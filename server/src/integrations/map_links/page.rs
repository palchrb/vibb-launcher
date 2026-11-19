//! Device › Apps' "Map links" group (design 110 §9, `partials/map_links_group.html`, `#map-links`): the phone's
//! switch as an auto-saving form (design 103: the save returns to the group, never the top), one line for what is
//! left (orange only for what is wrong or undone, the how-to behind its ⓘ - CLAUDE.md "Words in the UI"), and the
//! footer's ⓘ for what it does.
//!
//! Shown when the phone reports a target app in any state, or while the switch is on.

use std::collections::HashMap;

use askama::Template;
use axum::Form;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Redirect, Response};
use serde_json::Value;

use crate::AppState;
use crate::handlers::device_pages::{AppsFrom, apps_url};
use crate::i18n::{Lang, Words, t, t_params};

/// The line under the switch: its code (`map_links_server.json`'s `pwa_lines`), its words, orange or quiet, and its
/// ⓘ's how-to.
pub struct Line {
    pub code: &'static str,
    pub text: String,
    pub orange: bool,
    pub info: Option<String>,
    /// Under the switch; `false` for what follows from a choice the parent made (Google Maps allowed, the switch off
    /// with Play services' links still off), which is a quiet note in the footer's ⓘ (code QA L10, "Words in the UI").
    pub shown: bool,
}

#[derive(Template)]
#[template(path = "partials/map_links_group.html")]
struct GroupTemplate {
    lang: Lang,
    device_id: i64,
    from: Option<String>,
    /// Design 114 §5.4: the missing setup steps this group needs (rows 15-16), at the top of the group.
    setup_warnings: Vec<crate::setup_status::Warning>,
    on: bool,
    switch_label: String,
    /// §8.4: Google's lookup is broken (server-wide), above the switch.
    lookup: Option<(String, String)>,
    line: Option<Line>,
    footer: String,
}
crate::i18n::words!(GroupTemplate);

/// The target app's name in the words.
fn app_name(report: Option<&Value>) -> &'static str {
    match report.and_then(|r| r["target"].as_str()) {
        Some(target) if target.starts_with("app.comaps") => "CoMaps",
        _ => "Organic Maps",
    }
}

/// Which line shows (§9, first match; `None` for none): with the switch on, from the report's `reach` first; with it
/// off, only the quiet note that Google Play services' links are still off. `steps_left`: design 114's warning lines
/// at `#map-links` (rows 15-16, its `map_play_links` and `map_vibb_links`) say what is left on the phone, so no line
/// of this group's says it again (114 §2.6).
pub fn line_code(
    on: bool,
    report: Option<&Value>,
    steps_left: bool,
) -> Option<(&'static str, bool)> {
    if !on {
        return (report.and_then(|r| r["play_services_links"].as_bool()) == Some(false))
            .then_some(("off_play", false));
    }
    let Some(report) = report else {
        return Some(("waiting", false));
    };
    let reach = |class: &str| report["reach"][class].as_bool().unwrap_or(false);
    match report["target_state"].as_str() {
        Some("hidden") => return Some(("hidden", true)),
        Some("allowed") | Some("suspended") => {}
        _ => return Some(("missing", true)),
    }
    if steps_left {
        return None;
    }
    // Code QA L5: short links don't reach Vibb One and Play services' links can't be read (114 never counts that as a
    // step left): no line, rather than "Ready." or "held" over links that go elsewhere.
    if !reach("short") && report["play_services_links"].as_bool() != Some(false) {
        return None;
    }
    // Every host the parent can select is selected (step 2 suspends the apps holding them while its page is open), so
    // Google's long links not reaching Vibb One is an app the parent allowed taking them: Google Maps.
    if !reach("google") {
        return Some(("held", false));
    }
    Some(("ready", false))
}

fn line(lang: Lang, code: &'static str, orange: bool, app: &str) -> Line {
    let words = |key: &str| t_params(lang, key, &[("app", app)]);
    let (text, info) = match code {
        "off_play" => (
            "map_links.line.off_play",
            Some("map_links.line.off_play_fix"),
        ),
        "waiting" => ("map_links.line.waiting", None),
        "missing" => ("map_links.line.missing", None),
        "hidden" => ("map_links.line.hidden", Some("map_links.line.hidden_fix")),
        "held" => ("map_links.line.held", None),
        _ => ("map_links.line.ready", None),
    };
    Line {
        code,
        text: words(text),
        orange,
        info: info.map(words),
        shown: !matches!(code, "held" | "off_play"),
    }
}

/// The group for this phone, rendered, or `None` when it isn't offered.
pub async fn group(
    state: &AppState,
    lang: Lang,
    device_id: i64,
    from: Option<&str>,
    setup_warnings: &crate::setup_status::Warnings,
) -> Result<Option<String>, sqlx::Error> {
    let on = super::is_on(&state.db, device_id).await?;
    let report = super::report(&state.db, device_id).await?;
    let has_target = report
        .as_ref()
        .is_some_and(|r| r["target"].as_str().is_some());
    if !on && !has_target {
        return Ok(None);
    }
    let app = app_name(report.as_ref());
    let broken =
        on && super::resolve::lookup_broken(&super::resolve::recent_counts(&state.db).await?);
    let setup_warnings = setup_warnings.at("map-links").to_vec();
    let html = GroupTemplate {
        lang,
        device_id,
        from: from.map(str::to_string),
        on,
        switch_label: t_params(lang, "map_links.switch", &[("app", app)]),
        lookup: broken.then(|| {
            (
                t(lang, "map_links.line.lookup").to_string(),
                t_params(lang, "map_links.line.lookup_fix", &[("app", app)]),
            )
        }),
        line: line_code(on, report.as_ref(), !setup_warnings.is_empty())
            .map(|(code, orange)| line(lang, code, orange, app)),
        setup_warnings,
        footer: t_params(lang, "map_links.footer", &[("app", app)]),
    }
    .render()
    .unwrap_or_else(|err| {
        tracing::error!(%err, "map links: the group didn't render");
        String::new()
    });
    Ok(Some(html))
}

/// `POST /devices/{id}/map-links`: the switch (`on` present = on). Nudges the phone; back to `#map-links`.
pub async fn save(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    match super::set_on(&state, id, form.contains_key("on")).await {
        Ok(true) => {
            let from = form.get("from").and_then(|f| AppsFrom::parse(f));
            Redirect::to(&apps_url(id, "", from, "map-links")).into_response()
        }
        Ok(false) => crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.device_not_found"),
        Err(err) => {
            tracing::error!(device_id = id, %err, "map links: couldn't save the switch");
            crate::i18n::error(lang, StatusCode::INTERNAL_SERVER_ERROR, "error.save_failed")
        }
    }
}
