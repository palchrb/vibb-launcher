//! Sound mode (design 18, `docs/design/18-sound-mode.md` at the monorepo root). The kid's Settings
//! page has a sound row (quick-controls bit 8: Sound / Silent (vibrate) - never Android's Silent,
//! which is Do Not Disturb), and every status report carries the phone's ringer mode and its
//! interruption filter (DND), so About This Phone can say why a call wasn't heard (design 31:
//! typed values, [state]; Do Not Disturb is also an Attention row).

use crate::i18n::{Lang, t};

/// The latest report's `ringer_mode`, kept only if it is one the launcher sends; anything else
/// (or a missing field from an older launcher) is stored as NULL.
pub fn sanitize_ringer_mode(value: Option<&str>) -> Option<&'static str> {
    match value? {
        "normal" => Some("normal"),
        "vibrate" => Some("vibrate"),
        "silent" => Some("silent"),
        _ => None,
    }
}

/// The latest report's `interruption_filter` (`NotificationManager.getCurrentInterruptionFilter`),
/// kept only if known.
pub fn sanitize_interruption_filter(value: Option<&str>) -> Option<&'static str> {
    match value? {
        "all" => Some("all"),
        "priority" => Some("priority"),
        "none" => Some("none"),
        "alarms" => Some("alarms"),
        _ => None,
    }
}

/// The ringer at the last sync (`AudioManager` ringer mode).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Ringer {
    Normal,
    Vibrate,
    Silent,
}

impl Ringer {
    /// The row's value on About This Phone, in `lang`.
    pub fn label(self, lang: Lang) -> &'static str {
        t(
            lang,
            match self {
                Ringer::Normal => "common.on",
                Ringer::Vibrate => "device.about.ringer.vibrate",
                Ringer::Silent => "device.about.ringer.silent",
            },
        )
    }

    /// The ⓘ under the row, for a ringer that isn't on.
    pub fn detail(self, lang: Lang) -> Option<&'static str> {
        match self {
            Ringer::Normal => None,
            Ringer::Vibrate => Some(t(lang, "device.about.ringer.vibrate_sub")),
            Ringer::Silent => Some(t(lang, "device.about.ringer.silent_sub")),
        }
    }
}

/// Do Not Disturb at the last sync (the interruption filter).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Dnd {
    Off,
    PriorityOnly,
    AlarmsOnly,
    TotalSilence,
}

impl Dnd {
    pub fn is_on(self) -> bool {
        self != Dnd::Off
    }

    pub fn label(self, lang: Lang) -> &'static str {
        t(
            lang,
            match self {
                Dnd::Off => "common.off",
                Dnd::PriorityOnly => "device.about.dnd.priority",
                Dnd::AlarmsOnly => "device.about.dnd.alarms",
                Dnd::TotalSilence => "device.about.dnd.none",
            },
        )
    }

    /// The ⓘ under the row while it is on.
    pub fn detail(self, lang: Lang) -> Option<&'static str> {
        Some(t(
            lang,
            match self {
                Dnd::Off => return None,
                Dnd::PriorityOnly => "device.about.dnd.priority_sub",
                Dnd::AlarmsOnly => "device.about.dnd.alarms_sub",
                Dnd::TotalSilence => "device.about.dnd.none_sub",
            },
        ))
    }
}

/// The sound at the last sync (design 31 M5: the typed values About's rows show). `None` for what
/// the phone didn't report, or reported as something unknown.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct SoundState {
    pub ringer: Option<Ringer>,
    pub dnd: Option<Dnd>,
}

/// The latest report's ringer mode and interruption filter, typed.
pub fn state(ringer_mode: Option<&str>, interruption_filter: Option<&str>) -> SoundState {
    SoundState {
        ringer: match sanitize_ringer_mode(ringer_mode) {
            Some("normal") => Some(Ringer::Normal),
            Some("vibrate") => Some(Ringer::Vibrate),
            Some("silent") => Some(Ringer::Silent),
            _ => None,
        },
        dnd: match sanitize_interruption_filter(interruption_filter) {
            Some("all") => Some(Dnd::Off),
            Some("priority") => Some(Dnd::PriorityOnly),
            Some("alarms") => Some(Dnd::AlarmsOnly),
            Some("none") => Some(Dnd::TotalSilence),
            _ => None,
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_known_values_are_kept() {
        for value in ["normal", "vibrate", "silent"] {
            assert_eq!(sanitize_ringer_mode(Some(value)), Some(value));
        }
        for value in ["all", "priority", "none", "alarms"] {
            assert_eq!(sanitize_interruption_filter(Some(value)), Some(value));
        }
        for bad in ["", "NORMAL", "loud", "normal ", "<b>"] {
            assert_eq!(sanitize_ringer_mode(Some(bad)), None);
            assert_eq!(sanitize_interruption_filter(Some(bad)), None);
        }
        assert_eq!(sanitize_ringer_mode(None), None);
        assert_eq!(sanitize_interruption_filter(None), None);
    }

    #[test]
    fn the_state_is_what_the_phone_reported() {
        assert_eq!(state(None, None), SoundState::default());
        assert_eq!(state(Some("bogus"), Some("bogus")), SoundState::default());
        let on = state(Some("normal"), Some("all"));
        assert_eq!(on.ringer, Some(Ringer::Normal));
        assert_eq!(on.dnd, Some(Dnd::Off));
        assert!(!Dnd::Off.is_on());
        assert_eq!(Ringer::Normal.label(Lang::En), "On");
        assert_eq!(Ringer::Normal.detail(Lang::En), None);
        assert_eq!(Dnd::Off.label(Lang::En), "Off");
        assert_eq!(Dnd::Off.detail(Lang::En), None);
        let quiet = state(Some("vibrate"), Some("priority"));
        assert_eq!(quiet.ringer, Some(Ringer::Vibrate));
        assert_eq!(quiet.dnd, Some(Dnd::PriorityOnly));
        assert_eq!(state(Some("silent"), None).ringer, Some(Ringer::Silent));
        assert_eq!(state(None, Some("none")).dnd, Some(Dnd::TotalSilence));
        assert_eq!(state(None, Some("alarms")).dnd, Some(Dnd::AlarmsOnly));
        for (dnd, label) in [
            (Dnd::PriorityOnly, "Priority only"),
            (Dnd::AlarmsOnly, "Alarms only"),
            (Dnd::TotalSilence, "Total silence"),
        ] {
            assert!(dnd.is_on());
            assert_eq!(dnd.label(Lang::En), label);
            assert!(
                dnd.detail(Lang::En)
                    .unwrap()
                    .contains("can't change the sound")
            );
        }
        assert_eq!(Ringer::Vibrate.label(Lang::En), "Vibrate");
        assert_eq!(Ringer::Silent.label(Lang::En), "Silent");
        assert!(
            Ringer::Silent
                .detail(Lang::En)
                .unwrap()
                .contains("neither ring nor vibrate")
        );
    }
}
