//! Combined admin view for update-related actions: the app's own version
//! and self-update controls alongside the Pi's OS package updates and
//! Tailscale. "Is everything up to date" is one job regardless of which
//! layer it's checking, so this is one page even though the two underlying
//! schedules (app auto-update vs. OS/Tailscale auto-update) stay
//! independently configurable - each POST route from handlers::system_update
//! / handlers::system_maintenance hands off to `render_page` below.

use askama::Template;
use axum::response::{Html, IntoResponse};

use crate::AppState;
use crate::handlers::{system_maintenance, system_update};

#[derive(Template)]
#[template(path = "updates.html")]
struct UpdatesTemplate {
    title: String,
    message: Option<String>,
    app: system_update::AppUpdateData,
    os: system_maintenance::OsUpdateData,
    /// `DEPLOY_KIND=docker` (design 22 §6): updates are a new image, not "Update now"; no OS,
    /// Tailscale or helper-script cards.
    docker: bool,
    /// What to set `HANDY_VERSION` to ("0.23.0") when a newer release is out.
    docker_version: Option<String>,
}

pub(crate) async fn render_page(state: &AppState, message: Option<String>) -> Html<String> {
    let docker = state.net.deploy_kind == crate::net::DeployKind::Docker;
    let app = system_update::gather(state).await;
    let os = if docker {
        system_maintenance::not_managed()
    } else {
        system_maintenance::gather().await
    };
    page(docker, app, os, message)
}

/// The page from what was gathered (no network: the tests build it directly).
pub(crate) fn page(
    docker: bool,
    app: system_update::AppUpdateData,
    os: system_maintenance::OsUpdateData,
    message: Option<String>,
) -> Html<String> {
    let docker_version = app
        .latest_version
        .as_deref()
        .filter(|_| docker && app.update_available)
        .map(|version| version.trim_start_matches('v').to_string());
    Html(
        UpdatesTemplate {
            title: "Software updates".to_string(),
            message,
            os,
            app,
            docker,
            docker_version,
        }
        .render()
        .unwrap(),
    )
}

pub async fn show_updates_page(
    axum::extract::State(state): axum::extract::State<AppState>,
) -> impl IntoResponse {
    render_page(&state, None).await
}
