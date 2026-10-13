//! Settings › Software Updates (`/updates`): the server's own version and self-update next to the
//! Pi's OS package updates and Tailscale, and the two schedules. "Is everything up to date" is one
//! job regardless of which layer it's checking, so this is one page even though the two
//! underlying schedules (app auto-update vs. OS/Tailscale auto-update) stay independently
//! configurable - each POST route from handlers::system_update / handlers::system_maintenance
//! renders this page in its answer (`render_page`), with its result in the group that posted
//! (`Flash::at`: "app", "os", "tailscale", "app-schedule", "os-schedule"), and posts to that
//! group's fragment so the answer lands there (design 31 §5.1).

use askama::Template;
use axum::response::{Html, IntoResponse};

use crate::AppState;
use crate::handlers::settings::Flash;
use crate::handlers::{system_maintenance, system_update};

#[derive(Template)]
#[template(path = "updates.html")]
struct UpdatesTemplate {
    title: String,
    flash: Option<Flash>,
    app: system_update::AppUpdateData,
    os: system_maintenance::OsUpdateData,
    weekdays: &'static [(&'static str, &'static str)],
    /// `DEPLOY_KIND=docker` (design 22 §6): updates are a new image, not "Update now"; no OS,
    /// Tailscale or helper-script groups.
    docker: bool,
    /// What to set `HANDY_VERSION` to ("0.23.0") when a newer release is out.
    docker_version: Option<String>,
}

pub(crate) async fn render_page(state: &AppState, flash: Option<Flash>) -> Html<String> {
    let docker = state.net.deploy_kind == crate::net::DeployKind::Docker;
    let app = system_update::gather(state).await;
    let os = if docker {
        system_maintenance::not_managed()
    } else {
        system_maintenance::gather().await
    };
    page(docker, app, os, flash)
}

/// The page from what was gathered (no network: the tests build it directly).
pub(crate) fn page(
    docker: bool,
    app: system_update::AppUpdateData,
    os: system_maintenance::OsUpdateData,
    flash: Option<Flash>,
) -> Html<String> {
    let docker_version = app
        .latest_version
        .as_deref()
        .filter(|_| docker && app.update_available)
        .map(|version| version.trim_start_matches('v').to_string());
    Html(
        UpdatesTemplate {
            title: "Software Updates".to_string(),
            flash,
            os,
            app,
            weekdays: &crate::handlers::backups::WEEKDAYS,
            docker,
            docker_version,
        }
        .render()
        .unwrap(),
    )
}

/// The root helper's request file couldn't be written: nothing starts, and why is behind ⓘ.
pub(crate) fn not_requested(at: &'static str) -> Flash {
    Flash::refused(at, "Nothing was started").because(
        "The server couldn't ask its helper on the Pi (the request file couldn't be written). \
         Re-running the installer sets the helper up again.",
    )
}

pub async fn show_updates_page(
    axum::extract::State(state): axum::extract::State<AppState>,
) -> impl IntoResponse {
    render_page(&state, None).await
}
