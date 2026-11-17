//! The registrations, the queue and the tombstones (design 108 §3.5, §3.6).
//!
//! - **A secret** is looked up by its SHA-256 ([lookup]): a live registration, a tombstone of one deleted within
//!   [GONE_DAYS] (M5), or never seen. The secret itself is never stored, and nothing per message is logged.
//! - **Every deletion of a registration** leaves its tombstone: migration 0152's trigger writes it, whatever deletes
//!   the row - [delete_registrations], [delete_everywhere], retention's [prune], and a phone deleted through `ON
//!   DELETE CASCADE`.
//! - **The queue**: one row per message, until it is acknowledged ([pull]) or expires (at most [TTL_MAX_SECS]). Caps
//!   ([PER_REGISTRATION], [PER_PHONE_ROWS], [PER_PHONE_BYTES]) drop the oldest - a Matrix push is "sync now", so the
//!   newest is the one that matters -, the server-wide [STORE_BYTES] refuses ([Queued::Full], 507).

use base64::Engine as _;
use serde::Serialize;
use sqlx::SqlitePool;

/// Tombstones are answered for this long, then pruned; an unseen phone's registrations go after as long.
pub const GONE_DAYS: i64 = 30;
pub const UNSEEN_DAYS: i64 = 30;
/// A push lives at most a day (Q10); without a `TTL` header it gets the whole day.
pub const TTL_MAX_SECS: i64 = 86_400;
/// A TTL 0 push, queued because the phone's stream is open, waits this long for the pull.
pub const TTL_ZERO_GRACE_SECS: i64 = 30;
pub const PER_REGISTRATION: i64 = 100;
pub const PER_PHONE_ROWS: i64 = 500;
pub const PER_PHONE_BYTES: i64 = 1024 * 1024;
pub const STORE_BYTES: i64 = 64 * 1024 * 1024;
/// One pull answers at most this many messages, or this many bytes of bodies (always at least one).
pub const PULL_MAX: usize = 50;
pub const PULL_BYTES: usize = 256 * 1024;
/// One pull acknowledges at most this many keys.
pub const ACK_MAX: usize = 200;
/// A message body (ntfy's `MessageSizeLimit`).
pub const BODY_MAX: usize = 4096;

/// A live registration, as a push resolves it.
#[derive(sqlx::FromRow, Debug, Clone, PartialEq, Eq)]
pub struct Registration {
    pub id: i64,
    pub device_id: i64,
    pub reg: String,
    pub package: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Lookup {
    Live(Registration),
    /// Deleted within [GONE_DAYS]: 410 / rejected, never counted toward a ban (M5).
    Gone,
    /// Never issued by this server (or its tombstone pruned): 410 / rejected, counted toward the client's ban.
    Unknown,
}

/// An endpoint secret's form: the spec's 160 bits, 27 base64url characters (the launcher makes it, §2.1).
pub fn valid_secret(secret: &str) -> bool {
    secret.len() == 27
        && secret
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

/// A registration key: 32 lowercase hex characters (the launcher's, §2.1).
pub fn valid_reg(reg: &str) -> bool {
    reg.len() == 32
        && reg
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

/// The endpoint the phone hands to its app: `<origin>/up/<secret>`.
pub fn endpoint(origin: &str, secret: &str) -> String {
    format!("{origin}/up/{secret}")
}

/// What `secret` is to this server. A secret that isn't one's form is never looked up.
pub async fn lookup(db: &SqlitePool, secret: &str) -> Result<Lookup, sqlx::Error> {
    if !valid_secret(secret) {
        return Ok(Lookup::Unknown);
    }
    let hash = super::secret_hash(secret);
    if let Some(registration) = sqlx::query_as::<_, Registration>(
        "SELECT id, device_id, reg, package FROM up_registrations WHERE secret_hash = ?",
    )
    .bind(&hash)
    .fetch_optional(db)
    .await?
    {
        return Ok(Lookup::Live(registration));
    }
    let gone: Option<i64> = sqlx::query_scalar(
        "SELECT 1 FROM up_gone WHERE secret_hash = ? AND gone_at > datetime('now', ?)",
    )
    .bind(&hash)
    .bind(format!("-{GONE_DAYS} days"))
    .fetch_optional(db)
    .await?;
    Ok(if gone.is_some() {
        Lookup::Gone
    } else {
        Lookup::Unknown
    })
}

/// Which of a phone's registrations to delete.
#[derive(Debug, Clone, Copy)]
pub enum Which<'a> {
    All,
    Package(&'a str),
    Reg(&'a str),
    /// Every registration whose package isn't on this allowlist (`prune_disallowed`).
    NotIn(&'a [String]),
}

/// The one way a phone's registrations are deleted (tombstoned by the trigger); the rows deleted.
pub async fn delete_registrations<'e>(
    db: impl sqlx::SqliteExecutor<'e>,
    device_id: i64,
    which: Which<'_>,
) -> Result<u64, sqlx::Error> {
    let done = match which {
        Which::All => {
            sqlx::query("DELETE FROM up_registrations WHERE device_id = ?")
                .bind(device_id)
                .execute(db)
                .await?
        }
        Which::Package(package) => {
            sqlx::query("DELETE FROM up_registrations WHERE device_id = ? AND package = ?")
                .bind(device_id)
                .bind(package)
                .execute(db)
                .await?
        }
        Which::Reg(reg) => {
            sqlx::query("DELETE FROM up_registrations WHERE device_id = ? AND reg = ?")
                .bind(device_id)
                .bind(reg)
                .execute(db)
                .await?
        }
        Which::NotIn(allowed) => {
            let allowed = serde_json::to_string(allowed).expect("a list of strings serializes");
            sqlx::query(
                "DELETE FROM up_registrations WHERE device_id = ? \
                 AND package NOT IN (SELECT value FROM json_each(?))",
            )
            .bind(device_id)
            .bind(allowed)
            .execute(db)
            .await?
        }
    };
    Ok(done.rows_affected())
}

/// Every phone's registrations (the public origin cleared, §3.1); the rows deleted.
pub async fn delete_everywhere<'e>(db: impl sqlx::SqliteExecutor<'e>) -> Result<u64, sqlx::Error> {
    Ok(sqlx::query("DELETE FROM up_registrations")
        .execute(db)
        .await?
        .rows_affected())
}

/// One push to queue.
#[derive(Debug, Clone)]
pub struct Push<'a> {
    pub body: &'a [u8],
    pub urgency: &'static str,
    pub topic: Option<&'a str>,
    pub ttl_secs: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Queued {
    /// Its random key (16 bytes, hex): `Location` and the MESSAGE id (L10).
    Key(String),
    /// The server-wide store is full: 507 (a 5xx, so Mastodon retries).
    Full,
}

/// 16 random bytes as hex: a message's key.
pub fn random_key() -> String {
    let mut bytes = [0u8; 16];
    ring::rand::SecureRandom::fill(&ring::rand::SystemRandom::new(), &mut bytes)
        .expect("the system's random source");
    hex::encode(bytes)
}

/// Queues `push` for `registration` in one transaction: expired rows of the phone go first, a `Topic` replaces the
/// registration's pending row with the same topic, then the caps drop the oldest (counted in `dropped`).
pub async fn enqueue(
    db: &SqlitePool,
    registration: &Registration,
    push: &Push<'_>,
    now_ms: i64,
) -> Result<Queued, sqlx::Error> {
    let len = push.body.len() as i64;
    let mut tx = db.begin().await?;
    sqlx::query("DELETE FROM up_messages WHERE device_id = ? AND expires_at_ms <= ?")
        .bind(registration.device_id)
        .bind(now_ms)
        .execute(&mut *tx)
        .await?;
    let stored: i64 = sqlx::query_scalar("SELECT COALESCE(SUM(LENGTH(body)), 0) FROM up_messages")
        .fetch_one(&mut *tx)
        .await?;
    if stored + len > STORE_BYTES {
        return Ok(Queued::Full);
    }
    if let Some(topic) = push.topic {
        sqlx::query("DELETE FROM up_messages WHERE registration_id = ? AND topic = ?")
            .bind(registration.id)
            .bind(topic)
            .execute(&mut *tx)
            .await?;
    }
    let pending: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM up_messages WHERE registration_id = ?")
            .bind(registration.id)
            .fetch_one(&mut *tx)
            .await?;
    if pending >= PER_REGISTRATION {
        let dropped = sqlx::query(
            "DELETE FROM up_messages WHERE id IN (SELECT id FROM up_messages \
             WHERE registration_id = ? ORDER BY id LIMIT ?)",
        )
        .bind(registration.id)
        .bind(pending - PER_REGISTRATION + 1)
        .execute(&mut *tx)
        .await?
        .rows_affected();
        sqlx::query("UPDATE up_registrations SET dropped = dropped + ? WHERE id = ?")
            .bind(dropped as i64)
            .bind(registration.id)
            .execute(&mut *tx)
            .await?;
    }
    // The phone's caps: the oldest row of its largest registration goes, one at a time.
    loop {
        let (rows, bytes): (i64, i64) = sqlx::query_as(
            "SELECT COUNT(*), COALESCE(SUM(LENGTH(body)), 0) FROM up_messages WHERE device_id = ?",
        )
        .bind(registration.device_id)
        .fetch_one(&mut *tx)
        .await?;
        if rows == 0 || (rows < PER_PHONE_ROWS && bytes + len <= PER_PHONE_BYTES) {
            break;
        }
        let oldest: Option<(i64, i64)> = sqlx::query_as(
            "SELECT id, registration_id FROM up_messages WHERE registration_id = ( \
               SELECT registration_id FROM up_messages WHERE device_id = ? \
               GROUP BY registration_id ORDER BY COUNT(*) DESC, registration_id LIMIT 1) \
             ORDER BY id LIMIT 1",
        )
        .bind(registration.device_id)
        .fetch_optional(&mut *tx)
        .await?;
        let Some((id, of)) = oldest else { break };
        sqlx::query("DELETE FROM up_messages WHERE id = ?")
            .bind(id)
            .execute(&mut *tx)
            .await?;
        sqlx::query("UPDATE up_registrations SET dropped = dropped + 1 WHERE id = ?")
            .bind(of)
            .execute(&mut *tx)
            .await?;
    }
    let key = random_key();
    let ttl = if push.ttl_secs == 0 {
        TTL_ZERO_GRACE_SECS
    } else {
        push.ttl_secs.clamp(1, TTL_MAX_SECS)
    };
    sqlx::query(
        "INSERT INTO up_messages (key, registration_id, device_id, body, urgency, topic, \
         created_at_ms, expires_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(&key)
    .bind(registration.id)
    .bind(registration.device_id)
    .bind(push.body)
    .bind(push.urgency)
    .bind(push.topic)
    .bind(now_ms)
    .bind(now_ms + ttl * 1000)
    .execute(&mut *tx)
    .await?;
    sqlx::query(
        "UPDATE up_registrations SET pushes = pushes + 1, last_push_at = datetime('now') \
         WHERE id = ?",
    )
    .bind(registration.id)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(Queued::Key(key))
}

/// One message as a pull answers it (§3.7): `body` is standard base64.
#[derive(Serialize, Debug, Clone, PartialEq, Eq)]
pub struct Message {
    pub key: String,
    pub reg: String,
    pub urgency: String,
    pub body: String,
}

#[derive(Serialize, Debug, Clone, PartialEq, Eq)]
pub struct Pulled {
    pub messages: Vec<Message>,
    pub more: bool,
}

/// A phone's pull: `ack` (this phone's keys only; others are ignored) deleted first and its registrations' last
/// delivery noted, then the next pending messages, oldest first - at most [PULL_MAX] or [PULL_BYTES] of bodies.
pub async fn pull(
    db: &SqlitePool,
    device_id: i64,
    ack: &[String],
    now_ms: i64,
) -> Result<Pulled, sqlx::Error> {
    if !ack.is_empty() {
        let keys = serde_json::to_string(ack).expect("a list of strings serializes");
        let mut tx = db.begin().await?;
        sqlx::query(
            "UPDATE up_registrations SET last_delivered_at = datetime('now') WHERE id IN ( \
               SELECT registration_id FROM up_messages WHERE device_id = ? \
               AND key IN (SELECT value FROM json_each(?)))",
        )
        .bind(device_id)
        .bind(&keys)
        .execute(&mut *tx)
        .await?;
        sqlx::query(
            "DELETE FROM up_messages WHERE device_id = ? \
             AND key IN (SELECT value FROM json_each(?))",
        )
        .bind(device_id)
        .bind(&keys)
        .execute(&mut *tx)
        .await?;
        tx.commit().await?;
    }
    let rows: Vec<(String, String, String, Vec<u8>)> = sqlx::query_as(
        "SELECT m.key, r.reg, m.urgency, m.body FROM up_messages m \
         JOIN up_registrations r ON r.id = m.registration_id \
         WHERE m.device_id = ? AND m.expires_at_ms > ? ORDER BY m.id LIMIT ?",
    )
    .bind(device_id)
    .bind(now_ms)
    .bind(PULL_MAX as i64 + 1)
    .fetch_all(db)
    .await?;
    let mut messages = Vec::new();
    let mut bytes = 0;
    let mut more = false;
    for (key, reg, urgency, body) in rows {
        if messages.len() == PULL_MAX || (!messages.is_empty() && bytes + body.len() > PULL_BYTES) {
            more = true;
            break;
        }
        bytes += body.len();
        messages.push(Message {
            key,
            reg,
            urgency,
            body: base64::engine::general_purpose::STANDARD.encode(&body),
        });
    }
    Ok(Pulled { messages, more })
}

/// Retention's part (`retention::prune`, hourly): expired messages, the registrations of a phone unseen for
/// [UNSEEN_DAYS] (no status report: ntfy's "abandoned" with our knowledge; tombstoned by the trigger), then the
/// tombstones older than [GONE_DAYS]. The rows deleted.
pub async fn prune(db: &SqlitePool, now_ms: i64) -> Result<u64, sqlx::Error> {
    let expired = sqlx::query("DELETE FROM up_messages WHERE expires_at_ms <= ?")
        .bind(now_ms)
        .execute(db)
        .await?
        .rows_affected();
    let unseen = sqlx::query(
        "DELETE FROM up_registrations WHERE device_id IN (SELECT id FROM devices \
         WHERE last_seen_at IS NOT NULL AND last_seen_at < datetime('now', ?))",
    )
    .bind(format!("-{UNSEEN_DAYS} days"))
    .execute(db)
    .await?
    .rows_affected();
    let tombstones = sqlx::query("DELETE FROM up_gone WHERE gone_at < datetime('now', ?)")
        .bind(format!("-{GONE_DAYS} days"))
        .execute(db)
        .await?
        .rows_affected();
    Ok(expired + unseen + tombstones)
}

#[cfg(test)]
mod tests {
    use super::*;

    async fn db() -> (tempfile::TempDir, SqlitePool, i64) {
        let dir = tempfile::tempdir().unwrap();
        let url = format!("sqlite://{}", dir.path().join("t.db").display());
        let db = crate::connect_db(&url).await;
        let id: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES ('Emma') RETURNING id")
            .fetch_one(&db)
            .await
            .unwrap();
        (dir, db, id)
    }

    fn secret(n: u8) -> String {
        format!("FakeSecretForTheStoreTest{n:02}")
    }

    async fn add(db: &SqlitePool, device: i64, n: u8, package: &str) {
        sqlx::query(
            "INSERT INTO up_registrations (device_id, reg, secret_hash, package) VALUES (?, ?, ?, ?)",
        )
        .bind(device)
        .bind(format!("{n:032x}"))
        .bind(super::super::secret_hash(&secret(n)))
        .bind(package)
        .execute(db)
        .await
        .unwrap();
    }

    async fn gone(db: &SqlitePool, n: u8) -> bool {
        lookup(db, &secret(n)).await.unwrap() == Lookup::Gone
    }

    /// Every delete path leaves the secret's tombstone (the trigger): the device API's, a package's (Stop, the
    /// allowlist), all of a phone's (the switch), everywhere (the address cleared), retention's unseen phone, and a
    /// phone deleted through the cascade. A never-issued secret is Unknown, not Gone.
    #[tokio::test]
    async fn every_delete_path_writes_tombstones() {
        let (_dir, db, emma) = db().await;
        let jack: i64 =
            sqlx::query_scalar("INSERT INTO devices (name) VALUES ('Jack') RETURNING id")
                .fetch_one(&db)
                .await
                .unwrap();
        for (n, package) in [(1, "a.b"), (2, "c.d"), (3, "e.f"), (4, "g.h")] {
            add(&db, emma, n, package).await;
        }
        add(&db, jack, 5, "a.b").await;
        assert!(matches!(
            lookup(&db, &secret(1)).await.unwrap(),
            Lookup::Live(_)
        ));
        assert_eq!(lookup(&db, &secret(9)).await.unwrap(), Lookup::Unknown);
        assert_eq!(lookup(&db, "short").await.unwrap(), Lookup::Unknown);

        let reg = format!("{:032x}", 1);
        delete_registrations(&db, emma, Which::Reg(&reg))
            .await
            .unwrap();
        assert!(gone(&db, 1).await);
        delete_registrations(&db, emma, Which::Package("c.d"))
            .await
            .unwrap();
        assert!(gone(&db, 2).await);
        delete_registrations(&db, emma, Which::NotIn(&["g.h".to_string()]))
            .await
            .unwrap();
        assert!(gone(&db, 3).await);
        assert!(!gone(&db, 4).await, "allowed stays");
        delete_registrations(&db, emma, Which::All).await.unwrap();
        assert!(gone(&db, 4).await);
        // A phone deleted: the cascade tombstones too.
        sqlx::query("DELETE FROM devices WHERE id = ?")
            .bind(jack)
            .execute(&db)
            .await
            .unwrap();
        assert!(gone(&db, 5).await);
        // Everywhere, and retention's unseen phone.
        add(&db, emma, 6, "a.b").await;
        delete_everywhere(&db).await.unwrap();
        assert!(gone(&db, 6).await);
        add(&db, emma, 7, "a.b").await;
        sqlx::query("UPDATE devices SET last_seen_at = datetime('now', '-31 days') WHERE id = ?")
            .bind(emma)
            .execute(&db)
            .await
            .unwrap();
        prune(&db, 0).await.unwrap();
        assert!(gone(&db, 7).await);
    }

    /// Tombstones are answered for 30 days, then pruned (and the secret reads as never seen).
    #[tokio::test]
    async fn tombstones_prune_at_30_days() {
        let (_dir, db, emma) = db().await;
        add(&db, emma, 1, "a.b").await;
        add(&db, emma, 2, "a.b").await;
        delete_registrations(&db, emma, Which::All).await.unwrap();
        sqlx::query(
            "UPDATE up_gone SET gone_at = datetime('now', '-31 days') WHERE secret_hash = ?",
        )
        .bind(super::super::secret_hash(&secret(1)))
        .execute(&db)
        .await
        .unwrap();
        assert_eq!(
            lookup(&db, &secret(1)).await.unwrap(),
            Lookup::Unknown,
            "past 30 days"
        );
        assert!(gone(&db, 2).await);
        prune(&db, 0).await.unwrap();
        let left: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM up_gone")
            .fetch_one(&db)
            .await
            .unwrap();
        assert_eq!(left, 1);
    }
}
