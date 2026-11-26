//! Design 138 (`docs/design/138-confirmed-zone-networks.md`): the Wi-Fi routers a parent confirmed
//! for a zone (`zone_networks`, migration 0180), one row per BSSID. The phone reports the network it
//! is on in `zone_watch.wifi` (a named WPA2/WPA3 personal network, unmetered, a readable BSSID) while
//! its policy has zones; the zone's page offers Confirm for it, and a confirm binds to what the
//! newest report with `wifi` says - never to the form. The policy serves each zone's unblocked rows
//! (`location_policy.zones[].networks`); a router the phone saw far outside the zone comes back in
//! `zone_watch.wifi_struck` and is blocked here (one orange line on the zone's page). The security
//! log names the phone and the zone, never a network's name or address.

use serde::{Deserialize, Serialize};

use crate::zones::{self, Circle, Side};

/// D2 (H1): at most this many rows a zone, blocked rows counted (L8).
pub const MAX_ZONE_NETWORKS: i64 = 16;
/// D4: the report a confirm binds to is at most this old.
pub const REPORT_FRESH: &str = "-1 hour";
/// D3: the report's key for the struck confirmations (`zone_watch.wifi_struck`).
pub const STRUCK_KEY: &str = "wifi_struck";
/// D3: a report's `wifi_struck` holds at most this many ids.
pub const STRUCK_MAX: usize = 80;

/// A confirmed router as stored.
#[derive(sqlx::FromRow, Clone, Debug, PartialEq)]
pub struct ZoneNetwork {
    pub id: i64,
    pub zone_id: i64,
    pub ssid: String,
    pub bssid: String,
    pub mld: Option<String>,
    pub blocked_at: Option<String>,
}

/// A confirmed router as the phone gets it (`location_policy.zones[].networks`). Pinned by
/// `policy_json_keys_snapshot`, the launcher's `PolicyResponseCompatTest` and
/// `contracts/core/zone_networks.json`.
#[derive(Serialize, Clone, Debug, PartialEq)]
pub struct PolicyNetwork {
    pub id: i64,
    pub ssid: String,
    pub bssid: String,
    pub mld: Option<String>,
}

/// The network the phone reported (`zone_watch.wifi`), as kept.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct ReportedWifi {
    pub ssid: String,
    pub bssid: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mld: Option<String>,
}

/// A BSSID as six lowercase hex pairs - unicast, never all zero or Android's redacted
/// `02:00:00:00:00:00`; `None` for anything else.
pub fn bssid(raw: &str) -> Option<String> {
    let lower = raw.trim().to_ascii_lowercase();
    let parts: Vec<&str> = lower.split(':').collect();
    if parts.len() != 6
        || parts
            .iter()
            .any(|p| p.len() != 2 || !p.chars().all(|c| c.is_ascii_hexdigit()))
    {
        return None;
    }
    let first = u8::from_str_radix(parts[0], 16).ok()?;
    if first & 1 == 1 || lower == "00:00:00:00:00:00" || lower == "02:00:00:00:00:00" {
        return None;
    }
    Some(lower)
}

/// A network's name the page may show: 1-32 bytes, no control characters.
pub fn ssid_ok(ssid: &str) -> bool {
    !ssid.is_empty() && ssid.len() <= 32 && !ssid.chars().any(char::is_control)
}

/// `zone_watch.wifi` through its shape (D3); `None` drops it alone.
pub fn clean_wifi(value: &serde_json::Value) -> Option<ReportedWifi> {
    let ssid = value.get("ssid")?.as_str()?;
    if !ssid_ok(ssid) {
        return None;
    }
    let b = bssid(value.get("bssid")?.as_str()?)?;
    let mld = match value.get("mld") {
        None | Some(serde_json::Value::Null) => None,
        Some(v) => Some(bssid(v.as_str()?)?),
    };
    Some(ReportedWifi {
        ssid: ssid.to_string(),
        bssid: b,
        mld,
    })
}

/// `zone_watch.wifi_struck` through its shape: positive ids, at most [STRUCK_MAX]; anything else
/// none.
pub fn clean_struck(value: &serde_json::Value) -> Vec<i64> {
    let Some(list) = value.as_array() else {
        return Vec::new();
    };
    if list.len() > STRUCK_MAX {
        return Vec::new();
    }
    let ids: Option<Vec<i64>> = list.iter().map(|v| v.as_i64().filter(|n| *n > 0)).collect();
    ids.unwrap_or_default()
}

/// D1 (H1): the same router - the BSSID's first five octets (one router's bands), or the same
/// multi-link address known on both sides.
pub fn same_router(a: &str, a_mld: Option<&str>, b: &str, b_mld: Option<&str>) -> bool {
    (a.len() >= 14 && b.len() >= 14 && a[..14] == b[..14]) || (a_mld.is_some() && a_mld == b_mld)
}

/// The rows of every zone of a phone, oldest first.
pub async fn of_device<'e, E: sqlx::SqliteExecutor<'e>>(
    db: E,
    device_id: i64,
) -> Result<Vec<ZoneNetwork>, sqlx::Error> {
    sqlx::query_as(
        "SELECT n.id, n.zone_id, n.ssid, n.bssid, n.mld, n.blocked_at FROM zone_networks n \
         JOIN device_zones z ON z.id = n.zone_id WHERE z.device_id = ? ORDER BY n.id",
    )
    .bind(device_id)
    .fetch_all(db)
    .await
}

/// D13: a zone's unblocked rows as the policy serves them.
pub fn policy_networks(rows: &[ZoneNetwork], zone_id: i64) -> Vec<PolicyNetwork> {
    rows.iter()
        .filter(|n| n.zone_id == zone_id && n.blocked_at.is_none())
        .map(|n| PolicyNetwork {
            id: n.id,
            ssid: n.ssid.clone(),
            bssid: n.bssid.clone(),
            mld: n.mld.clone(),
        })
        .collect()
}

/// D4 (L3): the phone's newest report since its enrolment that has `wifi`, at most an hour old - a
/// report without it (a redacted read) neither shows nor hides it.
pub async fn current_wifi(
    db: &sqlx::SqlitePool,
    device_id: i64,
) -> Result<Option<ReportedWifi>, sqlx::Error> {
    let rows: Vec<Option<String>> = sqlx::query_scalar(&format!(
        "SELECT zone_watch_json FROM device_status WHERE device_id = ?1 AND reported_at >= {} \
         AND reported_at >= datetime('now', '{REPORT_FRESH}') AND zone_watch_json LIKE '%\"wifi\":%' \
         ORDER BY reported_at DESC, id DESC LIMIT 1",
        crate::models::ENROLLED_AT
    ))
    .bind(device_id)
    .fetch_all(db)
    .await?;
    Ok(rows
        .into_iter()
        .flatten()
        .next()
        .and_then(|json| serde_json::from_str::<serde_json::Value>(&json).ok())
        .and_then(|v| v.get("wifi").and_then(clean_wifi)))
}

/// The orange line on a zone's Wi-Fi group (at most one; D7, §4). Revision 3 (the location review):
/// no "Ukjent ruter" line - it invited a parent to confirm a router set up with the home's name and
/// password; the Bekreft row for the phone's network stays, the parent's own step.
#[derive(Clone, Debug, PartialEq)]
pub enum Orange {
    /// A confirmation the phone saw far outside the zone ("Sett et annet sted").
    Elsewhere(String),
}

/// A confirmed name on the zone's page: its first row (the remove's target) and whether any of
/// its rows is blocked.
#[derive(Clone, Debug, PartialEq)]
pub struct NameRow {
    pub id: i64,
    pub ssid: String,
    pub blocked: bool,
}

/// What the zone's Wi-Fi group shows.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct WifiView {
    pub names: Vec<NameRow>,
    /// The phone's network with Confirm (D4).
    pub current: Option<ReportedWifi>,
    pub orange: Option<Orange>,
}

/// D4, §4, pure: the group from the zone's rows, the phone's current network and whether its
/// newest fix is surely outside the zone.
pub fn view_of(rows: &[ZoneNetwork], current: Option<ReportedWifi>, outside: bool) -> WifiView {
    let mut names: Vec<NameRow> = Vec::new();
    for n in rows {
        match names.iter_mut().find(|r| r.ssid == n.ssid) {
            Some(row) => row.blocked |= n.blocked_at.is_some(),
            None => names.push(NameRow {
                id: n.id,
                ssid: n.ssid.clone(),
                blocked: n.blocked_at.is_some(),
            }),
        }
    }
    let confirmed = |w: &ReportedWifi| {
        rows.iter().any(|n| {
            n.blocked_at.is_none()
                && n.ssid == w.ssid
                && same_router(&n.bssid, n.mld.as_deref(), &w.bssid, w.mld.as_deref())
        })
    };
    let offer = current.filter(|w| !outside && !confirmed(w));
    let orange = names
        .iter()
        .find(|r| r.blocked)
        .map(|name| Orange::Elsewhere(name.ssid.clone()));
    WifiView {
        names,
        current: offer,
        orange,
    }
}

/// The zone's group as the page shows it (D4: the phone's newest fix surely outside hides the
/// Bekreft row - only a fix at most an hour old, QA L1: an old one says nothing about where the
/// phone is now).
pub async fn view(
    db: &sqlx::SqlitePool,
    device_id: i64,
    zone: &zones::Zone,
) -> Result<WifiView, sqlx::Error> {
    let rows: Vec<ZoneNetwork> = of_device(db, device_id)
        .await?
        .into_iter()
        .filter(|n| n.zone_id == zone.id)
        .collect();
    let current = current_wifi(db, device_id).await?;
    let fix: Option<(f64, f64, Option<f64>, String)> = sqlx::query_as(
        "SELECT latitude, longitude, accuracy_meters, captured_at FROM device_locations \
         WHERE device_id = ? ORDER BY captured_at DESC, id DESC LIMIT 1",
    )
    .bind(device_id)
    .fetch_optional(db)
    .await?;
    let circle: Circle = zone.circle();
    let hour_ago = chrono::Utc::now() - chrono::Duration::hours(1);
    let outside = fix.is_some_and(|(lat, lng, acc, at)| {
        chrono::DateTime::parse_from_rfc3339(&at).is_ok_and(|t| t >= hour_ago)
            && zones::side(&circle, lat, lng, acc) == Some(Side::Outside)
    });
    Ok(view_of(&rows, current, outside))
}

/// What a confirm did.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Confirmed {
    /// A new row (or a blocked one replaced).
    Written,
    /// Already confirmed: nothing written.
    Already,
    /// The phone's newest report with `wifi` isn't the router the page showed.
    Changed,
    /// The zone holds [MAX_ZONE_NETWORKS] rows.
    Full,
}

/// D4: confirm the router the page showed (`bssid`, and its `mld` when the page had one) for this
/// phone's zone - only when the phone's newest report with `wifi`, at most an hour old, still says
/// that router (the BSSID, or the same multi-link address); the name and address stored are the
/// report's.
pub async fn confirm(
    db: &sqlx::SqlitePool,
    device_id: i64,
    zone_id: i64,
    posted_bssid: &str,
    posted_mld: Option<&str>,
    admin_id: i64,
) -> Result<Confirmed, sqlx::Error> {
    let Some(wifi) = current_wifi(db, device_id).await? else {
        return Ok(Confirmed::Changed);
    };
    let posted = bssid(posted_bssid);
    let posted_mld = posted_mld.and_then(bssid);
    let same = posted.as_deref() == Some(wifi.bssid.as_str())
        || (posted_mld.is_some() && posted_mld == wifi.mld);
    if !same {
        return Ok(Confirmed::Changed);
    }
    let mut tx = db.begin_with("BEGIN IMMEDIATE").await?;
    let existing: Vec<(i64, String, Option<String>)> =
        sqlx::query_as("SELECT id, bssid, blocked_at FROM zone_networks WHERE zone_id = ?")
            .bind(zone_id)
            .fetch_all(&mut *tx)
            .await?;
    let same_row = existing.iter().find(|(_, b, _)| *b == wifi.bssid);
    match same_row {
        Some((_, _, None)) => return Ok(Confirmed::Already),
        Some((id, _, Some(_))) => {
            sqlx::query("DELETE FROM zone_networks WHERE id = ?")
                .bind(id)
                .execute(&mut *tx)
                .await?;
        }
        None if existing.len() as i64 >= MAX_ZONE_NETWORKS => return Ok(Confirmed::Full),
        None => {}
    }
    sqlx::query(
        "INSERT INTO zone_networks (zone_id, ssid, bssid, mld, confirmed_by) VALUES (?, ?, ?, ?, ?)",
    )
    .bind(zone_id)
    .bind(&wifi.ssid)
    .bind(&wifi.bssid)
    .bind(&wifi.mld)
    .bind(admin_id)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(Confirmed::Written)
}

/// D2: remove a confirmed name - every row of the named row's name in the zone. `false`: no such
/// row in this zone.
pub async fn remove(
    db: &sqlx::SqlitePool,
    zone_id: i64,
    network_id: i64,
) -> Result<bool, sqlx::Error> {
    let gone = sqlx::query(
        "DELETE FROM zone_networks WHERE zone_id = ?1 AND ssid = \
         (SELECT ssid FROM zone_networks WHERE id = ?2 AND zone_id = ?1)",
    )
    .bind(zone_id)
    .bind(network_id)
    .execute(db)
    .await?;
    Ok(gone.rows_affected() > 0)
}

/// D7, D12: the struck ids of a report - this phone's rows not yet blocked get `blocked_at`;
/// returns the names of the zones whose rows were blocked now (another phone's ids do nothing).
pub async fn block_struck(
    db: &sqlx::SqlitePool,
    device_id: i64,
    ids: &[i64],
) -> Result<Vec<String>, sqlx::Error> {
    let mut zones_hit = Vec::new();
    for id in ids {
        let zone: Option<String> = sqlx::query_scalar(
            "UPDATE zone_networks SET blocked_at = datetime('now') WHERE id = ? AND blocked_at IS NULL \
             AND zone_id IN (SELECT id FROM device_zones WHERE device_id = ?) \
             RETURNING (SELECT name FROM device_zones WHERE device_zones.id = zone_networks.zone_id)",
        )
        .bind(id)
        .bind(device_id)
        .fetch_optional(db)
        .await?;
        if let Some(name) = zone {
            zones_hit.push(name);
        }
    }
    Ok(zones_hit)
}

/// D12: a zone moved further than its radius (from the old centre, by the old radius) loses its
/// confirmations.
pub fn moved_past_radius(before: &Circle, after: &Circle) -> bool {
    zones::distance_m(
        before.latitude,
        before.longitude,
        after.latitude,
        after.longitude,
    ) > before.radius_m as f64
}

#[cfg(test)]
mod tests {
    use super::*;

    fn row(id: i64, ssid: &str, bssid: &str, blocked: bool) -> ZoneNetwork {
        ZoneNetwork {
            id,
            zone_id: 1,
            ssid: ssid.into(),
            bssid: bssid.into(),
            mld: None,
            blocked_at: blocked.then(|| "2026-11-26 10:00:00".to_string()),
        }
    }

    fn wifi(ssid: &str, bssid: &str) -> ReportedWifi {
        ReportedWifi {
            ssid: ssid.into(),
            bssid: bssid.into(),
            mld: None,
        }
    }

    #[test]
    fn a_bssid_is_six_unicast_pairs_never_redacted() {
        assert_eq!(
            bssid("00:00:5E:00:53:01").as_deref(),
            Some("00:00:5e:00:53:01")
        );
        assert_eq!(bssid("02:00:00:00:00:00"), None);
        assert_eq!(bssid("00:00:00:00:00:00"), None);
        assert_eq!(bssid("01:00:5e:00:53:01"), None, "multicast");
        assert_eq!(bssid("00:00:5e:00:53"), None);
        assert!(ssid_ok("Net-A"));
        assert!(!ssid_ok(&"x".repeat(33)));
        assert!(!ssid_ok("Net\u{7}A"));
        assert!(!ssid_ok(""));
    }

    #[test]
    fn the_same_router_by_five_octets_or_its_multi_link_address() {
        assert!(same_router(
            "00:00:5e:00:53:01",
            None,
            "00:00:5e:00:53:02",
            None
        ));
        assert!(!same_router(
            "00:00:5e:00:53:01",
            None,
            "00:00:5e:00:54:01",
            None
        ));
        assert!(same_router(
            "00:00:5e:00:53:01",
            Some("00:00:5e:00:55:10"),
            "00:00:5e:00:56:01",
            Some("00:00:5e:00:55:10")
        ));
    }

    #[test]
    fn the_group_offers_confirm_and_one_orange_line() {
        let w = wifi("Net-A", "00:00:5e:00:53:01");
        // Nothing confirmed: Confirm, no line.
        let v = view_of(&[], Some(w.clone()), false);
        assert_eq!(v.current, Some(w.clone()));
        assert_eq!(v.orange, None);
        // Surely outside: no Confirm.
        assert_eq!(view_of(&[], Some(w.clone()), true).current, None);
        // The same router's other band confirmed: nothing to offer.
        let rows = [row(1, "Net-A", "00:00:5e:00:53:02", false)];
        let v = view_of(&rows, Some(w.clone()), false);
        assert_eq!((v.current, v.orange), (None, None));
        // Another router of a confirmed name: the Bekreft row, no orange line (revision 3).
        let other = wifi("Net-A", "00:00:5e:00:54:01");
        let v = view_of(&rows, Some(other.clone()), false);
        assert_eq!(v.current, Some(other.clone()));
        assert_eq!(v.orange, None);
        // ... and no Bekreft with the newest fix surely outside.
        assert_eq!(view_of(&rows, Some(other.clone()), true).current, None);
        // A blocked row: "Sett et annet sted" comes first, one line only.
        let rows = [
            row(1, "Net-A", "00:00:5e:00:53:02", true),
            row(2, "Net-A", "00:00:5e:00:57:02", false),
        ];
        let v = view_of(&rows, Some(other), false);
        assert_eq!(v.orange, Some(Orange::Elsewhere("Net-A".into())));
        assert_eq!(v.names.len(), 1);
        assert!(v.names[0].blocked);
    }

    #[test]
    fn a_move_past_the_radius_clears() {
        let c = Circle {
            latitude: 59.0,
            longitude: 10.0,
            radius_m: 150,
        };
        let near = Circle {
            latitude: 59.0 + 50.0 / 111_195.0,
            ..c
        };
        let far = Circle {
            latitude: 59.0 + 200.0 / 111_195.0,
            ..c
        };
        assert!(!moved_past_radius(&c, &near));
        assert!(moved_past_radius(&c, &far));
        assert!(!moved_past_radius(&c, &Circle { radius_m: 400, ..c }));
    }
}
