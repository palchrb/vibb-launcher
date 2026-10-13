//! Settings › Security Log (`/security`): currently blocked addresses (with Unban) and the
//! `security_events` audit trail - sign-ins and failed ones, lockouts, refused requests,
//! enrollments, the parent's changes and what the phones report (`security::record_security_event`
//! and its siblings write it). Design 31 step 14: each event a row with a label instead of its
//! id ([event_label]), the time in the Phone Setup zone, the stored detail behind the row's ⓘ.

use askama::Template;
use axum::Form;
use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Redirect, Response};
use chrono::{DateTime, Utc};

use crate::AppState;
use crate::display_time::{Zone, parse_db_utc};
use crate::models::{BannedIp, SecurityEvent};

/// One event as a row: label · when, "admin · 192.0.2.1" under it, the stored detail behind ⓘ.
struct EventRow {
    label: String,
    /// The event id, for the ⓘ panel only.
    kind: String,
    who: String,
    when: String,
    exact: String,
    detail: Option<String>,
}

/// A blocked address: until when (local) and why.
struct BanRow {
    ip: String,
    until: String,
    exact: String,
    reason: Option<String>,
}

#[derive(Template)]
#[template(path = "security.html")]
struct SecurityTemplate {
    title: String,
    events: Vec<EventRow>,
    bans: Vec<BanRow>,
    /// `?unbanned=<address>`: the flash in Blocked Addresses.
    unbanned: Option<String>,
    /// Showing older events (`?before=`): a link back to the newest.
    paged: bool,
    /// The id to page on from, when there may be older events.
    older_than: Option<i64>,
}

/// Events shown per page; older ones are a link away (security review M7: a flood can't push a
/// sign-in attempt out of reach).
const EVENTS_PER_PAGE: i64 = 200;

/// What a parent reads for an event id. Every id this server writes has its label; anything else
/// (an id from an older version) reads as its words ("music_entry_moved" -> "Music entry moved"),
/// never as the raw id - that stays in the row's ⓘ.
pub(crate) fn event_label(kind: &str) -> String {
    let label = match kind {
        "login_success" => "Signed in",
        "login_failed" => "Wrong password",
        "login_locked" => "Sign-in while locked",
        "totp_failed" => "Wrong two-factor code",
        "totp_reset" => "Two-factor login reset",
        "totp_reset_refused" => "Two-factor reset refused",
        "password_changed" => "Password changed",
        "signed_out_everywhere" => "Signed out everywhere",
        "ip_banned" => "Address blocked",
        "admin_refused" => "Request from outside refused",
        "csrf_refused" => "Request from another site refused",
        "device_auth_failed" => "Phone sign-in refused",
        "enroll_failed" => "Wrong setup code",
        "enroll_typed_paused" => "Setup codes paused",
        "enroll_code_created" => "New setup code",
        "device_enrolled" => "Phone connected",
        "access_revoked" => "Phone disconnected",
        "device_command_queued" => "Command sent to phone",
        "device_command_cancelled" => "Command cancelled",
        "device_command_replaced" => "Command replaced",
        "device_wipe_queued" => "Erase sent to phone",
        "kid_pin_changed" => "Child PIN changed",
        "kid_pin_cleared" => "Child PIN removed",
        "override_pin_changed" => "Parent PIN changed",
        "override_pin_cleared" => "Parent PIN removed",
        "parent_pin_used" => "Parent PIN used on phone",
        "parent_pin_lockout" => "Parent PIN locked on phone",
        "phone_foreign_access" => "Another app has control",
        "phone_relock_findings" => "Phone locked again: findings",
        "phone_app_blocked" => "App blocked on phone",
        "boot_unseen" => "Phone started without Vibb",
        "app_installed" => "App installed on phone",
        "play_install_mode" => "Play Store opened for installs",
        "time_lift" => "Time rule paused",
        "time_lift_ended" => "Rule pause ended",
        "time_rule_created" => "Time rule added",
        "time_rule_updated" => "Time rule changed",
        "time_rule_deleted" => "Time rule deleted",
        "screen_time_saved" => "Screen time changed",
        "location_policy_changed" => "Location setting changed",
        "location_retention_changed" => "Location history changed",
        "dns_log_changed" => "Blocked Activity log changed",
        "lock_screen_text_changed" => "Lock screen text changed",
        "lock_screen_app_changed" => "Lock screen app changed",
        "lock_wallpaper_changed" => "Lock screen background changed",
        "backup_schedule_changed" => "Backup schedule changed",
        "backup_restore_triggered" => "Backup restore started",
        "backup_restored" => "Backup restored",
        "drive_format_triggered" => "Drive format started",
        "system_update_triggered" => "Server update started",
        "system_restart_triggered" => "Server restart started",
        "app_update_schedule_changed" => "Automatic updates changed",
        "update_schedule_changed" => "System update schedule changed",
        "os_check_triggered" => "System update check started",
        "os_upgrade_triggered" => "System updates started",
        "tailscale_update_triggered" => "Tailscale update started",
        "reboot_triggered" => "Restart of the Pi started",
        "storytel_login_saved" => "Storytel login saved",
        "storytel_login_cleared" => "Storytel login removed",
        "storytel_login_refused" => "Storytel refused the login",
        "storytel_login_sent" => "Storytel login sent to phone",
        _ => return words_of(kind),
    };
    label.to_string()
}

/// "music_entry_moved" -> "Music entry moved"; an empty id reads "Event".
fn words_of(kind: &str) -> String {
    let words = kind
        .split(|c: char| c == '_' || c == '-' || c.is_whitespace())
        .filter(|w| !w.is_empty())
        .collect::<Vec<_>>()
        .join(" ");
    let mut chars = words.chars();
    match chars.next() {
        Some(first) => first.to_uppercase().chain(chars).collect(),
        None => "Event".to_string(),
    }
}

fn event_row(event: SecurityEvent, zone: &Zone, now: DateTime<Utc>) -> EventRow {
    let at = parse_db_utc(&event.created_at);
    let who = [event.username.as_deref(), event.ip_address.as_deref()]
        .into_iter()
        .flatten()
        .filter(|part| !part.is_empty())
        .collect::<Vec<_>>()
        .join(" · ");
    EventRow {
        label: event_label(&event.event_type),
        kind: event.event_type,
        who,
        when: at.map_or_else(|| "Time unknown".to_string(), |t| zone.when(t, now)),
        exact: at.map(|t| zone.exact(t)).unwrap_or_default(),
        detail: event.detail.filter(|d| !d.trim().is_empty()),
    }
}

fn server_error(err: sqlx::Error) -> Response {
    tracing::error!(%err, "couldn't read the security log");
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        "Couldn't read the security log - check the server log.",
    )
        .into_response()
}

#[derive(serde::Deserialize)]
pub struct SecurityLogQuery {
    before: Option<i64>,
    unbanned: Option<String>,
}

/// The page. A DB error is a 500, never an empty log (an empty log would read "nothing happened").
pub async fn security_log(
    State(state): State<AppState>,
    Query(query): Query<SecurityLogQuery>,
) -> Response {
    let before = query.before;
    let events = sqlx::query_as::<_, SecurityEvent>(
        "SELECT * FROM security_events WHERE (? IS NULL OR id < ?) ORDER BY id DESC LIMIT ?",
    )
    .bind(before)
    .bind(before)
    .bind(EVENTS_PER_PAGE)
    .fetch_all(&state.db)
    .await;
    let events = match events {
        Ok(events) => events,
        Err(err) => return server_error(err),
    };
    let older_than = (events.len() as i64 == EVENTS_PER_PAGE)
        .then(|| events.last().map(|event| event.id))
        .flatten();

    let bans = sqlx::query_as::<_, BannedIp>(
        "SELECT * FROM banned_ips WHERE banned_until > datetime('now') ORDER BY banned_until DESC",
    )
    .fetch_all(&state.db)
    .await;
    let bans = match bans {
        Ok(bans) => bans,
        Err(err) => return server_error(err),
    };

    let zone = Zone::load(&state.db).await;
    let now = Utc::now();
    let bans = bans
        .into_iter()
        .map(|ban| {
            let until = parse_db_utc(&ban.banned_until);
            BanRow {
                ip: ban.ip_address,
                until: until.map_or_else(|| "a while".to_string(), |t| zone.until(t, now)),
                exact: until.map(|t| zone.exact(t)).unwrap_or_default(),
                reason: ban.reason,
            }
        })
        .collect();
    let page = SecurityTemplate {
        title: "Security Log".to_string(),
        events: events
            .into_iter()
            .map(|event| event_row(event, &zone, now))
            .collect(),
        bans,
        unbanned: query.unbanned.filter(|ip| !ip.is_empty()),
        paged: before.is_some(),
        older_than,
    };
    Html(page.render().unwrap()).into_response()
}

#[derive(serde::Deserialize)]
pub struct UnbanForm {
    ip: String,
}

/// A form field, not a path segment: a ban's key may be an IPv6 /64 ("2001:db8:1:2::/64"). Back
/// to Blocked Addresses with the address in the flash (`#banned`); a DB error is a 500.
pub async fn unban_ip(State(state): State<AppState>, Form(form): Form<UnbanForm>) -> Response {
    let result = sqlx::query("DELETE FROM banned_ips WHERE ip_address = ?")
        .bind(&form.ip)
        .execute(&state.db)
        .await;
    if let Err(err) = result {
        tracing::error!(%err, "couldn't unban an address");
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            "Couldn't unban it - nothing was changed. Check the server log.",
        )
            .into_response();
    }
    let ip: String = form_urlencoded::byte_serialize(form.ip.as_bytes()).collect();
    Redirect::to(&format!("/security?unbanned={ip}#banned")).into_response()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_event_reads_as_words() {
        assert_eq!(event_label("login_failed"), "Wrong password");
        assert_eq!(
            event_label("device_command_queued"),
            "Command sent to phone"
        );
        assert_eq!(event_label("music_entry_moved"), "Music entry moved");
        assert_eq!(event_label("probe"), "Probe");
        assert_eq!(event_label(""), "Event");
        assert_eq!(event_label("__"), "Event");
        assert_eq!(event_label("ÿes_no"), "Ÿes no");
    }
}
