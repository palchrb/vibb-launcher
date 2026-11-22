//! Apple's Wi-Fi location service's request and answer (design 125 A8), by hand: no protobuf crate, one fixed path
//! through an untrusted answer.
//!
//! The format is ported from GrapheneOS NetworkLocation (branch 17, 474472e: `proto/apple_wps.proto`,
//! `ApplePositioningService.kt`, `AppleWifiPositioningService.kt`) under the MIT license:
//!
//! Copyright © 2024-2025 GrapheneOS
//!
//! Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
//! documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
//! rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
//! permit persons to whom the Software is furnished to do so, subject to the following conditions:
//!
//! The above copyright notice and this permission notice shall be included in all copies or substantial portions of
//! the Software.
//!
//! THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
//! WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
//! COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
//! OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
//!
//! - **Request**: GrapheneOS's binary header (`short 1`, the locale, the identifier and the version as length-prefixed
//!   strings, `int 1`, the message's length), then `ALSLocationRequest {wireless_aps 2: [WirelessAP {mac_id 1}],
//!   number_of_surrounding_wifis 4, surrounding_wifi_bands 31 (packed: 2.4 and 5 GHz), wifi_altitude_scale 32 (10^2),
//!   meta 33 {software_build 1, product_id 2}}` - macOS's `locationd`, no account, no device identifier. Pinned by the
//!   golden hex in `testdata/wifi_lookup/server.json`.
//! - **Answer**: a 10-byte header skipped (shorter: a format failure), then `ALSLocationResponse.wireless_aps 2 ->
//!   WirelessAP {mac_id 1, location 2} -> ALSLocation {latitude 1, longitude 2, accuracy 3}`. Varints at most 10 bytes,
//!   lengths checked, unknown fields of wire types 0, 1, 2 and 5 skipped, groups (3, 4) refused. A latitude of
//!   -18000000000, a missing location or latitude, or a position out of range is unknown (never 0,0). Apple drops
//!   leading zeros in `mac_id` (GrapheneOS's `normalizeBssid`); a non-hex one is left out; the first of duplicates wins;
//!   at most [MAX_ANSWER_APS] are read.

/// The answer's header before the protobuf message.
pub const ANSWER_HEADER: usize = 10;
/// At most this many APs are taken from one answer.
pub const MAX_ANSWER_APS: usize = 200;
/// A longer answer isn't read (`music_net::Kind::AppleWps` cuts the body here).
pub const MAX_ANSWER_BYTES: usize = 256_000;
/// Apple's "unknown" latitude.
const UNKNOWN_LATITUDE: i64 = -18_000_000_000;

const LOCALE: &[u8] = b"en-US_US";
const IDENTIFIER: &[u8] = b"com.apple.locationd";
const VERSION: &[u8] = b"15.4.24E248";
const SOFTWARE_BUILD: &[u8] = b"macOS15.4/24E248";
const PRODUCT_ID: &[u8] = b"arm64";

/// A position Apple knows for an AP: degrees and metres.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Position {
    pub lat: f64,
    pub lng: f64,
    pub acc_m: u16,
}

/// Why an answer couldn't be read.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Malformed;

fn varint(out: &mut Vec<u8>, mut n: u64) {
    loop {
        let byte = (n & 0x7f) as u8;
        n >>= 7;
        if n == 0 {
            out.push(byte);
            return;
        }
        out.push(byte | 0x80);
    }
}

fn tag(out: &mut Vec<u8>, field: u64, wire: u64) {
    varint(out, (field << 3) | wire);
}

fn bytes_field(out: &mut Vec<u8>, field: u64, payload: &[u8]) {
    tag(out, field, 2);
    varint(out, payload.len() as u64);
    out.extend_from_slice(payload);
}

/// The request body for `bssids` (lowercase `xx:xx:xx:xx:xx:xx`, already checked) with `surrounding` more APs asked
/// for (at least 1: 0 means about 400 to Apple).
pub fn request(bssids: &[String], surrounding: u32) -> Vec<u8> {
    let mut message = Vec::new();
    for bssid in bssids {
        let mut ap = Vec::new();
        bytes_field(&mut ap, 1, bssid.as_bytes());
        bytes_field(&mut message, 2, &ap);
    }
    tag(&mut message, 4, 0);
    varint(&mut message, u64::from(surrounding.max(1)));
    // proto3 packs a repeated enum: 2.4 GHz and 5 GHz.
    bytes_field(&mut message, 31, &[1, 2]);
    tag(&mut message, 32, 0);
    varint(&mut message, 2);
    let mut meta = Vec::new();
    bytes_field(&mut meta, 1, SOFTWARE_BUILD);
    bytes_field(&mut meta, 2, PRODUCT_ID);
    bytes_field(&mut message, 33, &meta);

    let mut body = Vec::with_capacity(64 + message.len());
    body.extend_from_slice(&1u16.to_be_bytes());
    for text in [LOCALE, IDENTIFIER, VERSION] {
        body.extend_from_slice(&(text.len() as u16).to_be_bytes());
        body.extend_from_slice(text);
    }
    body.extend_from_slice(&1u32.to_be_bytes());
    body.extend_from_slice(&(message.len() as u32).to_be_bytes());
    body.extend_from_slice(&message);
    body
}

/// A cursor over one message's bytes.
struct Reader<'a> {
    bytes: &'a [u8],
    at: usize,
}

/// One field's value.
enum Value<'a> {
    Varint(u64),
    Bytes(&'a [u8]),
    /// A fixed 32- or 64-bit value, skipped.
    Fixed,
}

impl<'a> Reader<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Reader { bytes, at: 0 }
    }

    fn varint(&mut self) -> Result<u64, Malformed> {
        let mut n: u64 = 0;
        for i in 0..10 {
            let byte = *self.bytes.get(self.at).ok_or(Malformed)?;
            self.at += 1;
            // The tenth byte holds only the 64th bit.
            if i == 9 && byte > 1 {
                return Err(Malformed);
            }
            n |= u64::from(byte & 0x7f) << (7 * i);
            if byte & 0x80 == 0 {
                return Ok(n);
            }
        }
        Err(Malformed)
    }

    fn take(&mut self, len: usize) -> Result<&'a [u8], Malformed> {
        let end = self.at.checked_add(len).ok_or(Malformed)?;
        let slice = self.bytes.get(self.at..end).ok_or(Malformed)?;
        self.at = end;
        Ok(slice)
    }

    /// The next field's number and value, `None` at the end.
    fn field(&mut self) -> Result<Option<(u64, Value<'a>)>, Malformed> {
        if self.at >= self.bytes.len() {
            return Ok(None);
        }
        let key = self.varint()?;
        let number = key >> 3;
        if number == 0 {
            return Err(Malformed);
        }
        let value = match key & 7 {
            0 => Value::Varint(self.varint()?),
            1 => {
                self.take(8)?;
                Value::Fixed
            }
            2 => {
                let len = usize::try_from(self.varint()?).map_err(|_| Malformed)?;
                Value::Bytes(self.take(len)?)
            }
            5 => {
                self.take(4)?;
                Value::Fixed
            }
            // Groups (3, 4) and anything else.
            _ => return Err(Malformed),
        };
        Ok(Some((number, value)))
    }
}

/// `mac_id` as `xx:xx:xx:xx:xx:xx` lowercase: Apple drops leading zeros; anything but six groups of one or two hex
/// digits is `None`.
pub fn normal_mac(raw: &str) -> Option<String> {
    let mut out = String::with_capacity(17);
    let mut groups = 0;
    for part in raw.split(':') {
        groups += 1;
        if groups > 6
            || part.is_empty()
            || part.len() > 2
            || !part.bytes().all(|b| b.is_ascii_hexdigit())
        {
            return None;
        }
        if groups > 1 {
            out.push(':');
        }
        if part.len() == 1 {
            out.push('0');
        }
        out.push_str(&part.to_ascii_lowercase());
    }
    (groups == 6).then_some(out)
}

/// `ALSLocation`'s position, `None` when unknown or out of range.
fn position(bytes: &[u8]) -> Result<Option<Position>, Malformed> {
    let mut reader = Reader::new(bytes);
    let (mut lat, mut lng, mut acc) = (None, 0i64, 0i64);
    while let Some((number, value)) = reader.field()? {
        match (number, value) {
            (1, Value::Varint(n)) => lat = Some(n as i64),
            (2, Value::Varint(n)) => lng = n as i64,
            // int32: a negative one arrives sign-extended to 64 bits.
            (3, Value::Varint(n)) => acc = n as i64,
            (1..=3, _) => return Err(Malformed),
            _ => {}
        }
    }
    let Some(lat) = lat.filter(|lat| *lat != UNKNOWN_LATITUDE) else {
        return Ok(None);
    };
    // 1e-8 degrees from Apple, kept to 1e-7 (a centimetre) as the cache seals it.
    let degrees = |n: i64| (n as f64 / 10.0).round() / 1e7;
    let (lat, lng) = (degrees(lat), degrees(lng));
    if !(-90.0..=90.0).contains(&lat)
        || !(-180.0..=180.0).contains(&lng)
        || !(1..=5000).contains(&acc)
    {
        return Ok(None);
    }
    Ok(Some(Position {
        lat,
        lng,
        acc_m: acc as u16,
    }))
}

/// The APs of an answer's body (the header included), in Apple's order: each BSSID once, with its position or `None`
/// (unknown). `Err` = not an answer this reads (a format failure).
pub fn answer(body: &[u8]) -> Result<Vec<(String, Option<Position>)>, Malformed> {
    if body.len() > MAX_ANSWER_BYTES {
        return Err(Malformed);
    }
    let message = body.get(ANSWER_HEADER..).ok_or(Malformed)?;
    let mut reader = Reader::new(message);
    let mut aps: Vec<(String, Option<Position>)> = Vec::new();
    while let Some((number, value)) = reader.field()? {
        let (2, ap) = (number, value) else {
            continue;
        };
        let Value::Bytes(ap) = ap else {
            return Err(Malformed);
        };
        if aps.len() >= MAX_ANSWER_APS {
            break;
        }
        let mut fields = Reader::new(ap);
        let (mut mac, mut location) = (None, None);
        while let Some((number, value)) = fields.field()? {
            match (number, value) {
                (1, Value::Bytes(text)) => mac = Some(text),
                (2, Value::Bytes(bytes)) => location = Some(position(bytes)?),
                (1 | 2, _) => return Err(Malformed),
                _ => {}
            }
        }
        let Some(mac) = mac
            .and_then(|m| std::str::from_utf8(m).ok())
            .and_then(normal_mac)
        else {
            continue;
        };
        if aps.iter().any(|(known, _)| *known == mac) {
            continue;
        }
        aps.push((mac, location.flatten()));
    }
    Ok(aps)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn macs_get_their_leading_zeros() {
        assert_eq!(
            normal_mac("0:0:5E:0:53:A").as_deref(),
            Some("00:00:5e:00:53:0a")
        );
        assert_eq!(
            normal_mac("00:00:5e:00:53:01").as_deref(),
            Some("00:00:5e:00:53:01")
        );
        for bad in [
            "",
            "0:0:5e:0:53",
            "0:0:5e:0:53:1:2",
            "0:0:5e:0:53:",
            "000:0:5e:0:53:1",
            "g:0:5e:0:53:1",
        ] {
            assert_eq!(normal_mac(bad), None, "{bad}");
        }
    }

    #[test]
    fn varints_are_bounded() {
        // The tenth byte may hold only the 64th bit.
        let mut max = vec![0xff; 9];
        max.push(0x01);
        assert_eq!(Reader::new(&max).varint(), Ok(u64::MAX));
        let mut over = vec![0xff; 9];
        over.push(0x02);
        assert_eq!(Reader::new(&over).varint(), Err(Malformed));
        assert_eq!(Reader::new(&[0x80; 11]).varint(), Err(Malformed));
        assert_eq!(Reader::new(&[0x80]).varint(), Err(Malformed));
        // -18000000000 as a plain varint is ten bytes and reads back.
        let mut unknown = Vec::new();
        varint(&mut unknown, UNKNOWN_LATITUDE as u64);
        assert_eq!(unknown.len(), 10);
        assert_eq!(
            Reader::new(&unknown).varint().map(|n| n as i64),
            Ok(UNKNOWN_LATITUDE)
        );
    }
}
