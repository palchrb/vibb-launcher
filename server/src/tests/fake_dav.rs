//! A stateful fake CalDAV calendar (design 72b §10, QA L12) served through `CannedFetch::serve`: a map of resources
//! with ETags that honours `If-Match` / `If-None-Match`, answers the ctag PROPFIND and the REPORT from the map, and
//! can be told to answer a write's precondition (`no-uid-conflict`, `need-privileges`, ...), to rewrite bodies after
//! a PUT as iCloud does (with or without an ETag in the answer), to throttle (429/503), to give weak or no ETags, to
//! redirect writes, and to hide a resource from the REPORT. The parent's own edits go straight into the map.

use std::collections::{BTreeMap, HashSet};
use std::sync::{Arc, Mutex};

use crate::music_net::SourceResponse;

use super::music::Recorded;

/// One resource: its ETag (without quotes) and its body.
#[derive(Clone)]
pub struct Resource {
    pub etag: u64,
    pub body: String,
}

#[derive(Default)]
pub struct Switches {
    /// After a PUT the stored body gets an `X-APPLE-` line and a new DTSTAMP, and a new ETag (QA M1).
    pub rewrite: bool,
    /// A PUT's answer carries no ETag (RFC 4791 §5.3.4).
    pub put_without_etag: bool,
    /// Every ETag is weak (`W/"…"`).
    pub weak: bool,
    /// No ETag anywhere (the REPORT's `getetag` too).
    pub no_etag: bool,
    /// Every write answers this status (429, 503, 507, 409, 400, 403 with [Switches::forbidden_body]).
    pub writes_answer: Option<u16>,
    pub forbidden_body: Option<String>,
    pub retry_after: Option<String>,
    /// Writes answer a 307 to the same collection.
    pub redirect_writes: bool,
    /// Resources the REPORT leaves out (moved past its window).
    pub hidden: HashSet<String>,
    /// The next N writes answer 412 whatever the condition (a lost-answer race).
    pub fail_next_with_412: usize,
    /// The next PUT is stored but answered with a network error (a timed-out answer).
    pub lose_next_put: bool,
}

#[derive(Default)]
pub struct State {
    pub resources: BTreeMap<String, Resource>,
    pub ctag: u64,
    next_etag: u64,
    pub switches: Switches,
}

/// One write as the fake saw it: (method, url, If-Match, If-None-Match).
pub type Write = (String, String, Option<String>, Option<String>);

/// The fake. `collection` ends in `/`; resources are full URLs under it.
pub struct FakeDav {
    pub collection: String,
    pub state: Mutex<State>,
    /// Writes (PUT/DELETE) as (method, url, If-Match, If-None-Match).
    pub writes: Mutex<Vec<Write>>,
}

fn quoted(etag: u64, weak: bool) -> String {
    if weak {
        format!("W/\"e{etag}\"")
    } else {
        format!("\"e{etag}\"")
    }
}

fn xml_escape(text: &str) -> String {
    text.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}

impl FakeDav {
    pub fn new(collection: &str) -> Arc<FakeDav> {
        Arc::new(FakeDav {
            collection: collection.to_string(),
            state: Mutex::new(State::default()),
            writes: Mutex::new(Vec::new()),
        })
    }

    /// Serves it on `fetch` (before the canned answers).
    pub fn serve_on(self: &Arc<Self>, fetch: &super::music::CannedFetch) {
        let me = self.clone();
        fetch.serve(Arc::new(move |r: &Recorded| me.answer(r)));
    }

    pub fn href(&self, uid: &str) -> String {
        format!("{}{uid}.ics", self.collection)
    }

    /// The parent's own PUT (no condition): a new ETag, the ctag moves.
    pub fn parent_put(&self, url: &str, body: &str) {
        let mut s = self.state.lock().unwrap();
        s.next_etag += 1;
        let etag = s.next_etag;
        s.resources.insert(
            url.to_string(),
            Resource {
                etag,
                body: body.to_string(),
            },
        );
        s.ctag += 1;
    }

    /// The parent's edit of a resource's body.
    pub fn parent_edit(&self, url: &str, edit: impl Fn(&str) -> String) {
        let body = self.body(url).expect("the resource exists");
        self.parent_put(url, &edit(&body));
    }

    pub fn parent_delete(&self, url: &str) {
        let mut s = self.state.lock().unwrap();
        s.resources.remove(url);
        s.ctag += 1;
    }

    pub fn body(&self, url: &str) -> Option<String> {
        self.state
            .lock()
            .unwrap()
            .resources
            .get(url)
            .map(|r| r.body.clone())
    }

    pub fn count(&self) -> usize {
        self.state.lock().unwrap().resources.len()
    }

    pub fn switches(&self, set: impl FnOnce(&mut Switches)) {
        set(&mut self.state.lock().unwrap().switches);
    }

    fn ok(status: u16, etag: Option<String>, body: Vec<u8>) -> SourceResponse {
        SourceResponse {
            status,
            body,
            etag,
            ..Default::default()
        }
    }

    fn answer(&self, r: &Recorded) -> Option<SourceResponse> {
        let in_collection = r.url == self.collection || r.url.starts_with(&self.collection);
        if !in_collection {
            return None;
        }
        let mut s = self.state.lock().unwrap();
        let weak = s.switches.weak;
        let no_etag = s.switches.no_etag;
        let tag = |etag: u64| (!no_etag).then(|| quoted(etag, weak));
        match r.method.as_str() {
            "PROPFIND" if r.url == self.collection => {
                let body = format!(
                    r#"<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/"><d:response><d:href>/calendars/forelder/familie/</d:href><d:propstat><d:prop><cs:getctag>ctag-{}</cs:getctag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"#,
                    s.ctag
                );
                Some(Self::ok(207, None, body.into_bytes()))
            }
            "REPORT" if r.url == self.collection => {
                let mut body = String::from(
                    r#"<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">"#,
                );
                for (url, res) in &s.resources {
                    if s.switches.hidden.contains(url) {
                        continue;
                    }
                    let path = url
                        .strip_prefix("https://dav.example.org")
                        .unwrap_or(url)
                        // Another percent-encoding of the same href (QA N5): never matched by it.
                        .replace('-', "%2D");
                    let etag = tag(res.etag)
                        .map(|e| format!("<d:getetag>{}</d:getetag>", xml_escape(&e)))
                        .unwrap_or_default();
                    body.push_str(&format!(
                        "<d:response><d:href>{path}</d:href><d:propstat><d:prop>{etag}<c:calendar-data>{}</c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>",
                        xml_escape(&res.body)
                    ));
                }
                body.push_str("</d:multistatus>");
                Some(Self::ok(207, None, body.into_bytes()))
            }
            "GET" => Some(match s.resources.get(&r.url) {
                Some(res) => Self::ok(200, tag(res.etag), res.body.clone().into_bytes()),
                None => Self::ok(404, None, Vec::new()),
            }),
            "PUT" | "DELETE" => {
                let if_match = r.header("if-match").map(str::to_string);
                let if_none = r.header("if-none-match").map(str::to_string);
                self.writes.lock().unwrap().push((
                    r.method.clone(),
                    r.url.clone(),
                    if_match.clone(),
                    if_none.clone(),
                ));
                if s.switches.redirect_writes {
                    return Some(SourceResponse {
                        status: 307,
                        location: Some(r.url.clone()),
                        ..Default::default()
                    });
                }
                if let Some(status) = s.switches.writes_answer {
                    return Some(SourceResponse {
                        status,
                        body: s
                            .switches
                            .forbidden_body
                            .clone()
                            .unwrap_or_default()
                            .into_bytes(),
                        retry_after: s.switches.retry_after.clone(),
                        ..Default::default()
                    });
                }
                if s.switches.fail_next_with_412 > 0 {
                    s.switches.fail_next_with_412 -= 1;
                    return Some(Self::ok(412, None, Vec::new()));
                }
                let current = s.resources.get(&r.url).map(|res| res.etag);
                let matches = |cond: &str, etag: u64| {
                    cond == quoted(etag, false) || cond == quoted(etag, true)
                };
                let allowed = match (&if_match, &if_none, current) {
                    (Some(m), _, Some(etag)) => matches(m, etag),
                    (Some(_), _, None) => false,
                    (None, Some(n), current) => n == "*" && current.is_none(),
                    // Never a write without a condition: the tests assert none came.
                    (None, None, _) => true,
                };
                if !allowed {
                    return Some(Self::ok(412, None, Vec::new()));
                }
                if r.method == "DELETE" {
                    s.resources.remove(&r.url);
                    s.ctag += 1;
                    return Some(Self::ok(204, None, Vec::new()));
                }
                let mut body = String::from_utf8(r.body.clone().unwrap_or_default()).unwrap();
                s.next_etag += 1;
                let etag = s.next_etag;
                if s.switches.rewrite {
                    // Rewritten after the PUT, as iCloud does: the answer's ETag is the version written, the stored
                    // one already another.
                    body = body
                        .replace(
                            "BEGIN:VEVENT\r\n",
                            "BEGIN:VEVENT\r\nX-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC\r\n",
                        )
                        .replace("DTSTAMP:", "DTSTAMP:2099");
                    s.next_etag += 1;
                }
                let stored = s.next_etag;
                let created = current.is_none();
                s.resources
                    .insert(r.url.clone(), Resource { etag: stored, body });
                s.ctag += 1;
                if s.switches.lose_next_put {
                    s.switches.lose_next_put = false;
                    return Some(Self::ok(599, None, Vec::new()));
                }
                let answer_etag = if s.switches.put_without_etag {
                    None
                } else {
                    tag(etag)
                };
                Some(Self::ok(
                    if created { 201 } else { 204 },
                    answer_etag,
                    Vec::new(),
                ))
            }
            _ => None,
        }
    }
}
