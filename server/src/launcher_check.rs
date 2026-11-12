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
/// The whole check - the download and the release list - at most.
const CHECK_TIMEOUT: Duration = Duration::from_secs(10 * 60);
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
    /// A file came, but not an APK with a manifest.
    NotAnApk,
    /// Nothing came: DNS, refused, an HTTP error, a timeout, a stall, over the size cap, no ZIP.
    Unreachable,
}

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

    /// Stores a finished check's result for `key`, unless the key changed meanwhile.
    fn store(&self, key: &Key, checked: Checked) {
        let mut check = self.0.lock().unwrap();
        check.running = false;
        if check.key.as_ref() == Some(key) {
            let wait = if checked.verdict == Verdict::Unreachable {
                RETRY_AFTER
            } else {
                RESULT_MAX_AGE
            };
            check.due = Some(Instant::now() + wait);
            check.result = Some(checked);
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
            };
        }
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

/// At start: removes a temp file a crash left behind, and starts the first check.
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
    let checked = tokio::time::timeout(CHECK_TIMEOUT, check(state, &key.0, &key.1))
        .await
        .unwrap_or_else(|_| {
            tracing::warn!(
                "the setup QR's launcher check took over {} s - stopped",
                CHECK_TIMEOUT.as_secs()
            );
            Checked {
                verdict: Verdict::Unreachable,
                version: None,
                published: None,
                min_sdk: None,
            }
        });
    state.launcher_check.store(&key, checked);
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
    stream_to_file(&mut response, path, 0, None, DOWNLOAD_STALL_TIMEOUT)
        .await
        .map(|_| ())
        .map_err(|err| err.to_string())
}

/// The launcher releases the server knows: the newest (its version and versionCode) and each
/// one's publishing time by versionCode.
#[derive(Debug, Default, PartialEq)]
pub(crate) struct Known {
    pub(crate) newest: Option<(String, i64)>,
    pub(crate) published: Vec<(i64, DateTime<Utc>)>,
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
    let mut known = Known::default();
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
    /// "checking", "works", "older" or "refused".
    pub state: &'static str,
    pub text: String,
    /// What to do, under the row.
    pub sub: Option<String>,
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
        matches!(self.state, "checking" | "refused")
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
    let (state_word, text, sub) = match seen(state) {
        Seen::Off => return None,
        Seen::Checking => (
            "checking",
            t(lang, "settings.provisioning.launcher.checking").to_string(),
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

/// A result's state, row and what to do, in `lang`.
fn words(
    lang: Lang,
    zone: &Zone,
    checked: &Checked,
    url: &str,
) -> (&'static str, String, Option<String>) {
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
            ("works", text, None)
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
            let sub = t_params(
                lang,
                "settings.provisioning.launcher.older_sub",
                &[("newest", newest)],
            );
            ("older", text, Some(sub))
        }
        Verdict::WrongSigner => (
            "refused",
            t(lang, "settings.provisioning.launcher.wrong_signer").to_string(),
            Some(t(lang, "settings.provisioning.launcher.wrong_signer_sub").to_string()),
        ),
        Verdict::WrongPackage { package } => (
            "refused",
            t_params(
                lang,
                "settings.provisioning.launcher.wrong_package",
                &[("package", package)],
            ),
            Some(t(lang, "settings.provisioning.launcher.wrong_package_sub").to_string()),
        ),
        Verdict::NotAnApk => (
            "refused",
            t(lang, "settings.provisioning.launcher.not_an_apk").to_string(),
            Some(t_params(
                lang,
                "settings.provisioning.launcher.address_sub",
                &[("url", url)],
            )),
        ),
        Verdict::Unreachable => (
            "refused",
            t(lang, "settings.provisioning.launcher.unreachable").to_string(),
            Some(t_params(
                lang,
                "settings.provisioning.launcher.address_sub",
                &[("url", url)],
            )),
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
    /// releases of `palchrb/vibb-launcher` - 0.0.1 (code 1, the fixtures' own) and the newer
    /// prerelease 0.0.2 (code 2).
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
                        {"tag_name": "launcher-v0.0.3", "draft": true, "prerelease": false},
                        {"tag_name": "server-v9.0.0", "prerelease": false,
                         "published_at": "2026-11-01T10:00:00Z"},
                        {"tag_name": "launcher-v0.0.2", "prerelease": true,
                         "published_at": "2026-11-10T10:00:00Z"},
                        {"tag_name": "launcher-v0.0.1", "prerelease": false,
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
                    newest: "0.0.2".to_string()
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
            Verdict::Unreachable,
            "a page is no APK: the download refuses it"
        );
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
    /// `launcher-v0.0.2` (code 2) makes the code-1 launcher older.
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
            newest: Some(("0.0.2".to_string(), 2)),
            published: Vec::new(),
        };
        assert_eq!(
            judge(&identity, &config, KEY1_CHECKSUM, &known).verdict,
            Verdict::Older {
                newest: "0.0.2".to_string()
            }
        );
        assert_eq!(
            judge(&identity, &config, KEY1_CHECKSUM, &Known::default()).verdict,
            Verdict::Works
        );
        let no_package = ApkIdentity {
            package: None,
            ..identity
        };
        assert_eq!(
            judge(&no_package, &config, KEY1_CHECKSUM, &known).verdict,
            Verdict::NotAnApk
        );
    }
}
