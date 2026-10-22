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
use crate::handlers::device_pages::PlayNote;
use crate::handlers::window_relock::RelockCard;
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
    AccessRevoked,
    PolicyProblem,
    UnseenRestarts,
    IncomingCallsNotBlocked,
    ChildLockOff,
    /// Design 59 §2.3: Android's lock on the phone opens with a PIN that isn't the Child PIN.
    ChildPinChanged,
    /// How many apps the phone keeps hidden.
    BlockedApps(usize),
    RestartOnce,
    /// Play install mode is on: the Play Store opens on the phone.
    PlayInstallMode,
    /// Android won't pause Play and "Block other apps' screens" is off: apps can open it.
    PlayStoreOpenable,
    /// The launcher isn't Android's Home app: the update fence is partial.
    NotHomeApp,
    /// The boot cover was armed but not shown: another home screen came up at boot.
    BootCoverNotShown,
    NoNotificationAccess,
    Calls(CallProblem),
    LedgerUnreadable,
    LauncherWithoutRules,
    /// Device › Music's warnings (design 31 step 7 fix round, qa-31-s7-code M1), from
    /// `music::music_warnings` like the page's: Vibb without music while entries are on, the music
    /// app not reachable, Storytel's login refused, over the storage limit, the old mobile-data
    /// switch, entries the phone can't fetch (one row for all).
    MusicCantDeliver,
    MusicUnreachable,
    MusicStorytelRefused,
    MusicOverLimit,
    MusicOlderPhone(crate::music::OlderApps),
    MusicFailing(usize),
    /// The phone's Spotify account can't play (design 26c §4.4: the login, the app, Premium or
    /// the phone's web player) - the music app's tiles say "Spør en voksen".
    MusicSpotify,
    /// The account the phone plays as, from the accounts table (qa-26c-merge2-code L3): its login
    /// to renew, its login ending within 14 days, two phones on it, no Spotify app set up.
    MusicSpotifyLogin,
    MusicSpotifyEnding,
    MusicSpotifyShared,
    MusicSpotifyNoApp,
}

impl Kind {
    /// Read from the phone's last report (so possibly out of date), not from this server's own
    /// settings.
    fn read_from_report(self) -> bool {
        !matches!(
            self,
            Kind::NoParentPin
                | Kind::EasyParentPin
                | Kind::AccessRevoked
                | Kind::IncomingCallsNotBlocked
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
            Kind::AccessRevoked => "access-revoked",
            Kind::PolicyProblem => "policy-problem",
            Kind::UnseenRestarts => "unseen-restarts",
            Kind::IncomingCallsNotBlocked => "incoming-calls",
            Kind::ChildLockOff => "child-lock-off",
            Kind::ChildPinChanged => "child-pin-changed",
            Kind::BlockedApps(_) => "blocked-apps",
            Kind::RestartOnce => "restart-once",
            Kind::PlayInstallMode => "play-install-mode",
            Kind::PlayStoreOpenable => "play-store-openable",
            Kind::NotHomeApp => "not-home-app",
            Kind::BootCoverNotShown => "boot-cover-not-shown",
            Kind::NoNotificationAccess => "notification-access",
            Kind::Calls(_) => "calls",
            Kind::LedgerUnreadable => "screen-time-record",
            Kind::LauncherWithoutRules => "launcher-without-rules",
            Kind::MusicCantDeliver => "music-cant-deliver",
            Kind::MusicUnreachable => "music-unreachable",
            Kind::MusicStorytelRefused => "music-storytel-refused",
            Kind::MusicOverLimit => "music-over-limit",
            Kind::MusicOlderPhone(_) => "music-older-phone",
            Kind::MusicFailing(_) => "music-entries",
            Kind::MusicSpotify => "music-spotify",
            Kind::MusicSpotifyLogin => "music-spotify-login",
            Kind::MusicSpotifyEnding => "music-spotify-ending",
            Kind::MusicSpotifyShared => "music-spotify-shared",
            Kind::MusicSpotifyNoApp => "music-spotify-no-app",
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
    pub access_revoked: bool,
    pub policy_problem: bool,
    pub unseen_restarts: bool,
    pub incoming_calls_not_blocked: bool,
    pub child_lock_off: bool,
    /// Design 59: the phone's Android PIN was changed on the phone (`kid_lock::pin_changed_on_phone`).
    pub child_pin_changed: bool,
    pub blocked_apps: usize,
    pub restart_once: bool,
    /// Advanced's Play and update-fence warnings that let other screens reach the child (design 31
    /// step 3 fix round, qa-31-s3-code M4/L4; the user can overrule).
    pub play_install_mode: bool,
    pub play_store_openable: bool,
    pub not_home_app: bool,
    pub boot_cover_not_shown: bool,
    pub no_notification_access: bool,
    /// From the call report, in its order (`Waiting` left out by [items]).
    pub calls: Vec<CallProblem>,
    pub ledger_unreadable: bool,
    pub launcher_without_rules: bool,
    /// Not a row: calls are managed and the launcher screens them, so a no-calls rule really
    /// blocks calls (the lock badge's "calls off", qa-31-s2-code M3).
    pub calls_enforced: bool,
    /// Device › Music's warnings (qa-31-s7-code M1).
    pub music: crate::handlers::music::MusicWarnings,
}

/// The rows for one phone, alarms first.
pub fn items(device_id: i64, facts: &Facts) -> Vec<AttentionItem> {
    if facts.not_set_up {
        return Vec::new();
    }
    let mut kinds: Vec<Kind> = Vec::new();
    let mut add = |on: bool, kind: Kind| {
        if on {
            kinds.push(kind);
        }
    };
    add(facts.restrictions_paused, Kind::RestrictionsPaused);
    add(facts.foreign_access, Kind::ForeignAccess);
    add(facts.do_not_disturb, Kind::DoNotDisturb);
    add(facts.google_backup, Kind::GoogleBackup);
    add(facts.no_parent_pin, Kind::NoParentPin);
    add(facts.easy_parent_pin, Kind::EasyParentPin);
    add(facts.access_revoked, Kind::AccessRevoked);
    add(facts.policy_problem, Kind::PolicyProblem);
    add(facts.unseen_restarts, Kind::UnseenRestarts);
    add(
        facts.incoming_calls_not_blocked,
        Kind::IncomingCallsNotBlocked,
    );
    add(facts.child_lock_off, Kind::ChildLockOff);
    add(facts.child_pin_changed, Kind::ChildPinChanged);
    add(
        facts.blocked_apps > 0,
        Kind::BlockedApps(facts.blocked_apps),
    );
    add(facts.restart_once, Kind::RestartOnce);
    add(facts.play_install_mode, Kind::PlayInstallMode);
    add(facts.play_store_openable, Kind::PlayStoreOpenable);
    add(facts.not_home_app, Kind::NotHomeApp);
    add(facts.boot_cover_not_shown, Kind::BootCoverNotShown);
    add(facts.no_notification_access, Kind::NoNotificationAccess);
    for problem in &facts.calls {
        add(problem.needs_attention(), Kind::Calls(*problem));
    }
    add(facts.ledger_unreadable, Kind::LedgerUnreadable);
    add(facts.launcher_without_rules, Kind::LauncherWithoutRules);
    let music = &facts.music;
    add(music.cant_deliver, Kind::MusicCantDeliver);
    add(music.unreachable, Kind::MusicUnreachable);
    add(music.storytel_refused, Kind::MusicStorytelRefused);
    add(music.over_limit.is_some(), Kind::MusicOverLimit);
    if let Some(apps) = music.older_phone {
        add(true, Kind::MusicOlderPhone(apps));
    }
    add(
        music.failing_entries > 0,
        Kind::MusicFailing(music.failing_entries),
    );
    add(music.spotify_account.no_app, Kind::MusicSpotifyNoApp);
    add(music.spotify_account.renew, Kind::MusicSpotifyLogin);
    add(music.spotify_account.ending, Kind::MusicSpotifyEnding);
    add(music.spotify_account.shared, Kind::MusicSpotifyShared);
    add(music.spotify_cant_play, Kind::MusicSpotify);
    kinds
        .into_iter()
        .map(|kind| AttentionItem {
            kind,
            level: level(kind),
            title: title(kind),
            target: target(kind, device_id),
            at_last_report: facts.report_stale && kind.read_from_report(),
        })
        .collect()
}

fn level(kind: Kind) -> Level {
    match kind {
        Kind::RestrictionsPaused
        | Kind::ForeignAccess
        | Kind::DoNotDisturb
        | Kind::GoogleBackup
        | Kind::NoParentPin
        | Kind::EasyParentPin
        | Kind::AccessRevoked
        | Kind::PolicyProblem
        | Kind::UnseenRestarts
        | Kind::IncomingCallsNotBlocked
        | Kind::ChildLockOff => Level::Alarm,
        Kind::BlockedApps(_)
        | Kind::ChildPinChanged
        | Kind::RestartOnce
        | Kind::PlayInstallMode
        | Kind::PlayStoreOpenable
        | Kind::NotHomeApp
        | Kind::BootCoverNotShown
        | Kind::NoNotificationAccess
        | Kind::Calls(_)
        | Kind::LedgerUnreadable
        | Kind::LauncherWithoutRules
        | Kind::MusicCantDeliver
        | Kind::MusicUnreachable
        | Kind::MusicStorytelRefused
        | Kind::MusicOverLimit
        | Kind::MusicOlderPhone(_)
        | Kind::MusicFailing(_)
        | Kind::MusicSpotify
        | Kind::MusicSpotifyLogin
        | Kind::MusicSpotifyEnding
        | Kind::MusicSpotifyShared
        | Kind::MusicSpotifyNoApp => Level::Action,
    }
}

fn title(kind: Kind) -> String {
    match kind {
        Kind::RestrictionsPaused => "Restrictions paused",
        Kind::ForeignAccess => "Unknown app may read the Parent PIN",
        Kind::DoNotDisturb => "Do Not Disturb is on",
        Kind::GoogleBackup => "Backup to Google is on",
        Kind::NoParentPin => "No Parent PIN set",
        Kind::EasyParentPin => "Parent PIN easy to guess",
        Kind::AccessRevoked => "Access removed",
        Kind::PolicyProblem => "Phone can't apply its settings",
        Kind::UnseenRestarts => "Phone started without Vibb One",
        Kind::IncomingCallsNotBlocked => "Incoming calls aren't blocked",
        Kind::ChildLockOff => "Lock is off on the phone",
        Kind::ChildPinChanged => "Phone's PIN isn't the Child PIN",
        Kind::BlockedApps(1) => "1 blocked app waits",
        Kind::BlockedApps(n) => return format!("{n} blocked apps wait"),
        Kind::RestartOnce => "Restart the phone once",
        Kind::PlayInstallMode => "Play Store open (install mode)",
        Kind::PlayStoreOpenable => "Play Store can be opened",
        Kind::NotHomeApp => "Vibb One isn't the Home app",
        Kind::BootCoverNotShown => "Boot cover wasn't shown",
        Kind::NoNotificationAccess => "Notification access off",
        Kind::Calls(problem) => problem.title(),
        Kind::LedgerUnreadable => "Screen-time record unreadable",
        Kind::LauncherWithoutRules => "Vibb One too old for time rules",
        Kind::MusicCantDeliver => "Vibb One can't deliver music yet",
        Kind::MusicUnreachable => "Music app not reachable",
        Kind::MusicStorytelRefused => "Storytel refused the phone's login",
        Kind::MusicOverLimit => "Music over the storage limit",
        Kind::MusicOlderPhone(apps) => apps.title(),
        Kind::MusicFailing(1) => "1 music entry fails on the phone",
        Kind::MusicFailing(n) => return format!("{n} music entries fail on the phone"),
        Kind::MusicSpotify => "Spotify can't play on this phone",
        Kind::MusicSpotifyLogin => "Spotify login needs renewing",
        Kind::MusicSpotifyEnding => "Spotify login ends soon",
        Kind::MusicSpotifyShared => "Two phones share a Spotify account",
        Kind::MusicSpotifyNoApp => "No Spotify app set up",
    }
    .to_string()
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
        Kind::AccessRevoked => ("/advanced", "connection"),
        Kind::RestartOnce | Kind::PlayInstallMode | Kind::PlayStoreOpenable => {
            ("/advanced", "app-screens")
        }
        Kind::NotHomeApp | Kind::BootCoverNotShown => ("/advanced", "updates"),
        Kind::NoParentPin | Kind::EasyParentPin => ("/lock", "parent-pin"),
        Kind::ChildLockOff | Kind::ChildPinChanged => ("/lock", "child-pin"),
        Kind::NoNotificationAccess => ("/home", "layout"),
        Kind::IncomingCallsNotBlocked | Kind::Calls(_) => {
            return format!("/devices/{id}/calls");
        }
        // Device › Time's warnings at the top of the page (step 9): no fragment needed.
        Kind::LedgerUnreadable | Kind::LauncherWithoutRules => {
            return format!("/devices/{id}/time");
        }
        // Device › Music's warnings at the top of the page: no fragment needed.
        Kind::MusicCantDeliver | Kind::MusicUnreachable => {
            return format!("/devices/{id}/music");
        }
        // The others above their own group (its header) or by their rows.
        Kind::MusicStorytelRefused => ("/music", "storytel-title"),
        Kind::MusicOverLimit => ("/music", "storage-title"),
        Kind::MusicOlderPhone(_) => ("/music", "music-settings"),
        Kind::MusicFailing(_) => ("/music", "library"),
        Kind::MusicSpotify
        | Kind::MusicSpotifyLogin
        | Kind::MusicSpotifyEnding
        | Kind::MusicSpotifyShared
        | Kind::MusicSpotifyNoApp => ("/music", "music-spotify"),
    };
    format!("/devices/{id}{page}#{anchor}")
}

/// A rule's "calls off" is real: calls are managed and the call report doesn't say the launcher
/// can't screen them. The lock badge's "· calls off" on every page reads it (qa-31-s9-code N1).
pub fn calls_enforced(policy: &DevicePolicy, problems: &[CallProblem]) -> bool {
    policy.calls_managed
        && !problems
            .iter()
            .any(|p| matches!(p, CallProblem::NotEnforced | CallProblem::AnyoneCanCall))
}

/// What [facts_of] reads; the summary passes the parts it has built.
pub struct Sources<'a> {
    pub device: &'a Device,
    pub policy: &'a DevicePolicy,
    pub latest: Option<&'a DeviceStatus>,
    pub relock: &'a RelockCard,
    /// The call report's problems.
    pub call_problems: &'a [CallProblem],
    /// Calls aren't managed while a no-calls rule is in force (Device › Time's warning).
    pub incoming_calls_not_blocked: bool,
    pub connection: ConnectionState,
    /// The music entries switched on for the phone (`music::ticked_entries`).
    pub music_ticked: &'a [i64],
    /// The Spotify account it plays as, from the accounts table (`music::spotify_account_warnings`).
    pub spotify_account: crate::handlers::music::SpotifyAccountWarnings,
}

/// Reads what [items] needs. A read that fails counts as "nothing to say" for its row (logged by
/// the builder that failed); the card it belongs to shows the error.
pub async fn facts(state: &AppState, device: &Device, connection: ConnectionState) -> Facts {
    if device.enrolled_at.is_none() {
        return Facts {
            not_set_up: true,
            ..Default::default()
        };
    }
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
    let incoming_calls_not_blocked = !policy.calls_managed
        && crate::time_rules::effective_rule_rows(&state.db, id, policy.custom_schedule_enabled)
            .await
            .unwrap_or_default()
            .iter()
            .any(|rule| !rule.calls_allowed);
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
    facts_of(
        &Sources {
            device,
            policy: &policy,
            latest,
            relock: &relock,
            call_problems: &call_problems,
            incoming_calls_not_blocked,
            connection,
            music_ticked: &music_ticked,
            spotify_account,
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
    let capability = |name: &str| {
        latest
            .and_then(|st| st.capabilities_json.as_deref())
            .is_some_and(|caps| caps.contains(&format!("\"{name}\"")))
    };
    let lock_state =
        crate::kid_lock::parse_lock_state(latest.and_then(|st| st.lock_state_json.as_deref()));
    let lock_phone = crate::kid_lock::PhoneLock {
        state: lock_state.as_ref(),
        reported: latest.is_some(),
        capable: capability(crate::kid_lock::PIN_LOCK_CAPABILITY),
        android_capable: capability(crate::kid_lock::ANDROID_KEYGUARD_CAPABILITY),
        pin_waiting: false,
    };
    let play =
        crate::handlers::device_pages::play_notes(latest, policy.block_activity_start, now_ms);
    let escapes = crate::handlers::device_pages::escapes_card(policy, latest, now_ms).notes;
    Facts {
        not_set_up: false,
        report_stale: matches!(
            s.connection,
            ConnectionState::Offline { .. } | ConnectionState::Disconnected
        ),
        restrictions_paused: latest.is_some_and(|st| st.restrictions_paused),
        foreign_access: s.relock.foreign.is_some(),
        do_not_disturb: crate::sound_mode::state(
            None,
            latest.and_then(|st| st.interruption_filter.as_deref()),
        )
        .dnd
        .is_some_and(crate::sound_mode::Dnd::is_on),
        google_backup: latest.and_then(|st| st.backup_service_enabled) == Some(true),
        no_parent_pin: crate::handlers::devices::managed_without_pin(policy),
        easy_parent_pin: policy.override_pin_hash.is_some()
            && policy.override_pin_easy == Some(true),
        access_revoked: s.device.token_hash.is_none(),
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
        incoming_calls_not_blocked: s.incoming_calls_not_blocked,
        // Lock Screen & PINs' own notes, so the rows and the page agree.
        child_lock_off: crate::kid_lock::lock_off(policy, &lock_phone),
        child_pin_changed: crate::kid_lock::pin_changed_on_phone(policy, &lock_phone),
        blocked_apps: s.relock.blocks.waiting,
        restart_once: !crate::models::admin_policy_effects(
            latest.and_then(|st| st.admin_policies_pending_json.as_deref()),
        )
        .is_empty(),
        // The same notes Advanced shows, so the row and the page agree.
        play_install_mode: play
            .iter()
            .any(|n| matches!(n, PlayNote::InstallMode { .. })),
        play_store_openable: play.contains(&PlayNote::NotSuspendable { block_on: false }),
        not_home_app: escapes.contains(&EscapeNote::PartialFence),
        boot_cover_not_shown: escapes
            .iter()
            .any(|n| matches!(n, EscapeNote::BootCoverNotShown { .. })),
        // Home Screen's own warning, so the row and the page agree.
        no_notification_access: crate::handlers::device_pages::notification_access_note(latest)
            .is_some(),
        calls: s.call_problems.to_vec(),
        // Device › Time reads the same typed report.
        ledger_unreadable: latest
            .and_then(|st| st.time_state_json.as_deref())
            .and_then(crate::time_state::TimeReport::parse)
            .is_some_and(|report| report.ledger_unreadable),
        launcher_without_rules: crate::handlers::devices::launcher_without_rules(latest),
        calls_enforced: calls_enforced(policy, s.call_problems),
        // Device › Music's own warnings, so the rows and the page agree.
        music: {
            let mut music = crate::handlers::music::music_warnings(policy, latest, s.music_ticked);
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
            access_revoked: true,
            policy_problem: true,
            unseen_restarts: true,
            incoming_calls_not_blocked: true,
            child_lock_off: true,
            child_pin_changed: true,
            blocked_apps: 2,
            restart_once: true,
            play_install_mode: true,
            play_store_openable: true,
            not_home_app: true,
            boot_cover_not_shown: true,
            no_notification_access: true,
            calls: vec![CallProblem::Waiting, CallProblem::AnyoneCanCall],
            ledger_unreadable: true,
            launcher_without_rules: true,
            music: crate::handlers::music::MusicWarnings {
                cant_deliver: true,
                unreachable: true,
                storytel_refused: true,
                over_limit: Some(("3.2 GB".to_string(), 2)),
                older_phone: Some(crate::music::OlderApps::Both),
                failing_entries: 2,
                spotify_cant_play: true,
                spotify_account: crate::handlers::music::SpotifyAccountWarnings {
                    no_app: true,
                    renew: true,
                    ending: true,
                    shared: true,
                },
            },
            ..Default::default()
        }
    }

    #[test]
    fn nothing_to_say_is_no_rows() {
        assert!(items(3, &Facts::default()).is_empty());
        // "Waiting for the phone" isn't a problem.
        let waiting = Facts {
            calls: vec![CallProblem::Waiting],
            ..Default::default()
        };
        assert!(items(3, &waiting).is_empty());
    }

    #[test]
    fn a_phone_not_set_up_has_no_rows() {
        let new = Facts {
            not_set_up: true,
            ..everything()
        };
        assert!(items(3, &new).is_empty());
    }

    #[test]
    fn a_stale_phones_report_rows_say_at_last_report() {
        let rows = items(3, &everything());
        assert!(rows.iter().all(|r| !r.at_last_report));
        let stale = Facts {
            report_stale: true,
            ..everything()
        };
        let rows = items(3, &stale);
        assert_eq!(
            rows.len(),
            items(3, &everything()).len(),
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
                "access-revoked",
                "incoming-calls"
            ]
        );
    }

    #[test]
    fn alarms_come_first_in_the_specs_order() {
        let rows = items(3, &everything());
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
                "Access removed",
                "Phone can't apply its settings",
                "Phone started without Vibb One",
                "Incoming calls aren't blocked",
                "Lock is off on the phone",
                "Phone's PIN isn't the Child PIN",
                "2 blocked apps wait",
                "Restart the phone once",
                "Play Store open (install mode)",
                "Play Store can be opened",
                "Vibb One isn't the Home app",
                "Boot cover wasn't shown",
                "Notification access off",
                "Anyone can call this phone",
                "Screen-time record unreadable",
                "Vibb One too old for time rules",
                "Vibb One can't deliver music yet",
                "Music app not reachable",
                "Storytel refused the phone's login",
                "Music over the storage limit",
                "Update Vibb One and the music app",
                "2 music entries fail on the phone",
                "No Spotify app set up",
                "Spotify login needs renewing",
                "Spotify login ends soon",
                "Two phones share a Spotify account",
                "Spotify can't play on this phone",
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
        with_calls.calls.push(CallProblem::NoRedirection);
        let rows = items(3, &with_calls);
        let keys: std::collections::HashSet<&str> = rows.iter().map(AttentionItem::key).collect();
        assert_eq!(keys.len(), rows.len() - 1);
        assert_eq!(rows.iter().filter(|r| r.key() == "calls").count(), 2);
    }

    #[test]
    fn titles_are_short_facts_without_package_names() {
        let mut kinds: Vec<Kind> = items(3, &everything())
            .into_iter()
            .map(|r| r.kind)
            .collect();
        kinds.push(Kind::BlockedApps(1));
        kinds.push(Kind::MusicFailing(1));
        kinds.extend(CallProblem::ALL.iter().map(|p| Kind::Calls(*p)));
        for kind in kinds {
            let title = title(kind);
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
        assert_eq!(title(Kind::BlockedApps(1)), "1 blocked app waits");
        assert_eq!(
            title(Kind::MusicFailing(1)),
            "1 music entry fails on the phone"
        );
    }

    #[test]
    fn rows_link_to_the_card_that_fixes_them() {
        let rows = items(7, &everything());
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
        assert_eq!(target("access-revoked"), "/devices/7/advanced#connection");
        assert_eq!(target("restart-once"), "/devices/7/advanced#app-screens");
        assert_eq!(
            target("play-install-mode"),
            "/devices/7/advanced#app-screens"
        );
        assert_eq!(
            target("play-store-openable"),
            "/devices/7/advanced#app-screens"
        );
        assert_eq!(target("not-home-app"), "/devices/7/advanced#updates");
        assert_eq!(
            target("boot-cover-not-shown"),
            "/devices/7/advanced#updates"
        );
        assert_eq!(target("child-lock-off"), "/devices/7/lock#child-pin");
        // Step 5: Home Screen.
        assert_eq!(target("notification-access"), "/devices/7/home#layout");
        // Step 9: Device › Time, its warnings at the top.
        assert_eq!(target("screen-time-record"), "/devices/7/time");
        assert_eq!(target("launcher-without-rules"), "/devices/7/time");
        assert_eq!(target("calls"), "/devices/7/calls");
        assert_eq!(target("incoming-calls"), "/devices/7/calls");
        // Step 7's fix round: Device › Music - the top warnings without a fragment, the others at
        // their group.
        assert_eq!(target("music-cant-deliver"), "/devices/7/music");
        assert_eq!(target("music-unreachable"), "/devices/7/music");
        assert_eq!(
            target("music-storytel-refused"),
            "/devices/7/music#storytel-title"
        );
        assert_eq!(target("music-over-limit"), "/devices/7/music#storage-title");
        assert_eq!(
            target("music-older-phone"),
            "/devices/7/music#music-settings"
        );
        assert_eq!(target("music-entries"), "/devices/7/music#library");
    }
}
