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
    let entry_rows = entries
        .iter()
        .enumerate()
        .map(|(index, e)| {
            let category = category_of(e.category_id);
            let (file_count, missing) = files.get(&e.id).copied().unwrap_or((0, 0));
            EntryRow {
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
                cover: e.cover_hash.clone(),
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
    })
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
    let inserted: Result<i64, sqlx::Error> = sqlx::query_scalar(&format!(
        "INSERT INTO music_entries (name, category_id, source, target, key, cache, resume, sort) \
         VALUES (?, ?, ?, ?, ?, ?, 1, {NEXT_SORT}) RETURNING id"
    ))
    .bind(&title)
    .bind(category)
    .bind(link.source())
    .bind(&target)
    .bind(music::state_key(&target))
    .bind(music::DEFAULT_CACHE)
    .fetch_one(&state.db)
    .await;
    let id = match inserted {
        Ok(id) => id,
        // Added by another request since the check above.
        Err(sqlx::Error::Database(err)) if err.is_unique_violation() => {
            let error = "That link is already in the library.".to_string();
            return render_music(&state, StatusCode::BAD_REQUEST, refuse(error)).await;
        }
        Err(err) => return server_error(err, "couldn't add a music entry"),
    };
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
    match entry_count(&state.db).await {
        Ok(n) if n >= MAX_ENTRIES => {
            let error = format!("The library has {MAX_ENTRIES} entries - delete one first.");
            return render_music(&state, StatusCode::BAD_REQUEST, refuse(error)).await;
        }
        Ok(_) => {}
        Err(err) => return server_error(err, "music entry count failed"),
    }
    let category = match pick_category(&state.db, &picked, "own").await {
        Ok(Ok(id)) => id,
        Ok(Err(error)) => {
            return render_music(&state, StatusCode::BAD_REQUEST, refuse(error.to_string())).await;
        }
        Err(err) => return server_error(err, "music category lookup failed"),
    };
    // The key is "own-<id>": a placeholder first, then the id, in one transaction.
    let result: Result<i64, sqlx::Error> = async {
        let mut tx = state.db.begin().await?;
        let id: i64 = sqlx::query_scalar(&format!(
            "INSERT INTO music_entries (name, category_id, source, key, cache, resume, sort) \
             VALUES (?, ?, 'own', ?, -1, 0, {NEXT_SORT}) RETURNING id"
        ))
        .bind(&name)
        .bind(category)
        .bind(format!("own-new-{}", random_name()))
        .fetch_one(&mut *tx)
        .await?;
        sqlx::query("UPDATE music_entries SET key = ? WHERE id = ?")
            .bind(format!("own-{id}"))
            .bind(id)
            .execute(&mut *tx)
            .await?;
        tx.commit().await?;
        Ok(id)
    }
    .await;
    let id = match result {
        Ok(id) => id,
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
    Disk(String),
}

/// Streams one multipart file to `<dir>/<random>.part`, hashing it as it goes; nothing is held in
/// memory beyond one chunk.
async fn receive(
    file_field: &mut Field<'_>,
    dir: &FsPath,
) -> Result<(TempFile, u64, String), ReceiveError> {
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
    let mut count: i64 =
        match sqlx::query_scalar("SELECT COUNT(*) FROM music_files WHERE entry_id = ?")
            .bind(id)
            .fetch_one(&state.db)
            .await
        {
            Ok(count) => count,
            Err(err) => return server_error(err, "music file count failed"),
        };
    let mut added: Vec<i64> = Vec::new();
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
        if count >= MAX_FILES_PER_ENTRY {
            problems.push(format!(
                "{original}: not saved - an entry holds at most {MAX_FILES_PER_ENTRY} files."
            ));
            continue;
        }
        if music::free_bytes(&dir).is_none_or(|free| free < MIN_FREE_BYTES) {
            problems.push(format!(
                "{original}: not saved - the server has less than {} GB free.",
                MIN_FREE_BYTES / 1_000_000_000
            ));
            break;
        }
        let (mut temp, size, sha256) = match receive(&mut file_field, &dir).await {
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
        let stored_name = format!("{}.{}", random_name(), tags.ext);
        let final_path = dir.join(&stored_name);
        if let Err(err) = tokio::fs::rename(&temp.path, &final_path).await {
            tracing::error!(%err, "couldn't move an uploaded music file into place");
            problems.push(format!("{original}: couldn't be saved on the server."));
            continue;
        }
        temp.keep = true;
        let art = match tags.picture.clone() {
            Some(picture) => photos::process_limited(picture.into(), Shape::Square)
                .await
                .ok(),
            None => None,
        };
        let inserted: Result<i64, sqlx::Error> = {
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
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0) RETURNING id",
            )
            .bind(id)
            .bind(format!("{id}/{stored_name}"))
            .bind(&original)
            .bind(i64::try_from(size).unwrap_or(i64::MAX))
            .bind(&sha256)
            .bind(&tags.title)
            .bind(&tags.artist)
            .bind(&tags.album)
            .bind(tags.track_no)
            .bind(tags.duration_ms)
            .bind(&art_hash)
            .fetch_one(&state.db)
            .await
        };
        match inserted {
            Ok(file_id) => {
                count += 1;
                added.push(file_id);
            }
            Err(err) => {
                tracing::error!(%err, "couldn't store an own music file");
                tokio::fs::remove_file(&final_path).await.ok();
                problems.push(format!("{original}: couldn't be saved on the server."));
            }
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
            let too_big = match music::device_library(&state.db, *phone).await {
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
    if !added.is_empty() {
        nudge(&state, &phones);
        log_event(
            &state,
            &admin,
            "music_files_added",
            &format!("entry {id}: {} file(s)", added.len()),
        )
        .await;
    }
    if problems.is_empty() {
        return Redirect::to(&format!("/music/entries/{id}#files")).into_response();
    }
    let entered = EntryEntered {
        upload_problems: problems,
        upload_saved: added.len(),
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
    Redirect::to(&format!("/music/entries/{id}#files")).into_response()
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
// The device page's Music card
// ------------------------------------------------------------------------------------------------

pub struct MusicTick {
    pub id: i64,
    pub name: String,
    pub detail: String,
    pub checked: bool,
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
) -> Result<MusicCard, sqlx::Error> {
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

    let library = music::device_library(&state.db, device_id).await?;
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
        notice: notice.and_then(notice_text),
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
        let fits = match state.db.acquire().await {
            Ok(mut conn) => music::tick_fits(&mut conn, id, entry_id).await,
            Err(err) => Err(err),
        };
        match fits {
            Ok(true) => {}
            Ok(false) => {
                return Redirect::to(&format!("/devices/{id}?music_notice=too_big#music"))
                    .into_response();
            }
            Err(err) => return server_error(err, "couldn't check a phone's library size"),
        }
        if let Err(err) = sqlx::query(
            "INSERT OR IGNORE INTO device_music_entries (device_id, entry_id) VALUES (?, ?)",
        )
        .bind(id)
        .bind(entry_id)
        .execute(&state.db)
        .await
        {
            return server_error(err, "couldn't tick a music entry");
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
    Redirect::to(&format!("/devices/{id}#music")).into_response()
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
    Redirect::to(&format!("/devices/{id}#music")).into_response()
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
