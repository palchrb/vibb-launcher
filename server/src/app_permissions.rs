//! Per-app permissions (design 68, `docs/design/68-app-permissions.md` at the monorepo root): the parent's "camera
//! off" per phone and app (`device_app_permissions`, migration 0102), sent as the policy's `app_permissions`, and
//! what the phone reports about each app's camera and special accesses (`installed_apps[].access`). Since 2026-11-04
//! also "Media player on the lock screen" per phone and app (`device_lock_media_apps`, migration 0127), sent
//! as the policy's `lock_media_apps`.
//!
//! The phone works out which special accesses an app wants from its own manifest; the server sends none of them and
//! only shows what the report says. A special access is approved on the phone alone (Settings › App access, in a
//! pause) - Android has no device-owner API for it.

use crate::i18n::t;
use serde::Serialize;
use sqlx::SqlitePool;

/// At most this many rows a phone (the PWA's form is the only writer).
pub const MAX_ROWS_PER_DEVICE: i64 = 200;

/// Every word this round shows the parent, from the catalog (`apps.perms.*`, design 70 slice 3a), in one place.
pub mod words {
    use crate::i18n::{Lang, t, t_params};

    /// The special accesses' names, by wire key (`AppAccess::special`'s order): their catalog keys.
    pub const ACCESSES: [(&str, &str); 5] = [
        ("manage_media", "apps.perms.access.manage_media"),
        ("exact_alarm", "apps.perms.access.exact_alarm"),
        ("write_settings", "apps.perms.access.write_settings"),
        // Android 16's name ("Usage access" on Android 14).
        ("usage_access", "apps.perms.access.usage_access"),
        ("full_screen", "apps.perms.access.full_screen"),
    ];

    pub fn state(lang: Lang, state: &str) -> &'static str {
        t(
            lang,
            match state {
                "granted" => "apps.perms.approved",
                "denied" => "apps.perms.not_approved",
                _ => "apps.perms.unknown",
            },
        )
    }

    pub fn camera_help(lang: Lang) -> &'static str {
        t(lang, "apps.perms.camera_help")
    }

    /// The way to approve `access` (its wire `key`, its `name`) on the phone. Alarms & reminders add the app's first start
    /// in the pause: Etar asks for the battery exemption then, which can't open outside it, and crashes (E4's M2).
    pub fn how_to_approve(lang: Lang, app: &str, key: &str, access: &str) -> String {
        let how = if key == "exact_alarm" {
            "apps.perms.how_alarm"
        } else {
            "apps.perms.how"
        };
        t_params(lang, how, &[("app", app), ("access", access)])
    }
}

/// One entry of the policy's `app_permissions`: a package the parent switched away from the default on this phone.
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

/// The policy's `lock_media_apps` for a phone (2026-11-04), sorted: the apps the parent switched on as media players on
/// the lock screen - the phone's lock tile then shows their playback as it does a music, video or browser app's.
pub async fn lock_media_apps(db: &SqlitePool, device_id: i64) -> Result<Vec<String>, sqlx::Error> {
    sqlx::query_scalar(
        "SELECT package_name FROM device_lock_media_apps WHERE device_id = ? ORDER BY package_name",
    )
    .bind(device_id)
    .fetch_all(db)
    .await
}

/// Switches one app's "Media player on the lock screen" on (a row) or off (none). Turning on first prunes
/// the phone's rows outside [offered] (the apps the switch is offered for now: an app uninstalled or no longer
/// allowed since keeps no row, so stale rows never fill the cap). `Ok(false)`: the phone still has
/// [MAX_ROWS_PER_DEVICE] rows (nothing written). Off always deletes, whatever the app is now.
pub async fn set_lock_media(
    db: &SqlitePool,
    device_id: i64,
    package: &str,
    on: bool,
    offered: &[String],
) -> Result<bool, sqlx::Error> {
    if on {
        sqlx::query(
            "DELETE FROM device_lock_media_apps WHERE device_id = ? \
             AND package_name NOT IN (SELECT value FROM json_each(?))",
        )
        .bind(device_id)
        .bind(serde_json::to_string(offered).unwrap_or_else(|_| "[]".to_string()))
        .execute(db)
        .await?;
    }
    if !on {
        sqlx::query("DELETE FROM device_lock_media_apps WHERE device_id = ? AND package_name = ?")
            .bind(device_id)
            .bind(package)
            .execute(db)
            .await?;
        return Ok(true);
    }
    let others: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM device_lock_media_apps WHERE device_id = ? AND package_name != ?",
    )
    .bind(device_id)
    .bind(package)
    .fetch_one(db)
    .await?;
    if others >= MAX_ROWS_PER_DEVICE {
        return Ok(false);
    }
    sqlx::query(
        "INSERT INTO device_lock_media_apps (device_id, package_name) VALUES (?, ?) \
         ON CONFLICT(device_id, package_name) DO UPDATE SET updated_at = datetime('now')",
    )
    .bind(device_id)
    .bind(package)
    .execute(db)
    .await?;
    Ok(true)
}

/// Whether an allowed app on Device › Apps gets the lock-screen media switch, on the page and at the route alike: a
/// third-party app the phone's last report has installed ([third_party]: reported and not preinstalled - the
/// Permissions are a third-party app's), never Vibb, Vibb music (its player always shows) or Play's core.
pub fn lock_media_offered(package: &str, is_launcher: bool, third_party: bool) -> bool {
    !is_launcher
        && third_party
        && crate::app_display::valid_package_name(package)
        && !crate::play::is_play_core(package)
        && !crate::music::MUSIC_PACKAGES.contains(&package)
        && !package.starts_with("me.vibb.launcher")
}

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

    /// The special accesses the app declares, in [words::ACCESSES]' order: `(key, name's catalog key, state)`.
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

/// One switch of an app's Tilgang (design 127 §2.5): its key, its name, on (allowed) or off, and the sub-line shown
/// only while the state isn't simply as set - `(text, orange)`.
pub struct AccessSwitch {
    pub key: &'static str,
    pub name: &'static str,
    pub on: bool,
    pub sub: Option<(&'static str, bool)>,
}

/// An app's Tilgang and Låseskjerm on its own page (design 127 §2.5, §2.6): the switches the report says the app asks
/// for (today the camera), the special accesses as quiet values, and the lock screen's media player.
pub struct AccessGroup {
    pub action: String,
    pub lock_media_action: String,
    pub package_name: String,
    /// The Tilgang group shows (the report has the app's `access`).
    pub shown: bool,
    pub camera: Option<AccessSwitch>,
    /// One per special access the app declares: its name and its value ("Godkjent", "Ikke godkjent", "Ukjent").
    pub specials: Vec<(&'static str, &'static str)>,
    /// The group's ⓘ: a line per switch shown, then the way to approve each special access that isn't.
    pub help: Vec<String>,
    /// "Mediespiller" (2026-11-04), only where [lock_media_offered]: on or off.
    pub lock_media: Option<bool>,
}

impl AccessGroup {
    /// For an allowed app on its page: `None` when the report says nothing about it (no `access`) and it has no
    /// lock-screen media switch ([lock_media]: `None` = not offered, else on or off). [paused]: the phone's last
    /// report says restrictions are paused, which lifts the camera off.
    #[allow(clippy::too_many_arguments)]
    pub fn of(
        lang: crate::i18n::Lang,
        device_id: i64,
        package: &str,
        label: &str,
        access: Option<&AppAccess>,
        camera_off: bool,
        paused: bool,
        lock_media: Option<bool>,
    ) -> Option<Self> {
        if access.is_none() && lock_media.is_none() {
            return None;
        }
        let none = AppAccess::default();
        let shown = access.is_some();
        let access = access.unwrap_or(&none);
        let mut help = Vec::new();
        // Design 127 §6: what any switch's off does, first, whenever a switch shows.
        if access.camera.is_some() {
            help.push(t(lang, "apps.access.closes").to_string());
        }
        let camera = access.camera.map(|state| {
            help.push(words::camera_help(lang).to_string());
            AccessSwitch {
                key: "camera",
                name: t(lang, "apps.perms.camera"),
                on: !camera_off,
                sub: match (camera_off, state) {
                    (true, _) if paused => Some((t(lang, "apps.access.paused"), false)),
                    (true, "requested") => Some((t(lang, "apps.access.waiting"), false)),
                    _ => None,
                },
            }
        });
        let mut specials = Vec::new();
        for (key, name, state) in access.special() {
            let name = t(lang, name);
            if state != "granted" {
                help.push(words::how_to_approve(lang, label, key, name));
            }
            specials.push((name, words::state(lang, state)));
        }
        Some(AccessGroup {
            action: format!("/devices/{device_id}/apps/permissions#access"),
            lock_media_action: format!("/devices/{device_id}/apps/lock-media#lock-screen"),
            package_name: package.to_string(),
            shown,
            camera,
            specials,
            help,
            lock_media,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::i18n::Lang;
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
            [("manage_media", "apps.perms.access.manage_media", "granted")]
        );
    }

    /// Design 127 §2.5: the camera switch only for an app that asks for it, its sub-line only while the state isn't as
    /// set, the special accesses as quiet values with the way to approve behind the ⓘ.
    #[test]
    fn the_group_says_what_the_report_says() {
        let of = |access: Option<AppAccess>, off: bool, paused: bool, lock: Option<bool>| {
            AccessGroup::of(
                Lang::En,
                1,
                "app.x",
                "Immich",
                access.as_ref(),
                off,
                paused,
                lock,
            )
        };
        let asks = || AppAccess::of(&json!({"camera": "requested", "manage_media": "denied"}));
        let group = of(asks(), true, false, None).unwrap();
        let camera = group.camera.as_ref().unwrap();
        assert!(!camera.on);
        assert_eq!(camera.sub, Some(("Waiting for the phone", false)));
        assert_eq!(group.specials, [("Media management", "Not approved")]);
        assert_eq!(
            group.help,
            [
                "Off: the app can't use it, and closes if it was running.",
                "Camera off: video calls and QR scanning stop working.",
                "On the phone: Settings (parent code) › Pause all restrictions › App access › Immich: Media \
                 management"
            ]
        );
        assert_eq!(group.action, "/devices/1/apps/permissions#access");
        assert_eq!(
            of(asks(), true, true, None).unwrap().camera.unwrap().sub,
            Some(("On during the pause", false))
        );
        // Applied, or on: nothing to say.
        let denied = AppAccess::of(&json!({"camera": "admin_denied", "manage_media": "granted"}));
        let group = of(denied, true, false, None).unwrap();
        assert_eq!(group.camera.unwrap().sub, None);
        assert_eq!(group.specials, [("Media management", "Approved")]);
        assert_eq!(group.help.len(), 2, "an approved access needs no way");
        assert!(
            of(asks(), false, false, None)
                .unwrap()
                .camera
                .unwrap()
                .sub
                .is_none()
        );
        // No camera key: no switch, and a stored row is inert.
        let group = of(
            AppAccess::of(&json!({"full_screen": "granted"})),
            true,
            false,
            None,
        )
        .unwrap();
        assert!(group.camera.is_none() && group.shown);
        assert!(of(None, true, false, None).is_none());
        // 2026-11-04: an app the phone says nothing about still gets the lock-screen media switch, alone.
        let switch = of(None, false, false, Some(true)).unwrap();
        assert_eq!(switch.lock_media, Some(true));
        assert!(!switch.shown && switch.camera.is_none() && switch.specials.is_empty());
        assert_eq!(
            switch.lock_media_action,
            "/devices/1/apps/lock-media#lock-screen"
        );
        // E4's M2: an alarm access's way says to open the app once in the pause.
        let alarm = AccessGroup::of(
            Lang::En,
            1,
            "ws.xsoh.etar",
            "Etar",
            AppAccess::of(&json!({"exact_alarm": "denied"})).as_ref(),
            false,
            false,
            None,
        )
        .unwrap();
        assert!(
            alarm.help[0].ends_with(
                "Etar: Alarms & reminders, then open Etar once while restrictions are paused"
            ),
            "{:?}",
            alarm.help
        );
    }

    #[test]
    fn the_lock_media_switch_is_a_third_party_apps() {
        assert!(lock_media_offered("de.danoeh.antennapod", false, true));
        assert!(
            !lock_media_offered("de.danoeh.antennapod", false, false),
            "preinstalled, or not installed"
        );
        assert!(!lock_media_offered("me.vibb.launcher", true, true));
        assert!(!lock_media_offered("me.vibb.launcher.debug", false, true));
        assert!(!lock_media_offered("me.vibb.music", false, true));
        assert!(!lock_media_offered("com.google.android.gms", false, true));
        assert!(!lock_media_offered("", false, true));
    }
}
