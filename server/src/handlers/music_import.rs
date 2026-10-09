//! "Import from Vibb" on `/music` (design `docs/design/21a-vibb-library-import.md`): a Vibb Pi's
//! curated library brought over in one go. `POST /music/import` reads the uploaded file (at most
//! 1 MB, never stored) and shows a preview at `#import`: a category choice per section, a tick per
//! entry, the phones to tick them on. The preview carries the file as it was read
//! (`music_import::document`) in a hidden field; `POST /music/import/confirm` reads that like a fresh
//! upload and re-checks everything in one transaction, then redirects to `/music#import` with what
//! happened (a session flash). No outgoing request: targets only go through `music::parse_link`.

use std::collections::{HashMap, HashSet};

use axum::extract::{Multipart, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Redirect, Response};
use axum::{Extension, Form};
use serde::{Deserialize, Serialize};

use super::music::{NEXT_SORT, log_event, nudge, render_import, server_error};
use crate::AppState;
use crate::music::{self, MusicCategory};
use crate::music_import::{self, MAX_IMPORT_BYTES, Section};
use crate::security::CurrentAdmin;

/// The session key of the last import's result, shown once on `/music`.
pub const RESULT_FLASH: &str = "music_import_result";
/// The result lists at most this many skipped rows and misses (then "... and N more").
const MAX_RESULT_LINES: usize = 100;

/// The card's state.
#[derive(Default)]
pub struct ImportCard {
    pub open: bool,
    pub error: Option<String>,
    pub result: Option<ImportResult>,
    pub preview: Option<ImportPreview>,
}

/// What the last import did.
#[derive(Serialize, Deserialize, Default, Debug, Clone, PartialEq, Eq)]
pub struct ImportResult {
    pub summary: String,
    pub lines: Vec<String>,
}

pub struct ImportPreview {
    /// The file as read (`music_import::document`), sent back with the confirm.
    pub doc: String,
    pub sections: Vec<PreviewSection>,
    pub phones: Vec<(i64, String)>,
    pub importable: usize,
    pub skipped: usize,
}

pub struct PreviewSection {
    pub index: usize,
    pub name: String,
    /// What "New: ..." creates.
    pub new_name: String,
    /// The choice shown: a category id, or "new".
    pub selected: String,
    pub importable: usize,
    pub rows: Vec<PreviewRow>,
}

pub struct PreviewRow {
    /// The tick's value: "<section>.<row>".
    pub value: String,
    pub name: String,
    pub target: String,
    pub settings: String,
    /// Why it isn't imported (no tick then).
    pub reason: Option<String>,
}

fn order_text(order: &str) -> &'static str {
    match order {
        "newest_first" => "newest first",
        "oldest_first" => "oldest first",
        _ => "natural order",
    }
}

fn cache_text(cache: i64) -> String {
    match cache {
        -1 => "all kept offline".to_string(),
        0 => "nothing kept offline".to_string(),
        n => format!("newest {n} kept offline"),
    }
}

/// The default choice for a section: the category of the same name, else a new one.
fn default_choice(categories: &[MusicCategory], section: &str) -> String {
    music_import::matching_category(categories, section)
        .map(|c| c.id.to_string())
        .unwrap_or_else(|| "new".to_string())
}

fn section_label(section: &Section) -> String {
    if section.name.is_empty() {
        "(a section without a name)".to_string()
    } else {
        section.name.clone()
    }
}

/// What the library holds now: targets, keys, and how many entries.
struct Existing {
    targets: HashSet<String>,
    keys: HashSet<String>,
    count: i64,
}

async fn existing(db: &mut sqlx::SqliteConnection) -> Result<Existing, sqlx::Error> {
    let rows: Vec<(Option<String>, String)> =
        sqlx::query_as("SELECT target, key FROM music_entries")
            .fetch_all(&mut *db)
            .await?;
    Ok(Existing {
        count: rows.len() as i64,
        targets: rows.iter().filter_map(|(t, _)| t.clone()).collect(),
        keys: rows.into_iter().map(|(_, k)| k).collect(),
    })
}

async fn categories(db: &mut sqlx::SqliteConnection) -> Result<Vec<MusicCategory>, sqlx::Error> {
    sqlx::query_as("SELECT * FROM music_categories ORDER BY sort, id")
        .fetch_all(&mut *db)
        .await
}

/// The preview of `sections` against the library as it is now (`error`: why a confirm failed).
async fn preview_card(
    state: &AppState,
    mut sections: Vec<Section>,
    error: Option<String>,
) -> Result<ImportCard, sqlx::Error> {
    let mut conn = state.db.acquire().await?;
    let existing = existing(&mut conn).await?;
    let categories = categories(&mut conn).await?;
    let phones: Vec<(i64, String)> = sqlx::query_as("SELECT id, name FROM devices ORDER BY name")
        .fetch_all(&mut *conn)
        .await?;
    let doc = music_import::document(&sections);
    music_import::plan(
        &mut sections,
        &existing.targets,
        &existing.keys,
        existing.count,
        None,
    );
    let mut importable = 0;
    let mut skipped = 0;
    let sections = sections
        .iter()
        .enumerate()
        .map(|(s, section)| {
            let rows: Vec<PreviewRow> = section
                .rows
                .iter()
                .enumerate()
                .map(|(r, row)| {
                    let (settings, reason) = match &row.outcome {
                        Ok(candidate) => {
                            importable += 1;
                            (
                                format!(
                                    "{} · {} · {} · {}",
                                    music::kind_label(candidate.source, Some(&candidate.target)),
                                    order_text(&candidate.order),
                                    cache_text(candidate.cache),
                                    if candidate.resume {
                                        "continues where it stopped"
                                    } else {
                                        "starts from the beginning"
                                    }
                                ),
                                None,
                            )
                        }
                        Err(skip) => {
                            skipped += 1;
                            (String::new(), Some(skip.reason()))
                        }
                    };
                    PreviewRow {
                        value: format!("{s}.{r}"),
                        name: if row.name.is_empty() {
                            "(no name)".to_string()
                        } else {
                            row.name.clone()
                        },
                        target: row.target.clone(),
                        settings,
                        reason,
                    }
                })
                .collect();
            PreviewSection {
                index: s,
                name: section_label(section),
                new_name: music_import::new_category_name(&section.name),
                selected: default_choice(&categories, &section.name),
                importable: rows.iter().filter(|r| r.reason.is_none()).count(),
                rows,
            }
        })
        .collect();
    Ok(ImportCard {
        open: true,
        error,
        result: None,
        preview: Some(ImportPreview {
            doc,
            sections,
            phones,
            importable,
            skipped,
        }),
    })
}

fn refused(error: impl Into<String>) -> ImportCard {
    ImportCard {
        open: true,
        error: Some(error.into()),
        ..Default::default()
    }
}

/// `POST /music/import` (multipart `library`): the preview, nothing stored. Not JSON, no
/// `sections` list or over 1 MB: 400 with the reason by the file field.
pub async fn preview(State(state): State<AppState>, mut multipart: Multipart) -> Response {
    const TOO_BIG: &str =
        "That file is over 1 MB - a Vibb library is much smaller. Pick the box's library.json.";
    let mut bytes = None;
    loop {
        match multipart.next_field().await {
            Ok(Some(field)) if field.name() == Some("library") => match field.bytes().await {
                Ok(read) => bytes = Some(read),
                Err(_) => {
                    return render_import(&state, StatusCode::BAD_REQUEST, refused(TOO_BIG)).await;
                }
            },
            Ok(Some(_)) => {}
            Ok(None) => break,
            Err(_) => {
                return render_import(&state, StatusCode::BAD_REQUEST, refused(TOO_BIG)).await;
            }
        }
    }
    let Some(bytes) = bytes.filter(|b| !b.is_empty()) else {
        let card = refused("Choose the library file first.");
        return render_import(&state, StatusCode::BAD_REQUEST, card).await;
    };
    if bytes.len() > MAX_IMPORT_BYTES {
        return render_import(&state, StatusCode::BAD_REQUEST, refused(TOO_BIG)).await;
    }
    let sections = match music_import::parse(&bytes) {
        Ok(sections) => sections,
        Err(error) => return render_import(&state, StatusCode::BAD_REQUEST, refused(error)).await,
    };
    match preview_card(&state, sections, None).await {
        Ok(card) => render_import(&state, StatusCode::OK, card).await,
        Err(err) => server_error(err, "couldn't preview a Vibb import"),
    }
}

/// What the confirm did.
struct Done {
    /// The file planned as the preview did (every row) - what an unticked row would have met.
    as_previewed: Vec<Section>,
    added: Vec<(i64, String)>,
    categories_created: usize,
    ticked_phones: Vec<i64>,
    /// (phone, entry name) ticks left out: the phone's library would pass the limit.
    misses: Vec<(i64, String)>,
}

/// `POST /music/import/confirm`: `doc` (the preview's document), `row` = "s.r" per ticked entry,
/// `category_<s>` = a category id or "new", `phone` = ids. Everything is checked again as for a
/// fresh upload, in one transaction: new categories, the entries in file order, the ticks - never
/// past a phone's limit (`music::tick_fits`). Back to `/music#import` with what happened.
pub async fn confirm(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    session: tower_sessions::Session,
    Form(form): Form<Vec<(String, String)>>,
) -> Response {
    let mut doc: Option<&str> = None;
    let mut wanted: HashSet<(usize, usize)> = HashSet::new();
    let mut choices: HashMap<usize, String> = HashMap::new();
    let mut phones: Vec<i64> = Vec::new();
    let mut malformed = false;
    for (key, value) in &form {
        match key.as_str() {
            "doc" => doc = Some(value),
            "row" => match value
                .split_once('.')
                .and_then(|(s, r)| Some((s.parse::<usize>().ok()?, r.parse::<usize>().ok()?)))
            {
                Some(row) => {
                    wanted.insert(row);
                }
                None => malformed = true,
            },
            "phone" => match value.parse::<i64>() {
                Ok(id) if !phones.contains(&id) => phones.push(id),
                Ok(_) => {}
                Err(_) => malformed = true,
            },
            other => {
                if let Some(index) = other.strip_prefix("category_") {
                    match index.parse::<usize>() {
                        Ok(index) => {
                            choices.insert(index, value.clone());
                        }
                        Err(_) => malformed = true,
                    }
                }
            }
        }
    }
    let Some(doc) = doc.filter(|d| d.len() <= MAX_IMPORT_BYTES) else {
        let card = refused("The preview was lost - choose the file and preview it again.");
        return render_import(&state, StatusCode::BAD_REQUEST, card).await;
    };
    let mut sections = match music_import::parse(doc.as_bytes()) {
        Ok(sections) => sections,
        Err(_) => {
            let card = refused("The preview was lost - choose the file and preview it again.");
            return render_import(&state, StatusCode::BAD_REQUEST, card).await;
        }
    };
    let again = |error: &str, sections: Vec<Section>| {
        let state = state.clone();
        let error = error.to_string();
        async move {
            match preview_card(&state, sections, Some(error)).await {
                Ok(card) => render_import(&state, StatusCode::BAD_REQUEST, card).await,
                Err(err) => server_error(err, "couldn't preview a Vibb import"),
            }
        }
    };
    if malformed {
        return again(
            "That form wasn't filled in as expected - try again.",
            sections,
        )
        .await;
    }
    let file = sections.clone();

    let outcome: Result<Result<Done, &'static str>, sqlx::Error> = async {
        let mut tx = state.db.begin().await?;
        let existing = existing(&mut tx).await?;
        let categories = categories(&mut tx).await?;
        let mut as_previewed = sections.clone();
        music_import::plan(
            &mut as_previewed,
            &existing.targets,
            &existing.keys,
            existing.count,
            None,
        );
        let accepted = music_import::plan(
            &mut sections,
            &existing.targets,
            &existing.keys,
            existing.count,
            Some(&wanted),
        );
        let known: HashSet<i64> = sqlx::query_scalar("SELECT id FROM devices")
            .fetch_all(&mut *tx)
            .await?
            .into_iter()
            .collect();
        if phones.iter().any(|p| !known.contains(p)) {
            return Ok(Err(
                "That phone doesn't exist any more - pick the phones again.",
            ));
        }
        // A category per section with something to add; "New: <section>" made once per name.
        let mut section_category: HashMap<usize, i64> = HashMap::new();
        let mut created: HashMap<String, i64> = HashMap::new();
        for &(s, r) in &accepted {
            if section_category.contains_key(&s) {
                continue;
            }
            let choice = choices
                .get(&s)
                .cloned()
                .unwrap_or_else(|| default_choice(&categories, &sections[s].name));
            let id = if choice == "new" {
                let name = music_import::new_category_name(&sections[s].name);
                match created.get(&name.to_lowercase()) {
                    Some(id) => *id,
                    None => {
                        let source = sections[s].rows[r]
                            .outcome
                            .as_ref()
                            .map(|c| c.source)
                            .unwrap_or("rss");
                        let (icon, color) = music::default_category(&categories, source)
                            .filter(|c| music::icon_known(&c.icon))
                            .filter(|c| music::color_hex(&c.color).is_some())
                            .map(|c| (c.icon.clone(), c.color.clone()))
                            .unwrap_or_else(|| ("music_note".to_string(), "sky".to_string()));
                        let id: i64 = sqlx::query_scalar(
                            "INSERT INTO music_categories (name, icon, color, sort) VALUES \
                             (?, ?, ?, (SELECT COALESCE(MAX(sort), 0) + 10 FROM music_categories)) \
                             RETURNING id",
                        )
                        .bind(&name)
                        .bind(&icon)
                        .bind(&color)
                        .fetch_one(&mut *tx)
                        .await?;
                        created.insert(name.to_lowercase(), id);
                        id
                    }
                }
            } else {
                match choice.parse::<i64>() {
                    Ok(id) if categories.iter().any(|c| c.id == id) => id,
                    _ => {
                        return Ok(Err(
                            "That category doesn't exist any more - pick another one.",
                        ));
                    }
                }
            };
            section_category.insert(s, id);
        }
        // The entries, in file order, after every existing one.
        let mut added: Vec<(music::LibEntry, String)> = Vec::new();
        for &(s, r) in &accepted {
            let Ok(candidate) = &sections[s].rows[r].outcome else {
                continue;
            };
            let category = section_category[&s];
            let (id, sort): (i64, i64) = sqlx::query_as(&format!(
                "INSERT INTO music_entries (name, category_id, source, target, key, play_order, \
                 cache, resume, sort) VALUES (?, ?, ?, ?, ?, ?, ?, ?, {NEXT_SORT}) \
                 RETURNING id, sort"
            ))
            .bind(&candidate.name)
            .bind(category)
            .bind(candidate.source)
            .bind(&candidate.target)
            .bind(&candidate.key)
            .bind(&candidate.order)
            .bind(candidate.cache)
            .bind(candidate.resume)
            .fetch_one(&mut *tx)
            .await?;
            // As `music::library_for` lists it - for the size estimate below.
            let entry = music::LibEntry {
                id,
                key: candidate.key.clone(),
                name: candidate.name.clone(),
                category,
                source: candidate.source.to_string(),
                target: Some(candidate.target.clone()),
                order: candidate.order.clone(),
                cache: candidate.cache,
                resume: candidate.resume,
                cover: None,
                sort,
            };
            added.push((entry, candidate.name.clone()));
        }
        // The ticks, never past a phone's limit (QA #4) - what doesn't fit stays unticked there.
        // One library build per phone plus one to confirm (qa-21-step1-code #3): each entry's
        // share of the document is estimated from its own JSON (and its category's, when new to
        // the phone); the final build takes back from the end whatever still doesn't fit.
        let lib_categories: HashMap<i64, music::LibCategory> = self::categories(&mut tx)
            .await?
            .into_iter()
            .map(|c| {
                (
                    c.id,
                    music::LibCategory {
                        id: c.id,
                        name: c.name,
                        icon: c.icon,
                        color: c.color,
                        sort: c.sort,
                    },
                )
            })
            .collect();
        let empty = music::Library::build(Vec::new(), Vec::new(), Vec::new())
            .json
            .len();
        let mut ticked_phones = Vec::new();
        let mut misses = Vec::new();
        for &phone in &phones {
            let current = music::library_for(&mut tx, phone, None).await?;
            let (mut size, mut present): (usize, HashSet<i64>) = match &current {
                Some(library) => (
                    library.json.len(),
                    library.category_ids.iter().copied().collect(),
                ),
                None => (empty, HashSet::new()),
            };
            let mut ticked: Vec<(i64, String)> = Vec::new();
            for (entry, name) in &added {
                let mut share = serde_json::to_vec(entry).map_or(usize::MAX / 4, |j| j.len()) + 1;
                if !present.contains(&entry.category) {
                    share += lib_categories
                        .get(&entry.category)
                        .and_then(|c| serde_json::to_vec(c).ok())
                        .map_or(usize::MAX / 4, |j| j.len())
                        + 1;
                }
                if size + share > music::MAX_LIBRARY_BYTES {
                    misses.push((phone, name.clone()));
                    continue;
                }
                sqlx::query(
                    "INSERT OR IGNORE INTO device_music_entries (device_id, entry_id) VALUES (?, ?)",
                )
                .bind(phone)
                .bind(entry.id)
                .execute(&mut *tx)
                .await?;
                size += share;
                present.insert(entry.category);
                ticked.push((entry.id, name.clone()));
            }
            if ticked.is_empty() {
                continue;
            }
            // The estimate errs on the large side; the real size decides.
            while let Some(library) = music::library_for(&mut tx, phone, None).await?
                && library.json.len() > music::MAX_LIBRARY_BYTES
            {
                let Some((id, name)) = ticked.pop() else {
                    break;
                };
                sqlx::query("DELETE FROM device_music_entries WHERE device_id = ? AND entry_id = ?")
                    .bind(phone)
                    .bind(id)
                    .execute(&mut *tx)
                    .await?;
                misses.push((phone, name));
            }
            if !ticked.is_empty() {
                ticked_phones.push(phone);
            }
        }
        tx.commit().await?;
        Ok(Ok(Done {
            as_previewed,
            added: added.into_iter().map(|(entry, name)| (entry.id, name)).collect(),
            categories_created: created.len(),
            ticked_phones,
            misses,
        }))
    }
    .await;
    let done = match outcome {
        Ok(Ok(done)) => done,
        Ok(Err(error)) => return again(error, file).await,
        Err(err) => return server_error(err, "couldn't import a Vibb library"),
    };

    let phone_names: HashMap<i64, String> =
        match sqlx::query_as::<_, (i64, String)>("SELECT id, name FROM devices")
            .fetch_all(&state.db)
            .await
        {
            Ok(rows) => rows.into_iter().collect(),
            Err(err) => return server_error(err, "couldn't read the phones"),
        };
    let result = describe(&sections, &wanted, &done, &phone_names);
    nudge(&state, &done.ticked_phones);
    let skipped = skipped_rows(&sections, &done.as_previewed, &wanted).len();
    log_event(
        &state,
        &admin,
        "music_library_imported",
        &format!(
            "{} entries, {} categories, {skipped} skipped",
            done.added.len(),
            done.categories_created
        ),
    )
    .await;
    if let Err(err) = session.insert(RESULT_FLASH, &result).await {
        tracing::warn!(%err, "couldn't keep the import's result for the page");
    }
    Redirect::to("/music#import").into_response()
}

/// Every row that wasn't imported for a reason, as "Section / name: reason": a ticked row as the
/// confirm found it, an unticked one as the preview showed it (no tick there means it was
/// skipped). A row the parent unticked isn't "skipped".
fn skipped_rows(
    sections: &[Section],
    as_previewed: &[Section],
    wanted: &HashSet<(usize, usize)>,
) -> Vec<String> {
    let mut lines = Vec::new();
    for (s, section) in sections.iter().enumerate() {
        for (r, row) in section.rows.iter().enumerate() {
            let outcome = if wanted.contains(&(s, r)) {
                &row.outcome
            } else {
                &as_previewed[s].rows[r].outcome
            };
            let Err(skip) = outcome else {
                continue;
            };
            let name = if row.name.is_empty() {
                "(no name)"
            } else {
                row.name.as_str()
            };
            lines.push(format!(
                "{} / {name}: {}",
                section_label(section),
                skip.reason()
            ));
        }
    }
    lines
}

fn describe(
    sections: &[Section],
    wanted: &HashSet<(usize, usize)>,
    done: &Done,
    phone_names: &HashMap<i64, String>,
) -> ImportResult {
    let skipped = skipped_rows(sections, &done.as_previewed, wanted);
    let left_out = done
        .as_previewed
        .iter()
        .enumerate()
        .flat_map(|(s, section)| {
            section
                .rows
                .iter()
                .enumerate()
                .map(move |(r, row)| (s, r, row))
        })
        .filter(|(s, r, row)| row.outcome.is_ok() && !wanted.contains(&(*s, *r)))
        .count();
    let name_of = |id: &i64| {
        phone_names
            .get(id)
            .cloned()
            .unwrap_or_else(|| format!("phone {id}"))
    };
    let mut summary = if done.added.is_empty() {
        if skipped.is_empty() {
            "Nothing was imported.".to_string()
        } else {
            "Nothing new to import.".to_string()
        }
    } else {
        let mut text = format!(
            "Imported {} entr{}",
            done.added.len(),
            if done.added.len() == 1 { "y" } else { "ies" }
        );
        if done.categories_created > 0 {
            text.push_str(&format!(
                " and {} new categor{}",
                done.categories_created,
                if done.categories_created == 1 {
                    "y"
                } else {
                    "ies"
                }
            ));
        }
        if done.ticked_phones.is_empty() {
            text.push_str(", on no phone yet - tick them on each phone's Music card.");
        } else {
            let names: Vec<String> = done.ticked_phones.iter().map(name_of).collect();
            text.push_str(&format!(", ticked on {}.", names.join(", ")));
        }
        text
    };
    if !skipped.is_empty() {
        summary.push_str(&format!(" {} skipped:", skipped.len()));
    }
    if left_out > 0 {
        summary.push_str(&format!(" ({left_out} you left out.)"));
    }
    let mut lines = skipped;
    lines.extend(done.misses.iter().map(|(phone, name)| {
        format!(
            "Not ticked on {}: \"{name}\" - that phone's music library would pass {} MB.",
            name_of(phone),
            music::MAX_LIBRARY_BYTES / 1_000_000
        )
    }));
    if lines.len() > MAX_RESULT_LINES {
        let more = lines.len() - MAX_RESULT_LINES;
        lines.truncate(MAX_RESULT_LINES);
        lines.push(format!("... and {more} more."));
    }
    ImportResult { summary, lines }
}
