//! Calls & SMS admin pages (design 02-calls.md 3.3): per-device switches, the device's contacts
//! (from the global `contacts` address book) and the global default country code. Every write
//! runs in a transaction, answers 500 on a database error (nothing half-written), nudges the
//! device over SSE and redirects back to `/devices/{id}/calls`. The launcher gets all of this as
//! `PolicyResponse.call_policy` (`device_api::build_policy`).

use std::collections::HashMap;

use askama::Template;
use axum::Form;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Redirect, Response};

use crate::AppState;
use crate::handlers::device_api::device_contacts;
use crate::models::{Device, DevicePolicy, MESSAGE_APPS};
use crate::phone;

/// Parent-facing names for `MESSAGE_APPS`, same order.
const MESSAGE_APP_LABELS: [&str; 4] = ["No message button", "SMS", "Element X", "Signal / Molly"];

struct SelectOption {
    value: String,
    label: String,
    selected: bool,
}

fn message_app_options(current: Option<&str>, with_default: Option<&str>) -> Vec<SelectOption> {
    let mut options = Vec::new();
    if let Some(default) = with_default {
        let label = MESSAGE_APPS
            .iter()
            .position(|app| *app == default)
            .map(|i| MESSAGE_APP_LABELS[i])
            .unwrap_or(default);
        options.push(SelectOption {
            value: String::new(),
            label: format!("Device default ({label})"),
            selected: current.is_none(),
        });
    }
    for (value, label) in MESSAGE_APPS.iter().zip(MESSAGE_APP_LABELS) {
        options.push(SelectOption {
            value: value.to_string(),
            label: label.to_string(),
            selected: current == Some(*value),
        });
    }
    options
}

struct ContactView {
    id: i64,
    name: String,
    number: String,
    inbound: bool,
    outbound: bool,
    show_on_home: bool,
    message_options: Vec<SelectOption>,
    message_address: String,
}

#[derive(Template)]
#[template(path = "device_calls.html")]
struct DeviceCallsTemplate {
    title: String,
    device: Device,
    managed: bool,
    calls_enabled: bool,
    sms_enabled: bool,
    default_message_options: Vec<SelectOption>,
    contacts: Vec<ContactView>,
    default_country_code: String,
    warnings: Vec<String>,
    error: Option<String>,
    form_name: String,
    form_number: String,
}

pub async fn show_calls(State(state): State<AppState>, Path(id): Path<i64>) -> Response {
    render(&state, id, StatusCode::OK, None, "", "").await
}

/// The page, optionally with a form error (and the rejected input kept in the add form).
async fn render(
    state: &AppState,
    id: i64,
    status: StatusCode,
    error: Option<String>,
    form_name: &str,
    form_number: &str,
) -> Response {
    match load_page(state, id, error, form_name, form_number).await {
        Ok(Some(page)) => (status, Html(page.render().unwrap())).into_response(),
        Ok(None) => (StatusCode::NOT_FOUND, "Device not found").into_response(),
        Err(err) => db_error(id, err),
    }
}

async fn load_page(
    state: &AppState,
    id: i64,
    error: Option<String>,
    form_name: &str,
    form_number: &str,
) -> Result<Option<DeviceCallsTemplate>, sqlx::Error> {
    let Some(device) = sqlx::query_as::<_, Device>("SELECT * FROM devices WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await?
    else {
        return Ok(None);
    };
    let Some(policy) =
        sqlx::query_as::<_, DevicePolicy>("SELECT * FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await?
    else {
        return Ok(None);
    };
    let default_country_code: String =
        sqlx::query_scalar("SELECT default_country_code FROM call_settings WHERE id = 1")
            .fetch_one(&state.db)
            .await?;
    let contacts = device_contacts(state, id)
        .await?
        .into_iter()
        .map(|c| ContactView {
            id: c.contact_id,
            name: c.name,
            number: c.phone_number,
            inbound: c.allow_inbound,
            outbound: c.allow_outbound,
            show_on_home: c.show_on_home,
            message_options: message_app_options(
                c.message_app.as_deref(),
                Some(&policy.default_message_app),
            ),
            message_address: c.message_address.unwrap_or_default(),
        })
        .collect();
    let warnings = Vec::new();

    Ok(Some(DeviceCallsTemplate {
        title: format!("{} - Calls & SMS", device.name),
        managed: policy.calls_managed,
        calls_enabled: policy.calls_enabled,
        sms_enabled: policy.sms_enabled,
        default_message_options: message_app_options(Some(&policy.default_message_app), None),
        contacts,
        default_country_code,
        warnings,
        error,
        form_name: form_name.to_string(),
        form_number: form_number.to_string(),
        device,
    }))
}

fn db_error(device_id: i64, err: sqlx::Error) -> Response {
    tracing::error!(device_id, %err, "calls page database error");
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        "Couldn't save - nothing was changed. Check the server log.",
    )
        .into_response()
}

fn back_to_calls(state: &AppState, id: i64) -> Response {
    // No receivers just means no phone is connected right now; it syncs on its next poll.
    let _ = state.command_notify.send(id);
    Redirect::to(&format!("/devices/{id}/calls")).into_response()
}

fn bad_request(message: &str) -> Response {
    (StatusCode::BAD_REQUEST, message.to_string()).into_response()
}

/// One form with every switch, so a missing checkbox really means "off".
pub async fn save_settings(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let default_message_app = form
        .get("default_message_app")
        .map(String::as_str)
        .unwrap_or("sms");
    if !MESSAGE_APPS.contains(&default_message_app) {
        return bad_request("Unknown messaging app");
    }
    let result = async {
        let mut tx = state.db.begin().await?;
        let updated = sqlx::query(
            "UPDATE device_policy SET calls_managed = ?, calls_enabled = ?, sms_enabled = ?, \
             default_message_app = ?, updated_at = datetime('now') WHERE device_id = ?",
        )
        .bind(form.contains_key("managed"))
        .bind(form.contains_key("calls_enabled"))
        .bind(form.contains_key("sms_enabled"))
        .bind(default_message_app)
        .bind(id)
        .execute(&mut *tx)
        .await?
        .rows_affected();
        tx.commit().await?;
        Ok::<_, sqlx::Error>(updated)
    }
    .await;
    match result {
        Ok(0) => (StatusCode::NOT_FOUND, "Device not found").into_response(),
        Ok(_) => back_to_calls(&state, id),
        Err(err) => db_error(id, err),
    }
}

/// Adds a contact to this device: normalises the number with the default country code, reuses
/// the global contact with that number (renaming it - the name is shared by every device that
/// has it) and attaches it at the end of the list with every flag on.
pub async fn add_contact(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let name = form.get("name").map(|s| s.trim()).unwrap_or("");
    let raw_number = form.get("number").map(String::as_str).unwrap_or("");
    let state = &state;
    let error = |message: String| async move {
        render(
            state,
            id,
            StatusCode::BAD_REQUEST,
            Some(message),
            name,
            raw_number,
        )
        .await
    };

    let name_len = name.chars().count();
    if name_len == 0 || name_len > 60 {
        return error("Give the contact a name (at most 60 characters).".to_string()).await;
    }
    let cc: String =
        match sqlx::query_scalar("SELECT default_country_code FROM call_settings WHERE id = 1")
            .fetch_one(&state.db)
            .await
        {
            Ok(cc) => cc,
            Err(err) => return db_error(id, err),
        };
    let number = match phone::normalize(raw_number, &cc) {
        Ok(number) => number,
        Err(err) => return error(format!("That number can't be used: {err}.")).await,
    };

    let result = async {
        let mut tx = state.db.begin().await?;
        let device_exists: bool =
            sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM device_policy WHERE device_id = ?)")
                .bind(id)
                .fetch_one(&mut *tx)
                .await?;
        if !device_exists {
            return Ok(false);
        }
        let contact_id: i64 = sqlx::query_scalar(
            "INSERT INTO contacts (name, phone_number) VALUES (?, ?) \
             ON CONFLICT(phone_number) DO UPDATE SET name = excluded.name RETURNING id",
        )
        .bind(name)
        .bind(&number)
        .fetch_one(&mut *tx)
        .await?;
        sqlx::query(
            "INSERT OR IGNORE INTO device_contacts (device_id, contact_id, sort_order) \
             VALUES (?, ?, (SELECT COALESCE(MAX(sort_order) + 1, 0) FROM device_contacts \
                            WHERE device_id = ?))",
        )
        .bind(id)
        .bind(contact_id)
        .bind(id)
        .execute(&mut *tx)
        .await?;
        tx.commit().await?;
        Ok::<_, sqlx::Error>(true)
    }
    .await;
    match result {
        Ok(false) => (StatusCode::NOT_FOUND, "Device not found").into_response(),
        Ok(true) => back_to_calls(&state, id),
        Err(err) => db_error(id, err),
    }
}

/// A Matrix user ID as Element expects it: `@localpart:server`, no whitespace.
fn valid_matrix_id(value: &str) -> bool {
    value.len() <= 255
        && value.starts_with('@')
        && value
            .split_once(':')
            .is_some_and(|(local, server)| local.len() > 1 && !server.is_empty())
        && !value.chars().any(char::is_whitespace)
}

/// Saves one contact's flags and Message button, scoped to this device: a contact that isn't
/// attached to it is a 404 (QA #18).
pub async fn update_contact(
    State(state): State<AppState>,
    Path((id, contact_id)): Path<(i64, i64)>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let message_app = match form.get("message_app").map(String::as_str) {
        None | Some("") => None,
        Some(app) if MESSAGE_APPS.contains(&app) => Some(app),
        Some(_) => return bad_request("Unknown messaging app"),
    };
    let message_address = match form.get("message_address").map(|s| s.trim()) {
        None | Some("") => None,
        Some(mxid) if valid_matrix_id(mxid) => Some(mxid),
        Some(_) => {
            return render(
                &state,
                id,
                StatusCode::BAD_REQUEST,
                Some(
                    "A Matrix ID looks like @name:server.org - the contact was not changed."
                        .to_string(),
                ),
                "",
                "",
            )
            .await;
        }
    };

    let result = async {
        let mut tx = state.db.begin().await?;
        let updated = sqlx::query(
            "UPDATE device_contacts SET allow_inbound = ?, allow_outbound = ?, show_on_home = ?, \
             message_app = ?, message_address = ? WHERE device_id = ? AND contact_id = ?",
        )
        .bind(form.contains_key("inbound"))
        .bind(form.contains_key("outbound"))
        .bind(form.contains_key("show_on_home"))
        .bind(message_app)
        .bind(message_address)
        .bind(id)
        .bind(contact_id)
        .execute(&mut *tx)
        .await?
        .rows_affected();
        tx.commit().await?;
        Ok::<_, sqlx::Error>(updated)
    }
    .await;
    match result {
        Ok(0) => (StatusCode::NOT_FOUND, "This contact isn't on this device").into_response(),
        Ok(_) => back_to_calls(&state, id),
        Err(err) => db_error(id, err),
    }
}

/// Detaches a contact from this device, and deletes it from the address book once no device has
/// it any more.
pub async fn remove_contact(
    State(state): State<AppState>,
    Path((id, contact_id)): Path<(i64, i64)>,
) -> Response {
    let result = async {
        let mut tx = state.db.begin().await?;
        let removed =
            sqlx::query("DELETE FROM device_contacts WHERE device_id = ? AND contact_id = ?")
                .bind(id)
                .bind(contact_id)
                .execute(&mut *tx)
                .await?
                .rows_affected();
        sqlx::query(
            "DELETE FROM contacts WHERE id = ? \
             AND NOT EXISTS (SELECT 1 FROM device_contacts WHERE contact_id = ?)",
        )
        .bind(contact_id)
        .bind(contact_id)
        .execute(&mut *tx)
        .await?;
        tx.commit().await?;
        Ok::<_, sqlx::Error>(removed)
    }
    .await;
    match result {
        Ok(0) => (StatusCode::NOT_FOUND, "This contact isn't on this device").into_response(),
        Ok(_) => back_to_calls(&state, id),
        Err(err) => db_error(id, err),
    }
}

/// The global default country code. Every device's `call_policy` carries it (the launcher uses
/// it for incoming numbers), so every device is nudged. Numbers already stored keep the code
/// they were normalised with.
pub async fn save_call_settings(
    State(state): State<AppState>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let back = form
        .get("device_id")
        .and_then(|id| id.parse::<i64>().ok())
        .map(|id| format!("/devices/{id}/calls"))
        .unwrap_or_else(|| "/devices".to_string());
    let cc = form
        .get("default_country_code")
        .map(|s| s.trim().trim_start_matches('+'))
        .unwrap_or("");
    if !phone::valid_country_code(cc) {
        return bad_request("The country code must be 1-3 digits, e.g. 47.");
    }
    let result = async {
        let mut tx = state.db.begin().await?;
        sqlx::query("UPDATE call_settings SET default_country_code = ? WHERE id = 1")
            .bind(cc)
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
                let _ = state.command_notify.send(id);
            }
            Redirect::to(&back).into_response()
        }
        Err(err) => db_error(0, err),
    }
}

#[cfg(test)]
mod tests {
    use super::valid_matrix_id;

    #[test]
    fn matrix_ids() {
        assert!(valid_matrix_id("@mamma:matrix.org"));
        assert!(valid_matrix_id("@a.b-c:example.org:8448"));
        for bad in [
            "mamma:matrix.org",
            "@:matrix.org",
            "@mamma",
            "@mamma:",
            "@ma mma:x.org",
        ] {
            assert!(!valid_matrix_id(bad), "{bad}");
        }
    }
}
