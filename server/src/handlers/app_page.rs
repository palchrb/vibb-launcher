//! An app's own page on a phone (design 127 step 1, `docs/design/127-app-page.md` at the monorepo root): Device › Apps ›
//! <app>, `GET /devices/{id}/apps/{package}`. The head (the tile, the name, the version, Tillatt and an update held for
//! the parent), Vises som (the Home tile beside the name, Logo · Automatisk · Symbol, every control saving at once),
//! Tilgang (the camera switch and the special accesses as quiet values) and Låseskjerm (Mediespiller). The list keeps
//! one row per app: its name opens this page, its switch stays on the row.
//!
//! Every allowed app with a package name gets a page, and Meldinger; not Vibb One (its catalog page has its versions),
//! Kalender (Device › Calendar), the managed dialer (Calls) or a row that isn't allowed. Such a package - or one the
//! phone no longer has - is a `303` to the list, at its row when it has one, never a 404: a page in the history must
//! not become an error.

use askama::Template;
use axum::extract::{Path, Query, State};
use axum::response::{Html, IntoResponse, Redirect, Response};

use crate::AppState;
use crate::app_display::{self as display, AppDisplayForm, DisplayValues};
use crate::handlers::device_pages::{AppsData, AppsFrom, Tab, apps_url};
use crate::i18n::{Lang, Words, t, t_params};
use crate::models::Device;

/// The app's page's address: `params` (`notice=…`, `held=…`, or empty), then `from` kept, then the fragment (without
/// `#`; empty for none). `package` is a valid package name (no character needs escaping).
pub(crate) fn page_url(
    device_id: i64,
    package: &str,
    params: &str,
    from: Option<AppsFrom>,
    fragment: &str,
) -> String {
    let from = from.map(|f| format!("from={}", f.value()));
    let query: Vec<&str> = [Some(params), from.as_deref()]
        .into_iter()
        .flatten()
        .filter(|p| !p.is_empty())
        .collect();
    let mut url = format!("/devices/{device_id}/apps/{package}");
    if !query.is_empty() {
        url.push('?');
        url.push_str(&query.join("&"));
    }
    if !fragment.is_empty() {
        url.push('#');
        url.push_str(fragment);
    }
    url
}

/// What an answer brings back to the page: a refused Vises som save's values (a 400), a refused save's code
/// (`?notice=`, shown at its group) and Approve's or Keep's word (`?held=`).
#[derive(Default)]
pub(crate) struct Shown {
    pub entered: Option<display::Entered>,
    pub notice: Option<String>,
    pub held: Option<String>,
}

/// The tile on the band and in the head (design 87 §8.1, the list's rule): the real logo over the letter, else the
/// chosen glyph on its colour, else the letter. The band's `app` macro reads these four.
pub(crate) struct PageTile {
    pub logo: Option<String>,
    pub icon: Option<(String, &'static str)>,
    pub letter: String,
    pub colour: &'static str,
}

/// The head's Tillatt switch (`/apps/toggle`, `back=app`).
pub(crate) struct AllowSwitch {
    pub package_name: String,
    pub tracked_app_id: Option<i64>,
    /// Not preinstalled: off removes the app (its ⓘ says so).
    pub removable: bool,
}

#[derive(Template)]
#[template(path = "device_app.html")]
struct AppPageTemplate {
    lang: Lang,
    title: String,
    device: Device,
    nav_tab: Tab,
    back_href: String,
    back_label: String,
    from: Option<String>,
    /// The app's own name (the band's title and the head's).
    name: String,
    tile: PageTile,
    /// The head's sub-lines: the version, the install state (orange when it failed).
    subs: Vec<(String, bool)>,
    /// Design 109: the system Phone app's note while calls are unmanaged, and whether it is an alarm.
    dialer_note: Option<(&'static str, bool)>,
    /// Design 102 §5.2: Chrome is the phone's login browser - a link to Logins.
    login_only: bool,
    allow: Option<AllowSwitch>,
    /// A refused Tillatt, at the head.
    refused: Option<&'static str>,
    /// Design 90: the catalog app's release held for the parent, and its catalog page.
    held: Option<crate::handlers::tracked_apps::HeldView>,
    catalog_id: Option<i64>,
    /// Approve's or Keep's answer (`?held=`).
    held_flash: Option<(String, bool)>,
    form: AppDisplayForm,
    on_home_help: &'static str,
    access: Option<crate::app_permissions::AccessGroup>,
    /// A refused Tilgang or Låseskjerm save, at its group.
    access_refused: Option<&'static str>,
    lock_refused: Option<&'static str>,
    /// The app installs now: the page refreshes itself (`data-waits`), as the list does.
    installing: bool,
}
crate::i18n::words!(AppPageTemplate);

/// `GET /devices/{id}/apps/{package}` (design 127 §2.1).
pub async fn view_app(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, package)): Path<(i64, String)>,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> Response {
    let from = query.get("from").and_then(|f| AppsFrom::parse(f));
    let shown = Shown {
        entered: None,
        notice: query.get("notice").cloned(),
        held: query.get("held").cloned(),
    };
    render(&state, lang, id, &package, from, shown).await
}

/// The app's page, or the list (`303`) when the app has none.
pub(crate) async fn render(
    state: &AppState,
    lang: Lang,
    id: i64,
    package: &str,
    from: Option<AppsFrom>,
    shown: Shown,
) -> Response {
    let data = match AppsData::load(state, lang, id).await {
        Ok(data) => data,
        Err(response) => return *response,
    };
    let builtin = package == crate::builtin::MESSAGES && data.messages_listed();
    let row = if builtin {
        Some(crate::handlers::device_pages::messages_row(
            lang,
            &data.policy,
            data.own.get(package),
        ))
    } else {
        data.rows
            .apps
            .iter()
            .find(|row| row.package_name == package)
            .cloned()
    };
    let row = match row {
        Some(row)
            if row.checked
                && !row.is_launcher
                && display::valid_package_name(package)
                && !data.managed_dialer(package) =>
        {
            row
        }
        // No page: the list, at the row when it has one, with a refusal's reason under it.
        other => {
            let anchor = other
                .map(|row| {
                    crate::handlers::devices::app_anchor(&row.package_name, row.tracked_app_id)
                })
                .unwrap_or_default();
            let params = match (&shown.notice, anchor.is_empty()) {
                (Some(code), false)
                    if crate::handlers::devices::toggle_refusal(lang, code).is_some() =>
                {
                    format!("notice={code}&app={anchor}")
                }
                _ => String::new(),
            };
            return Redirect::to(&apps_url(id, &params, from, &anchor)).into_response();
        }
    };
    let anchor = crate::handlers::devices::app_anchor(package, row.tracked_app_id);
    let name = row.own_label.clone().unwrap_or_else(|| row.label.clone());

    // The tab and the back link (§2.1): the list at the app's row, keeping `from`; from this very app's catalog page,
    // straight back there.
    let (nav_tab, back_href, back_label) = match from {
        Some(AppsFrom::App(app)) if Some(app) == row.tracked_app_id => {
            let catalog: Result<Option<String>, sqlx::Error> =
                sqlx::query_scalar("SELECT name FROM tracked_apps WHERE id = ?")
                    .bind(app)
                    .fetch_optional(&state.db)
                    .await;
            match catalog {
                Ok(Some(catalog)) => (
                    Tab::Apps,
                    format!("/apps/tracked/{app}#phone-{id}"),
                    catalog,
                ),
                Ok(None) => (
                    Tab::Apps,
                    apps_url(id, "", Some(AppsFrom::List), &anchor),
                    t(lang, "nav.apps").to_string(),
                ),
                Err(err) => return read_failed(lang, id, "the catalog app it came from", err),
            }
        }
        Some(from) => (
            Tab::Apps,
            apps_url(id, "", Some(from), &anchor),
            t(lang, "nav.apps").to_string(),
        ),
        None => (
            Tab::Devices,
            apps_url(id, "", None, &anchor),
            t(lang, "nav.apps").to_string(),
        ),
    };

    let current = data.own.get(package);
    let default = data.defaults.get(package);
    let installed = data
        .rows
        .installed
        .iter()
        .find(|a| a.package_name == package);
    let logo = (!builtin)
        .then(|| data.logos.get(package).cloned())
        .flatten();
    let tile = PageTile {
        logo: logo.clone(),
        icon: if builtin {
            let (glyph, tile) = display::MESSAGES_LOOK.shown(current);
            Some((glyph.to_string(), tile))
        } else {
            display::icon_of(&data.own, &data.defaults, package)
        },
        letter: crate::handlers::devices::initial(&row.label),
        colour: "",
    };

    // The head's sub-lines: the version from the last report, the install state (design 113: an installed app says
    // nothing), orange when the install failed.
    let mut subs = Vec::new();
    if let Some(version) = installed.and_then(|a| a.version_name.as_deref()) {
        subs.push((
            t_params(lang, "apps.page.version", &[("version", version)]),
            false,
        ));
    }
    let mut state_line = row.status_label.clone();
    if row.show_no_release_hint {
        if !state_line.is_empty() {
            state_line.push_str(" · ");
        }
        state_line.push_str(t(lang, "device.apps.no_release"));
    }
    if !state_line.is_empty() {
        subs.push((state_line, row.install_failed));
    }

    let allow = (!builtin).then(|| AllowSwitch {
        package_name: package.to_string(),
        tracked_app_id: row.tracked_app_id,
        removable: !installed.is_some_and(|a| a.preinstalled),
    });

    // Design 90: an update held for the parent - Approve and Keep act on the catalog app (every phone).
    let held = match row.tracked_app_id {
        Some(tid) => match crate::handlers::tracked_apps::held_for_page(state, tid, lang).await {
            Ok(held) => held,
            Err(err) => return read_failed(lang, id, "the app's held update", err),
        },
        None => None,
    };

    // Vises som (§2.4).
    let mut form = AppDisplayForm::new(
        lang,
        format!("/devices/{id}/apps/display#display"),
        "display".to_string(),
        package.to_string(),
        name.clone(),
        &current
            .or(default)
            .cloned()
            .unwrap_or_else(DisplayValues::empty),
        shown.entered.as_ref(),
    );
    form.fallback = display::fallback_glyph(current, default);
    if builtin {
        form = form.builtin(display::MESSAGES_LOOK);
    }
    form.logo = logo;
    form.own_row = current.is_some() || data.off_home.contains(package);
    // Design 113: the catalog's default is said only beside Tilbakestill (the phone has its own value).
    form.catalog_default = form
        .can_reset()
        .then(|| default.map(|values| values.describe(lang)))
        .flatten();
    form.from = from.map(AppsFrom::value);
    form.on_home = Some(!data.off_home.contains(package));
    let on_home_help = t(
        lang,
        if builtin {
            "apps.builtin.on_home_sub"
        } else {
            "apps.on_home_sub"
        },
    );

    // Tilgang and Låseskjerm (§2.5, §2.6): a third-party app's; Vibb music has neither (design 127 §5).
    let music = crate::music::MUSIC_PACKAGES.contains(&package);
    let access = (!builtin)
        .then(|| {
            crate::app_permissions::AccessGroup::of(
                lang,
                id,
                package,
                &row.label,
                row.access.as_ref().filter(|_| !music),
                data.camera_off.iter().any(|p| p == package),
                data.paused,
                crate::app_permissions::lock_media_offered(
                    package,
                    row.is_launcher,
                    row.third_party,
                )
                .then(|| data.lock_media.iter().any(|p| p == package)),
            )
        })
        .flatten();

    // A refusal's reason at the group whose save it was.
    let notice = shown.notice.as_deref().and_then(|code| {
        crate::handlers::devices::toggle_refusal(lang, code).map(|text| (code, text))
    });
    let at = |codes: &[&str]| {
        notice
            .filter(|(code, _)| codes.contains(code))
            .map(|(_, text)| text)
    };
    // A group the page doesn't show (the app changed since) leaves its reason at the head.
    let access_refused =
        at(&["perms", "perms_full"]).filter(|_| access.as_ref().is_some_and(|group| group.shown));
    let lock_refused = at(&["lock_media", "lock_media_full"]).filter(|_| {
        access
            .as_ref()
            .is_some_and(|group| group.lock_media.is_some())
    });
    let refused = notice
        .filter(|_| access_refused.is_none() && lock_refused.is_none())
        .map(|(_, text)| text);

    let installing = shown.entered.is_none() && row.is_installing;
    let dialer_note = data.dialer_note(lang, &row);
    let login_only = package == "com.android.chrome"
        && data.policy.login_browser_enabled
        && !crate::logins::chrome_ticked(&data.policy);
    let device = data.device;
    Html(
        AppPageTemplate {
            lang,
            title: format!("{name} · {}", device.name),
            nav_tab,
            back_href,
            back_label,
            from: from.map(AppsFrom::value),
            tile,
            subs,
            dialer_note,
            login_only,
            allow,
            refused,
            held,
            catalog_id: row.tracked_app_id,
            held_flash: shown
                .held
                .as_deref()
                .and_then(|word| crate::handlers::tracked_apps::held_flash(lang, word)),
            form,
            on_home_help,
            access,
            access_refused,
            lock_refused,
            installing,
            name,
            device,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

fn read_failed(lang: Lang, id: i64, what: &str, err: sqlx::Error) -> Response {
    tracing::error!(device_id = id, %err, "can't read {what}");
    crate::i18n::error(
        lang,
        axum::http::StatusCode::INTERNAL_SERVER_ERROR,
        "error.read_phone",
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_address_keeps_from_between_the_params_and_the_fragment() {
        assert_eq!(page_url(3, "a.b", "", None, ""), "/devices/3/apps/a.b");
        assert_eq!(
            page_url(3, "a.b", "notice=perms", Some(AppsFrom::App(4)), "access"),
            "/devices/3/apps/a.b?notice=perms&from=app:4#access"
        );
        assert_eq!(
            page_url(3, "a.b", "", Some(AppsFrom::List), "display"),
            "/devices/3/apps/a.b?from=apps#display"
        );
    }
}
