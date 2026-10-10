//! The admin-facing security log: recent auth events (failed logins,
//! lockouts, IP bans, successful sign-ins) and currently-banned IPs, plus a
//! way to unban one. Doubles as the audit trail for every `security_events`
//! row written elsewhere in the app (see `security::record_security_event`).

use askama::Template;
use axum::Form;
use axum::extract::State;
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
}

async fn render_security_log(state: &AppState, success: Option<String>) -> Html<String> {
    let events = sqlx::query_as::<_, SecurityEvent>(
        "SELECT * FROM security_events ORDER BY created_at DESC LIMIT 200",
    )
    .fetch_all(&state.db)
    .await
    .unwrap_or_default();

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
        }
        .render()
        .unwrap(),
    )
}

pub async fn security_log(State(state): State<AppState>) -> impl IntoResponse {
    render_security_log(&state, None).await
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

    render_security_log(&state, Some(format!("{} was unbanned.", form.ip))).await
}
