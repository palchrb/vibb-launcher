//! Episode art (design 27 §1 and §5 step 1, with its design QA M2, M3 and L1): the sweep's art
//! pass on canned answers with the fake clock - own pictures by hash, the series picture by URL
//! and by print, a result reused across entries, failures and retries, no picture budget and the
//! play order (due again at once until done), the backlog, a new cover's re-judging, the 16:9
//! picture, the one-time NRK refresh, the store (lock, prune, no backup, recovery), the cover
//! route's scoping and the card's count.

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
pub(super) fn picture(seed: u8, w: u32, h: u32) -> image::DynamicImage {
    image::DynamicImage::ImageRgb8(image::RgbImage::from_fn(w, h, |x, y| {
        let band = y * 8 / h;
        let t = (x * 255 / (w - 1)) as u8;
        let v = if (seed >> band) & 1 == 1 { t } else { 255 - t };
        image::Rgb([v, v / 2, 255 - v])
    }))
}

pub(super) fn png(seed: u8, w: u32, h: u32) -> Vec<u8> {
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

/// Code QA L4: the twin merge needs the same colours and a picture with structure. Episode
/// pictures that are one background with a small title each, or one design in other colours,
/// stay separate pictures (a wrong merge would show another episode's picture).
#[tokio::test]
async fn titles_on_one_background_and_recoloured_pictures_are_not_merged() {
    let app = TestApp::new().await;
    let feed = "https://example.org/titles.rss";
    let id = rss_entry(&app, feed).await;
    let titled = |x: u32, colour: [u8; 3]| {
        let image = image::DynamicImage::ImageRgb8(image::RgbImage::from_fn(512, 512, |px, py| {
            if (x..x + 90).contains(&px) && (400..416).contains(&py) {
                image::Rgb([10, 10, 10])
            } else {
                image::Rgb(colour)
            }
        }));
        let mut out = std::io::Cursor::new(Vec::new());
        image.write_to(&mut out, image::ImageFormat::Png).unwrap();
        out.into_inner()
    };
    let recoloured = |seed: u8| {
        let image = picture(seed, 400, 400).to_rgb8();
        let swapped = image::DynamicImage::ImageRgb8(image::RgbImage::from_fn(400, 400, |x, y| {
            let [r, g, b] = image.get_pixel(x, y).0;
            image::Rgb([b, r, g])
        }));
        let mut out = std::io::Cursor::new(Vec::new());
        swapped.write_to(&mut out, image::ImageFormat::Png).unwrap();
        out.into_inner()
    };
    let pictures: Vec<(String, Vec<u8>)> = vec![
        ("t1".into(), titled(60, [40, 90, 160])),
        ("t2".into(), titled(300, [40, 90, 160])),
        ("c1".into(), png(0b1001_0110, 400, 400)),
        ("c2".into(), recoloured(0b1001_0110)),
        // The same picture again under another URL and size: one picture.
        ("c3".into(), png(0b1001_0110, 500, 500)),
    ];
    let urls: Vec<String> = pictures
        .iter()
        .map(|(g, _)| format!("https://example.org/{g}.png"))
        .collect();
    let feed_items: Vec<(&str, Option<&str>)> = pictures
        .iter()
        .zip(&urls)
        .map(|((g, _), u)| (g.as_str(), Some(u.as_str())))
        .collect();
    app.fetch.answer(feed, 200, art_feed(None, &feed_items));
    for ((_, bytes), url) in pictures.iter().zip(&urls) {
        app.fetch.answer(url, 200, bytes.clone());
    }
    check(&app, id, t0()).await;
    let list = items(&app, id).await;
    let hash = |n: usize| list[n].art_hash.clone().unwrap();
    assert_ne!(hash(0), hash(1), "two titles on one background");
    assert_ne!(hash(2), hash(3), "one design in other colours");
    assert_eq!(hash(2), hash(4), "the same picture is still one");
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
    // An undecodable picture counts like a 4xx.
    app.fetch.answer(bad, 200, "not a picture");
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

/// No picture budget (the user, 2026-10-19: all pictures ASAP): one check fetches every picture,
/// in play order (newest first for a feed), and the next check's new items all theirs.
#[tokio::test]
async fn a_check_fetches_every_picture_in_play_order() {
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
    assert!(list.iter().all(|i| i.art_from.is_some()), "all 30 tried");
    let order: Vec<String> = app
        .fetch
        .calls
        .lock()
        .unwrap()
        .iter()
        .filter(|c| c.ends_with(".jpg"))
        .cloned()
        .collect();
    assert_eq!(order.len(), 30);
    assert_eq!(order.first(), Some(&urls[29]), "the newest first");
    assert_eq!(order.last(), Some(&urls[0]));
    assert_eq!(listing(&app, id).await.art_more, 0, "nothing left");

    // Within the day, no count holds anything back: ten new items, ten requests.
    let new_guids: Vec<String> = (30..40).map(|n| format!("i{n:02}")).collect();
    let new_urls: Vec<String> = (30..40)
        .map(|n| format!("https://example.org/n{n}.jpg"))
        .collect();
    let mut more = feed_items.clone();
    more.extend(
        new_guids
            .iter()
            .zip(&new_urls)
            .map(|(g, u)| (g.as_str(), Some(u.as_str()))),
    );
    app.fetch.answer(feed, 200, art_feed(None, &more));
    for url in &new_urls {
        app.fetch.answer(url, 404, "");
    }
    app.fetch.clear_calls();
    check(&app, id, t0() + TimeDelta::hours(1)).await;
    assert_eq!(calls_starting(&app, "https://example.org/n"), 10);
}

/// Code QA M1: a feed whose picture URLs change at every fetch (`?t=<n>`, a signed or
/// cache-busting query) costs its requests once; the same picture under a new query keeps its
/// result with no request. A picture moved to another path is fetched again at most weekly.
#[tokio::test]
async fn churning_picture_urls_are_not_fetched_again() {
    let app = TestApp::new().await;
    let feed = "https://example.org/churn.rss";
    let id = rss_entry(&app, feed).await;
    let answer = |app: &TestApp, n: u32, b_path: &str| {
        let a = format!("https://img.example.org/a.jpg?t={n}");
        let b = format!("https://img.example.org/{b_path}?t={n}");
        app.fetch.answer(
            feed,
            200,
            art_feed(None, &[("a", Some(a.as_str())), ("b", Some(b.as_str()))]),
        );
        app.fetch.answer(&a, 200, png(0b0011_0011, 300, 300));
        app.fetch.answer(&b, 200, png(0b1100_1100, 300, 300));
    };
    answer(&app, 1, "b.jpg");
    check(&app, id, t0()).await;
    assert_eq!(calls_starting(&app, "https://img.example.org/"), 2);
    let before = items(&app, id).await;
    for n in 2..6 {
        answer(&app, n, "b.jpg");
        check(&app, id, t0() + TimeDelta::hours(4 * i64::from(n))).await;
    }
    assert_eq!(
        calls_starting(&app, "https://img.example.org/"),
        2,
        "no image request after the first check"
    );
    let after = items(&app, id).await;
    assert_eq!(after[0].art_hash, before[0].art_hash);
    assert_eq!(
        after[0].art_from.as_deref(),
        Some("https://img.example.org/a.jpg?t=5")
    );
    // b moves to another path: not within a week of its result, then once.
    answer(&app, 6, "b2.jpg");
    check(&app, id, t0() + TimeDelta::days(3)).await;
    assert_eq!(calls_starting(&app, "https://img.example.org/b2.jpg"), 0);
    check(&app, id, t0() + TimeDelta::days(8)).await;
    assert_eq!(calls_starting(&app, "https://img.example.org/b2.jpg"), 1);
    check(&app, id, t0() + TimeDelta::days(8) + TimeDelta::hours(6)).await;
    assert_eq!(calls_starting(&app, "https://img.example.org/b2.jpg"), 1);
}

/// Code QA M2: an image host that is down ends the art pass after 3 network errors (or 5xx) in a
/// row, and doesn't use up the pictures' tries; a 4xx does.
#[tokio::test]
async fn a_dead_image_host_ends_the_pass_and_costs_no_tries() {
    let app = TestApp::new().await;
    let feed = "https://example.org/dead.rss";
    let id = rss_entry(&app, feed).await;
    let urls: Vec<String> = (0..8)
        .map(|n| format!("https://cdn.example.org/{n}.jpg"))
        .collect();
    let guids: Vec<String> = (0..8).map(|n| format!("d{n}")).collect();
    let feed_items: Vec<(&str, Option<&str>)> = guids
        .iter()
        .zip(&urls)
        .map(|(g, u)| (g.as_str(), Some(u.as_str())))
        .collect();
    app.fetch.answer(feed, 200, art_feed(None, &feed_items));
    for url in &urls {
        app.fetch.fail(
            url,
            crate::music_net::FetchError::Network("down".to_string()),
        );
    }
    let report = check(&app, id, t0()).await;
    assert_eq!(report.error, None);
    assert_eq!(
        calls_starting(&app, "https://cdn.example.org/"),
        3,
        "stops after 3"
    );
    let no_tries = |list: &[sweep::Item]| {
        list.iter()
            .all(|i| i.art_fails == 0 && i.art_state.is_none() && i.art_hash.is_none())
    };
    let list = items(&app, id).await;
    assert!(no_tries(&list), "no tries used");
    let waiting = list.iter().filter(|i| i.art_from.is_some()).count();
    assert_eq!(waiting, 3, "the three asked wait a day");
    // A 503 is the host's trouble too; the next three are asked.
    for url in &urls {
        app.fetch.answer(url, 503, "");
    }
    app.fetch.clear_calls();
    check(&app, id, t0() + TimeDelta::hours(6)).await;
    assert_eq!(calls_starting(&app, "https://cdn.example.org/"), 3);
    assert!(no_tries(&items(&app, id).await));
    // Back a day after the last try, but one picture is gone (404): that one counts, the others are fetched.
    for url in &urls {
        app.fetch.answer(url, 200, png(0b0101_0011, 300, 300));
    }
    app.fetch.answer(&urls[7], 404, "");
    check(&app, id, t0() + TimeDelta::hours(31)).await;
    let list = items(&app, id).await;
    assert_eq!(list[7].art_fails, 1);
    assert!(
        list[..7]
            .iter()
            .all(|i| i.art_state.as_deref() == Some("own"))
    );
}

/// Every `ok` item is tried, past the 200 design 27 stopped at (design 39 §2.4: no window); a
/// `gone` item keeps what it has and gets nothing new.
#[tokio::test]
async fn every_ok_item_in_play_order_and_gone_items_get_nothing_new() {
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
    for url in &urls {
        app.fetch.answer(url, 404, "");
    }
    app.fetch.answer(feed, 200, art_feed(None, &feed_items));
    let list = items(&app, id).await;
    assert!(list.is_empty());
    check(&app, id, t0()).await;
    let list = items(&app, id).await;
    let tried: Vec<bool> = list.iter().map(|i| i.art_from.is_some()).collect();
    assert!(tried.iter().all(|t| *t), "all 210 are tried");

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

/// A feed of `n` items oldest first, each with its own picture URL (answered 404: tried, nothing
/// stored), as `(guid, url)` pairs.
fn long_feed(app: &TestApp, feed: &str, prefix: &str, n: usize) -> Vec<(String, String)> {
    let pairs: Vec<(String, String)> = (0..n)
        .map(|i| {
            (
                format!("{prefix}{i:03}"),
                format!("https://img.example.org/{prefix}/{i}.jpg"),
            )
        })
        .collect();
    answer_long_feed(app, feed, &pairs);
    pairs
}

fn answer_long_feed(app: &TestApp, feed: &str, pairs: &[(String, String)]) {
    let feed_items: Vec<(&str, Option<&str>)> = pairs
        .iter()
        .map(|(g, u)| (g.as_str(), Some(u.as_str())))
        .collect();
    app.fetch.answer(feed, 200, art_feed(None, &feed_items));
    for (_, url) in pairs {
        app.fetch.answer(url, 404, "");
    }
}

/// Design 39 §2.4 with the 2026-10-19 rule: 300 items are more than one check's own budget of
/// requests ([sweep::MAX_CHECK_REQUESTS]); the check stops with the rest untried and says how many,
/// the entry is due again at once ([sweep::Why::Art], not at its interval), and the next check
/// finishes them - the near end first (a feed newest first), whatever the entry's offline choice.
#[tokio::test]
async fn due_again_at_once_until_the_pictures_are_done() {
    let app = TestApp::new().await;
    let feed = "https://example.org/300.rss";
    let id = rss_entry(&app, feed).await;
    sqlx::query("UPDATE music_entries SET cache = 0 WHERE id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    long_feed(&app, feed, "l", 300);
    check(&app, id, t0()).await;
    let tried =
        |list: &[sweep::Item]| -> Vec<bool> { list.iter().map(|i| i.art_from.is_some()).collect() };
    let first = tried(&items(&app, id).await);
    let done = first.iter().filter(|t| **t).count();
    // The feed's GET is one of the check's requests.
    assert_eq!(done, sweep::MAX_CHECK_REQUESTS as usize - 1);
    assert!(first[300 - done..].iter().all(|t| *t), "the newest first");
    let left = 300 - done as i64;
    assert_eq!(listing(&app, id).await.art_more, left);
    let soon = t0() + TimeDelta::seconds(5);
    let due = sweep::due(&app.db, soon).await.unwrap();
    let again = due
        .iter()
        .find(|s| s.entry_id == id)
        .expect("due again at once");
    assert_eq!(again.why, sweep::Why::Art);

    check(&app, id, soon).await;
    let all = tried(&items(&app, id).await);
    assert!(all.iter().all(|t| *t), "all 300 after two checks");
    assert_eq!(listing(&app, id).await.art_more, 0);
    let later = soon + TimeDelta::seconds(5);
    assert!(
        sweep::due(&app.db, later)
            .await
            .unwrap()
            .iter()
            .all(|s| s.entry_id != id),
        "done: back to its interval"
    );
    assert_eq!(
        calls_starting(&app, "https://img.example.org/"),
        300,
        "each once"
    );
}

/// A dead image host ends the pass (3 network errors in a row) without bringing the entry back at
/// once: no picture came, so it waits for its interval (a host that answered and then blipped
/// does come back at once).
#[tokio::test]
async fn a_dead_host_is_not_due_again_at_once() {
    let app = TestApp::new().await;
    let feed = "https://example.org/dead.rss";
    let id = rss_entry(&app, feed).await;
    let pairs = long_feed(&app, feed, "d", 20);
    for (_, url) in &pairs {
        app.fetch.fail(
            url,
            crate::music_net::FetchError::Network("down".to_string()),
        );
    }
    check(&app, id, t0()).await;
    assert_eq!(calls_starting(&app, "https://img.example.org/"), 3);
    assert_eq!(listing(&app, id).await.art_more, 0);
    assert!(
        sweep::due(&app.db, t0() + TimeDelta::seconds(5))
            .await
            .unwrap()
            .iter()
            .all(|s| s.entry_id != id)
    );
}

/// Q26 (the r5 investigation): one bad picture never fails the check or raises the listing's
/// `failures` - a 404 is one failed try, the rest are fetched. The hold was the dead-host rule:
/// three pictures on a dead host (the newest, so first) ended the pass for every other host's
/// pictures and, with no picture in yet, sent the entry to its regular check. Now only that host
/// waits.
#[tokio::test]
async fn a_bad_picture_or_a_dead_host_holds_no_other_picture() {
    let app = TestApp::new().await;
    let feed = "https://example.org/mixed.rss";
    let id = rss_entry(&app, feed).await;
    let good: Vec<String> = (0..4)
        .map(|n| format!("https://img.example.org/{n}.jpg"))
        .collect();
    let dead: Vec<String> = (0..3)
        .map(|n| format!("https://old-cdn.example.org/{n}.jpg"))
        .collect();
    let bad = "https://img.example.org/gone.jpg";
    // Oldest first: the good ones, the 404, then the three newest on the dead host.
    let mut urls: Vec<&str> = good.iter().map(String::as_str).collect();
    urls.push(bad);
    urls.extend(dead.iter().map(String::as_str));
    let guids: Vec<String> = (0..urls.len()).map(|n| format!("m{n}")).collect();
    let feed_items: Vec<(&str, Option<&str>)> = guids
        .iter()
        .zip(&urls)
        .map(|(g, u)| (g.as_str(), Some(*u)))
        .collect();
    app.fetch.answer(feed, 200, art_feed(None, &feed_items));
    for (n, url) in good.iter().enumerate() {
        app.fetch.answer(url, 200, png(1 << n, 300, 300));
    }
    app.fetch.answer(bad, 404, "");
    for url in &dead {
        app.fetch.fail(
            url,
            crate::music_net::FetchError::Network("down".to_string()),
        );
    }
    let report = check(&app, id, t0()).await;
    assert_eq!(report.error, None);
    let list = items(&app, id).await;
    assert!(
        list[..4]
            .iter()
            .all(|i| i.art_state.as_deref() == Some("own")),
        "{:?}",
        art_states(&list)
    );
    assert_eq!(
        (list[4].art_state.as_deref(), list[4].art_fails),
        (Some("failed"), 1)
    );
    assert!(
        list[5..]
            .iter()
            .all(|i| i.art_state.is_none() && i.art_fails == 0)
    );
    assert_eq!(calls_starting(&app, "https://old-cdn.example.org/"), 3);
    let stored = listing(&app, id).await;
    assert_eq!((stored.failures, stored.art_more), (0, 0));
    // The dead host never answered: it waits for the regular check, not again at once.
    assert!(
        sweep::due(&app.db, t0() + TimeDelta::seconds(5))
            .await
            .unwrap()
            .iter()
            .all(|s| s.entry_id != id)
    );
}

/// Code QA L3: with every host failing (a DNS or uplink fault after a good listing) the pass ends
/// after [sweep::ART_PASS_FAILURES] troubles over all hosts, not three per host; the rest wait for
/// the regular check, untried.
#[tokio::test]
async fn many_dead_hosts_end_the_pass() {
    let app = TestApp::new().await;
    let feed = "https://example.org/hosts.rss";
    let id = rss_entry(&app, feed).await;
    let urls: Vec<String> = (0..10)
        .map(|n| format!("https://cdn{n}.example.org/p.jpg"))
        .collect();
    let guids: Vec<String> = (0..10).map(|n| format!("h{n}")).collect();
    let feed_items: Vec<(&str, Option<&str>)> = guids
        .iter()
        .zip(&urls)
        .map(|(g, u)| (g.as_str(), Some(u.as_str())))
        .collect();
    app.fetch.answer(feed, 200, art_feed(None, &feed_items));
    for url in &urls {
        app.fetch.fail(
            url,
            crate::music_net::FetchError::Network("no route".to_string()),
        );
    }
    check(&app, id, t0()).await;
    assert_eq!(
        calls_starting(&app, "https://cdn"),
        sweep::ART_PASS_FAILURES as usize
    );
    let list = items(&app, id).await;
    assert_eq!(
        list.iter().filter(|i| i.art_from.is_none()).count(),
        10 - sweep::ART_PASS_FAILURES as usize,
        "the rest untried"
    );
    assert_eq!(listing(&app, id).await.art_more, 0);
}

/// A host that answered in this pass and then failed three times in a row (a blip) leaves its
/// other pictures untried and brings the entry back at once (code QA M2's rule, per host).
#[tokio::test]
async fn a_host_that_blips_brings_the_entry_back_at_once() {
    let app = TestApp::new().await;
    let feed = "https://example.org/blip.rss";
    let id = rss_entry(&app, feed).await;
    let pairs = long_feed(&app, feed, "b", 6);
    // Newest first: b005 answers, b004-b002 fail, b001 and b000 are left.
    app.fetch
        .answer(&pairs[5].1, 200, png(0b0011_0101, 300, 300));
    for (_, url) in &pairs[2..5] {
        app.fetch.fail(
            url,
            crate::music_net::FetchError::Network("blip".to_string()),
        );
    }
    check(&app, id, t0()).await;
    assert_eq!(calls_starting(&app, "https://img.example.org/"), 4);
    assert_eq!(listing(&app, id).await.art_more, 2);
    let due = sweep::due(&app.db, t0() + TimeDelta::seconds(5))
        .await
        .unwrap();
    assert_eq!(
        due.iter().find(|s| s.entry_id == id).map(|s| s.why),
        Some(sweep::Why::Art)
    );
}

/// qa-art-asap-code L3: a check whose listing spent the whole request budget sent no picture
/// request, so it doesn't come back at once only to spend it on the listing again.
#[tokio::test]
async fn a_check_with_no_picture_request_is_not_due_again_at_once() {
    let app = TestApp::new().await;
    app.state
        .music_sweep
        .set_limits(1, std::time::Duration::ZERO);
    let feed = "https://example.org/spent.rss";
    let id = rss_entry(&app, feed).await;
    long_feed(&app, feed, "s", 10);
    check(&app, id, t0()).await;
    assert_eq!(items(&app, id).await.len(), 10);
    assert_eq!(calls_starting(&app, "https://img.example.org/"), 0);
    assert_eq!(listing(&app, id).await.art_more, 0);
    assert!(
        sweep::due(&app.db, t0() + TimeDelta::seconds(5))
            .await
            .unwrap()
            .iter()
            .all(|s| s.entry_id != id)
    );
}

/// A simulated day of 30 NRK podcasts with 100 episodes each (every one with its own picture) and
/// 10 feeds: with no picture budget (2026-10-19), the sweep's first passes fetch every picture,
/// once each - each check within its own request budget, an unfinished entry due again at once -
/// and nothing more for the rest of the day.
#[tokio::test]
async fn a_day_of_backfill_fetches_every_picture_once() {
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
    let images = |app: &TestApp| {
        calls_starting(app, "https://gfx.nrk.no/ep/")
            + calls_starting(app, "https://img.example.org/")
    };
    // The first passes: until nothing is due at t0 (a pass checks one entry at most 5 times).
    for _ in 0..20 {
        assert!(sweep::run_pass(&app.state).await);
        if sweep::due(&app.db, t0()).await.unwrap().is_empty() {
            break;
        }
    }
    assert!(sweep::due(&app.db, t0()).await.unwrap().is_empty());
    assert_eq!(images(&app), 30 * 100 + 10 * 50, "every picture, once");
    let untried: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM music_items WHERE art_url IS NOT NULL AND art_fetched_at IS NULL",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(untried, 0);
    // The rest of the day: the sweep runs on; nothing is fetched again.
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
    assert_eq!(images(&app), 30 * 100 + 10 * 50);
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

/// Code QA L2: with the backlog off, a new item that wasn't fetched in its own first check (the
/// tests' cap of 0 stands in for a missed pass) is still new at the next check; older items still
/// wait.
#[tokio::test]
async fn with_the_backlog_off_a_new_item_that_missed_its_check_stays_new() {
    let app = TestApp::new().await;
    let feed = "https://example.org/late.rss";
    let id = rss_entry(&app, feed).await;
    let old_items = [
        ("o1", Some("https://example.org/o1.jpg")),
        ("o2", Some("https://example.org/o2.jpg")),
    ];
    app.fetch.answer(feed, 200, art_feed(None, &old_items));
    app.state.music_sweep.set_art_per_check(0);
    check(&app, id, t0()).await;
    app.state.music_sweep.set_art_per_check(u32::MAX);
    sqlx::query("UPDATE music_listings SET art_backlog = 0, art_backlog_at = ? WHERE entry_id = ?")
        .bind(stamp(t0() + TimeDelta::minutes(30)))
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    for url in ["o1", "o2", "n1"] {
        app.fetch.answer(
            &format!("https://example.org/{url}.jpg"),
            200,
            png(0b0110_1001, 300, 300),
        );
    }
    let mut with_new = old_items.to_vec();
    with_new.push(("n1", Some("https://example.org/n1.jpg")));
    app.fetch.answer(feed, 200, art_feed(None, &with_new));
    // No picture is fetched when n1 first shows up.
    app.state.music_sweep.set_art_per_check(0);
    check(&app, id, t0() + TimeDelta::hours(1)).await;
    assert_eq!(calls_to(&app, "https://example.org/n1.jpg"), 0);
    app.state.music_sweep.set_art_per_check(u32::MAX);
    check(&app, id, t0() + TimeDelta::hours(2)).await;
    assert_eq!(calls_to(&app, "https://example.org/n1.jpg"), 1, "still new");
    assert_eq!(
        calls_starting(&app, "https://example.org/o"),
        0,
        "the backlog waits"
    );
    // n1 is the entry's own picture: the backlog is on again from here (§1.4).
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

/// Code QA L1: an NRK entry listed before the update (no 16:9 reference yet, the refresh flag
/// set) reads its root at its next check, before the refresh and the backfill judge its episodes:
/// an episode on the 16:9 fallback is the series picture from the start, never a short-lived
/// carousel. The root is read once, not at every check.
#[tokio::test]
async fn an_existing_nrk_entry_reads_its_wide_picture_before_the_refresh() {
    let app = TestApp::new().await;
    let id = entry(
        &app,
        "https://radio.nrk.no/podkast/gml",
        "Gml",
        "auto",
        CREATED,
    )
    .await;
    let square = "https://gfx.nrk.no/gml-sq600";
    let wide = "https://gfx.nrk.no/gml-wide960";
    let root = format!("{PSAPI}/radio/catalog/podcast/gml");
    app.fetch.answer(
        &root,
        200,
        json!({"series": {
            "titles": {"title": "Gml"},
            "squareImage": [{"url": square, "width": 600}],
            "image": [{"url": wide, "width": 960}]
        }})
        .to_string(),
    );
    let episode_wide = "https://gfx.nrk.no/gml-wide600";
    let stubs = vec![art_stub("gml", "g1", 0, &[], &[(episode_wide, 600)])];
    answer_stubs(&app, "gml", &stubs);
    answer_manifests(&app, "podcast", &["g1".into()]);
    app.fetch.answer(square, 200, png(0b0000_0001, 600, 600));
    app.fetch.answer(wide, 200, png(0b1110_0000, 960, 540));
    app.fetch
        .answer(episode_wide, 200, png(0b1110_0000, 600, 338));
    // Listed by an older server: no art columns, the migration's flag.
    app.state.music_sweep.set_art_per_check(0);
    check(&app, id, t0()).await;
    app.state.music_sweep.set_art_per_check(u32::MAX);
    sqlx::query(
        "UPDATE music_listings SET cover_alt_url = NULL, cover_alt_print = NULL, \
         cover_alt_at = NULL, art_refs = NULL, art_refresh = 1 WHERE entry_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    sqlx::query("UPDATE music_items SET art_from = NULL, art_state = NULL WHERE entry_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    app.fetch.clear_calls();
    check(&app, id, t0() + TimeDelta::hours(12)).await;
    assert_eq!(calls_to(&app, &root), 1, "the root once");
    assert_eq!(calls_to(&app, wide), 1);
    let item = &items(&app, id).await[0];
    assert_eq!(item.art_state.as_deref(), Some("same"), "never own");
    assert!(listing(&app, id).await.cover_alt_at.is_some());
    app.fetch.clear_calls();
    check(&app, id, t0() + TimeDelta::hours(24)).await;
    assert_eq!(calls_to(&app, &root), 0, "not again before the week");
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
    // The root (for the references, code QA L1), the routine 5, then the refresh's full page.
    assert_eq!(report.requests, 3, "{:?}", app.fetch.calls.lock().unwrap());
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
/// deletes a picture between the two: the check is stopped right before its commit (code QA L3),
/// the lock is taken, and a prune started then waits and keeps the file.
#[tokio::test]
async fn the_store_lock_is_held_until_the_commit() {
    let app = TestApp::new().await;
    let feed = "https://example.org/lock.rss";
    let id = rss_entry(&app, feed).await;
    let url = "https://example.org/l.jpg";
    app.fetch
        .answer(feed, 200, art_feed(None, &[("l", Some(url))]));
    app.fetch.answer(url, 200, png(0b0100_0100, 300, 300));
    let (reached, go) = app.state.music_sweep.pause_before_commit();
    let state = app.state.clone();
    let task = tokio::spawn(async move { sweep::check_entry(&state, id, t0()).await });
    reached.await.unwrap();
    let files: Vec<_> = std::fs::read_dir(&*app.state.music_art_dir)
        .unwrap()
        .collect();
    assert_eq!(files.len(), 1, "written before the commit");
    assert!(items(&app, id).await.is_empty(), "nothing committed yet");
    // One poll: the lock is taken.
    assert!(
        tokio::time::timeout(std::time::Duration::ZERO, crate::photos::MUSIC_ART.lock())
            .await
            .is_err(),
        "the store's lock is held"
    );
    let state = app.state.clone();
    let prune = tokio::spawn(async move {
        crate::photos::MUSIC_ART
            .prune(&state.db, &state.music_art_dir)
            .await
    });
    tokio::task::yield_now().await;
    go.send(()).unwrap();
    task.await.unwrap().unwrap().unwrap();
    prune.await.unwrap();
    let hash = items(&app, id).await[0].art_hash.clone().unwrap();
    assert!(stored(&app, &hash), "the prune ran after the commit");
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
        &app.state.avatar_dir,
    )
    .unwrap();
    let mut archive = zip::ZipArchive::new(std::fs::File::open(&zip_path).unwrap()).unwrap();
    assert!(
        (0..archive.len()).all(|i| !archive.by_index(i).unwrap().name().starts_with("music_art")),
        "never backed up"
    );

    // A restore: the file isn't there and no zip has it - the rows forget it, the next check
    // fetches it again.
    let version = listing(&app, id).await.version.unwrap();
    std::fs::remove_file(app.state.music_art_dir.join(format!("{hash}.jpg"))).unwrap();
    crate::photos::recover_missing(&app.state, backups.path()).await;
    // The listing's version is stored again, from the rows the route serves (code QA L6).
    let restamped = listing(&app, id).await.version.unwrap();
    assert_ne!(restamped, version);
    let (phone, token) = app.enrolled_device("Ella").await;
    tick_on(&app, phone, id).await;
    assert_eq!(
        listing_doc(&app, &token, id).await["version"],
        restamped.as_str()
    );
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
    let page = app
        .get_page(&format!("/music/entries/{id}"), &cookie)
        .await
        .text();
    assert!(page.contains("2 episodes · 1 with episode art"), "{page}");
    let settings = app.get_page("/music/settings", &cookie).await.text();
    assert!(
        !settings.contains("at most 400 a day"),
        "the ⓘ is cut to the one fact"
    );
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

/// Not every music item came from NRK or RSS: a Storytel book is `ok` with no source URL - the
/// phone mints its audio - and its picture URL the shelf's cover (design 26b step 4a). The entry's
/// link tells it from an NRK/RSS item without a URL, which the art pass skips without giving it a
/// place in the window; an item without a picture URL is skipped for any source.
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
        art_tone: None,
        art_at: None,
        art_fetched_at: None,
        art_fails: 0,
    };
    let feed = crate::music::Link::Rss {
        url: "https://example.org/feed.rss".to_string(),
    };
    let podcast = crate::music::Link::NrkPodcast {
        slug: "p".to_string(),
    };
    let storytel = crate::music::Link::Storytel {
        kind: crate::storytel::TargetKind::Series,
        id: "26175".to_string(),
    };
    let audio = Some("https://media.example.org/1.mp3");
    let art = Some("https://media.example.org/1.jpg");
    assert!(sweep::art_candidate(&row(audio, art, "ok"), &feed));
    assert!(sweep::art_candidate(&row(audio, art, "ok"), &podcast));
    assert!(
        !sweep::art_candidate(&row(None, art, "ok"), &feed),
        "no source URL"
    );
    assert!(
        !sweep::art_candidate(&row(None, art, "ok"), &podcast),
        "no source URL"
    );
    assert!(
        sweep::art_candidate(&row(None, art, "ok"), &storytel),
        "a Storytel book with its cover"
    );
    assert!(
        !sweep::art_candidate(&row(None, None, "ok"), &storytel),
        "a Storytel book without a cover"
    );
    assert!(
        !sweep::art_candidate(&row(audio, None, "ok"), &feed),
        "no picture of its own"
    );
    for link in [&feed, &storytel] {
        assert!(!sweep::art_candidate(&row(audio, art, "gone"), link));
        assert!(!sweep::art_candidate(&row(audio, art, "pending"), link));
        assert!(!sweep::art_candidate(&row(None, art, "pending"), link));
    }
}

/// Migration 0082 (server round 5 §4): an entry left with an untried picture under the old caps is
/// due again at once (`art_more`); one whose pictures were all tried, or whose only untried item
/// has no picture URL (or is gone), is not - the condition of `art_candidate`/`art_due`.
#[tokio::test]
async fn migration_0082_flags_entries_with_untried_pictures() {
    let (db, _dir) = super::cleanup::migrated_before(82).await;
    let category: i64 = sqlx::query_scalar("SELECT MIN(id) FROM music_categories")
        .fetch_one(&db)
        .await
        .unwrap();
    type Items<'a> = &'a [(Option<&'a str>, Option<&'a str>, &'a str)];
    // (entry, its items as (art_url, art_from, state))
    let entries: [(&str, Items); 4] = [
        (
            "untried",
            &[
                (Some("https://a/1.jpg"), Some("https://a/1.jpg"), "ok"),
                (Some("https://a/2.jpg"), None, "ok"),
            ],
        ),
        (
            "moved",
            &[(Some("https://a/new.jpg"), Some("https://a/old.jpg"), "ok")],
        ),
        (
            "all tried",
            &[(Some("https://a/1.jpg"), Some("https://a/1.jpg"), "ok")],
        ),
        (
            "no url or gone",
            &[(None, None, "ok"), (Some("https://a/3.jpg"), None, "gone")],
        ),
    ];
    for (name, items) in entries {
        let id: i64 = sqlx::query_scalar(
            "INSERT INTO music_entries (name, category_id, source, key) VALUES (?, ?, 'rss', ?) \
             RETURNING id",
        )
        .bind(name)
        .bind(category)
        .bind(name)
        .fetch_one(&db)
        .await
        .unwrap();
        sqlx::query("INSERT INTO music_listings (entry_id) VALUES (?)")
            .bind(id)
            .execute(&db)
            .await
            .unwrap();
        for (seq, (art_url, art_from, state)) in items.iter().enumerate() {
            sqlx::query(
                "INSERT INTO music_items (entry_id, key, seq, state, url, art_url, art_from, \
                 first_seen_at) VALUES (?, ?, ?, ?, 'https://a/e.mp3', ?, ?, datetime('now'))",
            )
            .bind(id)
            .bind(format!("k{seq}"))
            .bind(seq as i64)
            .bind(state)
            .bind(art_url)
            .bind(art_from)
            .execute(&db)
            .await
            .unwrap();
        }
    }
    super::cleanup::finish(&db).await;
    let flagged: Vec<(String, i64)> = sqlx::query_as(
        "SELECT e.name, l.art_more FROM music_listings l JOIN music_entries e ON e.id = l.entry_id \
         ORDER BY e.id",
    )
    .fetch_all(&db)
    .await
    .unwrap();
    let flagged: Vec<(&str, i64)> = flagged.iter().map(|(n, m)| (n.as_str(), *m)).collect();
    assert_eq!(
        flagged,
        [
            ("untried", 1),
            ("moved", 1),
            ("all tried", 0),
            ("no url or gone", 0)
        ]
    );
}
