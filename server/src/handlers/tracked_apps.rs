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
use crate::handlers::music::DiskGuard;
use crate::i18n::{Lang, Words, t, t_count, t_params};
use crate::models::TrackedApp;
use crate::music::MIN_FREE_BYTES;
use crate::security::{CurrentAdmin, generate_device_token};

/// Where cached APKs live, one directory per app (`AppState.tracked_apps_dir`; a temp dir in tests).
pub const TRACKED_APPS_DIR: &str = "data/tracked_apps";

/// The largest asset a sync downloads: one GitHub lists as bigger is refused before downloading,
/// one without a listed size is stopped once it gets there.
const MAX_ASSET_BYTES: u64 = 1_000_000_000;
/// A manual upload's largest APK (upstream review 2026-11-20): twice a GitHub or F-Droid
/// release's [MAX_ASSET_BYTES]. The upload is the parent's own file in hand and the one way in for
/// a big game (upstream's was 1.2 GB), while the hourly sync's unattended download keeps the
/// smaller cap. Under 2^31 bytes, so the size fits a 32-bit int wherever it travels.
pub(crate) const MAX_UPLOAD_BYTES: u64 = 2_000_000_000;
/// The upload route's body limit (`main.rs`): the APK plus the label and the multipart framing.
pub(crate) const UPLOAD_BODY_LIMIT: usize = MAX_UPLOAD_BYTES as usize + 1_000_000;
/// The longest release label an upload keeps; its field's `maxlength` (code QA M2).
pub(crate) const MAX_LABEL_CHARS: usize = 100;
/// A new release stored (an upload, a sync, an approved held one): the phones' failed installs of
/// the release before say nothing about it any more (code QA L8).
const CLEAR_FAILED_INSTALLS: &str =
    "DELETE FROM device_install_progress WHERE tracked_app_id = ? AND failed = 1";
/// A download that delivers no data for this long fails...
pub(crate) const DOWNLOAD_STALL_TIMEOUT: Duration = Duration::from_secs(60);
/// ...and so does one still running after this, however steadily it trickles.
pub(crate) const DOWNLOAD_TOTAL_TIMEOUT: Duration = Duration::from_secs(30 * 60);

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
    /// The SHA-256 (hex) the download must have, checked before the file is moved into place
    /// ([stream_to_file]): an F-Droid repo's index's, or GitHub's asset `digest` (`sha256:<hex>`,
    /// sent since 2025). `None` - today's path, the size and the ZIP header only - for an asset
    /// GitHub lists without one, or with another algorithm.
    #[serde(default, rename = "digest", deserialize_with = "github_digest")]
    sha256: Option<String>,
    /// An F-Droid APK's signing certificates as the repo's index lists them (`None` for GitHub, or
    /// an index that lists none; `Some(empty)` when none listed is readable): the first pin must be
    /// one of them (Q32, [check_fdroid_package]).
    #[serde(skip)]
    index_signers: Option<Vec<String>>,
    /// When GitHub last changed the asset (RFC 3339; design 90 §6): an APK swapped on an old
    /// release waits from the swap. `None` for F-Droid, or when GitHub sends none.
    #[serde(default)]
    updated_at: Option<String>,
}

/// GitHub's asset `digest`, `sha256:<64 hex>`, as the hex; anything else (null, absent, another
/// algorithm, a malformed value) is no digest.
fn github_digest<'de, D: serde::Deserializer<'de>>(de: D) -> Result<Option<String>, D::Error> {
    let digest: Option<String> = Option::deserialize(de)?;
    Ok(digest.and_then(|digest| {
        let hex = digest.strip_prefix("sha256:")?;
        (hex.len() == 64 && hex.bytes().all(|b| b.is_ascii_hexdigit()))
            .then(|| hex.to_ascii_lowercase())
    }))
}

/// An APK is a ZIP file: anything else (an HTML error page, a truncated download) must never be
/// cached and offered to the phones. `head` is the download's first bytes (at least 4 when there
/// were that many), `len` its length, `expected_size` GitHub's asset size (0 = unknown).
/// [check_apk_download]'s answer for a download that isn't a ZIP at all (the setup QR's check
/// tells it from a network failure by it, `launcher_check`).
pub(crate) const NOT_A_ZIP: &str = "downloaded asset is not an APK (no ZIP header)";

fn check_apk_download(head: &[u8], len: u64, expected_size: u64) -> Result<(), String> {
    if !head.starts_with(b"PK\x03\x04") {
        return Err(NOT_A_ZIP.to_string());
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

/// Why a file name filter is refused ([AssetFilter::parse]).
#[derive(Debug, PartialEq, Eq)]
enum FilterRefusal {
    /// Over [MAX_PATTERN_CHARS].
    TooLong,
    /// A `^` pattern over [MAX_REGEX_BYTES] compiled.
    TooComplex,
    /// A `^` pattern that doesn't compile: the regex crate's reason (its words, not ours).
    NotRegex(String),
}

impl FilterRefusal {
    /// Completes "the file name filter is ...", in `lang`.
    fn words(&self, lang: Lang) -> String {
        let not_regex =
            |detail: &str| t_params(lang, "apps.filter.not_regex", &[("detail", detail)]);
        match self {
            FilterRefusal::TooLong => t_params(
                lang,
                "apps.filter.too_long",
                &[("max", &MAX_PATTERN_CHARS.to_string())],
            ),
            FilterRefusal::TooComplex => not_regex(t(lang, "apps.filter.too_complex")),
            FilterRefusal::NotRegex(detail) => not_regex(detail),
        }
    }
}

impl AssetFilter {
    /// `Err(reason)` ([FilterRefusal::words] completes "the file name filter is ..."): over
    /// [MAX_PATTERN_CHARS], or a `^` pattern that doesn't compile within [MAX_REGEX_BYTES].
    fn parse(pattern: Option<&str>) -> Result<Self, FilterRefusal> {
        let pattern = match pattern {
            None | Some("") => return Ok(Self::FirstApk),
            Some(p) => p,
        };
        if pattern.chars().count() > MAX_PATTERN_CHARS {
            return Err(FilterRefusal::TooLong);
        }
        if !pattern.starts_with('^') {
            return Ok(Self::Contains(pattern.to_string()));
        }
        RegexBuilder::new(pattern)
            .size_limit(MAX_REGEX_BYTES)
            .build()
            .map(Self::Regex)
            .map_err(|e| match e {
                regex::Error::CompiledTooBig(_) => FilterRefusal::TooComplex,
                // The message spans several lines (the pattern, a caret under the spot,
                // "error: ..."); the last one is the reason.
                e => {
                    let text = e.to_string();
                    let reason = text.lines().last().unwrap_or_default().trim();
                    FilterRefusal::NotRegex(
                        reason.strip_prefix("error: ").unwrap_or(reason).to_string(),
                    )
                }
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

/// The add and edit forms' check (see [AssetFilter::parse]), in `lang`. The message says nothing
/// was saved, since both forms refuse the whole save.
fn validate_asset_pattern(lang: Lang, pattern: Option<&str>) -> Result<(), String> {
    AssetFilter::parse(pattern).map(|_| ()).map_err(|reason| {
        t_params(
            lang,
            "apps.filter.refused",
            &[("reason", &reason.words(lang))],
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
    /// When the release was published (RFC 3339; design 90 §6's anchor with the asset's own time).
    #[serde(default)]
    published_at: Option<String>,
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
/// [release_allowed]. Every matching release, newest first (design 90: the sync takes the newest
/// whose wait is over, `catalog_holds::pick`).
async fn fetch_releases(
    github_repo: &str,
    include_prereleases: bool,
    asset_pattern: Option<&str>,
    tag_prefix: Option<&str>,
) -> Result<Vec<(GithubRelease, ReleaseAsset)>, Failure> {
    use SyncProblem::*;
    // An invalid stored filter fails before any request.
    AssetFilter::parse(asset_pattern).map_err(filter_failure)?;
    let client = reqwest::Client::builder()
        .user_agent(crate::net::USER_AGENT)
        .timeout(Duration::from_secs(15))
        .build()
        .map_err(|e| Failure::new(Server, e.to_string()))?;

    let url = format!("https://api.github.com/repos/{github_repo}/releases?per_page=100");
    let response = client
        .get(&url)
        .send()
        .await
        .map_err(|e| Failure::new(GithubUnreachable, e.to_string()))?;
    // This server's own clock against GitHub's (`clock_skew::observe_date`): the phones take the server's time.
    crate::clock_skew::observe_date(
        response
            .headers()
            .get(reqwest::header::DATE)
            .and_then(|v| v.to_str().ok()),
    );
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
    releases_from(releases, include_prereleases, asset_pattern, tag_prefix)
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

/// An invalid stored file name filter as a sync failure (its detail as the log has it).
fn filter_failure(reason: FilterRefusal) -> Failure {
    Failure::new(
        SyncProblem::Filter,
        format!("the file name filter is {}", reason.words(Lang::En)),
    )
}

/// The releases of `releases` (GitHub's list) the row may take, newest first: an invalid filter is
/// `Filter`, none matching `NoRelease`.
fn releases_from(
    releases: Vec<GithubRelease>,
    include_prereleases: bool,
    asset_pattern: Option<&str>,
    tag_prefix: Option<&str>,
) -> Result<Vec<(GithubRelease, ReleaseAsset)>, Failure> {
    let filter = AssetFilter::parse(asset_pattern).map_err(filter_failure)?;
    let matching = matching_releases(releases, include_prereleases, &filter, tag_prefix);
    if matching.is_empty() {
        return Err(Failure::new(
            SyncProblem::NoRelease,
            "no release with a matching asset found",
        ));
    }
    Ok(matching)
}

/// The newest of [releases_from].
#[cfg(test)]
fn release_from(
    releases: Vec<GithubRelease>,
    include_prereleases: bool,
    asset_pattern: Option<&str>,
    tag_prefix: Option<&str>,
) -> Result<(GithubRelease, ReleaseAsset), Failure> {
    releases_from(releases, include_prereleases, asset_pattern, tag_prefix)
        .map(|matching| matching.into_iter().next().expect("not empty"))
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
/// with a letter or digit (e.g. `launcher-v`). `Err` is the form's sentence, in `lang`.
fn parse_tag_prefix(lang: Lang, input: &str) -> Result<Option<String>, String> {
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
        Err(t_params(
            lang,
            "apps.tag_prefix.refused",
            &[("max", &MAX_TAG_PREFIX_CHARS.to_string())],
        ))
    }
}

/// Every release the row may take with its matching asset, in the list's order (newest first).
fn matching_releases(
    releases: Vec<GithubRelease>,
    include_prereleases: bool,
    filter: &AssetFilter,
    tag_prefix: Option<&str>,
) -> Vec<(GithubRelease, ReleaseAsset)> {
    let mut matching: Vec<(GithubRelease, ReleaseAsset)> = releases
        .into_iter()
        .filter(|r| !r.draft && (include_prereleases || !r.prerelease))
        .filter(|r| release_allowed(&r.tag_name, tag_prefix))
        .filter_map(|mut r| {
            let index = r.assets.iter().position(|a| filter.matches(&a.name))?;
            let asset = r.assets.swap_remove(index);
            Some((r, asset))
        })
        .collect();
    // GitHub's list is not newest-first by publication (2026-11-20: `launcher-v0.41.0`, published
    // 08:43, came before `launcher-v0.42.0-rc.1`, published 14:53, so Check Now kept saying "no new
    // version"): newest published first, a release without a readable time last, ties in GitHub's order.
    matching.sort_by_key(|(r, _)| std::cmp::Reverse(published_instant(r)));
    matching
}

/// When a release was published, `None` when GitHub gave no readable time.
fn published_instant(release: &GithubRelease) -> Option<chrono::DateTime<chrono::Utc>> {
    let at = release.published_at.as_deref()?;
    chrono::DateTime::parse_from_rfc3339(at)
        .ok()
        .map(|t| t.with_timezone(&chrono::Utc))
}

/// The newest of [matching_releases].
#[cfg(test)]
fn newest_matching_release(
    releases: Vec<GithubRelease>,
    include_prereleases: bool,
    filter: &AssetFilter,
    tag_prefix: Option<&str>,
) -> Result<(GithubRelease, ReleaseAsset), String> {
    matching_releases(releases, include_prereleases, filter, tag_prefix)
        .into_iter()
        .next()
        .ok_or_else(|| "no release with a matching asset found".to_string())
}

/// One chunk of a download at a time, `None` at the end: the GitHub response, or canned chunks
/// in the tests.
pub(crate) trait ChunkSource {
    async fn next_chunk(&mut self) -> Result<Option<Bytes>, String>;
}

impl ChunkSource for reqwest::Response {
    async fn next_chunk(&mut self) -> Result<Option<Bytes>, String> {
        self.chunk().await.map_err(|e| e.to_string())
    }
}

/// A manual upload's file field as a [ChunkSource]; `too_big` once the route's body limit
/// ([UPLOAD_BODY_LIMIT]) stopped it, `no_space` once the data disk would get below the music
/// upload's floor (`music::MIN_FREE_BYTES`, its [DiskGuard] looking every 64 MB; code QA M1) - each
/// worded as its own message.
struct UploadField<'a, 'd> {
    field: axum::extract::multipart::Field<'a>,
    guard: DiskGuard<'d>,
    received: u64,
    too_big: bool,
    no_space: bool,
}

impl ChunkSource for UploadField<'_, '_> {
    async fn next_chunk(&mut self) -> Result<Option<Bytes>, String> {
        let chunk = self.field.chunk().await.map_err(|err| {
            self.too_big |= err.status() == StatusCode::PAYLOAD_TOO_LARGE;
            err.body_text()
        })?;
        if let Some(chunk) = &chunk {
            self.received += chunk.len() as u64;
            if self.guard.check(self.received).is_err() {
                self.no_space = true;
                return Err("the server's disk would get below its free-space floor".to_string());
            }
        }
        Ok(chunk)
    }
}

/// The release label's field read a chunk at a time, at most [MAX_LABEL_CHARS] characters kept and
/// nothing past them read into memory (code QA M2: `text()` read it whole, up to the body limit).
async fn read_label(mut field: axum::extract::multipart::Field<'_>) -> Option<String> {
    // UTF-8: no character is longer than four bytes.
    let cap = MAX_LABEL_CHARS * 4;
    let mut bytes = Vec::new();
    while bytes.len() < cap {
        let Some(chunk) = field.chunk().await.ok()? else {
            break;
        };
        bytes.extend_from_slice(&chunk[..chunk.len().min(cap - bytes.len())]);
    }
    let label: String = String::from_utf8_lossy(&bytes)
        .trim()
        .chars()
        .take(MAX_LABEL_CHARS)
        .collect();
    Some(label.trim_end().to_string())
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
pub(crate) struct Downloaded {
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
/// F-Droid repo's index, GitHub's asset digest) - exactly that SHA-256, else `ChecksumMismatch`
/// and nothing is kept. A
/// chunk that takes longer than `stall` fails the download. Nothing is held in memory beyond one
/// chunk. Returns the bytes written and their SHA-256, which the phones check the whole file
/// against (design 13 QA #2). The setup QR's check (`launcher_check`) streams the QR's APK through
/// it too.
pub(crate) async fn stream_to_file(
    source: &mut impl ChunkSource,
    final_path: &str,
    expected_size: u64,
    expected_sha256: Option<&str>,
    stall: Duration,
) -> Result<Downloaded, Failure> {
    stream_capped(
        source,
        final_path,
        expected_size,
        expected_sha256,
        stall,
        MAX_ASSET_BYTES,
    )
    .await
}

/// [stream_to_file] with its own cap: a manual upload's is [MAX_UPLOAD_BYTES].
async fn stream_capped(
    source: &mut impl ChunkSource,
    final_path: &str,
    expected_size: u64,
    expected_sha256: Option<&str>,
    stall: Duration,
    max_bytes: u64,
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
        if written > max_bytes {
            return Err(Failure::new(
                TooBig,
                format!(
                    "download stopped at {} MB, over the {} limit",
                    written / 1_000_000,
                    gb_text(max_bytes)
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
                "the download's SHA-256 is {sha256}, the release lists {expected} - not stored"
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
    gb_text(MAX_ASSET_BYTES)
}

/// A whole number of gigabytes: "2 GB".
fn gb_text(bytes: u64) -> String {
    format!("{} GB", bytes / 1_000_000_000)
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
        .user_agent(crate::net::USER_AGENT)
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
    drop_foreign_own_releases(&state).await;
    backfill_version_codes(&state).await;
    backfill_release_permissions(&state).await;
    backfill_split_permissions(&state).await;
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

/// Vibb One's packages, release and debug.
pub(crate) const LAUNCHER_PACKAGES: [&str; 2] = ["me.vibb.launcher", "me.vibb.launcher.debug"];

/// Which of our own apps a catalog row is.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum OwnApp {
    Launcher,
    Music,
}

impl OwnApp {
    /// The packages a release of it may be.
    fn packages(self) -> &'static [&'static str] {
        match self {
            OwnApp::Launcher => &LAUNCHER_PACKAGES,
            OwnApp::Music => &crate::music::MUSIC_PACKAGES,
        }
    }
}

/// Which key a release must be signed with: an app's pinned one, or for our own apps the release
/// certificate behind `LAUNCHER_SIGNATURE_CHECKSUM`, whatever is pinned - never the first signer
/// seen.
#[derive(Clone, Copy, Debug)]
pub(crate) enum KeyRule<'a> {
    Pinned,
    /// One of ours, with the configured checksum (`None`: not set, so no new release passes).
    Ours(Option<&'a str>, OwnApp),
}

/// The [KeyRule] of `app` on this server, for a release that is the package `incoming` (`None`
/// for the cached one). Ours: the launcher's row, the music app's (`catalog_holds::ours`), or any
/// row whose pinned or incoming package is one of ours, whatever its repo.
pub(crate) fn key_rule<'a>(
    state: &'a AppState,
    app: &TrackedApp,
    incoming: Option<&str>,
) -> KeyRule<'a> {
    let pinned = app.package_name.as_str();
    let own = if app.is_launcher {
        Some(OwnApp::Launcher)
    } else if crate::catalog_holds::ours(app, &our_repos(state)) {
        Some(OwnApp::Music)
    } else {
        [Some(pinned), incoming]
            .into_iter()
            .flatten()
            .find_map(|package| {
                [OwnApp::Launcher, OwnApp::Music]
                    .into_iter()
                    .find(|own| own.packages().contains(&package))
            })
    };
    match own {
        Some(own) => KeyRule::Ours(state.config.launcher_signature_checksum.as_deref(), own),
        None => KeyRule::Pinned,
    }
}

/// Whether `signer` (hex) is the certificate `checksum` names.
fn own_signer(checksum: Option<&str>, signer: Option<&str>) -> bool {
    matches!((checksum, signer), (Some(checksum), Some(signer))
        if crate::launcher_check::checksum_matches(checksum, signer))
}

/// Whether a phone may be given `app`'s cached release: one of ours only when its pinned signer
/// is our release certificate. Without the checksum what is cached stays served (the phone checks
/// it against the pinned key); only new releases are refused.
pub(crate) fn servable(state: &AppState, app: &TrackedApp) -> bool {
    match key_rule(state, app, None) {
        KeyRule::Pinned | KeyRule::Ours(None, _) => true,
        KeyRule::Ours(checksum, _) => own_signer(checksum, app.signer_sha256.as_deref()),
    }
}

/// A release must be the app the catalog already knows (security review M10): the same package
/// and the pinned signing key, each readable from the APK once it is pinned (fail closed, like the
/// signer - QA of the security wave). The first release pins what it has; one of ours must be
/// signed with our release certificate ([KeyRule::Ours]), checked first.
pub(crate) fn check_pins(
    app: &TrackedApp,
    identity: &crate::apk::ApkIdentity,
    rule: KeyRule<'_>,
) -> Result<(), Failure> {
    if let KeyRule::Ours(checksum, own) = rule {
        if checksum.is_none() {
            return Err(Failure::new(
                SyncProblem::NoOwnKey,
                "refused: LAUNCHER_SIGNATURE_CHECKSUM isn't set, so this release of Vibb's own \
                 app can't be checked against its release key (DEPLOY.md)",
            ));
        }
        if !own_signer(checksum, identity.signer_sha256.as_deref()) {
            return Err(Failure::new(
                SyncProblem::OwnKey,
                "refused: the release isn't signed with Vibb's release key \
                 (LAUNCHER_SIGNATURE_CHECKSUM)",
            ));
        }
        // The row is that app, even with no package pinned (after "Forget").
        let package = identity.package.as_deref().unwrap_or("");
        if !own.packages().contains(&package) {
            return Err(Failure::new(
                SyncProblem::OtherApp,
                format!(
                    "refused: the release is the Android package {package}, not {}",
                    own.packages().join(" or ")
                ),
            ));
        }
    }
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
    if let KeyRule::Ours(..) = rule {
        return Ok(());
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
/// Settings, the package installer, the AOSP browser and files apps), Play's core, and the names reserved for Vibb
/// One's built-ins (design 82 D1).
pub(crate) fn never_a_catalog_package(package: &str) -> bool {
    package == "android"
        || package.starts_with("com.android.")
        || crate::play::is_play_core(package)
        || crate::builtin::is_reserved(package)
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
    // A first release (or one pinning the package anew) that joins a shared user ID would share
    // the permissions of the other apps of its key in it, which its own list doesn't show; a later
    // one is held for the parent (`catalog_holds::judged_permissions`). Ours are exempt: only our
    // release key signs them.
    let pinning = app.package_name.is_empty() || app.latest_release_tag.is_none();
    if pinning
        && identity.shared_user_id
        && matches!(
            key_rule(state, app, identity.package.as_deref()),
            KeyRule::Pinned
        )
    {
        return Err(Failure::new(
            SyncProblem::CantCheck,
            "refused: the release declares a shared user ID (android:sharedUserId), so what it \
             can use isn't only what it lists",
        ));
    }
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
/// had none, the signer only when none is pinned - for one of ours, our release certificate
/// always, and nothing else. Nothing is allowed by this: a phone allows the app once the launcher
/// has installed it there (`device_api::allow_installed_catalog_apps`), or when the parent checks
/// it.
async fn store_pins(state: &AppState, app: &TrackedApp, identity: &crate::apk::ApkIdentity) {
    let new_package = identity
        .package
        .as_deref()
        .filter(|_| app.package_name.is_empty());
    let (own, new_signer) = match key_rule(state, app, identity.package.as_deref()) {
        KeyRule::Ours(_, own)
            if !own
                .packages()
                .contains(&identity.package.as_deref().unwrap_or("")) =>
        {
            return;
        }
        KeyRule::Ours(checksum, _) => (
            identity
                .signer_sha256
                .as_deref()
                .filter(|signer| own_signer(checksum, Some(signer)))
                .filter(|signer| app.signer_sha256.as_deref() != Some(*signer)),
            None,
        ),
        KeyRule::Pinned => (
            None,
            identity
                .signer_sha256
                .as_deref()
                .filter(|_| app.signer_sha256.is_none()),
        ),
    };
    if new_package.is_none() && own.is_none() && new_signer.is_none() {
        return;
    }
    let stored = sqlx::query(
        "UPDATE tracked_apps SET \
         package_name = CASE WHEN package_name = '' THEN COALESCE(?, '') ELSE package_name END, \
         signer_sha256 = COALESCE(?, signer_sha256, ?) WHERE id = ?",
    )
    .bind(new_package)
    .bind(own)
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

/// Forgets the cached release of one of our own apps whose pinned signer isn't our release
/// certificate (cached before ours were pinned to it, or `LAUNCHER_SIGNATURE_CHECKSUM` changed):
/// no phone is given it, and the next sync downloads the release again and checks it. Run once at
/// startup after [backfill_pins]. Without the checksum nothing is forgotten: what is cached stays
/// served, only new releases are refused ([servable], [check_pins]).
async fn drop_foreign_own_releases(state: &AppState) {
    if state.config.launcher_signature_checksum.is_none() {
        return;
    }
    let apps = match sqlx::query_as::<_, TrackedApp>(
        "SELECT * FROM tracked_apps WHERE latest_release_tag IS NOT NULL",
    )
    .fetch_all(&state.db)
    .await
    {
        Ok(apps) => apps,
        Err(err) => {
            tracing::warn!(%err, "can't list the cached releases to check our own apps' key");
            return;
        }
    };
    for app in apps.iter().filter(|app| !servable(state, app)) {
        tracing::warn!(
            app_id = app.id,
            "the cached release isn't signed with Vibb's release key - forgotten"
        );
        if let Err(err) = sqlx::query(
            "UPDATE tracked_apps SET signer_sha256 = NULL, latest_release_tag = NULL, \
             latest_release_asset_id = NULL WHERE id = ?",
        )
        .bind(app.id)
        .execute(&state.db)
        .await
        {
            tracing::error!(app_id = app.id, %err, "can't forget the cached release");
        }
    }
}

/// Reads the versionCode of releases cached before migration 0139 from the cached file, so a
/// phone that installs it is known to run it (`catalog_release_installed`). The row is only
/// updated while it still names the read file. Run once at startup after the pins; best-effort.
async fn backfill_version_codes(state: &AppState) {
    let rows: Vec<(i64, String)> = match sqlx::query_as(
        "SELECT id, latest_release_file_path FROM tracked_apps \
         WHERE latest_release_file_path IS NOT NULL AND latest_release_version_code IS NULL",
    )
    .fetch_all(&state.db)
    .await
    {
        Ok(rows) => rows,
        Err(err) => {
            tracing::warn!(%err, "can't list the cached files to read the versionCode of");
            return;
        }
    };
    for (id, path) in rows {
        let code = match apk_identity(&path).await {
            Ok(identity) => identity.version_code,
            Err(err) => {
                tracing::warn!(app_id = id, %err, "can't read the cached APK");
                continue;
            }
        };
        let Some(code) = code else {
            continue;
        };
        if let Err(err) = sqlx::query(
            "UPDATE tracked_apps SET latest_release_version_code = ? \
             WHERE id = ? AND latest_release_file_path = ? AND latest_release_version_code IS NULL",
        )
        .bind(code)
        .bind(id)
        .bind(&path)
        .execute(&state.db)
        .await
        {
            tracing::warn!(app_id = id, %err, "can't store the cached APK's versionCode");
        }
    }
}

/// Reads the permissions of releases cached before migration 0141 (design 90 §5) from the cached
/// file and seeds the set the parent accepted with their sensitive names - what the phones run
/// already. A file whose list can't be read leaves both NULL: the next release that asks for
/// anything sensitive is then held (fail closed). The row is only updated while it still names the
/// read file. Run once at startup after the versionCodes; best-effort.
async fn backfill_release_permissions(state: &AppState) {
    let rows: Vec<(i64, String)> = match sqlx::query_as(
        "SELECT id, latest_release_file_path FROM tracked_apps \
         WHERE latest_release_file_path IS NOT NULL AND latest_release_permissions IS NULL \
         AND accepted_permissions IS NULL",
    )
    .fetch_all(&state.db)
    .await
    {
        Ok(rows) => rows,
        Err(err) => {
            tracing::warn!(%err, "can't list the cached files to read the permissions of");
            return;
        }
    };
    for (id, path) in rows {
        let permissions = match apk_identity(&path).await {
            Ok(identity) => identity.permissions,
            Err(err) => {
                tracing::warn!(app_id = id, %err, "can't read the cached APK");
                continue;
            }
        };
        let Some(permissions) = permissions else {
            tracing::warn!(
                app_id = id,
                "the cached APK's permissions can't be read - its next sensitive release waits for \
                 the parent"
            );
            continue;
        };
        if let Err(err) = sqlx::query(
            "UPDATE tracked_apps SET latest_release_permissions = ?, \
             accepted_permissions = COALESCE(accepted_permissions, ?) \
             WHERE id = ? AND latest_release_file_path = ?",
        )
        .bind(crate::catalog_holds::names_json(&permissions))
        .bind(widened(None, &permissions))
        .bind(id)
        .bind(&path)
        .execute(&state.db)
        .await
        {
            tracing::warn!(app_id = id, %err, "can't store the cached APK's permissions");
        }
    }
}

/// Reads each served release's permissions again with the split permissions Android adds for
/// its target SDK, and adds those its accepted names bring to the accepted set
/// ([crate::catalog_holds::with_splits]): a set stored before the reader added them would
/// otherwise hold the next unchanged update once. Idempotent; run at every startup after the
/// permissions backfill; a row is only updated while it still names the read file and set.
/// Best-effort.
async fn backfill_split_permissions(state: &AppState) {
    let rows: Vec<(i64, String, String, Option<String>)> = match sqlx::query_as(
        "SELECT id, latest_release_file_path, accepted_permissions, latest_release_permissions \
         FROM tracked_apps WHERE latest_release_file_path IS NOT NULL \
         AND accepted_permissions IS NOT NULL",
    )
    .fetch_all(&state.db)
    .await
    {
        Ok(rows) => rows,
        Err(err) => {
            tracing::warn!(%err, "can't list the served files to read the split permissions of");
            return;
        }
    };
    for (id, path, accepted_json, served_json) in rows {
        let Some(accepted) = crate::catalog_holds::read_names(Some(&accepted_json)) else {
            continue;
        };
        let identity = match apk_identity(&path).await {
            Ok(identity) => identity,
            Err(err) => {
                tracing::warn!(app_id = id, %err, "can't read the cached APK");
                continue;
            }
        };
        let (Some(served), Some(target)) = (identity.permissions, identity.split_target) else {
            continue;
        };
        let widened = crate::catalog_holds::with_splits(&accepted, &served, target);
        let served_now = crate::catalog_holds::names_json(&served);
        if widened == accepted && served_json.as_deref() == Some(served_now.as_str()) {
            continue;
        }
        if let Err(err) = sqlx::query(
            "UPDATE tracked_apps SET accepted_permissions = ?, latest_release_permissions = ? \
             WHERE id = ? AND latest_release_file_path = ? AND accepted_permissions = ?",
        )
        .bind(crate::catalog_holds::names_json(&widened))
        .bind(&served_now)
        .bind(id)
        .bind(&path)
        .bind(&accepted_json)
        .execute(&state.db)
        .await
        {
            tracing::warn!(app_id = id, %err, "can't store the split permissions");
        }
    }
}

/// At startup (design 90 §17.3 L7): an `.apk` in an app's directory that neither its served
/// release nor its held one names is removed - the served file is never touched -, and a held
/// row whose file is gone is deleted (logged: the next sync downloads the release again).
/// Best-effort; a database error removes nothing.
pub async fn remove_orphan_files(state: &AppState) {
    let rows: Vec<(i64, Option<String>, Option<String>)> = match sqlx::query_as(
        "SELECT t.id, t.latest_release_file_path, h.file_path FROM tracked_apps t \
         LEFT JOIN tracked_app_held_releases h ON h.tracked_app_id = t.id",
    )
    .fetch_all(&state.db)
    .await
    {
        Ok(rows) => rows,
        Err(err) => {
            tracing::warn!(%err, "can't list the apps' files");
            return;
        }
    };
    for (id, served, held) in rows {
        if let Some(held) = &held
            && !tokio::fs::try_exists(held).await.unwrap_or(true)
        {
            tracing::warn!(
                app_id = id,
                "the held release's file is gone - its row removed"
            );
            sqlx::query("DELETE FROM tracked_app_held_releases WHERE tracked_app_id = ?")
                .bind(id)
                .execute(&state.db)
                .await
                .ok();
        }
        let dir = state.tracked_apps_dir.join(id.to_string());
        let Ok(mut files) = tokio::fs::read_dir(&dir).await else {
            continue;
        };
        // By the file's name: the stored path may be spelled another way than the directory's.
        let kept: Vec<std::ffi::OsString> = [&served, &held]
            .into_iter()
            .flatten()
            .filter_map(|path| std::path::Path::new(path).file_name().map(|n| n.to_owned()))
            .collect();
        while let Ok(Some(file)) = files.next_entry().await {
            let path = file.path();
            let path_text = path.to_string_lossy();
            if path_text.ends_with(".apk") && !kept.contains(&file.file_name()) {
                match tokio::fs::remove_file(&path).await {
                    Ok(()) => tracing::info!("removed an orphan release file: {path_text}"),
                    Err(err) => tracing::warn!("can't remove {path_text}: {err}"),
                }
            }
        }
    }
}

/// Design 140 §4.2: at start (after the backfills, before the hourly sync's first pass), every
/// release of ours held for the server. One no longer ahead of the served release (an upload or a
/// newer release overtook it) goes, row and file. One whose stored `min_server` and whose file's
/// own value are both at or below this server's version is served as Approve serves a release -
/// without the parent: the file read and checked again, moved into the served release in one
/// transaction, the old file removed, the phones nudged; Security Log
/// `catalog_release_server_ready`, no inbox row or push. Any other stays held. The version only
/// changes with a restart, so the start is enough.
pub async fn release_server_holds(state: &AppState) {
    let ids: Vec<i64> = match sqlx::query_scalar(
        "SELECT tracked_app_id FROM tracked_app_held_releases WHERE reason = 'server'",
    )
    .fetch_all(&state.db)
    .await
    {
        Ok(ids) => ids,
        Err(err) => {
            tracing::warn!(%err, "can't list the releases held for the server");
            return;
        }
    };
    for id in ids {
        let Some(guard) = state.app_syncs.start(id) else {
            continue;
        };
        let app: Option<TrackedApp> = sqlx::query_as("SELECT * FROM tracked_apps WHERE id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await
            .ok()
            .flatten();
        let held = held_release(state, id).await.ok().flatten();
        let (Some(app), Some(held)) = (app, held) else {
            continue;
        };
        let Some(stored) = held_for_server(&held) else {
            continue;
        };
        if app.latest_release_tag.is_some()
            && !release_ahead(&app, &held.release_tag, held.asset_id)
        {
            tracing::info!(
                app_id = id,
                release = %held.release_tag,
                "the release held for the server isn't ahead of the served one any more - dropped"
            );
            drop_held(state, id, &held).await;
            continue;
        }
        let ready = crate::handlers::system_update::parse_version(stored)
            .is_some_and(|needed| needed <= state.server_version);
        if !ready {
            continue;
        }
        // The file's own value too: a row and a file that disagree stay held (fail closed).
        let file_ready = match apk_identity(&held.file_path).await {
            Ok(identity) => {
                server_need(state, &app, &identity) == crate::catalog_holds::ServerNeed::Serve
            }
            Err(_) => false,
        };
        if !file_ready {
            tracing::warn!(
                app_id = id,
                release = %held.release_tag,
                "the held release's file doesn't say this server is new enough - it stays held"
            );
            continue;
        }
        match approve(state, &app, &held).await {
            Ok(identity) => {
                drop(guard);
                let version = version_of(&held.release_tag, app.release_tag_prefix.as_deref());
                let own = crate::catalog_holds::version_text(state.server_version);
                crate::security::record_security_event(
                    &state.db,
                    "catalog_release_server_ready",
                    None,
                    None,
                    Some(crate::security::Detail::words(
                        "security.detail.catalog_release_server_ready",
                        &[("app", &app.name), ("version", &version), ("server", &own)],
                    )),
                )
                .await;
                store_pins(state, &app, &identity).await;
                crate::handlers::device_api::nudge_phones_with_app(state, id).await;
                tracing::info!(app_id = id, release = %held.release_tag, "held for the server - now served");
            }
            Err(failure) => {
                tracing::warn!(
                    app_id = id,
                    "the release held for the server wasn't served: {failure}"
                );
                drop_held(state, id, &held).await;
                guard.finish(&Err(failure));
            }
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
    /// The version the running sync downloads, once its listing named a new one ("New version
    /// found, downloading"); `None` while it lists, and when none runs.
    downloading: Option<String>,
    /// The last sync was a "Check Now" that found nothing new (no download, no failure): shown
    /// until the next sync starts.
    nothing_new: bool,
    /// "Check Now" was pressed while this sync ran (the hourly one, say): its end answers the
    /// press as a Check Now's does (code QA L3).
    check_asked: bool,
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
    /// The repo's entry.jar isn't signed, or its signature doesn't check out (round S4): the index
    /// isn't used.
    RepoSignature,
    /// The repo's entry.jar is signed by another key than the repo's pin (round S4).
    RepoKey,
    /// At the first pin, the APK is signed by a key the repo's index doesn't list for it (Q32).
    IndexSigner,
    /// The repo's signed entry.json is older than the newest one taken (S4 fix round M1).
    RepoRollback,
    /// The repo offers a lower versionCode than the cached release; the hourly sync keeps the
    /// cached one, "Check Now" takes it (S4 fix round M1).
    OlderVersion,
    /// A release of Vibb's own apps isn't signed with the release key (`LAUNCHER_SIGNATURE_CHECKSUM`).
    OwnKey,
    /// `LAUNCHER_SIGNATURE_CHECKSUM` isn't set, so a release of Vibb's own apps can't be checked.
    NoOwnKey,
}

impl SyncProblem {
    /// The short title (at most 6 words, §4.2) the app's page shows, in `lang`.
    pub fn title(self, lang: Lang) -> &'static str {
        t(
            lang,
            match self {
                SyncProblem::Filter => "apps.sync.filter",
                SyncProblem::GithubUnreachable => "apps.sync.github_unreachable",
                SyncProblem::RepoNotFound => "apps.sync.repo_not_found",
                SyncProblem::GithubError => "apps.sync.github_error",
                SyncProblem::NoRelease => "apps.sync.no_release",
                SyncProblem::Download => "apps.sync.download",
                SyncProblem::TooBig => "apps.sync.too_big",
                SyncProblem::Unreadable => "apps.sync.unreadable",
                SyncProblem::OtherApp => "apps.sync.other_app",
                SyncProblem::OtherKey => "apps.sync.other_key",
                SyncProblem::CantCheck => "apps.sync.cant_check",
                SyncProblem::Taken => "apps.sync.taken",
                SyncProblem::Server => "apps.sync.server",
                SyncProblem::Removed => "apps.sync.removed",
                SyncProblem::FdroidUnknown => "apps.sync.fdroid_unknown",
                SyncProblem::NotInRepo => "apps.sync.not_in_repo",
                SyncProblem::RepoUnreachable => "apps.sync.repo_unreachable",
                SyncProblem::RepoError => "apps.sync.repo_error",
                SyncProblem::ChecksumMismatch => "apps.sync.checksum_mismatch",
                SyncProblem::RepoSignature => "apps.sync.repo_signature",
                SyncProblem::RepoKey => "apps.sync.repo_key",
                SyncProblem::IndexSigner => "apps.sync.index_signer",
                SyncProblem::RepoRollback => "apps.sync.repo_rollback",
                SyncProblem::OlderVersion => "apps.sync.older_version",
                SyncProblem::OwnKey => "apps.sync.own_key",
                SyncProblem::NoOwnKey => "apps.sync.no_own_key",
            },
        )
    }
}

/// A failed sync or upload: its kind and the raw error (reqwest, sqlx and file errors included),
/// which only the ⓘ panel and the log show.
#[derive(Clone, Debug)]
pub struct Failure {
    pub problem: SyncProblem,
    pub detail: String,
    /// The hourly sync's skip of a release refused before (no call out): never recorded in the integration
    /// health (design 116 §2.1).
    pub skipped: bool,
}

impl Failure {
    pub fn new(problem: SyncProblem, detail: impl Into<String>) -> Self {
        Failure {
            problem,
            detail: detail.into(),
            skipped: false,
        }
    }
}

/// A sync failure's class in the integration health (design 116 §2.1), `None` = not recorded: this server's own
/// state or the app removed. Every refusal [SyncStatus::refused] remembers is `stop`; a checksum mismatch is one only
/// once it is refused (`refused`, after [MISMATCHES_TO_REFUSE] in a row), a transient one before.
pub(crate) fn class_of(
    problem: SyncProblem,
    refused: bool,
) -> Option<crate::integration_health::Class> {
    use crate::integration_health::Class::*;
    use SyncProblem::*;
    match problem {
        GithubUnreachable | GithubError | Download | RepoUnreachable => Some(Transient),
        RepoNotFound | NoRelease | Filter | FdroidUnknown | NotInRepo | RepoError => Some(Parent),
        ChecksumMismatch if !refused => Some(Transient),
        OtherApp | OtherKey | OwnKey | NoOwnKey | Taken | TooBig | Unreadable | CantCheck
        | IndexSigner | RepoKey | RepoSignature | RepoRollback | ChecksumMismatch => Some(Stop),
        Server | Removed => None,
        // The source answered; the cached release is kept on purpose: a success.
        OlderVersion => None,
    }
}

/// Records a GitHub or F-Droid sync's outcome under `github`/`fdroid`, scope `app:<id>` (design 116 §2.1). A manual
/// app has no source; a remembered refusal's skip calls nothing out and records nothing.
async fn record_health(state: &AppState, app: &TrackedApp, result: &Result<(), Failure>) {
    let key = match app.source_type.as_str() {
        "github" => "github",
        "fdroid" => "fdroid",
        _ => return,
    };
    let scope = format!("app:{}", app.id);
    match result {
        Ok(()) => crate::integration_health::ok(&state.db, key, &scope).await,
        Err(failure) if failure.skipped => {}
        Err(failure) if failure.problem == SyncProblem::OlderVersion => {
            crate::integration_health::ok(&state.db, key, &scope).await
        }
        Err(failure) => {
            let refused = state
                .app_syncs
                .refused(app.id)
                .is_some_and(|(_, _, why)| why.problem == failure.problem);
            if let Some(class) = class_of(failure.problem, refused) {
                crate::integration_health::failed(
                    &state.db,
                    key,
                    &scope,
                    &format!("{:?}", failure.problem),
                    class,
                )
                .await;
            }
        }
    }
}

impl std::fmt::Display for Failure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.problem.title(Lang::En), self.detail)
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
        status.downloading = None;
        status.nothing_new = false;
        status.check_asked = false;
        Some(SyncGuard {
            syncs: self.clone(),
            id,
        })
    }

    fn status(&self, id: i64) -> SyncStatus {
        self.lock().get(&id).cloned().unwrap_or_default()
    }

    /// "Check Now" pressed while a sync of `id` runs: that sync's end answers it ([SyncGuard::end]).
    /// `false` when none runs.
    fn ask_check(&self, id: i64) -> bool {
        let mut map = self.lock();
        match map.get_mut(&id) {
            Some(status) if status.running_since.is_some() => {
                status.check_asked = true;
                true
            }
            _ => false,
        }
    }

    /// The version the running sync of `id` downloads, if it got that far.
    fn downloading(&self, id: i64) -> Option<String> {
        self.lock()
            .get(&id)
            .and_then(|status| status.downloading.clone())
    }

    /// Notes that the running sync of `id` downloads `version` (the page says so while it runs).
    fn set_downloading(&self, id: i64, version: String) {
        self.lock().entry(id).or_default().downloading = Some(version);
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
    /// The end of what isn't a sync (approve, decline, update now): its failure, no answer to a
    /// Check Now.
    pub(crate) fn finish(self, result: &Result<(), Failure>) {
        self.close(result, false, false);
    }

    /// The end of a sync: a "Check Now" (`check`), or one Check Now was pressed during
    /// ([AppSyncs::ask_check]), that ended without a failure or a download found nothing new,
    /// and the page says so.
    fn end(self, result: &Result<(), Failure>, check: bool) {
        self.close(result, check, true);
    }

    fn close(self, result: &Result<(), Failure>, check: bool, asked_counts: bool) {
        let mut map = self.syncs.lock();
        let status = map.entry(self.id).or_default();
        status.last_error = result.as_ref().err().map(|failure| SyncFailure {
            at: utc_now(),
            failure: failure.clone(),
        });
        let asked = check || (asked_counts && status.check_asked);
        status.nothing_new = asked && result.is_ok() && status.downloading.is_none();
    }
}

impl Drop for SyncGuard {
    fn drop(&mut self) {
        if let Some(status) = self.syncs.lock().get_mut(&self.id) {
            status.running_since = None;
            status.downloading = None;
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
    guard.end(&result, false);
    result.map_err(SyncError::Failed)
}

/// Checks one GitHub or F-Droid app's source for a new release and syncs the one
/// [catalog_holds::pick] takes - the newest whose wait (design 90) is over, if it is new - noting
/// the newer one that still waits. A no-op for manual-source apps - those only ever change via
/// `upload_tracked_app_release`. Used by both the scheduled loop and the admin's manual "Check
/// now" button, so they can never drift apart ("Check now" honours the wait too). Only through
/// [sync_app], which holds the app's guard: the row is read here, under it, so the comparison is
/// with what the last sync stored.
async fn sync_one_app(state: &AppState, id: i64, forced: bool) -> Result<(), Failure> {
    let app = read_app(state, id).await?;
    let result = sync_read_app(state, &app, forced).await;
    record_health(state, &app, &result).await;
    result
}

/// [sync_one_app] once the row is read.
async fn sync_read_app(state: &AppState, app: &TrackedApp, forced: bool) -> Result<(), Failure> {
    let Some(listed) = list_releases(state, app, true, forced).await? else {
        // F-Droid's API names the stored release: nothing newer is offered.
        note_waiting(state, app, None).await;
        return Ok(());
    };
    sync_listed(state, app, &listed, forced).await
}

/// The app's row, read under its guard.
async fn read_app(state: &AppState, id: i64) -> Result<TrackedApp, Failure> {
    sqlx::query_as::<_, TrackedApp>("SELECT * FROM tracked_apps WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
        .map_err(|e| Failure::new(SyncProblem::Server, e.to_string()))?
        .ok_or_else(|| Failure::new(SyncProblem::Removed, "the app was removed"))
}

/// One release a source lists for the app, newest first: its tag as stored, its APK, and when the
/// source published it (design 90 §6; `None` when the source gave no readable time).
struct Listed {
    tag: String,
    asset: ReleaseAsset,
    published: Option<chrono::DateTime<chrono::Utc>>,
}

/// The releases the app's source lists, newest first, the app marked checked; `Ok(None)` when
/// `shortcut` and f-droid.org's API names the stored release (nothing to read further), and for a
/// manual app. `forced` ("Check Now") reads an F-Droid index the hourly sync would skip.
async fn list_releases(
    state: &AppState,
    app: &TrackedApp,
    shortcut: bool,
    forced: bool,
) -> Result<Option<Vec<Listed>>, Failure> {
    match app.source_type.as_str() {
        "github" => {
            let releases = fetch_releases(
                &app.github_repo,
                app.include_prereleases,
                app.asset_pattern.as_deref(),
                app.release_tag_prefix.as_deref(),
            )
            .await?;
            touch_checked(state, app.id).await;
            Ok(Some(
                releases
                    .into_iter()
                    .map(|(release, asset)| Listed {
                        published: crate::catalog_holds::anchor(
                            release.published_at.as_deref(),
                            asset.updated_at.as_deref(),
                        ),
                        tag: release.tag_name,
                        asset,
                    })
                    .collect(),
            ))
        }
        "fdroid" => fdroid_releases(state, app, shortcut, forced).await,
        _ => Ok(None),
    }
}

/// Syncs what [catalog_holds::pick] takes of `listed`, and notes the release that waits
/// ([note_waiting]). F-Droid's hourly sync never moves the stored release to a lower versionCode
/// (S4 fix round M1); "Check Now" (`forced`) does.
async fn sync_listed(
    state: &AppState,
    app: &TrackedApp,
    listed: &[Listed],
    forced: bool,
) -> Result<(), Failure> {
    use crate::catalog_holds::{Candidate, Context, pick};
    let held = held_release(state, app.id)
        .await
        .map_err(|e| Failure::new(SyncProblem::Server, e.to_string()))?;
    let now = chrono::Utc::now();
    let wait_hours = crate::catalog_holds::sync_wait(app, &our_repos(state));
    let seen = match wait_hours {
        Some(_) => note_seen(state, app.id, listed, now)
            .await
            .map_err(|e| Failure::new(SyncProblem::Server, e.to_string()))?,
        None => HashMap::new(),
    };
    let candidates: Vec<Candidate> = listed
        .iter()
        .map(|l| Candidate {
            tag: l.tag.clone(),
            asset_id: l.asset.id,
            published: l.published,
            first_seen: seen.get(&(l.tag.clone(), l.asset.id)).copied(),
        })
        .collect();
    let context = Context {
        wait_hours,
        now,
        served: served_of(app),
        held: held.as_ref().map(|h| (h.release_tag.as_str(), h.asset_id)),
        declined: declined_of(app),
    };
    let picked = pick(&candidates, &context, |tag, asset, of| {
        ahead(app, tag, asset, of)
    });
    note_waiting(
        state,
        app,
        picked.waiting.map(|(index, since)| (&listed[index], since)),
    )
    .await;
    let Some(index) = picked.take else {
        return Ok(());
    };
    let release = &listed[index];
    // S4 fix round M1, the second guard: the hourly sync never moves the cached release back to a
    // lower versionCode (a replayed index, the unsigned API's word, a repo that dropped its newest
    // build); "Check Now" and "Forget" (`forced`) do.
    if app.source_type == "fdroid"
        && !forced
        && app.latest_release_tag.is_some()
        && let Some(cached) = app.latest_release_asset_id
        && release.asset.id < cached
    {
        return Err(Failure::new(
            SyncProblem::OlderVersion,
            format!(
                "refused: the repo now offers version {} ({}), below the cached {cached} - the \
                 cached release stays; \"Check Now\" takes the older one",
                release.asset.id, release.tag
            ),
        ));
    }
    sync_release(state, app, &release.tag, &release.asset, forced).await
}

/// This server's own release repos (QA L4: what makes the music app's row ours): the server's
/// releases' repo and the launcher's.
pub(crate) fn our_repos(state: &AppState) -> Vec<String> {
    let mut repos = vec![state.config.server_release_repo.clone()];
    repos.extend(launcher_repo_from_url(&state.config.launcher_apk_url));
    repos
}

/// The app's wait (`catalog_holds::wait_hours` with [our_repos]).
fn app_wait(state: &AppState, app: &TrackedApp) -> Option<i64> {
    crate::catalog_holds::wait_hours(app, &our_repos(state))
}

/// When this server first saw each of `listed` (design 90 QA M1, `tracked_app_seen_releases`):
/// a release listed for the first time is seen `now`; one no longer listed is forgotten.
async fn note_seen(
    state: &AppState,
    id: i64,
    listed: &[Listed],
    now: chrono::DateTime<chrono::Utc>,
) -> Result<HashMap<(String, i64), chrono::DateTime<chrono::Utc>>, sqlx::Error> {
    let mut tx = state.db.begin().await?;
    let stored: Vec<(String, i64, String)> = sqlx::query_as(
        "SELECT release_tag, asset_id, first_seen FROM tracked_app_seen_releases \
         WHERE tracked_app_id = ?",
    )
    .bind(id)
    .fetch_all(&mut *tx)
    .await?;
    let mut seen = HashMap::new();
    for (tag, asset, at) in stored {
        let listed_now = listed.iter().any(|l| l.tag == tag && l.asset.id == asset);
        match crate::display_time::parse_db_utc(&at).filter(|_| listed_now) {
            Some(at) => {
                seen.insert((tag, asset), at);
            }
            None => {
                sqlx::query(
                    "DELETE FROM tracked_app_seen_releases WHERE tracked_app_id = ? \
                     AND release_tag = ? AND asset_id = ?",
                )
                .bind(id)
                .bind(&tag)
                .bind(asset)
                .execute(&mut *tx)
                .await?;
            }
        }
    }
    for release in listed {
        let key = (release.tag.clone(), release.asset.id);
        if seen.contains_key(&key) {
            continue;
        }
        sqlx::query(
            "INSERT OR IGNORE INTO tracked_app_seen_releases (tracked_app_id, release_tag, \
             asset_id, first_seen) VALUES (?, ?, ?, ?)",
        )
        .bind(id)
        .bind(&release.tag)
        .bind(release.asset.id)
        .bind(rfc3339(now))
        .execute(&mut *tx)
        .await?;
        seen.insert(key, now);
    }
    tx.commit().await?;
    Ok(seen)
}

/// The stored release as `(tag, asset id)`; `None` before the first or for an upload (no asset id).
fn served_of(app: &TrackedApp) -> Option<crate::catalog_holds::Named<'_>> {
    Some((
        app.latest_release_tag.as_deref()?,
        app.latest_release_asset_id?,
    ))
}

/// The release the parent declined, as `(tag, asset id)`.
fn declined_of(app: &TrackedApp) -> Option<crate::catalog_holds::Named<'_>> {
    Some((
        app.declined_release_tag.as_deref()?,
        app.declined_release_asset_id?,
    ))
}

/// Marks the app checked (the page's "Last checked").
async fn touch_checked(state: &AppState, id: i64) {
    sqlx::query("UPDATE tracked_apps SET last_checked_at = datetime('now') WHERE id = ?")
        .bind(id)
        .execute(&state.db)
        .await
        .ok();
}

/// An F-Droid app's releases ([crate::fdroid]): the newest version from the repo's per-package API
/// (f-droid.org) or its index (any other repo), then every older stable version of the index
/// below it, newest first, each with its SHA-256, size and `added` time from the repo's index -
/// so an APK is checked before it is stored. A release is stored as a GitHub one: tag
/// `v<versionName>`, asset id = the versionCode. `Ok(None)`: `shortcut` and the API's version is
/// the stored one (nothing to download, and no index needed).
async fn fdroid_releases(
    state: &AppState,
    app: &TrackedApp,
    shortcut: bool,
    forced: bool,
) -> Result<Option<Vec<Listed>>, Failure> {
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
    if shortcut
        && let Some((code, name)) = &from_api
        && app.latest_release_asset_id == Some(*code)
        && app.latest_release_tag.as_deref() == Some(fdroid::tag_of(name, *code).as_str())
    {
        touch_checked(state, app.id).await;
        return Ok(None);
    }
    let key = repo_key(state, &repo.url).await?;
    let not_before = repo_timestamp(state, &repo.url).await?;
    let read = fdroid::repo_package(
        &client,
        &repo,
        &state.tracked_apps_dir.join(fdroid::CACHE_DIR),
        package,
        &key,
        not_before,
        forced,
    )
    .await?;
    if let Some(signer) = &read.new_key {
        pin_repo_key(state, &repo.url, signer).await?;
    }
    note_repo_timestamp(state, &repo.url, read.timestamp).await?;
    let newest = fdroid::pick(
        &read.versions,
        package,
        from_api.as_ref().map(|(code, _)| *code),
    )?;
    touch_checked(state, app.id).await;
    // The stored release keeps its stored tag (the API's name may differ from the index's), so
    // the pick knows it is listed.
    let stored_tag = |code: i64| {
        app.latest_release_tag
            .clone()
            .filter(|_| app.latest_release_asset_id == Some(code))
    };
    let listed = |version: &fdroid::IndexVersion, name: &str| Listed {
        tag: stored_tag(version.code).unwrap_or_else(|| fdroid::tag_of(name, version.code)),
        asset: ReleaseAsset {
            id: version.code,
            name: version.file.clone(),
            browser_download_url: format!("{}/{}", repo.url, version.file),
            size: version.size,
            sha256: Some(version.sha256.clone()),
            index_signers: version.signers.clone(),
            updated_at: None,
        },
        published: crate::catalog_holds::anchor_ms(version.added),
    };
    // The API's name when there is one, so the shortcut above matches next time.
    let name = match &from_api {
        Some((_, name)) if !name.trim().is_empty() => name.as_str(),
        _ => newest.name.as_str(),
    };
    let mut releases = vec![listed(newest, name)];
    let mut older: Vec<i64> = read
        .versions
        .iter()
        .filter(|v| !v.beta && v.code < newest.code)
        .map(|v| v.code)
        .collect();
    older.sort_unstable_by(|a, b| b.cmp(a));
    older.dedup();
    for code in older {
        let version = fdroid::pick(&read.versions, package, Some(code))?;
        releases.push(listed(version, &version.name));
    }
    Ok(Some(releases))
}

/// The key `repo`'s entry.jar must be signed by (round S4): f-droid.org's and IzzyOnDroid's known
/// one, else the one pinned at this server's first fetch (`fdroid_repo_keys`), else none yet
/// (trust on first use). A database error fails the sync (closed: no index without its key).
async fn repo_key(state: &AppState, repo_url: &str) -> Result<crate::fdroid::RepoKey, Failure> {
    use crate::fdroid::RepoKey;
    if let Some(known) = RepoKey::known(repo_url) {
        return Ok(known);
    }
    let pinned: Option<String> =
        sqlx::query_scalar("SELECT signer_sha256 FROM fdroid_repo_keys WHERE repo = ?")
            .bind(repo_url)
            .fetch_optional(&state.db)
            .await
            .map_err(|e| Failure::new(SyncProblem::Server, e.to_string()))?;
    Ok(pinned.map_or(RepoKey::FirstUse, RepoKey::Pinned))
}

/// Pins `signer` as `repo`'s key at its first good fetch. Two first syncs at once: the first insert
/// wins and the other is held to it (another key is [SyncProblem::RepoKey]). Only while an F-Droid
/// app still has the repo (S4 fix L2: a sync still running mustn't put back a pin that
/// [remove_unused_fdroid_indexes] has just removed); otherwise the sync ends as `Removed`.
async fn pin_repo_key(state: &AppState, repo_url: &str, signer: &str) -> Result<(), Failure> {
    let server = |e: sqlx::Error| Failure::new(SyncProblem::Server, e.to_string());
    sqlx::query(
        "INSERT OR IGNORE INTO fdroid_repo_keys (repo, signer_sha256) SELECT ?1, ?2 \
         WHERE EXISTS (SELECT 1 FROM tracked_apps WHERE source_type = 'fdroid' AND fdroid_repo = ?1)",
    )
    .bind(repo_url)
    .bind(signer)
    .execute(&state.db)
    .await
    .map_err(server)?;
    let pinned: Option<String> =
        sqlx::query_scalar("SELECT signer_sha256 FROM fdroid_repo_keys WHERE repo = ?")
            .bind(repo_url)
            .fetch_optional(&state.db)
            .await
            .map_err(server)?;
    let Some(pinned) = pinned else {
        return Err(Failure::new(
            SyncProblem::Removed,
            "the repo's last app was removed during the sync - its key isn't pinned",
        ));
    };
    crate::fdroid::RepoKey::Pinned(pinned).check(signer)?;
    tracing::info!(
        repo = repo_url,
        signer,
        "F-Droid repo key pinned at its first fetch"
    );
    Ok(())
}

/// The newest entry.json `timestamp` this server took for `repo` (`fdroid_repo_state`, every repo,
/// known hosts too; 0 = none yet): an older signed entry.json is a rollback (S4 fix round M1). A
/// database error fails the sync.
async fn repo_timestamp(state: &AppState, repo_url: &str) -> Result<i64, Failure> {
    sqlx::query_scalar::<_, i64>("SELECT entry_timestamp FROM fdroid_repo_state WHERE repo = ?")
        .bind(repo_url)
        .fetch_optional(&state.db)
        .await
        .map(Option::unwrap_or_default)
        .map_err(|e| Failure::new(SyncProblem::Server, e.to_string()))
}

/// Raises `repo`'s stored entry.json timestamp to `timestamp` (never lowers it), while an F-Droid
/// app still has the repo (the L2 rule, as [pin_repo_key]).
async fn note_repo_timestamp(
    state: &AppState,
    repo_url: &str,
    timestamp: i64,
) -> Result<(), Failure> {
    sqlx::query(
        "INSERT INTO fdroid_repo_state (repo, entry_timestamp) SELECT ?1, ?2 \
         WHERE EXISTS (SELECT 1 FROM tracked_apps WHERE source_type = 'fdroid' AND fdroid_repo = ?1) \
         ON CONFLICT(repo) DO UPDATE SET \
         entry_timestamp = MAX(entry_timestamp, excluded.entry_timestamp)",
    )
    .bind(repo_url)
    .bind(timestamp)
    .execute(&state.db)
    .await
    .map(|_| ())
    .map_err(|e| Failure::new(SyncProblem::Server, e.to_string()))
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
/// or stored (a GitHub or manual app is checked by its pins alone). At the first pin (no signer
/// pinned yet) its signer must also be one the repo's index lists for it, when the index lists
/// any (Q32; listed but none readable is refused too, S4 fix L3): no defence against a tampered
/// index, which could change both, but a mixed-up mirror or a wrong file is caught before its key
/// becomes the app's.
fn check_fdroid_package(
    app: &TrackedApp,
    identity: &crate::apk::ApkIdentity,
    index_signers: Option<&[String]>,
) -> Result<(), Failure> {
    let Some(expected) = app
        .fdroid_package
        .as_deref()
        .filter(|_| app.source_type == "fdroid")
    else {
        return Ok(());
    };
    if app.signer_sha256.is_none()
        && let Some(listed) = index_signers
    {
        if listed.is_empty() {
            return Err(Failure::new(
                SyncProblem::IndexSigner,
                "refused: the repo's index lists signing keys for the APK, but none is a SHA-256 \
                 - not pinned or stored",
            ));
        }
        if let Some(signer) = identity.signer_sha256.as_deref()
            && !listed.iter().any(|key| key == signer)
        {
            return Err(Failure::new(
                SyncProblem::IndexSigner,
                format!(
                    "refused: the repo's APK is signed by key {signer}, but the repo's index lists \
                     {} for it - not pinned or stored",
                    listed.join(", ")
                ),
            ));
        }
    }
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
/// the release held for the parent, or the release the last sync refused (unless `forced`). A
/// release refused for its package or key is remembered ([SyncStatus::refused]). A checked release
/// is judged (design 90, [crate::catalog_holds::judge]): served, or held for the parent's approval
/// ([hold_release]) - never the app's first release, never ours.
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
        return Err(Failure {
            skipped: true,
            ..Failure::new(
                why.problem,
                format!(
                    "{} (not downloaded again until the release changes; \"Check Now\" tries again)",
                    why.detail
                ),
            )
        });
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
    // The release waiting for the parent's answer is never downloaded again (design 90 §7).
    let held = held_release(state, app.id)
        .await
        .map_err(|e| Failure::new(Server, e.to_string()))?;
    if held
        .as_ref()
        .is_some_and(|h| h.release_tag == tag && h.asset_id == asset.id)
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
    // Only now, with a release that isn't the cached or the held one, is anything downloaded: the
    // app's page says "New version found, downloading" from here, and Check Now answers.
    state
        .app_syncs
        .set_downloading(app.id, version_of(tag, app.release_tag_prefix.as_deref()));
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
            match check_fdroid_package(app, &identity, asset.index_signers.as_deref()).and_then(
                |()| {
                    check_pins(
                        app,
                        &identity,
                        key_rule(state, app, identity.package.as_deref()),
                    )
                },
            ) {
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

    // Design 140 §4.2: a release of ours names the oldest server it needs. One newer than this
    // server is held until a server that new starts (no parent answer, no inbox row); one from the
    // first tag that carries the value without a readable one is refused as unreadable. Before the
    // first-release rule below (QA L1): a first release is held too.
    match server_need(state, app, &identity) {
        crate::catalog_holds::ServerNeed::Serve => {}
        crate::catalog_holds::ServerNeed::Refuse => {
            tokio::fs::remove_file(&file_path).await.ok();
            let refused = Failure::new(
                Unreadable,
                format!(
                    "refused: no readable {} (design 140)",
                    crate::apk::MIN_SERVER_ENTRY
                ),
            );
            state
                .app_syncs
                .set_refused(app.id, Some((tag.to_string(), asset.id, refused.clone())));
            return Err(refused);
        }
        crate::catalog_holds::ServerNeed::Hold(needed) => {
            let release = HeldRelease {
                release_tag: tag.to_string(),
                asset_id: asset.id,
                file_path,
                asset_name: Some(asset.name.clone()),
                asset_size: Some(i64::try_from(downloaded.size).unwrap_or(i64::MAX)),
                sha256: downloaded.sha256,
                version_code: identity.version_code,
                permissions: identity
                    .permissions
                    .as_deref()
                    .map(crate::catalog_holds::names_json),
                reason: crate::catalog_holds::Reason::Server.key().to_string(),
                new_permissions: "[]".to_string(),
                held_at: utc_now(),
                min_server: Some(crate::catalog_holds::version_text(needed)),
            };
            return hold_release(state, app, held, release).await;
        }
    }
    // The app's first release seeds the set the parent accepted (design 90 §5) and is never held:
    // it reaches no phone until the parent ticks the app, and its page shows what it asks for.
    let first = app.latest_release_tag.is_none();
    let accepted = crate::catalog_holds::read_names(app.accepted_permissions.as_deref());
    let verdict = if first || app_wait(state, app).is_none() {
        crate::catalog_holds::Verdict::Serve
    } else {
        crate::catalog_holds::judge(
            false,
            crate::catalog_holds::judged_permissions(&identity),
            accepted.as_deref(),
            app.always_ask,
        )
    };
    if let crate::catalog_holds::Verdict::Hold(reason, new) = verdict {
        let release = HeldRelease {
            release_tag: tag.to_string(),
            asset_id: asset.id,
            file_path,
            asset_name: Some(asset.name.clone()),
            asset_size: Some(i64::try_from(downloaded.size).unwrap_or(i64::MAX)),
            sha256: downloaded.sha256,
            version_code: identity.version_code,
            permissions: identity
                .permissions
                .as_deref()
                .map(crate::catalog_holds::names_json),
            reason: reason.key().to_string(),
            new_permissions: crate::catalog_holds::names_json(&new),
            held_at: utc_now(),
            min_server: None,
        };
        return hold_release(state, app, held, release).await;
    }
    let seeded = first.then(|| {
        widened(
            accepted.as_deref(),
            identity.permissions.as_deref().unwrap_or_default(),
        )
    });
    let stored = sqlx::query(
        "UPDATE tracked_apps SET latest_release_tag = ?, latest_release_asset_id = ?, \
         latest_release_file_path = ?, latest_release_asset_name = ?, \
         latest_release_asset_size = ?, latest_release_sha256 = ?, \
         latest_release_version_code = ?, latest_release_permissions = ?, \
         accepted_permissions = COALESCE(?, accepted_permissions) WHERE id = ?",
    )
    .bind(tag)
    .bind(asset.id)
    .bind(&file_path)
    .bind(&asset.name)
    .bind(i64::try_from(downloaded.size).unwrap_or(i64::MAX))
    .bind(&downloaded.sha256)
    .bind(identity.version_code)
    .bind(
        identity
            .permissions
            .as_deref()
            .map(crate::catalog_holds::names_json),
    )
    .bind(seeded)
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
    sqlx::query(CLEAR_FAILED_INSTALLS)
        .bind(app.id)
        .execute(&state.db)
        .await
        .ok();
    store_pins(state, app, &identity).await;
    // Stored: a refusal remembered before (a mismatch "Check Now" got past) no longer holds.
    state.app_syncs.set_refused(app.id, None);
    // Design 90 §7: a newer release served supersedes the held one (the phones skip it), and it
    // no longer waits.
    if let Some(held) = &held
        && ahead(app, tag, asset.id, (&held.release_tag, held.asset_id))
    {
        drop_held(state, app.id, held).await;
    }
    clear_waiting_if(state, app.id, tag, asset.id).await;
    // App update notices (2026-10-25): a new release of an app that had one - never its first.
    if let Some(event) = update_available_event(app, tag, asset.id, chrono::Utc::now()) {
        crate::parent_events::raise_logged(state, &event).await;
    }
    crate::handlers::device_api::nudge_phones_with_app(state, app.id).await;
    Ok(())
}

/// A release held for the parent's approval (design 90 §7): one per app, its file next to the
/// served one, `tracked_app_held_releases`.
#[derive(sqlx::FromRow, Clone, Debug)]
pub(crate) struct HeldRelease {
    pub release_tag: String,
    pub asset_id: i64,
    pub file_path: String,
    pub asset_name: Option<String>,
    pub asset_size: Option<i64>,
    pub sha256: String,
    pub version_code: Option<i64>,
    /// Its declared permissions (JSON array), `None` unread.
    pub permissions: Option<String>,
    /// `catalog_holds::Reason::key`.
    pub reason: String,
    /// The sensitive names it newly asks for (JSON array).
    pub new_permissions: String,
    pub held_at: String,
    /// Reason `server` (design 140, 0187): the server version it needs (`X.Y.Z`).
    pub min_server: Option<String>,
}

/// [crate::catalog_holds::needs_server] for a release of `app` this server read (design 140).
fn server_need(
    state: &AppState,
    app: &TrackedApp,
    identity: &crate::apk::ApkIdentity,
) -> crate::catalog_holds::ServerNeed {
    crate::catalog_holds::needs_server(
        crate::catalog_holds::ours(app, &our_repos(state)),
        identity.min_server,
        identity.version_code,
        crate::catalog_holds::min_server_first_code(app),
        state.server_version,
    )
}

/// The app's held release, if any.
pub(crate) async fn held_release(
    state: &AppState,
    id: i64,
) -> Result<Option<HeldRelease>, sqlx::Error> {
    sqlx::query_as(
        "SELECT release_tag, asset_id, file_path, asset_name, asset_size, sha256, version_code, \
         permissions, reason, new_permissions, held_at, min_server FROM tracked_app_held_releases \
         WHERE tracked_app_id = ?",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await
}

/// Removes the app's held release `held` - its row while it still names that release, then its
/// file (never the served one).
async fn drop_held(state: &AppState, id: i64, held: &HeldRelease) {
    let removed = sqlx::query(
        "DELETE FROM tracked_app_held_releases WHERE tracked_app_id = ? AND release_tag = ? \
         AND asset_id = ?",
    )
    .bind(id)
    .bind(&held.release_tag)
    .bind(held.asset_id)
    .execute(&state.db)
    .await;
    match removed {
        Ok(_) => remove_unless_served(state, id, &held.file_path).await,
        Err(err) => tracing::error!(app_id = id, %err, "can't remove the held release"),
    }
}

/// Removes `path` unless the app's row serves it (a read error keeps it).
async fn remove_unless_served(state: &AppState, id: i64, path: &str) {
    let served: Result<Option<Option<String>>, sqlx::Error> =
        sqlx::query_scalar("SELECT latest_release_file_path FROM tracked_apps WHERE id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await;
    if let Ok(served) = served
        && served.flatten().as_deref() != Some(path)
    {
        tokio::fs::remove_file(path).await.ok();
    }
}

/// `accepted` (unknown counts as none) with the sensitive names of `permissions` added, as stored.
fn widened(accepted: Option<&[String]>, permissions: &[String]) -> String {
    let mut names: Vec<String> = accepted.unwrap_or_default().to_vec();
    names.extend(crate::catalog_holds::sensitive(permissions));
    names.sort();
    names.dedup();
    crate::catalog_holds::names_json(&names)
}

/// Holds `release` (downloaded and checked) for the parent's approval instead of serving it
/// (design 90 §7): its row replaces the app's `previous` held release, whose file goes; the phones
/// keep the served release and are not nudged; the inbox is told under the release's own `:held`
/// key (§17.3 H1).
async fn hold_release(
    state: &AppState,
    app: &TrackedApp,
    previous: Option<HeldRelease>,
    release: HeldRelease,
) -> Result<(), Failure> {
    let written = sqlx::query(
        "INSERT INTO tracked_app_held_releases (tracked_app_id, release_tag, asset_id, file_path, \
         asset_name, asset_size, sha256, version_code, permissions, reason, new_permissions, \
         held_at, min_server) SELECT ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?21 \
         WHERE EXISTS (SELECT 1 FROM tracked_apps WHERE id = ?1 AND source_type = ?13 \
         AND github_repo = ?14 AND fdroid_repo IS ?15 AND fdroid_package IS ?16 \
         AND asset_pattern IS ?17 AND release_tag_prefix IS ?18 AND include_prereleases = ?19 \
         AND signer_sha256 IS ?20) \
         ON CONFLICT(tracked_app_id) DO UPDATE SET release_tag = excluded.release_tag, \
         asset_id = excluded.asset_id, file_path = excluded.file_path, \
         asset_name = excluded.asset_name, asset_size = excluded.asset_size, \
         sha256 = excluded.sha256, version_code = excluded.version_code, \
         permissions = excluded.permissions, reason = excluded.reason, \
         new_permissions = excluded.new_permissions, held_at = excluded.held_at, \
         min_server = excluded.min_server",
    )
    .bind(app.id)
    .bind(&release.release_tag)
    .bind(release.asset_id)
    .bind(&release.file_path)
    .bind(&release.asset_name)
    .bind(release.asset_size)
    .bind(&release.sha256)
    .bind(release.version_code)
    .bind(&release.permissions)
    .bind(&release.reason)
    .bind(&release.new_permissions)
    .bind(&release.held_at)
    // Held only while the row still has the source the release came from (QA L2): a source or
    // key changed meanwhile ([forget_holds]) makes it another app's.
    .bind(&app.source_type)
    .bind(&app.github_repo)
    .bind(&app.fdroid_repo)
    .bind(&app.fdroid_package)
    .bind(&app.asset_pattern)
    .bind(&app.release_tag_prefix)
    .bind(app.include_prereleases)
    .bind(&app.signer_sha256)
    .bind(&release.min_server)
    .execute(&state.db)
    .await;
    match written {
        Ok(result) if result.rows_affected() > 0 => {}
        Ok(_) => {
            tokio::fs::remove_file(&release.file_path).await.ok();
            return Err(Failure::new(
                SyncProblem::Removed,
                "the app was removed, or its source or key changed, during the download",
            ));
        }
        Err(err) => {
            tokio::fs::remove_file(&release.file_path).await.ok();
            return Err(Failure::new(SyncProblem::Server, err.to_string()));
        }
    }
    if let Some(previous) = previous.filter(|p| p.file_path != release.file_path) {
        remove_unless_served(state, app.id, &previous.file_path).await;
    }
    clear_waiting_if(state, app.id, &release.release_tag, release.asset_id).await;
    // Design 140 QA M1: a release held for the server waits for no answer - the pages show it, no
    // inbox row and no push.
    if release.reason == crate::catalog_holds::Reason::Server.key() {
        tracing::info!(
            app_id = app.id,
            release = %release.release_tag,
            min_server = release.min_server.as_deref().unwrap_or_default(),
            "release held until this server is updated"
        );
        return Ok(());
    }
    let prefix = app.release_tag_prefix.as_deref();
    let to = version_of(&release.release_tag, prefix);
    let from = app
        .latest_release_tag
        .as_deref()
        .map(|old| version_of(old, prefix))
        .filter(|from| *from != to);
    let new = crate::catalog_holds::read_names(Some(&release.new_permissions)).unwrap_or_default();
    let event = crate::parent_events::app_update_held(
        app.id,
        &app.name,
        &format!("{}@{}", release.release_tag, release.asset_id),
        from.as_deref(),
        &to,
        crate::parent_events::HeldParams {
            reason: release.reason.clone(),
            groups: crate::catalog_holds::groups(&new)
                .into_iter()
                .map(str::to_string)
                .collect(),
        },
        chrono::Utc::now(),
    );
    crate::parent_events::raise_logged(state, &event).await;
    tracing::info!(
        app_id = app.id,
        release = %release.release_tag,
        reason = %release.reason,
        "release held for the parent's approval"
    );
    Ok(())
}

/// Clears the app's waiting release when it is `(tag, asset)` - just served or held.
async fn clear_waiting_if(state: &AppState, id: i64, tag: &str, asset: i64) {
    sqlx::query(
        "UPDATE tracked_apps SET waiting_release_tag = NULL, waiting_release_asset_id = NULL, \
         waiting_release_published_at = NULL, waiting_release_since = NULL \
         WHERE id = ? AND waiting_release_tag = ? AND waiting_release_asset_id = ?",
    )
    .bind(id)
    .bind(tag)
    .bind(asset)
    .execute(&state.db)
    .await
    .ok();
}

/// An instant as the waiting columns store it (UTC RFC 3339).
fn rfc3339(at: chrono::DateTime<chrono::Utc>) -> String {
    at.to_rfc3339_opts(chrono::SecondsFormat::Secs, true)
}

/// Stores the release that waits out the app's wait (design 90 §6, §17.3 M1: its source's time
/// and the wait's anchor, kept while the same release is seen again), or clears it (`None`: none
/// waits - pulled, served, or nothing newer). A release newly found waiting is told in the inbox
/// once ([crate::parent_events::app_update_waiting], the release's own key: served later, it
/// raises nothing more) - not for the app's first release.
async fn note_waiting(
    state: &AppState,
    app: &TrackedApp,
    waiting: Option<(&Listed, chrono::DateTime<chrono::Utc>)>,
) {
    let Some((release, since)) = waiting else {
        if app.waiting_release_tag.is_some() || app.waiting_release_since.is_some() {
            sqlx::query(
                "UPDATE tracked_apps SET waiting_release_tag = NULL, \
                 waiting_release_asset_id = NULL, waiting_release_published_at = NULL, \
                 waiting_release_since = NULL WHERE id = ?",
            )
            .bind(app.id)
            .execute(&state.db)
            .await
            .ok();
        }
        return;
    };
    let since_text = rfc3339(since);
    let published = release.published.map(rfc3339);
    let same = app.waiting_release_tag.as_deref() == Some(release.tag.as_str())
        && app.waiting_release_asset_id == Some(release.asset.id);
    if same
        && app.waiting_release_since.as_deref() == Some(since_text.as_str())
        && app.waiting_release_published_at == published
    {
        return;
    }
    let stored = sqlx::query(
        "UPDATE tracked_apps SET waiting_release_tag = ?, waiting_release_asset_id = ?, \
         waiting_release_published_at = ?, waiting_release_since = ? WHERE id = ?",
    )
    .bind(&release.tag)
    .bind(release.asset.id)
    .bind(&published)
    .bind(&since_text)
    .bind(app.id)
    .execute(&state.db)
    .await;
    if let Err(err) = stored {
        tracing::error!(app_id = app.id, %err, "can't note the waiting release");
        return;
    }
    if same {
        return;
    }
    let (Some(old_tag), Some(wait)) = (app.latest_release_tag.as_deref(), app_wait(state, app))
    else {
        return;
    };
    let prefix = app.release_tag_prefix.as_deref();
    let (from, to) = (
        version_of(old_tag, prefix),
        version_of(&release.tag, prefix),
    );
    let event = crate::parent_events::app_update_waiting(
        app.id,
        &app.name,
        &format!("{}@{}", release.tag, release.asset.id),
        (from != to).then_some(from.as_str()),
        &to,
        crate::catalog_holds::ready_at(since, wait),
        chrono::Utc::now(),
    );
    crate::parent_events::raise_logged(state, &event).await;
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
    ahead(app, tag, asset_id, (old_tag, old_asset))
}

/// Whether release `tag` (asset `asset_id`) is ahead of release `of` of `app`'s source - the stored,
/// held or declined one (design 90 §17.3 H2, [release_ahead]'s rule).
fn ahead(app: &TrackedApp, tag: &str, asset_id: i64, of: crate::catalog_holds::Named) -> bool {
    let (old_tag, old_asset) = of;
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
/// with its versionName), the music app's (`music_state.version_code`), and - from a launcher that
/// sends them (PLAN "Installed versionCode per catalog app in the status report") - every listed
/// app's (`installed_apps[].version_code`/`version_name`), by package.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct InstalledCodes {
    pub launcher: Option<i64>,
    pub launcher_name: Option<String>,
    pub music: Option<i64>,
    pub apps: std::collections::BTreeMap<String, InstalledVersion>,
}

/// One listed app's installed version, as the phone reports it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct InstalledVersion {
    pub code: i64,
    pub name: Option<String>,
}

/// Whether a phone that runs a catalog app other than the launcher and the music app at `version`
/// has the row's stored release installed: an F-Droid release by its versionCode (the row's asset
/// id is it); any other by the versionCode read from the release's APK (2026-11-12: a tag needn't
/// be the versionName - Organic Maps' "2026.11.11-39-android" is "2026.11.11-39-Web"), else - no
/// code read - by the reported versionName against the tag's version ([version_of], "v7.2.0" ->
/// "7.2.0"), a leading "v" on the name ignored too.
pub(crate) fn catalog_release_installed(app: &TrackedApp, version: &InstalledVersion) -> bool {
    let Some(tag) = app.latest_release_tag.as_deref() else {
        return false;
    };
    if app.source_type == "fdroid" {
        return app.latest_release_asset_id == Some(version.code);
    }
    if let Some(code) = app.latest_release_version_code {
        return code == version.code;
    }
    let Some(name) = version
        .name
        .as_deref()
        .map(str::trim)
        .filter(|n| !n.is_empty())
    else {
        return false;
    };
    let name = name
        .strip_prefix(['v', 'V'])
        .filter(|rest| rest.starts_with(|c: char| c.is_ascii_digit()))
        .unwrap_or(name);
    name == version_of(tag, app.release_tag_prefix.as_deref())
}

/// The catalog apps whose `app_updated` comes from the installed list (`before` -> `now`): each of
/// `apps` (the phone's checked catalog rows, neither the launcher's nor the music app's) whose
/// package the phone lists at a higher versionCode than the previous report did, which is the
/// row's stored release ([catalog_release_installed]); with its `from` (the previous versionName,
/// when it differs from the new version). No previous code (a report without one, or a new
/// app) is no update.
pub(crate) fn listed_app_updates<'a>(
    apps: &'a [TrackedApp],
    before: &InstalledCodes,
    now: &InstalledCodes,
) -> Vec<(&'a TrackedApp, i64, Option<String>, String)> {
    let mut updates = Vec::new();
    for app in apps {
        if app.is_launcher || app.package_name.trim().is_empty() {
            continue;
        }
        let (Some(was), Some(is)) = (
            before.apps.get(&app.package_name),
            now.apps.get(&app.package_name),
        ) else {
            continue;
        };
        if is.code <= was.code || !catalog_release_installed(app, is) {
            continue;
        }
        let tag = app.latest_release_tag.as_deref().unwrap_or_default();
        let to = version_of(tag, app.release_tag_prefix.as_deref());
        let from = was
            .name
            .clone()
            .filter(|f| !f.trim().is_empty() && *f != to);
        updates.push((app, is.code, from, to));
    }
    updates
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

/// `app_updated` events from a status report (2026-10-25): for the launcher, the music app and -
/// by the installed list's versionCodes ([listed_app_updates]) - every other catalog app checked for
/// the phone, a versionCode higher than the previous report's that is the catalog row's stored
/// release - once per phone and version (the key). No previous report (a new phone) or no code:
/// nothing.
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
    // Every other catalog app checked for this phone, by its listed versionCode (the music app keeps
    // its own `music_state` path, so it is never counted twice).
    if !now.apps.is_empty() && !before.apps.is_empty() {
        let music_id = music.as_ref().map_or(0, |m| m.id);
        let checked: Result<Vec<TrackedApp>, sqlx::Error> = sqlx::query_as(
            "SELECT ta.* FROM tracked_apps ta JOIN device_tracked_apps dta \
             ON dta.tracked_app_id = ta.id WHERE dta.device_id = ? AND ta.is_launcher = 0 \
             AND ta.package_name != '' AND ta.id != ? ORDER BY ta.id",
        )
        .bind(phone.id)
        .bind(music_id)
        .fetch_all(&state.db)
        .await;
        match checked {
            Ok(apps) => {
                for (app, code, from, to) in listed_app_updates(&apps, before, now) {
                    let event = crate::parent_events::app_updated(
                        phone.id,
                        &phone.name,
                        app.id,
                        &app.name,
                        code,
                        from.as_deref(),
                        &to,
                        phone.now,
                    );
                    crate::parent_events::raise_logged(state, &event).await;
                }
            }
            Err(err) => {
                tracing::error!(device_id = phone.id, %err, "can't read the phone's catalog apps")
            }
        }
    }
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
    /// Design 87 §8.1 (revision 3): the app's real logo (its URL) as most phones sent it, over a catalog glyph; the
    /// launcher's row the server's Vibb mark.
    logo: Option<String>,
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
    /// Instead of the version: "Off", "Update failed", "No release yet" (or when the first
    /// release reaches the phones, design 90), and the badge's class.
    badge: Option<(String, &'static str)>,
    tile: AppTile,
}

/// Where a phone's apps are listed: the app's own page on the phone (design 127 §2.1,
/// `/devices/{id}/apps/{package}`) when there is a package, else Device › Apps (design 31 step 6).
fn phone_apps_href(device_id: i64, package: Option<&str>, from: AppsFrom) -> String {
    match package.filter(|p| crate::app_display::valid_package_name(p)) {
        Some(package) => format!("/devices/{device_id}/apps/{package}?from={}", from.value()),
        None => crate::handlers::device_pages::apps_url(device_id, "", Some(from), ""),
    }
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

/// "1 phone", "2 phones", "no phones", in `lang`.
fn phones_text(lang: Lang, count: i64) -> String {
    match count {
        0 => t(lang, "apps.list.no_phones").to_string(),
        n => t_count(lang, "apps.list.phones", n, &[]),
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

fn app_tile(app: &TrackedApp, label: &str, logos: &HashMap<String, String>) -> AppTile {
    let colours = crate::handlers::devices::AVATAR_COLOURS;
    AppTile {
        logo: tile_logo(app, logos),
        letter: crate::handlers::devices::initial(label),
        colour: colours[app.id.rem_euclid(colours.len() as i64) as usize],
        icon: tile_icon(app),
    }
}

/// A catalog app's chosen glyph and its tile colour, when its key is known.
fn tile_icon(app: &TrackedApp) -> Option<(String, &'static str)> {
    app.display_icon
        .as_deref()
        .filter(|key| crate::app_display::icon_known(key))
        .map(|key| {
            (
                key.to_string(),
                crate::app_display::tile_of(&app.display_color),
            )
        })
}

/// A catalog app's logo as its tile shows it (design 87 §8.1): the server's Vibb mark for the launcher, else the
/// logo most phones sent or the catalog's kept one (`logos`, [crate::app_logos::catalog_logos]).
fn tile_logo(app: &TrackedApp, logos: &HashMap<String, String>) -> Option<String> {
    if app.is_launcher {
        Some(crate::app_logos::VIBB_MARK.to_string())
    } else {
        logos.get(&app.package_name).cloned()
    }
}

/// A catalog app's avatar on Notifications, by its tile's rule (code QA L5): the logo, else the chosen glyph on its
/// colour, else (no entry) the letter.
pub(crate) struct CatalogAvatar {
    pub logo: Option<String>,
    /// The glyph's key (`/static/app-icons/<key>.svg`) and the tile's colour.
    pub icon: Option<(String, &'static str)>,
}

/// The avatars of the catalog apps `ids`, by id, as their tiles show them ([tile_logo], [tile_icon]): the
/// Notifications rows of a catalog event (PLAN 2026-11-19). An app with neither, or removed, is left out; a failed read
/// is logged and leaves every row its letter.
pub(crate) async fn avatars_by_id(state: &AppState, ids: &[i64]) -> HashMap<i64, CatalogAvatar> {
    if ids.is_empty() {
        return HashMap::new();
    }
    let json = serde_json::to_string(ids).expect("numbers serialize");
    let apps: Vec<TrackedApp> = match sqlx::query_as(
        "SELECT * FROM tracked_apps WHERE id IN (SELECT value FROM json_each(?))",
    )
    .bind(json)
    .fetch_all(&state.db)
    .await
    {
        Ok(apps) => apps,
        Err(err) => {
            tracing::error!(%err, "can't read the catalog apps for their logos");
            return HashMap::new();
        }
    };
    let packages: Vec<String> = apps
        .iter()
        .filter(|app| !app.is_launcher && !app.package_name.is_empty())
        .map(|app| app.package_name.clone())
        .collect();
    let logos = match crate::app_logos::catalog_logos_for(state, &packages).await {
        Ok(logos) => logos,
        Err(err) => {
            tracing::error!(%err, "can't read the apps' logos");
            HashMap::new()
        }
    };
    apps.iter()
        .filter_map(|app| {
            let logo = tile_logo(app, &logos);
            let icon = if logo.is_none() { tile_icon(app) } else { None };
            (logo.is_some() || icon.is_some()).then_some((app.id, CatalogAvatar { logo, icon }))
        })
        .collect()
}

/// The catalog row of `app`, which `phones` phones have ticked; the launcher is on every one of
/// the `set_up` phones; `held`: a release waits for the parent (design 90 §9.2), `Some(true)` for
/// the server (design 140 §4.3). In `lang`.
#[allow(clippy::too_many_arguments)]
fn catalog_row(
    state: &AppState,
    app: &TrackedApp,
    phones: i64,
    set_up: i64,
    held: Option<bool>,
    logos: &HashMap<String, String>,
    lang: Lang,
) -> CatalogRow {
    let label = app
        .display_label
        .clone()
        .filter(|label| !label.trim().is_empty())
        .unwrap_or_else(|| app.name.clone());
    let mut sub = Vec::new();
    if app.is_launcher {
        sub.push(t(lang, "apps.list.launcher").to_string());
    } else if label != app.name {
        sub.push(app.name.clone());
    }
    sub.push(match (app.is_launcher, set_up) {
        (true, 0) => phones_text(lang, 0),
        (true, _) => t(lang, "apps.list.all_phones").to_string(),
        (false, _) => phones_text(lang, phones),
    });
    let badge = if !app.enabled {
        Some((t(lang, "common.off").to_string(), "badge"))
    } else if let Some(for_server) = held {
        let key = if for_server {
            "apps.list.waits_server"
        } else {
            "apps.list.waits"
        };
        Some((t(lang, key).to_string(), "badge badge-warn"))
    } else if state.app_syncs.status(app.id).last_error.is_some() {
        Some((
            t(lang, "apps.list.update_failed").to_string(),
            "badge badge-red",
        ))
    } else if app.latest_release_tag.is_none() {
        Some((t(lang, "device.apps.no_release").to_string(), "badge"))
    } else {
        None
    };
    CatalogRow {
        id: app.id,
        tile: app_tile(app, &label, logos),
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
    lang: Lang,
    title: String,
    phones: Vec<PhoneRow>,
    apps: Vec<CatalogRow>,
    bell: crate::parent_events::Bell,
}
crate::i18n::words!(AppsListTemplate);

/// The Apps tab (design 31 §3.3): each phone with how many apps it allows, the catalog - every
/// app the server keeps up to date, the launcher's own row included - and Add App.
pub async fn list_apps(
    State(state): State<AppState>,
    lang: Lang,
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
            t(lang, "devices.status.not_set_up").to_string()
        } else {
            match allowed_count(&state, device.id, &tracked).await {
                Some(count) => t_count(lang, "apps.list.allowed", count as i64, &[]),
                None => t(lang, "apps.list.unreadable").to_string(),
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
    // Design 87: the apps' real logos the phones sent; a failed read leaves the letters.
    let logos = crate::app_logos::catalog_logos(&state)
        .await
        .unwrap_or_else(|err| {
            tracing::error!(%err, "can't read the apps' logos");
            HashMap::new()
        });
    // Design 90 §9.2: the apps with a release waiting for the parent - or, design 140, for the
    // server.
    let held: HashMap<i64, bool> = sqlx::query_as::<_, (i64, bool)>(
        "SELECT tracked_app_id, reason = 'server' FROM tracked_app_held_releases",
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
                held.get(&app.id).copied(),
                &logos,
                lang,
            )
        })
        .collect();
    // The launcher first (the mockup's "Vibb"), then by the name the parent sees.
    let launcher: Option<i64> = tracked.iter().find(|t| t.is_launcher).map(|t| t.id);
    apps.sort_by_key(|row| (Some(row.id) != launcher, row.label.to_lowercase()));

    Html(
        AppsListTemplate {
            lang,
            title: t(lang, "nav.apps").to_string(),
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
    lang: Lang,
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
    /// The field the error belongs to ("asset_pattern", "release_tag_prefix", "fdroid_repo",
    /// "fdroid_package" or "release_wait_hours").
    error_field: &'static str,
    /// Design 90: the hours a new release waits, as typed (48 suggested).
    release_wait_hours: String,
}
crate::i18n::words!(TrackedAppAddTemplate);

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

pub async fn new_tracked_app_form(lang: Lang) -> impl IntoResponse {
    Html(
        TrackedAppAddTemplate {
            lang,
            title: t(lang, "apps.add.title").to_string(),
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
            release_wait_hours: crate::catalog_holds::DEFAULT_WAIT_HOURS.to_string(),
        }
        .render()
        .unwrap(),
    )
}

/// An F-Droid source as typed (the add and edit forms): the normalised repo and the package, or
/// the reason and the field it belongs to. The launcher's and the music app's packages stay
/// GitHub's (their release workflows sign them; F-Droid would sign with its own key), a system
/// package is never a catalog app, and a package another catalog app (not `this` one) already is
/// is refused - the phones would get two rows for one app. The reasons are in `lang`.
async fn parse_fdroid_source(
    state: &AppState,
    lang: Lang,
    repo: &str,
    package: &str,
    this: Option<i64>,
) -> Result<(String, String), (String, &'static str)> {
    let repo = crate::fdroid::normalize_repo(lang, repo).map_err(|e| (e, "fdroid_repo"))?;
    let package = crate::fdroid::parse_package(lang, package).map_err(|e| (e, "fdroid_package"))?;
    let refused = |text: String| Err((text, "fdroid_package"));
    if crate::handlers::device_api::installed_by_launcher(state, Some(&package)) {
        return refused(t(lang, "apps.fdroid.launcher").to_string());
    }
    if crate::music::MUSIC_PACKAGES.contains(&package.as_str()) {
        return refused(t(lang, "apps.fdroid.music").to_string());
    }
    if never_a_catalog_package(&package) {
        return refused(t_params(
            lang,
            "apps.fdroid.system",
            &[("package", &package)],
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
        Ok(Some(name)) => refused(t_params(
            lang,
            "apps.fdroid.taken",
            &[("name", &name), ("package", &package)],
        )),
        Err(err) => {
            tracing::error!(%err, "can't check the catalog's packages");
            Err((
                t(lang, "apps.fdroid.check_failed").to_string(),
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
    lang: Lang,
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
                lang,
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
        parse_tag_prefix(lang, &field("release_tag_prefix"))
    } else {
        Ok(None)
    };

    // Design 90: a GitHub or F-Droid app's wait; an upload's is never read (the column keeps its
    // default).
    // A post without the field (a form from before it, a script) takes the suggested 48; a field
    // sent is checked.
    let wait = match fields.get("release_wait_hours") {
        Some(typed) if source_type != "manual" => crate::catalog_holds::parse_wait(lang, typed),
        _ => Ok(crate::catalog_holds::DEFAULT_WAIT_HOURS),
    };

    // A refused save shows the form again with everything as entered (400, nothing written).
    // It posts back to its own path, so static/scroll-restore.js keeps the scroll position.
    let refused = match (
        validate_asset_pattern(lang, asset_pattern.as_deref()),
        &tag_prefix,
        &fdroid,
        &wait,
    ) {
        (Err(error), _, _, _) => Some((error, "asset_pattern")),
        (Ok(()), Err(error), _, _) => Some((error.clone(), "release_tag_prefix")),
        (Ok(()), Ok(_), Some(Err((error, field))), _) => Some((error.clone(), *field)),
        (Ok(()), Ok(_), _, Err(error)) => Some((error.clone(), "release_wait_hours")),
        (Ok(()), Ok(_), _, Ok(_)) => None,
    };
    if let Some((error, error_field)) = refused {
        let page = TrackedAppAddTemplate {
            lang,
            title: t(lang, "apps.add.title").to_string(),
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
            release_wait_hours: fields
                .get("release_wait_hours")
                .cloned()
                .unwrap_or_else(|| crate::catalog_holds::DEFAULT_WAIT_HOURS.to_string()),
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
         include_prereleases, release_tag_prefix, fdroid_repo, fdroid_package, \
         release_wait_hours) VALUES (?, '', ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
    )
    .bind(field("name").trim())
    .bind(source_type)
    .bind(&github_repo)
    .bind(&asset_pattern)
    .bind(include_prereleases)
    .bind(&tag_prefix)
    .bind(&fdroid_repo)
    .bind(&fdroid_package)
    .bind(wait.unwrap_or(crate::catalog_holds::DEFAULT_WAIT_HOURS))
    .fetch_one(&state.db)
    .await
    .expect("failed to create tracked app");

    Redirect::to(&format!("/apps/tracked/{id}")).into_response()
}

#[derive(Template)]
#[template(path = "tracked_app_detail.html")]
struct TrackedAppDetailTemplate {
    lang: Lang,
    title: String,
    app: TrackedApp,
    /// The app's tile at the band's right (design 101 §7): as the list draws it, the logo, else the glyph, else the
    /// letter; the launcher shows the Vibb mark.
    tile: AppTile,
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
    /// The version the running sync downloads ("New version found, downloading").
    sync_downloading: Option<String>,
    /// The last Check Now found nothing new (and nothing waits or is held).
    nothing_new: bool,
    /// How the last sync (hourly or "Check now") failed, if it did.
    sync_failure: Option<FailureView>,
    /// Name and icon (design 14): the default for every phone with the app
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
    /// Source & Signing's value: "GitHub", "F-Droid" (or another repo's host), "Uploaded" (worded).
    source_label: String,
    /// An F-Droid app's source row: "F-Droid · ws.xsoh.etar", the package's page on f-droid.org,
    /// and the one-source line behind its (i).
    fdroid_source: Option<String>,
    fdroid_page: Option<String>,
    fdroid_note: &'static str,
    /// Design 90: the `#update` group (held, waiting, declined), the served release's "Asks for"
    /// row, and the wait field under Updates (`None`: no wait - a manual app, or ours).
    update: Option<UpdateGroup>,
    asks: Option<AsksRow>,
    wait: Option<WaitField>,
}

impl TrackedAppDetailTemplate {
    /// The upload field's limit, which `static/apk-upload.js` checks before the file is sent.
    fn upload_max_bytes(&self) -> u64 {
        MAX_UPLOAD_BYTES
    }

    /// The release label field's `maxlength`.
    fn label_max_chars(&self) -> usize {
        MAX_LABEL_CHARS
    }

    /// The upload's too-big message, as the server's own answer words it.
    fn upload_too_big(&self) -> String {
        t_params(
            self.lang,
            "apps.app.upload_too_big",
            &[("limit", &gb_text(MAX_UPLOAD_BYTES))],
        )
    }
}
crate::i18n::words!(TrackedAppDetailTemplate);

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

impl Flash {
    /// A refused upload or package: the problem's title in `lang`, the raw error behind ⓘ.
    fn failed(failure: Failure, lang: Lang) -> Self {
        Flash {
            title: failure.problem.title(lang).to_string(),
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
fn note_of(notice: Option<&str>, lang: Lang) -> Option<Note> {
    match notice? {
        "uploaded" => Some(Note::Upload(Flash::done(t(lang, "apps.app.uploaded")))),
        "package_confirmed" => Some(Note::Package(Flash::done(t(
            lang,
            "apps.app.package_confirmed",
        )))),
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

/// `?open=source`: a save returns with Source & Signing open, so the form it came from is where the parent left it
/// (Vises som is a section of its own since design 127: its save lands on `#display`).
pub async fn view_tracked_app(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    axum::extract::Query(query): axum::extract::Query<HashMap<String, String>>,
) -> impl IntoResponse {
    let note = note_of(query.get("notice").map(String::as_str), lang);
    // Install Now's answer (design 37): `?install=<word>&phone=<id>`.
    let install = query.get("install").map(|word| {
        (
            word.as_str(),
            query.get("phone").and_then(|p| p.parse::<i64>().ok()),
        )
    });
    let extras = Extras {
        held_flash: query.get("held").and_then(|word| held_flash(lang, word)),
        ..Extras::default()
    };
    render_detail_full(
        &state,
        id,
        note,
        None,
        None,
        query.get("open").map(String::as_str),
        install,
        extras,
        lang,
    )
    .await
}

async fn render_detail(state: &AppState, id: i64, refused: Option<Note>, lang: Lang) -> Response {
    render_detail_full(
        state,
        id,
        refused,
        None,
        None,
        None,
        None,
        Extras::default(),
        lang,
    )
    .await
}

async fn render_detail_page(
    state: &AppState,
    id: i64,
    details: Option<DetailsForm>,
    lang: Lang,
) -> Response {
    render_detail_full(
        state,
        id,
        None,
        details,
        None,
        None,
        None,
        Extras::default(),
        lang,
    )
    .await
}

/// What else the app's page may show from a form's answer (design 90): the update group's flash,
/// and a refused wait (as typed, and why).
#[derive(Default)]
struct Extras {
    held_flash: Option<(String, bool)>,
    wait_refused: Option<(String, String)>,
}

/// The app's page (design 31 §3.3); `display` = a refused Name and icon save (design 14),
/// `open` = the `<details>` a save returns to ("display", "source"), `install` = Install Now's
/// answer (design 37: the word and the phone it names).
#[allow(clippy::too_many_arguments)]
async fn render_detail_full(
    state: &AppState,
    id: i64,
    refused: Option<Note>,
    details: Option<DetailsForm>,
    display: Option<crate::app_display::Entered>,
    open: Option<&str>,
    install: Option<(&str, Option<i64>)>,
    extras: Extras,
    lang: Lang,
) -> Response {
    let app = match sqlx::query_as::<_, TrackedApp>("SELECT * FROM tracked_apps WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(app) => app,
        Err(err) => return page_read_failed(id, err, lang),
    };

    let Some(app) = app else {
        return crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.app_not_found");
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
    let source_label = source_label(&app, lang);
    let is_fdroid = app.source_type == "fdroid";
    let fdroid_source = is_fdroid.then(|| format!("{source_label} · {fdroid_package}"));
    let fdroid_page = is_fdroid
        .then(|| crate::fdroid::package_page(fdroid_repo, fdroid_package))
        .flatten();
    let fdroid_note = if fdroid_page.is_some() || source_label == "F-Droid" {
        t(lang, "apps.app.fdroid_note")
    } else {
        t(lang, "apps.app.other_repo_note")
    };
    let zone = crate::display_time::Zone::load(&state.db, lang).await;
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
        lang,
        format!("/apps/tracked/{id}/display#display"),
        "display".to_string(),
        String::new(),
        app.name.clone(),
        &current,
        display.as_ref(),
    );
    // Design 87 §8.2: "App logo" previews as the logo most phones sent, and design 101 §7's tile on the band reads the
    // same list, once (a failed read is logged: the words, and the glyph or the letter, as on the list).
    let logos = match crate::app_logos::catalog_logos(state).await {
        Ok(logos) => logos,
        Err(err) => {
            tracing::error!(app_id = id, %err, "can't read the apps' logos");
            HashMap::new()
        }
    };
    if !app.package_name.is_empty() {
        form.logo = logos.get(&app.package_name).cloned();
        // Design 87b: Automatisk previews the tile most phones drew (a failed read is logged: the letter).
        form.auto_src = crate::app_logos::catalog_tile(state, &app.package_name)
            .await
            .unwrap_or_else(|err| {
                tracing::error!(app_id = id, %err, "can't read the app's Automatic tile");
                None
            });
    }
    let label = app
        .display_label
        .clone()
        .filter(|label| !label.trim().is_empty())
        .unwrap_or_else(|| app.name.clone());
    let tile = app_tile(&app, &label, &logos);
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
        Err(err) => return page_read_failed(id, err, lang),
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
                Err(err) => return page_read_failed(id, err, lang),
            }
        }
        phones.push(row);
    }
    if app.is_launcher
        && let Some((word, phone)) = install
        && !phone.is_some_and(|p| devices.iter().any(|d| d.id == p))
    {
        install_flash = crate::install_now::flash(lang, word);
    }
    let (upload_note, package_note) = match refused {
        Some(Note::Upload(flash)) => (Some(flash), None),
        Some(Note::Package(flash)) => (None, Some(flash)),
        None => (None, None),
    };
    let source_open = upload_note.is_some()
        || package_note.is_some()
        || details.error.is_some()
        || extras.wait_refused.is_some()
        || open == Some("source");
    let held = match held_release(state, id).await {
        Ok(held) => held,
        Err(err) => return page_read_failed(id, err, lang),
    };
    let update = update_group(
        &app,
        app_wait(state, &app),
        held.as_ref(),
        extras.held_flash,
        &zone,
        now,
        lang,
    );
    let asks = asks_row(&app, lang);
    let wait = app_wait(state, &app).map(|hours| match extras.wait_refused {
        Some((typed, error)) => WaitField {
            value: typed,
            error: Some(error),
        },
        None => WaitField {
            value: hours.to_string(),
            error: None,
        },
    });
    Html(
        TrackedAppDetailTemplate {
            lang,
            title: app.name.clone(),
            tile,
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
            sync_downloading: sync.downloading,
            // Code QA L4: not beside a release held, waiting or declined (the update group says what
            // there is), and never on a disabled app (the hourly sync, which would clear it, skips it).
            nothing_new: sync.nothing_new
                && app.enabled
                && update.as_ref().is_none_or(|u| {
                    u.held.is_none() && u.waiting.is_none() && u.declined.is_none()
                }),
            sync_failure: sync.last_error.map(|failed| FailureView {
                title: failed.failure.problem.title(lang),
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
            update,
            asks,
            wait,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

/// The `#update` group (design 90 §9.1): the release held for the parent, the one waiting out the
/// app's wait, the one the parent declined, and a form's answer.
struct UpdateGroup {
    held: Option<HeldView>,
    waiting: Option<WaitingView>,
    declined: Option<ReleaseView>,
    flash: Option<(String, bool)>,
}

/// A release a form on the group names, and its words.
pub(crate) struct ReleaseView {
    pub(crate) tag: String,
    pub(crate) asset_id: i64,
    pub(crate) title: String,
}

/// The held release: its row, the version the phones keep, and the ⓘ's lists. Also the app's own page on a phone
/// (design 127 §2.3).
pub(crate) struct HeldView {
    pub(crate) release: ReleaseView,
    pub(crate) sub: String,
    pub(crate) keep: String,
    pub(crate) about: String,
    /// A line per group it newly asks for ("Contacts - new"), then the sensitive ones it had
    /// already.
    pub(crate) new_lines: Vec<String>,
    pub(crate) had: Option<String>,
    /// Every permission it declares, as named.
    pub(crate) names: Vec<String>,
    pub(crate) keeps: String,
    /// Held for the server (design 140 §4.3): the title is the orange line, `sub` the text behind
    /// its (i); no Approve or Keep.
    pub(crate) server: bool,
}

/// The orange line of a held release (design 140 §4.3), the one helper for every page: "2.0
/// waits for you", or for a release of ours that needs a newer server "2.0 waits for server
/// 0.96.0".
pub(crate) fn held_line(lang: Lang, version: &str, min_server: Option<&str>) -> String {
    match min_server {
        Some(server) => t_params(
            lang,
            "apps.update.server_title",
            &[("version", version), ("server", server)],
        ),
        None => t_params(lang, "apps.update.held_title", &[("version", version)]),
    }
}

/// The server a held release waits for, when it is held for the server (reason `server`).
pub(crate) fn held_for_server(held: &HeldRelease) -> Option<&str> {
    (held.reason == crate::catalog_holds::Reason::Server.key())
        .then_some(held.min_server.as_deref())
        .flatten()
}

/// A held release's row as the version lines read it: the app id or name, the tag, the app's tag
/// prefix, and (design 140) the reason or label and the server it waits for.
type HeldRow<K> = (K, String, Option<String>, String, Option<String>);

/// The release that waits: its row and the sub-line.
struct WaitingView {
    release: ReleaseView,
    sub: String,
}

/// The wait field under Updates: the stored hours, or what was typed with the reason.
struct WaitField {
    value: String,
    error: Option<String>,
}

/// "Asks for" under Source & Signing › Release: the served release's sensitive groups and every
/// permission it declares.
struct AsksRow {
    value: String,
    names: Vec<String>,
}

/// The served release's "Asks for" row (§9.1); none before a release.
fn asks_row(app: &TrackedApp, lang: Lang) -> Option<AsksRow> {
    app.latest_release_tag.as_ref()?;
    let Some(names) = crate::catalog_holds::read_names(app.latest_release_permissions.as_deref())
    else {
        return Some(AsksRow {
            value: t(lang, "apps.app.asks_unread").to_string(),
            names: Vec::new(),
        });
    };
    let groups = crate::catalog_holds::groups(&names);
    let value = if groups.is_empty() {
        t(lang, "apps.app.asks_nothing").to_string()
    } else {
        capitalised(
            &groups
                .iter()
                .map(|group| crate::catalog_holds::group_in(lang, group))
                .collect::<Vec<_>>()
                .join(", "),
        )
    };
    Some(AsksRow { value, names })
}

/// The held release's words (design 90 §9.1): the catalog page's `#update` group and the app's page on a phone
/// (design 127 §2.3).
pub(crate) fn held_view(app: &TrackedApp, held: &HeldRelease, lang: Lang) -> HeldView {
    let prefix = app.release_tag_prefix.as_deref();
    let served = app
        .latest_release_tag
        .as_deref()
        .map(|tag| version_of(tag, prefix))
        .unwrap_or_default();
    let version = version_of(&held.release_tag, prefix);
    if let Some(server) = held_for_server(held) {
        let sub = if app.latest_release_tag.is_some() {
            t_params(
                lang,
                "apps.update.server_info",
                &[
                    ("served", &served),
                    ("server", server),
                    ("version", &version),
                ],
            )
        } else {
            t_params(
                lang,
                "apps.update.server_info_first",
                &[("server", server), ("version", &version)],
            )
        };
        return HeldView {
            release: ReleaseView {
                tag: held.release_tag.clone(),
                asset_id: held.asset_id,
                title: held_line(lang, &version, Some(server)),
            },
            sub,
            keep: String::new(),
            about: t(lang, "common.more_info").to_string(),
            new_lines: Vec::new(),
            had: None,
            names: Vec::new(),
            keeps: String::new(),
            server: true,
        };
    }
    let new = crate::catalog_holds::read_names(Some(&held.new_permissions)).unwrap_or_default();
    let new_groups = crate::catalog_holds::groups(&new);
    let declared = crate::catalog_holds::read_names(held.permissions.as_deref());
    let had_groups: Vec<&str> = declared
        .as_deref()
        .map(crate::catalog_holds::groups)
        .unwrap_or_default()
        .into_iter()
        .filter(|group| !new_groups.contains(group))
        .collect();
    let in_words = |groups: &[&str]| {
        groups
            .iter()
            .map(|group| crate::catalog_holds::group_in(lang, group))
            .collect::<Vec<_>>()
    };
    let sub = match crate::catalog_holds::Reason::from_key(&held.reason) {
        Some(crate::catalog_holds::Reason::Unreadable) => {
            t(lang, "apps.update.unreadable").to_string()
        }
        Some(crate::catalog_holds::Reason::AlwaysAsk) => t(lang, "apps.update.always").to_string(),
        _ => t_params(
            lang,
            "apps.update.asks",
            &[(
                "groups",
                &crate::catalog_holds::groups_sentence(lang, &new_groups),
            )],
        ),
    };
    HeldView {
        release: ReleaseView {
            tag: held.release_tag.clone(),
            asset_id: held.asset_id,
            title: t_params(lang, "apps.update.held_title", &[("version", &version)]),
        },
        sub,
        keep: t_params(lang, "apps.update.keep", &[("version", &served)]),
        about: t_params(lang, "apps.update.held_about", &[("version", &version)]),
        new_lines: new_groups
            .iter()
            .map(|group| {
                t_params(
                    lang,
                    "apps.update.new_group",
                    &[("group", &crate::catalog_holds::group_title(lang, group))],
                )
            })
            .collect(),
        had: (!had_groups.is_empty()).then(|| {
            t_params(
                lang,
                "apps.update.had",
                &[("groups", &in_words(&had_groups).join(", "))],
            )
        }),
        names: declared.unwrap_or_default(),
        keeps: t_params(lang, "apps.update.keeps_until", &[("version", &served)]),
        server: false,
    }
}

/// The app's held release on a phone's page (design 127 §2.3): `None` when nothing is held (or the app is gone).
pub(crate) async fn held_for_page(
    state: &AppState,
    id: i64,
    lang: Lang,
) -> Result<Option<HeldView>, sqlx::Error> {
    let Some(held) = held_release(state, id).await? else {
        return Ok(None);
    };
    let app: Option<TrackedApp> = sqlx::query_as("SELECT * FROM tracked_apps WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await?;
    Ok(app.map(|app| held_view(&app, &held, lang)))
}

/// Every held release's version, and the server it waits for when held for the server (design
/// 140), by catalog app (design 127 §2.2: the list's orange "7.25.0 venter på deg", [held_line]).
pub(crate) async fn held_versions(
    state: &AppState,
) -> Result<HashMap<i64, (String, Option<String>)>, sqlx::Error> {
    let rows: Vec<HeldRow<i64>> = sqlx::query_as(
        "SELECT h.tracked_app_id, h.release_tag, t.release_tag_prefix, h.reason, h.min_server \
         FROM tracked_app_held_releases h JOIN tracked_apps t ON t.id = h.tracked_app_id",
    )
    .fetch_all(&state.db)
    .await?;
    Ok(rows
        .into_iter()
        .map(|(id, tag, prefix, reason, min_server)| {
            let server = (reason == crate::catalog_holds::Reason::Server.key())
                .then_some(min_server)
                .flatten();
            (id, (version_of(&tag, prefix.as_deref()), server))
        })
        .collect())
}

/// Software Updates' orange lines (design 140 §4.3): "{app} {version} waits for server {server}"
/// for every release of ours held for the server, by the app's name; a failed read shows none.
pub(crate) async fn server_hold_lines(state: &AppState, lang: Lang) -> Vec<String> {
    let rows: Vec<HeldRow<String>> = match sqlx::query_as(
        "SELECT t.name, h.release_tag, t.release_tag_prefix, COALESCE(t.display_label, ''), \
             h.min_server \
             FROM tracked_app_held_releases h JOIN tracked_apps t ON t.id = h.tracked_app_id \
             WHERE h.reason = 'server' ORDER BY t.is_launcher DESC, t.name",
    )
    .fetch_all(&state.db)
    .await
    {
        Ok(rows) => rows,
        Err(err) => {
            tracing::error!(%err, "can't read the releases held for the server");
            return Vec::new();
        }
    };
    rows.into_iter()
        .map(|(name, tag, prefix, label, server)| {
            let app = if label.trim().is_empty() { name } else { label };
            t_params(
                lang,
                "apps.update.server_line",
                &[
                    ("app", &app),
                    ("version", &version_of(&tag, prefix.as_deref())),
                    ("server", server.as_deref().unwrap_or_default()),
                ],
            )
        })
        .collect()
}

/// The `#update` group's content; `None` when nothing is held, waiting or declined and no answer
/// is shown - and always for an app with no wait and no hold (ours, a manual one).
#[allow(clippy::too_many_arguments)]
fn update_group(
    app: &TrackedApp,
    wait: Option<i64>,
    held: Option<&HeldRelease>,
    flash: Option<(String, bool)>,
    zone: &crate::display_time::Zone,
    now: chrono::DateTime<chrono::Utc>,
    lang: Lang,
) -> Option<UpdateGroup> {
    // Ours have no wait and no parent hold (design 90), only a hold for the server (design 140).
    let wait = match wait {
        Some(wait) => wait,
        None => {
            held.filter(|held| held_for_server(held).is_some())?;
            0
        }
    };
    let prefix = app.release_tag_prefix.as_deref();
    let served = app
        .latest_release_tag
        .as_deref()
        .map(|tag| version_of(tag, prefix))
        .unwrap_or_default();
    let held = held.map(|held| held_view(app, held, lang));
    let waiting = match (
        &app.waiting_release_tag,
        app.waiting_release_asset_id,
        app.waiting_release_since
            .as_deref()
            .and_then(crate::display_time::parse_db_utc),
    ) {
        (Some(tag), Some(asset_id), Some(since)) => {
            let ready = crate::catalog_holds::ready_at(since, wait);
            let published = app
                .waiting_release_published_at
                .as_deref()
                .and_then(crate::display_time::parse_db_utc);
            let (key, at) = match published {
                Some(published) => ("apps.update.published_at", published),
                None => ("apps.update.found_at", since),
            };
            Some(WaitingView {
                release: ReleaseView {
                    tag: tag.clone(),
                    asset_id,
                    title: t_params(
                        lang,
                        "apps.update.waiting_title",
                        &[
                            ("version", &version_of(tag, prefix)),
                            ("when", &zone.until(ready, now)),
                        ],
                    ),
                },
                sub: t_params(lang, key, &[("when", &zone.since(at, now))]),
            })
        }
        _ => None,
    };
    let declined = match (&app.declined_release_tag, app.declined_release_asset_id) {
        (Some(tag), Some(asset_id)) => Some(ReleaseView {
            tag: tag.clone(),
            asset_id,
            title: t_params(
                lang,
                "apps.update.declined_title",
                &[("kept", &served), ("version", &version_of(tag, prefix))],
            ),
        }),
        _ => None,
    };
    if held.is_none() && waiting.is_none() && declined.is_none() && flash.is_none() {
        return None;
    }
    Some(UpdateGroup {
        held,
        waiting,
        declined,
        flash,
    })
}

/// What the app's page calls its source: "GitHub", "F-Droid" (another repo: its host),
/// "Uploaded" (in `lang`).
fn source_label(app: &TrackedApp, lang: Lang) -> String {
    match app.source_type.as_str() {
        "github" => t(lang, "apps.source.github").to_string(),
        "fdroid" => crate::fdroid::repo_label(app.fdroid_repo.as_deref().unwrap_or_default()),
        _ => t(lang, "apps.app.source_uploaded").to_string(),
    }
}

/// The app's page can't be read: a 500, never a page with a made-up part.
fn page_read_failed(id: i64, err: sqlx::Error, lang: Lang) -> Response {
    tracing::error!(app_id = id, %err, "can't read the app's page");
    crate::i18n::error(lang, StatusCode::INTERNAL_SERVER_ERROR, "error.read_app")
}

/// How long "Check now" waits for the source's listing at most before answering (qa-catalog #1:
/// the request used to wait up to the 30 min download cap). Under scroll-restore's 20 s, so the
/// page comes back where it was.
const CHECK_NOW_WAIT: Duration = Duration::from_secs(10);

/// How often Check Now looks whether its sync has started a download.
const CHECK_NOW_POLL: Duration = Duration::from_millis(50);

/// Starts a sync of the app (unless one runs - then the page says so) and redirects back to the
/// app's page once the source's listing is read: the sync's end (a failure, nothing new, a
/// release waiting or held), or "New version found, downloading" - the download goes on in the
/// background and the page refreshes in place until it ends (`data-waits`). The release cached
/// already is never downloaded again ([sync_release]).
pub async fn check_now(State(state): State<AppState>, lang: Lang, Path(id): Path<i64>) -> Response {
    let exists: Option<i64> = sqlx::query_scalar("SELECT id FROM tracked_apps WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
        .ok()
        .flatten();
    if exists.is_none() {
        return crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.app_not_found");
    }

    // Started here, so the wait below never looks before the sync has begun. One that runs already
    // (the hourly sync, another Check Now) is what the page shows, refreshing until it ends, and its
    // end answers this press too - "No new version" included (code QA L3). It isn't forced: a
    // release refused before is tried again by the next Check Now, not by that sync.
    let Some(guard) = state.app_syncs.start(id) else {
        state.app_syncs.ask_check(id);
        return Redirect::to(&format!("/apps/tracked/{id}#check")).into_response();
    };
    // In its own task: a large download still finishes, and is cached, after this answers.
    let task_state = state.clone();
    let mut sync = tokio::spawn(async move {
        let result = sync_one_app(&task_state, id, true).await;
        if let Err(e) = &result {
            tracing::warn!("tracked app {id} check failed: {e}");
        }
        guard.end(&result, true);
    });
    let downloading = async {
        while state.app_syncs.downloading(id).is_none() {
            tokio::time::sleep(CHECK_NOW_POLL).await;
        }
    };
    let _ = tokio::time::timeout(CHECK_NOW_WAIT, async {
        tokio::select! {
            _ = &mut sync => {}
            () = downloading => {}
        }
    })
    .await;
    Redirect::to(&format!("/apps/tracked/{id}#check")).into_response()
}

/// Manual-source apps' only path to a new release: the admin types a label
/// (there's no GitHub tag to borrow one from) and uploads an APK directly,
/// same shape as the old launcher_releases upload flow this replaces.
pub async fn upload_tracked_app_release(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    headers: axum::http::HeaderMap,
    mut multipart: Multipart,
) -> impl IntoResponse {
    let app = sqlx::query_as::<_, TrackedApp>("SELECT * FROM tracked_apps WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
        .ok()
        .flatten();

    let Some(app) = app else {
        return crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.app_not_found");
    };
    // An F-Droid app takes only the repo's APKs, each checked against the repo's index: an
    // upload would be a file nothing checked.
    if app.source_type == "fdroid" {
        let mut page = render_detail(
            &state,
            id,
            Some(Note::Upload(Flash {
                title: t(lang, "apps.app.fdroid_upload").to_string(),
                detail: Some(t(lang, "apps.app.fdroid_upload_detail").to_string()),
                ok: false,
            })),
            lang,
        )
        .await;
        if page.status() == StatusCode::OK {
            *page.status_mut() = StatusCode::BAD_REQUEST;
        }
        return page;
    }
    // A body the route's limit will stop anyway is answered before a byte of it is read (code QA L5).
    let length = headers
        .get(axum::http::header::CONTENT_LENGTH)
        .and_then(|value| value.to_str().ok()?.parse::<u64>().ok());
    if length.is_some_and(|length| length > UPLOAD_BODY_LIMIT as u64) {
        return upload_too_big(&state, id, lang).await;
    }
    // One upload at a time (code QA M1): two 2 GB files at once would race the free-space check.
    let Ok(_permit) = state.apk_upload.try_acquire() else {
        return upload_answer(
            &state,
            id,
            Flash::title(t(lang, "apps.app.upload_busy")),
            StatusCode::CONFLICT,
            lang,
        )
        .await;
    };

    let app_dir = state.tracked_apps_dir.join(id.to_string());
    if tokio::fs::create_dir_all(&app_dir).await.is_err() {
        return render_detail(
            &state,
            id,
            Some(Note::Upload(Flash::title(t(
                lang,
                "apps.app.upload_failed",
            )))),
            lang,
        )
        .await;
    }
    let file_path = app_dir
        .join(format!("{}.apk", random_label()))
        .to_string_lossy()
        .into_owned();
    // Removed on every answer but the stored release's (`keep` below).
    let placed = TempDownload::new(file_path.clone());
    // The data disk keeps the music upload's floor (`music::MIN_FREE_BYTES`, 1 GB) under the file
    // (code QA M1): refused up front when the body's size and the floor don't fit, and stopped
    // while streaming once the floor is reached (a body without a length, the disk filling).
    let free = crate::music::free_bytes(&app_dir);
    if free.is_none_or(|free| free < MIN_FREE_BYTES.saturating_add(length.unwrap_or(0))) {
        return upload_no_space(&state, id, lang).await;
    }

    // The APK is streamed to its file and hashed on the way (upstream review 2026-11-20: it was
    // read whole into memory under a 200 MB body limit, and a bigger one read as a missing file).
    let mut release_label: Option<String> = None;
    let mut upload: Option<Result<Downloaded, Failure>> = None;
    let mut apk_name: Option<String> = None;
    let mut too_big = false;
    let mut no_space = false;
    loop {
        let field = match multipart.next_field().await {
            Ok(Some(field)) => field,
            Ok(None) => break,
            Err(err) => {
                too_big |= err.status() == StatusCode::PAYLOAD_TOO_LARGE;
                break;
            }
        };
        let name = field.name().unwrap_or("").to_string();
        match name.as_str() {
            "release_label" => {
                release_label = read_label(field).await;
            }
            // A browser sends an empty file field when none was chosen: missing, as no field.
            "apk"
                if upload.is_none() && field.file_name().is_some_and(|n| !n.trim().is_empty()) =>
            {
                apk_name = field
                    .file_name()
                    .map(|n| n.trim().chars().take(200).collect::<String>())
                    .filter(|n| !n.is_empty());
                let mut source = UploadField {
                    field,
                    guard: DiskGuard::new(&app_dir, MIN_FREE_BYTES),
                    received: 0,
                    too_big: false,
                    no_space: false,
                };
                let result = stream_capped(
                    &mut source,
                    &file_path,
                    0,
                    None,
                    DOWNLOAD_STALL_TIMEOUT,
                    MAX_UPLOAD_BYTES,
                )
                .await;
                too_big |= source.too_big
                    || matches!(&result, Err(failure) if failure.problem == SyncProblem::TooBig);
                no_space |= source.no_space;
                upload = Some(result);
                if too_big || no_space {
                    break;
                }
            }
            _ => {}
        }
    }

    if too_big {
        return upload_too_big(&state, id, lang).await;
    }
    if no_space {
        return upload_no_space(&state, id, lang).await;
    }
    let (Some(release_label), Some(upload)) = (release_label, upload) else {
        return render_detail(
            &state,
            id,
            Some(Note::Upload(Flash::title(t(
                lang,
                "apps.app.upload_missing",
            )))),
            lang,
        )
        .await;
    };
    if release_label.is_empty() {
        return render_detail(
            &state,
            id,
            Some(Note::Upload(Flash::title(t(
                lang,
                "apps.app.upload_missing",
            )))),
            lang,
        )
        .await;
    }
    let uploaded = match upload {
        Ok(uploaded) => uploaded,
        // Not a ZIP (an empty file included): the APK can't be read, as `apk_identity` says -
        // worded for a file the parent chose, not a download (code QA L10).
        Err(failure) if failure.detail == NOT_A_ZIP => {
            return render_detail(
                &state,
                id,
                Some(Note::Upload(Flash {
                    title: SyncProblem::Unreadable.title(lang).to_string(),
                    detail: Some(t(lang, "apps.app.upload_not_apk").to_string()),
                    ok: false,
                })),
                lang,
            )
            .await;
        }
        Err(failure) => {
            tracing::warn!(app_id = id, "upload not stored: {}", failure.detail);
            return render_detail(
                &state,
                id,
                Some(Note::Upload(Flash::title(t(
                    lang,
                    "apps.app.upload_failed",
                )))),
                lang,
            )
            .await;
        }
    };

    let identity = match apk_identity(&file_path)
        .await
        .map_err(|err| Failure::new(SyncProblem::Unreadable, err))
        .and_then(|identity| {
            check_pins(
                &app,
                &identity,
                key_rule(&state, &app, identity.package.as_deref()),
            )
            .map(|()| identity)
        }) {
        Ok(identity) => identity,
        Err(err) => {
            tokio::fs::remove_file(&file_path).await.ok();
            return render_detail(
                &state,
                id,
                Some(Note::Upload(Flash::failed(err, lang))),
                lang,
            )
            .await;
        }
    };
    if let Err(err) = check_new_package(&state, &app, &identity).await {
        tokio::fs::remove_file(&file_path).await.ok();
        return render_detail(
            &state,
            id,
            Some(Note::Upload(Flash::failed(err, lang))),
            lang,
        )
        .await;
    }
    // Design 140 §4.2 (QA M3): a file of ours that needs a newer server, or whose need can't be
    // read, is refused at once - the upload is the parent's own act, so it is told, never parked.
    let refused = match server_need(&state, &app, &identity) {
        crate::catalog_holds::ServerNeed::Serve => None,
        crate::catalog_holds::ServerNeed::Hold(needed) => Some(t_params(
            lang,
            "apps.app.upload_needs_server",
            &[("server", &crate::catalog_holds::version_text(needed))],
        )),
        crate::catalog_holds::ServerNeed::Refuse => {
            Some(t(lang, "apps.app.upload_no_min_server").to_string())
        }
    };
    if let Some(title) = refused {
        tokio::fs::remove_file(&file_path).await.ok();
        return upload_answer(
            &state,
            id,
            Flash::title(&title),
            StatusCode::BAD_REQUEST,
            lang,
        )
        .await;
    }

    let sha256 = uploaded.sha256;
    // Design 90 §5: an upload is the parent's own act with the file in hand - never waited for or
    // held, and what it asks for is added to the set the parent accepted. A release held meanwhile
    // stays held until answered (§17.3 L8).
    let accepted = widened(
        crate::catalog_holds::read_names(app.accepted_permissions.as_deref()).as_deref(),
        identity.permissions.as_deref().unwrap_or_default(),
    );
    // From here the file is the row's or `replace_cached_file`'s to remove, also when the request
    // goes away mid-UPDATE (code QA L4).
    placed.keep();
    // The old file is the one the row names at the UPDATE, read in its transaction - not the one
    // read at the start, which a store meanwhile replaced (code QA L3).
    let stored: Result<Option<Option<String>>, sqlx::Error> = async {
        let mut tx = state.db.begin().await?;
        let old: Option<Option<String>> =
            sqlx::query_scalar("SELECT latest_release_file_path FROM tracked_apps WHERE id = ?")
                .bind(id)
                .fetch_optional(&mut *tx)
                .await?;
        let updated = sqlx::query(
            "UPDATE tracked_apps SET latest_release_tag = ?, latest_release_asset_id = NULL, \
             latest_release_file_path = ?, latest_release_asset_name = ?, \
             latest_release_asset_size = ?, latest_release_sha256 = ?, \
             latest_release_version_code = ?, latest_release_permissions = ?, \
             accepted_permissions = ?, last_checked_at = datetime('now') WHERE id = ?",
        )
        .bind(&release_label)
        .bind(&file_path)
        .bind(&apk_name)
        .bind(uploaded.size as i64)
        .bind(&sha256)
        .bind(identity.version_code)
        .bind(
            identity
                .permissions
                .as_deref()
                .map(crate::catalog_holds::names_json),
        )
        .bind(&accepted)
        .bind(id)
        .execute(&mut *tx)
        .await?
        .rows_affected()
            > 0;
        sqlx::query(CLEAR_FAILED_INSTALLS)
            .bind(id)
            .execute(&mut *tx)
            .await?;
        tx.commit().await?;
        Ok(old.filter(|_| updated))
    }
    .await;
    let (stored, old_path) = match stored {
        Ok(Some(old_path)) => (true, old_path),
        _ => (false, None),
    };
    replace_cached_file(stored, &file_path, old_path.as_deref()).await;
    if !stored {
        return render_detail(
            &state,
            id,
            Some(Note::Upload(Flash::title(t(
                lang,
                "apps.app.upload_failed",
            )))),
            lang,
        )
        .await;
    }
    store_pins(&state, &app, &identity).await;
    crate::handlers::device_api::nudge_phones_with_app(&state, id).await;

    // Back to the app's page at the Upload group, with the result there (a reload doesn't post
    // the file again; [Note]).
    Redirect::to(&format!("/apps/tracked/{id}?notice=uploaded#upload")).into_response()
}

/// The app's page with an upload's answer in the Upload group, under `status`.
async fn upload_answer(
    state: &AppState,
    id: i64,
    flash: Flash,
    status: StatusCode,
    lang: Lang,
) -> Response {
    let mut page = render_detail(state, id, Some(Note::Upload(flash)), lang).await;
    if page.status() == StatusCode::OK {
        *page.status_mut() = status;
    }
    page
}

/// An upload over [MAX_UPLOAD_BYTES] (413).
async fn upload_too_big(state: &AppState, id: i64, lang: Lang) -> Response {
    let title = t_params(
        lang,
        "apps.app.upload_too_big",
        &[("limit", &gb_text(MAX_UPLOAD_BYTES))],
    );
    upload_answer(
        state,
        id,
        Flash::title(&title),
        StatusCode::PAYLOAD_TOO_LARGE,
        lang,
    )
    .await
}

/// An upload that would take the data disk below `music::MIN_FREE_BYTES` (507).
async fn upload_no_space(state: &AppState, id: i64, lang: Lang) -> Response {
    let flash = Flash {
        title: t(lang, "apps.app.upload_no_space").to_string(),
        detail: Some(t_params(
            lang,
            "apps.app.upload_no_space_detail",
            &[("gb", &(MIN_FREE_BYTES / 1_000_000_000).to_string())],
        )),
        ok: false,
    };
    upload_answer(state, id, flash, StatusCode::INSUFFICIENT_STORAGE, lang).await
}

/// `POST /apps/tracked/{id}/display` (design 14): the catalog default for how the app shows on the
/// kid's phones (by the row's package name; a phone's own choice replaces it); it may be any of
/// design 34's three states. `action` "reset" clears it, "save" (or none) saves, anything else is a 400. Refused input: 400, the page with the entered values, the error by the field and
/// `aria-invalid`. Design 127 §2.4: `icon_mode` (Logo · Automatisk · Symbol) wins over `icon`. Saved: back to Vises
/// som (`#display`); nudges every phone (cosmetic, cheap).
pub async fn save_display(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let field = |name: &str| form.get(name).map(String::as_str).unwrap_or("");
    let reset = match field("action") {
        "reset" => true,
        "" | "save" => false,
        _ => return crate::i18n::error(lang, StatusCode::BAD_REQUEST, "error.out_of_date"),
    };
    // Design 127 §2.4: the segmented control's `icon_mode` wins over `icon`; Symbol without a glyph is the catalog's
    // own glyph, else the first.
    let fallback: Option<Option<String>> =
        match sqlx::query_scalar("SELECT display_icon FROM tracked_apps WHERE id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await
        {
            Ok(icon) => icon,
            Err(err) => return page_read_failed(id, err, lang),
        };
    let fallback = crate::app_display::fallback_glyph(
        Some(&crate::app_display::DisplayValues {
            label: None,
            icon: fallback.flatten(),
            color: crate::app_display::AUTO.to_string(),
        }),
        None,
    );
    let chosen = crate::app_display::icon_of_form(
        form.get("icon_mode").map(String::as_str),
        field("icon"),
        &fallback,
    )
    .map(|(icon, colour)| {
        let color = if colour { field("color") } else { "" };
        (icon, color.to_string())
    });
    let (icon, color, refused) = match chosen {
        Ok((icon, color)) => (icon, color, None),
        Err(error) => (
            field("icon").to_string(),
            field("color").to_string(),
            Some(error),
        ),
    };
    let values = if reset {
        crate::app_display::DisplayValues::empty()
    } else {
        match refused.map_or_else(
            || crate::app_display::validate(field("label"), &icon, &color),
            Err,
        ) {
            Ok(values) => values,
            Err(error) => {
                let entered = crate::app_display::Entered {
                    label: field("label").to_string(),
                    icon,
                    color,
                    error,
                };
                let mut page = render_detail_full(
                    &state,
                    id,
                    None,
                    None,
                    Some(entered),
                    None,
                    None,
                    Extras::default(),
                    lang,
                )
                .await;
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
        Ok(r) if r.rows_affected() == 0 => {
            crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.app_not_found")
        }
        Ok(_) => {
            // Every phone may have the app: nudge them all (a sync is cheap).
            let devices: Vec<i64> = sqlx::query_scalar("SELECT id FROM devices")
                .fetch_all(&state.db)
                .await
                .unwrap_or_default();
            for device in devices {
                let _ = state.command_notify.send(device);
            }
            Redirect::to(&format!("/apps/tracked/{id}#display")).into_response()
        }
        Err(err) => {
            tracing::error!(app_id = id, %err, "couldn't save the app's name and icon");
            crate::i18n::error(lang, StatusCode::INTERNAL_SERVER_ERROR, "error.save_failed")
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
    lang: Lang,
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
                t_params(
                    lang,
                    "apps.app.source_fixed",
                    &[("source", &source_label(&app, lang))],
                )
            } else {
                t(lang, "apps.app.source_chosen").to_string()
            }
        });
    let fdroid = if app.source_type == "fdroid" && source_refused.is_none() {
        Some(
            parse_fdroid_source(
                &state,
                lang,
                &field("fdroid_repo"),
                &field("fdroid_package"),
                Some(app.id),
            )
            .await
            .and_then(|(repo, package)| {
                pinned_fdroid_change(&app, lang, &repo, &package).map(|()| (repo, package))
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
            parse_tag_prefix(lang, &field("release_tag_prefix")),
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
        validate_asset_pattern(lang, asset_pattern.as_deref()),
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
        let mut page = render_detail_page(&state, id, Some(details), lang).await;
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
    // Design 90 §7: another repo or package is another source - its held, waiting and declined
    // releases were another's.
    if github_repo != app.github_repo
        || fdroid_repo != app.fdroid_repo
        || fdroid_package != app.fdroid_package
        || asset_pattern != app.asset_pattern
        || tag_prefix != app.release_tag_prefix
    {
        forget_holds(&state, id, false).await;
    }

    Redirect::to(&format!("/apps/tracked/{id}?open=source#details")).into_response()
}

/// Once an F-Droid app is pinned, its package and repo stay (qa-fdroid-source-code Q-5): another
/// package is another app (the next sync would refuse it as `OtherApp`), and another repo may
/// sign it with another key (`OtherKey`) - a change the phones could only take with a reinstall,
/// so it is refused at the form with the source change's words (in `lang`). Before the first
/// release both stay editable.
fn pinned_fdroid_change(
    app: &TrackedApp,
    lang: Lang,
    repo: &str,
    package: &str,
) -> Result<(), (String, &'static str)> {
    let release = if app.latest_release_tag.is_some() {
        t(lang, "apps.app.pinned_release")
    } else {
        t(lang, "apps.app.pinned_first_release")
    };
    if !app.package_name.is_empty() && package != app.package_name {
        return Err((
            t_params(
                lang,
                "apps.app.pinned_package",
                &[("package", &app.package_name), ("release", release)],
            ),
            "fdroid_package",
        ));
    }
    if app.signer_sha256.is_some() && app.fdroid_repo.as_deref() != Some(repo) {
        return Err((
            t_params(
                lang,
                "apps.app.pinned_repo",
                &[("release", release), ("source", &source_label(app, lang))],
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
    // A repo no app uses any more forgets its key and its newest entry.json too (round S4 and its
    // fix round): adding an app of it again pins the key of that first fetch - the way to take a
    // repo's real key change.
    for table in ["fdroid_repo_keys", "fdroid_repo_state"] {
        if let Err(err) = sqlx::query(&format!(
            "DELETE FROM {table} WHERE repo NOT IN (SELECT fdroid_repo FROM tracked_apps \
             WHERE source_type = 'fdroid' AND fdroid_repo IS NOT NULL)"
        ))
        .execute(&state.db)
        .await
        {
            tracing::warn!(%err, table, "can't remove the unused F-Droid repo rows");
        }
    }
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
             turn on \"Launcher\" on its page under Apps (no second row is \
             created)"
        ),
        Ok(LauncherSeed::NotGithub) => tracing::warn!(
            "LAUNCHER_APK_URL ({}) isn't a github.com/<owner>/<repo>/releases URL - no catalog \
             row for the launcher was created: add it under Apps and turn on \"Launcher\"",
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
    lang: Lang,
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
                    title: t(lang, "apps.app.not_installed").to_string(),
                    detail: Some(t_params(
                        lang,
                        "apps.app.not_installed_detail",
                        &[("package", &form.package)],
                    )),
                    ok: false,
                })),
                lang,
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
            Some(Note::Package(Flash::title(t(
                lang,
                "apps.app.confirm_failed",
            )))),
            lang,
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
    // The kept logo (0155) was the old package's: it goes with the name, and its file when nothing else names it.
    let kept: Option<String> =
        sqlx::query_scalar("SELECT logo_hash FROM tracked_apps WHERE id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await
            .ok()
            .flatten()
            .flatten();
    sqlx::query(
        "UPDATE tracked_apps SET package_name = '', signer_sha256 = NULL, logo_hash = NULL WHERE id = ?",
    )
    .bind(id)
    .execute(&state.db)
    .await
    .ok();
    let kept: Vec<String> = kept.into_iter().collect();
    crate::app_logos::prune_hashes(&state, &kept).await;
    // A release refused for the old pins is downloaded again at the next sync.
    state.app_syncs.set_refused(id, None);
    // Design 90 §17.3 M4: a new key is a new trust - the accepted set goes, and the next release
    // that asks for anything sensitive waits for the parent; the held, waiting and declined
    // releases go with the pins.
    forget_holds(&state, id, true).await;

    Redirect::to(&format!("/apps/tracked/{id}?open=source#release"))
}

pub async fn set_include_prereleases(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> impl IntoResponse {
    let include_prereleases = form.contains_key("include_prereleases");
    let changed = sqlx::query(
        "UPDATE tracked_apps SET include_prereleases = ? WHERE id = ? AND include_prereleases != ?",
    )
    .bind(include_prereleases)
    .bind(id)
    .bind(include_prereleases)
    .execute(&state.db)
    .await
    .is_ok_and(|result| result.rows_affected() > 0);
    // Design 90 QA L2: other releases are listed - the held, waiting and declined ones were the
    // old list's.
    if changed {
        forget_holds(&state, id, false).await;
    }

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

    // The catalog's kept logo (0155) goes with the row; read before the delete.
    let kept: Option<String> =
        sqlx::query_scalar("SELECT logo_hash FROM tracked_apps WHERE id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await
            .ok()
            .flatten()
            .flatten();
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
    // Its file, when nothing else names it.
    let kept: Vec<String> = kept.into_iter().collect();
    crate::app_logos::prune_hashes(&state, &kept).await;

    Redirect::to("/apps")
}

/// The exact release a form on the app's `#update` group names (design 90 §17.3: approve,
/// decline, Ask Again and Update Now act only on it).
#[derive(Deserialize)]
pub struct ReleaseForm {
    tag: String,
    asset_id: i64,
    /// Design 127 §2.3: `device:<id>` - the answer goes to the app's page on that phone, not the catalog's.
    #[serde(default)]
    back: Option<String>,
    /// The phone page's `?from=` (`device_pages::AppsFrom`), kept with `back`.
    #[serde(default)]
    from: Option<String>,
}

/// Back to the app's update group with a form's answer (`?held=<word>`, [held_flash]).
fn held_answer(id: i64, word: &str) -> Response {
    Redirect::to(&format!("/apps/tracked/{id}?held={word}#update")).into_response()
}

/// Approve's and Keep's answer (design 127 §2.3): with `back=device:<id>` the app's page on that phone, at its head
/// (`/devices/{d}/apps/{package}?held=<word>&from=…#app-head`) - only when the phone exists and the catalog row's
/// package is a valid one; anything else is [held_answer].
async fn held_back(state: &AppState, id: i64, word: &str, form: &ReleaseForm) -> Response {
    let device = form
        .back
        .as_deref()
        .and_then(|back| back.strip_prefix("device:"))
        .filter(|d| !d.is_empty() && d.len() <= 18 && d.bytes().all(|b| b.is_ascii_digit()))
        .and_then(|d| d.parse::<i64>().ok());
    let Some(device) = device else {
        return held_answer(id, word);
    };
    let found: Result<Option<(i64, String)>, sqlx::Error> = sqlx::query_as(
        "SELECT d.id, t.package_name FROM devices d, tracked_apps t WHERE d.id = ? AND t.id = ?",
    )
    .bind(device)
    .bind(id)
    .fetch_optional(&state.db)
    .await;
    match found {
        Ok(Some((device, package))) if crate::app_display::valid_package_name(&package) => {
            let from = form
                .from
                .as_deref()
                .and_then(AppsFrom::parse)
                .map(|from| format!("&from={}", from.value()))
                .unwrap_or_default();
            Redirect::to(&format!(
                "/devices/{device}/apps/{package}?held={word}{from}#app-head"
            ))
            .into_response()
        }
        Ok(_) => held_answer(id, word),
        Err(err) => {
            tracing::error!(app_id = id, %err, "can't read the phone a held answer returns to");
            held_answer(id, word)
        }
    }
}

/// A `?held=` word's flash in the update group, and whether it is good news; `None` for anything
/// else.
pub(crate) fn held_flash(lang: Lang, word: &str) -> Option<(String, bool)> {
    let (key, ok) = match word {
        "approved" => ("apps.update.flash.approved", true),
        "declined" => ("apps.update.flash.declined", true),
        "asked_again" => ("apps.update.flash.asked_again", true),
        "sent" => ("apps.update.flash.sent", true),
        "waits" => ("apps.update.flash.waits", false),
        "stale" => ("apps.update.flash.stale", false),
        "busy" => ("apps.update.flash.busy", false),
        "gone" => ("apps.update.flash.gone", false),
        "refused" => ("apps.update.flash.refused", false),
        _ => return None,
    };
    Some((t(lang, key).to_string(), ok))
}

/// `POST /apps/tracked/{id}/held/approve` (design 90 §7, §17.3 M3): serves the held release the
/// form names, under the app's sync guard - its file read and checked again against the row as it
/// is now (the pins, a new package, its SHA-256) and moved into the served release in one
/// transaction with what it asks for added to the accepted set; then the old file goes, the pins
/// are stored and the phones nudged. No inbox row: the parent just did it. A failed check drops
/// the held release and shows the sync's failure.
pub async fn approve_held(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    Form(form): Form<ReleaseForm>,
) -> Response {
    let Some(guard) = state.app_syncs.start(id) else {
        return held_back(&state, id, "busy", &form).await;
    };
    let (app, held) = match exact_held(&state, id, &form).await {
        Ok(Some(found)) => found,
        Ok(None) => return held_back(&state, id, "stale", &form).await,
        Err(err) => return page_read_failed(id, err, lang),
    };
    // Design 140 §4.2: a release held for the server waits for no answer.
    if held.reason == crate::catalog_holds::Reason::Server.key() {
        return held_back(&state, id, "stale", &form).await;
    }
    // Not ahead of the served release any more (an upload meanwhile): never a step back (QA L2);
    // Keep still answers it.
    if app.latest_release_tag.is_some() && !release_ahead(&app, &held.release_tag, held.asset_id) {
        return held_back(&state, id, "stale", &form).await;
    }
    match approve(&state, &app, &held).await {
        Ok(identity) => {
            drop(guard);
            let prefix = app.release_tag_prefix.as_deref();
            let new =
                crate::catalog_holds::read_names(Some(&held.new_permissions)).unwrap_or_default();
            let mut line = crate::security::Detail::words(
                "security.detail.catalog_release_approved",
                &[
                    ("app", &app.name),
                    ("version", &version_of(&held.release_tag, prefix)),
                ],
            );
            for group in crate::catalog_holds::groups(&new) {
                if let Some((_, _, within)) = crate::catalog_holds::GROUPS
                    .iter()
                    .find(|(key, _, _)| *key == group)
                {
                    line = line
                        .and("security.detail.catalog_release_asks", &[])
                        .word("group", within);
                }
            }
            crate::security::record_security_event(
                &state.db,
                "catalog_release_approved",
                Some(&admin.username),
                None,
                Some(line),
            )
            .await;
            store_pins(&state, &app, &identity).await;
            crate::handlers::device_api::nudge_phones_with_app(&state, id).await;
            held_back(&state, id, "approved", &form).await
        }
        Err(failure) => {
            tracing::warn!(app_id = id, "the held release wasn't approved: {failure}");
            drop_held(&state, id, &held).await;
            guard.finish(&Err(failure));
            held_back(&state, id, "refused", &form).await
        }
    }
}

/// The app and its held release when the release is the one `form` names; `Ok(None)` when it
/// isn't (answered, superseded) or the app is gone.
async fn exact_held(
    state: &AppState,
    id: i64,
    form: &ReleaseForm,
) -> Result<Option<(TrackedApp, HeldRelease)>, sqlx::Error> {
    let app: Option<TrackedApp> = sqlx::query_as("SELECT * FROM tracked_apps WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await?;
    let Some(app) = app else {
        return Ok(None);
    };
    Ok(held_release(state, id)
        .await?
        .filter(|held| held.release_tag == form.tag && held.asset_id == form.asset_id)
        .map(|held| (app, held)))
}

/// [approve_held]'s work, in `sync_release`'s order; the identity read again, for the pins.
async fn approve(
    state: &AppState,
    app: &TrackedApp,
    held: &HeldRelease,
) -> Result<crate::apk::ApkIdentity, Failure> {
    use SyncProblem::*;
    let identity = apk_identity(&held.file_path)
        .await
        .map_err(|e| Failure::new(Unreadable, e))?;
    check_pins(
        app,
        &identity,
        key_rule(state, app, identity.package.as_deref()),
    )?;
    check_new_package(state, app, &identity).await?;
    let path = std::path::PathBuf::from(&held.file_path);
    let sha256 = tokio::task::spawn_blocking(move || sha256_of_file(&path))
        .await
        .map_err(|e| Failure::new(Server, e.to_string()))?
        .map_err(|e| Failure::new(Server, e.to_string()))?;
    if sha256 != held.sha256 {
        return Err(Failure::new(
            ChecksumMismatch,
            "refused: the held release's file changed since it was downloaded",
        ));
    }
    let accepted = widened(
        crate::catalog_holds::read_names(app.accepted_permissions.as_deref()).as_deref(),
        identity.permissions.as_deref().unwrap_or_default(),
    );
    let server = |e: sqlx::Error| Failure::new(Server, e.to_string());
    let mut tx = state.db.begin().await.map_err(server)?;
    let stored = sqlx::query(
        "UPDATE tracked_apps SET latest_release_tag = ?, latest_release_asset_id = ?, \
         latest_release_file_path = ?, latest_release_asset_name = ?, \
         latest_release_asset_size = ?, latest_release_sha256 = ?, \
         latest_release_version_code = ?, latest_release_permissions = ?, \
         accepted_permissions = ? WHERE id = ?",
    )
    .bind(&held.release_tag)
    .bind(held.asset_id)
    .bind(&held.file_path)
    .bind(&held.asset_name)
    .bind(held.asset_size)
    .bind(&held.sha256)
    .bind(identity.version_code)
    .bind(
        identity
            .permissions
            .as_deref()
            .map(crate::catalog_holds::names_json),
    )
    .bind(&accepted)
    .bind(app.id)
    .execute(&mut *tx)
    .await
    .map_err(server)?
    .rows_affected()
        > 0;
    sqlx::query("DELETE FROM tracked_app_held_releases WHERE tracked_app_id = ?")
        .bind(app.id)
        .execute(&mut *tx)
        .await
        .map_err(server)?;
    sqlx::query(CLEAR_FAILED_INSTALLS)
        .bind(app.id)
        .execute(&mut *tx)
        .await
        .map_err(server)?;
    tx.commit().await.map_err(server)?;
    replace_cached_file(
        stored,
        &held.file_path,
        app.latest_release_file_path.as_deref(),
    )
    .await;
    if !stored {
        return Err(Failure::new(Removed, "the app was removed"));
    }
    state.app_syncs.set_refused(app.id, None);
    clear_waiting_if(state, app.id, &held.release_tag, held.asset_id).await;
    Ok(identity)
}

/// `POST /apps/tracked/{id}/held/decline` ("Keep 7.2.0", design 90 §7): the held release the form
/// names goes (row and file), and is remembered as declined - the sync never takes it, or one
/// behind it, again; the next release ahead of it is judged afresh.
pub async fn decline_held(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    Form(form): Form<ReleaseForm>,
) -> Response {
    let Some(guard) = state.app_syncs.start(id) else {
        return held_back(&state, id, "busy", &form).await;
    };
    let (app, held) = match exact_held(&state, id, &form).await {
        Ok(Some(found)) => found,
        Ok(None) => return held_back(&state, id, "stale", &form).await,
        Err(err) => return page_read_failed(id, err, lang),
    };
    // Design 140 §4.2: a release held for the server waits for no answer.
    if held.reason == crate::catalog_holds::Reason::Server.key() {
        return held_back(&state, id, "stale", &form).await;
    }
    let declined = sqlx::query(
        "UPDATE tracked_apps SET declined_release_tag = ?, declined_release_asset_id = ? \
         WHERE id = ?",
    )
    .bind(&held.release_tag)
    .bind(held.asset_id)
    .bind(id)
    .execute(&state.db)
    .await;
    if let Err(err) = declined {
        tracing::error!(app_id = id, %err, "can't decline the held release");
        return crate::i18n::error(lang, StatusCode::INTERNAL_SERVER_ERROR, "error.save_failed");
    }
    drop_held(&state, id, &held).await;
    drop(guard);
    let prefix = app.release_tag_prefix.as_deref();
    let kept = app
        .latest_release_tag
        .as_deref()
        .map(|tag| version_of(tag, prefix))
        .unwrap_or_default();
    crate::security::record_security_event(
        &state.db,
        "catalog_release_declined",
        Some(&admin.username),
        None,
        Some(crate::security::Detail::words(
            "security.detail.catalog_release_declined",
            &[
                ("app", &app.name),
                ("version", &version_of(&held.release_tag, prefix)),
                ("kept", &kept),
            ],
        )),
    )
    .await;
    held_back(&state, id, "declined", &form).await
}

/// `POST /apps/tracked/{id}/declined/ask-again` (design 90 §7): forgets the declined release the
/// form names and starts a check, as "Check Now" does - which holds it again.
pub async fn ask_again(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Form(form): Form<ReleaseForm>,
) -> Response {
    let Some(guard) = state.app_syncs.start(id) else {
        return held_answer(id, "busy");
    };
    let cleared = sqlx::query(
        "UPDATE tracked_apps SET declined_release_tag = NULL, declined_release_asset_id = NULL \
         WHERE id = ? AND declined_release_tag = ? AND declined_release_asset_id = ?",
    )
    .bind(id)
    .bind(&form.tag)
    .bind(form.asset_id)
    .execute(&state.db)
    .await;
    drop(guard);
    match cleared {
        Ok(result) if result.rows_affected() == 0 => return held_answer(id, "stale"),
        Ok(_) => {}
        Err(err) => {
            tracing::error!(app_id = id, %err, "can't forget the declined release");
            return crate::i18n::error(
                lang,
                StatusCode::INTERNAL_SERVER_ERROR,
                "error.save_failed",
            );
        }
    }
    let task_state = state.clone();
    let sync = tokio::spawn(async move {
        if let Err(SyncError::Failed(e)) = sync_app(&task_state, id, true).await {
            tracing::warn!("tracked app {id} check failed: {e}");
        }
    });
    let _ = tokio::time::timeout(CHECK_NOW_WAIT, sync).await;
    held_answer(id, "asked_again")
}

/// `POST /apps/tracked/{id}/update-now` (design 90 §6): the parent's one-shot override of the
/// wait for the exact release the form names - the source listed again under the app's sync
/// guard and that release synced at once. It still is judged: one that asks for something new is
/// held (D6). Gone from the list: "isn't offered any more", nothing else. A long download goes on
/// after the answer, as "Check Now"'s does.
pub async fn update_now(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<ReleaseForm>,
) -> Response {
    let Some(guard) = state.app_syncs.start(id) else {
        return held_answer(id, "busy");
    };
    let task_state = state.clone();
    let sync = tokio::spawn(async move {
        let result = update_now_sync(&task_state, id, &form).await;
        let word = match &result {
            Ok(Some(word)) => *word,
            Ok(None) => "gone",
            Err(_) => "refused",
        };
        guard.finish(&result.map(|_| ()));
        word
    });
    match tokio::time::timeout(CHECK_NOW_WAIT, sync).await {
        Ok(Ok(word)) => held_answer(id, word),
        _ => Redirect::to(&format!("/apps/tracked/{id}#update")).into_response(),
    }
}

/// [update_now]'s sync: `Ok(None)` when the release isn't listed any more; else "sent" or "waits"
/// (held for the parent).
async fn update_now_sync(
    state: &AppState,
    id: i64,
    form: &ReleaseForm,
) -> Result<Option<&'static str>, Failure> {
    let app = read_app(state, id).await?;
    if app_wait(state, &app).is_none() {
        return Ok(None);
    }
    let listed = list_releases(state, &app, false, false)
        .await?
        .unwrap_or_default();
    let Some(release) = listed
        .iter()
        .find(|l| l.tag == form.tag && l.asset.id == form.asset_id)
    else {
        return Ok(None);
    };
    // Never a step back (QA L3): a release not ahead of the served one isn't sent.
    if app.latest_release_tag.is_some() && !release_ahead(&app, &release.tag, release.asset.id) {
        return Ok(Some("stale"));
    }
    sync_release(state, &app, &release.tag, &release.asset, true).await?;
    let held = held_release(state, id)
        .await
        .map_err(|e| Failure::new(SyncProblem::Server, e.to_string()))?;
    Ok(Some(
        if held.is_some_and(|h| h.release_tag == release.tag && h.asset_id == release.asset.id) {
            "waits"
        } else {
            "sent"
        },
    ))
}

/// `POST /apps/tracked/{id}/release-wait` (design 90 §17.3 M2): the hours a new release waits. A
/// whole number from 0 to 336, else 400 with the page, the typed value and the reason by the
/// field, focused - at `#updates`. Saved: the Security Log line (shortening the wait is a security
/// choice the other parent should see) and back to `#updates`. Not for a manual app or ours.
pub async fn save_release_wait(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    Form(fields): Form<HashMap<String, String>>,
) -> Response {
    let app = match read_app(&state, id).await {
        Ok(app) => app,
        Err(_) => return crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.app_not_found"),
    };
    let Some(was) = app_wait(&state, &app) else {
        return Redirect::to(&format!("/apps/tracked/{id}?open=source#updates")).into_response();
    };
    let typed = fields
        .get("release_wait_hours")
        .cloned()
        .unwrap_or_default();
    let hours = match crate::catalog_holds::parse_wait(lang, &typed) {
        Ok(hours) => hours,
        Err(error) => {
            let mut page = render_detail_full(
                &state,
                id,
                None,
                None,
                None,
                Some("source"),
                None,
                Extras {
                    wait_refused: Some((typed, error)),
                    ..Extras::default()
                },
                lang,
            )
            .await;
            *page.status_mut() = StatusCode::BAD_REQUEST;
            return page;
        }
    };
    let saved = sqlx::query("UPDATE tracked_apps SET release_wait_hours = ? WHERE id = ?")
        .bind(hours)
        .bind(id)
        .execute(&state.db)
        .await;
    if let Err(err) = saved {
        tracing::error!(app_id = id, %err, "can't save the app's wait");
        return crate::i18n::error(lang, StatusCode::INTERNAL_SERVER_ERROR, "error.save_failed");
    }
    if hours != was {
        crate::security::record_security_event(
            &state.db,
            "catalog_release_wait_changed",
            Some(&admin.username),
            None,
            Some(crate::security::Detail::counted(
                "security.detail.catalog_release_wait_changed",
                hours,
                &[("app", &app.name), ("was", &was.to_string())],
            )),
        )
        .await;
    }
    Redirect::to(&format!("/apps/tracked/{id}?open=source#updates")).into_response()
}

/// `POST /apps/tracked/{id}/always-ask` (design 90 D9): "Ask me before every update". Turning it
/// off leaves a release held for it alone held - the parent answers it. Not for a manual app or
/// ours.
pub async fn set_always_ask(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> impl IntoResponse {
    sqlx::query(
        "UPDATE tracked_apps SET always_ask = ? WHERE id = ? AND source_type IN ('github', \
         'fdroid') AND is_launcher = 0",
    )
    .bind(form.contains_key("always_ask"))
    .bind(id)
    .execute(&state.db)
    .await
    .ok();
    Redirect::to(&format!("/apps/tracked/{id}?open=source#updates"))
}

/// Forgets what design 90 kept for the app's releases: the held release (row and file), the
/// waiting and the declined one - another key or source is another app (§7, "Removal of the
/// cause"); with `accepted` also the accepted set (Forget, §17.3 M4: the next sensitive release
/// waits for the parent).
async fn forget_holds(state: &AppState, id: i64, accepted: bool) {
    // Under the app's sync guard (QA L2), waited for up to Check Now's 10 s; a long download still
    // running then finds the row changed and holds nothing ([hold_release]).
    let mut guard = state.app_syncs.start(id);
    let waited = tokio::time::Instant::now();
    while guard.is_none() && waited.elapsed() < CHECK_NOW_WAIT {
        tokio::time::sleep(Duration::from_millis(200)).await;
        guard = state.app_syncs.start(id);
    }
    if let Ok(Some(held)) = held_release(state, id).await {
        drop_held(state, id, &held).await;
    }
    let sql = if accepted {
        "UPDATE tracked_apps SET waiting_release_tag = NULL, waiting_release_asset_id = NULL, \
         waiting_release_published_at = NULL, waiting_release_since = NULL, \
         declined_release_tag = NULL, declined_release_asset_id = NULL, \
         accepted_permissions = NULL WHERE id = ?"
    } else {
        "UPDATE tracked_apps SET waiting_release_tag = NULL, waiting_release_asset_id = NULL, \
         waiting_release_published_at = NULL, waiting_release_since = NULL, \
         declined_release_tag = NULL, declined_release_asset_id = NULL WHERE id = ?"
    };
    if let Err(err) = sqlx::query(sql).bind(id).execute(&state.db).await {
        tracing::error!(app_id = id, %err, "can't forget the app's held and waiting releases");
    }
    drop(guard);
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
            "/devices/5/apps/io.element.android.x?from=app:9"
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
            published_at: None,
            assets: assets
                .iter()
                .map(|(id, name)| ReleaseAsset {
                    id: *id,
                    name: name.to_string(),
                    browser_download_url: format!("https://example.org/{name}"),
                    size: 0,
                    sha256: None,
                    index_signers: None,
                    updated_at: None,
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
        let filter = AssetFilter::parse(pattern).map_err(|r| r.words(Lang::En))?;
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

    /// GitHub's list as it was on 2026-11-20: the stable launcher-v0.41.0 (published 08:43) listed
    /// above launcher-v0.42.0-rc.1 (published 14:53). With prereleases the RC is the newest; a release
    /// without a readable time goes last; without prereleases the stable one stays.
    #[test]
    fn the_newest_published_wins_whatever_githubs_order() {
        let at = |tag: &str, pre: bool, id: i64, published: Option<&str>| GithubRelease {
            published_at: published.map(str::to_string),
            ..release(tag, pre, &[(id, "kids-launcher-mdm.apk")])
        };
        let list = || {
            vec![
                at("launcher-v0.41.0", false, 1, Some("2026-11-20T08:43:10Z")),
                at("launcher-v0.40.0", false, 9, None),
                at(
                    "launcher-v0.42.0-rc.1",
                    true,
                    2,
                    Some("2026-11-20T14:53:40Z"),
                ),
                at(
                    "launcher-v0.41.0-rc.7",
                    true,
                    3,
                    Some("2026-11-20T08:23:23Z"),
                ),
            ]
        };
        let tags = |pre: bool| {
            matching_releases(list(), pre, &AssetFilter::FirstApk, Some("launcher-v"))
                .into_iter()
                .map(|(r, _)| r.tag_name)
                .collect::<Vec<_>>()
        };
        assert_eq!(
            tags(true),
            [
                "launcher-v0.42.0-rc.1",
                "launcher-v0.41.0",
                "launcher-v0.41.0-rc.7",
                "launcher-v0.40.0"
            ]
        );
        assert_eq!(tags(false), ["launcher-v0.41.0", "launcher-v0.40.0"]);
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
        assert_eq!(parse_tag_prefix(Lang::En, "  "), Ok(None));
        assert_eq!(
            parse_tag_prefix(Lang::En, " launcher-v "),
            Ok(Some("launcher-v".to_string()))
        );
        assert!(parse_tag_prefix(Lang::En, "-v").is_err());
        assert!(parse_tag_prefix(Lang::En, "a b").is_err());
        assert!(parse_tag_prefix(Lang::En, &"a".repeat(33)).is_err());
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
            reason.words(Lang::En),
            "not a valid regular expression (unclosed group) - a filter starting with ^ is read \
             as one"
        );
        let message = validate_asset_pattern(Lang::En, Some("^[")).unwrap_err();
        assert!(message.starts_with("The file name filter is not a valid regular expression"));
        assert!(message.ends_with("Nothing was saved."), "{message}");
        // Over the compiled size limit (qa-catalog #5): refused, not compiled with 10 MiB.
        let huge = AssetFilter::parse(Some(r"^(\w{1,100}){1,100}"))
            .err()
            .unwrap()
            .words(Lang::En);
        assert!(huge.contains("(too complex)"), "{huge}");
        // The form's sentence in the reader's language.
        assert_eq!(
            validate_asset_pattern(Lang::Nb, Some(&"a".repeat(MAX_PATTERN_CHARS + 1))).unwrap_err(),
            "Filnavnfilteret er lengre enn 256 tegn. Ingenting ble lagret."
        );
        // Over 256 characters: refused whatever kind of filter it is.
        let long = "a".repeat(MAX_PATTERN_CHARS + 1);
        assert_eq!(
            validate_asset_pattern(Lang::En, Some(&long)).unwrap_err(),
            "The file name filter is longer than 256 characters. Nothing was saved."
        );
        assert!(validate_asset_pattern(Lang::En, Some(&format!("^{}", &long[1..]))).is_err());
        assert!(validate_asset_pattern(Lang::En, Some(&long[1..])).is_ok());
        for ok in [
            None,
            Some(""),
            Some("kids-launcher-mdm.apk"),
            Some("[x"),
            Some(r"^\d+\.apk$"),
        ] {
            assert!(validate_asset_pattern(Lang::En, ok).is_ok(), "{ok:?}");
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

    /// A manual upload streams through the same path with its own cap (upstream review
    /// 2026-11-20): over it is `TooBig`, worded with that cap, and nothing is kept.
    #[tokio::test]
    async fn an_upload_over_its_cap_is_too_big_and_leaves_no_file() {
        let dir = tempfile::tempdir().unwrap();
        let target = dir.path().join("x.apk");
        let target = target.to_str().unwrap();
        let mut source = Canned::new([Step::Data(b"PK\x03\x04abc"), Step::Data(b"defgh")]);
        let err = stream_capped(&mut source, target, 0, None, STALL, 10)
            .await
            .unwrap_err();
        assert_eq!(err.problem, SyncProblem::TooBig, "{err}");
        assert!(files_in(dir.path()).is_empty());
        let mut source = Canned::new([Step::Data(b"PK\x03\x04abc"), Step::Data(b"def")]);
        let kept = stream_capped(&mut source, target, 0, None, STALL, 10)
            .await
            .unwrap();
        assert_eq!(kept.size, 10);
        assert_eq!(gb_text(MAX_UPLOAD_BYTES), "2 GB");
        assert!(UPLOAD_BODY_LIMIT as u64 > MAX_UPLOAD_BYTES && MAX_UPLOAD_BYTES < 1 << 31);
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
            index_signers: None,
            updated_at: None,
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
        assert!(
            SyncProblem::TooBig
                .title(Lang::En)
                .contains(&size_limit_text())
        );
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
        assert_eq!(capitalised(&phones_text(Lang::En, 0)), "No phones");
        assert_eq!(phones_text(Lang::En, 1), "1 phone");
        assert_eq!(phones_text(Lang::En, 2), "2 phones");
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
            RepoSignature,
            RepoKey,
            IndexSigner,
            RepoRollback,
            OlderVersion,
            OwnKey,
            NoOwnKey,
        ];
        for lang in [Lang::En, Lang::Nb] {
            let titles: std::collections::HashSet<&str> =
                all.iter().map(|p| p.title(lang)).collect();
            assert_eq!(titles.len(), all.len());
            for title in titles {
                assert!(title.split_whitespace().count() <= 6, "{title}");
                assert!(!title.ends_with('.'), "{title}");
            }
        }
        // Round S4's are in the parent's language already.
        for problem in [
            RepoSignature,
            RepoKey,
            IndexSigner,
            RepoRollback,
            OlderVersion,
        ] {
            let nb = problem.title(Lang::Nb);
            assert_ne!(nb, problem.title(Lang::En), "{nb}");
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
            index_signers: None,
            updated_at: None,
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

    /// The release key of [our_own_apps_take_only_the_release_key]: `testdata/apk/*-key4.apk`'s
    /// throwaway key (`me.vibb.music`, `me.vibb.launcher`), as `LAUNCHER_SIGNATURE_CHECKSUM` and as
    /// hex.
    const KEY4_CHECKSUM: &str = "9wK758cEFHgRiaJUfORL-tf8hHZGhA40-0qj3QsJmvc";
    const KEY4: &str = "f702bbe7c70414781189a2547ce44bfad7fc847646840e34fb4aa3dd0b099af7";
    const KEY1: &str = "0796f1809e476a2ce724f1341bcb32d03185b7a202c3469787c5775d65ef4be3";

    /// Our own apps are pinned to the release key behind `LAUNCHER_SIGNATURE_CHECKSUM` (key4 here),
    /// never to the first signer seen, and by their packages, whatever the repo: the music app's
    /// release signed with another key is refused on a row with no signer; one cached under
    /// another key before is never listed or downloaded and is forgotten at startup; after
    /// "Forget" the music row takes only the music app's package; the launcher's release with the
    /// key is served at once, never held. Without the checksum nothing cached is forgotten.
    #[tokio::test]
    async fn our_own_apps_take_only_the_release_key() {
        let mut config = crate::config::ForkConfig::for_tests();
        config.launcher_signature_checksum = Some(KEY4_CHECKSUM.to_string());
        let owned = crate::tests::TestApp::with_config(config).await;
        let app = &owned;
        let file = |name: &str| format!("testdata/apk/{name}.apk");
        let server = axum::Router::new().route(
            "/{name}",
            axum::routing::get(
                |axum::extract::Path(name): axum::extract::Path<String>| async move {
                    std::fs::read(format!("testdata/apk/{name}")).unwrap()
                },
            ),
        );
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        tokio::spawn(async move { axum::serve(listener, server).await.unwrap() });
        let asset = |id: i64, name: &str| ReleaseAsset {
            id,
            name: "vibb-music.apk".to_string(),
            browser_download_url: format!("{base}/{name}.apk"),
            size: std::fs::metadata(file(name)).unwrap().len(),
            sha256: None,
            index_signers: None,
            updated_at: None,
        };
        let id: i64 = sqlx::query_scalar(
            "INSERT INTO tracked_apps (name, package_name, source_type, github_repo, \
             release_tag_prefix) VALUES ('Vibb Musikk', 'me.vibb.music', 'github', ?, 'music-v') \
             RETURNING id",
        )
        .bind(&app.state.config.server_release_repo)
        .fetch_one(&app.db)
        .await
        .unwrap();
        let row_of = |id: i64| async move {
            sqlx::query_as::<_, TrackedApp>("SELECT * FROM tracked_apps WHERE id = ?")
                .bind(id)
                .fetch_one(&app.db)
                .await
                .unwrap()
        };
        let row = || row_of(id);
        assert!(matches!(
            key_rule(&app.state, &row().await, None),
            KeyRule::Ours(Some(_), OwnApp::Music)
        ));
        let (phone, token) = app.enrolled_device("Anna").await;
        sqlx::query("INSERT INTO device_tracked_apps (device_id, tracked_app_id) VALUES (?, ?)")
            .bind(phone)
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        let listed = |id: i64| {
            let token = token.clone();
            async move {
                let list = app
                    .request(
                        axum::http::Method::GET,
                        "/api/devices/apps",
                        Some(&token),
                        None,
                    )
                    .await
                    .json();
                list.as_array()
                    .unwrap()
                    .iter()
                    .any(|entry| entry["id"].as_i64() == Some(id))
            }
        };
        let download = |id: i64| {
            let token = token.clone();
            async move {
                app.request(
                    axum::http::Method::GET,
                    &format!("/api/devices/apps/{id}/download"),
                    Some(&token),
                    None,
                )
                .await
                .status
            }
        };

        // No signer pinned: another key (key1) is refused, nothing pinned or cached.
        let refused = sync_release(
            &app.state,
            &row().await,
            "music-v1.0.0",
            &asset(1, "fixture-key1"),
            true,
        )
        .await
        .unwrap_err();
        assert_eq!(refused.problem, SyncProblem::OwnKey, "{refused}");
        assert_eq!(row().await.signer_sha256, None);
        assert_eq!(row().await.latest_release_tag, None);

        // Cached under that key before: never listed or downloaded, forgotten at startup.
        std::fs::create_dir_all(app.state.tracked_apps_dir.as_ref()).unwrap();
        let cached = app.state.tracked_apps_dir.join("cached.apk");
        std::fs::copy(file("fixture-key1"), &cached).unwrap();
        sqlx::query(
            "UPDATE tracked_apps SET signer_sha256 = ?, latest_release_tag = 'music-v0.9.0', \
             latest_release_asset_id = 9, latest_release_file_path = ? WHERE id = ?",
        )
        .bind(KEY1)
        .bind(cached.to_str().unwrap())
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
        assert!(!servable(&app.state, &row().await));
        assert!(!listed(id).await);
        assert_eq!(download(id).await, StatusCode::NOT_FOUND);
        drop_foreign_own_releases(&app.state).await;
        assert_eq!(row().await.latest_release_tag, None, "forgotten at startup");
        assert_eq!(row().await.signer_sha256, None);

        // Without the checksum no new release passes, and nothing cached is forgotten.
        let identity = apk_identity(&file("music-key4")).await.unwrap();
        let unset =
            check_pins(&row().await, &identity, KeyRule::Ours(None, OwnApp::Music)).unwrap_err();
        assert_eq!(unset.problem, SyncProblem::NoOwnKey);
        let mut unset_config = crate::config::ForkConfig::for_tests();
        unset_config.launcher_signature_checksum = None;
        let unset_app = crate::tests::TestApp::with_config(unset_config).await;
        let kept: i64 = sqlx::query_scalar(
            "INSERT INTO tracked_apps (name, package_name, github_repo, is_launcher, \
             latest_release_tag, signer_sha256) VALUES ('Vibb One', 'me.vibb.launcher', 'o/r', 1, \
             'launcher-v1.0.0', ?) RETURNING id",
        )
        .bind(KEY4)
        .fetch_one(&unset_app.db)
        .await
        .unwrap();
        drop_foreign_own_releases(&unset_app.state).await;
        let kept: TrackedApp = sqlx::query_as("SELECT * FROM tracked_apps WHERE id = ?")
            .bind(kept)
            .fetch_one(&unset_app.db)
            .await
            .unwrap();
        assert_eq!(kept.latest_release_tag.as_deref(), Some("launcher-v1.0.0"));
        assert!(servable(&unset_app.state, &kept));

        // After "Forget" (no package) the row is still the music app's: the launcher's package is
        // refused even with the key, the music app's taken, pinned and served.
        sqlx::query("UPDATE tracked_apps SET package_name = '' WHERE id = ?")
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        let refused = sync_release(
            &app.state,
            &row().await,
            "music-v1.0.0",
            &asset(2, "launcher-key4"),
            true,
        )
        .await
        .unwrap_err();
        assert_eq!(refused.problem, SyncProblem::OtherApp, "{refused}");
        sync_release(
            &app.state,
            &row().await,
            "music-v1.0.1",
            &asset(3, "music-key4"),
            true,
        )
        .await
        .unwrap();
        let synced = row().await;
        assert_eq!(synced.signer_sha256.as_deref(), Some(KEY4));
        assert_eq!(synced.package_name, "me.vibb.music");
        assert!(servable(&app.state, &synced));
        assert!(listed(id).await);
        assert_eq!(download(id).await, StatusCode::OK);

        // The launcher's row: the music app's package is refused; its own release is served at
        // once, a later one too (no wait, never held).
        let launcher: i64 = sqlx::query_scalar(
            "INSERT INTO tracked_apps (name, package_name, github_repo, is_launcher) \
             VALUES ('Vibb One', 'me.vibb.launcher', 'o/r', 1) RETURNING id",
        )
        .fetch_one(&app.db)
        .await
        .unwrap();
        let refused = sync_release(
            &app.state,
            &row_of(launcher).await,
            "launcher-v1.0.0",
            &asset(10, "music-key4"),
            true,
        )
        .await
        .unwrap_err();
        assert_eq!(refused.problem, SyncProblem::OtherApp, "{refused}");
        for (tag, asset_id) in [("launcher-v1.0.0", 11), ("launcher-v1.0.1", 12)] {
            sync_release(
                &app.state,
                &row_of(launcher).await,
                tag,
                &asset(asset_id, "launcher-key4"),
                false,
            )
            .await
            .unwrap();
            assert_eq!(
                row_of(launcher).await.latest_release_tag.as_deref(),
                Some(tag)
            );
            assert!(held_release(&app.state, launcher).await.unwrap().is_none());
        }
        assert!(listed(launcher).await);

        // Ours by package, whatever the repo: another repo's row named after either app, or one
        // whose release is one of them.
        let other: i64 = sqlx::query_scalar(
            "INSERT INTO tracked_apps (name, package_name, github_repo) VALUES ('X', '', 'o/r') \
             RETURNING id",
        )
        .fetch_one(&app.db)
        .await
        .unwrap();
        let other_row = row_of(other).await;
        assert!(matches!(
            key_rule(&app.state, &other_row, None),
            KeyRule::Pinned
        ));
        assert!(matches!(
            key_rule(&app.state, &other_row, Some("me.vibb.launcher")),
            KeyRule::Ours(Some(_), OwnApp::Launcher)
        ));
        assert!(matches!(
            key_rule(&app.state, &other_row, Some("me.vibb.music.debug")),
            KeyRule::Ours(Some(_), OwnApp::Music)
        ));
        let foreign = crate::apk::ApkIdentity {
            package: Some("me.vibb.music".to_string()),
            signer_sha256: Some(KEY1.to_string()),
            version_code: None,
            version_name: None,
            min_sdk: None,
            permissions: None,
            split_target: None,
            shared_user_id: false,
            min_server: crate::apk::MinServer::Absent,
        };
        let refused = check_pins(
            &other_row,
            &foreign,
            key_rule(&app.state, &other_row, Some("me.vibb.music")),
        )
        .unwrap_err();
        assert_eq!(refused.problem, SyncProblem::OwnKey);
        sqlx::query("UPDATE tracked_apps SET package_name = 'me.vibb.music' WHERE id = ?")
            .bind(other)
            .execute(&app.db)
            .await
            .unwrap();
        assert!(matches!(
            key_rule(&app.state, &row_of(other).await, None),
            KeyRule::Ours(Some(_), OwnApp::Music)
        ));
    }

    /// A newly stored release nudges exactly the enrolled phones whose list carries the app
    /// (`device_api::scoped_apps`: ticked, or every phone for the launcher); a sync that finds
    /// what's cached, and a refused release, nudge none.
    #[tokio::test]
    async fn a_new_release_nudges_the_phones_that_get_the_app() {
        let mut config = crate::config::ForkConfig::for_tests();
        config.launcher_signature_checksum = Some(KEY4_CHECKSUM.to_string());
        let app = crate::tests::TestApp::with_config(config).await;
        let server = axum::Router::new()
            .route(
                "/launcher-key4.apk",
                axum::routing::get(|| async {
                    std::fs::read("testdata/apk/launcher-key4.apk").unwrap()
                }),
            )
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
            index_signers: None,
            updated_at: None,
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
        // The launcher's row (signed with the release key, key4 here) is on every enrolled phone.
        let launcher: i64 = sqlx::query_scalar(
            "INSERT INTO tracked_apps (name, package_name, github_repo, is_launcher) \
             VALUES ('Vibb One', '', 'o/r', 1) RETURNING id",
        )
        .fetch_one(&app.db)
        .await
        .unwrap();
        let launcher_row =
            sqlx::query_as::<_, TrackedApp>("SELECT * FROM tracked_apps WHERE id = ?")
                .bind(launcher)
                .fetch_one(&app.db)
                .await
                .unwrap();
        let launcher_asset = ReleaseAsset {
            id: 3,
            name: "launcher-key4.apk".to_string(),
            browser_download_url: format!("{base}/launcher-key4.apk"),
            size: std::fs::metadata("testdata/apk/launcher-key4.apk")
                .unwrap()
                .len(),
            sha256: None,
            index_signers: None,
            updated_at: None,
        };
        sync_release(&app.state, &launcher_row, "v3", &launcher_asset, false)
            .await
            .unwrap();
        let mut both = vec![ticked, other];
        both.sort();
        assert_eq!(nudged(), both);
    }

    /// GitHub's asset `digest` (Q27): `sha256:<hex>` is the SHA-256 the download must have;
    /// null, absent, another algorithm or a malformed value is no digest (today's path).
    #[test]
    fn a_github_asset_reads_its_sha256_digest() {
        let hex = "AB".repeat(32);
        let asset = |digest: serde_json::Value| -> ReleaseAsset {
            serde_json::from_value(serde_json::json!({
                "id": 7, "name": "app.apk", "size": 10,
                "browser_download_url": "https://example.org/app.apk", "digest": digest,
            }))
            .unwrap()
        };
        assert_eq!(
            asset(serde_json::json!(format!("sha256:{hex}"))).sha256,
            Some("ab".repeat(32))
        );
        for no in [
            serde_json::json!(null),
            serde_json::json!(format!("sha512:{hex}")),
            serde_json::json!("sha256:abc"),
            serde_json::json!(format!("sha256:{}", "zz".repeat(32))),
            serde_json::json!(hex),
        ] {
            assert_eq!(asset(no.clone()).sha256, None, "{no}");
        }
        let without: ReleaseAsset = serde_json::from_value(serde_json::json!({
            "id": 7, "name": "app.apk", "browser_download_url": "https://example.org/app.apk",
        }))
        .unwrap();
        assert_eq!(without.sha256, None);
    }

    /// A GitHub asset whose download isn't its listed digest is refused and nothing is stored or
    /// pinned (Q27); the right digest, or none, is stored as before.
    #[tokio::test]
    async fn a_github_download_must_match_its_digest() {
        let app = crate::tests::TestApp::new().await;
        let server = axum::Router::new().route(
            "/key1.apk",
            axum::routing::get(|| async {
                std::fs::read("testdata/apk/fixture-key1.apk").unwrap()
            }),
        );
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        tokio::spawn(async move { axum::serve(listener, server).await.unwrap() });
        let (apk, sha) = fixture_apk();
        let asset = |id: i64, digest: Option<String>| ReleaseAsset {
            id,
            name: "key1.apk".to_string(),
            browser_download_url: format!("{base}/key1.apk"),
            size: apk.len() as u64,
            sha256: digest,
            index_signers: None,
            updated_at: None,
        };
        let id: i64 = sqlx::query_scalar(
            "INSERT INTO tracked_apps (name, package_name, github_repo) \
             VALUES ('Fixture', '', 'o/r') RETURNING id",
        )
        .fetch_one(&app.db)
        .await
        .unwrap();
        let wrong = "cd".repeat(32);
        let err = sync_release(
            &app.state,
            &row_of(&app, id).await,
            "v1",
            &asset(1, Some(wrong.clone())),
            false,
        )
        .await
        .unwrap_err();
        assert_eq!(err.problem, SyncProblem::ChecksumMismatch, "{}", err.detail);
        assert!(
            err.detail.contains(&wrong) && err.detail.contains(&sha),
            "{}",
            err.detail
        );
        let row = row_of(&app, id).await;
        assert_eq!(row.latest_release_tag, None);
        assert_eq!(
            row.signer_sha256, None,
            "nothing pinned from an unverified file"
        );
        let app_dir = app.state.tracked_apps_dir.join(id.to_string());
        assert_eq!(files_in(&app_dir), Vec::<String>::new());

        sync_release(
            &app.state,
            &row_of(&app, id).await,
            "v1",
            &asset(2, Some(sha.clone())),
            false,
        )
        .await
        .unwrap();
        let row = row_of(&app, id).await;
        assert_eq!(row.latest_release_tag.as_deref(), Some("v1"));
        assert_eq!(row.latest_release_sha256.as_deref(), Some(sha.as_str()));
        // No digest: stored as before.
        sync_release(
            &app.state,
            &row_of(&app, id).await,
            "v2",
            &asset(3, None),
            false,
        )
        .await
        .unwrap();
        assert_eq!(
            row_of(&app, id).await.latest_release_tag.as_deref(),
            Some("v2")
        );
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

    /// An F-Droid row without a wait (design 90: the wait's own tests set theirs).
    async fn fdroid_row(app: &crate::tests::TestApp, repo: &str, package: &str) -> i64 {
        sqlx::query_scalar(
            "INSERT INTO tracked_apps (name, package_name, source_type, github_repo, fdroid_repo, \
             fdroid_package, release_wait_hours) VALUES ('Etar', '', 'fdroid', '', ?, ?, 0) \
             RETURNING id",
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

    /// Check Now says what it does (PLAN, approved 2026-11-19): it answers once the listing names a
    /// new version, while the APK still downloads - the page says "New version found,
    /// downloading" and refreshes in place (`data-waits`) until the sync ends; a Check Now that
    /// finds the cached version downloads nothing and says "No new version", until the next sync.
    #[tokio::test]
    async fn check_now_answers_at_the_listing_and_says_what_it_found() {
        use crate::fdroid::tests::FakeRepo;
        let app = crate::tests::TestApp::new().await;
        let cookie = app.admin_cookie().await;
        let (repo, fake) = crate::fdroid::tests::fake_repo("127.0.0.1").await;
        let (apk, sha) = fixture_apk();
        serve_fixture(&fake, "me.vibb.testfixture", &sha, &apk);
        let gate = std::sync::Arc::new(tokio::sync::Notify::new());
        *fake.file_gate.lock().unwrap() = Some(gate.clone());
        let id = fdroid_row(&app, &repo, "me.vibb.testfixture").await;
        let page = || async {
            app.get_page(&format!("/apps/tracked/{id}"), &cookie)
                .await
                .text()
        };

        // The APK is held back: Check Now answers anyway, with the download under way.
        let answer = tokio::time::timeout(
            CHECK_NOW_WAIT / 2,
            check_now(State(app.state.clone()), Lang::En, Path(id)),
        )
        .await
        .expect("answers before the download ends");
        assert_eq!(answer.status(), StatusCode::SEE_OTHER);
        assert_eq!(
            app.state.app_syncs.downloading(id).as_deref(),
            Some("1.0.57")
        );
        let html = page().await;
        assert!(html.contains(r#"data-sync="downloading""#), "{html}");
        assert!(html.contains("New version found, downloading"), "{html}");
        assert!(html.contains("data-waits"), "refreshes while it runs");

        gate.notify_one();
        tokio::time::timeout(Duration::from_secs(10), async {
            while app.state.app_syncs.status(id).running_since.is_some() {
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
        })
        .await
        .expect("the sync ends");
        let html = page().await;
        assert!(!html.contains("data-waits"), "{html}");
        assert!(!html.contains(r#"data-sync="downloading""#));
        assert!(!html.contains(r#"data-sync="nothing-new""#), "it found one");
        assert_eq!(
            row_of(&app, id).await.latest_release_tag.as_deref(),
            Some("v1.0.57")
        );
        assert_eq!(FakeRepo::count(&fake.file_gets), 1);

        // Again: the cached version is listed - nothing downloaded, and the page says so.
        check_now(State(app.state.clone()), Lang::En, Path(id)).await;
        assert_eq!(
            FakeRepo::count(&fake.file_gets),
            1,
            "never downloaded again"
        );
        let html = page().await;
        assert!(html.contains(r#"data-sync="nothing-new""#), "{html}");
        assert!(html.contains("No new version"));
        assert!(!html.contains("data-waits"));

        // The next sync (the hourly one) ends it.
        assert!(sync_app(&app.state, id, false).await.is_ok());
        assert!(!page().await.contains(r#"data-sync="nothing-new""#));
        assert_eq!(FakeRepo::count(&fake.file_gets), 1);
    }

    /// Code QA L3 of `server-small`: Check Now pressed while another sync runs (the hourly one) gets
    /// that sync's answer - "No new version" at its end -, while the end of what isn't a sync
    /// (approve, decline) answers nothing.
    #[tokio::test]
    async fn check_now_during_a_sync_is_answered_by_its_end() {
        let app = crate::tests::TestApp::new().await;
        let cookie = app.admin_cookie().await;
        let id = fdroid_row(&app, "https://f-droid.org/repo", "me.vibb.testfixture").await;
        let page = || async {
            app.get_page(&format!("/apps/tracked/{id}"), &cookie)
                .await
                .text()
        };

        let hourly = app.state.app_syncs.start(id).expect("none runs");
        check_now(State(app.state.clone()), Lang::En, Path(id)).await;
        assert!(page().await.contains(r#"data-sync="running""#));
        hourly.end(&Ok(()), false);
        let html = page().await;
        assert!(html.contains(r#"data-sync="nothing-new""#), "{html}");

        // A sync with no press says nothing; nor does an approve's end after a press.
        app.state.app_syncs.start(id).unwrap().end(&Ok(()), false);
        assert!(!page().await.contains(r#"data-sync="nothing-new""#));
        let approve = app.state.app_syncs.start(id).unwrap();
        check_now(State(app.state.clone()), Lang::En, Path(id)).await;
        approve.finish(&Ok(()));
        assert!(!page().await.contains(r#"data-sync="nothing-new""#));
    }

    /// Code QA L4 of `server-small`: "No new version" isn't shown beside a declined release (the
    /// update group says what there is), nor on a disabled app.
    #[tokio::test]
    async fn no_new_version_hides_beside_a_declined_release_and_on_a_disabled_app() {
        let app = crate::tests::TestApp::new().await;
        let cookie = app.admin_cookie().await;
        let id = fdroid_row(&app, "https://f-droid.org/repo", "me.vibb.testfixture").await;
        let shown = || async {
            app.get_page(&format!("/apps/tracked/{id}"), &cookie)
                .await
                .text()
                .contains(r#"data-sync="nothing-new""#)
        };
        app.state.app_syncs.start(id).unwrap().end(&Ok(()), true);
        assert!(shown().await);

        sqlx::query(
            "UPDATE tracked_apps SET latest_release_tag = 'v1.0', latest_release_asset_id = 1, \
             declined_release_tag = 'v1.1', declined_release_asset_id = 2 WHERE id = ?",
        )
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
        assert!(!shown().await, "beside the declined release");

        sqlx::query(
            "UPDATE tracked_apps SET declined_release_tag = NULL, declined_release_asset_id = NULL, \
             enabled = 0 WHERE id = ?",
        )
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
        assert!(!shown().await, "on a disabled app");
        sqlx::query("UPDATE tracked_apps SET enabled = 1 WHERE id = ?")
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        assert!(shown().await);
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
        use crate::fdroid::tests::FakeRepo;
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
        // S4 fix round M1: the hourly sync keeps the cached release; Check Now takes the older one.
        let err = sync_one_app(&app.state, id, false).await.unwrap_err();
        assert_eq!(err.problem, SyncProblem::OlderVersion, "{}", err.detail);
        assert_eq!(
            err.problem.title(crate::i18n::Lang::Nb),
            "Repoet tilbyr en eldre versjon"
        );
        let row = row_of(&app, id).await;
        assert_eq!(
            (
                row.latest_release_tag.as_deref(),
                row.latest_release_asset_id
            ),
            (Some("v1.0.58"), Some(58)),
            "a lower versionCode doesn't replace the cached release"
        );
        assert_eq!(FakeRepo::count(&fake.file_gets), 0, "nothing downloaded");
        sync_one_app(&app.state, id, true).await.unwrap();
        assert_eq!(
            row_of(&app, id).await.latest_release_tag.as_deref(),
            Some("v1.0.57"),
            "the older release is stored by Check Now"
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
        assert_eq!(row.latest_release_version_code, Some(1), "the APK's own");
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
            err.problem.title(Lang::En),
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
        assert_eq!(
            err.problem.title(Lang::En),
            "F-Droid doesn't know this package"
        );
        // Another repo isn't "F-Droid" (Q-9).
        let unknown = fdroid_row(&app, &index_repo, "org.nobody.app").await;
        let err = sync_one_app(&app.state, unknown, false).await.unwrap_err();
        assert_eq!(err.problem, SyncProblem::NotInRepo);
        assert_eq!(
            err.problem.title(Lang::En),
            "The repo doesn't have this package"
        );

        let gone = fdroid_row(&app, "http://127.0.0.1:9/repo", "me.vibb.testfixture").await;
        let err = sync_one_app(&app.state, gone, false).await.unwrap_err();
        assert_eq!(err.problem, SyncProblem::RepoUnreachable);
        assert_eq!(err.problem.title(Lang::En), "The repo didn't answer");

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

    /// Round S4 through a real sync: a repo without a known key is pinned to its entry.jar's key at
    /// the first fetch; at the first pin the APK's signer must be one the index lists (Q32); a
    /// later entry.jar by another key is refused and the cached release stays; the repo's last app
    /// removed, its key goes.
    #[tokio::test]
    async fn an_fdroid_repo_key_is_pinned_and_the_index_signer_checked() {
        use crate::fdroid::tests::FAKE_REPO_KEY;
        let app = crate::tests::TestApp::new().await;
        let (repo, fake) = crate::fdroid::tests::fake_repo("127.0.0.1").await;
        let (apk, sha) = fixture_apk();
        let signer =
            crate::apk::read_identity(std::path::Path::new("testdata/apk/fixture-key1.apk"))
                .unwrap()
                .signer_sha256
                .unwrap();
        serve_fixture(&fake, "me.vibb.testfixture", &sha, &apk);
        let list_signer = |listed: &str| {
            let mut index: serde_json::Value =
                serde_json::from_slice(&fake.index.lock().unwrap()).unwrap();
            index["packages"]["me.vibb.testfixture"]["versions"][&sha]["manifest"]["signer"] =
                serde_json::json!({"sha256": [listed]});
            fake.publish(&index.to_string());
        };
        let pinned_key = || async {
            sqlx::query_scalar::<_, String>(
                "SELECT signer_sha256 FROM fdroid_repo_keys WHERE repo = ?",
            )
            .bind(&repo)
            .fetch_optional(&app.db)
            .await
            .unwrap()
        };
        let id = fdroid_row(&app, &repo, "me.vibb.testfixture").await;

        // The index lists another signer for the APK: refused before anything is pinned.
        list_signer(&"ab".repeat(32));
        let err = sync_one_app(&app.state, id, false).await.unwrap_err();
        assert_eq!(err.problem, SyncProblem::IndexSigner, "{}", err.detail);
        let row = row_of(&app, id).await;
        assert_eq!((row.signer_sha256, row.latest_release_tag), (None, None));
        assert_eq!(
            pinned_key().await,
            Some(FAKE_REPO_KEY.sha256()),
            "the repo's key, first use"
        );

        // S4 fix L3: signers listed, none of them a SHA-256 - refused too, not skipped.
        list_signer("not-a-key");
        let err = sync_one_app(&app.state, id, true).await.unwrap_err();
        assert_eq!(err.problem, SyncProblem::IndexSigner, "{}", err.detail);
        assert!(err.detail.contains("none is a SHA-256"), "{}", err.detail);

        // The index lists the APK's signer: stored and pinned; the entry's timestamp kept (M1).
        *fake.timestamp.lock().unwrap() = 1_760_000_000_000;
        list_signer(&signer.to_uppercase());
        sync_one_app(&app.state, id, true).await.unwrap();
        let row = row_of(&app, id).await;
        assert_eq!(row.signer_sha256.as_deref(), Some(signer.as_str()));
        let stored = row.latest_release_file_path.clone().unwrap();
        let taken = || async {
            sqlx::query_scalar::<_, i64>(
                "SELECT entry_timestamp FROM fdroid_repo_state WHERE repo = ?",
            )
            .bind(&repo)
            .fetch_optional(&app.db)
            .await
            .unwrap()
        };
        assert_eq!(taken().await, Some(1_760_000_000_000));

        // M1: the repo's older entry.jar, genuinely signed, replayed: refused, the release stays.
        sqlx::query("UPDATE tracked_apps SET latest_release_asset_id = 56 WHERE id = ?")
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        *fake.timestamp.lock().unwrap() = 1_750_000_000_000;
        list_signer(&signer);
        let err = sync_one_app(&app.state, id, true).await.unwrap_err();
        assert_eq!(err.problem, SyncProblem::RepoRollback, "{}", err.detail);
        assert_eq!(
            err.problem.title(crate::i18n::Lang::Nb),
            "Repoet sendte en eldre indeks"
        );
        assert_eq!(
            row_of(&app, id).await.latest_release_file_path.as_deref(),
            Some(stored.as_str())
        );
        assert_eq!(taken().await, Some(1_760_000_000_000), "never lowered");

        // entry.jar by another key now: refused, the release and the pin stay.
        let other = crate::jar_signature::tests::TestKey::new("Vibb mirror (throwaway)");
        let entry = fake.entry.lock().unwrap().clone();
        *fake.jar.lock().unwrap() = Some(crate::jar_signature::tests::signed_jar(
            entry.as_bytes(),
            &other,
            false,
        ));
        sqlx::query("UPDATE tracked_apps SET latest_release_asset_id = 56 WHERE id = ?")
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        let err = sync_one_app(&app.state, id, true).await.unwrap_err();
        assert_eq!(err.problem, SyncProblem::RepoKey, "{}", err.detail);
        assert_eq!(
            err.problem.title(crate::i18n::Lang::Nb),
            "Repoets signeringsnøkkel er endret"
        );
        assert_eq!(
            row_of(&app, id).await.latest_release_file_path,
            Some(stored)
        );
        assert_eq!(pinned_key().await, Some(FAKE_REPO_KEY.sha256()));

        // The repo's last app gone: its key too.
        sqlx::query("DELETE FROM tracked_apps WHERE id = ?")
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        remove_unused_fdroid_indexes(&app.state).await;
        assert_eq!(pinned_key().await, None);
        assert_eq!(taken().await, None);
        // L2: a sync still running after that can't put the pin or the timestamp back.
        assert_eq!(
            pin_repo_key(&app.state, &repo, &FAKE_REPO_KEY.sha256())
                .await
                .unwrap_err()
                .problem,
            SyncProblem::Removed
        );
        note_repo_timestamp(&app.state, &repo, 1).await.unwrap();
        assert_eq!((pinned_key().await, taken().await), (None, None));
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

    // Design 90: the wait and the hold, through the whole sync
    // --------------------------------------------------------------------------------------------

    /// Republishes the fake repo's index with version `sha` added now (`added`, ms).
    fn added_now(fake: &crate::fdroid::tests::FakeRepo, package: &str, sha: &str) {
        let bytes = fake.index.lock().unwrap().clone();
        let mut index: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        index["packages"][package]["versions"][sha]["added"] =
            serde_json::json!(chrono::Utc::now().timestamp_millis());
        fake.publish(&index.to_string());
    }

    async fn update_notices(app: &crate::tests::TestApp) -> Vec<(String, serde_json::Value)> {
        sqlx::query_as::<_, (String, String)>(
            "SELECT dedup_key, params FROM parent_events WHERE kind = 'app_update_available' \
             ORDER BY id",
        )
        .fetch_all(&app.db)
        .await
        .unwrap()
        .into_iter()
        .map(|(key, params)| (key, serde_json::from_str(&params).unwrap()))
        .collect()
    }

    /// Design 90 §6, §17.1 D2: a release on f-droid.org's path waits the app's 48 hours from the
    /// index's `added` - listed, told once, not downloaded (Check Now neither) -, and is served
    /// once the wait is over (here: set to 0), raising nothing more (§17.3 H1).
    #[tokio::test]
    async fn a_release_waits_the_apps_hours_then_is_served() {
        use crate::fdroid::tests::FakeRepo;
        let app = crate::tests::TestApp::new().await;
        let (repo, fake) = crate::fdroid::tests::fake_repo("127.0.0.1").await;
        let (apk, sha) = fixture_apk();
        serve_fixture(&fake, "me.vibb.testfixture", &sha, &apk);
        added_now(&fake, "me.vibb.testfixture", &sha);
        let id = fdroid_row(&app, &repo, "me.vibb.testfixture").await;
        sqlx::query(
            "UPDATE tracked_apps SET latest_release_tag = 'v1.0.56', latest_release_asset_id = 56, \
             release_wait_hours = 48 WHERE id = ?",
        )
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();

        sync_one_app(&app.state, id, false).await.unwrap();
        sync_one_app(&app.state, id, true).await.unwrap();
        let row = row_of(&app, id).await;
        assert_eq!(row.latest_release_tag.as_deref(), Some("v1.0.56"));
        assert_eq!(
            (
                row.waiting_release_tag.as_deref(),
                row.waiting_release_asset_id
            ),
            (Some("v1.0.57"), Some(57))
        );
        assert!(row.waiting_release_published_at.is_some());
        // From the later of the index's time and this server's first sight (QA M1).
        assert!(row.waiting_release_since >= row.waiting_release_published_at);
        assert_eq!(
            FakeRepo::count(&fake.file_gets),
            0,
            "a waiting release isn't downloaded"
        );
        let notices = update_notices(&app).await;
        assert_eq!(notices.len(), 1, "{notices:?}");
        assert_eq!(notices[0].0, format!("{id}:v1.0.57@57"));
        assert!(notices[0].1["ready"].is_string(), "{:?}", notices[0].1);

        sqlx::query("UPDATE tracked_apps SET release_wait_hours = 0 WHERE id = ?")
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        sync_one_app(&app.state, id, false).await.unwrap();
        let row = row_of(&app, id).await;
        assert_eq!(row.latest_release_tag.as_deref(), Some("v1.0.57"));
        assert_eq!(row.waiting_release_tag, None);
        assert_eq!(row.latest_release_permissions.as_deref(), Some("[]"));
        assert_eq!(
            update_notices(&app).await.len(),
            1,
            "served: the found row only"
        );
    }

    /// The user, 2026-11-20: a first release reaches the phones at once - also one an older server
    /// stored as waiting - and only the next release waits the app's hours.
    #[tokio::test]
    async fn a_first_release_is_served_at_once_and_the_second_waits() {
        let app = crate::tests::TestApp::new().await;
        let (repo, fake) = crate::fdroid::tests::fake_repo("127.0.0.1").await;
        let (apk, sha) = fixture_apk();
        serve_fixture(&fake, "me.vibb.testfixture", &sha, &apk);
        added_now(&fake, "me.vibb.testfixture", &sha);
        let id = fdroid_row(&app, &repo, "me.vibb.testfixture").await;
        // Added before this rule: the first release waits, nothing served yet.
        sqlx::query(
            "UPDATE tracked_apps SET release_wait_hours = 48, waiting_release_tag = 'v1.0.57', \
             waiting_release_asset_id = 57, waiting_release_since = '2026-11-20T10:00:00Z' \
             WHERE id = ?",
        )
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
        sync_one_app(&app.state, id, false).await.unwrap();
        let row = row_of(&app, id).await;
        assert_eq!(row.latest_release_tag.as_deref(), Some("v1.0.57"));
        assert_eq!(row.waiting_release_tag, None);
        // Now the app has a release: another one waits its hours.
        sqlx::query("UPDATE tracked_apps SET latest_release_tag = 'v1.0.56', latest_release_asset_id = 56 WHERE id = ?")
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        sqlx::query("DELETE FROM tracked_app_seen_releases WHERE tracked_app_id = ?")
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        sync_one_app(&app.state, id, false).await.unwrap();
        let row = row_of(&app, id).await;
        assert_eq!(row.latest_release_tag.as_deref(), Some("v1.0.56"));
        assert_eq!(row.waiting_release_tag.as_deref(), Some("v1.0.57"));
    }

    /// §17.3 H2 on another repo's path (IzzyOnDroid's): Update Now sends the waiting release at
    /// once; the hourly syncs after it keep it - no step back to the older ripe one, no waiting
    /// row, no failure; a release no longer listed is "gone".
    #[tokio::test]
    async fn update_now_sends_the_exact_release_and_the_sync_never_steps_back() {
        let app = crate::tests::TestApp::new().await;
        let (repo, fake) = crate::fdroid::tests::fake_repo("localhost").await;
        let (apk, sha) = fixture_apk();
        serve_fixture(&fake, "me.vibb.testfixture", &sha, &apk);
        added_now(&fake, "me.vibb.testfixture", &sha);
        let id = fdroid_row(&app, &repo, "me.vibb.testfixture").await;
        sqlx::query(
            "UPDATE tracked_apps SET latest_release_tag = 'v1.0.56', latest_release_asset_id = 56, \
             release_wait_hours = 48 WHERE id = ?",
        )
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
        sync_one_app(&app.state, id, false).await.unwrap();
        assert_eq!(
            row_of(&app, id).await.waiting_release_tag.as_deref(),
            Some("v1.0.57")
        );
        // 1.0.56 seen long ago: ripe, so the sweeps below would step back to it without H2's rule.
        sqlx::query(
            "UPDATE tracked_app_seen_releases SET first_seen = '2020-01-01T00:00:00Z' \
             WHERE tracked_app_id = ? AND asset_id = 56",
        )
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();

        let exact = ReleaseForm {
            tag: "v1.0.57".to_string(),
            asset_id: 57,
            back: None,
            from: None,
        };
        assert_eq!(
            update_now_sync(&app.state, id, &exact).await.unwrap(),
            Some("sent")
        );
        for _ in 0..3 {
            sync_one_app(&app.state, id, false).await.unwrap();
        }
        let row = row_of(&app, id).await;
        assert_eq!(row.latest_release_tag.as_deref(), Some("v1.0.57"));
        assert_eq!(row.waiting_release_tag, None);
        let gone = ReleaseForm {
            tag: "v1.0.99".to_string(),
            asset_id: 99,
            back: None,
            from: None,
        };
        assert_eq!(update_now_sync(&app.state, id, &gone).await.unwrap(), None);
        // QA L3: a listed release behind the served one isn't sent.
        let back = ReleaseForm {
            tag: "v1.0.56".to_string(),
            asset_id: 56,
            back: None,
            from: None,
        };
        assert_eq!(
            update_now_sync(&app.state, id, &back).await.unwrap(),
            Some("stale")
        );
        assert_eq!(
            row_of(&app, id).await.latest_release_tag.as_deref(),
            Some("v1.0.57")
        );
    }

    /// §17.3 H2 on GitHub's rule (`ahead` by the versions): after an Update Now the newest is
    /// served - the older ripe release isn't taken and nothing waits; with the served release gone
    /// from the list, the newest ripe one is.
    #[tokio::test]
    async fn the_github_pick_never_steps_back() {
        use crate::catalog_holds::{Candidate, Context, Pick, pick};
        let app = crate::tests::TestApp::new().await;
        let id: i64 = sqlx::query_scalar(
            "INSERT INTO tracked_apps (name, package_name, github_repo, latest_release_tag, \
             latest_release_asset_id) VALUES ('Signal', '', 'o/r', 'v7.3.0', 300) RETURNING id",
        )
        .fetch_one(&app.db)
        .await
        .unwrap();
        let row = row_of(&app, id).await;
        let now = chrono::Utc::now();
        let ago = |h| Some(now - chrono::Duration::hours(h));
        let candidates = [
            Candidate {
                tag: "v7.3.0".to_string(),
                asset_id: 300,
                published: ago(1),
                first_seen: ago(1),
            },
            Candidate {
                tag: "v7.2.0".to_string(),
                asset_id: 200,
                published: ago(100),
                first_seen: ago(100),
            },
        ];
        let context = |served| Context {
            wait_hours: Some(48),
            now,
            served,
            held: None,
            declined: None,
        };
        let picked = |served| {
            pick(&candidates, &context(served), |tag, asset, of| {
                ahead(&row, tag, asset, of)
            })
        };
        assert_eq!(
            picked(served_of(&row)),
            Pick {
                take: None,
                waiting: None
            }
        );
        assert_eq!(picked(Some(("v7.1.0", 100))).take, Some(1));
    }

    /// Design 90 §5, §7: the first release seeds the accepted set and is served; one that asks for
    /// something new is held (the phones' release unchanged, no nudge, one inbox row however often
    /// it is found); a newer clean release supersedes it (its file gone), a newer sensitive one
    /// replaces it; the launcher's row is never held.
    #[tokio::test]
    async fn a_release_asking_for_more_is_held_until_superseded() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        let app = crate::tests::TestApp::new().await;
        let v2_gets = std::sync::Arc::new(AtomicUsize::new(0));
        let counted = v2_gets.clone();
        let server = axum::Router::new()
            .route(
                "/v1.apk",
                axum::routing::get(|| async {
                    std::fs::read("testdata/apk/perms-key3-v1.apk").unwrap()
                }),
            )
            .route(
                "/v2.apk",
                axum::routing::get(move || {
                    counted.fetch_add(1, Ordering::SeqCst);
                    async { std::fs::read("testdata/apk/perms-key3-v2.apk").unwrap() }
                }),
            );
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        tokio::spawn(async move { axum::serve(listener, server).await.unwrap() });
        let asset = |id: i64, file: &str| ReleaseAsset {
            id,
            name: format!("{file}.apk"),
            browser_download_url: format!("{base}/{file}.apk"),
            size: std::fs::metadata(format!("testdata/apk/perms-key3-{file}.apk"))
                .unwrap()
                .len(),
            sha256: None,
            index_signers: None,
            updated_at: None,
        };
        let id: i64 = sqlx::query_scalar(
            "INSERT INTO tracked_apps (name, package_name, github_repo) \
             VALUES ('Fixture', '', 'o/r') RETURNING id",
        )
        .fetch_one(&app.db)
        .await
        .unwrap();
        let (device, _) = app.enrolled_device("Anna").await;
        sqlx::query("INSERT INTO device_tracked_apps (device_id, tracked_app_id) VALUES (?, ?)")
            .bind(device)
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        let mut nudges = app.state.command_notify.subscribe();
        let held_events = || async {
            update_notices(&app)
                .await
                .into_iter()
                .filter(|(key, _)| key.ends_with(":held"))
                .collect::<Vec<_>>()
        };

        sync_release(
            &app.state,
            &row_of(&app, id).await,
            "v1",
            &asset(1, "v1"),
            false,
        )
        .await
        .unwrap();
        let row = row_of(&app, id).await;
        assert_eq!(
            row.accepted_permissions.as_deref(),
            Some(r#"["android.permission.INTERNET"]"#),
            "the first release seeds the set"
        );
        assert_eq!(nudges.try_recv().unwrap(), device);

        for _ in 0..3 {
            sync_release(
                &app.state,
                &row_of(&app, id).await,
                "v2",
                &asset(2, "v2"),
                false,
            )
            .await
            .unwrap();
        }
        assert_eq!(
            v2_gets.load(Ordering::SeqCst),
            1,
            "a held release isn't downloaded again"
        );
        assert_eq!(
            row_of(&app, id).await.latest_release_tag.as_deref(),
            Some("v1")
        );
        assert!(nudges.try_recv().is_err(), "no phone is nudged for a hold");
        let held = held_release(&app.state, id).await.unwrap().unwrap();
        assert_eq!(
            (held.release_tag.as_str(), held.reason.as_str()),
            ("v2", "permissions")
        );
        assert_eq!(
            held.new_permissions,
            r#"["android.permission.ACCESS_COARSE_LOCATION","android.permission.ACCESS_FINE_LOCATION","android.permission.READ_CONTACTS","bind:android.permission.BIND_ACCESSIBILITY_SERVICE","bind:android.permission.BIND_DEVICE_ADMIN"]"#
        );
        let events = held_events().await;
        assert_eq!(events.len(), 1, "{events:?}");
        assert_eq!(events[0].0, format!("{id}:v2@2:held"));
        assert_eq!(
            events[0].1["held"]["groups"],
            serde_json::json!(["contacts", "location", "accessibility", "device_admin"])
        );
        assert!(std::path::Path::new(&held.file_path).exists());

        // A newer release asking for nothing new: served, the held one superseded.
        sync_release(
            &app.state,
            &row_of(&app, id).await,
            "v3",
            &asset(3, "v1"),
            false,
        )
        .await
        .unwrap();
        assert_eq!(
            row_of(&app, id).await.latest_release_tag.as_deref(),
            Some("v3")
        );
        assert!(held_release(&app.state, id).await.unwrap().is_none());
        assert!(!std::path::Path::new(&held.file_path).exists());

        // A newer sensitive one replaces the held one, with its own row.
        sync_release(
            &app.state,
            &row_of(&app, id).await,
            "v4",
            &asset(4, "v2"),
            false,
        )
        .await
        .unwrap();
        let v4 = held_release(&app.state, id).await.unwrap().unwrap();
        sync_release(
            &app.state,
            &row_of(&app, id).await,
            "v5",
            &asset(5, "v2"),
            false,
        )
        .await
        .unwrap();
        let v5 = held_release(&app.state, id).await.unwrap().unwrap();
        assert_eq!(v5.release_tag, "v5");
        assert!(!std::path::Path::new(&v4.file_path).exists());
        assert_eq!(held_events().await.len(), 3);

        // Ours is never held, and takes only the release key: this app's key is another, so its
        // release is refused, neither held nor served (`our_own_apps_take_only_the_release_key`).
        sqlx::query("UPDATE tracked_apps SET is_launcher = 1 WHERE id = ?")
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        let refused = sync_release(
            &app.state,
            &row_of(&app, id).await,
            "v6",
            &asset(6, "v2"),
            false,
        )
        .await
        .unwrap_err();
        assert_eq!(refused.problem, SyncProblem::OwnKey);
        assert_eq!(
            row_of(&app, id).await.latest_release_tag.as_deref(),
            Some("v3")
        );
        assert_eq!(
            held_release(&app.state, id)
                .await
                .unwrap()
                .unwrap()
                .release_tag,
            "v5"
        );
    }

    /// A release that joins a shared user ID: a first release is refused, a later one is held as
    /// unreadable (its own permission list isn't all it could use).
    #[tokio::test]
    async fn a_release_with_a_shared_user_id_waits_for_the_parent() {
        let app = crate::tests::TestApp::new().await;
        let plain = std::fs::read("testdata/apk/perms-key3-v1.apk").unwrap();
        let shared = crate::apk::tests::shared_user_apk(&plain, "me.vibb.permfixture");
        let files = [("plain", plain), ("shared", shared)];
        let mut server = axum::Router::new();
        for (name, bytes) in files.clone() {
            server = server.route(
                &format!("/{name}.apk"),
                axum::routing::get(move || async move { bytes }),
            );
        }
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        tokio::spawn(async move { axum::serve(listener, server).await.unwrap() });
        let asset = |id: i64, name: &str| ReleaseAsset {
            id,
            name: format!("{name}.apk"),
            browser_download_url: format!("{base}/{name}.apk"),
            size: files.iter().find(|(n, _)| *n == name).unwrap().1.len() as u64,
            sha256: None,
            index_signers: None,
            updated_at: None,
        };
        let id: i64 = sqlx::query_scalar(
            "INSERT INTO tracked_apps (name, package_name, github_repo) \
             VALUES ('Fixture', '', 'o/r') RETURNING id",
        )
        .fetch_one(&app.db)
        .await
        .unwrap();

        let refused = sync_release(
            &app.state,
            &row_of(&app, id).await,
            "v1",
            &asset(1, "shared"),
            false,
        )
        .await
        .unwrap_err();
        assert_eq!(refused.problem, SyncProblem::CantCheck);
        assert!(
            refused.detail.contains("sharedUserId"),
            "{}",
            refused.detail
        );
        let row = row_of(&app, id).await;
        assert_eq!(
            (row.latest_release_tag, row.package_name.as_str()),
            (None, "")
        );

        sync_release(
            &app.state,
            &row_of(&app, id).await,
            "v2",
            &asset(2, "plain"),
            false,
        )
        .await
        .unwrap();
        sync_release(
            &app.state,
            &row_of(&app, id).await,
            "v3",
            &asset(3, "shared"),
            false,
        )
        .await
        .unwrap();
        assert_eq!(
            row_of(&app, id).await.latest_release_tag.as_deref(),
            Some("v2")
        );
        let held = held_release(&app.state, id).await.unwrap().unwrap();
        assert_eq!(
            (held.release_tag.as_str(), held.reason.as_str()),
            ("v3", "unreadable")
        );
    }

    /// Design 116 §2.1: a sync's outcome reaches the integration health. An F-Droid repo that answers is a success;
    /// a release signed with another key is `stop` at once, and the hourly skip of that remembered refusal records
    /// nothing. A GitHub app's broken stored filter is `parent`, `Fix` at the second.
    #[tokio::test]
    async fn a_sync_records_its_health() {
        use crate::integration_health::{Class, Problem, grace, raised, row};
        let app = crate::tests::TestApp::new().await;
        let (repo, fake) = crate::fdroid::tests::fake_repo("127.0.0.1").await;
        let (apk, sha) = fixture_apk();
        serve_fixture(&fake, "me.vibb.testfixture", &sha, &apk);
        let id = fdroid_row(&app, &repo, "me.vibb.testfixture").await;
        let scope = format!("app:{id}");
        sync_one_app(&app.state, id, false).await.unwrap();
        let health = row(&app.db, "fdroid", &scope).await.unwrap().unwrap();
        assert!(health.ok_at.is_some());
        assert_eq!((health.failing_since, health.failures), (None, 0));

        // Another pinned key, the release not cached: refused, then skipped by the hourly sync.
        sqlx::query(
            "UPDATE tracked_apps SET signer_sha256 = ?, latest_release_tag = NULL, \
             latest_release_asset_id = NULL WHERE id = ?",
        )
        .bind("ab".repeat(32))
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
        let err = sync_one_app(&app.state, id, false).await.unwrap_err();
        assert_eq!(err.problem, SyncProblem::OtherKey);
        let skipped = sync_one_app(&app.state, id, false).await.unwrap_err();
        assert!(skipped.skipped);
        let health = row(&app.db, "fdroid", &scope).await.unwrap().unwrap();
        assert_eq!((health.class, health.failures), (Some(Class::Stop), 1));
        assert_eq!(
            raised(&health, grace("fdroid"), chrono::Utc::now()),
            Some(Problem::Fix)
        );

        // GitHub: an invalid stored filter fails before any request.
        let github: i64 = sqlx::query_scalar(
            "INSERT INTO tracked_apps (name, package_name, github_repo, asset_pattern) \
             VALUES ('Fixture', '', 'o/r', '^(') RETURNING id",
        )
        .fetch_one(&app.db)
        .await
        .unwrap();
        for _ in 0..2 {
            let err = sync_one_app(&app.state, github, false).await.unwrap_err();
            assert_eq!(err.problem, SyncProblem::Filter);
        }
        let health = row(&app.db, "github", &format!("app:{github}"))
            .await
            .unwrap()
            .unwrap();
        assert_eq!((health.class, health.failures), (Some(Class::Parent), 2));
        assert_eq!(
            raised(&health, grace("github"), chrono::Utc::now()),
            Some(Problem::Fix)
        );
    }

    /// Design 116 §2.1: every sync problem's class.
    #[test]
    fn every_sync_problem_is_classed() {
        use crate::integration_health::Class::*;
        use SyncProblem::*;
        let rows = [
            (GithubUnreachable, false, Some(Transient)),
            (GithubError, false, Some(Transient)),
            (Download, false, Some(Transient)),
            (RepoUnreachable, false, Some(Transient)),
            (RepoNotFound, false, Some(Parent)),
            (NoRelease, false, Some(Parent)),
            (Filter, false, Some(Parent)),
            (FdroidUnknown, false, Some(Parent)),
            (NotInRepo, false, Some(Parent)),
            (RepoError, false, Some(Parent)),
            (OtherApp, true, Some(Stop)),
            (OtherKey, true, Some(Stop)),
            (Taken, true, Some(Stop)),
            (TooBig, false, Some(Stop)),
            (Unreadable, true, Some(Stop)),
            (CantCheck, true, Some(Stop)),
            (IndexSigner, true, Some(Stop)),
            (RepoKey, false, Some(Stop)),
            (RepoSignature, false, Some(Stop)),
            (RepoRollback, false, Some(Stop)),
            (ChecksumMismatch, true, Some(Stop)),
            (ChecksumMismatch, false, Some(Transient)),
            (Server, false, None),
            (Removed, false, None),
            (OlderVersion, false, None),
        ];
        for (problem, refused, class) in rows {
            assert_eq!(class_of(problem, refused), class, "{problem:?}");
        }
    }

    /// Design 140: a loopback server of APKs made from `testdata/apk/<base>.apk` with
    /// `assets/vibb/min_server` holding each text (`None`: the base as it is), and the assets that
    /// name them.
    async fn min_server_files(
        files: &[(&str, &str, Option<&str>)],
    ) -> HashMap<String, ReleaseAsset> {
        let mut routes = axum::Router::new();
        let mut made = Vec::new();
        for (name, base, text) in files {
            let base = std::fs::read(format!("testdata/apk/{base}.apk")).unwrap();
            let bytes = crate::apk::tests::with_min_server(&base, text.map(str::as_bytes));
            made.push((name.to_string(), bytes.len() as u64));
            routes = routes.route(
                &format!("/{name}.apk"),
                axum::routing::get(move || {
                    let bytes = bytes.clone();
                    async move { bytes }
                }),
            );
        }
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        tokio::spawn(async move { axum::serve(listener, routes).await.unwrap() });
        made.into_iter()
            .enumerate()
            .map(|(i, (name, size))| {
                let asset = ReleaseAsset {
                    id: 100 + i as i64,
                    name: format!("{name}.apk"),
                    browser_download_url: format!("{base}/{name}.apk"),
                    size,
                    sha256: None,
                    index_signers: None,
                    updated_at: None,
                };
                (name, asset)
            })
            .collect()
    }

    /// This server's version as text, and the next patch after it (a release that needs more).
    fn own_and_next(state: &AppState) -> (String, String) {
        let (x, y, z) = state.server_version;
        (format!("{x}.{y}.{z}"), format!("{x}.{y}.{}", z + 1))
    }

    /// Design 140 §4.2, §9, the launcher's row and the music app's: a release that needs a newer
    /// server is held - a first release too, nothing served - with no nudge, no inbox row (no
    /// push), not listed to the phones; one that needs this server is served and supersedes it; a
    /// newer held one replaces the held one; an unreadable value is refused, nothing stored or
    /// held, and not downloaded again every hour.
    #[tokio::test]
    async fn a_release_of_ours_that_needs_a_newer_server_is_held() {
        let mut config = crate::config::ForkConfig::for_tests();
        config.launcher_signature_checksum = Some(KEY4_CHECKSUM.to_string());
        let app = crate::tests::TestApp::with_config(config).await;
        let (own, next) = own_and_next(&app.state);
        let further = {
            let (x, y, z) = app.state.server_version;
            format!("{x}.{y}.{}", z + 2)
        };
        let files = min_server_files(&[
            ("l-next", "launcher-key4", Some(&format!("{next}\n"))),
            ("l-own", "launcher-key4", Some(&own)),
            ("l-further", "launcher-key4", Some(&further)),
            ("l-bad", "launcher-key4", Some("00.1.0\n")),
            ("m-next", "music-key4", Some(&next)),
            ("m-own", "music-key4", Some(&own)),
        ])
        .await;
        let (phone, _) = app.enrolled_device("Anna").await;
        let launcher: i64 = sqlx::query_scalar(
            "INSERT INTO tracked_apps (name, package_name, github_repo, is_launcher, \
             release_tag_prefix) VALUES ('Vibb One', 'me.vibb.launcher', 'o/r', 1, 'launcher-v') \
             RETURNING id",
        )
        .fetch_one(&app.db)
        .await
        .unwrap();
        let events = || async {
            sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM parent_events")
                .fetch_one(&app.db)
                .await
                .unwrap()
        };
        let mut nudges = app.state.command_notify.subscribe();
        let sync = |id: i64, tag: &'static str, name: &'static str| {
            let app = &app;
            let asset = &files[name];
            async move { sync_release(&app.state, &row_of(app, id).await, tag, asset, false).await }
        };

        // A first release that needs the next server: held, nothing served.
        sync(launcher, "launcher-v1.0.0", "l-next").await.unwrap();
        let held = held_release(&app.state, launcher).await.unwrap().unwrap();
        assert_eq!(held.reason, "server");
        assert_eq!(held.min_server.as_deref(), Some(next.as_str()));
        assert_eq!(row_of(&app, launcher).await.latest_release_tag, None);
        assert!(nudges.try_recv().is_err(), "no nudge");
        assert_eq!(events().await, 0, "no inbox row, no push");
        // The same release again: nothing to do, never downloaded again.
        sync(launcher, "launcher-v1.0.0", "l-next").await.unwrap();

        // One that needs this server: served, and it supersedes the held one behind it.
        sync(launcher, "launcher-v1.0.1", "l-own").await.unwrap();
        assert_eq!(
            row_of(&app, launcher).await.latest_release_tag.as_deref(),
            Some("launcher-v1.0.1")
        );
        assert!(held_release(&app.state, launcher).await.unwrap().is_none());
        assert_eq!(nudges.try_recv().ok(), Some(phone));

        // A later one that needs more: held, the phones stay on 1.0.1 and aren't nudged.
        sync(launcher, "launcher-v1.0.2", "l-next").await.unwrap();
        let held = held_release(&app.state, launcher).await.unwrap().unwrap();
        assert_eq!(held.release_tag, "launcher-v1.0.2");
        assert!(nudges.try_recv().is_err());
        assert_eq!(
            row_of(&app, launcher).await.latest_release_tag.as_deref(),
            Some("launcher-v1.0.1")
        );
        assert_eq!(events().await, 0);
        // A newer one held replaces it, its file going.
        sync(launcher, "launcher-v1.0.3", "l-further")
            .await
            .unwrap();
        let newer = held_release(&app.state, launcher).await.unwrap().unwrap();
        assert_eq!(newer.release_tag, "launcher-v1.0.3");
        assert_eq!(newer.min_server.as_deref(), Some(further.as_str()));
        assert!(!std::path::Path::new(&held.file_path).exists());
        // An unreadable value: refused as unreadable, nothing stored or held, kept refused.
        let refused = sync(launcher, "launcher-v1.0.4", "l-bad")
            .await
            .unwrap_err();
        assert_eq!(refused.problem, SyncProblem::Unreadable, "{refused}");
        assert_eq!(
            held_release(&app.state, launcher)
                .await
                .unwrap()
                .unwrap()
                .release_tag,
            "launcher-v1.0.3"
        );
        let again = sync(launcher, "launcher-v1.0.4", "l-bad")
            .await
            .unwrap_err();
        assert!(again.detail.contains("not downloaded again"), "{again}");
        assert_eq!(events().await, 0);

        // The music app's row: the same.
        let music: i64 = sqlx::query_scalar(
            "INSERT INTO tracked_apps (name, package_name, github_repo, release_tag_prefix) \
             VALUES ('Vibb music', 'me.vibb.music', ?, 'music-v') RETURNING id",
        )
        .bind(&app.state.config.server_release_repo)
        .fetch_one(&app.db)
        .await
        .unwrap();
        assert!(crate::catalog_holds::ours(
            &row_of(&app, music).await,
            &our_repos(&app.state)
        ));
        sync(music, "music-v1.0.0", "m-own").await.unwrap();
        sync(music, "music-v1.0.1", "m-next").await.unwrap();
        assert_eq!(
            row_of(&app, music).await.latest_release_tag.as_deref(),
            Some("music-v1.0.0")
        );
        let held = held_release(&app.state, music).await.unwrap().unwrap();
        assert_eq!(
            (held.reason.as_str(), held.min_server.as_deref()),
            ("server", Some(next.as_str()))
        );
        assert_eq!(events().await, 0);
    }

    /// Design 140 §4.2: at start, a release held for the server goes out once this server is new
    /// enough - served, nudged, the Security Log's line, no inbox row; one whose file names more
    /// than its row stays held; one no longer ahead of the served release is dropped.
    #[tokio::test]
    async fn a_release_held_for_the_server_goes_out_at_the_start_of_a_newer_one() {
        let mut config = crate::config::ForkConfig::for_tests();
        config.launcher_signature_checksum = Some(KEY4_CHECKSUM.to_string());
        let app = crate::tests::TestApp::with_config(config).await;
        let (own, next) = own_and_next(&app.state);
        let files = min_server_files(&[
            ("l-own", "launcher-key4", Some(&own)),
            ("l-next", "launcher-key4", Some(&next)),
        ])
        .await;
        let (phone, _) = app.enrolled_device("Anna").await;
        let launcher: i64 = sqlx::query_scalar(
            "INSERT INTO tracked_apps (name, package_name, github_repo, is_launcher, \
             release_tag_prefix) VALUES ('Vibb One', 'me.vibb.launcher', 'o/r', 1, 'launcher-v') \
             RETURNING id",
        )
        .fetch_one(&app.db)
        .await
        .unwrap();
        let sync = |tag: &'static str, name: &'static str| {
            let app = &app;
            let asset = &files[name];
            async move {
                sync_release(&app.state, &row_of(app, launcher).await, tag, asset, false)
                    .await
                    .unwrap()
            }
        };
        sync("launcher-v1.0.0", "l-own").await;
        let served_file = row_of(&app, launcher)
            .await
            .latest_release_file_path
            .unwrap();
        sync("launcher-v1.0.1", "l-next").await;
        let held = held_release(&app.state, launcher).await.unwrap().unwrap();

        // The same server again: it stays held.
        release_server_holds(&app.state).await;
        assert!(held_release(&app.state, launcher).await.unwrap().is_some());

        // The row says more than the file (or the other way round): it stays held.
        let mut newer = app.state.clone();
        newer.server_version = {
            let (x, y, z) = app.state.server_version;
            (x, y, z + 1)
        };
        sqlx::query("UPDATE tracked_app_held_releases SET min_server = ? WHERE tracked_app_id = ?")
            .bind(&own)
            .bind(launcher)
            .execute(&app.db)
            .await
            .unwrap();
        release_server_holds(&app.state).await;
        assert!(
            held_release(&app.state, launcher).await.unwrap().is_some(),
            "the file still names the next server"
        );
        sqlx::query("UPDATE tracked_app_held_releases SET min_server = ? WHERE tracked_app_id = ?")
            .bind(&next)
            .bind(launcher)
            .execute(&app.db)
            .await
            .unwrap();

        // A server that is new enough: served, the old file gone, the phones nudged, logged.
        let mut nudges = app.state.command_notify.subscribe();
        release_server_holds(&newer).await;
        let row = row_of(&app, launcher).await;
        assert_eq!(row.latest_release_tag.as_deref(), Some("launcher-v1.0.1"));
        assert_eq!(
            row.latest_release_file_path.as_deref(),
            Some(held.file_path.as_str())
        );
        assert!(held_release(&app.state, launcher).await.unwrap().is_none());
        assert!(!std::path::Path::new(&served_file).exists());
        assert_eq!(nudges.try_recv().ok(), Some(phone));
        let logged: Vec<Option<String>> = sqlx::query_scalar(
            "SELECT params FROM security_events WHERE event_type = 'catalog_release_server_ready'",
        )
        .fetch_all(&app.db)
        .await
        .unwrap();
        assert_eq!(logged.len(), 1);
        let events: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM parent_events")
            .fetch_one(&app.db)
            .await
            .unwrap();
        assert_eq!(events, 0, "no inbox row");

        // One no longer ahead of the served release (an upload overtook it): dropped, nothing
        // served.
        sync("launcher-v1.0.2", "l-next").await;
        let behind = held_release(&app.state, launcher).await.unwrap().unwrap();
        sqlx::query(
            "UPDATE tracked_apps SET latest_release_tag = 'launcher-v1.0.3', \
             latest_release_asset_id = 999 WHERE id = ?",
        )
        .bind(launcher)
        .execute(&app.db)
        .await
        .unwrap();
        release_server_holds(&newer).await;
        assert!(held_release(&app.state, launcher).await.unwrap().is_none());
        assert!(!std::path::Path::new(&behind.file_path).exists());
        assert_eq!(
            row_of(&app, launcher).await.latest_release_tag.as_deref(),
            Some("launcher-v1.0.3")
        );
    }
}
