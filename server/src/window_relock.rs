//! Parent-PIN windows that lock again (design 24b, `docs/design/24b-override-relock.md` at the
//! monorepo root): an override or a pause the parent PIN opened on the phone ends by itself, and
//! the phone's `relock` report says what it reset when it locked again and what it couldn't undo.
//! The device page shows it, can end a window ("Lock the phone again now", `end_windows`) and can
//! let apps the relock blocked back (`allow_blocked`). Pure, unit-tested; the handlers live in
//! `handlers::window_relock`.

use serde::{Deserialize, Serialize};
use serde_json::Value;

/// The launcher reports `relock` and handles the two commands.
pub const WINDOW_RELOCK_CAPABILITY: &str = "window_relock_v1";
/// The two commands of this design: their own insert, never deleted by another command and
/// deleting none (QA #8).
pub const END_WINDOWS: &str = "end_windows";
pub const ALLOW_BLOCKED: &str = "allow_blocked";
pub const OWN_INSERT_COMMANDS: [&str; 2] = [END_WINDOWS, ALLOW_BLOCKED];

/// At most this many package names per list (the launcher's `RELOCK_LIST_MAX`).
pub const LIST_MAX: usize = 8;
/// The device page shows a relock's findings this long.
pub const WARN_MS: i64 = 7 * 24 * 60 * 60 * 1000;
/// Phone-supplied times are kept within 1970..2100, counts within 0..1000.
const MAX_MS: i64 = 4_102_444_800_000;
const MAX_COUNT: i64 = 1000;

const TRIGGERS: [&str; 7] = [
    "timer",
    "lock_again",
    "command",
    "sync",
    "boot",
    "screen_on",
    "settings",
];
const WINDOWS: [&str; 2] = ["override", "pause"];
const FAILURES: [&str; 9] = [
    "credential",
    "signer_check",
    "apply",
    "permitted_lists",
    "proxies",
    "ca_certs",
    "notifications",
    "detections",
    "finish",
];

/// `StatusReportRequest.relock`, as stored (`device_status.relock_json`): only these fields.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq, Default)]
#[serde(default)]
pub struct RelockReport {
    pub at_ms: i64,
    pub trigger: String,
    pub windows: Vec<String>,
    pub proxies_cleared: i64,
    pub proxy_left: bool,
    pub ca_certs_removed: i64,
    pub credential_removed: bool,
    pub credential_left: bool,
    pub blocked: Vec<String>,
    pub accessibility_enabled: Vec<String>,
    pub admins_enabled: Vec<String>,
    pub apps_disabled: Vec<String>,
    pub notification_listeners: Vec<String>,
    pub own_notifications_off: bool,
    pub failures: Vec<String>,
}

fn int(value: &Value, key: &str) -> i64 {
    value
        .get(key)
        .and_then(Value::as_i64)
        .unwrap_or(0)
        .clamp(0, MAX_COUNT)
}

fn flag(value: &Value, key: &str) -> bool {
    value.get(key).and_then(Value::as_bool).unwrap_or(false)
}

/// Strings of [allowed] (or, without it, valid package names), each once, at most [LIST_MAX].
fn names(value: &Value, key: &str, allowed: Option<&[&str]>) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for item in value
        .get(key)
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
    {
        let Some(name) = item.as_str() else { continue };
        let ok = match allowed {
            Some(set) => set.contains(&name),
            None => crate::time_rules::valid_package_name(name),
        };
        if ok && !out.iter().any(|n| n == name) {
            out.push(name.to_string());
        }
        if out.len() == LIST_MAX {
            break;
        }
    }
    out
}

/// The phone's `relock`, re-serialized through the known fields: valid package names, known
/// windows, triggers and step names, counts and times in range. `None` for anything that isn't
/// an object with an `at_ms`.
pub fn sanitize_relock(value: &Value) -> Option<String> {
    if !value.is_object() {
        return None;
    }
    let at_ms = value.get("at_ms")?.as_i64()?.clamp(0, MAX_MS);
    let trigger = value
        .get("trigger")
        .and_then(Value::as_str)
        .filter(|t| TRIGGERS.contains(t))
        .unwrap_or("")
        .to_string();
    let report = RelockReport {
        at_ms,
        trigger,
        windows: names(value, "windows", Some(&WINDOWS)),
        proxies_cleared: int(value, "proxies_cleared"),
        proxy_left: flag(value, "proxy_left"),
        ca_certs_removed: int(value, "ca_certs_removed"),
        credential_removed: flag(value, "credential_removed"),
        credential_left: flag(value, "credential_left"),
        blocked: names(value, "blocked", None),
        accessibility_enabled: names(value, "accessibility_enabled", None),
        admins_enabled: names(value, "admins_enabled", None),
        apps_disabled: names(value, "apps_disabled", None),
        notification_listeners: names(value, "notification_listeners", None),
        own_notifications_off: flag(value, "own_notifications_off"),
        failures: names(value, "failures", Some(&FAILURES)),
    };
    serde_json::to_string(&report).ok()
}

pub fn parse_relock(json: Option<&str>) -> Option<RelockReport> {
    json.and_then(|j| serde_json::from_str(j).ok())
}

/// Something was reset, blocked, left on or failed - worth telling the parent.
pub fn found_anything(r: &RelockReport) -> bool {
    r.proxies_cleared > 0
        || r.proxy_left
        || r.ca_certs_removed > 0
        || r.credential_removed
        || r.credential_left
        || !r.blocked.is_empty()
        || !r.accessibility_enabled.is_empty()
        || !r.admins_enabled.is_empty()
        || !r.apps_disabled.is_empty()
        || !r.notification_listeners.is_empty()
        || r.own_notifications_off
        || !r.failures.is_empty()
}

fn trigger_text(trigger: &str) -> &'static str {
    match trigger {
        "timer" => "because its time was up",
        "lock_again" => "after \"Lock again\" on the phone",
        "command" => "after \"Lock the phone again now\" here",
        "sync" => "when it reached this server",
        "boot" => "after a restart",
        "screen_on" => "when the screen came on",
        "settings" => "after the pause was switched off in Settings",
        _ => "",
    }
}

fn count(n: i64, one: &str, many: &str) -> String {
    if n == 1 {
        format!("1 {one}")
    } else {
        format!("{n} {many}")
    }
}

/// "a, b and c".
fn and_list(parts: &[String]) -> String {
    match parts {
        [] => String::new(),
        [one] => one.clone(),
        [rest @ .., last] => format!("{} and {last}", rest.join(", ")),
    }
}

/// The device page's lines about the last relock (§1.7), each a warning, only while it is under
/// 7 days old and something was found. [allowlist]: the server's, so "Allow these apps" is shown
/// only for blocked apps the parent allowed ([RelockNotes::allow_button]).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct RelockNotes {
    pub lines: Vec<String>,
    /// Some blocked app is on the allowed list: "Allow these apps" brings it back.
    pub allow_button: bool,
}

pub fn relock_notes(r: &RelockReport, now_ms: i64, allowlist: &[String]) -> RelockNotes {
    if now_ms - r.at_ms > WARN_MS || !found_anything(r) {
        return RelockNotes::default();
    }
    let mut lines = Vec::new();
    let when = crate::parent_pin::when_text(r.at_ms, now_ms);
    let how = match trigger_text(&r.trigger) {
        "" => String::new(),
        text => format!(", {text}"),
    };
    let mut removed = Vec::new();
    if r.proxies_cleared > 0 {
        removed.push(count(r.proxies_cleared, "Wi-Fi proxy", "Wi-Fi proxies"));
    }
    if r.ca_certs_removed > 0 {
        removed.push(count(r.ca_certs_removed, "certificate", "certificates"));
    }
    if r.credential_removed {
        removed.push("an Android screen lock the child had set".to_string());
    }
    lines.push(if removed.is_empty() {
        format!("The phone locked again at {when}{how}.")
    } else {
        format!(
            "When the phone locked again at {when}{how}, it removed {}.",
            and_list(&removed)
        )
    });
    let allow_button = r.blocked.iter().any(|p| allowlist.contains(p));
    if !r.blocked.is_empty() {
        let mut line = format!(
            "Blocked: {} (installed or replaced while it was open).",
            r.blocked.join(", ")
        );
        if allow_button {
            line.push_str(" \"Allow these apps\" shows them again if they are yours.");
        }
        if r.blocked.iter().any(|p| !allowlist.contains(p)) {
            line.push_str(" Apps not ticked under Apps stay hidden until you tick them.");
        }
        lines.push(line);
    }
    let mut still = Vec::new();
    still.extend(
        r.accessibility_enabled
            .iter()
            .map(|p| format!("accessibility service {p}")),
    );
    still.extend(
        r.notification_listeners
            .iter()
            .map(|p| format!("notification access {p}")),
    );
    still.extend(r.admins_enabled.iter().map(|p| format!("device admin {p}")));
    if r.proxy_left {
        still.push("a proxy on mobile data".to_string());
    }
    if r.credential_left {
        still.push("an Android screen lock the child set (it couldn't be removed)".to_string());
    }
    if !still.is_empty() {
        lines.push(format!("Still on: {}.", still.join("; ")));
    }
    if !r.apps_disabled.is_empty() {
        lines.push(format!(
            "Turned off and can't be turned back on remotely: {} - turn them on in the phone's \
             Settings during a pause.",
            r.apps_disabled.join(", ")
        ));
    }
    if r.own_notifications_off {
        lines.push("The launcher's notifications are off.".to_string());
    }
    if !r.failures.is_empty() {
        lines.push(format!(
            "Steps that failed on the phone: {}.",
            r.failures.join(", ")
        ));
    }
    RelockNotes {
        lines,
        allow_button,
    }
}

/// The security log's line for a new relock with findings (`phone_relock_findings`): when it
/// happened on the phone, then what it found - built only from the sanitized report.
pub fn log_line(device_id: i64, r: &RelockReport) -> String {
    let at = chrono::DateTime::from_timestamp_millis(r.at_ms)
        .map(|t| t.format("%Y-%m-%d %H:%M UTC").to_string())
        .unwrap_or_else(|| "unknown".to_string());
    let mut parts = Vec::new();
    if r.proxies_cleared > 0 {
        parts.push(format!("Wi-Fi proxies removed: {}", r.proxies_cleared));
    }
    if r.ca_certs_removed > 0 {
        parts.push(format!("certificates removed: {}", r.ca_certs_removed));
    }
    if r.credential_removed {
        parts.push("Android screen lock removed".to_string());
    }
    for (label, list) in [
        ("blocked", &r.blocked),
        ("accessibility on", &r.accessibility_enabled),
        ("notification access", &r.notification_listeners),
        ("device admin", &r.admins_enabled),
        ("turned off", &r.apps_disabled),
        ("failed", &r.failures),
    ] {
        if !list.is_empty() {
            parts.push(format!("{label}: {}", list.join(", ")));
        }
    }
    if r.proxy_left {
        parts.push("a proxy left on mobile data".to_string());
    }
    if r.credential_left {
        parts.push("an Android screen lock left".to_string());
    }
    if r.own_notifications_off {
        parts.push("launcher notifications off".to_string());
    }
    let how = match r.trigger.as_str() {
        "" => String::new(),
        t => format!(" ({t})"),
    };
    format!(
        "device {device_id}, {at}: locked again{how} - {}.",
        parts.join("; ")
    )
    .chars()
    .take(1000)
    .collect()
}

/// The one-shot message after a command was queued (`?notice=`), shown in the Status card.
pub fn flash_text(code: &str) -> Option<&'static str> {
    match code {
        "end_windows_sent" => {
            Some("Sent: the phone locks again as soon as it gets this (usually within seconds).")
        }
        "allow_blocked_sent" => Some(
            "Sent: the phone shows the blocked apps again as soon as it gets this - the ones \
             ticked under Apps.",
        ),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    /// Design 24b §1.7's literal, as the launcher sends it.
    pub(crate) fn literal() -> Value {
        json!({ "at_ms": 1760100000000_i64, "trigger": "timer", "windows": ["override"],
                "proxies_cleared": 1, "proxy_left": false, "ca_certs_removed": 0,
                "credential_removed": false, "credential_left": false,
                "blocked": ["com.example.app"], "accessibility_enabled": [], "admins_enabled": [],
                "apps_disabled": [], "notification_listeners": [], "own_notifications_off": false,
                "failures": [] })
    }

    #[test]
    fn the_literal_survives_sanitizing_unchanged() {
        let stored = sanitize_relock(&literal()).unwrap();
        let back: Value = serde_json::from_str(&stored).unwrap();
        assert_eq!(back, literal());
    }

    #[test]
    fn sanitizing_keeps_known_values_in_range() {
        let raw = json!({ "at_ms": -5, "trigger": "<script>", "windows": ["override", "install_mode", "override"],
            "proxies_cleared": 99999, "ca_certs_removed": -3, "blocked": ["com.ok.app", "not a package", "<b>x</b>", 5],
            "accessibility_enabled": (0..20).map(|i| format!("com.a.b{i}")).collect::<Vec<_>>(),
            "failures": ["apply", "rm -rf", "apply"], "extra": "dropped", "own_notifications_off": "yes" });
        let r = parse_relock(sanitize_relock(&raw).as_deref()).unwrap();
        assert_eq!(r.at_ms, 0);
        assert_eq!(r.trigger, "");
        assert_eq!(r.windows, vec!["override"]);
        assert_eq!(r.proxies_cleared, 1000);
        assert_eq!(r.ca_certs_removed, 0);
        assert_eq!(r.blocked, vec!["com.ok.app"]);
        assert_eq!(r.accessibility_enabled.len(), LIST_MAX);
        assert_eq!(r.failures, vec!["apply"]);
        assert!(!r.own_notifications_off);
        assert!(!sanitize_relock(&raw).unwrap().contains("extra"));
        assert_eq!(sanitize_relock(&json!("x")), None);
        assert_eq!(sanitize_relock(&json!({ "trigger": "timer" })), None);
    }

    fn report() -> RelockReport {
        parse_relock(sanitize_relock(&literal()).as_deref()).unwrap()
    }

    const NOW: i64 = 1_760_100_000_000 + 3 * 60 * 60 * 1000;

    #[test]
    fn every_line_of_the_warning() {
        let r = RelockReport {
            proxies_cleared: 2,
            ca_certs_removed: 1,
            credential_removed: true,
            proxy_left: true,
            credential_left: false,
            blocked: vec!["com.example.app".into(), "com.example.game".into()],
            accessibility_enabled: vec!["com.evil.ally".into()],
            admins_enabled: vec!["com.evil.admin".into()],
            apps_disabled: vec!["com.android.inputmethod.latin".into()],
            notification_listeners: vec!["com.evil.reader".into()],
            own_notifications_off: true,
            failures: vec!["ca_certs".into()],
            ..report()
        };
        let notes = relock_notes(&r, NOW, &["com.example.app".to_string()]);
        assert!(notes.allow_button);
        assert_eq!(
            notes.lines,
            vec![
                "When the phone locked again at 2025-10-10 12:40 UTC (3 h ago), because its time was up, it removed 2 Wi-Fi proxies, 1 certificate and an Android screen lock the child had set.",
                "Blocked: com.example.app, com.example.game (installed or replaced while it was open). \"Allow these apps\" shows them again if they are yours. Apps not ticked under Apps stay hidden until you tick them.",
                "Still on: accessibility service com.evil.ally; notification access com.evil.reader; device admin com.evil.admin; a proxy on mobile data.",
                "Turned off and can't be turned back on remotely: com.android.inputmethod.latin - turn them on in the phone's Settings during a pause.",
                "The launcher's notifications are off.",
                "Steps that failed on the phone: ca_certs.",
            ]
        );
    }

    #[test]
    fn the_first_line_and_the_triggers() {
        let mut r = report();
        r.proxies_cleared = 0;
        r.blocked.clear();
        r.credential_left = true;
        for (trigger, text) in [
            ("timer", "because its time was up"),
            ("lock_again", "after \"Lock again\" on the phone"),
            ("command", "after \"Lock the phone again now\" here"),
            ("sync", "when it reached this server"),
            ("boot", "after a restart"),
            ("screen_on", "when the screen came on"),
            ("settings", "after the pause was switched off in Settings"),
        ] {
            r.trigger = trigger.into();
            let notes = relock_notes(&r, NOW, &[]);
            assert_eq!(
                notes.lines[0],
                format!("The phone locked again at 2025-10-10 12:40 UTC (3 h ago), {text}.")
            );
            assert_eq!(
                notes.lines[1],
                "Still on: an Android screen lock the child set (it couldn't be removed)."
            );
            assert!(!notes.allow_button);
        }
        r.trigger = String::new();
        assert_eq!(
            relock_notes(&r, NOW, &[]).lines[0],
            "The phone locked again at 2025-10-10 12:40 UTC (3 h ago)."
        );
    }

    #[test]
    fn nothing_found_or_older_than_a_week_shows_nothing() {
        let mut quiet = report();
        quiet.proxies_cleared = 0;
        quiet.blocked.clear();
        assert!(!found_anything(&quiet));
        assert_eq!(relock_notes(&quiet, NOW, &[]), RelockNotes::default());
        let old = report();
        assert!(
            !relock_notes(&old, old.at_ms + WARN_MS, &[])
                .lines
                .is_empty()
        );
        assert_eq!(
            relock_notes(&old, old.at_ms + WARN_MS + 1, &[]),
            RelockNotes::default()
        );
        // Not allowlisted: no button, only the tick hint.
        let notes = relock_notes(&old, NOW, &[]);
        assert!(!notes.allow_button);
        assert!(
            notes.lines[1].ends_with("Apps not ticked under Apps stay hidden until you tick them.")
        );
    }

    #[test]
    fn the_log_line_and_the_flash_texts() {
        assert_eq!(
            log_line(3, &report()),
            "device 3, 2025-10-10 12:40 UTC: locked again (timer) - Wi-Fi proxies removed: 1; blocked: com.example.app."
        );
        assert!(flash_text("end_windows_sent").is_some());
        assert!(flash_text("allow_blocked_sent").is_some());
        assert_eq!(flash_text("parent_pin_saved"), None);
    }
}
