//! Step 2 (design 129 §9): what the phone booted, read from its key's RootOfTrust. S1 reads and shows it (a quiet
//! value); S2 makes it a registration's condition (§3 item 13) and the orange line.
//!
//! The GrapheneOS list is `grapheneos_boot_keys.json`, compiled in: the `verifiedBootKeys` of GrapheneOS's signed
//! `https://grapheneos.org/attestation.json`, written by `scripts/grapheneos-boot-keys.py` after it checked the
//! OpenSSH signature against the signer it pins (`--selftest` in `server-ci.yml`'s changes job).

use super::key_description::{BootState, RootOfTrust};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Reading {
    /// `Verified` and locked: the maker's signed system.
    Verified,
    /// `SelfSigned`, locked, and a verified-boot key on the GrapheneOS list.
    GrapheneOs,
    /// Anything else: unverified, failed, another self-signed key, unlocked, or no RootOfTrust.
    Unknown,
}

impl Reading {
    pub fn of(root: Option<&RootOfTrust>) -> Reading {
        match root {
            Some(r) if r.device_locked && r.state == BootState::Verified => Reading::Verified,
            Some(r)
                if r.device_locked
                    && r.state == BootState::SelfSigned
                    && grapheneos_key(&r.verified_boot_key) =>
            {
                Reading::GrapheneOs
            }
            _ => Reading::Unknown,
        }
    }

    pub fn word(self) -> &'static str {
        match self {
            Reading::Verified => "verified",
            Reading::GrapheneOs => "grapheneos",
            Reading::Unknown => "unknown",
        }
    }
}

#[derive(serde::Deserialize)]
struct List {
    timestamp: i64,
    verified_boot_keys: Vec<String>,
}

static LIST: std::sync::LazyLock<List> = std::sync::LazyLock::new(|| {
    serde_json::from_str(include_str!("grapheneos_boot_keys.json"))
        .expect("grapheneos_boot_keys.json is the script's form")
});

/// Whether `key` (the RootOfTrust's 32-byte verified-boot key) is a GrapheneOS model's.
pub fn grapheneos_key(key: &[u8]) -> bool {
    let hex = hex::encode(key);
    LIST.verified_boot_keys.iter().any(|k| *k == hex)
}

/// The list's own `timestamp` (Unix seconds): the script refuses an older one.
pub fn grapheneos_list_timestamp() -> i64 {
    LIST.timestamp
}

#[cfg(test)]
mod tests {
    use super::*;

    fn root(state: BootState, locked: bool, key: Vec<u8>) -> RootOfTrust {
        RootOfTrust {
            verified_boot_key: key,
            device_locked: locked,
            state,
        }
    }

    #[test]
    fn the_table() {
        let graphene = hex::decode(&LIST.verified_boot_keys[0]).unwrap();
        let other = vec![7u8; 32];
        assert_eq!(
            Reading::of(Some(&root(BootState::Verified, true, other.clone()))),
            Reading::Verified
        );
        assert_eq!(
            Reading::of(Some(&root(BootState::Verified, false, other.clone()))),
            Reading::Unknown
        );
        assert_eq!(
            Reading::of(Some(&root(BootState::SelfSigned, true, graphene.clone()))),
            Reading::GrapheneOs
        );
        assert_eq!(
            Reading::of(Some(&root(BootState::SelfSigned, false, graphene.clone()))),
            Reading::Unknown
        );
        assert_eq!(
            Reading::of(Some(&root(BootState::SelfSigned, true, other.clone()))),
            Reading::Unknown
        );
        for state in [BootState::Unverified, BootState::Failed] {
            assert_eq!(
                Reading::of(Some(&root(state, true, graphene.clone()))),
                Reading::Unknown
            );
        }
        assert_eq!(Reading::of(None), Reading::Unknown);
    }

    #[test]
    fn the_list_is_the_scripts() {
        assert!(LIST.verified_boot_keys.len() >= 10);
        assert!(LIST.verified_boot_keys.iter().all(|k| {
            k.len() == 64
                && k.bytes()
                    .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
        }));
        assert!(grapheneos_list_timestamp() > 1_700_000_000);
    }
}
