//! The Attention list (design 31 §3.2 item 2, §3.8): what about a phone needs the parent, as one
//! line each with a link to the fix. The phone's summary shows the rows, the Devices list their
//! count as the red badge - both from [items] over the same [Facts], so the two always agree.
//!
//! Every title comes from a typed value, never from the first sentence of a card's text (M5):
//! those run past 8 words and carry package names. Alarms (red) come first, then what needs
//! action (orange), each in §3.2's order.
//!
//! Which rows count (the user's decision on qa-31-s2-code L5):
//! - a phone not set up has none: its "Not set up" says enough;
//! - an Offline or Disconnected phone keeps the rows read from its last report, marked "at last
//!   report" (they may no longer be true); the server's own facts (PINs, access, calls policy)
//!   are current and unmarked. Both kinds count, on the list and on the page alike.
//!
//! [facts] reads about 10 small things per phone (the policy, the last report, the relock card,
//! the call report, the rules); fine at 1-5 phones - don't make it one query. The summary, which
//! builds those parts anyway, passes them to [facts_of] instead of reading them twice.

use crate::AppState;
use crate::handlers::calls::CallProblem;
use crate::handlers::window_relock::RelockCard;
use crate::i18n::{Lang, t, t_count};
use crate::kiosk_escapes::EscapeNote;
use crate::models::{Device, DevicePolicy, DeviceStatus};
use crate::streams::ConnectionState;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Level {
    /// Red: something is open or broken.
    Alarm,
    /// Orange: needs the parent to do something.
    Action,
}

/// What a row is about.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    RestrictionsPaused,
    /// An app outside the allowlist has a device admin or an accessibility service: it may read
    /// the Parent PIN.
    ForeignAccess,
    DoNotDisturb,
    GoogleBackup,
    NoParentPin,
    EasyParentPin,
    /// Design 114 §5.2 (QA M4): the phone says Vibb One isn't its device owner (`setup.device_owner` false).
    NotDeviceOwner,
    /// Design 116 §3.4 (S-M2): the phone's web filter is down - the parent's switch on, the tunnel not filtering, for
    /// at least `mobile_network::VPN_REPORT_AFTER_MS` (`mobile_network::filter_down`).
    FilterDown,
    PolicyProblem,
    UnseenRestarts,
    ChildLockOff,
    /// Design 114 §5.2: the required setup steps left on the phone (`setup_status`'s N), one row for all of them.
    Setup(usize),
    /// Design 59 §2.3: Android's lock on the phone opens with a PIN that isn't the Child PIN.
    ChildPinChanged,
    /// How many apps the phone keeps hidden.
    BlockedApps(usize),
    RestartOnce,
    /// The launcher isn't Android's Home app: the update fence is partial.
    NotHomeApp,
    /// The boot cover was armed but not shown: another home screen came up at boot.
    BootCoverNotShown,
    /// Design 108c: app push problems, from `unified_push::health::attention` (the server's own data): pushes waiting
    /// on the server, an app's pushes refused past the heal (apps), an app's pushes still going outside (apps).
    AppPushWaiting,
    AppPushRefused(usize),
    AppPushOutside(usize),
    Calls(CallProblem),
    LedgerUnreadable,
    /// The phone's storage is full: its screen time isn't saved (`time_state.storage_full`).
    StorageFull,
    /// The phone's clock is off the time its decisions use, or its time hasn't been checked for long
    /// (`clock_skew::problem`).
    Clock(crate::clock_skew::ClockProblem),
    /// Device › Music's warnings (design 31 step 7 fix round, qa-31-s7-code M1), from
    /// `music::music_warnings` like the page's: the music app not reachable, Storytel's login
    /// refused, over the storage limit, entries the phone can't fetch (one row for all).
    MusicUnreachable,
    /// The phone has a music app signed with another key, which it refuses.
    MusicOtherSigner,
    MusicStorytelRefused,
    MusicOverLimit,
    MusicFailing(usize),
    /// The phone's Spotify account can't play (design 26c §4.4: the login, the app, Premium or
    /// the phone's web player) - the music app's tiles say "Spør en voksen".
    MusicSpotify,
    /// The account the phone plays as, from the accounts table (qa-26c-merge2-code L3): its login
    /// to renew, its login ending within 14 days, no Spotify app set up.
    MusicSpotifyLogin,
    MusicSpotifyEnding,
    MusicSpotifyNoApp,
    /// Design 116 §2.3: an outside service's failure behind this phone's features (the server's own data): its title
    /// and the failing scopes behind it.
    Integration(crate::integration_health::Title, usize),
    /// Design 129b: the new phone couldn't prove its key and waits for the parent (the server's own data).
    KeyWait,
    /// Design 129b D13: the phone's Vibb One is too old to enrol (the server's own data).
    OldApp,
}

impl Kind {
    /// Read from the phone's last report (so possibly out of date), not from this server's own
    /// settings.
    fn read_from_report(self) -> bool {
        !matches!(
            self,
            Kind::NoParentPin
                | Kind::EasyParentPin
                | Kind::AppPushWaiting
                | Kind::AppPushRefused(_)
                | Kind::AppPushOutside(_)
                | Kind::Integration(..)
                | Kind::KeyWait
                | Kind::OldApp
        )
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AttentionItem {
    pub kind: Kind,
    pub level: Level,
    /// At most 8 words, starting with the fact.
    pub title: String,
    /// Where it is fixed.
    pub target: String,
    /// From the last report of a phone that is Offline or Disconnected: "at last report".
    pub at_last_report: bool,
}

impl AttentionItem {
    /// `alarm` or `action`, for the row's class.
    pub fn level_css(&self) -> &'static str {
        match self.level {
            Level::Alarm => "alarm",
            Level::Action => "action",
        }
    }

    /// A stable name for the row (`data-attention`), for tests and the page's hooks. Every calls
    /// row shares "calls" (they link to the same page).
    pub fn key(&self) -> &'static str {
        match self.kind {
            Kind::RestrictionsPaused => "restrictions-paused",
            Kind::ForeignAccess => "foreign-access",
            Kind::DoNotDisturb => "do-not-disturb",
            Kind::GoogleBackup => "google-backup",
            Kind::NoParentPin => "no-parent-pin",
            Kind::EasyParentPin => "easy-parent-pin",
            Kind::NotDeviceOwner => "not-device-owner",
            Kind::FilterDown => "filter-down",
            Kind::PolicyProblem => "policy-problem",
            Kind::UnseenRestarts => "unseen-restarts",
            Kind::ChildLockOff => "child-lock-off",
            Kind::Setup(_) => "setup",
            Kind::ChildPinChanged => "child-pin-changed",
            Kind::BlockedApps(_) => "blocked-apps",
            Kind::RestartOnce => "restart-once",
            Kind::NotHomeApp => "not-home-app",
            Kind::BootCoverNotShown => "boot-cover-not-shown",
            Kind::AppPushWaiting => "app-push-waiting",
            Kind::AppPushRefused(_) => "app-push-refused",
            Kind::AppPushOutside(_) => "app-push-outside",
            Kind::Calls(_) => "calls",
            Kind::LedgerUnreadable => "screen-time-record",
            Kind::StorageFull => "storage-full",
            Kind::Clock(_) => "clock",
            Kind::MusicUnreachable => "music-unreachable",
            Kind::MusicOtherSigner => "music-other-signer",
            Kind::MusicStorytelRefused => "music-storytel-refused",
            Kind::MusicOverLimit => "music-over-limit",
            Kind::MusicFailing(_) => "music-entries",
            Kind::MusicSpotify => "music-spotify",
            Kind::MusicSpotifyLogin => "music-spotify-login",
            Kind::MusicSpotifyEnding => "music-spotify-ending",
            Kind::MusicSpotifyNoApp => "music-spotify-no-app",
            Kind::Integration(title, _) => title.key(),
            Kind::KeyWait => "key-wait",
            Kind::OldApp => "old-app",
        }
    }
}

/// The facts [items] turns into rows, typed. Gathered by [facts] or [facts_of]; pure from here.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Facts {
    /// Not enrolled: no rows at all (L5).
    pub not_set_up: bool,
    /// Offline or Disconnected: the rows from the last report say "at last report" (L5).
    pub report_stale: bool,
    pub restrictions_paused: bool,
    pub foreign_access: bool,
    pub do_not_disturb: bool,
    pub google_backup: bool,
    pub no_parent_pin: bool,
    pub easy_parent_pin: bool,
    pub policy_problem: bool,
    pub unseen_restarts: bool,
    pub child_lock_off: bool,
    /// Design 59: the phone's Android PIN was changed on the phone (`kid_lock::pin_changed_on_phone`).
    pub child_pin_changed: bool,
    pub blocked_apps: usize,
    pub restart_once: bool,
    /// Advanced's update-fence warnings that let other screens reach the child (design 31
    /// step 3 fix round, qa-31-s3-code M4/L4; the user can overrule). Play's install mode and an
    /// openable Play Store are the parent's choices: no row (2026-11-18).
    pub not_home_app: bool,
    pub boot_cover_not_shown: bool,
    /// Design 116 §3.4: the last report says the web filter is down (`mobile_network::filter_down`).
    pub filter_down: bool,
    /// Design 114 §5.2: the setup hold - the pause's row says "Setting up" and leads to Device › Oppsett.
    pub setup_hold: bool,
    /// Design 114 §5.2: the report's `setup.device_owner` is `false`.
    pub not_device_owner: bool,
    /// Design 114 §2.3: N while the summary's setup row is raised (`setup_status::setup_row`), outside the hold; else 0.
    pub setup_left: usize,
    /// Design 108c: the app push rows (`unified_push::health::attention`).
    pub app_push: crate::unified_push::health::AppPushAttention,
    /// From the call report, in its order (`Waiting` left out by [items]).
    pub calls: Vec<CallProblem>,
    pub ledger_unreadable: bool,
    /// The last report's `time_state.storage_full`.
    pub storage_full: bool,
    /// The last report's clock line (`time_state::TimeReport::clock_problem`).
    pub clock: Option<crate::clock_skew::ClockProblem>,
    /// Not a row: calls are managed and the launcher screens them, so a no-calls rule really
    /// blocks calls (the lock badge's "calls off", qa-31-s2-code M3).
    pub calls_enforced: bool,
    /// Device › Music's warnings (qa-31-s7-code M1).
    pub music: crate::handlers::music::MusicWarnings,
    /// Design 116 §2.3: the outside services failing behind this phone's features
    /// (`integration_health::for_device`), one per title.
    pub integrations: Vec<(crate::integration_health::Title, usize)>,
    /// Design 129b: a waiting enrolment's line and an older launcher's (`enrol_key::facts`), also for a phone not set
    /// up. Read by [facts] (the Devices list's count); the summary shows them in `#key-exception` instead.
    pub key: crate::enrol_key::KeyFacts,
}

/// The rows for one phone, alarms first, worded in `lang`.
pub fn items(lang: Lang, device_id: i64, facts: &Facts) -> Vec<AttentionItem> {
    let mut kinds: Vec<Kind> = Vec::new();
    let mut add = |on: bool, kind: Kind| {
        if on {
            kinds.push(kind);
        }
    };
    // Design 129b: a phone's enrolment waiting for the parent - a phone not set up too.
    add(facts.key.waiting_reason.is_some(), Kind::KeyWait);
    add(facts.key.old_app, Kind::OldApp);
    if facts.not_set_up {
        return kinds
            .into_iter()
            .map(|kind| key_item(lang, device_id, facts, kind))
            .collect();
    }
    add(facts.restrictions_paused, Kind::RestrictionsPaused);
    add(facts.foreign_access, Kind::ForeignAccess);
    add(facts.do_not_disturb, Kind::DoNotDisturb);
    add(facts.google_backup, Kind::GoogleBackup);
    add(facts.no_parent_pin, Kind::NoParentPin);
    add(facts.easy_parent_pin, Kind::EasyParentPin);
    add(facts.not_device_owner, Kind::NotDeviceOwner);
    add(facts.filter_down, Kind::FilterDown);
    add(facts.policy_problem, Kind::PolicyProblem);
    add(facts.unseen_restarts, Kind::UnseenRestarts);
    add(facts.child_lock_off, Kind::ChildLockOff);
    add(facts.setup_left > 0, Kind::Setup(facts.setup_left));
    add(facts.child_pin_changed, Kind::ChildPinChanged);
    add(
        facts.blocked_apps > 0,
        Kind::BlockedApps(facts.blocked_apps),
    );
    add(facts.restart_once, Kind::RestartOnce);
    add(facts.not_home_app, Kind::NotHomeApp);
    add(facts.boot_cover_not_shown, Kind::BootCoverNotShown);
    add(facts.app_push.waiting_since.is_some(), Kind::AppPushWaiting);
    add(
        !facts.app_push.refused.is_empty(),
        Kind::AppPushRefused(facts.app_push.refused.len()),
    );
    add(
        !facts.app_push.outside.is_empty(),
        Kind::AppPushOutside(facts.app_push.outside.len()),
    );
    for problem in &facts.calls {
        add(problem.needs_attention(), Kind::Calls(*problem));
    }
    add(facts.ledger_unreadable, Kind::LedgerUnreadable);
    add(facts.storage_full, Kind::StorageFull);
    if let Some(problem) = facts.clock {
        add(true, Kind::Clock(problem));
    }
    let music = &facts.music;
    add(music.other_signer, Kind::MusicOtherSigner);
    add(music.unreachable, Kind::MusicUnreachable);
    add(music.storytel_refused, Kind::MusicStorytelRefused);
    add(music.over_limit.is_some(), Kind::MusicOverLimit);
    add(
        music.failing_entries > 0,
        Kind::MusicFailing(music.failing_entries),
    );
    add(music.spotify_account.no_app, Kind::MusicSpotifyNoApp);
    add(music.spotify_account.renew, Kind::MusicSpotifyLogin);
    add(music.spotify_account.ending, Kind::MusicSpotifyEnding);
    add(music.spotify_cant_play, Kind::MusicSpotify);
    for (title, n) in &facts.integrations {
        add(*n > 0, Kind::Integration(*title, *n));
    }
    kinds
        .into_iter()
        .map(|kind| AttentionItem {
            kind,
            level: level(kind),
            title: match kind {
                Kind::RestrictionsPaused if facts.setup_hold => {
                    t(lang, "device.attention.setup_hold").to_string()
                }
                Kind::KeyWait | Kind::OldApp => key_item(lang, device_id, facts, kind).title,
                _ => title(lang, kind),
            },
            target: match kind {
                Kind::RestrictionsPaused if facts.setup_hold => {
                    format!("/devices/{device_id}/setup#open")
                }
                _ => target(kind, device_id),
            },
            at_last_report: facts.report_stale && kind.read_from_report(),
        })
        .collect()
}

/// Design 129b's rows: the line's own words, with the phone's name, to the summary's `#key-exception`.
fn key_item(lang: Lang, device_id: i64, facts: &Facts, kind: Kind) -> AttentionItem {
    let name = facts.key.name.as_str();
    let title = match kind {
        Kind::KeyWait => crate::i18n::t_params(
            lang,
            "device.key_wait",
            &[
                ("name", name),
                (
                    "reason",
                    t(
                        lang,
                        crate::enrol_key::reason_key(
                            facts.key.waiting_reason.as_deref().unwrap_or(""),
                        ),
                    ),
                ),
            ],
        ),
        _ => crate::i18n::t_params(lang, "device.old_app", &[("name", name)]),
    };
    AttentionItem {
        kind,
        level: Level::Action,
        title,
        target: format!("/devices/{device_id}#key-exception"),
        at_last_report: false,
    }
}

fn level(kind: Kind) -> Level {
    match kind {
        Kind::RestrictionsPaused
        | Kind::ForeignAccess
        | Kind::DoNotDisturb
        | Kind::GoogleBackup
        | Kind::NoParentPin
        | Kind::EasyParentPin
        | Kind::NotDeviceOwner
        | Kind::FilterDown
        | Kind::PolicyProblem
        | Kind::UnseenRestarts
        | Kind::ChildLockOff => Level::Alarm,
        Kind::BlockedApps(_)
        | Kind::Setup(_)
        | Kind::ChildPinChanged
        | Kind::RestartOnce
        | Kind::NotHomeApp
        | Kind::BootCoverNotShown
        | Kind::AppPushWaiting
        | Kind::AppPushRefused(_)
        | Kind::AppPushOutside(_)
        | Kind::Calls(_)
        | Kind::LedgerUnreadable
        | Kind::StorageFull
        | Kind::Clock(_)
        | Kind::MusicUnreachable
        | Kind::MusicOtherSigner
        | Kind::MusicStorytelRefused
        | Kind::MusicOverLimit
        | Kind::MusicFailing(_)
        | Kind::MusicSpotify
        | Kind::MusicSpotifyLogin
        | Kind::MusicSpotifyEnding
        | Kind::MusicSpotifyNoApp
        | Kind::Integration(..)
        | Kind::KeyWait
        | Kind::OldApp => Level::Action,
    }
}

/// The row's words in `lang` (`device.attention.*`).
fn title(lang: Lang, kind: Kind) -> String {
    match kind {
        Kind::RestrictionsPaused => t(lang, "device.attention.restrictions_paused"),
        Kind::ForeignAccess => t(lang, "device.attention.foreign_access"),
        Kind::DoNotDisturb => t(lang, "device.attention.do_not_disturb"),
        Kind::GoogleBackup => t(lang, "device.attention.google_backup"),
        Kind::NoParentPin => t(lang, "device.attention.no_parent_pin"),
        Kind::EasyParentPin => t(lang, "device.attention.easy_parent_pin"),
        Kind::NotDeviceOwner => t(lang, "device.attention.not_device_owner"),
        Kind::FilterDown => t(lang, "device.attention.filter_down"),
        Kind::PolicyProblem => t(lang, "device.attention.policy_problem"),
        Kind::UnseenRestarts => t(lang, "device.attention.unseen_restarts"),
        Kind::ChildLockOff => t(lang, "device.attention.child_lock_off"),
        Kind::Setup(n) => {
            return t_count(lang, "setup.summary", count(n), &[]);
        }
        Kind::ChildPinChanged => t(lang, "device.attention.child_pin_changed"),
        Kind::BlockedApps(n) => {
            return t_count(lang, "device.attention.blocked_apps", count(n), &[]);
        }
        Kind::RestartOnce => t(lang, "device.attention.restart_once"),
        Kind::NotHomeApp => t(lang, "device.attention.not_home_app"),
        Kind::BootCoverNotShown => t(lang, "device.attention.boot_cover_not_shown"),
        Kind::AppPushWaiting => t(lang, "device.attention.app_push_waiting"),
        Kind::AppPushRefused(n) => {
            return t_count(lang, "device.attention.app_push_refused", count(n), &[]);
        }
        Kind::AppPushOutside(n) => {
            return t_count(lang, "device.attention.app_push_outside", count(n), &[]);
        }
        Kind::Calls(problem) => problem.title(lang),
        Kind::LedgerUnreadable => t(lang, "device.attention.screen_time_record"),
        Kind::StorageFull => t(lang, "device.attention.storage_full"),
        Kind::Clock(problem) => {
            return problem.words(lang);
        }
        Kind::MusicUnreachable => t(lang, "device.attention.music_unreachable"),
        Kind::MusicOtherSigner => t(lang, "device.music.other_signer"),
        Kind::MusicStorytelRefused => t(lang, "device.attention.music_storytel_refused"),
        Kind::MusicOverLimit => t(lang, "device.attention.music_over_limit"),
        Kind::MusicFailing(n) => {
            return t_count(lang, "device.attention.music_failing", count(n), &[]);
        }
        Kind::MusicSpotify => t(lang, "device.attention.music_spotify"),
        Kind::MusicSpotifyLogin => t(lang, "device.attention.music_spotify_login"),
        Kind::MusicSpotifyEnding => t(lang, "device.attention.music_spotify_ending"),
        Kind::MusicSpotifyNoApp => t(lang, "device.attention.music_spotify_no_app"),
        Kind::Integration(title, n) if title.counted() => {
            return t_count(lang, title.words(), count(n), &[]);
        }
        Kind::Integration(title, _) => t(lang, title.words()),
        // Worded with the phone's name by `key_item`.
        Kind::KeyWait | Kind::OldApp => "",
    }
    .to_string()
}

fn count(n: usize) -> i64 {
    i64::try_from(n).unwrap_or(i64::MAX)
}

/// The page that fixes it: a sub-page's group, or (design 31 step 10) a group of the summary
/// itself - the quick actions with Lock again, the foreign-app warning with its steps, the blocked
/// apps with "Allow these apps…".
fn target(kind: Kind, id: i64) -> String {
    let (page, anchor) = match kind {
        Kind::RestrictionsPaused => ("", "quick"),
        Kind::ForeignAccess => ("", "foreign-access"),
        Kind::BlockedApps(_) => ("", "blocked-apps"),
        Kind::DoNotDisturb => ("/about", "sound"),
        Kind::PolicyProblem => ("/about", "software"),
        Kind::UnseenRestarts => ("/about", "diagnostics"),
        Kind::GoogleBackup => ("/advanced", "restrictions"),
        Kind::RestartOnce => ("/advanced", "app-screens"),
        Kind::NotHomeApp | Kind::BootCoverNotShown => ("/advanced", "updates"),
        Kind::NoParentPin | Kind::EasyParentPin => ("/lock", "parent-pin"),
        Kind::ChildLockOff | Kind::ChildPinChanged => ("/lock", "child-pin"),
        Kind::NotDeviceOwner => ("/setup", "step-device_owner"),
        Kind::FilterDown => ("/filter", "filter"),
        Kind::Setup(_) => {
            return format!("/devices/{id}/setup");
        }
        Kind::AppPushWaiting | Kind::AppPushRefused(_) | Kind::AppPushOutside(_) => {
            ("/apps", "app-push")
        }
        Kind::Calls(_) => {
            return format!("/devices/{id}/calls");
        }
        // Device › Time's warnings at the top of the page (step 9): no fragment needed.
        Kind::LedgerUnreadable | Kind::StorageFull | Kind::Clock(_) => {
            return format!("/devices/{id}/time");
        }
        // Device › Music's warnings at the top of the page: no fragment needed.
        Kind::MusicUnreachable | Kind::MusicOtherSigner => {
            return format!("/devices/{id}/music");
        }
        // The others above their own group (its header) or by their rows.
        Kind::MusicStorytelRefused => ("/music", "storytel-title"),
        Kind::MusicOverLimit => ("/music", "storage-title"),
        Kind::MusicFailing(_) => ("/music", "library"),
        Kind::MusicSpotify
        | Kind::MusicSpotifyLogin
        | Kind::MusicSpotifyEnding
        | Kind::MusicSpotifyNoApp => ("/music", "music-spotify"),
        Kind::Integration(title, _) => {
            return title.target(id);
        }
        Kind::KeyWait | Kind::OldApp => ("", "key-exception"),
    };
    format!("/devices/{id}{page}#{anchor}")
}

/// A rule's "calls off" is real: calls are managed and the call report doesn't say nothing screens
/// them. The lock badge's "· calls off" on every page reads it (qa-31-s9-code N1).
pub fn calls_enforced(policy: &DevicePolicy, problems: &[CallProblem]) -> bool {
    policy.calls_managed() && !problems.contains(&CallProblem::AnyoneCanCall)
}

/// What [facts_of] reads; the summary passes the parts it has built.
pub struct Sources<'a> {
    pub device: &'a Device,
    pub policy: &'a DevicePolicy,
    pub latest: Option<&'a DeviceStatus>,
    pub relock: &'a RelockCard,
    /// The call report's problems.
    pub call_problems: &'a [CallProblem],
    /// The Do Not Disturb the report shows is a silent rule's own ([dnd_is_rules_own]): no row.
    pub dnd_is_rules_own: bool,
    pub connection: ConnectionState,
    /// The music entries switched on for the phone (`music::ticked_entries`).
    pub music_ticked: &'a [i64],
    /// The Spotify account it plays as, from the accounts table (`music::spotify_account_warnings`).
    pub spotify_account: crate::handlers::music::SpotifyAccountWarnings,
    /// The app push rows (`unified_push::health::attention`), read by the caller.
    pub app_push: &'a crate::unified_push::health::AppPushAttention,
    /// The newest `phone_on`'s instant (`parent_events::last_phone_on_ms`): the boot cover's "not shown" waits for a
    /// start after the arm.
    pub last_phone_on_ms: Option<i64>,
    /// Design 116: `integration_health::for_device`, read by the caller.
    pub integrations: &'a [(crate::integration_health::Title, usize)],
    /// The integrations' part of the setup steps (`Integrations::setup`; design 110's map steps, design 114 §2.6).
    pub setup_part: &'a crate::integrations::SetupPart,
    /// Design 132 §4.5: a `set_kid_pin` is waiting or in flight (`kid_lock::pin_on_its_way`), read by the caller.
    pub pin_on_its_way: bool,
}

/// Whether the reported Do Not Disturb is the launcher's own for a silent time rule: the report's
/// `active_rule_id` names a rule whose `silent` is on and the filter is `priority`, which is what that
/// rule sets. `rules` are the served rows (`lock_now::badge_rows`): a "Slå på nå" copy (a negative id)
/// is among them with its rule's `silent` (design 123 QA M1: also when the rule is switched off for the
/// phone); a copy no longer served is read by its original among them. Anything that can't be read is
/// "no", so the row shows.
pub async fn dnd_is_rules_own(
    db: &sqlx::SqlitePool,
    device_id: i64,
    latest: Option<&DeviceStatus>,
    rules: &[crate::time_rules::TimeRuleRow],
) -> bool {
    let Some(latest) = latest else { return false };
    if latest.interruption_filter.as_deref() != Some("priority") {
        return false;
    }
    let Some(active) = latest
        .time_state_json
        .as_deref()
        .and_then(crate::time_state::TimeReport::parse)
        .and_then(|report| report.active_rule_id)
    else {
        return false;
    };
    if rules.iter().any(|rule| rule.id == active && rule.silent) {
        return true;
    }
    let original = if active < 0 {
        match sqlx::query_scalar::<_, Option<i64>>(
            "SELECT rule_id FROM time_lock_now WHERE id = ? AND device_id = ?",
        )
        .bind(-active)
        .bind(device_id)
        .fetch_optional(db)
        .await
        {
            Ok(Some(Some(rule_id))) => rule_id,
            _ => return false,
        }
    } else {
        active
    };
    rules.iter().any(|rule| rule.id == original && rule.silent)
}

/// Reads what [items] needs. A read that fails counts as "nothing to say" for its row (logged by
/// the builder that failed); the card it belongs to shows the error.
pub async fn facts(state: &AppState, device: &Device, connection: ConnectionState) -> Facts {
    let key = crate::enrol_key::facts(&state.db, device.id, &device.name).await;
    if device.enrolled_at.is_none() {
        return Facts {
            not_set_up: true,
            key,
            ..Default::default()
        };
    }
    let mut facts = facts_enrolled(state, device, connection).await;
    facts.key = key;
    facts
}

async fn facts_enrolled(state: &AppState, device: &Device, connection: ConnectionState) -> Facts {
    let id = device.id;
    let Some(policy) =
        sqlx::query_as::<_, DevicePolicy>("SELECT * FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await
            .unwrap_or_else(|err| {
                tracing::error!(device_id = id, %err, "attention: can't read the policy");
                None
            })
    else {
        return Facts::default();
    };
    let latest = sqlx::query_as::<_, DeviceStatus>(
        "SELECT * FROM device_status WHERE device_id = ? ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await
    .unwrap_or_else(|err| {
        tracing::error!(device_id = id, %err, "attention: can't read the last report");
        None
    });
    let latest = latest.as_ref();
    let now_ms = chrono::Utc::now().timestamp_millis();
    let allowed: Option<std::collections::HashSet<String>> = policy
        .allowlist_json
        .as_deref()
        .and_then(|json| serde_json::from_str::<Vec<String>>(json).ok())
        .map(|list| list.into_iter().collect());
    // Only the counts are read here, so the zone doesn't matter.
    let relock = crate::handlers::window_relock::card(
        &state.db,
        id,
        latest,
        None,
        &crate::display_time::Zone::utc_fallback(),
        chrono::Utc::now(),
        allowed.as_ref(),
    )
    .await
    // The badge only counts; the summary itself fails on this (qa-31-s10-code L4).
    .unwrap_or_else(|err| {
        tracing::error!(device_id = id, %err, "attention: can't read the relock parts");
        crate::handlers::window_relock::RelockCard::default()
    });
    let call_problems = match crate::handlers::calls::call_report(state, &policy).await {
        Ok(report) => report.problems(),
        Err(err) => {
            tracing::error!(device_id = id, %err, "attention: can't read the call report");
            Vec::new()
        }
    };
    // The Do Not Disturb row reads the served rules (a rule turned on now among them).
    let rules = crate::lock_now::badge_rows(&state.db, id, chrono::Utc::now()).await;
    let dnd_is_rules_own = dnd_is_rules_own(&state.db, id, latest, &rules).await;
    let music_ticked = crate::handlers::music::ticked_entries(&state.db, id)
        .await
        .unwrap_or_else(|err| {
            tracing::error!(device_id = id, %err, "attention: can't read the music ticks");
            Vec::new()
        });
    let spotify_account = crate::handlers::music::spotify_account_warnings(state, &policy)
        .await
        .unwrap_or_else(|err| {
            tracing::error!(device_id = id, %err, "attention: can't read the Spotify account");
            Default::default()
        });
    let app_push = crate::unified_push::health::attention(
        state,
        id,
        latest.map(|st| st.reported_at.as_str()),
        chrono::Utc::now(),
    )
    .await
    .unwrap_or_else(|err| {
        tracing::error!(device_id = id, %err, "attention: can't read the app push state");
        Default::default()
    });
    let last_phone_on_ms = crate::parent_events::last_phone_on_ms(&state.db, id)
        .await
        .unwrap_or_else(|err| {
            tracing::error!(device_id = id, %err, "attention: can't read the last start");
            None
        });
    let integrations = crate::integration_health::for_device(&state.db, id, chrono::Utc::now())
        .await
        .unwrap_or_else(|err| {
            tracing::error!(device_id = id, %err, "attention: can't read the integrations' health");
            Vec::new()
        });
    let setup_part = state.integrations.setup(state, id).await;
    let pin_on_its_way = crate::kid_lock::pin_on_its_way(&state.db, id).await;
    facts_of(
        &Sources {
            device,
            policy: &policy,
            latest,
            relock: &relock,
            call_problems: &call_problems,
            dnd_is_rules_own,
            connection,
            music_ticked: &music_ticked,
            spotify_account,
            app_push: &app_push,
            last_phone_on_ms,
            integrations: &integrations,
            setup_part: &setup_part,
            pin_on_its_way,
        },
        now_ms,
    )
}

/// [Facts] from what is already read.
pub fn facts_of(s: &Sources, now_ms: i64) -> Facts {
    if s.device.enrolled_at.is_none() {
        return Facts {
            not_set_up: true,
            ..Default::default()
        };
    }
    let (policy, latest) = (s.policy, s.latest);
    let lock_state =
        crate::kid_lock::parse_lock_state(latest.and_then(|st| st.lock_state_json.as_deref()));
    let lock_phone = crate::kid_lock::PhoneLock {
        state: lock_state.as_ref(),
        android_capable: crate::handlers::device_pages::has_capability(
            latest,
            crate::kid_lock::ANDROID_KEYGUARD_CAPABILITY,
        ),
        pin_on_its_way: s.pin_on_its_way,
        no_pin: crate::setup_status::reported_child_pin(latest) == Some(Some(false)),
    };
    let escapes =
        crate::handlers::device_pages::escapes_card(policy, latest, now_ms, s.last_phone_on_ms)
            .notes;
    let (setup_missing, setup_readings) =
        crate::setup_status::state_of(policy, latest, s.setup_part, &[]);
    let setup_row = crate::setup_status::setup_row(&setup_missing, &setup_readings);
    Facts {
        key: Default::default(),
        not_set_up: false,
        report_stale: matches!(
            s.connection,
            ConnectionState::Offline { .. } | ConnectionState::Disconnected
        ),
        restrictions_paused: latest.is_some_and(|st| st.paused_at(now_ms)),
        foreign_access: s.relock.foreign.is_some(),
        do_not_disturb: !s.dnd_is_rules_own
            && crate::sound_mode::state(
                None,
                latest.and_then(|st| st.interruption_filter.as_deref()),
            )
            .dnd
            .is_some_and(crate::sound_mode::Dnd::is_on),
        google_backup: latest.and_then(|st| st.backup_service_enabled) == Some(true),
        no_parent_pin: crate::handlers::devices::managed_without_pin(policy),
        easy_parent_pin: policy.override_pin_hash.is_some()
            && policy.override_pin_easy == Some(true),
        policy_problem: latest
            .and_then(|st| st.policy_state.as_deref())
            .is_some_and(|state| state != "ok"),
        unseen_restarts: latest.is_some_and(|st| {
            crate::boot_witness::unseen(
                st.unseen_boots_count,
                st.unseen_boots_last_at_ms,
                now_ms,
                policy.hardening.disallow_safe_boot,
                None,
            )
            .is_some()
        }),
        // Lock Screen & PINs' own notes, so the rows and the page agree.
        child_lock_off: crate::kid_lock::lock_off(policy, &lock_phone),
        child_pin_changed: crate::kid_lock::pin_changed_on_phone(policy, &lock_phone),
        blocked_apps: s.relock.blocks.waiting,
        restart_once: !crate::models::admin_policy_effects(
            latest.and_then(|st| st.admin_policies_pending_json.as_deref()),
        )
        .is_empty(),
        // The same notes Advanced shows, so the row and the page agree.
        not_home_app: escapes.contains(&EscapeNote::PartialFence),
        boot_cover_not_shown: escapes
            .iter()
            .any(|n| matches!(n, EscapeNote::BootCoverNotShown { .. })),
        filter_down: crate::mobile_network::filter_down(policy, latest, now_ms).is_some(),
        // Design 114: the one setup rule, as Device › Oppsett reads it. Ticks don't count here (no required step is
        // ticked), so no read of them. Held back in the setup hold (§5.2) and for a phone that isn't device owner
        // (QA M4: its own row stands alone).
        setup_hold: latest.is_some_and(|st| crate::setup_status::in_hold(st, now_ms)),
        not_device_owner: setup_readings.get("device_owner").copied().flatten() == Some(false),
        setup_left: if setup_row
            && !latest.is_some_and(|st| crate::setup_status::in_hold(st, now_ms))
        {
            crate::setup_status::count(&setup_missing)
        } else {
            0
        },
        app_push: s.app_push.clone(),
        calls: s.call_problems.to_vec(),
        // Device › Time reads the same typed report.
        ledger_unreadable: latest
            .and_then(|st| st.time_state_json.as_deref())
            .and_then(crate::time_state::TimeReport::parse)
            .is_some_and(|report| report.ledger_unreadable),
        storage_full: latest
            .and_then(|st| st.time_state_json.as_deref())
            .and_then(crate::time_state::TimeReport::parse)
            .is_some_and(|report| report.storage_full),
        // Device › Time reads the same line.
        clock: latest
            .and_then(|st| st.time_state_json.as_deref())
            .and_then(crate::time_state::TimeReport::parse)
            .and_then(|report| report.clock_problem()),
        calls_enforced: calls_enforced(policy, s.call_problems),
        // Device › Music's own warnings, so the rows and the page agree.
        music: {
            // Only whether each warning holds: no words needed.
            let mut music =
                crate::handlers::music::music_warnings(Lang::En, policy, latest, s.music_ticked);
            music.spotify_account = s.spotify_account;
            // A login to renew is one row: the phone's `reauthorize` says the same.
            let reported = crate::music::parse_music_state(
                latest.and_then(|st| st.music_state_json.as_deref()),
            )
            .and_then(|report| report.spotify);
            if music.spotify_account.renew && reported.as_deref() == Some("reauthorize") {
                music.spotify_cant_play = false;
            }
            music
        },
        integrations: s.integrations.to_vec(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn everything() -> Facts {
        Facts {
            restrictions_paused: true,
            foreign_access: true,
            do_not_disturb: true,
            google_backup: true,
            no_parent_pin: true,
            easy_parent_pin: true,
            policy_problem: true,
            unseen_restarts: true,
            child_lock_off: true,
            child_pin_changed: true,
            blocked_apps: 2,
            restart_once: true,
            not_home_app: true,
            boot_cover_not_shown: true,
            not_device_owner: true,
            filter_down: true,
            setup_left: 3,
            app_push: crate::unified_push::health::AppPushAttention {
                waiting_since: Some(chrono::Utc::now()),
                refused: vec![("a.b".to_string(), "2026-11-17 10:00:00".to_string())],
                outside: vec!["c.d".to_string(), "e.f".to_string()],
            },
            calls: vec![CallProblem::Waiting, CallProblem::AnyoneCanCall],
            ledger_unreadable: true,
            music: crate::handlers::music::MusicWarnings {
                unreachable: true,
                other_signer: true,
                storytel_refused: true,
                over_limit: Some(("3.2 GB".to_string(), 2)),
                failing_entries: 2,
                spotify_cant_play: true,
                spotify_account: crate::handlers::music::SpotifyAccountWarnings {
                    no_app: true,
                    renew: true,
                    ending: true,
                },
            },
            integrations: vec![(crate::integration_health::Title::Blocklist, 2)],
            ..Default::default()
        }
    }

    #[test]
    fn nothing_to_say_is_no_rows() {
        assert!(items(Lang::En, 3, &Facts::default()).is_empty());
        // "Waiting for the phone" isn't a problem.
        let waiting = Facts {
            calls: vec![CallProblem::Waiting],
            ..Default::default()
        };
        assert!(items(Lang::En, 3, &waiting).is_empty());
    }

    #[test]
    fn a_phone_not_set_up_has_no_rows() {
        let new = Facts {
            not_set_up: true,
            ..everything()
        };
        assert!(items(Lang::En, 3, &new).is_empty());
    }

    #[test]
    fn a_stale_phones_report_rows_say_at_last_report() {
        let rows = items(Lang::En, 3, &everything());
        assert!(rows.iter().all(|r| !r.at_last_report));
        let stale = Facts {
            report_stale: true,
            ..everything()
        };
        let rows = items(Lang::En, 3, &stale);
        assert_eq!(
            rows.len(),
            items(Lang::En, 3, &everything()).len(),
            "they still count"
        );
        let current: Vec<&str> = rows
            .iter()
            .filter(|r| !r.at_last_report)
            .map(AttentionItem::key)
            .collect();
        assert_eq!(
            current,
            [
                "no-parent-pin",
                "easy-parent-pin",
                // Design 108c: the server's own data, never "at last report".
                "app-push-waiting",
                "app-push-refused",
                "app-push-outside",
                // Design 116: the server's own data too.
                "integration-blocklist"
            ]
        );
    }

    #[test]
    fn alarms_come_first_in_the_specs_order() {
        let rows = items(Lang::En, 3, &everything());
        let titles: Vec<&str> = rows.iter().map(|r| r.title.as_str()).collect();
        assert_eq!(
            titles,
            [
                "Restrictions paused",
                "Unknown app may read the Parent PIN",
                "Do Not Disturb is on",
                "Backup to Google is on",
                "No Parent PIN set",
                "Parent PIN easy to guess",
                "Vibb One doesn't manage the phone",
                "Web filter is down",
                "Phone can't apply its settings",
                "Phone started without Vibb One",
                "Lock is off on the phone",
                "Setup: 3 steps left",
                "Phone's PIN isn't the Child PIN",
                "2 blocked apps wait",
                "Restart the phone once",
                "Vibb One isn't the Home app",
                "Boot cover wasn't shown",
                "App notifications aren't reaching the phone",
                "1 app's notifications are refused",
                "2 apps' notifications go via an outside service",
                "Anyone can call this phone",
                "Screen-time record unreadable",
                "The music app is not from Vibb",
                "Music app not reachable",
                "Storytel refused the phone's login",
                "Music over the storage limit",
                "2 music entries fail on the phone",
                "No Spotify app set up",
                "Spotify login needs renewing",
                "Spotify login ends soon",
                "Spotify can't play on this phone",
                "2 blocklists aren't updating",
            ]
        );
        let first_action = rows.iter().position(|r| r.level == Level::Action).unwrap();
        assert!(rows[..first_action].iter().all(|r| r.level == Level::Alarm));
        assert!(
            rows[first_action..]
                .iter()
                .all(|r| r.level == Level::Action)
        );
        assert_eq!(rows[0].level_css(), "alarm");
        assert_eq!(rows[first_action].level_css(), "action");
        // Each kind has its own key; the calls rows share "calls" (several can show at once).
        let mut with_calls = everything();
        with_calls.calls.push(CallProblem::FailClosed);
        let rows = items(Lang::En, 3, &with_calls);
        let keys: std::collections::HashSet<&str> = rows.iter().map(AttentionItem::key).collect();
        assert_eq!(keys.len(), rows.len() - 1);
        assert_eq!(rows.iter().filter(|r| r.key() == "calls").count(), 2);
    }

    #[test]
    fn titles_are_short_facts_without_package_names() {
        let mut kinds: Vec<Kind> = items(Lang::En, 3, &everything())
            .into_iter()
            .map(|r| r.kind)
            .collect();
        kinds.push(Kind::BlockedApps(1));
        kinds.push(Kind::Setup(1));
        kinds.push(Kind::MusicFailing(1));
        kinds.push(Kind::AppPushRefused(1));
        kinds.push(Kind::AppPushOutside(1));
        kinds.extend(CallProblem::ALL.iter().map(|p| Kind::Calls(*p)));
        for kind in kinds {
            let title = title(Lang::En, kind);
            let words = title.split_whitespace().count();
            assert!((1..=8).contains(&words), "{title}");
            assert!(!title.contains('.') && !title.ends_with('!'), "{title}");
            for jargon in ["launcher", "kiosk", "policy", "sync", "token", "MDM", "adb"] {
                assert!(
                    !title.to_lowercase().contains(&jargon.to_lowercase()),
                    "{title}"
                );
            }
        }
        assert_eq!(title(Lang::En, Kind::BlockedApps(1)), "1 blocked app waits");
        assert_eq!(title(Lang::En, Kind::Setup(1)), "Setup: 1 step left");
        assert_eq!(title(Lang::Nb, Kind::Setup(4)), "Oppsett: 4 steg igjen");
        assert_eq!(
            title(Lang::En, Kind::MusicFailing(1)),
            "1 music entry fails on the phone"
        );
        assert_eq!(
            title(Lang::Nb, Kind::AppPushRefused(3)),
            "Varsler til 3 apper blir avvist"
        );
        assert_eq!(
            title(Lang::Nb, Kind::AppPushOutside(1)),
            "Varsler til 1 app går via en ekstern tjeneste"
        );
        assert_eq!(
            title(Lang::Nb, Kind::AppPushWaiting),
            "Appvarsler kommer ikke fram til telefonen"
        );
    }

    #[test]
    fn rows_link_to_the_card_that_fixes_them() {
        let rows = items(Lang::En, 7, &everything());
        let target = |key: &str| {
            rows.iter()
                .find(|r| r.key() == key)
                .map(|r| r.target.as_str())
                .unwrap()
        };
        // Step 10: the summary's own groups.
        assert_eq!(target("restrictions-paused"), "/devices/7#quick");
        assert_eq!(target("foreign-access"), "/devices/7#foreign-access");
        assert_eq!(target("blocked-apps"), "/devices/7#blocked-apps");
        // Step 4: Lock Screen & PINs.
        assert_eq!(target("easy-parent-pin"), "/devices/7/lock#parent-pin");
        assert_eq!(target("no-parent-pin"), "/devices/7/lock#parent-pin");
        // Design 31 step 3: About This Phone and Advanced.
        assert_eq!(target("do-not-disturb"), "/devices/7/about#sound");
        assert_eq!(target("policy-problem"), "/devices/7/about#software");
        assert_eq!(target("unseen-restarts"), "/devices/7/about#diagnostics");
        assert_eq!(target("google-backup"), "/devices/7/advanced#restrictions");
        assert_eq!(target("restart-once"), "/devices/7/advanced#app-screens");
        assert_eq!(target("not-home-app"), "/devices/7/advanced#updates");
        assert_eq!(
            target("boot-cover-not-shown"),
            "/devices/7/advanced#updates"
        );
        assert_eq!(target("child-lock-off"), "/devices/7/lock#child-pin");
        // Design 114: Device › Oppsett.
        assert_eq!(target("setup"), "/devices/7/setup");
        assert_eq!(
            target("not-device-owner"),
            "/devices/7/setup#step-device_owner"
        );
        // Design 108c: Device › Apps' App push group.
        for key in ["app-push-waiting", "app-push-refused", "app-push-outside"] {
            assert_eq!(target(key), "/devices/7/apps#app-push");
        }
        // Step 9: Device › Time, its warnings at the top.
        assert_eq!(target("screen-time-record"), "/devices/7/time");
        assert_eq!(target("calls"), "/devices/7/calls");
        // Step 7's fix round: Device › Music - the top warnings without a fragment, the others at
        // their group.
        assert_eq!(target("music-unreachable"), "/devices/7/music");
        assert_eq!(target("music-other-signer"), "/devices/7/music");
        assert_eq!(
            target("music-storytel-refused"),
            "/devices/7/music#storytel-title"
        );
        assert_eq!(target("music-over-limit"), "/devices/7/music#storage-title");
        assert_eq!(target("music-entries"), "/devices/7/music#library");
        // Design 116: the phone's Web Filter page; an integration's page that says what the error is.
        assert_eq!(target("filter-down"), "/devices/7/filter#filter");
        assert_eq!(target("integration-blocklist"), "/dns");
    }

    #[test]
    fn the_setup_hold_is_the_pauses_row_with_its_own_words() {
        let held = Facts {
            restrictions_paused: true,
            setup_hold: true,
            ..Default::default()
        };
        let rows = items(Lang::Nb, 7, &held);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].key(), "restrictions-paused");
        assert_eq!(rows[0].level, Level::Alarm);
        assert_eq!(rows[0].title, "I oppsett, uten begrensninger");
        assert_eq!(rows[0].target, "/devices/7/setup#open");
        let paused = Facts {
            restrictions_paused: true,
            ..Default::default()
        };
        let rows = items(Lang::En, 7, &paused);
        assert_eq!(rows[0].title, "Restrictions paused");
        assert_eq!(rows[0].target, "/devices/7#quick");
    }
}
