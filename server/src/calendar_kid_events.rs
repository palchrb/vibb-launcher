//! CalDAV write-back of the kid's own events (design 72b, `docs/design/72b-caldav-write-back.md` at the monorepo
//! root; migration `0120_calendar_kid_writes.sql`). The parent marks one CalDAV calendar, ticked for the phone, as the
//! kid's (Kalender's select "Barnets avtaler", `caldav:<source uid>`); the phone keeps writing in its own "Min
//! kalender" and posts the whole set of the kid's coming events ([post], `POST /api/devices/calendar/kid-events`);
//! the server diffs it against what it wrote and writes each event to `<collection>/<uid>.ics` with the account's app
//! password, which never goes to the phone. The parent's edits and deletes in that calendar come back as hand-backs
//! the phone acknowledges (`applied`). The calendar wins a clash (§12, the user's answer 1).
//!
//! - **The set** (D1.3): the kid's coming events (no past ones, no forward horizon), at most [MAX_ROWS] rows and
//!   [MAX_BYTES], each in D2.7's canonical form ([canonical_text], [Version], [version_hash]), validated whole: one row
//!   that doesn't read, a UID outside D4.9's pattern ([valid_uid]) or a phone tag another device holds is a 400 for
//!   the set. One POST per [POST_GAP] per phone.
//! - **Nothing is lost** (D2.6, QA H1/H2): a missing event is a delete only when its row is written and `ok`, not in
//!   `ended`, ends after max(`from`, now), lies before `cut_from` and outside `cut_uids` when the set is cut, the
//!   phone's clock is within [CLOCK_SKEW], and the delete is not one of a mass delete held [HOLD] by time. An ended
//!   event becomes `past` - never deleted, never written. A parent's series or invitation turns the row into a
//!   `released` tombstone. A uid with a waiting hand-back is never written; only an acknowledgement of its current id
//!   clears it. Never a PUT over content the server hasn't read: every 412, a create's 404 and a `no-uid-conflict`
//!   GET the resource and compare it by content (`attempted_hash`, the base `remote_hash`).
//! - **The writes** ([work]): one job per account at a time (`write_claimed_at`), one request at a time through
//!   `music_fetch`, at most [WRITES_A_MINUTE] a minute, a 429/503 backing the account off; every write conditional
//!   (`If-None-Match: *` or `If-Match` with a strong ETag), no redirect followed, the 403 read by its body, a 401 the
//!   account's `login_refused`.
//! - **The way back** ([after_fetch], from the marked calendar's fetch): each resource matched to its uid by the
//!   VEVENT's UID; another ETag *and* another content hash is a parent's edit; a uid missing from the REPORT is
//!   confirmed by its own GET (404 = the parent's delete). The calendar's mass delete is held [HOLD] too.
//! - No event text in any log, error or settings-log row; the set and row types have no `Debug`.

use std::collections::{HashMap, HashSet};

use axum::body::Bytes;
use axum::extract::State;
use axum::http::{HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use chrono::{DateTime, NaiveDate, NaiveDateTime, TimeDelta, TimeZone, Utc};
use chrono_tz::Tz;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};

use crate::AppState;
use crate::caldav::{DavRequest, DavResource, WriteLogin};
use crate::calendar_feeds::FeedError;
use crate::ics::{self, KidResource, KidWhen};
use crate::music_sweep::{parse_stamp, stamp};

/// A launcher that hands its kid's events over reports this capability; without it the policy's `kid_caldav` is
/// `null` and the PWA offers no CalDAV calendar for them (D5.3).
pub const CAPABILITY: &str = "calendar_kid_caldav_v1";
/// Rows in a set (D1.3).
pub const MAX_ROWS: usize = 300;
/// The set's `events` as JSON, in bytes (D1.3); past it a 413.
pub const MAX_BYTES: usize = 512_000;
/// The uids a cut set lists (R-L3); past it `cut_uids` is `null` and nothing is deleted.
pub const MAX_CUT_UIDS: usize = 2000;
/// `ended` uids (R-M1).
pub const MAX_ENDED: usize = 300;
/// `applied` ids (D2.3).
pub const MAX_APPLIED: usize = 300;
/// The route's own body limit (the sizes above are checked in [post], so a 413 is said on the page).
pub const BODY_LIMIT: usize = 1024 * 1024;
/// One POST per phone this often (D1.7).
pub const POST_GAP: TimeDelta = TimeDelta::seconds(10);
/// A `from` further from the server's clock freezes the set's deletes (D2.6).
pub const CLOCK_SKEW: TimeDelta = TimeDelta::days(1);
/// A mass delete (either side's) waits this long (D2.6) ...
pub const HOLD: TimeDelta = TimeDelta::hours(24);
/// ... when it is at least this many events and more than half of those written.
pub const HOLD_AT: usize = 5;
/// A `past` row goes this long after its end; a `released` tombstone after this long absent (D2.4, D2.6).
pub const KEEP_DAYS: i64 = 60;
/// Writes per account per minute (D4.2).
pub const WRITES_A_MINUTE: i64 = 60;
/// The marked calendar is fetched after a job that wrote, at most this often (QA L5).
pub const FETCH_GAP: TimeDelta = TimeDelta::seconds(60);
/// A job's claim on its account is taken over after this (a job that died).
const CLAIM_FOR: TimeDelta = TimeDelta::minutes(5);
/// D2.7's cuts, in code points (design 72's `ics` caps).
pub const SUMMARY_MAX: usize = 200;
pub const LOCATION_MAX: usize = 200;
pub const DESCRIPTION_MAX: usize = 2000;
/// A stored ETag or href (D4.1).
const MAX_HEADER_VALUE: usize = 500;

/// The job's wake-up and the tests' clock (`AppState.kid_writes`).
#[derive(Default)]
pub struct KidWrites {
    notify: tokio::sync::Notify,
    clock: std::sync::Mutex<Option<DateTime<Utc>>>,
}

impl KidWrites {
    /// A POST stored a set: the job runs now rather than at its next pass.
    pub fn wake(&self) {
        self.notify.notify_one();
    }

    /// The server's time (the tests' fake one when set).
    pub fn now(&self) -> DateTime<Utc> {
        self.clock
            .lock()
            .ok()
            .and_then(|c| *c)
            .unwrap_or_else(Utc::now)
    }

    #[cfg(test)]
    pub fn set_clock(&self, at: DateTime<Utc>) {
        *self.clock.lock().unwrap() = Some(at);
    }
}

// ---- the canonical form (D2.7) -----------------------------------------------------------------

/// A text field's canonical form: `\r\n` and `\r` as `\n`, C0 controls other than LF and TAB dropped, then cut by
/// code point. Nothing else changes (no trim, no whitespace folding, no NFC).
pub fn canonical_text(raw: &str, max: usize) -> String {
    raw.replace("\r\n", "\n")
        .replace('\r', "\n")
        .chars()
        .filter(|c| !((*c as u32) < 0x20 && *c != '\n' && *c != '\t'))
        .take(max)
        .collect()
}

/// A time of the wire (D1.3): an all-day date, or a UTC instant.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum KidTime {
    Day(NaiveDate),
    At(NaiveDateTime),
}

const BASIC_UTC: &str = "%Y%m%dT%H%M%SZ";
const BASIC_DATE: &str = "%Y%m%d";

impl KidTime {
    /// `20261103T140000Z`, or `20261103` all-day.
    pub fn wire(self) -> String {
        match self {
            KidTime::Day(d) => d.format(BASIC_DATE).to_string(),
            KidTime::At(t) => t.format(BASIC_UTC).to_string(),
        }
    }

    /// A wire time of the given kind; `None` when it doesn't read.
    pub fn parse(value: &str, all_day: bool) -> Option<KidTime> {
        if all_day {
            (value.len() == 8)
                .then(|| NaiveDate::parse_from_str(value, BASIC_DATE).ok())
                .flatten()
                .map(KidTime::Day)
        } else {
            (value.len() == 16)
                .then(|| NaiveDateTime::parse_from_str(value, BASIC_UTC).ok())
                .flatten()
                .map(KidTime::At)
        }
    }

    /// Either kind (`cut_from`).
    fn parse_any(value: &str) -> Option<KidTime> {
        KidTime::parse(value, value.len() == 8)
    }

    /// The instant (an all-day date at UTC midnight, as the phone stores it).
    pub fn instant(self) -> DateTime<Utc> {
        match self {
            KidTime::Day(d) => d.and_time(chrono::NaiveTime::MIN).and_utc(),
            KidTime::At(t) => t.and_utc(),
        }
    }
}

/// One version of a kid event, canonical (D2.7): what is hashed, written and handed back.
#[derive(Serialize, Clone, PartialEq, Eq)]
pub struct Version {
    pub summary: String,
    pub location: String,
    pub description: String,
    pub start: String,
    pub end: String,
    pub all_day: bool,
}

impl Version {
    /// The canonical version of these fields and times: the texts cut, an all-day end at least a day after its start
    /// (a missing one a day), a timed end never before its start (a missing one a zero length).
    pub fn of(
        summary: &str,
        location: &str,
        description: &str,
        start: KidTime,
        end: Option<KidTime>,
    ) -> Version {
        let end = match (start, end) {
            (KidTime::Day(s), Some(KidTime::Day(e))) if e > s => KidTime::Day(e),
            (KidTime::Day(s), _) => KidTime::Day(s.succ_opt().unwrap_or(s)),
            (KidTime::At(s), Some(KidTime::At(e))) if e >= s => KidTime::At(e),
            (KidTime::At(s), _) => KidTime::At(s),
        };
        Version {
            summary: canonical_text(summary, SUMMARY_MAX),
            location: canonical_text(location, LOCATION_MAX),
            description: canonical_text(description, DESCRIPTION_MAX),
            start: start.wire(),
            end: end.wire(),
            all_day: matches!(start, KidTime::Day(_)),
        }
    }

    fn start_time(&self) -> Option<KidTime> {
        KidTime::parse(&self.start, self.all_day)
    }

    fn end_time(&self) -> Option<KidTime> {
        KidTime::parse(&self.end, self.all_day)
    }
}

/// 16 hex of SHA-256 over the version's canonical JSON `{summary, location, description, start, end, all_day}`
/// (D2.7) - the zone and the bytes never count.
pub fn version_hash(version: &Version) -> String {
    let json = serde_json::to_string(version).expect("a version serializes");
    hex::encode(Sha256::digest(json.as_bytes()))[..16].to_string()
}

/// A hand-back's id (R-M2): 16 hex of SHA-256 over the uid and the handed-back version's hash (`delete` for a
/// delete) - a second parent edit is a new id.
pub fn back_id(uid: &str, version_hash: &str) -> String {
    hex::encode(Sha256::digest(format!("{uid}{version_hash}").as_bytes()))[..16].to_string()
}

/// A resource's time resolved (D2.7): a floating one in `zone` (the zone the phone last sent for the event), else
/// UTC; an all-day date as it is.
fn resolved(when: KidWhen, zone: Option<Tz>) -> KidTime {
    let utc = |at: chrono::DateTime<Tz>| KidTime::At(at.naive_utc());
    let local = |tz: Tz, t: NaiveDateTime| {
        tz.from_local_datetime(&t)
            .earliest()
            .or_else(|| {
                tz.from_local_datetime(&(t + TimeDelta::hours(1)))
                    .earliest()
            })
            .map(utc)
            .unwrap_or(KidTime::At(t))
    };
    match when {
        KidWhen::Date(d) => KidTime::Day(d),
        KidWhen::Utc(t) => KidTime::At(t),
        KidWhen::Zoned(t, tz) => local(tz, t),
        KidWhen::Floating(t) => match zone {
            Some(tz) => local(tz, t),
            None => KidTime::At(t),
        },
    }
}

/// A resource's canonical version (D2.7): a missing DTEND a zero length (a day all-day), a DURATION resolved to an
/// end, a floating time in `zone`.
pub fn version_of_parts(
    summary: &str,
    location: &str,
    description: &str,
    start: KidWhen,
    end: Option<KidWhen>,
    duration: Option<TimeDelta>,
    zone: Option<Tz>,
) -> Version {
    let start = resolved(start, zone);
    let end = match (start, end) {
        (KidTime::Day(_), Some(KidWhen::Date(e))) => Some(KidTime::Day(e)),
        (KidTime::Day(_), Some(other)) => Some(match resolved(other, zone) {
            KidTime::At(t) => KidTime::Day(t.date()),
            day => day,
        }),
        (KidTime::Day(s), None) => duration.map(|d| {
            let days = d.num_days().max(1) as u64;
            KidTime::Day(s.checked_add_days(chrono::Days::new(days)).unwrap_or(s))
        }),
        (KidTime::At(_), Some(KidWhen::Date(e))) => Some(resolved(
            KidWhen::Floating(e.and_time(chrono::NaiveTime::MIN)),
            zone,
        )),
        (KidTime::At(_), Some(other)) => Some(resolved(other, zone)),
        (KidTime::At(s), None) => {
            duration.map(|d| KidTime::At(s.checked_add_signed(d).unwrap_or(s)))
        }
    };
    Version::of(summary, location, description, start, end)
}

fn resource_version(resource: &KidResource, zone: Option<Tz>) -> Version {
    version_of_parts(
        &resource.summary,
        &resource.location,
        &resource.description,
        resource.start,
        resource.end,
        resource.duration,
        zone,
    )
}

// ---- the set (D1.3) ----------------------------------------------------------------------------

/// D4.9's pattern: `vibb-` + the 8-hex phone tag + a lowercase UUID.
pub fn valid_uid(uid: &str) -> bool {
    let b = uid.as_bytes();
    let hex = |range: std::ops::Range<usize>| {
        b.get(range)
            .is_some_and(|s| s.iter().all(|c| matches!(c, b'0'..=b'9' | b'a'..=b'f')))
    };
    b.len() == 50
        && uid.starts_with("vibb-")
        && hex(5..13)
        && b[13] == b'-'
        && hex(14..22)
        && b[22] == b'-'
        && hex(23..27)
        && b[27] == b'-'
        && hex(28..32)
        && b[32] == b'-'
        && hex(33..37)
        && b[37] == b'-'
        && hex(38..50)
}

/// The phone tag of a valid uid.
fn tag_of(uid: &str) -> &str {
    &uid[5..13]
}

fn hex16(value: &str) -> bool {
    value.len() == 16
        && value
            .bytes()
            .all(|c| matches!(c, b'0'..=b'9' | b'a'..=b'f'))
}

/// One event of the set as the phone sent it, canonical. No `Debug`: it holds the kid's text.
#[derive(Serialize, Deserialize, Clone)]
pub struct KidEvent {
    pub uid: String,
    pub summary: String,
    pub location: String,
    pub description: String,
    pub start: String,
    pub end: String,
    pub all_day: bool,
    pub tz: Option<String>,
}

impl KidEvent {
    pub fn version(&self) -> Option<Version> {
        let start = KidTime::parse(&self.start, self.all_day)?;
        let end = KidTime::parse(&self.end, self.all_day)?;
        Some(Version::of(
            &self.summary,
            &self.location,
            &self.description,
            start,
            Some(end),
        ))
    }

    fn zone(&self) -> Option<Tz> {
        self.tz.as_deref().and_then(|z| z.parse::<Tz>().ok())
    }
}

/// A POST's set, read whole. No `Debug`.
struct Set {
    calendar: String,
    rev: String,
    from: DateTime<Utc>,
    cut: i64,
    cut_from: Option<KidTime>,
    cut_uids: Option<Vec<String>>,
    ended: Vec<String>,
    applied: Vec<String>,
    events: Vec<KidEvent>,
}

/// Every key read explicitly (QA M3: a set is taken whole or refused whole, never through a silent drop).
fn read_set(body: &Value) -> Result<Set, &'static str> {
    let bad = "bad_set";
    let obj = body.as_object().ok_or(bad)?;
    let string = |key: &str| obj.get(key).and_then(Value::as_str).ok_or(bad);
    let calendar = string("calendar")?.to_string();
    if !crate::calendar_feeds::valid_uid(&calendar) {
        return Err(bad);
    }
    let rev = string("rev")?.to_string();
    if !hex16(&rev) {
        return Err(bad);
    }
    let from = string("from")
        .ok()
        .and_then(|f| KidTime::parse(f, false))
        .ok_or(bad)?
        .instant();
    let cut = obj
        .get("cut")
        .and_then(Value::as_i64)
        .filter(|c| *c >= 0)
        .ok_or(bad)?;
    let cut_from = match obj.get("cut_from") {
        Some(Value::Null) => None,
        Some(Value::String(s)) => Some(KidTime::parse_any(s).ok_or(bad)?),
        _ => return Err(bad),
    };
    let uids = |value: &Value, max: usize| -> Result<Vec<String>, &'static str> {
        let list = value.as_array().ok_or(bad)?;
        if list.len() > max {
            return Err(bad);
        }
        list.iter()
            .map(|u| {
                u.as_str()
                    .filter(|u| valid_uid(u))
                    .map(str::to_string)
                    .ok_or(bad)
            })
            .collect()
    };
    let cut_uids = match obj.get("cut_uids") {
        Some(Value::Null) => None,
        Some(list) => Some(uids(list, MAX_CUT_UIDS)?),
        None => return Err(bad),
    };
    let ended = uids(obj.get("ended").ok_or(bad)?, MAX_ENDED)?;
    let applied_list = obj.get("applied").and_then(Value::as_array).ok_or(bad)?;
    if applied_list.len() > MAX_APPLIED {
        return Err(bad);
    }
    let applied = applied_list
        .iter()
        .map(|a| {
            a.as_str()
                .filter(|a| hex16(a))
                .map(str::to_string)
                .ok_or(bad)
        })
        .collect::<Result<Vec<_>, _>>()?;
    let rows = obj.get("events").and_then(Value::as_array).ok_or(bad)?;
    if rows.len() > MAX_ROWS {
        return Err(bad);
    }
    let mut taken = Vec::with_capacity(rows.len());
    let mut seen = HashSet::new();
    for row in rows {
        let r = row.as_object().ok_or(bad)?;
        let text = |key: &str| r.get(key).and_then(Value::as_str).ok_or(bad);
        let uid = text("uid")?;
        if !valid_uid(uid) || !seen.insert(uid.to_string()) {
            return Err(bad);
        }
        let all_day = r.get("all_day").and_then(Value::as_bool).ok_or(bad)?;
        let start = text("start")?;
        let end = text("end")?;
        if KidTime::parse(start, all_day).is_none() || KidTime::parse(end, all_day).is_none() {
            return Err(bad);
        }
        let tz = match r.get("tz") {
            Some(Value::Null) => None,
            Some(Value::String(z))
                if z.len() <= 64
                    && z.bytes()
                        .all(|c| c.is_ascii_alphanumeric() || b"/_+-".contains(&c)) =>
            {
                Some(z.clone())
            }
            _ => return Err(bad),
        };
        taken.push(KidEvent {
            uid: uid.to_string(),
            summary: canonical_text(text("summary")?, SUMMARY_MAX),
            location: canonical_text(text("location")?, LOCATION_MAX),
            description: canonical_text(text("description")?, DESCRIPTION_MAX),
            start: start.to_string(),
            end: end.to_string(),
            all_day,
            tz,
        });
    }
    taken.sort_by(|a, b| a.uid.cmp(&b.uid));
    Ok(Set {
        calendar,
        rev,
        from,
        cut,
        cut_from,
        cut_uids,
        ended,
        applied,
        events: taken,
    })
}

/// A set read and encoded again as the server keeps it (`intake_limits`: every key the contract's `snapshot` has
/// survives the reading).
#[cfg(test)]
pub(crate) fn set_as_read(body: &Value) -> Option<Value> {
    let set = read_set(body).ok()?;
    Some(serde_json::json!({
        "calendar": set.calendar,
        "rev": set.rev,
        "from": KidTime::At(set.from.naive_utc()).wire(),
        "cut": set.cut,
        "cut_from": set.cut_from.map(KidTime::wire),
        "cut_uids": set.cut_uids,
        "ended": set.ended,
        "applied": set.applied,
        "events": set.events,
    }))
}

/// The set as stored (`device_kid_events`). No `Debug`.
struct StoredSet {
    rev: Option<String>,
    events: Vec<KidEvent>,
    from: Option<DateTime<Utc>>,
    cut: i64,
    cut_from: Option<KidTime>,
    cut_uids: Option<Vec<String>>,
    ended: HashSet<String>,
    held_since: Option<DateTime<Utc>>,
    held_deletes: usize,
    error: Option<String>,
}

#[derive(sqlx::FromRow)]
struct SetRow {
    rev: Option<String>,
    events_json: Option<String>,
    from_at: Option<String>,
    cut: i64,
    cut_from: Option<String>,
    cut_uids: Option<String>,
    ended: String,
    held_since: Option<String>,
    held_deletes: i64,
    error: Option<String>,
}

async fn stored_set(
    db: impl sqlx::SqliteExecutor<'_>,
    device_id: i64,
) -> Result<Option<StoredSet>, sqlx::Error> {
    let row: Option<SetRow> = sqlx::query_as(
        "SELECT rev, events_json, from_at, cut, cut_from, cut_uids, ended, held_since, held_deletes, error \
         FROM device_kid_events WHERE device_id = ?",
    )
    .bind(device_id)
    .fetch_optional(db)
    .await?;
    Ok(row.map(|r| StoredSet {
        rev: r.rev,
        events: r
            .events_json
            .as_deref()
            .and_then(|j| serde_json::from_str(j).ok())
            .unwrap_or_default(),
        from: r.from_at.as_deref().and_then(parse_stamp),
        cut: r.cut,
        cut_from: r.cut_from.as_deref().and_then(KidTime::parse_any),
        cut_uids: r
            .cut_uids
            .as_deref()
            .and_then(|j| serde_json::from_str(j).ok()),
        ended: serde_json::from_str(&r.ended).unwrap_or_default(),
        held_since: r.held_since.as_deref().and_then(parse_stamp),
        held_deletes: r.held_deletes.max(0) as usize,
        error: r.error,
    }))
}

// ---- the rows ----------------------------------------------------------------------------------

/// One kid event the server wrote or read for a phone. No `Debug`: `back_json` holds the parent's text.
#[derive(sqlx::FromRow, Clone)]
struct Row {
    uid: String,
    href: Option<String>,
    etag: Option<String>,
    sent_hash: Option<String>,
    remote_hash: Option<String>,
    attempted_hash: Option<String>,
    sequence: i64,
    created_at_remote: Option<String>,
    start_at: Option<String>,
    end_at: Option<String>,
    state: String,
    failures: i64,
    next_at: Option<String>,
    back_id: Option<String>,
    back_json: Option<String>,
    back_after: Option<String>,
    fetch_wait: Option<String>,
    restore_wanted: bool,
}

const ROW_COLUMNS: &str = "uid, href, etag, sent_hash, remote_hash, attempted_hash, sequence, created_at_remote, \
     start_at, end_at, state, failures, next_at, back_id, back_json, back_after, fetch_wait, restore_wanted";

/// The versions sent whose answers weren't seen (`attempted_hash`, at most [MAX_ATTEMPTS], the newest last): a 412
/// that reads one of them is our own lost answer (R-L1a), never a parent's edit.
const MAX_ATTEMPTS: usize = 3;

impl Row {
    fn attempts(&self) -> impl Iterator<Item = &str> {
        self.attempted_hash
            .as_deref()
            .unwrap_or("")
            .split(',')
            .filter(|a| !a.is_empty())
    }

    fn was_attempted(&self, hash: &str) -> bool {
        self.attempts().any(|a| a == hash)
    }

    fn last_attempt(&self) -> Option<&str> {
        self.attempts().last()
    }

    /// `attempted_hash` with `hash` the newest attempt.
    fn with_attempt(&self, hash: &str) -> String {
        let mut kept: Vec<&str> = self.attempts().filter(|a| *a != hash).collect();
        kept.push(hash);
        let from = kept.len().saturating_sub(MAX_ATTEMPTS);
        kept[from..].join(",")
    }

    /// The resource may exist on the calendar: written (an ETag), or a create sent whose answer was lost.
    fn on_calendar(&self) -> bool {
        self.etag.is_some()
            || (matches!(self.state.as_str(), "pending" | "kept") && self.attempted_hash.is_some())
    }

    /// Its hand-back is a delete.
    fn back_is_delete(&self) -> bool {
        self.back_json
            .as_deref()
            .is_some_and(|b| b.contains("\"op\":\"delete\""))
    }

    /// Its hand-back is held (the calendar's mass delete, D2.6) and so was never sent.
    fn back_held(&self, now: DateTime<Utc>) -> bool {
        self.back_after
            .as_deref()
            .and_then(parse_stamp)
            .is_some_and(|after| after > now)
    }

    fn written(&self) -> bool {
        self.etag.is_some() && !matches!(self.state.as_str(), "past" | "released" | "gone")
    }
}

async fn rows_of(
    db: impl sqlx::SqliteExecutor<'_>,
    device_id: i64,
) -> Result<HashMap<String, Row>, sqlx::Error> {
    let rows: Vec<Row> = sqlx::query_as(&format!(
        "SELECT {ROW_COLUMNS} FROM kid_caldav_events WHERE device_id = ?"
    ))
    .bind(device_id)
    .fetch_all(db)
    .await?;
    Ok(rows.into_iter().map(|r| (r.uid.clone(), r)).collect())
}

/// A precise stamp for `fetch_wait` (R-L2: a write and the fetch after it may share a second).
fn fine_stamp(at: DateTime<Utc>) -> String {
    at.format("%Y-%m-%d %H:%M:%S%.3f").to_string()
}

fn instant_stamp(time: Option<KidTime>) -> Option<String> {
    time.map(|t| stamp(t.instant()))
}

// ---- the plan (D2.5, D2.6) ---------------------------------------------------------------------

/// What a set asks of the rows.
#[derive(Default)]
struct Plan {
    /// A write to make (create or update).
    writes: Vec<String>,
    /// Already as the phone has it: `sent_hash` := the hash, no write.
    same: Vec<(String, String)>,
    /// A delete that passed every test (held or not).
    deletes: Vec<String>,
    /// Ended: kept on the calendar as it is.
    past: Vec<String>,
    /// Missing, not deletable now (cut, frozen, a put-back asked for): a waiting write or delete dropped.
    keep: Vec<String>,
    /// A delete kept back by the phone's clock (L4): the set isn't taken as done.
    frozen_keeps: bool,
    /// Never on the calendar: the row goes.
    drop: Vec<String>,
    released_present: Vec<String>,
    released_absent: Vec<String>,
    /// The kid's mass delete (D2.6): at least [HOLD_AT] deletes and more than half of the written events.
    mass: bool,
}

fn plan(set: &StoredSet, rows: &HashMap<String, Row>, now: DateTime<Utc>) -> Plan {
    let mut out = Plan::default();
    let present: HashMap<&str, &KidEvent> =
        set.events.iter().map(|e| (e.uid.as_str(), e)).collect();
    for event in &set.events {
        let Some(version) = event.version() else {
            continue;
        };
        let h = version_hash(&version);
        let Some(row) = rows.get(&event.uid) else {
            out.writes.push(event.uid.clone());
            continue;
        };
        if row.back_id.is_some() {
            continue;
        }
        match row.state.as_str() {
            "released" => out.released_present.push(row.uid.clone()),
            "refused" => {
                if row.last_attempt() != Some(h.as_str()) {
                    out.writes.push(row.uid.clone());
                }
            }
            "gone" => out.writes.push(row.uid.clone()),
            "past" => {
                if row.remote_hash.as_deref() == Some(h.as_str()) {
                    out.same.push((row.uid.clone(), h));
                } else {
                    out.writes.push(row.uid.clone());
                }
            }
            // ok, pending, deleting (the kid undid a delete), kept.
            _ => {
                // D2.5 rule 1: written only when the kid changed it (`sent_hash`) and the calendar doesn't hold it
                // already (`remote_hash`); a row never confirmed on the calendar (no ETag) is written (or found).
                let sent = row.sent_hash.as_deref() == Some(h.as_str());
                let there = row.remote_hash.as_deref() == Some(h.as_str());
                if row.etag.is_some() && (sent || there) {
                    out.same.push((row.uid.clone(), h));
                } else {
                    out.writes.push(row.uid.clone());
                }
            }
        }
    }
    let lower = set.from.map_or(now, |from| from.max(now));
    let frozen = set.from.is_none_or(|from| (from - now).abs() > CLOCK_SKEW);
    for row in rows.values() {
        if present.contains_key(row.uid.as_str()) || row.back_id.is_some() {
            continue;
        }
        let end = row.end_at.as_deref().and_then(parse_stamp);
        let start = row.start_at.as_deref().and_then(parse_stamp);
        match row.state.as_str() {
            "released" => out.released_absent.push(row.uid.clone()),
            "past" => {
                if end.is_none_or(|e| e + TimeDelta::days(KEEP_DAYS) < now) {
                    out.drop.push(row.uid.clone());
                }
            }
            "gone" => {}
            _ if !row.on_calendar() => out.drop.push(row.uid.clone()),
            _ => {
                let ended = set.ended.contains(&row.uid) || end.is_none_or(|e| e <= lower);
                if ended {
                    out.past.push(row.uid.clone());
                    continue;
                }
                let cut_keeps = set.cut > 0
                    && match &set.cut_uids {
                        None => true,
                        Some(uids) => {
                            uids.contains(&row.uid)
                                || match (set.cut_from, start) {
                                    (Some(from), Some(start)) => start >= from.instant(),
                                    _ => true,
                                }
                        }
                    };
                if frozen || cut_keeps || row.restore_wanted {
                    out.frozen_keeps |= frozen && !cut_keeps && !row.restore_wanted;
                    out.keep.push(row.uid.clone());
                } else {
                    out.deletes.push(row.uid.clone());
                }
            }
        }
    }
    let written = rows.values().filter(|r| r.written()).count();
    out.mass = out.deletes.len() >= HOLD_AT && out.deletes.len() * 2 > written;
    out
}

/// The plan stored: rows made, states set, the kid's hold judged by time. Returns whether the deletes are held.
async fn apply_plan(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    device_id: i64,
    source_id: i64,
    set: &StoredSet,
    plan: &Plan,
    now: DateTime<Utc>,
) -> Result<bool, sqlx::Error> {
    let by_uid: HashMap<&str, &KidEvent> = set.events.iter().map(|e| (e.uid.as_str(), e)).collect();
    for uid in &plan.writes {
        let Some(version) = by_uid.get(uid.as_str()).and_then(|e| e.version()) else {
            continue;
        };
        sqlx::query(
            "INSERT INTO kid_caldav_events (device_id, uid, source_id, state, start_at, end_at) \
             VALUES (?, ?, ?, 'pending', ?, ?) \
             ON CONFLICT(device_id, uid) DO UPDATE SET state = 'pending', \
             start_at = COALESCE(kid_caldav_events.start_at, excluded.start_at), \
             end_at = COALESCE(kid_caldav_events.end_at, excluded.end_at)",
        )
        .bind(device_id)
        .bind(uid)
        .bind(source_id)
        .bind(instant_stamp(version.start_time()))
        .bind(instant_stamp(version.end_time()))
        .execute(&mut **tx)
        .await?;
    }
    for (uid, h) in &plan.same {
        sqlx::query(
            "UPDATE kid_caldav_events SET sent_hash = ?, state = 'ok' WHERE device_id = ? AND uid = ?",
        )
        .bind(h)
        .bind(device_id)
        .bind(uid)
        .execute(&mut **tx)
        .await?;
    }
    for uid in &plan.past {
        sqlx::query(
            "UPDATE kid_caldav_events SET state = 'past', next_at = NULL WHERE device_id = ? AND uid = ?",
        )
        .bind(device_id)
        .bind(uid)
        .execute(&mut **tx)
        .await?;
    }
    for uid in &plan.keep {
        // Neither written nor deleted while it is kept out of the set; never counted as waiting (code QA N5).
        sqlx::query(
            "UPDATE kid_caldav_events SET state = 'kept', next_at = NULL WHERE device_id = ? AND uid = ? \
             AND state IN ('pending', 'deleting')",
        )
        .bind(device_id)
        .bind(uid)
        .execute(&mut **tx)
        .await?;
    }
    for uid in &plan.drop {
        sqlx::query("DELETE FROM kid_caldav_events WHERE device_id = ? AND uid = ?")
            .bind(device_id)
            .bind(uid)
            .execute(&mut **tx)
            .await?;
    }
    for uid in &plan.released_present {
        sqlx::query(
            "UPDATE kid_caldav_events SET released_absent_since = NULL WHERE device_id = ? AND uid = ?",
        )
        .bind(device_id)
        .bind(uid)
        .execute(&mut **tx)
        .await?;
    }
    for uid in &plan.released_absent {
        sqlx::query(
            "UPDATE kid_caldav_events SET released_absent_since = COALESCE(released_absent_since, ?) \
             WHERE device_id = ? AND uid = ?",
        )
        .bind(stamp(now))
        .bind(device_id)
        .bind(uid)
        .execute(&mut **tx)
        .await?;
        sqlx::query(
            "DELETE FROM kid_caldav_events WHERE device_id = ? AND uid = ? AND state = 'released' \
             AND released_absent_since <= ?",
        )
        .bind(device_id)
        .bind(uid)
        .bind(stamp(now - TimeDelta::days(KEEP_DAYS)))
        .execute(&mut **tx)
        .await?;
    }
    // The kid's mass delete waits 24 h by time (QA M4): once held, every delete waits out the day (a later set with
    // fewer deletes); with no delete left the hold ends. A hold that has run out while a delete is stuck (refused,
    // backed off, stopped) starts again when a new mass delete comes (code QA L1).
    let expired = set.held_since.is_some_and(|since| now - since >= HOLD);
    let (held_since, held_deletes) = if plan.deletes.is_empty() {
        (None, 0)
    } else if plan.mass
        && (set.held_since.is_none() || (expired && plan.deletes.len() > set.held_deletes))
    {
        (Some(now), plan.deletes.len())
    } else if set.held_since.is_some() {
        // The count as this plan has it, never a running maximum (revision check RL1): a later wipe below an old
        // peak is a new mass delete too.
        (set.held_since, plan.deletes.len())
    } else {
        (None, 0)
    };
    let holding = held_since.is_some_and(|since| now - since < HOLD);
    let state = if holding { "ok" } else { "deleting" };
    for uid in &plan.deletes {
        sqlx::query(
            "UPDATE kid_caldav_events SET state = ? WHERE device_id = ? AND uid = ? AND back_id IS NULL \
             AND restore_wanted = 0",
        )
        .bind(state)
        .bind(device_id)
        .bind(uid)
        .execute(&mut **tx)
        .await?;
    }
    // `accepted` stays the old rev while a delete is held or frozen by the phone's clock: the phone keeps the deleted
    // uids skipped (D3.2, launcher QA L4).
    sqlx::query(
        "UPDATE device_kid_events SET held_since = ?, held_kind = ?, held_deletes = ?, \
         accepted = CASE WHEN ? THEN accepted ELSE rev END WHERE device_id = ?",
    )
    .bind(held_since.map(stamp))
    .bind(held_since.map(|_| "kid"))
    .bind(held_deletes as i64)
    .bind(holding || plan.frozen_keeps)
    .bind(device_id)
    .execute(&mut **tx)
    .await?;
    Ok(holding)
}

/// The set of a phone planned again from what is stored (a POST, a pass, an acknowledgement).
async fn replan(
    state: &AppState,
    device_id: i64,
    source_id: i64,
    now: DateTime<Utc>,
) -> Result<(), sqlx::Error> {
    let mut tx = state.db.begin().await?;
    let Some(set) = stored_set(&mut *tx, device_id).await? else {
        return Ok(());
    };
    if set.rev.is_none() {
        return Ok(());
    }
    let rows = rows_of(&mut *tx, device_id).await?;
    let plan = plan(&set, &rows, now);
    apply_plan(&mut tx, device_id, source_id, &set, &plan, now).await?;
    tx.commit().await
}

// ---- counts, the policy ------------------------------------------------------------------------

/// The policy's `calendar.kid_caldav` (D1.6): the marked calendar's feed uid, the last set taken, the hand-backs and
/// the writes waiting. `None` when nothing is marked (the caller also sends `null` without [CAPABILITY]).
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct KidCaldavPolicy {
    pub id: String,
    pub accepted: Option<String>,
    pub back: i64,
    pub pending: i64,
}

/// The marked calendar of a phone: (source id, source uid, account id).
async fn marked(
    db: impl sqlx::SqliteExecutor<'_>,
    device_id: i64,
) -> Result<Option<(i64, String, Option<i64>)>, sqlx::Error> {
    sqlx::query_as(
        "SELECT s.id, s.uid, s.account_id FROM device_calendar_feeds d JOIN calendar_sources s ON s.id = d.source_id \
         WHERE d.device_id = ? AND d.kid_writes = 1 AND s.kind = 'caldav'",
    )
    .bind(device_id)
    .fetch_optional(db)
    .await
}

/// The hand-backs the phone may have now (a held one later, D2.6).
async fn back_count(
    db: impl sqlx::SqliteExecutor<'_>,
    device_id: i64,
    now: DateTime<Utc>,
) -> Result<i64, sqlx::Error> {
    sqlx::query_scalar(
        "SELECT COUNT(*) FROM kid_caldav_events WHERE device_id = ? AND back_id IS NOT NULL \
         AND (back_after IS NULL OR back_after <= ?)",
    )
    .bind(device_id)
    .bind(stamp(now))
    .fetch_one(db)
    .await
}

/// The writes waiting (D3.2, R-L2): to make, or made and not yet in a stored fetch.
async fn pending_count(
    db: impl sqlx::SqliteExecutor<'_>,
    device_id: i64,
) -> Result<i64, sqlx::Error> {
    sqlx::query_scalar(
        "SELECT COUNT(*) FROM kid_caldav_events WHERE device_id = ? AND back_id IS NULL \
         AND (state IN ('pending', 'deleting') OR fetch_wait IS NOT NULL)",
    )
    .bind(device_id)
    .fetch_one(db)
    .await
}

pub async fn policy(
    db: &sqlx::SqlitePool,
    device_id: i64,
    now: DateTime<Utc>,
) -> Result<Option<KidCaldavPolicy>, sqlx::Error> {
    let Some((_, uid, _)) = marked(db, device_id).await? else {
        return Ok(None);
    };
    let accepted: Option<Option<String>> =
        sqlx::query_scalar("SELECT accepted FROM device_kid_events WHERE device_id = ?")
            .bind(device_id)
            .fetch_optional(db)
            .await?;
    Ok(Some(KidCaldavPolicy {
        id: uid,
        accepted: accepted.flatten(),
        back: back_count(db, device_id, now).await?,
        pending: pending_count(db, device_id).await?,
    }))
}

// ---- the endpoint (D1.7) -----------------------------------------------------------------------

fn answer(status: StatusCode, body: Value) -> Response {
    (status, axum::Json(body)).into_response()
}

fn error_answer(status: StatusCode, code: &str) -> Response {
    answer(status, serde_json::json!({ "error": code }))
}

/// A set refused with a 400 (R-L4): its code kept for the page.
async fn rejected(state: &AppState, device_id: i64, code: &str, now: DateTime<Utc>) -> Response {
    let stored = sqlx::query(
        "UPDATE device_kid_events SET rejected = ?, rejected_at = ? WHERE device_id = ?",
    )
    .bind(code)
    .bind(stamp(now))
    .bind(device_id)
    .execute(&state.db)
    .await;
    if let Err(err) = stored {
        tracing::error!(device_id, %err, "couldn't keep a refused set's code");
    }
    tracing::info!(
        device_id,
        code = code.split(':').next().unwrap_or(code),
        "a kid's set was refused"
    );
    error_answer(
        StatusCode::BAD_REQUEST,
        code.split(':').next().unwrap_or(code),
    )
}

/// `POST /api/devices/calendar/kid-events` (bearer, design 72b D1.7): the kid's set, its acknowledgements; answers
/// `{accepted, pending, back}` at once and wakes the write job. 409 `not_marked`, 429 with `Retry-After`, 413
/// `too_big`, 400 `bad_set`/`tag_pinned` (the whole set). Never logs a row's text.
pub async fn post(
    State(state): State<AppState>,
    axum::Extension(crate::security::AuthedDevice(device)): axum::Extension<
        crate::security::AuthedDevice,
    >,
    body: Bytes,
) -> Response {
    match post_inner(&state, device.id, &body).await {
        Ok(response) => response,
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "couldn't take a kid's set");
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        }
    }
}

async fn post_inner(
    state: &AppState,
    device_id: i64,
    body: &[u8],
) -> Result<Response, sqlx::Error> {
    let now = state.kid_writes.now();
    let capable = crate::handlers::device_api::last_capabilities(&state.db, device_id)
        .await?
        .iter()
        .any(|c| c == CAPABILITY);
    let Some((source_id, source_uid, _)) = marked(&state.db, device_id).await?.filter(|_| capable)
    else {
        return Ok(error_answer(StatusCode::CONFLICT, "not_marked"));
    };
    let value: Option<Value> = serde_json::from_slice(body).ok();
    if let Some(calendar) = value
        .as_ref()
        .and_then(|v| v.get("calendar"))
        .and_then(Value::as_str)
        && calendar != source_uid
    {
        return Ok(error_answer(StatusCode::CONFLICT, "not_marked"));
    }
    sqlx::query(
        "INSERT INTO device_kid_events (device_id, source_id) VALUES (?, ?) ON CONFLICT(device_id) DO NOTHING",
    )
    .bind(device_id)
    .bind(source_id)
    .execute(&state.db)
    .await?;
    // At most one POST per phone per [POST_GAP] (D1.7), whatever it holds.
    let last: Option<Option<String>> =
        sqlx::query_scalar("SELECT posted_at FROM device_kid_events WHERE device_id = ?")
            .bind(device_id)
            .fetch_optional(&state.db)
            .await?;
    if let Some(at) = last.flatten().as_deref().and_then(parse_stamp)
        && now - at < POST_GAP
        && now >= at
    {
        let wait = (POST_GAP - (now - at)).num_seconds().max(1);
        let mut response = error_answer(StatusCode::TOO_MANY_REQUESTS, "limited");
        response.headers_mut().insert(
            header::RETRY_AFTER,
            HeaderValue::from_str(&wait.to_string()).expect("digits"),
        );
        return Ok(response);
    }
    sqlx::query("UPDATE device_kid_events SET posted_at = ? WHERE device_id = ?")
        .bind(stamp(now))
        .bind(device_id)
        .execute(&state.db)
        .await?;
    let Some(value) = value else {
        return Ok(rejected(state, device_id, "bad_set", now).await);
    };
    let sent_bytes = value
        .get("events")
        .map(|e| serde_json::to_string(e).map_or(usize::MAX, |s| s.len()))
        .unwrap_or(0);
    // The phone counts each row's JSON and a separator against MAX_BYTES; the array's brackets and a re-encoding's
    // escapes get a little room.
    if body.len() > BODY_LIMIT || sent_bytes > MAX_BYTES + 1024 {
        sqlx::query(
            "UPDATE device_kid_events SET error = 'too_big', error_at = ? WHERE device_id = ?",
        )
        .bind(stamp(now))
        .bind(device_id)
        .execute(&state.db)
        .await?;
        return Ok(error_answer(StatusCode::PAYLOAD_TOO_LARGE, "too_big"));
    }
    let set = match read_set(&value) {
        Ok(set) => set,
        Err(code) => return Ok(rejected(state, device_id, code, now).await),
    };
    if set.calendar != source_uid {
        return Ok(error_answer(StatusCode::CONFLICT, "not_marked"));
    }
    // The tags (D4.9): each held by the device that first posted it.
    let tags: HashSet<&str> = set
        .events
        .iter()
        .map(|e| e.uid.as_str())
        .chain(set.ended.iter().map(String::as_str))
        .map(tag_of)
        .collect();
    // Inside the transaction (code QA L9): pinned first, then read - two phones posting one new tag can't both pass.
    let mut tx = state.db.begin().await?;
    for tag in &tags {
        sqlx::query("INSERT INTO kid_caldav_tags (tag, device_id) VALUES (?, ?) ON CONFLICT(tag) DO NOTHING")
            .bind(tag)
            .bind(device_id)
            .execute(&mut *tx)
            .await?;
        let holder: i64 = sqlx::query_scalar("SELECT device_id FROM kid_caldav_tags WHERE tag = ?")
            .bind(tag)
            .fetch_one(&mut *tx)
            .await?;
        if holder != device_id {
            tx.rollback().await?;
            return Ok(rejected(state, device_id, &format!("tag_pinned:{holder}"), now).await);
        }
    }
    // The acknowledgements first (D2.3, R-M2): only the uid's current hand-back id clears it.
    acknowledge(&mut tx, device_id, &set.applied).await?;
    sqlx::query(
        "UPDATE device_kid_events SET rev = ?, events_json = ?, from_at = ?, cut = ?, cut_from = ?, cut_uids = ?, \
         ended = ?, received_at = ?, rejected = NULL, rejected_at = NULL, \
         error = CASE WHEN error = 'too_big' THEN NULL ELSE error END WHERE device_id = ?",
    )
    .bind(&set.rev)
    .bind(serde_json::to_string(&set.events).expect("events serialize"))
    .bind(stamp(set.from))
    .bind(set.cut)
    .bind(set.cut_from.map(KidTime::wire))
    .bind(
        set.cut_uids
            .as_ref()
            .map(|u| serde_json::to_string(u).expect("uids serialize")),
    )
    .bind(serde_json::to_string(&set.ended).expect("uids serialize"))
    .bind(stamp(now))
    .bind(device_id)
    .execute(&mut *tx)
    .await?;
    let stored = stored_set(&mut *tx, device_id)
        .await?
        .expect("the row was just written");
    let rows = rows_of(&mut *tx, device_id).await?;
    let plan = plan(&stored, &rows, now);
    apply_plan(&mut tx, device_id, source_id, &stored, &plan, now).await?;
    tx.commit().await?;
    let body = answer_body(state, device_id, now).await?;
    state.kid_writes.wake();
    Ok(answer(StatusCode::OK, body))
}

/// D2.3: an `applied` id clears the wait only when it is the uid's current `back_id`; `sent_hash` := the version
/// acknowledged; an acknowledged delete drops the row (a released tombstone stays).
async fn acknowledge(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    device_id: i64,
    applied: &[String],
) -> Result<(), sqlx::Error> {
    for id in applied {
        let found: Option<(String, String, String)> = sqlx::query_as(
            "SELECT uid, state, back_json FROM kid_caldav_events WHERE device_id = ? AND back_id = ? \
             AND back_json IS NOT NULL",
        )
        .bind(device_id)
        .bind(id)
        .fetch_optional(&mut **tx)
        .await?;
        let Some((uid, row_state, back_json)) = found else {
            continue;
        };
        let back: Value = serde_json::from_str(&back_json).unwrap_or(Value::Null);
        if back["op"] == "delete" {
            if row_state == "released" {
                sqlx::query(
                    "UPDATE kid_caldav_events SET back_id = NULL, back_json = NULL, back_after = NULL \
                     WHERE device_id = ? AND uid = ?",
                )
                .bind(device_id)
                .bind(&uid)
                .execute(&mut **tx)
                .await?;
            } else {
                sqlx::query("DELETE FROM kid_caldav_events WHERE device_id = ? AND uid = ?")
                    .bind(device_id)
                    .bind(&uid)
                    .execute(&mut **tx)
                    .await?;
            }
            continue;
        }
        let acked = back_version(&back).map(|v| version_hash(&v));
        sqlx::query(
            "UPDATE kid_caldav_events SET back_id = NULL, back_json = NULL, back_after = NULL, sent_hash = ?, \
             state = CASE WHEN state = 'released' THEN state ELSE 'ok' END WHERE device_id = ? AND uid = ?",
        )
        .bind(acked)
        .bind(device_id)
        .bind(&uid)
        .execute(&mut **tx)
        .await?;
    }
    Ok(())
}

fn back_version(back: &Value) -> Option<Version> {
    Some(Version {
        summary: back["summary"].as_str()?.to_string(),
        location: back["location"].as_str()?.to_string(),
        description: back["description"].as_str()?.to_string(),
        start: back["start"].as_str()?.to_string(),
        end: back["end"].as_str()?.to_string(),
        all_day: back["all_day"].as_bool()?,
    })
}

/// The 200's body: the last set taken, the writes waiting, every hand-back the phone may have now (by uid).
async fn answer_body(
    state: &AppState,
    device_id: i64,
    now: DateTime<Utc>,
) -> Result<Value, sqlx::Error> {
    let accepted: Option<Option<String>> =
        sqlx::query_scalar("SELECT accepted FROM device_kid_events WHERE device_id = ?")
            .bind(device_id)
            .fetch_optional(&state.db)
            .await?;
    let backs: Vec<String> = sqlx::query_scalar(
        "SELECT back_json FROM kid_caldav_events WHERE device_id = ? AND back_json IS NOT NULL \
         AND (back_after IS NULL OR back_after <= ?) ORDER BY uid",
    )
    .bind(device_id)
    .bind(stamp(now))
    .fetch_all(&state.db)
    .await?;
    let back: Vec<Value> = backs
        .iter()
        .filter_map(|b| serde_json::from_str(b).ok())
        .collect();
    Ok(serde_json::json!({
        "accepted": accepted.flatten(),
        "pending": pending_count(&state.db, device_id).await?,
        "back": back,
    }))
}

// ---- hand-backs --------------------------------------------------------------------------------

fn upsert_json(uid: &str, version: &Version) -> (String, String) {
    let id = back_id(uid, &version_hash(version));
    let json = serde_json::json!({
        "id": id,
        "uid": uid,
        "op": "upsert",
        "summary": version.summary,
        "location": version.location,
        "description": version.description,
        "start": version.start,
        "end": version.end,
        "all_day": version.all_day,
    });
    (id, json.to_string())
}

fn delete_json(uid: &str) -> (String, String) {
    let id = back_id(uid, "delete");
    let json = serde_json::json!({ "id": id, "uid": uid, "op": "delete" });
    (id, json.to_string())
}

/// What a read of the resource says, stored: the calendar's version as the base, its ETag, its times.
async fn remember_remote(
    db: &sqlx::SqlitePool,
    device_id: i64,
    uid: &str,
    version: &Version,
    etag: &str,
    sequence: i64,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "UPDATE kid_caldav_events SET remote_hash = ?, etag = ?, sequence = MAX(sequence, ?), start_at = ?, \
         end_at = ? WHERE device_id = ? AND uid = ?",
    )
    .bind(version_hash(version))
    .bind(etag)
    .bind(sequence)
    .bind(instant_stamp(version.start_time()))
    .bind(instant_stamp(version.end_time()))
    .bind(device_id)
    .bind(uid)
    .execute(db)
    .await
    .map(|_| ())
}

/// The calendar wins (D2.1): the parent's version handed back, no write while it waits (a waiting write dropped) -
/// the base, the ETag, the times and the hand-back in one statement (code QA L4). A hand-back that waited is
/// replaced by this one (a new id): the phone applies it and acknowledges that.
async fn hand_back(
    db: &sqlx::SqlitePool,
    device_id: i64,
    uid: &str,
    version: &Version,
    etag: &str,
    sequence: i64,
) -> Result<(), sqlx::Error> {
    let (id, json) = upsert_json(uid, version);
    sqlx::query(
        "UPDATE kid_caldav_events SET remote_hash = ?, etag = ?, sequence = MAX(sequence, ?), start_at = ?, \
         end_at = ?, back_id = ?, back_json = ?, back_after = NULL, restore_wanted = 0, state = 'ok', \
         failures = 0, next_at = NULL WHERE device_id = ? AND uid = ?",
    )
    .bind(version_hash(version))
    .bind(etag)
    .bind(sequence)
    .bind(instant_stamp(version.start_time()))
    .bind(instant_stamp(version.end_time()))
    .bind(id)
    .bind(json)
    .bind(device_id)
    .bind(uid)
    .execute(db)
    .await?;
    tracing::info!(device_id, "a parent's edit of a kid's event is handed back");
    Ok(())
}

/// A series or an invitation can't come back (D2.4): a tombstone, the phone told to delete its copy.
async fn release(
    db: &sqlx::SqlitePool,
    device_id: i64,
    uid: &str,
    version: &Version,
    etag: &str,
) -> Result<(), sqlx::Error> {
    let (id, json) = delete_json(uid);
    sqlx::query(
        "UPDATE kid_caldav_events SET state = 'released', remote_hash = ?, etag = ?, back_id = ?, back_json = ?, \
         back_after = NULL, released_absent_since = NULL, restore_wanted = 0, next_at = NULL, failures = 0 \
         WHERE device_id = ? AND uid = ?",
    )
    .bind(version_hash(version))
    .bind(etag)
    .bind(id)
    .bind(json)
    .bind(device_id)
    .bind(uid)
    .execute(db)
    .await?;
    tracing::info!(
        device_id,
        "a kid's event became the parent's and was released"
    );
    Ok(())
}

/// The parent deleted it (a 404 confirmed): the phone told to delete its copy, after `after` when held.
async fn hand_back_delete(
    db: &sqlx::SqlitePool,
    device_id: i64,
    uid: &str,
    after: Option<DateTime<Utc>>,
) -> Result<(), sqlx::Error> {
    let (id, json) = delete_json(uid);
    sqlx::query(
        "UPDATE kid_caldav_events SET back_id = ?, back_json = ?, back_after = ?, state = 'ok', \
         next_at = NULL, failures = 0 WHERE device_id = ? AND uid = ?",
    )
    .bind(id)
    .bind(json)
    .bind(after.map(stamp))
    .bind(device_id)
    .bind(uid)
    .execute(db)
    .await?;
    Ok(())
}

// ---- the write job (D4.2-D4.4) -----------------------------------------------------------------

/// A strong ETag (QA L2: `If-Match` compares strongly), at most [MAX_HEADER_VALUE], no CR/LF.
fn strong(etag: Option<&str>) -> Option<String> {
    etag.map(str::trim)
        .filter(|e| {
            !e.is_empty()
                && !e.starts_with("W/")
                && e.len() <= MAX_HEADER_VALUE
                && !e.contains(['\r', '\n'])
        })
        .map(str::to_string)
}

/// `<collection>/<uid>.ics` by string append onto the collection's path, normalised to one `/` (D4.9) - never a
/// join of the phone's text.
pub fn resource_href(collection: &str, uid: &str) -> Option<String> {
    let mut url = reqwest::Url::parse(collection).ok()?;
    let path = url.path().trim_end_matches('/').to_string();
    url.set_path(&format!("{path}/{uid}.ics"));
    url.set_query(None);
    url.set_fragment(None);
    Some(url.to_string())
}

fn decoded(path: &str) -> String {
    let bytes = path.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        let hex = bytes
            .get(i + 1..i + 3)
            .and_then(|h| std::str::from_utf8(h).ok())
            .and_then(|h| u8::from_str_radix(h, 16).ok());
        match (bytes[i], hex) {
            (b'%', Some(b)) => {
                out.push(b);
                i += 3;
            }
            (b, _) => {
                out.push(b);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// A server-named href adopted only as a direct child of the collection (D4.9): the same origin, and after
/// percent-decoding one segment under the collection's path - never `..`, never a deeper path.
pub fn adoptable_href(collection: &str, href: &str) -> Option<String> {
    let base = reqwest::Url::parse(collection).ok()?;
    let url = base.join(href).ok()?;
    if url.scheme() != base.scheme()
        || url.host_str() != base.host_str()
        || url.port_or_known_default() != base.port_or_known_default()
        || url.query().is_some()
    {
        return None;
    }
    let dir = format!("{}/", decoded(base.path()).trim_end_matches('/'));
    let path = decoded(url.path());
    let rest = path.strip_prefix(&dir)?;
    if rest.is_empty() || rest.contains(['/', '\\']) || rest == "." || rest == ".." {
        return None;
    }
    Some(url.to_string())
}

/// A 403's body (RFC 4791 §5.3.2.1): the preconditions named in its `DAV:error`, and an href inside it.
fn forbidden_body(body: &[u8]) -> (Vec<(String, String)>, Option<String>) {
    use quick_xml::events::Event;
    use quick_xml::name::ResolveResult;
    let text = String::from_utf8_lossy(body);
    let mut reader = quick_xml::NsReader::from_str(&text);
    let mut depth = 0usize;
    let mut in_error: Option<usize> = None;
    let mut in_href = false;
    let mut names = Vec::new();
    let mut href = None;
    while let Ok((resolved, event)) = reader.read_resolved_event() {
        let ns = match resolved {
            ResolveResult::Bound(ns) => AsRef::<str>::as_ref(&ns).to_string(),
            _ => String::new(),
        };
        let empty = matches!(event, Event::Empty(_));
        match event {
            Event::DocType(_) => return (Vec::new(), None),
            Event::Start(e) | Event::Empty(e) => {
                let local = AsRef::<str>::as_ref(&e.local_name()).to_string();
                if in_error.is_none() && ns == crate::caldav::DAV && local == "error" {
                    in_error = Some(depth);
                } else if let Some(at) = in_error {
                    if depth == at + 1 {
                        names.push((ns.clone(), local.clone()));
                    }
                    if ns == crate::caldav::DAV && local == "href" && !empty {
                        in_href = true;
                    }
                }
                if !empty {
                    depth += 1;
                }
            }
            Event::Text(t) if in_href => {
                let piece = t.xml10_content().into_owned();
                href.get_or_insert_with(String::new).push_str(piece.trim());
            }
            Event::End(_) => {
                depth = depth.saturating_sub(1);
                in_href = false;
                if in_error == Some(depth) {
                    in_error = None;
                }
            }
            Event::Eof => break,
            _ => {}
        }
        if depth > 32 {
            break;
        }
    }
    (names, href)
}

/// How one write ended for the job.
enum Step {
    /// Done, or nothing more this pass for this event.
    Done,
    /// Wrote (a fetch is due).
    Wrote,
    /// Try this event again now (an ETag learned).
    Again,
    /// This phone's writes stop (`write_refused`, `full`, `no_etag`).
    StopDevice(&'static str),
    /// The account's writes stop: a 429/503 (backoff) or a refused password / another host (the account stopped).
    StopAccount,
}

/// A job's view of one marked calendar.
struct Target<'a> {
    login: &'a WriteLogin,
    device_id: i64,
    collection: String,
}

impl Target<'_> {
    async fn send(
        &self,
        state: &AppState,
        url: &str,
        request: &DavRequest<'_>,
    ) -> Result<crate::music_net::SourceResponse, FeedError> {
        self.login
            .send(state.music_fetch.as_ref(), url, request)
            .await
    }

    async fn get(
        &self,
        state: &AppState,
        url: &str,
    ) -> Result<crate::music_net::SourceResponse, FeedError> {
        let request = DavRequest {
            method: "GET",
            depth: None,
            content_type: None,
            condition: None,
            body: &[],
        };
        self.send(state, url, &request).await
    }
}

async fn row(db: &sqlx::SqlitePool, device_id: i64, uid: &str) -> Result<Option<Row>, sqlx::Error> {
    sqlx::query_as(&format!(
        "SELECT {ROW_COLUMNS} FROM kid_caldav_events WHERE device_id = ? AND uid = ?"
    ))
    .bind(device_id)
    .bind(uid)
    .fetch_optional(db)
    .await
}

/// An event's own failure: tried again later by `music_sweep::backoff`.
async fn backoff_event(
    db: &sqlx::SqlitePool,
    device_id: i64,
    row: &Row,
    now: DateTime<Utc>,
    code: &str,
) -> Result<Step, sqlx::Error> {
    let failures = row.failures + 1;
    let wait = crate::music_sweep::backoff(failures, TimeDelta::hours(1));
    sqlx::query(
        "UPDATE kid_caldav_events SET failures = ?, next_at = ? WHERE device_id = ? AND uid = ?",
    )
    .bind(failures)
    .bind(stamp(now + wait))
    .bind(device_id)
    .bind(&row.uid)
    .execute(db)
    .await?;
    tracing::info!(
        device_id,
        code,
        "a kid's event write failed; tried again later"
    );
    Ok(Step::Done)
}

/// The calendar refused this version (D4.4): shown and counted, written again only when the kid changes it.
async fn refuse_event(
    db: &sqlx::SqlitePool,
    device_id: i64,
    uid: &str,
    code: &str,
) -> Result<Step, sqlx::Error> {
    sqlx::query(
        "UPDATE kid_caldav_events SET state = 'refused', refused_code = ?, next_at = NULL \
         WHERE device_id = ? AND uid = ?",
    )
    .bind(code)
    .bind(device_id)
    .bind(uid)
    .execute(db)
    .await?;
    tracing::info!(device_id, code, "the calendar refused a kid's event");
    Ok(Step::Done)
}

/// The account backs off (QA L1): `Retry-After` in seconds, else `music_sweep::backoff`.
async fn backoff_account(
    db: &sqlx::SqlitePool,
    account_id: i64,
    retry_after: Option<&str>,
    now: DateTime<Utc>,
) -> Result<Step, sqlx::Error> {
    let failures: i64 =
        sqlx::query_scalar("SELECT write_failures + 1 FROM caldav_accounts WHERE id = ?")
            .bind(account_id)
            .fetch_optional(db)
            .await?
            .unwrap_or(1);
    // Seconds, or an HTTP date (RFC 9110 §10.2.3; code QA N6).
    let wait = retry_after
        .and_then(|r| {
            let r = r.trim();
            r.parse::<i64>().ok().or_else(|| {
                DateTime::parse_from_rfc2822(r)
                    .ok()
                    .map(|at| (at.with_timezone(&Utc) - now).num_seconds())
            })
        })
        .filter(|s| (1..=86_400).contains(s))
        .map(TimeDelta::seconds)
        .unwrap_or_else(|| crate::music_sweep::backoff(failures, TimeDelta::hours(1)));
    sqlx::query(
        "UPDATE caldav_accounts SET write_backoff_until = ?, write_failures = ? WHERE id = ?",
    )
    .bind(stamp(now + wait))
    .bind(failures)
    .bind(account_id)
    .execute(db)
    .await?;
    tracing::info!(account_id, "a CalDAV account's writes back off");
    Ok(Step::StopAccount)
}

/// A request's own failure ([FeedError]): a refused password or another host stops the account; anything else is
/// the event's backoff.
async fn failed_request(
    state: &AppState,
    target: &Target<'_>,
    row: &Row,
    err: FeedError,
    now: DateTime<Utc>,
) -> Result<Step, sqlx::Error> {
    if matches!(err.code, "login_refused" | "host_changed") {
        target.login.stop(state, err.code).await?;
        return Ok(Step::StopAccount);
    }
    backoff_event(&state.db, target.device_id, row, now, err.code).await
}

/// Any other answer to a write (D4.4).
async fn other_answer(
    state: &AppState,
    target: &Target<'_>,
    row: &Row,
    answer: &crate::music_net::SourceResponse,
    now: DateTime<Utc>,
) -> Result<Step, sqlx::Error> {
    match answer.status {
        409 | 400 | 415 | 413 => {
            refuse_event(
                &state.db,
                target.device_id,
                &row.uid,
                &answer.status.to_string(),
            )
            .await
        }
        507 => Ok(Step::StopDevice("full")),
        429 | 503 => {
            backoff_account(
                &state.db,
                target.login.id,
                answer.retry_after.as_deref(),
                now,
            )
            .await
        }
        _ => backoff_event(&state.db, target.device_id, row, now, "status").await,
    }
}

/// A written version stored (D2.5.2): its ETag from the answer, else read back with its body - our own when its
/// content is what we wrote, else a parent's edit that came between.
#[allow(clippy::too_many_arguments)]
async fn wrote(
    state: &AppState,
    target: &Target<'_>,
    row: &Row,
    href: &str,
    version: &Version,
    sequence: i64,
    answer_etag: Option<&str>,
    now: DateTime<Utc>,
) -> Result<Step, sqlx::Error> {
    let h = version_hash(version);
    let etag = match strong(answer_etag) {
        Some(etag) => Some(etag),
        None => match target.get(state, href).await {
            Ok(got) if got.status == 200 => {
                let Some(etag) = strong(got.etag.as_deref()) else {
                    mark_written(
                        &state.db,
                        target.device_id,
                        row,
                        href,
                        &h,
                        version,
                        None,
                        sequence,
                        now,
                    )
                    .await?;
                    return Ok(Step::StopDevice("no_etag"));
                };
                let read = ics::read_kid_resource(&got.body);
                match read {
                    Some(resource) => {
                        let zone = row_zone(state, target.device_id, &row.uid).await?;
                        let theirs = resource_version(&resource, zone);
                        if version_hash(&theirs) != h {
                            mark_written(
                                &state.db,
                                target.device_id,
                                row,
                                href,
                                &h,
                                version,
                                Some(&etag),
                                sequence,
                                now,
                            )
                            .await?;
                            judge(state, target.device_id, &row.uid, &resource, &etag, zone)
                                .await?;
                            return Ok(Step::Wrote);
                        }
                        Some(etag)
                    }
                    None => Some(etag),
                }
            }
            // Written, its ETag unknown: the next write finds it by its content - a create by its 412, an update by
            // the old ETag's 412 (kept: never a create over a parent's later delete, code QA L5).
            _ => {
                sqlx::query(
                    "UPDATE kid_caldav_events SET href = ?, attempted_hash = ?, state = 'pending', \
                     fetch_wait = ?, next_at = ? WHERE device_id = ? AND uid = ?",
                )
                .bind(href)
                .bind(row.with_attempt(&h))
                .bind(fine_stamp(now))
                .bind(stamp(now + TimeDelta::minutes(15)))
                .bind(target.device_id)
                .bind(&row.uid)
                .execute(&state.db)
                .await?;
                return Ok(Step::Wrote);
            }
        },
    };
    mark_written(
        &state.db,
        target.device_id,
        row,
        href,
        &h,
        version,
        etag.as_deref(),
        sequence,
        now,
    )
    .await?;
    Ok(Step::Wrote)
}

#[allow(clippy::too_many_arguments)]
async fn mark_written(
    db: &sqlx::SqlitePool,
    device_id: i64,
    row: &Row,
    href: &str,
    h: &str,
    version: &Version,
    etag: Option<&str>,
    sequence: i64,
    now: DateTime<Utc>,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "UPDATE kid_caldav_events SET href = ?, etag = ?, sent_hash = ?, remote_hash = ?, attempted_hash = ?, \
         sequence = ?, created_at_remote = COALESCE(created_at_remote, ?), start_at = ?, end_at = ?, state = 'ok', \
         failures = 0, next_at = NULL, fetch_wait = ?, refused_code = NULL WHERE device_id = ? AND uid = ?",
    )
    .bind(href)
    .bind(etag)
    .bind(h)
    .bind(h)
    .bind(h)
    .bind(sequence)
    .bind(KidTime::At(now.naive_utc()).wire())
    .bind(instant_stamp(version.start_time()))
    .bind(instant_stamp(version.end_time()))
    .bind(fine_stamp(now))
    .bind(device_id)
    .bind(&row.uid)
    .execute(db)
    .await?;
    tracing::info!(device_id, "a kid's event was written to the calendar");
    Ok(())
}

/// The zone the phone last sent for an event (a parent's floating time resolves in it, D2.7).
async fn row_zone(state: &AppState, device_id: i64, uid: &str) -> Result<Option<Tz>, sqlx::Error> {
    Ok(stored_set(&state.db, device_id).await?.and_then(|set| {
        set.events
            .iter()
            .find(|e| e.uid == uid)
            .and_then(KidEvent::zone)
    }))
}

/// The calendar's resource read (a fetch, a confirm, a write's read-back) judged against what is stored (D2.2,
/// D2.4, D4.6): a series or invitations release it; the stored base, our own write in flight or the phone's version
/// only store its ETag; anything else is the parent's edit, handed back.
async fn judge(
    state: &AppState,
    device_id: i64,
    uid: &str,
    resource: &KidResource,
    etag: &str,
    zone: Option<Tz>,
) -> Result<(), sqlx::Error> {
    let Some(row) = row(&state.db, device_id, uid).await? else {
        return Ok(());
    };
    let theirs = resource_version(resource, zone);
    let read = version_hash(&theirs);
    if resource.recurring || resource.invites {
        if row.state != "released" {
            release(&state.db, device_id, uid, &theirs, etag).await?;
        }
        return Ok(());
    }
    if row.state == "released" {
        return Ok(());
    }
    let phone = stored_set(&state.db, device_id)
        .await?
        .and_then(|set| set.events.into_iter().find(|e| e.uid == uid))
        .and_then(|e| e.version())
        .map(|v| version_hash(&v));
    if row.remote_hash.as_deref() == Some(read.as_str()) && row.back_id.is_none() {
        return remember_remote(&state.db, device_id, uid, &theirs, etag, resource.sequence).await;
    }
    if row.back_id.as_deref() == Some(back_id(uid, &read).as_str()) {
        // The hand-back waiting is this version already.
        return remember_remote(&state.db, device_id, uid, &theirs, etag, resource.sequence).await;
    }
    let ours = row.was_attempted(&read) && row.back_id.is_none();
    let phones = phone.as_deref() == Some(read.as_str()) && row.state != "past";
    if row.back_id.is_some()
        && (ours || phones)
        && !row.back_held(Utc::now().max(state.kid_writes.now()))
    {
        // A hand-back that may have been delivered is never cleared (code QA M1): the phone may have applied it, and
        // its acknowledgement would then match nothing. The calendar's version replaces it - a no-op or a revert.
        return hand_back(&state.db, device_id, uid, &theirs, etag, resource.sequence).await;
    }
    if ours || phones {
        // Our own write in flight, or the parent made it what the phone has: nothing to hand back. One statement
        // (code QA L4).
        sqlx::query(
            "UPDATE kid_caldav_events SET remote_hash = ?, etag = ?, sequence = MAX(sequence, ?), start_at = ?, \
             end_at = ?, sent_hash = ?, back_id = NULL, back_json = NULL, back_after = NULL, \
             state = CASE WHEN state = 'past' THEN state ELSE 'ok' END WHERE device_id = ? AND uid = ?",
        )
        .bind(&read)
        .bind(etag)
        .bind(resource.sequence)
        .bind(instant_stamp(theirs.start_time()))
        .bind(instant_stamp(theirs.end_time()))
        .bind(&read)
        .bind(device_id)
        .bind(uid)
        .execute(&state.db)
        .await?;
        return Ok(());
    }
    if row.state == "past" {
        // The past is left alone (D2.6): no hand-back, no new version stored.
        return Ok(());
    }
    hand_back(&state.db, device_id, uid, &theirs, etag, resource.sequence).await
}

/// Never a PUT over content the server hasn't read (D4.4): the resource at `href` read and compared - the phone's
/// version, our own lost answer (`attempted_hash`), the unchanged base, a series, else the parent's edit. `create`:
/// a GET 404 is the event's `status` error (RN3), not an adopt; for an update it is the parent's delete.
async fn read_first(
    state: &AppState,
    target: &Target<'_>,
    row: &Row,
    href: &str,
    version: &Version,
    create: bool,
    now: DateTime<Utc>,
) -> Result<Step, sqlx::Error> {
    let got = match target.get(state, href).await {
        Ok(got) => got,
        Err(err) => return failed_request(state, target, row, err, now).await,
    };
    if got.status == 404 {
        if create {
            return backoff_event(&state.db, target.device_id, row, now, "status").await;
        }
        return parents_delete(state, target, row, now).await;
    }
    if got.status != 200 {
        return other_answer(state, target, row, &got, now).await;
    }
    let Some(etag) = strong(got.etag.as_deref()) else {
        return Ok(Step::StopDevice("no_etag"));
    };
    let Some(resource) = ics::read_kid_resource(&got.body) else {
        // Content we can't read is never written over.
        return refuse_event(&state.db, target.device_id, &row.uid, "unreadable").await;
    };
    if resource.uid != row.uid {
        return refuse_event(&state.db, target.device_id, &row.uid, "uid").await;
    }
    sqlx::query("UPDATE kid_caldav_events SET href = ? WHERE device_id = ? AND uid = ?")
        .bind(href)
        .bind(target.device_id)
        .bind(&row.uid)
        .execute(&state.db)
        .await?;
    let zone = row_zone(state, target.device_id, &row.uid).await?;
    let theirs = resource_version(&resource, zone);
    let read = version_hash(&theirs);
    let h = version_hash(version);
    if resource.recurring || resource.invites {
        release(&state.db, target.device_id, &row.uid, &theirs, &etag).await?;
        return Ok(Step::Done);
    }
    let seq = resource.sequence;
    if read == h {
        mark_written(
            &state.db,
            target.device_id,
            row,
            href,
            &h,
            version,
            Some(&etag),
            row.sequence.max(seq),
            now,
        )
        .await?;
        return Ok(Step::Done);
    }
    if row.was_attempted(&read) {
        // Our own write whose answer was lost (R-L1a): its ETag learned, the phone's newer version written next.
        remember_remote(&state.db, target.device_id, &row.uid, &theirs, &etag, seq).await?;
        sqlx::query(
            "UPDATE kid_caldav_events SET sent_hash = ?, state = 'pending' WHERE device_id = ? AND uid = ?",
        )
        .bind(&read)
        .bind(target.device_id)
        .bind(&row.uid)
        .execute(&state.db)
        .await?;
        return Ok(Step::Again);
    }
    if row.remote_hash.as_deref() == Some(read.as_str()) {
        // The parent changed nothing (R-L1b, a rewritten body): the phone's version goes over the ETag read.
        remember_remote(&state.db, target.device_id, &row.uid, &theirs, &etag, seq).await?;
        sqlx::query(
            "UPDATE kid_caldav_events SET state = 'pending' WHERE device_id = ? AND uid = ?",
        )
        .bind(target.device_id)
        .bind(&row.uid)
        .execute(&state.db)
        .await?;
        return Ok(Step::Again);
    }
    // The calendar wins: no base, or another than it.
    hand_back(&state.db, target.device_id, &row.uid, &theirs, &etag, seq).await?;
    Ok(Step::Done)
}

/// A write's resource answered 404: the parent's delete, handed back - once the collection itself answers (code QA
/// L3: a calendar deleted at the provider, a server's bad hour, is no reason to empty the kid's day).
async fn parents_delete(
    state: &AppState,
    target: &Target<'_>,
    row: &Row,
    now: DateTime<Utc>,
) -> Result<Step, sqlx::Error> {
    let request = DavRequest {
        method: "PROPFIND",
        depth: Some("0"),
        content_type: Some("application/xml; charset=utf-8"),
        condition: None,
        body: br#"<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"#,
    };
    match target.send(state, &target.collection, &request).await {
        Ok(answer) if answer.status == 207 => {
            hand_back_delete(&state.db, target.device_id, &row.uid, None).await?;
            tracing::info!(
                device_id = target.device_id,
                "a kid's event the parent deleted is handed back"
            );
            Ok(Step::Done)
        }
        Ok(_) => backoff_event(&state.db, target.device_id, row, now, "collection").await,
        Err(err) => failed_request(state, target, row, err, now).await,
    }
}

/// A 403 read by its body (D4.4): `no-uid-conflict` adopted only through [read_first] at a direct child of the
/// collection on a confirmed host; another CalDAV precondition refuses the event; `need-privileges` or nothing
/// stops this phone's writes (`write_refused`) - never the account's `login_refused`.
async fn forbidden(
    state: &AppState,
    target: &Target<'_>,
    row: &Row,
    answer: &crate::music_net::SourceResponse,
    version: &Version,
    create: bool,
    now: DateTime<Utc>,
) -> Result<Step, sqlx::Error> {
    let (names, href) = forbidden_body(&answer.body);
    let caldav: Vec<&str> = names
        .iter()
        .filter(|(ns, _)| ns == crate::caldav::CALDAV)
        .map(|(_, l)| l.as_str())
        .collect();
    if caldav.contains(&"no-uid-conflict") {
        let adopted = href
            .as_deref()
            .and_then(|h| adoptable_href(&target.collection, h))
            .filter(|h| reqwest::Url::parse(h).is_ok_and(|url| target.login.confirmed_host(&url)));
        return match adopted {
            Some(href) if create => read_first(state, target, row, &href, version, true, now).await,
            _ => refuse_event(&state.db, target.device_id, &row.uid, "uid_conflict").await,
        };
    }
    if let Some(first) = caldav.first() {
        let code = format!("403:{first}");
        return refuse_event(&state.db, target.device_id, &row.uid, &code).await;
    }
    Ok(Step::StopDevice("write_refused"))
}

/// One waiting write or delete of `row` (its version from the set read just before).
async fn step(
    state: &AppState,
    target: &Target<'_>,
    row: &Row,
    event: Option<&KidEvent>,
    now: DateTime<Utc>,
) -> Result<Step, sqlx::Error> {
    if row.state == "deleting" {
        return delete_step(state, target, row, now).await;
    }
    let Some(version) = event.and_then(KidEvent::version) else {
        return Ok(Step::Done);
    };
    let h = version_hash(&version);
    let create = row.etag.is_none();
    let Some(href) = (if create {
        row.href
            .clone()
            .or_else(|| resource_href(&target.collection, &row.uid))
    } else {
        row.href.clone()
    }) else {
        return refuse_event(&state.db, target.device_id, &row.uid, "href").await;
    };
    let sequence = if create && row.sequence == 0 {
        0
    } else {
        row.sequence + 1
    };
    let attempted = row.with_attempt(&h);
    sqlx::query(
        "UPDATE kid_caldav_events SET attempted_hash = ?, href = ? WHERE device_id = ? AND uid = ?",
    )
    .bind(&attempted)
    .bind(&href)
    .bind(target.device_id)
    .bind(&row.uid)
    .execute(&state.db)
    .await?;
    let created = row
        .created_at_remote
        .clone()
        .unwrap_or_else(|| KidTime::At(now.naive_utc()).wire());
    let stamp_now = KidTime::At(now.naive_utc()).wire();
    let body = ics::write_event(&ics::KidWrite {
        uid: &row.uid,
        summary: &version.summary,
        location: &version.location,
        description: &version.description,
        start: &version.start,
        end: &version.end,
        all_day: version.all_day,
        sequence,
        stamp: &stamp_now,
        created: &created,
    });
    let condition = if create {
        (reqwest::header::IF_NONE_MATCH, "*".to_string())
    } else {
        (
            reqwest::header::IF_MATCH,
            row.etag.clone().expect("an update has an ETag"),
        )
    };
    let request = DavRequest {
        method: "PUT",
        depth: None,
        content_type: Some("text/calendar; charset=utf-8"),
        condition: Some(condition),
        body: body.as_bytes(),
    };
    let answer = match target.send(state, &href, &request).await {
        Ok(answer) => answer,
        Err(err) => return failed_request(state, target, row, err, now).await,
    };
    let mut row = row.clone();
    row.attempted_hash = Some(attempted);
    match answer.status {
        200 | 201 | 204 => {
            wrote(
                state,
                target,
                &row,
                &href,
                &version,
                sequence,
                answer.etag.as_deref(),
                now,
            )
            .await
        }
        412 => read_first(state, target, &row, &href, &version, create, now).await,
        404 if create => read_first(state, target, &row, &href, &version, true, now).await,
        404 => parents_delete(state, target, &row, now).await,
        403 => forbidden(state, target, &row, &answer, &version, create, now).await,
        _ => other_answer(state, target, &row, &answer, now).await,
    }
}

/// A delete (D4.3): `If-Match` the stored ETag; a resource whose ETag isn't known (a create whose answer was lost)
/// is read first. A 412 reads it: ours (any version we wrote or knew) is deleted with the new ETag, the parent's edit
/// wins (handed back, the kid's delete dropped).
async fn delete_step(
    state: &AppState,
    target: &Target<'_>,
    row: &Row,
    now: DateTime<Utc>,
) -> Result<Step, sqlx::Error> {
    let Some(href) = row
        .href
        .clone()
        .or_else(|| resource_href(&target.collection, &row.uid))
    else {
        return Ok(Step::Done);
    };
    let Some(etag) = row.etag.clone() else {
        return delete_read_first(state, target, row, &href, now).await;
    };
    let request = DavRequest {
        method: "DELETE",
        depth: None,
        content_type: None,
        condition: Some((reqwest::header::IF_MATCH, etag)),
        body: &[],
    };
    let answer = match target.send(state, &href, &request).await {
        Ok(answer) => answer,
        Err(err) => return failed_request(state, target, row, err, now).await,
    };
    match answer.status {
        200 | 202 | 204 | 404 => {
            sqlx::query(
                "UPDATE kid_caldav_events SET state = 'gone', etag = NULL, fetch_wait = ?, failures = 0, \
                 next_at = NULL WHERE device_id = ? AND uid = ?",
            )
            .bind(fine_stamp(now))
            .bind(target.device_id)
            .bind(&row.uid)
            .execute(&state.db)
            .await?;
            tracing::info!(
                device_id = target.device_id,
                "a kid's event was deleted from the calendar"
            );
            Ok(Step::Wrote)
        }
        412 => delete_read_first(state, target, row, &href, now).await,
        // `need-privileges` (or no body) stops this phone's writes; a precondition backs this delete off.
        403 if forbidden_body(&answer.body)
            .0
            .iter()
            .any(|(ns, _)| ns == crate::caldav::CALDAV) =>
        {
            backoff_event(&state.db, target.device_id, row, now, "403").await
        }
        403 => Ok(Step::StopDevice("write_refused")),
        507 => Ok(Step::StopDevice("full")),
        429 | 503 => {
            backoff_account(
                &state.db,
                target.login.id,
                answer.retry_after.as_deref(),
                now,
            )
            .await
        }
        // A refused delete is tried again by its backoff, never every pass (code QA L2), and never `refused`: a
        // refused row missing from the set would be planned as a delete again at once.
        _ => backoff_event(&state.db, target.device_id, row, now, "status").await,
    }
}

async fn delete_read_first(
    state: &AppState,
    target: &Target<'_>,
    row: &Row,
    href: &str,
    now: DateTime<Utc>,
) -> Result<Step, sqlx::Error> {
    let got = match target.get(state, href).await {
        Ok(got) => got,
        Err(err) => return failed_request(state, target, row, err, now).await,
    };
    if got.status == 404 {
        sqlx::query(
            "UPDATE kid_caldav_events SET state = 'gone', etag = NULL, fetch_wait = ? WHERE device_id = ? AND uid = ?",
        )
        .bind(fine_stamp(now))
        .bind(target.device_id)
        .bind(&row.uid)
        .execute(&state.db)
        .await?;
        return Ok(Step::Wrote);
    }
    if matches!(got.status, 429 | 503) {
        return backoff_account(&state.db, target.login.id, got.retry_after.as_deref(), now).await;
    }
    if got.status != 200 {
        return backoff_event(&state.db, target.device_id, row, now, "status").await;
    }
    let Some(etag) = strong(got.etag.as_deref()) else {
        return Ok(Step::StopDevice("no_etag"));
    };
    // Content we can't read is never deleted; tried again by its backoff, never `refused` (code QA L2).
    let Some(resource) = ics::read_kid_resource(&got.body).filter(|r| r.uid == row.uid) else {
        return backoff_event(&state.db, target.device_id, row, now, "unreadable").await;
    };
    let zone = row_zone(state, target.device_id, &row.uid).await?;
    let theirs = resource_version(&resource, zone);
    let read = version_hash(&theirs);
    if resource.recurring || resource.invites {
        release(&state.db, target.device_id, &row.uid, &theirs, &etag).await?;
        return Ok(Step::Done);
    }
    let known = row.was_attempted(&read)
        || [&row.remote_hash, &row.sent_hash]
            .iter()
            .any(|h| h.as_deref() == Some(read.as_str()));
    if known {
        sqlx::query(
            "UPDATE kid_caldav_events SET etag = ?, href = ? WHERE device_id = ? AND uid = ?",
        )
        .bind(&etag)
        .bind(href)
        .bind(target.device_id)
        .bind(&row.uid)
        .execute(&state.db)
        .await?;
        return Ok(Step::Again);
    }
    hand_back(
        &state.db,
        target.device_id,
        &row.uid,
        &theirs,
        &etag,
        resource.sequence,
    )
    .await?;
    Ok(Step::Done)
}

/// A claim's own token (code QA M3): the process's start and a counter, never reused.
fn claim_token() -> String {
    static COUNTER: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    static START: std::sync::LazyLock<i64> =
        std::sync::LazyLock::new(|| Utc::now().timestamp_nanos_opt().unwrap_or_default());
    let n = COUNTER.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    format!("{}-{n}", *START)
}

/// Claims the account's write job (QA L4): a POST, a pass and a sibling's POST never race. Returns the claim's token,
/// `None` when another job holds it.
async fn claim(
    db: &sqlx::SqlitePool,
    account_id: i64,
    now: DateTime<Utc>,
) -> Result<Option<String>, sqlx::Error> {
    let token = claim_token();
    let done = sqlx::query(
        "UPDATE caldav_accounts SET write_claimed_at = ?, write_claim = ? WHERE id = ? \
         AND (write_claimed_at IS NULL OR write_claimed_at <= ?)",
    )
    .bind(stamp(now))
    .bind(&token)
    .bind(account_id)
    .bind(stamp(now - CLAIM_FOR))
    .execute(db)
    .await?;
    Ok((done.rows_affected() > 0).then_some(token))
}

/// The claim kept alive at each step (code QA M3); `false` when another job has taken it (this one then stops).
async fn keep_claim(
    db: &sqlx::SqlitePool,
    account_id: i64,
    token: &str,
    now: DateTime<Utc>,
) -> Result<bool, sqlx::Error> {
    let done = sqlx::query(
        "UPDATE caldav_accounts SET write_claimed_at = ? WHERE id = ? AND write_claim = ?",
    )
    .bind(stamp(now))
    .bind(account_id)
    .bind(token)
    .execute(db)
    .await?;
    Ok(done.rows_affected() > 0)
}

/// One write against the account's 60 a minute (D4.2).
async fn take_budget(
    db: &sqlx::SqlitePool,
    account_id: i64,
    now: DateTime<Utc>,
) -> Result<bool, sqlx::Error> {
    let minute = now.format("%Y-%m-%d %H:%M").to_string();
    let done = sqlx::query(
        "UPDATE caldav_accounts SET write_minute_count = CASE WHEN write_minute_at = ? THEN write_minute_count + 1 \
         ELSE 1 END, write_minute_at = ? WHERE id = ? AND (write_minute_at IS NOT ? OR write_minute_count < ?)",
    )
    .bind(&minute)
    .bind(&minute)
    .bind(account_id)
    .bind(&minute)
    .bind(WRITES_A_MINUTE)
    .execute(db)
    .await?;
    Ok(done.rows_affected() > 0)
}

/// What a phone's counts were, for the nudge (QA L5): only when `pending` reaches 0, `back` goes above 0, or a stop
/// is new.
async fn counts(
    db: &sqlx::SqlitePool,
    device_id: i64,
    now: DateTime<Utc>,
) -> Result<(i64, i64, Option<String>), sqlx::Error> {
    let error: Option<Option<String>> =
        sqlx::query_scalar("SELECT error FROM device_kid_events WHERE device_id = ?")
            .bind(device_id)
            .fetch_optional(db)
            .await?;
    Ok((
        pending_count(db, device_id).await?,
        back_count(db, device_id, now).await?,
        error.flatten(),
    ))
}

fn nudge_if(
    state: &AppState,
    device_id: i64,
    before: &(i64, i64, Option<String>),
    after: &(i64, i64, Option<String>),
) {
    let stop = |e: &Option<String>| e.as_deref().is_some_and(|e| e != "too_big");
    if (before.0 > 0 && after.0 == 0)
        || (before.1 == 0 && after.1 > 0)
        || (!stop(&before.2) && stop(&after.2))
    {
        let _ = state.command_notify.send(device_id);
    }
}

/// The marked calendars of an account: (device, source id, source uid, sealed address, nonce, key fingerprint,
/// error).
type MarkRow = (i64, i64, String, Vec<u8>, Vec<u8>, String, Option<String>);

async fn marks_of(db: &sqlx::SqlitePool, account_id: i64) -> Result<Vec<MarkRow>, sqlx::Error> {
    sqlx::query_as(
        "SELECT d.device_id, s.id, s.uid, s.url_sealed, s.url_nonce, s.url_key_fp, s.error \
         FROM device_calendar_feeds d JOIN calendar_sources s ON s.id = d.source_id \
         WHERE s.account_id = ? AND d.kid_writes = 1 AND s.kind = 'caldav'",
    )
    .bind(account_id)
    .fetch_all(db)
    .await
}

/// The account's waiting writes (D4.2), under its claim. Returns the sources written to.
pub async fn work(
    state: &AppState,
    account_id: i64,
    now: DateTime<Utc>,
) -> Result<Vec<i64>, sqlx::Error> {
    if marks_of(&state.db, account_id).await?.is_empty() {
        return Ok(Vec::new());
    }
    let Some(token) = claim(&state.db, account_id, now).await? else {
        return Ok(Vec::new());
    };
    let result = work_claimed(state, account_id, &token, now).await;
    // Released only while it is still this job's (code QA M3).
    let released = sqlx::query(
        "UPDATE caldav_accounts SET write_claimed_at = NULL, write_claim = NULL WHERE id = ? AND write_claim = ?",
    )
    .bind(account_id)
    .bind(&token)
    .execute(&state.db)
    .await;
    if let Err(err) = released {
        tracing::error!(account_id, %err, "couldn't release a CalDAV account's write job");
    }
    let wrote = result?;
    for source in &wrote {
        // The calendar fetched after the job, at most once a minute (QA L5); its fetch reads it whatever its ctag.
        sqlx::query(
            "UPDATE calendar_sources SET next_at = MIN(next_at, MAX(?, COALESCE(datetime(tried_at, ?), ?))) \
             WHERE id = ?",
        )
        .bind(stamp(now))
        .bind(format!("+{} seconds", FETCH_GAP.num_seconds()))
        .bind(stamp(now))
        .bind(source)
        .execute(&state.db)
        .await?;
    }
    Ok(wrote)
}

/// A step's own time: the instant it runs, never before the pass began - a write's `fetch_wait` is when it was
/// written (code QA H3), not when the pass started.
fn step_time(state: &AppState, pass: DateTime<Utc>) -> DateTime<Utc> {
    state.kid_writes.now().max(pass)
}

/// Steps a row may take again within one pass (an ETag learned, our own lost answer) before it backs off (code QA
/// L7).
const AGAIN_PER_ROW: u32 = 2;

/// "Legg dem tilbake på telefonen" for one row (D2.6, code QA H2): read from the calendar and handed back; a 404
/// drops the row (gone on both sides); anything else waits for the next pass - the row is never deleted meanwhile.
async fn restore_step(
    state: &AppState,
    target: &Target<'_>,
    row: &Row,
    now: DateTime<Utc>,
) -> Result<Step, sqlx::Error> {
    let Some(href) = row.href.clone() else {
        sqlx::query("DELETE FROM kid_caldav_events WHERE device_id = ? AND uid = ?")
            .bind(target.device_id)
            .bind(&row.uid)
            .execute(&state.db)
            .await?;
        return Ok(Step::Done);
    };
    let got = match target.get(state, &href).await {
        Ok(got) => got,
        Err(err) => return failed_request(state, target, row, err, now).await,
    };
    match got.status {
        200 => {}
        404 => {
            sqlx::query("DELETE FROM kid_caldav_events WHERE device_id = ? AND uid = ?")
                .bind(target.device_id)
                .bind(&row.uid)
                .execute(&state.db)
                .await?;
            return Ok(Step::Done);
        }
        429 | 503 => {
            return backoff_account(&state.db, target.login.id, got.retry_after.as_deref(), now)
                .await;
        }
        _ => return backoff_event(&state.db, target.device_id, row, now, "status").await,
    }
    let Some(etag) = strong(got.etag.as_deref()) else {
        return Ok(Step::StopDevice("no_etag"));
    };
    let Some(read) = ics::read_kid_resource(&got.body).filter(|r| r.uid == row.uid) else {
        return backoff_event(&state.db, target.device_id, row, now, "unreadable").await;
    };
    let zone = row_zone(state, target.device_id, &row.uid).await?;
    let theirs = resource_version(&read, zone);
    if read.recurring || read.invites {
        release(&state.db, target.device_id, &row.uid, &theirs, &etag).await?;
    } else {
        hand_back(
            &state.db,
            target.device_id,
            &row.uid,
            &theirs,
            &etag,
            read.sequence,
        )
        .await?;
    }
    Ok(Step::Done)
}

async fn work_claimed(
    state: &AppState,
    account_id: i64,
    token: &str,
    now: DateTime<Utc>,
) -> Result<Vec<i64>, sqlx::Error> {
    let mut wrote = Vec::new();
    // No request while the account isn't `ok` or its password can't be opened (D4.2): the writes wait.
    let login = match crate::caldav::write_login(state, account_id).await? {
        Ok(login) => login,
        Err(_) => return Ok(wrote),
    };
    let backoff: Option<Option<String>> =
        sqlx::query_scalar("SELECT write_backoff_until FROM caldav_accounts WHERE id = ?")
            .bind(account_id)
            .fetch_optional(&state.db)
            .await?;
    if backoff
        .flatten()
        .as_deref()
        .and_then(parse_stamp)
        .is_some_and(|until| until > now)
    {
        return Ok(wrote);
    }
    let mut steps = 0i64;
    'devices: for (device_id, source_id, source_uid, sealed, nonce, fp, source_error) in
        marks_of(&state.db, account_id).await?
    {
        replan(state, device_id, source_id, now).await?;
        let before = counts(&state.db, device_id, now).await?;
        let Some(collection) = state.music_key.as_ref().and_then(|key| {
            key.open_with(
                &crate::calendar_feeds::aad(&fp, &source_uid),
                &sealed,
                &nonce,
                &fp,
            )
            .and_then(|u| String::from_utf8(u).ok())
        }) else {
            continue;
        };
        // A calendar the last Test no longer lists, or one it says the account can't write to, takes no write - and
        // the page says so (code QA L8).
        let listed = login.calendars.iter().find(|c| c.href == collection);
        let stop = if listed.is_none() || source_error.as_deref() == Some("not_listed") {
            Some("not_listed")
        } else if listed.is_some_and(|c| c.writable == Some(false)) {
            Some("write_refused")
        } else {
            None
        };
        if let Some(code) = stop {
            stop_device(&state.db, device_id, code, now).await?;
            let after = counts(&state.db, device_id, now).await?;
            nudge_if(state, device_id, &before, &after);
            continue;
        }
        let set = stored_set(&state.db, device_id).await?;
        let Some(mut set) = set.filter(|s| s.rev.is_some()) else {
            continue;
        };
        if set
            .error
            .as_deref()
            .is_some_and(|e| matches!(e, "write_refused" | "full" | "no_etag" | "not_listed"))
        {
            continue;
        }
        let target = Target {
            login: &login,
            device_id,
            collection,
        };
        let mut wrote_here = false;
        let mut stopped_account = false;
        let mut again: HashMap<String, u32> = HashMap::new();
        loop {
            let at = step_time(state, now);
            if !keep_claim(&state.db, account_id, token, at).await? {
                tracing::warn!(account_id, "a CalDAV account's write job lost its claim");
                break 'devices;
            }
            // Each write reads the latest set just before it goes (D4.2).
            let rev: Option<Option<String>> =
                sqlx::query_scalar("SELECT rev FROM device_kid_events WHERE device_id = ?")
                    .bind(device_id)
                    .fetch_optional(&state.db)
                    .await?;
            if rev.flatten() != set.rev {
                replan(state, device_id, source_id, at).await?;
                match stored_set(&state.db, device_id).await? {
                    Some(fresh) => set = fresh,
                    None => break,
                }
            }
            // "Put them back" first: those rows wait for nothing else.
            let restore: Option<Row> = sqlx::query_as(&format!(
                "SELECT {ROW_COLUMNS} FROM kid_caldav_events WHERE device_id = ? AND back_id IS NULL \
                 AND restore_wanted = 1 AND (next_at IS NULL OR next_at <= ?) ORDER BY uid LIMIT 1"
            ))
            .bind(device_id)
            .bind(stamp(at))
            .fetch_optional(&state.db)
            .await?;
            let next: Option<Row> = match restore {
                Some(row) => Some(row),
                None => {
                    sqlx::query_as(&format!(
                        "SELECT {ROW_COLUMNS} FROM kid_caldav_events WHERE device_id = ? AND back_id IS NULL \
                         AND restore_wanted = 0 AND state IN ('pending', 'deleting') \
                         AND (next_at IS NULL OR next_at <= ?) ORDER BY uid LIMIT 1"
                    ))
                    .bind(device_id)
                    .bind(stamp(at))
                    .fetch_optional(&state.db)
                    .await?
                }
            };
            let Some(next) = next else {
                break;
            };
            if steps >= WRITES_A_MINUTE || !take_budget(&state.db, account_id, at).await? {
                stopped_account = true;
                break;
            }
            steps += 1;
            let outcome = if next.restore_wanted {
                restore_step(state, &target, &next, at).await?
            } else {
                let wanted = set.events.iter().find(|e| e.uid == next.uid);
                step(state, &target, &next, wanted, at).await?
            };
            // The answer's instant on what this step wrote or read (revision RN1, RL2): a REPORT sent before it is never
            // judged against it - a written row, a hand-back or a release from a 412 or a 404.
            sqlx::query(
                "UPDATE kid_caldav_events SET fetch_wait = ? WHERE device_id = ? AND uid = ? \
                 AND (fetch_wait IS NOT NULL OR back_id IS NOT NULL OR state = 'released')",
            )
            .bind(fine_stamp(step_time(state, now)))
            .bind(device_id)
            .bind(&next.uid)
            .execute(&state.db)
            .await?;
            match outcome {
                Step::Wrote => wrote_here = true,
                Step::Done => {
                    // A step that left the row as it was would come round again: wait for the next pass.
                    let after = row(&state.db, device_id, &next.uid).await?;
                    if after.as_ref().is_some_and(|a| {
                        a.state == next.state
                            && a.etag == next.etag
                            && a.back_id.is_none()
                            && a.next_at == next.next_at
                            && a.restore_wanted == next.restore_wanted
                    }) {
                        sqlx::query("UPDATE kid_caldav_events SET next_at = ? WHERE device_id = ? AND uid = ?")
                            .bind(stamp(at + TimeDelta::minutes(15)))
                            .bind(device_id)
                            .bind(&next.uid)
                            .execute(&state.db)
                            .await?;
                    }
                }
                Step::Again => {
                    let n = again.entry(next.uid.clone()).or_default();
                    *n += 1;
                    if *n > AGAIN_PER_ROW
                        && let Some(row) = row(&state.db, device_id, &next.uid).await?
                    {
                        backoff_event(&state.db, device_id, &row, at, "again").await?;
                    }
                }
                Step::StopDevice(code) => {
                    stop_device(&state.db, device_id, code, at).await?;
                    break;
                }
                Step::StopAccount => {
                    stopped_account = true;
                    break;
                }
            }
        }
        if wrote_here {
            sqlx::query("UPDATE device_kid_events SET written_at = ? WHERE device_id = ?")
                .bind(stamp(step_time(state, now)))
                .bind(device_id)
                .execute(&state.db)
                .await?;
            wrote.push(source_id);
        }
        let after = counts(&state.db, device_id, now).await?;
        nudge_if(state, device_id, &before, &after);
        if stopped_account {
            break 'devices;
        }
    }
    if !wrote.is_empty() {
        // A write that went through ends a backoff that has passed (code QA L6).
        sqlx::query(
            "UPDATE caldav_accounts SET write_failures = 0, write_backoff_until = NULL WHERE id = ? \
             AND (write_backoff_until IS NULL OR write_backoff_until <= ?)",
        )
        .bind(account_id)
        .bind(stamp(step_time(state, now)))
        .execute(&state.db)
        .await?;
    }
    Ok(wrote)
}

async fn stop_device(
    db: &sqlx::SqlitePool,
    device_id: i64,
    code: &str,
    now: DateTime<Utc>,
) -> Result<(), sqlx::Error> {
    sqlx::query("UPDATE device_kid_events SET error = ?, error_at = ? WHERE device_id = ?")
        .bind(code)
        .bind(stamp(now))
        .bind(device_id)
        .execute(db)
        .await?;
    tracing::warn!(
        device_id,
        code,
        "a phone's kid events stopped being written"
    );
    Ok(())
}

/// A Test that passed (design 72b D4.4): the stops it answers (`write_refused`, `full`, `no_etag`) are cleared for
/// every phone writing to one of the account's calendars, and the account's backoff.
pub async fn account_tested(db: &sqlx::SqlitePool, account_id: i64) -> Result<(), sqlx::Error> {
    sqlx::query(
        "UPDATE device_kid_events SET error = NULL, error_at = NULL \
         WHERE error IN ('write_refused', 'full', 'no_etag', 'not_listed') \
         AND source_id IN (SELECT id FROM calendar_sources WHERE account_id = ?)",
    )
    .bind(account_id)
    .execute(db)
    .await?;
    sqlx::query(
        "UPDATE caldav_accounts SET write_backoff_until = NULL, write_failures = 0 WHERE id = ?",
    )
    .bind(account_id)
    .execute(db)
    .await?;
    Ok(())
}

/// The marked calendar's scheduled fetch runs its waiting writes first (D4.2). Returns whether a write still waits for
/// a fetch to store it - the fetch then reads the calendar whatever its ctag says.
pub async fn before_fetch(
    state: &AppState,
    source_id: i64,
    account_id: i64,
    now: DateTime<Utc>,
) -> Result<bool, sqlx::Error> {
    let writers: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM device_calendar_feeds WHERE source_id = ? AND kid_writes = 1",
    )
    .bind(source_id)
    .fetch_one(&state.db)
    .await?;
    if writers == 0 {
        return Ok(false);
    }
    work(state, account_id, now).await?;
    let waiting: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM kid_caldav_events WHERE source_id = ? AND fetch_wait IS NOT NULL",
    )
    .bind(source_id)
    .fetch_one(&state.db)
    .await?;
    Ok(waiting > 0)
}

// ---- the way back (D4.6) -----------------------------------------------------------------------

/// After the marked calendar's fetch stored its set (fetched at `now`): the writes made before it are in it (R-L2);
/// each kid event's resource judged by its UID; a uid missing from a REPORT that covers its dates confirmed by its
/// own GET; the calendar's mass delete held 24 h (D2.6).
pub async fn after_fetch(
    state: &AppState,
    source_id: i64,
    resources: &[DavResource],
    now: DateTime<Utc>,
    report_at: DateTime<Utc>,
) -> Result<(), sqlx::Error> {
    let devices: Vec<(i64, Option<i64>)> = sqlx::query_as(
        "SELECT d.device_id, s.account_id FROM device_calendar_feeds d JOIN calendar_sources s ON s.id = d.source_id \
         WHERE d.source_id = ? AND d.kid_writes = 1",
    )
    .bind(source_id)
    .fetch_all(&state.db)
    .await?;
    if devices.is_empty() {
        return Ok(());
    }
    let mut by_uid: HashMap<String, Vec<&DavResource>> = HashMap::new();
    for r in resources {
        if let Some(uid) = ics::resource_uid(&r.data) {
            by_uid.entry(uid).or_default().push(r);
        }
    }
    // Two resources with the kid's UID (another client's copy): the one at our own href (code QA N7).
    let ours = |row: &Row| -> Option<&DavResource> {
        let found = by_uid.get(&row.uid)?;
        let mine = row.href.as_deref().and_then(|href| {
            let base = reqwest::Url::parse(href).ok()?;
            let path = decoded(base.path());
            found.iter().copied().find(|r| {
                base.join(&r.href)
                    .is_ok_and(|url| decoded(url.path()) == path)
            })
        });
        mine.or_else(|| found.first().copied())
    };
    let today = now.date_naive();
    let window = (
        (today - TimeDelta::days(ics::PAST_DAYS))
            .and_time(chrono::NaiveTime::MIN)
            .and_utc(),
        (today + TimeDelta::days(ics::AHEAD_DAYS + 1))
            .and_time(chrono::NaiveTime::MIN)
            .and_utc(),
    );
    let mut login: Option<Option<WriteLogin>> = None;
    for (device_id, account_id) in devices {
        let before = counts(&state.db, device_id, now).await?;
        clear_back_held(&state.db, device_id).await?;
        // The writes made before this REPORT was sent are in its set now (R-L2); one made after it is judged by a
        // later fetch, never by this one (code QA H3).
        sqlx::query(
            "UPDATE kid_caldav_events SET fetch_wait = NULL WHERE device_id = ? AND source_id = ? AND fetch_wait <= ?",
        )
        .bind(device_id)
        .bind(source_id)
        .bind(fine_stamp(report_at))
        .execute(&state.db)
        .await?;
        sqlx::query("DELETE FROM kid_caldav_events WHERE device_id = ? AND state = 'gone' AND fetch_wait IS NULL")
            .bind(device_id)
            .execute(&state.db)
            .await?;
        let zones: HashMap<String, Option<Tz>> = stored_set(&state.db, device_id)
            .await?
            .map(|s| s.events.iter().map(|e| (e.uid.clone(), e.zone())).collect())
            .unwrap_or_default();
        let rows: Vec<Row> = sqlx::query_as(&format!(
            "SELECT {ROW_COLUMNS} FROM kid_caldav_events WHERE device_id = ? AND etag IS NOT NULL \
             AND state NOT IN ('past', 'released', 'gone') ORDER BY uid"
        ))
        .bind(device_id)
        .fetch_all(&state.db)
        .await?;
        let written = rows.len();
        let rows: Vec<Row> = rows
            .into_iter()
            .filter(|r| r.fetch_wait.is_none())
            .collect();
        let mut deleted: Vec<String> = Vec::new();
        for r in &rows {
            let zone = zones.get(&r.uid).copied().flatten();
            let deleting_back = r.back_is_delete() && r.state != "released";
            match ours(r) {
                Some(resource) => {
                    let Some(etag) = strong(resource.etag.as_deref()) else {
                        stop_device(&state.db, device_id, "no_etag", now).await?;
                        continue;
                    };
                    if deleting_back {
                        if r.back_held(now) {
                            // Back on the calendar while its delete was held, never sent (a wipe undone): no delete.
                            sqlx::query(
                                "UPDATE kid_caldav_events SET back_id = NULL, back_json = NULL, back_after = NULL \
                                 WHERE device_id = ? AND uid = ?",
                            )
                            .bind(device_id)
                            .bind(&r.uid)
                            .execute(&state.db)
                            .await?;
                        } else {
                            // The phone may have deleted its copy already (code QA H1): the restored version goes
                            // back as an upsert with a new id - re-inserted, or a no-op - never cleared.
                            let Some(read) = ics::read_kid_resource(resource.data.as_bytes())
                                .filter(|read| read.uid == r.uid)
                            else {
                                continue;
                            };
                            let theirs = resource_version(&read, zone);
                            if read.recurring || read.invites {
                                release(&state.db, device_id, &r.uid, &theirs, &etag).await?;
                            } else {
                                hand_back(
                                    &state.db,
                                    device_id,
                                    &r.uid,
                                    &theirs,
                                    &etag,
                                    read.sequence,
                                )
                                .await?;
                            }
                            continue;
                        }
                    }
                    if r.etag.as_deref() == Some(etag.as_str()) {
                        continue;
                    }
                    let Some(read) = ics::read_kid_resource(resource.data.as_bytes()) else {
                        continue;
                    };
                    judge(state, device_id, &r.uid, &read, &etag, zone).await?;
                }
                None if deleting_back => {}
                None => {
                    let start = r.start_at.as_deref().and_then(parse_stamp);
                    let end = r.end_at.as_deref().and_then(parse_stamp);
                    let covered = match (start, end) {
                        (Some(s), Some(e)) => s < window.1 && e >= window.0,
                        _ => false,
                    };
                    if !covered {
                        continue;
                    }
                    // A missing resource is confirmed by its own href (D2.6).
                    let Some(account) = account_id else {
                        continue;
                    };
                    if login.is_none() {
                        login = Some(crate::caldav::write_login(state, account).await?.ok());
                    }
                    let Some(Some(l)) = login.as_ref() else {
                        continue;
                    };
                    let Some(href) = r.href.as_deref() else {
                        continue;
                    };
                    let request = DavRequest {
                        method: "GET",
                        depth: None,
                        content_type: None,
                        condition: None,
                        body: &[],
                    };
                    match l.send(state.music_fetch.as_ref(), href, &request).await {
                        Ok(got) if got.status == 404 => deleted.push(r.uid.clone()),
                        Ok(got) if got.status == 200 => {
                            let (Some(etag), Some(read)) = (
                                strong(got.etag.as_deref()),
                                ics::read_kid_resource(&got.body),
                            ) else {
                                continue;
                            };
                            if read.uid == r.uid {
                                judge(state, device_id, &r.uid, &read, &etag, zone).await?;
                            }
                        }
                        Ok(_) => {}
                        Err(err) if matches!(err.code, "login_refused" | "host_changed") => {
                            l.stop(state, err.code).await?;
                            login = Some(None);
                        }
                        Err(_) => {}
                    }
                }
            }
        }
        // The calendar's mass delete waits 24 h (QA L9), as the kid's does.
        let held = deleted.len() >= HOLD_AT && deleted.len() * 2 > written;
        let since: Option<Option<String>> =
            sqlx::query_scalar("SELECT back_held_since FROM device_kid_events WHERE device_id = ?")
                .bind(device_id)
                .fetch_optional(&state.db)
                .await?;
        let since = since.flatten().as_deref().and_then(parse_stamp);
        let after_at = if held {
            let since = since.unwrap_or(now);
            sqlx::query("UPDATE device_kid_events SET back_held_since = ? WHERE device_id = ?")
                .bind(stamp(since))
                .bind(device_id)
                .execute(&state.db)
                .await?;
            Some(since + HOLD)
        } else {
            None
        };
        for uid in &deleted {
            hand_back_delete(&state.db, device_id, uid, after_at.filter(|at| *at > now)).await?;
        }
        clear_back_held(&state.db, device_id).await?;
        if !deleted.is_empty() {
            tracing::info!(
                device_id,
                deletes = deleted.len(),
                held,
                "kid events the parent deleted are handed back"
            );
        }
        let after = counts(&state.db, device_id, now).await?;
        nudge_if(state, device_id, &before, &after);
    }
    Ok(())
}

/// The calendar's hold ends with its last held hand-back (code QA M2: a wipe undone must not leave a stale start
/// that lets the next wipe through at once).
async fn clear_back_held(db: &sqlx::SqlitePool, device_id: i64) -> Result<(), sqlx::Error> {
    sqlx::query(
        "UPDATE device_kid_events SET back_held_since = NULL WHERE device_id = ? AND NOT EXISTS \
         (SELECT 1 FROM kid_caldav_events WHERE device_id = ? AND back_after IS NOT NULL)",
    )
    .bind(device_id)
    .bind(device_id)
    .execute(db)
    .await
    .map(|_| ())
}

// ---- the scheduler -----------------------------------------------------------------------------

/// The job (D4.2): woken by a POST, else every minute - each account with a marked calendar, then the held
/// hand-backs whose time came (the phone nudged).
pub async fn run(state: AppState) {
    tokio::time::sleep(std::time::Duration::from_secs(20)).await;
    loop {
        tokio::select! {
            _ = state.kid_writes.notify.notified() => {}
            _ = tokio::time::sleep(std::time::Duration::from_secs(60)) => {}
        }
        run_due(&state, state.kid_writes.now()).await;
    }
}

pub async fn run_due(state: &AppState, now: DateTime<Utc>) {
    let accounts: Result<Vec<i64>, sqlx::Error> = sqlx::query_scalar(
        "SELECT DISTINCT s.account_id FROM device_calendar_feeds d JOIN calendar_sources s ON s.id = d.source_id \
         WHERE d.kid_writes = 1 AND s.account_id IS NOT NULL",
    )
    .fetch_all(&state.db)
    .await;
    match accounts {
        Ok(accounts) => {
            for account in accounts {
                if let Err(err) = work(state, account, now).await {
                    tracing::error!(account_id = account, %err, "couldn't write a phone's kid events");
                }
            }
        }
        Err(err) => tracing::error!(%err, "couldn't read the marked calendars"),
    }
    // A held hand-back whose day has passed goes to the phone now.
    let due: Result<Vec<i64>, sqlx::Error> = sqlx::query_scalar(
        "SELECT DISTINCT device_id FROM kid_caldav_events WHERE back_after IS NOT NULL AND back_after <= ?",
    )
    .bind(stamp(now))
    .fetch_all(&state.db)
    .await;
    if let Ok(due) = due {
        for device in due {
            let cleared = sqlx::query(
                "UPDATE kid_caldav_events SET back_after = NULL WHERE device_id = ? AND back_after <= ?",
            )
            .bind(device)
            .bind(stamp(now))
            .execute(&state.db)
            .await;
            let _ = sqlx::query(
                "UPDATE device_kid_events SET back_held_since = NULL WHERE device_id = ?",
            )
            .bind(device)
            .execute(&state.db)
            .await;
            if cleared.is_ok() {
                let _ = state.command_notify.send(device);
            }
        }
    }
}

// ---- the parent's side -------------------------------------------------------------------------

/// "Legg dem tilbake på telefonen" (D2.6): the kid's held deletes marked to be handed back as the calendar has them.
/// The job reads each one under the account's claim, backoff and budget; until then a marked row is never deleted,
/// and one that can't be read waits for the next pass (code QA H2). Returns how many were marked; nothing while no
/// hold runs.
pub async fn restore_held(
    state: &AppState,
    device_id: i64,
    now: DateTime<Utc>,
) -> Result<usize, sqlx::Error> {
    let Some((source_id, _, Some(_))) = marked(&state.db, device_id).await? else {
        return Ok(0);
    };
    let Some(set) = stored_set(&state.db, device_id).await? else {
        return Ok(0);
    };
    if set.held_since.is_none_or(|since| now - since >= HOLD) {
        return Ok(0);
    }
    let rows = rows_of(&state.db, device_id).await?;
    let held = plan(&set, &rows, now).deletes;
    let mut tx = state.db.begin().await?;
    for uid in &held {
        sqlx::query(
            "UPDATE kid_caldav_events SET restore_wanted = 1, next_at = NULL WHERE device_id = ? AND uid = ? \
             AND back_id IS NULL",
        )
        .bind(device_id)
        .bind(uid)
        .execute(&mut *tx)
        .await?;
    }
    // Planned again at once: the marked rows are kept, the hold ends with no delete left.
    let rows = rows_of(&mut *tx, device_id).await?;
    let fresh = plan(&set, &rows, now);
    apply_plan(&mut tx, device_id, source_id, &set, &fresh, now).await?;
    tx.commit().await?;
    state.kid_writes.wake();
    tracing::info!(
        device_id,
        held = held.len(),
        "the kid's held events are to be handed back to the phone"
    );
    Ok(held.len())
}

/// What the status line under the select says (D5.2): counts only, never a title.
pub struct KidStatus {
    pub received: bool,
    pub written_at: Option<String>,
    pub events: i64,
    pub pending: i64,
    pub back: i64,
    pub cut: i64,
    pub refused: i64,
    pub error: Option<String>,
    /// The code of the last refused set (`bad_set`, `tag_pinned`) and the other device for a pinned tag.
    pub rejected: Option<(String, Option<i64>)>,
    pub clock: bool,
    /// The kid's held deletes: how many, until when.
    pub held: Option<(usize, DateTime<Utc>)>,
    /// The calendar's held deletes: how many, until when.
    pub back_held: Option<(i64, DateTime<Utc>)>,
    /// "Legg dem tilbake": the events still to be read from the calendar and handed back.
    pub restoring: i64,
}

pub async fn status(
    db: &sqlx::SqlitePool,
    device_id: i64,
    now: DateTime<Utc>,
) -> Result<Option<KidStatus>, sqlx::Error> {
    if marked(db, device_id).await?.is_none() {
        return Ok(None);
    }
    let Some(set) = stored_set(db, device_id).await? else {
        return Ok(Some(KidStatus {
            received: false,
            written_at: None,
            events: 0,
            pending: 0,
            back: 0,
            cut: 0,
            refused: 0,
            error: None,
            rejected: None,
            clock: false,
            held: None,
            back_held: None,
            restoring: 0,
        }));
    };
    let extra: (Option<String>, Option<String>, Option<String>, Option<String>) = sqlx::query_as(
        "SELECT written_at, rejected, received_at, back_held_since FROM device_kid_events WHERE device_id = ?",
    )
    .bind(device_id)
    .fetch_one(db)
    .await?;
    let refused: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM kid_caldav_events WHERE device_id = ? AND state = 'refused'",
    )
    .bind(device_id)
    .fetch_one(db)
    .await?;
    let rows = rows_of(db, device_id).await?;
    let restoring = rows.values().filter(|r| r.restore_wanted).count() as i64;
    let held = match set.held_since {
        Some(since) if set.rev.is_some() && now - since < HOLD => {
            let n = plan(&set, &rows, now).deletes.len();
            (n > 0).then_some((n, since + HOLD))
        }
        _ => None,
    };
    let back_held_n: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM kid_caldav_events WHERE device_id = ? AND back_after IS NOT NULL AND back_after > ?",
    )
    .bind(device_id)
    .bind(stamp(now))
    .fetch_one(db)
    .await?;
    let back_held = extra
        .3
        .as_deref()
        .and_then(parse_stamp)
        .filter(|_| back_held_n > 0)
        .map(|since| (back_held_n, since + HOLD));
    let rejected = extra.1.map(|code| match code.split_once(':') {
        Some((code, other)) => (code.to_string(), other.parse().ok()),
        None => (code, None),
    });
    Ok(Some(KidStatus {
        received: extra.2.is_some() || set.rev.is_some(),
        written_at: extra.0,
        events: set.events.len() as i64,
        pending: pending_count(db, device_id).await?,
        back: back_count(db, device_id, now).await?,
        cut: set.cut,
        refused,
        error: set.error,
        rejected,
        clock: set.from.is_some_and(|from| (from - now).abs() > CLOCK_SKEW),
        held,
        back_held,
        restoring,
    }))
}

/// Marks `source_id` as the phone's kid calendar, inside the save's transaction (D4.1): a different calendar (or
/// none before) starts afresh - the old rows go, `accepted` is `null` ("send it all"); the same calendar again keeps
/// everything (an autosave of the form).
pub async fn mark(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    device_id: i64,
    source_id: i64,
) -> Result<(), sqlx::Error> {
    let current: Option<i64> = sqlx::query_scalar(
        "SELECT source_id FROM device_calendar_feeds WHERE device_id = ? AND kid_writes = 1",
    )
    .bind(device_id)
    .fetch_optional(&mut **tx)
    .await?;
    if current == Some(source_id) {
        return Ok(());
    }
    unmark(tx, device_id).await?;
    sqlx::query(
        "UPDATE device_calendar_feeds SET kid_writes = 1 WHERE device_id = ? AND source_id = ?",
    )
    .bind(device_id)
    .bind(source_id)
    .execute(&mut **tx)
    .await?;
    sqlx::query("INSERT INTO device_kid_events (device_id, source_id) VALUES (?, ?)")
        .bind(device_id)
        .bind(source_id)
        .execute(&mut **tx)
        .await?;
    Ok(())
}

/// Ends the mark (D4.1, D4.8): the flag cleared and every row of the two event tables deleted in the same
/// transaction; what was written stays in the calendar; the tags stay.
pub async fn unmark(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    device_id: i64,
) -> Result<(), sqlx::Error> {
    sqlx::query("DELETE FROM kid_caldav_events WHERE device_id = ?")
        .bind(device_id)
        .execute(&mut **tx)
        .await?;
    sqlx::query("DELETE FROM device_kid_events WHERE device_id = ?")
        .bind(device_id)
        .execute(&mut **tx)
        .await?;
    sqlx::query(
        "UPDATE device_calendar_feeds SET kid_writes = 0 WHERE device_id = ? AND kid_writes = 1",
    )
    .bind(device_id)
    .execute(&mut **tx)
    .await?;
    Ok(())
}

/// The source uid of the phone's marked calendar, for the page.
pub async fn marked_uid(
    db: &sqlx::SqlitePool,
    device_id: i64,
) -> Result<Option<String>, sqlx::Error> {
    Ok(marked(db, device_id).await?.map(|(_, uid, _)| uid))
}

#[cfg(test)]
mod tests;
