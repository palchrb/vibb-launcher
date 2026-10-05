//! Curated wallpapers (design docs/design/08-ui-polish.md in the handy workspace, QA
//! qa-08-design.md): the built-in colours and gradients plus the parent's uploaded photos
//! (`photos::Shape::Portrait`, stored in `AppState.wallpaper_dir` - the `photos::WALLPAPERS`
//! store, never the contact-photo directory). Each phone gets the set the parent ticked on its
//! page (`device_wallpapers`); the kid picks one of them on the phone.

use crate::models::PolicyWallpaper;

/// A `wallpapers` row.
#[derive(sqlx::FromRow, Clone, Debug)]
pub struct WallpaperRow {
    pub id: i64,
    pub kind: String,
    pub colors: String,
    pub image_hash: Option<String>,
    pub label: String,
    pub builtin_key: Option<String>,
    pub lock_screen: bool,
}

const COLUMNS: &str = "w.id, w.kind, w.colors, w.image_hash, w.label, w.builtin_key, w.lock_screen";

fn is_hex_colour(s: &str) -> bool {
    s.len() == 7 && s.starts_with('#') && s[1..].bytes().all(|b| b.is_ascii_hexdigit())
}

impl WallpaperRow {
    /// The colours, checked: one for "color", two for "gradient", none for "image". `None` for
    /// a row that doesn't validate (it is then left out, with a warning).
    pub fn checked_colours(&self) -> Option<Vec<String>> {
        let colours: Vec<String> = serde_json::from_str(&self.colors).ok()?;
        let want = match self.kind.as_str() {
            "color" => 1,
            "gradient" => 2,
            "image" => 0,
            _ => return None,
        };
        (colours.len() == want && colours.iter().all(|c| is_hex_colour(c))).then_some(colours)
    }

    pub fn to_policy(&self) -> Option<PolicyWallpaper> {
        let colors = self.checked_colours()?;
        if (self.kind == "image") != self.image_hash.is_some() {
            return None;
        }
        Some(PolicyWallpaper {
            id: self.id,
            kind: self.kind.clone(),
            colors,
            image: self.image_hash.clone(),
            label: self.label.clone(),
            builtin_key: self.builtin_key.clone(),
            lock_screen: self.lock_screen && self.kind == "image",
        })
    }

    /// CSS `background` for the admin pages' swatches (colours are checked hex, so safe).
    pub fn css_background(&self) -> String {
        match (self.kind.as_str(), self.checked_colours()) {
            ("color", Some(c)) => c[0].clone(),
            ("gradient", Some(c)) => format!("linear-gradient(160deg, {}, {})", c[0], c[1]),
            _ => "#5C6B7A".to_string(),
        }
    }
}

/// Every wallpaper, in order.
pub async fn all(db: &sqlx::SqlitePool) -> Result<Vec<WallpaperRow>, sqlx::Error> {
    sqlx::query_as(&format!(
        "SELECT {COLUMNS} FROM wallpapers w ORDER BY w.sort, w.id"
    ))
    .fetch_all(db)
    .await
}

/// The wallpapers one device may use, in order.
pub async fn for_device(
    db: &sqlx::SqlitePool,
    device_id: i64,
) -> Result<Vec<WallpaperRow>, sqlx::Error> {
    sqlx::query_as(&format!(
        "SELECT {COLUMNS} FROM wallpapers w JOIN device_wallpapers dw ON dw.wallpaper_id = w.id \
         WHERE dw.device_id = ? ORDER BY w.sort, w.id"
    ))
    .bind(device_id)
    .fetch_all(db)
    .await
}

/// `launcher_ui.wallpapers` for a device. A row that doesn't validate is left out (logged) -
/// a wallpaper is cosmetic, the phone falls back to the next one or navy.
pub async fn policy_wallpapers(
    db: &sqlx::SqlitePool,
    device_id: i64,
) -> Result<Vec<PolicyWallpaper>, sqlx::Error> {
    Ok(for_device(db, device_id)
        .await?
        .iter()
        .filter_map(|row| {
            let policy = row.to_policy();
            if policy.is_none() {
                tracing::warn!(
                    wallpaper = row.id,
                    "wallpaper row doesn't validate - not sent"
                );
            }
            policy
        })
        .collect())
}

/// The devices that have a wallpaper (to nudge them after a change).
pub async fn devices_with(
    db: &sqlx::SqlitePool,
    wallpaper_id: i64,
) -> Result<Vec<i64>, sqlx::Error> {
    sqlx::query_scalar("SELECT device_id FROM device_wallpapers WHERE wallpaper_id = ?")
        .bind(wallpaper_id)
        .fetch_all(db)
        .await
}

#[cfg(test)]
mod tests {
    use super::*;

    fn row(kind: &str, colors: &str, image: Option<&str>) -> WallpaperRow {
        WallpaperRow {
            id: 1,
            kind: kind.into(),
            colors: colors.into(),
            image_hash: image.map(str::to_string),
            label: "x".into(),
            builtin_key: None,
            lock_screen: true,
        }
    }

    #[test]
    fn rows_are_checked_before_they_reach_a_phone() {
        assert!(row("color", r##"["#14213D"]"##, None).to_policy().is_some());
        assert!(
            row("gradient", r##"["#1C7ED6","#14213D"]"##, None)
                .to_policy()
                .is_some()
        );
        let hash = "a".repeat(64);
        let image = row("image", "[]", Some(&hash)).to_policy().unwrap();
        assert!(image.lock_screen);
        // A colour never claims the lock-screen flag.
        assert!(
            !row("color", r##"["#14213D"]"##, None)
                .to_policy()
                .unwrap()
                .lock_screen
        );
        for bad in [
            row("color", r##"["#14213D","#000000"]"##, None),
            row("color", r##"["14213D"]"##, None),
            row("color", r##"["#14213G"]"##, None),
            row("gradient", r##"["#1C7ED6"]"##, None),
            row("image", "[]", None),
            row("color", r##"["#14213D"]"##, Some(&hash)),
            row("plaid", "[]", None),
            row("color", "not json", None),
        ] {
            assert!(bad.to_policy().is_none(), "{bad:?}");
        }
        assert_eq!(
            row("gradient", r##"["#1C7ED6","#14213D"]"##, None).css_background(),
            "linear-gradient(160deg, #1C7ED6, #14213D)"
        );
    }
}
