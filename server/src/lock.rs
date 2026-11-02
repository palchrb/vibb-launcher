//! The app lock, "Lås med Face ID" (design 74 §3, step 3). A session whose parent turned it on in this browser locks
//! once the app has gone unused for its minutes; a passkey of the signed-in parent's opens it again (`/lock`,
//! `handlers::lock`). Enforced here, on the server: `security::admin_or_why` - the one place `require_full_auth`,
//! `require_session` and `/api/session` read a session through - asks [check] for every request but the ones in
//! [EXEMPT]; a locked navigation is sent to `/lock?next=<path?query>` (303), anything else answers 423 with no body.
//! The page's own side (`static/app-lock.js`) only keeps anything private from flashing up first.
//!
//! - **The setting** (D3.1): the session's [MINUTES_KEY] (1, 5, 15 or 60; none = off) and the cookie [cookie_name]
//!   (the minutes only, 400 days), which a new sign-in in this browser copies into its session ([carry]) only while
//!   that parent has a passkey for this RP ID - a lock that could never be opened stays off.
//! - **"Uten bruk"** (D3.3): the session's [USED_AT_KEY] (Unix seconds), moved only by a user-activated navigation
//!   (`Sec-Fetch-User: ?1`), a save (any unsafe method) and the page's beacon (`POST /api/used`, [BEACON_ROUTE]); a
//!   scripted reload and a script's GET never move it. Written only when it moved [MOVE_AFTER_SECS] or more (no
//!   session write per poll), the beacon always (it comes at most once a minute). The server locks at
//!   `used_at + minutes + 1 min` ([SLACK_SECS], the beacon's minute), the page at its last input + minutes.
//! - The lock neither extends nor shortens the session (D3.7); an unlock cycles the session id.

use axum::extract::{MatchedPath, Request};
use axum::http::{HeaderMap, HeaderValue, Method, StatusCode, header};
use axum::response::{IntoResponse, Redirect, Response};
use tower_sessions::Session;

/// The session's lock time in minutes (none, or 0: off).
pub const MINUTES_KEY: &str = "lock_minutes";
/// The session's last use, Unix seconds.
pub const USED_AT_KEY: &str = "used_at";
/// The lock times (D3.1, the user 2026-11-02: no "at once").
pub const CHOICES: [u32; 4] = [1, 5, 15, 60];
/// The time a switched-on lock starts with (D3.1: "5 min selected first when turning it on").
pub const FIRST_CHOICE: u32 = 5;
/// The server's slack past the page's own lock: the beacon comes at most once a minute.
pub const SLACK_SECS: u64 = 60;
/// A navigation or a save moves `used_at` only when it moved this much (no session write per click).
pub const MOVE_AFTER_SECS: u64 = 30;
/// The cookie lives 400 days (the most a browser keeps).
pub const COOKIE_SECS: u64 = 400 * 86_400;
/// The lock screen and its challenge.
pub const LOCK_ROUTE: &str = "/lock";
pub const OPTIONS_ROUTE: &str = "/lock/options";
/// The page's input beacon (D3.3).
pub const BEACON_ROUTE: &str = "/api/used";

/// The routes a locked session still reaches, by matched route (D3.4) - everything else behind a session is locked,
/// the onboarding routes behind `require_session` too. The first three are the only ones that read a session; the
/// rest never do (signed out, a token of their own, or no session at all) and are listed so the router walk
/// (`tests::app_lock::every_route_is_locked_or_exempt`) can tell every route apart. `/static/*` (the manifest and its
/// icons included) is `ServeDir`, never a route, and the device API (`/api/devices/*`, bearer tokens) is
/// `device_routes`' alone.
pub const EXEMPT: &[&str] = &[
    LOCK_ROUTE,
    OPTIONS_ROUTE,
    "/logout",
    // Signed out: the sign-in steps (a locked session that signs in again is a new sign-in, password and code or a
    // passkey, which is more than the unlock asks).
    "/login",
    "/auth/verify-2fa",
    "/auth/passkey/options",
    "/auth/passkey",
    "/sw.js",
    "/healthz",
    // The service worker's receipt: its per-delivery token is its proof (design 35 §7).
    "/notifications/receipt",
    // A phone's picture by its hash, also a push's icon (no session).
    "/phone-avatars/{hash}",
    // Spotify's way back: no session (the Strict cookie isn't sent); `done` sends on to a locked page.
    "/music/spotify/callback",
    "/music/spotify/done",
];

/// Whether `minutes` is one of the lock's times.
pub fn valid_minutes(minutes: u32) -> bool {
    CHOICES.contains(&minutes)
}

/// The lock cookie's name (D3.1): `__Host-handy-lock` (Secure, `Path=/`, no `Domain`), or `handy-lock` with
/// `INSECURE_COOKIES` (a `__Host-` cookie must be Secure), as the session cookie.
pub fn cookie_name(insecure: bool) -> &'static str {
    if insecure {
        "handy-lock"
    } else {
        "__Host-handy-lock"
    }
}

/// Whether this server runs with `INSECURE_COOKIES` (its session cookie is the plain `handy`).
pub fn insecure(state: &crate::AppState) -> bool {
    state.session_cookie == crate::security::session_cookie_name(true)
}

/// The `Set-Cookie` that keeps `minutes` for this browser, or (0) deletes it.
pub fn cookie_header(insecure: bool, minutes: u32) -> HeaderValue {
    let secure = if insecure { "" } else { "; Secure" };
    let value = if minutes == 0 {
        format!(
            "{}=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict{secure}",
            cookie_name(insecure)
        )
    } else {
        format!(
            "{}={minutes}; Path=/; Max-Age={COOKIE_SECS}; HttpOnly; SameSite=Strict{secure}",
            cookie_name(insecure)
        )
    };
    HeaderValue::from_str(&value).expect("the lock cookie is ASCII")
}

/// `response` with the lock cookie set to `minutes` (0 deletes it).
pub fn with_cookie(mut response: Response, insecure: bool, minutes: u32) -> Response {
    response
        .headers_mut()
        .append(header::SET_COOKIE, cookie_header(insecure, minutes));
    response
}

/// The minutes the browser's lock cookie holds, when it is one of the lock's times.
pub fn cookie_minutes(headers: &HeaderMap, insecure: bool) -> Option<u32> {
    let name = cookie_name(insecure);
    headers
        .get_all(header::COOKIE)
        .iter()
        .filter_map(|value| value.to_str().ok())
        .flat_map(|value| value.split(';'))
        .find_map(|pair| {
            let (cookie, value) = pair.trim().split_once('=')?;
            (cookie == name).then(|| value.trim().parse::<u32>().ok())?
        })
        .filter(|minutes| valid_minutes(*minutes))
}

pub fn now_secs() -> u64 {
    crate::admin_auth::now_secs()
}

/// The session's lock: its minutes (0 = off) and its last use. A value that can't be read is a lock with no use -
/// locked (fail closed); a time that isn't one of [CHOICES] reads as off (nothing writes one).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Held {
    pub minutes: u32,
    pub used_at: Option<u64>,
}

impl Held {
    pub async fn of(session: &Session) -> Held {
        let minutes = match session.get_value(MINUTES_KEY).await {
            Ok(None) => 0,
            Ok(Some(value)) => match value.as_u64().and_then(|m| u32::try_from(m).ok()) {
                Some(m) if valid_minutes(m) => m,
                Some(0) => 0,
                // Not a time this server writes: the strictest one, so the lock stays on (fail closed).
                _ => CHOICES[0],
            },
            Err(_) => CHOICES[0],
        };
        let used_at = session.get::<u64>(USED_AT_KEY).await.ok().flatten();
        Held { minutes, used_at }
    }

    pub fn on(&self) -> bool {
        self.minutes > 0
    }

    /// Past its minutes and the slack at `now` (or never used: locked).
    pub fn due(&self, now: u64) -> bool {
        self.on()
            && self.used_at.is_none_or(|used| {
                now >= used.saturating_add(u64::from(self.minutes) * 60 + SLACK_SECS)
            })
    }
}

/// Turns the lock on (`minutes`) or off (0) in this session; on, its use starts now.
pub async fn set(session: &Session, minutes: u32) -> Result<(), tower_sessions::session::Error> {
    if minutes == 0 {
        session.remove_value(MINUTES_KEY).await?;
        session.remove_value(USED_AT_KEY).await?;
    } else {
        session.insert(MINUTES_KEY, minutes).await?;
        session.insert(USED_AT_KEY, now_secs()).await?;
    }
    Ok(())
}

/// A use now (an unlock, a sign-in): the session's `used_at` moves whatever it was.
pub async fn used_now(session: &Session) {
    session.insert(USED_AT_KEY, now_secs()).await.ok();
}

/// A new sign-in in this browser (D3.1): the lock cookie's minutes are copied into the session - only when the parent
/// has a passkey for this RP ID; otherwise the lock could never be opened, and the cookie is left alone.
pub async fn carry(
    state: &crate::AppState,
    session: &Session,
    headers: &HeaderMap,
    admin_id: i64,
    ceremony: &crate::passkeys::Ceremony,
) {
    let Some(minutes) = cookie_minutes(headers, insecure(state)) else {
        return;
    };
    if !crate::passkeys::has_here(&state.db, admin_id, ceremony).await {
        return;
    }
    if let Err(err) = set(session, minutes).await {
        tracing::warn!(%err, "couldn't carry the app lock into the new sign-in");
    }
}

/// The parent lost a passkey (a removal): with none left for this RP ID, this session's lock goes off - it could never
/// be opened (D3.1; the other sessions ended with the removal's epoch). Whether it did.
pub async fn off_without_passkey(
    state: &crate::AppState,
    session: &Session,
    admin_id: i64,
    ceremony: &crate::passkeys::Ceremony,
) -> bool {
    if !Held::of(session).await.on()
        || crate::passkeys::has_here(&state.db, admin_id, ceremony).await
    {
        return false;
    }
    set(session, 0).await.ok();
    true
}

/// What the lock needs to know of a request (D3.3, D3.4).
#[derive(Clone, Debug, Default)]
pub struct Facts {
    pub method: Method,
    /// The path and query (`/devices/3?push=ab`).
    pub target: String,
    /// The matched route (`/devices/{id}`), for [EXEMPT]; `None` checks it whatever it is (`/api/session`).
    pub matched: Option<String>,
    /// A navigation: `Sec-Fetch-Mode: navigate`, or - a browser without `Sec-Fetch-*` (Safari before 16.4) - an
    /// `Accept` that asks for HTML.
    pub navigation: bool,
    /// A navigation the parent made: a tapped link, a tab, a typed address (`Sec-Fetch-User: ?1`). A scripted reload
    /// is a navigation too but never carries it.
    pub user_activated: bool,
    /// The page's beacon ([BEACON_ROUTE]).
    pub beacon: bool,
}

impl Facts {
    pub fn of(request: &Request) -> Facts {
        let headers = request.headers();
        let header = |name: &str| headers.get(name).and_then(|v| v.to_str().ok());
        let mode = header("sec-fetch-mode");
        let navigation = match mode {
            Some(mode) => mode.eq_ignore_ascii_case("navigate"),
            None => header("accept").is_some_and(|accept| accept.contains("text/html")),
        };
        let matched = request
            .extensions()
            .get::<MatchedPath>()
            .map(|m| m.as_str().to_string());
        let target = request.uri().path_and_query().map_or_else(
            || request.uri().path().to_string(),
            |pq| pq.as_str().to_string(),
        );
        Facts {
            method: request.method().clone(),
            beacon: matched.as_deref() == Some(BEACON_ROUTE),
            target,
            matched,
            navigation,
            user_activated: navigation && header("sec-fetch-user") == Some("?1"),
        }
    }

    /// A request the parent made: a user-activated navigation or a save (any unsafe method; the beacon is one).
    pub fn is_use(&self) -> bool {
        self.user_activated || !matches!(self.method, Method::GET | Method::HEAD | Method::OPTIONS)
    }

    pub fn exempt(&self) -> bool {
        self.matched
            .as_deref()
            .is_some_and(|route| EXEMPT.contains(&route))
    }
}

/// An open lock's numbers for the page (`<html data-lock-ms data-lock-idle>`, `static/app-lock.js`): the lock time
/// and how long ago the session was last used, both in milliseconds (relative, so the phone's clock doesn't matter).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Shown {
    pub lock_ms: u64,
    pub idle_ms: u64,
}

/// The session is locked: the request isn't served.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Locked;

/// The lock's check for one request of a signed-in session: `Err(Locked)` past its time; otherwise the session's use
/// moved when the request is one ([Facts::is_use]) and, while the lock is on, its numbers for the page. An exempt
/// route is neither checked nor counted.
pub async fn check(session: &Session, facts: &Facts) -> Result<Option<Shown>, Locked> {
    let held = Held::of(session).await;
    if !held.on() || facts.exempt() {
        return Ok(None);
    }
    let now = now_secs();
    if held.due(now) {
        return Err(Locked);
    }
    let mut used = held.used_at.unwrap_or(now);
    let moved = now.saturating_sub(used);
    if facts.is_use() && (facts.beacon || moved >= MOVE_AFTER_SECS) {
        match session.insert(USED_AT_KEY, now).await {
            Ok(()) => used = now,
            Err(err) => tracing::warn!(%err, "couldn't move the session's last use"),
        }
    }
    Ok(Some(Shown {
        lock_ms: u64::from(held.minutes) * 60_000,
        idle_ms: now.saturating_sub(used) * 1000,
    }))
}

/// A local path to go on to (`localPath`'s rule - `templates/sw.js`, `parent_events::local_target`): one leading `/`
/// (never `//` or `/\`), printable ASCII only (it goes into a `Location`), at most 512 bytes, and never the lock, the
/// sign-in or the sign-out themselves. Anything else is `None` (no open redirect).
pub fn local_next(raw: &str) -> Option<String> {
    let raw = raw.trim();
    if raw.is_empty() || raw.len() > 512 {
        return None;
    }
    if !raw.starts_with('/') || raw.starts_with("//") || raw.starts_with("/\\") {
        return None;
    }
    if !raw.bytes().all(|b| (0x21..0x7f).contains(&b) && b != b'\\') {
        return None;
    }
    let path = raw.split(['?', '#']).next().unwrap_or("");
    let own = |route: &str| path == route || path.starts_with(&format!("{route}/"));
    if own(LOCK_ROUTE) || own("/login") || own("/logout") || own("/auth") {
        return None;
    }
    Some(raw.to_string())
}

/// `/lock`, with `?next=` when there is a local one.
pub fn lock_url(next: Option<&str>) -> String {
    match next.and_then(local_next) {
        Some(next) => format!(
            "{LOCK_ROUTE}?{}",
            form_urlencoded::Serializer::new(String::new())
                .append_pair("next", &next)
                .finish()
        ),
        None => LOCK_ROUTE.to_string(),
    }
}

/// A locked request's answer (D3.4): a navigation goes to the lock screen (303 `/lock?next=<path?query>` - for a GET;
/// a form's POST loses what was sent and comes back to the start, since the page locks first), anything else is 423
/// with no body. `no-store` either way.
pub fn locked_response(facts: &Facts) -> Response {
    let mut response = if facts.navigation {
        let next =
            matches!(facts.method, Method::GET | Method::HEAD).then_some(facts.target.as_str());
        Redirect::to(&lock_url(next)).into_response()
    } else {
        StatusCode::LOCKED.into_response()
    };
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
}

/// `html` with ` data-lock-ms="…" data-lock-idle="…"` after its first `<html` (unchanged without one).
pub fn mark_html(html: &[u8], shown: Shown) -> Vec<u8> {
    const HTML: &[u8] = b"<html";
    let mark = format!(
        " data-lock-ms=\"{}\" data-lock-idle=\"{}\"",
        shown.lock_ms, shown.idle_ms
    );
    match html.windows(HTML.len()).position(|w| w == HTML) {
        Some(at) => {
            let split = at + HTML.len();
            let mut out = Vec::with_capacity(html.len() + mark.len());
            out.extend_from_slice(&html[..split]);
            out.extend_from_slice(mark.as_bytes());
            out.extend_from_slice(&html[split..]);
            out
        }
        None => html.to_vec(),
    }
}

/// An HTML answer of a lock-on session, marked for `static/app-lock.js` ([mark_html]); any other answer as it is.
pub async fn mark_page(response: Response, shown: Option<Shown>) -> Response {
    let Some(shown) = shown else {
        return response;
    };
    let html = response
        .headers()
        .get(header::CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        .is_some_and(|value| value.starts_with("text/html"));
    if !html {
        return response;
    }
    let (mut parts, body) = response.into_parts();
    let bytes = match axum::body::to_bytes(body, usize::MAX).await {
        Ok(bytes) => bytes,
        Err(err) => {
            tracing::error!(%err, "couldn't read a page to mark its lock");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    parts.headers.remove(header::CONTENT_LENGTH);
    Response::from_parts(parts, axum::body::Body::from(mark_html(&bytes, shown)))
}

/// An unlock in the security log (D3.5, the user's Q6): one `app_unlocked` row per parent and hour, "×N" past the
/// first. One statement adds to the hour's row; only without one is a row written.
pub async fn record_unlocked(db: &sqlx::SqlitePool, username: &str, ip: Option<&str>) {
    let merged = sqlx::query(
        "UPDATE security_events SET detail = '×' || (COALESCE(CAST(substr(detail, 2) AS INTEGER), 1) + 1) \
         WHERE id = (SELECT id FROM security_events WHERE event_type = 'app_unlocked' AND username = ? \
           AND created_at > datetime('now', '-1 hour') ORDER BY id DESC LIMIT 1)",
    )
    .bind(username)
    .execute(db)
    .await;
    match merged {
        Ok(done) if done.rows_affected() > 0 => {}
        _ => {
            crate::security::record_security_event(db, "app_unlocked", Some(username), ip, None)
                .await
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn next_is_a_local_path_only() {
        for good in [
            "/",
            "/devices",
            "/devices/3?push=ab12",
            "/account#app-lock",
            "/music?x=%2F%2Fevil",
        ] {
            assert_eq!(local_next(good).as_deref(), Some(good), "{good}");
        }
        for bad in [
            "",
            "devices",
            "//evil.example",
            "/\\evil.example",
            "/a\\b",
            "https://evil.example/",
            "/devices\r\nSet-Cookie: x=1",
            "/dévices",
            "/lock",
            "/lock?next=/devices",
            "/lock/options",
            "/login",
            "/login?next=/x",
            "/logout",
            "/auth/passkey",
        ] {
            assert_eq!(local_next(bad), None, "{bad:?}");
        }
        assert_eq!(local_next(&format!("/{}", "a".repeat(600))), None);
        // `/locks` is a page of its own, not the lock.
        assert!(local_next("/locks").is_some());
    }

    #[test]
    fn the_lock_url_carries_a_local_next_only() {
        assert_eq!(
            lock_url(Some("/devices/3?push=ab")),
            "/lock?next=%2Fdevices%2F3%3Fpush%3Dab"
        );
        assert_eq!(lock_url(Some("//evil.example")), "/lock");
        assert_eq!(lock_url(None), "/lock");
    }

    #[test]
    fn the_lock_is_due_past_its_minutes_and_the_slack() {
        let held = Held {
            minutes: 5,
            used_at: Some(1_000),
        };
        assert!(!held.due(1_000 + 5 * 60));
        assert!(!held.due(1_000 + 5 * 60 + SLACK_SECS - 1));
        assert!(held.due(1_000 + 5 * 60 + SLACK_SECS));
        // Never used: locked (fail closed); off: never.
        assert!(
            Held {
                minutes: 5,
                used_at: None
            }
            .due(0)
        );
        assert!(
            !Held {
                minutes: 0,
                used_at: None
            }
            .due(u64::MAX)
        );
    }

    #[test]
    fn the_cookie_holds_a_lock_time_only() {
        let mut headers = HeaderMap::new();
        headers.insert(
            header::COOKIE,
            HeaderValue::from_static("handy=abc; handy-lock=15"),
        );
        assert_eq!(cookie_minutes(&headers, true), Some(15));
        assert_eq!(cookie_minutes(&headers, false), None);
        headers.insert(header::COOKIE, HeaderValue::from_static("handy-lock=7"));
        assert_eq!(cookie_minutes(&headers, true), None);
        let set = cookie_header(false, 5);
        let set = set.to_str().unwrap();
        assert!(set.starts_with("__Host-handy-lock=5; Path=/;"), "{set}");
        for part in ["HttpOnly", "SameSite=Strict", "Secure", "Max-Age=34560000"] {
            assert!(set.contains(part), "{set}");
        }
        assert!(
            cookie_header(true, 0)
                .to_str()
                .unwrap()
                .contains("Max-Age=0")
        );
    }

    #[test]
    fn the_page_mark_goes_on_the_html_tag() {
        let marked = mark_html(
            b"<!DOCTYPE html>\n<html lang=\"nb\"><body></body></html>",
            Shown {
                lock_ms: 300_000,
                idle_ms: 2_000,
            },
        );
        assert_eq!(
            String::from_utf8(marked).unwrap(),
            "<!DOCTYPE html>\n<html data-lock-ms=\"300000\" data-lock-idle=\"2000\" lang=\"nb\"><body></body></html>"
        );
    }
}
