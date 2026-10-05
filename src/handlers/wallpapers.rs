//! The Wallpapers page and the device page's wallpaper card (design 08-ui-polish.md in the handy
//! workspace, QA qa-08-design.md). Uploads go through `photos::process` with
//! `Shape::Portrait` (upright, cropped to the phone's shape, no metadata) and are stored in the
//! wallpaper store only. A new upload is on no phone until the parent ticks it on a phone's page;
//! deleting one takes it off every phone (the phones then replace it at once).

use std::collections::HashSet;

use askama::Template;
use axum::Form;
use axum::extract::{Multipart, Path, State};
use axum::http::{StatusCode, header};
use axum::response::{Html, IntoResponse, Redirect, Response};

use crate::AppState;
use crate::photos::{self, PhotoError, Shape, WALLPAPERS};
use crate::wallpapers::{self as store, WallpaperRow};

/// Longest label kept (the phone reads it out with TalkBack).
const MAX_LABEL_CHARS: usize = 40;

pub struct WallpaperView {
    pub id: i64,
    pub label: String,
    pub background: String,
    pub image: Option<String>,
    pub builtin: bool,
    pub lock_screen: bool,
    pub devices: i64,
}

#[derive(Template)]
#[template(path = "wallpapers.html")]
struct WallpapersTemplate {
    title: String,
    error: Option<String>,
    wallpapers: Vec<WallpaperView>,
}

async fn page(state: &AppState, status: StatusCode, error: Option<String>) -> Response {
    let rows = match store::all(&state.db).await {
        Ok(rows) => rows,
        Err(err) => return db_error(err),
    };
    let counts: Vec<(i64, i64)> = match sqlx::query_as(
        "SELECT wallpaper_id, COUNT(*) FROM device_wallpapers GROUP BY wallpaper_id",
    )
    .fetch_all(&state.db)
    .await
    {
        Ok(counts) => counts,
        Err(err) => return db_error(err),
    };
    let wallpapers = rows
        .iter()
        .map(|row| WallpaperView {
            id: row.id,
            label: row.label.clone(),
            background: row.css_background(),
            image: row.image_hash.clone(),
            builtin: row.builtin_key.is_some(),
            lock_screen: row.lock_screen,
            devices: counts
                .iter()
                .find(|(id, _)| *id == row.id)
                .map_or(0, |(_, n)| *n),
        })
        .collect();
    let template = WallpapersTemplate {
        title: "Wallpapers".to_string(),
        error,
        wallpapers,
    };
    (status, Html(template.render().unwrap())).into_response()
}

fn db_error(err: sqlx::Error) -> Response {
    tracing::error!(%err, "wallpaper page database error");
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        "Couldn't load or save wallpapers - nothing was changed. Check the server log.",
    )
        .into_response()
}

fn nudge(state: &AppState, devices: &[i64]) {
    for device in devices {
        let _ = state.command_notify.send(*device);
    }
}

pub async fn show(State(state): State<AppState>) -> Response {
    page(&state, StatusCode::OK, None).await
}

/// Uploads a wallpaper (multipart: `image`, `label`, optional `lock_screen`). A file that isn't a
/// JPEG/PNG/WebP photo, is too big or can't be decoded is a 400 with the page and nothing is
/// stored. The new wallpaper is on no phone yet.
pub async fn upload(State(state): State<AppState>, mut multipart: Multipart) -> Response {
    let mut image = None;
    let mut label = String::new();
    let mut lock_screen = false;
    loop {
        match multipart.next_field().await {
            Ok(Some(field)) => match field.name() {
                Some("image") => match field.bytes().await {
                    Ok(bytes) => image = Some(bytes),
                    Err(_) => {
                        return page(
                            &state,
                            StatusCode::BAD_REQUEST,
                            Some(PhotoError::TooLarge.message().into()),
                        )
                        .await;
                    }
                },
                Some("label") => label = field.text().await.unwrap_or_default(),
                Some("lock_screen") => lock_screen = true,
                _ => {}
            },
            Ok(None) => break,
            Err(_) => {
                return page(
                    &state,
                    StatusCode::BAD_REQUEST,
                    Some(PhotoError::TooLarge.message().into()),
                )
                .await;
            }
        }
    }
    let label: String = label.trim().chars().take(MAX_LABEL_CHARS).collect();
    let label = if label.is_empty() {
        "Photo".to_string()
    } else {
        label
    };
    let Some(bytes) = image else {
        return page(
            &state,
            StatusCode::BAD_REQUEST,
            Some(PhotoError::Empty.message().into()),
        )
        .await;
    };
    let processed = match photos::process_limited(bytes, Shape::Portrait).await {
        Ok(processed) => processed,
        Err(err) => return page(&state, StatusCode::BAD_REQUEST, Some(err.message().into())).await,
    };
    let result = {
        let _files = WALLPAPERS.lock().await;
        if let Err(err) = photos::store(&state.wallpaper_dir, &processed).await {
            tracing::error!(%err, "couldn't store a wallpaper");
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't save the wallpaper - nothing was changed. Check the server log.",
            )
                .into_response();
        }
        sqlx::query(
            "INSERT INTO wallpapers (kind, image_hash, label, sort, lock_screen) \
             VALUES ('image', ?, ?, (SELECT COALESCE(MAX(sort), 0) + 10 FROM wallpapers), ?)",
        )
        .bind(&processed.hash)
        .bind(&label)
        .bind(lock_screen)
        .execute(&state.db)
        .await
    };
    match result {
        Ok(_) => Redirect::to("/wallpapers").into_response(),
        Err(err) => {
            WALLPAPERS.prune(&state.db, &state.wallpaper_dir).await;
            db_error(err)
        }
    }
}

async fn wallpaper(state: &AppState, id: i64) -> Result<Option<WallpaperRow>, sqlx::Error> {
    Ok(store::all(&state.db)
        .await?
        .into_iter()
        .find(|w| w.id == id))
}

/// "Also on the lock screen" for an uploaded image (`lock_screen=on` or absent).
pub async fn set_lock_screen(
    State(state): State<AppState>,
    Path(wallpaper_id): Path<i64>,
    Form(form): Form<Vec<(String, String)>>,
) -> Response {
    let on = form.iter().any(|(k, v)| k == "lock_screen" && v == "on");
    match wallpaper(&state, wallpaper_id).await {
        Ok(Some(w)) if w.kind == "image" => {}
        Ok(Some(_)) => {
            return (StatusCode::BAD_REQUEST, "Only photos have this choice").into_response();
        }
        Ok(None) => return (StatusCode::NOT_FOUND, "No such wallpaper").into_response(),
        Err(err) => return db_error(err),
    }
    let result = async {
        sqlx::query("UPDATE wallpapers SET lock_screen = ? WHERE id = ?")
            .bind(on)
            .bind(wallpaper_id)
            .execute(&state.db)
            .await?;
        store::devices_with(&state.db, wallpaper_id).await
    }
    .await;
    match result {
        Ok(devices) => {
            nudge(&state, &devices);
            Redirect::to("/wallpapers").into_response()
        }
        Err(err) => db_error(err),
    }
}

/// Deletes an uploaded wallpaper: off every phone (their rows cascade, the phones are nudged and
/// replace it at once), then the file is pruned. Built-ins can't be deleted, only unticked.
pub async fn delete(State(state): State<AppState>, Path(wallpaper_id): Path<i64>) -> Response {
    match wallpaper(&state, wallpaper_id).await {
        Ok(Some(w)) if w.builtin_key.is_none() => {}
        Ok(Some(_)) => {
            return page(
                &state,
                StatusCode::BAD_REQUEST,
                Some(
                    "Built-in wallpapers can't be deleted - untick them on a phone's page instead."
                        .into(),
                ),
            )
            .await;
        }
        Ok(None) => return (StatusCode::NOT_FOUND, "No such wallpaper").into_response(),
        Err(err) => return db_error(err),
    }
    let result = async {
        let devices = store::devices_with(&state.db, wallpaper_id).await?;
        let _files = WALLPAPERS.lock().await;
        sqlx::query("DELETE FROM wallpapers WHERE id = ?")
            .bind(wallpaper_id)
            .execute(&state.db)
            .await?;
        Ok::<_, sqlx::Error>(devices)
    }
    .await;
    match result {
        Ok(devices) => {
            WALLPAPERS.prune(&state.db, &state.wallpaper_dir).await;
            nudge(&state, &devices);
            Redirect::to("/wallpapers").into_response()
        }
        Err(err) => db_error(err),
    }
}

/// A stored wallpaper image for the admin pages (behind the admin session like every page).
pub async fn view_image(State(state): State<AppState>, Path(hash): Path<String>) -> Response {
    let Some(path) = photos::path_for(&state.wallpaper_dir, &hash) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    match tokio::fs::read(&path).await {
        Ok(bytes) => (
            [
                (header::CONTENT_TYPE, "image/jpeg"),
                (
                    header::CACHE_CONTROL,
                    "private, max-age=31536000, immutable",
                ),
            ],
            bytes,
        )
            .into_response(),
        Err(_) => StatusCode::NOT_FOUND.into_response(),
    }
}

/// One choice on the device page's wallpaper card.
pub struct DeviceWallpaperChoice {
    pub id: i64,
    pub label: String,
    pub background: String,
    pub image: Option<String>,
    pub checked: bool,
}

/// The device page's card: every wallpaper, ticked when this phone may use it.
pub async fn device_choices(
    state: &AppState,
    device_id: i64,
) -> Result<Vec<DeviceWallpaperChoice>, sqlx::Error> {
    let allowed: HashSet<i64> = store::for_device(&state.db, device_id)
        .await?
        .iter()
        .map(|w| w.id)
        .collect();
    Ok(store::all(&state.db)
        .await?
        .into_iter()
        .map(|w| DeviceWallpaperChoice {
            id: w.id,
            background: w.css_background(),
            checked: allowed.contains(&w.id),
            label: w.label,
            image: w.image_hash,
        })
        .collect())
}

/// Saves which wallpapers a phone may use (`wallpaper=<id>`, repeated). An unknown id is a 400
/// and nothing changes; the phone is nudged and, if its current one was unticked, replaces it.
pub async fn save_device_wallpapers(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<Vec<(String, String)>>,
) -> Response {
    let mut chosen = Vec::new();
    for (key, value) in &form {
        if key != "wallpaper" {
            continue;
        }
        match value.parse::<i64>() {
            Ok(v) => chosen.push(v),
            Err(_) => return (StatusCode::BAD_REQUEST, "Unknown wallpaper").into_response(),
        }
    }
    let result = async {
        let mut tx = state.db.begin().await?;
        let exists: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM devices WHERE id = ?)")
            .bind(id)
            .fetch_one(&mut *tx)
            .await?;
        if !exists {
            return Ok(Err(StatusCode::NOT_FOUND));
        }
        sqlx::query("DELETE FROM device_wallpapers WHERE device_id = ?")
            .bind(id)
            .execute(&mut *tx)
            .await?;
        for wallpaper_id in &chosen {
            let known: bool =
                sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM wallpapers WHERE id = ?)")
                    .bind(wallpaper_id)
                    .fetch_one(&mut *tx)
                    .await?;
            if !known {
                return Ok(Err(StatusCode::BAD_REQUEST));
            }
            sqlx::query(
                "INSERT OR IGNORE INTO device_wallpapers (device_id, wallpaper_id) VALUES (?, ?)",
            )
            .bind(id)
            .bind(wallpaper_id)
            .execute(&mut *tx)
            .await?;
        }
        tx.commit().await?;
        Ok::<_, sqlx::Error>(Ok(()))
    }
    .await;
    match result {
        Ok(Ok(())) => {
            nudge(&state, &[id]);
            Redirect::to(&format!("/devices/{id}")).into_response()
        }
        Ok(Err(StatusCode::NOT_FOUND)) => {
            (StatusCode::NOT_FOUND, "Device not found").into_response()
        }
        Ok(Err(status)) => (status, "Unknown wallpaper").into_response(),
        Err(err) => db_error(err),
    }
}
