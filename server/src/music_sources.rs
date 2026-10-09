//! What the music sweep reads from a source (design `docs/design/21b-music-server-sweep.md` §1 and
//! §2.3): NRK's psapi catalog (roots, episode pages, playback manifests and metadata) and RSS
//! feeds. Pure parsers over the bytes a request returned; `music_sweep` does the fetching.
//!
//! Ported in behaviour from palchrb/vibb `pi/vibb/content.py` (`_episode_stub`, `_pick_image`,
//! `_manifest_url`, `_series`, `_parse_feed`, `_feed_episode_id`; MIT, Copyright (c) palchrb), and
//! reimplemented in Rust under GPL-3.0 with the changes 21b lists: keys checked, duplicate keys
//! dropped (the newest kept), no-end years read as "always", a feed's direction taken from its
//! dates, and a real XML parser (`quick-xml`) with entity bombs refused.

use chrono::{DateTime, Utc};
use quick_xml::events::Event;
use serde_json::Value;
use sha1::{Digest, Sha1};

/// Titles are cut to this many characters (21b §1).
pub const MAX_TITLE_CHARS: usize = 200;

/// An NRK episode or programme id that may become an item key and a manifest path.
pub fn valid_key(key: &str) -> bool {
    !key.is_empty()
        && key.len() <= 64
        && key
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

fn cut(text: &str) -> Option<String> {
    crate::music::cut_title(text, MAX_TITLE_CHARS)
}

/// A psapi/RFC 3339 time as UTC; a year of 2100 or later is "no end" (`None`).
pub fn until_time(text: &str) -> Option<DateTime<Utc>> {
    let time = DateTime::parse_from_rfc3339(text.trim()).ok()?;
    // The year as written: "2100-01-01T00:00:00+01:00" is 2099 in UTC.
    (chrono::Datelike::year(&time) < 2100).then(|| time.with_timezone(&Utc))
}

fn psapi_time(text: &str) -> Option<DateTime<Utc>> {
    DateTime::parse_from_rfc3339(text.trim())
        .ok()
        .map(|t| t.with_timezone(&Utc))
}

/// vibb's `_pick_image`: the smallest variant at least `want` px wide, else the largest.
pub fn pick_image(images: Option<&Value>, want: u64) -> Option<String> {
    let mut found: Vec<(u64, String)> = images?
        .as_array()?
        .iter()
        .filter_map(|image| {
            let url = image.get("url")?.as_str()?;
            Some((
                image.get("width").and_then(Value::as_u64).unwrap_or(0),
                url.to_string(),
            ))
        })
        .collect();
    found.sort_by_key(|(width, _)| *width);
    found
        .iter()
        .find(|(width, _)| *width >= want)
        .or(found.last())
        .map(|(_, url)| url.clone())
}

/// One episode in a psapi page.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Stub {
    pub key: String,
    pub title: Option<String>,
    pub published_at: Option<DateTime<Utc>>,
    pub duration_ms: Option<i64>,
    pub art: Option<String>,
    pub available_until: Option<DateTime<Utc>>,
}

/// A psapi episode page: its stubs in page order (repeated keys dropped) and the next page's href.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Page {
    pub stubs: Vec<Stub>,
    pub next: Option<String>,
}

fn stub_of(episode: &Value) -> Option<Stub> {
    let href = episode.pointer("/_links/self/href")?.as_str()?;
    let key = href.trim_end_matches('/').rsplit('/').next()?.to_string();
    if !valid_key(&key) {
        return None;
    }
    Some(Stub {
        key,
        title: episode
            .pointer("/titles/title")
            .and_then(Value::as_str)
            .and_then(cut),
        published_at: episode
            .get("date")
            .and_then(Value::as_str)
            .and_then(psapi_time),
        duration_ms: episode
            .get("durationInSeconds")
            .and_then(Value::as_i64)
            .map(|s| s.saturating_mul(1000)),
        art: pick_image(
            episode.get("squareImage").or_else(|| episode.get("image")),
            300,
        ),
        available_until: episode
            .pointer("/usageRights/to/date")
            .and_then(Value::as_str)
            .and_then(until_time),
    })
}

pub fn parse_page(body: &[u8]) -> Option<Page> {
    let json: Value = serde_json::from_slice(body).ok()?;
    let mut seen = std::collections::HashSet::new();
    let stubs = json
        .pointer("/_embedded/episodes")
        .and_then(Value::as_array)
        .map(|episodes| {
            episodes
                .iter()
                .filter_map(stub_of)
                .filter(|s| seen.insert(s.key.clone()))
                .collect()
        })
        .unwrap_or_default();
    let next = json
        .pointer("/_links/next/href")
        .and_then(Value::as_str)
        .map(str::to_string);
    Some(Page { stubs, next })
}

/// A catalog root: the show's title and its cover (the smallest square image of at least 512 px).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Root {
    pub title: Option<String>,
    pub image: Option<String>,
}

pub fn parse_root(body: &[u8]) -> Option<Root> {
    let json: Value = serde_json::from_slice(body).ok()?;
    let series = json.get("series")?;
    Some(Root {
        title: series
            .pointer("/titles/title")
            .and_then(Value::as_str)
            .and_then(cut),
        image: pick_image(
            series.get("squareImage").or_else(|| series.get("image")),
            512,
        ),
    })
}

/// A playback manifest (vibb `_manifest_url`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Manifest {
    Playable {
        url: String,
        hls: bool,
        available_until: Option<DateTime<Utc>>,
        duration_ms: Option<i64>,
    },
    /// No `playable` (rights, geo-block, not out yet) or an encrypted asset.
    NotPlayable,
}

/// "PT1H2M3S" -> milliseconds.
pub fn iso_duration_ms(text: &str) -> Option<i64> {
    let rest = text.trim().strip_prefix("PT")?;
    let mut total = 0f64;
    let mut number = String::new();
    for c in rest.chars() {
        match c {
            '0'..='9' | '.' => number.push(c),
            'H' | 'M' | 'S' => {
                let value: f64 = number.parse().ok()?;
                number.clear();
                total += value
                    * match c {
                        'H' => 3600.0,
                        'M' => 60.0,
                        _ => 1.0,
                    };
            }
            _ => return None,
        }
    }
    number.is_empty().then_some((total * 1000.0) as i64)
}

pub fn parse_manifest(body: &[u8]) -> Option<Manifest> {
    let json: Value = serde_json::from_slice(body).ok()?;
    let Some(asset) = json.pointer("/playable/assets/0") else {
        return Some(Manifest::NotPlayable);
    };
    if asset.get("encrypted").and_then(Value::as_bool) == Some(true) {
        return Some(Manifest::NotPlayable);
    }
    let Some(url) = asset.get("url").and_then(Value::as_str) else {
        return Some(Manifest::NotPlayable);
    };
    let hls = asset
        .get("format")
        .and_then(Value::as_str)
        .is_some_and(|f| f.eq_ignore_ascii_case("hls"))
        || reqwest::Url::parse(url).is_ok_and(|u| u.path().ends_with(".m3u8"));
    Some(Manifest::Playable {
        url: url.to_string(),
        hls,
        available_until: json
            .pointer("/availability/onDemand/to")
            .and_then(Value::as_str)
            .and_then(until_time),
        duration_ms: json
            .pointer("/playable/duration")
            .and_then(Value::as_str)
            .and_then(iso_duration_ms),
    })
}

/// A programme's playback metadata (vibb `_series`): its title and the next programme's id.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Metadata {
    pub title: Option<String>,
    pub duration_ms: Option<i64>,
    pub next: Option<String>,
}

pub fn parse_metadata(body: &[u8]) -> Option<Metadata> {
    let json: Value = serde_json::from_slice(body).ok()?;
    let preplay = json.get("preplay");
    let title = preplay
        .and_then(|p| p.pointer("/titles/subtitle"))
        .and_then(Value::as_str)
        .filter(|t| !t.trim().is_empty())
        .or_else(|| {
            preplay
                .and_then(|p| p.pointer("/titles/title"))
                .and_then(Value::as_str)
        })
        .and_then(cut);
    let next = json
        .pointer("/_links/next/href")
        .and_then(Value::as_str)
        .and_then(|href| href.trim_end_matches('/').rsplit('/').next())
        .filter(|id| valid_key(id))
        .map(str::to_string);
    Some(Metadata {
        title,
        duration_ms: json
            .get("duration")
            .and_then(Value::as_str)
            .and_then(iso_duration_ms),
        next,
    })
}

// ------------------------------------------------------------------------------------------------
// RSS
// ------------------------------------------------------------------------------------------------

/// The parser version: a listing built by another one ignores the stored validators, so a parser
/// fix reaches a feed that keeps answering 304 (21b §2.3, QA #19).
pub const PARSER_VERSION: i64 = 1;

/// One feed item with an enclosure.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FeedItem {
    pub key: String,
    pub title: Option<String>,
    /// The enclosure as written (its key uses it; the phones get it without tracking prefixes).
    pub enclosure: String,
    pub published_at: Option<DateTime<Utc>>,
    pub duration_ms: Option<i64>,
    pub art: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Feed {
    pub title: Option<String>,
    pub image: Option<String>,
    /// In document order.
    pub items: Vec<FeedItem>,
    /// Most adjacent dated items ascend in document order: the feed lists oldest first.
    pub oldest_first: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FeedError {
    NotFeed,
    NoItems,
}

/// `sha1(text)[:12]` (vibb `_feed_episode_id`).
pub fn short_sha1(text: &str) -> String {
    hex::encode(Sha1::digest(text.as_bytes()))[..12].to_string()
}

/// The body as UTF-8: by its byte order mark, else its XML declaration's encoding, else UTF-8.
pub fn decode_feed(bytes: &[u8]) -> String {
    if let Some((encoding, bom)) = encoding_rs::Encoding::for_bom(bytes) {
        return encoding
            .decode_without_bom_handling(&bytes[bom..])
            .0
            .into_owned();
    }
    let head = String::from_utf8_lossy(&bytes[..bytes.len().min(200)]);
    let declared = head
        .split_once("<?xml")
        .and_then(|(_, rest)| rest.split_once("?>"))
        .and_then(|(decl, _)| decl.split_once("encoding"))
        .and_then(|(_, rest)| {
            let rest = rest.trim_start().strip_prefix('=')?.trim_start();
            let quote = rest.chars().next().filter(|q| *q == '"' || *q == '\'')?;
            rest[1..].split(quote).next().map(str::to_string)
        });
    match declared.and_then(|label| encoding_rs::Encoding::for_label(label.as_bytes())) {
        Some(encoding) if encoding != encoding_rs::UTF_8 => {
            encoding.decode_without_bom_handling(bytes).0.into_owned()
        }
        _ => String::from_utf8_lossy(bytes).into_owned(),
    }
}

/// `itunes:duration`: seconds, m:ss or h:mm:ss.
pub fn feed_duration_ms(text: &str) -> Option<i64> {
    let parts: Vec<&str> = text.trim().split(':').collect();
    if parts.is_empty() || parts.len() > 3 {
        return None;
    }
    let mut seconds = 0f64;
    for part in &parts {
        seconds = seconds * 60.0 + part.trim().parse::<f64>().ok()?;
    }
    Some((seconds * 1000.0) as i64)
}

fn feed_time(text: &str) -> Option<DateTime<Utc>> {
    let text = text.trim();
    DateTime::parse_from_rfc2822(text)
        .or_else(|_| DateTime::parse_from_rfc3339(text))
        .ok()
        .map(|t| t.with_timezone(&Utc))
}

/// The name of an element as a lower-case `prefix:local` string.
fn tag(name: &str) -> String {
    name.to_ascii_lowercase()
}

#[derive(Default)]
struct ItemDraft {
    guid: Option<String>,
    title: Option<String>,
    enclosure: Option<String>,
    published: Option<String>,
    duration: Option<String>,
    art: Option<String>,
}

fn attribute(element: &quick_xml::events::BytesStart<'_>, name: &str) -> Option<String> {
    element.attributes().flatten().find_map(|attr| {
        (attr.key.as_ref().eq_ignore_ascii_case(name)).then(|| {
            attr.normalized_value(quick_xml::XmlVersion::Implicit1_0)
                .map(|v| v.into_owned())
                .unwrap_or_else(|_| attr.value.to_string())
        })
    })
}

/// Parses an RSS feed (vibb `_parse_feed`): the channel title and image (`itunes:image@href`, else
/// `image/url`) and every item with an enclosure URL. Item keys: `sha1(guid)[:12]` with the guid
/// trimmed (an empty one = none), else `sha1(enclosure)[:12]`; `guid_keys` (NRK's RSS fallback)
/// uses a valid guid itself, so keys equal psapi's. A repeated key keeps its first (newest)
/// occurrence. A DOCTYPE with an internal subset is refused (entity bombs); one without is ignored
/// (RSS 0.91). Only the five entities and numeric references are decoded. A body that breaks off
/// (a cut-off read) keeps what was complete.
pub fn parse_feed(bytes: &[u8], guid_keys: bool) -> Result<Feed, FeedError> {
    let text = decode_feed(bytes);
    let mut reader = quick_xml::Reader::from_str(&text);
    reader.config_mut().check_end_names = false;
    reader.config_mut().allow_dangling_amp = true;
    let mut stack: Vec<String> = Vec::new();
    let mut in_channel = false;
    let mut saw_channel = false;
    let mut title: Option<String> = None;
    let mut itunes_image: Option<String> = None;
    let mut classic_image: Option<String> = None;
    let mut item: Option<ItemDraft> = None;
    let mut items: Vec<(FeedItem, Option<DateTime<Utc>>)> = Vec::new();
    let mut seen = std::collections::HashSet::new();
    let mut buffer = String::new();

    let finish_item = |draft: ItemDraft,
                       items: &mut Vec<(FeedItem, Option<DateTime<Utc>>)>,
                       seen: &mut std::collections::HashSet<String>| {
        let Some(enclosure) = draft.enclosure.filter(|e| !e.trim().is_empty()) else {
            return;
        };
        let enclosure = enclosure.trim().to_string();
        let guid = draft
            .guid
            .map(|g| g.trim().to_string())
            .filter(|g| !g.is_empty());
        let key = match &guid {
            Some(guid) if guid_keys && valid_key(guid) => guid.clone(),
            Some(guid) => short_sha1(guid),
            None => short_sha1(&enclosure),
        };
        if !seen.insert(key.clone()) {
            return;
        }
        let published = draft.published.as_deref().and_then(feed_time);
        items.push((
            FeedItem {
                key,
                title: draft.title.as_deref().and_then(cut),
                enclosure,
                published_at: published,
                duration_ms: draft.duration.as_deref().and_then(feed_duration_ms),
                art: draft
                    .art
                    .map(|a| a.trim().to_string())
                    .filter(|a| !a.is_empty()),
            },
            published,
        ));
    };

    // A cut-off or broken tail ends the loop: what was complete is kept.
    while let Ok(event) = reader.read_event() {
        match event {
            Event::DocType(doctype) => {
                if doctype.xml10_content().contains('[') {
                    return Err(FeedError::NotFeed);
                }
            }
            Event::Start(element) => {
                let name = tag(element.name().as_ref());
                match name.as_str() {
                    "channel" => {
                        in_channel = true;
                        saw_channel = true;
                    }
                    "item" if in_channel => item = Some(ItemDraft::default()),
                    "enclosure" => {
                        if let Some(draft) = item.as_mut() {
                            draft.enclosure = draft.enclosure.take().or(attribute(&element, "url"));
                        }
                    }
                    "itunes:image" => {
                        let href = attribute(&element, "href");
                        match item.as_mut() {
                            Some(draft) => draft.art = draft.art.take().or(href),
                            None if in_channel => itunes_image = itunes_image.or(href),
                            None => {}
                        }
                    }
                    _ => {}
                }
                stack.push(name);
                buffer.clear();
            }
            Event::Empty(element) => {
                let name = tag(element.name().as_ref());
                match name.as_str() {
                    "enclosure" => {
                        if let Some(draft) = item.as_mut() {
                            draft.enclosure = draft.enclosure.take().or(attribute(&element, "url"));
                        }
                    }
                    "itunes:image" => {
                        let href = attribute(&element, "href");
                        match item.as_mut() {
                            Some(draft) => draft.art = draft.art.take().or(href),
                            None if in_channel => itunes_image = itunes_image.or(href),
                            None => {}
                        }
                    }
                    _ => {}
                }
            }
            Event::Text(text) => buffer.push_str(&text.xml10_content()),
            Event::CData(data) => buffer.push_str(&data.into_inner()),
            Event::GeneralRef(reference) => {
                let name = reference.xml10_content();
                match reference.resolve_char_ref() {
                    Ok(Some(c)) => buffer.push(c),
                    _ => match name.as_ref() {
                        "amp" => buffer.push('&'),
                        "lt" => buffer.push('<'),
                        "gt" => buffer.push('>'),
                        "quot" => buffer.push('"'),
                        "apos" => buffer.push('\''),
                        // Only the five and numeric references: anything else stays as written.
                        other => {
                            buffer.push('&');
                            buffer.push_str(other);
                            buffer.push(';');
                        }
                    },
                }
            }
            Event::End(_) => {
                let Some(name) = stack.pop() else {
                    continue;
                };
                let parent = stack.last().map(String::as_str);
                let value = std::mem::take(&mut buffer);
                match (name.as_str(), parent) {
                    ("item", _) => {
                        if let Some(draft) = item.take() {
                            finish_item(draft, &mut items, &mut seen);
                        }
                    }
                    ("channel", _) => in_channel = false,
                    ("title", Some("channel")) => title = title.or_else(|| cut(&value)),
                    ("url", Some("image"))
                        if stack.len() >= 2 && stack[stack.len() - 2] == "channel" =>
                    {
                        classic_image = classic_image.or(Some(value.trim().to_string()))
                    }
                    ("title", Some("item")) => {
                        if let Some(draft) = item.as_mut() {
                            draft.title = Some(value);
                        }
                    }
                    ("guid", Some("item")) => {
                        if let Some(draft) = item.as_mut() {
                            draft.guid = Some(value);
                        }
                    }
                    ("pubdate", Some("item")) => {
                        if let Some(draft) = item.as_mut() {
                            draft.published = Some(value);
                        }
                    }
                    ("itunes:duration", Some("item")) => {
                        if let Some(draft) = item.as_mut() {
                            draft.duration = Some(value);
                        }
                    }
                    _ => {}
                }
            }
            Event::Eof => break,
            _ => {}
        }
    }
    if !saw_channel {
        return Err(FeedError::NotFeed);
    }
    if items.is_empty() {
        return Err(FeedError::NoItems);
    }
    // The direction: the majority of adjacent pairs of dated items.
    let dated: Vec<DateTime<Utc>> = items.iter().filter_map(|(_, d)| *d).collect();
    let rising = dated.windows(2).filter(|w| w[1] > w[0]).count();
    let falling = dated.windows(2).filter(|w| w[1] < w[0]).count();
    Ok(Feed {
        title,
        image: itunes_image
            .or(classic_image)
            .filter(|i| !i.trim().is_empty()),
        items: items.into_iter().map(|(item, _)| item).collect(),
        oldest_first: rising > falling,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture(name: &str) -> Vec<u8> {
        std::fs::read(format!("testdata/music_sources/{name}")).unwrap()
    }

    #[test]
    fn psapi_pages_give_checked_stubs_and_the_next_page() {
        let page = parse_page(&fixture("psapi_podcast_episodes_desc.json")).unwrap();
        assert_eq!(page.stubs.len(), 3, "the repeated stub is dropped");
        let first = &page.stubs[0];
        assert_eq!(first.key, "l_59575a02-8559-4b80-975a-0285598b8073");
        assert_eq!(
            first.title.as_deref(),
            Some("Hvordan lader du mobilen perfekt?")
        );
        assert_eq!(first.duration_ms, Some(3_454_000));
        assert_eq!(first.available_until, None, "year 9999 = always");
        assert_eq!(
            first.published_at.unwrap().to_rfc3339(),
            "2026-10-09T09:30:00+00:00"
        );
        assert!(
            first
                .art
                .as_deref()
                .unwrap()
                .starts_with("https://gfx.nrk.no/")
        );
        assert_eq!(
            page.next.as_deref(),
            Some("/radio/catalog/podcast/abels_taarn/episodes?page=2&pageSize=50&sort=desc")
        );
        // A bad id and a 2100 end.
        let odd = br#"{"_embedded": {"episodes": [
            {"_links": {"self": {"href": "/x/../etc passwd"}}},
            {"_links": {"self": {"href": "/x/ok_1"}}, "usageRights": {"to": {"date": "2100-01-01T00:00:00+01:00"}}},
            {"_links": {"self": {"href": "/x/ok_2"}}, "usageRights": {"to": {"date": "2027-01-01T00:00:00+01:00"}}}
        ]}}"#;
        let page = parse_page(odd).unwrap();
        assert_eq!(
            page.stubs
                .iter()
                .map(|s| s.key.as_str())
                .collect::<Vec<_>>(),
            ["ok_1", "ok_2"]
        );
        assert_eq!(page.stubs[0].available_until, None);
        assert!(page.stubs[1].available_until.is_some());
        assert_eq!(page.next, None);
        // A series page (sort=asc, recorded): programme ids from `/radio/catalog/programs/<id>`.
        let series = parse_page(&fixture("psapi_series_episodes_asc.json")).unwrap();
        assert_eq!(
            series
                .stubs
                .iter()
                .map(|s| s.key.as_str())
                .collect::<Vec<_>>(),
            ["MKTT72550116", "MKTT72550216", "MKTT72550316"]
        );
        assert_eq!(series.next, None);
        assert!(series.stubs.iter().all(|s| s.available_until.is_none()));
    }

    #[test]
    fn roots_manifests_and_metadata_parse() {
        let root = parse_root(&fixture("psapi_podcast_root.json")).unwrap();
        assert_eq!(root.title.as_deref(), Some("Abels tårn"));
        assert!(root.image.as_deref().unwrap().contains("gfx.nrk.no"));
        match parse_manifest(&fixture("psapi_manifest_podcast.json")).unwrap() {
            Manifest::Playable {
                url,
                hls,
                available_until,
                ..
            } => {
                assert!(url.ends_with(".mp3"));
                assert!(!hls);
                assert_eq!(available_until, None);
            }
            other => panic!("{other:?}"),
        }
        match parse_manifest(&fixture("psapi_manifest_program.json")).unwrap() {
            Manifest::Playable {
                url,
                hls,
                duration_ms,
                ..
            } => {
                assert!(url.contains(".m3u8"));
                assert!(hls);
                assert_eq!(duration_ms, Some(3_300_000));
            }
            other => panic!("{other:?}"),
        }
        assert_eq!(
            parse_manifest(
                br#"{"playability": "nonPlayable", "nonPlayable": {"reason": "notyetpublished"}}"#
            ),
            Some(Manifest::NotPlayable)
        );
        assert_eq!(
            parse_manifest(
                br#"{"playable": {"assets": [{"url": "https://x/a.mp3", "encrypted": true}]}}"#
            ),
            Some(Manifest::NotPlayable)
        );
        let meta = parse_metadata(&fixture("psapi_metadata_program.json")).unwrap();
        assert_eq!(meta.next.as_deref(), Some("MKTT72550216"));
        assert_eq!(meta.title.as_deref(), Some("Et budskap fra de døde"));
        assert_eq!(iso_duration_ms("PT1H2M3S"), Some(3_723_000));
        assert_eq!(iso_duration_ms("P1D"), None);
    }

    #[test]
    fn nrks_rss_fallback_keeps_psapis_keys() {
        let feed = parse_feed(&fixture("nrk_podkast_fallback.rss"), true).unwrap();
        assert_eq!(feed.title.as_deref(), Some("Abels tårn"));
        assert!(
            feed.image
                .as_deref()
                .unwrap()
                .starts_with("https://gfx.nrk.no/")
        );
        assert_eq!(feed.items[0].key, "l_1714dacf-d103-4582-94da-cfd103558293");
        assert_eq!(feed.items[0].duration_ms, Some(3_423_000));
        assert!(!feed.oldest_first);
        let hashed = parse_feed(&fixture("nrk_podkast_fallback.rss"), false).unwrap();
        assert_eq!(
            hashed.items[0].key,
            short_sha1("l_1714dacf-d103-4582-94da-cfd103558293")
        );
    }

    #[test]
    fn feeds_are_parsed_carefully() {
        let feed = br#"<?xml version="1.0"?>
<!DOCTYPE rss PUBLIC "-//Netscape Communications//DTD RSS 0.91//EN" "http://my.netscape.com/publish/formats/rss-0.91.dtd">
<rss version="0.91" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel>
<title><![CDATA[Godnatt & eventyr]]></title>
<image><url>https://example.org/classic.jpg</url></image>
<item><title>Tre</title><guid>  </guid><enclosure url="https://example.org/3.mp3"/><pubDate>Wed, 03 Jan 2024 06:00:00 GMT</pubDate><itunes:duration>1:02:03</itunes:duration></item>
<item><title>To &amp; &#229;</title><guid>g-2</guid><enclosure url="https://example.org/2.mp3"/><pubDate>Tue, 02 Jan 2024 06:00:00 GMT</pubDate><itunes:image href="https://example.org/2.jpg"/></item>
<item><title>To igjen</title><guid>g-2</guid><enclosure url="https://example.org/2b.mp3"/></item>
<item><title>Uten lyd</title></item>
<item><title>En</title><enclosure url="https://example.org/1.mp3"/><pubDate>Mon, 01 Jan 2024 06:00:00 GMT</pubDate><itunes:duration>95</itunes:duration></item>
</channel></rss>"#;
        let parsed = parse_feed(feed, false).unwrap();
        assert_eq!(parsed.title.as_deref(), Some("Godnatt & eventyr"));
        assert_eq!(
            parsed.image.as_deref(),
            Some("https://example.org/classic.jpg")
        );
        assert_eq!(
            parsed.items.len(),
            3,
            "no enclosure, and the repeated guid, are skipped"
        );
        assert_eq!(
            parsed.items[0].key,
            short_sha1("https://example.org/3.mp3"),
            "an empty guid"
        );
        assert_eq!(parsed.items[1].key, short_sha1("g-2"));
        assert_eq!(parsed.items[1].title.as_deref(), Some("To & å"));
        assert_eq!(
            parsed.items[1].art.as_deref(),
            Some("https://example.org/2.jpg")
        );
        assert_eq!(parsed.items[0].duration_ms, Some(3_723_000));
        assert_eq!(parsed.items[2].duration_ms, Some(95_000));
        assert!(!parsed.oldest_first, "dates fall: newest first");

        let serial = br#"<rss><channel><title>Serie</title>
<item><enclosure url="https://e.org/1.mp3"/><pubDate>Mon, 01 Jan 2024 06:00:00 GMT</pubDate></item>
<item><enclosure url="https://e.org/x.mp3"/></item>
<item><enclosure url="https://e.org/2.mp3"/><pubDate>Tue, 02 Jan 2024 06:00:00 GMT</pubDate></item>
<item><enclosure url="https://e.org/3.mp3"/><pubDate>Wed, 03 Jan 2024 06:00:00 GMT</pubDate></item>
</channel></rss>"#;
        assert!(parse_feed(serial, false).unwrap().oldest_first);

        let bomb = br#"<?xml version="1.0"?><!DOCTYPE rss [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;">]><rss><channel><title>&b;</title><item><enclosure url="https://e.org/1.mp3"/></item></channel></rss>"#;
        assert_eq!(parse_feed(bomb, false), Err(FeedError::NotFeed));
        assert_eq!(
            parse_feed(b"<html><title>No</title></html>", false),
            Err(FeedError::NotFeed)
        );
        assert_eq!(
            parse_feed(
                b"<rss><channel><title>T</title><item><title>x</title></item></channel></rss>",
                false
            ),
            Err(FeedError::NoItems)
        );
        // An unknown entity stays as written; a feed cut off mid-item keeps the complete ones.
        let cut_off = b"<rss><channel><title>A &nbsp; B</title><item><enclosure url=\"https://e.org/1.mp3\"/></item><item><enclosure url=\"https://e.org/2";
        let parsed = parse_feed(cut_off, false).unwrap();
        assert_eq!(parsed.title.as_deref(), Some("A &nbsp; B"));
        assert_eq!(parsed.items.len(), 1);
    }

    #[test]
    fn non_utf8_feeds_are_decoded_by_their_declaration() {
        let mut latin1 =
            b"<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><rss><channel><title>Bl".to_vec();
        latin1.push(0xE5); // å
        latin1.extend_from_slice(
            b"b\xe6r</title><item><enclosure url=\"https://e.org/1.mp3\"/></item></channel></rss>",
        );
        assert_eq!(
            parse_feed(&latin1, false).unwrap().title.as_deref(),
            Some("Blåbær")
        );
        let mut bom = vec![0xEF, 0xBB, 0xBF];
        bom.extend_from_slice("<rss><channel><title>Ærlig</title><item><enclosure url=\"https://e.org/1.mp3\"/></item></channel></rss>".as_bytes());
        assert_eq!(
            parse_feed(&bom, false).unwrap().title.as_deref(),
            Some("Ærlig")
        );
        assert_eq!(feed_duration_ms("1:02"), Some(62_000));
        assert_eq!(feed_duration_ms("x"), None);
    }
}
