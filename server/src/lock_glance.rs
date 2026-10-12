//! The lock's glance page (design 25, `docs/design/25-lock-glance.md` at the monorepo root,
//! migrations/0066_lock_glance.sql). The PIN lock's first page on the phone shows the clock, what's
//! playing and the newest notifications: missed calls always, and messages from the apps the parent
//! ticks here (Element X by default), with only the sender unless the parent switches the text on.
//! The server only stores the ticks and the switch and sends them as `PolicyResponse.lock_glance`;
//! the phone never sends back a row, a count or a name (only the capability `lock_glance_v1`).

use std::collections::BTreeSet;

use serde::Serialize;
use sqlx::SqlitePool;

/// Ticked for every phone by the migration and the `devices_get_lock_glance_default` trigger (the
/// SQL spells it; the tests check the two agree).
#[cfg(test)]
pub const ELEMENT_X: &str = "io.element.android.x";

/// A launcher that has the glance page reports this capability.
pub const LOCK_GLANCE_CAPABILITY: &str = "lock_glance_v1";

/// `PolicyResponse.lock_glance` - always sent: the ticked apps (sorted, valid package names only)
/// and whether the message text shows too. An older launcher ignores the key; an older server's
/// missing key reads as `{apps: [Element X], show_text: false}` on the phone (the same default).
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct LockGlancePolicy {
    pub apps: Vec<String>,
    pub show_text: bool,
}

/// A package the page can tick: a real Android package name (the launcher's own grammar,
/// `app_display::valid_package_name`) that isn't Play core (the launcher's business, never listed).
pub fn valid_package(package: &str) -> bool {
    crate::app_display::valid_package_name(package) && !crate::play::is_play_core(package)
}

/// The policy's `apps`: the stored ticks that are valid package names, sorted, once each. A row
/// written by hand that isn't one is left out (the launcher would drop it anyway).
pub fn policy_apps(rows: impl IntoIterator<Item = String>) -> Vec<String> {
    rows.into_iter()
        .filter(|p| crate::app_display::valid_package_name(p))
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect()
}

/// Every ticked package of a phone, as stored.
pub async fn ticked(db: &SqlitePool, device_id: i64) -> Result<Vec<String>, sqlx::Error> {
    sqlx::query_scalar(
        "SELECT package_name FROM device_lock_glance_apps WHERE device_id = ? ORDER BY package_name",
    )
    .bind(device_id)
    .fetch_all(db)
    .await
}

/// `PolicyResponse.lock_glance` for a phone ([policy_apps] of its ticks, and its switch).
pub async fn policy_lock_glance(
    db: &SqlitePool,
    device_id: i64,
    show_text: bool,
) -> Result<LockGlancePolicy, sqlx::Error> {
    Ok(LockGlancePolicy {
        apps: policy_apps(ticked(db, device_id).await?),
        show_text,
    })
}

/// What the parent should know (error style, in the "On the lock screen" subsection): there is no
/// lock without a kid PIN; a launcher without notification access shows missed calls only; a
/// reporting launcher without the capability has no glance page yet.
pub fn notes(
    kid_pin_set: bool,
    reported: bool,
    capable: bool,
    listener_enabled: Option<bool>,
) -> Vec<String> {
    let mut notes = Vec::new();
    if !kid_pin_set {
        notes.push(
            "There is no lock screen without a kid PIN - see Screen lock. The phone then opens \
             straight to its home screen and shows nothing of this."
                .to_string(),
        );
    }
    if reported && !capable {
        notes.push(
            "This phone's launcher doesn't show notifications on its lock screen yet - update the \
             launcher."
                .to_string(),
        );
    }
    if listener_enabled == Some(false) {
        notes.push(
            "The launcher can't read notifications, so the lock screen shows missed calls only; \
             see Launcher for the fix."
                .to_string(),
        );
    }
    notes
}

/// The security log's detail for a tick: "device 3: io.element.android.x on the lock screen: on".
pub fn log_line(device_id: i64, package: &str, on: bool) -> String {
    format!(
        "device {device_id}: {package} on the lock screen: {}",
        on_off(on)
    )
}

/// The security log's detail for the text switch: "device 3: message text on the lock screen: on".
pub fn text_log_line(device_id: i64, on: bool) -> String {
    format!(
        "device {device_id}: message text on the lock screen: {}",
        on_off(on)
    )
}

fn on_off(on: bool) -> &'static str {
    if on { "on" } else { "off" }
}

// ---- the device page ----------------------------------------------------------------------------

/// The "On the lock screen" subsection of the device page's Apps card.
#[derive(Default)]
pub struct LockScreenCard {
    pub show_text: bool,
    pub notes: Vec<String>,
    pub apps: Vec<LockScreenApp>,
    /// The ticks couldn't be read: the subsection says so instead of showing every box unticked.
    pub unreadable: bool,
}

/// One app's mini-form.
#[derive(Debug, PartialEq, Eq)]
pub struct LockScreenApp {
    pub package_name: String,
    pub label: String,
    pub on: bool,
    /// Ticked but not in the listed apps ("not allowed - ticked"), so any tick can be removed.
    pub note: Option<&'static str>,
}

/// The subsection's apps: the [listed] ones in their order (the allowed apps, or with no allowlist
/// every app the phone reports - QA Low 7) with their ticks, then every ticked package that isn't
/// listed (sorted) with [unlisted_note] - so a tick never becomes invisible. Invalid or Play-core
/// packages are never listed.
pub fn card_apps(
    listed: impl IntoIterator<Item = (String, String)>,
    ticked: &BTreeSet<String>,
    unlisted_note: &'static str,
) -> Vec<LockScreenApp> {
    let mut seen = BTreeSet::new();
    let mut apps: Vec<LockScreenApp> = listed
        .into_iter()
        .filter(|(package, _)| valid_package(package) && seen.insert(package.clone()))
        .map(|(package_name, label)| LockScreenApp {
            on: ticked.contains(&package_name),
            package_name,
            label,
            note: None,
        })
        .collect();
    apps.extend(
        ticked
            .iter()
            .filter(|p| valid_package(p) && !seen.contains(*p))
            .map(|package| LockScreenApp {
                package_name: package.clone(),
                label: package.clone(),
                on: true,
                note: Some(unlisted_note),
            }),
    );
    apps
}

/// [card_apps]'s note for a ticked package outside the list: with an allowlist it isn't allowed;
/// without one it isn't on the phone (every reported app is listed then).
pub const NOT_ALLOWED_TICKED: &str = "not allowed - ticked";
pub const NOT_ON_PHONE_TICKED: &str = "not on the phone - ticked";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn package_rules() {
        assert!(valid_package(ELEMENT_X));
        assert!(valid_package("org.thoughtcrime.securesms"));
        assert!(valid_package("com.example.app_2"));
        for bad in [
            "",
            "element",
            ".io.element",
            "io.element.",
            "io..element",
            "io.2element",
            "io.element-x",
            "io.element.x;drop",
            "io.élément.x",
        ] {
            assert!(!valid_package(bad), "{bad}");
        }
        assert!(!valid_package(&format!("a.{}", "b".repeat(255))));
        // Play core is the launcher's business.
        for play in [
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.gsf",
        ] {
            assert!(!valid_package(play), "{play}");
        }
    }

    #[test]
    fn the_policy_apps_are_sorted_valid_and_unique() {
        let rows = [
            "org.b.app",
            "not a package",
            ELEMENT_X,
            "org.b.app",
            "com.a.app",
        ]
        .map(str::to_string);
        assert_eq!(policy_apps(rows), ["com.a.app", ELEMENT_X, "org.b.app"]);
        assert!(policy_apps(Vec::new()).is_empty());
    }

    #[test]
    fn every_note() {
        assert!(notes(true, true, true, Some(true)).is_empty());
        assert!(notes(true, false, false, None).is_empty(), "no report yet");
        let all = notes(false, true, false, Some(false));
        assert_eq!(all.len(), 3);
        assert!(all[0].starts_with("There is no lock screen without a kid PIN - see Screen lock"));
        assert!(all[1].ends_with("update the launcher."));
        assert!(all[2].contains("shows missed calls only; see Launcher for the fix"));
        assert_eq!(notes(true, true, false, None).len(), 1);
        assert_eq!(notes(true, true, true, Some(false)).len(), 1);
    }

    #[test]
    fn the_log_lines() {
        assert_eq!(
            log_line(3, ELEMENT_X, true),
            "device 3: io.element.android.x on the lock screen: on"
        );
        assert_eq!(
            log_line(3, ELEMENT_X, false),
            "device 3: io.element.android.x on the lock screen: off"
        );
        assert_eq!(
            text_log_line(7, true),
            "device 7: message text on the lock screen: on"
        );
        assert_eq!(
            text_log_line(7, false),
            "device 7: message text on the lock screen: off"
        );
    }

    #[test]
    fn the_listed_apps_then_every_unlisted_tick() {
        let listed = [
            ("org.signal".to_string(), "Signal".to_string()),
            (ELEMENT_X.to_string(), "Element X".to_string()),
            ("com.android.vending".to_string(), "Play".to_string()),
            ("bad".to_string(), "Bad".to_string()),
            (ELEMENT_X.to_string(), "Twice".to_string()),
        ];
        let ticked: BTreeSet<String> = [ELEMENT_X, "com.old.chat", "com.google.android.gms"]
            .map(str::to_string)
            .into();
        let apps = card_apps(listed, &ticked, NOT_ALLOWED_TICKED);
        assert_eq!(
            apps,
            [
                LockScreenApp {
                    package_name: "org.signal".to_string(),
                    label: "Signal".to_string(),
                    on: false,
                    note: None,
                },
                LockScreenApp {
                    package_name: ELEMENT_X.to_string(),
                    label: "Element X".to_string(),
                    on: true,
                    note: None,
                },
                LockScreenApp {
                    package_name: "com.old.chat".to_string(),
                    label: "com.old.chat".to_string(),
                    on: true,
                    note: Some(NOT_ALLOWED_TICKED),
                },
            ]
        );
    }
}
