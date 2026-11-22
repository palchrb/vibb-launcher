//! The Apple answers kept on the server, sealed (design 125 A5): `wifi_lookup_aps` (migration 0163, `WITHOUT ROWID`).
//!
//! - **The key** `K` is 32 random bytes in `data/keys/wifi_lookup` (made at the first lookup, not at start - a server
//!   whose phones never turn the switch on has none; 0600, `keys::load_or_create`;
//!   no backup copies `data/keys/`). A new key empties the table: rows sealed under a lost one open for nobody.
//! - **A row's id** is HMAC-SHA256 of the BSSID under a key expanded from K (label `id`); **its position** (`{lat, lng,
//!   acc_m}`, 1e-7 degrees) is AES-256-GCM under a key expanded per BSSID from a second PRK (label `seal`), the BSSID
//!   as the AAD too - HKDF-SHA256 throughout, K itself never a key. The database and its backups alone reveal
//!   no BSSID and no position; whoever holds K and a BSSID can open that BSSID's row.
//! - **Months, not days or instants**: `fetched_month` (when Apple answered) and `used_month` (the last answer that
//!   used it), as `year * 12 + month - 1`.
//! - **Negatives**: an asked BSSID Apple doesn't know is a row with `negative = 1` and no seal; it answers "unknown"
//!   until the calendar month it was asked in ends (UTC; a negative asked on the 30th lasts a day) and is then asked
//!   again; capped on their own ([NEGATIVE_CAP]). Positives at [POSITIVE_CAP], the least recently used out first.
//! - **Lifetime** ([CACHE_LIFETIME_MONTHS], the user's Q6: "2 years unused, sealed"): [prune] drops a row unused that
//!   long; the table is emptied when a phone is deleted and no phone has the switch on.

use std::path::Path;

use chrono::Datelike;
use ring::{aead, hkdf, hmac, rand::SecureRandom};
use sqlx::SqlitePool;

use super::codec::Position;

/// A positive row unused this long goes (Q6: two years).
pub const CACHE_LIFETIME_MONTHS: i64 = 24;
/// A positive row fetched this long ago is asked again (three months, the note's 90 days).
pub const FRESH_MONTHS: i64 = 3;
/// A negative row answers for the month it was asked in (the note's 30 days).
pub const NEGATIVE_MONTHS: i64 = 1;
pub const POSITIVE_CAP: i64 = 200_000;
pub const NEGATIVE_CAP: i64 = 20_000;

/// The HKDF salt: this table's rows, this version.
const SEAL_SALT: &[u8] = b"vibb wifi_lookup row v1";
/// The labels the two keys are expanded under from `K` (code QA nit: `K` itself is never a key).
const ID_LABEL: &[u8] = b"id";
const SEAL_LABEL: &[u8] = b"seal";
const NONCE_LEN: usize = 12;
/// `lat`, `lng` as 1e-7 degree i32, `acc_m` as u16.
const PLAIN_LEN: usize = 10;

/// The month of an instant: `year * 12 + month - 1`.
pub fn month_of(at: chrono::DateTime<chrono::Utc>) -> i64 {
    i64::from(at.year()) * 12 + i64::from(at.month0())
}

/// The cache's key in memory.
pub struct CacheKey {
    id: hmac::Key,
    seal: hkdf::Prk,
}

impl CacheKey {
    /// The id key and the seal PRK, each expanded from `K` by HKDF-SHA256 under its own label.
    pub fn from_bytes(bytes: &[u8; 32]) -> Self {
        let prk = hkdf::Salt::new(hkdf::HKDF_SHA256, SEAL_SALT).extract(bytes);
        let id = prk
            .expand(&[ID_LABEL], hmac::HMAC_SHA256)
            .expect("one HMAC key is within HKDF's length");
        let seal = prk
            .expand(&[SEAL_LABEL], hkdf::HKDF_SHA256)
            .expect("one PRK is within HKDF's length");
        CacheKey {
            id: hmac::Key::from(id),
            seal: hkdf::Prk::from(seal),
        }
    }

    /// The row id of `bssid` (lowercase `xx:xx:xx:xx:xx:xx`).
    pub fn id(&self, bssid: &str) -> Vec<u8> {
        hmac::sign(&self.id, bssid.as_bytes()).as_ref().to_vec()
    }

    fn row_key(&self, bssid: &str) -> aead::LessSafeKey {
        let info = [bssid.as_bytes()];
        let okm = self
            .seal
            .expand(&info, &aead::AES_256_GCM)
            .expect("one AES-256 key is within HKDF's length");
        aead::LessSafeKey::new(aead::UnboundKey::from(okm))
    }

    /// `position` sealed for `bssid`'s row: the nonce, then the ciphertext and its tag.
    pub fn seal(&self, bssid: &str, position: &Position) -> Vec<u8> {
        let mut nonce = [0u8; NONCE_LEN];
        ring::rand::SystemRandom::new()
            .fill(&mut nonce)
            .expect("the system's random source");
        let mut plain = Vec::with_capacity(PLAIN_LEN + 16);
        plain.extend_from_slice(&((position.lat * 1e7).round() as i32).to_be_bytes());
        plain.extend_from_slice(&((position.lng * 1e7).round() as i32).to_be_bytes());
        plain.extend_from_slice(&position.acc_m.to_be_bytes());
        self.row_key(bssid)
            .seal_in_place_append_tag(
                aead::Nonce::assume_unique_for_key(nonce),
                aead::Aad::from(bssid.as_bytes()),
                &mut plain,
            )
            .expect("AES-GCM seals ten bytes");
        let mut sealed = nonce.to_vec();
        sealed.extend_from_slice(&plain);
        sealed
    }

    /// The position `sealed` holds for `bssid`, or `None`: another key, another BSSID's row, a changed byte.
    pub fn open(&self, bssid: &str, sealed: &[u8]) -> Option<Position> {
        if sealed.len() < NONCE_LEN {
            return None;
        }
        let (nonce, rest) = sealed.split_at(NONCE_LEN);
        let mut buffer = rest.to_vec();
        let plain = self
            .row_key(bssid)
            .open_in_place(
                aead::Nonce::try_assume_unique_for_key(nonce).ok()?,
                aead::Aad::from(bssid.as_bytes()),
                &mut buffer,
            )
            .ok()?;
        if plain.len() != PLAIN_LEN {
            return None;
        }
        let int = |at: usize| i32::from_be_bytes(plain[at..at + 4].try_into().unwrap());
        Some(Position {
            lat: f64::from(int(0)) / 1e7,
            lng: f64::from(int(4)) / 1e7,
            acc_m: u16::from_be_bytes([plain[8], plain[9]]),
        })
    }
}

/// The key from `file`, made (0600, 32 random bytes as base64, its directory 0700) the first time; a new key empties
/// the table. A mangled file is refused, never replaced.
pub async fn load_key(db: &SqlitePool, file: &Path) -> Result<CacheKey, String> {
    let loaded = crate::keys::load_or_create(file, crate::music_secret::fresh_key_file)?;
    let bytes = std::str::from_utf8(&loaded.bytes)
        .ok()
        .and_then(crate::music_secret::decode_key)
        .ok_or_else(|| {
            format!(
                "{} is not 32 bytes of base64 - fix it, or remove it to start an empty Apple cache",
                file.display()
            )
        })?;
    if loaded.created {
        // Rows sealed under a lost key (a restored backup, another machine) open for nobody.
        sqlx::query("DELETE FROM wifi_lookup_aps")
            .execute(db)
            .await
            .map_err(|err| format!("can't empty the Apple cache for its new key: {err}"))?;
        tracing::info!(
            "wifi_lookup: made the cache key {} (no backup holds it); the cache starts empty",
            file.display()
        );
    }
    Ok(CacheKey::from_bytes(&bytes))
}

/// What the cache holds for one asked BSSID.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Cached {
    /// Nothing, or a negative that ran out: ask Apple.
    Missing,
    /// Apple's position, fetched in `fetched_month`.
    Known {
        position: Position,
        fetched_month: i64,
    },
    /// Apple didn't know it this month.
    Unknown,
}

impl Cached {
    /// Whether Apple should be asked for it now.
    pub fn wanted(&self, now_month: i64) -> bool {
        match self {
            Cached::Missing => true,
            Cached::Known { fetched_month, .. } => now_month - fetched_month >= FRESH_MONTHS,
            Cached::Unknown => false,
        }
    }
}

/// The cache's answer for each of `bssids` (in order). A row that doesn't open (another key) is missing.
pub async fn read(
    db: &SqlitePool,
    key: &CacheKey,
    bssids: &[String],
    now_month: i64,
) -> Result<Vec<Cached>, sqlx::Error> {
    let mut out = Vec::with_capacity(bssids.len());
    for bssid in bssids {
        let row: Option<(Option<Vec<u8>>, i64, i64)> = sqlx::query_as(
            "SELECT sealed, fetched_month, negative FROM wifi_lookup_aps WHERE id = ?",
        )
        .bind(key.id(bssid))
        .fetch_optional(db)
        .await?;
        out.push(match row {
            Some((_, fetched, 1)) if now_month - fetched < NEGATIVE_MONTHS => Cached::Unknown,
            Some((Some(sealed), fetched_month, 0)) => match key.open(bssid, &sealed) {
                Some(position) => Cached::Known {
                    position,
                    fetched_month,
                },
                None => Cached::Missing,
            },
            _ => Cached::Missing,
        });
    }
    Ok(out)
}

/// Apple's answer stored at `now_month`: each known AP sealed (asked or surrounding, `used_month` now), each asked
/// one Apple didn't know a negative row. Then the caps.
pub async fn store(
    db: &SqlitePool,
    key: &CacheKey,
    known: &[(String, Position)],
    unknown: &[String],
    now_month: i64,
) -> Result<(), sqlx::Error> {
    let mut tx = db.begin().await?;
    for (bssid, position) in known {
        sqlx::query(
            "INSERT INTO wifi_lookup_aps (id, sealed, fetched_month, used_month, negative) VALUES (?, ?, ?, ?, 0) \
             ON CONFLICT(id) DO UPDATE SET sealed = excluded.sealed, fetched_month = excluded.fetched_month, \
             used_month = excluded.used_month, negative = 0",
        )
        .bind(key.id(bssid))
        .bind(key.seal(bssid, position))
        .bind(now_month)
        .bind(now_month)
        .execute(&mut *tx)
        .await?;
    }
    for bssid in unknown {
        sqlx::query(
            "INSERT INTO wifi_lookup_aps (id, sealed, fetched_month, used_month, negative) VALUES (?, NULL, ?, ?, 1) \
             ON CONFLICT(id) DO UPDATE SET sealed = NULL, fetched_month = excluded.fetched_month, \
             used_month = excluded.used_month, negative = 1",
        )
        .bind(key.id(bssid))
        .bind(now_month)
        .bind(now_month)
        .execute(&mut *tx)
        .await?;
    }
    evict(&mut tx, true, NEGATIVE_CAP).await?;
    evict(&mut tx, false, POSITIVE_CAP).await?;
    tx.commit().await
}

/// The negative or the positive rows above `cap` go, the least recently used first.
pub async fn evict(
    conn: &mut sqlx::SqliteConnection,
    negative: bool,
    cap: i64,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "DELETE FROM wifi_lookup_aps WHERE id IN (SELECT id FROM wifi_lookup_aps WHERE negative = ?1 \
         ORDER BY used_month LIMIT MAX(0, (SELECT COUNT(*) FROM wifi_lookup_aps WHERE negative = ?1) - ?2))",
    )
    .bind(negative)
    .bind(cap)
    .execute(conn)
    .await?;
    Ok(())
}

/// The rows an answer used get this month as their `used_month` (written only when it moves).
pub async fn touch(
    db: &SqlitePool,
    key: &CacheKey,
    bssids: &[&str],
    now_month: i64,
) -> Result<(), sqlx::Error> {
    for bssid in bssids {
        sqlx::query("UPDATE wifi_lookup_aps SET used_month = ? WHERE id = ? AND used_month < ?")
            .bind(now_month)
            .bind(key.id(bssid))
            .bind(now_month)
            .execute(db)
            .await?;
    }
    Ok(())
}

/// The sweep's prune: positives unused for [CACHE_LIFETIME_MONTHS], negatives past their month. Needs no key.
pub async fn prune(db: &SqlitePool, now_month: i64) -> Result<u64, sqlx::Error> {
    Ok(sqlx::query(
        "DELETE FROM wifi_lookup_aps WHERE (negative = 0 AND used_month <= ?1 - ?2) \
         OR (negative = 1 AND fetched_month <= ?1 - ?3)",
    )
    .bind(now_month)
    .bind(CACHE_LIFETIME_MONTHS)
    .bind(NEGATIVE_MONTHS)
    .execute(db)
    .await?
    .rows_affected())
}

/// After a phone's delete: the whole table, when no phone has the switch on any more.
pub async fn empty_unless_used(db: &SqlitePool) -> Result<u64, sqlx::Error> {
    Ok(sqlx::query(
        "DELETE FROM wifi_lookup_aps WHERE NOT EXISTS (SELECT 1 FROM wifi_lookup_devices WHERE is_on = 1)",
    )
    .execute(db)
    .await?
    .rows_affected())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_sealed_row_opens_only_for_its_bssid_and_key() {
        let key = CacheKey::from_bytes(&[7; 32]);
        let position = Position {
            lat: 59.9123456,
            lng: 10.7543210,
            acc_m: 34,
        };
        let sealed = key.seal("00:00:5e:00:53:01", &position);
        assert_eq!(key.open("00:00:5e:00:53:01", &sealed), Some(position));
        // A fresh nonce every time; nothing of the position or the BSSID in the clear.
        assert_ne!(key.seal("00:00:5e:00:53:01", &position), sealed);
        assert_eq!(key.open("00:00:5e:00:53:02", &sealed), None);
        assert_eq!(
            CacheKey::from_bytes(&[8; 32]).open("00:00:5e:00:53:01", &sealed),
            None
        );
        let mut changed = sealed.clone();
        *changed.last_mut().unwrap() ^= 1;
        assert_eq!(key.open("00:00:5e:00:53:01", &changed), None);
        assert_eq!(key.open("00:00:5e:00:53:01", &sealed[..5]), None);
        // Ids are the key's: another key, another id.
        assert_ne!(
            key.id("00:00:5e:00:53:01"),
            CacheKey::from_bytes(&[8; 32]).id("00:00:5e:00:53:01")
        );
        assert_eq!(key.id("00:00:5e:00:53:01").len(), 32);
    }

    #[test]
    fn months_count_from_year_zero() {
        let at = chrono::DateTime::parse_from_rfc3339("2026-11-22T10:00:00Z")
            .unwrap()
            .with_timezone(&chrono::Utc);
        assert_eq!(month_of(at), 2026 * 12 + 10);
    }
}
