//! The hardware-bound phone key, S1: observe (design 129, `docs/design/129-hardware-bound-phone-key.md`).
//!
//! - **The key**: a phone registers a Keystore key with its attestation chain (`routes`; at enrolment,
//!   `handlers::device_api::enroll`). The chain is checked by `attestation` before any transaction; a key that passes
//!   is stored `observed` under a random key id ([KeyIndex] keeps them in memory, as `security::TokenIndex` keeps the
//!   bearers).
//! - **Every request signed** (§4): `Vibb-Signature` and `Vibb-Body-SHA256` are checked at the edge from the headers
//!   alone ([DeviceKeys::check], called by `limits::device_edge`), before the body is read and before the request
//!   takes a per-device slot; the body is hashed as it streams ([HashingBody]) and a mismatch never reaches a handler.
//! - **Observe mode** (§8, S1): a valid signature authenticates; a failing one is logged and, when the same phone's
//!   bearer came with it, the bearer still authenticates. The bearer keeps working for every phone; nothing in S1
//!   can lock a phone out.
//! - **The signature clock** ([SigClock], §4.3): the server's own, never stepping back across a restart; every device
//!   answer carries it as `Vibb-Time`.

pub mod routes;

use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant};

use axum::http::{HeaderMap, Method, Uri};
use base64::Engine;
use sha2::Digest;

use crate::attestation::{self, Attested, Expect, Refusal, Revocations};

/// The headers (§4.2).
pub const SIGNATURE_HEADER: &str = "vibb-signature";
pub const BODY_HASH_HEADER: &str = "vibb-body-sha256";
pub const TIME_HEADER: &str = "vibb-time";
pub const AUTH_HEADER: &str = "vibb-auth";

/// The window (§4.3, L4): `sig_now - PAST <= t <= sig_now + FUTURE`.
pub const WINDOW_PAST_MS: i64 = 60_000;
pub const WINDOW_FUTURE_MS: i64 = 30_000;
/// The signature clock is written this often (and at shutdown); the floor after a restart is the stored value
/// plus this and the future window.
pub const CLOCK_WRITE_EVERY: Duration = Duration::from_secs(10);
/// Nonces remembered per key within the window; past it the key's requests get a 429.
pub const NONCES_PER_KEY: usize = 4096;
/// A registration challenge lives this long (§5.2 step 4).
pub const CHALLENGE_TTL_S: i64 = 600;
/// The addresses a phone's bearer came from are kept this long (§5.2, `device_addresses`).
pub const ADDRESS_DAYS: i64 = 30;
/// An address already written is written again (its `last_seen`) at most this often.
const ADDRESS_REWRITE: Duration = Duration::from_secs(3600);
/// A key's `last_signed_at` is written at most this often.
const SIGNED_REWRITE: Duration = Duration::from_secs(600);

/// Why a signed request (or a request a phone with a key sent) was refused: the `Vibb-Auth` word (§4.4).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AuthReason {
    Stale,
    Replay,
    UnknownKey,
    BadSig,
    BadBody,
    Missing,
    KeyRequired,
}

impl AuthReason {
    pub const ALL: &[AuthReason] = &[
        AuthReason::Stale,
        AuthReason::Replay,
        AuthReason::UnknownKey,
        AuthReason::BadSig,
        AuthReason::BadBody,
        AuthReason::Missing,
        AuthReason::KeyRequired,
    ];

    pub fn word(self) -> &'static str {
        match self {
            AuthReason::Stale => "stale",
            AuthReason::Replay => "replay",
            AuthReason::UnknownKey => "unknown_key",
            AuthReason::BadSig => "bad_sig",
            AuthReason::BadBody => "bad_body",
            AuthReason::Missing => "missing",
            AuthReason::KeyRequired => "key_required",
        }
    }

    /// Every refusal but `stale` counts against the client's failure bucket and is logged (§4.4).
    pub fn counts(self) -> bool {
        self != AuthReason::Stale
    }
}

/// A key's state (§8's `device_keys.state`). S1 stores `observed` alone.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum KeyState {
    Observed,
    Bound,
    Held,
    Pending,
    Retired,
}

impl KeyState {
    pub fn word(self) -> &'static str {
        match self {
            KeyState::Observed => "observed",
            KeyState::Bound => "bound",
            KeyState::Held => "held",
            KeyState::Pending => "pending",
            KeyState::Retired => "retired",
        }
    }

    pub fn from_word(word: &str) -> Option<KeyState> {
        [
            KeyState::Observed,
            KeyState::Bound,
            KeyState::Held,
            KeyState::Pending,
            KeyState::Retired,
        ]
        .into_iter()
        .find(|s| s.word() == word)
    }
}

#[derive(Clone, Debug)]
struct IndexedKey {
    device_id: i64,
    public_key: Arc<[u8]>,
}

/// `key id -> device and public key` for every key that may sign (not `retired`), so a signature check never
/// touches the database. Rebuilt at start and after every key write.
#[derive(Default)]
pub struct KeyIndex {
    map: RwLock<HashMap<String, IndexedKey>>,
    reload: tokio::sync::Mutex<()>,
}

impl KeyIndex {
    fn lookup(&self, key_id: &str) -> Option<IndexedKey> {
        self.map
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .get(key_id)
            .cloned()
    }

    pub async fn reload(&self, db: &sqlx::SqlitePool) -> Result<(), sqlx::Error> {
        let _one_at_a_time = self.reload.lock().await;
        let rows: Vec<(String, i64, Vec<u8>)> = sqlx::query_as(
            "SELECT key_id, device_id, public_key FROM device_keys WHERE state != 'retired'",
        )
        .fetch_all(db)
        .await?;
        *self.map.write().unwrap_or_else(|e| e.into_inner()) = rows
            .into_iter()
            .map(|(id, device_id, key)| {
                (
                    id,
                    IndexedKey {
                        device_id,
                        public_key: key.into(),
                    },
                )
            })
            .collect();
        Ok(())
    }

    /// After a key write for `device_id`: [KeyIndex::reload]; if the database can't be read, the device's keys are
    /// dropped from memory (a removed key must never keep signing; a new one waits for the next reload).
    pub async fn refresh(&self, db: &sqlx::SqlitePool, device_id: i64) {
        if let Err(err) = self.reload(db).await {
            tracing::error!(device_id, %err, "couldn't reload the device keys - dropping the phone's");
            self.map
                .write()
                .unwrap_or_else(|e| e.into_inner())
                .retain(|_, k| k.device_id != device_id);
        }
    }

    #[cfg(test)]
    pub fn len(&self) -> usize {
        self.map.read().unwrap().len()
    }
}

/// The server's signature clock (§4.3, M5): `base + monotonic time since start`, where `base` is the larger of the
/// wall clock at start and the floor read from the database. It never steps back - an NTP step or a VM snapshot
/// moves nothing - and runs ahead of a wall clock that is behind it; it catches up only at a restart.
pub struct SigClock {
    base_ms: i64,
    start: Instant,
}

impl Default for SigClock {
    fn default() -> Self {
        SigClock::starting(None, chrono::Utc::now().timestamp_millis())
    }
}

impl SigClock {
    /// The clock at a start: `stored` is the last written `sig_now` (if any), `wall_ms` the wall clock now.
    pub fn starting(stored: Option<i64>, wall_ms: i64) -> SigClock {
        SigClock {
            base_ms: sig_base(stored, wall_ms),
            start: Instant::now(),
        }
    }

    pub fn now_ms(&self) -> i64 {
        self.base_ms + self.start.elapsed().as_millis() as i64
    }
}

/// The base at a start (the contract's `clock` rows): `max(wall, stored + 10 s + the future window)`.
pub fn sig_base(stored: Option<i64>, wall_ms: i64) -> i64 {
    match stored {
        Some(stored) => {
            wall_ms.max(stored + CLOCK_WRITE_EVERY.as_millis() as i64 + WINDOW_FUTURE_MS)
        }
        None => wall_ms,
    }
}

/// Whether `t` is inside the window at `sig_now` (the contract's `window` rows).
pub fn in_window(t: i64, sig_now: i64) -> bool {
    sig_now - WINDOW_PAST_MS <= t && t <= sig_now + WINDOW_FUTURE_MS
}

/// The nonces seen per key within the window (§4.3): recorded only after a signature verified (L2).
#[derive(Default)]
struct Nonces {
    per_key: Mutex<HashMap<String, KeyNonces>>,
}

#[derive(Default)]
struct KeyNonces {
    order: VecDeque<(i64, String)>,
    seen: HashSet<String>,
}

enum NonceVerdict {
    New,
    Replay,
    Full,
}

impl Nonces {
    fn record(&self, key_id: &str, nonce: &str, t: i64, sig_now: i64) -> NonceVerdict {
        let mut per_key = self.per_key.lock().unwrap_or_else(|e| e.into_inner());
        if per_key.len() >= crate::limits::MAX_KEYS && !per_key.contains_key(key_id) {
            // The key whose newest nonce is oldest goes: its nonces are past the window or nearly.
            if let Some(oldest) = per_key
                .iter()
                .min_by_key(|(_, k)| k.order.back().map(|(t, _)| *t).unwrap_or(i64::MIN))
                .map(|(id, _)| id.clone())
            {
                per_key.remove(&oldest);
            }
        }
        let entry = per_key.entry(key_id.to_string()).or_default();
        // Nonces whose `t` has left the window can't come back: `stale` refuses them first.
        while let Some((front_t, _)) = entry.order.front() {
            if *front_t < sig_now - WINDOW_PAST_MS {
                let (_, old) = entry.order.pop_front().expect("a front");
                entry.seen.remove(&old);
            } else {
                break;
            }
        }
        if entry.seen.contains(nonce) {
            return NonceVerdict::Replay;
        }
        if entry.seen.len() >= NONCES_PER_KEY {
            return NonceVerdict::Full;
        }
        entry.seen.insert(nonce.to_string());
        // Kept in `t` order for the pruning above (requests arrive nearly in order; a late one is pruned a little
        // late, never early).
        entry.order.push_back((t, nonce.to_string()));
        NonceVerdict::New
    }
}

/// A test hook: the attestation check (the real [attestation::verify], or the tests' fake - §7.2: tests that aren't
/// about attestation never need a real chain).
pub type Attest = fn(&[Vec<u8>], &Expect, &dyn Revocations) -> Result<Attested, Refusal>;

/// Everything the phone keys need in memory (`AppState.device_keys`).
pub struct DeviceKeys {
    pub index: KeyIndex,
    pub clock: SigClock,
    nonces: Nonces,
    /// Google's revocation list (`attestation::status_list`).
    pub status: attestation::status_list::StatusList,
    pub attest: Attest,
    addresses: Mutex<HashMap<(i64, String), Instant>>,
    signed: Mutex<HashMap<String, Instant>>,
}

impl Default for DeviceKeys {
    fn default() -> Self {
        DeviceKeys::with_clock(SigClock::default())
    }
}

impl DeviceKeys {
    pub fn with_clock(clock: SigClock) -> DeviceKeys {
        DeviceKeys {
            index: Default::default(),
            clock,
            nonces: Default::default(),
            status: Default::default(),
            attest: attestation::verify,
            addresses: Default::default(),
            signed: Default::default(),
        }
    }

    /// The edge's reading of a request's signature (§4.5 steps 1-2): `None` when it has no `Vibb-Signature`.
    /// A valid one has its nonce recorded here.
    pub fn check(&self, method: &Method, uri: &Uri, headers: &HeaderMap) -> Option<SigCheck> {
        let header = headers.get(SIGNATURE_HEADER)?;
        Some(self.check_at(method, uri, headers, header, self.clock.now_ms()))
    }

    fn check_at(
        &self,
        method: &Method,
        uri: &Uri,
        headers: &HeaderMap,
        header: &axum::http::HeaderValue,
        sig_now: i64,
    ) -> SigCheck {
        let refused = |reason, device_id| SigCheck::Refused { reason, device_id };
        let Some(parsed) = header.to_str().ok().and_then(SignatureHeader::parse) else {
            return refused(AuthReason::BadSig, None);
        };
        let Some(key) = self.index.lookup(&parsed.key_id) else {
            return refused(AuthReason::UnknownKey, None);
        };
        if !in_window(parsed.t, sig_now) {
            return refused(AuthReason::Stale, Some(key.device_id));
        }
        let Some(body_hash) = headers
            .get(BODY_HASH_HEADER)
            .and_then(|v| v.to_str().ok())
            .filter(|v| v.len() == 64 && v.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f')))
        else {
            return refused(AuthReason::Missing, Some(key.device_id));
        };
        let content_encoding = headers
            .get(axum::http::header::CONTENT_ENCODING)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("");
        let Some(target) = signed_target(uri) else {
            return refused(AuthReason::BadSig, Some(key.device_id));
        };
        let message = canonical(
            &parsed.key_id,
            parsed.t,
            &parsed.nonce,
            method.as_str(),
            &target,
            content_encoding,
            body_hash,
        );
        if !verify_signature(&key.public_key, message.as_bytes(), &parsed.signature) {
            return refused(AuthReason::BadSig, Some(key.device_id));
        }
        match self
            .nonces
            .record(&parsed.key_id, &parsed.nonce, parsed.t, sig_now)
        {
            NonceVerdict::New => {}
            NonceVerdict::Replay => return refused(AuthReason::Replay, Some(key.device_id)),
            NonceVerdict::Full => return SigCheck::TooMany,
        }
        let mut expected = [0u8; 32];
        hex::decode_to_slice(body_hash, &mut expected).expect("checked hex");
        SigCheck::Valid {
            device_id: key.device_id,
            key_id: parsed.key_id,
            body_sha256: expected,
        }
    }

    /// Notes an address a phone's bearer request came from (§5.2's `device_addresses`), written at most hourly.
    pub async fn note_address(&self, db: &sqlx::SqlitePool, device_id: i64, address: &str) {
        {
            let mut seen = self.addresses.lock().unwrap_or_else(|e| e.into_inner());
            let key = (device_id, address.to_string());
            if seen
                .get(&key)
                .is_some_and(|at| at.elapsed() < ADDRESS_REWRITE)
            {
                return;
            }
            if seen.len() >= crate::limits::MAX_KEYS {
                crate::limits::evict_oldest(&mut seen, |at| *at);
            }
            seen.insert(key, Instant::now());
        }
        if let Err(err) = sqlx::query(
            "INSERT INTO device_addresses (device_id, address, first_seen, last_seen) \
             VALUES (?, ?, datetime('now'), datetime('now')) \
             ON CONFLICT (device_id, address) DO UPDATE SET last_seen = excluded.last_seen",
        )
        .bind(device_id)
        .bind(address)
        .execute(db)
        .await
        {
            tracing::warn!(device_id, %err, "can't note the phone's address");
        }
    }

    /// Notes that a key's signature verified (About This Phone's "signs"), written at most every 10 minutes.
    pub async fn note_signed(&self, db: &sqlx::SqlitePool, key_id: &str) {
        {
            let mut signed = self.signed.lock().unwrap_or_else(|e| e.into_inner());
            if signed
                .get(key_id)
                .is_some_and(|at| at.elapsed() < SIGNED_REWRITE)
            {
                return;
            }
            if signed.len() >= crate::limits::MAX_KEYS {
                crate::limits::evict_oldest(&mut signed, |at| *at);
            }
            signed.insert(key_id.to_string(), Instant::now());
        }
        if let Err(err) =
            sqlx::query("UPDATE device_keys SET last_signed_at = datetime('now') WHERE key_id = ?")
                .bind(key_id)
                .execute(db)
                .await
        {
            tracing::warn!(%err, "can't note a key's signature");
        }
    }

    /// Forgets a removed phone's addresses in memory.
    pub fn forget_device(&self, device_id: i64) {
        self.addresses
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .retain(|(id, _), _| *id != device_id);
    }
}

/// The edge's verdict on a request's signature.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SigCheck {
    /// Verified, nonce new: the request is authenticated as `device_id` (its body must hash to `body_sha256`).
    Valid {
        device_id: i64,
        key_id: String,
        body_sha256: [u8; 32],
    },
    /// Refused; `device_id` when the key id was known.
    Refused {
        reason: AuthReason,
        device_id: Option<i64>,
    },
    /// The key's nonce set is full: a 429.
    TooMany,
}

/// `Vibb-Signature: v1;k=<key id>;t=<ms>;n=<nonce>;s=<sig>` (§4.2), in that order.
#[derive(Debug, PartialEq, Eq)]
pub struct SignatureHeader {
    pub key_id: String,
    pub t: i64,
    pub nonce: String,
    pub signature: Vec<u8>,
}

impl SignatureHeader {
    pub fn parse(value: &str) -> Option<SignatureHeader> {
        let mut parts = value.split(';');
        if parts.next()? != "v1" {
            return None;
        }
        let key_id = parts.next()?.strip_prefix("k=")?;
        let t = parts.next()?.strip_prefix("t=")?;
        let nonce = parts.next()?.strip_prefix("n=")?;
        let s = parts.next()?.strip_prefix("s=")?;
        if parts.next().is_some() {
            return None;
        }
        // 16 random bytes each, Base64url without padding; the signature a DER ECDSA (at most 72 bytes).
        let b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD;
        if b64.decode(key_id).ok()?.len() != 16 || b64.decode(nonce).ok()?.len() != 16 {
            return None;
        }
        if t.is_empty() || t.len() > 15 || !t.bytes().all(|b| b.is_ascii_digit()) {
            return None;
        }
        let signature = b64.decode(s).ok()?;
        if signature.is_empty() || signature.len() > 80 {
            return None;
        }
        Some(SignatureHeader {
            key_id: key_id.to_string(),
            t: t.parse().ok()?,
            nonce: nonce.to_string(),
            signature,
        })
    }
}

/// The request target as signed (§4.2, L3): the raw path from `/api/devices` on, and `?` + the raw query when there
/// is one - never a decoded form.
pub fn signed_target(uri: &Uri) -> Option<String> {
    let path = uri.path();
    let at = path.find("/api/devices")?;
    let mut target = path[at..].to_string();
    if let Some(query) = uri.query() {
        target.push('?');
        target.push_str(query);
    }
    Some(target)
}

/// The signed bytes (§4.2): eight lines, joined by `\n`, no newline at the end.
pub fn canonical(
    key_id: &str,
    t: i64,
    nonce: &str,
    method: &str,
    target: &str,
    content_encoding: &str,
    body_sha256_hex: &str,
) -> String {
    format!(
        "vibb-req-v1\n{key_id}\n{t}\n{nonce}\n{method}\n{target}\n{content_encoding}\n{body_sha256_hex}"
    )
}

/// One P-256 verify of a DER ECDSA signature.
pub fn verify_signature(public_key: &[u8], message: &[u8], signature: &[u8]) -> bool {
    ring::signature::UnparsedPublicKey::new(&ring::signature::ECDSA_P256_SHA256_ASN1, public_key)
        .verify(message, signature)
        .is_ok()
}

/// A request body hashed as it streams (§4.5 step 3, no buffering): at its end the hash is compared, and a mismatch
/// makes the body yield an error - so the route's extractor fails and the handler never runs - and sets `mismatch`,
/// which the edge turns into a 400 `bad_body`.
pub struct HashingBody {
    inner: axum::body::Body,
    hasher: Option<sha2::Sha256>,
    expected: [u8; 32],
    mismatch: Arc<AtomicBool>,
}

impl HashingBody {
    pub fn new(inner: axum::body::Body, expected: [u8; 32], mismatch: Arc<AtomicBool>) -> Self {
        HashingBody {
            inner,
            hasher: Some(sha2::Sha256::new()),
            expected,
            mismatch,
        }
    }
}

impl http_body::Body for HashingBody {
    type Data = axum::body::Bytes;
    type Error = axum::Error;

    fn poll_frame(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<Option<Result<http_body::Frame<Self::Data>, Self::Error>>> {
        use std::task::Poll;
        if self.hasher.is_none() {
            return Poll::Ready(None);
        }
        match std::pin::Pin::new(&mut self.inner).poll_frame(cx) {
            Poll::Ready(Some(Ok(frame))) => {
                if let Some(data) = frame.data_ref()
                    && let Some(hasher) = self.hasher.as_mut()
                {
                    hasher.update(data);
                }
                Poll::Ready(Some(Ok(frame)))
            }
            Poll::Ready(None) => {
                let hash: [u8; 32] = self.hasher.take().expect("checked").finalize().into();
                if hash == self.expected {
                    Poll::Ready(None)
                } else {
                    self.mismatch.store(true, Ordering::Relaxed);
                    Poll::Ready(Some(Err(axum::Error::new(
                        "the body's hash isn't the signed one",
                    ))))
                }
            }
            other => other,
        }
    }

    fn is_end_stream(&self) -> bool {
        // Never "done" before the hash is compared: a reader that trusted the inner body's end would skip it.
        self.hasher.is_none()
    }

    fn size_hint(&self) -> http_body::SizeHint {
        self.inner.size_hint()
    }
}

/// SHA-256 of nothing: a request without a body.
pub fn empty_body_sha256() -> [u8; 32] {
    sha2::Sha256::digest([]).into()
}

/// A random id or challenge: `n` bytes from the CSPRNG.
pub fn random_bytes<const N: usize>() -> [u8; N] {
    let mut bytes = [0u8; N];
    ring::rand::SecureRandom::fill(&ring::rand::SystemRandom::new(), &mut bytes)
        .expect("the system's random source");
    bytes
}

pub fn b64url(bytes: &[u8]) -> String {
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

/// A key the phone holds, as the server answers it (`GET /key`, the policy, About This Phone).
#[derive(Clone, Debug, sqlx::FromRow)]
pub struct StoredKey {
    pub key_id: String,
    pub device_id: i64,
    pub spki_sha256: String,
    pub security_level: String,
    pub attestation_version: i64,
    pub os_patch_level: Option<i64>,
    pub boot_state: Option<String>,
    pub boot_key: Option<String>,
    pub device_locked: Option<bool>,
    pub root: String,
    pub unusual: bool,
    pub state: String,
    pub revoked_at: Option<String>,
    pub last_signed_at: Option<String>,
    pub created_at: String,
}

impl StoredKey {
    /// Step 2's reading from the stored RootOfTrust (§9).
    pub fn reading(&self) -> attestation::boot::Reading {
        let root = match (&self.boot_state, &self.boot_key, self.device_locked) {
            (Some(state), Some(key), Some(locked)) => {
                attestation::BootState::from_word(state).map(|state| attestation::RootOfTrust {
                    verified_boot_key: hex::decode(key).unwrap_or_default(),
                    device_locked: locked,
                    state,
                })
            }
            _ => None,
        };
        attestation::boot::Reading::of(root.as_ref())
    }
}

/// The phone's current key (any state but `retired`), if any.
pub async fn current_key(
    db: &sqlx::SqlitePool,
    device_id: i64,
) -> Result<Option<StoredKey>, sqlx::Error> {
    sqlx::query_as(
        "SELECT key_id, device_id, spki_sha256, security_level, attestation_version, os_patch_level, boot_state, \
         boot_key, device_locked, root, unusual, state, revoked_at, last_signed_at, created_at FROM device_keys \
         WHERE device_id = ? AND state != 'retired' ORDER BY created_at DESC LIMIT 1",
    )
    .bind(device_id)
    .fetch_optional(db)
    .await
}

/// The policy's `device_key` (§5.5): `{state, key_id?, spki_sha256?, rotate}`. S1 never asks for a rotation.
pub async fn policy_value(
    db: &sqlx::SqlitePool,
    device_id: i64,
) -> Result<serde_json::Value, sqlx::Error> {
    Ok(key_answer(current_key(db, device_id).await?.as_ref()))
}

/// `GET /key`'s answer and the policy's `device_key`, from the current key.
pub fn key_answer(key: Option<&StoredKey>) -> serde_json::Value {
    match key {
        Some(key) => serde_json::json!({
            "state": key.state,
            "key_id": key.key_id,
            "spki_sha256": key.spki_sha256,
            "rotate": false,
        }),
        None => serde_json::json!({ "state": "none", "rotate": false }),
    }
}

/// Stores an attested key for `device_id` on the caller's transaction (`observed` in S1), returning its random id.
/// `address`: the registering client; `unusual` when its bearer never came from there (§5.2, H3).
pub async fn insert_key(
    tx: &mut sqlx::SqliteConnection,
    device_id: i64,
    attested: &Attested,
    via: &str,
    address: &str,
    unusual: bool,
) -> Result<String, sqlx::Error> {
    let key_id = b64url(&random_bytes::<16>());
    let root = attested.root_of_trust.as_ref();
    sqlx::query(
        "INSERT INTO device_keys (key_id, device_id, spki, spki_sha256, public_key, security_level, \
         attestation_version, os_patch_level, boot_state, boot_key, device_locked, serials, root, address, unusual, \
         state, via) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'observed', ?)",
    )
    .bind(&key_id)
    .bind(device_id)
    .bind(&attested.spki)
    .bind(attestation::spki_sha256(&attested.spki))
    .bind(&attested.public_key)
    .bind(attested.level.word())
    .bind(attested.attestation_version)
    .bind(attested.os_patch_level)
    .bind(root.map(|r| r.state.word()))
    .bind(root.map(|r| hex::encode(&r.verified_boot_key)))
    .bind(root.map(|r| r.device_locked))
    .bind(serde_json::to_string(&attested.serials).unwrap_or_else(|_| "[]".into()))
    .bind(attested.root.word())
    .bind(address)
    .bind(unusual)
    .bind(via)
    .execute(&mut *tx)
    .await?;
    Ok(key_id)
}

/// An address counts as the phone's only when its bearer came from there at least this long before the
/// registration's challenge: the registration's own bearer calls (`GET /key`, the challenge) never make an address
/// known.
pub const ADDRESS_KNOWN_BEFORE: &str = "-1 hour";

/// Whether `address` is new for the phone: not among the addresses its bearer came from in the last
/// [ADDRESS_DAYS] days, first seen [ADDRESS_KNOWN_BEFORE] `before` (the registration's challenge).
pub async fn unusual_address(
    db: &sqlx::SqlitePool,
    device_id: i64,
    address: &str,
    before: &str,
) -> Result<bool, sqlx::Error> {
    let known: Option<i64> = sqlx::query_scalar(
        "SELECT 1 FROM device_addresses WHERE device_id = ? AND address = ? AND first_seen < datetime(?, ?) \
         AND last_seen > datetime('now', ?)",
    )
    .bind(device_id)
    .bind(address)
    .bind(before)
    .bind(ADDRESS_KNOWN_BEFORE)
    .bind(format!("-{ADDRESS_DAYS} days"))
    .fetch_optional(db)
    .await?;
    Ok(known.is_none())
}

/// Removes every key of a phone (a re-enrolment, a revoked access) and its challenge; the index follows.
pub async fn remove_keys(state: &crate::AppState, device_id: i64) {
    for sql in [
        "DELETE FROM device_keys WHERE device_id = ?",
        "DELETE FROM device_key_challenges WHERE device_id = ?",
    ] {
        if let Err(err) = sqlx::query(sql).bind(device_id).execute(&state.db).await {
            tracing::error!(device_id, %err, "can't remove the phone's keys");
        }
    }
    state.device_keys.index.refresh(&state.db, device_id).await;
}

/// Reads every stored key's serials against the status list (§6.3): `revoked_at` set for a key whose chain is now
/// listed, cleared for one no longer listed (a suspension lifted). A key keeps working either way.
pub async fn mark_revoked(state: &crate::AppState) {
    let Some(revoked) = state.device_keys.status.any() else {
        return;
    };
    let rows: Vec<(String, String, Option<String>)> =
        match sqlx::query_as("SELECT key_id, serials, revoked_at FROM device_keys")
            .fetch_all(&state.db)
            .await
        {
            Ok(rows) => rows,
            Err(err) => {
                tracing::error!(%err, "can't read the keys' serials");
                return;
            }
        };
    for (key_id, serials, was) in rows {
        let serials: Vec<String> = serde_json::from_str(&serials).unwrap_or_default();
        let now = serials.iter().any(|s| revoked.revoked(s));
        let sql = match (now, was.is_some()) {
            (true, false) => "UPDATE device_keys SET revoked_at = datetime('now') WHERE key_id = ?",
            (false, true) => "UPDATE device_keys SET revoked_at = NULL WHERE key_id = ?",
            _ => continue,
        };
        if let Err(err) = sqlx::query(sql).bind(&key_id).execute(&state.db).await {
            tracing::error!(%err, "can't mark a key's revocation");
        } else if now {
            tracing::warn!(key_id, "a stored phone key's chain is now revoked");
        }
    }
}

/// The signature clock's floor: the stored `sig_now`, if any.
pub async fn stored_clock(db: &sqlx::SqlitePool) -> Option<i64> {
    sqlx::query_scalar("SELECT sig_ms FROM sig_clock WHERE id = 1")
        .fetch_optional(db)
        .await
        .ok()
        .flatten()
}

/// Writes `sig_now` (every [CLOCK_WRITE_EVERY] and at shutdown).
pub async fn store_clock(state: &crate::AppState) {
    let now = state.device_keys.clock.now_ms();
    if let Err(err) = sqlx::query(
        "INSERT INTO sig_clock (id, sig_ms) VALUES (1, ?) \
         ON CONFLICT (id) DO UPDATE SET sig_ms = MAX(sig_ms, excluded.sig_ms)",
    )
    .bind(now)
    .execute(&state.db)
    .await
    {
        tracing::warn!(%err, "can't store the signature clock");
    }
}

/// Writes the signature clock every [CLOCK_WRITE_EVERY] (started by `main`, never by the tests).
pub async fn run_clock_writer(state: crate::AppState) {
    loop {
        store_clock(&state).await;
        tokio::time::sleep(CLOCK_WRITE_EVERY).await;
    }
}

#[cfg(test)]
pub mod testing {
    //! The tests' fake attestation (§7.2): `chain[0]` is the leaf's SPKI, `chain[1]` a JSON verdict
    //! `{"challenge": <Base64url>, "refuse": <word>?, "level": "tee"|"strongbox", "boot": <state>?, "locked": bool}`.

    use super::*;

    pub fn fake_attest(
        chain: &[Vec<u8>],
        expect: &Expect,
        _revocations: &dyn Revocations,
    ) -> Result<Attested, Refusal> {
        let [spki, verdict] = chain else {
            return Err(Refusal::Chain);
        };
        let verdict: serde_json::Value =
            serde_json::from_slice(verdict).map_err(|_| Refusal::Chain)?;
        let challenge = verdict["challenge"].as_str().unwrap_or_default();
        if base64::engine::general_purpose::URL_SAFE_NO_PAD
            .decode(challenge)
            .ok()
            .as_deref()
            != Some(expect.challenge)
        {
            return Err(Refusal::Challenge);
        }
        if let Some(word) = verdict["refuse"].as_str() {
            return Err(Refusal::from_word(word).expect("a refusal word"));
        }
        if expect.signature_digest.len() != 32 {
            return Err(Refusal::App);
        }
        Ok(Attested {
            spki: spki.clone(),
            public_key: spki[spki.len() - 65..].to_vec(),
            level: if verdict["level"] == "strongbox" {
                attestation::SecurityLevel::StrongBox
            } else {
                attestation::SecurityLevel::TrustedEnvironment
            },
            attestation_version: 300,
            os_patch_level: Some(202611),
            root_of_trust: Some(attestation::RootOfTrust {
                verified_boot_key: vec![9; 32],
                device_locked: verdict["locked"].as_bool().unwrap_or(true),
                state: attestation::BootState::from_word(
                    verdict["boot"].as_str().unwrap_or("verified"),
                )
                .expect("a boot state"),
            }),
            serials: vec!["1".into(), "c0ffee".into()],
            root: attestation::roots::Root::GoogleEcCa1,
        })
    }

    /// A P-256 key made at each run, signing as a phone would (§7.2).
    pub struct TestKey {
        pair: ring::signature::EcdsaKeyPair,
    }

    impl Default for TestKey {
        fn default() -> Self {
            TestKey::new()
        }
    }

    impl TestKey {
        pub fn new() -> TestKey {
            let rng = ring::rand::SystemRandom::new();
            let pkcs8 = ring::signature::EcdsaKeyPair::generate_pkcs8(
                &ring::signature::ECDSA_P256_SHA256_ASN1_SIGNING,
                &rng,
            )
            .unwrap();
            TestKey {
                pair: ring::signature::EcdsaKeyPair::from_pkcs8(
                    &ring::signature::ECDSA_P256_SHA256_ASN1_SIGNING,
                    pkcs8.as_ref(),
                    &rng,
                )
                .unwrap(),
            }
        }

        pub fn public_key(&self) -> Vec<u8> {
            ring::signature::KeyPair::public_key(&self.pair)
                .as_ref()
                .to_vec()
        }

        /// The key's SPKI (DER): the fixed P-256 header and the point.
        pub fn spki(&self) -> Vec<u8> {
            let mut spki =
                hex::decode("3059301306072a8648ce3d020106082a8648ce3d030107034200").unwrap();
            spki.extend(self.public_key());
            spki
        }

        pub fn sign(&self, message: &[u8]) -> Vec<u8> {
            self.pair
                .sign(&ring::rand::SystemRandom::new(), message)
                .unwrap()
                .as_ref()
                .to_vec()
        }

        /// A fake chain for [fake_attest].
        pub fn chain(&self, challenge: &[u8], extra: serde_json::Value) -> Vec<String> {
            let mut verdict = serde_json::json!({ "challenge": b64url(challenge) });
            if let (Some(v), Some(extra)) = (verdict.as_object_mut(), extra.as_object()) {
                v.extend(extra.clone());
            }
            vec![
                base64::engine::general_purpose::STANDARD.encode(self.spki()),
                base64::engine::general_purpose::STANDARD.encode(verdict.to_string()),
            ]
        }

        /// The headers of a request signed with `key_id` at `t` (§4.2).
        pub fn headers(
            &self,
            key_id: &str,
            t: i64,
            method: &str,
            target: &str,
            content_encoding: &str,
            body: &[u8],
        ) -> (String, String) {
            let body_hash = hex::encode(sha2::Sha256::digest(body));
            let nonce = b64url(&random_bytes::<16>());
            let message = canonical(
                key_id,
                t,
                &nonce,
                method,
                target,
                content_encoding,
                &body_hash,
            );
            let sig = b64url(&self.sign(message.as_bytes()));
            (format!("v1;k={key_id};t={t};n={nonce};s={sig}"), body_hash)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_header_parses_strictly() {
        let id = b64url(&[1; 16]);
        let n = b64url(&[2; 16]);
        let s = b64url(&[3; 70]);
        let good = format!("v1;k={id};t=1790000000000;n={n};s={s}");
        let parsed = SignatureHeader::parse(&good).unwrap();
        assert_eq!(parsed.t, 1_790_000_000_000);
        for bad in [
            format!("v2;k={id};t=1;n={n};s={s}"),
            format!("v1;t=1;k={id};n={n};s={s}"),
            format!("v1;k={id};t=-1;n={n};s={s}"),
            format!("v1;k={id};t=1;n={n};s={s};x=1"),
            format!("v1;k=short;t=1;n={n};s={s}"),
            format!("v1;k={id};t=1;n={n};s="),
            format!("v1;k={id};t=1;n={n}=;s={s}"),
        ] {
            assert!(SignatureHeader::parse(&bad).is_none(), "{bad}");
        }
    }

    #[test]
    fn the_target_is_raw_and_cut_at_api_devices() {
        let uri: Uri = "/prefix/api/devices/apps/7/download?x=%2F&y=1"
            .parse()
            .unwrap();
        assert_eq!(
            signed_target(&uri).unwrap(),
            "/api/devices/apps/7/download?x=%2F&y=1"
        );
        let plain: Uri = "/api/devices/policy".parse().unwrap();
        assert_eq!(signed_target(&plain).unwrap(), "/api/devices/policy");
        assert!(signed_target(&"/other".parse().unwrap()).is_none());
    }

    #[test]
    fn the_clock_never_steps_back() {
        // A floor ahead of the wall clock wins; the wall clock wins once it's past the floor.
        assert_eq!(sig_base(Some(1_000_000), 900_000), 1_040_000);
        assert_eq!(sig_base(Some(1_000_000), 2_000_000), 2_000_000);
        assert_eq!(sig_base(None, 5), 5);
        let clock = SigClock::starting(Some(10_000_000_000_000), 1);
        assert!(clock.now_ms() >= 10_000_000_040_000);
    }

    #[test]
    fn nonces_replay_and_prune() {
        let nonces = Nonces::default();
        assert!(matches!(
            nonces.record("k", "a", 1_000, 1_000),
            NonceVerdict::New
        ));
        assert!(matches!(
            nonces.record("k", "a", 1_000, 1_000),
            NonceVerdict::Replay
        ));
        assert!(matches!(
            nonces.record("k2", "a", 1_000, 1_000),
            NonceVerdict::New
        ));
        // Past the window the old nonce is gone (and `stale` refuses its `t` before it gets here).
        assert!(matches!(
            nonces.record("k", "b", 200_000, 200_000),
            NonceVerdict::New
        ));
        assert!(!nonces.per_key.lock().unwrap()["k"].seen.contains("a"));
    }

    #[test]
    fn a_full_key_is_refused_not_forgotten() {
        let nonces = Nonces::default();
        for i in 0..NONCES_PER_KEY {
            assert!(matches!(
                nonces.record("k", &i.to_string(), 5_000, 5_000),
                NonceVerdict::New
            ));
        }
        assert!(matches!(
            nonces.record("k", "x", 5_000, 5_000),
            NonceVerdict::Full
        ));
        assert!(matches!(
            nonces.record("k", "0", 5_000, 5_000),
            NonceVerdict::Replay
        ));
    }

    #[tokio::test]
    async fn the_hashing_body_refuses_a_changed_body() {
        use http_body_util::BodyExt;
        let expected: [u8; 32] = sha2::Sha256::digest(b"hello").into();
        let flag = Arc::new(AtomicBool::new(false));
        let body = HashingBody::new(axum::body::Body::from("hello"), expected, flag.clone());
        assert_eq!(body.collect().await.unwrap().to_bytes(), "hello");
        assert!(!flag.load(Ordering::Relaxed));
        let body = HashingBody::new(axum::body::Body::from("hellO"), expected, flag.clone());
        assert!(body.collect().await.is_err());
        assert!(flag.load(Ordering::Relaxed));
    }
}
