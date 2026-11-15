//! The answer card on a phone's summary (design 100 §14.4): the kid's open request for more time, answered with
//! "+15 min", "+30 min", "+1 t" or "Nei". One form posts to `/devices/{id}/time-request/answer#ask` (the card's own
//! group, so `scroll-restore.js` makes it a staying post); the answer lands on `#quick` with its flash, where the card
//! was. The first parent's answer wins for every parent (`time_answers::write_answer`); the phone learns it from its
//! policy (`time_policy.answer`).

use std::collections::HashMap;

use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Redirect, Response};
use axum::{Extension, Form};

use crate::AppState;
use crate::display_time::Zone;
use crate::i18n::Lang;
use crate::models::AdminUser;
use crate::security::{self, CurrentAdmin};
use crate::time_answers::{self, Given, Need, Standing};

/// The security log's line for a parent's "Nei" (after its commit); a yes is the lift's own line.
async fn no_line(state: &AppState, admin: &AdminUser, id: i64) {
    security::record_security_event(
        &state.db,
        "time_request_no",
        Some(&admin.username),
        None,
        Some(
            security::Detail::words(
                "security.detail.time_request_no",
                &[("device", &id.to_string())],
            )
            .with_facts(format!("device {id}")),
        ),
    )
    .await;
}

/// What the answer did.
enum Outcome {
    /// Written: the lift's target for a yes.
    Done(Given, &'static str),
    /// Nothing written: why.
    Stale(Standing),
}

/// `POST /devices/{id}/time-request/answer`: form `request` (the phone's request id) and `answer` (`15`, `30`, `60`
/// or `no`). One `BEGIN IMMEDIATE` transaction: the request must be the phone's open one; for minutes a lift is
/// written - screen time, or a pause of every rule when a rule locks (D9, decided now from the phone's fresh report,
/// else the request's own state: QA R2-M2) -; the answer row (a second parent's conflicts and rolls back); the answer
/// in the event's params. After the commit the security line and the nudge. A stale card writes nothing and says
/// why; an unknown request is a 404.
pub async fn answer(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let request = form.get("request").map(|r| r.trim()).unwrap_or("");
    let minutes = match form.get("answer").map(String::as_str) {
        Some("15") => Some(15),
        Some("30") => Some(30),
        Some("60") => Some(60),
        Some("no") => None,
        _ => return crate::i18n::error(lang, StatusCode::BAD_REQUEST, "error.unknown_lift"),
    };
    if !crate::handlers::device_api::time_request_id_ok(request) {
        return crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.no_such_request");
    }
    let device = match crate::handlers::device_pages::load_device(&state, id).await {
        Ok(device) => device,
        Err((status, key)) => return crate::i18n::error(lang, status, key),
    };
    let now = chrono::Utc::now();
    let zone = Zone::load(&state.db, lang).await;
    let read = async {
        let fresh = time_answers::fresh_report(&state, &device, &zone, now).await?;
        let day_start = time_answers::phone_day_start(&state.db, id, now).await?;
        Ok::<_, sqlx::Error>((fresh, day_start))
    };
    let (fresh, day_start) = match read.await {
        Ok(read) => read,
        Err(err) => return server_error(lang, err),
    };
    let written = async {
        let mut tx = state.db.begin_with("BEGIN IMMEDIATE").await?;
        let open = match time_answers::standing(&mut tx, id, request, day_start).await? {
            Standing::Open(open) => open,
            other => {
                tx.rollback().await?;
                return Ok::<_, sqlx::Error>(Outcome::Stale(other));
            }
        };
        let (given, target) = match minutes {
            Some(minutes) => {
                let asked = open.params.as_ref().map(|p| p.state.as_str());
                let target = match time_answers::need(fresh.as_ref(), asked, &zone, now) {
                    Need::Rules { .. } => "rule",
                    Need::Time => "budget",
                };
                let lift_id = crate::handlers::lifts::insert_lift(
                    &mut tx,
                    id,
                    target,
                    None,
                    None,
                    minutes,
                    &admin.username,
                )
                .await?;
                (Given::Yes { lift_id, minutes }, target)
            }
            None => (Given::No, ""),
        };
        if !time_answers::write_answer(&mut tx, id, request, given, &admin).await? {
            tx.rollback().await?;
            return Ok(Outcome::Stale(Standing::Answered));
        }
        tx.commit().await?;
        Ok(Outcome::Done(given, target))
    };
    let outcome = match written.await {
        Ok(outcome) => outcome,
        Err(err) => return server_error(lang, err),
    };
    let done = match outcome {
        Outcome::Done(Given::Yes { minutes, .. }, target) => {
            crate::handlers::lifts::lift_line(&state, &admin, id, target, None, minutes).await;
            let _ = state.command_notify.send(id);
            if target == "rule" { "pause" } else { "extra" }
        }
        Outcome::Done(Given::No, _) => {
            no_line(&state, &admin, id).await;
            let _ = state.command_notify.send(id);
            // §14.7: only a launcher that shows "Nei" is promised to.
            match crate::sms::has_capability(&state.db, id, time_answers::CAPABILITY).await {
                Ok(true) => "answer_no_seen",
                _ => "answer_no",
            }
        }
        Outcome::Stale(Standing::Unknown) => {
            return crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.no_such_request");
        }
        Outcome::Stale(Standing::Answered) => "answered_already",
        Outcome::Stale(Standing::Gone) => "request_gone",
        Outcome::Stale(Standing::AskedAgain | Standing::Open(_)) => "asked_again",
    };
    // Code QA L6: "… har allerede svart." names who answered this very card's request.
    let request = if done == "answered_already" {
        format!("&request={request}")
    } else {
        String::new()
    };
    Redirect::to(&format!("/devices/{id}?done={done}{request}#quick")).into_response()
}

fn server_error(lang: Lang, err: sqlx::Error) -> Response {
    tracing::error!(%err, "failed to answer a request for more time");
    crate::i18n::error(
        lang,
        StatusCode::INTERNAL_SERVER_ERROR,
        "error.save_failed_log",
    )
}
