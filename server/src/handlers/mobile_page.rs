//! Device › Mobilnett (design 95 revision 3; the user, 2026-11-13: a settings page of the phone's own, on a par with
//! Lock Screen & PINs - not a group on Advanced). Three groups, each an auto-saving form that answers on its own anchor
//! of this page, so a save never jumps to the top (with JS `scroll-restore.js` keeps the exact place; without, a
//! `<noscript>` Save does the same):
//! - `#mobile-data`: "Mobildata" - På / Bare Vibb (`apps_mobile`);
//! - `#data-roaming`: "Dataroaming" - Av (locked) / Telefonens valg (`data_roaming`, design 94);
//! - `#mobile-lock`: "Lås mobilnett-innstillingene" (`mobile_settings_locked`).
//!
//! One route, `POST /devices/{id}/mobile`, with the group in `group`: an unknown value is a 400 with nothing written,
//! an unknown phone a 404. Every save nudges the phone; `settings_log` writes the security log's line. The words come
//! from [crate::mobile_network].

use std::collections::HashMap;

use askama::Template;
use axum::Form;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Redirect, Response};

use crate::AppState;
use crate::handlers::device_pages::{load_device, load_latest, load_policy};
use crate::i18n::{Lang, Words};
use crate::mobile_network::{self, Card};
use crate::models::Device;

#[derive(Template)]
#[template(path = "device_mobile.html")]
struct MobileTemplate {
    lang: Lang,
    who: crate::handlers::devices::BandAvatar,
    title: String,
    device: Device,
    card: Card,
}
crate::i18n::words!(MobileTemplate);

/// GET `/devices/{id}/mobile`. A phone not set up yet has the page too: what is saved reaches it with its first
/// settings.
pub async fn view(State(state): State<AppState>, lang: Lang, Path(id): Path<i64>) -> Response {
    let device = match load_device(&state, id).await {
        Ok(device) => device,
        Err((status, key)) => return crate::i18n::error(lang, status, key),
    };
    let policy = match load_policy(&state, id).await {
        Ok(policy) => policy,
        Err((status, key)) => return crate::i18n::error(lang, status, key),
    };
    let latest = match load_latest(&state, id).await {
        Ok(latest) => latest,
        Err(err) => {
            tracing::error!(device_id = id, %err, "can't read the last report");
            return crate::i18n::error(lang, StatusCode::INTERNAL_SERVER_ERROR, "error.read_phone");
        }
    };
    Html(
        MobileTemplate {
            lang,
            who: crate::handlers::devices::BandAvatar::of(&device),
            title: crate::i18n::t(lang, "device.page.mobile").to_string(),
            card: mobile_network::card(lang, &policy, latest.as_ref()),
            device,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

/// The column, its new value and the anchor of a save, or `None` for a form this page doesn't send.
fn change(form: &HashMap<String, String>) -> Option<(&'static str, ChangeValue, &'static str)> {
    let pick = |name: &str, choices: &[&'static str]| -> Option<&'static str> {
        let value = form.get(name)?;
        choices.iter().copied().find(|c| *c == value)
    };
    match form.get("group").map(String::as_str)? {
        "mobile-data" => Some((
            "apps_mobile",
            ChangeValue::Text(pick("apps_mobile", mobile_network::APPS_CHOICES)?),
            "mobile-data",
        )),
        "data-roaming" => Some((
            "data_roaming",
            ChangeValue::Text(pick("data_roaming", mobile_network::ROAMING_CHOICES)?),
            "data-roaming",
        )),
        // A switch: missing = off.
        "mobile-lock" => Some((
            "mobile_settings_locked",
            ChangeValue::Flag(form.contains_key("on")),
            "mobile-lock",
        )),
        _ => None,
    }
}

enum ChangeValue {
    Text(&'static str),
    Flag(bool),
}

/// POST `/devices/{id}/mobile`: one group's choice. Back to the group's anchor, the phone nudged.
pub async fn save(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let Some((column, value, anchor)) = change(&form) else {
        return crate::i18n::error(lang, StatusCode::BAD_REQUEST, "error.out_of_date");
    };
    // The column comes from the fixed list above, never from the form.
    let sql = format!(
        "UPDATE device_policy SET {column} = ?, updated_at = datetime('now') WHERE device_id = ?"
    );
    let query = sqlx::query(&sql);
    let query = match value {
        ChangeValue::Text(text) => query.bind(text),
        ChangeValue::Flag(flag) => query.bind(flag),
    };
    match query.bind(id).execute(&state.db).await {
        Ok(done) if done.rows_affected() == 0 => {
            crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.device_not_found")
        }
        Ok(_) => {
            let _ = state.command_notify.send(id);
            Redirect::to(&format!("/devices/{id}/mobile#{anchor}")).into_response()
        }
        Err(err) => {
            tracing::error!(device_id = id, %err, "couldn't save the mobile network settings");
            crate::i18n::error(
                lang,
                StatusCode::INTERNAL_SERVER_ERROR,
                "error.save_failed_log",
            )
        }
    }
}
