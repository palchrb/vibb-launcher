//! Handy step 11 (design `docs/design/11-kiosk-escapes.md` at the monorepo root, with
//! `qa-11-design.md`): the per-device switches for the launcher's update fence and its
//! notification auto-cancel rule, and what the phone reports about them - `update_fence` and
//! `notification_cancels`, stored re-serialized and capped like `lock_state`, so nothing but the
//! known fields (package and channel ids, counts - never notification text) reaches the database.
//! Pure; Advanced › Updates & notifications shows [escapes_card] (design 31 step 3).

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
/// Phone-supplied times are kept within 1970..2100 (qa-11-code #8: no overflow in the card).
const MAX_MS: i64 = 4_102_444_800_000;
/// Phone-supplied counts are kept within 0..=1_000_000.
const MAX_COUNT: i64 = 1_000_000;
/// A fence the phone reported more than this long ago is no longer "right now".
const FENCE_STALE_MS: i64 = 15 * 60 * 1000;

fn clamp_ms(value: Option<i64>) -> Option<i64> {
    value.map(|v| v.clamp(0, MAX_MS))
}

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
    /// The last fence's start and end on the phone's clock (qa-11-code #5).
    pub last_fenced_at_ms: Option<i64>,
    pub last_released_at_ms: Option<i64>,
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
    state.pending_since_ms = clamp_ms(state.pending_since_ms);
    state.last_fenced_at_ms = clamp_ms(state.last_fenced_at_ms);
    state.last_released_at_ms = clamp_ms(state.last_released_at_ms);
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
        entry.cancelled = entry.cancelled.clamp(0, MAX_COUNT);
        entry.snoozed = entry.snoozed.clamp(0, MAX_COUNT);
    }
    cancels.dropped = cancels.dropped.clamp(0, MAX_COUNT).saturating_add(extra);
    serde_json::to_string(&cancels).ok()
}

pub fn parse_notification_cancels(json: Option<&str>) -> Option<NotificationCancels> {
    json.and_then(|j| serde_json::from_str(j).ok())
}

/// Advanced › Updates & notifications (design 31 step 3; the device page's "Launcher updates and
/// notifications" card until then): the three switches and what the phone reports, typed (M5).
#[derive(Debug, Default, PartialEq, Eq)]
pub struct EscapesCard {
    /// `device_policy.update_fence`.
    pub update_fence: bool,
    /// `device_policy.notification_auto_cancel`.
    pub notification_auto_cancel: bool,
    /// `device_policy.boot_cover` (design 16b).
    pub boot_cover: bool,
    pub notes: Vec<EscapeNote>,
    /// What the notification filter removed since the previous report, at most
    /// [REMOVED_SHOWN] (app, channel) pairs.
    pub removed: Vec<NotificationCancel>,
    /// Pairs left out of [Self::removed] (here or on the phone).
    pub more_removed: i64,
}

/// At most this many removed (app, channel) pairs are listed.
pub const REMOVED_SHOWN: usize = 5;

/// How the launcher's last update fence ended (`update_fence.last_release`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Release {
    /// "replaced", "replaced_backstop": the update went in.
    Replaced,
    InstallFailed,
    CommitFailed,
    /// A paused home screen without its fence record (lost or corrupt); the phone lifted it.
    Orphan,
    Rebooted,
    Expired,
    /// A reason this server doesn't know.
    Other(String),
}

impl Release {
    /// `None` for an end not worth a row (switched off, unmanaged).
    pub fn parse(reason: &str) -> Option<Release> {
        Some(match reason {
            "replaced" | "replaced_backstop" => Release::Replaced,
            "install_failed" => Release::InstallFailed,
            "commit_failed" => Release::CommitFailed,
            "orphan" => Release::Orphan,
            "rebooted" => Release::Rebooted,
            "expired" => Release::Expired,
            "switched_off" | "unmanaged" => return None,
            other => Release::Other(other.to_string()),
        })
    }

    /// A warning, not a plain row.
    pub fn failed(&self) -> bool {
        matches!(
            self,
            Release::InstallFailed | Release::CommitFailed | Release::Orphan
        )
    }
}

/// Why the night window's gate waited last (`update_fence.waiting_for`).
fn wait_text(reason: &str) -> Option<&'static str> {
    Some(match reason {
        "call" => "a call was on",
        "emergency" => "an emergency call was recent",
        "screen_on" => "the screen was on",
        "screen_off_short" => "the screen had only just gone off",
        "outside_window" => "waiting for the night window",
        // Install Now (design 37 §2.4); `install_now_download` comes without a pending tag, so
        // the "Update waiting" note never shows it.
        crate::install_now::WAIT_CALL => "Install Now waits for the call to end",
        crate::install_now::WAIT_EMERGENCY => "Install Now waits: an emergency call was recent",
        crate::install_now::WAIT_READY => "Install Now is installing it",
        _ => return None,
    })
}

/// How the boot cover handed over at the last start.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Handover {
    /// The cover saw Vibb ready and stepped aside by itself.
    ByItself,
    /// Vibb took over when it started.
    WhenVibbStarted,
}

/// One thing the phone's reports say about the update fence, the notification filter or the boot
/// cover (design 31 M5: typed; [EscapeNote::note] words it).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum EscapeNote {
    /// The reporting launcher has neither the fence nor the filter: update it.
    NeedsUpdate,
    /// A fence is up (planned or fenced). `stale`: it started more than 15 minutes ago and the
    /// phone hasn't reported since, so it is no longer "now".
    Fencing {
        started_at_ms: Option<i64>,
        stale: bool,
    },
    /// Home screens Android refused to pause during the update.
    Unsuspendable(Vec<String>),
    /// The launcher isn't Android's Home app: the fence is only partial.
    PartialFence,
    LastRelease(Release),
    /// A downloaded launcher update waits for the night window.
    UpdateWaiting {
        tag: String,
        /// It has waited a day: any quiet screen-off will do.
        overdue: bool,
        waiting_for: Option<&'static str>,
    },
    /// The filter is on, but the launcher has no notification access.
    FilterWithoutAccess,
    /// The filter is on, but not active on the phone yet.
    FilterNotActive,
    /// The boot cover is on, but the reporting launcher hasn't got it.
    BootCoverNeedsUpdate,
    /// The boot cover crashed twice in one start and switched itself off.
    BootCoverTripped,
    /// Armed at the next shutdown or restart.
    BootCoverArmed,
    BootCoverShown {
        at_ms: i64,
        handover: Option<Handover>,
    },
    /// Armed at a shutdown, but not shown at the start after it.
    BootCoverNotShown {
        armed_at_ms: i64,
    },
}

impl EscapeNote {
    /// A warning (an orange row above the group), not a plain row in it.
    pub fn is_warning(&self) -> bool {
        match self {
            EscapeNote::NeedsUpdate
            | EscapeNote::Unsuspendable(_)
            | EscapeNote::PartialFence
            | EscapeNote::FilterWithoutAccess
            | EscapeNote::BootCoverNeedsUpdate
            | EscapeNote::BootCoverTripped
            | EscapeNote::BootCoverNotShown { .. } => true,
            EscapeNote::LastRelease(release) => release.failed(),
            EscapeNote::Fencing { .. }
            | EscapeNote::UpdateWaiting { .. }
            | EscapeNote::FilterNotActive
            | EscapeNote::BootCoverArmed
            | EscapeNote::BootCoverShown { .. } => false,
        }
    }

    /// The words: a short title, a value for a row, the sentence behind its ⓘ.
    pub fn note(
        &self,
        zone: &crate::display_time::Zone,
        now: chrono::DateTime<chrono::Utc>,
    ) -> crate::handlers::device_pages::Note {
        let when = |ms: i64| {
            chrono::DateTime::from_timestamp_millis(ms)
                .map(|t| zone.when(t, now))
                .unwrap_or_default()
        };
        let (title, value, detail): (String, Option<String>, Option<String>) = match self {
            EscapeNote::NeedsUpdate => (
                "Update Vibb One for these switches".into(),
                None,
                Some(
                    "This phone's Vibb One has neither the update fence nor the notification filter \
                     yet. Until it updates, an update shows Android's own home screen for about a \
                     minute, and other apps' notifications can open screens outside the allowed \
                     apps."
                        .into(),
                ),
            ),
            EscapeNote::Fencing {
                started_at_ms,
                stale: false,
            } => (
                "Update installing".into(),
                Some(
                    started_at_ms
                        .map(|ms| format!("Since {}", when(ms)))
                        .unwrap_or_else(|| "Now".into()),
                ),
                Some(
                    "Other home screens and the notification shade are paused until Vibb One is back."
                        .into(),
                ),
            ),
            EscapeNote::Fencing {
                started_at_ms,
                stale: true,
            } => (
                "Update started".into(),
                started_at_ms.map(when),
                Some("The phone hasn't reported since.".into()),
            ),
            EscapeNote::Unsuspendable(packages) => (
                "Some home screens weren't paused".into(),
                None,
                Some(format!(
                    "Android refused to pause these during the update: {}.",
                    packages.join(", ")
                )),
            ),
            EscapeNote::PartialFence => (
                "Vibb One isn't the Home app".into(),
                None,
                Some(
                    "So the update fence is only partial. Check that the phone is still managed \
                     by Vibb One (device owner)."
                        .into(),
                ),
            ),
            EscapeNote::LastRelease(release) => {
                let (title, value, detail) = match release {
                    Release::Replaced => (
                        "Last update",
                        Some("Installed"),
                        "It went in; the update fence was lifted once Vibb One was back.".to_string(),
                    ),
                    Release::InstallFailed => (
                        "Last update failed to install",
                        None,
                        "The fence was lifted at once. The phone keeps the download and tries \
                         again in a night window an hour or more later (a wrong signing key or an \
                         older version waits for a new release)."
                            .to_string(),
                    ),
                    Release::CommitFailed => (
                        "Last update couldn't start",
                        None,
                        "The fence was lifted at once. The phone keeps the download and tries \
                         again in a night window an hour or more later."
                            .to_string(),
                    ),
                    Release::Orphan => (
                        "Update fence record lost",
                        None,
                        "A paused home screen was found without its record (lost or corrupt); \
                         the phone lifted it."
                            .to_string(),
                    ),
                    Release::Rebooted => (
                        "Last update",
                        Some("Interrupted by a restart"),
                        "The phone restarted during the update; the fence was lifted at boot."
                            .to_string(),
                    ),
                    Release::Expired => (
                        "Last update",
                        Some("Took too long"),
                        "It took longer than 10 minutes; the fence was lifted.".to_string(),
                    ),
                    Release::Other(reason) => (
                        "Last update",
                        Some("Ended"),
                        format!("The phone said: {reason}."),
                    ),
                };
                (title.into(), value.map(Into::into), Some(detail))
            }
            EscapeNote::UpdateWaiting {
                tag,
                overdue,
                waiting_for,
            } => {
                let mut detail = format!(
                    "Downloaded ({tag}). It goes in at night (02:00-05:00, screen off for 30 \
                     seconds, no call)"
                );
                if *overdue {
                    detail.push_str(". It has waited a day, so any quiet screen-off will do");
                }
                if let Some(why) = waiting_for {
                    detail.push_str(&format!(". Last check: {why}"));
                }
                detail.push('.');
                (
                    "Update waiting".into(),
                    Some(tag.strip_prefix("launcher-v").unwrap_or(tag).to_string()),
                    Some(detail),
                )
            }
            EscapeNote::FilterWithoutAccess => (
                "Filter has no notification access".into(),
                None,
                Some(
                    "Vibb One has no notification access (\"App badges\"), so nothing is removed. \
                     Grant it with adb before setting the phone up."
                        .into(),
                ),
            ),
            EscapeNote::FilterNotActive => (
                "Notification filter".into(),
                Some("Not active yet".into()),
                Some(
                    "It waits for the next sync, and pauses while the Parent PIN's override or a \
                     pause is in use."
                        .into(),
                ),
            ),
            EscapeNote::BootCoverNeedsUpdate => (
                "Update Vibb One for the boot cover".into(),
                None,
                Some(
                    "This phone's Vibb One doesn't have it yet; until then the switch does nothing."
                        .into(),
                ),
            ),
            EscapeNote::BootCoverTripped => (
                "Boot cover switched itself off".into(),
                None,
                Some(
                    "It crashed twice during one start. Turn the switch off and on to try again \
                     (after a Vibb One update)."
                        .into(),
                ),
            ),
            EscapeNote::BootCoverArmed => (
                "Cover at the next start".into(),
                Some("Ready".into()),
                Some(
                    "It shows from the phone's next shutdown or restart from its power menu; a \
                     crash or a forced restart skips it."
                        .into(),
                ),
            ),
            EscapeNote::BootCoverShown { at_ms, handover } => (
                "Cover at the last start".into(),
                Some(format!("Shown {}", when(*at_ms))),
                handover.map(|h| {
                    match h {
                        Handover::ByItself => "It handed over to Vibb One by itself.",
                        Handover::WhenVibbStarted => "It handed over when Vibb One started.",
                    }
                    .to_string()
                }),
            ),
            EscapeNote::BootCoverNotShown { armed_at_ms } => (
                "Boot cover wasn't shown".into(),
                None,
                Some(format!(
                    "It was armed at the shutdown at {} but not shown at the start after it: \
                     Android picked another home screen on this phone. Switch it off here.",
                    when(*armed_at_ms)
                )),
            ),
        };
        crate::handlers::device_pages::Note {
            warning: self.is_warning(),
            title,
            value,
            detail,
            fix: None,
        }
    }
}

/// What the latest report and the switches mean for the parent. `capable`: the reporting
/// launcher has [KIOSK_ESCAPES_CAPABILITY]; `reported`: there is a report at all.
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
        card.notes.push(EscapeNote::NeedsUpdate);
        return card;
    }
    if let Some(fence) = fence {
        if fence.state == "fenced" || fence.state == "planned" {
            // A phone that went silent while fenced must not say "now" forever.
            card.notes.push(EscapeNote::Fencing {
                started_at_ms: fence.last_fenced_at_ms,
                stale: fence
                    .last_fenced_at_ms
                    .is_some_and(|at| now_ms.saturating_sub(at) > FENCE_STALE_MS),
            });
        }
        if !fence.unsuspendable.is_empty() {
            card.notes
                .push(EscapeNote::Unsuspendable(fence.unsuspendable.clone()));
        }
        if update_fence && fence.home_role_held == Some(false) {
            card.notes.push(EscapeNote::PartialFence);
        }
        if let Some(release) = fence.last_release.as_deref().and_then(Release::parse) {
            card.notes.push(EscapeNote::LastRelease(release));
        }
        if let Some(tag) = fence.pending_tag.as_deref() {
            card.notes.push(EscapeNote::UpdateWaiting {
                tag: tag.to_string(),
                overdue: fence
                    .pending_since_ms
                    .is_some_and(|since| now_ms.saturating_sub(since) >= 24 * 60 * 60 * 1000),
                waiting_for: fence.waiting_for.as_deref().and_then(wait_text),
            });
        }
    }
    if notification_auto_cancel && listener_enabled == Some(false) {
        card.notes.push(EscapeNote::FilterWithoutAccess);
    }
    if let Some(cancels) = cancels {
        if notification_auto_cancel && !cancels.active && listener_enabled != Some(false) {
            card.notes.push(EscapeNote::FilterNotActive);
        }
        card.removed = cancels
            .entries
            .iter()
            .take(REMOVED_SHOWN)
            .cloned()
            .collect();
        card.more_removed = (cancels.entries.len().saturating_sub(REMOVED_SHOWN) as i64)
            .saturating_add(cancels.dropped);
    }
    card
}

/// Status-report capability: the launcher has the boot cover and reads `boot_cover` (design 16b).
pub const BOOT_COVER_CAPABILITY: &str = "boot_cover_v1";

/// The phone's `boot_cover` (design 16b, qa-16b-code #5).
#[derive(Deserialize, Serialize, Default, Debug, Clone, PartialEq, Eq)]
#[serde(default)]
pub struct BootCoverState {
    /// The phone takes the switch (on, managed, device owner).
    pub wanted: bool,
    /// The cover crashed twice during one start and stays off until the switch goes off and on.
    pub tripped: bool,
    pub last_armed_at_ms: Option<i64>,
    pub last_shown_at_ms: Option<i64>,
    /// "cover" | "launcher" - who disabled it after the unlock.
    pub last_handover: Option<String>,
    pub last_handover_at_ms: Option<i64>,
}

/// `StatusReportRequest.boot_cover` as stored: only the known fields, times clamped, the
/// hand-over only "cover" or "launcher"; `None` for anything that isn't such an object.
pub fn sanitize_boot_cover(value: &serde_json::Value) -> Option<String> {
    if !value.is_object() {
        return None;
    }
    let mut state: BootCoverState = serde_json::from_value(value.clone()).ok()?;
    // A time is a positive wall-clock instant or nothing.
    let time = |ms: Option<i64>| clamp_ms(ms).filter(|&t| t > 0);
    state.last_armed_at_ms = time(state.last_armed_at_ms);
    state.last_shown_at_ms = time(state.last_shown_at_ms);
    state.last_handover_at_ms = time(state.last_handover_at_ms);
    state.last_handover = state
        .last_handover
        .filter(|h| h == "cover" || h == "launcher");
    serde_json::to_string(&state).ok()
}

pub fn parse_boot_cover(json: Option<&str>) -> Option<BootCoverState> {
    json.and_then(|j| serde_json::from_str(j).ok())
}

/// What the phone says about the boot cover: nothing while the switch is off; "update Vibb" when
/// the reporting launcher hasn't got it; the phone's state otherwise - a tripped guard is a
/// warning, so "test it on this phone first" can be checked on Advanced.
pub fn boot_cover_notes(
    switch_on: bool,
    capable: bool,
    reported: bool,
    state: Option<&BootCoverState>,
) -> Vec<EscapeNote> {
    if !switch_on {
        return Vec::new();
    }
    if reported && !capable {
        return vec![EscapeNote::BootCoverNeedsUpdate];
    }
    let Some(state) = state else {
        return Vec::new();
    };
    if state.tripped {
        return vec![EscapeNote::BootCoverTripped];
    }
    vec![match (state.last_armed_at_ms, state.last_shown_at_ms) {
        (None, _) => EscapeNote::BootCoverArmed,
        (Some(armed), Some(shown)) if shown >= armed => EscapeNote::BootCoverShown {
            at_ms: shown,
            handover: match state.last_handover.as_deref() {
                Some("cover") => Some(Handover::ByItself),
                Some("launcher") => Some(Handover::WhenVibbStarted),
                _ => None,
            },
        },
        (Some(armed), _) => EscapeNote::BootCoverNotShown { armed_at_ms: armed },
    }]
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn oslo() -> crate::display_time::Zone {
        crate::display_time::Zone::from_setting("Europe/Oslo")
    }

    fn at(ms: i64) -> chrono::DateTime<chrono::Utc> {
        chrono::DateTime::from_timestamp_millis(ms).unwrap()
    }

    #[test]
    fn boot_cover_state_is_sanitized_and_explained() {
        let stored = sanitize_boot_cover(&serde_json::json!({
            "wanted": true, "tripped": false, "last_armed_at_ms": 1_000, "last_shown_at_ms": 2_000,
            "last_handover": "rm -rf", "last_handover_at_ms": -5, "extra": "x",
        }))
        .unwrap();
        let state = parse_boot_cover(Some(&stored)).unwrap();
        assert_eq!(state.last_handover, None);
        assert_eq!(state.last_handover_at_ms, None);
        assert!(!stored.contains("extra"));
        assert!(sanitize_boot_cover(&serde_json::json!("on")).is_none());

        // Switch off: nothing; an older launcher: update it.
        assert!(boot_cover_notes(false, true, true, Some(&state)).is_empty());
        assert_eq!(
            boot_cover_notes(true, false, true, None),
            vec![EscapeNote::BootCoverNeedsUpdate]
        );
        // Shown after the arm: a row.
        let shown = BootCoverState {
            last_handover: Some("cover".into()),
            ..state.clone()
        };
        let notes = boot_cover_notes(true, true, true, Some(&shown));
        assert_eq!(
            notes,
            vec![EscapeNote::BootCoverShown {
                at_ms: 2_000,
                handover: Some(Handover::ByItself)
            }]
        );
        let note = notes[0].note(&oslo(), at(10_000));
        assert!(!note.warning);
        assert_eq!(note.value.as_deref(), Some("Shown 01:00"));
        assert!(note.detail.unwrap().contains("by itself"));
        // Armed but never shown: a warning.
        let missed = BootCoverState {
            last_shown_at_ms: None,
            ..state.clone()
        };
        let notes = boot_cover_notes(true, true, true, Some(&missed));
        assert_eq!(
            notes,
            vec![EscapeNote::BootCoverNotShown { armed_at_ms: 1_000 }]
        );
        assert!(notes[0].is_warning());
        assert!(
            notes[0]
                .note(&oslo(), at(10_000))
                .detail
                .unwrap()
                .contains("not shown")
        );
        // Tripped: a warning, nothing else.
        let tripped = BootCoverState {
            tripped: true,
            ..state.clone()
        };
        let notes = boot_cover_notes(true, true, true, Some(&tripped));
        assert_eq!(notes, vec![EscapeNote::BootCoverTripped]);
        assert!(
            notes[0]
                .note(&oslo(), at(0))
                .detail
                .unwrap()
                .contains("crashed twice")
        );
        // Not armed yet.
        let notes = boot_cover_notes(true, true, true, Some(&BootCoverState::default()));
        assert_eq!(notes, vec![EscapeNote::BootCoverArmed]);
        assert!(
            notes[0]
                .note(&oslo(), at(0))
                .detail
                .unwrap()
                .contains("power menu")
        );
    }

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
        assert_eq!(card.notes, vec![EscapeNote::NeedsUpdate]);
        assert!(card.notes[0].is_warning());
        assert!(card.removed.is_empty());
        // No report at all: nothing to say.
        let card = escapes_card(true, true, false, false, None, None, None, 0);
        assert!(card.notes.is_empty());
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
            ..Default::default()
        };
        let day = 24 * 60 * 60 * 1000;
        let card = escapes_card(true, false, true, true, Some(&fence), None, None, day);
        assert_eq!(
            card.notes,
            vec![
                EscapeNote::Fencing {
                    started_at_ms: None,
                    stale: false
                },
                EscapeNote::Unsuspendable(vec!["com.oem.home".to_string()]),
                EscapeNote::PartialFence,
                EscapeNote::LastRelease(Release::InstallFailed),
                EscapeNote::UpdateWaiting {
                    tag: "launcher-v1.4.0".to_string(),
                    overdue: true,
                    waiting_for: Some("waiting for the night window"),
                },
            ]
        );
        let warnings: Vec<bool> = card.notes.iter().map(EscapeNote::is_warning).collect();
        assert_eq!(warnings, [false, true, true, true, false]);
        let notes: Vec<_> = card
            .notes
            .iter()
            .map(|n| n.note(&oslo(), at(day)))
            .collect();
        assert_eq!(notes[0].title, "Update installing");
        assert_eq!(notes[0].value.as_deref(), Some("Now"));
        assert!(notes[1].detail.as_deref().unwrap().contains("com.oem.home"));
        assert!(notes[2].detail.as_deref().unwrap().contains("only partial"));
        assert_eq!(notes[3].title, "Last update failed to install");
        assert_eq!(notes[4].value.as_deref(), Some("1.4.0"));
        let waiting = notes[4].detail.as_deref().unwrap();
        assert!(waiting.contains("launcher-v1.4.0"), "{waiting}");
        assert!(waiting.contains("any quiet screen-off"), "{waiting}");
        assert!(
            waiting.contains("waiting for the night window"),
            "{waiting}"
        );
        // The switch off: no partial-fence warning.
        let card = escapes_card(false, false, true, true, Some(&fence), None, None, day);
        assert!(!card.notes.contains(&EscapeNote::PartialFence));
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
        assert!(card.notes.is_empty(), "{:?}", card.notes);
        assert_eq!(card.removed.len(), REMOVED_SHOWN);
        assert_eq!(card.removed[0].package_name, "pkg0");
        assert_eq!(card.removed[0].snoozed, 1);
        assert_eq!(card.more_removed, 3);
        let no_access = escapes_card(false, true, true, true, None, None, Some(false), 0);
        assert_eq!(no_access.notes, vec![EscapeNote::FilterWithoutAccess]);
        assert!(
            no_access.notes[0]
                .note(&oslo(), at(0))
                .detail
                .unwrap()
                .contains("no notification access")
        );
        // On, but not active on the phone yet.
        let idle = NotificationCancels {
            active: false,
            ..Default::default()
        };
        let card = escapes_card(false, true, true, true, None, Some(&idle), Some(true), 0);
        assert_eq!(card.notes, vec![EscapeNote::FilterNotActive]);
    }

    #[test]
    fn extreme_phone_values_neither_overflow_nor_get_stored() {
        let stored = sanitize_update_fence(&json!({
            "state": "fenced", "pending_tag": "t", "pending_since_ms": i64::MIN,
            "last_fenced_at_ms": i64::MAX, "last_released_at_ms": i64::MIN,
        }))
        .unwrap();
        let fence = parse_update_fence(Some(&stored)).unwrap();
        assert_eq!(fence.pending_since_ms, Some(0));
        assert_eq!(fence.last_fenced_at_ms, Some(MAX_MS));
        assert_eq!(fence.last_released_at_ms, Some(0));
        let stored = sanitize_notification_cancels(&json!({
            "entries": [{ "package_name": "a", "cancelled": i64::MAX, "snoozed": i64::MIN }],
            "dropped": i64::MAX,
        }))
        .unwrap();
        let cancels = parse_notification_cancels(Some(&stored)).unwrap();
        assert_eq!(cancels.dropped, MAX_COUNT);
        assert_eq!(cancels.entries[0].cancelled, MAX_COUNT);
        // Unsanitized extremes (an old row) still render without panicking.
        let raw_fence = UpdateFenceState {
            state: "fenced".to_string(),
            pending_tag: Some("t".to_string()),
            pending_since_ms: Some(i64::MIN),
            last_fenced_at_ms: Some(i64::MIN),
            ..Default::default()
        };
        let raw_cancels = NotificationCancels {
            active: true,
            entries: (0..7).map(|_| NotificationCancel::default()).collect(),
            dropped: i64::MAX,
        };
        for now in [i64::MIN, 0, i64::MAX] {
            let card = escapes_card(
                true,
                true,
                true,
                true,
                Some(&raw_fence),
                Some(&raw_cancels),
                None,
                now,
            );
            assert!(!card.notes.is_empty());
            for note in &card.notes {
                note.note(&oslo(), at(0));
            }
            assert_eq!(card.more_removed, i64::MAX);
        }
    }

    #[test]
    fn a_stale_fence_is_no_longer_now() {
        let fence = UpdateFenceState {
            state: "fenced".to_string(),
            last_fenced_at_ms: Some(1_000),
            ..Default::default()
        };
        let card = |now: i64| escapes_card(true, false, true, true, Some(&fence), None, None, now);
        assert_eq!(
            card(1_000 + 60_000).notes,
            vec![EscapeNote::Fencing {
                started_at_ms: Some(1_000),
                stale: false
            }]
        );
        let stale = card(1_000 + FENCE_STALE_MS + 1).notes;
        assert_eq!(
            stale,
            vec![EscapeNote::Fencing {
                started_at_ms: Some(1_000),
                stale: true
            }]
        );
        let note = stale[0].note(&oslo(), at(1_000 + FENCE_STALE_MS + 1));
        assert_eq!(note.title, "Update started");
        assert!(note.detail.unwrap().contains("hasn't reported since"));
        // Exactly 15 minutes is still now.
        assert!(matches!(
            card(1_000 + FENCE_STALE_MS).notes[0],
            EscapeNote::Fencing { stale: false, .. }
        ));
    }

    #[test]
    fn releases() {
        assert_eq!(Release::parse("replaced"), Some(Release::Replaced));
        assert_eq!(Release::parse("replaced_backstop"), Some(Release::Replaced));
        assert_eq!(Release::parse("rebooted"), Some(Release::Rebooted));
        assert_eq!(Release::parse("switched_off"), None);
        assert_eq!(Release::parse("unmanaged"), None);
        assert_eq!(Release::parse("commit_failed"), Some(Release::CommitFailed));
        assert_eq!(Release::parse("orphan"), Some(Release::Orphan));
        assert_eq!(
            Release::parse("weird"),
            Some(Release::Other("weird".to_string()))
        );
        for (release, failed) in [
            (Release::Replaced, false),
            (Release::InstallFailed, true),
            (Release::CommitFailed, true),
            (Release::Orphan, true),
            (Release::Rebooted, false),
            (Release::Expired, false),
        ] {
            assert_eq!(release.failed(), failed, "{release:?}");
        }
        let note = EscapeNote::LastRelease(Release::Other("weird".into())).note(&oslo(), at(0));
        assert!(note.detail.unwrap().contains("weird"));
        let note = EscapeNote::LastRelease(Release::Replaced).note(&oslo(), at(0));
        assert_eq!(
            (note.title.as_str(), note.value.as_deref()),
            ("Last update", Some("Installed"))
        );
    }

    /// Design 37: the "Update waiting" note's "Last check: ..." for Install Now's waits; the four
    /// words fit the stored cap.
    #[test]
    fn install_now_waits_have_words() {
        assert_eq!(
            wait_text("install_now_call"),
            Some("Install Now waits for the call to end")
        );
        assert_eq!(
            wait_text("install_now_emergency"),
            Some("Install Now waits: an emergency call was recent")
        );
        assert_eq!(
            wait_text("install_now_ready"),
            Some("Install Now is installing it")
        );
        assert_eq!(wait_text("install_now_download"), None);
        for word in [
            crate::install_now::WAIT_DOWNLOAD,
            crate::install_now::WAIT_CALL,
            crate::install_now::WAIT_EMERGENCY,
            crate::install_now::WAIT_READY,
        ] {
            assert!(word.len() <= MAX_WORD, "{word}");
        }
    }
}
