//! In-process tests: the real router from `build_router`, a fresh migrated SQLite database per
//! test (a temp file, so WAL mode and multiple pool connections behave as in production), and
//! requests sent straight to the router - no network, no background tasks.

mod device_api;
mod provisioning;

use axum::Router;
use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{Method, Request, StatusCode, header};
use http_body_util::BodyExt;
use sqlx::SqlitePool;
use tower::ServiceExt;
use tower_sessions::SessionManagerLayer;
use tower_sessions_sqlx_store::SqliteStore;

use crate::config::ForkConfig;
use crate::{AppState, build_router, connect_db, dns_engine};

pub struct TestApp {
    pub router: Router,
    /// The same state the router was built with - lets tests call an admin handler directly
    /// (skipping the session/2FA middleware) or build a second router with different config.
    pub state: AppState,
    pub db: SqlitePool,
    _dir: tempfile::TempDir,
}

pub struct TestResponse {
    pub status: StatusCode,
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
    let body = response
        .into_body()
        .collect()
        .await
        .expect("failed to read body")
        .to_bytes()
        .to_vec();
    TestResponse { status, body }
}

impl TestResponse {
    pub fn text(&self) -> String {
        String::from_utf8_lossy(&self.body).into_owned()
    }
}

impl TestApp {
    pub async fn new() -> Self {
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
        let state = AppState {
            db: db.clone(),
            dns_compiled: dns_engine::empty_compiled_blocklist(),
            command_notify,
            config: std::sync::Arc::new(ForkConfig::for_tests()),
        };

        TestApp {
            router: build_router(state.clone(), session_layer),
            state,
            db,
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
        let mut request = builder.body(body).expect("failed to build request");
        // Handlers that rate-limit by client address extract ConnectInfo, which a real server
        // inserts per connection.
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
        let status = response.status();
        let body = response
            .into_body()
            .collect()
            .await
            .expect("failed to read body")
            .to_bytes()
            .to_vec();
        TestResponse { status, body }
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
