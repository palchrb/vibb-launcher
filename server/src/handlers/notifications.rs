//! Notifications (`/notifications`, design 35 §5): the warnings, This Device (Web Push on this
//! browser, `static/notifications.js`), the inbox of what happened on the phones (`parent_events`,
//! raised by `parent_events::raise`) with each row's delivery line, Getting Notifications (every
//! subscribed device, its health, Send a Test, Remove) and Notify Me About, the per-kind push
//! switches. Reached from the bell on the five tab pages and the Settings hub's row. Plus the
//! service worker's receipt (`POST /notifications/receipt`, no session: a per-delivery token).
//!
//! No GET changes state: a row is a form whose POST marks the event read and 303s to its target;
//! every other form comes back to its own group (`#this-device`, `#inbox`, `#devices`,
//! `#events`), so a save never lands at the top (`scroll-restore.js` keeps the place).

use std::collections::HashMap;

use askama::Template;
use axum::extract::{ConnectInfo, Path, Query, State};
use axum::http::{HeaderMap, StatusCode, header};
use axum::response::{Html, IntoResponse, Redirect, Response};
use axum::{Extension, Form};
use base64::Engine;
use sqlx::Row as _;

use crate::AppState;
use crate::display_time::Zone;
use crate::parent_events::{self, Kind};
use crate::push::{self, Health};
use crate::security::CurrentAdmin;

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
    /// "Pushed to 2 devices · shown on 1" (§5.1), nothing when no push was made.
    pub delivery: Option<String>,
}

/// A row of Getting Notifications: one subscribed device.
pub struct DeviceRow {
    pub id: i64,
    pub label: String,
    /// The badge's words and class.
    pub badge: (&'static str, &'static str),
    /// "Last shown 2 h ago", "Nothing sent yet", "Turned off on that device - …".
    pub sub: String,
    pub active: bool,
    /// The flash under this device's rows (Send a Test's answer): `(text, error)`.
    pub notice: Option<(&'static str, bool)>,
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
    /// The VAPID public key (base64url) for This Device; `None` = push is off on this server.
    push_key: Option<String>,
    /// The admin's active endpoints' 16-hex SHA-256 prefixes, comma-separated (§5.2).
    subscribed: String,
    devices: Vec<DeviceRow>,
    /// The devices that may not be showing notifications, or that pushes don't reach (the
    /// warnings, §6): `(title, sub-line)`.
    not_showing: Vec<(String, String)>,
    /// A push refused the server's signature in the last day (§4.4).
    clock_warning: bool,
    /// A flash in This Device or in Getting Notifications: `(text, error)`.
    this_notice: Option<(&'static str, bool)>,
}

/// A notice code's words and group (`#this-device` = true, `#devices` = false).
fn notice_of(code: Option<&str>) -> Option<(bool, &'static str, bool)> {
    Some(match code? {
        "push_service" => (true, "This browser's push service isn't supported.", true),
        "push_key" => (
            true,
            "This browser sent a key that doesn't work - try again.",
            true,
        ),
        "push_many" => (
            true,
            "10 devices get notifications already - remove one under Getting Notifications first.",
            true,
        ),
        "push_origin" => (
            true,
            "This browser didn't say where the page came from - try again.",
            true,
        ),
        "push_server" => (
            true,
            "Notifications are off on this server - check the server log.",
            true,
        ),
        "test_sent" => (false, "A test is on its way.", false),
        "test_wait" => (false, "Wait half a minute between tests.", true),
        "test_gone" => (
            false,
            "That device's notifications are turned off - turn them on again there.",
            true,
        ),
        _ => return None,
    })
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
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    let back = back_of(params.get("from").map(String::as_str));
    let device = number(&params, "device");
    let before = number(&params, "before");
    let now = chrono::Utc::now();
    let read = async {
        let page = read_page(&state, device, before).await?;
        let ids: Vec<i64> = page.1.iter().map(|r| r.0).collect();
        let lines = delivery_lines(&state.db, &ids).await?;
        let subscriptions = push::subscriptions(&state.db, admin.id, now).await?;
        let known = push::active_endpoint_hashes(&state.db).await?;
        Ok::<_, sqlx::Error>((page, lines, subscriptions, known))
    };
    match read.await {
        Ok(((phones, raw, unread, settings), mut lines, subscriptions, known)) => {
            let zone = Zone::load(&state.db).await;
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
                            delivery: lines.remove(&id).and_then(|d| delivery_line(&d)),
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
            let subscribed = known.join(",");
            let not_showing = subscriptions.iter().filter_map(warning_of).collect();
            let notice = notice_of(params.get("notice").map(String::as_str));
            let sub = number(&params, "sub");
            let devices = subscriptions
                .iter()
                .map(|s| {
                    let mut row = device_row(s, &zone, now);
                    if Some(row.id) == sub {
                        row.notice = notice
                            .filter(|n| !n.0)
                            .map(|(_, text, error)| (text, error));
                    }
                    row
                })
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
                    push_key: state.push.key.as_ref().map(|key| key.public.clone()),
                    subscribed,
                    devices,
                    not_showing,
                    clock_warning: push::signature_refused(&state.db, now).await,
                    this_notice: notice.filter(|n| n.0).map(|(_, text, error)| (text, error)),
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

/// A subscription's warning, `(title, sub-line)`, when it needs the parent (§6, qa-35-s2-code M1).
fn warning_of(sub: &push::Subscription) -> Option<(String, String)> {
    match sub.health {
        Health::MayNotShow => Some((
            format!("Notifications may not be showing on {}", sub.label),
            "Pushed, but that device didn't confirm them".to_string(),
        )),
        Health::NotGettingThrough => Some((
            format!("Notifications aren't reaching {}", sub.label),
            sub.failure.clone().unwrap_or_default(),
        )),
        _ => None,
    }
}

/// A subscription as Getting Notifications shows it.
fn device_row(
    sub: &push::Subscription,
    zone: &Zone,
    now: chrono::DateTime<chrono::Utc>,
) -> DeviceRow {
    let shown = sub
        .last_receipt_at
        .map(|t| format!("Last shown {}", zone.relative(t, now)));
    let (badge, sub_line) = match sub.health {
        Health::Ok => (("On", "badge badge-on"), shown),
        Health::NeverSent if sub.sending => {
            (("On", "badge badge-on"), Some("Sending…".to_string()))
        }
        Health::NeverSent => (
            ("On", "badge badge-on"),
            Some("Nothing sent yet".to_string()),
        ),
        Health::NotGettingThrough => (
            ("Not getting through", "badge badge-warn"),
            Some(format!(
                "{}{}",
                sub.failure.as_deref().unwrap_or(""),
                if sub.sending { " · trying again" } else { "" }
            )),
        ),
        Health::MayNotShow => (
            ("May not be showing", "badge badge-warn"),
            shown.or(Some("Nothing shown yet".to_string())),
        ),
        Health::Gone => (
            ("Turned off", "badge"),
            Some("Turned off on that device - turn it on again there".to_string()),
        ),
    };
    DeviceRow {
        id: sub.id,
        label: sub.label.clone(),
        badge,
        sub: sub_line.unwrap_or_else(|| "Nothing shown yet".to_string()),
        active: sub.health != Health::Gone,
        notice: None,
    }
}

/// One delivery of an event, for its line: status, reason, a receipt came, the device's label.
type DeliveryState = (String, Option<String>, bool, String);

/// The page's events' deliveries, by event (one query).
async fn delivery_lines(
    db: &sqlx::SqlitePool,
    event_ids: &[i64],
) -> Result<HashMap<i64, Vec<DeliveryState>>, sqlx::Error> {
    // A folded delivery takes its summary's status (the subscription's next summary after it),
    // so "In a summary" means the summary itself went out (qa-35-s2-code N4).
    let rows: Vec<(i64, String, Option<String>, bool, String)> = sqlx::query_as(
        "SELECT d.event_id, \
                CASE WHEN d.reason = 'folded' THEN COALESCE((SELECT m.status \
                    FROM push_deliveries m WHERE m.subscription_id = d.subscription_id \
                    AND m.kind = 'summary' AND m.id > d.id ORDER BY m.id LIMIT 1), 'failed') \
                ELSE d.status END, \
                d.reason, d.receipt_at IS NOT NULL, s.label \
         FROM push_deliveries d JOIN push_subscriptions s ON s.id = d.subscription_id \
         WHERE d.event_id IN (SELECT value FROM json_each(?)) ORDER BY d.id",
    )
    .bind(serde_json::to_string(event_ids).unwrap_or_default())
    .fetch_all(db)
    .await?;
    let mut lines: HashMap<i64, Vec<DeliveryState>> = HashMap::new();
    for (event_id, status, reason, shown, label) in rows {
        lines
            .entry(event_id)
            .or_default()
            .push((status, reason, shown, label));
    }
    Ok(lines)
}

/// An inbox row's delivery line (§5.1): "Shown on iPhone · Safari", "Pushed to iPhone · Safari,
/// not confirmed", "Pushed to 2 devices · shown on 1", "Sending", "In a summary" (its summary was
/// accepted), "Not sent"; nothing when no push was made. A folded delivery's status is its
/// summary's ([delivery_lines]). A push a restart dropped (design 38: the held `phone_off` the
/// next start's "restarted" replaced) or a newer shutdown superseded counts as none - the row says
/// nothing, never "Not sent".
pub fn delivery_line(deliveries: &[DeliveryState]) -> Option<String> {
    let deliveries: Vec<&DeliveryState> = deliveries
        .iter()
        .filter(|d| !(d.0 == "dropped" && matches!(d.1.as_deref(), Some("restart" | "superseded"))))
        .collect();
    let folded = |d: &&DeliveryState| d.1.as_deref() == Some("folded");
    let accepted: Vec<&DeliveryState> = deliveries
        .iter()
        .copied()
        .filter(|d| d.0 == "accepted" && !folded(d))
        .collect();
    let shown = accepted.iter().filter(|d| d.2).count();
    Some(match accepted.as_slice() {
        [] if deliveries.is_empty() => return None,
        [] if deliveries.iter().any(|d| d.0 == "queued") => "Sending".to_string(),
        [] if deliveries.iter().any(|d| folded(d) && d.0 == "accepted") => {
            "In a summary".to_string()
        }
        [] => "Not sent".to_string(),
        [one] if shown == 1 => format!("Shown on {}", one.3),
        [one] => format!("Pushed to {}, not confirmed", one.3),
        many => format!("Pushed to {} devices · shown on {shown}", many.len()),
    })
}

/// The Devices list's warning row (§6): a device that may not be showing notifications, or that
/// pushes don't reach (qa-35-s2-code M1).
pub async fn warning_row(
    state: &AppState,
    admin_id: i64,
) -> Option<crate::handlers::auth::WarningRow> {
    let subscriptions = push::subscriptions(&state.db, admin_id, chrono::Utc::now())
        .await
        .unwrap_or_else(|err| {
            tracing::error!(%err, "can't read the notification subscriptions");
            Vec::new()
        });
    let warnings: Vec<(String, String)> = subscriptions.iter().filter_map(warning_of).collect();
    let title = match warnings.as_slice() {
        [] => return None,
        [(one, _)] => one.clone(),
        many => format!("Notifications may not be showing on {} devices", many.len()),
    };
    Some(crate::handlers::auth::WarningRow {
        title,
        sub: "Check it under Notifications".to_string(),
    })
}

/// Where a This Device or Getting Notifications form comes back to, with its notice.
fn back_to(form: &HashMap<String, String>, notice: &str, group: &str) -> Response {
    Redirect::to(&format!(
        "/notifications{}&notice={notice}#{group}",
        back_query(form)
    ))
    .into_response()
}

/// Back to Getting Notifications with the notice in that subscription's own group.
fn back_to_sub(form: &HashMap<String, String>, notice: &str, sub: i64) -> Response {
    Redirect::to(&format!(
        "/notifications{}&notice={notice}&sub={sub}#devices",
        back_query(form)
    ))
    .into_response()
}

fn b64url(value: Option<&String>) -> Option<Vec<u8>> {
    base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(value?.trim().trim_end_matches('='))
        .ok()
}

/// This Device's Turn On (§5.2): the browser's subscription (`endpoint`, `p256dh`, `auth`) from
/// `notifications.js`. Refused with a flash in `#this-device`: push off on this server, a push
/// service off the list, a bad key, the 11th device, no `Origin`. The same endpoint again is the
/// same row, made active again.
pub async fn subscribe(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    headers: HeaderMap,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let Some(key) = &state.push.key else {
        return back_to(&form, "push_server", "this-device");
    };
    // `csrf_check` passes `Sec-Fetch-Site: same-origin` without an Origin; the push's
    // `navigate` and the JWT's fallback `sub` need it (§5.2, qa-35 L5).
    let Some(origin) = headers
        .get(header::ORIGIN)
        .and_then(|v| v.to_str().ok())
        .filter(|o| o.starts_with("https://") || o.starts_with("http://"))
        .filter(|o| o.len() <= 255)
    else {
        return back_to(&form, "push_origin", "this-device");
    };
    let endpoint = form.get("endpoint").map(|e| e.trim()).unwrap_or("");
    if endpoint.len() > 1024 || !crate::webpush::allowed(endpoint) {
        // The host only (§4.4): the path is the subscription's secret. A browser whose push
        // service isn't on the list (Chromium on a Pi, 2026-10-19) is found here, not guessed.
        tracing::warn!(
            host = crate::webpush::host_of(endpoint),
            "a browser's push service is not on the list - notifications stay off there"
        );
        return back_to(&form, "push_service", "this-device");
    }
    let (Some(p256dh), Some(auth)) = (b64url(form.get("p256dh")), b64url(form.get("auth"))) else {
        return back_to(&form, "push_key", "this-device");
    };
    if auth.len() != 16 || !crate::webpush::valid_browser_key(&p256dh) {
        return back_to(&form, "push_key", "this-device");
    }
    let label = push::label_of(
        headers
            .get(header::USER_AGENT)
            .and_then(|v| v.to_str().ok())
            .unwrap_or(""),
    );
    let stored = async {
        let mut tx = state.db.begin().await?;
        let others: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM push_subscriptions \
             WHERE admin_id = ? AND state = 'active' AND endpoint != ?",
        )
        .bind(admin.id)
        .bind(endpoint)
        .fetch_one(&mut *tx)
        .await?;
        if others >= push::MAX_SUBSCRIPTIONS {
            return Ok(false);
        }
        sqlx::query(
            "INSERT INTO push_subscriptions \
             (admin_id, endpoint, p256dh, auth, origin, label, vapid_fp) \
             VALUES (?, ?, ?, ?, ?, ?, ?) \
             ON CONFLICT (endpoint) DO UPDATE SET admin_id = excluded.admin_id, \
               p256dh = excluded.p256dh, auth = excluded.auth, origin = excluded.origin, \
               label = excluded.label, vapid_fp = excluded.vapid_fp, state = 'active', \
               gone_at = NULL",
        )
        .bind(admin.id)
        .bind(endpoint)
        .bind(&p256dh)
        .bind(&auth)
        .bind(origin)
        .bind(&label)
        .bind(&key.fingerprint)
        .execute(&mut *tx)
        .await?;
        tx.commit().await.map(|()| true)
    };
    match stored.await {
        Ok(true) => {
            crate::security::record_security_event(
                &state.db,
                "push_subscribed",
                Some(&admin.username),
                None,
                Some(&label),
            )
            .await;
            back_to(&form, "push_on", "this-device")
        }
        Ok(false) => back_to(&form, "push_many", "this-device"),
        Err(err) => db_error(err),
    }
}

/// This Device's Turn Off (§5.2): the browser unsubscribed; its row goes, whichever account
/// made it (This Device shows On for any account's subscription of this browser, L1).
pub async fn unsubscribe(
    State(state): State<AppState>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let done = sqlx::query("DELETE FROM push_subscriptions WHERE endpoint = ?")
        .bind(form.get("endpoint").map(|e| e.trim()).unwrap_or(""))
        .execute(&state.db)
        .await;
    match done {
        Ok(_) => back_to(&form, "push_off", "this-device"),
        Err(err) => db_error(err),
    }
}

/// Getting Notifications' Remove…: the device gets nothing more (its browser keeps a
/// subscription nobody sends to).
pub async fn remove(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let done = sqlx::query("DELETE FROM push_subscriptions WHERE id = ? AND admin_id = ?")
        .bind(id)
        .bind(admin.id)
        .execute(&state.db)
        .await;
    match done {
        Ok(_) => back_to(&form, "removed", "devices"),
        Err(err) => db_error(err),
    }
}

/// Getting Notifications' Send a Test (§6): a visible push to that device, one per 30 s.
pub async fn send_test(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let active: Result<Option<bool>, sqlx::Error> = sqlx::query_scalar(
        "SELECT state = 'active' FROM push_subscriptions WHERE id = ? AND admin_id = ?",
    )
    .bind(id)
    .bind(admin.id)
    .fetch_optional(&state.db)
    .await;
    match active {
        Ok(Some(true)) => {}
        Ok(_) => return back_to_sub(&form, "test_gone", id),
        Err(err) => return db_error(err),
    }
    if !state
        .push
        .take_test_slot(id, chrono::Utc::now().timestamp())
    {
        return back_to_sub(&form, "test_wait", id);
    }
    let queued = sqlx::query(
        "INSERT INTO push_deliveries (subscription_id, kind, token_hash) VALUES (?, 'test', X'')",
    )
    .bind(id)
    .execute(&state.db)
    .await;
    match queued {
        Ok(_) => {
            state.push.wake();
            back_to_sub(&form, "test_sent", id)
        }
        Err(err) => db_error(err),
    }
}

/// The service worker's receipt (§7): `{"d": delivery, "t": token, "a": "shown" | "clicked"}`.
/// 204 when the token is that delivery's (repeats too), 404 otherwise - counted against the
/// client like a bad device token (`limits`), 429 while it is refused.
pub async fn receipt(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<std::net::SocketAddr>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> StatusCode {
    let key = state.net.client(&headers, peer.ip()).key();
    if state.limits.ip_blocked(&key).is_some() {
        return StatusCode::TOO_MANY_REQUESTS;
    }
    #[derive(serde::Deserialize)]
    struct Receipt {
        d: i64,
        t: String,
        a: String,
        /// The worker event behind it and the worker's version (diagnostics, logged only).
        #[serde(default)]
        e: Option<String>,
        #[serde(default)]
        v: Option<String>,
    }
    // The worker's probe (`{"p": 1, ...}`, templates/sw.js `probe`): what the browser handed the
    // worker for a push - logged, nothing stored, 204. The iPhone diagnosis of 2026-10-19.
    if let Ok(probe) = serde_json::from_slice::<serde_json::Value>(&body)
        && probe.get("p").and_then(|p| p.as_i64()) == Some(1)
    {
        let text = probe.to_string();
        tracing::info!(probe = crate::audit::clip(&text, 600), "push probe");
        return StatusCode::NO_CONTENT;
    }
    let Ok(receipt) = serde_json::from_slice::<Receipt>(&body) else {
        return StatusCode::BAD_REQUEST;
    };
    // One line per receipt (delivery id and outcome, never the token): the one trace that says
    // whether a device's worker reaches this server at all (2026-10-19).
    match push::receipt(&state.db, receipt.d, &receipt.t, receipt.a == "clicked").await {
        Ok(push::Receipt::Matched) => {
            let event = crate::audit::clip(receipt.e.as_deref().unwrap_or("-"), 32);
            let worker = crate::audit::clip(receipt.v.as_deref().unwrap_or("-"), 32);
            tracing::info!(delivery = receipt.d, action = %receipt.a, %event, %worker, "push receipt");
            StatusCode::NO_CONTENT
        }
        Ok(push::Receipt::Stale) => {
            tracing::info!(
                delivery = receipt.d,
                "push receipt with an older token - ignored"
            );
            StatusCode::NOT_FOUND
        }
        Ok(push::Receipt::Unknown) => {
            tracing::warn!(
                delivery = receipt.d,
                "push receipt for no delivery - refused"
            );
            state.limits.ip_failed(&key);
            StatusCode::NOT_FOUND
        }
        Err(err) => {
            tracing::error!(%err, "can't store a push receipt");
            StatusCode::INTERNAL_SERVER_ERROR
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_delivery_line() {
        let d = |status: &str, reason: Option<&str>, shown: bool, label: &str| {
            (
                status.to_string(),
                reason.map(str::to_string),
                shown,
                label.to_string(),
            )
        };
        let iphone = "iPhone · Safari";
        for (deliveries, line) in [
            (vec![], None),
            (
                vec![d("accepted", None, true, iphone)],
                Some("Shown on iPhone · Safari"),
            ),
            (
                vec![d("accepted", None, false, iphone)],
                Some("Pushed to iPhone · Safari, not confirmed"),
            ),
            (
                vec![
                    d("accepted", None, true, iphone),
                    d("accepted", None, false, "Mac · Safari"),
                    d("failed", Some("BadJwtToken"), false, "Windows · Edge"),
                ],
                Some("Pushed to 2 devices · shown on 1"),
            ),
            (vec![d("queued", None, false, iphone)], Some("Sending")),
            // A folded delivery carries its summary's status.
            (
                vec![d("accepted", Some("folded"), false, iphone)],
                Some("In a summary"),
            ),
            (
                vec![d("queued", Some("folded"), false, iphone)],
                Some("Sending"),
            ),
            (
                vec![d("failed", Some("folded"), false, iphone)],
                Some("Not sent"),
            ),
            (
                vec![
                    d("failed", Some("expired"), false, iphone),
                    d("dropped", Some("gone"), false, iphone),
                ],
                Some("Not sent"),
            ),
            // Design 38: a held `phone_off` push a restart dropped is no push at all.
            (vec![d("dropped", Some("restart"), false, iphone)], None),
            (vec![d("dropped", Some("superseded"), false, iphone)], None),
            (
                vec![
                    d("dropped", Some("restart"), false, iphone),
                    d("dropped", Some("gone"), false, "Mac · Safari"),
                ],
                Some("Not sent"),
            ),
        ] {
            assert_eq!(
                delivery_line(&deliveries).as_deref(),
                line,
                "{deliveries:?}"
            );
        }
    }
}
