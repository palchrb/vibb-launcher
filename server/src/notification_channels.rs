//! Design 107: the Vibb One notification channels the phone has turned off. The report's `notification_channels`
//! cleaned ([clean]) and the channels' table ([CHANNELS]). The shared contract is
//! `testdata/notification_channels.json` (byte-identical in the launcher's test resources): `channels`
//! (this table), `report`/`report_none`/`report_all_off`, the launcher's `read` rows, the `clean` rows and the `pages`
//! rows (About's value and colour, the Attention title).
//!
//! The pages' parts (§5): the stored report read back ([report]), About This Phone › Sound's "Notifications" row
//! ([about]) and the summary's Attention row ([attention], [attention_title]). A channel counts only while its feature
//! is in use by the policy now, and Snarveier's own channel is never named here (its rows say it, design 100 §5).
//!
//! No security event: a notification turned off in the shade neither opens nor weakens the phone (Q5). The table's
//! snake_case keys stay in this file, which writes no event (`handlers::admin`'s label scan reads literals near a
//! writer as event ids).

use serde::Deserialize;
use serde_json::{Value, json};

use crate::i18n::{Lang, t, t_params};
use crate::models::{DevicePolicy, DeviceStatus};

/// What a channel serves: it counts only while the policy has Vibb run that feature (§5.1).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Feature {
    /// `sms_mode == "managed"`: Vibb's Meldinger.
    Sms,
    /// `calls_mode == "managed"`: Vibb is the dialer.
    Calls,
}

impl Feature {
    /// The contract's word.
    #[cfg_attr(not(test), allow(dead_code))]
    pub fn wire(self) -> &'static str {
        match self {
            Feature::Sms => "sms",
            Feature::Calls => "calls",
        }
    }
}

/// One of Vibb One's notification channels, as the phone names it.
pub struct Channel {
    /// The report's key: the channel id without `launcher:`.
    pub key: &'static str,
    /// The phone's channel id; the launcher's table reads it (Snarveier's `.N` ids as this one).
    #[cfg_attr(not(test), allow(dead_code))]
    pub id: &'static str,
    /// The phone's own names (the launcher's strings), so the parent can tell the kid exactly what to turn on.
    pub nb: &'static str,
    pub en: &'static str,
    /// `None`: always counts.
    pub feature: Option<Feature>,
    /// About's value is orange while it is off and counts.
    pub orange: bool,
    /// It raised the Attention row while it is off and counts (Q2); design 114 folds that row (see [Attention]).
    #[cfg_attr(not(test), allow(dead_code))]
    pub attention: bool,
    /// The ⓘ's sentence for it (`{name}`), if losing it has an effect worth a sentence (§5.4).
    pub effect: Option<&'static str>,
}

impl Channel {
    /// The channel's name in `lang`.
    pub fn name(&self, lang: Lang) -> &'static str {
        match lang {
            Lang::Nb => self.nb,
            Lang::En => self.en,
        }
    }
}

/// The key of Snarveier's channel: reported, never named on these pages.
const SHORTCUTS: &str = "shortcuts";

/// Every channel, in the display order (§7.1); the contract's `channels`.
pub const CHANNELS: &[Channel] = &[
    Channel {
        key: "messages",
        id: "launcher:messages_v2",
        nb: "Meldinger",
        en: "Messages",
        feature: Some(Feature::Sms),
        orange: true,
        attention: true,
        effect: Some("device.about.notifications.effect.messages"),
    },
    Channel {
        key: "missed_calls",
        id: "launcher:missed_calls",
        nb: "Tapte anrop",
        en: "Missed calls",
        feature: Some(Feature::Calls),
        orange: true,
        attention: true,
        effect: Some("device.about.notifications.effect.missed_calls"),
    },
    Channel {
        key: "calls",
        id: "launcher:calls",
        nb: "Samtaler",
        en: "Calls",
        feature: Some(Feature::Calls),
        orange: true,
        attention: false,
        effect: Some("device.about.notifications.effect.calls"),
    },
    Channel {
        key: "messages_silent",
        id: "launcher:messages_silent",
        nb: "Meldinger (stille)",
        en: "Messages (silent)",
        feature: Some(Feature::Sms),
        orange: false,
        attention: false,
        effect: None,
    },
    Channel {
        key: "messages_reactions",
        id: "launcher:messages_reactions",
        nb: "Reaksjoner",
        en: "Reactions",
        feature: Some(Feature::Sms),
        orange: false,
        attention: false,
        effect: None,
    },
    Channel {
        key: "calls_silent",
        id: "launcher:calls_silent",
        nb: "Samtaler (samtaleskjermen er åpen)",
        en: "Calls (call screen open)",
        feature: Some(Feature::Calls),
        orange: false,
        attention: false,
        effect: None,
    },
    Channel {
        key: "command_listener",
        id: "launcher:command_listener",
        nb: "Vibb One",
        en: "Vibb One",
        feature: None,
        orange: false,
        attention: false,
        effect: Some("device.about.notifications.effect.command_listener"),
    },
    Channel {
        key: "ring",
        id: "launcher:ring",
        nb: "Finn enheten – ringing",
        en: "Find My Device ring",
        feature: None,
        orange: false,
        attention: false,
        effect: Some("device.about.notifications.effect.ring"),
    },
    Channel {
        key: "window",
        id: "launcher:window",
        nb: "Åpnet med foreldrekoden",
        en: "Opened with the parent code",
        feature: None,
        orange: false,
        attention: false,
        effect: Some("device.about.notifications.effect.window"),
    },
    Channel {
        key: "install_mode",
        id: "launcher:install_mode",
        nb: "Play-installasjonsmodus",
        en: "Play install mode",
        feature: None,
        orange: false,
        attention: false,
        effect: None,
    },
    Channel {
        key: "app_install",
        id: "launcher:app_install",
        nb: "Installering og oppdatering av apper",
        en: "App installs and updates",
        feature: None,
        orange: false,
        attention: false,
        effect: None,
    },
    Channel {
        key: "vpn_filter",
        id: "launcher:vpn_filter",
        nb: "Innholdsfilter",
        en: "Content filter",
        feature: None,
        orange: false,
        attention: false,
        effect: None,
    },
    Channel {
        key: "setup",
        id: "launcher:setup",
        nb: "Oppsett",
        en: "Setup",
        feature: None,
        orange: false,
        attention: false,
        effect: None,
    },
    Channel {
        key: SHORTCUTS,
        id: "launcher:shortcuts",
        nb: "Snarveier",
        en: "Shortcuts",
        feature: None,
        orange: false,
        attention: false,
        effect: None,
    },
];

/// The channel with `key`.
#[cfg_attr(not(test), allow(dead_code))]
pub fn channel(key: &str) -> Option<&'static Channel> {
    CHANNELS.iter().find(|channel| channel.key == key)
}

/// The report's `notification_channels`, re-built (§4): `all_off` a bool or false; `blocked` only the table's keys
/// that are strings, each once, in the table's order (a missing or non-array `blocked` is `[]`); every other key
/// dropped. `None` when it isn't an object (stored `NULL`).
pub fn clean(value: &Value) -> Option<Value> {
    let object = value.as_object()?;
    let all_off = object
        .get("all_off")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    let sent: Vec<&str> = object
        .get("blocked")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
        .collect();
    let blocked: Vec<&str> = CHANNELS
        .iter()
        .map(|channel| channel.key)
        .filter(|key| sent.contains(key))
        .collect();
    Some(json!({"all_off": all_off, "blocked": blocked}))
}

/// The stored `notification_channels`, as the pages read it (the cleaned shape).
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct Report {
    pub all_off: bool,
    pub blocked: Vec<String>,
}

/// The latest report's `notification_channels`, only when it sent an object (§4). Nothing stored: the phone's read
/// failed - no row, never a false "All on".
pub fn report(latest: &DeviceStatus) -> Option<Report> {
    latest
        .notification_channels_json
        .as_deref()
        .and_then(|json| serde_json::from_str(json).ok())
}

/// Whether `feature` is in use by the policy now.
fn in_use(policy: &DevicePolicy, feature: Option<Feature>) -> bool {
    match feature {
        None => true,
        Some(Feature::Sms) => policy.sms_mode == "managed",
        Some(Feature::Calls) => policy.calls_mode == "managed",
    }
}

/// The blocked channels that count (§5.1), in the table's order: their feature in use, never Snarveier.
pub fn counted(policy: &DevicePolicy, report: &Report) -> Vec<&'static Channel> {
    CHANNELS
        .iter()
        .filter(|channel| channel.key != SHORTCUTS)
        .filter(|channel| report.blocked.iter().any(|key| key == channel.key))
        .filter(|channel| in_use(policy, channel.feature))
        .collect()
}

/// The language the names are in (§5.1, L4): the phone's when the policy sets it, else the page's.
fn names_lang(policy: &DevicePolicy, page: Lang) -> Lang {
    Lang::from_code(&policy.launcher_language).unwrap_or(page)
}

/// A channel's quoted name: in the phone's language, in the page's quotes.
fn quoted(page: Lang, names: Lang, channel: &Channel) -> String {
    t_params(
        page,
        "device.about.notifications.quoted",
        &[("name", channel.name(names))],
    )
}

/// About › Sound's "Notifications" row: its value, its class and its ⓘ.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AboutRow {
    pub value: String,
    pub orange: bool,
    /// Only while something is off: the lead, a sentence for each named channel with an effect, the fix.
    pub detail: Option<String>,
}

/// §5.2: "All on", "All off", or "Off: …" (up to four names in full; from five, three and "and N more"). Orange while
/// the app-wide switch is off or an orange channel that counts is off.
pub fn about(lang: Lang, policy: &DevicePolicy, report: &Report) -> AboutRow {
    let names = names_lang(policy, lang);
    let off = counted(policy, report);
    if !report.all_off && off.is_empty() {
        return AboutRow {
            value: t(lang, "device.about.notifications.all_on").to_string(),
            orange: false,
            detail: None,
        };
    }
    let value = if report.all_off {
        t(lang, "device.about.notifications.all_off").to_string()
    } else {
        let all: Vec<String> = off
            .iter()
            .map(|channel| quoted(lang, names, channel))
            .collect();
        let shown = if all.len() <= 4 {
            all.join(", ")
        } else {
            t_params(
                lang,
                "device.about.notifications.more",
                &[
                    ("names", &all[..3].join(", ")),
                    ("count", &(all.len() - 3).to_string()),
                ],
            )
        };
        t_params(lang, "device.about.notifications.off", &[("names", &shown)])
    };
    let mut sentences = vec![
        t(
            lang,
            if report.all_off {
                "device.about.notifications.lead_all_off"
            } else {
                "device.about.notifications.lead"
            },
        )
        .to_string(),
    ];
    for channel in &off {
        if let Some(effect) = channel.effect {
            sentences.push(t_params(
                lang,
                effect,
                &[("name", &quoted(lang, names, channel))],
            ));
        }
    }
    sentences.push(t(lang, "device.about.notifications.fix").to_string());
    AboutRow {
        value,
        orange: report.all_off || off.iter().any(|channel| channel.orange),
        detail: Some(sentences.join(" ")),
    }
}

/// The effect sentences of the counted channels that are off (§5.2's ⓘ without its lead and fix): design 114's
/// Device › Oppsett says them on the `vibb_notifications` row (code QA L3). Empty when nothing counted is off.
pub fn effects(lang: Lang, policy: &DevicePolicy, report: &Report) -> Vec<String> {
    let names = names_lang(policy, lang);
    counted(policy, report)
        .iter()
        .filter_map(|channel| {
            channel
                .effect
                .map(|effect| t_params(lang, effect, &[("name", &quoted(lang, names, channel))]))
        })
        .collect()
}

/// What the summary's Attention row said (§5.3), typed: the app-wide switch, or the attention channels that count.
/// Design 114 folds the row into the setup row (`setup_status`'s `vibb_notifications`, by About's orange); the rule
/// stays for the contract's `pages` rows' `attention` column, which the tests run.
#[cfg_attr(not(test), allow(dead_code))]
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Attention {
    pub all_off: bool,
    pub keys: Vec<&'static str>,
    /// The phone's language when the policy sets it (the names' language, §5.1); `None`: the page's.
    pub names: Option<Lang>,
}

/// The Attention row's facts: a blocked Meldinger or Tapte anrop while its feature counts, or the app-wide switch off
/// while SMS or calls are managed. Never Samtaler, the minor channels or Snarveier.
#[cfg_attr(not(test), allow(dead_code))]
pub fn attention(policy: &DevicePolicy, report: &Report) -> Option<Attention> {
    let managed = policy.sms_mode == "managed" || policy.calls_mode == "managed";
    let all_off = report.all_off && managed;
    let keys: Vec<&'static str> = counted(policy, report)
        .into_iter()
        .filter(|channel| channel.attention)
        .map(|channel| channel.key)
        .collect();
    (all_off || !keys.is_empty()).then(|| Attention {
        all_off,
        keys,
        names: Lang::from_code(&policy.launcher_language),
    })
}

/// The Attention row's title in `lang` (at most 8 words; the names unquoted).
#[cfg_attr(not(test), allow(dead_code))]
pub fn attention_title(lang: Lang, attention: &Attention) -> String {
    if attention.all_off {
        return t(lang, "device.attention.notifications_all_off").to_string();
    }
    let names = attention.names.unwrap_or(lang);
    let list: Vec<&str> = attention
        .keys
        .iter()
        .filter_map(|key| channel(key))
        .map(|channel| channel.name(names))
        .collect();
    t_params(
        lang,
        "device.attention.notifications_off",
        &[("names", &list.join(", "))],
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    const CONTRACT: &str = include_str!("../testdata/notification_channels.json");

    fn contract() -> Value {
        serde_json::from_str(CONTRACT).unwrap()
    }

    /// Design 107's Attention rule over every `pages` row, though design 114 shows it as a setup step: the title, or
    /// none (the page in English).
    #[test]
    fn the_pages_rows_attention() {
        for row in contract()["pages"].as_array().unwrap() {
            let policy = DevicePolicy {
                sms_mode: row["sms_mode"].as_str().unwrap().to_string(),
                calls_mode: row["calls_mode"].as_str().unwrap().to_string(),
                launcher_language: row["language"].as_str().unwrap().to_string(),
                ..Default::default()
            };
            let report: Report = serde_json::from_value(row["report"].clone()).unwrap();
            let title = attention(&policy, &report).map(|a| attention_title(Lang::En, &a));
            assert_eq!(
                title.as_deref(),
                row["attention"].as_str(),
                "{}",
                row["case"]
            );
        }
    }

    #[test]
    fn the_clean_rows() {
        for row in contract()["clean"].as_array().unwrap() {
            assert_eq!(
                clean(&row["in"]).unwrap_or(Value::Null),
                row["out"],
                "{}",
                row["case"]
            );
        }
    }

    /// The table is the contract's `channels`, in its order; every effect key is in both catalogs.
    #[test]
    fn the_table_is_the_contracts() {
        let channels: Vec<Value> = CHANNELS
            .iter()
            .map(|c| {
                json!({
                    "key": c.key,
                    "id": c.id,
                    "nb": c.nb,
                    "en": c.en,
                    "feature": c.feature.map(Feature::wire),
                    "orange": c.orange,
                    "attention": c.attention,
                })
            })
            .collect();
        assert_eq!(Value::from(channels), contract()["channels"]);
        for channel in CHANNELS {
            // Meldinger's id is versioned: Android can't raise a channel's importance, so High got a new id.
            let id = if channel.key == "messages" {
                "launcher:messages_v2".to_string()
            } else {
                format!("launcher:{}", channel.key)
            };
            assert_eq!(channel.id, id);
            if let Some(effect) = channel.effect {
                for lang in Lang::ALL {
                    assert!(crate::i18n::has(lang, effect), "{effect}");
                }
            }
        }
    }
}
