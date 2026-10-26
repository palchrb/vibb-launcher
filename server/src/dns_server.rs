//! A custom DNS server for the Web Filter (design 36 §2): one grammar for both of Settings › Web
//! Filter's fields ("Your server", "Backup server"), parsed without any network access - the server
//! never resolves or contacts either address; the phone is their only client.
//!
//! [parse_dns_server] classifies what a parent typed as DNS-over-HTTPS (a URL, an AdGuard Home
//! client id in its path or first label just part of it) or DNS-over-TLS (a host name or an IP,
//! port 853 unless given), or refuses it ([Refusal], worded by the handler). Stored as
//! [DnsServer::normalized], which parses to itself; two servers are the same when their
//! normalized forms are equal. Both values are secrets (either may hold a client id): only
//! [DnsServer::hint] and [DnsServer::kind] may reach a page, a log or an event.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

use serde::Serialize;

/// The longest value either field takes.
const MAX_LEN: usize = 512;
/// The longest DoH path.
const MAX_PATH: usize = 256;
/// The path a DoH URL without one gets (RFC 8484's usual one).
const DEFAULT_PATH: &str = "/dns-query";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Doh,
    Dot,
}

impl Kind {
    /// The word on the wire and in the security event.
    pub fn word(self) -> &'static str {
        match self {
            Kind::Doh => "doh",
            Kind::Dot => "dot",
        }
    }

    fn label(self) -> &'static str {
        match self {
            Kind::Doh => "DNS-over-HTTPS",
            Kind::Dot => "DNS-over-TLS",
        }
    }

    fn default_port(self) -> u16 {
        match self {
            Kind::Doh => 443,
            Kind::Dot => 853,
        }
    }
}

/// A server a phone can forward to: `host` lowercased (an IP in its canonical form, IPv6
/// unbracketed), `path` only for DoH (never empty there).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DnsServer {
    pub kind: Kind,
    pub host: String,
    pub port: u16,
    pub path: String,
}

/// Why a typed value isn't taken (design 36 §2). The handler words each one.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Refusal {
    Empty,
    TooLong,
    /// Whitespace or a control character inside.
    Spaces,
    /// Not ASCII: a name goes in its xn-- form.
    NotAscii,
    /// `http://`.
    Plaintext,
    /// Any scheme but `https://` and `tls://` (`quic://`, `h3://`, `sdns://`, ...).
    OtherScheme,
    /// `host:443` without a scheme.
    DohPortWithoutScheme,
    UserInfo,
    QueryOrFragment,
    /// A path on `tls://`.
    PathOnDot,
    /// Port 53 or 80.
    UnencryptedPort,
    /// Not a port number (0, above 65535, not digits).
    BadPort,
    /// A character outside `A-Z a-z 0-9 - . _ ~ /` and `%XX`, or longer than 256.
    BadPath,
    NotAnAddress,
    /// Loopback, unspecified, multicast, broadcast, IPv6 link-local, `localhost`.
    NeverAServer,
}

/// What the phone gets for one server (design 36 §4.1): DoH `{kind, url}`, DoT `{kind, host, port}`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum DnsTarget {
    Doh { url: String },
    Dot { host: String, port: u16 },
}

/// The policy's `dns_upstream`: the primary target, the parent's `rev` and the backup target.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct DnsUpstream {
    #[serde(flatten)]
    pub primary: DnsTarget,
    pub rev: i64,
    pub backup: DnsTarget,
}

impl DnsServer {
    /// `https://host[:port]/path` or `tls://host[:port]`: default ports left out, IPv6 bracketed.
    pub fn normalized(&self) -> String {
        let host = match self.host.parse::<Ipv6Addr>() {
            Ok(_) => format!("[{}]", self.host),
            Err(_) => self.host.clone(),
        };
        let port = if self.port == self.kind.default_port() {
            String::new()
        } else {
            format!(":{}", self.port)
        };
        match self.kind {
            Kind::Doh => format!("https://{host}{port}{}", self.path),
            Kind::Dot => format!("tls://{host}{port}"),
        }
    }

    /// What a page may show: `…` and the last two labels of a longer name (its first label may be
    /// a client id), an IP as is, never the path or the port; then the kind -
    /// "…dns.example · DNS-over-HTTPS".
    pub fn hint(&self) -> String {
        let shown = if self.host.parse::<IpAddr>().is_ok() {
            self.host.clone()
        } else {
            let labels: Vec<&str> = self.host.split('.').collect();
            if labels.len() > 2 {
                format!("…{}", labels[labels.len() - 2..].join("."))
            } else {
                self.host.clone()
            }
        };
        format!("{shown} · {}", self.kind.label())
    }

    pub fn target(&self) -> DnsTarget {
        match self.kind {
            Kind::Doh => DnsTarget::Doh {
                url: self.normalized(),
            },
            Kind::Dot => DnsTarget::Dot {
                host: self.host.clone(),
                port: self.port,
            },
        }
    }

    /// The same server as `other`: equal normalized forms.
    pub fn same_as(&self, other: &DnsServer) -> bool {
        self.normalized() == other.normalized()
    }
}

/// The stored pair when it is valid (design 36 §3): both parse and they aren't the same server.
pub fn valid_pair(server: &str, backup: &str) -> Option<(DnsServer, DnsServer)> {
    let server = parse_dns_server(server).ok()?;
    let backup = parse_dns_server(backup).ok()?;
    (!server.same_as(&backup)).then_some((server, backup))
}

/// The policy's `dns_upstream` for a stored pair with its `rev`, `None` unless the pair is valid.
pub fn upstream_of(server: &str, backup: &str, rev: i64) -> Option<DnsUpstream> {
    let (server, backup) = valid_pair(server, backup)?;
    Some(DnsUpstream {
        primary: server.target(),
        rev,
        backup: backup.target(),
    })
}

/// The security event's detail (design 36 §6): the choice and the stored servers' kinds, never an
/// address - `{"server":"custom","kind":"doh","backup_kind":"dot"}`.
pub fn event_detail(upstream: &str, server: &str, backup: &str) -> String {
    let kind = |value: &str| parse_dns_server(value).ok().map(|s| s.kind.word());
    let mut detail = serde_json::json!({ "server": upstream });
    if let Some(kind) = kind(server) {
        detail["kind"] = kind.into();
    }
    if let Some(kind) = kind(backup) {
        detail["backup_kind"] = kind.into();
    }
    detail.to_string()
}

/// The launcher capability that reports [DnsUpstreamState] (design 36 §4.2).
pub const DNS_CUSTOM_CAPABILITY: &str = "dns_custom_v1";

/// The launcher capability for design 67's bypass fixes: the browsers' managed config, the
/// built-in secure-DNS hosts and Firefox's canary.
pub const DNS_BYPASS_CAPABILITY: &str = "dns_bypass_v1";

/// Why a phone is on the backup (design 36 §4.2's words).
pub const BACKUP_REASONS: [&str; 6] = ["bootstrap", "connect", "tls", "http", "refused", "timeout"];

/// The phone's `dns_upstream` report as stored (`device_status.dns_upstream_json`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, serde::Deserialize)]
pub struct DnsUpstreamState {
    /// The `custom_rev` of the servers the phone forwards to.
    pub rev: i64,
    /// `custom`, `backup` or (design 67) `provider`.
    pub in_use: String,
    /// Since when (the phone's wall clock, ms); `None` when implausible.
    pub since_ms: Option<i64>,
    /// With `backup` only: one of [BACKUP_REASONS], or `None`.
    pub reason: Option<String>,
    /// "DNS can't be reached" since (design 67 §3.2, the phone's wall clock): with no
    /// [Self::unreachable_until_ms] it goes on, with one it is the last ended outage.
    pub unreachable_since_ms: Option<i64>,
    /// The last outage's end; only with a kept `unreachable_since_ms`, never before it.
    pub unreachable_until_ms: Option<i64>,
}

/// The secure-DNS hosts the phone blocks whatever the lists say (design 67 §2.2): the launcher's
/// `assets/dns/doh_hosts.txt`, byte-identical in `testdata/` (`doh_hosts_match_the_launchers_asset`).
const BUILTIN_DOH_HOSTS: &str = include_str!("../testdata/doh_hosts.txt");

/// `domain` (lowercase, no trailing dot) is a built-in secure-DNS host or under one: an Allowed
/// Domains entry for it changes nothing on the phone (qa-67-code L1).
pub fn is_builtin_doh_host(domain: &str) -> bool {
    static HOSTS: std::sync::LazyLock<std::collections::HashSet<&'static str>> =
        std::sync::LazyLock::new(|| {
            BUILTIN_DOH_HOSTS
                .lines()
                .map(|line| line.split('#').next().unwrap_or_default().trim())
                .filter(|name| !name.is_empty())
                .collect()
        });
    std::iter::successors(Some(domain), |d| {
        d.split_once('.').map(|(_, parent)| parent)
    })
    .any(|suffix| HOSTS.contains(suffix))
}

/// How far ahead of this server's clock a phone's time may be and still be kept (5 min).
pub const CLOCK_SLACK_MS: i64 = 5 * 60_000;

/// The status report's `dns_upstream`, stored through the known words only: an integer `rev` and
/// `in_use` are needed, a time is kept when plausible (2000 up to [CLOCK_SLACK_MS] after [now_ms],
/// the report's arrival), `reason` when it is a known word on a `backup` report;
/// `unreachable_until_ms` only with `unreachable_since_ms`, and neither when it ends before it
/// starts (design 67). Never an address, whatever a launcher sends.
pub fn sanitize_dns_upstream(value: &serde_json::Value, now_ms: i64) -> Option<String> {
    const MIN_MS: i64 = 946_684_800_000; // 2000-01-01
    // A phone clock ahead of ours (qa-67-code L2): no time later than the report's arrival + 5 min.
    let max_ms = now_ms.saturating_add(CLOCK_SLACK_MS);
    let plausible = |key: &str| {
        value
            .get(key)
            .and_then(serde_json::Value::as_i64)
            .filter(|ms| (MIN_MS..=max_ms).contains(ms))
    };
    let rev = value.get("rev")?.as_i64()?;
    let in_use = value.get("in_use")?.as_str()?;
    if !matches!(in_use, "custom" | "backup" | "provider") {
        return None;
    }
    let reason = value
        .get("reason")
        .and_then(serde_json::Value::as_str)
        .filter(|word| in_use == "backup" && BACKUP_REASONS.contains(word))
        .map(str::to_string);
    // An end before its start is no outage at all (qa-67-code L3: never an ongoing one).
    let (unreachable_since_ms, unreachable_until_ms) = match (
        plausible("unreachable_since_ms"),
        plausible("unreachable_until_ms"),
    ) {
        (Some(since), Some(until)) if until < since => (None, None),
        (since, until) => (since, until.filter(|_| since.is_some())),
    };
    let state = DnsUpstreamState {
        rev,
        in_use: in_use.to_string(),
        since_ms: plausible("since_ms"),
        reason,
        unreachable_since_ms,
        unreachable_until_ms,
    };
    serde_json::to_string(&state).ok()
}

/// A stored report read back; `None` for nothing or anything [sanitize_dns_upstream] didn't write.
pub fn parse_dns_upstream(stored: Option<&str>) -> Option<DnsUpstreamState> {
    serde_json::from_str(stored?).ok()
}

/// Classifies `typed` (design 36 §2's table), or says why it can't be a server.
pub fn parse_dns_server(typed: &str) -> Result<DnsServer, Refusal> {
    let typed = typed.trim();
    if typed.is_empty() {
        return Err(Refusal::Empty);
    }
    if typed.len() > MAX_LEN {
        return Err(Refusal::TooLong);
    }
    if !typed.is_ascii() {
        return Err(Refusal::NotAscii);
    }
    if typed.chars().any(|c| c.is_whitespace() || c.is_control()) {
        return Err(Refusal::Spaces);
    }

    let (scheme, rest) = match split_scheme(typed) {
        Some((scheme, rest)) => (Some(scheme.to_ascii_lowercase()), rest),
        None => (None, typed),
    };
    match scheme.as_deref() {
        None | Some("https") | Some("tls") => {}
        Some("http") => return Err(Refusal::Plaintext),
        Some(_) => return Err(Refusal::OtherScheme),
    }

    // RFC 8484's URI template may trail a DoH URL (https://, or no scheme with a path); anything
    // else after ? or # is refused.
    let doh_shaped = match scheme.as_deref() {
        Some(scheme) => scheme == "https",
        None => rest.contains('/'),
    };
    let rest = match rest.strip_suffix("{?dns}") {
        Some(stripped) if doh_shaped => stripped,
        _ => rest,
    };
    if rest.contains(['?', '#']) {
        return Err(Refusal::QueryOrFragment);
    }
    let (authority, path) = match rest.find('/') {
        Some(at) => rest.split_at(at),
        None => (rest, ""),
    };
    if authority.contains('@') {
        return Err(Refusal::UserInfo);
    }
    let (host, port) = split_host_port(authority)?;

    let kind = match (scheme.as_deref(), path) {
        (Some("https"), _) => Kind::Doh,
        (Some(_), "" | "/") => Kind::Dot,
        (Some(_), _) => return Err(Refusal::PathOnDot),
        (None, "") if port == Some(443) => return Err(Refusal::DohPortWithoutScheme),
        (None, "") => Kind::Dot,
        (None, _) => Kind::Doh,
    };
    let path = match kind {
        Kind::Dot => String::new(),
        Kind::Doh if path.is_empty() || path == "/" => DEFAULT_PATH.to_string(),
        Kind::Doh => checked_path(path)?,
    };
    Ok(DnsServer {
        kind,
        host,
        port: port.unwrap_or(kind.default_port()),
        path,
    })
}

/// `scheme://rest` when `typed` starts with a URI scheme.
fn split_scheme(typed: &str) -> Option<(&str, &str)> {
    let (scheme, rest) = typed.split_once("://")?;
    let mut chars = scheme.chars();
    let starts = chars.next().is_some_and(|c| c.is_ascii_alphabetic());
    let rest_ok = chars.all(|c| c.is_ascii_alphanumeric() || matches!(c, '+' | '-' | '.'));
    (starts && rest_ok).then_some((scheme, rest))
}

/// The host (checked, canonical) and the port, if any. An unbracketed IPv6 is always the whole
/// address; a port after one needs brackets.
fn split_host_port(authority: &str) -> Result<(String, Option<u16>), Refusal> {
    if let Some(inner) = authority.strip_prefix('[') {
        let (address, after) = inner.split_once(']').ok_or(Refusal::NotAnAddress)?;
        let port = match after {
            "" => None,
            _ => Some(checked_port(
                after.strip_prefix(':').ok_or(Refusal::NotAnAddress)?,
            )?),
        };
        return Ok((checked_ipv6(address)?, port));
    }
    match authority.matches(':').count() {
        0 => Ok((checked_name(authority)?, None)),
        1 => {
            let (host, port) = authority.split_once(':').unwrap_or_default();
            let port = checked_port(port)?;
            Ok((checked_name(host)?, Some(port)))
        }
        _ => Ok((checked_ipv6(authority)?, None)),
    }
}

fn checked_port(port: &str) -> Result<u16, Refusal> {
    if port.is_empty() || port.len() > 5 || !port.bytes().all(|b| b.is_ascii_digit()) {
        return Err(Refusal::BadPort);
    }
    match port.parse::<u32>() {
        Ok(53 | 80) => Err(Refusal::UnencryptedPort),
        Ok(n @ 1..=65535) => Ok(n as u16),
        _ => Err(Refusal::BadPort),
    }
}

fn checked_ipv6(address: &str) -> Result<String, Refusal> {
    let ip: Ipv6Addr = address.parse().map_err(|_| Refusal::NotAnAddress)?;
    // `::ffff:127.0.0.1`, `::127.0.0.1` and NAT64's `64:ff9b::7f00:1` are judged as the IPv4
    // address they carry.
    let [a, b, c, d, e, f, g, h] = ip.segments();
    let carried = if [a, b, c, d, e, f] == [0x64, 0xff9b, 0, 0, 0, 0] {
        Some(Ipv4Addr::from((u32::from(g) << 16) | u32::from(h)))
    } else {
        ip.to_ipv4()
    };
    let never = match carried {
        Some(v4) => never_a_server_v4(v4),
        None => {
            ip.is_loopback()
                || ip.is_unspecified()
                || ip.is_multicast()
                || ip.segments()[0] & 0xffc0 == 0xfe80
        }
    };
    if never {
        return Err(Refusal::NeverAServer);
    }
    Ok(ip.to_string())
}

fn never_a_server_v4(ip: Ipv4Addr) -> bool {
    // 240/4 (reserved, `255.255.255.255` included) can't be a server either.
    ip.is_loopback() || ip.octets()[0] == 0 || ip.is_multicast() || ip.octets()[0] >= 240
}

/// A host name (LDH labels) or a dotted-quad IPv4, lowercased, one trailing dot stripped. A name
/// the phone would read as a number (`1.1`, `0x7f.1`, `010.0.0.1`, `dns.123`) is refused.
fn checked_name(host: &str) -> Result<String, Refusal> {
    let host = host.strip_suffix('.').unwrap_or(host).to_ascii_lowercase();
    if host.is_empty() || host.len() > 253 {
        return Err(Refusal::NotAnAddress);
    }
    let labels: Vec<&str> = host.split('.').collect();
    let ldh = |label: &&str| {
        (1..=63).contains(&label.len())
            && label
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'-')
            && !label.starts_with('-')
            && !label.ends_with('-')
    };
    if !labels.iter().all(ldh) {
        return Err(Refusal::NotAnAddress);
    }
    let numeric = |label: &str| {
        label.bytes().all(|b| b.is_ascii_digit())
            || label
                .strip_prefix("0x")
                .is_some_and(|hex| hex.bytes().all(|b| b.is_ascii_hexdigit()))
    };
    let last_is_digits = labels
        .last()
        .is_some_and(|l| l.bytes().all(|b| b.is_ascii_digit()));
    if last_is_digits || labels.iter().all(|l| numeric(l)) {
        let ip = dotted_quad(&labels).ok_or(Refusal::NotAnAddress)?;
        if never_a_server_v4(ip) {
            return Err(Refusal::NeverAServer);
        }
        return Ok(ip.to_string());
    }
    if host == "localhost" || host.ends_with(".localhost") {
        return Err(Refusal::NeverAServer);
    }
    Ok(host)
}

/// Exactly four decimal octets 0-255 without leading zeros.
fn dotted_quad(labels: &[&str]) -> Option<Ipv4Addr> {
    if labels.len() != 4 {
        return None;
    }
    let mut octets = [0u8; 4];
    for (octet, label) in octets.iter_mut().zip(labels) {
        if label.is_empty() || (label.len() > 1 && label.starts_with('0')) {
            return None;
        }
        *octet = label.parse().ok()?;
    }
    Some(Ipv4Addr::from(octets))
}

/// A DoH path: `A-Z a-z 0-9 - . _ ~ /` and `%XX`, at most 256 characters, kept as typed.
fn checked_path(path: &str) -> Result<String, Refusal> {
    if path.len() > MAX_PATH {
        return Err(Refusal::BadPath);
    }
    let bytes = path.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'%' if bytes.len() > i + 2
                && bytes[i + 1].is_ascii_hexdigit()
                && bytes[i + 2].is_ascii_hexdigit() =>
            {
                i += 3;
                continue;
            }
            b if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'.' | b'_' | b'~' | b'/') => {}
            _ => return Err(Refusal::BadPath),
        }
        i += 1;
    }
    Ok(path.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn server(typed: &str) -> DnsServer {
        parse_dns_server(typed).unwrap_or_else(|why| panic!("{typed}: {why:?}"))
    }

    /// Every row of design 36 §2's table, and the address kinds it allows.
    #[test]
    fn classifies() {
        use Kind::{Doh, Dot};
        let rows: &[(&str, Kind, &str, u16, &str)] = &[
            (
                "https://dns.adguard-dns.com/dns-query",
                Doh,
                "dns.adguard-dns.com",
                443,
                "/dns-query",
            ),
            (
                "https://dns.example/dns-query/abc123",
                Doh,
                "dns.example",
                443,
                "/dns-query/abc123",
            ),
            (
                "https://abc123.dns.example/dns-query",
                Doh,
                "abc123.dns.example",
                443,
                "/dns-query",
            ),
            ("https://dns.example", Doh, "dns.example", 443, "/dns-query"),
            (
                "https://dns.example/",
                Doh,
                "dns.example",
                443,
                "/dns-query",
            ),
            (
                "https://dns.example:8443/dns-query/abc",
                Doh,
                "dns.example",
                8443,
                "/dns-query/abc",
            ),
            (
                "https://dns.example/dns-query{?dns}",
                Doh,
                "dns.example",
                443,
                "/dns-query",
            ),
            (
                "https://[2606:4700:4700::1111]/dns-query",
                Doh,
                "2606:4700:4700::1111",
                443,
                "/dns-query",
            ),
            (
                "https://192.168.1.10/dns-query",
                Doh,
                "192.168.1.10",
                443,
                "/dns-query",
            ),
            (
                "dns.example/dns-query/abc",
                Doh,
                "dns.example",
                443,
                "/dns-query/abc",
            ),
            ("dns.example/", Doh, "dns.example", 443, "/dns-query"),
            ("dns.quad9.net", Dot, "dns.quad9.net", 853, ""),
            ("abc123.dns.example", Dot, "abc123.dns.example", 853, ""),
            ("pi.tail1234.ts.net", Dot, "pi.tail1234.ts.net", 853, ""),
            ("pi.home.arpa", Dot, "pi.home.arpa", 853, ""),
            ("dns.example:8853", Dot, "dns.example", 8853, ""),
            ("tls://dns.example:8853", Dot, "dns.example", 8853, ""),
            ("1.1.1.3", Dot, "1.1.1.3", 853, ""),
            ("192.168.1.10", Dot, "192.168.1.10", 853, ""),
            ("100.101.102.103", Dot, "100.101.102.103", 853, ""),
            ("2620:fe::fe", Dot, "2620:fe::fe", 853, ""),
            ("[2620:fe::fe]:853", Dot, "2620:fe::fe", 853, ""),
            // An unbracketed IPv6 is the whole address, never an address and a port.
            ("2620:fe::fe:853", Dot, "2620:fe::fe:853", 853, ""),
            // Case, a trailing dot, spaces around, the scheme's case, private and tailnet ranges.
            (
                " HTTPS://DNS.Example./dns-query ",
                Doh,
                "dns.example",
                443,
                "/dns-query",
            ),
            ("tls://10.0.0.5", Dot, "10.0.0.5", 853, ""),
            ("fd7a:115c:a1e0::1", Dot, "fd7a:115c:a1e0::1", 853, ""),
            ("fc00::53", Dot, "fc00::53", 853, ""),
            ("64:ff9b::101:103", Dot, "64:ff9b::101:103", 853, ""),
            ("pi", Dot, "pi", 853, ""),
            ("router.local", Dot, "router.local", 853, ""),
            ("dns.internal", Dot, "dns.internal", 853, ""),
            (
                "https://dns.example/a%2Fb_c~d.e",
                Doh,
                "dns.example",
                443,
                "/a%2Fb_c~d.e",
            ),
        ];
        for (typed, kind, host, port, path) in rows {
            let parsed = server(typed);
            assert_eq!(
                (
                    parsed.kind,
                    parsed.host.as_str(),
                    parsed.port,
                    parsed.path.as_str()
                ),
                (*kind, *host, *port, *path),
                "{typed}"
            );
        }
    }

    /// Every refusal of design 36 §2.
    #[test]
    fn refuses() {
        use Refusal::*;
        let long_path = format!("https://dns.example/{}", "a".repeat(256));
        let long_value = format!("https://dns.example/{}", "a/".repeat(260));
        let long_label = format!("{}.example", "a".repeat(64));
        let long_name = format!("{}example", "abcdefghi.".repeat(25));
        let rows: Vec<(&str, Refusal)> = vec![
            ("", Empty),
            ("   ", Empty),
            (&long_value, TooLong),
            ("dns .example", Spaces),
            ("dns.exa\tmple", Spaces),
            ("dns.exa\u{7}mple", Spaces),
            ("dns.exämple", NotAscii),
            ("http://dns.example/dns-query", Plaintext),
            ("HTTP://dns.example", Plaintext),
            ("quic://dns.example", OtherScheme),
            ("h3://dns.example", OtherScheme),
            ("sdns://AgcAAAAAAAAA", OtherScheme),
            ("dns.example:443", DohPortWithoutScheme),
            ("https://user:pw@dns.example/dns-query", UserInfo),
            ("tls://user@dns.example", UserInfo),
            ("https://dns.example/dns-query?dns=AAAB", QueryOrFragment),
            ("https://dns.example/dns-query#x", QueryOrFragment),
            ("tls://dns.example/dns-query", PathOnDot),
            ("dns.example:53", UnencryptedPort),
            ("https://dns.example:80/dns-query", UnencryptedPort),
            ("tls://9.9.9.9:53", UnencryptedPort),
            ("dns.example:0", BadPort),
            ("dns.example:65536", BadPort),
            ("dns.example:", BadPort),
            ("dns.example:85x", BadPort),
            ("[2620:fe::fe]:99999", BadPort),
            ("https://dns.example/dns query", Spaces),
            ("https://dns.example/dns-query/a+b", BadPath),
            ("https://dns.example/%zz", BadPath),
            ("https://dns.example/%2", BadPath),
            (&long_path, BadPath),
            ("dns_server.example", NotAnAddress),
            ("-dns.example", NotAnAddress),
            ("dns..example", NotAnAddress),
            (&long_label, NotAnAddress),
            (&long_name, NotAnAddress),
            ("https:///dns-query", NotAnAddress),
            ("1.1", NotAnAddress),
            ("127.1", NotAnAddress),
            ("dns.123", NotAnAddress),
            ("010.0.0.1", NotAnAddress),
            ("0x7f.0.0.1", NotAnAddress),
            ("0x7f000001", NotAnAddress),
            ("1.2.3.256", NotAnAddress),
            ("fe80::1%eth0", NotAnAddress),
            ("[fe80::1%25eth0]", NotAnAddress),
            ("[2620:fe::fe", NotAnAddress),
            ("[2620:fe::fe]853", NotAnAddress),
            ("localhost", NeverAServer),
            ("LOCALHOST.", NeverAServer),
            ("dns.localhost", NeverAServer),
            ("127.0.0.1", NeverAServer),
            ("tls://127.0.0.53:853", NeverAServer),
            ("::1", NeverAServer),
            ("::ffff:127.0.0.1", NeverAServer),
            ("[::ffff:7f00:1]", NeverAServer),
            ("0.0.0.0", NeverAServer),
            ("::", NeverAServer),
            ("224.0.0.1", NeverAServer),
            ("239.255.255.250", NeverAServer),
            ("255.255.255.255", NeverAServer),
            ("240.0.0.1", NeverAServer),
            ("64:ff9b::7f00:1", NeverAServer),
            ("tls://dns.example{?dns}", QueryOrFragment),
            ("dns.example{?dns}", QueryOrFragment),
            ("ff02::1", NeverAServer),
            ("fe80::1", NeverAServer),
            ("https://[fe80::1]/dns-query", NeverAServer),
        ];
        for (typed, why) in rows {
            assert_eq!(parse_dns_server(typed), Err(why), "{typed:?}");
        }
    }

    #[test]
    fn normalized_parses_to_itself() {
        for (typed, normalized) in [
            (
                "https://dns.example/dns-query",
                "https://dns.example/dns-query",
            ),
            ("dns.example/", "https://dns.example/dns-query"),
            ("HTTPS://DNS.EXAMPLE:443", "https://dns.example/dns-query"),
            (
                "https://dns.example:8443/q/abc",
                "https://dns.example:8443/q/abc",
            ),
            (
                "https://[2606:4700:4700:0::1111]/dns-query",
                "https://[2606:4700:4700::1111]/dns-query",
            ),
            ("9.9.9.9", "tls://9.9.9.9"),
            ("tls://9.9.9.9:853", "tls://9.9.9.9"),
            ("abc123.dns.example.", "tls://abc123.dns.example"),
            ("dns.example:8853", "tls://dns.example:8853"),
            ("[2620:fe::fe]:8853", "tls://[2620:fe::fe]:8853"),
            ("2620:00fe::00fe", "tls://[2620:fe::fe]"),
        ] {
            let parsed = server(typed);
            assert_eq!(parsed.normalized(), normalized, "{typed}");
            assert_eq!(server(normalized), parsed, "{normalized}");
        }
        // Equality is the normalized form's.
        assert!(server("9.9.9.9").same_as(&server("tls://9.9.9.9:853")));
        assert!(!server("9.9.9.9").same_as(&server("https://9.9.9.9/dns-query")));
    }

    #[test]
    fn hint_hides_the_path_and_the_first_label() {
        for (typed, hint) in [
            (
                "https://dns.example/dns-query/abc123",
                "dns.example · DNS-over-HTTPS",
            ),
            (
                "https://abc123.dns.example/dns-query",
                "…dns.example · DNS-over-HTTPS",
            ),
            (
                "https://abc123.dns.example:8443/q",
                "…dns.example · DNS-over-HTTPS",
            ),
            ("abc123.dns.example", "…dns.example · DNS-over-TLS"),
            ("pi", "pi · DNS-over-TLS"),
            ("1.1.1.3", "1.1.1.3 · DNS-over-TLS"),
            (
                "https://[2606:4700:4700::1111]/x",
                "2606:4700:4700::1111 · DNS-over-HTTPS",
            ),
        ] {
            let shown = server(typed).hint();
            assert_eq!(shown, hint, "{typed}");
            assert!(!shown.contains("abc123"), "{shown}");
        }
    }

    #[test]
    fn the_wire_targets_and_the_pair() {
        let up = upstream_of("https://dns.example/dns-query/abc123", "1.1.1.3", 4).unwrap();
        assert_eq!(
            serde_json::to_value(&up).unwrap(),
            serde_json::json!({"kind": "doh", "url": "https://dns.example/dns-query/abc123",
                "rev": 4, "backup": {"kind": "dot", "host": "1.1.1.3", "port": 853}})
        );
        assert_eq!(upstream_of("", "1.1.1.3", 1), None);
        assert_eq!(upstream_of("9.9.9.9", "", 1), None);
        assert_eq!(upstream_of("9.9.9.9", "tls://9.9.9.9:853", 1), None);
        assert_eq!(
            event_detail("custom", "https://abc123.dns.example/dns-query", "1.1.1.3"),
            r#"{"backup_kind":"dot","kind":"doh","server":"custom"}"#
        );
        assert_eq!(event_detail("quad9", "", ""), r#"{"server":"quad9"}"#);
    }

    #[test]
    fn doh_hosts_match_the_launchers_asset() {
        let asset = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../launcher/app/src/main/assets/dns/doh_hosts.txt");
        let asset =
            std::fs::read_to_string(&asset).unwrap_or_else(|e| panic!("{}: {e}", asset.display()));
        assert_eq!(asset, BUILTIN_DOH_HOSTS, "testdata/doh_hosts.txt differs");
        for host in ["dns.google", "chrome.cloudflare-dns.com", "doh.opendns.com"] {
            assert!(is_builtin_doh_host(host), "{host}");
        }
        for host in [
            "google",
            "notdns.google.com",
            "doh.wa.gov",
            "example.com",
            "",
        ] {
            assert!(!is_builtin_doh_host(host), "{host}");
        }
    }

    #[test]
    fn sanitize_dns_upstream_keeps_known_words_only() {
        use serde_json::json;
        // The report arrives a minute after the contract's times.
        let now = 1_792_312_460_000_i64;
        let kept = |value: serde_json::Value| {
            parse_dns_upstream(sanitize_dns_upstream(&value, now).as_deref())
        };
        let state = |rev, in_use: &str, since_ms, reason: Option<&str>| DnsUpstreamState {
            rev,
            in_use: in_use.to_string(),
            since_ms,
            reason: reason.map(str::to_string),
            unreachable_since_ms: None,
            unreachable_until_ms: None,
        };
        assert_eq!(
            kept(
                json!({"rev": 4, "in_use": "backup", "since_ms": 1792312345000_i64,
                "reason": "tls", "host": "abc123.dns.example"})
            ),
            Some(state(4, "backup", Some(1792312345000), Some("tls")))
        );
        assert!(
            !sanitize_dns_upstream(
                &json!({"rev": 1, "in_use": "custom", "url": "https://x.example"}),
                now
            )
            .unwrap()
            .contains("example")
        );
        // A reason only with backup and only a known word; a since_ms only when plausible.
        assert_eq!(
            kept(json!({"rev": 2, "in_use": "custom", "since_ms": 5, "reason": "tls"})),
            Some(state(2, "custom", None, None))
        );
        assert_eq!(
            kept(
                json!({"rev": 2, "in_use": "backup", "since_ms": "x", "reason": "abc123.dns.example"})
            ),
            Some(state(2, "backup", None, None))
        );
        // Design 67: provider mode (its reason dropped) and the outage's two times - an end only
        // with a kept start, neither when it ends before it starts (L3), none later than the
        // arrival + 5 min (L2: a phone clock ahead).
        let outage = |since, until| DnsUpstreamState {
            unreachable_since_ms: since,
            unreachable_until_ms: until,
            ..state(0, "provider", None, None)
        };
        let t = 1_792_312_400_000_i64;
        for (sent, stored) in [
            (json!([t, t + 60_000]), outage(Some(t), Some(t + 60_000))),
            (json!([t, null]), outage(Some(t), None)),
            (json!([t, t - 1]), outage(None, None)),
            (
                json!([t, now + CLOCK_SLACK_MS]),
                outage(Some(t), Some(now + CLOCK_SLACK_MS)),
            ),
            (json!([t, now + CLOCK_SLACK_MS + 1]), outage(Some(t), None)),
            (json!([now + 3 * 3_600_000, null]), outage(None, None)),
            (json!([5, t]), outage(None, None)),
            (json!([null, t]), outage(None, None)),
            (json!(["10:42", "11:05"]), outage(None, None)),
        ] {
            assert_eq!(
                kept(json!({"rev": 0, "in_use": "provider", "reason": "tls",
                    "unreachable_since_ms": sent[0], "unreachable_until_ms": sent[1]})),
                Some(stored),
                "{sent}"
            );
        }
        // A report stored before design 67 reads back without them.
        assert_eq!(
            parse_dns_upstream(Some(
                r#"{"rev":2,"in_use":"custom","since_ms":null,"reason":null}"#
            )),
            Some(state(2, "custom", None, None))
        );
        for junk in [
            json!({"in_use": "custom"}),
            json!({"rev": "4", "in_use": "custom"}),
            json!({"rev": 1.5, "in_use": "custom"}),
            json!({"rev": 4, "in_use": "cloudflare"}),
            json!({"rev": 4}),
            json!("custom"),
            json!(null),
        ] {
            assert_eq!(sanitize_dns_upstream(&junk, now), None, "{junk}");
        }
        assert_eq!(parse_dns_upstream(Some("{\"rev\":1}")), None);
        assert_eq!(parse_dns_upstream(None), None);
    }
}
