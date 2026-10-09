//! Who is asking, and where this server listens (design 22 §2).
//!
//! - [NetConfig]: the listener settings from the environment (`BIND_ADDR`, `DEVICE_BIND_ADDR`,
//!   `ADMIN_DEVICE_API`, `ADMIN_PUBLIC`, `TRUSTED_PROXIES`, ...), read once at startup.
//! - [NetConfig::client]: the client IP. `X-Forwarded-For` is believed only from a peer in
//!   `TRUSTED_PROXIES`, walked from the right; from anyone else it is ignored (§0 #1: the old
//!   `client_ip` took the left-most entry from any peer, which nginx and a non-loopback bind let a
//!   client forge).
//! - [limit_key]: what limits and bans count by - an IPv4 address, or an IPv6 /64 (one home gets
//!   a whole /64, so a single IPv6 address is no key at all).

use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

use axum::http::HeaderMap;

/// An address range: an IP and a prefix length ("100.64.0.0/10", "::1").
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct IpNet {
    addr: IpAddr,
    prefix: u8,
}

impl IpNet {
    pub const fn v4(a: u8, b: u8, c: u8, d: u8, prefix: u8) -> IpNet {
        IpNet {
            addr: IpAddr::V4(Ipv4Addr::new(a, b, c, d)),
            prefix,
        }
    }

    pub const fn v6(segments: [u16; 8], prefix: u8) -> IpNet {
        let [a, b, c, d, e, f, g, h] = segments;
        IpNet {
            addr: IpAddr::V6(Ipv6Addr::new(a, b, c, d, e, f, g, h)),
            prefix,
        }
    }

    /// "1.2.3.4", "10.0.0.0/8", "::1", "fd00::/8". Host bits set under the prefix are allowed and
    /// ignored ("172.18.0.1/16" is the whole /16).
    pub fn parse(text: &str) -> Option<IpNet> {
        let text = text.trim();
        let (addr, prefix) = match text.split_once('/') {
            Some((addr, prefix)) => (addr, Some(prefix.parse::<u8>().ok()?)),
            None => (text, None),
        };
        let addr = addr.parse::<IpAddr>().ok()?.to_canonical();
        let max = if addr.is_ipv4() { 32 } else { 128 };
        let prefix = prefix.unwrap_or(max);
        (prefix <= max).then_some(IpNet { addr, prefix })
    }

    pub fn contains(&self, ip: IpAddr) -> bool {
        match (self.addr, ip.to_canonical()) {
            (IpAddr::V4(net), IpAddr::V4(ip)) => {
                let mask = u32::MAX
                    .checked_shl(32 - u32::from(self.prefix))
                    .unwrap_or(0);
                u32::from(net) & mask == u32::from(ip) & mask
            }
            (IpAddr::V6(net), IpAddr::V6(ip)) => {
                let mask = u128::MAX
                    .checked_shl(128 - u32::from(self.prefix))
                    .unwrap_or(0);
                u128::from(net) & mask == u128::from(ip) & mask
            }
            _ => false,
        }
    }
}

impl std::fmt::Display for IpNet {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let max = if self.addr.is_ipv4() { 32 } else { 128 };
        if self.prefix == max {
            write!(f, "{}", self.addr)
        } else {
            write!(f, "{}/{}", self.addr, self.prefix)
        }
    }
}

/// Tailscale's address ranges: the CGNAT block it hands out IPv4 addresses from, and its IPv6 ULA
/// prefix. 100.64.0.0/10 is also carrier CGNAT space - such a client could only arrive through a
/// port forward on a CGNAT line, where public exposure doesn't work in the first place (§2).
pub const TAILNET: [IpNet; 2] = [
    IpNet::v4(100, 64, 0, 0, 10),
    IpNet::v6([0xfd7a, 0x115c, 0xa1e0, 0, 0, 0, 0, 0], 48),
];

pub fn is_tailnet(ip: IpAddr) -> bool {
    TAILNET.iter().any(|net| net.contains(ip))
}

pub fn is_loopback(ip: IpAddr) -> bool {
    ip.to_canonical().is_loopback()
}

/// The key limits and bans count by: the IPv4 address itself, or the IPv6 /64 written as
/// "2001:db8:1:2::/64".
pub fn limit_key(ip: IpAddr) -> String {
    match ip.to_canonical() {
        IpAddr::V4(v4) => v4.to_string(),
        IpAddr::V6(v6) => {
            let network = u128::from(v6) & (u128::MAX << 64);
            format!("{}/64", Ipv6Addr::from(network))
        }
    }
}

/// How a request reached us: the resolved client, the TCP peer, and whether a trusted peer said
/// who the client is (`X-Forwarded-For`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Client {
    pub ip: IpAddr,
    pub peer: IpAddr,
    /// The peer is a trusted proxy and sent `X-Forwarded-For` (whether or not it named an
    /// untrusted client).
    pub forwarded: bool,
    /// An untrusted peer sent `X-Forwarded-For` - a proxy that isn't in `TRUSTED_PROXIES`.
    pub untrusted_forward: bool,
}

/// Every `X-Forwarded-For` entry, in order (several headers are one list). `None` when there is
/// no header; `Some(Err)` when an entry isn't an address.
fn forwarded_for(headers: &HeaderMap) -> Option<Result<Vec<IpAddr>, ()>> {
    let mut values = headers.get_all("x-forwarded-for").iter().peekable();
    values.peek()?;
    let mut list = Vec::new();
    for value in values {
        let Ok(text) = value.to_str() else {
            return Some(Err(()));
        };
        for entry in text.split(',') {
            match parse_forwarded_entry(entry.trim()) {
                Some(ip) => list.push(ip),
                None => return Some(Err(())),
            }
        }
    }
    Some(Ok(list))
}

/// "1.2.3.4", "2001:db8::1", or with a port ("1.2.3.4:5678", "[2001:db8::1]:443") as some proxies
/// write it.
fn parse_forwarded_entry(entry: &str) -> Option<IpAddr> {
    if let Ok(ip) = entry.parse::<IpAddr>() {
        return Some(ip.to_canonical());
    }
    if let Ok(addr) = entry.parse::<std::net::SocketAddr>() {
        return Some(addr.ip().to_canonical());
    }
    entry
        .strip_prefix('[')
        .and_then(|rest| rest.strip_suffix(']'))
        .and_then(|inner| inner.parse::<IpAddr>().ok())
        .map(|ip| ip.to_canonical())
}

pub const DEFAULT_BIND_ADDR: &str = "127.0.0.1:3100";

/// The listener settings (design 22 §2), read once at startup and kept in `AppState.net`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NetConfig {
    /// `BIND_ADDR`: the admin listener (the PWA, and the device API while `admin_device_api`).
    pub bind_addr: String,
    /// `DEVICE_BIND_ADDR`: the phone listener, which serves only the device API and `/healthz`.
    /// Unset = no second listener.
    pub device_bind_addr: Option<String>,
    /// `ADMIN_DEVICE_API` (default on): the admin listener also serves `/api/devices/*`, the URLs
    /// phones set up before the phone listener use. Off once every phone has moved.
    pub admin_device_api: bool,
    /// `ADMIN_PUBLIC` (default off): the admin listener answers every client, not only loopback
    /// and tailnet ones - exposure (b), where the PWA rests on password + TOTP alone.
    pub admin_public: bool,
    /// `TRUSTED_PROXIES` (default `127.0.0.1,::1`): peers whose `X-Forwarded-For` is believed.
    pub trusted_proxies: Vec<IpNet>,
    /// `ADMIN_TAILSCALE_USERS`: when set, every admin request needs a tailnet client IP and a
    /// `Tailscale-User-Login` in this list (only behind `tailscale serve`, which sets the header).
    pub admin_tailscale_users: Option<Vec<String>>,
}

impl Default for NetConfig {
    fn default() -> Self {
        Self::from_vars(&HashMap::new())
    }
}

/// "on"/"off" and the usual spellings; anything else is `None` (logged, default used).
fn parse_switch(value: &str) -> Option<bool> {
    match value.to_ascii_lowercase().as_str() {
        "on" | "true" | "1" | "yes" => Some(true),
        "off" | "false" | "0" | "no" => Some(false),
        _ => None,
    }
}

impl NetConfig {
    pub fn from_env() -> Self {
        Self::from_vars(&std::env::vars().collect())
    }

    /// The parser, on a plain map (tests don't touch the process environment). An empty value
    /// counts as unset; an invalid one is logged and the default applies - except
    /// `TRUSTED_PROXIES`, where an entry that isn't an address is dropped (logged), never widened.
    pub fn from_vars(vars: &HashMap<String, String>) -> Self {
        let get = |key: &str| {
            vars.get(key)
                .map(|v| v.trim())
                .filter(|v| !v.is_empty())
                .map(str::to_string)
        };
        let switch = |key: &str, default: bool| match get(key) {
            None => default,
            Some(value) => parse_switch(&value).unwrap_or_else(|| {
                tracing::error!("{key}={value:?} is not on or off - using the default");
                default
            }),
        };
        let trusted_proxies = match get("TRUSTED_PROXIES") {
            None => vec![
                IpNet::v4(127, 0, 0, 1, 32),
                IpNet::v6([0, 0, 0, 0, 0, 0, 0, 1], 128),
            ],
            Some(list) => list
                .split(',')
                .map(str::trim)
                .filter(|entry| !entry.is_empty())
                .filter_map(|entry| {
                    let net = IpNet::parse(entry);
                    if net.is_none() {
                        tracing::error!(
                            "TRUSTED_PROXIES: {entry:?} is not an address or range - ignored"
                        );
                    }
                    net
                })
                .collect(),
        };
        let admin_tailscale_users = get("ADMIN_TAILSCALE_USERS").map(|list| {
            list.split(',')
                .map(|login| login.trim().to_string())
                .filter(|login| !login.is_empty())
                .collect::<Vec<_>>()
        });
        NetConfig {
            bind_addr: get("BIND_ADDR").unwrap_or_else(|| DEFAULT_BIND_ADDR.to_string()),
            device_bind_addr: get("DEVICE_BIND_ADDR"),
            admin_device_api: switch("ADMIN_DEVICE_API", true),
            admin_public: switch("ADMIN_PUBLIC", false),
            trusted_proxies,
            // An empty list would refuse everyone; unset means "don't check".
            admin_tailscale_users: admin_tailscale_users.filter(|users| !users.is_empty()),
        }
    }

    pub fn is_trusted(&self, ip: IpAddr) -> bool {
        self.trusted_proxies.iter().any(|net| net.contains(ip))
    }

    /// The client behind `peer` (§2 "Trusted proxies"): from a trusted peer, `X-Forwarded-For`
    /// is walked from the right, skipping trusted entries, and the first other one is the client
    /// (all trusted: the left-most). A malformed header means the peer itself. From any other
    /// peer the header is ignored.
    pub fn client(&self, headers: &HeaderMap, peer: IpAddr) -> Client {
        let peer = peer.to_canonical();
        let header = forwarded_for(headers);
        if !self.is_trusted(peer) {
            return Client {
                ip: peer,
                peer,
                forwarded: false,
                untrusted_forward: header.is_some(),
            };
        }
        let ip = match &header {
            None | Some(Err(())) => peer,
            Some(Ok(list)) => list
                .iter()
                .rev()
                .find(|ip| !self.is_trusted(**ip))
                .or(list.first())
                .copied()
                .unwrap_or(peer),
        };
        Client {
            ip,
            peer,
            forwarded: header.is_some(),
            untrusted_forward: false,
        }
    }

    /// The one line `main` logs about how this server is exposed.
    pub fn exposure_line(&self) -> String {
        let admin = if self.admin_public {
            "answers every client (ADMIN_PUBLIC=on: the PWA rests on password + TOTP alone)"
        } else {
            "answers loopback and tailnet clients only"
        };
        let device_api = if self.admin_device_api {
            "with the device API"
        } else {
            "without the device API (ADMIN_DEVICE_API=off)"
        };
        let phones = match &self.device_bind_addr {
            Some(addr) => format!("phone listener on {addr} (device API only)"),
            None => "no phone listener (DEVICE_BIND_ADDR unset)".to_string(),
        };
        let trusted = self
            .trusted_proxies
            .iter()
            .map(ToString::to_string)
            .collect::<Vec<_>>()
            .join(", ");
        let users = match &self.admin_tailscale_users {
            Some(users) => format!("; admin only for Tailscale users {}", users.join(", ")),
            None => String::new(),
        };
        format!(
            "admin listener on {} {admin}, {device_api}; {phones}; trusted proxies: {}{users}",
            self.bind_addr,
            if trusted.is_empty() { "none" } else { &trusted },
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::HeaderValue;

    fn ip(text: &str) -> IpAddr {
        text.parse().unwrap()
    }

    fn xff(values: &[&str]) -> HeaderMap {
        let mut headers = HeaderMap::new();
        for value in values {
            headers.append("x-forwarded-for", HeaderValue::from_str(value).unwrap());
        }
        headers
    }

    fn vars(pairs: &[(&str, &str)]) -> HashMap<String, String> {
        pairs
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    }

    #[test]
    fn ranges_parse_and_match() {
        let net = IpNet::parse("172.18.0.1/16").unwrap();
        assert!(net.contains(ip("172.18.255.3")));
        assert!(!net.contains(ip("172.19.0.1")));
        assert!(IpNet::parse("::1").unwrap().contains(ip("::1")));
        assert!(!IpNet::parse("::1").unwrap().contains(ip("127.0.0.1")));
        assert!(IpNet::parse("0.0.0.0/0").unwrap().contains(ip("8.8.8.8")));
        // An IPv4-mapped IPv6 peer (a dual-stack bind) is its IPv4 address.
        assert!(
            IpNet::parse("127.0.0.1")
                .unwrap()
                .contains(ip("::ffff:127.0.0.1"))
        );
        for bad in [
            "",
            "1.2.3",
            "1.2.3.4/33",
            "::/129",
            "localhost",
            "1.2.3.4/x",
        ] {
            assert_eq!(IpNet::parse(bad), None, "{bad:?}");
        }
        assert!(is_tailnet(ip("100.101.102.103")));
        assert!(is_tailnet(ip("fd7a:115c:a1e0::1")));
        assert!(!is_tailnet(ip("100.128.0.1")));
        assert!(!is_tailnet(ip("fd7a:115c:a1e1::1")));
    }

    #[test]
    fn limit_keys_are_ipv4_addresses_and_ipv6_64s() {
        assert_eq!(limit_key(ip("203.0.113.7")), "203.0.113.7");
        assert_eq!(
            limit_key(ip("2001:db8:1:2:aaaa:bbbb:cccc:dddd")),
            "2001:db8:1:2::/64"
        );
        assert_eq!(
            limit_key(ip("2001:db8:1:2::1")),
            limit_key(ip("2001:db8:1:2:ffff::9"))
        );
        assert_ne!(
            limit_key(ip("2001:db8:1:2::1")),
            limit_key(ip("2001:db8:1:3::1"))
        );
        assert_eq!(limit_key(ip("::ffff:203.0.113.7")), "203.0.113.7");
    }

    #[test]
    fn forwarded_for_is_believed_only_from_trusted_peers() {
        let net = NetConfig::default();
        // An untrusted peer with XFF: the peer.
        let client = net.client(&xff(&["1.2.3.4"]), ip("198.51.100.9"));
        assert_eq!(client.ip, ip("198.51.100.9"));
        assert!(client.untrusted_forward && !client.forwarded);
        // A trusted peer: the right-most untrusted entry, not the left-most (forgeable) one.
        let client = net.client(&xff(&["6.6.6.6, 203.0.113.7"]), ip("127.0.0.1"));
        assert_eq!(client.ip, ip("203.0.113.7"));
        assert!(client.forwarded);
        // Trusted entries are skipped; several headers are one list.
        let client = net.client(&xff(&["6.6.6.6", "203.0.113.7, ::1"]), ip("::1"));
        assert_eq!(client.ip, ip("203.0.113.7"));
        // All trusted: the left-most.
        let client = net.client(&xff(&["127.0.0.1"]), ip("127.0.0.1"));
        assert_eq!(client.ip, ip("127.0.0.1"));
        // A malformed header means the peer itself.
        let client = net.client(&xff(&["203.0.113.7, nonsense"]), ip("127.0.0.1"));
        assert_eq!(client.ip, ip("127.0.0.1"));
        // Ports and brackets as some proxies write them.
        let client = net.client(&xff(&["[2001:db8::5]:443"]), ip("127.0.0.1"));
        assert_eq!(client.ip, ip("2001:db8::5"));
        let client = net.client(&xff(&["203.0.113.7:5678"]), ip("127.0.0.1"));
        assert_eq!(client.ip, ip("203.0.113.7"));
        // No header from a trusted peer: the peer, not forwarded (a local tool).
        let client = net.client(&HeaderMap::new(), ip("127.0.0.1"));
        assert_eq!(client.ip, ip("127.0.0.1"));
        assert!(!client.forwarded);
    }

    #[test]
    fn trusted_proxies_come_from_the_environment() {
        let net = NetConfig::from_vars(&vars(&[(
            "TRUSTED_PROXIES",
            "172.30.0.1, 172.30.0.0/24,not-an-ip",
        )]));
        assert_eq!(net.trusted_proxies.len(), 2);
        assert!(net.is_trusted(ip("172.30.0.20")));
        // The default (loopback) is replaced, not extended.
        assert!(!net.is_trusted(ip("127.0.0.1")));
        let client = net.client(&xff(&["203.0.113.7"]), ip("172.30.0.20"));
        assert_eq!(client.ip, ip("203.0.113.7"));
    }

    #[test]
    fn defaults_and_switches() {
        let net = NetConfig::default();
        assert_eq!(net.bind_addr, "127.0.0.1:3100");
        assert_eq!(net.device_bind_addr, None);
        assert!(net.admin_device_api);
        assert!(!net.admin_public);
        assert_eq!(net.admin_tailscale_users, None);
        let net = NetConfig::from_vars(&vars(&[
            ("DEVICE_BIND_ADDR", "127.0.0.1:3101"),
            ("ADMIN_DEVICE_API", "off"),
            ("ADMIN_PUBLIC", "On"),
            ("ADMIN_TAILSCALE_USERS", "parent@example.com, other@github"),
        ]));
        assert_eq!(net.device_bind_addr.as_deref(), Some("127.0.0.1:3101"));
        assert!(!net.admin_device_api);
        assert!(net.admin_public);
        assert_eq!(
            net.admin_tailscale_users,
            Some(vec![
                "parent@example.com".to_string(),
                "other@github".to_string()
            ])
        );
        // Invalid values keep the defaults (never "public").
        let net = NetConfig::from_vars(&vars(&[
            ("ADMIN_PUBLIC", "maybe"),
            ("ADMIN_TAILSCALE_USERS", " , "),
        ]));
        assert!(!net.admin_public);
        assert_eq!(net.admin_tailscale_users, None);
    }
}
