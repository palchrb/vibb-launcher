//! Notifications (design 35 §5, design 45). `/notifications` is the inbox alone: what happened on
//! the phones (`parent_events`, raised by `parent_events::raise`) with each row's delivery line,
//! reached from the bell on the five tab pages; a gear leads to `/notifications/settings`: the
//! warnings, This Device (Web Push on this browser, `static/notifications.js`), Getting
//! Notifications (every subscribed device, its health, Send a Test, Remove) and Notify Me About,
//! the per-kind push switches (the Settings hub's row leads there). Plus the service worker's
//! receipt (`POST /notifications/receipt`, no session: a per-delivery token) and the `?push=`
//! marker of a tapped notification ([push_marker]).
//!
//! Two GETs write, both idempotent and both only for a signed-in parent (the admin cookie is
//! SameSite=Strict, so a cross-site GET carries no session): opening the inbox marks the rows it
//! shows read (design 45 §1.3; events are shared by all parents, so is their read state), and
//! the `?push=` marker marks its delivery opened. A row is a form whose POST marks the event read
//! and 303s to its target; every other form comes back to its own group (`#inbox`,
//! `#this-device`, `#devices`, `#events`), so a save never lands at the top (`scroll-restore.js`
//! keeps the place).

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
use crate::i18n::{Lang, Words, t, t_count, t_params};
use crate::parent_events::{self, Kind};
use crate::push::{self, Health};
use crate::security::CurrentAdmin;

/// The tab the parent came from: the back link's target and the key of its words. Anything else is Devices.
fn back_of(from: Option<&str>) -> (&'static str, &'static str, &'static str) {
    match from {
        Some("apps") => ("apps", "/apps", "nav.apps"),
        Some("locate") => ("locate", "/devices/locate", "nav.locate"),
        Some("music") => ("music", "/music", "nav.music"),
        Some("settings") => ("settings", "/settings", "nav.settings"),
        _ => ("devices", "/devices", "nav.devices"),
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
    /// The phone's own picture over the letter (server round C).
    pub photo: Option<String>,
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

/// A Notify Me About switch's words, by its `notification_prefs` column ([Kind::switches]); `every_switch_is_worded`
/// keeps a new switch from showing its key.
fn switch_key(column: &str) -> &'static str {
    match column {
        "push_pin_used" => "settings.notifications.switch.pin_used",
        "push_pin_lockout" => "settings.notifications.switch.pin_lockout",
        "push_foreign_access" => "settings.notifications.switch.foreign_access",
        "push_unseen_boot" => "settings.notifications.switch.unseen_boot",
        "push_low_battery" => "settings.notifications.switch.low_battery",
        "push_phone_off" => "settings.notifications.switch.phone_off",
        "push_phone_on" => "settings.notifications.switch.phone_on",
        "push_zones" => "settings.notifications.switch.zones",
        "push_extra_time" => "settings.notifications.switch.extra_time",
        "push_sms_held" => "settings.notifications.switch.sms_held",
        crate::parent_events::APP_UPDATES_SWITCH => "settings.notifications.switch.app_updates",
        _ => "settings.notifications.switch.unknown",
    }
}

/// The inbox (design 45 §1.1).
#[derive(Template)]
#[template(path = "notifications.html")]
struct InboxTemplate {
    lang: Lang,
    title: String,
    /// `from`, the back link's path and the key of its words.
    back: (&'static str, &'static str, &'static str),
    /// The picker: every phone, the chosen one marked.
    phones: Vec<(i64, String, bool)>,
    device: Option<i64>,
    /// `?from=…[&device=…]`, for the page's own links.
    query: String,
    /// The rows, each with its unread state from before this page marked it read.
    rows: Vec<InboxRow>,
    /// An older page exists: "Show older" (`?before=<id>`).
    older_than: Option<i64>,
    /// This is an older page: "Newest" leads back.
    paged: bool,
    /// Unread events under the filter after this page's marking: "Mark all N as read".
    unread_after: i64,
    /// Every unread event after the marking: the gear's `data-unread`, the app badge's source
    /// (`register-sw.js`), as the bell's count on the tab pages.
    badge: i64,
    /// The devices that may not be showing notifications, or that pushes don't reach: rows to
    /// the settings page's Getting Notifications.
    not_showing: Vec<(String, String)>,
    /// A push refused the server's signature in the last day: one row to the settings page.
    clock_warning: bool,
}
crate::i18n::words!(InboxTemplate);

/// Notification Settings (design 45 §1.2).
#[derive(Template)]
#[template(path = "notifications_settings.html")]
struct SettingsTemplate {
    lang: Lang,
    title: String,
    /// `from`, kept for the inbox's back link.
    back: (&'static str, &'static str, &'static str),
    /// The inbox's filter, kept for the query round-trip.
    device: Option<i64>,
    /// `?from=…[&device=…]`: the back link to the inbox.
    query: String,
    switches: Vec<Switch>,
    /// The VAPID public key (base64url) for This Device; `None` = push is off on this server.
    push_key: Option<String>,
    /// The admin's active endpoints' 16-hex SHA-256 prefixes, comma-separated (§5.2).
    subscribed: String,
    devices: Vec<DeviceRow>,
    /// A push refused the server's signature in the last day (§4.4).
    clock_warning: bool,
    /// A flash in This Device or in Getting Notifications: `(text, error)`.
    this_notice: Option<(&'static str, bool)>,
}
crate::i18n::words!(SettingsTemplate);

/// A notice code's words in `lang` and its group (`#this-device` = true, `#devices` = false).
fn notice_of(lang: Lang, code: Option<&str>) -> Option<(bool, &'static str, bool)> {
    let (this_device, key, error) = match code? {
        "push_service" => (true, "settings.notifications.notice.push_service", true),
        "push_key" => (true, "settings.notifications.notice.push_key", true),
        "push_many" => (true, "settings.notifications.notice.push_many", true),
        "push_origin" => (true, "settings.notifications.notice.push_origin", true),
        "push_server" => (true, "settings.notifications.notice.push_server", true),
        "test_sent" => (false, "settings.notifications.notice.test_sent", false),
        "test_wait" => (false, "settings.notifications.notice.test_wait", true),
        "test_gone" => (false, "settings.notifications.notice.test_gone", true),
        _ => return None,
    };
    Some((this_device, t(lang, key), error))
}

/// An event as the inbox reads it ([ROW_COLUMNS]): id, phone, title, summary, detail,
/// `happened_at`, unread, the phone's name and `enrolled_at`, the kind and its parameters, and the
/// phone's own picture (migration 0100). The phone and its name are `None` for a catalog event
/// (`app_update_available`, migration 0099).
pub(crate) type Row = (
    i64,
    Option<i64>,
    String,
    String,
    Option<String>,
    String,
    bool,
    Option<String>,
    Option<String>,
    String,
    Option<String>,
    Option<String>,
);

/// [Row]'s columns, of [row_from]'s tables.
pub(crate) const ROW_COLUMNS: &str = "e.id, e.device_id, e.title, e.summary, e.detail, \
     e.happened_at, r.event_id IS NULL, d.name, d.enrolled_at, e.kind, e.params, d.avatar_hash";

/// [ROW_COLUMNS]' tables, unread for the admin bound as `?{admin}` (design 62 §5: each parent
/// reads for themselves).
pub(crate) fn row_from(admin: usize) -> String {
    format!(
        "parent_events e LEFT JOIN devices d ON d.id = e.device_id \
         LEFT JOIN parent_event_reads r ON r.event_id = e.id AND r.admin_id = ?{admin}"
    )
}

/// One event as a row: the inbox's, and the phone summary's Activity (design 48 §1.2), which
/// doesn't show the avatar. `words` says when it happened ("3 min ago" in the inbox, the clock
/// on the summary); the delivery line is the inbox's to add.
pub(crate) fn inbox_row(
    raw: Row,
    zone: &Zone,
    now: chrono::DateTime<chrono::Utc>,
    words: impl Fn(chrono::DateTime<chrono::Utc>) -> String,
) -> InboxRow {
    let (
        id,
        _device,
        title,
        summary,
        detail,
        happened_at,
        unread,
        name,
        enrolled,
        kind,
        params,
        avatar_hash,
    ) = raw;
    // Told from its parameters in the reader's language (design 64's zones, every kind since
    // design 70 §4); a row raised before that keeps its stored words.
    let detail = parent_events::detail(&kind, &title, detail, params.as_deref(), zone, now);
    let (title, summary) = parent_events::told(&kind, title, summary, params.as_deref(), zone, now);
    let at = crate::display_time::parse_db_utc(&happened_at);
    // A catalog event has no phone: a grey avatar with the app's letter (its title starts with it).
    let (avatar, initial) = match &name {
        Some(name) => (
            crate::handlers::devices::avatar_class_of(enrolled.is_some()),
            crate::handlers::devices::initial(name),
        ),
        None => (
            crate::handlers::devices::avatar_class_of(false),
            crate::handlers::devices::initial(&title),
        ),
    };
    InboxRow {
        id,
        avatar,
        initial,
        photo: crate::handlers::device_avatar::src_of(avatar_hash.as_deref()),
        title,
        summary,
        detail,
        when: at.map(&words).unwrap_or_default(),
        exact: at.map(|t| zone.exact(t)).unwrap_or_default(),
        unread,
        delivery: None,
    }
}

/// The inbox (design 45 §1.1, §1.3): reads the page, marks exactly the rows it shows read, then
/// counts what is left; the rows keep the unread state they had, for their dot.
pub async fn page(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    let back = back_of(params.get("from").map(String::as_str));
    let device = number(&params, "device");
    let before = number(&params, "before");
    let now = chrono::Utc::now();
    let read = async {
        let (phones, raw) = read_page(&state, admin.id, device, before).await?;
        let shown: Vec<i64> = raw
            .iter()
            .take(parent_events::PAGE as usize)
            .map(|r| r.0)
            .collect();
        let ids = serde_json::to_string(&shown).unwrap_or_default();
        parent_events::mark_read(&state.db, admin.id, &ids).await?;
        let (unread_after, badge): (i64, i64) = sqlx::query_as(&format!(
            "SELECT COALESCE(SUM(?2 IS NULL OR e.device_id = ?2), 0), COUNT(*) \
             FROM parent_events e WHERE {}",
            parent_events::UNREAD
        ))
        .bind(admin.id)
        .bind(device)
        .fetch_one(&state.db)
        .await?;
        // This parent's own devices only (design 62 §5, qa-62-step345-code P62b-1).
        let lines = delivery_lines(&state.db, admin.id, &shown).await?;
        let subscriptions = push::subscriptions(&state.db, lang, admin.id, now).await?;
        Ok::<_, sqlx::Error>((phones, raw, unread_after, badge, lines, subscriptions))
    };
    let (phones, raw, unread_after, badge, mut lines, subscriptions) = match read.await {
        Ok(read) => read,
        Err(err) => return read_error(lang, err),
    };
    let zone = Zone::load(&state.db, lang).await;
    let older_than = (raw.len() as i64 > parent_events::PAGE)
        .then(|| raw.get(parent_events::PAGE as usize - 1).map(|r| r.0))
        .flatten();
    let rows = raw
        .into_iter()
        .take(parent_events::PAGE as usize)
        .map(|raw| {
            let mut row = inbox_row(raw, &zone, now, |t| zone.relative(t, now));
            row.delivery = lines.remove(&row.id).and_then(|d| delivery_line(lang, &d));
            row
        })
        .collect();
    Html(
        InboxTemplate {
            lang,
            title: t(lang, "settings.notifications.title").to_string(),
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
            unread_after,
            badge,
            not_showing: subscriptions
                .iter()
                .filter_map(|sub| warning_of(lang, sub))
                .collect(),
            clock_warning: push::signature_refused(&state.db, now).await,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

/// Notification Settings (design 45 §1.2): This Device, Getting Notifications, Notify Me About.
pub async fn settings_page(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    let back = back_of(params.get("from").map(String::as_str));
    let device = number(&params, "device");
    let now = chrono::Utc::now();
    let read = async {
        let settings = read_switches(&state.db, admin.id).await?;
        let subscriptions = push::subscriptions(&state.db, lang, admin.id, now).await?;
        let known = push::active_endpoint_hashes(&state.db).await?;
        Ok::<_, sqlx::Error>((settings, subscriptions, known))
    };
    let (settings, subscriptions, known) = match read.await {
        Ok(read) => read,
        Err(err) => return read_error(lang, err),
    };
    let zone = Zone::load(&state.db, lang).await;
    let switches = Kind::switches()
        .into_iter()
        .zip(settings)
        .map(|((name, _), on)| Switch {
            name,
            label: t(lang, switch_key(name)),
            on,
        })
        .collect();
    let notice = notice_of(lang, params.get("notice").map(String::as_str));
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
        SettingsTemplate {
            lang,
            title: t(lang, "settings.notifications.settings_title").to_string(),
            back,
            device,
            query: page_query(back.0, device),
            switches,
            push_key: state.push.key.as_ref().map(|key| key.public.clone()),
            subscribed: known.join(","),
            devices,
            clock_warning: push::signature_refused(&state.db, now).await,
            this_notice: notice.filter(|n| n.0).map(|(_, text, error)| (text, error)),
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

/// One device row as JSON, for Send a Test's poll (server round 5 §5).
#[derive(serde::Serialize)]
struct DeviceRowJson {
    id: i64,
    badge: &'static str,
    badge_class: &'static str,
    sub: String,
}

/// `GET /notifications/subscriptions.json`: this admin's Getting Notifications rows (the badge
/// and the sub-line only - never an endpoint or a key), which `notifications.js` polls after Send
/// a Test to re-render them in place. No-store like every `/notifications` path.
pub async fn subscriptions_json(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let now = chrono::Utc::now();
    let subscriptions = match push::subscriptions(&state.db, lang, admin.id, now).await {
        Ok(subscriptions) => subscriptions,
        Err(err) => return read_error(lang, err),
    };
    let zone = Zone::load(&state.db, lang).await;
    let rows: Vec<DeviceRowJson> = subscriptions
        .iter()
        .map(|s| {
            let row = device_row(s, &zone, now);
            DeviceRowJson {
                id: row.id,
                badge: row.badge.0,
                badge_class: row.badge.1,
                sub: row.sub,
            }
        })
        .collect();
    axum::Json(rows).into_response()
}

fn read_error(lang: Lang, err: sqlx::Error) -> Response {
    tracing::error!(%err, "can't read the notifications page");
    crate::i18n::error(
        lang,
        StatusCode::INTERNAL_SERVER_ERROR,
        "error.read_notifications",
    )
}

/// The `?push=<delivery>` marker of a tapped notification (server round 5 §1), a layer on the
/// admin routes inside `require_full_auth`: on a GET carrying `push`, a delivery of one of this
/// admin's subscriptions is marked opened (`push::opened`), and the answer is a 303 to the same
/// URL without `push` - so Back and reload don't mark again and no page sees it; the fragment
/// (never sent to the server) survives the redirect. An unknown or foreign id, or garbage: the
/// same 303, nothing marked. A missing tap changes nothing.
pub async fn push_marker(
    State(state): State<AppState>,
    request: axum::extract::Request,
    next: axum::middleware::Next,
) -> Response {
    let Some((delivery, rest)) = (request.method() == axum::http::Method::GET)
        .then(|| request.uri().query().and_then(split_marker))
        .flatten()
    else {
        return next.run(request).await;
    };
    if let (Some(delivery), Some(CurrentAdmin(admin))) =
        (delivery, request.extensions().get::<CurrentAdmin>())
    {
        match push::opened(&state.db, admin.id, delivery).await {
            Ok(true) => tracing::info!(delivery, "a push was opened"),
            Ok(false) => {}
            Err(err) => tracing::error!(%err, "can't mark a push opened"),
        }
    }
    let path = request.uri().path();
    let to = if rest.is_empty() {
        path.to_string()
    } else {
        format!("{path}?{rest}")
    };
    Redirect::to(&to).into_response()
}

/// A query's `push` marker and the query without it: `None` when there is no `push` key; the id
/// is `None` when its value isn't a number.
fn split_marker(query: &str) -> Option<(Option<i64>, String)> {
    let mut marker = None;
    let mut rest = Vec::new();
    for pair in query.split('&') {
        match pair.split_once('=').unwrap_or((pair, "")) {
            ("push", value) => marker = Some(value.parse::<i64>().ok()),
            _ => rest.push(pair),
        }
    }
    marker.map(|id| (id, rest.join("&")))
}

/// The phones and a page of events (one more than shown, to know if there are older ones), unread
/// for `admin_id`.
async fn read_page(
    state: &AppState,
    admin_id: i64,
    device: Option<i64>,
    before: Option<i64>,
) -> Result<(Vec<(i64, String)>, Vec<Row>), sqlx::Error> {
    let phones = sqlx::query_as("SELECT id, name FROM devices ORDER BY name, id")
        .fetch_all(&state.db)
        .await?;
    let rows = sqlx::query_as(&format!(
        "SELECT {ROW_COLUMNS} FROM {} \
         WHERE (?1 IS NULL OR e.device_id = ?1) AND (?2 IS NULL OR e.id < ?2) \
         ORDER BY e.id DESC LIMIT ?3",
        row_from(4)
    ))
    .bind(device)
    .bind(before)
    .bind(parent_events::PAGE + 1)
    .bind(admin_id)
    .fetch_all(&state.db)
    .await?;
    Ok((phones, rows))
}

/// `admin_id`'s Notify Me About switches, in [Kind::ALL]'s order (design 62 §5: no row = every
/// switch's default - on, but "App updates" off, `Kind::switch_default`).
async fn read_switches(db: &sqlx::SqlitePool, admin_id: i64) -> Result<Vec<bool>, sqlx::Error> {
    let names = switch_names();
    let settings = sqlx::query(&format!(
        "SELECT {} FROM notification_prefs WHERE admin_id = ?",
        names.join(", ")
    ))
    .bind(admin_id)
    .fetch_optional(db)
    .await?;
    match settings {
        Some(settings) => (0..names.len())
            .map(|i| settings.try_get::<bool, _>(i))
            .collect(),
        None => Ok(names
            .iter()
            .map(|name| Kind::switch_default(name))
            .collect()),
    }
}

/// The switches' columns, in [Kind::ALL]'s order (compiled-in names, never input).
fn switch_names() -> Vec<&'static str> {
    Kind::switches().into_iter().map(|(name, _)| name).collect()
}

/// A row's tap: marks the event read for this parent and goes to its target (a local path;
/// anything else, or an event pruned meanwhile, goes back to the inbox as it was shown - `from`,
/// `device`).
pub async fn open(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let target = async {
        parent_events::mark_read(&state.db, admin.id, &format!("[{id}]")).await?;
        sqlx::query_scalar::<_, String>("SELECT target FROM parent_events WHERE id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await
    }
    .await;
    match target {
        Ok(Some(target)) if parent_events::local_target(&target) => {
            Redirect::to(&target).into_response()
        }
        Ok(_) => {
            Redirect::to(&format!("/notifications{}#inbox", back_query(&form))).into_response()
        }
        Err(err) => db_error(lang, err),
    }
}

/// Mark All as Read: every event (of the chosen phone) read for this parent, back to the inbox.
pub async fn read_all(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let device = number(&form, "device");
    let done = sqlx::query(
        "INSERT OR IGNORE INTO parent_event_reads (admin_id, event_id) \
         SELECT ?1, id FROM parent_events WHERE ?2 IS NULL OR device_id = ?2",
    )
    .bind(admin.id)
    .bind(device)
    .execute(&state.db)
    .await;
    match done {
        Ok(_) => {
            Redirect::to(&format!("/notifications{}#inbox", back_query(&form))).into_response()
        }
        Err(err) => db_error(lang, err),
    }
}

/// Notify Me About: the handler writes every switch of this parent's own (a missing one is off;
/// design 62 §5), back to `#events`. A removed account's held request writes nothing.
pub async fn save_settings(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let names = switch_names();
    let sql = format!(
        "INSERT INTO notification_prefs (admin_id, {}) SELECT id, {} FROM admin_users \
         WHERE id = ? AND removed_at IS NULL \
         ON CONFLICT (admin_id) DO UPDATE SET {}",
        names.join(", "),
        vec!["?"; names.len()].join(", "),
        names
            .iter()
            .map(|name| format!("{name} = excluded.{name}"))
            .collect::<Vec<_>>()
            .join(", ")
    );
    let mut update = sqlx::query(&sql);
    for name in &names {
        update = update.bind(form.get(*name).is_some_and(|v| v == "on"));
    }
    update = update.bind(admin.id);
    let done = update.execute(&state.db).await;
    match done {
        Ok(_) => Redirect::to(&format!(
            "/notifications/settings{}#events",
            back_query(&form)
        ))
        .into_response(),
        Err(err) => db_error(lang, err),
    }
}

fn db_error(lang: Lang, err: sqlx::Error) -> Response {
    tracing::error!(%err, "notifications database error");
    crate::i18n::error(
        lang,
        StatusCode::INTERNAL_SERVER_ERROR,
        "error.save_failed_log",
    )
}

/// A subscription's warning, `(title, sub-line)`, when it needs the parent (§6, qa-35-s2-code M1).
fn warning_of(lang: Lang, sub: &push::Subscription) -> Option<(String, String)> {
    match sub.health {
        Health::MayNotShow => Some((
            t_params(
                lang,
                "devices.warning.push_may_not_show",
                &[("label", &sub.label)],
            ),
            t(lang, "devices.warning.push_unconfirmed").to_string(),
        )),
        Health::NotGettingThrough => Some((
            t_params(
                lang,
                "devices.warning.push_not_reaching",
                &[("label", &sub.label)],
            ),
            sub.failure.clone().unwrap_or_default(),
        )),
        // A push service said it is gone (404/410): never silent (2026-11-04) - the row stays until that device
        // turns notifications on again (its new subscription replaces it) or the parent removes it.
        Health::Gone => Some((
            t_params(lang, "devices.warning.push_gone", &[("label", &sub.label)]),
            t(lang, "devices.warning.push_gone_sub").to_string(),
        )),
        Health::Ok | Health::NeverSent => None,
    }
}

/// A subscription as Getting Notifications shows it, in the [Zone]'s language.
fn device_row(
    sub: &push::Subscription,
    zone: &Zone,
    now: chrono::DateTime<chrono::Utc>,
) -> DeviceRow {
    let lang = zone.lang();
    let when = |t| zone.relative(t, now);
    // The newer of the last receipt and the last push Apple's service accepted and shows itself.
    let last = match (sub.last_receipt_at, sub.last_delivered_at) {
        (Some(r), Some(d)) if d > r => Some(t_params(
            lang,
            "settings.notifications.row.last_delivered",
            &[("when", &when(d))],
        )),
        (Some(r), _) => Some(t_params(
            lang,
            "settings.notifications.row.last_shown",
            &[("when", &when(r))],
        )),
        (None, Some(d)) => Some(t_params(
            lang,
            "settings.notifications.row.last_delivered",
            &[("when", &when(d))],
        )),
        (None, None) => None,
    };
    let nothing_shown = || t(lang, "settings.notifications.row.nothing_shown").to_string();
    // The quiet count (server round 5 §2): pushes since the device last confirmed or opened one.
    let shown = |line: Option<String>| {
        let line = line.unwrap_or_else(nothing_shown);
        let n = sub.since_opened.to_string();
        Some(match (sub.since_opened, sub.ever_opened) {
            (0, _) => line,
            (_, true) => t_params(
                lang,
                "settings.notifications.row.since_opened",
                &[("line", &line), ("n", &n)],
            ),
            (_, false) => t_params(
                lang,
                "settings.notifications.row.not_opened",
                &[("line", &line), ("n", &n)],
            ),
        })
    };
    let on = (t(lang, "common.on"), "badge badge-on");
    let (badge, sub_line) = match sub.health {
        Health::Ok => (on, shown(last)),
        Health::NeverSent if sub.sending => (
            on,
            Some(t(lang, "settings.notifications.row.sending").to_string()),
        ),
        Health::NeverSent => (
            on,
            Some(t(lang, "settings.notifications.row.nothing_sent").to_string()),
        ),
        Health::NotGettingThrough => (
            (
                t(lang, "settings.notifications.badge.not_through"),
                "badge badge-warn",
            ),
            Some({
                let failure = sub.failure.as_deref().unwrap_or("");
                if sub.sending {
                    t_params(
                        lang,
                        "settings.notifications.row.trying_again",
                        &[("failure", failure)],
                    )
                } else {
                    failure.to_string()
                }
            }),
        ),
        Health::MayNotShow => (
            (
                t(lang, "settings.notifications.badge.may_not_show"),
                "badge badge-warn",
            ),
            shown(last),
        ),
        Health::Gone => (
            (t(lang, "settings.notifications.badge.off"), "badge"),
            Some(t(lang, "settings.notifications.row.gone").to_string()),
        ),
    };
    DeviceRow {
        id: sub.id,
        label: sub.label.clone(),
        badge,
        sub: sub_line.unwrap_or_else(nothing_shown),
        active: sub.health != Health::Gone,
        notice: None,
    }
}

/// One delivery of an event, for its line: status, reason (`declarative`: Apple's service shows it
/// itself, no receipt comes), a receipt came, the device's label, it was opened (a tap: the
/// `?push=` marker or a `clicked` receipt).
type DeliveryState = (String, Option<String>, bool, String, bool);

/// The page's events' deliveries, by event (one query).
async fn delivery_lines(
    db: &sqlx::SqlitePool,
    admin_id: i64,
    event_ids: &[i64],
) -> Result<HashMap<i64, Vec<DeliveryState>>, sqlx::Error> {
    // A folded delivery takes its summary's status (the subscription's next summary after it),
    // so "In a summary" means the summary itself went out (qa-35-s2-code N4).
    let rows: Vec<(i64, String, Option<String>, bool, String, bool)> = sqlx::query_as(
        "SELECT d.event_id, \
                CASE WHEN d.reason = 'folded' THEN COALESCE((SELECT m.status \
                    FROM push_deliveries m WHERE m.subscription_id = d.subscription_id \
                    AND m.kind = 'summary' AND m.id > d.id ORDER BY m.id LIMIT 1), 'failed') \
                ELSE d.status END, \
                d.reason, d.receipt_at IS NOT NULL, s.label, d.clicked_at IS NOT NULL \
         FROM push_deliveries d JOIN push_subscriptions s ON s.id = d.subscription_id \
         WHERE d.event_id IN (SELECT value FROM json_each(?)) AND s.admin_id = ? ORDER BY d.id",
    )
    .bind(serde_json::to_string(event_ids).unwrap_or_default())
    .bind(admin_id)
    .fetch_all(db)
    .await?;
    let mut lines: HashMap<i64, Vec<DeliveryState>> = HashMap::new();
    for (event_id, status, reason, shown, label, opened) in rows {
        lines
            .entry(event_id)
            .or_default()
            .push((status, reason, shown, label, opened));
    }
    Ok(lines)
}

/// An inbox row's delivery line (§5.1): "Opened on iPhone · Safari" (a tap, server round 5 §1),
/// "Shown on iPhone · Safari", "Delivered to iPhone · Safari"
/// (Apple's service accepted a declarative push it shows itself), "Pushed to iPhone · Safari, not
/// confirmed", "Pushed to 2 devices · opened on 1" when any was opened, else "Pushed to 2 devices
/// · shown on 1" (delivered ones counted), "Sending", "In a
/// summary" (its summary was accepted), "Not sent"; nothing when no push was made. A folded delivery's status is its
/// summary's ([delivery_lines]). A push a restart dropped (design 38: the held `phone_off` the
/// next start's "restarted" replaced) or a newer shutdown superseded counts as none - the row says
/// nothing, never "Not sent".
pub fn delivery_line(lang: Lang, deliveries: &[DeliveryState]) -> Option<String> {
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
    let delivered = |d: &&DeliveryState| d.2 || d.1.as_deref() == Some("declarative");
    let shown = accepted.iter().filter(|d| delivered(d)).count().to_string();
    let opened = accepted.iter().filter(|d| d.4).count();
    let to = |key: &str, label: &str| t_params(lang, key, &[("label", label)]);
    Some(match accepted.as_slice() {
        [] if deliveries.is_empty() => return None,
        [] if deliveries.iter().any(|d| d.0 == "queued") => {
            t(lang, "settings.notifications.delivery.sending").to_string()
        }
        [] if deliveries.iter().any(|d| folded(d) && d.0 == "accepted") => {
            t(lang, "settings.notifications.delivery.summary").to_string()
        }
        [] => t(lang, "settings.notifications.delivery.not_sent").to_string(),
        [one] if one.4 => to("settings.notifications.delivery.opened", &one.3),
        [one] if one.2 => to("settings.notifications.delivery.shown", &one.3),
        [one] if delivered(one) => to("settings.notifications.delivery.delivered", &one.3),
        [one] => to("settings.notifications.delivery.pushed", &one.3),
        many if opened > 0 => t_params(
            lang,
            "settings.notifications.delivery.many_opened",
            &[
                ("devices", &many.len().to_string()),
                ("opened", &opened.to_string()),
            ],
        ),
        many => t_params(
            lang,
            "settings.notifications.delivery.many_shown",
            &[("devices", &many.len().to_string()), ("shown", &shown)],
        ),
    })
}

/// The Devices list's warning row (§6): a device that may not be showing notifications, or that
/// pushes don't reach (qa-35-s2-code M1).
pub async fn warning_row(
    state: &AppState,
    admin_id: i64,
    lang: Lang,
) -> Option<crate::handlers::auth::WarningRow> {
    let subscriptions = push::subscriptions(&state.db, lang, admin_id, chrono::Utc::now())
        .await
        .unwrap_or_else(|err| {
            tracing::error!(%err, "can't read the notification subscriptions");
            Vec::new()
        });
    let warnings: Vec<(String, String)> = subscriptions
        .iter()
        .filter_map(|sub| warning_of(lang, sub))
        .collect();
    let title = match warnings.as_slice() {
        [] => return None,
        [(one, _)] => one.clone(),
        many => t_count(lang, "devices.warning.push_many", many.len() as i64, &[]),
    };
    Some(crate::handlers::auth::WarningRow {
        title,
        sub: t(lang, "devices.warning.push_sub").to_string(),
    })
}

/// Where a This Device or Getting Notifications form comes back to, with its notice.
fn back_to(form: &HashMap<String, String>, notice: &str, group: &str) -> Response {
    Redirect::to(&format!(
        "/notifications/settings{}&notice={notice}#{group}",
        back_query(form)
    ))
    .into_response()
}

/// Back to Getting Notifications with the notice in that subscription's own group.
fn back_to_sub(form: &HashMap<String, String>, notice: &str, sub: i64) -> Response {
    Redirect::to(&format!(
        "/notifications/settings{}&notice={notice}&sub={sub}#devices",
        back_query(form)
    ))
    .into_response()
}

fn b64url(value: Option<&String>) -> Option<Vec<u8>> {
    base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(value?.trim().trim_end_matches('='))
        .ok()
}

/// A browser's subscription checked and stored ([store_subscription]): its label, or the notice that refuses it.
type Stored = Result<String, &'static str>;

/// The checks and the upsert of This Device's Turn On and of the app's own re-send ([sync]): push off on this server,
/// no `Origin`, a push service off the list, a bad key, the 11th device refuse it (each a notice); the same endpoint
/// again is the same row, made active again in this session's epoch. A `replaces` that names one of this parent's
/// `gone` rows (the browser's dead subscription it just replaced, `static/register-sw.js`) deletes it, so Devices'
/// warning ends with it. Turning it on lifts a Remove of this endpoint ([REMOVED]).
async fn store_subscription(
    state: &AppState,
    admin: &crate::models::AdminUser,
    headers: &HeaderMap,
    form: &HashMap<String, String>,
) -> Result<Stored, sqlx::Error> {
    let Some(key) = &state.push.key else {
        return Ok(Err("push_server"));
    };
    // `csrf_check` passes `Sec-Fetch-Site: same-origin` without an Origin; the push's
    // `navigate` and the JWT's fallback `sub` need it (§5.2, qa-35 L5).
    let Some(origin) = headers
        .get(header::ORIGIN)
        .and_then(|v| v.to_str().ok())
        .filter(|o| o.starts_with("https://") || o.starts_with("http://"))
        .filter(|o| o.len() <= 255)
    else {
        return Ok(Err("push_origin"));
    };
    let endpoint = form.get("endpoint").map(|e| e.trim()).unwrap_or("");
    if endpoint.len() > 1024 || !crate::webpush::allowed(endpoint) {
        // The host only (§4.4): the path is the subscription's secret. A browser whose push
        // service isn't on the list (Chromium on a Pi, 2026-10-19) is found here, not guessed.
        tracing::warn!(
            host = crate::webpush::host_of(endpoint),
            "a browser's push service is not on the list - notifications stay off there"
        );
        return Ok(Err("push_service"));
    }
    let (Some(p256dh), Some(auth)) = (b64url(form.get("p256dh")), b64url(form.get("auth"))) else {
        return Ok(Err("push_key"));
    };
    if auth.len() != 16 || !crate::webpush::valid_browser_key(&p256dh) {
        return Ok(Err("push_key"));
    }
    let label = push::label_of(
        headers
            .get(header::USER_AGENT)
            .and_then(|v| v.to_str().ok())
            .unwrap_or(""),
    );
    let replaces = form
        .get("replaces")
        .map(|e| e.trim())
        .filter(|e| !e.is_empty() && *e != endpoint && e.len() <= 1024);
    let mut tx = state.db.begin().await?;
    // A subscription from a signed-out session counts for nothing (Q-1).
    let others: i64 = sqlx::query_scalar(&format!(
        "SELECT COUNT(*) FROM push_subscriptions s \
         WHERE s.admin_id = ? AND s.state = 'active' AND s.endpoint != ? AND {}",
        push::CURRENT_EPOCH
    ))
    .bind(admin.id)
    .bind(endpoint)
    .fetch_one(&mut *tx)
    .await?;
    if others >= push::MAX_SUBSCRIPTIONS {
        return Ok(Err("push_many"));
    }
    sqlx::query(
        "INSERT INTO push_subscriptions \
         (admin_id, endpoint, p256dh, auth, origin, label, vapid_fp, session_epoch) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?) \
         ON CONFLICT (endpoint) DO UPDATE SET admin_id = excluded.admin_id, \
           p256dh = excluded.p256dh, auth = excluded.auth, origin = excluded.origin, \
           label = excluded.label, vapid_fp = excluded.vapid_fp, \
           session_epoch = excluded.session_epoch, state = 'active', gone_at = NULL",
    )
    .bind(admin.id)
    .bind(endpoint)
    .bind(&p256dh)
    .bind(&auth)
    .bind(origin)
    .bind(&label)
    .bind(&key.fingerprint)
    // The session's epoch (security review M1): when the account's moves past it (a new
    // password, a 2FA reset, Sign Out Everywhere), `push::send_due` deletes this row.
    .bind(admin.session_epoch)
    .execute(&mut *tx)
    .await?;
    sqlx::query("DELETE FROM push_removed WHERE endpoint_hash = ?")
        .bind(push::endpoint_hash(endpoint))
        .execute(&mut *tx)
        .await?;
    if let Some(old) = replaces {
        sqlx::query(
            "DELETE FROM push_subscriptions WHERE endpoint = ? AND admin_id = ? AND state = 'gone'",
        )
        .bind(old)
        .bind(admin.id)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    Ok(Ok(label))
}

/// Endpoints a parent removed under Getting Notifications or turned off on This Device (migration 0128, by
/// [push::endpoint_hash]): the app's own re-send ([sync]) never brings one back - only a tap on Turn On does.
const REMOVED: &str = "push_removed";

/// Notes `endpoint` as one a parent turned off ([REMOVED]).
async fn note_removed(db: &sqlx::SqlitePool, admin_id: i64, endpoint: &str) {
    if let Err(err) = sqlx::query(
        "INSERT INTO push_removed (endpoint_hash, admin_id) VALUES (?, ?) \
         ON CONFLICT (endpoint_hash) DO UPDATE SET admin_id = excluded.admin_id, removed_at = datetime('now')",
    )
    .bind(push::endpoint_hash(endpoint))
    .bind(admin_id)
    .execute(db)
    .await
    {
        tracing::error!(%err, table = REMOVED, "couldn't note a removed subscription");
    }
}

/// The cookie that says a parent turned notifications off in this browser (Push never lost silently, review M-1):
/// `__Host-handy-push-off` (`handy-push-off` with `INSECURE_COOKIES`; Secure, HttpOnly, Strict, 400 days, renewed by
/// every [sync] that answers `removed`). Set by This Device's Turn Off, the banner's Hide and a `removed` answer;
/// deleted by a tap on Turn On. It outlives an endpoint the browser rotates, a cleared localStorage, a new sign-in and
/// [REMOVED]'s rows: the app's own re-send never turns this browser on again while it is set.
pub fn off_cookie_name(insecure: bool) -> &'static str {
    if insecure {
        "handy-push-off"
    } else {
        "__Host-handy-push-off"
    }
}

/// The `Set-Cookie` that marks this browser turned off (`on`), or deletes the mark.
fn off_cookie(insecure: bool, on: bool) -> axum::http::HeaderValue {
    let secure = if insecure { "" } else { "; Secure" };
    let (value, age) = if on {
        ("1", crate::lock::COOKIE_SECS)
    } else {
        ("", 0)
    };
    axum::http::HeaderValue::from_str(&format!(
        "{}={value}; Path=/; Max-Age={age}; HttpOnly; SameSite=Strict{secure}",
        off_cookie_name(insecure)
    ))
    .expect("a cookie of ASCII")
}

/// Whether the request carries this browser's turned-off mark ([off_cookie_name]).
fn marked_off(headers: &HeaderMap, insecure: bool) -> bool {
    let name = off_cookie_name(insecure);
    headers
        .get_all(header::COOKIE)
        .iter()
        .filter_map(|value| value.to_str().ok())
        .flat_map(|value| value.split(';'))
        .any(|pair| {
            pair.trim()
                .split_once('=')
                .is_some_and(|(cookie, value)| cookie == name && value.trim() == "1")
        })
}

fn with_off_cookie(mut response: Response, insecure: bool, on: bool) -> Response {
    response
        .headers_mut()
        .append(header::SET_COOKIE, off_cookie(insecure, on));
    response
}

/// This Device's Turn On (§5.2): the browser's subscription (`endpoint`, `p256dh`, `auth`) from
/// `notifications.js`, checked and stored by [store_subscription]; refused with a flash in `#this-device`. The session
/// notes it as its browser's ([push::remember_this_browser]). A tap: it lifts this browser's turned-off mark.
pub async fn subscribe(
    State(state): State<AppState>,
    lang: Lang,
    session: tower_sessions::Session,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    headers: HeaderMap,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    match store_subscription(&state, &admin, &headers, &form).await {
        Ok(Ok(label)) => {
            subscribed(&state, &session, &admin, &form, &label, false).await;
            // The first opening's card on Devices (2026-11-01) comes back there; This Device to its group.
            let back = if form.get("offer").map(String::as_str) == Some("devices") {
                Redirect::to("/devices?notice=push_on#push-offer-done").into_response()
            } else {
                back_to(&form, "push_on", "this-device")
            };
            with_off_cookie(back, crate::lock::insecure(&state), false)
        }
        Ok(Err(notice)) => crate::settings_log::refused(back_to(&form, notice, "this-device")),
        Err(err) => db_error(lang, err),
    }
}

/// After a stored subscription: `push_subscribed` (the label; re-sent by the app itself, or replacing a dead one,
/// says so) and the session's note of its browser. A tap (not `by_the_app`) also deletes the Remove note of the
/// browser this session had before ([REMOVED]): the parent turned it on here again.
async fn subscribed(
    state: &AppState,
    session: &tower_sessions::Session,
    admin: &crate::models::AdminUser,
    form: &HashMap<String, String>,
    label: &str,
    by_the_app: bool,
) {
    let mut detail = crate::security::Detail::facts(label);
    if by_the_app {
        detail = detail.and("security.detail.push_resent", &[]);
    }
    if form.get("replaces").is_some_and(|e| !e.trim().is_empty()) {
        detail = detail.and("security.detail.push_replaced", &[]);
    }
    crate::security::record_security_event(
        &state.db,
        "push_subscribed",
        Some(&admin.username),
        None,
        Some(detail),
    )
    .await;
    if !by_the_app && let Some(before) = push::this_browser(session).await {
        sqlx::query("DELETE FROM push_removed WHERE endpoint_hash = ?")
            .bind(before)
            .execute(&state.db)
            .await
            .inspect_err(|err| tracing::error!(%err, "couldn't lift a Remove note"))
            .ok();
    }
    if let Some(endpoint) = form.get("endpoint") {
        push::remember_this_browser(session, endpoint.trim()).await;
    }
}

/// A [sync] answer: no-store JSON.
fn sync_answer(body: serde_json::Value) -> Response {
    (
        StatusCode::OK,
        [(header::CACHE_CONTROL, "no-store")],
        axum::Json(body),
    )
        .into_response()
}

/// `POST /notifications/subscriptions/sync` (Push never lost silently, 2026-11-04): the app's own check of this
/// browser's subscription, from `static/register-sw.js` on a signed-in page (and This Device's, `notifications.js`).
/// Never a way past a session: it sits behind `require_full_auth` and the app lock like every page, and a signed-out
/// or locked browser gets no answer to act on. Not a use of the app (`lock::PUSH_SYNC_ROUTE`): it runs at a page's
/// load. The form is the browser's subscription (`endpoint`, `p256dh`, `auth`, `key` = its `applicationServerKey`,
/// base64url, when the browser tells it) or nothing; `asked` = the parent tapped "Turn On" in the app's banner;
/// `off` = the banner's Hide; `replaces` = a dead subscription the browser just replaced. The answer's `state`:
/// - `no_key`: push is off on this server;
/// - `known`: an active row of this session's epoch has it (this parent's - noted as this session's browser - or
///   another parent's on a shared device);
/// - `removed`: a parent turned this browser off - its mark ([off_cookie_name]), the browser this session noted, or
///   the posted endpoint in [REMOVED] (review M-1: a new endpoint, a cleared localStorage or a new sign-in never
///   undo a Remove or a Turn Off) - and this is no tap; the mark is set (renewed) with it;
/// - `none` (no subscription sent), `other_key` (made for another server key), `gone` (its push service ended it):
///   with `key` (the server's) and `words` (the banner's) - the browser makes a new one;
/// - `stored`: stored again ([store_subscription]), logged `push_subscribed` "sent again by the app" (a tap:
///   the mark deleted);
/// - `refused` with `notice` (This Device's words for it).
pub async fn sync(
    State(state): State<AppState>,
    lang: Lang,
    session: tower_sessions::Session,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    headers: HeaderMap,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let Some(key) = &state.push.key else {
        return sync_answer(serde_json::json!({ "state": "no_key" }));
    };
    let insecure = crate::lock::insecure(&state);
    let removed = || {
        with_off_cookie(
            sync_answer(serde_json::json!({ "state": "removed" })),
            insecure,
            true,
        )
    };
    // The banner's Hide: this browser turned off, by the parent.
    if form.get("off").is_some_and(|v| v == "1") {
        return removed();
    }
    let to_make = |state: &str| {
        sync_answer(serde_json::json!({
            "state": state,
            "key": key.public,
            "words": {
                "off": t(lang, "push.banner.off"),
                "on": t(lang, "push.banner.on"),
                "hide": t(lang, "push.banner.hide"),
                "failed": t(lang, "push.banner.failed"),
            },
        }))
    };
    let endpoint = form.get("endpoint").map(|e| e.trim()).unwrap_or("");
    let asked = form.get("asked").is_some_and(|v| v == "1");
    let noted = push::this_browser(&session).await;
    let read = async {
        let row: Option<(i64, String, bool)> = if endpoint.is_empty() {
            None
        } else {
            sqlx::query_as(&format!(
                "SELECT s.admin_id, s.state, {} FROM push_subscriptions s WHERE s.endpoint = ?",
                push::CURRENT_EPOCH
            ))
            .bind(endpoint)
            .fetch_optional(&state.db)
            .await?
        };
        let hashes: Vec<String> = [
            (!endpoint.is_empty()).then(|| push::endpoint_hash(endpoint)),
            noted.clone(),
        ]
        .into_iter()
        .flatten()
        .collect();
        let removed: bool = sqlx::query_scalar(
            "SELECT EXISTS (SELECT 1 FROM push_removed \
             WHERE endpoint_hash IN (SELECT value FROM json_each(?)))",
        )
        .bind(serde_json::to_string(&hashes).unwrap_or_default())
        .fetch_one(&state.db)
        .await?;
        Ok::<_, sqlx::Error>((row, removed))
    };
    let (row, noted_removed) = match read.await {
        Ok(read) => read,
        Err(err) => return db_error(lang, err),
    };
    if let Some((owner, state_now, true)) = &row
        && state_now == "active"
    {
        if *owner == admin.id && noted.as_deref() != Some(push::endpoint_hash(endpoint).as_str()) {
            push::remember_this_browser(&session, endpoint).await;
        }
        return sync_answer(serde_json::json!({ "state": "known" }));
    }
    if !asked && (noted_removed || marked_off(&headers, insecure)) {
        return removed();
    }
    if endpoint.is_empty() {
        return to_make("none");
    }
    let browser_key = form
        .get("key")
        .map(|k| k.trim().trim_end_matches('='))
        .unwrap_or("");
    if !browser_key.is_empty() && browser_key != key.public.trim_end_matches('=') {
        return to_make("other_key");
    }
    if row.is_some_and(|(_, state_now, _)| state_now == "gone") {
        return to_make("gone");
    }
    match store_subscription(&state, &admin, &headers, &form).await {
        Ok(Ok(label)) => {
            subscribed(&state, &session, &admin, &form, &label, !asked).await;
            let stored = sync_answer(serde_json::json!({ "state": "stored" }));
            if asked {
                with_off_cookie(stored, insecure, false)
            } else {
                stored
            }
        }
        Ok(Err(notice)) => sync_answer(serde_json::json!({
            "state": "refused",
            "notice": notice,
            "words": notice_of(lang, Some(notice)).map(|(_, text, _)| text),
        })),
        Err(err) => db_error(lang, err),
    }
}

/// A parent turned subscriptions off: `push_unsubscribed` for each `(owner's username, label)`, and how
/// (`security.detail.*`).
pub async fn log_turned_off(db: &sqlx::SqlitePool, rows: &[(String, String)], how: &'static str) {
    for (username, label) in rows {
        crate::security::record_security_event(
            db,
            "push_unsubscribed",
            Some(username),
            None,
            Some(crate::security::Detail::words(how, &[("label", label)])),
        )
        .await;
    }
}

/// This Device's Turn Off (§5.2): the browser unsubscribed; its row goes, whichever account
/// made it (This Device shows On for any account's subscription of this browser, L1). Logged under the row's own
/// parent and noted as turned off ([REMOVED]) only for a row that went (review L-6), and this browser is marked
/// turned off ([off_cookie_name]) so the app never sends it again by itself.
pub async fn unsubscribe(
    State(state): State<AppState>,
    lang: Lang,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let endpoint = form.get("endpoint").map(|e| e.trim()).unwrap_or("");
    let done: Result<Vec<(i64, String, String)>, sqlx::Error> = sqlx::query_as(
        "DELETE FROM push_subscriptions WHERE endpoint = ? RETURNING admin_id, label, \
         (SELECT a.username FROM admin_users a WHERE a.id = push_subscriptions.admin_id)",
    )
    .bind(endpoint)
    .fetch_all(&state.db)
    .await;
    match done {
        Ok(rows) => {
            if let Some((owner, _, _)) = rows.first() {
                note_removed(&state.db, *owner, endpoint).await;
            }
            let logged: Vec<(String, String)> = rows
                .into_iter()
                .map(|(_, label, username)| (username, label))
                .collect();
            log_turned_off(&state.db, &logged, "security.detail.push_turned_off").await;
            with_off_cookie(
                back_to(&form, "push_off", "this-device"),
                crate::lock::insecure(&state),
                true,
            )
        }
        Err(err) => db_error(lang, err),
    }
}

/// Getting Notifications' Remove…: the device gets nothing more (its browser keeps a
/// subscription nobody sends to - and the app on it never sends it again by itself, [REMOVED]: an active row only;
/// a gone row's Remove only clears the warning). Logged.
pub async fn remove(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let done: Result<Option<(String, String, String)>, sqlx::Error> = sqlx::query_as(
        "DELETE FROM push_subscriptions WHERE id = ? AND admin_id = ? RETURNING endpoint, label, state",
    )
    .bind(id)
    .bind(admin.id)
    .fetch_optional(&state.db)
    .await;
    match done {
        Ok(gone) => {
            if let Some((endpoint, label, row_state)) = gone {
                // Only an active row is a device turned off (review R-1): removing a row its push service ended
                // clears the Devices warning, and that device may turn notifications on again by itself.
                if row_state == "active" {
                    note_removed(&state.db, admin.id, &endpoint).await;
                }
                log_turned_off(
                    &state.db,
                    &[(admin.username.clone(), label)],
                    "security.detail.push_removed",
                )
                .await;
            }
            back_to(&form, "removed", "devices")
        }
        Err(err) => db_error(lang, err),
    }
}

/// Getting Notifications' Send a Test (§6): a visible push to that device, one per 30 s.
pub async fn send_test(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let active: Result<Option<bool>, sqlx::Error> = sqlx::query_scalar(&format!(
        "SELECT s.state = 'active' AND {} FROM push_subscriptions s \
         WHERE s.id = ? AND s.admin_id = ?",
        push::CURRENT_EPOCH
    ))
    .bind(id)
    .bind(admin.id)
    .fetch_optional(&state.db)
    .await;
    match active {
        Ok(Some(true)) => {}
        Ok(_) => return back_to_sub(&form, "test_gone", id),
        Err(err) => return db_error(lang, err),
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
        Err(err) => db_error(lang, err),
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
                false,
            )
        };
        let opened = |mut delivery: DeliveryState| {
            delivery.4 = true;
            delivery
        };
        let iphone = "iPhone · Safari";
        for (deliveries, line) in [
            (vec![], None),
            // A tap (server round 5 §1): opened before shown before delivered.
            (
                vec![opened(d("accepted", Some("declarative"), false, iphone))],
                Some("Opened on iPhone · Safari"),
            ),
            (
                vec![opened(d("accepted", None, true, iphone))],
                Some("Opened on iPhone · Safari"),
            ),
            (
                vec![
                    opened(d("accepted", Some("declarative"), false, iphone)),
                    d("accepted", None, true, "Mac · Safari"),
                ],
                Some("Pushed to 2 devices · opened on 1"),
            ),
            (
                vec![d("accepted", None, true, iphone)],
                Some("Shown on iPhone · Safari"),
            ),
            (
                vec![d("accepted", None, false, iphone)],
                Some("Pushed to iPhone · Safari, not confirmed"),
            ),
            // Apple's service shows a declarative push itself: accepted is delivered.
            (
                vec![d("accepted", Some("declarative"), false, iphone)],
                Some("Delivered to iPhone · Safari"),
            ),
            (
                vec![
                    d("accepted", Some("declarative"), false, iphone),
                    d("accepted", None, false, "Mac · Safari"),
                ],
                Some("Pushed to 2 devices · shown on 1"),
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
                delivery_line(Lang::En, &deliveries).as_deref(),
                line,
                "{deliveries:?}"
            );
        }
        // In Norwegian too.
        assert_eq!(
            delivery_line(Lang::Nb, &[d("accepted", None, true, iphone)]).as_deref(),
            Some("Vist på iPhone · Safari")
        );
    }

    /// Every Notify Me About switch has its words in both catalogs.
    #[test]
    fn every_switch_is_worded() {
        for (column, _) in Kind::switches() {
            let key = switch_key(column);
            assert_ne!(key, "settings.notifications.switch.unknown", "{column}");
            for lang in Lang::ALL {
                assert_ne!(t(lang, key), key, "{column}");
            }
        }
    }
}
