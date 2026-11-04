//! No English on a Norwegian page (design 70 §8, slice 4): a parent reading in Norwegian opens every page and JSON
//! route of a seeded family (`tests::seeded_family`), and no whole word on them is one of the English catalog's that
//! no Norwegian value has.

use axum::http::StatusCode;

use super::TestApp;
use super::seeded_family;
use crate::i18n::{Lang, catalog_words, words_of_text};

/// Words that are facts, not English: the phone's `found` code in the security log's blocked-app line
/// ("(outside_play, install, …)").
const ALLOWED: &[&str] = &["install"];

/// What a reader sees of a page: its text and the attributes read out or shown (`title`, `aria-label`,
/// `placeholder`, `alt`, every `data-w-*` a script shows, an input button's `value`), without scripts, styles and
/// comments.
fn seen_text(html: &str) -> String {
    let gone = regex::Regex::new(
        r"(?is)<!--.*?-->|<script\b.*?</script>|<style\b.*?</style>|<!DOCTYPE[^>]*>",
    )
    .unwrap();
    let text = gone.replace_all(html, " ");
    let tag = regex::Regex::new(r"(?s)<(/?[a-zA-Z][a-zA-Z0-9-]*)([^>]*)>").unwrap();
    let attribute =
        regex::Regex::new(r#"([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)"#)
            .unwrap();
    let text = tag.replace_all(&text, |caps: &regex::Captures| {
        let name = caps[1].to_ascii_lowercase();
        let attributes: Vec<(String, String)> = attribute
            .captures_iter(&caps[2])
            .map(|a| {
                (
                    a[1].to_ascii_lowercase(),
                    a[2].trim_matches(['"', '\'']).to_string(),
                )
            })
            .collect();
        let button = name == "input"
            && attributes
                .iter()
                .any(|(n, v)| n == "type" && (v == "submit" || v == "button"));
        let mut kept = String::from(" ");
        for (n, v) in &attributes {
            if ["title", "aria-label", "placeholder", "alt"].contains(&n.as_str())
                || n.starts_with("data-w-")
                || (button && n == "value")
            {
                kept.push_str(v);
                kept.push(' ');
            }
        }
        kept
    });
    text.replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace("&#38;", "&")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
}

/// Every string in a JSON answer.
fn json_text(value: &serde_json::Value, out: &mut String) {
    match value {
        serde_json::Value::String(text) => {
            // A JSON route's HTML (the music cards) is read as a page.
            out.push_str(&seen_text(text));
            out.push('\n');
        }
        serde_json::Value::Array(items) => items.iter().for_each(|item| json_text(item, out)),
        serde_json::Value::Object(map) => map.values().for_each(|item| json_text(item, out)),
        _ => {}
    }
}

/// The English words on `text`: in some English value, in no Norwegian one, and not the fixtures'.
fn english_on(text: &str, english: &std::collections::HashSet<String>) -> Vec<String> {
    // Addresses, paths, commands' arguments, packages and codes are no language: a token with `/`, `_`, `=`, `@`
    // or a dot between letters.
    static FACT: std::sync::LazyLock<regex::Regex> =
        std::sync::LazyLock::new(|| regex::Regex::new(r"\S*(?:[/_=@]|\p{L}\.\p{L})\S*").unwrap());
    let mut found = Vec::new();
    for line in text.lines() {
        let line = line.split_whitespace().collect::<Vec<_>>().join(" ");
        let words: Vec<String> = words_of_text(&FACT.replace_all(&line, " "))
            .into_iter()
            .filter(|word| english.contains(word) && !ALLOWED.contains(&word.as_str()))
            .collect();
        if !words.is_empty() {
            found.push(format!("{} - in: {line}", words.join(", ")));
        }
    }
    found
}

#[tokio::test]
async fn nb_pages_have_no_english() {
    let app = TestApp::new().await;
    let family = seeded_family::seed(&app, Lang::Nb).await;
    let nb = catalog_words(Lang::Nb);
    let english: std::collections::HashSet<String> =
        catalog_words(Lang::En).difference(&nb).cloned().collect();
    // The test's own reach: plain English words are in the set.
    for word in ["the", "phone", "can't", "minutes"] {
        assert!(
            english.contains(word) || nb.contains(word),
            "{word} is in no catalog"
        );
    }
    assert!(english.contains("the") && english.contains("can't"));

    let (anna, ola, liv) = (family.anna, family.ola, family.liv);
    let mut pages = vec![
        "/devices".to_string(),
        "/devices/new".to_string(),
        "/devices/locate".to_string(),
        "/apps".to_string(),
        "/apps/tracked/new".to_string(),
        format!("/apps/tracked/{}", family.app),
        format!("/apps/tracked/{}", family.launcher),
        "/music".to_string(),
        "/music/settings".to_string(),
        "/music/import".to_string(),
        "/music/categories".to_string(),
        "/music/storytel".to_string(),
        "/music/spotify".to_string(),
        format!("/music/entries/{}", family.entry),
        "/settings".to_string(),
        "/settings/provisioning".to_string(),
        "/schedules".to_string(),
        "/schedules/screen-time".to_string(),
        "/schedules/rules/new".to_string(),
        format!("/schedules/rules/{}", family.rule),
        "/dns".to_string(),
        "/dns/log".to_string(),
        "/wallpapers".to_string(),
        "/backups".to_string(),
        "/updates".to_string(),
        "/security".to_string(),
        "/notifications".to_string(),
        "/notifications/settings".to_string(),
        "/account".to_string(),
        format!("/devices/{liv}"),
        format!("/devices/{liv}/provision"),
        format!("/devices/{anna}/zones/new"),
        format!("/devices/{anna}/zones/{}", family.zone),
        format!("/devices/{anna}/locations"),
        format!("/devices/{anna}/calendar/add"),
    ];
    for id in [anna, ola] {
        for sub in [
            "",
            "/about",
            "/advanced",
            "/lock",
            "/apps",
            "/home",
            "/filter",
            "/calls",
            "/time",
            "/bedtime",
            "/calendar",
            "/calendar/rules/new",
            "/music",
        ] {
            pages.push(format!("/devices/{id}{sub}"));
        }
    }
    let json_routes = [
        "/notifications/subscriptions.json".to_string(),
        format!("/apps/tracked/{}/install-now.json", family.launcher),
        format!("/devices/{anna}/locate-result.json"),
        "/music/cards".to_string(),
    ];

    let mut found = Vec::new();
    for page in &pages {
        let res = app.get_page(page, &family.cookie).await;
        assert_eq!(res.status, StatusCode::OK, "{page}: {}", res.text());
        let html = res.text();
        assert!(html.contains("<html lang=\"nb\""), "{page} isn't nb");
        for line in english_on(&seen_text(&html), &english) {
            found.push(format!("{page}: {line}"));
        }
    }
    for route in &json_routes {
        let res = app.get_page(route, &family.cookie).await;
        assert_eq!(res.status, StatusCode::OK, "{route}: {}", res.text());
        let mut text = String::new();
        json_text(&res.json(), &mut text);
        for line in english_on(&text, &english) {
            found.push(format!("{route}: {line}"));
        }
    }
    assert!(
        found.is_empty(),
        "English on nb pages ({}):\n{}",
        found.len(),
        found.join("\n")
    );
}

/// The scan sees what a reader does, and finds an English word only.
#[test]
fn the_scan_reads_text_and_shown_attributes() {
    let html = r#"<html><head><style>.x { color: red }</style><script>var s = "the phone";</script></head>
<body><p title="Hei">Tekst <b>her</b></p><!-- the note --><input type="submit" value="Lagre">
<button value="the">Ok</button><span data-w-done="Ferdig" class="the-phone"></span></body></html>"#;
    let seen = seen_text(html);
    assert_eq!(
        seen.split_whitespace().collect::<Vec<_>>(),
        ["Hei", "Tekst", "her", "Lagre", "Ok", "Ferdig"]
    );
    let english = ["the".to_string(), "can't".to_string()]
        .into_iter()
        .collect();
    assert_eq!(
        english_on("Det er the phone", &english),
        ["the - in: Det er the phone"]
    );
    assert_eq!(english_on("You can’t", &english).len(), 1);
    // An address, a path or a package is no English.
    assert!(
        english_on(
            "https://example.org/the/phone no.the.phone the_phone",
            &english
        )
        .is_empty()
    );
    assert!(english_on("Telefonen", &english).is_empty());
}
