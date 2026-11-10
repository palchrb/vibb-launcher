//! Vibb One's built-in apps as the parent's pages name them (design 82 D1,
//! `docs/design/82-meldinger-as-an-app.md`): Meldinger - the launcher's own SMS app - is no installed package, so its
//! lock-screen level (`device_lock_glance_apps`) and its name and icon (`device_app_display`) are stored under a key
//! of the reserved prefix. The package grammar can't keep a real app off the name, so the prefix is reserved
//! instead: a reported app under it is dropped at the status intake, no catalog app, allow switch or permissions
//! form takes it, and the lock and display saves take exactly [MESSAGES]. The launcher holds the same two constants.

/// Every name under this prefix is Vibb One's own.
pub const RESERVED_PREFIX: &str = "me.vibb.builtin.";

/// Meldinger's key (`sms_contract.json`'s `app.key`).
pub const MESSAGES: &str = "me.vibb.builtin.messages";

/// A name under the reserved prefix: never an installed app's.
pub fn is_reserved(package: &str) -> bool {
    package.starts_with(RESERVED_PREFIX)
}

/// A reserved name no built-in has: refused wherever [MESSAGES] is taken.
pub fn is_unknown_reserved(package: &str) -> bool {
    is_reserved(package) && package != MESSAGES
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_prefix_is_reserved_and_one_key_is_known() {
        assert!(is_reserved(MESSAGES) && !is_unknown_reserved(MESSAGES));
        assert!(is_unknown_reserved("me.vibb.builtin.other"));
        assert!(is_unknown_reserved("me.vibb.builtin.messages.x"));
        for free in ["me.vibb.launcher", "me.vibb.music", "me.vibb.builtin"] {
            assert!(!is_reserved(free), "{free}");
        }
        // Well-formed on both sides, and not Play core.
        assert!(crate::app_display::valid_package_name(MESSAGES));
        assert!(!crate::play::is_play_core(MESSAGES));
    }
}
