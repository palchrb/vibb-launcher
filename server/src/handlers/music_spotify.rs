//! The Spotify page (design 26c §2, step 1): `/music/spotify` - the family's own developer app
//! (`#app`: the client ID, the redirect URI to paste into the dashboard, how to set one up, Clear)
//! and the logged-in Spotify accounts (`#accounts`: each one's 6-month line, Remove, "Log in with
//! Spotify" and, for a server without https, the paste of the address the browser shows).
//!
//! The login (§2.3): `POST /music/spotify/login` keeps a PKCE pair in memory and answers 303 to
//! Spotify's authorize page, setting the `spotify_login` cookie (hex SHA-256 of the `state`,
//! `SameSite=Lax`, its path the callback's). Spotify sends the browser back to `GET
//! /music/spotify/callback` - reachable without a session (the admin cookie is `SameSite=Strict`,
//! so the browser doesn't send it on that navigation), still behind the admin gate - which needs
//! the cookie and a live `state`, and always answers 303 (the spent code never stays in the
//! history or a `Referer`) to `/music/spotify/done?login=<outcome>`, a same-site step back to the
//! page, where the Strict cookie is sent again. The paste (`POST /music/spotify/paste`) runs the
//! same code path for the loopback redirect, from the admin who started the login.
//!
//! Entries (§3.2, step 2b): `POST /music/spotify/add` turns a share link into a library entry
//! after the add check ([spotify::add_check]: the metadata read with the first account that may,
//! a playlist's first items page with each in turn) and ticks it, in the same transaction, on the
//! phones the parent picked that play as an account; the others are named in the notice.
//!
//! No form makes the page jump to the top: every save redirects to its card's anchor, a refused
//! one (400) shows the page again with the error by the field, which is `autofocus`ed.
//! Nothing secret is ever on the page: the client ID is public by design (it is in the address
//! bar of every login); tokens, the verifier and the code never reach a page, an event or a log.

use std::collections::HashMap;
use std::net::SocketAddr;

use askama::Template;
use axum::extract::{ConnectInfo, Path, Query, State};
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::response::{AppendHeaders, Html, IntoResponse, Redirect, Response};
use axum::{Extension, Form};
use chrono::{DateTime, Utc};

use super::music::{BELOW_ENTRY_LIMIT, NEXT_SORT, log_event, nudge, server_error};
use crate::AppState;
use crate::music::{self, MAX_ENTRIES, MAX_NAME_CHARS};
use crate::music_sweep::parse_stamp;
use crate::security::CurrentAdmin;
use crate::spotify::{self, LoginOutcome};

type FormMap = HashMap<String, String>;

fn field(form: &FormMap, key: &str) -> String {
    form.get(key).cloned().unwrap_or_default()
}

/// "12 Oct 2026".
fn day(time: DateTime<Utc>) -> String {
    time.format("%-d %b %Y").to_string()
}

/// A phone on the add form: its name, whom it plays as (`None` = Spotify is off on it), ticked.
pub struct PhoneChoice {
    pub id: i64,
    pub name: String,
    pub plays_as: Option<String>,
    pub checked: bool,
}

/// The add form (`#add`, §3.2) as shown: the values (as entered after a refusal) and the error by
/// its field.
pub struct AddView {
    pub link: String,
    /// The picked category's id, or "" = by the link's kind.
    pub category: String,
    pub categories: Vec<(i64, String)>,
    pub phones: Vec<PhoneChoice>,
    /// An account that can read is logged in (else the form says to log in first).
    pub usable: bool,
    pub error: Option<String>,
    pub error_field: &'static str,
    /// The link is in the library already: its entry's id (the card's anchor).
    pub existing: Option<i64>,
}

/// After an add (`?added=<id>...#added`): the entry, whether no account may list it (a playlist
/// played as a Spotify context), the phones it was ticked on and those it wasn't (and why).
pub struct AddedView {
    pub id: i64,
    pub name: String,
    pub context: bool,
    pub ticked: Vec<String>,
    pub skipped: Vec<String>,
}

/// One account on the page.
pub struct AccountView {
    pub id: i64,
    pub name: String,
    /// "Logged in 12 Oct 2026 - log in again before 10 Apr 2027".
    pub line: String,
    /// The login ended or ends soon, or its token can't be opened.
    pub warning: Option<String>,
    /// A refused Remove of this account.
    pub remove_error: Option<String>,
}

#[derive(Template)]
#[template(path = "music_spotify.html")]
struct SpotifyTemplate {
    title: String,
    /// The music key is there (else no login can be kept).
    key_available: bool,
    /// A client ID is set (here or in the environment).
    configured: bool,
    /// A client ID and a redirect URI: a login can start.
    ready: bool,
    last_four: String,
    redirect_uri: Option<String>,
    client_from_env: bool,
    redirect_from_env: bool,
    /// The PWA's row exists, or accounts do (Clear has something to do).
    clearable: bool,
    client_value: String,
    redirect_value: String,
    /// What this request suggests (https only for an https request, §2.1).
    suggested: String,
    /// The saved redirect is the loopback one: the paste is how a login ends.
    loopback: bool,
    /// Spotify refused the client ID at an account's last refresh.
    app_refused: bool,
    accounts: Vec<AccountView>,
    dev_mode_users: usize,
    app_error: Option<String>,
    app_error_field: &'static str,
    clear_error: Option<String>,
    login_error: Option<String>,
    /// A refused start from another address (code QA M1): the page there.
    login_elsewhere: Option<String>,
    paste_error: Option<String>,
    paste_value: String,
    /// After a save or a Clear.
    app_notice: Option<String>,
    /// After a login (`?login=`) or a Remove.
    accounts_notice: Option<String>,
    accounts_notice_good: bool,
    loopback_redirect: &'static str,
    add: AddView,
    added: Option<AddedView>,
}

/// What a refused add brings back: the link, the category, the phones, the error and its field,
/// the entry it already is.
#[derive(Default)]
struct AddEntered {
    link: String,
    category: String,
    devices: Vec<i64>,
    error: String,
    field: &'static str,
    existing: Option<i64>,
}

/// What a refused form brings back.
#[derive(Default)]
struct Entered {
    add: Option<AddEntered>,
    app: Option<(String, String, String, &'static str)>,
    clear_error: Option<String>,
    login_error: Option<String>,
    login_elsewhere: Option<String>,
    paste: Option<(String, String)>,
    remove: Option<(i64, String)>,
    /// The page's query (`?login=`, `?saved=`, ...).
    query: HashMap<String, String>,
}

/// ADMIN_CSP with the login form's `form-action` widened to Spotify's accounts service (or the
/// loopback fake): the form's answer is a 303 there, and a browser checks `form-action` on it.
fn page_csp(state: &AppState) -> String {
    let origin = state.spotify.config().accounts_origin();
    crate::admin_auth::ADMIN_CSP.replace(
        "form-action 'self'",
        &format!("form-action 'self' {origin}"),
    )
}

/// The peer is a trusted proxy (its `X-Forwarded-*` count).
fn trusted(state: &AppState, peer: SocketAddr) -> bool {
    state.net.is_trusted(peer.ip())
}

async fn render(
    state: &AppState,
    headers: &HeaderMap,
    peer_trusted: bool,
    status: StatusCode,
    entered: Entered,
) -> Response {
    let page = match page(state, headers, peer_trusted, entered).await {
        Ok(page) => page,
        Err(err) => return server_error(err, "couldn't load the Spotify page"),
    };
    let mut response = (status, Html(page.render().unwrap())).into_response();
    match HeaderValue::from_str(&page_csp(state)) {
        Ok(csp) => {
            response
                .headers_mut()
                .insert(header::CONTENT_SECURITY_POLICY, csp);
        }
        Err(err) => tracing::error!(%err, "spotify: the page's CSP isn't a header value"),
    }
    response
}

async fn page(
    state: &AppState,
    headers: &HeaderMap,
    peer_trusted: bool,
    entered: Entered,
) -> Result<SpotifyTemplate, sqlx::Error> {
    let app = spotify::app(state).await?;
    let rows = spotify::accounts(&state.db).await?;
    let now = state.music_sweep.now();
    let suggested = spotify::suggested_redirect(headers, peer_trusted);
    let query = &entered.query;
    let accounts: Vec<AccountView> = rows
        .iter()
        .map(|row| {
            let ends = row.login_ends();
            let line = match (parse_stamp(&row.authorized_at), ends) {
                (Some(at), Some(ends)) => {
                    format!("Logged in {} - log in again before {}", day(at), day(ends))
                }
                _ => "Logged in".to_string(),
            };
            let warning = if row.needs_login != 0 {
                Some(format!(
                    "Spotify ended this login (a login lasts 6 months, or its access was removed \
                     at spotify.com) - log in again as {}.",
                    row.display_name
                ))
            } else if row.refresh_error.as_deref() == Some("key") {
                Some(format!(
                    "The saved login was locked with another key (a restored backup or a new key \
                     file) - log in again as {}.",
                    row.display_name
                ))
            } else {
                ends.filter(|_| row.login_ending(now)).map(|ends| {
                    if now < ends {
                        format!(
                            "{}'s Spotify login ends on {}: log in again on this page.",
                            row.display_name,
                            ends.format("%-d %b")
                        )
                    } else {
                        format!(
                            "{}'s Spotify login has ended: log in again on this page.",
                            row.display_name
                        )
                    }
                })
            };
            AccountView {
                id: row.id,
                name: row.display_name.clone(),
                line,
                warning,
                remove_error: entered
                    .remove
                    .as_ref()
                    .filter(|(id, _)| *id == row.id)
                    .map(|(_, error)| error.clone()),
            }
        })
        .collect();
    let app_refused = rows
        .iter()
        .any(|row| row.needs_login == 0 && row.refresh_error.as_deref() == Some("invalid_client"));
    let (accounts_notice, accounts_notice_good) = match query.get("login") {
        Some(code) => {
            let newest: Option<String> = sqlx::query_scalar(
                "SELECT display_name FROM music_spotify_accounts \
                 ORDER BY authorized_at DESC, id DESC LIMIT 1",
            )
            .fetch_optional(&state.db)
            .await?;
            match LoginOutcome::text(code, newest.as_deref()) {
                Some((text, good)) => (Some(text), good),
                None => (None, false),
            }
        }
        None if query.contains_key("removed") => (
            Some("Removed - its login is deleted from this server.".to_string()),
            true,
        ),
        None => (None, false),
    };
    let app_notice = if let Some(n) = query.get("saved") {
        Some(match n.parse::<u64>().unwrap_or(0) {
            0 => "Saved.".to_string(),
            1 => "Saved - the account logged in with the old app was removed.".to_string(),
            n => format!("Saved - the {n} accounts logged in with the old app were removed."),
        })
    } else if query.contains_key("cleared") {
        Some("The Spotify app and every Spotify login were removed from this server.".to_string())
    } else {
        None
    };
    let (app_error, app_error_field, client_value, redirect_value) = match entered.app {
        Some((client, redirect, error, field)) => (Some(error), field, client, redirect),
        None => (
            None,
            "",
            app.as_ref()
                .map(|a| a.client_id.clone())
                .unwrap_or_default(),
            app.as_ref()
                .and_then(|a| a.redirect_uri.clone())
                .unwrap_or_else(|| suggested.clone()),
        ),
    };
    let (paste_value, paste_error) = match entered.paste {
        Some((value, error)) => (value, Some(error)),
        None => (String::new(), None),
    };
    let redirect_uri = app.as_ref().and_then(|a| a.redirect_uri.clone());
    let add = add_view(state, &rows, entered.add).await?;
    let added = added_view(state, query).await?;
    Ok(SpotifyTemplate {
        title: "Spotify".to_string(),
        key_available: state.music_key.is_some(),
        configured: app.is_some(),
        ready: app.as_ref().is_some_and(|a| a.ready().is_some()),
        last_four: app
            .as_ref()
            .map(spotify::App::last_four)
            .unwrap_or_default(),
        loopback: redirect_uri.as_deref() == Some(spotify::LOOPBACK_REDIRECT),
        redirect_uri,
        client_from_env: app.as_ref().is_some_and(|a| a.client_from_env),
        redirect_from_env: app.as_ref().is_some_and(|a| a.redirect_from_env),
        clearable: app.as_ref().is_some_and(|a| a.saved) || !rows.is_empty(),
        client_value,
        redirect_value,
        suggested,
        app_refused,
        accounts,
        dev_mode_users: spotify::DEV_MODE_USERS,
        app_error,
        app_error_field,
        clear_error: entered.clear_error,
        login_error: entered.login_error,
        login_elsewhere: entered.login_elsewhere,
        paste_error,
        paste_value,
        app_notice,
        accounts_notice,
        accounts_notice_good,
        loopback_redirect: spotify::LOOPBACK_REDIRECT,
        add,
        added,
    })
}

/// The add form's values: the phones with whom each plays as, the categories, an account that can
/// read; as entered after a refusal.
async fn add_view(
    state: &AppState,
    rows: &[spotify::AccountRow],
    entered: Option<AddEntered>,
) -> Result<AddView, sqlx::Error> {
    let categories: Vec<(i64, String)> =
        sqlx::query_as("SELECT id, name FROM music_categories ORDER BY sort, id")
            .fetch_all(&state.db)
            .await?;
    let phones: Vec<(i64, String, Option<String>)> = sqlx::query_as(
        "SELECT d.id, d.name, a.display_name FROM devices d \
         LEFT JOIN device_policy p ON p.device_id = d.id \
         LEFT JOIN music_spotify_accounts a ON a.id = p.music_spotify_account \
         ORDER BY d.name, d.id",
    )
    .fetch_all(&state.db)
    .await?;
    let entered = entered.unwrap_or_default();
    Ok(AddView {
        link: entered.link,
        category: entered.category,
        categories,
        phones: phones
            .into_iter()
            .map(|(id, name, plays_as)| PhoneChoice {
                checked: entered.devices.contains(&id),
                id,
                name,
                plays_as,
            })
            .collect(),
        usable: rows.iter().any(|row| row.needs_login == 0),
        error: (!entered.error.is_empty()).then_some(entered.error),
        error_field: entered.field,
        existing: entered.existing,
    })
}

/// Ids from a query value: "3,5" (at most [MAX_ENTRIES] of them; anything else is skipped).
fn ids(query: &HashMap<String, String>, key: &str) -> Vec<i64> {
    query
        .get(key)
        .map(|v| {
            v.split(',')
                .filter_map(|id| id.trim().parse().ok())
                .take(MAX_ENTRIES as usize)
                .collect()
        })
        .unwrap_or_default()
}

/// The notice after an add (`?added=<id>&context=1&on=..&off=..&big=..`), when the entry exists.
async fn added_view(
    state: &AppState,
    query: &HashMap<String, String>,
) -> Result<Option<AddedView>, sqlx::Error> {
    let Some(id) = query.get("added").and_then(|id| id.parse::<i64>().ok()) else {
        return Ok(None);
    };
    let Some(name) = sqlx::query_scalar::<_, String>(
        "SELECT name FROM music_entries WHERE id = ? AND source = 'spotify'",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await?
    else {
        return Ok(None);
    };
    let names: HashMap<i64, String> =
        sqlx::query_as::<_, (i64, String)>("SELECT id, name FROM devices")
            .fetch_all(&state.db)
            .await?
            .into_iter()
            .collect();
    let named = |key: &str| -> Vec<String> {
        ids(query, key)
            .into_iter()
            .filter_map(|id| names.get(&id).cloned())
            .collect()
    };
    let mut skipped: Vec<String> = named("off")
        .into_iter()
        .map(|phone| format!("{phone}: not ticked - Spotify is off on this phone"))
        .collect();
    skipped.extend(named("big").into_iter().map(|phone| {
        format!(
            "{phone}: not ticked - its music library would be bigger than {} MB",
            music::MAX_LIBRARY_BYTES / 1_000_000
        )
    }));
    Ok(Some(AddedView {
        id,
        name,
        context: query.get("context").is_some_and(|v| v == "1"),
        ticked: named("on"),
        skipped,
    }))
}

/// `GET /music/spotify[?login=<outcome>|saved=N|cleared=1|removed=1]`.
pub async fn show(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Query(query): Query<HashMap<String, String>>,
) -> Response {
    let entered = Entered {
        query,
        ..Default::default()
    };
    render(
        &state,
        &headers,
        trusted(&state, peer),
        StatusCode::OK,
        entered,
    )
    .await
}

/// `POST /music/spotify/app` (`client_id`, `redirect_uri`, `replace_accounts`): the family's
/// app (§2.1). A client ID is 32 letters and digits, the redirect URI
/// `https://<host>[:port]/music/spotify/callback` or exactly the loopback one; the environment's
/// values win (their fields are read-only). Another client ID with accounts present removes them
/// (their logins belong to the old app) - only with the box ticked.
pub async fn save_app(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<FormMap>,
) -> Response {
    let config = state.spotify.config();
    let client = config
        .env_client_id
        .clone()
        .unwrap_or_else(|| field(&form, "client_id").trim().to_string());
    let redirect = config
        .env_redirect_uri
        .clone()
        .unwrap_or_else(|| field(&form, "redirect_uri").trim().to_string());
    let refuse = |error: &str, field_name: &'static str| Entered {
        app: Some((
            client.clone(),
            redirect.clone(),
            error.to_string(),
            field_name,
        )),
        ..Default::default()
    };
    let peer_trusted = trusted(&state, peer);
    if !spotify::valid_client_id(&client) {
        let entered = refuse(
            "A Client ID is 32 letters and digits - copy it from your app's page on \
             developer.spotify.com (not the Client secret).",
            "client_id",
        );
        return render(
            &state,
            &headers,
            peer_trusted,
            StatusCode::BAD_REQUEST,
            entered,
        )
        .await;
    }
    if !spotify::valid_redirect_uri(&redirect) {
        let entered = refuse(
            &format!(
                "The Redirect URI is https://<this server's address>{} or exactly {}.",
                spotify::CALLBACK_PATH,
                spotify::LOOPBACK_REDIRECT
            ),
            "redirect_uri",
        );
        return render(
            &state,
            &headers,
            peer_trusted,
            StatusCode::BAD_REQUEST,
            entered,
        )
        .await;
    }
    let current = match spotify::app(&state).await {
        Ok(current) => current,
        Err(err) => return server_error(err, "couldn't read the Spotify app"),
    };
    let accounts: i64 = match sqlx::query_scalar("SELECT COUNT(*) FROM music_spotify_accounts")
        .fetch_one(&state.db)
        .await
    {
        Ok(n) => n,
        Err(err) => return server_error(err, "couldn't count the Spotify accounts"),
    };
    let another_client = current.as_ref().is_none_or(|app| app.client_id != client);
    if another_client && accounts > 0 && field(&form, "replace_accounts") != "on" {
        let entered = refuse(
            &format!(
                "Tick the box to save another Client ID: it removes the {accounts} logged-in \
                 Spotify account{} (their logins belong to the old app).",
                if accounts == 1 { "" } else { "s" }
            ),
            "replace_accounts",
        );
        return render(
            &state,
            &headers,
            peer_trusted,
            StatusCode::BAD_REQUEST,
            entered,
        )
        .await;
    }
    let mut removed = 0;
    if another_client && accounts > 0 {
        match spotify::remove_all(&state, false).await {
            Ok((count, phones)) => {
                removed = count;
                nudge(&state, &phones);
            }
            Err(err) => return server_error(err, "couldn't remove the Spotify accounts"),
        }
    }
    let saved = sqlx::query(
        "INSERT INTO music_spotify_app (id, client_id, redirect_uri, saved_at) \
         VALUES (1, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET client_id = excluded.client_id, \
         redirect_uri = excluded.redirect_uri, saved_at = excluded.saved_at",
    )
    .bind(&client)
    .bind(&redirect)
    .bind(crate::music_sweep::stamp(state.music_sweep.now()))
    .execute(&state.db)
    .await;
    if let Err(err) = saved {
        return server_error(err, "couldn't save the Spotify app");
    }
    let last_four = &client[client.len() - 4..];
    let mut detail = format!("Spotify app: client ID ending in {last_four}, redirect {redirect}");
    if removed > 0 {
        detail.push_str(&format!(" ({removed} accounts of the old app removed)"));
    }
    log_event(&state, &admin, "spotify_app_saved", &detail).await;
    // The Spotify entries that failed for want of an app are due now (design 26c step 2b).
    state.music_sweep.wake();
    Redirect::to(&format!("/music/spotify?saved={removed}#app")).into_response()
}

/// `POST /music/spotify/app/clear` (`confirm`): the app row and every account (their sealed
/// tokens; Developer Policy §I "disconnect ... delete"), every phone unbound, the cached tokens
/// forgotten (§2.1). The environment's client ID, if set, stays in effect.
pub async fn clear_app(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<FormMap>,
) -> Response {
    if field(&form, "confirm") != "on" {
        let entered = Entered {
            clear_error: Some(
                "Tick the box to remove the Spotify app and every Spotify login.".to_string(),
            ),
            ..Default::default()
        };
        return render(
            &state,
            &headers,
            trusted(&state, peer),
            StatusCode::BAD_REQUEST,
            entered,
        )
        .await;
    }
    let (removed, phones) = match spotify::remove_all(&state, true).await {
        Ok(done) => done,
        Err(err) => return server_error(err, "couldn't clear the Spotify app"),
    };
    nudge(&state, &phones);
    log_event(
        &state,
        &admin,
        "spotify_app_cleared",
        &format!("Spotify app and {removed} accounts removed"),
    )
    .await;
    Redirect::to("/music/spotify?cleared=1#app").into_response()
}

/// The `spotify_login` cookie: `value` for `max_age` seconds (0 clears it), its path the
/// callback's, `Secure` unless `INSECURE_COOKIES`.
fn login_cookie(state: &AppState, value: &str, max_age: u64) -> String {
    format!(
        "{}={value}; Path={}; Max-Age={max_age}; HttpOnly; SameSite=Lax{}",
        spotify::LOGIN_COOKIE,
        spotify::CALLBACK_PATH,
        if state.spotify.config().insecure_cookies {
            ""
        } else {
            "; Secure"
        }
    )
}

/// The `spotify_login` cookie the browser sent.
fn sent_login_cookie(headers: &HeaderMap) -> Option<String> {
    headers
        .get_all(header::COOKIE)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(';'))
        .find_map(|pair| {
            let (name, value) = pair.trim().split_once('=')?;
            (name == spotify::LOGIN_COOKIE).then(|| value.to_string())
        })
}

/// `POST /music/spotify/login`: a fresh PKCE login (§2.3) - 303 to Spotify's authorize page,
/// with the `spotify_login` cookie.
pub async fn login(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let refuse = |status: StatusCode, error: &str| {
        let entered = Entered {
            login_error: Some(error.to_string()),
            ..Default::default()
        };
        (status, entered)
    };
    let app = match spotify::app(&state).await {
        Ok(app) => app,
        Err(err) => return server_error(err, "couldn't read the Spotify app"),
    };
    let ready = app
        .as_ref()
        .and_then(|app| app.ready())
        .map(|(client, redirect)| (client.to_string(), redirect.to_string()));
    let refused = if state.music_key.is_none() {
        Some(refuse(
            StatusCode::SERVICE_UNAVAILABLE,
            "Spotify logins are off: this server has no key to keep them safe (see the server's \
             log, \"Storytel logins are off: ...\").",
        ))
    } else if ready.is_none() {
        Some(refuse(
            StatusCode::BAD_REQUEST,
            "Set up your Spotify app first: its Client ID and Redirect URI.",
        ))
    } else {
        None
    };
    if let Some((status, entered)) = refused {
        return render(&state, &headers, trusted(&state, peer), status, entered).await;
    }
    let (client, redirect) = ready.expect("checked above");
    // Spotify sends the browser back to the redirect URI's host, and the `spotify_login` cookie
    // is this host's: a start from another address of this server could only end "expired"
    // (code QA M1). The loopback redirect (the paste) works from anywhere.
    let peer_trusted = trusted(&state, peer);
    if let Some(target) = spotify::redirect_host(&redirect)
        && let Some(here) = spotify::request_host(&headers, peer_trusted)
        && here != target
    {
        let entered = Entered {
            login_error: Some(format!(
                "Spotify sends this login back to https://{target}: open this page there to log \
                 in."
            )),
            login_elsewhere: Some(format!("https://{target}/music/spotify#accounts")),
            ..Default::default()
        };
        return render(
            &state,
            &headers,
            peer_trusted,
            StatusCode::BAD_REQUEST,
            entered,
        )
        .await;
    }
    let started = state
        .spotify
        .start_login(&admin.username, &client, &redirect);
    tracing::info!("spotify: a login started");
    (
        AppendHeaders([(
            header::SET_COOKIE,
            login_cookie(&state, &started.cookie, spotify::PENDING_TTL.as_secs()),
        )]),
        Redirect::to(&started.authorize_url),
    )
        .into_response()
}

/// A code as Spotify sends it: visible ASCII, at most 4096 characters.
fn code_ok(code: &str) -> bool {
    (1..=4096).contains(&code.len()) && code.bytes().all(|b| b.is_ascii_graphic())
}

/// The callback's and the paste's one code path (§2.3), after the cookie (callback) or the
/// session (paste, `admin`) was checked: the pending login taken out by `state` (a replay finds
/// nothing), the app still the one it started with, then `error` or the code.
async fn finish(
    state: &AppState,
    params: &HashMap<String, String>,
    admin: Option<&str>,
) -> LoginOutcome {
    let Some(pending) = params
        .get("state")
        .and_then(|s| state.spotify.take_pending(s, admin))
    else {
        // Unknown, used, too old - or (the paste) another admin's, which stays in place.
        return LoginOutcome::Expired;
    };
    let app = match spotify::app(state).await {
        Ok(app) => app,
        Err(err) => {
            tracing::error!(%err, "spotify: couldn't read the app at a login");
            return LoginOutcome::Error;
        }
    };
    let Some(app) = app else {
        return LoginOutcome::Off;
    };
    if app.ready() != Some((pending.client_id.as_str(), pending.redirect_uri.as_str())) {
        // The app changed since the login started: its code belongs to the old one.
        return LoginOutcome::Expired;
    }
    if let Some(error) = params.get("error") {
        if error == "access_denied" {
            return LoginOutcome::Cancelled;
        }
        // Spotify's text is never stored: a fixed code.
        crate::security::record_security_event(
            &state.db,
            "spotify_login_failed",
            Some(&pending.admin),
            None,
            Some("authorize_error"),
        )
        .await;
        return LoginOutcome::Refused;
    }
    match params.get("code").filter(|code| code_ok(code)) {
        Some(code) => state.spotify.complete_login(state, pending, code).await,
        None => LoginOutcome::Refused,
    }
}

/// Where every login's end goes: the same-site step to the page (`?login=<outcome>`).
fn after_login(outcome: &LoginOutcome) -> String {
    format!("/music/spotify/done?login={}", outcome.code())
}

/// `GET /music/spotify/callback?code&state` or `?error&state` (§2.3): no session (the admin
/// cookie is `SameSite=Strict`); the `spotify_login` cookie must match the `state` (an authorize
/// URL that leaked can't be completed from another browser) and is cleared. Always a 303, never a
/// page on the code's URL.
pub async fn callback(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    let cookie_ok = match (params.get("state"), sent_login_cookie(&headers)) {
        (Some(login_state), Some(cookie)) => spotify::state_cookie(login_state) == cookie,
        _ => false,
    };
    let outcome = if cookie_ok {
        finish(&state, &params, None).await
    } else {
        tracing::info!("spotify: a callback without its login cookie");
        LoginOutcome::Expired
    };
    (
        AppendHeaders([(header::SET_COOKIE, login_cookie(&state, "", 0))]),
        Redirect::to(&after_login(&outcome)),
    )
        .into_response()
}

/// `GET /music/spotify/done?login=<outcome>`: no session needed and nothing secret - the browser
/// arrived here by the callback's 303 in a navigation Spotify's page started, so the Strict admin
/// cookie isn't sent yet; this page's own navigation to `/music/spotify` is same-site, so it is.
pub async fn done(Query(params): Query<HashMap<String, String>>) -> Response {
    let code = params
        .get("login")
        .filter(|code| LoginOutcome::text(code, None).is_some())
        .map(String::as_str)
        .unwrap_or("expired");
    let target = format!("/music/spotify?login={code}#accounts");
    let page = format!(
        "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"UTF-8\">\n\
         <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n\
         <meta http-equiv=\"refresh\" content=\"0;url={target}\">\n\
         <title>Spotify login · Kids Device MDM</title>\n\
         <link rel=\"stylesheet\" href=\"/static/style.css?v=7\">\n</head>\n<body>\n\
         <main class=\"container\">\n<p>Back to <a href=\"{target}\">the Spotify page</a>.</p>\n\
         </main>\n</body>\n</html>\n"
    );
    ([(header::CACHE_CONTROL, "no-store")], Html(page)).into_response()
}

/// The pasted address (§2.3, the loopback redirect): exactly `http://127.0.0.1:43117/callback`
/// with `state` and `code` (or `error`); its query, else `None`.
fn pasted(text: &str) -> Option<HashMap<String, String>> {
    let url = reqwest::Url::parse(text.trim()).ok()?;
    let base_ok = url.scheme() == "http"
        && url.host_str() == Some("127.0.0.1")
        && url.port() == Some(43117)
        && url.path() == "/callback"
        && url.username().is_empty()
        && url.password().is_none();
    if !base_ok {
        return None;
    }
    let mut params = HashMap::new();
    for (key, value) in url.query_pairs() {
        params
            .entry(key.into_owned())
            .or_insert_with(|| value.into_owned());
    }
    let complete = params.contains_key("state")
        && (params.contains_key("code") || params.contains_key("error"));
    complete.then_some(params)
}

/// `POST /music/spotify/paste` (`url`): the address the browser showed after a login with the
/// loopback redirect - the callback's code path, for the admin who started that login.
pub async fn paste(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<FormMap>,
) -> Response {
    let text = field(&form, "url");
    let Some(params) = pasted(&text) else {
        // Never shown back: a pasted address may hold a code.
        let entered = Entered {
            paste: Some((
                String::new(),
                format!(
                    "Paste the whole address your browser shows after logging in - it starts \
                     with {}?code= (or ?error=).",
                    spotify::LOOPBACK_REDIRECT
                ),
            )),
            ..Default::default()
        };
        return render(
            &state,
            &headers,
            trusted(&state, peer),
            StatusCode::BAD_REQUEST,
            entered,
        )
        .await;
    };
    let outcome = finish(&state, &params, Some(&admin.username)).await;
    // Same-site already (the session is here): straight to the page.
    Redirect::to(&format!("/music/spotify?login={}#accounts", outcome.code())).into_response()
}

/// `POST /music/spotify/accounts/{id}/remove` (`confirm`): unbinds its phones, deletes the row
/// (its token and name), forgets its cached token (§2.2).
pub async fn remove_account(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    Form(form): Form<FormMap>,
) -> Response {
    let exists: Result<Option<i64>, sqlx::Error> =
        sqlx::query_scalar("SELECT id FROM music_spotify_accounts WHERE id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await;
    match exists {
        Ok(Some(_)) => {}
        Ok(None) => return StatusCode::NOT_FOUND.into_response(),
        Err(err) => return server_error(err, "couldn't read a Spotify account"),
    }
    if field(&form, "confirm") != "on" {
        let entered = Entered {
            remove: Some((
                id,
                "Tick the box to remove this account's login.".to_string(),
            )),
            ..Default::default()
        };
        return render(
            &state,
            &headers,
            trusted(&state, peer),
            StatusCode::BAD_REQUEST,
            entered,
        )
        .await;
    }
    match spotify::remove_account(&state, id).await {
        Ok(Some((name, phones))) => {
            nudge(&state, &phones);
            log_event(
                &state,
                &admin,
                "spotify_account_removed",
                &format!("Spotify account {name} removed"),
            )
            .await;
            Redirect::to("/music/spotify?removed=1#accounts").into_response()
        }
        Ok(None) => StatusCode::NOT_FOUND.into_response(),
        Err(err) => server_error(err, "couldn't remove a Spotify account"),
    }
}

/// Whether a phone plays as an account (the gate's condition, `spotify_gate!()`); no row = no such
/// phone.
const PLAYS_SPOTIFY: &str = "SELECT EXISTS(SELECT 1 FROM device_policy p \
     JOIN music_spotify_accounts a ON a.id = p.music_spotify_account \
     WHERE p.device_id = d.id) FROM devices d WHERE d.id = ?";

/// At most this many fields in the add form (a phone per `device`; Storytel's Add's bound).
const MAX_ADD_FIELDS: usize = 200;

/// `POST /music/spotify/add` (`link`, `category`, `device` repeated; design 26c §3.2): a Spotify
/// share link (a URI, an `open.spotify.com` link, or a short link resolved one hop at a time)
/// becomes a library entry - source `spotify`, the canonical target, vibb's key, the name Spotify
/// gives, the category picked or by its kind, order `auto`, `cache` 0 (it streams), resume on -
/// after the add check ([spotify::add_check], within [spotify::ADD_BUDGET] with the short link),
/// and is ticked in the same transaction on each picked phone that plays as an account (within
/// its library's size). The others are named in the notice. Refused (400, nothing stored): no
/// app, no usable account, a link that isn't one, one already in the library, Spotify's refusal,
/// Spotify unreachable, a full library.
pub async fn add(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<Vec<(String, String)>>,
) -> Response {
    let value = |key: &str| {
        form.iter()
            .rev()
            .find(|(name, _)| name == key)
            .map(|(_, v)| v.clone())
            .unwrap_or_default()
    };
    let link_text = value("link");
    let picked = value("category");
    let mut devices: Vec<i64> = Vec::new();
    let mut bad_device = false;
    for (name, v) in &form {
        if name != "device" {
            continue;
        }
        match v.trim().parse::<i64>() {
            Ok(id) if !devices.contains(&id) => devices.push(id),
            Ok(_) => {}
            Err(_) => bad_device = true,
        }
    }
    let peer_trusted = trusted(&state, peer);
    let refuse = |error: String, field: &'static str, existing: Option<i64>| Entered {
        add: Some(AddEntered {
            link: link_text.clone(),
            category: picked.clone(),
            devices: devices.clone(),
            error,
            field,
            existing,
        }),
        ..Default::default()
    };
    macro_rules! refused {
        ($error:expr, $field:expr) => {
            refused!($error, $field, None)
        };
        ($error:expr, $field:expr, $existing:expr) => {
            return render(
                &state,
                &headers,
                peer_trusted,
                StatusCode::BAD_REQUEST,
                refuse($error.to_string(), $field, $existing),
            )
            .await
        };
    }
    if form.len() > MAX_ADD_FIELDS {
        refused!(
            format!("At most {MAX_ADD_FIELDS} phones at once - nothing was added."),
            "device"
        );
    }
    match spotify::app(&state).await {
        Ok(Some(_)) => {}
        Ok(None) => refused!("Set up your Spotify app first.", "link"),
        Err(err) => return server_error(err, "couldn't read the Spotify app"),
    }
    match spotify::readers(&state.db, None).await.map(|r| r.refusal()) {
        Ok(None) => {}
        Ok(Some(spotify::SpotifyError::Reauthorize)) => refused!(
            "The Spotify logins need renewing - log in again with a Spotify account first.",
            "link"
        ),
        Ok(Some(_)) => refused!("Log in with a Spotify account first.", "link"),
        Err(err) => return server_error(err, "couldn't read the Spotify accounts"),
    }
    // The phones picked must exist (before any request); whether each plays as an account is
    // decided in the insert's transaction (qa-26c-step2b-code L2).
    for &device in &devices {
        let row: Result<Option<bool>, sqlx::Error> = sqlx::query_scalar(PLAYS_SPOTIFY)
            .bind(device)
            .fetch_optional(&state.db)
            .await;
        match row {
            Ok(Some(_)) => {}
            Ok(None) => bad_device = true,
            Err(err) => return server_error(err, "couldn't read the phones"),
        }
    }
    if bad_device {
        refused!(
            "One of the phones doesn't exist any more - pick the phones again.",
            "device"
        );
    }
    let deadline = tokio::time::Instant::now() + state.spotify.add_budget();
    let reference = match spotify::parse_link(&link_text) {
        Ok(spotify::Link::Ref(reference)) => reference,
        Ok(spotify::Link::Short(short)) => {
            let resolved = tokio::time::timeout_at(
                deadline,
                spotify::resolve_short_link(state.music_fetch.as_ref(), &short),
            )
            .await;
            match resolved {
                Ok(Ok(reference)) => reference,
                Ok(Err(err)) => refused!(err.message(), "link"),
                Err(_) => refused!("Spotify couldn't be reached - try again.", "link"),
            }
        }
        Err(err) => refused!(err.message(), "link"),
    };
    let target = reference.target();
    let existing: Option<i64> =
        match sqlx::query_scalar("SELECT id FROM music_entries WHERE target = ?")
            .bind(&target)
            .fetch_optional(&state.db)
            .await
        {
            Ok(existing) => existing,
            Err(err) => return server_error(err, "music entry lookup failed"),
        };
    if let Some(id) = existing {
        refused!("Already in the library.", "link", Some(id));
    }
    let categories: Vec<music::MusicCategory> =
        match sqlx::query_as("SELECT * FROM music_categories ORDER BY sort, id")
            .fetch_all(&state.db)
            .await
        {
            Ok(categories) => categories,
            Err(err) => return server_error(err, "music category lookup failed"),
        };
    let category = match picked.trim().parse::<i64>() {
        Ok(id) => match categories.iter().find(|c| c.id == id) {
            Some(c) => c.id,
            None => refused!("That category doesn't exist any more.", "category"),
        },
        Err(_) => match music::default_category_for(&categories, "spotify", Some(&target)) {
            Some(c) => c.id,
            None => refused!("Add a category first (on the Music page).", "category"),
        },
    };
    let check =
        match tokio::time::timeout_at(deadline, spotify::add_check(&state, &reference)).await {
            Ok(Ok(check)) => check,
            Ok(Err(none)) => {
                if let spotify::SpotifyError::Database(err) = &none.err {
                    return server_error(err, "couldn't read the Spotify accounts");
                }
                refused!(spotify::add_refusal(&none), "link")
            }
            Err(_) => refused!("Spotify couldn't be reached - try again.", "link"),
        };
    let name = check
        .meta
        .name
        .as_deref()
        .and_then(|name| music::cut_title(name, MAX_NAME_CHARS))
        // No name from Spotify (a playlist's is optional): the kind's word, never the source's -
        // the name is the kid's tile and lock screen (design 26c step 8, qa-26c-step8-code L2).
        .unwrap_or_else(|| reference.kind.plain_name().to_string());
    // The entry and its ticks in one write transaction: the limit is the INSERT's own condition,
    // each tick's size check and write the import's (`music::tick_fits`).
    let mut on = Vec::new();
    let mut off = Vec::new();
    let mut big = Vec::new();
    let inserted: Result<Option<i64>, sqlx::Error> = async {
        let mut tx = state.db.begin_with("BEGIN IMMEDIATE").await?;
        let id: Option<i64> = sqlx::query_scalar(&format!(
            "INSERT INTO music_entries (name, category_id, source, target, key, play_order, \
             cache, resume, sort) SELECT ?, ?, 'spotify', ?, ?, 'auto', 0, 1, {NEXT_SORT} \
             {BELOW_ENTRY_LIMIT} RETURNING id"
        ))
        .bind(&name)
        .bind(category)
        .bind(&target)
        .bind(reference.key())
        .bind(MAX_ENTRIES)
        .fetch_optional(&mut *tx)
        .await?;
        let Some(id) = id else {
            return Ok(None);
        };
        for &device in &devices {
            let plays: Option<bool> = sqlx::query_scalar(PLAYS_SPOTIFY)
                .bind(device)
                .fetch_optional(&mut *tx)
                .await?;
            if plays != Some(true) {
                off.push(device);
                continue;
            }
            if !music::tick_fits(&mut tx, device, id).await? {
                big.push(device);
                continue;
            }
            sqlx::query(
                "INSERT OR IGNORE INTO device_music_entries (device_id, entry_id) VALUES (?, ?)",
            )
            .bind(device)
            .bind(id)
            .execute(&mut *tx)
            .await?;
            on.push(device);
        }
        tx.commit().await?;
        Ok(Some(id))
    }
    .await;
    let id = match inserted {
        Ok(Some(id)) => id,
        Ok(None) => refused!(
            format!("The library has {MAX_ENTRIES} entries - delete one first."),
            "link"
        ),
        // Added by another request since the check above.
        Err(sqlx::Error::Database(err)) if err.is_unique_violation() => {
            refused!("Already in the library.", "link")
        }
        Err(err) => return server_error(err, "couldn't add a Spotify entry"),
    };
    // Its first check comes before anything waiting (design 21b §2.2): listed within a minute.
    state.music_sweep.wake();
    nudge(&state, &on);
    let list = |phones: &[i64]| {
        phones
            .iter()
            .map(i64::to_string)
            .collect::<Vec<_>>()
            .join(",")
    };
    log_event(
        &state,
        &admin,
        "music_entry_added",
        &format!(
            "entry {id}: Spotify: {name} ({target}){}",
            if on.is_empty() {
                String::new()
            } else {
                format!(", ticked on phones {}", list(&on))
            }
        ),
    )
    .await;
    let mut url = format!("/music/spotify?added={id}");
    if check.context {
        url.push_str("&context=1");
    }
    for (key, phones) in [("on", &on), ("off", &off), ("big", &big)] {
        if !phones.is_empty() {
            url.push_str(&format!("&{key}={}", list(phones)));
        }
    }
    url.push_str("#added");
    Redirect::to(&url).into_response()
}
