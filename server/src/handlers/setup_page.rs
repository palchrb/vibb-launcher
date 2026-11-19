//! Device › Oppsett (design 114 §5.1): the phone's setup steps, from the one table in `setup_status`.
//! - `GET /devices/{id}/setup` ([view]): the open group (`#open`: the setup hold's "I oppsett til 07:10" with Lås
//!   igjen, or Åpne for oppsett / Åpnet til … with Lås igjen, the quick actions' own commands with `back=setup`), "På
//!   telefonen" (`#phone`), "Sjekk selv" (`#check`, only with rows), "Valgfritt" (`#optional`) and the ticked steps in
//!   one closed `<details>`; every row `#step-<key>`.
//! - `POST /devices/{id}/setup/ticks` ([tick]): a parent step ticked (`on`) or unticked, back on its row - a save never
//!   jumps to the top; the phone nudged; `settings_log` writes the security log's line.

use std::collections::HashMap;

use askama::Template;
use axum::Form;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Redirect, Response};

use crate::AppState;
use crate::handlers::device_pages::{load_device, load_latest, load_policy};
use crate::i18n::{Lang, Words, t_params};
use crate::models::Device;
use crate::setup_status::{self, Page};

#[derive(Template)]
#[template(path = "device_setup.html")]
struct SetupTemplate {
    lang: Lang,
    who: crate::handlers::devices::BandAvatar,
    title: String,
    device: Device,
    page: Page,
    /// In the setup hold: "I oppsett til 07:10".
    hold: Option<String>,
    /// Outside the hold: the quick actions' opening (`quick_actions::setup_card`), `None` before the first report.
    open: Option<crate::handlers::quick_actions::SetupCard>,
    /// "Sendt…" after Åpne for oppsett or Lås igjen (`?notice=`), shown in `#open` (also before the first report).
    flash: Option<&'static str>,
}
crate::i18n::words!(SetupTemplate);

impl SetupTemplate {
    /// The open group shows: in the hold, while open, while something the phone can do is missing, or for its flash.
    fn open_shown(&self) -> bool {
        self.hold.is_some()
            || self.flash.is_some()
            || self
                .open
                .as_ref()
                .is_some_and(|open| open.open || self.page.openable)
    }
}

/// GET `/devices/{id}/setup`.
pub async fn view(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Query(query): Query<HashMap<String, String>>,
) -> Response {
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
    let ticked = match setup_status::ticked(&state.db, id).await {
        Ok(ticked) => ticked,
        Err(err) => {
            tracing::error!(device_id = id, %err, "can't read the setup ticks");
            return crate::i18n::error(lang, StatusCode::INTERNAL_SERVER_ERROR, "error.read_phone");
        }
    };
    let zone = crate::display_time::Zone::load(&state.db, lang).await;
    let now = chrono::Utc::now();
    let notice = query.get("notice").map(String::as_str);
    let open = match crate::handlers::quick_actions::setup_card(
        &state.db,
        &device,
        latest.as_ref(),
        notice,
        &zone,
        now,
    )
    .await
    {
        Ok(open) => open,
        Err(err) => {
            tracing::error!(device_id = id, %err, "can't read the opening for setup");
            return crate::i18n::error(lang, StatusCode::INTERNAL_SERVER_ERROR, "error.read_phone");
        }
    };
    let hold = latest
        .as_ref()
        .and_then(|latest| setup_status::hold_end(latest, now.timestamp_millis()))
        .and_then(chrono::DateTime::from_timestamp_millis)
        .map(|end| t_params(lang, "setup.open.hold", &[("at", &zone.clock(end))]));
    let flash = notice
        .and_then(crate::window_relock::flash)
        .filter(|(group, _)| *group == "open")
        .map(|(_, key)| key);
    let page = setup_status::page(&setup_status::PageInput {
        lang,
        device_id: id,
        policy: &policy,
        latest: latest.as_ref(),
        ticked: &ticked,
    });
    Html(
        SetupTemplate {
            lang,
            who: crate::handlers::devices::BandAvatar::of(&device),
            title: crate::i18n::t(lang, "setup.page.title").to_string(),
            device,
            page,
            hold,
            open,
            flash,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

#[derive(serde::Deserialize)]
pub struct TickForm {
    pub step: String,
    /// The switch: present = ticked.
    #[serde(default)]
    pub on: Option<String>,
}

/// POST `/devices/{id}/setup/ticks`: a parent step ticked or unticked (§5.1). Back on its row, the phone nudged; a key
/// that isn't a parent step is a 400 with nothing written.
pub async fn tick(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Form(form): Form<TickForm>,
) -> Response {
    let Some(step) =
        setup_status::step(&form.step).filter(|step| step.read_by == setup_status::ReadBy::Parent)
    else {
        return crate::i18n::error(lang, StatusCode::BAD_REQUEST, "error.out_of_date");
    };
    if let Err((status, key)) = load_device(&state, id).await {
        return crate::i18n::error(lang, status, key);
    }
    let saved = if form.on.is_some() {
        setup_status::store_tick(&state.db, id, step.key, false)
            .await
            .map(|_| ())
    } else {
        sqlx::query("DELETE FROM device_setup_ticks WHERE device_id = ? AND step = ?")
            .bind(id)
            .bind(step.key)
            .execute(&state.db)
            .await
            .map(|_| ())
    };
    match saved {
        Ok(()) => {
            let _ = state.command_notify.send(id);
            Redirect::to(&format!("/devices/{id}/setup#step-{}", step.key)).into_response()
        }
        Err(err) => {
            tracing::error!(device_id = id, %err, "couldn't save a setup tick");
            crate::i18n::error(
                lang,
                StatusCode::INTERNAL_SERVER_ERROR,
                "error.save_failed_log",
            )
        }
    }
}
