//! Settings › Software Updates (`/updates`): this server's version, the newest `server-v*` release
//! and how the owner updates it - versions only. The server never acts on its host (the user,
//! 2026-11-04): no update button, no restart, no system or Tailscale updates, no schedule, and it
//! reads nothing of the host's state. In Docker Watchtower pulls the image, or the host's `./update-image.sh` checks it first; on a
//! systemd install the owner runs `update.sh` over SSH (DEPLOY.md, "Updating").
//!
//! On a Docker install one orange line says `update-image.sh` must be replaced once (the image is now signed with the
//! release key, and only the new script checks it), the step behind its ⓘ, until the parent dismisses it
//! (`POST /updates/image-script/done`, stored in `dismissed_notices`, migration 0169): the server can't see the
//! host's script.

use askama::Template;
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Redirect, Response};

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
    /// A Docker install's line that update-image.sh must be replaced once, until dismissed.
    image_script_notice: bool,
    /// The newer release's version ("0.67.0"), when one is out: a pinned `HANDY_VERSION` is set to it.
    newer_version: Option<String>,
    /// `SERVER_RELEASE_REPO` when it isn't the project's (security QA L1): the command shown stays the
    /// project's, and a line names the other repo - `.env` is the service user's, so the page never
    /// quietly points the owner's root shell at it.
    other_repo: Option<String>,
    /// This server's clock is off against GitHub's (`clock_skew::server_clock_off`): an orange line.
    server_clock_off: bool,
}
crate::i18n::words!(UpdatesTemplate);

/// The page from what was gathered (no network: the tests build it directly).
pub(crate) fn page(
    docker: bool,
    image_script_notice: bool,
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
            image_script_notice: docker && image_script_notice,
            newer_version,
            other_repo: (repo != crate::config::DEFAULT_SERVER_RELEASE_REPO)
                .then(|| repo.to_string()),
            server_clock_off: crate::clock_skew::server_clock_off(),
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
    // A failed read shows the line: it is only a reminder.
    let notice = docker
        && sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM dismissed_notices WHERE key = ?")
            .bind(IMAGE_SCRIPT_NOTICE)
            .fetch_one(&state.db)
            .await
            .map_or(true, |count| count == 0);
    page(docker, notice, app, &state.config.server_release_repo, lang)
}

/// The key of Software Updates' line that update-image.sh must be replaced once.
pub(crate) const IMAGE_SCRIPT_NOTICE: &str = "image_script_signed";

/// `POST /updates/image-script/done`: the parent replaced update-image.sh (or uses Watchtower), so the line goes for
/// good. Back to the group it was in, never to the top of the page.
pub async fn dismiss_image_script_notice(
    lang: Lang,
    axum::extract::State(state): axum::extract::State<AppState>,
) -> Response {
    match sqlx::query("INSERT OR IGNORE INTO dismissed_notices (key) VALUES (?)")
        .bind(IMAGE_SCRIPT_NOTICE)
        .execute(&state.db)
        .await
    {
        Ok(_) => Redirect::to("/updates#app").into_response(),
        Err(e) => {
            tracing::error!("dismissing the update-image.sh notice: {e}");
            crate::i18n::error(
                lang,
                StatusCode::INTERNAL_SERVER_ERROR,
                "error.save_failed_log",
            )
        }
    }
}
