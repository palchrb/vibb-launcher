//! Importing a Vibb Pi library (design `docs/design/21a-vibb-library-import.md`): the Pi's
//! `/etc/vibb/library.json` or its `GET /library` answer, `{"sections": [{"name", "spotify_user"?,
//! "entries": [{"name", "target", "order", "cache", "resume"}]}]}` (palchrb/vibb
//! `pi/vibb/library.py`). Pure: reading the file, the skip rules and the plan against what the
//! library already holds; the pages are `handlers::music_import`.
//!
//! No network: a target goes through `music::parse_link` only (the same normalised target, source
//! and key as "Add"), never `check_link` - a dead feed shows up later as the phone's entry error.
//! Positions and downloads aren't imported (design 20: no position mirroring).

use std::collections::HashSet;

use serde_json::{Value, json};

use crate::music::{self, DEFAULT_CACHE, MAX_ENTRIES, MAX_NAME_CHARS};

/// The largest file read (and the largest preview document accepted back).
pub const MAX_IMPORT_BYTES: usize = 1_000_000;
/// At most this many sections and entries are read; the rest of the file is ignored.
pub const MAX_SECTIONS: usize = 50;
pub const MAX_ROWS: usize = 500;
/// A skipped row's name and target are kept this long (to show it; it is never stored).
const MAX_SHOWN_CHARS: usize = 300;
/// A section name is kept this long (a new category's name is cut to 20 from it).
const MAX_SECTION_CHARS: usize = 100;

/// One entry that can go into the library, as "Add" would store it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Candidate {
    pub name: String,
    pub target: String,
    pub source: &'static str,
    pub key: String,
    pub order: String,
    pub cache: i64,
    pub resume: bool,
}

/// Why a row isn't imported.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Skip {
    Spotify,
    Storytel,
    Folder,
    /// `parse_link` refused it (its reason).
    BadLink(&'static str),
    NoName,
    /// An entry of a section that follows a Spotify profile.
    SpotifyProfile,
    InLibrary,
    Repeat,
    Full,
}

impl Skip {
    pub fn reason(&self) -> String {
        match self {
            Skip::Spotify => "Spotify comes in a later version of the music app".to_string(),
            Skip::Storytel => "Storytel books come in a later version of the music app".to_string(),
            Skip::Folder => {
                "a folder on the Vibb box - upload the files as an own-files entry".to_string()
            }
            Skip::BadLink(reason) => reason.trim_end_matches('.').to_string(),
            Skip::NoName => "it has no name".to_string(),
            Skip::SpotifyProfile => {
                "it comes from a Spotify profile (later version of the music app)".to_string()
            }
            Skip::InLibrary => "already in the library".to_string(),
            Skip::Repeat => "it is in the file twice - the first one is used".to_string(),
            Skip::Full => format!("the library holds at most {MAX_ENTRIES} entries"),
        }
    }
}

/// One entry of the file: as shown (and written back into the preview document) and what becomes
/// of it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Row {
    /// The cleaned name of a candidate, else the file's name (cut).
    pub name: String,
    /// The normalised target of a candidate, else the file's target (cut).
    pub target: String,
    pub order: String,
    pub cache: i64,
    pub resume: bool,
    pub outcome: Result<Candidate, Skip>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Section {
    pub name: String,
    pub spotify_user: Option<String>,
    pub rows: Vec<Row>,
}

fn cut(text: &str, max: usize) -> String {
    text.trim().chars().take(max).collect()
}

fn text_of(object: &serde_json::Map<String, Value>, key: &str) -> String {
    object
        .get(key)
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string()
}

/// What happens to one target from a section (`spotify_user` = the section follows a profile).
fn classify(target: &str, spotify_user: bool) -> Result<music::Link, Skip> {
    if spotify_user {
        return Err(Skip::SpotifyProfile);
    }
    let trimmed = target.trim();
    let lower = trimmed.to_ascii_lowercase();
    if lower.starts_with("spotify:")
        || lower.contains("open.spotify.com")
        || lower.contains("spotify.link/")
    {
        return Err(Skip::Spotify);
    }
    if lower.starts_with("storytel:") {
        return Err(Skip::Storytel);
    }
    if trimmed.starts_with('/') {
        return Err(Skip::Folder);
    }
    music::parse_link(trimmed).map_err(Skip::BadLink)
}

fn row_of(entry: &serde_json::Map<String, Value>, spotify_user: bool) -> Row {
    let raw_name = text_of(entry, "name");
    let raw_target = text_of(entry, "target");
    let order = entry
        .get("order")
        .and_then(Value::as_str)
        .filter(|o| music::valid_order(o))
        .unwrap_or("auto")
        .to_string();
    let cache = entry
        .get("cache")
        .and_then(Value::as_i64)
        .filter(|c| *c == -1 || (0..=100).contains(c))
        .unwrap_or(DEFAULT_CACHE);
    let resume = entry.get("resume").and_then(Value::as_bool).unwrap_or(true);
    // A long Pi name is cut, not refused.
    let name = music::clean_name(&cut(&raw_name, MAX_NAME_CHARS), MAX_NAME_CHARS);
    let outcome = classify(&raw_target, spotify_user).and_then(|link| {
        let name = name.clone().ok_or(Skip::NoName)?;
        let target = link.target();
        Ok(Candidate {
            key: music::state_key(&target),
            source: link.source(),
            name,
            target,
            order: order.clone(),
            cache,
            resume,
        })
    });
    let (name, target) = match &outcome {
        Ok(candidate) => (candidate.name.clone(), candidate.target.clone()),
        Err(_) => (
            name.unwrap_or_else(|| cut(&raw_name, MAX_SHOWN_CHARS)),
            cut(&raw_target, MAX_SHOWN_CHARS),
        ),
    };
    Row {
        name,
        target,
        order,
        cache,
        resume,
        outcome,
    }
}

/// Reads a Vibb library file. `Err` is the parent-facing reason (not JSON, no `sections` list).
/// Unknown fields are ignored; at most [MAX_SECTIONS] sections and [MAX_ROWS] entries are read.
pub fn parse(bytes: &[u8]) -> Result<Vec<Section>, String> {
    let value: Value = serde_json::from_slice(bytes).map_err(|_| {
        "That file isn't JSON - pick the Vibb box's library.json (see below).".to_string()
    })?;
    let Some(sections) = value.get("sections").and_then(Value::as_array) else {
        return Err(
            "That file isn't a Vibb library: it has no \"sections\" list. Pick the Vibb box's \
             library.json."
                .to_string(),
        );
    };
    let mut rows_read = 0;
    let mut out = Vec::new();
    for section in sections.iter().take(MAX_SECTIONS) {
        let Some(section) = section.as_object() else {
            continue;
        };
        let spotify_user = section
            .get("spotify_user")
            .and_then(Value::as_str)
            .map(|user| cut(user, MAX_SHOWN_CHARS))
            .filter(|user| !user.is_empty());
        let mut rows = Vec::new();
        for entry in section
            .get("entries")
            .and_then(Value::as_array)
            .map(Vec::as_slice)
            .unwrap_or_default()
        {
            if rows_read >= MAX_ROWS {
                break;
            }
            let Some(entry) = entry.as_object() else {
                continue;
            };
            rows_read += 1;
            rows.push(row_of(entry, spotify_user.is_some()));
        }
        out.push(Section {
            name: cut(&text_of(section, "name"), MAX_SECTION_CHARS),
            spotify_user,
            rows,
        });
    }
    Ok(out)
}

/// Marks what can't be added now, in file order: a target or key already in the library, a repeat
/// within the file (target or key), and everything past [MAX_ENTRIES]. Only rows in `wanted`
/// (`(section, row)`; `None` = all) are considered - the others are left as they are. Returns the
/// rows to add.
pub fn plan(
    sections: &mut [Section],
    existing_targets: &HashSet<String>,
    existing_keys: &HashSet<String>,
    existing_count: i64,
    wanted: Option<&HashSet<(usize, usize)>>,
) -> Vec<(usize, usize)> {
    let mut seen_targets: HashSet<String> = HashSet::new();
    let mut seen_keys: HashSet<String> = HashSet::new();
    let mut count = existing_count;
    let mut accepted = Vec::new();
    for (s, section) in sections.iter_mut().enumerate() {
        for (r, row) in section.rows.iter_mut().enumerate() {
            if wanted.is_some_and(|w| !w.contains(&(s, r))) {
                continue;
            }
            let Ok(candidate) = &row.outcome else {
                continue;
            };
            let skip = if existing_targets.contains(&candidate.target)
                || existing_keys.contains(&candidate.key)
            {
                Some(Skip::InLibrary)
            } else if seen_targets.contains(&candidate.target) || seen_keys.contains(&candidate.key)
            {
                Some(Skip::Repeat)
            } else if count >= MAX_ENTRIES {
                Some(Skip::Full)
            } else {
                None
            };
            match skip {
                Some(skip) => row.outcome = Err(skip),
                None => {
                    seen_targets.insert(candidate.target.clone());
                    seen_keys.insert(candidate.key.clone());
                    count += 1;
                    accepted.push((s, r));
                }
            }
        }
    }
    accepted
}

/// The preview's hidden document: the file as read, normalised, in the file's own shape - so the
/// confirm reads it with [parse] like a fresh upload and allows nothing more.
pub fn document(sections: &[Section]) -> String {
    let sections: Vec<Value> = sections
        .iter()
        .map(|section| {
            let entries: Vec<Value> = section
                .rows
                .iter()
                .map(|row| {
                    json!({
                        "name": row.name,
                        "target": row.target,
                        "order": row.order,
                        "cache": row.cache,
                        "resume": row.resume,
                    })
                })
                .collect();
            let mut object = json!({"name": section.name, "entries": entries});
            if let Some(user) = &section.spotify_user {
                object["spotify_user"] = json!(user);
            }
            object
        })
        .collect();
    json!({ "sections": sections }).to_string()
}

/// The name a "New: <section>" category gets: the section name cut to 20 characters, or "Vibb".
pub fn new_category_name(section: &str) -> String {
    music::clean_name(
        &cut(section, music::MAX_CATEGORY_NAME_CHARS),
        music::MAX_CATEGORY_NAME_CHARS,
    )
    .unwrap_or_else(|| "Vibb".to_string())
}

/// The existing category a section goes into by default: the one with the section's name (trimmed,
/// case-insensitive); `None` = a new one.
pub fn matching_category<'a>(
    categories: &'a [music::MusicCategory],
    section: &str,
) -> Option<&'a music::MusicCategory> {
    let wanted = section.trim().to_lowercase();
    categories
        .iter()
        .find(|c| !wanted.is_empty() && c.name.trim().to_lowercase() == wanted)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(name: &str, target: &str) -> Value {
        json!({"name": name, "target": target, "order": "auto", "cache": 5, "resume": true})
    }

    #[test]
    fn rows_are_classified_like_add_without_a_fetch() {
        let file = json!({"sections": [
            {"name": "Podkast", "entries": [
                entry("Abels tårn", "https://radio.nrk.no/podkast/abels_taarn/l_1"),
                entry("Feed", "https://example.org/feed.xml#x"),
                entry("Spill", "spotify:playlist:1"),
                entry("Spill 2", "https://open.spotify.com/playlist/1"),
                entry("Spill 3", "https://spotify.link/abc"),
                entry("Bok", "storytel:series:9"),
                entry("Mappe", "/srv/music/egne"),
                entry("Direkte", "https://radio.nrk.no/direkte/p1"),
                entry("   ", "https://example.org/x.rss"),
            ]},
            {"name": "Profil", "spotify_user": "kari", "entries": [entry("P", "https://example.org/p.rss")]},
        ]});
        let sections = parse(file.to_string().as_bytes()).unwrap();
        let outcomes: Vec<Result<&str, Skip>> = sections
            .iter()
            .flat_map(|s| s.rows.iter())
            .map(|r| {
                r.outcome
                    .as_ref()
                    .map(|c| c.target.as_str())
                    .map_err(Clone::clone)
            })
            .collect();
        assert_eq!(
            outcomes,
            vec![
                Ok("https://radio.nrk.no/podkast/abels_taarn"),
                Ok("https://example.org/feed.xml"),
                Err(Skip::Spotify),
                Err(Skip::Spotify),
                Err(Skip::Spotify),
                Err(Skip::Storytel),
                Err(Skip::Folder),
                Err(Skip::BadLink(
                    "That NRK link is neither a podcast (radio.nrk.no/podkast/...) nor a series \
                     (radio.nrk.no/serie/...)."
                )),
                Err(Skip::NoName),
                Err(Skip::SpotifyProfile),
            ]
        );
        let first = sections[0].rows[0].outcome.as_ref().unwrap();
        assert_eq!(first.key, "abels_taarn");
        assert_eq!(first.source, "nrk");
        assert_eq!(sections[0].rows[1].outcome.as_ref().unwrap().source, "rss");
    }

    #[test]
    fn settings_are_kept_and_bad_ones_defaulted() {
        let file = json!({"sections": [{"name": "S", "entries": [
            {"name": "a", "target": "https://example.org/a.rss", "order": "newest_first", "cache": -1, "resume": false},
            {"name": "b", "target": "https://example.org/b.rss", "order": "shuffle", "cache": 101, "resume": "no"},
            {"name": "c", "target": "https://example.org/c.rss", "cache": 42},
            {"name": "x".repeat(80), "target": "https://example.org/d.rss", "image": "/x.jpg", "new": true},
        ]}]});
        let sections = parse(file.to_string().as_bytes()).unwrap();
        let settings: Vec<(String, i64, bool)> = sections[0]
            .rows
            .iter()
            .map(|r| (r.order.clone(), r.cache, r.resume))
            .collect();
        assert_eq!(
            settings,
            vec![
                ("newest_first".into(), -1, false),
                ("auto".into(), DEFAULT_CACHE, true),
                ("auto".into(), 42, true),
                ("auto".into(), DEFAULT_CACHE, true),
            ]
        );
        assert_eq!(sections[0].rows[3].name.chars().count(), MAX_NAME_CHARS);
    }

    #[test]
    fn files_that_arent_a_library_are_refused() {
        assert!(parse(b"not json").unwrap_err().contains("isn't JSON"));
        assert!(
            parse(b"{\"entries\": []}")
                .unwrap_err()
                .contains("no \"sections\"")
        );
        assert!(parse(b"[1, 2]").unwrap_err().contains("no \"sections\""));
        // Odd sections and entries are passed over, not fatal.
        let sections =
            parse(br#"{"sections": [5, {"name": 3, "entries": [7, {"target": 1}]}]}"#).unwrap();
        assert_eq!(sections.len(), 1);
        assert_eq!(sections[0].rows.len(), 1);
        assert_eq!(sections[0].name, "");
    }

    #[test]
    fn at_most_50_sections_and_500_entries_are_read() {
        let sections: Vec<Value> = (0..60)
            .map(|s| {
                json!({"name": format!("S{s}"), "entries": (0..20).map(|e| entry("n", &format!("https://example.org/{s}-{e}.rss"))).collect::<Vec<_>>()})
            })
            .collect();
        let parsed = parse(json!({ "sections": sections }).to_string().as_bytes()).unwrap();
        assert_eq!(parsed.len(), MAX_SECTIONS);
        assert_eq!(parsed.iter().map(|s| s.rows.len()).sum::<usize>(), MAX_ROWS);
    }

    #[test]
    fn the_plan_skips_duplicates_and_stops_at_the_limit() {
        let file = json!({"sections": [{"name": "S", "entries": [
            entry("in library", "https://example.org/old.rss"),
            entry("a", "https://example.org/a.rss"),
            entry("a again", "https://example.org/a.rss"),
            entry("same key", "https://radio.nrk.no/podkast/abels_taarn"),
            entry("b", "https://example.org/b.rss"),
            entry("c", "https://example.org/c.rss"),
        ]}]});
        let mut sections = parse(file.to_string().as_bytes()).unwrap();
        let targets = HashSet::from(["https://example.org/old.rss".to_string()]);
        let keys = HashSet::from(["abels_taarn".to_string()]);
        let accepted = plan(&mut sections, &targets, &keys, MAX_ENTRIES - 2, None);
        assert_eq!(accepted, vec![(0, 1), (0, 4)]);
        let skips: Vec<Option<Skip>> = sections[0]
            .rows
            .iter()
            .map(|r| r.outcome.clone().err())
            .collect();
        assert_eq!(
            skips,
            vec![
                Some(Skip::InLibrary),
                None,
                Some(Skip::Repeat),
                Some(Skip::InLibrary),
                None,
                Some(Skip::Full),
            ]
        );
        // Only the wanted rows count: unticking "a" lets "c" in.
        let mut sections = parse(file.to_string().as_bytes()).unwrap();
        let wanted = HashSet::from([(0, 4), (0, 5)]);
        let accepted = plan(
            &mut sections,
            &targets,
            &keys,
            MAX_ENTRIES - 2,
            Some(&wanted),
        );
        assert_eq!(accepted, vec![(0, 4), (0, 5)]);
    }

    #[test]
    fn the_preview_document_reads_back_the_same() {
        let file = json!({"sections": [
            {"name": "  Podkast ", "entries": [
                entry("  Abels   tårn ", "radio.nrk.no/podkast/Abels_Taarn"),
                entry("Spill", "spotify:playlist:1"),
            ]},
            {"name": "Profil", "spotify_user": "kari", "entries": [entry("P", "https://example.org/p.rss")]},
        ]});
        let sections = parse(file.to_string().as_bytes()).unwrap();
        let again = parse(document(&sections).as_bytes()).unwrap();
        assert_eq!(again, sections);
        assert_eq!(again[0].name, "Podkast");
        assert_eq!(again[0].rows[0].name, "Abels tårn");
    }

    #[test]
    fn sections_find_their_category_by_name() {
        let category = |id: i64, name: &str| music::MusicCategory {
            id,
            name: name.to_string(),
            icon: "mic".into(),
            color: "teal".into(),
            sort: 10,
            default_kind: None,
        };
        let categories = vec![category(1, "Musikk"), category(4, "Podkast")];
        assert_eq!(
            matching_category(&categories, " podkast ").map(|c| c.id),
            Some(4)
        );
        assert_eq!(
            matching_category(&categories, "Lydbøker").map(|c| c.id),
            None
        );
        assert_eq!(matching_category(&categories, "").map(|c| c.id), None);
        assert_eq!(
            new_category_name("Godnatt og eventyr for de minste"),
            "Godnatt og eventyr f"
        );
        assert_eq!(new_category_name("   "), "Vibb");
    }
}
