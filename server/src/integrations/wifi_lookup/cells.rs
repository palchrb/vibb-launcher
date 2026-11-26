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
//!   surrounding towers (3, 26, 28, 30: design 137 N2's `max(1, NEAR_ASKED x its share)`, [NEAR_ASKED] 12, or 1 each
//!   while throttled - Apple answers the count in full-id neighbours and adds id-less areas on top, ~120 for a count
//!   of 0) and `meta` 33; field-number order, as protoc writes it.
//! - **Answer**: the 10-byte header, then fields 1 (GSM), 22 (LTE), 23 (SCDMA, read as WCDMA) and 24 (NR); field 2
//!   (APs) and 21 (CDMA) skipped and never cached. A tower missing MCC, MNC, area or cid, a negative one or one outside
//!   the ranges is left out; a missing location or Apple's -18000000000 is unknown; an accuracy outside
//!   1..[MAX_CELL_ACCURACY_M] is unknown; at most [super::codec::MAX_ANSWER_APS] towers; the first of duplicates wins.
//!   Design 137 N1: an LTE tower's EARFCN (6) and PCI (7), a WCDMA tower's UARFCN (11) and PSC (12) are read when they
//!   are varints within [Radio::near_fields]' ranges, else unknown - a -1, a value past the range or another wire type
//!   never makes the answer malformed; NR's and GSM's are not read.
//! - **Neighbours** (design 137 N3, [near]): an asked cell's list is the call's positioned towers, not asked, that
//!   overlap it (`d <= r_cell + r_tower`), nearest first, at most [NEAR_PER_CELL].

use super::codec::{self, Malformed, Position};

/// At most this many cells in one request (GrapheneOS's 12).
pub const MAX_CELLS: usize = 12;
/// A tower's accuracy is at most this (towers reach further than APs' 5 km).
pub const MAX_CELL_ACCURACY_M: i64 = 50_000;
/// Design 137 N2: the full-id neighbours a call asks for in all (the probe of 2026-11-25: a count of n gave n), shared
/// between the call's standards; while throttled 1 each.
pub const NEAR_ASKED: u64 = 12;
/// Design 137 N3: at most this many neighbours in an asked cell's list.
pub const NEAR_PER_CELL: usize = 12;

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

    /// Design 137 N1: the answer's channel field with its highest value, and its PCI field with its highest value - for
    /// LTE (EARFCN 6, PCI 7) and WCDMA (UARFCN 11, PSC 12); NR's and GSM's are not read.
    pub fn near_fields(self) -> Option<((u64, u64), (u64, u64))> {
        match self {
            Radio::Lte => Some(((6, 262_143), (7, 503))),
            Radio::Wcdma => Some(((11, 16_383), (12, 511))),
            Radio::Gsm | Radio::Nr => None,
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

    /// Design 137 N5: the wire form (C3) - MCC three digits, MNC at least two (Apple gives a number) - so a phone's
    /// parser takes a neighbour Apple named.
    pub fn wire(&self) -> String {
        format!(
            "{}:{:03}:{:02}:{}:{}",
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

/// The surrounding towers each standard of `cells` asks for (design 137 N2): `max(1, NEAR_ASKED x its share)`, 1 each
/// while throttled.
pub fn surrounding(cells: &[Cell], throttled: bool) -> Vec<(Radio, u64)> {
    let asked = cells.len().max(1) as u64;
    [Radio::Gsm, Radio::Lte, Radio::Wcdma, Radio::Nr]
        .into_iter()
        .filter_map(|radio| {
            let share = cells.iter().filter(|c| c.radio == radio).count() as u64;
            let count = if throttled {
                1
            } else {
                (NEAR_ASKED * share / asked).max(1)
            };
            (share > 0).then_some((radio, count))
        })
        .collect()
}

/// Design 137 N2: an answer throttles the next calls only when Apple sent more than asked - more parsed towers than
/// the call's cells and [NEAR_ASKED] (GrapheneOS's ~120 when a count is 0 or ignored).
pub fn throttles(towers: usize, asked: usize) -> bool {
    towers as u64 > asked as u64 + NEAR_ASKED
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

/// One tower of a cell call's answer: its canonical form, its position (`None`: unknown), and its channel and PCI when
/// Apple sent them within their ranges (design 137 N1).
#[derive(Clone, Debug, PartialEq)]
pub struct Tower {
    pub cell: String,
    pub position: Option<Position>,
    pub channel: Option<u32>,
    pub pci: Option<u16>,
}

/// The towers of a cell call's answer (the header included), in Apple's order: each canonical form once. `Err` = not
/// an answer this reads.
pub fn answer(body: &[u8]) -> Result<Vec<Tower>, Malformed> {
    let mut reader = codec::Reader::answer(body)?;
    let mut towers: Vec<Tower> = Vec::new();
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
        let near = radio.near_fields();
        let mut fields = codec::Reader::new(bytes);
        let (mut mcc, mut mnc, mut cid, mut area, mut location) = (None, None, None, None, None);
        let (mut channel, mut pci) = (None, None);
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
                // N1: a value past the range (Apple's -1 arrives as a huge u64) or of another wire type is unknown,
                // never malformed; the last one sent counts, as protobuf reads it.
                (number, value) => {
                    let Some(((channel_field, channel_max), (pci_field, pci_max))) = near else {
                        continue;
                    };
                    let within = |max: u64| match value {
                        codec::Value::Varint(n) if n <= max => Some(n),
                        _ => None,
                    };
                    if number == channel_field {
                        channel = within(channel_max).and_then(|n| u32::try_from(n).ok());
                    } else if number == pci_field {
                        pci = within(pci_max).and_then(|n| u16::try_from(n).ok());
                    }
                }
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
        if towers.iter().any(|known| known.cell == key) {
            continue;
        }
        towers.push(Tower {
            cell: key,
            position: location.flatten(),
            channel,
            pci,
        });
    }
    Ok(towers)
}

/// One neighbour in an asked cell's list (design 137 N3): its canonical form, Apple's position, its channel and PCI
/// when known.
#[derive(Clone, Debug, PartialEq)]
pub struct Near {
    pub cell: String,
    pub position: Position,
    pub channel: Option<u32>,
    pub pci: Option<u16>,
}

/// Design 137 N3: the list kept for an asked cell Apple placed at `at` - the call's positioned `towers` that are not
/// `asked` and overlap it (`d <= r_cell + r_tower`: a phone hears both only where both reach), nearest first (Apple's
/// order among equals), at most [NEAR_PER_CELL].
pub fn near(at: &Position, towers: &[Tower], asked: &[String]) -> Vec<Near> {
    let mut list: Vec<(f64, Near)> = towers
        .iter()
        .filter(|t| !asked.contains(&t.cell))
        .filter_map(|t| {
            let position = t.position?;
            let d = crate::zones::distance_m(at.lat, at.lng, position.lat, position.lng);
            (d <= f64::from(at.acc_m) + f64::from(position.acc_m)).then(|| {
                let near = Near {
                    cell: t.cell.clone(),
                    position,
                    channel: t.channel,
                    pci: t.pci,
                };
                (d, near)
            })
        })
        .collect();
    list.sort_by(|a, b| a.0.total_cmp(&b.0));
    list.into_iter()
        .take(NEAR_PER_CELL)
        .map(|(_, near)| near)
        .collect()
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
    fn the_counts_share_the_twelve() {
        let lte = Cell::parse("lte:001:01:1:1").unwrap();
        let nr = Cell::parse("nr:001:01:1:2").unwrap();
        let gsm = Cell::parse("gsm:001:01:1:3").unwrap();
        // Design 137 N2: twelve full-id neighbours, shared between the standards.
        assert_eq!(surrounding(&[lte], false), [(Radio::Lte, 12)]);
        assert_eq!(
            surrounding(&[gsm, lte, nr], false),
            [(Radio::Gsm, 4), (Radio::Lte, 4), (Radio::Nr, 4)]
        );
        let many: Vec<Cell> = (1..=11)
            .map(|n| Cell::parse(&format!("lte:001:01:1:{n}")).unwrap())
            .chain([gsm])
            .collect();
        assert_eq!(
            surrounding(&many, false),
            [(Radio::Gsm, 1), (Radio::Lte, 11)]
        );
        // Throttled: one each, never 0 (~120).
        assert_eq!(
            surrounding(&[gsm, lte], true),
            [(Radio::Gsm, 1), (Radio::Lte, 1)]
        );
    }

    /// Design 137 N2: only an answer with more towers than the call's cells and twelve throttles.
    #[test]
    fn the_throttle_trips_only_past_what_was_asked() {
        assert!(!throttles(13, 1));
        assert!(throttles(14, 1));
        assert!(!throttles(15, 3));
        assert!(throttles(16, 3));
        assert!(throttles(120, 12));
        assert!(!throttles(0, 1));
    }

    /// Design 137 N5: a neighbour's wire form takes the phone's parser - MCC three digits, MNC at least two.
    #[test]
    fn the_wire_form_parses_back() {
        for (canonical, wire) in [
            ("lte:1:1:4711:12345678", "lte:001:01:4711:12345678"),
            ("wcdma:242:2:2001:70001", "wcdma:242:02:2001:70001"),
            ("lte:310:410:7:7", "lte:310:410:7:7"),
            ("nr:1:0:1048577:4294967297", "nr:001:00:1048577:4294967297"),
        ] {
            let cell = Cell::from_canonical(canonical).unwrap();
            assert_eq!(cell.wire(), wire);
            assert_eq!(Cell::parse(wire), Some(cell));
        }
    }

    fn tower(cell: &str, lat: f64, lng: f64, acc_m: u16, pci: Option<u16>) -> Tower {
        Tower {
            cell: cell.to_string(),
            position: Some(Position { lat, lng, acc_m }),
            channel: pci.map(|_| 6200),
            pci,
        }
    }

    /// Design 137 N3: the overlap cut (`d <= r_cell + r_tower`), never an asked cell, never an unknown position,
    /// nearest first, at most twelve.
    #[test]
    fn the_list_keeps_the_twelve_nearest_that_overlap() {
        let at = Position {
            lat: 59.91,
            lng: 10.75,
            acc_m: 1500,
        };
        // About 111 m a 0.001 degree of latitude.
        let mut towers = vec![
            tower("lte:1:1:4711:1", 59.91, 10.75, 1500, None),
            tower("lte:1:1:4711:2", 59.935, 10.75, 1400, Some(2)),
            tower("lte:1:1:4711:3", 59.937, 10.75, 1400, Some(3)),
            tower("lte:1:1:4711:4", 59.92, 10.75, 1400, Some(4)),
            Tower {
                cell: "lte:1:1:4711:5".to_string(),
                position: None,
                channel: Some(6200),
                pci: Some(5),
            },
            tower("lte:1:1:4711:6", 59.95, 10.75, 3000, None),
            tower("lte:1:1:4711:7", 59.85, 10.75, 1000, Some(7)),
        ];
        let asked = ["lte:1:1:4711:1".to_string()];
        let list = near(&at, &towers, &asked);
        let cells: Vec<&str> = list.iter().map(|n| n.cell.as_str()).collect();
        // 4 at ~1.1 km; 2 at ~2.8 km (<= 2.9 km); 6 at ~4.4 km within 4.5 km; 3 at ~3.0 km past 2.9 km;
        // 7 at ~6.7 km; 5 without a position; 1 asked.
        assert_eq!(
            cells,
            ["lte:1:1:4711:4", "lte:1:1:4711:2", "lte:1:1:4711:6"]
        );
        assert_eq!(list[0].pci, Some(4));
        assert_eq!(list[0].channel, Some(6200));
        assert_eq!(list[2].pci, None);
        // Twenty overlapping: the twelve nearest.
        towers = (1..=20)
            .rev()
            .map(|n| {
                tower(
                    &format!("lte:1:1:1:{n}"),
                    59.91 + f64::from(n) * 0.001,
                    10.75,
                    1400,
                    None,
                )
            })
            .collect();
        let list = near(&at, &towers, &[]);
        assert_eq!(list.len(), NEAR_PER_CELL);
        let want: Vec<String> = (1..=12).map(|n| format!("lte:1:1:1:{n}")).collect();
        assert_eq!(
            list.iter().map(|n| n.cell.clone()).collect::<Vec<_>>(),
            want
        );
    }
}
