//! How an app shows on the kid's launcher (design `docs/design/14-app-display.md` at the monorepo
//! root, with its QA review and decisions): the parent's name, icon and tile colour. Two levels - a
//! default on the catalog row (`tracked_apps.display_*`, by its package name) and a per-phone row
//! (`device_app_display`, by package name, also for apps that aren't in the catalog) that replaces
//! the default as a whole. The policy carries the resolved list per phone
//! (`launcher_ui.app_display`). The icons and colours are `app_icons.rs` (generated from
//! `testdata/app_icons.json` by `scripts/material-symbols.sh`).

use serde::Serialize;
use sqlx::SqlitePool;

use crate::app_icons::{COLORS, ICONS};
use crate::i18n::{Lang, t, t_params};

/// A label is at most this many characters (Unicode scalar values) - Home shows about 10.
pub const MAX_LABEL_CHARS: usize = 20;
/// At most this many per-phone rows.
pub const MAX_ROWS_PER_DEVICE: i64 = 200;
/// The colour key meaning "the app's own tone" (design 34 P1: the launcher picks one of its six
/// tones from the app's icon).
pub const AUTO: &str = "auto";
/// The icon key meaning "the app's own logo" (design 34 P1): the phone draws the app's real icon
/// in its original colours - never the monochrome glyph, no tone. Not a key of [ICONS] (the glyph
/// and licence tests stand): `validate` and `sanitized` accept it beside them, and its colour is
/// always [AUTO].
pub const OWN: &str = "own";
/// What the preview shows for [AUTO]: the slate tone (QA #3 - the real tone comes from the app's
/// icon on the phone). Also in `static/app-display.js`.
pub const AUTO_PREVIEW: &str = "#606B80";
/// The preview tile without a glyph ("Automatic", "Logo"): dark enough for its white
/// text at 4.5:1 (qa-14-code #4). Also in `static/app-display.js`.
pub const OWN_PREVIEW: &str = "#5C6370";

/// Design 82 D9: what a part of Vibb One shows when nothing is chosen - its own glyph on its own tile, where an
/// app shows its tone. "Automatic" previews as it, and a chosen glyph with the colour [AUTO] keeps the tile.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct BuiltinLook {
    /// A glyph in `static/app-icons/`: a key of [ICONS], or the part's own glyph there (Meldinger's `sms`, not a
    /// parent choice).
    pub glyph: &'static str,
    pub tile: &'static str,
}

/// Meldinger's: the bubble with dots (Material Symbols `sms`, 2026-11-17; the launcher's `ic_kid_sms`) on the
/// launcher's `kid_messages_tile` - a parent-chosen "chat" app keeps the plain bubble.
pub const MESSAGES_LOOK: BuiltinLook = BuiltinLook {
    glyph: "sms",
    tile: "#347470",
};

/// Kalender's (Dagen): the calendar glyph on the launcher's `kid_day_tile`. Device › Apps' row only - the phone takes
/// no Name and icon for it.
pub const DAY_LOOK: BuiltinLook = BuiltinLook {
    glyph: "calendar_month",
    tile: "#496C9C",
};

impl BuiltinLook {
    /// The tile the phone draws for `values`: a chosen glyph on its colour, else the built-in's own. A colour
    /// applies only with a chosen glyph, as for every app.
    pub fn shown(&self, values: Option<&DisplayValues>) -> (&'static str, &'static str) {
        let glyph = values
            .and_then(|v| v.icon.as_deref())
            .and_then(|icon| ICONS.iter().find(|(key, _)| *key == icon))
            .map(|(key, _)| *key);
        match (glyph, values) {
            (Some(glyph), Some(v)) if color_known(&v.color) => (glyph, tile_of(&v.color)),
            (Some(glyph), _) => (glyph, self.tile),
            _ => (self.glyph, self.tile),
        }
    }
}

/// One entry of `launcher_ui.app_display` - every key always present. `label` `null` = the app's
/// own name; `icon` `null` = Vibb style (the white glyph on the app's tone), [OWN] = the app's own
/// logo, else a key of [ICONS]; `color` is [AUTO] or a key of [COLORS] (always [AUTO] for `null`
/// and [OWN]).
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct PolicyAppDisplay {
    pub package_name: String,
    pub label: Option<String>,
    pub icon: Option<String>,
    pub color: String,
    /// Design 69 §4.4: `false` = off Home and the app list except during a pause (a sync app such as ICSx5 or
    /// DAVx5 that must stay allowed); sent only when `false` (missing = on).
    #[serde(skip_serializing_if = "is_true")]
    pub on_home: bool,
}

fn is_true(value: &bool) -> bool {
    *value
}

/// A name/icon/colour choice, validated.
#[derive(Clone, Debug, PartialEq, Eq, Default)]
pub struct DisplayValues {
    pub label: Option<String>,
    pub icon: Option<String>,
    pub color: String,
}

impl DisplayValues {
    /// Nothing chosen: the app's own name in Vibb style.
    pub fn empty() -> Self {
        DisplayValues {
            label: None,
            icon: None,
            color: AUTO.to_string(),
        }
    }

    /// Nothing to send: no label and no icon (Vibb style under the app's own name). An [OWN] icon
    /// is something to send.
    pub fn is_empty(&self) -> bool {
        self.label.is_none() && self.icon.is_none()
    }

    /// The parent chose the app's own logo.
    pub fn is_own(&self) -> bool {
        self.icon.as_deref() == Some(OWN)
    }

    /// "Chat, with the chat icon on rose" - for the pages, in the reader's language.
    pub fn describe(&self, lang: Lang) -> String {
        let icon = match self.icon.as_deref() {
            None => t(lang, "apps.display.describe.vibb").to_string(),
            Some(OWN) => t(lang, "apps.display.describe.own").to_string(),
            Some(icon) => t_params(
                lang,
                "apps.display.describe.glyph",
                &[
                    ("icon", &icon_label(lang, icon)),
                    ("colour", &t(lang, color_label(&self.color)).to_lowercase()),
                ],
            ),
        };
        match &self.label {
            Some(label) => t_params(
                lang,
                "apps.display.describe.named",
                &[("name", label), ("icon", &icon)],
            ),
            None => t_params(lang, "apps.display.describe.unnamed", &[("icon", &icon)]),
        }
    }
}

/// Android's package name grammar: at least two dot-separated segments, each a letter followed by
/// letters, digits or underscores; at most 255 characters.
pub fn valid_package_name(name: &str) -> bool {
    name.len() <= 255
        && name.split('.').count() >= 2
        && name.split('.').all(|seg| {
            let mut chars = seg.chars();
            chars.next().is_some_and(|c| c.is_ascii_alphabetic())
                && chars.all(|c| c.is_ascii_alphanumeric() || c == '_')
        })
}

pub fn icon_known(key: &str) -> bool {
    ICONS.iter().any(|(k, _)| *k == key)
}

/// The catalog key of what the PWA calls a colour key (design 34 P1, QA M3): design 14's keys stay
/// (stored rows stay valid), their tiles are the launcher's tones and the names follow the tiles.
pub fn color_label(key: &str) -> &'static str {
    match key {
        "peach" => "apps.display.color.peach",
        "plum" => "apps.display.color.plum",
        "sky" => "apps.display.color.sky",
        "sunset" => "apps.display.color.sunset",
        "night" => "apps.display.color.night",
        "grey" => "apps.display.color.grey",
        _ => "apps.display.color.auto",
    }
}

/// The catalog key of an icon's name ("photo camera"); `None` for a key the catalog doesn't word.
fn icon_word(key: &str) -> Option<&'static str> {
    Some(match key {
        "chat" => "apps.display.icon.chat",
        "mail" => "apps.display.icon.mail",
        "photo_camera" => "apps.display.icon.photo_camera",
        "photo_library" => "apps.display.icon.photo_library",
        "music_note" => "apps.display.icon.music_note",
        "play_circle" => "apps.display.icon.play_circle",
        "sports_esports" => "apps.display.icon.sports_esports",
        "menu_book" => "apps.display.icon.menu_book",
        "school" => "apps.display.icon.school",
        "calculate" => "apps.display.icon.calculate",
        "map" => "apps.display.icon.map",
        "calendar_month" => "apps.display.icon.calendar_month",
        "alarm" => "apps.display.icon.alarm",
        "public" => "apps.display.icon.public",
        "partly_cloudy_day" => "apps.display.icon.partly_cloudy_day",
        "star" => "apps.display.icon.star",
        _ => return None,
    })
}

/// An icon's name in `lang`: its key with spaces when the catalog doesn't word it (`every_icon_is_worded`).
fn icon_label(lang: Lang, key: &str) -> String {
    match icon_word(key) {
        Some(word) => t(lang, word).to_string(),
        None => key.replace('_', " "),
    }
}

pub fn color_known(key: &str) -> bool {
    COLORS.iter().any(|(k, _, _)| *k == key)
}

/// The tile hex of a colour key ([AUTO_PREVIEW] for [AUTO] or an unknown key).
pub fn tile_of(color: &str) -> &'static str {
    COLORS
        .iter()
        .find(|(k, _, _)| *k == color)
        .map(|(_, _, tile)| *tile)
        .unwrap_or(AUTO_PREVIEW)
}

/// A refused field: which one (`label`, `icon`, `color`) and why (a catalog key; `{max}` is
/// [MAX_LABEL_CHARS], `{rows}` [MAX_ROWS_PER_DEVICE]).
#[derive(Debug, PartialEq, Eq)]
pub struct FieldError {
    pub field: &'static str,
    pub key: &'static str,
}

/// The form's values, checked: the label trimmed (empty = none), 1-20 characters, no control
/// characters; the icon empty (Vibb style), [OWN] or a known key; the colour [AUTO] or a known key
/// - [AUTO] whatever was sent with [OWN] (the logo has no tile).
pub fn validate(label: &str, icon: &str, color: &str) -> Result<DisplayValues, FieldError> {
    let label = label.trim();
    let label = if label.is_empty() {
        None
    } else if label.chars().count() > MAX_LABEL_CHARS {
        return Err(FieldError {
            field: "label",
            key: "apps.display.error.label_long",
        });
    } else if label.chars().any(char::is_control) {
        return Err(FieldError {
            field: "label",
            key: "apps.display.error.label_control",
        });
    } else {
        Some(label.to_string())
    };
    let icon = match icon.trim() {
        "" => None,
        OWN => Some(OWN.to_string()),
        key if icon_known(key) => Some(key.to_string()),
        _ => {
            return Err(FieldError {
                field: "icon",
                key: "apps.display.error.icon",
            });
        }
    };
    let color = match color.trim() {
        _ if icon.as_deref() == Some(OWN) => AUTO.to_string(),
        "" | AUTO => AUTO.to_string(),
        key if color_known(key) => key.to_string(),
        _ => {
            return Err(FieldError {
                field: "color",
                key: "apps.display.error.color",
            });
        }
    };
    Ok(DisplayValues { label, icon, color })
}

/// Stored values as the phones may get them: an unknown icon (a newer table, a hand edit) is
/// left out, [OWN] is kept (with [AUTO]), an unknown colour is [AUTO], a label that no longer
/// validates is left out.
fn sanitized(label: Option<String>, icon: Option<String>, color: String) -> DisplayValues {
    let label = label
        .and_then(|l| validate(&l, "", AUTO).ok())
        .and_then(|v| v.label);
    let icon = icon.filter(|i| i == OWN || icon_known(i));
    let color = if icon.as_deref() != Some(OWN) && color_known(&color) {
        color
    } else {
        AUTO.to_string()
    };
    DisplayValues { label, icon, color }
}

/// The icon a phone shows for [package] on its colour (its own choice, else the catalog's), when the key is known:
/// the Apps page's tile and the Lock page's list use it alike.
pub fn icon_of(
    own: &std::collections::BTreeMap<String, DisplayValues>,
    defaults: &std::collections::BTreeMap<String, DisplayValues>,
    package: &str,
) -> Option<(String, &'static str)> {
    own.get(package)
        .or_else(|| defaults.get(package))
        .and_then(|values| {
            values
                .icon
                .as_deref()
                .filter(|key| icon_known(key))
                .map(|key| (key.to_string(), tile_of(&values.color)))
        })
}

/// The catalog defaults by package name (rows with a package and a label or an icon; the first
/// row of a package wins).
pub async fn catalog_defaults(
    db: &SqlitePool,
) -> Result<std::collections::BTreeMap<String, DisplayValues>, sqlx::Error> {
    let rows: Vec<(String, Option<String>, Option<String>, String)> = sqlx::query_as(
        "SELECT package_name, display_label, display_icon, display_color FROM tracked_apps \
         WHERE package_name != '' AND (display_label IS NOT NULL OR display_icon IS NOT NULL) \
         ORDER BY id",
    )
    .fetch_all(db)
    .await?;
    let mut map = std::collections::BTreeMap::new();
    for (package, label, icon, color) in rows {
        map.entry(package)
            .or_insert_with(|| sanitized(label, icon, color));
    }
    Ok(map)
}

/// This phone's own rows by package name.
pub async fn device_rows(
    db: &SqlitePool,
    device_id: i64,
) -> Result<std::collections::BTreeMap<String, DisplayValues>, sqlx::Error> {
    let rows: Vec<(String, Option<String>, Option<String>, String)> = sqlx::query_as(
        "SELECT package_name, label, icon_key, color_key FROM device_app_display \
         WHERE device_id = ? ORDER BY package_name",
    )
    .bind(device_id)
    .fetch_all(db)
    .await?;
    Ok(rows
        .into_iter()
        .map(|(package, label, icon, color)| (package, sanitized(label, icon, color)))
        .collect())
}

/// This phone's own name for `package`, if the parent gave it one (design 82 W1: Meldinger's name on the pages).
pub async fn device_label(
    db: &SqlitePool,
    device_id: i64,
    package: &str,
) -> Result<Option<String>, sqlx::Error> {
    let label: Option<Option<String>> = sqlx::query_scalar(
        "SELECT label FROM device_app_display WHERE device_id = ? AND package_name = ?",
    )
    .bind(device_id)
    .bind(package)
    .fetch_optional(db)
    .await?;
    Ok(sanitized(label.flatten(), None, AUTO.to_string()).label)
}

/// The packages this phone keeps off Home (design 69 §4.4, `device_app_display.on_home = 0`).
pub async fn off_home(
    db: &SqlitePool,
    device_id: i64,
) -> Result<std::collections::BTreeSet<String>, sqlx::Error> {
    let rows: Vec<String> = sqlx::query_scalar(
        "SELECT package_name FROM device_app_display WHERE device_id = ? AND on_home = 0",
    )
    .bind(device_id)
    .fetch_all(db)
    .await?;
    Ok(rows.into_iter().collect())
}

/// `launcher_ui.app_display` for a phone: the catalog defaults, each replaced as a whole by the
/// phone's own row; only entries with a label, an icon or Off Home (a phone row with neither = Vibb style on
/// that phone), sorted by package. An entry without an icon or with [OWN] always says [AUTO].
pub async fn policy_app_display(
    db: &SqlitePool,
    device_id: i64,
) -> Result<Vec<PolicyAppDisplay>, sqlx::Error> {
    let mut resolved = catalog_defaults(db).await?;
    resolved.extend(device_rows(db, device_id).await?);
    let off = off_home(db, device_id).await?;
    Ok(resolved
        .into_iter()
        .filter(|(package, v)| !v.is_empty() || off.contains(package))
        .map(|(package_name, v)| {
            let on_home = !off.contains(&package_name);
            wire_entry(package_name, v, on_home)
        })
        .collect())
}

/// One wire entry: the colour only with a glyph - [AUTO] without an icon and for [OWN], whatever
/// the values carry (a guard: `sanitized` already forces [AUTO] for [OWN] on every read).
fn wire_entry(package_name: String, v: DisplayValues, on_home: bool) -> PolicyAppDisplay {
    PolicyAppDisplay {
        on_home,
        package_name,
        color: if v.icon.is_some() && !v.is_own() {
            v.color
        } else {
            AUTO.to_string()
        },
        label: v.label,
        icon: v.icon,
    }
}

// ---- the form (device page and catalog page) ------------------------------------------------

/// The glyph Symbol starts on when no glyph is chosen (design 127 §2.4): the first of the table.
pub const FIRST_GLYPH: &str = "chat";

/// The glyph a "Symbol" choice saves and shows when none is posted or stored (design 127 §2.4): `own`'s chosen glyph,
/// else the catalog default's, else [FIRST_GLYPH]. One glyph is always checked, so Symbol is never half a choice.
pub fn fallback_glyph(own: Option<&DisplayValues>, default: Option<&DisplayValues>) -> String {
    [own, default]
        .into_iter()
        .flatten()
        .filter_map(|values| values.icon.as_deref())
        .find(|icon| icon_known(icon))
        .unwrap_or(FIRST_GLYPH)
        .to_string()
}

/// The icon a save means (design 127 §2.4), before [validate]: the segmented control's `icon_mode` wins over `icon` -
/// `logo` is [OWN], `auto` empty (Vibb style), `symbol` the posted glyph (else `fallback`, [fallback_glyph]); a form
/// without `icon_mode` (an older page) is read by `icon` as before. The second value: whether the posted colour
/// applies (only with a glyph; Logo and Automatic save [AUTO]). Another mode is the icon's refusal.
pub fn icon_of_form(
    mode: Option<&str>,
    icon: &str,
    fallback: &str,
) -> Result<(String, bool), FieldError> {
    let refused = FieldError {
        field: "icon",
        key: "apps.display.error.icon",
    };
    match mode.map(str::trim) {
        None => Ok((icon.to_string(), true)),
        Some("logo") => Ok((OWN.to_string(), false)),
        Some("auto") => Ok((String::new(), false)),
        Some("symbol") => {
            let icon = icon.trim();
            Ok((
                if icon_known(icon) { icon } else { fallback }.to_string(),
                true,
            ))
        }
        Some(_) => Err(refused),
    }
}

/// One glyph radio.
pub struct IconChoice {
    pub key: &'static str,
    pub label: String,
}

/// One colour radio (`tile` is what the phone draws, `label` its name - [color_label], worded).
pub struct ColorChoice {
    pub key: &'static str,
    pub tile: &'static str,
    pub label: &'static str,
}

/// "Vises som" (design 127 §2.4, `templates/partials/app_display_group.html`): the app's own page and the catalog
/// app's page alike. Every control saves on change; Logo · Automatisk · Symbol is one segmented control, and the
/// symbols and colours show under Symbol only.
pub struct AppDisplayForm {
    /// Where it posts.
    pub action: String,
    /// The fields' id prefix.
    pub anchor: String,
    /// The package (a hidden field on the device page; empty on the catalog page).
    pub package_name: String,
    /// The app's own name, for the field's placeholder and the preview.
    pub app_label: String,
    pub label: String,
    /// Empty = Vibb style, [OWN] = the app's own logo, else a glyph key.
    pub icon: String,
    pub color: String,
    /// The glyph Symbol shows while no glyph is chosen ([fallback_glyph]).
    pub fallback: String,
    /// Device page: the catalog default, if there is one.
    pub catalog_default: Option<String>,
    /// Device page: this phone has its own row (Reset deletes it; the phone then follows the
    /// catalog default, else Vibb style).
    pub own_row: bool,
    /// Something is stored for this form's level (the catalog default on the catalog page).
    pub has_choice: bool,
    /// The refused field ("label", "icon", "color") and why, in the page's language.
    pub error_field: String,
    pub error: Option<String>,
    pub icons: Vec<IconChoice>,
    pub colors: Vec<ColorChoice>,
    /// Device › Apps opened from the Apps tab: its `from`, a hidden field the save keeps
    /// (`device_pages::AppsFrom`); `None` elsewhere.
    pub from: Option<String>,
    /// Device page: "On Home" (design 69 §4.4) - `None` on the catalog page, which has no such switch.
    pub on_home: Option<bool>,
    /// Design 82 D9: the form of a part of Vibb One ([Self::builtin]) - no "Logo", and "Automatic" is its own
    /// look. `None` for an app.
    pub builtin: Option<BuiltinLook>,
    /// Design 87 §8.2: the app's real logo (its URL), which the preview shows for "Logo". `None` while no phone has
    /// sent one.
    pub logo: Option<String>,
    /// Design 87b: the tile the phone draws for "Automatic" (its URL), which the preview shows for Automatisk. `None`
    /// while no phone has sent one (the letter), and for a part of Vibb One (its own look).
    pub auto_src: Option<String>,
}

/// What a refused save sends back to the form: the entered values and the error.
pub struct Entered {
    pub label: String,
    pub icon: String,
    pub color: String,
    pub error: FieldError,
}

impl AppDisplayForm {
    /// The form in `lang`: the icons' and colours' names and a refusal are worded here, the rest by the template.
    pub fn new(
        lang: Lang,
        action: String,
        anchor: String,
        package_name: String,
        app_label: String,
        current: &DisplayValues,
        entered: Option<&Entered>,
    ) -> Self {
        let (label, icon, color) = match entered {
            Some(e) => (e.label.clone(), e.icon.clone(), e.color.clone()),
            None => (
                current.label.clone().unwrap_or_default(),
                current.icon.clone().unwrap_or_default(),
                current.color.clone(),
            ),
        };
        let color = if color_known(color.trim()) {
            color.trim().to_string()
        } else {
            AUTO.to_string()
        };
        AppDisplayForm {
            action,
            anchor,
            package_name,
            app_label,
            fallback: fallback_glyph(Some(current), None),
            label,
            icon: icon.trim().to_string(),
            color,
            catalog_default: None,
            own_row: false,
            has_choice: !current.is_empty(),
            error_field: entered
                .map(|e| e.error.field.to_string())
                .unwrap_or_default(),
            error: entered.map(|e| {
                t_params(
                    lang,
                    e.error.key,
                    &[
                        ("max", &MAX_LABEL_CHARS.to_string()),
                        ("rows", &MAX_ROWS_PER_DEVICE.to_string()),
                    ],
                )
            }),
            icons: ICONS
                .iter()
                .map(|(key, _)| IconChoice {
                    key,
                    label: icon_label(lang, key),
                })
                .collect(),
            colors: COLORS
                .iter()
                .map(|(key, _, tile)| ColorChoice {
                    key,
                    tile,
                    label: t(lang, color_label(key)),
                })
                .collect(),
            from: None,
            on_home: None,
            builtin: None,
            logo: None,
            auto_src: None,
        }
    }

    /// The form of a part of Vibb One (design 82 D9): no Logo, and Automatic is its own look.
    pub fn builtin(mut self, look: BuiltinLook) -> Self {
        self.builtin = Some(look);
        self
    }

    /// The segment that is checked: `logo` ([OWN]), `symbol` (a glyph) or `auto`.
    pub fn mode(&self) -> &'static str {
        if self.icon == OWN && self.builtin.is_none() {
            "logo"
        } else if self.glyph().is_some() {
            "symbol"
        } else {
            "auto"
        }
    }

    /// The glyph radio that is checked: the chosen glyph, else [Self::fallback].
    pub fn picked_glyph(&self) -> &str {
        self.glyph().unwrap_or(&self.fallback)
    }

    /// The glyph chosen, if one is.
    pub fn glyph(&self) -> Option<&str> {
        icon_known(&self.icon).then_some(self.icon.as_str())
    }

    /// The colour radio that is checked ([AUTO] or a key of [COLORS]).
    pub fn picked_color(&self) -> &str {
        &self.color
    }

    /// What the colour [AUTO] previews as behind a glyph: a built-in's own tile, else the slate.
    pub fn auto_tile(&self) -> &'static str {
        self.builtin.map_or(AUTO_PREVIEW, |look| look.tile)
    }

    /// The checked colour's tile behind the chosen symbol ([Self::auto_tile] for [AUTO]).
    pub fn pick_tile(&self) -> &'static str {
        if self.color == AUTO {
            self.auto_tile()
        } else {
            tile_of(&self.color)
        }
    }

    /// The preview tile's colour for the checked mode (`static/app-display.js` draws the same).
    pub fn preview_tile(&self) -> &'static str {
        match (self.mode(), self.builtin) {
            ("symbol", _) => self.pick_tile(),
            ("auto", Some(look)) => look.tile,
            _ => OWN_PREVIEW,
        }
    }

    /// The glyph the preview draws: the chosen one, else a built-in's own under Automatic.
    pub fn preview_glyph(&self) -> Option<&str> {
        match self.mode() {
            "symbol" => Some(self.picked_glyph()),
            "auto" => self.builtin.map(|look| look.glyph),
            _ => None,
        }
    }

    /// The preview's name: the entered name, else the app's own.
    pub fn preview_label(&self) -> &str {
        if self.label.trim().is_empty() {
            &self.app_label
        } else {
            &self.label
        }
    }

    /// The preview tile's letter under Automatic (the app's name's first letter, as Home's tile reads).
    pub fn letter(&self) -> String {
        crate::handlers::devices::initial(&self.app_label)
    }

    /// Reset is offered only when there is something to reset: the phone's own row (device page),
    /// or a stored default (catalog page) - qa-34-s1-code N1.
    pub fn can_reset(&self) -> bool {
        if self.package_name.is_empty() {
            self.has_choice
        } else {
            self.own_row
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SHARED: &str = include_str!("../testdata/app_icons.json");

    /// QA #4: the runtime table is the shared JSON - and the launcher's copy is identical.
    #[test]
    fn the_table_is_the_shared_json() {
        let json: serde_json::Value = serde_json::from_str(SHARED).unwrap();
        let icons: Vec<(String, String)> = json["icons"]
            .as_object()
            .unwrap()
            .iter()
            .map(|(k, v)| (k.clone(), v.as_str().unwrap().to_string()))
            .collect();
        let mut ours: Vec<(String, String)> = ICONS
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect();
        let mut theirs = icons.clone();
        ours.sort();
        theirs.sort();
        assert_eq!(ours, theirs);
        assert_eq!(ICONS.len(), 16);
        let colors = json["colors"].as_object().unwrap();
        assert_eq!(colors.len(), COLORS.len());
        assert_eq!(COLORS.len(), 6);
        for (key, seed, tile) in COLORS {
            assert_eq!(colors[*key]["seed"], *seed, "{key}");
            assert_eq!(colors[*key]["tile"], *tile, "{key}");
        }
        let copy_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../launcher/app/src/test/resources/app_icons.json");
        let copy = std::fs::read_to_string(&copy_path)
            .unwrap_or_else(|e| panic!("{}: {e}", copy_path.display()));
        assert_eq!(copy, SHARED, "{} differs", copy_path.display());
    }

    /// Every icon has its glyph for the preview, saying where it comes from (QA #5).
    #[test]
    fn every_icon_has_a_glyph_and_the_licence_ships() {
        let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("static/app-icons");
        for (key, name) in ICONS {
            let svg = std::fs::read_to_string(dir.join(format!("{key}.svg")))
                .unwrap_or_else(|e| panic!("{key}: {e}"));
            assert!(
                svg.contains(&format!("Converted from Material Symbols \"{name}\""))
                    && svg.contains("Apache License 2.0")
                    && svg.contains("viewBox=\"0 -960 960 960\""),
                "{key}"
            );
        }
        let licence = std::fs::read_to_string(dir.join("LICENSE.txt")).unwrap();
        assert!(licence.contains("Apache License") && licence.contains("Version 2.0"));
    }

    /// QA #3, design 34 P1: white on every tile reaches 4.5:1 (the tones reach 5.3:1), and no tile
    /// is green or red (call / hang-up).
    #[test]
    fn tiles_carry_white_and_are_neither_green_nor_red() {
        fn channel(c: u8) -> f64 {
            let v = c as f64 / 255.0;
            if v <= 0.03928 {
                v / 12.92
            } else {
                ((v + 0.055) / 1.055).powf(2.4)
            }
        }
        for (key, _, tile) in COLORS {
            let rgb = u32::from_str_radix(tile.trim_start_matches('#'), 16).unwrap();
            let (r, g, b) = ((rgb >> 16) as u8, (rgb >> 8) as u8, rgb as u8);
            let lum = 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
            assert!((1.05 / (lum + 0.05)) >= 4.5, "{key} {tile}");
            // By hue (design 34: teal, 176°, is a tone; the call green is 75-165°, red 0-20°).
            let (rf, gf, bf) = (r as f64, g as f64, b as f64);
            let (max, min) = (rf.max(gf).max(bf), rf.min(gf).min(bf));
            let hue = if max == min {
                0.0
            } else if max == rf {
                (60.0 * (gf - bf) / (max - min)).rem_euclid(360.0)
            } else if max == gf {
                60.0 * (bf - rf) / (max - min) + 120.0
            } else {
                60.0 * (rf - gf) / (max - min) + 240.0
            };
            let chromatic = max - min > 30.0;
            assert!(
                !(chromatic && (75.0..=165.0).contains(&hue)),
                "{key} is green ({hue})"
            );
            // Red wraps at 0° (qa-34-s1-code N3): within 15° either side; rose (345°) is 15.2° off.
            assert!(
                !(chromatic && hue.min(360.0 - hue) < 15.0),
                "{key} is red ({hue})"
            );
            assert!(!(r > 180 && g < 80 && b < 80), "{key} is red");
        }
    }

    /** qa-14-code #4: the "its own icon" text is white on [OWN_PREVIEW] at 4.5:1 or more. */
    #[test]
    fn the_own_icon_preview_is_readable() {
        let rgb = u32::from_str_radix(OWN_PREVIEW.trim_start_matches('#'), 16).unwrap();
        let channel = |c: u32| {
            let v = (c & 0xFF) as f64 / 255.0;
            if v <= 0.03928 {
                v / 12.92
            } else {
                ((v + 0.055) / 1.055).powf(2.4)
            }
        };
        let lum = 0.2126 * channel(rgb >> 16) + 0.7152 * channel(rgb >> 8) + 0.0722 * channel(rgb);
        assert!(1.05 / (lum + 0.05) >= 4.5);
        let script = std::fs::read_to_string(
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("static/app-display.js"),
        )
        .unwrap();
        assert!(script.contains(OWN_PREVIEW) && script.contains(AUTO_PREVIEW));
        // Logo's word comes from the page (`data-w-own`).
        assert!(script.contains("\"own\""));
    }

    /// Design 82 D9: a part of Vibb One keeps its own glyph and tile until a glyph is chosen; a colour applies only
    /// with a chosen glyph, and a chosen glyph without one keeps the tile. There is no app logo to show.
    #[test]
    fn a_builtin_keeps_its_look_until_a_glyph_is_chosen() {
        let values = |icon: Option<&str>, color: &str| DisplayValues {
            label: None,
            icon: icon.map(str::to_string),
            color: color.to_string(),
        };
        let own = (MESSAGES_LOOK.glyph, MESSAGES_LOOK.tile);
        // Meldinger's glyph is its own (not a parent choice), converted beside the others.
        assert!(!icon_known(own.0));
        let svg = std::fs::read_to_string(
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("static/app-icons/sms.svg"),
        )
        .unwrap();
        assert!(
            svg.contains("Converted from Material Symbols \"sms\"")
                && svg.contains("Apache License 2.0")
        );
        assert!(icon_known(DAY_LOOK.glyph));
        assert_eq!(MESSAGES_LOOK.shown(None), own);
        assert_eq!(MESSAGES_LOOK.shown(Some(&values(None, "plum"))), own);
        assert_eq!(MESSAGES_LOOK.shown(Some(&values(Some(OWN), AUTO))), own);
        assert_eq!(
            MESSAGES_LOOK.shown(Some(&values(Some("mail"), AUTO))),
            ("mail", own.1)
        );
        assert_eq!(
            MESSAGES_LOOK.shown(Some(&values(Some("mail"), "plum"))),
            ("mail", tile_of("plum"))
        );
    }

    #[test]
    fn package_names_follow_androids_grammar() {
        for ok in ["io.element.android.x", "a.b", "com.example_1.App2"] {
            assert!(valid_package_name(ok), "{ok}");
        }
        let long = format!("a.{}", "b".repeat(254));
        for bad in [
            "",
            "chat",
            "1a.b",
            "a..b",
            "a.b.",
            "a.-b",
            "a b.c",
            "a.b/c",
            long.as_str(),
        ] {
            assert!(!valid_package_name(bad), "{bad}");
        }
    }

    #[test]
    fn validation_trims_counts_characters_and_knows_the_keys() {
        assert_eq!(
            validate("  Chat ", "chat", "peach").unwrap(),
            DisplayValues {
                label: Some("Chat".into()),
                icon: Some("chat".into()),
                color: "peach".into()
            }
        );
        assert_eq!(validate("", "", "").unwrap(), DisplayValues::empty());
        // 20 letters counted as characters, not bytes.
        assert!(validate(&"ø".repeat(20), "", AUTO).is_ok());
        assert_eq!(
            validate(&"ø".repeat(21), "", AUTO).unwrap_err().field,
            "label"
        );
        assert_eq!(validate("a\u{7}b", "", AUTO).unwrap_err().field, "label");
        assert_eq!(validate("Chat", "rocket", AUTO).unwrap_err().field, "icon");
        assert_eq!(validate("Chat", "", "green").unwrap_err().field, "color");
    }

    #[test]
    fn stored_values_drop_what_the_phones_cant_use() {
        let v = sanitized(Some("x".repeat(30)), Some("rocket".into()), "green".into());
        assert_eq!(v, DisplayValues::empty());
        assert!(v.is_empty());
        assert_eq!(
            DisplayValues {
                label: Some("Chat".into()),
                icon: Some("chat".into()),
                color: "peach".into()
            }
            .describe(Lang::En),
            "Chat, with the chat icon on rose"
        );
        assert_eq!(
            DisplayValues {
                label: None,
                icon: Some("chat".into()),
                color: AUTO.into()
            }
            .describe(Lang::En),
            "its own name, with the chat icon on the app's tone"
        );
        assert_eq!(
            DisplayValues::empty().describe(Lang::En),
            "its own name, with the automatic icon"
        );
        assert_eq!(
            DisplayValues {
                label: Some("Chat".into()),
                icon: None,
                color: AUTO.into()
            }
            .describe(Lang::En),
            "Chat, with the automatic icon"
        );
    }

    /// Design 34 P1 (QA H1): the third state, "the app's own logo" - accepted by `validate` and
    /// `sanitized` beside the glyphs (not one of them), always with [AUTO], something to send.
    #[test]
    fn the_apps_own_logo_is_a_third_state() {
        assert!(!icon_known(OWN), "own is not a glyph");
        assert_eq!(
            validate("", OWN, "peach").unwrap(),
            DisplayValues {
                label: None,
                icon: Some(OWN.into()),
                color: AUTO.into()
            }
        );
        let labelled = validate(" Chat ", " own ", "").unwrap();
        assert_eq!(labelled.label.as_deref(), Some("Chat"));
        assert!(labelled.is_own() && !labelled.is_empty());
        // A stored colour next to `own` (a hand edit) is never sent.
        let stored = sanitized(None, Some(OWN.into()), "sky".into());
        assert_eq!(stored.icon.as_deref(), Some(OWN));
        assert_eq!(stored.color, AUTO);
        assert!(!stored.is_empty());
        assert_eq!(
            stored.describe(Lang::En),
            "its own name, with the app's logo"
        );
        assert_eq!(labelled.describe(Lang::En), "Chat, with the app's logo");
        assert_eq!(labelled.describe(Lang::Nb), "Chat, med appens logo");
        // Vibb style is the empty choice, not `own`.
        assert!(DisplayValues::empty().is_empty() && !DisplayValues::empty().is_own());
    }

    /// qa-34-s1-code N4: the wire's guard - `own` and no icon always go out as `auto`, even from
    /// values built directly with another colour; a glyph keeps its colour.
    #[test]
    fn the_wire_sends_auto_for_the_logo_and_vibb_style() {
        let built = |icon: Option<&str>| DisplayValues {
            label: Some("Chat".into()),
            icon: icon.map(str::to_string),
            color: "sky".into(),
        };
        assert_eq!(wire_entry("a.b".into(), built(Some(OWN)), true).color, AUTO);
        assert_eq!(wire_entry("a.b".into(), built(None), true).color, AUTO);
        assert_eq!(
            wire_entry("a.b".into(), built(Some("chat")), true).color,
            "sky"
        );
        assert_eq!(
            wire_entry("a.b".into(), built(Some(OWN)), true)
                .icon
                .as_deref(),
            Some(OWN)
        );
    }

    /// qa-34-s1-code N1: Reset only when there is something to reset.
    #[test]
    fn reset_is_offered_only_with_something_to_reset() {
        let form = |package: &str, current: DisplayValues| {
            AppDisplayForm::new(
                Lang::En,
                String::new(),
                String::new(),
                package.into(),
                "Element".into(),
                &current,
                None,
            )
        };
        let chat = DisplayValues {
            label: Some("Chat".into()),
            icon: None,
            color: AUTO.into(),
        };
        // The catalog page: a stored default.
        assert!(!form("", DisplayValues::empty()).can_reset());
        assert!(form("", chat.clone()).can_reset());
        // The device page: the phone's own row, not the default it shows.
        let mut device = form("a.b", chat);
        assert!(!device.can_reset());
        device.own_row = true;
        assert!(device.can_reset());
    }

    /// QA M3: design 14's colour keys stay, their names follow the tones.
    #[test]
    fn every_colour_has_a_tone_name() {
        let names: Vec<&str> = COLORS
            .iter()
            .map(|(key, _, _)| t(Lang::En, color_label(key)))
            .collect();
        assert_eq!(names, ["Rose", "Plum", "Blue", "Indigo", "Teal", "Slate"]);
        assert_eq!(t(Lang::En, color_label(AUTO)), "The app's tone");
    }

    /// Every icon of the table has its name in both catalogs: a new one in
    /// `testdata/app_icons.json` needs its `apps.display.icon.<key>`.
    #[test]
    fn every_icon_is_worded() {
        for (key, _) in ICONS {
            let word = icon_word(key).unwrap_or_else(|| panic!("{key} has no word"));
            assert_eq!(word, format!("apps.display.icon.{key}"));
            for lang in [Lang::En, Lang::Nb] {
                assert!(crate::i18n::has(lang, word), "{key}");
            }
            assert_eq!(icon_label(Lang::En, key), key.replace('_', " "));
        }
    }

    /// Design 127 §2.4: the form's three modes - the segment, the glyph radio that is checked, the preview.
    #[test]
    fn the_form_knows_the_three_modes() {
        let form = |icon: &str, color: &str| {
            AppDisplayForm::new(
                Lang::En,
                String::new(),
                String::new(),
                String::new(),
                "Element".into(),
                &DisplayValues {
                    label: None,
                    icon: (!icon.is_empty()).then(|| icon.to_string()),
                    color: color.into(),
                },
                None,
            )
        };
        let vibb = form("", AUTO);
        assert_eq!(
            (vibb.mode(), vibb.picked_glyph(), vibb.preview_glyph()),
            ("auto", FIRST_GLYPH, None)
        );
        assert_eq!(vibb.preview_tile(), OWN_PREVIEW);
        assert_eq!(vibb.letter(), "E");
        let own = form(OWN, AUTO);
        assert_eq!((own.mode(), own.preview_glyph()), ("logo", None));
        assert_eq!(own.preview_tile(), OWN_PREVIEW);
        let chat = form("mail", "plum");
        assert_eq!(
            (chat.mode(), chat.picked_glyph(), chat.preview_glyph()),
            ("symbol", "mail", Some("mail"))
        );
        assert_eq!(chat.preview_tile(), tile_of("plum"));
        assert_eq!(form("chat", AUTO).preview_tile(), AUTO_PREVIEW);
        // A part of Vibb One has no Logo: its own look under Automatic.
        let messages = form("", AUTO).builtin(MESSAGES_LOOK);
        assert_eq!(messages.preview_glyph(), Some(MESSAGES_LOOK.glyph));
        assert_eq!(messages.preview_tile(), MESSAGES_LOOK.tile);
        assert_eq!(form(OWN, AUTO).builtin(MESSAGES_LOOK).mode(), "auto");
    }

    /// Design 127 §2.4: `icon_mode` wins over `icon`; Symbol always saves a real glyph; no mode reads `icon` as before.
    #[test]
    fn the_mode_decides_the_icon() {
        let values = |icon: Option<&str>| DisplayValues {
            label: None,
            icon: icon.map(str::to_string),
            color: AUTO.into(),
        };
        assert_eq!(
            icon_of_form(Some("logo"), "mail", "chat"),
            Ok((OWN.to_string(), false))
        );
        assert_eq!(
            icon_of_form(Some("auto"), "mail", "chat"),
            Ok((String::new(), false))
        );
        assert_eq!(
            icon_of_form(Some("symbol"), "mail", "chat"),
            Ok(("mail".to_string(), true))
        );
        assert_eq!(
            icon_of_form(Some("symbol"), "rocket", "star"),
            Ok(("star".to_string(), true))
        );
        assert_eq!(
            icon_of_form(None, "own", "chat"),
            Ok(("own".to_string(), true))
        );
        assert_eq!(
            icon_of_form(Some("glyph"), "mail", "chat")
                .unwrap_err()
                .field,
            "icon"
        );
        assert_eq!(fallback_glyph(None, None), FIRST_GLYPH);
        assert_eq!(
            fallback_glyph(Some(&values(Some(OWN))), Some(&values(Some("map")))),
            "map"
        );
        assert_eq!(
            fallback_glyph(Some(&values(Some("star"))), Some(&values(Some("map")))),
            "star"
        );
    }
}
