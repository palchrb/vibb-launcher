//! Apple's cell lookup (design 125b, step 3 of design 125): a cell's wire form (C3), its canonical form (C7) and the
//! cell call's request and answer (C6), by hand as [super::codec] - one fixed path through an untrusted answer.
//!
//! The format is ported from GrapheneOS NetworkLocation (branch 17, 474472e: `proto/apple_wps.proto`,
//! `cell/AppleCellPositioningService.kt`, `cell/CellTowerScanner.kt`) under the MIT license - its notice is in
//! [super::codec] and `NOTICE.md`.
//!
//! - **The wire form** `"<radio>:<mcc>:<mnc>:<area>:<cid>"`: radio `gsm|wcdma|lte|nr`, MCC three digits (`001`-`999`),
//!   MNC two or three digits as the network gives it, area and cid decimal without leading zeros, within each radio's
//!   ranges ([Radio::areas], [Radio::cids]: 0 and the all-ones fills cut at both ends). Anything else is refused.
//! - **The canonical form** - the cache's key and the seal's AAD - has MCC and MNC as numbers
//!   (`lte:242:1:4711:12345678`): Apple's own numbers, so the surrounding towers it sends meet the phones' asks, and two
//!   spellings of one MNC (`01`, `001`) are one cell.
//! - **Request** (C6): GrapheneOS's header, then `ALSLocationRequest` with the towers (GSM 1, LTE 25, WCDMA in SCDMA's
//!   27, NR 29: `{mcc 1, mnc 2, cell_id 3, area 4}`, no channel, PCI or signal), each standard asked with its count of
//!   surrounding towers (3, 26, 28, 30: `max(1, (total - 19) x its share)`, `total` 25, or 4 while throttled - Apple
//!   sends about 19 more than asked, ~120 for a count of 0) and `meta` 33; field-number order, as protoc writes it.
//! - **Answer**: the 10-byte header, then fields 1 (GSM), 22 (LTE), 23 (SCDMA, read as WCDMA) and 24 (NR); field 2
//!   (APs) and 21 (CDMA) skipped and never cached. A tower missing MCC, MNC, area or cid, a negative one or one outside
//!   the ranges is left out; a missing location or Apple's -18000000000 is unknown; an accuracy outside
//!   1..[MAX_CELL_ACCURACY_M] is unknown; at most [super::codec::MAX_ANSWER_APS] towers; the first of duplicates wins.

use super::codec::{self, Malformed, Position};

/// At most this many cells in one request (GrapheneOS's 12).
pub const MAX_CELLS: usize = 12;
/// A tower's accuracy is at most this (towers reach further than APs' 5 km).
pub const MAX_CELL_ACCURACY_M: i64 = 50_000;
/// Apple sends about this many more towers than a count asks for (GrapheneOS's reading).
const EXTRA_TOWERS: i64 = 19;
/// The towers a call asks for in all, and while throttled.
pub const TOWERS: i64 = 25;
pub const TOWERS_THROTTLED: i64 = 4;
/// An answer of this many towers or more throttles the next calls ([super::route::THROTTLE_FOR]).
pub const CELL_THROTTLE_AT: usize = 10;

/// A cell's radio, as the wire form names it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Radio {
    Gsm,
    Wcdma,
    Lte,
    Nr,
}

impl Radio {
    pub fn word(self) -> &'static str {
        match self {
            Radio::Gsm => "gsm",
            Radio::Wcdma => "wcdma",
            Radio::Lte => "lte",
            Radio::Nr => "nr",
        }
    }

    fn of(word: &str) -> Option<Radio> {
        match word {
            "gsm" => Some(Radio::Gsm),
            "wcdma" => Some(Radio::Wcdma),
            "lte" => Some(Radio::Lte),
            "nr" => Some(Radio::Nr),
            _ => None,
        }
    }

    /// The area's range (LAC or TAC): 0 and 3GPP's reserved `...FFFE` and the all-ones fill cut.
    pub fn areas(self) -> std::ops::RangeInclusive<u64> {
        match self {
            Radio::Nr => 1..=16_777_213,
            _ => 1..=65_533,
        }
    }

    /// The cid's range: 0 and the all-ones fill cut.
    pub fn cids(self) -> std::ops::RangeInclusive<u64> {
        match self {
            Radio::Gsm => 1..=65_534,
            Radio::Wcdma | Radio::Lte => 1..=268_435_454,
            Radio::Nr => 1..=68_719_476_734,
        }
    }

    /// The request's field for its towers and for its count of surrounding towers.
    fn request_fields(self) -> (u64, u64) {
        match self {
            Radio::Gsm => (1, 3),
            Radio::Lte => (25, 26),
            Radio::Wcdma => (27, 28),
            Radio::Nr => (29, 30),
        }
    }

    /// The answer's field holding this radio's towers.
    fn of_answer_field(field: u64) -> Option<Radio> {
        match field {
            1 => Some(Radio::Gsm),
            22 => Some(Radio::Lte),
            23 => Some(Radio::Wcdma),
            24 => Some(Radio::Nr),
            _ => None,
        }
    }
}

/// One cell's identity.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Cell {
    pub radio: Radio,
    pub mcc: u64,
    pub mnc: u64,
    pub area: u64,
    pub cid: u64,
}

/// Decimal digits only, no sign, no space; `None` past 20 digits.
fn digits(text: &str) -> Option<u64> {
    if text.is_empty() || text.len() > 20 || !text.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    text.parse().ok()
}

/// A number without a leading zero.
fn plain(text: &str) -> Option<u64> {
    (!text.starts_with('0')).then(|| digits(text)).flatten()
}

impl Cell {
    /// A cell of the wire form (C3), or `None`.
    pub fn parse(text: &str) -> Option<Cell> {
        let mut parts = text.split(':');
        let radio = Radio::of(parts.next()?)?;
        let mcc_text = parts.next()?;
        let mnc_text = parts.next()?;
        let area = plain(parts.next()?)?;
        let cid = plain(parts.next()?)?;
        if parts.next().is_some() || mcc_text.len() != 3 || !(2..=3).contains(&mnc_text.len()) {
            return None;
        }
        let cell = Cell {
            radio,
            mcc: digits(mcc_text)?,
            mnc: digits(mnc_text)?,
            area,
            cid,
        };
        cell.in_range().then_some(cell)
    }

    /// Every part within its range (the MCC 1..=999, the MNC 0..=999).
    fn in_range(&self) -> bool {
        (1..=999).contains(&self.mcc)
            && self.mnc <= 999
            && self.radio.areas().contains(&self.area)
            && self.radio.cids().contains(&self.cid)
    }

    /// The canonical form (C7): MCC and MNC as numbers.
    pub fn canonical(&self) -> String {
        format!(
            "{}:{}:{}:{}:{}",
            self.radio.word(),
            self.mcc,
            self.mnc,
            self.area,
            self.cid
        )
    }

    /// A cell of the canonical form (what [Cell::canonical] writes), or `None`.
    pub fn from_canonical(text: &str) -> Option<Cell> {
        let mut parts = text.split(':');
        let cell = Cell {
            radio: Radio::of(parts.next()?)?,
            mcc: digits(parts.next()?)?,
            mnc: digits(parts.next()?)?,
            area: digits(parts.next()?)?,
            cid: digits(parts.next()?)?,
        };
        (parts.next().is_none() && cell.in_range() && cell.canonical() == text).then_some(cell)
    }
}

fn varint(out: &mut Vec<u8>, n: u64) {
    codec::varint(out, n);
}

fn int_field(out: &mut Vec<u8>, field: u64, n: u64) {
    codec::tag(out, field, 0);
    varint(out, n);
}

/// The surrounding towers each standard of `cells` asks for: `max(1, (total - 19) x its share)`, `total`
/// [TOWERS] or [TOWERS_THROTTLED].
pub fn surrounding(cells: &[Cell], throttled: bool) -> Vec<(Radio, u64)> {
    let total = if throttled { TOWERS_THROTTLED } else { TOWERS };
    let asked = cells.len().max(1) as i64;
    [Radio::Gsm, Radio::Lte, Radio::Wcdma, Radio::Nr]
        .into_iter()
        .filter_map(|radio| {
            let share = cells.iter().filter(|c| c.radio == radio).count() as i64;
            (share > 0).then(|| {
                (
                    radio,
                    ((total - EXTRA_TOWERS) * share / asked).max(1) as u64,
                )
            })
        })
        .collect()
}

/// The cell call's body for `cells` (C6): GrapheneOS's header and `ALSLocationRequest` with the towers and each
/// standard's count, in field-number order, and `meta`.
pub fn request(cells: &[Cell], throttled: bool) -> Vec<u8> {
    let counts = surrounding(cells, throttled);
    let mut message = Vec::new();
    for radio in [Radio::Gsm, Radio::Lte, Radio::Wcdma, Radio::Nr] {
        let (towers, count) = radio.request_fields();
        for cell in cells.iter().filter(|c| c.radio == radio) {
            let mut tower = Vec::new();
            int_field(&mut tower, 1, cell.mcc);
            int_field(&mut tower, 2, cell.mnc);
            int_field(&mut tower, 3, cell.cid);
            int_field(&mut tower, 4, cell.area);
            codec::bytes_field(&mut message, towers, &tower);
        }
        if let Some((_, n)) = counts.iter().find(|(r, _)| *r == radio) {
            int_field(&mut message, count, *n);
        }
    }
    codec::meta(&mut message);
    codec::frame(&message)
}

/// The towers of a cell call's answer (the header included), in Apple's order: each canonical form once, with its
/// position or `None` (unknown). `Err` = not an answer this reads.
pub fn answer(body: &[u8]) -> Result<Vec<(String, Option<Position>)>, Malformed> {
    let mut reader = codec::Reader::answer(body)?;
    let mut towers: Vec<(String, Option<Position>)> = Vec::new();
    while let Some((number, value)) = reader.field()? {
        let Some(radio) = Radio::of_answer_field(number) else {
            continue;
        };
        let codec::Value::Bytes(bytes) = value else {
            return Err(Malformed);
        };
        if towers.len() >= codec::MAX_ANSWER_APS {
            break;
        }
        let mut fields = codec::Reader::new(bytes);
        let (mut mcc, mut mnc, mut cid, mut area, mut location) = (None, None, None, None, None);
        while let Some((number, value)) = fields.field()? {
            match (number, value) {
                (1, codec::Value::Varint(n)) => mcc = Some(n),
                (2, codec::Value::Varint(n)) => mnc = Some(n),
                (3, codec::Value::Varint(n)) => cid = Some(n),
                (4, codec::Value::Varint(n)) => area = Some(n),
                (5, codec::Value::Bytes(b)) => {
                    location = Some(codec::position(b, MAX_CELL_ACCURACY_M)?)
                }
                (1..=5, _) => return Err(Malformed),
                _ => {}
            }
        }
        // A negative int32 or int64 arrives as a huge u64: out of every range, so left out.
        let (Some(mcc), Some(mnc), Some(cid), Some(area)) = (mcc, mnc, cid, area) else {
            continue;
        };
        let cell = Cell {
            radio,
            mcc,
            mnc,
            area,
            cid,
        };
        if !cell.in_range() {
            continue;
        }
        let key = cell.canonical();
        if towers.iter().any(|(known, _)| *known == key) {
            continue;
        }
        towers.push((key, location.flatten()));
    }
    Ok(towers)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_canonical_form_takes_numbers() {
        let a = Cell::parse("lte:242:01:4711:12345678").unwrap();
        let b = Cell::parse("lte:242:001:4711:12345678").unwrap();
        assert_eq!(a, b);
        assert_eq!(a.canonical(), "lte:242:1:4711:12345678");
        assert_eq!(Cell::from_canonical("lte:242:1:4711:12345678"), Some(a));
        // Only the canonical spelling reads back.
        assert_eq!(Cell::from_canonical("lte:242:01:4711:12345678"), None);
        assert_eq!(
            Cell::parse("nr:001:01:16777213:68719476734")
                .unwrap()
                .canonical(),
            "nr:1:1:16777213:68719476734"
        );
    }

    #[test]
    fn the_counts_share_what_apple_adds() {
        let lte = Cell::parse("lte:001:01:1:1").unwrap();
        let nr = Cell::parse("nr:001:01:1:2").unwrap();
        let gsm = Cell::parse("gsm:001:01:1:3").unwrap();
        assert_eq!(surrounding(&[lte], false), [(Radio::Lte, 6)]);
        assert_eq!(
            surrounding(&[gsm, lte, nr], false),
            [(Radio::Gsm, 2), (Radio::Lte, 2), (Radio::Nr, 2)]
        );
        // Throttled: one each, never 0 (~120).
        assert_eq!(
            surrounding(&[gsm, lte], true),
            [(Radio::Gsm, 1), (Radio::Lte, 1)]
        );
    }
}
