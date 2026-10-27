//! A calendar link's body (design 72 §2.2-§2.3, `docs/design/72-calendar-feeds.md` at the monorepo root): RFC 5545
//! read into the set the phones get (`calendar_feeds`, §3). Our own reader, no crate: neither `ical` nor `icalendar`
//! caps a feed or normalizes its zones, and nothing here expands a recurrence - the phone's calendar provider does.
//!
//! - **Bytes first** (QA H1): the folds are taken out of the bytes ([unfold]: CRLF or LF + space or tab - the same
//!   bytes in every ASCII-compatible charset; a generator may fold inside a UTF-8 sequence), then the whole is decoded:
//!   its byte order mark, else the `Content-Type` charset, else UTF-8 with U+FFFD. It must begin with
//!   `BEGIN:VCALENDAR`, else [NotCalendar].
//! - **Lines**: `NAME;params:value`, quoted parameter values, a BEGIN/END stack at most [MAX_DEPTH] deep (deeper is
//!   skipped whole), a line cut at [MAX_LINE] bytes, at most [MAX_VEVENTS] VEVENTs (the rest counted `cut`). Read:
//!   VEVENT, its VALARMs, VTIMEZONE's `TZID` and `X-LIC-LOCATION`, the calendar's `X-WR-CALNAME` and
//!   `X-WR-TIMEZONE`; everything else is ignored.
//! - **Zones** (§2.3): every time goes on the wire in its series' zone, as RFC 5545 basic local time; `Z` is `UTC`;
//!   a TZID that is an IANA name passes; else its VTIMEZONE's `X-LIC-LOCATION`, a known prefix stripped, or a Windows
//!   name through CLDR's table (`windows_zones.txt`); else it is unknown - floating, counted `unknown_tz`, shown in the
//!   phone's time. A floating series takes `X-WR-TIMEZONE` when it is an IANA name, else `tz: null` (the phone's
//!   zone). All-day is a date. EXDATE, RDATE, RECURRENCE-ID and an override's own start and end are converted into the
//!   series' zone (one instant each; QA M4).
//! - **Events** (§2.2): UIDs never lose an event (QA L4), a series is a master and its overrides (an orphan, or one
//!   whose all-day-ness differs, stands alone), cancelled singles and series dropped, a cancelled override kept as
//!   such, RRULE passed through with `UNTIL` made UTC (or a date) when the zone is known, alarms as minutes before, the
//!   horizon (60 days back, 400 ahead; a series whole) and the caps (2000 events, 2 MB served).

use std::collections::HashMap;
use std::sync::LazyLock;

use chrono::{NaiveDate, NaiveDateTime, NaiveTime, TimeDelta, TimeZone};
use chrono_tz::Tz;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// A content line longer than this is cut (after unfolding).
pub const MAX_LINE: usize = 64 * 1024;
/// VEVENTs read; past it the rest count `cut`.
pub const MAX_VEVENTS: usize = 20_000;
/// The BEGIN/END stack: VCALENDAR, VEVENT, VALARM and one more; deeper components are skipped whole.
pub const MAX_DEPTH: usize = 4;
pub const SUMMARY_MAX: usize = 200;
pub const DESCRIPTION_MAX: usize = 2000;
pub const LOCATION_MAX: usize = 200;
/// Alarms kept per event (Dagen's "own" reminders read at most 120 minutes; a week is the cap kept).
pub const MAX_ALARMS: usize = 5;
pub const ALARM_MAX_MINUTES: i64 = 10_080;
/// The horizon (decision 3): a single event that ended more than this many days ago is dropped.
pub const PAST_DAYS: i64 = 60;
/// ... and one that starts more than this many days ahead.
pub const AHEAD_DAYS: i64 = 400;
/// The longest DURATION or alarm TRIGGER read; a longer one is `bad` (the event takes its fallback length).
pub const MAX_DURATION_DAYS: i64 = 400;
/// Events served per link (a master and each override count one).
pub const MAX_EVENTS: usize = 2000;
/// The served document's size cap.
pub const MAX_SERVED: usize = 2_000_000;

/// The wire's basic forms (§3).
const DATE_TIME: &str = "%Y%m%dT%H%M%S";
const DATE: &str = "%Y%m%d";

/// One override of a series (§3), every time in the series' zone.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct WireOverride {
    pub recurrence_id: String,
    pub summary: String,
    pub description: String,
    pub location: String,
    pub start: String,
    pub end: String,
    /// `confirmed`, `tentative` or `cancelled` (the provider makes no instance of a cancelled one).
    pub status: String,
    pub alarms: Vec<i64>,
}

/// One series (or single event) as the phone gets it (§3).
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct WireEvent {
    pub uid: String,
    /// 16 hex of SHA-256 over this event's JSON with `rev` empty (QA N1).
    pub rev: String,
    pub summary: String,
    pub description: String,
    pub location: String,
    /// `20261027T081500` in `tz`, or `20261027` all-day.
    pub start: String,
    /// Exclusive: an all-day event's day after its last.
    pub end: String,
    /// An IANA zone (`UTC` for `Z` times); `null` = floating, the phone's own zone; `null` for all-day.
    pub tz: Option<String>,
    pub all_day: bool,
    /// `UNTIL` UTC (`...Z`) or a date when the zone is known; local when floating (the phone makes it UTC).
    pub rrule: Option<String>,
    pub rdates: Vec<String>,
    pub exdates: Vec<String>,
    pub status: String,
    /// Minutes before the start, ascending.
    pub alarms: Vec<i64>,
    pub overrides: Vec<WireOverride>,
}

/// What a body gave beyond its events.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct Counts {
    /// Events served: masters and overrides.
    pub events: i64,
    /// Left out by a cap ([MAX_VEVENTS], [MAX_EVENTS], [MAX_SERVED]).
    pub cut: i64,
    /// Events whose TZID no rule knows: shown in the phone's time.
    pub unknown_tz: i64,
    /// Values that couldn't be read (an event without DTSTART, an RRULE we don't pass, a `PERIOD` RDATE).
    pub bad: i64,
}

#[derive(Debug)]
pub struct Parsed {
    /// `X-WR-CALNAME`, unescaped (the caller cleans it).
    pub name: Option<String>,
    /// Sorted by uid.
    pub events: Vec<WireEvent>,
    pub counts: Counts,
}

/// The body doesn't begin with `BEGIN:VCALENDAR` (an HTML login page, garbage).
#[derive(Debug, PartialEq, Eq)]
pub struct NotCalendar;

// ---- bytes -------------------------------------------------------------------------------------

/// The folds taken out (RFC 5545 §3.1): CRLF or a bare LF followed by one space or tab.
pub fn unfold(bytes: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        let fold_at = |j: usize| matches!(bytes.get(j), Some(b' ' | b'\t'));
        if bytes[i] == b'\r' && bytes.get(i + 1) == Some(&b'\n') && fold_at(i + 2) {
            i += 3;
        } else if bytes[i] == b'\n' && fold_at(i + 1) {
            i += 2;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    out
}

/// `charset=` of a `Content-Type`, unquoted.
fn charset(content_type: Option<&str>) -> Option<String> {
    content_type?.split(';').find_map(|part| {
        let (name, value) = part.split_once('=')?;
        name.trim()
            .eq_ignore_ascii_case("charset")
            .then(|| value.trim().trim_matches('"').to_string())
    })
}

/// The body as text (QA H1): unfolded on the bytes, then decoded by its byte order mark, else the `Content-Type`
/// charset, else UTF-8 with U+FFFD. A UTF-16 body (its BOM) is decoded first: its line ends aren't ASCII bytes.
pub fn text_of(body: &[u8], content_type: Option<&str>) -> String {
    if let Some((encoding, bom)) = encoding_rs::Encoding::for_bom(body) {
        let rest = &body[bom..];
        if encoding == encoding_rs::UTF_8 {
            return String::from_utf8_lossy(&unfold(rest)).into_owned();
        }
        let text = encoding.decode_without_bom_handling(rest).0;
        return String::from_utf8_lossy(&unfold(text.as_bytes())).into_owned();
    }
    let unfolded = unfold(body);
    match charset(content_type).and_then(|label| encoding_rs::Encoding::for_label(label.as_bytes()))
    {
        Some(encoding) if encoding != encoding_rs::UTF_8 => encoding
            .decode_without_bom_handling(&unfolded)
            .0
            .into_owned(),
        _ => String::from_utf8_lossy(&unfolded).into_owned(),
    }
}

// ---- content lines -----------------------------------------------------------------------------

#[derive(Clone, Debug, Default)]
struct Prop {
    value: String,
    /// Upper-cased names; a multi-valued parameter's values joined by ",".
    params: Vec<(String, String)>,
}

impl Prop {
    fn param(&self, name: &str) -> Option<&str> {
        self.params
            .iter()
            .find(|(n, _)| n == name)
            .map(|(_, v)| v.as_str())
    }

    fn param_is(&self, name: &str, value: &str) -> bool {
        self.param(name)
            .is_some_and(|v| v.eq_ignore_ascii_case(value))
    }
}

/// `NAME;P1=a,"b:c";P2=d:value` - the name upper-cased, quoted values unquoted; `None` for a line that isn't one.
fn content_line(line: &str) -> Option<(String, Prop)> {
    let bytes = line.as_bytes();
    let mut i = bytes.iter().position(|b| matches!(b, b';' | b':'))?;
    let name = line[..i].trim().to_ascii_uppercase();
    if name.is_empty() {
        return None;
    }
    let mut params = Vec::new();
    while bytes[i] == b';' {
        i += 1;
        let start = i;
        while i < bytes.len() && !matches!(bytes[i], b'=' | b';' | b':') {
            i += 1;
        }
        if i >= bytes.len() {
            return None;
        }
        let param = line[start..i].trim().to_ascii_uppercase();
        let mut values: Vec<&str> = Vec::new();
        if bytes[i] == b'=' {
            loop {
                i += 1;
                if bytes.get(i) == Some(&b'"') {
                    let from = i + 1;
                    let to = from + line[from..].find('"')?;
                    values.push(&line[from..to]);
                    i = to + 1;
                } else {
                    let from = i;
                    while i < bytes.len() && !matches!(bytes[i], b',' | b';' | b':') {
                        i += 1;
                    }
                    values.push(&line[from..i]);
                }
                if i >= bytes.len() {
                    return None;
                }
                if bytes[i] != b',' {
                    break;
                }
            }
        }
        params.push((param, values.join(",")));
    }
    if bytes[i] != b':' {
        return None;
    }
    Some((
        name,
        Prop {
            value: line[i + 1..].to_string(),
            params,
        },
    ))
}

/// At most [MAX_LINE] bytes, on a character boundary.
fn cut_line(line: &str) -> &str {
    if line.len() <= MAX_LINE {
        return line;
    }
    let mut end = MAX_LINE;
    while !line.is_char_boundary(end) {
        end -= 1;
    }
    &line[..end]
}

/// TEXT unescaped: `\n`/`\N` a newline, `\,` `\;` `\\` themselves (and any other escaped character itself).
fn unescape(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    let mut chars = value.chars();
    while let Some(c) = chars.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match chars.next() {
            Some('n' | 'N') => out.push('\n'),
            Some(other) => out.push(other),
            None => {}
        }
    }
    out
}

/// A text field: unescaped, tabs as spaces, every other control character but `\n` out, and the invisible marks the
/// title rules drop (a BOM mid-line, zero-width spaces, bidi marks; `calendar::is_dropped`, QA L8), trimmed, at most
/// `max` code points.
fn text(raw: Option<&str>, max: usize) -> String {
    let unescaped = unescape(raw.unwrap_or(""));
    let kept: String = unescaped
        .chars()
        .map(|c| if c == '\t' { ' ' } else { c })
        .filter(|c| *c == '\n' || !(c.is_control() || crate::calendar::is_dropped(*c)))
        .collect();
    let cut: String = kept.trim().chars().take(max).collect();
    cut.trim_end().to_string()
}

fn hex_of(text: &str) -> String {
    hex::encode(Sha256::digest(text.as_bytes()))
}

// ---- zones -------------------------------------------------------------------------------------

/// CLDR's Windows names (territory 001), the vendored table.
static WINDOWS: LazyLock<HashMap<&'static str, &'static str>> = LazyLock::new(|| {
    include_str!("windows_zones.txt")
        .lines()
        .filter(|l| !l.starts_with('#') && !l.trim().is_empty())
        .filter_map(|l| l.split_once('\t'))
        .collect()
});

/// The vendored table's rows (the tests).
#[cfg(test)]
pub fn windows_zones() -> Vec<(&'static str, &'static str)> {
    WINDOWS.iter().map(|(k, v)| (*k, *v)).collect()
}

fn olson(name: &str) -> Option<Tz> {
    name.trim().parse::<Tz>().ok()
}

/// A TZID's zone (§2.3): an IANA name, its VTIMEZONE's `X-LIC-LOCATION`, a known prefix stripped
/// (`/mozilla.org/<n>/`, `/freeassociation.sourceforge.net/Tzfile/`, `/softwarestudio.org/Tzfile/`), a Windows name;
/// else `None` (unknown).
fn resolve_zone(tzid: &str, vtimezones: &HashMap<String, String>) -> Option<Tz> {
    let id = tzid.trim().trim_matches('"');
    let stripped = || {
        if let Some(rest) = id.strip_prefix("/mozilla.org/") {
            return rest.split_once('/').map(|(_, zone)| zone);
        }
        ["/freeassociation.sourceforge.net/", "/softwarestudio.org/"]
            .iter()
            .find_map(|prefix| id.strip_prefix(prefix))
            .map(|rest| rest.strip_prefix("Tzfile/").unwrap_or(rest))
    };
    olson(id)
        .or_else(|| vtimezones.get(id).and_then(|l| olson(l)))
        .or_else(|| stripped().and_then(olson))
        .or_else(|| WINDOWS.get(id).and_then(|z| olson(z)))
}

/// A date or time as the body wrote it.
#[derive(Clone, Copy, Debug)]
enum When {
    Date(NaiveDate),
    Utc(NaiveDateTime),
    /// Local time in a known zone, or floating (`None`; `true` = a TZID no rule knew).
    Local(NaiveDateTime, Option<Tz>, bool),
}

impl When {
    fn is_date(self) -> bool {
        matches!(self, When::Date(_))
    }
}

fn parse_when(value: &str, prop: &Prop, vtimezones: &HashMap<String, String>) -> Option<When> {
    let value = value.trim();
    if value.len() == 8 || prop.param_is("VALUE", "DATE") {
        return NaiveDate::parse_from_str(value.get(..8)?, DATE)
            .ok()
            .map(When::Date);
    }
    let (value, utc) = match value.strip_suffix(['Z', 'z']) {
        Some(rest) => (rest, true),
        None => (value, false),
    };
    let at = NaiveDateTime::parse_from_str(value, DATE_TIME).ok()?;
    if utc {
        return Some(When::Utc(at));
    }
    Some(match prop.param("TZID") {
        Some(id) => match resolve_zone(id, vtimezones) {
            Some(tz) => When::Local(at, Some(tz), false),
            None => When::Local(at, None, true),
        },
        None => When::Local(at, None, false),
    })
}

/// The zone a series' times are written in.
#[derive(Clone, Copy, Debug, PartialEq)]
enum SeriesZone {
    AllDay,
    Zoned(Tz),
    Floating,
}

impl SeriesZone {
    fn name(self) -> Option<String> {
        match self {
            SeriesZone::Zoned(tz) => Some(tz.name().to_string()),
            _ => None,
        }
    }
}

/// A time in its series' zone.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
enum Moment {
    Day(NaiveDate),
    At(NaiveDateTime),
}

impl Moment {
    fn wire(self) -> String {
        match self {
            Moment::Day(d) => d.format(DATE).to_string(),
            Moment::At(t) => t.format(DATE_TIME).to_string(),
        }
    }

    fn date(self) -> NaiveDate {
        match self {
            Moment::Day(d) => d,
            Moment::At(t) => t.date(),
        }
    }

    /// `self` moved on by `by` (a day at least for a date, never back); checked - at chrono's edge it stays put.
    fn plus(self, by: TimeDelta) -> Moment {
        match self {
            Moment::Day(d) => {
                let days = u64::try_from(by.num_days().max(1)).unwrap_or(1);
                Moment::Day(d.checked_add_days(chrono::Days::new(days)).unwrap_or(d))
            }
            Moment::At(t) => {
                Moment::At(t.checked_add_signed(by.max(TimeDelta::zero())).unwrap_or(t))
            }
        }
    }

    fn since(self, earlier: Moment) -> TimeDelta {
        match (self, earlier) {
            (Moment::Day(a), Moment::Day(b)) => a - b,
            (Moment::At(a), Moment::At(b)) => a - b,
            _ => TimeDelta::zero(),
        }
    }
}

/// The instant of a local time in `tz` (a time in a gap: an hour later's).
fn instant(tz: Tz, at: NaiveDateTime) -> chrono::DateTime<Tz> {
    tz.from_local_datetime(&at)
        .earliest()
        .or_else(|| {
            tz.from_local_datetime(&at.checked_add_signed(TimeDelta::hours(1))?)
                .earliest()
        })
        .unwrap_or_else(|| tz.from_utc_datetime(&at))
}

/// `when` in the series' zone (one instant, no recurrence arithmetic). A date in a timed series takes `time_of_day`.
fn in_zone(when: When, zone: SeriesZone, time_of_day: NaiveTime) -> Moment {
    match (zone, when) {
        (SeriesZone::AllDay, When::Date(d)) => Moment::Day(d),
        (SeriesZone::AllDay, When::Utc(t) | When::Local(t, ..)) => Moment::Day(t.date()),
        (_, When::Date(d)) => Moment::At(d.and_time(time_of_day)),
        (SeriesZone::Zoned(tz), When::Utc(t)) => Moment::At(
            chrono::Utc
                .from_utc_datetime(&t)
                .with_timezone(&tz)
                .naive_local(),
        ),
        (SeriesZone::Zoned(tz), When::Local(t, Some(own), _)) => {
            Moment::At(instant(own, t).with_timezone(&tz).naive_local())
        }
        (_, When::Utc(t) | When::Local(t, ..)) => Moment::At(t),
    }
}

/// An RFC 5545 duration (`-P1D`, `PT15M`, `P1W`), signed.
fn parse_duration(value: &str) -> Option<TimeDelta> {
    let value = value.trim().to_ascii_uppercase();
    let (negative, rest) = match value.as_bytes().first()? {
        b'-' => (true, &value[1..]),
        b'+' => (false, &value[1..]),
        _ => (false, &value[..]),
    };
    let rest = rest.strip_prefix('P')?;
    let (mut seconds, mut number, mut in_time, mut any) = (0i64, String::new(), false, false);
    for c in rest.chars() {
        match c {
            '0'..='9' => number.push(c),
            'T' if !in_time && number.is_empty() => in_time = true,
            'W' | 'D' | 'H' | 'M' | 'S' => {
                let unit = match (c, in_time) {
                    ('W', false) => 604_800,
                    ('D', false) => 86_400,
                    ('H', true) => 3600,
                    ('M', true) => 60,
                    ('S', true) => 1,
                    _ => return None,
                };
                let n: i64 = number.parse().ok()?;
                number.clear();
                any = true;
                seconds = seconds.checked_add(n.checked_mul(unit)?)?;
            }
            _ => return None,
        }
    }
    // Longer than [MAX_DURATION_DAYS] is no duration we use (QA H1: chrono's own bound panics far below i64).
    if !number.is_empty() || !any || seconds > MAX_DURATION_DAYS * 86_400 {
        return None;
    }
    TimeDelta::try_seconds(if negative { -seconds } else { seconds })
}

/// An RRULE we pass through (§2.2): `[A-Z0-9=;,+-]{1,300}` with a `FREQ`; `UNTIL` made UTC `Z` (timed) or a date
/// (all-day) when the zone is known, kept local when floating (the phone makes it UTC in the zone it writes, QA L6).
/// With the date `UNTIL` falls on, for the horizon.
fn rrule(value: &str, zone: SeriesZone) -> Option<(String, Option<NaiveDate>)> {
    let value = value.trim().to_ascii_uppercase();
    let allowed = |b: u8| b.is_ascii_uppercase() || b.is_ascii_digit() || b"=;,+-".contains(&b);
    if value.is_empty() || value.len() > 300 || !value.bytes().all(allowed) {
        return None;
    }
    let mut until_day = None;
    let mut parts = Vec::new();
    let mut freq = false;
    for part in value.split(';').filter(|p| !p.is_empty()) {
        freq |= part.starts_with("FREQ=");
        let Some(until) = part.strip_prefix("UNTIL=") else {
            parts.push(part.to_string());
            continue;
        };
        let when = if until.len() == 8 {
            When::Date(NaiveDate::parse_from_str(until, DATE).ok()?)
        } else if let Some(utc) = until.strip_suffix('Z') {
            When::Utc(NaiveDateTime::parse_from_str(utc, DATE_TIME).ok()?)
        } else {
            When::Local(
                NaiveDateTime::parse_from_str(until, DATE_TIME).ok()?,
                None,
                false,
            )
        };
        let written = match (zone, when) {
            (SeriesZone::AllDay, _) => in_zone(when, zone, NaiveTime::MIN).wire(),
            (SeriesZone::Zoned(_), When::Utc(_)) | (SeriesZone::Floating, _) => until.to_string(),
            (SeriesZone::Zoned(tz), When::Date(d)) => {
                utc_basic(instant(tz, d.and_hms_opt(23, 59, 59)?))
            }
            (SeriesZone::Zoned(tz), When::Local(t, ..)) => utc_basic(instant(tz, t)),
        };
        until_day = Some(match when {
            When::Date(d) => d,
            When::Utc(t) | When::Local(t, ..) => t.date(),
        });
        parts.push(format!("UNTIL={written}"));
    }
    freq.then(|| (parts.join(";"), until_day))
}

fn utc_basic(at: chrono::DateTime<Tz>) -> String {
    format!("{}Z", at.naive_utc().format(DATE_TIME))
}

// ---- events ------------------------------------------------------------------------------------

#[derive(Default, Clone)]
struct RawAlarm {
    action: String,
    trigger: Option<Prop>,
}

/// Which of two copies wins: (SEQUENCE, LAST-MODIFIED, the place in the body), the higher.
type Rank = (i64, String, usize);

#[derive(Default, Clone)]
struct RawEvent {
    uid: Option<String>,
    recurrence_id: Option<Prop>,
    summary: Option<String>,
    description: Option<String>,
    location: Option<String>,
    dtstart: Option<Prop>,
    dtend: Option<Prop>,
    duration: Option<String>,
    status: Option<String>,
    sequence: i64,
    last_modified: String,
    rrule: Option<String>,
    rdates: Vec<Prop>,
    exdates: Vec<Prop>,
    alarms: Vec<RawAlarm>,
    /// Its place in the body (the later wins a tie).
    order: usize,
}

impl RawEvent {
    fn read(&mut self, name: &str, prop: Prop) {
        let first = |slot: &mut Option<String>, value: String| {
            slot.get_or_insert(value);
        };
        match name {
            "UID" => first(&mut self.uid, prop.value.trim().to_string()),
            "RECURRENCE-ID" => {
                self.recurrence_id.get_or_insert(prop);
            }
            "SUMMARY" => first(&mut self.summary, prop.value),
            "DESCRIPTION" => first(&mut self.description, prop.value),
            "LOCATION" => first(&mut self.location, prop.value),
            "DTSTART" => {
                self.dtstart.get_or_insert(prop);
            }
            "DTEND" => {
                self.dtend.get_or_insert(prop);
            }
            "DURATION" => first(&mut self.duration, prop.value),
            "STATUS" => first(&mut self.status, prop.value.trim().to_ascii_uppercase()),
            "SEQUENCE" => self.sequence = prop.value.trim().parse().unwrap_or(0),
            "LAST-MODIFIED" => self.last_modified = prop.value.trim().to_string(),
            "RRULE" => first(&mut self.rrule, prop.value),
            "RDATE" => self.rdates.push(prop),
            "EXDATE" => self.exdates.push(prop),
            _ => {}
        }
    }

    /// The UID kept (QA L4): missing → 32 hex over DTSTART, DTEND, SUMMARY and LOCATION; over 255 bytes → its hash.
    fn uid(&self) -> String {
        let given = self.uid.as_deref().map(str::trim).unwrap_or("");
        if given.is_empty() {
            let value = |p: &Option<Prop>| p.as_ref().map(|p| p.value.clone()).unwrap_or_default();
            let parts = [
                value(&self.dtstart),
                value(&self.dtend),
                self.summary.clone().unwrap_or_default(),
                self.location.clone().unwrap_or_default(),
            ];
            return hex_of(&parts.join("\u{0}"))[..32].to_string();
        }
        if given.len() > 255 {
            return hex_of(given)[..32].to_string();
        }
        given.to_string()
    }

    /// Which of two copies wins (QA L4): the higher SEQUENCE, the later LAST-MODIFIED, the later in the body.
    fn rank(&self) -> Rank {
        (self.sequence, self.last_modified.clone(), self.order)
    }

    fn status(&self) -> &'static str {
        match self.status.as_deref() {
            Some("CANCELLED") => "cancelled",
            Some("TENTATIVE") => "tentative",
            _ => "confirmed",
        }
    }

    /// The alarms (§2.2): DISPLAY or AUDIO, a duration before the start (not `RELATED=END`, not absolute), 0-10 080
    /// minutes, de-duplicated, ascending, at most [MAX_ALARMS].
    fn alarms(&self) -> Vec<i64> {
        let mut out = Vec::new();
        for alarm in &self.alarms {
            if alarm.action != "DISPLAY" && alarm.action != "AUDIO" {
                continue;
            }
            let Some(trigger) = &alarm.trigger else {
                continue;
            };
            if trigger.param_is("VALUE", "DATE-TIME") || trigger.param_is("RELATED", "END") {
                continue;
            }
            let Some(before) = parse_duration(&trigger.value) else {
                continue;
            };
            let seconds = before.num_seconds();
            let minutes = -seconds / 60;
            if seconds <= 0 && minutes <= ALARM_MAX_MINUTES && !out.contains(&minutes) {
                out.push(minutes);
            }
        }
        out.sort_unstable();
        out.truncate(MAX_ALARMS);
        out
    }
}

/// The reader's state the normalizing needs.
struct Ctx<'a> {
    vtimezones: &'a HashMap<String, String>,
    wr_timezone: Option<Tz>,
    counts: &'a mut Counts,
}

/// A master (or a standalone event) normalized.
struct Series {
    raw: RawEvent,
    zone: SeriesZone,
    time_of_day: NaiveTime,
    start: Moment,
    end: Moment,
    recurring: bool,
    until_day: Option<NaiveDate>,
    event: WireEvent,
}

/// DTEND (exclusive; `≤ DTSTART` → one day for a date, zero length for a time) or DURATION, else `fallback`.
fn end_of(
    raw: &RawEvent,
    start: Moment,
    zone: SeriesZone,
    fallback: TimeDelta,
    ctx: &mut Ctx,
) -> Moment {
    let dtend = raw
        .dtend
        .as_ref()
        .and_then(|p| parse_when(&p.value, p, ctx.vtimezones))
        .and_then(|w| converted(w, zone, NaiveTime::MIN, ctx));
    match dtend {
        Some(end) if end > start => end,
        Some(_) => start.plus(TimeDelta::zero()),
        None => match raw.duration.as_deref().map(parse_duration) {
            Some(Some(d)) => start.plus(match start {
                Moment::Day(_) => {
                    TimeDelta::try_days((d.num_seconds() + 86_399) / 86_400).unwrap_or_default()
                }
                Moment::At(_) => d,
            }),
            // A DURATION that doesn't read, or longer than MAX_DURATION_DAYS (QA H1).
            Some(None) => {
                ctx.counts.bad += 1;
                start.plus(fallback)
            }
            None => start.plus(fallback),
        },
    }
}

/// [in_zone], but `None` (and `bad`) for a UTC value in a floating series: there is no zone to convert it into, and
/// read as local it would land an offset off - an exclusion that misses, an override that detaches (QA L3).
fn converted(
    when: When,
    zone: SeriesZone,
    time_of_day: NaiveTime,
    ctx: &mut Ctx,
) -> Option<Moment> {
    if zone == SeriesZone::Floating && matches!(when, When::Utc(_)) {
        ctx.counts.bad += 1;
        return None;
    }
    Some(in_zone(when, zone, time_of_day))
}

/// RDATE or EXDATE values in the series' zone (each property's values comma-separated; `VALUE=PERIOD` dropped, `bad`).
fn dates_in(
    props: &[Prop],
    zone: SeriesZone,
    time_of_day: NaiveTime,
    ctx: &mut Ctx,
) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for prop in props {
        if prop.param_is("VALUE", "PERIOD") {
            ctx.counts.bad += 1;
            continue;
        }
        for value in prop.value.split(',').filter(|v| !v.trim().is_empty()) {
            match parse_when(value, prop, ctx.vtimezones) {
                Some(when) => {
                    if let Some(at) = converted(when, zone, time_of_day, ctx) {
                        out.push(at.wire());
                    }
                }
                None => ctx.counts.bad += 1,
            }
        }
    }
    out.sort();
    out.dedup();
    out
}

/// A master or a standalone event (§2.2-2.3); `None` without a DTSTART (`bad`).
fn series(raw: RawEvent, standalone: bool, ctx: &mut Ctx) -> Option<Series> {
    let Some(start_when) = raw
        .dtstart
        .as_ref()
        .and_then(|p| parse_when(&p.value, p, ctx.vtimezones))
    else {
        ctx.counts.bad += 1;
        return None;
    };
    let zone = match start_when {
        When::Date(_) => SeriesZone::AllDay,
        When::Utc(_) => SeriesZone::Zoned(Tz::UTC),
        When::Local(_, Some(tz), _) => SeriesZone::Zoned(tz),
        // An unknown TZID is floating, and a floating series takes the feed's X-WR-TIMEZONE when it is an IANA
        // name (§2.3's 3 then 4, QA L2); only one left to the phone's zone is counted.
        When::Local(_, None, true) => match ctx.wr_timezone {
            Some(tz) => SeriesZone::Zoned(tz),
            None => {
                ctx.counts.unknown_tz += 1;
                SeriesZone::Floating
            }
        },
        When::Local(_, None, false) => ctx
            .wr_timezone
            .map_or(SeriesZone::Floating, SeriesZone::Zoned),
    };
    let start = in_zone(start_when, zone, NaiveTime::MIN);
    let time_of_day = match start {
        Moment::At(t) => t.time(),
        Moment::Day(_) => NaiveTime::MIN,
    };
    let end = end_of(&raw, start, zone, TimeDelta::zero(), ctx);
    let mut until_day = None;
    let rule = if standalone {
        None
    } else {
        raw.rrule
            .as_deref()
            .and_then(|value| match rrule(value, zone) {
                Some((rule, until)) => {
                    until_day = until;
                    Some(rule)
                }
                None => {
                    ctx.counts.bad += 1;
                    None
                }
            })
    };
    let rdates = if standalone {
        Vec::new()
    } else {
        dates_in(&raw.rdates, zone, time_of_day, ctx)
    };
    let recurring = rule.is_some() || !rdates.is_empty();
    let exdates = if recurring {
        dates_in(&raw.exdates, zone, time_of_day, ctx)
    } else {
        Vec::new()
    };
    let event = WireEvent {
        uid: raw.uid(),
        rev: String::new(),
        summary: text(raw.summary.as_deref(), SUMMARY_MAX),
        description: text(raw.description.as_deref(), DESCRIPTION_MAX),
        location: text(raw.location.as_deref(), LOCATION_MAX),
        start: start.wire(),
        end: end.wire(),
        tz: zone.name(),
        all_day: zone == SeriesZone::AllDay,
        rrule: rule,
        rdates,
        exdates,
        status: raw.status().to_string(),
        alarms: raw.alarms(),
        overrides: Vec::new(),
    };
    Some(Series {
        raw,
        zone,
        time_of_day,
        start,
        end,
        recurring,
        until_day,
        event,
    })
}

/// An override of `master`, in its zone (QA M4); `None` when its RECURRENCE-ID doesn't read (`bad`). Fields it lacks
/// come from the master (its length too).
fn override_of(raw: &RawEvent, master: &Series, ctx: &mut Ctx) -> Option<WireOverride> {
    let recurrence = raw
        .recurrence_id
        .as_ref()
        .and_then(|p| parse_when(&p.value, p, ctx.vtimezones));
    let Some(recurrence) = recurrence else {
        ctx.counts.bad += 1;
        return None;
    };
    // A UTC recurrence id or start on a floating series can't be placed: the override is dropped (QA L3).
    let recurrence_id = converted(recurrence, master.zone, master.time_of_day, ctx)?;
    let start = match raw
        .dtstart
        .as_ref()
        .and_then(|p| parse_when(&p.value, p, ctx.vtimezones))
    {
        Some(w) => converted(w, master.zone, NaiveTime::MIN, ctx)?,
        None => recurrence_id,
    };
    let length = master.end.since(master.start);
    let end = end_of(raw, start, master.zone, length, ctx);
    let or_master = |own: &Option<String>, max: usize, theirs: &str| match own {
        Some(value) => text(Some(value.as_str()), max),
        None => theirs.to_string(),
    };
    Some(WireOverride {
        recurrence_id: recurrence_id.wire(),
        summary: or_master(&raw.summary, SUMMARY_MAX, &master.event.summary),
        description: or_master(&raw.description, DESCRIPTION_MAX, &master.event.description),
        location: or_master(&raw.location, LOCATION_MAX, &master.event.location),
        start: start.wire(),
        end: end.wire(),
        status: raw.status().to_string(),
        alarms: if raw.alarms.is_empty() {
            master.event.alarms.clone()
        } else {
            raw.alarms()
        },
    })
}

/// A series' rev: 16 hex of SHA-256 over its JSON with `rev` empty (QA N1).
fn series_rev(event: &WireEvent) -> String {
    let mut bare = event.clone();
    bare.rev = String::new();
    hex::encode(Sha256::digest(
        serde_json::to_vec(&bare).expect("an event serializes"),
    ))[..16]
        .to_string()
}

/// The feed's rev: 16 hex of SHA-256 over the events array.
pub fn feed_rev(events: &[WireEvent]) -> String {
    hex::encode(Sha256::digest(
        serde_json::to_vec(events).expect("events serialize"),
    ))[..16]
        .to_string()
}

/// Keeps `next` among `kept` by uid (QA L4): two non-recurring copies with different DTSTARTs are both kept (the
/// later as uid + `~` + 8 hex of its DTSTART); else the higher SEQUENCE, the later LAST-MODIFIED, the later in the body.
fn keep(kept: &mut Vec<Series>, by_uid: &mut HashMap<String, usize>, mut next: Series) {
    let Some(&at) = by_uid.get(&next.event.uid) else {
        by_uid.insert(next.event.uid.clone(), kept.len());
        kept.push(next);
        return;
    };
    let start_of = |s: &Series| s.raw.dtstart.as_ref().map(|p| p.value.trim().to_string());
    if !next.recurring && !kept[at].recurring && start_of(&next) != start_of(&kept[at]) {
        let variant = format!(
            "{}~{}",
            next.event.uid,
            &hex_of(&start_of(&next).unwrap_or_default())[..8]
        );
        next.event.uid = variant.clone();
        match by_uid.get(&variant) {
            None => {
                by_uid.insert(variant, kept.len());
                kept.push(next);
            }
            Some(&other) if next.raw.rank() > kept[other].raw.rank() => kept[other] = next,
            Some(_) => {}
        }
    } else if next.raw.rank() > kept[at].raw.rank() {
        kept[at] = next;
    }
}

/// Days between `today` and the series (0 for a recurring one or one today) - the caps drop the farthest first.
fn distance(s: &Series, today: NaiveDate) -> i64 {
    if s.recurring {
        return 0;
    }
    let (start, end) = (s.start.date(), s.end.date());
    if start > today {
        (start - today).num_days()
    } else if end < today {
        (today - end).num_days()
    } else {
        0
    }
}

/// A calendar link's body read into the set the phones get (§2.2-2.3), `today` the server's date (the horizon).
pub fn parse(
    body: &[u8],
    content_type: Option<&str>,
    today: NaiveDate,
) -> Result<Parsed, NotCalendar> {
    // The scheduler's panic guard is tested with this marker (`calendar_feeds::run_due`).
    #[cfg(test)]
    if body.windows(17).any(|w| w == b"X-VIBB-TEST-PANIC") {
        panic!("a test body asked the parser to panic");
    }
    let text = text_of(body, content_type);
    let mut lines = text.split('\n').map(|l| l.strip_suffix('\r').unwrap_or(l));
    let first = lines
        .by_ref()
        .find(|l| !l.trim().is_empty())
        .ok_or(NotCalendar)?;
    if !first.trim().eq_ignore_ascii_case("BEGIN:VCALENDAR") {
        return Err(NotCalendar);
    }
    let mut counts = Counts::default();
    let mut stack: Vec<String> = vec!["VCALENDAR".to_string()];
    let mut skip = 0usize;
    let (mut name, mut wr_timezone) = (None, None);
    let mut vtimezones: HashMap<String, String> = HashMap::new();
    let (mut tz_id, mut tz_location): (Option<String>, Option<String>) = (None, None);
    let mut raws: Vec<RawEvent> = Vec::new();
    let mut current: Option<RawEvent> = None;
    let mut alarm: Option<RawAlarm> = None;
    let mut vevents = 0usize;
    for line in lines {
        let Some((key, prop)) = content_line(cut_line(line)) else {
            continue;
        };
        match key.as_str() {
            "BEGIN" => {
                let component = prop.value.trim().to_ascii_uppercase();
                if skip > 0
                    || stack.len() >= MAX_DEPTH
                    || (stack.is_empty() && component != "VCALENDAR")
                {
                    skip += 1;
                    continue;
                }
                match (
                    stack.len(),
                    stack.last().map(String::as_str),
                    component.as_str(),
                ) {
                    (1, _, "VEVENT") => {
                        if vevents >= MAX_VEVENTS {
                            counts.cut += 1;
                            skip += 1;
                            continue;
                        }
                        vevents += 1;
                        current = Some(RawEvent {
                            order: vevents,
                            ..Default::default()
                        });
                    }
                    (2, Some("VEVENT"), "VALARM") => alarm = Some(RawAlarm::default()),
                    (1, _, "VTIMEZONE") => (tz_id, tz_location) = (None, None),
                    _ => {}
                }
                stack.push(component);
            }
            "END" => {
                if skip > 0 {
                    skip -= 1;
                    continue;
                }
                let Some(component) = stack.pop() else {
                    continue;
                };
                match (stack.len(), component.as_str()) {
                    (1, "VEVENT") => raws.extend(current.take()),
                    (2, "VALARM") => {
                        if let (Some(a), Some(event)) = (alarm.take(), current.as_mut()) {
                            event.alarms.push(a);
                        }
                    }
                    (1, "VTIMEZONE") => {
                        if let (Some(id), Some(location)) = (tz_id.take(), tz_location.take()) {
                            vtimezones.insert(id, location);
                        }
                    }
                    _ => {}
                }
            }
            _ if skip > 0 => {}
            _ => match (stack.len(), stack.last().map(String::as_str)) {
                (1, Some("VCALENDAR")) => match key.as_str() {
                    "X-WR-CALNAME" => name = Some(unescape(prop.value.trim())),
                    "X-WR-TIMEZONE" => wr_timezone = olson(&prop.value),
                    _ => {}
                },
                (2, Some("VTIMEZONE")) => match key.as_str() {
                    "TZID" => tz_id = Some(prop.value.trim().to_string()),
                    "X-LIC-LOCATION" => tz_location = Some(prop.value.trim().to_string()),
                    _ => {}
                },
                (2, Some("VEVENT")) => {
                    if let Some(event) = current.as_mut() {
                        event.read(&key, prop);
                    }
                }
                (3, Some("VALARM")) => {
                    if let Some(a) = alarm.as_mut() {
                        match key.as_str() {
                            "ACTION" => a.action = prop.value.trim().to_ascii_uppercase(),
                            "TRIGGER" => a.trigger = Some(prop),
                            _ => {}
                        }
                    }
                }
                _ => {}
            },
        }
    }
    let mut ctx = Ctx {
        vtimezones: &vtimezones,
        wr_timezone,
        counts: &mut counts,
    };

    // Masters first, then each override onto its series (or alone).
    let (overrides, masters): (Vec<RawEvent>, Vec<RawEvent>) =
        raws.into_iter().partition(|r| r.recurrence_id.is_some());
    let mut kept: Vec<Series> = Vec::new();
    let mut by_uid: HashMap<String, usize> = HashMap::new();
    for raw in masters {
        if let Some(s) = series(raw, false, &mut ctx) {
            keep(&mut kept, &mut by_uid, s);
        }
    }
    let mut attached: HashMap<(usize, String), (WireOverride, Rank)> = HashMap::new();
    let mut alone: Vec<RawEvent> = Vec::new();
    for raw in overrides {
        let uid = raw.uid();
        let own_date = raw
            .dtstart
            .as_ref()
            .or(raw.recurrence_id.as_ref())
            .and_then(|p| parse_when(&p.value, p, ctx.vtimezones))
            .is_some_and(When::is_date);
        let master = by_uid
            .get(&uid)
            .copied()
            .filter(|&i| kept[i].recurring && (kept[i].zone == SeriesZone::AllDay) == own_date);
        let Some(at) = master else {
            alone.push(raw);
            continue;
        };
        if let Some(o) = override_of(&raw, &kept[at], &mut ctx) {
            let key = (at, o.recurrence_id.clone());
            let rank = raw.rank();
            if attached.get(&key).is_none_or(|(_, r)| rank > *r) {
                attached.insert(key, (o, rank));
            }
        }
    }
    for raw in alone {
        let recurrence = raw
            .recurrence_id
            .as_ref()
            .map(|p| p.value.trim().to_string())
            .unwrap_or_default();
        let uid = format!("{}#{recurrence}", raw.uid());
        if let Some(mut s) = series(raw, true, &mut ctx) {
            s.event.uid = uid;
            keep(&mut kept, &mut by_uid, s);
        }
    }
    let mut attached: Vec<((usize, String), (WireOverride, _))> = attached.into_iter().collect();
    attached.sort_by(|a, b| a.0.cmp(&b.0));
    for ((at, _), (o, _)) in attached {
        kept[at].event.overrides.push(o);
    }

    // Cancelled singles and series out (a cancelled override stays, as such), then the horizon.
    let past = today - TimeDelta::days(PAST_DAYS);
    let ahead = today + TimeDelta::days(AHEAD_DAYS);
    kept.retain(|s| {
        s.event.status != "cancelled"
            && if s.recurring {
                s.until_day.is_none_or(|until| until >= past)
            } else {
                s.end.date() >= past && s.start.date() <= ahead
            }
    });

    // The caps: the farthest from today go first, a series whole.
    kept.sort_by(|a, b| {
        distance(a, today)
            .cmp(&distance(b, today))
            .then_with(|| a.event.uid.cmp(&b.event.uid))
    });
    let (mut events, mut bytes) = (0usize, 0usize);
    let mut served: Vec<WireEvent> = Vec::new();
    for s in kept {
        let mut event = s.event;
        event.rev = series_rev(&event);
        let size = serde_json::to_vec(&event).map_or(usize::MAX, |b| b.len() + 1);
        let count = 1 + event.overrides.len();
        if events + count > MAX_EVENTS || bytes + size > MAX_SERVED - 256 {
            counts.cut += count as i64;
            continue;
        }
        events += count;
        bytes += size;
        served.push(event);
    }
    served.sort_by(|a, b| a.uid.cmp(&b.uid));
    counts.events = events as i64;
    Ok(Parsed {
        name,
        events: served,
        counts,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn day(text: &str) -> NaiveDate {
        NaiveDate::parse_from_str(text, "%Y-%m-%d").unwrap()
    }

    fn fixture(name: &str) -> Vec<u8> {
        std::fs::read(format!("testdata/calendar_feeds/{name}")).unwrap()
    }

    fn read(name: &str) -> Parsed {
        parse(&fixture(name), None, day("2026-10-27")).unwrap()
    }

    fn event<'a>(parsed: &'a Parsed, uid: &str) -> &'a WireEvent {
        parsed
            .events
            .iter()
            .find(|e| e.uid == uid)
            .unwrap_or_else(|| {
                panic!(
                    "no {uid} in {:?}",
                    parsed.events.iter().map(|e| &e.uid).collect::<Vec<_>>()
                )
            })
    }

    #[test]
    fn every_windows_zone_target_parses() {
        let rows = windows_zones();
        assert!(rows.len() > 130, "{} rows", rows.len());
        for (windows, zone) in rows {
            assert!(olson(zone).is_some(), "{windows} -> {zone}");
        }
        assert_eq!(
            resolve_zone("W. Europe Standard Time", &HashMap::new()),
            Some(Tz::Europe__Berlin)
        );
    }

    #[test]
    fn folds_are_taken_out_of_the_bytes_before_decoding() {
        // "skoleåret" folded between the two bytes of "å" (0xC3 0xA5), CRLF and LF folds, a tab fold.
        let body = b"BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:a\r\nDTSTART:20261028T080000Z\r\nSUMMARY:skole\xC3\r\n \xA5ret\n\t 2026\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        let parsed = parse(body, None, day("2026-10-27")).unwrap();
        assert_eq!(parsed.events[0].summary, "skoleåret 2026");
        // Latin-1 by the Content-Type, its fold between two letters.
        let latin = b"BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:b\r\nDTSTART:20261028T080000Z\r\nSUMMARY:Bl\xE5b\r\n \xE6r\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        let parsed = parse(
            latin,
            Some("text/calendar; charset=\"ISO-8859-1\""),
            day("2026-10-27"),
        )
        .unwrap();
        assert_eq!(parsed.events[0].summary, "Blåbær");
        // A UTF-8 body read without a charset; a BOM wins over the header.
        let bom = [b"\xEF\xBB\xBF".as_slice(), &body[..]].concat();
        let parsed = parse(
            &bom,
            Some("text/calendar; charset=iso-8859-1"),
            day("2026-10-27"),
        )
        .unwrap();
        assert_eq!(parsed.events[0].summary, "skoleåret 2026");
        assert_eq!(unfold(b"a\r\n b\r\nc\n d"), b"ab\r\ncd");
    }

    #[test]
    fn content_lines_read_quoted_parameters() {
        let (name, prop) =
            content_line(r#"dtstart;TZID="W. Europe Standard Time";X-A=a,"b:c":20261027T081500"#)
                .unwrap();
        assert_eq!(name, "DTSTART");
        assert_eq!(prop.param("TZID"), Some("W. Europe Standard Time"));
        assert_eq!(prop.param("X-A"), Some("a,b:c"));
        assert_eq!(prop.value, "20261027T081500");
        assert!(content_line("no colon here").is_none());
        assert!(content_line(":value").is_none());
        assert_eq!(
            text(Some(r"Ta med\, gymtøy\; matpakke\nog \\ \Nslutt"), 200),
            "Ta med, gymtøy; matpakke\nog \\ \nslutt"
        );
        assert_eq!(text(Some("a\u{7}b\tc\r"), 200), "ab c");
        assert_eq!(parse_duration("-PT15M"), Some(TimeDelta::minutes(-15)));
        assert_eq!(
            parse_duration("P1W2DT3H"),
            Some(TimeDelta::hours(7 * 24 + 48 + 3))
        );
        assert_eq!(parse_duration("PT"), None);
        assert_eq!(parse_duration("P1H"), None);
    }

    #[test]
    fn what_isnt_a_calendar_is_refused() {
        let today = day("2026-10-27");
        assert!(parse(b"<!DOCTYPE html><html>Log in</html>", None, today).is_err());
        assert!(parse(&fixture("html.ics"), None, today).is_err());
        assert!(parse(b"\x00\x01garbage\xff", None, today).is_err());
        assert!(parse(b"", None, today).is_err());
        let empty = read("empty.ics");
        assert!(empty.events.is_empty());
        assert_eq!(empty.name.as_deref(), Some("Tom kalender"));
    }

    #[test]
    fn the_itslearning_feed() {
        let parsed = read("itslearning.ics");
        assert_eq!(parsed.name.as_deref(), Some("itslearning - 5B"));
        let trip = event(&parsed, "itsl-1001@itslearning.com");
        // The producer's own broken word stays as it is (the phone's rules handle it); "skoleåret" is whole.
        assert_eq!(
            trip.summary,
            "Gruppe 2- skoleåret 2026/2027: Litteraturhuset - Det kuleste fra R omerriket"
        );
        assert!(
            trip.description
                .starts_with("Vi deler gruppa i to og tar buss 08.56 og 09.11.")
        );
        assert!(trip.description.contains("5. trinn"));
        assert_eq!(trip.tz.as_deref(), Some("UTC"));
        assert_eq!(
            (trip.start.as_str(), trip.end.as_str()),
            ("20261015T084500", "20261015T093000")
        );
        // The weekly UTC series across 25 October: UTC on the wire, the phone moves it an hour locally.
        let gym = event(&parsed, "itsl-2002@itslearning.com");
        assert_eq!(
            gym.rrule.as_deref(),
            Some("FREQ=WEEKLY;BYDAY=MO;UNTIL=20270615T220000Z")
        );
        assert_eq!(gym.tz.as_deref(), Some("UTC"));
        assert_eq!(gym.start, "20261019T070000");
        assert_eq!(parsed.counts.unknown_tz, 0);
    }

    #[test]
    fn the_outlook_feed_maps_windows_names() {
        let parsed = read("outlook.ics");
        let meeting = event(&parsed, "outlook-1");
        assert_eq!(meeting.tz.as_deref(), Some("Europe/Berlin"));
        assert_eq!(meeting.start, "20261103T081500");
        assert_eq!(meeting.alarms, [15]);
        let romance = event(&parsed, "outlook-2");
        assert_eq!(romance.tz.as_deref(), Some("Europe/Paris"));
        // "Customized Time Zone": floating, counted.
        let custom = event(&parsed, "outlook-3");
        assert_eq!(custom.tz, None);
        assert_eq!(custom.start, "20261104T100000");
        assert_eq!(parsed.counts.unknown_tz, 1);
    }

    #[test]
    fn the_google_feed() {
        let parsed = read("google.ics");
        assert_eq!(parsed.name.as_deref(), Some("Fotball G12"));
        let training = event(&parsed, "g-training@google.com");
        assert_eq!(training.tz.as_deref(), Some("Europe/Oslo"));
        assert_eq!(training.rrule.as_deref(), Some("FREQ=WEEKLY;BYDAY=TU,TH"));
        assert_eq!(training.alarms, [30, 60]);
        // The override moved with a `Z`: converted into Oslo time (QA M4).
        assert_eq!(training.overrides.len(), 1);
        let moved = &training.overrides[0];
        assert_eq!(moved.recurrence_id, "20261103T173000");
        assert_eq!(
            (moved.start.as_str(), moved.end.as_str()),
            ("20261103T183000", "20261103T200000")
        );
        // `/mozilla.org/` stripped; X-LIC-LOCATION read.
        assert_eq!(
            event(&parsed, "g-mozilla@google.com").tz.as_deref(),
            Some("Europe/Oslo")
        );
        assert_eq!(
            event(&parsed, "g-lic@google.com").tz.as_deref(),
            Some("Europe/Oslo")
        );
    }

    #[test]
    fn the_series_cases() {
        let parsed = read("series.ics");
        // All-day weekly with EXDATE, a moved instance, a cancelled one.
        let camp = event(&parsed, "series-allday");
        assert!(camp.all_day);
        assert_eq!(camp.tz, None);
        assert_eq!(
            (camp.start.as_str(), camp.end.as_str()),
            ("20261102", "20261103")
        );
        assert_eq!(camp.rrule.as_deref(), Some("FREQ=WEEKLY;COUNT=10"));
        assert_eq!(camp.exdates, ["20261109"]);
        assert_eq!(camp.overrides.len(), 2);
        assert_eq!(camp.overrides[0].recurrence_id, "20261116");
        assert_eq!(camp.overrides[0].start, "20261117");
        assert_eq!(camp.overrides[1].status, "cancelled");
        // A timed Oslo series: EXDATE and RDATE in its zone, an override written in London converted, a local UNTIL
        // made UTC.
        let piano = event(&parsed, "series-piano");
        assert_eq!(piano.tz.as_deref(), Some("Europe/Oslo"));
        assert_eq!(
            piano.rrule.as_deref(),
            Some("FREQ=WEEKLY;UNTIL=20261222T171500Z")
        );
        assert_eq!(piano.exdates, ["20261110T181500", "20261117T181500"]);
        assert_eq!(piano.rdates, ["20261228T181500"]);
        assert_eq!(piano.overrides[0].start, "20261124T191500");
        assert_eq!(piano.alarms, [10]);
        // Floating with X-WR-TIMEZONE.
        assert_eq!(
            event(&parsed, "series-floating").tz.as_deref(),
            Some("Europe/Oslo")
        );
        // Duplicate UIDs: two singles with different starts both kept; recurring copies by SEQUENCE.
        assert_eq!(event(&parsed, "dup-single").start, "20261105T120000");
        let variant = parsed
            .events
            .iter()
            .find(|e| e.uid.starts_with("dup-single~"))
            .unwrap();
        assert_eq!(variant.start, "20261106T120000");
        assert_eq!(event(&parsed, "dup-series").summary, "Korps (ny)");
        // A missing UID and a 300-byte one get a hash; an orphan override and a THISANDFUTURE one.
        assert!(
            parsed
                .events
                .iter()
                .any(|e| e.summary == "Uten UID" && e.uid.len() == 32)
        );
        assert!(
            parsed
                .events
                .iter()
                .any(|e| e.summary == "Lang UID" && e.uid.len() == 32)
        );
        let orphan = event(&parsed, "orphan#20261201T100000Z");
        assert!(orphan.rrule.is_none());
        let future = event(&parsed, "series-future");
        assert_eq!(future.overrides[0].summary, "Svømming flyttet");
        // PERIOD RDATE dropped, an EMAIL alarm skipped, a RELATED=END one too.
        let period = event(&parsed, "series-period");
        assert!(period.rdates.is_empty());
        assert!(period.alarms.is_empty());
        assert!(parsed.counts.bad >= 1);
        // DTEND before DTSTART: zero length; a DURATION.
        assert_eq!(event(&parsed, "short").end, event(&parsed, "short").start);
        assert_eq!(event(&parsed, "with-duration").end, "20261105T100000");
    }

    #[test]
    fn cancelled_and_the_horizon() {
        let parsed = read("cancelled.ics");
        let uids: Vec<&str> = parsed.events.iter().map(|e| e.uid.as_str()).collect();
        // A cancelled single and a cancelled series (with its override) go; the series' cancelled instance stays.
        assert_eq!(uids, ["kept-series", "recent", "within"]);
        assert_eq!(
            event(&parsed, "kept-series").overrides[0].status,
            "cancelled"
        );
        assert_eq!(parsed.counts.events, 4);
    }

    #[test]
    fn the_caps() {
        let today = day("2026-10-27");
        let mut body = String::from("BEGIN:VCALENDAR\r\n");
        for i in 0..2001 {
            let at = today + TimeDelta::days(i % 300);
            body.push_str(&format!(
                "BEGIN:VEVENT\r\nUID:e{i}\r\nDTSTART:{}T080000Z\r\nSUMMARY:E {i}\r\nEND:VEVENT\r\n",
                at.format("%Y%m%d")
            ));
        }
        body.push_str("END:VCALENDAR\r\n");
        let parsed = parse(body.as_bytes(), None, today).unwrap();
        assert_eq!(parsed.events.len(), MAX_EVENTS);
        assert_eq!(parsed.counts.cut, 1);
        // The one cut is among the farthest.
        let gone: Vec<String> = (0..2001)
            .map(|i| format!("e{i}"))
            .filter(|uid| !parsed.events.iter().any(|e| &e.uid == uid))
            .collect();
        assert_eq!(gone.len(), 1);
        assert!(gone[0][1..].parse::<i64>().unwrap() % 300 == 299);
        // A 70 KB line is cut, the event kept.
        let long = format!(
            "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\nDTSTART:20261028T080000Z\r\nDESCRIPTION:{}\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n",
            "å".repeat(35_000)
        );
        let parsed = parse(long.as_bytes(), None, today).unwrap();
        assert_eq!(
            parsed.events[0].description.chars().count(),
            DESCRIPTION_MAX
        );
    }

    #[test]
    fn revs_follow_the_content() {
        let a = read("google.ics");
        let b = read("google.ics");
        assert_eq!(feed_rev(&a.events), feed_rev(&b.events));
        let training = event(&a, "g-training@google.com");
        assert_eq!(training.rev, series_rev(training));
        assert_eq!(training.rev.len(), 16);
        let mut changed = training.clone();
        changed.summary.push('!');
        assert_ne!(series_rev(&changed), training.rev);
    }

    /// QA H1: hostile values never panic; a DURATION or TRIGGER past MAX_DURATION_DAYS is `bad`, the event kept with
    /// its fallback length.
    #[test]
    fn hostile_values_never_panic() {
        let today = day("2026-10-27");
        let body = |props: &str| {
            format!(
                "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:h\r\nSUMMARY:H\r\n{props}\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
            )
        };
        let start = "DTSTART:20261105T100000Z";
        let rows = [
            format!("{start}\r\nDURATION:PT9223372036854775807S"),
            format!("{start}\r\nDURATION:PT9000000000000000S"),
            format!("{start}\r\nDURATION:P100000000000D"),
            format!("DTSTART;VALUE=DATE:20261105\r\nDURATION:P100000000000D"),
            format!("{start}\r\nDURATION:P401D"),
            format!(
                "{start}\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT9223372036854775807S\r\nEND:VALARM"
            ),
            format!(
                "{start}\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-P100000000000D\r\nEND:VALARM"
            ),
            format!("{start}\r\nDURATION:-PT1H"),
            format!("{start}\r\nDURATION:"),
            format!("{start}\r\nDURATION:P"),
            format!("{start}\r\nDURATION:PT"),
            format!("{start}\r\nRRULE:FREQ=DAILY;COUNT=99999999999999999999999"),
            format!("{start}\r\nRRULE:FREQ=DAILY;UNTIL=99991231T235959Z"),
            "DTSTART:99991231T230000Z\r\nDURATION:P400D\r\nRRULE:FREQ=YEARLY".to_string(),
            "DTSTART;VALUE=DATE:99991231\r\nDURATION:P400D\r\nRRULE:FREQ=YEARLY".to_string(),
            "DTSTART;TZID=Europe/Oslo:99991231T233000\r\nDTEND;TZID=Europe/Oslo:99991231T235959"
                .to_string(),
            "DTSTART:00000101T000000Z\r\nRRULE:FREQ=YEARLY;UNTIL=00010101".to_string(),
        ];
        for (i, props) in rows.iter().enumerate() {
            let parsed = parse(body(props).as_bytes(), None, today);
            assert!(parsed.is_ok(), "row {i}");
        }
        for props in &rows[..5] {
            let parsed = parse(body(props).as_bytes(), None, today).unwrap();
            assert!(parsed.counts.bad >= 1, "{props}");
            if let Some(e) = parsed.events.first() {
                assert!(e.end.as_str() <= "20261106", "{props}: {}", e.end);
            }
        }
        let alarm = parse(body(&rows[5]).as_bytes(), None, today).unwrap();
        assert!(alarm.events[0].alarms.is_empty());
        assert_eq!(parse_duration("PT9223372036854775807S"), None);
        assert_eq!(parse_duration("P400D"), Some(TimeDelta::days(400)));
    }

    /// QA L2, L3, L8: an unknown TZID takes X-WR-TIMEZONE; a UTC value on a floating series is dropped (`bad`), not
    /// read as local; invisible marks leave the text.
    #[test]
    fn floating_unknown_zones_and_invisible_marks() {
        let today = day("2026-10-27");
        let wr = "BEGIN:VCALENDAR\r\nX-WR-TIMEZONE:Europe/Oslo\r\nBEGIN:VEVENT\r\nUID:u\r\nSUMMARY:\u{FEFF}Mat\u{200B}te\u{202E}\r\nDTSTART;TZID=\"(UTC+01:00) Amsterdam\":20261105T100000\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        let parsed = parse(wr.as_bytes(), None, today).unwrap();
        assert_eq!(parsed.events[0].tz.as_deref(), Some("Europe/Oslo"));
        assert_eq!(parsed.events[0].start, "20261105T100000");
        assert_eq!(parsed.counts.unknown_tz, 0);
        assert_eq!(parsed.events[0].summary, "Matte");
        let floating = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:f\r\nDTSTART:20261105T100000\r\nRRULE:FREQ=WEEKLY\r\nEXDATE:20261112T090000Z\r\nEXDATE:20261119T100000\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:f\r\nRECURRENCE-ID:20261126T090000Z\r\nDTSTART:20261126T120000\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        let parsed = parse(floating.as_bytes(), None, today).unwrap();
        let series = &parsed.events[0];
        assert_eq!(series.tz, None);
        assert_eq!(series.exdates, ["20261119T100000"]);
        assert!(series.overrides.is_empty());
        assert!(parsed.counts.bad >= 2);
    }
}
