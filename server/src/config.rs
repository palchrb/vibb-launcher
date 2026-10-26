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
    /// names in the "re-run the installer" hint (`security::reinstall_hint`).
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
    /// a private address is taken with a warning in the log.
    pub phone_server_url: Option<String>,
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
                ServerUrl::PlainHttp(why) => {
                    tracing::warn!("PHONE_SERVER_URL: {why}");
                    true
                }
                ServerUrl::Refused(_) => false,
            }),
            sse_keepalive_secs: read("SSE_KEEPALIVE_SECS", is_valid_keepalive)
                .and_then(|v| v.parse().ok())
                .unwrap_or(DEFAULT_SSE_KEEPALIVE_SECS),
            github_api: "https://api.github.com".to_string(),
        }
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

fn is_valid_component(value: &str) -> bool {
    matches!(value.split_once('/'), Some((pkg, class)) if !pkg.is_empty() && !class.is_empty())
}

fn is_valid_url(value: &str) -> bool {
    value.starts_with("https://") && value.len() > "https://".len()
}

/// What [check_server_url] says about a server address for the setup QR.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ServerUrl {
    /// `https://`, or plain `http://` to a Tailscale address: the path is encrypted.
    Fine,
    /// Plain `http://` to another private address: taken, with this warning - safe only through
    /// an encrypted tunnel, which the server can't see.
    PlainHttp(String),
    /// Not taken, and why.
    Refused(&'static str),
}

/// The one rule for the phones' server address in the setup QR (security review L8; the user's
/// rule of 2026-10-26), for `.env`'s `PHONE_SERVER_URL` and Phone Setup's field alike:
/// - `https://` and a host: fine;
/// - `http://` to a Tailscale address (100.64.0.0/10, `fd7a:115c:a1e0::/48`, a `*.ts.net` name):
///   fine - the tailnet encrypts it;
/// - `http://` to another private address (RFC 1918, loopback, link-local, `fc00::/7`, a `.local`,
///   `.internal` or single-label name): taken with a warning - a WireGuard or Headscale path is
///   encrypted too, and the server can't tell it from a cleartext LAN;
/// - `http://` to a public address or name, anything else: refused.
pub fn check_server_url(value: &str) -> ServerUrl {
    let parsed = reqwest::Url::parse(value)
        .ok()
        .filter(|_| !value.chars().any(char::is_whitespace))
        // The parser makes `https:///path` a host "path"; the text must name one.
        .filter(|_| {
            value
                .split_once("://")
                .is_some_and(|(_, rest)| !rest.starts_with('/'))
        })
        .filter(|url| url.host_str().is_some_and(|host| !host.is_empty()));
    const SHAPE: &str = "Use an address like https://vibb.your-tailnet.ts.net. Nothing was saved.";
    let Some(url) = parsed else {
        return ServerUrl::Refused(SHAPE);
    };
    let host = url
        .host_str()
        .unwrap_or_default()
        .trim_start_matches('[')
        .trim_end_matches(']')
        .trim_end_matches('.')
        .to_ascii_lowercase();
    match url.scheme() {
        "https" => ServerUrl::Fine,
        "http" => match host_kind(&host) {
            HostKind::Tailscale => ServerUrl::Fine,
            HostKind::Private => ServerUrl::PlainHttp(format!(
                "The phones will use plain http to {host}. That is safe only if the path is an \
                 encrypted tunnel (WireGuard, Tailscale). The server can't tell."
            )),
            HostKind::Public => ServerUrl::Refused(
                "Use https:// to a public address: plain http would carry the phones' traffic \
                 unencrypted over the internet. Nothing was saved.",
            ),
        },
        _ => ServerUrl::Refused(SHAPE),
    }
}

enum HostKind {
    Tailscale,
    Private,
    Public,
}

/// Where a server address's host lives, by its address or name alone (nothing is looked up).
fn host_kind(host: &str) -> HostKind {
    use std::net::IpAddr;
    match host.parse::<IpAddr>() {
        Ok(IpAddr::V4(ip)) => {
            let [a, b, ..] = ip.octets();
            if a == 100 && (64..=127).contains(&b) {
                HostKind::Tailscale
            } else if ip.is_private() || ip.is_loopback() || ip.is_link_local() {
                HostKind::Private
            } else {
                HostKind::Public
            }
        }
        Ok(IpAddr::V6(ip)) => {
            let s = ip.segments();
            if s[0] == 0xfd7a && s[1] == 0x115c && s[2] == 0xa1e0 {
                HostKind::Tailscale
            } else if ip.is_loopback() || (s[0] & 0xfe00) == 0xfc00 || (s[0] & 0xffc0) == 0xfe80 {
                HostKind::Private
            } else {
                HostKind::Public
            }
        }
        Err(_) if host.ends_with(".ts.net") => HostKind::Tailscale,
        Err(_)
            if !host.contains('.') || host.ends_with(".local") || host.ends_with(".internal") =>
        {
            HostKind::Private
        }
        Err(_) => HostKind::Public,
    }
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
        // Plain http to a Tailscale address, or (with a warning) to another private one.
        for taken in ["http://100.101.102.103:3100", "http://10.0.2.2:3100"] {
            let config = ForkConfig::from_vars(&vars(&[("PHONE_SERVER_URL", taken)]));
            assert_eq!(config.phone_server_url.as_deref(), Some(taken));
        }
    }

    /// Security review L8, the user's rule: https fine; http to Tailscale fine; http to another
    /// private address taken with a warning; http to a public address or name refused.
    #[test]
    fn a_server_address_is_judged_in_four_classes() {
        for fine in [
            "https://vibb.example.org",
            "https://203.0.113.5:8443",
            "http://100.101.102.103:3100",
            "http://[fd7a:115c:a1e0::1]:3100",
            "http://vibb.tail1234.ts.net",
        ] {
            assert_eq!(check_server_url(fine), ServerUrl::Fine, "{fine}");
        }
        for warned in [
            "http://10.0.2.2:3100",
            "http://192.168.1.5",
            "http://172.20.0.1",
            "http://127.0.0.1:3100",
            "http://169.254.1.1",
            "http://[fd00::5]",
            "http://[fe80::1]",
            "http://[::1]",
            "http://nas.local",
            "http://vibb.internal",
            "http://vibb",
        ] {
            match check_server_url(warned) {
                ServerUrl::PlainHttp(why) => assert!(
                    why.contains("plain http to ") && why.contains("The server can't tell."),
                    "{why}"
                ),
                other => panic!("{warned}: {other:?}"),
            }
        }
        for refused in [
            "http://203.0.113.5",
            "http://vibb.example.org",
            "http://100.128.0.1",
            "http://[2001:db8::1]",
            "ftp://vibb.example",
            "vibb.example.ts.net",
        ] {
            assert!(
                matches!(check_server_url(refused), ServerUrl::Refused(_)),
                "{refused}"
            );
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
