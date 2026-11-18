//! Design 99: Snarveier, the kid's quick panel in the shade. The report's `shortcuts` cleaned ([clean]), the items'
//! table ([ITEMS]) and the capability. The shared contract is `testdata/shortcuts.json` (byte-identical in the
//! launcher's test resources): `capability`, `bit`, `max_items`, `items` (this table), `report`/`report_hidden` and the
//! `clean` rows.
//!
//! The pages' parts (§5.2, §5.3): the stored report read back ([report]), the Home Screen row's sub-line ([home_sub])
//! and About This Phone's value ([about_value]). The panel's own rules are the launcher's alone.

use serde::Deserialize;
use serde_json::{Value, json};

use crate::handlers::devices::{
    QUICK_CONTROL_AIRPLANE, QUICK_CONTROL_BLUETOOTH, QUICK_CONTROL_BRIGHTNESS,
    QUICK_CONTROL_MOBILE_DATA, QUICK_CONTROL_SHORTCUTS, QUICK_CONTROL_SOUND, QUICK_CONTROL_WIFI,
};
use crate::i18n::{Lang, t, t_params};
use crate::models::{DevicePolicy, DeviceStatus};

/// The capability a launcher claims once it reports `shortcuts` and posts the panel (bit 128; the shared contract's
/// `capability`).
#[cfg(test)]
pub const CAPABILITY: &str = "shortcuts_v1";

/// The panel holds at most this many items (D1: four in one row).
pub const MAX_ITEMS: usize = 4;

/// One item the kid can pick: its key, the quick-controls bit that offers it (`None`: always offered) and its full
/// names, as the PWA and the picker show them.
pub struct Item {
    pub key: &'static str,
    /// The gating is the launcher's alone (§4.1); the server keeps the bit for the contract's table test.
    #[cfg_attr(not(test), allow(dead_code))]
    pub bit: Option<i64>,
    pub nb: &'static str,
    pub en: &'static str,
}

impl Item {
    /// The item's name in `lang`.
    pub fn name(&self, lang: Lang) -> &'static str {
        match lang {
            Lang::Nb => self.nb,
            Lang::En => self.en,
        }
    }
}

/// Every item, in the default order (D4); the contract's `items`.
pub const ITEMS: &[Item] = &[
    Item {
        key: "torch",
        bit: None,
        nb: "Lommelykt",
        en: "Flashlight",
    },
    Item {
        key: "sound",
        bit: Some(QUICK_CONTROL_SOUND),
        nb: "Lyd",
        en: "Sound",
    },
    Item {
        key: "airplane",
        bit: Some(QUICK_CONTROL_AIRPLANE),
        nb: "Flymodus",
        en: "Airplane mode",
    },
    Item {
        key: "wifi",
        bit: Some(QUICK_CONTROL_WIFI),
        nb: "Wi-Fi",
        en: "Wi-Fi",
    },
    Item {
        key: "bluetooth",
        bit: Some(QUICK_CONTROL_BLUETOOTH),
        nb: "Bluetooth",
        en: "Bluetooth",
    },
    Item {
        key: "mobile_data",
        bit: Some(QUICK_CONTROL_MOBILE_DATA),
        nb: "Mobildata",
        en: "Mobile data",
    },
    Item {
        key: "brightness",
        bit: Some(QUICK_CONTROL_BRIGHTNESS),
        nb: "Lysstyrke",
        en: "Brightness",
    },
    Item {
        key: "more_time",
        bit: None,
        nb: "Mer tid",
        en: "More time",
    },
    Item {
        key: "music",
        bit: None,
        nb: "Musikk",
        en: "Music",
    },
];

/// The item with `key`.
pub fn item(key: &str) -> Option<&'static Item> {
    ITEMS.iter().find(|item| item.key == key)
}

/// The report's `shortcuts`, re-built (§6.2's `clean`): `shown` and `hidden` booleans or false, `shown` false while
/// `hidden`; `items` only the known keys that are strings, each once, cut to [MAX_ITEMS] (not a list: none); every
/// other key dropped. `None` when it isn't an object.
pub fn clean(value: &Value) -> Option<Value> {
    let object = value.as_object()?;
    let flag = |key: &str| object.get(key).and_then(Value::as_bool).unwrap_or(false);
    let hidden = flag("hidden");
    let mut items: Vec<&str> = Vec::new();
    for key in object
        .get("items")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
    {
        if items.len() < MAX_ITEMS && item(key).is_some() && !items.contains(&key) {
            items.push(key);
        }
    }
    Some(json!({
        "shown": flag("shown") && !hidden,
        "hidden": hidden,
        "items": items,
    }))
}

/// The stored `shortcuts`, as the pages read it (the cleaned shape).
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct Report {
    pub shown: bool,
    pub hidden: bool,
    pub items: Vec<String>,
}

/// The latest report's `shortcuts`, when it has one.
pub fn report(latest: &DeviceStatus) -> Option<Report> {
    latest
        .shortcuts_json
        .as_deref()
        .and_then(|json| serde_json::from_str(json).ok())
}

/// The items' names in `lang`, in the panel's order, joined by ", " (an unknown key is skipped).
fn names(lang: Lang, report: &Report) -> String {
    report
        .items
        .iter()
        .filter_map(|key| item(key))
        .map(|item| item.name(lang))
        .collect::<Vec<_>>()
        .join(", ")
}

/// The Home Screen row's sub-line (§5.2; design 113: quiet values), only when it says something; `name` is the phone's
/// (the kid is named, never given a pronoun): "Skjult av Emma" when the kid hid the panel; else, with bit 128 on, the
/// items the kid chose, or "Ingen" (no items: the kid chose none, or none of the chosen is offered - the report can't
/// tell the two apart, C99-L3). Nothing while the bit is off and the panel isn't hidden.
pub fn home_sub(lang: Lang, name: &str, policy: &DevicePolicy, report: &Report) -> Option<String> {
    if report.hidden {
        return Some(t_params(
            lang,
            "device.home.shortcuts_hidden",
            &[("name", name)],
        ));
    }
    if policy.quick_controls_mask & QUICK_CONTROL_SHORTCUTS == 0 {
        return None;
    }
    let chosen = names(lang, report);
    Some(if chosen.is_empty() {
        t(lang, "device.home.shortcuts_none").to_string()
    } else {
        chosen
    })
}

/// About This Phone's "Snarveier" value (§5.3): "Av" with bit 128 off, "Skjult av Emma" when the kid hid it, else the
/// items' names ("Ingen" without any).
pub fn about_value(lang: Lang, name: &str, policy: &DevicePolicy, report: &Report) -> String {
    if policy.quick_controls_mask & QUICK_CONTROL_SHORTCUTS == 0 {
        return t(lang, "common.off").to_string();
    }
    if report.hidden {
        return t_params(lang, "device.about.shortcuts_hidden", &[("name", name)]);
    }
    let chosen = names(lang, report);
    if chosen.is_empty() {
        t(lang, "device.about.none").to_string()
    } else {
        chosen
    }
}
