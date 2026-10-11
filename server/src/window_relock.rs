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
/// The commands of this design: their own insert, never deleted by another command and deleting
/// none (QA #8). `start_pause` (the fix round, QA High 1): the phone's 2-hour pause, started here
/// without the parent PIN on the phone.
pub const END_WINDOWS: &str = "end_windows";
pub const ALLOW_BLOCKED: &str = "allow_blocked";
pub const START_PAUSE: &str = "start_pause";
pub const OWN_INSERT_COMMANDS: [&str; 3] = [END_WINDOWS, ALLOW_BLOCKED, START_PAUSE];

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
    /// Each blocked app's signing key (SHA-256 of its first certificate, lower-case hex) - fix
    /// round QA Medium 2: "Allow these apps" shows it, and the catalog's pin is compared with it.
    pub blocked_signers: std::collections::BTreeMap<String, String>,
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

/// A signing key as M10 pins it: SHA-256, 64 lower-case hex characters.
pub fn valid_signer(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

/// `blocked_signers`: only for packages in [blocked], only valid keys.
fn signers(value: &Value, blocked: &[String]) -> std::collections::BTreeMap<String, String> {
    value
        .get("blocked_signers")
        .and_then(Value::as_object)
        .into_iter()
        .flatten()
        .filter(|(name, _)| blocked.contains(name))
        .filter_map(|(name, key)| {
            key.as_str()
                .filter(|k| valid_signer(k))
                .map(|k| (name.clone(), k.to_string()))
        })
        .collect()
}

/// "3f2a1b0c…9d8e7f60": enough of a key for a parent to compare.
fn short_key(key: &str) -> String {
    if key.len() < 16 {
        key.to_string()
    } else {
        format!("{}…{}", &key[..8], &key[key.len() - 8..])
    }
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
        blocked_signers: signers(value, &names(value, "blocked", None)),
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

/// `StatusReportRequest.foreign_access` (the fix round, QA High 1 (b)), as stored
/// (`device_status.foreign_access_json`): a device admin or an accessibility service of an app
/// outside the allowlist. The phone refuses the parent PIN while it reports one - such an app can't
/// be hidden or stopped and can read the screen.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq, Default)]
#[serde(default)]
pub struct ForeignAccess {
    pub admins: Vec<String>,
    pub accessibility: Vec<String>,
}

/// Valid package names only, at most [LIST_MAX] each; `None` when nothing is left.
pub fn sanitize_foreign_access(value: &Value) -> Option<String> {
    if !value.is_object() {
        return None;
    }
    let found = ForeignAccess {
        admins: names(value, "admins", None),
        accessibility: names(value, "accessibility", None),
    };
    if found.admins.is_empty() && found.accessibility.is_empty() {
        return None;
    }
    serde_json::to_string(&found).ok()
}

pub fn parse_foreign_access(json: Option<&str>) -> Option<ForeignAccess> {
    json.and_then(|j| serde_json::from_str(j).ok())
}

/// The Status card's red warning: what was found, then the steps (the template adds the last
/// one, "Change the parent PIN", with its link).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ForeignNote {
    pub text: String,
    pub steps: Vec<String>,
}

/// [paused]: the latest report has a pause on - step 1 is done.
pub fn foreign_note(found: &ForeignAccess, paused: bool) -> ForeignNote {
    let mut what = Vec::new();
    what.extend(found.admins.iter().map(|p| format!("device admin {p}")));
    what.extend(
        found
            .accessibility
            .iter()
            .map(|p| format!("accessibility service {p}")),
    );
    let mut apps: Vec<String> = found.admins.clone();
    for p in &found.accessibility {
        if !apps.contains(p) {
            apps.push(p.clone());
        }
    }
    let mut remove = Vec::new();
    if !found.admins.is_empty() {
        remove.push(format!(
            "Security > Device admin apps: turn {} off",
            and_list(&found.admins)
        ));
    }
    if !found.accessibility.is_empty() {
        remove.push(format!(
            "Accessibility: turn {} off",
            and_list(&found.accessibility)
        ));
    }
    let (from, it) = if apps.len() == 1 {
        ("an app you haven't allowed. It can", "it's")
    } else {
        ("apps you haven't allowed. They can", "they're")
    };
    ForeignNote {
        text: format!(
            "The phone refuses the parent PIN because of {}, from {from} control the phone or read \
             its screen - the parent PIN too - and the phone can't hide or stop them. Don't type \
             the parent PIN on the phone until {it} gone:",
            and_list(&what)
        ),
        steps: vec![
            if paused {
                "The phone's restrictions are paused (the last report): it is open without the \
                 parent PIN."
                    .to_string()
            } else {
                "Press \"Pause restrictions for 2 hours\" below: the phone opens without the \
                 parent PIN."
                    .to_string()
            },
            format!(
                "On the phone, in Settings: {}. Then uninstall {} (Settings > Apps).",
                remove.join("; "),
                and_list(&apps)
            ),
            "Press \"Lock the phone again now\" below. The phone takes the parent PIN again once \
             nothing unknown is on."
                .to_string(),
        ],
    }
}

/// The security log's line when the finding appears, changes or goes (`phone_foreign_access`).
pub fn foreign_log_line(device_id: i64, found: Option<&ForeignAccess>) -> String {
    match found {
        Some(f) => {
            let mut parts = Vec::new();
            if !f.admins.is_empty() {
                parts.push(format!("device admin: {}", f.admins.join(", ")));
            }
            if !f.accessibility.is_empty() {
                parts.push(format!("accessibility on: {}", f.accessibility.join(", ")));
            }
            format!(
                "device {device_id}: the parent PIN is refused on the phone - {}.",
                parts.join("; ")
            )
        }
        None => format!(
            "device {device_id}: nothing unknown any more - the phone takes the parent PIN again."
        ),
    }
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
/// 7 days old and something was found. [pins]: the catalog's pinned signers (M10, package -> key);
/// a blocked app signed with another key stays blocked, so "Allow these apps" is shown only for the
/// others ([RelockNotes::allow_button]).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct RelockNotes {
    pub lines: Vec<String>,
    /// Some blocked app is on the allowed list: "Allow these apps" brings it back.
    pub allow_button: bool,
}

pub fn relock_notes(
    r: &RelockReport,
    now_ms: i64,
    pins: &std::collections::HashMap<String, String>,
) -> RelockNotes {
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
    // Signed with another key than the catalog pinned: not the catalog's app, whatever its name.
    let not_catalogs = |p: &String| matches!((pins.get(p), r.blocked_signers.get(p)), (Some(pin), Some(key)) if pin != key);
    let allow_button = r.blocked.iter().any(|p| !not_catalogs(p));
    if !r.blocked.is_empty() {
        let apps: Vec<String> = r
            .blocked
            .iter()
            .map(|p| match r.blocked_signers.get(p) {
                Some(key) => format!("{p} (signing key {})", short_key(key)),
                None => p.clone(),
            })
            .collect();
        let mut line = format!(
            "Blocked: {} - installed or replaced from outside Play while it was open, or signed \
             with another key, so ticking it under Apps doesn't show it.",
            apps.join(", ")
        );
        for p in r.blocked.iter().filter(|p| not_catalogs(p)) {
            line.push_str(&format!(
                " {p} is signed with another key than the catalog's ({}), so it stays blocked: \
                 uninstall it.",
                short_key(&pins[p])
            ));
        }
        if allow_button {
            line.push_str(
                " If one is yours and its key is the one you expect, tick it under Apps and press \
                 \"Allow these apps\".",
            );
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
        still.push("a proxy on a network (mobile data or Wi-Fi)".to_string());
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
        parts.push("a proxy left on a network".to_string());
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
        "start_pause_sent" => Some(
            "Sent: the phone pauses its restrictions for 2 hours as soon as it gets this (usually \
             within seconds).",
        ),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::collections::HashMap;

    /// Design 24b §1.7's literal, as the launcher sends it.
    pub(crate) fn literal() -> Value {
        json!({ "at_ms": 1760100000000_i64, "trigger": "timer", "windows": ["override"],
                "proxies_cleared": 1, "proxy_left": false, "ca_certs_removed": 0,
                "credential_removed": false, "credential_left": false,
                "blocked": ["com.example.app"],
                "blocked_signers": {"com.example.app": "3f2a00000000000000000000000000000000000000000000000000000000c0de"},
                "accessibility_enabled": [], "admins_enabled": [],
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
            "blocked_signers": {"com.ok.app": "AB12", "not a package": "3f2a00000000000000000000000000000000000000000000000000000000c0de"},
            "accessibility_enabled": (0..20).map(|i| format!("com.a.b{i}")).collect::<Vec<_>>(),
            "failures": ["apply", "rm -rf", "apply"], "extra": "dropped", "own_notifications_off": "yes" });
        let r = parse_relock(sanitize_relock(&raw).as_deref()).unwrap();
        assert_eq!(r.at_ms, 0);
        assert_eq!(r.trigger, "");
        assert_eq!(r.windows, vec!["override"]);
        assert_eq!(r.proxies_cleared, 1000);
        assert_eq!(r.ca_certs_removed, 0);
        assert_eq!(r.blocked, vec!["com.ok.app"]);
        // A key that isn't 64 lower-case hex, or for an app not blocked: dropped.
        assert!(r.blocked_signers.is_empty());
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
        let notes = relock_notes(&r, NOW, &HashMap::new());
        assert!(notes.allow_button);
        assert_eq!(
            notes.lines,
            vec![
                "When the phone locked again at 2025-10-10 12:40 UTC (3 h ago), because its time was up, it removed 2 Wi-Fi proxies, 1 certificate and an Android screen lock the child had set.",
                "Blocked: com.example.app (signing key 3f2a0000…0000c0de), com.example.game - installed or replaced from outside Play while it was open, or signed with another key, so ticking it under Apps doesn't show it. If one is yours and its key is the one you expect, tick it under Apps and press \"Allow these apps\".",
                "Still on: accessibility service com.evil.ally; notification access com.evil.reader; device admin com.evil.admin; a proxy on a network (mobile data or Wi-Fi).",
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
            let notes = relock_notes(&r, NOW, &HashMap::new());
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
            relock_notes(&r, NOW, &HashMap::new()).lines[0],
            "The phone locked again at 2025-10-10 12:40 UTC (3 h ago)."
        );
    }

    #[test]
    fn nothing_found_or_older_than_a_week_shows_nothing() {
        let mut quiet = report();
        quiet.proxies_cleared = 0;
        quiet.blocked.clear();
        assert!(!found_anything(&quiet));
        assert_eq!(
            relock_notes(&quiet, NOW, &HashMap::new()),
            RelockNotes::default()
        );
        let old = report();
        assert!(
            !relock_notes(&old, old.at_ms + WARN_MS, &HashMap::new())
                .lines
                .is_empty()
        );
        assert_eq!(
            relock_notes(&old, old.at_ms + WARN_MS + 1, &HashMap::new()),
            RelockNotes::default()
        );
    }

    #[test]
    fn an_app_with_another_key_than_the_catalogs_stays_blocked() {
        let r = report();
        let mut pins = HashMap::new();
        pins.insert("com.example.app".to_string(), "9".repeat(64));
        let notes = relock_notes(&r, NOW, &pins);
        // Its only blocked app isn't the catalog's: no button, and why.
        assert!(!notes.allow_button);
        assert!(
            notes.lines[1].ends_with(
                " com.example.app is signed with another key than the catalog's (99999999…99999999), so it stays blocked: uninstall it."
            ),
            "{}",
            notes.lines[1]
        );
        // The catalog's own key: the button.
        pins.insert(
            "com.example.app".to_string(),
            r.blocked_signers["com.example.app"].clone(),
        );
        assert!(relock_notes(&r, NOW, &pins).allow_button);
    }

    #[test]
    fn the_log_line_and_the_flash_texts() {
        assert_eq!(
            log_line(3, &report()),
            "device 3, 2025-10-10 12:40 UTC: locked again (timer) - Wi-Fi proxies removed: 1; blocked: com.example.app."
        );
        assert!(flash_text("end_windows_sent").is_some());
        assert!(flash_text("allow_blocked_sent").is_some());
        assert!(flash_text("start_pause_sent").is_some());
        assert_eq!(flash_text("parent_pin_saved"), None);
    }

    #[test]
    fn foreign_access_is_kept_through_valid_names_only() {
        let raw = json!({ "admins": ["com.evil.admin", "not a package", "com.evil.admin"],
                          "accessibility": ["com.evil.reader"], "extra": 1 });
        let stored = sanitize_foreign_access(&raw).unwrap();
        assert_eq!(
            parse_foreign_access(Some(&stored)),
            Some(ForeignAccess {
                admins: vec!["com.evil.admin".to_string()],
                accessibility: vec!["com.evil.reader".to_string()],
            })
        );
        // Nothing valid, or not an object: nothing stored.
        assert_eq!(
            sanitize_foreign_access(&json!({ "admins": ["x"], "accessibility": [] })),
            None
        );
        assert_eq!(sanitize_foreign_access(&json!([])), None);
        assert_eq!(parse_foreign_access(Some("garbage")), None);
    }

    #[test]
    fn the_foreign_access_warning_names_it_and_gives_the_steps() {
        let found = ForeignAccess {
            admins: vec!["com.evil.admin".to_string()],
            accessibility: vec!["com.evil.admin".to_string(), "com.evil.reader".to_string()],
        };
        assert!(
            foreign_note(&found, true).steps[0].starts_with("The phone's restrictions are paused")
        );
        let note = foreign_note(&found, false);
        assert_eq!(
            note.text,
            "The phone refuses the parent PIN because of device admin com.evil.admin, \
             accessibility service com.evil.admin and accessibility service com.evil.reader, from \
             apps you haven't allowed. They can control the phone or read its screen - the parent \
             PIN too - and the phone can't hide or stop them. Don't type the parent PIN on the \
             phone until they're gone:"
        );
        assert_eq!(note.steps.len(), 3);
        assert!(note.steps[0].contains("Pause restrictions for 2 hours"));
        assert_eq!(
            note.steps[1],
            "On the phone, in Settings: Security > Device admin apps: turn com.evil.admin off; \
             Accessibility: turn com.evil.admin and com.evil.reader off. Then uninstall \
             com.evil.admin and com.evil.reader (Settings > Apps)."
        );
        assert!(note.steps[2].contains("Lock the phone again now"));
        assert_eq!(
            foreign_log_line(4, Some(&found)),
            "device 4: the parent PIN is refused on the phone - device admin: com.evil.admin; \
             accessibility on: com.evil.admin, com.evil.reader."
        );
        assert!(foreign_log_line(4, None).contains("takes the parent PIN again"));
    }
}
