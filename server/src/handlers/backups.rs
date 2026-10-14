//! Admin-triggered and scheduled backups: a consistent snapshot of the
//! SQLite database, zipped up and stored under `data/backups/`.
//!
//! Backup creation itself never needs root - it only touches `data/`, which
//! this process can already write to - so scheduling lives entirely in this
//! process as a background task (see `run_scheduled_backups` in main.rs),
//! unlike the OS/Tailscale scheduler which needs a separate root component.
//! The one piece that *does* need root is copying backups to an external
//! drive (outside `data/`), which the root scheduler in install.sh handles.

use askama::Template;
use axum::Extension;
use axum::extract::{Path, Query, State};
use axum::http::{StatusCode, header};
use axum::response::{Html, IntoResponse, Redirect, Response};
use serde::Deserialize;
use std::io::Write;

use crate::AppState;
use crate::handlers::settings::Flash;
use crate::security::{self, CurrentAdmin};

/// The backups' folder in `AppState::data_dir` (`data/backups`).
pub(crate) const BACKUP_DIR: &str = "backups";
const SCHEDULE_FILE: &str = "backup_schedule.conf";
const LAST_RUN_FILE: &str = "backup_schedule_last_run";
const EXTERNAL_MOUNT: &str = "/mnt/kid-phone-server-backup";
const LIVE_MIRROR_DB: &str = "live_mirror.db";
/// The root helper's request file in `data_dir` (`system_update` and `system_maintenance` write
/// the same one).
pub(crate) const FLAG_FILE: &str = "update_requested";

/// A backup in the list: when it was made (local, the exact time in `title`) and its size; the
/// file name only inside its Restore… confirm, where it is typed.
struct BackupRow {
    filename: String,
    size_display: String,
    when: String,
    exact: String,
}

#[derive(Clone)]
pub struct BackupScheduleConfig {
    frequency: String,
    day_of_week: String,
    day_of_month: String,
    backup_time: String,
    retention_count: String,
    external_copy_enabled: bool,
    continuous_mirror_enabled: bool,
}

impl BackupScheduleConfig {
    fn defaults() -> Self {
        BackupScheduleConfig {
            frequency: "daily".to_string(),
            day_of_week: "0".to_string(),
            day_of_month: "1".to_string(),
            backup_time: "02:00".to_string(),
            retention_count: "14".to_string(),
            external_copy_enabled: false,
            continuous_mirror_enabled: false,
        }
    }

    pub async fn load(data: &std::path::Path) -> Self {
        let Ok(contents) = tokio::fs::read_to_string(data.join(SCHEDULE_FILE)).await else {
            return Self::defaults();
        };
        let mut config = Self::defaults();
        for line in contents.lines() {
            let Some((key, value)) = line.split_once('=') else {
                continue;
            };
            let value = value.trim().to_string();
            match key.trim() {
                "FREQUENCY" => config.frequency = value,
                "DAY_OF_WEEK" => config.day_of_week = value,
                "DAY_OF_MONTH" => config.day_of_month = value,
                "BACKUP_TIME" => config.backup_time = value,
                "RETENTION_COUNT" => config.retention_count = value,
                "EXTERNAL_COPY_ENABLED" => config.external_copy_enabled = value == "true",
                "CONTINUOUS_MIRROR_ENABLED" => config.continuous_mirror_enabled = value == "true",
                _ => {}
            }
        }
        config
    }

    fn to_file_contents(&self) -> String {
        format!(
            "FREQUENCY={}\nDAY_OF_WEEK={}\nDAY_OF_MONTH={}\nBACKUP_TIME={}\nRETENTION_COUNT={}\nEXTERNAL_COPY_ENABLED={}\nCONTINUOUS_MIRROR_ENABLED={}\n",
            self.frequency,
            self.day_of_week,
            self.day_of_month,
            self.backup_time,
            self.retention_count,
            self.external_copy_enabled,
            self.continuous_mirror_enabled,
        )
    }

    fn retention_count(&self) -> Option<usize> {
        self.retention_count
            .trim()
            .parse::<usize>()
            .ok()
            .filter(|n| *n > 0)
    }
}

/// The schedule forms' day choices (Sunday = 0, as the schedule files and the root scheduler
/// read them).
pub(crate) const WEEKDAYS: [(&str, &str); 7] = [
    ("1", "Monday"),
    ("2", "Tuesday"),
    ("3", "Wednesday"),
    ("4", "Thursday"),
    ("5", "Friday"),
    ("6", "Saturday"),
    ("0", "Sunday"),
];

async fn external_drive_mounted() -> bool {
    tokio::process::Command::new("mountpoint")
        .args(["-q", EXTERNAL_MOUNT])
        .status()
        .await
        .map(|s| s.success())
        .unwrap_or(false)
}

fn human_size(bytes: u64) -> String {
    const UNITS: [&str; 4] = ["B", "KB", "MB", "GB"];
    let mut size = bytes as f64;
    let mut unit = 0;
    while size >= 1024.0 && unit < UNITS.len() - 1 {
        size /= 1024.0;
        unit += 1;
    }
    if unit == 0 {
        format!("{bytes} {}", UNITS[unit])
    } else {
        format!("{size:.1} {}", UNITS[unit])
    }
}

pub(crate) fn is_safe_backup_filename(name: &str) -> bool {
    name.starts_with("backup-")
        && name.ends_with(".zip")
        && !name.contains('/')
        && !name.contains('\\')
        && !name.contains("..")
}

async fn list_backup_filenames(dir: &std::path::Path) -> Vec<String> {
    let mut names = Vec::new();
    if let Ok(mut entries) = tokio::fs::read_dir(dir).await {
        while let Ok(Some(entry)) = entries.next_entry().await {
            let filename = entry.file_name().to_string_lossy().to_string();
            if is_safe_backup_filename(&filename) {
                names.push(filename);
            }
        }
    }
    names.sort();
    names
}

/// Deletes the oldest backups beyond `retention_count` (filenames are
/// timestamp-prefixed, so lexical order is chronological order).
pub async fn prune_old_backups(dir: &std::path::Path, retention_count: usize) {
    let names = list_backup_filenames(dir).await;
    if names.len() <= retention_count {
        return;
    }
    for name in &names[..names.len() - retention_count] {
        let _ = tokio::fs::remove_file(dir.join(name)).await;
    }
}

/// The database snapshot plus every contact photo (`contact_photos/<hash>.jpg`), wallpaper image
/// (`wallpapers/<hash>.jpg`) and music cover (`music_covers/<hash>.jpg`). Never the parent's own
/// music files (design 21: too big; a restore lists them as missing), nor episode art (design 27:
/// `photos::MUSIC_ART` is fetched again by the sweep). A restore swaps in only the
/// database (deploy/install.sh); the server puts back missing files from the backups at startup
/// (`photos::recover_missing`).
pub(crate) fn build_backup_zip(
    db_snapshot_path: &str,
    zip_path: &str,
    photo_dir: &std::path::Path,
    wallpaper_dir: &std::path::Path,
    music_cover_dir: &std::path::Path,
) -> std::io::Result<()> {
    let file = std::fs::File::create(zip_path)?;
    let mut writer = zip::ZipWriter::new(file);
    let options = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated);

    writer.start_file("kidphone.db", options)?;
    writer.write_all(&std::fs::read(db_snapshot_path)?)?;
    crate::photos::add_to_zip(&mut writer, photo_dir)?;
    crate::photos::WALLPAPERS.add_to_zip(&mut writer, wallpaper_dir)?;
    crate::photos::MUSIC_COVERS.add_to_zip(&mut writer, music_cover_dir)?;

    writer.finish()?;
    Ok(())
}

/// A consistent copy of the live database (`VACUUM INTO`) at `target`, with no admin sessions in
/// it (security review M2): a backup zip, the live mirror and a pre-migration copy must never sign
/// anyone in - nor carry the phones' Tailscale key ([strip_secrets_in]). The copy is made at `scratch` (in `data/`, which `backup_sync.sh` never copies off the
/// box), the sessions are deleted with `secure_delete` (their bytes zeroed, not left in free pages),
/// and only then is it renamed to `target`. The file is 0600 by the process umask (`main`).
pub(crate) async fn snapshot(
    db: &sqlx::SqlitePool,
    scratch: &std::path::Path,
    target: &std::path::Path,
) -> Result<(), String> {
    // VACUUM INTO refuses an existing file.
    let _ = tokio::fs::remove_file(scratch).await;
    let result = async {
        sqlx::query("VACUUM INTO ?")
            .bind(scratch.to_string_lossy().to_string())
            .execute(db)
            .await
            .map_err(|err| format!("the snapshot failed: {err}"))?;
        strip_secrets_in(scratch).await?;
        tokio::fs::rename(scratch, target)
            .await
            .map_err(|err| format!("couldn't move the snapshot to {}: {err}", target.display()))
    }
    .await;
    if result.is_err() {
        let _ = tokio::fs::remove_file(scratch).await;
    }
    result
}

/// Clears, in the database file at `path`, what no copy of it may carry: every admin session
/// (`tower_sessions`) and the Tailscale auth key for the phones (QA of the security wave, a note
/// on M2: a backup was a way onto the tailnet). The key is write-only in the PWA and one-time, so
/// after a restore the parent enters a new one before the next phone's setup. In rollback-journal
/// mode with the journal in memory, so the copy stays a single file; `secure_delete` zeroes the old
/// bytes.
async fn strip_secrets_in(path: &std::path::Path) -> Result<(), String> {
    use sqlx::Connection;
    let options = sqlx::sqlite::SqliteConnectOptions::new()
        .filename(path)
        .journal_mode(sqlx::sqlite::SqliteJournalMode::Memory)
        .pragma("secure_delete", "on");
    let fail = |err: sqlx::Error| format!("couldn't clear the secrets in the snapshot: {err}");
    let mut conn = sqlx::SqliteConnection::connect_with(&options)
        .await
        .map_err(fail)?;
    for (table, clear) in [
        ("tower_sessions", "DELETE FROM tower_sessions"),
        (
            "provisioning_settings",
            "UPDATE provisioning_settings SET tailscale_auth_key = '' WHERE tailscale_auth_key != ''",
        ),
    ] {
        let exists: bool = sqlx::query_scalar(
            "SELECT EXISTS (SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?)",
        )
        .bind(table)
        .fetch_one(&mut conn)
        .await
        .map_err(fail)?;
        if exists {
            sqlx::query(clear).execute(&mut conn).await.map_err(fail)?;
        }
    }
    conn.close().await.map_err(fail)
}

/// Creates a full backup (a DB snapshot, zipped). Used by both the
/// admin-triggered handler and the background scheduled-backup task, so the
/// actual backup logic only needs to be reviewed/maintained in one place.
pub async fn perform_backup(state: &AppState) -> Result<String, String> {
    let backup_dir = state.data_dir.join(BACKUP_DIR);
    tokio::fs::create_dir_all(&backup_dir)
        .await
        .map_err(|_| "Couldn't create the backups folder.".to_string())?;

    let timestamp = chrono::Local::now().format("%Y%m%d-%H%M%S").to_string();
    // In data/, not data/backups/: backup_sync.sh copies everything in backups/ to the drive.
    let snapshot_path = state
        .data_dir
        .join(format!("backup-snapshot-{timestamp}.db"))
        .to_string_lossy()
        .into_owned();
    let zip_filename = format!("backup-{timestamp}.zip");
    let zip_path = backup_dir
        .join(&zip_filename)
        .to_string_lossy()
        .into_owned();

    // VACUUM INTO takes a consistent, complete snapshot of the live database
    // (including anything only committed to the WAL so far) without needing
    // to stop the app or risk a torn read from copying the file directly.
    let snapshot_file = std::path::PathBuf::from(&snapshot_path);
    snapshot(
        &state.db,
        &snapshot_file.with_extension("db.tmp"),
        &snapshot_file,
    )
    .await
    .map_err(|e| format!("Snapshot failed: {e}"))?;

    let snapshot_path_for_zip = snapshot_path.clone();
    let zip_path_for_zip = zip_path.clone();
    let photo_dir = state.photo_dir.as_path().to_path_buf();
    let wallpaper_dir = state.wallpaper_dir.as_path().to_path_buf();
    let music_cover_dir = state.music_cover_dir.as_path().to_path_buf();
    let zip_result = tokio::task::spawn_blocking(move || {
        build_backup_zip(
            &snapshot_path_for_zip,
            &zip_path_for_zip,
            &photo_dir,
            &wallpaper_dir,
            &music_cover_dir,
        )
    })
    .await;

    let _ = tokio::fs::remove_file(&snapshot_path).await;

    match zip_result {
        Ok(Ok(())) => Ok(zip_filename),
        Ok(Err(e)) => {
            let _ = tokio::fs::remove_file(&zip_path).await;
            Err(format!("Failed to build backup zip: {e}"))
        }
        Err(e) => Err(format!("Backup zip task panicked: {e}")),
    }
}

#[derive(Template)]
#[template(path = "backups.html")]
struct BackupsTemplate {
    title: String,
    backups: Vec<BackupRow>,
    /// The last form's result, in its group ("backups", "upload", "schedule", "drive").
    flash: Option<Flash>,
    /// A refused restore: the file whose confirm renders open, and why.
    restore_refused: Option<(String, &'static str)>,
    /// A refused format: the drive whose confirm renders open, and why.
    format_refused: Option<(String, &'static str)>,
    schedule: BackupScheduleConfig,
    weekdays: &'static [(&'static str, &'static str)],
    external_mount: String,
    external_drive_mounted: bool,
    removable_drives: Vec<RemovableDrive>,
    /// `DEPLOY_KIND=docker` (design 22 §6): no external drive, live mirror or formatting.
    docker: bool,
}

impl BackupsTemplate {
    /// Why a restore of `file` was refused, if it was (its confirm then renders open).
    fn restore_error(&self, file: &str) -> Option<&'static str> {
        self.restore_refused
            .as_ref()
            .filter(|(refused, _)| refused == file)
            .map(|(_, why)| *why)
    }

    /// Why formatting `device` was refused, if it was.
    fn format_error(&self, device: &str) -> Option<&'static str> {
        self.format_refused
            .as_ref()
            .filter(|(refused, _)| refused == device)
            .map(|(_, why)| *why)
    }
}

/// What a form on Backups sent back, besides the page.
#[derive(Default)]
struct Answer {
    flash: Option<Flash>,
    restore_refused: Option<(String, &'static str)>,
    format_refused: Option<(String, &'static str)>,
}

/// What a form that worked did (qa-31-s14-code M1): the POST redirects to
/// `/backups?done=<code>#<group>` and the GET shows its line in that group, so a reload repeats
/// nothing (no second restore, backup or format). Refusals still render in the POST's answer: they
/// changed nothing, and a typed confirm never goes into a URL.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Done {
    Backup,
    Deleted,
    Gone,
    Schedule,
    Upload,
    Restore,
    Format,
}

impl Done {
    const ALL: [Done; 7] = [
        Done::Backup,
        Done::Deleted,
        Done::Gone,
        Done::Schedule,
        Done::Upload,
        Done::Restore,
        Done::Format,
    ];

    fn code(self) -> &'static str {
        match self {
            Done::Backup => "backup",
            Done::Deleted => "deleted",
            Done::Gone => "gone",
            Done::Schedule => "schedule",
            Done::Upload => "upload",
            Done::Restore => "restore",
            Done::Format => "format",
        }
    }

    fn group(self) -> &'static str {
        match self {
            Done::Schedule => "schedule",
            Done::Upload => "upload",
            Done::Format => "drive",
            _ => "backups",
        }
    }

    fn parse(code: &str) -> Option<Done> {
        Done::ALL.into_iter().find(|done| done.code() == code)
    }

    /// The line in its group. A restore's result is shown on the page the server answers after
    /// it is back, so it says what happened.
    fn flash(self, docker: bool) -> Flash {
        let text = match self {
            Done::Backup => "Backup made.",
            Done::Deleted => "Backup deleted.",
            Done::Gone => "That backup was already deleted.",
            Done::Schedule => "Schedule saved.",
            Done::Upload => "Uploaded. It is at the top of the list.",
            Done::Restore if docker => {
                "Restore started. The server puts the backup in place and starts again within a \
                 few seconds; everyone signs in again then."
            }
            Done::Restore => {
                "Restore started. The server puts the backup in place and starts again within \
                 about a minute; everyone signs in again then."
            }
            Done::Format => {
                "Formatting the drive. It takes about a minute; reload this page then to see it."
            }
        };
        Flash::done(self.group(), text)
    }

    fn redirect(self) -> Response {
        Redirect::to(&format!("/backups?done={}#{}", self.code(), self.group())).into_response()
    }
}

impl Answer {
    fn refused(at: &'static str, title: impl Into<String>) -> Answer {
        Answer {
            flash: Some(Flash::refused(at, title)),
            ..Answer::default()
        }
    }
}

/// The page. Every form renders its answer here, in the POST's response (as before design 31), and
/// posts to its group's fragment, so the answer lands where the parent was.
async fn render_backups(state: &AppState, status: StatusCode, answer: Answer) -> Response {
    let docker = state.net.deploy_kind == crate::net::DeployKind::Docker;
    let zone = crate::display_time::Zone::load(&state.db).await;
    let now = chrono::Utc::now();
    let mut backups: Vec<BackupRow> = Vec::new();
    if let Ok(mut entries) = tokio::fs::read_dir(state.data_dir.join(BACKUP_DIR)).await {
        while let Ok(Some(entry)) = entries.next_entry().await {
            let filename = entry.file_name().to_string_lossy().to_string();
            if !is_safe_backup_filename(&filename) {
                continue;
            }
            let Ok(meta) = entry.metadata().await else {
                continue;
            };
            let made = meta
                .modified()
                .ok()
                .map(chrono::DateTime::<chrono::Utc>::from);
            backups.push(BackupRow {
                filename,
                size_display: human_size(meta.len()),
                when: made.map_or_else(|| "Time unknown".to_string(), |t| zone.when(t, now)),
                exact: made.map(|t| zone.exact(t)).unwrap_or_default(),
            });
        }
    }
    // The file names start with their time: newest first.
    backups.sort_by(|a, b| b.filename.cmp(&a.filename));

    let page = BackupsTemplate {
        title: "Backups".to_string(),
        backups,
        flash: answer.flash,
        restore_refused: answer.restore_refused,
        format_refused: answer.format_refused,
        schedule: BackupScheduleConfig::load(&state.data_dir).await,
        weekdays: &WEEKDAYS,
        external_mount: EXTERNAL_MOUNT.to_string(),
        external_drive_mounted: !docker && external_drive_mounted().await,
        removable_drives: if docker {
            Vec::new()
        } else {
            list_removable_drives().await
        },
        docker,
    };
    (status, Html(page.render().unwrap())).into_response()
}

/// The page; `?done=<code>` (a form that worked, [Done]) shows its line in its group.
pub async fn list_backups(
    State(state): State<AppState>,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> Response {
    let docker = state.net.deploy_kind == crate::net::DeployKind::Docker;
    let answer = Answer {
        flash: query
            .get("done")
            .and_then(|code| Done::parse(code))
            .map(|done| done.flash(docker)),
        ..Answer::default()
    };
    render_backups(&state, StatusCode::OK, answer).await
}

pub async fn create_backup(State(state): State<AppState>) -> Response {
    match perform_backup(&state).await {
        Ok(_) => {
            let schedule = BackupScheduleConfig::load(&state.data_dir).await;
            if let Some(retention) = schedule.retention_count() {
                prune_old_backups(&state.data_dir.join(BACKUP_DIR), retention).await;
            }
            Done::Backup.redirect()
        }
        Err(e) => {
            tracing::error!("manual backup failed: {e}");
            let answer = Answer {
                flash: Some(
                    Flash::refused("backups", "The backup failed")
                        .because("Nothing was saved. The server's log says why."),
                ),
                ..Answer::default()
            };
            render_backups(&state, StatusCode::INTERNAL_SERVER_ERROR, answer).await
        }
    }
}

pub async fn download_backup(
    State(state): State<AppState>,
    Path(filename): Path<String>,
) -> impl IntoResponse {
    if !is_safe_backup_filename(&filename) {
        return (StatusCode::NOT_FOUND, "Not found").into_response();
    }
    match tokio::fs::read(state.data_dir.join(BACKUP_DIR).join(&filename)).await {
        Ok(bytes) => {
            let headers = [
                (header::CONTENT_TYPE, "application/zip".to_string()),
                (
                    header::CONTENT_DISPOSITION,
                    format!("attachment; filename=\"{filename}\""),
                ),
            ];
            (headers, bytes).into_response()
        }
        Err(_) => (StatusCode::NOT_FOUND, "Not found").into_response(),
    }
}

/// Deletes a backup file (behind a `<details>` confirm). One already gone (a second tab) says so.
pub async fn delete_backup(
    State(state): State<AppState>,
    Path(filename): Path<String>,
) -> Response {
    if !is_safe_backup_filename(&filename) {
        return (StatusCode::NOT_FOUND, "Not found").into_response();
    }
    match tokio::fs::remove_file(state.data_dir.join(BACKUP_DIR).join(&filename)).await {
        Ok(()) => Done::Deleted.redirect(),
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => Done::Gone.redirect(),
        Err(err) => {
            tracing::error!(%err, "couldn't delete a backup");
            let answer = Answer::refused("backups", "Couldn't delete the backup.");
            render_backups(&state, StatusCode::INTERNAL_SERVER_ERROR, answer).await
        }
    }
}

#[derive(Deserialize)]
pub struct BackupScheduleForm {
    frequency: String,
    day_of_week: String,
    day_of_month: String,
    backup_time: String,
    retention_count: String,
    external_copy_enabled: Option<String>,
    continuous_mirror_enabled: Option<String>,
}

const VALID_FREQUENCIES: [&str; 3] = ["daily", "weekly", "monthly"];

/// Saves the schedule (one form, §5.6). A bad frequency or a time that isn't HH:MM writes nothing
/// (400, the reason in the group).
pub async fn save_backup_schedule(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    axum::Form(form): axum::Form<BackupScheduleForm>,
) -> Response {
    if !VALID_FREQUENCIES.contains(&form.frequency.as_str()) {
        let answer = Answer::refused("schedule", "Unknown frequency - nothing was saved.");
        return render_backups(&state, StatusCode::BAD_REQUEST, answer).await;
    }
    let day_of_week: i32 = form.day_of_week.trim().parse().unwrap_or(0).clamp(0, 6);
    let day_of_month: i32 = form.day_of_month.trim().parse().unwrap_or(1).clamp(1, 28);
    // Also read by a root script (backup_sync.sh): exactly HH:MM or nothing is written (review H1).
    let backup_time = if form.backup_time.trim().is_empty() {
        "02:00".to_string()
    } else if security::valid_clock_time(form.backup_time.trim()) {
        form.backup_time.trim().to_string()
    } else {
        let answer = Answer::refused(
            "schedule",
            "The time must look like 02:00 - nothing was saved.",
        );
        return render_backups(&state, StatusCode::BAD_REQUEST, answer).await;
    };
    let retention_count: i64 = form
        .retention_count
        .trim()
        .parse()
        .unwrap_or(14)
        .clamp(0, 3650);

    let config = BackupScheduleConfig {
        frequency: form.frequency,
        day_of_week: day_of_week.to_string(),
        day_of_month: day_of_month.to_string(),
        backup_time,
        retention_count: retention_count.to_string(),
        // No external drive in the container (design 22 §6).
        external_copy_enabled: form.external_copy_enabled.is_some()
            && state.net.deploy_kind == crate::net::DeployKind::Systemd,
        continuous_mirror_enabled: form.continuous_mirror_enabled.is_some()
            && state.net.deploy_kind == crate::net::DeployKind::Systemd,
    };

    let answer = if tokio::fs::write(
        state.data_dir.join(SCHEDULE_FILE),
        config.to_file_contents(),
    )
    .await
    .is_ok()
    {
        security::record_security_event(
            &state.db,
            "backup_schedule_changed",
            Some(&admin.username),
            None,
            None,
        )
        .await;
        return Done::Schedule.redirect();
    } else {
        Answer::refused("schedule", "Couldn't save the schedule.")
    };
    render_backups(&state, StatusCode::INTERNAL_SERVER_ERROR, answer).await
}

/// Background task (spawned once at startup, see main.rs) that creates a
/// backup when the configured schedule says it's due, and prunes old
/// backups per the retention setting. Entirely in-process since backup
/// creation only touches data/, which this process already owns - no root
/// component needed, unlike the OS/Tailscale scheduler.
pub async fn run_scheduled_backups(state: AppState) {
    let mut interval = tokio::time::interval(std::time::Duration::from_secs(5 * 60));
    loop {
        interval.tick().await;

        let schedule = BackupScheduleConfig::load(&state.data_dir).await;
        if !is_due(&state.data_dir, &schedule).await {
            continue;
        }

        let today = chrono::Local::now().format("%Y-%m-%d").to_string();
        if tokio::fs::write(state.data_dir.join(LAST_RUN_FILE), &today)
            .await
            .is_err()
        {
            tracing::warn!("couldn't write backup schedule last-run marker, skipping this cycle");
            continue;
        }

        match perform_backup(&state).await {
            Ok(filename) => {
                tracing::info!("scheduled backup created: {filename}");
                if let Some(retention) = schedule.retention_count() {
                    prune_old_backups(&state.data_dir.join(BACKUP_DIR), retention).await;
                }
            }
            Err(e) => tracing::error!("scheduled backup failed: {e}"),
        }
    }
}

async fn is_due(data: &std::path::Path, schedule: &BackupScheduleConfig) -> bool {
    let today = chrono::Local::now().format("%Y-%m-%d").to_string();
    let last_run = tokio::fs::read_to_string(data.join(LAST_RUN_FILE))
        .await
        .unwrap_or_default();
    if last_run.trim() == today {
        return false;
    }

    use chrono::{Datelike, Timelike};
    let now = chrono::Local::now();
    match schedule.frequency.as_str() {
        "weekly" => {
            let configured: u32 = schedule.day_of_week.parse().unwrap_or(0);
            // chrono's weekday: Mon=0..Sun=6; our config uses Sun=0..Sat=6
            let today_dow = now.weekday().num_days_from_sunday();
            if today_dow != configured {
                return false;
            }
        }
        "monthly" => {
            let configured: u32 = schedule.day_of_month.parse().unwrap_or(1);
            if now.day() != configured {
                return false;
            }
        }
        _ => {}
    }

    let Some((hour_str, minute_str)) = schedule.backup_time.split_once(':') else {
        return false;
    };
    let (Ok(target_hour), Ok(target_minute)) = (
        hour_str.trim().parse::<i64>(),
        minute_str.trim().parse::<i64>(),
    ) else {
        return false;
    };
    let target_minutes = target_hour * 60 + target_minute;
    let now_minutes = now.hour() as i64 * 60 + now.minute() as i64;
    let diff = now_minutes - target_minutes;
    (0..5).contains(&diff)
}

/// Background task: keeps `data/live_mirror.db` refreshed as a consistent,
/// up-to-date snapshot of the live database, every couple of minutes - a
/// much tighter recovery point than the named point-in-time backups above.
/// Only does anything if continuous mirroring is actually enabled, since
/// otherwise this would just burn CPU/IO on a Pi for no benefit (a snapshot
/// refreshed on the same SD card the original lives on protects against
/// nothing on its own - the value is entirely in the root scheduler mirroring
/// this file out to external media, see deploy/install.sh's backup_sync.sh).
///
/// Written to a temp path and renamed into place atomically, so the external
/// copy step (a separate process) can never read a half-written file.
pub async fn run_live_mirror(state: AppState) {
    let mut interval = tokio::time::interval(std::time::Duration::from_secs(90));
    loop {
        interval.tick().await;

        let schedule = BackupScheduleConfig::load(&state.data_dir).await;
        if !schedule.continuous_mirror_enabled {
            continue;
        }

        let mirror = state.data_dir.join(LIVE_MIRROR_DB);
        if let Err(e) = snapshot(&state.db, &mirror.with_extension("db.tmp"), &mirror).await {
            tracing::warn!("live mirror snapshot failed: {e}");
        }
    }
}

#[derive(Clone)]
pub struct RemovableDrive {
    device: String,
    size_display: String,
}

/// The block device backing a mount point, with any trailing partition
/// number stripped (e.g. `/dev/mmcblk0p2` -> `/dev/mmcblk0`, `/dev/sda1` ->
/// `/dev/sda`) - used to make sure the format-drive feature can never even
/// list the Pi's own boot/root device as a candidate.
async fn base_device_of(mount_point: &str) -> Option<String> {
    let output = tokio::process::Command::new("findmnt")
        .args(["-no", "SOURCE", mount_point])
        .output()
        .await
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let source = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if source.is_empty() {
        return None;
    }
    let base = source.trim_end_matches(|c: char| c.is_ascii_digit());
    let base = base.strip_suffix('p').unwrap_or(base);
    Some(base.to_string())
}

/// Lists devices the kernel reports as removable, excluding whatever backs
/// the root and boot filesystems by identity (not just by "removable"
/// classification, which some SD card controllers also report as true) -
/// this list is what both the UI and the format-drive confirmation step
/// treat as safe-to-format candidates.
async fn list_removable_drives() -> Vec<RemovableDrive> {
    let mut drives = Vec::new();
    let Ok(mut entries) = tokio::fs::read_dir("/sys/block").await else {
        return drives;
    };

    let root_device = base_device_of("/").await;
    let boot_device = base_device_of("/boot").await;

    while let Ok(Some(entry)) = entries.next_entry().await {
        let name = entry.file_name().to_string_lossy().to_string();
        let removable = tokio::fs::read_to_string(format!("/sys/block/{name}/removable"))
            .await
            .unwrap_or_default();
        if removable.trim() != "1" {
            continue;
        }

        let device = format!("/dev/{name}");
        if Some(&device) == root_device.as_ref() || Some(&device) == boot_device.as_ref() {
            continue;
        }

        let size_sectors: u64 = tokio::fs::read_to_string(format!("/sys/block/{name}/size"))
            .await
            .ok()
            .and_then(|s| s.trim().parse().ok())
            .unwrap_or(0);
        if size_sectors == 0 {
            continue;
        }

        drives.push(RemovableDrive {
            device,
            size_display: human_size(size_sectors * 512),
        });
    }
    drives
}

/// Why a Format request is refused.
#[derive(Debug, PartialEq, Eq)]
enum FormatRefusal {
    /// The drive isn't in the fresh list.
    Gone,
    /// The typed size isn't the drive's.
    WrongSize,
}

impl FormatRefusal {
    /// The page's answer: a wrong size under that drive's open confirm, a gone drive in the group.
    fn answer(self, device: &str) -> Answer {
        match self {
            FormatRefusal::Gone => Answer::refused(
                "drive",
                "That drive isn't connected any more - nothing was formatted.",
            ),
            FormatRefusal::WrongSize => Answer {
                format_refused: Some((
                    device.to_string(),
                    "That isn't the drive's size - nothing was formatted.",
                )),
                ..Answer::default()
            },
        }
    }
}

/// The drive a Format request names, only if it is in the freshly listed `drives` and the typed
/// size is its size exactly.
fn format_target<'a>(
    drives: &'a [RemovableDrive],
    device: &str,
    confirm: &str,
) -> Result<&'a RemovableDrive, FormatRefusal> {
    let drive = drives
        .iter()
        .find(|d| d.device == device)
        .ok_or(FormatRefusal::Gone)?;
    if confirm.trim() != drive.size_display {
        return Err(FormatRefusal::WrongSize);
    }
    Ok(drive)
}

#[derive(Deserialize)]
pub struct FormatDriveForm {
    device: String,
    confirm_size: String,
}

/// Formats a removable drive as ext4 and mounts it at the external backup
/// path. The actual mkfs/mount work happens root-side (see
/// action_format_drive in deploy/install.sh's actions.sh) - this handler's
/// job is just the two checks that make the request safe to forward: the
/// device must be in the *freshly re-fetched* list of detected removable
/// drives (never trust a stale value from an old page load), and the admin
/// must type the drive's exact displayed size, not just click a button -
/// a plain confirm() dialog is bypassable by anyone who can hit this route
/// directly, and formatting is the one action here with no undo.
/// The root side independently re-validates the device again from scratch
/// regardless of what this handler already checked. A wrong size renders the
/// page with that drive's confirm open and the reason under the field.
pub async fn format_drive(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    axum::Form(form): axum::Form<FormatDriveForm>,
) -> Response {
    let drives = list_removable_drives().await;
    let drive = match format_target(&drives, &form.device, &form.confirm_size) {
        Ok(drive) => drive,
        Err(refusal) => {
            let answer = refusal.answer(&form.device);
            return render_backups(&state, StatusCode::BAD_REQUEST, answer).await;
        }
    };

    let flag_content = format!("format_drive {}", drive.device);
    let answer = if tokio::fs::write(state.data_dir.join(FLAG_FILE), &flag_content)
        .await
        .is_ok()
    {
        security::record_security_event(
            &state.db,
            "drive_format_triggered",
            Some(&admin.username),
            None,
            Some(&drive.device),
        )
        .await;
        return Done::Format.redirect();
    } else {
        not_requested("drive")
    };
    render_backups(&state, StatusCode::INTERNAL_SERVER_ERROR, answer).await
}

/// The root helper's request file couldn't be written: nothing happens, and why is behind ⓘ.
fn not_requested(at: &'static str) -> Answer {
    Answer {
        flash: Some(crate::handlers::updates::not_requested(at)),
        ..Answer::default()
    }
}

#[derive(Deserialize)]
pub struct RestoreBackupForm {
    confirm_filename: String,
}

/// Restores a named backup: stops the app, swaps the live database for the
/// backup's contents, and restarts - all root-side (see
/// action_restore_backup in deploy/install.sh's actions.sh), since this
/// process can't safely replace the very database file it has open under
/// its own running SQLite connection. This handler's job is just the two
/// checks that make the request safe to forward: the backup must still be
/// in the *freshly re-listed* set of files on disk (never trust a stale
/// page load), and the admin must type the exact filename to confirm - this
/// discards whatever's in the live database right now, so a plain confirm()
/// dialog (bypassable via a direct POST) isn't enough. The root side takes
/// its own safety copy of the current data before overwriting anything, in
/// case the wrong backup gets picked. A wrong name renders the page with that
/// backup's confirm open and the reason under the field.
pub async fn restore_backup(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(filename): Path<String>,
    axum::Form(form): axum::Form<RestoreBackupForm>,
) -> Response {
    if !is_safe_backup_filename(&filename) {
        return (StatusCode::NOT_FOUND, "Not found").into_response();
    }
    let names = list_backup_filenames(&state.data_dir.join(BACKUP_DIR)).await;
    if !names.contains(&filename) {
        let answer = Answer::refused(
            "backups",
            "That backup isn't there any more - nothing was restored.",
        );
        return render_backups(&state, StatusCode::BAD_REQUEST, answer).await;
    }
    if form.confirm_filename.trim() != filename {
        let answer = Answer {
            restore_refused: Some((
                filename,
                "That isn't the file's name - nothing was restored.",
            )),
            ..Answer::default()
        };
        return render_backups(&state, StatusCode::BAD_REQUEST, answer).await;
    }

    if state.net.deploy_kind == crate::net::DeployKind::Docker {
        return restore_in_container(&state, &admin, &filename).await;
    }

    let flag_content = format!("restore_backup {filename}");
    let answer = if tokio::fs::write(state.data_dir.join(FLAG_FILE), &flag_content)
        .await
        .is_ok()
    {
        security::record_security_event(
            &state.db,
            "backup_restore_triggered",
            Some(&admin.username),
            None,
            Some(&filename),
        )
        .await;
        return Done::Restore.redirect();
    } else {
        not_requested("backups")
    };
    render_backups(&state, StatusCode::INTERNAL_SERVER_ERROR, answer).await
}

/// The restore in the container (design 22 §6): no root helper, so the marker
/// `data/restore_request` names the backup and the process exits non-zero; the restart policy
/// brings it back, and startup applies the restore before opening the database
/// (`startup::apply_restore_request`, which also puts the old database back if the restored one
/// won't open).
async fn restore_in_container(
    state: &AppState,
    admin: &crate::models::AdminUser,
    filename: &str,
) -> Response {
    let marker = state.data_dir.join(crate::startup::RESTORE_REQUEST);
    if let Err(err) = tokio::fs::write(&marker, filename).await {
        tracing::error!(%err, "couldn't write the restore request");
        let answer = Answer::refused(
            "backups",
            "Couldn't start the restore - nothing was restored. The server's log says why.",
        );
        return render_backups(state, StatusCode::INTERNAL_SERVER_ERROR, answer).await;
    }
    security::record_security_event(
        &state.db,
        "backup_restore_triggered",
        Some(&admin.username),
        None,
        Some(filename),
    )
    .await;
    // The 303's GET comes back at once, well inside the second before the exit.
    restart_soon(state.clone());
    Done::Restore.redirect()
}

/// Exits non-zero a moment after the response went out, with the database closed (its WAL
/// checkpointed), so the restart policy starts the container again. Not in tests.
fn restart_soon(state: AppState) {
    tokio::spawn(async move {
        tokio::time::sleep(std::time::Duration::from_secs(1)).await;
        state.command_streams.close_all();
        let _ = tokio::time::timeout(std::time::Duration::from_secs(3), state.db.close()).await;
        tracing::info!("restarting for the restore");
        #[cfg(not(test))]
        std::process::exit(3);
    });
}

/// Accepts an uploaded backup zip - e.g. one downloaded earlier and saved
/// elsewhere, or recovered from the external drive after this Pi's own copy
/// was lost - and adds it to the list above so it can be restored like any
/// other backup. Never trusts the browser-supplied filename or the .zip
/// extension alone: the archive is actually opened and checked for a
/// `kidphone.db` entry before being accepted. A refusal says why in the
/// Upload group (its form open).
pub async fn upload_backup(
    State(state): State<AppState>,
    mut multipart: axum::extract::Multipart,
) -> Response {
    let refused = |why: &'static str| Answer::refused("upload", why);
    let Ok(Some(field)) = multipart.next_field().await else {
        return render_backups(
            &state,
            StatusCode::BAD_REQUEST,
            refused("Choose a file first."),
        )
        .await;
    };
    let Ok(bytes) = field.bytes().await else {
        let answer = refused("The file couldn't be read. Nothing was added.");
        return render_backups(&state, StatusCode::BAD_REQUEST, answer).await;
    };
    if bytes.is_empty() {
        return render_backups(
            &state,
            StatusCode::BAD_REQUEST,
            refused("That file is empty."),
        )
        .await;
    }

    let bytes_for_check = bytes.clone();
    let valid = tokio::task::spawn_blocking(move || {
        let cursor = std::io::Cursor::new(&bytes_for_check[..]);
        zip::ZipArchive::new(cursor)
            .ok()
            .is_some_and(|mut archive| archive.by_name("kidphone.db").is_ok())
    })
    .await
    .unwrap_or(false);

    if !valid {
        let answer = Answer {
            flash: Some(
                Flash::refused("upload", "That isn't a Vibb Phone backup")
                    .because("A backup is a .zip with kidphone.db inside. Nothing was added."),
            ),
            ..Answer::default()
        };
        return render_backups(&state, StatusCode::BAD_REQUEST, answer).await;
    }

    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.subsec_nanos())
        .unwrap_or(0);
    let timestamp = chrono::Local::now().format("%Y%m%d-%H%M%S").to_string();
    let filename = format!("backup-{timestamp}-uploaded-{nanos}.zip");
    let dir = state.data_dir.join(BACKUP_DIR);
    let path = dir.join(&filename);

    let saved = async {
        tokio::fs::create_dir_all(&dir).await?;
        tokio::fs::write(&path, &bytes).await
    }
    .await;
    match saved {
        Ok(()) => Done::Upload.redirect(),
        Err(err) => {
            tracing::error!(%err, "couldn't save an uploaded backup");
            let answer = refused("Couldn't save the file. Nothing was added.");
            render_backups(&state, StatusCode::INTERNAL_SERVER_ERROR, answer).await
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn drive() -> RemovableDrive {
        RemovableDrive {
            device: "/dev/sda".to_string(),
            size_display: "29.8 GB".to_string(),
        }
    }

    /// Format needs the drive in the fresh list and its size typed exactly (qa-31-s14-code L1).
    #[test]
    fn format_needs_a_listed_drive_and_its_size() {
        let drives = [drive()];
        assert!(format_target(&drives, "/dev/sda", " 29.8 GB ").is_ok());
        assert_eq!(
            format_target(&drives, "/dev/sda", "29.8").err(),
            Some(FormatRefusal::WrongSize)
        );
        assert_eq!(
            format_target(&drives, "/dev/sdb", "29.8 GB").err(),
            Some(FormatRefusal::Gone)
        );
        assert_eq!(
            format_target(&[], "/dev/sda", "29.8 GB").err(),
            Some(FormatRefusal::Gone)
        );
        let wrong = FormatRefusal::WrongSize.answer("/dev/sda");
        assert_eq!(wrong.format_refused.unwrap().0, "/dev/sda");
        assert_eq!(
            FormatRefusal::Gone.answer("/dev/sda").flash.unwrap().at,
            "drive"
        );
    }

    /// The page with a backup and a USB drive (which a test machine has neither of): every form
    /// lands on its own group, the deletes and the format are confirms, a refused format opens its
    /// confirm; every [Done] code reads back to itself and shows in a group the page has.
    #[test]
    fn the_page_with_a_backup_and_a_drive() {
        let page = |format_refused: Option<(String, &'static str)>, flash: Option<Flash>| {
            BackupsTemplate {
                title: "Backups".to_string(),
                backups: vec![BackupRow {
                    filename: "backup-20261013-030000.zip".to_string(),
                    size_display: "1.2 MB".to_string(),
                    when: "03:00".to_string(),
                    exact: String::new(),
                }],
                flash,
                restore_refused: None,
                format_refused,
                schedule: BackupScheduleConfig::defaults(),
                weekdays: &WEEKDAYS,
                external_mount: EXTERNAL_MOUNT.to_string(),
                external_drive_mounted: false,
                removable_drives: vec![drive()],
                docker: false,
            }
            .render()
            .unwrap()
        };
        let html = page(None, None);
        crate::tests::settings_pages::check_forms("/backups", &html);
        assert!(html.contains("<summary class=\"row\">Format /dev/sda (29.8 GB)…</summary>"));
        assert!(!html.contains("data-drive=\"/dev/sda\" open"));
        let refused = page(
            Some(("/dev/sda".to_string(), "That isn't the drive's size.")),
            None,
        );
        assert!(refused.contains("data-drive=\"/dev/sda\" open"));
        assert!(refused.contains("id=\"format-error\""));
        for done in Done::ALL {
            assert_eq!(Done::parse(done.code()), Some(done));
            let html = page(None, Some(done.flash(false)));
            assert!(
                html.contains(&format!("id=\"{}\"", done.group())),
                "{done:?}"
            );
            assert!(
                html.contains(&format!("data-flash=\"{}\"", done.group())),
                "{done:?}"
            );
            assert!(!done.flash(true).title.contains("open this page again"));
        }
        assert_eq!(Done::parse("nonsense"), None);
    }
}
