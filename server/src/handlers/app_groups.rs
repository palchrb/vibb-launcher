//! Design 139 §5.3: a phone's app groups on their own editor page (`device_group.html`, tab Devices), linked from
//! Device › Time Rules' Appgrupper (`#groups`).
//!
//! - `GET/POST /devices/{id}/time/groups/new`: a new group; the add lands on its page, `?saved=added#group`.
//! - `GET/POST /devices/{id}/time/groups/{gid}`: the group; a save lands on `?saved=1#group`.
//! - `POST /devices/{id}/time/groups/{gid}/delete`: then Device › Time Rules' `?notice=group_deleted#groups`.
//!
//! The form follows the Bedtime page: Save in the sticky bar with its flash, Navn, Apper (the phone's reported,
//! allowed apps without ours and without the apps of its other groups; a ticked app no longer offered keeps its note),
//! Grense (`partials/budget_rows.html`), Slett gruppe… outside the form. A refusal renders the page again (400) as
//! typed, the reason under its field (`autofocus`, `aria-invalid`) and in the bar. A group of another phone is a 404;
//! one deleted in another tab lands on `#groups` with `notice=group_gone`, nothing written. Unknown phone 404, DB error 500;
//! every write is logged (`app_group_saved`, `app_group_deleted`, the group's name as a parameter) and nudges the phone.

use std::collections::{BTreeMap, BTreeSet};

use askama::Template;
use axum::Extension;
use axum::body::Bytes;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Redirect, Response};

use crate::AppState;
use crate::app_groups::{self, AppGroupRow, MAX_APPS, MAX_NAME_CHARS};
use crate::handlers::schedules::{
    AppChoice, BudgetInput, budget_entered, budget_inputs, field, form_pairs, nudge,
    parse_budget_form, read_failed_in, render, scope_apps, server_error_in,
};
use crate::i18n::{Lang, Words, t, t_params};
use crate::security::CurrentAdmin;

/// A new group's limits: none until the parent sets them.
const NO_LIMITS_JSON: &str = "[null,null,null,null,null,null,null]";

/// The page's fields, from the stored group, a new one's or as typed.
struct GroupForm {
    name: String,
    name_error: Option<String>,
    apps: Vec<AppChoice>,
    apps_error: Option<String>,
}

impl GroupForm {
    /// "3 apps", "None": the Apper row's value.
    fn apps_value(&self, lang: &Lang) -> String {
        let lang = *lang;
        let chosen = self.apps.iter().filter(|a| a.checked).count() as i64;
        if chosen == 0 {
            t(lang, "device.exceptions.value_none").to_string()
        } else {
            crate::i18n::t_count(lang, "device.groups.apps_count", chosen, &[])
        }
    }
}

#[derive(Template)]
#[template(path = "device_group.html")]
struct GroupTemplate {
    lang: Lang,
    title: String,
    device_id: i64,
    /// `None` for a new group (Add, no delete).
    group_id: Option<i64>,
    form: GroupForm,
    /// The 7 limits (`partials/budget_rows.html` reads `budget`).
    budget: Vec<BudgetInput>,
    /// The flash after an add or a save.
    saved: bool,
    /// A refusal's reason, also in the bar.
    refusal: Option<String>,
    /// The stored group doesn't read: what shows empty is saved as shown.
    corrupt: bool,
}
crate::i18n::words!(GroupTemplate);

/// A validated form.
#[derive(Debug, PartialEq, Eq)]
pub(crate) struct GroupInput {
    pub name: String,
    pub apps: Vec<String>,
    pub days_json: String,
}

/// Why a form was refused, by the field it shows under.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum GroupError {
    Name(String),
    Apps(String),
    Day(usize, String),
}

/// The form's apps as posted: valid package names, none of ours, each once, in order.
fn posted_apps(fields: &[(String, String)]) -> Vec<String> {
    let mut seen = BTreeSet::new();
    fields
        .iter()
        .filter(|(k, _)| k == "apps")
        .map(|(_, v)| v.trim())
        .filter(|p| crate::time_rules::valid_package_name(p) && !app_groups::never_grouped(p))
        .filter(|p| seen.insert(p.to_string()))
        .map(str::to_string)
        .collect()
}

/// `name` (1-40 characters, trimmed), `apps` (at least one, at most [MAX_APPS], none in another of the phone's groups
/// - [others]: their names and apps -, D2), `b0`..`b6` (0-1440 or empty). [labels]: the apps' names for a refusal.
pub(crate) fn parse_group_form(
    lang: Lang,
    fields: &[(String, String)],
    others: &[(String, Vec<String>)],
    labels: &BTreeMap<String, String>,
) -> Result<GroupInput, GroupError> {
    let name: String = field(fields, "name")
        .trim()
        .chars()
        .take(MAX_NAME_CHARS)
        .collect();
    if name.trim().is_empty() {
        return Err(GroupError::Name(
            t(lang, "device.groups.refused.name").to_string(),
        ));
    }
    let apps = posted_apps(fields);
    if apps.is_empty() {
        return Err(GroupError::Apps(
            t(lang, "device.groups.refused.no_app").to_string(),
        ));
    }
    if apps.len() > MAX_APPS {
        return Err(GroupError::Apps(
            t(lang, "device.groups.refused.too_many").to_string(),
        ));
    }
    for app in &apps {
        if let Some((group, _)) = others.iter().find(|(_, list)| list.contains(app)) {
            let label = labels.get(app).map_or(app.as_str(), String::as_str);
            return Err(GroupError::Apps(t_params(
                lang,
                "device.groups.refused.in_group",
                &[("app", label), ("group", group)],
            )));
        }
    }
    let days_json = parse_budget_form(lang, fields).map_err(|(i, why)| GroupError::Day(i, why))?;
    Ok(GroupInput {
        name: name.trim().to_string(),
        apps,
        days_json,
    })
}

/// The phone's name, or the answer: 404 for an unknown phone, 500 on a DB error.
async fn phone_or_answer(state: &AppState, id: i64, lang: Lang) -> Result<String, Box<Response>> {
    match sqlx::query_scalar::<_, String>(
        "SELECT d.name FROM devices d JOIN device_policy p ON p.device_id = d.id WHERE d.id = ?",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await
    {
        Ok(Some(name)) => Ok(name),
        Ok(None) => Err(Box::new(crate::i18n::error(
            lang,
            StatusCode::NOT_FOUND,
            "error.device_not_found",
        ))),
        Err(err) => Err(Box::new(read_failed_in(lang, err, "the device"))),
    }
}

/// Device › Time Rules' Appgrupper with `notice`.
fn to_groups(id: i64, notice: &str) -> Response {
    Redirect::to(&format!("/devices/{id}/time?notice={notice}#groups")).into_response()
}

/// The phone's group `gid`, or the answer: another phone's group 404, a deleted one back to `#groups` with
/// `notice=group_gone` (nothing written).
async fn group_or_answer(
    state: &AppState,
    id: i64,
    gid: i64,
    lang: Lang,
) -> Result<AppGroupRow, Box<Response>> {
    match app_groups::one(&state.db, id, gid).await {
        Ok(Some(row)) => Ok(row),
        Ok(None) => match sqlx::query_scalar::<_, i64>("SELECT id FROM app_groups WHERE id = ?")
            .bind(gid)
            .fetch_optional(&state.db)
            .await
        {
            Ok(Some(_)) => Err(Box::new(crate::i18n::error(
                lang,
                StatusCode::NOT_FOUND,
                "error.group_not_found",
            ))),
            Ok(None) => Err(Box::new(to_groups(id, "group_gone"))),
            Err(err) => Err(Box::new(read_failed_in(lang, err, "the app group"))),
        },
        Err(err) => Err(Box::new(read_failed_in(lang, err, "the app group"))),
    }
}

/// What the Apper list may offer: the phone's allowed, reported apps without ours and without the apps of its other
/// groups (D2).
struct Offer {
    offered: BTreeMap<String, String>,
    reported: BTreeMap<String, String>,
}

async fn offer(
    state: &AppState,
    id: i64,
    this: Option<i64>,
    lang: Lang,
) -> Result<Offer, Box<Response>> {
    let scope = scope_apps(state, &[id], true)
        .await
        .map_err(|err| Box::new(read_failed_in(lang, err, "the reported apps")))?;
    let others = others(state, id, this, lang).await?;
    let taken: BTreeSet<&String> = others.iter().flat_map(|(_, apps)| apps).collect();
    let offered = scope
        .offered
        .iter()
        .filter(|(p, _)| !app_groups::never_grouped(p) && !taken.contains(p))
        .map(|(p, l)| (p.clone(), l.clone()))
        .collect();
    Ok(Offer {
        offered,
        reported: scope.reported,
    })
}

impl Offer {
    /// Every offered app, then every chosen one the list doesn't offer, ticked, with its note (nothing stored
    /// disappears from the form; a save writes what is shown ticked).
    fn choices(&self, chosen: &[String]) -> Vec<AppChoice> {
        let mut apps: Vec<AppChoice> = self
            .offered
            .iter()
            .map(|(package, label)| AppChoice {
                checked: chosen.contains(package),
                package: package.clone(),
                label: label.clone(),
                note: None,
            })
            .collect();
        for package in chosen {
            if self.offered.contains_key(package) || apps.iter().any(|a| &a.package == package) {
                continue;
            }
            let reported = self.reported.get(package);
            apps.push(AppChoice {
                package: package.clone(),
                label: reported.cloned().unwrap_or_else(|| package.clone()),
                checked: true,
                note: Some(if reported.is_some() {
                    "device.exceptions.not_allowed"
                } else {
                    "device.exceptions.not_installed"
                }),
            });
        }
        apps.sort_by_key(|a| a.label.to_lowercase());
        apps
    }
}

/// GET `/devices/{id}/time/groups/new`: an empty group.
pub async fn new_group(State(state): State<AppState>, lang: Lang, Path(id): Path<i64>) -> Response {
    if let Err(answer) = phone_or_answer(&state, id, lang).await {
        return *answer;
    }
    let offer = match offer(&state, id, None, lang).await {
        Ok(offer) => offer,
        Err(answer) => return *answer,
    };
    render(
        GroupTemplate {
            lang,
            title: t(lang, "device.groups.new").to_string(),
            device_id: id,
            group_id: None,
            form: GroupForm {
                name: String::new(),
                name_error: None,
                apps: offer.choices(&[]),
                apps_error: None,
            },
            budget: budget_inputs(lang, NO_LIMITS_JSON),
            saved: false,
            refusal: None,
            corrupt: false,
        },
        StatusCode::OK,
    )
}

/// GET `/devices/{id}/time/groups/{gid}`.
pub async fn show_group(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, gid)): Path<(i64, i64)>,
    Query(params): Query<BTreeMap<String, String>>,
) -> Response {
    if let Err(answer) = phone_or_answer(&state, id, lang).await {
        return *answer;
    }
    let row = match group_or_answer(&state, id, gid, lang).await {
        Ok(row) => row,
        Err(answer) => return *answer,
    };
    let offer = match offer(&state, id, Some(gid), lang).await {
        Ok(offer) => offer,
        Err(answer) => return *answer,
    };
    render(
        GroupTemplate {
            lang,
            title: row.name.clone(),
            device_id: id,
            group_id: Some(gid),
            form: GroupForm {
                name: row.name.clone(),
                name_error: None,
                apps: offer.choices(&row.apps()),
                apps_error: None,
            },
            budget: budget_inputs(lang, &row.days_json),
            saved: params.contains_key("saved"),
            refusal: None,
            corrupt: row.to_policy().is_err(),
        },
        StatusCode::OK,
    )
}

/// A refused form: the page again (400) as typed, the reason under its field and in the bar.
async fn refuse(
    state: &AppState,
    id: i64,
    row: Option<&AppGroupRow>,
    fields: &[(String, String)],
    error: GroupError,
    lang: Lang,
) -> Response {
    let offer = match offer(state, id, row.map(|r| r.id), lang).await {
        Ok(offer) => offer,
        Err(answer) => return *answer,
    };
    let chosen: Vec<String> = posted_apps(fields).into_iter().take(MAX_APPS * 2).collect();
    let mut form = GroupForm {
        name: field(fields, "name").chars().take(MAX_NAME_CHARS).collect(),
        name_error: None,
        apps: offer.choices(&chosen),
        apps_error: None,
    };
    let mut budget = budget_entered(lang, fields, &(usize::MAX, String::new()));
    let refusal = match error {
        GroupError::Name(why) => {
            form.name_error = Some(why.clone());
            why
        }
        GroupError::Apps(why) => {
            form.apps_error = Some(why.clone());
            why
        }
        GroupError::Day(i, why) => {
            budget = budget_entered(lang, fields, &(i, why.clone()));
            why
        }
    };
    render(
        GroupTemplate {
            lang,
            title: row.map_or_else(
                || t(lang, "device.groups.new").to_string(),
                |r| r.name.clone(),
            ),
            device_id: id,
            group_id: row.map(|r| r.id),
            form,
            budget,
            saved: false,
            refusal: Some(refusal),
            corrupt: row.is_some_and(|r| r.to_policy().is_err()),
        },
        StatusCode::BAD_REQUEST,
    )
}

/// The labels of the phone's reported apps, for a refusal's words.
async fn labels(
    state: &AppState,
    id: i64,
    lang: Lang,
) -> Result<BTreeMap<String, String>, Box<Response>> {
    scope_apps(state, &[id], true)
        .await
        .map(|scope| scope.reported)
        .map_err(|err| Box::new(read_failed_in(lang, err, "the reported apps")))
}

/// The others of the phone's groups (D2), names and apps.
async fn others(
    state: &AppState,
    id: i64,
    this: Option<i64>,
    lang: Lang,
) -> Result<Vec<(String, Vec<String>)>, Box<Response>> {
    app_groups::of_device(&state.db, id)
        .await
        .map(|rows| {
            rows.into_iter()
                .filter(|g| Some(g.id) != this)
                .map(|g| (g.name.clone(), g.apps()))
                .collect()
        })
        .map_err(|err| Box::new(read_failed_in(lang, err, "the app groups")))
}

/// The security log's line: the phone and the group's name.
async fn log(state: &AppState, admin: &crate::models::AdminUser, event: &str, id: i64, name: &str) {
    crate::security::record_security_event(
        &state.db,
        event,
        Some(&admin.username),
        None,
        Some(
            crate::security::Detail::words(
                "security.detail.app_group",
                &[("device", &id.to_string()), ("group", name)],
            )
            .with_facts(format!("device {id}")),
        ),
    )
    .await;
}

/// POST `/devices/{id}/time/groups/new`: then the new group's page.
pub async fn create_group(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    body: Bytes,
) -> Response {
    let fields = form_pairs(&body);
    if let Err(answer) = phone_or_answer(&state, id, lang).await {
        return *answer;
    }
    let (others, labels) = match (
        others(&state, id, None, lang).await,
        labels(&state, id, lang).await,
    ) {
        (Ok(o), Ok(l)) => (o, l),
        (Err(answer), _) | (_, Err(answer)) => return *answer,
    };
    let input = match parse_group_form(lang, &fields, &others, &labels) {
        Ok(input) => input,
        Err(error) => return refuse(&state, id, None, &fields, error, lang).await,
    };
    let gid: i64 = match sqlx::query_scalar(
        "INSERT INTO app_groups (device_id, name, apps_json, days_json) VALUES (?, ?, ?, ?) RETURNING id",
    )
    .bind(id)
    .bind(&input.name)
    .bind(serde_json::to_string(&input.apps).expect("strings serialize"))
    .bind(&input.days_json)
    .fetch_one(&state.db)
    .await
    {
        Ok(gid) => gid,
        Err(err) => return server_error_in(lang, err, "failed to add an app group"),
    };
    log(&state, &admin, "app_group_saved", id, &input.name).await;
    nudge(&state, Some(id)).await;
    Redirect::to(&format!(
        "/devices/{id}/time/groups/{gid}?saved=added#group"
    ))
    .into_response()
}

/// POST `/devices/{id}/time/groups/{gid}`: then its form.
pub async fn save_group(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path((id, gid)): Path<(i64, i64)>,
    body: Bytes,
) -> Response {
    let fields = form_pairs(&body);
    if let Err(answer) = phone_or_answer(&state, id, lang).await {
        return *answer;
    }
    let row = match group_or_answer(&state, id, gid, lang).await {
        Ok(row) => row,
        Err(answer) => return *answer,
    };
    let (others, labels) = match (
        others(&state, id, Some(gid), lang).await,
        labels(&state, id, lang).await,
    ) {
        (Ok(o), Ok(l)) => (o, l),
        (Err(answer), _) | (_, Err(answer)) => return *answer,
    };
    let input = match parse_group_form(lang, &fields, &others, &labels) {
        Ok(input) => input,
        Err(error) => return refuse(&state, id, Some(&row), &fields, error, lang).await,
    };
    match sqlx::query(
        "UPDATE app_groups SET name = ?, apps_json = ?, days_json = ? WHERE id = ? AND device_id = ?",
    )
    .bind(&input.name)
    .bind(serde_json::to_string(&input.apps).expect("strings serialize"))
    .bind(&input.days_json)
    .bind(gid)
    .bind(id)
    .execute(&state.db)
    .await
    {
        // Deleted between the read and the write: as a group deleted in another tab.
        Ok(done) if done.rows_affected() == 0 => return to_groups(id, "group_gone"),
        Ok(_) => {}
        Err(err) => return server_error_in(lang, err, "failed to save an app group"),
    }
    log(&state, &admin, "app_group_saved", id, &input.name).await;
    nudge(&state, Some(id)).await;
    Redirect::to(&format!("/devices/{id}/time/groups/{gid}?saved=1#group")).into_response()
}

/// POST `/devices/{id}/time/groups/{gid}/delete`: then Device › Time Rules' Appgrupper.
pub async fn delete_group(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path((id, gid)): Path<(i64, i64)>,
) -> Response {
    if let Err(answer) = phone_or_answer(&state, id, lang).await {
        return *answer;
    }
    let row = match group_or_answer(&state, id, gid, lang).await {
        Ok(row) => row,
        Err(answer) => return *answer,
    };
    match sqlx::query("DELETE FROM app_groups WHERE id = ? AND device_id = ?")
        .bind(gid)
        .bind(id)
        .execute(&state.db)
        .await
    {
        Ok(done) if done.rows_affected() == 0 => return to_groups(id, "group_gone"),
        Ok(_) => {}
        Err(err) => return server_error_in(lang, err, "failed to delete an app group"),
    }
    log(&state, &admin, "app_group_deleted", id, &row.name).await;
    nudge(&state, Some(id)).await;
    to_groups(id, "group_deleted")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pairs(list: &[(&str, &str)]) -> Vec<(String, String)> {
        list.iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    }

    #[test]
    fn the_group_form() {
        let labels: BTreeMap<String, String> =
            [("org.example.game".to_string(), "Game".to_string())].into();
        let others = vec![("Spill".to_string(), vec!["org.example.game".to_string()])];
        let ok = parse_group_form(
            Lang::En,
            &pairs(&[
                ("name", "  Video  "),
                ("apps", "org.example.video"),
                ("apps", "org.example.video"),
                ("apps", "me.vibb.music"),
                ("b0", "60"),
                ("b5", "0"),
            ]),
            &others,
            &labels,
        )
        .unwrap();
        assert_eq!(ok.name, "Video");
        assert_eq!(ok.apps, ["org.example.video"]);
        assert_eq!(ok.days_json, "[60,null,null,null,null,0,null]");

        let refused = |list: &[(&str, &str)]| {
            parse_group_form(Lang::En, &pairs(list), &others, &labels).unwrap_err()
        };
        assert_eq!(
            refused(&[("name", " "), ("apps", "org.example.video")]),
            GroupError::Name("Give the group a name".into())
        );
        assert_eq!(
            refused(&[("name", "A"), ("apps", "me.vibb.launcher")]),
            GroupError::Apps("Choose at least one app".into())
        );
        assert_eq!(
            refused(&[("name", "A"), ("apps", "org.example.game")]),
            GroupError::Apps("Game is in Spill".into())
        );
        let many: Vec<String> = (0..=MAX_APPS)
            .map(|i| format!("org.example.a{i}"))
            .collect();
        let mut list = vec![("name", "A")];
        list.extend(many.iter().map(|p| ("apps", p.as_str())));
        assert_eq!(refused(&list), GroupError::Apps("At most 50 apps".into()));
        assert!(matches!(
            refused(&[("name", "A"), ("apps", "org.example.video"), ("b2", "1441")]),
            GroupError::Day(2, _)
        ));
        // A long name is cut to 40 characters.
        let long = "x".repeat(60);
        let cut = parse_group_form(
            Lang::En,
            &pairs(&[("name", &long), ("apps", "org.example.video")]),
            &others,
            &labels,
        )
        .unwrap();
        assert_eq!(cut.name.chars().count(), MAX_NAME_CHARS);
    }
}
