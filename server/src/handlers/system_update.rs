//! Lets an admin trigger an app update or restart entirely from the UI, and
//! optionally schedule automatic update checks.
//!
//! This process itself never touches its own binary or calls anything
//! privileged - it only ever writes one word ("update" or "restart") into
//! `data/update_requested`, a file inside its own already-writable data
//! directory. A completely separate, root-owned systemd path unit
//! (installed by deploy/install.sh, living outside this app's install
//! directory entirely) watches for that file and does the actual privileged
//! work. Even a fully compromised app process can only ever *request* one of
//! two fixed actions - it can never reach or modify the privileged side.
//!
//! The page this used to render on its own now lives combined with the
//! OS/Tailscale page at `/updates` - see `handlers::updates` - so every
//! handler below hands off to that shared renderer instead of rendering its
//! own template.

use axum::Extension;
use axum::extract::State;
use axum::response::{Html, IntoResponse};
use serde::Deserialize;
use std::time::Duration;

use crate::AppState;
use crate::config::SERVER_RELEASE_TAG_PREFIX;
use crate::handlers::updates;
use crate::security::{self, CurrentAdmin};

const FLAG_FILE: &str = "data/update_requested";
const SCHEDULE_FILE: &str = "data/app_update_schedule.conf";
const LAST_CHECK_FILE: &str = "data/app_update_last_check";

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
/// release deleted) would refuse to start on a database the running version already migrated.
/// Going back is a deliberate step over SSH (`KPS_ALLOW_DOWNGRADE=1`, DEPLOY.md).
fn is_newer(latest: &str, current: &str) -> bool {
    matches!((parse_version(latest), parse_version(current)), (Some(l), Some(c)) if l > c)
}

/// Why an update request must not be written right now, if it mustn't: the root-side scripts
/// are older than this app needs. A pre-monorepo `actions.sh` would fetch the old repo's
/// update.sh and install the old repo's last release over a database this version migrated.
async fn update_blocked_reason(state: &AppState) -> Option<String> {
    security::watcher_needs_update().await.then(|| {
        format!(
            "Not updating: the system helper scripts on this Pi are too old to update this \
             version safely. Re-run the installer over SSH first (it keeps your settings and \
             data): {}",
            security::reinstall_hint(&state.config.server_release_repo)
        )
    })
}

/// `repo` is `config::ForkConfig::server_release_repo` (`owner/repo`).
async fn latest_release_tag(repo: &str) -> Option<String> {
    let client = reqwest::Client::builder()
        .user_agent("kid-phone-server (self-hosted, github.com)")
        .timeout(Duration::from_secs(8))
        .build()
        .ok()?;
    let url = format!("https://api.github.com/repos/{repo}/releases?per_page=100");
    let response = client.get(url).send().await.ok()?;
    if !response.status().is_success() {
        return None;
    }
    let releases = response.json::<Vec<Release>>().await.ok()?;
    newest_server_version(&releases)
}

#[derive(Clone)]
pub(crate) struct AppUpdateScheduleConfig {
    pub(crate) frequency: String,
    pub(crate) day_of_week: String,
    pub(crate) day_of_month: String,
    pub(crate) check_time: String,
    pub(crate) auto_install_enabled: bool,
}

impl AppUpdateScheduleConfig {
    fn defaults() -> Self {
        AppUpdateScheduleConfig {
            frequency: "daily".to_string(),
            day_of_week: "0".to_string(),
            day_of_month: "1".to_string(),
            check_time: "04:00".to_string(),
            auto_install_enabled: false,
        }
    }

    async fn load() -> Self {
        let Ok(contents) = tokio::fs::read_to_string(SCHEDULE_FILE).await else {
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
                "CHECK_TIME" => config.check_time = value,
                "AUTO_INSTALL_ENABLED" => config.auto_install_enabled = value == "true",
                _ => {}
            }
        }
        config
    }

    fn to_file_contents(&self) -> String {
        format!(
            "FREQUENCY={}\nDAY_OF_WEEK={}\nDAY_OF_MONTH={}\nCHECK_TIME={}\nAUTO_INSTALL_ENABLED={}\n",
            self.frequency,
            self.day_of_week,
            self.day_of_month,
            self.check_time,
            self.auto_install_enabled,
        )
    }

    /// Plain-language readout of what's actually configured right now, same
    /// idea as BackupScheduleConfig::summary in handlers/backups.rs.
    fn summary(&self) -> String {
        let when = match self.frequency.as_str() {
            "weekly" => format!(
                "every {} at {}",
                crate::handlers::backups::day_of_week_name(&self.day_of_week),
                self.check_time
            ),
            "monthly" => format!(
                "on day {} of the month at {}",
                self.day_of_month, self.check_time
            ),
            _ => format!("daily at {}", self.check_time),
        };
        let auto_install = if self.auto_install_enabled {
            "on"
        } else {
            "off"
        };
        format!("Checking for a new version {when}. Auto-install: {auto_install}.")
    }
}

/// Everything the combined updates page needs to know about the app's own
/// version/self-update state - gathered here, rendered by `handlers::updates`.
pub(crate) struct AppUpdateData {
    pub(crate) current_version: String,
    pub(crate) latest_version: Option<String>,
    pub(crate) update_available: bool,
    pub(crate) check_failed: bool,
    pub(crate) watcher_version: Option<String>,
    pub(crate) watcher_needs_update: bool,
    pub(crate) reinstall_hint: String,
    pub(crate) schedule: AppUpdateScheduleConfig,
    pub(crate) schedule_summary: String,
}

pub(crate) async fn gather(state: &AppState) -> AppUpdateData {
    let current_version = crate::APP_VERSION.to_string();
    let latest_version = latest_release_tag(&state.config.server_release_repo).await;
    let check_failed = latest_version.is_none();
    let update_available = latest_version
        .as_deref()
        .is_some_and(|latest| is_newer(latest, &current_version));
    let schedule = AppUpdateScheduleConfig::load().await;
    let schedule_summary = schedule.summary();

    AppUpdateData {
        current_version,
        latest_version,
        update_available,
        check_failed,
        watcher_version: security::installed_watcher_version().await,
        watcher_needs_update: security::watcher_needs_update().await,
        reinstall_hint: security::reinstall_hint(&state.config.server_release_repo),
        schedule,
        schedule_summary,
    }
}

async fn request_action(
    state: &AppState,
    admin: &crate::models::AdminUser,
    action: &str,
    event_type: &str,
    started_message: &str,
) -> Html<String> {
    let message = if tokio::fs::write(FLAG_FILE, action).await.is_ok() {
        security::record_security_event(&state.db, event_type, Some(&admin.username), None, None)
            .await;
        started_message.to_string()
    } else {
        "Couldn't write the request file - the update watcher may not be set up on this install."
            .to_string()
    };
    updates::render_page(state, Some(message)).await
}

pub async fn trigger_update(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> impl IntoResponse {
    if let Some(reason) = update_blocked_reason(&state).await {
        return updates::render_page(&state, Some(reason)).await;
    }
    // A failed check doesn't block the button (update.sh refuses a downgrade on its own), but a
    // known release that isn't newer does.
    if let Some(latest) = latest_release_tag(&state.config.server_release_repo).await
        && !is_newer(&latest, crate::APP_VERSION)
    {
        return updates::render_page(
            &state,
            Some(format!(
                "Not updating: the newest release ({latest}) isn't newer than this one ({}).",
                crate::APP_VERSION
            )),
        )
        .await;
    }
    request_action(
        &state,
        &admin,
        "update",
        "system_update_triggered",
        "Update started. The app will download the new version and restart automatically - \
         this can take about a minute. Refresh this page shortly.",
    )
    .await
}

pub async fn trigger_restart(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> impl IntoResponse {
    request_action(
        &state,
        &admin,
        "restart",
        "system_restart_triggered",
        "Restarting now. Refresh this page in a few seconds.",
    )
    .await
}

#[derive(Deserialize)]
pub struct AppUpdateScheduleForm {
    frequency: String,
    day_of_week: String,
    day_of_month: String,
    check_time: String,
    auto_install_enabled: Option<String>,
}

const VALID_FREQUENCIES: [&str; 3] = ["daily", "weekly", "monthly"];

pub async fn save_app_update_schedule(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    axum::Form(form): axum::Form<AppUpdateScheduleForm>,
) -> impl IntoResponse {
    let frequency = if VALID_FREQUENCIES.contains(&form.frequency.as_str()) {
        form.frequency
    } else {
        "daily".to_string()
    };
    let day_of_week: i32 = form.day_of_week.trim().parse().unwrap_or(0).clamp(0, 6);
    let day_of_month: i32 = form.day_of_month.trim().parse().unwrap_or(1).clamp(1, 28);
    let check_time = if form.check_time.trim().is_empty() {
        "04:00".to_string()
    } else {
        form.check_time.trim().to_string()
    };

    let config = AppUpdateScheduleConfig {
        frequency,
        day_of_week: day_of_week.to_string(),
        day_of_month: day_of_month.to_string(),
        check_time,
        auto_install_enabled: form.auto_install_enabled.is_some(),
    };

    let message = if tokio::fs::write(SCHEDULE_FILE, config.to_file_contents())
        .await
        .is_ok()
    {
        security::record_security_event(
            &state.db,
            "app_update_schedule_changed",
            Some(&admin.username),
            None,
            None,
        )
        .await;
        "Update-check schedule saved.".to_string()
    } else {
        "Couldn't save the schedule.".to_string()
    };

    updates::render_page(&state, Some(message)).await
}

/// Background task: checks for a new app release on the configured
/// interval and, if auto-install is enabled and a new version is found,
/// triggers the same "update" flag-file request the manual button uses.
/// Like the button, it never asks while the watcher is too old
/// (`update_blocked_reason`) and only for a strictly newer version.
pub async fn run_scheduled_app_update_check(state: AppState) {
    let mut interval = tokio::time::interval(Duration::from_secs(5 * 60));
    loop {
        interval.tick().await;

        let schedule = AppUpdateScheduleConfig::load().await;
        if !schedule.auto_install_enabled {
            continue;
        }
        if !is_due(&schedule).await {
            continue;
        }

        let today = chrono::Local::now().format("%Y-%m-%d").to_string();
        if tokio::fs::write(LAST_CHECK_FILE, &today).await.is_err() {
            tracing::warn!("couldn't write app-update last-check marker, skipping this cycle");
            continue;
        }

        if let Some(reason) = update_blocked_reason(&state).await {
            tracing::warn!("auto-update skipped: {reason}");
            continue;
        }
        let Some(latest) = latest_release_tag(&state.config.server_release_repo).await else {
            continue;
        };
        if is_newer(&latest, crate::APP_VERSION) {
            tracing::info!("auto-update: new version {latest} found, triggering update");
            let _ = tokio::fs::write(FLAG_FILE, "update").await;
        }
    }
}

/// Mirrors the same daily/weekly/monthly + time-window + once-per-day-guard
/// logic as the backup scheduler's `is_due` in handlers/backups.rs.
async fn is_due(schedule: &AppUpdateScheduleConfig) -> bool {
    let today = chrono::Local::now().format("%Y-%m-%d").to_string();
    let last_check = tokio::fs::read_to_string(LAST_CHECK_FILE)
        .await
        .unwrap_or_default();
    if last_check.trim() == today {
        return false;
    }

    use chrono::{Datelike, Timelike};
    let now = chrono::Local::now();
    match schedule.frequency.as_str() {
        "weekly" => {
            let configured: u32 = schedule.day_of_week.parse().unwrap_or(0);
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

    let Some((hour_str, minute_str)) = schedule.check_time.split_once(':') else {
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
