//! The round "Mobilnett" (design 94, data roaming; design 95 revision 3, "Bare Vibb" and the mobile-network settings
//! lock): the stored choices as the policy serves them, the report's `data_roaming` and `mobile_data` cleaned field by
//! field, and the words the PWA shows - on the phone's own Mobilnett page (`handlers::mobile_page`,
//! `device_mobile.html`), as the row's value and as lines on the summary.
//!
//! The shared contract is `testdata/mobile_network.json` (byte-identical in the launcher's test resources;
//! `tests::mobile_network`). The launcher alone decides the VPN's shape; nothing here judges a network.

use serde::Deserialize;
use serde_json::{Value, json};

use crate::i18n::{Lang, t, t_params};
use crate::models::{DevicePolicy, DeviceStatus};

/// "Mobildata for apper" (design 95 rev. 3).
pub const APPS_CHOICES: &[&str] = &["on", "vibb"];

/// Design 97: "I utlandet", since design 113 "Roaming" - Av (`off`), Bare Vibb (`vibb`), På (`home`).
pub const ABROAD_CHOICES: &[&str] = &["off", "vibb", "home"];

/// The stored "Roaming" as served: `vibb` and `home` as they are, anything else `off` (fail closed).
pub fn abroad_choice(stored: &str) -> &'static str {
    match stored {
        "vibb" => "vibb",
        "home" => "home",
        _ => "off",
    }
}

/// The stored "Mobildata for apper" as served: `vibb` or `on`.
pub fn apps_choice(stored: &str) -> &'static str {
    if stored == "vibb" { "vibb" } else { "on" }
}

/// Design 94 §5: the report's `data_roaming`, re-serialised field by field. A booleans that isn't one reads as its safe
/// value (`false`; `null` for `enabled`), `country` must be `[a-z]{2}`, `network` `[0-9]{5,6}`, `modems` 1..=8, design
/// 97's `where` one of `home`, `abroad`, `unknown` (else `null`); a value that isn't an object is
/// `None` (stored `NULL`), never a refused report.
pub fn clean_roaming(value: &Value) -> Option<String> {
    let object = value.as_object()?;
    let flag = |key: &str| object.get(key).and_then(Value::as_bool).unwrap_or(false);
    let country = object
        .get("country")
        .and_then(Value::as_str)
        .filter(|c| c.len() == 2 && c.bytes().all(|b| b.is_ascii_lowercase()));
    let network = object
        .get("network")
        .and_then(Value::as_str)
        .filter(|n| (5..=6).contains(&n.len()) && n.bytes().all(|b| b.is_ascii_digit()));
    let modems = object
        .get("modems")
        .and_then(Value::as_i64)
        .filter(|m| (1..=8).contains(m));
    Some(
        json!({
            "locked": flag("locked"),
            "roaming": flag("roaming"),
            "country": country,
            "network": network,
            "enabled": object.get("enabled").and_then(Value::as_bool),
            "data_active": flag("data_active"),
            "modems": modems,
            "where": object
                .get("where")
                .and_then(Value::as_str)
                .filter(|w| matches!(*w, "home" | "abroad" | "unknown")),
        })
        .to_string(),
    )
}

/// Design 95 rev. 3: the report's `mobile_data`, re-serialised: `enabled` a boolean or `null`, `sink` one of the known
/// words (else `off`), `settings_locked` a boolean (else `false`); design 97's `kid` `on`, `off` or `null` (the row
/// hidden); design 126's `cellular_2g` one of [CELLULAR_2G_WORDS], anything else left out.
pub fn clean_mobile(value: &Value) -> Option<String> {
    let object = value.as_object()?;
    let sink = object
        .get("sink")
        .and_then(Value::as_str)
        .filter(|s| matches!(*s, "off" | "parent" | "abroad" | "roaming" | "kid"))
        .unwrap_or("off");
    let mut clean = json!({
        "enabled": object.get("enabled").and_then(Value::as_bool),
        "sink": sink,
        "settings_locked": object.get("settings_locked").and_then(Value::as_bool).unwrap_or(false),
        "kid": object
            .get("kid")
            .and_then(Value::as_str)
            .filter(|k| matches!(*k, "on" | "off")),
    });
    if let Some(word) = object
        .get("cellular_2g")
        .and_then(Value::as_str)
        .filter(|w| CELLULAR_2G_WORDS.contains(w))
    {
        clean["cellular_2g"] = json!(word);
    }
    Some(clean.to_string())
}

/// Design 126 A5 (§3.6.4): the report's `mobile_data.cellular_2g` - `blocked` (the restriction set), `abroad` (lifted by
/// itself abroad), `lifted` (a pause, the override or a setup window), `off` (the switch off or no policy).
pub const CELLULAR_2G_WORDS: &[&str] = &["blocked", "abroad", "lifted", "off"];

/// Design 116 §3.5: how long the phone's filter is down before the server raises "Web filter is down" - the contract's
/// `vpn_filter.report_after_ms`, the launcher's `VPN_REPORT_AFTER_MS`.
pub const VPN_REPORT_AFTER_MS: i64 = 600_000;

/// Design 116 §3.4: the report's `vpn_filter`, re-serialised: `state` one of `up`, `down`, `off` (else the field is
/// dropped), `down_ms` a non-negative integer and `period` at most 32 characters of `[0-9:]`, both kept only with
/// `down`. A value that isn't an object is `None` (stored `NULL`), never a refused report.
pub fn clean_vpn_filter(value: &Value) -> Option<String> {
    let object = value.as_object()?;
    let state = object
        .get("state")
        .and_then(Value::as_str)
        .filter(|s| matches!(*s, "up" | "down" | "off"))?;
    let mut clean = serde_json::Map::new();
    clean.insert("state".into(), json!(state));
    if state == "down" {
        if let Some(down_ms) = object
            .get("down_ms")
            .and_then(Value::as_i64)
            .filter(|ms| *ms >= 0)
        {
            clean.insert("down_ms".into(), json!(down_ms));
        }
        if let Some(period) = object.get("period").and_then(Value::as_str).filter(|p| {
            !p.is_empty() && p.len() <= 32 && p.bytes().all(|b| b.is_ascii_digit() || b == b':')
        }) {
            clean.insert("period".into(), json!(period));
        }
    }
    Some(Value::Object(clean).to_string())
}

/// A stored `vpn_filter_json`.
#[derive(Debug, Deserialize, Clone, PartialEq, Eq)]
pub struct VpnFilterReport {
    pub state: String,
    #[serde(default)]
    pub down_ms: Option<i64>,
    #[serde(default)]
    pub period: Option<String>,
}

/// The latest report's `vpn_filter`.
pub fn vpn_filter_report(latest: &DeviceStatus) -> Option<VpnFilterReport> {
    serde_json::from_str(latest.vpn_filter_json.as_deref()?).ok()
}

/// Design 116 §3.4 (QA M10, the contract's `raise` rows): "Web filter is down" holds when the report says `down` for at
/// least [VPN_REPORT_AFTER_MS], the parent's switch is on now, and the report isn't paused (a pause, the setup hold) -
/// a parent's choice is never down.
pub fn filter_down_raised(
    state: &str,
    down_ms: Option<i64>,
    filter_enabled: bool,
    paused: bool,
) -> bool {
    state == "down"
        && down_ms.is_some_and(|ms| ms >= VPN_REPORT_AFTER_MS)
        && filter_enabled
        && !paused
}

/// The phone's filter is down now by its last report ([filter_down_raised]): the report, for its `down_ms` and
/// `period`.
pub fn filter_down(
    policy: &DevicePolicy,
    latest: Option<&DeviceStatus>,
    now_ms: i64,
) -> Option<VpnFilterReport> {
    let latest = latest?;
    let report = vpn_filter_report(latest)?;
    filter_down_raised(
        &report.state,
        report.down_ms,
        policy.vpn_filter_enabled,
        latest.paused_at(now_ms),
    )
    .then_some(report)
}

/// [filter_down] read from the database (the `/dns` page's line); a read error is "not down" (logged).
pub async fn filter_down_of(
    db: &sqlx::SqlitePool,
    device_id: i64,
    now_ms: i64,
) -> Option<VpnFilterReport> {
    let read = async {
        let policy =
            sqlx::query_as::<_, DevicePolicy>("SELECT * FROM device_policy WHERE device_id = ?")
                .bind(device_id)
                .fetch_optional(db)
                .await?;
        let latest = sqlx::query_as::<_, DeviceStatus>(
            "SELECT * FROM device_status WHERE device_id = ? ORDER BY reported_at DESC, id DESC LIMIT 1",
        )
        .bind(device_id)
        .fetch_optional(db)
        .await?;
        Ok::<_, sqlx::Error>((policy, latest))
    };
    match read.await {
        Ok((Some(policy), latest)) => filter_down(&policy, latest.as_ref(), now_ms),
        Ok((None, _)) => None,
        Err(err) => {
            tracing::error!(device_id, %err, "can't read whether the web filter is down");
            None
        }
    }
}

/// Design 116 §3.4: the event "Web filter is down", once per down period (keyed by the report's `period`), at the first
/// report that raises the row and under its conditions. No event when the filter comes back.
pub async fn filter_down_intake(state: &crate::AppState, phone: &crate::parent_events::Phone) {
    let Some(report) = filter_down_of(&state.db, phone.id, phone.now.timestamp_millis()).await
    else {
        return;
    };
    let Some(period) = report.period.as_deref() else {
        return;
    };
    let started = phone.now - chrono::Duration::milliseconds(report.down_ms.unwrap_or(0));
    crate::parent_events::raise_logged(state, &phone.filter_down(period, started)).await;
}

/// A stored `data_roaming_json`.
#[derive(Debug, Default, Deserialize, Clone, PartialEq, Eq)]
pub struct RoamingReport {
    pub locked: bool,
    pub roaming: bool,
    pub country: Option<String>,
    pub network: Option<String>,
    pub enabled: Option<bool>,
    pub data_active: bool,
    pub modems: Option<i64>,
    /// Design 97 §4.1: the launcher's own judgement, `home`, `abroad` or `unknown`; `None` when absent.
    #[serde(default, rename = "where")]
    pub where_is: Option<String>,
}

/// A stored `mobile_data_json`.
#[derive(Debug, Deserialize, Clone, PartialEq, Eq)]
pub struct MobileReport {
    pub enabled: Option<bool>,
    pub sink: String,
    pub settings_locked: bool,
    /// Design 97 §3.2: the kid's own switch, `on` or `off`; `None` while the row is hidden.
    #[serde(default)]
    pub kid: Option<String>,
    /// Design 126 A5: "Blokker 2G" as the phone applies it ([CELLULAR_2G_WORDS]); `None` from a launcher without it.
    #[serde(default)]
    pub cellular_2g: Option<String>,
}

/// The latest report's `data_roaming`.
pub fn roaming_report(latest: &DeviceStatus) -> Option<RoamingReport> {
    serde_json::from_str(latest.data_roaming_json.as_deref()?).ok()
}

/// Design 121 §4.3: the phone said it is abroad (`where`), the parent's Roaming is Av (`off`, "Ingen data") and no pause
/// lifts it - a quiet the parent chose, grey on the pages, never "No contact".
pub fn abroad_no_data(abroad: &str, latest: &DeviceStatus, now_ms: i64) -> bool {
    abroad_choice(abroad) == "off"
        && roaming_report(latest).is_some_and(|r| r.where_is.as_deref() == Some("abroad"))
        && !latest.paused_at(now_ms)
}

/// The latest report's `mobile_data`.
pub fn mobile_report(latest: &DeviceStatus) -> Option<MobileReport> {
    serde_json::from_str(latest.mobile_data_json.as_deref()?).ok()
}

/// The countries named in full (design 94 §6.2): the EEA (EU 27, IS, LI, NO) and then GB and CH, which are outside it.
/// Each has `data_roaming.place.<cc>` in both catalogs - the whole place phrase ("i Sverige", "på Island"; "in the UK"),
/// since nb's preposition and en's "the" depend on the country (design 97 §5: used through `data_roaming.in` and the
/// roaming notice's title).
pub const NAMED: &[&str] = &[
    "at", "be", "bg", "hr", "cy", "cz", "dk", "ee", "fi", "fr", "de", "gr", "hu", "ie", "it", "lv",
    "lt", "lu", "mt", "nl", "pl", "pt", "ro", "sk", "si", "es", "se", "is", "li", "no", "gb", "ch",
];
/// [NAMED]'s first 30 are the EEA.
const EEA_COUNT: usize = 30;

fn ship_network(report: &RoamingReport) -> bool {
    report
        .network
        .as_deref()
        .is_some_and(|n| n.starts_with("901"))
}

/// A named country's place phrase ("i Sverige" / "in Sweden"), `None` for a country not in [NAMED].
pub fn place_phrase(lang: Lang, cc: &str) -> Option<String> {
    NAMED
        .contains(&cc)
        .then(|| t(lang, &format!("data_roaming.place.{cc}")).to_string())
}

/// Design 97 code QA M2: the launcher reads the phone abroad on a foreign network Android doesn't flag as roaming
/// (carrier config can clear the flag for a partner network); it is worded as roaming.
fn abroad_unflagged(report: &RoamingReport) -> bool {
    !report.roaming && !ship_network(report) && report.where_is.as_deref() == Some("abroad")
}

/// Design 94 §6.2: where the phone roams, and whether it is a warning (orange). The 901 rows decide first - near land
/// the country can be a coastal one. `None` when the phone doesn't roam (or, design 97, isn't abroad) and isn't on a ship
/// or plane network.
pub fn roaming_words(lang: Lang, report: &RoamingReport) -> Option<(String, bool)> {
    if ship_network(report) {
        let key = if report.roaming {
            "data_roaming.ship"
        } else {
            "data_roaming.ship_home"
        };
        return Some((t(lang, key).to_string(), true));
    }
    if !report.roaming && !abroad_unflagged(report) {
        return None;
    }
    // National roaming: another network in the SIM's own country is never "roaming in <home>".
    if report.where_is.as_deref() == Some("home") {
        return Some((t(lang, "data_roaming.national").to_string(), false));
    }
    Some(match report.country.as_deref() {
        Some(cc) => match place_phrase(lang, cc) {
            Some(place) => (
                t_params(lang, "data_roaming.in", &[("place", &place)]),
                NAMED.iter().position(|named| *named == cc) >= Some(EEA_COUNT),
            ),
            None => (
                t_params(
                    lang,
                    "data_roaming.elsewhere",
                    &[("country", &cc.to_ascii_uppercase())],
                ),
                true,
            ),
        },
        None => (t(lang, "data_roaming.plain").to_string(), true),
    })
}

/// [roaming_words] with a ship network Android doesn't count as roaming worded as a flagged one while the phone's sink
/// covers it ([ship_covered]): "Roamer på et skips- eller flynett", orange, as the flagged ship (code QA M1 of
/// `server-small`); uncovered it stays `data_roaming.ship_home`.
fn shown_roaming_words(
    lang: Lang,
    report: &RoamingReport,
    covered: bool,
) -> Option<(String, bool)> {
    if ship_network(report) && !report.roaming && covered {
        return Some((t(lang, "data_roaming.ship").to_string(), true));
    }
    roaming_words(lang, report)
}

/// The summary header's roaming part (design 94 §6.2), from the latest report, by the Mobilnett page's rule
/// ([shown_roaming_words]; the roaming emulator run of 2026-11-19, finding 1).
pub fn summary_roaming(
    lang: Lang,
    latest: &DeviceStatus,
    policy: &DevicePolicy,
) -> Option<(String, bool)> {
    let report = roaming_report(latest)?;
    let covered = ship_covered(policy, mobile_report(latest).as_ref());
    shown_roaming_words(lang, &report, covered)
}

/// One state line: its words, an optional sentence under them, and whether it is a warning (orange).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Line {
    /// For tests and the page's hooks (`data-mobile-line`).
    pub key: &'static str,
    pub title: String,
    pub sub: Option<String>,
    pub warn: bool,
}

impl Line {
    fn new(lang: Lang, key: &'static str, title: &str, sub: Option<&str>, warn: bool) -> Line {
        Line {
            key,
            title: t(lang, title).to_string(),
            sub: sub.map(|s| t(lang, s).to_string()),
            warn,
        }
    }

    fn named(lang: Lang, key: &'static str, title: &str, name: &str) -> Line {
        Line {
            key,
            title: t_params(lang, title, &[("name", name)]),
            sub: None,
            warn: false,
        }
    }
}

/// Design 94 §6.1's warnings from the report: a roaming session that outlived the lock (with a launcher that sinks it,
/// only Vibb uses it), a SIM that still reads roaming on under the lock, a ship network the lock doesn't cover (design
/// 97: unless the phone's abroad sink covers it - Av or Bare Vibb).
fn roaming_warnings(
    lang: Lang,
    report: &RoamingReport,
    mobile: Option<&MobileReport>,
    airplane_locked: bool,
    ship_covered: bool,
) -> Vec<Line> {
    let mut lines = Vec::new();
    if report.locked && report.data_active {
        let sinking = mobile.is_some_and(|m| m.sink == "roaming" || m.sink == "abroad");
        let sub = match (sinking, airplane_locked) {
            (_, true) => "mobile.state.lingering_fix_restart",
            (_, false) => "mobile.state.lingering_fix",
        };
        let title = if sinking {
            "mobile.state.lingering_vibb"
        } else {
            "mobile.state.lingering"
        };
        lines.push(Line::new(lang, "lingering", title, Some(sub), true));
    }
    if report.locked && report.enabled == Some(true) {
        lines.push(Line::new(
            lang,
            "still-on",
            "mobile.state.still_on",
            Some("mobile.state.still_on_sub"),
            true,
        ));
    }
    if ship_network(report) && !report.roaming && !ship_covered {
        lines.push(Line {
            key: "ship-home",
            title: t(lang, "data_roaming.ship_home").to_string(),
            sub: None,
            warn: true,
        });
    }
    lines
}

/// Whether the phone's abroad sink covers a ship network Android doesn't count as roaming (design 97 Q97-H1): Bare
/// Vibb or Av, and - where the report says - a sink actually up (code QA L1 of `server-small`: a pause, or a choice not
/// on the phone yet, leaves the ship uncovered, and the orange `ship_home` comes back).
fn ship_covered(policy: &DevicePolicy, mobile: Option<&MobileReport>) -> bool {
    abroad_choice(&policy.abroad) != "home"
        && mobile.is_none_or(|m| matches!(m.sink.as_str(), "abroad" | "parent" | "roaming" | "kid"))
}

/// The "only Vibb" line for the report's sink (design 97 §5): only the kid's Off, a quiet value on the summary (design
/// 113: the parent's Bare Vibb and the abroad sink restate the chosen radio, so they have no line).
fn only_vibb_line(lang: Lang, name: &str, sink: &str) -> Option<Line> {
    (sink == "kid").then(|| Line::named(lang, "only-vibb-kid", "mobile.state.only_vibb_kid", name))
}

/// The summary's lines (design 95 rev. 3; design 113): only when they say something - the kid's Off (grey) and the
/// roaming warnings. Mobile data off on the phone is design 114's setup row (`attention::Kind::Setup`), said once.
/// Each leads to the Mobilnett page.
pub fn summary_lines(
    lang: Lang,
    name: &str,
    policy: &DevicePolicy,
    latest: Option<&DeviceStatus>,
) -> Vec<Line> {
    let Some(latest) = latest else {
        return Vec::new();
    };
    let mobile = mobile_report(latest);
    let mut lines = Vec::new();
    if let Some(m) = &mobile
        && m.enabled != Some(false)
        && let Some(line) = only_vibb_line(lang, name, &m.sink)
    {
        lines.push(line);
    }
    if let Some(report) = roaming_report(latest) {
        lines.extend(roaming_warnings(
            lang,
            &report,
            mobile.as_ref(),
            policy.hardening.disallow_airplane_mode,
            ship_covered(policy, mobile.as_ref()),
        ));
    }
    lines
}

/// The phone page's Mobilnett row value (design 97 §5): the choices away from a new phone's defaults (apps on, Bare
/// Vibb abroad) and the kid's Off, joined with " · "; `None` when nothing differs. Mobile data off on the phone is the
/// Attention row's, not a part here (design 113, QA L6).
pub fn row_value(
    lang: Lang,
    name: &str,
    policy: &DevicePolicy,
    latest: Option<&DeviceStatus>,
) -> Option<String> {
    let mut parts: Vec<String> = Vec::new();
    if apps_choice(&policy.apps_mobile) == "vibb" {
        parts.push(t(lang, "mobile.value.vibb").to_string());
    }
    match abroad_choice(&policy.abroad) {
        "off" => parts.push(t(lang, "mobile.value.abroad_off").to_string()),
        "home" => parts.push(t(lang, "mobile.value.abroad_home").to_string()),
        _ => {}
    }
    if latest
        .and_then(mobile_report)
        .is_some_and(|m| m.kid.as_deref() == Some("off"))
    {
        parts.push(t_params(lang, "mobile.value.kid_off", &[("name", name)]));
    }
    (!parts.is_empty()).then(|| parts.join(" · "))
}

/// The Mobilnett page's groups (`device_mobile.html`).
pub struct Card {
    pub apps_mobile: &'static str,
    pub abroad: &'static str,
    pub settings_locked: bool,
    /// The parent shows the kid's own Mobildata switch (`quick_controls_mask` bit 32).
    pub kid_shown: bool,
    /// Under "Mobildata", "Roaming" and the lock: the phone's state, worded. A `warn` line is orange above its group,
    /// its `sub` behind its ⓘ; a grey line has no `sub`.
    pub data_lines: Vec<Line>,
    pub abroad_lines: Vec<Line>,
    pub lock_lines: Vec<Line>,
    /// Design 126 A5: "Blokker 2G" on, "Tillat 2G i utlandet" on (the user's decision of 2026-11-22: off by default, 2G
    /// stays blocked abroad), and the one quiet line - "Av i utlandet" while both are on and the latest report says the
    /// phone lifted it abroad (the rule working, never orange).
    pub block_2g: bool,
    pub allow_2g_abroad: bool,
    pub block_2g_lines: Vec<Line>,
}

/// Whether Android's roaming switch, read off, is what keeps the phone off mobile data now: Android counts the network
/// as roaming (`roaming`) and no roaming session is up (`data_active`). On a foreign network Android doesn't flag, the
/// switch stops nothing and the phone has data (the roaming emulator run of 2026-11-19, finding 2).
fn switch_stops_data(report: &RoamingReport) -> bool {
    report.enabled == Some(false) && report.roaming && !report.data_active
}

/// The switch line under "Roaming" (design 97 §5; design 113: Android's switch is said only when it is wrong). Under Av
/// (`off`): nothing when the report says `locked` (the lock working as chosen), the grey "not on the phone yet" while it
/// isn't and no pause runs. Otherwise (På or Bare Vibb) one orange line only when the switch reads off - leading with the
/// place and "no mobile data now" while the phone is abroad and the switch is what stops its data
/// ([switch_stops_data]) -, its fix behind the line's ⓘ; on or unknown says nothing.
fn switch_line(
    lang: Lang,
    name: &str,
    abroad: &str,
    report: &RoamingReport,
    paused: bool,
) -> Option<Line> {
    if abroad == "off" {
        // The lock hasn't reached the phone yet (a choice changed since its report); a pause lifts it on purpose.
        return (!report.locked && !paused)
            .then(|| Line::new(lang, "pending", "mobile.state.pending", None, false));
    }
    if report.enabled != Some(false) {
        return None;
    }
    Some(
        if report.where_is.as_deref() == Some("abroad") && switch_stops_data(report) {
            let place = roaming_words(lang, report)
                .map(|(words, _)| words)
                .unwrap_or_else(|| t(lang, "data_roaming.plain").to_string());
            Line {
                key: "switch",
                title: t_params(
                    lang,
                    "mobile.abroad.switch.off_abroad",
                    &[("place", &place)],
                ),
                sub: Some(t_params(
                    lang,
                    "mobile.abroad.switch.off_abroad_sub",
                    &[("name", name)],
                )),
                warn: true,
            }
        } else {
            // Code QA L2: abroad with data (a network Android doesn't flag, a session still up), the ⓘ says it
            // works now.
            let sub = if report.where_is.as_deref() == Some("abroad") {
                "mobile.abroad.switch.off_has_data_sub"
            } else {
                "mobile.abroad.switch.off_sub"
            };
            Line::new(lang, "switch", "mobile.abroad.switch.off", Some(sub), true)
        },
    )
}

/// The page's state from the stored choices and the latest report (design 94 §6.1's table, design 95 rev. 3's lines,
/// design 97 §5).
pub fn card(lang: Lang, name: &str, policy: &DevicePolicy, latest: Option<&DeviceStatus>) -> Card {
    let apps_mobile = apps_choice(&policy.apps_mobile);
    let abroad = abroad_choice(&policy.abroad);
    let mobile = latest.and_then(mobile_report);
    let roaming = latest.and_then(roaming_report);
    let paused = latest.is_some_and(|s| s.paused_at(chrono::Utc::now().timestamp_millis()));

    let mut data_lines = Vec::new();
    if let Some(m) = &mobile {
        // Android's own mobile-data switch off is design 114's setup step `mobile_data`, its warning line at the
        // group's top (`setup_status::page_warnings`).
        // Code QA L6: the kid's Off is said once on the page, by its own line; the "only Vibb" lines are the summary's
        // (design 113: the parent's and the abroad one restate the chosen radio, so the page has none).
        if m.kid.as_deref() == Some("off") {
            data_lines.push(Line::named(lang, "kid-off", "mobile.state.kid_off", name));
        }
    }
    if apps_mobile == "vibb" && paused && mobile.is_some() {
        data_lines.push(Line::new(
            lang,
            "lifted",
            "mobile.state.lifted",
            None,
            false,
        ));
    }

    let mut abroad_lines = Vec::new();
    if let Some(report) = &roaming {
        abroad_lines.extend(switch_line(lang, name, abroad, report, paused));
        // The abroad switch-off line already leads with the place: one mention is enough. QA M1 (design 113): only that
        // variant - the plain orange "off" at home or with `where` unknown keeps the place or national line below it.
        let place_in_switch = report.where_is.as_deref() == Some("abroad")
            && switch_stops_data(report)
            && abroad_lines.iter().any(|l: &Line| l.key == "switch");
        // Code QA M1: a ship network Android doesn't flag, covered by the sink, is worded as a flagged one.
        let covered = ship_covered(policy, mobile.as_ref());
        let unflagged_ship = ship_network(report) && !report.roaming && covered;
        if let Some((words, warn)) = shown_roaming_words(lang, report, covered).filter(|_| {
            (report.roaming || abroad_unflagged(report) || unflagged_ship) && !place_in_switch
        }) {
            abroad_lines.push(Line {
                key: "roaming",
                title: words,
                sub: None,
                warn,
            });
        }
        // Design 97 §5: Av cuts mobile data in a national-roaming area at home.
        if abroad == "off" && report.roaming && report.where_is.as_deref() == Some("home") {
            abroad_lines.push(Line::new(
                lang,
                "national",
                "mobile.abroad.national",
                Some("mobile.abroad.national_sub"),
                true,
            ));
        }
        abroad_lines.extend(roaming_warnings(
            lang,
            report,
            mobile.as_ref(),
            policy.hardening.disallow_airplane_mode,
            ship_covered(policy, mobile.as_ref()),
        ));
    }

    let mut lock_lines = Vec::new();
    if let Some(m) = &mobile
        && policy.mobile_settings_locked
        && !m.settings_locked
    {
        // Code QA L6: lifted by a pause, or not in place yet.
        lock_lines.push(if paused {
            Line::new(lang, "lifted", "mobile.state.lifted", None, false)
        } else {
            Line::new(lang, "pending", "mobile.state.pending", None, false)
        });
    }

    let mut block_2g_lines = Vec::new();
    if policy.block_2g
        && policy.allow_2g_abroad
        && mobile
            .as_ref()
            .is_some_and(|m| m.cellular_2g.as_deref() == Some("abroad"))
    {
        block_2g_lines.push(Line::new(
            lang,
            "abroad-2g",
            "mobile.block_2g.abroad",
            None,
            false,
        ));
    }

    Card {
        apps_mobile,
        abroad,
        settings_locked: policy.mobile_settings_locked,
        block_2g: policy.block_2g,
        allow_2g_abroad: policy.allow_2g_abroad,
        block_2g_lines,
        kid_shown: policy.quick_controls_mask & crate::handlers::devices::QUICK_CONTROL_MOBILE_DATA
            != 0,
        data_lines,
        abroad_lines,
        lock_lines,
    }
}

/// Design 97 §6 (D6 as corrected): a place told within this long is not told again - the border guard.
pub const ROAMING_GUARD: chrono::Duration = chrono::Duration::hours(1);

/// The roaming notice's state on the device (`devices.roaming_place`, `devices.roaming_told`): the last abroad place
/// (`None` at home) and the places told within [ROAMING_GUARD], with when (epoch ms).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct RoamingState {
    pub place: Option<String>,
    pub told: std::collections::BTreeMap<String, i64>,
}

impl RoamingState {
    /// From the two columns; an unreadable `told` reads as none (the worst case is one notice too many).
    pub fn of(place: Option<String>, told: Option<&str>) -> RoamingState {
        RoamingState {
            place,
            told: told
                .and_then(|t| serde_json::from_str(t).ok())
                .unwrap_or_default(),
        }
    }

    /// `roaming_told` as stored: `NULL` when empty.
    pub fn told_json(&self) -> Option<String> {
        (!self.told.is_empty())
            .then(|| serde_json::to_string(&self.told).expect("a map serializes"))
    }
}

/// A report's place for the notice (design 97 §6): `ship` on a 901 network (a place of its own, the most expensive),
/// else its country, else none.
pub fn notice_place(report: &RoamingReport) -> Option<String> {
    if ship_network(report) {
        return Some("ship".to_string());
    }
    report.country.clone()
}

/// Design 97 §6, the pure rule (D6 as corrected): what one report (`where_is` and its [notice_place]) does to the state,
/// and the place to tell, if any.
/// - `abroad` at another place than the last one (or none before): told, unless told within [ROAMING_GUARD]; the place
///   becomes the last one either way. The same place: nothing. No readable place: the plain notice only when there is
///   no last place (`plain` becomes it), otherwise nothing.
/// - `home`: the last place cleared - coming home never tells.
/// - `unknown` (or anything else): nothing changes.
///
/// Told entries older than the guard are dropped whenever the state changes.
pub fn roaming_edge(
    state: &RoamingState,
    where_is: &str,
    place: Option<&str>,
    now: chrono::DateTime<chrono::Utc>,
) -> (RoamingState, Option<String>) {
    let now_ms = now.timestamp_millis();
    let fresh = |told: &std::collections::BTreeMap<String, i64>| {
        told.iter()
            .filter(|(_, at)| now_ms - **at < ROAMING_GUARD.num_milliseconds())
            .map(|(p, at)| (p.clone(), *at))
            .collect::<std::collections::BTreeMap<_, _>>()
    };
    match where_is {
        "home" if state.place.is_some() => (
            RoamingState {
                place: None,
                told: fresh(&state.told),
            },
            None,
        ),
        "abroad" => {
            let place = match place {
                Some(place) => place,
                None if state.place.is_none() => "plain",
                None => return (state.clone(), None),
            };
            if state.place.as_deref() == Some(place) {
                return (state.clone(), None);
            }
            let mut told = fresh(&state.told);
            let tell = !told.contains_key(place);
            if tell {
                told.insert(place.to_string(), now_ms);
            }
            (
                RoamingState {
                    place: Some(place.to_string()),
                    told,
                },
                tell.then(|| place.to_string()),
            )
        }
        _ => (state.clone(), None),
    }
}

/// A report's `data_roaming` with `where` against the phone's roaming state (design 97 §6):
/// in one transaction, a compare-and-set on the state as read and the raise only when that changed the row (two
/// parallel reports tell once), as `parent_events::battery_report`. Failures are logged.
pub async fn roaming_intake(
    state: &crate::AppState,
    phone: &crate::parent_events::Phone,
    report: &RoamingReport,
) {
    let Some(where_is) = report.where_is.as_deref() else {
        return;
    };
    let result = async {
        let mut tx = state.db.begin().await?;
        let (place, told): (Option<String>, Option<String>) =
            sqlx::query_as("SELECT roaming_place, roaming_told FROM devices WHERE id = ?")
                .bind(phone.id)
                .fetch_one(&mut *tx)
                .await?;
        let before = RoamingState::of(place.clone(), told.as_deref());
        let (after, tell) = roaming_edge(
            &before,
            where_is,
            notice_place(report).as_deref(),
            phone.now,
        );
        if after == before {
            return Ok::<_, sqlx::Error>(false);
        }
        let changed = sqlx::query(
            "UPDATE devices SET roaming_place = ?, roaming_told = ? \
             WHERE id = ? AND roaming_place IS ? AND roaming_told IS ?",
        )
        .bind(&after.place)
        .bind(after.told_json())
        .bind(phone.id)
        .bind(&place)
        .bind(&told)
        .execute(&mut *tx)
        .await?
        .rows_affected();
        let raised = match tell {
            Some(tell) if changed == 1 => {
                crate::parent_events::raise(&mut tx, &phone.roaming_started(&tell, phone.now))
                    .await?
            }
            _ => None,
        };
        tx.commit().await?;
        Ok(raised.is_some())
    };
    match result.await {
        Ok(true) => state.push.wake(),
        Ok(false) => {}
        Err(err) => {
            tracing::error!(device_id = phone.id, %err, "can't keep the roaming notice's state")
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn roaming(country: Option<&str>, network: Option<&str>, roaming: bool) -> RoamingReport {
        RoamingReport {
            roaming,
            country: country.map(str::to_string),
            network: network.map(str::to_string),
            ..Default::default()
        }
    }

    #[test]
    fn the_words_name_the_country_and_ship_networks_first() {
        let se = roaming_words(Lang::Nb, &roaming(Some("se"), Some("24001"), true)).unwrap();
        assert_eq!(se, ("Roamer i Sverige".to_string(), false));
        let is = roaming_words(Lang::Nb, &roaming(Some("is"), None, true)).unwrap();
        assert_eq!(is.0, "Roamer på Island");
        let gb = roaming_words(Lang::En, &roaming(Some("gb"), None, true)).unwrap();
        assert_eq!(gb, ("Roaming in the UK".to_string(), true));
        let us = roaming_words(Lang::En, &roaming(Some("us"), None, true)).unwrap();
        assert_eq!(us, ("Roaming (US)".to_string(), true));
        assert_eq!(
            roaming_words(Lang::Nb, &roaming(None, None, true)).unwrap(),
            ("Roamer".to_string(), true)
        );
        // A ferry near Denmark reads "dk": the 901 network decides first.
        let ferry = roaming_words(Lang::Nb, &roaming(Some("dk"), Some("901012"), true)).unwrap();
        assert_eq!(
            ferry,
            ("Roamer på et skips- eller flynett".to_string(), true)
        );
        assert!(
            roaming_words(Lang::En, &roaming(None, Some("901012"), false))
                .unwrap()
                .0
                .contains("doesn't cover it")
        );
        assert_eq!(roaming_words(Lang::Nb, &roaming(None, None, false)), None);
        // Code QA M2: abroad on a network Android doesn't flag as roaming reads as roaming; at home it doesn't.
        let unflagged = |where_is: &str| RoamingReport {
            where_is: Some(where_is.to_string()),
            ..roaming(Some("se"), Some("24001"), false)
        };
        assert_eq!(
            roaming_words(Lang::Nb, &unflagged("abroad")),
            Some(("Roamer i Sverige".to_string(), false))
        );
        assert_eq!(roaming_words(Lang::Nb, &unflagged("home")), None);
        // National roaming: another network in the SIM's own country is never "Roamer i <home>".
        let national = RoamingReport {
            where_is: Some("home".to_string()),
            ..roaming(Some("no"), Some("24201"), true)
        };
        assert_eq!(
            roaming_words(Lang::Nb, &national),
            Some(("Bruker et annet nett i hjemlandet".to_string(), false))
        );
        assert_eq!(
            roaming_words(Lang::En, &national).unwrap().0,
            "Using another network in its home country"
        );
    }

    /// Design 97 §6 (D6 as corrected): a run of reports, each `(minutes, where, place)`, and the places told.
    fn told_in(run: &[(i64, &str, Option<&str>)]) -> Vec<String> {
        let start = chrono::DateTime::from_timestamp(1_763_100_000, 0).unwrap();
        let mut state = RoamingState::default();
        let mut told = Vec::new();
        for (minutes, where_is, place) in run {
            let now = start + chrono::Duration::minutes(*minutes);
            // Each write goes through the columns, as the intake's does.
            let (after, tell) = roaming_edge(&state, where_is, *place, now);
            state = RoamingState::of(after.place.clone(), after.told_json().as_deref());
            assert_eq!(state, after);
            told.extend(tell);
        }
        told
    }

    #[test]
    fn a_notice_for_every_crossing_with_the_hours_border_guard() {
        // Norway -> Sweden -> Denmark -> Sweden, hours apart: three.
        assert_eq!(
            told_in(&[
                (0, "home", None),
                (60, "abroad", Some("se")),
                (180, "abroad", Some("dk")),
                (300, "abroad", Some("se")),
            ]),
            ["se", "dk", "se"]
        );
        // A border flap within the hour: one. Sweden again after more than an hour: a second.
        assert_eq!(
            told_in(&[
                (0, "abroad", Some("se")),
                (10, "home", None),
                (20, "abroad", Some("se")),
                (30, "home", None),
                (50, "abroad", Some("se")),
                (55, "home", None),
            ]),
            ["se"]
        );
        assert_eq!(
            told_in(&[
                (0, "abroad", Some("se")),
                (10, "home", None),
                (20, "abroad", Some("se")),
                (40, "home", None),
                (70, "abroad", Some("se")),
            ]),
            ["se", "se"]
        );
        // A ferry: Norway -> ship -> Denmark.
        assert_eq!(
            told_in(&[
                (0, "home", None),
                (30, "abroad", Some("ship")),
                (240, "abroad", Some("dk")),
            ]),
            ["ship", "dk"]
        );
        // `unknown` in between (airplane mode, no service) changes nothing.
        assert_eq!(
            told_in(&[
                (0, "abroad", Some("se")),
                (60, "unknown", None),
                (600, "unknown", None),
                (660, "abroad", Some("se")),
            ]),
            ["se"]
        );
        // Home, then Sweden: a notice; home never tells.
        assert_eq!(
            told_in(&[
                (0, "home", None),
                (5, "home", None),
                (90, "abroad", Some("se"))
            ]),
            ["se"]
        );
        // No readable country: the plain notice only when no place is open; a country after it is told.
        assert_eq!(
            told_in(&[
                (0, "abroad", None),
                (10, "abroad", None),
                (20, "abroad", Some("se")),
                (30, "abroad", None),
            ]),
            ["plain", "se"]
        );
        // A 901 network is a place of its own mid-trip too, and the report's place reads it first.
        assert_eq!(
            told_in(&[(0, "abroad", Some("dk")), (120, "abroad", Some("ship"))]),
            ["dk", "ship"]
        );
        let ferry = roaming(Some("dk"), Some("901012"), true);
        assert_eq!(notice_place(&ferry).as_deref(), Some("ship"));
        assert_eq!(
            notice_place(&roaming(Some("se"), Some("24001"), true)).as_deref(),
            Some("se")
        );
        assert_eq!(notice_place(&roaming(None, None, true)), None);
    }

    #[test]
    fn every_named_country_has_its_phrase_in_both_languages() {
        for cc in NAMED {
            for lang in [Lang::En, Lang::Nb] {
                let key = format!("data_roaming.place.{cc}");
                let words = t(lang, &key);
                assert!(
                    words != key && !words.is_empty(),
                    "{key} missing in {lang:?}"
                );
            }
        }
        assert_eq!(NAMED.len(), 32);
    }

    /// Code QA L6: "unlocked for setup" only while a pause runs; otherwise "not in place yet" - for the roaming lock
    /// and the settings lock alike.
    #[test]
    fn unlocked_without_a_pause_is_pending() {
        let policy = DevicePolicy {
            abroad: "off".into(),
            mobile_settings_locked: true,
            ..Default::default()
        };
        let mut status = DeviceStatus {
            data_roaming_json: Some(
                r#"{"locked":false,"roaming":false,"data_active":false}"#.into(),
            ),
            mobile_data_json: Some(
                r#"{"enabled":true,"sink":"off","settings_locked":false}"#.into(),
            ),
            ..Default::default()
        };
        let keys = |card: &Card| {
            (
                card.abroad_lines.iter().map(|l| l.key).collect::<Vec<_>>(),
                card.lock_lines.iter().map(|l| l.key).collect::<Vec<_>>(),
            )
        };
        assert_eq!(
            keys(&card(Lang::En, "Emma", &policy, Some(&status))),
            (vec!["pending"], vec!["pending"])
        );
        // Design 113: the pause has no switch line of its own; Av unlocked by it says nothing.
        status.restrictions_paused = true;
        assert_eq!(
            keys(&card(Lang::En, "Emma", &policy, Some(&status))),
            (vec![], vec!["lifted"])
        );
    }

    /// The summary's header says what the Mobilnett page says (2026-11-19's emulator run, finding 1; code QA M1 and L1
    /// of `server-small`): a ship network Android doesn't count as roaming, covered by the sink under Bare Vibb or Av,
    /// is worded as a flagged one - "Roamer på et skips- eller flynett", orange - on the header and the page alike; the
    /// orange "the lock doesn't cover it" is På's, and comes back under Bare Vibb or Av when the report's sink is down
    /// (a pause); a flagged ship network is roaming under every choice.
    #[test]
    fn the_header_takes_the_pages_ship_rule() {
        let status = |roaming: &str, sink: Option<&str>| DeviceStatus {
            data_roaming_json: Some(roaming.into()),
            mobile_data_json: sink.map(|sink| {
                format!(r#"{{"enabled":true,"sink":"{sink}","settings_locked":true}}"#)
            }),
            ..Default::default()
        };
        let choice = |abroad: &str| DevicePolicy {
            abroad: abroad.into(),
            ..Default::default()
        };
        let ship = ("Roamer på et skips- eller flynett".to_string(), true);
        let ship_home = (
            "På et skips- eller flynett som ikke regnes som roaming: låsen dekker det ikke"
                .to_string(),
            true,
        );
        let unflagged_json = r#"{"locked":false,"roaming":false,"country":null,"network":"90112","enabled":true,"data_active":false,"where":"abroad"}"#;
        // (choice, the report's sink, the header, the page's roaming line, the page's ship-home line)
        let rows = [
            ("vibb", Some("abroad"), Some(ship.clone()), true, false),
            ("off", Some("abroad"), Some(ship.clone()), true, false),
            ("vibb", None, Some(ship.clone()), true, false),
            // A pause lifts the sink: not covered.
            ("vibb", Some("off"), Some(ship_home.clone()), false, true),
            ("off", Some("off"), Some(ship_home.clone()), false, true),
            ("home", Some("off"), Some(ship_home.clone()), false, true),
            ("home", None, Some(ship_home.clone()), false, true),
        ];
        for (abroad, sink, header, roaming_line, ship_home_line) in rows {
            let latest = status(unflagged_json, sink);
            let row = format!("{abroad} {sink:?}");
            assert_eq!(
                summary_roaming(Lang::Nb, &latest, &choice(abroad)),
                header,
                "{row}"
            );
            let lines = card(Lang::Nb, "Emma", &choice(abroad), Some(&latest)).abroad_lines;
            let roaming = lines.iter().find(|l| l.key == "roaming");
            assert_eq!(roaming.is_some(), roaming_line, "{row}: {lines:?}");
            if let Some(line) = roaming {
                assert_eq!((line.title.clone(), line.warn), ship, "{row}");
            }
            assert_eq!(
                lines.iter().any(|l| l.key == "ship-home"),
                ship_home_line,
                "{row}: {lines:?}"
            );
        }
        let flagged = status(
            r#"{"locked":false,"roaming":true,"country":null,"network":"90112","enabled":true,"data_active":true,"where":"abroad"}"#,
            Some("abroad"),
        );
        assert_eq!(
            summary_roaming(Lang::Nb, &flagged, &choice("vibb")),
            Some(ship)
        );
    }

    /// Design 113 (QA M1): På, at home on another network of the home country, Android's roaming switch off - the
    /// orange switch line (its fix behind the ⓘ) and the grey national line both show; abroad the switch line names the
    /// place and the place line goes. Under Av with the lock in place, nothing about the switch.
    #[test]
    fn the_switch_line_shows_only_when_off_and_keeps_the_national_line() {
        let policy = DevicePolicy {
            abroad: "home".into(),
            ..Default::default()
        };
        let status = |roaming: &str| DeviceStatus {
            data_roaming_json: Some(roaming.into()),
            mobile_data_json: Some(
                r#"{"enabled":true,"sink":"off","settings_locked":true}"#.into(),
            ),
            ..Default::default()
        };
        let lines = |policy: &DevicePolicy, roaming: &str| {
            card(Lang::Nb, "Emma", policy, Some(&status(roaming)))
                .abroad_lines
                .into_iter()
                .map(|l| (l.key, l.title, l.sub.is_some(), l.warn))
                .collect::<Vec<_>>()
        };
        let national = r#"{"locked":false,"roaming":true,"country":"no","network":"24201","enabled":false,"data_active":false,"where":"home"}"#;
        assert_eq!(
            lines(&policy, national),
            [
                (
                    "switch",
                    "Androids roamingbryter er av".to_string(),
                    true,
                    true
                ),
                (
                    "roaming",
                    "Bruker et annet nett i hjemlandet".to_string(),
                    false,
                    false
                ),
            ]
        );
        let abroad = r#"{"locked":false,"roaming":true,"country":"se","network":"24001","enabled":false,"data_active":false,"where":"abroad"}"#;
        assert_eq!(
            lines(&policy, abroad),
            [(
                "switch",
                "Roamer i Sverige – Androids roamingbryter er av".to_string(),
                true,
                true
            )]
        );
        // Abroad on a network Android doesn't flag as roaming (2026-11-19's emulator run, finding 2): the switch stops
        // nothing and the phone has data - the plain switch line, never "no mobile data now", and the place on its own
        // line. A roaming session still up (`data_active`) has data too.
        let policy_vibb = DevicePolicy {
            abroad: "vibb".into(),
            ..Default::default()
        };
        let unflagged = r#"{"locked":false,"roaming":false,"country":"se","network":"24001","enabled":false,"data_active":false,"where":"abroad"}"#;
        let expected = [
            (
                "switch",
                "Androids roamingbryter er av".to_string(),
                true,
                true,
            ),
            ("roaming", "Roamer i Sverige".to_string(), false, false),
        ];
        assert_eq!(lines(&policy_vibb, unflagged), expected);
        // Code QA L2: its ⓘ says data works now.
        let sub = card(Lang::Nb, "Emma", &policy_vibb, Some(&status(unflagged)))
            .abroad_lines
            .into_iter()
            .find(|l| l.key == "switch")
            .and_then(|l| l.sub)
            .unwrap();
        assert!(sub.starts_with("Mobildata virker nå"), "{sub}");
        let session = r#"{"locked":false,"roaming":true,"country":"se","network":"24001","enabled":false,"data_active":true,"where":"abroad"}"#;
        assert_eq!(lines(&policy_vibb, session), expected);
        // The switch on, or Av with the lock in place: nothing about it.
        let on =
            r#"{"locked":false,"roaming":false,"enabled":true,"data_active":false,"where":"home"}"#;
        assert!(lines(&policy, on).is_empty());
        let off = DevicePolicy {
            abroad: "off".into(),
            ..Default::default()
        };
        let locked =
            r#"{"locked":true,"roaming":false,"enabled":false,"data_active":false,"where":"home"}"#;
        assert!(lines(&off, locked).is_empty());
    }
}
