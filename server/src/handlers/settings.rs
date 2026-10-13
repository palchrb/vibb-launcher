//! Settings › Phone Setup (`/settings/provisioning`): the values every phone's setup QR code carries.
//! The Settings tab itself (`/settings`) is `handlers::settings_hub`. Also [Flash], the result line
//! the Settings pages show inside the group whose form saved (design 31 §3.0).

use askama::Template;
use axum::Form;
use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Redirect, Response};
use std::collections::HashMap;

use crate::AppState;
use crate::models::ProvisioningSettings;

/// A form's result, shown inside the group `at` (its id) by `partials/group_flash.html`: green
/// when done, else red, with the reason behind ⓘ when there is one. Never at the page's top.
pub struct Flash {
    pub at: &'static str,
    pub ok: bool,
    pub title: String,
    pub detail: Option<String>,
}

impl Flash {
    pub fn done(at: &'static str, title: impl Into<String>) -> Flash {
        Flash {
            at,
            ok: true,
            title: title.into(),
            detail: None,
        }
    }

    pub fn refused(at: &'static str, title: impl Into<String>) -> Flash {
        Flash {
            ok: false,
            ..Flash::done(at, title)
        }
    }

    pub fn because(self, detail: impl Into<String>) -> Flash {
        Flash {
            detail: Some(detail.into()),
            ..self
        }
    }
}

/// What the form shows: the stored values, or what a refused save sent (never the key).
struct Entered {
    server_url: String,
    locale: String,
    time_zone: String,
}

#[derive(Template)]
#[template(path = "provisioning_settings.html")]
struct ProvisioningSettingsTemplate {
    title: String,
    entered: Entered,
    /// The Tailscale key is write-only (design 22 §3.2.6): only "Set (tskey-auth-...abcd)".
    tailscale_key_hint: Option<String>,
    /// `?saved=1`: the flash under the Save button (`#saved`).
    saved: bool,
    /// A refused save: the field ("locale" or "time_zone") and why.
    error: Option<(&'static str, &'static str)>,
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

/// The stored row; `None` (logged) on a DB error, which the pages answer with [read_failed].
async fn load(state: &AppState) -> Option<ProvisioningSettings> {
    sqlx::query_as::<_, ProvisioningSettings>("SELECT * FROM provisioning_settings WHERE id = 1")
        .fetch_optional(&state.db)
        .await
        .map(Option::unwrap_or_default)
        .map_err(|err| tracing::error!(%err, "couldn't read Phone Setup"))
        .ok()
}

fn read_failed() -> Response {
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        "Couldn't read Phone Setup - check the server log.",
    )
        .into_response()
}

fn render(
    status: StatusCode,
    settings: &ProvisioningSettings,
    entered: Entered,
    saved: bool,
    error: Option<(&'static str, &'static str)>,
) -> Response {
    let page = ProvisioningSettingsTemplate {
        title: "Phone Setup".to_string(),
        entered,
        tailscale_key_hint: key_hint(&settings.tailscale_auth_key),
        saved,
        error,
    };
    (status, Html(page.render().unwrap())).into_response()
}

/// Single server_url/tailscale_auth_key pair, embedded verbatim into every device's QR
/// provisioning payload (`handlers::provisioning`) and read by the launcher's own in-app QR
/// scanner for devices where Android's native zero-touch flow doesn't run (e.g. GrapheneOS) -
/// see that handler's own doc comment for why these are global rather than per-device. A DB
/// error is a 500, never an empty form (its Save would clear every value).
pub async fn provisioning_settings_form(
    State(state): State<AppState>,
    Query(query): Query<HashMap<String, String>>,
) -> Response {
    let Some(settings) = load(&state).await else {
        return read_failed();
    };
    let entered = Entered {
        server_url: settings.server_url.clone(),
        locale: settings.locale.clone(),
        time_zone: settings.time_zone.clone(),
    };
    let saved = query.get("saved").is_some_and(|v| v == "1");
    render(StatusCode::OK, &settings, entered, saved, None)
}

/// Saves the one form. A malformed language or time zone writes nothing and renders the page
/// again (400) with what was typed - never the key - and the reason under that field; the form
/// posts to `#setup`, so the answer lands on the form. Saved: back to the flash under Save
/// (`?saved=1#saved`), where the parent was, with or without JS.
pub async fn update_provisioning_settings(
    State(state): State<AppState>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
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
    let error = if !locale.is_empty() && !crate::handlers::provisioning::valid_locale(locale) {
        Some(("locale", "Use the form nb_NO. Nothing was saved."))
    } else if !time_zone.is_empty() && !crate::handlers::provisioning::valid_time_zone(time_zone) {
        Some((
            "time_zone",
            "Use a name like Europe/Oslo. Nothing was saved.",
        ))
    } else {
        None
    };
    if error.is_some() {
        let Some(settings) = load(&state).await else {
            return read_failed();
        };
        let entered = Entered {
            server_url: server_url.to_string(),
            locale: locale.to_string(),
            time_zone: time_zone.to_string(),
        };
        return render(StatusCode::BAD_REQUEST, &settings, entered, false, error);
    }

    let result = sqlx::query(
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
    .await;
    if let Err(err) = result {
        tracing::error!(%err, "couldn't save Phone Setup");
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            "Couldn't save - nothing was changed. Check the server log.",
        )
            .into_response();
    }

    Redirect::to("/settings/provisioning?saved=1#saved").into_response()
}
