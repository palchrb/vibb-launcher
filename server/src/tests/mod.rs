//! In-process tests: the real router from `build_router`, a fresh migrated SQLite database per
//! test (a temp file, so WAL mode and multiple pool connections behave as in production), and
//! requests sent straight to the router - no network, no background tasks.

mod app_display;
mod calls;
mod cleanup;
mod device_api;
mod hardening;
mod launcher_ui;
mod music;
mod play;
mod provisioning;
mod sound_mode;
mod step10;
mod step11;
mod step9;
mod time_rules;
mod tracked_apps;
mod wallpapers;

use axum::Router;
use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{HeaderMap, Method, Request, StatusCode, header};
use http_body_util::BodyExt;
use sqlx::SqlitePool;
use tower::ServiceExt;
use tower_sessions::SessionManagerLayer;
use tower_sessions_sqlx_store::SqliteStore;

use crate::config::ForkConfig;
use crate::{AppState, build_router, connect_db, dns_engine, security};

pub struct TestApp {
    pub router: Router,
    /// The same state the router was built with - lets tests call an admin handler directly
    /// (skipping the session/2FA middleware) or build a second router with different config.
    pub state: AppState,
    pub db: SqlitePool,
    /// The music add check's canned answers (design 21): `fetch.answer(url, status, body)`.
    pub fetch: std::sync::Arc<music::CannedFetch>,
    _dir: tempfile::TempDir,
}

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
        // Plain HTTP in tests, so cookies must not be Secure-only.
        let session_layer = SessionManagerLayer::new(session_store).with_secure(false);

        let (command_notify, _) = tokio::sync::broadcast::channel(64);
        let fetch = std::sync::Arc::new(music::CannedFetch::default());
        let state = AppState {
            db: db.clone(),
            dns_compiled: dns_engine::empty_compiled_blocklist(),
            command_notify,
            config: std::sync::Arc::new(ForkConfig::for_tests()),
            photo_dir: std::sync::Arc::new(dir.path().join("contact_photos")),
            wallpaper_dir: std::sync::Arc::new(dir.path().join("wallpapers")),
            command_streams: Default::default(),
            app_syncs: Default::default(),
            tracked_apps_dir: std::sync::Arc::new(dir.path().join("tracked_apps")),
            music_files_dir: std::sync::Arc::new(dir.path().join("music_files")),
            music_cover_dir: std::sync::Arc::new(dir.path().join("music_covers")),
            music_key: Some(std::sync::Arc::new(
                crate::music_secret::MusicKey::from_bytes(&[42; 32]),
            )),
            music_fetch: fetch.clone(),
        };

        TestApp {
            router: build_router(state.clone(), session_layer),
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
    async fn send(&self, mut request: Request<Body>) -> TestResponse {
        request
            .extensions_mut()
            .insert(ConnectInfo(std::net::SocketAddr::from((
                [127, 0, 0, 1],
                40000,
            ))));
        let response = self
            .router
            .clone()
            .oneshot(request)
            .await
            .expect("router returned an error");
        read_response(response).await
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

        let code = security::totp_for_secret(TOTP_SECRET, USERNAME)
            .generate_current()
            .expect("system clock before 1970");
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

    /// Inserts a device the way `handlers::devices::create_device` does (kiosk on, policy row
    /// present) with a valid enrollment code, and returns `(device_id, enrollment_code)`.
    pub async fn create_device(&self, name: &str) -> (i64, String) {
        let code = format!("CODE-{name}");
        let id: i64 = sqlx::query_scalar(
            "INSERT INTO devices (name, enrollment_code, enrollment_code_expires_at) \
             VALUES (?, ?, datetime('now', '+15 minutes')) RETURNING id",
        )
        .bind(name)
        .bind(&code)
        .fetch_one(&self.db)
        .await
        .expect("failed to insert device");
        sqlx::query("INSERT INTO device_policy (device_id, kiosk_desired) VALUES (?, 1)")
            .bind(id)
            .execute(&self.db)
            .await
            .expect("failed to insert device policy");
        (id, code)
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
