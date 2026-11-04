//! The server's own version and the newest `server-v*` release, for Settings › Software Updates
//! (`handlers::updates`). Read-only: the server never updates itself or anything else on its host
//! (the user, 2026-11-04) - the page says how the owner does it.

use serde::Deserialize;
use std::time::{Duration, Instant};

use crate::AppState;
use crate::config::SERVER_RELEASE_TAG_PREFIX;

#[derive(Deserialize)]
struct Release {
    tag_name: String,
    #[serde(default)]
    prerelease: bool,
    #[serde(default)]
    draft: bool,
}

/// The repo is a monorepo that also publishes the launcher (`launcher-v*`, and those are the
/// ones GitHub marks "latest"), so this reads the release *list* and takes the newest stable
/// `server-vX.Y.Z` - never `/releases/latest`. Returns it as `vX.Y.Z`, the form of
/// `crate::APP_VERSION`.
fn newest_server_version(releases: &[Release]) -> Option<String> {
    releases
        .iter()
        .filter(|r| !r.draft && !r.prerelease)
        .find_map(|r| {
            let version = r.tag_name.strip_prefix(SERVER_RELEASE_TAG_PREFIX)?;
            let mut parts = version.split('.');
            let numeric = parts
                .by_ref()
                .take(3)
                .filter(|p| !p.is_empty() && p.bytes().all(|b| b.is_ascii_digit()))
                .count()
                == 3
                && parts.next().is_none();
            numeric.then(|| format!("v{version}"))
        })
}

/// `vX.Y.Z` (or `X.Y.Z`) as a comparable triple; anything else is `None`.
fn parse_version(version: &str) -> Option<(u64, u64, u64)> {
    let mut parts = version.strip_prefix('v').unwrap_or(version).split('.');
    let mut next = || -> Option<u64> {
        let p = parts.next()?;
        if p.is_empty() || !p.bytes().all(|b| b.is_ascii_digit()) {
            return None;
        }
        p.parse().ok()
    };
    let triple = (next()?, next()?, next()?);
    parts.next().is_none().then_some(triple)
}

/// Only a strictly newer release is an update: an older one (a backport tag, or the newest
/// release deleted) would refuse to start on a database the running version already migrated
/// (`update.sh` refuses it too; going back is `KPS_ALLOW_DOWNGRADE=1`, DEPLOY.md).
fn is_newer(latest: &str, current: &str) -> bool {
    matches!((parse_version(latest), parse_version(current)), (Some(l), Some(c)) if l > c)
}

/// `repo` is `config::ForkConfig::server_release_repo` (`owner/repo`).
async fn latest_release_tag(config: &crate::config::ForkConfig) -> Option<String> {
    let client = reqwest::Client::builder()
        .user_agent(crate::net::USER_AGENT)
        .timeout(Duration::from_secs(8))
        .build()
        .ok()?;
    let url = format!(
        "{}/repos/{}/releases?per_page=100",
        config.github_api, config.server_release_repo
    );
    let response = client.get(url).send().await.ok()?;
    if !response.status().is_success() {
        return None;
    }
    let releases = response.json::<Vec<Release>>().await.ok()?;
    newest_server_version(&releases)
}

/// How long the newest release read from GitHub stands before a page reads it again, and how soon
/// after a failed read.
const RELEASE_MAX_AGE: Duration = Duration::from_secs(60 * 60);
const RELEASE_RETRY: Duration = Duration::from_secs(5 * 60);

/// The newest server release, in memory (`AppState::server_release`): Software Updates shows it
/// and never waits on GitHub (2026-11-03: the live read made the page ~140 ms slower). Read at
/// start, and in the background when a page finds it older than [RELEASE_MAX_AGE].
#[derive(Default)]
pub struct ServerRelease(std::sync::Mutex<ReleaseState>);

#[derive(Default)]
struct ReleaseState {
    /// The last tag a read found; a failed read keeps it.
    latest: Option<String>,
    /// Once a read has ended: when the next is due, and whether that one failed.
    read: Option<(Instant, bool)>,
    refreshing: bool,
}

impl ServerRelease {
    /// What a page shows - the last good tag and how the last read ended (`None`: none has yet) -
    /// and whether it should start a read (one is due and none is running).
    fn seen(&self) -> (Option<String>, Option<bool>, bool) {
        let mut release = self.0.lock().unwrap();
        let due = release.read.is_none_or(|(due, _)| Instant::now() >= due);
        let start = due && !std::mem::replace(&mut release.refreshing, true);
        (release.latest.clone(), release.read.map(|r| r.1), start)
    }

    #[cfg(test)]
    pub(crate) fn set(&self, latest: Option<&str>, due: bool, failed: bool) {
        let next = Instant::now() + if due { Duration::ZERO } else { RELEASE_MAX_AGE };
        *self.0.lock().unwrap() = ReleaseState {
            latest: latest.map(str::to_string),
            read: Some((next, failed)),
            refreshing: false,
        };
    }

    #[cfg(test)]
    pub(crate) fn latest(&self) -> (Option<String>, bool) {
        let release = self.0.lock().unwrap();
        (release.latest.clone(), release.refreshing)
    }
}

/// Reads the newest release from GitHub into [ServerRelease] and returns what the read found.
pub(crate) async fn refresh_release(state: &AppState) -> Option<String> {
    let found = latest_release_tag(&state.config).await;
    let mut release = state.server_release.0.lock().unwrap();
    if found.is_some() {
        release.latest.clone_from(&found);
    }
    let wait = if found.is_some() {
        RELEASE_MAX_AGE
    } else {
        RELEASE_RETRY
    };
    release.read = Some((Instant::now() + wait, found.is_none()));
    release.refreshing = false;
    found
}

/// What Software Updates shows about the server's version - gathered here, rendered by
/// `handlers::updates`.
pub(crate) struct AppUpdateData {
    pub(crate) current_version: String,
    pub(crate) latest_version: Option<String>,
    pub(crate) update_available: bool,
    pub(crate) check_failed: bool,
    /// No read of GitHub has ended since the server started: the page says it's checking.
    pub(crate) checking: bool,
}

pub(crate) async fn gather(state: &AppState) -> AppUpdateData {
    let current_version = crate::APP_VERSION.to_string();
    let (latest_version, last_read_failed, start) = state.server_release.seen();
    if start {
        let state = state.clone();
        tokio::spawn(async move { refresh_release(&state).await });
    }
    let checking = last_read_failed.is_none();
    let check_failed = last_read_failed == Some(true) && latest_version.is_none();
    let update_available = latest_version
        .as_deref()
        .is_some_and(|latest| is_newer(latest, &current_version));

    AppUpdateData {
        current_version,
        latest_version,
        update_available,
        check_failed,
        checking,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn release(tag: &str, prerelease: bool, draft: bool) -> Release {
        Release {
            tag_name: tag.to_string(),
            prerelease,
            draft,
        }
    }

    #[test]
    fn only_stable_server_releases_count() {
        let releases = [
            release("launcher-v0.30.0", false, false),
            release("server-v0.20.0", false, true),
            release("server-v0.19.0", true, false),
            release("server-v0.19.0-rc.1", false, false),
            release("v0.18.9", false, false),
            release("server-v0.18.7", false, false),
            release("server-v0.18.6", false, false),
        ];
        assert_eq!(newest_server_version(&releases).as_deref(), Some("v0.18.7"));
        assert_eq!(
            newest_server_version(&[release("launcher-v1.0.0", false, false)]),
            None
        );
    }

    #[test]
    fn only_a_strictly_newer_version_is_an_update() {
        assert!(is_newer("v0.18.7", "v0.18.6"));
        assert!(is_newer("v0.19.0", "v0.18.10"));
        assert!(is_newer("v1.0.0", "v0.99.99"));
        assert!(!is_newer("v0.18.6", "v0.18.6"));
        assert!(!is_newer("v0.18.5", "v0.18.6"));
        assert!(is_newer("v0.10.0", "v0.9.0"));
        assert!(!is_newer("garbage", "v0.18.6"));
        assert!(!is_newer("v0.19.0", "dev"));
        assert!(!is_newer("v0.19.0.1", "v0.18.6"));
    }
}
