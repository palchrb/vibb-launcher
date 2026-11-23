//! The apps with notification access on a phone. The report's `notification_listeners` cleaned ([clean]): every app
//! Android lets read notifications besides Vibb One, each with `off` while Vibb has it hidden or suspended. The shared
//! contract is `testdata/notification_listeners.json` (byte-identical in the launcher's test resources): `policy`
//! (Advanced › Android Auto as the policy carries it), `report`/`report_none`, the launcher's `read` rows, the `clean`
//! rows and the `unknown` rows (which listeners About This Phone names in orange).
//!
//! About This Phone's "Notification access" row ([about]): a quiet value when every app that reads is one the parent
//! gave the phone through Vibb (a catalog app ticked for it, or an allowed app), one short orange value naming the
//! others, how to remove the access behind the (i).

use std::collections::{BTreeSet, HashMap};

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use crate::i18n::{Lang, t};
use crate::models::{DevicePolicy, DeviceStatus, InstalledApp};

/// The most listeners kept from one report; Android has a handful.
pub const MAX_LISTENERS: usize = 32;

/// One app with notification access, as stored.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Listener {
    pub package: String,
    /// Vibb has the app hidden or suspended, so it reads nothing.
    #[serde(default)]
    pub off: bool,
}

/// The report's `notification_listeners`, re-built: a list of `{package, off}` with a real package name, each package
/// once (the first), at most [MAX_LISTENERS], `off` a boolean or false, every other key dropped. `None` when it isn't
/// a list.
pub fn clean(value: &Value) -> Option<Value> {
    let rows = value.as_array()?;
    let mut kept: Vec<Value> = Vec::new();
    let mut seen = BTreeSet::new();
    for row in rows {
        let Some(object) = row.as_object() else {
            continue;
        };
        let Some(package) = object.get("package").and_then(Value::as_str) else {
            continue;
        };
        if !crate::handlers::device_api::is_package_name(package) || !seen.insert(package) {
            continue;
        }
        if kept.len() == MAX_LISTENERS {
            break;
        }
        let off = object.get("off").and_then(Value::as_bool).unwrap_or(false);
        kept.push(json!({"package": package, "off": off}));
    }
    Some(Value::from(kept))
}

/// The latest report's listeners, when it sent them.
pub fn report(latest: &DeviceStatus) -> Option<Vec<Listener>> {
    latest
        .notification_listeners_json
        .as_deref()
        .and_then(|json| serde_json::from_str(json).ok())
}

/// The listeners that read and that the parent didn't give the phone: not `off`, not in `known`; in the report's order.
pub fn unknown<'a>(listeners: &'a [Listener], known: &BTreeSet<String>) -> Vec<&'a str> {
    listeners
        .iter()
        .filter(|l| !l.off && !known.contains(&l.package))
        .map(|l| l.package.as_str())
        .collect()
}

/// The packages the parent gave this phone through Vibb: the catalog apps ticked for it and the allowed apps.
pub async fn known_packages(
    db: &sqlx::SqlitePool,
    device_id: i64,
    policy: &DevicePolicy,
) -> BTreeSet<String> {
    let mut known: BTreeSet<String> = policy
        .allowlist_json
        .as_deref()
        .and_then(|json| serde_json::from_str::<Vec<String>>(json).ok())
        .unwrap_or_default()
        .into_iter()
        .collect();
    let catalog: Vec<String> = sqlx::query_scalar(
        "SELECT ta.package_name FROM tracked_apps ta \
         JOIN device_tracked_apps dta ON dta.tracked_app_id = ta.id \
         WHERE dta.device_id = ? AND ta.package_name IS NOT NULL",
    )
    .bind(device_id)
    .fetch_all(db)
    .await
    .unwrap_or_else(|err| {
        tracing::error!(device_id, %err, "can't read the phone's catalog apps");
        Vec::new()
    });
    known.extend(catalog);
    known
}

/// About's row: its value, whether it is orange, and the (i)'s sentence.
pub struct AboutRow {
    pub value: String,
    pub orange: bool,
    pub detail: Option<String>,
}

/// About's "Notification access" row from the phone's listeners: the unknown ones by name in orange, else the ones
/// that read (quiet), else "Only Vibb One". An app's name comes from the report's installed apps, else its package.
pub fn about(
    lang: Lang,
    listeners: &[Listener],
    known: &BTreeSet<String>,
    installed: &[InstalledApp],
) -> AboutRow {
    let labels: HashMap<&str, &str> = installed
        .iter()
        .map(|app| (app.package_name.as_str(), app.label.as_str()))
        .collect();
    let name = |package: &str| -> String {
        labels
            .get(package)
            .filter(|label| !label.is_empty())
            .map(|label| label.to_string())
            .unwrap_or_else(|| package.to_string())
    };
    let strangers = unknown(listeners, known);
    if !strangers.is_empty() {
        let names: Vec<String> = strangers.iter().map(|p| name(p)).collect();
        return AboutRow {
            value: names.join(", "),
            orange: true,
            detail: Some(t(lang, "device.about.listeners_unknown_info").to_string()),
        };
    }
    let reading: Vec<String> = listeners
        .iter()
        .filter(|l| !l.off)
        .map(|l| name(&l.package))
        .collect();
    if reading.is_empty() {
        AboutRow {
            value: t(lang, "device.about.listeners_only_vibb").to_string(),
            orange: false,
            detail: None,
        }
    } else {
        AboutRow {
            value: reading.join(", "),
            orange: false,
            detail: Some(t(lang, "device.about.listeners_info").to_string()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const CONTRACT: &str = include_str!("../testdata/notification_listeners.json");

    fn contract() -> Value {
        serde_json::from_str(CONTRACT).unwrap()
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

    #[test]
    fn the_reports_are_kept_whole() {
        let contract = contract();
        for key in ["report", "report_none"] {
            assert_eq!(
                clean(&contract[key]).as_ref(),
                Some(&contract[key]),
                "{key}"
            );
        }
    }

    #[test]
    fn the_unknown_rows() {
        for row in contract()["unknown"].as_array().unwrap() {
            let listeners: Vec<Listener> = serde_json::from_value(row["report"].clone()).unwrap();
            let known: BTreeSet<String> = serde_json::from_value(row["known"].clone()).unwrap();
            assert_eq!(
                json!(unknown(&listeners, &known)),
                row["unknown"],
                "{}",
                row["case"]
            );
        }
    }

    #[test]
    fn about_is_quiet_unless_an_unknown_app_reads() {
        let listeners = vec![
            Listener {
                package: "com.example.watch".into(),
                off: false,
            },
            Listener {
                package: "com.google.android.projection.gearhead".into(),
                off: true,
            },
        ];
        let installed: Vec<InstalledApp> = serde_json::from_value(
            json!([{"package_name": "com.example.watch", "label": "Watch"}]),
        )
        .unwrap();
        let row = about(Lang::En, &listeners, &BTreeSet::new(), &installed);
        assert!(row.orange);
        assert_eq!(row.value, "Watch");
        let known = BTreeSet::from(["com.example.watch".to_string()]);
        let row = about(Lang::Nb, &listeners, &known, &installed);
        assert!(!row.orange);
        assert_eq!(row.value, "Watch");
        let row = about(Lang::En, &listeners[1..], &known, &installed);
        assert!(!row.orange);
        assert_eq!(row.value, "Only Vibb One");
    }

    #[test]
    fn the_contract_is_identical_to_the_launchers_copy() {
        let launcher = std::fs::read_to_string(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../launcher/app/src/test/resources/notification_listeners.json"
        ))
        .unwrap();
        assert_eq!(launcher, CONTRACT, "the launcher's copy differs");
    }
}
