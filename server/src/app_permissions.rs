//! Per-app permissions (design 68, `docs/design/68-app-permissions.md` at the monorepo root): the parent's "camera
//! off" per phone and app (`device_app_permissions`, migration 0102), sent as the policy's `app_permissions`, and
//! what the phone reports about each app's camera and special accesses (`installed_apps[].access`).
//!
//! The phone works out which special accesses an app wants from its own manifest; the server sends none of them and
//! only shows what the report says. A special access is approved on the phone alone (Settings › App access, in a
//! pause) - Android has no device-owner API for it.

use serde::Serialize;
use sqlx::SqlitePool;

/// At most this many rows a phone (the PWA's form is the only writer).
pub const MAX_ROWS_PER_DEVICE: i64 = 200;

/// Every word this round shows the parent, in one place (the coordinator's rule of 2026-10-26: no English scattered
/// through templates).
// i18n: moves to i18n.rs (design 69)
pub mod words {
    /// The `<details>`' summary under an app's row.
    pub const PERMISSIONS: &str = "Permissions";
    pub const CAMERA: &str = "Camera";
    pub const CAMERA_OFF: &str = "Camera off";
    pub const CAMERA_WAITING: &str = "Camera off · waiting for the phone";
    /// The pause lifts it (design 68 Q1): the phone reports the camera back while it lasts.
    pub const CAMERA_PAUSED: &str = "Camera off · back on while restrictions are paused";
    pub const APPROVED: &str = "approved";
    pub const NOT_APPROVED: &str = "not approved";
    pub const UNKNOWN: &str = "unknown";
    pub const SAVE: &str = "Save";
    /// Above the Allowed list while the phone's launcher can't do this yet.
    pub const UPDATE_NOTE: &str = "Update Vibb One to manage apps' permissions";
    /// A refused save, under the app's row (`?notice=perms`).
    pub const REFUSED: &str = "This app's permissions can't be changed here.";
    pub const FULL: &str =
        "This phone already has 200 apps with their camera off - turn some back on first.";
    /// The special accesses' names, by wire key (`AppAccess::special`'s order).
    pub const ACCESSES: [(&str, &str); 5] = [
        ("manage_media", "Media management"),
        ("exact_alarm", "Alarms & reminders"),
        ("write_settings", "Modify system settings"),
        // Android 16's name ("Usage access" on Android 14).
        ("usage_access", "App usage data"),
        ("full_screen", "Full-screen notifications"),
    ];

    pub fn state(state: &str) -> &'static str {
        match state {
            "granted" => APPROVED,
            "denied" => NOT_APPROVED,
            _ => UNKNOWN,
        }
    }

    pub fn camera_help(app: &str) -> String {
        format!(
            "Off: {app} can't use the camera - a video call or a QR scan stops working. Turning it off closes \
             {app} if it was allowed the camera."
        )
    }

    pub fn how_to_approve(app: &str, access: &str) -> String {
        format!(
            "On the phone: Settings (parent code) › Pause all restrictions › App access › {app}: {access}"
        )
    }
}

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

    /// The special accesses the app declares, in [words::ACCESSES]' order: `(key, name, state)`.
    pub fn special(&self) -> Vec<(&'static str, &'static str, &'static str)> {
        let states = [
            self.manage_media,
            self.exact_alarm,
            self.write_settings,
            self.usage_access,
            self.full_screen,
        ];
        words::ACCESSES
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

/// Sets or clears one app's camera-off row. `Ok(false)`: the phone already has [MAX_ROWS_PER_DEVICE] rows (nothing
/// written).
pub async fn set_camera(
    db: &SqlitePool,
    device_id: i64,
    package: &str,
    deny: bool,
) -> Result<bool, sqlx::Error> {
    if !deny {
        sqlx::query("DELETE FROM device_app_permissions WHERE device_id = ? AND package_name = ?")
            .bind(device_id)
            .bind(package)
            .execute(db)
            .await?;
        return Ok(true);
    }
    let others: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM device_app_permissions WHERE device_id = ? AND package_name != ?",
    )
    .bind(device_id)
    .bind(package)
    .fetch_one(db)
    .await?;
    if others >= MAX_ROWS_PER_DEVICE {
        return Ok(false);
    }
    sqlx::query(
        "INSERT INTO device_app_permissions (device_id, package_name, camera) VALUES (?, ?, 'deny') \
         ON CONFLICT(device_id, package_name) DO UPDATE SET camera = 'deny', updated_at = datetime('now')",
    )
    .bind(device_id)
    .bind(package)
    .execute(db)
    .await?;
    Ok(true)
}

/// One allowed app's Permissions on Device › Apps: the row's second sub-line and the `<details>` under it.
pub struct PermsView {
    /// `perms-<package>`: the details' id and the form's return.
    pub anchor: String,
    pub action: String,
    pub package_name: String,
    /// Open on return from its own save (`?perms=<package>`).
    pub open: bool,
    /// "Camera off · Media management: not approved" - only what needs a look (an approval lives in the details);
    /// empty for nothing to say.
    pub line: String,
    /// The camera switch, only when the report has `access.camera`: `(on, help)`.
    pub camera: Option<(bool, String)>,
    /// One line per declared access: `(text, how to approve it while it isn't)`.
    pub accesses: Vec<(String, Option<String>)>,
    pub title: &'static str,
    pub camera_label: &'static str,
    pub save: &'static str,
}

impl PermsView {
    /// For an allowed app: `None` when the report says nothing about it (no `access`). [paused]: the phone's last
    /// report says restrictions are paused, which lifts the camera off.
    pub fn of(
        device_id: i64,
        package: &str,
        label: &str,
        access: Option<&AppAccess>,
        camera_off: bool,
        paused: bool,
        open: bool,
    ) -> Option<Self> {
        let access = access?;
        let mut parts = Vec::new();
        match (camera_off, access.camera) {
            (true, Some("admin_denied")) => parts.push(words::CAMERA_OFF.to_string()),
            (true, Some(_)) if paused => parts.push(words::CAMERA_PAUSED.to_string()),
            (true, Some(_)) => parts.push(words::CAMERA_WAITING.to_string()),
            _ => {}
        }
        let mut accesses = Vec::new();
        for (_, name, state) in access.special() {
            let text = format!("{name}: {}", words::state(state));
            let approved = state == "granted";
            if !approved {
                parts.push(text.clone());
            }
            accesses.push((
                text,
                (!approved).then(|| words::how_to_approve(label, name)),
            ));
        }
        let anchor = format!("perms-{package}");
        Some(PermsView {
            action: format!("/devices/{device_id}/apps/permissions#{anchor}"),
            anchor,
            package_name: package.to_string(),
            open,
            line: parts.join(" · "),
            camera: access
                .camera
                .map(|_| (!camera_off, words::camera_help(label))),
            accesses,
            title: words::PERMISSIONS,
            camera_label: words::CAMERA,
            save: words::SAVE,
        })
    }
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

    #[test]
    fn the_line_and_the_details_say_what_the_report_says() {
        let access = AppAccess::of(&json!({"camera": "requested", "manage_media": "denied"}));
        let view =
            PermsView::of(1, "app.x", "Immich", access.as_ref(), true, false, false).unwrap();
        assert_eq!(
            view.line,
            "Camera off · waiting for the phone · Media management: not approved"
        );
        let paused =
            PermsView::of(1, "app.x", "Immich", access.as_ref(), true, true, false).unwrap();
        assert!(
            paused.line.starts_with(words::CAMERA_PAUSED),
            "{}",
            paused.line
        );
        assert_eq!(view.camera.as_ref().map(|c| c.0), Some(false));
        assert_eq!(
            view.accesses[0].1.as_deref(),
            Some(
                "On the phone: Settings (parent code) › Pause all restrictions › App access › \
                 Immich: Media management"
            )
        );
        assert_eq!(view.action, "/devices/1/apps/permissions#perms-app.x");
        let denied = AppAccess::of(&json!({"camera": "admin_denied", "manage_media": "granted"}));
        let view =
            PermsView::of(1, "app.x", "Immich", denied.as_ref(), true, false, false).unwrap();
        // An approval stays out of the row's line and lives in the details.
        assert_eq!(view.line, "Camera off");
        assert_eq!(
            view.accesses[0],
            ("Media management: approved".to_string(), None)
        );
        // No camera key: no switch, and a stored row is inert.
        let none = AppAccess::of(&json!({"full_screen": "granted"}));
        let view = PermsView::of(1, "app.x", "X", none.as_ref(), true, false, false).unwrap();
        assert!(view.camera.is_none());
        assert_eq!(view.line, "");
        assert!(PermsView::of(1, "app.x", "X", None, true, false, false).is_none());
    }
}
