//! A phone's Bedtime (design 66, `docs/design/66-bedtime.md`): one setting per phone, entered
//! once as two rows of nights - Sun-Thu, and Fri-Sat (the nights before Saturday and Sunday) -
//! and the only rule that runs past midnight. It is the phone's `bedtime = 1` row in
//! `time_rules` (`time_rules::Nights` builds its 7 days), outside the rule sets, so it holds
//! whatever the Own rules switch says.
//!
//! - `GET /devices/{id}/bedtime` (`device_bedtime.html`): one form shaped like the rule editor, Save
//!   in the sticky top bar with the save's flash or a refusal's reason. A phone without one opens
//!   filled in (21:00-07:00 both rows, calls on) with Add, nothing stored before that.
//! - `POST /devices/{id}/bedtime`: one upsert (`ON CONFLICT` on the partial unique index, so two
//!   saves of a new bedtime at once make one row), then `?saved=1#bedtime`; a refusal renders the
//!   page again (400) as typed.
//! - `POST /devices/{id}/bedtime/remove`: then Device › Time's `?notice=bedtime_removed#bedtime`.
//!
//! An unknown phone is a 404, a DB error a 500; a save or a remove nudges the phone and is in the
//! security log (`bedtime_saved`, `bedtime_removed`).

use std::collections::{BTreeMap, HashMap};

use askama::Template;
use axum::Extension;
use axum::body::Bytes;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Redirect, Response};

use crate::AppState;
use crate::handlers::schedules::{
    AppChoice, app_choices, apps_value, field, form_pairs, log_change, minutes_to_time_input,
    nudge, parse_exempt_apps_form, read_failed, render, reported_apps, server_error,
    time_input_to_minutes, window_text,
};
use crate::security::CurrentAdmin;
use crate::time_rules::{self, BEDTIME_NAME, DayWindow, LAST_MINUTE, NOON, Nights, TimeRuleRow};

/// The two rows: their labels, Sun-Thu first.
const ROWS: [&str; 2] = ["Sun-Thu nights", "Fri-Sat nights"];
/// A new bedtime's rows.
const DEFAULT_NIGHT: DayWindow = DayWindow {
    start: 21 * 60,
    end: 7 * 60,
};

/// "Every night 21:00-07:00", "Sun-Thu 21:00-07:00 · Fri-Sat 22:30-09:00", "Sun-Thu nights
/// 21:00-07:00" (the other row empty).
pub(crate) fn nights_summary(nights: &Nights) -> String {
    match (nights.school, nights.free) {
        (Some(school), Some(free)) if school == free => {
            format!("Every night {}", window_text(school))
        }
        (Some(school), Some(free)) => format!(
            "Sun-Thu {} · Fri-Sat {}",
            window_text(school),
            window_text(free)
        ),
        (Some(school), None) => format!("Sun-Thu nights {}", window_text(school)),
        (None, Some(free)) => format!("Fri-Sat nights {}", window_text(free)),
        (None, None) => "No nights".to_string(),
    }
}

/// The stored bedtime's nights, `None` when the row doesn't read: its days or apps don't parse,
/// or its days aren't two rows of nights (a restored or hand-edited row).
fn stored_nights(row: &TimeRuleRow) -> Option<Nights> {
    row.to_policy().ok()?;
    Nights::from_days(&time_rules::parse_days(&row.days_json).ok()?)
}

/// The bedtime's words for Device › Time and Settings › Time Rules; `None` for a row that
/// doesn't read ("Can't be read", `text-alarm`).
pub(crate) fn bedtime_summary(row: &TimeRuleRow) -> Option<String> {
    stored_nights(row).map(|n| nights_summary(&n))
}

/// One row of nights on the page.
struct NightsInput {
    index: usize,
    label: &'static str,
    start: String,
    end: String,
    error: Option<String>,
}

/// The page's fields, from the stored bedtime, the defaults or as typed. The names match
/// `partials/exceptions.html`'s (`rule.apps`, ...).
struct BedtimeForm {
    rows: Vec<NightsInput>,
    calls_allowed: bool,
    apps: Vec<AppChoice>,
    apps_error: Option<String>,
}

impl BedtimeForm {
    fn apps_value(&self) -> String {
        apps_value(&self.apps)
    }
}

fn nights_inputs(nights: &[Option<DayWindow>; 2]) -> Vec<NightsInput> {
    (0..2)
        .map(|i| NightsInput {
            index: i,
            label: ROWS[i],
            start: nights[i]
                .map(|w| minutes_to_time_input(w.start))
                .unwrap_or_default(),
            end: nights[i]
                .map(|w| minutes_to_time_input(w.end))
                .unwrap_or_default(),
            error: None,
        })
        .collect()
}

#[derive(Template)]
#[template(path = "device_bedtime.html")]
struct BedtimeTemplate {
    title: String,
    device_id: i64,
    device_name: String,
    /// The form's fields; `rule` for the shared Exceptions partial.
    rule: BedtimeForm,
    /// A bedtime is stored: Save and Remove Bedtime…, else Add.
    stored: bool,
    saved: bool,
    /// A refusal's reason, in the top bar beside Save.
    refusal: Option<String>,
    /// The stored bedtime doesn't read: what shows empty and is saved as shown.
    corrupt: Option<String>,
}

/// A validated bedtime form.
#[derive(Debug, PartialEq, Eq)]
pub(crate) struct BedtimeInput {
    pub nights: Nights,
    pub calls_allowed: bool,
    pub exempt_apps: Vec<String>,
}

/// Why a bedtime form was refused, by the field it is shown under.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum BedtimeError {
    /// Row 0 (Sun-Thu) or 1 (Fri-Sat).
    Row(usize, String),
    Apps(String),
}

/// `n0_start`/`n0_end` (Sun-Thu) and `n1_*` (Fri-Sat), `HH:MM`, both or neither: the evening in
/// 12:00-23:59, the morning in 00:00-11:59 (every window crosses midnight, under 24 h); not both
/// rows empty. The exempt apps as a rule's.
pub(crate) fn parse_bedtime_form(
    fields: &[(String, String)],
) -> Result<BedtimeInput, BedtimeError> {
    let mut rows = [None; 2];
    for (i, label) in ROWS.iter().enumerate() {
        let start = field(fields, &format!("n{i}_start")).trim();
        let end = field(fields, &format!("n{i}_end")).trim();
        if start.is_empty() && end.is_empty() {
            continue;
        }
        let refused = |why: &str| Err(BedtimeError::Row(i, format!("{label}: {why}")));
        if start.is_empty() || end.is_empty() {
            return refused("give both times, or leave both empty.");
        }
        let (Some(start), Some(end)) = (time_input_to_minutes(start), time_input_to_minutes(end))
        else {
            return refused("use times like 21:00.");
        };
        if !(NOON..=LAST_MINUTE).contains(&start) || end >= NOON {
            return refused("bedtime starts after noon and ends before noon.");
        }
        rows[i] = Some(DayWindow { start, end });
    }
    if rows == [None, None] {
        return Err(BedtimeError::Row(
            0,
            "Give the times, or remove the bedtime.".to_string(),
        ));
    }
    Ok(BedtimeInput {
        nights: Nights {
            school: rows[0],
            free: rows[1],
        },
        calls_allowed: fields.iter().any(|(k, _)| k == "calls_allowed"),
        exempt_apps: parse_exempt_apps_form(fields).map_err(BedtimeError::Apps)?,
    })
}

/// The phone's name (a device with its policy row), or the answer: 404 for an unknown phone, 500
/// on a DB error.
async fn phone_or_answer(state: &AppState, id: i64) -> Result<String, Box<Response>> {
    match sqlx::query_scalar(
        "SELECT d.name FROM devices d JOIN device_policy p ON p.device_id = d.id WHERE d.id = ?",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await
    {
        Ok(Some(name)) => Ok(name),
        Ok(None) => Err(Box::new(
            (StatusCode::NOT_FOUND, "Device not found").into_response(),
        )),
        Err(err) => Err(Box::new(read_failed(err, "the device"))),
    }
}

/// The stored bedtime, and the phone's reported apps (package -> label).
type StoredAndApps = (Option<TimeRuleRow>, BTreeMap<String, String>);

async fn stored_and_apps(state: &AppState, id: i64) -> Result<StoredAndApps, Box<Response>> {
    let stored = time_rules::bedtime_row(&state.db, id)
        .await
        .map_err(|err| Box::new(read_failed(err, "the bedtime")))?;
    let apps = reported_apps(state, &[id])
        .await
        .map_err(|err| Box::new(read_failed(err, "the reported apps")))?;
    Ok((stored, apps))
}

/// The parts of a stored bedtime that don't read, for the page's warning.
fn unreadable_parts(row: &TimeRuleRow) -> Option<String> {
    let nights = time_rules::parse_days(&row.days_json)
        .ok()
        .and_then(|days| Nights::from_days(&days))
        .is_some();
    let apps = time_rules::parse_exempt_apps(&row.exempt_apps_json).is_ok();
    let parts = match (nights, apps) {
        (true, true) => return None,
        (false, true) => "Its nights couldn't",
        (true, false) => "Its apps couldn't",
        (false, false) => "Its nights and apps couldn't",
    };
    let error = if row.to_policy().is_err() {
        "The phone gets an error instead of its rules. "
    } else {
        ""
    };
    Some(format!("{error}{parts} be read"))
}

/// GET `/devices/{id}/bedtime`: the page, with the stored bedtime or a new one's defaults.
pub async fn show_bedtime(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    let device_name = match phone_or_answer(&state, id).await {
        Ok(name) => name,
        Err(answer) => return *answer,
    };
    let (stored, available) = match stored_and_apps(&state, id).await {
        Ok(found) => found,
        Err(answer) => return *answer,
    };
    let rule = match &stored {
        Some(row) => {
            let nights = time_rules::parse_days(&row.days_json)
                .ok()
                .and_then(|days| Nights::from_days(&days))
                .map_or([None, None], |n| [n.school, n.free]);
            let exempt = time_rules::parse_exempt_apps(&row.exempt_apps_json).unwrap_or_default();
            BedtimeForm {
                rows: nights_inputs(&nights),
                calls_allowed: row.calls_allowed,
                apps: app_choices(&available, &exempt),
                apps_error: None,
            }
        }
        None => BedtimeForm {
            rows: nights_inputs(&[Some(DEFAULT_NIGHT); 2]),
            calls_allowed: true,
            apps: app_choices(&available, &[]),
            apps_error: None,
        },
    };
    render(
        BedtimeTemplate {
            title: "Bedtime".to_string(),
            device_id: id,
            device_name,
            corrupt: stored.as_ref().and_then(unreadable_parts),
            stored: stored.is_some(),
            saved: params.contains_key("saved"),
            refusal: None,
            rule,
        },
        StatusCode::OK,
    )
}

/// A refused save: the page again (400) as typed, the reason under its row and in the top bar.
async fn refuse(
    state: &AppState,
    id: i64,
    device_name: String,
    fields: &[(String, String)],
    error: BedtimeError,
) -> Response {
    let (stored, available) = match stored_and_apps(state, id).await {
        Ok(found) => found,
        Err(answer) => return *answer,
    };
    let typed = |key: &str| -> String { field(fields, key).chars().take(8).collect() };
    let chosen: Vec<String> = fields
        .iter()
        .filter(|(k, v)| k == "exempt_apps" && time_rules::valid_package_name(v))
        .map(|(_, v)| v.clone())
        .take(time_rules::MAX_EXEMPT_APPS * 2)
        .collect();
    let mut rule = BedtimeForm {
        rows: (0..2)
            .map(|i| NightsInput {
                index: i,
                label: ROWS[i],
                start: typed(&format!("n{i}_start")),
                end: typed(&format!("n{i}_end")),
                error: None,
            })
            .collect(),
        calls_allowed: fields.iter().any(|(k, _)| k == "calls_allowed"),
        apps: app_choices(&available, &chosen),
        apps_error: None,
    };
    let refusal = match error {
        BedtimeError::Row(i, why) => {
            rule.rows[i].error = Some(why.clone());
            why
        }
        BedtimeError::Apps(why) => {
            rule.apps_error = Some(why.clone());
            why
        }
    };
    render(
        BedtimeTemplate {
            title: "Bedtime".to_string(),
            device_id: id,
            device_name,
            corrupt: stored.as_ref().and_then(unreadable_parts),
            stored: stored.is_some(),
            saved: false,
            refusal: Some(refusal),
            rule,
        },
        StatusCode::BAD_REQUEST,
    )
}

/// POST `/devices/{id}/bedtime`: add or save (one upsert), then the page's form.
pub async fn save_bedtime(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    body: Bytes,
) -> Response {
    let fields = form_pairs(&body);
    let device_name = match phone_or_answer(&state, id).await {
        Ok(name) => name,
        Err(answer) => return *answer,
    };
    let input = match parse_bedtime_form(&fields) {
        Ok(input) => input,
        Err(error) => return refuse(&state, id, device_name, &fields, error).await,
    };
    // The partial unique index is the conflict target: a save of a new bedtime racing another
    // one updates the row the other made, never a second row or a 500.
    if let Err(err) = sqlx::query(
        "INSERT INTO time_rules (device_id, name, kind, calls_allowed, exempt_apps_json, \
         days_json, bedtime) VALUES (?, ?, 'bedtime', ?, ?, ?, 1) \
         ON CONFLICT(device_id) WHERE bedtime = 1 DO UPDATE SET name = excluded.name, \
         kind = 'bedtime', calls_allowed = excluded.calls_allowed, \
         exempt_apps_json = excluded.exempt_apps_json, days_json = excluded.days_json",
    )
    .bind(id)
    .bind(BEDTIME_NAME)
    .bind(input.calls_allowed)
    .bind(serde_json::to_string(&input.exempt_apps).expect("strings serialize"))
    .bind(serde_json::to_string(&input.nights.days()).expect("windows serialize"))
    .execute(&state.db)
    .await
    {
        return server_error(err, "failed to save a bedtime");
    }
    log_change(
        &state,
        &admin,
        "bedtime_saved",
        Some(id),
        &nights_summary(&input.nights),
    )
    .await;
    nudge(&state, Some(id)).await;
    // Its form: Save is at the top, and scroll-restore keeps the place.
    Redirect::to(&format!("/devices/{id}/bedtime?saved=1#bedtime")).into_response()
}

/// POST `/devices/{id}/bedtime/remove`: then Device › Time's Bedtime group. Removing one that is
/// already gone lands there too, with nothing written.
pub async fn remove_bedtime(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
) -> Response {
    if let Err(answer) = phone_or_answer(&state, id).await {
        return *answer;
    }
    match sqlx::query("DELETE FROM time_rules WHERE device_id = ? AND bedtime = 1")
        .bind(id)
        .execute(&state.db)
        .await
    {
        Ok(done) if done.rows_affected() > 0 => {
            log_change(&state, &admin, "bedtime_removed", Some(id), BEDTIME_NAME).await;
            nudge(&state, Some(id)).await;
        }
        Ok(_) => {}
        Err(err) => return server_error(err, "failed to remove a bedtime"),
    }
    Redirect::to(&format!(
        "/devices/{id}/time?notice=bedtime_removed#bedtime"
    ))
    .into_response()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pairs(list: &[(&str, &str)]) -> Vec<(String, String)> {
        list.iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    }

    fn w(start: i64, end: i64) -> Option<DayWindow> {
        Some(DayWindow { start, end })
    }

    #[test]
    fn the_bedtime_form() {
        let ok = parse_bedtime_form(&pairs(&[
            ("n0_start", "21:00"),
            ("n0_end", "07:00"),
            ("n1_start", "22:30"),
            ("n1_end", "09:00"),
            ("calls_allowed", "on"),
            ("exempt_apps", "me.vibb.music"),
        ]))
        .unwrap();
        assert_eq!(
            ok.nights,
            Nights {
                school: w(1260, 420),
                free: w(1350, 540)
            }
        );
        assert!(ok.calls_allowed);
        assert_eq!(ok.exempt_apps, ["me.vibb.music"]);
        // Fri-Sat are the nights before Saturday and Sunday: days 4 and 5.
        let days = ok.nights.days();
        assert_eq!(days[3], w(1260, 420));
        assert_eq!(days[4], w(1350, 540));
        assert_eq!(days[5], w(1350, 540));
        assert_eq!(days[6], w(1260, 420));

        // One empty row is no bedtime those nights; the edges of the ranges are taken.
        let one =
            parse_bedtime_form(&pairs(&[("n1_start", "12:00"), ("n1_end", "11:59")])).unwrap();
        assert_eq!(
            one.nights,
            Nights {
                school: None,
                free: w(720, 719)
            }
        );
        assert!(!one.calls_allowed);
        assert!(parse_bedtime_form(&pairs(&[("n0_start", "23:59"), ("n0_end", "00:00")])).is_ok());

        let refused = |list: &[(&str, &str)]| parse_bedtime_form(&pairs(list)).unwrap_err();
        assert_eq!(
            refused(&[]),
            BedtimeError::Row(0, "Give the times, or remove the bedtime.".to_string())
        );
        let noon = |row: usize| {
            BedtimeError::Row(
                row,
                format!(
                    "{}: bedtime starts after noon and ends before noon.",
                    ROWS[row]
                ),
            )
        };
        for (start, end) in [("11:59", "07:00"), ("21:00", "12:00"), ("01:00", "07:00")] {
            assert_eq!(
                refused(&[("n1_start", start), ("n1_end", end)]),
                noon(1),
                "{start}-{end}"
            );
        }
        assert_eq!(
            refused(&[("n0_start", "21:00")]),
            BedtimeError::Row(
                0,
                "Sun-Thu nights: give both times, or leave both empty.".to_string()
            )
        );
        assert_eq!(
            refused(&[("n0_start", "9pm"), ("n0_end", "07:00")]),
            BedtimeError::Row(0, "Sun-Thu nights: use times like 21:00.".to_string())
        );
        assert!(matches!(
            refused(&[
                ("n0_start", "21:00"),
                ("n0_end", "07:00"),
                ("exempt_apps", "nope")
            ]),
            BedtimeError::Apps(_)
        ));
    }

    #[test]
    fn the_bedtimes_words() {
        let nights = |school, free| nights_summary(&Nights { school, free });
        assert_eq!(
            nights(w(1260, 420), w(1260, 420)),
            "Every night 21:00-07:00"
        );
        assert_eq!(
            nights(w(1260, 420), w(1350, 540)),
            "Sun-Thu 21:00-07:00 · Fri-Sat 22:30-09:00"
        );
        assert_eq!(nights(w(1260, 420), None), "Sun-Thu nights 21:00-07:00");
        assert_eq!(nights(None, w(1350, 540)), "Fri-Sat nights 22:30-09:00");
    }
}
