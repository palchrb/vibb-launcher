//! The lock's glance page (design 25, `docs/design/25-lock-glance.md` at the monorepo root,
//! migrations/0066_lock_glance.sql). The PIN lock's first page on the phone shows the clock, what's
//! playing and the newest notifications: missed calls always, and messages from the apps the parent
//! ticks here (Element X by default), with only the sender unless the parent switches that app's
//! text on (design 51). The server only stores the ticks and their text flags and sends them as
//! `PolicyResponse.lock_glance`; the phone never sends back a row, a count or a name (only the
//! capability `lock_glance_v1`).
//!
//! Design 82 (`docs/design/82-meldinger-as-an-app.md`): Meldinger - the launcher's built-in SMS app - has its level
//! in the same table under [crate::builtin::MESSAGES], set by the same select. It is no installed package, so it is
//! never in `lock_glance.apps`: the policy carries it as `lock_glance.sms` ([sms_level]).

use std::collections::{BTreeMap, BTreeSet};

use serde::Serialize;
use sqlx::SqlitePool;

/// Ticked for every phone by the migration and the `devices_get_lock_glance_default` trigger (the
/// SQL spells it; the tests check the two agree).
#[cfg(test)]
pub const ELEMENT_X: &str = "io.element.android.x";

/// A launcher that has the glance page reports this capability.
pub const LOCK_GLANCE_CAPABILITY: &str = "lock_glance_v1";

/// Design 82 D6: a launcher that honours `lock_glance.sms`, Off included, and Meldinger's `app_display` entry reports
/// this. Used for the pages' notes only, never to change what is sent.
pub const MESSAGES_APP_CAPABILITY: &str = "messages_app_v1";

/// `lock_glance.sms`'s three words (design 82 D3).
pub const SMS_OFF: &str = "off";
pub const SMS_NAME: &str = "name";
pub const SMS_TEXT: &str = "text";

/// `PolicyResponse.lock_glance` - always sent: the ticked apps (sorted, valid package names only),
/// each with whether its message text shows too (design 51; before it `apps` were strings and one
/// `show_text` was the phone's). An older launcher drops the objects and shows missed calls only
/// until it updates; an older server's strings read as sender only on a new launcher. `sms` is Meldinger's level
/// (design 82 D3, [sms_level]), always sent: a launcher that knows the key reads a missing one as an older server's.
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct LockGlancePolicy {
    pub apps: Vec<LockGlanceApp>,
    pub sms: &'static str,
}

impl LockGlancePolicy {
    /// What a failed read sends (design 82 D3): no apps and Meldinger off - never an absent `sms`.
    pub fn unreadable() -> Self {
        LockGlancePolicy {
            apps: Vec::new(),
            sms: SMS_OFF,
        }
    }

    /// `call_policy.sms_lock_text` for a launcher from before `lock_glance.sms`: true only at Full text.
    pub fn sms_lock_text(&self) -> bool {
        self.sms == SMS_TEXT
    }
}

/// One ticked app of [LockGlancePolicy].
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct LockGlanceApp {
    pub package: String,
    pub show_text: bool,
}

/// A package the page can tick: a real Android package name (the launcher's own grammar,
/// `app_display::valid_package_name`) that isn't Play core (the launcher's business, never listed). Under the
/// reserved prefix exactly Meldinger's key (design 82 D1).
pub fn valid_package(package: &str) -> bool {
    crate::app_display::valid_package_name(package)
        && !crate::play::is_play_core(package)
        && !crate::builtin::is_unknown_reserved(package)
}

/// The policy's `apps`: the stored ticks that are valid package names, sorted, once each. A row
/// written by hand that isn't one is left out (the launcher would drop it anyway), and so is every reserved name:
/// Meldinger's level goes as `sms` ([sms_level]).
pub fn policy_apps(rows: impl IntoIterator<Item = (String, bool)>) -> Vec<LockGlanceApp> {
    rows.into_iter()
        .filter(|(p, _)| {
            crate::app_display::valid_package_name(p) && !crate::builtin::is_reserved(p)
        })
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

/// Meldinger's stored choice among a phone's rows: `None` = Off, else its `show_text`.
pub fn sms_stored(rows: &[(String, bool)]) -> Option<bool> {
    rows.iter()
        .find(|(package, _)| package == crate::builtin::MESSAGES)
        .map(|(_, text)| *text)
}

/// `lock_glance.sms` (design 82 D3) from the stored choice: no row `off`, 0 `name`, 1 `text` - and `text` only while
/// `sms_mode` is `managed` (the only time the parent sees the row), else `name`. The server owns that rule; the
/// phone only reads the word.
pub fn sms_level(stored: Option<bool>, sms_mode: &str) -> &'static str {
    match stored {
        None => SMS_OFF,
        Some(true) if sms_mode == "managed" => SMS_TEXT,
        Some(_) => SMS_NAME,
    }
}

/// The page's word for a stored choice (the select's option): its catalog key.
pub fn level_label(stored: Option<bool>) -> &'static str {
    match stored {
        None => "common.off",
        Some(false) => "device.lock.name_only",
        Some(true) => "device.lock.full_text",
    }
}

/// `PolicyResponse.lock_glance` for a phone: [policy_apps] of its ticks and Meldinger's [sms_level], from one read.
pub async fn policy_lock_glance(
    db: &SqlitePool,
    device_id: i64,
    sms_mode: &str,
) -> Result<LockGlancePolicy, sqlx::Error> {
    let rows = ticked(db, device_id).await?;
    Ok(LockGlancePolicy {
        sms: sms_level(sms_stored(&rows), sms_mode),
        apps: policy_apps(rows),
    })
}

/// What Lock Screen & PINs' Notifications group warns about (design 31 step 4, typed): there is
/// no lock without a Child PIN; a reporting launcher without the capability has no glance page
/// yet; a launcher without notification access shows missed calls only - and Meldinger's rows, which need none
/// (design 82 D7), while its row is listed and not Off.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Note {
    NoChildPin,
    NeedsUpdate,
    NoNotificationAccess,
    NoNotificationAccessButMessages,
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
            Note::NoNotificationAccessButMessages => (
                "device.lock.glance.no_access_messages",
                "device.lock.glance.no_access_messages_sub",
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

/// [messages_shown]: Meldinger's row is listed and not Off.
pub fn notes(
    kid_pin_set: bool,
    reported: bool,
    capable: bool,
    listener_enabled: Option<bool>,
    messages_shown: bool,
) -> Vec<Note> {
    let mut notes = Vec::new();
    if !kid_pin_set {
        notes.push(Note::NoChildPin);
    }
    if reported && !capable {
        notes.push(Note::NeedsUpdate);
    }
    if listener_enabled == Some(false) {
        notes.push(if messages_shown {
            Note::NoNotificationAccessButMessages
        } else {
            Note::NoNotificationAccess
        });
    }
    notes
}

/// The security log's words for a tick (`security.detail.lock_screen_app.*`, design 70 §4):
/// "phone 3: io.element.android.x on the lock screen: on". Meldinger's key never shows (design 82 D7): it has its
/// own catalog keys with no package - "phone 3: Messages on the lock screen: on" - worded in the reader's language.
pub fn log_line(device_id: i64, package: &str, on: bool) -> crate::security::Detail {
    if package == crate::builtin::MESSAGES {
        return switch_line(
            device_id,
            if on {
                "security.detail.lock_screen_messages.on"
            } else {
                "security.detail.lock_screen_messages.off"
            },
            &[],
        );
    }
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
/// the lock screen: on" - Meldinger's again by its own keys.
pub fn text_log_line(device_id: i64, package: &str, on: bool) -> crate::security::Detail {
    if package == crate::builtin::MESSAGES {
        return switch_line(
            device_id,
            if on {
                "security.detail.lock_screen_messages_text.on"
            } else {
                "security.detail.lock_screen_messages_text.off"
            },
            &[],
        );
    }
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

/// The same for the buzz switch (design 82 W3): "phone 3: buzz for a notification: on".
pub fn buzz_log_line(device_id: i64, on: bool) -> crate::security::Detail {
    switch_line(
        device_id,
        if on {
            "security.detail.lock_buzz.on"
        } else {
            "security.detail.lock_buzz.off"
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
    /// A line under the label that says nothing of the list (design 82 D7: Meldinger's "update Vibb One"): the row
    /// stays listed, and its saves return to it.
    pub hint: Option<&'static str>,
}

/// Meldinger's row (design 82 D7), the group's first while SMS is managed: its stored choice, and with Off stored on
/// a phone that has reported without [MESSAGES_APP_CAPABILITY] the line that it still shows the name.
pub fn messages_row(
    label: String,
    stored: Option<bool>,
    reported: bool,
    capable: bool,
) -> LockScreenApp {
    LockScreenApp {
        package_name: crate::builtin::MESSAGES.to_string(),
        label,
        on: stored.is_some(),
        show_text: stored == Some(true),
        note: None,
        hint: (stored.is_none() && reported && !capable).then_some(MESSAGES_OFF_NEEDS_UPDATE),
    }
}

/// [messages_row]'s hint.
pub const MESSAGES_OFF_NEEDS_UPDATE: &str = "device.lock.glance.messages_off_needs_update";

/// The subsection's apps: the [listed] ones in their order (the allowed apps, or with no allowlist
/// every app the phone reports - QA Low 7) with their ticks, then every ticked package that isn't
/// listed (sorted) with [unlisted_note] - so a tick never becomes invisible. Invalid, Play-core or reserved
/// packages are never listed (Meldinger's row is [messages_row]). [ticked] maps each tick to its text flag.
pub fn card_apps(
    listed: impl IntoIterator<Item = (String, String)>,
    ticked: &BTreeMap<String, bool>,
    allowlist: Option<&BTreeSet<String>>,
) -> Vec<LockScreenApp> {
    let listable = |package: &str| valid_package(package) && !crate::builtin::is_reserved(package);
    let mut seen = BTreeSet::new();
    let mut apps: Vec<LockScreenApp> = listed
        .into_iter()
        .filter(|(package, _)| listable(package) && seen.insert(package.clone()))
        .map(|(package_name, label)| LockScreenApp {
            on: ticked.contains_key(&package_name),
            show_text: ticked.get(&package_name) == Some(&true),
            package_name,
            label,
            note: None,
            hint: None,
        })
        .collect();
    apps.extend(
        ticked
            .iter()
            .filter(|(p, _)| listable(p) && !seen.contains(*p))
            .map(|(package, show_text)| LockScreenApp {
                package_name: package.clone(),
                label: package.clone(),
                on: true,
                show_text: *show_text,
                note: Some(unlisted_note(allowlist, package)),
                hint: None,
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
        // Design 82 D1: under the reserved prefix exactly Meldinger's key.
        assert!(valid_package(crate::builtin::MESSAGES));
        assert!(!valid_package("me.vibb.builtin.other"));
        assert!(!valid_package("me.vibb.builtin.messages.x"));
    }

    #[test]
    fn the_policy_apps_are_sorted_valid_and_unique() {
        let rows = [
            ("org.b.app", true),
            ("not a package", true),
            (ELEMENT_X, false),
            ("org.b.app", true),
            ("com.a.app", false),
            // Design 82: Meldinger's level goes as `sms`, never as an app.
            (crate::builtin::MESSAGES, true),
            ("me.vibb.builtin.other", true),
        ]
        .map(|(p, text)| (p.to_string(), text));
        assert_eq!(sms_stored(&rows), Some(true));
        assert_eq!(sms_stored(&rows[..5]), None);
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
        assert!(notes(true, true, true, Some(true), false).is_empty());
        assert!(
            notes(true, false, false, None, false).is_empty(),
            "no report yet"
        );
        let mut all = notes(false, true, false, Some(false), false);
        assert_eq!(
            all,
            [
                Note::NoChildPin,
                Note::NeedsUpdate,
                Note::NoNotificationAccess
            ]
        );
        assert_eq!(notes(true, true, false, None, false), [Note::NeedsUpdate]);
        assert_eq!(
            notes(true, true, true, Some(false), false),
            [Note::NoNotificationAccess]
        );
        // Design 82 D7: Meldinger's rows need no notification access - the note says so only while its row shows.
        assert_eq!(
            notes(true, true, true, Some(false), true),
            [Note::NoNotificationAccessButMessages]
        );
        assert!(notes(true, true, true, Some(true), true).is_empty());
        all.push(Note::NoNotificationAccessButMessages);
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
            crate::security::worded_in(&buzz_log_line(3, false), crate::i18n::Lang::Nb),
            "telefon 3: vibrer ved varsel: av"
        );
        assert_eq!(
            log_line(3, ELEMENT_X, true).columns().0.as_deref(),
            Some("device 3")
        );
        // Design 82 D7: Meldinger's key never shows - its own lines, in the reader's language.
        let nb = |detail| crate::security::worded_in(&detail, crate::i18n::Lang::Nb);
        let key = crate::builtin::MESSAGES;
        assert_eq!(
            worded(&log_line(3, key, true)),
            "phone 3: Messages on the lock screen: on"
        );
        assert_eq!(
            nb(log_line(3, key, false)),
            "telefon 3: Meldinger på låseskjermen: av"
        );
        assert_eq!(
            worded(&text_log_line(3, key, false)),
            "phone 3: Messages' text on the lock screen: off"
        );
        assert_eq!(
            nb(text_log_line(3, key, true)),
            "telefon 3: meldingsteksten til Meldinger på låseskjermen: på"
        );
    }

    /// Design 82 D7: Meldinger's row - its stored choice, and the update line only for Off on a phone that has
    /// reported without the capability. Never `note`: the row is always listed.
    #[test]
    fn meldingers_row() {
        let row =
            |stored, reported, capable| messages_row("Messages".into(), stored, reported, capable);
        let off = row(None, true, false);
        assert_eq!(off.package_name, crate::builtin::MESSAGES);
        assert_eq!((off.on, off.show_text, off.note), (false, false, None));
        assert_eq!(off.hint, Some(MESSAGES_OFF_NEEDS_UPDATE));
        assert_eq!(row(None, true, true).hint, None);
        assert_eq!(row(None, false, false).hint, None, "no report yet");
        let name = row(Some(false), true, false);
        assert_eq!((name.on, name.show_text, name.hint), (true, false, None));
        let text = row(Some(true), true, false);
        assert_eq!((text.on, text.show_text, text.hint), (true, true, None));
        assert_eq!(
            [None, Some(false), Some(true)].map(level_label),
            [
                "common.off",
                "device.lock.name_only",
                "device.lock.full_text"
            ]
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
            // Design 82: Meldinger's row is `messages_row`'s, never a listed app or an unlisted tick.
            (crate::builtin::MESSAGES.to_string(), "Fake".to_string()),
        ];
        let ticked: BTreeMap<String, bool> = [
            (ELEMENT_X, true),
            // Listed and ticked with its text off (qa-r7-b-code L3).
            ("org.signal", false),
            ("com.old.chat", false),
            ("com.google.android.gms", true),
            ("com.allowed.later", false),
            (crate::builtin::MESSAGES, true),
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
                    hint: None,
                },
                LockScreenApp {
                    package_name: ELEMENT_X.to_string(),
                    label: "Element X".to_string(),
                    on: true,
                    show_text: true,
                    note: None,
                    hint: None,
                },
                LockScreenApp {
                    package_name: "com.allowed.later".to_string(),
                    label: "com.allowed.later".to_string(),
                    on: true,
                    show_text: false,
                    note: Some(NOT_ON_PHONE_TICKED),
                    hint: None,
                },
                LockScreenApp {
                    package_name: "com.old.chat".to_string(),
                    label: "com.old.chat".to_string(),
                    on: true,
                    show_text: false,
                    note: Some(NOT_ALLOWED_TICKED),
                    hint: None,
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
