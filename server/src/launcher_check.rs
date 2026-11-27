//! The setup QR's preflight (design 86): the server fetches the launcher APK the QR names
//! (`LAUNCHER_APK_URL`) and checks it against the QR's other two launcher values - the signing
//! certificate's checksum (`LAUNCHER_SIGNATURE_CHECKSUM`) and the admin component's package
//! (`LAUNCHER_ADMIN_COMPONENT`) - so the parent sees before scanning whether a phone would take it,
//! and which Vibb One it would start on. Android says only "Can't set up device" when they don't
//! match.
//!
//! Off the request path, as `system_update::ServerRelease`: one result in memory, keyed by the
//! address and the checksum, standing [RESULT_MAX_AGE] ([RETRY_AFTER] after a failed fetch). A page
//! that finds it due starts a check in the background and never waits; the check also starts when
//! the server does. Read-only: the check never writes `.env` or anything else on the host.

use std::time::{Duration, Instant};

use chrono::{DateTime, Utc};
use serde::Deserialize;

use crate::AppState;
use crate::apk::ApkIdentity;
use crate::config::ForkConfig;
use crate::display_time::Zone;
use crate::i18n::{Lang, t, t_params};

/// How long a result stands before a page starts the next check, and how soon after a fetch that
/// failed.
const RESULT_MAX_AGE: Duration = Duration::from_secs(60 * 60);
const RETRY_AFTER: Duration = Duration::from_secs(5 * 60);
/// Design 140 §6 (QA M4): a result older than this is compared with the release GitHub serves as
/// `releases/latest` before a page shows it, GitHub asked at most this often - the address may serve
/// another APK the moment a stable release is published.
const FRESH_FOR: Duration = Duration::from_secs(5 * 60);
/// The freshness compare's list read at most (a page waits for it).
const FRESHNESS_TIMEOUT: Duration = Duration::from_secs(5);
/// The whole check - the download and the release list - at most: the catalog's download limit.
const CHECK_TIMEOUT: Duration = crate::handlers::tracked_apps::DOWNLOAD_TOTAL_TIMEOUT;
/// How long "Check again" waits for the answer before it goes back to the page.
const CHECK_AGAIN_WAIT: Duration = Duration::from_secs(10);
/// The temp file's name under `data/`: `launcher-check.<random>.apk`, gone once read.
const TEMP_PREFIX: &str = "launcher-check.";

/// What a phone set up with the QR would meet.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Verdict {
    /// The QR's launcher: the package and the signer match.
    Works,
    /// It works, but a newer launcher release is known (its version, "0.39.0-rc.6").
    Older { newest: String },
    /// No v2/v3 signer, or not the one the checksum names: the phone refuses it.
    WrongSigner,
    /// Another app's package (`package`): the phone refuses it.
    WrongPackage { package: String },
    /// A file came, but not an APK with a manifest - or no ZIP at all (a web page at the address).
    NotAnApk,
    /// Nothing came: DNS, refused, an HTTP error, a stall, over the size cap.
    Unreachable,
    /// The whole check took longer than [CHECK_TIMEOUT]: a download that trickles.
    TimedOut,
    /// Design 129b D13: older than the first Vibb One that sends an enrolment attempt
    /// ([ENROL_ATTEMPT_FIRST_CODE]) - this server never enrols it: no QR is made.
    TooOld { version: String },
    /// Design 140 §6 (Q1 (a)): it names a newer server than this one (`server`, "0.96.0") - a
    /// phone set up with it would enrol against routes this server doesn't have: no QR is made.
    NeedsServer { version: String, server: String },
}

/// Design 129b D13: the first launcher versionCode that speaks design 129b's enrolment (`attempt`, `key_error`, the
/// waiting answer) - the first launcher tag after every tag made before it. `launcher_tag_codes.json`'s
/// `enrol_attempt_first` names its tag; `scripts/release-version.sh --selftest` and
/// `tests::the_first_enrol_attempt_code_is_the_contracts` check it.
pub const ENROL_ATTEMPT_FIRST_CODE: i64 = 430003;

impl Verdict {
    /// A phone would stop at "Can't set up device": no QR is made (fail closed).
    pub fn blocks_qr(&self) -> bool {
        !matches!(self, Verdict::Works | Verdict::Older { .. })
    }
}

/// A check's result: the verdict, the QR's launcher version ("0.34.0", its versionName, else its
/// versionCode) and when that release was published, when known.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Checked {
    pub verdict: Verdict,
    pub version: Option<String>,
    pub published: Option<DateTime<Utc>>,
    /// The APK's minSdkVersion: the oldest Android a new phone may run.
    pub min_sdk: Option<u32>,
}

/// The address and the checksum a result was made for.
type Key = (String, String);

/// The one result, in memory (`AppState::launcher_check`).
#[derive(Default)]
pub struct LauncherCheck(std::sync::Mutex<CheckState>);

#[derive(Default)]
struct CheckState {
    key: Option<Key>,
    result: Option<Checked>,
    /// When the next check is due; `None` = now.
    due: Option<Instant>,
    running: bool,
    /// When the result was stored, and when the freshness compare last read the release list
    /// (design 140 §6).
    stored_at: Option<Instant>,
    listed_at: Option<Instant>,
}

/// What a page shows.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Seen {
    /// No checksum: nothing to check (the missing-checksum banner says why).
    Off,
    /// No check has ended for this address and checksum yet.
    Checking,
    Done(Checked),
}

impl LauncherCheck {
    /// The result for `key` and whether to start a check (one is due and none runs). A result made
    /// for another key is dropped.
    fn seen(&self, key: &Key) -> (Option<Checked>, bool) {
        let mut check = self.0.lock().unwrap();
        if check.key.as_ref() != Some(key) {
            *check = CheckState {
                key: Some(key.clone()),
                running: check.running,
                ..CheckState::default()
            };
        }
        let due = check.due.is_none_or(|due| Instant::now() >= due);
        let start = due && !std::mem::replace(&mut check.running, true);
        (check.result.clone(), start)
    }

    /// Stores a finished check's result for `key`, unless the key changed meanwhile. `running` is
    /// cleared by [run]'s [ClearRunning] alone, so it is cleared once, however the check ends.
    fn store(&self, key: &Key, checked: Checked) {
        let mut check = self.0.lock().unwrap();
        if check.key.as_ref() == Some(key) {
            let wait = if matches!(checked.verdict, Verdict::Unreachable | Verdict::TimedOut) {
                RETRY_AFTER
            } else {
                RESULT_MAX_AGE
            };
            check.due = Some(Instant::now() + wait);
            check.result = Some(checked);
            check.stored_at = Some(Instant::now());
        }
    }

    /// The version of the result for `key` when it is older than [FRESH_FOR] and the release list
    /// wasn't read in the last [FRESH_FOR] (the read is marked now); `None` otherwise.
    fn due_for_freshness(&self, key: &Key) -> Option<Option<String>> {
        let mut check = self.0.lock().unwrap();
        let old = |at: Option<Instant>| at.is_none_or(|at| at.elapsed() >= FRESH_FOR);
        if check.key.as_ref() != Some(key)
            || check.running
            || check.result.is_none()
            || !old(check.stored_at)
            || !old(check.listed_at)
        {
            return None;
        }
        check.listed_at = Some(Instant::now());
        check.result.as_ref().map(|checked| checked.version.clone())
    }

    /// The freshness compare found another release at the address: the result for `key` goes,
    /// and the next look starts a check.
    fn forget_if(&self, key: &Key) {
        let mut check = self.0.lock().unwrap();
        if check.key.as_ref() == Some(key) {
            check.result = None;
            check.due = None;
        }
    }

    /// "Check again": the result is dropped and the next look starts a check.
    fn forget(&self) {
        let mut check = self.0.lock().unwrap();
        check.result = None;
        check.due = None;
    }

    /// The tests' result for `config`'s key, as if a check had just ended.
    #[cfg(test)]
    pub(crate) fn set(&self, config: &ForkConfig, checked: Checked) {
        if let Some(key) = key_of(config) {
            *self.0.lock().unwrap() = CheckState {
                key: Some(key),
                result: Some(checked),
                due: Some(Instant::now() + RESULT_MAX_AGE),
                running: false,
                stored_at: Some(Instant::now()),
                listed_at: None,
            };
        }
    }

    /// The tests' clock: the result and the last list read as if made `ago`.
    #[cfg(test)]
    pub(crate) fn age(&self, ago: Duration) {
        let mut check = self.0.lock().unwrap();
        let then = Instant::now().checked_sub(ago);
        check.stored_at = then;
        check.listed_at = check.listed_at.and(then);
    }
}

/// Design 140 §6 (QA M4): before a page shows the QR, a result older than [FRESH_FOR] is compared
/// with the release GitHub serves as `releases/latest` (read at most once per [FRESH_FOR]); when
/// that isn't the checked APK's version, `releases/latest` serves another APK now, so the result goes
/// and the page reads "Checking" (no QR) until the new one is checked. Only for a
/// `github.com/<owner>/<repo>/releases/latest/...` address (a release's own address never moves);
/// a failed read keeps the result - the APK check is the gate, the list only says it moved.
pub async fn refresh_if_moved(state: &AppState) {
    let Some(key) = key_of(&state.config) else {
        return;
    };
    let url = &state.config.launcher_apk_url;
    let Some(repo) = crate::handlers::tracked_apps::launcher_repo_from_url(url) else {
        return;
    };
    if !url.contains(&format!("/{repo}/releases/latest/")) {
        return;
    }
    let Some(checked_version) = state.launcher_check.due_for_freshness(&key) else {
        return;
    };
    let latest =
        match tokio::time::timeout(FRESHNESS_TIMEOUT, latest_tag(&state.config, &repo)).await {
            Ok(Ok(tag)) => tag,
            Ok(Err(err)) => {
                tracing::warn!(
                    "the setup QR's freshness check couldn't read {repo}'s latest release: {err}"
                );
                return;
            }
            Err(_) => {
                tracing::warn!(
                    "the setup QR's freshness check couldn't read {repo}'s latest release in time"
                );
                return;
            }
        };
    // The release `releases/latest` serves - GitHub's own pick (code QA L2) -, as the APK's version reads.
    let newest = Some(shown_tag(
        &latest,
        crate::handlers::tracked_apps::LAUNCHER_ROW_TAG_PREFIX,
    ));
    if let Some(newest) = newest
        && checked_version.as_deref() != Some(newest.as_str())
    {
        tracing::info!(
            "the setup QR's launcher moved to {newest} (checked {}) - checking it again",
            checked_version.as_deref().unwrap_or("?")
        );
        state.launcher_check.forget_if(&key);
    }
}

fn key_of(config: &ForkConfig) -> Option<Key> {
    let checksum = config.launcher_signature_checksum.clone()?;
    Some((config.launcher_apk_url.clone(), checksum))
}

/// What the pages show, starting a check in the background when one is due.
pub fn seen(state: &AppState) -> Seen {
    let Some(key) = key_of(&state.config) else {
        return Seen::Off;
    };
    let (result, start) = state.launcher_check.seen(&key);
    if start {
        let state = state.clone();
        tokio::spawn(async move { run(&state, key).await });
    }
    match result {
        Some(checked) => Seen::Done(checked),
        None => Seen::Checking,
    }
}

/// At start, before anything can start a check: removes a temp file a crash left behind, then
/// starts the first check (in the background).
pub async fn start(state: &AppState) {
    if let Ok(mut files) = tokio::fs::read_dir(state.data_dir.as_ref()).await {
        while let Ok(Some(file)) = files.next_entry().await {
            if file.file_name().to_string_lossy().starts_with(TEMP_PREFIX) {
                let _ = tokio::fs::remove_file(file.path()).await;
            }
        }
    }
    let _ = seen(state);
}

/// Runs a check for `key` and stores its result.
async fn run(state: &AppState, key: Key) {
    let _running = ClearRunning(state.launcher_check.clone());
    let checked = tokio::time::timeout(CHECK_TIMEOUT, check(state, &key.0, &key.1))
        .await
        .unwrap_or_else(|_| {
            tracing::warn!(
                "the setup QR's launcher check took over {} s - stopped",
                CHECK_TIMEOUT.as_secs()
            );
            Checked {
                verdict: Verdict::TimedOut,
                version: None,
                published: None,
                min_sdk: None,
            }
        });
    state.launcher_check.store(&key, checked);
}

/// Clears `running` when a check ends any way - a panic or a dropped task too - so the pages never
/// say "Checking" for good; the next look starts a check again.
struct ClearRunning(std::sync::Arc<LauncherCheck>);

impl Drop for ClearRunning {
    fn drop(&mut self) {
        let mut check = self
            .0
            .0
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        check.running = false;
    }
}

/// Downloads the APK at `url`, reads it and judges it against `checksum` and the admin component.
async fn check(state: &AppState, url: &str, checksum: &str) -> Checked {
    let unreachable = Checked {
        verdict: Verdict::Unreachable,
        version: None,
        published: None,
        min_sdk: None,
    };
    let dir = state.data_dir.as_ref();
    if let Err(err) = tokio::fs::create_dir_all(dir).await {
        tracing::error!(%err, "the setup QR's launcher check can't write to {}", dir.display());
        return unreachable;
    }
    let path = dir.join(format!(
        "{TEMP_PREFIX}{}.apk",
        crate::handlers::tracked_apps::random_label()
    ));
    // Deleted when this is dropped, whatever happens below.
    let file = crate::handlers::tracked_apps::TempDownload::new(path.to_string_lossy().into());
    if let Err(err) = download(url, &file.path).await {
        tracing::warn!("the setup QR's launcher check couldn't download {url}: {err}");
        if err == crate::handlers::tracked_apps::NOT_A_ZIP {
            return Checked {
                verdict: Verdict::NotAnApk,
                ..unreachable
            };
        }
        return unreachable;
    }
    let read = std::path::PathBuf::from(&file.path);
    let identity = tokio::task::spawn_blocking(move || crate::apk::read_identity(&read))
        .await
        .map_err(|err| err.to_string())
        .and_then(|read| read);
    drop(file);
    let identity = match identity {
        Ok(identity) => identity,
        Err(err) => {
            tracing::warn!("the setup QR's launcher at {url} isn't an APK: {err}");
            return Checked {
                verdict: Verdict::NotAnApk,
                version: None,
                published: None,
                min_sdk: None,
            };
        }
    };
    let known = known_releases(state).await;
    let checked = judge(&identity, &state.config, checksum, &known);
    if checked.verdict.blocks_qr() {
        tracing::warn!(
            ?identity,
            "the setup QR's launcher at {url} would be refused: {:?}",
            checked.verdict
        );
    }
    checked
}

/// Streams `url` to `path` (redirects as the catalog's: at most 5, never away from https; the
/// catalog's size cap and stall timeout, `tracked_apps::stream_to_file`).
async fn download(url: &str, path: &str) -> Result<(), String> {
    use crate::handlers::tracked_apps::{DOWNLOAD_STALL_TIMEOUT, https_redirects, stream_to_file};
    let client = reqwest::Client::builder()
        .user_agent(crate::net::USER_AGENT)
        .connect_timeout(Duration::from_secs(15))
        .redirect(https_redirects())
        .build()
        .map_err(|err| err.to_string())?;
    let mut response = tokio::time::timeout(DOWNLOAD_STALL_TIMEOUT, client.get(url).send())
        .await
        .map_err(|_| "no answer".to_string())?
        .and_then(reqwest::Response::error_for_status)
        .map_err(|err| err.to_string())?;
    // A ZIP-less body keeps the bare reason, [check] tells it from the rest by it.
    stream_to_file(&mut response, path, 0, None, DOWNLOAD_STALL_TIMEOUT)
        .await
        .map(|_| ())
        .map_err(|err| {
            if err.detail == crate::handlers::tracked_apps::NOT_A_ZIP {
                err.detail
            } else {
                err.to_string()
            }
        })
}

/// The launcher releases the server knows: the newest (its version and versionCode) and each
/// one's publishing time by versionCode.
#[derive(Debug, Default, PartialEq)]
pub(crate) struct Known {
    pub(crate) newest: Option<(String, i64)>,
    pub(crate) published: Vec<(i64, DateTime<Utc>)>,
    /// The lowest versionCode a phone can enrol with ([ENROL_ATTEMPT_FIRST_CODE] in a running server; the tests'
    /// fixtures, codes 1 and 2, set their own).
    pub(crate) min_code: i64,
    /// This server's version (design 140 §6): the APK's `min_server` is judged against it; `None`
    /// (the tests' default) doesn't judge it.
    pub(crate) own_server: Option<(u64, u64, u64)>,
}

#[derive(Deserialize)]
struct Release {
    tag_name: String,
    #[serde(default)]
    draft: bool,
    published_at: Option<DateTime<Utc>>,
}

/// The launcher catalog row's stored release and, read now, the `launcher-v*` releases in its
/// GitHub repo (else `LAUNCHER_APK_URL`'s) - **prereleases included**: this only says a newer
/// launcher exists, it never picks one for the phones. A failed read loses the list only.
async fn known_releases(state: &AppState) -> Known {
    let row: Option<crate::models::TrackedApp> =
        sqlx::query_as("SELECT * FROM tracked_apps WHERE is_launcher = 1 ORDER BY id LIMIT 1")
            .fetch_optional(&state.db)
            .await
            .unwrap_or_else(|err| {
                tracing::error!(%err, "the setup QR's launcher check can't read the catalog");
                None
            });
    let prefix = row
        .as_ref()
        .and_then(|row| row.release_tag_prefix.clone())
        .unwrap_or_else(|| crate::handlers::tracked_apps::LAUNCHER_ROW_TAG_PREFIX.to_string());
    let mut known = Known {
        // The test fixtures are codes 1 and 2: their own rows set the floor (`judge`'s tests).
        min_code: if cfg!(test) {
            0
        } else {
            ENROL_ATTEMPT_FIRST_CODE
        },
        own_server: Some(state.server_version),
        ..Known::default()
    };
    let mut offer = |version: String, code: i64| {
        if known
            .newest
            .as_ref()
            .is_none_or(|(_, newest)| code > *newest)
        {
            known.newest = Some((version, code));
        }
    };
    if let Some(row) = &row
        && let Some(tag) = &row.latest_release_tag
        && let Some(code) = row
            .latest_release_version_code
            .or_else(|| crate::install_now::launcher_tag_version_code(tag))
    {
        offer(shown_tag(tag, &prefix), code);
    }
    let repo = row
        .as_ref()
        .filter(|row| row.source_type == "github" && !row.github_repo.is_empty())
        .map(|row| row.github_repo.clone())
        .or_else(|| {
            crate::handlers::tracked_apps::launcher_repo_from_url(&state.config.launcher_apk_url)
        });
    let Some(repo) = repo else {
        return known;
    };
    match release_list(&state.config, &repo).await {
        Ok(releases) => {
            for release in releases.iter().filter(|r| !r.draft) {
                if !release.tag_name.starts_with(&prefix) {
                    continue;
                }
                let Some(code) = crate::install_now::launcher_tag_version_code(&release.tag_name)
                else {
                    continue;
                };
                offer(shown_tag(&release.tag_name, &prefix), code);
                if let Some(at) = release.published_at {
                    known.published.push((code, at));
                }
            }
        }
        Err(err) => {
            tracing::warn!("the setup QR's launcher check couldn't list {repo}'s releases: {err}")
        }
    }
    known
}

/// "launcher-v0.39.0-rc.6" as "0.39.0-rc.6".
fn shown_tag(tag: &str, prefix: &str) -> String {
    tag.strip_prefix(prefix).unwrap_or(tag).to_string()
}

/// The tag of `repo`'s release GitHub serves as `releases/latest` (design 140 §6, code QA L2).
async fn latest_tag(config: &ForkConfig, repo: &str) -> Result<String, String> {
    #[derive(Deserialize)]
    struct Latest {
        tag_name: String,
    }
    let client = reqwest::Client::builder()
        .user_agent(crate::net::USER_AGENT)
        .timeout(FRESHNESS_TIMEOUT)
        .build()
        .map_err(|err| err.to_string())?;
    client
        .get(format!(
            "{}/repos/{repo}/releases/latest",
            config.github_api
        ))
        .send()
        .await
        .and_then(reqwest::Response::error_for_status)
        .map_err(|err| err.to_string())?
        .json::<Latest>()
        .await
        .map(|latest| latest.tag_name)
        .map_err(|err| err.to_string())
}

async fn release_list(config: &ForkConfig, repo: &str) -> Result<Vec<Release>, String> {
    let client = reqwest::Client::builder()
        .user_agent(crate::net::USER_AGENT)
        .timeout(Duration::from_secs(15))
        .build()
        .map_err(|err| err.to_string())?;
    client
        .get(format!(
            "{}/repos/{repo}/releases?per_page=100",
            config.github_api
        ))
        .send()
        .await
        .and_then(reqwest::Response::error_for_status)
        .map_err(|err| err.to_string())?
        .json()
        .await
        .map_err(|err| err.to_string())
}

/// Whether the QR's `checksum` (`PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM`: URL-safe base64 of
/// the certificate's SHA-256, no padding) names the certificate `apk.rs` read (`signer_hex`:
/// lower-case hex of the same SHA-256). Both are decoded to their 32 bytes; never compared as text.
pub fn checksum_matches(checksum: &str, signer_hex: &str) -> bool {
    use base64::Engine as _;
    let want =
        base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(checksum.trim_end_matches('='));
    let have = hex::decode(signer_hex);
    matches!((want, have), (Ok(want), Ok(have)) if want.len() == 32 && want == have)
}

/// The verdict on an APK the QR's address served.
pub(crate) fn judge(
    identity: &ApkIdentity,
    config: &ForkConfig,
    checksum: &str,
    known: &Known,
) -> Checked {
    let Some(package) = identity.package.clone() else {
        return Checked {
            verdict: Verdict::NotAnApk,
            version: None,
            published: None,
            min_sdk: None,
        };
    };
    let version = identity
        .version_name
        .clone()
        .or_else(|| identity.version_code.map(|code| code.to_string()));
    let published = identity.version_code.and_then(|code| {
        known
            .published
            .iter()
            .find(|(known, _)| *known == code)
            .map(|(_, at)| *at)
    });
    let admin_package = config
        .launcher_admin_component
        .split_once('/')
        .map_or(config.launcher_admin_component.as_str(), |(package, _)| {
            package
        });
    let signed = identity
        .signer_sha256
        .as_deref()
        .is_some_and(|signer| checksum_matches(checksum, signer));
    let verdict = if package != admin_package {
        Verdict::WrongPackage { package }
    } else if !signed {
        Verdict::WrongSigner
    } else if identity
        .version_code
        .is_some_and(|code| code < known.min_code)
    {
        Verdict::TooOld {
            version: version.clone().unwrap_or_default(),
        }
    } else if let Some(verdict) = known.own_server.and_then(|own| {
        // Design 140 §6: a Vibb One that needs a newer server, or from the first tag that carries
        // the value without a readable one (fail closed), makes no QR.
        match crate::catalog_holds::needs_server(
            true,
            identity.min_server,
            identity.version_code,
            crate::catalog_holds::LAUNCHER_MIN_SERVER_FIRST_CODE,
            own,
        ) {
            crate::catalog_holds::ServerNeed::Serve => None,
            crate::catalog_holds::ServerNeed::Hold(needed) => Some(Verdict::NeedsServer {
                version: version.clone().unwrap_or_default(),
                server: crate::catalog_holds::version_text(needed),
            }),
            crate::catalog_holds::ServerNeed::Refuse => Some(Verdict::NotAnApk),
        }
    }) {
        verdict
    } else {
        match (identity.version_code, &known.newest) {
            (Some(code), Some((newest, newest_code))) if code < *newest_code => Verdict::Older {
                newest: newest.clone(),
            },
            _ => Verdict::Works,
        }
    };
    Checked {
        verdict,
        version,
        published,
        min_sdk: identity.min_sdk,
    }
}

/// The row the pages show (`partials/launcher_check.html`, as `launcher`).
pub struct Line {
    /// "checking", "works", "older", "needs_server" (design 140, orange) or "refused".
    pub state: &'static str,
    pub text: String,
    /// The short visible line under a warning: "A phone would refuse it. No QR code until it's fixed." (design 113).
    pub sub: Option<String>,
    /// What to do, behind the warning's ⓘ.
    pub fix: Option<String>,
    /// The oldest Android a new phone may run ("A new phone needs Android 14 or newer..."), from
    /// the APK's minSdkVersion, for a launcher a phone would take.
    pub needs: Option<String>,
    /// Check again comes back to this phone's Connect page; `None` = Phone Setup.
    pub device_id: Option<i64>,
    /// The page reloads itself while it checks - only a page a GET drew (a reload would send a
    /// POST again).
    pub auto_reload: bool,
}

impl Line {
    /// No QR is made: a phone would refuse the launcher, or the check hasn't ended.
    pub fn blocks_qr(&self) -> bool {
        matches!(self.state, "checking" | "refused" | "needs_server")
    }
}

/// The row for what [seen] found, in `lang`; `None` without a checksum.
pub fn line(
    state: &AppState,
    lang: Lang,
    zone: &Zone,
    device_id: Option<i64>,
    auto_reload: bool,
) -> Option<Line> {
    let mut seen_min_sdk = None;
    let (state_word, text, sub, fix) = match seen(state) {
        Seen::Off => return None,
        Seen::Checking => (
            "checking",
            t(lang, "settings.provisioning.launcher.checking").to_string(),
            None,
            None,
        ),
        Seen::Done(checked) => {
            seen_min_sdk = checked.min_sdk;
            words(lang, zone, &checked, &state.config.launcher_apk_url)
        }
    };
    let needs = match seen_min_sdk {
        Some(api) if matches!(state_word, "works" | "older") => Some(needs_android(lang, api)),
        _ => None,
    };
    Some(Line {
        state: state_word,
        text,
        sub,
        fix,
        needs,
        device_id,
        auto_reload,
    })
}

/// The Android release of API level `api` (34 = "14"); `None` past the ones known here.
fn android_release(api: u32) -> Option<&'static str> {
    Some(match api {
        21 | 22 => "5",
        23 => "6",
        24 | 25 => "7",
        26 | 27 => "8",
        28 => "9",
        29 => "10",
        30 => "11",
        31 | 32 => "12",
        33 => "13",
        34 => "14",
        35 => "15",
        36 => "16",
        _ => return None,
    })
}

/// "A new phone needs Android 14 or newer", in `lang`; an API level past [android_release] by its
/// number.
fn needs_android(lang: Lang, api: u32) -> String {
    match android_release(api) {
        Some(release) => t_params(
            lang,
            "settings.provisioning.launcher.needs_android",
            &[("version", release)],
        ),
        None => t_params(
            lang,
            "settings.provisioning.launcher.needs_api",
            &[("level", &api.to_string())],
        ),
    }
}

/// A result's state, row, short visible line and what to do (behind the ⓘ), in `lang` (design 113: a refused launcher
/// says in view that no QR code is made; the env variables to check are the ⓘ's).
fn words(
    lang: Lang,
    zone: &Zone,
    checked: &Checked,
    url: &str,
) -> (&'static str, String, Option<String>, Option<String>) {
    let refused = || Some(t(lang, "settings.provisioning.launcher.refused_short").to_string());
    let address = || {
        Some(t_params(
            lang,
            "settings.provisioning.launcher.address_sub",
            &[("url", url)],
        ))
    };
    let version = checked.version.as_deref().unwrap_or("?");
    let date = checked.published.map(|at| {
        let now = Utc::now();
        zone.date_of(zone.local(at).date_naive(), zone.today(now))
    });
    match &checked.verdict {
        Verdict::Works => {
            let text = match &date {
                Some(date) => t_params(
                    lang,
                    "settings.provisioning.launcher.works_dated",
                    &[("version", version), ("date", date)],
                ),
                None => t_params(
                    lang,
                    "settings.provisioning.launcher.works",
                    &[("version", version)],
                ),
            };
            ("works", text, None, None)
        }
        Verdict::Older { newest } => {
            let text = match &date {
                Some(date) => t_params(
                    lang,
                    "settings.provisioning.launcher.older_dated",
                    &[("version", version), ("date", date), ("newest", newest)],
                ),
                None => t_params(
                    lang,
                    "settings.provisioning.launcher.older",
                    &[("version", version), ("newest", newest)],
                ),
            };
            let fix = t_params(
                lang,
                "settings.provisioning.launcher.older_sub",
                &[("newest", newest)],
            );
            let sub = t(lang, "settings.provisioning.launcher.older_short").to_string();
            ("older", text, Some(sub), Some(fix))
        }
        Verdict::WrongSigner => (
            "refused",
            t(lang, "settings.provisioning.launcher.wrong_signer").to_string(),
            refused(),
            Some(t(lang, "settings.provisioning.launcher.wrong_signer_sub").to_string()),
        ),
        Verdict::WrongPackage { package } => (
            "refused",
            t_params(
                lang,
                "settings.provisioning.launcher.wrong_package",
                &[("package", package)],
            ),
            refused(),
            Some(t(lang, "settings.provisioning.launcher.wrong_package_sub").to_string()),
        ),
        // Not an APK: the short line alone (design 113).
        Verdict::NotAnApk => (
            "refused",
            t(lang, "settings.provisioning.launcher.not_an_apk").to_string(),
            refused(),
            None,
        ),
        Verdict::TimedOut => (
            "refused",
            t(lang, "settings.provisioning.launcher.timed_out").to_string(),
            refused(),
            address(),
        ),
        Verdict::Unreachable => (
            "refused",
            t(lang, "settings.provisioning.launcher.unreachable").to_string(),
            refused(),
            address(),
        ),
        // Design 140 §6: orange, the short line in view, the fix behind the (i).
        Verdict::NeedsServer { server, .. } => (
            "needs_server",
            t_params(
                lang,
                "settings.provisioning.launcher.needs_server",
                &[("server", server)],
            ),
            Some(t(lang, "settings.provisioning.launcher.needs_server_short").to_string()),
            Some(t_params(
                lang,
                "settings.provisioning.launcher.needs_server_sub",
                &[("server", server)],
            )),
        ),
        // Design 129b D13: the fix behind the (i).
        Verdict::TooOld { .. } => (
            "refused",
            t(lang, "settings.provisioning.launcher.too_old").to_string(),
            refused(),
            Some(t(lang, "settings.provisioning.launcher.too_old_sub").to_string()),
        ),
    }
}

/// `POST /settings/provisioning/launcher-check` ("Check again"): drops the result, starts a check
/// and waits up to [CHECK_AGAIN_WAIT] for it, then goes back to the row (`#launcher`) - Phone
/// Setup's, or the Connect page of the phone in the form's `device`.
pub async fn check_again(
    axum::extract::State(state): axum::extract::State<AppState>,
    axum::Form(form): axum::Form<std::collections::HashMap<String, String>>,
) -> axum::response::Response {
    use axum::response::IntoResponse;
    state.launcher_check.forget();
    let _ = seen(&state);
    let waited = Instant::now();
    while waited.elapsed() < CHECK_AGAIN_WAIT && seen(&state) == Seen::Checking {
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
    let back = match form.get("device").and_then(|id| id.parse::<i64>().ok()) {
        Some(id) => format!("/devices/{id}/provision#launcher"),
        None => "/settings/provisioning#launcher".to_string(),
    };
    axum::response::Redirect::to(&back).into_response()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tests::TestApp;

    const KEY1: &str = "0796f1809e476a2ce724f1341bcb32d03185b7a202c3469787c5775d65ef4be3";
    /// KEY1's SHA-256 as the QR carries it (its `-` is URL-safe base64's).
    const KEY1_CHECKSUM: &str = "B5bxgJ5HaiznJPE0G8sy0DGFt6ICw0aXh8V3XWXvS-M";
    const KEY2_CHECKSUM: &str = "djnbCbEHugOS0yvy6ArrxOcoyOoGwJGg1DMP3ard1DQ";

    #[test]
    fn the_checksum_is_compared_as_bytes() {
        assert!(checksum_matches(KEY1_CHECKSUM, KEY1));
        assert!(checksum_matches(&format!("{KEY1_CHECKSUM}="), KEY1));
        assert!(checksum_matches(KEY1_CHECKSUM, &KEY1.to_uppercase()));
        assert!(!checksum_matches(KEY2_CHECKSUM, KEY1));
        // Standard base64's `+` for the `-`: not the QR's encoding.
        assert!(!checksum_matches(&KEY1_CHECKSUM.replace('-', "+"), KEY1));
        assert!(!checksum_matches(KEY1, KEY1), "hex is not the checksum");
        assert!(!checksum_matches("", ""));
        assert!(!checksum_matches(KEY1_CHECKSUM, "not hex"));
        assert!(!checksum_matches(&"A".repeat(43), KEY1));
    }

    /// A fake GitHub on loopback: `/latest/<name>` redirects to `/files/<name>` (as GitHub's
    /// `releases/latest/download/` does), which serves `testdata/apk/<name>`; and the launcher
    /// releases of `palchrb/vibb-launcher` - 0.0.0-rc.1 (code 1, the fixtures' own) and the newer
    /// 0.0.0-rc.2 (code 2).
    async fn fake_github() -> String {
        use axum::extract::Path;
        use axum::response::{IntoResponse, Redirect};
        let router = axum::Router::new()
            .route(
                "/latest/{name}",
                axum::routing::get(|Path(name): Path<String>| async move {
                    Redirect::temporary(&format!("/files/{name}"))
                }),
            )
            .route(
                "/files/{name}",
                axum::routing::get(|Path(name): Path<String>| async move {
                    match std::fs::read(format!("testdata/apk/{name}")) {
                        Ok(bytes) => bytes.into_response(),
                        Err(_) if name == "page.apk" => "<html>a page</html>".into_response(),
                        Err(_) => axum::http::StatusCode::NOT_FOUND.into_response(),
                    }
                }),
            )
            .route(
                "/repos/palchrb/vibb-launcher/releases",
                axum::routing::get(|| async {
                    axum::Json(serde_json::json!([
                        {"tag_name": "launcher-v0.0.0-rc.3", "draft": true, "prerelease": false},
                        {"tag_name": "server-v9.0.0", "prerelease": false,
                         "published_at": "2026-11-01T10:00:00Z"},
                        {"tag_name": "launcher-v0.0.0-rc.2", "prerelease": true,
                         "published_at": "2026-11-10T10:00:00Z"},
                        {"tag_name": "launcher-v0.0.0-rc.1", "prerelease": false,
                         "published_at": "2026-10-21T10:00:00Z"},
                    ]))
                }),
            );
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
        format!("http://127.0.0.1:{port}")
    }

    /// A state whose QR names `name` on [fake_github], signed by `checksum`, for the fixtures'
    /// package; the launcher catalog row watches the fake repo (`latest_tag` its stored release).
    async fn checked(name: &str, checksum: &str, latest_tag: Option<&str>) -> Checked {
        let app = TestApp::new().await;
        let base = fake_github().await;
        let mut state = app.state.clone();
        let mut config = (*state.config).clone();
        config.launcher_apk_url = format!("{base}/latest/{name}");
        config.launcher_signature_checksum = Some(checksum.to_string());
        config.launcher_admin_component = "me.vibb.testfixture/x.Admin".to_string();
        config.github_api = base;
        state.config = std::sync::Arc::new(config);
        sqlx::query(
            "INSERT INTO tracked_apps (name, package_name, source_type, github_repo, \
             release_tag_prefix, is_launcher, latest_release_tag) \
             VALUES ('Vibb One', 'me.vibb.testfixture', 'github', 'palchrb/vibb-launcher', \
             'launcher-v', 1, ?)",
        )
        .bind(latest_tag)
        .execute(&app.db)
        .await
        .unwrap();
        let key = key_of(&state.config).unwrap();
        // The new key takes the place of the one TestApp set, as a page's look would.
        let _ = state.launcher_check.seen(&key);
        run(&state, key).await;
        // The temp file is gone.
        let left: Vec<_> = std::fs::read_dir(state.data_dir.as_ref())
            .map(|files| {
                files
                    .filter_map(Result::ok)
                    .map(|f| f.file_name())
                    .collect()
            })
            .unwrap_or_default();
        assert!(
            !left
                .iter()
                .any(|f| f.to_string_lossy().starts_with(TEMP_PREFIX)),
            "{left:?}"
        );
        match seen(&state) {
            Seen::Done(checked) => checked,
            other => panic!("no result: {other:?}"),
        }
    }

    #[tokio::test]
    async fn the_verdicts() {
        let published = |at: &str| Some(at.parse::<DateTime<Utc>>().unwrap());
        // The newest launcher: works, with its date from the release list.
        assert_eq!(
            checked("fixture-key1-v2.apk", KEY1_CHECKSUM, None).await,
            Checked {
                verdict: Verdict::Works,
                version: Some("2".to_string()),
                published: published("2026-11-10T10:00:00Z"),
                min_sdk: Some(26),
            }
        );
        // Older than the prerelease the list names (a draft and the server's release don't count).
        assert_eq!(
            checked("fixture-key1.apk", KEY1_CHECKSUM, None).await,
            Checked {
                verdict: Verdict::Older {
                    newest: "0.0.0-rc.2".to_string()
                },
                version: Some("1".to_string()),
                published: published("2026-10-21T10:00:00Z"),
                min_sdk: Some(26),
            }
        );
        let verdict = |c: Checked| c.verdict;
        assert_eq!(
            verdict(checked("fixture-key2.apk", KEY1_CHECKSUM, None).await),
            Verdict::WrongSigner
        );
        assert_eq!(
            verdict(checked("fixture-key1-v1-only.apk", KEY1_CHECKSUM, None).await),
            Verdict::WrongSigner,
            "no v2/v3 signer can't be confirmed"
        );
        assert_eq!(
            verdict(checked("other-package-key1.apk", KEY1_CHECKSUM, None).await),
            Verdict::WrongPackage {
                package: "me.vibb.otherfixture".to_string()
            }
        );
        assert_eq!(
            verdict(checked("missing.apk", KEY1_CHECKSUM, None).await),
            Verdict::Unreachable
        );
        assert_eq!(
            verdict(checked("page.apk", KEY1_CHECKSUM, None).await),
            Verdict::NotAnApk,
            "a page at the address is no APK, not an unreachable address"
        );
    }

    /// Design 129b D13: the floor is the shared contract's `enrol_attempt_first` tag's code.
    #[test]
    fn the_first_enrol_attempt_code_is_the_contracts() {
        let contract: serde_json::Value = serde_json::from_str(
            &std::fs::read_to_string(crate::contracts::path("core/launcher_tag_codes.json"))
                .unwrap(),
        )
        .unwrap();
        let tag = contract["enrol_attempt_first"].as_str().unwrap();
        let row = contract["codes"]
            .as_array()
            .unwrap()
            .iter()
            .find(|row| row["tag"] == tag)
            .expect("the tag is a row");
        assert_eq!(row["code"].as_i64(), Some(ENROL_ATTEMPT_FIRST_CODE));
    }

    #[test]
    fn the_android_a_new_phone_needs() {
        assert_eq!(
            needs_android(Lang::En, 34),
            "A new phone needs Android 14 or newer: update it before the factory reset."
        );
        assert_eq!(android_release(36), Some("16"));
        assert!(needs_android(Lang::En, 40).contains("API level 40"));
    }

    /// Without the release list, the catalog row's stored release still counts: a stored
    /// `launcher-v0.0.0-rc.2` (code 2) makes the code-1 launcher older.
    #[test]
    fn the_catalog_row_alone_judges_older() {
        let config = {
            let mut config = ForkConfig::for_tests();
            config.launcher_admin_component = "me.vibb.testfixture/x.Admin".to_string();
            config
        };
        let identity =
            crate::apk::read_identity(std::path::Path::new("testdata/apk/fixture-key1.apk"))
                .unwrap();
        let known = Known {
            newest: Some(("0.0.0-rc.2".to_string(), 2)),
            published: Vec::new(),
            min_code: 0,
            own_server: None,
        };
        assert_eq!(
            judge(&identity, &config, KEY1_CHECKSUM, &known).verdict,
            Verdict::Older {
                newest: "0.0.0-rc.2".to_string()
            }
        );
        assert_eq!(
            judge(&identity, &config, KEY1_CHECKSUM, &Known::default()).verdict,
            Verdict::Works
        );
        // Design 129b D13: under the first launcher that sends an enrolment attempt, no QR.
        let floor = Known {
            min_code: 2,
            ..Known::default()
        };
        let too_old = judge(&identity, &config, KEY1_CHECKSUM, &floor).verdict;
        assert_eq!(
            too_old,
            Verdict::TooOld {
                version: "1".to_string()
            }
        );
        assert!(too_old.blocks_qr());
        let no_package = ApkIdentity {
            package: None,
            ..identity
        };
        assert_eq!(
            judge(&no_package, &config, KEY1_CHECKSUM, &known).verdict,
            Verdict::NotAnApk
        );
    }

    /// Design 140 §6 (Q1 (a)): a Vibb One that names a newer server makes no QR (orange, its short line and the fix
    /// behind the (i)); one that names this server works; one past the first tag with no value is no readable Vibb
    /// One.
    #[test]
    fn a_launcher_that_needs_a_newer_server_makes_no_qr() {
        use crate::apk::MinServer;
        let config = {
            let mut config = ForkConfig::for_tests();
            config.launcher_admin_component = "me.vibb.testfixture/x.Admin".to_string();
            config
        };
        let known = Known {
            own_server: Some((0, 95, 0)),
            ..Known::default()
        };
        let with = |min_server, code| ApkIdentity {
            min_server,
            version_code: code,
            ..crate::apk::read_identity(std::path::Path::new("testdata/apk/fixture-key1.apk"))
                .unwrap()
        };
        let needs = judge(
            &with(MinServer::Version((0, 96, 0)), Some(1)),
            &config,
            KEY1_CHECKSUM,
            &known,
        );
        assert_eq!(
            needs.verdict,
            Verdict::NeedsServer {
                version: "1".to_string(),
                server: "0.96.0".to_string()
            }
        );
        assert!(needs.verdict.blocks_qr());
        let (state, text, sub, fix) =
            words(Lang::En, &Zone::utc_fallback(), &needs, "https://x/y.apk");
        assert_eq!(state, "needs_server");
        assert_eq!(text, "Vibb One in the QR code needs server 0.96.0");
        assert_eq!(
            sub.as_deref(),
            Some("No QR code until the server is updated.")
        );
        assert_eq!(
            fix.as_deref(),
            Some(
                "Update this server to 0.96.0 or newer, or point LAUNCHER_APK_URL at an older release."
            )
        );
        let line = Line {
            state,
            text,
            sub,
            fix,
            needs: None,
            device_id: None,
            auto_reload: false,
        };
        assert!(line.blocks_qr());
        assert_eq!(
            judge(
                &with(MinServer::Version((0, 95, 0)), Some(1)),
                &config,
                KEY1_CHECKSUM,
                &known
            )
            .verdict,
            Verdict::Works
        );
        assert_eq!(
            judge(
                &with(
                    MinServer::Absent,
                    Some(crate::catalog_holds::LAUNCHER_MIN_SERVER_FIRST_CODE)
                ),
                &config,
                KEY1_CHECKSUM,
                &known
            )
            .verdict,
            Verdict::NotAnApk
        );
        assert_eq!(
            judge(
                &with(MinServer::Unreadable, Some(1)),
                &config,
                KEY1_CHECKSUM,
                &known
            )
            .verdict,
            Verdict::NotAnApk
        );
        // Before the first tag that carries it: as today.
        assert_eq!(
            judge(
                &with(MinServer::Absent, Some(1)),
                &config,
                KEY1_CHECKSUM,
                &known
            )
            .verdict,
            Verdict::Works
        );
        // Ranked after the signer: a wrong key is still said first.
        assert_eq!(
            judge(
                &with(MinServer::Version((0, 96, 0)), Some(1)),
                &config,
                KEY2_CHECKSUM,
                &known
            )
            .verdict,
            Verdict::WrongSigner
        );
    }

    /// Design 140 §6 (QA M4, code QA L2): a result older than [FRESH_FOR] is compared with GitHub's
    /// `releases/latest` - another one forgets it (the page reads "Checking", no QR); the same one, a failed list read, a result younger
    /// than [FRESH_FOR] or an address that isn't `releases/latest` keep it. The list is read at most once per
    /// [FRESH_FOR].
    #[tokio::test]
    async fn a_moved_latest_release_is_checked_again() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        let reads = std::sync::Arc::new(AtomicUsize::new(0));
        let stable = std::sync::Arc::new(std::sync::Mutex::new("launcher-v0.43.0".to_string()));
        let router = {
            let (reads, stable) = (reads.clone(), stable.clone());
            axum::Router::new().route(
                "/repos/palchrb/vibb-launcher/releases/latest",
                axum::routing::get(move || {
                    reads.fetch_add(1, Ordering::SeqCst);
                    let stable = stable.lock().unwrap().clone();
                    async move { axum::Json(serde_json::json!({"tag_name": stable})) }
                }),
            )
        };
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
        let mut config = ForkConfig::for_tests();
        config.launcher_apk_url =
            "https://github.com/palchrb/vibb-launcher/releases/latest/download/kids-launcher-mdm.apk"
                .to_string();
        config.launcher_signature_checksum = Some(KEY1_CHECKSUM.to_string());
        config.github_api = base;
        let app = TestApp::with_config(config.clone()).await;
        let state = &app.state;
        let result = |version: &str| Checked {
            verdict: Verdict::Works,
            version: Some(version.to_string()),
            published: None,
            min_sdk: None,
        };
        let has_result = || state.launcher_check.0.lock().unwrap().result.is_some();

        // Younger than FRESH_FOR: no list read.
        state.launcher_check.set(&config, result("0.42.0"));
        refresh_if_moved(state).await;
        assert!(has_result());
        assert_eq!(reads.load(Ordering::SeqCst), 0);
        // Older, the same release: it stands, and the list isn't read again for FRESH_FOR.
        state.launcher_check.set(&config, result("0.43.0"));
        state.launcher_check.age(FRESH_FOR + Duration::from_secs(1));
        refresh_if_moved(state).await;
        assert!(has_result());
        assert_eq!(reads.load(Ordering::SeqCst), 1);
        refresh_if_moved(state).await;
        assert_eq!(reads.load(Ordering::SeqCst), 1);
        // A newer stable release out: forgotten - the next look checks it, the page reads "Checking".
        *stable.lock().unwrap() = "launcher-v0.44.0".to_string();
        state.launcher_check.age(FRESH_FOR + Duration::from_secs(1));
        refresh_if_moved(state).await;
        assert_eq!(reads.load(Ordering::SeqCst), 2);
        assert!(!has_result());

        // A failed list read keeps the result.
        let mut down = config.clone();
        down.github_api = "http://127.0.0.1:9".to_string();
        let down_app = TestApp::with_config(down.clone()).await;
        down_app.state.launcher_check.set(&down, result("0.40.0"));
        down_app
            .state
            .launcher_check
            .age(FRESH_FOR + Duration::from_secs(1));
        refresh_if_moved(&down_app.state).await;
        assert!(
            down_app
                .state
                .launcher_check
                .0
                .lock()
                .unwrap()
                .result
                .is_some()
        );

        // A release's own address never moves: no list read.
        let mut pinned = config.clone();
        pinned.launcher_apk_url =
            "https://github.com/palchrb/vibb-launcher/releases/download/launcher-v0.40.0/kids-launcher-mdm.apk"
                .to_string();
        let pinned_app = TestApp::with_config(pinned.clone()).await;
        pinned_app
            .state
            .launcher_check
            .set(&pinned, result("0.40.0"));
        pinned_app
            .state
            .launcher_check
            .age(FRESH_FOR + Duration::from_secs(1));
        let before = reads.load(Ordering::SeqCst);
        refresh_if_moved(&pinned_app.state).await;
        assert!(
            pinned_app
                .state
                .launcher_check
                .0
                .lock()
                .unwrap()
                .result
                .is_some()
        );
        assert_eq!(reads.load(Ordering::SeqCst), before);
    }
}
