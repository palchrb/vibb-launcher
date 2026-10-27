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
use crate::i18n::{Lang, Words};

#[derive(Template)]
#[template(path = "updates.html")]
struct UpdatesTemplate {
    lang: Lang,
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
crate::i18n::words!(UpdatesTemplate);

pub(crate) async fn render_page(
    state: &AppState,
    flash: Option<Flash>,
    lang: Lang,
) -> Html<String> {
    let docker = state.net.deploy_kind == crate::net::DeployKind::Docker;
    let app = system_update::gather(state).await;
    let os = if docker {
        system_maintenance::not_managed()
    } else {
        system_maintenance::gather(&state.data_dir).await
    };
    page(docker, app, os, flash, lang)
}

/// The page from what was gathered (no network: the tests build it directly).
pub(crate) fn page(
    docker: bool,
    app: system_update::AppUpdateData,
    os: system_maintenance::OsUpdateData,
    flash: Option<Flash>,
    lang: Lang,
) -> Html<String> {
    let docker_version = app
        .latest_version
        .as_deref()
        .filter(|_| docker && app.update_available)
        .map(|version| version.trim_start_matches('v').to_string());
    Html(
        UpdatesTemplate {
            lang,
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

/// What a form that worked started or saved (qa-31-s14-code M1): the POST redirects to
/// `/updates?done=<code>#<group>` and the GET shows its line there, so a reload never asks for a
/// second update, restart or reboot. Refusals (nothing started) render in the POST's answer.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Done {
    Update,
    Restart,
    OsCheck,
    OsUpgrade,
    Tailscale,
    Reboot,
    AppSchedule,
    OsSchedule,
}

impl Done {
    const ALL: [Done; 8] = [
        Done::Update,
        Done::Restart,
        Done::OsCheck,
        Done::OsUpgrade,
        Done::Tailscale,
        Done::Reboot,
        Done::AppSchedule,
        Done::OsSchedule,
    ];

    fn code(self) -> &'static str {
        match self {
            Done::Update => "update",
            Done::Restart => "restart",
            Done::OsCheck => "os_check",
            Done::OsUpgrade => "os_upgrade",
            Done::Tailscale => "tailscale",
            Done::Reboot => "reboot",
            Done::AppSchedule => "app_schedule",
            Done::OsSchedule => "os_schedule",
        }
    }

    /// Its group on the page (the fragment, and where [Flash] shows).
    pub(crate) fn group(self) -> &'static str {
        match self {
            Done::Update | Done::Restart => "app",
            Done::OsCheck | Done::OsUpgrade | Done::Reboot => "os",
            Done::Tailscale => "tailscale",
            Done::AppSchedule => "app-schedule",
            Done::OsSchedule => "os-schedule",
        }
    }

    fn parse(code: &str) -> Option<Done> {
        Done::ALL.into_iter().find(|done| done.code() == code)
    }

    pub(crate) fn flash(self) -> Flash {
        let text = match self {
            Done::Update => {
                "Update started. The server downloads the new version and restarts by itself; \
                 reload this page in a minute."
            }
            Done::Restart => "Restart started. Reload this page in a few seconds.",
            Done::OsCheck => "Checking for updates. Reload this page in a few seconds.",
            Done::OsUpgrade => {
                "Installing updates. A Pi's first upgrade can take a long time; reload this page \
                 later."
            }
            Done::Tailscale => "Updating Tailscale. Reload this page in a few seconds.",
            Done::Reboot => "The Pi is restarting. Reload this page in about a minute.",
            Done::AppSchedule | Done::OsSchedule => "Schedule saved.",
        };
        Flash::done(self.group(), text)
    }

    pub(crate) fn redirect(self) -> axum::response::Response {
        axum::response::Redirect::to(&format!("/updates?done={}#{}", self.code(), self.group()))
            .into_response()
    }
}

/// Asks the root helper for `action` (its request file in `data_dir`): done, back to the page with
/// `done`'s line (a 303, so a reload asks nothing again); the file can't be written, the page
/// renders in the answer with why nothing started (500).
pub(crate) async fn request_action(
    state: &AppState,
    admin: &crate::models::AdminUser,
    action: &str,
    event_type: &str,
    done: Done,
    lang: Lang,
) -> axum::response::Response {
    let path = state.data_dir.join(crate::handlers::backups::FLAG_FILE);
    if tokio::fs::write(path, action).await.is_err() {
        let page = render_page(state, Some(not_requested(done.group())), lang).await;
        return (axum::http::StatusCode::INTERNAL_SERVER_ERROR, page).into_response();
    }
    crate::security::record_security_event(
        &state.db,
        event_type,
        Some(&admin.username),
        None,
        None,
    )
    .await;
    done.redirect()
}

/// The root helper's request file couldn't be written: nothing starts, and why is behind ⓘ.
pub(crate) fn not_requested(at: &'static str) -> Flash {
    Flash::refused(at, "Nothing was started").because(
        "The server couldn't ask its helper on the Pi (the request file couldn't be written). \
         Re-running the installer sets the helper up again.",
    )
}

/// The page; `?done=<code>` (a form that worked, [Done]) shows its line in its group.
pub async fn show_updates_page(
    lang: Lang,
    axum::extract::State(state): axum::extract::State<AppState>,
    axum::extract::Query(query): axum::extract::Query<std::collections::HashMap<String, String>>,
) -> impl IntoResponse {
    let flash = query
        .get("done")
        .and_then(|code| Done::parse(code))
        .map(Done::flash);
    render_page(&state, flash, lang).await
}
