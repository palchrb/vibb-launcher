//! The parts of an X.509 certificate the attestation check reads (design 129 §3): the signed bytes and their
//! signature, the serial, the names, the validity, the public key and the extensions. Parsing only - every judgement
//! is `attestation::verify`'s.

use super::der::{Tag, Tlv, parse_one};

pub const OID_RSA_ENCRYPTION: &[u8] = &[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01];
pub const OID_SHA256_WITH_RSA: &[u8] = &[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x0b];
pub const OID_EC_PUBLIC_KEY: &[u8] = &[0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01];
pub const OID_ECDSA_SHA256: &[u8] = &[0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x03, 0x02];
pub const OID_ECDSA_SHA384: &[u8] = &[0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x03, 0x03];
pub const OID_P256: &[u8] = &[0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07];
pub const OID_P384: &[u8] = &[0x2b, 0x81, 0x04, 0x00, 0x22];
/// The KeyDescription, `1.3.6.1.4.1.11129.2.1.17`.
pub const OID_KEY_DESCRIPTION: &[u8] =
    &[0x2b, 0x06, 0x01, 0x04, 0x01, 0xd6, 0x79, 0x02, 0x01, 0x11];
/// The remote provisioning info, `1.3.6.1.4.1.11129.2.1.30`.
pub const OID_PROVISIONING_INFO: &[u8] =
    &[0x2b, 0x06, 0x01, 0x04, 0x01, 0xd6, 0x79, 0x02, 0x01, 0x1e];
/// `serialNumber` (2.5.4.5) in a name: a factory intermediate has it.
pub const OID_SERIAL_NUMBER_ATTR: &[u8] = &[0x55, 0x04, 0x05];

/// A public key's kind, as the chain's signatures need it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum KeyKind {
    Rsa,
    P256,
    P384,
    Other,
}

#[derive(Clone, Debug)]
pub struct Extension<'a> {
    pub oid: &'a [u8],
    pub value: &'a [u8],
}

#[derive(Clone, Debug)]
pub struct Certificate<'a> {
    /// The tbsCertificate's whole encoding: what the issuer signed.
    pub tbs: &'a [u8],
    pub signature_alg: &'a [u8],
    pub signature: &'a [u8],
    /// The serial's INTEGER content (a leading 0x00 kept).
    pub serial: &'a [u8],
    pub subject: &'a [u8],
    pub not_before: i64,
    pub not_after: i64,
    /// The SubjectPublicKeyInfo's whole encoding (what the pins and the stored key hash).
    pub spki: &'a [u8],
    pub key_kind: KeyKind,
    /// The key itself: an RSAPublicKey's DER, or an EC point (uncompressed).
    pub key: &'a [u8],
    pub extensions: Vec<Extension<'a>>,
}

impl<'a> Certificate<'a> {
    pub fn parse(der: &'a [u8]) -> Option<Certificate<'a>> {
        let cert = parse_one(der, Tag::SEQUENCE)?;
        let mut outer = cert.reader();
        let tbs = outer.expect(Tag::SEQUENCE)?;
        let signature_alg = algorithm(&outer.expect(Tag::SEQUENCE)?)?.0;
        let signature = bit_string(&outer.expect(Tag::BIT_STRING)?)?;
        if !outer.is_empty() {
            return None;
        }

        let mut t = tbs.reader();
        if let Some(version) = t.optional(Tag::explicit(0)) {
            // v3 (2) is what every attestation certificate is; v1 has no extensions to read.
            let v = parse_one(version.content, Tag::INTEGER)?.int()?;
            if !(0..=2).contains(&v) {
                return None;
            }
        }
        let serial = t.expect(Tag::INTEGER)?.content;
        let inner_alg = algorithm(&t.expect(Tag::SEQUENCE)?)?.0;
        if inner_alg != signature_alg {
            return None;
        }
        // The issuer: read past, never compared (the signatures bind the chain).
        t.expect(Tag::SEQUENCE)?;
        let validity = t.expect(Tag::SEQUENCE)?;
        let mut v = validity.reader();
        let not_before = time(&v.next()?)?;
        let not_after = time(&v.next()?)?;
        let subject = t.expect(Tag::SEQUENCE)?.raw;
        let spki_tlv = t.expect(Tag::SEQUENCE)?;
        let (key_kind, key) = public_key(&spki_tlv)?;
        let mut extensions = Vec::new();
        while let Some(next) = t.next() {
            match next.tag {
                Tag {
                    class: super::der::Class::Context,
                    number: 1 | 2,
                    ..
                } => {}
                tag if tag == Tag::explicit(3) => {
                    let list = parse_one(next.content, Tag::SEQUENCE)?;
                    let mut l = list.reader();
                    while !l.is_empty() {
                        let ext = l.expect(Tag::SEQUENCE)?;
                        let mut e = ext.reader();
                        let oid = e.expect(Tag::OID)?.content;
                        // `critical` is read past: every extension the check needs is read, the rest ignored.
                        if let Some(b) = e.optional(Tag::BOOLEAN) {
                            b.boolean()?;
                        }
                        let value = e.expect(Tag::OCTET_STRING)?.content;
                        if !e.is_empty() {
                            return None;
                        }
                        // An extension named twice is never a certificate we take (RFC 5280 §4.2).
                        if extensions.iter().any(|x: &Extension| x.oid == oid) {
                            return None;
                        }
                        extensions.push(Extension { oid, value });
                    }
                }
                _ => return None,
            }
        }
        Some(Certificate {
            tbs: tbs.raw,
            signature_alg,
            signature,
            serial,
            subject,
            not_before,
            not_after,
            spki: spki_tlv.raw,
            key_kind,
            key,
            extensions,
        })
    }

    pub fn extension(&self, oid: &[u8]) -> Option<&Extension<'a>> {
        self.extensions.iter().find(|e| e.oid == oid)
    }

    /// The serial as Google's status list writes it: lowercase hex, no leading zeros.
    pub fn serial_hex(&self) -> String {
        let hex = hex::encode(self.serial);
        let trimmed = hex.trim_start_matches('0');
        if trimmed.is_empty() {
            "0".to_string()
        } else {
            trimmed.to_string()
        }
    }

    /// Whether the subject names `oid` (with any value).
    pub fn subject_has(&self, oid: &[u8]) -> bool {
        name_values(self.subject).iter().any(|(o, _)| *o == oid)
    }
}

/// Every attribute of a Name, in order: (OID, the value's content).
fn name_values(name: &[u8]) -> Vec<(&[u8], &[u8])> {
    let mut out = Vec::new();
    let Some(seq) = parse_one(name, Tag::SEQUENCE) else {
        return out;
    };
    let mut rdns = seq.reader();
    while let Some(rdn) = rdns.next() {
        let mut atvs = rdn.reader();
        while let Some(atv) = atvs.next() {
            let mut a = atv.reader();
            if let (Some(oid), Some(value)) = (a.expect(Tag::OID), a.next()) {
                out.push((oid.content, value.content));
            }
        }
    }
    out
}

/// An AlgorithmIdentifier: its OID and its parameters' whole encoding (if any).
fn algorithm<'a>(seq: &Tlv<'a>) -> Option<(&'a [u8], Option<Tlv<'a>>)> {
    let mut r = seq.reader();
    let oid = r.expect(Tag::OID)?.content;
    let params = r.next();
    if !r.is_empty() {
        return None;
    }
    Some((oid, params))
}

/// A BIT STRING's bytes, whole bytes only.
fn bit_string<'a>(tlv: &Tlv<'a>) -> Option<&'a [u8]> {
    let (&unused, bytes) = tlv.content.split_first()?;
    (unused == 0).then_some(bytes)
}

fn public_key<'a>(spki: &Tlv<'a>) -> Option<(KeyKind, &'a [u8])> {
    let mut r = spki.reader();
    let (alg, params) = algorithm(&r.expect(Tag::SEQUENCE)?)?;
    let key = bit_string(&r.expect(Tag::BIT_STRING)?)?;
    if !r.is_empty() {
        return None;
    }
    let kind = if alg == OID_RSA_ENCRYPTION {
        KeyKind::Rsa
    } else if alg == OID_EC_PUBLIC_KEY {
        match params {
            Some(p) if p.tag == Tag::OID && p.content == OID_P256 => KeyKind::P256,
            Some(p) if p.tag == Tag::OID && p.content == OID_P384 => KeyKind::P384,
            _ => KeyKind::Other,
        }
    } else {
        KeyKind::Other
    };
    Some((kind, key))
}

/// UTCTime (YYMMDDHHMMSSZ, a year below 50 is 20YY) or GeneralizedTime (YYYYMMDDHHMMSSZ), to Unix seconds.
fn time(tlv: &Tlv) -> Option<i64> {
    // ASCII digits only, before any slicing by byte (code QA M3: a multi-byte character would panic).
    if !tlv.content.is_ascii() {
        return None;
    }
    let text = std::str::from_utf8(tlv.content).ok()?;
    let text = text.strip_suffix('Z')?;
    let full = if tlv.tag == Tag::UTC_TIME {
        if text.len() != 12 {
            return None;
        }
        let yy: i32 = text[..2].parse().ok()?;
        format!(
            "{}{}",
            if yy < 50 { 2000 + yy } else { 1900 + yy },
            &text[2..]
        )
    } else if tlv.tag == Tag::GENERALIZED_TIME {
        if text.len() != 14 {
            return None;
        }
        text.to_string()
    } else {
        return None;
    };
    if !full.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    chrono::NaiveDateTime::parse_from_str(&full, "%Y%m%d%H%M%S")
        .ok()
        .map(|t| t.and_utc().timestamp())
}

/// Verifies `cert`'s signature with `issuer`'s key, for every algorithm pair the attestation chains use (L1): RSA
/// PKCS#1 with SHA-256; ECDSA P-256 and P-384 with SHA-256 or SHA-384. Anything else is `false`.
pub fn signed_by(cert: &Certificate, issuer: &Certificate) -> bool {
    use ring::signature as s;
    let alg: &dyn s::VerificationAlgorithm = match (cert.signature_alg, issuer.key_kind) {
        (OID_SHA256_WITH_RSA, KeyKind::Rsa) => &s::RSA_PKCS1_2048_8192_SHA256,
        (OID_ECDSA_SHA256, KeyKind::P256) => &s::ECDSA_P256_SHA256_ASN1,
        (OID_ECDSA_SHA384, KeyKind::P256) => &s::ECDSA_P256_SHA384_ASN1,
        (OID_ECDSA_SHA256, KeyKind::P384) => &s::ECDSA_P384_SHA256_ASN1,
        (OID_ECDSA_SHA384, KeyKind::P384) => &s::ECDSA_P384_SHA384_ASN1,
        _ => return false,
    };
    s::UnparsedPublicKey::new(alg, issuer.key)
        .verify(cert.tbs, cert.signature)
        .is_ok()
}

/// A PEM file's certificates, in order (tests and the roots' pins).
#[cfg(test)]
pub fn pem_certificates(pem: &str) -> Vec<Vec<u8>> {
    use base64::Engine;
    let mut out = Vec::new();
    let mut current: Option<String> = None;
    for line in pem.lines() {
        let line = line.trim();
        if line == "-----BEGIN CERTIFICATE-----" {
            current = Some(String::new());
        } else if line == "-----END CERTIFICATE-----" {
            if let Some(b64) = current.take() {
                out.push(
                    base64::engine::general_purpose::STANDARD
                        .decode(b64)
                        .expect("PEM base64"),
                );
            }
        } else if let Some(b64) = current.as_mut() {
            b64.push_str(line);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tlv(tag: Tag, content: &[u8]) -> Vec<u8> {
        let mut out = vec![
            if tag == Tag::UTC_TIME { 0x17 } else { 0x18 },
            content.len() as u8,
        ];
        out.extend_from_slice(content);
        out
    }

    /// Code QA M3: a time that isn't ASCII digits is no time - never a panic (reachable unauthenticated through an
    /// enrolment's chain).
    #[test]
    fn malformed_times_are_refused_not_panics() {
        let read = |tag: Tag, text: &[u8]| {
            let raw = tlv(tag, text);
            time(&parse_one(&raw, tag).unwrap())
        };
        assert_eq!(read(Tag::UTC_TIME, b"260101000000Z"), Some(1_767_225_600));
        assert_eq!(
            read(Tag::GENERALIZED_TIME, b"20260101000000Z"),
            Some(1_767_225_600)
        );
        let bad: [&[u8]; 10] = [
            "Ø60101000000Z".as_bytes(),
            "2Ø0101000000Z".as_bytes(),
            "26010100000ØZ".as_bytes(),
            b"26010100000Z",
            b"2601010000000",
            b"2601010000+0Z",
            b"261301000000Z",
            b"",
            b"Z",
            &[0xff; 13],
        ];
        for text in bad {
            assert_eq!(read(Tag::UTC_TIME, text), None, "{text:?}");
            assert_eq!(read(Tag::GENERALIZED_TIME, text), None, "{text:?}");
        }
        assert_eq!(
            read(Tag::GENERALIZED_TIME, "2026010100000Ø".as_bytes()),
            None
        );
    }

    /// Every single-byte change of a real chain's certificates parses or is refused - never a panic.
    #[test]
    fn a_changed_byte_never_panics() {
        let pem =
            std::fs::read_to_string("testdata/attestation/chains/caiman-sdk36-TEE_EC_RKP.pem")
                .unwrap();
        for der in pem_certificates(&pem) {
            for at in 0..der.len() {
                for value in [0x00, 0x80, 0xff, der[at] ^ 0x01] {
                    let mut changed = der.clone();
                    changed[at] = value;
                    if let Some(cert) = Certificate::parse(&changed)
                        && let Some(ext) = cert.extension(OID_KEY_DESCRIPTION)
                    {
                        let _ = super::super::key_description::KeyDescription::parse(ext.value);
                    }
                }
            }
        }
    }
}
