//! The PWA's words in English and Norwegian (design 69 introduced it for the Calendar pages; round S-i18n, design 70,
//! moves the rest). A page's language is [Lang], resolved per request ([Lang::of_request]): `?lang=en|nb` for this
//! request only, else the account's `admin_users.lang`, else `Accept-Language`, else English. The words are two flat
//! catalogs compiled in, `src/i18n/en.json` and `src/i18n/nb.json` (key → plain text, `{name}` for a parameter, no
//! markup), read through [t], [t_params] and [t_count], or a template's [Words]. `i18n::tests` checks that both have
//! every key with the same parameters, the plural pairs, no markup, and that every key the templates and handlers name
//! exists. No new user-facing word is hardcoded (the user, 2026-10-26).

use std::collections::HashMap;
use std::sync::LazyLock;

use axum::extract::FromRequestParts;
use axum::http::header::ACCEPT_LANGUAGE;
use axum::http::request::Parts;
use axum::http::{HeaderMap, StatusCode, Uri};
use axum::response::{IntoResponse, Response};

use crate::security::CurrentAdmin;

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
    /// Every language, for the tests and the Account select.
    pub const ALL: [Lang; 2] = [Lang::En, Lang::Nb];

    /// The `<html lang>` code, also what `admin_users.lang` stores.
    pub fn code(self) -> &'static str {
        match self {
            Lang::En => "en",
            Lang::Nb => "nb",
        }
    }

    /// The language a stored or sent code names; `None` for any other value.
    pub fn from_code(code: &str) -> Option<Lang> {
        Lang::ALL.into_iter().find(|lang| lang.code() == code)
    }

    fn catalog(self) -> &'static Catalog {
        match self {
            Lang::En => &EN,
            Lang::Nb => &NB,
        }
    }

    /// The plural form [t_count] picks for `n` (CLDR's integer rule, the same for nb and en; a third language adds
    /// its own forms here).
    pub fn plural_form(self, n: i64) -> &'static str {
        match self {
            Lang::En | Lang::Nb if n == 1 => "one",
            Lang::En | Lang::Nb => "other",
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

    /// The request's `Accept-Language` language ([Self::from_accept_language]).
    pub fn of_headers(headers: &HeaderMap) -> Lang {
        Lang::from_accept_language(headers.get(ACCEPT_LANGUAGE).and_then(|v| v.to_str().ok()))
    }

    /// A request's language, first match wins (design 70 §3): `?lang=en|nb` (this request only, never stored; any
    /// other value is ignored), the signed-in account's own (`admin`, the [CurrentAdmin] the session layer put in the
    /// extensions - no database read), `Accept-Language`, English.
    pub fn of_request(uri: &Uri, headers: &HeaderMap, admin: Option<&CurrentAdmin>) -> Lang {
        let asked = uri.query().and_then(|query| {
            form_urlencoded::parse(query.as_bytes())
                .filter(|(name, _)| name == "lang")
                .find_map(|(_, value)| Lang::from_code(&value))
        });
        asked
            .or_else(|| {
                admin.and_then(|CurrentAdmin(admin)| Lang::from_code(admin.lang.as_deref()?))
            })
            .unwrap_or_else(|| Lang::of_headers(headers))
    }
}

impl<S: Send + Sync> FromRequestParts<S> for Lang {
    type Rejection = std::convert::Infallible;

    async fn from_request_parts(parts: &mut Parts, _: &S) -> Result<Self, Self::Rejection> {
        Ok(Lang::of_request(
            &parts.uri,
            &parts.headers,
            parts.extensions.get::<CurrentAdmin>(),
        ))
    }
}

/// The text of `key` in `lang`; the key itself when the catalog lacks it (visible on the page, and logged - the tests
/// keep that from happening).
pub fn t(lang: Lang, key: &str) -> &str {
    match lang.catalog().get(key) {
        Some(text) => text,
        None => {
            tracing::warn!(key, lang = lang.code(), "no such i18n key");
            key
        }
    }
}

/// [t] with each `{name}` replaced by its value: one pass over the text, so a value is never scanned again (a name
/// with a `{…}` in it stays as typed).
pub fn t_params(lang: Lang, key: &str, params: &[(&str, &str)]) -> String {
    let text = t(lang, key);
    let mut out = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(open) = rest.find('{') {
        out.push_str(&rest[..open]);
        let after = &rest[open + 1..];
        let value = after.find('}').and_then(|close| {
            let name = &after[..close];
            params
                .iter()
                .find(|(n, _)| *n == name)
                .map(|(_, value)| (*value, close))
        });
        match value {
            Some((value, close)) => {
                out.push_str(value);
                rest = &after[close + 1..];
            }
            None => {
                out.push('{');
                rest = after;
            }
        }
    }
    out.push_str(rest);
    out
}

/// A counted text: `<key>.one` or `<key>.other` by [Lang::plural_form], `{count}` filled with `n` and the rest with
/// `params`. Zero is its own key the caller picks.
pub fn t_count(lang: Lang, key: &str, n: i64, params: &[(&str, &str)]) -> String {
    let count = n.to_string();
    let mut all = Vec::with_capacity(params.len() + 1);
    all.push(("count", count.as_str()));
    all.extend_from_slice(params);
    t_params(lang, &format!("{key}.{}", lang.plural_form(n)), &all)
}

/// A plain-text answer worded in `lang` (`text/plain`), for the refusals and failures a page never renders.
pub fn error(lang: Lang, status: StatusCode, key: &str) -> Response {
    (status, t(lang, key).to_string()).into_response()
}

/// A template's words (design 70 §2): every template struct has `lang: Lang` and implements this, so the page and
/// the partials it includes (`head`, `tab_bar`, `bell`) say `{{ self.t("…") }}`, `self.tp("…", [("name", value)])`
/// and `self.tc("…", n, [])`.
pub trait Words {
    fn lang(&self) -> Lang;

    fn t<'a>(&self, key: &'a str) -> &'a str {
        t(self.lang(), key)
    }

    fn tp(&self, key: &str, params: &[(&str, &str)]) -> String {
        t_params(self.lang(), key, params)
    }

    // No template counts yet (Account's counted warning is worded in Rust); round S-i18n's tabs use it.
    #[allow(dead_code)]
    fn tc(&self, key: &str, n: i64, params: &[(&str, &str)]) -> String {
        t_count(self.lang(), key, n, params)
    }
}

/// `impl Words for $ty` from its `lang` field.
macro_rules! words {
    ($($ty:ty),+ $(,)?) => {
        $(impl $crate::i18n::Words for $ty {
            fn lang(&self) -> $crate::i18n::Lang {
                self.lang
            }
        })+
    };
}
pub(crate) use words;

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
                // No markup: a sentence with a link is split at the element (design 70 §2).
                assert!(!text.contains(['<', '>']), "{key} has markup: {text:?}");
                // A counted text comes as a pair, both with {count}.
                if let Some(stem) = key
                    .strip_suffix(".one")
                    .or_else(|| key.strip_suffix(".other"))
                {
                    for form in ["one", "other"] {
                        let pair = a
                            .get(&format!("{stem}.{form}"))
                            .unwrap_or_else(|| panic!("{stem}.{form} is missing"));
                        assert!(pair.contains("{count}"), "{stem}.{form} has no {{count}}");
                    }
                }
            }
        }
    }

    /// Every key the templates and handlers name exists: the call forms' first argument, and every quoted literal in
    /// one of the catalog's namespaces (its keys' first segments) shaped like a key - a key held in a variable or a
    /// table. A counted key ([t_count]) exists when its `.one` and `.other` do.
    #[test]
    fn every_key_used_exists() {
        let namespaces: std::collections::HashSet<&str> = EN
            .keys()
            .map(|key| key.split('.').next().unwrap())
            .collect();
        let is_key = |text: &str| {
            text.split('.').count() > 1
                && text.split('.').all(|segment| {
                    !segment.is_empty()
                        && segment
                            .bytes()
                            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_')
                })
                // A template's or a file's name ("account.html", "music.rs").
                && !["html", "js", "rs", "json", "css", "sql"]
                    .contains(&text.rsplit('.').next().unwrap())
        };
        let exists = |key: &str| {
            EN.contains_key(key)
                || (EN.contains_key(&format!("{key}.one"))
                    && EN.contains_key(&format!("{key}.other")))
        };
        let mut used = 0;
        for dir in ["templates", "templates/partials", "src", "src/handlers"] {
            for entry in std::fs::read_dir(dir).unwrap() {
                let path = entry.unwrap().path();
                if path.is_dir() || path.ends_with("i18n.rs") {
                    continue;
                }
                let text = std::fs::read_to_string(&path).unwrap_or_default();
                // Every piece between two quotes (an attribute can hold a `self.t("…")`: no parity).
                for literal in text.split('"') {
                    if !is_key(literal) || !namespaces.contains(literal.split('.').next().unwrap())
                    {
                        continue;
                    }
                    assert!(
                        exists(literal),
                        "{}: {literal} is in no catalog",
                        path.display()
                    );
                    used += 1;
                }
                // The call forms name a key in any namespace (a typo in its first segment too).
                for marker in [
                    "self.t(\"",
                    "self.tp(\"",
                    "self.tc(\"",
                    "t(lang, \"",
                    "t_params(lang, \"",
                    "t_count(lang, \"",
                ] {
                    for (i, _) in text.match_indices(marker) {
                        let start = i + marker.len();
                        let key = &text[start..start + text[start..].find('"').unwrap()];
                        assert!(exists(key), "{}: {key} is in no catalog", path.display());
                    }
                }
            }
        }
        assert!(used > 100, "{used} keys used");
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
    }

    /// The extractor's order (design 70 §3): `?lang=` for this request, the account's, `Accept-Language`, English;
    /// any other `?lang=` or stored value is ignored.
    #[test]
    fn a_requests_language_is_the_query_then_the_account_then_the_header() {
        let admin = |lang: Option<&str>| {
            CurrentAdmin(crate::models::AdminUser {
                id: 1,
                username: "parent".to_string(),
                password_hash: String::new(),
                must_change_password: false,
                totp_secret: None,
                totp_enabled: true,
                failed_login_attempts: 0,
                locked_until: None,
                session_epoch: 0,
                display_name: None,
                invited_by: None,
                invite_expires_at: None,
                lang: lang.map(str::to_string),
            })
        };
        let mut nb = HeaderMap::new();
        nb.insert(ACCEPT_LANGUAGE, "nb-NO".parse().unwrap());
        let none = HeaderMap::new();
        let of = |uri: &str, headers: &HeaderMap, admin: Option<&CurrentAdmin>| {
            Lang::of_request(&uri.parse::<Uri>().unwrap(), headers, admin)
        };
        assert_eq!(of("/devices", &none, None), Lang::En);
        assert_eq!(of("/devices", &nb, None), Lang::Nb);
        assert_eq!(of("/devices", &nb, Some(&admin(None))), Lang::Nb);
        assert_eq!(of("/devices", &nb, Some(&admin(Some("en")))), Lang::En);
        assert_eq!(of("/devices", &none, Some(&admin(Some("nb")))), Lang::Nb);
        assert_eq!(
            of("/devices?lang=en", &nb, Some(&admin(Some("nb")))),
            Lang::En
        );
        assert_eq!(
            of("/devices?x=1&lang=nb", &none, Some(&admin(Some("en")))),
            Lang::Nb
        );
        // `?lang=zz` and a stored value no catalog has are ignored.
        assert_eq!(
            of("/devices?lang=zz", &nb, Some(&admin(Some("en")))),
            Lang::En
        );
        assert_eq!(of("/devices?lang=zz", &nb, None), Lang::Nb);
        assert_eq!(of("/devices?lang=", &none, None), Lang::En);
        assert_eq!(of("/devices", &nb, Some(&admin(Some("de")))), Lang::Nb);
        assert_eq!(Lang::from_code("NB"), None);
        for lang in Lang::ALL {
            assert_eq!(Lang::from_code(lang.code()), Some(lang));
        }
    }

    #[test]
    fn a_missing_key_reads_as_itself() {
        assert_eq!(t(Lang::Nb, "no.such.key"), "no.such.key");
        assert_eq!(
            t_params(Lang::En, "no.such.key", &[("x", "1")]),
            "no.such.key"
        );
        assert_eq!(
            t_params(Lang::En, "common.save", &[]),
            t(Lang::En, "common.save")
        );
    }

    /// Each `{name}` is filled in one pass: a value is never scanned again.
    #[test]
    fn parameters_are_filled_once() {
        assert_eq!(
            t_params(Lang::En, "calendar.rules.skipped", &[("ids", "{ids} #1")]),
            "The phone's Vibb One can't use rule {ids} #1 yet and leaves it out."
        );
    }

    #[test]
    fn a_count_picks_one_or_other() {
        for lang in Lang::ALL {
            assert_eq!(lang.plural_form(1), "one");
            for n in [0, 2, 5, 11, 21, 101] {
                assert_eq!(lang.plural_form(n), "other");
            }
            for n in [1, 3] {
                let form = if n == 1 { "one" } else { "other" };
                let counted = t_count(
                    lang,
                    "account.warning.live_locked",
                    n,
                    &[("until", "14:05")],
                );
                assert_eq!(
                    counted,
                    t_params(
                        lang,
                        &format!("account.warning.live_locked.{form}"),
                        &[("count", &n.to_string()), ("until", "14:05")]
                    )
                );
                assert!(counted.starts_with(&n.to_string()) && counted.contains("14:05"));
            }
        }
        // A template's [Words] say the same.
        struct Page {
            lang: Lang,
        }
        words!(Page);
        let page = Page { lang: Lang::Nb };
        assert_eq!(
            page.tc("account.warning.live", 2, &[]),
            t_count(Lang::Nb, "account.warning.live", 2, &[])
        );
        assert_eq!(page.t("common.save"), "Lagre");
        assert_eq!(
            page.tp("account.name.hint", &[("username", "mamma")]),
            t_params(Lang::Nb, "account.name.hint", &[("username", "mamma")])
        );
    }

    #[test]
    fn an_error_is_plain_text_in_the_language() {
        let res = error(Lang::Nb, StatusCode::FORBIDDEN, "error.csrf");
        assert_eq!(res.status(), StatusCode::FORBIDDEN);
        let kind = res.headers()[axum::http::header::CONTENT_TYPE]
            .to_str()
            .unwrap();
        assert!(kind.starts_with("text/plain"), "{kind}");
    }
}
