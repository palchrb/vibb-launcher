//! Settings › Phone Setup (`/settings/provisioning`): the values every phone's setup QR code carries
//! (one form), and the default country code every phone's numbers use (a second form, design 31
//! step 8: `/settings/calls`, which moved here from each phone's Calls & Contacts page).
//! The Settings tab itself (`/settings`) is `handlers::settings_hub`. Also [Flash], the result line
//! the Settings pages show inside the group whose form saved (design 31 §3.0).

use askama::Template;
use axum::Form;
use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Redirect, Response};
use std::collections::HashMap;

use crate::AppState;
use crate::i18n::Lang;
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
    /// A refused save carried a new Tailscale key: it isn't sent back (it is a secret), so the
    /// page says it wasn't saved and must be pasted again (qa-31-s14-code L3).
    key_dropped: bool,
    /// "Remove the stored key" was ticked in the refused save: kept ticked.
    clear_key: bool,
}

#[derive(Template)]
#[template(path = "provisioning_settings.html")]
struct ProvisioningSettingsTemplate {
    lang: Lang,
    title: String,
    entered: Entered,
    /// `.env`'s `PHONE_SERVER_URL`: the QR's address while none is saved here, and the field's
    /// hint says where the address comes from (Q25).
    env_server_url: Option<String>,
    /// The Tailscale key is write-only (design 22 §3.2.6): only "Set (tskey-auth-...abcd)".
    tailscale_key_hint: Option<String>,
    /// `?saved=1`: the flash under the Save button (`#saved`).
    saved: bool,
    /// The address the QR uses is plain http to a private address, or the saved one is refused by
    /// the rule now (saved before 0.55.0, QA L-e): said under the field (security review L8).
    url_warning: Option<String>,
    /// A refused save: the field ("server_url", "locale" or "time_zone") and why.
    error: Option<(&'static str, &'static str)>,
    country_code: CountryCode,
}
crate::i18n::words!(ProvisioningSettingsTemplate);

/// The second form: the default country code, stored or as a refused save typed it.
struct CountryCode {
    value: String,
    /// `?saved=country-code`: the flash under its Save.
    saved: bool,
    /// A refused save's reason, under the field.
    error: Option<&'static str>,
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

/// The stored default country code; `None` (logged) on a DB error.
async fn load_country_code(state: &AppState) -> Option<String> {
    sqlx::query_scalar("SELECT default_country_code FROM call_settings WHERE id = 1")
        .fetch_one(&state.db)
        .await
        .map_err(|err| tracing::error!(%err, "couldn't read the default country code"))
        .ok()
}

fn read_failed() -> Response {
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        "Couldn't read Phone Setup - check the server log.",
    )
        .into_response()
}

#[allow(clippy::too_many_arguments)]
fn render(
    state: &AppState,
    status: StatusCode,
    settings: &ProvisioningSettings,
    entered: Entered,
    saved: bool,
    error: Option<(&'static str, &'static str)>,
    country_code: CountryCode,
    lang: Lang,
) -> Response {
    let url_refused = error.is_some_and(|(field, _)| field == "server_url");
    let shown = if entered.server_url.is_empty() {
        state.config.phone_server_url.as_deref().unwrap_or("")
    } else {
        entered.server_url.as_str()
    };
    let url_warning = if url_refused || shown.is_empty() {
        None
    } else if crate::handlers::provisioning::saved_refused(shown) {
        Some(
            "This saved address isn't put in the setup QR: plain http goes only to a private \
             address. Save an https:// one."
                .to_string(),
        )
    } else {
        crate::handlers::provisioning::plain_http_warning(shown)
    };
    let page = ProvisioningSettingsTemplate {
        lang,
        title: "Phone Setup".to_string(),
        url_warning,
        entered,
        env_server_url: state.config.phone_server_url.clone(),
        tailscale_key_hint: key_hint(&settings.tailscale_auth_key),
        saved,
        error,
        country_code,
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
    lang: Lang,
    Query(query): Query<HashMap<String, String>>,
) -> Response {
    let (Some(settings), Some(code)) = (load(&state).await, load_country_code(&state).await) else {
        return read_failed();
    };
    let saved = query.get("saved").map(String::as_str);
    let country_code = CountryCode {
        value: code,
        saved: saved == Some("country-code"),
        error: None,
    };
    render(
        &state,
        StatusCode::OK,
        &settings,
        stored(&settings),
        saved == Some("1"),
        None,
        country_code,
        lang,
    )
}

/// The form as stored.
fn stored(settings: &ProvisioningSettings) -> Entered {
    Entered {
        server_url: settings.server_url.clone(),
        locale: settings.locale.clone(),
        time_zone: settings.time_zone.clone(),
        key_dropped: false,
        clear_key: false,
    }
}

/// Saves the one form. A malformed language or time zone writes nothing and renders the page
/// again (400) with what was typed - never the key - and the reason under that field; the form
/// posts to `#setup`, so the answer lands on the form. Saved: back to the flash under Save
/// (`?saved=1#saved`), where the parent was, with or without JS.
pub async fn update_provisioning_settings(
    State(state): State<AppState>,
    lang: Lang,
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
    // Empty = .env's PHONE_SERVER_URL, else the one rule for the QR's address (security review L8):
    // a refused one isn't saved, a plain-http one to a private address is, with its warning.
    let url_refusal = match crate::config::check_server_url(server_url) {
        crate::config::ServerUrl::Refused(why) if !server_url.is_empty() => Some(why),
        _ => None,
    };
    let error = if let Some(why) = url_refusal {
        Some(("server_url", why))
    } else if !locale.is_empty() && !crate::handlers::provisioning::valid_locale(locale) {
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
        let (Some(settings), Some(code)) = (load(&state).await, load_country_code(&state).await)
        else {
            return read_failed();
        };
        let entered = Entered {
            server_url: server_url.to_string(),
            locale: locale.to_string(),
            time_zone: time_zone.to_string(),
            key_dropped: !new_key.is_empty(),
            clear_key,
        };
        let country_code = CountryCode {
            value: code,
            saved: false,
            error: None,
        };
        return render(
            &state,
            StatusCode::BAD_REQUEST,
            &settings,
            entered,
            false,
            error,
            country_code,
            lang,
        );
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

/// Saves the default country code (`POST /settings/calls`, Phone Setup's second form). Every
/// phone's `call_policy` carries it (the launcher reads incoming numbers with it), so every phone
/// is nudged; numbers already stored keep the code they were saved with. Saved: back to its flash
/// (`?saved=country-code#country-code`). Anything but 1-3 digits (a leading + is dropped) writes
/// nothing and renders the page (400) with what was typed and the reason under the field; the form
/// posts to `#country-code`, so the answer lands on it. One form posts here now, so it always
/// returns there (an older Calls page's `device_id` is ignored).
pub async fn save_country_code(
    State(state): State<AppState>,
    lang: Lang,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let typed = form
        .get("default_country_code")
        .map(|s| s.trim())
        .unwrap_or("");
    let code = typed.strip_prefix('+').unwrap_or(typed);
    if !crate::phone::valid_country_code(code) {
        let Some(settings) = load(&state).await else {
            return read_failed();
        };
        let country_code = CountryCode {
            value: typed.chars().take(16).collect(),
            saved: false,
            error: Some("Use 1 to 3 digits, like 47. Nothing was saved."),
        };
        return render(
            &state,
            StatusCode::BAD_REQUEST,
            &settings,
            stored(&settings),
            false,
            None,
            country_code,
            lang,
        );
    }
    let result = async {
        let mut tx = state.db.begin().await?;
        sqlx::query("UPDATE call_settings SET default_country_code = ? WHERE id = 1")
            .bind(code)
            .execute(&mut *tx)
            .await?;
        let devices: Vec<i64> = sqlx::query_scalar("SELECT id FROM devices")
            .fetch_all(&mut *tx)
            .await?;
        tx.commit().await?;
        Ok::<_, sqlx::Error>(devices)
    }
    .await;
    match result {
        Ok(devices) => {
            for id in devices {
                // No receivers just means no phone is connected; it syncs on its next poll.
                let _ = state.command_notify.send(id);
            }
            Redirect::to("/settings/provisioning?saved=country-code#country-code").into_response()
        }
        Err(err) => {
            tracing::error!(%err, "couldn't save the default country code");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't save - nothing was changed. Check the server log.",
            )
                .into_response()
        }
    }
}
