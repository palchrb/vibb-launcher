//! Design 136: a CalDAV account belongs to the phone whose page added it, "Delt i familien" is the owner's choice and
//! only the owner's phone writes. Migration 0183 on a database migrated to 0182 (§9) and an older backup restored;
//! every route from another phone; the same login on another page and under another spelling; claims by a typed
//! password and their race; sharing on and off; the stale forms; a deleted owner and the one-day sweep.

use std::sync::Arc;
use std::sync::atomic::{AtomicU8, Ordering};

use axum::http::{Method, StatusCode};
use chrono::Utc;

use super::TestApp;
use super::caldav::{
    FRITID, HOME, LIST, PASSWORD, SERVER, USER, account_id, fake_icloud, home_uid, share,
    test_form, tick,
};
use crate::caldav;

/// A throwaway value, nothing like a real password: the one the fake server refuses.
const WRONG: &str = "test-only-wrong-password";

async fn phones(app: &TestApp, count: usize) -> Vec<(i64, String)> {
    let mut out = Vec::new();
    for n in 1..=count {
        out.push(app.enrolled_device(&format!("Telefon {n}")).await);
    }
    out
}

/// `(owner_device_id, shared)` of the account.
async fn owner(app: &TestApp, account: i64) -> (Option<i64>, bool) {
    sqlx::query_as("SELECT owner_device_id, shared FROM caldav_accounts WHERE id = ?")
        .bind(account)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

/// Lets the next Test run at once (the 10 s gap).
async fn next_test(app: &TestApp) {
    sqlx::query("UPDATE caldav_accounts SET tested_at = NULL")
        .execute(&app.db)
        .await
        .unwrap();
}

async fn post(
    app: &TestApp,
    cookie: &str,
    path: &str,
    fields: &[(&str, &str)],
) -> super::TestResponse {
    app.request_form(Method::POST, path, Some(cookie), fields)
        .await
}

/// The ticks form as a page drawn with `was` ticked posts it with `on` ticked.
async fn ticks(
    app: &TestApp,
    cookie: &str,
    device: i64,
    account: i64,
    was: &[&str],
    on: &[&str],
) -> super::TestResponse {
    let mut fields: Vec<(String, String)> = Vec::new();
    for href in [HOME, FRITID] {
        let id = caldav::calendar_id(href);
        fields.push(("cal".to_string(), id.clone()));
        let drawn = if was.contains(&href) { "1" } else { "0" };
        fields.push((format!("was_on_{id}"), drawn.to_string()));
        if on.contains(&href) {
            fields.push((format!("on_{id}"), "on".to_string()));
        }
    }
    let fields: Vec<(&str, &str)> = fields
        .iter()
        .map(|(k, v)| (k.as_str(), v.as_str()))
        .collect();
    post(
        app,
        cookie,
        &format!("/devices/{device}/calendar/caldav/{account}/calendars"),
        &fields,
    )
    .await
}

/// The uids of this phone's ticked calendars.
async fn feeds(app: &TestApp, device: i64) -> Vec<String> {
    sqlx::query_scalar(
        "SELECT s.uid FROM device_calendar_feeds d JOIN calendar_sources s ON s.id = d.source_id \
         WHERE d.device_id = ? ORDER BY s.uid",
    )
    .bind(device)
    .fetch_all(&app.db)
    .await
    .unwrap()
}

fn fritid_uid() -> String {
    caldav::uid_of("https://dav.example.net/", USER, FRITID)
}

async fn settings_saved(app: &TestApp) -> i64 {
    sqlx::query_scalar("SELECT COUNT(*) FROM security_events WHERE event_type = 'settings_saved'")
        .fetch_one(&app.db)
        .await
        .unwrap()
}

/// Kalender's "Barnets avtaler" set to the calendar.
async fn mark(app: &TestApp, cookie: &str, device: i64, uid: &str) -> super::TestResponse {
    let pick = format!("caldav:{uid}");
    post(
        app,
        cookie,
        &format!("/devices/{device}/calendar/dagen"),
        &[("kid", &pick)],
    )
    .await
}

async fn delete_phone(app: &TestApp, device: i64) {
    sqlx::query("DELETE FROM devices WHERE id = ?")
        .bind(device)
        .execute(&app.db)
        .await
        .unwrap();
}

// ---- the migration -------------------------------------------------------------------------------

/// §9's seed on a database migrated to 0182: what each account becomes, the second writer's mark ended, every phone's
/// feeds and marks as before (but that one), the four triggers, the conditional claim and a deleted owner.
#[tokio::test]
async fn migration_0183_gives_each_account_a_phone() {
    let (db, _dir) = super::cleanup::migrated_before(183).await;
    let mut phone = Vec::new();
    for n in 1..=4 {
        let id: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES (?) RETURNING id")
            .bind(format!("Telefon {n}"))
            .fetch_one(&db)
            .await
            .unwrap();
        sqlx::query("INSERT INTO device_policy (device_id, kiosk_desired) VALUES (?, 1)")
            .bind(id)
            .execute(&db)
            .await
            .unwrap();
        phone.push(id);
    }
    let (t1, t2, t3, t4) = (phone[0], phone[1], phone[2], phone[3]);
    let mut account = std::collections::HashMap::new();
    for name in ["a", "b", "c", "d", "e", "f"] {
        let principal = (name != "f").then(|| format!("/p/{name}/"));
        let id: i64 = sqlx::query_scalar(
            "INSERT INTO caldav_accounts (server_url, username, password_sealed, password_nonce, password_key_fp, \
             principal, state) VALUES ('https://dav.example.net/', ?, X'', X'', '', ?, ?) RETURNING id",
        )
        .bind(name)
        .bind(&principal)
        .bind(if principal.is_some() { "ok" } else { "untested" })
        .fetch_one(&db)
        .await
        .unwrap();
        account.insert(name, id);
    }
    let mut source = std::collections::HashMap::new();
    for (n, (name, of)) in [
        ("a1", "a"),
        ("b1", "b"),
        ("c1", "c"),
        ("d1", "d"),
        ("d2", "d"),
    ]
    .into_iter()
    .enumerate()
    {
        let id: i64 = sqlx::query_scalar(
            "INSERT INTO calendar_sources (uid, kind, account_id, url_sealed, url_nonce, url_key_fp, host, rev, \
             feed_json, next_at) VALUES (?, 'caldav', ?, X'00', X'00', '', 'dav.example.net', 'r1', '{}', \
             '2026-11-01T00:00:00.000Z') RETURNING id",
        )
        .bind(format!("{:032x}", n + 1))
        .bind(account[of])
        .fetch_one(&db)
        .await
        .unwrap();
        source.insert(name, id);
    }
    // (phone, calendar, writes): A on Telefon 1 (writing); B on 1 and 2 (2 writing); C on 2 and 3; D on 3 and 4,
    // both writing (two of its calendars).
    let ticks = [
        (t1, "a1", true),
        (t1, "b1", false),
        (t2, "b1", true),
        (t2, "c1", false),
        (t3, "c1", false),
        (t3, "d1", true),
        (t4, "d2", true),
    ];
    for (device, cal, writes) in ticks {
        sqlx::query(
            "INSERT INTO device_calendar_feeds (device_id, source_id, name, kid_writes) VALUES (?, ?, ?, ?)",
        )
        .bind(device)
        .bind(source[cal])
        .bind(cal)
        .bind(writes)
        .execute(&db)
        .await
        .unwrap();
        if writes {
            sqlx::query("INSERT INTO device_kid_events (device_id, source_id) VALUES (?, ?)")
                .bind(device)
                .bind(source[cal])
                .execute(&db)
                .await
                .unwrap();
            sqlx::query(
                "INSERT INTO kid_caldav_events (device_id, uid, source_id) VALUES (?, ?, ?)",
            )
            .bind(device)
            .bind(format!("vibb-kid-{device}"))
            .bind(source[cal])
            .execute(&db)
            .await
            .unwrap();
            sqlx::query("INSERT INTO kid_caldav_tags (tag, device_id) VALUES (?, ?)")
                .bind(format!("tag{device}"))
                .bind(device)
                .execute(&db)
                .await
                .unwrap();
        }
    }
    let now = Utc::now();
    let mut before = Vec::new();
    for &device in &phone {
        let feeds = crate::calendar_feeds::policy_feeds(&db, device)
            .await
            .unwrap();
        let kid = crate::calendar_kid_events::policy(&db, device, now)
            .await
            .unwrap();
        assert!(kid.is_some(), "every phone writes before");
        before.push((feeds, kid));
    }

    super::cleanup::finish(&db).await;

    let of = |name: &'static str| {
        let db = db.clone();
        let id = account[name];
        async move {
            sqlx::query_as::<_, (Option<i64>, bool)>(
                "SELECT owner_device_id, shared FROM caldav_accounts WHERE id = ?",
            )
            .bind(id)
            .fetch_one(&db)
            .await
            .unwrap()
        }
    };
    assert_eq!(of("a").await, (Some(t1), false), "A: Telefon 1's");
    assert_eq!(
        of("b").await,
        (Some(t2), true),
        "B: shared, Telefon 2 writes"
    );
    assert_eq!(of("c").await, (None, true), "C: shared, no owner");
    assert_eq!(
        of("d").await,
        (Some(t3), true),
        "D: shared, the lower writer"
    );
    assert_eq!(of("e").await, (None, true), "E: ticked nowhere");
    assert_eq!(of("f").await, (None, true), "F: untested");
    // Telefon 4's mark ended as an unmark ends one; its tag stays.
    let t4_rows: (i64, i64, i64, i64) = sqlx::query_as(
        "SELECT (SELECT kid_writes FROM device_calendar_feeds WHERE device_id = ?1), \
         (SELECT COUNT(*) FROM device_kid_events WHERE device_id = ?1), \
         (SELECT COUNT(*) FROM kid_caldav_events WHERE device_id = ?1), \
         (SELECT COUNT(*) FROM kid_caldav_tags WHERE device_id = ?1)",
    )
    .bind(t4)
    .fetch_one(&db)
    .await
    .unwrap();
    assert_eq!(t4_rows, (0, 0, 0, 1));
    for (i, &device) in phone.iter().enumerate() {
        let feeds = crate::calendar_feeds::policy_feeds(&db, device)
            .await
            .unwrap();
        let kid = crate::calendar_kid_events::policy(&db, device, now)
            .await
            .unwrap();
        assert_eq!(feeds, before[i].0, "Telefon {}'s feeds", i + 1);
        if device == t4 {
            assert_eq!(kid, None);
        } else {
            assert_eq!(kid, before[i].1, "Telefon {}'s mark", i + 1);
        }
    }
    let broken: Vec<(String,)> = sqlx::query_as("SELECT \"table\" FROM pragma_foreign_key_check")
        .fetch_all(&db)
        .await
        .unwrap();
    assert!(broken.is_empty(), "{broken:?}");

    // The four triggers, each refusing its case with a `design 136:` abort.
    let refused = |sql: &'static str, binds: Vec<i64>| {
        let db = db.clone();
        async move {
            let mut query = sqlx::query(sql);
            for b in binds {
                query = query.bind(b);
            }
            let err = query.execute(&db).await.unwrap_err();
            assert!(caldav::rule_abort(&err), "{sql}: {err}");
        }
    };
    // Telefon 2 ticks A (Telefon 1's, not shared).
    refused(
        "INSERT INTO device_calendar_feeds (device_id, source_id, name) VALUES (?, ?, 'a1')",
        vec![t2, source["a1"]],
    )
    .await;
    // Telefon 3 marks C (no owner) and Telefon 1 marks B (Telefon 2's).
    refused(
        "UPDATE device_calendar_feeds SET kid_writes = 1 WHERE device_id = ? AND source_id = ?",
        vec![t3, source["c1"]],
    )
    .await;
    refused(
        "UPDATE device_calendar_feeds SET kid_writes = 1 WHERE device_id = ? AND source_id = ?",
        vec![t1, source["b1"]],
    )
    .await;
    // B unshared while Telefon 1 ticks it; C unshared with no owner.
    refused(
        "UPDATE caldav_accounts SET shared = 0 WHERE id = ?",
        vec![account["b"]],
    )
    .await;
    refused(
        "UPDATE caldav_accounts SET shared = 0 WHERE id = ?",
        vec![account["c"]],
    )
    .await;
    // D given to Telefon 4 while Telefon 3 writes to it.
    refused(
        "UPDATE caldav_accounts SET owner_device_id = ? WHERE id = ?",
        vec![t4, account["d"]],
    )
    .await;

    // The conditional claim: C becomes Telefon 2's; Telefon 3's claim after it changes nothing.
    let claim = |device: i64| {
        let db = db.clone();
        let id = account["c"];
        async move {
            sqlx::query(
                "UPDATE caldav_accounts SET owner_device_id = ? WHERE id = ? \
                 AND (principal IS NULL OR owner_device_id IS NULL)",
            )
            .bind(device)
            .bind(id)
            .execute(&db)
            .await
            .unwrap()
            .rows_affected()
        }
    };
    assert_eq!(claim(t2).await, 1);
    assert_eq!(claim(t3).await, 0);
    assert_eq!(of("c").await, (Some(t2), true));

    // Deleting Telefon 1: A has no owner and is stamped unused; B is untouched. Deleting Telefon 3, D's writing
    // owner, fires no trigger.
    for device in [t1, t3] {
        sqlx::query("DELETE FROM devices WHERE id = ?")
            .bind(device)
            .execute(&db)
            .await
            .unwrap();
    }
    assert_eq!(of("a").await, (None, false));
    let unused: Option<String> =
        sqlx::query_scalar("SELECT unused_since FROM caldav_accounts WHERE id = ?")
            .bind(account["a"])
            .fetch_one(&db)
            .await
            .unwrap();
    assert!(unused.is_some());
    assert_eq!(of("b").await, (Some(t2), true));
    assert_eq!(of("d").await, (None, true));
}

/// An older backup (from before 0183, with accounts and a mark) passes the restore's check: migrated forward, its
/// schema is exactly what the migrations make (the four triggers included).
#[tokio::test]
async fn an_older_backup_restores_through_0183() {
    let (db, dir) = super::cleanup::migrated_before(183).await;
    let device: i64 =
        sqlx::query_scalar("INSERT INTO devices (name) VALUES ('Telefon 1') RETURNING id")
            .fetch_one(&db)
            .await
            .unwrap();
    let account: i64 = sqlx::query_scalar(
        "INSERT INTO caldav_accounts (server_url, username, password_sealed, password_nonce, password_key_fp, \
         principal, state) VALUES ('https://dav.example.net/', 'a', X'', X'', '', '/p/a/', 'ok') RETURNING id",
    )
    .fetch_one(&db)
    .await
    .unwrap();
    let source: i64 = sqlx::query_scalar(
        "INSERT INTO calendar_sources (uid, kind, account_id, url_sealed, url_nonce, url_key_fp, host, rev, \
         feed_json, next_at) VALUES ('0123456789abcdef0123456789abcdef', 'caldav', ?, X'00', X'00', '', \
         'dav.example.net', 'r1', '{}', '2026-11-01T00:00:00.000Z') RETURNING id",
    )
    .bind(account)
    .fetch_one(&db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO device_calendar_feeds (device_id, source_id, name, kid_writes) VALUES (?, ?, 'Hjem', 1)",
    )
    .bind(device)
    .bind(source)
    .execute(&db)
    .await
    .unwrap();
    db.close().await;
    let file = dir.path().join("old.db");
    let checked = crate::handlers::backups::check_extracted(dir.path(), &file).await;
    assert!(
        matches!(checked, crate::handlers::backups::Checked::Ok),
        "{}",
        match checked {
            crate::handlers::backups::Checked::Failed(code) => format!("{code:?}"),
            crate::handlers::backups::Checked::NoRoom(err) => err,
            crate::handlers::backups::Checked::Ok => String::new(),
        }
    );
}

// ---- the routes ---------------------------------------------------------------------------------

/// An account added on Telefon 1 is Telefon 1's: Telefon 2's pages don't show it, every route from Telefon 2 answers
/// 303 or is refused and writes nothing, and its Kalender can't pick the calendar.
#[tokio::test]
async fn an_unshared_account_is_its_phones_alone() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let p = phones(&app, 2).await;
    let (t1, t2) = (p[0].0, p[1].0);
    app.post_status(&p[1].1, serde_json::json!({})).await;
    fake_icloud(&app);
    let res = test_form(&app, &cookie, t1, SERVER, PASSWORD).await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    let account = account_id(&app).await;
    assert_eq!(owner(&app, account).await, (Some(t1), false));
    tick(&app, &cookie, t1, account, &[HOME]).await;
    let page = app
        .get_page(&format!("/devices/{t1}/calendar"), &cookie)
        .await
        .text();
    assert!(page.contains(&format!("data-caldav=\"{account}\"")));
    let page = app
        .get_page(&format!("/devices/{t2}/calendar"), &cookie)
        .await
        .text();
    assert!(
        !page.contains(&format!("data-caldav=\"{account}\"")),
        "{page}"
    );
    assert!(!page.contains(&home_uid()));

    let sources = format!("/devices/{t2}/calendar#sources");
    let res = app
        .get_page(&format!("/devices/{t2}/calendar/caldav/{account}"), &cookie)
        .await;
    assert_eq!(res.location(), Some(sources.as_str()));
    let (requests, saved) = (app.fetch.count(), settings_saved(&app).await);
    next_test(&app).await;
    let base = format!("/devices/{t2}/calendar/caldav/{account}");
    let home = caldav::calendar_id(HOME);
    let on = format!("on_{home}");
    for (path, fields) in [
        (
            format!("{base}/calendars"),
            vec![("cal", home.as_str()), (on.as_str(), "on")],
        ),
        (format!("{base}/test"), vec![("password", PASSWORD)]),
        (
            format!("{base}/sharing"),
            vec![("was_shared", "0"), ("shared", "on")],
        ),
        (format!("{base}/remove"), vec![("was_shared", "0")]),
    ] {
        let res = post(&app, &cookie, &path, &fields).await;
        assert_eq!(res.location(), Some(sources.as_str()), "{path}");
    }
    assert_eq!(app.fetch.count(), requests, "no request for Telefon 2");
    assert_eq!(settings_saved(&app).await, saved, "a refusal is no save");
    assert!(feeds(&app, t2).await.is_empty());
    assert_eq!(owner(&app, account).await, (Some(t1), false));
    // Kalender: no option, and a posted pick is a 400.
    let page = app
        .get_page(&format!("/devices/{t2}/calendar"), &cookie)
        .await
        .text();
    assert!(!page.contains(&format!("caldav:{}", home_uid())));
    let res = mark(&app, &cookie, t2, &home_uid()).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
}

/// Decision 6: the same login added on another phone's page - refused before any request while it is that phone's
/// and unshared, tested (the owner kept) once shared, and under another spelling refused at the pass (L5).
#[tokio::test]
async fn the_same_login_on_another_phones_page() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let p = phones(&app, 2).await;
    let (t1, t2) = (p[0].0, p[1].0);
    fake_icloud(&app);
    test_form(&app, &cookie, t1, SERVER, PASSWORD).await;
    let account = account_id(&app).await;
    let kept: (Vec<u8>, Option<String>) =
        sqlx::query_as("SELECT password_nonce, tested_at FROM caldav_accounts WHERE id = ?")
            .bind(account)
            .fetch_one(&app.db)
            .await
            .unwrap();
    let requests = app.fetch.count();
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{t2}/calendar/caldav?lang=nb"),
            Some(&cookie),
            &[
                ("server", SERVER),
                ("username", USER),
                ("password", PASSWORD),
            ],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let page = res.text();
    let under = super::settings_pages::part(&page, "id=\"caldav-username-error\"", "</div>");
    assert!(under.contains("Kontoen hører til Telefon 1."), "{under}");
    assert!(
        under.contains("Del den under Telefon 1, eller fjern den der og legg den til her."),
        "{under}"
    );
    assert!(!page.contains(PASSWORD));
    assert_eq!(app.fetch.count(), requests, "nothing tested");
    let now: (Vec<u8>, Option<String>) =
        sqlx::query_as("SELECT password_nonce, tested_at FROM caldav_accounts WHERE id = ?")
            .bind(account)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(now, kept, "the password and the Test's time unchanged");
    let en = test_form(&app, &cookie, t2, SERVER, PASSWORD).await.text();
    assert!(en.contains("The account belongs to Telefon 1."), "{en}");

    // Shared: Telefon 2's add runs Test; a pass replaces the password and keeps the owner.
    share(&app, &cookie, t1, account, true).await;
    next_test(&app).await;
    let res = test_form(&app, &cookie, t2, SERVER, PASSWORD).await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{t2}/calendar/caldav/{account}").as_str())
    );
    let nonce: Vec<u8> =
        sqlx::query_scalar("SELECT password_nonce FROM caldav_accounts WHERE id = ?")
            .bind(account)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_ne!(nonce, kept.0, "the password replaced");
    assert_eq!(owner(&app, account).await, (Some(t1), true));

    // L5: unshared again, the server spelled another way on Telefon 2's page reaches the same principal: refused and
    // rolled back - the second row stays untested, shown nowhere.
    share(&app, &cookie, t1, account, false).await;
    let other = "https://dav.example.net/caldav/";
    let res = test_form(&app, &cookie, t2, other, PASSWORD).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("The account belongs to Telefon 1."));
    let second: (Option<String>, Vec<u8>, String) = sqlx::query_as(
        "SELECT principal, password_sealed, state FROM caldav_accounts WHERE server_url = ?",
    )
    .bind(other)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(second, (None, Vec::new(), "untested".to_string()));
    assert!(caldav::accounts(&app.db, t2).await.unwrap().is_empty());
}

/// H1: an owner is claimed only by a passing Test with a typed password - an untested row, an orphan, a shared
/// account no phone owns; the stored password never claims; a claim keeps `shared`; the switch shows after it.
#[tokio::test]
async fn claims_need_a_typed_password() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let p = phones(&app, 4).await;
    let (t1, t2, t3, t4) = (p[0].0, p[1].0, p[2].0, p[3].0);
    fake_icloud(&app);
    // A first Test that failed on Telefon 1 leaves an untested row; Telefon 2's pass claims it.
    let res = test_form(&app, &cookie, t1, SERVER, WRONG).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let account = account_id(&app).await;
    assert_eq!(owner(&app, account).await, (Some(t1), false));
    next_test(&app).await;
    let res = test_form(&app, &cookie, t2, SERVER, PASSWORD).await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    assert_eq!(owner(&app, account).await, (Some(t2), false));

    // An orphan (its phone deleted, not shared) is shown nowhere; a wrong password leaves it so, a right one claims.
    delete_phone(&app, t2).await;
    assert_eq!(owner(&app, account).await, (None, false));
    assert!(caldav::accounts(&app.db, t3).await.unwrap().is_empty());
    next_test(&app).await;
    let res = test_form(&app, &cookie, t3, SERVER, WRONG).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert_eq!(owner(&app, account).await, (None, false));
    next_test(&app).await;
    let res = test_form(&app, &cookie, t3, SERVER, PASSWORD).await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    assert_eq!(owner(&app, account).await, (Some(t3), false));

    // Shared with no owner: Telefon 3 shares, Telefon 1 ticks, Telefon 3 goes.
    tick(&app, &cookie, t3, account, &[HOME]).await;
    share(&app, &cookie, t3, account, true).await;
    tick(&app, &cookie, t1, account, &[FRITID]).await;
    delete_phone(&app, t3).await;
    assert_eq!(owner(&app, account).await, (None, true));
    let page = app
        .get_page(
            &format!("/devices/{t4}/calendar/caldav/{account}?lang=nb"),
            &cookie,
        )
        .await
        .text();
    let row = super::settings_pages::part(&page, "data-row=\"owner\"", "</details>");
    assert!(row.contains(">ingen<"), "{row}");
    assert!(
        row.contains("Test på nytt med app-passordet her, så hører kontoen til Telefon 4."),
        "{row}"
    );
    assert!(
        !page.contains("/sharing#sharing"),
        "no switch before a claim"
    );
    assert!(page.contains("Kalenderne fra kontoen forsvinner fra alle telefonene"));
    // The stored password never claims.
    next_test(&app).await;
    let test = format!("/devices/{t4}/calendar/caldav/{account}/test");
    let res = post(&app, &cookie, &test, &[("password", "")]).await;
    assert!(res.location().unwrap().contains("notice=caldav_tested"));
    assert_eq!(owner(&app, account).await, (None, true));
    // A typed one does: shared stays, Telefon 1 keeps its tick, the switch is drawn.
    next_test(&app).await;
    post(&app, &cookie, &test, &[("password", PASSWORD)]).await;
    assert_eq!(owner(&app, account).await, (Some(t4), true));
    assert_eq!(feeds(&app, t1).await, [fritid_uid()]);
    let page = app
        .get_page(&format!("/devices/{t4}/calendar/caldav/{account}"), &cookie)
        .await
        .text();
    assert!(page.contains("/sharing#sharing") && page.contains("Shared with the family"));
    // (d): Telefon 1 sees it as Telefon 4's, with no remove.
    let page = app
        .get_page(&format!("/devices/{t1}/calendar/caldav/{account}"), &cookie)
        .await
        .text();
    assert!(
        page.contains(&format!("href=\"/devices/{t4}/calendar/caldav/{account}\"")),
        "{page}"
    );
    assert!(page.contains(">Telefon 4<") && page.contains("Belongs to"));
    assert!(!page.contains("/remove\"") && !page.contains("/sharing#sharing"));
}

/// M1: two phones' Tests race for an account no phone owns. The other phone claims it while this phone's Test is
/// asking the server: no claim here - the pass goes on as an ordinary Test while the account is still shared, and
/// is rolled back ("Kontoen hører til …") once it is the other phone's alone.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_claim_race_has_one_winner() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let p = phones(&app, 3).await;
    let (t1, t2, t3) = (p[0].0, p[1].0, p[2].0);
    fake_icloud(&app);
    test_form(&app, &cookie, t1, SERVER, PASSWORD).await;
    let account = account_id(&app).await;
    share(&app, &cookie, t1, account, true).await;
    delete_phone(&app, t1).await;
    assert_eq!(owner(&app, account).await, (None, true));
    // 1: Telefon 3 claims and keeps it shared; 2: Telefon 3 claims and unshares - while Telefon 2's Test lists the
    // calendars.
    let mode = Arc::new(AtomicU8::new(0));
    let (db, seen) = (app.db.clone(), mode.clone());
    app.fetch.serve(Arc::new(move |r: &super::music::Recorded| {
        if !(r.method == "PROPFIND" && r.url == LIST && r.header("authorization").is_some()) {
            return None;
        }
        let shared = match seen.swap(0, Ordering::SeqCst) {
            1 => 1,
            2 => 0,
            _ => return None,
        };
        let db = db.clone();
        tokio::task::block_in_place(|| {
            tokio::runtime::Handle::current().block_on(async move {
                sqlx::query(
                    "UPDATE caldav_accounts SET owner_device_id = ?, shared = ? WHERE id = ? \
                     AND (principal IS NULL OR owner_device_id IS NULL)",
                )
                .bind(t3)
                .bind(shared)
                .bind(account)
                .execute(&db)
                .await
                .unwrap();
            })
        });
        None
    }));
    mode.store(1, Ordering::SeqCst);
    next_test(&app).await;
    caldav::test_again(&app.state, t2, account, PASSWORD, Utc::now())
        .await
        .unwrap();
    assert_eq!(owner(&app, account).await, (Some(t3), true), "one winner");

    sqlx::query("UPDATE caldav_accounts SET owner_device_id = NULL WHERE id = ?")
        .bind(account)
        .execute(&app.db)
        .await
        .unwrap();
    let nonce: Vec<u8> =
        sqlx::query_scalar("SELECT password_nonce FROM caldav_accounts WHERE id = ?")
            .bind(account)
            .fetch_one(&app.db)
            .await
            .unwrap();
    mode.store(2, Ordering::SeqCst);
    next_test(&app).await;
    let lost = caldav::test_again(&app.state, t2, account, PASSWORD, Utc::now()).await;
    assert!(
        matches!(&lost, Err(caldav::TestError::OtherPhone(name)) if name == "Telefon 3"),
        "{lost:?}"
    );
    assert_eq!(owner(&app, account).await, (Some(t3), false));
    let after: Vec<u8> =
        sqlx::query_scalar("SELECT password_nonce FROM caldav_accounts WHERE id = ?")
            .bind(account)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(after, nonce, "the pass rolled back");
    // Through the route, Telefon 2's page has nothing left: back to its sources.
    let res = app
        .get_page(&format!("/devices/{t2}/calendar/caldav/{account}"), &cookie)
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{t2}/calendar#sources").as_str())
    );
}

/// Sharing on and off: the sibling's row and page, its ticks and feeds, no mark there, the owner's mark untouched;
/// off takes the sibling's calendars (nudged, named in the line), keeps their settings by key, and a stale tab
/// changes nothing. The settings log records the switch as a save.
#[tokio::test]
async fn sharing_on_and_off() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let p = phones(&app, 2).await;
    let ((t1, token1), (t2, token2)) = (p[0].clone(), p[1].clone());
    app.post_status(&token1, serde_json::json!({})).await;
    app.post_status(&token2, serde_json::json!({})).await;
    fake_icloud(&app);
    test_form(&app, &cookie, t1, SERVER, PASSWORD).await;
    let account = account_id(&app).await;
    ticks(&app, &cookie, t1, account, &[], &[HOME]).await;
    let res = mark(&app, &cookie, t1, &home_uid()).await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    let saved = settings_saved(&app).await;
    let res = share(&app, &cookie, t1, account, true).await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{t1}/calendar/caldav/{account}#sharing").as_str())
    );
    assert!(settings_saved(&app).await >= saved.max(1));
    let detail: String = sqlx::query_scalar(
        "SELECT detail FROM security_events WHERE event_type = 'settings_saved' AND detail LIKE '%/sharing%' \
         ORDER BY id DESC LIMIT 1",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(
        detail.contains("sharing") && !detail.contains(USER),
        "{detail}"
    );

    // Telefon 2: the row's "· shared from Telefon 1", "Belongs to", no switch, no remove.
    let page = app
        .get_page(&format!("/devices/{t2}/calendar"), &cookie)
        .await
        .text();
    let row = super::settings_pages::part(&page, &format!("data-caldav=\"{account}\""), "</a>");
    assert!(row.contains("· shared from Telefon 1"), "{row}");
    let nb = app
        .get_page(&format!("/devices/{t1}/calendar?lang=nb"), &cookie)
        .await
        .text();
    let row = super::settings_pages::part(&nb, &format!("data-caldav=\"{account}\""), "</a>");
    assert!(
        row.contains(" · delt<") && !row.contains("tone-orange"),
        "{row}"
    );
    let sibling = format!("/devices/{t2}/calendar/caldav/{account}");
    let page = app.get_page(&sibling, &cookie).await.text();
    assert!(page.contains("Belongs to") && page.contains(">Telefon 1<"));
    assert!(!page.contains("/sharing#sharing") && !page.contains("/remove\""));
    // Its ticks reach its feeds; it can't mark; Test works; remove and sharing are refused.
    ticks(&app, &cookie, t2, account, &[], &[FRITID]).await;
    let policy = app.policy(&token2).await;
    assert_eq!(policy["calendar"]["feeds"][0]["id"], fritid_uid().as_str());
    let page = app
        .get_page(&format!("/devices/{t2}/calendar"), &cookie)
        .await
        .text();
    assert!(!page.contains(&format!("caldav:{}", fritid_uid())));
    assert_eq!(
        mark(&app, &cookie, t2, &fritid_uid()).await.status,
        StatusCode::BAD_REQUEST
    );
    next_test(&app).await;
    let res = post(
        &app,
        &cookie,
        &format!("{sibling}/test"),
        &[("password", "")],
    )
    .await;
    assert!(res.location().unwrap().contains("notice=caldav_tested"));
    let saved = settings_saved(&app).await;
    for path in ["remove", "sharing"] {
        let res = post(
            &app,
            &cookie,
            &format!("{sibling}/{path}"),
            &[("was_shared", "1")],
        )
        .await;
        assert_eq!(
            res.location(),
            Some(format!("{sibling}#account").as_str()),
            "{path}"
        );
    }
    assert_eq!(settings_saved(&app).await, saved);
    assert_eq!(owner(&app, account).await, (Some(t1), true));
    // A setting of Telefon 2's calendar, by its key.
    let key = crate::calendar_feeds::calendar_key(&fritid_uid());
    sqlx::query(
        "INSERT INTO device_calendars (device_id, calendar_key, shown) VALUES (?, ?, 0) \
         ON CONFLICT(device_id, calendar_key) DO UPDATE SET shown = 0",
    )
    .bind(t2)
    .bind(&key)
    .execute(&app.db)
    .await
    .unwrap();

    // Off: Telefon 2's calendars go (nudged, named), Telefon 1's mark stays.
    let mut nudges = app.state.command_notify.subscribe();
    let res = share(&app, &cookie, t1, account, false).await;
    let line = format!("/devices/{t1}/calendar/caldav/{account}?notice=unshared&from={t2}#sharing");
    assert_eq!(res.location(), Some(line.as_str()));
    assert_eq!(nudges.try_recv().unwrap(), t2);
    let page = app
        .get_page(line.trim_end_matches("#sharing"), &cookie)
        .await
        .text();
    assert!(page.contains(">Removed from Telefon 2.<"), "{page}");
    let nb = app
        .get_page(
            &format!("{}&lang=nb", line.trim_end_matches("#sharing")),
            &cookie,
        )
        .await
        .text();
    assert!(nb.contains(">Fjernet fra Telefon 2.<"));
    assert!(feeds(&app, t2).await.is_empty());
    assert!(
        app.policy(&token2).await["calendar"]["feeds"]
            .as_array()
            .unwrap()
            .is_empty()
    );
    assert_eq!(
        crate::calendar_kid_events::marked_uid(&app.db, t1)
            .await
            .unwrap(),
        Some(home_uid())
    );
    // A stale tab drawn shared posts off again: nothing changes, no line.
    let res = share(&app, &cookie, t1, account, false).await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{t1}/calendar/caldav/{account}#sharing").as_str())
    );
    // Shared and ticked again, Telefon 2's setting is still there.
    share(&app, &cookie, t1, account, true).await;
    ticks(&app, &cookie, t2, account, &[], &[FRITID]).await;
    let shown: bool = sqlx::query_scalar(
        "SELECT shown FROM device_calendars WHERE device_id = ? AND calendar_key = ?",
    )
    .bind(t2)
    .bind(&key)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(!shown);
    // A forged sharing post for an account no phone owns is refused.
    sqlx::query("UPDATE caldav_accounts SET owner_device_id = NULL")
        .execute(&app.db)
        .await
        .unwrap();
    let res = post(
        &app,
        &cookie,
        &format!("/devices/{t1}/calendar/caldav/{account}/sharing"),
        &[("was_shared", "1")],
    )
    .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{t1}/calendar/caldav/{account}#account").as_str())
    );
    assert_eq!(owner(&app, account).await, (None, true));
}

/// Decision 9: the forms send what they were drawn with. A stale ticks tab never unticks a calendar ticked since, a
/// post that changed nothing lands on `#calendars`, a stale sibling tab is refused and leaves no tick (L1), and a
/// remove drawn unshared for an account shared since is refused with the shared question open (M3).
#[tokio::test]
async fn stale_forms_change_only_what_they_changed() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let p = phones(&app, 2).await;
    let (t1, t2) = (p[0].0, p[1].0);
    fake_icloud(&app);
    test_form(&app, &cookie, t1, SERVER, PASSWORD).await;
    let account = account_id(&app).await;
    let page = format!("/devices/{t1}/calendar/caldav/{account}");
    let form = app.get_page(&page, &cookie).await.text();
    assert!(form.contains(&format!(
        "name=\"was_on_{}\" value=\"0\"",
        caldav::calendar_id(HOME)
    )));
    ticks(&app, &cookie, t1, account, &[], &[HOME]).await;
    // The other tab, drawn before Hjem was ticked, ticks Fritid: Hjem stays.
    let res = ticks(&app, &cookie, t1, account, &[], &[FRITID]).await;
    assert_eq!(res.location(), Some(format!("{page}#calendars").as_str()));
    let mut both = vec![home_uid(), fritid_uid()];
    both.sort();
    assert_eq!(feeds(&app, t1).await, both);
    // A post that changed nothing.
    let res = ticks(&app, &cookie, t1, account, &[HOME, FRITID], &[HOME, FRITID]).await;
    assert_eq!(res.location(), Some(format!("{page}#calendars").as_str()));
    assert_eq!(feeds(&app, t1).await, both);

    // A sibling tab drawn while shared ticks after the owner unshared: refused, no tick left.
    share(&app, &cookie, t1, account, true).await;
    let drawn = app
        .get_page(&format!("/devices/{t2}/calendar/caldav/{account}"), &cookie)
        .await;
    assert_eq!(drawn.status, StatusCode::OK);
    share(&app, &cookie, t1, account, false).await;
    let res = ticks(&app, &cookie, t2, account, &[], &[HOME, FRITID]).await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{t2}/calendar#sources").as_str())
    );
    assert!(feeds(&app, t2).await.is_empty());

    // M3: the remove confirm drawn unshared, the account shared since.
    let drawn = app.get_page(&page, &cookie).await.text();
    assert!(drawn.contains("name=\"was_shared\" value=\"0\""));
    assert!(drawn.contains("calendars leave this phone"));
    share(&app, &cookie, t1, account, true).await;
    let res = post(
        &app,
        &cookie,
        &format!("{page}/remove"),
        &[("was_shared", "0")],
    )
    .await;
    assert_eq!(
        res.location(),
        Some(format!("{page}?notice=remove_shared#remove").as_str())
    );
    assert_eq!(
        owner(&app, account).await,
        (Some(t1), true),
        "nothing deleted"
    );
    let again = app
        .get_page(&format!("{page}?notice=remove_shared"), &cookie)
        .await
        .text();
    let confirm = super::settings_pages::part(&again, "<section id=\"remove\"", "</section>");
    assert!(
        confirm.contains("<details class=\"confirm cell\" open>"),
        "{confirm}"
    );
    assert!(confirm.contains("go off every phone"), "{confirm}");
    // Posted as drawn now, it goes.
    let res = post(
        &app,
        &cookie,
        &format!("{page}/remove"),
        &[("was_shared", "1")],
    )
    .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{t1}/calendar#sources").as_str())
    );
    let left: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM caldav_accounts")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(left, 0);
}

/// A deleted owner: a shared account loses its owner and the siblings keep their feeds; an unshared one is hidden and
/// swept a day later. L4: unsharing an account the owner ticks none of leaves no source - stamped, swept after
/// [caldav::UNUSED_HOURS] unless the owner ticks one first.
#[tokio::test]
async fn a_deleted_owner_and_the_one_day_sweep() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let p = phones(&app, 3).await;
    let (t1, t2, t3) = (p[0].0, p[1].0, p[2].0);
    fake_icloud(&app);
    test_form(&app, &cookie, t1, SERVER, PASSWORD).await;
    let account = account_id(&app).await;
    let unused = || async {
        sqlx::query_scalar::<_, Option<String>>(
            "SELECT unused_since FROM caldav_accounts WHERE id = ?",
        )
        .bind(account)
        .fetch_one(&app.db)
        .await
        .unwrap()
    };
    let later = Utc::now() + chrono::TimeDelta::hours(caldav::UNUSED_HOURS + 1);
    let sooner = Utc::now() + chrono::TimeDelta::hours(caldav::UNUSED_HOURS - 1);

    // L4: shared, only Telefon 2 ticks; unsharing leaves no source.
    share(&app, &cookie, t1, account, true).await;
    ticks(&app, &cookie, t2, account, &[], &[HOME]).await;
    assert!(unused().await.is_none());
    share(&app, &cookie, t1, account, false).await;
    assert!(unused().await.is_some(), "stamped");
    let sources: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM calendar_sources")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(sources, 0);
    assert_eq!(caldav::sweep(&app.db, sooner).await.unwrap(), 0);
    // The owner ticks one first: kept.
    ticks(&app, &cookie, t1, account, &[], &[HOME]).await;
    assert!(unused().await.is_none());
    assert_eq!(caldav::sweep(&app.db, later).await.unwrap(), 0);

    // Shared again, Telefon 2 ticks Fritid; Telefon 1 goes: no owner, Telefon 2 keeps its feed.
    share(&app, &cookie, t1, account, true).await;
    ticks(&app, &cookie, t2, account, &[], &[FRITID]).await;
    delete_phone(&app, t1).await;
    assert_eq!(owner(&app, account).await, (None, true));
    assert_eq!(feeds(&app, t2).await, [fritid_uid()]);
    assert_eq!(caldav::sweep(&app.db, later).await.unwrap(), 0);

    // Claimed by Telefon 3, unshared, and Telefon 3 goes: hidden everywhere, swept a day later.
    next_test(&app).await;
    test_form(&app, &cookie, t3, SERVER, PASSWORD).await;
    assert_eq!(owner(&app, account).await, (Some(t3), true));
    let res = share(&app, &cookie, t3, account, false).await;
    assert!(res.location().unwrap().contains(&format!("from={t2}")));
    ticks(&app, &cookie, t3, account, &[], &[HOME]).await;
    delete_phone(&app, t3).await;
    assert_eq!(owner(&app, account).await, (None, false));
    assert!(caldav::accounts(&app.db, t2).await.unwrap().is_empty());
    assert!(unused().await.is_some());
    assert_eq!(caldav::sweep(&app.db, sooner).await.unwrap(), 0);
    assert_eq!(caldav::sweep(&app.db, later).await.unwrap(), 1);
}

/// Decision 11: a replaced phone. Telefon 1 writes to its shared account and is deleted; the new Telefon 3's page
/// shows "Belongs to · none"; its typed-password Test claims it, and its Kalender can mark the calendar again.
#[tokio::test]
async fn a_replaced_phone_takes_its_account_back() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let p = phones(&app, 2).await;
    let ((t1, token1), (t2, _)) = (p[0].clone(), p[1].clone());
    app.post_status(&token1, serde_json::json!({})).await;
    fake_icloud(&app);
    test_form(&app, &cookie, t1, SERVER, PASSWORD).await;
    let account = account_id(&app).await;
    ticks(&app, &cookie, t1, account, &[], &[HOME]).await;
    assert_eq!(
        mark(&app, &cookie, t1, &home_uid()).await.status,
        StatusCode::SEE_OTHER
    );
    share(&app, &cookie, t1, account, true).await;
    ticks(&app, &cookie, t2, account, &[], &[FRITID]).await;
    delete_phone(&app, t1).await;
    let (t3, token3) = app.enrolled_device("Telefon 3").await;
    app.post_status(&token3, serde_json::json!({})).await;
    let page = format!("/devices/{t3}/calendar/caldav/{account}");
    let drawn = app.get_page(&page, &cookie).await.text();
    assert!(drawn.contains("data-value=\"owner\">none<"), "{drawn}");
    next_test(&app).await;
    post(
        &app,
        &cookie,
        &format!("{page}/test"),
        &[("password", PASSWORD)],
    )
    .await;
    assert_eq!(owner(&app, account).await, (Some(t3), true));
    ticks(&app, &cookie, t3, account, &[], &[HOME]).await;
    let kalender = app
        .get_page(&format!("/devices/{t3}/calendar"), &cookie)
        .await
        .text();
    assert!(kalender.contains(&format!("caldav:{}", home_uid())));
    assert_eq!(
        mark(&app, &cookie, t3, &home_uid()).await.status,
        StatusCode::SEE_OTHER
    );
    assert_eq!(
        crate::calendar_kid_events::marked_uid(&app.db, t3)
            .await
            .unwrap(),
        Some(home_uid())
    );
    assert_eq!(feeds(&app, t2).await, [fritid_uid()]);
}
