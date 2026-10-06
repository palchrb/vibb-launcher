//! Handy step 11 (design `docs/design/11-kiosk-escapes.md` at the monorepo root, with
//! `qa-11-design.md`): the per-device switches for the launcher's update fence and its
//! notification auto-cancel rule, and what the phone reports about them - `update_fence` and
//! `notification_cancels`, stored re-serialized and capped like `lock_state`, so nothing but the
//! known fields (package and channel ids, counts - never notification text) reaches the database.
//! Pure; the device page's card is [escapes_card].

use serde::{Deserialize, Serialize};

/// Status-report capability of a launcher that has the update fence, the night update window and
/// the notification rule.
pub const KIOSK_ESCAPES_CAPABILITY: &str = "kiosk_escapes_v1";

/// At most this many entries of a list are stored (unsuspendable packages, notification counts).
pub const MAX_ENTRIES: usize = 20;
/// Package and channel ids are cut to this many characters (qa-11-design.md #13).
pub const MAX_ID: usize = 64;
/// Short status words ("fenced", "outside_window", ...).
const MAX_WORD: usize = 32;

fn cut(value: &str, max: usize) -> String {
    value.chars().take(max).collect()
}

/// The phone's `update_fence` report. Unknown fields are ignored, missing ones default.
#[derive(Deserialize, Serialize, Default, Debug, Clone, PartialEq, Eq)]
#[serde(default)]
pub struct UpdateFenceState {
    /// The `update_fence` switch as the phone sees it.
    pub enabled: bool,
    /// "none" | "planned" | "fenced".
    pub state: String,
    /// Home packages Android refused to suspend for the update window.
    pub unsuspendable: Vec<String>,
    /// Why the last fence ended ("replaced", "install_failed", "rebooted", ...).
    pub last_release: Option<String>,
    /// Whether the launcher holds ROLE_HOME (a partial fence otherwise).
    pub home_role_held: Option<bool>,
    /// A downloaded launcher update waiting for the night window.
    pub pending_tag: Option<String>,
    pub pending_since_ms: Option<i64>,
    /// The window gate's last reason to wait: "call", "emergency", "screen_on",
    /// "screen_off_short", "outside_window".
    pub waiting_for: Option<String>,
}

/// `StatusReportRequest.update_fence` as stored: only the [UpdateFenceState] fields, lists and
/// strings capped; `None` for anything that isn't such an object.
pub fn sanitize_update_fence(value: &serde_json::Value) -> Option<String> {
    if !value.is_object() {
        return None;
    }
    let mut state: UpdateFenceState = serde_json::from_value(value.clone()).ok()?;
    state.state = cut(&state.state, MAX_WORD);
    state.unsuspendable = state
        .unsuspendable
        .iter()
        .take(MAX_ENTRIES)
        .map(|p| cut(p, MAX_ID))
        .collect();
    state.last_release = state.last_release.map(|r| cut(&r, MAX_WORD));
    state.pending_tag = state.pending_tag.map(|t| cut(&t, MAX_ID));
    state.waiting_for = state.waiting_for.map(|w| cut(&w, MAX_WORD));
    serde_json::to_string(&state).ok()
}

pub fn parse_update_fence(json: Option<&str>) -> Option<UpdateFenceState> {
    json.and_then(|j| serde_json::from_str(j).ok())
}

/// One (package, channel) of the phone's `notification_cancels`.
#[derive(Deserialize, Serialize, Default, Debug, Clone, PartialEq, Eq)]
#[serde(default)]
pub struct NotificationCancel {
    pub package_name: String,
    pub channel: Option<String>,
    pub cancelled: i64,
    pub snoozed: i64,
}

/// The phone's `notification_cancels`: whether the rule is active and what it removed since the
/// previous report.
#[derive(Deserialize, Serialize, Default, Debug, Clone, PartialEq, Eq)]
#[serde(default)]
pub struct NotificationCancels {
    pub active: bool,
    pub entries: Vec<NotificationCancel>,
    /// Entries the phone (or this server) left out.
    pub dropped: i64,
}

/// `StatusReportRequest.notification_cancels` as stored: only the known fields, at most
/// [MAX_ENTRIES] entries (the rest counted in `dropped`), ids cut to [MAX_ID] characters, counts
/// never negative; `None` for anything that isn't such an object.
pub fn sanitize_notification_cancels(value: &serde_json::Value) -> Option<String> {
    if !value.is_object() {
        return None;
    }
    let mut cancels: NotificationCancels = serde_json::from_value(value.clone()).ok()?;
    let extra = cancels.entries.len().saturating_sub(MAX_ENTRIES) as i64;
    cancels.entries.truncate(MAX_ENTRIES);
    for entry in &mut cancels.entries {
        entry.package_name = cut(&entry.package_name, MAX_ID);
        entry.channel = entry.channel.as_deref().map(|c| cut(c, MAX_ID));
        entry.cancelled = entry.cancelled.max(0);
        entry.snoozed = entry.snoozed.max(0);
    }
    cancels.dropped = cancels.dropped.max(0).saturating_add(extra);
    serde_json::to_string(&cancels).ok()
}

pub fn parse_notification_cancels(json: Option<&str>) -> Option<NotificationCancels> {
    json.and_then(|j| serde_json::from_str(j).ok())
}

/// The device page's "Launcher updates and notifications" card.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct EscapesCard {
    /// `device_policy.update_fence`.
    pub update_fence: bool,
    /// `device_policy.notification_auto_cancel`.
    pub notification_auto_cancel: bool,
    pub lines: Vec<String>,
    pub warnings: Vec<String>,
}

/// What the latest report and the switches mean for the parent. [capable]: the reporting
/// launcher has [KIOSK_ESCAPES_CAPABILITY]; [reported]: there is a report at all.
#[allow(clippy::too_many_arguments)]
pub fn escapes_card(
    update_fence: bool,
    notification_auto_cancel: bool,
    capable: bool,
    reported: bool,
    fence: Option<&UpdateFenceState>,
    cancels: Option<&NotificationCancels>,
    listener_enabled: Option<bool>,
    now_ms: i64,
) -> EscapesCard {
    let mut card = EscapesCard {
        update_fence,
        notification_auto_cancel,
        ..Default::default()
    };
    if reported && !capable {
        card.warnings.push(
            "This phone's launcher has neither the update fence nor the notification filter yet - \
             update the launcher. Until then a launcher update shows Android's own home screen for \
             about a minute, and other apps' notifications can open screens outside the kiosk."
                .to_string(),
        );
        return card;
    }
    if !update_fence {
        card.lines.push(
            "Update fence off: while the launcher installs its own update (at night, screen off) \
             the phone may show Android's own home screen and shade for about a minute."
                .to_string(),
        );
    }
    if let Some(fence) = fence {
        if fence.state == "fenced" || fence.state == "planned" {
            card.lines.push(
                "The launcher is installing its own update right now: other home screens and the \
                 notification shade are paused until it is back."
                    .to_string(),
            );
        }
        if !fence.unsuspendable.is_empty() {
            card.warnings.push(format!(
                "Android refused to pause these home screens during the update: {}.",
                fence.unsuspendable.join(", ")
            ));
        }
        if update_fence && fence.home_role_held == Some(false) {
            card.warnings.push(
                "The launcher isn't Android's Home app on this phone, so the update fence is only \
                 partial. Check that the phone is still device owner."
                    .to_string(),
            );
        }
        if let Some(text) = fence.last_release.as_deref().and_then(release_text) {
            if fence.last_release.as_deref() == Some("install_failed") {
                card.warnings.push(text);
            } else {
                card.lines.push(text);
            }
        }
        if let Some(tag) = fence.pending_tag.as_deref() {
            let overdue = fence
                .pending_since_ms
                .is_some_and(|since| now_ms - since >= 24 * 60 * 60 * 1000);
            let mut line = format!(
                "Launcher update {tag} is downloaded and goes in at night (02:00-05:00, screen off \
                 for 30 seconds, no call)"
            );
            if overdue {
                line.push_str(" - it has waited a day, so any quiet screen-off will do");
            }
            if let Some(why) = fence.waiting_for.as_deref().and_then(wait_text) {
                line.push_str(&format!(". Last check: {why}"));
            }
            line.push('.');
            card.lines.push(line);
        }
    }
    if notification_auto_cancel && listener_enabled == Some(false) {
        card.warnings.push(
            "The notification filter is on, but the launcher has no notification access (\"App \
             badges\"), so nothing is removed. Grant it with adb before enrolling (see the \
             emulator/provisioning notes)."
                .to_string(),
        );
    }
    if let Some(cancels) = cancels {
        if notification_auto_cancel && !cancels.active && listener_enabled != Some(false) {
            card.lines.push(
                "The notification filter isn't active on the phone yet (it waits for the next \
                 sync, and pauses while the unlock code or the pause is in use)."
                    .to_string(),
            );
        }
        for entry in cancels.entries.iter().take(5) {
            let channel = entry
                .channel
                .as_deref()
                .map(|c| format!(" ({c})"))
                .unwrap_or_default();
            let mut line = format!(
                "Removed since the last report: {}{channel} - {} notification(s)",
                entry.package_name, entry.cancelled
            );
            if entry.snoozed > 0 {
                line.push_str(&format!(
                    ", {} snoozed for an hour (it kept coming back)",
                    entry.snoozed
                ));
            }
            line.push('.');
            card.lines.push(line);
        }
        let more = cancels.entries.len().saturating_sub(5) as i64 + cancels.dropped;
        if more > 0 {
            card.lines
                .push(format!("... and {more} more app/channel pair(s)."));
        }
    }
    card
}

/// The device page's text for `update_fence.last_release`; `None` = nothing worth saying.
pub fn release_text(reason: &str) -> Option<String> {
    Some(match reason {
        "replaced" | "replaced_backstop" => {
            "The last launcher update went in; the update fence was lifted once the launcher was \
             back."
                .to_string()
        }
        "install_failed" | "commit_failed" => {
            "The last launcher update failed to install; the update fence was lifted at once and \
             the update is retried in an hour."
                .to_string()
        }
        "rebooted" => "The phone restarted during the last launcher update; the update fence was \
            lifted at boot."
            .to_string(),
        "expired" => {
            "The last launcher update took longer than 10 minutes; the update fence was lifted."
                .to_string()
        }
        "switched_off" | "unmanaged" => return None,
        other => format!("The last update fence ended: {other}."),
    })
}

fn wait_text(reason: &str) -> Option<&'static str> {
    Some(match reason {
        "call" => "a call was on",
        "emergency" => "an emergency call was recent",
        "screen_on" => "the screen was on",
        "screen_off_short" => "the screen had only just gone off",
        "outside_window" => "waiting for the night window",
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn update_fence_keeps_only_known_fields_capped() {
        let stored = sanitize_update_fence(&json!({
            "enabled": true,
            "state": "fenced".repeat(20),
            "unsuspendable": (0..30).map(|i| format!("{}{i}", "p".repeat(100))).collect::<Vec<_>>(),
            "last_release": "replaced",
            "pending_tag": "t".repeat(500),
            "notification_text": "secret",
        }))
        .unwrap();
        let state = parse_update_fence(Some(&stored)).unwrap();
        assert!(state.enabled);
        assert_eq!(state.state.chars().count(), MAX_WORD);
        assert_eq!(state.unsuspendable.len(), MAX_ENTRIES);
        assert!(
            state
                .unsuspendable
                .iter()
                .all(|p| p.chars().count() <= MAX_ID)
        );
        assert_eq!(state.pending_tag.unwrap().chars().count(), MAX_ID);
        assert!(!stored.contains("secret"));
        assert_eq!(sanitize_update_fence(&json!("fenced")), None);
        assert_eq!(sanitize_update_fence(&json!({ "state": 5 })), None);
    }

    #[test]
    fn notification_cancels_are_capped_and_never_carry_text() {
        let entries: Vec<_> = (0..25)
            .map(|i| {
                json!({
                    "package_name": format!("{}{i}", "x".repeat(100)),
                    "channel": "c".repeat(100),
                    "cancelled": 3,
                    "snoozed": -2,
                    "title": "Hi from Grandma",
                })
            })
            .collect();
        let stored = sanitize_notification_cancels(
            &json!({ "active": true, "entries": entries, "dropped": 4, "text": "x" }),
        )
        .unwrap();
        let cancels = parse_notification_cancels(Some(&stored)).unwrap();
        assert!(cancels.active);
        assert_eq!(cancels.entries.len(), MAX_ENTRIES);
        assert_eq!(cancels.dropped, 4 + 5);
        assert!(
            cancels
                .entries
                .iter()
                .all(|e| e.package_name.chars().count() <= MAX_ID
                    && e.channel.as_deref().unwrap().chars().count() <= MAX_ID
                    && e.snoozed == 0)
        );
        assert!(!stored.contains("Grandma"));
        assert_eq!(sanitize_notification_cancels(&json!([1, 2])), None);
    }

    #[test]
    fn card_without_the_capability_only_says_update() {
        let card = escapes_card(true, true, false, true, None, None, Some(true), 0);
        assert_eq!(card.warnings.len(), 1);
        assert!(card.lines.is_empty());
    }

    #[test]
    fn card_explains_the_fence_and_the_pending_update() {
        let fence = UpdateFenceState {
            enabled: true,
            state: "fenced".to_string(),
            unsuspendable: vec!["com.oem.home".to_string()],
            last_release: Some("install_failed".to_string()),
            home_role_held: Some(false),
            pending_tag: Some("launcher-v1.4.0".to_string()),
            pending_since_ms: Some(0),
            waiting_for: Some("outside_window".to_string()),
        };
        let day = 24 * 60 * 60 * 1000;
        let card = escapes_card(true, false, true, true, Some(&fence), None, None, day);
        let all = format!("{:?}", card);
        assert!(all.contains("installing its own update right now"), "{all}");
        assert!(all.contains("com.oem.home"), "{all}");
        assert!(
            all.contains("only \\\n                 partial") || all.contains("partial"),
            "{all}"
        );
        assert!(
            card.warnings
                .iter()
                .any(|w| w.contains("failed to install"))
        );
        assert!(all.contains("launcher-v1.4.0"), "{all}");
        assert!(all.contains("any quiet screen-off"), "{all}");
        assert!(all.contains("waiting for the night window"), "{all}");
    }

    #[test]
    fn card_lists_removed_notifications_and_missing_access() {
        let cancels = NotificationCancels {
            active: true,
            entries: (0..7)
                .map(|i| NotificationCancel {
                    package_name: format!("pkg{i}"),
                    channel: Some("nag".to_string()),
                    cancelled: 2,
                    snoozed: if i == 0 { 1 } else { 0 },
                })
                .collect(),
            dropped: 1,
        };
        let card = escapes_card(false, true, true, true, None, Some(&cancels), Some(true), 0);
        assert!(card.lines.iter().any(|l| l.contains("Update fence off")));
        assert!(
            card.lines
                .iter()
                .any(|l| l.contains("pkg0 (nag)") && l.contains("snoozed"))
        );
        assert!(card.lines.iter().any(|l| l.contains("3 more")));
        let no_access = escapes_card(false, true, true, true, None, None, Some(false), 0);
        assert!(
            no_access
                .warnings
                .iter()
                .any(|w| w.contains("no notification access"))
        );
    }

    #[test]
    fn release_texts() {
        assert!(release_text("replaced").unwrap().contains("went in"));
        assert!(release_text("rebooted").unwrap().contains("restarted"));
        assert_eq!(release_text("switched_off"), None);
        assert!(release_text("weird").unwrap().contains("weird"));
    }
}
