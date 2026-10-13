//! The Attention list (design 31 §3.2 item 2, §3.8): what about a phone needs the parent, as one
//! line each with a link to the fix. The device page shows the rows, the Devices list their count
//! as the red badge - both from [collect], so the two always agree.
//!
//! Every title comes from a typed value, never from the first sentence of a card's text (M5):
//! those run past 8 words and carry package names. Alarms (red) come first, then what needs
//! action (orange), each in §3.2's order.
//!
//! It reads about 10 small things per phone (the policy, the last report, the relock card, the
//! call report, the rules); fine at 1-5 phones - don't make it one query.

use crate::AppState;
use crate::handlers::calls::CallProblem;
use crate::models::{Device, DevicePolicy, DeviceStatus};

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
    NoNotificationAccess,
    Calls(CallProblem),
    LedgerUnreadable,
    LauncherWithoutRules,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AttentionItem {
    pub kind: Kind,
    pub level: Level,
    /// At most 8 words, starting with the fact.
    pub title: String,
    /// Where it is fixed.
    pub target: String,
}

impl AttentionItem {
    /// `alarm` or `action`, for the row's class.
    pub fn level_css(&self) -> &'static str {
        match self.level {
            Level::Alarm => "alarm",
            Level::Action => "action",
        }
    }

    /// A stable name for the row (`data-attention`), for tests and the page's hooks.
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
            Kind::NoNotificationAccess => "notification-access",
            Kind::Calls(_) => "calls",
            Kind::LedgerUnreadable => "screen-time-record",
            Kind::LauncherWithoutRules => "launcher-without-rules",
        }
    }
}

/// The facts [items] turns into rows, typed. Gathered by [facts]; pure from here on.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Facts {
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
    pub no_notification_access: bool,
    /// From the call report, in its order (`Waiting` left out).
    pub calls: Vec<CallProblem>,
    pub ledger_unreadable: bool,
    pub launcher_without_rules: bool,
}

/// The rows for one phone, alarms first.
pub fn items(device_id: i64, facts: &Facts) -> Vec<AttentionItem> {
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
        Kind::NoNotificationAccess => "Notification access off",
        Kind::Calls(problem) => problem.title(),
        Kind::LedgerUnreadable => "Screen-time record unreadable",
        Kind::LauncherWithoutRules => "Vibb too old for time rules",
    }
    .to_string()
}

/// The page that fixes it. Until each card has its sub-page (design 31 §8 steps 3-10) these are
/// the cards' anchors on the device page; each step moves its targets.
fn target(kind: Kind, id: i64) -> String {
    let anchor = match kind {
        Kind::RestrictionsPaused
        | Kind::ForeignAccess
        | Kind::DoNotDisturb
        | Kind::PolicyProblem
        | Kind::UnseenRestarts
        | Kind::BlockedApps(_) => "status",
        Kind::GoogleBackup => "hardening",
        Kind::NoParentPin | Kind::EasyParentPin => "parent-pin",
        Kind::AccessRevoked => "access",
        Kind::ChildLockOff => "screen-lock",
        Kind::RestartOnce => "play-kiosk",
        Kind::NoNotificationAccess => "launcher",
        Kind::LedgerUnreadable | Kind::LauncherWithoutRules => "time",
        Kind::IncomingCallsNotBlocked | Kind::Calls(_) => {
            return format!("/devices/{id}/calls");
        }
    };
    format!("/devices/{id}#{anchor}")
}

/// The Attention rows for `device`.
pub async fn collect(state: &AppState, device: &Device) -> Vec<AttentionItem> {
    items(device.id, &facts(state, device).await)
}

/// Reads what [items] needs. A read that fails counts as "nothing to say" for its row (logged by
/// the builder that failed); the card it belongs to shows the error.
pub async fn facts(state: &AppState, device: &Device) -> Facts {
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
    let capability = |name: &str| {
        latest
            .and_then(|s| s.capabilities_json.as_deref())
            .is_some_and(|caps| caps.contains(&format!("\"{name}\"")))
    };

    let allowed: Option<std::collections::HashSet<String>> = policy
        .allowlist_json
        .as_deref()
        .and_then(|json| serde_json::from_str::<Vec<String>>(json).ok())
        .map(|list| list.into_iter().collect());
    let relock =
        crate::handlers::window_relock::card(&state.db, id, latest, None, now_ms, allowed.as_ref())
            .await;
    let blocked_apps = if relock.blocks.items.is_empty() && relock.notes.allow_button {
        // An older launcher: its last relock's "Blocked:" apps that "Allow these apps" lets out.
        relock.notes.allow.len().max(1)
    } else {
        relock.blocks.items.len()
    };

    let calls = match crate::handlers::calls::call_report(state, &policy).await {
        Ok(report) => report.problems,
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

    let time = latest
        .and_then(|s| s.time_state_json.as_deref())
        .and_then(|json| serde_json::from_str::<serde_json::Value>(json).ok());
    let lock_state =
        crate::kid_lock::parse_lock_state(latest.and_then(|s| s.lock_state_json.as_deref()));

    Facts {
        restrictions_paused: latest.is_some_and(|s| s.restrictions_paused),
        foreign_access: relock.foreign.is_some(),
        do_not_disturb: matches!(
            crate::sound_mode::sanitize_interruption_filter(
                latest.and_then(|s| s.interruption_filter.as_deref())
            ),
            Some("priority" | "alarms" | "none")
        ),
        google_backup: latest.and_then(|s| s.backup_service_enabled) == Some(true),
        no_parent_pin: crate::handlers::devices::managed_without_pin(&policy),
        easy_parent_pin: policy.override_pin_hash.is_some()
            && policy.override_pin_easy == Some(true),
        access_revoked: device.enrolled_at.is_some() && device.token_hash.is_none(),
        policy_problem: latest
            .and_then(|s| s.policy_state.as_deref())
            .is_some_and(|state| state != "ok"),
        unseen_restarts: latest.is_some_and(|s| {
            crate::boot_witness::warning(
                s.unseen_boots_count,
                s.unseen_boots_last_at_ms,
                now_ms,
                policy.hardening.disallow_safe_boot,
                None,
            )
            .is_some()
        }),
        incoming_calls_not_blocked,
        child_lock_off: crate::kid_lock::lock_off(
            &policy,
            lock_state.as_ref(),
            capability(crate::kid_lock::PIN_LOCK_CAPABILITY),
            latest.is_some(),
        ),
        blocked_apps,
        restart_once: crate::models::admin_policy_notice(
            latest.and_then(|s| s.admin_policies_pending_json.as_deref()),
        )
        .is_some(),
        no_notification_access: latest.and_then(|s| s.notification_listener_enabled) == Some(false),
        calls,
        ledger_unreadable: time
            .as_ref()
            .is_some_and(|t| t["ledger_unreadable"].as_bool() == Some(true)),
        launcher_without_rules: crate::handlers::devices::launcher_without_rules(latest),
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
            no_notification_access: true,
            calls: vec![CallProblem::Waiting, CallProblem::AnyoneCanCall],
            ledger_unreadable: true,
            launcher_without_rules: true,
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
        // Each kind has its own key.
        let mut keys: Vec<&str> = rows.iter().map(AttentionItem::key).collect();
        keys.dedup();
        assert_eq!(keys.len(), rows.len());
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
        assert_eq!(target("easy-parent-pin"), "/devices/7#parent-pin");
        assert_eq!(target("google-backup"), "/devices/7#hardening");
        assert_eq!(target("access-revoked"), "/devices/7#access");
        assert_eq!(target("child-lock-off"), "/devices/7#screen-lock");
        assert_eq!(target("restart-once"), "/devices/7#play-kiosk");
        assert_eq!(target("notification-access"), "/devices/7#launcher");
        assert_eq!(target("screen-time-record"), "/devices/7#time");
        assert_eq!(target("calls"), "/devices/7/calls");
        assert_eq!(target("incoming-calls"), "/devices/7/calls");
    }
}
