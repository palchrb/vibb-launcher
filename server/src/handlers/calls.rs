//! Calls & Contacts (`/devices/{id}/calls`, design 02-calls.md 3.3, rebuilt in design 31 step 8):
//! the phone's call switches (one form, `/calls/settings`), its contacts (from the global
//! `contacts` address book, one form per contact) and Add Contact. Every write runs in a
//! transaction, answers 500 on a database error (nothing half-written) and nudges the phone. The
//! launcher gets all of this as `PolicyResponse.call_policy` (`device_api::build_policy`). The
//! global default country code is on Settings › Phone Setup (`handlers::settings`).
//!
//! Where the forms return (design 31 §5.1): the switches to their group (`#settings`), a contact's
//! forms to its row, open (`?open=<id>#contact-<id>`),
//! Add Contact to the new row, Remove Contact… to `#contacts`. A refusal renders the page (400)
//! with that form open, what was typed and the reason under the field; a contact that is already
//! gone (a second tap, another tab) returns to `#contacts`, saying so when something wasn't saved.

use std::collections::HashMap;

use askama::Template;
use axum::Form;
use axum::extract::{Multipart, Path, Query, State};
use axum::http::{StatusCode, header};
use axum::response::{Html, IntoResponse, Redirect, Response};
use chrono::{DateTime, Utc};

use crate::AppState;
use crate::display_time::{Zone, parse_db_utc};
use crate::handlers::device_api::device_contacts;
use crate::handlers::device_pages::Note;
use crate::i18n::Lang;
use crate::models::{Device, DevicePolicy, MESSAGE_APPS};
use crate::phone;
use crate::photos;

/// Parent-facing names for `MESSAGE_APPS`, same order.
const MESSAGE_APP_LABELS: [&str; 4] = ["No message button", "SMS", "Element X", "Signal / Molly"];

/// How long after a change to `calls_managed` an older status report counts as "waiting for the
/// phone" rather than as the phone's real state (QA 09 #5).
const ROLES_PENDING_SECS: i64 = 5 * 60;
/// The calls page reloads itself (`static/auto-reload.js`) for this long while it waits.
const ROLES_REFRESH_SECS: i64 = 2 * 60;

/// The launcher's package as an adb command names it (behind an ⓘ only, never visible text).
const LAUNCHER_FOR_ADB: &str = "me.vibb.launcher (a debug build: me.vibb.launcher.debug; a launcher from before 2026-10-06: \
     com.kidslauncher.mdm)";

struct SelectOption {
    value: String,
    label: String,
    selected: bool,
}

fn message_app_options(current: Option<&str>, with_default: Option<&str>) -> Vec<SelectOption> {
    let mut options = Vec::new();
    if let Some(default) = with_default {
        let label = MESSAGE_APPS
            .iter()
            .position(|app| *app == default)
            .map(|i| MESSAGE_APP_LABELS[i])
            .unwrap_or(default);
        options.push(SelectOption {
            value: String::new(),
            label: format!("Phone's default ({label})"),
            selected: current.is_none(),
        });
    }
    for (value, label) in MESSAGE_APPS.iter().zip(MESSAGE_APP_LABELS) {
        options.push(SelectOption {
            value: value.to_string(),
            label: label.to_string(),
            selected: current == Some(*value),
        });
    }
    options
}

struct ContactView {
    id: i64,
    name: String,
    number: String,
    inbound: bool,
    outbound: bool,
    show_on_home: bool,
    message_options: Vec<SelectOption>,
    message_address: String,
    /// SHA-256 of the contact's photo (shown via `/contact-photos/{hash}`).
    photo: Option<String>,
    initial: String,
    /// The row's value: "Both ways · Home".
    summary: String,
    /// The row's form is open (`?open=`, or a refusal in it).
    open: bool,
    /// A refusal under a field: `message_address` or `photo`, and why.
    error: Option<(&'static str, String)>,
}

/// First letter of a name, upper-cased, for the placeholder avatar.
fn initial_of(name: &str) -> String {
    name.trim()
        .chars()
        .next()
        .map(|c| c.to_uppercase().collect())
        .unwrap_or_else(|| "?".to_string())
}

/// A contact row's value: which way calls go, and the home-screen button - only for a contact in
/// the phone book, as the launcher shows it (`CallRules.kt`: `outbound && showOnHome`;
/// qa-31-s8-code L2). The switch keeps its value for when the contact goes back in the book.
fn contact_summary(inbound: bool, outbound: bool, home: bool) -> String {
    let calls = match (inbound, outbound) {
        (true, true) => "Both ways",
        (true, false) => "Calls in only",
        (false, true) => "Calls out only",
        (false, false) => "No calls",
    };
    if home && outbound {
        format!("{calls} · Home")
    } else {
        calls.to_string()
    }
}

#[derive(Template)]
#[template(path = "device_calls.html")]
struct DeviceCallsTemplate {
    lang: Lang,
    title: String,
    device: Device,
    managed: bool,
    calls_enabled: bool,
    sms_enabled: bool,
    default_message_options: Vec<SelectOption>,
    contacts: Vec<ContactView>,
    default_country_code: String,
    /// The call report's problems, as orange rows above the groups.
    warnings: Vec<Note>,
    /// The rest of the report (waiting for the phone, the call-screening role): rows in Settings.
    notes: Vec<Note>,
    /// Reload (`static/auto-reload.js`) while the phone confirms a change.
    auto_refresh: bool,
    /// The Contacts group's line (`?notice=`, a contact that is gone).
    contacts_notice: Option<&'static str>,
    /// Add Contact is open (a refusal), with what was typed and the field's reason.
    add_open: bool,
    add_name: String,
    add_number: String,
    add_error: Option<(&'static str, String)>,
}
crate::i18n::words!(DeviceCallsTemplate);

/// What a request shows besides the stored state: an open contact, a notice, a refusal.
#[derive(Default)]
struct Shown<'a> {
    /// `?open=<contact id>`: that contact's form is open.
    open: Option<i64>,
    /// `?notice=` for the Contacts group.
    notice: Option<&'static str>,
    /// A refused Add Contact: the name and number typed, the field and why.
    add: Option<(&'a str, &'a str, &'static str, String)>,
    /// A refused contact save or photo.
    contact: Option<ContactRefusal<'a>>,
}

/// A refused contact save or photo: the contact, the field and why, and the form it sent (`None`
/// for a photo), whose values the row shows again.
struct ContactRefusal<'a> {
    id: i64,
    field: &'static str,
    why: String,
    sent: Option<&'a HashMap<String, String>>,
}

/// `?notice=` codes for the Contacts group.
fn contacts_notice(code: &str) -> Option<&'static str> {
    match code {
        "contact_gone" => Some("That contact was already removed, so nothing was saved."),
        _ => None,
    }
}

pub async fn show_calls(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Query(query): Query<HashMap<String, String>>,
) -> Response {
    let shown = Shown {
        open: query.get("open").and_then(|v| v.parse().ok()),
        notice: query.get("notice").and_then(|code| contacts_notice(code)),
        ..Default::default()
    };
    render(&state, id, StatusCode::OK, shown, lang).await
}

/// The page; a read that fails is a 500, never an empty list (its saves would read as "off").
async fn render(
    state: &AppState,
    id: i64,
    status: StatusCode,
    shown: Shown<'_>,
    lang: Lang,
) -> Response {
    match load_page(state, id, shown, lang).await {
        Ok(Some(page)) => (status, Html(page.render().unwrap())).into_response(),
        Ok(None) => (StatusCode::NOT_FOUND, "Device not found").into_response(),
        Err(err) => {
            tracing::error!(device_id = id, %err, "can't read the calls page");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't read this phone - check the server log.",
            )
                .into_response()
        }
    }
}

async fn load_page(
    state: &AppState,
    id: i64,
    shown: Shown<'_>,
    lang: Lang,
) -> Result<Option<DeviceCallsTemplate>, sqlx::Error> {
    let Some(device) = sqlx::query_as::<_, Device>("SELECT * FROM devices WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await?
    else {
        return Ok(None);
    };
    let Some(policy) =
        sqlx::query_as::<_, DevicePolicy>("SELECT * FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await?
    else {
        return Ok(None);
    };
    let default_country_code: String =
        sqlx::query_scalar("SELECT default_country_code FROM call_settings WHERE id = 1")
            .fetch_one(&state.db)
            .await?;
    let refused = shown.contact.as_ref().map(|r| r.id);
    let contacts: Vec<ContactView> = device_contacts(state, id)
        .await?
        .into_iter()
        .map(|c| {
            let mut view = ContactView {
                initial: initial_of(&c.name),
                id: c.contact_id,
                summary: contact_summary(c.allow_inbound, c.allow_outbound, c.show_on_home),
                name: c.name,
                number: c.phone_number,
                inbound: c.allow_inbound,
                outbound: c.allow_outbound,
                show_on_home: c.show_on_home,
                message_options: message_app_options(
                    c.message_app.as_deref(),
                    Some(&policy.default_message_app),
                ),
                message_address: c.message_address.unwrap_or_default(),
                photo: c.photo_hash,
                open: shown.open == Some(c.contact_id),
                error: None,
            };
            if let Some(refusal) = &shown.contact
                && refusal.id == view.id
            {
                view.open = true;
                view.error = Some((refusal.field, refusal.why.clone()));
                // What the refused form sent, so nothing typed is lost.
                if let Some(sent) = refusal.sent {
                    view.inbound = sent.contains_key("inbound");
                    view.outbound = sent.contains_key("outbound");
                    view.show_on_home = sent.contains_key("show_on_home");
                    let app = sent.get("message_app").map(String::as_str);
                    view.message_options = message_app_options(
                        app.filter(|a| MESSAGE_APPS.contains(a)),
                        Some(&policy.default_message_app),
                    );
                    view.message_address = sent
                        .get("message_address")
                        .map(|s| s.trim().to_string())
                        .unwrap_or_default();
                }
            }
            view
        })
        .collect();
    let report = call_report(state, &policy).await?;
    let (zone, now) = (Zone::load(&state.db).await, Utc::now());
    let (warnings, mut notes): (Vec<Note>, Vec<Note>) = report
        .findings
        .iter()
        .map(|f| f.note(&zone, now))
        .partition(|note| note.warning);
    if report.screening_hint {
        notes.push(screening_note());
    }
    let (add_open, add_name, add_number, add_error) = match shown.add {
        Some((name, number, field, why)) => (
            true,
            name.to_string(),
            number.to_string(),
            Some((field, why)),
        ),
        None => (false, String::new(), String::new(), None),
    };
    // Never on a refusal's page (the answer to a POST): a reload would send it again, or lose
    // what was typed (qa-09-code #9).
    let auto_refresh = report.auto_refresh && add_error.is_none() && refused.is_none();

    Ok(Some(DeviceCallsTemplate {
        lang,
        title: format!("{} - Calls & Contacts", device.name),
        managed: policy.calls_managed,
        calls_enabled: policy.calls_enabled,
        sms_enabled: policy.sms_enabled,
        default_message_options: message_app_options(Some(&policy.default_message_app), None),
        contacts,
        default_country_code,
        warnings,
        notes,
        auto_refresh,
        contacts_notice: shown.notice,
        add_open,
        add_name,
        add_number,
        add_error,
        device,
    }))
}

/// The call-screening role is missing while Vibb is the phone app: a plain row, not a warning
/// (the user's emulator, 2026-10-12), with the adb fix behind its ⓘ. No "accept the prompt":
/// Android refuses exclusive-role prompts under the launcher's DISALLOW_CONFIG_DEFAULT_APPS.
fn screening_note() -> Note {
    Note {
        warning: false,
        title: "Call screening".to_string(),
        value: Some("Not granted".to_string()),
        detail: Some(format!(
            "Vibb One is the phone app and hangs up calls that aren't allowed, but another app could \
             screen incoming calls first. The phone can't tell which apps can. With USB debugging \
             allowed: adb shell cmd role add-role-holder android.app.role.CALL_SCREENING \
             {LAUNCHER_FOR_ADB}"
        )),
        fix: None,
    }
}

/// What the calls page and the device page know about a phone's calls, typed (design 31 M5): the
/// Attention rows take [CallProblem]s, the calls page words each [Finding] as a [Note].
pub(crate) struct CallReport {
    /// In the order the page shows them; [Finding::Waiting] is the one that isn't a problem.
    pub findings: Vec<Finding>,
    /// Vibb is the phone app but doesn't hold the call-screening role: good to know, nothing
    /// wrong (a row, not a warning).
    pub screening_hint: bool,
    /// Waiting for the phone to confirm a role change (reload the page).
    pub auto_refresh: bool,
}

impl CallReport {
    /// What each finding is about (the Attention rows).
    pub fn problems(&self) -> Vec<CallProblem> {
        self.findings.iter().map(Finding::problem).collect()
    }
}

/// One thing the last report says about calls, with what its words need. Nothing a phone sent is
/// shown as visible text except as escaped data behind an ⓘ (a package name, the phone's error).
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum Finding {
    EmergencyCall {
        at: DateTime<Utc>,
    },
    CallbackWindow {
        until: DateTime<Utc>,
    },
    CallScreenFailed {
        at: DateTime<Utc>,
    },
    /// The last report predates a change made less than 5 minutes ago.
    Waiting {
        report: Option<DateTime<Utc>>,
    },
    /// The last report predates a change made 5+ minutes ago.
    Unconfirmed {
        changed: Option<DateTime<Utc>>,
        report: Option<DateTime<Utc>>,
    },
    DialerNotAllowed {
        dialer: String,
    },
    MessagesHidden {
        sms_app: String,
    },
    /// `stopped`: an earlier report had the capability (an older launcher installed since).
    NotEnforced {
        stopped: bool,
    },
    FailClosed,
    NotPhoneApp,
    AnyoneCanCall,
    NoRedirection,
    CallLogUnreadable,
    /// `unreadable`: the copy can't be read; else it couldn't be written.
    RulesAfterRestart {
        unreadable: bool,
    },
    Reported {
        error: String,
    },
    SmsAppAllowed {
        sms_app: String,
    },
}

/// "10:58 · 3 min ago": a report's time with its age (one of them when they read the same).
fn stamp(zone: &Zone, t: DateTime<Utc>, now: DateTime<Utc>) -> String {
    let (when, ago) = (zone.when(t, now), zone.relative(t, now));
    if when == ago {
        when
    } else {
        format!("{when} · {ago}")
    }
}

impl Finding {
    pub fn problem(&self) -> CallProblem {
        match self {
            Finding::EmergencyCall { .. } => CallProblem::EmergencyCall,
            Finding::CallbackWindow { .. } => CallProblem::CallbackWindow,
            Finding::CallScreenFailed { .. } => CallProblem::CallScreenFailed,
            Finding::Waiting { .. } => CallProblem::Waiting,
            Finding::Unconfirmed { .. } => CallProblem::Unconfirmed,
            Finding::DialerNotAllowed { .. } => CallProblem::DialerNotAllowed,
            Finding::MessagesHidden { .. } => CallProblem::MessagesHidden,
            Finding::NotEnforced { .. } => CallProblem::NotEnforced,
            Finding::FailClosed => CallProblem::FailClosed,
            Finding::NotPhoneApp => CallProblem::NotPhoneApp,
            Finding::AnyoneCanCall => CallProblem::AnyoneCanCall,
            Finding::NoRedirection => CallProblem::NoRedirection,
            Finding::CallLogUnreadable => CallProblem::CallLogUnreadable,
            Finding::RulesAfterRestart { .. } => CallProblem::RulesAfterRestart,
            Finding::Reported { .. } => CallProblem::Reported,
            Finding::SmsAppAllowed { .. } => CallProblem::SmsAppAllowed,
        }
    }

    /// The calls page's words (§4.2): the problem's short title and one or two sentences, local
    /// times, an expert detail (an adb command, a package name, the phone's own error) behind the
    /// ⓘ only. Everything but [Finding::Waiting] is a warning.
    pub fn note(&self, zone: &Zone, now: DateTime<Utc>) -> Note {
        let package = |package: &str| Some(format!("Its package name is {package}."));
        let (detail, fix): (String, Option<String>) = match self {
            Finding::EmergencyCall { at } => (
                format!(
                    "The phone called an emergency number ({}).",
                    zone.when(*at, now)
                ),
                None,
            ),
            Finding::CallbackWindow { until } => (
                format!(
                    "Anyone can call this phone until {}, so the emergency services can call back.",
                    zone.until(*until, now)
                ),
                None,
            ),
            Finding::CallScreenFailed { at } => (
                format!(
                    "A call rang ({}) but couldn't show over the lock, so your child may not have \
                     been able to answer.",
                    zone.when(*at, now)
                ),
                Some("Please report this with Vibb One's version (About This Phone).".to_string()),
            ),
            Finding::Waiting { report } => (
                match report {
                    Some(t) => format!(
                        "Its last report ({}) is from before the change. Reload the page in a \
                         minute.",
                        stamp(zone, *t, now)
                    ),
                    None => "Its last report is from before the change. Reload the page in a \
                             minute."
                        .to_string(),
                },
                None,
            ),
            Finding::Unconfirmed { changed, report } => (
                format!(
                    "The phone hasn't reported since the change{}; it may be off or offline. The \
                     notes here are from its last report{}.",
                    changed.map_or(String::new(), |t| format!(" at {}", zone.when(t, now))),
                    report.map_or(String::new(), |t| format!(" ({})", stamp(zone, t, now))),
                ),
                None,
            ),
            Finding::DialerNotAllowed { dialer } => (
                "The phone app isn't allowed under Apps, and calls aren't managed here. Turn it \
                 on under Apps, or turn on Manage calls and SMS."
                    .to_string(),
                package(dialer),
            ),
            Finding::MessagesHidden { sms_app } => (
                "It isn't allowed under Apps, so texts can't be read or sent. Turn it on there."
                    .to_string(),
                package(sms_app),
            ),
            Finding::NotEnforced { stopped: true } => (
                "Vibb One on this phone stopped reporting that it screens calls; an older version may \
                 have been installed."
                    .to_string(),
                None,
            ),
            Finding::NotEnforced { stopped: false } => (
                "Vibb One on this phone is too old to screen calls. Update it.".to_string(),
                None,
            ),
            Finding::FailClosed => (
                "Only emergency calls work until it gets good settings from this server."
                    .to_string(),
                None,
            ),
            Finding::NotPhoneApp => (
                "It can only call emergency numbers, and withheld numbers may ring through. On \
                 the phone, open the home screen and accept \"Set as default phone app\"."
                    .to_string(),
                None,
            ),
            Finding::AnyoneCanCall => (
                "Incoming calls aren't screened, and only emergency calls go out. On the phone, \
                 open the home screen and accept \"Set as default phone app\"."
                    .to_string(),
                None,
            ),
            Finding::NoRedirection => (
                "Calls to numbers that aren't allowed are hung up only after they start."
                    .to_string(),
                Some(format!(
                    "With USB debugging allowed: adb shell cmd role add-role-holder \
                     android.app.role.CALL_REDIRECTION {LAUNCHER_FOR_ADB}"
                )),
            ),
            Finding::CallLogUnreadable => (
                "Vibb One can't read the call log, so after an emergency call the emergency services \
                 may not get through. Check that Vibb One is the phone app."
                    .to_string(),
                None,
            ),
            Finding::RulesAfterRestart { unreadable } => (
                format!(
                    "The phone {} its call rules for right after a restart: until it's unlocked \
                     once, only emergency calls work.{}",
                    if *unreadable {
                        "can't read"
                    } else {
                        "couldn't save"
                    },
                    if *unreadable {
                        ""
                    } else {
                        " It tries again at every sync."
                    }
                ),
                None,
            ),
            Finding::Reported { error } => (
                "Vibb One on the phone reported an error with the call rules.".to_string(),
                Some(format!("The phone said: {error}")),
            ),
            Finding::SmsAppAllowed { sms_app } => (
                "The phone keeps it hidden anyway, since it can chat over RCS. Turn it off under \
                 Apps."
                    .to_string(),
                package(sms_app),
            ),
        };
        Note {
            warning: self.problem().needs_attention(),
            title: self.problem().title().to_string(),
            value: None,
            detail: Some(detail),
            fix,
        }
    }
}

/// How far the latest status report can be trusted after the parent changed `calls_managed`
/// (B1, QA 09 #5). Pure, unit-tested.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum RoleReport {
    /// The report is newer than the change (or nothing changed): its warnings are real - also
    /// when it disagrees with what the page says (that is a warning, never "waiting").
    Current,
    /// Older than a change made less than 5 minutes ago: role warnings are replaced by "waiting".
    Pending,
    /// Older than a change made 5+ minutes ago: the real warnings, plus "not confirmed".
    Unconfirmed,
}

pub(crate) fn role_report(
    reported_at: DateTime<Utc>,
    roles_changed_at: Option<DateTime<Utc>>,
    now: DateTime<Utc>,
) -> RoleReport {
    match roles_changed_at {
        Some(changed) if reported_at < changed => {
            if (now - changed).num_seconds() < ROLES_PENDING_SECS {
                RoleReport::Pending
            } else {
                RoleReport::Unconfirmed
            }
        }
        _ => RoleReport::Current,
    }
}

/// What the parent should know about this phone's calls, from its latest status report: the
/// launcher can't enforce calls (or stopped reporting that it can, QA #22), the dialer,
/// call-redirection or call-screening role isn't ours (security review L7: without the dialer and
/// the screening role nothing filters incoming calls), an error, the fail-closed state, an
/// allowlisted Messages app while SMS is off (QA #8), an unreadable call log (QA step 2 #7), and
/// recent emergency calls / an open callback window (QA #2). Right after the parent changed
/// `calls_managed`, a report from before the change shows "waiting" instead of the role findings -
/// for at most 5 minutes; after that the real findings plus "not confirmed", and a newer report
/// that still disagrees is always a finding (B1, QA 09 #5).
/// Emergency calls are reported whether or not calls are managed now.
pub(crate) async fn call_report(
    state: &AppState,
    policy: &DevicePolicy,
) -> Result<CallReport, sqlx::Error> {
    let mut report = CallReport {
        findings: Vec::new(),
        screening_hint: false,
        auto_refresh: false,
    };
    let latest: Option<(Option<String>, Option<String>, String)> = sqlx::query_as(
        "SELECT capabilities_json, call_state_json, reported_at FROM device_status \
         WHERE device_id = ? ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(policy.device_id)
    .fetch_optional(&state.db)
    .await?;
    let Some((capabilities_json, call_state_json, reported_at)) = latest else {
        return Ok(report);
    };
    let capabilities: Vec<String> = capabilities_json
        .as_deref()
        .and_then(|json| serde_json::from_str(json).ok())
        .unwrap_or_default();
    let capable = capabilities.iter().any(|c| c == CALL_POLICY_CAPABILITY);
    let call_state: serde_json::Value = call_state_json
        .as_deref()
        .and_then(|json| serde_json::from_str(json).ok())
        .unwrap_or_default();
    let flag = |key: &str| call_state.get(key).and_then(serde_json::Value::as_bool);
    let text = |key: &str| {
        call_state
            .get(key)
            .and_then(serde_json::Value::as_str)
            .map(str::to_string)
    };
    let findings = &mut report.findings;
    let now = Utc::now();
    if let Some(at) = text("last_emergency_call_at").and_then(|t| parse_time(&t))
        && now - at < chrono::Duration::days(7)
    {
        findings.push(Finding::EmergencyCall { at });
    }
    if let Some(until) = text("callback_window_until").and_then(|t| parse_time(&t))
        && until > now
    {
        findings.push(Finding::CallbackWindow { until });
    }
    // Handy's lock (step 10, QA 10 #8): the in-call screen didn't come up over the lock for a
    // ringing call - the kid may not have been able to answer.
    if let Some(at) = text("in_call_ui_failed_at").and_then(|t| parse_time(&t))
        && now - at < chrono::Duration::days(7)
    {
        findings.push(Finding::CallScreenFailed { at });
    }

    let reported = parse_db_utc(&reported_at);
    let changed = policy.roles_changed_at.as_deref().and_then(parse_db_utc);
    let roles = reported
        .map(|r| role_report(r, changed, now))
        .unwrap_or(RoleReport::Current);
    match roles {
        RoleReport::Pending => {
            findings.push(Finding::Waiting { report: reported });
            report.auto_refresh =
                changed.is_some_and(|c| (now - c).num_seconds() < ROLES_REFRESH_SECS);
        }
        RoleReport::Unconfirmed => findings.push(Finding::Unconfirmed {
            changed,
            report: reported,
        }),
        RoleReport::Current => {}
    }

    // A new phone starts with only the safe defaults allowed (security review M10); the parent may
    // also have unchecked the phone or SMS app. Say what that costs (QA of the security wave,
    // Medium 3): while calls aren't managed the launcher restricts outgoing calls unless the
    // system dialer is allowed, and it hides an SMS app that isn't.
    let allowlist: Option<Vec<String>> = policy
        .allowlist_json
        .as_deref()
        .and_then(|json| serde_json::from_str(json).ok());
    if let Some(allowed) = &allowlist {
        if !policy.calls_managed
            && let Some(dialer) = text("system_dialer")
            && !allowed.contains(&dialer)
        {
            findings.push(Finding::DialerNotAllowed { dialer });
        }
        if (!policy.calls_managed || policy.sms_enabled)
            && let Some(sms_app) = text("default_sms_package")
            && !allowed.contains(&sms_app)
        {
            findings.push(Finding::MessagesHidden { sms_app });
        }
    }
    if !policy.calls_managed {
        return Ok(report);
    }
    if !capable {
        let stopped: bool = sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM device_status WHERE device_id = ? \
             AND capabilities_json LIKE ?)",
        )
        .bind(policy.device_id)
        .bind(format!("%\"{CALL_POLICY_CAPABILITY}\"%"))
        .fetch_one(&state.db)
        .await?;
        findings.push(Finding::NotEnforced { stopped });
        return Ok(report);
    }
    if roles == RoleReport::Pending {
        // The role and call-log state in this report predates the change.
        return Ok(report);
    }
    if text("state").as_deref() == Some("fail_closed") {
        findings.push(Finding::FailClosed);
    }
    // Security review L7: Telecom binds one screening service besides the carrier's - the
    // call-screening role's holder, else the default phone app's (CallsManager
    // .setUpCallFilterGraph). Without either role nothing screens incoming calls; with another app
    // holding the screening role, that app screens them instead of the launcher. A launcher from
    // before the wave doesn't report the screening role (`None`).
    let screening = flag("screening_role_held");
    if flag("dialer_role_held") == Some(false) {
        findings.push(if screening == Some(true) {
            Finding::NotPhoneApp
        } else {
            Finding::AnyoneCanCall
        });
    } else if screening == Some(false) {
        // A hint, not an error (the user's emulator, 2026-10-12), and nothing it can't know (QA of
        // cmd-expiry, Medium 3): the phone can't read who else holds the role (RoleManager
        // .getRoleHolders is a system API), and another holder screens instead of the launcher.
        report.screening_hint = true;
    }
    if flag("redirection_role_held") == Some(false) {
        findings.push(Finding::NoRedirection);
    }
    if flag("call_log_readable") == Some(false) {
        findings.push(Finding::CallLogUnreadable);
    }
    match text("boot_policy").as_deref() {
        Some("write_failed") => findings.push(Finding::RulesAfterRestart { unreadable: false }),
        Some("unreadable") => findings.push(Finding::RulesAfterRestart { unreadable: true }),
        _ => {}
    }
    if let Some(error) = text("last_error") {
        findings.push(Finding::Reported { error });
    }
    if !policy.sms_enabled
        && let Some(sms_app) = text("default_sms_package")
        && allowlist
            .as_ref()
            .is_some_and(|list| list.contains(&sms_app))
    {
        findings.push(Finding::SmsAppAllowed { sms_app });
    }
    Ok(report)
}

/// What a [Finding] is about (design 31 M5): the Attention row's short title comes from
/// this, never from the warning's first sentence (package names, 40 words).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CallProblem {
    /// An emergency call in the last 7 days.
    EmergencyCall,
    /// The emergency services' callback window is open.
    CallbackWindow,
    /// The in-call screen couldn't come up over the lock.
    CallScreenFailed,
    /// Waiting for the phone to confirm a change (not a problem).
    Waiting,
    /// The phone hasn't confirmed a change made 5+ minutes ago.
    Unconfirmed,
    /// Calls unmanaged and the phone app isn't allowed: emergency calls only.
    DialerNotAllowed,
    /// The SMS app isn't allowed, so the phone hides it.
    MessagesHidden,
    /// The launcher doesn't (or no longer does) enforce calls.
    NotEnforced,
    /// The phone can't read its call rules: emergency calls only.
    FailClosed,
    /// Not the phone app, but incoming calls are still screened.
    NotPhoneApp,
    /// Not the phone app and nothing screens incoming calls.
    AnyoneCanCall,
    /// The call-redirection role is missing.
    NoRedirection,
    /// The call log can't be read (no emergency callback).
    CallLogUnreadable,
    /// The call rules for right after a restart couldn't be saved or read.
    RulesAfterRestart,
    /// The phone reported an error.
    Reported,
    /// SMS is off but the Messages app is allowed.
    SmsAppAllowed,
}

impl CallProblem {
    /// The Attention row's title: at most 8 words, no package names.
    pub fn title(self) -> &'static str {
        match self {
            CallProblem::EmergencyCall => "Emergency call made from this phone",
            CallProblem::CallbackWindow => "Emergency services can call back now",
            CallProblem::CallScreenFailed => "A call may have rung unanswered",
            CallProblem::Waiting => "Waiting for the phone to confirm",
            CallProblem::Unconfirmed => "Calls change not confirmed yet",
            CallProblem::DialerNotAllowed => "Only emergency calls work",
            CallProblem::MessagesHidden => "Messages app is hidden",
            CallProblem::NotEnforced => "Calls aren't screened",
            CallProblem::FailClosed => "Phone can't read its call rules",
            CallProblem::NotPhoneApp => "Vibb One isn't the phone app",
            CallProblem::AnyoneCanCall => "Anyone can call this phone",
            CallProblem::NoRedirection => "Blocked numbers may ring briefly",
            CallProblem::CallLogUnreadable => "Emergency callback may not work",
            CallProblem::RulesAfterRestart => "Calls blocked after a restart",
            CallProblem::Reported => "Phone reported a calls problem",
            CallProblem::SmsAppAllowed => "Messages app allowed with SMS off",
        }
    }

    /// Something to look at (everything but `Waiting`).
    pub fn needs_attention(self) -> bool {
        self != CallProblem::Waiting
    }

    /// Every kind, for the tests.
    #[cfg(test)]
    pub(crate) const ALL: [CallProblem; 16] = [
        CallProblem::EmergencyCall,
        CallProblem::CallbackWindow,
        CallProblem::CallScreenFailed,
        CallProblem::Waiting,
        CallProblem::Unconfirmed,
        CallProblem::DialerNotAllowed,
        CallProblem::MessagesHidden,
        CallProblem::NotEnforced,
        CallProblem::FailClosed,
        CallProblem::NotPhoneApp,
        CallProblem::AnyoneCanCall,
        CallProblem::NoRedirection,
        CallProblem::CallLogUnreadable,
        CallProblem::RulesAfterRestart,
        CallProblem::Reported,
        CallProblem::SmsAppAllowed,
    ];
}

/// Capability the launcher reports when it enforces `call_policy`.
pub(crate) const CALL_POLICY_CAPABILITY: &str = "call_policy_v1";

pub(crate) fn parse_time(value: &str) -> Option<DateTime<Utc>> {
    DateTime::parse_from_rfc3339(value)
        .ok()
        .map(|t| t.with_timezone(&Utc))
}

fn db_error(device_id: i64, err: sqlx::Error) -> Response {
    tracing::error!(device_id, %err, "calls page database error");
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        "Couldn't save - nothing was changed. Check the server log.",
    )
        .into_response()
}

fn not_found() -> Response {
    (StatusCode::NOT_FOUND, "Device not found").into_response()
}

/// Nudges the phone and returns to `to` (a path on its calls page).
fn saved(state: &AppState, id: i64, to: String) -> Response {
    // No receivers just means no phone is connected right now; it syncs on its next poll.
    let _ = state.command_notify.send(id);
    Redirect::to(&to).into_response()
}

/// A contact's row, its form open: where its saves return.
fn contact_row(id: i64, contact_id: i64) -> String {
    format!("/devices/{id}/calls?open={contact_id}#contact-{contact_id}")
}

/// The Contacts group, for a contact that is gone: `gone` says nothing was saved (a refusal,
/// [crate::settings_log::Refused]).
fn contacts_group(id: i64, gone: bool) -> Response {
    let notice = if gone { "?notice=contact_gone" } else { "" };
    let back = Redirect::to(&format!("/devices/{id}/calls{notice}#contacts"));
    if gone {
        crate::settings_log::refused(back)
    } else {
        back.into_response()
    }
}

fn bad_request(message: &str) -> Response {
    (StatusCode::BAD_REQUEST, message.to_string()).into_response()
}

/// Whether the phone exists (`None`: it doesn't) and has this contact.
async fn contact_on_device(
    state: &AppState,
    id: i64,
    contact_id: i64,
) -> Result<Option<bool>, sqlx::Error> {
    let (device, contact): (bool, bool) = sqlx::query_as(
        "SELECT EXISTS(SELECT 1 FROM device_policy WHERE device_id = ?1), \
         EXISTS(SELECT 1 FROM device_contacts WHERE device_id = ?1 AND contact_id = ?2)",
    )
    .bind(id)
    .bind(contact_id)
    .fetch_one(&state.db)
    .await?;
    Ok(device.then_some(contact))
}

/// One form with every switch, so a missing checkbox really means "off". Back to its group,
/// `#settings` (§5.1.4: without JS the answer lands on the form, with JS scroll-restore keeps the
/// exact place).
pub async fn save_settings(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let default_message_app = form
        .get("default_message_app")
        .map(String::as_str)
        .unwrap_or("sms");
    if !MESSAGE_APPS.contains(&default_message_app) {
        return bad_request("Unknown messaging app");
    }
    let managed = form.contains_key("managed");
    let result = async {
        let mut tx = state.db.begin().await?;
        // A change to calls_managed stamps roles_changed_at: reports from before it may still
        // show the old role state (B1).
        let updated = sqlx::query(
            "UPDATE device_policy SET \
             roles_changed_at = CASE WHEN calls_managed != ? THEN datetime('now') \
                                     ELSE roles_changed_at END, \
             calls_managed = ?, calls_enabled = ?, sms_enabled = ?, \
             default_message_app = ?, updated_at = datetime('now') WHERE device_id = ?",
        )
        .bind(managed)
        .bind(managed)
        .bind(form.contains_key("calls_enabled"))
        .bind(form.contains_key("sms_enabled"))
        .bind(default_message_app)
        .bind(id)
        .execute(&mut *tx)
        .await?
        .rows_affected();
        tx.commit().await?;
        Ok::<_, sqlx::Error>(updated)
    }
    .await;
    match result {
        Ok(0) => not_found(),
        Ok(_) => saved(&state, id, format!("/devices/{id}/calls#settings")),
        Err(err) => db_error(id, err),
    }
}

/// Adds a contact to this device: normalises the number with the default country code, reuses
/// the global contact with that number (renaming it - the name is shared by every device that
/// has it) and attaches it at the end of the list with every flag on. Back to its row; a refusal
/// renders the page (400) with Add Contact open, what was typed and the reason under its field.
pub async fn add_contact(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let name = form.get("name").map(|s| s.trim()).unwrap_or("");
    let raw_number = form.get("number").map(String::as_str).unwrap_or("");
    let state = &state;
    let refuse = |field: &'static str, why: String| async move {
        let shown = Shown {
            add: Some((name, raw_number, field, why)),
            ..Default::default()
        };
        render(state, id, StatusCode::BAD_REQUEST, shown, lang).await
    };

    let name_len = name.chars().count();
    if name_len == 0 || name_len > 60 {
        return refuse(
            "name",
            "Give the contact a name (at most 60 characters).".into(),
        )
        .await;
    }
    let cc: String =
        match sqlx::query_scalar("SELECT default_country_code FROM call_settings WHERE id = 1")
            .fetch_one(&state.db)
            .await
        {
            Ok(cc) => cc,
            Err(err) => return db_error(id, err),
        };
    let number = match phone::normalize(raw_number, &cc) {
        Ok(number) => number,
        Err(err) => return refuse("number", format!("That number can't be used: {err}.")).await,
    };

    let result = async {
        let mut tx = state.db.begin().await?;
        let device_exists: bool =
            sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM device_policy WHERE device_id = ?)")
                .bind(id)
                .fetch_one(&mut *tx)
                .await?;
        if !device_exists {
            return Ok(None);
        }
        let contact_id: i64 = sqlx::query_scalar(
            "INSERT INTO contacts (name, phone_number) VALUES (?, ?) \
             ON CONFLICT(phone_number) DO UPDATE SET name = excluded.name RETURNING id",
        )
        .bind(name)
        .bind(&number)
        .fetch_one(&mut *tx)
        .await?;
        sqlx::query(
            "INSERT OR IGNORE INTO device_contacts (device_id, contact_id, sort_order) \
             VALUES (?, ?, (SELECT COALESCE(MAX(sort_order) + 1, 0) FROM device_contacts \
                            WHERE device_id = ?))",
        )
        .bind(id)
        .bind(contact_id)
        .bind(id)
        .execute(&mut *tx)
        .await?;
        tx.commit().await?;
        Ok::<_, sqlx::Error>(Some(contact_id))
    }
    .await;
    match result {
        Ok(None) => not_found(),
        Ok(Some(contact_id)) => saved(
            state,
            id,
            format!("/devices/{id}/calls#contact-{contact_id}"),
        ),
        Err(err) => db_error(id, err),
    }
}

/// A Matrix user ID as Element expects it: `@localpart:server`, no whitespace.
/// The Matrix user-ID grammar (spec "User Identifiers"), the same check as the launcher's
/// `isMatrixId`: localpart `a-z 0-9 . _ = - / +`, server a hostname, IPv4 or `[IPv6]` with an
/// optional `:port` - so `?`, `#`, `&` and spaces never end up in the phone's `matrix:` URI
/// (qa-09-code #8).
fn valid_matrix_id(value: &str) -> bool {
    let Some(rest) = value.strip_prefix('@') else {
        return false;
    };
    let Some((local, server)) = rest.split_once(':') else {
        return false;
    };
    let local_ok = !local.is_empty()
        && local
            .chars()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || "._=-/+".contains(c));
    // "[IPv6]" or a host name / IPv4, then an optional ":port".
    let (host_ok, port) = if let Some(v6) = server.strip_prefix('[') {
        let Some((addr, after)) = v6.split_once(']') else {
            return false;
        };
        let port = match after {
            "" => None,
            _ => match after.strip_prefix(':') {
                Some(port) => Some(port),
                None => return false,
            },
        };
        let ok = !addr.is_empty()
            && addr
                .chars()
                .all(|c| c.is_ascii_hexdigit() || c == ':' || c == '.');
        (ok, port)
    } else {
        let (host, port) = match server.split_once(':') {
            Some((host, port)) => (host, Some(port)),
            None => (server, None),
        };
        let ok = !host.is_empty()
            && host
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-');
        (ok, port)
    };
    let port_ok =
        port.is_none_or(|p| (1..=5).contains(&p.len()) && p.chars().all(|c| c.is_ascii_digit()));
    value.len() <= 255 && local_ok && host_ok && port_ok
}

/// Saves one contact's flags and Message button, scoped to this device. Back to its row, open;
/// a Matrix ID that isn't one renders the page (400) with the row open, what was sent and the
/// reason under the field; a contact that isn't on this phone (removed meanwhile) returns to
/// `#contacts` saying nothing was saved. An unknown phone is a 404.
pub async fn update_contact(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, contact_id)): Path<(i64, i64)>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let message_app = match form.get("message_app").map(String::as_str) {
        None | Some("") => None,
        Some(app) if MESSAGE_APPS.contains(&app) => Some(app),
        Some(_) => return bad_request("Unknown messaging app"),
    };
    let message_address = match form.get("message_address").map(|s| s.trim()) {
        None | Some("") => None,
        Some(mxid) if valid_matrix_id(mxid) => Some(mxid),
        Some(_) => {
            // A contact removed meanwhile (another tab) has no row to open: back to the list,
            // saying nothing was saved (qa-31-s8-code N2).
            match contact_on_device(&state, id, contact_id).await {
                Ok(Some(true)) => {}
                Ok(Some(false)) => return contacts_group(id, true),
                Ok(None) => return not_found(),
                Err(err) => return db_error(id, err),
            }
            let shown = Shown {
                contact: Some(ContactRefusal {
                    id: contact_id,
                    field: "message_address",
                    why: "A Matrix ID looks like @name:matrix.org. Nothing was saved.".to_string(),
                    sent: Some(&form),
                }),
                ..Default::default()
            };
            return render(&state, id, StatusCode::BAD_REQUEST, shown, lang).await;
        }
    };

    let result = async {
        let mut tx = state.db.begin().await?;
        let updated = sqlx::query(
            "UPDATE device_contacts SET allow_inbound = ?, allow_outbound = ?, show_on_home = ?, \
             message_app = ?, message_address = ? WHERE device_id = ? AND contact_id = ?",
        )
        .bind(form.contains_key("inbound"))
        .bind(form.contains_key("outbound"))
        .bind(form.contains_key("show_on_home"))
        .bind(message_app)
        .bind(message_address)
        .bind(id)
        .bind(contact_id)
        .execute(&mut *tx)
        .await?
        .rows_affected();
        tx.commit().await?;
        Ok::<_, sqlx::Error>(updated)
    }
    .await;
    match result {
        Ok(0) => gone(&state, id, contact_id, true).await,
        Ok(_) => saved(&state, id, contact_row(id, contact_id)),
        Err(err) => db_error(id, err),
    }
}

/// A form for a contact that isn't on this phone: an unknown phone is a 404, else back to the
/// Contacts group (`said`: with "nothing was saved"). Nothing was written either way.
async fn gone(state: &AppState, id: i64, contact_id: i64, said: bool) -> Response {
    match contact_on_device(state, id, contact_id).await {
        Ok(None) => not_found(),
        Ok(Some(_)) => crate::settings_log::refused(contacts_group(id, said)),
        Err(err) => db_error(id, err),
    }
}

/// Detaches a contact from this device, and deletes it from the address book once no device has
/// it any more. Back to the Contacts group - also when it was already gone (a second tap).
pub async fn remove_contact(
    State(state): State<AppState>,
    Path((id, contact_id)): Path<(i64, i64)>,
) -> Response {
    let result = async {
        let mut tx = state.db.begin().await?;
        let removed =
            sqlx::query("DELETE FROM device_contacts WHERE device_id = ? AND contact_id = ?")
                .bind(id)
                .bind(contact_id)
                .execute(&mut *tx)
                .await?
                .rows_affected();
        sqlx::query(
            "DELETE FROM contacts WHERE id = ? \
             AND NOT EXISTS (SELECT 1 FROM device_contacts WHERE contact_id = ?)",
        )
        .bind(contact_id)
        .bind(contact_id)
        .execute(&mut *tx)
        .await?;
        tx.commit().await?;
        Ok::<_, sqlx::Error>(removed)
    }
    .await;
    match result {
        Ok(0) => gone(&state, id, contact_id, false).await,
        Ok(_) => {
            // The address-book entry may be gone, and its photo with it.
            photos::prune(&state).await;
            let _ = state.command_notify.send(id);
            contacts_group(id, false)
        }
        Err(err) => db_error(id, err),
    }
}

/// Every device that has this contact - all of them see its photo.
async fn devices_with_contact(state: &AppState, contact_id: i64) -> Result<Vec<i64>, sqlx::Error> {
    sqlx::query_scalar("SELECT device_id FROM device_contacts WHERE contact_id = ?")
        .bind(contact_id)
        .fetch_all(&state.db)
        .await
}

/// Sets (or, with `None`, clears) a contact's photo, prunes files nobody uses any more and nudges
/// every device that has the contact. The caller holds `photos::lock_files()` across its store
/// and this commit ([`photos::prune`] takes it again afterwards).
async fn commit_photo(
    state: &AppState,
    contact_id: i64,
    hash: Option<&str>,
) -> Result<Vec<i64>, sqlx::Error> {
    let mut tx = state.db.begin().await?;
    sqlx::query("UPDATE contacts SET photo_hash = ? WHERE id = ?")
        .bind(hash)
        .bind(contact_id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    devices_with_contact(state, contact_id).await
}

async fn photo_saved(
    state: &AppState,
    id: i64,
    contact_id: i64,
    result: Result<Vec<i64>, sqlx::Error>,
) -> Response {
    match result {
        Ok(devices) => {
            photos::prune(state).await;
            for device in devices {
                let _ = state.command_notify.send(device);
            }
            Redirect::to(&contact_row(id, contact_id)).into_response()
        }
        Err(err) => db_error(id, err),
    }
}

/// Uploads a contact's photo (multipart field `photo`). The photo is decoded and re-encoded
/// (`photos::process`: square JPEG, no metadata) before anything is stored; a file that isn't a
/// JPEG/PNG/WebP photo, is too big or can't be decoded renders the page (400) with the contact
/// open and the reason under its photo, and nothing changes. The photo belongs to the
/// address-book contact, so every device that has it gets it. Back to the contact's row.
pub async fn upload_photo(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, contact_id)): Path<(i64, i64)>,
    mut multipart: Multipart,
) -> Response {
    match contact_on_device(&state, id, contact_id).await {
        Ok(Some(true)) => {}
        Ok(None) => return not_found(),
        Ok(Some(false)) => return contacts_group(id, true),
        Err(err) => return db_error(id, err),
    }
    let photo_error = |message: &str| {
        let state = state.clone();
        let message = message.to_string();
        async move {
            let shown = Shown {
                contact: Some(ContactRefusal {
                    id: contact_id,
                    field: "photo",
                    why: message,
                    sent: None,
                }),
                ..Default::default()
            };
            render(&state, id, StatusCode::BAD_REQUEST, shown, lang).await
        }
    };

    let mut upload = None;
    loop {
        match multipart.next_field().await {
            Ok(Some(field)) if field.name() == Some("photo") => match field.bytes().await {
                Ok(bytes) => upload = Some(bytes),
                Err(_) => return photo_error(photos::PhotoError::TooLarge.message()).await,
            },
            Ok(Some(_)) => {}
            Ok(None) => break,
            Err(_) => return photo_error(photos::PhotoError::TooLarge.message()).await,
        }
    }
    let Some(bytes) = upload else {
        return photo_error(photos::PhotoError::Empty.message()).await;
    };
    let processed = match photos::process_limited(bytes, photos::Shape::Square).await {
        Ok(processed) => processed,
        Err(err) => return photo_error(err.message()).await,
    };
    let result = {
        let _files = photos::lock_files().await;
        if let Err(err) = photos::store(&state.photo_dir, &processed).await {
            tracing::error!(%err, "couldn't store a contact photo");
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't save the photo - nothing was changed. Check the server log.",
            )
                .into_response();
        }
        commit_photo(&state, contact_id, Some(&processed.hash)).await
    };
    photo_saved(&state, id, contact_id, result).await
}

/// Removes a contact's photo (for every device that has the contact). Back to the contact's row.
pub async fn remove_photo(
    State(state): State<AppState>,
    Path((id, contact_id)): Path<(i64, i64)>,
) -> Response {
    match contact_on_device(&state, id, contact_id).await {
        Ok(Some(true)) => {
            let result = {
                let _files = photos::lock_files().await;
                commit_photo(&state, contact_id, None).await
            };
            photo_saved(&state, id, contact_id, result).await
        }
        // Nothing was removed: say so, as for a save (qa-31-s8-code N3).
        Ok(Some(false)) => contacts_group(id, true),
        Ok(None) => not_found(),
        Err(err) => db_error(id, err),
    }
}

/// A stored photo for the admin pages (behind the admin session like every page).
pub async fn view_photo(State(state): State<AppState>, Path(hash): Path<String>) -> Response {
    let Some(path) = photos::path_for(&state.photo_dir, &hash) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    match tokio::fs::read(&path).await {
        Ok(bytes) => (
            [
                (header::CONTENT_TYPE, "image/jpeg"),
                (
                    header::CACHE_CONTROL,
                    "private, max-age=31536000, immutable",
                ),
            ],
            bytes,
        )
            .into_response(),
        Err(_) => StatusCode::NOT_FOUND.into_response(),
    }
}

#[cfg(test)]
mod tests {
    use super::{Finding, RoleReport, contact_summary, role_report, valid_matrix_id};
    use crate::display_time::Zone;
    use chrono::{DateTime, TimeZone, Utc};

    fn t(value: &str) -> DateTime<Utc> {
        chrono::NaiveDateTime::parse_from_str(value, "%Y-%m-%d %H:%M:%S")
            .unwrap()
            .and_utc()
    }

    #[test]
    fn role_report_pending_expires() {
        let changed = t("2026-10-05 10:00:00");
        let older = t("2026-10-05 09:59:00");
        // Nothing changed, or a report newer than the change: current, also if it disagrees.
        assert_eq!(
            role_report(older, None, t("2026-10-05 10:01:00")),
            RoleReport::Current
        );
        assert_eq!(
            role_report(
                t("2026-10-05 10:00:30"),
                Some(changed),
                t("2026-10-05 10:01:00")
            ),
            RoleReport::Current
        );
        assert_eq!(
            role_report(older, Some(changed), t("2026-10-05 10:04:59")),
            RoleReport::Pending
        );
        assert_eq!(
            role_report(older, Some(changed), t("2026-10-05 10:05:00")),
            RoleReport::Unconfirmed
        );
    }

    #[test]
    fn matrix_ids() {
        assert!(valid_matrix_id("@mamma:matrix.org"));
        assert!(valid_matrix_id("@a.b-c:example.org:8448"));
        assert!(valid_matrix_id("@a/b+c=d:[::1]:8448"));
        assert!(valid_matrix_id("@a:[::1]"));
        for bad in [
            "mamma:matrix.org",
            "@:matrix.org",
            "@mamma",
            "@mamma:",
            "@ma mma:x.org",
            "@a:b?action=join&via=x",
            "@a#b:x.org",
            "@a?b:x.org",
            "@Mamma:x.org",
            "@a:x.org/path",
            "@a:x.org:port",
            "@a:[::1]x",
        ] {
            assert!(!valid_matrix_id(bad), "{bad}");
        }
    }

    #[test]
    fn a_contact_row_says_which_way_calls_go() {
        assert_eq!(contact_summary(true, true, true), "Both ways · Home");
        assert_eq!(contact_summary(true, false, false), "Calls in only");
        assert_eq!(contact_summary(false, true, true), "Calls out only · Home");
        assert_eq!(contact_summary(false, false, false), "No calls");
        // Home only for a contact in the phone book, as the launcher shows it (qa-31-s8-code L2).
        assert_eq!(contact_summary(true, false, true), "Calls in only");
        assert_eq!(contact_summary(false, false, true), "No calls");
    }

    /// Every finding's words (design 31 §2.2b, §4.2): the title is the Attention row's, the
    /// sentence has no package name, adb command or raw code - those sit behind the ⓘ - and its
    /// times are local; only "waiting" isn't a warning.
    #[test]
    fn findings_read_as_short_local_notes() {
        let zone = Zone::from_setting("Europe/Oslo");
        // 12:00 UTC = 14:00 in Oslo (summer time).
        let now = Utc.with_ymd_and_hms(2026, 7, 1, 12, 0, 0).unwrap();
        let earlier = Utc.with_ymd_and_hms(2026, 7, 1, 8, 58, 0).unwrap();
        let later = Utc.with_ymd_and_hms(2026, 7, 1, 12, 55, 0).unwrap();
        let package = "com.example.dialer";
        let all = [
            Finding::EmergencyCall { at: earlier },
            Finding::CallbackWindow { until: later },
            Finding::CallScreenFailed { at: earlier },
            Finding::Waiting {
                report: Some(earlier),
            },
            Finding::Unconfirmed {
                changed: Some(earlier),
                report: Some(earlier),
            },
            Finding::DialerNotAllowed {
                dialer: package.to_string(),
            },
            Finding::MessagesHidden {
                sms_app: package.to_string(),
            },
            Finding::NotEnforced { stopped: true },
            Finding::NotEnforced { stopped: false },
            Finding::FailClosed,
            Finding::NotPhoneApp,
            Finding::AnyoneCanCall,
            Finding::NoRedirection,
            Finding::CallLogUnreadable,
            Finding::RulesAfterRestart { unreadable: true },
            Finding::RulesAfterRestart { unreadable: false },
            Finding::Reported {
                error: "setDefaultDialerApplication: IllegalArgumentException".to_string(),
            },
            Finding::SmsAppAllowed {
                sms_app: package.to_string(),
            },
        ];
        let mut kinds: Vec<_> = all.iter().map(Finding::problem).collect();
        kinds.dedup();
        assert_eq!(kinds, super::CallProblem::ALL, "one finding per problem");
        for finding in &all {
            let note = finding.note(&zone, now);
            assert_eq!(note.title, finding.problem().title());
            assert_eq!(
                note.warning,
                *finding
                    != (Finding::Waiting {
                        report: Some(earlier)
                    })
            );
            let detail = note.detail.unwrap();
            assert!(detail.split_whitespace().count() <= 40, "{detail}");
            for raw in [
                package,
                "adb",
                "UTC",
                "role",
                "launcher",
                "Exception",
                "08:58",
                "12:55",
            ] {
                assert!(!detail.contains(raw), "{raw} in {detail}");
            }
        }
        let detail = |f: &Finding| f.note(&zone, now).detail.unwrap();
        assert!(detail(&all[0]).contains("(10:58)"), "{}", detail(&all[0]));
        assert!(detail(&all[1]).contains("until 14:55"));
        assert!(
            detail(&all[3]).contains("(10:58 · 3 h ago)"),
            "{}",
            detail(&all[3])
        );
        // True with and without JS, whatever the reload does (qa-31-s8-code L1).
        assert!(detail(&all[3]).ends_with("Reload the page in a minute."));
        assert!(!detail(&all[3]).contains("reloads"));
        assert!(detail(&all[4]).contains("at 10:58"));
        // The expert parts are behind the ⓘ.
        let fix = |f: &Finding| f.note(&zone, now).fix.unwrap_or_default();
        assert!(fix(&all[5]).contains(package));
        assert!(fix(&all[12]).contains("adb shell cmd role add-role-holder"));
        assert!(fix(&all[16]).contains("IllegalArgumentException"));
    }
}
