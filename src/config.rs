//! Values that differ between this fork's deployments and upstream's - which GitHub repo this
//! server updates itself from, and which launcher build the provisioning QR installs. Read once
//! at startup from env vars (`.env` is loaded by `dotenvy` in `main()`), stored in
//! `AppState.config`.
//!
//! Every default points at our own (`palchrb`) forks, never at upstream's. The one value with
//! no default at all is the launcher's signing-certificate checksum: it depends on a release key
//! only the person running this server has, and a wrong default would make the provisioning QR
//! install somebody else's build - so while it's unset, `/devices/{id}/provision` shows a banner
//! instead of a QR code (see `handlers::provisioning`).

use std::collections::HashMap;

pub const DEFAULT_SERVER_RELEASE_REPO: &str = "palchrb/kid-phone-server";
pub const DEFAULT_LAUNCHER_ADMIN_COMPONENT: &str =
    "com.kidslauncher.mdm/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver";
/// `releases/latest` only ever serves a normal (non-prerelease) release, and the launcher's
/// release CI always attaches the APK under this one stable asset name - so this URL keeps
/// pointing at the newest release build with nothing to update per release.
pub const DEFAULT_LAUNCHER_APK_URL: &str =
    "https://github.com/palchrb/kids-launcher-mdm/releases/latest/download/kids-launcher-mdm.apk";

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
    /// `SSE_KEEPALIVE_SECS` (5-240, default 120): how often the command stream sends a keepalive
    /// comment. Only phones without working FCM hold the stream; their client read timeout is
    /// 300 s (launcher `SSE_READ_TIMEOUT_MS`), so the cap keeps a 60 s margin - a value at or
    /// above it would make every SSE phone drop and reopen the stream every 5 minutes.
    pub sse_keepalive_secs: u64,
}

pub const DEFAULT_SSE_KEEPALIVE_SECS: u64 = 120;
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
                .unwrap_or_else(|| DEFAULT_SERVER_RELEASE_REPO.to_string()),
            launcher_admin_component: read("LAUNCHER_ADMIN_COMPONENT", is_valid_component)
                .unwrap_or_else(|| DEFAULT_LAUNCHER_ADMIN_COMPONENT.to_string()),
            launcher_apk_url: read("LAUNCHER_APK_URL", is_valid_url)
                .unwrap_or_else(|| DEFAULT_LAUNCHER_APK_URL.to_string()),
            launcher_signature_checksum: read("LAUNCHER_SIGNATURE_CHECKSUM", is_valid_checksum),
            sse_keepalive_secs: read("SSE_KEEPALIVE_SECS", is_valid_keepalive)
                .and_then(|v| v.parse().ok())
                .unwrap_or(DEFAULT_SSE_KEEPALIVE_SECS),
        }
    }

    /// Defaults plus a syntactically valid (made-up) checksum, so tests see a working QR page
    /// unless they clear it on purpose.
    #[cfg(test)]
    pub fn for_tests() -> Self {
        let mut config = Self::from_vars(&HashMap::new());
        config.launcher_signature_checksum = Some("A".repeat(43));
        config
    }
}

/// `owner/repo`, the same character set GitHub allows - also what `deploy/install.sh` checks
/// `KPS_REPO` against before substituting it into a root-run script.
fn is_valid_repo(value: &str) -> bool {
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
        assert_eq!(config.server_release_repo, "palchrb/kid-phone-server");
        assert_eq!(
            config.launcher_admin_component,
            DEFAULT_LAUNCHER_ADMIN_COMPONENT
        );
        assert_eq!(config.launcher_apk_url, DEFAULT_LAUNCHER_APK_URL);
        assert_eq!(config.launcher_signature_checksum, None);
    }

    #[test]
    fn valid_values_are_used() {
        let config = ForkConfig::from_vars(&vars(&[
            ("SERVER_RELEASE_REPO", "someone/kid-phone-server"),
            ("LAUNCHER_ADMIN_COMPONENT", "org.example/org.example.Admin"),
            ("LAUNCHER_APK_URL", "https://example.org/launcher.apk"),
            ("LAUNCHER_SIGNATURE_CHECKSUM", CHECKSUM),
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
    }

    #[test]
    fn invalid_values_are_treated_as_unset() {
        let cases: &[(&str, &str)] = &[
            ("SERVER_RELEASE_REPO", "no-slash"),
            ("SERVER_RELEASE_REPO", "a/b; rm -rf /"),
            ("SERVER_RELEASE_REPO", "/repo"),
            ("LAUNCHER_ADMIN_COMPONENT", "org.example.Admin"),
            ("LAUNCHER_APK_URL", "http://example.org/launcher.apk"),
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
