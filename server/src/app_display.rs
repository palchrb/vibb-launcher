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
/// The colour key meaning "the app's own colour".
pub const AUTO: &str = "auto";
/// What the preview shows for [AUTO]: the launcher's neutral grey (QA #3 - the real colour comes
/// from the app's icon on the phone).
pub const AUTO_PREVIEW: &str = "#868E96";
/// The preview tile without a glyph ("its own icon"): dark enough for its white text at 4.5:1
/// (qa-14-code #4; white on [AUTO_PREVIEW] is only 3.3:1). Also in `static/app-display.js`.
pub const OWN_PREVIEW: &str = "#5C6370";

/// One entry of `launcher_ui.app_display` - every key always present. `label`/`icon` `null` = the
/// app's own; `color` is [AUTO] or a key of [COLORS].
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
    pub fn own() -> Self {
        DisplayValues {
            label: None,
            icon: None,
            color: AUTO.to_string(),
        }
    }

    pub fn is_own(&self) -> bool {
        self.label.is_none() && self.icon.is_none()
    }

    /// "Chat, with the chat icon on peach" - for the pages.
    pub fn describe(&self) -> String {
        let icon = self.icon.as_ref().map(|icon| {
            let on = if self.color == AUTO {
                "the app's own colour".to_string()
            } else {
                self.color.clone()
            };
            format!("the {} icon on {on}", icon.replace('_', " "))
        });
        match (&self.label, icon) {
            (Some(label), Some(icon)) => format!("{label}, with {icon}"),
            (Some(label), None) => format!("{label}, with the app's own icon"),
            (None, Some(icon)) => format!("its own name, with {icon}"),
            (None, None) => "the app's own name and icon".to_string(),
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
/// characters; the icon empty or a known key; the colour [AUTO] or a known key.
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
        key if icon_known(key) => Some(key.to_string()),
        _ => {
            return Err(FieldError {
                field: "icon",
                message: "Pick one of the icons, or the app's own.".to_string(),
            });
        }
    };
    let color = match color.trim() {
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
/// left out, an unknown colour is [AUTO], a label that no longer validates is left out.
fn sanitized(label: Option<String>, icon: Option<String>, color: String) -> DisplayValues {
    let label = label
        .and_then(|l| validate(&l, "", AUTO).ok())
        .and_then(|v| v.label);
    let icon = icon.filter(|i| icon_known(i));
    let color = if color_known(&color) {
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
/// phone's own row; only entries with a label or an icon (a phone row with neither = the app's
/// own), sorted by package. An entry without an icon always says [AUTO].
pub async fn policy_app_display(
    db: &SqlitePool,
    device_id: i64,
) -> Result<Vec<PolicyAppDisplay>, sqlx::Error> {
    let mut resolved = catalog_defaults(db).await?;
    resolved.extend(device_rows(db, device_id).await?);
    Ok(resolved
        .into_iter()
        .filter(|(_, v)| !v.is_own())
        .map(|(package_name, v)| PolicyAppDisplay {
            package_name,
            color: if v.icon.is_some() {
                v.color
            } else {
                AUTO.to_string()
            },
            label: v.label,
            icon: v.icon,
        })
        .collect())
}

// ---- the form (device page and catalog page) ------------------------------------------------

/// One icon radio.
pub struct IconChoice {
    pub key: &'static str,
    pub label: String,
    pub checked: bool,
}

/// One colour radio (`tile` is what the phone draws).
pub struct ColorChoice {
    pub key: &'static str,
    pub tile: &'static str,
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
    /// Empty = the app's own icon.
    pub icon: String,
    pub color: String,
    /// What the phone shows now, for the summary line.
    pub summary: String,
    /// Device page: the catalog default, if there is one.
    pub catalog_default: Option<String>,
    /// Device page: this phone has its own row (then "Follow the catalog" is offered with a default).
    pub own_row: bool,
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
                checked: color == *key,
            })
            .collect();
        AppDisplayForm {
            action,
            anchor,
            package_name,
            summary: current.describe(),
            app_label,
            preview_tile: if icon.trim().is_empty() {
                OWN_PREVIEW.to_string()
            } else {
                tile_of(&color).to_string()
            },
            label,
            icon,
            color,
            catalog_default: None,
            own_row: false,
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

    /// QA #3: white on every tile reaches 3:1, and no tile is green or red (call / hang-up).
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
            assert!((1.05 / (lum + 0.05)) >= 3.0, "{key} {tile}");
            assert!(
                !(g > r && g > b && g as i32 - r as i32 > 40),
                "{key} is green"
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
        assert!(script.contains(OWN_PREVIEW));
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
        assert_eq!(validate("", "", "").unwrap(), DisplayValues::own());
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
        assert_eq!(v, DisplayValues::own());
        assert_eq!(
            DisplayValues {
                label: Some("Chat".into()),
                icon: Some("chat".into()),
                color: "peach".into()
            }
            .describe(),
            "Chat, with the chat icon on peach"
        );
        assert_eq!(
            DisplayValues::own().describe(),
            "the app's own name and icon"
        );
    }
}
