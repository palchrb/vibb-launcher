//! Settings › Software Updates (`/updates`): this server's version, the newest `server-v*` release
//! and how the owner updates it - versions only. The server never acts on its host (the user,
//! 2026-11-04): no update button, no restart, no system or Tailscale updates, no schedule, and it
//! reads nothing of the host's state. In Docker the image is pulled (Watchtower, or by hand); on a
//! systemd install the owner runs `update.sh` over SSH (DEPLOY.md, "Updating").

use askama::Template;
use axum::response::{Html, IntoResponse};

use crate::AppState;
use crate::handlers::system_update;
use crate::i18n::{Lang, Words, t};

#[derive(Template)]
#[template(path = "updates.html")]
struct UpdatesTemplate {
    lang: Lang,
    title: String,
    app: system_update::AppUpdateData,
    /// `DEPLOY_KIND=docker` (design 22 §6): updated by pulling the image.
    docker: bool,
    /// The newer release's version ("0.67.0"), when one is out: a pinned `HANDY_VERSION` is set to it.
    newer_version: Option<String>,
    /// `SERVER_RELEASE_REPO` when it isn't the project's (security QA L1): the command shown stays the
    /// project's, and a line names the other repo - `.env` is the service user's, so the page never
    /// quietly points the owner's root shell at it.
    other_repo: Option<String>,
}
crate::i18n::words!(UpdatesTemplate);

/// The page from what was gathered (no network: the tests build it directly).
pub(crate) fn page(
    docker: bool,
    app: system_update::AppUpdateData,
    repo: &str,
    lang: Lang,
) -> Html<String> {
    let newer_version = app
        .latest_version
        .as_deref()
        .filter(|_| app.update_available)
        .map(|version| version.trim_start_matches('v').to_string());
    Html(
        UpdatesTemplate {
            lang,
            title: t(lang, "settings.updates.title").to_string(),
            app,
            docker,
            newer_version,
            other_repo: (repo != crate::config::DEFAULT_SERVER_RELEASE_REPO)
                .then(|| repo.to_string()),
        }
        .render()
        .unwrap(),
    )
}

/// The page. An old bookmark's `?done=` (from when the page had buttons) is ignored.
pub async fn show_updates_page(
    lang: Lang,
    axum::extract::State(state): axum::extract::State<AppState>,
) -> impl IntoResponse {
    let docker = state.net.deploy_kind == crate::net::DeployKind::Docker;
    let app = system_update::gather(&state).await;
    page(docker, app, &state.config.server_release_repo, lang)
}
