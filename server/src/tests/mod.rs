//! In-process tests: the real router from `build_admin_router`, a fresh migrated SQLite database per
//! test (a temp file, so WAL mode and multiple pool connections behave as in production), and
//! requests sent straight to the router - no network, no background tasks.

mod admin_auth;
mod app_display;
mod backup_secrets;
mod calls;
mod catalog_pins;
mod cleanup;
mod command_expiry;
mod command_receipts;
mod deploy_scripts;
mod device_api;
mod dns_blocklist;
mod docker;
mod edge;
mod enrollment;
mod hardening;
mod intake_limits;
mod launcher_ui;
mod listeners;
mod locations;
mod lock_glance;
mod music;
mod music_art;
mod music_spotify;
mod music_storytel;
mod music_sweep;
mod parent_pin;
mod play;
mod provisioning;
mod shell;
mod sound_mode;
mod step10;
mod step11;
mod step9;
mod time_rules;
mod tracked_apps;
mod wallpapers;
mod window_relock;

use axum::Router;
use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{HeaderMap, Method, Request, StatusCode, header};
use http_body_util::BodyExt;
use sqlx::SqlitePool;
use tower::ServiceExt;
use tower_sessions_sqlx_store::SqliteStore;

use crate::config::ForkConfig;
use crate::{AppState, build_admin_router, connect_db, dns_engine, security};

pub struct TestApp {
    pub router: Router,
    /// The same state the router was built with - lets tests call an admin handler directly
    /// (skipping the session/2FA middleware) or build a second router with different config.
    pub state: AppState,
    pub db: SqlitePool,
    /// Every music source fetch's canned answers (designs 21, 21b): `fetch.answer(url, status,
    /// body)`.
    pub fetch: std::sync::Arc<music::CannedFetch>,
    _dir: tempfile::TempDir,
}

/// The peer every plain test request comes from.
pub const LOOPBACK_PEER: std::net::SocketAddr =
    std::net::SocketAddr::new(std::net::IpAddr::V4(std::net::Ipv4Addr::LOCALHOST), 40000);

pub struct TestResponse {
    pub status: StatusCode,
    pub headers: HeaderMap,
    pub body: Vec<u8>,
}

impl TestResponse {
    pub fn json(&self) -> serde_json::Value {
        serde_json::from_slice(&self.body).unwrap_or_else(|e| {
            panic!(
                "response is not JSON ({e}): {}",
                String::from_utf8_lossy(&self.body)
            )
        })
    }
}

/// Status and body of a response from a handler called directly (see `TestApp::state`).
pub async fn read_response(response: axum::response::Response) -> TestResponse {
    let status = response.status();
    let headers = response.headers().clone();
    let body = response
        .into_body()
        .collect()
        .await
        .expect("failed to read body")
        .to_bytes()
        .to_vec();
    TestResponse {
        status,
        headers,
        body,
    }
}

impl TestResponse {
    pub fn text(&self) -> String {
        String::from_utf8_lossy(&self.body).into_owned()
    }

    /// The `Location` header of a redirect.
    pub fn location(&self) -> Option<&str> {
        self.headers
            .get(header::LOCATION)
            .and_then(|v| v.to_str().ok())
    }
}

impl TestApp {
    pub async fn new() -> Self {
        Self::with_net(crate::net::NetConfig::default()).await
    }

    /// A test app whose listeners are configured like `vars` (`BIND_ADDR`, `ADMIN_PUBLIC`, ...,
    /// design 22 §2).
    pub async fn with_env(vars: &[(&str, &str)]) -> Self {
        let vars = vars
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect();
        Self::with_net(crate::net::NetConfig::from_vars(&vars)).await
    }

    pub async fn with_net(net: crate::net::NetConfig) -> Self {
        // As `main` does before anything opens a connection: handlers that build a reqwest
        // client (the DNS upstream switch refreshes the blocklists) need the process-wide rustls
        // provider. The FCM tests used to install it as a side effect (design 19).
        let _ = rustls::crypto::ring::default_provider().install_default();
        let dir = tempfile::tempdir().expect("failed to create temp dir");
        let url = format!("sqlite://{}", dir.path().join("test.db").display());
        let db = connect_db(&url).await;

        let session_store = SqliteStore::new(db.clone());
        session_store
            .migrate()
            .await
            .expect("failed to run session store migrations");
        // Plain HTTP in tests, so cookies must not be Secure-only (`INSECURE_COOKIES`: the
        // cookie is a plain `handy`).
        let session_layer = crate::security::session_layer(session_store, true, 30);

        let (command_notify, _) = tokio::sync::broadcast::channel(64);
        let fetch = std::sync::Arc::new(music::CannedFetch::default());
        let state = AppState {
            db: db.clone(),
            dns_compiled: dns_engine::empty_compiled_blocklist(),
            command_notify,
            config: std::sync::Arc::new(ForkConfig::for_tests()),
            photo_dir: std::sync::Arc::new(dir.path().join("contact_photos")),
            dns_feed_dir: std::sync::Arc::new(dir.path().join("dns_feeds")),
            wallpaper_dir: std::sync::Arc::new(dir.path().join("wallpapers")),
            command_streams: Default::default(),
            app_syncs: Default::default(),
            tracked_apps_dir: std::sync::Arc::new(dir.path().join("tracked_apps")),
            music_files_dir: std::sync::Arc::new(dir.path().join("music_files")),
            music_cover_dir: std::sync::Arc::new(dir.path().join("music_covers")),
            music_art_dir: std::sync::Arc::new(dir.path().join("music_art")),
            music_key: Some(std::sync::Arc::new(
                crate::music_secret::MusicKey::from_bytes(&[42; 32]),
            )),
            // The real gate (one request at a time), without the 200 ms spacing.
            music_fetch: std::sync::Arc::new(crate::music_net::Gated::new(
                fetch.clone(),
                std::time::Duration::ZERO,
            )),
            music_sweep: Default::default(),
            music_libraries: Default::default(),
            storytel: Default::default(),
            spotify: Default::default(),
            net: std::sync::Arc::new(net),
            audit: Default::default(),
            limits: Default::default(),
            tokens: Default::default(),
            enrollment: Default::default(),
            passwords: Default::default(),
        };

        TestApp {
            router: build_admin_router(state.clone(), session_layer),
            state,
            db,
            fetch,
            _dir: dir,
        }
    }

    pub async fn request(
        &self,
        method: Method,
        uri: &str,
        bearer: Option<&str>,
        json: Option<serde_json::Value>,
    ) -> TestResponse {
        let mut builder = Request::builder().method(method).uri(uri);
        if let Some(token) = bearer {
            builder = builder.header(header::AUTHORIZATION, format!("Bearer {token}"));
        }
        let body = match json {
            Some(value) => {
                builder = builder.header(header::CONTENT_TYPE, "application/json");
                Body::from(value.to_string())
            }
            None => Body::empty(),
        };
        self.send(builder.body(body).expect("failed to build request"))
            .await
    }

    /// Sends a request through the router as a real connection would: with a fixed loopback
    /// `ConnectInfo` (handlers that rate-limit by client address extract it).
    async fn send(&self, request: Request<Body>) -> TestResponse {
        self.send_via(&self.router, LOOPBACK_PEER, request).await
    }

    /// Sends a request through `router` (the admin router, or [TestApp::device_router]) from the
    /// TCP peer `peer`.
    pub async fn send_via(
        &self,
        router: &Router,
        peer: std::net::SocketAddr,
        request: Request<Body>,
    ) -> TestResponse {
        read_response(self.call_via(router, peer, request).await).await
    }

    /// [TestApp::send_via] without reading the body (a stream).
    pub async fn call_via(
        &self,
        router: &Router,
        peer: std::net::SocketAddr,
        mut request: Request<Body>,
    ) -> axum::response::Response {
        request.extensions_mut().insert(ConnectInfo(peer));
        router
            .clone()
            .oneshot(request)
            .await
            .expect("router returned an error")
    }

    /// The admin listener's router on a changed copy of the state (another config), with its own
    /// session layer on the same database.
    pub fn admin_router(&self, state: AppState) -> Router {
        let store = SqliteStore::new(self.db.clone());
        build_admin_router(state, crate::security::session_layer(store, true, 30))
    }

    /// The phone listener's router (`DEVICE_BIND_ADDR`) on the same state.
    pub fn device_router(&self) -> Router {
        crate::build_device_router(self.state.clone())
    }

    /// A urlencoded form POST (or other method) with an optional session cookie, as a browser
    /// sends the admin UI's forms. Repeated keys are sent repeatedly.
    pub async fn request_form(
        &self,
        method: Method,
        uri: &str,
        cookie: Option<&str>,
        fields: &[(&str, &str)],
    ) -> TestResponse {
        let body = form_urlencoded::Serializer::new(String::new())
            .extend_pairs(fields)
            .finish();
        let mut builder = Request::builder()
            .method(method)
            .uri(uri)
            .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded");
        if let Some(cookie) = cookie {
            builder = builder.header(header::COOKIE, cookie);
        }
        self.send(
            builder
                .body(Body::from(body))
                .expect("failed to build request"),
        )
        .await
    }

    /// A GET with a session cookie (an admin page).
    pub async fn get_page(&self, uri: &str, cookie: &str) -> TestResponse {
        let request = Request::builder()
            .uri(uri)
            .header(header::COOKIE, cookie)
            .body(Body::empty())
            .expect("failed to build request");
        self.send(request).await
    }

    /// Logs in through the real `/login` and `/auth/verify-2fa` forms as a fully onboarded admin
    /// (password changed, TOTP enrolled with a fixed secret) and returns the session cookie
    /// (`name=value`) for `request_form`/`get_page`, so admin routes run behind the real
    /// session/2FA middleware.
    pub async fn admin_cookie(&self) -> String {
        const USERNAME: &str = "parent";
        const PASSWORD: &str = "correct horse battery staple";
        const TOTP_SECRET: &str = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";
        sqlx::query(
            "INSERT OR IGNORE INTO admin_users \
             (username, password_hash, must_change_password, totp_secret, totp_enabled) \
             VALUES (?, ?, 0, ?, 1)",
        )
        .bind(USERNAME)
        .bind(security::hash_password(PASSWORD))
        .bind(TOTP_SECRET)
        .execute(&self.db)
        .await
        .expect("failed to seed the admin user");

        let login = self
            .request_form(
                Method::POST,
                "/login",
                None,
                &[("username", USERNAME), ("password", PASSWORD)],
            )
            .await;
        assert_eq!(
            login.location(),
            Some("/auth/verify-2fa"),
            "{}",
            login.text()
        );
        let cookie = session_cookie(&login).expect("login set no session cookie");

        let code = self.next_totp_code(USERNAME, TOTP_SECRET).await;
        let verify = self
            .request_form(
                Method::POST,
                "/auth/verify-2fa",
                Some(&cookie),
                &[("code", &code)],
            )
            .await;
        assert_eq!(verify.location(), Some("/"), "{}", verify.text());
        session_cookie(&verify).unwrap_or(cookie)
    }

    /// A TOTP code the server accepts now: for the current step, or a later one when that step
    /// was already used (a code works once - design 22 §3.2.1), waiting for it if needed.
    pub async fn next_totp_code(&self, username: &str, secret: &str) -> String {
        let last: Option<i64> =
            sqlx::query_scalar("SELECT totp_last_step FROM admin_users WHERE username = ?")
                .bind(username)
                .fetch_one(&self.db)
                .await
                .expect("no such admin");
        loop {
            let now_step = (crate::admin_auth::now_secs() / 30) as i64;
            let step = last.map_or(now_step, |last| now_step.max(last + 1));
            if step <= now_step + 1 {
                return security::totp_for_secret(secret, username).generate(step as u64 * 30);
            }
            tokio::time::sleep(std::time::Duration::from_millis(500)).await;
        }
    }

    /// Inserts a device the way `handlers::devices::create_device` does (kiosk on, policy row
    /// present) and gives it a setup-QR enrollment code (design 22: stored hashed, valid 30
    /// minutes), returning `(device_id, enrollment_code)`.
    pub async fn create_device(&self, name: &str) -> (i64, String) {
        let id: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES (?) RETURNING id")
            .bind(name)
            .fetch_one(&self.db)
            .await
            .expect("failed to insert device");
        sqlx::query("INSERT INTO device_policy (device_id, kiosk_desired) VALUES (?, 1)")
            .bind(id)
            .execute(&self.db)
            .await
            .expect("failed to insert device policy");
        let code = self.new_code(id, crate::enrollment::Kind::Qr).await;
        (id, code)
    }

    /// A new enrollment code of `kind` for a device, as the device page or the QR page makes one.
    pub async fn new_code(&self, device_id: i64, kind: crate::enrollment::Kind) -> String {
        crate::enrollment::new_code(&self.state, device_id, kind)
            .await
            .expect("failed to make an enrollment code")
            .0
    }

    /// Creates and enrolls a device, returning `(device_id, bearer_token)`.
    pub async fn enrolled_device(&self, name: &str) -> (i64, String) {
        let (id, code) = self.create_device(name).await;
        let res = self
            .request(
                Method::POST,
                "/api/devices/enroll",
                None,
                Some(serde_json::json!({ "enrollment_code": code })),
            )
            .await;
        assert_eq!(res.status, StatusCode::OK, "enrollment failed");
        let token = res.json()["device_token"]
            .as_str()
            .expect("no device_token in enroll response")
            .to_string();
        (id, token)
    }
}

/// `name=value` of the first `Set-Cookie` header, if any.
fn session_cookie(response: &TestResponse) -> Option<String> {
    response
        .headers
        .get(header::SET_COOKIE)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.split(';').next())
        .map(str::to_string)
}

// ------------------------------------------------------------------------------------------------
// Page markup helpers (design 31 §5.5, M8). Tests assert on what a control *is* - checked, saving
// itself, posting where - never on attribute order or whitespace, so a restyled switch
// (`class="sw" role="switch"`) or a moved handler (`data-autosave`) changes no test.
// ------------------------------------------------------------------------------------------------

/// One start tag: where it starts in the page and its attributes in order (a bare attribute has
/// no value). Values are the raw text between the quotes, still HTML-escaped.
pub struct Tag<'a> {
    pub start: usize,
    pub attrs: Vec<(&'a str, Option<&'a str>)>,
}

impl<'a> Tag<'a> {
    pub fn attr(&self, name: &str) -> Option<&'a str> {
        self.attrs
            .iter()
            .find(|(key, _)| key.eq_ignore_ascii_case(name))
            .map(|(_, value)| value.unwrap_or(""))
    }

    pub fn has(&self, name: &str) -> bool {
        self.attrs
            .iter()
            .any(|(key, _)| key.eq_ignore_ascii_case(name))
    }
}

/// Every `<name ...>` start tag in `html`, in page order.
pub fn tags<'a>(html: &'a str, name: &str) -> Vec<Tag<'a>> {
    let open = format!("<{name}");
    let mut found = Vec::new();
    let mut from = 0;
    while let Some(at) = html[from..].find(&open).map(|i| from + i) {
        from = at + open.len();
        let rest = &html[from..];
        // `<input` must not match `<inputs`: the name ends at whitespace, `/` or `>`.
        if !rest.starts_with(|c: char| c.is_ascii_whitespace() || c == '>' || c == '/') {
            continue;
        }
        let (attrs, len) = parse_attrs(rest);
        found.push(Tag { start: at, attrs });
        from += len;
    }
    found
}

/// The attributes up to the tag's `>` (quotes respected) and how many bytes that took.
type Attrs<'a> = Vec<(&'a str, Option<&'a str>)>;

fn parse_attrs(text: &str) -> (Attrs<'_>, usize) {
    let bytes = text.as_bytes();
    let mut attrs = Vec::new();
    let mut i = 0;
    loop {
        while i < bytes.len() && (bytes[i].is_ascii_whitespace() || bytes[i] == b'/') {
            i += 1;
        }
        if i >= bytes.len() || bytes[i] == b'>' {
            return (attrs, (i + 1).min(bytes.len()));
        }
        let key_start = i;
        while i < bytes.len()
            && !matches!(bytes[i], b'=' | b'>' | b'/')
            && !bytes[i].is_ascii_whitespace()
        {
            i += 1;
        }
        let key = &text[key_start..i];
        if i < bytes.len() && bytes[i] == b'=' {
            i += 1;
            match bytes.get(i).copied() {
                Some(quote @ (b'"' | b'\'')) => {
                    let value_start = i + 1;
                    let end = text[value_start..]
                        .find(quote as char)
                        .map_or(text.len(), |e| value_start + e);
                    attrs.push((key, Some(&text[value_start..end])));
                    i = (end + 1).min(bytes.len());
                }
                _ => {
                    let value_start = i;
                    while i < bytes.len() && bytes[i] != b'>' && !bytes[i].is_ascii_whitespace() {
                        i += 1;
                    }
                    attrs.push((key, Some(&text[value_start..i])));
                }
            }
        } else {
            attrs.push((key, None));
        }
    }
}

/// The form controls (`input`, `select`, `textarea`) named `name`, in page order.
fn controls<'a>(html: &'a str, name: &str) -> Vec<Tag<'a>> {
    let mut found: Vec<Tag<'a>> = ["input", "select", "textarea"]
        .iter()
        .flat_map(|kind| tags(html, kind))
        .filter(|tag| tag.attr("name") == Some(name))
        .collect();
    found.sort_by_key(|tag| tag.start);
    found
}

/// Whether the first `<input>` named `name` - with `value`, unless `value` is empty - is
/// checked; `None` when there is no such input. Slice `html` to one form or card first when the
/// name repeats.
pub fn input_checked(html: &str, name: &str, value: &str) -> Option<bool> {
    tags(html, "input")
        .into_iter()
        .find(|tag| {
            tag.attr("name") == Some(name) && (value.is_empty() || tag.attr("value") == Some(value))
        })
        .map(|tag| tag.has("checked"))
}

/// Whether a control named `name` saves its form when it changes: `data-autosave` (handled by
/// `static/autosave.js`), or an inline `onchange` that submits.
pub fn autosaves(html: &str, name: &str) -> bool {
    controls(html, name).iter().any(|tag| {
        tag.has("data-autosave") || tag.attr("onchange").is_some_and(|js| js.contains("submit"))
    })
}

/// The `action` of the form that holds the first control named `input_name`.
pub fn form_action_of<'a>(html: &'a str, input_name: &str) -> Option<&'a str> {
    let control = controls(html, input_name).into_iter().next()?;
    let form = tags(&html[..control.start], "form").into_iter().last()?;
    // The form must still be open at the control.
    if html[form.start..control.start].contains("</form>") {
        return None;
    }
    form.attr("action")
}

/// The tab the tab bar marks `aria-current="page"` (`devices`, `apps`, `locate`, `music`,
/// `settings`), or "" when none is (design 31 §3.1).
pub fn nav_current(html: &str) -> &str {
    let Some(start) = html.find("<nav class=\"tab-bar\"") else {
        return "";
    };
    let nav = &html[start..];
    let nav = &nav[..nav.find("</nav>").unwrap_or(nav.len())];
    tags(nav, "a")
        .into_iter()
        .find(|tag| tag.attr("aria-current") == Some("page"))
        .map_or("", |tag| tag.attr("data-tab").unwrap_or(""))
}

/// A phone's page in the PWA. Design 31 moves the device page's cards to sub-pages one step at a
/// time (§8); a test names the section, so a move changes this table, not the test.
#[derive(Clone, Copy, Debug)]
pub enum Section {
    /// `/devices/{id}`: the device page (the summary from design 31 step 10).
    Summary,
    /// `/devices/{id}/calls`: Calls & Contacts.
    Calls,
}

pub fn device_page(id: i64, section: Section) -> String {
    match section {
        Section::Summary => format!("/devices/{id}"),
        Section::Calls => format!("/devices/{id}/calls"),
    }
}

/// A redirect (3xx) to `path` (with its query) and `fragment` (`None`: no `#` at all) - the
/// redirect table's check (design 31 §5.1).
#[track_caller]
pub fn assert_redirect(response: &TestResponse, path: &str, fragment: Option<&str>) {
    assert!(
        response.status.is_redirection(),
        "expected a redirect to {path}, got {}: {}",
        response.status,
        response.text()
    );
    let location = response.location().expect("a redirect without Location");
    let (got_path, got_fragment) = match location.split_once('#') {
        Some((p, f)) => (p, Some(f)),
        None => (location, None),
    };
    assert_eq!(got_path, path, "redirect path (Location: {location})");
    assert_eq!(
        got_fragment, fragment,
        "redirect fragment (Location: {location})"
    );
}
