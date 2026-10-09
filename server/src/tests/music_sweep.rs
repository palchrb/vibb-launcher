//! The music sweep (design 21b §8): listing NRK and RSS sources on canned answers (recorded psapi
//! and podkast.nrk.no answers in `testdata/music_sources/`) with a fake clock - first fills and
//! incremental checks, the windows, merges, caps, failures that keep the last good list, the
//! cadence and its load, addresses, covers, nudges, rechecks, the listing route and the cards.

use axum::http::{Method, StatusCode, header};
use chrono::{DateTime, TimeDelta, Utc};
use serde_json::{Value, json};

use super::TestApp;
use super::music::{Canned, device_get, events, nudged, pretty_json, tick};
use crate::music_net::FetchError;
use crate::music_sweep::{self as sweep, Breaker, CheckReport, parse_stamp, stamp};

const PSAPI: &str = "https://psapi.nrk.no";

fn at(text: &str) -> DateTime<Utc> {
    parse_stamp(text).unwrap()
}

fn t0() -> DateTime<Utc> {
    at("2026-10-09 12:00:00")
}

fn fixture(name: &str) -> Vec<u8> {
    std::fs::read(format!("testdata/music_sources/{name}")).unwrap()
}

/// An entry straight into the table (no add check), created at `created`.
async fn entry(app: &TestApp, target: &str, name: &str, order: &str, created: &str) -> i64 {
    let link = crate::music::parse_link(target).unwrap();
    sqlx::query_scalar(
        "INSERT INTO music_entries (name, category_id, source, target, key, play_order, cache, \
         resume, sort, created_at) VALUES (?, 4, ?, ?, ?, ?, 5, 1, \
         (SELECT COALESCE(MAX(sort), 0) + 10 FROM music_entries), ?) RETURNING id",
    )
    .bind(name)
    .bind(link.source())
    .bind(link.target())
    .bind(crate::music::state_key(&link.target()))
    .bind(order)
    .bind(created)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

async fn tick_on(app: &TestApp, device: i64, entry: i64) {
    sqlx::query("INSERT OR IGNORE INTO device_music_entries (device_id, entry_id) VALUES (?, ?)")
        .bind(device)
        .bind(entry)
        .execute(&app.db)
        .await
        .unwrap();
}

async fn check(app: &TestApp, entry: i64, now: DateTime<Utc>) -> CheckReport {
    sweep::check_entry(&app.state, entry, now, &mut Breaker::default())
        .await
        .unwrap()
        .expect("the entry is checked")
}

async fn listing(app: &TestApp, entry: i64) -> sweep::Listing {
    sweep::load_listing(&app.db, entry).await.unwrap().unwrap()
}

async fn items(app: &TestApp, entry: i64) -> Vec<sweep::Item> {
    let mut conn = app.db.acquire().await.unwrap();
    sweep::load_items(&mut conn, entry).await.unwrap()
}

fn keys(items: &[sweep::Item]) -> Vec<String> {
    items.iter().map(|i| i.key.clone()).collect()
}

fn states(items: &[sweep::Item]) -> Vec<String> {
    items.iter().map(|i| i.state.clone()).collect()
}

/// A psapi episode stub.
fn stub(kind: &str, slug: &str, key: &str, minutes_ago: i64) -> Value {
    let date = (t0() - TimeDelta::minutes(minutes_ago)).to_rfc3339();
    json!({
        "_links": {"self": {"href": format!("/radio/catalog/{kind}/{slug}/episodes/{key}")}},
        "episodeId": key,
        "titles": {"title": format!("Episode {key}")},
        "date": date,
        "durationInSeconds": 600,
        "squareImage": [{"url": format!("https://gfx.nrk.no/{key}"), "width": 300}],
        "usageRights": {"to": {"date": "9999-12-31T00:00:00+01:00"}}
    })
}

fn page_url(kind: &str, slug: &str, sort: &str, page: usize) -> String {
    format!("{PSAPI}/radio/catalog/{kind}/{slug}/episodes?page={page}&pageSize=50&sort={sort}")
}

/// psapi's episode pages of a podcast or series whose episodes are `newest_first` (both sorts).
fn answer_pages(app: &TestApp, kind: &str, slug: &str, newest_first: &[String]) {
    let oldest_first: Vec<String> = newest_first.iter().rev().cloned().collect();
    for (sort, keys) in [("desc", newest_first.to_vec()), ("asc", oldest_first)] {
        let pages: Vec<&[String]> = if keys.is_empty() {
            vec![&[][..]]
        } else {
            keys.chunks(50).collect()
        };
        for (index, chunk) in pages.iter().enumerate() {
            let mut body = json!({
                "_embedded": {"episodes": chunk.iter().map(|key| {
                    let age = newest_first.iter().position(|k| k == key).unwrap() as i64;
                    stub(kind, slug, key, age * 60)
                }).collect::<Vec<_>>()}
            });
            if index + 1 < pages.len() {
                body["_links"] = json!({"next": {"href": format!(
                    "/radio/catalog/{kind}/{slug}/episodes?page={}&pageSize=50&sort={sort}",
                    index + 2
                )}});
            }
            app.fetch.answer(
                &page_url(kind, slug, sort, index + 1),
                200,
                body.to_string(),
            );
        }
    }
}

fn manifest_url(kind: &str, key: &str) -> String {
    format!("{PSAPI}/playback/manifest/{kind}/{key}")
}

fn audio(key: &str) -> String {
    format!("https://podkast.nrk.no/fil/{key}.mp3")
}

fn answer_manifests(app: &TestApp, kind: &str, keys: &[String]) {
    for key in keys {
        answer_manifest(app, kind, key, &audio(key));
    }
}

fn answer_manifest(app: &TestApp, kind: &str, key: &str, url: &str) {
    app.fetch.answer(
        &manifest_url(kind, key),
        200,
        json!({
            "playable": {"duration": "PT10M", "assets": [{"url": url, "format": "MP3", "encrypted": false}]},
            "availability": {"onDemand": {"to": "9999-12-31T23:59:59Z"}}
        })
        .to_string(),
    );
}

fn answer_root(app: &TestApp, kind: &str, slug: &str, title: &str, image: Option<&str>) {
    let mut series = json!({"titles": {"title": title}});
    if let Some(image) = image {
        series["squareImage"] = json!([{"url": image, "width": 600}]);
    }
    app.fetch.answer(
        &format!("{PSAPI}/radio/catalog/{kind}/{slug}"),
        200,
        json!({ "series": series }).to_string(),
    );
}

fn keys_n(prefix: &str, n: usize) -> Vec<String> {
    // Newest first: prefix_<n> ... prefix_1.
    (1..=n).rev().map(|i| format!("{prefix}_{i}")).collect()
}

fn jpeg(shade: u8) -> Vec<u8> {
    let image = image::RgbImage::from_pixel(64, 64, image::Rgb([shade, 100, 50]));
    let mut out = std::io::Cursor::new(Vec::new());
    image::DynamicImage::ImageRgb8(image)
        .write_to(&mut out, image::ImageFormat::Jpeg)
        .unwrap();
    out.into_inner()
}

async fn library(app: &TestApp, token: &str) -> (Value, String) {
    let res = device_get(app, "/api/devices/music/library", token, &[]).await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    let etag = res.headers[header::ETAG].to_str().unwrap().to_string();
    (res.json(), etag)
}

fn lib_entry(library: &Value, id: i64) -> Value {
    library["entries"]
        .as_array()
        .unwrap()
        .iter()
        .find(|e| e["id"] == id)
        .cloned()
        .unwrap()
}

// ------------------------------------------------------------------------------------------------
// NRK podcasts
// ------------------------------------------------------------------------------------------------

/// The recorded answers: a first fill reads the root, walks the desc pages and resolves each
/// manifest (a stub repeated across pages once), and fetches the show's square image as the cover.
#[tokio::test]
async fn a_recorded_nrk_podcast_is_filled_from_psapi() {
    let app = TestApp::new().await;
    let id = entry(
        &app,
        "https://radio.nrk.no/podkast/abels_taarn",
        "Abels tårn",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    app.fetch.answer(
        &format!("{PSAPI}/radio/catalog/podcast/abels_taarn"),
        200,
        fixture("psapi_podcast_root.json"),
    );
    app.fetch.answer(
        &page_url("podcast", "abels_taarn", "desc", 1),
        200,
        fixture("psapi_podcast_episodes_desc.json"),
    );
    app.fetch.answer(
        &page_url("podcast", "abels_taarn", "desc", 2),
        200,
        r#"{"_embedded": {"episodes": []}}"#,
    );
    let page: Value = serde_json::from_slice(&fixture("psapi_podcast_episodes_desc.json")).unwrap();
    let recorded: Vec<String> = page["_embedded"]["episodes"]
        .as_array()
        .unwrap()
        .iter()
        .map(|e| e["episodeId"].as_str().unwrap().to_string())
        .collect();
    for key in &recorded {
        app.fetch.answer(
            &manifest_url("podcast", key),
            200,
            fixture("psapi_manifest_podcast.json"),
        );
    }
    let root: Value = serde_json::from_slice(&fixture("psapi_podcast_root.json")).unwrap();
    let image = crate::music_sources::pick_image(root.pointer("/series/squareImage"), 512).unwrap();
    app.fetch.answer(&image, 200, jpeg(10));

    let report = check(&app, id, t0()).await;
    assert_eq!(report.error, None);
    assert_eq!(report.new_items, 3);
    // root, 2 pages, 3 manifests, the cover
    assert_eq!(report.requests, 7, "{:?}", app.fetch.calls.lock().unwrap());
    let list = items(&app, id).await;
    assert_eq!(states(&list), ["ok", "ok", "ok"]);
    assert_eq!(list[2].key, recorded[0], "oldest first: the newest is last");
    assert!(list[2].url.as_deref().unwrap().ends_with(".mp3"));
    let row = listing(&app, id).await;
    assert_eq!(row.title.as_deref(), Some("Abels tårn"));
    assert_eq!(row.keep_end.as_deref(), Some("newest"));
    assert_eq!(row.lan, Some(false));
    assert!(row.version.is_some() && row.cover_hash.is_some());
    assert_eq!(row.failures, 0);
    assert_eq!(row.requests, 7);
    assert!(
        app.state
            .music_cover_dir
            .join(format!("{}.jpg", row.cover_hash.unwrap()))
            .exists()
    );
}

/// The coordinator's must: a new episode changes the library's `items` version and its ETag, for
/// the phone with the entry only, with one nudge; nothing new changes nothing and costs one
/// request.
#[tokio::test]
async fn a_new_episode_moves_the_library_version_and_the_etag() {
    let app = TestApp::new().await;
    let (phone, token) = app.enrolled_device("Ella").await;
    let (other, other_token) = app.enrolled_device("Max").await;
    let id = entry(
        &app,
        "https://radio.nrk.no/podkast/ukas",
        "Ukas",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    let own: i64 = sqlx::query_scalar(
        "INSERT INTO music_entries (name, category_id, source, key, cache, resume, sort) \
         VALUES ('Bil', 1, 'own', 'own-1', -1, 0, 99) RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    tick_on(&app, phone, id).await;
    tick_on(&app, other, own).await;
    answer_root(&app, "podcast", "ukas", "Ukas", None);
    let mut episodes = keys_n("u", 3);
    answer_pages(&app, "podcast", "ukas", &episodes);
    answer_manifests(&app, "podcast", &episodes);
    check(&app, id, t0()).await;
    let (before, etag_before) = library(&app, &token).await;
    let (_, other_etag) = library(&app, &other_token).await;
    let version_before = lib_entry(&before, id)["items"]
        .as_str()
        .unwrap()
        .to_string();
    assert_eq!(
        Some(version_before.clone()),
        listing(&app, id).await.version
    );

    let mut rx = app.state.command_notify.subscribe();
    app.fetch.clear_calls();
    let same = check(&app, id, t0() + TimeDelta::hours(12)).await;
    assert_eq!((same.requests, same.new_items, same.changed), (1, 0, false));
    assert_eq!(
        library(&app, &token).await.1,
        etag_before,
        "nothing new, nothing moves"
    );

    episodes.insert(0, "u_4".to_string());
    answer_pages(&app, "podcast", "ukas", &episodes);
    answer_manifests(&app, "podcast", &episodes[..1]);
    app.fetch.clear_calls();
    let fresh = check(&app, id, t0() + TimeDelta::hours(24)).await;
    assert_eq!(
        app.fetch.calls.lock().unwrap().as_slice(),
        [
            page_url("podcast", "ukas", "desc", 1),
            manifest_url("podcast", "u_4")
        ],
        "the walk stops at the first known key; only the new episode's manifest"
    );
    assert!(fresh.changed);
    assert_eq!(fresh.phones, vec![phone], "only the phone with the entry");
    let (after, etag_after) = library(&app, &token).await;
    let version_after = lib_entry(&after, id)["items"].as_str().unwrap().to_string();
    assert_ne!(version_after, version_before);
    assert_ne!(etag_after, etag_before);
    assert_eq!(
        library(&app, &other_token).await.1,
        other_etag,
        "the other phone's library stays"
    );
    // check_entry reports the phones; the pass nudges them once.
    assert!(nudged(&mut rx).is_empty());
    let listing_doc = device_get(
        &app,
        &format!("/api/devices/music/entries/{id}/items"),
        &token,
        &[],
    )
    .await;
    assert_eq!(listing_doc.json()["items"].as_array().unwrap().len(), 4);
    assert_eq!(listing_doc.json()["version"], version_after.as_str());
}

/// QA #2: a manifest that fails leaves its item pending and the check succeeds; at most 10
/// earlier failures are retried per check; pending for 14 days -> gone.
#[tokio::test]
async fn failing_manifests_leave_items_pending_not_the_entry_dark() {
    let app = TestApp::new().await;
    let id = entry(
        &app,
        "https://radio.nrk.no/podkast/p",
        "P",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    answer_root(&app, "podcast", "p", "P", None);
    let episodes = keys_n("p", 15);
    answer_pages(&app, "podcast", "p", &episodes);
    answer_manifests(&app, "podcast", &episodes[..3]);
    for key in &episodes[3..] {
        app.fetch.answer(&manifest_url("podcast", key), 503, "");
    }
    let report = check(&app, id, t0()).await;
    assert_eq!(report.error, None, "a manifest isn't the listing");
    let list = items(&app, id).await;
    assert_eq!(list.iter().filter(|i| i.state == "ok").count(), 3);
    assert!(
        list.iter()
            .filter(|i| i.state == "pending")
            .all(|i| i.attempts == 1)
    );
    let doc = sweep::build_listing(id, &list, false).unwrap();
    assert_eq!(doc.count, 3, "pending items aren't listed");

    // The next check retries at most 10 of the 12.
    app.fetch.clear_calls();
    check(&app, id, t0() + TimeDelta::hours(12)).await;
    let manifests = app
        .fetch
        .calls
        .lock()
        .unwrap()
        .iter()
        .filter(|u| u.contains("/playback/manifest/"))
        .count();
    assert_eq!(manifests, sweep::MAX_PENDING_RETRIES);

    // 14 days on, what is still pending is gone (and listed with url null).
    check(&app, id, t0() + TimeDelta::days(15)).await;
    let list = items(&app, id).await;
    assert!(list.iter().all(|i| i.state != "pending"));
    assert_eq!(list.iter().filter(|i| i.state == "gone").count(), 12);
}

/// A first fill whose every manifest fails is no list: `no_items`, the stubs kept with their
/// attempts (so a retry doesn't start over), the entry backs off.
#[tokio::test]
async fn a_fill_with_no_playable_episode_is_no_items_and_backs_off() {
    let app = TestApp::new().await;
    let id = entry(
        &app,
        "https://radio.nrk.no/podkast/x",
        "X",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    answer_root(&app, "podcast", "x", "X", None);
    answer_pages(&app, "podcast", "x", &keys_n("x", 2));
    let report = check(&app, id, t0()).await;
    assert_eq!(report.error.as_deref(), Some("no_items"));
    let row = listing(&app, id).await;
    assert_eq!((row.version, row.failures), (None, 1));
    assert!(items(&app, id).await.iter().all(|i| i.attempts == 1));
    let due: Vec<i64> = sweep::due(&app.db, t0() + TimeDelta::minutes(10))
        .await
        .unwrap()
        .iter()
        .map(|s| s.entry_id)
        .collect();
    assert!(due.is_empty(), "the backoff, not a loop");
    let due: Vec<i64> = sweep::due(&app.db, t0() + TimeDelta::minutes(15))
        .await
        .unwrap()
        .iter()
        .map(|s| s.entry_id)
        .collect();
    assert_eq!(due, vec![id]);
}

/// The check budget: what is resolved is committed, the rest stays pending, and the entry
/// continues at the next free slot.
#[tokio::test]
async fn the_budget_commits_progress_and_the_next_check_continues() {
    let app = TestApp::new().await;
    app.state
        .music_sweep
        .set_limits(10, std::time::Duration::ZERO);
    let id = entry(
        &app,
        "https://radio.nrk.no/podkast/b",
        "B",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    answer_root(&app, "podcast", "b", "B", None);
    let episodes = keys_n("b", 20);
    answer_pages(&app, "podcast", "b", &episodes);
    answer_manifests(&app, "podcast", &episodes);
    let first = check(&app, id, t0()).await;
    assert_eq!(first.requests, 10);
    assert_eq!(first.error, None, "reaching the budget isn't a failure");
    let list = items(&app, id).await;
    assert_eq!(list.len(), 20);
    assert_eq!(
        list.iter().filter(|i| i.state == "ok").count(),
        8,
        "root + page + 8 manifests"
    );
    let due = sweep::due(&app.db, t0() + TimeDelta::seconds(5))
        .await
        .unwrap();
    assert_eq!(
        due.first().map(|s| (s.entry_id, s.why)),
        Some((id, sweep::Why::Rework))
    );
    check(&app, id, t0() + TimeDelta::seconds(5)).await;
    check(&app, id, t0() + TimeDelta::seconds(10)).await;
    assert!(items(&app, id).await.iter().all(|i| i.state == "ok"));
    assert!(
        sweep::due(&app.db, t0() + TimeDelta::seconds(15))
            .await
            .unwrap()
            .is_empty()
    );
}

/// The podcast cap: the newest 100, the oldest gone first.
#[tokio::test]
async fn a_podcast_keeps_its_newest_100() {
    let app = TestApp::new().await;
    let id = entry(
        &app,
        "https://radio.nrk.no/podkast/c",
        "C",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    answer_root(&app, "podcast", "c", "C", None);
    let mut episodes = keys_n("c", 120);
    answer_pages(&app, "podcast", "c", &episodes);
    answer_manifests(&app, "podcast", &episodes);
    check(&app, id, t0()).await;
    let list = items(&app, id).await;
    assert_eq!(list.len(), 100);
    assert_eq!(
        (list[0].key.as_str(), list[99].key.as_str()),
        ("c_21", "c_120")
    );
    assert!(listing(&app, id).await.capped);
    // A withdrawn episode is gone; two new ones push out the gone one first.
    sqlx::query("UPDATE music_items SET state = 'gone', url = NULL WHERE key = 'c_50'")
        .execute(&app.db)
        .await
        .unwrap();
    episodes.insert(0, "c_121".into());
    episodes.insert(0, "c_122".into());
    answer_pages(&app, "podcast", "c", &episodes);
    answer_manifests(&app, "podcast", &episodes[..2]);
    check(&app, id, t0() + TimeDelta::hours(12)).await;
    let list = keys(&items(&app, id).await);
    assert_eq!(list.len(), 100);
    assert!(
        !list.contains(&"c_50".to_string()),
        "the gone one goes first"
    );
    assert!(!list.contains(&"c_21".to_string()));
    assert_eq!((list[0].as_str(), list[99].as_str()), ("c_22", "c_122"));
}

/// psapi lists nothing: podkast.nrk.no's RSS (recorded) with its guids as keys; when psapi comes
/// back the same keys continue the list.
#[tokio::test]
async fn the_rss_fallback_keeps_psapis_keys() {
    let app = TestApp::new().await;
    let id = entry(
        &app,
        "https://radio.nrk.no/podkast/abels_taarn",
        "Abels tårn",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    answer_root(&app, "podcast", "abels_taarn", "Abels tårn", None);
    answer_pages(&app, "podcast", "abels_taarn", &[]);
    app.fetch.answer(
        "https://podkast.nrk.no/program/abels_taarn.rss",
        200,
        fixture("nrk_podkast_fallback.rss"),
    );
    app.fetch.answer(
        "https://gfx.nrk.no/cvcBxxMTH8eVin9Ez6izvQn1QPtdpI3Qu1YD-lHBcXVA.png",
        404,
        "",
    );
    let report = check(&app, id, t0()).await;
    assert_eq!(report.error, None);
    let row = listing(&app, id).await;
    assert!(row.fallback);
    let fallback = items(&app, id).await;
    assert_eq!(fallback.len(), 5);
    assert_eq!(
        fallback[4].key, "l_1714dacf-d103-4582-94da-cfd103558293",
        "the guid itself"
    );
    assert!(fallback.iter().all(|i| i.state == "ok"));

    // psapi is back with one new episode on top of the same five.
    let mut episodes: Vec<String> = fallback.iter().rev().map(|i| i.key.clone()).collect();
    episodes.insert(0, "l_new".into());
    answer_pages(&app, "podcast", "abels_taarn", &episodes);
    answer_manifests(&app, "podcast", &episodes[..1]);
    let report = check(&app, id, t0() + TimeDelta::hours(12)).await;
    assert_eq!(report.new_items, 1);
    let after = items(&app, id).await;
    assert_eq!(keys(&after)[..5], keys(&fallback)[..]);
    assert_eq!(after[5].key, "l_new");
    assert!(!listing(&app, id).await.fallback);
}

// ------------------------------------------------------------------------------------------------
// NRK series and programmes (the user's answer to open question 1)
// ------------------------------------------------------------------------------------------------

/// A 150-episode series: `auto` keeps the newest 100 and plays newest first; "Oldest first" keeps
/// the first 100, never evicts its start and needs no request once full; changing the order
/// refills from the other end.
#[tokio::test]
async fn a_long_series_window_follows_the_play_order() {
    let app = TestApp::new().await;
    let (phone, token) = app.enrolled_device("Ella").await;
    let id = entry(
        &app,
        "https://radio.nrk.no/serie/lang",
        "Lang",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    tick_on(&app, phone, id).await;
    answer_root(&app, "series", "lang", "Lang", None);
    let episodes = keys_n("s", 150);
    answer_pages(&app, "series", "lang", &episodes);
    answer_manifests(&app, "program", &episodes);
    check(&app, id, t0()).await;
    let list = keys(&items(&app, id).await);
    assert_eq!(
        (list.len(), list[0].as_str(), list[99].as_str()),
        (100, "s_51", "s_150")
    );
    let row = listing(&app, id).await;
    assert_eq!(
        (row.keep_end.as_deref(), row.capped),
        (Some("newest"), true)
    );
    let (lib, _) = library(&app, &token).await;
    assert_eq!(
        lib_entry(&lib, id)["order"],
        "newest_first",
        "auto plays a long series newest first"
    );

    // Oldest first: a refill from the start, then nothing to fetch while it is full.
    sqlx::query("UPDATE music_entries SET play_order = 'oldest_first' WHERE id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let due = sweep::due(&app.db, t0() + TimeDelta::minutes(1))
        .await
        .unwrap();
    assert_eq!(
        due.first().map(|s| s.why),
        Some(sweep::Why::Rework),
        "the refill is due at once"
    );
    app.fetch.clear_calls();
    check(&app, id, t0() + TimeDelta::minutes(1)).await;
    let list = keys(&items(&app, id).await);
    assert_eq!(
        (list.len(), list[0].as_str(), list[99].as_str()),
        (100, "s_1", "s_100")
    );
    assert_eq!(listing(&app, id).await.keep_end.as_deref(), Some("first"));
    let (lib, _) = library(&app, &token).await;
    assert_eq!(lib_entry(&lib, id)["order"], "oldest_first");
    app.fetch.clear_calls();
    let report = check(&app, id, t0() + TimeDelta::hours(13)).await;
    assert_eq!(
        report.requests, 0,
        "anchored at the start and full: nothing to ask"
    );
    assert_eq!(
        keys(&items(&app, id).await),
        list,
        "the start is never evicted"
    );
}

/// A series of at most 100 episodes (a serial story) keeps vibb's oldest-first `auto`: all of it,
/// from the start; new episodes are appended.
#[tokio::test]
async fn a_short_series_is_kept_whole_from_its_start() {
    let app = TestApp::new().await;
    let (phone, token) = app.enrolled_device("Ella").await;
    let id = entry(
        &app,
        "https://radio.nrk.no/serie/kort",
        "Kort",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    tick_on(&app, phone, id).await;
    answer_root(&app, "series", "kort", "Kort", None);
    let mut episodes = keys_n("k", 30);
    answer_pages(&app, "series", "kort", &episodes);
    answer_manifests(&app, "program", &episodes);
    check(&app, id, t0()).await;
    let row = listing(&app, id).await;
    assert_eq!(
        (row.keep_end.as_deref(), row.capped),
        (Some("first"), false)
    );
    assert_eq!(
        lib_entry(&library(&app, &token).await.0, id)["order"],
        "auto"
    );
    episodes.insert(0, "k_31".into());
    answer_pages(&app, "series", "kort", &episodes);
    answer_manifests(&app, "program", &episodes[..1]);
    check(&app, id, t0() + TimeDelta::hours(12)).await;
    let list = keys(&items(&app, id).await);
    assert_eq!(
        (list.len(), list[0].as_str(), list[30].as_str()),
        (31, "k_1", "k_31")
    );
}

/// `serie/<slug>/<programId>`: the manifest plus metadata walk along `_links.next` (the recorded
/// metadata's shape); one request when nothing is new; continues when there is a next.
#[tokio::test]
async fn a_programme_link_walks_the_metadata_chain() {
    let app = TestApp::new().await;
    let id = entry(
        &app,
        "https://radio.nrk.no/serie/radioteatret/MKTT72550116",
        "Teater",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    answer_root(&app, "series", "radioteatret", "Radioteatret", None);
    let recorded = fixture("psapi_metadata_program.json");
    app.fetch.answer(
        &format!("{PSAPI}/playback/metadata/program/MKTT72550116"),
        200,
        recorded,
    );
    app.fetch.answer(
        &manifest_url("program", "MKTT72550116"),
        200,
        fixture("psapi_manifest_program.json"),
    );
    let meta = |id: &str, next: Option<&str>| {
        let mut body = json!({"id": id, "duration": "PT30M", "preplay": {"titles": {"title": "Radioteatret", "subtitle": format!("Del {id}")}}});
        if let Some(next) = next {
            body["_links"] =
                json!({"next": {"href": format!("/playback/metadata/program/{next}")}});
        }
        body.to_string()
    };
    app.fetch.answer(
        &format!("{PSAPI}/playback/metadata/program/MKTT72550216"),
        200,
        meta("MKTT72550216", None),
    );
    answer_manifest(
        &app,
        "program",
        "MKTT72550216",
        "https://example-cdn.nrk.no/b.m3u8",
    );
    let report = check(&app, id, t0()).await;
    assert_eq!(report.requests, 5, "root + 2 × (manifest + metadata)");
    let list = items(&app, id).await;
    assert_eq!(keys(&list), ["MKTT72550116", "MKTT72550216"]);
    assert_eq!(list[0].title.as_deref(), Some("Et budskap fra de døde"));
    assert!(list[0].hls, "the recorded HLS manifest");
    assert!(list[1].hls, ".m3u8");
    assert_eq!(listing(&app, id).await.keep_end.as_deref(), Some("first"));

    app.fetch.clear_calls();
    let quiet = check(&app, id, t0() + TimeDelta::hours(12)).await;
    assert_eq!(quiet.requests, 1, "the last programme's metadata only");
    app.fetch.answer(
        &format!("{PSAPI}/playback/metadata/program/MKTT72550216"),
        200,
        meta("MKTT72550216", Some("MKTT72550316")),
    );
    app.fetch.answer(
        &format!("{PSAPI}/playback/metadata/program/MKTT72550316"),
        200,
        meta("MKTT72550316", None),
    );
    answer_manifest(&app, "program", "MKTT72550316", &audio("MKTT72550316"));
    let more = check(&app, id, t0() + TimeDelta::hours(24)).await;
    assert_eq!((more.new_items, more.requests), (1, 3));
    assert_eq!(items(&app, id).await.len(), 3);
}

// ------------------------------------------------------------------------------------------------
// RSS
// ------------------------------------------------------------------------------------------------

fn feed(items: &[(&str, &str)], newest_first: bool) -> String {
    let mut body = String::from(
        "<?xml version=\"1.0\"?><rss xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"><channel><title>Rull</title>",
    );
    let mut ordered: Vec<(usize, &(&str, &str))> = items.iter().enumerate().collect();
    if newest_first {
        ordered.reverse();
    }
    for (index, (guid, url)) in ordered {
        body.push_str(&format!(
            "<item><title>{guid}</title><guid>{guid}</guid><enclosure url=\"{url}\"/><pubDate>{}</pubDate></item>",
            (t0() + TimeDelta::days(index as i64)).to_rfc2822()
        ));
    }
    body.push_str("</channel></rss>");
    body
}

/// QA #9: a rolling window keeps the old items as `gone` (url null), a returning one is `ok`
/// again, new ones are appended as the newest; oldest-first feeds are read the right way up.
#[tokio::test]
async fn a_rolling_feed_is_merged_not_replaced() {
    let app = TestApp::new().await;
    let id = entry(
        &app,
        "https://example.org/rull.rss",
        "Rull",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    let url = "https://example.org/rull.rss";
    app.fetch.answer(
        url,
        200,
        feed(
            &[
                ("a", "https://example.org/a.mp3"),
                ("b", "https://example.org/b.mp3"),
                ("c", "https://example.org/c.mp3"),
            ],
            true,
        ),
    );
    check(&app, id, t0()).await;
    let key = |g: &str| crate::music_sources::short_sha1(g);
    assert_eq!(keys(&items(&app, id).await), [key("a"), key("b"), key("c")]);
    app.fetch.answer(
        url,
        200,
        feed(
            &[
                ("b", "https://example.org/b.mp3"),
                ("c", "https://example.org/c.mp3"),
                ("d", "https://example.org/d.mp3"),
            ],
            true,
        ),
    );
    check(&app, id, t0() + TimeDelta::hours(6)).await;
    let list = items(&app, id).await;
    assert_eq!(keys(&list), [key("a"), key("b"), key("c"), key("d")]);
    assert_eq!(states(&list), ["gone", "ok", "ok", "ok"]);
    let doc: Value =
        serde_json::from_slice(&sweep::build_listing(id, &list, false).unwrap().json).unwrap();
    assert_eq!(
        doc["items"][0]["url"],
        Value::Null,
        "gone: url null, still listed"
    );
    // "a" comes back.
    app.fetch.answer(
        url,
        200,
        feed(
            &[
                ("a", "https://example.org/a2.mp3"),
                ("b", "https://example.org/b.mp3"),
                ("c", "https://example.org/c.mp3"),
                ("d", "https://example.org/d.mp3"),
            ],
            true,
        ),
    );
    check(&app, id, t0() + TimeDelta::hours(12)).await;
    let list = items(&app, id).await;
    assert_eq!(states(&list), ["ok", "ok", "ok", "ok"]);
    assert_eq!(list[0].url.as_deref(), Some("https://example.org/a2.mp3"));

    // An oldest-first (serial) feed is stored oldest first too.
    let serial = entry(
        &app,
        "https://example.org/serial.rss",
        "Serial",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    app.fetch.answer(
        "https://example.org/serial.rss",
        200,
        feed(
            &[
                ("1", "https://example.org/1.mp3"),
                ("2", "https://example.org/2.mp3"),
                ("3", "https://example.org/3.mp3"),
            ],
            false,
        ),
    );
    check(&app, serial, t0()).await;
    assert_eq!(
        keys(&items(&app, serial).await),
        [key("1"), key("2"), key("3")]
    );
}

/// A 304, or a 200 with the same body, changes nothing; a list another parser built ignores the
/// stored validators; tracking prefixes are stripped from the phones' URL but not from the key.
#[tokio::test]
async fn unchanged_feeds_cost_one_request_and_nothing_else() {
    let app = TestApp::new().await;
    let id = entry(
        &app,
        "https://example.org/e.rss",
        "E",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    let body = feed(
        &[(
            "a",
            "https://dts.podtrac.com/redirect.mp3/example.org/a.mp3",
        )],
        true,
    );
    app.fetch.canned(
        "https://example.org/e.rss",
        Canned {
            status: 200,
            body: body.clone().into_bytes(),
            etag: Some("\"v1\"".into()),
            last_modified: None,
        },
    );
    check(&app, id, t0()).await;
    let first = items(&app, id).await;
    assert_eq!(first[0].url.as_deref(), Some("https://example.org/a.mp3"));
    assert_eq!(first[0].key, crate::music_sources::short_sha1("a"));
    let version = listing(&app, id).await.version;
    app.fetch.clear_calls();
    let report = check(&app, id, t0() + TimeDelta::hours(6)).await;
    assert_eq!(report.requests, 1);
    assert_eq!(
        app.fetch.conditional.lock().unwrap()[0].1.as_deref(),
        Some("\"v1\"")
    );
    assert_eq!(listing(&app, id).await.version, version);
    assert_eq!(
        listing(&app, id).await.listed_at.as_deref(),
        Some("2026-10-09 18:00:00")
    );
    // A list from an older parser: no validators, the body is parsed again.
    sqlx::query("UPDATE music_listings SET parser = 0 WHERE entry_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    app.fetch.clear_calls();
    check(&app, id, t0() + TimeDelta::hours(12)).await;
    assert_eq!(app.fetch.conditional.lock().unwrap()[0].1, None);
    assert_eq!(
        listing(&app, id).await.parser,
        Some(crate::music_sources::PARSER_VERSION)
    );
    // No validators at all: the body hash.
    let plain = entry(
        &app,
        "https://example.org/plain.rss",
        "Plain",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    app.fetch.answer("https://example.org/plain.rss", 200, body);
    check(&app, plain, t0()).await;
    sqlx::query("UPDATE music_items SET title = 'changed by hand' WHERE entry_id = ?")
        .bind(plain)
        .execute(&app.db)
        .await
        .unwrap();
    check(&app, plain, t0() + TimeDelta::hours(6)).await;
    assert_eq!(
        items(&app, plain).await[0].title.as_deref(),
        Some("changed by hand"),
        "not parsed again"
    );
}

// ------------------------------------------------------------------------------------------------
// Keep the last good list (§2.4)
// ------------------------------------------------------------------------------------------------

#[tokio::test]
async fn a_failing_source_keeps_the_last_good_list() {
    let app = TestApp::new().await;
    let (phone, _) = app.enrolled_device("Ella").await;
    let nrk = entry(
        &app,
        "https://radio.nrk.no/podkast/k",
        "K",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    let rss = entry(
        &app,
        "https://example.org/k.rss",
        "KR",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    tick_on(&app, phone, nrk).await;
    tick_on(&app, phone, rss).await;
    answer_root(&app, "podcast", "k", "K", None);
    let episodes = keys_n("k", 2);
    answer_pages(&app, "podcast", "k", &episodes);
    answer_manifests(&app, "podcast", &episodes);
    app.fetch.answer(
        "https://example.org/k.rss",
        200,
        feed(&[("a", "https://example.org/a.mp3")], true),
    );
    check(&app, nrk, t0()).await;
    check(&app, rss, t0()).await;
    let kept_nrk = (items(&app, nrk).await, listing(&app, nrk).await.version);
    let kept_rss = (items(&app, rss).await, listing(&app, rss).await.version);

    let mut when = t0() + TimeDelta::hours(12);
    let page = page_url("podcast", "k", "desc", 1);
    for (setup, code) in [
        (
            Box::new(|app: &TestApp| {
                app.fetch
                    .answer(&page_url("podcast", "k", "desc", 1), 503, "")
            }) as Box<dyn Fn(&TestApp)>,
            "http_503",
        ),
        (
            Box::new(|app: &TestApp| {
                app.fetch
                    .fail(&page_url("podcast", "k", "desc", 1), FetchError::Timeout)
            }),
            "timeout",
        ),
        (
            Box::new(|app: &TestApp| {
                app.fetch.fail(
                    &page_url("podcast", "k", "desc", 1),
                    FetchError::Network("reset".into()),
                )
            }),
            "network",
        ),
    ] {
        setup(&app);
        let report = check(&app, nrk, when).await;
        assert_eq!(report.error.as_deref(), Some(code));
        assert!(!report.changed && report.phones.is_empty(), "no nudge");
        assert_eq!(
            (items(&app, nrk).await, listing(&app, nrk).await.version),
            kept_nrk
        );
        when += TimeDelta::hours(1);
    }
    let row = listing(&app, nrk).await;
    assert_eq!(row.failures, 3);
    assert_eq!(row.failing_since.as_deref(), Some("2026-10-10 00:00:00"));
    let _ = page;

    for (body, status, code) in [
        ("".to_string(), 404, "http_404"),
        (
            "<html><title>Parked</title></html>".to_string(),
            200,
            "not_feed",
        ),
        (
            "<rss><channel><title>T</title><item><title>no audio</title></item></channel></rss>"
                .to_string(),
            200,
            "no_items",
        ),
        (
            format!(
                "<rss><channel><title>T</title>{}",
                "<item><title>x</title></item>".repeat(800_000)
            ),
            200,
            "too_big",
        ),
    ] {
        app.fetch.answer("https://example.org/k.rss", status, body);
        let report = check(&app, rss, when).await;
        assert_eq!(report.error.as_deref(), Some(code));
        assert_eq!(
            (items(&app, rss).await, listing(&app, rss).await.version),
            kept_rss
        );
        when += TimeDelta::hours(1);
    }
    // A success clears it all.
    app.fetch.answer(
        "https://example.org/k.rss",
        200,
        feed(&[("a", "https://example.org/a.mp3")], true),
    );
    check(&app, rss, when).await;
    let row = listing(&app, rss).await;
    assert_eq!(
        (row.error, row.failing_since, row.failures),
        (None, None, 0)
    );
}

// ------------------------------------------------------------------------------------------------
// Cadence (§2.2)
// ------------------------------------------------------------------------------------------------

/// The table for every setting, an entry no phone has once a day, the backoff capped at the
/// interval and daily after a week, a crash backing off like a failure, Check now during a check.
#[tokio::test]
async fn the_cadence_follows_the_setting() {
    for (setting, rss, nrk) in [(1, 1, 2), (3, 3, 6), (6, 6, 12), (12, 12, 12), (24, 24, 24)] {
        assert_eq!(sweep::interval("rss", true, setting), TimeDelta::hours(rss));
        assert_eq!(sweep::interval("nrk", true, setting), TimeDelta::hours(nrk));
        assert_eq!(sweep::interval("nrk", false, setting), TimeDelta::hours(24));
        assert_eq!(sweep::interval("rss", false, setting), TimeDelta::hours(24));
    }
    assert_eq!(
        sweep::backoff(1, TimeDelta::hours(12)),
        TimeDelta::minutes(15)
    );
    assert_eq!(sweep::backoff(2, TimeDelta::hours(12)), TimeDelta::hours(1));
    assert_eq!(sweep::backoff(3, TimeDelta::hours(12)), TimeDelta::hours(4));
    assert_eq!(
        sweep::backoff(4, TimeDelta::hours(12)),
        TimeDelta::hours(12),
        "capped at the interval"
    );
    assert_eq!(sweep::backoff(4, TimeDelta::hours(6)), TimeDelta::hours(6));

    let app = TestApp::new().await;
    let (phone, _) = app.enrolled_device("Ella").await;
    let ticked = entry(
        &app,
        "https://example.org/t.rss",
        "T",
        "auto",
        "2026-10-01 10:00:00",
    )
    .await;
    let unticked = entry(
        &app,
        "https://example.org/u.rss",
        "U",
        "auto",
        "2026-10-01 10:00:00",
    )
    .await;
    tick_on(&app, phone, ticked).await;
    for id in [ticked, unticked] {
        sqlx::query(
            "INSERT INTO music_listings (entry_id, version, listed_at, checked_at) \
             VALUES (?, '0123456789abcdef', '2026-10-09 12:00:00', '2026-10-09 12:00:00')",
        )
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    }
    let due_ids = |list: Vec<sweep::Scheduled>| list.iter().map(|s| s.entry_id).collect::<Vec<_>>();
    let early = sweep::due(&app.db, t0() + TimeDelta::minutes(359))
        .await
        .unwrap();
    assert!(
        early.is_empty(),
        "a fresh sweeper over recent stamps: nothing ({early:?})"
    );
    assert_eq!(
        due_ids(
            sweep::due(&app.db, t0() + TimeDelta::hours(6))
                .await
                .unwrap()
        ),
        vec![ticked]
    );
    assert_eq!(
        due_ids(
            sweep::due(&app.db, t0() + TimeDelta::hours(24))
                .await
                .unwrap()
        ),
        vec![ticked, unticked]
    );
    // A new setting re-times without a rewrite.
    sqlx::query("UPDATE music_settings SET sweep_hours = 1")
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        due_ids(
            sweep::due(&app.db, t0() + TimeDelta::hours(1))
                .await
                .unwrap()
        ),
        vec![ticked]
    );

    // A crash after the start stamp: due after the backoff, not again at once.
    sqlx::query("UPDATE music_listings SET failures = 1, checked_at = '2026-10-09 13:00:00' WHERE entry_id = ?")
        .bind(ticked)
        .execute(&app.db)
        .await
        .unwrap();
    assert!(
        due_ids(
            sweep::due(&app.db, at("2026-10-09 13:10:00"))
                .await
                .unwrap()
        )
        .is_empty()
    );
    assert_eq!(
        due_ids(
            sweep::due(&app.db, at("2026-10-09 13:15:00"))
                .await
                .unwrap()
        ),
        vec![ticked]
    );
    // Failing for a week: once a day.
    sqlx::query("UPDATE music_listings SET failures = 30, failing_since = '2026-10-01 13:00:00' WHERE entry_id = ?")
        .bind(ticked)
        .execute(&app.db)
        .await
        .unwrap();
    assert!(
        !due_ids(
            sweep::due(&app.db, at("2026-10-10 12:59:00"))
                .await
                .unwrap()
        )
        .contains(&ticked)
    );
    assert!(
        due_ids(
            sweep::due(&app.db, at("2026-10-10 13:00:00"))
                .await
                .unwrap()
        )
        .contains(&ticked)
    );
    // Check now pressed during a check (later than its start) runs again afterwards.
    sqlx::query(
        "UPDATE music_listings SET requested_at = '2026-10-09 13:00:01' WHERE entry_id = ?",
    )
    .bind(unticked)
    .execute(&app.db)
    .await
    .unwrap();
    let due = sweep::due(&app.db, at("2026-10-09 13:01:00"))
        .await
        .unwrap();
    assert_eq!(
        due.first().map(|s| (s.entry_id, s.why)),
        Some((unticked, sweep::Why::CheckNow))
    );
}

/// Check now first, then a single new entry before an import's backlog (newest first), then
/// rework, then the intervals.
#[tokio::test]
async fn the_most_urgent_entry_goes_first() {
    let app = TestApp::new().await;
    let mut imported = Vec::new();
    for i in 0..3 {
        imported.push(
            entry(
                &app,
                &format!("https://example.org/import{i}.rss"),
                "I",
                "auto",
                "2026-10-09 10:00:00",
            )
            .await,
        );
    }
    let added = entry(
        &app,
        "https://example.org/added.rss",
        "A",
        "auto",
        "2026-10-09 11:30:00",
    )
    .await;
    let old = entry(
        &app,
        "https://example.org/old.rss",
        "O",
        "auto",
        "2026-10-01 10:00:00",
    )
    .await;
    sqlx::query("INSERT INTO music_listings (entry_id, version, listed_at, checked_at, requested_at) VALUES (?, '0123456789abcdef', '2026-10-09 06:00:00', '2026-10-09 06:00:00', '2026-10-09 11:59:00')")
        .bind(old)
        .execute(&app.db)
        .await
        .unwrap();
    let due: Vec<(i64, sweep::Why)> = sweep::due(&app.db, t0())
        .await
        .unwrap()
        .iter()
        .map(|s| (s.entry_id, s.why))
        .collect();
    assert_eq!(due[0], (old, sweep::Why::CheckNow));
    assert_eq!(
        due[1],
        (added, sweep::Why::New),
        "a single add before the import's backlog"
    );
    assert_eq!(due.len(), 2 + imported.len());
}

/// A tick on the device card, an add, a saved setting and Check now wake the sweeper.
#[tokio::test]
async fn changes_wake_the_sweeper() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (phone, _) = app.enrolled_device("Ella").await;
    let id = entry(
        &app,
        "https://example.org/w.rss",
        "W",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    assert!(!app.state.music_sweep.woken().await);
    tick(&app, &cookie, phone, id, true).await;
    assert!(app.state.music_sweep.woken().await, "a tick");
    let res = app
        .request_form(
            Method::POST,
            "/music/sweep",
            Some(&cookie),
            &[("hours", "3")],
        )
        .await;
    assert_eq!(res.location(), Some("/music#sweep"));
    assert!(app.state.music_sweep.woken().await, "the setting");
    assert_eq!(events(&app, "music_sweep_saved").await, ["every 3 hours"]);
    assert_eq!(sweep::sweep_hours(&app.db).await.unwrap(), 3);
    let res = app
        .request_form(
            Method::POST,
            "/music/sweep",
            Some(&cookie),
            &[("hours", "2")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    app.fetch.answer(
        "https://example.org/new.rss",
        200,
        feed(&[("a", "https://example.org/a.mp3")], true),
    );
    let res = app
        .request_form(
            Method::POST,
            "/music",
            Some(&cookie),
            &[("link", "https://example.org/new.rss")],
        )
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    assert!(app.state.music_sweep.woken().await, "an add");
    let res = app
        .request_form(
            Method::POST,
            &format!("/music/entries/{id}/check"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.location(), Some(format!("/music#entry-{id}").as_str()));
    assert!(app.state.music_sweep.woken().await, "Check now");
    // Two wakes during a pass are kept as one.
    app.state.music_sweep.wake();
    app.state.music_sweep.wake();
    assert!(app.state.music_sweep.woken().await);
    assert!(!app.state.music_sweep.woken().await);
}

/// The coordinator's load numbers: a simulated day of 30 NRK podcasts and 10 feeds, all ticked and
/// listed, nothing new, stays within 21b's request counts at each setting.
async fn simulate_day(setting: i64) -> usize {
    let app = TestApp::new().await;
    let (phone, _) = app.enrolled_device("Ella").await;
    sqlx::query("UPDATE music_settings SET sweep_hours = ?")
        .bind(setting)
        .execute(&app.db)
        .await
        .unwrap();
    let mut ids = Vec::new();
    for n in 0..30 {
        let slug = format!("pod{n}");
        let id = entry(
            &app,
            &format!("https://radio.nrk.no/podkast/{slug}"),
            &slug,
            "auto",
            "2026-10-09 11:00:00",
        )
        .await;
        answer_root(&app, "podcast", &slug, &slug, None);
        let episodes = keys_n(&slug, 1);
        answer_pages(&app, "podcast", &slug, &episodes);
        answer_manifests(&app, "podcast", &episodes);
        ids.push(id);
    }
    for n in 0..10 {
        let url = format!("https://feeds.example.org/{n}.rss");
        let id = entry(&app, &url, "F", "auto", "2026-10-09 11:00:00").await;
        app.fetch.canned(
            &url,
            Canned {
                status: 200,
                body: feed(&[("a", "https://example.org/a.mp3")], true).into_bytes(),
                etag: Some(format!("\"{n}\"")),
                last_modified: None,
            },
        );
        ids.push(id);
    }
    for id in &ids {
        tick_on(&app, phone, *id).await;
        check(&app, *id, t0()).await;
    }
    app.fetch.clear_calls();
    let end = t0() + TimeDelta::hours(24);
    let mut now = t0();
    loop {
        let next = sweep::schedule(&app.db, now)
            .await
            .unwrap()
            .iter()
            .map(|s| s.at)
            .min()
            .unwrap();
        if next > end {
            break;
        }
        now = next.max(now);
        for due in sweep::due(&app.db, now).await.unwrap() {
            check(&app, due.entry_id, now).await;
        }
    }
    app.fetch.count()
}

#[tokio::test]
async fn a_day_at_1_hour_stays_within_600_requests() {
    assert_eq!(simulate_day(1).await, 600);
}

#[tokio::test]
async fn a_day_at_3_hours_stays_within_200_requests() {
    assert_eq!(simulate_day(3).await, 200);
}

#[tokio::test]
async fn a_day_at_6_hours_stays_within_100_requests() {
    assert_eq!(simulate_day(6).await, 100);
}

#[tokio::test]
async fn a_day_at_12_hours_stays_within_80_requests() {
    assert_eq!(simulate_day(12).await, 80);
}

#[tokio::test]
async fn a_day_at_24_hours_stays_within_40_requests() {
    assert_eq!(simulate_day(24).await, 40);
}

/// The breaker: 3 network failures from psapi in a pass defer its remaining entries (no failure
/// counted for them, the host paused); the feeds go on.
#[tokio::test]
async fn an_nrk_outage_costs_three_requests_not_thirty() {
    let app = TestApp::new().await;
    app.state
        .music_sweep
        .set_limits(sweep::MAX_CHECK_REQUESTS, std::time::Duration::ZERO);
    let mut nrk = Vec::new();
    for n in 0..6 {
        nrk.push(
            entry(
                &app,
                &format!("https://radio.nrk.no/podkast/down{n}"),
                "D",
                "auto",
                "2026-10-09 11:00:00",
            )
            .await,
        );
    }
    let rss = entry(
        &app,
        "https://example.org/up.rss",
        "Up",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    app.fetch.answer(
        "https://example.org/up.rss",
        200,
        feed(&[("a", "https://example.org/a.mp3")], true),
    );
    // Nothing answers for psapi: every request is a network error.
    assert!(sweep::run_pass(&app.state).await);
    let calls = app.fetch.calls.lock().unwrap().clone();
    assert_eq!(calls.iter().filter(|u| u.starts_with(PSAPI)).count(), 3);
    assert!(calls.contains(&"https://example.org/up.rss".to_string()));
    let failed: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM music_listings WHERE failures > 0")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(failed, 3, "the deferred entries aren't failures");
    assert!(listing(&app, rss).await.version.is_some());
    // The next pass leaves psapi alone while it is paused.
    app.fetch.clear_calls();
    sweep::run_pass(&app.state).await;
    assert!(
        app.fetch
            .calls
            .lock()
            .unwrap()
            .iter()
            .all(|u| !u.starts_with(PSAPI))
    );
}

/// An entry deleted while it is checked: nothing is written.
#[tokio::test]
async fn an_entry_deleted_mid_check_writes_nothing() {
    let app = TestApp::new().await;
    let id = entry(
        &app,
        "https://example.org/gone.rss",
        "G",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    app.fetch.answer(
        "https://example.org/gone.rss",
        200,
        feed(&[("a", "https://example.org/a.mp3")], true),
    );
    app.fetch.delay(100);
    let state = app.state.clone();
    let task = tokio::spawn(async move {
        sweep::check_entry(&state, id, t0(), &mut Breaker::default())
            .await
            .unwrap()
    });
    tokio::time::sleep(std::time::Duration::from_millis(40)).await;
    sqlx::query("DELETE FROM music_entries WHERE id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    assert!(task.await.unwrap().is_none());
    let rows: i64 = sqlx::query_scalar(
        "SELECT (SELECT COUNT(*) FROM music_listings) + (SELECT COUNT(*) FROM music_items)",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(rows, 0);
}

/// One request in flight server-wide: a sweep check and an add check share the gate, and the add
/// waits for one request, not for the whole fill.
#[tokio::test]
async fn an_add_check_waits_for_one_request_not_a_fill() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let id = entry(
        &app,
        "https://radio.nrk.no/podkast/fill",
        "Fill",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    answer_root(&app, "podcast", "fill", "Fill", None);
    let episodes = keys_n("f", 20);
    answer_pages(&app, "podcast", "fill", &episodes);
    answer_manifests(&app, "podcast", &episodes);
    app.fetch.answer(
        "https://example.org/added.rss",
        200,
        feed(&[("a", "https://example.org/a.mp3")], true),
    );
    app.fetch.delay(15);
    let state = app.state.clone();
    let fill = tokio::spawn(async move {
        sweep::check_entry(&state, id, t0(), &mut Breaker::default())
            .await
            .unwrap()
    });
    tokio::time::sleep(std::time::Duration::from_millis(40)).await;
    let res = app
        .request_form(
            Method::POST,
            "/music",
            Some(&cookie),
            &[("link", "https://example.org/added.rss")],
        )
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    fill.await.unwrap();
    let calls = app.fetch.calls.lock().unwrap().clone();
    let add_at = calls
        .iter()
        .position(|u| u == "https://example.org/added.rss")
        .unwrap();
    assert!(
        add_at < calls.len() - 10,
        "the add went in between the fill's requests: {add_at} of {}",
        calls.len()
    );
    assert_eq!(
        app.fetch
            .most_in_flight
            .load(std::sync::atomic::Ordering::SeqCst),
        1
    );
}

// ------------------------------------------------------------------------------------------------
// Addresses (§2.7)
// ------------------------------------------------------------------------------------------------

/// A feed on the LAN works and is fixed as `lan`; a public one that later resolves privately (a
/// lapsed domain, DNS rebinding; mapped IPv4 forms too) is refused; a public feed's media on
/// private hosts is dropped and its private image never fetched.
#[tokio::test]
async fn public_feeds_never_reach_private_addresses() {
    let app = TestApp::new().await;
    let lan = entry(
        &app,
        "http://nas.home/feed.rss",
        "NAS",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    app.fetch.dns("nas.home", &["192.168.1.10"]);
    app.fetch.answer(
        "http://nas.home/feed.rss",
        200,
        feed(&[("a", "http://nas.home/a.mp3")], true),
    );
    check(&app, lan, t0()).await;
    let row = listing(&app, lan).await;
    assert_eq!(row.lan, Some(true));
    assert_eq!(
        items(&app, lan).await[0].url.as_deref(),
        Some("http://nas.home/a.mp3"),
        "a LAN feed's own media"
    );

    let public = entry(
        &app,
        "https://pod.example.com/feed.rss",
        "Pub",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    app.fetch.dns("cdn.inside", &["10.0.0.5"]);
    let body = "<rss><channel><title>Pub</title><itunes:image href=\"http://10.0.0.9/cover.jpg\"/>\
        <item><guid>1</guid><enclosure url=\"https://cdn.example.com/1.mp3\"/><itunes:image href=\"http://cdn.inside/1.jpg\"/></item>\
        <item><guid>2</guid><enclosure url=\"http://192.168.1.1/2.mp3\"/></item>\
        <item><guid>3</guid><enclosure url=\"http://cdn.inside/3.mp3\"/></item>\
        <item><guid>4</guid><enclosure url=\"file:///etc/passwd\"/></item>\
        <item><guid>5</guid><enclosure url=\"https://user:pw@cdn.example.com/5.mp3\"/></item>\
        <item><guid>6</guid><enclosure url=\"http://[::ffff:192.168.1.1]/6.mp3\"/></item>\
        </channel></rss>";
    app.fetch
        .answer("https://pod.example.com/feed.rss", 200, body);
    check(&app, public, t0()).await;
    assert_eq!(listing(&app, public).await.lan, Some(false));
    let list = items(&app, public).await;
    // Undated: newest first in the document, stored oldest first (6 .. 1).
    let urls: Vec<Option<&str>> = list.iter().map(|i| i.url.as_deref()).collect();
    assert_eq!(
        urls,
        [
            None,
            None,
            None,
            None,
            None,
            Some("https://cdn.example.com/1.mp3")
        ]
    );
    assert_eq!(list.iter().filter(|i| i.state == "ok").count(), 1);
    assert!(
        list.iter().all(|i| i.art_url.is_none()),
        "the private art is dropped"
    );
    assert!(
        !app.fetch
            .calls
            .lock()
            .unwrap()
            .iter()
            .any(|u| u.contains("10.0.0.9")),
        "a private cover is never fetched"
    );

    // The domain now points at the LAN: refused, the list kept, the class unchanged.
    for private in [
        "192.168.1.1",
        "::ffff:10.0.0.1",
        "fd7a:115c:a1e0::1",
        "100.64.0.1",
        "127.0.0.1",
    ] {
        app.fetch.dns("pod.example.com", &[private]);
        let report = check(&app, public, t0() + TimeDelta::hours(6)).await;
        assert_eq!(report.error.as_deref(), Some("network"), "{private}");
        assert_eq!(listing(&app, public).await.lan, Some(false));
        assert_eq!(items(&app, public).await, list);
    }
}

// ------------------------------------------------------------------------------------------------
// Covers (§2.6)
// ------------------------------------------------------------------------------------------------

#[tokio::test]
async fn source_covers_are_fetched_once_and_kept() {
    let app = TestApp::new().await;
    let (phone, token) = app.enrolled_device("Ella").await;
    let id = entry(
        &app,
        "https://radio.nrk.no/podkast/cov",
        "Cov",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    tick_on(&app, phone, id).await;
    answer_root(
        &app,
        "podcast",
        "cov",
        "Cov",
        Some("https://gfx.nrk.no/one"),
    );
    let episodes = keys_n("cv", 1);
    answer_pages(&app, "podcast", "cov", &episodes);
    answer_manifests(&app, "podcast", &episodes);
    app.fetch.answer("https://gfx.nrk.no/one", 200, jpeg(10));
    app.fetch.answer("https://gfx.nrk.no/two", 200, jpeg(200));
    check(&app, id, t0()).await;
    let first = listing(&app, id).await.cover_hash.unwrap();
    let (lib, _) = library(&app, &token).await;
    assert_eq!(
        lib_entry(&lib, id)["cover"],
        first.as_str(),
        "the source's cover"
    );
    let res = device_get(
        &app,
        &format!("/api/devices/music/covers/{first}"),
        &token,
        &[],
    )
    .await;
    assert_eq!(res.status, StatusCode::OK);
    // Kept by a prune.
    crate::photos::MUSIC_COVERS
        .prune(&app.db, &app.state.music_cover_dir)
        .await;
    assert!(
        app.state
            .music_cover_dir
            .join(format!("{first}.jpg"))
            .exists()
    );

    // A new image at the weekly root read: fetched (it changed), at most once a day.
    answer_root(
        &app,
        "podcast",
        "cov",
        "Cov",
        Some("https://gfx.nrk.no/two"),
    );
    app.fetch.clear_calls();
    check(&app, id, t0() + TimeDelta::hours(12)).await;
    assert!(
        !app.fetch
            .calls
            .lock()
            .unwrap()
            .iter()
            .any(|u| u.ends_with("/radio/catalog/podcast/cov")),
        "no root read before a week"
    );
    check(&app, id, t0() + TimeDelta::days(8)).await;
    let second = listing(&app, id).await.cover_hash.unwrap();
    assert_ne!(second, first);
    assert!(
        !app.state
            .music_cover_dir
            .join(format!("{first}.jpg"))
            .exists(),
        "the old one is pruned"
    );

    // A lost cover is fetched again at the next check.
    std::fs::remove_file(app.state.music_cover_dir.join(format!("{second}.jpg"))).unwrap();
    crate::photos::MUSIC_COVERS
        .recover_missing(
            &app.db,
            &app.state.music_cover_dir,
            std::path::Path::new("/nonexistent"),
        )
        .await;
    assert_eq!(listing(&app, id).await.cover_hash, None);
    app.fetch.clear_calls();
    check(&app, id, t0() + TimeDelta::days(10)).await;
    assert_eq!(listing(&app, id).await.cover_hash, Some(second.clone()));

    // The parent's own cover wins.
    sqlx::query("UPDATE music_entries SET cover_hash = ? WHERE id = ?")
        .bind("d".repeat(64))
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let (lib, _) = library(&app, &token).await;
    assert_eq!(lib_entry(&lib, id)["cover"], "d".repeat(64));
}

// ------------------------------------------------------------------------------------------------
// Rechecks (§2.5)
// ------------------------------------------------------------------------------------------------

async fn report_errors(app: &TestApp, token: &str, errors: Value) {
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(token),
            Some(json!({
                "lock_reason": "NONE",
                "kiosk_engaged": true,
                "capabilities": ["music_v1"],
                "music_state": {"item_errors": errors}
            })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
}

async fn flags(app: &TestApp, entry: i64) -> Vec<String> {
    sqlx::query_scalar(
        "SELECT key FROM music_items WHERE entry_id = ? AND recheck = 1 ORDER BY seq",
    )
    .bind(entry)
    .fetch_all(&app.db)
    .await
    .unwrap()
}

#[tokio::test]
async fn failing_urls_reported_by_a_phone_are_re_resolved() {
    let app = TestApp::new().await;
    let (phone, token) = app.enrolled_device("Ella").await;
    let (_, stranger) = app.enrolled_device("Not ticked").await;
    let id = entry(
        &app,
        "https://radio.nrk.no/podkast/r",
        "R",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    tick_on(&app, phone, id).await;
    answer_root(&app, "podcast", "r", "R", None);
    let episodes = keys_n("r", 4);
    answer_pages(&app, "podcast", "r", &episodes);
    answer_manifests(&app, "podcast", &episodes);
    check(&app, id, t0()).await;
    let version = listing(&app, id).await.version.unwrap();

    // Only 401/403/404/410, for the current list, from a phone with the entry.
    report_errors(
        &app,
        &token,
        json!([
            {"entry": id, "version": version, "item": "r_1", "error": "network"},
            {"entry": id, "version": version, "item": "r_2", "error": "bad_media"},
            {"entry": id, "version": "0000000000000000", "item": "r_3", "error": "http_404"},
            {"entry": id, "version": version, "item": "nope", "error": "http_404"},
        ]),
    )
    .await;
    report_errors(
        &app,
        &stranger,
        json!([{"entry": id, "version": version, "item": "r_1", "error": "http_403"}]),
    )
    .await;
    assert!(flags(&app, id).await.is_empty());
    assert!(!app.state.music_sweep.woken().await);
    report_errors(
        &app,
        &token,
        json!([{"entry": id, "version": version, "item": "r_4", "error": "http_403"}]),
    )
    .await;
    assert_eq!(flags(&app, id).await, ["r_4"]);
    assert!(
        app.state.music_sweep.woken().await,
        "a flag wakes the sweeper"
    );
    let due = sweep::due(&app.db, t0() + TimeDelta::minutes(5))
        .await
        .unwrap();
    assert!(due[0].recheck_only, "only the flag: no listing part");

    // The re-resolve: a renewed URL is stored, moves the version, the flag is cleared.
    answer_manifest(
        &app,
        "podcast",
        "r_4",
        "https://podkast.nrk.no/fil/r_4-renewed.mp3",
    );
    app.fetch.clear_calls();
    let report = check(&app, id, t0() + TimeDelta::minutes(5)).await;
    assert_eq!(
        app.fetch.calls.lock().unwrap().as_slice(),
        [manifest_url("podcast", "r_4")]
    );
    assert!(report.changed);
    assert_eq!(report.phones, vec![phone]);
    let row = listing(&app, id).await;
    assert_eq!(row.failures, 0, "a recheck restores failures");
    assert_eq!(
        row.listed_at.as_deref(),
        Some("2026-10-09 12:00:00"),
        "no listing part"
    );
    let list = items(&app, id).await;
    assert_eq!(
        list[3].url.as_deref(),
        Some("https://podkast.nrk.no/fil/r_4-renewed.mp3")
    );
    assert!(flags(&app, id).await.is_empty());
    // One re-resolve per item a day.
    let version = row.version.unwrap();
    report_errors(
        &app,
        &token,
        json!([{"entry": id, "version": version, "item": "r_4", "error": "http_404"}]),
    )
    .await;
    assert!(flags(&app, id).await.is_empty());

    // Two items within a day: the whole entry, once a day; the same URL changes nothing, no
    // playable makes it gone.
    report_errors(
        &app,
        &token,
        json!([
            {"entry": id, "version": version, "item": "r_1", "error": "http_410"},
            {"entry": id, "version": version, "item": "r_2", "error": "http_401"},
        ]),
    )
    .await;
    app.fetch.answer(
        &manifest_url("podcast", "r_1"),
        200,
        r#"{"playability": "nonPlayable", "nonPlayable": {"reason": "rightsexpired"}}"#,
    );
    app.fetch.clear_calls();
    let report = check(&app, id, t0() + TimeDelta::minutes(10)).await;
    assert_eq!(
        app.fetch.calls.lock().unwrap().len(),
        4,
        "every item of the entry"
    );
    assert!(listing(&app, id).await.full_recheck_at.is_some());
    let list = items(&app, id).await;
    assert_eq!(states(&list), ["gone", "ok", "ok", "ok"]);
    assert!(report.changed);
    assert!(
        flags(&app, id).await.is_empty(),
        "cleared after any attempt"
    );
}

/// Expiry re-resolves first: NRK extended the rights -> a new `available_until`; else gone.
/// Past the server-wide budget, flags wait for the next day.
#[tokio::test]
async fn expired_items_are_asked_about_before_they_go() {
    let app = TestApp::new().await;
    let id = entry(
        &app,
        "https://radio.nrk.no/podkast/e",
        "E",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    answer_root(&app, "podcast", "e", "E", None);
    let episodes = keys_n("e", 2);
    answer_pages(&app, "podcast", "e", &episodes);
    answer_manifests(&app, "podcast", &episodes);
    check(&app, id, t0()).await;
    sqlx::query(
        "UPDATE music_items SET available_until = '2026-10-09 13:00:00' WHERE entry_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    app.fetch.answer(
        &manifest_url("podcast", "e_1"),
        200,
        r#"{"playability": "nonPlayable"}"#,
    );
    check(&app, id, t0() + TimeDelta::hours(12)).await;
    let list = items(&app, id).await;
    assert_eq!(states(&list), ["gone", "ok"]);
    assert_eq!(list[1].available_until, None, "extended for good");

    // The budget: 300 manifests a day server-wide, counting this entry's own.
    for n in 0..300 {
        sqlx::query(
            "INSERT INTO music_items (entry_id, key, seq, state, first_seen_at, rechecked_at) \
             VALUES (?, ?, ?, 'gone', '2026-10-09 12:00:00', '2026-10-10 00:30:00')",
        )
        .bind(id)
        .bind(format!("filler{n}"))
        .bind(100 + n)
        .execute(&app.db)
        .await
        .unwrap();
    }
    sqlx::query("UPDATE music_items SET recheck = 1, reported_at = '2026-10-10 01:00:00' WHERE entry_id = ? AND key = 'e_2'")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let due = sweep::due(&app.db, at("2026-10-10 01:00:00"))
        .await
        .unwrap();
    assert!(due.is_empty(), "the flag waits for the budget");
    let due = sweep::due(&app.db, at("2026-10-11 01:00:00"))
        .await
        .unwrap();
    assert_eq!(due.first().map(|s| s.entry_id), Some(id));
}

// ------------------------------------------------------------------------------------------------
// The listing route and the library (§4)
// ------------------------------------------------------------------------------------------------

/// `server/testdata/music_listing.json` pins the listing's shape (the music app keeps a
/// byte-identical copy). `MUSIC_LISTING_SNAPSHOT_WRITE=1` rewrites it.
#[tokio::test]
async fn music_listing_snapshot() {
    let app = TestApp::new().await;
    let (phone, token) = app.enrolled_device("Ella").await;
    let (_, stranger) = app.enrolled_device("Max").await;
    let id = entry(
        &app,
        "https://example.org/snap.rss",
        "Snap",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    tick_on(&app, phone, id).await;
    let uri = format!("/api/devices/music/entries/{id}/items");
    assert_eq!(
        device_get(&app, &uri, &token, &[]).await.status,
        StatusCode::NOT_FOUND,
        "never listed"
    );
    app.fetch.answer(
        "https://example.org/snap.rss",
        200,
        "<rss xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"><channel><title>Snap</title>\
         <item><title>Gammel</title><guid>old</guid><enclosure url=\"https://example.org/old.mp3\"/><pubDate>Mon, 05 Oct 2026 06:00:00 GMT</pubDate></item>\
         </channel></rss>",
    );
    check(&app, id, t0()).await;
    app.fetch.answer(
        "https://example.org/snap.rss",
        200,
        "<rss xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"><channel><title>Snap</title>\
         <item><title>Ny episode</title><guid>new</guid><enclosure url=\"https://cdn.example.org/ny.m3u8\"/>\
         <itunes:duration>1:02:03</itunes:duration><itunes:image href=\"https://example.org/ny.jpg\"/>\
         <pubDate>Fri, 09 Oct 2026 06:00:00 GMT</pubDate></item>\
         </channel></rss>",
    );
    check(&app, id, t0() + TimeDelta::hours(6)).await;
    let res = device_get(&app, &uri, &token, &[]).await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.headers[header::CONTENT_TYPE], "application/json");
    let version = listing(&app, id).await.version.unwrap();
    assert_eq!(res.headers[header::ETAG], format!("\"{version}\"").as_str());
    let pretty = pretty_json(&res.text());
    let path = "testdata/music_listing.json";
    if std::env::var("MUSIC_LISTING_SNAPSHOT_WRITE").is_ok() {
        std::fs::write(path, &pretty).unwrap();
    }
    assert_eq!(
        pretty,
        std::fs::read_to_string(path).unwrap(),
        "the listing changed - update both copies together"
    );
    let not_modified = device_get(
        &app,
        &uri,
        &token,
        &[("if-none-match", &format!("\"{version}\""))],
    )
    .await;
    assert_eq!(not_modified.status, StatusCode::NOT_MODIFIED);
    let gzipped = device_get(&app, &uri, &token, &[("accept-encoding", "gzip")]).await;
    assert_eq!(gzipped.headers[header::CONTENT_ENCODING], "gzip");
    // Scoped to the phone's ticks.
    assert_eq!(
        device_get(&app, &uri, &stranger, &[]).await.status,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        device_get(&app, "/api/devices/music/entries/999/items", &token, &[])
            .await
            .status,
        StatusCode::NOT_FOUND
    );
}

/// The 1 MB cut: oldest first for a list that keeps the newest, gone ones before the rest, the
/// newest kept for one anchored at its start.
#[test]
fn a_listing_past_1_mb_is_cut_from_the_end_opposite_its_anchor() {
    let now = t0();
    let item = |n: usize, state: &str| sweep::Item {
        key: format!("k{n}"),
        state: state.to_string(),
        title: Some("x".repeat(900)),
        url: Some(format!("https://example.org/{n}.mp3")),
        hls: false,
        duration_ms: None,
        art_url: None,
        published_at: None,
        available_until: None,
        first_seen_at: stamp(now),
        attempts: 0,
        reported_at: None,
        rechecked_at: None,
        recheck: false,
    };
    let mut list: Vec<sweep::Item> = (0..1200).map(|n| item(n, "ok")).collect();
    list[600].state = "gone".into();
    let built = sweep::build_listing(1, &list, false).unwrap();
    assert!(built.cut && built.json.len() <= sweep::MAX_LISTING_BYTES);
    let doc: Value = serde_json::from_slice(&built.json).unwrap();
    let kept: Vec<&str> = doc["items"]
        .as_array()
        .unwrap()
        .iter()
        .map(|i| i["key"].as_str().unwrap())
        .collect();
    assert_eq!(kept.last(), Some(&"k1199"), "the newest stay");
    assert!(!kept.contains(&"k600"), "gone ones go first");
    assert!(!kept.contains(&"k0"));
    let anchored = sweep::build_listing(1, &list, true).unwrap();
    let doc: Value = serde_json::from_slice(&anchored.json).unwrap();
    assert_eq!(doc["items"][0]["key"], "k0", "anchored at the start");
    assert_eq!(anchored.count, doc["items"].as_array().unwrap().len());
    // Same rows, same version.
    assert_eq!(
        sweep::build_listing(1, &list, false).unwrap().version,
        built.version
    );
}

/// `tick_fits` adds up the listings: past 32 MB a tick is refused with its own notice.
#[tokio::test]
async fn ticks_past_the_listing_budget_are_refused() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (phone, _) = app.enrolled_device("Ella").await;
    let big = entry(
        &app,
        "https://example.org/big.rss",
        "Big",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    let more = entry(
        &app,
        "https://example.org/more.rss",
        "More",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    sqlx::query("INSERT INTO music_listings (entry_id, version, bytes) VALUES (?, '0123456789abcdef', 31000000), (?, '0123456789abcdee', 2000000)")
        .bind(big)
        .bind(more)
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        tick(&app, &cookie, phone, big, true).await.status,
        StatusCode::SEE_OTHER
    );
    let refused = tick(&app, &cookie, phone, more, true).await;
    assert_eq!(
        refused.location(),
        Some(
            format!(
                "/devices/{phone}?music_notice=lists_too_big&music_entry={more}#music-entry-{more}"
            )
            .as_str()
        )
    );
    let page = app
        .get_page(
            &format!("/devices/{phone}?music_notice=lists_too_big&music_entry={more}"),
            &cookie,
        )
        .await;
    assert!(
        page.text()
            .contains("episode lists this phone gets would pass 32 MB")
    );
}

// ------------------------------------------------------------------------------------------------
// The PWA (§3)
// ------------------------------------------------------------------------------------------------

/// Every card state and the per-phone lines; Check now's limits and redirects; the offline
/// select; the cards route.
#[tokio::test]
async fn the_cards_say_what_the_sweep_knows() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (ella, _) = app.enrolled_device("Ella").await;
    let (max, max_token) = app.enrolled_device("Max").await;
    let (_ida, _) = app.enrolled_device("Ida").await;
    let ok = entry(
        &app,
        "https://radio.nrk.no/serie/lang",
        "Lang serie",
        "oldest_first",
        "2026-10-01 10:00:00",
    )
    .await;
    let failing = entry(
        &app,
        "https://example.org/fail.rss",
        "Feilende",
        "auto",
        "2026-10-01 10:00:00",
    )
    .await;
    let never = entry(
        &app,
        "https://radio.nrk.no/podkast/aldri",
        "Aldri",
        "auto",
        "2026-10-01 10:00:00",
    )
    .await;
    let waiting = entry(
        &app,
        "https://example.org/wait.rss",
        "Venter",
        "auto",
        "2026-10-09 11:00:00",
    )
    .await;
    let now = Utc::now();
    let recent = stamp(now - TimeDelta::hours(2));
    sqlx::query(
        "INSERT INTO music_listings (entry_id, title, lan, capped, cut, keep_end, item_count, version, \
           listed_at, checked_at) VALUES (?, 'Radioteatret', 0, 1, 1, 'first', 98, '0123456789abcdef', ?, ?)",
    )
    .bind(ok)
    .bind(&recent)
    .bind(&recent)
    .execute(&app.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO music_items (entry_id, key, seq, state, title, url, first_seen_at, published_at) \
         VALUES (?, 'a', 0, 'ok', 'Første', 'https://x/a.mp3', ?, '2026-10-08 06:00:00'), \
                (?, 'b', 1, 'ok', 'Andre del', 'https://x/b.mp3', ?, '2026-10-09 06:00:00')",
    )
    .bind(ok)
    .bind(&recent)
    .bind(ok)
    .bind(&recent)
    .execute(&app.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO music_listings (entry_id, lan, version, listed_at, checked_at, error, error_at, \
           failing_since, failures) VALUES (?, 1, '1111111111111111', '2026-10-08 10:00:00', ?, \
           'http_404', ?, ?, 1)",
    )
    .bind(failing)
    .bind(&recent)
    .bind(&recent)
    .bind(&recent)
    .execute(&app.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO music_listings (entry_id, checked_at, error, error_at, failing_since, failures) \
         VALUES (?, ?, 'not_found', ?, ?, 1)",
    )
    .bind(never)
    .bind(&recent)
    .bind(&recent)
    .bind(&recent)
    .execute(&app.db)
    .await
    .unwrap();
    for (phone, entry_id) in [(ella, ok), (max, ok), (_ida, ok), (ella, failing)] {
        tick_on(&app, phone, entry_id).await;
    }
    // Max reports: an older list; Ella: 5 of 5 (her report arrives below as Ella's token isn't
    // kept - set straight in the table).
    sqlx::query(
        "INSERT INTO device_status (device_id, lock_reason, kiosk_engaged, music_state_json, reported_at) \
         VALUES (?, 'NONE', 1, ?, datetime('now'))",
    )
    .bind(ella)
    .bind(json!({"downloads": [{"entry": ok, "version": "0123456789abcdef", "have": 2, "want": 5, "waiting": "wifi"}],
                 "item_errors": [{"entry": ok, "version": "0123456789abcdef", "item": "a", "error": "http_403"}]}).to_string())
    .execute(&app.db)
    .await
    .unwrap();
    let _ = max_token;
    sqlx::query(
        "INSERT INTO device_status (device_id, lock_reason, kiosk_engaged, music_state_json, reported_at) \
         VALUES (?, 'NONE', 1, ?, '2026-10-01 08:00:00')",
    )
    .bind(max)
    .bind(json!({"downloads": [{"entry": ok, "version": "ffffffffffffffff", "have": 1, "want": 5}]}).to_string())
    .execute(&app.db)
    .await
    .unwrap();

    let page = app.get_page("/music", &cookie).await.text();
    assert!(page.contains("Check for new episodes"));
    assert!(page.contains("6 hours is what the Vibb Pi uses (NRK podcasts every 12 hours)"));
    assert!(
        page.contains(
            "A public feed can never send the server or the phones to a private address."
        )
    );
    assert!(page.contains("value=\"6\" selected"));
    // The listed, capped, cut series.
    assert!(page.contains("Radioteatret"), "the source's own title");
    assert!(page.contains("98 episodes · newest: Andre del · 9 Oct"));
    assert!(page.contains("first 100 episodes (the series has more)"));
    assert!(page.contains("list cut to the first 98 (size limit)"));
    assert!(page.contains("Checked 2 h ago"));
    assert!(page.contains(
        "Ella: 2 of 5 offline · waiting for Wi-Fi · 1 failing (the source answered 403)"
    ));
    assert!(page.contains("Max: has an older list (as of 1 Oct 08:00 UTC)"));
    assert!(page.contains("Ida: not reported yet"));
    assert!(
        page.contains("Ella: nothing on it reported yet"),
        "a report without the entry"
    );
    // The failing feed on the LAN, and the one never listed.
    assert!(page.contains(", home network"));
    assert!(page.contains("Last check failed"));
    assert!(
        page.contains("the source answered 404. The phones keep the list from 8 Oct. Next try")
    );
    let unescaped = page.replace("&#x27;", "'").replace("&#39;", "'");
    assert!(
        unescaped.contains(
            "Couldn't list it: NRK doesn't know that podcast or series - check the link. Next try"
        ),
        "{}",
        &unescaped[unescaped.find("Aldri").unwrap_or(0)..]
            [..1500.min(unescaped.len() - unescaped.find("Aldri").unwrap_or(0))]
    );
    // Waiting, with what is ahead (the never-checked one is due; the failing ones aren't yet).
    assert!(page.contains("Waiting for the first check"));
    assert!(page.contains("data-busy=\"1\""));
    assert!(page.contains("/static/music-cards.js"));

    // Checking.
    let res = app
        .get_page(&format!("/music/cards?ids={waiting},{ok}"), &cookie)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    let cards = res.json();
    assert_eq!(cards["cards"].as_array().unwrap().len(), 2);
    assert_eq!(cards["cards"][0]["busy"], true);
    assert!(
        cards["cards"][0]["html"]
            .as_str()
            .unwrap()
            .contains("class=\"music-status music-status-busy\"")
    );
    let too_many = (0..201)
        .map(|n| n.to_string())
        .collect::<Vec<_>>()
        .join(",");
    assert_eq!(
        app.get_page(&format!("/music/cards?ids={too_many}"), &cookie)
            .await
            .status,
        StatusCode::BAD_REQUEST
    );

    // Check now: from the card and the entry page, then the per-entry limit.
    let res = app
        .request_form(
            Method::POST,
            &format!("/music/entries/{ok}/check"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.location(), Some(format!("/music#entry-{ok}").as_str()));
    let res = app
        .request_form(
            Method::POST,
            &format!("/music/entries/{failing}/check"),
            Some(&cookie),
            &[("back", "entry")],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/music/entries/{failing}#status").as_str())
    );
    let page = app.get_page("/music", &cookie).await.text();
    assert!(page.contains("Check again after"));
    assert_eq!(events(&app, "music_check_requested").await.len(), 2);
    app.request_form(
        Method::POST,
        &format!("/music/entries/{ok}/check"),
        Some(&cookie),
        &[],
    )
    .await;
    assert_eq!(
        events(&app, "music_check_requested").await.len(),
        2,
        "15 minutes per entry"
    );
    let entry_page = app
        .get_page(&format!("/music/entries/{failing}"), &cookie)
        .await
        .text();
    assert!(entry_page.contains("id=\"status\"") && entry_page.contains("Check again after"));

    // The offline select.
    let res = app
        .request_form(
            Method::POST,
            &format!("/music/entries/{ok}/offline"),
            Some(&cookie),
            &[("cache", "10")],
        )
        .await;
    assert_eq!(res.location(), Some(format!("/music#entry-{ok}").as_str()));
    let cache: i64 = sqlx::query_scalar("SELECT cache FROM music_entries WHERE id = ?")
        .bind(ok)
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(cache, 10);
    assert_eq!(events(&app, "music_entry_saved").await.len(), 1);
    let res = app
        .request_form(
            Method::POST,
            &format!("/music/entries/{ok}/offline"),
            Some(&cookie),
            &[("cache", "7")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
}

/// Check now's hourly and daily limits in all.
#[tokio::test]
async fn check_now_is_limited_per_hour_and_day() {
    let app = TestApp::new().await;
    let now = t0();
    for _ in 0..11 {
        sqlx::query("INSERT INTO music_check_requests (at) VALUES (?)")
            .bind(stamp(now - TimeDelta::minutes(50)))
            .execute(&app.db)
            .await
            .unwrap();
    }
    let mut ids = Vec::new();
    for n in 0..3 {
        ids.push(
            entry(
                &app,
                &format!("https://example.org/l{n}.rss"),
                "L",
                "auto",
                "2026-10-09 11:00:00",
            )
            .await,
        );
    }
    assert_eq!(
        sweep::check_now_blocked_until(&app.db, ids[0], now)
            .await
            .unwrap(),
        None
    );
    sweep::request_check(&app.db, ids[0], now).await.unwrap();
    // 12 in the hour: blocked until the oldest of them is an hour old.
    assert_eq!(
        sweep::check_now_blocked_until(&app.db, ids[1], now)
            .await
            .unwrap(),
        Some(now + TimeDelta::minutes(10))
    );
    // 48 a day.
    for n in 0..40 {
        sqlx::query("INSERT INTO music_check_requests (at) VALUES (?)")
            .bind(stamp(now - TimeDelta::hours(3) - TimeDelta::minutes(n)))
            .execute(&app.db)
            .await
            .unwrap();
    }
    let until = sweep::check_now_blocked_until(&app.db, ids[2], now + TimeDelta::hours(2))
        .await
        .unwrap();
    assert!(
        until.is_some_and(|u| u > now + TimeDelta::hours(18)),
        "{until:?}"
    );
}
