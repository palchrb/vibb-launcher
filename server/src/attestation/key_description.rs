//! The KeyDescription (OID 1.3.6.1.4.1.11129.2.1.17), as Android's key attestation schema defines it (design 129 §3
//! items 5-10). Only the fields the check reads are kept; every other tag is skipped, so a newer KeyMint (a higher
//! `attestationVersion`, new tags) still parses - nothing unknown is ever guessed at.

use super::der::{Reader, Tag, Tlv, int_of, parse_one};

/// `SecurityLevel`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SecurityLevel {
    Software,
    TrustedEnvironment,
    StrongBox,
}

impl SecurityLevel {
    fn of(value: i64) -> Option<SecurityLevel> {
        match value {
            0 => Some(SecurityLevel::Software),
            1 => Some(SecurityLevel::TrustedEnvironment),
            2 => Some(SecurityLevel::StrongBox),
            _ => None,
        }
    }

    pub fn word(self) -> &'static str {
        match self {
            SecurityLevel::Software => "software",
            SecurityLevel::TrustedEnvironment => "tee",
            SecurityLevel::StrongBox => "strongbox",
        }
    }
}

/// `VerifiedBootState`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BootState {
    Verified,
    SelfSigned,
    Unverified,
    Failed,
}

impl BootState {
    fn of(value: i64) -> Option<BootState> {
        match value {
            0 => Some(BootState::Verified),
            1 => Some(BootState::SelfSigned),
            2 => Some(BootState::Unverified),
            3 => Some(BootState::Failed),
            _ => None,
        }
    }

    pub fn word(self) -> &'static str {
        match self {
            BootState::Verified => "verified",
            BootState::SelfSigned => "self_signed",
            BootState::Unverified => "unverified",
            BootState::Failed => "failed",
        }
    }

    pub fn from_word(word: &str) -> Option<BootState> {
        [
            BootState::Verified,
            BootState::SelfSigned,
            BootState::Unverified,
            BootState::Failed,
        ]
        .into_iter()
        .find(|s| s.word() == word)
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RootOfTrust {
    pub verified_boot_key: Vec<u8>,
    pub device_locked: bool,
    pub state: BootState,
}

/// `AttestationApplicationId` (tag 709): the packages and the signing certificates' SHA-256 digests.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ApplicationId {
    pub packages: Vec<String>,
    pub signatures: Vec<Vec<u8>>,
}

/// One `AuthorizationList`'s fields the check reads.
#[derive(Clone, Debug, Default)]
pub struct AuthorizationList {
    pub purposes: Option<Vec<i64>>,
    pub algorithm: Option<i64>,
    pub key_size: Option<i64>,
    pub digests: Option<Vec<i64>>,
    pub ec_curve: Option<i64>,
    pub no_auth_required: bool,
    pub origin: Option<i64>,
    pub root_of_trust: Option<RootOfTrust>,
    pub os_patch_level: Option<i64>,
    pub application_id: Option<ApplicationId>,
    /// How many tags the list names, read or not (the emulator profile takes only an empty hardware list).
    pub entries: usize,
}

#[derive(Clone, Debug)]
pub struct KeyDescription {
    pub attestation_version: i64,
    pub attestation_security_level: SecurityLevel,
    /// Read by the `emulator_keys` profile alone (the two versions must agree there).
    #[cfg_attr(not(feature = "emulator_keys"), allow(dead_code))]
    pub keymint_version: i64,
    pub keymint_security_level: SecurityLevel,
    pub challenge: Vec<u8>,
    pub software: AuthorizationList,
    pub hardware: AuthorizationList,
}

impl KeyDescription {
    /// The extension's value (the OCTET STRING's content): the KeyDescription's DER.
    pub fn parse(value: &[u8]) -> Option<KeyDescription> {
        let seq = parse_one(value, Tag::SEQUENCE)?;
        let mut r = seq.reader();
        let attestation_version = r.expect(Tag::INTEGER)?.int()?;
        let attestation_security_level = SecurityLevel::of(r.expect(Tag::ENUMERATED)?.int()?)?;
        let keymint_version = r.expect(Tag::INTEGER)?.int()?;
        let keymint_security_level = SecurityLevel::of(r.expect(Tag::ENUMERATED)?.int()?)?;
        let challenge = r.expect(Tag::OCTET_STRING)?.content.to_vec();
        let _unique_id = r.expect(Tag::OCTET_STRING)?;
        let software = authorization_list(&r.expect(Tag::SEQUENCE)?)?;
        let hardware = authorization_list(&r.expect(Tag::SEQUENCE)?)?;
        // Later versions may append fields; nothing past the two lists is read.
        Some(KeyDescription {
            attestation_version,
            attestation_security_level,
            keymint_version,
            keymint_security_level,
            challenge,
            software,
            hardware,
        })
    }
}

/// The tags read (Android's `Tag` numbers without the type bits).
mod tags {
    pub const PURPOSE: u32 = 1;
    pub const ALGORITHM: u32 = 2;
    pub const KEY_SIZE: u32 = 3;
    pub const DIGEST: u32 = 5;
    pub const EC_CURVE: u32 = 10;
    pub const NO_AUTH_REQUIRED: u32 = 503;
    pub const ORIGIN: u32 = 702;
    pub const ROOT_OF_TRUST: u32 = 704;
    pub const OS_PATCH_LEVEL: u32 = 706;
    pub const APPLICATION_ID: u32 = 709;
}

fn authorization_list(seq: &Tlv) -> Option<AuthorizationList> {
    let mut list = AuthorizationList::default();
    let mut r = seq.reader();
    let mut last: Option<u32> = None;
    while let Some(item) = r.next() {
        // Every entry is `[n] EXPLICIT`, in ascending tag order with none twice (the Kotlin library refuses
        // `tags_not_in_ascending_order`; a list that names a tag twice could say two things).
        if item.tag.class != super::der::Class::Context || !item.tag.constructed {
            return None;
        }
        let n = item.tag.number;
        if last.is_some_and(|l| n <= l) {
            return None;
        }
        last = Some(n);
        list.entries += 1;
        let inner = || {
            let mut i = item.reader();
            let tlv = i.next()?;
            i.is_empty().then_some(tlv)
        };
        match n {
            tags::PURPOSE => list.purposes = Some(int_set(&inner()?)?),
            tags::DIGEST => list.digests = Some(int_set(&inner()?)?),
            tags::ALGORITHM => list.algorithm = Some(int(&inner()?)?),
            tags::KEY_SIZE => list.key_size = Some(int(&inner()?)?),
            tags::EC_CURVE => list.ec_curve = Some(int(&inner()?)?),
            tags::ORIGIN => list.origin = Some(int(&inner()?)?),
            tags::OS_PATCH_LEVEL => list.os_patch_level = Some(int(&inner()?)?),
            tags::NO_AUTH_REQUIRED => {
                if inner()?.tag != Tag::NULL {
                    return None;
                }
                list.no_auth_required = true;
            }
            tags::ROOT_OF_TRUST => list.root_of_trust = Some(root_of_trust(&inner()?)?),
            tags::APPLICATION_ID => {
                let octets = inner()?;
                if octets.tag != Tag::OCTET_STRING {
                    return None;
                }
                list.application_id = Some(application_id(octets.content)?);
            }
            _ => {}
        }
    }
    Some(list)
}

fn int(tlv: &Tlv) -> Option<i64> {
    (tlv.tag == Tag::INTEGER).then_some(())?;
    int_of(tlv.content)
}

fn int_set(tlv: &Tlv) -> Option<Vec<i64>> {
    (tlv.tag == Tag::SET).then_some(())?;
    let mut r = tlv.reader();
    let mut out = Vec::new();
    while !r.is_empty() {
        out.push(int(&r.next()?)?);
    }
    Some(out)
}

fn root_of_trust(tlv: &Tlv) -> Option<RootOfTrust> {
    (tlv.tag == Tag::SEQUENCE).then_some(())?;
    let mut r = tlv.reader();
    let verified_boot_key = r.expect(Tag::OCTET_STRING)?.content.to_vec();
    let device_locked = r.expect(Tag::BOOLEAN)?.boolean()?;
    let state = BootState::of(r.expect(Tag::ENUMERATED)?.int()?)?;
    // verifiedBootHash (version 3 on) is not read.
    Some(RootOfTrust {
        verified_boot_key,
        device_locked,
        state,
    })
}

fn application_id(der: &[u8]) -> Option<ApplicationId> {
    let seq = parse_one(der, Tag::SEQUENCE)?;
    let mut r = seq.reader();
    let infos = r.expect(Tag::SET)?;
    let digests = r.expect(Tag::SET)?;
    let mut id = ApplicationId::default();
    let mut i = Reader::new(infos.content);
    while !i.is_empty() {
        let info = i.expect(Tag::SEQUENCE)?;
        let mut p = info.reader();
        let name = p.expect(Tag::OCTET_STRING)?.content;
        id.packages.push(String::from_utf8(name.to_vec()).ok()?);
    }
    let mut d = Reader::new(digests.content);
    while !d.is_empty() {
        id.signatures
            .push(d.expect(Tag::OCTET_STRING)?.content.to_vec());
    }
    Some(id)
}
