//! Install Now (design 37, `docs/design/37-install-now.md` at the monorepo root): the parent's
//! one-time, immediate install of Vibb One's newest release on one phone, from the launcher's page
//! under Apps. A `device_commands` row of kind [COMMAND] carries the release tag the row showed;
//! the phone downloads the newest release on any network, checks it exactly as every self-update
//! and installs it at once - waiting only for a call or an emergency. What the row says and whether
//! it offers the button is decided here, pure and typed; the template words it
//! (`handlers::install_now`, `tracked_app_detail.html`).

use chrono::{DateTime, NaiveDateTime, Utc};

use crate::app_downloads::AppDownloadEntry;
use crate::display_time::Zone;
use crate::models::{CommandStatus, DeviceCommand, Unconfirmed};

/// The command's kind in `device_commands`.
pub const COMMAND: &str = "install_update";

/// The status-report capability of a launcher that runs Install Now.
pub const CAPABILITY: &str = "install_now_v1";

/// The launcher's `update_fence.waiting_for` words while an Install Now runs (design 37 §3).
pub const WAIT_DOWNLOAD: &str = "install_now_download";
pub const WAIT_CALL: &str = "install_now_call";
pub const WAIT_EMERGENCY: &str = "install_now_emergency";
pub const WAIT_READY: &str = "install_now_ready";

/// An answer stays in the row (a `details.info`) this long after it came.
const ANSWER_SHOWN_HOURS: i64 = 24;

/// The versionCode the release workflow builds for a launcher tag - the launcher's
/// `launcherTagVersionCode`: the part before `@`, `launcher-vX.Y.Z` = `X*1_000_000 + Y*1_000 + Z`,
/// `-rc.N` one less; `None` for any other tag (a manual upload's label).
pub fn launcher_tag_version_code(tag: &str) -> Option<i64> {
    let tag = tag.split('@').next().unwrap_or(tag);
    let rest = tag.strip_prefix("launcher-v")?;
    let (version, rc) = match rest.split_once("-rc.") {
        Some((version, n)) => {
            if n.is_empty() || !n.bytes().all(|b| b.is_ascii_digit()) {
                return None;
            }
            (version, true)
        }
        None => (rest, false),
    };
    let parts: Vec<&str> = version.split('.').collect();
    let [x, y, z] = parts.as_slice() else {
        return None;
    };
    let number = |s: &str, max: usize| -> Option<i64> {
        (!s.is_empty() && s.len() <= max && s.bytes().all(|b| b.is_ascii_digit()))
            .then(|| s.parse().ok())
            .flatten()
    };
    let code = number(x, 4)? * 1_000_000 + number(y, 3)? * 1_000 + number(z, 3)? - i64::from(rc);
    (code >= 1).then_some(code)
}

/// What the phone's latest report says, as far as Install Now cares.
#[derive(Clone, Debug, Default)]
pub struct Report<'a> {
    /// The running build's name ("1.3.2") and code.
    pub app_version: Option<&'a str>,
    pub app_version_code: Option<i64>,
    /// The report lists [CAPABILITY].
    pub capable: bool,
    /// `update_fence.pending_tag`: a downloaded update waits.
    pub pending_tag: Option<&'a str>,
    /// `update_fence.waiting_for`.
    pub waiting_for: Option<&'a str>,
    /// The launcher row's `app_downloads` entry.
    pub download: Option<&'a AppDownloadEntry>,
    /// When the server stored the report.
    pub reported_at: Option<DateTime<Utc>>,
}

/// The launcher row and the phone, without the command: what a tap would do.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Standing {
    /// The phone never reported.
    NotReported,
    /// No release, or the launcher row is off (`enabled = 0`): the phone would get nothing.
    NoRelease,
    /// The phone runs the release (or a newer one).
    UpToDate { running: String },
    /// The release is newer; `downloaded` while the phone keeps it pending.
    Behind {
        running: String,
        waiting: String,
        downloaded: bool,
    },
    /// Behind, but the phone's Vibb One doesn't know Install Now yet.
    TooOld { waiting: String },
}

/// [Standing] with the newest Install Now: the row's state.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Offer {
    Standing(Standing),
    /// Not fetched (`Waiting`, with Cancel) or fetched and not confirmed yet (`sent`).
    Asked {
        command_id: i64,
        sent: bool,
    },
    /// Confirmed, no answer yet: what the phone does ([working_words]).
    Working {
        words: String,
    },
}

impl Offer {
    /// The button: Install Now only for [Standing::Behind].
    pub fn install(&self) -> bool {
        matches!(self, Offer::Standing(Standing::Behind { .. }))
    }

    /// The command Cancel may stop: one the phone hasn't fetched.
    pub fn cancel_id(&self) -> Option<i64> {
        match self {
            Offer::Asked {
                command_id,
                sent: false,
            } => Some(*command_id),
            _ => None,
        }
    }

    /// The poll's state word (`static/install-now.js` reloads the page when it changes).
    pub fn state(&self) -> &'static str {
        match self {
            Offer::Asked { sent: false, .. } => "waiting",
            Offer::Asked { sent: true, .. } => "sent",
            Offer::Working { .. } => "working",
            Offer::Standing(_) => "idle",
        }
    }

    /// The running version, the row's value.
    pub fn value(&self) -> Option<&str> {
        match self {
            Offer::Standing(Standing::UpToDate { running } | Standing::Behind { running, .. }) => {
                Some(running.as_str())
            }
            _ => None,
        }
    }

    /// The row's sub-line.
    pub fn sub(&self) -> Option<String> {
        Some(match self {
            Offer::Standing(Standing::NotReported) => "Hasn't reported yet".to_string(),
            Offer::Standing(Standing::NoRelease) => "No release to install".to_string(),
            Offer::Standing(Standing::UpToDate { .. }) => return None,
            Offer::Standing(Standing::Behind {
                waiting,
                downloaded: true,
                ..
            }) => format!("{waiting} is waiting"),
            Offer::Standing(Standing::Behind { waiting, .. }) => {
                format!("{waiting} not downloaded yet")
            }
            Offer::Standing(Standing::TooOld { waiting }) => {
                format!("{waiting} is waiting. Install Now works from this update on.")
            }
            Offer::Asked { sent: false, .. } => "Install Now: waiting for the phone".to_string(),
            Offer::Asked { sent: true, .. } => "Install Now: sent, not confirmed yet".to_string(),
            Offer::Working { words } => words.clone(),
        })
    }
}

/// The release as the parent reads it: "1.4.0" for `launcher-v1.4.0@123`, a manual upload's label.
fn release_name(tag: &str) -> String {
    crate::app_downloads::release_name(tag).to_string()
}

/// What a tap would do now ([Standing]): `tag` is the launcher row's release, `None` when it has
/// none or is off. A tag that doesn't parse (a manual upload) is behind unless the phone runs a
/// build of that name - the phone may then answer "Already on X" (design 37 §2.2, accepted).
pub fn release(tag: Option<&str>, report: Option<&Report>) -> Standing {
    let Some(report) = report else {
        return Standing::NotReported;
    };
    let Some(tag) = tag else {
        return Standing::NoRelease;
    };
    let waiting = release_name(tag);
    let running = report.app_version.unwrap_or("Unknown version").to_string();
    let behind = report.pending_tag.is_some()
        || match launcher_tag_version_code(tag) {
            Some(code) => report.app_version_code.is_none_or(|running| code > running),
            None => report.app_version != Some(waiting.as_str()),
        };
    if !behind {
        Standing::UpToDate { running }
    } else if !report.capable {
        Standing::TooOld { waiting }
    } else {
        Standing::Behind {
            running,
            waiting,
            downloaded: report.pending_tag.is_some(),
        }
    }
}

/// The newest Install Now still open: `Waiting`, `Sent` (not lost), or `Received` without an
/// answer under its 25 h. Anything else (an answer, Cancel, "No answer", lost) gives the row its
/// button back.
fn open_status(command: &DeviceCommand, now: NaiveDateTime) -> Option<CommandStatus> {
    let status = command.status(now, true);
    match status {
        CommandStatus::Waiting { .. }
        | CommandStatus::Received { overdue: false, .. }
        | CommandStatus::Sent {
            unconfirmed: Unconfirmed::NotYet | Unconfirmed::OfferedAgain,
            ..
        } => Some(status),
        _ => None,
    }
}

/// The row: [release], unless the newest Install Now ([newest]) is still open.
pub fn offer(
    tag: Option<&str>,
    report: Option<&Report>,
    newest: Option<&DeviceCommand>,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Offer {
    if let Some(command) = newest
        && awaiting_report(command, report, now.naive_utc())
    {
        return Offer::Working {
            words: "Installed - waiting for the phone's report".to_string(),
        };
    }
    if let Some(command) = newest
        && let Some(status) = open_status(command, now.naive_utc())
    {
        return match status {
            CommandStatus::Waiting { .. } => Offer::Asked {
                command_id: command.id,
                sent: false,
            },
            CommandStatus::Received { at, .. } => Offer::Working {
                words: working_words(at, report, zone, now),
            },
            _ => Offer::Asked {
                command_id: command.id,
                sent: true,
            },
        };
    }
    Offer::Standing(release(tag, report))
}

/// How long a "Done" answer may wait for the report that shows the new version.
const AWAIT_REPORT_MINUTES: i64 = 60;

/// The phone answered "installed: X" and its latest report is from before that answer
/// (qa-37-code M1: in one sync the launcher answers before it reports): the row is still
/// working - not "behind" with an Install Now button - until a report lands, at most an hour.
pub fn awaiting_report(
    command: &DeviceCommand,
    report: Option<&Report>,
    now: NaiveDateTime,
) -> bool {
    if command.succeeded == Some(false)
        || !command
            .result
            .as_deref()
            .is_some_and(|r| r.starts_with("installed: "))
    {
        return false;
    }
    let Some(answered) = command
        .acknowledged_at
        .as_deref()
        .and_then(|at| NaiveDateTime::parse_from_str(at, "%Y-%m-%d %H:%M:%S").ok())
    else {
        return false;
    };
    if (now - answered).num_minutes() >= AWAIT_REPORT_MINUTES {
        return false;
    }
    match report.and_then(|r| r.reported_at) {
        Some(reported) => reported.naive_utc() < answered,
        None => true,
    }
}

/// What a POST does: `None` = queue it (a waiting one is replaced), else the word it answers with.
pub fn refusal(
    tag: Option<&str>,
    report: Option<&Report>,
    newest: Option<&DeviceCommand>,
    now: NaiveDateTime,
) -> Option<&'static str> {
    if let Some(command) = newest
        && let Some(status) = open_status(command, now)
        && !matches!(status, CommandStatus::Waiting { .. })
    {
        return Some("in_progress");
    }
    // Just installed, the report not in yet (M1): nothing to install.
    if let Some(command) = newest
        && awaiting_report(command, report, now)
    {
        return Some("in_progress");
    }
    match release(tag, report) {
        Standing::Behind { .. } => None,
        Standing::NotReported => Some("not_reported"),
        Standing::NoRelease => Some("no_release"),
        Standing::UpToDate { .. } => Some("up_to_date"),
        Standing::TooOld { .. } => Some("too_old"),
    }
}

/// What the phone does after its receipt (`received` server time): from its latest report, only
/// when that report came after the receipt (both server times) - else "The phone got it (14:02)".
pub fn working_words(
    received: Option<DateTime<Utc>>,
    report: Option<&Report>,
    zone: &Zone,
    now: DateTime<Utc>,
) -> String {
    let got_it = || match received {
        Some(at) => format!("The phone got it ({})", zone.when(at, now)),
        None => "The phone got it".to_string(),
    };
    let Some(report) = report else {
        return got_it();
    };
    let fresh = match (report.reported_at, received) {
        (Some(reported), Some(received)) => reported > received,
        _ => false,
    };
    if !fresh {
        return got_it();
    }
    match report.waiting_for {
        Some(WAIT_DOWNLOAD) => download_words(report.download),
        Some(WAIT_CALL) => "Waits for the call to end".to_string(),
        Some(WAIT_EMERGENCY) => "Waits: an emergency call was recent".to_string(),
        Some(WAIT_READY) => "Installing".to_string(),
        _ => got_it(),
    }
}

/// The launcher's download while Install Now waits for it.
fn download_words(entry: Option<&AppDownloadEntry>) -> String {
    let Some(entry) = entry else {
        return "Downloading".to_string();
    };
    match entry.state.as_str() {
        "waiting_network" => "Waiting for a network".to_string(),
        "waiting_space" => "Waiting: not enough space".to_string(),
        "waiting_roaming" => "Waiting: the phone is roaming".to_string(),
        _ => match crate::app_downloads::progress(entry) {
            Some(progress) => format!("Downloading {progress}"),
            None => "Downloading".to_string(),
        },
    }
}

/// When the newest Install Now ended - its answer, its Cancel, or the end of its 25 h without one
/// - for the row's answer, shown [ANSWER_SHOWN_HOURS]; `None` while it is open.
pub fn answer_fresh(command: &DeviceCommand, now: NaiveDateTime) -> bool {
    if open_status(command, now).is_some() {
        return false;
    }
    let parse = |at: &str| NaiveDateTime::parse_from_str(at, "%Y-%m-%d %H:%M:%S").ok();
    let ended = command
        .acknowledged_at
        .as_deref()
        .and_then(parse)
        .or_else(|| command.cancelled_at.as_deref().and_then(parse))
        .or_else(|| {
            command.received_at.as_deref().and_then(parse).map(|at| {
                at + chrono::Duration::minutes(crate::models::INSTALL_UPDATE_RECEIVED_MINUTES)
            })
        })
        .or_else(|| command.delivered_at.as_deref().and_then(parse));
    ended.is_some_and(|at| (now - at).num_hours() < ANSWER_SHOWN_HOURS)
}

/// The flash for `?install=<word>` (the POSTs' redirects): its text and whether it is good news.
pub fn flash(word: &str) -> Option<(&'static str, bool)> {
    Some(match word {
        "sent" => ("Install Now sent.", true),
        "cancelled" => ("Install Now cancelled.", true),
        "too_late" => ("Too late to cancel: the phone has it.", false),
        "up_to_date" => ("Already up to date.", true),
        "too_old" => ("This phone's Vibb One is too old for Install Now.", false),
        "no_release" => ("No release to install. Check Now first.", false),
        "in_progress" => ("Install Now is already on its way.", false),
        "not_reported" => ("This phone hasn't reported yet.", false),
        "not_set_up" => ("That phone isn't set up.", false),
        "unknown_phone" => ("That phone isn't here any more.", false),
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn oslo() -> Zone {
        Zone::from_setting("Europe/Oslo")
    }

    fn utc(text: &str) -> DateTime<Utc> {
        crate::display_time::parse_db_utc(text).unwrap()
    }

    fn command(status: &str) -> DeviceCommand {
        let mut c = DeviceCommand {
            id: 9,
            device_id: 1,
            command: COMMAND.to_string(),
            requested_at: "2026-10-19 12:00:00".to_string(),
            delivered_at: None,
            acknowledged_at: None,
            result: None,
            received_at: None,
            deliveries: 0,
            first_delivered_at: None,
            succeeded: None,
            cancelled_at: None,
            cancel_reason: None,
        };
        match status {
            "waiting" => {}
            "sent" => {
                c.delivered_at = Some("2026-10-19 12:00:30".into());
                c.first_delivered_at = c.delivered_at.clone();
                c.deliveries = 1;
            }
            "received" => {
                c.delivered_at = Some("2026-10-19 12:00:30".into());
                c.received_at = Some("2026-10-19 12:00:31".into());
            }
            "failed" => {
                c.delivered_at = Some("2026-10-19 12:00:30".into());
                c.received_at = Some("2026-10-19 12:00:31".into());
                c.acknowledged_at = Some("2026-10-19 12:03:00".into());
                c.result = Some("download failed".into());
                c.succeeded = Some(false);
            }
            "done" => {
                c.delivered_at = Some("2026-10-19 12:00:30".into());
                c.received_at = Some("2026-10-19 12:00:31".into());
                c.acknowledged_at = Some("2026-10-19 12:05:00".into());
                c.result = Some("installed: 1.4.0".into());
                c.succeeded = Some(true);
            }
            "cancelled" => {
                c.cancelled_at = Some("2026-10-19 12:01:00".into());
                c.cancel_reason = Some("parent".into());
            }
            other => panic!("{other}"),
        }
        c
    }

    fn report<'a>() -> Report<'a> {
        Report {
            app_version: Some("1.3.2"),
            app_version_code: Some(1_003_002),
            capable: true,
            reported_at: Some(utc("2026-10-19 12:02:00")),
            ..Report::default()
        }
    }

    const TAG: &str = "launcher-v1.4.0@77";

    #[test]
    fn launcher_tags_read_as_the_release_workflows_version_codes() {
        // The launcher's SelfUpdatePlanTest cases.
        assert_eq!(
            launcher_tag_version_code("launcher-v1.4.0"),
            Some(1_004_000)
        );
        assert_eq!(
            launcher_tag_version_code("launcher-v1.4.0@77"),
            Some(1_004_000)
        );
        assert_eq!(launcher_tag_version_code("launcher-v0.32.0"), Some(32_000));
        assert_eq!(
            launcher_tag_version_code("launcher-v2.0.1-rc.3"),
            Some(2_000_000)
        );
        assert_eq!(launcher_tag_version_code("launcher-v0.0.1-rc.1"), None);
        assert_eq!(launcher_tag_version_code("launcher-v12345.0.0"), None);
        assert_eq!(launcher_tag_version_code("launcher-v1.4"), None);
        assert_eq!(launcher_tag_version_code("launcher-v1.4.0-rc."), None);
        assert_eq!(launcher_tag_version_code("v1.4.0"), None);
        assert_eq!(launcher_tag_version_code("1.4.0 test"), None);
    }

    #[test]
    fn the_release_table() {
        let r = report();
        assert_eq!(release(Some(TAG), None), Standing::NotReported);
        assert_eq!(release(None, Some(&r)), Standing::NoRelease);
        assert_eq!(
            release(Some(TAG), Some(&r)),
            Standing::Behind {
                running: "1.3.2".into(),
                waiting: "1.4.0".into(),
                downloaded: false
            }
        );
        let pending = Report {
            pending_tag: Some(TAG),
            ..report()
        };
        assert_eq!(
            Offer::Standing(release(Some(TAG), Some(&pending))).sub(),
            Some("1.4.0 is waiting".into())
        );
        assert_eq!(
            Offer::Standing(release(Some(TAG), Some(&r))).sub(),
            Some("1.4.0 not downloaded yet".into())
        );
        let running = Report {
            app_version: Some("1.4.0"),
            app_version_code: Some(1_004_000),
            ..report()
        };
        assert_eq!(
            release(Some(TAG), Some(&running)),
            Standing::UpToDate {
                running: "1.4.0".into()
            }
        );
        let old = Report {
            capable: false,
            ..report()
        };
        let too_old = Offer::Standing(release(Some(TAG), Some(&old)));
        assert!(!too_old.install());
        assert_eq!(
            too_old.sub(),
            Some("1.4.0 is waiting. Install Now works from this update on.".into())
        );
        // A manual upload's label never parses: behind unless the phone runs that name.
        let manual = "1.4.0 test";
        assert!(matches!(
            release(Some(manual), Some(&r)),
            Standing::Behind { .. }
        ));
        let on_it = Report {
            app_version: Some("1.4.0 test"),
            ..report()
        };
        assert!(matches!(
            release(Some(manual), Some(&on_it)),
            Standing::UpToDate { .. }
        ));
    }

    #[test]
    fn the_offer_follows_the_newest_install_now() {
        let r = report();
        let zone = oslo();
        let now = utc("2026-10-19 12:10:00");
        let offer_for = |c: &DeviceCommand| offer(Some(TAG), Some(&r), Some(c), &zone, now);
        let waiting = offer_for(&command("waiting"));
        assert_eq!(waiting.cancel_id(), Some(9));
        assert_eq!(waiting.state(), "waiting");
        assert_eq!(
            waiting.sub(),
            Some("Install Now: waiting for the phone".into())
        );
        let sent = offer_for(&command("sent"));
        assert_eq!(sent.cancel_id(), None);
        assert!(!sent.install());
        assert_eq!(
            sent.sub(),
            Some("Install Now: sent, not confirmed yet".into())
        );
        assert_eq!(offer_for(&command("received")).state(), "working");
        // An answer, a Cancel and "No answer" give the button back.
        for done in ["failed", "cancelled"] {
            assert!(offer_for(&command(done)).install(), "{done}");
        }
        let late = offer(
            Some(TAG),
            Some(&r),
            Some(&command("received")),
            &zone,
            utc("2026-10-20 13:01:00"),
        );
        assert!(late.install());
        // Still Working at 11 minutes (not "No answer" as other commands).
        let eleven = offer(
            Some(TAG),
            Some(&r),
            Some(&command("received")),
            &zone,
            utc("2026-10-19 12:11:31"),
        );
        assert_eq!(eleven.state(), "working");
    }

    #[test]
    fn a_post_queues_only_for_a_phone_that_is_behind() {
        let r = report();
        let now = utc("2026-10-19 12:10:00").naive_utc();
        assert_eq!(refusal(Some(TAG), Some(&r), None, now), None);
        // A waiting one is replaced, a fetched one isn't.
        assert_eq!(
            refusal(Some(TAG), Some(&r), Some(&command("waiting")), now),
            None
        );
        assert_eq!(
            refusal(Some(TAG), Some(&r), Some(&command("sent")), now),
            Some("in_progress")
        );
        assert_eq!(
            refusal(Some(TAG), Some(&r), Some(&command("received")), now),
            Some("in_progress")
        );
        assert_eq!(
            refusal(Some(TAG), Some(&r), Some(&command("failed")), now),
            None
        );
        assert_eq!(refusal(None, Some(&r), None, now), Some("no_release"));
        assert_eq!(refusal(Some(TAG), None, None, now), Some("not_reported"));
        let old = Report {
            capable: false,
            ..report()
        };
        assert_eq!(refusal(Some(TAG), Some(&old), None, now), Some("too_old"));
        let running = Report {
            app_version_code: Some(1_004_000),
            ..report()
        };
        assert_eq!(
            refusal(Some(TAG), Some(&running), None, now),
            Some("up_to_date")
        );
        for word in [
            "sent",
            "cancelled",
            "too_late",
            "up_to_date",
            "too_old",
            "no_release",
            "in_progress",
            "not_reported",
            "not_set_up",
            "unknown_phone",
        ] {
            assert!(flash(word).is_some(), "{word}");
        }
    }

    #[test]
    fn working_words_come_from_a_report_after_the_receipt() {
        let zone = oslo();
        let now = utc("2026-10-19 12:10:00");
        let received = Some(utc("2026-10-19 12:00:31"));
        let entry = |state: &str| AppDownloadEntry {
            tracked_app_id: 1,
            release_tag: TAG.into(),
            state: state.into(),
            bytes: 40_000_000,
            total: Some(80_000_000),
            ..AppDownloadEntry::default()
        };
        let downloading = entry("downloading");
        let no_net = entry("waiting_network");
        let no_space = entry("waiting_space");
        let with = |waiting: &'static str, download: Option<&AppDownloadEntry>| -> String {
            let r = Report {
                waiting_for: Some(waiting),
                download,
                ..report()
            };
            working_words(received, Some(&r), &zone, now)
        };
        assert_eq!(
            with(WAIT_DOWNLOAD, Some(&downloading)),
            "Downloading 40 of 80 MB"
        );
        assert_eq!(with(WAIT_DOWNLOAD, Some(&no_net)), "Waiting for a network");
        assert_eq!(
            with(WAIT_DOWNLOAD, Some(&no_space)),
            "Waiting: not enough space"
        );
        assert_eq!(with(WAIT_DOWNLOAD, None), "Downloading");
        assert_eq!(with(WAIT_CALL, None), "Waits for the call to end");
        assert_eq!(
            with(WAIT_EMERGENCY, None),
            "Waits: an emergency call was recent"
        );
        assert_eq!(with(WAIT_READY, None), "Installing");
        assert_eq!(with("outside_window", None), "The phone got it (14:00)");
        // A report from before the receipt says nothing about the command.
        let stale = Report {
            reported_at: Some(utc("2026-10-19 11:59:00")),
            waiting_for: Some(WAIT_READY),
            ..report()
        };
        assert_eq!(
            working_words(received, Some(&stale), &zone, now),
            "The phone got it (14:00)"
        );
    }

    /// qa-37-code M1: "installed: X" with a report from before it is still working.
    #[test]
    fn a_done_answer_waits_for_its_report() {
        let done = command("done"); // answered 12:05:00
        let at = |t: &str| utc(t).naive_utc();
        let old = Report {
            reported_at: Some(utc("2026-10-19 12:02:00")),
            ..report()
        };
        let fresh = Report {
            reported_at: Some(utc("2026-10-19 12:05:00")),
            ..report()
        };
        assert!(awaiting_report(
            &done,
            Some(&old),
            at("2026-10-19 12:06:00")
        ));
        assert!(awaiting_report(&done, None, at("2026-10-19 12:06:00")));
        assert!(!awaiting_report(
            &done,
            Some(&fresh),
            at("2026-10-19 12:06:00")
        ));
        assert!(
            !awaiting_report(&done, Some(&old), at("2026-10-19 13:05:00")),
            "an hour at most"
        );
        let mut running = command("done");
        running.result = Some("already running: 1.4.0".into());
        assert!(!awaiting_report(
            &running,
            Some(&old),
            at("2026-10-19 12:06:00")
        ));
        assert!(!awaiting_report(
            &command("failed"),
            Some(&old),
            at("2026-10-19 12:06:00")
        ));
        let zone = oslo();
        let offered = offer(
            Some(TAG),
            Some(&old),
            Some(&done),
            &zone,
            utc("2026-10-19 12:06:00"),
        );
        assert_eq!(offered.state(), "working");
        assert!(!offered.install());
        assert_eq!(
            refusal(
                Some(TAG),
                Some(&old),
                Some(&done),
                at("2026-10-19 12:06:00")
            ),
            Some("in_progress")
        );
    }

    #[test]
    fn an_answer_shows_for_a_day() {
        let failed = command("failed");
        assert!(answer_fresh(
            &failed,
            utc("2026-10-20 12:02:00").naive_utc()
        ));
        assert!(!answer_fresh(
            &failed,
            utc("2026-10-20 12:03:00").naive_utc()
        ));
        assert!(!answer_fresh(
            &command("waiting"),
            utc("2026-10-19 12:03:00").naive_utc()
        ));
        // "No answer" counts from the end of its 25 h.
        assert!(answer_fresh(
            &command("received"),
            utc("2026-10-20 14:00:00").naive_utc()
        ));
    }
}
