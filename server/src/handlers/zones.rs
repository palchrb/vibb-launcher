//! Zones (design 64; design 135: the family's zones, picked per phone). The pages, all in Locate's tab:
//!
//! - **The family's list** `/zones` ([list_page], `family_zones.html`): every family zone, oldest first, with its
//!   radius and the phones that use it; "Add Zone" (a note at [MAX_FAMILY_ZONES]). `?device=N` sets Back to that
//!   phone's Zones page.
//! - **The zone's editor** `/zones/new`, `/zones/{zone_id}` ([new_zone], [show_zone], `family_zone.html`): name,
//!   centre, radius, the "Phones" checkboxes, Delete Zone…. Every form carries the values it was drawn with (`was_*`,
//!   decision 12) and the save writes only what differs from them, so a stale page never undoes another parent's
//!   change ([save_zone]). A moved centre or a changed radius starts every link over ([zones::start_over]); a rename
//!   changes the name only. The name's rules apply only to a changed name (decision 5).
//! - **A phone's Zones page** `/devices/{id}/zones` (`handlers::locate::show_zones`, `device_zones.html`): one
//!   auto-saving form ([save_phone]) - a switch per family zone (linked or not) and, while linked, the signed-in
//!   parent's "Arrive" and "Leave" (`zone_alerts`).
//! - `zones.json` for the Locate map's circles: the phone's links (`id` = the link's, the policy's).
//! - **The zone's Wi-Fi** (design 138, on the editor's `#zone-wifi`): the routers a parent confirmed for the zone,
//!   the network each linked phone is on now with Confirm ([confirm_wifi], [remove_wifi]). A confirmation is the
//!   family zone's: `zone_networks` keeps it on every link (`crate::zone_networks`).
//!
//! Without JS the forms are the whole feature; `static/zones.js` adds the editor's map. Every save lands on its row
//! (`#zone-N`, N the family zone's id) or its group; a refusal re-renders the editor (400 at `#zone-form`) or, for the
//! autosave form, comes back to the zone's cell with the reason ([crate::settings_log::refused]). Every change is in
//! the security log by the zone's and the phones' names, never coordinates, and nudges the phones whose policy changed.

use askama::Template;
use axum::body::Bytes;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Json, Redirect, Response};
use axum::{Extension, Form};
use std::collections::{HashMap, HashSet};

use crate::AppState;
use crate::i18n::{Lang, Words, t, t_params};
use crate::models::{AdminUser, Device};
use crate::security::{self, CurrentAdmin};
use crate::zones::{self, FamilyZone, MAX_FAMILY_ZONES, MAX_ZONES, NAME_MAX, RADIUS};

/// A phone's tick in the editor's "Phones" group.
struct PhoneTick {
    id: i64,
    name: String,
    /// As shown (stored, or as typed on a refusal).
    ticked: bool,
    /// As the page was drawn (`was_phone_<id>`, decision 12).
    was: bool,
}

/// "Use {phone}'s location": a phone's newest fix (6 decimals).
struct PhoneFix {
    name: String,
    latitude: String,
    longitude: String,
}

#[derive(Template)]
#[template(path = "family_zone.html")]
struct ZoneTemplate {
    lang: Lang,
    title: String,
    /// `None` for a new zone.
    zone_id: Option<i64>,
    /// The page it came from (a real phone id, else none): Back, the landing and the hidden field.
    device: Option<i64>,
    back_href: String,
    back_label: String,
    form: ZoneForm,
    errors: ZoneErrors,
    /// Not saved: the reason in the form's first box (a refusal that is no one field's: the 51st zone).
    refusal: Option<String>,
    phones: Vec<PhoneTick>,
    /// A ticked phone at its cap: "{phone} already has 10 zones." under the group.
    phones_error: Option<String>,
    /// One "Use {phone}'s location" per phone with a fix, the `device` phone first.
    fixes: Vec<PhoneFix>,
    /// Where a new zone's map opens: the `device` phone's newest fix, else any phone's newest.
    start: Option<(String, String)>,
    /// Delete Zone…'s question, with the phones that use the zone.
    delete_question: String,
    radius_min: i64,
    radius_max: i64,
    radius_step: i64,
    /// Design 138: the zone's Wi-Fi group (a stored zone only).
    wifi: crate::zone_networks::WifiView,
    /// The group's flash after a confirm or a remove (`?wifi=`): `(catalog key, is an error)`.
    wifi_flash: Option<(&'static str, bool)>,
}
crate::i18n::words!(ZoneTemplate);

impl ZoneTemplate {
    /// The orange line's words and its (i), `(line key, info key, name)`.
    fn wifi_orange(&self) -> Option<(&'static str, &'static str, &str)> {
        match &self.wifi.orange {
            Some(crate::zone_networks::Orange::Elsewhere(name)) => Some((
                "locate.zone.wifi.elsewhere",
                "locate.zone.wifi.elsewhere_info",
                name.as_str(),
            )),
            None => None,
        }
    }
}

/// The form's values as shown (typed, or stored), and as the page was drawn (`was_*`).
struct ZoneForm {
    name: String,
    latitude: String,
    longitude: String,
    radius: String,
    was_name: String,
    was_latitude: String,
    was_longitude: String,
    was_radius: String,
}

/// Each field's refusal, as its catalog key (the page words it).
#[derive(Default)]
struct ZoneErrors {
    name: Option<&'static str>,
    latitude: Option<&'static str>,
    longitude: Option<&'static str>,
    radius: Option<&'static str>,
}

impl ZoneErrors {
    fn any(&self) -> bool {
        self.name.is_some()
            || self.latitude.is_some()
            || self.longitude.is_some()
            || self.radius.is_some()
    }
}

/// A form that reads: the place as typed.
#[derive(Clone, Debug, PartialEq)]
struct Input {
    name: String,
    latitude: f64,
    longitude: f64,
    radius_m: i64,
}

/// A coordinate to 6 decimals (about 0.1 m): what is stored and shown, so a form saved again
/// unchanged never "moves" the zone.
fn round6(value: f64) -> f64 {
    (value * 1e6).round() / 1e6
}

fn coordinate(value: f64) -> String {
    format!("{:.6}", round6(value))
}

/// A number as typed: a decimal comma reads as a point (a Norwegian keyboard's).
fn number(raw: &str) -> Option<f64> {
    let raw = raw.trim();
    let raw = if raw.contains('.') {
        raw.to_string()
    } else {
        raw.replace(',', ".")
    };
    raw.parse::<f64>().ok().filter(|v| v.is_finite())
}

/// Unicode's format characters (general category `Cf`, Unicode 15): zero-width spaces and joiners,
/// the bidi overrides and isolates, the soft hyphen, the BOM, the tag characters. In a name they
/// make two names that look the same, or turn "… arrived at …" around (code QA Q-8). A name may
/// hold any other visible character: letters, digits, spaces, punctuation, emoji.
fn is_format(c: char) -> bool {
    matches!(
        c as u32,
        0x00AD
            | 0x0600..=0x0605
            | 0x061C
            | 0x06DD
            | 0x070F
            | 0x0890..=0x0891
            | 0x08E2
            | 0x180E
            | 0x200B..=0x200F
            | 0x202A..=0x202E
            | 0x2060..=0x2064
            | 0x2066..=0x206F
            | 0xFEFF
            | 0xFFF9..=0xFFFB
            | 0x110BD
            | 0x110CD
            | 0x13430..=0x1343F
            | 0x1BCA0..=0x1BCA3
            | 0x1D173..=0x1D17A
            | 0xE0001
            | 0xE0020..=0xE007F
    )
}

/// The form read: the values to show again, and the input or each field's refusal. The name is
/// checked only when `check_name` (a new zone, or a name that differs from the one the page was
/// drawn with - design 135 decision 5); its uniqueness and the caps are checked in the save's
/// transaction.
fn parse(
    form: &HashMap<String, String>,
    check_name: bool,
) -> (ZoneForm, Result<Input, ZoneErrors>) {
    let get = |key: &str| form.get(key).map(String::as_str).unwrap_or("");
    let typed = ZoneForm {
        name: get("name").trim().to_string(),
        latitude: get("latitude").trim().to_string(),
        longitude: get("longitude").trim().to_string(),
        radius: get("radius_m").trim().to_string(),
        was_name: get("was_name").trim().to_string(),
        was_latitude: get("was_latitude").trim().to_string(),
        was_longitude: get("was_longitude").trim().to_string(),
        was_radius: get("was_radius_m").trim().to_string(),
    };
    let mut errors = ZoneErrors::default();
    let name = typed.name.clone();
    if check_name {
        if name.is_empty() {
            errors.name = Some("locate.zone.error.name_empty");
        } else if name.chars().count() > NAME_MAX {
            errors.name = Some("locate.zone.error.name_long");
        } else if name.chars().any(|c| c.is_control() || is_format(c)) {
            errors.name = Some("locate.zone.error.name_invisible");
        }
    }
    let latitude = number(&typed.latitude).filter(|v| v.abs() <= 90.0);
    if latitude.is_none() {
        errors.latitude = Some("locate.zone.error.latitude");
    }
    let longitude = number(&typed.longitude).filter(|v| v.abs() <= 180.0);
    if longitude.is_none() {
        errors.longitude = Some("locate.zone.error.longitude");
    }
    let radius = typed
        .radius
        .parse::<i64>()
        .ok()
        .filter(|r| RADIUS.contains(r));
    if radius.is_none() {
        errors.radius = Some("locate.zone.error.radius");
    }
    let parsed = match (latitude, longitude, radius) {
        (Some(latitude), Some(longitude), Some(radius_m)) if !errors.any() => Ok(Input {
            name,
            latitude: round6(latitude),
            longitude: round6(longitude),
            radius_m,
        }),
        _ => Err(errors),
    };
    (typed, parsed)
}

/// What the editor's save writes (decision 12): only the fields that differ from the values the page
/// was drawn with. The centre (latitude and longitude) is one field.
#[derive(Clone, Debug, Default, PartialEq)]
struct Diff {
    name: Option<String>,
    centre: Option<(f64, f64)>,
    radius_m: Option<i64>,
}

impl Diff {
    fn of(input: &Input, typed: &ZoneForm) -> Diff {
        let was_number = |raw: &str| number(raw).map(round6);
        let centre_was = was_number(&typed.was_latitude).zip(was_number(&typed.was_longitude));
        Diff {
            name: (input.name != typed.was_name).then(|| input.name.clone()),
            centre: (centre_was != Some((input.latitude, input.longitude)))
                .then_some((input.latitude, input.longitude)),
            radius_m: (typed.was_radius.parse::<i64>().ok() != Some(input.radius_m))
                .then_some(input.radius_m),
        }
    }
}

/// What a save changed, as the log's sentences (`security.detail.zone.*`, design 70 §4) - never a
/// coordinate; empty when nothing did.
fn changes(
    before: &FamilyZone,
    after: &FamilyZone,
) -> Vec<(&'static str, Vec<(&'static str, String)>)> {
    let mut words = Vec::new();
    if before.name != after.name {
        words.push((
            "security.detail.zone.renamed",
            vec![("name", before.name.clone())],
        ));
    }
    if before.latitude != after.latitude || before.longitude != after.longitude {
        words.push(("security.detail.zone.moved", vec![]));
    }
    if before.radius_m != after.radius_m {
        words.push((
            "security.detail.zone.radius",
            vec![
                ("from", before.radius_m.to_string()),
                ("to", after.radius_m.to_string()),
            ],
        ));
    }
    words
}

/// The same name, ignoring case.
fn same_name(a: &str, b: &str) -> bool {
    a.to_lowercase() == b.to_lowercase()
}

/// Phone names as one phrase: "Telefon 1", "Telefon 1 and Telefon 2", "A, B and C".
fn phone_list(lang: Lang, names: &[String]) -> String {
    match names {
        [] => String::new(),
        [one] => one.clone(),
        [rest @ .., last] => t_params(
            lang,
            "locate.zone.phones_and",
            &[("list", &rest.join(", ")), ("last", last)],
        ),
    }
}

/// The family's phones, oldest first.
async fn phones(state: &AppState) -> Result<Vec<Device>, sqlx::Error> {
    sqlx::query_as::<_, Device>("SELECT * FROM devices ORDER BY id")
        .fetch_all(&state.db)
        .await
}

/// A phone's newest fix: where a new zone starts and "Use {phone}'s location" goes.
async fn newest_point(state: &AppState, id: i64) -> Result<Option<(f64, f64)>, sqlx::Error> {
    sqlx::query_as(
        "SELECT latitude, longitude FROM device_locations WHERE device_id = ? \
         ORDER BY captured_at DESC, id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await
}

/// The family's newest fix of any phone.
async fn newest_any(state: &AppState) -> Result<Option<(f64, f64)>, sqlx::Error> {
    sqlx::query_as(
        "SELECT latitude, longitude FROM device_locations ORDER BY captured_at DESC, id DESC LIMIT 1",
    )
    .fetch_optional(&state.db)
    .await
}

async fn device_of(state: &AppState, id: i64) -> Result<Option<Device>, sqlx::Error> {
    sqlx::query_as::<_, Device>("SELECT * FROM devices WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
}

/// `device` from a query or a form, kept only when it names a real phone (design 135 §8); anything
/// else is ignored.
async fn known_device(state: &AppState, raw: Option<&String>) -> Result<Option<i64>, sqlx::Error> {
    let Some(id) = raw.and_then(|r| r.trim().parse::<i64>().ok()) else {
        return Ok(None);
    };
    Ok(device_of(state, id).await?.map(|d| d.id))
}

/// A 404 worded by `key` (`error.device_not_found`, `error.zone_not_found`).
fn not_found(lang: Lang, key: &str) -> Response {
    crate::i18n::error(lang, StatusCode::NOT_FOUND, key)
}

fn db_error(err: sqlx::Error, lang: Lang) -> Response {
    tracing::error!(%err, "zones: database error");
    crate::i18n::error(lang, StatusCode::INTERNAL_SERVER_ERROR, "error.zone_save")
}

/// The list a page from `device` returns to: the phone's Zones page, or the family's.
fn list_href(device: Option<i64>) -> String {
    match device {
        Some(id) => format!("/devices/{id}/zones"),
        None => "/zones".to_string(),
    }
}

/// What the editor shows besides the form.
struct EditorParts {
    zone_id: Option<i64>,
    stored_name: String,
    device: Option<i64>,
    phones: Vec<PhoneTick>,
    phones_error: Option<String>,
    refusal: Option<String>,
    /// Design 138: the Wi-Fi group's flash (`?wifi=`).
    wifi_flash: Option<(&'static str, bool)>,
}

/// The editor, with `status` (200, or 400 for a refusal).
async fn render(
    state: &AppState,
    parts: EditorParts,
    form: ZoneForm,
    errors: ZoneErrors,
    status: StatusCode,
    lang: Lang,
) -> Response {
    let all = match phones(state).await {
        Ok(all) => all,
        Err(err) => return db_error(err, lang),
    };
    // The `device` phone first, then the others.
    let mut order: Vec<&Device> = all.iter().filter(|d| Some(d.id) == parts.device).collect();
    order.extend(all.iter().filter(|d| Some(d.id) != parts.device));
    let mut fixes = Vec::new();
    for phone in order {
        match newest_point(state, phone.id).await {
            Ok(Some((lat, lng))) => fixes.push(PhoneFix {
                name: phone.name.clone(),
                latitude: coordinate(lat),
                longitude: coordinate(lng),
            }),
            Ok(None) => {}
            Err(err) => return db_error(err, lang),
        }
    }
    let start = match parts.device {
        Some(id) => match newest_point(state, id).await {
            Ok(fix) => fix,
            Err(err) => return db_error(err, lang),
        },
        None => None,
    };
    let start = match start {
        Some(fix) => Some(fix),
        None => match newest_any(state).await {
            Ok(fix) => fix,
            Err(err) => return db_error(err, lang),
        },
    }
    .map(|(lat, lng)| (coordinate(lat), coordinate(lng)));
    let users: Vec<String> = parts
        .phones
        .iter()
        .filter(|p| p.was)
        .map(|p| p.name.clone())
        .collect();
    let delete_question = if users.is_empty() {
        t_params(
            lang,
            "locate.zone.delete_question",
            &[("name", &parts.stored_name)],
        )
    } else {
        t_params(
            lang,
            "locate.zone.delete_question_used",
            &[
                ("name", &parts.stored_name),
                ("phones", &phone_list(lang, &users)),
            ],
        )
    };
    let title = if parts.zone_id.is_some() {
        parts.stored_name.clone()
    } else {
        t(lang, "locate.zone.new").to_string()
    };
    // Design 138: the stored zone's Wi-Fi group.
    let wifi = match parts.zone_id {
        Some(zid) => match zones::family_zone(&state.db, zid).await {
            Ok(Some(zone)) => match crate::zone_networks::view(&state.db, &zone).await {
                Ok(view) => view,
                Err(err) => return db_error(err, lang),
            },
            Ok(None) => crate::zone_networks::WifiView::default(),
            Err(err) => return db_error(err, lang),
        },
        None => crate::zone_networks::WifiView::default(),
    };
    let page = ZoneTemplate {
        lang,
        title,
        zone_id: parts.zone_id,
        device: parts.device,
        back_href: list_href(parts.device),
        back_label: t(lang, "locate.zones.title").to_string(),
        form,
        errors,
        refusal: parts.refusal,
        phones: parts.phones,
        phones_error: parts.phones_error,
        fixes,
        start,
        delete_question,
        radius_min: *RADIUS.start(),
        radius_max: *RADIUS.end(),
        radius_step: zones::RADIUS_STEP,
        wifi,
        wifi_flash: parts.wifi_flash,
    };
    (status, Html(page.render().unwrap())).into_response()
}

/// The phones' ticks: `linked` as drawn; `typed` (a refusal) as the form posted them.
fn ticks(
    all: &[Device],
    linked: &HashSet<i64>,
    typed: Option<&HashMap<String, String>>,
) -> Vec<PhoneTick> {
    all.iter()
        .map(|d| {
            let was = match typed {
                Some(form) => form
                    .get(&format!("was_phone_{}", d.id))
                    .map_or(linked.contains(&d.id), |v| v == "1"),
                None => linked.contains(&d.id),
            };
            let ticked = match typed {
                Some(form) => form
                    .get(&format!("phone_{}", d.id))
                    .is_some_and(|v| v == "on"),
                None => was,
            };
            PhoneTick {
                id: d.id,
                name: d.name.clone(),
                ticked,
                was,
            }
        })
        .collect()
}

/// The phones linked to the family zone `zone_id`.
async fn linked_phones(state: &AppState, zone_id: i64) -> Result<HashSet<i64>, sqlx::Error> {
    let ids: Vec<i64> = sqlx::query_scalar("SELECT device_id FROM device_zones WHERE zone_id = ?")
        .bind(zone_id)
        .fetch_all(&state.db)
        .await?;
    Ok(ids.into_iter().collect())
}

/// A family list row: "Hjem" over "150 m · Telefon 1, Telefon 2", and after its save the line.
struct FamilyRow {
    id: i64,
    name: String,
    sub: String,
    flash: Option<&'static str>,
}

#[derive(Template)]
#[template(path = "family_zones.html")]
struct FamilyZonesTemplate {
    lang: Lang,
    title: String,
    back_href: String,
    back_label: String,
    /// `?device=N` kept on the links, so the editor returns to that phone.
    device: Option<i64>,
    rows: Vec<FamilyRow>,
    /// After a delete (`?zone=deleted`): the group's line.
    flash: Option<&'static str>,
    /// At [MAX_FAMILY_ZONES]: "Add Zone" becomes this note.
    full: Option<String>,
}
crate::i18n::words!(FamilyZonesTemplate);

/// `GET /zones`: the family's zones (design 135 §5.2), oldest first. `?device=N` sets Back to that
/// phone's Zones page; `?zone=added|saved&zone_id=N` puts the line under the row, `?zone=deleted`
/// under the group.
pub async fn list_page(
    State(state): State<AppState>,
    lang: Lang,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    let device = match known_device(&state, params.get("device")).await {
        Ok(device) => device,
        Err(err) => return db_error(err, lang),
    };
    let flash = params.get("zone").map(String::as_str);
    let flash_zone = params.get("zone_id").and_then(|s| s.parse::<i64>().ok());
    let read = async {
        let family = zones::family(&state.db).await?;
        let users: Vec<(i64, String)> = sqlx::query_as(
            "SELECT d.zone_id, v.name FROM device_zones d JOIN devices v ON v.id = d.device_id \
             ORDER BY v.id",
        )
        .fetch_all(&state.db)
        .await?;
        Ok::<_, sqlx::Error>((family, users))
    };
    let (family, users) = match read.await {
        Ok(read) => read,
        Err(err) => return db_error(err, lang),
    };
    let row_flash = match flash {
        Some("added") => Some(t(lang, "locate.zones.added")),
        Some("saved") => Some(t(lang, "common.saved")),
        _ => None,
    };
    let rows: Vec<FamilyRow> = family
        .iter()
        .map(|z| {
            let names: Vec<&str> = users
                .iter()
                .filter(|(zone, _)| *zone == z.id)
                .map(|(_, name)| name.as_str())
                .collect();
            let phones = if names.is_empty() {
                t(lang, "locate.zones.no_phone").to_string()
            } else {
                names.join(", ")
            };
            FamilyRow {
                id: z.id,
                name: z.name.clone(),
                sub: t_params(
                    lang,
                    "locate.zones.family_sub",
                    &[("radius", &z.radius_m.to_string()), ("phones", &phones)],
                ),
                flash: row_flash.filter(|_| flash_zone == Some(z.id)),
            }
        })
        .collect();
    let (back_href, back_label) = match device {
        Some(id) => (
            format!("/devices/{id}/zones"),
            t(lang, "locate.zones.title"),
        ),
        None => ("/devices/locate".to_string(), t(lang, "nav.locate")),
    };
    Html(
        FamilyZonesTemplate {
            lang,
            title: t(lang, "locate.zones.title").to_string(),
            back_href,
            back_label: back_label.to_string(),
            device,
            full: (rows.len() as i64 >= MAX_FAMILY_ZONES).then(|| {
                t_params(
                    lang,
                    "locate.zones.full",
                    &[("max", &MAX_FAMILY_ZONES.to_string())],
                )
            }),
            rows,
            flash: (flash == Some("deleted")).then(|| t(lang, "locate.zones.deleted")),
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

/// `GET /zones/new`: the form at the `device` phone's newest fix (else any phone's), the default
/// radius; `?device=N` ticks that phone and is where the save lands.
pub async fn new_zone(
    State(state): State<AppState>,
    lang: Lang,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    let device = match known_device(&state, params.get("device")).await {
        Ok(device) => device,
        Err(err) => return db_error(err, lang),
    };
    let all = match phones(&state).await {
        Ok(all) => all,
        Err(err) => return db_error(err, lang),
    };
    let start = match device {
        Some(id) => newest_point(&state, id).await,
        None => Ok(None),
    };
    let start = match start {
        Ok(Some(fix)) => Some(fix),
        Ok(None) => match newest_any(&state).await {
            Ok(fix) => fix,
            Err(err) => return db_error(err, lang),
        },
        Err(err) => return db_error(err, lang),
    };
    let (latitude, longitude) = match start {
        Some((lat, lng)) => (coordinate(lat), coordinate(lng)),
        None => (String::new(), String::new()),
    };
    let mut phones = ticks(&all, &HashSet::new(), None);
    for p in phones.iter_mut() {
        p.ticked = Some(p.id) == device;
    }
    let form = ZoneForm {
        name: String::new(),
        latitude,
        longitude,
        radius: zones::DEFAULT_RADIUS.to_string(),
        was_name: String::new(),
        was_latitude: String::new(),
        was_longitude: String::new(),
        was_radius: String::new(),
    };
    let parts = EditorParts {
        zone_id: None,
        stored_name: String::new(),
        device,
        phones,
        phones_error: None,
        refusal: None,
        wifi_flash: None,
    };
    render(
        &state,
        parts,
        form,
        ZoneErrors::default(),
        StatusCode::OK,
        lang,
    )
    .await
}

/// `GET /zones/{zone_id}`: the zone's form as stored, with the phones that use it ticked;
/// `?wifi=saved|changed|full` the Wi-Fi group's flash after a confirm or a remove (design 138).
pub async fn show_zone(
    State(state): State<AppState>,
    lang: Lang,
    Path(zone_id): Path<i64>,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    let wifi_flash = match params.get("wifi").map(String::as_str) {
        Some("saved") => Some(("common.saved", false)),
        Some("changed") => Some(("locate.zone.wifi.changed", true)),
        Some("full") => Some(("locate.zone.wifi.full", true)),
        _ => None,
    };
    let device = match known_device(&state, params.get("device")).await {
        Ok(device) => device,
        Err(err) => return db_error(err, lang),
    };
    let zone = match zones::family_zone(&state.db, zone_id).await {
        Ok(Some(zone)) => zone,
        Ok(None) => return not_found(lang, "error.zone_not_found"),
        Err(err) => return db_error(err, lang),
    };
    let read = async {
        let all = phones(&state).await?;
        let linked = linked_phones(&state, zone_id).await?;
        Ok::<_, sqlx::Error>(ticks(&all, &linked, None))
    };
    let phones = match read.await {
        Ok(phones) => phones,
        Err(err) => return db_error(err, lang),
    };
    let form = ZoneForm {
        name: zone.name.clone(),
        latitude: coordinate(zone.latitude),
        longitude: coordinate(zone.longitude),
        radius: zone.radius_m.to_string(),
        was_name: zone.name.clone(),
        was_latitude: coordinate(zone.latitude),
        was_longitude: coordinate(zone.longitude),
        was_radius: zone.radius_m.to_string(),
    };
    let parts = EditorParts {
        zone_id: Some(zone.id),
        stored_name: zone.name,
        device,
        phones,
        phones_error: None,
        refusal: None,
        wifi_flash,
    };
    render(
        &state,
        parts,
        form,
        ZoneErrors::default(),
        StatusCode::OK,
        lang,
    )
    .await
}

/// What a save's transaction found: written, or a refusal to show (nothing written).
enum Outcome {
    /// The zone's id, the phones whose policy changed, and the log's lines to write.
    Written(i64, Vec<i64>, Vec<LogLine>),
    NameTaken,
    FamilyFull,
    /// A phone (its name) at [MAX_ZONES].
    PhoneFull(String),
    Gone,
}

/// A security line to write after the commit.
struct LogLine {
    event: &'static str,
    detail: security::Detail,
}

/// A family line (`zone_added`, `zone_changed`, `zone_deleted`): the zone's words, never coordinates (no phone, so
/// no facts: the log shows facts beside the words unless they name a phone).
fn family_line(event: &'static str, key: &str, params: &[(&str, &str)]) -> LogLine {
    LogLine {
        event,
        detail: security::Detail::words(key, params),
    }
}

/// A phone's line (`zone_linked`, `zone_unlinked`): "phone 3 (Telefon 1): Hjem".
fn link_line(event: &'static str, device: &Device, zone: &str) -> LogLine {
    LogLine {
        event,
        detail: security::Detail::words(
            "security.detail.zone.link",
            &[
                ("device", &device.id.to_string()),
                ("name", &device.name),
                ("zone", zone),
            ],
        )
        .with_facts(format!("device {}", device.id)),
    }
}

/// The lines written, the phones nudged, the answer.
async fn written(
    state: &AppState,
    admin: &AdminUser,
    nudge: &[i64],
    lines: Vec<LogLine>,
    back: String,
) -> Response {
    for line in lines {
        security::record_security_event(
            &state.db,
            line.event,
            Some(&admin.username),
            None,
            Some(line.detail),
        )
        .await;
    }
    for id in nudge {
        let _ = state.command_notify.send(*id);
    }
    Redirect::to(&back).into_response()
}

/// The phone has [MAX_ZONES] links already: one more is refused.
async fn over_cap(conn: &mut sqlx::SqliteConnection, phone: &Device) -> Result<bool, sqlx::Error> {
    let links: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_zones WHERE device_id = ?")
        .bind(phone.id)
        .fetch_one(&mut *conn)
        .await?;
    Ok(links >= MAX_ZONES)
}

/// `POST /zones`: a new family zone, linked to the ticked phones (each link's side unknown and its
/// cursor at its phone's newest fix: the history is never judged against it). Refused (400,
/// nothing written): a field, a name the family has, a 51st zone, a ticked phone at its cap.
pub async fn create_zone(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let device = match known_device(&state, form.get("device")).await {
        Ok(device) => device,
        Err(err) => return db_error(err, lang),
    };
    let all = match phones(&state).await {
        Ok(all) => all,
        Err(err) => return db_error(err, lang),
    };
    let (typed, parsed) = parse(&form, true);
    let phones = ticks(&all, &HashSet::new(), Some(&form));
    let input = match parsed {
        Ok(input) => input,
        Err(errors) => {
            let parts = EditorParts {
                zone_id: None,
                stored_name: String::new(),
                device,
                phones,
                phones_error: None,
                refusal: None,
                wifi_flash: None,
            };
            return render(&state, parts, typed, errors, StatusCode::BAD_REQUEST, lang).await;
        }
    };
    let ticked: Vec<&Device> = all
        .iter()
        .filter(|d| phones.iter().any(|p| p.id == d.id && p.ticked))
        .collect();
    let outcome = async {
        // IMMEDIATE: the counts, the name check and the inserts under one write lock, so two saves
        // can't make a 51st zone, an 11th link or two zones of one name.
        let mut tx = state.db.begin_with("BEGIN IMMEDIATE").await?;
        let family = zones::family(&mut *tx).await?;
        if family.len() as i64 >= MAX_FAMILY_ZONES {
            return Ok(Outcome::FamilyFull);
        }
        if family.iter().any(|z| same_name(&z.name, &input.name)) {
            return Ok(Outcome::NameTaken);
        }
        for phone in &ticked {
            if over_cap(&mut tx, phone).await? {
                return Ok(Outcome::PhoneFull(phone.name.clone()));
            }
        }
        let zone_id: i64 = sqlx::query_scalar(
            "INSERT INTO family_zones (name, latitude, longitude, radius_m, created_by) \
             VALUES (?, ?, ?, ?, ?) RETURNING id",
        )
        .bind(&input.name)
        .bind(input.latitude)
        .bind(input.longitude)
        .bind(input.radius_m)
        .bind(admin.id)
        .fetch_one(&mut *tx)
        .await?;
        let zone = FamilyZone {
            id: zone_id,
            name: input.name.clone(),
            latitude: input.latitude,
            longitude: input.longitude,
            radius_m: input.radius_m,
        };
        let radius = input.radius_m.to_string();
        let mut lines = vec![family_line(
            "zone_added",
            "security.detail.zone.family_added",
            &[("zone", &input.name), ("radius", &radius)],
        )];
        let now = chrono::Utc::now();
        let mut nudge = Vec::new();
        for phone in &ticked {
            zones::link(&mut tx, phone.id, &zone, Some(admin.id), now).await?;
            lines.push(link_line("zone_linked", phone, &input.name));
            nudge.push(phone.id);
        }
        tx.commit().await?;
        Ok::<_, sqlx::Error>(Outcome::Written(zone_id, nudge, lines))
    };
    let outcome = match outcome.await {
        Ok(outcome) => outcome,
        Err(err) => return db_error(err, lang),
    };
    refused_or_written(
        &state,
        &admin,
        outcome,
        None,
        String::new(),
        device,
        typed,
        phones,
        "added",
        lang,
    )
    .await
}

/// A save's answer: the landing (`?zone=<line>&zone_id=Z#zone-Z` on the page it came from; a save
/// that changed nothing lands on the row with no line), or the refusal re-rendered at `#zone-form`.
#[allow(clippy::too_many_arguments)]
async fn refused_or_written(
    state: &AppState,
    admin: &AdminUser,
    outcome: Outcome,
    zone_id: Option<i64>,
    stored_name: String,
    device: Option<i64>,
    typed: ZoneForm,
    phones: Vec<PhoneTick>,
    line: &str,
    lang: Lang,
) -> Response {
    let mut parts = EditorParts {
        zone_id,
        stored_name,
        device,
        phones,
        phones_error: None,
        refusal: None,
        wifi_flash: None,
    };
    let mut errors = ZoneErrors::default();
    match outcome {
        Outcome::Written(zone, nudge, lines) => {
            let list = list_href(device);
            let back = if lines.is_empty() {
                format!("{list}#zone-{zone}")
            } else {
                format!("{list}?zone={line}&zone_id={zone}#zone-{zone}")
            };
            return written(state, admin, &nudge, lines, back).await;
        }
        Outcome::Gone => return not_found(lang, "error.zone_not_found"),
        Outcome::NameTaken => errors.name = Some("locate.zone.error.name_taken"),
        Outcome::FamilyFull => {
            parts.refusal = Some(t_params(
                lang,
                "locate.zones.full",
                &[("max", &MAX_FAMILY_ZONES.to_string())],
            ));
        }
        Outcome::PhoneFull(phone) => {
            parts.phones_error = Some(t_params(
                lang,
                "locate.zone.error.full",
                &[("phone", &phone), ("max", &MAX_ZONES.to_string())],
            ));
        }
    }
    render(state, parts, typed, errors, StatusCode::BAD_REQUEST, lang).await
}

/// `POST /zones/{zone_id}`: the editor saved (decision 12) - each field written only when it differs
/// from the value the page was drawn with (`was_*`; the centre is one field), each phone linked or
/// unlinked only when its tick differs from `was_phone_<id>`. A moved centre or a changed radius
/// starts every link over silently; a new link starts over; a rename keeps every side. The name is
/// checked only when it changed. Refused (400, nothing written): a field, a changed name the family
/// has, a phone at its cap. An unknown zone is a 404.
pub async fn save_zone(
    State(state): State<AppState>,
    lang: Lang,
    Path(zone_id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let device = match known_device(&state, form.get("device")).await {
        Ok(device) => device,
        Err(err) => return db_error(err, lang),
    };
    let stored = match zones::family_zone(&state.db, zone_id).await {
        Ok(Some(zone)) => zone,
        Ok(None) => return not_found(lang, "error.zone_not_found"),
        Err(err) => return db_error(err, lang),
    };
    let all = match phones(&state).await {
        Ok(all) => all,
        Err(err) => return db_error(err, lang),
    };
    let name_changed = form
        .get("name")
        .map(|n| n.trim())
        .is_some_and(|n| n != form.get("was_name").map_or("", |w| w.trim()));
    let (typed, parsed) = parse(&form, name_changed);
    let phones = match linked_phones(&state, zone_id).await {
        Ok(linked) => ticks(&all, &linked, Some(&form)),
        Err(err) => return db_error(err, lang),
    };
    let input = match parsed {
        Ok(input) => input,
        Err(errors) => {
            let parts = EditorParts {
                zone_id: Some(zone_id),
                stored_name: stored.name,
                device,
                phones,
                phones_error: None,
                refusal: None,
                wifi_flash: None,
            };
            return render(&state, parts, typed, errors, StatusCode::BAD_REQUEST, lang).await;
        }
    };
    let diff = Diff::of(&input, &typed);
    // Only the phones the page drew (`was_phone_<id>` present) whose tick changed.
    let wanted: Vec<(&Device, bool)> = all
        .iter()
        .filter_map(|d| {
            let was = form.get(&format!("was_phone_{}", d.id))? == "1";
            let now = form
                .get(&format!("phone_{}", d.id))
                .is_some_and(|v| v == "on");
            (was != now).then_some((d, now))
        })
        .collect();
    let outcome = async {
        let mut tx = state.db.begin_with("BEGIN IMMEDIATE").await?;
        let Some(before) = zones::family_zone(&mut *tx, zone_id).await? else {
            return Ok(Outcome::Gone);
        };
        let mut after = before.clone();
        if let Some(name) = &diff.name {
            let family = zones::family(&mut *tx).await?;
            if family
                .iter()
                .any(|z| z.id != zone_id && same_name(&z.name, name))
            {
                return Ok(Outcome::NameTaken);
            }
            after.name = name.clone();
        }
        if let Some((lat, lng)) = diff.centre {
            after.latitude = lat;
            after.longitude = lng;
        }
        if let Some(radius_m) = diff.radius_m {
            after.radius_m = radius_m;
        }
        let linked: HashSet<i64> = zones::links_of(&mut *tx, zone_id)
            .await?
            .iter()
            .map(|l| l.device_id)
            .collect();
        let now = chrono::Utc::now();
        // Links to make (still unlinked now) and to drop (still linked now); a phone linked or
        // unlinked meanwhile is left as it is.
        let to_link: Vec<&Device> = wanted
            .iter()
            .filter(|(d, on)| *on && !linked.contains(&d.id))
            .map(|(d, _)| *d)
            .collect();
        let to_unlink: Vec<&Device> = wanted
            .iter()
            .filter(|(d, on)| !*on && linked.contains(&d.id))
            .map(|(d, _)| *d)
            .collect();
        for phone in &to_link {
            if over_cap(&mut tx, phone).await? {
                return Ok(Outcome::PhoneFull(phone.name.clone()));
            }
        }
        let mut lines = Vec::new();
        let mut nudge: Vec<i64> = Vec::new();
        if after != before {
            sqlx::query(
                "UPDATE family_zones SET name = ?, latitude = ?, longitude = ?, radius_m = ?, \
                 updated_at = datetime('now') WHERE id = ?",
            )
            .bind(&after.name)
            .bind(after.latitude)
            .bind(after.longitude)
            .bind(after.radius_m)
            .bind(zone_id)
            .execute(&mut *tx)
            .await?;
            if after.circle() != before.circle() {
                // Decision 4: every phone using the zone starts over silently, and is nudged.
                for link in zones::links_of(&mut *tx, zone_id).await? {
                    zones::start_over(&mut tx, link.id, link.device_id, &after.circle(), now)
                        .await?;
                    nudge.push(link.device_id);
                }
            }
            let mut detail = security::Detail::words(
                "security.detail.zone.family_changed",
                &[("zone", &after.name)],
            );
            for (key, params) in changes(&before, &after) {
                let params: Vec<(&str, &str)> =
                    params.iter().map(|(n, v)| (*n, v.as_str())).collect();
                detail = detail.and(key, &params);
            }
            // Design 138 D12: moved further than its radius, the zone's confirmed routers go (from
            // every link).
            if crate::zone_networks::moved_past_radius(&before.circle(), &after.circle()) {
                let cleared = sqlx::query(
                    "DELETE FROM zone_networks WHERE zone_id IN \
                     (SELECT id FROM device_zones WHERE zone_id = ?)",
                )
                .bind(zone_id)
                .execute(&mut *tx)
                .await?;
                if cleared.rows_affected() > 0 {
                    detail = detail.and("security.detail.zone.wifi_removed", &[]);
                }
            }
            lines.push(LogLine {
                event: "zone_changed",
                detail,
            });
        }
        for phone in &to_unlink {
            sqlx::query("DELETE FROM device_zones WHERE device_id = ? AND zone_id = ?")
                .bind(phone.id)
                .bind(zone_id)
                .execute(&mut *tx)
                .await?;
            lines.push(link_line("zone_unlinked", phone, &after.name));
            nudge.push(phone.id);
        }
        for phone in &to_link {
            zones::link(&mut tx, phone.id, &after, Some(admin.id), now).await?;
            lines.push(link_line("zone_linked", phone, &after.name));
            nudge.push(phone.id);
        }
        tx.commit().await?;
        nudge.sort_unstable();
        nudge.dedup();
        Ok::<_, sqlx::Error>(Outcome::Written(zone_id, nudge, lines))
    };
    let outcome = match outcome.await {
        Ok(outcome) => outcome,
        Err(err) => return db_error(err, lang),
    };
    refused_or_written(
        &state,
        &admin,
        outcome,
        Some(zone_id),
        stored.name,
        device,
        typed,
        phones,
        "saved",
        lang,
    )
    .await
}

/// Design 138: the editor's Wi-Fi group, `#zone-wifi`, with its flash and the page it came from.
fn wifi_back(zone_id: i64, device: Option<i64>, flash: &str) -> String {
    let from = device.map(|d| format!("&device={d}")).unwrap_or_default();
    format!("/zones/{zone_id}?wifi={flash}{from}#zone-wifi")
}

/// `POST /zones/{zone_id}/wifi`: design 138 D4 - confirm the router the page showed (`bssid`,
/// `mld`) for the family zone, bound to the newest report with `wifi` of the phone it showed it for
/// (`phone`, linked to the zone); written to every link of the zone. Back to `#zone-wifi`.
pub async fn confirm_wifi(
    State(state): State<AppState>,
    lang: Lang,
    Path(zone_id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let device = match known_device(&state, form.get("device")).await {
        Ok(device) => device,
        Err(err) => return db_error(err, lang),
    };
    let zone = match zones::family_zone(&state.db, zone_id).await {
        Ok(Some(zone)) => zone,
        Ok(None) => return not_found(lang, "error.zone_not_found"),
        Err(err) => return db_error(err, lang),
    };
    let phone = match form.get("phone").and_then(|p| p.trim().parse::<i64>().ok()) {
        Some(id) => match device_of(&state, id).await {
            Ok(Some(phone)) => phone,
            Ok(None) => {
                return Redirect::to(&wifi_back(zone_id, device, "changed")).into_response();
            }
            Err(err) => return db_error(err, lang),
        },
        None => return Redirect::to(&wifi_back(zone_id, device, "changed")).into_response(),
    };
    let bssid = form.get("bssid").map(String::as_str).unwrap_or("");
    let mld = form
        .get("mld")
        .map(String::as_str)
        .filter(|m| !m.is_empty());
    match crate::zone_networks::confirm(&state.db, phone.id, zone_id, bssid, mld, admin.id).await {
        Ok(crate::zone_networks::Confirmed::Written) => {
            let nudge = match linked_phones(&state, zone_id).await {
                Ok(linked) => linked.into_iter().collect::<Vec<i64>>(),
                Err(err) => return db_error(err, lang),
            };
            let line = link_line("zone_wifi_confirmed", &phone, &zone.name);
            written(
                &state,
                &admin,
                &nudge,
                vec![line],
                wifi_back(zone_id, device, "saved"),
            )
            .await
        }
        Ok(crate::zone_networks::Confirmed::Already) => {
            Redirect::to(&wifi_back(zone_id, device, "saved")).into_response()
        }
        Ok(crate::zone_networks::Confirmed::Changed) => {
            Redirect::to(&wifi_back(zone_id, device, "changed")).into_response()
        }
        Ok(crate::zone_networks::Confirmed::Full) => {
            Redirect::to(&wifi_back(zone_id, device, "full")).into_response()
        }
        Err(err) => db_error(err, lang),
    }
}

/// `POST /zones/{zone_id}/wifi/{network_id}/delete`: design 138 D2 - every row of that row's name
/// goes, on every link of the family zone ("Fjern" ends only the parent's confirmation). Back to
/// `#zone-wifi`.
pub async fn remove_wifi(
    State(state): State<AppState>,
    lang: Lang,
    Path((zone_id, network_id)): Path<(i64, i64)>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let device = match known_device(&state, form.get("device")).await {
        Ok(device) => device,
        Err(err) => return db_error(err, lang),
    };
    let zone = match zones::family_zone(&state.db, zone_id).await {
        Ok(Some(zone)) => zone,
        Ok(None) => return not_found(lang, "error.zone_not_found"),
        Err(err) => return db_error(err, lang),
    };
    let nudge = match linked_phones(&state, zone_id).await {
        Ok(linked) => linked.into_iter().collect::<Vec<i64>>(),
        Err(err) => return db_error(err, lang),
    };
    match crate::zone_networks::remove(&state.db, zone_id, network_id).await {
        Ok(true) => {
            let line = family_line(
                "zone_wifi_removed",
                "security.detail.zone.family_changed",
                &[("zone", &zone.name)],
            );
            written(
                &state,
                &admin,
                &nudge,
                vec![line],
                wifi_back(zone_id, device, "saved"),
            )
            .await
        }
        // QA L9: gone already (another parent, a block replaced): back to the group, never a 404 page.
        Ok(false) => Redirect::to(&wifi_back(zone_id, device, "saved")).into_response(),
        Err(err) => db_error(err, lang),
    }
}

/// `POST /zones/{zone_id}/delete`: the zone goes, with its links, their alerts, and any weather it
/// stood in for (decisions 9, 10; the foreign keys); its past events stay in the inbox until the
/// location history's retention takes them. The phones that used it are nudged. Back to `#zones`
/// of the page it came from (`device`).
pub async fn delete_zone(
    State(state): State<AppState>,
    lang: Lang,
    Path(zone_id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let device = match known_device(&state, form.get("device")).await {
        Ok(device) => device,
        Err(err) => return db_error(err, lang),
    };
    let deleted = async {
        let mut tx = state.db.begin_with("BEGIN IMMEDIATE").await?;
        let users: Vec<(i64, String)> = sqlx::query_as(
            "SELECT v.id, v.name FROM device_zones d JOIN devices v ON v.id = d.device_id \
             WHERE d.zone_id = ? ORDER BY v.id",
        )
        .bind(zone_id)
        .fetch_all(&mut *tx)
        .await?;
        let name: Option<String> =
            sqlx::query_scalar("DELETE FROM family_zones WHERE id = ? RETURNING name")
                .bind(zone_id)
                .fetch_optional(&mut *tx)
                .await?;
        tx.commit().await?;
        Ok::<_, sqlx::Error>(name.map(|name| (name, users)))
    };
    match deleted.await {
        Ok(Some((name, users))) => {
            let names: Vec<String> = users.iter().map(|(_, n)| n.clone()).collect();
            let line = if names.is_empty() {
                family_line(
                    "zone_deleted",
                    "security.detail.zone.family_deleted",
                    &[("zone", &name)],
                )
            } else {
                family_line(
                    "zone_deleted",
                    "security.detail.zone.family_deleted_used",
                    &[("zone", &name), ("phones", &names.join(", "))],
                )
            };
            let nudge: Vec<i64> = users.iter().map(|(id, _)| *id).collect();
            let back = format!("{}?zone=deleted#zones", list_href(device));
            written(&state, &admin, &nudge, vec![line], back).await
        }
        Ok(None) => not_found(lang, "error.zone_not_found"),
        Err(err) => db_error(err, lang),
    }
}

/// A form's fields in order, repeated names kept (`zone=`, `alerts=`).
fn fields(body: &[u8]) -> Vec<(String, String)> {
    form_urlencoded::parse(body).into_owned().collect()
}

/// `POST /devices/{id}/zones`: the phone's Zones page, one auto-saving form (design 135 §5.4,
/// decision 12) - in one `BEGIN IMMEDIATE`:
/// - **links**: each `zone=<id>` whose `use_<id>` differs from `was_use_<id>` is linked when it
///   isn't now, or unlinked when it is (a zone deleted meanwhile is skipped; a zone the form doesn't
///   name is never touched); a new link starts over;
/// - **alerts**: each `alerts=<link>` that is still this phone's link now has the signed-in parent's
///   arrive or leave written only where it differs from its `was_` value;
/// - **the cap**: a link that would make an 11th refuses the whole post - a 303 to the zone's cell with
///   `?refused=full`, nothing written, marked [crate::settings_log::refused].
///
/// A change of links nudges the phone (and is in the security log); a change of alerts alone doesn't
/// (the settings log's `settings_saved` line says it). Lands on the first zone that changed
/// (`?zone=saved&zone_id=Z#zone-Z`); when nothing did (a stale page whose change was made already),
/// on the first zone the post named that still exists, with no line (code QA L5), else on `#zones`.
pub async fn save_phone(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    body: Bytes,
) -> Response {
    let device = match device_of(&state, id).await {
        Ok(Some(device)) => device,
        Ok(None) => return not_found(lang, "error.device_not_found"),
        Err(err) => return db_error(err, lang),
    };
    let fields = fields(&body);
    let get = |key: &str| {
        fields
            .iter()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v.as_str())
    };
    let ids = |name: &str| {
        let mut seen = Vec::new();
        for (k, v) in &fields {
            if k == name
                && let Ok(n) = v.trim().parse::<i64>()
                && !seen.contains(&n)
            {
                seen.push(n);
            }
        }
        seen
    };
    // (zone id, wanted linked) for each cell whose switch changed.
    let switched: Vec<(i64, bool)> = ids("zone")
        .into_iter()
        .filter_map(|z| {
            let was = get(&format!("was_use_{z}"))? == "1";
            let now = get(&format!("use_{z}")) == Some("on");
            (was != now).then_some((z, now))
        })
        .collect();
    // (link id, Some(arrive), Some(leave)) where they changed.
    let alerts: Vec<(i64, Option<bool>, Option<bool>)> = ids("alerts")
        .into_iter()
        .filter_map(|l| {
            let changed = |what: &str| {
                let was = get(&format!("was_{what}_{l}"))? == "1";
                let now = get(&format!("{what}_{l}")) == Some("on");
                (was != now).then_some(now)
            };
            let (arrive, leave) = (changed("arrive"), changed("leave"));
            (arrive.is_some() || leave.is_some()).then_some((l, arrive, leave))
        })
        .collect();
    let named = ids("zone");
    enum Phone {
        Done {
            first: Option<i64>,
            /// Nothing changed: the first zone the post named that still exists.
            named: Option<i64>,
            linked_changed: bool,
            lines: Vec<LogLine>,
        },
        Full(i64),
    }
    let outcome = async {
        let mut tx = state.db.begin_with("BEGIN IMMEDIATE").await?;
        let now = chrono::Utc::now();
        let mut first: Option<i64> = None;
        let mut lines = Vec::new();
        let mut links: i64 =
            sqlx::query_scalar("SELECT COUNT(*) FROM device_zones WHERE device_id = ?")
                .bind(id)
                .fetch_one(&mut *tx)
                .await?;
        for (zone_id, on) in &switched {
            let Some(zone) = zones::family_zone(&mut *tx, *zone_id).await? else {
                continue;
            };
            let link: Option<i64> = sqlx::query_scalar(
                "SELECT id FROM device_zones WHERE device_id = ? AND zone_id = ?",
            )
            .bind(id)
            .bind(zone_id)
            .fetch_optional(&mut *tx)
            .await?;
            match (*on, link) {
                (true, None) => {
                    if links >= MAX_ZONES {
                        return Ok(Phone::Full(*zone_id));
                    }
                    zones::link(&mut tx, id, &zone, Some(admin.id), now).await?;
                    links += 1;
                    lines.push(link_line("zone_linked", &device, &zone.name));
                }
                (false, Some(link)) => {
                    sqlx::query("DELETE FROM device_zones WHERE id = ?")
                        .bind(link)
                        .execute(&mut *tx)
                        .await?;
                    links -= 1;
                    lines.push(link_line("zone_unlinked", &device, &zone.name));
                }
                _ => continue,
            }
            first.get_or_insert(*zone_id);
        }
        let linked_changed = !lines.is_empty();
        for (link, arrive, leave) in &alerts {
            // The same row as drawn, still this phone's: an unlink-and-relink meanwhile doesn't match.
            let zone_id: Option<i64> = sqlx::query_scalar(
                "SELECT zone_id FROM device_zones WHERE id = ? AND device_id = ?",
            )
            .bind(link)
            .bind(id)
            .fetch_optional(&mut *tx)
            .await?;
            let Some(zone_id) = zone_id else {
                continue;
            };
            for (column, value) in [("arrive", arrive), ("leave", leave)] {
                let Some(value) = value else {
                    continue;
                };
                // The column is compiled in, never input.
                sqlx::query(&format!(
                    "INSERT INTO zone_alerts (admin_id, device_zone_id, {column}) VALUES (?, ?, ?) \
                     ON CONFLICT (admin_id, device_zone_id) DO UPDATE SET {column} = excluded.{column}"
                ))
                .bind(admin.id)
                .bind(link)
                .bind(*value)
                .execute(&mut *tx)
                .await?;
            }
            first.get_or_insert(zone_id);
        }
        let mut named_first = None;
        if first.is_none() {
            for z in &named {
                if zones::family_zone(&mut *tx, *z).await?.is_some() {
                    named_first = Some(*z);
                    break;
                }
            }
        }
        tx.commit().await?;
        Ok::<_, sqlx::Error>(Phone::Done {
            first,
            named: named_first,
            linked_changed,
            lines,
        })
    };
    match outcome.await {
        Ok(Phone::Done {
            first,
            named,
            linked_changed,
            lines,
        }) => {
            let back = match (first, named) {
                (Some(z), _) => format!("/devices/{id}/zones?zone=saved&zone_id={z}#zone-{z}"),
                (None, Some(z)) => format!("/devices/{id}/zones#zone-{z}"),
                (None, None) => format!("/devices/{id}/zones#zones"),
            };
            let nudge: &[i64] = if linked_changed { &[id] } else { &[] };
            written(&state, &admin, nudge, lines, back).await
        }
        Ok(Phone::Full(z)) => crate::settings_log::refused(Redirect::to(&format!(
            "/devices/{id}/zones?refused=full&zone_id={z}#zone-{z}"
        ))),
        Err(err) => db_error(err, lang),
    }
}

/// A zone of `zones.json`: the circle and its name, for the Locate map (`static/locate.js` draws
/// it, the name as text).
#[derive(serde::Serialize)]
struct ZonePoint {
    id: i64,
    name: String,
    latitude: f64,
    longitude: f64,
    radius_m: i64,
}

/// `GET /devices/{id}/zones.json` (no-store: the coordinates of a child's home never sit in a
/// cached page): the phone's zones (its links, design 135; `id` the link's), oldest first; 404 for
/// an unknown phone (code QA N-8).
pub async fn zones_json(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
) -> Response {
    match device_of(&state, id).await {
        Ok(Some(_)) => {}
        Ok(None) => return not_found(lang, "error.device_not_found"),
        Err(err) => return db_error(err, lang),
    }
    match zones::list(&state.db, id).await {
        Ok(list) => Json(
            list.into_iter()
                .map(|z| ZonePoint {
                    id: z.id,
                    name: z.name,
                    latitude: z.latitude,
                    longitude: z.longitude,
                    radius_m: z.radius_m,
                })
                .collect::<Vec<_>>(),
        )
        .into_response(),
        Err(err) => db_error(err, lang),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn form(fields: &[(&str, &str)]) -> HashMap<String, String> {
        fields
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    }

    #[test]
    fn the_form_reads_or_marks_its_fields() {
        let good = form(&[
            ("name", " Home "),
            ("latitude", "59,9139"),
            ("longitude", "10.75221234"),
            ("radius_m", "150"),
        ]);
        let (_, parsed) = parse(&good, true);
        assert_eq!(
            parsed.ok(),
            Some(Input {
                name: "Home".to_string(),
                latitude: 59.9139,
                longitude: 10.752212,
                radius_m: 150,
            })
        );
        for (fields, which) in [
            (
                &[
                    ("name", ""),
                    ("latitude", "1"),
                    ("longitude", "1"),
                    ("radius_m", "150"),
                ][..],
                "name",
            ),
            (
                &[
                    ("name", &"x".repeat(41)[..]),
                    ("latitude", "1"),
                    ("longitude", "1"),
                    ("radius_m", "150"),
                ][..],
                "name",
            ),
            (
                &[
                    ("name", "a\nb"),
                    ("latitude", "1"),
                    ("longitude", "1"),
                    ("radius_m", "150"),
                ][..],
                "name",
            ),
            (
                &[
                    ("name", "a"),
                    ("latitude", "91"),
                    ("longitude", "1"),
                    ("radius_m", "150"),
                ][..],
                "latitude",
            ),
            (
                &[
                    ("name", "a"),
                    ("latitude", "NaN"),
                    ("longitude", "1"),
                    ("radius_m", "150"),
                ][..],
                "latitude",
            ),
            (
                &[
                    ("name", "a"),
                    ("latitude", "1"),
                    ("longitude", "-180.5"),
                    ("radius_m", "150"),
                ][..],
                "longitude",
            ),
            (
                &[
                    ("name", "a"),
                    ("latitude", "1"),
                    ("longitude", "1"),
                    ("radius_m", "99"),
                ][..],
                "radius",
            ),
            (
                &[
                    ("name", "a"),
                    ("latitude", "1"),
                    ("longitude", "1"),
                    ("radius_m", "2001"),
                ][..],
                "radius",
            ),
            (
                &[
                    ("name", "a"),
                    ("latitude", "1"),
                    ("longitude", "1"),
                    ("radius_m", "1e3"),
                ][..],
                "radius",
            ),
        ] {
            let (_, parsed) = parse(&form(fields), true);
            let errors = parsed.expect_err(which);
            let marked = [
                ("name", errors.name.is_some()),
                ("latitude", errors.latitude.is_some()),
                ("longitude", errors.longitude.is_some()),
                ("radius", errors.radius.is_some()),
            ];
            for (field, is) in marked {
                assert_eq!(is, field == which, "{fields:?}: {field}");
            }
        }
        // The edges are in.
        let (_, edge) = parse(
            &form(&[
                ("name", &"x".repeat(40)),
                ("latitude", "-90"),
                ("longitude", "180"),
                ("radius_m", "2000"),
            ]),
            true,
        );
        assert!(edge.is_ok());
        // Invisible characters (code QA Q-8): a zero-width space, a bidi override, a BOM, a soft
        // hyphen; punctuation and emoji are fine.
        for name in [
            "Ho\u{200B}me",
            "\u{202E}emoH",
            "\u{FEFF}Home",
            "Ho\u{00AD}me",
        ] {
            let (_, parsed) = parse(
                &form(&[
                    ("name", name),
                    ("latitude", "1"),
                    ("longitude", "1"),
                    ("radius_m", "150"),
                ]),
                true,
            );
            let errors = parsed.expect_err(name);
            assert_eq!(errors.name, Some("locate.zone.error.name_invisible"));
        }
        let (_, fine) = parse(
            &form(&[
                ("name", "Bestemor & Co. (Ålesund) 🏠"),
                ("latitude", "1"),
                ("longitude", "1"),
                ("radius_m", "150"),
            ]),
            true,
        );
        assert!(fine.is_ok());
        // Design 135 decision 5: a name kept as drawn isn't checked (a migrated name over 40).
        let (_, kept) = parse(
            &form(&[
                ("name", &"x".repeat(41)),
                ("latitude", "1"),
                ("longitude", "1"),
                ("radius_m", "150"),
            ]),
            false,
        );
        assert!(kept.is_ok());
    }

    #[test]
    fn a_save_writes_only_what_differs_from_the_page() {
        let input = Input {
            name: "Hjem".to_string(),
            latitude: 59.9139,
            longitude: 10.7522,
            radius_m: 150,
        };
        let drawn = |name: &str, lat: &str, lng: &str, radius: &str| ZoneForm {
            name: String::new(),
            latitude: String::new(),
            longitude: String::new(),
            radius: String::new(),
            was_name: name.to_string(),
            was_latitude: lat.to_string(),
            was_longitude: lng.to_string(),
            was_radius: radius.to_string(),
        };
        assert_eq!(
            Diff::of(&input, &drawn("Hjem", "59.913900", "10.752200", "150")),
            Diff::default()
        );
        assert_eq!(
            Diff::of(&input, &drawn("Hjem", "59.913900", "10.752200", "300")),
            Diff {
                radius_m: Some(150),
                ..Diff::default()
            }
        );
        // The centre is one field: one coordinate changed writes both.
        assert_eq!(
            Diff::of(&input, &drawn("Hjemme", "59.913900", "10.7", "150")),
            Diff {
                name: Some("Hjem".to_string()),
                centre: Some((59.9139, 10.7522)),
                ..Diff::default()
            }
        );
        // No `was_` (a page without them): everything is written.
        let all = Diff::of(&input, &drawn("", "", "", ""));
        assert!(all.name.is_some() && all.centre.is_some() && all.radius_m.is_some());
    }

    #[test]
    fn a_change_is_logged_without_coordinates() {
        let before = FamilyZone {
            id: 1,
            name: "Hjem".to_string(),
            latitude: 59.9139,
            longitude: 10.7522,
            radius_m: 150,
        };
        let after = FamilyZone {
            name: "Home".to_string(),
            latitude: 59.9140,
            radius_m: 250,
            ..before.clone()
        };
        // The sentences (design 70 §4), worded as the log reads them.
        let mut detail = security::Detail::facts("zone 1");
        for (key, params) in changes(&before, &after) {
            let params: Vec<(&str, &str)> = params.iter().map(|(n, v)| (*n, v.as_str())).collect();
            detail = detail.and(key, &params);
        }
        let words = security::worded_test(&detail);
        assert_eq!(words, "Renamed from Hjem. Moved. Radius 150 → 250 m.");
        assert!(!words.contains("59.9"));
        assert!(changes(&before, &before).is_empty());
    }

    #[test]
    fn phones_read_as_one_phrase() {
        let names = |n: &[&str]| n.iter().map(|s| s.to_string()).collect::<Vec<_>>();
        assert_eq!(phone_list(Lang::Nb, &names(&["Telefon 1"])), "Telefon 1");
        assert_eq!(
            phone_list(Lang::Nb, &names(&["Telefon 1", "Telefon 2"])),
            "Telefon 1 og Telefon 2"
        );
        assert_eq!(phone_list(Lang::En, &names(&["A", "B", "C"])), "A, B and C");
    }
}
