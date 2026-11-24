//! A small DER reader (design 129 §3): just what the attestation chain needs - a tag (with the high tag numbers the
//! KeyDescription's `[709]` uses), a definite length, the content. No new crate: every reading here is the server's
//! own and fails closed (`None`) on anything it doesn't expect.

/// A tag's class (the top two bits of its first byte).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Class {
    Universal,
    Application,
    Context,
    Private,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Tag {
    pub class: Class,
    pub constructed: bool,
    pub number: u32,
}

impl Tag {
    pub const BOOLEAN: Tag = Tag::universal(1, false);
    pub const INTEGER: Tag = Tag::universal(2, false);
    pub const BIT_STRING: Tag = Tag::universal(3, false);
    pub const OCTET_STRING: Tag = Tag::universal(4, false);
    pub const NULL: Tag = Tag::universal(5, false);
    pub const OID: Tag = Tag::universal(6, false);
    pub const ENUMERATED: Tag = Tag::universal(10, false);
    pub const UTC_TIME: Tag = Tag::universal(23, false);
    pub const GENERALIZED_TIME: Tag = Tag::universal(24, false);
    pub const SEQUENCE: Tag = Tag::universal(16, true);
    pub const SET: Tag = Tag::universal(17, true);

    const fn universal(number: u32, constructed: bool) -> Tag {
        Tag {
            class: Class::Universal,
            constructed,
            number,
        }
    }

    /// `[n]` EXPLICIT: context-specific and constructed.
    pub const fn explicit(number: u32) -> Tag {
        Tag {
            class: Class::Context,
            constructed: true,
            number,
        }
    }
}

/// One element: its tag, its content and the whole encoding (tag and length included).
#[derive(Clone, Copy, Debug)]
pub struct Tlv<'a> {
    pub tag: Tag,
    pub content: &'a [u8],
    pub raw: &'a [u8],
}

impl<'a> Tlv<'a> {
    /// The content as a reader of elements (a SEQUENCE's, a SET's, an EXPLICIT tag's).
    pub fn reader(&self) -> Reader<'a> {
        Reader::new(self.content)
    }

    /// An INTEGER's value as an i64 (at most 8 content bytes, two's complement).
    pub fn int(&self) -> Option<i64> {
        int_of(self.content)
    }

    /// A BOOLEAN: DER's 0x00 or 0xFF only (code QA L1); any other byte is no BOOLEAN.
    pub fn boolean(&self) -> Option<bool> {
        match (self.tag == Tag::BOOLEAN, self.content) {
            (true, [0x00]) => Some(false),
            (true, [0xff]) => Some(true),
            _ => None,
        }
    }
}

/// A two's-complement INTEGER or ENUMERATED content of at most 8 bytes.
pub fn int_of(content: &[u8]) -> Option<i64> {
    if content.is_empty() || content.len() > 8 {
        return None;
    }
    let mut value: i64 = if content[0] & 0x80 != 0 { -1 } else { 0 };
    for byte in content {
        value = (value << 8) | i64::from(*byte);
    }
    Some(value)
}

/// Reads elements one after another from a slice.
#[derive(Clone, Debug)]
pub struct Reader<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    pub fn new(data: &'a [u8]) -> Reader<'a> {
        Reader { data, pos: 0 }
    }

    pub fn is_empty(&self) -> bool {
        self.pos >= self.data.len()
    }

    /// The next element without taking it.
    pub fn peek(&self) -> Option<Tlv<'a>> {
        self.clone().next()
    }

    /// The next element, whatever its tag.
    #[allow(clippy::should_implement_trait)]
    pub fn next(&mut self) -> Option<Tlv<'a>> {
        let start = self.pos;
        let data = self.data;
        let first = *data.get(self.pos)?;
        self.pos += 1;
        let class = match first >> 6 {
            0 => Class::Universal,
            1 => Class::Application,
            2 => Class::Context,
            _ => Class::Private,
        };
        let constructed = first & 0x20 != 0;
        let mut number = u32::from(first & 0x1f);
        if number == 0x1f {
            // High tag number: base-128, at most 4 bytes (a tag past 2^28 is never ours).
            number = 0;
            for i in 0.. {
                if i == 4 {
                    return None;
                }
                let byte = *data.get(self.pos)?;
                self.pos += 1;
                number = (number << 7) | u32::from(byte & 0x7f);
                if byte & 0x80 == 0 {
                    break;
                }
            }
        }
        let first_len = *data.get(self.pos)?;
        self.pos += 1;
        let len = if first_len & 0x80 == 0 {
            usize::from(first_len)
        } else {
            let count = usize::from(first_len & 0x7f);
            // 0x80 is the indefinite form, never DER; more than 4 length bytes is never a certificate.
            if count == 0 || count > 4 {
                return None;
            }
            let mut len = 0usize;
            for _ in 0..count {
                len = (len << 8) | usize::from(*data.get(self.pos)?);
                self.pos += 1;
            }
            len
        };
        let end = self.pos.checked_add(len)?;
        let content = data.get(self.pos..end)?;
        self.pos = end;
        Some(Tlv {
            tag: Tag {
                class,
                constructed,
                number,
            },
            content,
            raw: &data[start..end],
        })
    }

    /// The next element, which must carry `tag`.
    pub fn expect(&mut self, tag: Tag) -> Option<Tlv<'a>> {
        let tlv = self.next()?;
        (tlv.tag == tag).then_some(tlv)
    }

    /// The next element if it carries `tag`; nothing is taken otherwise.
    pub fn optional(&mut self, tag: Tag) -> Option<Tlv<'a>> {
        if self.peek()?.tag == tag {
            self.next()
        } else {
            None
        }
    }
}

/// One whole element that fills `data` exactly.
pub fn parse_one(data: &[u8], tag: Tag) -> Option<Tlv<'_>> {
    let mut reader = Reader::new(data);
    let tlv = reader.expect(tag)?;
    reader.is_empty().then_some(tlv)
}

/// An OID's dotted form, for messages and comparisons in tests.
pub fn oid_string(content: &[u8]) -> Option<String> {
    let (&first, rest) = content.split_first()?;
    let mut parts = vec![u64::from(first / 40).min(2), 0];
    parts[1] = u64::from(first) - parts[0] * 40;
    let mut value: u64 = 0;
    for byte in rest {
        value = value.checked_mul(128)? | u64::from(byte & 0x7f);
        if byte & 0x80 == 0 {
            parts.push(value);
            value = 0;
        }
    }
    Some(
        parts
            .iter()
            .map(u64::to_string)
            .collect::<Vec<_>>()
            .join("."),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_short_long_and_high_tags() {
        // [709] EXPLICIT { OCTET STRING "ab" }: 0xBF 0x85 0x45, length 4.
        let data = [0xbf, 0x85, 0x45, 0x04, 0x04, 0x02, b'a', b'b'];
        let tlv = parse_one(&data, Tag::explicit(709)).unwrap();
        let inner = tlv.reader().expect(Tag::OCTET_STRING).unwrap();
        assert_eq!(inner.content, b"ab");

        let mut long = vec![0x04, 0x81, 200];
        long.extend(std::iter::repeat_n(7u8, 200));
        assert_eq!(
            parse_one(&long, Tag::OCTET_STRING).unwrap().content.len(),
            200
        );
    }

    #[test]
    fn refuses_what_der_never_is() {
        assert!(
            parse_one(&[0x30, 0x80, 0, 0], Tag::SEQUENCE).is_none(),
            "indefinite"
        );
        assert!(
            parse_one(&[0x04, 0x05, 1, 2], Tag::OCTET_STRING).is_none(),
            "short"
        );
        assert!(
            parse_one(&[0x04, 0x01, 1, 2], Tag::OCTET_STRING).is_none(),
            "trailing"
        );
        assert!(
            parse_one(&[0x04, 0x85, 0, 0, 0, 0, 1], Tag::OCTET_STRING).is_none(),
            "five length bytes"
        );
    }

    #[test]
    fn integers_and_oids() {
        assert_eq!(int_of(&[0x01, 0x2c]), Some(300));
        assert_eq!(int_of(&[0xff]), Some(-1));
        assert_eq!(int_of(&[0x00, 0x80]), Some(128));
        assert_eq!(int_of(&[0; 9]), None);
        assert_eq!(
            oid_string(&[0x2b, 0x06, 0x01, 0x04, 0x01, 0xd6, 0x79, 0x02, 0x01, 0x11]).unwrap(),
            "1.3.6.1.4.1.11129.2.1.17"
        );
    }
}
