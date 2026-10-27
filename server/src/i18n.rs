//! The PWA's words in English and Norwegian (design 69 introduced it for the Calendar pages; round S-i18n moves the
//! rest). A page's language is [Lang], resolved per request from `Accept-Language` (`nb`, `nn`, `no` → Norwegian,
//! else English) - `admin_users` has no language of its own yet. The words are two flat catalogs compiled in,
//! `src/i18n/en.json` and `src/i18n/nb.json` (key → text, `{name}` for a parameter), read through [t] and
//! [t_params]; `i18n::tests` checks that both have every key with the same parameters and that every key the
//! templates and handlers name exists. No new user-facing word is hardcoded (the user, 2026-10-26).

use std::collections::HashMap;
use std::sync::LazyLock;

use axum::extract::FromRequestParts;
use axum::http::header::ACCEPT_LANGUAGE;
use axum::http::request::Parts;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum Lang {
    #[default]
    En,
    Nb,
}

type Catalog = HashMap<String, String>;

fn parse(json: &str) -> Catalog {
    serde_json::from_str(json).expect("an i18n catalog is a flat JSON object of strings")
}

static EN: LazyLock<Catalog> = LazyLock::new(|| parse(include_str!("i18n/en.json")));
static NB: LazyLock<Catalog> = LazyLock::new(|| parse(include_str!("i18n/nb.json")));

impl Lang {
    /// The `<html lang>` code.
    pub fn code(self) -> &'static str {
        match self {
            Lang::En => "en",
            Lang::Nb => "nb",
        }
    }

    fn catalog(self) -> &'static Catalog {
        match self {
            Lang::En => &EN,
            Lang::Nb => &NB,
        }
    }

    /// The best supported language of an `Accept-Language` value (by its q-values, the first on a tie); English
    /// when it names neither.
    pub fn from_accept_language(header: Option<&str>) -> Lang {
        let mut best: Option<(f32, Lang)> = None;
        for part in header.unwrap_or_default().split(',') {
            let mut pieces = part.split(';');
            let tag = pieces
                .next()
                .unwrap_or_default()
                .trim()
                .to_ascii_lowercase();
            let q = pieces
                .find_map(|p| p.trim().strip_prefix("q="))
                .and_then(|q| q.trim().parse::<f32>().ok())
                .unwrap_or(1.0);
            let primary = tag.split('-').next().unwrap_or_default();
            let lang = match primary {
                "nb" | "nn" | "no" => Lang::Nb,
                "en" => Lang::En,
                _ => continue,
            };
            if q > 0.0 && best.is_none_or(|(b, _)| q > b) {
                best = Some((q, lang));
            }
        }
        best.map_or(Lang::En, |(_, lang)| lang)
    }
}

impl<S: Send + Sync> FromRequestParts<S> for Lang {
    type Rejection = std::convert::Infallible;

    async fn from_request_parts(parts: &mut Parts, _: &S) -> Result<Self, Self::Rejection> {
        Ok(Lang::from_accept_language(
            parts
                .headers
                .get(ACCEPT_LANGUAGE)
                .and_then(|v| v.to_str().ok()),
        ))
    }
}

/// The text of `key` in `lang`; English when that catalog lacks it, the key itself when neither has it (the tests
/// keep that from happening).
pub fn t(lang: Lang, key: &str) -> &'static str {
    lang.catalog()
        .get(key)
        .or_else(|| EN.get(key))
        .map(String::as_str)
        .unwrap_or_else(|| {
            tracing::warn!(key, "no such i18n key");
            "?"
        })
}

/// [t] with each `{name}` replaced by its value (once each, values are not scanned again).
pub fn t_params(lang: Lang, key: &str, params: &[(&str, &str)]) -> String {
    let mut out = t(lang, key).to_string();
    for (name, value) in params {
        out = out.replacen(&format!("{{{name}}}"), value, 1);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn placeholders(text: &str) -> Vec<String> {
        let mut out: Vec<String> = text
            .split('{')
            .skip(1)
            .filter_map(|rest| rest.split_once('}').map(|(name, _)| name.to_string()))
            .collect();
        out.sort();
        out
    }

    #[test]
    fn both_catalogs_have_every_key_with_the_same_parameters() {
        assert!(!EN.is_empty());
        for (a, b) in [(&*EN, &*NB), (&*NB, &*EN)] {
            for (key, text) in a {
                let other = b
                    .get(key)
                    .unwrap_or_else(|| panic!("{key} is missing in a catalog"));
                assert!(
                    !text.trim().is_empty() && !other.trim().is_empty(),
                    "{key} is empty"
                );
                assert_eq!(
                    placeholders(text),
                    placeholders(other),
                    "{key}'s parameters"
                );
            }
        }
    }

    /// Every `self.t("…")`, `t(lang, "…")` and `t_params(lang, "…"` key named in the files that use the layer exists.
    #[test]
    fn every_key_used_exists() {
        let mut used = 0;
        for dir in ["templates", "templates/partials", "src", "src/handlers"] {
            for entry in std::fs::read_dir(dir).unwrap() {
                let path = entry.unwrap().path();
                if path.is_dir() || path.ends_with("i18n.rs") {
                    continue;
                }
                let text = std::fs::read_to_string(&path).unwrap_or_default();
                // The call forms, and every quoted key of the layer's namespaces (an error key held in a variable).
                for marker in [
                    "self.t(\"",
                    "t(lang, \"",
                    "t(self.lang, \"",
                    "t_params(lang, \"",
                    "t_params(self.lang, \"",
                    "\"calendar.",
                    "\"common.",
                ] {
                    for (i, _) in text.match_indices(marker) {
                        let start = if marker.starts_with('"') {
                            i + 1
                        } else {
                            i + marker.len()
                        };
                        let key = &text[start..start + text[start..].find('"').unwrap()];
                        assert!(
                            EN.contains_key(key),
                            "{}: {key} is in no catalog",
                            path.display()
                        );
                        used += 1;
                    }
                }
            }
        }
        assert!(used > 50, "{used} keys used");
    }

    #[test]
    fn the_language_follows_accept_language() {
        for (header, lang) in [
            (None, Lang::En),
            (Some(""), Lang::En),
            (Some("nb-NO,nb;q=0.9,en;q=0.8"), Lang::Nb),
            (Some("no"), Lang::Nb),
            (Some("nn"), Lang::Nb),
            (Some("en-US,en;q=0.9,nb;q=0.8"), Lang::En),
            (Some("de-DE,nb;q=0.5,en;q=0.7"), Lang::En),
            (Some("de, nb;q=0.3"), Lang::Nb),
            (Some("sv,da"), Lang::En),
            (Some("nb;q=0"), Lang::En),
        ] {
            assert_eq!(Lang::from_accept_language(header), lang, "{header:?}");
        }
        assert_eq!(
            t_params(Lang::En, "calendar.title", &[]),
            t(Lang::En, "calendar.title")
        );
        assert_eq!(t(Lang::Nb, "no.such.key"), "?");
    }
}
