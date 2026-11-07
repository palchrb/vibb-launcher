//! Values that differ between this fork's deployments and upstream's - which GitHub repo this
//! server updates itself from, and which launcher build the provisioning QR installs. Read once
//! at startup from env vars (`.env` is loaded by `dotenvy` in `main()`), stored in
//! `AppState.config`.
//!
//! Every default points at our own (`palchrb/vibb-launcher`) monorepo, never at upstream's. That
//! one repo publishes both components: the launcher from `launcher-v*` tags (the stable ones become
//! GitHub's "latest" release) and this server from `server-v*` tags (never "latest"). The one value with
//! no default at all is the launcher's signing-certificate checksum: it depends on a release key
//! only the person running this server has, and a wrong default would make the provisioning QR
//! install somebody else's build - so while it's unset, `/devices/{id}/provision` shows a banner
//! instead of a QR code (see `handlers::provisioning`).

use std::collections::HashMap;

pub const DEFAULT_SERVER_RELEASE_REPO: &str = "palchrb/vibb-launcher";
/// Tag prefix of this server's own releases in [`DEFAULT_SERVER_RELEASE_REPO`] - the in-app
/// update check only ever considers `server-vX.Y.Z` releases (`handlers::system_update`).
pub const SERVER_RELEASE_TAG_PREFIX: &str = "server-v";
/// Tag prefix of the Vibb music app's releases in the same repo (design 21). A catalog row without
/// a `release_tag_prefix` never picks one of them, so a launcher row can't install vibb-music.apk.
pub const MUSIC_RELEASE_TAG_PREFIX: &str = "music-v";
/// Before the monorepo (2026-10), the server and the launcher had a repo each. Installs from then
/// may still carry these values in their `.env` (install.sh wrote `SERVER_RELEASE_REPO` there);
/// they are read as the new defaults, so an existing install follows the move with no edit.
pub const LEGACY_SERVER_RELEASE_REPO: &str = "palchrb/kid-phone-server";
pub const LEGACY_LAUNCHER_APK_URL: &str =
    "https://github.com/palchrb/kids-launcher-mdm/releases/latest/download/kids-launcher-mdm.apk";
/// The launcher's applicationId is `me.vibb.launcher` since 2026-10-06; its Kotlin namespace (the
/// class names) stayed `com.kidslauncher.mdm`.
pub const DEFAULT_LAUNCHER_ADMIN_COMPONENT: &str =
    "me.vibb.launcher/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver";
/// The default before the package rename; `install.sh` wrote it into `.env`, so it is read as
/// today's (a QR with the old package would install a build whose admin isn't there).
pub const LEGACY_LAUNCHER_ADMIN_COMPONENT: &str =
    "com.kidslauncher.mdm/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver";
/// `releases/latest` only ever serves a normal (non-prerelease) release, only launcher releases
/// are ever marked latest (server releases are published with makeLatest false), and the
/// launcher's release CI always attaches the APK under this one stable asset name - so this URL
/// keeps pointing at the newest launcher release with nothing to update per release.
pub const DEFAULT_LAUNCHER_APK_URL: &str =
    "https://github.com/palchrb/vibb-launcher/releases/latest/download/kids-launcher-mdm.apk";

/// Maps an old default to today's (see [`LEGACY_SERVER_RELEASE_REPO`],
/// [`LEGACY_LAUNCHER_ADMIN_COMPONENT`]).
fn migrate_legacy(key: &str, value: String, legacy: &str, current: &str) -> String {
    if value == legacy {
        tracing::info!("{key}={legacy} is an old default - using {current} instead");
        current.to_string()
    } else {
        value
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ForkConfig {
    /// `owner/repo` this server checks for its own new releases (`handlers::system_update`) and
    /// names in Software Updates' `update.sh` command (`handlers::updates::update_command`).
    pub server_release_repo: String,
    /// `PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME` in the provisioning QR.
    pub launcher_admin_component: String,
    /// `PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION` in the provisioning QR.
    pub launcher_apk_url: String,
    /// `PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM`: SHA-256 of the launcher's signing
    /// certificate, base64url without padding (43 characters). `None` until configured - see
    /// the module doc comment and DEPLOY.md for how to compute it.
    pub launcher_signature_checksum: Option<String>,
    /// `PHONE_SERVER_URL`: the phones' server address in the setup QR when Settings › Phone Setup
    /// has none saved - the saved one always wins (`provisioning::server_url`). Checked as Phone
    /// Setup's field ([check_server_url]): a refused one is ignored (logged), a plain-http one to
    /// a Tailscale IP is taken with a warning in the log.
    pub phone_server_url: Option<String>,
    /// `PHONE_RECOVERY_URL` (design 76's fix round, H-1): the address a phone whose certificate check refuses this
    /// server can be pointed at - plain `http://` to a Tailscale IP literal only ([check_server_url]'s
    /// `TailnetHttp`; anything else is logged and ignored). Shown on Device › Advanced › Certificate check and sent
    /// to the phone in the policy's `server_tls.recovery_url`; unset = the server's own tailnet address on a
    /// listener that answers it, when it can tell (`server_tls::recovery_url`).
    pub phone_recovery_url: Option<String>,
    /// `SSE_KEEPALIVE_SECS` (5-240, default 240 since design 19, 120 before): how often the
    /// command stream sends a keepalive comment. Every phone holds the stream (no FCM); its read
    /// timeout is 300 s (launcher `SSE_READ_TIMEOUT_MS`, also in launchers already shipped), so
    /// the cap keeps a 60 s margin - a value at or above it would make every phone drop and
    /// reopen the stream every 5 minutes. Each keepalive wakes the phone's radio, so the longest
    /// safe interval is the default; `SSE_KEEPALIVE_SECS=120` in `.env` reverts it.
    pub sse_keepalive_secs: u64,
    /// Where the server's own update check asks GitHub (`handlers::system_update`): always
    /// `https://api.github.com`, a closed local port in tests so Software Updates renders
    /// without the network.
    pub github_api: String,
    /// `PWA_DEFAULT_LANG` (`en` or `nb`; unset = the browser's `Accept-Language`, else English): the language
    /// of a request with neither `?lang=` nor an account language - the sign-in pages, and the language stored
    /// on an account's first signed-in request (`i18n::Lang::of_request`, `security::store_first_lang`). The
    /// per-account switch in Settings › Account wins.
    pub pwa_default_lang: Option<crate::i18n::Lang>,
    /// `MET_CONTACT` (design 75 D8): the operator's contact (an e-mail or a link), appended to MET's User-Agent
    /// ([ForkConfig::met_user_agent]). 3-100 printable ASCII characters without quotes; anything else is logged and
    /// ignored. With it MET can tie the cells it is asked for to that address (DEPLOY.md).
    pub met_contact: Option<String>,
}

pub const DEFAULT_SSE_KEEPALIVE_SECS: u64 = 240;
/// 60 s under the launcher's 300 s SSE read timeout.
pub const MAX_SSE_KEEPALIVE_SECS: u64 = 240;

fn is_valid_keepalive(value: &str) -> bool {
    value
        .parse::<u64>()
        .is_ok_and(|v| (5..=MAX_SSE_KEEPALIVE_SECS).contains(&v))
}

impl ForkConfig {
    pub fn from_env() -> Self {
        Self::from_vars(&std::env::vars().collect())
    }

    /// The actual parser, taking a plain map so tests don't have to mutate the process
    /// environment. An empty value counts as unset. An invalid value is logged and also treated
    /// as unset (so the default applies, or for the checksum, no QR is shown) - starting the
    /// server anyway is better than refusing to start over a provisioning setting.
    pub fn from_vars(vars: &HashMap<String, String>) -> Self {
        let read = |key: &str, valid: fn(&str) -> bool| -> Option<String> {
            let value = vars.get(key).map(|v| v.trim()).filter(|v| !v.is_empty())?;
            if valid(value) {
                Some(value.to_string())
            } else {
                tracing::error!("{key} is set but invalid ({value:?}) - ignoring it");
                None
            }
        };

        ForkConfig {
            server_release_repo: read("SERVER_RELEASE_REPO", is_valid_repo)
                .map(|v| {
                    migrate_legacy(
                        "SERVER_RELEASE_REPO",
                        v,
                        LEGACY_SERVER_RELEASE_REPO,
                        DEFAULT_SERVER_RELEASE_REPO,
                    )
                })
                .unwrap_or_else(|| DEFAULT_SERVER_RELEASE_REPO.to_string()),
            launcher_admin_component: read("LAUNCHER_ADMIN_COMPONENT", is_valid_component)
                .map(|v| {
                    migrate_legacy(
                        "LAUNCHER_ADMIN_COMPONENT",
                        v,
                        LEGACY_LAUNCHER_ADMIN_COMPONENT,
                        DEFAULT_LAUNCHER_ADMIN_COMPONENT,
                    )
                })
                .unwrap_or_else(|| DEFAULT_LAUNCHER_ADMIN_COMPONENT.to_string()),
            launcher_apk_url: read("LAUNCHER_APK_URL", is_valid_url)
                .map(|v| {
                    migrate_legacy(
                        "LAUNCHER_APK_URL",
                        v,
                        LEGACY_LAUNCHER_APK_URL,
                        DEFAULT_LAUNCHER_APK_URL,
                    )
                })
                .unwrap_or_else(|| DEFAULT_LAUNCHER_APK_URL.to_string()),
            launcher_signature_checksum: read("LAUNCHER_SIGNATURE_CHECKSUM", is_valid_checksum),
            phone_server_url: read("PHONE_SERVER_URL", |url| match check_server_url(url) {
                ServerUrl::Fine => true,
                ServerUrl::TailnetHttp(host) => {
                    tracing::warn!(
                        "PHONE_SERVER_URL: the phones will use plain http to {host} over the tailnet. \
                         Tailscale encrypts the way, but only https proves the server to the phone - \
                         prefer an https:// name from tailscale serve."
                    );
                    true
                }
                ServerUrl::Refused(_) => {
                    tracing::warn!(
                        "PHONE_SERVER_URL is not used: the phones take https:// only, or plain http:// \
                         to a Tailscale IP address (100.64.0.0/10, fd7a:115c:a1e0::/48)."
                    );
                    false
                }
            }),
            phone_recovery_url: read("PHONE_RECOVERY_URL", |url| {
                let tailnet = matches!(check_server_url(url), ServerUrl::TailnetHttp(_));
                if !tailnet {
                    tracing::warn!(
                        "PHONE_RECOVERY_URL is not used: it must be plain http:// to a Tailscale IP address \
                         (100.64.0.0/10, fd7a:115c:a1e0::/48), the way a phone reaches this server past its \
                         certificate check."
                    );
                }
                tailnet
            }),
            sse_keepalive_secs: read("SSE_KEEPALIVE_SECS", is_valid_keepalive)
                .and_then(|v| v.parse().ok())
                .unwrap_or(DEFAULT_SSE_KEEPALIVE_SECS),
            github_api: "https://api.github.com".to_string(),
            pwa_default_lang: read("PWA_DEFAULT_LANG", |v| {
                crate::i18n::Lang::from_code(&v.to_ascii_lowercase()).is_some()
            })
            .and_then(|v| crate::i18n::Lang::from_code(&v.to_ascii_lowercase())),
            met_contact: read("MET_CONTACT", is_valid_contact),
        }
    }

    /// MET's User-Agent (design 75 D8, the user: "OK vi kjører standard"): `vibb-server/<version>
    /// github.com/<SERVER_RELEASE_REPO>`, then `MET_CONTACT` when set. Only MET's requests carry it; every other
    /// request keeps the neutral `net::USER_AGENT`.
    pub fn met_user_agent(&self) -> String {
        let mut agent = format!(
            "vibb-server/{} github.com/{}",
            env!("CARGO_PKG_VERSION"),
            self.server_release_repo
        );
        if let Some(contact) = &self.met_contact {
            agent.push(' ');
            agent.push_str(contact);
        }
        agent
    }

    /// Defaults plus a syntactically valid (made-up) checksum, so tests see a working QR page
    /// unless they clear it on purpose.
    #[cfg(test)]
    pub fn for_tests() -> Self {
        let mut config = Self::from_vars(&HashMap::new());
        config.launcher_signature_checksum = Some("A".repeat(43));
        // Port 9 (discard) on loopback: refused at once, so a check "fails" without the network.
        config.github_api = "http://127.0.0.1:9".to_string();
        config
    }
}

/// `owner/repo`, the same character set GitHub allows - also what `deploy/install.sh` checks
/// `KPS_REPO` against before substituting it into a root-run script.
pub(crate) fn is_valid_repo(value: &str) -> bool {
    let ok_part = |p: &str| {
        !p.is_empty()
            && p.chars()
                .all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '.' | '-'))
    };
    matches!(value.split_once('/'), Some((owner, repo)) if ok_part(owner) && ok_part(repo))
}

/// `MET_CONTACT`: 3-100 printable ASCII characters, no quotes (it goes into a header as it is).
fn is_valid_contact(value: &str) -> bool {
    (3..=100).contains(&value.len())
        && value
            .bytes()
            .all(|b| (0x20..0x7f).contains(&b) && b != b'"' && b != b'\'')
}

fn is_valid_component(value: &str) -> bool {
    matches!(value.split_once('/'), Some((pkg, class)) if !pkg.is_empty() && !class.is_empty())
}

fn is_valid_url(value: &str) -> bool {
    value.starts_with("https://") && value.len() > "https://".len()
}

/// What [check_server_url] says about a server address for the setup QR.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ServerUrl {
    /// `https://` and a host.
    Fine,
    /// Plain `http://` to a Tailscale IP literal (this host, lower case, an IPv6 one without
    /// brackets): taken, with a warning - the phone sends it only through its own tailnet
    /// connection, but only https proves the server to it.
    TailnetHttp(String),
    /// Not taken, and the key of why (`settings.provisioning.url_*`, worded by Phone Setup).
    Refused(&'static str),
}

/// The one rule for the phones' server address in the setup QR (launcher security review S-M1,
/// the user's decision of 2026-11-04; before it, L8's four classes), for `.env`'s
/// `PHONE_SERVER_URL` and Phone Setup's field alike, and the launcher's for the address it stores
/// (`ServerUrlRule.kt`; both run `testdata/server_url_vectors.json`):
/// - `https://` and a host: fine;
/// - `http://` to a Tailscale IP literal (100.64.0.0/10, `fd7a:115c:a1e0::/48`): taken with a
///   warning (no name is looked up: a `*.ts.net` name must be https);
/// - anything else: refused - a private address too (a LAN carries the token in clear).
///
/// The grammar is narrower than a URL, so no two parsers can see two hosts in it: after trimming
/// ASCII whitespace, only `scheme://host[:port][/path]` with a path of `A-Z a-z 0-9 . _ ~ / -` -
/// no userinfo, query, fragment, `%` or `\`; a host is a strict dotted quad, a bracketed IPv6
/// literal of hex groups (no dotted tail, no zone), or a DNS name whose last label isn't a number.
pub fn check_server_url(value: &str) -> ServerUrl {
    const SHAPE: &str = "settings.provisioning.url_shape";
    let Some(parts) = parse_server_url(value) else {
        return ServerUrl::Refused(SHAPE);
    };
    if parts.https {
        ServerUrl::Fine
    } else if parts.ip.is_some_and(is_tailnet_ip) {
        ServerUrl::TailnetHttp(parts.host)
    } else {
        ServerUrl::Refused("settings.provisioning.url_plain_http")
    }
}

/// An address [check_server_url]'s grammar takes.
struct ServerUrlParts {
    https: bool,
    host: String,
    ip: Option<std::net::IpAddr>,
}

fn parse_server_url(value: &str) -> Option<ServerUrlParts> {
    let text = value.trim_matches(|c: char| c.is_ascii_whitespace());
    let (scheme, rest) = text.split_once("://")?;
    if scheme.is_empty() || !scheme.chars().all(|c| c.is_ascii_alphabetic()) {
        return None;
    }
    let https = if scheme.eq_ignore_ascii_case("https") {
        true
    } else if scheme.eq_ignore_ascii_case("http") {
        false
    } else {
        return None;
    };
    let (authority, path) = match rest.find('/') {
        Some(i) => rest.split_at(i),
        None => (rest, ""),
    };
    let path_char = |c: char| c.is_ascii_alphanumeric() || "._~/-".contains(c);
    if !path.chars().all(path_char) {
        return None;
    }
    let (host, ip, port) = if let Some(bracketed) = authority.strip_prefix('[') {
        let (raw, after) = bracketed.split_once(']')?;
        let ip = ipv6_literal(raw)?;
        let port = match after {
            "" => None,
            p => Some(p.strip_prefix(':')?),
        };
        (
            raw.to_ascii_lowercase(),
            Some(std::net::IpAddr::V6(ip)),
            port,
        )
    } else {
        let (raw, port) = match authority.split_once(':') {
            Some((h, p)) => (h, Some(p)),
            None => (authority, None),
        };
        let ip = ipv4_literal(raw);
        if ip.is_none() && !dns_name(raw) {
            return None;
        }
        (raw.to_ascii_lowercase(), ip.map(std::net::IpAddr::V4), port)
    };
    if let Some(p) = port {
        let digits = !p.is_empty() && p.len() <= 5 && p.chars().all(|c| c.is_ascii_digit());
        if !digits || p.starts_with('0') || !(1..=65535).contains(&p.parse::<u32>().ok()?) {
            return None;
        }
    }
    Some(ServerUrlParts { https, host, ip })
}

/// 100.64.0.0/10 or `fd7a:115c:a1e0::/48` (an IPv4-mapped IPv6 address is neither).
pub(crate) fn is_tailnet_ip(ip: std::net::IpAddr) -> bool {
    match ip {
        std::net::IpAddr::V4(ip) => {
            let [a, b, ..] = ip.octets();
            a == 100 && (64..=127).contains(&b)
        }
        std::net::IpAddr::V6(ip) => {
            let s = ip.segments();
            s[0] == 0xfd7a && s[1] == 0x115c && s[2] == 0xa1e0
        }
    }
}

/// Four decimal parts 0-255, no leading zero (`100.064.0.1` is octal to some parsers).
fn ipv4_literal(text: &str) -> Option<std::net::Ipv4Addr> {
    let parts: Vec<&str> = text.split('.').collect();
    if parts.len() != 4 {
        return None;
    }
    let mut octets = [0u8; 4];
    for (octet, part) in octets.iter_mut().zip(&parts) {
        let digits =
            !part.is_empty() && part.len() <= 3 && part.chars().all(|c| c.is_ascii_digit());
        if !digits || (part.len() > 1 && part.starts_with('0')) {
            return None;
        }
        *octet = part.parse().ok()?;
    }
    Some(std::net::Ipv4Addr::from(octets))
}

/// Hex groups and one `::` only (no dotted IPv4 tail, no zone).
fn ipv6_literal(text: &str) -> Option<std::net::Ipv6Addr> {
    if text.is_empty() || !text.chars().all(|c| c.is_ascii_hexdigit() || c == ':') {
        return None;
    }
    let groups = |s: &str| -> Option<Vec<u16>> {
        if s.is_empty() {
            return Some(Vec::new());
        }
        s.split(':')
            .map(|g| {
                if (1..=4).contains(&g.len()) {
                    u16::from_str_radix(g, 16).ok()
                } else {
                    None
                }
            })
            .collect()
    };
    let halves: Vec<&str> = text.split("::").collect();
    let all = match halves.as_slice() {
        [whole] => groups(whole).filter(|g| g.len() == 8)?,
        [head, tail] => {
            let (head, tail) = (groups(head)?, groups(tail)?);
            if head.len() + tail.len() > 7 {
                return None;
            }
            let mut all = head;
            all.resize(8 - tail.len(), 0);
            all.extend(tail);
            all
        }
        _ => return None,
    };
    let segments: [u16; 8] = all.try_into().ok()?;
    Some(std::net::Ipv6Addr::from(segments))
}

/// A DNS name of ASCII labels (letters, digits, `-` inside), no empty label (so no trailing dot),
/// at most 253 characters, whose last label isn't a number (decimal or `0x` hex): URL parsers read
/// such a host as an IPv4 address (`1681915905` is 100.64.0.1).
fn dns_name(text: &str) -> bool {
    if text.is_empty() || text.len() > 253 {
        return false;
    }
    let labels_ok = text.split('.').all(|l| {
        (1..=63).contains(&l.len())
            && !l.starts_with('-')
            && !l.ends_with('-')
            && l.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
    });
    if !labels_ok {
        return false;
    }
    let last = text.rsplit('.').next().unwrap_or_default();
    let numeric = last.chars().all(|c| c.is_ascii_digit())
        || (last.len() >= 2
            && last[..2].eq_ignore_ascii_case("0x")
            && last[2..].chars().all(|c| c.is_ascii_hexdigit()));
    !numeric
}

/// 32 bytes as base64url without padding is exactly 43 characters.
fn is_valid_checksum(value: &str) -> bool {
    value.len() == 43
        && value
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

#[cfg(test)]
mod tests {
    use super::*;

    fn vars(pairs: &[(&str, &str)]) -> HashMap<String, String> {
        pairs
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    }

    const CHECKSUM: &str = "TLXcVaskQBZyh0S88O29PvHa9RaiCCl-7TybpGlbmkg";

    #[test]
    fn missing_values_use_our_defaults_and_no_checksum() {
        let config = ForkConfig::from_vars(&HashMap::new());
        assert_eq!(config.server_release_repo, "palchrb/vibb-launcher");
        assert_eq!(
            config.launcher_admin_component,
            DEFAULT_LAUNCHER_ADMIN_COMPONENT
        );
        assert_eq!(config.launcher_apk_url, DEFAULT_LAUNCHER_APK_URL);
        assert_eq!(config.launcher_signature_checksum, None);
        assert_eq!(config.phone_server_url, None);
    }

    #[test]
    fn valid_values_are_used() {
        let config = ForkConfig::from_vars(&vars(&[
            ("SERVER_RELEASE_REPO", "someone/kid-phone-server"),
            ("LAUNCHER_ADMIN_COMPONENT", "org.example/org.example.Admin"),
            ("LAUNCHER_APK_URL", "https://example.org/launcher.apk"),
            ("LAUNCHER_SIGNATURE_CHECKSUM", CHECKSUM),
            ("PHONE_SERVER_URL", "https://vibb.example.ts.net"),
        ]));
        assert_eq!(config.server_release_repo, "someone/kid-phone-server");
        assert_eq!(
            config.launcher_admin_component,
            "org.example/org.example.Admin"
        );
        assert_eq!(config.launcher_apk_url, "https://example.org/launcher.apk");
        assert_eq!(
            config.launcher_signature_checksum.as_deref(),
            Some(CHECKSUM)
        );
        assert_eq!(
            config.phone_server_url.as_deref(),
            Some("https://vibb.example.ts.net")
        );
        // Plain http to a Tailscale IP, with a warning in the log.
        let taken = "http://100.101.102.103:3100";
        let config = ForkConfig::from_vars(&vars(&[("PHONE_SERVER_URL", taken)]));
        assert_eq!(config.phone_server_url.as_deref(), Some(taken));
    }

    /// Design 76's fix round (H-1): the recovery address is plain http to a Tailscale IP or nothing - an https one
    /// would be checked by the very pins it is meant to get past, a LAN address carries the token in clear.
    #[test]
    fn the_recovery_url_is_a_tailnet_address_only() {
        let taken = "http://100.101.102.103:3101";
        let config = ForkConfig::from_vars(&vars(&[("PHONE_RECOVERY_URL", taken)]));
        assert_eq!(config.phone_recovery_url.as_deref(), Some(taken));
        for refused in [
            "https://vibb.example.ts.net",
            "http://192.168.1.10:3101",
            "http://vibb.example.ts.net:3101",
        ] {
            let config = ForkConfig::from_vars(&vars(&[("PHONE_RECOVERY_URL", refused)]));
            assert_eq!(config.phone_recovery_url, None, "{refused}");
        }
        assert_eq!(ForkConfig::from_vars(&vars(&[])).phone_recovery_url, None);
    }

    /// Launcher security review S-M1, the user's rule of 2026-11-04: every row of the vectors the
    /// launcher runs too, and the copies identical.
    #[test]
    fn the_server_url_vectors() {
        const VECTORS: &str = include_str!("../testdata/server_url_vectors.json");
        let rows: serde_json::Value = serde_json::from_str(VECTORS).unwrap();
        let rows = rows["vectors"].as_array().unwrap();
        assert!(rows.len() >= 50);
        for row in rows {
            let url = row["url"].as_str().unwrap();
            let got = match check_server_url(url) {
                ServerUrl::Fine => "https",
                ServerUrl::TailnetHttp(host) => {
                    assert!(url.to_ascii_lowercase().contains(&host), "{url}: {host}");
                    "tailnet_http"
                }
                ServerUrl::Refused(_) => "refused",
            };
            assert_eq!(got, row["verdict"].as_str().unwrap(), "{url:?}");
        }
        let launcher = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../launcher/app/src/test/resources/server_url_vectors.json");
        let copy = std::fs::read_to_string(&launcher)
            .unwrap_or_else(|e| panic!("{}: {e}", launcher.display()));
        assert_eq!(
            copy, VECTORS,
            "the launcher's server_url_vectors.json differs"
        );
    }

    /// The refusal's reason: a bad shape, or plain http to anything but a tailnet IP.
    #[test]
    fn a_refused_address_says_why() {
        for (url, why) in [
            ("vibb.example.ts.net", "settings.provisioning.url_shape"),
            (
                "https://user@vibb.example.org",
                "settings.provisioning.url_shape",
            ),
            ("http://192.168.1.5", "settings.provisioning.url_plain_http"),
            (
                "http://vibb.tail0000.ts.net",
                "settings.provisioning.url_plain_http",
            ),
        ] {
            assert_eq!(check_server_url(url), ServerUrl::Refused(why), "{url}");
        }
    }

    #[test]
    fn invalid_values_are_treated_as_unset() {
        let cases: &[(&str, &str)] = &[
            ("SERVER_RELEASE_REPO", "no-slash"),
            ("SERVER_RELEASE_REPO", "a/b; rm -rf /"),
            ("SERVER_RELEASE_REPO", "/repo"),
            ("LAUNCHER_ADMIN_COMPONENT", "org.example.Admin"),
            ("LAUNCHER_APK_URL", "http://example.org/launcher.apk"),
            ("PHONE_SERVER_URL", "vibb.example.ts.net"),
            ("PHONE_SERVER_URL", "https://"),
            ("PHONE_SERVER_URL", "https:///path"),
            ("PHONE_SERVER_URL", "https://a b"),
            ("PHONE_SERVER_URL", "ftp://vibb.example"),
            ("PHONE_SERVER_URL", "http://vibb.example.org"),
            ("PHONE_SERVER_URL", "http://100.128.0.1"),
            ("PHONE_SERVER_URL", "http://10.0.2.2:3100"),
            ("PHONE_SERVER_URL", "http://vibb.tail1234.ts.net"),
            ("PWA_DEFAULT_LANG", "de"),
            ("LAUNCHER_SIGNATURE_CHECKSUM", "too-short"),
            // 44 characters: a padded base64 value, not the unpadded form Android expects.
            (
                "LAUNCHER_SIGNATURE_CHECKSUM",
                "TLXcVaskQBZyh0S88O29PvHa9RaiCCl-7TybpGlbmkg=",
            ),
            // Standard (not url-safe) base64 alphabet.
            (
                "LAUNCHER_SIGNATURE_CHECKSUM",
                "TLXcVaskQBZyh0S88O29PvHa9RaiCCl+7TybpGlbmkg",
            ),
        ];
        let defaults = ForkConfig::from_vars(&HashMap::new());
        for (key, value) in cases {
            let config = ForkConfig::from_vars(&vars(&[(key, value)]));
            assert_eq!(config, defaults, "{key}={value:?} should be ignored");
        }
    }

    #[test]
    fn met_contact_is_checked_and_appended() {
        let agent =
            |value: &str| ForkConfig::from_vars(&vars(&[("MET_CONTACT", value)])).met_user_agent();
        let base = format!(
            "vibb-server/{} github.com/palchrb/vibb-launcher",
            env!("CARGO_PKG_VERSION")
        );
        assert_eq!(agent(""), base);
        assert_eq!(agent(" ola@example.no "), format!("{base} ola@example.no"));
        for bad in [
            "ab",
            "with \"quote\"",
            "line\nbreak",
            "ø@example.no",
            &"x".repeat(101),
        ] {
            assert_eq!(agent(bad), base, "{bad:?} is ignored");
        }
    }

    #[test]
    fn the_pwa_default_language_is_en_or_nb() {
        let of = |value: &str| {
            ForkConfig::from_vars(&vars(&[("PWA_DEFAULT_LANG", value)])).pwa_default_lang
        };
        assert_eq!(of("en"), Some(crate::i18n::Lang::En));
        assert_eq!(of(" NB "), Some(crate::i18n::Lang::Nb));
        assert_eq!(of(""), None);
        assert_eq!(of("de"), None);
    }

    #[test]
    fn pre_monorepo_defaults_follow_the_move() {
        let config = ForkConfig::from_vars(&vars(&[
            ("SERVER_RELEASE_REPO", LEGACY_SERVER_RELEASE_REPO),
            ("LAUNCHER_APK_URL", LEGACY_LAUNCHER_APK_URL),
        ]));
        assert_eq!(config, ForkConfig::from_vars(&HashMap::new()));
        assert_eq!(
            config.launcher_apk_url,
            "https://github.com/palchrb/vibb-launcher/releases/latest/download/kids-launcher-mdm.apk"
        );
    }

    #[test]
    fn pre_rename_admin_component_follows_the_package_rename() {
        let config = ForkConfig::from_vars(&vars(&[(
            "LAUNCHER_ADMIN_COMPONENT",
            LEGACY_LAUNCHER_ADMIN_COMPONENT,
        )]));
        assert_eq!(
            config.launcher_admin_component,
            "me.vibb.launcher/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver"
        );
        // Any other value (e.g. a debug build on a test server) is kept as written.
        let debug = "com.kidslauncher.mdm.debug/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver";
        let config = ForkConfig::from_vars(&vars(&[("LAUNCHER_ADMIN_COMPONENT", debug)]));
        assert_eq!(config.launcher_admin_component, debug);
    }

    #[test]
    fn empty_and_blank_values_count_as_unset() {
        let config = ForkConfig::from_vars(&vars(&[
            ("SERVER_RELEASE_REPO", ""),
            ("LAUNCHER_SIGNATURE_CHECKSUM", "   "),
        ]));
        assert_eq!(config, ForkConfig::from_vars(&HashMap::new()));
    }

    #[test]
    fn surrounding_whitespace_is_trimmed() {
        let config = ForkConfig::from_vars(&vars(&[(
            "LAUNCHER_SIGNATURE_CHECKSUM",
            &format!(" {CHECKSUM}\n"),
        )]));
        assert_eq!(
            config.launcher_signature_checksum.as_deref(),
            Some(CHECKSUM)
        );
    }
}
