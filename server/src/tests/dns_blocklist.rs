//! Security review M6: the DNS blocklist fails closed, per feed. The lists are fetched through `music_net` with
//! public reach and https-only redirects, answered here by the canned fetch.

use axum::http::{Method, StatusCode};

use super::TestApp;
use crate::dns_engine;

const GOOD: u8 = 0;
const SERVER_ERROR: u8 = 1;
const PORTAL_PAGE: u8 = 2;
const TOO_BIG: u8 = 3;

const FEED: &str = "https://lists.example/feed";

/// The feed's answer from now on.
fn serve(app: &TestApp, mode: u8) {
    let (status, body) = match mode {
        GOOD => (
            200,
            "# a hosts file\n0.0.0.0 ads.example\ntracker.example.\n".to_string(),
        ),
        SERVER_ERROR => (500, "oops".to_string()),
        PORTAL_PAGE => (
            200,
            "<html><body>Sign in to the Wi-Fi</body></html>\n".to_string(),
        ),
        _ => (
            200,
            "a.example\n".repeat(dns_engine::MAX_FEED_BYTES / 10 + 1),
        ),
    };
    app.fetch.answer(FEED, status, body);
}

/// The feed at [FEED], answering well: returns its URL.
fn feed_server(app: &TestApp) -> String {
    serve(app, GOOD);
    FEED.to_string()
}

async fn add_feed(app: &TestApp, name: &str, url: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO dns_blocklists (name, url, enabled) VALUES (?, ?, 1) RETURNING id",
    )
    .bind(name)
    .bind(url)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

async fn blocklist(app: &TestApp, token: &str) -> (StatusCode, String) {
    let res = app
        .request(Method::GET, "/api/devices/dns-blocklist", Some(token), None)
        .await;
    (res.status, res.text())
}

async fn compile(app: &TestApp) {
    dns_engine::compile_blocklist(&app.state, &app.state.dns_compiled).await;
}

#[tokio::test]
async fn a_failing_feed_keeps_its_domains_and_one_never_loaded_is_left_out() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (phone, token) = app.enrolled_device("phone").await;
    let url = feed_server(&app);
    let ads = add_feed(&app, "Ads", &url).await;

    // Before the first load the feed has nothing: left out, the list answered all the same.
    let (status, body) = blocklist(&app, &token).await;
    assert_eq!(status, StatusCode::OK);
    assert!(!body.contains("ads.example"), "{body}");

    compile(&app).await;
    let (status, body) = blocklist(&app, &token).await;
    assert_eq!(status, StatusCode::OK);
    assert!(
        body.contains("\"ads.example\"") && body.contains("\"tracker.example\""),
        "{body}"
    );
    let loaded_hash = app.state.dns_compiled.read().await.content_hash.clone();

    // A 500, a captive portal's page and a body over the cap: each keeps the domains.
    for (bad, why) in [
        (SERVER_ERROR, "500"),
        (PORTAL_PAGE, "no domains"),
        (TOO_BIG, "larger than"),
    ] {
        serve(&app, bad);
        compile(&app).await;
        let (status, body) = blocklist(&app, &token).await;
        assert_eq!(status, StatusCode::OK, "{why}");
        assert!(body.contains("\"ads.example\""), "{why}: {body}");
        assert_eq!(
            app.state.dns_compiled.read().await.content_hash,
            loaded_hash,
            "{why}"
        );
        let page = app.get_page("/dns", &cookie).await.text();
        assert!(page.contains("The last download failed ("), "{why}");
        assert!(page.contains(why), "{why}");
    }
    // Design 116 §2.1: the feed's health - the 500 transient, then the page and the size the parent's to mend.
    let health = || async {
        crate::integration_health::row(&app.db, "blocklist", &format!("feed:{ads}"))
            .await
            .unwrap()
            .unwrap()
    };
    let row = health().await;
    assert_eq!(
        (row.class, row.failures, row.error.as_deref()),
        (
            Some(crate::integration_health::Class::Parent),
            3,
            Some("too_big")
        )
    );

    // A second feed that never loads (a mistyped URL): left out - no phone ever got it - and the
    // rest, the parent's own domains included, still reaches the phone (QA of the security wave,
    // Low 8: a 503 froze every list).
    add_feed(&app, "Broken", "https://127.0.0.1:9/nothing").await;
    sqlx::query(
        "INSERT INTO dns_custom_domains (domain, list_type) VALUES ('tiktok.com', 'block')",
    )
    .execute(&app.db)
    .await
    .unwrap();
    serve(&app, GOOD);
    compile(&app).await;
    let (status, body) = blocklist(&app, &token).await;
    assert_eq!(status, StatusCode::OK);
    assert!(
        body.contains("\"ads.example\"") && body.contains("\"tiktok.com\""),
        "{body}"
    );
    let page = app.get_page("/dns", &cookie).await.text();
    assert!(page.contains("Not loaded yet ("), "{page}");
    assert_eq!(
        health().await.failing_since,
        None,
        "a good download clears it"
    );
    // The phone's Web Filter page (design 31 step 5): the list's problem as a short title under
    // its name, the download error only on the global page.
    let page = app
        .get_page(&super::device_page(phone, super::Section::Filter), &cookie)
        .await
        .text();
    let broken = &page[page.find(">Broken<").expect("the list's row")..];
    let broken = &broken[..broken.find("</label>").unwrap()];
    assert!(
        broken.contains("<span class=\"row-sub text-alarm\">Not loaded yet</span>"),
        "{broken}"
    );
    assert!(!page.contains("127.0.0.1:9"), "{page}");
    let ads = &page[page.find(">Ads<").expect("the list's row")..];
    assert!(!ads[..ads.find("</label>").unwrap()].contains("row-sub"));
}

/// A failing list on a phone's Web Filter page: "Last download failed", not the raw error.
#[tokio::test]
async fn a_phones_filter_page_names_a_failing_list_without_the_error() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (phone, _) = app.enrolled_device("phone").await;
    let url = feed_server(&app);
    add_feed(&app, "Ads", &url).await;
    compile(&app).await;
    serve(&app, SERVER_ERROR);
    compile(&app).await;
    let page = app
        .get_page(&super::device_page(phone, super::Section::Filter), &cookie)
        .await
        .text();
    let ads = &page[page.find(">Ads<").expect("the list's row")..];
    let ads = &ads[..ads.find("</label>").unwrap()];
    assert!(
        ads.contains("<span class=\"row-sub text-alarm\">Last download failed</span>"),
        "{ads}"
    );
    assert!(!ads.contains("500"), "{ads}");
    assert!(!page.contains("The last download failed"));
}

/// QA of the security wave, Low 8: a feed's last good domains survive a restart without
/// internet, and a deleted feed's copy goes.
#[tokio::test]
async fn a_feed_keeps_its_domains_across_a_restart() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (_, token) = app.enrolled_device("phone").await;
    let url = feed_server(&app);
    let feed = add_feed(&app, "Ads", &url).await;
    compile(&app).await;
    let saved = app.state.dns_feed_dir.join(format!("{feed}.txt"));
    assert_eq!(
        std::fs::read_to_string(&saved).unwrap(),
        "ads.example\ntracker.example\n"
    );

    // "Restart": nothing in memory, and the feed's server is down.
    *app.state.dns_compiled.write().await =
        std::sync::Arc::try_unwrap(dns_engine::empty_compiled_blocklist())
            .ok()
            .unwrap()
            .into_inner();
    serve(&app, SERVER_ERROR);
    compile(&app).await;
    let (status, body) = blocklist(&app, &token).await;
    assert_eq!(status, StatusCode::OK);
    assert!(body.contains("\"ads.example\""), "{body}");
    let page = app.get_page("/dns", &cookie).await.text();
    assert!(page.contains("its previous 2 domains are kept"), "{page}");

    sqlx::query("DELETE FROM dns_blocklists WHERE id = ?")
        .bind(feed)
        .execute(&app.db)
        .await
        .unwrap();
    compile(&app).await;
    assert!(!saved.exists());
}

#[tokio::test]
async fn database_errors_never_shrink_the_list() {
    let app = TestApp::new().await;
    let (_, token) = app.enrolled_device("phone").await;
    let url = feed_server(&app);
    add_feed(&app, "Ads", &url).await;
    compile(&app).await;
    let hash = app.state.dns_compiled.read().await.content_hash.clone();

    sqlx::query("ALTER TABLE dns_custom_domains RENAME TO dns_custom_domains_away")
        .execute(&app.db)
        .await
        .unwrap();
    // The compile keeps what it had; the endpoint answers 500, not a list without the custom
    // domains.
    compile(&app).await;
    assert_eq!(app.state.dns_compiled.read().await.content_hash, hash);
    assert!(!app.state.dns_compiled.read().await.lists.is_empty());
    let (status, _) = blocklist(&app, &token).await;
    assert_eq!(status, StatusCode::INTERNAL_SERVER_ERROR);
}

#[test]
fn a_feed_parses_to_domains_only() {
    let parsed = dns_engine::parse_feed(
        "# comment\n0.0.0.0 Ads.Example.\n0.0.0.0\tTabbed.example # trailing\n127.0.0.1 local.example\n\
         plain.example\n||adblock.example^\n||rule.example^$third-party\n<html>\nlocalhost\n\
         127.0.0.1 localhost\n10.0.0.1 lan.example\nnot a domain.example\n",
    );
    let mut parsed: Vec<&str> = parsed.iter().map(String::as_str).collect();
    parsed.sort();
    assert_eq!(
        parsed,
        [
            "adblock.example",
            "ads.example",
            "local.example",
            "plain.example",
            "tabbed.example"
        ]
    );
}

/// Design 116 §2.1: every download failure's class.
#[test]
fn every_feed_failure_is_classed() {
    use crate::integration_health::Class::*;
    use dns_engine::FeedFailure;
    for (failure, class) in [
        (FeedFailure::Network("reset".into()), Transient),
        (FeedFailure::Status(503), Transient),
        (FeedFailure::Status(408), Transient),
        (FeedFailure::Status(429), Transient),
        (FeedFailure::Status(404), Parent),
        (FeedFailure::Status(410), Parent),
        (FeedFailure::TooBig, Parent),
        (FeedFailure::NotAList, Parent),
        (FeedFailure::Refused("private".into()), Parent),
        (FeedFailure::NeedsHttps, Parent),
    ] {
        assert_eq!(failure.class(), class, "{failure:?}");
    }
}

/// Design 126 C1: a phone made in the PWA gets "Ads & tracking" on by its own override while the list is off for every
/// phone; an older phone keeps what it had, and Settings' hub counts the list in force on the new phone.
#[tokio::test]
async fn a_new_phone_gets_the_ads_list_on() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (older, _) = app.create_device("older").await;
    let ads: i64 =
        sqlx::query_scalar("SELECT id FROM dns_blocklists WHERE name = 'Ads & tracking'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    sqlx::query("UPDATE dns_blocklists SET enabled = 0")
        .execute(&app.db)
        .await
        .unwrap();
    let hub = |app: &TestApp| {
        let db = app.db.clone();
        async move {
            let zone = crate::display_time::Zone::from_setting("Europe/Oslo");
            let dir = tempfile::tempdir().unwrap();
            crate::handlers::settings_hub::hub_values(&db, dir.path(), &zone, chrono::Utc::now())
                .await
                .web_filter
        }
    };
    assert_eq!(hub(&app).await.as_deref(), Some("No lists"));

    let new_phone = |name: &'static str| {
        let app = &app;
        let cookie = cookie.clone();
        async move {
            let res = app
                .request_form(
                    Method::POST,
                    "/devices/new",
                    Some(&cookie),
                    &[("name", name)],
                )
                .await;
            assert!(res.status.is_redirection(), "{}", res.text());
            let id: i64 = sqlx::query_scalar("SELECT id FROM devices WHERE name = ?")
                .bind(name)
                .fetch_one(&app.db)
                .await
                .unwrap();
            let rows: Vec<(i64, bool)> = sqlx::query_as(
                "SELECT blocklist_id, enabled FROM device_blocklist_overrides WHERE device_id = ?",
            )
            .bind(id)
            .fetch_all(&app.db)
            .await
            .unwrap();
            rows
        }
    };
    assert_eq!(new_phone("Lily").await, [(ads, true)]);
    let older_rows: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM device_blocklist_overrides WHERE device_id = ?")
            .bind(older)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(older_rows, 0, "an older phone is untouched");
    assert_eq!(hub(&app).await.as_deref(), Some("1 list"));

    // On for every phone already: no override, which would pin the phone against a later change of the switch.
    sqlx::query("UPDATE dns_blocklists SET enabled = 1 WHERE id = ?")
        .bind(ads)
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(new_phone("Max").await, []);

    // The list removed: nothing written, the phone made all the same.
    sqlx::query("DELETE FROM dns_blocklists WHERE id = ?")
        .bind(ads)
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(new_phone("Ola").await, []);
}

/// A list is fetched from public addresses only and its redirects only to https: a hop to a tailnet literal, to
/// `http://` or to a name with only private answers is refused (the list stays not loaded, its health the parent's);
/// a hop to another https address is followed.
#[tokio::test]
async fn a_list_is_fetched_over_https_from_public_addresses_only() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let body = "0.0.0.0 ads.example\n";
    app.fetch.redirect(
        "https://lists.example/tailnet",
        302,
        "https://100.64.0.1/hosts",
    );
    app.fetch.redirect(
        "https://lists.example/plain",
        301,
        "http://mirror.example/hosts",
    );
    app.fetch.redirect(
        "https://lists.example/lan",
        302,
        "https://lan.example/hosts",
    );
    app.fetch.dns("lan.example", &["192.168.1.20"]);
    app.fetch.answer("https://lan.example/hosts", 200, body);
    app.fetch.answer("https://100.64.0.1/hosts", 200, body);
    app.fetch.answer("http://mirror.example/hosts", 200, body);
    app.fetch
        .redirect("https://lists.example/moved", 308, "/v2/hosts");
    app.fetch
        .answer("https://lists.example/v2/hosts", 200, body);
    let refused = [
        add_feed(&app, "Tailnet", "https://lists.example/tailnet").await,
        add_feed(&app, "Plain", "https://lists.example/plain").await,
        add_feed(&app, "Lan", "https://lists.example/lan").await,
    ];
    let moved = add_feed(&app, "Moved", "https://lists.example/moved").await;
    compile(&app).await;

    {
        let compiled = app.state.dns_compiled.read().await;
        let list = |id: i64| {
            compiled
                .lists
                .iter()
                .find(|l| l.blocklist_id == id)
                .unwrap()
        };
        for id in refused {
            let list = list(id);
            assert!(
                !list.loaded && list.domains.is_empty(),
                "{:?}",
                list.last_error
            );
            assert!(
                list.last_error
                    .as_deref()
                    .unwrap_or_default()
                    .starts_with("not fetched: "),
                "{:?}",
                list.last_error
            );
        }
        assert!(list(moved).loaded && list(moved).domains.contains("ads.example"));
    }
    for id in refused {
        let health = crate::integration_health::row(&app.db, "blocklist", &format!("feed:{id}"))
            .await
            .unwrap()
            .unwrap();
        assert_eq!(
            (health.class, health.error.as_deref()),
            (
                Some(crate::integration_health::Class::Parent),
                Some("refused")
            )
        );
    }
    // The private answers are refused by the fetch itself (its resolver, the literal check); the http hop is never
    // asked.
    let requests = app.fetch.requests.lock().unwrap().clone();
    assert!(!requests.iter().any(|r| r.url.starts_with("http://mirror")));
    assert!(requests.iter().all(|r| !r.follow));

    // A new list must be https (the form's refusal); nothing is added.
    let count = || async {
        sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM dns_blocklists")
            .fetch_one(&app.db)
            .await
            .unwrap()
    };
    let lists = count().await;
    let res = app
        .request_form(
            Method::POST,
            "/dns/blocklists/new",
            Some(&cookie),
            &[("name", "Mine"), ("url", "http://lists.example/hosts")],
        )
        .await;
    let location = res.location().unwrap_or_default();
    assert!(
        location.starts_with("/dns?notice=bad_list&name="),
        "{location}"
    );
    assert_eq!(count().await, lists);
}

/// A list saved with an `http://` address before the form asked for https is never asked: it keeps what it had, its
/// row says "Needs https" in orange with the fix behind its ⓘ (a field for the https address), and its health is the
/// parent's. The new address must be https; once it is, the list loads.
#[tokio::test]
async fn an_http_list_is_not_fetched_and_says_needs_https() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (phone, _) = app.enrolled_device("phone").await;
    let body = "0.0.0.0 ads.example\n";
    app.fetch.answer("http://old.example/hosts", 200, body);
    app.fetch.answer("https://old.example/hosts", 200, body);
    let old = add_feed(&app, "Old", "http://old.example/hosts").await;
    compile(&app).await;
    {
        let compiled = app.state.dns_compiled.read().await;
        let list = compiled
            .lists
            .iter()
            .find(|l| l.blocklist_id == old)
            .unwrap();
        assert!(list.needs_https && !list.loaded && list.domains.is_empty());
    }
    let requests = app.fetch.requests.lock().unwrap().clone();
    assert!(
        !requests.iter().any(|r| r.url.contains("old.example")),
        "never asked"
    );
    let health = crate::integration_health::row(&app.db, "blocklist", &format!("feed:{old}"))
        .await
        .unwrap()
        .unwrap();
    assert_eq!(
        (health.class, health.error.as_deref()),
        (
            Some(crate::integration_health::Class::Parent),
            Some("needs_https")
        )
    );

    let problem_row = |page: &str| {
        let row = &page[page
            .find(&format!("data-list-problem=\"{old}\""))
            .expect("the problem row")..];
        row[..row.find("</details>").unwrap()].to_string()
    };
    let page = app.get_page("/dns", &cookie).await.text();
    let row = problem_row(&page);
    assert!(
        row.contains("<span class=\"row-label tone-orange\">Needs https</span>"),
        "{row}"
    );
    assert!(
        row.contains("Lists are only fetched over https, so this one isn&#39;t updated.")
            || row.contains("Lists are only fetched over https, so this one isn&#x27;t updated."),
        "{row}"
    );
    assert!(
        row.contains(&format!("action=\"/dns/blocklists/{old}/url#lists\""))
            && row.contains("value=\"https://old.example/hosts\""),
        "{row}"
    );
    let page = app
        .get_page(&super::device_page(phone, super::Section::Filter), &cookie)
        .await
        .text();
    assert!(
        page.contains("<span class=\"row-sub text-alarm\">Needs https</span>"),
        "{page}"
    );
    let page = app.get_page("/dns?lang=nb", &cookie).await.text();
    assert!(page.contains(">Må bruke https<"), "{page}");

    // An address that isn't https is refused back to the open ⓘ; nothing changes.
    let res = app
        .request_form(
            Method::POST,
            &format!("/dns/blocklists/{old}/url"),
            Some(&cookie),
            &[("url", "http://typed.example/hosts")],
        )
        .await;
    let location = res.location().unwrap_or_default().to_string();
    assert_eq!(
        location,
        format!("/dns?notice=bad_list_url&list={old}&url=http%3A%2F%2Ftyped.example%2Fhosts#lists")
    );
    let page = app.get_page(&location, &cookie).await.text();
    let row = problem_row(&page);
    assert!(
        row.contains(" open>")
            && row.contains("The address must start with https://.")
            && row.contains("value=\"http://typed.example/hosts\""),
        "{row}"
    );

    // Off for every phone: the parent's choice - nothing on its row, on the phone's page or in Attention.
    sqlx::query("UPDATE dns_blocklists SET enabled = 0 WHERE id = ?")
        .bind(old)
        .execute(&app.db)
        .await
        .unwrap();
    let page = app.get_page("/dns", &cookie).await.text();
    assert!(
        !page.contains(&format!("data-list-problem=\"{old}\"")) && !page.contains("Needs https"),
        "{page}"
    );
    let page = app
        .get_page(&super::device_page(phone, super::Section::Filter), &cookie)
        .await
        .text();
    assert!(!page.contains("Needs https"), "{page}");
    let attention = crate::integration_health::for_device(&app.db, phone, chrono::Utc::now())
        .await
        .unwrap();
    assert!(attention.is_empty(), "{attention:?}");
    // On for one phone by its own setting: the line is back.
    sqlx::query(
        "INSERT INTO device_blocklist_overrides (device_id, blocklist_id, enabled) VALUES (?, ?, 1)",
    )
    .bind(phone)
    .bind(old)
    .execute(&app.db)
    .await
    .unwrap();
    let page = app.get_page("/dns", &cookie).await.text();
    assert!(
        page.contains(&format!("data-list-problem=\"{old}\"")),
        "{page}"
    );

    let res = app
        .request_form(
            Method::POST,
            &format!("/dns/blocklists/{old}/url"),
            Some(&cookie),
            &[("url", "https://old.example/hosts")],
        )
        .await;
    assert_eq!(res.location().unwrap_or_default(), "/dns#lists");
    {
        let compiled = app.state.dns_compiled.read().await;
        let list = compiled
            .lists
            .iter()
            .find(|l| l.blocklist_id == old)
            .unwrap();
        assert!(!list.needs_https && list.loaded && list.domains.contains("ads.example"));
    }
    let res = app
        .request_form(
            Method::POST,
            "/dns/blocklists/999999/url",
            Some(&cookie),
            &[("url", "https://old.example/hosts")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
}
