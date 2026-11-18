//! The links themselves (design 110 §2, §3, §4.1): which links are short links and how they are rebuilt
//! (`short_links`), which long links are map links (`long_hosts`), and where a Google link's feature id sits
//! (`feature_ids`) - each rule pinned by the shared contract's rows, which the phone runs through its own code. Plus
//! what the server needs to know of a long link to decide on a lookup, and the S2 cell id the lookup's `far` check
//! compares against.

/// A short link's id: `[A-Za-z0-9_-]{1,64}`.
const MAX_ID: usize = 64;

/// A long link the server takes from a `Location` (§8.1).
pub const MAX_LONG_URL: usize = 2000;

fn parse_https(url: &str) -> Option<reqwest::Url> {
    let parsed = reqwest::Url::parse(url).ok()?;
    (parsed.scheme() == "https"
        && parsed.username().is_empty()
        && parsed.password().is_none()
        && parsed.port().is_none()
        && parsed.host_str().is_some())
    .then_some(parsed)
}

fn short_id(id: &str) -> bool {
    (1..=MAX_ID).contains(&id.len())
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

/// A short link rebuilt from its host and id (https, no query, no fragment), or `None` for anything else
/// (`short_links`): `maps.app.goo.gl/<id>`, `goo.gl/maps/<id>`, `maps.apple/p/<id>`.
pub fn short_link(url: &str) -> Option<String> {
    let parsed = parse_https(url)?;
    let host = parsed.host_str()?.to_ascii_lowercase();
    let path = parsed.path();
    let (prefix, id) = match host.as_str() {
        "maps.app.goo.gl" => ("https://maps.app.goo.gl/", path.strip_prefix('/')?),
        "goo.gl" => ("https://goo.gl/maps/", path.strip_prefix("/maps/")?),
        "maps.apple" => ("https://maps.apple/p/", path.strip_prefix("/p/")?),
        _ => return None,
    };
    short_id(id).then(|| format!("{prefix}{id}"))
}

/// `google.<tld>`'s tld: `com`, a country code, `co.<cc>` or `com.<cc>`.
fn google_tld(rest: &str) -> bool {
    let cc = |s: &str| s.len() == 2 && s.bytes().all(|b| b.is_ascii_lowercase());
    rest == "com"
        || cc(rest)
        || rest
            .strip_prefix("co.")
            .or_else(|| rest.strip_prefix("com."))
            .is_some_and(cc)
}

/// Which Google map host this is: `Some(true)` for `maps.google.<tld>` (any path), `Some(false)` for
/// `www.google.<tld>` and `google.<tld>` (`/maps…` only).
fn google_host(host: &str) -> Option<bool> {
    if let Some(rest) = host.strip_prefix("maps.google.") {
        return google_tld(rest).then_some(true);
    }
    let rest = host.strip_prefix("www.").unwrap_or(host);
    rest.strip_prefix("google.")
        .filter(|tld| google_tld(tld))
        .map(|_| false)
}

fn maps_path(path: &str) -> bool {
    path == "/maps" || path.starts_with("/maps/")
}

/// A Google long map link (`long_hosts`' Google rows).
pub fn google_map_url(url: &reqwest::Url) -> bool {
    let Some(host) = url.host_str().map(str::to_ascii_lowercase) else {
        return false;
    };
    match google_host(&host) {
        Some(true) => {
            let path = url.path();
            if path.is_empty() || path == "/" {
                url.query_pairs()
                    .any(|(k, _)| matches!(k.as_ref(), "q" | "query" | "ll" | "cid" | "ftid"))
            } else {
                true
            }
        }
        Some(false) => maps_path(url.path()),
        None => false,
    }
}

/// A long map link (`long_hosts`): https, no user info or port, a Google map host with its path rule, or
/// `maps.apple.com`.
pub fn long_map_url(url: &str) -> bool {
    let Some(parsed) = parse_https(url) else {
        return false;
    };
    google_map_url(&parsed)
        || parsed
            .host_str()
            .is_some_and(|h| h.eq_ignore_ascii_case("maps.apple.com"))
}

/// A feature id `0x<1-16 hex>:0x<1-16 hex>`, lowercased.
pub fn valid_ftid(text: &str) -> Option<String> {
    let (a, b) = text.split_once(':')?;
    let half = |h: &str| {
        h.strip_prefix("0x")
            .or_else(|| h.strip_prefix("0X"))
            .filter(|hex| {
                (1..=16).contains(&hex.len()) && hex.bytes().all(|b| b.is_ascii_hexdigit())
            })
            .map(str::to_ascii_lowercase)
    };
    Some(format!("0x{}:0x{}", half(a)?, half(b)?))
}

fn percent_decoded(text: &str) -> String {
    percent_decode(text.as_bytes())
}

fn percent_decode(bytes: &[u8]) -> String {
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%'
            && i + 2 < bytes.len()
            && let (Some(h), Some(l)) = (hex_val(bytes[i + 1]), hex_val(bytes[i + 2]))
        {
            out.push(h * 16 + l);
            i += 3;
            continue;
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn hex_val(b: u8) -> Option<u8> {
    (b as char).to_digit(16).map(|d| d as u8)
}

/// Every `!1s<ftid>` in a `data=` string, in order.
fn data_ftids(path: &str) -> Vec<String> {
    let decoded = percent_decoded(path);
    decoded
        .split('!')
        .filter_map(|piece| piece.strip_prefix("1s"))
        .filter_map(valid_ftid)
        .collect()
}

/// A Google long link's feature id (`feature_ids`): `ftid=`, else the `data=…!1s` of `/maps/place/…` (the first) or
/// of `/maps/dir/…` (the last: the destination), else `cid=<decimal>` as `0x0:0x<hex>`. `None` for any other link.
pub fn feature_id(url: &str) -> Option<String> {
    let parsed = parse_https(url)?;
    if !google_map_url(&parsed) {
        return None;
    }
    let pairs: Vec<(String, String)> = parsed.query_pairs().into_owned().collect();
    if let Some((_, value)) = pairs.iter().find(|(k, _)| k == "ftid") {
        return valid_ftid(value);
    }
    let path = parsed.path();
    if path.contains("!1s") || path.contains("%211s") {
        let found = data_ftids(path);
        let pick = if path.contains("/dir/") {
            found.last()
        } else {
            found.first()
        };
        if let Some(ftid) = pick {
            return Some(ftid.clone());
        }
    }
    let cid = pairs.iter().find(|(k, _)| k == "cid")?;
    let n: u64 = cid
        .1
        .parse()
        .ok()
        .filter(|_| cid.1.bytes().all(|b| b.is_ascii_digit()))?;
    Some(format!("0x0:0x{n:x}"))
}

/// What the server needs of a long Google link to decide on a lookup (§8.1): its feature id, its text (`q`/`query`,
/// or the `/maps/place/` or the last `/maps/dir/` segment, when not a point) and whether it carries the place's point.
#[derive(Debug, Default, PartialEq)]
pub struct Facts {
    pub ftid: Option<String>,
    pub text: Option<String>,
    pub has_point: bool,
}

/// `lat,lon` (with spaces or `+` after the comma): the pair, else `None`.
pub fn lat_lon(text: &str) -> Option<(f64, f64)> {
    let (a, b) = text.split_once(',')?;
    let lat: f64 = a.trim().trim_matches('+').parse().ok()?;
    let lon: f64 = b.trim().trim_matches('+').parse().ok()?;
    ((-90.0..=90.0).contains(&lat) && (-180.0..=180.0).contains(&lon)).then_some((lat, lon))
}

fn plus_spaces(text: &str) -> String {
    percent_decoded(&text.replace('+', " ")).trim().to_string()
}

pub fn facts(url: &str) -> Facts {
    let Some(parsed) = parse_https(url) else {
        return Facts::default();
    };
    let mut facts = Facts {
        ftid: feature_id(url),
        ..Default::default()
    };
    let pairs: Vec<(String, String)> = parsed.query_pairs().into_owned().collect();
    let get = |name: &str| {
        pairs
            .iter()
            .find(|(k, _)| k == name)
            .map(|(_, v)| v.as_str())
    };
    let path = parsed.path();
    if path.contains("!3d") && path.contains("!4d") {
        facts.has_point = true;
    }
    for name in ["q", "query", "destination", "daddr"] {
        if let Some(value) = get(name) {
            if lat_lon(value).is_some() {
                facts.has_point = true;
            } else if facts.text.is_none()
                && !value.trim().is_empty()
                && matches!(name, "q" | "query")
            {
                facts.text = Some(value.trim().to_string());
            }
        }
    }
    if get("ll").and_then(lat_lon).is_some() && facts.text.is_none() {
        facts.has_point = true;
    }
    let segments: Vec<&str> = path.split('/').filter(|s| !s.is_empty()).collect();
    let place = segments
        .iter()
        .position(|s| *s == "place" || *s == "search")
        .and_then(|i| segments.get(i + 1));
    let dir_last = segments.iter().position(|s| *s == "dir").and_then(|i| {
        segments[i + 1..]
            .iter()
            .rev()
            .find(|s| !s.starts_with('@') && !s.starts_with("data="))
    });
    if let Some(segment) = place.or(dir_last) {
        let text = plus_spaces(segment);
        if lat_lon(&text).is_some() {
            facts.has_point = true;
        } else if facts.text.is_none() && !text.is_empty() {
            facts.text = Some(text);
        }
    }
    facts
}

/// The text names an address: a part after the first comma holds a digit (G4's rule, §2).
pub fn has_address(text: Option<&str>) -> bool {
    text.and_then(|t| t.split_once(','))
        .is_some_and(|(_, rest)| rest.bytes().any(|b| b.is_ascii_digit()))
}

// --- S2 (the cell id a feature id's first half is, design 110 §4.2) ---------------------------------------------

const POS_TO_ORIENTATION: [u8; 4] = [1, 0, 0, 3];
const IJ_TO_POS: [[u8; 4]; 4] = [[0, 1, 3, 2], [0, 3, 1, 2], [2, 3, 1, 0], [2, 1, 3, 0]];

fn uv_to_st(u: f64) -> f64 {
    if u >= 0.0 {
        0.5 * (1.0 + 3.0 * u).sqrt()
    } else {
        1.0 - 0.5 * (1.0 - 3.0 * u).sqrt()
    }
}

/// The level-30 S2 cell id of a point (the quadratic projection, as Google's S2 library).
pub fn s2_cell(lat: f64, lon: f64) -> u64 {
    let (la, lo) = (lat.to_radians(), lon.to_radians());
    let (x, y, z) = (la.cos() * lo.cos(), la.cos() * lo.sin(), la.sin());
    let abs = [x.abs(), y.abs(), z.abs()];
    let mut face = if abs[0] >= abs[1] && abs[0] >= abs[2] {
        0
    } else if abs[1] >= abs[2] {
        1
    } else {
        2
    };
    if [x, y, z][face] < 0.0 {
        face += 3;
    }
    let (u, v) = match face {
        0 => (y / x, z / x),
        1 => (-x / y, z / y),
        2 => (-x / z, -y / z),
        3 => (z / x, y / x),
        4 => (z / y, -x / y),
        _ => (-y / z, -x / z),
    };
    let max = (1u64 << 30) as f64;
    let ij = |st: f64| ((st * max).floor()).clamp(0.0, max - 1.0) as u64;
    let (i, j) = (ij(uv_to_st(u)), ij(uv_to_st(v)));
    let mut orientation = (face & 1) as u8;
    let mut pos: u64 = 0;
    for k in (0..30).rev() {
        let bits = ((((i >> k) & 1) << 1) | ((j >> k) & 1)) as usize;
        let p = IJ_TO_POS[orientation as usize][bits];
        pos = (pos << 2) | u64::from(p);
        orientation ^= POS_TO_ORIENTATION[p as usize];
    }
    ((face as u64) << 61) | (pos << 1) | 1
}

/// How many leading bits two cell ids share.
pub fn shared_bits(a: u64, b: u64) -> u32 {
    (a ^ b).leading_zeros()
}

/// A feature id's first half as a number (`None` for `0x0`, a `cid`'s: no cell).
pub fn ftid_cell(ftid: &str) -> Option<u64> {
    let first = ftid.split_once(':')?.0.strip_prefix("0x")?;
    u64::from_str_radix(first, 16).ok().filter(|n| *n != 0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_s2_cells_share_the_measured_bits() {
        let artbox = 0x46416db4338a09ff;
        assert_eq!(
            shared_bits(s2_cell(59.922361499999994, 10.6868892), artbox),
            31
        );
        assert_eq!(shared_bits(s2_cell(59.80735225, 10.46334695), artbox), 17);
        assert_eq!(
            shared_bits(s2_cell(34.5945482, 133.7583428), 0x3551565394d96b57),
            42
        );
        assert_eq!(ftid_cell("0x0:0x7d"), None);
        assert_eq!(ftid_cell("0x46416db4338a09ff:0x1"), Some(artbox));
    }

    #[test]
    fn facts_say_when_to_look_up() {
        let g9 = facts(
            "https://maps.google.com?q=Artbox+AS,+Drammensveien+133,+0277+Oslo&ftid=0x46416db4338a09ff:0x7d7d788043d1f40e&entry=gps",
        );
        assert_eq!(
            g9.text.as_deref(),
            Some("Artbox AS, Drammensveien 133, 0277 Oslo")
        );
        assert!(!g9.has_point && g9.ftid.is_some());
        let g4 = facts(
            "https://www.google.com/maps/place/Steak+no+Don/@34.59,133.75,17z/data=!4m6!3m5!1s0x3551565394d96b57:0x1c9bdbc5990908bf!8m2!3d34.5945482!4d133.7583428",
        );
        assert_eq!(g4.text.as_deref(), Some("Steak no Don"));
        assert!(g4.has_point && !has_address(g4.text.as_deref()));
        let g8 = facts("https://www.google.com/maps/search/39.920439,+116.331538?entry=s");
        assert!(g8.has_point && g8.ftid.is_none() && g8.text.is_none());
        assert!(has_address(Some("Artbox AS, Drammensveien 133")));
        assert!(!has_address(Some("Artbox AS, Oslo")));
        assert_eq!(lat_lon("59.9, 10.7"), Some((59.9, 10.7)));
        assert_eq!(lat_lon("Kiwi, Oslo"), None);
    }
}
