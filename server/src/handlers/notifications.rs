//! Notifications (`/notifications`, design 35 §5.1, step 1): the inbox of what happened on the
//! phones (`parent_events`, raised by `parent_events::raise`) and Notify Me About, the per-kind
//! push switches (stored now; the push itself is step 2). Reached from the bell on the five tab
//! pages and the Settings hub's row.
//!
//! No GET changes state: a row is a form whose POST marks the event read and 303s to its target;
//! Mark All as Read and the switches come back to their own group (`#inbox`, `#events`), so a save
//! never lands at the top (`scroll-restore.js` keeps the place).

use std::collections::HashMap;

use askama::Template;
use axum::Form;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Redirect, Response};
use sqlx::Row as _;

use crate::AppState;
use crate::display_time::Zone;
use crate::parent_events::{self, Kind};

/// The tab the parent came from: the back link's target and words. Anything else is Devices.
fn back_of(from: Option<&str>) -> (&'static str, &'static str, &'static str) {
    match from {
        Some("apps") => ("apps", "/apps", "Apps"),
        Some("locate") => ("locate", "/devices/locate", "Locate"),
        Some("music") => ("music", "/music", "Music"),
        Some("settings") => ("settings", "/settings", "Settings"),
        _ => ("devices", "/devices", "Devices"),
    }
}

/// The inbox's query (`from`, `device`, `before`), kept across its own links and forms.
fn page_query(from: &str, device: Option<i64>) -> String {
    match device {
        Some(id) => format!("?from={from}&device={id}"),
        None => format!("?from={from}"),
    }
}

/// [page_query] from a form's `from` and `device`, for its redirect.
fn back_query(form: &HashMap<String, String>) -> String {
    page_query(
        back_of(form.get("from").map(String::as_str)).0,
        number(form, "device"),
    )
}

fn number(params: &HashMap<String, String>, key: &str) -> Option<i64> {
    params.get(key).and_then(|v| v.trim().parse().ok())
}

/// One inbox row.
pub struct InboxRow {
    pub id: i64,
    pub avatar: &'static str,
    pub initial: String,
    pub title: String,
    pub summary: String,
    pub detail: Option<String>,
    /// "3 min ago" (when it happened), the exact time for `title=`.
    pub when: String,
    pub exact: String,
    pub unread: bool,
}

/// A Notify Me About switch.
pub struct Switch {
    pub name: &'static str,
    pub label: &'static str,
    pub on: bool,
}

#[derive(Template)]
#[template(path = "notifications.html")]
struct NotificationsTemplate {
    title: String,
    /// `from`, the back link's path and words.
    back: (&'static str, &'static str, &'static str),
    /// The picker: every phone, the chosen one marked.
    phones: Vec<(i64, String, bool)>,
    device: Option<i64>,
    /// `?from=…[&device=…]`, for the page's own links.
    query: String,
    rows: Vec<InboxRow>,
    /// An older page exists: "Show older" (`?before=<id>`).
    older_than: Option<i64>,
    /// This is an older page: "Newest" leads back.
    paged: bool,
    /// Unread events under the filter: Mark All as Read shows.
    unread: i64,
    switches: Vec<Switch>,
}

type Row = (
    i64,
    i64,
    String,
    String,
    Option<String>,
    String,
    bool,
    String,
    Option<String>,
);

pub async fn page(
    State(state): State<AppState>,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    let back = back_of(params.get("from").map(String::as_str));
    let device = number(&params, "device");
    let before = number(&params, "before");
    match read_page(&state, device, before).await {
        Ok((phones, raw, unread, settings)) => {
            let zone = Zone::load(&state.db).await;
            let now = chrono::Utc::now();
            let older_than = (raw.len() as i64 > parent_events::PAGE)
                .then(|| raw.get(parent_events::PAGE as usize - 1).map(|r| r.0))
                .flatten();
            let rows = raw
                .into_iter()
                .take(parent_events::PAGE as usize)
                .map(
                    |(
                        id,
                        device_id,
                        title,
                        summary,
                        detail,
                        happened_at,
                        unread,
                        name,
                        enrolled,
                    )| {
                        let at = crate::display_time::parse_db_utc(&happened_at);
                        InboxRow {
                            id,
                            avatar: crate::handlers::devices::avatar_class_of(
                                device_id,
                                enrolled.is_some(),
                            ),
                            initial: crate::handlers::devices::initial(&name),
                            title,
                            summary,
                            detail,
                            when: at.map(|t| zone.relative(t, now)).unwrap_or_default(),
                            exact: at.map(|t| zone.exact(t)).unwrap_or_default(),
                            unread,
                        }
                    },
                )
                .collect();
            let switches = Kind::ALL
                .iter()
                .filter_map(|kind| kind.switch())
                .zip(settings)
                .map(|((name, label), on)| Switch { name, label, on })
                .collect();
            Html(
                NotificationsTemplate {
                    title: "Notifications".to_string(),
                    back,
                    phones: phones
                        .into_iter()
                        .map(|(id, name)| (id, name, Some(id) == device))
                        .collect(),
                    device,
                    query: page_query(back.0, device),
                    rows,
                    older_than,
                    paged: before.is_some(),
                    unread,
                    switches,
                }
                .render()
                .unwrap(),
            )
            .into_response()
        }
        Err(err) => {
            tracing::error!(%err, "can't read the notifications page");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't read the notifications - check the server log.",
            )
                .into_response()
        }
    }
}

/// The phones, a page of events (one more than shown, to know if there are older ones), the unread
/// count under the filter and the switches in [Kind::ALL]'s order.
async fn read_page(
    state: &AppState,
    device: Option<i64>,
    before: Option<i64>,
) -> Result<(Vec<(i64, String)>, Vec<Row>, i64, Vec<bool>), sqlx::Error> {
    let phones = sqlx::query_as("SELECT id, name FROM devices ORDER BY name, id")
        .fetch_all(&state.db)
        .await?;
    let rows = sqlx::query_as(
        "SELECT e.id, e.device_id, e.title, e.summary, e.detail, e.happened_at, \
                e.read_at IS NULL, d.name, d.enrolled_at \
         FROM parent_events e JOIN devices d ON d.id = e.device_id \
         WHERE (?1 IS NULL OR e.device_id = ?1) AND (?2 IS NULL OR e.id < ?2) \
         ORDER BY e.id DESC LIMIT ?3",
    )
    .bind(device)
    .bind(before)
    .bind(parent_events::PAGE + 1)
    .fetch_all(&state.db)
    .await?;
    let unread = sqlx::query_scalar(
        "SELECT COUNT(*) FROM parent_events WHERE read_at IS NULL AND (?1 IS NULL OR device_id = ?1)",
    )
    .bind(device)
    .fetch_one(&state.db)
    .await?;
    let names = switch_names();
    let settings = sqlx::query(&format!(
        "SELECT {} FROM notification_settings WHERE id = 1",
        names.join(", ")
    ))
    .fetch_one(&state.db)
    .await?;
    let on = (0..names.len())
        .map(|i| settings.try_get::<bool, _>(i))
        .collect::<Result<_, _>>()?;
    Ok((phones, rows, unread, on))
}

/// The switches' columns, in [Kind::ALL]'s order (compiled-in names, never input).
fn switch_names() -> Vec<&'static str> {
    Kind::ALL
        .iter()
        .filter_map(|kind| kind.switch())
        .map(|(name, _)| name)
        .collect()
}

/// A row's tap: marks the event read and goes to its target (a local path; anything else, or an
/// event pruned meanwhile, goes back to the inbox as it was shown - `from`, `device`).
pub async fn open(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let target: Result<Option<String>, sqlx::Error> = sqlx::query_scalar(
        "UPDATE parent_events SET read_at = COALESCE(read_at, datetime('now')) WHERE id = ? \
         RETURNING target",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await;
    match target {
        Ok(Some(target)) if parent_events::local_target(&target) => {
            Redirect::to(&target).into_response()
        }
        Ok(_) => {
            Redirect::to(&format!("/notifications{}#inbox", back_query(&form))).into_response()
        }
        Err(err) => db_error(err),
    }
}

/// Mark All as Read: every unread event (of the chosen phone), back to the inbox.
pub async fn read_all(
    State(state): State<AppState>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let device = number(&form, "device");
    let done = sqlx::query(
        "UPDATE parent_events SET read_at = datetime('now') \
         WHERE read_at IS NULL AND (?1 IS NULL OR device_id = ?1)",
    )
    .bind(device)
    .execute(&state.db)
    .await;
    match done {
        Ok(_) => {
            Redirect::to(&format!("/notifications{}#inbox", back_query(&form))).into_response()
        }
        Err(err) => db_error(err),
    }
}

/// Notify Me About: the handler writes every switch (a missing one is off), back to `#events`.
pub async fn save_settings(
    State(state): State<AppState>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let names = switch_names();
    let sql = format!(
        "UPDATE notification_settings SET {} WHERE id = 1",
        names
            .iter()
            .map(|name| format!("{name} = ?"))
            .collect::<Vec<_>>()
            .join(", ")
    );
    let mut update = sqlx::query(&sql);
    for name in names {
        update = update.bind(form.get(name).is_some_and(|v| v == "on"));
    }
    let done = update.execute(&state.db).await;
    match done {
        Ok(_) => {
            Redirect::to(&format!("/notifications{}#events", back_query(&form))).into_response()
        }
        Err(err) => db_error(err),
    }
}

fn db_error(err: sqlx::Error) -> Response {
    tracing::error!(%err, "notifications database error");
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        "Couldn't save - nothing was changed. Check the server log.",
    )
        .into_response()
}
