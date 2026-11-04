//! Passkeys' verification (design 74 §5, D5.1): an own minimal WebAuthn verifier on `ring`, for
//! `attestation: "none"` - no attestation statement is read or trusted.
//!
//! - [Cbor]: a bounded CBOR reader (definite lengths only, at most [MAX_DEPTH] containers deep,
//!   unsigned and negative integers, byte and text strings, arrays and maps - and `true`/`false`
//!   inside the dropped extensions map only; any other major type, an indefinite length, a
//!   duplicate map key or trailing bytes is a refusal).
//! - [CoseKey]: ES256 (P-256, the point checked on the curve), EdDSA (Ed25519) and RS256 (a
//!   2048-8192 bit modulus), its `alg`, `kty` and `crv` agreeing; stored as the COSE bytes.
//! - [Registration::verify] and [Assertion::verify]: the client data's `type`, its origin (exactly
//!   one of the request's, [PasskeyConfig::origins_for]), `crossOrigin` false, `rpIdHash`, the
//!   flags (UP and UV required, BS without BE refused, BE fixed at registration), the attested
//!   credential data read in order with the extensions map after it when ED is set, the signature
//!   over `authenticatorData || sha256(clientDataJSON)` and the counter rule (D5.3). The challenge
//!   is taken by the caller (`passkeys::Challenges::take`) between parsing and verifying.
//! - [PasskeyConfig]: `PASSKEY_RP_ID` and `PASSKEY_ORIGINS`, checked at start (D4.1, D4.2), and a
//!   request's RP ID and origins from its host.

use base64::Engine;
use sha2::{Digest, Sha256};

/// COSE algorithm ids (D1.2's `pubKeyCredParams`).
pub const ES256: i64 = -7;
pub const EDDSA: i64 = -8;
pub const RS256: i64 = -257;
/// The algorithms asked for at registration, in order of preference.
pub const ALGS: [i64; 3] = [ES256, EDDSA, RS256];

/// Containers (arrays, maps) nested at most this deep.
pub const MAX_DEPTH: usize = 4;
/// A credential id's length in bytes (D5.1).
pub const MIN_CREDENTIAL_ID: usize = 16;
/// At most 512 at registration (WebAuthn allows 1023): with an RSA-4096 signature an assertion
/// then always fits `/auth/passkey`'s 4 KiB body (qa-passkeys-1 B-L7).
pub const MAX_CREDENTIAL_ID: usize = 512;
/// An RS256 key's modulus in bits (ring verifies 2048-8192; 4096 keeps the assertion in 4 KiB).
pub const RSA_BITS: std::ops::RangeInclusive<usize> = 2048..=4096;

/// authenticatorData's flags.
pub const FLAG_UP: u8 = 0x01;
pub const FLAG_UV: u8 = 0x04;
pub const FLAG_BE: u8 = 0x08;
pub const FLAG_BS: u8 = 0x10;
pub const FLAG_AT: u8 = 0x40;
pub const FLAG_ED: u8 = 0x80;

/// Why a ceremony was refused: one code each, for the security log (`passkey_refused`'s facts)
/// and the tests. The parent sees one red line whatever it is (D1.5).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Refused {
    /// The browser's JSON doesn't read.
    Json,
    /// A field isn't base64url.
    Base64,
    /// `id` isn't `rawId`'s base64url, or `type` isn't `public-key`.
    Id,
    /// clientData's `type` isn't the ceremony's.
    Type,
    /// The challenge isn't one we issued for this session, kind, route and epoch (or it was used).
    Challenge,
    Origin,
    CrossOrigin,
    RpIdHash,
    UserPresence,
    UserVerification,
    /// BS set without BE.
    BackupState,
    /// BE differs from the one stored at registration.
    BackupEligible,
    /// AT missing at registration, or set in an assertion.
    AttestedData,
    /// ED set with no extensions map, or bytes left after the data with ED unset.
    Extensions,
    /// authData's credential id differs from `rawId`.
    CredentialId,
    /// The credential id's length is outside 16-512 bytes.
    CredentialIdLength,
    Cbor,
    /// The COSE key doesn't read, or its alg, kty and crv disagree.
    CoseKey,
    /// The key's algorithm wasn't one we asked for.
    Algorithm,
    /// The key itself is no good (a point off the curve, a modulus outside 2048-8192 bits).
    Key,
    Signature,
    /// The counter didn't move forward (D5.3: possibly a copy).
    Counter,
    /// `userHandle` is missing where it is required, or names another account.
    UserHandle,
    /// No such credential (or another parent's).
    UnknownCredential,
    /// The account isn't ready (D2.2's predicate).
    NotReady,
    /// The server couldn't read or store the passkey (a database error, logged where it happened).
    Database,
}

impl Refused {
    /// The log's code.
    pub fn code(self) -> &'static str {
        match self {
            Refused::Json => "json",
            Refused::Base64 => "base64",
            Refused::Id => "id",
            Refused::Type => "type",
            Refused::Challenge => "challenge",
            Refused::Origin => "origin",
            Refused::CrossOrigin => "cross_origin",
            Refused::RpIdHash => "rp_id_hash",
            Refused::UserPresence => "user_presence",
            Refused::UserVerification => "user_verification",
            Refused::BackupState => "backup_state",
            Refused::BackupEligible => "backup_eligible",
            Refused::AttestedData => "attested_data",
            Refused::Extensions => "extensions",
            Refused::CredentialId => "credential_id",
            Refused::CredentialIdLength => "credential_id_length",
            Refused::Cbor => "cbor",
            Refused::CoseKey => "cose_key",
            Refused::Algorithm => "algorithm",
            Refused::Key => "key",
            Refused::Signature => "signature",
            Refused::Counter => "counter",
            Refused::UserHandle => "user_handle",
            Refused::UnknownCredential => "unknown_credential",
            Refused::NotReady => "not_ready",
            Refused::Database => "database",
        }
    }

    /// The server's own refusal, not a failed try of the parent's (Push never lost silently, 2026-11-04): the
    /// challenge expired, was dropped from the session's store (its 8 newest), already used or bound to another
    /// session or epoch - a lock screen shown again after a while, a prompt answered late -, or the server couldn't
    /// read the passkey. The app lock's unlock doesn't count these toward `admin_auth::REAUTH_MAX_WRONG`; everything
    /// else an assertion can be refused for is the browser's answer itself and counts.
    pub fn servers_own(self) -> bool {
        matches!(self, Refused::Challenge | Refused::Database)
    }
}

// ---------------------------------------------------------------------------------------------
// CBOR

/// One CBOR data item of the kinds WebAuthn uses.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Cbor {
    Uint(u64),
    /// `-1 - n`.
    Nint(u64),
    Bytes(Vec<u8>),
    Text(String),
    Array(Vec<Cbor>),
    Map(Vec<(Cbor, Cbor)>),
    /// `true`/`false`: only inside an extensions map (`hmac-secret: true`; qa-passkeys-1 A-N1).
    Bool(bool),
}

impl Cbor {
    /// The integer, when it is one.
    pub fn int(&self) -> Option<i128> {
        match self {
            Cbor::Uint(n) => Some(i128::from(*n)),
            Cbor::Nint(n) => Some(-1 - i128::from(*n)),
            _ => None,
        }
    }

    fn bytes(&self) -> Option<&[u8]> {
        match self {
            Cbor::Bytes(b) => Some(b),
            _ => None,
        }
    }
}

/// The value of an integer key in a map.
fn by_int(map: &[(Cbor, Cbor)], key: i128) -> Option<&Cbor> {
    map.iter()
        .find(|(k, _)| k.int() == Some(key))
        .map(|(_, v)| v)
}

/// The value of a text key in a map.
fn by_text<'a>(map: &'a [(Cbor, Cbor)], key: &str) -> Option<&'a Cbor> {
    map.iter()
        .find(|(k, _)| matches!(k, Cbor::Text(t) if t == key))
        .map(|(_, v)| v)
}

/// Reads one item from the start of `input`: the item and the bytes it took.
pub fn cbor_item(input: &[u8]) -> Result<(Cbor, usize), Refused> {
    let mut pos = 0;
    let item = read_item(input, &mut pos, 0, false)?;
    Ok((item, pos))
}

/// [cbor_item] that also reads `true` and `false`: the extensions map, dropped unread.
fn cbor_item_with_bools(input: &[u8]) -> Result<(Cbor, usize), Refused> {
    let mut pos = 0;
    let item = read_item(input, &mut pos, 0, true)?;
    Ok((item, pos))
}

/// Reads exactly one item: nothing may follow it.
pub fn cbor_exact(input: &[u8]) -> Result<Cbor, Refused> {
    let (item, used) = cbor_item(input)?;
    if used != input.len() {
        return Err(Refused::Cbor);
    }
    Ok(item)
}

/// The argument of a head whose additional information is `info`.
fn read_argument(input: &[u8], pos: &mut usize, info: u8) -> Result<u64, Refused> {
    let width = match info {
        0..=23 => return Ok(u64::from(info)),
        24 => 1,
        25 => 2,
        26 => 4,
        27 => 8,
        // 28-30 are reserved, 31 is an indefinite length: refused.
        _ => return Err(Refused::Cbor),
    };
    let end = pos.checked_add(width).ok_or(Refused::Cbor)?;
    let bytes = input.get(*pos..end).ok_or(Refused::Cbor)?;
    *pos = end;
    Ok(bytes.iter().fold(0u64, |acc, b| (acc << 8) | u64::from(*b)))
}

/// `n` as a length no longer than what is left of `input` after `pos` (every item takes at least
/// a byte): a huge length is a refusal before anything is allocated.
fn bounded(n: u64, input: &[u8], pos: usize, per_item: usize) -> Result<usize, Refused> {
    let left = input.len().saturating_sub(pos);
    let n = usize::try_from(n).map_err(|_| Refused::Cbor)?;
    if n.checked_mul(per_item).is_none_or(|need| need > left) {
        return Err(Refused::Cbor);
    }
    Ok(n)
}

fn read_item(input: &[u8], pos: &mut usize, depth: usize, bools: bool) -> Result<Cbor, Refused> {
    let head = *input.get(*pos).ok_or(Refused::Cbor)?;
    *pos += 1;
    let (major, info) = (head >> 5, head & 0x1f);
    let argument = read_argument(input, pos, info)?;
    match major {
        0 => Ok(Cbor::Uint(argument)),
        1 => Ok(Cbor::Nint(argument)),
        2 | 3 => {
            let len = bounded(argument, input, *pos, 1)?;
            let bytes = input[*pos..*pos + len].to_vec();
            *pos += len;
            if major == 2 {
                Ok(Cbor::Bytes(bytes))
            } else {
                String::from_utf8(bytes)
                    .map(Cbor::Text)
                    .map_err(|_| Refused::Cbor)
            }
        }
        4 | 5 => {
            if depth + 1 > MAX_DEPTH {
                return Err(Refused::Cbor);
            }
            if major == 4 {
                let count = bounded(argument, input, *pos, 1)?;
                let mut items = Vec::with_capacity(count);
                for _ in 0..count {
                    items.push(read_item(input, pos, depth + 1, bools)?);
                }
                Ok(Cbor::Array(items))
            } else {
                let count = bounded(argument, input, *pos, 2)?;
                let mut entries: Vec<(Cbor, Cbor)> = Vec::with_capacity(count);
                for _ in 0..count {
                    let key = read_item(input, pos, depth + 1, bools)?;
                    let value = read_item(input, pos, depth + 1, bools)?;
                    if entries.iter().any(|(k, _)| *k == key) {
                        return Err(Refused::Cbor);
                    }
                    entries.push((key, value));
                }
                Ok(Cbor::Map(entries))
            }
        }
        7 if bools && (argument == 20 || argument == 21) && info < 24 => {
            Ok(Cbor::Bool(argument == 21))
        }
        // Tags, floats and other simple values: nothing WebAuthn sends us needs them.
        _ => Err(Refused::Cbor),
    }
}

// ---------------------------------------------------------------------------------------------
// COSE keys

/// A credential's public key, as parsed from its COSE bytes.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CoseKey {
    pub alg: i64,
    material: KeyMaterial,
}

#[derive(Clone, Debug, PartialEq, Eq)]
enum KeyMaterial {
    /// The uncompressed point (0x04 || x || y).
    P256(Vec<u8>),
    Ed25519(Vec<u8>),
    Rsa {
        n: Vec<u8>,
        e: Vec<u8>,
    },
}

/// Whether `point` (uncompressed, 65 bytes) is on P-256: ring checks a peer's key in an ECDH
/// agreement, so one is run against a throwaway key.
fn p256_point_ok(point: &[u8]) -> bool {
    use ring::agreement;
    let rng = ring::rand::SystemRandom::new();
    let Ok(private) = agreement::EphemeralPrivateKey::generate(&agreement::ECDH_P256, &rng) else {
        return false;
    };
    let peer = agreement::UnparsedPublicKey::new(&agreement::ECDH_P256, point);
    agreement::agree_ephemeral(private, &peer, |_| ()).is_ok()
}

/// A big-endian integer's bit length, leading zero bytes ignored.
fn bit_length(bytes: &[u8]) -> usize {
    let bytes = match bytes.iter().position(|b| *b != 0) {
        Some(first) => &bytes[first..],
        None => return 0,
    };
    (bytes.len() - 1) * 8 + (8 - bytes[0].leading_zeros() as usize)
}

impl CoseKey {
    /// Parses exactly one COSE key (nothing after it).
    pub fn parse(bytes: &[u8]) -> Result<CoseKey, Refused> {
        let Cbor::Map(map) = cbor_exact(bytes).map_err(|_| Refused::CoseKey)? else {
            return Err(Refused::CoseKey);
        };
        CoseKey::of_map(&map)
    }

    fn of_map(map: &[(Cbor, Cbor)]) -> Result<CoseKey, Refused> {
        let int = |key| by_int(map, key).and_then(Cbor::int);
        let bytes = |key| by_int(map, key).and_then(Cbor::bytes);
        let alg = int(3).ok_or(Refused::CoseKey)?;
        let kty = int(1).ok_or(Refused::CoseKey)?;
        let alg = i64::try_from(alg).map_err(|_| Refused::Algorithm)?;
        if !ALGS.contains(&alg) {
            return Err(Refused::Algorithm);
        }
        let material = match (alg, kty) {
            (ES256, 2) => {
                if int(-1) != Some(1) {
                    return Err(Refused::CoseKey);
                }
                let (x, y) = (bytes(-2), bytes(-3));
                let (Some(x), Some(y)) = (x, y) else {
                    return Err(Refused::CoseKey);
                };
                if x.len() != 32 || y.len() != 32 {
                    return Err(Refused::Key);
                }
                let mut point = Vec::with_capacity(65);
                point.push(0x04);
                point.extend_from_slice(x);
                point.extend_from_slice(y);
                if !p256_point_ok(&point) {
                    return Err(Refused::Key);
                }
                KeyMaterial::P256(point)
            }
            (EDDSA, 1) => {
                if int(-1) != Some(6) {
                    return Err(Refused::CoseKey);
                }
                let x = bytes(-2).ok_or(Refused::CoseKey)?;
                if x.len() != 32 {
                    return Err(Refused::Key);
                }
                KeyMaterial::Ed25519(x.to_vec())
            }
            (RS256, 3) => {
                let (n, e) = (bytes(-1), bytes(-2));
                let (Some(n), Some(e)) = (n, e) else {
                    return Err(Refused::CoseKey);
                };
                // What ring's RsaPublicKeyComponents takes (qa-passkeys-1 A-L3): no leading zero
                // byte, an odd exponent of 3 to 2^33-1 - else the key could never sign in.
                let exponent = e.iter().fold(0u64, |acc, b| (acc << 8) | u64::from(*b));
                let ok = n.first().is_some_and(|b| *b != 0)
                    && RSA_BITS.contains(&bit_length(n))
                    && e.first().is_some_and(|b| *b != 0)
                    && e.len() <= 5
                    && exponent % 2 == 1
                    && (3..=(1u64 << 33) - 1).contains(&exponent);
                if !ok {
                    return Err(Refused::Key);
                }
                KeyMaterial::Rsa {
                    n: n.to_vec(),
                    e: e.to_vec(),
                }
            }
            _ => return Err(Refused::CoseKey),
        };
        Ok(CoseKey { alg, material })
    }

    /// Whether `signature` signs `message` with this key.
    pub fn verify(&self, message: &[u8], signature: &[u8]) -> bool {
        use ring::signature;
        match &self.material {
            KeyMaterial::P256(point) => {
                signature::UnparsedPublicKey::new(&signature::ECDSA_P256_SHA256_ASN1, point)
                    .verify(message, signature)
                    .is_ok()
            }
            KeyMaterial::Ed25519(x) => signature::UnparsedPublicKey::new(&signature::ED25519, x)
                .verify(message, signature)
                .is_ok(),
            KeyMaterial::Rsa { n, e } => signature::RsaPublicKeyComponents { n, e }
                .verify(&signature::RSA_PKCS1_2048_8192_SHA256, message, signature)
                .is_ok(),
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The browser's JSON

fn b64(text: &str) -> Result<Vec<u8>, Refused> {
    base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(text)
        .map_err(|_| Refused::Base64)
}

/// base64url without padding, as the options and `PublicKeyCredential.toJSON` write it.
pub fn b64url(bytes: &[u8]) -> String {
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

/// `clientDataJSON`, read.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ClientData {
    pub kind: String,
    pub challenge: Vec<u8>,
    pub origin: String,
    pub cross_origin: bool,
    hash: [u8; 32],
}

#[derive(serde::Deserialize)]
struct ClientDataJson {
    #[serde(rename = "type")]
    kind: String,
    challenge: String,
    origin: String,
    #[serde(rename = "crossOrigin", default)]
    cross_origin: Option<bool>,
}

impl ClientData {
    pub fn parse(raw: &[u8]) -> Result<ClientData, Refused> {
        let json: ClientDataJson = serde_json::from_slice(raw).map_err(|_| Refused::Json)?;
        Ok(ClientData {
            kind: json.kind,
            challenge: b64(&json.challenge)?,
            origin: json.origin,
            cross_origin: json.cross_origin.unwrap_or(false),
            hash: Sha256::digest(raw).into(),
        })
    }

    /// The type, the origin and `crossOrigin` (D4.2): the origin exactly one of `origins`, its host
    /// the RP ID or under it.
    fn check(&self, kind: &str, origins: &[String], rp_id: &str) -> Result<(), Refused> {
        if self.kind != kind {
            return Err(Refused::Type);
        }
        if !origins.contains(&self.origin) || !origin_fits(&self.origin, rp_id) {
            return Err(Refused::Origin);
        }
        if self.cross_origin {
            return Err(Refused::CrossOrigin);
        }
        Ok(())
    }
}

/// `origin`'s host is `rp_id` or a name under it.
fn origin_fits(origin: &str, rp_id: &str) -> bool {
    let Some((_, rest)) = origin.split_once("://") else {
        return false;
    };
    let host = match rest.rsplit_once(':') {
        Some((host, port)) if port.bytes().all(|b| b.is_ascii_digit()) => host,
        _ => rest,
    };
    under(host, rp_id)
}

/// `host` is `rp_id` or ends with `.rp_id`.
pub fn under(host: &str, rp_id: &str) -> bool {
    host == rp_id
        || host
            .strip_suffix(rp_id)
            .is_some_and(|before| before.ends_with('.'))
}

// ---------------------------------------------------------------------------------------------
// authenticatorData

/// authenticatorData, read.
#[derive(Clone, Debug)]
pub struct AuthData {
    pub rp_id_hash: [u8; 32],
    pub flags: u8,
    pub sign_count: u32,
    /// The attested credential data: the credential id and the COSE key's bytes.
    pub attested: Option<(Vec<u8>, Vec<u8>)>,
}

impl AuthData {
    /// Reads `raw`; `registration` requires AT, an assertion refuses it. UP and UV required, BS
    /// without BE refused; the extensions map is read (and dropped) when ED is set; nothing may be
    /// left over.
    pub fn parse(raw: &[u8], registration: bool) -> Result<AuthData, Refused> {
        if raw.len() < 37 {
            return Err(Refused::AttestedData);
        }
        let rp_id_hash: [u8; 32] = raw[..32].try_into().expect("32 bytes");
        let flags = raw[32];
        let sign_count = u32::from_be_bytes(raw[33..37].try_into().expect("4 bytes"));
        if flags & FLAG_UP == 0 {
            return Err(Refused::UserPresence);
        }
        if flags & FLAG_UV == 0 {
            return Err(Refused::UserVerification);
        }
        if flags & FLAG_BS != 0 && flags & FLAG_BE == 0 {
            return Err(Refused::BackupState);
        }
        let mut pos = 37;
        let attested = if flags & FLAG_AT != 0 {
            if !registration {
                return Err(Refused::AttestedData);
            }
            let fixed = raw.get(pos..pos + 18).ok_or(Refused::AttestedData)?;
            // 16 bytes of AAGUID (unused: no attestation is trusted), then the id's length.
            let len = usize::from(u16::from_be_bytes([fixed[16], fixed[17]]));
            pos += 18;
            if !(MIN_CREDENTIAL_ID..=MAX_CREDENTIAL_ID).contains(&len) {
                return Err(Refused::CredentialIdLength);
            }
            let id = raw
                .get(pos..pos + len)
                .ok_or(Refused::AttestedData)?
                .to_vec();
            pos += len;
            let (key, used) = cbor_item(&raw[pos..]).map_err(|_| Refused::CoseKey)?;
            if !matches!(key, Cbor::Map(_)) {
                return Err(Refused::CoseKey);
            }
            let cose = raw[pos..pos + used].to_vec();
            pos += used;
            Some((id, cose))
        } else {
            if registration {
                return Err(Refused::AttestedData);
            }
            None
        };
        if flags & FLAG_ED != 0 {
            let (extensions, used) =
                cbor_item_with_bools(&raw[pos..]).map_err(|_| Refused::Extensions)?;
            if !matches!(extensions, Cbor::Map(_)) {
                return Err(Refused::Extensions);
            }
            pos += used;
        }
        if pos != raw.len() {
            return Err(Refused::Extensions);
        }
        Ok(AuthData {
            rp_id_hash,
            flags,
            sign_count,
            attested,
        })
    }

    fn rp_id_matches(&self, rp_id: &str) -> bool {
        self.rp_id_hash == <[u8; 32]>::from(Sha256::digest(rp_id.as_bytes()))
    }
}

// ---------------------------------------------------------------------------------------------
// Registration

#[derive(serde::Deserialize)]
struct RegistrationJson {
    id: String,
    #[serde(rename = "rawId")]
    raw_id: String,
    #[serde(rename = "type")]
    kind: String,
    response: RegistrationResponse,
}

#[derive(serde::Deserialize)]
struct RegistrationResponse {
    #[serde(rename = "clientDataJSON")]
    client_data_json: String,
    #[serde(rename = "attestationObject")]
    attestation_object: String,
    #[serde(default)]
    transports: Option<Vec<String>>,
}

/// A registration as the browser sent it (`credential`, D1.2), read but not yet verified.
#[derive(Debug)]
pub struct Registration {
    pub client: ClientData,
    raw_id: Vec<u8>,
    attestation_object: Vec<u8>,
    transports: Vec<String>,
}

/// What a verified registration stores (D5.2).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NewCredential {
    pub credential_id: Vec<u8>,
    /// The COSE key's bytes, as registered.
    pub public_key: Vec<u8>,
    pub alg: i64,
    pub sign_count: u32,
    pub backup_eligible: bool,
    pub backed_up: bool,
    /// `getTransports()` as JSON (known names only), for `allowCredentials`.
    pub transports: Option<String>,
}

/// The transport names WebAuthn defines; anything else is dropped.
const TRANSPORTS: [&str; 6] = ["usb", "nfc", "ble", "smart-card", "hybrid", "internal"];

impl Registration {
    pub fn parse(json: &str) -> Result<Registration, Refused> {
        let json: RegistrationJson = serde_json::from_str(json).map_err(|_| Refused::Json)?;
        let raw_id = b64(&json.raw_id)?;
        if json.kind != "public-key" || json.id != b64url(&raw_id) {
            return Err(Refused::Id);
        }
        let mut transports: Vec<String> = Vec::new();
        for t in json.response.transports.unwrap_or_default() {
            if TRANSPORTS.contains(&t.as_str()) && !transports.contains(&t) {
                transports.push(t);
            }
        }
        Ok(Registration {
            client: ClientData::parse(&b64(&json.response.client_data_json)?)?,
            raw_id,
            attestation_object: b64(&json.response.attestation_object)?,
            transports,
        })
    }

    /// Everything but the challenge (taken before): the type, the origin, the RP ID's hash, the
    /// flags, the credential id (equal to `rawId`, 16-512 bytes) and the COSE key.
    pub fn verify(&self, rp_id: &str, origins: &[String]) -> Result<NewCredential, Refused> {
        self.client.check("webauthn.create", origins, rp_id)?;
        let Cbor::Map(object) = cbor_exact(&self.attestation_object)? else {
            return Err(Refused::Cbor);
        };
        // `fmt` and `attStmt` must be there and of their kinds; nothing in them is trusted.
        if !matches!(by_text(&object, "fmt"), Some(Cbor::Text(_)))
            || !matches!(by_text(&object, "attStmt"), Some(Cbor::Map(_)))
        {
            return Err(Refused::Cbor);
        }
        let Some(Cbor::Bytes(raw)) = by_text(&object, "authData") else {
            return Err(Refused::Cbor);
        };
        let data = AuthData::parse(raw, true)?;
        if !data.rp_id_matches(rp_id) {
            return Err(Refused::RpIdHash);
        }
        let (credential_id, cose) = data.attested.expect("registration has AT");
        if credential_id != self.raw_id {
            return Err(Refused::CredentialId);
        }
        let key = CoseKey::parse(&cose)?;
        Ok(NewCredential {
            credential_id,
            public_key: cose,
            alg: key.alg,
            sign_count: data.sign_count,
            backup_eligible: data.flags & FLAG_BE != 0,
            backed_up: data.flags & FLAG_BS != 0,
            transports: (!self.transports.is_empty())
                .then(|| serde_json::to_string(&self.transports).expect("strings")),
        })
    }
}

// ---------------------------------------------------------------------------------------------
// Assertions

#[derive(serde::Deserialize)]
struct AssertionJson {
    id: String,
    #[serde(rename = "rawId")]
    raw_id: String,
    #[serde(rename = "type")]
    kind: String,
    response: AssertionResponse,
}

#[derive(serde::Deserialize)]
struct AssertionResponse {
    #[serde(rename = "clientDataJSON")]
    client_data_json: String,
    #[serde(rename = "authenticatorData")]
    authenticator_data: String,
    signature: String,
    #[serde(rename = "userHandle", default)]
    user_handle: Option<String>,
}

/// An assertion as the browser sent it, read but not yet verified.
#[derive(Debug)]
pub struct Assertion {
    pub client: ClientData,
    pub credential_id: Vec<u8>,
    /// `None` when the browser sent none (or an empty one).
    pub user_handle: Option<Vec<u8>>,
    auth_data: Vec<u8>,
    signature: Vec<u8>,
}

/// A credential as stored, for [Assertion::verify].
pub struct Stored<'a> {
    pub public_key: &'a [u8],
    pub rp_id: &'a str,
    pub sign_count: i64,
    pub backup_eligible: bool,
}

/// A verified assertion: the new counter and backup state to store.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Asserted {
    pub sign_count: u32,
    pub backed_up: bool,
}

impl Assertion {
    pub fn parse(json: &str) -> Result<Assertion, Refused> {
        let json: AssertionJson = serde_json::from_str(json).map_err(|_| Refused::Json)?;
        let credential_id = b64(&json.raw_id)?;
        if json.kind != "public-key" || json.id != b64url(&credential_id) {
            return Err(Refused::Id);
        }
        let user_handle = match json.response.user_handle.as_deref() {
            None | Some("") => None,
            Some(handle) => Some(b64(handle)?),
        };
        Ok(Assertion {
            client: ClientData::parse(&b64(&json.response.client_data_json)?)?,
            credential_id,
            user_handle,
            auth_data: b64(&json.response.authenticator_data)?,
            signature: b64(&json.response.signature)?,
        })
    }

    /// Everything but the challenge (taken before) and the user handle (the caller's, D1.4/D1.6):
    /// the type, the origin, the stored credential's RP ID, UP and UV, BE as stored, the signature
    /// and the counter rule (D5.3: when both counters are non-zero the new one must be higher).
    pub fn verify(&self, stored: &Stored, origins: &[String]) -> Result<Asserted, Refused> {
        self.client.check("webauthn.get", origins, stored.rp_id)?;
        let data = AuthData::parse(&self.auth_data, false)?;
        if !data.rp_id_matches(stored.rp_id) {
            return Err(Refused::RpIdHash);
        }
        if (data.flags & FLAG_BE != 0) != stored.backup_eligible {
            return Err(Refused::BackupEligible);
        }
        let key = CoseKey::parse(stored.public_key)?;
        let mut signed = self.auth_data.clone();
        signed.extend_from_slice(&self.client.hash);
        if !key.verify(&signed, &self.signature) {
            return Err(Refused::Signature);
        }
        // D5.3 (WebAuthn L3 §7.2 step 22, qa-passkeys-1 A-L2): once a counter was stored, every
        // use must be higher - a 0 after it is a copy's signal too. Two 0s are a synced passkey's.
        if stored.sign_count != 0 && i64::from(data.sign_count) <= stored.sign_count {
            return Err(Refused::Counter);
        }
        Ok(Asserted {
            sign_count: data.sign_count,
            backed_up: data.flags & FLAG_BS != 0,
        })
    }
}

// ---------------------------------------------------------------------------------------------
// RP ID and origins (D4.1, D4.2)

/// Public suffixes a `PASSKEY_RP_ID` may not be (D4.1): a short built-in list of the common ones;
/// a single label (`com`, `no`) is refused anyway (no dot), and the browser refuses the rest.
const PUBLIC_SUFFIXES: &[&str] = &[
    "co.uk",
    "org.uk",
    "me.uk",
    "ac.uk",
    "gov.uk",
    "ltd.uk",
    "plc.uk",
    "net.uk",
    "com.au",
    "net.au",
    "org.au",
    "edu.au",
    "gov.au",
    "co.nz",
    "net.nz",
    "org.nz",
    "co.jp",
    "ne.jp",
    "or.jp",
    "com.br",
    "com.cn",
    "com.mx",
    "com.tr",
    "com.tw",
    "com.hk",
    "com.sg",
    "co.za",
    "co.in",
    "co.kr",
    "co.il",
    "com.ar",
    "com.pl",
    "co.at",
    "or.at",
    "priv.no",
    "co.no",
    "github.io",
    "gitlab.io",
    "ts.net",
    "duckdns.org",
    "dyndns.org",
    "no-ip.org",
    "ddns.net",
    "herokuapp.com",
    "netlify.app",
    "vercel.app",
    "pages.dev",
    "workers.dev",
    "web.app",
    "firebaseapp.com",
    "appspot.com",
    "blogspot.com",
    "cloudfront.net",
    "azurewebsites.net",
    "ngrok.io",
    "ngrok-free.app",
    "ngrok.app",
    "trycloudflare.com",
    "fly.dev",
    "onrender.com",
];

/// A host name a passkey can be made for: lowercase letters, digits and hyphens in labels of 1-63
/// (none starting or ending with a hyphen), at most 253 in all, with a dot - or `localhost`. An IP
/// literal (every label digits) is none: browsers refuse an IP as an RP ID.
pub fn valid_name(name: &str) -> bool {
    if name == "localhost" {
        return true;
    }
    let labels: Vec<&str> = name.split('.').collect();
    name.len() <= 253
        && labels.len() >= 2
        && labels.iter().all(|label| {
            (1..=63).contains(&label.len())
                && label
                    .bytes()
                    .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
                && !label.starts_with('-')
                && !label.ends_with('-')
        })
        && !labels
            .last()
            .is_some_and(|tld| tld.bytes().all(|b| b.is_ascii_digit()))
}

/// A Host (or `X-Forwarded-Host`) value as the RP ID's host and its port: lowercased, the port
/// split off, one trailing dot dropped (`handy.example.com.` is `handy.example.com`). `None` for an
/// IP literal or a name [valid_name] refuses.
pub fn host_of(raw: &str) -> Option<(String, Option<u16>)> {
    let raw = raw.trim().to_ascii_lowercase();
    if raw.starts_with('[') {
        return None; // An IPv6 literal.
    }
    let (host, port) = match raw.rsplit_once(':') {
        Some((host, port)) => (host.to_string(), Some(port.parse::<u16>().ok()?)),
        None => (raw, None),
    };
    let host = host.strip_suffix('.').unwrap_or(&host).to_string();
    valid_name(&host).then_some((host, port))
}

/// `PASSKEY_RP_ID` and `PASSKEY_ORIGINS` (D4.1, D4.2), read once at start, and whether
/// `http://localhost` may be an origin (`INSECURE_COOKIES`, development only).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct PasskeyConfig {
    /// One RP ID for every host under it; `None` = each request's own host.
    pub rp_id: Option<String>,
    /// Full origins a proxy's outside name may have, each under `rp_id`.
    pub origins: Vec<String>,
    pub insecure_localhost: bool,
}

/// An origin as written in `PASSKEY_ORIGINS`: `https://host[:port]`, lowercase, no path - or
/// `http://localhost[:port]` when `insecure`. Its host, when it is one.
fn origin_host(origin: &str, insecure: bool) -> Option<String> {
    let (scheme, rest) = origin.split_once("://")?;
    let (host, port) = match rest.rsplit_once(':') {
        Some((host, port)) => (host, Some(port)),
        None => (rest, None),
    };
    // A default port is never in a browser's origin (qa-passkeys-1 A-N5): refused, not normalized.
    let default = if scheme == "https" { 443 } else { 80 };
    let port_ok = port.is_none_or(|p| p.parse::<u16>().is_ok_and(|p| p != 0 && p != default));
    let ok = port_ok
        && valid_name(host)
        && match scheme {
            "https" => true,
            "http" => insecure && host == "localhost",
            _ => false,
        };
    ok.then(|| host.to_string())
}

impl PasskeyConfig {
    pub fn from_env() -> Result<Self, String> {
        Self::from_vars(&std::env::vars().collect())
    }

    /// The start-up check: a bad value is the reason the server refuses to start.
    pub fn from_vars(vars: &std::collections::HashMap<String, String>) -> Result<Self, String> {
        let get = |key: &str| {
            vars.get(key)
                .map(|v| v.trim().to_string())
                .filter(|v| !v.is_empty())
        };
        let insecure_localhost = get("INSECURE_COOKIES").is_some_and(|v| v == "true" || v == "1");
        let rp_id = match get("PASSKEY_RP_ID") {
            None => None,
            Some(rp) => {
                if rp != rp.to_ascii_lowercase() {
                    return Err(format!(
                        "PASSKEY_RP_ID={rp:?} has upper case - write the name in lower case"
                    ));
                }
                if rp.parse::<std::net::IpAddr>().is_ok() || !valid_name(&rp) {
                    return Err(format!(
                        "PASSKEY_RP_ID={rp:?} is not a DNS name with a dot (or localhost) - \
                         browsers refuse an IP address or a bare name as a passkey's domain"
                    ));
                }
                if PUBLIC_SUFFIXES.contains(&rp.as_str()) {
                    return Err(format!(
                        "PASSKEY_RP_ID={rp:?} is a public suffix - set it to your own domain, \
                         best the PWA's own name (e.g. handy.example.com)"
                    ));
                }
                Some(rp)
            }
        };
        let mut origins = Vec::new();
        if let Some(list) = get("PASSKEY_ORIGINS") {
            let Some(rp) = &rp_id else {
                return Err(
                    "PASSKEY_ORIGINS is set without PASSKEY_RP_ID - set the RP ID the origins \
                     fall under"
                        .to_string(),
                );
            };
            for origin in list.split(',').map(str::trim).filter(|o| !o.is_empty()) {
                let fits = origin == origin.to_ascii_lowercase()
                    && origin_host(origin, insecure_localhost).is_some_and(|h| under(&h, rp));
                if !fits {
                    return Err(format!(
                        "PASSKEY_ORIGINS: {origin:?} is not an https origin (scheme and host, \
                         lower case, no path, no default port) under PASSKEY_RP_ID={rp}"
                    ));
                }
                if !origins.iter().any(|o| o == origin) {
                    origins.push(origin.to_string());
                }
            }
        }
        Ok(PasskeyConfig {
            rp_id,
            origins,
            insecure_localhost,
        })
    }

    /// The RP ID for a request at `host` ([host_of]'s; `None` when the host isn't a name): the
    /// host itself, or `PASSKEY_RP_ID` when the host is under it. A host outside it gets no
    /// passkey UI - unless `PASSKEY_ORIGINS` is set: then the proxy's outside name isn't what
    /// reaches the server, and only the allowlist can pass an origin.
    pub fn rp_id_for(&self, host: Option<&str>) -> Option<String> {
        let Some(rp) = &self.rp_id else {
            return host.map(str::to_string);
        };
        match host {
            Some(host) if host == rp => Some(rp.clone()),
            // Another name under the RP ID only when PASSKEY_ORIGINS lists it (qa-passkeys-1 A-M1).
            Some(host) if under(host, rp) => self.lists(host).then(|| rp.clone()),
            // A proxy's other name (not an IP: no passkey UI there, A-N4).
            Some(_) if !self.origins.is_empty() => Some(rp.clone()),
            _ => None,
        }
    }

    /// `PASSKEY_ORIGINS` has an origin at `host`.
    fn lists(&self, host: &str) -> bool {
        self.origins
            .iter()
            .any(|origin| origin_host(origin, self.insecure_localhost).as_deref() == Some(host))
    }

    /// The origins a ceremony at `host:port` may come from (D4.2): `https://` + the RP ID itself
    /// (+ its port when not 443) when the request's host is the RP ID, `http://localhost[:port]`
    /// only with `INSECURE_COOKIES`, and `PASSKEY_ORIGINS`. Never an origin built from another host
    /// name (qa-passkeys-1 A-M1): under a shared `PASSKEY_RP_ID` a client that forges `Host` could
    /// make a sibling subdomain's origin "this request's" - every other name must be listed.
    pub fn origins_for(&self, host: Option<(&str, Option<u16>)>, rp_id: &str) -> Vec<String> {
        let mut origins = Vec::new();
        if let Some((host, port)) = host
            && host == rp_id
        {
            origins.push(match port {
                None | Some(443) => format!("https://{host}"),
                Some(port) => format!("https://{host}:{port}"),
            });
            if self.insecure_localhost && host == "localhost" {
                origins.push(match port {
                    None | Some(80) => "http://localhost".to_string(),
                    Some(port) => format!("http://localhost:{port}"),
                });
            }
        }
        for origin in &self.origins {
            if !origins.contains(origin) {
                origins.push(origin.clone());
            }
        }
        origins
    }

    /// The start-up line about passkeys.
    pub fn startup_line(&self) -> String {
        match &self.rp_id {
            Some(rp) if self.origins.is_empty() => format!(
                "passkeys: RP ID {rp} (PASSKEY_RP_ID), at https://{rp} only - another name under it \
                 needs PASSKEY_ORIGINS"
            ),
            Some(rp) => format!(
                "passkeys: RP ID {rp} (PASSKEY_RP_ID), origins {} (PASSKEY_ORIGINS)",
                self.origins.join(", ")
            ),
            None => "passkeys: each request's host is its RP ID (PASSKEY_RP_ID unset); an IP \
                     address shows no passkey sign-in"
                .to_string(),
        }
    }
}

// ---------------------------------------------------------------------------------------------
// A fake authenticator for the tests (D7: ring-generated keys signing real authenticatorData)

#[cfg(test)]
pub mod fake {
    use super::*;
    use ring::rand::SystemRandom;
    use ring::signature::{self, KeyPair};

    /// A minimal CBOR writer for the fake's keys and objects.
    pub fn enc(item: &Cbor, out: &mut Vec<u8>) {
        fn head(major: u8, n: u64, out: &mut Vec<u8>) {
            let m = major << 5;
            match n {
                0..=23 => out.push(m | n as u8),
                24..=0xff => out.extend([m | 24, n as u8]),
                0x100..=0xffff => {
                    out.push(m | 25);
                    out.extend((n as u16).to_be_bytes());
                }
                0x1_0000..=0xffff_ffff => {
                    out.push(m | 26);
                    out.extend((n as u32).to_be_bytes());
                }
                _ => {
                    out.push(m | 27);
                    out.extend(n.to_be_bytes());
                }
            }
        }
        match item {
            Cbor::Uint(n) => head(0, *n, out),
            Cbor::Nint(n) => head(1, *n, out),
            Cbor::Bytes(b) => {
                head(2, b.len() as u64, out);
                out.extend(b);
            }
            Cbor::Text(t) => {
                head(3, t.len() as u64, out);
                out.extend(t.as_bytes());
            }
            Cbor::Array(items) => {
                head(4, items.len() as u64, out);
                for i in items {
                    enc(i, out);
                }
            }
            Cbor::Map(entries) => {
                head(5, entries.len() as u64, out);
                for (k, v) in entries {
                    enc(k, out);
                    enc(v, out);
                }
            }
            Cbor::Bool(b) => out.push(if *b { 0xf5 } else { 0xf4 }),
        }
    }

    pub fn encoded(item: &Cbor) -> Vec<u8> {
        let mut out = Vec::new();
        enc(item, &mut out);
        out
    }

    pub fn int(n: i64) -> Cbor {
        if n >= 0 {
            Cbor::Uint(n as u64)
        } else {
            Cbor::Nint((-1 - n) as u64)
        }
    }

    enum Pair {
        Es256(signature::EcdsaKeyPair),
        Ed25519(signature::Ed25519KeyPair),
    }

    /// One credential on a fake authenticator: its key, id, counter and flags.
    pub struct FakeAuthenticator {
        pair: Pair,
        pub credential_id: Vec<u8>,
        pub counter: u32,
        /// Flags of the next ceremony (UP, UV and BE by default; BS follows BE).
        pub flags: u8,
        pub user_handle: Vec<u8>,
    }

    impl FakeAuthenticator {
        pub fn es256(user_handle: &[u8]) -> Self {
            let rng = SystemRandom::new();
            let alg = &signature::ECDSA_P256_SHA256_ASN1_SIGNING;
            let pkcs8 = signature::EcdsaKeyPair::generate_pkcs8(alg, &rng).unwrap();
            let pair = signature::EcdsaKeyPair::from_pkcs8(alg, pkcs8.as_ref(), &rng).unwrap();
            Self::with(Pair::Es256(pair), user_handle)
        }

        pub fn ed25519(user_handle: &[u8]) -> Self {
            let rng = SystemRandom::new();
            let pkcs8 = signature::Ed25519KeyPair::generate_pkcs8(&rng).unwrap();
            let pair = signature::Ed25519KeyPair::from_pkcs8(pkcs8.as_ref()).unwrap();
            Self::with(Pair::Ed25519(pair), user_handle)
        }

        fn with(pair: Pair, user_handle: &[u8]) -> Self {
            let mut credential_id = vec![0u8; 32];
            ring::rand::SecureRandom::fill(&SystemRandom::new(), &mut credential_id).unwrap();
            FakeAuthenticator {
                pair,
                credential_id,
                counter: 0,
                flags: FLAG_UP | FLAG_UV | FLAG_BE | FLAG_BS,
                user_handle: user_handle.to_vec(),
            }
        }

        pub fn alg(&self) -> i64 {
            match self.pair {
                Pair::Es256(_) => ES256,
                Pair::Ed25519(_) => EDDSA,
            }
        }

        /// The COSE key's bytes.
        pub fn cose(&self) -> Vec<u8> {
            encoded(&match &self.pair {
                Pair::Es256(pair) => {
                    let point = pair.public_key().as_ref();
                    Cbor::Map(vec![
                        (int(1), int(2)),
                        (int(3), int(ES256)),
                        (int(-1), int(1)),
                        (int(-2), Cbor::Bytes(point[1..33].to_vec())),
                        (int(-3), Cbor::Bytes(point[33..].to_vec())),
                    ])
                }
                Pair::Ed25519(pair) => Cbor::Map(vec![
                    (int(1), int(1)),
                    (int(3), int(EDDSA)),
                    (int(-1), int(6)),
                    (int(-2), Cbor::Bytes(pair.public_key().as_ref().to_vec())),
                ]),
            })
        }

        pub fn sign(&self, message: &[u8]) -> Vec<u8> {
            match &self.pair {
                Pair::Es256(pair) => pair
                    .sign(&SystemRandom::new(), message)
                    .unwrap()
                    .as_ref()
                    .to_vec(),
                Pair::Ed25519(pair) => pair.sign(message).as_ref().to_vec(),
            }
        }

        /// A registration's `credential` JSON for `challenge` at `origin` with `rp_id`'s hash.
        pub fn register(&self, challenge: &[u8], origin: &str, rp_id: &str) -> String {
            let auth = self.auth_data(rp_id, true, None);
            let client = client_json("webauthn.create", challenge, origin, false);
            registration_json(&self.credential_id, &client, &attestation(&auth))
        }

        /// authenticatorData: the RP ID's hash, the flags (AT added at registration, ED when
        /// `extensions` are given), the counter, the attested data and the extensions.
        pub fn auth_data(&self, rp_id: &str, attested: bool, extensions: Option<&[u8]>) -> Vec<u8> {
            let mut flags = self.flags;
            if attested {
                flags |= FLAG_AT;
            }
            if extensions.is_some() {
                flags |= FLAG_ED;
            }
            let mut out = Sha256::digest(rp_id.as_bytes()).to_vec();
            out.push(flags);
            out.extend(self.counter.to_be_bytes());
            if attested {
                out.extend([0u8; 16]);
                out.extend((self.credential_id.len() as u16).to_be_bytes());
                out.extend(&self.credential_id);
                out.extend(self.cose());
            }
            if let Some(extensions) = extensions {
                out.extend(extensions);
            }
            out
        }

        /// An assertion's JSON for `challenge` at `origin`, with or without the user handle.
        pub fn assert(
            &mut self,
            challenge: &[u8],
            origin: &str,
            rp_id: &str,
            with_handle: bool,
        ) -> String {
            if self.counter != 0 {
                self.counter += 1;
            }
            let auth = self.auth_data(rp_id, false, None);
            let client = client_json("webauthn.get", challenge, origin, false);
            let handle = with_handle.then(|| self.user_handle.clone());
            self.assertion_json(&auth, &client, handle.as_deref())
        }

        /// An assertion's JSON from its parts, signed.
        pub fn assertion_json(&self, auth: &[u8], client: &[u8], handle: Option<&[u8]>) -> String {
            let mut signed = auth.to_vec();
            signed.extend(Sha256::digest(client));
            let signature = self.sign(&signed);
            serde_json::json!({
                "id": b64url(&self.credential_id),
                "rawId": b64url(&self.credential_id),
                "type": "public-key",
                "response": {
                    "clientDataJSON": b64url(client),
                    "authenticatorData": b64url(auth),
                    "signature": b64url(&signature),
                    "userHandle": handle.map(b64url),
                },
            })
            .to_string()
        }
    }

    pub fn client_json(kind: &str, challenge: &[u8], origin: &str, cross_origin: bool) -> Vec<u8> {
        serde_json::json!({
            "type": kind,
            "challenge": b64url(challenge),
            "origin": origin,
            "crossOrigin": cross_origin,
        })
        .to_string()
        .into_bytes()
    }

    /// An attestation object with `fmt` "none".
    pub fn attestation(auth: &[u8]) -> Vec<u8> {
        encoded(&Cbor::Map(vec![
            (Cbor::Text("fmt".into()), Cbor::Text("none".into())),
            (Cbor::Text("attStmt".into()), Cbor::Map(vec![])),
            (Cbor::Text("authData".into()), Cbor::Bytes(auth.to_vec())),
        ]))
    }

    pub fn registration_json(raw_id: &[u8], client: &[u8], attestation: &[u8]) -> String {
        serde_json::json!({
            "id": b64url(raw_id),
            "rawId": b64url(raw_id),
            "type": "public-key",
            "response": {
                "clientDataJSON": b64url(client),
                "attestationObject": b64url(attestation),
                "transports": ["internal", "hybrid", "carrier-pigeon"],
            },
        })
        .to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::fake::*;
    use super::*;

    const RP: &str = "handy.example.com";
    const ORIGIN: &str = "https://handy.example.com";
    const CHALLENGE: [u8; 32] = [7; 32];

    fn origins() -> Vec<String> {
        vec![ORIGIN.to_string()]
    }

    fn registered(fake: &FakeAuthenticator) -> NewCredential {
        Registration::parse(&fake.register(&CHALLENGE, ORIGIN, RP))
            .unwrap()
            .verify(RP, &origins())
            .unwrap()
    }

    /// A registration with its authData replaced.
    fn register_with(fake: &FakeAuthenticator, auth: &[u8]) -> Result<NewCredential, Refused> {
        let client = client_json("webauthn.create", &CHALLENGE, ORIGIN, false);
        let json = registration_json(&fake.credential_id, &client, &attestation(auth));
        Registration::parse(&json)?.verify(RP, &origins())
    }

    fn verify_assertion(
        json: &str,
        stored: &NewCredential,
        sign_count: i64,
    ) -> Result<Asserted, Refused> {
        Assertion::parse(json)?.verify(
            &Stored {
                public_key: &stored.public_key,
                rp_id: RP,
                sign_count,
                backup_eligible: stored.backup_eligible,
            },
            &origins(),
        )
    }

    #[test]
    fn the_cbor_reader_keeps_to_its_bounds() {
        // A map of an int to a byte string reads, and the bytes it took are counted.
        let item = encoded(&Cbor::Map(vec![(int(-2), Cbor::Bytes(vec![1, 2]))]));
        assert_eq!(cbor_item(&item).unwrap().1, item.len());
        // Trailing bytes after the one item.
        let mut trailing = item.clone();
        trailing.push(0);
        assert_eq!(cbor_exact(&trailing), Err(Refused::Cbor));
        // Indefinite lengths (a byte string, an array, a map).
        for indefinite in [[0x5f, 0xff], [0x9f, 0xff], [0xbf, 0xff]] {
            assert_eq!(cbor_exact(&indefinite), Err(Refused::Cbor));
        }
        // Tags, floats, simple values, reserved additional information.
        for other in [vec![0xc0, 0x00], vec![0xf9, 0, 0], vec![0xf5], vec![0x1c]] {
            assert_eq!(cbor_exact(&other), Err(Refused::Cbor), "{other:?}");
        }
        // A huge length is refused before anything is read or allocated.
        assert_eq!(
            cbor_exact(&[0x5b, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff]),
            Err(Refused::Cbor)
        );
        assert_eq!(
            cbor_exact(&[0x9b, 0, 0, 0, 1, 0, 0, 0, 0]),
            Err(Refused::Cbor)
        );
        assert_eq!(cbor_exact(&[0x44, 1, 2]), Err(Refused::Cbor));
        // Depth: four containers read, five don't.
        let mut nested = Cbor::Uint(1);
        for _ in 0..MAX_DEPTH {
            nested = Cbor::Array(vec![nested]);
        }
        assert!(cbor_exact(&encoded(&nested)).is_ok());
        assert_eq!(
            cbor_exact(&encoded(&Cbor::Array(vec![nested]))),
            Err(Refused::Cbor)
        );
        // A duplicate key; text that isn't UTF-8.
        let duplicate = encoded(&Cbor::Map(vec![(int(3), int(-7)), (int(3), int(-8))]));
        assert_eq!(cbor_exact(&duplicate), Err(Refused::Cbor));
        assert_eq!(cbor_exact(&[0x62, 0xff, 0xfe]), Err(Refused::Cbor));
        assert_eq!(cbor_exact(&[]), Err(Refused::Cbor));
    }

    #[test]
    fn es256_and_ed25519_register_and_assert() {
        for mut fake in [
            FakeAuthenticator::es256(b"user"),
            FakeAuthenticator::ed25519(b"user"),
        ] {
            let stored = registered(&fake);
            assert_eq!(stored.alg, fake.alg());
            assert_eq!(stored.credential_id, fake.credential_id);
            assert!(stored.backup_eligible && stored.backed_up);
            // Unknown transports are dropped.
            assert_eq!(
                stored.transports.as_deref(),
                Some(r#"["internal","hybrid"]"#)
            );
            let json = fake.assert(&CHALLENGE, ORIGIN, RP, true);
            let asserted = verify_assertion(&json, &stored, 0).unwrap();
            assert_eq!(
                asserted,
                Asserted {
                    sign_count: 0,
                    backed_up: true
                }
            );
            let parsed = Assertion::parse(&json).unwrap();
            assert_eq!(parsed.user_handle.as_deref(), Some(&b"user"[..]));
            assert_eq!(parsed.client.challenge, CHALLENGE);
        }
    }

    /// RS256 against a vector made once with a throwaway RSA key (only public data is kept:
    /// `testdata/webauthn/rs256.json`, written by `scripts/webauthn-rs256-vector.py`).
    #[test]
    fn an_rs256_vector_verifies() {
        let vector: serde_json::Value =
            serde_json::from_str(include_str!("../testdata/webauthn/rs256.json")).unwrap();
        let rp = vector["rp_id"].as_str().unwrap();
        let origin = vector["origin"].as_str().unwrap().to_string();
        let registration = Registration::parse(&vector["registration"].to_string()).unwrap();
        let stored = registration
            .verify(rp, std::slice::from_ref(&origin))
            .unwrap();
        assert_eq!(stored.alg, RS256);
        let assertion = Assertion::parse(&vector["assertion"].to_string()).unwrap();
        let check = |sign_count| {
            assertion.verify(
                &Stored {
                    public_key: &stored.public_key,
                    rp_id: rp,
                    sign_count,
                    backup_eligible: stored.backup_eligible,
                },
                std::slice::from_ref(&origin),
            )
        };
        assert_eq!(check(0).unwrap().sign_count, 2);
        // The same assertion once its counter is stored: a copy.
        assert_eq!(check(2), Err(Refused::Counter));
    }

    #[test]
    fn a_registration_with_an_extensions_map_reads() {
        let fake = FakeAuthenticator::es256(b"user");
        // credProps and credProtect, as some authenticators add them (ED set).
        let extensions = encoded(&Cbor::Map(vec![
            (Cbor::Text("credProtect".into()), int(2)),
            (
                Cbor::Text("credProps".into()),
                Cbor::Map(vec![(Cbor::Text("rk".into()), Cbor::Uint(1))]),
            ),
        ]));
        let auth = fake.auth_data(RP, true, Some(&extensions));
        assert_eq!(register_with(&fake, &auth).unwrap().alg, ES256);
        // A boolean output (hmac-secret) reads in the extensions map (A-N1), nowhere else.
        let bools = encoded(&Cbor::Map(vec![(
            Cbor::Text("hmac-secret".into()),
            Cbor::Bool(true),
        )]));
        let auth = fake.auth_data(RP, true, Some(&bools));
        assert_eq!(register_with(&fake, &auth).unwrap().alg, ES256);
        assert_eq!(cbor_exact(&bools), Err(Refused::Cbor));
        // ED set with nothing after the key.
        let mut no_map = fake.auth_data(RP, true, None);
        no_map[32] |= FLAG_ED;
        assert_eq!(register_with(&fake, &no_map), Err(Refused::Extensions));
        // ED unset with bytes left over.
        let mut left = fake.auth_data(RP, true, None);
        left.extend(&extensions);
        assert_eq!(register_with(&fake, &left), Err(Refused::Extensions));
        // ED set with something other than a map, or the map with trailing bytes.
        let auth = fake.auth_data(RP, true, Some(&encoded(&Cbor::Uint(5))));
        assert_eq!(register_with(&fake, &auth), Err(Refused::Extensions));
        let mut trailing = extensions.clone();
        trailing.push(0);
        let auth = fake.auth_data(RP, true, Some(&trailing));
        assert_eq!(register_with(&fake, &auth), Err(Refused::Extensions));
    }

    #[test]
    fn a_registration_is_refused_for_each_rule() {
        let fake = FakeAuthenticator::es256(b"user");
        let auth = fake.auth_data(RP, true, None);
        let att = attestation(&auth);
        let make = |kind: &str, origin: &str, cross: bool| {
            let client = client_json(kind, &CHALLENGE, origin, cross);
            Registration::parse(&registration_json(&fake.credential_id, &client, &att))
                .unwrap()
                .verify(RP, &origins())
        };
        assert_eq!(make("webauthn.get", ORIGIN, false), Err(Refused::Type));
        // A sibling subdomain under the RP ID is another origin (D4.2).
        assert_eq!(
            make("webauthn.create", "https://wiki.example.com", false),
            Err(Refused::Origin)
        );
        assert_eq!(
            make("webauthn.create", "http://handy.example.com", false),
            Err(Refused::Origin)
        );
        assert_eq!(
            make("webauthn.create", ORIGIN, true),
            Err(Refused::CrossOrigin)
        );
        // Another RP ID's hash.
        let other = fake.auth_data("other.example.com", true, None);
        assert_eq!(register_with(&fake, &other), Err(Refused::RpIdHash));
        // UV, UP, BS without BE.
        for (clear, set, refused) in [
            (FLAG_UV, 0, Refused::UserVerification),
            (FLAG_UP, 0, Refused::UserPresence),
            (FLAG_BE, FLAG_BS, Refused::BackupState),
        ] {
            let mut auth = fake.auth_data(RP, true, None);
            auth[32] = (auth[32] & !clear) | set;
            assert_eq!(register_with(&fake, &auth), Err(refused));
        }
        // No attested data.
        let mut auth = fake.auth_data(RP, false, None);
        auth[32] &= !FLAG_AT;
        assert_eq!(register_with(&fake, &auth), Err(Refused::AttestedData));
        // The authData's id differs from rawId; rawId differs from id.
        let client = client_json("webauthn.create", &CHALLENGE, ORIGIN, false);
        let json = registration_json(&[9; 32], &client, &att);
        assert_eq!(
            Registration::parse(&json).unwrap().verify(RP, &origins()),
            Err(Refused::CredentialId)
        );
        let mut value: serde_json::Value = serde_json::from_str(&json).unwrap();
        value["id"] = serde_json::json!(b64url(&fake.credential_id));
        assert_eq!(
            Registration::parse(&value.to_string()).unwrap_err(),
            Refused::Id
        );
    }

    /// The credential id's length and the COSE key's alg, kty and crv (D5.1).
    #[test]
    fn a_bad_credential_id_or_key_is_refused() {
        let mut fake = FakeAuthenticator::es256(b"user");
        fake.credential_id = vec![1; MAX_CREDENTIAL_ID + 1];
        let auth = fake.auth_data(RP, true, None);
        assert_eq!(
            register_with(&fake, &auth),
            Err(Refused::CredentialIdLength)
        );
        fake.credential_id = vec![1; MIN_CREDENTIAL_ID - 1];
        let auth = fake.auth_data(RP, true, None);
        assert_eq!(
            register_with(&fake, &auth),
            Err(Refused::CredentialIdLength)
        );

        let cose = |entries: Vec<(Cbor, Cbor)>| encoded(&Cbor::Map(entries));
        let fake = FakeAuthenticator::es256(b"user");
        let Cbor::Map(good) = cbor_exact(&fake.cose()).unwrap() else {
            unreachable!()
        };
        let replace = |key: i64, value: Cbor| {
            good.iter()
                .map(|(k, v)| {
                    if k.int() == Some(i128::from(key)) {
                        (k.clone(), value.clone())
                    } else {
                        (k.clone(), v.clone())
                    }
                })
                .collect::<Vec<_>>()
        };
        // ES256 with an OKP kty, with Ed25519's curve; EdDSA named on an EC2 key; a not-requested alg.
        assert_eq!(
            CoseKey::parse(&cose(replace(1, int(1)))),
            Err(Refused::CoseKey)
        );
        assert_eq!(
            CoseKey::parse(&cose(replace(-1, int(6)))),
            Err(Refused::CoseKey)
        );
        assert_eq!(
            CoseKey::parse(&cose(replace(3, int(EDDSA)))),
            Err(Refused::CoseKey)
        );
        assert_eq!(
            CoseKey::parse(&cose(replace(3, int(-35)))),
            Err(Refused::Algorithm)
        );
        assert_eq!(
            CoseKey::parse(&cose(replace(3, int(RS256)))),
            Err(Refused::CoseKey)
        );
        // A point off the curve.
        assert_eq!(
            CoseKey::parse(&cose(replace(-3, Cbor::Bytes(vec![5; 32])))),
            Err(Refused::Key)
        );
        // Ed25519 with 31 bytes; RSA with a 1024-bit modulus.
        let ed = cose(vec![
            (int(1), int(1)),
            (int(3), int(EDDSA)),
            (int(-1), int(6)),
            (int(-2), Cbor::Bytes(vec![1; 31])),
        ]);
        assert_eq!(CoseKey::parse(&ed), Err(Refused::Key));
        let rsa = |n: Vec<u8>, e: Vec<u8>| {
            cose(vec![
                (int(1), int(3)),
                (int(3), int(RS256)),
                (int(-1), Cbor::Bytes(n)),
                (int(-2), Cbor::Bytes(e)),
            ])
        };
        let e = vec![1, 0, 1];
        assert!(CoseKey::parse(&rsa(vec![0xc5; 256], e.clone())).is_ok());
        // 1024 bits; 8192 (past the 4096 that keeps an assertion in 4 KiB); a leading zero byte
        // (ring refuses it, A-L3); an even exponent, 1, past 2^33-1, a leading zero.
        for (n, e) in [
            (vec![0xff; 128], e.clone()),
            (vec![0xff; 1024], e.clone()),
            ([vec![0], vec![0xff; 256]].concat(), e.clone()),
            (vec![0xc5; 256], vec![1, 0, 0]),
            (vec![0xc5; 256], vec![1]),
            (vec![0xc5; 256], vec![2, 0, 0, 0, 1]),
            (vec![0xc5; 256], vec![0, 1, 0, 1]),
        ] {
            assert_eq!(
                CoseKey::parse(&rsa(n, e.clone())),
                Err(Refused::Key),
                "{e:?}"
            );
        }
        assert_eq!(bit_length(&[0, 0x01, 0]), 9);
    }

    #[test]
    fn an_assertion_is_refused_for_each_rule() {
        let mut fake = FakeAuthenticator::es256(b"user");
        let stored = registered(&fake);
        let sign = |fake: &FakeAuthenticator, auth: &[u8], client: &[u8]| {
            verify_assertion(&fake.assertion_json(auth, client, None), &stored, 0)
        };
        let auth = fake.auth_data(RP, false, None);
        let client =
            |kind: &str, origin: &str, cross: bool| client_json(kind, &CHALLENGE, origin, cross);
        assert!(sign(&fake, &auth, &client("webauthn.get", ORIGIN, false)).is_ok());
        assert_eq!(
            sign(&fake, &auth, &client("webauthn.create", ORIGIN, false)),
            Err(Refused::Type)
        );
        assert_eq!(
            sign(
                &fake,
                &auth,
                &client("webauthn.get", "https://wiki.example.com", false)
            ),
            Err(Refused::Origin)
        );
        assert_eq!(
            sign(&fake, &auth, &client("webauthn.get", ORIGIN, true)),
            Err(Refused::CrossOrigin)
        );
        let get = client("webauthn.get", ORIGIN, false);
        let other = fake.auth_data("other.example.com", false, None);
        assert_eq!(sign(&fake, &other, &get), Err(Refused::RpIdHash));
        let mut no_uv = auth.clone();
        no_uv[32] &= !FLAG_UV;
        assert_eq!(sign(&fake, &no_uv, &get), Err(Refused::UserVerification));
        // BE changed since the registration.
        let mut no_be = auth.clone();
        no_be[32] &= !(FLAG_BE | FLAG_BS);
        assert_eq!(sign(&fake, &no_be, &get), Err(Refused::BackupEligible));
        // AT in an assertion.
        let attested = fake.auth_data(RP, true, None);
        assert_eq!(sign(&fake, &attested, &get), Err(Refused::AttestedData));
        // Another key's signature.
        let stranger = FakeAuthenticator::es256(b"user");
        let mut json: serde_json::Value =
            serde_json::from_str(&stranger.assertion_json(&auth, &get, None)).unwrap();
        json["id"] = serde_json::json!(b64url(&fake.credential_id));
        json["rawId"] = json["id"].clone();
        assert_eq!(
            verify_assertion(&json.to_string(), &stored, 0),
            Err(Refused::Signature)
        );
        // BS may change between uses; it is reported.
        fake.flags &= !FLAG_BS;
        let json = fake.assert(&CHALLENGE, ORIGIN, RP, false);
        assert!(!verify_assertion(&json, &stored, 0).unwrap().backed_up);
    }

    /// D5.3: both counters non-zero and the new one not higher is a copy.
    #[test]
    fn a_counter_that_goes_back_is_refused() {
        let mut fake = FakeAuthenticator::ed25519(b"user");
        let stored = registered(&fake);
        fake.counter = 5;
        let json = fake.assert(&CHALLENGE, ORIGIN, RP, false); // 6
        assert_eq!(verify_assertion(&json, &stored, 5).unwrap().sign_count, 6);
        assert_eq!(verify_assertion(&json, &stored, 6), Err(Refused::Counter));
        assert_eq!(verify_assertion(&json, &stored, 9), Err(Refused::Counter));
        // A 0 after a stored counter is a copy's signal too (A-L2); two 0s are a synced passkey's.
        fake.counter = 0;
        let json = fake.assert(&CHALLENGE, ORIGIN, RP, false);
        assert_eq!(verify_assertion(&json, &stored, 5), Err(Refused::Counter));
        assert!(verify_assertion(&json, &stored, 0).is_ok());
    }

    #[test]
    fn hosts_and_rp_ids() {
        assert_eq!(
            host_of("Handy.Example.com."),
            Some(("handy.example.com".to_string(), None))
        );
        assert_eq!(
            host_of("handy.example.com:8443"),
            Some(("handy.example.com".to_string(), Some(8443)))
        );
        assert_eq!(
            host_of("localhost:3100"),
            Some(("localhost".to_string(), Some(3100)))
        );
        for bad in [
            "192.168.1.10",
            "192.168.1.10:3100",
            "[::1]:3100",
            "pi",
            "a_b.example.com",
            "-x.example.com",
            "x.example.com:port",
            "",
        ] {
            assert_eq!(host_of(bad), None, "{bad}");
        }
        let config = PasskeyConfig::default();
        assert_eq!(
            config.rp_id_for(Some("handy.example.com")).as_deref(),
            Some("handy.example.com")
        );
        assert_eq!(config.rp_id_for(None), None);
        assert_eq!(
            config.origins_for(Some(("handy.example.com", Some(443))), "handy.example.com"),
            ["https://handy.example.com"]
        );
        assert_eq!(
            config.origins_for(Some(("handy.example.com", Some(8443))), "handy.example.com"),
            ["https://handy.example.com:8443"]
        );
        // localhost over http only with INSECURE_COOKIES.
        assert_eq!(
            config.origins_for(Some(("localhost", Some(3100))), "localhost"),
            ["https://localhost:3100"]
        );
        let insecure = PasskeyConfig {
            insecure_localhost: true,
            ..PasskeyConfig::default()
        };
        assert_eq!(
            insecure.origins_for(Some(("localhost", Some(3100))), "localhost"),
            ["https://localhost:3100", "http://localhost:3100"]
        );
        // A shared RP ID (A-M1): the RP ID's own host is its origin; another name under it is
        // offered only when PASSKEY_ORIGINS lists it, and never gets an origin from its Host.
        let shared = PasskeyConfig {
            rp_id: Some("example.com".into()),
            ..PasskeyConfig::default()
        };
        assert_eq!(
            shared.rp_id_for(Some("example.com")).as_deref(),
            Some("example.com")
        );
        assert_eq!(shared.rp_id_for(Some("handy.example.com")), None);
        assert_eq!(shared.rp_id_for(Some("pi.tail1.ts.net")), None);
        assert_eq!(
            shared.origins_for(Some(("example.com", None)), "example.com"),
            ["https://example.com"]
        );
        assert!(
            shared
                .origins_for(Some(("wiki.example.com", None)), "example.com")
                .is_empty()
        );
        assert!(
            shared
                .origins_for(Some(("evil.test", None)), "example.com")
                .is_empty()
        );
        let listed = PasskeyConfig {
            rp_id: Some("example.com".into()),
            origins: vec!["https://handy.example.com".into()],
            ..PasskeyConfig::default()
        };
        assert_eq!(
            listed.rp_id_for(Some("handy.example.com")).as_deref(),
            Some("example.com")
        );
        assert_eq!(listed.rp_id_for(Some("wiki.example.com")), None);
        // A proxy's other name gets the RP ID (only the list passes); an IP or no host none (A-N4).
        assert_eq!(
            listed.rp_id_for(Some("backend.internal.lan")).as_deref(),
            Some("example.com")
        );
        assert_eq!(listed.rp_id_for(None), None);
        assert_eq!(
            listed.origins_for(Some(("wiki.example.com", None)), "example.com"),
            ["https://handy.example.com"]
        );
    }

    /// D4.1/D4.2's start-up checks.
    #[test]
    fn the_passkey_settings_are_checked_at_start() {
        let config = |pairs: &[(&str, &str)]| {
            PasskeyConfig::from_vars(
                &pairs
                    .iter()
                    .map(|(k, v)| (k.to_string(), v.to_string()))
                    .collect(),
            )
        };
        assert_eq!(config(&[]).unwrap(), PasskeyConfig::default());
        assert_eq!(
            config(&[("PASSKEY_RP_ID", "handy.example.com")])
                .unwrap()
                .rp_id
                .as_deref(),
            Some("handy.example.com")
        );
        for bad in [
            "192.168.1.10",
            "::1",
            "com",
            "co.uk",
            "ts.net",
            "Handy.Example.com",
            "pi",
            "handy.example.com.",
            "https://handy.example.com",
        ] {
            assert!(config(&[("PASSKEY_RP_ID", bad)]).is_err(), "{bad}");
        }
        let origins = config(&[
            ("PASSKEY_RP_ID", "example.com"),
            (
                "PASSKEY_ORIGINS",
                "https://handy.example.com, https://example.com:8443",
            ),
        ])
        .unwrap();
        assert_eq!(
            origins.origins,
            ["https://handy.example.com", "https://example.com:8443"]
        );
        for bad in [
            "https://evil.test",
            "https://handy.example.com:443",
            "http://handy.example.com",
            "https://handy.example.com/path",
            "https://Handy.example.com",
            "handy.example.com",
        ] {
            assert!(
                config(&[("PASSKEY_RP_ID", "example.com"), ("PASSKEY_ORIGINS", bad)]).is_err(),
                "{bad}"
            );
        }
        assert!(config(&[("PASSKEY_ORIGINS", "https://handy.example.com")]).is_err());
        // http://localhost as an origin only with INSECURE_COOKIES.
        let local = [
            ("PASSKEY_RP_ID", "localhost"),
            ("PASSKEY_ORIGINS", "http://localhost:3100"),
        ];
        assert!(config(&local).is_err());
        assert!(config(&[local[0], local[1], ("INSECURE_COOKIES", "1")]).is_ok());
    }
}
