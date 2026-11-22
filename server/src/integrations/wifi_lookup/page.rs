//! The Location page's "Wi-Fi lookup at Apple" group (design 125 A1, A7; `partials/wifi_lookup_group.html`,
//! `#wifi-lookup`): the phone's switch as an auto-saving form back to the group (never the top), one ⓘ for what it
//! does - with the line that the lookups go from the server's own address while no proxy is set -, and the orange
//! "Apple lookups aren't working" while `integration_health` raises the integration's row and the switch is on.

use std::collections::HashMap;

use askama::Template;
use axum::Form;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Redirect, Response};

use super::{NAME, Proxy};
use crate::AppState;
use crate::i18n::{Lang, Words};

#[derive(Template)]
#[template(path = "partials/wifi_lookup_group.html")]
struct GroupTemplate {
    lang: Lang,
    device_id: i64,
    from_device: bool,
    on: bool,
    /// A7: Apple calls have failed past their grace, or the pause reached its day.
    broken: bool,
    /// A1: no `WIFI_LOOKUP_PROXY`.
    direct: bool,
}
crate::i18n::words!(GroupTemplate);

/// The Location page's URL back to the group.
pub fn location_url(device_id: i64, from_device: bool) -> String {
    let from = if from_device { "?from=device" } else { "" };
    format!("/devices/{device_id}/location-settings{from}")
}

/// The group for this phone, rendered.
pub async fn group(
    state: &AppState,
    lang: Lang,
    device_id: i64,
    from_device: bool,
) -> Result<Option<String>, sqlx::Error> {
    let on = super::is_on(&state.db, device_id).await?;
    let broken = on
        && crate::integration_health::problem(&state.db, NAME, "", chrono::Utc::now())
            .await?
            .is_some();
    let html = GroupTemplate {
        lang,
        device_id,
        from_device,
        on,
        broken,
        direct: state.integrations.wifi_lookup.proxy == Proxy::Direct,
    }
    .render()
    .unwrap_or_else(|err| {
        tracing::error!(%err, "wifi_lookup: the group didn't render");
        String::new()
    });
    Ok(Some(html))
}

/// `POST /devices/{id}/wifi-lookup`: the switch (`on` present = on). Nudges the phone; back to `#wifi-lookup`.
pub async fn save(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    match super::set_on(&state, id, form.contains_key("on")).await {
        Ok(true) => {
            let from_device = form.get("from").map(String::as_str) == Some("device");
            Redirect::to(&format!("{}#wifi-lookup", location_url(id, from_device))).into_response()
        }
        Ok(false) => crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.device_not_found"),
        Err(err) => {
            tracing::error!(%err, "wifi_lookup: couldn't save the switch");
            crate::i18n::error(lang, StatusCode::INTERNAL_SERVER_ERROR, "error.save_failed")
        }
    }
}
