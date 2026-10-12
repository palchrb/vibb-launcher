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
    /// A download of this feed has worked on this server, now or before a restart (its last good
    /// domains are kept in `AppState::dns_feed_dir` - QA of the security wave, Low 8). One that
    /// never has is empty: no phone got its domains from here, so leaving it out shrinks nothing.
    pub loaded: bool,
    /// SHA-256 of the feed's domains as downloaded (before the custom allow list), so an unchanged
    /// download isn't written to disk again.
    pub source_hash: String,
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
/// portal's page) keeps the domains it had - in memory, else its last good copy on disk (written
/// after each changed download), so a restart without internet keeps them too. One that never
/// loaded on this server stays `loaded: false` and empty. A database error keeps the whole
/// compiled list.
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
    let feed_dir = state.dns_feed_dir.as_path();

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
                let source_hash = domains_hash(&domains);
                let unchanged = shared.read().await.lists.iter().any(|old| {
                    old.blocklist_id == list.id && old.loaded && old.source_hash == source_hash
                });
                if !unchanged {
                    save_feed(feed_dir, list.id, &domains).await;
                }
                domains.retain(|domain| !global_custom_allow.contains(domain));
                CompiledList {
                    blocklist_id: list.id,
                    category: list.name.clone(),
                    domains,
                    loaded: true,
                    source_hash,
                    last_error: None,
                }
            }
            Err(err) => {
                tracing::warn!(
                    "DNS blocklist '{}': {err} - keeping its previous domains",
                    list.name
                );
                let in_memory = {
                    let previous = shared.read().await;
                    previous
                        .lists
                        .iter()
                        .find(|old| old.blocklist_id == list.id && old.loaded)
                        .map(|old| (old.domains.clone(), old.source_hash.clone()))
                };
                let kept = match in_memory {
                    Some(kept) => Some(kept),
                    None => load_feed(feed_dir, list.id).await.map(|domains| {
                        let hash = domains_hash(&domains);
                        (domains, hash)
                    }),
                };
                let loaded = kept.is_some();
                let (mut domains, source_hash) = kept.unwrap_or_default();
                domains.retain(|domain| !global_custom_allow.contains(domain));
                CompiledList {
                    blocklist_id: list.id,
                    category: list.name.clone(),
                    domains,
                    loaded,
                    source_hash,
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

    forget_removed_feeds(feed_dir, &blocklists).await;

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
            Some(("0.0.0.0" | "127.0.0.1", domain)) => domain.trim(),
            Some(_) => continue,
            // Adblock syntax for a whole domain (`||example.com^`); any other rule is skipped.
            None => entry
                .strip_prefix("||")
                .and_then(|rule| rule.strip_suffix('^'))
                .unwrap_or(entry),
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

/// SHA-256 (hex) of a feed's domains, in sorted order.
fn domains_hash(domains: &HashSet<String>) -> String {
    use sha2::{Digest, Sha256};
    let mut sorted: Vec<&String> = domains.iter().collect();
    sorted.sort();
    let mut hasher = Sha256::new();
    for domain in sorted {
        hasher.update(domain.as_bytes());
        hasher.update(b"\n");
    }
    hex::encode(hasher.finalize())
}

fn feed_file(dir: &std::path::Path, id: i64) -> std::path::PathBuf {
    dir.join(format!("{id}.txt"))
}

/// Writes a feed's domains (one per line) to `<dir>/<id>.txt`, by a temp file and a rename.
/// Best-effort: a failure only means a restart without internet has to do without them.
async fn save_feed(dir: &std::path::Path, id: i64, domains: &HashSet<String>) {
    let mut sorted: Vec<&String> = domains.iter().collect();
    sorted.sort();
    let mut text = String::with_capacity(sorted.iter().map(|d| d.len() + 1).sum());
    for domain in sorted {
        text.push_str(domain);
        text.push('\n');
    }
    let path = feed_file(dir, id);
    let temp = dir.join(format!("{id}.txt.tmp"));
    let written = async {
        tokio::fs::create_dir_all(dir).await?;
        tokio::fs::write(&temp, text).await?;
        tokio::fs::rename(&temp, &path).await
    }
    .await;
    if let Err(err) = written {
        tracing::warn!("DNS blocklist {id}: couldn't keep its domains on disk: {err}");
    }
}

/// A feed's last good domains from disk, if there are any.
async fn load_feed(dir: &std::path::Path, id: i64) -> Option<HashSet<String>> {
    let text = tokio::fs::read_to_string(feed_file(dir, id)).await.ok()?;
    if text.len() > MAX_FEED_BYTES {
        return None;
    }
    let domains = parse_feed(&text);
    (!domains.is_empty()).then_some(domains)
}

/// Deletes the saved domains of feeds that no longer exist.
async fn forget_removed_feeds(dir: &std::path::Path, feeds: &[DnsBlocklist]) {
    let Ok(mut entries) = tokio::fs::read_dir(dir).await else {
        return;
    };
    while let Ok(Some(entry)) = entries.next_entry().await {
        let name = entry.file_name().to_string_lossy().to_string();
        let Some(id) = name
            .strip_suffix(".txt")
            .and_then(|id| id.parse::<i64>().ok())
        else {
            continue;
        };
        if !feeds.iter().any(|feed| feed.id == id) {
            let _ = tokio::fs::remove_file(entry.path()).await;
        }
    }
}
