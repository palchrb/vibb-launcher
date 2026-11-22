//! Per-app permissions (design 68, `docs/design/68-app-permissions.md` at the monorepo root; design 127 step 2,
//! `docs/design/127-app-page.md` §3): the parent's Kamera, Posisjon, Mikrofon and Kontakter off per phone and app
//! (`device_app_denials`, migration 0166 - one row per key), sent as the policy's `app_permissions`, and what the phone
//! reports about each app's grant keys and special accesses (`installed_apps[].access`; the shared contract
//! `testdata/app_access.json`, byte-identical in the launcher's test resources). Since 2026-11-04
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

/// A key the parent switches off per app on its page (design 127 §3.1), in display order: its wire key, its name's
/// catalog key, the ⓘ line said while it shows, and the Android permissions it covers (the app asks for the key when it
/// requests any of them; the launcher's `AccessKey`, checked against `app_access.json`'s `keys`). `network` (Nett)
/// joins in step 3.
pub struct GrantKey {
    pub key: &'static str,
    pub name: &'static str,
    pub help: &'static str,
    /// Checked against the contract's `keys` (the launcher applies them; the server only shows the report).
    #[cfg_attr(not(test), allow(dead_code))]
    pub permissions: &'static [&'static str],
}

pub const GRANT_KEYS: [GrantKey; 4] = [
    GrantKey {
        key: "camera",
        name: "apps.perms.camera",
        help: "apps.perms.camera_help",
        permissions: &["android.permission.CAMERA"],
    },
    GrantKey {
        key: "location",
        name: "apps.perms.location",
        help: "apps.perms.location_help",
        permissions: &[
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
        ],
    },
    GrantKey {
        key: "microphone",
        name: "apps.perms.microphone",
        help: "apps.perms.microphone_help",
        permissions: &["android.permission.RECORD_AUDIO"],
    },
    GrantKey {
        key: "contacts",
        name: "apps.perms.contacts",
        help: "apps.perms.contacts_help",
        permissions: &[
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
        ],
    },
];

/// [key] as one of [GRANT_KEYS]' (the `'static` spelling), else `None`.
pub fn grant_key(key: &str) -> Option<&'static str> {
    GRANT_KEYS.iter().map(|k| k.key).find(|k| *k == key)
}

/// One entry of the policy's `app_permissions` (design 127 §3.2): a package with at least one key the parent switched
/// off on this phone, each `"deny"`, the others left out.
#[derive(Serialize, Clone, Debug, Default, PartialEq, Eq)]
pub struct AppPermission {
    pub package_name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub camera: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub location: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub microphone: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub contacts: Option<&'static str>,
}

impl AppPermission {
    fn deny(&mut self, key: &str) {
        let slot = match key {
            "camera" => &mut self.camera,
            "location" => &mut self.location,
            "microphone" => &mut self.microphone,
            "contacts" => &mut self.contacts,
            _ => return,
        };
        *slot = Some("deny");
    }

    /// Whether the parent switched [key] off.
    pub fn denies(&self, key: &str) -> bool {
        match key {
            "camera" => self.camera,
            "location" => self.location,
            "microphone" => self.microphone,
            "contacts" => self.contacts,
            _ => None,
        }
        .is_some()
    }
}

/// The policy's `app_permissions` for a phone, by package - also what the app's page reads. A failing query is the
/// caller's 500 for the whole policy (fail closed): an empty list would hand every permission back. A key this server
/// doesn't send yet (`network`, before step 3) is left out.
pub async fn policy_app_permissions(
    db: &SqlitePool,
    device_id: i64,
) -> Result<Vec<AppPermission>, sqlx::Error> {
    let rows: Vec<(String, String)> = sqlx::query_as(
        "SELECT package_name, permission FROM device_app_denials WHERE device_id = ? \
         ORDER BY package_name, permission",
    )
    .bind(device_id)
    .fetch_all(db)
    .await?;
    let mut out: Vec<AppPermission> = Vec::new();
    for (package, key) in rows {
        if grant_key(&key).is_none() {
            continue;
        }
        if out.last().is_none_or(|last| last.package_name != package) {
            out.push(AppPermission {
                package_name: package,
                ..AppPermission::default()
            });
        }
        if let Some(last) = out.last_mut() {
            last.deny(&key);
        }
    }
    Ok(out)
}

const ACCESS_STATES: [&str; 3] = ["granted", "denied", "unknown"];
/// A grant key's values (design 127 §3.2): asked for, every requested permission DENIED, or the phone's last try to
/// apply the parent's off refused.
const GRANT_STATES: [&str; 3] = ["requested", "admin_denied", "refused"];

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

/// `installed_apps[].access` as stored: only the keys the app declares, each a known value. The grant keys
/// ([GRANT_KEYS]: `camera`, `location`, `microphone`, `contacts`) are `requested` | `admin_denied` | `refused` (only for
/// the third-party apps the phone may deny), the five special accesses `granted` | `denied` | `unknown`.
#[derive(Serialize, Clone, Debug, Default, PartialEq, Eq)]
pub struct AppAccess {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub camera: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub location: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub microphone: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub contacts: Option<&'static str>,
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
            camera: known("camera", &GRANT_STATES),
            location: known("location", &GRANT_STATES),
            microphone: known("microphone", &GRANT_STATES),
            contacts: known("contacts", &GRANT_STATES),
            manage_media: known("manage_media", &ACCESS_STATES),
            exact_alarm: known("exact_alarm", &ACCESS_STATES),
            write_settings: known("write_settings", &ACCESS_STATES),
            usage_access: known("usage_access", &ACCESS_STATES),
            full_screen: known("full_screen", &ACCESS_STATES),
        };
        (access != AppAccess::default()).then_some(access)
    }

    /// A grant key's value ([GRANT_KEYS]), `None` when the app doesn't ask for it.
    pub fn grant(&self, key: &str) -> Option<&'static str> {
        match key {
            "camera" => self.camera,
            "location" => self.location,
            "microphone" => self.microphone,
            "contacts" => self.contacts,
            _ => None,
        }
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

/// Switches one key ([GRANT_KEYS]) of one app off (a row) or back on (none). `Ok(false)`: the phone already has
/// [MAX_ROWS_PER_DEVICE] other packages with a key off (nothing written). Allowing always deletes the one row. No prune
/// (design 127 §3.4): a removed app keeps its rows, so it comes back with its denials.
pub async fn set_denied(
    db: &SqlitePool,
    device_id: i64,
    package: &str,
    key: &'static str,
    deny: bool,
) -> Result<bool, sqlx::Error> {
    if !deny {
        sqlx::query(
            "DELETE FROM device_app_denials WHERE device_id = ? AND package_name = ? AND permission = ?",
        )
        .bind(device_id)
        .bind(package)
        .bind(key)
        .execute(db)
        .await?;
        return Ok(true);
    }
    let others: i64 = sqlx::query_scalar(
        "SELECT COUNT(DISTINCT package_name) FROM device_app_denials WHERE device_id = ? AND package_name != ?",
    )
    .bind(device_id)
    .bind(package)
    .fetch_one(db)
    .await?;
    if others >= MAX_ROWS_PER_DEVICE {
        return Ok(false);
    }
    sqlx::query(
        "INSERT INTO device_app_denials (device_id, package_name, permission) VALUES (?, ?, ?) \
         ON CONFLICT(device_id, package_name, permission) DO UPDATE SET updated_at = datetime('now')",
    )
    .bind(device_id)
    .bind(package)
    .bind(key)
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

/// An app's Tilgang and Låseskjerm on its own page (design 127 §2.5, §2.6): a switch per key the report says the app
/// asks for ([GRANT_KEYS]' order), the special accesses as quiet values, and the lock screen's media player.
pub struct AccessGroup {
    pub action: String,
    pub lock_media_action: String,
    pub package_name: String,
    /// The Tilgang group shows (the report has the app's `access`).
    pub shown: bool,
    pub switches: Vec<AccessSwitch>,
    /// One per special access the app declares: its name and its value ("Godkjent", "Ikke godkjent", "Ukjent").
    pub specials: Vec<(&'static str, &'static str)>,
    /// The group's ⓘ: what any switch's off does and a line per switch shown, then the way to approve each special
    /// access that isn't.
    pub help: Vec<String>,
    /// "Mediespiller" (2026-11-04), only where [lock_media_offered]: on or off.
    pub lock_media: Option<bool>,
}

/// A switch's sub-line (design 127 §2.5), only while it is off and the phone isn't simply as set: the pause lifts it,
/// the phone refused it (orange: the parent's off isn't applied, and nothing the parent does fixes it), or the phone
/// hasn't applied it yet. On, or applied: nothing.
fn switch_sub(
    lang: crate::i18n::Lang,
    off: bool,
    state: &str,
    paused: bool,
) -> Option<(&'static str, bool)> {
    if !off {
        return None;
    }
    match state {
        _ if paused => Some((t(lang, "apps.access.paused"), false)),
        "refused" => Some((t(lang, "apps.access.refused"), true)),
        "requested" => Some((t(lang, "apps.access.waiting"), false)),
        _ => None,
    }
}

impl AccessGroup {
    /// For an allowed app on its page: `None` when the report says nothing about it (no `access`) and it has no
    /// lock-screen media switch ([lock_media]: `None` = not offered, else on or off). [denied]: the keys the parent
    /// switched off for it (`None` = none). [paused]: the phone's last report says restrictions are paused, which
    /// lifts every key.
    #[allow(clippy::too_many_arguments)]
    pub fn of(
        lang: crate::i18n::Lang,
        device_id: i64,
        package: &str,
        label: &str,
        access: Option<&AppAccess>,
        denied: Option<&AppPermission>,
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
        let mut switches = Vec::new();
        for key in &GRANT_KEYS {
            let Some(state) = access.grant(key.key) else {
                continue;
            };
            let off = denied.is_some_and(|d| d.denies(key.key));
            // Design 127 §6: what any switch's off does, first, whenever a switch shows.
            if switches.is_empty() {
                help.push(t(lang, "apps.access.closes").to_string());
            }
            help.push(t(lang, key.help).to_string());
            switches.push(AccessSwitch {
                key: key.key,
                name: t(lang, key.name),
                on: !off,
                sub: switch_sub(lang, off, state, paused),
            });
        }
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
            switches,
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
            "microphone": "refused",
        }))
        .unwrap();
        assert_eq!(access.camera, Some("admin_denied"));
        assert_eq!(access.microphone, Some("refused"));
        assert_eq!(access.location, None);
        assert_eq!(access.manage_media, Some("granted"));
        assert_eq!(access.exact_alarm, None);
        assert_eq!(access.full_screen, None);
        assert_eq!(
            serde_json::to_value(&access).unwrap(),
            json!({"camera": "admin_denied", "microphone": "refused", "manage_media": "granted"})
        );
        assert_eq!(AppAccess::of(&json!({"location": "granted"})), None);
        assert_eq!(AppAccess::of(&json!("granted")), None);
        assert_eq!(
            access.special(),
            [("manage_media", "apps.perms.access.manage_media", "granted")]
        );
    }

    /// Design 127 §2.5: a switch only for a key the app asks for, its sub-line only while the state isn't as set, the
    /// special accesses as quiet values with the way to approve behind the ⓘ.
    #[test]
    fn the_group_says_what_the_report_says() {
        let camera_off = AppPermission {
            package_name: "app.x".to_string(),
            camera: Some("deny"),
            ..AppPermission::default()
        };
        let of = |access: Option<AppAccess>, off: bool, paused: bool, lock: Option<bool>| {
            AccessGroup::of(
                Lang::En,
                1,
                "app.x",
                "Immich",
                access.as_ref(),
                off.then_some(&camera_off),
                paused,
                lock,
            )
        };
        let asks = || AppAccess::of(&json!({"camera": "requested", "manage_media": "denied"}));
        let group = of(asks(), true, false, None).unwrap();
        let camera = &group.switches[0];
        assert_eq!(group.switches.len(), 1);
        assert!(!camera.on);
        assert_eq!(camera.sub, Some(("Waiting for the phone", false)));
        assert_eq!(group.specials, [("Media management", "Not approved")]);
        assert_eq!(
            group.help,
            [
                "Off: the app can't use it, and closes if it was using it.",
                "Camera off: video calls and QR scanning stop working.",
                "On the phone: Settings (parent code) › Pause all restrictions › App access › Immich: Media \
                 management"
            ]
        );
        assert_eq!(group.action, "/devices/1/apps/permissions#access");
        assert_eq!(
            of(asks(), true, true, None).unwrap().switches[0].sub,
            Some(("On during the pause", false))
        );
        // Applied, or on: nothing to say.
        let denied = AppAccess::of(&json!({"camera": "admin_denied", "manage_media": "granted"}));
        let group = of(denied, true, false, None).unwrap();
        assert_eq!(group.switches[0].sub, None);
        assert_eq!(group.specials, [("Media management", "Approved")]);
        assert_eq!(group.help.len(), 2, "an approved access needs no way");
        assert!(
            of(asks(), false, false, None).unwrap().switches[0]
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
        assert!(group.switches.is_empty() && group.shown);
        assert!(of(None, true, false, None).is_none());
        // 2026-11-04: an app the phone says nothing about still gets the lock-screen media switch, alone.
        let switch = of(None, false, false, Some(true)).unwrap();
        assert_eq!(switch.lock_media, Some(true));
        assert!(!switch.shown && switch.switches.is_empty() && switch.specials.is_empty());
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
            None,
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

    /// Design 127 step 2: the four switches in the table's order, only for what the app asks for; a refusal of the
    /// parent's off is the one orange line, and the pause still says the pause.
    #[test]
    fn every_grant_key_has_its_switch() {
        let access = AppAccess::of(&json!({
            "contacts": "refused", "microphone": "admin_denied", "location": "requested",
        }));
        let denied = AppPermission {
            package_name: "app.x".to_string(),
            location: Some("deny"),
            contacts: Some("deny"),
            ..AppPermission::default()
        };
        let group = |paused| {
            AccessGroup::of(
                Lang::Nb,
                1,
                "app.x",
                "X",
                access.as_ref(),
                Some(&denied),
                paused,
                None,
            )
            .unwrap()
        };
        let shown = group(false);
        let rows: Vec<_> = shown
            .switches
            .iter()
            .map(|s| (s.key, s.name, s.on, s.sub))
            .collect();
        assert_eq!(
            rows,
            [
                (
                    "location",
                    "Posisjon",
                    false,
                    Some(("Venter på telefonen", false))
                ),
                ("microphone", "Mikrofon", true, None),
                (
                    "contacts",
                    "Kontakter",
                    false,
                    Some(("Telefonen kan ikke slå av dette", true))
                ),
            ]
        );
        assert_eq!(shown.help.len(), 4, "{:?}", shown.help);
        assert!(
            group(true)
                .switches
                .iter()
                .filter(|s| !s.on)
                .all(|s| s.sub == Some(("På i pausen", false)))
        );
        assert_eq!(grant_key("network"), None, "Nett waits for step 3");
        assert_eq!(grant_key("camera"), Some("camera"));
    }

    fn contract() -> serde_json::Value {
        serde_json::from_str(&std::fs::read_to_string("testdata/app_access.json").unwrap()).unwrap()
    }

    /// Design 127 §3.3: the shared contract - `keys` against [GRANT_KEYS], `clean` through [AppAccess::of], `policy`
    /// produced by the page's real saves, `report` posted and read back as stored and as the page shows it, `never`
    /// refused, and the launcher's copy byte for byte.
    #[tokio::test]
    async fn the_contract() {
        use crate::tests::{TestApp, assert_redirect};
        use axum::http::Method;
        let contract = contract();
        let ours = std::fs::read("testdata/app_access.json").unwrap();
        let theirs = std::fs::read("../launcher/app/src/test/resources/app_access.json").unwrap();
        assert!(
            ours == theirs,
            "testdata/app_access.json differs from the launcher's copy"
        );

        let keys: Vec<serde_json::Value> = GRANT_KEYS
            .iter()
            .map(|k| json!({"key": k.key, "permissions": k.permissions}))
            .collect();
        assert_eq!(contract["keys"], json!(keys));

        for row in contract["clean"].as_array().unwrap() {
            let kept = AppAccess::of(&row["access"]).map(|a| serde_json::to_value(a).unwrap());
            assert_eq!(
                kept.unwrap_or(serde_json::Value::Null),
                row["kept"],
                "{row}"
            );
        }

        let app = TestApp::new().await;
        let cookie = app.admin_cookie().await;
        let (id, token) = app.enrolled_device("Anna").await;
        let policy = || async {
            app.request(Method::GET, "/api/devices/policy", Some(&token), None)
                .await
                .json()["app_permissions"]
                .clone()
        };
        assert_eq!(policy().await, contract["policy"]["none"]);

        // The report, stored known keys and values only, every one kept.
        let report = &contract["report"];
        app.post_status(&token, report.clone()).await;
        let stored: String = sqlx::query_scalar(
            "SELECT installed_apps_json FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1",
        )
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap();
        let stored: serde_json::Value = serde_json::from_str(&stored).unwrap();
        assert_eq!(stored, report["installed_apps"]);
        let packages: Vec<&str> = report["installed_apps"]
            .as_array()
            .unwrap()
            .iter()
            .map(|a| a["package_name"].as_str().unwrap())
            .collect();
        sqlx::query("UPDATE device_policy SET allowlist_json = ? WHERE device_id = ?")
            .bind(serde_json::to_string(&packages).unwrap())
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();

        // The page's saves: Element X's Kamera and Mikrofon off, Maps' Posisjon and Kontakter off, Notes' Kamera off
        // and on again.
        let save = |package: &'static str, key: &'static str, allow: bool| {
            let (app, cookie) = (&app, &cookie);
            async move {
                let mut form = vec![("package_name", package), ("key", key)];
                if allow {
                    form.push(("allow", "on"));
                }
                let res = app
                    .request_form(
                        Method::POST,
                        &format!("/devices/{id}/apps/permissions"),
                        Some(cookie),
                        &form,
                    )
                    .await;
                assert_redirect(
                    &res,
                    &format!("/devices/{id}/apps/{package}"),
                    Some("access"),
                );
            }
        };
        save("io.element.android.x", "camera", false).await;
        save("io.element.android.x", "microphone", false).await;
        save("org.example.maps", "location", false).await;
        save("org.example.maps", "contacts", false).await;
        save("org.example.notes", "camera", false).await;
        save("org.example.notes", "camera", true).await;
        assert_eq!(policy().await, contract["policy"]["seeded"]);

        // The page says what the report says: applied is quiet, a refusal orange, a key the app asks for and the
        // parent left on is a plain switch.
        let html = app
            .get_page(&format!("/devices/{id}/apps/io.element.android.x"), &cookie)
            .await
            .text();
        let access = &html[html.find("<section id=\"access\"").unwrap()..];
        let access = &access[..access.find("</section>").unwrap()];
        for key in ["camera", "microphone", "contacts"] {
            assert!(access.contains(&format!("data-access=\"{key}\"")), "{key}");
        }
        assert!(!access.contains("data-access=\"location\""));
        assert!(
            access.contains("tone-orange\">The phone can&#39;t turn this off"),
            "{access}"
        );
        assert_eq!(access.matches("row-sub").count(), 1, "{access}");
        // A deny of a key the app doesn't ask for is refused; an allow is always accepted.
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/apps/permissions"),
                Some(&cookie),
                &[
                    ("package_name", "io.element.android.x"),
                    ("key", "location"),
                ],
            )
            .await;
        assert_redirect(
            &res,
            &format!("/devices/{id}/apps/io.element.android.x?notice=perms"),
            Some("access"),
        );
        save("io.element.android.x", "location", true).await;

        // `never`: no switch offered or applied for Vibb's own apps, even when they ask.
        let never: Vec<&str> = contract["never"]
            .as_array()
            .unwrap()
            .iter()
            .map(|p| p.as_str().unwrap())
            .collect();
        app.post_status(
            &token,
            json!({ "installed_apps": never.iter().map(|p| json!({
                "package_name": p, "label": p, "preinstalled": false, "access": {"camera": "requested"},
            })).collect::<Vec<_>>() }),
        )
        .await;
        for package in never {
            let res = app
                .request_form(
                    Method::POST,
                    &format!("/devices/{id}/apps/permissions"),
                    Some(&cookie),
                    &[("package_name", package), ("key", "camera")],
                )
                .await;
            assert_redirect(
                &res,
                &format!("/devices/{id}/apps/{package}?notice=perms"),
                Some("access"),
            );
        }
        assert_eq!(policy().await, contract["policy"]["seeded"]);
    }

    /// Migration 0166 on a 0165 database: every camera-off row becomes a `camera` denial, the old table gone.
    #[tokio::test]
    async fn migration_0166_copies_the_camera_rows() {
        let dir = tempfile::tempdir().unwrap();
        let options = sqlx::sqlite::SqliteConnectOptions::new()
            .filename(dir.path().join("m.db"))
            .create_if_missing(true)
            .foreign_keys(true);
        let db = SqlitePool::connect_with(options).await.unwrap();
        for migration in crate::MIGRATOR.iter().filter(|m| m.version <= 165) {
            sqlx::raw_sql(&migration.sql).execute(&db).await.unwrap();
        }
        sqlx::raw_sql(
            "INSERT INTO devices (id, name) VALUES (7, 'Anna'); \
             INSERT INTO device_app_permissions (device_id, package_name, camera, updated_at) \
             VALUES (7, 'com.whatsapp', 'deny', '2026-11-01 10:00:00'), (7, 'io.element.android.x', 'deny', \
             '2026-11-02 10:00:00');",
        )
        .execute(&db)
        .await
        .unwrap();
        let migration = crate::MIGRATOR.iter().find(|m| m.version == 166).unwrap();
        sqlx::raw_sql(&migration.sql).execute(&db).await.unwrap();
        let rows: Vec<(i64, String, String, String)> = sqlx::query_as(
            "SELECT device_id, package_name, permission, updated_at FROM device_app_denials ORDER BY package_name",
        )
        .fetch_all(&db)
        .await
        .unwrap();
        assert_eq!(
            rows,
            [
                (
                    7,
                    "com.whatsapp".to_string(),
                    "camera".to_string(),
                    "2026-11-01 10:00:00".to_string()
                ),
                (
                    7,
                    "io.element.android.x".to_string(),
                    "camera".to_string(),
                    "2026-11-02 10:00:00".to_string()
                ),
            ]
        );
        assert!(
            sqlx::query("SELECT 1 FROM device_app_permissions")
                .fetch_optional(&db)
                .await
                .is_err()
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
