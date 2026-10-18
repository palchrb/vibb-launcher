//! Design 36 step 1: a custom DNS server for the Web Filter - the PWA's write-only Custom Server
//! form and its refusals, the policy's `dns_upstream`, the status report's `dns_upstream` (the
//! contract shared with the launcher) and the per-phone lines under the group.

use axum::http::{Method, StatusCode, header};
use serde_json::{Value, json};

use super::settings_pages::{check_forms, part};
use super::{TestApp, TestResponse, assert_redirect};
use crate::display_time::Zone;

/// The launcher's status body with `dns_upstream` (the shared contract: the launcher's
/// `DnsUpstreamStatusTest` encodes exactly this; byte-identical in its test resources).
const DNS_STATUS_REPORT: &str = include_str!("../../testdata/dns_upstream_status.json");

/// `(upstream, custom_server, custom_backup, custom_rev)` as stored.
async fn stored(app: &TestApp) -> (String, String, String, i64) {
    sqlx::query_as(
        "SELECT upstream, custom_server, custom_backup, custom_rev FROM dns_filter_settings",
    )
    .fetch_one(&app.db)
    .await
    .unwrap()
}

async fn store(app: &TestApp, upstream: &str, server: &str, backup: &str, rev: i64) {
    sqlx::query(
        "UPDATE dns_filter_settings SET upstream = ?, custom_server = ?, custom_backup = ?, \
         custom_rev = ?",
    )
    .bind(upstream)
    .bind(server)
    .bind(backup)
    .bind(rev)
    .execute(&app.db)
    .await
    .unwrap();
}

async fn save(app: &TestApp, cookie: &str, fields: &[(&str, &str)]) -> TestResponse {
    app.request_form(Method::POST, "/dns/upstream/custom", Some(cookie), fields)
        .await
}

async fn pick(app: &TestApp, cookie: &str, upstream: &str) -> TestResponse {
    app.request_form(
        Method::POST,
        "/dns/upstream",
        Some(cookie),
        &[("upstream", upstream)],
    )
    .await
}

/// A field of the Custom Server form, from its label to the end of its `.field`.
fn field<'a>(html: &'a str, id: &str) -> &'a str {
    part(html, &format!("for=\"{id}\""), "</div>")
}

async fn events(app: &TestApp) -> Vec<String> {
    sqlx::query_scalar(
        "SELECT detail FROM security_events WHERE event_type = 'dns_server_changed' ORDER BY id",
    )
    .fetch_all(&app.db)
    .await
    .unwrap()
}

#[tokio::test]
async fn a_custom_server_is_saved_write_only_and_lands_on_its_group() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let page = app.get_page("/dns", &cookie).await.text();
    check_forms("/dns", &page);
    assert!(field(&page, "custom_server").contains("value=\"\""));
    assert!(field(&page, "custom_server").contains("Not set."));
    assert!(field(&page, "custom_backup").contains("value=\"https://1.1.1.3/dns-query\""));
    assert!(!page.contains("forget_custom"));
    assert!(page.contains("<details class=\"disclosure cell\" id=\"custom-server\">"));

    let res = save(
        &app,
        &cookie,
        &[
            ("custom_server", "https://dns.example/dns-query/abc123"),
            ("custom_backup", "1.1.1.3"),
        ],
    )
    .await;
    assert_redirect(&res, "/dns?open=custom-server", Some("server"));
    app.assert_lands(&res, &cookie).await;
    // QA L2: the row comes back open after a save (the house rule for a closed `<details>`).
    let back = app
        .get_page("/dns?open=custom-server", &cookie)
        .await
        .text();
    assert!(back.contains("id=\"custom-server\" open"));
    assert_eq!(
        stored(&app).await,
        (
            "custom".into(),
            "https://dns.example/dns-query/abc123".into(),
            "tls://1.1.1.3".into(),
            1
        )
    );
    let page = app.get_page("/dns", &cookie).await.text();
    assert!(field(&page, "custom_server").contains("Set (dns.example · DNS-over-HTTPS)."));
    assert!(field(&page, "custom_backup").contains("Set (1.1.1.3 · DNS-over-TLS)."));
    assert!(
        // The stored path never shows (the prefill's own `/dns-query` is ours, not the parent's).
        !page.contains("abc123") && !page.contains("dns.example/dns-query"),
        "{page}"
    );
    assert!(field(&page, "custom_server").contains("value=\"\""));
    assert!(field(&page, "custom_backup").contains("value=\"\""));
    assert!(page.contains("name=\"forget_custom\""));
    assert!(page.contains("<option value=\"custom\" selected>"));
    assert!(
        !page.contains("data-hint=\"backup-ip\""),
        "the backup is an IP"
    );
    assert_eq!(
        events(&app).await,
        [r#"{"backup_kind":"dot","kind":"doh","server":"custom"}"#]
    );

    // A backup-only change keeps the server; with both names the page suggests an IP backup.
    let res = save(
        &app,
        &cookie,
        &[
            ("custom_server", ""),
            ("custom_backup", "abc123.dns2.example"),
        ],
    )
    .await;
    assert_redirect(&res, "/dns?open=custom-server", Some("server"));
    let (_, server, backup, rev) = stored(&app).await;
    assert_eq!(
        (server.as_str(), backup.as_str(), rev),
        (
            "https://dns.example/dns-query/abc123",
            "tls://abc123.dns2.example",
            2
        )
    );
    let page = app.get_page("/dns", &cookie).await.text();
    assert!(field(&page, "custom_backup").contains("Set (…dns2.example · DNS-over-TLS)."));
    assert!(!page.contains("abc123"));
    assert!(page.contains("data-hint=\"backup-ip\""));

    // Both empty keeps; the same values typed again change nothing either.
    for fields in [
        [("custom_server", ""), ("custom_backup", "")],
        [
            ("custom_server", "HTTPS://DNS.EXAMPLE/dns-query/abc123"),
            ("custom_backup", "tls://abc123.dns2.example:853"),
        ],
    ] {
        let res = save(&app, &cookie, &fields).await;
        assert_redirect(&res, "/dns?open=custom-server", Some("server"));
        assert_eq!(stored(&app).await.3, 2);
    }
    assert_eq!(events(&app).await.len(), 2);

    // Quad9 keeps both values; Custom can be picked again without typing them.
    assert_redirect(&pick(&app, &cookie, "quad9").await, "/dns", Some("server"));
    assert_eq!(
        stored(&app).await,
        ("quad9".into(), server.clone(), backup.clone(), 2)
    );
    assert_redirect(&pick(&app, &cookie, "custom").await, "/dns", Some("server"));
    assert_eq!(stored(&app).await.0, "custom");
    assert_eq!(
        events(&app).await[2..],
        [
            r#"{"backup_kind":"dot","kind":"doh","server":"quad9"}"#,
            r#"{"backup_kind":"dot","kind":"doh","server":"custom"}"#
        ]
    );

    // Forget clears both and puts the phones back on Cloudflare.
    let res = save(
        &app,
        &cookie,
        &[
            ("custom_server", ""),
            ("custom_backup", ""),
            ("forget_custom", "on"),
        ],
    )
    .await;
    assert_redirect(&res, "/dns?open=custom-server", Some("server"));
    assert_eq!(
        stored(&app).await,
        ("cloudflare".into(), String::new(), String::new(), 3)
    );
    assert_eq!(
        events(&app).await.last().unwrap(),
        r#"{"server":"cloudflare"}"#
    );
    let page = app.get_page("/dns", &cookie).await.text();
    assert!(field(&page, "custom_backup").contains("value=\"https://1.1.1.3/dns-query\""));
    assert!(!page.contains("forget_custom"));
    for detail in events(&app).await {
        assert!(
            !detail.contains("example") && !detail.contains("1.1.1"),
            "{detail}"
        );
    }
}

/// A refused save or pick: 400, `no-store`, in place with the row open, the reason under its
/// field, what was typed back in the body and nothing written. One refusal per field through
/// HTTP; the grammar is `dns_server::tests`'.
fn refused(res: &TestResponse) -> String {
    assert_eq!(res.status, StatusCode::BAD_REQUEST, "{}", res.text());
    assert_eq!(res.headers.get(header::CACHE_CONTROL).unwrap(), "no-store");
    let html = res.text();
    check_forms("/dns (refused)", &html);
    assert!(html.contains("id=\"custom-server\" open"));
    html
}

#[tokio::test]
async fn refusals_render_in_place_and_write_nothing() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    for (fields, at, other, reason) in [
        (
            [
                ("custom_server", "dns.example:53"),
                ("custom_backup", "1.1.1.3"),
            ],
            "custom_server",
            "custom_backup",
            "Port 53 and 80",
        ),
        (
            [
                ("custom_server", "9.9.9.9"),
                ("custom_backup", "http://dns.example/q"),
            ],
            "custom_backup",
            "custom_server",
            "Not encrypted",
        ),
    ] {
        let html = refused(&save(&app, &cookie, &fields).await);
        let here = field(&html, at);
        assert!(
            here.contains(reason) && here.contains("aria-invalid=\"true\""),
            "{at}: {here}"
        );
        assert!(!field(&html, other).contains("field-error"));
        let typed = fields.iter().find(|(name, _)| *name == at).unwrap().1;
        assert!(here.contains(&format!("value=\"{typed}\"")), "{here}");
    }

    // Nothing stored: no server, no backup (the prefill cleared), or the same server twice.
    for (fields, at, reason) in [
        (
            [("custom_server", ""), ("custom_backup", "1.1.1.3")],
            "custom_server",
            "Type your server&#39;s address first.",
        ),
        (
            [
                ("custom_server", "https://dns.example/dns-query"),
                ("custom_backup", ""),
            ],
            "custom_backup",
            "Custom needs a backup server",
        ),
        (
            [
                ("custom_server", "9.9.9.9"),
                ("custom_backup", "tls://9.9.9.9:853"),
            ],
            "custom_backup",
            "The backup must be another server than yours.",
        ),
    ] {
        let html = refused(&save(&app, &cookie, &fields).await);
        assert!(field(&html, at).contains(reason), "{fields:?}");
    }

    // Custom picked with nothing stored: the row open, the reason under the first field.
    let html = refused(&pick(&app, &cookie, "custom").await);
    assert!(field(&html, "custom_server").contains("Type your server&#39;s address first."));
    assert!(!field(&html, "custom_backup").contains("field-error"));
    assert!(html.contains("<option value=\"custom\" selected>"));

    assert_eq!(
        stored(&app).await,
        ("cloudflare".into(), String::new(), String::new(), 0)
    );
    assert!(events(&app).await.is_empty());
}

/// QA M2: a refusal never echoes a stored value - the inputs hold only what was typed, the
/// hints only the hints.
#[tokio::test]
async fn a_refused_save_never_shows_the_stored_values() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let pair = (
        "https://abc123.dns.example/dns-query/id456",
        "tls://def789.dns2.example",
    );
    store(&app, "custom", pair.0, pair.1, 1).await;
    let html = refused(
        &save(
            &app,
            &cookie,
            &[("custom_server", ""), ("custom_backup", "dns.example:53")],
        )
        .await,
    );
    for secret in ["abc123", "id456", "def789", pair.0, pair.1] {
        assert!(!html.contains(secret), "{secret} in the page");
    }
    assert!(field(&html, "custom_server").contains("value=\"\""));
    assert!(field(&html, "custom_server").contains("Set (…dns.example · DNS-over-HTTPS)."));
    assert_eq!(stored(&app).await.1, pair.0);
}

/// QA L1/L3: a stored pair that isn't valid keeps its hints, prefills nothing over a stored
/// backup, and the picker names what is missing; the policy reads it as Cloudflare.
#[tokio::test]
async fn an_equal_stored_pair_keeps_its_hints_and_backup() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    store(&app, "quad9", "tls://9.9.9.9", "tls://9.9.9.9", 1).await;
    let page = app.get_page("/dns", &cookie).await.text();
    assert!(field(&page, "custom_server").contains("Set (9.9.9.9 · DNS-over-TLS)."));
    assert!(field(&page, "custom_backup").contains("Set (9.9.9.9 · DNS-over-TLS)."));
    assert!(field(&page, "custom_backup").contains("value=\"\""));

    let html = refused(&pick(&app, &cookie, "custom").await);
    assert!(!field(&html, "custom_server").contains("field-error"));
    assert!(field(&html, "custom_backup").contains("The backup must be another server"));
    assert!(field(&html, "custom_backup").contains("value=\"\""));

    // Typing only "Your server" keeps the stored backup.
    let res = save(
        &app,
        &cookie,
        &[("custom_server", "dns.quad9.net"), ("custom_backup", "")],
    )
    .await;
    assert_redirect(&res, "/dns?open=custom-server", Some("server"));
    let (upstream, server, backup, _) = stored(&app).await;
    assert_eq!(
        (upstream.as_str(), server.as_str(), backup.as_str()),
        ("custom", "tls://dns.quad9.net", "tls://9.9.9.9")
    );

    // The picker with a stored server and no backup: the backup's own reason.
    store(&app, "quad9", "tls://9.9.9.9", "", 2).await;
    let html = refused(&pick(&app, &cookie, "custom").await);
    assert!(!field(&html, "custom_server").contains("field-error"));
    assert!(field(&html, "custom_backup").contains("Custom needs a backup server"));
    assert!(field(&html, "custom_backup").contains("value=\"https://1.1.1.3/dns-query\""));
}

/// QA M1: both routes need a full session and refuse a cross-site post; nothing is written.
#[tokio::test]
async fn the_dns_server_routes_refuse_without_a_session_or_from_another_site() {
    use axum::body::Body;
    use axum::http::Request;
    let app = TestApp::new().await;
    let valid = [
        ("custom_server", "https://dns.example/dns-query"),
        ("custom_backup", "1.1.1.3"),
    ];
    for (path, fields) in [
        ("/dns/upstream/custom", &valid[..]),
        ("/dns/upstream", &[("upstream", "quad9")][..]),
    ] {
        let res = app.request_form(Method::POST, path, None, fields).await;
        assert_eq!(res.location(), Some("/login"), "{path}");
    }
    let cookie = app.admin_cookie().await;
    let body = form_urlencoded::Serializer::new(String::new())
        .extend_pairs(valid)
        .finish();
    let request = Request::builder()
        .method(Method::POST)
        .uri("/dns/upstream/custom")
        .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
        .header(header::COOKIE, &cookie)
        .header("sec-fetch-site", "cross-site")
        .body(Body::from(body))
        .unwrap();
    let res = app
        .send_via(&app.router, super::LOOPBACK_PEER, request)
        .await;
    assert_eq!(res.status, StatusCode::FORBIDDEN);
    assert_eq!(
        stored(&app).await,
        ("cloudflare".into(), String::new(), String::new(), 0)
    );
    assert!(events(&app).await.is_empty());
}

/// Step 3's QA M3: the prefill posted untouched is a DoH backup (port 443, so it answers where
/// 853 is blocked), stored and sent as one.
#[tokio::test]
async fn the_prefilled_backup_is_saved_as_dns_over_https() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (_, token) = app.enrolled_device("Anna").await;
    let page = app.get_page("/dns", &cookie).await.text();
    let prefill = "https://1.1.1.3/dns-query";
    assert!(field(&page, "custom_backup").contains(&format!("value=\"{prefill}\"")));
    let res = save(
        &app,
        &cookie,
        &[
            ("custom_server", "tls://dns.example"),
            ("custom_backup", prefill),
        ],
    )
    .await;
    assert_redirect(&res, "/dns?open=custom-server", Some("server"));
    assert_eq!(stored(&app).await.2, prefill);
    let policy = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json();
    assert_eq!(
        policy["dns_upstream"]["backup"],
        json!({"kind": "doh", "url": prefill})
    );
}

#[tokio::test]
async fn custom_server_in_the_policy() {
    let app = TestApp::new().await;
    let (_, token) = app.enrolled_device("Anna").await;
    let policy = || async {
        app.request(Method::GET, "/api/devices/policy", Some(&token), None)
            .await
            .json()
    };
    for (server, backup, expected) in [
        (
            "https://dns.example/dns-query/abc123",
            "tls://1.1.1.3",
            json!({"kind": "doh", "url": "https://dns.example/dns-query/abc123", "rev": 4,
                "backup": {"kind": "dot", "host": "1.1.1.3", "port": 853}}),
        ),
        (
            "tls://abc123.dns.example:8853",
            "https://[2606:4700:4700::1111]/dns-query",
            json!({"kind": "dot", "host": "abc123.dns.example", "port": 8853, "rev": 4,
                "backup": {"kind": "doh", "url": "https://[2606:4700:4700::1111]/dns-query"}}),
        ),
        (
            "tls://[2620:fe::fe]",
            "https://dns.quad9.net/dns-query",
            json!({"kind": "dot", "host": "2620:fe::fe", "port": 853, "rev": 4,
                "backup": {"kind": "doh", "url": "https://dns.quad9.net/dns-query"}}),
        ),
    ] {
        store(&app, "custom", server, backup, 4).await;
        let policy = policy().await;
        assert_eq!(policy["dns_upstream_provider"], "cloudflare");
        assert_eq!(policy["dns_upstream"], expected);
    }
    // Quad9 with values kept: the old word, no object.
    store(&app, "quad9", "tls://9.9.9.9", "tls://1.1.1.3", 4).await;
    let policy = policy().await;
    assert_eq!(policy["dns_upstream_provider"], "quad9");
    assert_eq!(policy["dns_upstream"], Value::Null);
}

#[tokio::test]
async fn custom_without_a_valid_pair_is_cloudflare() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (_, token) = app.enrolled_device("Anna").await;
    for (server, backup) in [
        ("", ""),
        ("https://dns.example/dns-query", ""),
        ("not a server", "tls://1.1.1.3"),
        ("tls://9.9.9.9", "tls://9.9.9.9:853"),
    ] {
        store(&app, "custom", server, backup, 2).await;
        let policy = app
            .request(Method::GET, "/api/devices/policy", Some(&token), None)
            .await
            .json();
        assert_eq!(policy["dns_upstream_provider"], "cloudflare", "{server}");
        assert_eq!(policy["dns_upstream"], Value::Null, "{server}");
        let page = app.get_page("/dns", &cookie).await.text();
        assert!(
            page.contains("The saved addresses aren't accepted any more"),
            "{server}"
        );
        assert!(page.contains("id=\"custom-server\" open"));
        assert!(!page.contains("data-dns-phone"));
    }
}

/// The launcher keeps a byte-identical copy of the contract in its test resources.
#[test]
fn the_dns_status_contract_matches_the_launchers_copy() {
    let copy_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../launcher/app/src/test/resources/dns_upstream_status.json");
    let copy = std::fs::read_to_string(&copy_path)
        .unwrap_or_else(|e| panic!("{}: {e}", copy_path.display()));
    assert_eq!(copy, DNS_STATUS_REPORT, "{} differs", copy_path.display());
}

/// The launcher's body is taken as sent and stored through the known words only.
#[tokio::test]
async fn dns_upstream_status_contract() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let report: Value = serde_json::from_str(DNS_STATUS_REPORT).unwrap();
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(report.clone()),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    let kept = || async {
        let stored: Option<String> = sqlx::query_scalar(
            "SELECT dns_upstream_json FROM device_status WHERE device_id = ? \
             ORDER BY id DESC LIMIT 1",
        )
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap();
        stored.map(|s| serde_json::from_str::<Value>(&s).unwrap())
    };
    assert_eq!(kept().await, Some(report["dns_upstream"].clone()));

    // Never an address, whatever a launcher sends; junk stores nothing; an older report none.
    let mut sneaky = report.clone();
    sneaky["dns_upstream"]["host"] = json!("abc123.dns.example");
    sneaky["dns_upstream"]["reason"] = json!("https://abc123.dns.example");
    app.request(
        Method::POST,
        "/api/devices/status",
        Some(&token),
        Some(sneaky),
    )
    .await;
    let stored = kept().await.unwrap();
    assert!(!stored.to_string().contains("example"), "{stored}");
    assert_eq!(stored["reason"], Value::Null);
    for junk in [
        json!("backup"),
        json!({"rev": "4", "in_use": "custom"}),
        Value::Null,
    ] {
        let mut body = report.clone();
        body["dns_upstream"] = junk;
        app.request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(body),
        )
        .await;
        assert_eq!(kept().await, None);
    }
}

/// While Custom is in force, one line per phone from its last report (design 36 §6.3).
#[tokio::test]
async fn the_footer_says_per_phone_which_server_is_in_use() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    store(
        &app,
        "custom",
        "https://dns.example/dns-query",
        "tls://1.1.1.3",
        4,
    )
    .await;
    let caps = json!(["call_policy_v1", "dns_custom_v1"]);
    let report = |token: String, body: Value| {
        let app = &app;
        async move {
            let res = app
                .request(
                    Method::POST,
                    "/api/devices/status",
                    Some(&token),
                    Some(body),
                )
                .await;
            assert_eq!(res.status, StatusCode::NO_CONTENT);
        }
    };
    let status = |caps: &Value, upstream: Value| {
        json!({"lock_reason": "NONE", "kiosk_engaged": true, "capabilities": caps,
            "dns_upstream": upstream})
    };
    let since = chrono::Utc::now() - chrono::Duration::minutes(3);
    let (anna, t) = app.enrolled_device("Anna").await;
    report(
        t,
        status(&caps, json!({"rev": 4, "in_use": "custom", "since_ms": 1})),
    )
    .await;
    let (ola, t) = app.enrolled_device("Ola").await;
    let ola_token = t.clone();
    report(
        t,
        status(
            &caps,
            json!({"rev": 4, "in_use": "backup", "since_ms": since.timestamp_millis(),
                "reason": "tls"}),
        ),
    )
    .await;
    let (per, t) = app.enrolled_device("Per").await;
    report(t, status(&json!(["call_policy_v1"]), Value::Null)).await;
    let (kari, t) = app.enrolled_device("Kari").await;
    report(t, status(&caps, json!({"rev": 3, "in_use": "custom"}))).await;
    let (liv, t) = app.enrolled_device("Liv").await;
    report(t, status(&caps, json!({"rev": 4, "in_use": "custom"}))).await;
    sqlx::query("UPDATE device_policy SET vpn_filter_enabled = 0 WHERE device_id = ?")
        .bind(liv)
        .execute(&app.db)
        .await
        .unwrap();
    let (mia, _) = app.enrolled_device("Mia").await;
    let (nils, _) = app.create_device("Nils").await;

    let line = |html: &str, id: i64| {
        part(html, &format!("data-dns-phone=\"{id}\">"), "</li>")
            .trim_start_matches(&format!("data-dns-phone=\"{id}\">"))
            .trim_end_matches("</li>")
            .to_string()
    };
    let zone = Zone::load(&app.db).await;
    let page = app.get_page("/dns", &cookie).await.text();
    assert_eq!(line(&page, anna), "Anna · Your server in use");
    assert_eq!(
        line(&page, ola),
        format!(
            "Ola · Backup server since {} - your server&#39;s certificate wasn&#39;t accepted",
            zone.when(since, chrono::Utc::now())
        )
    );
    assert_eq!(
        line(&page, per),
        "Per · Cloudflare - update Vibb One on this phone to use your server"
    );
    assert_eq!(line(&page, kari), "Kari · Waiting for the phone");
    assert_eq!(line(&page, liv), "Liv · Web filter off");
    assert_eq!(line(&page, mia), "Mia · Waiting for the phone");
    // QA L4: a phone never set up gets no line.
    assert!(!page.contains(&format!("data-dns-phone=\"{nils}\"")));

    // One wording per reason; none (or an unknown one) just names the backup.
    for (reason, words) in [
        (
            "bootstrap",
            "your server&#39;s address couldn&#39;t be looked up",
        ),
        (
            "connect",
            "can&#39;t reach your server from where the phone is",
        ),
        (
            "http",
            "your server didn&#39;t answer as a DNS-over-HTTPS server",
        ),
        (
            "refused",
            "your server refused this phone - check its allowed clients",
        ),
        ("timeout", "your server didn&#39;t answer in time"),
    ] {
        report(
            ola_token.clone(),
            status(
                &caps,
                json!({"rev": 4, "in_use": "backup", "reason": reason}),
            ),
        )
        .await;
        let page = app.get_page("/dns", &cookie).await.text();
        assert_eq!(line(&page, ola), format!("Ola · Backup server - {words}"));
    }
    report(
        ola_token,
        status(&caps, json!({"rev": 4, "in_use": "backup"})),
    )
    .await;
    let page = app.get_page("/dns", &cookie).await.text();
    assert_eq!(line(&page, ola), "Ola · Backup server");

    // Only while Custom is chosen.
    store(
        &app,
        "quad9",
        "https://dns.example/dns-query",
        "tls://1.1.1.3",
        4,
    )
    .await;
    let page = app.get_page("/dns", &cookie).await.text();
    assert!(!page.contains("data-dns-phone"));
}
