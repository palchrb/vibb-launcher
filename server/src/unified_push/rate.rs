// Ported from ntfy (https://github.com/binwiederhier/ntfy), `server/server_rate_visitor.go` and the request limiter of
// `server/visitor.go` at commit a098f52, by Philipp C. Heckel and the ntfy contributors, licensed under the Apache
// License 2.0 (see `Apache-2.0.txt` in this directory); changed here: rewritten in Rust for Vibb's push server (see
// `README.md`). Distributed as part of this server under the GNU GPL v3.0 or later.

//! Who pays for a push (design 108 §3.3): ntfy's "rate visitor" - **the subscriber is charged, not the publisher**.
//! Every push to a registration is charged to its phone's bucket ([PHONE_BURST], [PHONE_PER_SEC]) and, under it, to
//! the registration's own sub-bucket ([REG_BURST], [REG_PER_SEC], M3), so one homeserver pushing for many people is
//! never the one limited, and one flooded endpoint can't starve the phone's other apps. ntfy's numbers (60 burst, one
//! per 5 s) are for an anonymous visitor; a phone in a busy group chat needs more.
//!
//! The client IP is counted only for secrets this server never issued (M5), in a map of its own that the device API
//! never sees, with the device API's numbers ([crate::limits::IP_FAILURES] in [crate::limits::IP_WINDOW], then 429 for
//! [crate::limits::IP_BLOCK]). A ban never blocks a request whose secret resolves: the handlers look the secret up
//! first and ask [Rates::banned] only for an unresolved one. ntfy's "abandoned topic" (410 after 4 days without a
//! subscriber) is our knowledge instead: gone means the registration was deleted (`store::lookup`'s tombstones).
//! Every map is capped at [crate::limits::MAX_KEYS], the oldest dropped first. Nothing here touches the database.

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::Instant;

use crate::limits::{IP_BLOCK, IP_FAILURES, IP_WINDOW, MAX_KEYS, Refused, evict_oldest};

/// A phone's bucket: 120 pushes at once, then one a second (ntfy: `VisitorRequestLimitBurst` 60, a 5 s replenish).
pub const PHONE_BURST: f64 = 120.0;
pub const PHONE_PER_SEC: f64 = 1.0;
/// A registration's sub-bucket under it (M3): 30 at once, then one every 2 s.
pub const REG_BURST: f64 = 30.0;
pub const REG_PER_SEC: f64 = 0.5;

struct Bucket {
    tokens: f64,
    last: Instant,
}

impl Bucket {
    fn refill(&mut self, now: Instant, burst: f64, per_sec: f64) {
        let elapsed = now.saturating_duration_since(self.last).as_secs_f64();
        self.tokens = (self.tokens + elapsed * per_sec).min(burst);
        self.last = now;
    }

    fn wait(&self, per_sec: f64) -> u64 {
        (((1.0 - self.tokens) / per_sec).ceil() as u64).max(1)
    }
}

struct IpFailures {
    window_start: Instant,
    count: u32,
    blocked_until: Option<Instant>,
}

#[derive(Default)]
pub struct Rates {
    phones: Mutex<HashMap<i64, Bucket>>,
    registrations: Mutex<HashMap<i64, Bucket>>,
    ip_failures: Mutex<HashMap<String, IpFailures>>,
}

impl Rates {
    /// One push to `registration` of `device`: both buckets give a token, or neither is charged (429).
    pub fn charge(&self, device: i64, registration: i64) -> Result<(), Refused> {
        self.charge_at(device, registration, Instant::now())
    }

    fn charge_at(&self, device: i64, registration: i64, now: Instant) -> Result<(), Refused> {
        // Always the phones first, then the registrations: one lock order.
        let mut phones = self.phones.lock().unwrap_or_else(|e| e.into_inner());
        let mut registrations = self.registrations.lock().unwrap_or_else(|e| e.into_inner());
        if phones.len() >= MAX_KEYS && !phones.contains_key(&device) {
            evict_oldest(&mut phones, |bucket| bucket.last);
        }
        if registrations.len() >= MAX_KEYS && !registrations.contains_key(&registration) {
            evict_oldest(&mut registrations, |bucket| bucket.last);
        }
        let phone = phones.entry(device).or_insert(Bucket {
            tokens: PHONE_BURST,
            last: now,
        });
        phone.refill(now, PHONE_BURST, PHONE_PER_SEC);
        let reg = registrations.entry(registration).or_insert(Bucket {
            tokens: REG_BURST,
            last: now,
        });
        reg.refill(now, REG_BURST, REG_PER_SEC);
        if reg.tokens < 1.0 {
            return Err(Refused {
                retry_after_secs: reg.wait(REG_PER_SEC),
            });
        }
        if phone.tokens < 1.0 {
            return Err(Refused {
                retry_after_secs: phone.wait(PHONE_PER_SEC),
            });
        }
        reg.tokens -= 1.0;
        phone.tokens -= 1.0;
        Ok(())
    }

    /// Whether this client's never-seen secrets have it refused right now (429).
    pub fn banned(&self, key: &str) -> Option<Refused> {
        self.banned_at(key, Instant::now())
    }

    fn banned_at(&self, key: &str, now: Instant) -> Option<Refused> {
        let failures = self.ip_failures.lock().unwrap_or_else(|e| e.into_inner());
        let until = failures.get(key)?.blocked_until?;
        (until > now).then(|| Refused {
            retry_after_secs: (until.duration_since(now).as_secs_f64().ceil() as u64).max(1),
        })
    }

    /// Counts one never-seen secret from this client (never a tombstoned one, M5). `true` when that bans it.
    pub fn never_seen(&self, key: &str) -> bool {
        self.never_seen_at(key, Instant::now())
    }

    fn never_seen_at(&self, key: &str, now: Instant) -> bool {
        let mut failures = self.ip_failures.lock().unwrap_or_else(|e| e.into_inner());
        if failures.len() >= MAX_KEYS && !failures.contains_key(key) {
            evict_oldest(&mut failures, |f| f.window_start);
        }
        let entry = failures.entry(key.to_string()).or_insert(IpFailures {
            window_start: now,
            count: 0,
            blocked_until: None,
        });
        if entry.blocked_until.is_some_and(|until| until <= now) {
            entry.blocked_until = None;
            entry.count = 0;
            entry.window_start = now;
        }
        if now.saturating_duration_since(entry.window_start) >= IP_WINDOW {
            entry.window_start = now;
            entry.count = 0;
        }
        entry.count += 1;
        if entry.count >= IP_FAILURES && entry.blocked_until.is_none() {
            entry.blocked_until = Some(now + IP_BLOCK);
            return true;
        }
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    /// ntfy's `TestServer_SubscriberRateLimiting_*`: the pushes are charged to the subscriber (the phone), whoever
    /// sends them - two registrations of one phone share its bucket, another phone has its own.
    #[test]
    fn the_phone_is_charged() {
        let rates = Rates::default();
        let now = Instant::now();
        // Four registrations of phone 1 at 30 each use up its 120.
        for registration in 1..=4 {
            for _ in 0..30 {
                rates.charge_at(1, registration, now).unwrap();
            }
        }
        let refused = rates.charge_at(1, 5, now).unwrap_err();
        assert_eq!(refused.retry_after_secs, 1);
        // Phone 2 has its own bucket.
        assert!(rates.charge_at(2, 6, now).is_ok());
        // One a second comes back.
        let later = now + Duration::from_secs(1);
        assert!(rates.charge_at(1, 5, later).is_ok());
        assert!(rates.charge_at(1, 5, later).is_err());
    }

    /// M3: one flooded registration gets 429 after 30 while the phone's other registration still delivers.
    #[test]
    fn a_registration_has_its_own_sub_bucket() {
        let rates = Rates::default();
        let now = Instant::now();
        for _ in 0..30 {
            rates.charge_at(1, 1, now).unwrap();
        }
        let refused = rates.charge_at(1, 1, now).unwrap_err();
        assert_eq!(refused.retry_after_secs, 2, "one every 2 s");
        for _ in 0..30 {
            assert!(rates.charge_at(1, 2, now).is_ok(), "the other app delivers");
        }
        // A refused push is charged to neither bucket: the phone has 60 left, not fewer.
        for _ in 0..60 {
            rates.charge_at(1, 3, now).ok();
            rates.charge_at(1, 4, now).ok();
        }
        assert!(rates.charge_at(1, 3, now).is_err());
        assert!(rates.charge_at(1, 4, now).is_err());
    }

    /// M5: the IP map is UP's own and counts what it is told (never-seen secrets only); the ban ends after its block.
    #[test]
    fn the_ip_failure_map_is_separate_and_counts_never_seen_secrets() {
        let rates = Rates::default();
        let device_api = crate::limits::Limits::default();
        let now = Instant::now();
        for n in 1..IP_FAILURES {
            assert!(!rates.never_seen_at("203.0.113.7", now), "failure {n}");
        }
        assert!(rates.banned_at("203.0.113.7", now).is_none());
        assert!(rates.never_seen_at("203.0.113.7", now));
        assert_eq!(
            rates
                .banned_at("203.0.113.7", now)
                .unwrap()
                .retry_after_secs,
            600
        );
        assert!(
            device_api.ip_blocked("203.0.113.7").is_none(),
            "the device API's map never sees UP's failures"
        );
        assert!(rates.banned_at("203.0.113.8", now).is_none());
        assert!(
            rates
                .banned_at("203.0.113.7", now + IP_BLOCK + Duration::from_secs(1))
                .is_none()
        );
        // A charge for a resolving secret never consults the ban.
        assert!(rates.charge_at(1, 1, now).is_ok());
    }
}
