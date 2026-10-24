//! An F-Droid repo as a release source for catalog apps (PLAN "An F-Droid repo as a release
//! source; Etar first"; docs/notes/server/play-and-updates.md, "F-Droid source"). A catalog app
//! of source `fdroid` is a repo address and a package id there; its sync is
//! `handlers::tracked_apps::sync_fdroid`, which stores the release as a GitHub one is stored.
//!
//! Two paths to the newest version, by the repo:
//! - **f-droid.org** ([API_HOSTS]): its per-package API, `https://f-droid.org/api/v1/packages/<id>`
//!   (`suggestedVersionCode`, else the highest listed `versionCode`). A 404 is a package F-Droid
//!   doesn't know; a version that is cached already ends the check without the index.
//! - **any other repo** (IzzyOnDroid's `https://apt.izzysoft.de/fdroid/repo`, a self-hosted one):
//!   the per-package API may not exist there, so the repo's index alone - its highest
//!   `versionCode` outside a release channel (a beta).
//!
//! Either way the APK's SHA-256 and size come from the repo's index: `<repo>/entry.json` names the
//! index (`index-v2.json`) with its SHA-256 and size; the index is downloaded only when that
//! SHA-256 changed ([index_file]: at most [MAX_INDEX_BYTES], checked against entry.json before it
//! is used, cached under `data/tracked_apps/fdroid/` keyed by the repo and that SHA-256; the
//! entry's daily diffs are never applied - a full fetch when it changes is fine at one repo a day).
//! The APK, `<repo>/<id>_<versionCode>.apk`, is checked against the index's SHA-256 before it is
//! stored (`tracked_apps::stream_to_file`): never an unverified file.
//!
//! Trust: the index and the APK come over HTTPS from the repo (a redirect may not leave https),
//! and the APK's hash must be the index's. The repo's own signature (`entry.jar`, a JAR signature
//! over entry.json) is **not** verified in this round (PLAN, the follow-up): a JAR signature
//! verifier in Rust is its own piece of work.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::time::Duration;

use serde::Deserialize;
use serde::de::{DeserializeSeed, Deserializer, IgnoredAny, MapAccess, Visitor};
use sha2::{Digest, Sha256};
use tokio::io::AsyncWriteExt;

use crate::handlers::tracked_apps::{Failure, SyncProblem, TempDownload};

/// The repo the add form starts with: F-Droid's own.
pub const DEFAULT_REPO: &str = "https://f-droid.org/repo";
/// The cached indexes' directory, under `AppState.tracked_apps_dir` (never a number, so never an
/// app's directory; `remove_partial_downloads` cleans its `.part` files too).
pub const CACHE_DIR: &str = "fdroid";
/// The largest index a sync downloads (f-droid.org's was 67 MB in October 2026).
pub const MAX_INDEX_BYTES: u64 = 100_000_000;
/// The largest entry.json or API answer read.
const MAX_SMALL_BYTES: usize = 1 << 20;
/// The longest repo address the forms accept.
const MAX_REPO_CHARS: usize = 200;
/// The longest versionName taken into a release tag.
const MAX_VERSION_NAME_CHARS: usize = 64;
const REQUEST_TIMEOUT: Duration = Duration::from_secs(15);
const STALL_TIMEOUT: Duration = Duration::from_secs(60);
const INDEX_TOTAL_TIMEOUT: Duration = Duration::from_secs(10 * 60);

/// The hosts whose per-package API is used; every other repo takes the index path.
#[cfg(not(test))]
const API_HOSTS: &[&str] = &["f-droid.org"];
/// In the tests the fake repo on 127.0.0.1 has the API, the one on `localhost` hasn't.
#[cfg(test)]
const API_HOSTS: &[&str] = &["f-droid.org", "127.0.0.1"];

/// A repo address as typed: blank = [DEFAULT_REPO]; else an `https://` URL without a user,
/// query or fragment, stored without its trailing slash (`https://f-droid.org/repo/` ->
/// `https://f-droid.org/repo`). `Err` is the form's sentence.
pub fn normalize_repo(input: &str) -> Result<String, String> {
    let text = input.trim();
    if text.is_empty() {
        return Ok(DEFAULT_REPO.to_string());
    }
    let refused = || {
        format!(
            "The repo must be an https:// address (at most {MAX_REPO_CHARS} characters), e.g. \
             {DEFAULT_REPO}. Nothing was saved."
        )
    };
    if text.chars().count() > MAX_REPO_CHARS {
        return Err(refused());
    }
    let url = reqwest::Url::parse(text).map_err(|_| refused())?;
    let plain = url.scheme() == "https"
        && url.host_str().is_some_and(|host| !host.is_empty())
        && url.username().is_empty()
        && url.password().is_none()
        && url.query().is_none()
        && url.fragment().is_none();
    if !plain {
        return Err(refused());
    }
    Ok(url.as_str().trim_end_matches('/').to_string())
}

/// A package id as typed: an Android package name (`ws.xsoh.etar`). `Err` is the form's sentence.
pub fn parse_package(input: &str) -> Result<String, String> {
    let package = input.trim();
    if crate::app_display::valid_package_name(package) {
        Ok(package.to_string())
    } else {
        Err(
            "The package must be an Android package name, e.g. ws.xsoh.etar (on F-Droid it is \
             the last part of the app's address). Nothing was saved."
                .to_string(),
        )
    }
}

/// A stored repo, ready to sync.
#[derive(Debug, PartialEq, Eq)]
pub struct Repo {
    /// `https://f-droid.org/repo`, no trailing slash.
    pub url: String,
    /// The per-package API's base (`https://f-droid.org/api/v1/packages`); `None` = the index path.
    pub api: Option<String>,
}

impl Repo {
    /// A stored repo address (the forms keep it https; loopback http too in the tests), `None`
    /// for anything else.
    pub fn from_stored(stored: &str) -> Option<Repo> {
        let url = reqwest::Url::parse(stored).ok()?;
        let host = url.host_str()?.to_string();
        let loopback_http = cfg!(test)
            && url.scheme() == "http"
            && matches!(host.as_str(), "127.0.0.1" | "localhost");
        if url.scheme() != "https" && !loopback_http {
            return None;
        }
        let port = url.port().map(|p| format!(":{p}")).unwrap_or_default();
        Some(Repo {
            url: stored.trim_end_matches('/').to_string(),
            api: API_HOSTS
                .contains(&host.as_str())
                .then(|| format!("{}://{host}{port}/api/v1/packages", url.scheme())),
        })
    }
}

/// What the app's page calls the repo: "F-Droid" for f-droid.org, else its host
/// ("apt.izzysoft.de").
pub fn repo_label(stored: &str) -> String {
    match reqwest::Url::parse(stored)
        .ok()
        .and_then(|url| url.host_str().map(str::to_string))
    {
        Some(host) if host == "f-droid.org" => "F-Droid".to_string(),
        Some(host) => host,
        None => stored.to_string(),
    }
}

/// The package's page on f-droid.org (`https://f-droid.org/packages/ws.xsoh.etar/`); `None` for
/// another repo.
pub fn package_page(stored: &str, package: &str) -> Option<String> {
    (repo_label(stored) == "F-Droid" && crate::app_display::valid_package_name(package))
        .then(|| format!("https://f-droid.org/packages/{package}/"))
}

/// The release tag of a version: `v<versionName>` ("v1.0.57", shown as "1.0.57" like a GitHub
/// tag); `v<versionCode>` when the name is blank, too long or has control characters.
pub fn tag_of(version_name: &str, version_code: i64) -> String {
    let name = version_name.trim();
    let usable = !name.is_empty()
        && name.chars().count() <= MAX_VERSION_NAME_CHARS
        && !name.chars().any(char::is_control);
    if usable {
        format!("v{name}")
    } else {
        format!("v{version_code}")
    }
}

/// The client for the repo's API, entry.json and index.
pub fn client() -> Result<reqwest::Client, Failure> {
    reqwest::Client::builder()
        .user_agent("kid-phone-server (self-hosted, F-Droid repo)")
        .connect_timeout(REQUEST_TIMEOUT)
        .redirect(crate::handlers::tracked_apps::https_redirects())
        .build()
        .map_err(|e| Failure::new(SyncProblem::Server, e.to_string()))
}

/// GETs a small document (entry.json, an API answer): its status and, on a success, at most
/// [MAX_SMALL_BYTES] of body.
async fn get_small(
    client: &reqwest::Client,
    url: &str,
) -> Result<(reqwest::StatusCode, Vec<u8>), Failure> {
    let unreachable = |e: String| Failure::new(SyncProblem::RepoUnreachable, format!("{url}: {e}"));
    let mut response = client
        .get(url)
        .timeout(REQUEST_TIMEOUT)
        .send()
        .await
        .map_err(|e| unreachable(e.to_string()))?;
    let status = response.status();
    if !status.is_success() {
        return Ok((status, Vec::new()));
    }
    let mut body = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|e| unreachable(e.to_string()))?
    {
        body.extend_from_slice(&chunk);
        if body.len() > MAX_SMALL_BYTES {
            return Err(Failure::new(
                SyncProblem::RepoError,
                format!("{url} is over {} KB", MAX_SMALL_BYTES / 1024),
            ));
        }
    }
    Ok((status, body))
}

/// The per-package API's answer (only what is used).
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ApiPackage {
    #[serde(default)]
    suggested_version_code: Option<i64>,
    #[serde(default)]
    packages: Vec<ApiVersion>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApiVersion {
    #[serde(default)]
    version_name: String,
    version_code: i64,
}

/// The API's version: `suggestedVersionCode` when it has one (with its listed name), else the
/// highest listed `versionCode`.
pub(crate) fn pick_api(answer: &ApiPackage, package: &str) -> Result<(i64, String), Failure> {
    let name_of = |code: i64| {
        answer
            .packages
            .iter()
            .find(|v| v.version_code == code)
            .map(|v| v.version_name.clone())
    };
    let code = match answer.suggested_version_code {
        Some(code) => code,
        None => answer
            .packages
            .iter()
            .map(|v| v.version_code)
            .max()
            .ok_or_else(|| {
                Failure::new(
                    SyncProblem::NoRelease,
                    format!("F-Droid lists no version of {package}"),
                )
            })?,
    };
    Ok((code, name_of(code).unwrap_or_default()))
}

/// The newest version of `package` by the repo's per-package API (`<api>/<package>`): a 404 is a
/// package the repo doesn't know.
pub async fn api_version(
    client: &reqwest::Client,
    api: &str,
    package: &str,
) -> Result<(i64, String), Failure> {
    let url = format!("{api}/{package}");
    let (status, body) = get_small(client, &url).await?;
    if status == reqwest::StatusCode::NOT_FOUND {
        return Err(Failure::new(
            SyncProblem::FdroidUnknown,
            format!("{url} answered 404: the repo has no package {package}"),
        ));
    }
    if !status.is_success() {
        return Err(Failure::new(
            SyncProblem::RepoUnreachable,
            format!("{url} answered {status}"),
        ));
    }
    let answer: ApiPackage = serde_json::from_slice(&body).map_err(|e| {
        Failure::new(
            SyncProblem::RepoError,
            format!("{url} isn't F-Droid's package answer: {e}"),
        )
    })?;
    pick_api(&answer, package)
}

/// entry.json's index: the file, its SHA-256 (lower-case hex) and size.
#[derive(Debug, PartialEq, Eq)]
pub(crate) struct EntryIndex {
    /// "index-v2.json", without the leading slash entry.json has.
    pub name: String,
    pub sha256: String,
    pub size: u64,
}

#[derive(Deserialize)]
struct Entry {
    index: EntryFile,
}

#[derive(Deserialize)]
struct EntryFile {
    name: String,
    sha256: String,
    size: u64,
}

/// A plain file name (`index-v2.json`, `ws.xsoh.etar_57.apk`) from a repo, its leading slash
/// dropped; `None` for a path, `..` or anything else.
fn plain_file_name(name: &str, extension: &str) -> Option<String> {
    let name = name.strip_prefix('/').unwrap_or(name);
    let plain = !name.is_empty()
        && name.len() <= 255
        && !name.starts_with('.')
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'))
        && name.to_ascii_lowercase().ends_with(extension);
    plain.then(|| name.to_string())
}

/// Lower-case hex of a 64-digit SHA-256, `None` for anything else.
fn sha256_hex(text: &str) -> Option<String> {
    (text.len() == 64 && text.chars().all(|c| c.is_ascii_hexdigit()))
        .then(|| text.to_ascii_lowercase())
}

pub(crate) fn parse_entry(body: &[u8]) -> Result<EntryIndex, Failure> {
    let bad = |why: String| Failure::new(SyncProblem::RepoError, why);
    let entry: Entry = serde_json::from_slice(body)
        .map_err(|e| bad(format!("entry.json isn't an F-Droid index entry (v2): {e}")))?;
    let name = plain_file_name(&entry.index.name, ".json")
        .ok_or_else(|| bad(format!("entry.json names the index {:?}", entry.index.name)))?;
    let sha256 = sha256_hex(&entry.index.sha256)
        .ok_or_else(|| bad("entry.json's index SHA-256 isn't one".to_string()))?;
    if entry.index.size > MAX_INDEX_BYTES {
        return Err(bad(format!(
            "the repo's index is {} MB, over the {} MB limit - not downloaded",
            entry.index.size / 1_000_000,
            MAX_INDEX_BYTES / 1_000_000
        )));
    }
    Ok(EntryIndex {
        name,
        sha256,
        size: entry.index.size,
    })
}

/// The cache file of `repo`'s index with this SHA-256: `<dir>/<key>-<sha256>.json`, the key the
/// first 16 hex digits of the repo address's SHA-256. Named by the hash, so two syncs at once
/// can't pair one index with another's hash.
fn cache_path(dir: &Path, repo_url: &str, sha256: &str) -> PathBuf {
    dir.join(format!("{}-{sha256}.json", cache_key(repo_url)))
}

fn cache_key(repo_url: &str) -> String {
    hex::encode(Sha256::digest(repo_url.as_bytes()))[..16].to_string()
}

/// The repo's current index on disk: entry.json is fetched every time; the index only when no
/// cached copy has entry.json's SHA-256 (then downloaded, checked, and the repo's older copies
/// removed).
pub async fn index_file(
    client: &reqwest::Client,
    repo: &Repo,
    cache_dir: &Path,
) -> Result<PathBuf, Failure> {
    let entry_url = format!("{}/entry.json", repo.url);
    let (status, body) = get_small(client, &entry_url).await?;
    if status == reqwest::StatusCode::NOT_FOUND {
        return Err(Failure::new(
            SyncProblem::RepoError,
            format!("{entry_url} answered 404: not an F-Droid repo (index v2) at this address"),
        ));
    }
    if !status.is_success() {
        return Err(Failure::new(
            SyncProblem::RepoUnreachable,
            format!("{entry_url} answered {status}"),
        ));
    }
    let entry = parse_entry(&body)?;
    let path = cache_path(cache_dir, &repo.url, &entry.sha256);
    if tokio::fs::metadata(&path)
        .await
        .is_ok_and(|meta| meta.is_file() && meta.len() == entry.size)
    {
        return Ok(path);
    }
    tokio::fs::create_dir_all(cache_dir)
        .await
        .map_err(|e| Failure::new(SyncProblem::Server, e.to_string()))?;
    let index_url = format!("{}/{}", repo.url, entry.name);
    tokio::time::timeout(
        INDEX_TOTAL_TIMEOUT,
        download_index(client, &index_url, &path, &entry),
    )
    .await
    .map_err(|_| {
        Failure::new(
            SyncProblem::RepoUnreachable,
            format!(
                "the index download stopped after {} minutes",
                INDEX_TOTAL_TIMEOUT.as_secs() / 60
            ),
        )
    })??;
    remove_older_copies(cache_dir, &repo.url, &path).await;
    Ok(path)
}

/// Streams the index to `path` through a temp file, renamed into place only when it has exactly
/// entry.json's size and SHA-256.
async fn download_index(
    client: &reqwest::Client,
    url: &str,
    path: &Path,
    entry: &EntryIndex,
) -> Result<(), Failure> {
    use SyncProblem::{ChecksumMismatch, RepoError, RepoUnreachable, Server};
    let unreachable = |e: String| Failure::new(RepoUnreachable, format!("{url}: {e}"));
    let mut response = tokio::time::timeout(STALL_TIMEOUT, client.get(url).send())
        .await
        .map_err(|_| unreachable(format!("no answer for {} s", STALL_TIMEOUT.as_secs())))?
        .map_err(|e| unreachable(e.to_string()))?
        .error_for_status()
        .map_err(|e| unreachable(e.to_string()))?;
    if response
        .content_length()
        .is_some_and(|len| len > entry.size)
    {
        return Err(Failure::new(
            RepoError,
            format!(
                "{url} is longer than the {} bytes entry.json lists",
                entry.size
            ),
        ));
    }
    let temp = TempDownload::new(format!(
        "{}.{}.part",
        path.display(),
        crate::handlers::tracked_apps::random_label()
    ));
    let mut file = tokio::fs::File::create(&temp.path)
        .await
        .map_err(|e| Failure::new(Server, format!("can't create {}: {e}", temp.path)))?;
    let mut digest = Sha256::new();
    let mut written: u64 = 0;
    loop {
        let chunk = tokio::time::timeout(STALL_TIMEOUT, response.chunk())
            .await
            .map_err(|_| {
                unreachable(format!(
                    "stalled: no data for {} s",
                    STALL_TIMEOUT.as_secs()
                ))
            })?
            .map_err(|e| unreachable(e.to_string()))?;
        let Some(chunk) = chunk else { break };
        written += chunk.len() as u64;
        if written > entry.size {
            return Err(Failure::new(
                RepoError,
                format!(
                    "{url} is longer than the {} bytes entry.json lists",
                    entry.size
                ),
            ));
        }
        digest.update(&chunk);
        file.write_all(&chunk)
            .await
            .map_err(|e| Failure::new(Server, format!("can't write {}: {e}", temp.path)))?;
    }
    if written != entry.size {
        return Err(Failure::new(
            RepoError,
            format!("{url} has {written} bytes, entry.json lists {}", entry.size),
        ));
    }
    let sha256 = hex::encode(digest.finalize());
    if sha256 != entry.sha256 {
        return Err(Failure::new(
            ChecksumMismatch,
            format!(
                "the index's SHA-256 is {sha256}, entry.json says {} - not used",
                entry.sha256
            ),
        ));
    }
    let server = |e: std::io::Error| Failure::new(Server, e.to_string());
    file.flush().await.map_err(server)?;
    file.sync_all().await.map_err(server)?;
    drop(file);
    tokio::fs::rename(&temp.path, path)
        .await
        .map_err(|e| Failure::new(Server, format!("can't move the index into place: {e}")))?;
    temp.keep();
    Ok(())
}

/// Removes the repo's cached indexes other than `current`. Best-effort.
async fn remove_older_copies(dir: &Path, repo_url: &str, current: &Path) {
    let prefix = format!("{}-", cache_key(repo_url));
    let Ok(mut files) = tokio::fs::read_dir(dir).await else {
        return;
    };
    while let Ok(Some(file)) = files.next_entry().await {
        let name = file.file_name().to_string_lossy().into_owned();
        if name.starts_with(&prefix) && name.ends_with(".json") && file.path() != current {
            tokio::fs::remove_file(file.path()).await.ok();
        }
    }
}

/// One APK of the package in the repo's index.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct IndexVersion {
    /// Lower-case hex; the index's key for the version.
    pub sha256: String,
    pub code: i64,
    pub name: String,
    /// "ws.xsoh.etar_57.apk", at `<repo>/<file>`.
    pub file: String,
    /// Bytes; 0 if the index has none.
    pub size: u64,
    /// In a release channel (a beta): never picked by the index path.
    pub beta: bool,
}

#[derive(Deserialize)]
struct RawPackage {
    #[serde(default)]
    versions: HashMap<String, RawVersion>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawVersion {
    file: RawFile,
    manifest: RawManifest,
    #[serde(default)]
    release_channels: Vec<String>,
}

#[derive(Deserialize)]
struct RawFile {
    name: String,
    #[serde(default)]
    sha256: Option<String>,
    #[serde(default)]
    size: u64,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawManifest {
    version_code: i64,
    #[serde(default)]
    version_name: String,
}

/// Reads only `packages.<package>` of an index-v2 document, skipping every other package without
/// keeping it (the whole of f-droid.org's index parsed would be hundreds of MB).
struct IndexSeed<'a>(&'a str);

impl<'de> DeserializeSeed<'de> for IndexSeed<'_> {
    type Value = Option<RawPackage>;

    fn deserialize<D: Deserializer<'de>>(self, deserializer: D) -> Result<Self::Value, D::Error> {
        deserializer.deserialize_map(self)
    }
}

impl<'de> Visitor<'de> for IndexSeed<'_> {
    type Value = Option<RawPackage>;

    fn expecting(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        f.write_str("an F-Droid index (v2)")
    }

    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<Self::Value, A::Error> {
        let mut found = None;
        while let Some(key) = map.next_key::<String>()? {
            if key == "packages" {
                found = map.next_value_seed(PackagesSeed(self.0))?;
            } else {
                map.next_value::<IgnoredAny>()?;
            }
        }
        Ok(found)
    }
}

struct PackagesSeed<'a>(&'a str);

impl<'de> DeserializeSeed<'de> for PackagesSeed<'_> {
    type Value = Option<RawPackage>;

    fn deserialize<D: Deserializer<'de>>(self, deserializer: D) -> Result<Self::Value, D::Error> {
        deserializer.deserialize_map(self)
    }
}

impl<'de> Visitor<'de> for PackagesSeed<'_> {
    type Value = Option<RawPackage>;

    fn expecting(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        f.write_str("the index's packages")
    }

    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<Self::Value, A::Error> {
        let mut found = None;
        while let Some(key) = map.next_key::<String>()? {
            if key == self.0 {
                found = Some(map.next_value::<RawPackage>()?);
            } else {
                map.next_value::<IgnoredAny>()?;
            }
        }
        Ok(found)
    }
}

/// The package's APKs in an index-v2 document, `None` when the index doesn't have the package. A
/// version whose key isn't a SHA-256, whose `file.sha256` is another, or whose file isn't a plain
/// `.apk` name is left out (never trusted).
pub(crate) fn package_versions(
    reader: impl std::io::Read,
    package: &str,
) -> Result<Option<Vec<IndexVersion>>, serde_json::Error> {
    let mut deserializer = serde_json::Deserializer::from_reader(reader);
    let Some(raw) = IndexSeed(package).deserialize(&mut deserializer)? else {
        return Ok(None);
    };
    let mut versions: Vec<IndexVersion> = raw
        .versions
        .into_iter()
        .filter_map(|(key, version)| {
            let sha256 = sha256_hex(&key)?;
            if let Some(listed) = &version.file.sha256
                && listed.to_ascii_lowercase() != sha256
            {
                return None;
            }
            Some(IndexVersion {
                sha256,
                code: version.manifest.version_code,
                name: version.manifest.version_name,
                file: plain_file_name(&version.file.name, ".apk")?,
                size: version.file.size,
                beta: !version.release_channels.is_empty(),
            })
        })
        .collect();
    versions.sort_by(|a, b| b.code.cmp(&a.code).then_with(|| a.file.cmp(&b.file)));
    Ok(Some(versions))
}

/// [package_versions] of a cached index file, off the async threads.
pub async fn read_package(path: PathBuf, package: &str) -> Result<Vec<IndexVersion>, Failure> {
    let wanted = package.to_string();
    let read = tokio::task::spawn_blocking(move || {
        let file = std::fs::File::open(&path)?;
        package_versions(std::io::BufReader::with_capacity(1 << 16, file), &wanted)
            .map_err(std::io::Error::other)
    })
    .await
    .map_err(|e| Failure::new(SyncProblem::Server, e.to_string()))?;
    match read {
        Ok(Some(versions)) => Ok(versions),
        Ok(None) => Err(Failure::new(
            SyncProblem::FdroidUnknown,
            format!("the repo's index has no package {package}"),
        )),
        Err(err) => Err(Failure::new(
            SyncProblem::RepoError,
            format!("the repo's index can't be read: {err}"),
        )),
    }
}

/// The version to sync: the one with `code` (the API's pick; the APK named `<id>_<code>.apk`
/// first), else the highest `versionCode` outside a release channel.
pub fn pick<'a>(
    versions: &'a [IndexVersion],
    package: &str,
    code: Option<i64>,
) -> Result<&'a IndexVersion, Failure> {
    match code {
        Some(code) => {
            let named = format!("{package}_{code}.apk");
            versions
                .iter()
                .find(|v| v.code == code && v.file == named)
                .or_else(|| versions.iter().find(|v| v.code == code))
                .ok_or_else(|| {
                    Failure::new(
                        SyncProblem::RepoError,
                        format!("the repo's index has no APK of version {code} of {package}"),
                    )
                })
        }
        None => versions
            .iter()
            .filter(|v| !v.beta)
            .max_by_key(|v| v.code)
            .ok_or_else(|| {
                Failure::new(
                    SyncProblem::NoRelease,
                    format!("the repo lists no stable version of {package}"),
                )
            }),
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    #[test]
    fn a_repo_address_is_https_without_its_trailing_slash() {
        assert_eq!(normalize_repo("").unwrap(), DEFAULT_REPO);
        assert_eq!(normalize_repo("  ").unwrap(), DEFAULT_REPO);
        assert_eq!(
            normalize_repo(" https://f-droid.org/repo/ ").unwrap(),
            "https://f-droid.org/repo"
        );
        assert_eq!(
            normalize_repo("https://APT.izzysoft.de/fdroid/repo//").unwrap(),
            "https://apt.izzysoft.de/fdroid/repo"
        );
        for refused in [
            "http://f-droid.org/repo",
            "f-droid.org/repo",
            "ftp://f-droid.org/repo",
            "https://user:pw@f-droid.org/repo",
            "https://f-droid.org/repo?x=1",
            "https://f-droid.org/repo#top",
            "javascript:alert(1)",
        ] {
            let error = normalize_repo(refused).unwrap_err();
            assert!(error.contains("https://"), "{refused}: {error}");
            assert!(error.ends_with("Nothing was saved."), "{error}");
        }
        assert!(normalize_repo(&format!("https://f-droid.org/{}", "a".repeat(200))).is_err());
        assert_eq!(parse_package(" ws.xsoh.etar ").unwrap(), "ws.xsoh.etar");
        for refused in [
            "etar",
            "ws.xsoh.",
            "ws..etar",
            "ws.xsoh.etar/1",
            "1ws.etar",
            "",
        ] {
            assert!(parse_package(refused).is_err(), "{refused}");
        }
    }

    /// f-droid.org takes the per-package API; every other repo the index alone.
    #[test]
    fn f_droid_org_takes_the_api_and_another_repo_the_index() {
        assert_eq!(
            Repo::from_stored("https://f-droid.org/repo"),
            Some(Repo {
                url: "https://f-droid.org/repo".to_string(),
                api: Some("https://f-droid.org/api/v1/packages".to_string()),
            })
        );
        assert_eq!(
            Repo::from_stored("https://apt.izzysoft.de/fdroid/repo"),
            Some(Repo {
                url: "https://apt.izzysoft.de/fdroid/repo".to_string(),
                api: None,
            })
        );
        assert_eq!(Repo::from_stored("http://f-droid.org/repo"), None);
        assert_eq!(Repo::from_stored("not a url"), None);
        assert_eq!(repo_label("https://f-droid.org/repo"), "F-Droid");
        assert_eq!(
            repo_label("https://apt.izzysoft.de/fdroid/repo"),
            "apt.izzysoft.de"
        );
        assert_eq!(
            package_page("https://f-droid.org/repo", "ws.xsoh.etar").as_deref(),
            Some("https://f-droid.org/packages/ws.xsoh.etar/")
        );
        assert_eq!(
            package_page("https://apt.izzysoft.de/fdroid/repo", "ws.xsoh.etar"),
            None
        );
    }

    #[test]
    fn a_version_name_is_the_tag() {
        assert_eq!(tag_of("1.0.57", 57), "v1.0.57");
        assert_eq!(tag_of("  ", 57), "v57");
        assert_eq!(tag_of("1.0\n57", 57), "v57");
        assert_eq!(tag_of(&"9".repeat(65), 57), "v57");
    }

    #[test]
    fn the_api_suggests_the_version() {
        let answer = |json: &str| serde_json::from_str::<ApiPackage>(json).unwrap();
        let etar = answer(
            r#"{"packageName":"ws.xsoh.etar","suggestedVersionCode":57,"packages":[
                {"versionName":"1.0.58-beta","versionCode":58},
                {"versionName":"1.0.57","versionCode":57},
                {"versionName":"1.0.56","versionCode":56}]}"#,
        );
        assert_eq!(
            pick_api(&etar, "ws.xsoh.etar").unwrap(),
            (57, "1.0.57".to_string())
        );
        let no_suggestion = answer(
            r#"{"packageName":"a.b","packages":[{"versionName":"2","versionCode":2},
                {"versionName":"3","versionCode":3}]}"#,
        );
        assert_eq!(
            pick_api(&no_suggestion, "a.b").unwrap(),
            (3, "3".to_string())
        );
        let empty = answer(r#"{"packageName":"a.b","packages":[]}"#);
        assert_eq!(
            pick_api(&empty, "a.b").unwrap_err().problem,
            SyncProblem::NoRelease
        );
    }

    #[test]
    fn entry_json_names_the_index() {
        let sha = "ab".repeat(32);
        let entry = format!(
            r#"{{"timestamp":1,"version":20002,"index":{{"name":"/index-v2.json","sha256":"{}","size":67000000,"numPackages":4500}},"diffs":{{}}}}"#,
            sha.to_uppercase()
        );
        assert_eq!(
            parse_entry(entry.as_bytes()).unwrap(),
            EntryIndex {
                name: "index-v2.json".to_string(),
                sha256: sha.clone(),
                size: 67_000_000,
            }
        );
        for (name, sha256, size) in [
            ("/../etc/passwd", sha.as_str(), 10),
            ("/sub/index-v2.json", sha.as_str(), 10),
            ("/index-v2.json", "abc", 10),
            ("/index-v2.json", sha.as_str(), MAX_INDEX_BYTES + 1),
        ] {
            let entry =
                format!(r#"{{"index":{{"name":"{name}","sha256":"{sha256}","size":{size}}}}}"#);
            assert_eq!(
                parse_entry(entry.as_bytes()).unwrap_err().problem,
                SyncProblem::RepoError,
                "{name} {sha256} {size}"
            );
        }
        assert_eq!(
            parse_entry(b"<html>").unwrap_err().problem,
            SyncProblem::RepoError
        );
    }

    /// One version in [index_json]: `(sha256, code, name, file, beta)`.
    pub(crate) type FakeVersion<'a> = (String, i64, &'a str, String, bool);

    /// An index-v2 document: `packages` maps each id to its versions.
    pub(crate) fn index_json(packages: &[(&str, Vec<FakeVersion>)]) -> String {
        let packages: serde_json::Map<String, serde_json::Value> = packages
            .iter()
            .map(|(id, versions)| {
                let versions: serde_json::Map<String, serde_json::Value> = versions
                    .iter()
                    .map(|(sha, code, name, file, beta)| {
                        let mut version = serde_json::json!({
                            "added": 1,
                            "file": {"name": format!("/{file}"), "sha256": sha, "size": 1000},
                            "manifest": {"versionCode": code, "versionName": name,
                                         "usesSdk": {"minSdkVersion": 21}},
                        });
                        if *beta {
                            version["releaseChannels"] = serde_json::json!(["Beta"]);
                        }
                        (sha.clone(), version)
                    })
                    .collect();
                (
                    id.to_string(),
                    serde_json::json!({"metadata": {"name": {"en-US": id}}, "versions": versions}),
                )
            })
            .collect();
        serde_json::json!({"repo": {"name": {"en-US": "Test"}, "timestamp": 1}, "packages": packages})
            .to_string()
    }

    /// An in-process F-Droid repo (the GitHub tests' pattern: an axum server on 127.0.0.1): its
    /// entry.json, index, per-package API and APKs, and how often each was asked for.
    #[derive(Default)]
    pub(crate) struct FakeRepo {
        pub entry: std::sync::Mutex<String>,
        pub index: std::sync::Mutex<Vec<u8>>,
        /// Package -> the API's answer; a package without one is a 404.
        pub api: std::sync::Mutex<HashMap<String, String>>,
        /// File name -> bytes, served at `/repo/<name>`.
        pub files: std::sync::Mutex<HashMap<String, Vec<u8>>>,
        pub entry_gets: std::sync::atomic::AtomicUsize,
        pub index_gets: std::sync::atomic::AtomicUsize,
        pub api_gets: std::sync::atomic::AtomicUsize,
        pub file_gets: std::sync::atomic::AtomicUsize,
    }

    impl FakeRepo {
        /// Serves `index` with an entry.json naming its SHA-256 and size.
        pub fn publish(&self, index: &str) {
            let sha256 = hex::encode(Sha256::digest(index.as_bytes()));
            self.publish_as(index.as_bytes(), &sha256, index.len() as u64);
        }

        /// Serves `index` with an entry.json naming `sha256` and `size`, whatever the index is.
        pub fn publish_as(&self, index: &[u8], sha256: &str, size: u64) {
            *self.index.lock().unwrap() = index.to_vec();
            *self.entry.lock().unwrap() = format!(
                r#"{{"timestamp":1,"version":20002,"index":{{"name":"/index-v2.json","sha256":"{sha256}","size":{size},"numPackages":1}},"diffs":{{}}}}"#
            );
        }

        pub fn count(counter: &std::sync::atomic::AtomicUsize) -> usize {
            counter.load(std::sync::atomic::Ordering::SeqCst)
        }
    }

    /// Starts a fake repo; its address is `http://<host>:<port>/repo` - on `127.0.0.1` it has the
    /// per-package API ([API_HOSTS]), on `localhost` the sync takes the index path.
    pub(crate) async fn fake_repo(host: &str) -> (String, std::sync::Arc<FakeRepo>) {
        use axum::extract::{Path as UrlPath, State};
        use axum::http::StatusCode;
        use axum::response::IntoResponse;
        use std::sync::atomic::Ordering::SeqCst;
        let fake = std::sync::Arc::new(FakeRepo::default());
        let router =
            axum::Router::new()
                .route(
                    "/repo/{file}",
                    axum::routing::get(
                        |State(fake): State<std::sync::Arc<FakeRepo>>,
                         UrlPath(file): UrlPath<String>| async move {
                            match file.as_str() {
                                "entry.json" => {
                                    fake.entry_gets.fetch_add(1, SeqCst);
                                    fake.entry
                                        .lock()
                                        .unwrap()
                                        .clone()
                                        .into_bytes()
                                        .into_response()
                                }
                                "index-v2.json" => {
                                    fake.index_gets.fetch_add(1, SeqCst);
                                    fake.index.lock().unwrap().clone().into_response()
                                }
                                _ => {
                                    fake.file_gets.fetch_add(1, SeqCst);
                                    match fake.files.lock().unwrap().get(&file) {
                                        Some(bytes) => bytes.clone().into_response(),
                                        None => StatusCode::NOT_FOUND.into_response(),
                                    }
                                }
                            }
                        },
                    ),
                )
                .route(
                    "/api/v1/packages/{id}",
                    axum::routing::get(
                        |State(fake): State<std::sync::Arc<FakeRepo>>,
                         UrlPath(id): UrlPath<String>| async move {
                            fake.api_gets.fetch_add(1, SeqCst);
                            match fake.api.lock().unwrap().get(&id) {
                                Some(body) => body.clone().into_response(),
                                None => StatusCode::NOT_FOUND.into_response(),
                            }
                        },
                    ),
                )
                .with_state(fake.clone());
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
        (format!("http://{host}:{port}/repo"), fake)
    }

    fn cached_files(dir: &Path) -> Vec<String> {
        let mut names: Vec<String> = std::fs::read_dir(dir)
            .map(|files| {
                files
                    .map(|f| f.unwrap().file_name().to_string_lossy().into_owned())
                    .collect()
            })
            .unwrap_or_default();
        names.sort();
        names
    }

    /// The index is downloaded only when entry.json names another SHA-256, checked against it
    /// before it is used, and the repo's older copy goes once a new one is in place.
    #[tokio::test]
    async fn the_index_is_cached_by_the_entrys_hash() {
        // As `main` (and `TestApp::new`) do: reqwest's rustls needs the process-wide provider.
        let _ = rustls::crypto::ring::default_provider().install_default();
        let (url, fake) = fake_repo("127.0.0.1").await;
        let sha = |n: u8| format!("{n:02x}").repeat(32);
        let v1 = index_json(&[(
            "a.b",
            vec![(sha(1), 1, "1", "a.b_1.apk".to_string(), false)],
        )]);
        fake.publish(&v1);
        let dir = tempfile::tempdir().unwrap();
        let cache = dir.path().join(CACHE_DIR);
        let repo = Repo::from_stored(&url).unwrap();
        let client = client().unwrap();

        let first = index_file(&client, &repo, &cache).await.unwrap();
        assert_eq!(FakeRepo::count(&fake.index_gets), 1);
        assert_eq!(read_package(first.clone(), "a.b").await.unwrap()[0].code, 1);
        // Unchanged: entry.json asked again, the index not.
        let again = index_file(&client, &repo, &cache).await.unwrap();
        assert_eq!(again, first);
        assert_eq!(FakeRepo::count(&fake.entry_gets), 2);
        assert_eq!(FakeRepo::count(&fake.index_gets), 1, "no re-download");

        // A new index: downloaded again, the older copy removed.
        let v2 = index_json(&[(
            "a.b",
            vec![(sha(2), 2, "2", "a.b_2.apk".to_string(), false)],
        )]);
        fake.publish(&v2);
        let second = index_file(&client, &repo, &cache).await.unwrap();
        assert_ne!(second, first);
        assert_eq!(FakeRepo::count(&fake.index_gets), 2, "re-downloaded");
        assert_eq!(
            read_package(second.clone(), "a.b").await.unwrap()[0].code,
            2
        );
        assert_eq!(
            cached_files(&cache),
            [second.file_name().unwrap().to_string_lossy().into_owned()]
        );

        // An index that isn't what entry.json says: refused, nothing kept, the good copy stays.
        let tampered = v2.replace("\"2\"", "\"3\"");
        assert_eq!(tampered.len(), v2.len());
        fake.publish_as(tampered.as_bytes(), &sha(9), tampered.len() as u64);
        let err = index_file(&client, &repo, &cache).await.unwrap_err();
        assert_eq!(err.problem, SyncProblem::ChecksumMismatch, "{}", err.detail);
        assert!(err.detail.contains("entry.json says"), "{}", err.detail);
        assert_eq!(
            cached_files(&cache),
            [second.file_name().unwrap().to_string_lossy().into_owned()],
            "no .part file, no unverified index"
        );
        // Longer or shorter than entry.json lists: refused too.
        let real = hex::encode(Sha256::digest(v2.as_bytes()));
        fake.publish_as(v2.as_bytes(), &real, v2.len() as u64 + 5);
        let err = index_file(&client, &repo, &cache).await.unwrap_err();
        assert_eq!(err.problem, SyncProblem::RepoError, "{}", err.detail);
        fake.publish_as(v2.as_bytes(), &real, v2.len() as u64 - 5);
        let err = index_file(&client, &repo, &cache).await.unwrap_err();
        assert_eq!(err.problem, SyncProblem::RepoError, "{}", err.detail);

        // Another repo's copy is its own file.
        let (other_url, other) = fake_repo("localhost").await;
        other.publish(&v1);
        let other_path = index_file(&client, &Repo::from_stored(&other_url).unwrap(), &cache)
            .await
            .unwrap();
        assert_ne!(other_path, second);
        assert!(second.exists());

        // No repo there: "the repo didn't answer"; a page without entry.json: not a repo.
        let gone = Repo::from_stored("http://127.0.0.1:9/repo").unwrap();
        assert_eq!(
            index_file(&client, &gone, &cache)
                .await
                .unwrap_err()
                .problem,
            SyncProblem::RepoUnreachable
        );
        let not_a_repo = Repo::from_stored(&url.replace("/repo", "/nothing")).unwrap();
        assert_eq!(
            index_file(&client, &not_a_repo, &cache)
                .await
                .unwrap_err()
                .problem,
            SyncProblem::RepoError
        );
    }

    #[test]
    fn only_the_package_is_read_from_the_index() {
        let sha = |n: u8| format!("{n:02x}").repeat(32);
        let index = index_json(&[
            (
                "org.other",
                vec![(sha(1), 9, "9", "org.other_9.apk".to_string(), false)],
            ),
            (
                "ws.xsoh.etar",
                vec![
                    (
                        sha(2),
                        56,
                        "1.0.56",
                        "ws.xsoh.etar_56.apk".to_string(),
                        false,
                    ),
                    (
                        sha(3),
                        57,
                        "1.0.57",
                        "ws.xsoh.etar_57.apk".to_string(),
                        false,
                    ),
                    (
                        sha(4),
                        58,
                        "1.0.58-beta",
                        "ws.xsoh.etar_58.apk".to_string(),
                        true,
                    ),
                    // Not trusted: a key that isn't a hash, a file that's a path.
                    (
                        "nothex".to_string(),
                        99,
                        "x",
                        "ws.xsoh.etar_99.apk".to_string(),
                        false,
                    ),
                    (sha(5), 98, "x", "../ws.xsoh.etar_98.apk".to_string(), false),
                ],
            ),
        ]);
        let versions = package_versions(index.as_bytes(), "ws.xsoh.etar")
            .unwrap()
            .unwrap();
        assert_eq!(
            versions.iter().map(|v| v.code).collect::<Vec<_>>(),
            [58, 57, 56]
        );
        // The index path: the highest stable code; the API's code: that one.
        let newest = pick(&versions, "ws.xsoh.etar", None).unwrap();
        assert_eq!(
            (newest.code, newest.name.as_str(), newest.file.as_str()),
            (57, "1.0.57", "ws.xsoh.etar_57.apk")
        );
        assert_eq!(newest.sha256, sha(3));
        assert_eq!(
            pick(&versions, "ws.xsoh.etar", Some(56)).unwrap().sha256,
            sha(2)
        );
        assert_eq!(
            pick(&versions, "ws.xsoh.etar", Some(60))
                .unwrap_err()
                .problem,
            SyncProblem::RepoError
        );
        assert_eq!(package_versions(index.as_bytes(), "a.b").unwrap(), None);
        // A listed file hash that isn't the key: left out.
        let mismatched = index.replace(&format!(r#""sha256":"{}""#, sha(3)), r#""sha256":"00""#);
        let versions = package_versions(mismatched.as_bytes(), "ws.xsoh.etar")
            .unwrap()
            .unwrap();
        assert!(versions.iter().all(|v| v.code != 57));
    }
}
