//! The admin-facing security log: recent auth events (failed logins,
//! lockouts, IP bans, successful sign-ins) and currently-banned IPs, plus a
//! way to unban one. Doubles as the audit trail for every `security_events`
//! row written elsewhere in the app (see `security::record_security_event`).

use askama::Template;
use axum::Form;
use axum::extract::{Query, State};
use axum::response::{Html, IntoResponse};

use crate::AppState;
use crate::models::{BannedIp, SecurityEvent};

#[derive(Template)]
#[template(path = "security.html")]
struct SecurityTemplate {
    title: String,
    events: Vec<SecurityEvent>,
    banned_ips: Vec<BannedIp>,
    success: Option<String>,
    /// Showing older events (`?before=`): a link back to the newest.
    paged: bool,
    /// The id to page on from, when there may be older events.
    older_than: Option<i64>,
}

/// Events shown per page; older ones are a link away (security review M7: a flood can't push a
/// sign-in attempt out of reach).
const EVENTS_PER_PAGE: i64 = 200;

async fn render_security_log(
    state: &AppState,
    success: Option<String>,
    before: Option<i64>,
) -> Html<String> {
    let events = sqlx::query_as::<_, SecurityEvent>(
        "SELECT * FROM security_events WHERE (? IS NULL OR id < ?) ORDER BY id DESC LIMIT ?",
    )
    .bind(before)
    .bind(before)
    .bind(EVENTS_PER_PAGE)
    .fetch_all(&state.db)
    .await
    .unwrap_or_default();
    let older_than = (events.len() as i64 == EVENTS_PER_PAGE)
        .then(|| events.last().map(|event| event.id))
        .flatten();

    let banned_ips = sqlx::query_as::<_, BannedIp>(
        "SELECT * FROM banned_ips WHERE banned_until > datetime('now') ORDER BY banned_until DESC",
    )
    .fetch_all(&state.db)
    .await
    .unwrap_or_default();

    Html(
        SecurityTemplate {
            title: "Security log".to_string(),
            events,
            banned_ips,
            success,
            paged: before.is_some(),
            older_than,
        }
        .render()
        .unwrap(),
    )
}

#[derive(serde::Deserialize)]
pub struct SecurityLogQuery {
    before: Option<i64>,
}

pub async fn security_log(
    State(state): State<AppState>,
    Query(query): Query<SecurityLogQuery>,
) -> impl IntoResponse {
    render_security_log(&state, None, query.before).await
}

#[derive(serde::Deserialize)]
pub struct UnbanForm {
    ip: String,
}

/// A form field, not a path segment: a ban's key may be an IPv6 /64 ("2001:db8:1:2::/64").
pub async fn unban_ip(
    State(state): State<AppState>,
    Form(form): Form<UnbanForm>,
) -> impl IntoResponse {
    sqlx::query("DELETE FROM banned_ips WHERE ip_address = ?")
        .bind(&form.ip)
        .execute(&state.db)
        .await
        .ok();

    render_security_log(&state, Some(format!("{} was unbanned.", form.ip)), None).await
}
