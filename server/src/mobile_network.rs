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

/// The launcher capability for data roaming (design 94 §4).
pub const ROAMING_CAPABILITY: &str = "data_roaming_v1";
/// The launcher capability for Bare Vibb, the settings lock and `mobile_data` (design 95 rev. 3).
pub const MOBILE_CAPABILITY: &str = "mobile_data_v1";

/// The choices the page offers (design 95 rev. 3: "Tillat i EU/EØS" is out of this round; the column allows `eea`).
pub const ROAMING_CHOICES: &[&str] = &["off", "phone"];
pub const APPS_CHOICES: &[&str] = &["on", "vibb"];

/// The stored roaming choice as served: `phone` and `eea` as they are, anything else (an empty default) `off`.
pub fn roaming_choice(stored: &str) -> &'static str {
    match stored {
        "phone" => "phone",
        "eea" => "eea",
        _ => "off",
    }
}

/// The stored "Mobildata for apper" as served: `vibb` or `on`.
pub fn apps_choice(stored: &str) -> &'static str {
    if stored == "vibb" { "vibb" } else { "on" }
}

/// Design 94 §5: the report's `data_roaming`, re-serialised field by field. A booleans that isn't one reads as its safe
/// value (`false`; `null` for `enabled`), `country` must be `[a-z]{2}`, `network` `[0-9]{5,6}`, `modems` 1..=8; a value
/// that isn't an object is `None` (stored `NULL`), never a refused report.
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
        })
        .to_string(),
    )
}

/// Design 95 rev. 3: the report's `mobile_data`, re-serialised: `enabled` a boolean or `null`, `sink` one of the known
/// words (else `off`), `settings_locked` a boolean (else `false`).
pub fn clean_mobile(value: &Value) -> Option<String> {
    let object = value.as_object()?;
    let sink = object
        .get("sink")
        .and_then(Value::as_str)
        .filter(|s| matches!(*s, "off" | "parent" | "roaming"))
        .unwrap_or("off");
    Some(
        json!({
            "enabled": object.get("enabled").and_then(Value::as_bool),
            "sink": sink,
            "settings_locked": object.get("settings_locked").and_then(Value::as_bool).unwrap_or(false),
        })
        .to_string(),
    )
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
}

/// A stored `mobile_data_json`.
#[derive(Debug, Deserialize, Clone, PartialEq, Eq)]
pub struct MobileReport {
    pub enabled: Option<bool>,
    pub sink: String,
    pub settings_locked: bool,
}

fn has(latest: &DeviceStatus, capability: &str) -> bool {
    crate::handlers::device_pages::has_capability(Some(latest), capability)
}

/// The latest report's `data_roaming`, from a launcher that claims [ROAMING_CAPABILITY].
pub fn roaming_report(latest: &DeviceStatus) -> Option<RoamingReport> {
    if !has(latest, ROAMING_CAPABILITY) {
        return None;
    }
    serde_json::from_str(latest.data_roaming_json.as_deref()?).ok()
}

/// The latest report's `mobile_data`, from a launcher that claims [MOBILE_CAPABILITY].
pub fn mobile_report(latest: &DeviceStatus) -> Option<MobileReport> {
    if !has(latest, MOBILE_CAPABILITY) {
        return None;
    }
    serde_json::from_str(latest.mobile_data_json.as_deref()?).ok()
}

/// The countries named in full (design 94 §6.2): the EEA (EU 27, IS, LI, NO) and then GB and CH, which are outside it.
/// Each has `data_roaming.in.<cc>` in both catalogs - the whole phrase, since nb's preposition and en's "the" depend on
/// the country.
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

/// Design 94 §6.2: where the phone roams, and whether it is a warning (orange). The 901 rows decide first - near land
/// the country can be a coastal one. `None` when the phone doesn't roam and isn't on a ship or plane network.
pub fn roaming_words(lang: Lang, report: &RoamingReport) -> Option<(String, bool)> {
    if ship_network(report) {
        let key = if report.roaming {
            "data_roaming.ship"
        } else {
            "data_roaming.ship_home"
        };
        return Some((t(lang, key).to_string(), true));
    }
    if !report.roaming {
        return None;
    }
    Some(match report.country.as_deref() {
        Some(cc) => match NAMED.iter().position(|named| *named == cc) {
            Some(index) => (
                t(lang, &format!("data_roaming.in.{cc}")).to_string(),
                index >= EEA_COUNT,
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

/// The summary header's roaming part (design 94 §6.2), from the latest report.
pub fn summary_roaming(lang: Lang, latest: &DeviceStatus) -> Option<(String, bool)> {
    roaming_words(lang, &roaming_report(latest)?)
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
}

/// Design 94 §6.1's warnings from the report: a roaming session that outlived the lock (with a launcher that sinks it,
/// only Vibb uses it), a SIM that still reads roaming on under the lock, a ship network the lock doesn't cover.
fn roaming_warnings(
    lang: Lang,
    report: &RoamingReport,
    mobile: Option<&MobileReport>,
    airplane_locked: bool,
) -> Vec<Line> {
    let mut lines = Vec::new();
    if report.locked && report.data_active {
        let sinking = mobile.is_some_and(|m| m.sink == "roaming");
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
    if ship_network(report) && !report.roaming {
        lines.push(Line {
            key: "ship-home",
            title: t(lang, "data_roaming.ship_home").to_string(),
            sub: None,
            warn: true,
        });
    }
    lines
}

/// The summary's lines (design 95 rev. 3, the approved mockup): only when they say something - mobile data off on the
/// phone, only Vibb on cellular now, and the roaming warnings. Each leads to the Mobilnett page.
pub fn summary_lines(
    lang: Lang,
    policy: &DevicePolicy,
    latest: Option<&DeviceStatus>,
) -> Vec<Line> {
    let Some(latest) = latest else {
        return Vec::new();
    };
    let mobile = mobile_report(latest);
    let mut lines = Vec::new();
    if let Some(m) = &mobile {
        if m.enabled == Some(false) {
            lines.push(Line::new(
                lang,
                "data-off",
                "mobile.state.data_off",
                Some("mobile.state.data_off_sub"),
                false,
            ));
        } else if m.sink == "parent" {
            lines.push(Line::new(
                lang,
                "only-vibb",
                "mobile.state.only_vibb",
                Some("mobile.state.only_vibb_sub"),
                false,
            ));
        }
    }
    if let Some(report) = roaming_report(latest) {
        lines.extend(roaming_warnings(
            lang,
            &report,
            mobile.as_ref(),
            policy.hardening.disallow_airplane_mode,
        ));
    }
    lines
}

/// The phone page's Mobilnett row value (design 95 rev. 3): the choices away from the defaults and mobile data off on
/// the phone, joined with " · "; `None` when nothing differs.
pub fn row_value(
    lang: Lang,
    policy: &DevicePolicy,
    latest: Option<&DeviceStatus>,
) -> Option<String> {
    let mut parts: Vec<&str> = Vec::new();
    if apps_choice(&policy.apps_mobile) == "vibb" {
        parts.push(t(lang, "mobile.value.vibb"));
    }
    if roaming_choice(&policy.data_roaming) == "phone" {
        parts.push(t(lang, "mobile.value.roaming_phone"));
    }
    if latest
        .and_then(mobile_report)
        .is_some_and(|m| m.enabled == Some(false))
    {
        parts.push(t(lang, "mobile.value.data_off"));
    }
    (!parts.is_empty()).then(|| parts.join(" · "))
}

/// The Mobilnett page's groups (`device_mobile.html`).
pub struct Card {
    pub apps_mobile: &'static str,
    pub data_roaming: &'static str,
    pub settings_locked: bool,
    /// The latest report claims each capability; the groups' controls wait for it.
    pub mobile_capable: bool,
    pub roaming_capable: bool,
    /// The phone has reported at all: without a report nothing is said about its Vibb One.
    pub reported: bool,
    /// Under "Mobildata", "Dataroaming" and the lock: the phone's state, worded.
    pub data_lines: Vec<Line>,
    pub roaming_lines: Vec<Line>,
    pub lock_lines: Vec<Line>,
}

/// The page's state from the stored choices and the latest report (design 94 §6.1's table, design 95 rev. 3's lines).
pub fn card(lang: Lang, policy: &DevicePolicy, latest: Option<&DeviceStatus>) -> Card {
    let apps_mobile = apps_choice(&policy.apps_mobile);
    let data_roaming = roaming_choice(&policy.data_roaming);
    let mobile = latest.and_then(mobile_report);
    let roaming = latest.and_then(roaming_report);
    let paused = latest.is_some_and(|s| s.paused_at(chrono::Utc::now().timestamp_millis()));
    let mobile_capable = latest.is_some_and(|s| has(s, MOBILE_CAPABILITY));
    let roaming_capable = latest.is_some_and(|s| has(s, ROAMING_CAPABILITY));
    let reported = latest.is_some();
    let update = |lang| {
        Line::new(
            lang,
            "needs-update",
            "mobile.state.needs_update",
            None,
            false,
        )
    };

    let mut data_lines = Vec::new();
    if reported && !mobile_capable {
        data_lines.push(update(lang));
    }
    if let Some(m) = &mobile {
        if m.enabled == Some(false) {
            data_lines.push(Line::new(
                lang,
                "data-off",
                "mobile.state.data_off",
                Some("mobile.state.data_off_sub"),
                false,
            ));
        }
        if m.sink == "parent" {
            data_lines.push(Line::new(
                lang,
                "only-vibb",
                "mobile.state.only_vibb",
                Some("mobile.state.only_vibb_sub"),
                false,
            ));
        }
    }
    if apps_mobile == "vibb" && paused && mobile_capable {
        data_lines.push(Line::new(
            lang,
            "lifted",
            "mobile.state.lifted",
            None,
            false,
        ));
    }

    let mut roaming_lines = Vec::new();
    if reported && !roaming_capable {
        roaming_lines.push(update(lang));
    }
    if let Some(report) = &roaming {
        if report.locked {
            roaming_lines.push(Line::new(
                lang,
                "locked",
                "mobile.state.roaming_locked",
                None,
                false,
            ));
        } else if data_roaming == "off" {
            roaming_lines.push(Line::new(
                lang,
                "unlocked",
                "mobile.state.roaming_unlocked",
                None,
                false,
            ));
        } else if let Some(enabled) = report.enabled {
            let key = if enabled {
                "mobile.state.phone_on"
            } else {
                "mobile.state.phone_off"
            };
            roaming_lines.push(Line::new(lang, "phone", key, None, false));
        }
        if let Some((words, warn)) = roaming_words(lang, report).filter(|_| report.roaming) {
            roaming_lines.push(Line {
                key: "roaming",
                title: words,
                sub: None,
                warn,
            });
        }
        roaming_lines.extend(roaming_warnings(
            lang,
            report,
            mobile.as_ref(),
            policy.hardening.disallow_airplane_mode,
        ));
    }

    let mut lock_lines = Vec::new();
    if let Some(m) = &mobile
        && policy.mobile_settings_locked
        && !m.settings_locked
        && paused
    {
        lock_lines.push(Line::new(
            lang,
            "lifted",
            "mobile.state.lifted",
            None,
            false,
        ));
    }

    Card {
        apps_mobile,
        data_roaming,
        settings_locked: policy.mobile_settings_locked,
        mobile_capable,
        roaming_capable,
        reported,
        data_lines,
        roaming_lines,
        lock_lines,
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
    }

    #[test]
    fn every_named_country_has_its_phrase_in_both_languages() {
        for cc in NAMED {
            for lang in [Lang::En, Lang::Nb] {
                let key = format!("data_roaming.in.{cc}");
                let words = t(lang, &key);
                assert!(
                    words != key && !words.is_empty(),
                    "{key} missing in {lang:?}"
                );
            }
        }
        assert_eq!(NAMED.len(), 32);
    }
}
