//! A phone's zones (design 64 §3.5, step 1): the zone's page (`/devices/{id}/zones/new`,
//! `/devices/{id}/zones/{zone_id}`, `templates/device_zone.html`, tab Locate) with one form -
//! name, centre, radius, the two switches - and Delete Zone…, their posts, and `zones.json` for
//! the Locate map's circles. The list is the Locate page's `#zones` (`handlers::locate`).
//!
//! Without JS the form is the whole feature (the numbers typed, or kept from the phone's newest
//! fix); `static/zones.js` adds the map: a tap places the centre, the marker drags, the slider
//! redraws the circle, every change writes the fields. A save returns to the zone's row on Locate
//! (`?zone=added|saved&zone_id=N#zone-N`), a delete to `#zones`; a refusal renders this page again
//! (400) at `#zone-form` as typed, the field marked, nothing written. Zones are the family's;
//! every change is in the security log by the phone's and the zone's names, never coordinates.
//! Every save nudges the phone (design 64 step 2's fences follow the policy).

use askama::Template;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Json, Redirect, Response};
use axum::{Extension, Form};
use std::collections::HashMap;

use crate::AppState;
use crate::models::{AdminUser, Device};
use crate::security::{self, CurrentAdmin};
use crate::zones::{self, MAX_ZONES, NAME_MAX, RADIUS};

#[derive(Template)]
#[template(path = "device_zone.html")]
struct ZoneTemplate {
    title: String,
    device: Device,
    /// `None` for a new zone.
    zone_id: Option<i64>,
    /// The zone's name as stored, for Delete Zone…'s question.
    stored_name: String,
    form: ZoneForm,
    errors: ZoneErrors,
    /// Not saved: the reason in the bar (a refusal that is no one field's: the 11th zone).
    refusal: Option<String>,
    /// The phone's newest fix (6 decimals), for "Use Phone's Location"; `None` without one.
    phone_fix: Option<(String, String)>,
    radius_min: i64,
    radius_max: i64,
    radius_step: i64,
}

/// The form's values as shown (typed, or stored).
struct ZoneForm {
    name: String,
    latitude: String,
    longitude: String,
    radius: String,
    notify_arrive: bool,
    notify_leave: bool,
}

/// Each field's refusal.
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

/// A form that reads: what is written.
#[derive(Clone, Debug, PartialEq)]
struct Input {
    name: String,
    latitude: f64,
    longitude: f64,
    radius_m: i64,
    notify_arrive: bool,
    notify_leave: bool,
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

/// The form read: the values to show again, and the input or each field's refusal. The name's
/// uniqueness and the cap are checked in the save's transaction.
fn parse(form: &HashMap<String, String>) -> (ZoneForm, Result<Input, ZoneErrors>) {
    let get = |key: &str| form.get(key).map(String::as_str).unwrap_or("");
    let typed = ZoneForm {
        name: get("name").trim().to_string(),
        latitude: get("latitude").trim().to_string(),
        longitude: get("longitude").trim().to_string(),
        radius: get("radius_m").trim().to_string(),
        notify_arrive: get("notify_arrive") == "on",
        notify_leave: get("notify_leave") == "on",
    };
    let mut errors = ZoneErrors::default();
    let name = typed.name.clone();
    if name.is_empty() {
        errors.name = Some("Give the zone a name.");
    } else if name.chars().count() > NAME_MAX {
        errors.name = Some("At most 40 characters.");
    } else if name.chars().any(char::is_control) {
        errors.name = Some("Letters, digits and spaces - no line breaks.");
    }
    let latitude = number(&typed.latitude).filter(|v| v.abs() <= 90.0);
    if latitude.is_none() {
        errors.latitude = Some("A latitude between -90 and 90.");
    }
    let longitude = number(&typed.longitude).filter(|v| v.abs() <= 180.0);
    if longitude.is_none() {
        errors.longitude = Some("A longitude between -180 and 180.");
    }
    let radius = typed
        .radius
        .parse::<i64>()
        .ok()
        .filter(|r| RADIUS.contains(r));
    if radius.is_none() {
        errors.radius = Some("Between 100 and 2000 m.");
    }
    let parsed = match (latitude, longitude, radius) {
        (Some(latitude), Some(longitude), Some(radius_m)) if !errors.any() => Ok(Input {
            name,
            latitude: round6(latitude),
            longitude: round6(longitude),
            radius_m,
            notify_arrive: typed.notify_arrive,
            notify_leave: typed.notify_leave,
        }),
        _ => Err(errors),
    };
    (typed, parsed)
}

/// What a save changed, for the security log ("radius 150 -> 250 m, moved, renamed, arrive
/// off") - never a coordinate.
fn changes(before: &zones::Zone, after: &Input) -> Vec<String> {
    let mut words = Vec::new();
    if before.name != after.name {
        words.push(format!("renamed from {}", before.name));
    }
    if before.latitude != after.latitude || before.longitude != after.longitude {
        words.push("moved".to_string());
    }
    if before.radius_m != after.radius_m {
        words.push(format!(
            "radius {} -> {} m",
            before.radius_m, after.radius_m
        ));
    }
    for (was, now, what) in [
        (before.notify_arrive, after.notify_arrive, "arrive"),
        (before.notify_leave, after.notify_leave, "leave"),
    ] {
        if was != now {
            words.push(format!("{what} {}", if now { "on" } else { "off" }));
        }
    }
    words
}

/// The same name on this phone, ignoring case.
fn same_name(a: &str, b: &str) -> bool {
    a.to_lowercase() == b.to_lowercase()
}

/// The phone's newest fix: where a new zone starts and "Use Phone's Location" goes.
async fn newest_point(state: &AppState, id: i64) -> Result<Option<(f64, f64)>, sqlx::Error> {
    sqlx::query_as(
        "SELECT latitude, longitude FROM device_locations WHERE device_id = ? \
         ORDER BY captured_at DESC, id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await
}

async fn device_of(state: &AppState, id: i64) -> Result<Option<Device>, sqlx::Error> {
    sqlx::query_as::<_, Device>("SELECT * FROM devices WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
}

fn not_found(what: &'static str) -> Response {
    (StatusCode::NOT_FOUND, what).into_response()
}

fn db_error(device_id: i64, err: sqlx::Error) -> Response {
    tracing::error!(device_id, %err, "zones: database error");
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        "Nothing was saved - check the server log.",
    )
        .into_response()
}

/// The page, with `status` (200, or 400 for a refusal).
#[allow(clippy::too_many_arguments)]
async fn render(
    state: &AppState,
    device: Device,
    zone_id: Option<i64>,
    stored_name: String,
    form: ZoneForm,
    errors: ZoneErrors,
    refusal: Option<String>,
    status: StatusCode,
) -> Response {
    let phone_fix = match newest_point(state, device.id).await {
        Ok(fix) => fix.map(|(lat, lng)| (coordinate(lat), coordinate(lng))),
        Err(err) => return db_error(device.id, err),
    };
    let title = if zone_id.is_some() {
        stored_name.clone()
    } else {
        "New Zone".to_string()
    };
    let page = ZoneTemplate {
        title,
        device,
        zone_id,
        stored_name,
        form,
        errors,
        refusal,
        phone_fix,
        radius_min: *RADIUS.start(),
        radius_max: *RADIUS.end(),
        radius_step: zones::RADIUS_STEP,
    };
    (status, Html(page.render().unwrap())).into_response()
}

/// `GET /devices/{id}/zones/new`: the form at the phone's newest fix and the default radius (no
/// fix: empty coordinates and the page says so).
pub async fn new_zone(State(state): State<AppState>, Path(id): Path<i64>) -> Response {
    let device = match device_of(&state, id).await {
        Ok(Some(device)) => device,
        Ok(None) => return not_found("Device not found"),
        Err(err) => return db_error(id, err),
    };
    let (latitude, longitude) = match newest_point(&state, id).await {
        Ok(Some((lat, lng))) => (coordinate(lat), coordinate(lng)),
        Ok(None) => (String::new(), String::new()),
        Err(err) => return db_error(id, err),
    };
    let form = ZoneForm {
        name: String::new(),
        latitude,
        longitude,
        radius: zones::DEFAULT_RADIUS.to_string(),
        notify_arrive: true,
        notify_leave: true,
    };
    render(
        &state,
        device,
        None,
        String::new(),
        form,
        ZoneErrors::default(),
        None,
        StatusCode::OK,
    )
    .await
}

/// The zone of this phone (`Ok(None)`: none, or another phone's - a 404 either way).
async fn zone_of(
    state: &AppState,
    id: i64,
    zone_id: i64,
) -> Result<Option<zones::Zone>, sqlx::Error> {
    sqlx::query_as("SELECT * FROM device_zones WHERE id = ? AND device_id = ?")
        .bind(zone_id)
        .bind(id)
        .fetch_optional(&state.db)
        .await
}

/// `GET /devices/{id}/zones/{zone_id}`: the zone's form as stored.
pub async fn show_zone(
    State(state): State<AppState>,
    Path((id, zone_id)): Path<(i64, i64)>,
) -> Response {
    let device = match device_of(&state, id).await {
        Ok(Some(device)) => device,
        Ok(None) => return not_found("Device not found"),
        Err(err) => return db_error(id, err),
    };
    let zone = match zone_of(&state, id, zone_id).await {
        Ok(Some(zone)) => zone,
        Ok(None) => return not_found("Zone not found"),
        Err(err) => return db_error(id, err),
    };
    let form = ZoneForm {
        name: zone.name.clone(),
        latitude: coordinate(zone.latitude),
        longitude: coordinate(zone.longitude),
        radius: zone.radius_m.to_string(),
        notify_arrive: zone.notify_arrive,
        notify_leave: zone.notify_leave,
    };
    render(
        &state,
        device,
        Some(zone.id),
        zone.name,
        form,
        ZoneErrors::default(),
        None,
        StatusCode::OK,
    )
    .await
}

/// A save's answer once it is written: the log line, the nudge, the zone's row on Locate.
async fn saved(
    state: &AppState,
    admin: &AdminUser,
    device: &Device,
    event: &str,
    detail: String,
    back: String,
) -> Response {
    security::record_security_event(
        &state.db,
        event,
        Some(&admin.username),
        None,
        Some(&format!("device {} ({}): {detail}", device.id, device.name)),
    )
    .await;
    let _ = state.command_notify.send(device.id);
    Redirect::to(&back).into_response()
}

/// What a save's transaction found: written (the zone's id), or a refusal to show.
enum Outcome {
    Written(i64),
    NameTaken,
    Full,
    Gone,
}

/// `POST /devices/{id}/zones`: a new zone, its side unknown and its cursor at the phone's newest
/// fix (the history is never judged against it; decision 4). Refused (400, nothing written): a
/// field, a name the phone has, an 11th zone.
pub async fn create_zone(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let device = match device_of(&state, id).await {
        Ok(Some(device)) => device,
        Ok(None) => return not_found("Device not found"),
        Err(err) => return db_error(id, err),
    };
    let (typed, parsed) = parse(&form);
    let input = match parsed {
        Ok(input) => input,
        Err(errors) => {
            return render(
                &state,
                device,
                None,
                String::new(),
                typed,
                errors,
                None,
                StatusCode::BAD_REQUEST,
            )
            .await;
        }
    };
    let written = async {
        // IMMEDIATE: the count, the name check and the insert under one write lock, so two saves
        // can't make an 11th zone or two of one name.
        let mut tx = state.db.begin_with("BEGIN IMMEDIATE").await?;
        let existing = zones::list(&mut *tx, id).await?;
        if existing.len() as i64 >= MAX_ZONES {
            return Ok(Outcome::Full);
        }
        if existing.iter().any(|z| same_name(&z.name, &input.name)) {
            return Ok(Outcome::NameTaken);
        }
        let zone_id: i64 = sqlx::query_scalar(
            "INSERT INTO device_zones (device_id, name, latitude, longitude, radius_m, \
             notify_arrive, notify_leave, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
        )
        .bind(id)
        .bind(&input.name)
        .bind(input.latitude)
        .bind(input.longitude)
        .bind(input.radius_m)
        .bind(input.notify_arrive)
        .bind(input.notify_leave)
        .bind(admin.id)
        .fetch_one(&mut *tx)
        .await?;
        zones::start_over(&mut tx, zone_id, id).await?;
        tx.commit().await?;
        Ok::<_, sqlx::Error>(Outcome::Written(zone_id))
    };
    match written.await {
        Ok(Outcome::Written(zone_id)) => {
            let detail = format!("{}, {} m", input.name, input.radius_m);
            let back =
                format!("/devices/locate?device={id}&zone=added&zone_id={zone_id}#zone-{zone_id}");
            saved(&state, &admin, &device, "zone_added", detail, back).await
        }
        Ok(Outcome::Full) => {
            let refusal = format!("{MAX_ZONES} zones is the most for one phone. Delete one first.");
            render(
                &state,
                device,
                None,
                String::new(),
                typed,
                ZoneErrors::default(),
                Some(refusal),
                StatusCode::BAD_REQUEST,
            )
            .await
        }
        Ok(Outcome::NameTaken) => {
            let errors = ZoneErrors {
                name: Some("This phone has a zone with that name."),
                ..ZoneErrors::default()
            };
            render(
                &state,
                device,
                None,
                String::new(),
                typed,
                errors,
                None,
                StatusCode::BAD_REQUEST,
            )
            .await
        }
        Ok(Outcome::Gone) => not_found("Device not found"),
        Err(err) => db_error(id, err),
    }
}

/// `POST /devices/{id}/zones/{zone_id}`: the form saved. A moved centre or a changed radius
/// forgets the side (decision 4: the next one is set silently); a rename or a switch keeps it.
/// Another phone's zone is a 404.
pub async fn save_zone(
    State(state): State<AppState>,
    Path((id, zone_id)): Path<(i64, i64)>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let device = match device_of(&state, id).await {
        Ok(Some(device)) => device,
        Ok(None) => return not_found("Device not found"),
        Err(err) => return db_error(id, err),
    };
    let stored = match zone_of(&state, id, zone_id).await {
        Ok(Some(zone)) => zone,
        Ok(None) => return not_found("Zone not found"),
        Err(err) => return db_error(id, err),
    };
    let (typed, parsed) = parse(&form);
    let input = match parsed {
        Ok(input) => input,
        Err(errors) => {
            return render(
                &state,
                device,
                Some(zone_id),
                stored.name,
                typed,
                errors,
                None,
                StatusCode::BAD_REQUEST,
            )
            .await;
        }
    };
    let written = async {
        let mut tx = state.db.begin_with("BEGIN IMMEDIATE").await?;
        let existing = zones::list(&mut *tx, id).await?;
        let Some(before) = existing.iter().find(|z| z.id == zone_id).cloned() else {
            return Ok((Outcome::Gone, Vec::new()));
        };
        if existing
            .iter()
            .any(|z| z.id != zone_id && same_name(&z.name, &input.name))
        {
            return Ok((Outcome::NameTaken, Vec::new()));
        }
        sqlx::query(
            "UPDATE device_zones SET name = ?, latitude = ?, longitude = ?, radius_m = ?, \
             notify_arrive = ?, notify_leave = ?, updated_at = datetime('now') WHERE id = ?",
        )
        .bind(&input.name)
        .bind(input.latitude)
        .bind(input.longitude)
        .bind(input.radius_m)
        .bind(input.notify_arrive)
        .bind(input.notify_leave)
        .bind(zone_id)
        .execute(&mut *tx)
        .await?;
        let moved = before.latitude != input.latitude
            || before.longitude != input.longitude
            || before.radius_m != input.radius_m;
        if moved {
            zones::start_over(&mut tx, zone_id, id).await?;
        }
        tx.commit().await?;
        Ok::<_, sqlx::Error>((Outcome::Written(zone_id), changes(&before, &input)))
    };
    match written.await {
        Ok((Outcome::Written(_), changed)) => {
            let back =
                format!("/devices/locate?device={id}&zone=saved&zone_id={zone_id}#zone-{zone_id}");
            if changed.is_empty() {
                return Redirect::to(&back).into_response();
            }
            let detail = format!("{} - {}", input.name, changed.join(", "));
            saved(&state, &admin, &device, "zone_changed", detail, back).await
        }
        Ok((Outcome::NameTaken, _)) => {
            let errors = ZoneErrors {
                name: Some("This phone has a zone with that name."),
                ..ZoneErrors::default()
            };
            render(
                &state,
                device,
                Some(zone_id),
                stored.name,
                typed,
                errors,
                None,
                StatusCode::BAD_REQUEST,
            )
            .await
        }
        Ok((Outcome::Gone | Outcome::Full, _)) => not_found("Zone not found"),
        Err(err) => db_error(id, err),
    }
}

/// `POST /devices/{id}/zones/{zone_id}/delete`: the zone goes; its past events stay in the inbox
/// until the location history's retention takes them. Back to `#zones` with the line.
pub async fn delete_zone(
    State(state): State<AppState>,
    Path((id, zone_id)): Path<(i64, i64)>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let device = match device_of(&state, id).await {
        Ok(Some(device)) => device,
        Ok(None) => return not_found("Device not found"),
        Err(err) => return db_error(id, err),
    };
    let deleted: Result<Option<String>, sqlx::Error> = sqlx::query_scalar(
        "DELETE FROM device_zones WHERE id = ? AND device_id = ? RETURNING name",
    )
    .bind(zone_id)
    .bind(id)
    .fetch_optional(&state.db)
    .await;
    match deleted {
        Ok(Some(name)) => {
            let back = format!("/devices/locate?device={id}&zone=deleted#zones");
            saved(&state, &admin, &device, "zone_deleted", name, back).await
        }
        Ok(None) => not_found("Zone not found"),
        Err(err) => db_error(id, err),
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
    here: bool,
}

/// `GET /devices/{id}/zones.json` (no-store: the coordinates of a child's home never sit in a
/// cached page): the phone's zones, oldest first.
pub async fn zones_json(State(state): State<AppState>, Path(id): Path<i64>) -> Response {
    match zones::list(&state.db, id).await {
        Ok(list) => Json(
            list.into_iter()
                .map(|z| ZonePoint {
                    here: z.here(),
                    id: z.id,
                    name: z.name,
                    latitude: z.latitude,
                    longitude: z.longitude,
                    radius_m: z.radius_m,
                })
                .collect::<Vec<_>>(),
        )
        .into_response(),
        Err(err) => db_error(id, err),
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
            ("notify_arrive", "on"),
        ]);
        let (_, parsed) = parse(&good);
        assert_eq!(
            parsed.ok(),
            Some(Input {
                name: "Home".to_string(),
                latitude: 59.9139,
                longitude: 10.752212,
                radius_m: 150,
                notify_arrive: true,
                notify_leave: false,
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
            let (_, parsed) = parse(&form(fields));
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
        let (_, edge) = parse(&form(&[
            ("name", &"x".repeat(40)),
            ("latitude", "-90"),
            ("longitude", "180"),
            ("radius_m", "2000"),
        ]));
        assert!(edge.is_ok());
    }

    #[test]
    fn a_change_is_logged_without_coordinates() {
        let before = zones::Zone {
            id: 1,
            device_id: 3,
            name: "Hjem".to_string(),
            latitude: 59.9139,
            longitude: 10.7522,
            radius_m: 150,
            notify_arrive: true,
            notify_leave: true,
            side: None,
            side_since: None,
            pending_side: None,
            pending_at: None,
            evaluated_at: None,
            evaluated_fix_id: None,
        };
        let after = Input {
            name: "Home".to_string(),
            latitude: 59.9140,
            longitude: 10.7522,
            radius_m: 250,
            notify_arrive: false,
            notify_leave: true,
        };
        let words = changes(&before, &after).join(", ");
        assert_eq!(
            words,
            "renamed from Hjem, moved, radius 150 -> 250 m, arrive off"
        );
        assert!(!words.contains("59.9"));
        let same = Input {
            name: before.name.clone(),
            latitude: before.latitude,
            longitude: before.longitude,
            radius_m: before.radius_m,
            notify_arrive: true,
            notify_leave: true,
        };
        assert!(changes(&before, &same).is_empty());
    }
}
