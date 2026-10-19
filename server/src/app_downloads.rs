//! Catalog downloads on the phone (design `docs/design/13-app-downloads.md` at the monorepo root,
//! with its QA review and decisions): what the phone reports in `app_downloads` - a full snapshot
//! of every download it waits for or runs, whether its "Wi-Fi only" switch is on and what network
//! it is on - stored re-serialized and capped like `update_fence`, and Device › Apps' texts for it
//! (design 31 step 6: short, in Phone Setup's zone). Pure.

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

use crate::display_time::Zone;

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
    /// "installing", or a release the phone refused to install (security review M10, the launcher's
    /// check of the APK against the app's pins): "refused_package" | "refused_signer" |
    /// "refused_unreadable" | "refused_unpinned".
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

/// A phone-supplied time, local: "since 10:58", "since yesterday 21:40", "since 12 Oct".
fn time(ms: i64) -> Option<DateTime<Utc>> {
    DateTime::from_timestamp_millis(ms)
}

/// "120 of 326 MB", "120 MB so far", or nothing before the first byte.
pub(crate) fn progress(entry: &AppDownloadEntry) -> Option<String> {
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

/// The phone refused to install this release (security review M10).
pub fn refused(entry: &AppDownloadEntry) -> bool {
    entry.state.starts_with("refused")
}

/// What the download does, lower case, so it reads after "update": "waiting for Wi-Fi",
/// "refused: signed with another key". Never a raw code (design 31 §4.2).
fn state_text(state: &str) -> &'static str {
    match state {
        "waiting_wifi" => "waiting for Wi-Fi",
        "waiting_network" => "waiting for a network",
        "waiting_roaming" => "paused while roaming",
        "waiting_space" => "waiting for storage space",
        "downloading" => "downloading",
        "installing" => "installing",
        "refused_package" => "refused: it is another app",
        "refused_signer" => "refused: signed with another key",
        "refused_unreadable" => "refused: the file is unreadable",
        "refused_unpinned" => "refused: its key isn't known yet",
        other if other.starts_with("refused") => "refused by the phone",
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

/// `head`, then " · since 10:58" while it waits, " · any network from Mon 14:30" while an update
/// waits for Wi-Fi with a grace, and " · 120 of 326 MB". Times in Phone Setup's zone (design 31
/// M4).
fn line(head: String, entry: &AppDownloadEntry, zone: &Zone, now: DateTime<Utc>) -> String {
    let mut parts = vec![head];
    if waiting(&entry.state)
        && let Some(since) = entry.since_ms.and_then(time)
    {
        parts.push(format!("since {}", zone.when(since, now)));
    }
    if entry.state == "waiting_wifi"
        && let Some(any) = entry.any_network_at_ms.and_then(time)
    {
        parts.push(format!("any network from {}", zone.until(any, now)));
    }
    parts.extend(progress(entry));
    parts.join(" · ")
}

/// A catalog app that isn't on the phone yet: "Waiting for Wi-Fi · since 10:58 · 120 of 326 MB".
pub fn new_app_label(entry: &AppDownloadEntry, zone: &Zone, now: DateTime<Utc>) -> String {
    line(capitalized(state_text(&entry.state)), entry, zone, now)
}

/// An installed catalog app with a newer release: "Installed · update waiting for Wi-Fi · since
/// 10:58".
pub fn update_label(entry: &AppDownloadEntry, zone: &Zone, now: DateTime<Utc>) -> String {
    line(
        format!("Installed · update {}", state_text(&entry.state)),
        entry,
        zone,
        now,
    )
}

/// "0.32.0" for `launcher-v0.32.0@123`: the tag without the asset id and the release prefix.
pub fn release_name(tag: &str) -> &str {
    let tag = tag.split('@').next().unwrap_or(tag);
    tag.strip_prefix("launcher-v")
        .or_else(|| tag.strip_prefix('v'))
        .unwrap_or(tag)
}

/// Vibb's own update, named: "Installed · update 0.32.0 waiting for Wi-Fi · any network from Mon
/// 14:30". Downloaded, it waits for the night window ("downloaded").
pub fn launcher_label(entry: &AppDownloadEntry, zone: &Zone, now: DateTime<Utc>) -> String {
    let state = match entry.state.as_str() {
        "installing" => "downloaded",
        other => state_text(other),
    };
    line(
        format!(
            "Installed · update {} {state}",
            release_name(&entry.release_tag)
        ),
        entry,
        zone,
        now,
    )
}

/// The phone's network at its last report, as a row value: "Wi-Fi", "Mobile data", "Roaming",
/// "No network"; `None` for a value this server doesn't know.
pub fn network(downloads: &AppDownloads) -> Option<&'static str> {
    Some(match downloads.network.as_str() {
        "unmetered" => "Wi-Fi",
        "metered" => "Mobile data",
        "roaming" => "Roaming",
        "none" => "No network",
        _ => return None,
    })
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
            // 01:00 UTC, 03:00 in Oslo.
            since_ms: Some(OCT_6 + 3_600_000),
            any_network_at_ms: None,
        }
    }

    fn oslo() -> Zone {
        Zone::from_setting("Europe/Oslo")
    }

    /// The same day, 12:00 UTC.
    fn now() -> DateTime<Utc> {
        time(OCT_6 + 12 * 3_600_000).unwrap()
    }

    #[test]
    fn labels_say_what_waits_and_since_when() {
        let (zone, now) = (oslo(), now());
        let e = entry("waiting_wifi", 120_000_000, Some(326_123_456));
        assert_eq!(
            new_app_label(&e, &zone, now),
            "Waiting for Wi-Fi · since 03:00 · 120 of 326 MB"
        );
        assert_eq!(
            update_label(&e, &zone, now),
            "Installed · update waiting for Wi-Fi · since 03:00 · 120 of 326 MB"
        );
        assert_eq!(
            new_app_label(&entry("waiting_wifi", 0, None), &zone, now),
            "Waiting for Wi-Fi · since 03:00"
        );
        assert_eq!(
            new_app_label(&entry("downloading", 0, Some(326_000_000)), &zone, now),
            "Downloading · 0 of 326 MB"
        );
        assert_eq!(
            new_app_label(&entry("waiting_roaming", 5_000_000, None), &zone, now),
            "Paused while roaming · since 03:00 · 5 MB so far"
        );
        assert_eq!(
            new_app_label(&entry("installing", 0, None), &zone, now),
            "Installing"
        );
        // A day later it says which day; in the fallback zone, UTC and labelled.
        let later = now + chrono::Duration::days(1);
        assert_eq!(
            new_app_label(&e, &zone, later),
            "Waiting for Wi-Fi · since yesterday 03:00 · 120 of 326 MB"
        );
        assert_eq!(
            new_app_label(&entry("waiting_space", 0, None), &Zone::utc_fallback(), now),
            "Waiting for storage space · since 01:00 UTC"
        );
    }

    /// Security review M10: the phone's refusal of a release, with its reason - never "waiting".
    #[test]
    fn a_refused_release_says_why() {
        let (zone, now) = (oslo(), now());
        let e = entry("refused_signer", 0, None);
        assert!(refused(&e));
        assert_eq!(
            new_app_label(&e, &zone, now),
            "Refused: signed with another key"
        );
        assert_eq!(
            update_label(&e, &zone, now),
            "Installed · update refused: signed with another key"
        );
        for (state, text) in [
            ("refused_package", "Refused: it is another app"),
            ("refused_unreadable", "Refused: the file is unreadable"),
            ("refused_unpinned", "Refused: its key isn't known yet"),
            ("refused_later", "Refused by the phone"),
        ] {
            assert_eq!(new_app_label(&entry(state, 0, None), &zone, now), text);
        }
        assert!(!refused(&entry("waiting_wifi", 0, None)));
    }

    #[test]
    fn the_launcher_row_names_the_release_and_the_grace() {
        let (zone, now) = (oslo(), now());
        let mut e = entry("waiting_wifi", 0, None);
        e.release_tag = "launcher-v0.32.0@99".to_string();
        e.any_network_at_ms = Some(OCT_6 + 3 * DAY);
        assert_eq!(
            launcher_label(&e, &zone, now),
            "Installed · update 0.32.0 waiting for Wi-Fi · since 03:00 · any network from Fri 02:00"
        );
        e.state = "waiting_roaming".to_string();
        assert_eq!(
            launcher_label(&e, &zone, now),
            "Installed · update 0.32.0 paused while roaming · since 03:00"
        );
        e.state = "installing".to_string();
        assert_eq!(
            launcher_label(&e, &zone, now),
            "Installed · update 0.32.0 downloaded"
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
        assert_eq!(network(&parsed), Some("Mobile data"));
        assert_eq!(sanitize(&serde_json::json!([1, 2])), None);
    }
}
