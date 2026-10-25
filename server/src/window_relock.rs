//! Parent-PIN windows that lock again (design 24b, `docs/design/24b-override-relock.md` at the
//! monorepo root): an override or a pause the parent PIN opened on the phone ends by itself, and
//! the phone's `relock` report says what it reset when it locked again and what it couldn't undo.
//! The phone's summary shows it typed (design 31 step 10: the words are the template's), can end
//! a window ("Lock again", `end_windows`), open one ("Unlock for 2 h…", `start_pause`) and let
//! blocked apps back ("Allow these apps…", `allow_blocked`). Pure, unit-tested; the handlers live
//! in `handlers::window_relock`. Times are in the Phone Setup zone (`display_time`); the security
//! log's lines stay UTC.

use std::collections::{HashMap, HashSet};

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::display_time::Zone;

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
/// The summary shows a relock's findings, an older launcher's blocked apps and the commands'
/// answers this long.
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

/// One entry of `StatusReportRequest.relock_blocks` (fix of 2026-10-12), as stored
/// (`device_status.relock_blocks_json`): an app the phone keeps hidden even when allowed under Apps
/// until "Allow these apps" - set by a relock, or by the install check outside windows.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub struct RelockBlock {
    pub package_name: String,
    /// Its signing key now (SHA-256, lower-case hex); `None` = unknown.
    pub signer: Option<String>,
    /// `outside_play | other_key | catalog_key | unknown`.
    pub reason: String,
    /// `relock | install`.
    pub found: String,
    /// When it was blocked (the phone's clock); `None` = unknown.
    pub at_ms: Option<i64>,
}

/// One app "Allow these apps" lets out (QA of cmd-expiry, Low 1): the package and the signing key
/// the summary showed for it (`None` = the phone couldn't read it). Sent with the command
/// (`PendingCommand.allow`); the phone lets out only a listed block whose key is still that one -
/// never a block that came after the parent looked, nor an app replaced since.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub struct AllowedBlock {
    pub package_name: String,
    pub signer: Option<String>,
}

/// The summary's hidden form value for "Allow these apps…": `package:key` (the key empty when
/// unknown), comma-separated - [parse_allow_form] reads it back.
pub fn allow_form_value(allow: &[AllowedBlock]) -> String {
    allow
        .iter()
        .map(|a| format!("{}:{}", a.package_name, a.signer.as_deref().unwrap_or("")))
        .collect::<Vec<_>>()
        .join(",")
}

/// The posted list: valid package names, each once, with a valid key or none; anything else is
/// left out. At most [BLOCKS_MAX] - the card never lists more.
pub fn parse_allow_form(value: &str) -> Vec<AllowedBlock> {
    let mut out: Vec<AllowedBlock> = Vec::new();
    for entry in value.split(',') {
        let Some((name, key)) = entry.trim().split_once(':') else {
            continue;
        };
        if !crate::time_rules::valid_package_name(name)
            || out.iter().any(|a| a.package_name == name)
            || !(key.is_empty() || valid_signer(key))
        {
            continue;
        }
        out.push(AllowedBlock {
            package_name: name.to_string(),
            signer: (!key.is_empty()).then(|| key.to_string()),
        });
        if out.len() == BLOCKS_MAX {
            break;
        }
    }
    out
}

/// At most this many blocks are kept (the launcher's `RELOCK_BLOCKS_MAX`).
pub const BLOCKS_MAX: usize = 16;
const BLOCK_REASONS: [&str; 4] = ["outside_play", "other_key", "catalog_key", "unknown"];
const BLOCK_FOUND: [&str; 2] = ["relock", "install"];

/// The phone's `relock_blocks`: entries with a valid package name (each once), a valid key or none,
/// a known reason and place (else `unknown` / `relock`), a time in range; at most [BLOCKS_MAX].
/// `Some("[]")` for an empty list - the phone reports its blocks, and has none; `None` for anything
/// that isn't a list.
pub fn sanitize_relock_blocks(value: &Value) -> Option<String> {
    let items = value.as_array()?;
    let mut blocks: Vec<RelockBlock> = Vec::new();
    for item in items {
        let Some(name) = item.get("package_name").and_then(Value::as_str) else {
            continue;
        };
        if !crate::time_rules::valid_package_name(name)
            || blocks.iter().any(|b| b.package_name == name)
        {
            continue;
        }
        let pick = |key: &str, known: &[&str], default: &str| {
            item.get(key)
                .and_then(Value::as_str)
                .filter(|v| known.contains(v))
                .unwrap_or(default)
                .to_string()
        };
        blocks.push(RelockBlock {
            package_name: name.to_string(),
            signer: item
                .get("signer")
                .and_then(Value::as_str)
                .filter(|k| valid_signer(k))
                .map(str::to_string),
            reason: pick("reason", &BLOCK_REASONS, "unknown"),
            found: pick("found", &BLOCK_FOUND, "relock"),
            at_ms: item
                .get("at_ms")
                .and_then(Value::as_i64)
                .map(|ms| ms.clamp(0, MAX_MS)),
        });
        if blocks.len() == BLOCKS_MAX {
            break;
        }
    }
    serde_json::to_string(&blocks).ok()
}

pub fn parse_relock_blocks(json: Option<&str>) -> Option<Vec<RelockBlock>> {
    json.and_then(|j| serde_json::from_str(j).ok())
}

/// The names the phone reports for its apps (`installed_apps_json`): package -> label. The
/// summary names an app by it, and by its package name when the phone reported no label (the
/// server knows no other name; the package is behind the row's ⓘ either way).
pub fn app_names(installed_json: Option<&str>) -> HashMap<String, String> {
    installed_json
        .and_then(|json| serde_json::from_str::<Vec<crate::models::InstalledApp>>(json).ok())
        .unwrap_or_default()
        .into_iter()
        .filter(|app| !app.label.trim().is_empty())
        .map(|app| {
            (
                app.package_name,
                app.label.trim().chars().take(60).collect(),
            )
        })
        .collect()
}

fn name_of(package: &str, names: &HashMap<String, String>) -> String {
    names
        .get(package)
        .cloned()
        .unwrap_or_else(|| package.to_string())
}

/// A time the phone reported, as the summary shows it: "10:12 · 3 h ago" today, "yesterday
/// 21:40", "12 Oct" - in the Phone Setup zone, with its age (design 31 M4).
fn reported_when(zone: &Zone, at: DateTime<Utc>, now: DateTime<Utc>) -> String {
    // Today the clock and the age say different things; on another day `when` is already the
    // day and time (or the date), as `relative` would be.
    if zone.is_today(at, now) {
        format!("{} · {}", zone.when(at, now), zone.relative(at, now))
    } else {
        zone.when(at, now)
    }
}

/// Why the phone keeps an app hidden (typed; [BlockWhy::text] words it).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BlockWhy {
    /// Installed from outside Play (a download or adb).
    OutsidePlay,
    /// Replaced by an app signed with another key while the phone was open.
    OtherKey,
    /// Signed with another key than the catalog's pin (M10): not the catalog's app whatever its
    /// name, so it stays blocked through "Allow these apps".
    NotCatalogs,
    /// Blocked when the phone locked again; why is unknown (or an older launcher's relock).
    Relock,
}

impl BlockWhy {
    pub fn text(self) -> &'static str {
        match self {
            BlockWhy::OutsidePlay => "From outside Play",
            BlockWhy::OtherKey => "Replaced while the phone was open",
            BlockWhy::NotCatalogs => "Not the catalog's app: uninstall it",
            BlockWhy::Relock => "Blocked when the phone locked again",
        }
    }

    pub fn imposter(self) -> bool {
        self == BlockWhy::NotCatalogs
    }
}

/// One app the phone keeps hidden even when allowed under Apps.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BlockItem {
    pub package_name: String,
    /// The phone's label for it, else its package name ([app_names]).
    pub name: String,
    /// Its signing key, shortened ("3f2a0000…0000c0de"); `None` = the phone couldn't read it.
    pub key: Option<String>,
    /// The catalog's pinned key, shortened, when it differs ([BlockWhy::NotCatalogs]).
    pub pin: Option<String>,
    pub why: BlockWhy,
    /// Noticed at its install (outside a window), else when the phone locked again.
    pub at_install: bool,
    /// When, in the Phone Setup zone with its age, and the exact time; empty when unknown.
    pub when: String,
    pub exact: String,
    /// Allowed under Apps - it shows after "Allow these apps" - or not, so it stays hidden until
    /// it is turned on there too (QA of cmd-expiry, Low 3). `None`: apps aren't managed, or it
    /// stays blocked anyway (an imposter).
    pub ticked: Option<bool>,
}

/// What the phone blocks now: the summary's Blocked Apps group and its Attention row.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Blocks {
    pub items: Vec<BlockItem>,
    /// What "Allow these apps…" sends: the listed blocks it may let out, with the keys shown (QA of
    /// cmd-expiry, Low 1). Empty: no button.
    pub allow: Vec<AllowedBlock>,
    /// How many more the phone has than it lists (QA of cmd-expiry, Low 2).
    pub more: Option<i64>,
    /// The Attention row's count ("2 blocked apps wait"): every listed block - or, from an older
    /// launcher's relock, the ones "Allow these apps" still lets out.
    pub waiting: usize,
}

impl Blocks {
    /// The apps "Allow these apps…" lets out, by name, for its confirm: "Chat and org.example.a".
    pub fn allow_names(&self) -> String {
        and_list(
            &self
                .items
                .iter()
                .filter(|i| self.allow.iter().any(|a| a.package_name == i.package_name))
                .map(|i| i.name.clone())
                .collect::<Vec<_>>(),
        )
    }
}

/// What [blocks] and [relock_blocked] read besides the phone's list.
pub struct BlockContext<'a> {
    /// The catalog's pinned signers (M10): package -> key.
    pub pins: &'a HashMap<String, String>,
    /// The apps allowed under Apps; `None` when apps aren't managed.
    pub allowed: Option<&'a HashSet<String>>,
    pub names: &'a HashMap<String, String>,
    pub zone: &'a Zone,
    pub now: DateTime<Utc>,
}

impl BlockContext<'_> {
    /// The catalog pins another key than the one the phone reports.
    fn differs(&self, package: &str, signer: Option<&str>) -> bool {
        matches!((self.pins.get(package), signer), (Some(pin), Some(key)) if pin != key)
    }

    fn item(
        &self,
        package: &str,
        signer: Option<&str>,
        why: BlockWhy,
        at_install: bool,
        at_ms: Option<i64>,
    ) -> BlockItem {
        let at = at_ms.and_then(DateTime::from_timestamp_millis);
        BlockItem {
            package_name: package.to_string(),
            name: name_of(package, self.names),
            key: signer.map(short_key),
            pin: why
                .imposter()
                .then(|| self.pins.get(package).map(|pin| short_key(pin)))
                .flatten(),
            why,
            at_install,
            when: at
                .map(|t| reported_when(self.zone, t, self.now))
                .unwrap_or_default(),
            exact: at.map(|t| self.zone.exact(t)).unwrap_or_default(),
            ticked: self
                .allowed
                .filter(|_| !why.imposter())
                .map(|a| a.contains(package)),
        }
    }
}

/// The phone's current blocks (`relock_blocks`, fix of 2026-10-12): each with its key, why and
/// when; an app whose key differs from the catalog's pin - by the server's pin or the phone's
/// reason - stays blocked, so "Allow these apps" sends only the others. [total]: the phone's
/// `relock_blocks_total`.
pub fn blocks(list: &[RelockBlock], total: Option<i64>, ctx: &BlockContext) -> Blocks {
    let mut items = Vec::new();
    let mut allow = Vec::new();
    for b in list {
        let why = if b.reason == "catalog_key" || ctx.differs(&b.package_name, b.signer.as_deref())
        {
            BlockWhy::NotCatalogs
        } else {
            match b.reason.as_str() {
                "outside_play" => BlockWhy::OutsidePlay,
                "other_key" => BlockWhy::OtherKey,
                _ => BlockWhy::Relock,
            }
        };
        if !why.imposter() {
            allow.push(AllowedBlock {
                package_name: b.package_name.clone(),
                signer: b.signer.clone(),
            });
        }
        items.push(ctx.item(
            &b.package_name,
            b.signer.as_deref(),
            why,
            b.found == "install",
            b.at_ms,
        ));
    }
    let more = total
        .zip(i64::try_from(list.len()).ok())
        .map(|(total, listed)| total - listed)
        .filter(|more| *more > 0);
    Blocks {
        waiting: items.len(),
        items,
        allow,
        more,
    }
}

/// An older launcher (no `relock_blocks`): its last relock's `blocked` apps, for a week. "Allow
/// these apps" lets out the ones that aren't imposters, until the phone has answered one pressed
/// after that relock ([allowed_since_relock], QA Low 10) - then they are gone and only the list
/// stays.
pub fn relock_blocked(r: &RelockReport, allowed_since: bool, ctx: &BlockContext) -> Blocks {
    if ctx.now.timestamp_millis() - r.at_ms > WARN_MS {
        return Blocks::default();
    }
    let mut items = Vec::new();
    let mut allow = Vec::new();
    for p in &r.blocked {
        let signer = r.blocked_signers.get(p).map(String::as_str);
        let why = if ctx.differs(p, signer) {
            BlockWhy::NotCatalogs
        } else {
            BlockWhy::Relock
        };
        if !why.imposter() && !allowed_since {
            allow.push(AllowedBlock {
                package_name: p.clone(),
                signer: signer.map(str::to_string),
            });
        }
        items.push(ctx.item(p, signer, why, false, Some(r.at_ms)));
    }
    Blocks {
        waiting: allow.len(),
        items,
        allow,
        more: None,
    }
}

/// The security log's line for apps the phone newly reports as blocked (`phone_app_blocked`):
/// `None` when none is new since [previous].
pub fn blocks_log_line(
    device_id: i64,
    previous: Option<&[RelockBlock]>,
    now: &[RelockBlock],
) -> Option<String> {
    let new: Vec<String> = now
        .iter()
        .filter(|b| !previous.is_some_and(|p| p.iter().any(|q| q.package_name == b.package_name)))
        .map(|b| {
            let key = b.signer.as_deref().map(short_key).unwrap_or_default();
            format!("{} ({}, {}, key {key})", b.package_name, b.reason, b.found)
        })
        .collect();
    (!new.is_empty()).then(|| format!("device {device_id}: blocked {}", new.join("; ")))
}

/// The summary's red warning while the phone refuses the Parent PIN (the fix round, QA High 1
/// (b)): the apps by name, their packages for the ⓘ, and whether the first step (a pause) is done.
/// The template words the steps.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ForeignView {
    /// The device admins by name ("Spy Kit"), empty when none.
    pub admins: String,
    /// The accessibility services by name, empty when none.
    pub accessibility: String,
    /// Every app once: "Spy Kit and com.evil.reader".
    pub apps: String,
    /// More than one app ("they" rather than "it").
    pub many: bool,
    /// "Device admin: com.evil.admin. Accessibility: com.evil.reader." - for the ⓘ.
    pub packages: String,
    /// The latest report has a pause on: the phone is open without the Parent PIN.
    pub paused: bool,
}

pub fn foreign_view(
    found: &ForeignAccess,
    paused: bool,
    names: &HashMap<String, String>,
) -> ForeignView {
    let named =
        |list: &[String]| and_list(&list.iter().map(|p| name_of(p, names)).collect::<Vec<_>>());
    let mut apps: Vec<String> = found.admins.clone();
    for p in &found.accessibility {
        if !apps.contains(p) {
            apps.push(p.clone());
        }
    }
    let mut packages = Vec::new();
    if !found.admins.is_empty() {
        packages.push(format!("Device admin: {}.", found.admins.join(", ")));
    }
    if !found.accessibility.is_empty() {
        packages.push(format!(
            "Accessibility: {}.",
            found.accessibility.join(", ")
        ));
    }
    ForeignView {
        admins: named(&found.admins),
        accessibility: named(&found.accessibility),
        many: apps.len() > 1,
        apps: named(&apps),
        packages: packages.join(" "),
        paused,
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

/// "a, b and c".
fn and_list(parts: &[String]) -> String {
    match parts {
        [] => String::new(),
        [one] => one.clone(),
        [rest @ .., last] => format!("{} and {last}", rest.join(", ")),
    }
}

fn count(n: i64, one: &str, many: &str) -> String {
    if n == 1 {
        format!("1 {one}")
    } else {
        format!("{n} {many}")
    }
}

/// The last relock's findings (§1.7), typed for the summary's Locked Again group: only while the
/// relock is under 7 days old and found something. Its blocked apps are [Blocks]'.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Findings {
    /// When it locked again, in the Phone Setup zone with its age, and the exact time.
    pub when: String,
    pub exact: String,
    /// Why it locked again ("Its time was up"); `None` when the phone didn't say.
    pub trigger: Option<&'static str>,
    /// What it removed: "2 Wi-Fi proxies", "1 certificate", "an Android screen lock".
    pub removed: Vec<String>,
    /// What it couldn't undo, by name: "Accessibility: Spy Kit", "a proxy on a network".
    pub still_on: Vec<String>,
    /// Apps turned off that can't be turned back on remotely, by name.
    pub turned_off: Vec<String>,
    pub own_notifications_off: bool,
    /// The steps that failed, in words ("Certificates").
    pub failures: Vec<&'static str>,
}

fn trigger_text(trigger: &str) -> Option<&'static str> {
    Some(match trigger {
        "timer" => "Its time was up",
        "lock_again" => "Lock again on the phone",
        "command" => "Lock again from here",
        "sync" => "When it reached this server",
        "boot" => "After a restart",
        "screen_on" => "When the screen came on",
        "settings" => "Pause switched off in Settings",
        _ => return None,
    })
}

/// A failed step's words (the codes are the sanitized [FAILURES] only).
fn failure_text(code: &str) -> &'static str {
    match code {
        "credential" => "Screen lock",
        "signer_check" => "Signing-key check",
        "apply" => "Restrictions",
        "permitted_lists" => "Allowed keyboards and services",
        "proxies" => "Proxies",
        "ca_certs" => "Certificates",
        "notifications" => "Notifications",
        "detections" => "App checks",
        "finish" => "Finishing up",
        _ => "Another step",
    }
}

pub fn findings(
    r: &RelockReport,
    names: &HashMap<String, String>,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Option<Findings> {
    if now.timestamp_millis() - r.at_ms > WARN_MS || !found_anything(r) {
        return None;
    }
    let at = DateTime::from_timestamp_millis(r.at_ms)?;
    let mut removed = Vec::new();
    if r.proxies_cleared > 0 {
        removed.push(count(r.proxies_cleared, "Wi-Fi proxy", "Wi-Fi proxies"));
    }
    if r.ca_certs_removed > 0 {
        removed.push(count(r.ca_certs_removed, "certificate", "certificates"));
    }
    if r.credential_removed {
        removed.push("an Android screen lock".to_string());
    }
    let mut still_on = Vec::new();
    let named = |label: &str, list: &[String]| {
        list.iter()
            .map(|p| format!("{label}: {}", name_of(p, names)))
            .collect::<Vec<_>>()
    };
    still_on.extend(named("Accessibility", &r.accessibility_enabled));
    still_on.extend(named("Notification access", &r.notification_listeners));
    still_on.extend(named("Device admin", &r.admins_enabled));
    if r.proxy_left {
        still_on.push("a proxy on a network".to_string());
    }
    if r.credential_left {
        still_on.push("an Android screen lock".to_string());
    }
    Some(Findings {
        when: reported_when(zone, at, now),
        exact: zone.exact(at),
        trigger: trigger_text(&r.trigger),
        removed,
        still_on,
        turned_off: r.apps_disabled.iter().map(|p| name_of(p, names)).collect(),
        own_notifications_off: r.own_notifications_off,
        failures: r.failures.iter().map(|f| failure_text(f)).collect(),
    })
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

/// The last of one of this design's commands (QA Low 10): when it was pressed (server time) and,
/// once the phone answered, when and what.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CommandAnswer {
    pub command: String,
    pub requested_ms: i64,
    pub acknowledged_ms: Option<i64>,
    pub result: Option<String>,
    /// The phone fetched it.
    pub delivered: bool,
    /// Cancelled before it reached the phone (by the parent, or by an enrollment).
    pub cancelled: bool,
    /// The answer's success flag (`device_commands.succeeded`; `None` before an answer).
    pub succeeded: Option<bool>,
}

/// How long the phone's summary reloads itself after "Lock again" or "Unlock 2 h…" was pressed
/// ([waiting_for_phone]), from the press.
pub const WAIT_FOR_PHONE_MS: i64 = 2 * 60 * 1000;

/// Whether the phone's summary reloads itself every 3 s (`static/auto-reload.js`) to show what
/// "Lock again" (`end_windows`) or "Unlock 2 h…" (`start_pause`) did: the phone acts at once (the
/// SSE nudge), but no push reaches the page. The newer of the two in [answers] (the newest of each
/// command), pressed less than [WAIT_FOR_PHONE_MS] before `now_ms`, not cancelled and not refused,
/// while the phone hasn't answered it or the newest report doesn't show it yet: `paused` (the
/// report's `restrictions_paused`) after an Unlock, `open` (a pause or Play install mode) no more
/// after Lock again. Nothing waits: `false`.
pub fn waiting_for_phone(answers: &[CommandAnswer], paused: bool, open: bool, now_ms: i64) -> bool {
    waited_for(answers, paused, open, now_ms).is_some()
}

/// [waiting_for_phone]'s command: [END_WINDOWS] or [START_PAUSE] while the summary waits for it.
pub fn waited_for(
    answers: &[CommandAnswer],
    paused: bool,
    open: bool,
    now_ms: i64,
) -> Option<&'static str> {
    let last = answers
        .iter()
        .filter(|a| a.command == END_WINDOWS || a.command == START_PAUSE)
        .max_by_key(|a| a.requested_ms)?;
    if last.cancelled || now_ms - last.requested_ms >= WAIT_FOR_PHONE_MS {
        return None;
    }
    let command = if last.command == START_PAUSE {
        START_PAUSE
    } else {
        END_WINDOWS
    };
    if last.acknowledged_ms.is_none() {
        return Some(command);
    }
    if last.succeeded == Some(false) {
        return None;
    }
    let shown = if command == START_PAUSE {
        paused
    } else {
        !open
    };
    (!shown).then_some(command)
}

/// The flash a reloading summary keeps while it waits (qa-pwa-fixes-a-code Q-2): `auto-reload.js`
/// drops `?notice=` from the address, so the "Sent." line comes from the command itself.
pub fn waiting_flash(command: &str) -> Option<(&'static str, &'static str)> {
    match command {
        START_PAUSE => flash("start_pause_sent"),
        END_WINDOWS => flash("end_windows_sent"),
        _ => None,
    }
}

/// "Allow these apps" stays only until the phone has answered one pressed after the relock - the
/// report doesn't change, but the blocks it lists are gone (QA Low 10).
pub fn allowed_since_relock(answer: Option<&CommandAnswer>, relock_at_ms: i64) -> bool {
    answer.is_some_and(|a| a.acknowledged_ms.is_some() && a.requested_ms > relock_at_ms)
}

/// The one-shot flash after a command was queued (`?notice=`): the summary group its form is in
/// (where the redirect lands, design 31 §3.0), and the text.
pub fn flash(code: &str) -> Option<(&'static str, &'static str)> {
    Some(match code {
        "end_windows_sent" => (
            "quick",
            "Sent. The phone locks again within seconds when online.",
        ),
        "start_pause_sent" => (
            "quick",
            "Sent. The phone unlocks for 2 hours within seconds when online.",
        ),
        "allow_blocked_sent" => (
            "blocked-apps",
            "Sent. The apps allowed under Apps show again within seconds when online.",
        ),
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::collections::{HashMap, HashSet};

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

    /// The launcher's `relock_blocks` literal (PolicyResponseCompatTest).
    fn blocks_literal() -> Value {
        json!([
            { "package_name": "me.vibb.music.debug",
              "signer": "3f2a00000000000000000000000000000000000000000000000000000000c0de",
              "reason": "outside_play", "found": "install", "at_ms": 1760100000000_i64 },
            { "package_name": "org.example.old", "signer": null, "reason": "unknown",
              "found": "relock", "at_ms": null }
        ])
    }

    #[test]
    fn relock_blocks_are_kept_through_the_known_fields() {
        let stored = sanitize_relock_blocks(&blocks_literal()).unwrap();
        let back: Value = serde_json::from_str(&stored).unwrap();
        assert_eq!(back, blocks_literal());
        // Junk: names, keys, reasons and places that aren't known go or fall back.
        let junk = json!([
            { "package_name": "not a package" },
            { "package_name": "org.example.app", "signer": "XYZ", "reason": "because",
              "found": "somewhere", "at_ms": -5 },
            { "package_name": "org.example.app", "reason": "other_key" },
            "x"
        ]);
        let blocks = parse_relock_blocks(sanitize_relock_blocks(&junk).as_deref()).unwrap();
        assert_eq!(
            blocks,
            vec![RelockBlock {
                package_name: "org.example.app".to_string(),
                signer: None,
                reason: "unknown".to_string(),
                found: "relock".to_string(),
                at_ms: Some(0),
            }]
        );
        // An empty list is a phone with no blocks; anything but a list is nothing.
        assert_eq!(sanitize_relock_blocks(&json!([])).as_deref(), Some("[]"));
        assert_eq!(sanitize_relock_blocks(&json!({})), None);
        let many: Vec<Value> = (0..30)
            .map(|i| json!({ "package_name": format!("org.example.app{i}"), "reason": "outside_play", "found": "install" }))
            .collect();
        assert_eq!(
            parse_relock_blocks(sanitize_relock_blocks(&Value::Array(many)).as_deref())
                .unwrap()
                .len(),
            BLOCKS_MAX
        );
    }

    #[test]
    fn a_new_block_is_logged_once() {
        let blocks =
            parse_relock_blocks(Some(&sanitize_relock_blocks(&blocks_literal()).unwrap())).unwrap();
        assert_eq!(
            blocks_log_line(5, Some(&blocks[1..]), &blocks).as_deref(),
            Some(
                "device 5: blocked me.vibb.music.debug (outside_play, install, key 3f2a0000…0000c0de)"
            )
        );
        assert_eq!(blocks_log_line(5, Some(&blocks), &blocks), None);
        assert_eq!(blocks_log_line(5, None, &[]), None);
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

    fn oslo() -> Zone {
        Zone::from_setting("Europe/Oslo")
    }

    fn now() -> DateTime<Utc> {
        DateTime::from_timestamp_millis(NOW).unwrap()
    }

    /// The relock happened at 12:40 UTC, 14:40 in Oslo (CEST): never the UTC clock.
    #[test]
    fn every_finding_is_typed_in_local_time() {
        let r = RelockReport {
            proxies_cleared: 2,
            ca_certs_removed: 1,
            credential_removed: true,
            proxy_left: true,
            credential_left: false,
            blocked: vec!["com.example.app".into()],
            accessibility_enabled: vec!["com.evil.ally".into()],
            admins_enabled: vec!["com.evil.admin".into()],
            apps_disabled: vec!["com.android.inputmethod.latin".into()],
            notification_listeners: vec!["com.evil.reader".into()],
            own_notifications_off: true,
            failures: vec!["ca_certs".into(), "signer_check".into()],
            ..report()
        };
        let names = HashMap::from([("com.evil.ally".to_string(), "Ally <b>".to_string())]);
        let f = findings(&r, &names, &oslo(), now()).unwrap();
        assert_eq!(f.when, "14:40 · 3 h ago");
        assert!(f.exact.starts_with("2025-10-10 14:40:00"), "{}", f.exact);
        assert_eq!(f.trigger, Some("Its time was up"));
        assert_eq!(
            f.removed,
            ["2 Wi-Fi proxies", "1 certificate", "an Android screen lock"]
        );
        // By the phone's name where it reported one; the package otherwise.
        assert_eq!(
            f.still_on,
            [
                "Accessibility: Ally <b>",
                "Notification access: com.evil.reader",
                "Device admin: com.evil.admin",
                "a proxy on a network",
            ]
        );
        assert_eq!(f.turned_off, ["com.android.inputmethod.latin"]);
        assert!(f.own_notifications_off);
        // Words, never the codes.
        assert_eq!(f.failures, ["Certificates", "Signing-key check"]);
        // An empty Phone Setup zone says UTC.
        let utc = findings(&r, &names, &Zone::utc_fallback(), now()).unwrap();
        assert_eq!(utc.when, "12:40 UTC · 3 h ago");
    }

    #[test]
    fn the_triggers_and_a_screen_lock_left_on() {
        let mut r = report();
        r.proxies_cleared = 0;
        r.blocked.clear();
        r.credential_left = true;
        for (trigger, text) in [
            ("timer", "Its time was up"),
            ("lock_again", "Lock again on the phone"),
            ("command", "Lock again from here"),
            ("sync", "When it reached this server"),
            ("boot", "After a restart"),
            ("screen_on", "When the screen came on"),
            ("settings", "Pause switched off in Settings"),
        ] {
            r.trigger = trigger.into();
            let f = findings(&r, &HashMap::new(), &oslo(), now()).unwrap();
            assert_eq!(f.trigger, Some(text));
            assert_eq!(f.still_on, ["an Android screen lock"]);
            assert!(f.removed.is_empty());
        }
        r.trigger = String::new();
        assert_eq!(
            findings(&r, &HashMap::new(), &oslo(), now())
                .unwrap()
                .trigger,
            None
        );
    }

    #[test]
    fn nothing_found_or_older_than_a_week_shows_nothing() {
        let mut quiet = report();
        quiet.proxies_cleared = 0;
        quiet.blocked.clear();
        assert!(!found_anything(&quiet));
        assert_eq!(findings(&quiet, &HashMap::new(), &oslo(), now()), None);
        let old = report();
        let at = |ms: i64| DateTime::from_timestamp_millis(ms).unwrap();
        assert!(findings(&old, &HashMap::new(), &oslo(), at(old.at_ms + WARN_MS)).is_some());
        assert_eq!(
            findings(&old, &HashMap::new(), &oslo(), at(old.at_ms + WARN_MS + 1)),
            None
        );
    }

    fn ctx<'a>(
        pins: &'a HashMap<String, String>,
        allowed: Option<&'a HashSet<String>>,
        names: &'a HashMap<String, String>,
        zone: &'a Zone,
    ) -> BlockContext<'a> {
        BlockContext {
            pins,
            allowed,
            names,
            zone,
            now: now(),
        }
    }

    /// An older launcher (no `relock_blocks`): the relock's blocked apps, a catalog imposter kept
    /// with no button, and the button gone once an "Allow" pressed after the relock was answered.
    #[test]
    fn an_older_launchers_relock_lists_its_blocked_apps() {
        let r = report();
        let (none, zone) = (HashMap::new(), oslo());
        let b = relock_blocked(&r, false, &ctx(&none, None, &none, &zone));
        assert_eq!(b.items.len(), 1);
        assert_eq!(b.items[0].why, BlockWhy::Relock);
        assert_eq!(b.items[0].key.as_deref(), Some("3f2a0000…0000c0de"));
        assert_eq!(b.items[0].when, "14:40 · 3 h ago");
        assert_eq!(b.waiting, 1);
        assert_eq!(
            allow_form_value(&b.allow),
            format!("com.example.app:{}", r.blocked_signers["com.example.app"])
        );
        // The catalog pins another key: not the catalog's app - it stays, and alone it gets no button.
        let pins = HashMap::from([("com.example.app".to_string(), "9".repeat(64))]);
        let b = relock_blocked(&r, false, &ctx(&pins, None, &none, &zone));
        assert_eq!(b.items[0].why, BlockWhy::NotCatalogs);
        assert_eq!(b.items[0].pin.as_deref(), Some("99999999…99999999"));
        assert!(b.allow.is_empty());
        assert_eq!(b.waiting, 0);
        // The catalog's own key: the button.
        let pins = HashMap::from([(
            "com.example.app".to_string(),
            r.blocked_signers["com.example.app"].clone(),
        )]);
        assert_eq!(
            relock_blocked(&r, false, &ctx(&pins, None, &none, &zone))
                .allow
                .len(),
            1
        );
        // Answered since the relock: the list stays, the button and the count go.
        let b = relock_blocked(&r, true, &ctx(&none, None, &none, &zone));
        assert_eq!((b.items.len(), b.allow.len(), b.waiting), (1, 0, 0));
        // A week later: nothing.
        let late = BlockContext {
            now: DateTime::from_timestamp_millis(r.at_ms + WARN_MS + 1).unwrap(),
            ..ctx(&none, None, &none, &zone)
        };
        assert_eq!(relock_blocked(&r, false, &late), Blocks::default());
    }

    #[test]
    fn the_log_line_and_the_flashes() {
        assert_eq!(
            log_line(3, &report()),
            "device 3, 2025-10-10 12:40 UTC: locked again (timer) - Wi-Fi proxies removed: 1; blocked: com.example.app."
        );
        assert_eq!(flash("end_windows_sent").unwrap().0, "quick");
        assert_eq!(flash("start_pause_sent").unwrap().0, "quick");
        assert_eq!(flash("allow_blocked_sent").unwrap().0, "blocked-apps");
        assert_eq!(flash("parent_pin_saved"), None);
    }

    #[test]
    fn allow_these_apps_goes_once_an_answered_one_is_newer_than_the_relock() {
        let allowed = CommandAnswer {
            command: ALLOW_BLOCKED.to_string(),
            requested_ms: NOW - 2 * 60 * 60 * 1000,
            acknowledged_ms: Some(NOW),
            result: Some("allowed: 1 apps".to_string()),
            delivered: true,
            cancelled: false,
            succeeded: Some(true),
        };
        assert!(allowed_since_relock(
            Some(&allowed),
            NOW - 3 * 60 * 60 * 1000
        ));
        assert!(!allowed_since_relock(Some(&allowed), NOW - 60 * 60 * 1000));
        let unanswered = CommandAnswer {
            acknowledged_ms: None,
            ..allowed
        };
        assert!(!allowed_since_relock(Some(&unanswered), 0));
        assert!(!allowed_since_relock(None, 0));
    }

    /// The summary waits for the phone (reloads) after Lock again or Unlock 2 h…: unanswered, or
    /// answered but not in the newest report yet; never past 2 minutes, after a cancel or a
    /// refusal, or for another command.
    #[test]
    fn the_summary_waits_for_the_phone_after_lock_again_or_unlock() {
        let pressed = |command: &str, ago_ms: i64| CommandAnswer {
            command: command.to_string(),
            requested_ms: NOW - ago_ms,
            acknowledged_ms: None,
            result: None,
            delivered: false,
            cancelled: false,
            succeeded: None,
        };
        let answered = |a: CommandAnswer, ok: bool| CommandAnswer {
            acknowledged_ms: Some(NOW),
            delivered: true,
            succeeded: Some(ok),
            ..a
        };
        // (answers, paused, open) -> waits.
        let pause = pressed(START_PAUSE, 10_000);
        let lock = pressed(END_WINDOWS, 10_000);
        let cases: Vec<(Vec<CommandAnswer>, bool, bool, bool)> = vec![
            (vec![], false, false, false),
            (vec![pressed(ALLOW_BLOCKED, 10_000)], false, false, false),
            // Unanswered: waits, whatever the report says.
            (vec![pause.clone()], false, false, true),
            (vec![lock.clone()], true, true, true),
            (vec![lock.clone()], false, false, true),
            // Answered: until the newest report shows it.
            (vec![answered(pause.clone(), true)], false, false, true),
            (vec![answered(pause.clone(), true)], true, true, false),
            (vec![answered(lock.clone(), true)], true, true, true),
            (vec![answered(lock.clone(), true)], false, false, false),
            // Play install mode still on at the newest report: Lock again still waits (Q-3).
            (vec![answered(lock.clone(), true)], false, true, true),
            // An older launcher's answer has no flag: as a success.
            (
                vec![CommandAnswer {
                    succeeded: None,
                    ..answered(pause.clone(), true)
                }],
                false,
                false,
                true,
            ),
            // Refused or cancelled: nothing to wait for.
            (vec![answered(pause.clone(), false)], false, false, false),
            (
                vec![CommandAnswer {
                    cancelled: true,
                    ..pause.clone()
                }],
                false,
                false,
                false,
            ),
            // About 2 minutes after the press it stops.
            (
                vec![pressed(START_PAUSE, WAIT_FOR_PHONE_MS - 1000)],
                false,
                false,
                true,
            ),
            (
                vec![pressed(START_PAUSE, WAIT_FOR_PHONE_MS)],
                false,
                false,
                false,
            ),
            // The newer of the two counts: Lock again after an Unlock waits for the lock.
            (
                vec![
                    answered(pressed(START_PAUSE, 60_000), true),
                    answered(lock.clone(), true),
                ],
                true,
                true,
                true,
            ),
            (
                vec![
                    answered(pressed(START_PAUSE, 60_000), true),
                    answered(lock.clone(), true),
                ],
                false,
                false,
                false,
            ),
        ];
        for (i, (answers, paused, open, waits)) in cases.into_iter().enumerate() {
            assert_eq!(
                waiting_for_phone(&answers, paused, open, NOW),
                waits,
                "case {i}"
            );
        }
        // About 2 minutes, as PLAN's item says (qa-pwa-fixes-a-code Q-4).
        assert_eq!(WAIT_FOR_PHONE_MS, 120_000);
        // The kept "Sent." line is the command's own (Q-2).
        assert_eq!(
            waited_for(std::slice::from_ref(&pause), false, false, NOW),
            Some(START_PAUSE)
        );
        assert_eq!(waiting_flash(START_PAUSE), flash("start_pause_sent"));
        assert_eq!(waiting_flash(END_WINDOWS), flash("end_windows_sent"));
        assert_eq!(waiting_flash(ALLOW_BLOCKED), None);
    }

    fn literal_blocks() -> Vec<RelockBlock> {
        parse_relock_blocks(Some(&sanitize_relock_blocks(&blocks_literal()).unwrap())).unwrap()
    }

    #[test]
    fn every_block_is_listed_with_its_key_and_why_and_a_catalog_imposter_stays() {
        let blocks_now = literal_blocks();
        let (none, zone) = (HashMap::new(), oslo());
        let names = HashMap::from([("me.vibb.music.debug".to_string(), "Vibb Music".to_string())]);
        let b = blocks(&blocks_now, None, &ctx(&none, None, &names, &zone));
        assert_eq!(b.waiting, 2);
        assert_eq!(b.allow_names(), "Vibb Music and org.example.old");
        let first = &b.items[0];
        assert_eq!(first.name, "Vibb Music");
        assert_eq!(first.key.as_deref(), Some("3f2a0000…0000c0de"));
        assert_eq!(first.why, BlockWhy::OutsidePlay);
        assert_eq!(first.why.text(), "From outside Play");
        assert!(first.at_install);
        assert_eq!(first.when, "14:40 · 3 h ago");
        assert_eq!(first.ticked, None);
        let second = &b.items[1];
        assert_eq!(
            (
                second.name.as_str(),
                second.why,
                second.at_install,
                second.when.as_str()
            ),
            ("org.example.old", BlockWhy::Relock, false, "")
        );
        // The catalog pins another key: not the catalog's app - it stays, and alone it gets no
        // button; it still waits (the Attention row counts it).
        let pins = HashMap::from([("me.vibb.music.debug".to_string(), "9".repeat(64))]);
        let b = blocks(&blocks_now[..1], None, &ctx(&pins, None, &none, &zone));
        assert_eq!(b.items[0].why, BlockWhy::NotCatalogs);
        assert_eq!(b.items[0].pin.as_deref(), Some("99999999…99999999"));
        assert!(b.allow.is_empty());
        assert_eq!(b.waiting, 1);
        // The phone's own reason says so too (its pin, kept from the app list).
        let mut imposter = blocks_now[0].clone();
        imposter.reason = "catalog_key".to_string();
        let b = blocks(
            std::slice::from_ref(&imposter),
            None,
            &ctx(&none, None, &none, &zone),
        );
        assert!(b.allow.is_empty());
        assert_eq!(b.items[0].pin, None);
        // None: nothing.
        assert_eq!(
            blocks(&[], None, &ctx(&none, None, &none, &zone)),
            Blocks::default()
        );
        // Ticked or not under Apps, each (QA of cmd-expiry, Low 3); an imposter stays anyway.
        let ticked = HashSet::from(["org.example.old".to_string()]);
        let b = blocks(&blocks_now, None, &ctx(&none, Some(&ticked), &none, &zone));
        assert_eq!(b.items[0].ticked, Some(false));
        assert_eq!(b.items[1].ticked, Some(true));
        let b = blocks(&[imposter], None, &ctx(&none, Some(&ticked), &none, &zone));
        assert_eq!(b.items[0].ticked, None);
    }

    /// QA of cmd-expiry, Low 1: "Allow these apps" sends the listed apps it may let out, with the
    /// keys shown - not a catalog imposter - and reads back only valid entries.
    #[test]
    fn allow_sends_the_listed_apps_and_their_keys() {
        let blocks_now = literal_blocks();
        let (none, zone) = (HashMap::new(), oslo());
        let b = blocks(&blocks_now, None, &ctx(&none, None, &none, &zone));
        let value = allow_form_value(&b.allow);
        let key = blocks_now[0].signer.clone().unwrap();
        assert_eq!(value, format!("me.vibb.music.debug:{key},org.example.old:"));
        assert_eq!(parse_allow_form(&value), b.allow);
        assert_eq!(b.allow[1].signer, None);
        let pins = HashMap::from([("me.vibb.music.debug".to_string(), "9".repeat(64))]);
        let b = blocks(&blocks_now, None, &ctx(&pins, None, &none, &zone));
        assert_eq!(allow_form_value(&b.allow), "org.example.old:");
        // Junk, repeats and bad keys go; at most BLOCKS_MAX.
        assert_eq!(
            parse_allow_form("x y:, org.example.a:XYZ,org.example.b:,org.example.b:,nocolon"),
            vec![AllowedBlock {
                package_name: "org.example.b".to_string(),
                signer: None
            }]
        );
        let many: Vec<String> = (0..30).map(|i| format!("org.example.app{i}:")).collect();
        assert_eq!(parse_allow_form(&many.join(",")).len(), BLOCKS_MAX);
        // An older launcher's relock: its allowable apps, with their keys.
        let mut r = RelockReport {
            at_ms: NOW - 60_000,
            blocked: vec!["org.example.a".to_string(), "org.example.b".to_string()],
            ..Default::default()
        };
        r.blocked_signers
            .insert("org.example.a".to_string(), "a".repeat(64));
        r.blocked_signers
            .insert("org.example.b".to_string(), "b".repeat(64));
        let pins = HashMap::from([("org.example.b".to_string(), "9".repeat(64))]);
        assert_eq!(
            relock_blocked(&r, false, &ctx(&pins, None, &none, &zone)).allow,
            vec![AllowedBlock {
                package_name: "org.example.a".to_string(),
                signer: Some("a".repeat(64))
            }]
        );
    }

    /// QA of cmd-expiry, Low 2: more blocks than listed say so.
    #[test]
    fn more_blocks_than_listed_say_so() {
        let blocks_now = literal_blocks();
        let (none, zone) = (HashMap::new(), oslo());
        let more =
            |total: Option<i64>| blocks(&blocks_now, total, &ctx(&none, None, &none, &zone)).more;
        assert_eq!(more(None), None);
        assert_eq!(more(Some(2)), None);
        assert_eq!(more(Some(1)), None);
        assert_eq!(more(Some(19)), Some(17));
    }

    #[test]
    fn the_phones_names_for_its_apps() {
        let names = app_names(Some(
            r#"[{"package_name":"com.a","label":" Chat "},{"package_name":"com.b","label":"  "},
                {"package_name":"com.c","label":"<b>Spy</b>"}]"#,
        ));
        assert_eq!(names.get("com.a").map(String::as_str), Some("Chat"));
        assert_eq!(names.get("com.b"), None);
        // Kept as the phone sent it; the template escapes it.
        assert_eq!(names.get("com.c").map(String::as_str), Some("<b>Spy</b>"));
        assert!(app_names(Some("garbage")).is_empty());
        assert!(app_names(None).is_empty());
    }

    #[test]
    fn the_foreign_access_warning_names_the_apps() {
        let found = ForeignAccess {
            admins: vec!["com.evil.admin".to_string()],
            accessibility: vec!["com.evil.admin".to_string(), "com.evil.reader".to_string()],
        };
        let names = HashMap::from([("com.evil.admin".to_string(), "Spy Kit".to_string())]);
        let view = foreign_view(&found, false, &names);
        assert_eq!(
            view,
            ForeignView {
                admins: "Spy Kit".to_string(),
                accessibility: "Spy Kit and com.evil.reader".to_string(),
                apps: "Spy Kit and com.evil.reader".to_string(),
                many: true,
                packages: "Device admin: com.evil.admin. Accessibility: com.evil.admin, \
                           com.evil.reader."
                    .to_string(),
                paused: false,
            }
        );
        assert!(foreign_view(&found, true, &names).paused);
        let one = ForeignAccess {
            admins: Vec::new(),
            accessibility: vec!["com.evil.reader".to_string()],
        };
        let view = foreign_view(&one, false, &HashMap::new());
        assert_eq!((view.admins.as_str(), view.many), ("", false));
        assert_eq!(view.packages, "Accessibility: com.evil.reader.");
        assert_eq!(
            foreign_log_line(4, Some(&found)),
            "device 4: the parent PIN is refused on the phone - device admin: com.evil.admin; \
             accessibility on: com.evil.admin, com.evil.reader."
        );
        assert!(foreign_log_line(4, None).contains("takes the parent PIN again"));
    }
}
