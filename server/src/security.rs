use argon2::password_hash::SaltString;
use argon2::password_hash::rand_core::{OsRng, RngCore};
use argon2::{Argon2, PasswordHash, PasswordHasher, PasswordVerifier};
use axum::extract::{Request, State};
use axum::middleware::Next;
use axum::response::{IntoResponse, Redirect, Response};
use pbkdf2::pbkdf2_hmac;
use sha2::{Digest, Sha256};
use totp_rs::{Algorithm, Secret, TOTP};
use tower_sessions::Session;

use crate::AppState;
use crate::models::AdminUser;

pub const MIN_PASSWORD_LEN: usize = 12;

/// Failed *password or TOTP* attempts against one account before it locks.
pub const MAX_FAILED_ATTEMPTS: i64 = 5;
/// How long a locked account stays locked before auto-unlocking.
pub const LOCKOUT_MINUTES: i64 = 15;
/// Failed attempts from one IP (across any account) within the window below
/// before that IP is banned from reaching the login page at all.
pub const MAX_FAILED_PER_IP: i64 = 15;
pub const IP_FAILURE_WINDOW_MINUTES: i64 = 15;
/// How long an IP ban lasts before auto-expiring.
pub const IP_BAN_HOURS: i64 = 1;

pub fn hash_password(password: &str) -> String {
    let salt = SaltString::generate(&mut OsRng);
    Argon2::default()
        .hash_password(password.as_bytes(), &salt)
        .expect("hashing a non-empty password should not fail")
        .to_string()
}

pub fn verify_password(password: &str, hash: &str) -> bool {
    let Ok(parsed_hash) = PasswordHash::new(hash) else {
        return false;
    };
    Argon2::default()
        .verify_password(password.as_bytes(), &parsed_hash)
        .is_ok()
}

/// Short, human-typeable, unambiguous (no 0/O/1/I/L) enrollment code shown in
/// the admin UI and typed once into the phone's Settings screen - this
/// replaces the old flow of typing a raw server URL + made-up device number.
pub fn generate_enrollment_code() -> String {
    const CHARS: &[u8] = b"23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    let mut rng = OsRng;
    (0..8)
        .map(|_| CHARS[(rng.next_u32() as usize) % CHARS.len()] as char)
        .collect()
}

/// High-entropy bearer token handed to a device once, at enrollment. Only
/// its SHA-256 hash (see `hash_token`) is ever stored - the plaintext value
/// is shown/returned exactly once and can't be recovered afterward, the same
/// way a password reset works.
pub fn generate_device_token() -> String {
    let mut bytes = [0u8; 32];
    OsRng.fill_bytes(&mut bytes);
    hex::encode(bytes)
}

pub fn hash_token(token: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(token.as_bytes());
    hex::encode(hasher.finalize())
}

pub async fn record_security_event(
    db: &sqlx::SqlitePool,
    event_type: &str,
    username: Option<&str>,
    ip: Option<&str>,
    detail: Option<&str>,
) {
    sqlx::query(
        "INSERT INTO security_events (event_type, username, ip_address, detail) VALUES (?, ?, ?, ?)",
    )
    .bind(event_type)
    .bind(username)
    .bind(ip)
    .bind(detail)
    .execute(db)
    .await
    .ok();
}

pub async fn is_ip_banned(db: &sqlx::SqlitePool, ip: &str) -> bool {
    sqlx::query_scalar::<_, bool>(
        "SELECT EXISTS(SELECT 1 FROM banned_ips WHERE ip_address = ? AND banned_until > datetime('now'))",
    )
    .bind(ip)
    .fetch_one(db)
    .await
    .unwrap_or(false)
}

/// Counts recent failed attempts from this IP and bans it if over threshold.
/// Call after recording a failed-login-type security event.
pub async fn check_and_ban_ip_if_needed(db: &sqlx::SqlitePool, ip: &str) {
    let recent_failures: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM security_events \
         WHERE ip_address = ? AND event_type IN ('login_failed', 'totp_failed') \
         AND created_at > datetime('now', ?)",
    )
    .bind(ip)
    .bind(format!("-{IP_FAILURE_WINDOW_MINUTES} minutes"))
    .fetch_one(db)
    .await
    .unwrap_or(0);

    if recent_failures >= MAX_FAILED_PER_IP {
        sqlx::query(
            "INSERT INTO banned_ips (ip_address, banned_until, reason) VALUES (?, datetime('now', ?), ?) \
             ON CONFLICT(ip_address) DO UPDATE SET banned_until = excluded.banned_until, reason = excluded.reason",
        )
        .bind(ip)
        .bind(format!("+{IP_BAN_HOURS} hours"))
        .bind(format!(
            "{recent_failures} failed login attempts within {IP_FAILURE_WINDOW_MINUTES} minutes"
        ))
        .execute(db)
        .await
        .ok();

        record_security_event(
            db,
            "ip_banned",
            None,
            Some(ip),
            Some(&format!("{recent_failures} failed attempts")),
        )
        .await;
    }
}

/// True if this account is currently locked out (and the lock hasn't expired).
pub async fn is_account_locked(db: &sqlx::SqlitePool, admin_id: i64) -> bool {
    sqlx::query_scalar::<_, bool>(
        "SELECT locked_until IS NOT NULL AND locked_until > datetime('now') FROM admin_users WHERE id = ?",
    )
    .bind(admin_id)
    .fetch_optional(db)
    .await
    .ok()
    .flatten()
    .unwrap_or(false)
}

/// Records one failed password/TOTP attempt against a known account, locking
/// it once MAX_FAILED_ATTEMPTS is reached. Deliberately does nothing if the
/// account is already locked, so repeated hammering can't extend the lockout
/// and lock a legitimate user out indefinitely.
pub async fn record_failed_login(db: &sqlx::SqlitePool, admin_id: i64) {
    if is_account_locked(db, admin_id).await {
        return;
    }

    sqlx::query(
        "UPDATE admin_users SET failed_login_attempts = failed_login_attempts + 1 WHERE id = ?",
    )
    .bind(admin_id)
    .execute(db)
    .await
    .ok();

    let attempts: i64 =
        sqlx::query_scalar("SELECT failed_login_attempts FROM admin_users WHERE id = ?")
            .bind(admin_id)
            .fetch_one(db)
            .await
            .unwrap_or(0);

    if attempts >= MAX_FAILED_ATTEMPTS {
        sqlx::query("UPDATE admin_users SET locked_until = datetime('now', ?) WHERE id = ?")
            .bind(format!("+{LOCKOUT_MINUTES} minutes"))
            .bind(admin_id)
            .execute(db)
            .await
            .ok();
    }
}

pub async fn reset_failed_login(db: &sqlx::SqlitePool, admin_id: i64) {
    sqlx::query(
        "UPDATE admin_users SET failed_login_attempts = 0, locked_until = NULL WHERE id = ?",
    )
    .bind(admin_id)
    .execute(db)
    .await
    .ok();
}

/// Builds a TOTP object for a given admin from a stored (or freshly
/// generated) base32 secret. All accounts share the same algorithm/digits/
/// step, so the secret alone is enough to reconstruct it.
pub fn totp_for_secret(secret_base32: &str, username: &str) -> TOTP {
    let secret = Secret::Encoded(secret_base32.to_string());
    TOTP::new(
        Algorithm::SHA1,
        6,
        1,
        30,
        secret
            .to_bytes()
            .expect("stored secret should be valid base32"),
        Some("Kids Device MDM".to_string()),
        username.to_string(),
    )
    .expect("fixed TOTP parameters should always be valid")
}

/// Rounds recommended (OWASP, 2023) as a PBKDF2-HMAC-SHA256 minimum.
const PIN_PBKDF2_ROUNDS: u32 = 210_000;
const PIN_SALT_LEN: usize = 16;
const PIN_HASH_LEN: usize = 32;

/// Hashes a device's offline-override PIN (and, since handy step 10, the kid's lock-screen PIN)
/// with PBKDF2-HMAC-SHA256 and a fresh random salt, returning `(hash_hex, salt_hex)`.
/// Deliberately not the Argon2 used for admin passwords: this hash+salt pair gets shipped down to
/// the device in its policy payload so the launcher can verify a locally-entered PIN with zero
/// network at all, and PBKDF2 is available on Android via the built-in
/// `javax.crypto.SecretKeyFactory` with no extra client dependency, unlike Argon2. The launcher's
/// `server/PinHash.kt` must use the same parameters (`pin_hash_shared_vector` pins them).
pub fn hash_pin(pin: &str) -> (String, String) {
    let mut salt = [0u8; PIN_SALT_LEN];
    OsRng.fill_bytes(&mut salt);
    (hash_pin_with_salt(pin, &salt), hex::encode(salt))
}

/// [hash_pin] with a given salt (hex of the hash).
pub fn hash_pin_with_salt(pin: &str, salt: &[u8]) -> String {
    let mut hash = [0u8; PIN_HASH_LEN];
    pbkdf2_hmac::<Sha256>(pin.as_bytes(), salt, PIN_PBKDF2_ROUNDS, &mut hash);
    hex::encode(hash)
}

/// Whether `pin` matches a stored [hash_pin] pair. Only used to keep the kid's PIN and the
/// override PIN apart (handy step 10: one must never verify against the other's hash); an
/// unparseable pair never matches. Constant-time comparison.
pub fn verify_pin(pin: &str, hash_hex: &str, salt_hex: &str) -> bool {
    let (Ok(salt), Ok(expected)) = (hex::decode(salt_hex), hex::decode(hash_hex)) else {
        return false;
    };
    if expected.len() != PIN_HASH_LEN {
        return false;
    }
    let mut hash = [0u8; PIN_HASH_LEN];
    pbkdf2_hmac::<Sha256>(pin.as_bytes(), &salt, PIN_PBKDF2_ROUNDS, &mut hash);
    hash.iter()
        .zip(expected.iter())
        .fold(0u8, |acc, (a, b)| acc | (a ^ b))
        == 0
}

pub fn generate_totp_secret_base32() -> String {
    match Secret::generate_secret().to_encoded() {
        Secret::Encoded(s) => s,
        Secret::Raw(_) => unreachable!("to_encoded() always returns the Encoded variant"),
    }
}

/// Plain read of whatever version install.sh last stamped into
/// `data/watcher_version`, purely for display ("System helper scripts:
/// vX.Y.Z"). Whether that's actually a *problem* is answered separately by
/// `watcher_needs_update` below.
pub async fn installed_watcher_version() -> Option<String> {
    let installed = tokio::fs::read_to_string("data/watcher_version")
        .await
        .ok()?;
    let installed = installed.trim();
    if installed.is_empty() {
        None
    } else {
        Some(installed.to_string())
    }
}

/// The watcher "schema" version: a small counter, independent of the app's
/// own release version, bumped only when install.sh's root-side scripts
/// (actions.sh/watcher.sh/scheduler.sh/backup_sync.sh) actually gain or
/// change a privileged action - see board-game-tracker's CLAUDE.md for why
/// comparing raw version strings directly was the wrong check (it fired on
/// every single app release regardless of whether the watcher itself had
/// changed at all).
/// 6: actions.sh fetches update.sh from the monorepo's server/deploy/.
pub const REQUIRED_WATCHER_SCHEMA: u32 = 6;

async fn installed_watcher_schema() -> Option<u32> {
    let raw = tokio::fs::read_to_string("data/watcher_schema_version")
        .await
        .ok()?;
    raw.trim().parse::<u32>().ok()
}

/// Whether the installed watcher is missing a privileged action the app
/// might need to request. An unknown schema (no marker at all - a fresh
/// install that hasn't run install.sh's watcher setup yet) is treated as
/// needing an update too, since it can't be confirmed safe either way.
pub async fn watcher_needs_update() -> bool {
    installed_watcher_schema()
        .await
        .is_none_or(|schema| schema < REQUIRED_WATCHER_SCHEMA)
}

/// Re-run hint shown wherever the watcher version is displayed - the
/// root-side watcher/scheduler scripts are only ever refreshed by re-running
/// install.sh (the in-app "Update now" button only swaps the app binary). `repo` is
/// `config::ForkConfig::server_release_repo`. The `KPS_REPO=` prefix keeps a re-run installing
/// from the same repo even when it isn't install.sh's built-in default.
pub fn reinstall_hint(repo: &str) -> String {
    format!(
        "curl -fsSL https://raw.githubusercontent.com/{repo}/master/server/deploy/install.sh | sudo KPS_REPO={repo} bash"
    )
}

/// Creates the first admin account from ADMIN_USERNAME/ADMIN_PASSWORD env
/// vars if the admin_users table is empty - there's no self-registration, so
/// this is the only way to get a first account onto a fresh install.
pub async fn bootstrap_admin(db: &sqlx::SqlitePool) {
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM admin_users")
        .fetch_one(db)
        .await
        .expect("failed to count admin_users");
    if count > 0 {
        return;
    }

    let username = std::env::var("ADMIN_USERNAME").unwrap_or_else(|_| "admin".to_string());
    let password = std::env::var("ADMIN_PASSWORD")
        .expect("ADMIN_PASSWORD must be set in .env to bootstrap the first admin account");

    let hash = hash_password(&password);
    sqlx::query(
        "INSERT INTO admin_users (username, password_hash, must_change_password) VALUES (?, ?, 1)",
    )
    .bind(&username)
    .bind(&hash)
    .execute(db)
    .await
    .expect("failed to create bootstrap admin");

    tracing::info!("bootstrapped initial admin account: {username}");
}

#[derive(Clone)]
pub struct CurrentAdmin(pub AdminUser);

async fn load_active_admin(state: &AppState, session: &Session) -> Option<AdminUser> {
    let admin_id: i64 = session.get("admin_id").await.ok().flatten()?;
    sqlx::query_as::<_, AdminUser>("SELECT * FROM admin_users WHERE id = ?")
        .bind(admin_id)
        .fetch_optional(&state.db)
        .await
        .ok()
        .flatten()
}

/// Requires a valid session, but does not enforce the forced-password-change
/// gate - used for the change-password page itself and logout, which must
/// stay reachable mid-onboarding.
pub async fn require_session(
    State(state): State<AppState>,
    session: Session,
    mut request: Request,
    next: Next,
) -> Response {
    match load_active_admin(&state, &session).await {
        Some(admin) => {
            request.extensions_mut().insert(CurrentAdmin(admin));
            next.run(request).await
        }
        None => {
            session.flush().await.ok();
            Redirect::to("/login").into_response()
        }
    }
}

/// Requires a logged-in admin who has completed the forced password change
/// and mandatory 2FA setup. Use this on every route in the actual admin UI.
pub async fn require_full_auth(
    State(state): State<AppState>,
    session: Session,
    mut request: Request,
    next: Next,
) -> Response {
    match load_active_admin(&state, &session).await {
        Some(admin) if admin.must_change_password => {
            Redirect::to("/auth/change-password").into_response()
        }
        Some(admin) if !admin.totp_enabled => Redirect::to("/auth/setup-2fa").into_response(),
        Some(admin) => {
            request.extensions_mut().insert(CurrentAdmin(admin));
            next.run(request).await
        }
        None => {
            session.flush().await.ok();
            Redirect::to("/login").into_response()
        }
    }
}

/// The device a request's bearer token belongs to. Only the id: the token check is in memory
/// ([TokenIndex]), and handlers read what they need.
#[derive(Clone, Copy, Debug)]
pub struct DeviceRef {
    pub id: i64,
}

#[derive(Clone)]
pub struct AuthedDevice(pub DeviceRef);

/// `token_hash -> device id` for every enrolled phone (design 22 §3.1), so checking a token never
/// touches the database: a failed check costs one SHA-256 and one hash probe. Rebuilt at start
/// ([TokenIndex::reload]) and after every write to `devices.token_hash` - enroll, revoke, delete
/// ([TokenIndex::refresh]).
#[derive(Default)]
pub struct TokenIndex {
    map: std::sync::RwLock<std::collections::HashMap<String, i64>>,
    /// Reloads run one at a time, each reading after its own write, so the last one to finish
    /// holds every write.
    reload: tokio::sync::Mutex<()>,
}

impl TokenIndex {
    pub fn lookup(&self, token: &str) -> Option<i64> {
        let hash = hash_token(token);
        self.map
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .get(&hash)
            .copied()
    }

    /// Reads every token hash from the database.
    pub async fn reload(&self, db: &sqlx::SqlitePool) -> Result<(), sqlx::Error> {
        let _one_at_a_time = self.reload.lock().await;
        let rows: Vec<(String, i64)> =
            sqlx::query_as("SELECT token_hash, id FROM devices WHERE token_hash IS NOT NULL")
                .fetch_all(db)
                .await?;
        *self.map.write().unwrap_or_else(|e| e.into_inner()) = rows.into_iter().collect();
        Ok(())
    }

    /// After a write to one device's `token_hash` (`token_hash` = what was written, `None` =
    /// cleared or deleted): [TokenIndex::reload], and if the database can't be read, the same
    /// change applied by hand - a phone must never keep a revoked token, nor be refused the one it
    /// was just given.
    pub async fn refresh(&self, db: &sqlx::SqlitePool, device_id: i64, token_hash: Option<&str>) {
        if let Err(err) = self.reload(db).await {
            tracing::error!(device_id, %err, "couldn't reload the device tokens - patching");
            let mut map = self.map.write().unwrap_or_else(|e| e.into_inner());
            map.retain(|_, id| *id != device_id);
            if let Some(hash) = token_hash {
                map.insert(hash.to_string(), device_id);
            }
        }
    }
}

fn too_many(refused: crate::limits::Refused) -> Response {
    (
        axum::http::StatusCode::TOO_MANY_REQUESTS,
        [(
            axum::http::header::RETRY_AFTER,
            refused.retry_after_secs.to_string(),
        )],
    )
        .into_response()
}

/// Bearer-token auth for the device-facing API (`/api/devices/*`, excluding enroll) - completely
/// separate from the admin session system above, since a kid's phone is never an admin session.
///
/// Design 22 §3.1, in this order:
/// - the token is looked up in memory ([TokenIndex]);
/// - a bad or missing one counts against the client's failure bucket (`limits`; 429 once it's
///   full, else 401) and a throttled `device_auth_failed` event;
/// - a valid one never touches that bucket: it takes from the device's own bucket, then an
///   in-flight slot for its route class, held until the response body is done (an SSE stream or
///   a download holds it for its whole life).
pub async fn require_device_token(
    State(state): State<AppState>,
    mut request: Request,
    next: Next,
) -> Response {
    use crate::limits;

    let client =
        crate::gate::peer_of(&request).map(|peer| state.net.client(request.headers(), peer.ip()));
    if let Some(client) = &client {
        state.limits.observe_peer(client);
    }
    let token = request
        .headers()
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "));
    let device_id = token.and_then(|token| state.tokens.lookup(token));

    let Some(device_id) = device_id else {
        let key = client
            .map(|c| crate::net::limit_key(c.ip))
            .unwrap_or_default();
        if let Some(refused) = state.limits.ip_blocked(&key) {
            return too_many(refused);
        }
        let blocked = state.limits.ip_failed(&key);
        let what = if token.is_some() {
            "invalid device token"
        } else {
            "missing bearer token"
        };
        let ip = client.map(|c| c.ip.to_string());
        let detail = format!(
            "{what} for {}{}",
            request.uri().path().chars().take(100).collect::<String>(),
            if blocked {
                " - this client is now refused for 10 minutes"
            } else {
                ""
            }
        );
        state
            .audit
            .record(
                &state.db,
                "device_auth_failed",
                &key,
                ip.as_deref(),
                &detail,
            )
            .await;
        return (axum::http::StatusCode::UNAUTHORIZED, what).into_response();
    };

    if let Err(refused) = state.limits.take_token(device_id) {
        return too_many(refused);
    }
    let class = limits::route_class(request.uri().path());
    let permit = match state.limits.enter(device_id, class) {
        Ok(permit) => permit,
        Err(refused) => return too_many(refused),
    };
    if let Some(client) = &client {
        state.limits.record_access(device_id, client);
    }
    request
        .extensions_mut()
        .insert(AuthedDevice(DeviceRef { id: device_id }));
    let response = next.run(request).await;
    limits::hold_until_done(response, permit)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Shared with the launcher's `PinHashTest` (same PIN, salt and expected hash): both sides
    /// must derive the same PBKDF2 parameters or no PIN would ever verify on the phone.
    #[test]
    fn pin_hash_shared_vector() {
        let salt: Vec<u8> = (0u8..16).collect();
        assert_eq!(
            hash_pin_with_salt("1234", &salt),
            "942eed8586f04aa8cc4b14537eb02bb601b671749b6f95c07a2d2261f83e75a7"
        );
        assert!(verify_pin(
            "1234",
            "942eed8586f04aa8cc4b14537eb02bb601b671749b6f95c07a2d2261f83e75a7",
            "000102030405060708090a0b0c0d0e0f"
        ));
    }

    #[test]
    fn verify_pin_round_trip_and_garbage() {
        let (hash, salt) = hash_pin("4711");
        assert!(verify_pin("4711", &hash, &salt));
        assert!(!verify_pin("4712", &hash, &salt));
        assert!(!verify_pin("4711", "zz", &salt));
        assert!(!verify_pin("4711", &hash, "not hex"));
        assert!(!verify_pin("4711", &hash[..10], &salt));
    }
}
