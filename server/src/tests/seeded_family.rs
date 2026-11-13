//! A family with something on every page (design 70 §8): a parent reading in Norwegian, three phones - one with a
//! rich report (time rules, battery, zones, locks, update fence, downloads, music), one with problems the first
//! doesn't have, one not set up yet - one event of each kind, a security line of each label, a tracked app with a
//! release, a music entry, a zone and a time rule. `tests::i18n_pages` reads every page of it.

use axum::http::Method;
use chrono::{Duration, Utc};
use serde_json::json;

use super::TestApp;
use crate::parent_events::{self, Phone, Push, Start, TimeAsk};

pub struct Family {
    /// The parent's session; the account reads in `lang`.
    pub cookie: String,
    /// The phone with the rich report.
    pub anna: i64,
    /// Anna's device token (her policy is the fullest one: `tests::policy_etag`).
    pub anna_token: String,
    /// The phone with the other problems.
    pub ola: i64,
    /// Enrolled never: Connect This Phone.
    pub liv: i64,
    pub rule: i64,
    pub zone: i64,
    pub app: i64,
    pub launcher: i64,
    pub entry: i64,
    /// Anna's calendar link, CalDAV account and title rule (their own pages).
    pub calendar_link: String,
    pub caldav: i64,
    pub calendar_rule: i64,
}

/// The family, its parent reading in `lang`.
pub async fn seed(app: &TestApp, lang: crate::i18n::Lang) -> Family {
    let cookie = app.admin_cookie_as("mamma", "Mamma").await;
    sqlx::query("UPDATE admin_users SET lang = ? WHERE username = 'mamma'")
        .bind(lang.code())
        .execute(&app.db)
        .await
        .unwrap();
    let now = Utc::now();
    let now_ms = now.timestamp_millis();
    let (anna, anna_token) = app.enrolled_device("Anna").await;
    let (ola, ola_token) = app.enrolled_device("Ola").await;
    let (liv, _) = app.create_device("Liv").await;

    // A time rule on every phone, a phone's own rule, a running pause and extra time.
    let rule: i64 = sqlx::query_scalar(
        "INSERT INTO time_rules (device_id, name, kind, calls_allowed, exempt_apps_json, days_json) \
         VALUES (NULL, 'Skole', 'school', 1, '[]', \
         '[{\"start\":495,\"end\":870},{\"start\":495,\"end\":870},null,null,null,null,null]') RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO time_rules (device_id, name, kind, calls_allowed, exempt_apps_json, days_json) \
         VALUES (?, 'Kveld', 'bedtime', 0, '[]', \
         '[{\"start\":1260,\"end\":420},null,null,null,null,null,{\"start\":1290,\"end\":420}]')",
    )
    .bind(anna)
    .execute(&app.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO time_lifts (device_id, target, rule_id, rule_name, minutes, expires_at) \
         VALUES (?, 'rule', ?, 'Skole', 30, datetime('now', '+30 minutes'))",
    )
    .bind(anna)
    .bind(rule)
    .execute(&app.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO time_lifts (device_id, target, rule_id, rule_name, minutes, expires_at) \
         VALUES (?, 'budget', NULL, NULL, 15, datetime('now', '+1 day'))",
    )
    .bind(anna)
    .execute(&app.db)
    .await
    .unwrap();
    sqlx::query(
        "UPDATE device_policy SET allowlist_json = '[\"no.eksempel.spill\"]', \
         override_pin_hash = 'aGFzaA', override_pin_salt = 'c2FsdA', update_fence = 1, boot_cover = 1 \
         WHERE device_id = ?",
    )
    .bind(anna)
    .execute(&app.db)
    .await
    .unwrap();

    // The catalog: an app with a release, and Vibb One.
    let tracked: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo, latest_release_tag, \
         latest_release_asset_id, latest_release_asset_name, latest_release_asset_size, last_checked_at) \
         VALUES ('Spillet', 'no.eksempel.spill', 'eksempel/spill', 'v2.0.0', 41, 'spill.apk', 12345678, \
         datetime('now', '-2 hours')) RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    let launcher: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo, latest_release_tag, \
         latest_release_asset_id, is_launcher, release_tag_prefix) \
         VALUES ('Vibb One', 'me.vibb.launcher', 'palchrb/vibb-launcher', 'launcher-v0.36.0', 77, \
         1, 'launcher-v') RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();

    // A music entry.
    let entry: i64 = sqlx::query_scalar(
        "INSERT INTO music_entries (name, category_id, source, target, key, play_order, cache, resume, sort) \
         VALUES ('Abels tårn', 1, 'nrk', 'https://radio.nrk.no/podkast/abels_taarn', 'abels_taarn', 'auto', 5, \
         1, 10) RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();

    // The first entry's last check failed; a second one never listed.
    let unlisted: i64 = sqlx::query_scalar(
        "INSERT INTO music_entries (name, category_id, source, target, key, play_order, cache, resume, sort) \
         VALUES ('Ukas nyheter', 1, 'rss', 'https://eksempel.no/feed.xml', '40201d4c16fc', 'newest_first', 3, 1, \
         20) RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO music_listings (entry_id, version, listed_at, checked_at, error, error_at, failing_since) \
         VALUES (?, '0123456789abcdef', datetime('now', '-1 day'), datetime('now', '-1 hour'), 'timeout', \
         datetime('now', '-1 hour'), datetime('now', '-1 hour'))",
    )
    .bind(entry)
    .execute(&app.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO music_listings (entry_id, error, error_at, failing_since) \
         VALUES (?, 'http_503', datetime('now', '-2 hours'), datetime('now', '-2 hours'))",
    )
    .bind(unlisted)
    .execute(&app.db)
    .await
    .unwrap();
    sqlx::query("INSERT INTO device_music_entries (device_id, entry_id) VALUES (?, ?), (?, ?)")
        .bind(anna)
        .bind(entry)
        .bind(anna)
        .bind(unlisted)
        .execute(&app.db)
        .await
        .unwrap();

    // A zone on Anna.
    let made = app
        .request_form(
            Method::POST,
            &format!("/devices/{anna}/zones"),
            Some(&cookie),
            &[
                ("name", "Hjemme"),
                ("latitude", "59.91417"),
                ("longitude", "10.7522"),
                ("radius_m", "150"),
                ("notify_arrive", "on"),
                ("notify_leave", "on"),
            ],
        )
        .await;
    assert!(made.status.is_redirection(), "{}", made.text());
    let zone: i64 = sqlx::query_scalar("SELECT id FROM device_zones WHERE device_id = ?")
        .bind(anna)
        .fetch_one(&app.db)
        .await
        .unwrap();

    // Anna: a rich report.
    let today = crate::display_time::Zone::load(&app.db, lang)
        .await
        .today(now)
        .to_string();
    let fix = (now - Duration::minutes(3)).to_rfc3339_opts(chrono::SecondsFormat::Secs, true);
    app.post_status(
        &anna_token,
        json!({
            "app_version": "0.36.0", "app_version_code": 360_009,
            "capabilities": ["call_policy_v1", "time_rules_v1", "zones_v1", "kiosk_escapes_v1", "boot_cover_v1",
                             "window_relock_v1", "dns_custom_v1"],
            "battery": { "level": 14, "charging": false },
            "location": { "latitude": 59.91417, "longitude": 10.7522, "accuracy_meters": 15.0,
                          "captured_at": fix, "provider": "gps" },
            "zone_watch": { "fences": 1, "motion_sensor": true, "location_on": true, "background_location": true,
                            "mock_ignored": 2, "still": true },
            "time_state": { "lock_reason": "SCHOOL", "active_rule_id": rule, "active_rule_name": "Skole",
                            "day": today, "used_minutes": 42, "budget_minutes": 90, "extra_minutes": 15,
                            "calls_blocked": true, "lifts_active": [], "zone": "Europe/Oslo" },
            "lock_state": { "active": true, "locked": true, "failures": 5,
                            "backoff_until_ms": now_ms + 600_000, "exempt_yields": 2,
                            "voip_fsi_denied": ["io.element.android.x"] },
            "update_fence": { "enabled": true, "state": "installing", "since_ms": now_ms - 60_000,
                              "home_role_held": false, "waiting_for": "install_now_download" },
            "boot_cover": { "wanted": true, "last_armed_at_ms": now_ms - 3_600_000 },
            "app_downloads": { "wifi_only": true, "network": "metered", "entries": [{
                "tracked_app_id": tracked, "release_tag": "v2.0.0", "state": "downloading",
                "bytes": 4_000_000, "total": 12_345_678 }] },
            "installed_apps": [
                { "package_name": "no.eksempel.spill", "label": "Spillet", "preinstalled": false,
                  "installer": "me.vibb.launcher", "version_code": 100, "version_name": "1.0.0" }],
            "ringer_mode": "vibrate", "interruption_filter": "priority",
            "screen_timeout_seconds": 60,
            "relock_blocks": [{ "package_name": "no.eksempel.ukjent", "signer": null, "reason": "outside_play",
                                "found": "install", "at_ms": now_ms - 60_000 }],
            "foreign_access": { "admins": ["no.eksempel.speil"], "accessibility": [] },
            "call_state": { "state": "managed", "last_emergency_call_at": now_ms - 7_200_000 },
            "music_state": { "package": "me.vibb.music", "bridge": "bound", "version_code": 60_009,
                             "cache_bytes": 3_500_000_000_i64, "audio_bytes": 3_400_000_000_i64,
                             "extra_bytes": 120_000_000, "downloads_waiting": 2,
                             "entry_errors": [{ "entry": entry, "error": "not_found" }] },
        }),
    )
    .await;

    // Ola: what Anna doesn't show.
    app.post_status(
        &ola_token,
        json!({
            "app_version": "0.30.0", "app_version_code": 30_000,
            "capabilities": ["time_rules_v1", "kiosk_escapes_v1", "boot_cover_v1"],
            "battery": { "level": 80, "charging": true },
            "restrictions_paused": true,
            "policy_state": "cache_corrupt",
            "backup_service_enabled": true,
            "notification_listener_enabled": false,
            "admin_policies_pending": ["disable-keyguard-features"],
            "unseen_boots": { "count": 2, "last_at_ms": now_ms - 60_000 },
            "lock_state": { "active": false, "inactive": "crash_guard" },
            "update_fence": { "enabled": true, "state": "none", "home_role_held": false,
                              "last": { "outcome": "failed", "at_ms": now_ms - 3_600_000, "reason": "x" } },
            "boot_cover": { "wanted": true, "tripped": true, "last_handover": "nope" },
            "play_store_suspendable": false,
            "time_state": { "lock_reason": "NONE", "day": today, "used_minutes": 95, "budget_minutes": 90,
                            "extra_minutes": 0, "lifts_active": [] },
        }),
    )
    .await;

    // One event of each kind.
    let mut conn = app.db.acquire().await.unwrap();
    let phone = Phone::load(&app.db, anna).await.unwrap();
    let ago = |minutes| now - Duration::minutes(minutes);
    let off: crate::models::PowerOff = serde_json::from_value(json!({
        "at_ms": ago(90).timestamp_millis(), "boot_count": 3,
        "battery": { "level": 3, "charging": false }, "reason": "battery",
    }))
    .unwrap();
    let on: crate::models::PowerOn = serde_json::from_value(json!({
        "at_ms": ago(80).timestamp_millis(), "boot_count": 4,
        "battery": { "level": 20, "charging": true },
    }))
    .unwrap();
    let pin = |kind, until: Option<i64>| crate::parent_pin::PinEvent {
        event_id: format!("{:032x}", now_ms + kind as i64),
        kind: if kind == 0 {
            crate::parent_pin::Kind::Unlocked
        } else {
            crate::parent_pin::Kind::Lockout
        },
        entry: crate::parent_pin::Entry::Pause,
        occurred_at_ms: ago(30).timestamp_millis(),
        until_ms: until,
        failures: Some(3),
        level: Some(1),
        clock_off: false,
    };
    let battery: crate::models::BatteryReport =
        serde_json::from_value(json!({ "level": 14, "charging": false, "low": true })).unwrap();
    let events = [
        phone.emergency_call(ago(120)),
        phone.pin_event(&pin(0, None)).unwrap(),
        phone
            .pin_event(&pin(1, Some(ago(-15).timestamp_millis())))
            .unwrap(),
        phone.foreign_access(
            &crate::window_relock::ForeignAccess {
                admins: vec!["no.eksempel.speil".to_string()],
                accessibility: vec!["no.eksempel.leser".to_string()],
            },
            "{}",
            7,
            &Default::default(),
        ),
        phone.unseen_boot(2, Some(ago(50).timestamp_millis())),
        phone.low_battery(&battery, ago(40)),
        phone.phone_off(&off, ago(90), false),
        phone.phone_on(
            &on,
            ago(80),
            &Start::AfterOff {
                off: off.clone(),
                at: ago(90),
            },
        ),
        parent_events::zone_crossing(anna, "Anna", zone, "Hjemme", true, ago(20), Push::No),
        parent_events::zone_crossing(anna, "Anna", zone, "Hjemme", false, ago(10), Push::No),
        parent_events::extra_time_request(anna, "Anna", "ab12", ago(5), &TimeAsk::UsedUp, Push::No),
        parent_events::app_update_available(
            tracked,
            "Spillet",
            "v2.0.0@41",
            Some("1.0.0"),
            "2.0.0",
            ago(60),
        ),
        parent_events::app_updated(
            anna,
            "Anna",
            launcher,
            "Vibb One",
            360_009,
            None,
            "0.36.0",
            ago(70),
        ),
        parent_events::passkey_added(1, "Mamma", "Sikkerhetsnøkkel", ago(100)),
        parent_events::push_off(
            1,
            "Mamma",
            &["iPhone · Safari".to_string()],
            crate::push::Forget::Password,
            ago(110),
        ),
        // Design 76: the certificate check turned off, a certificate from outside the set.
        parent_events::server_tls_off(&phone, "Mamma", ago(115)),
        parent_events::server_tls_seen(
            &phone,
            &crate::server_tls::Outside {
                kind: "refused".to_string(),
                host: "vibb.example.org".to_string(),
                name: "emSign Root CA - G1".to_string(),
                pin: "N2oacIKlk9zMINVh0Rnpq40w8RzDIdCjf6QfDfKE4Bw=".to_string(),
                network: "mobile".to_string(),
                at_ms: ago(118).timestamp_millis(),
                received_at: String::new(),
                open: false,
            },
            ago(118),
        ),
        // Design 77: a stranger's message held for the parent.
        parent_events::sms_held(
            anna,
            &phone.name,
            "00112233445566778899aabbccddeeff",
            ago(120),
            ago(120) + Duration::hours(24),
            "number",
            "+4790000099",
            parent_events::Push::Now,
        ),
        // Design 77 §14: the kid asks for a new contact.
        parent_events::contact_request(
            anna,
            &phone.name,
            "10112233445566778899aabbccddeeff",
            "Bestemor",
            "+4790000098",
            ago(121),
        ),
        // Design 83: a contact's picture names an MMS address the parent must allow.
        parent_events::mmsc_host(anna, &phone.name, 1, "Mormor", ago(122)),
    ];
    let kinds: std::collections::BTreeSet<&str> = events.iter().map(|e| e.kind.wire()).collect();
    assert_eq!(kinds.len(), parent_events::Kind::ALL.len(), "every kind");
    for event in &events {
        parent_events::raise(&mut conn, event).await.unwrap();
    }
    drop(conn);
    // Design 77 §14 (QA L4): Anna's open contact request with a Matrix ID, so the Calls page's "Forespørsler" card and
    // "Legg til…"'s "Bruk Matrix-ID-en" are read in Norwegian too.
    sqlx::query(
        "INSERT INTO device_contact_requests (device_id, id, name, number, matrix_id, asked_at, received_at) \
         VALUES (?, '10112233445566778899aabbccddeeff', 'Bestemor', '1881', '@bestemor:example.org', ?, ?)",
    )
    .bind(anna)
    .bind(ago(121).timestamp_millis())
    .bind(ago(121).timestamp_millis())
    .execute(&app.db)
    .await
    .unwrap();
    // Design 80: Mormor, a contact whose directions differ ("Tilpasset", her Avansert drawn open) and whose Message
    // button opens Element X without a Matrix ID ("Mangler Matrix-ID").
    let mormor: i64 = sqlx::query_scalar(
        "INSERT INTO contacts (name, phone_number) VALUES ('Mormor', '+4790000055') RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO device_contacts (device_id, contact_id, allow_inbound, allow_outbound, sms_in, sms_out, mms, \
         message_app) VALUES (?, ?, 1, 0, 1, 0, 0, 'element')",
    )
    .bind(anna)
    .bind(mormor)
    .execute(&app.db)
    .await
    .unwrap();

    // Design 83: Anna's questions about an MMS address from Mormor's pictures - one Android's list names for the
    // SIM, one it doesn't, one for an operator the list doesn't have - and an allowed and a declined address.
    let listed = crate::mmsc_hosts::listed("24204").unwrap();
    let listed = listed.unasked.iter().chain(&listed.hints).next().unwrap();
    for (mccmnc, host, state) in [
        ("24204", *listed, "open"),
        ("24204", "mms.example.invalid:8002", "open"),
        ("24299", "mms.example.invalid", "open"),
        ("24299", "mmsc.example.invalid", "allowed"),
        ("24299", "mmsc2.example.invalid", "declined"),
    ] {
        sqlx::query(
            "INSERT INTO device_mmsc_hosts (device_id, mccmnc, host, state, picture_from, picture_at, reported_at, \
             decided_at, decided_by) VALUES (?, ?, ?, ?, '+4790000055', ?, ?, datetime('now'), 1)",
        )
        .bind(anna)
        .bind(mccmnc)
        .bind(host)
        .bind(state)
        .bind(ago(122).timestamp_millis())
        .bind(ago(122).timestamp_millis())
        .execute(&app.db)
        .await
        .unwrap();
    }

    // Ola: a Parent and a Child PIN through their forms (worded security lines), Android's lock chosen, and the
    // Child PIN's command answered with a refusal (Activity's line, the Lock page's row); Anna's sent one waits.
    for (path, field, pin) in [
        ("parent-pin", "new_pin", "246810"),
        ("kid-lock", "new_kid_pin", "1357"),
    ] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{ola}/{path}"),
                Some(&cookie),
                &[(field, pin)],
            )
            .await;
        assert!(res.status.is_redirection(), "{path}: {}", res.text());
    }
    sqlx::query("UPDATE device_policy SET kid_keyguard = 'android' WHERE device_id IN (?, ?)")
        .bind(ola)
        .bind(anna)
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO device_commands (device_id, command, requested_at, delivered_at, first_delivered_at, \
         deliveries, received_at, acknowledged_at, result, succeeded) VALUES \
         (?, 'set_kid_pin', datetime('now', '-20 minutes'), datetime('now', '-19 minutes'), \
         datetime('now', '-19 minutes'), 1, datetime('now', '-19 minutes'), datetime('now', '-18 minutes'), \
         'refused: mismatch', 0), \
         (?, 'set_kid_pin', datetime('now', '-2 minutes'), NULL, NULL, 0, NULL, NULL, NULL, NULL)",
    )
    .bind(ola)
    .bind(anna)
    .execute(&app.db)
    .await
    .unwrap();
    // A pause through its form (a worded line).
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{anna}/lifts"),
            Some(&cookie),
            &[
                ("target", "rule"),
                ("rule", &rule.to_string()),
                ("minutes", "15"),
            ],
        )
        .await;
    assert!(res.status.is_redirection(), "lift: {}", res.text());

    // Design 72: a calendar link, a CalDAV account and a title rule on Anna, as `tests::shell` seeds them.
    app.fetch.answer(
        "https://skole.example.no/k.ics",
        200,
        "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n",
    );
    let calendar_link = crate::calendar_feeds::add(
        &app.state,
        anna,
        "https://skole.example.no/k.ics",
        "Skolen",
        now,
    )
    .await
    .unwrap();
    let caldav: i64 = sqlx::query_scalar(
        "INSERT INTO caldav_accounts (server_url, username, password_sealed, password_nonce, password_key_fp, \
         hosts, principal, calendars_json, state) VALUES ('https://dav.example.net/', 'forelder', x'00', x'00', '', \
         '[\"dav.example.net\"]', '/p/', \
         '[{\"id\":\"0123456789abcdef\",\"href\":\"https://dav.example.net/c/hjem/\",\"name\":\"Hjem\"}]', 'ok') \
         RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    let calendar_rule: i64 = sqlx::query_scalar(
        "INSERT INTO device_calendar_rules (device_id, sort_order, field, match_kind, pattern, action) \
         VALUES (?, 1, 'title', 'contains', 'Trinn', 'hide') RETURNING id",
    )
    .bind(anna)
    .fetch_one(&app.db)
    .await
    .unwrap();

    // A security line of each label (the forms above wrote worded ones).
    for key in crate::i18n::keys_in("security.event.") {
        let id = key.trim_start_matches("security.event.");
        crate::security::record_security_event(
            &app.db,
            id,
            Some("mamma"),
            Some("100.64.0.9"),
            None,
        )
        .await;
    }

    Family {
        cookie,
        anna,
        anna_token,
        ola,
        liv,
        rule,
        zone,
        app: tracked,
        launcher,
        entry,
        calendar_link,
        caldav,
        calendar_rule,
    }
}
