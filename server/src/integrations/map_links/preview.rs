//! The place lookup's answer (design 110 §8.3): Google's `/maps/preview/place` by a feature id, read as
//! `serde_json::Value` along checked paths only, then checked against the link before a point is believed. Any miss
//! is an [Outcome], never a guess: the phone then opens Organic Maps' offline search.

use serde::Serialize;

use super::links;

/// What one lookup came to (§8.4's counter; the database's `outcome`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Outcome {
    /// The place, its own point within 200 m of the viewport (or the viewport alone, every check passed).
    Found,
    /// The place, its own point over 200 m from the viewport: the place's point is used.
    Disagree,
    /// A redirect to Google's consent page.
    Consent,
    /// Any other answer than a 200.
    Http,
    /// The answer isn't the shape this server reads.
    Format,
    /// The answer is about another feature id.
    Echo,
    /// The point is off the Earth, or at 0,0.
    Range,
    /// The point is far from the feature id's own S2 cell.
    Far,
    /// The address shares neither the link's postcode nor its street.
    Text,
    /// Google couldn't be reached in time.
    Unreachable,
    /// The server's own limits: no request was sent.
    Busy,
}

impl Outcome {
    #[cfg(test)]
    pub const ALL: [Outcome; 11] = [
        Outcome::Found,
        Outcome::Disagree,
        Outcome::Consent,
        Outcome::Http,
        Outcome::Format,
        Outcome::Echo,
        Outcome::Range,
        Outcome::Far,
        Outcome::Text,
        Outcome::Unreachable,
        Outcome::Busy,
    ];

    pub fn code(self) -> &'static str {
        match self {
            Outcome::Found => "found",
            Outcome::Disagree => "disagree",
            Outcome::Consent => "consent",
            Outcome::Http => "http",
            Outcome::Format => "format",
            Outcome::Echo => "echo",
            Outcome::Range => "range",
            Outcome::Far => "far",
            Outcome::Text => "text",
            Outcome::Unreachable => "unreachable",
            Outcome::Busy => "busy",
        }
    }

    pub fn found(self) -> bool {
        matches!(self, Outcome::Found | Outcome::Disagree)
    }
}

/// The place, as the phone gets it (`place` of the resolve answer).
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct Place {
    pub lat: f64,
    pub lon: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub address: Option<String>,
}

/// The lookup's address for a feature id (only the id leaves the server, never the link's text): the user's minimal
/// form, `hl` the phone's language ("no" or "en").
pub fn lookup_url(ftid: &str, hl: &str) -> String {
    format!("https://www.google.com/maps/preview/place?pb=!1m2!1s{ftid}!2s&authuser=0&hl={hl}")
}

/// The place's own point and the viewport agree within this.
const AGREE_METRES: f64 = 200.0;
/// The point and the feature id's cell share at least this many leading bits (measured 24-42 for real places, 17 for
/// the requester's IP centre).
const MIN_SHARED_BITS: u32 = 20;
const MAX_NAME: usize = 120;

/// One lookup's answer judged: its outcome and, when found, the place.
pub fn judge(
    status: u16,
    location: Option<&str>,
    content_type: Option<&str>,
    body: &[u8],
    ftid: &str,
    text: Option<&str>,
) -> (Outcome, Option<Place>) {
    if (300..400).contains(&status) {
        let consent = location
            .and_then(|l| reqwest::Url::parse(l).ok())
            .and_then(|u| u.host_str().map(str::to_ascii_lowercase))
            .is_some_and(|h| h == "consent.google.com" || h.starts_with("consent.google."));
        return (
            if consent {
                Outcome::Consent
            } else {
                Outcome::Http
            },
            None,
        );
    }
    if status != 200 {
        return (Outcome::Http, None);
    }
    let json_type = content_type
        .map(str::to_ascii_lowercase)
        .is_some_and(|t| t.contains("json") || t.contains("javascript"));
    if !json_type {
        return (Outcome::Format, None);
    }
    let Some(rest) = body.strip_prefix(b")]}'") else {
        return (Outcome::Format, None);
    };
    let Ok(d) = serde_json::from_slice::<serde_json::Value>(rest.trim_ascii_start()) else {
        return (Outcome::Format, None);
    };
    let place = d.get(6).filter(|v| v.is_array());
    let own = place
        .and_then(|p| p.get(9))
        .and_then(|p| Some((p.get(2)?.as_f64()?, p.get(3)?.as_f64()?)));
    let viewport = d
        .get(4)
        .and_then(|v| v.get(0))
        .and_then(|v| Some((v.get(2)?.as_f64()?, v.get(1)?.as_f64()?)));
    let Some(place) = place else {
        return (Outcome::Format, None);
    };
    let (point, disagree) = match (own, viewport) {
        (Some(own), Some(view)) => (own, metres(own, view) > AGREE_METRES),
        (Some(own), None) => (own, false),
        (None, Some(view)) => (view, false),
        (None, None) => return (Outcome::Format, None),
    };
    // The echo: the answer is about the id asked for (a `0x0:` cid's second half alone).
    let echo = place
        .get(10)
        .and_then(|e| e.as_str())
        .and_then(links::valid_ftid);
    let echoed = match (echo, ftid.split_once(':')) {
        (Some(echo), Some(("0x0", second))) => {
            echo.split_once(':').is_some_and(|(_, s)| s == second)
        }
        (Some(echo), _) => echo == ftid,
        (None, _) => false,
    };
    if !echoed {
        return (Outcome::Echo, None);
    }
    let (lat, lon) = point;
    if !(-90.0..=90.0).contains(&lat)
        || !(-180.0..=180.0).contains(&lon)
        || (lat.abs() < 0.001 && lon.abs() < 0.001)
    {
        return (Outcome::Range, None);
    }
    if let Some(cell) = links::ftid_cell(ftid)
        && links::shared_bits(links::s2_cell(lat, lon), cell) < MIN_SHARED_BITS
    {
        return (Outcome::Far, None);
    }
    let name = place
        .get(11)
        .and_then(|n| n.as_str())
        .map(str::trim)
        .filter(|n| !n.is_empty() && n.chars().count() <= MAX_NAME)
        .map(str::to_string);
    let address = place.get(2).and_then(|a| a.as_array()).and_then(|lines| {
        if !(1..=4).contains(&lines.len()) {
            return None;
        }
        let lines: Option<Vec<&str>> = lines
            .iter()
            .map(|l| l.as_str().map(str::trim).filter(|l| !l.is_empty()))
            .collect();
        Some(lines?.join(", "))
    });
    if !text_agrees(text, address.as_deref()) {
        return (Outcome::Text, None);
    }
    let round = |x: f64| (x * 1e7).round() / 1e7;
    (
        if disagree {
            Outcome::Disagree
        } else {
            Outcome::Found
        },
        Some(Place {
            lat: round(lat),
            lon: round(lon),
            name,
            address,
        }),
    )
}

/// Great-circle distance in metres.
fn metres(a: (f64, f64), b: (f64, f64)) -> f64 {
    let (la1, lo1, la2, lo2) = (
        a.0.to_radians(),
        a.1.to_radians(),
        b.0.to_radians(),
        b.1.to_radians(),
    );
    let h = ((la2 - la1) / 2.0).sin().powi(2)
        + la1.cos() * la2.cos() * ((lo2 - lo1) / 2.0).sin().powi(2);
    2.0 * 6_371_000.0 * h.sqrt().asin()
}

/// Lowercase, the diacritics dropped, æ/ø/ß spelled out.
fn fold(text: &str) -> String {
    use unicode_normalization::UnicodeNormalization;
    use unicode_normalization::char::is_combining_mark;
    text.to_lowercase()
        .replace('æ', "ae")
        .replace('ø', "o")
        .replace('ß', "ss")
        .nfd()
        .filter(|c| !is_combining_mark(*c))
        .collect()
}

fn tokens(text: &str) -> Vec<String> {
    fold(text)
        .split(|c: char| !c.is_alphanumeric())
        .filter(|t| !t.is_empty())
        .map(str::to_string)
        .collect()
}

/// "Near what the text says", without geocoding (§8.3 `text`): when the link's text has a postcode (four digits
/// leading a part, "0277 Oslo") or a street (a word before a house number, "Drammensveien 133"), the answer's address
/// must share one of them. A text without either passes.
fn text_agrees(text: Option<&str>, address: Option<&str>) -> bool {
    let Some(text) = text else {
        return true;
    };
    let mut postcodes = Vec::new();
    let mut streets = Vec::new();
    for part in text.split(',') {
        let words = tokens(part);
        if let Some(first) = words.first()
            && first.len() == 4
            && first.bytes().all(|b| b.is_ascii_digit())
        {
            postcodes.push(first.clone());
        }
        for pair in words.windows(2) {
            let number = pair[1].trim_end_matches(|c: char| c.is_ascii_alphabetic());
            if pair[0].chars().count() >= 3
                && pair[0].chars().all(char::is_alphabetic)
                && (1..=4).contains(&number.len())
                && number.bytes().all(|b| b.is_ascii_digit())
            {
                streets.push(pair[0].clone());
            }
        }
    }
    if postcodes.is_empty() && streets.is_empty() {
        return true;
    }
    let Some(address) = address else {
        return false;
    };
    let have = tokens(address);
    postcodes.iter().chain(&streets).any(|t| have.contains(t))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_text_check_reads_streets_and_postcodes() {
        let text = Some("Artbox AS, Drammensveien 133, 0277 Oslo");
        assert!(text_agrees(text, Some("Drammensveien 133, 0277 Oslo")));
        assert!(text_agrees(text, Some("Drammensveien 133, 0278 Oslo")));
        assert!(text_agrees(text, Some("Somewhere 1, 0277 Oslo")));
        assert!(!text_agrees(text, Some("Storgata 1, 0155 Oslo")));
        assert!(!text_agrees(text, None));
        assert!(text_agrees(Some("Steak no Don"), None));
        assert!(text_agrees(
            Some("Kiwi Tåsen, Tåsenveien 71"),
            Some("Tasenveien 71, 0873 Oslo")
        ));
        assert!(text_agrees(None, None));
    }
}
