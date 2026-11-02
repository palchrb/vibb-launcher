//! Passkeys for parents, step 1 (design 74 §1, §4, §5; the verifier is `crate::webauthn`).
//!
//! - [Challenges] (D1.7): every ceremony's challenge lives here, not in the session copy each
//!   request writes back - 32 random bytes bound to its [Kind], the session, the account and its
//!   session epoch (none for a sign-in), the RP ID and the route that consumes it; at most
//!   [MAX_CHALLENGES] for [CHALLENGE_TTL], swept on insert. [Challenges::take] removes the entry
//!   under the lock and answers it only when every field matches: one use, atomic. A restart
//!   drops them (the ceremony is simply tried again).
//! - [Ceremony]: a request's RP ID and origins from its host (`Host`, or `X-Forwarded-Host` from a
//!   trusted peer - the host `csrf_guard` compares; D4.1, D4.2), its route and client.
//! - The table `admin_passkeys` (0116): [add], [remove], the counter's atomic store
//!   ([store_use], D5.3), and [sign_in]/[reauth], which take the challenge, find the credential
//!   and its account, verify and store the use. Each refusal is logged by [log_refused].
//! - The account's `webauthn_user_id` ([user_id]): 16 random bytes made at its first add, never
//!   the row id or the username, so a passkey names nobody.

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use axum::extract::FromRequestParts;
use axum::http::request::Parts;
use ring::rand::SecureRandom;

use crate::AppState;
use crate::models::AdminUser;
use crate::webauthn::{self, Asserted, Assertion, NewCredential, PasskeyConfig, Refused};

/// Passkeys per parent (D1.1).
pub const MAX_PASSKEYS: i64 = 10;
/// Live sign-in (`login`) challenges at most: signed-out clients can make them, so when the pool
/// is full the oldest one goes (qa-passkeys-1 A-M2) - they can never refuse anyone else's.
pub const MAX_SIGN_IN: usize = 500;
/// Live signed-in challenges (re-auth, add) at most: a separate pool, so signed-out traffic can
/// never refuse them; only valid sessions fill it, [PER_SESSION] each.
pub const MAX_SIGNED_IN: usize = 500;
/// Live sign-in challenges per client (`net::limit_key`, an IPv6 /56 since step 2): a fourth drops that
/// client's oldest.
pub const SIGN_IN_PER_CLIENT: usize = 3;
/// Live challenges per session: a session asking for more drops its oldest, so one signed-in
/// browser can't fill the store.
pub const PER_SESSION: usize = 8;
/// A challenge lives 5 minutes; the options' `timeout` says the same.
pub const CHALLENGE_TTL: Duration = Duration::from_secs(300);
/// A session's live sign-in challenge is handed out again only while younger than this (step 2,
/// qa-passkeys-1 B-RN2): a sign-in page open longer gets a fresh one with its full 5 minutes, so the
/// autofill's choice doesn't meet a challenge that died under it. `static/passkey.js` restarts its
/// conditional get at 4.5 minutes.
pub const LIVE_REUSE: Duration = Duration::from_secs(240);
pub const TIMEOUT_MS: u64 = 300_000;
/// The route a sign-in's assertion is posted to.
pub const SIGN_IN_ROUTE: &str = "/auth/passkey";
/// The route a registration's credential is posted to.
pub const REGISTER_ROUTE: &str = "/account/passkeys";

/// The pre-session's counted `passkey_started` rows (their ids, oldest first): taken back when the ceremony ends
/// (qa-passkeys-2 limiter L4: a list, so a row whose challenge was evicted is still this session's).
pub const PRE_SIGN_IN_KEY: &str = "passkey_sign_in";

/// At most this many open rows a session remembers: one per live challenge a client can hold, and the one asked
/// for again. Past it the oldest is forgotten (it keeps counting until the ban's window ends).
pub const PRE_SIGN_IN_ROWS: usize = SIGN_IN_PER_CLIENT + 1;

/// The session's open rows (a session from before the list kept one id).
async fn started_rows(session: &tower_sessions::Session) -> Vec<i64> {
    match session.get_value(PRE_SIGN_IN_KEY).await {
        Ok(Some(serde_json::Value::Number(n))) => n.as_i64().into_iter().collect(),
        Ok(Some(value)) => serde_json::from_value(value).unwrap_or_default(),
        _ => Vec::new(),
    }
}

async fn keep_rows(session: &tower_sessions::Session, rows: &[i64]) {
    if rows.is_empty() {
        session.remove_value(PRE_SIGN_IN_KEY).await.ok();
    } else {
        session.insert(PRE_SIGN_IN_KEY, rows).await.ok();
    }
}

async fn delete_row(db: &sqlx::SqlitePool, id: i64, older_than: Option<Duration>) -> bool {
    let age = older_than.map(|age| format!("-{} seconds", age.as_secs()));
    sqlx::query(
        "DELETE FROM security_events WHERE id = ?1 AND event_type = 'passkey_started' \
         AND (?2 IS NULL OR created_at <= datetime('now', ?2))",
    )
    .bind(id)
    .bind(age)
    .execute(db)
    .await
    .map(|done| done.rows_affected() == 1)
    .unwrap_or(false)
}

/// Ends a signed-out session's passkey count (qa-passkeys-1 B-M3): its newest `passkey_started` row goes, at a
/// passkey sign-in's POST (whose refusal counts on its own) and at a password sign-in's - so opening the sign-in
/// page costs nothing once the parent does sign in, and a wrong password costs what it cost before passkeys.
pub async fn end_sign_in_attempt(db: &sqlx::SqlitePool, session: &tower_sessions::Session) {
    let mut rows = started_rows(session).await;
    if let Some(id) = rows.pop() {
        delete_row(db, id, None).await;
        keep_rows(session, &rows).await;
    }
}

/// A sign-in that succeeded (a right password or a passkey): every open row of the session goes - a household whose
/// pages evicted each other's challenges pays nothing once it signs in (qa-passkeys-2 limiter L4).
pub async fn sign_in_done(db: &sqlx::SqlitePool, session: &tower_sessions::Session) {
    for id in started_rows(session).await {
        delete_row(db, id, None).await;
    }
    keep_rows(session, &[]).await;
}

/// A new options call's row: the session's rows past [LIVE_REUSE] go first (their ceremony is over - qa-passkeys-1
/// B-RN1), younger ones (their challenge evicted) stay and count until a sign-in or their window ends, then `id`
/// joins them.
pub async fn note_started(db: &sqlx::SqlitePool, session: &tower_sessions::Session, id: i64) {
    let mut kept = Vec::new();
    for row in started_rows(session).await {
        if !delete_row(db, row, Some(LIVE_REUSE)).await {
            kept.push(row);
        }
    }
    kept.push(id);
    let skip = kept.len().saturating_sub(PRE_SIGN_IN_ROWS);
    keep_rows(session, &kept[skip..]).await;
}

/// What a challenge is for.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Register,
    Login,
    Reauth,
}

/// Everything a challenge is bound to (D1.7).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Bound {
    pub kind: Kind,
    pub session: String,
    /// The account and its session epoch; `None` for a sign-in.
    pub admin: Option<(i64, i64)>,
    pub rp_id: String,
    pub route: String,
}

struct Entry {
    bound: Bound,
    made: Instant,
    taken: Taken,
    /// The client's limit key, for [SIGN_IN_PER_CLIENT].
    client: String,
    /// The client's neighbourhood (`net::crowd_key`): a full sign-in pool drops from the one holding most.
    crowd: String,
}

/// What the issuer kept with a challenge.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Taken {
    /// A registration's name (D1.2: given with the options, never sent again).
    pub label: Option<String>,
}

/// The in-memory challenge store (D1.7): two pools, sign-in and signed-in.
#[derive(Default)]
pub struct Challenges {
    map: Mutex<HashMap<[u8; 32], Entry>>,
}

/// Removes the oldest entries `which` picks until fewer than `cap` are left.
fn make_room(map: &mut HashMap<[u8; 32], Entry>, cap: usize, which: impl Fn(&Entry) -> bool) {
    let mut picked: Vec<([u8; 32], Instant)> = map
        .iter()
        .filter(|(_, entry)| which(entry))
        .map(|(key, entry)| (*key, entry.made))
        .collect();
    if picked.len() < cap {
        return;
    }
    picked.sort_by_key(|(_, made)| *made);
    for (key, _) in &picked[..=picked.len() - cap] {
        map.remove(key);
    }
}

/// Makes room in a full sign-in pool (design 74 step 2, qa-passkeys-1 A-R1): until fewer than `cap`
/// are left, drops the oldest sign-in challenge of the crowd (`net::crowd_key`) holding the most
/// (of two crowds holding as many, the one with the older challenge). A flood from one range -
/// even from many fresh /56s of it - evicts its own; a parent's one challenge from elsewhere goes
/// only when every crowd holds one at most and it is the oldest: 500 neighbourhoods at once.
fn make_room_by_crowd(map: &mut HashMap<[u8; 32], Entry>, cap: usize) {
    loop {
        let mut crowds: HashMap<&str, (usize, Instant, [u8; 32])> = HashMap::new();
        let mut total = 0;
        for (key, entry) in map.iter().filter(|(_, e)| e.bound.kind == Kind::Login) {
            total += 1;
            let crowd = crowds
                .entry(entry.crowd.as_str())
                .or_insert((0, entry.made, *key));
            crowd.0 += 1;
            if entry.made < crowd.1 {
                (crowd.1, crowd.2) = (entry.made, *key);
            }
        }
        if total < cap {
            return;
        }
        let Some(oldest) = crowds
            .values()
            .max_by(|a, b| a.0.cmp(&b.0).then(b.1.cmp(&a.1)))
            .map(|crowd| crowd.2)
        else {
            return;
        };
        map.remove(&oldest);
    }
}

/// Who asks for a challenge: the client's limit key (`net::limit_key`) and its neighbourhood
/// (`net::crowd_key`).
#[derive(Clone, Copy, Debug)]
pub struct Asker<'a> {
    pub client: &'a str,
    pub crowd: &'a str,
}

impl<'a> Asker<'a> {
    pub fn of(ceremony: &'a Ceremony) -> Asker<'a> {
        Asker {
            client: &ceremony.client_key,
            crowd: &ceremony.client_crowd,
        }
    }
}

impl Challenges {
    /// A new challenge bound to `bound` for `asker`; `None` only when the signed-in pool is full
    /// (a sign-in's pool drops from its biggest crowd instead).
    pub fn issue(&self, bound: Bound, asker: Asker, taken: Taken) -> Option<[u8; 32]> {
        self.issue_at(Instant::now(), bound, asker, taken)
    }

    fn issue_at(&self, now: Instant, bound: Bound, asker: Asker, taken: Taken) -> Option<[u8; 32]> {
        let client = asker.client;
        let mut map = self.map.lock().unwrap_or_else(|e| e.into_inner());
        map.retain(|_, entry| now.saturating_duration_since(entry.made) < CHALLENGE_TTL);
        let sign_in = bound.kind == Kind::Login;
        let same_pool = |entry: &Entry| (entry.bound.kind == Kind::Login) == sign_in;
        // A session at its cap swaps its oldest for this one: the pool's count doesn't grow.
        make_room(&mut map, PER_SESSION, |entry| {
            same_pool(entry) && entry.bound.session == bound.session
        });
        if sign_in {
            make_room(&mut map, SIGN_IN_PER_CLIENT, |entry| {
                same_pool(entry) && entry.client == client
            });
            make_room_by_crowd(&mut map, MAX_SIGN_IN);
        } else if map.values().filter(|entry| same_pool(entry)).count() >= MAX_SIGNED_IN {
            return None;
        }
        let mut challenge = [0u8; 32];
        ring::rand::SystemRandom::new().fill(&mut challenge).ok()?;
        map.insert(
            challenge,
            Entry {
                bound,
                made: now,
                taken,
                client: client.to_string(),
                crowd: asker.crowd.to_string(),
            },
        );
        Some(challenge)
    }

    /// The live sign-in challenges `client` (its limit key) holds: a banned key gets one at most (qa-passkeys-2
    /// limiter L3).
    pub fn live_sign_ins_of(&self, client: &str) -> usize {
        let now = Instant::now();
        let map = self.map.lock().unwrap_or_else(|e| e.into_inner());
        map.values()
            .filter(|entry| {
                entry.bound.kind == Kind::Login
                    && entry.client == client
                    && now.saturating_duration_since(entry.made) < CHALLENGE_TTL
            })
            .count()
    }

    /// The session's live sign-in challenge at `rp_id`, when it has one younger than [LIVE_REUSE]:
    /// a reload of the sign-in page, or the button after the conditional get, gets it again
    /// (qa-passkeys-1 B-M3) - with the time it has left, which its options say as `timeout`, so the
    /// page refreshes before it dies (qa-passkeys-2 limiter L1).
    pub fn live_sign_in(&self, session: &str, rp_id: &str) -> Option<([u8; 32], Duration)> {
        self.live_sign_in_at(Instant::now(), session, rp_id)
    }

    fn live_sign_in_at(
        &self,
        now: Instant,
        session: &str,
        rp_id: &str,
    ) -> Option<([u8; 32], Duration)> {
        let map = self.map.lock().unwrap_or_else(|e| e.into_inner());
        map.iter()
            .filter(|(_, entry)| {
                entry.bound.kind == Kind::Login
                    && entry.bound.session == session
                    && entry.bound.rp_id == rp_id
                    && now.saturating_duration_since(entry.made) < LIVE_REUSE
            })
            .max_by_key(|(_, entry)| entry.made)
            .map(|(key, entry)| {
                let left = CHALLENGE_TTL.saturating_sub(now.saturating_duration_since(entry.made));
                (*key, left)
            })
    }

    /// Removes `challenge` and answers what was kept with it only when it is fresh and bound to
    /// exactly `bound`. Removed whatever the answer: a challenge is tried once.
    pub fn take(&self, challenge: &[u8], bound: &Bound) -> Option<Taken> {
        self.take_at(Instant::now(), challenge, bound)
    }

    fn take_at(&self, now: Instant, challenge: &[u8], bound: &Bound) -> Option<Taken> {
        let key: [u8; 32] = challenge.try_into().ok()?;
        let entry = self
            .map
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&key)?;
        let fresh = now.saturating_duration_since(entry.made) < CHALLENGE_TTL;
        (fresh && entry.bound == *bound).then_some(entry.taken)
    }

    #[cfg(test)]
    pub fn len(&self) -> usize {
        self.map.lock().unwrap().len()
    }
}

/// `AppState.passkeys`: the start-up settings and the challenge store.
#[derive(Default)]
pub struct Passkeys {
    pub config: PasskeyConfig,
    pub challenges: Challenges,
}

impl Passkeys {
    pub fn new(config: PasskeyConfig) -> Passkeys {
        Passkeys {
            config,
            challenges: Challenges::default(),
        }
    }
}

/// A request as a ceremony sees it (D4.1, D4.2).
#[derive(Clone, Debug, Default)]
pub struct Ceremony {
    /// `None`: no passkey UI here.
    pub rp_id: Option<String>,
    /// The origins a ceremony here may come from.
    pub origins: Vec<String>,
    /// The request's path: the route a challenge is bound to.
    pub route: String,
    /// The client's limit key (`net::limit_key`), its neighbourhood (`net::crowd_key`) and its
    /// address for the log.
    pub client_key: String,
    pub client_crowd: String,
    pub client: String,
    /// "iPhone", "Android", "Mac"... from the User-Agent: the add form's prefilled name.
    pub device_name: Option<&'static str>,
}

/// A device's name for a new passkey's prefilled Name (D1.1), from the User-Agent.
pub fn device_name(user_agent: &str) -> Option<&'static str> {
    let ua = user_agent.to_ascii_lowercase();
    [
        ("iphone", "iPhone"),
        ("ipad", "iPad"),
        ("android", "Android"),
        ("macintosh", "Mac"),
        ("mac os x", "Mac"),
        ("windows", "Windows"),
        ("cros", "Chromebook"),
        ("linux", "Linux"),
    ]
    .into_iter()
    .find(|(marker, _)| ua.contains(marker))
    .map(|(_, name)| name)
}

impl Ceremony {
    pub fn of(parts: &Parts, state: &AppState) -> Ceremony {
        let peer = parts
            .extensions
            .get::<axum::extract::ConnectInfo<std::net::SocketAddr>>()
            .map(|info| info.0.ip());
        let trusted = peer.is_some_and(|ip| state.net.is_trusted(ip));
        let header = |name: &str| {
            parts
                .headers
                .get(name)
                .and_then(|v| v.to_str().ok())
                .map(|v| v.split(',').next().unwrap_or("").trim().to_string())
                .filter(|v| !v.is_empty())
        };
        let raw = trusted
            .then(|| header("x-forwarded-host"))
            .flatten()
            .or_else(|| header("host"));
        let host = raw.as_deref().and_then(webauthn::host_of);
        let config = &state.passkeys.config;
        let rp_id = config.rp_id_for(host.as_ref().map(|(h, _)| h.as_str()));
        let origins = rp_id
            .as_deref()
            .map(|rp| config.origins_for(host.as_ref().map(|(h, p)| (h.as_str(), *p)), rp))
            .unwrap_or_default();
        let client = peer.map(|ip| state.net.client(&parts.headers, ip));
        Ceremony {
            rp_id,
            origins,
            route: parts.uri.path().to_string(),
            client_key: client.map(|c| c.key()).unwrap_or_default(),
            client_crowd: client
                .map(|c| match c.ip {
                    Some(ip) => crate::net::crowd_key(ip),
                    None => c.key(),
                })
                .unwrap_or_default(),
            client: client.map(|c| c.describe()).unwrap_or_default(),
            device_name: parts
                .headers
                .get(axum::http::header::USER_AGENT)
                .and_then(|v| v.to_str().ok())
                .and_then(device_name),
        }
    }
}

impl FromRequestParts<AppState> for Ceremony {
    type Rejection = std::convert::Infallible;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        Ok(Ceremony::of(parts, state))
    }
}

/// The routes whose forms ask for the password again and may take a passkey instead (D1.6, §6.2):
/// a re-auth challenge names one of these as the route that consumes it.
pub fn reauth_route(route: &str) -> bool {
    let number = |s: &str| !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit());
    let parts: Vec<&str> = route.trim_start_matches('/').split('/').collect();
    matches!(
        parts.as_slice(),
        ["account", "password"]
            | ["account", "reset-2fa"]
            | ["account", "passkey-only"]
            | ["account", "password-sign-in"]
            | ["account", "parents"]
            | ["account", "passkeys", "options"]
    ) || matches!(parts.as_slice(), ["account", "parents", n, "reset" | "remove"] if number(n))
        || matches!(parts.as_slice(), ["account", "passkeys", n, "remove"] if number(n))
        || matches!(parts.as_slice(), ["devices", n, "lock"] if number(n))
}

/// The session's id as a challenge binds it.
pub fn session_id(session: &tower_sessions::Session) -> Option<String> {
    session.id().map(|id| id.to_string())
}

// ---------------------------------------------------------------------------------------------
// The table

/// A row of `admin_passkeys`.
#[derive(sqlx::FromRow, Clone, Debug)]
pub struct PasskeyRow {
    pub id: i64,
    pub admin_id: i64,
    pub credential_id: Vec<u8>,
    pub public_key: Vec<u8>,
    pub rp_id: String,
    pub name: String,
    pub sign_count: i64,
    pub backup_eligible: bool,
    pub backed_up: bool,
    pub transports: Option<String>,
    pub created_at: String,
    pub last_used_at: Option<String>,
    pub last_used_from: Option<String>,
}

/// The account's passkeys, oldest first.
pub async fn of_admin(db: &sqlx::SqlitePool, admin_id: i64) -> sqlx::Result<Vec<PasskeyRow>> {
    sqlx::query_as("SELECT * FROM admin_passkeys WHERE admin_id = ? ORDER BY id")
        .bind(admin_id)
        .fetch_all(db)
        .await
}

/// Every account's number of passkeys (the Parents group, §6.3).
pub async fn counts(db: &sqlx::SqlitePool) -> sqlx::Result<HashMap<i64, i64>> {
    let rows: Vec<(i64, i64)> =
        sqlx::query_as("SELECT admin_id, COUNT(*) FROM admin_passkeys GROUP BY admin_id")
            .fetch_all(db)
            .await?;
    Ok(rows.into_iter().collect())
}

/// Whether `admin_id` has a passkey for this request's RP ID: the re-auth fields then offer
/// "Confirm with passkey" (D1.6). A read error is no (the password stays).
pub async fn has_here(db: &sqlx::SqlitePool, admin_id: i64, ceremony: &Ceremony) -> bool {
    let Some(rp_id) = ceremony.rp_id.as_deref() else {
        return false;
    };
    sqlx::query_scalar(
        "SELECT EXISTS (SELECT 1 FROM admin_passkeys WHERE admin_id = ? AND rp_id = ?)",
    )
    .bind(admin_id)
    .bind(rp_id)
    .fetch_one(db)
    .await
    .unwrap_or(false)
}

/// How many RP IDs the family's passkeys are for (D4.3: rows say "for <host>" past one).
pub async fn rp_ids(db: &sqlx::SqlitePool) -> sqlx::Result<i64> {
    sqlx::query_scalar("SELECT COUNT(DISTINCT rp_id) FROM admin_passkeys")
        .fetch_one(db)
        .await
}

/// The account's `webauthn_user_id`, made at the first add (16 random bytes) - only while the
/// session's epoch is the account's and the account is ready. `None`: nothing written.
pub async fn user_id(db: &sqlx::SqlitePool, admin: &AdminUser) -> sqlx::Result<Option<Vec<u8>>> {
    let mut fresh = [0u8; 16];
    ring::rand::SystemRandom::new()
        .fill(&mut fresh)
        .map_err(|_| sqlx::Error::Protocol("no random bytes".into()))?;
    sqlx::query_scalar(concat!(
        "UPDATE admin_users SET webauthn_user_id = COALESCE(webauthn_user_id, ?) \
         WHERE id = ? AND session_epoch = ? AND ",
        crate::ready_sql!(),
        " RETURNING webauthn_user_id"
    ))
    .bind(fresh.as_slice())
    .bind(admin.id)
    .bind(admin.session_epoch)
    .fetch_optional(db)
    .await
}

/// What [add] did.
#[derive(Debug, PartialEq, Eq)]
pub enum Added {
    Done(i64),
    /// [MAX_PASSKEYS] already.
    Full,
    /// This credential is registered already (to anyone).
    Duplicate,
    /// The session ended, or the account isn't ready: nothing was written.
    SignedOut,
}

/// Stores a verified registration for `admin` at `rp_id`, the cap and the actor condition (its
/// epoch, ready) in the one `INSERT`.
pub async fn add(
    db: &sqlx::SqlitePool,
    admin: &AdminUser,
    rp_id: &str,
    name: &str,
    credential: &NewCredential,
) -> sqlx::Result<Added> {
    let inserted: Result<Option<i64>, sqlx::Error> = sqlx::query_scalar(concat!(
        "INSERT INTO admin_passkeys (admin_id, credential_id, public_key, alg, rp_id, name, \
         sign_count, backup_eligible, backed_up, transports) \
         SELECT ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10 \
         WHERE EXISTS (SELECT 1 FROM admin_users WHERE id = ?1 AND session_epoch = ?11 AND ",
        crate::ready_sql!(),
        ") AND (SELECT COUNT(*) FROM admin_passkeys WHERE admin_id = ?1) < ?12 RETURNING id"
    ))
    .bind(admin.id)
    .bind(&credential.credential_id)
    .bind(&credential.public_key)
    .bind(credential.alg)
    .bind(rp_id)
    .bind(name)
    .bind(i64::from(credential.sign_count))
    .bind(credential.backup_eligible)
    .bind(credential.backed_up)
    .bind(&credential.transports)
    .bind(admin.session_epoch)
    .bind(MAX_PASSKEYS)
    .fetch_optional(db)
    .await;
    match inserted {
        Ok(Some(id)) => Ok(Added::Done(id)),
        Ok(None) if actor_ok(db, admin).await? => Ok(Added::Full),
        Ok(None) => Ok(Added::SignedOut),
        Err(sqlx::Error::Database(err)) if err.is_unique_violation() => Ok(Added::Duplicate),
        Err(err) => Err(err),
    }
}

/// The actor condition on its own: the session's epoch is the account's, and it is ready.
async fn actor_ok(db: &sqlx::SqlitePool, admin: &AdminUser) -> sqlx::Result<bool> {
    sqlx::query_scalar(concat!(
        "SELECT EXISTS (SELECT 1 FROM admin_users WHERE id = ? AND session_epoch = ? AND ",
        crate::ready_sql!(),
        ")"
    ))
    .bind(admin.id)
    .bind(admin.session_epoch)
    .fetch_one(db)
    .await
}

/// What [remove] did.
#[derive(Debug, PartialEq, Eq)]
pub enum Removed {
    /// The removed passkey's name and the account's new session epoch (this session keeps it).
    Done {
        name: String,
        epoch: i64,
    },
    /// No such passkey of this account.
    Gone,
    /// The last passkey of a passkey-only account (D2.3): switch the password on first.
    Last,
    SignedOut,
}

/// Removes one of `admin`'s passkeys and moves the session epoch in the same transaction (D1.3):
/// every other session ends with it, as with a password change. Never a passkey-only account's
/// last one (D2.3): the count is the `DELETE`'s own condition, never a read before it.
pub async fn remove(db: &sqlx::SqlitePool, admin: &AdminUser, id: i64) -> sqlx::Result<Removed> {
    let mut tx = db.begin_with("BEGIN IMMEDIATE").await?;
    let name: Option<String> = sqlx::query_scalar(concat!(
        "DELETE FROM admin_passkeys WHERE id = ?1 AND admin_id = ?2 AND EXISTS \
         (SELECT 1 FROM admin_users WHERE id = ?2 AND session_epoch = ?3 AND ",
        crate::ready_sql!(),
        " AND NOT (passkey_only = 1 AND (SELECT COUNT(*) FROM admin_passkeys \
           WHERE admin_passkeys.admin_id = admin_users.id) <= 1)) RETURNING name"
    ))
    .bind(id)
    .bind(admin.id)
    .bind(admin.session_epoch)
    .fetch_optional(&mut *tx)
    .await?;
    let Some(name) = name else {
        tx.rollback().await?;
        if !actor_ok(db, admin).await? {
            return Ok(Removed::SignedOut);
        }
        // Why nothing was written, read after the statement: the row is there, so it was the last one.
        let there: bool = sqlx::query_scalar(
            "SELECT EXISTS (SELECT 1 FROM admin_passkeys WHERE id = ? AND admin_id = ?)",
        )
        .bind(id)
        .bind(admin.id)
        .fetch_one(db)
        .await?;
        return Ok(if there { Removed::Last } else { Removed::Gone });
    };
    let epoch: i64 = sqlx::query_scalar(
        "UPDATE admin_users SET session_epoch = session_epoch + 1 WHERE id = ? \
         RETURNING session_epoch",
    )
    .bind(admin.id)
    .fetch_one(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(Removed::Done { name, epoch })
}

/// Deletes every passkey of `admin_id` on the caller's connection: a Reset, a soft Remove and
/// `reset-2fa` do it in their own transaction (D1.3b).
pub async fn delete_all<'e, E: sqlx::SqliteExecutor<'e>>(
    db: E,
    admin_id: i64,
) -> sqlx::Result<u64> {
    Ok(sqlx::query("DELETE FROM admin_passkeys WHERE admin_id = ?")
        .bind(admin_id)
        .execute(db)
        .await?
        .rows_affected())
}

/// What [switch_on] did.
#[derive(Debug, PartialEq, Eq)]
pub enum SwitchedOn {
    /// The account's new session epoch (this session keeps it).
    Done(i64),
    /// No passkey: add one first (D2.1).
    NoPasskey,
    /// On already.
    Already,
    SignedOut,
}

/// "Kun passkey" on (D2.1, D2.2), one `UPDATE`: the password becomes `'!passkey'` (no argon2 string matches it),
/// the TOTP goes, the session epoch moves (this session keeps it) - only for this session's epoch, an account not
/// removed and ready, and **at least one passkey counted in the statement itself**, so a removal in flight can't
/// slip between a read and the write.
pub async fn switch_on(db: &sqlx::SqlitePool, admin: &AdminUser) -> sqlx::Result<SwitchedOn> {
    let epoch: Option<i64> = sqlx::query_scalar(concat!(
        "UPDATE admin_users SET passkey_only = 1, password_hash = '!passkey', totp_secret = NULL, \
         totp_enabled = 0, totp_last_step = NULL, failed_login_attempts = 0, lock_count = 0, \
         locked_until = NULL, session_epoch = session_epoch + 1 \
         WHERE id = ?1 AND session_epoch = ?2 AND removed_at IS NULL AND passkey_only = 0 AND ",
        crate::ready_sql!(),
        " AND (SELECT COUNT(*) FROM admin_passkeys WHERE admin_passkeys.admin_id = admin_users.id) >= 1 \
         RETURNING session_epoch"
    ))
    .bind(admin.id)
    .bind(admin.session_epoch)
    .fetch_optional(db)
    .await?;
    if let Some(epoch) = epoch {
        return Ok(SwitchedOn::Done(epoch));
    }
    if !actor_ok(db, admin).await? {
        return Ok(SwitchedOn::SignedOut);
    }
    let on: bool = sqlx::query_scalar("SELECT passkey_only FROM admin_users WHERE id = ?")
        .bind(admin.id)
        .fetch_one(db)
        .await?;
    Ok(if on {
        SwitchedOn::Already
    } else {
        SwitchedOn::NoPasskey
    })
}

/// "Kun passkey" off (D2.5), one `UPDATE`: the new password's hash and the TOTP (its secret, on, and the step the
/// page's code used, so that code can't sign in again) - the account is never in between. Only for this session's
/// epoch, an account not removed and still passkey-only. The passkeys stay; nothing else ends. `None`: nothing
/// written (signed out, or switched off already).
pub async fn switch_off(
    db: &sqlx::SqlitePool,
    admin: &AdminUser,
    password_hash: &str,
    totp_secret: &str,
    totp_step: i64,
) -> sqlx::Result<Option<i64>> {
    sqlx::query_scalar(
        "UPDATE admin_users SET passkey_only = 0, password_hash = ?1, totp_secret = ?2, totp_enabled = 1, \
         totp_last_step = ?3, failed_login_attempts = 0, lock_count = 0, locked_until = NULL \
         WHERE id = ?4 AND session_epoch = ?5 AND removed_at IS NULL AND passkey_only = 1 \
         RETURNING session_epoch",
    )
    .bind(password_hash)
    .bind(totp_secret)
    .bind(totp_step)
    .bind(admin.id)
    .bind(admin.session_epoch)
    .fetch_optional(db)
    .await
}

/// Whether a ready parent other than `admin_id` can Reset them (D2.1's and D2.3's lines; read for the words only).
pub async fn another_ready_parent(db: &sqlx::SqlitePool, admin_id: i64) -> sqlx::Result<bool> {
    sqlx::query_scalar(concat!(
        "SELECT EXISTS (SELECT 1 FROM admin_users WHERE id != ? AND ",
        crate::ready_sql!(),
        ")"
    ))
    .bind(admin_id)
    .fetch_one(db)
    .await
}

/// Stores a verified use (D5.3): the counter only forward (0 stays 0 only while it was 0), BS as it
/// is now, when and from where. `false` when a parallel use stored as high a counter first - the
/// same refusal as a copy, so two replays of one assertion can't both pass.
pub async fn store_use(
    db: &sqlx::SqlitePool,
    id: i64,
    asserted: Asserted,
    from: &str,
) -> sqlx::Result<bool> {
    let count = i64::from(asserted.sign_count);
    let done = sqlx::query(
        "UPDATE admin_passkeys SET \
         sign_count = ?1, backed_up = ?2, \
         last_used_at = datetime('now'), last_used_from = NULLIF(?3, '') \
         WHERE id = ?4 AND ((sign_count = 0 AND ?1 = 0) OR sign_count < ?1)",
    )
    .bind(count)
    .bind(asserted.backed_up)
    .bind(crate::audit::clip(from, 64))
    .bind(id)
    .execute(db)
    .await?;
    Ok(done.rows_affected() == 1)
}

async fn find(db: &sqlx::SqlitePool, credential_id: &[u8]) -> sqlx::Result<Option<PasskeyRow>> {
    sqlx::query_as("SELECT * FROM admin_passkeys WHERE credential_id = ?")
        .bind(credential_id)
        .fetch_optional(db)
        .await
}

/// A refusal that a database error caused (logged; the ceremony is refused).
fn db_refused(err: sqlx::Error) -> Refused {
    tracing::error!(%err, "couldn't read or store a passkey");
    Refused::UnknownCredential
}

/// The stored credential's check and the use's store, after the account was found.
async fn finish(
    state: &AppState,
    row: &PasskeyRow,
    assertion: &Assertion,
    ceremony: &Ceremony,
    rp_id: &str,
) -> Result<(), Refused> {
    if row.rp_id != rp_id {
        return Err(Refused::RpIdHash);
    }
    let asserted = assertion.verify(
        &webauthn::Stored {
            public_key: &row.public_key,
            rp_id: &row.rp_id,
            sign_count: row.sign_count,
            backup_eligible: row.backup_eligible,
        },
        &ceremony.origins,
    )?;
    match store_use(&state.db, row.id, asserted, &ceremony.client).await {
        Ok(true) => Ok(()),
        Ok(false) => Err(Refused::Counter),
        Err(err) => Err(db_refused(err)),
    }
}

/// A verified passkey sign-in (D1.4, D1.5).
pub struct SignedIn {
    pub admin: AdminUser,
    pub passkey: PasskeyRow,
}

/// A refusal, with the account when one was found (for the log line's name).
pub struct NotSignedIn {
    pub why: Refused,
    pub username: Option<String>,
}

/// The sign-in's assertion: the `login` challenge of this session at this RP ID, the account by
/// its user handle (required here, `allowCredentials` was empty) and ready by the one predicate,
/// the credential that account's, then [Assertion::verify] and the counter's store.
pub async fn sign_in(
    state: &AppState,
    session: Option<String>,
    ceremony: &Ceremony,
    json: &str,
) -> Result<SignedIn, NotSignedIn> {
    let refused = |why| NotSignedIn {
        why,
        username: None,
    };
    let assertion = Assertion::parse(json).map_err(refused)?;
    let (Some(session), Some(rp_id)) = (session, ceremony.rp_id.clone()) else {
        return Err(refused(Refused::Challenge));
    };
    let bound = Bound {
        kind: Kind::Login,
        session,
        admin: None,
        rp_id: rp_id.clone(),
        route: SIGN_IN_ROUTE.to_string(),
    };
    state
        .passkeys
        .challenges
        .take(&assertion.client.challenge, &bound)
        .ok_or(refused(Refused::Challenge))?;
    let Some(handle) = assertion.user_handle.as_deref() else {
        return Err(refused(Refused::UserHandle));
    };
    let admin: Option<AdminUser> = sqlx::query_as(concat!(
        crate::admin_select!(),
        " WHERE webauthn_user_id = ? AND ",
        crate::ready_sql!()
    ))
    .bind(handle)
    .fetch_optional(&state.db)
    .await
    .map_err(|err| refused(db_refused(err)))?;
    let row = find(&state.db, &assertion.credential_id)
        .await
        .map_err(|err| refused(db_refused(err)))?;
    let (admin, row) = match (admin, row) {
        (Some(admin), Some(row)) if row.admin_id == admin.id => (admin, row),
        (None, Some(row)) => {
            // The credential is known: its account isn't ready (removed, invited, reset), or the
            // handle names no ready account while the credential's account is (qa-passkeys-1 B-N4).
            let owner: Option<(String, bool)> = sqlx::query_as(concat!(
                "SELECT username, ",
                crate::ready_sql!(),
                " FROM admin_users WHERE id = ?"
            ))
            .bind(row.admin_id)
            .fetch_optional(&state.db)
            .await
            .ok()
            .flatten();
            let why = match owner {
                Some((_, true)) => Refused::UserHandle,
                _ => Refused::NotReady,
            };
            return Err(NotSignedIn {
                why,
                username: owner.map(|(username, _)| username),
            });
        }
        (Some(admin), Some(_)) => {
            return Err(NotSignedIn {
                why: Refused::UserHandle,
                username: Some(admin.username),
            });
        }
        (_, None) => return Err(refused(Refused::UnknownCredential)),
    };
    let username = Some(admin.username.clone());
    finish(state, &row, &assertion, ceremony, &rp_id)
        .await
        .map_err(|why| NotSignedIn {
            why,
            username: username.clone(),
        })?;
    Ok(SignedIn {
        admin,
        passkey: row,
    })
}

/// A re-auth's assertion (D1.6): the `reauth` challenge of this session, account, epoch, RP ID
/// and route, a credential of the signed-in parent's (another parent's passkey proves nothing
/// here), its user handle - when the browser sends one - the account's; then [finish].
pub async fn reauth(
    state: &AppState,
    session: &tower_sessions::Session,
    ceremony: &Ceremony,
    admin: &AdminUser,
    json: &str,
) -> Result<PasskeyRow, Refused> {
    let assertion = Assertion::parse(json)?;
    let (Some(session), Some(rp_id)) = (session_id(session), ceremony.rp_id.clone()) else {
        return Err(Refused::Challenge);
    };
    let bound = Bound {
        kind: Kind::Reauth,
        session,
        admin: Some((admin.id, admin.session_epoch)),
        rp_id: rp_id.clone(),
        route: ceremony.route.clone(),
    };
    state
        .passkeys
        .challenges
        .take(&assertion.client.challenge, &bound)
        .ok_or(Refused::Challenge)?;
    let row = find(&state.db, &assertion.credential_id)
        .await
        .map_err(db_refused)?
        .filter(|row| row.admin_id == admin.id)
        .ok_or(Refused::UnknownCredential)?;
    if let Some(handle) = assertion.user_handle.as_deref() {
        let own: Option<Vec<u8>> =
            sqlx::query_scalar("SELECT webauthn_user_id FROM admin_users WHERE id = ?")
                .bind(admin.id)
                .fetch_optional(&state.db)
                .await
                .map_err(db_refused)?
                .flatten();
        if own.as_deref() != Some(handle) {
            return Err(Refused::UserHandle);
        }
    }
    finish(state, &row, &assertion, ceremony, &rp_id).await?;
    Ok(row)
}

/// Logs a refused ceremony: `passkey_cloned` for a counter that didn't move forward (D5.3),
/// else `passkey_refused`, with what it was (`security.detail.passkey.<context>`) and the code.
pub async fn log_refused(
    db: &sqlx::SqlitePool,
    why: Refused,
    context: &'static str,
    username: Option<&str>,
    ip: Option<&str>,
) {
    let event = if why == Refused::Counter {
        "passkey_cloned"
    } else {
        "passkey_refused"
    };
    crate::security::record_security_event(
        db,
        event,
        username,
        ip,
        Some(crate::security::Detail::words(context, &[]).with_facts(why.code())),
    )
    .await;
}

/// The options a browser's `navigator.credentials.get` takes (as JSON for `static/passkey.js`):
/// the challenge, the RP ID, UV required, and the credentials it may use (empty for a sign-in).
/// `timeout` is the challenge's time left ([CHALLENGE_TTL] for a new one).
pub fn request_options(
    challenge: &[u8],
    rp_id: &str,
    allow: &[PasskeyRow],
    timeout: Duration,
) -> serde_json::Value {
    serde_json::json!({
        "challenge": webauthn::b64url(challenge),
        "rpId": rp_id,
        "timeout": u64::try_from(timeout.as_millis()).unwrap_or(TIMEOUT_MS).min(TIMEOUT_MS),
        "userVerification": "required",
        "allowCredentials": allow.iter().map(descriptor).collect::<Vec<_>>(),
    })
}

/// A credential as `allowCredentials`/`excludeCredentials` name it.
fn descriptor(row: &PasskeyRow) -> serde_json::Value {
    let mut out =
        serde_json::json!({ "type": "public-key", "id": webauthn::b64url(&row.credential_id) });
    if let Some(transports) = row
        .transports
        .as_deref()
        .and_then(|t| serde_json::from_str::<Vec<String>>(t).ok())
    {
        out["transports"] = serde_json::json!(transports);
    }
    out
}

/// The creation options (D1.2).
pub fn creation_options(
    challenge: &[u8],
    rp_id: &str,
    user_id: &[u8],
    admin: &AdminUser,
    exclude: &[PasskeyRow],
) -> serde_json::Value {
    serde_json::json!({
        "challenge": webauthn::b64url(challenge),
        "rp": { "id": rp_id, "name": "Vibb" },
        "user": {
            "id": webauthn::b64url(user_id),
            "name": admin.username,
            "displayName": admin.name(),
        },
        "pubKeyCredParams": webauthn::ALGS
            .iter()
            .map(|alg| serde_json::json!({ "type": "public-key", "alg": alg }))
            .collect::<Vec<_>>(),
        "authenticatorSelection": { "residentKey": "required", "requireResidentKey": true, "userVerification": "required" },
        "attestation": "none",
        "excludeCredentials": exclude.iter().map(descriptor).collect::<Vec<_>>(),
        "timeout": TIMEOUT_MS,
    })
}

/// A passkey as Account's row shows it (§6.1).
pub struct Shown {
    pub id: i64,
    pub name: String,
    /// "Added 1 Nov · Last used today 14:02" (from where, when known).
    pub sub: String,
    /// "Synced" / "Only on this device" (BS; + "for handy.example.com" when the family's passkeys
    /// are for more than one RP ID).
    pub kind: String,
    /// The address (RP ID) it is for, and whether it is the account's only passkey there while others are
    /// elsewhere: a passkey-only parent's removal then says signing in there stops (qa-passkeys-2 L1).
    pub rp_id: String,
    pub last_for_host: bool,
}

/// Account's rows of `admin`'s passkeys, in `zone`'s words.
pub fn shown(
    rows: &[PasskeyRow],
    several_rp_ids: bool,
    zone: &crate::display_time::Zone,
    now: chrono::DateTime<chrono::Utc>,
) -> Vec<Shown> {
    use crate::i18n::{t, t_params};
    let lang = zone.lang();
    rows.iter()
        .map(|row| {
            let added = crate::display_time::parse_db_utc(&row.created_at)
                .map(|at| zone.date_of(zone.local(at).date_naive(), zone.today(now)))
                .unwrap_or_default();
            let used = row
                .last_used_at
                .as_deref()
                .and_then(crate::display_time::parse_db_utc)
                .map(|at| {
                    if zone.is_today(at, now) {
                        t_params(lang, "time.today", &[("clock", &zone.clock(at))])
                    } else {
                        zone.at(at, now)
                    }
                });
            let used = match (used, row.last_used_from.as_deref()) {
                (None, _) => t(lang, "passkeys.row.not_used").to_string(),
                (Some(when), Some(from)) => t_params(
                    lang,
                    "passkeys.row.used_from",
                    &[("when", &when), ("from", from)],
                ),
                (Some(when), None) => t_params(lang, "passkeys.row.used", &[("when", &when)]),
            };
            // BS as the last use reported it (D5.3: it can change).
            let mut kind = t(
                lang,
                if row.backed_up {
                    "passkeys.row.synced"
                } else {
                    "passkeys.row.device"
                },
            )
            .to_string();
            if several_rp_ids {
                kind.push_str(" · ");
                kind.push_str(&t_params(lang, "passkeys.row.for", &[("host", &row.rp_id)]));
            }
            let here = rows.iter().filter(|r| r.rp_id == row.rp_id).count();
            Shown {
                id: row.id,
                rp_id: row.rp_id.clone(),
                last_for_host: here == 1 && rows.len() > 1,
                name: row.name.clone(),
                sub: format!(
                    "{} · {used}",
                    t_params(lang, "passkeys.row.added", &[("when", &added)])
                ),
                kind,
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn by(client: &str) -> Asker<'_> {
        Asker {
            client,
            crowd: client,
        }
    }

    fn bound(session: &str) -> Bound {
        Bound {
            kind: Kind::Reauth,
            session: session.to_string(),
            admin: Some((1, 4)),
            rp_id: "handy.example.com".to_string(),
            route: "/account/password".to_string(),
        }
    }

    /// D1.7: one use; every field must match; a mismatch spends it too; 5 minutes.
    #[test]
    fn a_challenge_is_taken_once_and_only_as_bound() {
        let store = Challenges::default();
        let c = store.issue(bound("s1"), by("c"), Taken::default()).unwrap();
        assert!(store.take(&c, &bound("s1")).is_some());
        assert!(store.take(&c, &bound("s1")).is_none(), "used twice");
        let wrong = [
            Bound {
                session: "s2".into(),
                ..bound("s1")
            },
            Bound {
                kind: Kind::Login,
                ..bound("s1")
            },
            Bound {
                admin: Some((1, 5)),
                ..bound("s1")
            },
            Bound {
                admin: Some((2, 4)),
                ..bound("s1")
            },
            Bound {
                rp_id: "example.com".into(),
                ..bound("s1")
            },
            Bound {
                route: "/account/reset-2fa".into(),
                ..bound("s1")
            },
        ];
        for other in wrong {
            let c = store.issue(bound("s1"), by("c"), Taken::default()).unwrap();
            assert!(store.take(&c, &other).is_none(), "{other:?}");
            assert!(
                store.take(&c, &bound("s1")).is_none(),
                "spent by the wrong take"
            );
        }
        let now = Instant::now();
        let c = store
            .issue_at(now, bound("s1"), by("c"), Taken::default())
            .unwrap();
        assert!(
            store
                .take_at(now + CHALLENGE_TTL, &c, &bound("s1"))
                .is_none()
        );
        assert!(store.take(&[1, 2, 3], &bound("s1")).is_none());
    }

    fn sign_in(session: &str) -> Bound {
        Bound {
            kind: Kind::Login,
            admin: None,
            route: SIGN_IN_ROUTE.to_string(),
            ..bound(session)
        }
    }

    #[test]
    fn the_store_keeps_its_bounds() {
        let store = Challenges::default();
        // One session keeps its newest few.
        let first = store
            .issue(bound("one"), by("c"), Taken::default())
            .unwrap();
        for _ in 0..PER_SESSION {
            store
                .issue(bound("one"), by("c"), Taken::default())
                .unwrap();
        }
        assert_eq!(store.len(), PER_SESSION);
        assert!(
            store.take(&first, &bound("one")).is_none(),
            "the oldest went"
        );
        // A client's sign-ins: its newest three.
        let store = Challenges::default();
        let oldest = store
            .issue(sign_in("a"), by("c"), Taken::default())
            .unwrap();
        for s in ["b", "c", "d"] {
            store.issue(sign_in(s), by("c"), Taken::default()).unwrap();
        }
        assert_eq!(store.len(), SIGN_IN_PER_CLIENT);
        assert!(store.take(&oldest, &sign_in("a")).is_none());
        assert!(store.live_sign_in("d", "handy.example.com").is_some());
        assert!(store.live_sign_in("d", "handy.example.com").unwrap().1 <= CHALLENGE_TTL);
        assert!(store.live_sign_in("d", "example.com").is_none());
    }

    /// qa-passkeys-1 A-M2: signed-out clients fill only the sign-in pool, which drops its oldest;
    /// a signed-in ceremony is still issued, and a signed-in pool that is full refuses.
    #[test]
    fn sign_ins_never_crowd_out_signed_in_ceremonies() {
        let store = Challenges::default();
        let now = Instant::now();
        let first = store
            .issue_at(now, sign_in("s0"), by("k0"), Taken::default())
            .unwrap();
        for i in 1..MAX_SIGN_IN + 50 {
            let at = now + Duration::from_millis(i as u64);
            assert!(
                store
                    .issue_at(
                        at,
                        sign_in(&format!("s{i}")),
                        by(&format!("k{i}")),
                        Taken::default()
                    )
                    .is_some()
            );
        }
        assert_eq!(store.len(), MAX_SIGN_IN);
        assert!(
            store.take(&first, &sign_in("s0")).is_none(),
            "the oldest went"
        );
        let later = now + Duration::from_secs(1);
        for i in 0..MAX_SIGNED_IN {
            assert!(
                store
                    .issue_at(later, bound(&format!("p{i}")), by("k"), Taken::default())
                    .is_some(),
                "{i}"
            );
        }
        assert!(
            store
                .issue_at(later, bound("full"), by("k"), Taken::default())
                .is_none()
        );
        // The sign-in pool still issues while the signed-in one is full, and that one frees with time.
        assert!(
            store
                .issue_at(later, sign_in("x"), by("kx"), Taken::default())
                .is_some()
        );
        assert!(
            store
                .issue_at(
                    now + CHALLENGE_TTL + Duration::from_secs(2),
                    bound("full"),
                    by("k"),
                    Taken::default()
                )
                .is_some()
        );
    }

    /// Step 2 (qa-passkeys-1 A-R1): a full sign-in pool drops from the crowd holding the most, so a flood from many
    /// fresh clients of one range - each under its per-client cap - never evicts a parent's one challenge from
    /// elsewhere, however old it is; with every crowd at one, the oldest goes as before.
    #[test]
    fn a_full_sign_in_pool_drops_from_its_biggest_crowd() {
        let store = Challenges::default();
        let now = Instant::now();
        let parent = Asker {
            client: "203.0.113.7",
            crowd: "203.0.113.0/24",
        };
        let mine = store
            .issue_at(now, sign_in("parent"), parent, Taken::default())
            .unwrap();
        for i in 0..MAX_SIGN_IN * 3 {
            let client = format!("2001:db8:1:{:x}00::/56", i % 256 + 1);
            let at = now + Duration::from_millis(i as u64 + 1);
            let asker = Asker {
                client: &client,
                crowd: "2001:db8:1::/48",
            };
            store
                .issue_at(at, sign_in(&format!("flood-{i}")), asker, Taken::default())
                .unwrap();
        }
        assert_eq!(store.len(), MAX_SIGN_IN);
        assert!(
            store.take(&mine, &sign_in("parent")).is_some(),
            "the parent's challenge stayed"
        );
        // Every crowd at one: the oldest goes.
        let store = Challenges::default();
        let first = store
            .issue_at(now, sign_in("s0"), by("c0"), Taken::default())
            .unwrap();
        for i in 1..=MAX_SIGN_IN {
            let at = now + Duration::from_millis(i as u64);
            store
                .issue_at(
                    at,
                    sign_in(&format!("s{i}")),
                    by(&format!("c{i}")),
                    Taken::default(),
                )
                .unwrap();
        }
        assert!(store.take(&first, &sign_in("s0")).is_none());
    }

    /// Step 2 (qa-passkeys-1 B-RN2): a session's live sign-in challenge is handed out again only while younger than
    /// [LIVE_REUSE]; past it the page gets a fresh one with its full five minutes.
    #[test]
    fn a_live_sign_in_is_reused_only_while_young() {
        let store = Challenges::default();
        let now = Instant::now();
        let c = store
            .issue_at(now, sign_in("s"), by("c"), Taken::default())
            .unwrap();
        let rp = "handy.example.com";
        assert_eq!(
            store.live_sign_in_at(now, "s", rp),
            Some((c, CHALLENGE_TTL))
        );
        // Handed out again with the time it has left (qa-passkeys-2 limiter L1).
        assert_eq!(
            store.live_sign_in_at(now + LIVE_REUSE - Duration::from_secs(1), "s", rp),
            Some((c, CHALLENGE_TTL - LIVE_REUSE + Duration::from_secs(1)))
        );
        assert_eq!(store.live_sign_in_at(now + LIVE_REUSE, "s", rp), None);
        // Still takeable until its five minutes end: a prompt opened from it isn't cut short.
        assert!(store.take_at(now + LIVE_REUSE, &c, &sign_in("s")).is_some());
    }

    #[test]
    fn the_reauth_routes() {
        for ok in [
            "/account/password",
            "/account/reset-2fa",
            "/account/parents",
            "/account/parents/3/reset",
            "/account/parents/3/remove",
            "/account/passkeys/options",
            "/account/passkeys/12/remove",
            "/devices/4/lock",
        ] {
            assert!(reauth_route(ok), "{ok}");
        }
        for bad in [
            "/account",
            "/auth/passkey",
            "/account/passkeys",
            "/account/parents/x/reset",
            "/devices/4/lock/extra",
            "/devices//lock",
            "/logout",
        ] {
            assert!(!reauth_route(bad), "{bad}");
        }
    }

    #[test]
    fn a_device_name_from_the_user_agent() {
        let iphone = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15";
        assert_eq!(device_name(iphone), Some("iPhone"));
        let android =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/130 Mobile";
        assert_eq!(device_name(android), Some("Android"));
        let mac = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Safari/605";
        assert_eq!(device_name(mac), Some("Mac"));
        assert_eq!(device_name("curl/8.0"), None);
    }
}
