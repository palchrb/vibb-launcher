//! Security review M6: the DNS blocklist fails closed, per feed.

use std::sync::Arc;
use std::sync::atomic::{AtomicU8, Ordering};

use axum::http::{Method, StatusCode};

use super::TestApp;
use crate::dns_engine;

const GOOD: u8 = 0;
const SERVER_ERROR: u8 = 1;
const PORTAL_PAGE: u8 = 2;
const TOO_BIG: u8 = 3;

/// A feed server whose answer the test switches: returns its URL and the switch.
async fn feed_server() -> (String, Arc<AtomicU8>) {
    let mode = Arc::new(AtomicU8::new(GOOD));
    let served = mode.clone();
    let app = axum::Router::new().route(
        "/feed",
        axum::routing::get(move || {
            let mode = served.load(Ordering::SeqCst);
            async move {
                match mode {
                    GOOD => (
                        StatusCode::OK,
                        "# a hosts file\n0.0.0.0 ads.example\ntracker.example.\n".to_string(),
                    ),
                    SERVER_ERROR => (StatusCode::INTERNAL_SERVER_ERROR, "oops".to_string()),
                    PORTAL_PAGE => (
                        StatusCode::OK,
                        "<html><body>Sign in to the Wi-Fi</body></html>\n".to_string(),
                    ),
                    _ => (
                        StatusCode::OK,
                        "a.example\n".repeat(dns_engine::MAX_FEED_BYTES / 10 + 1),
                    ),
                }
            }
        }),
    );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/feed", listener.local_addr().unwrap());
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    (url, mode)
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
    let (_, token) = app.enrolled_device("phone").await;
    let (url, mode) = feed_server().await;
    add_feed(&app, "Ads", &url).await;

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
        mode.store(bad, Ordering::SeqCst);
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

    // A second feed that never loads (a mistyped URL): left out - no phone ever got it - and the
    // rest, the parent's own domains included, still reaches the phone (QA of the security wave,
    // Low 8: a 503 froze every list).
    add_feed(&app, "Broken", "http://127.0.0.1:9/nothing").await;
    sqlx::query(
        "INSERT INTO dns_custom_domains (domain, list_type) VALUES ('tiktok.com', 'block')",
    )
    .execute(&app.db)
    .await
    .unwrap();
    mode.store(GOOD, Ordering::SeqCst);
    compile(&app).await;
    let (status, body) = blocklist(&app, &token).await;
    assert_eq!(status, StatusCode::OK);
    assert!(
        body.contains("\"ads.example\"") && body.contains("\"tiktok.com\""),
        "{body}"
    );
    let page = app.get_page("/dns", &cookie).await.text();
    assert!(page.contains("Not loaded yet ("), "{page}");
}

/// QA of the security wave, Low 8: a feed's last good domains survive a restart without
/// internet, and a deleted feed's copy goes.
#[tokio::test]
async fn a_feed_keeps_its_domains_across_a_restart() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (_, token) = app.enrolled_device("phone").await;
    let (url, mode) = feed_server().await;
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
    mode.store(SERVER_ERROR, Ordering::SeqCst);
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
    let (url, _) = feed_server().await;
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
