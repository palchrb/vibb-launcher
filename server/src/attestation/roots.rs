//! Google's attestation root keys, pinned (design 129 §3 item 2): each pin is the SHA-256 of the root's
//! SubjectPublicKeyInfo, so the 2016 RSA certificate's expiry (2026-05-24) changes nothing - the key is the same in
//! its 2019, 2021 and 2022 certificates. The certificates are `testdata/attestation/roots/` (copied from Google's
//! page "Verify hardware-backed key pairs with key attestation" on 2026-11-24; `tests::the_pins_are_the_roots`
//! computes every pin from them). Google's JSON root list is never fetched at run time: a new root comes in a server
//! release, by design 76's rule for pins.

/// Which root a chain ends at (kept per key, §3 item 12).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Root {
    /// The RSA-4096 key, subject `SERIALNUMBER=f92009e853b6b045`.
    GoogleRsa,
    /// "Key Attestation CA1" (ECDSA P-384), signing chains from 2026-02-01.
    GoogleEcCa1,
}

impl Root {
    pub fn word(self) -> &'static str {
        match self {
            Root::GoogleRsa => "google_rsa",
            Root::GoogleEcCa1 => "google_ec_ca1",
        }
    }

    pub fn from_word(word: &str) -> Option<Root> {
        PINS.iter().map(|(_, r)| *r).find(|r| r.word() == word)
    }
}

/// `(SHA-256 of the SPKI, the root)`.
pub const PINS: &[(&str, Root)] = &[
    (
        "feb2ea7551ee316ed4bb443c8293b884dbfdea40b603ee3e4f4a897e4580fbae",
        Root::GoogleRsa,
    ),
    (
        "3ee44512a1af2beb39c889490c60ea3f82e43f5d5a5532f5ab9419f676cd07ec",
        Root::GoogleEcCa1,
    ),
];

/// The root `spki` is pinned as, if any.
pub fn pinned(spki: &[u8]) -> Option<Root> {
    let hash = super::spki_sha256(spki);
    PINS.iter().find(|(pin, _)| *pin == hash).map(|(_, r)| *r)
}
