//! The lock's glance page (design 25, `docs/design/25-lock-glance.md` at the monorepo root,
//! migrations/0066_lock_glance.sql). The PIN lock's first page on the phone shows the clock, what's
//! playing and the newest notifications: missed calls always, and messages from the apps the parent
//! ticks here (Element X by default), with only the sender unless the parent switches that app's
//! text on (design 51). The server only stores the ticks and their text flags and sends them as
//! `PolicyResponse.lock_glance`; the phone never sends back a row, a count or a name (only the
//! capability `lock_glance_v1`).

use std::collections::{BTreeMap, BTreeSet};

use serde::Serialize;
use sqlx::SqlitePool;

/// Ticked for every phone by the migration and the `devices_get_lock_glance_default` trigger (the
/// SQL spells it; the tests check the two agree).
#[cfg(test)]
pub const ELEMENT_X: &str = "io.element.android.x";

/// A launcher that has the glance page reports this capability.
pub const LOCK_GLANCE_CAPABILITY: &str = "lock_glance_v1";

/// `PolicyResponse.lock_glance` - always sent: the ticked apps (sorted, valid package names only),
/// each with whether its message text shows too (design 51; before it `apps` were strings and one
/// `show_text` was the phone's). An older launcher drops the objects and shows missed calls only
/// until it updates; an older server's strings read as sender only on a new launcher.
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct LockGlancePolicy {
    pub apps: Vec<LockGlanceApp>,
}

/// One ticked app of [LockGlancePolicy].
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct LockGlanceApp {
    pub package: String,
    pub show_text: bool,
}

/// A package the page can tick: a real Android package name (the launcher's own grammar,
/// `app_display::valid_package_name`) that isn't Play core (the launcher's business, never listed).
pub fn valid_package(package: &str) -> bool {
    crate::app_display::valid_package_name(package) && !crate::play::is_play_core(package)
}

/// The policy's `apps`: the stored ticks that are valid package names, sorted, once each. A row
/// written by hand that isn't one is left out (the launcher would drop it anyway).
pub fn policy_apps(rows: impl IntoIterator<Item = (String, bool)>) -> Vec<LockGlanceApp> {
    rows.into_iter()
        .filter(|(p, _)| crate::app_display::valid_package_name(p))
        .collect::<BTreeMap<_, _>>()
        .into_iter()
        .map(|(package, show_text)| LockGlanceApp { package, show_text })
        .collect()
}

/// Every ticked package of a phone, as stored, with its text flag.
pub async fn ticked(db: &SqlitePool, device_id: i64) -> Result<Vec<(String, bool)>, sqlx::Error> {
    sqlx::query_as(
        "SELECT package_name, show_text FROM device_lock_glance_apps WHERE device_id = ? \
         ORDER BY package_name",
    )
    .bind(device_id)
    .fetch_all(db)
    .await
}

/// `PolicyResponse.lock_glance` for a phone ([policy_apps] of its ticks).
pub async fn policy_lock_glance(
    db: &SqlitePool,
    device_id: i64,
) -> Result<LockGlancePolicy, sqlx::Error> {
    Ok(LockGlancePolicy {
        apps: policy_apps(ticked(db, device_id).await?),
    })
}

/// What Lock Screen & PINs' Notifications group warns about (design 31 step 4, typed): there is
/// no lock without a Child PIN; a reporting launcher without the capability has no glance page
/// yet; a launcher without notification access shows missed calls only.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Note {
    NoChildPin,
    NeedsUpdate,
    NoNotificationAccess,
}

impl Note {
    /// Its words in `lang`.
    pub fn note(self, lang: crate::i18n::Lang) -> crate::handlers::device_pages::Note {
        let (title, detail) = match self {
            Note::NoChildPin => (
                "device.lock.glance.no_child_pin",
                "device.lock.glance.no_child_pin_sub",
            ),
            Note::NeedsUpdate => (
                "device.lock.glance.needs_update",
                "device.lock.glance.needs_update_sub",
            ),
            Note::NoNotificationAccess => (
                "device.lock.glance.no_access",
                "device.lock.glance.no_access_sub",
            ),
        };
        crate::handlers::device_pages::Note {
            warning: true,
            title: crate::i18n::t(lang, title).to_string(),
            value: None,
            detail: Some(crate::i18n::t(lang, detail).to_string()),
            fix: None,
        }
    }
}

pub fn notes(
    kid_pin_set: bool,
    reported: bool,
    capable: bool,
    listener_enabled: Option<bool>,
) -> Vec<Note> {
    let mut notes = Vec::new();
    if !kid_pin_set {
        notes.push(Note::NoChildPin);
    }
    if reported && !capable {
        notes.push(Note::NeedsUpdate);
    }
    if listener_enabled == Some(false) {
        notes.push(Note::NoNotificationAccess);
    }
    notes
}

/// The security log's words for a tick (`security.detail.lock_screen_app.*`, design 70 §4):
/// "phone 3: io.element.android.x on the lock screen: on".
pub fn log_line(device_id: i64, package: &str, on: bool) -> crate::security::Detail {
    switch_line(
        device_id,
        if on {
            "security.detail.lock_screen_app.on"
        } else {
            "security.detail.lock_screen_app.off"
        },
        &[("package", package)],
    )
}

/// The same for an app's text switch (design 51): "phone 3: io.element.android.x's message text on
/// the lock screen: on".
pub fn text_log_line(device_id: i64, package: &str, on: bool) -> crate::security::Detail {
    switch_line(
        device_id,
        if on {
            "security.detail.lock_screen_text.on"
        } else {
            "security.detail.lock_screen_text.off"
        },
        &[("package", package)],
    )
}

/// The same for the wake switch (launcher round 7 §3): "phone 3: wake the screen for a
/// notification: on".
pub fn wake_log_line(device_id: i64, on: bool) -> crate::security::Detail {
    switch_line(
        device_id,
        if on {
            "security.detail.lock_wake.on"
        } else {
            "security.detail.lock_wake.off"
        },
        &[],
    )
}

fn switch_line(device_id: i64, key: &str, params: &[(&str, &str)]) -> crate::security::Detail {
    let device = device_id.to_string();
    let mut all = vec![("device", device.as_str())];
    all.extend_from_slice(params);
    crate::security::Detail::words(key, &all).with_facts(format!("device {device_id}"))
}

// ---- the device page ----------------------------------------------------------------------------

/// Lock Screen & PINs' Notifications group (the device page's Apps card's "On the lock screen"
/// before design 31 step 4).
#[derive(Default)]
pub struct LockScreenCard {
    pub notes: Vec<Note>,
    pub apps: Vec<LockScreenApp>,
    /// The ticks couldn't be read: the subsection says so instead of showing every box unticked.
    pub unreadable: bool,
    /// What the subsection says when [apps] is empty ([empty_text]).
    pub empty_text: &'static str,
}

/// One app's mini-form.
#[derive(Debug, PartialEq, Eq)]
pub struct LockScreenApp {
    pub package_name: String,
    pub label: String,
    pub on: bool,
    /// Ticked with its message text shown (design 51; the switch shows on ticked rows only).
    pub show_text: bool,
    /// Ticked but not in the listed apps ("Not allowed", under its switch that is on), so any tick
    /// can be removed.
    pub note: Option<&'static str>,
}

/// The subsection's apps: the [listed] ones in their order (the allowed apps, or with no allowlist
/// every app the phone reports - QA Low 7) with their ticks, then every ticked package that isn't
/// listed (sorted) with [unlisted_note] - so a tick never becomes invisible. Invalid or Play-core
/// packages are never listed. [ticked] maps each tick to its text flag.
pub fn card_apps(
    listed: impl IntoIterator<Item = (String, String)>,
    ticked: &BTreeMap<String, bool>,
    allowlist: Option<&BTreeSet<String>>,
) -> Vec<LockScreenApp> {
    let mut seen = BTreeSet::new();
    let mut apps: Vec<LockScreenApp> = listed
        .into_iter()
        .filter(|(package, _)| valid_package(package) && seen.insert(package.clone()))
        .map(|(package_name, label)| LockScreenApp {
            on: ticked.contains_key(&package_name),
            show_text: ticked.get(&package_name) == Some(&true),
            package_name,
            label,
            note: None,
        })
        .collect();
    apps.extend(
        ticked
            .iter()
            .filter(|(p, _)| valid_package(p) && !seen.contains(*p))
            .map(|(package, show_text)| LockScreenApp {
                package_name: package.clone(),
                label: package.clone(),
                on: true,
                show_text: *show_text,
                note: Some(unlisted_note(allowlist, package)),
            }),
    );
    apps
}

/// [card_apps]'s note for a ticked package outside the list (code QA Low 6): "not allowed" only when
/// the phone has an allowlist ([allowlist]) without it; otherwise - no allowlist, or allowed but neither
/// reported installed nor a selected catalog app - it isn't on the phone.
pub fn unlisted_note(allowlist: Option<&BTreeSet<String>>, package: &str) -> &'static str {
    match allowlist {
        Some(allowed) if !allowed.contains(package) => NOT_ALLOWED_TICKED,
        _ => NOT_ON_PHONE_TICKED,
    }
}

/// The notes' keys (the page words them).
pub const NOT_ALLOWED_TICKED: &str = "device.lock.glance.not_allowed";
pub const NOT_ON_PHONE_TICKED: &str = "device.lock.glance.not_on_phone";

/// The group's text when it has no app to list (code QA Low 6): the phone hasn't reported its
/// apps yet, or it has but none is allowed (an allowlist) or listed - its key.
pub fn empty_text(reported: bool, has_allowlist: bool) -> &'static str {
    match (reported, has_allowlist) {
        (false, _) => "device.lock.glance.empty_unreported",
        (true, true) => "device.lock.glance.empty_none_allowed",
        (true, false) => "device.lock.glance.empty_none",
    }
}

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
            ("org.b.app", true),
            ("not a package", true),
            (ELEMENT_X, false),
            ("org.b.app", true),
            ("com.a.app", false),
        ]
        .map(|(p, text)| (p.to_string(), text));
        let app = |package: &str, show_text| LockGlanceApp {
            package: package.to_string(),
            show_text,
        };
        assert_eq!(
            policy_apps(rows),
            [
                app("com.a.app", false),
                app(ELEMENT_X, false),
                app("org.b.app", true)
            ]
        );
        assert!(policy_apps(Vec::new()).is_empty());
    }

    #[test]
    fn every_note() {
        assert!(notes(true, true, true, Some(true)).is_empty());
        assert!(notes(true, false, false, None).is_empty(), "no report yet");
        let all = notes(false, true, false, Some(false));
        assert_eq!(
            all,
            [
                Note::NoChildPin,
                Note::NeedsUpdate,
                Note::NoNotificationAccess
            ]
        );
        assert_eq!(notes(true, true, false, None), [Note::NeedsUpdate]);
        assert_eq!(
            notes(true, true, true, Some(false)),
            [Note::NoNotificationAccess]
        );
        for note in all {
            let words = note.note(crate::i18n::Lang::En);
            assert!(words.warning);
            // §4.2: a title of up to 6 words and one sentence.
            assert!(words.title.split_whitespace().count() <= 6, "{note:?}");
            let detail = words.detail.unwrap();
            assert!(
                !detail.contains("kid") && !detail.contains("adb"),
                "{detail}"
            );
        }
    }

    #[test]
    fn the_log_lines() {
        let worded = crate::security::worded_test;
        assert_eq!(
            worded(&log_line(3, ELEMENT_X, true)),
            "phone 3: io.element.android.x on the lock screen: on"
        );
        assert_eq!(
            worded(&log_line(3, ELEMENT_X, false)),
            "phone 3: io.element.android.x on the lock screen: off"
        );
        assert_eq!(
            worded(&text_log_line(7, ELEMENT_X, true)),
            "phone 7: io.element.android.x's message text on the lock screen: on"
        );
        assert_eq!(
            worded(&text_log_line(7, ELEMENT_X, false)),
            "phone 7: io.element.android.x's message text on the lock screen: off"
        );
        assert_eq!(
            crate::security::worded_in(&wake_log_line(3, true), crate::i18n::Lang::Nb),
            "telefon 3: vekk skjermen ved varsel: på"
        );
        assert_eq!(
            log_line(3, ELEMENT_X, true).columns().0.as_deref(),
            Some("device 3")
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
        let ticked: BTreeMap<String, bool> = [
            (ELEMENT_X, true),
            // Listed and ticked with its text off (qa-r7-b-code L3).
            ("org.signal", false),
            ("com.old.chat", false),
            ("com.google.android.gms", true),
            ("com.allowed.later", false),
        ]
        .map(|(p, text)| (p.to_string(), text))
        .into();
        let allowed: BTreeSet<String> = ["org.signal", ELEMENT_X, "com.allowed.later"]
            .map(str::to_string)
            .into();
        let apps = card_apps(listed.clone(), &ticked, Some(&allowed));
        assert_eq!(
            apps,
            [
                LockScreenApp {
                    package_name: "org.signal".to_string(),
                    label: "Signal".to_string(),
                    on: true,
                    show_text: false,
                    note: None,
                },
                LockScreenApp {
                    package_name: ELEMENT_X.to_string(),
                    label: "Element X".to_string(),
                    on: true,
                    show_text: true,
                    note: None,
                },
                LockScreenApp {
                    package_name: "com.allowed.later".to_string(),
                    label: "com.allowed.later".to_string(),
                    on: true,
                    show_text: false,
                    note: Some(NOT_ON_PHONE_TICKED),
                },
                LockScreenApp {
                    package_name: "com.old.chat".to_string(),
                    label: "com.old.chat".to_string(),
                    on: true,
                    show_text: false,
                    note: Some(NOT_ALLOWED_TICKED),
                },
            ]
        );
        // Without an allowlist nothing is "not allowed".
        let open = card_apps(listed, &ticked, None);
        assert!(
            open.iter()
                .filter(|a| a.note.is_some())
                .all(|a| a.note == Some(NOT_ON_PHONE_TICKED))
        );
    }

    #[test]
    fn the_empty_subsection_says_why() {
        let en =
            |reported, listed| crate::i18n::t(crate::i18n::Lang::En, empty_text(reported, listed));
        assert!(en(false, true).contains("hasn't reported its apps"));
        assert!(en(false, false).contains("hasn't reported its apps"));
        assert!(en(true, true).contains("No allowed apps"));
        assert!(!en(true, false).contains("hasn't reported"));
    }
}
