//! The one client for every music source fetch - the add check, the sweep and source covers
//! (design `docs/design/21b-music-server-sweep.md` §2.1 and §2.7, with its QA review and decisions).
//!
//! - **Limits per request kind** ([Kind]): a size cap (the body is read up to it and marked
//!   `truncated` past it - the caller decides) and an overall timeout; a 10 s connect timeout and
//!   30 s without data for all. reqwest's `gzip` is on, so the caps count decoded bytes.
//! - **Addresses** ([Reach]): a source the parent entered or imported may be on the home LAN or
//!   the tailnet ([Reach::Any]). A public one ([Reach::Public]) can never take the server to a
//!   private address: its resolver drops private answers at connect time (no DNS-rebinding gap),
//!   IP literals are checked before the request and at every redirect, at most 5 redirects, http and
//!   https only, never a proxy.
//! - **One request in flight server-wide** ([Gated]): every fetch passes one fair (FIFO) gate, with
//!   request starts at least 200 ms apart, so an add check waits for at most one sweep request.

use std::future::Future;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::pin::Pin;
use std::sync::Arc;
use std::time::Duration;

/// The largest feed read (decoded), for the add check and the sweep (21b §2.3, raised from 5 MB).
pub const MAX_FEED_BYTES: usize = 20_000_000;
/// An item `url` or `art` longer than this is dropped (21b §1).
pub const MAX_MEDIA_URL_CHARS: usize = 2000;

pub type BoxFut<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

/// What a request is for: its size cap and overall timeout (21b §2.1).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    /// A psapi catalog root or episode page.
    Page,
    /// A psapi playback manifest or metadata.
    Manifest,
    /// An RSS feed.
    Feed,
    /// A cover image.
    Image,
}

impl Kind {
    pub fn limit(self) -> usize {
        match self {
            Kind::Page => 2_000_000,
            Kind::Manifest => 256_000,
            Kind::Feed => MAX_FEED_BYTES,
            Kind::Image => 10_000_000,
        }
    }

    pub fn overall(self) -> Duration {
        Duration::from_secs(match self {
            Kind::Page => 30,
            Kind::Manifest => 15,
            Kind::Feed => 120,
            Kind::Image => 60,
        })
    }
}

/// Which addresses a request may reach.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Reach {
    /// Public addresses only: NRK, and every source whose target was public at its first check.
    Public,
    /// A LAN or tailnet source the parent entered: any address.
    Any,
}

pub struct SourceRequest {
    pub url: String,
    pub kind: Kind,
    pub reach: Reach,
    /// Conditional GET (RSS): `If-None-Match` and `If-Modified-Since`.
    pub etag: Option<String>,
    pub last_modified: Option<String>,
}

impl SourceRequest {
    pub fn new(url: impl Into<String>, kind: Kind, reach: Reach) -> Self {
        SourceRequest {
            url: url.into(),
            kind,
            reach,
            etag: None,
            last_modified: None,
        }
    }
}

/// An answer: the status, the body's first `kind.limit()` bytes (`truncated` = there was more) and
/// the validators.
#[derive(Debug, Clone, Default)]
pub struct SourceResponse {
    pub status: u16,
    pub body: Vec<u8>,
    pub truncated: bool,
    pub etag: Option<String>,
    pub last_modified: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FetchError {
    Network(String),
    Timeout,
    /// A private address where only public ones may go, a bad scheme, too many redirects.
    Refused(String),
}

impl FetchError {
    /// The sweep's error code (21b §2.4).
    pub fn code(&self) -> &'static str {
        match self {
            FetchError::Timeout => "timeout",
            FetchError::Network(_) | FetchError::Refused(_) => "network",
        }
    }

    pub fn message(&self) -> String {
        match self {
            FetchError::Network(err) => err.clone(),
            FetchError::Timeout => "no answer in time".to_string(),
            FetchError::Refused(why) => why.clone(),
        }
    }
}

/// A music source fetcher: [HttpSource] behind [Gated] in the server, a canned one in the tests.
pub trait Source: Send + Sync {
    fn get<'a>(&'a self, request: SourceRequest) -> BoxFut<'a, Result<SourceResponse, FetchError>>;
    /// Every address `host` resolves to - where a target lives (`lan`), and whether a public
    /// feed's enclosure host is private. An IP literal is its own answer.
    fn resolve<'a>(&'a self, host: &'a str) -> BoxFut<'a, Result<Vec<IpAddr>, FetchError>>;
}

/// The private ranges of 21b §2.7. Mapped (`::ffff:0:0/96`) and NAT64 (`64:ff9b::/96`) addresses
/// are judged by their IPv4.
pub fn is_private(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => private_v4(v4),
        IpAddr::V6(v6) => private_v6(v6),
    }
}

fn private_v4(ip: Ipv4Addr) -> bool {
    let [a, b, c, _] = ip.octets();
    a == 0
        || a == 10
        || (a == 100 && (64..=127).contains(&b))
        || a == 127
        || (a == 169 && b == 254)
        || (a == 172 && (16..=31).contains(&b))
        || (a == 192 && b == 0 && c == 0)
        || (a == 192 && b == 168)
        || (a == 198 && (b == 18 || b == 19))
        || a >= 224
}

fn private_v6(ip: Ipv6Addr) -> bool {
    let s = ip.segments();
    if let Some(v4) = ip.to_ipv4_mapped() {
        return private_v4(v4);
    }
    if s[0] == 0x64 && s[1] == 0xff9b && s[2..6] == [0, 0, 0, 0] {
        let v4 = Ipv4Addr::new((s[6] >> 8) as u8, s[6] as u8, (s[7] >> 8) as u8, s[7] as u8);
        return private_v4(v4);
    }
    ip.is_unspecified()
        || ip.is_loopback()
        || (s[0] & 0xfe00) == 0xfc00
        || (s[0] & 0xffc0) == 0xfe80
        || (s[0] & 0xff00) == 0xff00
}

/// The host of an http(s) URL (lower case, IPv6 without brackets).
pub fn host_of(url: &str) -> Option<String> {
    let url = reqwest::Url::parse(url).ok()?;
    if !matches!(url.scheme(), "http" | "https") {
        return None;
    }
    let host = url
        .host_str()?
        .trim_start_matches('[')
        .trim_end_matches(']');
    Some(host.to_ascii_lowercase())
}

/// The IP of a host that is an IP literal.
pub fn literal_ip(host: &str) -> Option<IpAddr> {
    host.trim_start_matches('[')
        .trim_end_matches(']')
        .parse()
        .ok()
}

/// An item `url` or `art` the phones may get: http or https, no userinfo, at most
/// [MAX_MEDIA_URL_CHARS] (21b §1, QA #13).
pub fn media_url_ok(url: &str) -> bool {
    if url.chars().count() > MAX_MEDIA_URL_CHARS {
        return false;
    }
    reqwest::Url::parse(url).is_ok_and(|u| {
        matches!(u.scheme(), "http" | "https")
            && u.host_str().is_some()
            && u.username().is_empty()
            && u.password().is_none()
    })
}

/// Tracking-redirect prefixes stripped from the `url` the phones get (21 §4.3): the phone's DNS
/// filter may block them. The item key uses the enclosure as written.
const TRACKING_PREFIXES: &[&str] = &[
    "dts.podtrac.com/redirect.mp3/",
    "www.podtrac.com/pts/redirect.mp3/",
    "podtrac.com/pts/redirect.mp3/",
    "chtbl.com/track/",
    "chrt.fm/track/",
    "pdst.fm/e/",
    "op3.dev/e/",
    "op3.dev/e,",
];

/// `url` without the tracking prefixes in front of it (chained ones too).
pub fn strip_tracking(url: &str) -> String {
    let mut current = url.to_string();
    for _ in 0..6 {
        let Some((scheme, rest)) = current.split_once("://") else {
            break;
        };
        let lower = rest.to_ascii_lowercase();
        let Some(prefix) = TRACKING_PREFIXES.iter().find(|p| lower.starts_with(**p)) else {
            break;
        };
        let mut after = &rest[prefix.len()..];
        // chtbl/chrt carry an id segment, op3's "e," variant its options: skip to the next "/".
        if matches!(
            *prefix,
            "chtbl.com/track/" | "chrt.fm/track/" | "op3.dev/e,"
        ) {
            after = after.split_once('/').map_or("", |(_, tail)| tail);
        }
        if after.is_empty() {
            break;
        }
        current = if after.starts_with("http://") || after.starts_with("https://") {
            after.to_string()
        } else {
            format!("{scheme}://{after}")
        };
    }
    current
}

/// Resolves a name and keeps only public answers - the resolver of [Reach::Public]'s client, so a
/// public name that (re)binds to a private address can't be connected to.
struct PublicResolver;

impl reqwest::dns::Resolve for PublicResolver {
    fn resolve(&self, name: reqwest::dns::Name) -> reqwest::dns::Resolving {
        Box::pin(async move {
            let found: Vec<SocketAddr> =
                tokio::net::lookup_host((name.as_str(), 0)).await?.collect();
            let public = public_only(found);
            if public.is_empty() {
                return Err(format!("{} resolves only to private addresses", name.as_str()).into());
            }
            let addrs: reqwest::dns::Addrs = Box::new(public.into_iter());
            Ok(addrs)
        })
    }
}

/// The answers a public-only client may connect to.
pub fn public_only(found: Vec<SocketAddr>) -> Vec<SocketAddr> {
    found.into_iter().filter(|a| !is_private(a.ip())).collect()
}

/// The redirect rule: at most 5 hops, http/https only, and for a public-only client no IP literal
/// in a private range (names are checked by its resolver).
fn redirect_policy(reach: Reach) -> reqwest::redirect::Policy {
    reqwest::redirect::Policy::custom(move |attempt| {
        // `previous()` holds the first URL too: five redirects are six URLs (QA 1c #16).
        if attempt.previous().len() > 5 {
            return attempt.error("more than 5 redirects");
        }
        let url = attempt.url();
        if !matches!(url.scheme(), "http" | "https") {
            return attempt.error("a redirect to something that isn't http or https");
        }
        if reach == Reach::Public && url.host_str().and_then(literal_ip).is_some_and(is_private) {
            return attempt.error("a redirect from a public address to a private one");
        }
        attempt.follow()
    })
}

/// The real client: two reqwest clients, one per [Reach].
pub struct HttpSource {
    public: reqwest::Client,
    any: reqwest::Client,
}

impl HttpSource {
    pub fn new() -> Self {
        let base = |reach: Reach| {
            let builder = reqwest::Client::builder()
                .user_agent("kid-phone-server (self-hosted; vibb music library)")
                .no_proxy()
                .connect_timeout(Duration::from_secs(10))
                .read_timeout(Duration::from_secs(30))
                .redirect(redirect_policy(reach));
            match reach {
                Reach::Public => builder.dns_resolver(Arc::new(PublicResolver)),
                Reach::Any => builder,
            }
            .build()
            .expect("the music source client builds")
        };
        HttpSource {
            public: base(Reach::Public),
            any: base(Reach::Any),
        }
    }
}

impl Default for HttpSource {
    fn default() -> Self {
        Self::new()
    }
}

fn header(response: &reqwest::Response, name: reqwest::header::HeaderName) -> Option<String> {
    response
        .headers()
        .get(name)
        .and_then(|v| v.to_str().ok())
        .map(|v| v.chars().take(500).collect())
}

fn network_error(err: reqwest::Error) -> FetchError {
    if err.is_timeout() {
        return FetchError::Timeout;
    }
    let mut text = err.to_string();
    let mut source = std::error::Error::source(&err);
    while let Some(inner) = source {
        text.push_str(": ");
        text.push_str(&inner.to_string());
        source = inner.source();
    }
    if text.contains("private address") || text.contains("redirect") {
        FetchError::Refused(text)
    } else {
        FetchError::Network(text)
    }
}

impl Source for HttpSource {
    fn get<'a>(&'a self, request: SourceRequest) -> BoxFut<'a, Result<SourceResponse, FetchError>> {
        Box::pin(async move {
            let Some(host) = host_of(&request.url) else {
                return Err(FetchError::Refused(
                    "only http and https links work".to_string(),
                ));
            };
            if request.reach == Reach::Public && literal_ip(&host).is_some_and(is_private) {
                return Err(FetchError::Refused(
                    "a private address where only public ones may go".to_string(),
                ));
            }
            let client = match request.reach {
                Reach::Public => &self.public,
                Reach::Any => &self.any,
            };
            let mut builder = client.get(&request.url).timeout(request.kind.overall());
            if let Some(etag) = &request.etag {
                builder = builder.header(reqwest::header::IF_NONE_MATCH, etag);
            }
            if let Some(modified) = &request.last_modified {
                builder = builder.header(reqwest::header::IF_MODIFIED_SINCE, modified);
            }
            let mut response = builder.send().await.map_err(network_error)?;
            let status = response.status().as_u16();
            let etag = header(&response, reqwest::header::ETAG);
            let last_modified = header(&response, reqwest::header::LAST_MODIFIED);
            let limit = request.kind.limit();
            let mut body = Vec::new();
            let mut truncated = false;
            while let Some(chunk) = response.chunk().await.map_err(network_error)? {
                let room = limit - body.len();
                if chunk.len() > room {
                    body.extend_from_slice(&chunk[..room]);
                    truncated = true;
                    break;
                }
                body.extend_from_slice(&chunk);
            }
            Ok(SourceResponse {
                status,
                body,
                truncated,
                etag,
                last_modified,
            })
        })
    }

    fn resolve<'a>(&'a self, host: &'a str) -> BoxFut<'a, Result<Vec<IpAddr>, FetchError>> {
        Box::pin(async move {
            if let Some(ip) = literal_ip(host) {
                return Ok(vec![ip]);
            }
            tokio::net::lookup_host((host, 0))
                .await
                .map(|found| found.map(|a| a.ip()).collect())
                .map_err(|e| FetchError::Network(e.to_string()))
        })
    }
}

/// One request in flight server-wide, starts at least `spacing` apart, in arrival order (a
/// `tokio::sync::Mutex` is fair).
pub struct Gated {
    inner: Arc<dyn Source>,
    last: tokio::sync::Mutex<Option<tokio::time::Instant>>,
    spacing: Duration,
}

/// Request starts at least this far apart (21b §2.1).
pub const REQUEST_SPACING: Duration = Duration::from_millis(200);

impl Gated {
    pub fn new(inner: Arc<dyn Source>, spacing: Duration) -> Self {
        Gated {
            inner,
            last: tokio::sync::Mutex::new(None),
            spacing,
        }
    }
}

impl Source for Gated {
    fn get<'a>(&'a self, request: SourceRequest) -> BoxFut<'a, Result<SourceResponse, FetchError>> {
        Box::pin(async move {
            let mut last = self.last.lock().await;
            if let Some(at) = *last {
                tokio::time::sleep_until(at + self.spacing).await;
            }
            *last = Some(tokio::time::Instant::now());
            // Held until the answer is in: never two requests at once.
            self.inner.get(request).await
        })
    }

    fn resolve<'a>(&'a self, host: &'a str) -> BoxFut<'a, Result<Vec<IpAddr>, FetchError>> {
        self.inner.resolve(host)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn private_ranges_follow_the_design() {
        for ip in [
            "0.1.2.3",
            "10.0.0.1",
            "100.64.0.1",
            "100.127.255.254",
            "127.0.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "172.31.255.255",
            "192.0.0.8",
            "192.168.1.1",
            "198.18.0.1",
            "198.19.255.1",
            "224.0.0.1",
            "255.255.255.255",
            "::",
            "::1",
            "fd7a:115c:a1e0::1",
            "fc00::1",
            "fe80::1",
            "ff02::1",
            "::ffff:192.168.1.1",
            "::ffff:127.0.0.1",
            "64:ff9b::a00:1",
        ] {
            assert!(is_private(ip.parse().unwrap()), "{ip}");
        }
        for ip in [
            "1.1.1.1",
            "100.63.255.255",
            "100.128.0.1",
            "172.15.0.1",
            "172.32.0.1",
            "192.0.1.1",
            "198.20.0.1",
            "2a02:c7f::1",
            "::ffff:1.1.1.1",
            "64:ff9b::101:101",
        ] {
            assert!(!is_private(ip.parse().unwrap()), "{ip}");
        }
    }

    #[test]
    fn the_public_resolver_drops_private_answers() {
        let found = vec![
            "192.168.1.1:0".parse().unwrap(),
            "[fd7a:115c:a1e0::5]:0".parse().unwrap(),
            "93.184.216.34:0".parse().unwrap(),
        ];
        assert_eq!(
            public_only(found),
            vec!["93.184.216.34:0".parse::<SocketAddr>().unwrap()]
        );
        assert!(public_only(vec!["10.0.0.1:0".parse().unwrap()]).is_empty());
    }

    #[test]
    fn media_urls_are_http_without_userinfo() {
        assert!(media_url_ok("https://example.org/a.mp3"));
        assert!(media_url_ok("http://example.org/a.mp3?x=1"));
        for bad in [
            "file:///etc/passwd",
            "data:audio/mp3;base64,AAAA",
            "content://media/1",
            "https://user:pw@example.org/a.mp3",
            "https://user@example.org/a.mp3",
            "not a url",
        ] {
            assert!(!media_url_ok(bad), "{bad}");
        }
        assert!(!media_url_ok(&format!(
            "https://example.org/{}",
            "a".repeat(2000)
        )));
        assert_eq!(host_of("https://[::1]:8080/x").as_deref(), Some("::1"));
        assert_eq!(
            host_of("HTTPS://Example.ORG/x").as_deref(),
            Some("example.org")
        );
        assert_eq!(host_of("ftp://example.org/x"), None);
    }

    #[test]
    fn tracking_prefixes_are_stripped() {
        assert_eq!(
            strip_tracking("https://dts.podtrac.com/redirect.mp3/example.org/ep1.mp3"),
            "https://example.org/ep1.mp3"
        );
        assert_eq!(
            strip_tracking("https://chtbl.com/track/ABC123/traffic.example.org/ep.mp3"),
            "https://traffic.example.org/ep.mp3"
        );
        assert_eq!(
            strip_tracking("https://pdst.fm/e/chtbl.com/track/XYZ/https://cdn.example.org/a.mp3"),
            "https://cdn.example.org/a.mp3"
        );
        assert_eq!(
            strip_tracking("https://op3.dev/e,pg=abc/https://media.example.org/b.mp3"),
            "https://media.example.org/b.mp3"
        );
        assert_eq!(
            strip_tracking("https://op3.dev/e/media.example.org/c.mp3"),
            "https://media.example.org/c.mp3"
        );
        assert_eq!(
            strip_tracking("https://example.org/plain.mp3"),
            "https://example.org/plain.mp3"
        );
    }

    /// A loopback server (no real network): a public-only request to it is refused before it is
    /// sent; a LAN request gets it, with gzip, validators and the size cap; redirects are bounded
    /// and stay http(s).
    #[tokio::test]
    async fn the_client_keeps_public_sources_off_private_addresses() {
        let _ = rustls::crypto::ring::default_provider().install_default();
        use axum::response::IntoResponse;
        use axum::routing::get;
        let app = axum::Router::new()
            .route(
                "/feed",
                get(|headers: axum::http::HeaderMap| async move {
                    if headers.get("if-none-match").is_some_and(|v| v == "\"v1\"") {
                        return (
                            axum::http::StatusCode::NOT_MODIFIED,
                            [("etag", "\"v1\"")],
                            Vec::new(),
                        );
                    }
                    (
                        axum::http::StatusCode::OK,
                        [("etag", "\"v1\"")],
                        b"<rss/>".to_vec(),
                    )
                }),
            )
            .route("/big", get(|| async { vec![b'x'; 300_000] }))
            .route(
                "/hop/{n}",
                get(
                    |axum::extract::Path(n): axum::extract::Path<u32>| async move {
                        if n == 0 {
                            axum::response::Redirect::temporary("/feed").into_response()
                        } else {
                            axum::response::Redirect::temporary(&format!("/hop/{}", n - 1))
                                .into_response()
                        }
                    },
                ),
            )
            .route(
                "/loop",
                get(|| async { axum::response::Redirect::temporary("/loop") }),
            )
            .route(
                "/ftp",
                get(|| async { axum::response::Redirect::temporary("ftp://example.org/x") }),
            );
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        let source = HttpSource::new();
        let url = |path: &str| format!("http://{addr}{path}");

        let refused = source
            .get(SourceRequest::new(url("/feed"), Kind::Feed, Reach::Public))
            .await;
        assert!(
            matches!(refused, Err(FetchError::Refused(_))),
            "{refused:?}"
        );

        let ok = source
            .get(SourceRequest::new(url("/feed"), Kind::Feed, Reach::Any))
            .await
            .unwrap();
        assert_eq!((ok.status, ok.body.as_slice()), (200, &b"<rss/>"[..]));
        assert_eq!(ok.etag.as_deref(), Some("\"v1\""));
        let mut conditional = SourceRequest::new(url("/feed"), Kind::Feed, Reach::Any);
        conditional.etag = ok.etag.clone();
        assert_eq!(source.get(conditional).await.unwrap().status, 304);

        let big = source
            .get(SourceRequest::new(url("/big"), Kind::Manifest, Reach::Any))
            .await
            .unwrap();
        assert!(big.truncated);
        assert_eq!(big.body.len(), Kind::Manifest.limit());

        // Five redirects are followed, a sixth isn't (`/hop/n` makes n + 1).
        let five = source
            .get(SourceRequest::new(url("/hop/4"), Kind::Feed, Reach::Any))
            .await
            .unwrap();
        assert_eq!(five.status, 200);
        assert!(
            source
                .get(SourceRequest::new(url("/hop/5"), Kind::Feed, Reach::Any))
                .await
                .is_err()
        );
        for path in ["/loop", "/ftp"] {
            let err = source
                .get(SourceRequest::new(url(path), Kind::Page, Reach::Any))
                .await;
            assert!(err.is_err(), "{path}");
        }
        assert_eq!(
            source.resolve("192.168.1.1").await.unwrap(),
            vec!["192.168.1.1".parse::<IpAddr>().unwrap()]
        );
    }

    /// 21b §8: a public source that redirects to any private range (mapped IPv4 forms too) is
    /// refused before anything is sent there, and a name that resolves privately (DNS rebinding)
    /// is never connected to.
    #[tokio::test]
    async fn redirects_and_names_from_public_to_private_are_refused() {
        // As `main` does first (reqwest has no TLS provider of its own here).
        let _ = rustls::crypto::ring::default_provider().install_default();
        use axum::extract::Path;
        use axum::routing::get;
        const TARGETS: [&str; 12] = [
            "http://0.0.0.0/x",
            "http://10.0.0.1/x",
            "http://100.64.0.1/x",
            "http://127.0.0.1:1/x",
            "http://169.254.169.254/latest/meta-data",
            "http://172.16.0.1/x",
            "http://192.168.1.1/x",
            "http://198.18.0.1/x",
            "http://[::1]/x",
            "http://[fd7a:115c:a1e0::1]/x",
            "http://[::ffff:192.168.1.1]/x",
            "http://[64:ff9b::a00:1]/x",
        ];
        let app = axum::Router::new()
            .route(
                "/r/{n}",
                get(|Path(n): Path<usize>| async move {
                    axum::response::Redirect::temporary(TARGETS[n])
                }),
            )
            .route("/feed", get(|| async { "<rss/>" }));
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        // The public client's redirect rule, without its resolver (the test server is loopback).
        let client = reqwest::Client::builder()
            .no_proxy()
            .redirect(redirect_policy(Reach::Public))
            .build()
            .unwrap();
        for (n, target) in TARGETS.iter().enumerate() {
            let err = client
                .get(format!("http://{addr}/r/{n}"))
                .send()
                .await
                .expect_err(target);
            assert!(
                matches!(network_error(err), FetchError::Refused(_)),
                "{target}"
            );
        }
        // A name that resolves only to loopback: the public resolver drops it at connect time.
        let refused = HttpSource::new()
            .get(SourceRequest::new(
                format!("http://localhost:{}/feed", addr.port()),
                Kind::Feed,
                Reach::Public,
            ))
            .await;
        assert!(refused.is_err(), "{refused:?}");
    }

    /// The gate lets one request through at a time, in order.
    #[tokio::test]
    async fn the_gate_allows_one_request_at_a_time() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        struct Slow {
            now: AtomicUsize,
            most: AtomicUsize,
        }
        impl Source for Slow {
            fn get<'a>(
                &'a self,
                _: SourceRequest,
            ) -> BoxFut<'a, Result<SourceResponse, FetchError>> {
                Box::pin(async move {
                    let at = self.now.fetch_add(1, Ordering::SeqCst) + 1;
                    self.most.fetch_max(at, Ordering::SeqCst);
                    tokio::time::sleep(Duration::from_millis(5)).await;
                    self.now.fetch_sub(1, Ordering::SeqCst);
                    Ok(SourceResponse::default())
                })
            }
            fn resolve<'a>(&'a self, _: &'a str) -> BoxFut<'a, Result<Vec<IpAddr>, FetchError>> {
                Box::pin(async { Ok(Vec::new()) })
            }
        }
        let slow = Arc::new(Slow {
            now: AtomicUsize::new(0),
            most: AtomicUsize::new(0),
        });
        let gated = Arc::new(Gated::new(slow.clone(), Duration::ZERO));
        let mut tasks = Vec::new();
        for _ in 0..8 {
            let gated = gated.clone();
            tasks.push(tokio::spawn(async move {
                gated
                    .get(SourceRequest::new(
                        "https://example.org",
                        Kind::Page,
                        Reach::Public,
                    ))
                    .await
            }));
        }
        for task in tasks {
            task.await.unwrap().unwrap();
        }
        assert_eq!(slow.most.load(Ordering::SeqCst), 1);
    }
}
