//! Watches arbitrary GitHub repos' Releases for a new APK and pushes it to
//! devices - or, for apps with no public release feed (or where an admin
//! wants full manual control, including the launcher's own updates), lets
//! the admin upload an APK directly. Both source types feed the same
//! `latest_release_tag`/`latest_release_file_path` fields and the same
//! device-facing API (`handlers::device_api::tracked_app_updates`), so nothing
//! downstream of "there's a cached release ready to serve" needs to know or
//! care which source produced it.

use askama::Template;
use axum::body::Bytes;
use axum::extract::{Multipart, Path, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Redirect, Response};
use axum::{Extension, Form};
use regex::Regex;
use serde::Deserialize;
use std::collections::HashMap;
use std::time::Duration;
use tokio::io::AsyncWriteExt;

use crate::AppState;
use crate::config::SERVER_RELEASE_TAG_PREFIX;
use crate::models::TrackedApp;
use crate::security::{CurrentAdmin, generate_device_token};

const TRACKED_APPS_DIR: &str = "data/tracked_apps";

/// The largest asset a sync downloads: one GitHub lists as bigger is refused before downloading,
/// one without a listed size is stopped once it gets there.
const MAX_ASSET_BYTES: u64 = 1_000_000_000;
/// A download that delivers no data for this long fails...
const DOWNLOAD_STALL_TIMEOUT: Duration = Duration::from_secs(60);
/// ...and so does one still running after this, however steadily it trickles.
const DOWNLOAD_TOTAL_TIMEOUT: Duration = Duration::from_secs(30 * 60);
/// A `.part` file this old is left over from a crash (a live download writes at least every
/// [DOWNLOAD_STALL_TIMEOUT] and ends after [DOWNLOAD_TOTAL_TIMEOUT]) - removed after the next sync.
const STALE_PART_AGE: Duration = Duration::from_secs(2 * 60 * 60);

#[derive(Deserialize)]
struct GithubAsset {
    id: i64,
    name: String,
    browser_download_url: String,
    /// Bytes, as GitHub reports it; 0 if missing.
    #[serde(default)]
    size: u64,
}

/// An APK is a ZIP file: anything else (an HTML error page, a truncated download) must never be
/// cached and offered to the phones. `head` is the download's first bytes (at least 4 when there
/// were that many), `len` its length, `expected_size` GitHub's asset size (0 = unknown).
fn check_apk_download(head: &[u8], len: u64, expected_size: u64) -> Result<(), String> {
    if !head.starts_with(b"PK\x03\x04") {
        return Err("downloaded asset is not an APK (no ZIP header)".to_string());
    }
    if expected_size != 0 && len != expected_size {
        return Err(format!(
            "downloaded asset has {len} bytes, GitHub lists {expected_size}"
        ));
    }
    Ok(())
}

/// Which asset of a release is the app (`tracked_apps.asset_pattern`): no pattern = the first
/// `.apk`; a pattern starting with `^` is a regular expression on the asset name (e.g.
/// `^\d+\.apk$` for Element X's universal APK, named after its versionCode); anything else is
/// text the name must contain.
enum AssetFilter {
    FirstApk,
    Contains(String),
    Regex(Regex),
}

impl AssetFilter {
    /// `Err` (a short reason) for a `^` pattern that isn't a valid regular expression.
    fn parse(pattern: Option<&str>) -> Result<Self, String> {
        match pattern {
            None | Some("") => Ok(Self::FirstApk),
            Some(p) if p.starts_with('^') => Regex::new(p).map(Self::Regex).map_err(|e| {
                // The regex crate's message spans several lines (the pattern, a caret under the
                // spot, "error: ..."); the last one is the reason.
                let text = e.to_string();
                let reason = text.lines().last().unwrap_or_default().trim();
                let reason = reason.strip_prefix("error: ").unwrap_or(reason);
                format!("not a valid regular expression ({reason})")
            }),
            Some(p) => Ok(Self::Contains(p.to_string())),
        }
    }

    fn matches(&self, asset_name: &str) -> bool {
        match self {
            Self::FirstApk => asset_name.ends_with(".apk"),
            Self::Contains(text) => asset_name.contains(text.as_str()),
            Self::Regex(regex) => regex.is_match(asset_name),
        }
    }
}

/// The add and edit forms' check: a pattern starting with `^` must compile. The message says
/// nothing was saved, since both forms refuse the whole save.
fn validate_asset_pattern(pattern: Option<&str>) -> Result<(), String> {
    AssetFilter::parse(pattern).map(|_| ()).map_err(|reason| {
        format!(
            "The asset filename filter starts with ^, so it is read as a regular expression, and \
             it is {reason}. Nothing was saved."
        )
    })
}

#[derive(Deserialize)]
struct GithubRelease {
    tag_name: String,
    #[serde(default)]
    prerelease: bool,
    #[serde(default)]
    draft: bool,
    assets: Vec<GithubAsset>,
}

/// Hits the Releases *list* endpoint rather than `/releases/latest` - the
/// latter only ever returns the newest non-prerelease, non-draft release,
/// which would never find anything for a repo that only ever publishes to a
/// rolling prerelease tag. The list is already newest-first, so after
/// filtering the first match is the one we want - same approach Obtainium
/// uses for this exact problem.
///
/// A release only matches if it actually carries a matching asset, and a
/// `server-v*` release never does: this project's own repo
/// (`palchrb/vibb-launcher`) is a monorepo that publishes the server's
/// releases next to the launcher's `launcher-v*` ones, so the launcher's
/// catalog row must skip past a newer server release to the newest launcher.
async fn fetch_latest_release(
    github_repo: &str,
    include_prereleases: bool,
    asset_pattern: Option<&str>,
) -> Result<(GithubRelease, GithubAsset), String> {
    let filter = AssetFilter::parse(asset_pattern)
        .map_err(|reason| format!("the asset filename filter is {reason}"))?;
    let client = reqwest::Client::builder()
        .user_agent("kid-phone-server (self-hosted, github.com)")
        .timeout(Duration::from_secs(15))
        .build()
        .map_err(|e| e.to_string())?;

    let url = format!("https://api.github.com/repos/{github_repo}/releases?per_page=100");
    let response = client.get(&url).send().await.map_err(|e| e.to_string())?;
    if !response.status().is_success() {
        return Err(format!("GitHub API returned {}", response.status()));
    }
    let releases: Vec<GithubRelease> = response.json().await.map_err(|e| e.to_string())?;
    newest_matching_release(releases, include_prereleases, &filter)
}

fn newest_matching_release(
    releases: Vec<GithubRelease>,
    include_prereleases: bool,
    filter: &AssetFilter,
) -> Result<(GithubRelease, GithubAsset), String> {
    releases
        .into_iter()
        .filter(|r| !r.draft && (include_prereleases || !r.prerelease))
        .filter(|r| !r.tag_name.starts_with(SERVER_RELEASE_TAG_PREFIX))
        .find_map(|mut r| {
            let index = r.assets.iter().position(|a| filter.matches(&a.name))?;
            let asset = r.assets.swap_remove(index);
            Some((r, asset))
        })
        .ok_or_else(|| "no release with a matching asset found".to_string())
}

/// One chunk of a download at a time, `None` at the end: the GitHub response, or canned chunks
/// in the tests.
trait ChunkSource {
    async fn next_chunk(&mut self) -> Result<Option<Bytes>, String>;
}

impl ChunkSource for reqwest::Response {
    async fn next_chunk(&mut self) -> Result<Option<Bytes>, String> {
        self.chunk().await.map_err(|e| e.to_string())
    }
}

/// A download's temp file, deleted when this is dropped unless [TempDownload::keep] ran - so an
/// error return, a timeout and a future dropped half-way (a "Check now" whose request went away,
/// a shutdown) all remove it.
struct TempDownload {
    path: String,
    keep: bool,
}

impl TempDownload {
    fn keep(mut self) {
        self.keep = true;
    }
}

impl Drop for TempDownload {
    fn drop(&mut self) {
        if !self.keep {
            let _ = std::fs::remove_file(&self.path);
        }
    }
}

/// Streams an asset to `final_path` through a temp file next to it (`<final_path>.<random>.part`),
/// renamed into place only once it is a complete APK: ZIP header, exactly `expected_size` bytes
/// when GitHub lists one, never over [MAX_ASSET_BYTES]. A chunk that takes longer than `stall`
/// fails the download. Nothing is held in memory beyond one chunk. Returns the bytes written.
async fn stream_to_file(
    source: &mut impl ChunkSource,
    final_path: &str,
    expected_size: u64,
    stall: Duration,
) -> Result<u64, String> {
    let temp = TempDownload {
        path: format!("{final_path}.{}.part", random_label()),
        keep: false,
    };
    let mut file = tokio::fs::File::create(&temp.path)
        .await
        .map_err(|e| format!("can't create {}: {e}", temp.path))?;
    let mut head: Vec<u8> = Vec::with_capacity(4);
    let mut written: u64 = 0;
    loop {
        let chunk = tokio::time::timeout(stall, source.next_chunk())
            .await
            .map_err(|_| format!("download stalled: no data for {} s", stall.as_secs()))??;
        let Some(chunk) = chunk else { break };
        written += chunk.len() as u64;
        if written > MAX_ASSET_BYTES {
            return Err(format!(
                "download stopped at {} MB, over the {} limit",
                written / 1_000_000,
                size_limit_text()
            ));
        }
        if expected_size != 0 && written > expected_size {
            return Err(format!(
                "download is longer than the {expected_size} bytes GitHub lists"
            ));
        }
        if head.len() < 4 {
            let take = (4 - head.len()).min(chunk.len());
            head.extend_from_slice(&chunk[..take]);
            if head.len() == 4 {
                check_apk_download(&head, written, 0)?;
            }
        }
        file.write_all(&chunk)
            .await
            .map_err(|e| format!("can't write {}: {e}", temp.path))?;
    }
    check_apk_download(&head, written, expected_size)?;
    file.flush().await.map_err(|e| e.to_string())?;
    file.sync_all().await.map_err(|e| e.to_string())?;
    drop(file);
    tokio::fs::rename(&temp.path, final_path)
        .await
        .map_err(|e| format!("can't move the download into place: {e}"))?;
    temp.keep();
    Ok(written)
}

/// "1 GB", for the messages about [MAX_ASSET_BYTES].
fn size_limit_text() -> String {
    format!("{} GB", MAX_ASSET_BYTES / 1_000_000_000)
}

/// Downloads `asset` to `final_path` (see [stream_to_file]): refused up front when GitHub lists it
/// over [MAX_ASSET_BYTES]; fails when the server sends no data for [DOWNLOAD_STALL_TIMEOUT]. The
/// overall cap ([DOWNLOAD_TOTAL_TIMEOUT]) is the caller's.
async fn download_asset(asset: &GithubAsset, final_path: &str) -> Result<u64, String> {
    if asset.size > MAX_ASSET_BYTES {
        return Err(format!(
            "{} is {} MB, over the {} limit - not downloaded",
            asset.name,
            asset.size / 1_000_000,
            size_limit_text()
        ));
    }
    let client = reqwest::Client::builder()
        .user_agent("kid-phone-server (self-hosted, github.com)")
        .connect_timeout(Duration::from_secs(15))
        .build()
        .map_err(|e| e.to_string())?;
    let mut response = tokio::time::timeout(
        DOWNLOAD_STALL_TIMEOUT,
        client.get(&asset.browser_download_url).send(),
    )
    .await
    .map_err(|_| {
        format!(
            "no answer from the download server for {} s",
            DOWNLOAD_STALL_TIMEOUT.as_secs()
        )
    })?
    .map_err(|e| e.to_string())?
    .error_for_status()
    .map_err(|e| e.to_string())?;
    if let Some(length) = response.content_length()
        && length > MAX_ASSET_BYTES
    {
        return Err(format!(
            "{} is {} MB, over the {} limit - not downloaded",
            asset.name,
            length / 1_000_000,
            size_limit_text()
        ));
    }
    stream_to_file(
        &mut response,
        final_path,
        asset.size,
        DOWNLOAD_STALL_TIMEOUT,
    )
    .await
}

/// Removes `.part` files in `dir` not written for [STALE_PART_AGE]: left over from a crash or a
/// power cut mid-download, which [TempDownload] can't clean up. Best-effort.
async fn remove_stale_parts(dir: &str) {
    let Ok(mut entries) = tokio::fs::read_dir(dir).await else {
        return;
    };
    while let Ok(Some(entry)) = entries.next_entry().await {
        if !entry.file_name().to_string_lossy().ends_with(".part") {
            continue;
        }
        let stale = entry
            .metadata()
            .await
            .ok()
            .and_then(|m| m.modified().ok())
            .and_then(|t| t.elapsed().ok())
            .is_some_and(|age| age > STALE_PART_AGE);
        if stale {
            tokio::fs::remove_file(entry.path()).await.ok();
        }
    }
}

/// Checks one GitHub-sourced app's repo for a new release and, if the
/// release+asset identity differs from what's cached, downloads the
/// matching asset and replaces the previously-cached file. A no-op for
/// manual-source apps - those only ever change via `upload_tracked_app_release`.
/// Used by both the scheduled loop and the admin's manual "Check now"
/// button, so they can never drift apart.
async fn sync_one_app(state: &AppState, app: &TrackedApp) -> Result<(), String> {
    if app.source_type != "github" {
        return Ok(());
    }

    let (release, asset) = fetch_latest_release(
        &app.github_repo,
        app.include_prereleases,
        app.asset_pattern.as_deref(),
    )
    .await?;

    sqlx::query("UPDATE tracked_apps SET last_checked_at = datetime('now') WHERE id = ?")
        .bind(app.id)
        .execute(&state.db)
        .await
        .ok();

    // Compared as a (tag, asset id) pair, not just the tag - a rolling tag
    // (e.g. this project's own "pre-release") never changes name between
    // pushes, but GitHub gives the replaced asset a new id every time, so
    // this still correctly detects a new build even when the tag doesn't.
    if Some(&release.tag_name) == app.latest_release_tag.as_ref()
        && Some(asset.id) == app.latest_release_asset_id
    {
        return Ok(());
    }

    let app_dir = format!("{TRACKED_APPS_DIR}/{}", app.id);
    tokio::fs::create_dir_all(&app_dir)
        .await
        .map_err(|e| e.to_string())?;
    let file_path = format!("{app_dir}/{}-{}.apk", release.tag_name, asset.id);
    // Streamed to disk, never held in memory: Element X's universal APK is 326 MB.
    let size = tokio::time::timeout(DOWNLOAD_TOTAL_TIMEOUT, download_asset(&asset, &file_path))
        .await
        .map_err(|_| {
            format!(
                "download of {} stopped after {} minutes",
                asset.name,
                DOWNLOAD_TOTAL_TIMEOUT.as_secs() / 60
            )
        })??;
    remove_stale_parts(&app_dir).await;

    if let Some(old_path) = &app.latest_release_file_path {
        if old_path != &file_path {
            tokio::fs::remove_file(old_path).await.ok();
        }
    }

    sqlx::query(
        "UPDATE tracked_apps SET latest_release_tag = ?, latest_release_asset_id = ?, \
         latest_release_file_path = ?, latest_release_asset_name = ?, \
         latest_release_asset_size = ? WHERE id = ?",
    )
    .bind(&release.tag_name)
    .bind(asset.id)
    .bind(&file_path)
    .bind(&asset.name)
    .bind(i64::try_from(size).unwrap_or(i64::MAX))
    .bind(app.id)
    .execute(&state.db)
    .await
    .map_err(|e| e.to_string())?;

    Ok(())
}

/// Background task: checks every enabled tracked app on a fixed interval.
/// One app's failure (bad repo, rate-limited, network blip) never blocks the
/// others or crashes the loop - logged and retried next cycle. A no-op per
/// iteration for manual-source apps (`sync_one_app` returns early for them).
pub async fn run_scheduled_tracked_app_sync(state: AppState) {
    let mut interval = tokio::time::interval(Duration::from_secs(60 * 60));
    loop {
        interval.tick().await;

        let apps = sqlx::query_as::<_, TrackedApp>("SELECT * FROM tracked_apps WHERE enabled = 1")
            .fetch_all(&state.db)
            .await
            .unwrap_or_default();

        for app in apps {
            if let Err(e) = sync_one_app(&state, &app).await {
                tracing::warn!("tracked app '{}' sync failed: {e}", app.name);
            }
        }
    }
}

/// A random on-disk filename component, not anything browser-supplied -
/// avoids trusting client input for a filesystem path. Reuses the device-
/// token generator purely for its randomness, not as a credential here.
fn random_label() -> String {
    generate_device_token()
}

#[derive(Template)]
#[template(path = "apps_list.html")]
struct AppsListTemplate {
    title: String,
    tracked: Vec<TrackedApp>,
}

/// The Apps tab - one card per tracked app, whatever its source (including
/// the launcher itself, which is just another GitHub-sourced row here).
pub async fn list_apps(State(state): State<AppState>) -> impl IntoResponse {
    let tracked = sqlx::query_as::<_, TrackedApp>("SELECT * FROM tracked_apps ORDER BY name")
        .fetch_all(&state.db)
        .await
        .unwrap_or_default();

    Html(
        AppsListTemplate {
            title: "Apps".to_string(),
            tracked,
        }
        .render()
        .unwrap(),
    )
}

#[derive(Template)]
#[template(path = "tracked_app_add.html")]
struct TrackedAppAddTemplate {
    title: String,
    /// Set when a save was refused; the fields below then hold what was entered.
    error: Option<String>,
    name: String,
    package_name: String,
    manual: bool,
    github_repo: String,
    asset_pattern: String,
    include_prereleases: bool,
}

pub async fn new_tracked_app_form() -> impl IntoResponse {
    Html(
        TrackedAppAddTemplate {
            title: "Add an app".to_string(),
            error: None,
            name: String::new(),
            package_name: String::new(),
            manual: false,
            github_repo: String::new(),
            asset_pattern: String::new(),
            include_prereleases: false,
        }
        .render()
        .unwrap(),
    )
}

/// Accepts a plain "owner/repo" string, or a pasted GitHub URL in any of its common forms - full
/// URL, with or without protocol/www, pointing at the repo root or its Releases page, with or
/// without a trailing slash or ".git" - and normalizes all of them down to "owner/repo" (what
/// `fetch_latest_release` actually needs to build the API URL). Confirmed live this needed to be
/// forgiving: an admin copying a URL out of the browser address bar naturally includes
/// "https://github.com/" and is often sitting on the repo's "/releases" page specifically, and a
/// strict "owner/repo"-only parser rejected all of that with no useful error.
fn normalize_github_repo(input: &str) -> String {
    let mut s = input.trim();
    for prefix in ["https://", "http://"] {
        if let Some(rest) = s.strip_prefix(prefix) {
            s = rest;
        }
    }
    for host in ["www.github.com/", "github.com/"] {
        if let Some(rest) = s.strip_prefix(host) {
            s = rest;
        }
    }
    let parts: Vec<&str> = s.split('/').filter(|p| !p.is_empty()).collect();
    match (parts.first(), parts.get(1)) {
        (Some(owner), Some(repo)) => {
            format!("{owner}/{}", repo.strip_suffix(".git").unwrap_or(repo))
        }
        _ => s.trim_end_matches('/').to_string(),
    }
}

pub async fn create_tracked_app(
    State(state): State<AppState>,
    Extension(_admin): Extension<CurrentAdmin>,
    Form(fields): Form<HashMap<String, String>>,
) -> Response {
    let field = |k: &str| fields.get(k).cloned().unwrap_or_default();
    let source_type = if field("source_type") == "manual" {
        "manual"
    } else {
        "github"
    };

    let github_repo = if source_type == "github" {
        normalize_github_repo(field("github_repo").trim())
    } else {
        String::new()
    };
    let asset_pattern = if source_type == "github" {
        let trimmed = field("asset_pattern").trim().to_string();
        (!trimmed.is_empty()).then_some(trimmed)
    } else {
        None
    };
    let include_prereleases = source_type == "github" && fields.contains_key("include_prereleases");

    // A refused save shows the form again with everything as entered (400, nothing written).
    // It posts back to its own path, so static/scroll-restore.js keeps the scroll position.
    if let Err(error) = validate_asset_pattern(asset_pattern.as_deref()) {
        let page = TrackedAppAddTemplate {
            title: "Add an app".to_string(),
            error: Some(error),
            name: field("name"),
            package_name: field("package_name"),
            manual: source_type == "manual",
            github_repo: field("github_repo"),
            asset_pattern: field("asset_pattern"),
            include_prereleases,
        };
        return (StatusCode::BAD_REQUEST, Html(page.render().unwrap())).into_response();
    }

    // package_name is never taken from admin input (see update_tracked_app's own doc comment) -
    // a brand-new app can't have one known yet anyway, since nothing's been installed to detect
    // it from. Always starts empty; device_api::status backfills it automatically.
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, source_type, github_repo, asset_pattern, include_prereleases) \
         VALUES (?, '', ?, ?, ?, ?) RETURNING id",
    )
    .bind(field("name").trim())
    .bind(source_type)
    .bind(&github_repo)
    .bind(&asset_pattern)
    .bind(include_prereleases)
    .fetch_one(&state.db)
    .await
    .expect("failed to create tracked app");

    Redirect::to(&format!("/apps/tracked/{id}")).into_response()
}

#[derive(Template)]
#[template(path = "tracked_app_detail.html")]
struct TrackedAppDetailTemplate {
    title: String,
    app: TrackedApp,
    error: Option<String>,
    details: DetailsForm,
}

/// What the Details form shows: the saved values, or - when a save was refused - what was
/// entered, with the reason next to the field.
struct DetailsForm {
    name: String,
    github_repo: String,
    asset_pattern: String,
    error: Option<String>,
}

pub async fn view_tracked_app(
    State(state): State<AppState>,
    Path(id): Path<i64>,
) -> impl IntoResponse {
    render_detail(&state, id, None).await
}

async fn render_detail(state: &AppState, id: i64, error: Option<String>) -> Response {
    render_detail_page(state, id, error, None).await
}

async fn render_detail_page(
    state: &AppState,
    id: i64,
    error: Option<String>,
    details: Option<DetailsForm>,
) -> Response {
    let app = sqlx::query_as::<_, TrackedApp>("SELECT * FROM tracked_apps WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
        .ok()
        .flatten();

    let Some(app) = app else {
        return (StatusCode::NOT_FOUND, "App not found").into_response();
    };

    let details = details.unwrap_or_else(|| DetailsForm {
        name: app.name.clone(),
        github_repo: app.github_repo.clone(),
        asset_pattern: app.asset_pattern.clone().unwrap_or_default(),
        error: None,
    });
    Html(
        TrackedAppDetailTemplate {
            title: app.name.clone(),
            app,
            error,
            details,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

pub async fn check_now(State(state): State<AppState>, Path(id): Path<i64>) -> impl IntoResponse {
    let app = sqlx::query_as::<_, TrackedApp>("SELECT * FROM tracked_apps WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
        .ok()
        .flatten();

    let Some(app) = app else {
        return (StatusCode::NOT_FOUND, "App not found").into_response();
    };

    // In its own task: a large download (minutes) still finishes, and is cached, when the
    // browser gives up waiting and the request goes away.
    let task_state = state.clone();
    let error = tokio::spawn(async move { sync_one_app(&task_state, &app).await })
        .await
        .unwrap_or_else(|e| Err(e.to_string()))
        .err();
    render_detail(&state, id, error).await
}

/// Manual-source apps' only path to a new release: the admin types a label
/// (there's no GitHub tag to borrow one from) and uploads an APK directly,
/// same shape as the old launcher_releases upload flow this replaces.
pub async fn upload_tracked_app_release(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    mut multipart: Multipart,
) -> impl IntoResponse {
    let app = sqlx::query_as::<_, TrackedApp>("SELECT * FROM tracked_apps WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
        .ok()
        .flatten();

    let Some(app) = app else {
        return (StatusCode::NOT_FOUND, "App not found").into_response();
    };

    let mut release_label: Option<String> = None;
    let mut apk_bytes: Option<Vec<u8>> = None;
    let mut apk_name: Option<String> = None;

    while let Ok(Some(field)) = multipart.next_field().await {
        let name = field.name().unwrap_or("").to_string();
        match name.as_str() {
            "release_label" => {
                release_label = field.text().await.ok().map(|s| s.trim().to_string());
            }
            "apk" => {
                apk_name = field
                    .file_name()
                    .map(|n| n.trim().chars().take(200).collect::<String>())
                    .filter(|n| !n.is_empty());
                apk_bytes = field.bytes().await.ok().map(|b| b.to_vec());
            }
            _ => {}
        }
    }

    let (Some(release_label), Some(apk_bytes)) = (release_label, apk_bytes) else {
        return render_detail(
            &state,
            id,
            Some("Fill in a release label and choose a file.".to_string()),
        )
        .await;
    };

    if release_label.is_empty() || apk_bytes.is_empty() {
        return render_detail(
            &state,
            id,
            Some("Fill in a release label and choose a file.".to_string()),
        )
        .await;
    }

    let app_dir = format!("{TRACKED_APPS_DIR}/{id}");
    if tokio::fs::create_dir_all(&app_dir).await.is_err() {
        return render_detail(
            &state,
            id,
            Some("Failed to save the uploaded file.".to_string()),
        )
        .await;
    }
    let file_path = format!("{app_dir}/{}.apk", random_label());
    if tokio::fs::write(&file_path, &apk_bytes).await.is_err() {
        return render_detail(
            &state,
            id,
            Some("Failed to save the uploaded file.".to_string()),
        )
        .await;
    }

    if let Some(old_path) = &app.latest_release_file_path {
        if old_path != &file_path {
            tokio::fs::remove_file(old_path).await.ok();
        }
    }

    sqlx::query(
        "UPDATE tracked_apps SET latest_release_tag = ?, latest_release_asset_id = NULL, \
         latest_release_file_path = ?, latest_release_asset_name = ?, \
         latest_release_asset_size = ?, last_checked_at = datetime('now') WHERE id = ?",
    )
    .bind(&release_label)
    .bind(&file_path)
    .bind(&apk_name)
    .bind(apk_bytes.len() as i64)
    .bind(id)
    .execute(&state.db)
    .await
    .ok();

    render_detail(&state, id, None).await
}

/// Lets an admin fix any of a tracked app's identifying details after creation - there was
/// previously no way to do this short of deleting and re-adding the row (losing its cached
/// release/check history). `source_type` itself isn't editable here - switching between
/// GitHub-tracked and manually-uploaded changes the whole update-fetching model, not just a field.
pub async fn update_tracked_app(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(fields): Form<HashMap<String, String>>,
) -> Response {
    let app = sqlx::query_as::<_, TrackedApp>("SELECT * FROM tracked_apps WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
        .ok()
        .flatten();
    let Some(app) = app else {
        return Redirect::to("/apps").into_response();
    };

    let field = |k: &str| fields.get(k).cloned().unwrap_or_default();
    let name = field("name").trim().to_string();

    let (github_repo, asset_pattern) = if app.source_type == "github" {
        let repo = normalize_github_repo(field("github_repo").trim());
        let pattern = field("asset_pattern").trim().to_string();
        (repo, (!pattern.is_empty()).then_some(pattern))
    } else {
        (app.github_repo.clone(), app.asset_pattern.clone())
    };

    // Refused as a whole (400, nothing written): the page comes back with the entered values and
    // the reason in the Details card, its filter field focused (and so scrolled into view).
    if let Err(error) = validate_asset_pattern(asset_pattern.as_deref()) {
        let details = DetailsForm {
            name: field("name"),
            github_repo: field("github_repo"),
            asset_pattern: field("asset_pattern"),
            error: Some(error),
        };
        let mut page = render_detail_page(&state, id, None, Some(details)).await;
        *page.status_mut() = StatusCode::BAD_REQUEST;
        return page;
    }

    // package_name is deliberately not editable here - it used to be free text ("Android package
    // name (optional)"), and a typo or a missed applicationIdSuffix (confirmed live: the browser
    // fork's real installed package is com.kidsmdm.browser.debug, not the com.kidsmdm.browser an
    // admin reasonably typed) silently broke the unified Apps list's matching with no obvious
    // cause. device_api::status now backfills it automatically the moment a device reports the
    // real package - see that handler's own doc comment - so there's no longer a reason for a
    // human to type it at all. forget_package_name below is the escape hatch if it's ever
    // detected wrong.
    sqlx::query(
        "UPDATE tracked_apps SET name = ?, github_repo = ?, asset_pattern = ? WHERE id = ?",
    )
    .bind(&name)
    .bind(&github_repo)
    .bind(&asset_pattern)
    .bind(id)
    .execute(&state.db)
    .await
    .ok();

    Redirect::to(&format!("/apps/tracked/{id}")).into_response()
}

/// Explicit admin-facing way to mark/unmark the one row that is the launcher itself - see
/// migrations/0013_device_tracked_apps.sql's doc comment for what this flag does. Exists because
/// the migration's one-time best-effort `UPDATE ... WHERE package_name = 'com.kidslauncher.mdm'`
/// missed the real row on a live install whose actual package name was the debug-suffixed
/// `com.kidslauncher.mdm.debug` (every build shipped so far has been the debug variant - see
/// kids-launcher-mdm's `app/build.gradle.kts`) - a plain heuristic match on package name is too
/// fragile to be the only way to set this, so there needed to be a direct way to fix it without a
/// new migration or shell access to the Pi. (The launcher's package is `me.vibb.launcher` since
/// 2026-10-06; migration 0037 renamed a launcher row that still had the old name.)
pub async fn set_is_launcher(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> impl IntoResponse {
    let is_launcher = form.contains_key("is_launcher");
    sqlx::query("UPDATE tracked_apps SET is_launcher = ? WHERE id = ?")
        .bind(is_launcher)
        .bind(id)
        .execute(&state.db)
        .await
        .ok();

    Redirect::to(&format!("/apps/tracked/{id}"))
}

pub async fn set_enabled(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> impl IntoResponse {
    let enabled = form.contains_key("enabled");
    sqlx::query("UPDATE tracked_apps SET enabled = ? WHERE id = ?")
        .bind(enabled)
        .bind(id)
        .execute(&state.db)
        .await
        .ok();

    Redirect::to(&format!("/apps/tracked/{id}"))
}

/// Resets a wrongly-detected package name back to empty, letting device_api::status's
/// auto-backfill try again from the next device that reports this app newly installed - the
/// escape hatch for update_tracked_app no longer accepting manual package name entry at all (see
/// that handler's own doc comment for why).
pub async fn forget_package_name(
    State(state): State<AppState>,
    Path(id): Path<i64>,
) -> impl IntoResponse {
    sqlx::query("UPDATE tracked_apps SET package_name = '' WHERE id = ?")
        .bind(id)
        .execute(&state.db)
        .await
        .ok();

    Redirect::to(&format!("/apps/tracked/{id}"))
}

pub async fn set_include_prereleases(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> impl IntoResponse {
    let include_prereleases = form.contains_key("include_prereleases");
    sqlx::query("UPDATE tracked_apps SET include_prereleases = ? WHERE id = ?")
        .bind(include_prereleases)
        .bind(id)
        .execute(&state.db)
        .await
        .ok();

    Redirect::to(&format!("/apps/tracked/{id}"))
}

pub async fn delete_tracked_app(
    State(state): State<AppState>,
    Path(id): Path<i64>,
) -> impl IntoResponse {
    let app = sqlx::query_as::<_, TrackedApp>("SELECT * FROM tracked_apps WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
        .ok()
        .flatten();

    // Enforced here, not just hidden in tracked_app_detail.html - the launcher is the app that
    // enforces every other restriction on the phone, so there's no safe way to let an admin
    // remove it from the push list. The template already doesn't render a delete control for it;
    // this is defense-in-depth against a direct POST.
    let Some(app) = app else {
        return Redirect::to("/apps");
    };
    if app.is_launcher {
        return Redirect::to(&format!("/apps/tracked/{id}"));
    }

    if let Some(path) = &app.latest_release_file_path {
        tokio::fs::remove_file(path).await.ok();
    }

    sqlx::query("DELETE FROM tracked_apps WHERE id = ?")
        .bind(id)
        .execute(&state.db)
        .await
        .ok();

    Redirect::to("/apps")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;

    fn release(tag: &str, prerelease: bool, assets: &[(i64, &str)]) -> GithubRelease {
        GithubRelease {
            tag_name: tag.to_string(),
            prerelease,
            draft: false,
            assets: assets
                .iter()
                .map(|(id, name)| GithubAsset {
                    id: *id,
                    name: name.to_string(),
                    browser_download_url: format!("https://example.org/{name}"),
                    size: 0,
                })
                .collect(),
        }
    }

    /// The newest matching release's tag and asset name for `pattern` (as stored).
    fn pick(
        releases: Vec<GithubRelease>,
        include_prereleases: bool,
        pattern: Option<&str>,
    ) -> Result<(String, String), String> {
        let filter = AssetFilter::parse(pattern)?;
        newest_matching_release(releases, include_prereleases, &filter)
            .map(|(r, a)| (r.tag_name, a.name))
    }

    /// The monorepo's list: a newer server release (no APK, and a `.apk`-named asset would not
    /// count either) and a launcher RC sit above the newest stable launcher.
    fn monorepo() -> Vec<GithubRelease> {
        vec![
            release("server-v0.19.0", false, &[(1, "kid-phone-server-x.tar.gz")]),
            release("server-v0.18.9", false, &[(2, "odd.apk")]),
            release(
                "launcher-v0.31.0-rc.1",
                true,
                &[(3, "kids-launcher-mdm.apk")],
            ),
            release("launcher-v0.30.0", false, &[(4, "kids-launcher-mdm.apk")]),
        ]
    }

    /// element-hq/element-x-android v26.09.4: per-ABI F-Droid builds, the universal Play build
    /// named after its versionCode (a new name every release), and an AAB.
    fn element_x() -> Vec<GithubRelease> {
        vec![release(
            "v26.09.4",
            false,
            &[
                (10, "app-fdroid-arm64-v8a-release-signed.apk"),
                (11, "app-fdroid-armeabi-v7a-release-signed.apk"),
                (12, "app-fdroid-x86-release-signed.apk"),
                (13, "app-fdroid-x86_64-release-signed.apk"),
                (14, "202609040.apk"),
                (15, "app-gplay-release-signed.aab"),
            ],
        )]
    }

    #[test]
    fn launcher_row_skips_server_releases() {
        let (r, a) = newest_matching_release(
            monorepo(),
            false,
            &AssetFilter::parse(Some("kids-launcher-mdm.apk")).unwrap(),
        )
        .unwrap();
        assert_eq!((r.tag_name.as_str(), a.id), ("launcher-v0.30.0", 4));

        let (r, a) = newest_matching_release(monorepo(), true, &AssetFilter::FirstApk).unwrap();
        assert_eq!((r.tag_name.as_str(), a.id), ("launcher-v0.31.0-rc.1", 3));
    }

    #[test]
    fn a_caret_pattern_is_a_regular_expression() {
        let picked = |pattern| pick(element_x(), false, pattern).map(|(_, name)| name);
        assert_eq!(picked(Some(r"^\d+\.apk$")).unwrap(), "202609040.apk");
        assert_eq!(
            picked(Some("^app-fdroid-arm64")).unwrap(),
            "app-fdroid-arm64-v8a-release-signed.apk"
        );
        // Without a filter: the first .apk. Without the ^: plain text the name must contain.
        assert_eq!(
            picked(None).unwrap(),
            "app-fdroid-arm64-v8a-release-signed.apk"
        );
        assert_eq!(
            picked(Some("x86_64")).unwrap(),
            "app-fdroid-x86_64-release-signed.apk"
        );
        assert!(picked(Some(r"\d+\.apk$")).is_err());
        assert!(picked(Some(r"^\d+\.aab$")).is_err());
    }

    #[test]
    fn substring_patterns_behave_as_before() {
        let launcher = Some("kids-launcher-mdm.apk");
        assert_eq!(
            pick(monorepo(), false, launcher).unwrap(),
            (
                "launcher-v0.30.0".to_string(),
                "kids-launcher-mdm.apk".to_string()
            )
        );
        // server-v* releases stay skipped, also for a filter (or a regex) one of them matches.
        assert_eq!(
            pick(monorepo(), false, Some("odd")).unwrap_err(),
            "no release with a matching asset found"
        );
        assert!(pick(monorepo(), false, Some("^odd")).is_err());
        assert!(pick(monorepo(), false, Some("^kid-phone-server")).is_err());
        // Regex characters without a leading ^ are literal text.
        assert!(pick(monorepo(), false, Some("kids-launcher-mdm.ap.")).is_err());
    }

    #[test]
    fn an_invalid_regular_expression_is_refused() {
        let reason = AssetFilter::parse(Some(r"^(\d+\.apk")).err().unwrap();
        assert!(
            reason.starts_with("not a valid regular expression ("),
            "{reason}"
        );
        assert!(!reason.contains('\n'), "{reason}");
        let message = validate_asset_pattern(Some("^[")).unwrap_err();
        assert!(message.contains("regular expression") && message.contains("Nothing was saved"));
        for ok in [
            None,
            Some(""),
            Some("kids-launcher-mdm.apk"),
            Some("[x"),
            Some(r"^\d+\.apk$"),
        ] {
            assert!(validate_asset_pattern(ok).is_ok(), "{ok:?}");
        }
    }

    #[test]
    fn only_a_complete_zip_is_accepted_as_an_apk() {
        let apk = b"PK\x03\x04rest-of-the-zip";
        let len = apk.len() as u64;
        assert!(check_apk_download(apk, len, 0).is_ok());
        assert!(check_apk_download(apk, len, len).is_ok());
        assert!(check_apk_download(apk, len, len + 1).is_err());
        assert!(check_apk_download(b"<!DOCTYPE html><html>Not Found", 30, 0).is_err());
        assert!(check_apk_download(b"", 0, 0).is_err());
        assert!(check_apk_download(b"PK", 2, 0).is_err());
    }

    #[test]
    fn no_matching_asset_is_an_error() {
        assert!(pick(monorepo(), false, Some("other.apk")).is_err());
    }

    enum Step {
        Data(&'static [u8]),
        Fail,
        Stall,
    }

    /// Canned chunks for [stream_to_file]; `Stall` never answers.
    struct Canned(VecDeque<Step>);

    impl Canned {
        fn new(steps: impl IntoIterator<Item = Step>) -> Self {
            Canned(steps.into_iter().collect())
        }
    }

    impl ChunkSource for Canned {
        async fn next_chunk(&mut self) -> Result<Option<Bytes>, String> {
            match self.0.pop_front() {
                None => Ok(None),
                Some(Step::Data(data)) => Ok(Some(Bytes::from_static(data))),
                Some(Step::Fail) => Err("connection reset".to_string()),
                Some(Step::Stall) => std::future::pending().await,
            }
        }
    }

    fn files_in(dir: &std::path::Path) -> Vec<String> {
        let mut names: Vec<String> = std::fs::read_dir(dir)
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        names.sort();
        names
    }

    const STALL: Duration = Duration::from_millis(200);

    #[tokio::test]
    async fn a_download_is_streamed_to_a_temp_file_and_renamed() {
        let dir = tempfile::tempdir().unwrap();
        let target = dir.path().join("v1-14.apk");
        let target = target.to_str().unwrap();
        // The ZIP header arrives split over chunks.
        let mut source = Canned::new([
            Step::Data(b"P"),
            Step::Data(b"K\x03"),
            Step::Data(b"\x04rest"),
            Step::Data(b"-of-the-zip"),
        ]);
        let written = stream_to_file(&mut source, target, 19, STALL)
            .await
            .unwrap();
        assert_eq!(written, 19);
        assert_eq!(std::fs::read(target).unwrap(), b"PK\x03\x04rest-of-the-zip");
        assert_eq!(files_in(dir.path()), ["v1-14.apk"]);
    }

    #[tokio::test]
    async fn a_failed_download_leaves_no_file() {
        let dir = tempfile::tempdir().unwrap();
        let target = dir.path().join("v1-14.apk");
        let target = target.to_str().unwrap();
        let cases: Vec<(Vec<Step>, u64, &str)> = vec![
            // Shorter than GitHub lists.
            (vec![Step::Data(b"PK\x03\x04abc")], 100, "GitHub lists 100"),
            // Longer than GitHub lists: stopped at once.
            (
                vec![Step::Data(b"PK\x03\x04abc"), Step::Data(b"more")],
                8,
                "longer than",
            ),
            // Not a ZIP: stopped at the first 4 bytes.
            (
                vec![Step::Data(b"<!DOCTYPE html>"), Step::Stall],
                0,
                "no ZIP header",
            ),
            // The connection drops half-way.
            (
                vec![Step::Data(b"PK\x03\x04abc"), Step::Fail],
                0,
                "connection reset",
            ),
            // No data for the stall time.
            (
                vec![Step::Data(b"PK\x03\x04abc"), Step::Stall],
                0,
                "stalled",
            ),
            (vec![], 0, "no ZIP header"),
        ];
        for (steps, expected_size, error) in cases {
            let mut source = Canned::new(steps);
            let err = stream_to_file(&mut source, target, expected_size, STALL)
                .await
                .unwrap_err();
            assert!(err.contains(error), "{err}");
            assert!(
                files_in(dir.path()).is_empty(),
                "{error}: {:?}",
                files_in(dir.path())
            );
        }
    }

    /// The overall cap (or a request that went away) drops the download mid-way: the temp file
    /// goes with it.
    #[tokio::test]
    async fn a_dropped_download_removes_its_temp_file() {
        let dir = tempfile::tempdir().unwrap();
        let target = dir.path().join("v1-14.apk");
        let target = target.to_str().unwrap();
        let mut source = Canned::new([Step::Data(b"PK\x03\x04abc"), Step::Stall]);
        let download = stream_to_file(&mut source, target, 0, Duration::from_secs(600));
        assert!(
            tokio::time::timeout(Duration::from_millis(200), download)
                .await
                .is_err()
        );
        assert!(
            files_in(dir.path()).is_empty(),
            "{:?}",
            files_in(dir.path())
        );
    }

    #[tokio::test]
    async fn an_asset_over_the_limit_is_refused_before_downloading() {
        let dir = tempfile::tempdir().unwrap();
        let target = dir.path().join("big.apk");
        let asset = GithubAsset {
            id: 1,
            name: "big.apk".to_string(),
            // Nothing listens there: an attempt to download would fail differently.
            browser_download_url: "http://127.0.0.1:9/big.apk".to_string(),
            size: MAX_ASSET_BYTES + 1,
        };
        let err = download_asset(&asset, target.to_str().unwrap())
            .await
            .unwrap_err();
        assert_eq!(
            err,
            "big.apk is 1000 MB, over the 1 GB limit - not downloaded"
        );
        assert!(files_in(dir.path()).is_empty());
    }

    #[tokio::test]
    async fn stale_part_files_are_removed() {
        let dir = tempfile::tempdir().unwrap();
        let old = std::time::SystemTime::now() - Duration::from_secs(3 * 60 * 60);
        for name in ["old.apk.x.part", "old.apk"] {
            let file = std::fs::File::create(dir.path().join(name)).unwrap();
            file.set_modified(old).unwrap();
        }
        std::fs::File::create(dir.path().join("live.apk.y.part")).unwrap();
        remove_stale_parts(dir.path().to_str().unwrap()).await;
        assert_eq!(files_in(dir.path()), ["live.apk.y.part", "old.apk"]);
    }
}
