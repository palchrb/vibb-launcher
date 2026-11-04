//! OS-level and Tailscale update management, plus scheduling - parallel to
//! (but separate from) the app's own self-update logic in
//! handlers::system_update. Both are rendered together on one combined page
//! by handlers::updates.
//!
//! Read-only status checks (apt list --upgradable, tailscale version) run
//! directly in this unprivileged process - they don't touch anything and
//! don't need root. The page never waits on them: [OsChecks] keeps their
//! results in memory and reads them in the background (6 h; 5 min after a
//! failure). Anything that actually changes the system (installing
//! packages, updating Tailscale, rebooting) goes through the same
//! flag-file + root-owned watcher pattern as the app's own update button;
//! see deploy/install.sh's watcher.sh for the privileged side.
//!
//! Scheduling is handled entirely by a separate root-owned timer
//! (kid-phone-server-scheduler.timer) that polls a plain config file this
//! handler writes - the schedule itself never needs to go through the
//! flag-file mechanism, since that indirection exists only to let this
//! unprivileged, internet-facing process request privileged actions.

use axum::Extension;
use axum::extract::State;
use axum::response::{IntoResponse, Response};
use axum::{Form, http};
use serde::Deserialize;
use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;
use std::time::{Duration, Instant};

use crate::AppState;
use crate::handlers::settings::Flash;
use crate::handlers::updates;
use crate::i18n::Lang;
use crate::security::{self, CurrentAdmin};

/// In `AppState::data_dir`.
const SCHEDULE_FILE: &str = "schedule.conf";
#[cfg_attr(test, allow(dead_code))]
const SUBPROCESS_TIMEOUT: Duration = Duration::from_secs(5);

/// How long `apt list --upgradable` and `tailscale version --upstream` stand before a page starts a
/// read again (in the background), and how soon after a read that failed.
const CHECK_MAX_AGE: Duration = Duration::from_secs(6 * 60 * 60);
const CHECK_RETRY: Duration = Duration::from_secs(5 * 60);

/// Runs a command with a timeout - a stuck subprocess (e.g. `apt` blocked
/// waiting on the dpkg lock while an upgrade is still running) must never be
/// able to hang a read indefinitely. `watcher_busy` below is the primary
/// defense (skips calling apt at all when an update is known to be running);
/// this timeout is the backstop for cases that check doesn't catch.
#[cfg_attr(test, allow(dead_code))]
async fn run(cmd: &str, args: &[&str]) -> Option<String> {
    let child = tokio::process::Command::new(cmd).args(args).output();
    let output = tokio::time::timeout(SUBPROCESS_TIMEOUT, child)
        .await
        .ok()?
        .ok()?;
    Some(String::from_utf8_lossy(&output.stdout).into_owned())
}

/// What runs a command and returns its output (`None`: it didn't run or ran out of time); the
/// tests give [OsChecks] a canned one.
type Runner = Arc<
    dyn Fn(
            &'static str,
            &'static [&'static str],
        ) -> Pin<Box<dyn Future<Output = Option<String>> + Send>>
        + Send
        + Sync,
>;

/// True while the root-owned watcher is actively running a requested action
/// (an OS upgrade in particular can take a long time on a Pi's first run).
/// Querying another unit's status is a read-only systemd operation any local
/// user can do - no privilege needed, unlike actually controlling the unit.
async fn watcher_busy() -> bool {
    tokio::process::Command::new("systemctl")
        .args(["is-active", "--quiet", "kid-phone-server-updater.service"])
        .status()
        .await
        .map(|s| s.success())
        .unwrap_or(false)
}

/// One result kept in memory: the last good value (a failed read keeps it), how the last read
/// ended, and whether one is running. The same rule as the release tag's
/// (`system_update::ServerRelease`), with the clock given so a test can pass it.
struct CheckCache<T>(std::sync::Mutex<CheckState<T>>);

struct CheckState<T> {
    value: Option<T>,
    /// Once a read has ended: when the next is due, and whether that one failed.
    read: Option<(Instant, bool)>,
    refreshing: bool,
}

impl<T> Default for CheckCache<T> {
    fn default() -> Self {
        CheckCache(std::sync::Mutex::new(CheckState {
            value: None,
            read: None,
            refreshing: false,
        }))
    }
}

/// What a page shows from a [CheckCache].
struct Seen<T> {
    /// The last good value.
    value: Option<T>,
    /// No read has ended since the server started: the page says it is checking.
    checking: bool,
    /// The last read failed (`value` may still be an older good one); the page tells a failure
    /// from a value by `value` alone, the tests read this.
    #[cfg_attr(not(test), allow(dead_code))]
    failed: bool,
    /// A read is due and none is running: the caller starts it, and is the only one that does.
    start: bool,
}

impl<T: Clone> CheckCache<T> {
    fn seen(&self, now: Instant) -> Seen<T> {
        let mut state = self.0.lock().unwrap();
        let due = state.read.is_none_or(|(due, _)| now >= due);
        let start = due && !std::mem::replace(&mut state.refreshing, true);
        Seen {
            value: state.value.clone(),
            checking: state.read.is_none(),
            failed: state.read.is_some_and(|(_, failed)| failed),
            start,
        }
    }

    /// Claims a read for a caller that starts one itself (a false answer: one is already running).
    fn claim(&self) -> bool {
        !std::mem::replace(&mut self.0.lock().unwrap().refreshing, true)
    }

    /// A read ended at `now`: `store` is the value to keep (`None` keeps the old one), `ok` whether
    /// it found what it looked for - the next is due in [CHECK_MAX_AGE], or [CHECK_RETRY] if not.
    fn finish(&self, now: Instant, store: Option<T>, ok: bool) {
        let mut state = self.0.lock().unwrap();
        if store.is_some() {
            state.value = store;
        }
        let wait = if ok { CHECK_MAX_AGE } else { CHECK_RETRY };
        state.read = Some((now + wait, !ok));
        state.refreshing = false;
    }
}

/// `tailscale version` and what `--upstream` found (`None`: it couldn't ask pkgs.tailscale.com).
#[derive(Clone, Debug, PartialEq, Eq)]
struct TailscaleInfo {
    current: String,
    upstream: Option<String>,
}

/// `--upstream` prints the same version block as plain `tailscale version` (commit hash, long
/// version, go version, ...) with an extra "upstream: X" field appended somewhere in it -
/// comparing the whole blob against just current's first line always looked like an update was
/// available, even when up to date. Pull out just that field's value via substring search rather
/// than assuming it's on its own line, since it's unclear whether tailscale's output actually has
/// a newline there or just a space.
fn parse_upstream(output: &str) -> Option<String> {
    output
        .split("upstream:")
        .nth(1)
        .and_then(|rest| rest.split_whitespace().next())
        .map(str::to_string)
}

/// What a finished Tailscale read leaves in the cache: the value to store (`None` keeps the old)
/// and whether the read found everything. A read that found the version but not the upstream one
/// never replaces an earlier read that had both, and is never shown as "up to date".
fn tailscale_store(
    old: Option<&TailscaleInfo>,
    found: Option<TailscaleInfo>,
) -> (Option<TailscaleInfo>, bool) {
    match found {
        Some(info) if info.upstream.is_some() => (Some(info), true),
        Some(info) if old.is_none_or(|old| old.upstream.is_none()) => (Some(info), false),
        _ => (None, false),
    }
}

/// The apt and Tailscale checks, in memory (`AppState::os_checks`): Software Updates shows them
/// and never waits on `apt` or on pkgs.tailscale.com (2026-11-04: the page ran both on every
/// visit). Read at start, in the background when a page finds one older than [CHECK_MAX_AGE]
/// (the first page after a start says "checking"), and after the Check for Updates, Install
/// Updates and Update Tailscale buttons once the root helper is done.
pub struct OsChecks {
    apt: CheckCache<usize>,
    tailscale: CheckCache<TailscaleInfo>,
    runner: Runner,
}

impl Default for OsChecks {
    fn default() -> Self {
        // The tests run no apt or tailscale of their own.
        #[cfg(test)]
        let runner: Runner = Arc::new(|_, _| Box::pin(async { None }));
        #[cfg(not(test))]
        let runner: Runner = Arc::new(|cmd, args| Box::pin(async move { run(cmd, args).await }));
        OsChecks {
            apt: Default::default(),
            tailscale: Default::default(),
            runner,
        }
    }
}

impl OsChecks {
    #[cfg(test)]
    fn with_runner(runner: Runner) -> Self {
        OsChecks {
            runner,
            ..Default::default()
        }
    }

    async fn read_apt(&self) {
        let found = (self.runner)("apt", &["list", "--upgradable"])
            .await
            .map(|output| {
                output
                    .lines()
                    .filter(|l| !l.starts_with("Listing...") && !l.trim().is_empty())
                    .count()
            });
        self.apt.finish(Instant::now(), found, found.is_some());
    }

    async fn read_tailscale(&self) {
        // `--upstream` asks pkgs.tailscale.com: both run at once.
        let (current, upstream) = tokio::join!(
            (self.runner)("tailscale", &["version"]),
            (self.runner)("tailscale", &["version", "--upstream"])
        );
        let found = current
            .and_then(|out| out.lines().next().map(|l| l.trim().to_string()))
            .filter(|current| !current.is_empty())
            .map(|current| TailscaleInfo {
                current,
                upstream: upstream.as_deref().and_then(parse_upstream),
            });
        let old = self.tailscale.0.lock().unwrap().value.clone();
        let (store, ok) = tailscale_store(old.as_ref(), found);
        self.tailscale.finish(Instant::now(), store, ok);
    }

    /// Reads both now (a read already running is left to finish).
    pub(crate) async fn refresh(&self) {
        tokio::join!(
            async {
                if self.apt.claim() {
                    self.read_apt().await;
                }
            },
            async {
                if self.tailscale.claim() {
                    self.read_tailscale().await;
                }
            }
        );
    }

    /// After a button that changes what the checks would say: reads again once the root helper has
    /// done (a few seconds for a check, many minutes for a first upgrade), off the request.
    pub(crate) fn refresh_after_action(self: &Arc<Self>) {
        let checks = self.clone();
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_secs(3)).await;
            for _ in 0..300 {
                if !watcher_busy().await {
                    break;
                }
                tokio::time::sleep(Duration::from_secs(5)).await;
            }
            checks.refresh().await;
        });
    }
}

fn reboot_required() -> bool {
    std::path::Path::new("/var/run/reboot-required").exists()
}

#[derive(Default, Clone)]
pub(crate) struct ScheduleConfig {
    pub(crate) frequency: String,
    pub(crate) day_of_week: String,
    pub(crate) day_of_month: String,
    pub(crate) check_time: String,
    pub(crate) auto_apply_os: bool,
    pub(crate) auto_apply_tailscale: bool,
    pub(crate) auto_reboot: bool,
}

impl ScheduleConfig {
    fn defaults() -> Self {
        ScheduleConfig {
            frequency: "daily".to_string(),
            day_of_week: "0".to_string(),
            day_of_month: "1".to_string(),
            check_time: "03:00".to_string(),
            auto_apply_os: false,
            auto_apply_tailscale: false,
            auto_reboot: false,
        }
    }

    async fn load(data: &std::path::Path) -> Self {
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
                "CHECK_TIME" => config.check_time = value,
                "AUTO_APPLY_OS" => config.auto_apply_os = value == "true",
                "AUTO_APPLY_TAILSCALE" => config.auto_apply_tailscale = value == "true",
                "AUTO_REBOOT" => config.auto_reboot = value == "true",
                _ => {}
            }
        }
        config
    }

    fn to_file_contents(&self) -> String {
        format!(
            "FREQUENCY={}\nDAY_OF_WEEK={}\nDAY_OF_MONTH={}\nCHECK_TIME={}\nAUTO_APPLY_OS={}\nAUTO_APPLY_TAILSCALE={}\nAUTO_REBOOT={}\n",
            self.frequency,
            self.day_of_week,
            self.day_of_month,
            self.check_time,
            self.auto_apply_os,
            self.auto_apply_tailscale,
            self.auto_reboot,
        )
    }
}

/// Everything the combined updates page needs to know about the OS/Tailscale
/// side - gathered here, rendered by `handlers::updates`.
pub(crate) struct OsUpdateData {
    pub(crate) watcher_busy: bool,
    pub(crate) os_update_count: Option<usize>,
    /// No read of apt has ended since the server started: the page says it's checking.
    pub(crate) os_checking: bool,
    pub(crate) reboot_required: bool,
    pub(crate) tailscale_current: Option<String>,
    pub(crate) tailscale_upstream: Option<String>,
    pub(crate) tailscale_update_available: bool,
    /// The version is known but pkgs.tailscale.com couldn't be asked: never shown as up to date.
    pub(crate) tailscale_upstream_unknown: bool,
    pub(crate) tailscale_checking: bool,
    pub(crate) schedule: ScheduleConfig,
}

/// Nothing to report: the container has no OS or Tailscale of its own to manage (design 22 §6).
pub(crate) fn not_managed() -> OsUpdateData {
    OsUpdateData {
        watcher_busy: false,
        os_update_count: None,
        os_checking: false,
        reboot_required: false,
        tailscale_current: None,
        tailscale_upstream: None,
        tailscale_update_available: false,
        tailscale_upstream_unknown: false,
        tailscale_checking: false,
        schedule: ScheduleConfig::defaults(),
    }
}

pub(crate) async fn gather(state: &AppState) -> OsUpdateData {
    let busy = watcher_busy().await;
    let checks = &state.os_checks;
    let mut data = OsUpdateData {
        watcher_busy: busy,
        os_update_count: None,
        os_checking: false,
        reboot_required: reboot_required(),
        tailscale_current: None,
        tailscale_upstream: None,
        tailscale_update_available: false,
        tailscale_upstream_unknown: false,
        tailscale_checking: false,
        schedule: ScheduleConfig::load(&state.data_dir).await,
    };

    // Don't start a read while the watcher is busy - apt would just block on the dpkg lock the
    // in-progress action is holding, and there's nothing new to report while it's still running
    // anyway (`refresh_after_action` reads when it's done).
    if busy {
        return data;
    }
    let now = Instant::now();
    let (apt, tailscale) = (checks.apt.seen(now), checks.tailscale.seen(now));
    if apt.start {
        let checks = checks.clone();
        tokio::spawn(async move { checks.read_apt().await });
    }
    if tailscale.start {
        let checks = checks.clone();
        tokio::spawn(async move { checks.read_tailscale().await });
    }

    data.os_update_count = apt.value;
    data.os_checking = apt.checking;
    data.tailscale_checking = tailscale.checking;
    if let Some(info) = tailscale.value {
        data.tailscale_update_available = info
            .upstream
            .as_deref()
            .is_some_and(|upstream| upstream != info.current);
        data.tailscale_upstream_unknown = info.upstream.is_none();
        data.tailscale_upstream = info.upstream;
        data.tailscale_current = Some(info.current);
    }
    data
}

pub async fn trigger_os_check(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let done = updates::Done::OsCheck;
    let answer =
        updates::request_action(&state, &admin, "os_check", "os_check_triggered", done, lang).await;
    refresh_if_started(&state, &answer);
    answer
}

pub async fn trigger_os_upgrade(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let done = updates::Done::OsUpgrade;
    let answer = updates::request_action(
        &state,
        &admin,
        "os_upgrade",
        "os_upgrade_triggered",
        done,
        lang,
    )
    .await;
    refresh_if_started(&state, &answer);
    answer
}

pub async fn trigger_tailscale_update(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let (action, event) = ("tailscale_update", "tailscale_update_triggered");
    let done = updates::Done::Tailscale;
    let answer = updates::request_action(&state, &admin, action, event, done, lang).await;
    refresh_if_started(&state, &answer);
    answer
}

/// The request was written (a 303 back to the page): the checks are read again when the helper is
/// done, so the next visit shows the result, not the last read's.
fn refresh_if_started(state: &AppState, answer: &Response) {
    if answer.status().is_redirection() {
        state.os_checks.refresh_after_action();
    }
}

pub async fn trigger_reboot(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let done = updates::Done::Reboot;
    updates::request_action(&state, &admin, "reboot", "reboot_triggered", done, lang).await
}

#[derive(Deserialize)]
pub struct ScheduleForm {
    frequency: String,
    day_of_week: String,
    day_of_month: String,
    check_time: String,
    auto_apply_os: Option<String>,
    auto_apply_tailscale: Option<String>,
    auto_reboot: Option<String>,
}

const VALID_FREQUENCIES: [&str; 3] = ["daily", "weekly", "monthly"];

/// Saves the OS/Tailscale schedule (one form, §5.6). A bad frequency or a time that isn't HH:MM
/// writes nothing (400, the reason in the group).
pub async fn save_schedule(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<ScheduleForm>,
) -> Response {
    let refuse = |why: String| Flash::refused("os-schedule", why);
    if !VALID_FREQUENCIES.contains(&form.frequency.as_str()) {
        let flash = refuse(crate::i18n::t(lang, "settings.schedule.bad_frequency").to_string());
        let page = updates::render_page(&state, Some(flash), lang).await;
        return (http::StatusCode::BAD_REQUEST, page).into_response();
    }
    let day_of_week: i32 = form.day_of_week.trim().parse().unwrap_or(0).clamp(0, 6);
    let day_of_month: i32 = form.day_of_month.trim().parse().unwrap_or(1).clamp(1, 28);
    // Read by a root script (scheduler.sh): exactly HH:MM or nothing is written (review H1).
    let check_time = if form.check_time.trim().is_empty() {
        "03:00".to_string()
    } else if security::valid_clock_time(form.check_time.trim()) {
        form.check_time.trim().to_string()
    } else {
        let flash = refuse(updates::bad_time(lang, "03:00"));
        let page = updates::render_page(&state, Some(flash), lang).await;
        return (http::StatusCode::BAD_REQUEST, page).into_response();
    };

    let config = ScheduleConfig {
        frequency: form.frequency,
        day_of_week: day_of_week.to_string(),
        day_of_month: day_of_month.to_string(),
        check_time,
        auto_apply_os: form.auto_apply_os.is_some(),
        auto_apply_tailscale: form.auto_apply_tailscale.is_some(),
        auto_reboot: form.auto_reboot.is_some(),
    };

    if tokio::fs::write(
        state.data_dir.join(SCHEDULE_FILE),
        config.to_file_contents(),
    )
    .await
    .is_err()
    {
        let page = updates::render_page(
            &state,
            Some(refuse(
                crate::i18n::t(lang, "settings.schedule.save_failed").to_string(),
            )),
            lang,
        )
        .await;
        return (http::StatusCode::INTERNAL_SERVER_ERROR, page).into_response();
    }
    security::record_security_event(
        &state.db,
        "update_schedule_changed",
        Some(&admin.username),
        None,
        None,
    )
    .await;
    updates::Done::OsSchedule.redirect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;
    use std::sync::atomic::{AtomicUsize, Ordering::SeqCst};

    const TS: &str = "1.80.2\n  tailscale commit: abc\n  go version: go1.23\n";

    fn info(current: &str, upstream: Option<&str>) -> TailscaleInfo {
        TailscaleInfo {
            current: current.into(),
            upstream: upstream.map(str::to_string),
        }
    }

    /// The cache's rule: one read at a time, none until it is due, six hours after a good read and
    /// five minutes after a failed one; a failed read keeps the last good value.
    #[test]
    fn a_check_stands_six_hours_and_a_failure_five_minutes() {
        let cache = CheckCache::<usize>::default();
        let t0 = Instant::now();

        let first = cache.seen(t0);
        assert!(first.checking && first.start && first.value.is_none());
        let second = cache.seen(t0);
        assert!(second.checking && !second.start, "one read at a time");

        cache.finish(t0, Some(3), true);
        let fresh = cache.seen(t0 + CHECK_MAX_AGE - Duration::from_secs(1));
        assert_eq!(
            (fresh.value, fresh.checking, fresh.failed, fresh.start),
            (Some(3), false, false, false)
        );
        let due = cache.seen(t0 + CHECK_MAX_AGE);
        assert!(due.start && due.value == Some(3));

        let t1 = t0 + CHECK_MAX_AGE;
        cache.finish(t1, None, false);
        let after_failure = cache.seen(t1 + CHECK_RETRY - Duration::from_secs(1));
        assert_eq!(after_failure.value, Some(3), "the last good value stays");
        assert!(after_failure.failed && !after_failure.start);
        assert!(
            cache.seen(t1 + CHECK_RETRY).start,
            "a failure is tried again soon"
        );
    }

    #[test]
    fn upstream_is_read_from_anywhere_in_the_block() {
        assert_eq!(
            parse_upstream("1.80.2\n upstream: 1.82.0\n"),
            Some("1.82.0".into())
        );
        assert_eq!(
            parse_upstream("1.80.2 upstream: 1.82.0 go: 1"),
            Some("1.82.0".into())
        );
        assert_eq!(parse_upstream("1.80.2\n"), None);
        assert_eq!(parse_upstream("upstream:"), None);
    }

    /// A read that found the version but not the upstream one is never "up to date" and never
    /// replaces a read that had both.
    #[test]
    fn a_half_read_never_replaces_a_whole_one() {
        let whole = info("1.80.2", Some("1.82.0"));
        let half = info("1.80.2", None);
        assert_eq!(
            tailscale_store(None, Some(whole.clone())),
            (Some(whole.clone()), true)
        );
        assert_eq!(
            tailscale_store(None, Some(half.clone())),
            (Some(half.clone()), false)
        );
        assert_eq!(
            tailscale_store(Some(&whole), Some(half.clone())),
            (None, false)
        );
        assert_eq!(
            tailscale_store(Some(&half), Some(half.clone())),
            (Some(half), false)
        );
        assert_eq!(tailscale_store(Some(&whole), None), (None, false));
    }

    type Answers = Arc<Mutex<(Option<String>, Option<String>, Option<String>)>>;

    /// A runner answering from `(apt, tailscale version, tailscale version --upstream)` and
    /// counting the calls.
    fn fake(answers: &Answers, calls: &Arc<AtomicUsize>) -> OsChecks {
        let (answers, calls) = (answers.clone(), calls.clone());
        OsChecks::with_runner(Arc::new(move |cmd, args| {
            calls.fetch_add(1, SeqCst);
            let a = answers.lock().unwrap().clone();
            let out = match (cmd, args.len()) {
                ("apt", _) => a.0,
                (_, 1) => a.1,
                _ => a.2,
            };
            Box::pin(async move { out })
        }))
    }

    #[tokio::test]
    async fn the_checks_read_once_and_keep_the_last_good_answer() {
        let apt = "Listing...\nlibc6/stable 1 arm64 [upgradable from: 0]\ncurl/stable 2 arm64\n";
        let answers: Answers = Arc::new(Mutex::new((
            Some(apt.into()),
            Some(TS.into()),
            Some(format!("{TS}  upstream: 1.82.0\n")),
        )));
        let calls = Arc::new(AtomicUsize::new(0));
        let checks = fake(&answers, &calls);

        checks.refresh().await;
        assert_eq!(calls.load(SeqCst), 3);
        let apt_seen = checks.apt.seen(Instant::now());
        assert_eq!(
            (apt_seen.value, apt_seen.failed, apt_seen.start),
            (Some(2), false, false)
        );
        let ts_seen = checks.tailscale.seen(Instant::now());
        assert_eq!(ts_seen.value, Some(info("1.80.2", Some("1.82.0"))));

        // Everything fails: the last good answers stay, both are marked failed and due soon.
        *answers.lock().unwrap() = (None, Some(TS.into()), None);
        checks.refresh().await;
        let apt_seen = checks.apt.seen(Instant::now());
        assert_eq!((apt_seen.value, apt_seen.failed), (Some(2), true));
        let ts_seen = checks.tailscale.seen(Instant::now());
        assert_eq!(ts_seen.value, Some(info("1.80.2", Some("1.82.0"))));
        assert!(ts_seen.failed);
    }

    #[tokio::test]
    async fn an_unreachable_upstream_is_unknown_not_up_to_date() {
        let answers: Answers = Arc::new(Mutex::new((None, Some(TS.into()), None)));
        let checks = fake(&answers, &Arc::new(AtomicUsize::new(0)));
        checks.refresh().await;
        let seen = checks.tailscale.seen(Instant::now());
        assert_eq!(seen.value, Some(info("1.80.2", None)));
        assert!(seen.failed);
        assert!(checks.apt.seen(Instant::now()).value.is_none());
    }
}
