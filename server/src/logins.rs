//! Design 102: Wi-Fi sign-in pages and in-app logins without free Chrome (`docs/design/102-captive-portals-and-logins.md`).
//!
//! - **Part A**, the Wi-Fi sign-in window on the phone: the policy's `captive_portal` ([portal_policy]: the switch, the
//!   parent code required, the limits, the "one more" counter), the report's `captive_portal` ([clean_portal]) and its
//!   two Activity events ([intake]: `wifi_signin_wide`, `wifi_signin_budget`).
//! - **Part B**, Chrome only for logins: the policy's `login_browser` ([browser_policy]: Chrome's package and the sites,
//!   the ticked [PRESETS] expanded and the parent's own entries, [valid_entry]), the report's `login_browser`
//!   ([clean_browser]).
//!
//! The shared contract is `testdata/login_windows.json` (byte-identical in the launcher's test resources). The page
//! (`/devices/{id}/logins`) and the Advanced page's `#wifi-signin` row are `handlers::logins`.

use std::collections::BTreeSet;

use chrono::{DateTime, Duration, NaiveTime, TimeZone, Timelike, Utc};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};

use crate::models::DevicePolicy;

/// A launcher that reports `captive_portal` and opens the Wi-Fi sign-in window claims this.
pub const PORTAL_CAPABILITY: &str = "captive_portal_v1";
/// A launcher that runs Chrome's login role (`login_browser`) claims this.
pub const BROWSER_CAPABILITY: &str = "login_browser_v1";

/// The one browser the role accepts (D8); the launcher refuses any other package.
pub const CHROME: &str = "com.android.chrome";

/// Each opening's minutes (D2).
pub const MINUTES: i64 = 10;
/// The code-free openings and minutes a day (D2, D17: a correct parent code opens past them).
pub const DAY_OPENINGS: i64 = 4;
pub const DAY_MINUTES: i64 = 20;
/// The parent's "one more" counts at most this many a day on the phone (D14).
pub const GRANTS_A_DAY: i64 = 3;

/// At most this many own entries per phone (Chrome takes 1000).
pub const MAX_OWN_ENTRIES: i64 = 100;
/// The note's length, in characters.
pub const MAX_NOTE: usize = 60;
/// The longest entry taken.
const MAX_ENTRY: usize = 255;

/// The report's limits (§6.2): the last 10 sessions, 20 sites visited, 10 blocked hosts and frames each.
const MAX_SESSIONS: usize = 10;
const MAX_VISITED: usize = 20;
const MAX_BLOCKED: usize = 10;
/// A host's length (DNS) and a network name's (an SSID is at most 32 bytes; room for its escapes).
const MAX_HOST: usize = 253;
const MAX_NETWORK: usize = 64;
const MAX_VERSION: usize = 64;

/// The window's outcomes (§6.2).
pub const OUTCOMES: &[&str] = &[
    "validated",
    "passes",
    "closed",
    "closed_by_vibb",
    "timeout",
    "budget",
    "lost",
];
/// How a window was started: the notification, Vibb's Wi-Fi page or Home's line, or a reopen (§3.8).
pub const STARTS: &[&str] = &["system", "vibb", "revisit"];
/// How an opening was approved (§3.2): the parent code, a grant, a PIN pause, or nothing (the requirement off).
pub const APPROVALS: &[&str] = &["code", "grant", "pause", "none"];
/// Who handles Android's sign-in intent.
pub const HANDLERS: &[&str] = &["vibb", "system", "other"];
/// The role's state on the phone, and why it failed.
pub const BROWSER_STATES: &[&str] = &["on", "off", "absent", "failed"];
pub const BROWSER_REASONS: &[&str] = &["package", "write", "readback", "absent"];

/// A preset (D11): a name the parent ticks, its words and its hosts. Stored as a set of [Preset::key]s and expanded
/// when the policy is built, so a corrected preset reaches every phone that ticked it (Q102-M8).
pub struct Preset {
    pub key: &'static str,
    /// The label; a proper name, the same in both languages.
    pub name: &'static str,
    pub hosts: &'static [&'static str],
}

/// The presets, in the page's order (D-C6 settles each one's hosts).
pub const PRESETS: &[Preset] = &[
    Preset {
        key: "feide",
        name: "Feide",
        // `concat!`: a quoted "auth." literal reads as a catalog key to `i18n::tests::every_key_used_exists`.
        hosts: &[
            concat!("auth", ".dataporten.no"),
            "idp.feide.no",
            "innsyn.feide.no",
        ],
    },
    Preset {
        key: "google",
        name: "Google",
        hosts: &["accounts.google.com", "accounts.youtube.com"],
    },
    Preset {
        key: "microsoft",
        name: "Microsoft",
        hosts: &[
            "login.microsoftonline.com",
            "login.live.com",
            "login.microsoft.com",
            "account.live.com",
        ],
    },
    Preset {
        key: "apple",
        name: "Apple",
        hosts: &["appleid.apple.com", "idmsa.apple.com"],
    },
];

/// The preset named `key`.
pub fn preset(key: &str) -> Option<&'static Preset> {
    PRESETS.iter().find(|p| p.key == key)
}

/// The stored set (`device_policy.login_browser_presets`, a JSON array) as known keys, in [PRESETS]' order. Anything
/// unreadable is none.
pub fn presets_of(stored: &str) -> Vec<&'static str> {
    let ticked: Vec<String> = serde_json::from_str(stored).unwrap_or_default();
    PRESETS
        .iter()
        .filter(|p| ticked.iter().any(|t| t == p.key))
        .map(|p| p.key)
        .collect()
}

/// The set with `key` ticked or not, as stored.
pub fn presets_with(stored: &str, key: &str, on: bool) -> String {
    let mut keys = presets_of(stored);
    keys.retain(|k| *k != key);
    if on && let Some(p) = preset(key) {
        keys.push(p.key);
    }
    let ordered: Vec<&str> = PRESETS
        .iter()
        .map(|p| p.key)
        .filter(|k| keys.contains(k))
        .collect();
    serde_json::to_string(&ordered).expect("strings serialize")
}

/// An entry as the add field takes it (§5.2, the contract's `entries`), or `None` for a refusal: a host (lower case,
/// ASCII, two labels or more, the last with a letter) with an optional leading dot (that host only), an optional port
/// and an optional path. A pasted `http(s)://` URL keeps its host and path; any other scheme, a `*`, a query without
/// a pasted URL, a space or a character outside the path's set is refused. A path of only `/` is dropped.
pub fn valid_entry(input: &str) -> Option<String> {
    let text = input.trim();
    if text.is_empty() || text.len() > MAX_ENTRY || text.contains('*') || !text.is_ascii() {
        return None;
    }
    let lower = text.to_ascii_lowercase();
    let (pasted, rest) = match lower
        .strip_prefix("https://")
        .or_else(|| lower.strip_prefix("http://"))
    {
        Some(_) => (true, &text[text.find("://").expect("a scheme") + 3..]),
        None => (false, text),
    };
    let rest = if pasted {
        rest.split(['?', '#']).next().unwrap_or_default()
    } else if rest.contains(['?', '#']) || rest.contains("://") {
        return None;
    } else {
        rest
    };
    let (authority, path) = match rest.find('/') {
        Some(slash) => (&rest[..slash], &rest[slash..]),
        None => (rest, ""),
    };
    let (dot, authority) = match authority.strip_prefix('.') {
        Some(after) => (".", after),
        None => ("", authority),
    };
    let (host, port) = match authority.split_once(':') {
        Some((host, port)) => (host, Some(port)),
        None => (authority, None),
    };
    let host = host.to_ascii_lowercase();
    let labels: Vec<&str> = host.split('.').collect();
    let label_ok = |label: &&str| {
        !label.is_empty()
            && label.len() <= 63
            && !label.starts_with('-')
            && !label.ends_with('-')
            && label
                .bytes()
                .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
    };
    if labels.len() < 2
        || host.len() > MAX_HOST
        || !labels.iter().all(label_ok)
        || !labels
            .last()
            .is_some_and(|tld| tld.bytes().any(|b| b.is_ascii_lowercase()))
    {
        return None;
    }
    let port = match port {
        Some(port) => {
            let n: u16 = port
                .parse()
                .ok()
                .filter(|_| port.bytes().all(|b| b.is_ascii_digit()))?;
            if n == 0 {
                return None;
            }
            format!(":{n}")
        }
        None => String::new(),
    };
    if !path
        .bytes()
        .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'/' | b'-' | b'.' | b'_' | b'~' | b'%'))
    {
        return None;
    }
    let path = if path == "/" { "" } else { path };
    Some(format!("{dot}{host}{port}{path}"))
}

/// The policy's `captive_portal` (§6.1): always sent.
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct CaptivePortalPolicy {
    pub enabled: bool,
    pub require_code: bool,
    pub minutes: i64,
    pub day_openings: i64,
    pub day_minutes: i64,
    /// The parent's "one more" counter (D14): the phone adds a code-free opening for each step it hasn't used.
    pub grant: i64,
}

/// The policy's `login_browser` (§6.1): `null` while the role is off or Chrome is ticked as a normal app (the server
/// keeps the two exclusive).
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct LoginBrowserPolicy {
    pub package: &'static str,
    /// The ticked presets' hosts and the parent's own entries, sorted, each once.
    pub hosts: Vec<String>,
}

/// The policy's `captive_portal` from the phone's settings.
pub fn portal_policy(policy: &DevicePolicy) -> CaptivePortalPolicy {
    CaptivePortalPolicy {
        enabled: policy.captive_portal_enabled,
        require_code: policy.captive_portal_require_code,
        minutes: MINUTES,
        day_openings: DAY_OPENINGS,
        day_minutes: DAY_MINUTES,
        grant: policy.captive_portal_grant,
    }
}

/// Chrome is ticked under Apps (the parent's allowlist names it).
pub fn chrome_ticked(policy: &DevicePolicy) -> bool {
    policy
        .allowlist_json
        .as_deref()
        .and_then(|json| serde_json::from_str::<Vec<String>>(json).ok())
        .is_some_and(|list| list.iter().any(|p| p == CHROME))
}

/// The phone's own entries, oldest first: `(entry, note)`.
pub async fn own_sites(
    db: &sqlx::SqlitePool,
    device_id: i64,
) -> Result<Vec<(String, Option<String>)>, sqlx::Error> {
    sqlx::query_as(
        "SELECT entry, note FROM login_role_sites WHERE device_id = ? ORDER BY added_ms, entry",
    )
    .bind(device_id)
    .fetch_all(db)
    .await
}

/// The sorted union of the ticked presets' hosts and the own entries.
pub fn hosts_of(presets: &[&str], own: &[String]) -> Vec<String> {
    let mut hosts: BTreeSet<String> = own.iter().cloned().collect();
    for key in presets {
        if let Some(p) = preset(key) {
            hosts.extend(p.hosts.iter().map(|h| h.to_string()));
        }
    }
    hosts.into_iter().collect()
}

/// The policy's `login_browser`: `None` while the role is off or Chrome is ticked. A DB error is the caller's.
pub async fn browser_policy(
    db: &sqlx::SqlitePool,
    policy: &DevicePolicy,
) -> Result<Option<LoginBrowserPolicy>, sqlx::Error> {
    if !policy.login_browser_enabled || chrome_ticked(policy) {
        return Ok(None);
    }
    let own: Vec<String> = own_sites(db, policy.device_id)
        .await?
        .into_iter()
        .map(|(entry, _)| entry)
        .collect();
    Ok(Some(LoginBrowserPolicy {
        package: CHROME,
        hosts: hosts_of(&presets_of(&policy.login_browser_presets), &own),
    }))
}

/// A host as the report may carry it: lower case, the characters of a host name or an IP literal, at most
/// [MAX_HOST].
fn host(value: &Value) -> Option<String> {
    let text = value.as_str()?.trim().to_ascii_lowercase();
    (!text.is_empty()
        && text.len() <= MAX_HOST
        && text
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b':' | b'[' | b']')))
    .then_some(text)
}

/// A list of hosts, each once, at most `max`.
fn hosts(value: Option<&Value>, max: usize) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for h in value
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(host)
    {
        if out.len() < max && !out.contains(&h) {
            out.push(h);
        }
    }
    out
}

/// A free text cut to `max` characters, control characters gone; `None` when empty.
fn text(value: Option<&Value>, max: usize) -> Option<String> {
    let cleaned: String = value?
        .as_str()?
        .chars()
        .filter(|c| !c.is_control())
        .take(max)
        .collect();
    let cleaned = cleaned.trim().to_string();
    (!cleaned.is_empty()).then_some(cleaned)
}

/// One of `words`, else `None`.
fn word(value: Option<&Value>, words: &[&'static str]) -> Option<&'static str> {
    let text = value?.as_str()?;
    words.iter().copied().find(|w| *w == text)
}

/// A count or an instant: a whole number from 0 to `max`.
fn count(value: Option<&Value>, max: i64) -> Option<i64> {
    value?.as_i64().filter(|n| (0..=max).contains(n))
}

/// Instants after 2020 and before 2100, in ms.
const MS_MAX: i64 = 4_102_444_800_000;

/// The report's `captive_portal`, re-built field by field (§6.2, the contract's `clean`): `handler` one of
/// [HANDLERS] (else `other`), `parent_code_set` a boolean (else false), `today` its three counts (else 0), and the
/// last [MAX_SESSIONS] sessions - a session without a known `outcome`, `start`, `approved` or an `opened_ms` is
/// dropped; its hosts cleaned and cut ([MAX_VISITED], [MAX_BLOCKED]), `visited_count` at least the list's length.
/// `None` when it isn't an object.
pub fn clean_portal(value: &Value) -> Option<Value> {
    let object = value.as_object()?;
    let today = object.get("today").and_then(Value::as_object);
    let today_count = |key: &str, max: i64| today.and_then(|t| count(t.get(key), max)).unwrap_or(0);
    let sessions: Vec<Value> = object
        .get("sessions")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(Value::as_object)
        .filter_map(clean_session)
        .take(MAX_SESSIONS)
        .collect();
    Some(json!({
        "handler": word(object.get("handler"), HANDLERS).unwrap_or("other"),
        "parent_code_set": object.get("parent_code_set").and_then(Value::as_bool).unwrap_or(false),
        "today": {
            "openings": today_count("openings", 1_000),
            "ms": today_count("ms", 86_400_000),
            "grant_used": today_count("grant_used", 1_000),
        },
        "sessions": sessions,
    }))
}

fn clean_session(session: &Map<String, Value>) -> Option<Value> {
    let opened_ms = count(session.get("opened_ms"), MS_MAX)?;
    let outcome = word(session.get("outcome"), OUTCOMES)?;
    let start = word(session.get("start"), STARTS)?;
    let approved = word(session.get("approved"), APPROVALS)?;
    let closed_ms = count(session.get("closed_ms"), MS_MAX).filter(|c| *c >= opened_ms);
    let visited = hosts(session.get("visited"), MAX_VISITED);
    let visited_count = count(session.get("visited_count"), 100_000)
        .unwrap_or(0)
        .max(visited.len() as i64);
    Some(json!({
        "opened_ms": opened_ms,
        "closed_ms": closed_ms,
        "network": text(session.get("network"), MAX_NETWORK),
        "first_host": session.get("first_host").and_then(host),
        "outcome": outcome,
        "visited": visited,
        "visited_count": visited_count,
        "blocked_hosts": hosts(session.get("blocked_hosts"), MAX_BLOCKED),
        "blocked_frames": hosts(session.get("blocked_frames"), MAX_BLOCKED),
        "start": start,
        "approved": approved,
    }))
}

/// The report's `login_browser`, re-built (§6.2): `package` Chrome's or dropped (a launcher never reports another),
/// `state` one of [BROWSER_STATES], `reason` one of [BROWSER_REASONS] or null, `version_name` cut, the instants
/// plausible or null. `None` when it isn't an object or its state is unknown.
pub fn clean_browser(value: &Value) -> Option<Value> {
    let object = value.as_object()?;
    let state = word(object.get("state"), BROWSER_STATES)?;
    Some(json!({
        "package": word(object.get("package"), &[CHROME]),
        "state": state,
        "version_name": text(object.get("version_name"), MAX_VERSION),
        "written_ms": count(object.get("written_ms"), MS_MAX),
        "reason": word(object.get("reason"), BROWSER_REASONS),
        "cleared_ms": count(object.get("cleared_ms"), MS_MAX),
    }))
}

/// A stored session, as the page and the events read it (the cleaned shape).
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct Session {
    pub opened_ms: i64,
    pub closed_ms: Option<i64>,
    pub network: Option<String>,
    pub first_host: Option<String>,
    pub outcome: String,
    pub visited: Vec<String>,
    pub visited_count: i64,
    pub blocked_hosts: Vec<String>,
    pub blocked_frames: Vec<String>,
    pub start: String,
    pub approved: String,
}

/// The stored `today`.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct Today {
    pub openings: i64,
    pub ms: i64,
    pub grant_used: i64,
}

/// The stored `captive_portal`.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct PortalReport {
    pub handler: String,
    pub parent_code_set: bool,
    pub today: Today,
    pub sessions: Vec<Session>,
}

/// The stored `login_browser`.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct BrowserReport {
    pub state: String,
    pub version_name: Option<String>,
    pub written_ms: Option<i64>,
    pub reason: Option<String>,
}

/// A session the parent should hear about (§3.7, R2-L3): more than 3 sites visited, a blocked frame (never a blocked
/// sub-resource: portal pages load blocked analytics), or the time ran out.
pub fn wide(session: &Session) -> bool {
    session.visited_count > 3 || !session.blocked_frames.is_empty() || session.outcome == "timeout"
}

/// Today's code-free budget is used up (§3.4): the openings or the minutes, with each grant's opening and minutes.
pub fn budget_spent(today: &Today) -> bool {
    let grants = today.grant_used.clamp(0, GRANTS_A_DAY);
    today.openings >= DAY_OPENINGS + grants || today.ms >= (DAY_MINUTES + MINUTES * grants) * 60_000
}

/// The push of a Wi-Fi sign-in event (§3.7): at once in the day, held until 07:00 between 23:00 and 07:00 in the
/// family's zone (no pings at night).
pub fn night_push(
    zone: &crate::display_time::Zone,
    now: DateTime<Utc>,
) -> crate::parent_events::Push {
    let local = zone.local(now);
    let hour = local.hour();
    if (7..23).contains(&hour) {
        return crate::parent_events::Push::Now;
    }
    let day = if hour >= 23 {
        local.date_naive() + Duration::days(1)
    } else {
        local.date_naive()
    };
    let seven = day.and_time(NaiveTime::from_hms_opt(7, 0, 0).expect("07:00"));
    match zone.tz().from_local_datetime(&seven).earliest() {
        Some(at) => crate::parent_events::Push::Held(at.with_timezone(&Utc)),
        None => crate::parent_events::Push::Now,
    }
}

/// The report's events (§3.7): each wide session once (keyed by its `opened_ms`), and the budget used up once a day
/// (keyed by the family's local date). Only from a launcher that claims [PORTAL_CAPABILITY] (the caller checks).
pub async fn intake(
    state: &crate::AppState,
    phone: &crate::parent_events::Phone,
    stored: Option<&str>,
) {
    let Some(report) = stored.and_then(|json| serde_json::from_str::<PortalReport>(json).ok())
    else {
        return;
    };
    let push = night_push(&phone.zone, phone.now);
    for session in report.sessions.iter().filter(|s| wide(s)) {
        crate::parent_events::raise_logged(state, &phone.wifi_signin_wide(session, push)).await;
    }
    if budget_spent(&report.today) {
        let date = phone.zone.today(phone.now).to_string();
        crate::parent_events::raise_logged(state, &phone.wifi_signin_budget(&date, push)).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The add field's rules beyond the contract's rows.
    #[test]
    fn entries_keep_host_port_and_path() {
        assert_eq!(
            valid_entry("HTTPS://School.Example:8443/Login/SSO?next=1#top").as_deref(),
            Some("school.example:8443/Login/SSO")
        );
        assert_eq!(valid_entry("school.example:0"), None);
        assert_eq!(valid_entry("school.example:x"), None);
        assert_eq!(valid_entry("-bad.example"), None);
        assert_eq!(valid_entry("school.123"), None);
        assert_eq!(valid_entry("school.example/a b"), None);
        assert_eq!(valid_entry("school.example/a\"b"), None);
    }

    #[test]
    fn presets_are_a_set_in_the_tables_order() {
        let stored = presets_with("[]", "google", true);
        let stored = presets_with(&stored, "feide", true);
        assert_eq!(stored, r#"["feide","google"]"#);
        assert_eq!(
            presets_of(&presets_with(&stored, "google", false)),
            ["feide"]
        );
        assert_eq!(presets_of("not json"), Vec::<&str>::new());
        assert_eq!(presets_of(r#"["laser","apple"]"#), ["apple"]);
    }

    #[test]
    fn the_budget_and_the_wide_session() {
        let today = |openings, ms, grant_used| Today {
            openings,
            ms,
            grant_used,
        };
        assert!(!budget_spent(&today(3, 19 * 60_000, 0)));
        assert!(budget_spent(&today(4, 0, 0)));
        assert!(budget_spent(&today(0, 20 * 60_000, 0)));
        assert!(!budget_spent(&today(4, 0, 1)), "a grant adds an opening");
        assert!(!budget_spent(&today(1, 25 * 60_000, 1)), "and 10 minutes");
        let session = |visited_count, frames: &[&str], outcome: &str| Session {
            visited_count,
            blocked_frames: frames.iter().map(|f| f.to_string()).collect(),
            outcome: outcome.to_string(),
            blocked_hosts: vec!["ads.example".to_string()],
            ..Default::default()
        };
        assert!(
            !wide(&session(3, &[], "validated")),
            "a blocked sub-resource is no event"
        );
        assert!(wide(&session(4, &[], "validated")));
        assert!(wide(&session(1, &["video.example"], "closed")));
        assert!(wide(&session(1, &[], "timeout")));
    }

    #[test]
    fn a_night_push_waits_for_seven() {
        let zone = crate::display_time::Zone::from_setting("Europe/Oslo");
        let at = |s: &str| crate::display_time::parse_db_utc(s).unwrap();
        assert_eq!(
            night_push(&zone, at("2026-11-15 12:00:00")),
            crate::parent_events::Push::Now
        );
        // 23:30 in Oslo (UTC+1) waits for 07:00 the next morning.
        assert_eq!(
            night_push(&zone, at("2026-11-15 22:30:00")),
            crate::parent_events::Push::Held(at("2026-11-16 06:00:00"))
        );
        // 03:00 waits for 07:00 the same morning.
        assert_eq!(
            night_push(&zone, at("2026-11-16 02:00:00")),
            crate::parent_events::Push::Held(at("2026-11-16 06:00:00"))
        );
    }
}
