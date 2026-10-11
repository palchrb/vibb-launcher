//! Compiles the blocklist feeds/custom domains that every device's own
//! on-device DNS filter (the launcher's `KidVpnService`) resolves its
//! effective per-device list from - see `handlers::device_api::dns_blocklist`.
//!
//! This module used to also run a live, in-process DNS server
//! (`hickory-server`) that devices queried directly, either over plain port
//! 53 (via a Tailscale-exit-node + iptables redirect) or DNS-over-TLS (via
//! Android's Private DNS locked to this Pi's hostname). That approach hit a
//! hard Android limitation - Private DNS strict-mode bootstrap resolution
//! structurally cannot resolve a Tailscale MagicDNS hostname - and was
//! replaced by on-device filtering entirely (see this repo's CLAUDE.md for
//! the full migration writeup). This server's job shrank to just what's
//! below: distributing the compiled list, and ingesting a log of what got
//! blocked (`handlers::dns_filter::show_dns_log`).

use std::collections::HashSet;
use std::sync::Arc;

use tokio::sync::RwLock;

use crate::AppState;
use crate::models::{DnsBlocklist, DnsCustomDomain};

/// One curated feed's downloaded, normalized domain set - kept separate per
/// feed (rather than flattened into one big domain->category map) so a
/// device-level override can enable a feed the *global* default has off
/// (impossible if we'd only ever downloaded globally-enabled feeds).
pub struct CompiledList {
    pub blocklist_id: i64,
    pub category: String,
    pub domains: HashSet<String>,
    /// A download of this feed has worked since the server started (security review M6): until
    /// then `device_api::dns_blocklist` answers 503 to a phone that uses it, which keeps its own
    /// list.
    pub loaded: bool,
    /// Why the last download failed (its previous domains are kept); `None` after a good one.
    pub last_error: Option<String>,
}

/// The largest feed read, after decompression (the big public lists are around 10 MB).
pub const MAX_FEED_BYTES: usize = 32 * 1024 * 1024;

/// The server's compiled view of "what could a device's on-device filter
/// block" - every defined blocklist feed's domains (regardless of that
/// feed's *global* enabled flag - see [CompiledList]'s doc comment), plus
/// global (device_id IS NULL) custom block domains, minus global custom
/// allow domains. A specific device's actual effective list is resolved from
/// this cheaply at request time (global `enabled` flags + this device's
/// `device_blocklist_overrides`/scoped `dns_custom_domains`, applied on top)
/// - see `handlers::device_api::dns_blocklist` - rather than precomputed per
/// device, since this base rarely changes and per-device overrides are
/// small.
pub struct CompiledBlocklist {
    pub lists: Vec<CompiledList>,
    /// Global (device_id IS NULL) custom block/allow domains, already
    /// lowercased/normalized - kept separate from `lists` since they're not
    /// tied to a `blocklist_id` and always apply regardless of any per-device
    /// blocklist-feed override.
    pub global_custom_block: HashSet<String>,
    pub global_custom_allow: HashSet<String>,
    /// Stable content hash (independent of any device's overrides) - part of
    /// `PolicyResponse.dns_filter_version`'s input, so a client can tell
    /// "did the global list change" without re-fetching the whole thing.
    pub content_hash: String,
}

pub type SharedCompiledBlocklist = Arc<RwLock<CompiledBlocklist>>;

pub fn empty_compiled_blocklist() -> SharedCompiledBlocklist {
    Arc::new(RwLock::new(CompiledBlocklist {
        lists: Vec::new(),
        global_custom_block: HashSet::new(),
        global_custom_allow: HashSet::new(),
        content_hash: String::new(),
    }))
}

/// Downloads/normalizes every *defined* blocklist feed (not just
/// globally-enabled ones - see [CompiledList]) plus global custom domains,
/// and swaps the result into `shared` - called after every
/// settings/blocklist/custom-domain change and on the same hourly refresh as
/// before (see `handlers::dns_filter::run_blocklist_refresh`).
///
/// Fails closed per feed (security review M6): a feed whose download fails (an error, a non-2xx
/// status, a body over [MAX_FEED_BYTES], or nothing in it that looks like a domain - a captive
/// portal's page) keeps the domains it had, and one that never loaded stays `loaded: false`. A
/// database error keeps the whole compiled list.
pub async fn compile_blocklist(state: &AppState, shared: &SharedCompiledBlocklist) {
    let feeds = sqlx::query_as::<_, DnsBlocklist>("SELECT * FROM dns_blocklists")
        .fetch_all(&state.db)
        .await;
    let custom = sqlx::query_as::<_, DnsCustomDomain>(
        "SELECT * FROM dns_custom_domains WHERE device_id IS NULL",
    )
    .fetch_all(&state.db)
    .await;
    let (blocklists, custom) = match (feeds, custom) {
        (Ok(feeds), Ok(custom)) => (feeds, custom),
        (Err(err), _) | (_, Err(err)) => {
            tracing::error!(
                "DNS blocklist: couldn't read the settings ({err}) - keeping the compiled list"
            );
            return;
        }
    };

    let global_custom_allow: HashSet<String> = custom
        .iter()
        .filter(|d| d.list_type == "allow")
        .map(|d| d.domain.to_lowercase())
        .collect();
    let global_custom_block: HashSet<String> = custom
        .iter()
        .filter(|d| d.list_type == "block")
        .map(|d| d.domain.to_lowercase())
        .filter(|d| !global_custom_allow.contains(d))
        .collect();

    let client = reqwest::Client::builder()
        .user_agent("kid-phone-server (self-hosted, dns filter)")
        .timeout(std::time::Duration::from_secs(30))
        .build()
        .expect("reqwest client always builds");

    let mut lists = Vec::new();

    for list in &blocklists {
        let fetched = fetch_feed(&client, &list.url).await.and_then(|text| {
            let domains = parse_feed(&text);
            if domains.is_empty() {
                Err("no domains in it".to_string())
            } else {
                Ok(domains)
            }
        });
        let compiled = match fetched {
            Ok(mut domains) => {
                domains.retain(|domain| !global_custom_allow.contains(domain));
                CompiledList {
                    blocklist_id: list.id,
                    category: list.name.clone(),
                    domains,
                    loaded: true,
                    last_error: None,
                }
            }
            Err(err) => {
                tracing::warn!(
                    "DNS blocklist '{}': {err} - keeping its previous domains",
                    list.name
                );
                let previous = shared.read().await;
                let before = previous
                    .lists
                    .iter()
                    .find(|old| old.blocklist_id == list.id);
                let mut domains = before.map(|old| old.domains.clone()).unwrap_or_default();
                domains.retain(|domain| !global_custom_allow.contains(domain));
                CompiledList {
                    blocklist_id: list.id,
                    category: list.name.clone(),
                    domains,
                    loaded: before.is_some_and(|old| old.loaded),
                    last_error: Some(err),
                }
            }
        };
        lists.push(compiled);
    }

    let content_hash = {
        use sha2::{Digest, Sha256};
        let mut hasher = Sha256::new();
        let mut list_ids: Vec<i64> = lists.iter().map(|l| l.blocklist_id).collect();
        list_ids.sort();
        for id in list_ids {
            let list = lists.iter().find(|l| l.blocklist_id == id).unwrap();
            let mut sorted: Vec<&String> = list.domains.iter().collect();
            sorted.sort();
            hasher.update(format!("list:{id}\n").as_bytes());
            for d in sorted {
                hasher.update(d.as_bytes());
                hasher.update(b"\n");
            }
        }
        let mut sorted_block: Vec<&String> = global_custom_block.iter().collect();
        sorted_block.sort();
        for d in sorted_block {
            hasher.update(d.as_bytes());
            hasher.update(b"\n");
        }
        hex::encode(hasher.finalize())
    };

    *shared.write().await = CompiledBlocklist {
        lists,
        global_custom_block,
        global_custom_allow,
        content_hash,
    };
}

/// One feed's body: a 2xx answer, at most [MAX_FEED_BYTES] (decompressed).
async fn fetch_feed(client: &reqwest::Client, url: &str) -> Result<String, String> {
    let mut response = client
        .get(url)
        .send()
        .await
        .map_err(|err| format!("download failed: {err}"))?;
    let status = response.status();
    if !status.is_success() {
        return Err(format!("the server answered {status}"));
    }
    let too_big = || format!("larger than {} MB", MAX_FEED_BYTES / (1024 * 1024));
    if response
        .content_length()
        .is_some_and(|length| length > MAX_FEED_BYTES as u64)
    {
        return Err(too_big());
    }
    let mut body = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|err| format!("download failed: {err}"))?
    {
        if body.len() + chunk.len() > MAX_FEED_BYTES {
            return Err(too_big());
        }
        body.extend_from_slice(&chunk);
    }
    Ok(String::from_utf8_lossy(&body).into_owned())
}

/// The domains in a hosts-format feed: `domain` or `0.0.0.0 domain` lines, `#` comments; only
/// entries shaped like a domain name (so an HTML page parses to nothing).
pub(crate) fn parse_feed(text: &str) -> HashSet<String> {
    let mut domains = HashSet::new();
    for line in text.lines() {
        let entry = line.split('#').next().unwrap_or("").trim();
        if entry.is_empty() {
            continue;
        }
        let domain = match entry.split_once(char::is_whitespace) {
            Some(("0.0.0.0", domain)) => domain.trim(),
            Some(_) => continue,
            None => entry,
        };
        let domain = domain.trim_end_matches('.').to_lowercase();
        if looks_like_a_domain(&domain) {
            domains.insert(domain);
        }
    }
    domains
}

fn looks_like_a_domain(name: &str) -> bool {
    name.len() <= 253
        && name.contains('.')
        && name.split('.').all(|label| {
            !label.is_empty()
                && label.len() <= 63
                && label
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
        })
}
