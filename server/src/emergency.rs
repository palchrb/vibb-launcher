//! A phone's emergency menu (design 73): the numbers the kid gets when it taps Nødsamtale, in the parent's order,
//! sent as `call_policy.emergency_menu`. Stored in `device_emergency_numbers` (0114); no rows = [DEFAULT_NUMBER] alone.
//!
//! The fixed emergency list is shared with the launcher (`testdata/emergency_numbers.json`, byte-identical in
//! `../launcher/app/src/test/resources/`; the launcher's `Emergency.staticSet` ORs it with the platform's own
//! `isEmergencyNumber`). Only the numbers on it are shown here as "always works"; the phone may know a few more.

use serde::Serialize;
use sqlx::SqlitePool;

/// Emergency numbers in every country (the launcher's `Emergency.ALWAYS`).
pub const ALWAYS: &[&str] = &["112", "911"];

/// The default country code's own (the launcher's `Emergency.BY_COUNTRY`). Deliberately not 000/08/118/999.
pub const BY_COUNTRY: &[(&str, &[&str])] = &[
    ("45", &["112", "114"]),
    ("46", &["112"]),
    ("47", &["110", "112", "113"]),
];

/// The menu of a phone without rows, and the number the list may never be without when nothing else is.
pub const DEFAULT_NUMBER: &str = "112";

/// At most this many numbers, so the phone's sheet always fits the screen.
pub const MAX_ENTRIES: usize = 6;

/// A name's length, in characters.
pub const MAX_NAME_CHARS: usize = 40;

/// The PWA's suggestions (the user, 2026-11-01: Ambulanse and Brann, no Legevakt): a default country code, the
/// number and the word for its name, shown while the number isn't on the list.
pub const SUGGESTIONS: &[(&str, &str, &str)] = &[
    ("47", "113", "device.emergency.ambulance"),
    ("47", "110", "device.emergency.fire"),
];

/// Whether a normalised number (`phone::normalize`) is on the fixed list for `cc`. Exact matches only.
pub fn is_emergency(number: &str, cc: &str) -> bool {
    ALWAYS.contains(&number)
        || BY_COUNTRY
            .iter()
            .any(|(country, numbers)| *country == cc && numbers.contains(&number))
}

/// One number on the menu: `id` is `None` for the default row nothing has stored yet.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MenuEntry {
    pub id: Option<i64>,
    pub number: String,
    pub name: String,
}

/// `call_policy.emergency_menu`'s row (the launcher's `PolicyEmergencyEntry`).
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct PolicyEntry {
    pub number: String,
    pub name: String,
}

fn default_menu() -> Vec<MenuEntry> {
    vec![MenuEntry {
        id: None,
        number: DEFAULT_NUMBER.to_string(),
        name: String::new(),
    }]
}

/// The phone's menu in order; the default when it has no rows.
pub async fn device_menu(db: &SqlitePool, device_id: i64) -> Result<Vec<MenuEntry>, sqlx::Error> {
    let rows: Vec<(i64, String, String)> = sqlx::query_as(
        "SELECT id, number, name FROM device_emergency_numbers WHERE device_id = ? ORDER BY sort_order, id",
    )
    .bind(device_id)
    .fetch_all(db)
    .await?;
    if rows.is_empty() {
        return Ok(default_menu());
    }
    Ok(rows
        .into_iter()
        .map(|(id, number, name)| MenuEntry {
            id: Some(id),
            number,
            name,
        })
        .collect())
}

/// What the policy sends: never empty.
pub fn policy_menu(menu: &[MenuEntry]) -> Vec<PolicyEntry> {
    let entries: Vec<PolicyEntry> = menu
        .iter()
        .map(|e| PolicyEntry {
            number: e.number.clone(),
            name: e.name.clone(),
        })
        .collect();
    if entries.is_empty() {
        policy_menu(&default_menu())
    } else {
        entries
    }
}

/// Before the first write: a phone without rows gets its default 112 stored, so the change applies to the list
/// the parent sees.
pub async fn store_default(
    tx: &mut sqlx::SqliteConnection,
    device_id: i64,
) -> Result<(), sqlx::Error> {
    let stored: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM device_emergency_numbers WHERE device_id = ?)",
    )
    .bind(device_id)
    .fetch_one(&mut *tx)
    .await?;
    if !stored {
        sqlx::query(
            "INSERT INTO device_emergency_numbers (device_id, number, name, sort_order) VALUES (?, ?, '', 1)",
        )
        .bind(device_id)
        .bind(DEFAULT_NUMBER)
        .execute(&mut *tx)
        .await?;
    }
    Ok(())
}

/// After Phone Setup's default country code changed (code QA L3): a phone whose stored menu has no number of the
/// fixed list for `cc` any more (113 is Norway's, not Sweden's) gets 112 first, as the phone would show it; a menu
/// that then has more than [MAX_ENTRIES] loses its last number, which the phone drops too. Returns the phones changed.
pub async fn repair_menus(
    tx: &mut sqlx::SqliteConnection,
    cc: &str,
) -> Result<Vec<i64>, sqlx::Error> {
    let rows: Vec<(i64, i64, String)> = sqlx::query_as(
        "SELECT device_id, id, number FROM device_emergency_numbers ORDER BY device_id, sort_order, id",
    )
    .fetch_all(&mut *tx)
    .await?;
    let mut changed = Vec::new();
    let mut at = 0;
    while at < rows.len() {
        let device = rows[at].0;
        let menu: Vec<&(i64, i64, String)> =
            rows[at..].iter().take_while(|r| r.0 == device).collect();
        at += menu.len();
        if menu.iter().any(|(_, _, number)| is_emergency(number, cc)) {
            continue;
        }
        sqlx::query(
            "INSERT INTO device_emergency_numbers (device_id, number, name, sort_order) VALUES (?, ?, '', 0)",
        )
        .bind(device)
        .bind(DEFAULT_NUMBER)
        .execute(&mut *tx)
        .await?;
        for (i, (_, id, _)) in menu.iter().enumerate() {
            if i + 1 >= MAX_ENTRIES {
                sqlx::query("DELETE FROM device_emergency_numbers WHERE id = ?")
                    .bind(id)
                    .execute(&mut *tx)
                    .await?;
            } else {
                sqlx::query("UPDATE device_emergency_numbers SET sort_order = ? WHERE id = ?")
                    .bind(i as i64 + 1)
                    .bind(id)
                    .execute(&mut *tx)
                    .await?;
            }
        }
        changed.push(device);
    }
    Ok(changed)
}

/// The row the phone shows large and red: the first number on the fixed list (design 73 §3).
pub fn big_row(menu: &[MenuEntry], cc: &str) -> Option<usize> {
    menu.iter().position(|e| is_emergency(&e.number, cc))
}

/// Whether the row at `at` may go: the list keeps at least one number on the fixed list (and so is never empty).
pub fn can_remove(menu: &[MenuEntry], at: usize, cc: &str) -> bool {
    menu.iter()
        .enumerate()
        .any(|(i, e)| i != at && is_emergency(&e.number, cc))
}

/// The suggestions to show: for this country, not on the list, while the list has room. `(number, name key)`.
pub fn suggestions(menu: &[MenuEntry], cc: &str) -> Vec<(&'static str, &'static str)> {
    if menu.len() >= MAX_ENTRIES {
        return Vec::new();
    }
    SUGGESTIONS
        .iter()
        .filter(|(country, number, _)| *country == cc && !menu.iter().any(|e| e.number == *number))
        .map(|(_, number, key)| (*number, *key))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const CONTRACT: &str = include_str!("../testdata/emergency_numbers.json");

    fn entry(number: &str) -> MenuEntry {
        MenuEntry {
            id: Some(1),
            number: number.to_string(),
            name: String::new(),
        }
    }

    #[test]
    fn the_fixed_list_is_the_contracts() {
        let contract: serde_json::Value = serde_json::from_str(CONTRACT).unwrap();
        let always: Vec<&str> = contract["always"]
            .as_array()
            .unwrap()
            .iter()
            .map(|v| v.as_str().unwrap())
            .collect();
        assert_eq!(always, ALWAYS);
        let by_country = contract["by_country"].as_object().unwrap();
        assert_eq!(by_country.len(), BY_COUNTRY.len());
        for (cc, numbers) in BY_COUNTRY {
            let listed: Vec<&str> = by_country[*cc]
                .as_array()
                .unwrap()
                .iter()
                .map(|v| v.as_str().unwrap())
                .collect();
            assert_eq!(listed, *numbers, "{cc}");
        }
    }

    /// The same vectors the launcher runs: a raw number is normalised as a contact's, then judged.
    #[test]
    fn the_contract_vectors() {
        let contract: serde_json::Value = serde_json::from_str(CONTRACT).unwrap();
        let vectors = contract["vectors"].as_array().unwrap();
        assert!(vectors.len() >= 20);
        for v in vectors {
            let (input, cc) = (v["input"].as_str().unwrap(), v["cc"].as_str().unwrap());
            let judged = crate::phone::normalize(input, cc)
                .map(|n| is_emergency(&n, cc))
                .unwrap_or(false);
            assert_eq!(
                judged,
                v["emergency"].as_bool().unwrap(),
                "{input:?} in {cc}"
            );
        }
    }

    #[test]
    fn the_contract_matches_the_launchers_copy() {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../launcher/app/src/test/resources/emergency_numbers.json");
        let copy =
            std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
        assert_eq!(copy, CONTRACT, "{} differs", path.display());
    }

    #[test]
    fn the_list_keeps_an_emergency_number() {
        let menu = [entry("112"), entry("+4791234567"), entry("113")];
        assert!(can_remove(&menu, 0, "47"), "113 stays");
        assert!(can_remove(&menu, 1, "47"));
        let menu = [entry("112"), entry("+4791234567")];
        assert!(!can_remove(&menu, 0, "47"), "only Mamma would be left");
        assert!(!can_remove(&[entry("112")], 0, "47"), "never empty");
        // 113 is no emergency number in Sweden.
        assert!(!can_remove(&[entry("112"), entry("113")], 0, "46"));
    }

    #[test]
    fn the_big_row_is_the_first_emergency_number() {
        let menu = [entry("+4791234567"), entry("113"), entry("112")];
        assert_eq!(big_row(&menu, "47"), Some(1));
        assert_eq!(big_row(&[entry("+4791234567")], "47"), None);
    }

    #[test]
    fn suggestions_only_what_is_missing() {
        let shown = |menu: &[MenuEntry], cc| {
            suggestions(menu, cc)
                .into_iter()
                .map(|(n, _)| n)
                .collect::<Vec<_>>()
        };
        assert_eq!(shown(&[entry("112")], "47"), ["113", "110"]);
        assert_eq!(shown(&[entry("112"), entry("113")], "47"), ["110"]);
        assert!(shown(&[entry("112")], "46").is_empty(), "Norway's only");
        let full: Vec<MenuEntry> = (0..MAX_ENTRIES)
            .map(|i| entry(&format!("11{i}0")))
            .collect();
        assert!(shown(&full, "47").is_empty(), "no room");
        assert!(
            !shown(&[entry("112")], "47").contains(&"116117"),
            "no Legevakt"
        );
    }

    #[test]
    fn the_policy_menu_is_never_empty() {
        assert_eq!(
            policy_menu(&[]),
            [PolicyEntry {
                number: "112".into(),
                name: String::new()
            }]
        );
    }
}
