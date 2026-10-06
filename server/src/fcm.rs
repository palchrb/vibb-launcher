//! Firebase Cloud Messaging sender (handy step 7, `docs/design/07-battery-fcm-play.md` in the handy
//! workspace). Sends one content-free, data-only "sync" nudge per call - the phone always re-fetches
//! its policy, so a forged or replayed push is at most one extra sync. Optional: without
//! `FCM_SERVICE_ACCOUNT_FILE` the module is off, the policy says `push.fcm_enabled: false` and
//! every phone keeps using the SSE stream.
//!
//! No Google SDK: the OAuth2 service-account flow is a self-signed RS256 JWT (`ring`) exchanged
//! at the key's `token_uri`, and the send is one `POST .../messages:send`. Every request has a
//! timeout (QA 07 #11) and nothing secret ever reaches a log: the private key, the JWT, the access
//! token and the phones' registration tokens all have redacting `Debug` impls or are only logged as
//! [token_hash].

use std::fmt;
use std::future::Future;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::sync::Arc;
use std::time::{Duration, Instant};

use base64::Engine;
use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use ring::signature::{RSA_PKCS1_SHA256, RsaKeyPair};
use serde::Deserialize;
use sha2::{Digest, Sha256};

pub const FCM_SCOPE: &str = "https://www.googleapis.com/auth/firebase.messaging";
pub const DEFAULT_FCM_BASE_URL: &str = "https://fcm.googleapis.com";
pub const DEFAULT_TOKEN_URI: &str = "https://oauth2.googleapis.com/token";

/// First 16 hex chars of SHA-256 over the token - what the policy's `push.fcm_token_hash` carries
/// and the only form a registration token is ever logged in.
pub fn token_hash(token: &str) -> String {
    let digest = Sha256::digest(token.as_bytes());
    hex::encode(digest)[..16].to_string()
}

/// A new random nonce for one send (`"n"` in the message), 16 hex chars. The phone reports the
/// last one it received (`push.last_nudge_id`), which is the health check's acknowledgement.
pub fn new_nonce() -> String {
    use argon2::password_hash::rand_core::{OsRng, RngCore};
    let mut bytes = [0u8; 8];
    OsRng.fill_bytes(&mut bytes);
    hex::encode(bytes)
}

// ---------------------------------------------------------------------------------------------
// Service account
// ---------------------------------------------------------------------------------------------

/// The parts of a Google service-account JSON key this module needs. `Debug` never prints the key.
pub struct ServiceAccount {
    pub project_id: String,
    pub client_email: String,
    pub token_uri: String,
    key_pair: RsaKeyPair,
}

impl fmt::Debug for ServiceAccount {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ServiceAccount")
            .field("project_id", &self.project_id)
            .field("client_email", &self.client_email)
            .field("token_uri", &self.token_uri)
            .field("private_key", &"<redacted>")
            .finish()
    }
}

#[derive(Debug)]
pub enum LoadError {
    Io(String),
    /// Group- or world-readable (any of mode `0o077`) - the key must be 0600.
    Permissions(u32),
    /// Inside the data directory, which backups zip and mirror.
    InsideDataDir(PathBuf),
    Json(String),
    Key(String),
}

impl fmt::Display for LoadError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            LoadError::Io(e) => write!(f, "can't read the key file: {e}"),
            LoadError::Permissions(mode) => write!(
                f,
                "the key file is readable by others (mode {mode:o}) - chmod 600 it and make the \
                 service user its owner"
            ),
            LoadError::InsideDataDir(dir) => write!(
                f,
                "the key file is inside the data directory {} (backups would copy it) - move it, \
                 e.g. to /etc/kid-phone-server/",
                dir.display()
            ),
            LoadError::Json(e) => write!(f, "not a service-account JSON key: {e}"),
            LoadError::Key(e) => write!(f, "the private key can't be used: {e}"),
        }
    }
}

#[derive(Deserialize)]
struct RawServiceAccount {
    project_id: String,
    client_email: String,
    private_key: String,
    #[serde(default)]
    token_uri: Option<String>,
}

/// Reads and checks a service-account key file: owner-only permissions, outside `data_dir`, a
/// usable RSA key. Any failure leaves FCM off (the caller logs the error).
pub fn load_service_account(path: &Path, data_dir: &Path) -> Result<ServiceAccount, LoadError> {
    let meta = std::fs::metadata(path).map_err(|e| LoadError::Io(e.to_string()))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = meta.permissions().mode() & 0o777;
        if mode & 0o077 != 0 {
            return Err(LoadError::Permissions(mode));
        }
    }
    #[cfg(not(unix))]
    let _ = meta;
    let canonical = std::fs::canonicalize(path).map_err(|e| LoadError::Io(e.to_string()))?;
    if let Ok(data) = std::fs::canonicalize(data_dir)
        && canonical.starts_with(&data)
    {
        return Err(LoadError::InsideDataDir(data));
    }
    let text = std::fs::read_to_string(path).map_err(|e| LoadError::Io(e.to_string()))?;
    parse_service_account(&text)
}

pub fn parse_service_account(json: &str) -> Result<ServiceAccount, LoadError> {
    let raw: RawServiceAccount =
        serde_json::from_str(json).map_err(|e| LoadError::Json(e.to_string()))?;
    if raw.project_id.is_empty() || raw.client_email.is_empty() {
        return Err(LoadError::Json(
            "project_id or client_email is empty".into(),
        ));
    }
    let der = pem_to_der(&raw.private_key).ok_or_else(|| LoadError::Key("not a PEM key".into()))?;
    let key_pair = RsaKeyPair::from_pkcs8(&der).map_err(|e| LoadError::Key(e.to_string()))?;
    Ok(ServiceAccount {
        project_id: raw.project_id,
        client_email: raw.client_email,
        token_uri: raw
            .token_uri
            .filter(|u| !u.is_empty())
            .unwrap_or_else(|| DEFAULT_TOKEN_URI.to_string()),
        key_pair,
    })
}

/// PEM -> DER: drop the `-----` armour lines, base64-decode the rest.
fn pem_to_der(pem: &str) -> Option<Vec<u8>> {
    let body: String = pem
        .lines()
        .map(str::trim)
        .filter(|l| !l.starts_with("-----"))
        .collect();
    STANDARD.decode(body).ok()
}

/// A signed JWT; `Debug` hides it.
pub struct SignedJwt(pub String);

impl fmt::Debug for SignedJwt {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("SignedJwt(<redacted>)")
    }
}

impl ServiceAccount {
    /// The self-signed assertion for the token exchange: RS256, valid one hour from `now_secs`.
    pub fn sign_jwt(&self, now_secs: i64) -> Result<SignedJwt, String> {
        let header = URL_SAFE_NO_PAD.encode(br#"{"alg":"RS256","typ":"JWT"}"#);
        let claims = serde_json::json!({
            "iss": self.client_email,
            "scope": FCM_SCOPE,
            "aud": self.token_uri,
            "iat": now_secs,
            "exp": now_secs + 3600,
        });
        let claims = URL_SAFE_NO_PAD.encode(claims.to_string());
        let signing_input = format!("{header}.{claims}");
        let mut signature = vec![0u8; self.key_pair.public().modulus_len()];
        self.key_pair
            .sign(
                &RSA_PKCS1_SHA256,
                &ring::rand::SystemRandom::new(),
                signing_input.as_bytes(),
                &mut signature,
            )
            .map_err(|_| "RSA signing failed".to_string())?;
        Ok(SignedJwt(format!(
            "{signing_input}.{}",
            URL_SAFE_NO_PAD.encode(signature)
        )))
    }

    /// DER `RSAPublicKey` of the key (tests verify signatures with it).
    #[cfg(test)]
    pub fn public_key_der(&self) -> Vec<u8> {
        self.key_pair.public().as_ref().to_vec()
    }
}

// ---------------------------------------------------------------------------------------------
// Error classes
// ---------------------------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FcmErrorClass {
    /// The registration token is gone or never belonged to this project: clear it, the phone
    /// falls back to SSE and re-registers. Carries the FCM error code.
    TokenDead(String),
    /// Our access token was refused: drop it and retry once.
    Unauthorized,
    /// Quota or server trouble: retry later (`Retry-After` if given).
    Retryable {
        code: String,
        retry_after: Option<Duration>,
    },
    /// Anything else (bad config, permission denied): give up; the backstop sync covers it.
    Fatal(String),
}

/// Maps an FCM v1 error response to what to do about it. `body` is the JSON error body
/// (`{"error": {"status": ..., "details": [{"errorCode": ...}]}}`); `retry_after` the raw header.
pub fn classify_fcm_error(status: u16, body: &str, retry_after: Option<&str>) -> FcmErrorClass {
    let parsed: serde_json::Value = serde_json::from_str(body).unwrap_or_default();
    let error = &parsed["error"];
    let error_code = error["details"]
        .as_array()
        .and_then(|details| details.iter().find_map(|d| d["errorCode"].as_str()))
        .map(str::to_string);
    let status_name = error["status"].as_str().map(str::to_string);
    let code = error_code
        .clone()
        .or(status_name.clone())
        .unwrap_or_else(|| format!("HTTP {status}"));
    let retry_after = retry_after
        .and_then(|v| v.trim().parse::<u64>().ok())
        .map(Duration::from_secs);
    match (status, error_code.as_deref()) {
        (_, Some("UNREGISTERED")) | (404, _) => FcmErrorClass::TokenDead(code),
        (_, Some("SENDER_ID_MISMATCH")) => FcmErrorClass::TokenDead(code),
        // The message itself is a constant, so an invalid argument can only be the token.
        (400, _) if code == "INVALID_ARGUMENT" => FcmErrorClass::TokenDead(code),
        (401, _) => FcmErrorClass::Unauthorized,
        (429 | 500 | 503, _) => FcmErrorClass::Retryable { code, retry_after },
        _ => FcmErrorClass::Fatal(code),
    }
}

// ---------------------------------------------------------------------------------------------
// The sender
// ---------------------------------------------------------------------------------------------

/// What happened to one nudge.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SendOutcome {
    Sent,
    /// FCM says the token is dead (code) - clear it.
    TokenDead(String),
    /// Gave up (reason, no secrets in it).
    Failed(String),
}

pub type SendFuture<'a> = Pin<Box<dyn Future<Output = SendOutcome> + Send + 'a>>;

/// What a phone's `fcm_token` is: a Firebase installation ID (the launcher registers by FID since
/// firebase-messaging 25.1) or a legacy registration token. HTTP v1 targets an FID with
/// `message.fid`; `message.token` is deprecated and accepts FIDs only during Firebase's migration
/// period (firebase.google.com/docs/cloud-messaging/send/admin-sdk).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TargetKind {
    Fid,
    Token,
}

impl TargetKind {
    /// The phone's report ("fid"/"token") when it agrees with the value, else the shape: an FID
    /// is 22 characters of base64url; a registration token is longer and contains ':'.
    pub fn of(value: &str, reported: Option<&str>) -> TargetKind {
        if value.contains(':') {
            return TargetKind::Token;
        }
        match reported {
            Some("token") => TargetKind::Token,
            Some("fid") => TargetKind::Fid,
            _ if looks_like_fid(value) => TargetKind::Fid,
            _ => TargetKind::Token,
        }
    }

    pub fn field(self) -> &'static str {
        match self {
            TargetKind::Fid => "fid",
            TargetKind::Token => "token",
        }
    }
}

/// A Firebase installation ID: exactly 22 characters of base64url (no padding).
pub fn looks_like_fid(value: &str) -> bool {
    value.len() == 22
        && value
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

/// Where a nudge goes: the phone's FID or registration token, and which of the two it is.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Target<'a> {
    pub id: &'a str,
    pub kind: TargetKind,
}

impl<'a> Target<'a> {
    pub fn token(id: &'a str) -> Self {
        Target {
            id,
            kind: TargetKind::Token,
        }
    }
    pub fn fid(id: &'a str) -> Self {
        Target {
            id,
            kind: TargetKind::Fid,
        }
    }
}

/// Sends one nudge to one phone (FID or registration token). Behind a trait so tests use a fake.
pub trait FcmSender: Send + Sync {
    fn send<'a>(&'a self, target: Target<'a>, nonce: &'a str) -> SendFuture<'a>;
    /// Why this server can't reach FCM right now (the token endpoint keeps failing), for the
    /// device page; `None` when fine.
    fn server_problem(&self) -> Option<String> {
        None
    }
}

pub type SharedSender = Arc<dyn FcmSender>;

/// Timeouts and retry budget; tests shrink them.
#[derive(Debug, Clone)]
pub struct HttpFcmConfig {
    pub fcm_base_url: String,
    pub request_timeout: Duration,
    /// Tries for a retryable error, all within [Self::retry_budget].
    pub max_tries: u32,
    pub retry_budget: Duration,
    /// Wait between tries when FCM gives no `Retry-After`.
    pub default_backoff: Duration,
}

impl Default for HttpFcmConfig {
    fn default() -> Self {
        HttpFcmConfig {
            fcm_base_url: DEFAULT_FCM_BASE_URL.to_string(),
            request_timeout: Duration::from_secs(10),
            max_tries: 3,
            retry_budget: Duration::from_secs(120),
            default_backoff: Duration::from_secs(5),
        }
    }
}

struct AccessToken {
    value: String,
    refresh_at: Instant,
}

impl fmt::Debug for AccessToken {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("AccessToken(<redacted>)")
    }
}

#[derive(Default)]
struct TokenEndpointState {
    /// Last token-endpoint failure (no secrets), cleared on success.
    problem: Option<String>,
    last_warned: Option<Instant>,
}

/// The real sender: OAuth2 JWT bearer flow + FCM v1 send, with timeouts and bounded retries.
pub struct HttpFcmSender {
    account: ServiceAccount,
    config: HttpFcmConfig,
    client: reqwest::Client,
    token: tokio::sync::Mutex<Option<AccessToken>>,
    endpoint: std::sync::Mutex<TokenEndpointState>,
}

impl fmt::Debug for HttpFcmSender {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("HttpFcmSender")
            .field("account", &self.account)
            .field("config", &self.config)
            .finish_non_exhaustive()
    }
}

enum TryResult {
    Done(SendOutcome),
    Unauthorized,
    Retry(String, Option<Duration>),
}

impl HttpFcmSender {
    pub fn new(account: ServiceAccount, config: HttpFcmConfig) -> Result<Self, String> {
        let client = reqwest::Client::builder()
            .timeout(config.request_timeout)
            .connect_timeout(config.request_timeout)
            .build()
            .map_err(|e| format!("can't build the HTTP client: {e}"))?;
        Ok(HttpFcmSender {
            account,
            config,
            client,
            token: tokio::sync::Mutex::new(None),
            endpoint: std::sync::Mutex::new(TokenEndpointState::default()),
        })
    }

    /// From `FCM_SERVICE_ACCOUNT_FILE`: `Ok(None)` when unset (FCM off), an error when set but
    /// unusable (also FCM off; `main` logs it).
    pub fn from_env(data_dir: &Path) -> Result<Option<Self>, String> {
        let Some(path) = std::env::var("FCM_SERVICE_ACCOUNT_FILE")
            .ok()
            .map(|p| p.trim().to_string())
            .filter(|p| !p.is_empty())
        else {
            return Ok(None);
        };
        let account = load_service_account(Path::new(&path), data_dir)
            .map_err(|e| format!("FCM_SERVICE_ACCOUNT_FILE: {e}"))?;
        Self::new(account, HttpFcmConfig::default()).map(Some)
    }

    fn note_endpoint(&self, problem: Option<String>) {
        let mut state = self.endpoint.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(problem) = &problem {
            let due = state
                .last_warned
                .is_none_or(|t| t.elapsed() >= Duration::from_secs(3600));
            if due {
                tracing::warn!(%problem, "FCM: can't get an access token (warned at most hourly)");
                state.last_warned = Some(Instant::now());
            }
        }
        state.problem = problem;
    }

    async fn access_token(&self) -> Result<String, String> {
        let mut cached = self.token.lock().await;
        if let Some(token) = cached.as_ref()
            && Instant::now() < token.refresh_at
        {
            return Ok(token.value.clone());
        }
        let now = chrono::Utc::now().timestamp();
        let jwt = self.account.sign_jwt(now)?;
        let body = form_urlencoded::Serializer::new(String::new())
            .append_pair("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            .append_pair("assertion", &jwt.0)
            .finish();
        let result = async {
            let response = self
                .client
                .post(&self.account.token_uri)
                .header(
                    reqwest::header::CONTENT_TYPE,
                    "application/x-www-form-urlencoded",
                )
                .body(body)
                .send()
                .await
                .map_err(|e| format!("token endpoint unreachable: {}", e.without_url()))?;
            let status = response.status();
            let json: serde_json::Value = response
                .json()
                .await
                .map_err(|e| format!("token endpoint answered garbage: {}", e.without_url()))?;
            if !status.is_success() {
                let error = json["error"].as_str().unwrap_or("unknown error");
                return Err(format!("token endpoint refused: HTTP {status} {error}"));
            }
            let value = json["access_token"]
                .as_str()
                .ok_or("token endpoint answered without access_token")?
                .to_string();
            let expires_in = json["expires_in"].as_u64().unwrap_or(3600);
            Ok(AccessToken {
                value,
                // Refresh five minutes before it expires.
                refresh_at: Instant::now() + Duration::from_secs(expires_in.saturating_sub(300)),
            })
        }
        .await;
        match result {
            Ok(token) => {
                self.note_endpoint(None);
                let value = token.value.clone();
                *cached = Some(token);
                Ok(value)
            }
            Err(err) => {
                self.note_endpoint(Some(err.clone()));
                Err(err)
            }
        }
    }

    async fn drop_access_token(&self) {
        *self.token.lock().await = None;
    }

    async fn try_once(&self, target: Target<'_>, nonce: &str) -> TryResult {
        let access = match self.access_token().await {
            Ok(a) => a,
            Err(e) => return TryResult::Done(SendOutcome::Failed(e)),
        };
        let url = format!(
            "{}/v1/projects/{}/messages:send",
            self.config.fcm_base_url.trim_end_matches('/'),
            self.account.project_id
        );
        let mut message = serde_json::json!({
            "message": {
                "data": {"k": "sync", "n": nonce},
                "android": {"priority": "HIGH", "ttl": "600s"},
            }
        });
        message["message"][target.kind.field()] = serde_json::Value::from(target.id);
        let response = match self
            .client
            .post(&url)
            .bearer_auth(&access)
            .json(&message)
            .send()
            .await
        {
            Ok(r) => r,
            Err(e) => {
                return TryResult::Retry(format!("FCM unreachable: {}", e.without_url()), None);
            }
        };
        let status = response.status().as_u16();
        if response.status().is_success() {
            return TryResult::Done(SendOutcome::Sent);
        }
        let retry_after = response
            .headers()
            .get(reqwest::header::RETRY_AFTER)
            .and_then(|v| v.to_str().ok())
            .map(str::to_string);
        let body = response.text().await.unwrap_or_default();
        match classify_fcm_error(status, &body, retry_after.as_deref()) {
            FcmErrorClass::TokenDead(code) => TryResult::Done(SendOutcome::TokenDead(code)),
            FcmErrorClass::Unauthorized => TryResult::Unauthorized,
            FcmErrorClass::Retryable { code, retry_after } => TryResult::Retry(code, retry_after),
            FcmErrorClass::Fatal(code) => TryResult::Done(SendOutcome::Failed(code)),
        }
    }

    async fn send_with_retries(&self, target: Target<'_>, nonce: &str) -> SendOutcome {
        let started = Instant::now();
        let mut tries = 0;
        let mut reauthed = false;
        loop {
            tries += 1;
            match self.try_once(target, nonce).await {
                TryResult::Done(outcome) => return outcome,
                TryResult::Unauthorized if !reauthed => {
                    reauthed = true;
                    tries -= 1;
                    self.drop_access_token().await;
                }
                TryResult::Unauthorized => {
                    self.drop_access_token().await;
                    return SendOutcome::Failed("FCM refused our access token".into());
                }
                TryResult::Retry(code, retry_after) => {
                    let wait = retry_after.unwrap_or(self.config.default_backoff);
                    if tries >= self.config.max_tries
                        || started.elapsed() + wait > self.config.retry_budget
                    {
                        return SendOutcome::Failed(format!(
                            "{code} (gave up after {tries} tries)"
                        ));
                    }
                    tokio::time::sleep(wait).await;
                }
            }
        }
    }
}

impl FcmSender for HttpFcmSender {
    fn send<'a>(&'a self, target: Target<'a>, nonce: &'a str) -> SendFuture<'a> {
        Box::pin(self.send_with_retries(target, nonce))
    }

    fn server_problem(&self) -> Option<String> {
        self.endpoint
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .problem
            .clone()
    }
}

#[cfg(test)]
pub mod testing {
    //! A fake sender for TestApp: records every send and answers with a scripted outcome.
    use super::*;

    #[derive(Default)]
    pub struct FakeSender {
        pub sent: std::sync::Mutex<Vec<(String, String)>>,
        /// The target kind of each send, in order.
        pub kinds: std::sync::Mutex<Vec<TargetKind>>,
        pub next: std::sync::Mutex<Option<SendOutcome>>,
    }

    impl FakeSender {
        pub fn sends(&self) -> Vec<(String, String)> {
            self.sent.lock().unwrap().clone()
        }
        pub fn kinds(&self) -> Vec<TargetKind> {
            self.kinds.lock().unwrap().clone()
        }
        pub fn answer(&self, outcome: SendOutcome) {
            *self.next.lock().unwrap() = Some(outcome);
        }
    }

    impl FcmSender for FakeSender {
        fn send<'a>(&'a self, target: Target<'a>, nonce: &'a str) -> SendFuture<'a> {
            self.sent
                .lock()
                .unwrap()
                .push((target.id.to_string(), nonce.to_string()));
            self.kinds.lock().unwrap().push(target.kind);
            let outcome = self
                .next
                .lock()
                .unwrap()
                .clone()
                .unwrap_or(SendOutcome::Sent);
            Box::pin(async move { outcome })
        }
    }
}
