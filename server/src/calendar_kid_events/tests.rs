//! Design 72b §10: the shared contract (`testdata/calendar_kid_events.json`), the canonical form, the writer, the
//! hrefs, and every guard of the write-back against the stateful fake CalDAV (`tests::fake_dav`).

use std::sync::Arc;

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::*;
use crate::tests::fake_dav::FakeDav;
use crate::tests::{TestApp, TestResponse};

const SERVER_URL: &str = "https://dav.example.org/";
const HOST: &str = "dav.example.org";
const USER: &str = "forelder@example.org";
/// A throwaway value, nothing like a real app password.
const PASSWORD: &str = "test-only-not-a-real-password";
const COLLECTION: &str = "https://dav.example.org/calendars/forelder/familie/";
const TAG: &str = "1a2b3c4d";
const CONTRACT: &str = "testdata/calendar_kid_events.json";

fn contract() -> Value {
    serde_json::from_str(&std::fs::read_to_string(CONTRACT).unwrap()).unwrap()
}

fn t0() -> DateTime<Utc> {
    KidTime::parse("20261102T120000Z", false).unwrap().instant()
}

fn uid(n: u32) -> String {
    format!("vibb-{TAG}-0b6f2c1e-3d4a-4b5c-8d9e-0f1a2b3c4d{n:02}")
}

/// One event of a set (timed, UTC wire times).
fn event(n: u32, summary: &str, start: &str, end: &str) -> Value {
    json!({
        "uid": uid(n), "summary": summary, "location": "", "description": "",
        "start": start, "end": end, "all_day": false, "tz": "Europe/Oslo",
    })
}

fn rev_of(events: &[Value]) -> String {
    let mut sorted: Vec<Value> = events.to_vec();
    sorted.sort_by(|a, b| a["uid"].as_str().cmp(&b["uid"].as_str()));
    // A malformed row (the refusal tests) has no rev of its own: any 16 hex.
    let Ok(typed) = sorted
        .into_iter()
        .map(serde_json::from_value)
        .collect::<Result<Vec<KidEvent>, _>>()
    else {
        return "0123456789abcdef".to_string();
    };
    hex::encode(Sha256::digest(
        serde_json::to_string(&typed).unwrap().as_bytes(),
    ))[..16]
        .to_string()
}

/// One phone with the contract's calendar ticked and marked for its kid's events.
struct Kid {
    app: Arc<TestApp>,
    cookie: String,
    device: i64,
    token: String,
    dav: Arc<FakeDav>,
    account: i64,
    source: i64,
    calendar: String,
}

const CAPABILITIES: [&str; 3] = ["calendar_v1", "calendar_feeds_v1", CAPABILITY];

async fn report_capabilities(app: &TestApp, token: &str, capabilities: &[&str]) {
    app.post_status(token, json!({ "capabilities": capabilities }))
        .await;
}

/// The account inserted as a passing Test leaves it (its password sealed), the calendar ticked on the phone through
/// the account page's form, then marked through Kalender's select.
async fn kid_with(writable: Option<bool>) -> Kid {
    let app = Arc::new(TestApp::new().await);
    app.state.kid_writes.set_clock(t0());
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("Anna").await;
    report_capabilities(&app, &token, &CAPABILITIES).await;
    let dav = FakeDav::new(COLLECTION);
    dav.serve_on(&app.fetch);
    // The family's calendar holds the parent's own events too (an empty answer would be held, design 72 QA M1).
    dav.parent_put(
        &format!("{COLLECTION}foreldremote.ics"),
        &parent_body(
            "foreldremote-2026",
            "Foreldremøte",
            "20261105T170000Z",
            "20261105T180000Z",
            "",
        ),
    );
    app.fetch.basic(HOST, USER, PASSWORD);
    let found = crate::caldav::Found {
        id: crate::caldav::calendar_id(COLLECTION),
        href: COLLECTION.to_string(),
        name: "Familie".to_string(),
        writable,
    };
    let account: i64 = sqlx::query_scalar(
        "INSERT INTO caldav_accounts (server_url, username, password_sealed, password_nonce, password_key_fp, \
         hosts, principal, home_set, calendars_json, state, ok_at) VALUES (?, ?, X'', X'', '', ?, ?, ?, ?, 'ok', ?) \
         RETURNING id",
    )
    .bind(SERVER_URL)
    .bind(USER)
    .bind(json!([HOST]).to_string())
    .bind("https://dav.example.org/principals/forelder/")
    .bind("https://dav.example.org/calendars/forelder/")
    .bind(serde_json::to_string(&[&found]).unwrap())
    .bind(stamp(t0()))
    .fetch_one(&app.db)
    .await
    .unwrap();
    let key = app.state.music_key.clone().unwrap();
    let (sealed, nonce) = key.seal_with(
        &crate::caldav::aad(&key.fingerprint, account),
        PASSWORD.as_bytes(),
    );
    sqlx::query(
        "UPDATE caldav_accounts SET password_sealed = ?, password_nonce = ?, password_key_fp = ? WHERE id = ?",
    )
    .bind(sealed)
    .bind(nonce)
    .bind(&key.fingerprint)
    .bind(account)
    .execute(&app.db)
    .await
    .unwrap();
    let on = format!("on_{}", found.id);
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{device}/calendar/caldav/{account}/calendars"),
            Some(&cookie),
            &[("cal", &found.id), (&on, "on")],
        )
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    let calendar = crate::caldav::uid_of(SERVER_URL, USER, COLLECTION);
    let source: i64 = sqlx::query_scalar("SELECT id FROM calendar_sources WHERE uid = ?")
        .bind(&calendar)
        .fetch_one(&app.db)
        .await
        .unwrap();
    let kid = Kid {
        app,
        cookie,
        device,
        token,
        dav,
        account,
        source,
        calendar,
    };
    let res = kid.pick(&format!("caldav:{}", kid.calendar)).await;
    let expected = if writable == Some(false) {
        StatusCode::BAD_REQUEST
    } else {
        StatusCode::SEE_OTHER
    };
    assert_eq!(res.status, expected, "{}", res.text());
    kid
}

async fn kid() -> Kid {
    kid_with(None).await
}

impl Kid {
    fn now(&self) -> DateTime<Utc> {
        self.app.state.kid_writes.now()
    }

    fn set_now(&self, at: DateTime<Utc>) {
        self.app.state.kid_writes.set_clock(at);
    }

    async fn pick(&self, kid: &str) -> TestResponse {
        self.app
            .request_form(
                Method::POST,
                &format!("/devices/{}/calendar/dagen", self.device),
                Some(&self.cookie),
                &[("on_home", "on"), ("kid", kid)],
            )
            .await
    }

    /// A set's POST, the 10 s gap waived.
    async fn post(&self, body: &Value) -> TestResponse {
        sqlx::query("UPDATE device_kid_events SET posted_at = NULL WHERE device_id = ?")
            .bind(self.device)
            .execute(&self.app.db)
            .await
            .unwrap();
        self.post_raw(body).await
    }

    async fn post_raw(&self, body: &Value) -> TestResponse {
        self.app
            .request(
                Method::POST,
                "/api/devices/calendar/kid-events",
                Some(&self.token),
                Some(body.clone()),
            )
            .await
    }

    fn set(&self, events: Vec<Value>) -> Value {
        self.set_with(events, json!({}))
    }

    /// A set from the phone's clock, `extra`'s keys over it.
    fn set_with(&self, events: Vec<Value>, extra: Value) -> Value {
        let mut body = json!({
            "calendar": self.calendar,
            "rev": rev_of(&events),
            "from": KidTime::At(self.now().naive_utc()).wire(),
            "cut": 0, "cut_from": null, "cut_uids": [], "ended": [], "applied": [],
            "events": events,
        });
        for (k, v) in extra.as_object().unwrap() {
            body[k] = v.clone();
        }
        body
    }

    async fn post_ok(&self, body: &Value) -> Value {
        let res = self.post(body).await;
        assert_eq!(res.status, StatusCode::OK, "{}", res.text());
        res.json()
    }

    async fn work(&self) {
        // The claim and the minute's budget are the job's own: a test's passes run back to back.
        sqlx::query("UPDATE caldav_accounts SET write_minute_count = 0, write_claimed_at = NULL")
            .execute(&self.app.db)
            .await
            .unwrap();
        work(&self.app.state, self.account, self.now())
            .await
            .unwrap();
    }

    async fn fetch(&self) {
        crate::calendar_feeds::refresh(&self.app.state, self.source, self.now())
            .await
            .unwrap();
    }

    /// The write job, then the marked calendar's fetch.
    async fn sync(&self) {
        self.work().await;
        self.fetch().await;
    }

    async fn rows(&self) -> Vec<(String, String, Option<String>)> {
        sqlx::query_as(
            "SELECT uid, state, back_id FROM kid_caldav_events WHERE device_id = ? ORDER BY uid",
        )
        .bind(self.device)
        .fetch_all(&self.app.db)
        .await
        .unwrap()
    }

    async fn state_of(&self, n: u32) -> Option<String> {
        sqlx::query_scalar("SELECT state FROM kid_caldav_events WHERE device_id = ? AND uid = ?")
            .bind(self.device)
            .bind(uid(n))
            .fetch_optional(&self.app.db)
            .await
            .unwrap()
    }

    fn writes(&self) -> Vec<crate::tests::fake_dav::Write> {
        self.dav.writes.lock().unwrap().clone()
    }

    fn clear_writes(&self) {
        self.dav.writes.lock().unwrap().clear();
    }

    async fn policy(&self) -> Value {
        self.app.policy(&self.token).await["calendar"]["kid_caldav"].clone()
    }

    /// Lets every waiting row go now (its backoff waived).
    async fn due(&self) {
        sqlx::query("UPDATE kid_caldav_events SET next_at = NULL WHERE device_id = ?")
            .bind(self.device)
            .execute(&self.app.db)
            .await
            .unwrap();
    }

    async fn page(&self) -> String {
        self.app
            .get_page(&format!("/devices/{}/calendar", self.device), &self.cookie)
            .await
            .text()
    }
}

/// A resource body the parent writes (as a calendar app would: its own PRODID and SEQUENCE).
fn parent_body(uid: &str, summary: &str, start: &str, end: &str, extra: &str) -> String {
    format!(
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Apple Inc.//iPhone 18.1//EN\r\nBEGIN:VEVENT\r\nUID:{uid}\r\n\
         DTSTAMP:20261102T130000Z\r\nSEQUENCE:3\r\nSUMMARY:{summary}\r\nDTSTART:{start}\r\nDTEND:{end}\r\n{extra}\
         END:VEVENT\r\nEND:VCALENDAR\r\n"
    )
}

// ---- the contract ------------------------------------------------------------------------------

#[test]
fn the_contract_is_identical_to_the_launchers_copy() {
    let ours = std::fs::read_to_string(CONTRACT).unwrap();
    let theirs =
        std::fs::read_to_string("../launcher/app/src/test/resources/calendar_kid_events.json")
            .expect("the launcher's copy");
    assert_eq!(ours, theirs);
}

fn when(value: &str, tz: Option<Tz>) -> KidWhen {
    if value.len() == 8 {
        KidWhen::Date(NaiveDate::parse_from_str(value, BASIC_DATE).unwrap())
    } else if let Some(utc) = value.strip_suffix('Z') {
        KidWhen::Utc(NaiveDateTime::parse_from_str(utc, "%Y%m%dT%H%M%S").unwrap())
    } else {
        let t = NaiveDateTime::parse_from_str(value, "%Y%m%dT%H%M%S").unwrap();
        match tz {
            Some(tz) => KidWhen::Zoned(t, tz),
            None => KidWhen::Floating(t),
        }
    }
}

fn duration_of(value: &str) -> TimeDelta {
    let body = format!(
        "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\nDTSTART:20260101T000000Z\r\nDURATION:{value}\r\nEND:VEVENT\r\n\
         END:VCALENDAR\r\n"
    );
    crate::ics::read_kid_resource(body.as_bytes())
        .unwrap()
        .duration
        .unwrap()
}

#[test]
fn the_canonical_rows() {
    let c = contract();
    for row in c["canonical"].as_array().unwrap() {
        let input = &row["input"];
        let tz: Option<Tz> = input["tz"].as_str().map(|z| z.parse().unwrap());
        // A time without `Z` is the parent's floating one: resolved in the row's `tz` (D2.7).
        let version = version_of_parts(
            input["summary"].as_str().unwrap(),
            input["location"].as_str().unwrap(),
            input["description"].as_str().unwrap(),
            when(input["start"].as_str().unwrap(), None),
            input["end"].as_str().map(|e| when(e, None)),
            input["duration"].as_str().map(duration_of),
            tz,
        );
        assert_eq!(
            serde_json::to_value(&version).unwrap(),
            row["canonical"],
            "{}",
            row["name"]
        );
        assert_eq!(version_hash(&version), row["hash"], "{}", row["name"]);
    }
}

#[test]
fn the_snapshots_revs_are_the_events_hash() {
    let c = contract();
    for snapshot in [&c["snapshot"], &c["cut_variant"]["snapshot"]] {
        let rows = snapshot["events"].as_array().unwrap();
        assert_eq!(snapshot["rev"], rev_of(rows));
        // Every row is canonical already: the server's reading changes nothing.
        let set = read_set(snapshot).expect("the contract's set reads");
        assert_eq!(
            serde_json::to_value(&set.events).unwrap(),
            snapshot["events"]
        );
    }
    let cut = &c["cut_variant"]["snapshot"];
    assert_eq!(cut["cut"], 4);
    assert_eq!(cut["cut_from"], "20261103T160000Z");
}

/// The shared contract (§8): the snapshot posted against the stateful fake, each PUT's body as
/// `testdata/calendar_kid_events/<uid>.ics`, every `answers` row produced, `back_provider` the provider values each
/// hand-back writes. `CALENDAR_KID_EVENTS_CONTRACT_WRITE=1` rewrites `answers`, `back_provider`, `policy_waiting`
/// and the `.ics` bodies.
#[tokio::test]
async fn the_contract() {
    let write = std::env::var("CALENDAR_KID_EVENTS_CONTRACT_WRITE").is_ok();
    let mut c = contract();
    let k = kid().await;
    assert_eq!(k.calendar, c["policy"]["id"].as_str().unwrap());
    assert_eq!(k.policy().await, c["policy"]);
    let snapshot = c["snapshot"].clone();
    let mut answers: Vec<Value> = Vec::new();
    let mut answer = |name: &str, res: &TestResponse| {
        answers.push(json!({ "name": name, "status": res.status.as_u16(), "body": res.json() }));
    };

    // The first set: every coming event to write.
    let res = k.post(&snapshot).await;
    answer("first", &res);
    k.work().await;
    let writes = k.writes();
    assert_eq!(writes.len(), 7, "{writes:?}");
    for (method, url, if_match, if_none) in &writes {
        assert_eq!(method, "PUT");
        assert_eq!(if_none.as_deref(), Some("*"));
        assert!(if_match.is_none());
        let uid = url.trim_start_matches(COLLECTION).trim_end_matches(".ics");
        let path = format!("testdata/calendar_kid_events/{uid}.ics");
        let body = k.dav.body(url).unwrap();
        if write {
            std::fs::create_dir_all("testdata/calendar_kid_events").unwrap();
            std::fs::write(&path, &body).unwrap();
        }
        assert_eq!(
            body,
            std::fs::read_to_string(&path).unwrap(),
            "{uid}'s body changed"
        );
        // Its own body reads back to the version the phone sent.
        let read = crate::ics::read_kid_resource(body.as_bytes()).unwrap();
        let sent: KidEvent = serde_json::from_value(
            snapshot["events"]
                .as_array()
                .unwrap()
                .iter()
                .find(|e| e["uid"] == uid)
                .unwrap()
                .clone(),
        )
        .unwrap();
        assert!(resource_version(&read, sent.zone()) == sent.version().unwrap());
    }
    assert_eq!(k.policy().await["pending"], 7, "until the fetch after it");
    k.fetch().await;
    assert_eq!(k.policy().await["pending"], 0);
    assert_eq!(k.policy().await["accepted"], snapshot["rev"]);

    // The parent moves Kino an hour and renames it, moves the ski day by two days, deletes the choir practice.
    let kino = k.dav.href(&uid(1));
    k.dav.parent_edit(&kino, |b| {
        b.replace("SUMMARY:Kino", "SUMMARY:Kino med klassen")
            .replace(
                "DTSTART:20261103T160000Z",
                "DTSTART;TZID=Europe/Oslo:20261103T180000",
            )
            .replace(
                "DTEND:20261103T180000Z",
                "DTEND;TZID=Europe/Oslo:20261103T200000",
            )
    });
    let skidag = k.dav.href(&uid(3));
    k.dav.parent_edit(&skidag, |b| {
        b.replace("DTSTART;VALUE=DATE:20261110", "DTSTART;VALUE=DATE:20261112")
            .replace("DTEND;VALUE=DATE:20261111", "DTEND;VALUE=DATE:20261113")
    });
    k.dav.parent_delete(&k.dav.href(&uid(2)));
    k.clear_writes();
    k.fetch().await;
    let res = k.post(&snapshot).await;
    answer("accepted", &res);
    assert!(k.writes().is_empty(), "the calendar wins: nothing written");
    let backs = res.json()["back"].as_array().unwrap().clone();
    assert_eq!(backs.len(), 3);
    let policy_waiting = k.policy().await;
    assert_eq!(policy_waiting["back"], 3);

    // The phone applied Kino's hand-back: its set has the parent's version, and it acknowledges the id.
    let kino_back = backs.iter().find(|b| b["uid"] == uid(1)).unwrap().clone();
    let skidag_first = backs.iter().find(|b| b["uid"] == uid(3)).unwrap()["id"].clone();
    let mut applied = snapshot.clone();
    for e in applied["events"].as_array_mut().unwrap().iter_mut() {
        if e["uid"] == uid(1) {
            for key in [
                "summary",
                "location",
                "description",
                "start",
                "end",
                "all_day",
            ] {
                e[key] = kino_back[key].clone();
            }
        }
    }
    let rev = rev_of(applied["events"].as_array().unwrap());
    applied["rev"] = rev.into();
    applied["applied"] = json!([kino_back["id"]]);
    let res = k.post(&applied).await;
    answer("acknowledged", &res);
    k.work().await;
    assert!(
        k.writes().is_empty(),
        "an acknowledged version is the calendar's: no write"
    );

    // The parent moves the ski day again: a new id; the first one acknowledged clears nothing (R-M2).
    k.dav.parent_edit(&skidag, |b| {
        b.replace("DTSTART;VALUE=DATE:20261112", "DTSTART;VALUE=DATE:20261113")
            .replace("DTEND;VALUE=DATE:20261113", "DTEND;VALUE=DATE:20261114")
    });
    k.fetch().await;
    applied["applied"] = json!([skidag_first]);
    let res = k.post(&applied).await;
    answer("stale_ack", &res);
    let back = res.json()["back"].clone();
    assert!(
        back.as_array()
            .unwrap()
            .iter()
            .any(|b| b["uid"] == uid(3) && b["id"] != skidag_first)
    );

    // A data clear: the phone sends no events at all - five written ones would go: held a day (QA M4).
    let res = k.post(&k.set(vec![])).await;
    answer("held", &res);
    assert_eq!(res.json()["accepted"], applied["rev"]);
    k.work().await;
    assert!(k.writes().iter().all(|w| w.0 != "DELETE"));

    // Refusals.
    let mut other = applied.clone();
    other["calendar"] = "0".repeat(32).into();
    let res = k.post(&other).await;
    answer("not_marked", &res);
    k.post(&applied).await;
    let res = k.post_raw(&applied).await;
    assert!(res.headers.get("retry-after").is_some());
    answer("limited", &res);
    let big: Vec<Value> = (0..300)
        .map(|i| {
            let mut e = event(1, "Stor", "20261103T160000Z", "20261103T170000Z");
            e["uid"] = format!("vibb-{TAG}-0b6f2c1e-3d4a-4b5c-8d9e-{i:012}").into();
            e["description"] = "x".repeat(2000).into();
            e
        })
        .collect();
    let res = k.post(&k.set(big)).await;
    answer("too_big", &res);
    let mut bad = applied.clone();
    bad["events"][0]["uid"] = "vibb-1a2b3c4d-not-a-uuid".into();
    let res = k.post(&bad).await;
    answer("bad_set", &res);
    // A second phone (the same phone enrolled again) posting the first one's tag.
    let (second, token) = k.app.enrolled_device("Anna ny").await;
    report_capabilities(&k.app, &token, &CAPABILITIES).await;
    let found = crate::caldav::calendar_id(COLLECTION);
    let on = format!("on_{found}");
    k.app
        .request_form(
            Method::POST,
            &format!("/devices/{second}/calendar/caldav/{}/calendars", k.account),
            Some(&k.cookie),
            &[("cal", &found), (&on, "on")],
        )
        .await;
    let pick = format!("caldav:{}", k.calendar);
    k.app
        .request_form(
            Method::POST,
            &format!("/devices/{second}/calendar/dagen"),
            Some(&k.cookie),
            &[("kid", &pick)],
        )
        .await;
    let res = k
        .app
        .request(
            Method::POST,
            "/api/devices/calendar/kid-events",
            Some(&token),
            Some(applied.clone()),
        )
        .await;
    answer("tag_pinned", &res);
    let page = k
        .app
        .get_page(&format!("/devices/{second}/calendar"), &k.cookie)
        .await
        .text();
    assert!(
        page.contains("Delete the old phone Anna under Devices"),
        "{page}"
    );

    // The provider values each hand-back of `accepted` writes (D2.3): the row with the uid updated, else inserted;
    // a delete removes it; all-day at UTC midnights in UTC.
    let accepted = answers.iter().find(|a| a["name"] == "accepted").unwrap()["body"]["back"]
        .as_array()
        .unwrap()
        .clone();
    let provider = c["provider"].as_array().unwrap().clone();
    let back_provider: Vec<Value> = accepted
        .iter()
        .map(|b| {
            let row = provider
                .iter()
                .find(|r| r["uid"] == b["uid"] && r["deleted"] == false);
            let ms = |v: &str, all_day: bool| {
                KidTime::parse(v, all_day)
                    .unwrap()
                    .instant()
                    .timestamp_millis()
            };
            match (b["op"].as_str().unwrap(), row) {
                ("delete", Some(r)) => {
                    json!({ "id": b["id"], "action": "delete", "event": r["id"] })
                }
                ("delete", None) => json!({ "id": b["id"], "action": "ignore", "event": null }),
                (_, row) => {
                    let all_day = b["all_day"].as_bool().unwrap();
                    let tz = if all_day {
                        json!("UTC")
                    } else {
                        row.map_or(json!(null), |r| r["tz"].clone())
                    };
                    json!({
                        "id": b["id"],
                        "action": if row.is_some() { "update" } else { "insert" },
                        "event": row.map(|r| r["id"].clone()),
                        "values": {
                            "title": b["summary"], "location": b["location"], "description": b["description"],
                            "dtstart": ms(b["start"].as_str().unwrap(), all_day),
                            "dtend": ms(b["end"].as_str().unwrap(), all_day),
                            "all_day": all_day,
                            "tz": tz,
                        },
                    })
                }
            }
        })
        .collect();
    if write {
        c["answers"] = json!(answers);
        c["back_provider"] = json!(back_provider);
        c["policy_waiting"] = policy_waiting.clone();
        std::fs::write(CONTRACT, serde_json::to_string_pretty(&c).unwrap() + "\n").unwrap();
    }
    assert_eq!(
        json!(answers),
        c["answers"],
        "the answers changed - update both copies"
    );
    assert_eq!(json!(back_provider), c["back_provider"]);
    assert_eq!(policy_waiting, c["policy_waiting"]);
    // Every id follows R-M2's rule: SHA-256 over the uid and the version's hash.
    for a in c["answers"].as_array().unwrap() {
        for b in a["body"]["back"].as_array().into_iter().flatten() {
            let uid = b["uid"].as_str().unwrap();
            let expected = if b["op"] == "delete" {
                back_id(uid, "delete")
            } else {
                back_id(uid, &version_hash(&back_version(b).unwrap()))
            };
            assert_eq!(b["id"], expected);
        }
    }
}

// ---- the pieces --------------------------------------------------------------------------------

#[test]
fn the_uid_pattern_and_the_hrefs() {
    assert!(valid_uid(&uid(1)));
    assert!(!valid_uid("vibb-1a2b3c4d-NOT-A-UUID"));
    assert!(!valid_uid(&uid(1).to_uppercase()));
    assert!(!valid_uid(&format!("{}/", &uid(1)[..49])));
    assert!(!valid_uid(&format!("{}0", uid(1))));
    // Appended onto the collection's path, one slash, never a join of the phone's text.
    assert_eq!(
        resource_href("https://dav.example.org/calendars/familie", &uid(1)).unwrap(),
        format!("https://dav.example.org/calendars/familie/{}.ics", uid(1))
    );
    assert_eq!(
        resource_href("https://dav.example.org/calendars/familie//", &uid(1)).unwrap(),
        format!("https://dav.example.org/calendars/familie/{}.ics", uid(1))
    );
    // A server-named href is adopted only as a direct child of the collection.
    assert_eq!(
        adoptable_href(COLLECTION, "/calendars/forelder/familie/annen.ics").as_deref(),
        Some("https://dav.example.org/calendars/forelder/familie/annen.ics")
    );
    for refused in [
        "../x.ics",
        "/calendars/forelder/familie/%2e%2e/x.ics",
        "%2e%2e%2fx.ics",
        "/calendars/forelder/familie/a/b.ics",
        "https://evil.example.org/calendars/forelder/familie/x.ics",
        "/calendars/forelder/familie/",
    ] {
        assert!(adoptable_href(COLLECTION, refused).is_none(), "{refused}");
    }
}

#[test]
fn the_writer_folds_escapes_and_reads_back() {
    let summary = format!("Kino; med, venner\\ og «{}» 🎶\tslutt", "ø".repeat(60));
    let description = "Linje én\nlinje to, med; tegn\\";
    let body = crate::ics::write_event(&crate::ics::KidWrite {
        uid: &uid(1),
        summary: &summary,
        location: "",
        description,
        start: "20261103T160000Z",
        end: "20261103T180000Z",
        all_day: false,
        sequence: 2,
        stamp: "20261102T120000Z",
        created: "20261101T080000Z",
    });
    for line in body.split("\r\n") {
        assert!(line.len() <= 75, "{line}");
    }
    assert!(body.ends_with("END:VCALENDAR\r\n"));
    assert!(!body.contains("LOCATION"), "an empty place isn't written");
    assert!(body.contains("SEQUENCE:2\r\n") && body.contains("PRODID:-//Vibb//Vibb One//EN\r\n"));
    let read = crate::ics::read_kid_resource(body.as_bytes()).unwrap();
    assert_eq!(read.summary, summary);
    assert_eq!(read.description, description);
    assert!(!read.recurring && !read.invites);
    let all_day = crate::ics::write_event(&crate::ics::KidWrite {
        uid: &uid(2),
        summary: "Skidag",
        location: "Tryvann",
        description: "",
        start: "20261110",
        end: "20261111",
        all_day: true,
        sequence: 0,
        stamp: "20261102T120000Z",
        created: "20261102T120000Z",
    });
    assert!(all_day.contains("DTSTART;VALUE=DATE:20261110\r\n"));
    let v = resource_version(
        &crate::ics::read_kid_resource(all_day.as_bytes()).unwrap(),
        None,
    );
    assert_eq!(
        (v.start.as_str(), v.end.as_str(), v.all_day),
        ("20261110", "20261111", true)
    );
    // A series and an invitation are the parent's (D2.4); an e-mail alarm's ATTENDEE invites nobody.
    let read = |extra: &str| {
        crate::ics::read_kid_resource(
            parent_body(
                &uid(1),
                "Kino",
                "20261103T160000Z",
                "20261103T170000Z",
                extra,
            )
            .as_bytes(),
        )
        .unwrap()
    };
    assert!(read("RRULE:FREQ=WEEKLY\r\n").recurring);
    assert!(read("ATTENDEE:mailto:x@example.org\r\n").invites);
    assert!(read("ORGANIZER:mailto:x@example.org\r\n").invites);
    let alarm = read(
        "BEGIN:VALARM\r\nACTION:EMAIL\r\nATTENDEE:mailto:x@example.org\r\nTRIGGER:-PT15M\r\nEND:VALARM\r\n",
    );
    assert!(!alarm.invites && !alarm.recurring);
}

#[test]
fn writable_comes_from_the_privilege_set() {
    let body = br#"<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"><d:response><d:href>/a/</d:href><d:propstat><d:prop><d:current-user-privilege-set><d:privilege><d:read/></d:privilege><d:privilege><d:bind/></d:privilege><d:privilege><d:write-content/></d:privilege></d:current-user-privilege-set></d:prop></d:propstat></d:response><d:response><d:href>/b/</d:href><d:propstat><d:prop><d:current-user-privilege-set><d:privilege><d:read/></d:privilege></d:current-user-privilege-set></d:prop></d:propstat></d:response><d:response><d:href>/c/</d:href><d:propstat><d:prop><d:displayname>C</d:displayname></d:prop></d:propstat></d:response><d:response><d:href>/d/</d:href><d:propstat><d:prop><d:current-user-privilege-set><d:privilege><d:all/></d:privilege></d:current-user-privilege-set></d:prop></d:propstat></d:response></d:multistatus>"#;
    let rs = crate::caldav::multistatus(body).unwrap();
    let got: Vec<Option<bool>> = rs.iter().map(crate::caldav::writable_of).collect();
    assert_eq!(got, [Some(true), Some(false), None, Some(true)]);
}

#[test]
fn a_forbidden_body_names_its_precondition() {
    let (names, href) = forbidden_body(
        br#"<?xml version="1.0"?><D:error xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav"><C:no-uid-conflict><D:href>/calendars/forelder/familie/other.ics</D:href></C:no-uid-conflict></D:error>"#,
    );
    assert_eq!(
        names,
        [(
            crate::caldav::CALDAV.to_string(),
            "no-uid-conflict".to_string()
        )]
    );
    assert_eq!(
        href.as_deref(),
        Some("/calendars/forelder/familie/other.ics")
    );
    let (names, _) = forbidden_body(br#"<d:error xmlns:d="DAV:"><d:need-privileges/></d:error>"#);
    assert_eq!(
        names,
        [(
            crate::caldav::DAV.to_string(),
            "need-privileges".to_string()
        )]
    );
    assert!(forbidden_body(b"").0.is_empty());
}

// ---- the writes --------------------------------------------------------------------------------

#[tokio::test]
async fn every_write_carries_its_condition_and_a_redirect_is_not_followed() {
    let k = kid().await;
    let kino = event(1, "Kino", "20261103T160000Z", "20261103T180000Z");
    k.post_ok(&k.set(vec![kino.clone()])).await;
    k.sync().await;
    let mut moved = kino.clone();
    moved["start"] = "20261103T170000Z".into();
    moved["end"] = "20261103T190000Z".into();
    k.post_ok(&k.set(vec![moved])).await;
    k.sync().await;
    k.post_ok(&k.set(vec![])).await;
    k.sync().await;
    let writes = k.writes();
    let methods: Vec<(&str, bool, bool)> = writes
        .iter()
        .map(|(m, _, if_match, if_none)| {
            (
                m.as_str(),
                if_match.is_some(),
                if_none.as_deref() == Some("*"),
            )
        })
        .collect();
    assert_eq!(
        methods,
        [
            ("PUT", false, true),
            ("PUT", true, false),
            ("DELETE", true, false)
        ]
    );
    assert_eq!(k.dav.count(), 1, "the parent's event alone");
    // No request of a write follows a redirect: the 307 is that event's error, asked once.
    k.dav.switches(|s| s.redirect_writes = true);
    k.clear_writes();
    k.post_ok(&k.set(vec![event(
        2,
        "Tannlege",
        "20261104T071500Z",
        "20261104T074500Z",
    )]))
    .await;
    k.work().await;
    assert_eq!(k.writes().len(), 1);
    assert!(
        k.app
            .fetch
            .requests
            .lock()
            .unwrap()
            .iter()
            .all(|r| !r.follow)
    );
    assert_eq!(k.state_of(2).await.as_deref(), Some("pending"));
}

#[tokio::test]
async fn a_release_is_a_tombstone_a_stale_set_never_writes() {
    let k = kid().await;
    let set = k.set(vec![event(
        1,
        "Kino",
        "20261103T160000Z",
        "20261103T180000Z",
    )]);
    k.post_ok(&set).await;
    k.sync().await;
    let href = k.dav.href(&uid(1));
    k.dav.parent_edit(&href, |b| {
        b.replace("END:VEVENT", "RRULE:FREQ=WEEKLY\r\nEND:VEVENT")
    });
    k.fetch().await;
    assert_eq!(k.state_of(1).await.as_deref(), Some("released"));
    k.clear_writes();
    // The phone hasn't applied the delete yet: its set still has the event - nothing is written.
    let answer = k.post_ok(&set).await;
    assert_eq!(answer["back"][0]["op"], "delete");
    k.work().await;
    assert!(k.writes().is_empty());
    // Acknowledged, the tombstone stays; a later stale set still writes nothing.
    let mut acked = set.clone();
    acked["applied"] = json!([answer["back"][0]["id"]]);
    k.post_ok(&acked).await;
    k.post_ok(&set).await;
    k.work().await;
    assert!(k.writes().is_empty());
    assert_eq!(k.state_of(1).await.as_deref(), Some("released"));
    assert!(
        k.dav.body(&href).unwrap().contains("RRULE"),
        "the parent's series stays"
    );
}

#[tokio::test]
async fn a_re_pick_after_an_unmark_hands_the_parents_edit_back() {
    let k = kid().await;
    let set = k.set(vec![event(
        1,
        "Kino",
        "20261103T160000Z",
        "20261103T180000Z",
    )]);
    k.post_ok(&set).await;
    k.sync().await;
    assert_eq!(k.pick("phone").await.status, StatusCode::SEE_OTHER);
    assert!(k.rows().await.is_empty(), "unmarking deletes the rows");
    assert_eq!(
        k.dav.count(),
        2,
        "what was written stays in the calendar (and the parent's event)"
    );
    let href = k.dav.href(&uid(1));
    k.dav
        .parent_edit(&href, |b| b.replace("SUMMARY:Kino", "SUMMARY:Kino flyttet"));
    k.pick(&format!("caldav:{}", k.calendar)).await;
    assert_eq!(
        k.policy().await["accepted"],
        Value::Null,
        "a re-pick: send it all"
    );
    k.clear_writes();
    k.post_ok(&set).await;
    k.work().await;
    // The create met the parent's resource: read, not overwritten (QA H1).
    assert_eq!(k.writes().len(), 1);
    assert_eq!(k.writes()[0].3.as_deref(), Some("*"));
    let answer = k.post_ok(&set).await;
    assert_eq!(answer["back"][0]["summary"], "Kino flyttet");
    assert!(k.dav.body(&href).unwrap().contains("Kino flyttet"));
}

#[tokio::test]
async fn a_lost_answer_is_our_own_not_the_parents() {
    let k = kid().await;
    let v1 = event(1, "Kino", "20261103T160000Z", "20261103T180000Z");
    k.post_ok(&k.set(vec![v1.clone()])).await;
    k.sync().await;
    // The kid changes it; the PUT lands but its answer is lost.
    let mut v2 = v1.clone();
    v2["summary"] = "Kino 2".into();
    k.dav.switches(|s| s.lose_next_put = true);
    k.post_ok(&k.set(vec![v2.clone()])).await;
    k.work().await;
    assert!(k.dav.body(&k.dav.href(&uid(1))).unwrap().contains("Kino 2"));
    // ... and changes it again before the retry: the 412's read is a version we sent (R-L1a) - written, no hand-back.
    let mut v3 = v1.clone();
    v3["summary"] = "Kino 3".into();
    k.post_ok(&k.set(vec![v3.clone()])).await;
    k.due().await;
    k.work().await;
    let answer = k.post_ok(&k.set(vec![v3])).await;
    assert_eq!(answer["back"], json!([]));
    assert!(k.dav.body(&k.dav.href(&uid(1))).unwrap().contains("Kino 3"));
    // A lost create: the next create's 412 reads our own version - the kid's later edit is written, not handed back.
    k.dav.switches(|s| s.lose_next_put = true);
    let t1 = event(2, "Tannlege", "20261104T071500Z", "20261104T074500Z");
    k.post_ok(&k.set(vec![t1.clone()])).await;
    k.work().await;
    let mut t2 = t1.clone();
    t2["location"] = "Klinikken".into();
    k.post_ok(&k.set(vec![t2.clone()])).await;
    k.due().await;
    k.work().await;
    let answer = k.post_ok(&k.set(vec![t2])).await;
    assert_eq!(answer["back"], json!([]));
    assert!(
        k.dav
            .body(&k.dav.href(&uid(2)))
            .unwrap()
            .contains("LOCATION:Klinikken")
    );
}

#[tokio::test]
async fn hand_backs_wait_for_their_acknowledgement() {
    let k = kid().await;
    let v1 = event(1, "Kino", "20261103T160000Z", "20261103T180000Z");
    let set = k.set(vec![v1.clone()]);
    k.post_ok(&set).await;
    k.sync().await;
    let href = k.dav.href(&uid(1));
    k.dav.parent_edit(&href, |b| {
        b.replace("SUMMARY:Kino", "SUMMARY:Kino forelder")
    });
    k.fetch().await;
    // The kid edits it while the hand-back waits: not written, the hand-back sent again and again.
    let mut kid_edit = v1.clone();
    kid_edit["summary"] = "Kino barn".into();
    k.clear_writes();
    let first = k.post_ok(&k.set(vec![kid_edit.clone()])).await;
    k.work().await;
    let again = k.post_ok(&k.set(vec![kid_edit])).await;
    assert!(k.writes().is_empty());
    assert_eq!(first["back"], again["back"]);
    assert_eq!(first["back"][0]["summary"], "Kino forelder");
    // Applied and acknowledged; the kid's next change is written once and not handed back.
    let mut applied = v1.clone();
    applied["summary"] = "Kino forelder".into();
    let mut acked = k.set(vec![applied.clone()]);
    acked["applied"] = json!([first["back"][0]["id"]]);
    assert_eq!(k.post_ok(&acked).await["back"], json!([]));
    k.work().await;
    assert!(k.writes().is_empty());
    let mut next = applied;
    next["summary"] = "Kino til slutt".into();
    k.post_ok(&k.set(vec![next.clone()])).await;
    k.sync().await;
    assert_eq!(k.writes().len(), 1);
    assert_eq!(k.post_ok(&k.set(vec![next])).await["back"], json!([]));
}

#[tokio::test]
async fn a_rewritten_body_is_no_parents_edit() {
    for without_etag in [false, true] {
        let k = kid().await;
        k.dav.switches(|s| {
            s.rewrite = true;
            s.put_without_etag = without_etag;
        });
        let set = k.set(vec![event(
            1,
            "Kino",
            "20261103T160000Z",
            "20261103T180000Z",
        )]);
        k.post_ok(&set).await;
        k.sync().await;
        k.fetch().await;
        let answer = k.post_ok(&set).await;
        assert_eq!(answer["back"], json!([]), "without_etag {without_etag}");
        assert_eq!(answer["pending"], 0);
    }
}

#[tokio::test]
async fn an_acknowledged_delete_drops_the_row() {
    let k = kid().await;
    let set = k.set(vec![event(
        1,
        "Kino",
        "20261103T160000Z",
        "20261103T180000Z",
    )]);
    k.post_ok(&set).await;
    k.sync().await;
    k.dav.parent_delete(&k.dav.href(&uid(1)));
    k.fetch().await;
    let answer = k.post_ok(&set).await;
    assert_eq!(answer["back"][0]["op"], "delete");
    let mut acked = k.set(vec![]);
    acked["applied"] = json!([answer["back"][0]["id"]]);
    k.post_ok(&acked).await;
    assert!(k.rows().await.is_empty());
    k.work().await;
    assert!(
        k.writes().iter().all(|w| w.0 == "PUT"),
        "nothing deleted by the server"
    );
}

// ---- what is not a delete ----------------------------------------------------------------------

#[tokio::test]
async fn a_cut_set_deletes_nothing_it_left_out() {
    let k = kid().await;
    let near = event(1, "Kino", "20261103T160000Z", "20261103T180000Z");
    let far = event(2, "Leir", "20270601T080000Z", "20270605T140000Z");
    let farther = event(3, "Tur", "20271001T080000Z", "20271002T140000Z");
    k.post_ok(&k.set(vec![near.clone(), far, farther])).await;
    k.sync().await;
    k.clear_writes();
    // The far ones left out by the limits: at or after `cut_from`, or named in `cut_uids` - none deleted.
    let cut = k.set_with(
        vec![near.clone()],
        json!({ "cut": 2, "cut_from": "20270601T080000Z", "cut_uids": [uid(2), uid(3)] }),
    );
    k.post_ok(&cut).await;
    k.work().await;
    assert!(k.writes().is_empty());
    // A near event dropped by the bytes (its start before cut_from, in cut_uids) stays; with `cut_uids` null nothing
    // is deleted at all.
    let listed = k.set_with(
        vec![],
        json!({ "cut": 3, "cut_from": "20261103T160000Z", "cut_uids": [uid(1), uid(2), uid(3)] }),
    );
    k.post_ok(&listed).await;
    let null = k.set_with(
        vec![],
        json!({ "cut": 2001, "cut_from": "20261103T160000Z", "cut_uids": null }),
    );
    k.post_ok(&null).await;
    k.work().await;
    assert!(k.writes().is_empty());
    // An event stored before cut_from and not listed is a delete (QA M3 narrowed).
    let tannlege = event(4, "Tannlege", "20261104T071500Z", "20261104T074500Z");
    k.post_ok(&k.set(vec![near.clone(), tannlege])).await;
    k.sync().await;
    k.clear_writes();
    let cut = k.set_with(
        vec![near],
        json!({ "cut": 2, "cut_from": "20270601T080000Z", "cut_uids": [uid(2), uid(3)] }),
    );
    k.post_ok(&cut).await;
    k.work().await;
    let deletes: Vec<String> = k
        .writes()
        .into_iter()
        .filter(|w| w.0 == "DELETE")
        .map(|w| w.1)
        .collect();
    assert_eq!(deletes, [k.dav.href(&uid(4))]);
}

#[tokio::test]
async fn an_ended_event_is_never_deleted() {
    let k = kid().await;
    let lekser = event(1, "Lekser", "20261102T113000Z", "20261102T123000Z");
    let kino = event(2, "Kino", "20261103T160000Z", "20261103T180000Z");
    k.post_ok(&k.set(vec![lekser, kino])).await;
    k.sync().await;
    k.clear_writes();
    // An hour later the homework is over and leaves the set; the kid moved Kino to yesterday (in `ended`, R-M1).
    k.set_now(t0() + TimeDelta::hours(1));
    k.post_ok(&k.set_with(vec![], json!({ "ended": [uid(2)] })))
        .await;
    k.work().await;
    assert!(k.writes().is_empty());
    assert_eq!(k.state_of(1).await.as_deref(), Some("past"));
    assert_eq!(k.state_of(2).await.as_deref(), Some("past"));
    assert_eq!(k.dav.count(), 3, "both, and the parent's event");
    // Moved back into the future with the calendar unchanged: written, not reverted (R-L1b).
    let back = event(2, "Kino igjen", "20261105T160000Z", "20261105T180000Z");
    k.post_ok(&k.set(vec![back.clone()])).await;
    k.sync().await;
    assert!(
        k.dav
            .body(&k.dav.href(&uid(2)))
            .unwrap()
            .contains("Kino igjen")
    );
    assert_eq!(k.post_ok(&k.set(vec![back])).await["back"], json!([]));
    // 60 days after its end a past row goes.
    k.set_now(t0() + TimeDelta::days(61));
    k.post_ok(&k.set(vec![])).await;
    assert_eq!(k.state_of(1).await, None);
}

#[tokio::test]
async fn a_phone_clock_a_day_off_freezes_its_deletes() {
    let k = kid().await;
    let kino = event(1, "Kino", "20261103T160000Z", "20261103T180000Z");
    k.post_ok(&k.set(vec![kino])).await;
    k.sync().await;
    k.clear_writes();
    let mut skewed = k.set(vec![event(2, "Ny", "20261105T160000Z", "20261105T170000Z")]);
    skewed["from"] = KidTime::At((t0() - TimeDelta::days(2)).naive_utc())
        .wire()
        .into();
    k.post_ok(&skewed).await;
    k.work().await;
    let methods: Vec<String> = k.writes().into_iter().map(|w| w.0).collect();
    assert_eq!(methods, ["PUT"], "its writes go, its deletes don't");
    assert!(k.page().await.contains("data-kid-status=\"clock\""));
}

#[tokio::test]
async fn a_malformed_row_refuses_the_whole_set() {
    let k = kid().await;
    let good = event(1, "Kino", "20261103T160000Z", "20261103T180000Z");
    for broken in [
        json!({ "uid": uid(2), "summary": "x" }),
        json!({ "uid": uid(2), "summary": 5, "location": "", "description": "", "start": "20261103T160000Z", "end": "20261103T170000Z", "all_day": false, "tz": null }),
        json!({ "uid": uid(2), "summary": "x", "location": "", "description": "", "start": "2026-11-03", "end": "20261103T170000Z", "all_day": false, "tz": null }),
        json!({ "uid": "vibb-1a2b3c4d-x", "summary": "x", "location": "", "description": "", "start": "20261103T160000Z", "end": "20261103T170000Z", "all_day": false, "tz": null }),
        good.clone(),
    ] {
        let res = k.post(&k.set(vec![good.clone(), broken])).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST);
        assert_eq!(res.json()["error"], "bad_set");
    }
    assert!(k.rows().await.is_empty(), "nothing taken");
    let page = k.page().await;
    assert!(page.contains("data-kid-status=\"rejected\""), "{page}");
    let res = k
        .post(&k.set_with(vec![good], json!({ "from": "yesterday" })))
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
}

// ---- the holds ---------------------------------------------------------------------------------

fn six() -> Vec<Value> {
    (1..=6)
        .map(|n| {
            let day = 3 + n;
            event(
                n,
                &format!("Avtale {n}"),
                &format!("202611{day:02}T160000Z"),
                &format!("202611{day:02}T170000Z"),
            )
        })
        .collect()
}

#[tokio::test]
async fn the_kids_mass_delete_is_held_a_day_or_put_back() {
    let k = kid().await;
    k.post_ok(&k.set(six())).await;
    k.sync().await;
    k.clear_writes();
    // "Min kalender" emptied by a data clear: the set lost every event - held 24 h by time.
    let answer = k.post_ok(&k.set(vec![])).await;
    assert_eq!(answer["accepted"], rev_of(&six()), "the old rev while held");
    k.work().await;
    k.set_now(t0() + TimeDelta::hours(23));
    k.post_ok(&k.set(vec![])).await;
    k.work().await;
    assert!(k.writes().is_empty());
    let page = k.page().await;
    assert!(
        page.contains("data-kid-status=\"held\"") && page.contains("data-kid-restore"),
        "{page}"
    );
    // "Put them back on the phone": each read from the calendar and handed back.
    let res = k
        .app
        .request_form(
            Method::POST,
            &format!("/devices/{}/calendar/dagen/restore", k.device),
            Some(&k.cookie),
            &[],
        )
        .await;
    crate::tests::assert_redirect(
        &res,
        &format!("/devices/{}/calendar", k.device),
        Some("dagen"),
    );
    k.work().await;
    let answer = k.post_ok(&k.set(vec![])).await;
    let back = answer["back"].as_array().unwrap();
    assert_eq!(back.len(), 6);
    assert!(back.iter().all(|b| b["op"] == "upsert"));
    k.work().await;
    assert!(k.writes().is_empty());

    // Without an answer, the deletes go after the day.
    let k = kid().await;
    k.post_ok(&k.set(six())).await;
    k.sync().await;
    k.clear_writes();
    k.post_ok(&k.set(vec![])).await;
    k.set_now(t0() + TimeDelta::hours(25));
    k.post_ok(&k.set(vec![])).await;
    k.work().await;
    assert_eq!(k.writes().iter().filter(|w| w.0 == "DELETE").count(), 6);
}

#[tokio::test]
async fn the_calendars_mass_delete_is_held_too() {
    let k = kid().await;
    let set = k.set(six());
    k.post_ok(&set).await;
    k.sync().await;
    for n in 1..=6 {
        k.dav.parent_delete(&k.dav.href(&uid(n)));
    }
    k.fetch().await;
    let answer = k.post_ok(&set).await;
    assert_eq!(answer["back"], json!([]), "held a day");
    assert!(k.page().await.contains("data-kid-status=\"back_held\""));
    k.set_now(t0() + TimeDelta::hours(25));
    run_due(&k.app.state, k.now()).await;
    let answer = k.post_ok(&set).await;
    assert_eq!(answer["back"].as_array().unwrap().len(), 6);
    assert!(
        k.writes().iter().all(|w| w.0 == "PUT"),
        "never deleted by the server"
    );
}

#[tokio::test]
async fn a_missing_resource_is_confirmed_by_its_own_href() {
    let k = kid().await;
    let set = k.set(vec![
        event(1, "Kino", "20261103T160000Z", "20261103T180000Z"),
        event(2, "Tannlege", "20261104T071500Z", "20261104T074500Z"),
    ]);
    k.post_ok(&set).await;
    k.sync().await;
    // Kino moved past the REPORT's window (the GET finds it); the dentist hidden from the REPORT but there unchanged.
    let kino = k.dav.href(&uid(1));
    k.dav.parent_edit(&kino, |b| {
        b.replace("20261103T160000Z", "20281103T160000Z")
            .replace("20261103T180000Z", "20281103T180000Z")
    });
    let dentist = k.dav.href(&uid(2));
    k.dav.switches(|s| {
        s.hidden.insert(kino.clone());
        s.hidden.insert(dentist.clone());
    });
    k.fetch().await;
    let answer = k.post_ok(&set).await;
    let back = answer["back"].as_array().unwrap();
    assert_eq!(back.len(), 1, "{back:?}");
    assert_eq!(back[0]["uid"], uid(1));
    assert_eq!(back[0]["start"], "20281103T160000Z");
    // Gone for good: the confirming GET answers 404 - the parent's delete.
    k.dav.parent_delete(&dentist);
    k.fetch().await;
    let answer = k.post_ok(&set).await;
    assert!(
        answer["back"]
            .as_array()
            .unwrap()
            .iter()
            .any(|b| b["uid"] == uid(2) && b["op"] == "delete")
    );
}

// ---- the 403, the stops ------------------------------------------------------------------------

#[tokio::test]
async fn a_403_is_read_by_its_body() {
    // `no-uid-conflict`: its href read first, adopted as our own when it holds the phone's version.
    let k = kid().await;
    let kino = event(1, "Kino", "20261103T160000Z", "20261103T180000Z");
    let other = format!("{COLLECTION}gammel-kino.ics");
    let written = crate::ics::write_event(&crate::ics::KidWrite {
        uid: &uid(1),
        summary: "Kino",
        location: "",
        description: "",
        start: "20261103T160000Z",
        end: "20261103T180000Z",
        all_day: false,
        sequence: 0,
        stamp: "20261101T120000Z",
        created: "20261101T120000Z",
    });
    k.dav.parent_put(&other, &written);
    k.dav.switches(|s| {
        s.writes_answer = Some(403);
        s.forbidden_body = Some(r#"<D:error xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav"><C:no-uid-conflict><D:href>/calendars/forelder/familie/gammel-kino.ics</D:href></C:no-uid-conflict></D:error>"#.to_string());
    });
    k.post_ok(&k.set(vec![kino.clone()])).await;
    k.work().await;
    assert_eq!(k.state_of(1).await.as_deref(), Some("ok"));
    let href: String = sqlx::query_scalar("SELECT href FROM kid_caldav_events WHERE uid = ?")
        .bind(uid(1))
        .fetch_one(&k.app.db)
        .await
        .unwrap();
    assert_eq!(href, other);
    // Another CalDAV precondition refuses that event alone.
    k.dav.switches(|s| {
        s.forbidden_body = Some(r#"<D:error xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav"><C:valid-calendar-data/></D:error>"#.to_string());
    });
    k.post_ok(&k.set(vec![
        kino.clone(),
        event(2, "Rar", "20261104T071500Z", "20261104T074500Z"),
    ]))
    .await;
    k.work().await;
    assert_eq!(k.state_of(2).await.as_deref(), Some("refused"));
    assert!(k.page().await.contains("data-kid-status=\"refused\""));
    // `need-privileges`: this phone's writes stop - and the account stays `ok`.
    k.dav.switches(|s| {
        s.forbidden_body =
            Some(r#"<D:error xmlns:D="DAV:"><D:need-privileges/></D:error>"#.to_string())
    });
    k.post_ok(&k.set(vec![
        kino.clone(),
        event(3, "Ny", "20261104T091500Z", "20261104T094500Z"),
    ]))
    .await;
    k.work().await;
    let state: String = sqlx::query_scalar("SELECT state FROM caldav_accounts")
        .fetch_one(&k.app.db)
        .await
        .unwrap();
    assert_eq!(state, "ok");
    assert!(k.page().await.contains("data-kid-status=\"write_refused\""));
    k.clear_writes();
    k.work().await;
    assert!(
        k.writes().is_empty(),
        "stopped until a Test passes or a new pick"
    );

    // 507 → full; Nextcloud's 400 and a 409 → that event refused.
    for (status, code) in [(507u16, Some("full")), (400, None), (409, None)] {
        let k = kid().await;
        k.dav.switches(|s| s.writes_answer = Some(status));
        k.post_ok(&k.set(vec![kino.clone()])).await;
        k.work().await;
        let error: Option<String> = sqlx::query_scalar("SELECT error FROM device_kid_events")
            .fetch_one(&k.app.db)
            .await
            .unwrap();
        assert_eq!(error.as_deref(), code, "{status}");
        if code.is_none() {
            assert_eq!(k.state_of(1).await.as_deref(), Some("refused"), "{status}");
        }
    }
    // A 401 is the account's refused password.
    let k = kid().await;
    k.app.fetch.basic(HOST, USER, "another-test-only-value");
    k.post_ok(&k.set(vec![kino])).await;
    k.work().await;
    let state: String = sqlx::query_scalar("SELECT state FROM caldav_accounts")
        .fetch_one(&k.app.db)
        .await
        .unwrap();
    assert_eq!(state, "login_refused");
}

#[tokio::test]
async fn no_strong_etag_means_no_writes() {
    for weak in [true, false] {
        let k = kid().await;
        k.dav.switches(|s| {
            s.weak = weak;
            s.no_etag = !weak;
        });
        k.post_ok(&k.set(vec![event(
            1,
            "Kino",
            "20261103T160000Z",
            "20261103T180000Z",
        )]))
        .await;
        k.work().await;
        assert!(
            k.page().await.contains("data-kid-status=\"no_etag\""),
            "weak {weak}"
        );
        k.clear_writes();
        k.post_ok(&k.set(vec![event(2, "Ny", "20261104T160000Z", "20261104T170000Z")]))
            .await;
        k.work().await;
        assert!(k.writes().is_empty());
    }
}

#[tokio::test]
async fn the_account_backs_off_and_paces_its_writes() {
    let k = kid().await;
    k.dav.switches(|s| {
        s.writes_answer = Some(429);
        s.retry_after = Some("120".to_string());
    });
    k.post_ok(&k.set(six())).await;
    k.work().await;
    assert_eq!(k.writes().len(), 1, "the account stops at the first 429");
    k.dav.switches(|s| s.writes_answer = None);
    k.clear_writes();
    k.work().await;
    assert!(k.writes().is_empty(), "until Retry-After has passed");
    k.set_now(t0() + TimeDelta::seconds(121));
    // 60 a minute: one left this minute.
    sqlx::query(
        "UPDATE caldav_accounts SET write_minute_count = 59, write_minute_at = ?, write_claimed_at = NULL",
    )
    .bind(k.now().format("%Y-%m-%d %H:%M").to_string())
    .execute(&k.app.db)
    .await
    .unwrap();
    work(&k.app.state, k.account, k.now()).await.unwrap();
    assert_eq!(k.writes().len(), 1);
    // A claim held by another job: this one writes nothing.
    k.clear_writes();
    sqlx::query("UPDATE caldav_accounts SET write_minute_count = 0, write_claimed_at = ?")
        .bind(stamp(k.now()))
        .execute(&k.app.db)
        .await
        .unwrap();
    work(&k.app.state, k.account, k.now()).await.unwrap();
    assert!(k.writes().is_empty());
}

#[tokio::test]
async fn pending_lasts_until_the_fetch_after_and_the_nudge_waits_for_it() {
    let k = kid().await;
    let mut nudges = k.app.state.command_notify.subscribe();
    let set = k.set(vec![event(
        1,
        "Kino",
        "20261103T160000Z",
        "20261103T180000Z",
    )]);
    assert_eq!(k.post_ok(&set).await["pending"], 1);
    k.work().await;
    assert_eq!(
        k.policy().await["pending"],
        1,
        "written, not yet in a stored fetch (R-L2)"
    );
    while nudges.try_recv().is_ok() {}
    k.fetch().await;
    assert_eq!(k.policy().await["pending"], 0);
    assert!(
        nudges.try_recv().is_ok(),
        "pending reached 0: the phone nudged"
    );
    // A later delete is pending until the fetch after it too.
    k.post_ok(&k.set(vec![])).await;
    k.work().await;
    assert_eq!(k.policy().await["pending"], 1);
    k.fetch().await;
    assert_eq!(k.policy().await["pending"], 0);
    assert!(k.rows().await.is_empty());
}

#[tokio::test]
async fn a_404_create_whose_get_is_404_is_a_status_error() {
    let k = kid().await;
    k.dav.switches(|s| s.writes_answer = Some(404));
    let set = k.set(vec![event(
        1,
        "Kino",
        "20261103T160000Z",
        "20261103T180000Z",
    )]);
    k.post_ok(&set).await;
    k.work().await;
    assert_eq!(k.state_of(1).await.as_deref(), Some("pending"));
    assert_eq!(k.post_ok(&set).await["back"], json!([]), "no adopt");
}

#[tokio::test]
async fn a_calendar_the_last_test_no_longer_lists_takes_no_write() {
    let k = kid().await;
    sqlx::query("UPDATE caldav_accounts SET calendars_json = '[]'")
        .execute(&k.app.db)
        .await
        .unwrap();
    k.post_ok(&k.set(vec![event(
        1,
        "Kino",
        "20261103T160000Z",
        "20261103T180000Z",
    )]))
    .await;
    k.work().await;
    assert!(k.writes().is_empty());
}

// ---- the PWA, the policy, the cascades ---------------------------------------------------------

#[tokio::test]
async fn the_select_offers_the_calendar_and_the_mark_ends_with_its_tick() {
    let k = kid().await;
    let page = k.page().await;
    let value = format!("caldav:{}", k.calendar);
    assert!(
        page.contains(&format!(
            r#"<option value="{value}" selected>Familie · dav.example.org (written by the server)</option>"#
        )),
        "{page}"
    );
    assert!(page.contains("data-kid-status=\"waiting_phone\""));
    assert_eq!(
        k.policy().await,
        json!({ "id": k.calendar, "accepted": null, "back": 0, "pending": 0 })
    );
    // The account's page says where the kid's events go.
    let account = k
        .app
        .get_page(
            &format!("/devices/{}/calendar/caldav/{}", k.device, k.account),
            &k.cookie,
        )
        .await
        .text();
    assert!(account.contains("data-kid-writes"), "{account}");
    // Written: the status line counts, never a title.
    k.post_ok(&k.set(vec![event(
        1,
        "Kinokveld",
        "20261103T160000Z",
        "20261103T180000Z",
    )]))
    .await;
    k.sync().await;
    let page = k.page().await;
    assert!(page.contains("data-kid-status=\"written\""), "{page}");
    assert!(!page.contains("Kinokveld"));
    let logged: Vec<String> =
        sqlx::query_scalar("SELECT COALESCE(detail, '') FROM security_events")
            .fetch_all(&k.app.db)
            .await
            .unwrap();
    assert!(logged.iter().all(|d| !d.contains("Kinokveld")));
    // Unticking the marked calendar ends the mark by the cascade, and says so.
    let id = crate::caldav::calendar_id(COLLECTION);
    let res = k
        .app
        .request_form(
            Method::POST,
            &format!(
                "/devices/{}/calendar/caldav/{}/calendars",
                k.device, k.account
            ),
            Some(&k.cookie),
            &[("cal", &id)],
        )
        .await;
    let back = format!(
        "/devices/{}/calendar/caldav/{}?notice=kid_caldav_ended&cal={id}#calendars",
        k.device, k.account
    );
    assert_eq!(res.location(), Some(back.as_str()));
    let notice = k
        .app
        .get_page(back.split('#').next().unwrap(), &k.cookie)
        .await
        .text();
    assert!(
        notice.contains("data-flash=\"kid-caldav-ended\""),
        "{notice}"
    );
    assert!(notice.contains("no longer written to Familie"), "{notice}");
    for table in ["kid_caldav_events", "device_kid_events"] {
        let n: i64 = sqlx::query_scalar(&format!("SELECT COUNT(*) FROM {table}"))
            .fetch_one(&k.app.db)
            .await
            .unwrap();
        assert_eq!(n, 0, "{table}");
    }
    assert_eq!(k.policy().await, Value::Null);
    assert!(
        k.page()
            .await
            .contains(r#"<option value="phone" selected>"#)
    );
    assert_eq!(k.dav.count(), 2, "what was written stays");
}

#[tokio::test]
async fn an_older_launcher_and_an_unwritable_calendar_cant_be_picked() {
    let k = kid_with(Some(false)).await;
    // `writable: false` at the last Test: not offered, and refused.
    assert!(!k.page().await.contains("caldav:"));
    let k = kid().await;
    k.pick("phone").await;
    report_capabilities(&k.app, &k.token, &["calendar_v1", "calendar_feeds_v1"]).await;
    let page = k.page().await;
    assert!(
        page.contains(&format!(
            r#"<option value="caldav:{}" disabled>"#,
            k.calendar
        )),
        "{page}"
    );
    assert!(page.contains("data-kid-caldav-older"));
    assert_eq!(
        k.pick(&format!("caldav:{}", k.calendar)).await.status,
        StatusCode::BAD_REQUEST
    );
    // A pick saved while the capability was there stays; the policy is `null` until it is back.
    report_capabilities(&k.app, &k.token, &CAPABILITIES).await;
    k.pick(&format!("caldav:{}", k.calendar)).await;
    report_capabilities(&k.app, &k.token, &["calendar_v1", "calendar_feeds_v1"]).await;
    assert_eq!(
        k.pick(&format!("caldav:{}", k.calendar)).await.status,
        StatusCode::SEE_OTHER
    );
    assert_eq!(k.policy().await, Value::Null);
    let res = k.post(&k.set(vec![])).await;
    assert_eq!(res.status, StatusCode::CONFLICT);
}

#[tokio::test]
async fn removing_the_account_or_the_phone_takes_every_row() {
    let k = kid().await;
    k.post_ok(&k.set(vec![event(
        1,
        "Kino",
        "20261103T160000Z",
        "20261103T180000Z",
    )]))
    .await;
    k.sync().await;
    let res = k
        .app
        .request_form(
            Method::POST,
            &format!("/devices/{}/calendar/caldav/{}/remove", k.device, k.account),
            Some(&k.cookie),
            &[],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(
            format!(
                "/devices/{}/calendar?notice=kid_caldav_ended#sources",
                k.device
            )
            .as_str()
        )
    );
    for table in ["kid_caldav_events", "device_kid_events"] {
        let n: i64 = sqlx::query_scalar(&format!("SELECT COUNT(*) FROM {table}"))
            .fetch_one(&k.app.db)
            .await
            .unwrap();
        assert_eq!(n, 0, "{table}");
    }
    let tags: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM kid_caldav_tags")
        .fetch_one(&k.app.db)
        .await
        .unwrap();
    assert_eq!(tags, 1, "the tags stay with the phone");
    sqlx::query("DELETE FROM devices WHERE id = ?")
        .bind(k.device)
        .execute(&k.app.db)
        .await
        .unwrap();
    let tags: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM kid_caldav_tags")
        .fetch_one(&k.app.db)
        .await
        .unwrap();
    assert_eq!(tags, 0);
}

// ---- the code QA's findings, as regression tests ----------------------------------------------

/// H1: a delete hand-back the phone may have applied is never cleared when the resource comes back: the restored
/// version goes back as an upsert, and nothing is deleted.
#[tokio::test]
async fn a_restored_resource_after_a_delivered_delete_is_handed_back_not_deleted() {
    let k = kid().await;
    let set = k.set(vec![
        event(1, "Kino", "20261103T160000Z", "20261103T180000Z"),
        event(2, "Tannlege", "20261104T071500Z", "20261104T074500Z"),
    ]);
    k.post_ok(&set).await;
    k.sync().await;
    let kino = k.dav.href(&uid(1));
    let body = k.dav.body(&kino).unwrap();
    k.dav.parent_delete(&kino);
    k.fetch().await;
    let answer = k.post_ok(&set).await;
    let back = answer["back"].as_array().unwrap().clone();
    assert_eq!(back.len(), 1);
    assert_eq!(back[0]["op"], "delete");
    let id = back[0]["id"].as_str().unwrap().to_string();
    // The phone applies the delete; before it acknowledges, the parent restores the event (trash bin / undo).
    k.dav.parent_put(&kino, &body);
    k.fetch().await;
    k.clear_writes();
    let after = k.set_with(
        vec![event(2, "Tannlege", "20261104T071500Z", "20261104T074500Z")],
        json!({ "applied": [id] }),
    );
    let answer = k.post_ok(&after).await;
    k.work().await;
    assert!(
        k.writes().iter().all(|w| w.0 != "DELETE"),
        "{:?}",
        k.writes()
    );
    assert!(k.dav.body(&kino).is_some());
    let back = answer["back"].as_array().unwrap();
    assert_eq!(
        (back[0]["op"].as_str(), back[0]["summary"].as_str()),
        (Some("upsert"), Some("Kino"))
    );
}

/// H2: "Legg dem tilbake" whose GETs partly fail keeps the rest from being deleted; the job reads them later.
#[tokio::test]
async fn a_partly_failed_put_back_deletes_nothing_and_finishes_later() {
    let k = kid().await;
    k.post_ok(&k.set(six())).await;
    k.sync().await;
    k.clear_writes();
    k.post_ok(&k.set(vec![])).await;
    k.work().await;
    assert!(k.writes().is_empty());
    k.dav.switches(|s| s.fail_gets = 2);
    assert_eq!(
        restore_held(&k.app.state, k.device, k.now()).await.unwrap(),
        6
    );
    k.work().await;
    k.post_ok(&k.set(vec![])).await;
    k.work().await;
    assert_eq!(k.writes().iter().filter(|w| w.0 == "DELETE").count(), 0);
    assert!(k.page().await.contains("data-kid-status=\"restoring\""));
    // The account's backoffs pass (each 429 backs it off): every one is read and handed back.
    k.set_now(t0() + TimeDelta::minutes(16));
    k.work().await;
    k.set_now(t0() + TimeDelta::hours(3));
    k.work().await;
    let back = k.post_ok(&k.set(vec![])).await["back"]
        .as_array()
        .unwrap()
        .len();
    assert_eq!(back, 6);
    k.work().await;
    assert_eq!(k.writes().iter().filter(|w| w.0 == "DELETE").count(), 0);
}

/// M2: a wipe the calendar undid before the day passed leaves no stale start: the next wipe is held too.
#[tokio::test]
async fn the_calendars_hold_survives_an_undone_wipe() {
    let k = kid().await;
    let set = k.set(six());
    k.post_ok(&set).await;
    k.sync().await;
    let bodies: Vec<(String, String)> = (1..=6)
        .map(|n| {
            let h = k.dav.href(&uid(n));
            (h.clone(), k.dav.body(&h).unwrap())
        })
        .collect();
    for (h, _) in &bodies {
        k.dav.parent_delete(h);
    }
    k.fetch().await;
    assert_eq!(k.post_ok(&set).await["back"], json!([]), "held");
    for (h, b) in &bodies {
        k.dav.parent_put(h, b);
    }
    k.fetch().await;
    assert_eq!(k.post_ok(&set).await["back"], json!([]));
    k.set_now(t0() + TimeDelta::hours(25));
    for (h, _) in &bodies {
        k.dav.parent_delete(h);
    }
    k.fetch().await;
    let back = k.post_ok(&k.set(six())).await["back"]
        .as_array()
        .unwrap()
        .len();
    assert_eq!(back, 0, "the second wipe is held as the first");
}

/// M1: the parent's undo while a delivered hand-back waits replaces the hand-back; nothing is overwritten.
#[tokio::test]
async fn a_parents_undo_while_a_hand_back_waits_replaces_it() {
    let k = kid().await;
    let kino = event(1, "Kino", "20261103T160000Z", "20261103T180000Z");
    let set = k.set(vec![
        kino.clone(),
        event(2, "Tannlege", "20261104T071500Z", "20261104T074500Z"),
    ]);
    k.post_ok(&set).await;
    k.sync().await;
    let href = k.dav.href(&uid(1));
    let original = k.dav.body(&href).unwrap();
    k.dav
        .parent_edit(&href, |b| b.replace("SUMMARY:Kino", "SUMMARY:Kino P1"));
    k.fetch().await;
    let back = k.post_ok(&set).await["back"].as_array().unwrap().clone();
    assert_eq!(back.len(), 1);
    let id = back[0]["id"].as_str().unwrap().to_string();
    // The phone applies P1; before it acknowledges, the parent undoes the edit.
    k.dav.parent_put(&href, &original);
    k.fetch().await;
    k.clear_writes();
    let applied = k.set_with(
        vec![
            event(1, "Kino P1", "20261103T160000Z", "20261103T180000Z"),
            event(2, "Tannlege", "20261104T071500Z", "20261104T074500Z"),
        ],
        json!({ "applied": [id] }),
    );
    let answer = k.post_ok(&applied).await;
    k.work().await;
    assert!(!k.dav.body(&href).unwrap().contains("Kino P1"));
    assert!(k.writes().is_empty());
    assert_eq!(answer["back"][0]["summary"], "Kino", "the undo handed back");
}

/// H3: a REPORT answered before a write is never judged after it.
#[tokio::test]
async fn a_report_from_before_a_write_says_nothing_about_it() {
    let k = kid().await;
    let other = event(2, "Tannlege", "20261104T071500Z", "20261104T074500Z");
    k.post_ok(&k.set(vec![
        event(1, "Kino", "20261103T160000Z", "20261103T180000Z"),
        other.clone(),
    ]))
    .await;
    k.sync().await;
    let href = k.dav.href(&uid(1));
    let old_body = k.dav.body(&href).unwrap();
    let old_etag: String =
        sqlx::query_scalar("SELECT etag FROM kid_caldav_events WHERE device_id = ? AND uid = ?")
            .bind(k.device)
            .bind(uid(1))
            .fetch_one(&k.app.db)
            .await
            .unwrap();
    let fetch_began = k.now();
    let stale = vec![crate::caldav::DavResource {
        href: href
            .trim_start_matches("https://dav.example.org")
            .to_string(),
        etag: Some(old_etag),
        data: old_body,
    }];
    k.set_now(t0() + TimeDelta::seconds(30));
    let edited = k.set(vec![
        event(1, "Kino med Ola", "20261103T160000Z", "20261103T180000Z"),
        other,
    ]);
    k.post_ok(&edited).await;
    k.work().await;
    assert!(k.dav.body(&href).unwrap().contains("Kino med Ola"));
    after_fetch(&k.app.state, k.source, &stale, fetch_began, fetch_began)
        .await
        .unwrap();
    let answer = k.post_ok(&edited).await;
    assert_eq!(answer["back"], json!([]));
    assert_eq!(
        answer["pending"], 1,
        "still waiting for a fetch that saw it (R-L2)"
    );
}

/// M3: the claim is a token: a job whose claim was taken over neither keeps nor releases the new one.
#[tokio::test]
async fn the_claim_is_kept_and_released_only_by_its_own_job() {
    let k = kid().await;
    let db = &k.app.db;
    let first = claim(db, k.account, t0()).await.unwrap().unwrap();
    assert!(claim(db, k.account, t0()).await.unwrap().is_none());
    assert!(
        keep_claim(db, k.account, &first, t0() + TimeDelta::minutes(4))
            .await
            .unwrap()
    );
    // Lost for good (a job that died): taken over after CLAIM_FOR.
    let later = t0() + TimeDelta::minutes(10);
    let second = claim(db, k.account, later).await.unwrap().unwrap();
    assert!(!keep_claim(db, k.account, &first, later).await.unwrap());
    assert!(keep_claim(db, k.account, &second, later).await.unwrap());
}

/// A POST while a write is in flight: the newer version is written next, the older never wins.
#[tokio::test]
async fn a_set_posted_during_a_write_wins() {
    let k = kid().await;
    k.post_ok(&k.set(vec![event(
        1,
        "Kino",
        "20261103T160000Z",
        "20261103T180000Z",
    )]))
    .await;
    k.app.fetch.hold();
    let state = k.app.state.clone();
    let (account, now) = (k.account, k.now());
    let job = tokio::spawn(async move { work(&state, account, now).await });
    k.app.fetch.wait_in_flight().await;
    k.post_ok(&k.set(vec![event(
        1,
        "Kino 2",
        "20261103T160000Z",
        "20261103T180000Z",
    )]))
    .await;
    k.app.fetch.release();
    job.await.unwrap().unwrap();
    k.work().await;
    k.fetch().await;
    assert!(k.dav.body(&k.dav.href(&uid(1))).unwrap().contains("Kino 2"));
    let answer = k
        .post_ok(&k.set(vec![event(
            1,
            "Kino 2",
            "20261103T160000Z",
            "20261103T180000Z",
        )]))
        .await;
    assert_eq!(
        (answer["back"].clone(), answer["pending"].clone()),
        (json!([]), json!(0))
    );
}

/// L2: a refused delete backs off instead of going round every pass.
#[tokio::test]
async fn a_refused_delete_backs_off() {
    let k = kid().await;
    k.post_ok(&k.set(vec![event(
        1,
        "Kino",
        "20261103T160000Z",
        "20261103T180000Z",
    )]))
    .await;
    k.sync().await;
    k.dav.switches(|s| s.writes_answer = Some(409));
    k.clear_writes();
    k.post_ok(&k.set(vec![])).await;
    k.work().await;
    k.work().await;
    assert_eq!(k.writes().len(), 1);
    assert_eq!(k.state_of(1).await.as_deref(), Some("deleting"));
}

/// L3: a write's 404 with the collection itself gone is no parent's delete.
#[tokio::test]
async fn a_gone_collection_hands_back_no_delete() {
    let k = kid().await;
    let kino = event(1, "Kino", "20261103T160000Z", "20261103T180000Z");
    k.post_ok(&k.set(vec![kino.clone()])).await;
    k.sync().await;
    k.dav.switches(|s| s.collection_gone = true);
    let mut moved = kino;
    moved["summary"] = "Kino flyttet".into();
    k.post_ok(&k.set(vec![moved.clone()])).await;
    k.work().await;
    assert_eq!(k.post_ok(&k.set(vec![moved])).await["back"], json!([]));
}

/// L5: an update whose ETag couldn't be read back keeps the old one: the parent's later delete is seen (412, 404),
/// never answered by a create.
#[tokio::test]
async fn a_lost_etag_after_an_update_never_becomes_a_create() {
    let k = kid().await;
    let kino = event(1, "Kino", "20261103T160000Z", "20261103T180000Z");
    k.post_ok(&k.set(vec![kino.clone()])).await;
    k.sync().await;
    k.dav.switches(|s| {
        s.put_without_etag = true;
        s.fail_gets = 1;
    });
    let mut v2 = kino.clone();
    v2["summary"] = "Kino 2".into();
    k.post_ok(&k.set(vec![v2.clone()])).await;
    k.work().await;
    k.dav.parent_delete(&k.dav.href(&uid(1)));
    let mut v3 = kino;
    v3["summary"] = "Kino 3".into();
    k.post_ok(&k.set(vec![v3.clone()])).await;
    k.due().await;
    k.clear_writes();
    k.work().await;
    assert!(
        k.writes().iter().all(|w| w.3.is_none()),
        "no create: {:?}",
        k.writes()
    );
    assert!(k.dav.body(&k.dav.href(&uid(1))).is_none());
    assert_eq!(k.post_ok(&k.set(vec![v3])).await["back"][0]["op"], "delete");
}

/// L7: a server whose ETags never match stops after a couple of tries per row.
#[tokio::test]
async fn again_and_again_backs_off() {
    let k = kid().await;
    let kino = event(1, "Kino", "20261103T160000Z", "20261103T180000Z");
    k.post_ok(&k.set(vec![kino.clone()])).await;
    k.sync().await;
    k.dav.switches(|s| s.fail_next_with_412 = 100);
    let mut v2 = kino;
    v2["summary"] = "Kino 2".into();
    k.clear_writes();
    k.post_ok(&k.set(vec![v2])).await;
    k.work().await;
    assert!(k.writes().len() <= 3, "{}", k.writes().len());
    k.work().await;
    assert!(k.writes().len() <= 3, "backed off");
}

/// L8 and N7: a calendar the last Test no longer lists says so; a second resource with the kid's UID at another
/// href is never taken for ours.
#[tokio::test]
async fn not_listed_says_so_and_a_copy_elsewhere_is_not_ours() {
    let k = kid().await;
    k.post_ok(&k.set(vec![event(
        1,
        "Kino",
        "20261103T160000Z",
        "20261103T180000Z",
    )]))
    .await;
    k.sync().await;
    // Another client's copy of the UID, its text changed, at another href that sorts first.
    let copy = k
        .dav
        .body(&k.dav.href(&uid(1)))
        .unwrap()
        .replace("SUMMARY:Kino", "SUMMARY:Kopi");
    k.dav.parent_put(&format!("{COLLECTION}0-kopi.ics"), &copy);
    k.fetch().await;
    assert_eq!(
        k.post_ok(&k.set(vec![event(
            1,
            "Kino",
            "20261103T160000Z",
            "20261103T180000Z"
        )]))
        .await["back"],
        json!([])
    );
    sqlx::query("UPDATE caldav_accounts SET calendars_json = '[]'")
        .execute(&k.app.db)
        .await
        .unwrap();
    k.work().await;
    assert!(k.page().await.contains("data-kid-status=\"not_listed\""));
}
