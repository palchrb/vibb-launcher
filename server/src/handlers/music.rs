//! The parent's music pages (design 21 §1.2): `/music` (entries, categories, Storytel, the music
//! app's catalog row), `/music/entries/{id}` (an entry's settings, cover, own files) and the device
//! page's Music card (`#music`: the app, the ticks, mobile data, the volume cap, Storytel, what the
//! phone reports).
//!
//! No form makes the page jump to the top (memory rule, server/CLAUDE.md): every save redirects to
//! its card's anchor (`#entry-<id>`, `#categories`, `#category-<id>`, `#storytel`, `#music`, ...);
//! a refused one (400) shows the page again with the values as entered and the error by the field,
//! which is `autofocus`ed. Every change nudges the phones it reaches (SSE) and is in the security
//! log (`music_*`, `storytel_login_saved/cleared`).

use std::collections::HashMap;
use std::path::{Path as FsPath, PathBuf};

use askama::Template;
use axum::extract::multipart::Field;
use axum::extract::{Multipart, Path, State};
use axum::http::{StatusCode, header};
use axum::response::{Html, IntoResponse, Redirect, Response};
use axum::{Extension, Form};
use chrono::{DateTime, Utc};
use sha2::{Digest, Sha256};
use tokio::io::AsyncWriteExt;

use crate::AppState;
use crate::models::{DevicePolicy, DeviceStatus, InstalledApp};
use crate::music::{
    self, CACHE_CHOICES, MAX_CATEGORY_NAME_CHARS, MAX_ENTRIES, MAX_FILE_BYTES, MAX_FILES_PER_ENTRY,
    MAX_LIBRARY_BYTES, MAX_NAME_CHARS, MIN_FREE_BYTES, MusicCategory, MusicEntry, MusicFile,
    ORDERS, VOLUME_CAPS,
};
use crate::photos::{self, MUSIC_COVERS, Shape};
use crate::security::{self, CurrentAdmin};

type FormMap = HashMap<String, String>;

pub(super) fn server_error(err: impl std::fmt::Display, what: &str) -> Response {
    tracing::error!(%err, "{what}");
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        "Couldn't load or save the music library - nothing was changed. Check the server log.",
    )
        .into_response()
}

fn field(form: &FormMap, key: &str) -> String {
    form.get(key).cloned().unwrap_or_default()
}

pub(super) fn nudge(state: &AppState, devices: &[i64]) {
    for device in devices {
        let _ = state.command_notify.send(*device);
    }
}

pub(super) async fn log_event(
    state: &AppState,
    admin: &crate::models::AdminUser,
    kind: &str,
    detail: &str,
) {
    security::record_security_event(&state.db, kind, Some(&admin.username), None, Some(detail))
        .await;
}

/// A random file-name part (never anything the browser sent).
fn random_name() -> String {
    security::generate_device_token()[..32].to_string()
}

// ------------------------------------------------------------------------------------------------
// The music app's catalog row
// ------------------------------------------------------------------------------------------------

/// The music app's catalog row: by package (migration 0049 names it) or by its `music-v` prefix.
pub(crate) async fn music_app_row(
    db: &sqlx::SqlitePool,
) -> Result<Option<crate::models::TrackedApp>, sqlx::Error> {
    sqlx::query_as(
        "SELECT * FROM tracked_apps WHERE package_name IN ('me.vibb.music', 'me.vibb.music.debug') \
         OR release_tag_prefix = ? ORDER BY id LIMIT 1",
    )
    .bind(crate::config::MUSIC_RELEASE_TAG_PREFIX)
    .fetch_optional(db)
    .await
}

/// `POST /music/catalog-row`: adds the music app to the Apps catalog when it isn't there (a server
/// whose launcher row 0049 didn't recognise): the configured release repo, `music-v*` releases,
/// `vibb-music.apk`, stable only, "Musikk" on the phones.
pub async fn add_catalog_row(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    match music_app_row(&state.db).await {
        Ok(Some(_)) => return Redirect::to("/music#app").into_response(),
        Ok(None) => {}
        Err(err) => return server_error(err, "music app row lookup failed"),
    }
    let inserted = sqlx::query(
        "INSERT INTO tracked_apps (name, package_name, source_type, github_repo, asset_pattern, \
         include_prereleases, release_tag_prefix, display_label, display_icon, display_color) \
         VALUES ('Vibb Musikk', 'me.vibb.music', 'github', ?, '^vibb-music\\.apk$', 0, ?, \
         'Musikk', 'music_note', 'peach')",
    )
    .bind(&state.config.server_release_repo)
    .bind(crate::config::MUSIC_RELEASE_TAG_PREFIX)
    .execute(&state.db)
    .await;
    if let Err(err) = inserted {
        return server_error(err, "couldn't add the music app to the catalog");
    }
    log_event(
        &state,
        &admin,
        "music_app_catalog_added",
        &format!("Vibb music app from {}", state.config.server_release_repo),
    )
    .await;
    Redirect::to("/music#app").into_response()
}

// ------------------------------------------------------------------------------------------------
// /music
// ------------------------------------------------------------------------------------------------

pub struct EntryRow {
    pub id: i64,
    pub name: String,
    pub kind: &'static str,
    pub category: String,
    pub category_color: String,
    pub category_icon: String,
    pub phones: i64,
    pub files: i64,
    pub missing: i64,
    pub cover: Option<String>,
    pub first: bool,
    pub last: bool,
    /// What the sweep knows (NRK and RSS entries, design 21b §3).
    pub sweep: Option<SweepCard>,
    /// One line per phone with the entry ticked.
    pub phone_lines: Vec<String>,
}

/// An NRK/RSS card's sweep part (design 21b §3).
pub struct SweepCard {
    /// The source's own title, when it differs from the entry's name.
    pub source_title: Option<String>,
    /// A feed on the home network or tailnet.
    pub lan: bool,
    /// "42 episodes · newest: <title> · 9 Oct".
    pub episodes: Option<String>,
    /// "first 100 episodes (the series has more)", "list cut to the newest 812 (size limit)".
    pub notes: Vec<String>,
    pub status: StatusBlock,
    /// Check now isn't allowed before this ("14:20 UTC").
    pub check_after: Option<String>,
    pub cache: i64,
    pub caches: Vec<(i64, String)>,
}

/// The card's status (`.music-status`, two lines at least, swapped in place by
/// `static/music-cards.js` while `busy`).
#[derive(Clone)]
pub struct StatusBlock {
    pub entry_id: i64,
    pub text: String,
    /// "ok", "busy" or "error".
    pub tone: &'static str,
    pub busy: bool,
}

pub struct CategoryView {
    pub id: i64,
    pub name: String,
    pub icon: String,
    pub color: String,
    pub tile: String,
    pub entries: i64,
    pub first: bool,
    pub last: bool,
    pub error: Option<String>,
    pub error_field: &'static str,
}

/// The values of the "Add from a link" form.
#[derive(Default)]
pub struct AddForm {
    pub link: String,
    pub category: String,
    pub error: Option<String>,
}

/// "Own files" (a new entry for uploads).
#[derive(Default)]
pub struct OwnForm {
    pub name: String,
    pub category: String,
    pub error: Option<String>,
}

/// "Add a category".
pub struct NewCategoryForm {
    pub name: String,
    pub icon: String,
    pub color: String,
    pub error: Option<String>,
    pub error_field: &'static str,
}

impl Default for NewCategoryForm {
    fn default() -> Self {
        NewCategoryForm {
            name: String::new(),
            icon: "music_note".to_string(),
            color: "sky".to_string(),
            error: None,
            error_field: "",
        }
    }
}

pub struct StorytelCard {
    /// The server has a usable key (else the form is disabled).
    pub key_available: bool,
    /// "9 Oct 2026" when a login is stored.
    pub saved_on: Option<String>,
    /// A login is stored but sealed with another key: enter it again.
    pub stale: bool,
    pub phones: i64,
    pub error: Option<String>,
}

pub struct CatalogCard {
    pub id: i64,
    pub name: String,
    pub release: Option<String>,
    pub phones: i64,
}

#[derive(Template)]
#[template(path = "music.html")]
struct MusicTemplate {
    title: String,
    entries: Vec<EntryRow>,
    entry_count: i64,
    max_entries: i64,
    categories: Vec<CategoryView>,
    category_options: Vec<(i64, String)>,
    icons: Vec<&'static str>,
    colors: Vec<(&'static str, &'static str)>,
    add: AddForm,
    own: OwnForm,
    new_category: NewCategoryForm,
    storytel: StorytelCard,
    catalog: Option<CatalogCard>,
    /// "Import from Vibb" (design 21a, `#import`).
    import: super::music_import::ImportCard,
    /// Own files on disk no entry names (qa-21-step1-code #7): how many and how big.
    orphans: Option<(usize, String)>,
    /// The sweep setting (design 21b §3, `#sweep`): the hours and every choice.
    sweep_hours: i64,
    sweep_choices: Vec<i64>,
}

/// What a refused form on `/music` brings back.
#[derive(Default)]
struct Entered {
    add: Option<AddForm>,
    own: Option<OwnForm>,
    new_category: Option<NewCategoryForm>,
    /// A refused category save: its id, the values and the error.
    category: Option<(i64, String, String, String, String, &'static str)>,
    storytel_error: Option<String>,
    import: Option<super::music_import::ImportCard>,
}

fn tile_of(color: &str) -> String {
    music::color_hex(color).unwrap_or("#5C6370").to_string()
}

/// `GET /music`; shows (once) how the last import went (design 21a, a session flash).
pub async fn show(State(state): State<AppState>, session: tower_sessions::Session) -> Response {
    let result: Option<super::music_import::ImportResult> = session
        .remove(super::music_import::RESULT_FLASH)
        .await
        .ok()
        .flatten();
    let entered = Entered {
        import: result.map(|result| super::music_import::ImportCard {
            open: true,
            result: Some(result),
            ..Default::default()
        }),
        ..Default::default()
    };
    render_music(&state, StatusCode::OK, entered).await
}

/// The page as it is, without a session (the tests call it with another state).
#[cfg(test)]
pub(crate) async fn render_plain(state: &AppState) -> Response {
    render_music(state, StatusCode::OK, Entered::default()).await
}

/// The page with the import card as given (a preview, or a refused import).
pub(super) async fn render_import(
    state: &AppState,
    status: StatusCode,
    card: super::music_import::ImportCard,
) -> Response {
    let entered = Entered {
        import: Some(card),
        ..Default::default()
    };
    render_music(state, status, entered).await
}

async fn render_music(state: &AppState, status: StatusCode, entered: Entered) -> Response {
    let page = match music_page(state, entered).await {
        Ok(page) => page,
        Err(err) => return server_error(err, "couldn't load the music page"),
    };
    (status, Html(page.render().unwrap())).into_response()
}

async fn music_page(state: &AppState, entered: Entered) -> Result<MusicTemplate, sqlx::Error> {
    let categories: Vec<MusicCategory> =
        sqlx::query_as("SELECT * FROM music_categories ORDER BY sort, id")
            .fetch_all(&state.db)
            .await?;
    let entries: Vec<MusicEntry> = sqlx::query_as(
        "SELECT id, name, category_id, source, target, key, play_order, cache, resume, \
         cover_hash, sort FROM music_entries ORDER BY sort, id",
    )
    .fetch_all(&state.db)
    .await?;
    let phones: HashMap<i64, i64> = sqlx::query_as::<_, (i64, i64)>(
        "SELECT entry_id, COUNT(*) FROM device_music_entries GROUP BY entry_id",
    )
    .fetch_all(&state.db)
    .await?
    .into_iter()
    .collect();
    let files: HashMap<i64, (i64, i64)> = sqlx::query_as::<_, (i64, i64, i64)>(
        "SELECT entry_id, COUNT(*), SUM(missing) FROM music_files GROUP BY entry_id",
    )
    .fetch_all(&state.db)
    .await?
    .into_iter()
    .map(|(entry, count, missing)| (entry, (count, missing)))
    .collect();
    let in_use: HashMap<i64, i64> = sqlx::query_as::<_, (i64, i64)>(
        "SELECT category_id, COUNT(*) FROM music_entries GROUP BY category_id",
    )
    .fetch_all(&state.db)
    .await?
    .into_iter()
    .collect();

    let category_of = |id: i64| categories.iter().find(|c| c.id == id);
    let count = entries.len();
    let view = SweepView::load(state, state.music_sweep.now()).await?;
    let entry_rows = entries
        .iter()
        .enumerate()
        .map(|(index, e)| {
            let category = category_of(e.category_id);
            let (file_count, missing) = files.get(&e.id).copied().unwrap_or((0, 0));
            EntryRow {
                sweep: view.card(e),
                phone_lines: view.phone_lines(e),
                id: e.id,
                name: e.name.clone(),
                kind: music::kind_label(&e.source, e.target.as_deref()),
                category: category.map(|c| c.name.clone()).unwrap_or_default(),
                category_color: tile_of(category.map_or("", |c| c.color.as_str())),
                category_icon: category
                    .map(|c| c.icon.clone())
                    .filter(|i| music::icon_known(i))
                    .unwrap_or_else(|| "music_note".to_string()),
                phones: phones.get(&e.id).copied().unwrap_or(0),
                files: file_count,
                missing,
                // The parent's cover, else the source's (design 21b §3), else the category tile.
                cover: e.cover_hash.clone().or_else(|| view.source_cover(e.id)),
                first: index == 0,
                last: index + 1 == count,
            }
        })
        .collect();

    let category_count = categories.len();
    let category_views = categories
        .iter()
        .enumerate()
        .map(|(index, c)| {
            let mut view = CategoryView {
                id: c.id,
                name: c.name.clone(),
                icon: c.icon.clone(),
                color: c.color.clone(),
                tile: tile_of(&c.color),
                entries: in_use.get(&c.id).copied().unwrap_or(0),
                first: index == 0,
                last: index + 1 == category_count,
                error: None,
                error_field: "",
            };
            if let Some((id, name, icon, color, error, error_field)) = &entered.category
                && *id == c.id
            {
                view.name = name.clone();
                view.icon = icon.clone();
                view.color = color.clone();
                view.tile = tile_of(color);
                view.error = Some(error.clone());
                view.error_field = error_field;
            }
            view
        })
        .collect();

    let stored: (Option<Vec<u8>>, Option<String>, Option<String>) = sqlx::query_as(
        "SELECT ciphertext, key_fingerprint, saved_at FROM music_storytel WHERE id = 1",
    )
    .fetch_one(&state.db)
    .await?;
    let storytel_phones: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM device_policy WHERE music_storytel = 1")
            .fetch_one(&state.db)
            .await?;
    let saved_on = stored.0.as_ref().map(|_| {
        stored
            .2
            .as_deref()
            .and_then(|t| chrono::NaiveDateTime::parse_from_str(t, "%Y-%m-%d %H:%M:%S").ok())
            .map(|t| t.format("%-d %b %Y").to_string())
            .unwrap_or_else(|| "earlier".to_string())
    });
    let stale = stored.0.is_some()
        && state
            .music_key
            .as_ref()
            .is_none_or(|key| stored.1.as_deref() != Some(key.fingerprint.as_str()));
    let storytel = StorytelCard {
        key_available: state.music_key.is_some(),
        saved_on,
        stale,
        phones: storytel_phones,
        error: entered.storytel_error,
    };

    let catalog = match music_app_row(&state.db).await? {
        Some(app) => {
            let phones: i64 = sqlx::query_scalar(
                "SELECT COUNT(*) FROM device_tracked_apps WHERE tracked_app_id = ?",
            )
            .bind(app.id)
            .fetch_one(&state.db)
            .await?;
            Some(CatalogCard {
                id: app.id,
                name: app.name,
                release: app.latest_release_tag,
                phones,
            })
        }
        None => None,
    };

    Ok(MusicTemplate {
        title: "Music".to_string(),
        entries: entry_rows,
        entry_count: count as i64,
        max_entries: MAX_ENTRIES,
        category_options: categories.iter().map(|c| (c.id, c.name.clone())).collect(),
        categories: category_views,
        icons: crate::music_icons::ICONS.iter().map(|(k, _)| *k).collect(),
        colors: crate::music_icons::COLORS.to_vec(),
        add: entered.add.unwrap_or_default(),
        own: entered.own.unwrap_or_default(),
        new_category: entered.new_category.unwrap_or_default(),
        storytel,
        catalog,
        import: entered.import.unwrap_or_default(),
        orphans: music::orphan_files(&state.db, &state.music_files_dir)
            .await
            .map(|o| {
                (!o.files.is_empty()).then(|| (o.files.len(), music::megabytes(o.bytes as i64)))
            })?,
        sweep_hours: view.setting,
        sweep_choices: crate::music_sweep::SWEEP_HOURS.to_vec(),
    })
}

/// `POST /music/orphans/delete`: deletes the own files no entry names (left by restoring an older
/// backup), and the directories of entries that are gone. Looked up again here, so a file that got
/// its row meanwhile stays.
pub async fn delete_orphans(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let orphans = match music::orphan_files(&state.db, &state.music_files_dir).await {
        Ok(orphans) => orphans,
        Err(err) => return server_error(err, "couldn't list the music files"),
    };
    let mut deleted = 0;
    for file in &orphans.files {
        if tokio::fs::remove_file(file).await.is_ok() {
            deleted += 1;
        }
        // An entry directory left empty goes too (best effort: fails while not empty).
        if let Some(parent) = file.parent() {
            tokio::fs::remove_dir(parent).await.ok();
        }
    }
    log_event(
        &state,
        &admin,
        "music_orphans_deleted",
        &format!(
            "{deleted} file(s), {}",
            music::megabytes(orphans.bytes as i64)
        ),
    )
    .await;
    Redirect::to("/music#entries").into_response()
}

/// The category for a new entry: the one picked, else the first whose `default_kind` matches the
/// source, else the first. `Err` when there is none, or the picked one doesn't exist.
async fn pick_category(
    db: &sqlx::SqlitePool,
    picked: &str,
    source: &str,
) -> Result<Result<i64, &'static str>, sqlx::Error> {
    let categories: Vec<MusicCategory> =
        sqlx::query_as("SELECT * FROM music_categories ORDER BY sort, id")
            .fetch_all(db)
            .await?;
    if let Ok(id) = picked.trim().parse::<i64>() {
        return Ok(categories
            .iter()
            .find(|c| c.id == id)
            .map(|c| c.id)
            .ok_or("That category doesn't exist any more."));
    }
    Ok(music::default_category(&categories, source)
        .map(|c| c.id)
        .ok_or("Add a category first."))
}

async fn entry_count(db: &sqlx::SqlitePool) -> Result<i64, sqlx::Error> {
    sqlx::query_scalar("SELECT COUNT(*) FROM music_entries")
        .fetch_one(db)
        .await
}

/// The `sort` of an entry added now: after every other (the carousel order).
pub(super) const NEXT_SORT: &str = "(SELECT COALESCE(MAX(sort), 0) + 10 FROM music_entries)";
/// Ends an `INSERT INTO music_entries ... SELECT ...` so it inserts nothing once the library holds
/// the bound limit (`MAX_ENTRIES`): the count and the write are one statement.
pub(super) const BELOW_ENTRY_LIMIT: &str = "WHERE (SELECT COUNT(*) FROM music_entries) < ?";

/// `POST /music`: adds an NRK or RSS entry from a pasted link, after one GET that must look like
/// the source; its title becomes the name. 400 (the page, the link kept, the reason by the field)
/// when it isn't.
pub async fn add_link(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<FormMap>,
) -> Response {
    let link_text = field(&form, "link");
    let picked = field(&form, "category");
    let refuse = |error: String| Entered {
        add: Some(AddForm {
            link: link_text.clone(),
            category: picked.clone(),
            error: Some(error),
        }),
        ..Default::default()
    };
    let link = match music::parse_link(&link_text) {
        Ok(link) => link,
        Err(error) => {
            return render_music(&state, StatusCode::BAD_REQUEST, refuse(error.to_string())).await;
        }
    };
    match entry_count(&state.db).await {
        Ok(n) if n >= MAX_ENTRIES => {
            let error = format!("The library has {MAX_ENTRIES} entries - delete one first.");
            return render_music(&state, StatusCode::BAD_REQUEST, refuse(error)).await;
        }
        Ok(_) => {}
        Err(err) => return server_error(err, "music entry count failed"),
    }
    let target = link.target();
    let exists: Option<String> =
        match sqlx::query_scalar("SELECT name FROM music_entries WHERE target = ?")
            .bind(&target)
            .fetch_optional(&state.db)
            .await
        {
            Ok(name) => name,
            Err(err) => return server_error(err, "music entry lookup failed"),
        };
    if let Some(name) = exists {
        let error = format!("That link is already in the library (\"{name}\").");
        return render_music(&state, StatusCode::BAD_REQUEST, refuse(error)).await;
    }
    let category = match pick_category(&state.db, &picked, link.source()).await {
        Ok(Ok(id)) => id,
        Ok(Err(error)) => {
            return render_music(&state, StatusCode::BAD_REQUEST, refuse(error.to_string())).await;
        }
        Err(err) => return server_error(err, "music category lookup failed"),
    };
    let title = match music::check_link(state.music_fetch.as_ref(), &link).await {
        Ok(title) => title,
        Err(err) => {
            return render_music(&state, StatusCode::BAD_REQUEST, refuse(err.message())).await;
        }
    };
    // The limit is checked in the INSERT itself (qa-21-step1-code #6): two adds can't pass it.
    let inserted: Result<Option<i64>, sqlx::Error> = sqlx::query_scalar(&format!(
        "INSERT INTO music_entries (name, category_id, source, target, key, cache, resume, sort) \
         SELECT ?, ?, ?, ?, ?, ?, 1, {NEXT_SORT} {BELOW_ENTRY_LIMIT} RETURNING id"
    ))
    .bind(&title)
    .bind(category)
    .bind(link.source())
    .bind(&target)
    .bind(music::state_key(&target))
    .bind(music::DEFAULT_CACHE)
    .bind(MAX_ENTRIES)
    .fetch_optional(&state.db)
    .await;
    let id = match inserted {
        Ok(Some(id)) => id,
        Ok(None) => {
            let error = format!("The library has {MAX_ENTRIES} entries - delete one first.");
            return render_music(&state, StatusCode::BAD_REQUEST, refuse(error)).await;
        }
        // Added by another request since the check above.
        Err(sqlx::Error::Database(err)) if err.is_unique_violation() => {
            let error = "That link is already in the library.".to_string();
            return render_music(&state, StatusCode::BAD_REQUEST, refuse(error)).await;
        }
        Err(err) => return server_error(err, "couldn't add a music entry"),
    };
    // Its first fill comes before anything waiting (design 21b §2.2).
    state.music_sweep.wake();
    log_event(
        &state,
        &admin,
        "music_entry_added",
        &format!("entry {id}: {title} ({target})"),
    )
    .await;
    Redirect::to(&format!("/music#entry-{id}")).into_response()
}

/// `POST /music/own`: a new "own files" entry (name, category), then its page for the uploads.
pub async fn add_own(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<FormMap>,
) -> Response {
    let name_text = field(&form, "name");
    let picked = field(&form, "category");
    let refuse = |error: String| Entered {
        own: Some(OwnForm {
            name: name_text.clone(),
            category: picked.clone(),
            error: Some(error),
        }),
        ..Default::default()
    };
    let Some(name) = music::clean_name(&name_text, MAX_NAME_CHARS) else {
        let error = format!("Give it a name (at most {MAX_NAME_CHARS} characters).");
        return render_music(&state, StatusCode::BAD_REQUEST, refuse(error)).await;
    };
    // The 200-entry limit is the INSERT's own condition below.
    let category = match pick_category(&state.db, &picked, "own").await {
        Ok(Ok(id)) => id,
        Ok(Err(error)) => {
            return render_music(&state, StatusCode::BAD_REQUEST, refuse(error.to_string())).await;
        }
        Err(err) => return server_error(err, "music category lookup failed"),
    };
    // The key is "own-" and 12 random hex digits, unique for good: after a restore the ids come
    // back, and the music app keys positions and downloads by it (qa-21-step1-code #7). The limit
    // is checked in the INSERT itself (#6).
    let inserted: Result<Option<i64>, sqlx::Error> = sqlx::query_scalar(&format!(
        "INSERT INTO music_entries (name, category_id, source, key, cache, resume, sort) \
         SELECT ?, ?, 'own', ?, -1, 0, {NEXT_SORT} {BELOW_ENTRY_LIMIT} RETURNING id"
    ))
    .bind(&name)
    .bind(category)
    .bind(music::new_own_key())
    .bind(MAX_ENTRIES)
    .fetch_optional(&state.db)
    .await;
    let id = match inserted {
        Ok(Some(id)) => id,
        Ok(None) => {
            let error = format!("The library has {MAX_ENTRIES} entries - delete one first.");
            return render_music(&state, StatusCode::BAD_REQUEST, refuse(error)).await;
        }
        Err(err) => return server_error(err, "couldn't add an own-files entry"),
    };
    log_event(
        &state,
        &admin,
        "music_entry_added",
        &format!("entry {id}: {name} (own files)"),
    )
    .await;
    Redirect::to(&format!("/music/entries/{id}#files")).into_response()
}

/// Swaps `sort` with the neighbour above or below (`direction` "up"/"down") among `table`'s rows.
async fn move_row(
    db: &sqlx::SqlitePool,
    table: &str,
    id: i64,
    direction: &str,
) -> Result<bool, sqlx::Error> {
    let rows: Vec<(i64, i64)> =
        sqlx::query_as(&format!("SELECT id, sort FROM {table} ORDER BY sort, id"))
            .fetch_all(db)
            .await?;
    let Some(index) = rows.iter().position(|(row, _)| *row == id) else {
        return Ok(false);
    };
    let other = match direction {
        "up" if index > 0 => index - 1,
        "down" if index + 1 < rows.len() => index + 1,
        _ => return Ok(true),
    };
    // Renumber all rows in order (10, 20, ...) with the two swapped: equal sorts can't stick.
    let mut order: Vec<i64> = rows.iter().map(|(row, _)| *row).collect();
    order.swap(index, other);
    let mut tx = db.begin().await?;
    for (position, row) in order.iter().enumerate() {
        sqlx::query(&format!("UPDATE {table} SET sort = ? WHERE id = ?"))
            .bind((position as i64 + 1) * 10)
            .bind(row)
            .execute(&mut *tx)
            .await?;
    }
    tx.commit().await?;
    Ok(true)
}

/// `POST /music/entries/{id}/move` (`direction` up/down): the carousel order on every phone.
pub async fn move_entry(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<FormMap>,
) -> Response {
    match move_row(&state.db, "music_entries", id, &field(&form, "direction")).await {
        Ok(true) => {}
        Ok(false) => return (StatusCode::NOT_FOUND, "No such entry").into_response(),
        Err(err) => return server_error(err, "couldn't move a music entry"),
    }
    let phones: Vec<i64> =
        sqlx::query_scalar("SELECT DISTINCT device_id FROM device_music_entries")
            .fetch_all(&state.db)
            .await
            .unwrap_or_default();
    nudge(&state, &phones);
    log_event(&state, &admin, "music_entry_moved", &format!("entry {id}")).await;
    Redirect::to(&format!("/music#entry-{id}")).into_response()
}

/// A category as typed: `Err((message, field))`.
fn check_category(name: &str, icon: &str, color: &str) -> Result<String, (String, &'static str)> {
    let Some(name) = music::clean_name(name, MAX_CATEGORY_NAME_CHARS) else {
        return Err((
            format!("Give the category a name (at most {MAX_CATEGORY_NAME_CHARS} characters)."),
            "name",
        ));
    };
    if !music::icon_known(icon) {
        return Err(("Pick one of the icons.".to_string(), "icon"));
    }
    if music::color_hex(color).is_none() {
        return Err(("Pick one of the colours.".to_string(), "color"));
    }
    Ok(name)
}

/// `POST /music/categories`: a new category (name, icon, colour), last in the row.
pub async fn add_category(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<FormMap>,
) -> Response {
    let (name, icon, color) = (
        field(&form, "name"),
        field(&form, "icon"),
        field(&form, "color"),
    );
    let checked = match check_category(&name, &icon, &color) {
        Ok(checked) => checked,
        Err((error, error_field)) => {
            let entered = Entered {
                new_category: Some(NewCategoryForm {
                    name,
                    icon,
                    color,
                    error: Some(error),
                    error_field,
                }),
                ..Default::default()
            };
            return render_music(&state, StatusCode::BAD_REQUEST, entered).await;
        }
    };
    let inserted: Result<i64, sqlx::Error> = sqlx::query_scalar(
        "INSERT INTO music_categories (name, icon, color, sort) \
         VALUES (?, ?, ?, (SELECT COALESCE(MAX(sort), 0) + 10 FROM music_categories)) RETURNING id",
    )
    .bind(&checked)
    .bind(&icon)
    .bind(&color)
    .fetch_one(&state.db)
    .await;
    let id = match inserted {
        Ok(id) => id,
        Err(err) => return server_error(err, "couldn't add a music category"),
    };
    log_event(
        &state,
        &admin,
        "music_category_added",
        &format!("category {id}: {checked}"),
    )
    .await;
    Redirect::to(&format!("/music#category-{id}")).into_response()
}

/// `POST /music/categories/{id}`: renames it and sets its icon and colour.
pub async fn save_category(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<FormMap>,
) -> Response {
    let (name, icon, color) = (
        field(&form, "name"),
        field(&form, "icon"),
        field(&form, "color"),
    );
    let checked = match check_category(&name, &icon, &color) {
        Ok(checked) => checked,
        Err((error, error_field)) => {
            let entered = Entered {
                category: Some((id, name, icon, color, error, error_field)),
                ..Default::default()
            };
            return render_music(&state, StatusCode::BAD_REQUEST, entered).await;
        }
    };
    let updated =
        sqlx::query("UPDATE music_categories SET name = ?, icon = ?, color = ? WHERE id = ?")
            .bind(&checked)
            .bind(&icon)
            .bind(&color)
            .bind(id)
            .execute(&state.db)
            .await;
    match updated {
        Ok(result) if result.rows_affected() == 0 => {
            return (StatusCode::NOT_FOUND, "No such category").into_response();
        }
        Ok(_) => {}
        Err(err) => return server_error(err, "couldn't save a music category"),
    }
    nudge(
        &state,
        &music::phones_with_category(&state.db, id)
            .await
            .unwrap_or_default(),
    );
    log_event(
        &state,
        &admin,
        "music_category_changed",
        &format!("category {id}: {checked}"),
    )
    .await;
    Redirect::to(&format!("/music#category-{id}")).into_response()
}

/// `POST /music/categories/{id}/move` (`direction` up/down): the tiles' order on the phones.
pub async fn move_category(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<FormMap>,
) -> Response {
    match move_row(
        &state.db,
        "music_categories",
        id,
        &field(&form, "direction"),
    )
    .await
    {
        Ok(true) => {}
        Ok(false) => return (StatusCode::NOT_FOUND, "No such category").into_response(),
        Err(err) => return server_error(err, "couldn't move a music category"),
    }
    let phones: Vec<i64> =
        sqlx::query_scalar("SELECT DISTINCT device_id FROM device_music_entries")
            .fetch_all(&state.db)
            .await
            .unwrap_or_default();
    nudge(&state, &phones);
    log_event(
        &state,
        &admin,
        "music_category_moved",
        &format!("category {id}"),
    )
    .await;
    Redirect::to(&format!("/music#category-{id}")).into_response()
}

/// `POST /music/categories/{id}/delete`: only while no entry uses it (else 400, the card says so).
pub async fn delete_category(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let category: Option<MusicCategory> =
        match sqlx::query_as("SELECT * FROM music_categories WHERE id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await
        {
            Ok(category) => category,
            Err(err) => return server_error(err, "music category lookup failed"),
        };
    let Some(category) = category else {
        return (StatusCode::NOT_FOUND, "No such category").into_response();
    };
    let used: i64 =
        match sqlx::query_scalar("SELECT COUNT(*) FROM music_entries WHERE category_id = ?")
            .bind(id)
            .fetch_one(&state.db)
            .await
        {
            Ok(used) => used,
            Err(err) => return server_error(err, "music category use lookup failed"),
        };
    if used > 0 {
        let entered = Entered {
            category: Some((
                id,
                category.name.clone(),
                category.icon.clone(),
                category.color.clone(),
                format!(
                    "Not deleted: {used} entr{} use{} this category - move {} to another one first.",
                    if used == 1 { "y" } else { "ies" },
                    if used == 1 { "s" } else { "" },
                    if used == 1 { "it" } else { "them" }
                ),
                "delete",
            )),
            ..Default::default()
        };
        return render_music(&state, StatusCode::BAD_REQUEST, entered).await;
    }
    if let Err(err) = sqlx::query("DELETE FROM music_categories WHERE id = ?")
        .bind(id)
        .execute(&state.db)
        .await
    {
        return server_error(err, "couldn't delete a music category");
    }
    log_event(
        &state,
        &admin,
        "music_category_deleted",
        &format!("category {id}: {}", category.name),
    )
    .await;
    Redirect::to("/music#categories").into_response()
}

// ------------------------------------------------------------------------------------------------
// Storytel
// ------------------------------------------------------------------------------------------------

async fn storytel_phones(db: &sqlx::SqlitePool) -> Vec<i64> {
    sqlx::query_scalar("SELECT device_id FROM device_policy WHERE music_storytel = 1")
        .fetch_all(db)
        .await
        .unwrap_or_default()
}

/// `POST /music/storytel` (`email`, `password`): seals the family's login with the server's key and
/// bumps the generation. Never shown back, never logged; the fields are never prefilled.
pub async fn save_storytel(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<FormMap>,
) -> Response {
    let refuse = |error: &str| Entered {
        storytel_error: Some(error.to_string()),
        ..Default::default()
    };
    let Some(key) = state.music_key.clone() else {
        let entered = refuse("Storytel is off: this server has no key to keep the login safe.");
        return render_music(&state, StatusCode::SERVICE_UNAVAILABLE, entered).await;
    };
    let email = field(&form, "email").trim().to_string();
    let password = field(&form, "password");
    let email_ok = (3..=200).contains(&email.chars().count())
        && email.contains('@')
        && !email.chars().any(|c| c.is_control() || c.is_whitespace());
    if !email_ok {
        let entered = refuse("Enter the Storytel account's e-mail address.");
        return render_music(&state, StatusCode::BAD_REQUEST, entered).await;
    }
    if password.is_empty()
        || password.chars().count() > 200
        || password.chars().any(char::is_control)
    {
        let entered = refuse("Enter the Storytel password.");
        return render_music(&state, StatusCode::BAD_REQUEST, entered).await;
    }
    let plain = serde_json::json!({"email": email, "password": password}).to_string();
    let (ciphertext, nonce) = key.seal(plain.as_bytes());
    let saved = sqlx::query(
        "UPDATE music_storytel SET ciphertext = ?, nonce = ?, key_fingerprint = ?, \
         saved_at = datetime('now'), generation = generation + 1 WHERE id = 1",
    )
    .bind(&ciphertext)
    .bind(&nonce)
    .bind(&key.fingerprint)
    .execute(&state.db)
    .await;
    if let Err(err) = saved {
        return server_error(err, "couldn't save the Storytel login");
    }
    nudge(&state, &storytel_phones(&state.db).await);
    log_event(
        &state,
        &admin,
        "storytel_login_saved",
        "Storytel login saved",
    )
    .await;
    Redirect::to("/music#storytel").into_response()
}

/// `POST /music/storytel/clear`: forgets the login; the phones delete theirs (generation 0).
pub async fn clear_storytel(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let cleared = sqlx::query(
        "UPDATE music_storytel SET ciphertext = NULL, nonce = NULL, key_fingerprint = NULL, \
         saved_at = NULL, generation = generation + 1 WHERE id = 1",
    )
    .execute(&state.db)
    .await;
    if let Err(err) = cleared {
        return server_error(err, "couldn't clear the Storytel login");
    }
    nudge(&state, &storytel_phones(&state.db).await);
    log_event(
        &state,
        &admin,
        "storytel_login_cleared",
        "Storytel login removed",
    )
    .await;
    Redirect::to("/music#storytel").into_response()
}

// ------------------------------------------------------------------------------------------------
// /music/entries/{id}
// ------------------------------------------------------------------------------------------------

pub struct EntryForm {
    pub name: String,
    pub category: i64,
    pub order: String,
    pub cache: i64,
    pub resume: bool,
    pub error: Option<String>,
    pub error_field: &'static str,
}

pub struct FileRow {
    pub id: i64,
    pub title: String,
    pub detail: String,
    pub missing: bool,
    pub art: Option<String>,
}

#[derive(Template)]
#[template(path = "music_entry.html")]
struct EntryTemplate {
    title: String,
    entry: MusicEntry,
    kind: &'static str,
    own: bool,
    form: EntryForm,
    categories: Vec<(i64, String)>,
    orders: Vec<(&'static str, &'static str)>,
    /// The offline choices, plus the entry's own value when it is none of them (an import keeps
    /// the Vibb box's, design 21a).
    caches: Vec<(i64, String)>,
    files: Vec<FileRow>,
    file_count: i64,
    max_files: i64,
    phones: Vec<(i64, String)>,
    /// What went wrong with an upload (each file on its own line), and how many were saved.
    upload_problems: Vec<String>,
    upload_saved: usize,
    cover_error: Option<String>,
    /// The sweep's status, Check now and the per-phone lines (NRK and RSS, design 21b §3).
    sweep: Option<SweepCard>,
    phone_lines: Vec<String>,
    /// The cover the sweep fetched from the source (shown while the parent has none).
    source_cover: Option<String>,
}

/// What a refused form on an entry's page brings back.
#[derive(Default)]
struct EntryEntered {
    form: Option<EntryForm>,
    upload_problems: Vec<String>,
    upload_saved: usize,
    cover_error: Option<String>,
}

async fn load_entry(db: &sqlx::SqlitePool, id: i64) -> Result<Option<MusicEntry>, sqlx::Error> {
    sqlx::query_as(
        "SELECT id, name, category_id, source, target, key, play_order, cache, resume, \
         cover_hash, sort FROM music_entries WHERE id = ?",
    )
    .bind(id)
    .fetch_optional(db)
    .await
}

fn duration_text(ms: Option<i64>) -> Option<String> {
    let seconds = ms? / 1000;
    Some(if seconds >= 3600 {
        format!(
            "{}:{:02}:{:02}",
            seconds / 3600,
            (seconds / 60) % 60,
            seconds % 60
        )
    } else {
        format!("{}:{:02}", seconds / 60, seconds % 60)
    })
}

pub async fn show_entry(State(state): State<AppState>, Path(id): Path<i64>) -> Response {
    render_entry(&state, id, StatusCode::OK, EntryEntered::default()).await
}

async fn render_entry(
    state: &AppState,
    id: i64,
    status: StatusCode,
    entered: EntryEntered,
) -> Response {
    let entry = match load_entry(&state.db, id).await {
        Ok(Some(entry)) => entry,
        Ok(None) => return (StatusCode::NOT_FOUND, "No such entry").into_response(),
        Err(err) => return server_error(err, "music entry lookup failed"),
    };
    let page: Result<EntryTemplate, sqlx::Error> = async {
        let categories: Vec<(i64, String)> =
            sqlx::query_as("SELECT id, name FROM music_categories ORDER BY sort, id")
                .fetch_all(&state.db)
                .await?;
        let files: Vec<MusicFile> = sqlx::query_as(
            "SELECT id, entry_id, original_name, size, sha256, title, artist, album, \
             track_no, duration_ms, art_hash, sort, missing FROM music_files \
             WHERE entry_id = ? ORDER BY sort, id",
        )
        .bind(id)
        .fetch_all(&state.db)
        .await?;
        let phones: Vec<(i64, String)> = sqlx::query_as(
            "SELECT d.id, d.name FROM devices d JOIN device_music_entries m ON m.device_id = d.id \
             WHERE m.entry_id = ? ORDER BY d.name",
        )
        .bind(id)
        .fetch_all(&state.db)
        .await?;
        let view = SweepView::load(state, state.music_sweep.now()).await?;
        let form = entered.form.unwrap_or_else(|| EntryForm {
            name: entry.name.clone(),
            category: entry.category_id,
            order: entry.play_order.clone(),
            cache: entry.cache,
            resume: entry.resume,
            error: None,
            error_field: "",
        });
        let file_rows = files
            .iter()
            .map(|f| {
                let detail = [
                    f.track_no.map(|t| format!("track {t}")),
                    f.artist.clone(),
                    f.album.clone(),
                    duration_text(f.duration_ms),
                    Some(music::megabytes(f.size)),
                    Some(f.original_name.clone()),
                ]
                .into_iter()
                .flatten()
                .collect::<Vec<_>>()
                .join(" · ");
                FileRow {
                    id: f.id,
                    title: f.display_title(),
                    detail,
                    missing: f.missing,
                    art: f.art_hash.clone(),
                }
            })
            .collect();
        Ok(EntryTemplate {
            title: entry.name.clone(),
            kind: music::kind_label(&entry.source, entry.target.as_deref()),
            own: entry.source == "own",
            form,
            categories,
            orders: ORDERS.to_vec(),
            caches: {
                let mut caches: Vec<(i64, String)> = CACHE_CHOICES
                    .iter()
                    .map(|(value, label)| (*value, label.to_string()))
                    .collect();
                if !music::valid_cache(entry.cache) {
                    caches.push((entry.cache, format!("{} (from Vibb)", entry.cache)));
                }
                caches
            },
            file_count: files.len() as i64,
            files: file_rows,
            max_files: MAX_FILES_PER_ENTRY,
            phones,
            upload_problems: entered.upload_problems,
            upload_saved: entered.upload_saved,
            cover_error: entered.cover_error,
            sweep: view.card(&entry),
            phone_lines: view.phone_lines(&entry),
            source_cover: view.source_cover(entry.id),
            entry,
        })
    }
    .await;
    match page {
        Ok(page) => (status, Html(page.render().unwrap())).into_response(),
        Err(err) => server_error(err, "couldn't load a music entry's page"),
    }
}

/// `POST /music/entries/{id}`: name, category, play order and offline depth (not for own files:
/// always in order, always all) and resume.
pub async fn save_entry(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<FormMap>,
) -> Response {
    let entry = match load_entry(&state.db, id).await {
        Ok(Some(entry)) => entry,
        Ok(None) => return (StatusCode::NOT_FOUND, "No such entry").into_response(),
        Err(err) => return server_error(err, "music entry lookup failed"),
    };
    let own = entry.source == "own";
    let name_text = field(&form, "name");
    let category = field(&form, "category").trim().parse::<i64>().unwrap_or(-1);
    let order = if own {
        entry.play_order.clone()
    } else {
        field(&form, "order")
    };
    let cache = if own {
        -1
    } else {
        field(&form, "cache")
            .trim()
            .parse::<i64>()
            .unwrap_or(i64::MIN)
    };
    let resume = form.get("resume").is_some_and(|v| v == "on");
    let refuse = |error: String, error_field: &'static str| EntryEntered {
        form: Some(EntryForm {
            name: name_text.clone(),
            category,
            order: order.clone(),
            cache,
            resume,
            error: Some(error),
            error_field,
        }),
        ..Default::default()
    };
    let Some(name) = music::clean_name(&name_text, MAX_NAME_CHARS) else {
        let entered = refuse(
            format!("Give it a name (at most {MAX_NAME_CHARS} characters)."),
            "name",
        );
        return render_entry(&state, id, StatusCode::BAD_REQUEST, entered).await;
    };
    let category_exists: bool =
        match sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM music_categories WHERE id = ?)")
            .bind(category)
            .fetch_one(&state.db)
            .await
        {
            Ok(exists) => exists,
            Err(err) => return server_error(err, "music category lookup failed"),
        };
    if !category_exists {
        let entered = refuse("Pick one of the categories.".to_string(), "category");
        return render_entry(&state, id, StatusCode::BAD_REQUEST, entered).await;
    }
    if !music::valid_order(&order) {
        let entered = refuse("Pick one of the orders.".to_string(), "order");
        return render_entry(&state, id, StatusCode::BAD_REQUEST, entered).await;
    }
    // One of the choices, or the value it already has (an import keeps the Vibb box's, 21a).
    if !own && !music::valid_cache(cache) && cache != entry.cache {
        let entered = refuse("Pick one of the offline choices.".to_string(), "cache");
        return render_entry(&state, id, StatusCode::BAD_REQUEST, entered).await;
    }
    let updated = sqlx::query(
        "UPDATE music_entries SET name = ?, category_id = ?, play_order = ?, cache = ?, \
         resume = ? WHERE id = ?",
    )
    .bind(&name)
    .bind(category)
    .bind(&order)
    .bind(cache)
    .bind(resume)
    .bind(id)
    .execute(&state.db)
    .await;
    if let Err(err) = updated {
        return server_error(err, "couldn't save a music entry");
    }
    if order != entry.play_order {
        // An NRK series' window follows its order: the refill shouldn't wait (QA 1c #10).
        state.music_sweep.wake();
    }
    nudge(
        &state,
        &music::phones_with_entry(&state.db, id)
            .await
            .unwrap_or_default(),
    );
    log_event(
        &state,
        &admin,
        "music_entry_changed",
        &format!("entry {id}: {name}, {order}, cache {cache}, resume {resume}"),
    )
    .await;
    Redirect::to(&format!("/music/entries/{id}#entry-{id}")).into_response()
}

/// `POST /music/entries/{id}/delete`: the entry, its ticks, its own files (rows and disk) and
/// covers nothing else uses.
pub async fn delete_entry(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let entry = match load_entry(&state.db, id).await {
        Ok(Some(entry)) => entry,
        Ok(None) => return (StatusCode::NOT_FOUND, "No such entry").into_response(),
        Err(err) => return server_error(err, "music entry lookup failed"),
    };
    let phones = match music::phones_with_entry(&state.db, id).await {
        Ok(phones) => phones,
        Err(err) => return server_error(err, "music entry lookup failed"),
    };
    if let Err(err) = sqlx::query("DELETE FROM music_entries WHERE id = ?")
        .bind(id)
        .execute(&state.db)
        .await
    {
        return server_error(err, "couldn't delete a music entry");
    }
    tokio::fs::remove_dir_all(state.music_files_dir.join(id.to_string()))
        .await
        .ok();
    MUSIC_COVERS.prune(&state.db, &state.music_cover_dir).await;
    nudge(&state, &phones);
    log_event(
        &state,
        &admin,
        "music_entry_deleted",
        &format!("entry {id}: {}", entry.name),
    )
    .await;
    Redirect::to("/music#entries").into_response()
}

/// `POST /music/entries/{id}/cover` (multipart `cover`): the parent's cover, square, at most 512 px,
/// no metadata (`photos::process`), in the music cover store.
pub async fn upload_cover(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    mut multipart: Multipart,
) -> Response {
    match load_entry(&state.db, id).await {
        Ok(Some(_)) => {}
        Ok(None) => return (StatusCode::NOT_FOUND, "No such entry").into_response(),
        Err(err) => return server_error(err, "music entry lookup failed"),
    }
    let refuse = |message: &str| EntryEntered {
        cover_error: Some(message.to_string()),
        ..Default::default()
    };
    let mut image = None;
    loop {
        match multipart.next_field().await {
            Ok(Some(field)) if field.name() == Some("cover") => match field.bytes().await {
                Ok(bytes) => image = Some(bytes),
                Err(_) => {
                    let entered = refuse(photos::PhotoError::TooLarge.message());
                    return render_entry(&state, id, StatusCode::BAD_REQUEST, entered).await;
                }
            },
            Ok(Some(_)) => {}
            Ok(None) => break,
            Err(_) => {
                let entered = refuse(photos::PhotoError::TooLarge.message());
                return render_entry(&state, id, StatusCode::BAD_REQUEST, entered).await;
            }
        }
    }
    let Some(bytes) = image else {
        let entered = refuse(photos::PhotoError::Empty.message());
        return render_entry(&state, id, StatusCode::BAD_REQUEST, entered).await;
    };
    let processed = match photos::process_limited(bytes, Shape::Square).await {
        Ok(processed) => processed,
        Err(err) => {
            return render_entry(&state, id, StatusCode::BAD_REQUEST, refuse(err.message())).await;
        }
    };
    let result = {
        let _files = MUSIC_COVERS.lock().await;
        if let Err(err) = photos::store(&state.music_cover_dir, &processed).await {
            return server_error(err, "couldn't store a music cover");
        }
        sqlx::query("UPDATE music_entries SET cover_hash = ? WHERE id = ?")
            .bind(&processed.hash)
            .bind(id)
            .execute(&state.db)
            .await
    };
    MUSIC_COVERS.prune(&state.db, &state.music_cover_dir).await;
    if let Err(err) = result {
        return server_error(err, "couldn't save a music cover");
    }
    nudge(
        &state,
        &music::phones_with_entry(&state.db, id)
            .await
            .unwrap_or_default(),
    );
    log_event(
        &state,
        &admin,
        "music_cover_changed",
        &format!("entry {id}"),
    )
    .await;
    Redirect::to(&format!("/music/entries/{id}#cover")).into_response()
}

/// `POST /music/entries/{id}/cover/remove`: back to the source's art (or an own file's).
pub async fn remove_cover(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let updated = sqlx::query("UPDATE music_entries SET cover_hash = NULL WHERE id = ?")
        .bind(id)
        .execute(&state.db)
        .await;
    match updated {
        Ok(result) if result.rows_affected() == 0 => {
            return (StatusCode::NOT_FOUND, "No such entry").into_response();
        }
        Ok(_) => {}
        Err(err) => return server_error(err, "couldn't remove a music cover"),
    }
    MUSIC_COVERS.prune(&state.db, &state.music_cover_dir).await;
    nudge(
        &state,
        &music::phones_with_entry(&state.db, id)
            .await
            .unwrap_or_default(),
    );
    log_event(
        &state,
        &admin,
        "music_cover_changed",
        &format!("entry {id}: removed"),
    )
    .await;
    Redirect::to(&format!("/music/entries/{id}#cover")).into_response()
}

/// A temp file deleted on drop unless kept (an error, a cut-off upload, a dropped request).
struct TempFile {
    path: PathBuf,
    keep: bool,
}

impl Drop for TempFile {
    fn drop(&mut self) {
        if !self.keep {
            let _ = std::fs::remove_file(&self.path);
        }
    }
}

enum ReceiveError {
    /// The upload stopped half-way (the rest of the request is unusable).
    CutOff,
    TooBig,
    Empty,
    /// The data disk would get below [MIN_FREE_BYTES] (qa-21-step1-code #5).
    NoSpace,
    Disk(String),
}

/// How often (bytes written) an upload looks at the free space again.
const DISK_CHECK_BYTES: u64 = 64 * 1024 * 1024;

/// Stops an upload before it takes the data disk below `floor` (qa-21-step1-code #5): the free
/// space is looked at before the first byte and again every [DISK_CHECK_BYTES], so two uploads at
/// once or a file bigger than what's left stop too - the SQLite database shares that disk.
struct DiskGuard<'a> {
    dir: &'a FsPath,
    floor: u64,
    next_check: u64,
}

impl<'a> DiskGuard<'a> {
    fn new(dir: &'a FsPath, floor: u64) -> Self {
        DiskGuard {
            dir,
            floor,
            next_check: 0,
        }
    }

    /// `written` = bytes of this file written so far.
    fn check(&mut self, written: u64) -> Result<(), ReceiveError> {
        if written < self.next_check {
            return Ok(());
        }
        self.next_check = written + DISK_CHECK_BYTES;
        match music::free_bytes(self.dir) {
            Some(free) if free >= self.floor => Ok(()),
            _ => Err(ReceiveError::NoSpace),
        }
    }
}

/// Streams one multipart file to `<dir>/<random>.part`, hashing it as it goes; nothing is held in
/// memory beyond one chunk. Stops (the temp file goes) when the disk gets below `floor`.
async fn receive(
    file_field: &mut Field<'_>,
    dir: &FsPath,
    floor: u64,
) -> Result<(TempFile, u64, String), ReceiveError> {
    let mut guard = DiskGuard::new(dir, floor);
    guard.check(0)?;
    let temp = TempFile {
        path: dir.join(format!("{}.part", random_name())),
        keep: false,
    };
    let mut out = tokio::fs::File::create(&temp.path)
        .await
        .map_err(|e| ReceiveError::Disk(e.to_string()))?;
    let mut digest = Sha256::new();
    let mut size: u64 = 0;
    while let Some(chunk) = file_field.chunk().await.map_err(|_| ReceiveError::CutOff)? {
        size += chunk.len() as u64;
        if size > MAX_FILE_BYTES {
            return Err(ReceiveError::TooBig);
        }
        guard.check(size)?;
        digest.update(&chunk);
        out.write_all(&chunk)
            .await
            .map_err(|e| ReceiveError::Disk(e.to_string()))?;
    }
    if size == 0 {
        return Err(ReceiveError::Empty);
    }
    out.flush()
        .await
        .and(out.sync_all().await)
        .map_err(|e| ReceiveError::Disk(e.to_string()))?;
    Ok((temp, size, hex::encode(digest.finalize())))
}

/// What became of one received own file.
#[derive(Debug, PartialEq, Eq)]
enum Kept {
    Added(i64),
    /// A missing file of the entry with the same content, back in place (its id and path kept).
    Restored(i64),
    /// The entry already has this file.
    Duplicate,
    /// The entry holds [MAX_FILES_PER_ENTRY] files.
    Full,
    Failed,
}

/// Keeps one received own file of entry `entry_id` (`temp` read by lofty as `tags`):
/// - a **missing** file of the entry with the same SHA-256 is restored in place - same id and
///   path, so the phones' copies and handover marks stay valid (qa-21-step1-code #2);
/// - the same content already present is refused;
/// - anything else is added, unless the entry is full (checked in the INSERT itself, #6).
///
/// The file is renamed into place before its row is written; a failed row write removes it again
/// (so does an entry deleted meanwhile: the foreign key refuses the row, or the rename finds no
/// directory).
#[allow(clippy::too_many_arguments)]
async fn keep_upload(
    state: &AppState,
    entry_id: i64,
    mut temp: TempFile,
    size: u64,
    sha256: &str,
    original: &str,
    tags: &music::AudioTags,
) -> Kept {
    // (id, path, missing, art_hash) of a file of this entry with the same content.
    type Same = (i64, String, bool, Option<String>);
    let existing: Result<Option<Same>, sqlx::Error> = sqlx::query_as(
        "SELECT id, path, missing, art_hash FROM music_files \
             WHERE entry_id = ? AND sha256 = ? ORDER BY missing DESC, id LIMIT 1",
    )
    .bind(entry_id)
    .bind(sha256)
    .fetch_optional(&state.db)
    .await;
    let existing = match existing {
        Ok(existing) => existing,
        Err(err) => {
            tracing::error!(%err, "music file lookup failed");
            return Kept::Failed;
        }
    };
    let art = async {
        match tags.picture.clone() {
            Some(picture) => photos::process_limited(picture.into(), Shape::Square)
                .await
                .ok(),
            None => None,
        }
    };
    match existing {
        Some((_, _, false, _)) => Kept::Duplicate,
        Some((file_id, path, true, art_hash)) => {
            let Some(final_path) = music::file_path(&state.music_files_dir, &path) else {
                return Kept::Failed;
            };
            if let Some(parent) = final_path.parent() {
                tokio::fs::create_dir_all(parent).await.ok();
            }
            if let Err(err) = tokio::fs::rename(&temp.path, &final_path).await {
                tracing::error!(%err, "couldn't put a restored music file back");
                return Kept::Failed;
            }
            temp.keep = true;
            // Its embedded art too, when the cover was lost with the rest.
            let art = if art_hash.is_none() { art.await } else { None };
            let _covers = MUSIC_COVERS.lock().await;
            let new_art = match &art {
                Some(processed) => photos::store(&state.music_cover_dir, processed)
                    .await
                    .ok()
                    .map(|()| processed.hash.clone()),
                None => None,
            };
            let updated = sqlx::query(
                "UPDATE music_files SET missing = 0, art_hash = COALESCE(art_hash, ?) WHERE id = ?",
            )
            .bind(&new_art)
            .bind(file_id)
            .execute(&state.db)
            .await;
            match updated {
                Ok(result) if result.rows_affected() == 1 => Kept::Restored(file_id),
                Ok(_) => {
                    tokio::fs::remove_file(&final_path).await.ok();
                    Kept::Failed
                }
                Err(err) => {
                    tracing::error!(%err, "couldn't restore a music file's row");
                    tokio::fs::remove_file(&final_path).await.ok();
                    Kept::Failed
                }
            }
        }
        None => {
            let stored_name = format!("{}.{}", random_name(), tags.ext);
            let dir = state.music_files_dir.join(entry_id.to_string());
            let final_path = dir.join(&stored_name);
            if let Err(err) = tokio::fs::rename(&temp.path, &final_path).await {
                tracing::error!(%err, "couldn't move an uploaded music file into place");
                return Kept::Failed;
            }
            temp.keep = true;
            let art = art.await;
            let inserted: Result<Option<i64>, sqlx::Error> = {
                let _covers = MUSIC_COVERS.lock().await;
                let art_hash = match &art {
                    Some(processed) => photos::store(&state.music_cover_dir, processed)
                        .await
                        .ok()
                        .map(|()| processed.hash.clone()),
                    None => None,
                };
                sqlx::query_scalar(
                    "INSERT INTO music_files (entry_id, path, original_name, size, sha256, title, \
                     artist, album, track_no, duration_ms, art_hash, sort) \
                     SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0 \
                     WHERE (SELECT COUNT(*) FROM music_files WHERE entry_id = ?) < ? RETURNING id",
                )
                .bind(entry_id)
                .bind(format!("{entry_id}/{stored_name}"))
                .bind(original)
                .bind(i64::try_from(size).unwrap_or(i64::MAX))
                .bind(sha256)
                .bind(&tags.title)
                .bind(&tags.artist)
                .bind(&tags.album)
                .bind(tags.track_no)
                .bind(tags.duration_ms)
                .bind(&art_hash)
                .bind(entry_id)
                .bind(MAX_FILES_PER_ENTRY)
                .fetch_optional(&state.db)
                .await
            };
            match inserted {
                Ok(Some(file_id)) => Kept::Added(file_id),
                Ok(None) => {
                    tokio::fs::remove_file(&final_path).await.ok();
                    Kept::Full
                }
                Err(err) => {
                    tracing::error!(%err, "couldn't store an own music file");
                    tokio::fs::remove_file(&final_path).await.ok();
                    Kept::Failed
                }
            }
        }
    }
}

/// The browser's file name, reduced to its last component and cut to 200 characters.
fn original_name(name: Option<&str>) -> String {
    let name = name.unwrap_or("");
    let last = name.rsplit(['/', '\\']).next().unwrap_or(name);
    last.chars()
        .filter(|c| !c.is_control())
        .take(200)
        .collect::<String>()
        .trim()
        .to_string()
}

/// Removes own files (rows, disk) - after an upload pushed a phone's library over the limit.
async fn remove_files(state: &AppState, ids: &[i64]) {
    for id in ids {
        let path: Option<String> = sqlx::query_scalar("SELECT path FROM music_files WHERE id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await
            .ok()
            .flatten();
        sqlx::query("DELETE FROM music_files WHERE id = ?")
            .bind(id)
            .execute(&state.db)
            .await
            .ok();
        if let Some(path) = path.and_then(|p| music::file_path(&state.music_files_dir, &p)) {
            tokio::fs::remove_file(path).await.ok();
        }
    }
}

/// `POST /music/entries/{id}/files` (multipart, repeated `file`): own files, each streamed to disk
/// (`.part`, hashed on the way, at most 1 GB, refused below 1 GB free), read with lofty by content
/// (mp3/m4a/m4b/ogg/opus/flac/wav, anything else deleted and listed), its tags and embedded art
/// kept. A file that would make a phone's library too big (QA #4) is taken back. Back to `#files`;
/// with problems, the page (400) lists them next to how many were saved.
pub async fn upload_files(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    mut multipart: Multipart,
) -> Response {
    let entry = match load_entry(&state.db, id).await {
        Ok(Some(entry)) => entry,
        Ok(None) => return (StatusCode::NOT_FOUND, "No such entry").into_response(),
        Err(err) => return server_error(err, "music entry lookup failed"),
    };
    if entry.source != "own" {
        // Its page has no upload form: only a hand-made request gets here.
        return (
            StatusCode::BAD_REQUEST,
            "Only an own-files entry takes uploads.",
        )
            .into_response();
    }
    let dir = state.music_files_dir.join(id.to_string());
    if let Err(err) = tokio::fs::create_dir_all(&dir).await {
        return server_error(err, "couldn't create a music file directory");
    }
    let mut added: Vec<i64> = Vec::new();
    let mut restored: Vec<i64> = Vec::new();
    let mut problems: Vec<String> = Vec::new();
    loop {
        let mut file_field = match multipart.next_field().await {
            Ok(Some(file_field)) => file_field,
            Ok(None) => break,
            Err(_) => {
                problems.push("The upload stopped half-way - try the rest again.".to_string());
                break;
            }
        };
        if file_field.name() != Some("file") {
            continue;
        }
        let original = original_name(file_field.file_name());
        if original.is_empty() {
            continue; // the empty part a browser sends when no file was chosen
        }
        let (temp, size, sha256) = match receive(&mut file_field, &dir, MIN_FREE_BYTES).await {
            Ok(received) => received,
            Err(ReceiveError::CutOff) => {
                problems.push(format!("{original}: the upload stopped half-way."));
                break;
            }
            Err(ReceiveError::TooBig) => {
                problems.push(format!(
                    "{original}: not saved - over {} GB.",
                    MAX_FILE_BYTES / 1_000_000_000
                ));
                continue;
            }
            Err(ReceiveError::Empty) => {
                problems.push(format!("{original}: the file is empty."));
                continue;
            }
            Err(ReceiveError::NoSpace) => {
                problems.push(format!(
                    "{original}: not saved - the server would have less than {} GB free.",
                    MIN_FREE_BYTES / 1_000_000_000
                ));
                break;
            }
            Err(ReceiveError::Disk(err)) => {
                tracing::error!(%err, "couldn't write an uploaded music file");
                problems.push(format!(
                    "{original}: couldn't be written to the server's disk."
                ));
                break;
            }
        };
        let tags = {
            let path = temp.path.clone();
            let name = original.clone();
            tokio::task::spawn_blocking(move || music::read_audio(&path, &name))
                .await
                .ok()
                .flatten()
        };
        let Some(tags) = tags else {
            problems.push(format!(
                "{original}: not an audio file the music app can play (mp3, m4a, m4b, ogg, opus, \
                 flac or wav) - not saved."
            ));
            continue;
        };
        match keep_upload(&state, id, temp, size, &sha256, &original, &tags).await {
            Kept::Added(file_id) => added.push(file_id),
            Kept::Restored(file_id) => restored.push(file_id),
            Kept::Duplicate => problems.push(format!(
                "{original}: already in this entry - not saved again."
            )),
            Kept::Full => problems.push(format!(
                "{original}: not saved - an entry holds at most {MAX_FILES_PER_ENTRY} files."
            )),
            Kept::Failed => problems.push(format!("{original}: couldn't be saved on the server.")),
        }
    }
    if let Err(err) = music::resort_files(&state.db, id).await {
        tracing::error!(%err, "couldn't sort an entry's files");
    }
    // QA #4: no phone's library may pass the limit - the new files go again if one would.
    let phones = music::phones_with_entry(&state.db, id)
        .await
        .unwrap_or_default();
    if !added.is_empty() {
        for phone in &phones {
            let too_big =
                match music::cached_library(&state.db, &state.music_libraries, *phone).await {
                    Ok(library) => library.is_some_and(|l| l.json.len() > MAX_LIBRARY_BYTES),
                    Err(err) => {
                        tracing::error!(%err, "couldn't check a phone's library size");
                        true
                    }
                };
            if too_big {
                remove_files(&state, &added).await;
                music::resort_files(&state.db, id).await.ok();
                problems.push(format!(
                    "Not saved: with these {} file(s) a phone's music library would be bigger than \
                     {} MB. Take the entry off a phone, or split it.",
                    added.len(),
                    MAX_LIBRARY_BYTES / 1_000_000
                ));
                added.clear();
                break;
            }
        }
    }
    MUSIC_COVERS.prune(&state.db, &state.music_cover_dir).await;
    if !added.is_empty() || !restored.is_empty() {
        nudge(&state, &phones);
        log_event(
            &state,
            &admin,
            "music_files_added",
            &format!(
                "entry {id}: {} file(s), {} restored",
                added.len(),
                restored.len()
            ),
        )
        .await;
    }
    if problems.is_empty() {
        return Redirect::to(&format!("/music/entries/{id}#files")).into_response();
    }
    let entered = EntryEntered {
        upload_problems: problems,
        upload_saved: added.len() + restored.len(),
        ..Default::default()
    };
    render_entry(&state, id, StatusCode::BAD_REQUEST, entered).await
}

/// `POST /music/entries/{id}/files/{file_id}/delete`.
pub async fn delete_file(
    State(state): State<AppState>,
    Path((id, file_id)): Path<(i64, i64)>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let row: Option<(String, String)> = match sqlx::query_as(
        "SELECT path, original_name FROM music_files WHERE id = ? AND entry_id = ?",
    )
    .bind(file_id)
    .bind(id)
    .fetch_optional(&state.db)
    .await
    {
        Ok(row) => row,
        Err(err) => return server_error(err, "music file lookup failed"),
    };
    let Some((path, name)) = row else {
        return (StatusCode::NOT_FOUND, "No such file").into_response();
    };
    // Back to where the parent was (qa-21-step1-code #8): the next file in the list, else the one
    // before, else the card.
    let neighbours: Vec<i64> =
        sqlx::query_scalar("SELECT id FROM music_files WHERE entry_id = ? ORDER BY sort, id")
            .bind(id)
            .fetch_all(&state.db)
            .await
            .unwrap_or_default();
    let anchor = neighbours
        .iter()
        .position(|f| *f == file_id)
        .and_then(|at| {
            neighbours
                .get(at + 1)
                .or_else(|| at.checked_sub(1).and_then(|b| neighbours.get(b)))
        })
        .map_or_else(|| "files".to_string(), |next| format!("file-{next}"));
    if let Err(err) = sqlx::query("DELETE FROM music_files WHERE id = ?")
        .bind(file_id)
        .execute(&state.db)
        .await
    {
        return server_error(err, "couldn't delete a music file");
    }
    if let Some(path) = music::file_path(&state.music_files_dir, &path) {
        tokio::fs::remove_file(path).await.ok();
    }
    music::resort_files(&state.db, id).await.ok();
    MUSIC_COVERS.prune(&state.db, &state.music_cover_dir).await;
    nudge(
        &state,
        &music::phones_with_entry(&state.db, id)
            .await
            .unwrap_or_default(),
    );
    log_event(
        &state,
        &admin,
        "music_file_deleted",
        &format!("entry {id}: {name}"),
    )
    .await;
    Redirect::to(&format!("/music/entries/{id}#{anchor}")).into_response()
}

/// `GET /music-covers/{hash}` (session): a cover for the parent's pages.
pub async fn view_cover(State(state): State<AppState>, Path(hash): Path<String>) -> Response {
    let Some(path) = photos::path_for(&state.music_cover_dir, &hash) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    match tokio::fs::read(&path).await {
        Ok(bytes) => ([(header::CONTENT_TYPE, "image/jpeg")], bytes).into_response(),
        Err(_) => StatusCode::NOT_FOUND.into_response(),
    }
}

// ------------------------------------------------------------------------------------------------
// The sweep on the cards (design 21b §3)
// ------------------------------------------------------------------------------------------------

/// "14:30 UTC" today, else "9 Oct 14:30 UTC".
fn utc_time(time: DateTime<Utc>, now: DateTime<Utc>) -> String {
    if time.date_naive() == now.date_naive() {
        time.format("%H:%M UTC").to_string()
    } else {
        time.format("%-d %b %H:%M UTC").to_string()
    }
}

/// "9 Oct".
fn day_text(time: DateTime<Utc>) -> String {
    time.format("%-d %b").to_string()
}

/// "just now", "5 min ago", "2 h ago", "3 days ago".
fn ago(time: DateTime<Utc>, now: DateTime<Utc>) -> String {
    let minutes = (now - time).num_minutes().max(0);
    match minutes {
        0 => "just now".to_string(),
        1..=59 => format!("{minutes} min ago"),
        60..=2879 => format!("{} h ago", minutes / 60),
        _ => format!("{} days ago", minutes / 1440),
    }
}

/// What the cards need from the sweep, read with a few aggregate queries (design 21b §3: 200
/// cards on a Pi Zero).
struct SweepView {
    now: DateTime<Utc>,
    setting: i64,
    current: Option<i64>,
    listings: HashMap<i64, crate::music_sweep::Listing>,
    /// Entry -> the newest listed item's title and date.
    newest: HashMap<i64, (Option<String>, Option<String>)>,
    /// Entry -> its schedule and how many due entries come before it.
    schedule: HashMap<i64, (crate::music_sweep::Scheduled, usize)>,
    /// Entry -> the phones with it ticked (id, name), by name.
    phones: HashMap<i64, Vec<(i64, String)>>,
    /// Phone -> its last music report and when it came.
    reports: HashMap<i64, (music::MusicState, Option<DateTime<Utc>>)>,
    /// Check now is blocked for every entry until then (the hourly and daily limits).
    check_block: Option<DateTime<Utc>>,
}

impl SweepView {
    async fn load(state: &AppState, now: DateTime<Utc>) -> Result<SweepView, sqlx::Error> {
        use crate::music_sweep::{self as sweep, parse_stamp};
        // Before the rows: a check that ends while they are read still shows (QA 1c #18).
        let current = state.music_sweep.current();
        let listings: HashMap<i64, sweep::Listing> =
            sqlx::query_as::<_, sweep::Listing>("SELECT * FROM music_listings")
                .fetch_all(&state.db)
                .await?
                .into_iter()
                .map(|l| (l.entry_id, l))
                .collect();
        let newest: HashMap<i64, (Option<String>, Option<String>)> =
            sqlx::query_as::<_, (i64, Option<String>, Option<String>, String)>(
                "SELECT i.entry_id, i.title, i.published_at, i.first_seen_at FROM music_items i \
                 WHERE i.state = 'ok' AND i.seq = (SELECT MAX(j.seq) FROM music_items j \
                   WHERE j.entry_id = i.entry_id AND j.state = 'ok')",
            )
            .fetch_all(&state.db)
            .await?
            .into_iter()
            .map(|(entry, title, published, seen)| (entry, (title, published.or(Some(seen)))))
            .collect();
        let mut ahead = 0;
        let schedule = sweep::schedule(&state.db, now)
            .await?
            .into_iter()
            .map(|s| {
                let position = ahead;
                if s.at <= now {
                    ahead += 1;
                }
                (s.entry_id, (s, position))
            })
            .collect();
        let mut phones: HashMap<i64, Vec<(i64, String)>> = HashMap::new();
        for (entry, device, name) in sqlx::query_as::<_, (i64, i64, String)>(
            "SELECT m.entry_id, d.id, d.name FROM device_music_entries m \
             JOIN devices d ON d.id = m.device_id ORDER BY d.name, d.id",
        )
        .fetch_all(&state.db)
        .await?
        {
            phones.entry(entry).or_default().push((device, name));
        }
        let reports = sqlx::query_as::<_, (i64, Option<String>, Option<String>)>(
            "SELECT d.id, \
               (SELECT s.music_state_json FROM device_status s WHERE s.device_id = d.id \
                ORDER BY s.reported_at DESC, s.id DESC LIMIT 1), \
               (SELECT s.reported_at FROM device_status s WHERE s.device_id = d.id \
                ORDER BY s.reported_at DESC, s.id DESC LIMIT 1) \
             FROM devices d WHERE d.id IN (SELECT device_id FROM device_music_entries)",
        )
        .fetch_all(&state.db)
        .await?
        .into_iter()
        .filter_map(|(device, json, at)| {
            let report = music::parse_music_state(json.as_deref())?;
            Some((device, (report, at.as_deref().and_then(parse_stamp))))
        })
        .collect();
        Ok(SweepView {
            now,
            setting: sweep::sweep_hours(&state.db).await?,
            current,
            listings,
            newest,
            schedule,
            phones,
            reports,
            check_block: sweep::check_now_blocked_until(&state.db, -1, now).await?,
        })
    }

    fn status(&self, entry: &MusicEntry) -> StatusBlock {
        use crate::music_sweep::parse_stamp;
        let now = self.now;
        let block = |text: String, tone: &'static str, busy: bool| StatusBlock {
            entry_id: entry.id,
            text,
            tone,
            busy,
        };
        let listing = self.listings.get(&entry.id);
        // A check under way: its start stamp is newer than its end (a success zeroes `failures`, a
        // failure stamps `error_at`), and recent (a check a crash cut off doesn't last).
        let checking = listing.is_some_and(|l| {
            let started = l.checked_at.as_deref().and_then(parse_stamp);
            let failed = l.error_at.as_deref().and_then(parse_stamp);
            l.failures > 0
                && started.is_some_and(|at| {
                    failed.is_none_or(|f| f < at)
                        && now - at
                            < chrono::TimeDelta::minutes(crate::music_sweep::CHECKING_MINUTES)
                })
        });
        if self.current == Some(entry.id) || checking {
            return block("Checking…".to_string(), "busy", true);
        }
        let scheduled = self.schedule.get(&entry.id);
        let next_try = scheduled.map(|(s, _)| utc_time(s.at.max(now), now));
        let waiting = |what: &str, ahead: usize| {
            if ahead == 0 {
                format!("Waiting for {what}")
            } else {
                format!("Waiting for {what} ({ahead} ahead)")
            }
        };
        let failed = listing.filter(|l| l.failures > 0 && l.error.is_some());
        let listed = listing.and_then(|l| l.version.as_ref());
        if let Some((scheduled, ahead)) = scheduled
            && scheduled.at <= now
            && failed.is_none()
        {
            let what = match scheduled.why {
                crate::music_sweep::Why::New => "the first check",
                _ if listed.is_none() => "the first check",
                _ => "a check",
            };
            return block(waiting(what, *ahead), "busy", true);
        }
        if let Some(listing) = failed {
            let code = listing.error.clone().unwrap_or_default();
            let next = next_try
                .map(|t| format!(" Next try {t}."))
                .unwrap_or_default();
            let text = match (listed, listing.listed_at.as_deref().and_then(parse_stamp)) {
                (Some(_), listed_at) => format!(
                    "Last check failed {}: {}. The phones keep the list{}.{next}",
                    listing
                        .error_at
                        .as_deref()
                        .and_then(parse_stamp)
                        .map(|t| utc_time(t, now))
                        .unwrap_or_default(),
                    music::entry_error_text(&code),
                    listed_at
                        .map(|t| format!(" from {}", day_text(t)))
                        .unwrap_or_default(),
                ),
                (None, _) => format!(
                    "Couldn't list it: {}{next}",
                    music::CheckError::from_code(&code).message()
                ),
            };
            return block(text, "error", false);
        }
        match listing.and_then(|l| l.listed_at.as_deref().and_then(parse_stamp)) {
            Some(at) if listed.is_some() => block(format!("Checked {}", ago(at, now)), "ok", false),
            _ => block(waiting("the first check", 0), "busy", true),
        }
    }

    /// The cover the sweep fetched from the source.
    fn source_cover(&self, entry_id: i64) -> Option<String> {
        self.listings
            .get(&entry_id)
            .and_then(|l| l.cover_hash.clone())
    }

    /// The sweep part of an NRK/RSS card; `None` for own files.
    fn card(&self, entry: &MusicEntry) -> Option<SweepCard> {
        if !matches!(entry.source.as_str(), "nrk" | "rss") {
            return None;
        }
        use crate::music_sweep::parse_stamp;
        let listing = self.listings.get(&entry.id);
        let episodes = listing.filter(|l| l.version.is_some()).map(|l| {
            let mut text = format!(
                "{} episode{}",
                l.item_count,
                if l.item_count == 1 { "" } else { "s" }
            );
            if let Some((title, date)) = self.newest.get(&entry.id) {
                if let Some(title) = title {
                    text.push_str(&format!(" · newest: {title}"));
                }
                if let Some(date) = date.as_deref().and_then(parse_stamp) {
                    text.push_str(&format!(" · {}", day_text(date)));
                }
            }
            text
        });
        let mut notes = Vec::new();
        if let Some(listing) = listing.filter(|l| l.version.is_some()) {
            let series = entry
                .target
                .as_deref()
                .is_some_and(|t| t.contains("/serie/"));
            if listing.capped {
                let what = if series { "series" } else { "podcast" };
                let cap = if entry.source == "rss" {
                    crate::music_sweep::MAX_RSS_ITEMS
                } else {
                    crate::music_sweep::MAX_NRK_ITEMS
                };
                notes.push(match listing.keep_end.as_deref() {
                    Some("first") => format!("first {cap} episodes (the {what} has more)"),
                    _ => format!("the newest {cap} episodes (the {what} has more)"),
                });
            }
            if listing.cut {
                let end = if listing.keep_end.as_deref() == Some("first") {
                    "first"
                } else {
                    "newest"
                };
                notes.push(format!(
                    "list cut to the {end} {} (size limit)",
                    listing.item_count
                ));
            }
        }
        let requested_gap = listing
            .and_then(|l| l.requested_at.as_deref().and_then(parse_stamp))
            .map(|at| at + chrono::TimeDelta::minutes(crate::music_sweep::CHECK_NOW_GAP_MINUTES))
            .filter(|at| *at > self.now);
        let check_after = requested_gap
            .max(self.check_block)
            .map(|at| utc_time(at, self.now));
        let mut caches: Vec<(i64, String)> = CACHE_CHOICES
            .iter()
            .map(|(value, label)| (*value, label.to_string()))
            .collect();
        if !music::valid_cache(entry.cache) {
            caches.push((entry.cache, format!("{} (from Vibb)", entry.cache)));
        }
        Some(SweepCard {
            source_title: listing
                .and_then(|l| l.title.clone())
                .filter(|t| t != &entry.name),
            lan: listing.and_then(|l| l.lan) == Some(true),
            episodes,
            notes,
            status: self.status(entry),
            check_after,
            cache: entry.cache,
            caches,
        })
    }

    /// One line per phone with the entry ticked, from its last report (§3).
    fn phone_lines(&self, entry: &MusicEntry) -> Vec<String> {
        let Some(phones) = self.phones.get(&entry.id) else {
            return Vec::new();
        };
        let current = self.listings.get(&entry.id).and_then(|l| l.version.clone());
        let own = entry.source == "own";
        phones
            .iter()
            .map(|(device, name)| {
                let Some((report, at)) = self.reports.get(device) else {
                    return format!("{name}: not reported yet");
                };
                let row = report.downloads.iter().find(|d| d.entry == entry.id);
                let failing: Vec<&music::ItemError> = report
                    .item_errors
                    .iter()
                    .filter(|e| {
                        e.entry == entry.id && current.as_deref() == Some(e.version.as_str())
                    })
                    .collect();
                let mut parts: Vec<String> = Vec::new();
                match row {
                    Some(row) if !own && current.as_deref() != Some(row.version.as_str()) => {
                        parts.push("has an older list".to_string())
                    }
                    Some(row) => {
                        let mut text = format!("{} of {} offline", row.have, row.want);
                        match row.waiting.as_deref() {
                            Some("wifi") => text.push_str(" · waiting for Wi-Fi"),
                            Some("storage") => text.push_str(" · waiting for storage space"),
                            Some("roaming") => text.push_str(" · paused while roaming"),
                            _ => {}
                        }
                        parts.push(text);
                    }
                    // It reported, but not on this entry yet.
                    None if failing.is_empty() => {
                        parts.push("nothing on it reported yet".to_string())
                    }
                    None => {}
                }
                if let Some(first) = failing.first() {
                    parts.push(format!(
                        "{} failing ({})",
                        failing.len(),
                        music::entry_error_text(&first.error)
                    ));
                }
                let mut line = format!("{name}: {}", parts.join(" · "));
                if let Some(at) = at.filter(|at| *at < self.now - chrono::TimeDelta::hours(1)) {
                    line.push_str(&format!(" (as of {})", at.format("%-d %b %H:%M UTC")));
                }
                line
            })
            .collect()
    }
}

/// `POST /music/sweep` (`hours`): the family-wide sweep cadence; back to `#sweep`.
pub async fn save_sweep(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<FormMap>,
) -> Response {
    let Some(hours) = field(&form, "hours")
        .trim()
        .parse::<i64>()
        .ok()
        .filter(|h| crate::music_sweep::SWEEP_HOURS.contains(h))
    else {
        return (StatusCode::BAD_REQUEST, "Pick one of the choices.").into_response();
    };
    if let Err(err) = sqlx::query("UPDATE music_settings SET sweep_hours = ? WHERE id = 1")
        .bind(hours)
        .execute(&state.db)
        .await
    {
        return server_error(err, "couldn't save the music sweep setting");
    }
    // Nothing is rewritten: the next pass reads the setting (design 21b §2.2).
    state.music_sweep.wake();
    log_event(
        &state,
        &admin,
        "music_sweep_saved",
        &format!("every {hours} hours"),
    )
    .await;
    Redirect::to("/music#sweep").into_response()
}

/// `POST /music/entries/{id}/check` (`back` = "entry" from the entry's page): Check now, within
/// its limits (§2.1: 15 min per entry, 12 an hour, 48 a day); back to the page it came from. When
/// it isn't allowed the card already says when it is.
pub async fn check_now(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<FormMap>,
) -> Response {
    let back = if field(&form, "back") == "entry" {
        format!("/music/entries/{id}#status")
    } else {
        format!("/music#entry-{id}")
    };
    let entry = match load_entry(&state.db, id).await {
        Ok(Some(entry)) => entry,
        Ok(None) => return (StatusCode::NOT_FOUND, "No such entry").into_response(),
        Err(err) => return server_error(err, "music entry lookup failed"),
    };
    if !matches!(entry.source.as_str(), "nrk" | "rss") {
        return (
            StatusCode::BAD_REQUEST,
            "Only NRK and RSS entries are checked",
        )
            .into_response();
    }
    let now = state.music_sweep.now();
    match crate::music_sweep::check_now_blocked_until(&state.db, id, now).await {
        Ok(Some(_)) => return Redirect::to(&back).into_response(),
        Ok(None) => {}
        Err(err) => return server_error(err, "couldn't read the check limits"),
    }
    if let Err(err) = crate::music_sweep::request_check(&state.db, id, now).await {
        return server_error(err, "couldn't ask for a check");
    }
    state.music_sweep.wake();
    log_event(
        &state,
        &admin,
        "music_check_requested",
        &format!("entry {id}: {}", entry.name),
    )
    .await;
    Redirect::to(&back).into_response()
}

/// `POST /music/entries/{id}/offline` (`cache`): the card's offline select; back to the card.
pub async fn save_offline(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<FormMap>,
) -> Response {
    let entry = match load_entry(&state.db, id).await {
        Ok(Some(entry)) => entry,
        Ok(None) => return (StatusCode::NOT_FOUND, "No such entry").into_response(),
        Err(err) => return server_error(err, "music entry lookup failed"),
    };
    if entry.source == "own" {
        return (
            StatusCode::BAD_REQUEST,
            "Own files are always all on the phone",
        )
            .into_response();
    }
    let cache = field(&form, "cache")
        .trim()
        .parse::<i64>()
        .unwrap_or(i64::MIN);
    if !music::valid_cache(cache) && cache != entry.cache {
        return (StatusCode::BAD_REQUEST, "Pick one of the offline choices.").into_response();
    }
    if let Err(err) = sqlx::query("UPDATE music_entries SET cache = ? WHERE id = ?")
        .bind(cache)
        .bind(id)
        .execute(&state.db)
        .await
    {
        return server_error(err, "couldn't save a music entry's offline choice");
    }
    nudge(
        &state,
        &music::phones_with_entry(&state.db, id)
            .await
            .unwrap_or_default(),
    );
    log_event(
        &state,
        &admin,
        "music_entry_saved",
        &format!("entry {id}: {}, offline {cache}", entry.name),
    )
    .await;
    Redirect::to(&format!("/music#entry-{id}")).into_response()
}

#[derive(Template)]
#[template(path = "partials/music_status_only.html")]
struct StatusTemplate<'a> {
    status: &'a StatusBlock,
}

#[derive(serde::Deserialize)]
pub struct CardsQuery {
    #[serde(default)]
    ids: String,
}

/// Most cards the in-place update asks for at once.
pub const MAX_CARD_IDS: usize = 200;

/// `GET /music/cards?ids=1,2,3` (admin): each NRK/RSS card's status block, for
/// `static/music-cards.js` while a card says Checking or Waiting.
pub async fn cards(
    State(state): State<AppState>,
    axum::extract::Query(query): axum::extract::Query<CardsQuery>,
) -> Response {
    let ids: Result<Vec<i64>, _> = query
        .ids
        .split(',')
        .filter(|p| !p.trim().is_empty())
        .map(|p| p.trim().parse::<i64>())
        .collect();
    let Ok(ids) = ids else {
        return (StatusCode::BAD_REQUEST, "ids are numbers").into_response();
    };
    if ids.len() > MAX_CARD_IDS {
        return (StatusCode::BAD_REQUEST, "At most 200 cards at once").into_response();
    }
    let built: Result<Vec<serde_json::Value>, sqlx::Error> = async {
        let view = SweepView::load(&state, state.music_sweep.now()).await?;
        let entries: Vec<MusicEntry> = sqlx::query_as(
            "SELECT id, name, category_id, source, target, key, play_order, cache, resume, \
             cover_hash, sort FROM music_entries WHERE source IN ('nrk', 'rss')",
        )
        .fetch_all(&state.db)
        .await?;
        Ok(ids
            .iter()
            .filter_map(|id| entries.iter().find(|e| e.id == *id))
            .map(|entry| {
                let status = view.status(entry);
                serde_json::json!({
                    "id": entry.id,
                    "busy": status.busy,
                    "html": StatusTemplate { status: &status }.render().unwrap_or_default(),
                })
            })
            .collect())
    }
    .await;
    match built {
        Ok(cards) => (
            [(header::CACHE_CONTROL, "no-store")],
            axum::Json(serde_json::json!({ "cards": cards })),
        )
            .into_response(),
        Err(err) => server_error(err, "couldn't build the music cards"),
    }
}

// ------------------------------------------------------------------------------------------------
// The device page's Music card
// ------------------------------------------------------------------------------------------------

pub struct MusicTick {
    pub id: i64,
    pub name: String,
    pub detail: String,
    pub checked: bool,
    /// A refused tick of this entry (`?music_notice=...&music_entry=<id>`), shown by its row.
    pub notice: Option<&'static str>,
}

pub struct MusicAppSwitch {
    pub tracked_app_id: i64,
    pub package_name: String,
    pub checked: bool,
    pub status: String,
}

/// The device page's "Music" card (`#music`).
pub struct MusicCard {
    pub app: Option<MusicAppSwitch>,
    pub ticks: Vec<MusicTick>,
    pub mobile_data: bool,
    pub volume_cap: Option<i64>,
    /// Every choice and whether it is the current one.
    pub volume_caps: Vec<(i64, bool)>,
    pub storytel: bool,
    pub storytel_stored: bool,
    /// The library this phone gets: "3 entries · 12.3 kB", or none.
    pub library: Option<String>,
    pub lines: Vec<String>,
    pub warnings: Vec<String>,
    /// One-shot message after a refused tick (`?music_notice=`).
    pub notice: Option<&'static str>,
}

/// `?music_notice=` codes.
pub fn notice_text(code: &str) -> Option<&'static str> {
    Some(match code {
        "too_big" => {
            "Not ticked: with that entry this phone's music library would be bigger than 3 MB. \
             Untick another entry first."
        }
        _ => return None,
    })
}

pub(crate) async fn device_card(
    state: &AppState,
    policy: &DevicePolicy,
    latest_status: Option<&DeviceStatus>,
    notice: Option<&str>,
    notice_entry: Option<i64>,
) -> Result<MusicCard, sqlx::Error> {
    let notice = notice.and_then(notice_text);
    // A notice about one entry goes by its row; otherwise at the card's top.
    let (row_notice, notice) = match notice_entry {
        Some(entry) => (Some((entry, notice)), None),
        None => (None, notice),
    };
    let device_id = policy.device_id;
    let entries: Vec<(i64, String, String, Option<String>, String, bool)> = sqlx::query_as(
        "SELECT e.id, e.name, e.source, e.target, COALESCE(c.name, ''), \
         EXISTS(SELECT 1 FROM device_music_entries d WHERE d.device_id = ? AND d.entry_id = e.id) \
         FROM music_entries e LEFT JOIN music_categories c ON c.id = e.category_id \
         ORDER BY e.sort, e.id",
    )
    .bind(device_id)
    .fetch_all(&state.db)
    .await?;
    let names: HashMap<i64, String> = entries
        .iter()
        .map(|(id, name, ..)| (*id, name.clone()))
        .collect();
    let ticks = entries
        .into_iter()
        .map(|(id, name, source, target, category, checked)| MusicTick {
            id,
            name,
            detail: format!(
                "{} · {category}",
                music::kind_label(&source, target.as_deref())
            ),
            checked,
            notice: row_notice
                .filter(|(entry, _)| *entry == id)
                .and_then(|(_, notice)| notice),
        })
        .collect();

    let installed: Vec<InstalledApp> = latest_status
        .and_then(|s| s.installed_apps_json.as_deref())
        .and_then(|j| serde_json::from_str(j).ok())
        .unwrap_or_default();
    let app = match music_app_row(&state.db).await? {
        Some(row) => {
            let checked: bool = sqlx::query_scalar(
                "SELECT EXISTS(SELECT 1 FROM device_tracked_apps \
                 WHERE device_id = ? AND tracked_app_id = ?)",
            )
            .bind(device_id)
            .bind(row.id)
            .fetch_one(&state.db)
            .await?;
            let on_phone = installed
                .iter()
                .any(|a| a.package_name == row.package_name && !row.package_name.is_empty());
            let status = match (on_phone, &row.latest_release_tag) {
                (true, _) => "Installed".to_string(),
                (false, None) => "No release yet".to_string(),
                (false, Some(_)) if checked => "Waiting for the phone".to_string(),
                (false, Some(_)) => "Not installed".to_string(),
            };
            Some(MusicAppSwitch {
                tracked_app_id: row.id,
                package_name: row.package_name,
                checked,
                status,
            })
        }
        None => None,
    };

    let library = music::cached_library(&state.db, &state.music_libraries, device_id).await?;
    let entry_count: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM device_music_entries WHERE device_id = ?")
            .bind(device_id)
            .fetch_one(&state.db)
            .await?;
    let storytel_stored: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM music_storytel WHERE id = 1 AND ciphertext IS NOT NULL)",
    )
    .fetch_one(&state.db)
    .await?;

    let mut lines = Vec::new();
    let mut warnings = Vec::new();
    let capable = latest_status
        .and_then(|s| s.capabilities_json.as_deref())
        .and_then(|j| serde_json::from_str::<Vec<String>>(j).ok())
        .is_some_and(|caps| caps.iter().any(|c| c == "music_v1"));
    let state_report =
        music::parse_music_state(latest_status.and_then(|s| s.music_state_json.as_deref()));
    match &state_report {
        Some(report) => {
            if let Some(package) = &report.package {
                lines.push(match report.version_code {
                    Some(code) => format!("Music app: {package} (version code {code})"),
                    None => format!("Music app: {package}"),
                });
            }
            if let Some(bridge) = &report.bridge
                && bridge != "bound"
            {
                warnings.push(format!(
                    "The launcher can't reach the music app ({}).",
                    bridge.replace('_', " ")
                ));
            }
            let current = library.as_ref().map(|l| l.version.as_str());
            match (&report.library_version, current) {
                (Some(applied), Some(current)) if applied == current => {
                    lines.push("Library: up to date on the phone".to_string())
                }
                (Some(_), Some(_)) => lines.push(
                    "Library: the phone has an older one - it updates at its next sync".to_string(),
                ),
                (None, Some(_)) => lines.push("Library: not on the phone yet".to_string()),
                (_, None) => {}
            }
            if let Some(waiting) = report.downloads_waiting.filter(|n| *n > 0) {
                lines.push(format!("Downloads waiting: {waiting}"));
            }
            if let Some(bytes) = report.cache_bytes {
                lines.push(format!("Stored on the phone: {}", music::megabytes(bytes)));
            }
            if let Some(storytel) = &report.storytel {
                lines.push(format!(
                    "Storytel on the phone: {}",
                    storytel.replace('_', " ")
                ));
            }
            for error in &report.entry_errors {
                let entry = names
                    .get(&error.entry)
                    .map(|name| format!("\"{name}\""))
                    .unwrap_or_else(|| format!("Entry {}", error.entry));
                warnings.push(format!(
                    "{entry} on the phone: {}",
                    music::entry_error_text(&error.error)
                ));
            }
        }
        None if capable => lines.push("The phone hasn't reported on music yet.".to_string()),
        None => {}
    }
    if latest_status.is_some() && !capable && entry_count > 0 {
        warnings.push(
            "The launcher on this phone doesn't handle music yet - update it to deliver the \
             library."
                .to_string(),
        );
    }

    Ok(MusicCard {
        app,
        ticks,
        mobile_data: policy.music_mobile_data,
        volume_cap: music::volume_cap(policy.music_volume_cap_pct),
        volume_caps: VOLUME_CAPS
            .iter()
            .map(|cap| {
                (
                    *cap,
                    music::volume_cap(policy.music_volume_cap_pct) == Some(*cap),
                )
            })
            .collect(),
        storytel: policy.music_storytel,
        storytel_stored,
        library: library.map(|l| {
            format!(
                "{entry_count} entr{} · {:.1} kB",
                if entry_count == 1 { "y" } else { "ies" },
                l.json.len() as f64 / 1000.0
            )
        }),
        lines,
        warnings,
        notice,
    })
}

/// `POST /devices/{id}/music/entries/{entry_id}` (`selected` = ticked): one auto-saving tick.
/// Ticking is refused when the phone's library would pass the limit (QA #4).
pub async fn set_device_entry(
    State(state): State<AppState>,
    Path((id, entry_id)): Path<(i64, i64)>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<FormMap>,
) -> Response {
    let selected = form.contains_key("selected");
    let found: Result<(bool, Option<String>), sqlx::Error> = async {
        let device: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM devices WHERE id = ?)")
            .bind(id)
            .fetch_one(&state.db)
            .await?;
        let entry: Option<String> =
            sqlx::query_scalar("SELECT name FROM music_entries WHERE id = ?")
                .bind(entry_id)
                .fetch_optional(&state.db)
                .await?;
        Ok((device, entry))
    }
    .await;
    let name = match found {
        Ok((true, Some(name))) => name,
        Ok(_) => return (StatusCode::NOT_FOUND, "No such phone or entry").into_response(),
        Err(err) => return server_error(err, "music tick lookup failed"),
    };
    if selected {
        // The size check and the tick in one write transaction (qa-21-step1-code #6): two quick
        // ticks can't pass the limit together.
        let ticked: Result<bool, sqlx::Error> = async {
            let mut tx = state.db.begin_with("BEGIN IMMEDIATE").await?;
            if !music::tick_fits(&mut tx, id, entry_id).await? {
                return Ok(false);
            }
            sqlx::query(
                "INSERT OR IGNORE INTO device_music_entries (device_id, entry_id) VALUES (?, ?)",
            )
            .bind(id)
            .bind(entry_id)
            .execute(&mut *tx)
            .await?;
            tx.commit().await?;
            Ok(true)
        }
        .await;
        match ticked {
            Ok(true) => {}
            Ok(false) => {
                return Redirect::to(&format!(
                    "/devices/{id}?music_notice=too_big&music_entry={entry_id}#music-entry-{entry_id}"
                ))
                .into_response();
            }
            Err(err) => return server_error(err, "couldn't tick a music entry"),
        }
    } else if let Err(err) =
        sqlx::query("DELETE FROM device_music_entries WHERE device_id = ? AND entry_id = ?")
            .bind(id)
            .bind(entry_id)
            .execute(&state.db)
            .await
    {
        return server_error(err, "couldn't untick a music entry");
    }
    nudge(&state, &[id]);
    // A ticked entry may now follow the setting instead of once a day (design 21b §2.2).
    state.music_sweep.wake();
    log_event(
        &state,
        &admin,
        "music_phone_entry",
        &format!(
            "device {id}: {name} {}",
            if selected { "on" } else { "off" }
        ),
    )
    .await;
    // Back to that row, not the card's top (qa-21-step1-code #8).
    Redirect::to(&format!("/devices/{id}#music-entry-{entry_id}")).into_response()
}

/// `POST /devices/{id}/music`: mobile data (`mobile_data`), the volume cap (`volume_cap`: "off" or
/// one of [VOLUME_CAPS]) and Storytel on this phone (`storytel`). One auto-saving form: a missing
/// checkbox is off.
pub async fn save_device_settings(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<FormMap>,
) -> Response {
    let mobile_data = form.contains_key("mobile_data");
    let storytel = form.contains_key("storytel");
    let cap = match field(&form, "volume_cap").trim() {
        "" | "off" => None,
        value => match value.parse::<i64>() {
            Ok(pct) if VOLUME_CAPS.contains(&pct) => Some(pct),
            _ => return (StatusCode::BAD_REQUEST, "Unknown volume cap").into_response(),
        },
    };
    let updated = sqlx::query(
        "UPDATE device_policy SET music_mobile_data = ?, music_volume_cap_pct = ?, \
         music_storytel = ?, updated_at = datetime('now') WHERE device_id = ?",
    )
    .bind(mobile_data)
    .bind(cap)
    .bind(storytel)
    .bind(id)
    .execute(&state.db)
    .await;
    match updated {
        Ok(result) if result.rows_affected() == 0 => {
            return (StatusCode::NOT_FOUND, "Device not found").into_response();
        }
        Ok(_) => {}
        Err(err) => return server_error(err, "couldn't save a phone's music settings"),
    }
    nudge(&state, &[id]);
    log_event(
        &state,
        &admin,
        "music_phone_settings",
        &format!(
            "device {id}: mobile data {mobile_data}, volume cap {}, Storytel {storytel}",
            cap.map_or("off".to_string(), |c| format!("{c} %"))
        ),
    )
    .await;
    Redirect::to(&format!("/devices/{id}#music-settings")).into_response()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn upload_names_keep_only_the_last_component() {
        assert_eq!(
            original_name(Some("C:\\Musikk\\01 Sang.mp3")),
            "01 Sang.mp3"
        );
        assert_eq!(original_name(Some("../../etc/passwd")), "passwd");
        assert_eq!(original_name(None), "");
        assert_eq!(duration_text(Some(3_723_000)).as_deref(), Some("1:02:03"));
        assert_eq!(duration_text(Some(61_000)).as_deref(), Some("1:01"));
    }
}

#[cfg(test)]
mod fix_tests {
    use super::*;

    /// qa-21-step1-code #5: the free space is looked at before the first byte and every 64 MB.
    #[test]
    fn the_disk_guard_stops_below_the_floor() {
        let dir = tempfile::tempdir().unwrap();
        let mut guard = DiskGuard::new(dir.path(), u64::MAX);
        assert!(matches!(guard.check(0), Err(ReceiveError::NoSpace)));
        let mut guard = DiskGuard::new(dir.path(), 0);
        assert!(guard.check(0).is_ok());
        // Not looked at again before the next 64 MB (a floor that can't be met would show it).
        guard.floor = u64::MAX;
        assert!(guard.check(DISK_CHECK_BYTES - 1).is_ok());
        assert!(matches!(
            guard.check(DISK_CHECK_BYTES),
            Err(ReceiveError::NoSpace)
        ));
    }

    /// Test gap: an entry deleted while one of its uploads is on its way - the file goes, nothing
    /// is stored (the directory is gone, or the foreign key refuses the row).
    #[tokio::test]
    async fn an_upload_into_a_deleted_entry_leaves_no_file() {
        let app = crate::tests::TestApp::new().await;
        let tags = music::AudioTags {
            ext: "wav",
            title: None,
            artist: None,
            album: None,
            track_no: None,
            duration_ms: None,
            picture: None,
        };
        for directory_gone in [true, false] {
            let id: i64 = sqlx::query_scalar(
                "INSERT INTO music_entries (name, category_id, source, key) \
                 VALUES ('X', 1, 'own', ?) RETURNING id",
            )
            .bind(music::new_own_key())
            .fetch_one(&app.db)
            .await
            .unwrap();
            let dir = app.state.music_files_dir.join(id.to_string());
            std::fs::create_dir_all(&dir).unwrap();
            let temp_path = app.state.music_files_dir.join(format!("upload-{id}.part"));
            std::fs::write(&temp_path, b"audio").unwrap();
            // delete_entry meanwhile: the row goes, and (first case) the directory.
            sqlx::query("DELETE FROM music_entries WHERE id = ?")
                .bind(id)
                .execute(&app.db)
                .await
                .unwrap();
            if directory_gone {
                std::fs::remove_dir_all(&dir).unwrap();
            }
            let temp = TempFile {
                path: temp_path.clone(),
                keep: false,
            };
            let kept = keep_upload(&app.state, id, temp, 5, "abc", "a.wav", &tags).await;
            assert_eq!(kept, Kept::Failed, "directory gone: {directory_gone}");
            assert!(!temp_path.exists());
            let left = std::fs::read_dir(&dir).map(|d| d.count()).unwrap_or(0);
            assert_eq!(left, 0, "directory gone: {directory_gone}");
            let rows: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM music_files")
                .fetch_one(&app.db)
                .await
                .unwrap();
            assert_eq!(rows, 0);
        }
    }
}
