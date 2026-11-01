//! CalDAV, read-only (design 72 step 2, §6 of `docs/design/72-calendar-feeds.md` at the monorepo root; migration
//! `0110_caldav_accounts.sql`): the parent signs in to a CalDAV account with an app password on Device › Calendar,
//! Test lists its calendars, and each calendar ticked on a phone's page is a `calendar_sources` row of kind `caldav` -
//! fetched by the scheduler of [crate::calendar_feeds] and served to the phone exactly as a calendar link
//! (`calendar.feeds`, `GET /api/devices/calendar/feeds/{uid}`). The launcher is unchanged.
//!
//! - **The account** (`caldav_accounts`) is the family's: every phone's page shows it, each phone ticks its own
//!   calendars. The app password is sealed with the music key (`caldav-v1:<fingerprint>:<id>`) and never leaves the
//!   server: not to a phone, a log (no `Debug` holds it), a page or the settings log.
//! - **Discovery** ([discover]) one hop at a time, each judged as a link is (`calendar_feeds::route`: plain http only
//!   to the LAN or the tailnet, a public name never to a private address, no https → http): `/.well-known/caldav`
//!   (or the path typed), `current-user-principal`, `calendar-home-set`, then Depth 1 for the calendars (`resourcetype`
//!   calendar, `supported-calendar-component-set` with VEVENT). XML by `quick-xml` ([multistatus]). At Test the
//!   password goes to a host only once it asks for it (a 401 without it): a mail domain's web host that never asks
//!   never gets it. **The hosts that got it are the only hosts it may go to later** (`hosts`, shown on the page; a
//!   calendar on a host that never asked isn't offered): a redirect to another one is `host_changed` before any
//!   request to it. An address the server names (principal, home set, a calendar) is held to the same rules as a
//!   redirect: never the LAN for a public account, and only the server's own host and port for a LAN one.
//! - **The fetch** ([fetch_collection]): `PROPFIND` Depth 0 `getctag` + `sync-token`; unchanged (and a report less
//!   than [REPORT_EVERY] old, so events entering the window come in) → done; else one
//!   `REPORT calendar-query` with a `time-range` (−60 .. +400 days) for `calendar-data`, capped at `Kind::Calendar`'s
//!   5 MB (`too_big` keeps the set), every resource through `ics::parse` as one body. No `sync-collection`.
//! - **A refused password stops** (QA M5): a 401/403 with the password marks the account `login_refused` - no request
//!   for any of its calendars until Test passes; Test at most once per [TEST_GAP] per account, no retry after a 401.
//!
//! Scope: basic auth (iCloud, Fastmail, Nextcloud, Radicale). Out: Google (OAuth only; its secret ICS address works as
//! a link), digest auth, client certificates.

use std::collections::{BTreeSet, HashMap, HashSet};

use base64::Engine;
use chrono::{DateTime, TimeDelta, Utc};
use quick_xml::events::Event;
use quick_xml::name::ResolveResult;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::AppState;
use crate::calendar_feeds::{self, FeedError, Fetched, Route};
use crate::music_net::{Kind, Reach, Source, SourceRequest, SourceResponse};
use crate::music_sweep::stamp;

pub const DAV: &str = "DAV:";
pub const CALDAV: &str = "urn:ietf:params:xml:ns:caldav";
pub const CS: &str = "http://calendarserver.org/ns/";

/// Test at most this often per account (QA M5).
pub const TEST_GAP: TimeDelta = TimeDelta::seconds(10);
/// Accounts a server keeps (a family's).
pub const MAX_ACCOUNTS: i64 = 10;
/// Calendars an account lists at Test.
pub const MAX_CALENDARS: usize = 50;
pub const MAX_SERVER_CHARS: usize = 500;
pub const MAX_USERNAME_CHARS: usize = 200;
pub const MAX_PASSWORD_CHARS: usize = 500;
/// An account no phone has a calendar of goes this long after it was last used: its last Test, or its last calendar
/// leaving the last phone (`unused_since`, code QA L3) - one switch flipped by mistake never loses the login.
pub const UNUSED_HOURS: i64 = 24;
/// Redirects followed per request.
const MAX_HOPS: usize = 5;
/// XML nesting read.
const MAX_DEPTH: usize = 32;
/// A validator kept, in bytes.
const MAX_VALIDATOR: usize = 500;
/// The `REPORT` runs at least this often, whatever the ctag says: the window moves a day each day (QA L6).
pub const REPORT_EVERY: TimeDelta = TimeDelta::hours(24);

/// Mail domains whose CalDAV server isn't at the domain itself (design 72 §6: "an e-mail, its domain tried").
const KNOWN_DOMAINS: &[(&str, &str)] = &[
    ("icloud.com", "https://caldav.icloud.com/"),
    ("me.com", "https://caldav.icloud.com/"),
    ("mac.com", "https://caldav.icloud.com/"),
    ("fastmail.com", "https://caldav.fastmail.com/"),
    ("fastmail.fm", "https://caldav.fastmail.com/"),
];

/// A form value refused before any request: the i18n key of the reason.
pub type Refusal = &'static str;

// ---- names -------------------------------------------------------------------------------------

/// The server field (§6: "the server, or an e-mail - its domain tried"): an address as a link is normalized (https
/// unless written http, no user or password, no query or fragment), or an e-mail's domain (a known one's server).
/// Returns the address and the e-mail when one was given (the username's default).
pub fn normalize_server(raw: &str) -> Result<(String, Option<String>), Refusal> {
    let raw = raw.trim();
    if raw.is_empty() {
        return Err("calendar.caldav.error.no_server");
    }
    if raw.chars().count() > MAX_SERVER_CHARS {
        return Err("calendar.caldav.error.too_long");
    }
    let email = !raw.contains("://") && !raw.contains('/') && raw.contains('@');
    let typed = if email {
        let domain = raw
            .rsplit('@')
            .next()
            .unwrap_or_default()
            .to_ascii_lowercase();
        match KNOWN_DOMAINS.iter().find(|(d, _)| *d == domain) {
            Some((_, server)) => server.to_string(),
            None => format!("https://{domain}/"),
        }
    } else if raw.contains("://") {
        raw.to_string()
    } else {
        format!("https://{raw}")
    };
    let mut url = reqwest::Url::parse(&typed).map_err(|_| "calendar.caldav.error.no_server")?;
    if !matches!(url.scheme(), "http" | "https") || url.host_str().is_none_or(str::is_empty) {
        return Err("calendar.caldav.error.no_server");
    }
    if !url.username().is_empty() || url.password().is_some() {
        return Err("calendar.caldav.error.userinfo");
    }
    url.set_query(None);
    url.set_fragment(None);
    Ok((url.to_string(), email.then(|| raw.to_string())))
}

/// A CalDAV calendar's source uid (§2: `vibb-caldav:` + the account + the collection): 32 hex of SHA-256 over the
/// account's address, username and the collection's address - the account's identity rather than its row id, so an
/// account removed and signed in again gives each calendar back its key (its settings and rules), as a link does.
pub fn uid_of(server_url: &str, username: &str, href: &str) -> String {
    hex::encode(Sha256::digest(
        format!("vibb-caldav:{server_url}\u{0}{username}\u{0}{href}").as_bytes(),
    ))[..32]
        .to_string()
}

/// A listed calendar's id in the tick form: 16 hex of its address (never the address itself in a form).
pub fn calendar_id(href: &str) -> String {
    hex::encode(Sha256::digest(href.as_bytes()))[..16].to_string()
}

fn aad(fingerprint: &str, account_id: i64) -> Vec<u8> {
    format!("caldav-v1:{fingerprint}:{account_id}").into_bytes()
}

// ---- XML ---------------------------------------------------------------------------------------

/// One property of a `propstat` with a 200: its text, the `href`s inside it, its child elements (namespace, local
/// name) and the `comp name`s of a `supported-calendar-component-set`.
#[derive(Default, Debug, Clone)]
pub struct Prop {
    pub text: String,
    pub hrefs: Vec<String>,
    pub children: Vec<(String, String)>,
    pub comps: Vec<String>,
}

/// Properties by (namespace, local name).
type Props = HashMap<(String, String), Prop>;

/// One `response` of a multistatus: its `href` and its found properties by (namespace, local name).
#[derive(Default, Debug)]
pub struct DavResponse {
    pub href: String,
    pub props: Props,
}

impl DavResponse {
    pub fn prop(&self, ns: &str, local: &str) -> Option<&Prop> {
        self.props.get(&(ns.to_string(), local.to_string()))
    }
}

fn attribute(element: &quick_xml::events::BytesStart<'_>, name: &str) -> Option<String> {
    element.attributes().flatten().find_map(|attr| {
        (attr.key.local_name().as_ref() == name).then(|| {
            attr.normalized_value(quick_xml::XmlVersion::Implicit1_0)
                .map(|v| v.into_owned())
                .unwrap_or_else(|_| attr.value.to_string())
        })
    })
}

/// A `207 Multi-Status` body read by namespace (any prefix): every `response` with the properties of its 200
/// `propstat`s. `None` when it isn't XML (or declares its own entities, or nests past [MAX_DEPTH]).
pub fn multistatus(body: &[u8]) -> Option<Vec<DavResponse>> {
    let text = String::from_utf8_lossy(body);
    let mut reader = quick_xml::NsReader::from_str(&text);
    reader.config_mut().expand_empty_elements = true;
    let mut stack: Vec<(String, String)> = Vec::new();
    let mut out = Vec::new();
    let mut response: Option<DavResponse> = None;
    let mut propstat: Option<(Props, String)> = None;
    // The property being read: the depth of its element, its name, what it holds.
    let mut prop: Option<(usize, (String, String), Prop)> = None;
    let mut href: Option<String> = None;
    loop {
        let (resolved, event) = reader.read_resolved_event().ok()?;
        let ns = match resolved {
            ResolveResult::Bound(ns) => AsRef::<str>::as_ref(&ns).to_string(),
            _ => String::new(),
        };
        let text: Option<String> = match event {
            Event::DocType(doctype) => {
                if doctype.xml10_content().contains('[') {
                    return None;
                }
                None
            }
            Event::Start(element) => {
                if stack.len() >= MAX_DEPTH {
                    return None;
                }
                let local = AsRef::<str>::as_ref(&element.local_name()).to_string();
                let depth = stack.len();
                let dav = ns == DAV;
                if let Some((at, _, p)) = prop.as_mut() {
                    if depth == *at + 1 {
                        p.children.push((ns.clone(), local.clone()));
                    }
                    if dav && local == "href" {
                        href = Some(String::new());
                    }
                    if ns == CALDAV && local == "comp" {
                        p.comps
                            .extend(attribute(&element, "name").map(|n| n.to_ascii_uppercase()));
                    }
                } else if propstat.is_some() {
                    if stack.last().is_some_and(|(n, l)| n == DAV && l == "prop") {
                        prop = Some((depth, (ns.clone(), local.clone()), Prop::default()));
                    }
                } else if response.is_some() {
                    if dav && local == "propstat" {
                        propstat = Some((HashMap::new(), String::new()));
                    }
                } else if dav && local == "response" {
                    response = Some(DavResponse::default());
                }
                stack.push((ns, local));
                None
            }
            Event::Text(t) => Some(t.xml10_content().into_owned()),
            Event::CData(data) => Some(data.into_inner().into_owned()),
            Event::GeneralRef(reference) => {
                let name = reference.xml10_content().into_owned();
                Some(match reference.resolve_char_ref() {
                    Ok(Some(c)) => c.to_string(),
                    _ => match name.as_str() {
                        "amp" => "&".to_string(),
                        "lt" => "<".to_string(),
                        "gt" => ">".to_string(),
                        "quot" => "\"".to_string(),
                        "apos" => "'".to_string(),
                        other => format!("&{other};"),
                    },
                })
            }
            Event::End(_) => {
                let Some((ns, local)) = stack.pop() else {
                    continue;
                };
                let depth = stack.len();
                let dav = ns == DAV;
                if let Some((at, _, p)) = prop.as_mut() {
                    if dav
                        && local == "href"
                        && let Some(h) = href.take()
                    {
                        p.hrefs.push(h.trim().to_string());
                    }
                    if depth == *at {
                        let (_, name, p) = prop.take().expect("a property is open");
                        if let Some((props, _)) = propstat.as_mut() {
                            props.insert(name, p);
                        }
                    }
                } else if dav && local == "propstat" {
                    if let (Some((props, status)), Some(r)) = (propstat.take(), response.as_mut()) {
                        // `HTTP/1.1 200 OK`; a propstat without a status is read as found.
                        let code = status.split_whitespace().nth(1);
                        if code.is_none_or(|c| c == "200") {
                            r.props.extend(props);
                        }
                    }
                } else if dav && local == "response" {
                    out.extend(response.take());
                }
                None
            }
            Event::Eof => break,
            _ => None,
        };
        let Some(text) = text else {
            continue;
        };
        let at = stack.last().map(|(n, l)| (n.as_str(), l.as_str()));
        if let Some((_, _, p)) = prop.as_mut() {
            p.text.push_str(&text);
            if let Some(h) = href.as_mut() {
                h.push_str(&text);
            }
        } else if let Some((_, status)) = propstat.as_mut() {
            if at == Some((DAV, "status")) {
                status.push_str(&text);
            }
        } else if let Some(r) = response.as_mut()
            && at == Some((DAV, "href"))
        {
            r.href.push_str(&text);
        }
    }
    for r in &mut out {
        r.href = r.href.trim().to_string();
    }
    Some(out)
}

// ---- requests ----------------------------------------------------------------------------------

const PROPFIND_START: &str = r#"<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><d:current-user-principal/><c:calendar-home-set/><d:resourcetype/></d:prop></d:propfind>"#;

const PROPFIND_HOME: &str = r#"<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><c:calendar-home-set/></d:prop></d:propfind>"#;

const PROPFIND_LIST: &str = r#"<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/"><d:prop><d:resourcetype/><d:displayname/><c:supported-calendar-component-set/><cs:getctag/></d:prop></d:propfind>"#;

const PROPFIND_CTAG: &str = r#"<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/"><d:prop><cs:getctag/><d:sync-token/></d:prop></d:propfind>"#;

/// The `calendar-query` for the events from 60 days back to 400 ahead (the horizon of `ics`, decision 3).
fn report_body(now: DateTime<Utc>) -> String {
    let day = |d: i64| {
        (now.date_naive() + TimeDelta::days(d))
            .format("%Y%m%dT000000Z")
            .to_string()
    };
    format!(
        r#"<?xml version="1.0" encoding="utf-8"?>
<c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><d:getetag/><c:calendar-data/></d:prop><c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT"><c:time-range start="{}" end="{}"/></c:comp-filter></c:comp-filter></c:filter></c:calendar-query>"#,
        day(-crate::ics::PAST_DAYS),
        day(crate::ics::AHEAD_DAYS + 1)
    )
}

/// A login. No `Debug`: it holds the password.
pub struct Login<'a> {
    pub username: &'a str,
    pub password: &'a str,
}

/// Where the password may go: at Test, a host that asked for it (a 401 without it) - collected here; later, only
/// the hosts Test confirmed.
enum Hosts<'a> {
    Learn(&'a mut BTreeSet<String>),
    Fixed(&'a [String]),
}

struct Dav<'a> {
    source: &'a dyn Source,
    login: Login<'a>,
    /// The address was the LAN or the tailnet when judged; every request judged again (`calendar_feeds::route`).
    lan: bool,
    hosts: Hosts<'a>,
    /// A LAN account's server: its host and port, the only ones any request may go to (code QA M1).
    origin: Option<(String, Option<u16>)>,
    /// A LAN account's name resolved publicly: it stays public for good (the caller stores it).
    went_public: bool,
}

/// A LAN account's host and port ([Dav::origin]); `None` for a public one.
fn origin_of(server_url: &str, lan: bool) -> Option<(String, Option<u16>)> {
    let url = reqwest::Url::parse(server_url).ok().filter(|_| lan)?;
    Some((host_of(&url), url.port_or_known_default()))
}

/// A path segment's `%XX` decoded (a calendar without a display name is named by it), lossy UTF-8.
fn percent_decoded(segment: &str) -> String {
    let bytes = segment.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        let hex = bytes
            .get(i + 1..i + 3)
            .and_then(|h| std::str::from_utf8(h).ok())
            .and_then(|h| u8::from_str_radix(h, 16).ok());
        match (bytes[i], hex) {
            (b'%', Some(byte)) => {
                out.push(byte);
                i += 3;
            }
            (byte, _) => {
                out.push(byte);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn host_of(url: &reqwest::Url) -> String {
    url.host_str().unwrap_or_default().to_ascii_lowercase()
}

impl Dav<'_> {
    fn authorization(&self) -> String {
        let pair = format!("{}:{}", self.login.username, self.login.password);
        format!(
            "Basic {}",
            base64::engine::general_purpose::STANDARD.encode(pair)
        )
    }

    /// One WebDAV request, its redirects followed here one hop at a time (at most [MAX_HOPS], the same method and
    /// body): each hop judged as a link is, never https → http, a LAN address only on its own host and port, and,
    /// after Test, only to a confirmed host (`host_changed` before the request). A 401/403 with the password is
    /// `login_refused` and never retried. Returns the address that answered and the answer.
    async fn ask(
        &mut self,
        method: &'static str,
        url: &reqwest::Url,
        depth: &'static str,
        body: &str,
    ) -> Result<(reqwest::Url, SourceResponse), FeedError> {
        let mut current = url.clone();
        let mut hops = 0;
        loop {
            let host = host_of(&current);
            // Every address, the server's own hrefs too - not only a redirect's (code QA M1).
            if let Some((origin, port)) = &self.origin
                && (host != *origin || current.port_or_known_default() != *port)
            {
                return Err(FeedError::code("network"));
            }
            let with_password = match &self.hosts {
                Hosts::Learn(asked) => asked.contains(&host),
                Hosts::Fixed(hosts) => {
                    if !hosts.contains(&host) {
                        return Err(FeedError::code("host_changed"));
                    }
                    true
                }
            };
            let route = calendar_feeds::route(self.source, current.as_str(), self.lan).await?;
            // A public account never reaches the LAN: an http address the server names that resolves to it is a
            // private address (code QA M1).
            if !self.lan && matches!(route, Route::Lan(_)) {
                return Err(FeedError::code("private_address"));
            }
            if self.lan && route == Route::Public {
                self.went_public = true;
            }
            let mut request = match &route {
                Route::Public => {
                    SourceRequest::new(current.as_str(), Kind::Calendar, Reach::Public)
                }
                Route::Lan(addrs) => {
                    SourceRequest::new(current.as_str(), Kind::Calendar, Reach::Any)
                        .pinned_to(addrs)
                }
            };
            request.method =
                reqwest::Method::from_bytes(method.as_bytes()).expect("a WebDAV method");
            request.follow = false;
            request.headers = vec![
                (
                    reqwest::header::HeaderName::from_static("depth"),
                    depth.to_string(),
                ),
                (
                    reqwest::header::CONTENT_TYPE,
                    "application/xml; charset=utf-8".to_string(),
                ),
            ];
            if with_password {
                request
                    .headers
                    .push((reqwest::header::AUTHORIZATION, self.authorization()));
            }
            request.body = Some(body.as_bytes().to_vec());
            let answer = self
                .source
                .get(request)
                .await
                .map_err(calendar_feeds::fetch_error)?;
            if matches!(answer.status, 401 | 403) && with_password {
                return Err(FeedError::code("login_refused"));
            }
            if answer.status == 401
                && let Hosts::Learn(asked) = &mut self.hosts
            {
                // This host asks for the password: it gets it, once (a second 401 is a refusal).
                asked.insert(host);
                continue;
            }
            let Some(location) = answer
                .location
                .as_deref()
                .filter(|_| matches!(answer.status, 301 | 302 | 303 | 307 | 308))
            else {
                return Ok((current, answer));
            };
            hops += 1;
            if hops > MAX_HOPS {
                return Err(FeedError::code("network"));
            }
            let next = current
                .join(location)
                .map_err(|_| FeedError::code("network"))?;
            if next.scheme() == "http" && (current.scheme() == "https" || route == Route::Public) {
                return Err(FeedError::code("insecure"));
            }
            if !matches!(next.scheme(), "http" | "https") {
                return Err(FeedError::code("network"));
            }
            if matches!(route, Route::Lan(_))
                && (next.host_str() != current.host_str()
                    || next.port_or_known_default() != current.port_or_known_default())
            {
                return Err(FeedError::code("network"));
            }
            current = next;
        }
    }
}

/// A non-207 answer as an error: 404/405/410 → `missing` (the caller's code), else `status`.
fn not_multistatus(status: u16, missing: &'static str) -> FeedError {
    match status {
        404 | 405 | 410 => FeedError::code(missing),
        other => FeedError {
            code: "status",
            status: Some(other),
        },
    }
}

// ---- discovery ---------------------------------------------------------------------------------

/// A calendar Test listed (`caldav_accounts.calendars_json`).
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub struct Found {
    /// [calendar_id] of the address: what the tick form posts.
    pub id: String,
    /// The collection's address, absolute.
    pub href: String,
    pub name: String,
}

/// What a passing Test found.
#[derive(Debug)]
pub struct Discovery {
    pub principal: String,
    pub home_set: String,
    pub calendars: Vec<Found>,
    /// The hosts the password may go to: those that asked for it.
    pub hosts: Vec<String>,
    pub went_public: bool,
}

/// Test (§6): the account's calendars, discovered one hop at a time - `/.well-known/caldav` and then the address
/// itself when only a host was given (else the address typed), `current-user-principal` (or `calendar-home-set`
/// there already), `calendar-home-set`, Depth 1 for the calendars with VEVENT. Errors: `login_refused`,
/// `not_caldav` (nothing CalDAV at the address), `no_calendars`, and a link's (`network`, `status`, ...).
pub async fn discover(
    source: &dyn Source,
    server_url: &str,
    lan: bool,
    login: Login<'_>,
) -> Result<Discovery, FeedError> {
    let url = reqwest::Url::parse(server_url).map_err(|_| FeedError::code("not_caldav"))?;
    let contexts: Vec<reqwest::Url> = if url.path().is_empty() || url.path() == "/" {
        let known = url
            .join("/.well-known/caldav")
            .map_err(|_| FeedError::code("not_caldav"))?;
        vec![known, url.clone()]
    } else {
        vec![url.clone()]
    };
    let mut asked = BTreeSet::new();
    let mut dav = Dav {
        source,
        login,
        lan,
        hosts: Hosts::Learn(&mut asked),
        origin: origin_of(server_url, lan),
        went_public: false,
    };
    let mut last = FeedError::code("not_caldav");
    let mut start: Option<(reqwest::Url, DavResponse)> = None;
    for context in &contexts {
        match dav.ask("PROPFIND", context, "0", PROPFIND_START).await {
            Ok((at, answer)) if answer.status == 207 => {
                let found = multistatus(&answer.body)
                    .unwrap_or_default()
                    .into_iter()
                    .find(|r| {
                        r.prop(DAV, "current-user-principal")
                            .is_some_and(|p| !p.hrefs.is_empty())
                            || r.prop(CALDAV, "calendar-home-set")
                                .is_some_and(|p| !p.hrefs.is_empty())
                    });
                if let Some(found) = found {
                    start = Some((at, found));
                    break;
                }
            }
            Ok((_, answer)) => last = not_multistatus(answer.status, "not_caldav"),
            // No retry after a refused password (QA M5).
            Err(err) if err.code == "login_refused" => return Err(err),
            Err(err) => last = err,
        }
    }
    let (at, first) = start.ok_or(last)?;
    let join = |base: &reqwest::Url, href: &str| {
        base.join(href).map_err(|_| FeedError::code("not_caldav"))
    };
    let principal = match first
        .prop(DAV, "current-user-principal")
        .and_then(|p| p.hrefs.first())
    {
        Some(href) => join(&at, href)?,
        None => at.clone(),
    };
    let home = match first
        .prop(CALDAV, "calendar-home-set")
        .and_then(|p| p.hrefs.first())
    {
        Some(href) => join(&at, href)?,
        None => {
            let (at, answer) = dav.ask("PROPFIND", &principal, "0", PROPFIND_HOME).await?;
            if answer.status != 207 {
                return Err(not_multistatus(answer.status, "not_caldav"));
            }
            let href = multistatus(&answer.body)
                .unwrap_or_default()
                .into_iter()
                .find_map(|r| {
                    r.prop(CALDAV, "calendar-home-set")
                        .and_then(|p| p.hrefs.first().cloned())
                })
                .ok_or(FeedError::code("not_caldav"))?;
            join(&at, &href)?
        }
    };
    let (at, answer) = dav.ask("PROPFIND", &home, "1", PROPFIND_LIST).await?;
    if answer.status != 207 {
        return Err(not_multistatus(answer.status, "not_caldav"));
    }
    if answer.truncated {
        return Err(FeedError::code("too_big"));
    }
    let listed = multistatus(&answer.body).ok_or(FeedError::code("not_caldav"))?;
    let mut calendars: Vec<Found> = Vec::new();
    let mut seen = HashSet::new();
    for r in &listed {
        let calendar = r.prop(DAV, "resourcetype").is_some_and(|p| {
            p.children
                .iter()
                .any(|(n, l)| n == CALDAV && l == "calendar")
        });
        let components_ok = r
            .prop(CALDAV, "supported-calendar-component-set")
            .is_none_or(|p| p.comps.is_empty() || p.comps.iter().any(|c| c == "VEVENT"));
        if !calendar || !components_ok || r.href.is_empty() {
            continue;
        }
        let Ok(href) = at.join(&r.href) else {
            continue;
        };
        if !matches!(href.scheme(), "http" | "https") || !seen.insert(href.to_string()) {
            continue;
        }
        let shown = r
            .prop(DAV, "displayname")
            .map(|p| crate::calendar::clean(&p.text, calendar_feeds::NAME_MAX))
            .filter(|n| !n.is_empty());
        let segment = href
            .path_segments()
            .and_then(|mut s| s.rfind(|s| !s.is_empty()))
            .map(|s| crate::calendar::clean(&percent_decoded(s), calendar_feeds::NAME_MAX))
            .filter(|n| !n.is_empty());
        let name = shown.or(segment).unwrap_or_else(|| host_of(&href));
        calendars.push(Found {
            id: calendar_id(href.as_str()),
            href: href.to_string(),
            name,
        });
        if calendars.len() >= MAX_CALENDARS {
            break;
        }
    }
    let went_public = dav.went_public;
    // The password goes only to a host that asked for it: a calendar elsewhere isn't offered (code QA M1).
    calendars
        .retain(|c| reqwest::Url::parse(&c.href).is_ok_and(|url| asked.contains(&host_of(&url))));
    if calendars.is_empty() {
        return Err(FeedError::code("no_calendars"));
    }
    Ok(Discovery {
        principal: principal.to_string(),
        home_set: home.to_string(),
        calendars,
        hosts: asked.into_iter().collect(),
        went_public,
    })
}

// ---- the fetch ---------------------------------------------------------------------------------

/// One calendar's fetch (§6): `PROPFIND` Depth 0 for `getctag` and `sync-token` (the validator, `<ctag>|<token>`);
/// the stored one and a report less than [REPORT_EVERY] old (`last_report`) → [Fetched::NotModified]; else
/// `REPORT calendar-query` over the horizon (its time returned as `last_modified`), every resource's
/// `calendar-data` read by `ics::parse` as one body (a resource that isn't a whole VCALENDAR counted `bad`). Only to
/// `hosts`. Returns the fetch and whether a LAN account's address now resolved publicly.
#[allow(clippy::too_many_arguments)]
pub async fn fetch_collection(
    source: &dyn Source,
    server_url: &str,
    url: &str,
    lan: bool,
    login: Login<'_>,
    hosts: &[String],
    (validator, last_report): (Option<&str>, Option<&str>),
    now: DateTime<Utc>,
) -> (Result<Fetched, FeedError>, bool) {
    let mut dav = Dav {
        source,
        login,
        lan,
        hosts: Hosts::Fixed(hosts),
        origin: origin_of(server_url, lan),
        went_public: false,
    };
    let result = fetch_with(&mut dav, url, (validator, last_report), now).await;
    (result, dav.went_public)
}

async fn fetch_with(
    dav: &mut Dav<'_>,
    url: &str,
    (validator, last_report): (Option<&str>, Option<&str>),
    now: DateTime<Utc>,
) -> Result<Fetched, FeedError> {
    let url = reqwest::Url::parse(url).map_err(|_| FeedError::code("network"))?;
    let (_, answer) = dav.ask("PROPFIND", &url, "0", PROPFIND_CTAG).await?;
    if answer.status != 207 {
        return Err(not_multistatus(answer.status, "not_found"));
    }
    let tags = multistatus(&answer.body).ok_or(FeedError::code("not_caldav"))?;
    let tag = tags.first().and_then(|r| {
        let ctag = r.prop(CS, "getctag").map(|p| p.text.trim()).unwrap_or("");
        let token = r
            .prop(DAV, "sync-token")
            .map(|p| p.text.trim())
            .unwrap_or("");
        (!ctag.is_empty() || !token.is_empty())
            .then(|| format!("{ctag}|{token}"))
            .filter(|t| t.len() <= MAX_VALIDATOR)
    });
    let reported_lately = last_report
        .and_then(crate::music_sweep::parse_stamp)
        .is_some_and(|at| now - at < REPORT_EVERY);
    if tag.is_some() && tag.as_deref() == validator && reported_lately {
        return Ok(Fetched::NotModified);
    }
    let (_, answer) = dav.ask("REPORT", &url, "1", &report_body(now)).await?;
    if answer.status != 207 {
        return Err(not_multistatus(answer.status, "not_found"));
    }
    if answer.truncated {
        return Err(FeedError::code("too_big"));
    }
    let resources = multistatus(&answer.body).ok_or(FeedError::code("not_caldav"))?;
    let mut body = String::new();
    let mut skipped = 0i64;
    for r in &resources {
        let Some(data) = r.prop(CALDAV, "calendar-data") else {
            continue;
        };
        let data = data.text.trim().trim_start_matches('\u{feff}').trim();
        let upper = |s: &str| s.to_ascii_uppercase();
        let whole = upper(data.get(..15).unwrap_or("")) == "BEGIN:VCALENDAR"
            && data.len() >= 13
            && upper(data.get(data.len() - 13..).unwrap_or("")) == "END:VCALENDAR";
        if whole {
            body.push_str(data);
            body.push_str("\r\n");
        } else {
            skipped += 1;
        }
    }
    if body.is_empty() {
        body.push_str("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n");
    }
    // A parser over untrusted bytes: a panic in it is this calendar's failure (as a link's, QA H1).
    let read = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        crate::ics::parse(body.as_bytes(), None, now.date_naive())
    }));
    let mut parsed = match read {
        Ok(Ok(parsed)) => parsed,
        Ok(Err(_)) => return Err(FeedError::code("not_calendar")),
        Err(_) => {
            tracing::error!("a CalDAV calendar's body made the parser panic");
            return Err(FeedError::code("unreadable"));
        }
    };
    parsed.counts.bad += skipped;
    Ok(Fetched::Body {
        parsed,
        etag: tag,
        last_modified: Some(stamp(now)),
    })
}

// ---- the stored account ------------------------------------------------------------------------

/// The account as a fetch needs it. No `Debug`: it holds the sealed password.
#[derive(sqlx::FromRow)]
struct AccountRow {
    server_url: String,
    username: String,
    password_sealed: Vec<u8>,
    password_nonce: Vec<u8>,
    password_key_fp: String,
    hosts: String,
    calendars_json: String,
    lan: bool,
    state: String,
}

async fn account(state: &AppState, id: i64) -> Result<Option<AccountRow>, sqlx::Error> {
    sqlx::query_as(
        "SELECT server_url, username, password_sealed, password_nonce, password_key_fp, hosts, calendars_json, \
         lan, state FROM caldav_accounts WHERE id = ?",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await
}

fn open_password(state: &AppState, id: i64, row: &AccountRow) -> Option<String> {
    let key = state.music_key.as_ref()?;
    key.open_with(
        &aad(&row.password_key_fp, id),
        &row.password_sealed,
        &row.password_nonce,
        &row.password_key_fp,
    )
    .and_then(|p| String::from_utf8(p).ok())
    .filter(|p| !p.is_empty())
}

/// A `caldav` source's fetch for `calendar_feeds::refresh`: no request while its account is stopped (`login_refused`,
/// `host_changed` - "Test again") or its password can't be opened (`key_lost`); a refused password or another host
/// stops the account for all its calendars (QA M5). A calendar the account's last Test no longer listed is
/// `not_listed`, without a request - that one alone (code QA M2). `validators`: the stored ctag and the last report's
/// time.
pub async fn refresh_source(
    state: &AppState,
    account_id: Option<i64>,
    url: &str,
    validators: (Option<&str>, Option<&str>),
    now: DateTime<Utc>,
) -> Result<Result<Fetched, FeedError>, sqlx::Error> {
    let Some(id) = account_id else {
        return Ok(Err(FeedError::code("not_caldav")));
    };
    let Some(row) = account(state, id).await? else {
        return Ok(Err(FeedError::code("not_caldav")));
    };
    match row.state.as_str() {
        "ok" => {}
        "host_changed" => return Ok(Err(FeedError::code("host_changed"))),
        _ => return Ok(Err(FeedError::code("login_refused"))),
    }
    let listed: Vec<Found> = serde_json::from_str(&row.calendars_json).unwrap_or_default();
    if !listed.iter().any(|c| c.href == url) {
        return Ok(Err(FeedError::code("not_listed")));
    }
    let Some(password) = open_password(state, id, &row) else {
        return Ok(Err(FeedError::code("key_lost")));
    };
    let hosts: Vec<String> = serde_json::from_str(&row.hosts).unwrap_or_default();
    let login = Login {
        username: &row.username,
        password: &password,
    };
    let (result, went_public) = fetch_collection(
        state.music_fetch.as_ref(),
        &row.server_url,
        url,
        row.lan,
        login,
        &hosts,
        validators,
        now,
    )
    .await;
    if let Err(err) = &result
        && matches!(err.code, "login_refused" | "host_changed")
    {
        // Only while the password is the one this fetch used: a Test that saved a new one meanwhile wins (code QA
        // L4).
        sqlx::query(
            "UPDATE caldav_accounts SET state = ? WHERE id = ? AND state = 'ok' AND password_nonce = ?",
        )
        .bind(err.code)
        .bind(id)
        .bind(&row.password_nonce)
        .execute(&state.db)
        .await?;
        tracing::warn!(
            account_id = id,
            code = err.code,
            "a CalDAV account stopped until it is tested again"
        );
    }
    if went_public {
        sqlx::query("UPDATE caldav_accounts SET lan = 0 WHERE id = ?")
            .bind(id)
            .execute(&state.db)
            .await?;
    }
    Ok(result)
}

// ---- Test --------------------------------------------------------------------------------------

/// Why a Test didn't pass.
#[derive(Debug)]
pub enum TestError {
    /// Refused before any request: an i18n key.
    Refused(Refusal),
    /// Tested less than [TEST_GAP] ago.
    Wait,
    /// The Test ran and failed (stored as the account's `test_error`).
    Failed(FeedError),
    Db(sqlx::Error),
}

impl From<sqlx::Error> for TestError {
    fn from(err: sqlx::Error) -> TestError {
        TestError::Db(err)
    }
}

fn typed_password(raw: &str) -> Result<&str, Refusal> {
    let password = raw.trim();
    if password.chars().count() > MAX_PASSWORD_CHARS {
        return Err("calendar.caldav.error.too_long");
    }
    Ok(password)
}

/// Claims the account's Test (one per [TEST_GAP]): two taps at once test once.
async fn claim(state: &AppState, id: i64, now: DateTime<Utc>) -> Result<bool, sqlx::Error> {
    let done = sqlx::query(
        "UPDATE caldav_accounts SET tested_at = ? WHERE id = ? \
         AND (tested_at IS NULL OR tested_at <= ?)",
    )
    .bind(stamp(now))
    .bind(id)
    .bind(stamp(now - TEST_GAP))
    .execute(&state.db)
    .await?;
    Ok(done.rows_affected() > 0)
}

/// Test of an account typed in the form (§6): the server (or an e-mail), the username (empty: the e-mail) and the
/// app password. The same server and username again is that account, its password replaced once the Test passes
/// (a failed one keeps the working password, code QA L5). Returns the account's id; a failed Test is stored on it
/// too.
pub async fn test_new(
    state: &AppState,
    raw_server: &str,
    raw_username: &str,
    raw_password: &str,
    now: DateTime<Utc>,
) -> Result<i64, TestError> {
    if state.music_key.is_none() {
        return Err(TestError::Refused("calendar.caldav.error.no_key"));
    }
    let (server_url, email) = normalize_server(raw_server).map_err(TestError::Refused)?;
    let username = match raw_username.trim() {
        "" => email.ok_or(TestError::Refused("calendar.caldav.error.no_username"))?,
        typed => typed.to_string(),
    };
    if username.chars().count() > MAX_USERNAME_CHARS {
        return Err(TestError::Refused("calendar.caldav.error.too_long"));
    }
    let password = typed_password(raw_password).map_err(TestError::Refused)?;
    if password.is_empty() {
        return Err(TestError::Refused("calendar.caldav.error.no_password"));
    }
    let existing: Option<i64> =
        sqlx::query_scalar("SELECT id FROM caldav_accounts WHERE server_url = ? AND username = ?")
            .bind(&server_url)
            .bind(&username)
            .fetch_optional(&state.db)
            .await?;
    let id = match existing {
        Some(id) => id,
        None => {
            // The accounts the page lists (a first Test that never passed is hidden and swept), as `can_add_caldav`
            // counts them (code QA L8).
            let count: i64 = sqlx::query_scalar(
                "SELECT COUNT(*) FROM caldav_accounts WHERE principal IS NOT NULL",
            )
            .fetch_one(&state.db)
            .await?;
            if count >= MAX_ACCOUNTS {
                return Err(TestError::Refused("calendar.caldav.error.too_many"));
            }
            sqlx::query_scalar(
                "INSERT INTO caldav_accounts (server_url, username, password_sealed, password_nonce, \
                 password_key_fp) VALUES (?, ?, X'', X'', '') \
                 ON CONFLICT(server_url, username) DO UPDATE SET username = excluded.username RETURNING id",
            )
            .bind(&server_url)
            .bind(&username)
            .fetch_one(&state.db)
            .await?
        }
    };
    if !claim(state, id, now).await? {
        return Err(TestError::Wait);
    }
    run_test(state, id, &server_url, &username, password, true, now).await?;
    Ok(id)
}

/// "Test again" on an account: with a new password when one is typed (stored once it passes), else the stored one
/// (a lost key or a first Test that failed has none: type it again).
pub async fn test_again(
    state: &AppState,
    id: i64,
    raw_password: &str,
    now: DateTime<Utc>,
) -> Result<(), TestError> {
    let found: Option<(String, String)> =
        sqlx::query_as("SELECT server_url, username FROM caldav_accounts WHERE id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await?;
    let Some((server_url, username)) = found else {
        return Err(TestError::Refused("calendar.caldav.error.gone"));
    };
    let typed = typed_password(raw_password).map_err(TestError::Refused)?;
    let password = if typed.is_empty() {
        let row = account(state, id).await?;
        row.and_then(|row| open_password(state, id, &row))
            .ok_or(TestError::Refused("calendar.caldav.error.retype"))?
    } else {
        typed.to_string()
    };
    if !claim(state, id, now).await? {
        return Err(TestError::Wait);
    }
    let new_password = !typed.is_empty();
    run_test(
        state,
        id,
        &server_url,
        &username,
        &password,
        new_password,
        now,
    )
    .await
}

/// One Test, stored: a pass replaces the hosts and the calendars, stores a `new_password`, sets the account `ok`,
/// seals its calendars' addresses again under today's key, marks a ticked calendar no longer listed `not_listed`
/// (code QA M2) and lets every other failing calendar of it be fetched at once; a failure is the account's
/// `test_error` (the stored password refused stops it; a new one that fails is never kept, code QA L5).
async fn run_test(
    state: &AppState,
    id: i64,
    server_url: &str,
    username: &str,
    password: &str,
    new_password: bool,
    now: DateTime<Utc>,
) -> Result<(), TestError> {
    let Some(key) = state.music_key.clone() else {
        return Err(TestError::Refused("calendar.caldav.error.no_key"));
    };
    let source = state.music_fetch.as_ref();
    // Judged as a pasted link (§5): plain http only where the name resolves to the LAN or the tailnet.
    let judged = crate::music_sweep::lan_addresses(source, server_url)
        .await
        .map_err(calendar_feeds::fetch_error);
    let http = server_url.starts_with("http://");
    let found = match judged {
        Ok(None) if http => Err(FeedError::code("insecure")),
        Ok(addrs) => {
            let lan = addrs.is_some();
            let lan_http = http
                && addrs
                    .as_ref()
                    .is_some_and(|a| !a.iter().all(|ip| calendar_feeds::tailscale(*ip)));
            let login = Login { username, password };
            discover(source, server_url, lan, login)
                .await
                .map(|d| (d, lan, lan_http))
        }
        Err(err) => Err(err),
    };
    let (discovery, lan, lan_http) = match found {
        Ok(found) => found,
        Err(err) => {
            let stops = err.code == "login_refused" && !new_password;
            sqlx::query(
                "UPDATE caldav_accounts SET test_error = ?, test_status = ?, \
                 state = CASE WHEN ? AND state <> 'untested' THEN 'login_refused' ELSE state END \
                 WHERE id = ?",
            )
            .bind(err.code)
            .bind(err.status.map(i64::from))
            .bind(stops)
            .bind(id)
            .execute(&state.db)
            .await?;
            tracing::info!(account_id = id, code = err.code, "a CalDAV Test failed");
            return Err(TestError::Failed(err));
        }
    };
    let mut tx = state.db.begin().await?;
    sqlx::query(
        "UPDATE caldav_accounts SET hosts = ?, principal = ?, home_set = ?, calendars_json = ?, lan = ?, \
         lan_http = ?, state = 'ok', test_error = NULL, test_status = NULL, ok_at = ? WHERE id = ?",
    )
    .bind(serde_json::to_string(&discovery.hosts).expect("hosts serialize"))
    .bind(&discovery.principal)
    .bind(&discovery.home_set)
    .bind(serde_json::to_string(&discovery.calendars).expect("calendars serialize"))
    .bind(lan && !discovery.went_public)
    .bind(lan_http)
    .bind(stamp(now))
    .bind(id)
    .execute(&mut *tx)
    .await?;
    if new_password {
        let (sealed, nonce) = key.seal_with(&aad(&key.fingerprint, id), password.as_bytes());
        sqlx::query(
            "UPDATE caldav_accounts SET password_sealed = ?, password_nonce = ?, password_key_fp = ? WHERE id = ?",
        )
        .bind(sealed)
        .bind(nonce)
        .bind(&key.fingerprint)
        .bind(id)
        .execute(&mut *tx)
        .await?;
    }
    // The calendars ticked before: one no longer listed is that calendar's own error (never the account's); the
    // others sealed again under today's key (a key made anew since), the address kept.
    let sources: Vec<(i64, String, String)> =
        sqlx::query_as("SELECT id, uid, url_key_fp FROM calendar_sources WHERE account_id = ?")
            .bind(id)
            .fetch_all(&mut *tx)
            .await?;
    for (source_id, uid, sealed_with) in sources {
        let Some(found) = discovery
            .calendars
            .iter()
            .find(|c| uid_of(server_url, username, &c.href) == uid)
        else {
            sqlx::query(
                "UPDATE calendar_sources SET error = 'not_listed', error_status = NULL WHERE id = ?",
            )
            .bind(source_id)
            .execute(&mut *tx)
            .await?;
            continue;
        };
        if sealed_with == key.fingerprint {
            continue;
        }
        let (sealed, nonce) = key.seal_with(
            &calendar_feeds::aad(&key.fingerprint, &uid),
            found.href.as_bytes(),
        );
        sqlx::query(
            "UPDATE calendar_sources SET url_sealed = ?, url_nonce = ?, url_key_fp = ? WHERE id = ?",
        )
        .bind(sealed)
        .bind(nonce)
        .bind(&key.fingerprint)
        .bind(source_id)
        .execute(&mut *tx)
        .await?;
    }
    sqlx::query(
        "UPDATE calendar_sources SET next_at = ?, failures = 0 WHERE account_id = ? AND error IS NOT NULL \
         AND error <> 'not_listed'",
    )
    .bind(stamp(now))
    .bind(id)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    tracing::info!(
        account_id = id,
        calendars = discovery.calendars.len(),
        "a CalDAV account passed its Test"
    );
    Ok(())
}

// ---- a phone's calendars -----------------------------------------------------------------------

/// Why the ticks weren't saved.
#[derive(Debug)]
pub enum TickError {
    /// An i18n key (no music key, too many calendars).
    Refused(Refusal),
    /// The account is gone (removed meanwhile).
    Gone,
    Db(sqlx::Error),
}

impl From<sqlx::Error> for TickError {
    fn from(err: sqlx::Error) -> TickError {
        TickError::Db(err)
    }
}

/// The ticks of one account's calendars on a phone (the form sends every listed calendar's id in `listed` and the
/// ticked ones in `ticked`; ids it doesn't know are ignored): a ticked calendar becomes a source (shared with a
/// sibling that has it) and this phone's; an unticked one goes off this phone - after the ticks, so swapping one for
/// another never empties the account (whose last calendar takes it away). Returns the sources made, for the caller
/// to fetch at once. Caps: [calendar_feeds::MAX_LINKS_PER_PHONE] with the links, [calendar_feeds::MAX_SOURCES].
pub async fn save_ticks(
    state: &AppState,
    device_id: i64,
    account_id: i64,
    listed: &[String],
    ticked: &HashSet<String>,
    now: DateTime<Utc>,
) -> Result<Vec<i64>, TickError> {
    let found: Option<(String, String, String, bool, bool)> = sqlx::query_as(
        "SELECT server_url, username, calendars_json, lan, lan_http FROM caldav_accounts \
         WHERE id = ? AND principal IS NOT NULL",
    )
    .bind(account_id)
    .fetch_optional(&state.db)
    .await?;
    let Some((server_url, username, calendars_json, lan, lan_http)) = found else {
        return Err(TickError::Gone);
    };
    let Some(key) = state.music_key.clone() else {
        return Err(TickError::Refused("calendar.caldav.error.no_key"));
    };
    let calendars: Vec<Found> = serde_json::from_str(&calendars_json).unwrap_or_default();
    let mine: HashSet<String> = sqlx::query_scalar(
        "SELECT s.uid FROM device_calendar_feeds d JOIN calendar_sources s ON s.id = d.source_id \
         WHERE d.device_id = ?",
    )
    .bind(device_id)
    .fetch_all(&state.db)
    .await?
    .into_iter()
    .collect();
    let chosen: Vec<(&Found, String, bool)> = calendars
        .iter()
        .filter(|c| listed.contains(&c.id))
        .map(|c| {
            (
                c,
                uid_of(&server_url, &username, &c.href),
                ticked.contains(&c.id),
            )
        })
        .collect();
    let adding = chosen
        .iter()
        .filter(|(_, uid, on)| *on && !mine.contains(uid))
        .count();
    let removing = chosen
        .iter()
        .filter(|(_, uid, on)| !*on && mine.contains(uid))
        .count();
    if adding > 0 && (mine.len() + adding - removing) as i64 > calendar_feeds::MAX_LINKS_PER_PHONE {
        return Err(TickError::Refused(
            "calendar.caldav.error.too_many_calendars",
        ));
    }
    // The server's cap before any write (code QA L7): a refusal mid-way would leave ticks saved but unfetched.
    let mut new_sources = 0i64;
    for (_, uid, _) in chosen
        .iter()
        .filter(|(_, uid, on)| *on && !mine.contains(uid))
    {
        let exists: Option<i64> =
            sqlx::query_scalar("SELECT id FROM calendar_sources WHERE uid = ?")
                .bind(uid)
                .fetch_optional(&state.db)
                .await?;
        new_sources += i64::from(exists.is_none());
    }
    let sources: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM calendar_sources")
        .fetch_one(&state.db)
        .await?;
    if new_sources > 0 && sources + new_sources > calendar_feeds::MAX_SOURCES {
        return Err(TickError::Refused("calendar.links.error.too_many_sources"));
    }
    let mut made = Vec::new();
    for (c, uid, _) in chosen
        .iter()
        .filter(|(_, uid, on)| *on && !mine.contains(uid))
    {
        let mut tx = state.db.begin().await?;
        let existing: Option<i64> =
            sqlx::query_scalar("SELECT id FROM calendar_sources WHERE uid = ?")
                .bind(uid)
                .fetch_optional(&mut *tx)
                .await?;
        let source_id = match existing {
            Some(id) => id,
            None => {
                let host = reqwest::Url::parse(&c.href)
                    .map(|u| host_of(&u))
                    .unwrap_or_default();
                let (rev, json) = calendar_feeds::document(uid, Vec::new());
                let (sealed, nonce) = key.seal_with(
                    &calendar_feeds::aad(&key.fingerprint, uid),
                    c.href.as_bytes(),
                );
                let id: i64 = sqlx::query_scalar(
                    "INSERT INTO calendar_sources (uid, kind, account_id, url_sealed, url_nonce, url_key_fp, host, \
                     lan, lan_http, rev, feed_json, feed_name, next_at) \
                     VALUES (?, 'caldav', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                )
                .bind(uid)
                .bind(account_id)
                .bind(sealed)
                .bind(nonce)
                .bind(&key.fingerprint)
                .bind(&host)
                .bind(lan)
                .bind(lan_http)
                .bind(&rev)
                .bind(&json)
                .bind(&c.name)
                .bind(stamp(now))
                .fetch_one(&mut *tx)
                .await?;
                made.push(id);
                id
            }
        };
        sqlx::query(
            "INSERT INTO device_calendar_feeds (device_id, source_id, name) VALUES (?, ?, ?) \
             ON CONFLICT(device_id, source_id) DO NOTHING",
        )
        .bind(device_id)
        .bind(source_id)
        .bind(&c.name)
        .execute(&mut *tx)
        .await?;
        tx.commit().await?;
    }
    for (_, uid, _) in chosen
        .iter()
        .filter(|(_, uid, on)| !*on && mine.contains(uid))
    {
        sqlx::query(
            "DELETE FROM device_calendar_feeds WHERE device_id = ? \
             AND source_id = (SELECT id FROM calendar_sources WHERE uid = ?)",
        )
        .bind(device_id)
        .bind(uid)
        .execute(&state.db)
        .await?;
    }
    tracing::info!(
        device_id,
        account_id,
        added = adding,
        removed = removing,
        "a phone's CalDAV calendars were saved"
    );
    Ok(made)
}

/// Removes an account: its calendars go off every phone (the cascade), its password with it. Returns the phones that
/// had one of them, to nudge.
pub async fn remove(state: &AppState, account_id: i64) -> Result<Vec<i64>, sqlx::Error> {
    let phones: Vec<i64> = sqlx::query_scalar(
        "SELECT DISTINCT d.device_id FROM device_calendar_feeds d \
         JOIN calendar_sources s ON s.id = d.source_id WHERE s.account_id = ?",
    )
    .bind(account_id)
    .fetch_all(&state.db)
    .await?;
    sqlx::query("DELETE FROM caldav_accounts WHERE id = ?")
        .bind(account_id)
        .execute(&state.db)
        .await?;
    tracing::info!(account_id, "a CalDAV account was removed");
    Ok(phones)
}

/// An account no phone has a calendar of, [UNUSED_HOURS] after it was last used - its last Test (a Test never
/// ticked, a first Test that failed) and its last calendar going (`unused_since`, stamped by the migration's
/// trigger) -, goes with its password. Run with the scheduler's pass.
pub async fn sweep(db: &sqlx::SqlitePool, now: DateTime<Utc>) -> Result<u64, sqlx::Error> {
    let cut = stamp(now - TimeDelta::hours(UNUSED_HOURS));
    let done = sqlx::query(
        "DELETE FROM caldav_accounts WHERE (tested_at IS NULL OR tested_at <= ?) \
         AND (unused_since IS NULL OR unused_since <= ?) \
         AND NOT EXISTS (SELECT 1 FROM calendar_sources s WHERE s.account_id = caldav_accounts.id)",
    )
    .bind(&cut)
    .bind(&cut)
    .execute(db)
    .await?;
    Ok(done.rows_affected())
}

// ---- the page ----------------------------------------------------------------------------------

/// One account on the page (every account that passed a Test once).
#[derive(sqlx::FromRow, Clone, Debug)]
pub struct AccountView {
    pub id: i64,
    pub server_url: String,
    pub username: String,
    pub hosts: String,
    pub calendars_json: String,
    pub state: String,
    pub test_error: Option<String>,
    pub test_status: Option<i64>,
    pub ok_at: Option<String>,
    pub key_fp: String,
    pub lan_http: bool,
}

pub async fn accounts(db: &sqlx::SqlitePool) -> Result<Vec<AccountView>, sqlx::Error> {
    sqlx::query_as(
        "SELECT id, server_url, username, hosts, calendars_json, state, test_error, test_status, ok_at, \
         password_key_fp AS key_fp, lan_http FROM caldav_accounts WHERE principal IS NOT NULL ORDER BY id",
    )
    .fetch_all(db)
    .await
}

impl AccountView {
    pub fn calendars(&self) -> Vec<Found> {
        serde_json::from_str(&self.calendars_json).unwrap_or_default()
    }

    pub fn hosts(&self) -> Vec<String> {
        serde_json::from_str(&self.hosts).unwrap_or_default()
    }

    pub fn uid(&self, found: &Found) -> String {
        uid_of(&self.server_url, &self.username, &found.href)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_server_field_takes_an_address_or_an_email() {
        assert_eq!(
            normalize_server(" Cloud.Example.NO ").unwrap(),
            ("https://cloud.example.no/".to_string(), None)
        );
        assert_eq!(
            normalize_server("https://cloud.example.no:443/remote.php/dav?x=1#y")
                .unwrap()
                .0,
            "https://cloud.example.no/remote.php/dav"
        );
        assert_eq!(
            normalize_server("forelder@iCloud.com").unwrap(),
            (
                "https://caldav.icloud.com/".to_string(),
                Some("forelder@iCloud.com".to_string())
            )
        );
        assert_eq!(
            normalize_server("forelder@familie.example").unwrap().0,
            "https://familie.example/"
        );
        assert_eq!(
            normalize_server("http://nas.home.arpa:5232/").unwrap().0,
            "http://nas.home.arpa:5232/"
        );
        assert_eq!(
            normalize_server("https://u:p@x.example/"),
            Err("calendar.caldav.error.userinfo")
        );
        assert_eq!(
            normalize_server("ftp://x.example/"),
            Err("calendar.caldav.error.no_server")
        );
        assert_eq!(
            normalize_server("  "),
            Err("calendar.caldav.error.no_server")
        );
    }

    #[test]
    fn a_multistatus_is_read_by_namespace() {
        // Any prefix, a default namespace, CDATA, an entity, a 404 propstat left out.
        let body = br#"<?xml version="1.0"?>
<multistatus xmlns="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:x="http://calendarserver.org/ns/">
 <response><href>/cal/home/</href>
  <propstat><prop><resourcetype><collection/><C:calendar/></resourcetype><displayname>Skole &amp; fritid</displayname>
   <C:supported-calendar-component-set><C:comp name="VEVENT"/><C:comp name="VTODO"/></C:supported-calendar-component-set>
   <x:getctag><![CDATA[ctag-1]]></x:getctag></prop><status>HTTP/1.1 200 OK</status></propstat>
  <propstat><prop><C:calendar-color/></prop><status>HTTP/1.1 404 Not Found</status></propstat>
 </response>
 <response><href>/principals/u/</href><propstat><prop><current-user-principal><href>/principals/u/</href></current-user-principal></prop></propstat></response>
</multistatus>"#;
        let rs = multistatus(body).unwrap();
        assert_eq!(rs.len(), 2);
        assert_eq!(rs[0].href, "/cal/home/");
        assert!(
            rs[0]
                .prop(DAV, "resourcetype")
                .unwrap()
                .children
                .contains(&(CALDAV.to_string(), "calendar".to_string()))
        );
        assert_eq!(
            rs[0].prop(DAV, "displayname").unwrap().text,
            "Skole & fritid"
        );
        assert_eq!(
            rs[0]
                .prop(CALDAV, "supported-calendar-component-set")
                .unwrap()
                .comps,
            ["VEVENT", "VTODO"]
        );
        assert_eq!(rs[0].prop(CS, "getctag").unwrap().text, "ctag-1");
        assert!(rs[0].prop(CALDAV, "calendar-color").is_none());
        assert_eq!(
            rs[1].prop(DAV, "current-user-principal").unwrap().hrefs,
            ["/principals/u/"]
        );
        // An internal subset (entity tricks) and garbage are refused.
        assert!(
            multistatus(b"<!DOCTYPE x [<!ENTITY a \"b\">]><multistatus xmlns=\"DAV:\"/>").is_none()
        );
        assert!(multistatus(b"<a><b></a>").is_none());
    }

    #[test]
    fn uids_follow_the_account_not_its_row() {
        let a = uid_of(
            "https://caldav.icloud.com/",
            "f@icloud.com",
            "https://p1.icloud.com/1/calendars/home/",
        );
        assert_eq!(a.len(), 32);
        assert_eq!(
            a,
            uid_of(
                "https://caldav.icloud.com/",
                "f@icloud.com",
                "https://p1.icloud.com/1/calendars/home/"
            )
        );
        assert_ne!(
            a,
            uid_of(
                "https://caldav.icloud.com/",
                "g@icloud.com",
                "https://p1.icloud.com/1/calendars/home/"
            )
        );
        // Never a link's uid for the same address.
        assert_ne!(
            a,
            calendar_feeds::uid_of("https://p1.icloud.com/1/calendars/home/")
        );
    }
}
