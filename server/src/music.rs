//! Vibb music, phase 1 (design `docs/design/21-vibb-music-phase1.md` at the monorepo root, with its
//! QA review and decisions; the kid's GUI is design 20): the server's half - the library
//! *definition* (categories, entries, the parent's own files) and what goes to each phone.
//!
//! - **Sources**: an NRK podcast or series link, an RSS feed, or "own files" uploaded here. The phone
//!   fetches NRK and RSS from the source like the vibb Pi; the server never proxies or stores them.
//!   Adding a link is the server's only fetch (one GET that must look like the source).
//! - **The library** of a phone is its ticked entries with their categories, files and covers
//!   ([device_library]), served by `GET /api/devices/music/library` and named in the policy by its
//!   version: 16 hex of the SHA-256 of the library JSON *without* its own `version` field (QA #4).
//!   Own files that aren't on disk (never backed up) stay listed as `missing` (QA #3).
//! - **Policy** `music` ([policy_music]): the version, mobile data, the volume cap and the Storytel
//!   generation - or `null` when it can't be read, never an empty library.

use std::collections::HashMap;
use std::future::Future;
use std::path::Path;
use std::pin::Pin;
use std::sync::Arc;
use std::time::Duration;

use serde::{Deserialize, Serialize};
use sha1::Sha1;
use sha2::{Digest, Sha256};

/// Own files on disk: `data/music_files/<entry id>/<random>.<ext>` - never in a backup.
pub const MUSIC_FILES_DIR: &str = "data/music_files";
/// Covers (parent uploads and own files' embedded art), a `photos::Store` with its own prune.
pub const MUSIC_COVERS_DIR: &str = "data/music_covers";

/// At most this many entries in the library.
pub const MAX_ENTRIES: i64 = 200;
/// At most this many files per own-files entry.
pub const MAX_FILES_PER_ENTRY: i64 = 300;
/// The largest own file accepted.
pub const MAX_FILE_BYTES: u64 = 1_000_000_000;
/// Uploads are refused while the data disk has less free than this.
pub const MIN_FREE_BYTES: u64 = 1_000_000_000;
/// A phone's library JSON may not grow past this (QA #4): ticks and uploads beyond it are refused.
pub const MAX_LIBRARY_BYTES: usize = 3_000_000;
/// One upload request (several files) - each file is at most [MAX_FILE_BYTES].
pub const MAX_UPLOAD_REQUEST_BYTES: usize = 4_000_000_000;
/// Entry names (the carousel title) and category names (the tile label).
pub const MAX_NAME_CHARS: usize = 60;
pub const MAX_CATEGORY_NAME_CHARS: usize = 20;
/// Tag text kept from an own file.
const MAX_TAG_CHARS: usize = 200;
/// The add check: one GET, at most this long, reading at most this much of the body.
pub const FETCH_TIMEOUT: Duration = Duration::from_secs(10);
pub const MAX_FEED_BYTES: usize = 5_000_000;
/// NRK's programme API (the same one the vibb Pi and the music app use).
pub const PSAPI: &str = "https://psapi.nrk.no";

/// Offline depth choices: value and label (`cache`: -1 all, 0 none, N newest).
pub const CACHE_CHOICES: [(i64, &str); 7] = [
    (0, "None"),
    (1, "1"),
    (3, "3"),
    (5, "5"),
    (10, "10"),
    (20, "20"),
    (-1, "All"),
];
pub const DEFAULT_CACHE: i64 = 5;
/// Play orders: value and label.
pub const ORDERS: [(&str, &str); 3] = [
    (
        "auto",
        "Natural (podcasts newest first, series and own files from the start)",
    ),
    ("newest_first", "Newest first"),
    ("oldest_first", "Oldest first"),
];
/// The volume cap choices in percent; `None` = off (the default, decision after QA review).
pub const VOLUME_CAPS: [i64; 4] = [90, 80, 70, 60];

pub fn valid_cache(cache: i64) -> bool {
    CACHE_CHOICES.iter().any(|(c, _)| *c == cache)
}

pub fn valid_order(order: &str) -> bool {
    ORDERS.iter().any(|(o, _)| *o == order)
}

/// A stored cap that isn't one of the choices is sent as off.
pub fn volume_cap(stored: Option<i64>) -> Option<i64> {
    stored.filter(|pct| VOLUME_CAPS.contains(pct))
}

pub fn icon_known(key: &str) -> bool {
    crate::music_icons::ICONS.iter().any(|(k, _)| *k == key)
}

/// The tile colour of a colour key.
pub fn color_hex(key: &str) -> Option<&'static str> {
    crate::music_icons::COLORS
        .iter()
        .find(|(k, _)| *k == key)
        .map(|(_, hex)| *hex)
}

/// A name the parent typed: trimmed, inner whitespace collapsed, 1..=`max` characters, no control
/// characters. `None` when nothing usable is left.
pub fn clean_name(input: &str, max: usize) -> Option<String> {
    let collapsed = input.split_whitespace().collect::<Vec<_>>().join(" ");
    if collapsed.is_empty() || collapsed.chars().count() > max {
        return None;
    }
    if collapsed.chars().any(char::is_control) {
        return None;
    }
    Some(collapsed)
}

/// A title from a feed or a tag: like [clean_name], but cut to `max` instead of refused.
pub fn cut_title(input: &str, max: usize) -> Option<String> {
    let collapsed: String = input
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .chars()
        .filter(|c| !c.is_control())
        .take(max)
        .collect();
    (!collapsed.is_empty()).then_some(collapsed)
}

// ------------------------------------------------------------------------------------------------
// Links
// ------------------------------------------------------------------------------------------------

/// What a pasted link is, normalised.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Link {
    /// `radio.nrk.no/podkast/<slug>[/<episode>]` - the whole podcast.
    NrkPodcast { slug: String },
    /// `radio.nrk.no/serie/<slug>[/<programId>]` - the whole series, or from that programme on.
    NrkSeries {
        slug: String,
        program: Option<String>,
    },
    /// Any other http(s) URL - must turn out to be an RSS feed.
    Rss { url: String },
}

fn nrk_slug(segment: &str) -> Option<String> {
    let ok = !segment.is_empty()
        && segment.len() <= 100
        && segment
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_');
    ok.then(|| segment.to_ascii_lowercase())
}

fn nrk_program(segment: &str) -> Option<String> {
    let ok = !segment.is_empty()
        && segment.len() <= 40
        && segment
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_');
    ok.then(|| segment.to_string())
}

impl Link {
    /// The normalised URL stored as `music_entries.target` (unique).
    pub fn target(&self) -> String {
        match self {
            Link::NrkPodcast { slug } => format!("https://radio.nrk.no/podkast/{slug}"),
            Link::NrkSeries {
                slug,
                program: None,
            } => format!("https://radio.nrk.no/serie/{slug}"),
            Link::NrkSeries {
                slug,
                program: Some(program),
            } => format!("https://radio.nrk.no/serie/{slug}/{program}"),
            Link::Rss { url } => url.clone(),
        }
    }

    pub fn source(&self) -> &'static str {
        match self {
            Link::Rss { .. } => "rss",
            _ => "nrk",
        }
    }
}

/// Parses a pasted link. `Err` is the parent-facing reason.
pub fn parse_link(input: &str) -> Result<Link, &'static str> {
    let trimmed = input.trim();
    if trimmed.is_empty() {
        return Err("Paste a link first.");
    }
    if trimmed.len() > 2000 || trimmed.chars().any(|c| c.is_control() || c.is_whitespace()) {
        return Err("That doesn't look like a link.");
    }
    let lower = trimmed.to_ascii_lowercase();
    let with_scheme = if lower.starts_with("http://") || lower.starts_with("https://") {
        trimmed.to_string()
    } else if lower.contains("://") {
        return Err("Only http and https links work.");
    } else {
        format!("https://{trimmed}")
    };
    let mut url =
        reqwest::Url::parse(&with_scheme).map_err(|_| "That doesn't look like a link.")?;
    if !matches!(url.scheme(), "http" | "https") {
        return Err("Only http and https links work.");
    }
    let Some(host) = url.host_str().map(str::to_string) else {
        return Err("That doesn't look like a link.");
    };
    if !url.username().is_empty() || url.password().is_some() {
        return Err("Leave the user name and password out of the link.");
    }
    if host == "radio.nrk.no" {
        let segments: Vec<&str> = url
            .path_segments()
            .map(|s| s.filter(|p| !p.is_empty()).collect())
            .unwrap_or_default();
        const NOT_NRK: &str = "That NRK link is neither a podcast (radio.nrk.no/podkast/...) nor a \
                               series (radio.nrk.no/serie/...).";
        return match segments.as_slice() {
            ["podkast", slug, ..] => Ok(Link::NrkPodcast {
                slug: nrk_slug(slug).ok_or(NOT_NRK)?,
            }),
            ["serie", slug] | ["serie", slug, "sesong", _] => Ok(Link::NrkSeries {
                slug: nrk_slug(slug).ok_or(NOT_NRK)?,
                program: None,
            }),
            ["serie", slug, "sesong", _, program] | ["serie", slug, program] => {
                Ok(Link::NrkSeries {
                    slug: nrk_slug(slug).ok_or(NOT_NRK)?,
                    program: Some(nrk_program(program).ok_or(NOT_NRK)?),
                })
            }
            _ => Err(NOT_NRK),
        };
    }
    url.set_fragment(None);
    Ok(Link::Rss {
        url: url.to_string(),
    })
}

/// vibb's `state_key`: the NRK podcast slug, else the first 12 hex of SHA-1 of the target. The
/// music app keys positions and downloads by it, like the Pi's bookmarks.
pub fn state_key(target: &str) -> String {
    if let Some(rest) = target
        .strip_prefix("https://radio.nrk.no/podkast/")
        .or_else(|| target.strip_prefix("http://radio.nrk.no/podkast/"))
    {
        let slug: String = rest
            .chars()
            .take_while(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '_')
            .collect();
        if !slug.is_empty() {
            return slug.to_ascii_lowercase();
        }
    }
    hex::encode(Sha1::digest(target.as_bytes()))[..12].to_string()
}

/// A new own-files entry's key: "own-" and 12 random hex digits - unique over time, unlike the
/// id, which a restored database hands out again (qa-21-step1-code #7).
pub fn new_own_key() -> String {
    format!("own-{}", &crate::security::generate_device_token()[..12])
}

/// "NRK podcast", "Own files", ... for the parent's pages.
pub fn kind_label(source: &str, target: Option<&str>) -> &'static str {
    match source {
        "own" => "Own files",
        "rss" => "Podcast (RSS)",
        "nrk" if target.is_some_and(|t| t.contains("/serie/")) => "NRK series",
        "nrk" => "NRK podcast",
        "storytel" => "Storytel",
        "spotify" => "Spotify",
        _ => "Other",
    }
}

/// The category kind a new entry of this source goes into (design 20: NRK/RSS -> podcasts, own
/// files and Spotify -> music, Storytel -> audiobooks).
pub fn default_kind(source: &str) -> &'static str {
    match source {
        "nrk" | "rss" => "podcast",
        "storytel" => "audiobook",
        _ => "music",
    }
}

/// The category a new entry of `source` goes into unless the parent picks one: the first whose
/// `default_kind` is the source's ([default_kind]), else the first category.
pub fn default_category<'a>(
    categories: &'a [MusicCategory],
    source: &str,
) -> Option<&'a MusicCategory> {
    let kind = default_kind(source);
    categories
        .iter()
        .find(|c| c.default_kind.as_deref() == Some(kind))
        .or_else(|| categories.first())
}

// ------------------------------------------------------------------------------------------------
// The add check (the server's only fetch)
// ------------------------------------------------------------------------------------------------

/// One GET's answer: the status and the body - its first `limit` bytes (`truncated` = there was
/// more). A bounded read, not a refusal (qa-21-step1-code #10): the caller decides what a prefix is
/// good for - the add check needs only a feed's channel and first episode, a sweep (design 21b)
/// can ask with its own limit and see that it got everything.
pub struct Fetched {
    pub status: u16,
    pub body: Vec<u8>,
    pub truncated: bool,
}

#[derive(Debug)]
pub enum FetchError {
    Network(String),
}

/// The server's HTTP GET of a music source - `AppState.music_fetch`, canned in the tests.
pub trait Fetch: Send + Sync {
    /// At most `limit` bytes of the body are read.
    fn get<'a>(
        &'a self,
        url: &'a str,
        limit: usize,
    ) -> Pin<Box<dyn Future<Output = Result<Fetched, FetchError>> + Send + 'a>>;
}

/// The real one: [FETCH_TIMEOUT] overall; reading stops at `limit` bytes.
pub struct HttpFetch;

impl Fetch for HttpFetch {
    fn get<'a>(
        &'a self,
        url: &'a str,
        limit: usize,
    ) -> Pin<Box<dyn Future<Output = Result<Fetched, FetchError>> + Send + 'a>> {
        Box::pin(async move {
            let client = reqwest::Client::builder()
                .user_agent("kid-phone-server (self-hosted; vibb music library)")
                .timeout(FETCH_TIMEOUT)
                .build()
                .map_err(|e| FetchError::Network(e.to_string()))?;
            let mut response = client
                .get(url)
                .send()
                .await
                .map_err(|e| FetchError::Network(e.to_string()))?;
            let status = response.status().as_u16();
            let mut body = Vec::new();
            let mut truncated = false;
            while let Some(chunk) = response
                .chunk()
                .await
                .map_err(|e| FetchError::Network(e.to_string()))?
            {
                let room = limit - body.len();
                if chunk.len() > room {
                    body.extend_from_slice(&chunk[..room]);
                    truncated = true;
                    break; // the rest is never downloaded: dropping the response closes it
                }
                body.extend_from_slice(&chunk);
            }
            Ok(Fetched {
                status,
                body,
                truncated,
            })
        })
    }
}

/// Why a link wasn't added.
#[derive(Debug, PartialEq, Eq)]
pub enum CheckError {
    NotFound,
    NotFeed,
    NoItems,
    Status(u16),
    TooBig,
    Network(String),
}

impl CheckError {
    pub fn message(&self) -> String {
        match self {
            CheckError::NotFound => {
                "NRK doesn't know that podcast or series - check the link.".to_string()
            }
            CheckError::NotFeed => {
                "That link isn't a podcast feed (RSS). Paste the feed's address \
                                    (it often ends in .rss or .xml), or an NRK radio link."
                    .to_string()
            }
            CheckError::NoItems => "That feed has no episodes with audio.".to_string(),
            CheckError::Status(code) => {
                format!("The site answered {code} - check the link, or try again later.")
            }
            CheckError::TooBig => {
                "That feed's first 5 MB hold no episode with audio - not added.".to_string()
            }
            CheckError::Network(err) => {
                format!("Couldn't reach the site ({err}) - try again later.")
            }
        }
    }
}

/// The title of a link, after one GET that must look like its source: psapi's catalog entry for
/// NRK, an RSS feed with at least one enclosure for anything else.
pub async fn check_link(fetch: &dyn Fetch, link: &Link) -> Result<String, CheckError> {
    let (url, slug) = match link {
        Link::NrkPodcast { slug } => (format!("{PSAPI}/radio/catalog/podcast/{slug}"), slug),
        Link::NrkSeries { slug, .. } => (format!("{PSAPI}/radio/catalog/series/{slug}"), slug),
        Link::Rss { url } => {
            // The channel's title and one episode come first in a feed: its first
            // [MAX_FEED_BYTES] decide, however long the whole feed is (qa-21-step1-code #10).
            let fetched = fetch.get(url, MAX_FEED_BYTES).await.map_err(fetch_error)?;
            if fetched.status != 200 {
                return Err(CheckError::Status(fetched.status));
            }
            return match rss_title(&fetched.body) {
                Err(CheckError::NoItems | CheckError::NotFeed) if fetched.truncated => {
                    Err(CheckError::TooBig)
                }
                other => other,
            };
        }
    };
    let fetched = fetch.get(&url, MAX_FEED_BYTES).await.map_err(fetch_error)?;
    match fetched.status {
        200 => {}
        404 | 410 => return Err(CheckError::NotFound),
        other => return Err(CheckError::Status(other)),
    }
    let json: serde_json::Value =
        serde_json::from_slice(&fetched.body).map_err(|_| CheckError::NotFound)?;
    Ok(nrk_title(&json).unwrap_or_else(|| slug.clone()))
}

fn fetch_error(err: FetchError) -> CheckError {
    match err {
        FetchError::Network(e) => CheckError::Network(e),
    }
}

/// The podcast's or series' title in a psapi catalog answer.
pub fn nrk_title(json: &serde_json::Value) -> Option<String> {
    [
        &["series", "titles", "title"][..],
        &["titles", "title"][..],
        &["series", "title"][..],
        &["title"][..],
    ]
    .iter()
    .find_map(|path| {
        path.iter()
            .try_fold(json, |value, key| value.get(*key))
            .and_then(|v| v.as_str())
            .and_then(|t| cut_title(t, MAX_NAME_CHARS))
    })
}

/// The text inside an XML element body: CDATA kept as is, the five entities and numeric
/// references decoded, tags dropped.
fn xml_text(raw: &str) -> String {
    let mut out = String::new();
    let mut rest = raw;
    while !rest.is_empty() {
        if let Some(after) = rest.strip_prefix("<![CDATA[") {
            let end = after.find("]]>").unwrap_or(after.len());
            out.push_str(&after[..end]);
            rest = after.get(end + 3..).unwrap_or("");
        } else if rest.starts_with('<') {
            let end = rest.find('>').map_or(rest.len(), |i| i + 1);
            rest = &rest[end..];
        } else if rest.starts_with('&') {
            let end = rest.find(';').filter(|i| *i <= 10);
            let decoded = end.and_then(|i| {
                let name = &rest[1..i];
                match name {
                    "amp" => Some('&'),
                    "lt" => Some('<'),
                    "gt" => Some('>'),
                    "quot" => Some('"'),
                    "apos" => Some('\''),
                    _ => name
                        .strip_prefix("#x")
                        .or_else(|| name.strip_prefix("#X"))
                        .and_then(|h| u32::from_str_radix(h, 16).ok())
                        .or_else(|| name.strip_prefix('#').and_then(|d| d.parse().ok()))
                        .and_then(char::from_u32),
                }
            });
            match (decoded, end) {
                (Some(c), Some(i)) => {
                    out.push(c);
                    rest = &rest[i + 1..];
                }
                _ => {
                    out.push('&');
                    rest = &rest[1..];
                }
            }
        } else {
            let end = rest
                .find(['<', '&'])
                .unwrap_or(rest.len())
                .max(rest.chars().next().map_or(1, char::len_utf8));
            out.push_str(&rest[..end]);
            rest = &rest[end..];
        }
    }
    out
}

/// The index just after the opening tag `<name` (followed by `>`, `/` or whitespace) at or after
/// `from`, and where that tag starts.
fn find_tag(text: &str, name: &str, from: usize) -> Option<(usize, usize)> {
    let needle = format!("<{name}");
    let mut at = from;
    while let Some(i) = text.get(at..)?.find(&needle) {
        let start = at + i;
        let after = start + needle.len();
        match text[after..].chars().next() {
            Some('>') | Some('/') => return Some((start, after)),
            Some(c) if c.is_whitespace() => return Some((start, after)),
            _ => at = after,
        }
    }
    None
}

/// An RSS feed's channel title, if the body is an RSS feed with at least one item that has an
/// enclosure with a URL (vibb plays only those). Lenient on purpose: it only has to tell a feed from
/// a web page; the music app parses it properly.
pub fn rss_title(body: &[u8]) -> Result<String, CheckError> {
    let text = String::from_utf8_lossy(body);
    let Some((_, channel)) = find_tag(&text, "channel", 0) else {
        return Err(CheckError::NotFeed);
    };
    let first_item = find_tag(&text, "item", channel).map(|(start, _)| start);
    let title = find_tag(&text, "title", channel)
        .filter(|(start, _)| first_item.is_none_or(|item| *start < item))
        .and_then(|(_, after)| {
            let open_end = after + text[after..].find('>')? + 1;
            let close = open_end + text[open_end..].find("</title>")?;
            cut_title(&xml_text(&text[open_end..close]), MAX_NAME_CHARS)
        });
    let mut items = 0;
    let mut at = channel;
    while let Some((start, after)) = find_tag(&text, "item", at) {
        let end = text[after..]
            .find("</item>")
            .map_or(text.len(), |i| after + i);
        let item = &text[start..end];
        let has_audio = find_tag(item, "enclosure", 0).is_some_and(|(_, a)| {
            let tag_end = item[a..].find('>').map_or(item.len(), |i| a + i);
            item[a..tag_end].contains("url=")
        });
        if has_audio {
            items += 1;
        }
        at = end.max(after);
    }
    if items == 0 {
        return Err(CheckError::NoItems);
    }
    Ok(title.unwrap_or_else(|| "Podcast".to_string()))
}

// ------------------------------------------------------------------------------------------------
// Rows
// ------------------------------------------------------------------------------------------------

#[derive(sqlx::FromRow, Clone, Debug)]
pub struct MusicCategory {
    pub id: i64,
    pub name: String,
    pub icon: String,
    pub color: String,
    pub sort: i64,
    pub default_kind: Option<String>,
}

#[derive(sqlx::FromRow, Clone, Debug)]
pub struct MusicEntry {
    pub id: i64,
    pub name: String,
    pub category_id: i64,
    pub source: String,
    pub target: Option<String>,
    pub key: String,
    pub play_order: String,
    pub cache: i64,
    pub resume: bool,
    pub cover_hash: Option<String>,
    pub sort: i64,
}

#[derive(sqlx::FromRow, Clone, Debug)]
pub struct MusicFile {
    pub id: i64,
    pub entry_id: i64,
    pub original_name: String,
    pub size: i64,
    pub sha256: String,
    pub title: Option<String>,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub track_no: Option<i64>,
    pub duration_ms: Option<i64>,
    pub art_hash: Option<String>,
    pub sort: i64,
    pub missing: bool,
}

impl MusicFile {
    /// The tag's title, else the file name without its extension (vibb).
    pub fn display_title(&self) -> String {
        self.title.clone().unwrap_or_else(|| {
            Path::new(&self.original_name)
                .file_stem()
                .map(|s| s.to_string_lossy().into_owned())
                .unwrap_or_else(|| self.original_name.clone())
        })
    }
}

/// Own files in play order (`(id, track_no, original_name)` -> ids): track order when every file
/// has a track number, else by file name (vibb; QA #9), ties by id.
pub fn file_order(files: &[(i64, Option<i64>, String)]) -> Vec<i64> {
    let mut sorted: Vec<&(i64, Option<i64>, String)> = files.iter().collect();
    if !files.is_empty() && files.iter().all(|(_, track, _)| track.is_some()) {
        sorted.sort_by_key(|f| (f.1, f.2.to_lowercase(), f.0));
    } else {
        sorted.sort_by_key(|f| (f.2.to_lowercase(), f.0));
    }
    sorted.into_iter().map(|(id, _, _)| *id).collect()
}

/// Rewrites `music_files.sort` of one entry ([file_order]).
pub async fn resort_files(db: &sqlx::SqlitePool, entry_id: i64) -> Result<(), sqlx::Error> {
    let rows: Vec<(i64, Option<i64>, String)> =
        sqlx::query_as("SELECT id, track_no, original_name FROM music_files WHERE entry_id = ?")
            .bind(entry_id)
            .fetch_all(db)
            .await?;
    let mut tx = db.begin().await?;
    for (index, id) in file_order(&rows).into_iter().enumerate() {
        sqlx::query("UPDATE music_files SET sort = ? WHERE id = ?")
            .bind(index as i64)
            .bind(id)
            .execute(&mut *tx)
            .await?;
    }
    tx.commit().await
}

// ------------------------------------------------------------------------------------------------
// The library a phone gets
// ------------------------------------------------------------------------------------------------

#[derive(Serialize, Debug, Clone, PartialEq, Eq)]
pub struct LibCategory {
    pub id: i64,
    pub name: String,
    pub icon: String,
    pub color: String,
    pub sort: i64,
}

#[derive(Serialize, Debug, Clone, PartialEq, Eq)]
pub struct LibEntry {
    pub id: i64,
    pub key: String,
    pub name: String,
    pub category: i64,
    pub source: String,
    pub target: Option<String>,
    pub order: String,
    pub cache: i64,
    pub resume: bool,
    pub cover: Option<String>,
    pub sort: i64,
}

#[derive(Serialize, Debug, Clone, PartialEq, Eq)]
pub struct LibFile {
    pub id: i64,
    pub entry: i64,
    pub title: String,
    pub artist: Option<String>,
    pub track: Option<i64>,
    pub duration_ms: Option<i64>,
    pub size: i64,
    pub sha256: String,
    pub art: Option<String>,
    pub sort: i64,
    /// Not on this server's disk (a restore without the audio): still listed, so the phones keep
    /// their copy, but not downloadable (QA #3).
    pub missing: bool,
}

/// What is hashed: the library without its own version (QA #4).
#[derive(Serialize)]
struct LibraryBody<'a> {
    v: u32,
    categories: &'a [LibCategory],
    entries: &'a [LibEntry],
    files: &'a [LibFile],
    covers: &'a [String],
}

/// What is served (`server/testdata/music_library.json` pins the shape).
#[derive(Serialize)]
struct LibraryDocument<'a> {
    v: u32,
    version: &'a str,
    categories: &'a [LibCategory],
    entries: &'a [LibEntry],
    files: &'a [LibFile],
    covers: &'a [String],
}

pub const LIBRARY_FORMAT: u32 = 1;

/// A phone's library, ready to serve.
#[derive(Debug, Clone)]
pub struct Library {
    pub version: String,
    /// The served document (cheap to clone: the cache hands it out).
    pub json: axum::body::Bytes,
    /// The categories it lists (the import's size estimate, 21a).
    pub category_ids: Vec<i64>,
}

impl Library {
    pub fn build(
        categories: Vec<LibCategory>,
        entries: Vec<LibEntry>,
        files: Vec<LibFile>,
    ) -> Library {
        let category_ids = categories.iter().map(|c| c.id).collect();
        let mut covers: Vec<String> = entries
            .iter()
            .filter_map(|e| e.cover.clone())
            .chain(files.iter().filter_map(|f| f.art.clone()))
            .collect();
        covers.sort();
        covers.dedup();
        let body = serde_json::to_vec(&LibraryBody {
            v: LIBRARY_FORMAT,
            categories: &categories,
            entries: &entries,
            files: &files,
            covers: &covers,
        })
        .expect("the library always serializes");
        let version = hex::encode(Sha256::digest(&body))[..16].to_string();
        let json = serde_json::to_vec(&LibraryDocument {
            v: LIBRARY_FORMAT,
            version: &version,
            categories: &categories,
            entries: &entries,
            files: &files,
            covers: &covers,
        })
        .expect("the library always serializes");
        Library {
            version,
            json: json.into(),
            category_ids,
        }
    }
}

/// The entries in a phone's library: its ticks (plus `extra`, an entry about to be ticked - the
/// size check), phase-1 sources only.
const LIBRARY_ENTRIES: &str = "SELECT id, name, category_id, source, target, key, play_order, \
     cache, resume, cover_hash, sort FROM music_entries \
     WHERE source IN ('nrk', 'rss', 'own') AND (id IN \
       (SELECT entry_id FROM device_music_entries WHERE device_id = ?) OR id = ?) \
     ORDER BY sort, id";

/// `device_id`'s library, with `extra` added (an entry about to be ticked; `None` = as is), read
/// on `db` - a pooled connection or a transaction's (the import ticks inside one, 21a).
/// `Ok(None)` = nothing ticked. A database error is an error - the caller sends `music: null`.
pub async fn library_for(
    db: &mut sqlx::SqliteConnection,
    device_id: i64,
    extra: Option<i64>,
) -> Result<Option<Library>, sqlx::Error> {
    #[cfg(test)]
    LIBRARY_BUILDS.with(|builds| builds.set(builds.get() + 1));
    let extra = extra.unwrap_or(-1);
    let entries: Vec<MusicEntry> = sqlx::query_as(LIBRARY_ENTRIES)
        .bind(device_id)
        .bind(extra)
        .fetch_all(&mut *db)
        .await?;
    if entries.is_empty() {
        return Ok(None);
    }
    let category_ids: std::collections::HashSet<i64> =
        entries.iter().map(|e| e.category_id).collect();
    let categories: Vec<LibCategory> =
        sqlx::query_as::<_, MusicCategory>("SELECT * FROM music_categories ORDER BY sort, id")
            .fetch_all(&mut *db)
            .await?
            .into_iter()
            .filter(|c| category_ids.contains(&c.id))
            .map(|c| LibCategory {
                id: c.id,
                name: c.name,
                icon: c.icon,
                color: c.color,
                sort: c.sort,
            })
            .collect();
    let own: std::collections::HashSet<i64> = entries
        .iter()
        .filter(|e| e.source == "own")
        .map(|e| e.id)
        .collect();
    let mut files: Vec<LibFile> = Vec::new();
    if !own.is_empty() {
        let rows: Vec<MusicFile> = sqlx::query_as(
            "SELECT id, entry_id, original_name, size, sha256, title, artist, album, \
             track_no, duration_ms, art_hash, sort, missing FROM music_files \
             WHERE entry_id IN (SELECT entry_id FROM device_music_entries WHERE device_id = ?) \
                OR entry_id = ? \
             ORDER BY entry_id, sort, id",
        )
        .bind(device_id)
        .bind(extra)
        .fetch_all(&mut *db)
        .await?;
        files = rows
            .into_iter()
            .filter(|f| own.contains(&f.entry_id))
            .map(|f| LibFile {
                id: f.id,
                entry: f.entry_id,
                title: f.display_title(),
                artist: f.artist.clone(),
                track: f.track_no,
                duration_ms: f.duration_ms,
                size: f.size,
                sha256: f.sha256.clone(),
                art: f.art_hash.clone(),
                sort: f.sort,
                missing: f.missing,
            })
            .collect();
    }
    let entries = entries
        .into_iter()
        .map(|e| LibEntry {
            id: e.id,
            key: e.key,
            name: e.name,
            category: e.category_id,
            target: e.target,
            order: e.play_order,
            cache: if e.source == "own" { -1 } else { e.cache },
            resume: e.resume,
            cover: e.cover_hash,
            sort: e.sort,
            source: e.source,
        })
        .collect();
    Ok(Some(Library::build(categories, entries, files)))
}

#[cfg(test)]
thread_local! {
    /// How many libraries this thread built ([library_for]) - the tests count builds with it (a
    /// `#[tokio::test]` runs on one thread, so the count is the test's own).
    pub static LIBRARY_BUILDS: std::cell::Cell<usize> = const { std::cell::Cell::new(0) };
}

/// Each phone's built library, kept under the library revision it was built at (migration 0050,
/// qa-21-step1-code #3): the policy poll, the library route and the device card reuse it until
/// something a library is built from changes. The revision moves by trigger on every write to
/// `music_entries`, `music_categories`, `music_files` and `device_music_entries`; a new source of
/// library data either gets the same triggers or calls [bump_library_revision]. In memory only,
/// one library per phone.
#[derive(Default)]
pub struct LibraryCache(std::sync::Mutex<HashMap<i64, KeptLibrary>>);

/// The revision a library was built at, and the library (`None` = nothing ticked).
type KeptLibrary = (i64, Option<Arc<Library>>);

impl LibraryCache {
    fn get(&self, device_id: i64, revision: i64) -> Option<Option<Arc<Library>>> {
        let cache = self.0.lock().unwrap_or_else(|e| e.into_inner());
        cache
            .get(&device_id)
            .filter(|(at, _)| *at == revision)
            .map(|(_, library)| library.clone())
    }

    fn put(&self, device_id: i64, revision: i64, library: Option<Arc<Library>>) {
        let mut cache = self.0.lock().unwrap_or_else(|e| e.into_inner());
        // Never replace a newer build with an older one (two requests racing a change).
        if cache.get(&device_id).is_none_or(|(at, _)| *at <= revision) {
            cache.insert(device_id, (revision, library));
        }
    }
}

/// The library revision now (migration 0050).
pub async fn library_revision(db: &mut sqlx::SqliteConnection) -> Result<i64, sqlx::Error> {
    sqlx::query_scalar("SELECT revision FROM music_library_revision WHERE id = 1")
        .fetch_one(&mut *db)
        .await
}

/// Moves the library revision by hand - for a writer whose table has no revision triggers.
#[allow(dead_code)] // for the sweeper's tables (design 21b); the step-1 tables use triggers
pub async fn bump_library_revision(db: &mut sqlx::SqliteConnection) -> Result<(), sqlx::Error> {
    sqlx::query("UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1")
        .execute(&mut *db)
        .await
        .map(|_| ())
}

/// `device_id`'s library through `cache`: the kept one while the revision hasn't moved, else built
/// - in one read transaction, so the revision and the data it is kept under agree.
pub async fn cached_library(
    db: &sqlx::SqlitePool,
    cache: &LibraryCache,
    device_id: i64,
) -> Result<Option<Arc<Library>>, sqlx::Error> {
    let mut tx = db.begin().await?;
    let revision = library_revision(&mut tx).await?;
    if let Some(kept) = cache.get(device_id, revision) {
        return Ok(kept);
    }
    let library = library_for(&mut tx, device_id, None).await?.map(Arc::new);
    tx.commit().await?;
    cache.put(device_id, revision, library.clone());
    Ok(library)
}

/// `device_id`'s library as it is now ([library_for]), built fresh.
#[cfg(test)]
pub async fn device_library(
    db: &sqlx::SqlitePool,
    device_id: i64,
) -> Result<Option<Library>, sqlx::Error> {
    let mut conn = db.acquire().await?;
    library_for(&mut conn, device_id, None).await
}

/// Whether ticking `entry_id` keeps `device_id`'s library within [MAX_LIBRARY_BYTES] (QA #4) -
/// the one check for a tick on the device card and for the import's ticks (21a).
pub async fn tick_fits(
    db: &mut sqlx::SqliteConnection,
    device_id: i64,
    entry_id: i64,
) -> Result<bool, sqlx::Error> {
    Ok(library_for(db, device_id, Some(entry_id))
        .await?
        .is_none_or(|library| library.json.len() <= MAX_LIBRARY_BYTES))
}

/// `PolicyResponse.music` - small, always sent (`null` only when it can't be read).
#[derive(Serialize, Debug, Clone, PartialEq, Eq)]
pub struct MusicPolicy {
    /// The version of `GET /api/devices/music/library` for this phone; `null` = nothing ticked.
    pub library_version: Option<String>,
    /// Downloads and streaming may use mobile data (default off).
    pub mobile_data: bool,
    /// `STREAM_MUSIC` cap in percent, `null` = off (the default).
    pub volume_cap_pct: Option<i64>,
    /// Changes with every save and clear of the family's Storytel login; 0 unless this phone's
    /// Storytel switch is on and a login is stored. The phone compares with `!=` (QA #7).
    pub storytel_generation: i64,
}

pub async fn policy_music(
    db: &sqlx::SqlitePool,
    cache: &LibraryCache,
    policy: &crate::models::DevicePolicy,
) -> Result<MusicPolicy, sqlx::Error> {
    let library_version = cached_library(db, cache, policy.device_id)
        .await?
        .map(|library| library.version.clone());
    let storytel_generation = if policy.music_storytel {
        sqlx::query_scalar::<_, i64>(
            "SELECT generation FROM music_storytel WHERE id = 1 AND ciphertext IS NOT NULL",
        )
        .fetch_optional(db)
        .await?
        .unwrap_or(0)
    } else {
        0
    };
    Ok(MusicPolicy {
        library_version,
        mobile_data: policy.music_mobile_data,
        volume_cap_pct: volume_cap(policy.music_volume_cap_pct),
        storytel_generation,
    })
}

/// Whether `hash` is a cover in `device_id`'s library - an entry's cover or an own file's art of
/// an entry the phone has ticked (one query, not a library build: qa-21-step1-code #3).
pub async fn cover_in_library(
    db: &sqlx::SqlitePool,
    device_id: i64,
    hash: &str,
) -> Result<bool, sqlx::Error> {
    sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM music_entries e \
           JOIN device_music_entries d ON d.entry_id = e.id \
           WHERE d.device_id = ? AND e.source IN ('nrk', 'rss', 'own') AND e.cover_hash = ? \
         UNION ALL SELECT 1 FROM music_files f \
           JOIN music_entries e ON e.id = f.entry_id \
           JOIN device_music_entries d ON d.entry_id = f.entry_id \
           WHERE d.device_id = ? AND e.source = 'own' AND f.art_hash = ?)",
    )
    .bind(device_id)
    .bind(hash)
    .bind(device_id)
    .bind(hash)
    .fetch_one(db)
    .await
}

/// The phones that have `entry_id` ticked (to nudge after a change).
pub async fn phones_with_entry(
    db: &sqlx::SqlitePool,
    entry_id: i64,
) -> Result<Vec<i64>, sqlx::Error> {
    sqlx::query_scalar("SELECT device_id FROM device_music_entries WHERE entry_id = ?")
        .bind(entry_id)
        .fetch_all(db)
        .await
}

/// The phones with any entry of `category_id` ticked.
pub async fn phones_with_category(
    db: &sqlx::SqlitePool,
    category_id: i64,
) -> Result<Vec<i64>, sqlx::Error> {
    sqlx::query_scalar(
        "SELECT DISTINCT d.device_id FROM device_music_entries d \
         JOIN music_entries e ON e.id = d.entry_id WHERE e.category_id = ?",
    )
    .bind(category_id)
    .fetch_all(db)
    .await
}

// ------------------------------------------------------------------------------------------------
// Own files on disk
// ------------------------------------------------------------------------------------------------

/// Free bytes for an unprivileged user on the file system holding `dir` (its nearest existing
/// ancestor), `None` when it can't be read.
pub fn free_bytes(dir: &Path) -> Option<u64> {
    let mut probe = dir;
    loop {
        if probe.exists() {
            let stat = rustix::fs::statvfs(probe).ok()?;
            return Some(stat.f_bavail.saturating_mul(stat.f_frsize));
        }
        probe = probe.parent()?;
        if probe.as_os_str().is_empty() {
            probe = Path::new(".");
        }
    }
}

/// What an own file turned out to be.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AudioTags {
    /// The extension it's stored under: mp3, m4a, m4b, ogg, opus, flac or wav.
    pub ext: &'static str,
    pub title: Option<String>,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub track_no: Option<i64>,
    pub duration_ms: Option<i64>,
    /// The embedded front cover (or the first picture), as stored in the file.
    pub picture: Option<Vec<u8>>,
}

/// Reads an uploaded file with lofty (by its content, not its name; blocking). `None` = not one of
/// mp3/m4a/m4b/ogg/opus/flac/wav, or unreadable.
pub fn read_audio(path: &Path, original_name: &str) -> Option<AudioTags> {
    use lofty::file::FileType;
    use lofty::picture::PictureType;
    use lofty::prelude::*;

    let tagged = lofty::probe::Probe::open(path)
        .ok()?
        .guess_file_type()
        .ok()?
        .read()
        .ok()?;
    let ext = match tagged.file_type() {
        FileType::Mpeg => "mp3",
        FileType::Mp4 if original_name.to_ascii_lowercase().ends_with(".m4b") => "m4b",
        FileType::Mp4 => "m4a",
        FileType::Vorbis => "ogg",
        FileType::Opus => "opus",
        FileType::Flac => "flac",
        FileType::Wav => "wav",
        _ => return None,
    };
    let duration = tagged.properties().duration().as_millis();
    let tag = tagged.primary_tag().or_else(|| tagged.first_tag());
    let text =
        |value: Option<std::borrow::Cow<'_, str>>| value.and_then(|v| cut_title(&v, MAX_TAG_CHARS));
    let picture = tag.and_then(|t| {
        t.pictures()
            .iter()
            .find(|p| p.pic_type() == PictureType::CoverFront)
            .or_else(|| t.pictures().first())
            .map(|p| p.data().to_vec())
    });
    Some(AudioTags {
        ext,
        title: tag.and_then(|t| text(t.title())),
        artist: tag.and_then(|t| text(t.artist())),
        album: tag.and_then(|t| text(t.album())),
        track_no: tag.and_then(|t| t.track()).map(i64::from),
        duration_ms: (duration > 0).then(|| i64::try_from(duration).unwrap_or(i64::MAX)),
        picture,
    })
}

/// At startup: flags every own file that isn't on disk as `missing` (a restore on another box -
/// the audio is never backed up) and clears the flag where it came back. Best effort, logged.
pub async fn flag_missing_files(db: &sqlx::SqlitePool, dir: &Path) {
    let rows: Vec<(i64, String, bool)> =
        match sqlx::query_as("SELECT id, path, missing FROM music_files")
            .fetch_all(db)
            .await
        {
            Ok(rows) => rows,
            Err(err) => {
                tracing::warn!(%err, "can't list the music files to check");
                return;
            }
        };
    for (id, path, missing) in rows {
        let exists = file_path(dir, &path).is_some_and(|p| p.is_file());
        if exists == !missing {
            continue;
        }
        if !exists {
            tracing::warn!(
                file_id = id,
                path,
                "music file missing on disk - listed as missing"
            );
        }
        if let Err(err) = sqlx::query("UPDATE music_files SET missing = ? WHERE id = ?")
            .bind(!exists)
            .bind(id)
            .execute(db)
            .await
        {
            tracing::warn!(%err, "can't flag a missing music file");
        }
    }
}

/// `<dir>/<relative>` for a stored relative path ("<entry>/<name>"), `None` for anything that could
/// leave the directory.
pub fn file_path(dir: &Path, relative: &str) -> Option<std::path::PathBuf> {
    let ok = !relative.is_empty()
        && relative.split('/').all(|part| {
            !part.is_empty()
                && part != "."
                && part != ".."
                && part
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_' || c == '-')
        });
    ok.then(|| dir.join(relative))
}

/// Own files on disk that no row names (qa-21-step1-code #7): what a restore of an older database
/// leaves behind. Never deleted silently - the newer database might be put back - but shown on the
/// Music page with a button.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct Orphans {
    pub files: Vec<std::path::PathBuf>,
    pub bytes: u64,
}

/// A file this young may be an upload between its rename and its row: never an orphan yet.
const ORPHAN_MIN_AGE: Duration = Duration::from_secs(10 * 60);

/// The files in `<dir>/<entry>/` that no `music_files.path` names (not `.part` files, none younger
/// than [ORPHAN_MIN_AGE]).
pub async fn orphan_files(db: &sqlx::SqlitePool, dir: &Path) -> Result<Orphans, sqlx::Error> {
    let referenced: std::collections::HashSet<String> =
        sqlx::query_scalar("SELECT path FROM music_files")
            .fetch_all(db)
            .await?
            .into_iter()
            .collect();
    let mut orphans = Orphans::default();
    let Ok(mut entries) = tokio::fs::read_dir(dir).await else {
        return Ok(orphans);
    };
    let now = std::time::SystemTime::now();
    while let Ok(Some(entry_dir)) = entries.next_entry().await {
        let entry_name = entry_dir.file_name().to_string_lossy().into_owned();
        let Ok(mut files) = tokio::fs::read_dir(entry_dir.path()).await else {
            continue;
        };
        while let Ok(Some(file)) = files.next_entry().await {
            let name = file.file_name().to_string_lossy().into_owned();
            if name.ends_with(".part") || referenced.contains(&format!("{entry_name}/{name}")) {
                continue;
            }
            let Ok(meta) = file.metadata().await else {
                continue;
            };
            let young = meta
                .modified()
                .ok()
                .and_then(|m| now.duration_since(m).ok())
                .is_none_or(|age| age < ORPHAN_MIN_AGE);
            if meta.is_file() && !young {
                orphans.bytes += meta.len();
                orphans.files.push(file.path());
            }
        }
    }
    orphans.files.sort();
    Ok(orphans)
}

/// At startup: deletes upload temp files (`*.part`) a crash left in `<dir>/<entry>/`.
pub async fn remove_partial_uploads(dir: &Path) {
    let Ok(mut entries) = tokio::fs::read_dir(dir).await else {
        return;
    };
    while let Ok(Some(entry_dir)) = entries.next_entry().await {
        let Ok(mut files) = tokio::fs::read_dir(entry_dir.path()).await else {
            continue;
        };
        while let Ok(Some(file)) = files.next_entry().await {
            if file.file_name().to_string_lossy().ends_with(".part") {
                tokio::fs::remove_file(file.path()).await.ok();
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// What the phone reports (`music_state`)
// ------------------------------------------------------------------------------------------------

/// At most this many entry errors are kept.
pub const MAX_ENTRY_ERRORS: usize = 50;
const MAX_WORD: usize = 32;

/// One entry the music app couldn't list: `not_found`, `not_feed`, `http_<code>`, `no_items`.
#[derive(Deserialize, Serialize, Default, Debug, Clone, PartialEq, Eq)]
#[serde(default)]
pub struct EntryError {
    pub entry: i64,
    pub error: String,
}

/// `StatusReportRequest.music_state` (capability `music_v1`): the last state the music app reported
/// through the launcher plus the launcher's own view. Never positions or what is playing.
#[derive(Deserialize, Serialize, Default, Debug, Clone, PartialEq, Eq)]
#[serde(default)]
pub struct MusicState {
    pub package: Option<String>,
    pub version_code: Option<i64>,
    /// The launcher's bridge to the music app ("bound", "unbound", ...).
    pub bridge: Option<String>,
    /// The library version the music app has applied.
    pub library_version: Option<String>,
    pub cache_bytes: Option<i64>,
    pub downloads_waiting: Option<i64>,
    pub entry_errors: Vec<EntryError>,
    /// The Storytel login on the phone ("ok", "none", "login_failed", ...).
    pub storytel: Option<String>,
}

fn word(value: Option<String>) -> Option<String> {
    value.filter(|w| {
        !w.is_empty()
            && w.len() <= MAX_WORD
            && w.chars()
                .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_')
    })
}

fn is_version(value: &str) -> bool {
    value.len() == 16
        && value
            .bytes()
            .all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

/// `music_state` as stored: only the known fields, short words and real package names only,
/// numbers clamped, at most [MAX_ENTRY_ERRORS] errors; `None` for anything that isn't such an
/// object.
pub fn sanitize_music_state(value: &serde_json::Value) -> Option<String> {
    if !value.is_object() {
        return None;
    }
    let state: MusicState = serde_json::from_value(value.clone()).ok()?;
    let clean = MusicState {
        package: state
            .package
            .filter(|p| crate::time_rules::valid_package_name(p) && p.len() <= 100),
        version_code: state.version_code.map(|v| v.clamp(0, i64::from(i32::MAX))),
        bridge: word(state.bridge),
        library_version: state.library_version.filter(|v| is_version(v)),
        cache_bytes: state.cache_bytes.map(|b| b.clamp(0, 10_000_000_000_000)),
        downloads_waiting: state.downloads_waiting.map(|n| n.clamp(0, 1_000_000)),
        entry_errors: state
            .entry_errors
            .into_iter()
            .filter_map(|e| {
                Some(EntryError {
                    entry: e.entry.max(0),
                    error: word(Some(e.error))?,
                })
            })
            .take(MAX_ENTRY_ERRORS)
            .collect(),
        storytel: word(state.storytel),
    };
    serde_json::to_string(&clean).ok()
}

pub fn parse_music_state(json: Option<&str>) -> Option<MusicState> {
    json.and_then(|j| serde_json::from_str(j).ok())
}

/// "not found at the source" for an entry error code.
pub fn entry_error_text(code: &str) -> String {
    match code {
        "not_found" => "not found at the source".to_string(),
        "not_feed" => "the link isn't a feed any more".to_string(),
        "no_items" => "no episodes".to_string(),
        other => match other.strip_prefix("http_") {
            Some(status) => format!("the source answered {status}"),
            None => other.replace('_', " "),
        },
    }
}

/// Bytes as "12.3 MB".
pub fn megabytes(bytes: i64) -> String {
    format!("{:.1} MB", bytes as f64 / 1_000_000.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nrk_links_are_normalised() {
        let podcast = |slug: &str| Link::NrkPodcast {
            slug: slug.to_string(),
        };
        assert_eq!(
            parse_link("https://radio.nrk.no/podkast/abels_taarn").unwrap(),
            podcast("abels_taarn")
        );
        // An episode link adds the whole podcast; scheme, case and trailing bits don't matter.
        assert_eq!(
            parse_link("radio.nrk.no/podkast/Abels_Taarn/l_8a5f3c1e-1b2c/").unwrap(),
            podcast("abels_taarn")
        );
        assert_eq!(
            parse_link("http://RADIO.nrk.no/podkast/abels_taarn?x=1#y").unwrap(),
            podcast("abels_taarn")
        );
        assert_eq!(
            parse_link("https://radio.nrk.no/serie/radioteatret").unwrap(),
            Link::NrkSeries {
                slug: "radioteatret".into(),
                program: None
            }
        );
        assert_eq!(
            parse_link("https://radio.nrk.no/serie/radioteatret/sesong/2019").unwrap(),
            Link::NrkSeries {
                slug: "radioteatret".into(),
                program: None
            }
        );
        let from_program = Link::NrkSeries {
            slug: "radioteatret".into(),
            program: Some("MSPO30001119".into()),
        };
        assert_eq!(
            parse_link("https://radio.nrk.no/serie/radioteatret/MSPO30001119").unwrap(),
            from_program
        );
        assert_eq!(
            parse_link("https://radio.nrk.no/serie/radioteatret/sesong/2019/MSPO30001119").unwrap(),
            from_program
        );
        assert_eq!(
            from_program.target(),
            "https://radio.nrk.no/serie/radioteatret/MSPO30001119"
        );
        assert_eq!(
            podcast("abels_taarn").target(),
            "https://radio.nrk.no/podkast/abels_taarn"
        );
        assert!(parse_link("https://radio.nrk.no/direkte/p1").is_err());
        assert!(parse_link("https://radio.nrk.no/").is_err());
        assert!(parse_link("https://radio.nrk.no/podkast/a%20b").is_err());
    }

    #[test]
    fn other_links_are_feeds_without_fragments() {
        assert_eq!(
            parse_link(" https://Example.ORG/feed.xml?a=1#top ").unwrap(),
            Link::Rss {
                url: "https://example.org/feed.xml?a=1".into()
            }
        );
        assert_eq!(
            parse_link("example.org/podcast.rss").unwrap().target(),
            "https://example.org/podcast.rss"
        );
        assert!(parse_link("").is_err());
        assert!(parse_link("ftp://example.org/x").is_err());
        assert!(parse_link("javascript:alert(1)").is_err());
        assert!(parse_link("https://user:pw@example.org/feed").is_err());
        assert!(parse_link("https://exa mple.org/").is_err());
    }

    #[test]
    fn state_keys_follow_vibb() {
        assert_eq!(
            state_key("https://radio.nrk.no/podkast/abels_taarn"),
            "abels_taarn"
        );
        // sha1("https://example.org/feed.xml")[:12]
        assert_eq!(state_key("https://example.org/feed.xml"), {
            let full = hex::encode(Sha1::digest(b"https://example.org/feed.xml"));
            full[..12].to_string()
        });
        assert_eq!(state_key("https://radio.nrk.no/serie/x").len(), 12);
    }

    #[test]
    fn rss_feeds_are_told_from_pages() {
        let feed = br#"<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
<channel>
  <title><![CDATA[Godnatt &amp; eventyr]]></title>
  <itunes:image href="https://example.org/a.jpg"/>
  <item><title>Uten lyd</title></item>
  <item><title>Episode 1</title><enclosure url="https://example.org/1.mp3" type="audio/mpeg" length="1"/></item>
</channel></rss>"#;
        assert_eq!(rss_title(feed).unwrap(), "Godnatt &amp; eventyr");
        let entities = b"<rss><channel><title>Bl&#229; &amp; gr&#xF8;nn</title>\
            <item><enclosure url='x.mp3'/></item></channel></rss>";
        assert_eq!(rss_title(entities).unwrap(), "Blå & grønn");
        // An item's title isn't the channel's.
        let no_title =
            b"<rss><channel><item><title>Ep</title><enclosure url=\"a\"/></item></channel></rss>";
        assert_eq!(rss_title(no_title).unwrap(), "Podcast");
        assert_eq!(
            rss_title(
                b"<rss><channel><title>T</title><item><title>x</title></item></channel></rss>"
            ),
            Err(CheckError::NoItems)
        );
        assert_eq!(
            rss_title(b"<!doctype html><html><head><title>Hi</title></head></html>"),
            Err(CheckError::NotFeed)
        );
        assert_eq!(
            rss_title(b"<rss><channelx><item><enclosure url='a'/></item></channelx></rss>"),
            Err(CheckError::NotFeed)
        );
    }

    #[test]
    fn nrk_titles_come_from_the_catalog() {
        let json = serde_json::json!({"series": {"titles": {"title": "  Abels   tårn "}}});
        assert_eq!(nrk_title(&json).as_deref(), Some("Abels tårn"));
        assert_eq!(
            nrk_title(&serde_json::json!({"titles": {"title": "Radioteatret"}})).as_deref(),
            Some("Radioteatret")
        );
        assert_eq!(nrk_title(&serde_json::json!({"series": {}})), None);
    }

    #[test]
    fn own_files_play_in_track_order_or_by_name() {
        let tracked = vec![
            (1, Some(2), "b.mp3".to_string()),
            (2, Some(1), "c.mp3".to_string()),
            (3, Some(3), "a.mp3".to_string()),
        ];
        assert_eq!(file_order(&tracked), vec![2, 1, 3]);
        let mixed = vec![
            (1, Some(2), "B.mp3".to_string()),
            (2, None, "c.mp3".to_string()),
            (3, Some(3), "a.mp3".to_string()),
        ];
        assert_eq!(file_order(&mixed), vec![3, 1, 2]);
        assert!(file_order(&[]).is_empty());
    }

    #[test]
    fn names_are_cleaned() {
        assert_eq!(
            clean_name("  Gode   sanger ", 60).as_deref(),
            Some("Gode sanger")
        );
        assert_eq!(clean_name("   ", 60), None);
        assert_eq!(clean_name(&"x".repeat(21), 20), None);
        assert_eq!(clean_name("a\u{7}b", 20), None);
        assert_eq!(cut_title(&"x".repeat(80), 60).unwrap().len(), 60);
    }

    #[test]
    fn the_version_ignores_itself_and_follows_every_field() {
        let category = LibCategory {
            id: 1,
            name: "Musikk".into(),
            icon: "music_note".into(),
            color: "rose".into(),
            sort: 10,
        };
        let entry = LibEntry {
            id: 3,
            key: "abels_taarn".into(),
            name: "Abels tårn".into(),
            category: 1,
            source: "nrk".into(),
            target: Some("https://radio.nrk.no/podkast/abels_taarn".into()),
            order: "auto".into(),
            cache: 5,
            resume: true,
            cover: None,
            sort: 10,
        };
        let a = Library::build(vec![category.clone()], vec![entry.clone()], vec![]);
        let b = Library::build(vec![category.clone()], vec![entry.clone()], vec![]);
        assert_eq!(a.version, b.version);
        assert_eq!(a.version.len(), 16);
        let body = serde_json::to_vec(&LibraryBody {
            v: 1,
            categories: std::slice::from_ref(&category),
            entries: std::slice::from_ref(&entry),
            files: &[],
            covers: &[],
        })
        .unwrap();
        assert_eq!(a.version, hex::encode(Sha256::digest(&body))[..16]);
        let renamed = LibEntry {
            name: "Abels tårn!".into(),
            ..entry
        };
        assert_ne!(
            Library::build(vec![category], vec![renamed], vec![]).version,
            a.version
        );
    }

    #[test]
    fn music_state_keeps_known_fields_only() {
        let raw = serde_json::json!({
            "package": "me.vibb.music",
            "version_code": 1000000,
            "bridge": "bound",
            "library_version": "0123456789abcdef",
            "cache_bytes": -5,
            "downloads_waiting": 3,
            "entry_errors": (0..60).map(|i| serde_json::json!({"entry": i, "error": "http_404"})).collect::<Vec<_>>(),
            "storytel": "ok",
            "now_playing": "secret",
            "position_ms": 1234
        });
        let stored = sanitize_music_state(&raw).unwrap();
        assert!(!stored.contains("now_playing") && !stored.contains("position"));
        let state = parse_music_state(Some(&stored)).unwrap();
        assert_eq!(state.entry_errors.len(), MAX_ENTRY_ERRORS);
        assert_eq!(state.cache_bytes, Some(0));
        assert_eq!(state.library_version.as_deref(), Some("0123456789abcdef"));
        let odd = serde_json::json!({
            "package": "not a package; rm -rf",
            "bridge": "<b>",
            "library_version": "XYZ",
            "entry_errors": [{"entry": 1, "error": "Bad Words"}, {"entry": 2, "error": "no_items"}]
        });
        let state = parse_music_state(sanitize_music_state(&odd).as_deref()).unwrap();
        assert_eq!(state.package, None);
        assert_eq!(state.bridge, None);
        assert_eq!(state.library_version, None);
        assert_eq!(
            state.entry_errors,
            vec![EntryError {
                entry: 2,
                error: "no_items".into()
            }]
        );
        assert_eq!(sanitize_music_state(&serde_json::json!([1])), None);
        assert_eq!(
            sanitize_music_state(&serde_json::json!({"bridge": 5})),
            None
        );
        assert_eq!(entry_error_text("http_503"), "the source answered 503");
    }

    #[test]
    fn stored_paths_never_leave_the_directory() {
        let dir = Path::new("/data/music_files");
        assert!(file_path(dir, "3/abc.mp3").is_some());
        assert!(file_path(dir, "../x").is_none());
        assert!(file_path(dir, "3/../../etc/passwd").is_none());
        assert!(file_path(dir, "/etc/passwd").is_none());
        assert!(file_path(dir, "").is_none());
    }
}
