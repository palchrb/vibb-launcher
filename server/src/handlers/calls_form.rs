//! Design 80 (`docs/design/80-calls-contacts-simpler.md`): a contact's four plain choices on the Calls page and how
//! they map to the six stored flags.
//! - [both_ways] (D3): a two-way switch ("Kan ringe og ringes", "Kan sende og få meldinger") sets both flags of its
//!   channel, a one-way switch in the contact's "Avansert" sets one, and a channel whose two directions differ is
//!   "Tilpasset" ([Way::Custom]) - never rewritten by the page, a stale one neither (L1).
//! - [contact_row] (D6): the closed list row names every state in two lines.
//! - [Subs] (D1): the sub-lines that say what a switch doesn't do in the phone's present channel modes.
//!
//! Everything here is pure: the handlers (`handlers::calls`) read and write, the template draws.

use std::collections::HashMap;

use crate::i18n::{Lang, t, t_params};

/// A channel's main row as the page drew it (`calls_shown`/`texts_shown`): the two-way switch on or off, or the
/// one-line "Tilpasset" row when the two directions differ (D4).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Way {
    On,
    Off,
    Custom,
}

impl Way {
    /// The class of a pair (in, out): equal is a switch, else "Tilpasset".
    pub(crate) fn of(pair: (bool, bool)) -> Way {
        match pair {
            (true, true) => Way::On,
            (false, false) => Way::Off,
            _ => Way::Custom,
        }
    }

    /// The `*_shown` value the form posts back.
    pub(crate) fn shown(self) -> &'static str {
        match self {
            Way::On => "on",
            Way::Off => "off",
            Way::Custom => "custom",
        }
    }

    pub(crate) fn is_on(self) -> bool {
        self == Way::On
    }

    pub(crate) fn is_custom(self) -> bool {
        self == Way::Custom
    }
}

/// D3: the pair (in, out) a channel stores from a post.
/// - `shown`: the form's `*_shown`, what the main row showed when the page was drawn;
/// - `two_way`: the two-way switch is in the post (on);
/// - `posted`: the pair from the contact's "Avansert", always posted (it is in the same form);
/// - `stored`: the pair stored now (`None` for a new contact).
///
/// A two-way switch the parent changed sets both flags to its value. Otherwise the pair is taken as posted, except
/// that a post which left the switch alone with the pair exactly as `shown` implies keeps the stored pair (L1: a stale
/// tab saving another field never undoes a split made meanwhile). `custom`, a missing or an unknown `shown` (an older
/// page, N4) is the pair as posted - today's behaviour.
pub(crate) fn both_ways(
    shown: Option<&str>,
    two_way: bool,
    posted: (bool, bool),
    stored: Option<(bool, bool)>,
) -> (bool, bool) {
    let implied = match shown {
        Some("on") => true,
        Some("off") => false,
        _ => return posted,
    };
    if two_way != implied {
        return (two_way, two_way);
    }
    if posted == (implied, implied) {
        return stored.unwrap_or(posted);
    }
    posted
}

/// A contact form's two channels as stored after D3: `(allow_inbound, allow_outbound)` and `(sms_in, sms_out)`. For an
/// emergency number (`emergency`, R2-H1) the texts row is `sms_out` alone and `sms_in` is never stored. `stored` is
/// the contact's stored pairs (`None` for Add Contact).
pub(crate) fn resolve(
    form: &HashMap<String, String>,
    stored: Option<((bool, bool), (bool, bool))>,
    emergency: bool,
) -> ((bool, bool), (bool, bool)) {
    let on = |key: &str| form.contains_key(key);
    let shown = |key: &str| form.get(key).map(String::as_str);
    let calls = both_ways(
        shown("calls_shown"),
        on("calls"),
        (on("inbound"), on("outbound")),
        stored.map(|(calls, _)| calls),
    );
    let texts = if emergency {
        // Code QA L5: the texts row is `sms_out` alone, but Add Contact (the number typed, so not known as an emergency
        // number when drawn) posts the two-way switch too: it resolves as the pair `(sms_out, sms_out)`, so turning it
        // off without JS turns `sms_out` off. A contact's own emergency row posts no `texts_shown`: `sms_out` as posted.
        let out = on("sms_out");
        let stored = stored.map(|(_, (_, out))| (out, out));
        (
            false,
            both_ways(shown("texts_shown"), on("texts"), (out, out), stored).1,
        )
    } else {
        both_ways(
            shown("texts_shown"),
            on("texts"),
            (on("sms_in"), on("sms_out")),
            stored.map(|(_, texts)| texts),
        )
    };
    (calls, (texts.0 && !emergency, texts.1))
}

/// D6: the closed contact row's value and its second line. `calls` is (in, out) - in: the contact calls the kid -,
/// `texts` likewise (for an emergency number both are `sms_out`, N6). The extras: "Hjem" for a phone-book contact on
/// Home (`CallRules.homeContacts`), "uten bilder" while texts go either way without pictures.
pub(crate) fn contact_row(
    lang: Lang,
    calls: (bool, bool),
    texts: (bool, bool),
    home: bool,
    mms: bool,
) -> (String, Option<String>) {
    let mut extras: Vec<&str> = Vec::new();
    if calls.1 && home {
        extras.push(t(lang, "device.calls.home"));
    }
    if (texts.0 || texts.1) && !mms {
        extras.push(t(lang, "device.calls.row.no_pictures"));
    }
    let whole = match (calls, texts) {
        ((true, true), (true, true)) => Some("device.calls.row.calls_and_texts"),
        ((true, true), (false, false)) => Some("device.calls.row.calls_only"),
        ((false, false), (true, true)) => Some("device.calls.row.texts_only"),
        ((false, false), (false, false)) => Some("device.calls.row.none"),
        _ => None,
    };
    if let Some(key) = whole {
        let second = (!extras.is_empty()).then(|| extras.join(" · "));
        return (t(lang, key).to_string(), second);
    }
    let calls_part = t(
        lang,
        match calls {
            (true, true) => "device.calls.row.calls_both",
            (true, false) => "device.calls.row.calls_in",
            (false, true) => "device.calls.row.calls_out",
            (false, false) => "device.calls.row.no_calls",
        },
    );
    let texts_part = t(
        lang,
        match texts {
            (true, true) => "device.calls.texts_both_ways",
            (true, false) => "device.calls.texts_in",
            (false, true) => "device.calls.texts_out",
            (false, false) => "device.calls.row.no_texts",
        },
    );
    let mut second = vec![texts_part];
    second.extend(extras);
    (calls_part.to_string(), Some(second.join(" · ")))
}

/// D1: the sub-lines of a contact's four rows in the phone's channel modes - what a switch doesn't do now. Values are
/// always kept and sent as they are.
#[derive(Default, Debug, PartialEq, Eq)]
pub(crate) struct Subs {
    pub calls: Option<String>,
    pub texts: Option<String>,
    pub pictures: Option<String>,
    pub home: Option<String>,
}

/// The modes are `device_policy.calls_mode`/`sms_mode`; `kid` and `contact` the names in the words (Add Contact's
/// "Kontakten" at a sentence's start, "kontakten" inside one - L7); `outbound`, `texts_any` (a text goes either way)
/// the contact's switches.
pub(crate) fn subs(
    lang: Lang,
    calls_mode: &str,
    sms_mode: &str,
    kid: &str,
    contact: (&str, &str),
    outbound: bool,
    texts_any: bool,
) -> Subs {
    let start = [("kid", kid), ("contact", contact.0)];
    let names = [("kid", kid), ("contact", contact.1)];
    let calls = match (calls_mode, sms_mode) {
        ("managed", _) => None,
        ("unmanaged", "unmanaged") => Some(t(lang, "device.calls.sub.calls_applies").to_string()),
        ("unmanaged", _) => Some(t_params(
            lang,
            if outbound {
                "device.calls.sub.calls_unmanaged_in_book"
            } else {
                "device.calls.sub.calls_unmanaged_not_in_book"
            },
            &start,
        )),
        _ => Some(t(lang, "device.calls.sub.calls_off").to_string()),
    };
    let sms_applies =
        (sms_mode != "managed").then(|| t(lang, "device.calls.sub.sms_applies").to_string());
    let pictures = sms_applies.clone().or_else(|| {
        (!texts_any).then(|| t_params(lang, "device.calls.sub.pictures_no_texts", &names))
    });
    Subs {
        calls,
        texts: sms_applies,
        pictures,
        home: (!outbound).then(|| t_params(lang, "device.calls.sub.home_needs_out", &names)),
    }
}

/// D2's one-way labels with both names, so a direction can't be misread: `[contact calls kid, kid calls contact,
/// contact texts kid, kid texts contact]`. `contact` as in [subs].
pub(crate) fn one_way_labels(lang: Lang, kid: &str, contact: (&str, &str)) -> [String; 4] {
    let start = [("kid", kid), ("contact", contact.0)];
    let inner = [("kid", kid), ("contact", contact.1)];
    [
        t_params(lang, "device.calls.adv.call_in", &start),
        t_params(lang, "device.calls.adv.call_out", &inner),
        t_params(lang, "device.calls.adv.text_in", &start),
        t_params(lang, "device.calls.adv.text_out", &inner),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Every row of D3's table, an unknown `*_shown` read as absent (N4), and the stale-page guard (L1).
    #[test]
    fn both_ways_follows_the_table() {
        let split = Some((true, false));
        // `on`, the switch still on: the pair as posted, so a one-way change in Avansert is taken.
        assert_eq!(
            both_ways(Some("on"), true, (false, true), split),
            (false, true)
        );
        // `on`, the switch turned off: both off.
        assert_eq!(
            both_ways(Some("on"), false, (true, true), split),
            (false, false)
        );
        assert_eq!(
            both_ways(Some("on"), false, (true, false), None),
            (false, false)
        );
        // `off`, the switch turned on: both on.
        assert_eq!(
            both_ways(Some("off"), true, (false, false), split),
            (true, true)
        );
        // `off`, unchanged: the pair as posted.
        assert_eq!(
            both_ways(Some("off"), false, (true, false), None),
            (true, false)
        );
        // `custom` and an older page (absent, or any other value): the pair as posted, the switch ignored.
        for shown in [Some("custom"), None, Some("v2"), Some("")] {
            assert_eq!(
                both_ways(shown, true, (true, false), split),
                (true, false),
                "{shown:?}"
            );
            assert_eq!(
                both_ways(shown, false, (false, true), split),
                (false, true),
                "{shown:?}"
            );
        }
        // L1: a stale page drawn `on` (or `off`) that saves another field, its pair exactly as drawn, keeps the split
        // another tab stored meanwhile.
        assert_eq!(
            both_ways(Some("on"), true, (true, true), split),
            (true, false)
        );
        assert_eq!(
            both_ways(Some("off"), false, (false, false), split),
            (true, false)
        );
        // ...and a new contact (nothing stored) takes the pair.
        assert_eq!(
            both_ways(Some("on"), true, (true, true), None),
            (true, true)
        );
    }

    fn form(fields: &[&str]) -> HashMap<String, String> {
        fields
            .iter()
            .map(|field| match field.split_once('=') {
                Some((k, v)) => (k.to_string(), v.to_string()),
                None => (field.to_string(), "on".to_string()),
            })
            .collect()
    }

    /// An emergency number never stores `sms_in`, and its texts row is `sms_out` alone (R2-H1, N6).
    #[test]
    fn an_emergency_number_texts_by_sms_out_alone() {
        let sent = form(&[
            "calls_shown=on",
            "calls",
            "inbound",
            "outbound",
            "sms_in",
            "sms_out",
        ]);
        assert_eq!(resolve(&sent, None, true), ((true, true), (false, true)));
        // Turned on: the kid's way on, never `sms_in`.
        let sent = form(&["texts_shown=off", "texts", "sms_in"]);
        assert_eq!(resolve(&sent, None, true), ((false, false), (false, true)));
        // A plain number: the two-way switch turned on sets both.
        assert_eq!(resolve(&sent, None, false), ((false, false), (true, true)));
        // Code QA L5: Add Contact without JS - "Kan sende og få meldinger" turned off, Avansert's `sms_out` still on: off.
        let sent = form(&["texts_shown=on", "sms_out"]);
        assert_eq!(resolve(&sent, None, true), ((false, false), (false, false)));
        // A contact's own emergency row (no `texts_shown`): `sms_out` as posted.
        let sent = form(&["sms_out"]);
        assert_eq!(resolve(&sent, None, true), ((false, false), (false, true)));
    }

    /// D1's sub-lines for each mode pair (M2).
    #[test]
    fn the_sub_lines_follow_the_modes() {
        let en = Lang::En;
        let managed = subs(
            en,
            "managed",
            "managed",
            "Anna",
            ("Mamma", "Mamma"),
            true,
            true,
        );
        assert_eq!(managed, Subs::default());
        let off = subs(en, "off", "managed", "Anna", ("Mamma", "Mamma"), true, true);
        assert_eq!(
            off.calls.as_deref(),
            Some("Calls are off - only emergency numbers can be called")
        );
        let book = subs(
            en,
            "unmanaged",
            "managed",
            "Anna",
            ("Mamma", "Mamma"),
            true,
            true,
        );
        assert_eq!(
            book.calls.as_deref(),
            Some("Calls aren't managed - anyone can call. Mamma is in the phone book")
        );
        let not_book = subs(
            en,
            "unmanaged",
            "off",
            "Anna",
            ("Mamma", "Mamma"),
            false,
            true,
        );
        assert_eq!(
            not_book.calls.as_deref(),
            Some("Calls aren't managed - anyone can call. Mamma isn't in the phone book")
        );
        assert_eq!(
            not_book.texts.as_deref(),
            Some("Applies while SMS is managed by Vibb")
        );
        assert_eq!(not_book.pictures, not_book.texts);
        assert_eq!(
            not_book.home.as_deref(),
            Some("Shows when Anna can call Mamma")
        );
        let neither = subs(
            en,
            "unmanaged",
            "unmanaged",
            "Anna",
            ("Mamma", "Mamma"),
            true,
            true,
        );
        assert_eq!(
            neither.calls.as_deref(),
            Some("Applies while calls or SMS are managed by Vibb")
        );
        // SMS managed, no text either way: pictures say when they apply (N5).
        let quiet = subs(
            en,
            "managed",
            "managed",
            "Anna",
            ("Mamma", "Mamma"),
            true,
            false,
        );
        assert_eq!(
            quiet.pictures.as_deref(),
            Some("Applies when Anna and Mamma text")
        );
        assert_eq!(quiet.texts, None);
    }
}
