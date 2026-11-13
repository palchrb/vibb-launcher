//! No English on a Norwegian page (design 70 §8, slice 4): a parent reading in Norwegian opens every page and JSON
//! route of a seeded family (`tests::seeded_family`), and no whole word on them is one of the English catalog's that
//! no Norwegian value has.

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};

use super::TestApp;
use super::seeded_family;
use crate::i18n::{Lang, catalog_words, language_words, t};

/// The fixtures' names, on every page: the parent "mamma" (her username and «Mamma», quoted in a hint) and Anna's
/// avatar initial "A".
const FIXTURES: &[&str] = &["mamma", "a"];

/// Words that are facts on one page, not English: the phone's `found` code in the security log's blocked-app line
/// ("(outside_play, install, …)"), and the phone's own answer that Activity keeps in a reworded row's `title`
/// ("refused: mismatch", Ola's Child PIN; on the phone's Activity page since 2026-11-13).
const ALLOWED: &[(&str, &str)] = &[("/security", "install"), ("/devices/2/activity", "refused")];

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
        // A script's sentence keeps its `{name}` slots (design 93's `data-w-on`): the script fills them, the reader
        // never sees them.
        static SLOT: std::sync::LazyLock<regex::Regex> =
            std::sync::LazyLock::new(|| regex::Regex::new(r"\{[a-z_]+\}").unwrap());
        for (n, v) in &attributes {
            if n.starts_with("data-w-") {
                kept.push_str(&SLOT.replace_all(v, " "));
                kept.push(' ');
            } else if ["title", "aria-label", "placeholder", "alt"].contains(&n.as_str())
                || (button && n == "value")
            {
                kept.push_str(v);
                kept.push(' ');
            }
        }
        kept
    });
    // Askama's escapes and the pages' own entities, as the reader sees them.
    static ENTITY: std::sync::LazyLock<regex::Regex> =
        std::sync::LazyLock::new(|| regex::Regex::new(r"&(#x?[0-9a-fA-F]+|[a-z]+);").unwrap());
    ENTITY
        .replace_all(&text, |caps: &regex::Captures| {
            let name = &caps[1];
            let code = match name.strip_prefix("#x").or_else(|| name.strip_prefix("#X")) {
                Some(hex) => u32::from_str_radix(hex, 16).ok(),
                None => name.strip_prefix('#').and_then(|dec| dec.parse().ok()),
            };
            match (code.and_then(char::from_u32), name) {
                (Some(c), _) => c.to_string(),
                (None, "amp") => "&".to_string(),
                (None, "quot") => "\"".to_string(),
                (None, "lt") => "<".to_string(),
                (None, "gt") => ">".to_string(),
                _ => " ".to_string(),
            }
        })
        .into_owned()
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

/// The English words on `text` ([language_words]: in some English value and in no Norwegian one, but `allowed`),
/// and any `{param}` left unfilled.
fn english_on(
    text: &str,
    english: &std::collections::HashSet<String>,
    allowed: &[&str],
) -> Vec<String> {
    static PARAM: std::sync::LazyLock<regex::Regex> =
        std::sync::LazyLock::new(|| regex::Regex::new(r"\{[a-z_]+\}").unwrap());
    let mut found = Vec::new();
    for line in text.lines() {
        let line = line.split_whitespace().collect::<Vec<_>>().join(" ");
        let mut words: Vec<String> = language_words(&line)
            .into_iter()
            .filter(|word| english.contains(word) && !allowed.contains(&word.as_str()))
            .collect();
        words.extend(PARAM.find_iter(&line).map(|m| m.as_str().to_string()));
        if !words.is_empty() {
            found.push(format!("{} - in: {line}", words.join(", ")));
        }
    }
    found
}

/// A request in Norwegian (`Accept-Language`, for the pages before an account), with the session `cookie`.
async fn send_nb(
    app: &TestApp,
    method: Method,
    uri: &str,
    cookie: Option<&str>,
    form: &[(&str, &str)],
) -> super::TestResponse {
    let mut builder = Request::builder()
        .method(method)
        .uri(uri)
        .header(header::ACCEPT_LANGUAGE, "nb-NO,nb;q=0.9,en;q=0.5")
        .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded");
    if let Some(cookie) = cookie {
        builder = builder.header(header::COOKIE, cookie);
    }
    let body = form_urlencoded::Serializer::new(String::new())
        .extend_pairs(form)
        .finish();
    app.send(builder.body(Body::from(body)).unwrap()).await
}

/// A GET that follows the server's redirects to its own pages (a sub-page that lives elsewhere, a page that
/// isn't this account's): the page landed on, and its answer.
async fn landed(app: &TestApp, uri: &str, cookie: Option<&str>) -> (String, super::TestResponse) {
    let mut uri = uri.to_string();
    for _ in 0..4 {
        let res = send_nb(app, Method::GET, &uri, cookie, &[]).await;
        match res.location() {
            Some(next) if res.status.is_redirection() && next.starts_with('/') => {
                uri = next.split('#').next().unwrap_or(next).to_string();
            }
            _ => return (uri, res),
        }
    }
    panic!("{uri}: too many redirects");
}

/// An account in onboarding (`must_change_password` / no two-factor yet), reading in Norwegian: its session.
async fn onboarding(app: &TestApp, username: &str, change_password: bool, totp: bool) -> String {
    const PASSWORD: &str = "a generated first password";
    sqlx::query(
        "INSERT INTO admin_users (username, password_hash, must_change_password, totp_secret, totp_enabled, lang) \
         VALUES (?, ?, ?, ?, ?, 'nb')",
    )
    .bind(username)
    .bind(crate::security::hash_password(PASSWORD))
    .bind(change_password)
    .bind(totp.then_some("JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"))
    .bind(totp)
    .execute(&app.db)
    .await
    .unwrap();
    let res = send_nb(
        app,
        Method::POST,
        "/login",
        None,
        &[("username", username), ("password", PASSWORD)],
    )
    .await;
    super::session_cookie(&res).unwrap_or_else(|| panic!("{username}: no session: {}", res.text()))
}

#[tokio::test]
async fn nb_pages_have_no_english() {
    let app = TestApp::new().await;
    let family = seeded_family::seed(&app, Lang::Nb).await;
    let nb = catalog_words(Lang::Nb);
    let english: std::collections::HashSet<String> =
        catalog_words(Lang::En).difference(&nb).cloned().collect();
    // The test's own reach (qa-s-i18n-4-code M1): the plain English words a leftover would most likely be.
    for word in [
        "the", "can't", "phone", "settings", "add", "password", "account", "device", "allow",
        "open", "set", "call", "code", "key", "error", "failed", "minutes", "off",
    ] {
        assert!(
            english.contains(word),
            "{word} is no English the test can see"
        );
    }

    let (anna, ola, liv) = (family.anna, family.ola, family.liv);
    // Design 93: a rule turned on now, and the summary with each sheet open.
    sqlx::query(
        "INSERT INTO time_lock_now (device_id, rule_id, rule_name, zone, local_date, start_minute, \
         end_minute, ends_at) VALUES (?, ?, 'Skole', 'Europe/Oslo', '2026-11-13', 600, 1439, \
         datetime('now', '+1 hour'))",
    )
    .bind(anna)
    .bind(family.rule)
    .execute(&app.db)
    .await
    .unwrap();
    let mut pages = vec![
        format!("/devices/{anna}?open=rules&mode=on"),
        format!("/devices/{anna}?open=rules&mode=pause"),
        format!("/devices/{anna}?open=time"),
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
        "/music/spotify/done".to_string(),
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
        "/account/parents".to_string(),
        "/account/password-sign-in".to_string(),
        format!("/devices/{liv}"),
        format!("/devices/{liv}/provision"),
        format!("/devices/{anna}/zones/new"),
        format!("/devices/{anna}/zones/{}", family.zone),
        format!("/devices/{anna}/locations"),
        format!("/devices/{anna}/calendar/add"),
        format!("/devices/{anna}/calendar/add?kind=caldav"),
        format!("/devices/{anna}/calendar/links/{}", family.calendar_link),
        format!("/devices/{anna}/calendar/caldav/{}", family.caldav),
        format!("/devices/{anna}/calendar/rules/{}", family.calendar_rule),
    ];
    for id in [anna, ola] {
        for sub in [
            "",
            "/activity",
            "/about",
            "/advanced",
            "/lock",
            "/apps",
            "/home",
            "/weather",
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
    // Design 77 §14 (QA L4): "Legg til…" from Anna's seeded contact request, with "Bruk Matrix-ID-en".
    pages.push(format!(
        "/devices/{anna}/calls?request=10112233445566778899aabbccddeeff"
    ));
    // Design 80: the page's Avansert open, and Add Contact from a held number ("Tilpasset", its Avansert open); the
    // seeded Mormor is split, her own Avansert drawn open.
    pages.push(format!("/devices/{anna}/calls?advanced=1"));
    pages.push(format!("/devices/{anna}/calls?add_number=%2B4798765432"));
    // Every one-shot flash, on the page that shows it (qa-s-i18n-4-code M2), each checked to be there.
    let mut flashes: Vec<(String, &str)> = Vec::new();
    for code in ["extra", "pause", "gone", "ended", "over"] {
        let (_, key, _) = crate::handlers::lifts::done_flash(code).unwrap();
        flashes.push((format!("/devices/{anna}?done={code}"), key));
    }
    for code in ["end_windows_sent", "start_pause_sent", "allow_blocked_sent"] {
        let (_, key) = crate::window_relock::flash(code).unwrap();
        flashes.push((format!("/devices/{anna}?notice={code}"), key));
    }
    for code in [
        "saved",
        "saved_safe_boot",
        "saved_not_kept",
        "saved_safe_boot_not_kept",
        "cleared",
        "pin_sent",
        "pin_not_sent",
        "pin_not_kept",
        "keyguard_no_pin",
        "invalid",
        "needs_override",
        "same_as_override",
    ] {
        let (text, _) = crate::kid_lock::flash_text(Lang::Nb, code).unwrap();
        flashes.push((format!("/devices/{ola}/lock?notice={code}"), text));
    }
    for code in [
        "parent_pin_saved",
        "parent_pin_saved_easy",
        "parent_pin_cleared",
        "parent_pin_invalid",
        "override_is_kid_pin",
        "override_needed_by_lock",
    ] {
        let (text, _) = crate::parent_pin::flash_text(Lang::Nb, code).unwrap();
        flashes.push((format!("/devices/{ola}/lock?notice={code}"), text));
    }
    let json_routes = [
        "/notifications/subscriptions.json".to_string(),
        format!("/apps/tracked/{}/install-now.json", family.launcher),
        format!("/devices/{anna}/locate-result.json"),
        "/music/cards".to_string(),
    ];

    let mut found = Vec::new();
    let mut check = |page: &str, text: &str| {
        let allowed: Vec<&str> = ALLOWED
            .iter()
            .filter(|(on, _)| *on == page.split('?').next().unwrap_or(page))
            .map(|(_, word)| *word)
            .chain(FIXTURES.iter().copied())
            .collect();
        for line in english_on(text, &english, &allowed) {
            found.push(format!("{page}: {line}"));
        }
    };
    let html_of = |page: &str, res: &super::TestResponse| {
        let html = res.text();
        assert!(html.contains("<html lang=\"nb\""), "{page} isn't nb");
        seen_text(&html)
    };
    for page in &pages {
        let (at, res) = landed(&app, page, Some(&family.cookie)).await;
        assert_eq!(
            res.status,
            StatusCode::OK,
            "{page} (at {at}): {}",
            res.text()
        );
        check(&at, &html_of(&at, &res));
    }
    for (page, text) in &flashes {
        let (at, res) = landed(&app, page, Some(&family.cookie)).await;
        assert_eq!(res.status, StatusCode::OK, "{page}: {}", res.text());
        let seen = html_of(&at, &res);
        // A key (a lift's, a command's) is the flash's own; a text is already worded.
        let flash = if text.contains(' ') {
            text
        } else {
            t(Lang::Nb, text)
        };
        assert!(
            seen.split_whitespace()
                .collect::<Vec<_>>()
                .join(" ")
                .contains(flash),
            "{page}: no flash {flash:?}"
        );
        check(page, &seen);
    }
    for route in &json_routes {
        let res = send_nb(&app, Method::GET, route, Some(&family.cookie), &[]).await;
        assert_eq!(res.status, StatusCode::OK, "{route}: {}", res.text());
        let mut text = String::new();
        json_text(&res.json(), &mut text);
        check(route, &text);
    }
    // A refused form comes back worded: a contact's number, a zone without a name.
    for (route, form) in [
        (
            format!("/devices/{anna}/contacts"),
            vec![("name", "Bestemor"), ("number", "abc")],
        ),
        (
            format!("/devices/{anna}/zones"),
            vec![
                ("name", ""),
                ("latitude", "x"),
                ("longitude", ""),
                ("radius_m", "0"),
            ],
        ),
    ] {
        let res = send_nb(&app, Method::POST, &route, Some(&family.cookie), &form).await;
        assert_eq!(
            res.status,
            StatusCode::BAD_REQUEST,
            "{route}: {}",
            res.text()
        );
        check(&route, &html_of(&route, &res));
    }
    // Before an account: the sign-in page, and the onboarding steps (`Accept-Language` and the account's own).
    let (_, login) = landed(&app, "/login", None).await;
    assert_eq!(login.status, StatusCode::OK);
    check("/login", &html_of("/login", &login));
    for (user, change, totp, page) in [
        ("ny1", true, false, "/auth/change-password"),
        ("ny2", false, false, "/auth/setup-2fa"),
        ("ny3", false, true, "/auth/verify-2fa"),
    ] {
        let cookie = onboarding(&app, user, change, totp).await;
        let (at, res) = landed(&app, page, Some(&cookie)).await;
        assert_eq!(
            res.status,
            StatusCode::OK,
            "{page} (at {at}): {}",
            res.text()
        );
        assert_eq!(at, page, "{user} lands elsewhere");
        check(page, &html_of(page, &res));
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
    let english = ["the".to_string(), "can't".to_string(), "phone".to_string()]
        .into_iter()
        .collect();
    assert_eq!(
        english_on("Det er the phone", &english, &[]),
        ["the, phone - in: Det er the phone"]
    );
    assert_eq!(english_on("You can’t", &english, &[]).len(), 1);
    assert!(english_on("Det er the", &english, &["the"]).is_empty());
    // An address, a path, a package, a name, a «quote» or a command is no English.
    assert!(
        english_on(
            "https://example.org/the/phone no.the.phone the_phone Vibb Phone «the phone» adb shell the phone",
            &english,
            &[]
        )
        .is_empty()
    );
    assert!(english_on("Telefonen", &english, &[]).is_empty());
    // A parameter left unfilled.
    assert_eq!(
        english_on("Lagret {tid}", &english, &[]),
        ["{tid} - in: Lagret {tid}"]
    );
    assert_eq!(
        seen_text("<p>R&amp;D &#8250; &#x27;x&#39;</p>").trim(),
        "R&D › 'x'"
    );
}
