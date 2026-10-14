//! The Attention list (design 31 §3.2 item 2, §3.8): what about a phone needs the parent, as one
//! line each with a link to the fix. The device page shows the rows, the Devices list their count
//! as the red badge - both from [items] over the same [Facts], so the two always agree.
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
//! the call report, the rules); fine at 1-5 phones - don't make it one query. The device page,
//! which builds those cards anyway, passes them to [facts_of] instead of reading them twice.

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
        | Kind::RestartOnce
        | Kind::PlayInstallMode
        | Kind::PlayStoreOpenable
        | Kind::NotHomeApp
        | Kind::BootCoverNotShown
        | Kind::NoNotificationAccess
        | Kind::Calls(_)
        | Kind::LedgerUnreadable
        | Kind::LauncherWithoutRules => Level::Action,
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
        Kind::UnseenRestarts => "Phone started without Vibb",
        Kind::IncomingCallsNotBlocked => "Incoming calls aren't blocked",
        Kind::ChildLockOff => "Lock is off on the phone",
        Kind::BlockedApps(1) => "1 blocked app waits",
        Kind::BlockedApps(n) => return format!("{n} blocked apps wait"),
        Kind::RestartOnce => "Restart the phone once",
        Kind::PlayInstallMode => "Play Store open (install mode)",
        Kind::PlayStoreOpenable => "Play Store can be opened",
        Kind::NotHomeApp => "Vibb isn't the Home app",
        Kind::BootCoverNotShown => "Boot cover wasn't shown",
        Kind::NoNotificationAccess => "Notification access off",
        Kind::Calls(problem) => problem.title(),
        Kind::LedgerUnreadable => "Screen-time record unreadable",
        Kind::LauncherWithoutRules => "Vibb too old for time rules",
    }
    .to_string()
}

/// The page that fixes it. Until each card has its sub-page (design 31 §8 steps 4-10) some are
/// the cards' anchors on the device page; each step moves its targets. Step 3 moved Do Not
/// Disturb, the policy problem and the restarts to About This Phone, and Google backup, access
/// and the restart to Advanced; step 4 the Parent PIN and the lock to Lock Screen & PINs; step 5
/// notification access to Home Screen.
fn target(kind: Kind, id: i64) -> String {
    let (page, anchor) = match kind {
        Kind::RestrictionsPaused | Kind::ForeignAccess | Kind::BlockedApps(_) => ("", "status"),
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
        Kind::ChildLockOff => ("/lock", "child-pin"),
        Kind::NoNotificationAccess => ("/home", "layout"),
        Kind::LedgerUnreadable | Kind::LauncherWithoutRules => ("", "time"),
        Kind::IncomingCallsNotBlocked | Kind::Calls(_) => {
            return format!("/devices/{id}/calls");
        }
    };
    format!("/devices/{id}{page}#{anchor}")
}

/// What [facts_of] reads; the device page passes the cards it has built.
pub struct Sources<'a> {
    pub device: &'a Device,
    pub policy: &'a DevicePolicy,
    pub latest: Option<&'a DeviceStatus>,
    pub relock: &'a RelockCard,
    /// The call report's problems.
    pub call_problems: &'a [CallProblem],
    /// Calls aren't managed while a no-calls rule is in force (the Time rules card's warning).
    pub incoming_calls_not_blocked: bool,
    pub connection: ConnectionState,
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
    let relock =
        crate::handlers::window_relock::card(&state.db, id, latest, None, now_ms, allowed.as_ref())
            .await;
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
    facts_of(
        &Sources {
            device,
            policy: &policy,
            latest,
            relock: &relock,
            call_problems: &call_problems,
            incoming_calls_not_blocked,
            connection,
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
    let blocked_apps = if s.relock.blocks.items.is_empty() && s.relock.notes.allow_button {
        // An older launcher: its last relock's "Blocked:" apps that "Allow these apps" lets out.
        s.relock.notes.allow.len().max(1)
    } else {
        s.relock.blocks.items.len()
    };
    let time = latest
        .and_then(|st| st.time_state_json.as_deref())
        .and_then(|json| serde_json::from_str::<serde_json::Value>(json).ok());
    let lock_state =
        crate::kid_lock::parse_lock_state(latest.and_then(|st| st.lock_state_json.as_deref()));
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
        child_lock_off: crate::kid_lock::lock_off(
            policy,
            lock_state.as_ref(),
            capability(crate::kid_lock::PIN_LOCK_CAPABILITY),
            latest.is_some(),
        ),
        blocked_apps,
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
        ledger_unreadable: time
            .as_ref()
            .is_some_and(|t| t["ledger_unreadable"].as_bool() == Some(true)),
        launcher_without_rules: crate::handlers::devices::launcher_without_rules(latest),
        calls_enforced: policy.calls_managed
            && !s
                .call_problems
                .iter()
                .any(|p| matches!(p, CallProblem::NotEnforced | CallProblem::AnyoneCanCall)),
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
                "Phone started without Vibb",
                "Incoming calls aren't blocked",
                "Lock is off on the phone",
                "2 blocked apps wait",
                "Restart the phone once",
                "Play Store open (install mode)",
                "Play Store can be opened",
                "Vibb isn't the Home app",
                "Boot cover wasn't shown",
                "Notification access off",
                "Anyone can call this phone",
                "Screen-time record unreadable",
                "Vibb too old for time rules",
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
        assert_eq!(target("restrictions-paused"), "/devices/7#status");
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
        assert_eq!(target("screen-time-record"), "/devices/7#time");
        assert_eq!(target("calls"), "/devices/7/calls");
        assert_eq!(target("incoming-calls"), "/devices/7/calls");
    }
}
