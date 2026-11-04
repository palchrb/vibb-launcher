//! Admin-triggered and scheduled backups: a consistent snapshot of the
//! SQLite database, zipped up and stored under `data/backups/`.
//!
//! Everything here stays in `data/`, which this process owns: the schedule is a
//! background task in this process (`run_scheduled_backups`), and nothing is
//! copied off the machine - the server never acts on its host (the user,
//! 2026-11-04; the root copy to a USB drive and its formatting are gone). A
//! restore is the container's restart (`restore_in_container`); on a systemd
//! install the owner runs it over SSH, and the page shows the command.

use askama::Template;
use axum::Extension;
use axum::extract::{Path, Query, State};
use axum::http::{StatusCode, header};
use axum::response::{Html, IntoResponse, Redirect, Response};
use serde::Deserialize;
use std::io::Write;

use crate::AppState;
use crate::handlers::settings::Flash;
use crate::i18n::{Lang, Words, t};
use crate::security::{self, CurrentAdmin};

/// The backups' folder in `AppState::data_dir` (`data/backups`).
pub(crate) const BACKUP_DIR: &str = "backups";
const SCHEDULE_FILE: &str = "backup_schedule.conf";
const LAST_RUN_FILE: &str = "backup_schedule_last_run";
/// "Backups to keep" when the schedule file doesn't say.
pub(crate) const DEFAULT_RETENTION_COUNT: usize = 14;

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
}

impl BackupScheduleConfig {
    fn defaults() -> Self {
        BackupScheduleConfig {
            frequency: "daily".to_string(),
            day_of_week: "0".to_string(),
            day_of_month: "1".to_string(),
            backup_time: "02:00".to_string(),
            retention_count: DEFAULT_RETENTION_COUNT.to_string(),
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
                _ => {}
            }
        }
        config
    }

    fn to_file_contents(&self) -> String {
        format!(
            "FREQUENCY={}\nDAY_OF_WEEK={}\nDAY_OF_MONTH={}\nBACKUP_TIME={}\nRETENTION_COUNT={}\n",
            self.frequency,
            self.day_of_week,
            self.day_of_month,
            self.backup_time,
            self.retention_count,
        )
    }

    /// How many backups to keep; `None` keeps them all ("0"). A value that isn't 1-4 digits (only
    /// a hand-edited file has one) keeps [DEFAULT_RETENTION_COUNT] and is logged
    /// (qa-pwa-fixes-a-code Q-6).
    fn retention_count(&self) -> Option<usize> {
        retention_of(&self.retention_count)
    }
}

/// [BackupScheduleConfig::retention_count] of the file's `RETENTION_COUNT` value.
pub(crate) fn retention_of(value: &str) -> Option<usize> {
    let value = value.trim();
    let count =
        if !value.is_empty() && value.len() <= 4 && value.bytes().all(|b| b.is_ascii_digit()) {
            value.parse::<usize>().unwrap_or(DEFAULT_RETENTION_COUNT)
        } else {
            tracing::warn!(
                "ignoring an invalid RETENTION_COUNT in {SCHEDULE_FILE} - keeping \
             {DEFAULT_RETENTION_COUNT} backups"
            );
            DEFAULT_RETENTION_COUNT
        };
    (count > 0).then_some(count)
}

/// The schedule form's day choices (Sunday = 0, as the schedule file has them).
/// Each: the value and the key of the day's name.
pub(crate) const WEEKDAYS: [(&str, &str); 7] = [
    ("1", "settings.time_rules.weekday.1"),
    ("2", "settings.time_rules.weekday.2"),
    ("3", "settings.time_rules.weekday.3"),
    ("4", "settings.time_rules.weekday.4"),
    ("5", "settings.time_rules.weekday.5"),
    ("6", "settings.time_rules.weekday.6"),
    ("0", "settings.time_rules.weekday.7"),
];

fn human_size(lang: Lang, bytes: u64) -> String {
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
        format!("{} {}", crate::i18n::number(lang, size, 1), UNITS[unit])
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

/// The database snapshot plus every contact photo (`contact_photos/<hash>.jpg`) and its kept original
/// (`contact_photo_originals/<hash>.jpg`, migration 0123), wallpaper image
/// (`wallpapers/<hash>.jpg`), music cover (`music_covers/<hash>.jpg`) and phone picture
/// (`device_avatars/<hash>.jpg`, server round C). Never the parent's own
/// music files (design 21: too big; a restore lists them as missing), nor episode art (design 27:
/// `photos::MUSIC_ART` is fetched again by the sweep). A restore swaps in only the
/// database (deploy/install.sh); the server puts back missing files from the backups at startup
/// (`photos::recover_missing`).
pub(crate) fn build_backup_zip(
    db_snapshot_path: &str,
    zip_path: &str,
    photo_dir: &std::path::Path,
    photo_original_dir: &std::path::Path,
    wallpaper_dir: &std::path::Path,
    music_cover_dir: &std::path::Path,
    avatar_dir: &std::path::Path,
) -> std::io::Result<()> {
    let file = std::fs::File::create(zip_path)?;
    let mut writer = zip::ZipWriter::new(file);
    let options = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated);

    writer.start_file("kidphone.db", options)?;
    writer.write_all(&std::fs::read(db_snapshot_path)?)?;
    crate::photos::add_to_zip(&mut writer, photo_dir)?;
    crate::photos::CONTACT_ORIGINALS.add_to_zip(&mut writer, photo_original_dir)?;
    crate::photos::WALLPAPERS.add_to_zip(&mut writer, wallpaper_dir)?;
    crate::photos::MUSIC_COVERS.add_to_zip(&mut writer, music_cover_dir)?;
    crate::photos::DEVICE_AVATARS.add_to_zip(&mut writer, avatar_dir)?;

    writer.finish()?;
    Ok(())
}

/// A consistent copy of the live database (`VACUUM INTO`) at `target`, with no admin sessions in
/// it (security review M2): a backup zip and a pre-migration copy must never sign
/// anyone in - nor carry the phones' Tailscale key ([strip_secrets_in]). The copy is made at `scratch` (in `data/`, never
/// in `data/backups/`, where a parent downloads from), the sessions are deleted with `secure_delete` (their bytes zeroed, not left in free pages),
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
/// (`tower_sessions`), the browsers' Web Push subscriptions and the Tailscale auth key for the phones (QA of the security wave, a note
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
        // The browsers' push secrets (design 35 §7); a restore couldn't use them anyway (the
        // VAPID key isn't backed up). Deliveries first.
        ("push_deliveries", "DELETE FROM push_deliveries"),
        ("push_subscriptions", "DELETE FROM push_subscriptions"),
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
    // In data/, not data/backups/: only finished backups are ever listed there.
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
    let photo_original_dir = state.photo_original_dir.as_path().to_path_buf();
    let wallpaper_dir = state.wallpaper_dir.as_path().to_path_buf();
    let music_cover_dir = state.music_cover_dir.as_path().to_path_buf();
    let avatar_dir = state.avatar_dir.as_path().to_path_buf();
    let zip_result = tokio::task::spawn_blocking(move || {
        build_backup_zip(
            &snapshot_path_for_zip,
            &zip_path_for_zip,
            &photo_dir,
            &photo_original_dir,
            &wallpaper_dir,
            &music_cover_dir,
            &avatar_dir,
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
    lang: Lang,
    title: String,
    backups: Vec<BackupRow>,
    /// The last form's result, in its group ("backups", "upload", "schedule").
    flash: Option<Flash>,
    /// A refused restore: the file whose confirm renders open, and the key of why.
    restore_refused: Option<(String, &'static str)>,
    schedule: BackupScheduleConfig,
    weekdays: &'static [(&'static str, &'static str)],
    /// `DEPLOY_KIND=docker` (design 22 §6): Restore… restarts the container. On a systemd install
    /// it shows the command the owner runs over SSH ([BackupsTemplate::restore_command]).
    docker: bool,
}
crate::i18n::words!(BackupsTemplate);

impl BackupsTemplate {
    /// Why a restore of `file` was refused, if it was (its confirm then renders open).
    fn restore_error(&self, file: &str) -> Option<&'static str> {
        self.restore_refused
            .as_ref()
            .filter(|(refused, _)| refused == file)
            .map(|(_, why)| *why)
    }

    /// On a systemd install: the command that restores `file` (the same restore as the
    /// container's, `startup::apply_restore_request`, at the service's next start; DEPLOY.md), or
    /// `None` for a name a shell would read - the owner pastes it into a root-capable shell
    /// (security QA M1): the page then says to rename the file.
    fn restore_command(&self, file: &str) -> Option<String> {
        restore_command(self.lang, file)
    }
}

/// [BackupsTemplate::restore_command]: only for a [shell_plain_backup_name], and quoted anyway.
pub(crate) fn restore_command(lang: Lang, file: &str) -> Option<String> {
    shell_plain_backup_name(file).then(|| {
        crate::i18n::t_params(
            lang,
            "settings.backups.restore_command",
            &[("file", &shell_quote(file))],
        )
    })
}

/// `^backup-[0-9A-Za-z._-]+\.zip$`: nothing a shell expands, splits or substitutes.
pub(crate) fn shell_plain_backup_name(name: &str) -> bool {
    name.strip_prefix("backup-")
        .and_then(|rest| rest.strip_suffix(".zip"))
        .is_some_and(|middle| {
            !middle.is_empty()
                && middle
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'-'))
        })
}

/// `value` in single quotes, each `'` in it as `'\''`: one word to a POSIX shell, never expanded.
pub(crate) fn shell_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\\''"))
}

/// What a form on Backups sent back, besides the page.
#[derive(Default)]
struct Answer {
    flash: Option<Flash>,
    restore_refused: Option<(String, &'static str)>,
}

/// What a form that worked did (qa-31-s14-code M1): the POST redirects to
/// `/backups?done=<code>#<group>` and the GET shows its line in that group, so a reload repeats
/// nothing (no second restore or backup). Refusals still render in the POST's answer: they
/// changed nothing, and a typed confirm never goes into a URL.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Done {
    Backup,
    Deleted,
    Gone,
    Schedule,
    Upload,
    Restore,
}

impl Done {
    const ALL: [Done; 6] = [
        Done::Backup,
        Done::Deleted,
        Done::Gone,
        Done::Schedule,
        Done::Upload,
        Done::Restore,
    ];

    fn code(self) -> &'static str {
        match self {
            Done::Backup => "backup",
            Done::Deleted => "deleted",
            Done::Gone => "gone",
            Done::Schedule => "schedule",
            Done::Upload => "upload",
            Done::Restore => "restore",
        }
    }

    fn group(self) -> &'static str {
        match self {
            Done::Schedule => "schedule",
            Done::Upload => "upload",
            _ => "backups",
        }
    }

    fn parse(code: &str) -> Option<Done> {
        Done::ALL.into_iter().find(|done| done.code() == code)
    }

    /// The line in its group. A restore's result is shown on the page the server answers after
    /// it is back, so it says what happened (only the container restores from the page).
    fn flash(self, lang: Lang) -> Flash {
        let key = match self {
            Done::Backup => "settings.backups.done.backup",
            Done::Deleted => "settings.backups.done.deleted",
            Done::Gone => "settings.backups.done.gone",
            Done::Schedule => "settings.schedule.saved",
            Done::Upload => "settings.backups.done.upload",
            Done::Restore => "settings.backups.done.restore",
        };
        Flash::done(self.group(), t(lang, key))
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
async fn render_backups(
    state: &AppState,
    status: StatusCode,
    answer: Answer,
    lang: Lang,
) -> Response {
    let docker = state.net.deploy_kind == crate::net::DeployKind::Docker;
    let zone = crate::display_time::Zone::load(&state.db, lang).await;
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
                size_display: human_size(lang, meta.len()),
                when: made.map_or_else(
                    || t(lang, "settings.backups.time_unknown").to_string(),
                    |made| zone.when(made, now),
                ),
                exact: made.map(|t| zone.exact(t)).unwrap_or_default(),
            });
        }
    }
    // The file names start with their time: newest first.
    backups.sort_by(|a, b| b.filename.cmp(&a.filename));

    let page = BackupsTemplate {
        lang,
        title: t(lang, "settings.backups.title").to_string(),
        backups,
        flash: answer.flash,
        restore_refused: answer.restore_refused,
        schedule: BackupScheduleConfig::load(&state.data_dir).await,
        weekdays: &WEEKDAYS,
        docker,
    };
    (status, Html(page.render().unwrap())).into_response()
}

/// The page; `?done=<code>` (a form that worked, [Done]) shows its line in its group.
pub async fn list_backups(
    State(state): State<AppState>,
    lang: Lang,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> Response {
    let answer = Answer {
        flash: query
            .get("done")
            .and_then(|code| Done::parse(code))
            .map(|done| done.flash(lang)),
        ..Answer::default()
    };
    render_backups(&state, StatusCode::OK, answer, lang).await
}

pub async fn create_backup(lang: Lang, State(state): State<AppState>) -> Response {
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
                    Flash::refused("backups", t(lang, "settings.backups.failed"))
                        .because(t(lang, "settings.backups.failed_why")),
                ),
                ..Answer::default()
            };
            render_backups(&state, StatusCode::INTERNAL_SERVER_ERROR, answer, lang).await
        }
    }
}

pub async fn download_backup(
    State(state): State<AppState>,
    lang: Lang,
    Path(filename): Path<String>,
) -> impl IntoResponse {
    if !is_safe_backup_filename(&filename) {
        return crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.backup_not_found");
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
        Err(_) => crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.backup_not_found"),
    }
}

/// Deletes a backup file (behind a `<details>` confirm). One already gone (a second tab) says so.
pub async fn delete_backup(
    State(state): State<AppState>,
    lang: Lang,
    Path(filename): Path<String>,
) -> Response {
    if !is_safe_backup_filename(&filename) {
        return crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.backup_not_found");
    }
    match tokio::fs::remove_file(state.data_dir.join(BACKUP_DIR).join(&filename)).await {
        Ok(()) => Done::Deleted.redirect(),
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => Done::Gone.redirect(),
        Err(err) => {
            tracing::error!(%err, "couldn't delete a backup");
            let answer = Answer::refused("backups", t(lang, "settings.backups.delete_failed"));
            render_backups(&state, StatusCode::INTERNAL_SERVER_ERROR, answer, lang).await
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
}

const VALID_FREQUENCIES: [&str; 3] = ["daily", "weekly", "monthly"];

/// Saves the schedule (one form, §5.6). A bad frequency or a time that isn't HH:MM writes nothing
/// (400, the reason in the group).
pub async fn save_backup_schedule(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    axum::Form(form): axum::Form<BackupScheduleForm>,
) -> Response {
    if !VALID_FREQUENCIES.contains(&form.frequency.as_str()) {
        let answer = Answer::refused("schedule", t(lang, "settings.schedule.bad_frequency"));
        return render_backups(&state, StatusCode::BAD_REQUEST, answer, lang).await;
    }
    let day_of_week: i32 = form.day_of_week.trim().parse().unwrap_or(0).clamp(0, 6);
    let day_of_month: i32 = form.day_of_month.trim().parse().unwrap_or(1).clamp(1, 28);
    // Exactly HH:MM or nothing is written (review H1).
    let backup_time = if form.backup_time.trim().is_empty() {
        "02:00".to_string()
    } else if security::valid_clock_time(form.backup_time.trim()) {
        form.backup_time.trim().to_string()
    } else {
        let answer = Answer::refused(
            "schedule",
            crate::i18n::t_params(lang, "settings.schedule.bad_time", &[("example", "02:00")]),
        );
        return render_backups(&state, StatusCode::BAD_REQUEST, answer, lang).await;
    };
    let retention_count: i64 = form
        .retention_count
        .trim()
        .parse()
        .unwrap_or(DEFAULT_RETENTION_COUNT as i64)
        .clamp(0, 3650);

    let config = BackupScheduleConfig {
        frequency: form.frequency,
        day_of_week: day_of_week.to_string(),
        day_of_month: day_of_month.to_string(),
        backup_time,
        retention_count: retention_count.to_string(),
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
        Answer::refused("schedule", t(lang, "settings.schedule.save_failed"))
    };
    render_backups(&state, StatusCode::INTERNAL_SERVER_ERROR, answer, lang).await
}

/// Background task (spawned once at startup, see main.rs) that creates a
/// backup when the configured schedule says it's due, and prunes old
/// backups per the retention setting. Entirely in-process: backup creation
/// only touches data/, which this process already owns.
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

#[derive(Deserialize)]
pub struct RestoreBackupForm {
    confirm_filename: String,
}

/// Restores a named backup in the container: the backup must still be in the *freshly
/// re-listed* set of files on disk (never trust a stale page load), and the admin must type the
/// exact filename to confirm - this discards whatever's in the live database right now, so a
/// plain confirm() dialog (bypassable via a direct POST) isn't enough. A wrong name renders the
/// page with that backup's confirm open and the reason under the field. On a systemd install the
/// server has no way to restart itself and asks nothing of its host (the user, 2026-11-04): the
/// page shows the command, and a POST here restores nothing.
pub async fn restore_backup(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(filename): Path<String>,
    axum::Form(form): axum::Form<RestoreBackupForm>,
) -> Response {
    if !is_safe_backup_filename(&filename) {
        return crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.backup_not_found");
    }
    if state.net.deploy_kind != crate::net::DeployKind::Docker {
        let answer = Answer::refused("backups", t(lang, "settings.backups.restore_over_ssh"));
        return render_backups(&state, StatusCode::BAD_REQUEST, answer, lang).await;
    }
    let names = list_backup_filenames(&state.data_dir.join(BACKUP_DIR)).await;
    if !names.contains(&filename) {
        let answer = Answer::refused("backups", t(lang, "settings.backups.restore_gone"));
        return render_backups(&state, StatusCode::BAD_REQUEST, answer, lang).await;
    }
    if form.confirm_filename.trim() != filename {
        let answer = Answer {
            restore_refused: Some((filename, "settings.backups.wrong_name")),
            ..Answer::default()
        };
        return render_backups(&state, StatusCode::BAD_REQUEST, answer, lang).await;
    }
    restore_in_container(&state, &admin, &filename, lang).await
}

/// The restore in the container (design 22 §6): the marker `data/restore_request` names the
/// backup and the process exits non-zero; the restart policy
/// brings it back, and startup applies the restore before opening the database
/// (`startup::apply_restore_request`, which also puts the old database back if the restored one
/// won't open).
async fn restore_in_container(
    state: &AppState,
    admin: &crate::models::AdminUser,
    filename: &str,
    lang: Lang,
) -> Response {
    let marker = state.data_dir.join(crate::startup::RESTORE_REQUEST);
    if let Err(err) = tokio::fs::write(&marker, filename).await {
        tracing::error!(%err, "couldn't write the restore request");
        let answer = Answer::refused("backups", t(lang, "settings.backups.restore_failed"));
        return render_backups(state, StatusCode::INTERNAL_SERVER_ERROR, answer, lang).await;
    }
    security::record_security_event(
        &state.db,
        "backup_restore_triggered",
        Some(&admin.username),
        None,
        Some(security::Detail::facts(filename)),
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
/// elsewhere after this server's own copy was lost - and adds it to the list above so it can be restored like any
/// other backup. Never trusts the browser-supplied filename or the .zip
/// extension alone: the archive is actually opened and checked for a
/// `kidphone.db` entry before being accepted. A refusal says why in the
/// Upload group (its form open).
pub async fn upload_backup(
    State(state): State<AppState>,
    lang: Lang,
    mut multipart: axum::extract::Multipart,
) -> Response {
    let refused = |key: &'static str| Answer::refused("upload", t(lang, key));
    let Ok(Some(field)) = multipart.next_field().await else {
        return render_backups(
            &state,
            StatusCode::BAD_REQUEST,
            refused("settings.backups.upload_none"),
            lang,
        )
        .await;
    };
    let Ok(bytes) = field.bytes().await else {
        let answer = refused("settings.backups.upload_unreadable");
        return render_backups(&state, StatusCode::BAD_REQUEST, answer, lang).await;
    };
    if bytes.is_empty() {
        return render_backups(
            &state,
            StatusCode::BAD_REQUEST,
            refused("settings.backups.upload_empty"),
            lang,
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
                Flash::refused("upload", t(lang, "settings.backups.not_a_backup"))
                    .because(t(lang, "settings.backups.not_a_backup_why")),
            ),
            ..Answer::default()
        };
        return render_backups(&state, StatusCode::BAD_REQUEST, answer, lang).await;
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
            let answer = refused("settings.backups.upload_failed");
            render_backups(&state, StatusCode::INTERNAL_SERVER_ERROR, answer, lang).await
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The page with a backup (which a test machine has none of): every form lands on its own
    /// group, the delete is a confirm; in the container the restore is a typed confirm, on a
    /// systemd install its command for that file; every [Done] code reads back to itself and shows
    /// in a group the page has.
    #[test]
    fn the_page_with_a_backup() {
        let page = |docker: bool, flash: Option<Flash>| {
            BackupsTemplate {
                lang: Lang::En,
                title: "Backups".to_string(),
                backups: vec![BackupRow {
                    filename: "backup-20261013-030000.zip".to_string(),
                    size_display: "1.2 MB".to_string(),
                    when: "03:00".to_string(),
                    exact: String::new(),
                }],
                flash,
                restore_refused: None,
                schedule: BackupScheduleConfig::defaults(),
                weekdays: &WEEKDAYS,
                docker,
            }
            .render()
            .unwrap()
        };
        assert_eq!(human_size(Lang::Nb, 1_258_291), "1,2 MB");
        assert_eq!(human_size(Lang::En, 1_258_291), "1.2 MB");
        for docker in [false, true] {
            let html = page(docker, None);
            crate::tests::settings_pages::check_forms("/backups", &html);
            for gone in ["drive", "format", "mirror", "external"] {
                assert!(!html.to_lowercase().contains(gone), "{gone}: {html}");
            }
            let restore = "action=\"/backups/backup-20261013-030000.zip/restore#backups\"";
            let command = "echo &#39;backup-20261013-030000.zip&#39; | sudo -u kidphone tee \
                           /opt/kid-phone-server/data/restore_request";
            assert_eq!(html.contains(restore), docker, "docker: {docker}");
            assert_eq!(html.contains(command), !docker, "docker: {docker}");
        }
        for done in Done::ALL {
            assert_eq!(Done::parse(done.code()), Some(done));
            let html = page(true, Some(done.flash(Lang::En)));
            assert!(
                html.contains(&format!("id=\"{}\"", done.group())),
                "{done:?}"
            );
            assert!(
                html.contains(&format!("data-flash=\"{}\"", done.group())),
                "{done:?}"
            );
            assert!(!done.flash(Lang::En).title.contains("open this page again"));
        }
        assert_eq!(Done::parse("nonsense"), None);
        assert_eq!(Done::parse("format"), None, "an old bookmark shows nothing");
    }

    /// Security QA M1: the systemd restore command is pasted into a root-capable shell, so it is
    /// shown only for a plain `backup-*.zip` name, and the name is single-quoted anyway; any other
    /// name (a browser's "(1)", a space, `$(...)`, a backtick, a glob, a quote) shows the rename note.
    #[test]
    fn a_restore_command_never_hands_a_shell_a_hostile_name() {
        let plain = [
            "backup-20261104-020000.zip",
            "backup-20261104-020000-uploaded-12.zip",
            "backup-a_b.c.zip",
        ];
        let hostile = [
            "backup-20261104-020000 (1).zip",
            "backup-two words.zip",
            "backup-$(touch pwned).zip",
            "backup-`touch pwned`.zip",
            "backup-*.zip",
            "backup-it's.zip",
            "backup-x;touch pwned.zip",
            "backup-.zip",
            "backup-x.zip.txt",
        ];
        for name in plain {
            assert!(shell_plain_backup_name(name), "{name}");
            let command = restore_command(Lang::En, name).unwrap();
            assert!(
                command.starts_with(&format!("echo '{name}' | sudo -u kidphone tee ")),
                "{command}"
            );
        }
        for name in hostile {
            assert!(!shell_plain_backup_name(name), "{name}");
            assert_eq!(restore_command(Lang::En, name), None, "{name}");
        }
        // The quoting alone already makes each one a single word that bash prints back unchanged.
        let sandbox = tempfile::tempdir().unwrap();
        for name in plain.iter().chain(&hostile) {
            let output = std::process::Command::new("bash")
                .arg("-c")
                .arg(format!("printf %s {}", shell_quote(name)))
                .current_dir(sandbox.path())
                .output()
                .unwrap();
            assert_eq!(String::from_utf8(output.stdout).unwrap(), *name);
        }
        assert!(!sandbox.path().join("pwned").exists());
        // The page: the command for a plain name, the rename note for the others.
        let html = BackupsTemplate {
            lang: Lang::En,
            title: "Backups".to_string(),
            backups: [plain[0], hostile[0], hostile[2]]
                .iter()
                .map(|name| BackupRow {
                    filename: name.to_string(),
                    size_display: "1 KB".to_string(),
                    when: "now".to_string(),
                    exact: String::new(),
                })
                .collect(),
            flash: None,
            restore_refused: None,
            schedule: BackupScheduleConfig::defaults(),
            weekdays: &WEEKDAYS,
            docker: false,
        }
        .render()
        .unwrap();
        assert_eq!(html.matches("<pre><code>echo ").count(), 1, "{html}");
        assert_eq!(html.matches("data-restore=\"rename\"").count(), 2, "{html}");
        assert!(!html.contains("touch pwned).zip | sudo"));
    }
}
