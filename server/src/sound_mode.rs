//! Sound mode (design 18, `docs/design/18-sound-mode.md` at the monorepo root). The kid's Settings
//! page has a sound row (quick-controls bit 8: Sound / Silent (vibrate) - never Android's Silent,
//! which is Do Not Disturb), and every status report carries the phone's ringer mode and its
//! interruption filter (DND), so the device page can say why a call wasn't heard.

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

/// The device page's Status card: what the sound was at the last sync. Nothing for a value the
/// phone didn't report; Do Not Disturb only while it is on.
pub fn status_lines(ringer_mode: Option<&str>, interruption_filter: Option<&str>) -> Vec<String> {
    let mut lines = Vec::new();
    let sound = match sanitize_ringer_mode(ringer_mode) {
        Some("normal") => Some("Sound at the last sync: on."),
        Some("vibrate") => {
            Some("Sound at the last sync: silent (vibrate) - calls and notifications only vibrate.")
        }
        Some("silent") => Some(
            "Sound at the last sync: fully silent - calls and notifications neither ring nor \
             vibrate (set with the phone's volume buttons; the kid's sound row only offers sound \
             and silent with vibration).",
        ),
        _ => None,
    };
    lines.extend(sound.map(str::to_string));
    let dnd = match sanitize_interruption_filter(interruption_filter) {
        Some("priority") => Some(
            "Do Not Disturb at the last sync: on (priority only) - only the calls it lets \
             through ring. The kid can't change the sound from the launcher while it is on.",
        ),
        Some("alarms") => Some(
            "Do Not Disturb at the last sync: on (alarms only) - calls don't ring. The kid can't \
             change the sound from the launcher while it is on.",
        ),
        Some("none") => Some(
            "Do Not Disturb at the last sync: on (total silence) - calls and alarms don't ring. \
             The kid can't change the sound from the launcher while it is on.",
        ),
        _ => None,
    };
    lines.extend(dnd.map(str::to_string));
    lines
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
    fn the_status_says_what_the_phone_reported() {
        assert!(status_lines(None, None).is_empty());
        assert!(status_lines(Some("bogus"), Some("bogus")).is_empty());
        // DND off is not worth a line.
        assert_eq!(
            status_lines(Some("normal"), Some("all")),
            vec!["Sound at the last sync: on.".to_string()]
        );
        let lines = status_lines(Some("vibrate"), Some("priority"));
        assert_eq!(lines.len(), 2);
        assert!(lines[0].contains("silent (vibrate)"));
        assert!(lines[1].contains("Do Not Disturb at the last sync: on (priority only)"));
        assert!(status_lines(Some("silent"), None)[0].contains("fully silent"));
        assert!(status_lines(None, Some("none"))[0].contains("total silence"));
        assert!(status_lines(None, Some("alarms"))[0].contains("alarms only"));
    }
}
