//! Watches arbitrary GitHub repos' Releases for a new APK and pushes it to
//! devices - or, for apps with no public release feed (or where an admin
//! wants full manual control, including the launcher's own updates), lets
//! the admin upload an APK directly; or watches an F-Droid repo for a package's newest version,
//! each APK checked against the repo's index ([crate::fdroid], `sync_fdroid`). All three source
//! types feed the same
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
use regex::{Regex, RegexBuilder};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::time::Duration;
use tokio::io::AsyncWriteExt;

use crate::AppState;
use crate::config::{MUSIC_RELEASE_TAG_PREFIX, SERVER_RELEASE_TAG_PREFIX};
use crate::handlers::device_pages::AppsFrom;
use crate::models::TrackedApp;
use crate::security::{CurrentAdmin, generate_device_token};

/// Where cached APKs live, one directory per app (`AppState.tracked_apps_dir`; a temp dir in tests).
pub const TRACKED_APPS_DIR: &str = "data/tracked_apps";

/// The largest asset a sync downloads: one GitHub lists as bigger is refused before downloading,
/// one without a listed size is stopped once it gets there.
const MAX_ASSET_BYTES: u64 = 1_000_000_000;
/// A download that delivers no data for this long fails...
const DOWNLOAD_STALL_TIMEOUT: Duration = Duration::from_secs(60);
/// ...and so does one still running after this, however steadily it trickles.
const DOWNLOAD_TOTAL_TIMEOUT: Duration = Duration::from_secs(30 * 60);

/// A release's APK: a GitHub release asset as its API lists it, or an F-Droid repo's APK
/// (`sync_fdroid`: the id is the versionCode).
#[derive(Deserialize)]
struct ReleaseAsset {
    id: i64,
    name: String,
    browser_download_url: String,
    /// Bytes, as GitHub (or the repo's index) reports it; 0 if missing.
    #[serde(default)]
    size: u64,
    /// The SHA-256 (lower-case hex) the download must have, from an F-Droid repo's index: checked
    /// before the file is moved into place ([stream_to_file]). `None` for GitHub.
    #[serde(skip)]
    sha256: Option<String>,
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
            "downloaded asset has {len} bytes, the release lists {expected_size}"
        ));
    }
    Ok(())
}

/// Which asset of a release is the app (`tracked_apps.asset_pattern`): no pattern = the first
/// `.apk`; a pattern starting with `^` is a regular expression on the asset name (e.g.
/// `^\d+\.apk$` for Element X's universal APK, named after its versionCode); anything else is
/// text the name must contain. Only an `.apk` is ever picked, whatever the filter: an `.aab` or
/// another ZIP passes the download's header check but can't be installed (qa-catalog #6).
enum AssetFilter {
    FirstApk,
    Contains(String),
    Regex(Regex),
}

/// The longest filter the forms accept, in characters (qa-catalog #5).
const MAX_PATTERN_CHARS: usize = 256;
/// The compiled size limit of a `^` filter (the regex crate's default is 10 MiB).
const MAX_REGEX_BYTES: usize = 1 << 20;

impl AssetFilter {
    /// `Err(reason)` completes "the file name filter is ...": over [MAX_PATTERN_CHARS], or a
    /// `^` pattern that doesn't compile within [MAX_REGEX_BYTES].
    fn parse(pattern: Option<&str>) -> Result<Self, String> {
        let pattern = match pattern {
            None | Some("") => return Ok(Self::FirstApk),
            Some(p) => p,
        };
        if pattern.chars().count() > MAX_PATTERN_CHARS {
            return Err(format!("longer than {MAX_PATTERN_CHARS} characters"));
        }
        if !pattern.starts_with('^') {
            return Ok(Self::Contains(pattern.to_string()));
        }
        RegexBuilder::new(pattern)
            .size_limit(MAX_REGEX_BYTES)
            .build()
            .map(Self::Regex)
            .map_err(|e| {
                let detail = match e {
                    regex::Error::CompiledTooBig(_) => "too complex".to_string(),
                    // The message spans several lines (the pattern, a caret under the spot,
                    // "error: ..."); the last one is the reason.
                    e => {
                        let text = e.to_string();
                        let reason = text.lines().last().unwrap_or_default().trim();
                        reason.strip_prefix("error: ").unwrap_or(reason).to_string()
                    }
                };
                format!(
                    "not a valid regular expression ({detail}) - a filter starting with ^ is read \
                     as one"
                )
            })
    }

    fn matches(&self, asset_name: &str) -> bool {
        let is_apk = asset_name.to_ascii_lowercase().ends_with(".apk");
        is_apk
            && match self {
                Self::FirstApk => true,
                Self::Contains(text) => asset_name.contains(text.as_str()),
                Self::Regex(regex) => regex.is_match(asset_name),
            }
    }
}

/// The add and edit forms' check (see [AssetFilter::parse]). The message says nothing was saved,
/// since both forms refuse the whole save.
fn validate_asset_pattern(pattern: Option<&str>) -> Result<(), String> {
    AssetFilter::parse(pattern)
        .map(|_| ())
        .map_err(|reason| format!("The file name filter is {reason}. Nothing was saved."))
}

#[derive(Deserialize)]
struct GithubRelease {
    tag_name: String,
    #[serde(default)]
    prerelease: bool,
    #[serde(default)]
    draft: bool,
    assets: Vec<ReleaseAsset>,
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
/// releases next to the launcher's `launcher-v*` and the music app's `music-v*`
/// ones, so each catalog row must skip past the others' releases - see
/// [release_allowed].
async fn fetch_latest_release(
    github_repo: &str,
    include_prereleases: bool,
    asset_pattern: Option<&str>,
    tag_prefix: Option<&str>,
) -> Result<(GithubRelease, ReleaseAsset), Failure> {
    use SyncProblem::*;
    // An invalid stored filter fails before any request.
    AssetFilter::parse(asset_pattern).map_err(filter_failure)?;
    let client = reqwest::Client::builder()
        .user_agent("kid-phone-server (self-hosted, github.com)")
        .timeout(Duration::from_secs(15))
        .build()
        .map_err(|e| Failure::new(Server, e.to_string()))?;

    let url = format!("https://api.github.com/repos/{github_repo}/releases?per_page=100");
    let response = client
        .get(&url)
        .send()
        .await
        .map_err(|e| Failure::new(GithubUnreachable, e.to_string()))?;
    if !response.status().is_success() {
        return Err(Failure::new(
            github_problem(response.status()),
            format!("GitHub API returned {}", response.status()),
        ));
    }
    let releases: Vec<GithubRelease> = response
        .json()
        .await
        .map_err(|e| Failure::new(GithubError, e.to_string()))?;
    release_from(releases, include_prereleases, asset_pattern, tag_prefix)
}

/// What a non-success answer from GitHub's API means for the parent: 404 is a wrong (or private)
/// repo, anything else (a rate limit's 403 or 429, a 5xx) an error on GitHub's side.
fn github_problem(status: reqwest::StatusCode) -> SyncProblem {
    if status == reqwest::StatusCode::NOT_FOUND {
        SyncProblem::RepoNotFound
    } else {
        SyncProblem::GithubError
    }
}

/// An invalid stored file name filter as a sync failure.
fn filter_failure(reason: String) -> Failure {
    Failure::new(
        SyncProblem::Filter,
        format!("the file name filter is {reason}"),
    )
}

/// The newest release of `releases` (GitHub's list) the row takes: an invalid filter is `Filter`,
/// none matching `NoRelease`.
fn release_from(
    releases: Vec<GithubRelease>,
    include_prereleases: bool,
    asset_pattern: Option<&str>,
    tag_prefix: Option<&str>,
) -> Result<(GithubRelease, ReleaseAsset), Failure> {
    let filter = AssetFilter::parse(asset_pattern).map_err(filter_failure)?;
    newest_matching_release(releases, include_prereleases, &filter, tag_prefix)
        .map_err(|e| Failure::new(SyncProblem::NoRelease, e))
}

/// Whether a catalog row may take a release with this tag: never a `server-v*` one (the server's
/// own); with a `release_tag_prefix` (design 21: `launcher-v`, `music-v`) only its own; without one
/// anything but the music app's `music-v*` releases, so a launcher row that predates the prefix
/// (migration 0049 sets it only where the cached tag already was `launcher-v*`) can't pick up
/// vibb-music.apk.
fn release_allowed(tag: &str, tag_prefix: Option<&str>) -> bool {
    if tag.starts_with(SERVER_RELEASE_TAG_PREFIX) {
        return false;
    }
    match tag_prefix {
        Some(prefix) => tag.starts_with(prefix),
        None => !tag.starts_with(MUSIC_RELEASE_TAG_PREFIX),
    }
}

/// The longest release tag prefix the forms accept.
const MAX_TAG_PREFIX_CHARS: usize = 32;

/// A release tag prefix as typed: blank = none; else letters, digits, `.`, `_` and `-`, starting
/// with a letter or digit (e.g. `launcher-v`). `Err` completes the form's sentence.
fn parse_tag_prefix(input: &str) -> Result<Option<String>, String> {
    let prefix = input.trim();
    if prefix.is_empty() {
        return Ok(None);
    }
    let valid = prefix.len() <= MAX_TAG_PREFIX_CHARS
        && prefix
            .chars()
            .next()
            .is_some_and(|c| c.is_ascii_alphanumeric())
        && prefix
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'));
    if valid {
        Ok(Some(prefix.to_string()))
    } else {
        Err(format!(
            "The tag prefix may only hold letters, digits, '.', '_' and '-' (at most \
             {MAX_TAG_PREFIX_CHARS}), e.g. launcher-v. Nothing was saved."
        ))
    }
}

fn newest_matching_release(
    releases: Vec<GithubRelease>,
    include_prereleases: bool,
    filter: &AssetFilter,
    tag_prefix: Option<&str>,
) -> Result<(GithubRelease, ReleaseAsset), String> {
    releases
        .into_iter()
        .filter(|r| !r.draft && (include_prereleases || !r.prerelease))
        .filter(|r| release_allowed(&r.tag_name, tag_prefix))
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
pub(crate) struct TempDownload {
    pub(crate) path: String,
    keep: bool,
}

impl TempDownload {
    pub(crate) fn new(path: String) -> Self {
        TempDownload { path, keep: false }
    }

    pub(crate) fn keep(mut self) {
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

/// A finished download: its size and SHA-256 (lower-case hex).
#[derive(Debug, PartialEq, Eq)]
struct Downloaded {
    size: u64,
    sha256: String,
}

/// Lower-case hex of a SHA-256 digest.
fn hex_digest(digest: impl AsRef<[u8]>) -> String {
    hex::encode(digest.as_ref())
}

/// Streams an asset to `final_path` through a temp file next to it (`<final_path>.<random>.part`),
/// renamed into place only once it is a complete APK: ZIP header, exactly `expected_size` bytes
/// when the release lists one, never over [MAX_ASSET_BYTES], and - with `expected_sha256` (an
/// F-Droid repo's index) - exactly that SHA-256, else `ChecksumMismatch` and nothing is kept. A
/// chunk that takes longer than `stall` fails the download. Nothing is held in memory beyond one
/// chunk. Returns the bytes written and their SHA-256, which the phones check the whole file
/// against (design 13 QA #2).
async fn stream_to_file(
    source: &mut impl ChunkSource,
    final_path: &str,
    expected_size: u64,
    expected_sha256: Option<&str>,
    stall: Duration,
) -> Result<Downloaded, Failure> {
    use SyncProblem::{Download, Server, TooBig};
    let temp = TempDownload {
        path: format!("{final_path}.{}.part", random_label()),
        keep: false,
    };
    let mut file = tokio::fs::File::create(&temp.path)
        .await
        .map_err(|e| Failure::new(Server, format!("can't create {}: {e}", temp.path)))?;
    let mut head: Vec<u8> = Vec::with_capacity(4);
    let mut written: u64 = 0;
    let mut digest = Sha256::new();
    loop {
        let chunk = tokio::time::timeout(stall, source.next_chunk())
            .await
            .map_err(|_| {
                Failure::new(
                    Download,
                    format!("download stalled: no data for {} s", stall.as_secs()),
                )
            })?
            .map_err(|e| Failure::new(Download, e))?;
        let Some(chunk) = chunk else { break };
        written += chunk.len() as u64;
        if written > MAX_ASSET_BYTES {
            return Err(Failure::new(
                TooBig,
                format!(
                    "download stopped at {} MB, over the {} limit",
                    written / 1_000_000,
                    size_limit_text()
                ),
            ));
        }
        if expected_size != 0 && written > expected_size {
            return Err(Failure::new(
                Download,
                format!("download is longer than the {expected_size} bytes the release lists"),
            ));
        }
        if head.len() < 4 {
            let take = (4 - head.len()).min(chunk.len());
            head.extend_from_slice(&chunk[..take]);
            if head.len() == 4 {
                check_apk_download(&head, written, 0).map_err(|e| Failure::new(Download, e))?;
            }
        }
        file.write_all(&chunk)
            .await
            .map_err(|e| Failure::new(Server, format!("can't write {}: {e}", temp.path)))?;
        digest.update(&chunk);
    }
    check_apk_download(&head, written, expected_size).map_err(|e| Failure::new(Download, e))?;
    let sha256 = hex_digest(digest.finalize());
    if let Some(expected) = expected_sha256
        && !sha256.eq_ignore_ascii_case(expected)
    {
        return Err(Failure::new(
            SyncProblem::ChecksumMismatch,
            format!(
                "the download's SHA-256 is {sha256}, the repo's index says {expected} - not stored"
            ),
        ));
    }
    let server = |e: std::io::Error| Failure::new(Server, e.to_string());
    file.flush().await.map_err(server)?;
    file.sync_all().await.map_err(server)?;
    drop(file);
    tokio::fs::rename(&temp.path, final_path)
        .await
        .map_err(|e| Failure::new(Server, format!("can't move the download into place: {e}")))?;
    temp.keep();
    Ok(Downloaded {
        size: written,
        sha256,
    })
}

/// "1 GB", for the messages about [MAX_ASSET_BYTES].
fn size_limit_text() -> String {
    format!("{} GB", MAX_ASSET_BYTES / 1_000_000_000)
}

/// Downloads `asset` to `final_path` (see [stream_to_file]): refused up front when GitHub lists it
/// over [MAX_ASSET_BYTES]; fails when the server sends no data for [DOWNLOAD_STALL_TIMEOUT]. The
/// overall cap ([DOWNLOAD_TOTAL_TIMEOUT]) is the caller's. An F-Droid repo's APK (`fdroid`) is
/// fetched without any proxy from the environment (qa-fdroid-source-code Q-2: no proxy in the trust
/// path, as `music_net` does); a GitHub asset keeps reqwest's default.
async fn download_asset(
    asset: &ReleaseAsset,
    final_path: &str,
    fdroid: bool,
) -> Result<Downloaded, Failure> {
    use SyncProblem::{Download, Server};
    let too_big = |bytes: u64| {
        Failure::new(
            SyncProblem::TooBig,
            format!(
                "{} is {} MB, over the {} limit - not downloaded",
                asset.name,
                bytes / 1_000_000,
                size_limit_text()
            ),
        )
    };
    if asset.size > MAX_ASSET_BYTES {
        return Err(too_big(asset.size));
    }
    // A neutral user agent: the same client fetches GitHub's assets and F-Droid's APKs (Q-9).
    let mut builder = reqwest::Client::builder()
        .user_agent("kid-phone-server (self-hosted)")
        .connect_timeout(Duration::from_secs(15))
        .redirect(https_redirects());
    if fdroid {
        builder = builder.no_proxy();
    }
    let client = builder
        .build()
        .map_err(|e| Failure::new(Server, e.to_string()))?;
    let mut response = tokio::time::timeout(
        DOWNLOAD_STALL_TIMEOUT,
        client.get(&asset.browser_download_url).send(),
    )
    .await
    .map_err(|_| {
        Failure::new(
            Download,
            format!(
                "no answer from the download server for {} s",
                DOWNLOAD_STALL_TIMEOUT.as_secs()
            ),
        )
    })?
    .map_err(|e| Failure::new(Download, e.to_string()))?
    .error_for_status()
    .map_err(|e| Failure::new(Download, e.to_string()))?;
    if let Some(length) = response.content_length()
        && length > MAX_ASSET_BYTES
    {
        return Err(too_big(length));
    }
    stream_to_file(
        &mut response,
        final_path,
        asset.size,
        asset.sha256.as_deref(),
        DOWNLOAD_STALL_TIMEOUT,
    )
    .await
}

/// At most 5 redirects, and never from https to anything else: GitHub's asset links redirect to
/// its CDN, an F-Droid repo's to a mirror - both over https (the F-Droid trust statement: the
/// index and the APK come over HTTPS).
pub(crate) fn https_redirects() -> reqwest::redirect::Policy {
    reqwest::redirect::Policy::custom(|attempt| {
        let verdict = match attempt.previous().first() {
            Some(first) => redirect_verdict(first, attempt.url(), attempt.previous().len()),
            None => Ok(()),
        };
        match verdict {
            Ok(()) => attempt.follow(),
            Err(why) => attempt.error(why),
        }
    })
}

/// The most redirects a download follows.
const MAX_REDIRECTS: usize = 5;

/// [https_redirects]' rule (qa-fdroid-source-code Q-1): the `hops`-th redirect (reqwest's
/// `previous()` holds the first URL too, so the first redirect is hop 1) from `first` to `next` is
/// followed up to [MAX_REDIRECTS], and never from an https start to anything but https; another
/// host is fine (GitHub's CDN, an F-Droid mirror).
fn redirect_verdict(
    first: &reqwest::Url,
    next: &reqwest::Url,
    hops: usize,
) -> Result<(), &'static str> {
    if hops > MAX_REDIRECTS {
        Err("more than 5 redirects")
    } else if first.scheme() == "https" && next.scheme() != "https" {
        Err("a redirect away from https")
    } else {
        Ok(())
    }
}

/// Deletes every `<root>/<app id>/*.part`: left by a crash or a power cut mid-download, which
/// [TempDownload] can't clean up. Run once at startup, before the scheduled sync starts, when
/// nothing can be downloading (qa-catalog #2). Best-effort.
pub async fn remove_partial_downloads(root: &std::path::Path) {
    let Ok(mut apps) = tokio::fs::read_dir(root).await else {
        return;
    };
    while let Ok(Some(app_dir)) = apps.next_entry().await {
        let Ok(mut files) = tokio::fs::read_dir(app_dir.path()).await else {
            continue;
        };
        while let Ok(Some(file)) = files.next_entry().await {
            if file.file_name().to_string_lossy().ends_with(".part") {
                match tokio::fs::remove_file(file.path()).await {
                    Ok(()) => {
                        tracing::info!("removed a partial download: {}", file.path().display())
                    }
                    Err(err) => tracing::warn!("can't remove {}: {err}", file.path().display()),
                }
            }
        }
    }
}

/// Fills in `latest_release_sha256` for cached files that predate it (migration 0043), one app at a
/// time, hashed off the async threads. The row is only updated while it still names the hashed
/// file, so a sync or upload meanwhile wins. Run once at startup in its own task; best-effort.
pub async fn backfill_release_hashes(state: AppState) {
    let rows: Vec<(i64, String)> = match sqlx::query_as(
        "SELECT id, latest_release_file_path FROM tracked_apps \
         WHERE latest_release_file_path IS NOT NULL AND latest_release_sha256 IS NULL",
    )
    .fetch_all(&state.db)
    .await
    {
        Ok(rows) => rows,
        Err(err) => {
            tracing::warn!(%err, "can't list the cached files to hash");
            return;
        }
    };
    for (id, path) in rows {
        let hashed = {
            let path = path.clone();
            tokio::task::spawn_blocking(move || sha256_of_file(std::path::Path::new(&path))).await
        };
        let sha256 = match hashed {
            Ok(Ok(sha256)) => sha256,
            Ok(Err(err)) => {
                tracing::warn!(app_id = id, %err, "can't hash the cached file");
                continue;
            }
            Err(err) => {
                tracing::warn!(app_id = id, %err, "hashing the cached file failed");
                continue;
            }
        };
        if let Err(err) = sqlx::query(
            "UPDATE tracked_apps SET latest_release_sha256 = ? \
             WHERE id = ? AND latest_release_file_path = ? AND latest_release_sha256 IS NULL",
        )
        .bind(&sha256)
        .bind(id)
        .bind(&path)
        .execute(&state.db)
        .await
        {
            tracing::warn!(app_id = id, %err, "can't store the cached file's hash");
        }
    }
    backfill_pins(&state).await;
}

/// SHA-256 (lower-case hex) of a file, read in 64 KiB pieces. Blocking.
fn sha256_of_file(path: &std::path::Path) -> std::io::Result<String> {
    use std::io::Read;
    let mut file = std::fs::File::open(path)?;
    let mut digest = Sha256::new();
    let mut buffer = vec![0u8; 64 * 1024];
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        digest.update(&buffer[..read]);
    }
    Ok(hex_digest(digest.finalize()))
}

/// The cached APK's package and signer ([crate::apk]), read off the async threads.
async fn apk_identity(path: &str) -> Result<crate::apk::ApkIdentity, String> {
    let path = std::path::PathBuf::from(path);
    tokio::task::spawn_blocking(move || crate::apk::read_identity(&path))
        .await
        .map_err(|err| format!("reading the APK failed: {err}"))?
}

/// A release must be the app the catalog already knows (security review M10): the same package
/// and the pinned signing key, each readable from the APK once it is pinned (fail closed, like the
/// signer - QA of the security wave). The first release pins what it has.
pub(crate) fn check_pins(
    app: &TrackedApp,
    identity: &crate::apk::ApkIdentity,
) -> Result<(), Failure> {
    match (&identity.package, app.package_name.is_empty()) {
        (Some(package), false) if *package != app.package_name => {
            return Err(Failure::new(
                SyncProblem::OtherApp,
                format!(
                    "refused: the release is the Android package {package}, this app is {} (if \
                     the catalog has it wrong, forget the package name and signing key)",
                    app.package_name
                ),
            ));
        }
        (None, false) => {
            return Err(Failure::new(
                SyncProblem::CantCheck,
                "refused: the release's Android package can't be read from its APK, so it can't \
                 be checked against this app's",
            ));
        }
        _ => {}
    }
    match (&app.signer_sha256, &identity.signer_sha256) {
        (Some(pinned), Some(signer)) if pinned != signer => Err(Failure::new(
            SyncProblem::OtherKey,
            "refused: the release is signed with a different key than this app's earlier \
             releases (if its developer really changed keys, forget the package name and signing \
             key)",
        )),
        (Some(_), None) => Err(Failure::new(
            SyncProblem::CantCheck,
            "refused: the release has no APK Signature Scheme v2/v3 signature to check against \
             this app's signing key",
        )),
        _ => Ok(()),
    }
}

/// Packages a catalog app can never be: the Android system's own (`android`, `com.android.*` -
/// Settings, the package installer, the AOSP browser and files apps) and Play's core.
pub(crate) fn never_a_catalog_package(package: &str) -> bool {
    package == "android"
        || package.starts_with("com.android.")
        || crate::play::is_play_core(package)
}

/// The phone (by name) whose latest report has `package` as a system app or from an installer
/// other than the launcher (Play, another store); a sideloaded copy (no installer) doesn't count.
async fn foreign_on_a_phone(
    state: &AppState,
    package: &str,
) -> Result<Option<String>, sqlx::Error> {
    let latest: Vec<(String, Option<String>)> = sqlx::query_as(
        "SELECT d.name, s.installed_apps_json FROM devices d JOIN device_status s ON s.id = \
         (SELECT id FROM device_status WHERE device_id = d.id ORDER BY reported_at DESC, id DESC \
          LIMIT 1)",
    )
    .fetch_all(&state.db)
    .await?;
    Ok(latest.into_iter().find_map(|(name, json)| {
        let installed: Vec<crate::models::InstalledApp> = json
            .as_deref()
            .and_then(|json| serde_json::from_str(json).ok())
            .unwrap_or_default();
        installed
            .iter()
            .any(|app| {
                app.package_name == package
                    && (app.preinstalled
                        || app.installer.as_deref().is_some_and(|installer| {
                            !crate::handlers::device_api::installed_by_launcher(
                                state,
                                Some(installer),
                            )
                        }))
            })
            .then_some(name)
    }))
}

/// A release that would give the app its package (it had none) must not name one a catalog app
/// can't be, or one a phone already has from elsewhere (QA of the security wave, Medium 2: a
/// release naming Settings or a Play-installed browser). Allowing never follows from a release
/// anyway (`device_api::allow_installed_catalog_apps`), but a pinned package is what checking the
/// app on another phone allows.
async fn check_new_package(
    state: &AppState,
    app: &TrackedApp,
    identity: &crate::apk::ApkIdentity,
) -> Result<(), Failure> {
    let Some(package) = identity
        .package
        .as_deref()
        .filter(|_| app.package_name.is_empty())
    else {
        return Ok(());
    };
    if never_a_catalog_package(package) {
        return Err(Failure::new(
            SyncProblem::Taken,
            format!(
                "refused: the release is the Android package {package}, which belongs to the \
                 system - a catalog app can't be it"
            ),
        ));
    }
    // One package, one catalog row (qa-fdroid-source-code Q-6): another row pinned to it, or an
    // F-Droid row waiting for it, has it already - the phones would get two rows for one app.
    let taken: Result<Option<String>, sqlx::Error> = sqlx::query_scalar(
        "SELECT name FROM tracked_apps WHERE (package_name = ?1 OR fdroid_package = ?1) \
         AND id != ?2 ORDER BY id LIMIT 1",
    )
    .bind(package)
    .bind(app.id)
    .fetch_optional(&state.db)
    .await;
    match taken {
        Ok(None) => {}
        Ok(Some(other)) => {
            return Err(Failure::new(
                SyncProblem::Taken,
                format!(
                    "refused: the release is the Android package {package}, which {other} in the \
                     catalog already is - one app, one row"
                ),
            ));
        }
        Err(err) => {
            return Err(Failure::new(
                SyncProblem::Server,
                format!("refused: couldn't check the catalog's packages ({err})"),
            ));
        }
    }
    match foreign_on_a_phone(state, package).await {
        Ok(None) => Ok(()),
        Ok(Some(phone)) => Err(Failure::new(
            SyncProblem::Taken,
            format!(
                "refused: the release is the Android package {package}, which {phone} already has \
                 as a system app or from another store - a catalog app can't take it over"
            ),
        )),
        Err(err) => Err(Failure::new(
            SyncProblem::Server,
            format!("refused: couldn't check the phones' apps ({err})"),
        )),
    }
}

/// Stores the pins a release brought, where the app had none: the package only when the catalog
/// had none, the signer only when none is pinned. Nothing is allowed by this: a phone allows the
/// app once the launcher has installed it there (`device_api::allow_installed_catalog_apps`), or
/// when the parent checks it.
async fn store_pins(state: &AppState, app: &TrackedApp, identity: &crate::apk::ApkIdentity) {
    let new_package = identity
        .package
        .as_deref()
        .filter(|_| app.package_name.is_empty());
    let new_signer = identity
        .signer_sha256
        .as_deref()
        .filter(|_| app.signer_sha256.is_none());
    if new_package.is_none() && new_signer.is_none() {
        return;
    }
    let stored = sqlx::query(
        "UPDATE tracked_apps SET \
         package_name = CASE WHEN package_name = '' THEN COALESCE(?, '') ELSE package_name END, \
         signer_sha256 = COALESCE(signer_sha256, ?) WHERE id = ?",
    )
    .bind(new_package)
    .bind(new_signer)
    .bind(app.id)
    .execute(&state.db)
    .await;
    if let Err(err) = stored {
        tracing::error!(app_id = app.id, %err, "can't store the app's pins");
    }
}

/// Pins the package and signer of releases cached before migration 0062, from the cached file
/// (what the phones have installed). A catalog package that differs from the file's is left as it
/// is and logged: the next release is then refused until the parent forgets it. Run once at
/// startup after the hash backfill; best-effort.
async fn backfill_pins(state: &AppState) {
    let apps = match sqlx::query_as::<_, TrackedApp>(
        "SELECT * FROM tracked_apps \
         WHERE latest_release_file_path IS NOT NULL AND signer_sha256 IS NULL",
    )
    .fetch_all(&state.db)
    .await
    {
        Ok(apps) => apps,
        Err(err) => {
            tracing::warn!(%err, "can't list the cached files to pin");
            return;
        }
    };
    for app in apps {
        let Some(path) = app.latest_release_file_path.as_deref() else {
            continue;
        };
        match apk_identity(path).await {
            Ok(identity) => {
                if let Some(package) = &identity.package
                    && !app.package_name.is_empty()
                    && *package != app.package_name
                {
                    tracing::warn!(
                        app_id = app.id,
                        "the cached APK is {package}, the catalog says {} - not pinned",
                        app.package_name
                    );
                    continue;
                }
                if let Err(err) = check_new_package(state, &app, &identity).await {
                    tracing::warn!(app_id = app.id, "the cached APK isn't pinned: {err}");
                    continue;
                }
                store_pins(state, &app, &identity).await;
            }
            Err(err) => tracing::warn!(app_id = app.id, %err, "can't read the cached APK"),
        }
    }
}

/// Which apps are syncing right now, and how each one's last sync ended (shown on its page). One
/// sync per app at a time (qa-catalog #1): "Check now" and the hourly loop never download the same
/// app twice in parallel, and a slower sync that saw an older release can't finish last and roll
/// the cached one back. In memory, so a restart forgets it (`AppState.app_syncs`).
#[derive(Default)]
pub struct AppSyncs(std::sync::Mutex<HashMap<i64, SyncStatus>>);

#[derive(Default, Clone)]
struct SyncStatus {
    /// When the running sync started (UTC), `None` when none runs.
    running_since: Option<String>,
    /// The last finished sync's failure, `None` after a success.
    last_error: Option<SyncFailure>,
    /// The release (tag, asset id) the last sync refused for its package or key, and why: the
    /// hourly sync doesn't download it again (QA of the security wave, Low 5 - a 326 MB APK every
    /// hour); "Check now" and "Forget" do.
    refused: Option<(String, i64, Failure)>,
    /// The release (tag, asset id) whose download didn't match the repo's checksum, and how many
    /// syncs in a row it did: at [MISMATCHES_TO_REFUSE] it becomes [SyncStatus::refused].
    mismatches: Option<(String, i64, u32)>,
}

/// Checksum mismatches in a row of one release before the hourly sync stops downloading it
/// (qa-fdroid-source-code Q-3); "Check Now" still tries.
const MISMATCHES_TO_REFUSE: u32 = 3;

#[derive(Clone)]
pub struct SyncFailure {
    /// UTC, like `tracked_apps.last_checked_at`.
    pub at: String,
    pub failure: Failure,
}

/// Why a sync or an upload didn't take a release, as a kind (design 31 §4.4, step 11): the app's
/// page shows [SyncProblem::title], the raw error ([Failure::detail]) behind its ⓘ.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SyncProblem {
    /// The stored file name filter doesn't compile (the forms refuse one, so only an old row).
    Filter,
    /// No answer from GitHub: the network, DNS, a timeout.
    GithubUnreachable,
    /// GitHub answered 404: a wrong or private repo.
    RepoNotFound,
    /// GitHub answered another error (a rate limit's 403, a 5xx) or something unreadable.
    GithubError,
    /// No release (of the allowed tags) has a matching .apk.
    NoRelease,
    /// The download failed, stalled, took too long or wasn't a whole APK.
    Download,
    /// The release's file is over the 1 GB limit: refused until the release changes.
    TooBig,
    /// The APK's manifest or signature can't be read.
    Unreadable,
    /// The release is another package than the app's pinned one.
    OtherApp,
    /// The release is signed with another key than the pinned one.
    OtherKey,
    /// Pinned, but the release's package or signature can't be read to check it.
    CantCheck,
    /// The release's package is the system's, or one a phone has from elsewhere.
    Taken,
    /// This server couldn't store or check something (a full disk, the database).
    Server,
    /// The app was removed while it synced.
    Removed,
    /// f-droid.org's API doesn't know the package (its 404).
    FdroidUnknown,
    /// The repo's index doesn't have the package (any repo; qa-fdroid-source-code Q-9).
    NotInRepo,
    /// No answer from the F-Droid repo: the network, a timeout, a 5xx.
    RepoUnreachable,
    /// The repo's entry.json, index or API answer can't be used (not an F-Droid repo, too big,
    /// no APK for the version, the stored repo or package invalid).
    RepoError,
    /// The APK (or the index) didn't have the SHA-256 the repo lists: nothing stored.
    ChecksumMismatch,
}

impl SyncProblem {
    /// The short title (at most 6 words, §4.2) the app's page shows.
    pub fn title(self) -> &'static str {
        match self {
            SyncProblem::Filter => "File name filter is invalid",
            SyncProblem::GithubUnreachable => "Couldn't reach GitHub",
            SyncProblem::RepoNotFound => "Repo not found on GitHub",
            SyncProblem::GithubError => "GitHub returned an error",
            SyncProblem::NoRelease => "No matching release found",
            SyncProblem::Download => "Download failed",
            SyncProblem::TooBig => "Release file is over 1 GB",
            SyncProblem::Unreadable => "Couldn't read the APK",
            SyncProblem::OtherApp => "Release is a different app",
            SyncProblem::OtherKey => "Signed with a different key",
            SyncProblem::CantCheck => "Release can't be checked",
            SyncProblem::Taken => "Package belongs to another app",
            SyncProblem::Server => "Something went wrong",
            SyncProblem::Removed => "The app was removed",
            SyncProblem::FdroidUnknown => "F-Droid doesn't know this package",
            SyncProblem::NotInRepo => "The repo doesn't have this package",
            SyncProblem::RepoUnreachable => "The repo didn't answer",
            SyncProblem::RepoError => "The repo's answer can't be used",
            SyncProblem::ChecksumMismatch => "Download didn't match the repo's checksum",
        }
    }
}

/// A failed sync or upload: its kind and the raw error (reqwest, sqlx and file errors included),
/// which only the ⓘ panel and the log show.
#[derive(Clone, Debug)]
pub struct Failure {
    pub problem: SyncProblem,
    pub detail: String,
}

impl Failure {
    pub fn new(problem: SyncProblem, detail: impl Into<String>) -> Self {
        Failure {
            problem,
            detail: detail.into(),
        }
    }
}

impl std::fmt::Display for Failure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.problem.title(), self.detail)
    }
}

fn utc_now() -> String {
    chrono::Utc::now().format("%Y-%m-%d %H:%M:%S").to_string()
}

impl AppSyncs {
    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<i64, SyncStatus>> {
        self.0
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// Marks `id` as syncing, or `None` when a sync of it already runs.
    pub(crate) fn start(self: &std::sync::Arc<Self>, id: i64) -> Option<SyncGuard> {
        let mut map = self.lock();
        let status = map.entry(id).or_default();
        if status.running_since.is_some() {
            return None;
        }
        status.running_since = Some(utc_now());
        Some(SyncGuard {
            syncs: self.clone(),
            id,
        })
    }

    fn status(&self, id: i64) -> SyncStatus {
        self.lock().get(&id).cloned().unwrap_or_default()
    }

    fn refused(&self, id: i64) -> Option<(String, i64, Failure)> {
        self.lock()
            .get(&id)
            .and_then(|status| status.refused.clone())
    }

    fn set_refused(&self, id: i64, refused: Option<(String, i64, Failure)>) {
        self.lock().entry(id).or_default().refused = refused;
    }

    /// Counts a checksum mismatch of `release` (tag, asset id) and returns how many in a row it
    /// has had (a mismatch of another release starts again at 1); `None` - a download that
    /// matched - clears the count and returns 0.
    fn mismatched(&self, id: i64, release: Option<(String, i64)>) -> u32 {
        let mut map = self.lock();
        let status = map.entry(id).or_default();
        let Some((tag, asset)) = release else {
            status.mismatches = None;
            return 0;
        };
        let count = match &status.mismatches {
            Some((t, a, n)) if *t == tag && *a == asset => n + 1,
            _ => 1,
        };
        status.mismatches = Some((tag, asset, count));
        count
    }
}

/// Held for one app's sync; dropping it (also when the sync's future is dropped) ends the sync.
pub(crate) struct SyncGuard {
    syncs: std::sync::Arc<AppSyncs>,
    id: i64,
}

impl SyncGuard {
    pub(crate) fn finish(self, result: &Result<(), Failure>) {
        self.syncs.lock().entry(self.id).or_default().last_error =
            result.as_ref().err().map(|failure| SyncFailure {
                at: utc_now(),
                failure: failure.clone(),
            });
    }
}

impl Drop for SyncGuard {
    fn drop(&mut self) {
        if let Some(status) = self.syncs.lock().get_mut(&self.id) {
            status.running_since = None;
        }
    }
}

enum SyncError {
    AlreadyRunning,
    Failed(Failure),
}

/// Syncs app `id` unless a sync of it already runs, and records how it ended. See [sync_one_app];
/// `forced` ("Check now") downloads a release the last sync refused again.
async fn sync_app(state: &AppState, id: i64, forced: bool) -> Result<(), SyncError> {
    let Some(guard) = state.app_syncs.start(id) else {
        return Err(SyncError::AlreadyRunning);
    };
    let result = sync_one_app(state, id, forced).await;
    guard.finish(&result);
    result.map_err(SyncError::Failed)
}

/// Checks one GitHub-sourced app's repo for a new release and, if the
/// release+asset identity differs from what's cached, downloads the
/// matching asset and replaces the previously-cached file. A no-op for
/// manual-source apps - those only ever change via `upload_tracked_app_release`.
/// Used by both the scheduled loop and the admin's manual "Check now"
/// button, so they can never drift apart. Only through [sync_app], which holds the app's guard:
/// the row is read here, under it, so the comparison is with what the last sync stored.
async fn sync_one_app(state: &AppState, id: i64, forced: bool) -> Result<(), Failure> {
    let app = sqlx::query_as::<_, TrackedApp>("SELECT * FROM tracked_apps WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
        .map_err(|e| Failure::new(SyncProblem::Server, e.to_string()))?
        .ok_or_else(|| Failure::new(SyncProblem::Removed, "the app was removed"))?;
    match app.source_type.as_str() {
        "github" => {}
        "fdroid" => return sync_fdroid(state, &app, forced).await,
        _ => return Ok(()),
    }

    let (release, asset) = fetch_latest_release(
        &app.github_repo,
        app.include_prereleases,
        app.asset_pattern.as_deref(),
        app.release_tag_prefix.as_deref(),
    )
    .await?;

    sqlx::query("UPDATE tracked_apps SET last_checked_at = datetime('now') WHERE id = ?")
        .bind(app.id)
        .execute(&state.db)
        .await
        .ok();
    sync_release(state, &app, &release.tag_name, &asset, forced).await
}

/// Marks the app checked (the page's "Last checked").
async fn touch_checked(state: &AppState, id: i64) {
    sqlx::query("UPDATE tracked_apps SET last_checked_at = datetime('now') WHERE id = ?")
        .bind(id)
        .execute(&state.db)
        .await
        .ok();
}

/// An F-Droid app's sync ([crate::fdroid]): the newest version from the repo's per-package API
/// (f-droid.org) or its index (any other repo), its SHA-256 and size from the repo's index, then
/// [sync_release] with that hash, so the APK is checked before it is stored. The release is
/// stored as a GitHub one: tag `v<versionName>`, asset id = the versionCode.
async fn sync_fdroid(state: &AppState, app: &TrackedApp, forced: bool) -> Result<(), Failure> {
    use crate::fdroid;
    let repo = app
        .fdroid_repo
        .as_deref()
        .and_then(fdroid::Repo::from_stored);
    let package = app
        .fdroid_package
        .as_deref()
        .filter(|p| crate::app_display::valid_package_name(p));
    let (Some(repo), Some(package)) = (repo, package) else {
        return Err(Failure::new(
            SyncProblem::RepoError,
            "the stored repo address or package isn't valid - fix it under Details",
        ));
    };
    let client = fdroid::client()?;
    let from_api = match &repo.api {
        Some(api) => Some(fdroid::api_version(&client, api, package).await?),
        None => None,
    };
    // The API's version is the cached one: nothing to download, and no index needed.
    if let Some((code, name)) = &from_api
        && app.latest_release_asset_id == Some(*code)
        && app.latest_release_tag.as_deref() == Some(fdroid::tag_of(name, *code).as_str())
    {
        touch_checked(state, app.id).await;
        return Ok(());
    }
    let versions = fdroid::repo_package(
        &client,
        &repo,
        &state.tracked_apps_dir.join(fdroid::CACHE_DIR),
        package,
        forced,
    )
    .await?;
    let version = fdroid::pick(&versions, package, from_api.as_ref().map(|(code, _)| *code))?;
    touch_checked(state, app.id).await;
    // The API's name when there is one, so the check above matches next time.
    let name = match &from_api {
        Some((_, name)) if !name.trim().is_empty() => name.as_str(),
        _ => version.name.as_str(),
    };
    let asset = ReleaseAsset {
        id: version.code,
        name: version.file.clone(),
        browser_download_url: format!("{}/{}", repo.url, version.file),
        size: version.size,
        sha256: Some(version.sha256.clone()),
    };
    sync_release(
        state,
        app,
        &fdroid::tag_of(name, version.code),
        &asset,
        forced,
    )
    .await
}

/// A release tag as part of a file name: anything but letters, digits, `.`, `_` and `-` becomes
/// `_` (an F-Droid versionName is free text; a GitHub tag may hold a `/`).
fn file_safe(tag: &str) -> String {
    tag.chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-') {
                c
            } else {
                '_'
            }
        })
        .collect()
}

/// An F-Droid app's APK must be the repo's package: anything else is refused before it is pinned
/// or stored (a GitHub or manual app is checked by its pins alone).
fn check_fdroid_package(
    app: &TrackedApp,
    identity: &crate::apk::ApkIdentity,
) -> Result<(), Failure> {
    let Some(expected) = app
        .fdroid_package
        .as_deref()
        .filter(|_| app.source_type == "fdroid")
    else {
        return Ok(());
    };
    match identity.package.as_deref() {
        Some(package) if package == expected => Ok(()),
        Some(package) => Err(Failure::new(
            SyncProblem::OtherApp,
            format!(
                "refused: the repo's APK is the Android package {package}, this app is {expected}"
            ),
        )),
        None => Err(Failure::new(
            SyncProblem::CantCheck,
            "refused: the repo's APK has no readable Android package",
        )),
    }
}

/// Downloads `asset` of release `tag` for `app` and caches it, unless it is what's cached already,
/// or the release the last sync refused (unless `forced`). A release refused for its package or
/// key is remembered ([SyncStatus::refused]).
async fn sync_release(
    state: &AppState,
    app: &TrackedApp,
    tag: &str,
    asset: &ReleaseAsset,
    forced: bool,
) -> Result<(), Failure> {
    use SyncProblem::*;
    if !forced
        && let Some((refused_tag, refused_asset, why)) = state.app_syncs.refused(app.id)
        && refused_tag == tag
        && refused_asset == asset.id
    {
        return Err(Failure::new(
            why.problem,
            format!(
                "{} (not downloaded again until the release changes; \"Check Now\" tries again)",
                why.detail
            ),
        ));
    }

    // Compared as a (tag, asset id) pair, not just the tag - a rolling tag
    // (e.g. this project's own "pre-release") never changes name between
    // pushes, but GitHub gives the replaced asset a new id every time, so
    // this still correctly detects a new build even when the tag doesn't.
    if app.latest_release_tag.as_deref() == Some(tag)
        && Some(asset.id) == app.latest_release_asset_id
    {
        return Ok(());
    }

    let app_dir = state.tracked_apps_dir.join(app.id.to_string());
    tokio::fs::create_dir_all(&app_dir)
        .await
        .map_err(|e| Failure::new(Server, e.to_string()))?;
    let file_path = app_dir
        .join(format!("{}-{}.apk", file_safe(tag), asset.id))
        .to_string_lossy()
        .into_owned();
    // Streamed to disk, never held in memory: Element X's universal APK is 326 MB.
    let downloaded = tokio::time::timeout(
        DOWNLOAD_TOTAL_TIMEOUT,
        download_asset(asset, &file_path, app.source_type == "fdroid"),
    )
    .await
    .map_err(|_| {
        Failure::new(
            Download,
            format!(
                "download of {} stopped after {} minutes",
                asset.name,
                DOWNLOAD_TOTAL_TIMEOUT.as_secs() / 60
            ),
        )
    })?;
    let downloaded = match downloaded {
        Ok(downloaded) => {
            state.app_syncs.mismatched(app.id, None);
            downloaded
        }
        Err(err) if err.problem == ChecksumMismatch => {
            // Once is a CDN mid-update; MISMATCHES_TO_REFUSE in a row is a mirror or proxy that is
            // wrong, and isn't downloaded again every hour (qa-fdroid-source-code Q-3).
            let count = state
                .app_syncs
                .mismatched(app.id, Some((tag.to_string(), asset.id)));
            if count >= MISMATCHES_TO_REFUSE {
                let refused = Failure::new(
                    ChecksumMismatch,
                    format!("{} ({count} checks in a row)", err.detail),
                );
                state
                    .app_syncs
                    .set_refused(app.id, Some((tag.to_string(), asset.id, refused.clone())));
                return Err(refused);
            }
            return Err(err);
        }
        Err(err) => return Err(err),
    };
    let checked = match apk_identity(&file_path)
        .await
        .map_err(|e| Failure::new(Unreadable, e))
    {
        Ok(identity) => {
            match check_fdroid_package(app, &identity).and_then(|()| check_pins(app, &identity)) {
                Ok(()) => check_new_package(state, app, &identity)
                    .await
                    .map(|()| identity),
                Err(err) => Err(err),
            }
        }
        Err(err) => Err(err),
    };
    let identity = match checked {
        Ok(identity) => identity,
        Err(err) => {
            tokio::fs::remove_file(&file_path).await.ok();
            state
                .app_syncs
                .set_refused(app.id, Some((tag.to_string(), asset.id, err.clone())));
            return Err(err);
        }
    };

    let stored = sqlx::query(
        "UPDATE tracked_apps SET latest_release_tag = ?, latest_release_asset_id = ?, \
         latest_release_file_path = ?, latest_release_asset_name = ?, \
         latest_release_asset_size = ?, latest_release_sha256 = ? WHERE id = ?",
    )
    .bind(tag)
    .bind(asset.id)
    .bind(&file_path)
    .bind(&asset.name)
    .bind(i64::try_from(downloaded.size).unwrap_or(i64::MAX))
    .bind(&downloaded.sha256)
    .bind(app.id)
    .execute(&state.db)
    .await;
    let stored = match stored {
        Ok(result) => result.rows_affected() > 0,
        Err(err) => {
            tokio::fs::remove_file(&file_path).await.ok();
            return Err(Failure::new(Server, err.to_string()));
        }
    };
    replace_cached_file(stored, &file_path, app.latest_release_file_path.as_deref()).await;
    if !stored {
        return Err(Failure::new(
            Removed,
            "the app was removed during the download",
        ));
    }
    store_pins(state, app, &identity).await;
    // Stored: a refusal remembered before (a mismatch "Check Now" got past) no longer holds.
    state.app_syncs.set_refused(app.id, None);
    // App update notices (2026-10-25): a new release of an app that had one - never its first.
    if let Some(event) = update_available_event(app, tag, asset.id, chrono::Utc::now()) {
        crate::parent_events::raise_logged(state, &event).await;
    }
    crate::handlers::device_api::nudge_phones_with_app(state, app.id).await;
    Ok(())
}

/// Whether release `tag` (asset `asset_id`) is ahead of `app`'s stored one (qa-pwa-fixes-b-code
/// Q-1): a source can go back - "Include prereleases" turned off after an RC, the newest release
/// deleted, an F-Droid repo dropping its newest build -, and an older release stored over a newer
/// one is no update. F-Droid: the asset id is the versionCode, higher is newer. GitHub: the
/// versions when both read as X.Y.Z (an RC before its release, `install_now::version_order`) -
/// the same version under a new asset id is a rolling tag's new build -, else the asset id, which
/// GitHub hands out in upload order.
fn release_ahead(app: &TrackedApp, tag: &str, asset_id: i64) -> bool {
    let (Some(old_tag), Some(old_asset)) = (
        app.latest_release_tag.as_deref(),
        app.latest_release_asset_id,
    ) else {
        // Stored before the asset id was: only the versions can tell.
        let prefix = app.release_tag_prefix.as_deref();
        return app.latest_release_tag.as_deref().is_some_and(|old| {
            let order = |t| crate::install_now::version_order(&version_of(t, prefix));
            matches!((order(tag), order(old)), (Some(new), Some(old)) if new > old)
        });
    };
    if app.source_type == "fdroid" {
        return asset_id > old_asset;
    }
    let prefix = app.release_tag_prefix.as_deref();
    let order = |t| crate::install_now::version_order(&version_of(t, prefix));
    match (order(tag), order(old_tag)) {
        (Some(new), Some(old)) if new != old => new > old,
        _ => asset_id > old_asset,
    }
}

/// The `app_update_available` event for a release just stored over `app`'s previous one (2026-10-25,
/// `docs/notes/server/notifications.md` "App update notices"): `None` for the app's first release
/// and for one that isn't ahead of it ([release_ahead]). Keyed on the release as stored (`tag@asset
/// id`): a sweep or a Check Now that finds it again raises nothing - one event per release, never
/// per sweep.
fn update_available_event(
    app: &TrackedApp,
    tag: &str,
    asset_id: i64,
    at: chrono::DateTime<chrono::Utc>,
) -> Option<crate::parent_events::NewEvent> {
    let old_tag = app.latest_release_tag.as_deref()?;
    if !release_ahead(app, tag, asset_id) {
        return None;
    }
    let prefix = app.release_tag_prefix.as_deref();
    let (from, to) = (version_of(old_tag, prefix), version_of(tag, prefix));
    Some(crate::parent_events::app_update_available(
        app.id,
        &app.name,
        &format!("{tag}@{asset_id}"),
        // A rolling tag's new build has the same name: no "from".
        (from != to).then_some(from.as_str()),
        &to,
        at,
    ))
}

/// The versionCodes a status report names (2026-10-25): the launcher's own (`app_version_code`,
/// with its versionName) and the music app's (`music_state.version_code`) - the two catalog apps
/// whose installed version the server learns. Other catalog apps' versions aren't reported.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct InstalledCodes {
    pub launcher: Option<i64>,
    pub launcher_name: Option<String>,
    pub music: Option<i64>,
}

/// Whether a phone that runs `code` of `app` (the launcher's row when `launcher`, else the music
/// app's) has the row's stored release installed: the launcher's by its tag's versionCode
/// (`install_now::launcher_tag_version_code`), the music app's by the version its code reads as
/// (`device_pages::version_of_code`) against the tag's.
pub(crate) fn release_installed(app: &TrackedApp, launcher: bool, code: i64) -> bool {
    let Some(tag) = app.latest_release_tag.as_deref() else {
        return false;
    };
    if launcher {
        return crate::install_now::launcher_tag_version_code(tag) == Some(code);
    }
    crate::handlers::device_pages::version_of_code(code).as_deref()
        == Some(version_of(tag, app.release_tag_prefix.as_deref()).as_str())
}

/// `app_updated` events from a status report (2026-10-25): for the launcher and the music app, a
/// versionCode higher than the previous report's that is the catalog row's stored release - once
/// per phone and version (the key). No previous report (a new phone) or no code: nothing.
pub(crate) async fn note_app_updates(
    state: &AppState,
    phone: &crate::parent_events::Phone,
    before: &InstalledCodes,
    now: &InstalledCodes,
) {
    let read = async {
        let launcher: Option<TrackedApp> =
            sqlx::query_as("SELECT * FROM tracked_apps WHERE is_launcher = 1 ORDER BY id LIMIT 1")
                .fetch_optional(&state.db)
                .await?;
        let music = crate::handlers::music::music_app_row(&state.db).await?;
        Ok::<_, sqlx::Error>((launcher, music))
    };
    let (launcher, music) = match read.await {
        Ok(rows) => rows,
        Err(err) => {
            tracing::error!(device_id = phone.id, %err, "can't read the catalog for app updates");
            return;
        }
    };
    let cases = [
        (
            launcher,
            true,
            before.launcher,
            now.launcher,
            before.launcher_name.clone(),
        ),
        (
            music.filter(|m| !m.is_launcher),
            false,
            before.music,
            now.music,
            before
                .music
                .and_then(crate::handlers::device_pages::version_of_code),
        ),
    ];
    for (row, is_launcher, was, is, from) in cases {
        let (Some(app), Some(was), Some(is)) = (row, was, is) else {
            continue;
        };
        if is <= was || !release_installed(&app, is_launcher, is) {
            continue;
        }
        let tag = app.latest_release_tag.as_deref().unwrap_or_default();
        let to = version_of(tag, app.release_tag_prefix.as_deref());
        let from = from.filter(|f| !f.trim().is_empty() && *f != to);
        let event = crate::parent_events::app_updated(
            phone.id,
            &phone.name,
            app.id,
            &app.name,
            is,
            from.as_deref(),
            &to,
            phone.now,
        );
        crate::parent_events::raise_logged(state, &event).await;
    }
}

/// After the row's UPDATE (never before, so a phone never gets a 404 in between and a failed
/// UPDATE keeps the old file, qa-catalog #7): removes the old file once the row names the new one,
/// or the new one when the UPDATE matched no row - the app was deleted meanwhile (#4).
async fn replace_cached_file(stored: bool, new_path: &str, old_path: Option<&str>) {
    if !stored {
        tokio::fs::remove_file(new_path).await.ok();
    } else if let Some(old_path) = old_path.filter(|old| *old != new_path) {
        tokio::fs::remove_file(old_path).await.ok();
    }
}

/// Background task: checks every enabled tracked app on a fixed interval.
/// One app's failure (bad repo, rate-limited, network blip) never blocks the
/// others or crashes the loop - logged and retried next cycle. A no-op per
/// iteration for manual-source apps (`sync_one_app` returns early for them),
/// and for an app whose "Check now" is still running.
pub async fn run_scheduled_tracked_app_sync(state: AppState) {
    let mut interval = tokio::time::interval(Duration::from_secs(60 * 60));
    loop {
        interval.tick().await;

        let apps = sqlx::query_as::<_, TrackedApp>("SELECT * FROM tracked_apps WHERE enabled = 1")
            .fetch_all(&state.db)
            .await
            .unwrap_or_default();

        for app in apps {
            match sync_app(&state, app.id, false).await {
                Ok(()) => {}
                Err(SyncError::AlreadyRunning) => {
                    tracing::info!("tracked app '{}' is already syncing, skipped", app.name);
                }
                Err(SyncError::Failed(e)) => {
                    tracing::warn!("tracked app '{}' sync failed: {e}", app.name);
                }
            }
        }
    }
}

/// A random on-disk filename component, not anything browser-supplied -
/// avoids trusting client input for a filesystem path. Reuses the device-
/// token generator purely for its randomness, not as a credential here.
pub(crate) fn random_label() -> String {
    generate_device_token()
}

/// One phone on the Apps tab or an app's page (design 31 §3.3): its avatar, its name and, on the
/// tab, how many apps it allows.
struct PhoneRow {
    id: i64,
    name: String,
    initial: String,
    avatar: &'static str,
    photo: Option<String>,
    /// "14 allowed", "Not set up"; empty on an app's page.
    value: String,
    href: String,
    /// The launcher's page: the phone's Install Now part (design 37).
    install: Option<crate::handlers::install_now::InstallRow>,
}

impl PhoneRow {
    fn new(device: &crate::models::Device, value: String, href: String) -> Self {
        PhoneRow {
            id: device.id,
            name: device.name.clone(),
            initial: crate::handlers::devices::initial(&device.name),
            avatar: crate::handlers::devices::avatar_class(device),
            photo: crate::handlers::devices::avatar_photo(device),
            value,
            href,
            install: None,
        }
    }
}

/// A catalog row's icon tile: the catalog icon on its colour (what the phones show), else the
/// first letter of the name on a colour by the app's id.
struct AppTile {
    letter: String,
    colour: &'static str,
    /// The icon key (`/static/app-icons/<key>.svg`) and the tile's colour.
    icon: Option<(String, &'static str)>,
}

/// One app on the Apps tab's catalog (design 31 §3.3).
struct CatalogRow {
    id: i64,
    /// The name phones show ("Chat"), else the app's own.
    label: String,
    /// "Launcher · all phones", "Element X · 1 phone", "No phones".
    sub: String,
    /// "26.10.1", from the release tag.
    version: Option<String>,
    /// Instead of the version: "Off", "Update failed", "No release yet", and the badge's class.
    badge: Option<(&'static str, &'static str)>,
    tile: AppTile,
}

/// Where a phone's apps are listed: Device › Apps (design 31 step 6), at the app's row
/// (`#app-<package>`) when there is one.
fn phone_apps_href(device_id: i64, package: Option<&str>, from: AppsFrom) -> String {
    let fragment = package
        .filter(|p| crate::app_display::valid_package_name(p))
        .map(|package| format!("app-{package}"))
        .unwrap_or_default();
    crate::handlers::device_pages::apps_url(device_id, "", Some(from), &fragment)
}

/// A release tag as a version: without the row's tag prefix and a leading "v" before a digit
/// ("launcher-v0.32.0" -> "0.32.0", "v26.09.4" -> "26.09.4"); a manual upload's label as typed.
fn version_of(tag: &str, prefix: Option<&str>) -> String {
    let rest = prefix
        .and_then(|prefix| tag.strip_prefix(prefix))
        .filter(|rest| !rest.is_empty())
        .unwrap_or(tag);
    let starts_with_digit = |s: &str| s.starts_with(|c: char| c.is_ascii_digit());
    if let Some(version) = rest.strip_prefix(['v', 'V'])
        && starts_with_digit(version)
    {
        return version.to_string();
    }
    if starts_with_digit(rest) {
        return rest.to_string();
    }
    // "launcher-v0.32.0" without its row's prefix: from the first "-v" before a digit.
    rest.match_indices("-v")
        .map(|(at, _)| &rest[at + 2..])
        .find(|version| starts_with_digit(version))
        .unwrap_or(rest)
        .to_string()
}

/// "1 phone", "2 phones", "no phones".
fn phones_text(count: i64) -> String {
    match count {
        0 => "no phones".to_string(),
        1 => "1 phone".to_string(),
        n => format!("{n} phones"),
    }
}

/// The first letter upper-cased ("no phones" -> "No phones").
fn capitalised(text: &str) -> String {
    let mut chars = text.chars();
    match chars.next() {
        Some(first) => first.to_uppercase().chain(chars).collect(),
        None => String::new(),
    }
}

fn app_tile(app: &TrackedApp, label: &str) -> AppTile {
    let colours = crate::handlers::devices::AVATAR_COLOURS;
    AppTile {
        letter: crate::handlers::devices::initial(label),
        colour: colours[app.id.rem_euclid(colours.len() as i64) as usize],
        icon: app
            .display_icon
            .as_deref()
            .filter(|key| crate::app_display::icon_known(key))
            .map(|key| {
                (
                    key.to_string(),
                    crate::app_display::tile_of(&app.display_color),
                )
            }),
    }
}

/// The catalog row of `app`, which `phones` phones have ticked; the launcher is on every one of
/// the `set_up` phones.
fn catalog_row(state: &AppState, app: &TrackedApp, phones: i64, set_up: i64) -> CatalogRow {
    let label = app
        .display_label
        .clone()
        .filter(|label| !label.trim().is_empty())
        .unwrap_or_else(|| app.name.clone());
    let mut sub = Vec::new();
    if app.is_launcher {
        sub.push("Launcher".to_string());
    } else if label != app.name {
        sub.push(app.name.clone());
    }
    sub.push(match (app.is_launcher, set_up) {
        (true, 0) => phones_text(0),
        (true, _) => "all phones".to_string(),
        (false, _) => phones_text(phones),
    });
    let badge = if !app.enabled {
        Some(("Off", "badge"))
    } else if state.app_syncs.status(app.id).last_error.is_some() {
        Some(("Update failed", "badge badge-red"))
    } else if app.latest_release_tag.is_none() {
        Some(("No release yet", "badge"))
    } else {
        None
    };
    CatalogRow {
        id: app.id,
        tile: app_tile(app, &label),
        label,
        sub: capitalised(&sub.join(" · ")),
        version: app
            .latest_release_tag
            .as_deref()
            .map(|tag| version_of(tag, app.release_tag_prefix.as_deref())),
        badge,
    }
}

/// How many apps a phone allows, as Device › Apps switches them on (design 31 §3.3,
/// "14 allowed"): the apps it reported that its allowlist has (not Play's core), and the catalog
/// apps chosen for it that it hasn't reported installed; not the launcher. `None` when the stored
/// allowlist can't be read.
async fn allowed_count(state: &AppState, device_id: i64, catalog: &[TrackedApp]) -> Option<usize> {
    let allowlist: Option<String> =
        sqlx::query_scalar("SELECT allowlist_json FROM device_policy WHERE device_id = ?")
            .bind(device_id)
            .fetch_optional(&state.db)
            .await
            .ok()?
            .flatten();
    let allowed: std::collections::HashSet<String> = allowlist
        .as_deref()
        .map(serde_json::from_str::<Vec<String>>)
        .transpose()
        .ok()?
        .unwrap_or_default()
        .into_iter()
        .collect();
    let report: Option<String> = sqlx::query_scalar(
        "SELECT installed_apps_json FROM device_status WHERE device_id = ? \
         ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(device_id)
    .fetch_optional(&state.db)
    .await
    .ok()
    .flatten()
    .flatten();
    let installed: Vec<crate::models::InstalledApp> = report
        .as_deref()
        .and_then(|json| serde_json::from_str(json).ok())
        .unwrap_or_default();
    let selected: std::collections::HashSet<i64> =
        sqlx::query_scalar("SELECT tracked_app_id FROM device_tracked_apps WHERE device_id = ?")
            .bind(device_id)
            .fetch_all(&state.db)
            .await
            .ok()?
            .into_iter()
            .collect();
    let installed: Vec<&crate::models::InstalledApp> = installed
        .iter()
        .filter(|app| !crate::play::is_play_core(&app.package_name))
        .collect();
    let seen: std::collections::HashSet<&str> = installed
        .iter()
        .map(|app| app.package_name.as_str())
        .collect();
    let reported = installed
        .iter()
        .filter(|app| allowed.contains(&app.package_name))
        .count();
    let waiting = catalog
        .iter()
        .filter(|t| !t.is_launcher && !crate::play::is_play_core(&t.package_name))
        .filter(|t| t.package_name.is_empty() || !seen.contains(t.package_name.as_str()))
        .filter(|t| selected.contains(&t.id))
        .count();
    Some(reported + waiting)
}

#[derive(Template)]
#[template(path = "apps_list.html")]
struct AppsListTemplate {
    title: String,
    phones: Vec<PhoneRow>,
    apps: Vec<CatalogRow>,
    bell: crate::parent_events::Bell,
}

/// The Apps tab (design 31 §3.3): each phone with how many apps it allows, the catalog - every
/// app the server keeps up to date, the launcher's own row included - and Add App.
pub async fn list_apps(
    State(state): State<AppState>,
    axum::Extension(crate::security::CurrentAdmin(admin)): axum::Extension<
        crate::security::CurrentAdmin,
    >,
) -> impl IntoResponse {
    let tracked = sqlx::query_as::<_, TrackedApp>("SELECT * FROM tracked_apps ORDER BY name")
        .fetch_all(&state.db)
        .await
        .unwrap_or_default();
    let devices = sqlx::query_as::<_, crate::models::Device>("SELECT * FROM devices ORDER BY name")
        .fetch_all(&state.db)
        .await
        .unwrap_or_default();
    let mut phones = Vec::with_capacity(devices.len());
    for device in &devices {
        let value = if device.enrolled_at.is_none() {
            "Not set up".to_string()
        } else {
            match allowed_count(&state, device.id, &tracked).await {
                Some(count) => format!("{count} allowed"),
                None => "List unreadable".to_string(),
            }
        };
        phones.push(PhoneRow::new(
            device,
            value,
            phone_apps_href(device.id, None, AppsFrom::List),
        ));
    }
    let set_up = devices.iter().filter(|d| d.enrolled_at.is_some()).count() as i64;
    let counts: HashMap<i64, i64> = sqlx::query_as::<_, (i64, i64)>(
        "SELECT tracked_app_id, COUNT(*) FROM device_tracked_apps GROUP BY tracked_app_id",
    )
    .fetch_all(&state.db)
    .await
    .unwrap_or_default()
    .into_iter()
    .collect();
    let mut apps: Vec<CatalogRow> = tracked
        .iter()
        .map(|app| {
            catalog_row(
                &state,
                app,
                counts.get(&app.id).copied().unwrap_or(0),
                set_up,
            )
        })
        .collect();
    // The launcher first (the mockup's "Vibb"), then by the name the parent sees.
    let launcher: Option<i64> = tracked.iter().find(|t| t.is_launcher).map(|t| t.id);
    apps.sort_by_key(|row| (Some(row.id) != launcher, row.label.to_lowercase()));

    Html(
        AppsListTemplate {
            title: "Apps".to_string(),
            phones,
            apps,
            bell: crate::parent_events::Bell::load(&state.db, admin.id).await,
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
    /// The chosen source: "github", "fdroid" or "manual".
    source: &'static str,
    github_repo: String,
    asset_pattern: String,
    include_prereleases: bool,
    /// Design 21: only releases whose tag starts with this.
    release_tag_prefix: String,
    /// An F-Droid app's repo (prefilled with [crate::fdroid::DEFAULT_REPO]) and package id.
    fdroid_repo: String,
    fdroid_package: String,
    /// The field the error belongs to ("asset_pattern", "release_tag_prefix", "fdroid_repo" or
    /// "fdroid_package").
    error_field: &'static str,
}

impl TrackedAppAddTemplate {
    /// "More options" starts open when one of its fields holds something or was refused.
    fn more_open(&self) -> bool {
        matches!(self.error_field, "asset_pattern" | "release_tag_prefix")
            || !self.package_name.trim().is_empty()
            || !self.asset_pattern.trim().is_empty()
            || !self.release_tag_prefix.trim().is_empty()
            || self.include_prereleases
    }
}

pub async fn new_tracked_app_form() -> impl IntoResponse {
    Html(
        TrackedAppAddTemplate {
            title: "Add App".to_string(),
            error: None,
            name: String::new(),
            package_name: String::new(),
            source: "github",
            github_repo: String::new(),
            asset_pattern: String::new(),
            include_prereleases: false,
            release_tag_prefix: String::new(),
            fdroid_repo: crate::fdroid::DEFAULT_REPO.to_string(),
            fdroid_package: String::new(),
            error_field: "",
        }
        .render()
        .unwrap(),
    )
}

/// An F-Droid source as typed (the add and edit forms): the normalised repo and the package, or
/// the reason and the field it belongs to. The launcher's and the music app's packages stay
/// GitHub's (their release workflows sign them; F-Droid would sign with its own key), a system
/// package is never a catalog app, and a package another catalog app (not `this` one) already is
/// is refused - the phones would get two rows for one app.
async fn parse_fdroid_source(
    state: &AppState,
    repo: &str,
    package: &str,
    this: Option<i64>,
) -> Result<(String, String), (String, &'static str)> {
    let repo = crate::fdroid::normalize_repo(repo).map_err(|e| (e, "fdroid_repo"))?;
    let package = crate::fdroid::parse_package(package).map_err(|e| (e, "fdroid_package"))?;
    let refused = |text: String| Err((format!("{text} Nothing was saved."), "fdroid_package"));
    if crate::handlers::device_api::installed_by_launcher(state, Some(&package)) {
        return refused(
            "Vibb One updates itself from its own GitHub releases, never from F-Droid.".into(),
        );
    }
    if crate::music::MUSIC_PACKAGES.contains(&package.as_str()) {
        return refused(
            "Vibb music comes from this project's GitHub releases, never from F-Droid.".into(),
        );
    }
    if never_a_catalog_package(&package) {
        return refused(format!(
            "{package} belongs to the system - a catalog app can't be it."
        ));
    }
    let taken: Result<Option<String>, sqlx::Error> = sqlx::query_scalar(
        "SELECT name FROM tracked_apps WHERE (package_name = ?1 OR fdroid_package = ?1) \
         AND id IS NOT ?2 ORDER BY id LIMIT 1",
    )
    .bind(&package)
    .bind(this)
    .fetch_optional(&state.db)
    .await;
    match taken {
        Ok(None) => Ok((repo, package)),
        Ok(Some(name)) => refused(format!("{name} in the catalog is already {package}.")),
        Err(err) => {
            tracing::error!(%err, "can't check the catalog's packages");
            Err((
                "Couldn't check the catalog. Nothing was saved.".to_string(),
                "fdroid_package",
            ))
        }
    }
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
    let source_type = match field("source_type").as_str() {
        "manual" => "manual",
        "fdroid" => "fdroid",
        _ => "github",
    };
    let fdroid = if source_type == "fdroid" {
        Some(
            parse_fdroid_source(
                &state,
                &field("fdroid_repo"),
                &field("fdroid_package"),
                None,
            )
            .await,
        )
    } else {
        None
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
    let tag_prefix = if source_type == "github" {
        parse_tag_prefix(&field("release_tag_prefix"))
    } else {
        Ok(None)
    };

    // A refused save shows the form again with everything as entered (400, nothing written).
    // It posts back to its own path, so static/scroll-restore.js keeps the scroll position.
    let refused = match (
        validate_asset_pattern(asset_pattern.as_deref()),
        &tag_prefix,
        &fdroid,
    ) {
        (Err(error), _, _) => Some((error, "asset_pattern")),
        (Ok(()), Err(error), _) => Some((error.clone(), "release_tag_prefix")),
        (Ok(()), Ok(_), Some(Err((error, field)))) => Some((error.clone(), *field)),
        (Ok(()), Ok(_), _) => None,
    };
    if let Some((error, error_field)) = refused {
        let page = TrackedAppAddTemplate {
            title: "Add App".to_string(),
            error: Some(error),
            name: field("name"),
            package_name: field("package_name"),
            source: source_type,
            github_repo: field("github_repo"),
            asset_pattern: field("asset_pattern"),
            include_prereleases,
            release_tag_prefix: field("release_tag_prefix"),
            fdroid_repo: field("fdroid_repo"),
            fdroid_package: field("fdroid_package"),
            error_field,
        };
        return (StatusCode::BAD_REQUEST, Html(page.render().unwrap())).into_response();
    }
    let tag_prefix = tag_prefix.unwrap_or_default();
    let (fdroid_repo, fdroid_package) = match fdroid {
        Some(Ok((repo, package))) => (Some(repo), Some(package)),
        _ => (None, None),
    };

    // package_name is never taken from admin input (see update_tracked_app's own doc comment) -
    // a brand-new app can't have one known yet anyway, since nothing's been installed to detect
    // it from. Always starts empty and is pinned from the first release's APK (an F-Droid app's
    // APK must also be its `fdroid_package`, `check_fdroid_package`).
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, source_type, github_repo, asset_pattern, \
         include_prereleases, release_tag_prefix, fdroid_repo, fdroid_package) \
         VALUES (?, '', ?, ?, ?, ?, ?, ?, ?) RETURNING id",
    )
    .bind(field("name").trim())
    .bind(source_type)
    .bind(&github_repo)
    .bind(&asset_pattern)
    .bind(include_prereleases)
    .bind(&tag_prefix)
    .bind(&fdroid_repo)
    .bind(&fdroid_package)
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
    /// The upload's result (refused, or done), shown in the Upload group (§3.0's flash).
    upload_note: Option<Flash>,
    /// The package confirmation's result, shown under the package row.
    package_note: Option<Flash>,
    details: DetailsForm,
    /// "0.32.0", from the cached release's tag.
    version: Option<String>,
    /// When the last check (or upload) found a release, local and relative.
    last_checked: Option<When>,
    /// A sync of this app is running since then - "Check now" says so.
    sync_running: Option<When>,
    /// How the last sync (hourly or "Check now") failed, if it did.
    sync_failure: Option<FailureView>,
    /// "Show on phones as" (design 14): the default for every phone with the app
    /// (`partials/app_display_form.html` reads `form`).
    form: crate::app_display::AppDisplayForm,
    /// For an app without a package: what the parent may confirm as its package ([package_candidates]).
    package_candidates: Vec<String>,
    /// The phones that have it ticked (every set-up phone for the launcher).
    phones: Vec<PhoneRow>,
    /// "0796f180…65ef4be3"; the whole key is in the ⓘ panel.
    signer_short: Option<String>,
    /// Source & signing starts open when a refused save or upload is in it.
    source_open: bool,
    /// `?install=<word>` for a phone without a row (design 37): shown at the group's top.
    install_flash: Option<(&'static str, bool)>,
    /// A launcher row is waiting for its phone: `static/install-now.js` polls.
    install_polling: bool,
    /// Source & Signing's value: "GitHub", "F-Droid" (or another repo's host), "Uploaded".
    source_label: String,
    /// An F-Droid app's source row: "F-Droid · ws.xsoh.etar", the package's page on f-droid.org,
    /// and the one-source line under it.
    fdroid_source: Option<String>,
    fdroid_page: Option<String>,
    fdroid_note: &'static str,
}

/// A local time on a page: "2 h ago" and the exact time for `title=` and ⓘ panels.
struct When {
    text: String,
    exact: String,
}

impl When {
    fn of(
        zone: &crate::display_time::Zone,
        at: &str,
        now: chrono::DateTime<chrono::Utc>,
    ) -> Option<Self> {
        let at = crate::display_time::parse_db_utc(at)?;
        Some(When {
            text: zone.relative(at, now),
            exact: zone.exact(at),
        })
    }
}

/// The last failed sync: a short title, the raw error behind ⓘ (§4.4).
struct FailureView {
    title: &'static str,
    detail: String,
    when: Option<When>,
}

/// A form's result, in the group of that form (§3.0's flash): a short title, the reason behind
/// ⓘ; green when `ok`.
struct Flash {
    title: String,
    detail: Option<String>,
    ok: bool,
}

impl Flash {
    fn title(title: &str) -> Self {
        Flash {
            title: title.to_string(),
            detail: None,
            ok: false,
        }
    }

    fn done(title: &str) -> Self {
        Flash {
            ok: true,
            ..Flash::title(title)
        }
    }
}

impl From<Failure> for Flash {
    fn from(failure: Failure) -> Self {
        Flash {
            title: failure.problem.title().to_string(),
            detail: Some(failure.detail),
            ok: false,
        }
    }
}

/// Which group a form's [Flash] belongs to. Both groups are in Source & Signing, so it opens, and
/// both forms post to an action with the group's fragment (`#upload`, `#release`): a refusal
/// rendered in the POST's answer and the redirect after a success land on the group, where the
/// parent was, with the result in view (qa-31-s11-code M1; §5.1 item 4).
enum Note {
    Upload(Flash),
    Package(Flash),
}

/// The success notes the redirects name (`?notice=`), in their groups.
fn note_of(notice: Option<&str>) -> Option<Note> {
    match notice? {
        "uploaded" => Some(Note::Upload(Flash::done("New version uploaded"))),
        "package_confirmed" => Some(Note::Package(Flash::done("Package confirmed"))),
        _ => None,
    }
}

/// What the Details form shows: the saved values, or - when a save was refused - what was
/// entered, with the reason next to the field.
struct DetailsForm {
    name: String,
    github_repo: String,
    asset_pattern: String,
    /// Design 21: only releases whose tag starts with this (blank = any but server-v*/music-v*).
    release_tag_prefix: String,
    /// An F-Droid app's repo and package.
    fdroid_repo: String,
    fdroid_package: String,
    error: Option<String>,
    /// The field the error belongs to ("asset_pattern", "release_tag_prefix", "fdroid_repo",
    /// "fdroid_package", or "source_type" - a refused source change, above the fields).
    error_field: &'static str,
}

/// `?open=display|source`: a save returns with its `<details>` open ("Show on phones as", Source
/// & Signing), so the form it came from is where the parent left it.
pub async fn view_tracked_app(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    axum::extract::Query(query): axum::extract::Query<HashMap<String, String>>,
) -> impl IntoResponse {
    let note = note_of(query.get("notice").map(String::as_str));
    // Install Now's answer (design 37): `?install=<word>&phone=<id>`.
    let install = query.get("install").map(|word| {
        (
            word.as_str(),
            query.get("phone").and_then(|p| p.parse::<i64>().ok()),
        )
    });
    render_detail_full(
        &state,
        id,
        note,
        None,
        None,
        query.get("open").map(String::as_str),
        install,
    )
    .await
}

async fn render_detail(state: &AppState, id: i64, refused: Option<Note>) -> Response {
    render_detail_full(state, id, refused, None, None, None, None).await
}

async fn render_detail_page(state: &AppState, id: i64, details: Option<DetailsForm>) -> Response {
    render_detail_full(state, id, None, details, None, None, None).await
}

/// The app's page (design 31 §3.3); `display` = a refused "Show on phones as" save (design 14),
/// `open` = the `<details>` a save returns to ("display", "source"), `install` = Install Now's
/// answer (design 37: the word and the phone it names).
async fn render_detail_full(
    state: &AppState,
    id: i64,
    refused: Option<Note>,
    details: Option<DetailsForm>,
    display: Option<crate::app_display::Entered>,
    open: Option<&str>,
    install: Option<(&str, Option<i64>)>,
) -> Response {
    let app = match sqlx::query_as::<_, TrackedApp>("SELECT * FROM tracked_apps WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(app) => app,
        Err(err) => return page_read_failed(id, err),
    };

    let Some(app) = app else {
        return (StatusCode::NOT_FOUND, "App not found").into_response();
    };

    let details = details.unwrap_or_else(|| DetailsForm {
        name: app.name.clone(),
        github_repo: app.github_repo.clone(),
        asset_pattern: app.asset_pattern.clone().unwrap_or_default(),
        release_tag_prefix: app.release_tag_prefix.clone().unwrap_or_default(),
        fdroid_repo: app.fdroid_repo.clone().unwrap_or_default(),
        fdroid_package: app.fdroid_package.clone().unwrap_or_default(),
        error: None,
        error_field: "",
    });
    let fdroid_repo = app.fdroid_repo.as_deref().unwrap_or_default();
    let fdroid_package = app.fdroid_package.as_deref().unwrap_or_default();
    let source_label = source_label(&app);
    let is_fdroid = app.source_type == "fdroid";
    let fdroid_source = is_fdroid.then(|| format!("{source_label} · {fdroid_package}"));
    let fdroid_page = is_fdroid
        .then(|| crate::fdroid::package_page(fdroid_repo, fdroid_package))
        .flatten();
    let fdroid_note = if fdroid_page.is_some() || source_label == "F-Droid" {
        "One source per app: F-Droid signs it with its own key, so it can't switch to GitHub or \
         Play without a reinstall."
    } else {
        "One source per app: switching it to GitHub or Play needs a reinstall when the repo's \
         signing key isn't the developer's."
    };
    let zone = crate::display_time::Zone::load(&state.db).await;
    let now = chrono::Utc::now();
    let sync = state.app_syncs.status(id);
    let package_candidates = if app.package_name.is_empty() {
        package_candidates(state, id).await
    } else {
        Vec::new()
    };
    let current = crate::app_display::DisplayValues {
        label: app.display_label.clone(),
        icon: app.display_icon.clone(),
        color: app.display_color.clone(),
    };
    let mut form = crate::app_display::AppDisplayForm::new(
        format!("/apps/tracked/{id}/display#display"),
        "display".to_string(),
        String::new(),
        app.name.clone(),
        &current,
        display.as_ref(),
    );
    form.open |= open == Some("display");
    // The phones with the app ticked; the launcher is on every phone that is set up. A DB error
    // is a 500, never an empty list (design 37 §2.1).
    let devices: Result<Vec<crate::models::Device>, sqlx::Error> = if app.is_launcher {
        sqlx::query_as("SELECT * FROM devices WHERE enrolled_at IS NOT NULL ORDER BY name")
            .fetch_all(&state.db)
            .await
    } else {
        sqlx::query_as(
            "SELECT d.* FROM devices d JOIN device_tracked_apps dta ON dta.device_id = d.id \
             WHERE dta.tracked_app_id = ? ORDER BY d.name",
        )
        .bind(id)
        .fetch_all(&state.db)
        .await
    };
    let devices = match devices {
        Ok(devices) => devices,
        Err(err) => return page_read_failed(id, err),
    };
    let row_package =
        (!app.is_launcher && !app.package_name.is_empty()).then_some(app.package_name.as_str());
    let mut phones: Vec<PhoneRow> = Vec::with_capacity(devices.len());
    // Install Now's word for a phone that has no row here goes to the group's top.
    let mut install_flash = None;
    let mut install_polling = false;
    for device in &devices {
        let mut row = PhoneRow::new(
            device,
            String::new(),
            phone_apps_href(device.id, row_package, AppsFrom::App(id)),
        );
        if app.is_launcher {
            let flash = install
                .filter(|(_, phone)| *phone == Some(device.id))
                .map(|(word, _)| word);
            match crate::handlers::install_now::install_row(
                state, &app, device.id, &zone, now, flash,
            )
            .await
            {
                Ok(install_row) => {
                    install_polling |= install_row.state != "idle";
                    row.install = Some(install_row);
                }
                Err(err) => return page_read_failed(id, err),
            }
        }
        phones.push(row);
    }
    if app.is_launcher
        && let Some((word, phone)) = install
        && !phone.is_some_and(|p| devices.iter().any(|d| d.id == p))
    {
        install_flash = crate::install_now::flash(word);
    }
    let (upload_note, package_note) = match refused {
        Some(Note::Upload(flash)) => (Some(flash), None),
        Some(Note::Package(flash)) => (None, Some(flash)),
        None => (None, None),
    };
    let source_open = upload_note.is_some()
        || package_note.is_some()
        || details.error.is_some()
        || open == Some("source");
    Html(
        TrackedAppDetailTemplate {
            title: app.name.clone(),
            version: app
                .latest_release_tag
                .as_deref()
                .map(|tag| version_of(tag, app.release_tag_prefix.as_deref())),
            last_checked: app
                .last_checked_at
                .as_deref()
                .and_then(|at| When::of(&zone, at, now)),
            sync_running: sync
                .running_since
                .as_deref()
                .and_then(|at| When::of(&zone, at, now)),
            sync_failure: sync.last_error.map(|failed| FailureView {
                title: failed.failure.problem.title(),
                detail: failed.failure.detail,
                when: When::of(&zone, &failed.at, now),
            }),
            signer_short: app.signer_sha256.as_deref().map(|key| {
                if key.len() > 16 && key.is_ascii() {
                    format!("{}…{}", &key[..8], &key[key.len() - 8..])
                } else {
                    key.to_string()
                }
            }),
            form,
            app,
            upload_note,
            package_note,
            details,
            package_candidates,
            phones,
            source_open,
            install_flash,
            install_polling,
            source_label,
            fdroid_source,
            fdroid_page,
            fdroid_note,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

/// What the app's page calls its source: "GitHub", "F-Droid" (another repo: its host),
/// "Uploaded".
fn source_label(app: &TrackedApp) -> String {
    match app.source_type.as_str() {
        "github" => "GitHub".to_string(),
        "fdroid" => crate::fdroid::repo_label(app.fdroid_repo.as_deref().unwrap_or_default()),
        _ => "Uploaded".to_string(),
    }
}

/// The app's page can't be read: a 500, never a page with a made-up part.
fn page_read_failed(id: i64, err: sqlx::Error) -> Response {
    tracing::error!(app_id = id, %err, "can't read the app's page");
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        "Couldn't read this app - check the server log.",
    )
        .into_response()
}

/// How long "Check now" waits for its sync before answering: a quick check shows its result at
/// once, a long download goes on in the background and the page says it is running (qa-catalog
/// #1: the request used to wait up to the 30 min download cap). Under scroll-restore's 20 s, so
/// the page comes back where it was.
const CHECK_NOW_WAIT: Duration = Duration::from_secs(10);

/// Starts a sync of the app (unless one runs - then the page says so) and redirects back to the
/// app's page, which shows a failure or that it is still running.
pub async fn check_now(State(state): State<AppState>, Path(id): Path<i64>) -> Response {
    let exists: Option<i64> = sqlx::query_scalar("SELECT id FROM tracked_apps WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
        .ok()
        .flatten();
    if exists.is_none() {
        return (StatusCode::NOT_FOUND, "App not found").into_response();
    }

    // In its own task: a large download still finishes, and is cached, after this answers.
    let task_state = state.clone();
    let sync = tokio::spawn(async move {
        if let Err(SyncError::Failed(e)) = sync_app(&task_state, id, true).await {
            tracing::warn!("tracked app {id} check failed: {e}");
        }
    });
    let _ = tokio::time::timeout(CHECK_NOW_WAIT, sync).await;
    Redirect::to(&format!("/apps/tracked/{id}#check")).into_response()
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
    // An F-Droid app takes only the repo's APKs, each checked against the repo's index: an
    // upload would be a file nothing checked.
    if app.source_type == "fdroid" {
        let mut page = render_detail(
            &state,
            id,
            Some(Note::Upload(Flash {
                title: "F-Droid apps come from the repo".to_string(),
                detail: Some(
                    "Each release comes from the F-Droid repo and is checked against its \
                     checksum - nothing was uploaded."
                        .to_string(),
                ),
                ok: false,
            })),
        )
        .await;
        if page.status() == StatusCode::OK {
            *page.status_mut() = StatusCode::BAD_REQUEST;
        }
        return page;
    }

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
            Some(Note::Upload(Flash::title("Add a version and a file"))),
        )
        .await;
    };

    if release_label.is_empty() || apk_bytes.is_empty() {
        return render_detail(
            &state,
            id,
            Some(Note::Upload(Flash::title("Add a version and a file"))),
        )
        .await;
    }

    let app_dir = state.tracked_apps_dir.join(id.to_string());
    if tokio::fs::create_dir_all(&app_dir).await.is_err() {
        return render_detail(
            &state,
            id,
            Some(Note::Upload(Flash::title("Couldn't save the file"))),
        )
        .await;
    }
    let file_path = app_dir
        .join(format!("{}.apk", random_label()))
        .to_string_lossy()
        .into_owned();
    if tokio::fs::write(&file_path, &apk_bytes).await.is_err() {
        return render_detail(
            &state,
            id,
            Some(Note::Upload(Flash::title("Couldn't save the file"))),
        )
        .await;
    }

    let identity = match apk_identity(&file_path)
        .await
        .map_err(|err| Failure::new(SyncProblem::Unreadable, err))
        .and_then(|identity| check_pins(&app, &identity).map(|()| identity))
    {
        Ok(identity) => identity,
        Err(err) => {
            tokio::fs::remove_file(&file_path).await.ok();
            return render_detail(&state, id, Some(Note::Upload(err.into()))).await;
        }
    };
    if let Err(err) = check_new_package(&state, &app, &identity).await {
        tokio::fs::remove_file(&file_path).await.ok();
        return render_detail(&state, id, Some(Note::Upload(err.into()))).await;
    }

    let sha256 = hex_digest(Sha256::digest(&apk_bytes));
    let stored = sqlx::query(
        "UPDATE tracked_apps SET latest_release_tag = ?, latest_release_asset_id = NULL, \
         latest_release_file_path = ?, latest_release_asset_name = ?, \
         latest_release_asset_size = ?, latest_release_sha256 = ?, \
         last_checked_at = datetime('now') WHERE id = ?",
    )
    .bind(&release_label)
    .bind(&file_path)
    .bind(&apk_name)
    .bind(apk_bytes.len() as i64)
    .bind(&sha256)
    .bind(id)
    .execute(&state.db)
    .await
    .is_ok_and(|result| result.rows_affected() > 0);
    replace_cached_file(stored, &file_path, app.latest_release_file_path.as_deref()).await;
    if !stored {
        return render_detail(
            &state,
            id,
            Some(Note::Upload(Flash::title("Couldn't save the file"))),
        )
        .await;
    }
    store_pins(&state, &app, &identity).await;
    crate::handlers::device_api::nudge_phones_with_app(&state, id).await;

    // Back to the app's page at the Upload group, with the result there (a reload doesn't post
    // the file again; [Note]).
    Redirect::to(&format!("/apps/tracked/{id}?notice=uploaded#upload")).into_response()
}

/// `POST /apps/tracked/{id}/display` (design 14): the catalog default for how the app shows on the
/// kid's phones (by the row's package name; a phone's own choice replaces it); it may be any of
/// design 34's three states. `action` "reset" (an older page's "own") clears it. Refused input: 400, the page with the entered values, the error by the field and
/// that field focused. Saved: back to the card, open (`?open=display#display`: it doesn't fold up
/// under the parent, the browser pass of 2026-10-14, 6f); nudges every phone (cosmetic, cheap).
pub async fn save_display(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let field = |name: &str| form.get(name).map(String::as_str).unwrap_or("");
    let values = if matches!(field("action"), "reset" | "own") {
        crate::app_display::DisplayValues::empty()
    } else {
        match crate::app_display::validate(field("label"), field("icon"), field("color")) {
            Ok(values) => values,
            Err(error) => {
                let entered = crate::app_display::Entered {
                    label: field("label").to_string(),
                    icon: field("icon").to_string(),
                    color: field("color").to_string(),
                    error,
                };
                let mut page =
                    render_detail_full(&state, id, None, None, Some(entered), None, None).await;
                if page.status() == StatusCode::OK {
                    *page.status_mut() = StatusCode::BAD_REQUEST;
                }
                return page;
            }
        }
    };
    let result = sqlx::query(
        "UPDATE tracked_apps SET display_label = ?, display_icon = ?, display_color = ? \
         WHERE id = ?",
    )
    .bind(&values.label)
    .bind(&values.icon)
    .bind(&values.color)
    .bind(id)
    .execute(&state.db)
    .await;
    match result {
        Ok(r) if r.rows_affected() == 0 => (StatusCode::NOT_FOUND, "App not found").into_response(),
        Ok(_) => {
            // Every phone may have the app: nudge them all (a sync is cheap).
            let devices: Vec<i64> = sqlx::query_scalar("SELECT id FROM devices")
                .fetch_all(&state.db)
                .await
                .unwrap_or_default();
            for device in devices {
                let _ = state.command_notify.send(device);
            }
            Redirect::to(&format!("/apps/tracked/{id}?open=display#display")).into_response()
        }
        Err(err) => {
            tracing::error!(app_id = id, %err, "couldn't save the app's name and icon");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't save - nothing was changed.",
            )
                .into_response()
        }
    }
}

/// Lets an admin fix any of a tracked app's identifying details after creation - there was
/// previously no way to do this short of deleting and re-adding the row (losing its cached
/// release/check history). `source_type` itself isn't editable here - switching between
/// GitHub-tracked, F-Droid and manually-uploaded changes the whole update-fetching model, not just
/// a field, and an F-Droid build is signed with F-Droid's key: a posted `source_type` other than
/// the app's is refused (400, nothing written), worded by whether a release is cached.
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

    // One source per app: a change is refused, the reason by the form's top. With a cached
    // release the phones have it from that source - another one (F-Droid signs with its own key)
    // would need a reinstall; without one, the app is removed and added again.
    let source_refused = fields
        .get("source_type")
        .filter(|source| **source != app.source_type)
        .map(|_| {
            if app.latest_release_tag.is_some() {
                format!(
                    "This app's releases come from {}: its source can't change, since the phones \
                     would have to reinstall it (another source signs it with another key). \
                     Nothing was saved.",
                    source_label(&app)
                )
            } else {
                "An app's source is chosen when it is added: remove the app and add it again \
                 with the other source. Nothing was saved."
                    .to_string()
            }
        });
    let fdroid = if app.source_type == "fdroid" && source_refused.is_none() {
        Some(
            parse_fdroid_source(
                &state,
                &field("fdroid_repo"),
                &field("fdroid_package"),
                Some(app.id),
            )
            .await
            .and_then(|(repo, package)| {
                pinned_fdroid_change(&app, &repo, &package).map(|()| (repo, package))
            }),
        )
    } else {
        None
    };

    let (github_repo, asset_pattern, tag_prefix) = if app.source_type == "github" {
        let repo = normalize_github_repo(field("github_repo").trim());
        let pattern = field("asset_pattern").trim().to_string();
        (
            repo,
            (!pattern.is_empty()).then_some(pattern),
            parse_tag_prefix(&field("release_tag_prefix")),
        )
    } else {
        (
            app.github_repo.clone(),
            app.asset_pattern.clone(),
            Ok(app.release_tag_prefix.clone()),
        )
    };

    // Refused as a whole (400, nothing written): the page comes back with the entered values and
    // the reason in the Details card, that field focused (and so scrolled into view).
    let refused = match (
        source_refused,
        validate_asset_pattern(asset_pattern.as_deref()),
        &tag_prefix,
        &fdroid,
    ) {
        (Some(error), _, _, _) => Some((error, "source_type")),
        (None, Err(error), _, _) => Some((error, "asset_pattern")),
        (None, Ok(()), Err(error), _) => Some((error.clone(), "release_tag_prefix")),
        (None, Ok(()), Ok(_), Some(Err((error, field)))) => Some((error.clone(), *field)),
        (None, Ok(()), Ok(_), _) => None,
    };
    if let Some((error, error_field)) = refused {
        let details = DetailsForm {
            name: field("name"),
            github_repo: field("github_repo"),
            asset_pattern: field("asset_pattern"),
            release_tag_prefix: field("release_tag_prefix"),
            fdroid_repo: field("fdroid_repo"),
            fdroid_package: field("fdroid_package"),
            error: Some(error),
            error_field,
        };
        let mut page = render_detail_page(&state, id, Some(details)).await;
        *page.status_mut() = StatusCode::BAD_REQUEST;
        return page;
    }
    let tag_prefix = tag_prefix.unwrap_or_default();
    let (fdroid_repo, fdroid_package) = match fdroid {
        Some(Ok((repo, package))) => (Some(repo), Some(package)),
        _ => (app.fdroid_repo.clone(), app.fdroid_package.clone()),
    };

    // package_name is deliberately not editable here - it used to be free text ("Android package
    // name (optional)"), and a typo or a missed applicationIdSuffix (confirmed live: the browser
    // fork's real installed package is com.kidsmdm.browser.debug, not the com.kidsmdm.browser an
    // admin reasonably typed) silently broke the unified Apps list's matching with no obvious
    // cause. device_api::status now backfills it automatically the moment a device reports the
    // real package - see that handler's own doc comment - so there's no longer a reason for a
    // human to type it at all. forget_package_name below is the escape hatch if it's ever
    // detected wrong.
    sqlx::query(
        "UPDATE tracked_apps SET name = ?, github_repo = ?, asset_pattern = ?, \
         release_tag_prefix = ?, fdroid_repo = ?, fdroid_package = ? WHERE id = ?",
    )
    .bind(&name)
    .bind(&github_repo)
    .bind(&asset_pattern)
    .bind(&tag_prefix)
    .bind(&fdroid_repo)
    .bind(&fdroid_package)
    .bind(id)
    .execute(&state.db)
    .await
    .ok();

    if app.source_type == "fdroid" && fdroid_repo != app.fdroid_repo {
        remove_unused_fdroid_indexes(&state).await;
    }

    Redirect::to(&format!("/apps/tracked/{id}?open=source#details")).into_response()
}

/// Once an F-Droid app is pinned, its package and repo stay (qa-fdroid-source-code Q-5): another
/// package is another app (the next sync would refuse it as `OtherApp`), and another repo may
/// sign it with another key (`OtherKey`) - a change the phones could only take with a reinstall,
/// so it is refused at the form with the source change's words. Before the first release both
/// stay editable.
fn pinned_fdroid_change(
    app: &TrackedApp,
    repo: &str,
    package: &str,
) -> Result<(), (String, &'static str)> {
    let release = if app.latest_release_tag.is_some() {
        "its release"
    } else {
        "its first release"
    };
    if !app.package_name.is_empty() && package != app.package_name {
        return Err((
            format!(
                "This app is {} - pinned from {release}: another package is another app. Remove \
                 this one and add the other. Nothing was saved.",
                app.package_name
            ),
            "fdroid_package",
        ));
    }
    if app.signer_sha256.is_some() && app.fdroid_repo.as_deref() != Some(repo) {
        return Err((
            format!(
                "This app's signing key is pinned from {release} from {}: another repo may sign \
                 it with another key, and the phones would have to reinstall it. Remove the app \
                 and add it again with the other repo. Nothing was saved.",
                source_label(app)
            ),
            "fdroid_repo",
        ));
    }
    Ok(())
}

/// Removes the cached F-Droid indexes of repos no catalog app uses any more
/// (qa-fdroid-source-code Q-8: f-droid.org's is 67 MB): at startup, after an F-Droid app is
/// removed or moved to another repo. A database error removes nothing. Best-effort.
pub async fn remove_unused_fdroid_indexes(state: &AppState) {
    let repos: Vec<String> = match sqlx::query_scalar(
        "SELECT DISTINCT fdroid_repo FROM tracked_apps \
         WHERE source_type = 'fdroid' AND fdroid_repo IS NOT NULL",
    )
    .fetch_all(&state.db)
    .await
    {
        Ok(repos) => repos,
        Err(err) => {
            tracing::warn!(%err, "can't list the F-Droid repos; no cached index removed");
            return;
        }
    };
    crate::fdroid::remove_unused_indexes(
        &state.tracked_apps_dir.join(crate::fdroid::CACHE_DIR),
        &repos,
    )
    .await;
}

/// The launcher's catalog row as [seed_launcher_row] creates it: the name, the asset filter
/// (the APK name the launcher's release CI attaches) and the tag prefix of its releases.
pub const LAUNCHER_ROW_NAME: &str = "Vibb One";
pub const LAUNCHER_ROW_ASSET: &str = "kids-launcher-mdm.apk";
pub const LAUNCHER_ROW_TAG_PREFIX: &str = "launcher-v";

/// `owner/repo` from a `https://github.com/<owner>/<repo>/releases/...` URL
/// (`LAUNCHER_APK_URL`), `None` for anything else.
pub fn launcher_repo_from_url(url: &str) -> Option<String> {
    let rest = url.trim().strip_prefix("https://")?;
    let rest = rest
        .strip_prefix("github.com/")
        .or_else(|| rest.strip_prefix("www.github.com/"))?;
    let mut parts = rest.split('/');
    let (owner, repo, releases) = (parts.next()?, parts.next()?, parts.next()?);
    let repo = format!("{owner}/{repo}");
    (releases == "releases" && crate::config::is_valid_repo(&repo)).then_some(repo)
}

/// What [seed_launcher_row] did.
#[derive(Debug, PartialEq)]
pub enum LauncherSeed {
    /// A row with `is_launcher = 1` is there (the parent's, or one made before): nothing done.
    Exists,
    /// A row the parent added for the launcher without the flag (id, name): nothing done - no
    /// second row, and the parent's row is left as it is (see [seed_launcher_row]).
    HandMade(i64, String),
    /// `LAUNCHER_APK_URL` isn't a GitHub releases URL: nothing done - the launcher's repo is
    /// unknown, and upstream's is the wrong one for a self-hosted or fork launcher.
    NotGithub,
    /// The row was created, watching `repo`, pinned to `package`.
    Created {
        id: i64,
        repo: String,
        package: String,
    },
}

/// At startup (both deploy kinds): makes sure the launcher has its catalog row, so its
/// self-updates need nothing added under Apps. Only when no row has `is_launcher = 1`:
///
/// - a row that is clearly the launcher already - a GitHub row watching the launcher's repo with
///   the `launcher-v` tag prefix or a filter naming `kids-launcher-mdm`, or any row (an upload's
///   too) pinned to the launcher's package (from `LAUNCHER_ADMIN_COMPONENT`) - is left alone and
///   logged: a second row would serve the launcher twice, and flagging the parent's row would
///   push it to every phone with whatever the parent chose (pre-releases on, say) and would undo
///   a parent who turned the flag off on purpose, at every restart.
/// - `LAUNCHER_APK_URL` not a `github.com/<owner>/<repo>/releases/...` URL: nothing (logged).
/// - else one row is created: "Vibb One", GitHub, that repo, filter `kids-launcher-mdm.apk`, tag
///   prefix `launcher-v`, pre-releases off, enabled, `is_launcher`, and the launcher's package
///   from `LAUNCHER_ADMIN_COMPONENT` (security review M10 then refuses a release of any other
///   package - a test server with the debug component never gets the release launcher); the
///   signing key pins at the first sync.
///
/// The read and the insert share one transaction for two processes starting on one database at
/// once; a later start is safe by the `is_launcher` check alone.
pub async fn seed_launcher_row(
    db: &sqlx::SqlitePool,
    config: &crate::config::ForkConfig,
) -> Result<LauncherSeed, sqlx::Error> {
    let repo = launcher_repo_from_url(&config.launcher_apk_url);
    let package = config
        .launcher_admin_component
        .split_once('/')
        .map(|(package, _)| package)
        .unwrap_or_default();
    let mut tx = db.begin().await?;
    let flagged: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM tracked_apps WHERE is_launcher = 1")
            .fetch_one(&mut *tx)
            .await?;
    if flagged > 0 {
        return Ok(LauncherSeed::Exists);
    }
    let hand_made: Option<(i64, String)> = sqlx::query_as(
        "SELECT id, name FROM tracked_apps WHERE \
           (source_type = 'github' AND LOWER(github_repo) = LOWER(?) \
              AND (release_tag_prefix = ? \
                   OR INSTR(COALESCE(asset_pattern, ''), 'kids-launcher-mdm') > 0)) \
           OR (package_name != '' AND package_name = ?) \
         ORDER BY id LIMIT 1",
    )
    .bind(&repo)
    .bind(LAUNCHER_ROW_TAG_PREFIX)
    .bind(package)
    .fetch_optional(&mut *tx)
    .await?;
    if let Some((id, name)) = hand_made {
        return Ok(LauncherSeed::HandMade(id, name));
    }
    let Some(repo) = repo else {
        return Ok(LauncherSeed::NotGithub);
    };
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, source_type, github_repo, asset_pattern, \
         include_prereleases, release_tag_prefix, enabled, is_launcher) \
         VALUES (?, ?, 'github', ?, ?, 0, ?, 1, 1) RETURNING id",
    )
    .bind(LAUNCHER_ROW_NAME)
    .bind(package)
    .bind(&repo)
    .bind(LAUNCHER_ROW_ASSET)
    .bind(LAUNCHER_ROW_TAG_PREFIX)
    .fetch_one(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(LauncherSeed::Created {
        id,
        repo,
        package: package.to_string(),
    })
}

/// [seed_launcher_row] from `main`, logged; an error is logged and the server starts anyway.
pub async fn ensure_launcher_row(state: &AppState) {
    match seed_launcher_row(&state.db, &state.config).await {
        Ok(LauncherSeed::Exists) => {}
        Ok(LauncherSeed::HandMade(id, name)) => tracing::warn!(
            "no catalog app is marked as the launcher; if '{name}' (id {id}) is the launcher, \
             turn on \"This is the launcher app\" on its page under Apps (no second row is \
             created)"
        ),
        Ok(LauncherSeed::NotGithub) => tracing::warn!(
            "LAUNCHER_APK_URL ({}) isn't a github.com/<owner>/<repo>/releases URL - no catalog \
             row for the launcher was created: add it under Apps and turn on \"This is the \
             launcher app\"",
            state.config.launcher_apk_url
        ),
        Ok(LauncherSeed::Created { repo, package, .. }) => tracing::info!(
            "created the launcher's catalog row ({repo}, {LAUNCHER_ROW_TAG_PREFIX}*, {package})"
        ),
        Err(err) => tracing::error!("couldn't create the launcher's catalog row: {err}"),
    }
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
    // The launcher's row stays GitHub: Vibb One updates itself from its signed GitHub releases,
    // and an F-Droid app is F-Droid's build (the page shows no switch for one).
    sqlx::query(
        "UPDATE tracked_apps SET is_launcher = ? WHERE id = ? \
         AND NOT (? AND source_type = 'fdroid')",
    )
    .bind(is_launcher)
    .bind(id)
    .bind(is_launcher)
    .execute(&state.db)
    .await
    .ok();

    Redirect::to(&format!("/apps/tracked/{id}?open=source#updates"))
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

    Redirect::to(&format!("/apps/tracked/{id}?open=source#updates"))
}

/// Packages the launcher installed on a phone that has this app checked and that no catalog app is
/// yet: what the app's page offers the parent to confirm as the app's package when its APK didn't
/// give one (QA of the security wave, Low 4: a phone's report never sets a package by itself - it
/// could be any phone's claim). Never a system or Play package, nor the launcher itself.
async fn package_candidates(state: &AppState, app_id: i64) -> Vec<String> {
    let reports: Vec<Option<String>> = sqlx::query_scalar(
        "SELECT s.installed_apps_json FROM device_tracked_apps dta JOIN device_status s ON s.id = \
         (SELECT id FROM device_status WHERE device_id = dta.device_id \
          ORDER BY reported_at DESC, id DESC LIMIT 1) \
         WHERE dta.tracked_app_id = ?",
    )
    .bind(app_id)
    .fetch_all(&state.db)
    .await
    .unwrap_or_default();
    let known: std::collections::HashSet<String> =
        sqlx::query_scalar("SELECT package_name FROM tracked_apps WHERE package_name != ''")
            .fetch_all(&state.db)
            .await
            .unwrap_or_default()
            .into_iter()
            .collect();
    let mut candidates: Vec<String> = reports
        .iter()
        .flatten()
        .filter_map(|json| serde_json::from_str::<Vec<crate::models::InstalledApp>>(json).ok())
        .flatten()
        .filter(|app| {
            crate::handlers::device_api::installed_by_launcher(state, app.installer.as_deref())
                && !crate::handlers::device_api::installed_by_launcher(
                    state,
                    Some(&app.package_name),
                )
                && !never_a_catalog_package(&app.package_name)
                && !known.contains(&app.package_name)
        })
        .map(|app| app.package_name)
        .collect();
    candidates.sort();
    candidates.dedup();
    candidates.truncate(10);
    candidates
}

#[derive(Deserialize)]
pub struct ConfirmPackageForm {
    package: String,
}

/// `POST /apps/tracked/{id}/confirm-package`: the parent confirms which package an app without
/// one is, out of [package_candidates]. Then every phone that has the app checked and the launcher
/// installed it on allows it ([crate::handlers::device_api::allow_installed_catalog_apps]).
pub async fn confirm_package(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<ConfirmPackageForm>,
) -> Response {
    if !package_candidates(&state, id).await.contains(&form.package) {
        return (
            StatusCode::BAD_REQUEST,
            render_detail(
                &state,
                id,
                Some(Note::Package(Flash {
                    title: "Vibb One didn't install that app".to_string(),
                    detail: Some(format!(
                        "{} isn't an app Vibb One installed on a phone that has this app. Nothing \
                         was changed.",
                        form.package
                    )),
                    ok: false,
                })),
            )
            .await,
        )
            .into_response();
    }
    if let Err(err) =
        sqlx::query("UPDATE tracked_apps SET package_name = ? WHERE id = ? AND package_name = ''")
            .bind(&form.package)
            .bind(id)
            .execute(&state.db)
            .await
    {
        tracing::error!(app_id = id, %err, "can't store the confirmed package");
        return render_detail(
            &state,
            id,
            Some(Note::Package(Flash::title(
                "Couldn't save. Nothing was changed.",
            ))),
        )
        .await;
    }
    let reports: Vec<(i64, Option<String>)> = sqlx::query_as(
        "SELECT dta.device_id, s.installed_apps_json FROM device_tracked_apps dta \
         JOIN device_status s ON s.id = (SELECT id FROM device_status \
         WHERE device_id = dta.device_id ORDER BY reported_at DESC, id DESC LIMIT 1) \
         WHERE dta.tracked_app_id = ?",
    )
    .bind(id)
    .fetch_all(&state.db)
    .await
    .unwrap_or_default();
    for (device_id, json) in reports {
        let installed: Vec<crate::models::InstalledApp> = json
            .as_deref()
            .and_then(|json| serde_json::from_str(json).ok())
            .unwrap_or_default();
        crate::handlers::device_api::allow_installed_catalog_apps(&state, device_id, &installed)
            .await;
    }
    Redirect::to(&format!(
        "/apps/tracked/{id}?notice=package_confirmed#release"
    ))
    .into_response()
}

/// Forgets the app's pins - its package name and signing key (security review M10) - so the next
/// release (or "Check now") sets them again from its APK: the escape hatch for a wrong package
/// name and for a developer who really changed keys (update_tracked_app doesn't take a typed
/// package name - see that handler's own doc comment for why).
pub async fn forget_package_name(
    State(state): State<AppState>,
    Path(id): Path<i64>,
) -> impl IntoResponse {
    sqlx::query("UPDATE tracked_apps SET package_name = '', signer_sha256 = NULL WHERE id = ?")
        .bind(id)
        .execute(&state.db)
        .await
        .ok();
    // A release refused for the old pins is downloaded again at the next sync.
    state.app_syncs.set_refused(id, None);

    Redirect::to(&format!("/apps/tracked/{id}?open=source#release"))
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

    Redirect::to(&format!("/apps/tracked/{id}?open=source#updates"))
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
        return Redirect::to(&format!("/apps/tracked/{id}#check"));
    }

    let deleted = sqlx::query("DELETE FROM tracked_apps WHERE id = ?")
        .bind(id)
        .execute(&state.db)
        .await
        .is_ok();
    if !deleted {
        return Redirect::to(&format!("/apps/tracked/{id}#remove"));
    }

    // The whole app directory (qa-catalog #4): a download still running for it can then no
    // longer be renamed into place, and its UPDATE matches no row (`sync_one_app`).
    if let Some(path) = &app.latest_release_file_path {
        tokio::fs::remove_file(path).await.ok();
    }
    tokio::fs::remove_dir_all(state.tracked_apps_dir.join(id.to_string()))
        .await
        .ok();
    if app.source_type == "fdroid" {
        remove_unused_fdroid_indexes(&state).await;
    }

    Redirect::to("/apps")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;

    /// The Apps tab's phone rows say where they came from (`?from=`), kept with the app's row on
    /// the phone's page; a package that isn't one is left out, never put in the address.
    #[test]
    fn a_phone_link_says_where_it_came_from() {
        assert_eq!(
            phone_apps_href(5, None, AppsFrom::List),
            "/devices/5/apps?from=apps"
        );
        assert_eq!(
            phone_apps_href(5, Some("io.element.android.x"), AppsFrom::App(9)),
            "/devices/5/apps?from=app:9#app-io.element.android.x"
        );
        assert_eq!(
            phone_apps_href(5, Some("not a package\"><b>"), AppsFrom::App(9)),
            "/devices/5/apps?from=app:9"
        );
    }

    fn release(tag: &str, prerelease: bool, assets: &[(i64, &str)]) -> GithubRelease {
        GithubRelease {
            tag_name: tag.to_string(),
            prerelease,
            draft: false,
            assets: assets
                .iter()
                .map(|(id, name)| ReleaseAsset {
                    id: *id,
                    name: name.to_string(),
                    browser_download_url: format!("https://example.org/{name}"),
                    size: 0,
                    sha256: None,
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
        newest_matching_release(releases, include_prereleases, &filter, None)
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
            None,
        )
        .unwrap();
        assert_eq!((r.tag_name.as_str(), a.id), ("launcher-v0.30.0", 4));

        let (r, a) =
            newest_matching_release(monorepo(), true, &AssetFilter::FirstApk, None).unwrap();
        assert_eq!((r.tag_name.as_str(), a.id), ("launcher-v0.31.0-rc.1", 3));
    }

    /// Design 21: a music-v* release (a newer RC and a stable one) sits above the launcher's. The
    /// launcher row never takes it - with its prefix, and without one (a row 0049 left alone) - and
    /// the music row takes only its own.
    #[test]
    fn launcher_and_music_rows_keep_to_their_own_releases() {
        let releases = || {
            let mut all = vec![
                release("music-v0.0.1-rc.1", true, &[(20, "vibb-music.apk")]),
                release("music-v0.1.0", false, &[(21, "vibb-music.apk")]),
            ];
            all.extend(monorepo());
            all
        };
        let pick = |prereleases: bool, pattern: Option<&str>, prefix: Option<&str>| {
            newest_matching_release(
                releases(),
                prereleases,
                &AssetFilter::parse(pattern).unwrap(),
                prefix,
            )
            .map(|(r, a)| (r.tag_name, a.id))
        };
        let launcher = Some(r"^kids-launcher-mdm\.apk$");
        assert_eq!(
            pick(false, launcher, Some("launcher-v")).unwrap(),
            ("launcher-v0.30.0".to_string(), 4)
        );
        assert_eq!(
            pick(true, None, Some("launcher-v")).unwrap(),
            ("launcher-v0.31.0-rc.1".to_string(), 3)
        );
        // No prefix and no filter (the first .apk): still never the music app.
        assert_eq!(
            pick(true, None, None).unwrap(),
            ("launcher-v0.31.0-rc.1".to_string(), 3)
        );
        assert_eq!(
            pick(false, Some(r"^vibb-music\.apk$"), Some("music-v")).unwrap(),
            ("music-v0.1.0".to_string(), 21)
        );
        assert_eq!(
            pick(true, Some(r"^vibb-music\.apk$"), Some("music-v")).unwrap(),
            ("music-v0.0.1-rc.1".to_string(), 20)
        );
        assert!(pick(false, Some(r"^vibb-music\.apk$"), None).is_err());
        // server-v* stays out whatever the prefix.
        assert!(pick(false, Some("odd"), Some("server-v")).is_err());
    }

    #[test]
    fn release_tag_prefixes_are_checked() {
        assert_eq!(parse_tag_prefix("  "), Ok(None));
        assert_eq!(
            parse_tag_prefix(" launcher-v "),
            Ok(Some("launcher-v".to_string()))
        );
        assert!(parse_tag_prefix("-v").is_err());
        assert!(parse_tag_prefix("a b").is_err());
        assert!(parse_tag_prefix(&"a".repeat(33)).is_err());
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
        // Only an .apk is ever picked: the Play AAB matches neither a regex nor a substring.
        assert!(picked(Some(r"^\d+\.aab$")).is_err());
        assert!(picked(Some("^app-gplay")).is_err());
        assert!(picked(Some("gplay")).is_err());
        let mixed = vec![release(
            "v1",
            false,
            &[(1, "app-release.aab"), (2, "app-release.APK")],
        )];
        assert_eq!(
            pick(mixed, false, Some("^app-release")).unwrap().1,
            "app-release.APK"
        );
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
        assert_eq!(
            reason,
            "not a valid regular expression (unclosed group) - a filter starting with ^ is read \
             as one"
        );
        let message = validate_asset_pattern(Some("^[")).unwrap_err();
        assert!(message.starts_with("The file name filter is not a valid regular expression"));
        assert!(message.ends_with("Nothing was saved."), "{message}");
        // Over the compiled size limit (qa-catalog #5): refused, not compiled with 10 MiB.
        let huge = AssetFilter::parse(Some(r"^(\w{1,100}){1,100}"))
            .err()
            .unwrap();
        assert!(huge.contains("(too complex)"), "{huge}");
        // Over 256 characters: refused whatever kind of filter it is.
        let long = "a".repeat(MAX_PATTERN_CHARS + 1);
        assert_eq!(
            validate_asset_pattern(Some(&long)).unwrap_err(),
            "The file name filter is longer than 256 characters. Nothing was saved."
        );
        assert!(validate_asset_pattern(Some(&format!("^{}", &long[1..]))).is_err());
        assert!(validate_asset_pattern(Some(&long[1..])).is_ok());
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
        let written = stream_to_file(&mut source, target, 19, None, STALL)
            .await
            .unwrap();
        assert_eq!(written.size, 19);
        assert_eq!(std::fs::read(target).unwrap(), b"PK\x03\x04rest-of-the-zip");
        assert_eq!(files_in(dir.path()), ["v1-14.apk"]);
        // The hash of the whole file, as the phones check it (design 13 QA #2).
        assert_eq!(
            written.sha256,
            hex_digest(Sha256::digest(b"PK\x03\x04rest-of-the-zip"))
        );
        assert_eq!(
            sha256_of_file(std::path::Path::new(target)).unwrap(),
            written.sha256
        );
    }

    #[tokio::test]
    async fn a_failed_download_leaves_no_file() {
        let dir = tempfile::tempdir().unwrap();
        let target = dir.path().join("v1-14.apk");
        let target = target.to_str().unwrap();
        let cases: Vec<(Vec<Step>, u64, &str)> = vec![
            // Shorter than the release lists.
            (
                vec![Step::Data(b"PK\x03\x04abc")],
                100,
                "the release lists 100",
            ),
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
            let err = stream_to_file(&mut source, target, expected_size, None, STALL)
                .await
                .unwrap_err();
            assert!(err.detail.contains(error), "{err}");
            assert_eq!(err.problem, SyncProblem::Download, "{err}");
            assert!(
                files_in(dir.path()).is_empty(),
                "{error}: {:?}",
                files_in(dir.path())
            );
        }
    }

    /// A temp file this server can't write (a full SD card, a missing directory) is its own
    /// problem, not a failed download (qa-31-s11-code L1).
    #[tokio::test]
    async fn a_disk_error_is_the_servers_not_the_downloads() {
        let dir = tempfile::tempdir().unwrap();
        let target = dir.path().join("missing").join("v1-14.apk");
        let mut source = Canned::new([Step::Data(b"PK\x03\x04abc")]);
        let err = stream_to_file(&mut source, target.to_str().unwrap(), 0, None, STALL)
            .await
            .unwrap_err();
        assert_eq!(err.problem, SyncProblem::Server, "{err}");
        assert!(err.detail.starts_with("can't create"), "{err}");
    }

    /// The overall cap (or a request that went away) drops the download mid-way: the temp file
    /// goes with it.
    #[tokio::test]
    async fn a_dropped_download_removes_its_temp_file() {
        let dir = tempfile::tempdir().unwrap();
        let target = dir.path().join("v1-14.apk");
        let target = target.to_str().unwrap();
        let mut source = Canned::new([Step::Data(b"PK\x03\x04abc"), Step::Stall]);
        let download = stream_to_file(&mut source, target, 0, None, Duration::from_secs(600));
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
        let asset = ReleaseAsset {
            id: 1,
            name: "big.apk".to_string(),
            // Nothing listens there: an attempt to download would fail differently.
            browser_download_url: "http://127.0.0.1:9/big.apk".to_string(),
            size: MAX_ASSET_BYTES + 1,
            sha256: None,
        };
        let err = download_asset(&asset, target.to_str().unwrap(), false)
            .await
            .unwrap_err();
        assert_eq!(err.problem, SyncProblem::TooBig);
        assert_eq!(
            err.detail,
            "big.apk is 1000 MB, over the 1 GB limit - not downloaded"
        );
        // The title names the limit the check uses.
        assert!(SyncProblem::TooBig.title().contains(&size_limit_text()));
        assert!(files_in(dir.path()).is_empty());
    }

    #[tokio::test]
    async fn partial_downloads_are_removed_at_startup() {
        let root = tempfile::tempdir().unwrap();
        for app in ["1", "2"] {
            std::fs::create_dir(root.path().join(app)).unwrap();
        }
        for name in ["1/v1-14.apk", "1/v2-15.apk.abc.part", "2/v1-3.apk.def.part"] {
            std::fs::File::create(root.path().join(name)).unwrap();
        }
        remove_partial_downloads(root.path()).await;
        assert_eq!(files_in(&root.path().join("1")), ["v1-14.apk"]);
        assert!(files_in(&root.path().join("2")).is_empty());
        // No directory yet (a fresh install): nothing to do.
        remove_partial_downloads(&root.path().join("missing")).await;
    }

    /// Design 31 step 11: a catalog row's version is its tag without the row's prefix and a "v"
    /// before a digit; a manual label stays as typed.
    #[test]
    fn a_release_tag_reads_as_a_version() {
        assert_eq!(version_of("launcher-v0.32.0", Some("launcher-v")), "0.32.0");
        assert_eq!(
            version_of("music-v0.3.0-rc.1", Some("music-v")),
            "0.3.0-rc.1"
        );
        assert_eq!(version_of("launcher-v0.32.0", None), "0.32.0");
        assert_eq!(version_of("v26.10.1", None), "26.10.1");
        assert_eq!(version_of("V1.76.0", None), "1.76.0");
        assert_eq!(version_of("1.2.0", None), "1.2.0");
        assert_eq!(version_of("pre-release", None), "pre-release");
        assert_eq!(version_of("vibb", None), "vibb");
        assert_eq!(version_of("launcher-v", Some("launcher-v")), "launcher-v");
        assert_eq!(version_of("v2.1.0-v8a", None), "2.1.0-v8a");
        assert_eq!(version_of("app-v1.0-v2", None), "1.0-v2");
        assert_eq!(capitalised(&phones_text(0)), "No phones");
        assert_eq!(phones_text(1), "1 phone");
        assert_eq!(phones_text(2), "2 phones");
    }

    /// Every sync problem has its own short title (§4.2: a warning title is at most 6 words).
    #[test]
    fn sync_problems_have_short_titles() {
        use SyncProblem::*;
        let all = [
            Filter,
            GithubUnreachable,
            RepoNotFound,
            GithubError,
            NoRelease,
            Download,
            TooBig,
            Unreadable,
            OtherApp,
            OtherKey,
            CantCheck,
            Taken,
            Server,
            Removed,
            FdroidUnknown,
            NotInRepo,
            RepoUnreachable,
            RepoError,
            ChecksumMismatch,
        ];
        let titles: std::collections::HashSet<&str> = all.iter().map(|p| p.title()).collect();
        assert_eq!(titles.len(), all.len());
        for title in titles {
            assert!(title.split_whitespace().count() <= 6, "{title}");
            assert!(!title.ends_with('.'), "{title}");
        }
        let failure = Failure::new(RepoNotFound, "GitHub API returned 404 Not Found");
        assert_eq!(
            failure.to_string(),
            "Repo not found on GitHub: GitHub API returned 404 Not Found"
        );
    }

    /// GitHub's answers as the parent reads them (qa-31-s11-code L2): 404 a wrong repo, anything
    /// else GitHub's error; an invalid stored filter and no matching release are their own kinds.
    #[test]
    fn github_answers_are_classified() {
        use reqwest::StatusCode;
        assert_eq!(
            github_problem(StatusCode::NOT_FOUND),
            SyncProblem::RepoNotFound
        );
        for status in [
            StatusCode::FORBIDDEN,
            StatusCode::TOO_MANY_REQUESTS,
            StatusCode::BAD_GATEWAY,
            StatusCode::INTERNAL_SERVER_ERROR,
            StatusCode::UNAUTHORIZED,
        ] {
            assert_eq!(github_problem(status), SyncProblem::GithubError, "{status}");
        }
        let none = release_from(monorepo(), false, Some("other.apk"), None)
            .err()
            .unwrap();
        assert_eq!(none.problem, SyncProblem::NoRelease);
        assert_eq!(none.detail, "no release with a matching asset found");
        let bad = release_from(monorepo(), false, Some("^["), None)
            .err()
            .unwrap();
        assert_eq!(bad.problem, SyncProblem::Filter);
        assert!(bad.detail.starts_with("the file name filter is"), "{bad}");
        let (release, asset) = release_from(monorepo(), false, None, Some("launcher-v")).unwrap();
        assert_eq!(
            (release.tag_name.as_str(), asset.name.as_str()),
            ("launcher-v0.30.0", "kids-launcher-mdm.apk")
        );
    }

    /// One sync per app (qa-catalog #1): a second start of the same app is refused until the
    /// first one ends - also when its future is dropped - and the last failure is kept.
    #[test]
    fn one_sync_per_app_at_a_time() {
        let syncs = std::sync::Arc::new(AppSyncs::default());
        let first = syncs.start(1).expect("first sync starts");
        assert!(syncs.start(1).is_none());
        let other = syncs.start(2).expect("another app syncs in parallel");
        assert!(syncs.status(1).running_since.is_some());
        first.finish(&Err(Failure::new(
            SyncProblem::GithubError,
            "GitHub API returned 403",
        )));
        let status = syncs.status(1);
        assert!(status.running_since.is_none());
        let failure = status.last_error.unwrap().failure;
        assert_eq!(failure.problem, SyncProblem::GithubError);
        assert_eq!(failure.detail, "GitHub API returned 403");

        let again = syncs.start(1).expect("free again");
        drop(again);
        assert!(syncs.start(1).is_some_and(|g| {
            g.finish(&Ok(()));
            true
        }));
        assert!(syncs.status(1).last_error.is_none());
        drop(other);
        assert!(syncs.status(2).running_since.is_none());
    }

    /// The old file goes only after the row names the new one; a row deleted meanwhile takes the
    /// new file with it (qa-catalog #4, #7).
    #[tokio::test]
    async fn the_cached_file_is_replaced_after_the_update() {
        let dir = tempfile::tempdir().unwrap();
        let path = |name: &str| dir.path().join(name).to_str().unwrap().to_string();
        for name in ["old.apk", "new.apk"] {
            std::fs::File::create(dir.path().join(name)).unwrap();
        }
        replace_cached_file(true, &path("new.apk"), Some(&path("old.apk"))).await;
        assert_eq!(files_in(dir.path()), ["new.apk"]);
        replace_cached_file(true, &path("new.apk"), Some(&path("new.apk"))).await;
        assert_eq!(files_in(dir.path()), ["new.apk"]);
        std::fs::File::create(dir.path().join("newer.apk")).unwrap();
        replace_cached_file(false, &path("newer.apk"), Some(&path("new.apk"))).await;
        assert_eq!(files_in(dir.path()), ["new.apk"]);
    }

    /// QA of the security wave, Low 5: a release refused for its key isn't downloaded again every
    /// hour - only when "Check now" asks, or after "Forget".
    #[tokio::test]
    async fn a_refused_release_is_not_downloaded_again_every_hour() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        let app = crate::tests::TestApp::new().await;
        let downloads = std::sync::Arc::new(AtomicUsize::new(0));
        let counted = downloads.clone();
        let apk = std::fs::read("testdata/apk/fixture-key2.apk").unwrap();
        let size = apk.len() as u64;
        let server = axum::Router::new().route(
            "/app.apk",
            axum::routing::get(move || {
                counted.fetch_add(1, Ordering::SeqCst);
                let apk = apk.clone();
                async move { apk }
            }),
        );
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}/app.apk", listener.local_addr().unwrap());
        tokio::spawn(async move { axum::serve(listener, server).await.unwrap() });
        let id: i64 = sqlx::query_scalar(
            "INSERT INTO tracked_apps (name, package_name, github_repo, signer_sha256) VALUES \
             ('Fixture', 'me.vibb.testfixture', 'o/r', \
              '0796f1809e476a2ce724f1341bcb32d03185b7a202c3469787c5775d65ef4be3') RETURNING id",
        )
        .fetch_one(&app.db)
        .await
        .unwrap();
        let row = || async {
            sqlx::query_as::<_, TrackedApp>("SELECT * FROM tracked_apps WHERE id = ?")
                .bind(id)
                .fetch_one(&app.db)
                .await
                .unwrap()
        };
        let asset = ReleaseAsset {
            id: 77,
            name: "app.apk".to_string(),
            browser_download_url: url,
            size,
            sha256: None,
        };

        let first = sync_release(&app.state, &row().await, "v2", &asset, false).await;
        let first = first.unwrap_err();
        assert_eq!(first.problem, SyncProblem::OtherKey);
        assert!(first.detail.contains("different key"));
        assert_eq!(downloads.load(Ordering::SeqCst), 1);
        // The hourly sync: not downloaded again, the reason kept.
        let hourly = sync_release(&app.state, &row().await, "v2", &asset, false).await;
        let hourly = hourly.unwrap_err();
        assert_eq!(hourly.problem, SyncProblem::OtherKey, "the reason kept");
        assert!(hourly.detail.contains("not downloaded again"));
        assert_eq!(downloads.load(Ordering::SeqCst), 1);
        // "Check now": downloaded again (and refused again).
        assert!(
            sync_release(&app.state, &row().await, "v2", &asset, true)
                .await
                .is_err()
        );
        assert_eq!(downloads.load(Ordering::SeqCst), 2);
        // "Forget": the next sync takes it, with the new key pinned.
        app.state.app_syncs.set_refused(id, None);
        sqlx::query("UPDATE tracked_apps SET package_name = '', signer_sha256 = NULL WHERE id = ?")
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        sync_release(&app.state, &row().await, "v2", &asset, false)
            .await
            .unwrap();
        assert_eq!(downloads.load(Ordering::SeqCst), 3);
        assert_eq!(row().await.latest_release_tag.as_deref(), Some("v2"));
    }

    /// A newly stored release nudges exactly the enrolled phones whose list carries the app
    /// (`device_api::scoped_apps`: ticked, or every phone for the launcher); a sync that finds
    /// what's cached, and a refused release, nudge none.
    #[tokio::test]
    async fn a_new_release_nudges_the_phones_that_get_the_app() {
        let app = crate::tests::TestApp::new().await;
        let server = axum::Router::new()
            .route(
                "/key1.apk",
                axum::routing::get(|| async {
                    std::fs::read("testdata/apk/fixture-key1.apk").unwrap()
                }),
            )
            .route(
                "/key2.apk",
                axum::routing::get(|| async {
                    std::fs::read("testdata/apk/fixture-key2.apk").unwrap()
                }),
            );
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        tokio::spawn(async move { axum::serve(listener, server).await.unwrap() });
        let asset = |id: i64, file: &str| ReleaseAsset {
            id,
            name: format!("{file}.apk"),
            browser_download_url: format!("{base}/{file}.apk"),
            size: std::fs::metadata(format!("testdata/apk/fixture-{file}.apk"))
                .unwrap()
                .len(),
            sha256: None,
        };
        let id: i64 = sqlx::query_scalar(
            "INSERT INTO tracked_apps (name, package_name, github_repo) \
             VALUES ('Fixture', '', 'o/r') RETURNING id",
        )
        .fetch_one(&app.db)
        .await
        .unwrap();
        let row = || async {
            sqlx::query_as::<_, TrackedApp>("SELECT * FROM tracked_apps WHERE id = ?")
                .bind(id)
                .fetch_one(&app.db)
                .await
                .unwrap()
        };
        let (ticked, _) = app.enrolled_device("Anna").await;
        let (other, _) = app.enrolled_device("Ola").await;
        let (not_enrolled, _) = app.create_device("Kari").await;
        for device in [ticked, not_enrolled] {
            sqlx::query(
                "INSERT INTO device_tracked_apps (device_id, tracked_app_id) VALUES (?, ?)",
            )
            .bind(device)
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        }
        let mut nudges = app.state.command_notify.subscribe();
        let mut nudged = || {
            let mut devices = Vec::new();
            while let Ok(device) = nudges.try_recv() {
                devices.push(device);
            }
            devices.sort();
            devices
        };

        sync_release(&app.state, &row().await, "v1", &asset(1, "key1"), false)
            .await
            .unwrap();
        assert_eq!(
            nudged(),
            vec![ticked],
            "only the enrolled phone that has it"
        );
        // The same (tag, asset id) again: a no-op check, no nudge.
        sync_release(&app.state, &row().await, "v1", &asset(1, "key1"), false)
            .await
            .unwrap();
        assert_eq!(nudged(), Vec::<i64>::new());
        // A release signed with another key is refused: nothing new for the phones.
        assert!(
            sync_release(&app.state, &row().await, "v2", &asset(2, "key2"), false)
                .await
                .is_err()
        );
        assert_eq!(nudged(), Vec::<i64>::new());
        // The launcher's row is on every enrolled phone.
        sqlx::query("UPDATE tracked_apps SET is_launcher = 1 WHERE id = ?")
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        sync_release(&app.state, &row().await, "v3", &asset(3, "key1"), false)
            .await
            .unwrap();
        let mut both = vec![ticked, other];
        both.sort();
        assert_eq!(nudged(), both);
    }

    fn fork_config(vars: &[(&str, &str)]) -> crate::config::ForkConfig {
        crate::config::ForkConfig::from_vars(
            &vars
                .iter()
                .map(|(k, v)| (k.to_string(), v.to_string()))
                .collect(),
        )
    }

    async fn all_rows(db: &sqlx::SqlitePool) -> Vec<TrackedApp> {
        sqlx::query_as("SELECT * FROM tracked_apps ORDER BY id")
            .fetch_all(db)
            .await
            .unwrap()
    }

    #[test]
    fn the_launchers_repo_comes_from_a_github_releases_url() {
        let repo = |url: &str| launcher_repo_from_url(url);
        assert_eq!(
            repo(crate::config::DEFAULT_LAUNCHER_APK_URL).as_deref(),
            Some("palchrb/vibb-launcher")
        );
        assert_eq!(
            repo("https://github.com/someone/my-fork/releases/download/launcher-v1.0.0/x.apk")
                .as_deref(),
            Some("someone/my-fork")
        );
        assert_eq!(
            repo("https://www.github.com/a/b/releases/latest/download/kids-launcher-mdm.apk")
                .as_deref(),
            Some("a/b")
        );
        for url in [
            "https://example.org/launcher.apk",
            "https://github.com/a/b/raw/main/x.apk",
            "https://github.com/a/releases",
            "http://github.com/a/b/releases/latest/download/x.apk",
            "https://github.com.evil.org/a/b/releases/x.apk",
            "https://github.com/a;rm/b/releases/x.apk",
        ] {
            assert_eq!(repo(url), None, "{url}");
        }
    }

    /// A fresh database gets exactly one launcher row, with these fields, and later starts
    /// (idempotent) change nothing.
    #[tokio::test]
    async fn a_fresh_database_gets_the_launchers_row_once() {
        let app = crate::tests::TestApp::new().await;
        let config = fork_config(&[]);
        let LauncherSeed::Created { id, repo, package } =
            seed_launcher_row(&app.db, &config).await.unwrap()
        else {
            panic!("no row created");
        };
        assert_eq!(
            (repo.as_str(), package.as_str()),
            ("palchrb/vibb-launcher", "me.vibb.launcher")
        );
        let rows = all_rows(&app.db).await;
        assert_eq!(rows.len(), 1);
        let row = &rows[0];
        assert_eq!(row.id, id);
        assert_eq!(row.name, "Vibb One");
        assert_eq!(row.source_type, "github");
        assert_eq!(row.github_repo, "palchrb/vibb-launcher");
        assert_eq!(row.asset_pattern.as_deref(), Some("kids-launcher-mdm.apk"));
        assert_eq!(row.release_tag_prefix.as_deref(), Some("launcher-v"));
        assert!(!row.include_prereleases);
        assert!(row.enabled);
        assert!(row.is_launcher);
        // The launcher's package from LAUNCHER_ADMIN_COMPONENT (M10 refuses any other); the key
        // pins at the first sync.
        assert_eq!(row.package_name, "me.vibb.launcher");
        assert_eq!(row.signer_sha256, None);
        assert_eq!(row.latest_release_tag, None);

        for _ in 0..2 {
            assert_eq!(
                seed_launcher_row(&app.db, &config).await.unwrap(),
                LauncherSeed::Exists
            );
        }
        assert_eq!(all_rows(&app.db).await.len(), 1);
    }

    /// The parent's own launcher row (any name, repo or settings) is never touched, and no second
    /// row is made.
    #[tokio::test]
    async fn an_existing_launcher_row_is_left_alone() {
        let app = crate::tests::TestApp::new().await;
        sqlx::query(
            "INSERT INTO tracked_apps (name, package_name, github_repo, include_prereleases, \
             enabled, is_launcher) VALUES ('My launcher', 'me.vibb.launcher', 'me/other', 1, 0, 1)",
        )
        .execute(&app.db)
        .await
        .unwrap();
        assert_eq!(
            seed_launcher_row(&app.db, &fork_config(&[])).await.unwrap(),
            LauncherSeed::Exists
        );
        let after = all_rows(&app.db).await;
        assert_eq!(after.len(), 1);
        assert_eq!(
            (after[0].name.as_str(), after[0].github_repo.as_str()),
            ("My launcher", "me/other")
        );
        assert!(after[0].include_prereleases && !after[0].enabled && after[0].is_launcher);
    }

    /// A row the parent added for the launcher without the flag - the same repo (any case) with
    /// the launcher's tag prefix or file name, or pinned to the launcher's package - stops the
    /// seed: no second row, and that row isn't flagged or changed. Other apps in the same repo
    /// (the music app) or elsewhere don't.
    #[tokio::test]
    async fn a_hand_made_launcher_row_gets_no_duplicate() {
        let hand_made = [
            "INSERT INTO tracked_apps (name, package_name, github_repo, release_tag_prefix, \
             include_prereleases) VALUES ('Launcher', '', 'Palchrb/Vibb-Launcher', 'launcher-v', 1)",
            "INSERT INTO tracked_apps (name, package_name, github_repo, asset_pattern) \
             VALUES ('Launcher', '', 'palchrb/vibb-launcher', 'kids-launcher-mdm.apk')",
            "INSERT INTO tracked_apps (name, package_name, github_repo) \
             VALUES ('Launcher', 'me.vibb.launcher', 'someone/fork')",
            // QA L1: an upload row pinned to the launcher's package.
            "INSERT INTO tracked_apps (name, package_name, github_repo, source_type) \
             VALUES ('Launcher', 'me.vibb.launcher', '', 'manual')",
        ];
        for insert in hand_made {
            let app = crate::tests::TestApp::new().await;
            let id: i64 = sqlx::query_scalar(&format!("{insert} RETURNING id"))
                .fetch_one(&app.db)
                .await
                .unwrap();
            let before = all_rows(&app.db).await;
            for _ in 0..2 {
                assert_eq!(
                    seed_launcher_row(&app.db, &fork_config(&[])).await.unwrap(),
                    LauncherSeed::HandMade(id, "Launcher".to_string()),
                    "{insert}"
                );
            }
            ensure_launcher_row(&app.state).await;
            let after = all_rows(&app.db).await;
            assert_eq!(after.len(), 1, "{insert}");
            assert!(!after[0].is_launcher, "{insert}");
            assert_eq!(
                after[0].include_prereleases, before[0].include_prereleases,
                "{insert}"
            );
        }

        // The music app in the same repo, an app elsewhere, and a launcher-looking row in another
        // repo without the launcher's package: the launcher's row is made.
        let app = crate::tests::TestApp::new().await;
        sqlx::query(
            "INSERT INTO tracked_apps (name, package_name, github_repo, asset_pattern, \
             release_tag_prefix) VALUES ('Vibb music', 'me.vibb.music', 'palchrb/vibb-launcher', \
             'vibb-music.apk', 'music-v'), ('Element', '', 'element-hq/element-x-android', NULL, \
             NULL), ('Fork', '', 'someone/fork', 'kids-launcher-mdm.apk', 'launcher-v')",
        )
        .execute(&app.db)
        .await
        .unwrap();
        assert!(matches!(
            seed_launcher_row(&app.db, &fork_config(&[])).await.unwrap(),
            LauncherSeed::Created { .. }
        ));
        assert_eq!(all_rows(&app.db).await.len(), 4);
    }

    /// `LAUNCHER_APK_URL` on another GitHub repo: the row watches that repo. Not a GitHub
    /// releases URL: the default repo (`derived` false, which `ensure_launcher_row` logs).
    #[tokio::test]
    async fn the_row_watches_the_repo_of_launcher_apk_url() {
        let app = crate::tests::TestApp::new().await;
        let config = fork_config(&[(
            "LAUNCHER_APK_URL",
            "https://github.com/someone/my-fork/releases/latest/download/kids-launcher-mdm.apk",
        )]);
        assert!(matches!(
            seed_launcher_row(&app.db, &config).await.unwrap(),
            LauncherSeed::Created { ref repo, .. } if repo == "someone/my-fork"
        ));
        assert_eq!(all_rows(&app.db).await[0].github_repo, "someone/my-fork");

        // QA M2: not a GitHub releases URL - no row (never upstream's repo), a warning.
        let app = crate::tests::TestApp::new().await;
        let config = fork_config(&[("LAUNCHER_APK_URL", "https://example.org/launcher.apk")]);
        for _ in 0..2 {
            assert_eq!(
                seed_launcher_row(&app.db, &config).await.unwrap(),
                LauncherSeed::NotGithub
            );
        }
        assert!(all_rows(&app.db).await.is_empty());
    }

    /// QA M1: the row is pinned to `LAUNCHER_ADMIN_COMPONENT`'s package, so a test server with
    /// the debug component refuses the release launcher (M10) instead of pushing it to phones.
    #[tokio::test]
    async fn the_row_is_pinned_to_the_launchers_package() {
        for (component, package) in [
            (
                "me.vibb.launcher/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver",
                "me.vibb.launcher",
            ),
            (
                "me.vibb.launcher.debug/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver",
                "me.vibb.launcher.debug",
            ),
        ] {
            let app = crate::tests::TestApp::new().await;
            let config = fork_config(&[("LAUNCHER_ADMIN_COMPONENT", component)]);
            assert!(matches!(
                seed_launcher_row(&app.db, &config).await.unwrap(),
                LauncherSeed::Created { package: ref p, .. } if p == package
            ));
            let row = all_rows(&app.db).await.remove(0);
            assert_eq!(row.package_name, package);
            assert_eq!(row.signer_sha256, None);
        }
    }

    /// A database error (here: no table) is an `Err` that `ensure_launcher_row` logs - nothing
    /// panics, the server starts.
    #[tokio::test]
    async fn a_database_error_does_not_stop_the_start() {
        let app = crate::tests::TestApp::new().await;
        sqlx::query("ALTER TABLE tracked_apps RENAME TO tracked_apps_gone")
            .execute(&app.db)
            .await
            .unwrap();
        assert!(seed_launcher_row(&app.db, &fork_config(&[])).await.is_err());
        ensure_launcher_row(&app.state).await;
    }

    /// The created row, as stored, picks only the newest stable launcher release - never a newer
    /// `music-v*` or `server-v*` release, even one carrying a `kids-launcher-mdm.apk`, nor a
    /// launcher RC.
    #[tokio::test]
    async fn the_created_row_takes_only_stable_launcher_releases() {
        let app = crate::tests::TestApp::new().await;
        seed_launcher_row(&app.db, &fork_config(&[])).await.unwrap();
        let row = all_rows(&app.db).await.remove(0);
        let mut releases = vec![
            release("server-v0.26.0", false, &[(30, "kids-launcher-mdm.apk")]),
            release("music-v0.2.0", false, &[(31, "kids-launcher-mdm.apk")]),
            release("music-v0.1.0", false, &[(21, "vibb-music.apk")]),
        ];
        releases.extend(monorepo());
        let (r, a) = newest_matching_release(
            releases,
            row.include_prereleases,
            &AssetFilter::parse(row.asset_pattern.as_deref()).unwrap(),
            row.release_tag_prefix.as_deref(),
        )
        .unwrap();
        assert_eq!((r.tag_name.as_str(), a.id), ("launcher-v0.30.0", 4));
        for tag in ["server-v0.26.0", "music-v0.2.0", "music-v0.1.0"] {
            assert!(!release_allowed(tag, row.release_tag_prefix.as_deref()));
        }
    }

    // --------------------------------------------------------------------------------------------
    // The F-Droid source (crate::fdroid): the whole sync against an in-process repo
    // --------------------------------------------------------------------------------------------

    /// The test fixture APK (package `me.vibb.testfixture`) and its SHA-256.
    fn fixture_apk() -> (Vec<u8>, String) {
        let apk = std::fs::read("testdata/apk/fixture-key1.apk").unwrap();
        let sha = hex::encode(Sha256::digest(&apk));
        (apk, sha)
    }

    /// A repo serving the fixture as version 57 ("1.0.57") of `package`, with a 56 and a beta 58
    /// beside it, and the API suggesting 57; the index lists `listed_sha` for 57.
    fn serve_fixture(
        fake: &crate::fdroid::tests::FakeRepo,
        package: &str,
        listed_sha: &str,
        apk: &[u8],
    ) {
        serve_fixture_named(fake, package, listed_sha, apk, "1.0.57");
    }

    /// [serve_fixture] with version 57 named `name`.
    fn serve_fixture_named(
        fake: &crate::fdroid::tests::FakeRepo,
        package: &str,
        listed_sha: &str,
        apk: &[u8],
        name: &str,
    ) {
        let other = |n: u8| format!("{n:02x}").repeat(32);
        let file = |code: i64| format!("{package}_{code}.apk");
        let mut index: serde_json::Value =
            serde_json::from_str(&crate::fdroid::tests::index_json(&[(
                package,
                vec![
                    (other(1), 56, "1.0.56", file(56), false),
                    (listed_sha.to_string(), 57, name, file(57), false),
                    (other(2), 58, "1.0.58-beta", file(58), true),
                ],
            )]))
            .unwrap();
        index["packages"][package]["versions"][listed_sha]["file"]["size"] =
            serde_json::json!(apk.len());
        fake.publish(&index.to_string());
        fake.files.lock().unwrap().insert(file(57), apk.to_vec());
        fake.api.lock().unwrap().insert(
            package.to_string(),
            format!(
                r#"{{"packageName":"{package}","suggestedVersionCode":57,"packages":[
                {{"versionName":"1.0.58-beta","versionCode":58}},
                {{"versionName":"{name}","versionCode":57}}]}}"#
            ),
        );
    }

    async fn fdroid_row(app: &crate::tests::TestApp, repo: &str, package: &str) -> i64 {
        sqlx::query_scalar(
            "INSERT INTO tracked_apps (name, package_name, source_type, github_repo, fdroid_repo, \
             fdroid_package) VALUES ('Etar', '', 'fdroid', '', ?, ?) RETURNING id",
        )
        .bind(repo)
        .bind(package)
        .fetch_one(&app.db)
        .await
        .unwrap()
    }

    async fn row_of(app: &crate::tests::TestApp, id: i64) -> TrackedApp {
        sqlx::query_as("SELECT * FROM tracked_apps WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap()
    }

    /// f-droid.org's path (the API on 127.0.0.1 in the tests): the API's suggested version, its
    /// hash from the index, the APK checked and stored as a GitHub release is - and a check that
    /// finds the cached version asks for neither the index nor the APK again.
    #[tokio::test]
    async fn an_fdroid_app_syncs_by_the_api_and_the_index() {
        use crate::fdroid::tests::FakeRepo;
        let app = crate::tests::TestApp::new().await;
        let (repo, fake) = crate::fdroid::tests::fake_repo("127.0.0.1").await;
        let (apk, sha) = fixture_apk();
        serve_fixture(&fake, "me.vibb.testfixture", &sha, &apk);
        let id = fdroid_row(&app, &repo, "me.vibb.testfixture").await;
        let (device, _) = app.enrolled_device("Anna").await;
        sqlx::query("INSERT INTO device_tracked_apps (device_id, tracked_app_id) VALUES (?, ?)")
            .bind(device)
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        let mut nudges = app.state.command_notify.subscribe();

        sync_one_app(&app.state, id, false).await.unwrap();
        let row = row_of(&app, id).await;
        assert_eq!(row.latest_release_tag.as_deref(), Some("v1.0.57"));
        assert_eq!(row.latest_release_asset_id, Some(57));
        assert_eq!(row.latest_release_sha256.as_deref(), Some(sha.as_str()));
        assert_eq!(
            row.latest_release_asset_name.as_deref(),
            Some("me.vibb.testfixture_57.apk")
        );
        assert_eq!(row.latest_release_asset_size, Some(apk.len() as i64));
        assert!(row.last_checked_at.is_some());
        let path = row.latest_release_file_path.clone().unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), apk);
        assert!(path.ends_with("v1.0.57-57.apk"), "{path}");
        // Pinned from the APK, like a GitHub release.
        assert_eq!(row.package_name, "me.vibb.testfixture");
        assert!(row.signer_sha256.is_some());
        assert_eq!(
            version_of(row.latest_release_tag.as_deref().unwrap(), None),
            "1.0.57"
        );
        assert_eq!(
            nudges.try_recv().unwrap(),
            device,
            "the phone with it is nudged"
        );
        assert_eq!(
            (
                FakeRepo::count(&fake.api_gets),
                FakeRepo::count(&fake.index_gets),
                FakeRepo::count(&fake.file_gets)
            ),
            (1, 1, 1)
        );

        // The next check: the API says 57 again - no index, no download.
        sync_one_app(&app.state, id, false).await.unwrap();
        assert_eq!(
            (
                FakeRepo::count(&fake.api_gets),
                FakeRepo::count(&fake.entry_gets),
                FakeRepo::count(&fake.index_gets),
                FakeRepo::count(&fake.file_gets)
            ),
            (2, 1, 1, 1)
        );
        assert_eq!(row_of(&app, id).await.latest_release_file_path, Some(path));
    }

    /// App update notices (2026-10-25): a sync that stores a new release over an older one raises
    /// one `app_update_available` (no phone, old and new version); the next sweep and a Check Now
    /// that find the same release raise nothing; an app's first release raises nothing.
    #[tokio::test]
    async fn a_new_release_is_one_notice_never_one_per_sweep() {
        let app = crate::tests::TestApp::new().await;
        let (repo, fake) = crate::fdroid::tests::fake_repo("127.0.0.1").await;
        let (apk, sha) = fixture_apk();
        serve_fixture(&fake, "me.vibb.testfixture", &sha, &apk);
        let id = fdroid_row(&app, &repo, "me.vibb.testfixture").await;
        sync_one_app(&app.state, id, false).await.unwrap();
        let notices = || async {
            sqlx::query_as::<_, (Option<i64>, String, String)>(
                "SELECT device_id, dedup_key, params FROM parent_events \
                 WHERE kind = 'app_update_available' ORDER BY id",
            )
            .fetch_all(&app.db)
            .await
            .unwrap()
        };
        assert!(notices().await.is_empty(), "the first release is no update");

        // As if 1.0.56 were the cached release: the next sync stores 1.0.57 over it.
        sqlx::query(
            "UPDATE tracked_apps SET latest_release_tag = 'v1.0.56', latest_release_asset_id = 56 \
             WHERE id = ?",
        )
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
        sync_one_app(&app.state, id, false).await.unwrap();
        sync_one_app(&app.state, id, false).await.unwrap();
        sync_one_app(&app.state, id, true).await.unwrap();
        let rows = notices().await;
        assert_eq!(rows.len(), 1, "{rows:?}");
        let (device, key, params) = &rows[0];
        assert_eq!(*device, None);
        assert_eq!(key, &format!("{id}:v1.0.57@57"));
        let params: serde_json::Value = serde_json::from_str(params).unwrap();
        assert_eq!(
            (
                params["app"].as_str(),
                params["from"].as_str(),
                params["to"].as_str()
            ),
            (Some("Etar"), Some("1.0.56"), Some("1.0.57"))
        );
    }

    /// qa-pwa-fixes-b-code Q-1: a source that goes back stores an older release over a newer one -
    /// an F-Droid repo dropping its newest build here, through a real sync - and raises nothing.
    #[tokio::test]
    async fn an_older_release_stored_over_a_newer_one_is_no_notice() {
        let app = crate::tests::TestApp::new().await;
        let (repo, fake) = crate::fdroid::tests::fake_repo("127.0.0.1").await;
        let (apk, sha) = fixture_apk();
        serve_fixture(&fake, "me.vibb.testfixture", &sha, &apk);
        let id = fdroid_row(&app, &repo, "me.vibb.testfixture").await;
        // As if 1.0.58 were cached and the repo now offers 1.0.57 only.
        sqlx::query(
            "UPDATE tracked_apps SET latest_release_tag = 'v1.0.58', latest_release_asset_id = 58 \
             WHERE id = ?",
        )
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
        sync_one_app(&app.state, id, false).await.unwrap();
        assert_eq!(
            row_of(&app, id).await.latest_release_tag.as_deref(),
            Some("v1.0.57"),
            "the older release is stored"
        );
        let notices: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM parent_events WHERE kind = 'app_update_available'",
        )
        .fetch_one(&app.db)
        .await
        .unwrap();
        assert_eq!(notices, 0);
    }

    /// Q-1's rule for each source: F-Droid by the versionCode; GitHub by the versions (an RC before
    /// its release), the same version under a newer asset a rolling tag's new build, names that
    /// aren't versions by the asset id.
    #[tokio::test]
    async fn only_a_release_ahead_is_an_update() {
        let app = crate::tests::TestApp::new().await;
        let id: i64 = sqlx::query_scalar(
            "INSERT INTO tracked_apps (name, package_name, github_repo, release_tag_prefix) \
             VALUES ('Vibb One', 'me.vibb.launcher', 'a/b', 'launcher-v') RETURNING id",
        )
        .fetch_one(&app.db)
        .await
        .unwrap();
        let mut row = row_of(&app, id).await;
        let stored = |row: &mut TrackedApp, tag: &str, asset: i64| {
            row.latest_release_tag = Some(tag.to_string());
            row.latest_release_asset_id = Some(asset);
        };
        let at = chrono::Utc::now();
        // GitHub, "Include prereleases" turned off after an RC: the older stable release.
        stored(&mut row, "launcher-v0.36.0-rc.7", 100);
        assert!(!release_ahead(&row, "launcher-v0.35.0", 90));
        assert!(update_available_event(&row, "launcher-v0.35.0", 90, at).is_none());
        // ... and the RC's own release, ahead of it.
        assert!(release_ahead(&row, "launcher-v0.36.0", 120));
        assert!(update_available_event(&row, "launcher-v0.36.0", 120, at).is_some());
        // The newest release deleted on GitHub: the one before it, uploaded earlier.
        row.release_tag_prefix = None;
        stored(&mut row, "v7.3.0", 200);
        assert!(!release_ahead(&row, "v7.2.0", 150));
        // A version back under a newer asset (a re-upload of an old release) is still behind.
        assert!(!release_ahead(&row, "v7.2.0", 250));
        // A rolling tag: the same version, a newer asset.
        stored(&mut row, "pre-release", 5);
        assert!(release_ahead(&row, "pre-release", 6));
        assert!(!release_ahead(&row, "pre-release", 4));
        // Names that aren't versions: by the asset id.
        stored(&mut row, "nightly-2026-10-01", 5);
        assert!(release_ahead(&row, "nightly-2026-10-02", 9));
        assert!(!release_ahead(&row, "nightly-2026-09-30", 3));
        // F-Droid: the versionCode (the asset id), whatever the names say.
        row.source_type = "fdroid".to_string();
        stored(&mut row, "v1.0.58", 58);
        assert!(!release_ahead(&row, "v1.0.57", 57));
        assert!(release_ahead(&row, "v1.0.59", 59));
    }

    /// The launcher's release by its tag's versionCode, the music app's by its version.
    #[tokio::test]
    async fn a_release_is_installed_by_its_versioncode() {
        let app = crate::tests::TestApp::new().await;
        let id: i64 = sqlx::query_scalar(
            "INSERT INTO tracked_apps (name, package_name, github_repo, latest_release_tag, \
             release_tag_prefix) VALUES ('Musikk', 'me.vibb.music', 'a/b', 'music-v0.6.0', \
             'music-v') RETURNING id",
        )
        .fetch_one(&app.db)
        .await
        .unwrap();
        let mut row = row_of(&app, id).await;
        assert!(release_installed(&row, false, 60_009));
        assert!(!release_installed(&row, false, 60_001));
        row.latest_release_tag = Some("launcher-v0.36.0".to_string());
        row.release_tag_prefix = Some("launcher-v".to_string());
        assert!(release_installed(&row, true, 360_009));
        assert!(!release_installed(&row, true, 360_007));
        row.latest_release_tag = None;
        assert!(!release_installed(&row, true, 360_009));
        // No previous release: no notice.
        assert!(update_available_event(&row, "launcher-v0.36.0", 1, chrono::Utc::now()).is_none());
    }

    /// Another repo's path (no API: `localhost` in the tests): the highest stable versionCode in
    /// the index (the beta 58 left out), the API never asked.
    #[tokio::test]
    async fn another_repo_syncs_by_the_index_alone() {
        use crate::fdroid::tests::FakeRepo;
        let app = crate::tests::TestApp::new().await;
        let (repo, fake) = crate::fdroid::tests::fake_repo("localhost").await;
        let (apk, sha) = fixture_apk();
        serve_fixture(&fake, "me.vibb.testfixture", &sha, &apk);
        let id = fdroid_row(&app, &repo, "me.vibb.testfixture").await;

        sync_one_app(&app.state, id, false).await.unwrap();
        let row = row_of(&app, id).await;
        assert_eq!(row.latest_release_tag.as_deref(), Some("v1.0.57"));
        assert_eq!(row.latest_release_asset_id, Some(57));
        assert_eq!(row.latest_release_sha256.as_deref(), Some(sha.as_str()));
        assert_eq!(FakeRepo::count(&fake.api_gets), 0, "no API on this repo");
        // Unchanged: entry.json again, the index from the cache, no download.
        sync_one_app(&app.state, id, false).await.unwrap();
        assert_eq!(
            (
                FakeRepo::count(&fake.entry_gets),
                FakeRepo::count(&fake.index_gets),
                FakeRepo::count(&fake.file_gets)
            ),
            (2, 1, 1)
        );
    }

    /// An APK that isn't what the repo's index lists is never stored: nothing in the app's
    /// directory, no release on the row, and the check's problem says so.
    #[tokio::test]
    async fn an_apk_that_doesnt_match_the_index_is_refused() {
        let app = crate::tests::TestApp::new().await;
        let (repo, fake) = crate::fdroid::tests::fake_repo("127.0.0.1").await;
        let (apk, _) = fixture_apk();
        let wrong = "ab".repeat(32);
        serve_fixture(&fake, "me.vibb.testfixture", &wrong, &apk);
        let id = fdroid_row(&app, &repo, "me.vibb.testfixture").await;

        let err = sync_app(&app.state, id, true).await.err().unwrap();
        let SyncError::Failed(err) = err else {
            panic!("already running")
        };
        assert_eq!(err.problem, SyncProblem::ChecksumMismatch);
        assert_eq!(
            err.problem.title(),
            "Download didn't match the repo's checksum"
        );
        assert!(err.detail.contains(&wrong), "{}", err.detail);
        let row = row_of(&app, id).await;
        assert_eq!(row.latest_release_tag, None);
        assert_eq!(row.latest_release_file_path, None);
        assert_eq!(
            row.signer_sha256, None,
            "nothing pinned from an unverified file"
        );
        let app_dir = app.state.tracked_apps_dir.join(id.to_string());
        assert_eq!(files_in(&app_dir), Vec::<String>::new());
        // The app's page shows the problem's words.
        assert_eq!(
            app.state
                .app_syncs
                .status(id)
                .last_error
                .unwrap()
                .failure
                .problem,
            SyncProblem::ChecksumMismatch
        );
    }

    /// The parent's words for what can go wrong: an unknown package (f-droid.org's API's 404; not
    /// in another repo's index), a repo that doesn't answer, an APK of another package.
    #[tokio::test]
    async fn fdroid_sync_problems_have_plain_words() {
        let app = crate::tests::TestApp::new().await;
        let (api_repo, api_fake) = crate::fdroid::tests::fake_repo("127.0.0.1").await;
        let (index_repo, index_fake) = crate::fdroid::tests::fake_repo("localhost").await;
        let (apk, sha) = fixture_apk();
        serve_fixture(&api_fake, "me.vibb.testfixture", &sha, &apk);
        serve_fixture(&index_fake, "me.vibb.testfixture", &sha, &apk);

        let unknown = fdroid_row(&app, &api_repo, "org.nobody.app").await;
        let err = sync_one_app(&app.state, unknown, false).await.unwrap_err();
        assert_eq!(err.problem, SyncProblem::FdroidUnknown);
        assert_eq!(err.problem.title(), "F-Droid doesn't know this package");
        // Another repo isn't "F-Droid" (Q-9).
        let unknown = fdroid_row(&app, &index_repo, "org.nobody.app").await;
        let err = sync_one_app(&app.state, unknown, false).await.unwrap_err();
        assert_eq!(err.problem, SyncProblem::NotInRepo);
        assert_eq!(err.problem.title(), "The repo doesn't have this package");

        let gone = fdroid_row(&app, "http://127.0.0.1:9/repo", "me.vibb.testfixture").await;
        let err = sync_one_app(&app.state, gone, false).await.unwrap_err();
        assert_eq!(err.problem, SyncProblem::RepoUnreachable);
        assert_eq!(err.problem.title(), "The repo didn't answer");

        // The repo lists the fixture under another package id: refused before it is pinned.
        serve_fixture(&index_fake, "org.other.app", &sha, &apk);
        let other = fdroid_row(&app, &index_repo, "org.other.app").await;
        let err = sync_one_app(&app.state, other, false).await.unwrap_err();
        assert_eq!(err.problem, SyncProblem::OtherApp, "{}", err.detail);
        let row = row_of(&app, other).await;
        assert_eq!(
            (row.package_name.as_str(), row.latest_release_tag),
            ("", None)
        );

        // A stored address that isn't https (only the tests' loopback may be http).
        let plain = fdroid_row(&app, "http://f-droid.org/repo", "ws.xsoh.etar").await;
        assert_eq!(
            sync_one_app(&app.state, plain, false)
                .await
                .unwrap_err()
                .problem,
            SyncProblem::RepoError
        );
    }

    /// Q-7 (M28): a versionName with a slash and a space is a plain file name in the app's own
    /// directory.
    #[tokio::test]
    async fn an_fdroid_version_name_is_file_safe_in_the_cached_path() {
        let app = crate::tests::TestApp::new().await;
        let (repo, fake) = crate::fdroid::tests::fake_repo("localhost").await;
        let (apk, sha) = fixture_apk();
        serve_fixture_named(&fake, "me.vibb.testfixture", &sha, &apk, "1.0/57 beta");
        let id = fdroid_row(&app, &repo, "me.vibb.testfixture").await;
        sync_one_app(&app.state, id, false).await.unwrap();
        let row = row_of(&app, id).await;
        assert_eq!(row.latest_release_tag.as_deref(), Some("v1.0/57 beta"));
        let path = std::path::PathBuf::from(row.latest_release_file_path.unwrap());
        assert_eq!(
            path,
            app.state
                .tracked_apps_dir
                .join(id.to_string())
                .join("v1.0_57_beta-57.apk")
        );
        assert!(path.exists());
    }

    /// Q-3: an APK that doesn't match the index three hourly checks in a row isn't downloaded a
    /// fourth time; "Check Now" still tries, and a matching download clears it all.
    #[tokio::test]
    async fn a_repeated_checksum_mismatch_is_remembered() {
        use crate::fdroid::tests::FakeRepo;
        let app = crate::tests::TestApp::new().await;
        let (repo, fake) = crate::fdroid::tests::fake_repo("localhost").await;
        let (apk, sha) = fixture_apk();
        let wrong = "ab".repeat(32);
        serve_fixture(&fake, "me.vibb.testfixture", &wrong, &apk);
        let id = fdroid_row(&app, &repo, "me.vibb.testfixture").await;
        for n in 1..=3 {
            let err = sync_one_app(&app.state, id, false).await.unwrap_err();
            assert_eq!(err.problem, SyncProblem::ChecksumMismatch);
            assert_eq!(FakeRepo::count(&fake.file_gets), n);
        }
        let err = sync_one_app(&app.state, id, false).await.unwrap_err();
        assert_eq!(err.problem, SyncProblem::ChecksumMismatch);
        assert!(err.detail.contains("3 checks in a row"), "{}", err.detail);
        assert!(
            err.detail.contains("not downloaded again"),
            "{}",
            err.detail
        );
        assert_eq!(
            FakeRepo::count(&fake.file_gets),
            3,
            "the hourly check stops"
        );
        // Check Now tries again.
        sync_one_app(&app.state, id, true).await.unwrap_err();
        assert_eq!(FakeRepo::count(&fake.file_gets), 4);
        // The repo fixed: Check Now stores it, and the next hourly check finds it cached.
        serve_fixture(&fake, "me.vibb.testfixture", &sha, &apk);
        sync_one_app(&app.state, id, true).await.unwrap();
        assert_eq!(
            row_of(&app, id).await.latest_release_sha256.as_deref(),
            Some(sha.as_str())
        );
        sync_one_app(&app.state, id, false).await.unwrap();
        assert_eq!(FakeRepo::count(&fake.file_gets), 5);
        // Two mismatches, then a match: the count starts again.
        let syncs = AppSyncs::default();
        assert_eq!(syncs.mismatched(1, Some(("v1".into(), 1))), 1);
        assert_eq!(syncs.mismatched(1, Some(("v1".into(), 1))), 2);
        assert_eq!(syncs.mismatched(1, None), 0);
        assert_eq!(syncs.mismatched(1, Some(("v1".into(), 1))), 1);
        assert_eq!(
            syncs.mismatched(1, Some(("v2".into(), 2))),
            1,
            "another release"
        );
    }

    /// Q-6: a first release can't pin a package another catalog row has, or that an F-Droid row
    /// is waiting for.
    #[tokio::test]
    async fn a_first_release_cant_take_another_rows_package() {
        let app = crate::tests::TestApp::new().await;
        let identity =
            crate::apk::read_identity(std::path::Path::new("testdata/apk/fixture-key1.apk"))
                .unwrap();
        let insert = |sql: &'static str| {
            let db = app.db.clone();
            async move {
                sqlx::query_scalar::<_, i64>(sql)
                    .fetch_one(&db)
                    .await
                    .unwrap()
            }
        };
        let plain = insert(
            "INSERT INTO tracked_apps (name, package_name, github_repo) \
             VALUES ('Fixture', '', 'o/r') RETURNING id",
        )
        .await;
        let row = row_of(&app, plain).await;
        check_new_package(&app.state, &row, &identity)
            .await
            .unwrap();
        // An F-Droid row waits for the package: the other row's first release is refused.
        let waiting = insert(
            "INSERT INTO tracked_apps (name, package_name, source_type, github_repo, \
             fdroid_repo, fdroid_package) VALUES ('Fixture (F-Droid)', '', 'fdroid', '', \
             'https://f-droid.org/repo', 'me.vibb.testfixture') RETURNING id",
        )
        .await;
        let err = check_new_package(&app.state, &row, &identity)
            .await
            .unwrap_err();
        assert_eq!(err.problem, SyncProblem::Taken);
        assert!(err.detail.contains("Fixture (F-Droid)"), "{}", err.detail);
        // The F-Droid row itself may take it: its own fdroid_package is no other row's.
        let own = row_of(&app, waiting).await;
        sqlx::query("DELETE FROM tracked_apps WHERE id = ?")
            .bind(plain)
            .execute(&app.db)
            .await
            .unwrap();
        check_new_package(&app.state, &own, &identity)
            .await
            .unwrap();
        // Another row pinned to it already.
        insert(
            "INSERT INTO tracked_apps (name, package_name, github_repo) \
             VALUES ('Pinned', 'me.vibb.testfixture', 'o/p') RETURNING id",
        )
        .await;
        let err = check_new_package(&app.state, &own, &identity)
            .await
            .unwrap_err();
        assert_eq!(err.problem, SyncProblem::Taken);
        assert!(err.detail.contains("Pinned"), "{}", err.detail);
    }

    /// Q-1: the redirect rule.
    #[test]
    fn a_redirect_never_leaves_https_and_stops_after_five() {
        let url = |u: &str| reqwest::Url::parse(u).unwrap();
        let start = url("https://github.com/o/r/releases/download/v1/a.apk");
        let cdn = url("https://release-assets.githubusercontent.com/x");
        assert_eq!(
            redirect_verdict(&start, &cdn, 1),
            Ok(()),
            "another host, https"
        );
        assert_eq!(
            redirect_verdict(
                &start,
                &url("http://release-assets.githubusercontent.com/x"),
                1
            ),
            Err("a redirect away from https")
        );
        assert_eq!(
            redirect_verdict(&start, &url("http://github.com/x"), 3),
            Err("a redirect away from https")
        );
        assert_eq!(
            redirect_verdict(&start, &cdn, 5),
            Ok(()),
            "the 5th is followed"
        );
        assert_eq!(
            redirect_verdict(&start, &cdn, 6),
            Err("more than 5 redirects"),
            "the 6th is refused"
        );
        let local = url("http://127.0.0.1:9/r/6");
        assert_eq!(
            redirect_verdict(&local, &url("http://127.0.0.1:9/r/5"), 1),
            Ok(()),
            "http to http (the tests' loopback)"
        );
    }

    #[test]
    fn a_tag_is_safe_in_a_file_name() {
        assert_eq!(file_safe("v1.0.57"), "v1.0.57");
        assert_eq!(file_safe("launcher-v0.36.0-rc.4"), "launcher-v0.36.0-rc.4");
        assert_eq!(file_safe("v1/../../x y"), "v1_.._.._x_y");
    }
}
