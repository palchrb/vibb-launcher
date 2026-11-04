//! Settings › Security Log (`/security`): currently blocked addresses (with Unban) and the
//! `security_events` audit trail - sign-ins and failed ones, lockouts, refused requests,
//! enrollments, the parent's changes and what the phones report (`security::record_security_event`
//! and its siblings write it). Design 31 step 14: each event a row with a label instead of its
//! id ([event_label]), the time in the Phone Setup zone; since 2026-11-03 who, the detail and the
//! exact time shown under it (no ⓘ).

use crate::i18n::Words;
use askama::Template;
use axum::Form;
use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Redirect, Response};
use chrono::{DateTime, Utc};

use crate::AppState;
use crate::display_time::{Zone, parse_db_utc};
use crate::i18n::Lang;
use crate::models::{BannedIp, SecurityEvent};

/// One event as a plain row: label · when, and under it everything about it - who, the detail,
/// the exact time (the user, 2026-11-03: no ⓘ to open).
struct EventRow {
    label: String,
    /// "Mamma (mamma) · 10.0.0.3 · " then the row's words in the reader's language (`params`,
    /// design 70 §4) and its facts; the facts alone, or an older row's prose, when there are no
    /// words.
    sub: String,
    when: String,
    exact: String,
}

/// A blocked address: until when (local; `None` when the stored time can't be read) and why.
struct BanRow {
    ip: String,
    until: Option<String>,
    exact: String,
    reason: Option<String>,
}

#[derive(Template)]
#[template(path = "security.html")]
struct SecurityTemplate {
    lang: Lang,
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
crate::i18n::words!(SecurityTemplate);

/// Events shown per page; older ones are a link away (security review M7: a flood can't push a
/// sign-in attempt out of reach).
const EVENTS_PER_PAGE: i64 = 200;

/// What a parent reads for an event id, in their language: `security.event.<id>` from the catalog
/// (every id this server writes has one in both, pinned by `tests::every_written_event_has_a_label`,
/// which reads the writers in `src/`); anything else (an id from an older version) reads as its
/// words ("retired_event" -> "Retired event"), never as the raw id.
pub(crate) fn event_label(lang: Lang, kind: &str) -> String {
    match label_key(lang, kind) {
        Some(key) => crate::i18n::t(lang, &key).to_string(),
        None => words_of(lang, kind),
    }
}

/// The catalog key of an id this server writes; `None` for any other (or an id that isn't shaped
/// like one, which could name another key).
fn label_key(lang: Lang, kind: &str) -> Option<String> {
    let shaped = !kind.is_empty()
        && kind
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_');
    let key = format!("security.event.{kind}");
    (shaped && crate::i18n::has(lang, &key)).then_some(key)
}

/// "music_entry_moved" -> "Music entry moved" (an id is its own words, in no language); an empty id reads
/// "Event" in `lang`.
fn words_of(lang: Lang, kind: &str) -> String {
    let words = kind
        .split(|c: char| c == '_' || c == '-' || c.is_whitespace())
        .filter(|w| !w.is_empty())
        .collect::<Vec<_>>()
        .join(" ");
    let mut chars = words.chars();
    match chars.next() {
        Some(first) => first.to_uppercase().chain(chars).collect(),
        None => crate::i18n::t(lang, "security_log.event").to_string(),
    }
}

fn event_row(
    event: SecurityEvent,
    names: &crate::parents::Names,
    zone: &Zone,
    now: DateTime<Utc>,
) -> EventRow {
    let at = parse_db_utc(&event.created_at);
    // "Mamma (mamma) · 10.0.0.3" (design 62 §6); a name that is no account's stays as stored.
    let detail =
        crate::security::Detail::shown(event.detail.as_deref(), event.params.as_deref(), zone, now);
    let sub = [
        event.username.as_deref().map(|u| names.logged(u)),
        event.ip_address,
        detail,
    ]
    .into_iter()
    .flatten()
    .filter(|part| !part.is_empty())
    .collect::<Vec<_>>()
    .join(" · ");
    EventRow {
        label: event_label(zone.lang(), &event.event_type),
        sub,
        when: at.map_or_else(
            || crate::i18n::t(zone.lang(), "security_log.time_unknown").to_string(),
            |t| zone.when(t, now),
        ),
        exact: at.map(|t| zone.exact(t)).unwrap_or_default(),
    }
}

fn server_error(lang: Lang, err: sqlx::Error) -> Response {
    tracing::error!(%err, "couldn't read the security log");
    crate::i18n::error(
        lang,
        StatusCode::INTERNAL_SERVER_ERROR,
        "error.read_security_log",
    )
}

#[derive(serde::Deserialize)]
pub struct SecurityLogQuery {
    before: Option<i64>,
    unbanned: Option<String>,
}

/// The page. A DB error is a 500, never an empty log (an empty log would read "nothing happened").
pub async fn security_log(
    State(state): State<AppState>,
    lang: Lang,
    Query(query): Query<SecurityLogQuery>,
) -> Response {
    let before = query.before;
    let events = sqlx::query_as::<_, SecurityEvent>(
        // A signed-out passkey options call is a count for the ban, not an event (design 74 D1.4).
        "SELECT * FROM security_events WHERE (? IS NULL OR id < ?) \
         AND event_type != 'passkey_started' ORDER BY id DESC LIMIT ?",
    )
    .bind(before)
    .bind(before)
    .bind(EVENTS_PER_PAGE)
    .fetch_all(&state.db)
    .await;
    let events = match events {
        Ok(events) => events,
        Err(err) => return server_error(lang, err),
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
        Err(err) => return server_error(lang, err),
    };

    let names = match crate::parents::Names::load(&state.db).await {
        Ok(names) => names,
        Err(err) => return server_error(lang, err),
    };
    let zone = Zone::load(&state.db, lang).await;
    let now = Utc::now();
    let bans: Vec<BanRow> = bans
        .into_iter()
        .map(|ban| {
            let until = parse_db_utc(&ban.banned_until);
            BanRow {
                ip: ban.ip_address,
                until: until.map(|t| zone.until(t, now)),
                exact: until.map(|t| zone.exact(t)).unwrap_or_default(),
                // Words since design 70 (a sentence's JSON), prose on a ban from before.
                reason: ban
                    .reason
                    .map(|reason| crate::security::Detail::worded_or_stored(&reason, &zone, now)),
            }
        })
        .collect();
    // Only an address that isn't blocked now reads as unbanned (qa-31-s14-code N1).
    let unbanned = query
        .unbanned
        .filter(|ip| !ip.is_empty() && !bans.iter().any(|ban| &ban.ip == ip));
    let page = SecurityTemplate {
        lang,
        title: crate::i18n::t(lang, "security_log.title").to_string(),
        events: events
            .into_iter()
            .map(|event| event_row(event, &names, &zone, now))
            .collect(),
        bans,
        unbanned,
        paged: before.is_some(),
        older_than,
    };
    Html(page.render().unwrap()).into_response()
}

#[derive(serde::Deserialize)]
pub struct UnbanForm {
    ip: String,
}

/// A form field, not a path segment: a ban's key may be an IPv6 /56 ("2001:db8:1:200::/56"). Back
/// to Blocked Addresses (`#banned`), with the address in the flash only when a ban was lifted; a
/// DB error is a 500.
pub async fn unban_ip(
    State(state): State<AppState>,
    lang: Lang,
    Form(form): Form<UnbanForm>,
) -> Response {
    let result = sqlx::query("DELETE FROM banned_ips WHERE ip_address = ?")
        .bind(&form.ip)
        .execute(&state.db)
        .await;
    let lifted = match result {
        Ok(done) => done.rows_affected() > 0,
        Err(err) => {
            tracing::error!(%err, "couldn't unban an address");
            return crate::i18n::error(lang, StatusCode::INTERNAL_SERVER_ERROR, "error.unban");
        }
    };
    if !lifted {
        return Redirect::to("/security#banned").into_response();
    }
    let ip: String = form_urlencoded::byte_serialize(form.ip.as_bytes()).collect();
    Redirect::to(&format!("/security?unbanned={ip}#banned")).into_response()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_event_reads_as_words() {
        let en = |kind| event_label(Lang::En, kind);
        assert_eq!(en("login_failed"), "Wrong password");
        assert_eq!(en("device_command_queued"), "Command sent to phone");
        assert_eq!(en("music_entry_moved"), "Music moved");
        assert_eq!(event_label(Lang::Nb, "login_failed"), "Feil passord");
        assert_eq!(event_label(Lang::Nb, "music_entry_moved"), "Musikk flyttet");
        // An id this server never wrote: its own words, in any language.
        assert_eq!(en("retired_event"), "Retired event");
        assert_eq!(event_label(Lang::Nb, "retired_event"), "Retired event");
        assert_eq!(en("probe"), "Probe");
        assert_eq!(en(""), "Event");
        assert_eq!(en("__"), "Event");
        assert_eq!(en("ÿes_no"), "Ÿes no");
        // An id shaped like another key is no label.
        assert_eq!(label_key(Lang::En, "unknown.music.title"), None);
    }

    /// Literals near a security-event writer that are not event ids: form fields, notice codes,
    /// root-helper actions and command names.
    const NOT_EVENTS: [&str; 40] = [
        // Design 59: the Child PIN group's notices and the reveal's form action.
        "keyguard_no_pin",
        "pin_not_kept",
        "pin_not_sent",
        "pin_sent",
        "reveal_kid_pin",
        // A failed Spotify login's codes (`spotify_login_failed`'s details, design 26c §2.3).
        "access_denied",
        "admin_id",
        "authorize_error",
        "clear_kid_pin",
        "clear_pin",
        // A push delivery's reason and This Device's notices (design 35 step 2).
        "event_gone",
        "gone_unsaved",
        "invalid_grant",
        "music_entries",
        "music_note",
        "new_kid_pin",
        "new_pin",
        // Add Parent's refused field (design 62).
        "new_username",
        // Install Now's answers next to its queue (design 37: `?install=<word>`).
        "install_update",
        "no_release",
        "not_set_up",
        "not_a_domain_allow",
        "not_a_domain_block",
        "os_check",
        "os_upgrade",
        "override_is_kid_pin",
        "override_needed_by_lock",
        "parent_pin_cleared",
        "parent_pin_invalid",
        "parent_pin_saved",
        "parent_pin_saved_easy",
        "push_many",
        "push_on",
        "saved_not_kept",
        "saved_safe_boot",
        "saved_safe_boot_not_kept",
        "show_text",
        "stop_ring",
        "tailscale_update",
        "unknown_phone",
    ];

    /// Every event id the server writes has a label (qa-31-s14-code M2): the `snake_case` literals
    /// around each writer in `src/` (outside tests) - `record_security_event`, `log_event`,
    /// `log_change`, the audit's `.record`, `request_action`, the raw `INSERT INTO
    /// security_events`, and the `let (…event…)` tuples that pick one - are ids, unless listed in
    /// [NOT_EVENTS]. A new event without a label fails here.
    #[test]
    fn every_written_event_has_a_label() {
        let writer = regex::Regex::new(
            r"record_security_event\(|record_phone_event(?:_logged)?\(|log_event\(|log_change\(|\.record\(|request_action\(|INSERT INTO security_events",
        )
        .unwrap();
        let picker = regex::Regex::new(r"let \(?[a-z_, ]*event").unwrap();
        let literal = regex::Regex::new(r#"["']([a-z][a-z0-9]*(?:_[a-z0-9]+)+)["']"#).unwrap();
        let mut files = Vec::new();
        let mut dirs = vec![std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("src")];
        while let Some(dir) = dirs.pop() {
            for entry in std::fs::read_dir(&dir).unwrap() {
                let path = entry.unwrap().path();
                if path.is_dir() {
                    if path.file_name().is_some_and(|n| n != "tests") {
                        dirs.push(path);
                    }
                } else if path.extension().is_some_and(|e| e == "rs") {
                    files.push(path);
                }
            }
        }
        let mut ids = std::collections::BTreeSet::new();
        for file in &files {
            let text = std::fs::read_to_string(file).unwrap();
            let text = text
                .find("#[cfg(test)]\nmod tests {")
                .map_or(text.as_str(), |cut| &text[..cut]);
            let lines: Vec<&str> = text.lines().collect();
            for (i, line) in lines.iter().enumerate() {
                let span = if writer.is_match(line) && !line.contains("fn ") {
                    i.saturating_sub(15)..(i + 12).min(lines.len())
                } else if picker.is_match(line) {
                    i..(i + 70).min(lines.len())
                } else {
                    continue;
                };
                for near in &lines[span] {
                    for found in literal.captures_iter(near) {
                        ids.insert(found[1].to_string());
                    }
                }
            }
        }
        for known in [
            "login_failed",
            "music_phone_settings",
            "backup_restored",
            "kid_pin_cleared",
        ] {
            assert!(ids.contains(known), "the scan misses {known}: {ids:?}");
        }
        // A phone's lines name their kind by `security::PhoneLine`, not by a literal at the call.
        ids.extend(
            crate::security::PhoneLine::ALL
                .iter()
                .map(|kind| kind.wire().to_string()),
        );
        // The label is in both catalogs (design 70 §4).
        let unlabelled: Vec<&String> = ids
            .iter()
            .filter(|id| !NOT_EVENTS.contains(&id.as_str()))
            .filter(|id| {
                Lang::ALL
                    .into_iter()
                    .any(|lang| !crate::i18n::has(lang, &format!("security.event.{id}")))
            })
            .collect();
        assert!(
            unlabelled.is_empty(),
            "events without a label: {unlabelled:?}"
        );
    }
}
