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
/// The preview tile without a glyph ("Vibb style", "Its own logo"): dark enough for its white
/// text at 4.5:1 (qa-14-code #4). Also in `static/app-display.js`.
pub const OWN_PREVIEW: &str = "#5C6370";

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

    /// "Chat, with the chat icon on rose" - for the pages.
    pub fn describe(&self) -> String {
        let icon = match self.icon.as_deref() {
            None => "the Vibb-style icon".to_string(),
            Some(OWN) => "the app's own logo".to_string(),
            Some(icon) => {
                let on = if self.color == AUTO {
                    "the app's tone".to_string()
                } else {
                    color_label(&self.color).to_lowercase()
                };
                format!("the {} icon on {on}", icon.replace('_', " "))
            }
        };
        match &self.label {
            Some(label) => format!("{label}, with {icon}"),
            None => format!("its own name, with {icon}"),
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

/// What the PWA calls a colour key (design 34 P1, QA M3): design 14's keys stay (stored rows stay
/// valid), their tiles are the launcher's tones and the names follow the tiles.
pub fn color_label(key: &str) -> &'static str {
    match key {
        "peach" => "Rose",
        "plum" => "Plum",
        "sky" => "Blue",
        "sunset" => "Indigo",
        "night" => "Teal",
        "grey" => "Slate",
        _ => "The app's tone",
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

/// A refused field: which one (`label`, `icon`, `color`) and why.
#[derive(Debug, PartialEq, Eq)]
pub struct FieldError {
    pub field: &'static str,
    pub message: String,
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
            message: format!(
                "At most {MAX_LABEL_CHARS} characters - the home screen shows about 10."
            ),
        });
    } else if label.chars().any(char::is_control) {
        return Err(FieldError {
            field: "label",
            message: "The name can't contain control characters.".to_string(),
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
                message: "Pick one of the icons, Vibb style or the app's own logo.".to_string(),
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
                message: "Pick one of the colours.".to_string(),
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

/// `launcher_ui.app_display` for a phone: the catalog defaults, each replaced as a whole by the
/// phone's own row; only entries with a label or an icon (a phone row with neither = Vibb style on
/// that phone), sorted by package. An entry without an icon or with [OWN] always says [AUTO].
pub async fn policy_app_display(
    db: &SqlitePool,
    device_id: i64,
) -> Result<Vec<PolicyAppDisplay>, sqlx::Error> {
    let mut resolved = catalog_defaults(db).await?;
    resolved.extend(device_rows(db, device_id).await?);
    Ok(resolved
        .into_iter()
        .filter(|(_, v)| !v.is_empty())
        .map(|(package_name, v)| wire_entry(package_name, v))
        .collect())
}

/// One wire entry: the colour only with a glyph - [AUTO] without an icon and for [OWN], whatever
/// the values carry (a guard: `sanitized` already forces [AUTO] for [OWN] on every read).
fn wire_entry(package_name: String, v: DisplayValues) -> PolicyAppDisplay {
    PolicyAppDisplay {
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

/// One icon radio.
pub struct IconChoice {
    pub key: &'static str,
    pub label: String,
    pub checked: bool,
}

/// One colour radio (`tile` is what the phone draws, `label` its name - [color_label]).
pub struct ColorChoice {
    pub key: &'static str,
    pub tile: &'static str,
    pub label: &'static str,
    pub checked: bool,
}

/// The "Name and icon" form (`templates/partials/app_display_form.html`).
pub struct AppDisplayForm {
    /// Where it posts.
    pub action: String,
    /// The element id the save returns to (`app-<package>` on the device page).
    pub anchor: String,
    /// The package (a hidden field on the device page; empty on the catalog page).
    pub package_name: String,
    /// The app's own name, for the field's placeholder and the preview.
    pub app_label: String,
    pub label: String,
    /// Empty = Vibb style, [OWN] = the app's own logo, else a glyph key.
    pub icon: String,
    pub color: String,
    /// What the phone shows now, for the summary line.
    pub summary: String,
    /// Device page: the catalog default, if there is one.
    pub catalog_default: Option<String>,
    /// Device page: this phone has its own row (Reset deletes it; the phone then follows the
    /// catalog default, else Vibb style).
    pub own_row: bool,
    /// Something is stored for this form's level (the catalog default on the catalog page).
    pub has_choice: bool,
    /// The refused field ("label", "icon", "color") and why.
    pub error_field: String,
    pub error: Option<String>,
    /// Shown open (after a refused save).
    pub open: bool,
    pub icons: Vec<IconChoice>,
    pub colors: Vec<ColorChoice>,
    /// The preview tile's colour.
    pub preview_tile: String,
}

/// What a refused save sends back to the form: the entered values and the error.
pub struct Entered {
    pub label: String,
    pub icon: String,
    pub color: String,
    pub error: FieldError,
}

impl AppDisplayForm {
    pub fn new(
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
        let color = if color.is_empty() {
            AUTO.to_string()
        } else {
            color
        };
        let icons = ICONS
            .iter()
            .map(|(key, _)| IconChoice {
                key,
                label: key.replace('_', " "),
                checked: icon == *key,
            })
            .collect();
        let colors = COLORS
            .iter()
            .map(|(key, _, tile)| ColorChoice {
                key,
                tile,
                label: color_label(key),
                checked: color == *key,
            })
            .collect();
        AppDisplayForm {
            action,
            anchor,
            package_name,
            summary: current.describe(),
            app_label,
            preview_tile: if icon.trim().is_empty() || icon.trim() == OWN {
                OWN_PREVIEW.to_string()
            } else {
                tile_of(&color).to_string()
            },
            label,
            icon,
            color,
            catalog_default: None,
            own_row: false,
            has_choice: !current.is_empty(),
            error_field: entered
                .map(|e| e.error.field.to_string())
                .unwrap_or_default(),
            error: entered.map(|e| e.error.message.clone()),
            open: entered.is_some(),
            icons,
            colors,
        }
    }

    /// The text field's preview text: the entered name, else the app's own.
    pub fn preview_label(&self) -> &str {
        if self.label.trim().is_empty() {
            &self.app_label
        } else {
            &self.label
        }
    }

    pub fn auto_checked(&self) -> bool {
        self.color == AUTO
    }

    /// The Icon radio "The app's own logo" is the choice (the colours then don't apply).
    pub fn own_checked(&self) -> bool {
        self.icon.trim() == OWN
    }

    /// The glyph shown in the preview, if one is chosen.
    pub fn glyph(&self) -> Option<&str> {
        let icon = self.icon.trim();
        (!icon.is_empty() && icon != OWN && icon_known(icon)).then_some(icon)
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

    /// What Reset does here: the catalog form clears its default, the device form deletes the
    /// phone's row (it then follows the catalog default, else Vibb style). Empty without Reset.
    pub fn reset_hint(&self) -> &'static str {
        if !self.can_reset() {
            ""
        } else if self.package_name.is_empty() {
            "Reset: no default - Vibb style unless a phone has its own choice."
        } else if self.catalog_default.is_some() {
            "Reset: this phone follows the catalog default."
        } else {
            "Reset: back to Vibb style."
        }
    }

    /// The preview tile's text without a glyph: "Vibb style" or "Its own logo".
    pub fn preview_text(&self) -> &'static str {
        if self.own_checked() {
            "Its own logo"
        } else {
            "Vibb style"
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
        assert!(script.contains("Its own logo") && script.contains("Vibb style"));
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
            .describe(),
            "Chat, with the chat icon on rose"
        );
        assert_eq!(
            DisplayValues {
                label: None,
                icon: Some("chat".into()),
                color: AUTO.into()
            }
            .describe(),
            "its own name, with the chat icon on the app's tone"
        );
        assert_eq!(
            DisplayValues::empty().describe(),
            "its own name, with the Vibb-style icon"
        );
        assert_eq!(
            DisplayValues {
                label: Some("Chat".into()),
                icon: None,
                color: AUTO.into()
            }
            .describe(),
            "Chat, with the Vibb-style icon"
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
        assert_eq!(stored.describe(), "its own name, with the app's own logo");
        assert_eq!(labelled.describe(), "Chat, with the app's own logo");
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
        assert_eq!(wire_entry("a.b".into(), built(Some(OWN))).color, AUTO);
        assert_eq!(wire_entry("a.b".into(), built(None)).color, AUTO);
        assert_eq!(wire_entry("a.b".into(), built(Some("chat"))).color, "sky");
        assert_eq!(
            wire_entry("a.b".into(), built(Some(OWN))).icon.as_deref(),
            Some(OWN)
        );
    }

    /// qa-34-s1-code N1: Reset only when there is something to reset.
    #[test]
    fn reset_is_offered_only_with_something_to_reset() {
        let form = |package: &str, current: DisplayValues| {
            AppDisplayForm::new(
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
        assert_eq!(form("", DisplayValues::empty()).reset_hint(), "");
        // The device page: the phone's own row, not the default it shows.
        let mut device = form("a.b", chat);
        assert!(!device.can_reset());
        device.own_row = true;
        assert!(device.can_reset());
        assert_eq!(device.reset_hint(), "Reset: back to Vibb style.");
    }

    /// QA M3: design 14's colour keys stay, their names follow the tones.
    #[test]
    fn every_colour_has_a_tone_name() {
        let names: Vec<&str> = COLORS.iter().map(|(key, _, _)| color_label(key)).collect();
        assert_eq!(names, ["Rose", "Plum", "Blue", "Indigo", "Teal", "Slate"]);
        assert_eq!(color_label(AUTO), "The app's tone");
    }

    /// The form's three states: the preview text, the glyph, the radio.
    #[test]
    fn the_form_knows_the_three_states() {
        let form = |icon: &str| {
            AppDisplayForm::new(
                String::new(),
                String::new(),
                String::new(),
                "Element".into(),
                &DisplayValues {
                    label: None,
                    icon: (!icon.is_empty()).then(|| icon.to_string()),
                    color: AUTO.into(),
                },
                None,
            )
        };
        let vibb = form("");
        assert_eq!(
            (vibb.preview_text(), vibb.glyph(), vibb.own_checked()),
            ("Vibb style", None, false)
        );
        assert_eq!(vibb.preview_tile, OWN_PREVIEW);
        let own = form(OWN);
        assert_eq!(
            (own.preview_text(), own.glyph(), own.own_checked()),
            ("Its own logo", None, true)
        );
        assert_eq!(own.preview_tile, OWN_PREVIEW);
        assert!(own.icons.iter().all(|choice| !choice.checked));
        let chat = form("chat");
        assert_eq!((chat.glyph(), chat.own_checked()), (Some("chat"), false));
        assert_eq!(chat.preview_tile, AUTO_PREVIEW);
    }
}
