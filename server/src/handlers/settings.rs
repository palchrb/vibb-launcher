//! The Settings tab - a static card list linking out to the existing
//! Backups/Software updates/Security log/Account pages, mirroring the
//! admin-console pattern from the user's Board Game Tracker app. No DB
//! queries of its own, just navigation.

use askama::Template;
use axum::Form;
use axum::extract::State;
use axum::response::{Html, IntoResponse, Redirect};
use std::collections::HashMap;

use crate::AppState;
use crate::models::ProvisioningSettings;

#[derive(Template)]
#[template(path = "settings_hub.html")]
struct SettingsHubTemplate {
    title: String,
}

pub async fn settings_hub() -> impl IntoResponse {
    Html(
        SettingsHubTemplate {
            title: "Settings".to_string(),
        }
        .render()
        .unwrap(),
    )
}

#[derive(Template)]
#[template(path = "provisioning_settings.html")]
struct ProvisioningSettingsTemplate {
    title: String,
    settings: ProvisioningSettings,
    /// The Tailscale key is write-only (design 22 §3.2.6): only "set (tskey-auth-...abcd)".
    tailscale_key_hint: Option<String>,
}

/// "tskey-auth-...abcd": enough to tell keys apart, never the key.
pub fn key_hint(key: &str) -> Option<String> {
    let key = key.trim();
    if key.is_empty() {
        return None;
    }
    let tail: String = key
        .chars()
        .rev()
        .take(4)
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .collect();
    let prefix = if key.starts_with("tskey-auth-") {
        "tskey-auth-"
    } else {
        ""
    };
    Some(if key.chars().count() <= 8 {
        "set".to_string()
    } else {
        format!("{prefix}...{tail}")
    })
}

/// Single server_url/tailscale_auth_key pair, embedded verbatim into every device's QR
/// provisioning payload (`handlers::provisioning`) and read by the launcher's own in-app QR
/// scanner for devices where Android's native zero-touch flow doesn't run (e.g. GrapheneOS) -
/// see that handler's own doc comment for why these are global rather than per-device.
pub async fn provisioning_settings_form(State(state): State<AppState>) -> impl IntoResponse {
    let settings = sqlx::query_as::<_, ProvisioningSettings>(
        "SELECT * FROM provisioning_settings WHERE id = 1",
    )
    .fetch_optional(&state.db)
    .await
    .ok()
    .flatten()
    .unwrap_or_default();

    Html(
        ProvisioningSettingsTemplate {
            title: "Provisioning".to_string(),
            tailscale_key_hint: key_hint(&settings.tailscale_auth_key),
            settings,
        }
        .render()
        .unwrap(),
    )
}

pub async fn update_provisioning_settings(
    State(state): State<AppState>,
    Form(form): Form<HashMap<String, String>>,
) -> impl IntoResponse {
    let server_url = form.get("server_url").map(|s| s.trim()).unwrap_or("");
    // Write-only: an empty field keeps the stored key, "clear" removes it.
    let new_key = form
        .get("tailscale_auth_key")
        .map(|s| s.trim())
        .unwrap_or("");
    let clear_key = form.contains_key("clear_tailscale_auth_key");
    // Empty = not in the QR. Anything else must have the form Android expects, or nothing is
    // written (fix round 2026-10-06).
    let locale = form.get("locale").map(|s| s.trim()).unwrap_or("");
    let time_zone = form.get("time_zone").map(|s| s.trim()).unwrap_or("");
    if (!locale.is_empty() && !crate::handlers::provisioning::valid_locale(locale))
        || (!time_zone.is_empty() && !crate::handlers::provisioning::valid_time_zone(time_zone))
    {
        return (
            axum::http::StatusCode::BAD_REQUEST,
            "Language must look like nb_NO and the time zone like Europe/Oslo - nothing was saved.",
        )
            .into_response();
    }

    sqlx::query(
        "UPDATE provisioning_settings SET server_url = ?, \
         tailscale_auth_key = CASE WHEN ? THEN '' WHEN ? <> '' THEN ? ELSE tailscale_auth_key END, \
         locale = ?, time_zone = ?, updated_at = datetime('now') WHERE id = 1",
    )
    .bind(server_url)
    .bind(clear_key)
    .bind(new_key)
    .bind(new_key)
    .bind(locale)
    .bind(time_zone)
    .execute(&state.db)
    .await
    .ok();

    Redirect::to("/settings/provisioning").into_response()
}
