//! Episode art (design 27 §1 and §5 step 1, with its design QA M2, M3 and L1): the sweep's art
//! pass on canned answers with the fake clock - own pictures by hash, the series picture by URL
//! and by print, a result reused across entries, failures and retries, the budgets and the play
//! order, the backlog, a new cover's re-judging, the 16:9 picture, the one-time NRK refresh, the
//! store (lock, prune, no backup, recovery), the cover route's scoping and the card's count.

use axum::http::{Method, StatusCode};
use chrono::TimeDelta;
use serde_json::{Value, json};

use super::TestApp;
use super::music::device_get;
use super::music_sweep::{
    PSAPI, answer_manifests, answer_root, check, entry, items, listing, small_url, t0, tick_on,
};
use crate::music_sweep::{self as sweep, stamp};

const CREATED: &str = "2026-10-09 11:00:00";

/// A picture whose print is fixed by `seed`: eight horizontal bands, each a gradient rising or
/// falling with the band's bit of `seed`. Two seeds that differ in one bit are 8 bits apart (more
/// than the "same picture" line of 5), and any size or re-encode of one seed prints the same.
fn picture(seed: u8, w: u32, h: u32) -> image::DynamicImage {
    image::DynamicImage::ImageRgb8(image::RgbImage::from_fn(w, h, |x, y| {
        let band = y * 8 / h;
        let t = (x * 255 / (w - 1)) as u8;
        let v = if (seed >> band) & 1 == 1 { t } else { 255 - t };
        image::Rgb([v, v / 2, 255 - v])
    }))
}

fn png(seed: u8, w: u32, h: u32) -> Vec<u8> {
    let mut out = std::io::Cursor::new(Vec::new());
    picture(seed, w, h)
        .write_to(&mut out, image::ImageFormat::Png)
        .unwrap();
    out.into_inner()
}

fn lossy_jpeg(seed: u8, side: u32) -> Vec<u8> {
    let mut out = Vec::new();
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut out, 40)
        .encode_image(&picture(seed, side, side).to_rgb8())
        .unwrap();
    out
}

/// An RSS feed, oldest item first: `(guid, its itunes:image)`, and the channel's image.
fn art_feed(channel_image: Option<&str>, items: &[(&str, Option<&str>)]) -> String {
    let mut body = String::from(
        "<?xml version=\"1.0\"?><rss xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"><channel><title>Bilder</title>",
    );
    if let Some(image) = channel_image {
        body.push_str(&format!("<itunes:image href=\"{image}\"/>"));
    }
    for (index, (guid, image)) in items.iter().enumerate() {
        let image = image
            .map(|i| format!("<itunes:image href=\"{i}\"/>"))
            .unwrap_or_default();
        body.push_str(&format!(
            "<item><title>{guid}</title><guid>{guid}</guid>\
             <enclosure url=\"https://example.org/{guid}.mp3\"/>{image}<pubDate>{}</pubDate></item>",
            (t0() + TimeDelta::minutes(index as i64)).to_rfc2822()
        ));
    }
    body.push_str("</channel></rss>");
    body
}

async fn rss_entry(app: &TestApp, url: &str) -> i64 {
    entry(app, url, "Bilder", "auto", CREATED).await
}

fn art_states(list: &[sweep::Item]) -> Vec<Option<String>> {
    list.iter().map(|i| i.art_state.clone()).collect()
}

fn calls_to(app: &TestApp, url: &str) -> usize {
    app.fetch
        .calls
        .lock()
        .unwrap()
        .iter()
        .filter(|u| u.as_str() == url)
        .count()
}

fn calls_starting(app: &TestApp, prefix: &str) -> usize {
    app.fetch
        .calls
        .lock()
        .unwrap()
        .iter()
        .filter(|u| u.starts_with(prefix))
        .count()
}

fn stored(app: &TestApp, hash: &str) -> bool {
    app.state.music_art_dir.join(format!("{hash}.jpg")).exists()
}

async fn listing_doc(app: &TestApp, token: &str, id: i64) -> Value {
    let res = device_get(
        app,
        &format!("/api/devices/music/entries/{id}/items"),
        token,
        &[],
    )
    .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    res.json()
}

/// A psapi episode stub with the given picture variants (`squareImage` and/or 16:9 `image`).
fn art_stub(
    slug: &str,
    key: &str,
    minutes_ago: i64,
    square: &[(&str, u64)],
    wide: &[(&str, u64)],
) -> Value {
    let variants = |list: &[(&str, u64)]| -> Value {
        list.iter()
            .map(|(url, width)| json!({"url": url, "width": width}))
            .collect()
    };
    let mut stub = json!({
        "_links": {"self": {"href": format!("/radio/catalog/podcast/{slug}/episodes/{key}")}},
        "titles": {"title": format!("Episode {key}")},
        "date": (t0() - TimeDelta::minutes(minutes_ago)).to_rfc3339(),
        "durationInSeconds": 600,
        "usageRights": {"to": {"date": "9999-12-31T00:00:00+01:00"}}
    });
    if !square.is_empty() {
        stub["squareImage"] = variants(square);
    }
    if !wide.is_empty() {
        stub["image"] = variants(wide);
    }
    stub
}

/// A podcast's desc pages (the 50-episode page and the routine 5) of these stubs, newest first.
fn answer_stubs(app: &TestApp, slug: &str, stubs: &[Value]) {
    let full =
        format!("{PSAPI}/radio/catalog/podcast/{slug}/episodes?page=1&pageSize=50&sort=desc");
    app.fetch.answer(
        &full,
        200,
        json!({"_embedded": {"episodes": stubs}}).to_string(),
    );
    let small: Vec<Value> = stubs.iter().take(5).cloned().collect();
    app.fetch.answer(
        &small_url("podcast", slug, "desc"),
        200,
        json!({"_embedded": {"episodes": small}}).to_string(),
    );
}

// ------------------------------------------------------------------------------------------------
// Own pictures, by hash
// ------------------------------------------------------------------------------------------------

/// An RSS item's `itunes:image` is fetched once, stored as a 512 px JPEG and listed by hash; an
/// item without one is `null`; the cover route serves it only to a phone with the entry, only for
/// a listed item.
#[tokio::test]
async fn rss_items_get_their_own_pictures_by_hash() {
    let app = TestApp::new().await;
    let (phone, token) = app.enrolled_device("Ella").await;
    let (_, stranger) = app.enrolled_device("Max").await;
    let feed = "https://example.org/art.rss";
    let id = rss_entry(&app, feed).await;
    tick_on(&app, phone, id).await;
    app.fetch.answer(
        feed,
        200,
        art_feed(
            Some("https://example.org/chan.jpg"),
            &[
                ("a", Some("https://example.org/a.jpg")),
                ("b", None),
                ("c", Some("https://example.org/c.jpg")),
            ],
        ),
    );
    app.fetch.answer(
        "https://example.org/chan.jpg",
        200,
        png(0b0000_0001, 600, 600),
    );
    app.fetch.answer(
        "https://example.org/a.jpg",
        200,
        png(0b0001_0000, 1400, 1400),
    );
    app.fetch
        .answer("https://example.org/c.jpg", 200, png(0b1000_0000, 800, 800));
    let report = check(&app, id, t0()).await;
    assert_eq!(report.error, None);
    assert_eq!((report.art_own, report.art_same), (2, 0));
    assert_eq!(report.art_line(), ", art 2 (2 own, 0 same)");
    // The feed, the cover and two pictures.
    assert_eq!(report.requests, 4, "{:?}", app.fetch.calls.lock().unwrap());
    let list = items(&app, id).await;
    assert_eq!(
        art_states(&list),
        [Some("own".to_string()), None, Some("own".to_string())]
    );
    let a = list[0].art_hash.clone().unwrap();
    let c = list[2].art_hash.clone().unwrap();
    assert_ne!(a, c);
    assert!(crate::photos::is_valid_hash(&a));
    assert!(stored(&app, &a) && stored(&app, &c));
    let file = std::fs::read(app.state.music_art_dir.join(format!("{a}.jpg"))).unwrap();
    let image = image::load_from_memory(&file).unwrap();
    assert_eq!((image.width(), image.height()), (512, 512), "512 px");
    let doc = listing_doc(&app, &token, id).await;
    let arts: Vec<Value> = doc["items"]
        .as_array()
        .unwrap()
        .iter()
        .map(|i| i["art"].clone())
        .collect();
    assert_eq!(arts, [json!(a), Value::Null, json!(c)]);

    // The cover route: the phone with the entry, the picture's bytes.
    let uri = format!("/api/devices/music/covers/{a}");
    let res = device_get(&app, &uri, &token, &[]).await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.body, file);
    assert_eq!(
        device_get(&app, &uri, &stranger, &[]).await.status,
        StatusCode::NOT_FOUND,
        "another phone"
    );
    assert_eq!(
        device_get(
            &app,
            &format!("/api/devices/music/covers/{}", "e".repeat(64)),
            &token,
            &[]
        )
        .await
        .status,
        StatusCode::NOT_FOUND
    );
    // Not for a pending item, nor for an entry the phone no longer has.
    sqlx::query("UPDATE music_items SET state = 'pending' WHERE art_hash = ?")
        .bind(&a)
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        device_get(&app, &uri, &token, &[]).await.status,
        StatusCode::NOT_FOUND,
        "pending"
    );
    sqlx::query("UPDATE music_items SET state = 'ok' WHERE art_hash = ?")
        .bind(&a)
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query("DELETE FROM device_music_entries WHERE entry_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        device_get(&app, &uri, &token, &[]).await.status,
        StatusCode::NOT_FOUND,
        "unticked"
    );

    // Nothing new: no picture is fetched again.
    app.fetch.clear_calls();
    check(&app, id, t0() + TimeDelta::hours(6)).await;
    assert_eq!(calls_starting(&app, "https://example.org/a.jpg"), 0);
    assert_eq!(calls_starting(&app, "https://example.org/c.jpg"), 0);
}

// ------------------------------------------------------------------------------------------------
// The series picture
// ------------------------------------------------------------------------------------------------

/// The 512 pick: an NRK episode that uses the series picture has the cover's URL (step 1, no
/// request); one with its own picture takes its smallest variant of at least 512 px.
#[tokio::test]
async fn an_nrk_episode_with_the_series_url_costs_no_request() {
    let app = TestApp::new().await;
    let id = entry(
        &app,
        "https://radio.nrk.no/podkast/bilder",
        "Bilder",
        "auto",
        CREATED,
    )
    .await;
    let series = "https://gfx.nrk.no/series600";
    answer_root(&app, "podcast", "bilder", "Bilder", Some(series));
    let stubs = vec![
        art_stub(
            "bilder",
            "e3",
            0,
            &[
                ("https://gfx.nrk.no/e3-300", 300),
                ("https://gfx.nrk.no/e3-960", 960),
            ],
            &[],
        ),
        art_stub(
            "bilder",
            "e2",
            60,
            &[("https://gfx.nrk.no/series300", 300), (series, 600)],
            &[],
        ),
        art_stub("bilder", "e1", 120, &[], &[]),
    ];
    answer_stubs(&app, "bilder", &stubs);
    answer_manifests(&app, "podcast", &["e3".into(), "e2".into(), "e1".into()]);
    app.fetch.answer(series, 200, png(0b0000_0011, 600, 600));
    app.fetch
        .answer("https://gfx.nrk.no/e3-960", 200, png(0b1100_0000, 960, 960));
    let report = check(&app, id, t0()).await;
    assert_eq!(report.error, None);
    // root, page, 3 manifests, the cover, e3's picture
    assert_eq!(report.requests, 7, "{:?}", app.fetch.calls.lock().unwrap());
    assert_eq!(calls_to(&app, series), 1, "only as the cover");
    let list = items(&app, id).await;
    assert_eq!(
        list[2].art_url.as_deref(),
        Some("https://gfx.nrk.no/e3-960")
    );
    assert_eq!(
        art_states(&list),
        [None, Some("same".to_string()), Some("own".to_string())]
    );
    assert_eq!(list[1].art_hash, None);
    assert!(list[2].art_hash.is_some());
    assert_eq!((report.art_own, report.art_same), (1, 1));
}

/// The print (§1.3 step 4): the series picture under another URL, at another size, or re-encoded
/// is the series picture, and nothing is stored; another picture is the item's own.
#[tokio::test]
async fn the_series_picture_under_another_url_is_judged_by_its_print() {
    let app = TestApp::new().await;
    let feed = "https://example.org/print.rss";
    let id = rss_entry(&app, feed).await;
    app.fetch.answer(
        feed,
        200,
        art_feed(
            Some("https://example.org/chan.jpg"),
            &[
                ("small", Some("https://example.org/chan-300.jpg?ts=1")),
                ("lossy", Some("https://example.org/chan.jpg?ts=2")),
                ("own", Some("https://example.org/own.jpg")),
            ],
        ),
    );
    app.fetch.answer(
        "https://example.org/chan.jpg",
        200,
        png(0b0101_0101, 1400, 1400),
    );
    app.fetch.answer(
        "https://example.org/chan-300.jpg?ts=1",
        200,
        png(0b0101_0101, 300, 300),
    );
    app.fetch.answer(
        "https://example.org/chan.jpg?ts=2",
        200,
        lossy_jpeg(0b0101_0101, 900),
    );
    app.fetch.answer(
        "https://example.org/own.jpg",
        200,
        png(0b0101_0100, 600, 600),
    );
    let report = check(&app, id, t0()).await;
    assert_eq!((report.art_own, report.art_same), (1, 2));
    let list = items(&app, id).await;
    assert_eq!(
        art_states(&list),
        [
            Some("same".to_string()),
            Some("same".to_string()),
            Some("own".to_string())
        ]
    );
    assert!(list[0].art_print.is_some() && list[0].art_hash.is_none());
    let files = std::fs::read_dir(&*app.state.music_art_dir)
        .unwrap()
        .count();
    assert_eq!(files, 1, "only the own picture is stored");
}

/// Step 2: a URL another entry already has a result for costs no request, and is judged against
/// this entry's series picture; a `same` elsewhere stored no file, so this entry's `own` needs
/// the request (QA L1 c).
#[tokio::test]
async fn a_picture_seen_in_another_entry_is_judged_per_entry() {
    let app = TestApp::new().await;
    let shared = "https://example.org/shared.jpg";
    app.fetch.answer(shared, 200, png(0b0011_1100, 600, 600));
    // B: the shared picture is its series picture (under another URL).
    let b = rss_entry(&app, "https://example.org/b.rss").await;
    app.fetch.answer(
        "https://example.org/b.rss",
        200,
        art_feed(
            Some("https://example.org/b-chan.jpg"),
            &[("b1", Some(shared))],
        ),
    );
    app.fetch.answer(
        "https://example.org/b-chan.jpg",
        200,
        png(0b0011_1100, 800, 800),
    );
    // C and D: another series picture.
    let c = rss_entry(&app, "https://example.org/c.rss").await;
    app.fetch.answer(
        "https://example.org/c.rss",
        200,
        art_feed(
            Some("https://example.org/c-chan.jpg"),
            &[("c1", Some(shared))],
        ),
    );
    app.fetch.answer(
        "https://example.org/c-chan.jpg",
        200,
        png(0b1000_0001, 600, 600),
    );
    let d = rss_entry(&app, "https://example.org/d.rss").await;
    app.fetch.answer(
        "https://example.org/d.rss",
        200,
        art_feed(
            Some("https://example.org/c-chan.jpg"),
            &[("d1", Some(shared))],
        ),
    );

    check(&app, b, t0()).await;
    assert_eq!(calls_to(&app, shared), 1);
    assert_eq!(items(&app, b).await[0].art_state.as_deref(), Some("same"));
    // C: B's result is a print without a file - judged own here, so fetched.
    check(&app, c, t0()).await;
    assert_eq!(calls_to(&app, shared), 2, "QA L1 c");
    let c_hash = items(&app, c).await[0].art_hash.clone().unwrap();
    // D: C's picture is reused, no request.
    let report = check(&app, d, t0()).await;
    assert_eq!(calls_to(&app, shared), 2);
    assert_eq!(items(&app, d).await[0].art_hash, Some(c_hash));
    assert_eq!(report.art_own, 1);
    // And B, checked again with nothing new, is still the series picture.
    check(&app, b, t0() + TimeDelta::hours(6)).await;
    assert_eq!(items(&app, b).await[0].art_hash, None);
}

/// QA M2: one picture under ten URLs in a feed with no channel image is one hash (the phone sees
/// one picture, so no carousel).
#[tokio::test]
async fn one_picture_under_many_urls_is_one_hash() {
    let app = TestApp::new().await;
    let feed = "https://example.org/same.rss";
    let id = rss_entry(&app, feed).await;
    let urls: Vec<String> = (0..10)
        .map(|n| format!("https://example.org/p.jpg?v={n}"))
        .collect();
    let entries: Vec<(String, Option<&str>)> = urls
        .iter()
        .enumerate()
        .map(|(n, url)| (format!("i{n}"), Some(url.as_str())))
        .collect();
    let feed_items: Vec<(&str, Option<&str>)> =
        entries.iter().map(|(g, u)| (g.as_str(), *u)).collect();
    app.fetch.answer(feed, 200, art_feed(None, &feed_items));
    for (n, url) in urls.iter().enumerate() {
        // The same picture at different sizes: different bytes, the same print.
        app.fetch.answer(
            url,
            200,
            png(0b0110_0110, 300 + n as u32 * 40, 300 + n as u32 * 40),
        );
    }
    let report = check(&app, id, t0()).await;
    assert_eq!(report.art_own, 10);
    let hashes: std::collections::HashSet<String> = items(&app, id)
        .await
        .into_iter()
        .filter_map(|i| i.art_hash)
        .collect();
    assert_eq!(hashes.len(), 1);
    assert_eq!(
        std::fs::read_dir(&*app.state.music_art_dir)
            .unwrap()
            .count(),
        1
    );
}

// ------------------------------------------------------------------------------------------------
// Failures
// ------------------------------------------------------------------------------------------------

/// A failure never fails the check; it is tried again a day later, at most 3 times for one URL;
/// a new URL starts again.
#[tokio::test]
async fn a_failed_picture_is_retried_daily_three_times_and_a_new_url_resets() {
    let app = TestApp::new().await;
    let (phone, token) = app.enrolled_device("Ella").await;
    let feed = "https://example.org/fail.rss";
    let id = rss_entry(&app, feed).await;
    tick_on(&app, phone, id).await;
    let bad = "https://example.org/bad.jpg";
    app.fetch
        .answer(feed, 200, art_feed(None, &[("x", Some(bad))]));
    app.fetch.answer(bad, 404, "");
    let report = check(&app, id, t0()).await;
    assert_eq!(report.error, None, "never fails the check");
    let item = &items(&app, id).await[0];
    assert_eq!(
        (item.art_state.as_deref(), item.art_fails),
        (Some("failed"), 1)
    );
    assert_eq!(
        listing_doc(&app, &token, id).await["items"][0]["art"],
        Value::Null
    );
    check(&app, id, t0() + TimeDelta::hours(6)).await;
    assert_eq!(calls_to(&app, bad), 1, "not within a day");
    check(&app, id, t0() + TimeDelta::hours(25)).await;
    app.fetch.fail(bad, crate::music_net::FetchError::Timeout);
    check(&app, id, t0() + TimeDelta::hours(50)).await;
    assert_eq!(calls_to(&app, bad), 3);
    assert_eq!(items(&app, id).await[0].art_fails, 3);
    check(&app, id, t0() + TimeDelta::hours(80)).await;
    assert_eq!(calls_to(&app, bad), 3, "given up after 3");
    // A new URL: tried at once, from zero.
    let good = "https://example.org/good.jpg";
    app.fetch
        .answer(feed, 200, art_feed(None, &[("x", Some(good))]));
    app.fetch.answer(good, 200, png(0b0000_1111, 400, 400));
    check(&app, id, t0() + TimeDelta::hours(81)).await;
    let item = &items(&app, id).await[0];
    assert_eq!(
        (item.art_state.as_deref(), item.art_fails),
        (Some("own"), 0)
    );
    assert_eq!(
        listing_doc(&app, &token, id).await["items"][0]["art"],
        json!(item.art_hash.clone().unwrap())
    );
}

// ------------------------------------------------------------------------------------------------
// Budgets and order
// ------------------------------------------------------------------------------------------------

/// 20 requests a check, in play order (newest first for a feed); 400 a day server-wide, counting
/// only requests; a request the budget stops leaves its item untried (QA L1 b).
#[tokio::test]
async fn twenty_a_check_and_400_a_day_in_play_order() {
    let app = TestApp::new().await;
    let feed = "https://example.org/many.rss";
    let id = rss_entry(&app, feed).await;
    let guids: Vec<String> = (0..30).map(|n| format!("i{n:02}")).collect();
    let urls: Vec<String> = (0..30)
        .map(|n| format!("https://example.org/{n}.jpg"))
        .collect();
    let feed_items: Vec<(&str, Option<&str>)> = guids
        .iter()
        .zip(&urls)
        .map(|(g, u)| (g.as_str(), Some(u.as_str())))
        .collect();
    app.fetch.answer(feed, 200, art_feed(None, &feed_items));
    for url in &urls {
        app.fetch.answer(url, 404, "");
    }
    check(&app, id, t0()).await;
    let list = items(&app, id).await;
    let tried: Vec<bool> = list.iter().map(|i| i.art_from.is_some()).collect();
    assert_eq!(tried.iter().filter(|t| **t).count(), 20);
    assert!(
        tried[10..].iter().all(|t| *t) && tried[..10].iter().all(|t| !*t),
        "the newest 20 first: {tried:?}"
    );
    assert_eq!(
        calls_starting(&app, "https://example.org/1"),
        10,
        "10-19, not 1"
    );

    // The day: 395 requests elsewhere leave room for 5.
    sqlx::query(
        "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 375) \
         INSERT INTO music_items (entry_id, key, seq, state, first_seen_at, art_fetched_at) \
         SELECT ?, 'x' || i, 1000 + i, 'gone', ?, ? FROM n",
    )
    .bind(id)
    .bind(stamp(t0()))
    .bind(stamp(t0() + TimeDelta::minutes(30)))
    .execute(&app.db)
    .await
    .unwrap();
    app.fetch.clear_calls();
    check(&app, id, t0() + TimeDelta::hours(1)).await;
    // 20 at t0 + 375 = 395: 5 left.
    assert_eq!(calls_starting(&app, "https://example.org/") - 1, 5);
    let untried = items(&app, id)
        .await
        .iter()
        .filter(|i| i.art_url.is_some() && i.art_from.is_none())
        .count();
    assert_eq!(untried, 5, "the rest stays untried");
}

/// Only the first 200 `ok` items in play order get pictures (`ART_WINDOW`); a `gone` item keeps
/// what it has and gets nothing new; this check's new items go first.
#[tokio::test]
async fn the_window_is_200_in_play_order_and_gone_items_get_nothing_new() {
    let app = TestApp::new().await;
    app.state.music_sweep.set_art_per_check(1000);
    let feed = "https://example.org/long.rss";
    let id = entry(&app, feed, "Lang", "oldest_first", CREATED).await;
    let guids: Vec<String> = (0..210).map(|n| format!("g{n:03}")).collect();
    let urls: Vec<String> = (0..210)
        .map(|n| format!("https://example.org/{n}.jpg"))
        .collect();
    let feed_items: Vec<(&str, Option<&str>)> = guids
        .iter()
        .zip(&urls)
        .map(|(g, u)| (g.as_str(), Some(u.as_str())))
        .collect();
    app.fetch.answer(feed, 200, art_feed(None, &feed_items));
    let list = items(&app, id).await;
    assert!(list.is_empty());
    check(&app, id, t0()).await;
    let list = items(&app, id).await;
    let tried: Vec<bool> = list.iter().map(|i| i.art_from.is_some()).collect();
    assert!(
        tried[..200].iter().all(|t| *t),
        "oldest first: the first 200"
    );
    assert!(tried[200..].iter().all(|t| !*t));

    // g000 leaves the feed (gone) and changes its picture; nothing is fetched for it.
    let mut changed = feed_items.clone();
    changed.remove(0);
    let new_url = "https://example.org/new-for-g000.jpg";
    app.fetch.answer(feed, 200, art_feed(None, &changed));
    sqlx::query("UPDATE music_items SET art_url = ? WHERE key = (SELECT key FROM music_items WHERE entry_id = ? ORDER BY seq LIMIT 1)")
        .bind(new_url)
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    check(&app, id, t0() + TimeDelta::hours(1)).await;
    let first = &items(&app, id).await[0];
    assert_eq!(first.state, "gone");
    assert_eq!(calls_to(&app, new_url), 0);
}

/// A simulated day of 30 NRK podcasts with 100 episodes each (every one with its own picture) and
/// 10 feeds: the first pass fetches at most 20 a check and 400 in all, and nothing more until the
/// day is over.
#[tokio::test]
async fn a_day_of_backfill_stays_within_the_budgets() {
    let app = TestApp::new().await;
    app.state
        .music_sweep
        .set_limits(sweep::MAX_CHECK_REQUESTS, std::time::Duration::ZERO);
    let (phone, _) = app.enrolled_device("Ella").await;
    let picture = png(0b0101_1010, 64, 64);
    for n in 0..30 {
        let slug = format!("pod{n}");
        let id = entry(
            &app,
            &format!("https://radio.nrk.no/podkast/{slug}"),
            &slug,
            "auto",
            CREATED,
        )
        .await;
        answer_root(&app, "podcast", &slug, &slug, None);
        let keys: Vec<String> = (1..=100).rev().map(|i| format!("{slug}_{i}")).collect();
        let stubs: Vec<Value> = keys
            .iter()
            .enumerate()
            .map(|(age, key)| {
                let url = format!("https://gfx.nrk.no/ep/{key}");
                art_stub(&slug, key, age as i64 * 60, &[(url.as_str(), 600)], &[])
            })
            .collect();
        for (page, chunk) in stubs.chunks(50).enumerate() {
            let mut body = json!({"_embedded": {"episodes": chunk}});
            if page == 0 {
                body["_links"] = json!({"next": {"href": format!(
                    "/radio/catalog/podcast/{slug}/episodes?page=2&pageSize=50&sort=desc"
                )}});
            }
            app.fetch.answer(
                &format!(
                    "{PSAPI}/radio/catalog/podcast/{slug}/episodes?page={}&pageSize=50&sort=desc",
                    page + 1
                ),
                200,
                body.to_string(),
            );
        }
        app.fetch.answer(
            &small_url("podcast", &slug, "desc"),
            200,
            json!({"_embedded": {"episodes": stubs[..5]}}).to_string(),
        );
        answer_manifests(&app, "podcast", &keys);
        for key in &keys {
            app.fetch.answer(
                &format!("https://gfx.nrk.no/ep/{key}"),
                200,
                picture.clone(),
            );
        }
        tick_on(&app, phone, id).await;
    }
    for n in 0..10 {
        let url = format!("https://feeds.example.org/{n}.rss");
        let id = entry(&app, &url, "F", "auto", CREATED).await;
        let images: Vec<String> = (0..50)
            .map(|i| format!("https://img.example.org/{n}/{i}.jpg"))
            .collect();
        let guids: Vec<String> = (0..50).map(|i| format!("f{n}-{i}")).collect();
        let feed_items: Vec<(&str, Option<&str>)> = guids
            .iter()
            .zip(&images)
            .map(|(g, i)| (g.as_str(), Some(i.as_str())))
            .collect();
        app.fetch.answer(&url, 200, art_feed(None, &feed_items));
        for image in &images {
            app.fetch.answer(image, 200, picture.clone());
        }
        tick_on(&app, phone, id).await;
    }
    app.state.music_sweep.set_clock(Some(t0()));
    assert!(sweep::run_pass(&app.state).await);
    let images = |app: &TestApp| {
        calls_starting(app, "https://gfx.nrk.no/ep/")
            + calls_starting(app, "https://img.example.org/")
    };
    assert_eq!(images(&app), sweep::ART_PER_DAY as usize);
    let per_entry: Vec<i64> = sqlx::query_scalar(
        "SELECT COUNT(*) FROM music_items WHERE art_fetched_at IS NOT NULL GROUP BY entry_id",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert!(per_entry.iter().all(|n| *n <= sweep::ART_PER_CHECK as i64));
    // The rest of the day: the sweep runs on; no more pictures until the day's budget frees.
    let end = t0() + TimeDelta::hours(23);
    loop {
        let now = app.state.music_sweep.now();
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
        app.state.music_sweep.set_clock(Some(next.max(now)));
        assert!(sweep::run_pass(&app.state).await);
    }
    assert_eq!(images(&app), sweep::ART_PER_DAY as usize);
}

// ------------------------------------------------------------------------------------------------
// The backlog (§1.4)
// ------------------------------------------------------------------------------------------------

/// Five fetched pictures in a row that are the series picture (and none of the entry's own): only
/// new items from then on. An own picture turns the backlog back on; so does a month (QA L1 e).
#[tokio::test]
async fn the_backlog_stops_after_five_series_pictures_and_comes_back() {
    let app = TestApp::new().await;
    app.state.music_sweep.set_art_per_check(6);
    let feed = "https://example.org/backlog.rss";
    let id = rss_entry(&app, feed).await;
    let chan = "https://example.org/chan.jpg";
    app.fetch.answer(chan, 200, png(0b0000_0110, 600, 600));
    let mut feed_items: Vec<(String, String)> = (0..9)
        .map(|n| {
            (
                format!("o{n}"),
                format!("https://example.org/chan.jpg?ts={n}"),
            )
        })
        .collect();
    let answer = |app: &TestApp, list: &[(String, String)]| {
        let refs: Vec<(&str, Option<&str>)> = list
            .iter()
            .map(|(g, u)| (g.as_str(), Some(u.as_str())))
            .collect();
        app.fetch.answer(feed, 200, art_feed(Some(chan), &refs));
        for (_, url) in list {
            if url.contains("?ts=") {
                app.fetch.answer(url, 200, png(0b0000_0110, 300, 300));
            }
        }
    };
    answer(&app, &feed_items);
    check(&app, id, t0()).await;
    assert!(!listing(&app, id).await.art_backlog, "6 series pictures");
    let untried = |list: &[sweep::Item]| list.iter().filter(|i| i.art_from.is_none()).count();
    assert_eq!(untried(&items(&app, id).await), 3);
    // Only new items now: the 3 older ones wait.
    feed_items.push(("n1".into(), "https://example.org/chan.jpg?ts=n1".into()));
    answer(&app, &feed_items);
    check(&app, id, t0() + TimeDelta::hours(6)).await;
    let list = items(&app, id).await;
    assert_eq!(untried(&list), 3);
    assert!(
        list.last().unwrap().art_from.is_some(),
        "the new one is tried"
    );
    // An own picture: the backlog is back, the next check tries the rest.
    feed_items.push(("n2".into(), "https://example.org/own.jpg".into()));
    answer(&app, &feed_items);
    app.fetch.answer(
        "https://example.org/own.jpg",
        200,
        png(0b1111_0000, 300, 300),
    );
    check(&app, id, t0() + TimeDelta::hours(12)).await;
    assert!(listing(&app, id).await.art_backlog);
    check(&app, id, t0() + TimeDelta::hours(18)).await;
    assert_eq!(untried(&items(&app, id).await), 0);

    // A month later: off, then on again.
    sqlx::query("UPDATE music_listings SET art_backlog = 0, art_backlog_at = ? WHERE entry_id = ?")
        .bind(stamp(t0()))
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    check(&app, id, t0() + TimeDelta::days(20)).await;
    assert!(!listing(&app, id).await.art_backlog);
    check(&app, id, t0() + TimeDelta::days(31)).await;
    assert!(listing(&app, id).await.art_backlog);
}

// ------------------------------------------------------------------------------------------------
// A new cover (§1.3 step 6, QA M2)
// ------------------------------------------------------------------------------------------------

/// A new series cover re-judges every item from its stored print with no request: an own picture
/// that is now the cover becomes the series picture (its file is pruned); the old cover's items
/// stay the series picture (the earlier covers' prints); once an old print drops out (after 3
/// newer covers), its items are untried and fetched again.
#[tokio::test]
async fn a_new_cover_re_judges_without_requests() {
    let app = TestApp::new().await;
    let feed = "https://example.org/rebrand.rss";
    let id = rss_entry(&app, feed).await;
    let old_art = "https://example.org/old-art.jpg";
    let own_art = "https://example.org/own-art.jpg";
    app.fetch.answer(old_art, 200, png(0b0000_0001, 400, 400));
    app.fetch.answer(own_art, 200, png(0b0000_0010, 400, 400));
    let with_cover = |cover: &str| {
        art_feed(
            Some(cover),
            &[("old", Some(old_art)), ("own", Some(own_art))],
        )
    };
    let covers = [
        ("https://example.org/c1.jpg", 0b0000_0001u8),
        ("https://example.org/c2.jpg", 0b0000_0010),
        ("https://example.org/c3.jpg", 0b0000_0100),
        ("https://example.org/c4.jpg", 0b0000_1000),
        ("https://example.org/c5.jpg", 0b0001_0000),
    ];
    for (url, seed) in covers {
        app.fetch.answer(url, 200, png(seed, 600, 600));
    }
    app.fetch.answer(feed, 200, with_cover(covers[0].0));
    check(&app, id, t0()).await;
    let list = items(&app, id).await;
    assert_eq!(
        art_states(&list),
        [Some("same".to_string()), Some("own".to_string())]
    );
    let own_hash = list[1].art_hash.clone().unwrap();
    assert!(stored(&app, &own_hash));

    // Rebrand: the new cover is the "own" picture.
    app.fetch.answer(feed, 200, with_cover(covers[1].0));
    app.fetch.clear_calls();
    let report = check(&app, id, t0() + TimeDelta::days(1)).await;
    assert_eq!(
        calls_to(&app, old_art) + calls_to(&app, own_art),
        0,
        "no requests"
    );
    let list = items(&app, id).await;
    assert_eq!(
        art_states(&list),
        [Some("same".to_string()), Some("same".to_string())],
        "{report:?}"
    );
    assert!(!stored(&app, &own_hash), "pruned");
    let row = listing(&app, id).await;
    assert_eq!(
        row.old_prints.as_deref().map(|p| p.matches(',').count()),
        Some(0)
    );

    // Three more covers: the first one's print drops out, its item is fetched again (own now).
    for (day, (url, _)) in covers[2..].iter().enumerate() {
        app.fetch.answer(feed, 200, with_cover(url));
        check(&app, id, t0() + TimeDelta::days(2 + day as i64)).await;
    }
    assert_eq!(calls_to(&app, old_art), 1, "untried, so fetched");
    let list = items(&app, id).await;
    assert_eq!(list[0].art_state.as_deref(), Some("own"));
    assert_eq!(
        list[1].art_state.as_deref(),
        Some("same"),
        "c2 is still kept"
    );
}

/// QA M2 b: NRK's 16:9 series image (fetched once with the cover) is a series picture too: an
/// episode that falls back to it, centre-cropped, is judged `same`.
#[tokio::test]
async fn nrks_wide_series_image_is_a_series_picture() {
    let app = TestApp::new().await;
    let id = entry(
        &app,
        "https://radio.nrk.no/podkast/bred",
        "Bred",
        "auto",
        CREATED,
    )
    .await;
    let square = "https://gfx.nrk.no/sq600";
    let wide = "https://gfx.nrk.no/wide960";
    app.fetch.answer(
        &format!("{PSAPI}/radio/catalog/podcast/bred"),
        200,
        json!({"series": {
            "titles": {"title": "Bred"},
            "squareImage": [{"url": square, "width": 600}],
            "image": [{"url": wide, "width": 960}]
        }})
        .to_string(),
    );
    let episode_wide = "https://gfx.nrk.no/wide600";
    let stubs = vec![art_stub("bred", "b1", 0, &[], &[(episode_wide, 600)])];
    answer_stubs(&app, "bred", &stubs);
    answer_manifests(&app, "podcast", &["b1".into()]);
    app.fetch.answer(square, 200, png(0b0000_0001, 600, 600));
    // The banner: 16:9, the middle of it is what the crop keeps.
    app.fetch.answer(wide, 200, png(0b1110_0000, 960, 540));
    app.fetch
        .answer(episode_wide, 200, png(0b1110_0000, 600, 338));
    check(&app, id, t0()).await;
    let item = &items(&app, id).await[0];
    assert_eq!(item.art_state.as_deref(), Some("same"));
    let row = listing(&app, id).await;
    assert_eq!(row.cover_alt_url.as_deref(), Some(wide));
    assert!(row.cover_alt_print.is_some() && row.cover_print.is_some());
    // Fetched once: not again at the next root read.
    app.fetch.clear_calls();
    check(&app, id, t0() + TimeDelta::days(8)).await;
    assert_eq!(calls_to(&app, wide), 0);
}

// ------------------------------------------------------------------------------------------------
// The one-time refresh (QA M3)
// ------------------------------------------------------------------------------------------------

/// Episodes listed with the old 300 px pick get the 512 px one once: the flagged check walks the
/// pages again and updates only `art_url` (untried); the next routine check is as before.
#[tokio::test]
async fn nrk_episodes_listed_before_get_the_512_picture_once() {
    let app = TestApp::new().await;
    app.state.music_sweep.set_art_per_check(0);
    let id = entry(
        &app,
        "https://radio.nrk.no/podkast/gammel",
        "Gammel",
        "auto",
        CREATED,
    )
    .await;
    answer_root(&app, "podcast", "gammel", "Gammel", None);
    let keys: Vec<String> = vec!["g3".into(), "g2".into(), "g1".into()];
    let stubs: Vec<Value> = keys
        .iter()
        .enumerate()
        .map(|(age, key)| {
            let small = format!("https://gfx.nrk.no/{key}-300");
            let big = format!("https://gfx.nrk.no/{key}-600");
            art_stub(
                "gammel",
                key,
                age as i64 * 60,
                &[(small.as_str(), 300), (big.as_str(), 600)],
                &[],
            )
        })
        .collect();
    answer_stubs(&app, "gammel", &stubs);
    answer_manifests(&app, "podcast", &keys);
    check(&app, id, t0()).await;
    // As an older server left it: the 300 px pick, tried, and the migration's flag.
    sqlx::query(
        "UPDATE music_items SET art_url = replace(art_url, '-600', '-300'), art_from = \
         replace(art_url, '-600', '-300'), art_state = 'own', art_hash = ? WHERE entry_id = ?",
    )
    .bind("a".repeat(64))
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    sqlx::query("UPDATE music_listings SET art_refresh = 1 WHERE entry_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    app.fetch.clear_calls();
    let report = check(&app, id, t0() + TimeDelta::hours(12)).await;
    // The routine 5, then the refresh's full page.
    assert_eq!(report.requests, 2, "{:?}", app.fetch.calls.lock().unwrap());
    let list = items(&app, id).await;
    assert!(
        list.iter()
            .all(|i| i.art_url.as_deref().unwrap().ends_with("-600"))
    );
    assert!(list.iter().all(|i| i.art_from.is_none()), "untried");
    assert_eq!(list.len(), 3, "nothing added or dropped");
    assert!(!listing(&app, id).await.art_refresh);
    app.fetch.clear_calls();
    let report = check(&app, id, t0() + TimeDelta::hours(24)).await;
    assert_eq!(report.requests, 1, "the routine walk as before");
}

// ------------------------------------------------------------------------------------------------
// The store
// ------------------------------------------------------------------------------------------------

/// The store's lock is held from writing the file to the commit (photos' rule), so a prune never
/// deletes a picture between the two.
#[tokio::test]
async fn the_store_lock_is_held_until_the_commit() {
    let app = TestApp::new().await;
    let feed = "https://example.org/lock.rss";
    let id = rss_entry(&app, feed).await;
    let url = "https://example.org/l.jpg";
    app.fetch
        .answer(feed, 200, art_feed(None, &[("l", Some(url))]));
    app.fetch.answer(url, 200, png(0b0100_0100, 300, 300));
    let guard = crate::photos::MUSIC_ART.lock().await;
    let state = app.state.clone();
    let task = tokio::spawn(async move { sweep::check_entry(&state, id, t0()).await });
    tokio::time::sleep(std::time::Duration::from_millis(500)).await;
    assert!(!task.is_finished(), "waits for the store's lock");
    assert!(items(&app, id).await.is_empty(), "nothing committed yet");
    drop(guard);
    task.await.unwrap().unwrap().unwrap();
    let hash = items(&app, id).await[0].art_hash.clone().unwrap();
    assert!(stored(&app, &hash));
}

/// Pruned with the items (after a delete), never in a backup zip, and after a restore the rows
/// are forgotten and the next check fetches the picture again.
#[tokio::test]
async fn episode_art_is_pruned_not_backed_up_and_fetched_again_after_a_restore() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let feed = "https://example.org/store.rss";
    let id = rss_entry(&app, feed).await;
    let url = "https://example.org/s.jpg";
    app.fetch
        .answer(feed, 200, art_feed(None, &[("s", Some(url))]));
    app.fetch.answer(url, 200, png(0b0010_0010, 300, 300));
    check(&app, id, t0()).await;
    let hash = items(&app, id).await[0].art_hash.clone().unwrap();
    assert!(stored(&app, &hash));

    // Not in a backup.
    let backups = tempfile::tempdir().unwrap();
    let db = backups.path().join("snapshot.db");
    std::fs::write(&db, b"db").unwrap();
    let zip_path = backups.path().join("backup-1.zip");
    crate::handlers::backups::build_backup_zip(
        db.to_str().unwrap(),
        zip_path.to_str().unwrap(),
        &app.state.photo_dir,
        &app.state.wallpaper_dir,
        &app.state.music_cover_dir,
    )
    .unwrap();
    let mut archive = zip::ZipArchive::new(std::fs::File::open(&zip_path).unwrap()).unwrap();
    assert!(
        (0..archive.len()).all(|i| !archive.by_index(i).unwrap().name().starts_with("music_art")),
        "never backed up"
    );

    // A restore: the file isn't there and no zip has it - the rows forget it, the next check
    // fetches it again.
    std::fs::remove_file(app.state.music_art_dir.join(format!("{hash}.jpg"))).unwrap();
    crate::photos::recover_missing(&app.state, backups.path()).await;
    let item = &items(&app, id).await[0];
    assert_eq!(
        (item.art_hash.as_ref(), item.art_from.as_ref()),
        (None, None)
    );
    app.fetch.clear_calls();
    check(&app, id, t0() + TimeDelta::hours(6)).await;
    assert_eq!(calls_to(&app, url), 1);
    assert!(stored(&app, &hash));

    // Deleting the entry prunes its pictures.
    let res = app
        .request_form(
            Method::POST,
            &format!("/music/entries/{id}/delete"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.location(), Some("/music#entries"));
    assert!(!stored(&app, &hash));
}

/// The card's count line says how many listed episodes have their own picture.
#[tokio::test]
async fn the_card_counts_episodes_with_art() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let feed = "https://example.org/card.rss";
    let id = rss_entry(&app, feed).await;
    app.fetch.answer(
        feed,
        200,
        art_feed(
            None,
            &[("a", Some("https://example.org/ca.jpg")), ("b", None)],
        ),
    );
    app.fetch.answer(
        "https://example.org/ca.jpg",
        200,
        png(0b0001_1000, 300, 300),
    );
    check(&app, id, t0()).await;
    let page = app.get_page("/music", &cookie).await.text();
    assert!(page.contains("2 episodes · 1 with episode art"), "{page}");
    assert!(page.contains("Episode pictures are fetched a few at a time (at most 400 a day)."));
}

/// The log line's art part.
#[test]
fn the_log_line_counts_own_and_same() {
    let report = sweep::CheckReport {
        art_own: 3,
        art_same: 4,
        ..Default::default()
    };
    assert_eq!(report.art_line(), ", art 7 (3 own, 4 same)");
    assert_eq!(sweep::CheckReport::default().art_line(), "");
}

/// Not every music item came from NRK or RSS: a Storytel part is `ok` with no source URL (and no
/// picture URL). The art pass skips such rows, and they take no place in the window.
#[test]
fn items_without_a_source_url_or_a_picture_url_are_skipped() {
    let row = |url: Option<&str>, art: Option<&str>, state: &str| sweep::Item {
        key: "part-1".to_string(),
        state: state.to_string(),
        title: Some("Del 1".to_string()),
        url: url.map(str::to_string),
        hls: false,
        duration_ms: None,
        art_url: art.map(str::to_string),
        published_at: None,
        available_until: None,
        first_seen_at: "2026-10-12T00:00:00.000Z".to_string(),
        attempts: 0,
        reported_at: None,
        rechecked_at: None,
        recheck: false,
        art_hash: None,
        art_from: None,
        art_state: None,
        art_print: None,
        art_at: None,
        art_fetched_at: None,
        art_fails: 0,
    };
    let audio = Some("https://media.example.org/1.mp3");
    let art = Some("https://media.example.org/1.jpg");
    assert!(sweep::art_candidate(&row(audio, art, "ok")));
    assert!(
        !sweep::art_candidate(&row(None, None, "ok")),
        "a Storytel part"
    );
    assert!(
        !sweep::art_candidate(&row(None, art, "ok")),
        "no source URL"
    );
    assert!(
        !sweep::art_candidate(&row(audio, None, "ok")),
        "no picture of its own"
    );
    assert!(!sweep::art_candidate(&row(audio, art, "gone")));
    assert!(!sweep::art_candidate(&row(audio, art, "pending")));
}
