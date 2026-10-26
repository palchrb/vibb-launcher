//! Per-app permissions (design 68, `docs/design/68-app-permissions.md` at the monorepo root): the parent's "camera
//! off" per phone and app (`device_app_permissions`, migration 0102), sent as the policy's `app_permissions`, and
//! what the phone reports about each app's camera and special accesses (`installed_apps[].access`).
//!
//! The phone works out which special accesses an app wants from its own manifest; the server sends none of them and
//! only shows what the report says. A special access is approved on the phone alone (Settings › App access, in a
//! pause) - Android has no device-owner API for it.

use serde::Serialize;
use sqlx::SqlitePool;

/// One entry of the policy's `app_permissions`: a package the parent switched away from the default on this phone.
/// Later keys are additive; an older launcher ignores the whole key.
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct AppPermission {
    pub package_name: String,
    /// `"deny"`: the phone denies the app's camera (`setPermissionGrantState` DENIED).
    pub camera: &'static str,
}

/// The policy's `app_permissions` for a phone, by package. A failing query is the caller's 500 for the whole policy
/// (fail closed): an empty list would hand every camera back.
pub async fn policy_app_permissions(
    db: &SqlitePool,
    device_id: i64,
) -> Result<Vec<AppPermission>, sqlx::Error> {
    Ok(camera_denied(db, device_id)
        .await?
        .into_iter()
        .map(|package_name| AppPermission {
            package_name,
            camera: "deny",
        })
        .collect())
}

/// The packages whose camera the parent switched off on this phone, sorted.
pub async fn camera_denied(db: &SqlitePool, device_id: i64) -> Result<Vec<String>, sqlx::Error> {
    sqlx::query_scalar(
        "SELECT package_name FROM device_app_permissions WHERE device_id = ? AND camera = 'deny' \
         ORDER BY package_name",
    )
    .bind(device_id)
    .fetch_all(db)
    .await
}

/// The special accesses a phone reports, in the order the PWA shows them, with their names.
pub const ACCESSES: [(&str, &str); 5] = [
    ("manage_media", "Media management"),
    ("exact_alarm", "Alarms & reminders"),
    ("write_settings", "Modify system settings"),
    ("usage_access", "Usage access"),
    ("full_screen", "Full-screen notifications"),
];

const ACCESS_STATES: [&str; 3] = ["granted", "denied", "unknown"];
const CAMERA_STATES: [&str; 2] = ["requested", "admin_denied"];

/// `installed_apps[].access` as stored: only the keys the app declares, each a known value. `camera` is
/// `requested` | `admin_denied` (only for the third-party apps the phone may deny), the five special accesses
/// `granted` | `denied` | `unknown`.
#[derive(Serialize, Clone, Debug, Default, PartialEq, Eq)]
pub struct AppAccess {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub camera: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub manage_media: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub exact_alarm: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub write_settings: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub usage_access: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub full_screen: Option<&'static str>,
}

impl AppAccess {
    /// Known keys and values only; anything else - a newer launcher's key or value, a wrong type - is dropped, never
    /// a refused report. Nothing known = `None`.
    pub fn of(value: &serde_json::Value) -> Option<Self> {
        let object = value.as_object()?;
        let known = |key: &str, states: &[&'static str]| {
            object
                .get(key)
                .and_then(serde_json::Value::as_str)
                .and_then(|v| states.iter().copied().find(|s| *s == v))
        };
        let access = AppAccess {
            camera: known("camera", &CAMERA_STATES),
            manage_media: known("manage_media", &ACCESS_STATES),
            exact_alarm: known("exact_alarm", &ACCESS_STATES),
            write_settings: known("write_settings", &ACCESS_STATES),
            usage_access: known("usage_access", &ACCESS_STATES),
            full_screen: known("full_screen", &ACCESS_STATES),
        };
        (access != AppAccess::default()).then_some(access)
    }

    /// The special accesses the app declares, in [ACCESSES]' order: `(key, name, state)`.
    pub fn special(&self) -> Vec<(&'static str, &'static str, &'static str)> {
        let states = [
            self.manage_media,
            self.exact_alarm,
            self.write_settings,
            self.usage_access,
            self.full_screen,
        ];
        ACCESSES
            .iter()
            .zip(states)
            .filter_map(|((key, name), state)| state.map(|s| (*key, *name, s)))
            .collect()
    }
}

/// `InstalledApp.access`'s deserializer ([AppAccess::of]).
pub fn lenient_access<'de, D: serde::Deserializer<'de>>(
    deserializer: D,
) -> Result<Option<AppAccess>, D::Error> {
    let value = <Option<serde_json::Value> as serde::Deserialize>::deserialize(deserializer)?;
    Ok(value.as_ref().and_then(AppAccess::of))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn access_keeps_known_keys_and_values_only() {
        let access = AppAccess::of(&json!({
            "camera": "admin_denied",
            "manage_media": "granted",
            "exact_alarm": "maybe",
            "full_screen": 3,
            "location": "granted",
        }))
        .unwrap();
        assert_eq!(access.camera, Some("admin_denied"));
        assert_eq!(access.manage_media, Some("granted"));
        assert_eq!(access.exact_alarm, None);
        assert_eq!(access.full_screen, None);
        assert_eq!(
            serde_json::to_value(&access).unwrap(),
            json!({"camera": "admin_denied", "manage_media": "granted"})
        );
        assert_eq!(AppAccess::of(&json!({"location": "granted"})), None);
        assert_eq!(AppAccess::of(&json!("granted")), None);
        assert_eq!(
            access.special(),
            [("manage_media", "Media management", "granted")]
        );
    }
}
