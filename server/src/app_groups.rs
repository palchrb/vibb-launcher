//! Design 139 part A (`docs/design/139-app-groups.md`): app groups - a named set of one phone's apps with one daily
//! limit for all of them together, per weekday (migration 0186, `app_groups`). The phone counts the time the apps are on
//! screen and closes the group at its limit; the server stores, validates and serves the groups in
//! `time_policy.groups` (contract `contracts/core/app_groups.json`) and shows the phone's report
//! (`time_state.groups`). The pages are `handlers::app_groups`.

use serde::Serialize;
use serde_json::Value;
use sqlx::SqlitePool;

/// A group's name: 1-40 characters, trimmed.
pub const MAX_NAME_CHARS: usize = 40;
/// At most this many apps in one group.
pub const MAX_APPS: usize = 50;
/// At most this many groups on one phone (code QA L2).
pub const MAX_GROUPS: usize = 50;
/// The report's `time_state.groups` keeps at most this many groups.
pub const MAX_REPORTED: usize = 50;

/// An `app_groups` row.
#[derive(sqlx::FromRow, Clone, Debug)]
pub struct AppGroupRow {
    pub id: i64,
    pub name: String,
    pub apps_json: String,
    pub days_json: String,
}

/// `PolicyResponse.time_policy.groups[]` (§5.2): the group's id, name, apps and its 7 limits, Monday first, minutes
/// or `null` for no limit that day.
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct PolicyGroup {
    pub id: i64,
    pub name: String,
    pub apps: Vec<String>,
    pub days: Vec<Option<i64>>,
}

/// Packages a group never offers (D8, code QA L3): the Play Store and Play services, the system WebView, the usual
/// dialers, clocks and keyboards - the phone spares them anyway (its `neverRestrict`, the alarm apps, the Play Store), so
/// the PWA never shows them as closable.
pub const NEVER_GROUPED: &[&str] = &[
    "com.android.vending",
    "com.google.android.gms",
    "com.google.android.gsf",
    "com.google.android.webview",
    "com.android.webview",
    "com.android.dialer",
    "com.google.android.dialer",
    "com.android.deskclock",
    "com.google.android.deskclock",
    "com.android.inputmethod.latin",
    "com.google.android.inputmethod.latin",
];

/// A package Vibb never puts in a group (D8): Vibb One, Vibb music and our other packages, and [NEVER_GROUPED]. The phone
/// spares its own and the music app too.
pub fn never_grouped(package: &str) -> bool {
    package.starts_with("me.vibb.") || NEVER_GROUPED.contains(&package)
}

/// The stored apps: valid package names, at most [MAX_APPS], none of ours.
pub fn parse_apps(json: &str) -> Result<Vec<String>, String> {
    let apps: Vec<String> = serde_json::from_str(json).map_err(|e| format!("apps: {e}"))?;
    if apps.len() > MAX_APPS {
        return Err(format!("apps: {} is more than {MAX_APPS}", apps.len()));
    }
    if let Some(bad) = apps
        .iter()
        .find(|p| !crate::time_rules::valid_package_name(p) || never_grouped(p))
    {
        return Err(format!("apps: {bad:?} isn't a package a group takes"));
    }
    Ok(apps)
}

impl AppGroupRow {
    /// The wire form; anything stored that doesn't read is an error (the policy is a 500, never fewer groups).
    pub fn to_policy(&self) -> Result<PolicyGroup, String> {
        let name = self.name.trim();
        if name.is_empty() || name.chars().count() > MAX_NAME_CHARS {
            return Err(format!("group {}: a name of the wrong length", self.id));
        }
        Ok(PolicyGroup {
            id: self.id,
            name: name.to_string(),
            apps: parse_apps(&self.apps_json).map_err(|e| format!("group {}: {e}", self.id))?,
            days: crate::time_rules::parse_budget(&self.days_json)
                .map_err(|e| format!("group {}: {e}", self.id))?,
        })
    }

    /// The group limits some day, or doesn't read (fail closed: the setup step applies).
    pub fn has_limit(&self) -> bool {
        crate::time_rules::parse_budget(&self.days_json)
            .map_or(true, |days| days.iter().any(Option::is_some))
    }

    /// Its apps, empty when they don't read.
    pub fn apps(&self) -> Vec<String> {
        parse_apps(&self.apps_json).unwrap_or_default()
    }
}

/// The phone's groups, oldest first.
pub async fn of_device(db: &SqlitePool, device_id: i64) -> Result<Vec<AppGroupRow>, sqlx::Error> {
    sqlx::query_as(
        "SELECT id, name, apps_json, days_json FROM app_groups WHERE device_id = ? \
         ORDER BY id",
    )
    .bind(device_id)
    .fetch_all(db)
    .await
}

/// One of the phone's groups; `None` for another phone's or a deleted one.
pub async fn one(
    db: &SqlitePool,
    device_id: i64,
    group_id: i64,
) -> Result<Option<AppGroupRow>, sqlx::Error> {
    sqlx::query_as(
        "SELECT id, name, apps_json, days_json FROM app_groups WHERE id = ? \
         AND device_id = ?",
    )
    .bind(group_id)
    .bind(device_id)
    .fetch_optional(db)
    .await
}

/// The policy's `groups` (always the key, `[]` when there are none).
pub fn policy_groups(rows: &[AppGroupRow]) -> Result<Vec<PolicyGroup>, String> {
    rows.iter().map(AppGroupRow::to_policy).collect()
}

/// The setup step's input (§5.3, `when: app_groups`): a group limits some day. A failed read counts as one, so the
/// step's line shows rather than hides.
pub async fn setup_input(db: &SqlitePool, device_id: i64) -> bool {
    match of_device(db, device_id).await {
        Ok(rows) => rows.iter().any(AppGroupRow::has_limit),
        Err(err) => {
            tracing::error!(device_id, %err, "can't read the app groups for the setup steps");
            true
        }
    }
}

/// One group in the phone's report (`time_state.groups[]`, §4.4).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Reported {
    pub id: i64,
    pub used_minutes: i64,
    pub extra_minutes: i64,
    pub closed: bool,
}

/// The report's groups, from the stored `time_state` (kept to the known keys by `device_api::TIME_STATE_FIELDS`).
pub fn reported(time_state_json: Option<&str>) -> (Option<String>, Vec<Reported>) {
    let Some(state) = time_state_json.and_then(|j| serde_json::from_str::<Value>(j).ok()) else {
        return (None, Vec::new());
    };
    let day = state["day"].as_str().map(str::to_string);
    let groups = state["groups"]
        .as_array()
        .map(|list| {
            list.iter()
                .filter_map(|g| {
                    Some(Reported {
                        id: g["id"].as_i64()?,
                        used_minutes: g["used_minutes"].as_i64().unwrap_or(0),
                        extra_minutes: g["extra_minutes"].as_i64().unwrap_or(0),
                        closed: g["closed"].as_bool().unwrap_or(false),
                    })
                })
                .collect()
        })
        .unwrap_or_default();
    (day, groups)
}

/// The report's `usage_access` (§4.4): `None` when the phone left it out (unreadable) or sent no report.
pub fn usage_access(time_state_json: Option<&str>) -> Option<bool> {
    time_state_json
        .and_then(|j| serde_json::from_str::<Value>(j).ok())?
        .get("usage_access")?
        .as_bool()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn row(name: &str, apps: &str, days: &str) -> AppGroupRow {
        AppGroupRow {
            id: 3,
            name: name.to_string(),
            apps_json: apps.to_string(),
            days_json: days.to_string(),
        }
    }

    #[test]
    fn a_stored_group_reads_or_fails_whole() {
        let ok = row(
            "Spill",
            r#"["org.example.game"]"#,
            "[60,60,60,60,60,120,120]",
        );
        assert_eq!(
            ok.to_policy().unwrap(),
            PolicyGroup {
                id: 3,
                name: "Spill".into(),
                apps: vec!["org.example.game".into()],
                days: vec![
                    Some(60),
                    Some(60),
                    Some(60),
                    Some(60),
                    Some(60),
                    Some(120),
                    Some(120)
                ],
            }
        );
        assert!(ok.has_limit());
        assert!(!row("A", "[]", "[null,null,null,null,null,null,null]").has_limit());
        for bad in [
            row("", "[]", "[null,null,null,null,null,null,null]"),
            row("A", "nope", "[null,null,null,null,null,null,null]"),
            row(
                "A",
                r#"["me.vibb.music"]"#,
                "[null,null,null,null,null,null,null]",
            ),
            row("A", "[]", "[60]"),
            row("A", "[]", "[2000,0,0,0,0,0,0]"),
        ] {
            assert!(bad.to_policy().is_err(), "{bad:?}");
        }
        // A group that doesn't read counts as limiting (the setup step's line shows).
        assert!(row("A", "[]", "[60]").has_limit());
    }

    #[test]
    fn the_report_reads_its_groups() {
        let (day, groups) = reported(Some(
            r#"{"day":"2026-11-27","groups":[{"id":3,"used_minutes":40,"extra_minutes":15,"closed":false},{"x":1}]}"#,
        ));
        assert_eq!(day.as_deref(), Some("2026-11-27"));
        assert_eq!(
            groups,
            [Reported {
                id: 3,
                used_minutes: 40,
                extra_minutes: 15,
                closed: false
            }]
        );
        assert_eq!(usage_access(Some(r#"{"usage_access":false}"#)), Some(false));
        assert_eq!(usage_access(Some("{}")), None);
    }
}
