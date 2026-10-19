//! Web Push's two pieces of cryptography, on `ring` (design 35 §4.2), pure - keys passed in, no
//! I/O: the VAPID key and its JWT (RFC 8292: ES256, `aud` = the push service's origin), and the
//! message encryption (RFC 8291 over RFC 8188's `aes128gcm`, one record). Plus which push
//! services a subscription may name ([allowed], §4.5). The sender is `push`.

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD as B64;
use ring::rand::SecureRandom;
use ring::signature::{ECDSA_P256_SHA256_FIXED_SIGNING, EcdsaKeyPair, KeyPair};
use ring::{aead, agreement, hkdf};
use sha2::{Digest, Sha256};

/// The plaintext's cap (§4.3): Apple takes 4 KB of encrypted body; ours stays far under.
pub const PLAINTEXT_MAX: usize = 3072;
/// A JWT is valid this long (RFC 8292 allows at most 24 h).
pub const JWT_LIFETIME_SECS: i64 = 12 * 60 * 60;
/// The record size in the `aes128gcm` header (one record holds the whole message).
const RECORD_SIZE: u32 = 4096;

/// The server's VAPID key (`data/keys/vapid-p256.pk8`, `push::load_key`). Only its public half
/// ever leaves the server.
pub struct VapidKey {
    pair: EcdsaKeyPair,
    /// The public key, base64url of the 65-byte uncompressed point: the page's `data-key` and
    /// the `k=` of every request.
    pub public: String,
    /// The first 16 hex of SHA-256 of the public key (`push_subscriptions.vapid_fp`).
    pub fingerprint: String,
}

impl VapidKey {
    /// A new key as PKCS#8 DER, for the key file.
    pub fn generate_pkcs8() -> Vec<u8> {
        EcdsaKeyPair::generate_pkcs8(
            &ECDSA_P256_SHA256_FIXED_SIGNING,
            &ring::rand::SystemRandom::new(),
        )
        .expect("the system's random source works")
        .as_ref()
        .to_vec()
    }

    pub fn from_pkcs8(der: &[u8]) -> Option<VapidKey> {
        let pair = EcdsaKeyPair::from_pkcs8(
            &ECDSA_P256_SHA256_FIXED_SIGNING,
            der,
            &ring::rand::SystemRandom::new(),
        )
        .ok()?;
        let point = pair.public_key().as_ref();
        Some(VapidKey {
            public: B64.encode(point),
            fingerprint: hex::encode(&Sha256::digest(point)[..8]),
            pair,
        })
    }
}

impl std::fmt::Debug for VapidKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "VapidKey({})", self.fingerprint)
    }
}

/// The VAPID JWT for one push service (`aud`, "https://host") and contact (`sub`, a `mailto:` or
/// `https:` URL; none when the server has neither), issued at `iat` (Unix seconds): `exp` = `iat`
/// + [JWT_LIFETIME_SECS].
pub fn vapid_jwt(key: &VapidKey, aud: &str, sub: Option<&str>, iat: i64) -> String {
    let header = B64.encode(br#"{"typ":"JWT","alg":"ES256"}"#);
    let mut claims = serde_json::json!({ "aud": aud, "exp": iat + JWT_LIFETIME_SECS });
    if let Some(sub) = sub {
        claims["sub"] = sub.into();
    }
    let claims = B64.encode(claims.to_string());
    let signing_input = format!("{header}.{claims}");
    let signature = key
        .pair
        .sign(&ring::rand::SystemRandom::new(), signing_input.as_bytes())
        .expect("signing with a valid key can't fail");
    format!("{signing_input}.{}", B64.encode(signature.as_ref()))
}

/// The request's `Authorization` header (RFC 8292 §3).
pub fn authorization(jwt: &str, key: &VapidKey) -> String {
    format!("vapid t={jwt}, k={}", key.public)
}

/// A push service the server may send to (§4.5): `https`, no userinfo, port 443, a host name
/// (no IP literal) that is FCM's (`fcm.googleapis.com`, and `jmt17.google.com` from a Chromium
/// built without Google's API keys - Debian's, Raspberry Pi OS's; 2026-10-19), Mozilla's, or under
/// Apple's or Windows' push domains (a label boundary: `push.apple.com.example.net` is not).
/// Checked at subscribe and before every send.
pub fn allowed(endpoint: &str) -> bool {
    let Ok(url) = reqwest::Url::parse(endpoint) else {
        return false;
    };
    // `domain()` is `None` for an IP literal.
    let Some(host) = url.domain() else {
        return false;
    };
    url.scheme() == "https"
        && url.username().is_empty()
        && url.password().is_none()
        && url.port().is_none()
        && (host == "fcm.googleapis.com"
            || host == "jmt17.google.com"
            || host == "updates.push.services.mozilla.com"
            || host.ends_with(".push.apple.com")
            || host.ends_with(".notify.windows.com"))
}

/// The JWT's `aud` for an [allowed] endpoint: "https://host".
pub fn aud_of(endpoint: &str) -> Option<String> {
    let url = reqwest::Url::parse(endpoint).ok()?;
    Some(format!("https://{}", url.host_str()?))
}

/// The endpoint's host, for log lines (never the path: it is the subscription's secret name).
pub fn host_of(endpoint: &str) -> String {
    reqwest::Url::parse(endpoint)
        .ok()
        .and_then(|url| url.host_str().map(str::to_string))
        .unwrap_or_default()
}

#[derive(Debug, PartialEq, Eq)]
pub enum EncryptError {
    TooLarge,
    /// The browser's key isn't a P-256 point (checked at subscribe, so a bug or a changed row).
    BadKey,
}

/// `plaintext` encrypted for the browser whose subscription holds `ua_public` (65 bytes) and
/// `auth` (16 bytes): RFC 8291 §3.4 with a fresh ephemeral key and salt from `rng`, as one
/// RFC 8188 `aes128gcm` record - salt (16) || rs = 4096 (u32 BE) || idlen 65 || as_public, then
/// the ciphertext of `plaintext || 0x02`.
///
/// The draws from `rng` are in this order (a test's fixed sequence depends on it): the ephemeral
/// key's 32 bytes first, then the 16-byte salt.
pub fn encrypt(
    rng: &dyn SecureRandom,
    ua_public: &[u8],
    auth: &[u8],
    plaintext: &[u8],
) -> Result<Vec<u8>, EncryptError> {
    if plaintext.len() > PLAINTEXT_MAX {
        return Err(EncryptError::TooLarge);
    }
    let private = agreement::EphemeralPrivateKey::generate(&agreement::ECDH_P256, rng)
        .map_err(|_| EncryptError::BadKey)?;
    let as_public = private
        .compute_public_key()
        .map_err(|_| EncryptError::BadKey)?;
    let mut salt = [0u8; 16];
    rng.fill(&mut salt).map_err(|_| EncryptError::BadKey)?;
    let (cek, nonce) = agreement::agree_ephemeral(
        private,
        &agreement::UnparsedPublicKey::new(&agreement::ECDH_P256, ua_public),
        |secret| content_keys(secret, auth, ua_public, as_public.as_ref(), &salt),
    )
    .map_err(|_| EncryptError::BadKey)?;
    let mut body = Vec::with_capacity(86 + plaintext.len() + 17);
    body.extend_from_slice(&salt);
    body.extend_from_slice(&RECORD_SIZE.to_be_bytes());
    body.push(as_public.as_ref().len() as u8);
    body.extend_from_slice(as_public.as_ref());
    let mut record = plaintext.to_vec();
    record.push(0x02);
    aead::LessSafeKey::new(aead::UnboundKey::new(&aead::AES_128_GCM, &cek).expect("16 bytes"))
        .seal_in_place_append_tag(
            aead::Nonce::assume_unique_for_key(nonce),
            aead::Aad::empty(),
            &mut record,
        )
        .expect("a short record seals");
    body.extend_from_slice(&record);
    Ok(body)
}

/// A browser's `p256dh` (65 bytes) is a P-256 point: one throwaway agreement (§5.2).
pub fn valid_browser_key(ua_public: &[u8]) -> bool {
    ua_public.len() == 65
        && agreement::EphemeralPrivateKey::generate(
            &agreement::ECDH_P256,
            &ring::rand::SystemRandom::new(),
        )
        .ok()
        .and_then(|private| {
            agreement::agree_ephemeral(
                private,
                &agreement::UnparsedPublicKey::new(&agreement::ECDH_P256, ua_public),
                |_| (),
            )
            .ok()
        })
        .is_some()
}

/// RFC 8291 §3.3-3.4 from the ECDH secret: the IKM, then the CEK and the nonce.
fn content_keys(
    ecdh_secret: &[u8],
    auth: &[u8],
    ua_public: &[u8],
    as_public: &[u8],
    salt: &[u8],
) -> ([u8; 16], [u8; 12]) {
    let ikm: [u8; 32] = expand(
        &hkdf::Salt::new(hkdf::HKDF_SHA256, auth).extract(ecdh_secret),
        &[b"WebPush: info\0", ua_public, as_public],
    );
    let prk = hkdf::Salt::new(hkdf::HKDF_SHA256, salt).extract(&ikm);
    (
        expand(&prk, &[b"Content-Encoding: aes128gcm\0"]),
        expand(&prk, &[b"Content-Encoding: nonce\0"]),
    )
}

/// HKDF-Expand to N bytes.
fn expand<const N: usize>(prk: &hkdf::Prk, info: &[&[u8]]) -> [u8; N] {
    struct Len(usize);
    impl hkdf::KeyType for Len {
        fn len(&self) -> usize {
            self.0
        }
    }
    let mut out = [0u8; N];
    prk.expand(info, Len(N))
        .and_then(|okm| okm.fill(&mut out))
        .expect("HKDF-SHA256 gives up to 8160 bytes");
    out
}

/// The fake browser of the tests (design 35 §4.2): `ring` has no ECDH with an imported static
/// key and its `SecureRandom` is sealed, so a fixed key goes through its deprecated test random
/// (`FixedSliceSequenceRandom`; if a `ring` bump removes it, only these helpers move to `p256` as
/// a dev-dependency). The only `#[allow(deprecated)]` in the tree.
#[cfg(test)]
#[allow(deprecated)]
pub mod test_browser {
    use super::*;

    /// A random source that hands out `slices` in order, each exactly once (asserted on drop).
    pub fn fixed_rng<'a>(slices: &'a [&'a [u8]]) -> ring::test::rand::FixedSliceSequenceRandom<'a> {
        ring::test::rand::FixedSliceSequenceRandom {
            bytes: slices,
            current: core::cell::UnsafeCell::new(0),
        }
    }

    /// RFC 8291 Appendix A's user agent: its keys and auth secret (public test values).
    pub const UA_PRIVATE: &str = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    pub const UA_PUBLIC: &str =
        "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    pub const AUTH: &str = "BTBZMqHH6r4Tts7J_aSIgg";

    pub fn bytes(b64: &str) -> Vec<u8> {
        B64.decode(b64).unwrap()
    }

    /// The plaintext of an `aes128gcm` body sent to [UA_PUBLIC], as the browser decrypts it.
    pub fn decrypt(body: &[u8]) -> Option<Vec<u8>> {
        let salt = body.get(..16)?;
        let idlen = *body.get(20)? as usize;
        let as_public = body.get(21..21 + idlen)?;
        let ua_private = bytes(UA_PRIVATE);
        let slices = [&ua_private[..]];
        let rng = fixed_rng(&slices);
        let private = agreement::EphemeralPrivateKey::generate(&agreement::ECDH_P256, &rng).ok()?;
        let (cek, nonce) = agreement::agree_ephemeral(
            private,
            &agreement::UnparsedPublicKey::new(&agreement::ECDH_P256, as_public),
            |secret| content_keys(secret, &bytes(AUTH), &bytes(UA_PUBLIC), as_public, salt),
        )
        .ok()?;
        let mut record = body.get(21 + idlen..)?.to_vec();
        let plain = aead::LessSafeKey::new(aead::UnboundKey::new(&aead::AES_128_GCM, &cek).ok()?)
            .open_in_place(
                aead::Nonce::assume_unique_for_key(nonce),
                aead::Aad::empty(),
                &mut record,
            )
            .ok()?;
        // The last record: the delimiter 0x02, after any zero padding.
        let end = plain.iter().rposition(|b| *b != 0)?;
        (plain[end] == 0x02).then(|| plain[..end].to_vec())
    }
}

#[cfg(test)]
#[allow(deprecated)]
mod tests {
    use super::test_browser::*;
    use super::*;

    // RFC 8291 Appendix A.
    const PLAINTEXT: &str = "V2hlbiBJIGdyb3cgdXAsIEkgd2FudCB0byBiZSBhIHdhdGVybWVsb24";
    const AS_PRIVATE: &str = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    const AS_PUBLIC: &str =
        "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    const SALT: &str = "DGv6ra1nlYgDCS1FRnbzlw";
    const ECDH_SECRET: &str = "kyrL1jIIOHEzg3sM2ZWRHDRB62YACZhhSlknJ672kSs";
    const IKM: &str = "S4lYMb_L0FxCeq0WhDx813KgSYqU26kOyzWUdsXYyrg";
    const CEK: &str = "oIhVW04MRdy2XN9CiKLxTg";
    const NONCE: &str = "4h_95klXJ5E_qnoN";
    const HEADER: &str = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    const CIPHERTEXT: &str =
        "8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEsbI_0LpXMuGvnzQ";

    #[test]
    fn rfc_8291_appendix_a() {
        // The ECDH secret, from the server's side.
        let as_private = bytes(AS_PRIVATE);
        let slices = [&as_private[..]];
        let rng = fixed_rng(&slices);
        let private =
            agreement::EphemeralPrivateKey::generate(&agreement::ECDH_P256, &rng).unwrap();
        assert_eq!(B64.encode(private.compute_public_key().unwrap()), AS_PUBLIC);
        let secret = agreement::agree_ephemeral(
            private,
            &agreement::UnparsedPublicKey::new(&agreement::ECDH_P256, bytes(UA_PUBLIC)),
            |s| s.to_vec(),
        )
        .unwrap();
        assert_eq!(B64.encode(&secret), ECDH_SECRET);
        // The IKM (its own expand), the CEK and the nonce.
        let ikm: [u8; 32] = expand(
            &hkdf::Salt::new(hkdf::HKDF_SHA256, &bytes(AUTH)).extract(&secret),
            &[b"WebPush: info\0", &bytes(UA_PUBLIC), &bytes(AS_PUBLIC)],
        );
        assert_eq!(B64.encode(ikm), IKM);
        let (cek, nonce) = content_keys(
            &secret,
            &bytes(AUTH),
            &bytes(UA_PUBLIC),
            &bytes(AS_PUBLIC),
            &bytes(SALT),
        );
        assert_eq!(
            (B64.encode(cek), B64.encode(nonce)),
            (CEK.into(), NONCE.into())
        );
        // The whole body, end to end: the ephemeral key first, then the salt.
        let salt = bytes(SALT);
        let slices = [&as_private[..], &salt[..]];
        let rng = fixed_rng(&slices);
        let body = encrypt(&rng, &bytes(UA_PUBLIC), &bytes(AUTH), &bytes(PLAINTEXT)).unwrap();
        assert_eq!(B64.encode(&body[..86]), HEADER);
        assert_eq!(B64.encode(&body[86..]), CIPHERTEXT);
        // The fake browser reads it back (the 0x02 delimiter stripped).
        assert_eq!(decrypt(&body).unwrap(), bytes(PLAINTEXT));
    }

    /// Its own test (qa-35-s2-code N1): a fixed salt or key fails here, not only inside
    /// Appendix A, whose fixed random aborts the binary on a failure.
    #[test]
    fn a_fresh_salt_and_key_per_message() {
        let system = ring::rand::SystemRandom::new();
        let one = encrypt(&system, &bytes(UA_PUBLIC), &bytes(AUTH), b"{}").unwrap();
        let two = encrypt(&system, &bytes(UA_PUBLIC), &bytes(AUTH), b"{}").unwrap();
        assert_ne!(one[..16], two[..16], "a fresh salt each time");
        assert_ne!(one[21..86], two[21..86], "a fresh ephemeral key each time");
        assert_eq!(decrypt(&two).unwrap(), b"{}");
    }

    #[test]
    fn the_cap_and_bad_keys() {
        let system = ring::rand::SystemRandom::new();
        let full = vec![b'x'; PLAINTEXT_MAX];
        let body = encrypt(&system, &bytes(UA_PUBLIC), &bytes(AUTH), &full).unwrap();
        assert_eq!(decrypt(&body).unwrap(), full);
        assert!(body.len() <= 4096);
        assert_eq!(
            encrypt(
                &system,
                &bytes(UA_PUBLIC),
                &bytes(AUTH),
                &[b'x'; PLAINTEXT_MAX + 1]
            ),
            Err(EncryptError::TooLarge)
        );
        let mut off_curve = bytes(UA_PUBLIC);
        off_curve[64] ^= 1;
        assert_eq!(
            encrypt(&system, &off_curve, &bytes(AUTH), b"{}"),
            Err(EncryptError::BadKey)
        );
        assert!(valid_browser_key(&bytes(UA_PUBLIC)));
        assert!(!valid_browser_key(&off_curve));
        assert!(!valid_browser_key(&bytes(UA_PUBLIC)[..33]));
    }

    #[test]
    fn the_jwt_and_its_header() {
        let key = VapidKey::from_pkcs8(&VapidKey::generate_pkcs8()).unwrap();
        assert_eq!(bytes(&key.public).len(), 65);
        assert_eq!(key.fingerprint.len(), 16);
        let jwt = vapid_jwt(
            &key,
            "https://web.push.apple.com",
            Some("mailto:parent@example.org"),
            1000,
        );
        let parts: Vec<&str> = jwt.split('.').collect();
        assert_eq!(parts.len(), 3);
        let json =
            |part: &str| -> serde_json::Value { serde_json::from_slice(&bytes(part)).unwrap() };
        assert_eq!(
            json(parts[0]),
            serde_json::json!({"typ": "JWT", "alg": "ES256"})
        );
        assert_eq!(
            json(parts[1]),
            serde_json::json!({
                "aud": "https://web.push.apple.com",
                "exp": 1000 + 12 * 3600,
                "sub": "mailto:parent@example.org"
            })
        );
        // The fixed r||s signature verifies with the public key (as the push service checks it).
        ring::signature::UnparsedPublicKey::new(
            &ring::signature::ECDSA_P256_SHA256_FIXED,
            bytes(&key.public),
        )
        .verify(
            format!("{}.{}", parts[0], parts[1]).as_bytes(),
            &bytes(parts[2]),
        )
        .unwrap();
        assert_eq!(
            authorization(&jwt, &key),
            format!("vapid t={jwt}, k={}", key.public)
        );
        assert!(VapidKey::from_pkcs8(b"not a key").is_none());
    }

    #[test]
    fn only_the_four_push_services() {
        for good in [
            "https://fcm.googleapis.com/fcm/send/abc",
            "https://jmt17.google.com/fcm/send/abc",
            "https://updates.push.services.mozilla.com/wpush/v2/abc",
            "https://web.push.apple.com/QK1",
            "https://wns2-db5p.notify.windows.com/w/?token=abc",
            "https://web.push.apple.com:443/QK1",
        ] {
            assert!(allowed(good), "{good}");
        }
        assert_eq!(
            aud_of("https://web.push.apple.com:443/QK1").unwrap(),
            "https://web.push.apple.com"
        );
        assert_eq!(
            aud_of("https://fcm.googleapis.com/fcm/send/abc").unwrap(),
            "https://fcm.googleapis.com"
        );
        for bad in [
            "https://push.apple.com.example.net/x",
            "https://push.apple.com/x",
            "http://fcm.googleapis.com/fcm/send/abc",
            "https://192.168.1.1/x",
            "https://[::1]/x",
            "https://fcm.googleapis.com:8443/x",
            "https://user:pw@fcm.googleapis.com/x",
            "https://evilfcm.googleapis.com/x",
            "https://jmt17.google.com.example.net/x",
            "https://jmt18.google.com/x",
            "not a url",
        ] {
            assert!(!allowed(bad), "{bad}");
        }
    }
}
