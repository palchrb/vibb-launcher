//! Settings saves in the security log (Q29; decision 10 of `docs/plan/2026-10-25-queue-rounds.md`):
//! [log_saves], one middleware on the signed-in admin routes, writes `settings_saved` for a POST
//! answered with a 303 back to a page of this app - the handlers' success path. A refusal (a 4xx,
//! a form rendered again with its error) is never a save, and neither are the actions in
//! [NOT_SAVES]. One row per parent and page per [MERGE_MINUTES]: a later save on that page adds
//! its route to the row (`×N` when the same route and ids come again), so a session of toggles is
//! one line. The detail is `<page> · <route> (<param> <value>, ...); ...`, from the matched route
//! and its path's ids only - never a form's values.
//!
//! Two answers that look like a save aren't one (code QA M1, L2): a refusal that goes back to its
//! page with a 303 and its reason in the query (`?notice=parent_pin_invalid`) carries [Refused] in
//! its extensions ([refused]), and a save whose handler wrote its own security event (any kind but
//! this one, by the same parent, during the request) already says what changed.

use axum::extract::{MatchedPath, Request, State};
use axum::http::{Method, StatusCode, header};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};

use crate::AppState;
use crate::security::{CurrentAdmin, record_security_event};

/// A parent's saves on one page within this many minutes of its row's first are one row.
pub const MERGE_MINUTES: i64 = 10;
/// Past this a row takes no more routes; the next save starts a new one.
const MAX_DETAIL: usize = 1000;
/// POSTs that answer 303 but change no setting: commands and pauses (logged as their own events),
/// checks, read marks, a test push, a backup made or restored and the root helper's actions
/// (their own events too). `every_skipped_route_is_a_route` keeps the list to real routes.
const NOT_SAVES: &[&str] = &[
    "/devices/{id}/command/locate",
    "/devices/{id}/command/ring",
    "/devices/{id}/command/stop-ring",
    "/devices/{id}/command/lock",
    "/devices/{id}/command/wipe",
    "/devices/{id}/commands/{command_id}/cancel",
    "/devices/{id}/end-windows",
    "/devices/{id}/allow-blocked",
    "/devices/{id}/start-pause",
    "/devices/{id}/kid-pin/send",
    "/devices/{id}/lifts",
    "/devices/{id}/lifts/{lift_id}/end",
    "/apps/tracked/{id}/check",
    "/apps/tracked/{id}/install-now/{device_id}",
    "/apps/tracked/{id}/install-now/{device_id}/cancel/{command_id}",
    "/music/entries/{id}/check",
    // A calendar link's "Fetch now" (design 72 §5): a check, not a setting.
    "/devices/{id}/calendar/links/{uid}/fetch",
    "/notifications/{id}/open",
    "/notifications/read-all",
    // Hides a session banner, like a read mark (code QA L1).
    "/account/dismiss-warning",
    "/notifications/subscriptions/{id}/test",
    "/backups/create",
    "/backups/upload",
    "/backups/{filename}/restore",
    // The Pi's root helper's actions (their own `*_triggered` events).
    "/update/trigger",
    "/update/restart",
    "/system/os/check",
    "/system/os/upgrade",
    "/system/tailscale/update",
    "/system/reboot",
    "/backups/format-drive",
];

/// One write at a time, so two saves at once can't both start a row or lose a count.
static WRITES: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

/// The mark of a refusal answered with a 303 back to its page: not a save (code QA M1).
#[derive(Clone, Copy, Debug)]
pub struct Refused;

/// `response` marked [Refused].
pub fn refused(response: impl IntoResponse) -> Response {
    let mut response = response.into_response();
    response.extensions_mut().insert(Refused);
    response
}

/// The middleware (inside `require_full_auth`, which puts [CurrentAdmin] in): see the module doc.
/// A failed write is logged and the answer goes out unchanged.
pub async fn log_saves(State(state): State<AppState>, request: Request, next: Next) -> Response {
    let saved = (request.method() == Method::POST)
        .then(|| {
            let route = request
                .extensions()
                .get::<MatchedPath>()?
                .as_str()
                .to_string();
            let actor = request
                .extensions()
                .get::<CurrentAdmin>()?
                .0
                .username
                .clone();
            (!NOT_SAVES.contains(&route.as_str()))
                .then(|| (actor, entry(&route, request.uri().path())))
        })
        .flatten();
    // The newest event before the handler runs: one it writes itself is above it.
    let before = match saved {
        Some(_) => newest_event(&state.db).await,
        None => None,
    };
    let response = next.run(request).await;
    let (Some((actor, entry)), Some(before)) = (saved, before) else {
        return response;
    };
    let Some(page) = page_of(&response) else {
        return response;
    };
    if response.extensions().get::<Refused>().is_some() {
        return response;
    }
    let _guard = WRITES.lock().await;
    let result = async {
        if !logged_since(&state.db, &actor, before).await? {
            record(&state.db, &actor, &page, &entry).await?;
        }
        Ok::<_, sqlx::Error>(())
    }
    .await;
    if let Err(err) = result {
        tracing::error!(%err, "couldn't log a settings save");
    }
    response
}

/// The id of the newest security event (0 for none); `None` (logged) when it can't be read.
async fn newest_event(db: &sqlx::SqlitePool) -> Option<i64> {
    sqlx::query_scalar("SELECT COALESCE(MAX(id), 0) FROM security_events")
        .fetch_one(db)
        .await
        .map_err(|err| tracing::error!(%err, "couldn't read the security log"))
        .ok()
}

/// Whether `actor`'s request wrote an event of its own after the event `before` (code QA L2).
async fn logged_since(
    db: &sqlx::SqlitePool,
    actor: &str,
    before: i64,
) -> Result<bool, sqlx::Error> {
    sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM security_events WHERE id > ? AND username = ? \
         AND event_type <> 'settings_saved')",
    )
    .bind(before)
    .bind(actor)
    .fetch_one(db)
    .await
}

/// The page a 303 sends the browser back to (its path), or `None` for any other answer, a
/// redirect off this app or to the sign-in.
fn page_of(response: &Response) -> Option<String> {
    if response.status() != StatusCode::SEE_OTHER {
        return None;
    }
    let location = response.headers().get(header::LOCATION)?.to_str().ok()?;
    if !location.starts_with('/') || location.starts_with("//") {
        return None;
    }
    let path = location.split(['?', '#']).next().unwrap_or("");
    (path != "/login").then(|| clean(path, 120))
}

/// `route` with its ids from `path`: "/devices/{id}/zones/{zone_id} (id 3, zone_id 5)".
fn entry(route: &str, path: &str) -> String {
    let ids: Vec<String> = route
        .split('/')
        .zip(path.split('/'))
        .filter_map(|(name, value)| {
            let name = name.strip_prefix('{')?.strip_suffix('}')?;
            Some(format!("{name} {}", clean(value, 40)))
        })
        .collect();
    if ids.is_empty() {
        route.to_string()
    } else {
        format!("{route} ({})", ids.join(", "))
    }
}

/// At most `max` characters of `value`, anything but letters, digits and `/._-` as `?`.
fn clean(value: &str, max: usize) -> String {
    value
        .chars()
        .take(max)
        .map(|c| {
            if c.is_ascii_alphanumeric() || matches!(c, '/' | '.' | '_' | '-') {
                c
            } else {
                '?'
            }
        })
        .collect()
}

/// `entries` ("a; b ×2") with `entry` added: its count up by one when it is there, else at the end.
fn merged(entries: &str, entry: &str) -> String {
    let mut found = false;
    let parts: Vec<String> = entries
        .split("; ")
        .map(|part| {
            let (base, count) = part
                .rsplit_once(" ×")
                .and_then(|(base, n)| Some((base, n.parse::<u32>().ok()?)))
                .unwrap_or((part, 1));
            if !found && base == entry {
                found = true;
                format!("{base} ×{}", count + 1)
            } else {
                part.to_string()
            }
        })
        .collect();
    let mut out = parts.join("; ");
    if !found {
        out = format!("{out}; {entry}");
    }
    out
}

/// Adds `entry` to `actor`'s row for `page` from the last [MERGE_MINUTES], or starts one.
async fn record(
    db: &sqlx::SqlitePool,
    actor: &str,
    page: &str,
    entry: &str,
) -> Result<(), sqlx::Error> {
    let prefix = format!("{page} · ");
    let open: Option<(i64, String)> = sqlx::query_as(
        "SELECT id, detail FROM security_events WHERE event_type = 'settings_saved' \
         AND username = ? AND created_at > datetime('now', ?) AND substr(detail, 1, ?) = ? \
         ORDER BY id DESC LIMIT 1",
    )
    .bind(actor)
    .bind(format!("-{MERGE_MINUTES} minutes"))
    .bind(prefix.chars().count() as i64)
    .bind(&prefix)
    .fetch_optional(db)
    .await?;
    if let Some((id, detail)) = open {
        let detail = format!("{prefix}{}", merged(&detail[prefix.len()..], entry));
        if detail.len() <= MAX_DETAIL {
            sqlx::query("UPDATE security_events SET detail = ? WHERE id = ?")
                .bind(detail)
                .bind(id)
                .execute(db)
                .await?;
            return Ok(());
        }
    }
    record_security_event(
        db,
        "settings_saved",
        Some(actor),
        None,
        Some(&format!("{prefix}{entry}")),
    )
    .await;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A renamed route must not slip out of the list unnoticed.
    #[test]
    fn every_skipped_route_is_a_route() {
        let main =
            std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/src/main.rs")).unwrap();
        for route in NOT_SAVES {
            assert!(main.contains(&format!("\"{route}\"")), "{route}");
        }
    }

    #[test]
    fn an_entry_is_its_route_and_ids() {
        assert_eq!(
            entry("/devices/{id}/zones/{zone_id}", "/devices/3/zones/5"),
            "/devices/{id}/zones/{zone_id} (id 3, zone_id 5)"
        );
        assert_eq!(entry("/music/sweep", "/music/sweep"), "/music/sweep");
        assert_eq!(
            entry("/backups/{filename}/delete", "/backups/a b<x>/delete"),
            "/backups/{filename}/delete (filename a?b?x?)"
        );
    }

    #[test]
    fn a_repeated_entry_counts_and_a_new_one_is_added() {
        let one = "/devices/{id}/hardening (id 3)";
        assert_eq!(merged(one, one), format!("{one} ×2"));
        assert_eq!(merged(&format!("{one} ×2"), one), format!("{one} ×3"));
        assert_eq!(
            merged(one, "/devices/{id}/kid-lock (id 3)"),
            format!("{one}; /devices/{{id}}/kid-lock (id 3)")
        );
    }
}
