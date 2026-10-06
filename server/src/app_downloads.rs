//! Catalog downloads on the phone (design `docs/design/13-app-downloads.md` at the monorepo root,
//! with its QA review and decisions): what the phone reports in `app_downloads` - a full snapshot
//! of every download it waits for or runs, whether its "Wi-Fi only" switch is on and what network
//! it is on - stored re-serialized and capped like `update_fence`, and the device page's texts for
//! it. Pure.

use serde::{Deserialize, Serialize};

/// At most this many downloads are stored.
pub const MAX_ENTRIES: usize = 20;
/// Release tags (`tag@asset_id`) are cut to this many characters.
const MAX_TAG: usize = 100;
/// Short status words ("waiting_wifi", "unmetered", ...).
const MAX_WORD: usize = 32;
/// Phone-supplied times are kept within 1970..2100.
const MAX_MS: i64 = 4_102_444_800_000;
/// Phone-supplied byte counts are kept within 0..=1 TB.
const MAX_BYTES: i64 = 1_000_000_000_000;

fn cut(value: &str, max: usize) -> String {
    value.chars().take(max).collect()
}

/// One download of the phone's `app_downloads`. Unknown fields are ignored, missing ones default.
#[derive(Deserialize, Serialize, Default, Debug, Clone, PartialEq, Eq)]
#[serde(default)]
pub struct AppDownloadEntry {
    pub tracked_app_id: i64,
    /// The release as `GET /api/devices/apps` named it (`tag@asset_id` for GitHub apps).
    pub release_tag: String,
    /// "waiting_wifi" | "waiting_network" | "waiting_roaming" | "waiting_space" | "downloading" |
    /// "installing".
    pub state: String,
    /// Bytes on the phone so far.
    pub bytes: i64,
    /// The file's size, once the phone has seen it.
    pub total: Option<i64>,
    /// When the phone first saw this release (it has waited since then).
    pub since_ms: Option<i64>,
    /// The launcher's own update: from when it may use any (non-roaming) network.
    pub any_network_at_ms: Option<i64>,
}

/// The phone's `app_downloads`.
#[derive(Deserialize, Serialize, Default, Debug, Clone, PartialEq, Eq)]
#[serde(default)]
pub struct AppDownloads {
    /// The phone's "App updates only on Wi-Fi" as it applies it.
    pub wifi_only: bool,
    /// "unmetered" | "metered" | "roaming" | "none".
    pub network: String,
    pub entries: Vec<AppDownloadEntry>,
}

/// `StatusReportRequest.app_downloads` as stored: only the known fields, at most [MAX_ENTRIES]
/// entries, strings cut, numbers clamped; `None` for anything that isn't such an object.
pub fn sanitize(value: &serde_json::Value) -> Option<String> {
    if !value.is_object() {
        return None;
    }
    let mut downloads: AppDownloads = serde_json::from_value(value.clone()).ok()?;
    downloads.network = cut(&downloads.network, MAX_WORD);
    downloads.entries.truncate(MAX_ENTRIES);
    for entry in &mut downloads.entries {
        entry.release_tag = cut(&entry.release_tag, MAX_TAG);
        entry.state = cut(&entry.state, MAX_WORD);
        entry.bytes = entry.bytes.clamp(0, MAX_BYTES);
        entry.total = entry.total.map(|t| t.clamp(0, MAX_BYTES));
        entry.since_ms = entry.since_ms.map(|t| t.clamp(0, MAX_MS));
        entry.any_network_at_ms = entry.any_network_at_ms.map(|t| t.clamp(0, MAX_MS));
    }
    serde_json::to_string(&downloads).ok()
}

pub fn parse(json: Option<&str>) -> Option<AppDownloads> {
    json.and_then(|j| serde_json::from_str(j).ok())
}

impl AppDownloads {
    pub fn entry(&self, tracked_app_id: i64) -> Option<&AppDownloadEntry> {
        self.entries
            .iter()
            .find(|e| e.tracked_app_id == tracked_app_id)
    }
}

/// "9 Oct" (UTC).
fn day(ms: i64) -> Option<String> {
    chrono::DateTime::from_timestamp_millis(ms).map(|t| t.format("%-d %b").to_string())
}

/// "120 of 326 MB", "120 MB so far", or nothing before the first byte.
fn progress(entry: &AppDownloadEntry) -> Option<String> {
    let mb = |b: i64| (b + 500_000) / 1_000_000;
    match entry.total.filter(|t| *t > 0) {
        Some(total) if entry.bytes > 0 || entry.state == "downloading" => {
            Some(format!("{} of {} MB", mb(entry.bytes), mb(total)))
        }
        None if entry.bytes > 0 => Some(format!("{} MB so far", mb(entry.bytes))),
        _ => None,
    }
}

fn waiting(state: &str) -> bool {
    state.starts_with("waiting")
}

/// "Waiting for Wi-Fi", "Downloading", ...
fn state_text(state: &str) -> &'static str {
    match state {
        "waiting_wifi" => "waiting for Wi-Fi",
        "waiting_network" => "waiting for a network",
        "waiting_roaming" => "waiting - the phone is roaming",
        "waiting_space" => "waiting for free storage on the phone",
        "downloading" => "downloading",
        "installing" => "installing",
        _ => "waiting",
    }
}

fn capitalized(text: &str) -> String {
    let mut chars = text.chars();
    match chars.next() {
        Some(first) => first.to_uppercase().chain(chars).collect(),
        None => String::new(),
    }
}

/// " since 6 Oct" while waiting, else nothing.
fn since(entry: &AppDownloadEntry) -> String {
    if !waiting(&entry.state) {
        return String::new();
    }
    entry
        .since_ms
        .and_then(day)
        .map(|d| format!(" since {d}"))
        .unwrap_or_default()
}

fn with_progress(mut line: String, entry: &AppDownloadEntry) -> String {
    if let Some(p) = progress(entry) {
        line.push_str(" · ");
        line.push_str(&p);
    }
    line
}

/// A catalog app that isn't on the phone yet: "Waiting for Wi-Fi since 6 Oct · 120 of 326 MB".
pub fn new_app_label(entry: &AppDownloadEntry) -> String {
    with_progress(
        format!("{}{}", capitalized(state_text(&entry.state)), since(entry)),
        entry,
    )
}

/// An installed catalog app with a newer release: "Installed · update waiting for Wi-Fi since 6 Oct".
pub fn update_label(entry: &AppDownloadEntry) -> String {
    with_progress(
        format!(
            "Installed · update {}{}",
            state_text(&entry.state),
            since(entry)
        ),
        entry,
    )
}

/// "0.32.0" for `launcher-v0.32.0@123`: the tag without the asset id and the release prefix.
pub fn release_name(tag: &str) -> &str {
    let tag = tag.split('@').next().unwrap_or(tag);
    tag.strip_prefix("launcher-v")
        .or_else(|| tag.strip_prefix('v'))
        .unwrap_or(tag)
}

/// The launcher's own update: "Installed · 0.32.0 waits for Wi-Fi, any network from 9 Oct".
pub fn launcher_label(entry: &AppDownloadEntry) -> String {
    let name = release_name(&entry.release_tag);
    let line = match entry.state.as_str() {
        "waiting_wifi" => match entry.any_network_at_ms.and_then(day) {
            Some(d) => format!("{name} waits for Wi-Fi, any network from {d}"),
            None => format!("{name} waits for Wi-Fi"),
        },
        "downloading" => format!("downloading {name}"),
        "installing" => format!("{name} downloaded"),
        other if waiting(other) => format!("{name} is {}", state_text(other)),
        _ => format!("{name} waits"),
    };
    with_progress(format!("Installed · {line}"), entry)
}

/// The Apps card's line about the phone's network, from the last report.
pub fn network_line(downloads: &AppDownloads) -> Option<String> {
    let network = match downloads.network.as_str() {
        "unmetered" => "on Wi-Fi (or another network that isn't metered)",
        "metered" => "on mobile data",
        "roaming" => "roaming - downloads wait until it is home again",
        "none" => "without a network",
        _ => return None,
    };
    Some(format!("Last report: the phone was {network}."))
}

#[cfg(test)]
mod tests {
    use super::*;

    const OCT_6: i64 = 1_791_244_800_000; // 2026-10-06 00:00 UTC
    const DAY: i64 = 24 * 60 * 60 * 1000;

    fn entry(state: &str, bytes: i64, total: Option<i64>) -> AppDownloadEntry {
        AppDownloadEntry {
            tracked_app_id: 4,
            release_tag: "v26.09.4@14".to_string(),
            state: state.to_string(),
            bytes,
            total,
            since_ms: Some(OCT_6 + 3_600_000),
            any_network_at_ms: None,
        }
    }

    #[test]
    fn labels_say_what_waits_and_since_when() {
        let e = entry("waiting_wifi", 120_000_000, Some(326_123_456));
        assert_eq!(
            new_app_label(&e),
            "Waiting for Wi-Fi since 6 Oct · 120 of 326 MB"
        );
        assert_eq!(
            update_label(&e),
            "Installed · update waiting for Wi-Fi since 6 Oct · 120 of 326 MB"
        );
        assert_eq!(
            new_app_label(&entry("waiting_wifi", 0, None)),
            "Waiting for Wi-Fi since 6 Oct"
        );
        assert_eq!(
            new_app_label(&entry("downloading", 0, Some(326_000_000))),
            "Downloading · 0 of 326 MB"
        );
        assert_eq!(
            new_app_label(&entry("waiting_roaming", 5_000_000, None)),
            "Waiting - the phone is roaming since 6 Oct · 5 MB so far"
        );
        assert_eq!(new_app_label(&entry("installing", 0, None)), "Installing");
    }

    #[test]
    fn the_launcher_row_names_the_release_and_the_grace() {
        let mut e = entry("waiting_wifi", 0, None);
        e.release_tag = "launcher-v0.32.0@99".to_string();
        e.any_network_at_ms = Some(OCT_6 + 3 * DAY);
        assert_eq!(
            launcher_label(&e),
            "Installed · 0.32.0 waits for Wi-Fi, any network from 9 Oct"
        );
        e.state = "waiting_roaming".to_string();
        assert_eq!(
            launcher_label(&e),
            "Installed · 0.32.0 is waiting - the phone is roaming"
        );
        assert_eq!(release_name("v1.2"), "1.2");
        assert_eq!(release_name("1.0 beta"), "1.0 beta");
    }

    #[test]
    fn only_known_fields_are_stored_capped() {
        let entries: Vec<serde_json::Value> = (0..30)
            .map(|i| {
                serde_json::json!({
                    "tracked_app_id": i, "release_tag": "x".repeat(300), "state": "waiting_wifi",
                    "bytes": -5, "total": i64::MAX, "since_ms": -1, "secret": "no",
                })
            })
            .collect();
        let stored = sanitize(&serde_json::json!({
            "wifi_only": true, "network": "metered", "entries": entries, "extra": 1,
        }))
        .unwrap();
        assert!(!stored.contains("secret") && !stored.contains("extra"));
        let parsed = parse(Some(&stored)).unwrap();
        assert_eq!(parsed.entries.len(), MAX_ENTRIES);
        assert_eq!(parsed.entries[0].release_tag.len(), MAX_TAG);
        assert_eq!(parsed.entries[0].bytes, 0);
        assert_eq!(parsed.entries[0].total, Some(MAX_BYTES));
        assert_eq!(parsed.entries[0].since_ms, Some(0));
        assert!(parsed.wifi_only);
        assert_eq!(
            network_line(&parsed).as_deref(),
            Some("Last report: the phone was on mobile data.")
        );
        assert_eq!(sanitize(&serde_json::json!([1, 2])), None);
    }
}
