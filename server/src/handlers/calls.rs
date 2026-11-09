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
use crate::emergency;
use crate::handlers::device_api::device_contacts;
use crate::handlers::device_pages::Note;
use crate::i18n::{Lang, Words, t, t_params};
use crate::models::{CHANNEL_MODES, Device, DevicePolicy, MESSAGE_APPS, derived_old_keys};
use crate::phone;
use crate::photos;
use crate::sms;

/// Parent-facing names for `MESSAGE_APPS`, same order.
const MESSAGE_APP_LABELS: [&str; 4] = ["No message button", "SMS", "Element X", "Signal / Molly"];

/// How long after a change to `calls_mode` or `sms_mode` an older status report counts as "waiting for the
/// phone" rather than as the phone's real state (QA 09 #5).
const ROLES_PENDING_SECS: i64 = 5 * 60;
/// The calls page reloads itself (`static/auto-reload.js`) for this long while it waits.
const ROLES_REFRESH_SECS: i64 = 2 * 60;

/// Design 77 §8.5's path 2 (code QA M1): Google's web form that deregisters a number from RCS chat features, for a
/// phone whose Messages app is already hidden. It texts the number a code - that message is held, so the parent lets
/// its sender in under "Meldinger som venter".
const RCS_FORM_URL: &str = "https://messages.google.com/disable-chat";
const RCS_FORM_LABEL: &str = "messages.google.com/disable-chat";

/// The launcher's package as an adb command names it (behind an ⓘ only, never visible text).
const LAUNCHER_FOR_ADB: &str = "me.vibb.launcher (a debug build: me.vibb.launcher.debug; a launcher from before 2026-10-06: \
     com.kidslauncher.mdm)";

struct SelectOption {
    value: String,
    label: String,
    selected: bool,
}

fn message_app_options(
    lang: Lang,
    current: Option<&str>,
    with_default: Option<&str>,
) -> Vec<SelectOption> {
    // "No message button" is a word; the others are the apps' names.
    let label_of = |label: &'static str| {
        if label == MESSAGE_APP_LABELS[0] {
            t(lang, "device.calls.no_message_button")
        } else {
            label
        }
    };
    let mut options = Vec::new();
    if let Some(default) = with_default {
        let label = MESSAGE_APPS
            .iter()
            .position(|app| *app == default)
            .map(|i| label_of(MESSAGE_APP_LABELS[i]))
            .unwrap_or(default);
        options.push(SelectOption {
            value: String::new(),
            label: t_params(lang, "device.calls.phone_default", &[("app", label)]),
            selected: current.is_none(),
        });
    }
    for (value, label) in MESSAGE_APPS.iter().zip(MESSAGE_APP_LABELS) {
        options.push(SelectOption {
            value: value.to_string(),
            label: label_of(label).to_string(),
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
    /// Design 77 D3: the "Meldinger" group's switches, independent of the call ones.
    sms_in: bool,
    sms_out: bool,
    mms: bool,
    /// An emergency number: "Kan sende melding til" shows disabled, with why (QA R2-H1).
    texts_in_refused: bool,
    message_options: Vec<SelectOption>,
    message_address: String,
    /// SHA-256 of the contact's photo (shown via `/contact-photos/{hash}`).
    photo: Option<String>,
    /// Its kept original's SHA-256 and the view the square was cut at (migration 0123): "Adjust crop" opens
    /// `/contact-photos/original/{hash}` there. `None` for a photo stored before it (the square is refined).
    original: Option<(String, String)>,
    initial: String,
    /// The row's value: "Both ways · Home".
    summary: String,
    /// Under it, which way texts go: "Texts both ways" (design 77 D3).
    texts: Option<&'static str>,
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
/// qa-31-s8-code L2). The switch keeps its value for when the contact goes back in the book. Design 77 D3: then
/// which way texts go (`texts`: in as the phone gets it - never for an emergency number -, out), when either does.
fn contact_summary(
    lang: Lang,
    inbound: bool,
    outbound: bool,
    home: bool,
    texts: (bool, bool),
) -> String {
    let calls = t(
        lang,
        match (inbound, outbound) {
            (true, true) => "device.calls.both_ways",
            (true, false) => "device.calls.in_only",
            (false, true) => "device.calls.out_only",
            (false, false) => "device.calls.no_calls",
        },
    );
    let mut summary = if home && outbound {
        format!("{calls} · {}", t(lang, "device.calls.home"))
    } else {
        calls.to_string()
    };
    if let Some(texts) = texts_line(lang, texts) {
        summary.push_str(" · ");
        summary.push_str(texts);
    }
    summary
}

/// Design 77 D3: which way a contact's texts go (`texts`: in, out), `None` when neither. The row shows it on a line
/// of its own under the call summary, so a long summary never squeezes the name.
fn texts_line(lang: Lang, texts: (bool, bool)) -> Option<&'static str> {
    let key = match texts {
        (true, true) => "device.calls.texts_both_ways",
        (true, false) => "device.calls.texts_in",
        (false, true) => "device.calls.texts_out",
        (false, false) => return None,
    };
    Some(t(lang, key))
}

#[derive(Template)]
#[template(path = "device_calls.html")]
struct DeviceCallsTemplate {
    lang: Lang,
    title: String,
    device: Device,
    /// Design 77 D2: the two channel rows, calls first.
    channels: Vec<ChannelRow>,
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
    /// Add Contact's switches (design 77 R2-M1): `inbound`, `outbound`, `show_on_home`, `sms_in`, `sms_out`, `mms` -
    /// all on for a new contact, only `sms_in` for "Opprett kontakt…" from a held message.
    add_switches: [bool; 6],
    /// Design 77 §14: Add Contact came from the kid's request - its id (sent back as `request`) and the Matrix ID the
    /// kid suggested, shown with its own "Bruk Matrix-ID-en" ([Self::use_matrix], unticked until the parent ticks it).
    add_request: Option<(String, Option<String>)>,
    use_matrix: bool,
    /// Design 77 §14: the "Forespørsler" card's rows.
    requests: Vec<crate::handlers::contact_requests::RequestView>,
    /// The requests card's line (`?notice=`).
    requests_notice: Option<&'static str>,
    /// Design 77 step 4: "Meldinger som venter", the blocked list, the names, the SMS settings.
    sms: crate::handlers::calls_sms::SmsPage,
    /// The waiting card's line (`?notice=`).
    held_notice: Option<&'static str>,
    /// The names list is full (D17: 50): Add Name is not offered.
    names_full: bool,
    /// Add Name is open (a refusal), with what was typed and why.
    name_add_open: bool,
    name_add_value: String,
    name_add_error: Option<&'static str>,
    /// A refused SMS settings save: the field and why (the form shows what was sent).
    sms_error: Option<(&'static str, String)>,
    /// The family's contacts not on this phone (`id`, "name · number"), for "Add from the family".
    family: Vec<(i64, String)>,
    /// Design 73: the emergency menu's rows, in order.
    emergency: Vec<EmergencyRow>,
    /// Room for another number (at most `emergency::MAX_ENTRIES`).
    emergency_can_add: bool,
    /// The suggestions not on the list yet: `(number, name)`.
    emergency_suggestions: Vec<(&'static str, &'static str)>,
    /// The group's line (`?notice=`): a number kept or already gone.
    emergency_notice: Option<&'static str>,
    /// Add Number is open (a refusal), with what was typed and the field's reason.
    emergency_add_open: bool,
    emergency_add_number: String,
    emergency_add_name: String,
    emergency_add_error: Option<(&'static str, String)>,
    /// A refused channel choice (code QA M2), shown under the two rows.
    settings_error: Option<&'static str>,
    /// Design 77 §8.5's path 2: Google's form that turns a number's chat features off, the RCS line's ⓘ link.
    rcs_form_url: &'static str,
    rcs_form_label: &'static str,
}
crate::i18n::words!(DeviceCallsTemplate);

impl DeviceCallsTemplate {
    /// Add Contact's switches (design 77 R2-M1), in [ADD_SWITCHES]' order: the form name, the label, on.
    fn add_switch_rows(&self) -> Vec<(&'static str, String, bool)> {
        let kid = [("name", self.device.name.as_str())];
        let labels = [
            t_params(self.lang, "device.calls.can_call", &kid),
            t(self.lang, "device.calls.phone_book").to_string(),
            t(self.lang, "device.calls.on_home").to_string(),
            t_params(self.lang, "device.sms.texts_in", &kid),
            t_params(self.lang, "device.sms.texts_out", &kid),
            t(self.lang, "device.sms.pictures").to_string(),
        ];
        ADD_SWITCHES
            .into_iter()
            .zip(labels)
            .zip(self.add_switches)
            .map(|((name, label), on)| (name, label, on))
            .collect()
    }

    /// Design 77 D12: the own-number fields, SIM 1 and 2 - the form name, the label, the value and a refusal's why.
    fn own_fields(&self) -> Vec<(&'static str, String, String, Option<String>)> {
        ["own_number_1", "own_number_2"]
            .into_iter()
            .zip(self.sms.own.iter())
            .enumerate()
            .map(|(i, (field, value))| {
                (
                    field,
                    t_params(
                        self.lang,
                        "device.sms.settings.own_number",
                        &[("slot", &(i + 1).to_string())],
                    ),
                    value.clone(),
                    self.sms_error
                        .as_ref()
                        .filter(|(bad, _)| *bad == field)
                        .map(|(_, why)| why.clone()),
                )
            })
            .collect()
    }
}

/// One channel's row (design 77 D2): its form name (`calls_mode`/`sms_mode`), label, the segmented control's three
/// choices and the chosen state's sub-line.
struct ChannelRow {
    name: &'static str,
    label: &'static str,
    options: Vec<Segment>,
    sub: &'static str,
    /// SMS's RCS step (§8.5): "Før du velger Styrt av Vibb: slå av chatfunksjoner i Meldinger" before it is chosen,
    /// "Slå av chatfunksjoner i Meldinger først" under it; with an ⓘ that links Google's form.
    extra: Option<&'static str>,
    /// "<choice> - Oppdater Vibb One først", one line per choice the phone can't do yet (code QA M2).
    update_first: Vec<String>,
}

/// One choice of a segmented control (design 77 D2, the look of the phone switcher's segments): a radio, its short
/// label and a quiet note under it ("anbefalt").
struct Segment {
    value: &'static str,
    label: &'static str,
    note: Option<&'static str>,
    selected: bool,
    /// Shown but not choosable: a channel state this phone's launcher can't do yet.
    disabled: bool,
}

/// D2's three choices and the sub-line of `mode` for the calls channel (`sms` false) or SMS; `other` is the other
/// channel's state. `engine`: the phone reports `sms_engine_v1` - SMS's "Styrt av Vibb" then says "anbefalt".
/// Without it (code QA M2, the capability gate) a choice that would make the pair one an older launcher can't do
/// ([sms::older_launcher_expresses]) shows disabled, with "<choice> - Oppdater Vibb One først" under the row - never
/// the stored one.
fn channel_row(lang: Lang, sms: bool, mode: &str, other: &str, engine: bool) -> ChannelRow {
    let mut update_first = Vec::new();
    let options = CHANNEL_MODES
        .iter()
        .map(|value| {
            let (calls_mode, sms_mode) = if sms {
                (other, *value)
            } else {
                (*value, other)
            };
            let disabled =
                *value != mode && !engine && !sms::older_launcher_expresses(calls_mode, sms_mode);
            let key = match *value {
                "off" => "device.calls.mode.off",
                "managed" => "device.calls.mode.managed",
                _ => "device.calls.mode.unmanaged",
            };
            if disabled {
                update_first.push(t_params(
                    lang,
                    "device.calls.mode.update_first",
                    &[("mode", t(lang, key))],
                ));
            }
            Segment {
                value,
                label: t(lang, key),
                note: (sms && engine && *value == "managed")
                    .then(|| t(lang, "device.calls.mode.recommended")),
                selected: *value == mode,
                disabled,
            }
        })
        .collect();
    let (name, label, sub) = match (sms, mode) {
        (false, "managed") => (
            "calls_mode",
            "device.calls.channel.calls",
            "device.calls.channel.calls_managed",
        ),
        (false, "unmanaged") => (
            "calls_mode",
            "device.calls.channel.calls",
            "device.calls.channel.calls_unmanaged",
        ),
        (false, _) => (
            "calls_mode",
            "device.calls.channel.calls",
            "device.calls.channel.calls_off",
        ),
        (true, "managed") => (
            "sms_mode",
            "device.calls.channel.sms",
            "device.calls.channel.sms_managed",
        ),
        (true, "unmanaged") => (
            "sms_mode",
            "device.calls.channel.sms",
            "device.calls.channel.sms_unmanaged",
        ),
        (true, _) => (
            "sms_mode",
            "device.calls.channel.sms",
            "device.calls.channel.sms_off",
        ),
    };
    ChannelRow {
        name,
        label: t(lang, label),
        options,
        sub: t(lang, sub),
        // §8.5 (code QA M1): "først" - the step is shown before the switch, while Messages is still visible (SMS
        // "Ikke styrt" or "Av" on a phone that can take "Styrt av Vibb"), and stays under it.
        extra: match (sms, mode) {
            (false, _) => None,
            (true, "managed") => Some(t(lang, "device.calls.channel.sms_rcs")),
            (true, _) if engine => Some(t(lang, "device.calls.channel.sms_rcs_before")),
            (true, _) => None,
        },
        update_first,
    }
}

/// One row of the emergency menu (design 73).
struct EmergencyRow {
    /// `None` for the default 112 nothing has stored yet (alone, so it has no buttons).
    id: Option<i64>,
    number: String,
    /// The parent's name; for an emergency number without one, "Emergency number".
    name: String,
    /// The sub-line's word key, and whether it is orange (a number that follows the call rules).
    sub: &'static str,
    warn: bool,
    /// A second line: on a managed phone, a non-emergency number that isn't an outbound phone-book contact (the
    /// phone dims it).
    extra: Option<&'static str>,
    first: bool,
    last: bool,
    removable: bool,
}

impl EmergencyRow {
    fn anchor(&self) -> String {
        match self.id {
            Some(id) => format!("emergency-{id}"),
            None => "emergency-default".to_string(),
        }
    }
}

/// `phone_book`: the outbound contacts' numbers while calls are managed, `None` while they aren't.
fn emergency_rows(
    lang: Lang,
    menu: &[emergency::MenuEntry],
    cc: &str,
    phone_book: Option<&[String]>,
) -> Vec<EmergencyRow> {
    let big = emergency::big_row(menu, cc);
    menu.iter()
        .enumerate()
        .map(|(i, e)| {
            let real = emergency::is_emergency(&e.number, cc);
            let name = if e.name.is_empty() && real {
                t(lang, "device.emergency.default_name").to_string()
            } else {
                e.name.clone()
            };
            let (sub, warn) = if !real {
                ("device.emergency.not_emergency", true)
            } else if big == Some(i) {
                ("device.emergency.big", false)
            } else {
                ("device.emergency.always", false)
            };
            EmergencyRow {
                id: e.id,
                number: e.number.clone(),
                name,
                sub,
                warn,
                extra: phone_book
                    .filter(|book| !real && !book.contains(&e.number))
                    .map(|_| "device.emergency.not_in_phone_book"),
                first: i == 0,
                last: i + 1 == menu.len(),
                removable: e.id.is_some() && emergency::can_remove(menu, i, cc),
            }
        })
        .collect()
}

/// What a request shows besides the stored state: an open contact, a notice, a refusal.
#[derive(Default)]
pub(super) struct Shown<'a> {
    /// `?open=<contact id>`: that contact's form is open.
    pub(super) open: Option<i64>,
    /// `?notice=` for the Contacts group.
    pub(super) notice: Option<&'static str>,
    /// A refused Add Contact: the name and number typed, the field and why.
    pub(super) add: Option<(&'a str, &'a str, &'static str, String)>,
    /// What a refused Add Contact sent (its switches are shown again), when it had them.
    pub(super) add_sent: Option<&'a HashMap<String, String>>,
    /// Design 77 "Opprett kontakt…" (`?add_number=`): Add Contact open with the held number, only "Kan sende
    /// melding til" on (R2-M1).
    pub(super) add_held: Option<&'a str>,
    /// Design 77 §14 "Legg til…" (`?request=<id>`): Add Contact open with the kid's request - its name and number,
    /// §14's switches, the Matrix ID behind its unticked "Bruk Matrix-ID-en".
    pub(super) add_request: Option<&'a str>,
    /// `?notice=` for the requests card.
    pub(super) requests_notice: Option<&'static str>,
    /// `?notice=` for "Meldinger som venter".
    pub(super) held_notice: Option<&'static str>,
    /// A refused SMS settings save.
    pub(super) sms: Option<crate::handlers::calls_sms::SmsRefusal<'a>>,
    /// A refused Add Name: what was typed and why.
    pub(super) name_add: Option<(&'a str, &'static str)>,
    /// A refused contact save or photo.
    pub(super) contact: Option<ContactRefusal<'a>>,
    /// `?notice=` for the emergency menu.
    pub(super) emergency_notice: Option<&'static str>,
    /// A refused Add Number: the number and name typed, the field and why.
    pub(super) emergency_add: Option<(&'a str, &'a str, &'static str, String)>,
    /// A refused channel choice (design 77, code QA M2): why, under the two rows.
    pub(super) settings_error: Option<&'static str>,
}

/// `?notice=` codes for the emergency menu.
fn emergency_notice(lang: Lang, code: &str) -> Option<&'static str> {
    match code {
        "emergency_kept" => Some(t(lang, "device.emergency.kept")),
        "emergency_gone" => Some(t(lang, "device.emergency.gone")),
        _ => None,
    }
}

/// A refused contact save or photo: the contact, the field and why, and the form it sent (`None`
/// for a photo), whose values the row shows again.
pub(super) struct ContactRefusal<'a> {
    id: i64,
    field: &'static str,
    why: String,
    sent: Option<&'a HashMap<String, String>>,
}

/// `?notice=` codes for the Contacts group.
fn contacts_notice(lang: Lang, code: &str) -> Option<&'static str> {
    match code {
        "contact_gone" => Some(t(lang, "device.calls.contact_gone")),
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
        notice: query
            .get("notice")
            .and_then(|code| contacts_notice(lang, code)),
        emergency_notice: query
            .get("notice")
            .and_then(|code| emergency_notice(lang, code)),
        held_notice: query
            .get("notice")
            .and_then(|code| crate::handlers::calls_sms::notice(lang, code)),
        // Design 77 "Opprett kontakt…": a held number, prefilled (the form's escaping and the link's encoding
        // carry it; the save normalises and checks it as any typed number).
        add_held: query
            .get("add_number")
            .map(|n| n.trim())
            .filter(|n| !n.is_empty() && n.chars().count() <= 32),
        // Design 77 §14 "Legg til…": read from the stored request of this phone, never from the link.
        add_request: query.get("request").map(String::as_str),
        requests_notice: query
            .get("notice")
            .and_then(|code| crate::handlers::contact_requests::notice(lang, code)),
        ..Default::default()
    };
    render(&state, id, StatusCode::OK, shown, lang).await
}

/// The page; a read that fails is a 500, never an empty list (its saves would read as "off").
pub(super) async fn render(
    state: &AppState,
    id: i64,
    status: StatusCode,
    shown: Shown<'_>,
    lang: Lang,
) -> Response {
    match load_page(state, id, shown, lang).await {
        Ok(Some(page)) => (status, Html(page.render().unwrap())).into_response(),
        Ok(None) => crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.device_not_found"),
        Err(err) => {
            tracing::error!(device_id = id, %err, "can't read the calls page");
            crate::i18n::error(lang, StatusCode::INTERNAL_SERVER_ERROR, "error.read_phone")
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
    let menu = emergency::device_menu(&state.db, id).await?;
    let emergency_suggestions = emergency::suggestions(&menu, &default_country_code)
        .into_iter()
        .map(|(number, key)| (number, t(lang, key)))
        .collect();
    let (emergency_add_open, emergency_add_number, emergency_add_name, emergency_add_error) =
        match shown.emergency_add {
            Some((number, name, field, why)) => (
                true,
                number.to_string(),
                name.to_string(),
                Some((field, why)),
            ),
            None => (false, String::new(), String::new(), None),
        };
    let originals: HashMap<i64, (String, String)> = sqlx::query_as::<_, (i64, String, String)>(
        "SELECT c.id, c.photo_original_hash, c.photo_view FROM contacts c \
         JOIN device_contacts dc ON dc.contact_id = c.id \
         WHERE dc.device_id = ? AND c.photo_hash IS NOT NULL \
         AND c.photo_original_hash IS NOT NULL AND c.photo_view IS NOT NULL",
    )
    .bind(id)
    .fetch_all(&state.db)
    .await?
    .into_iter()
    .map(|(contact, hash, view)| (contact, (hash, view)))
    .collect();
    let contacts: Vec<ContactView> = device_contacts(state, id)
        .await?
        .into_iter()
        .map(|c| {
            let texts_in_refused = crate::handlers::calls_sms::texts_in_refused(
                &c.phone_number,
                &default_country_code,
            );
            let mut view = ContactView {
                initial: initial_of(&c.name),
                id: c.contact_id,
                summary: contact_summary(
                    lang,
                    c.allow_inbound,
                    c.allow_outbound,
                    c.show_on_home,
                    (false, false),
                ),
                texts: texts_line(lang, (c.sms_in && !texts_in_refused, c.sms_out)),
                name: c.name,
                number: c.phone_number,
                inbound: c.allow_inbound,
                outbound: c.allow_outbound,
                show_on_home: c.show_on_home,
                sms_in: c.sms_in && !texts_in_refused,
                sms_out: c.sms_out,
                mms: c.mms,
                texts_in_refused,
                message_options: message_app_options(
                    lang,
                    c.message_app.as_deref(),
                    Some(&policy.default_message_app),
                ),
                message_address: c.message_address.unwrap_or_default(),
                photo: c.photo_hash,
                original: originals.get(&c.contact_id).cloned(),
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
                    view.sms_in = sent.contains_key("sms_in") && !view.texts_in_refused;
                    view.sms_out = sent.contains_key("sms_out");
                    view.mms = sent.contains_key("mms");
                    let app = sent.get("message_app").map(String::as_str);
                    view.message_options = message_app_options(
                        lang,
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
    let family: Vec<(i64, String, String)> = sqlx::query_as(
        "SELECT id, name, phone_number FROM contacts WHERE id NOT IN \
         (SELECT contact_id FROM device_contacts WHERE device_id = ?) ORDER BY name COLLATE NOCASE, id",
    )
    .bind(id)
    .fetch_all(&state.db)
    .await?;
    let family = family
        .into_iter()
        .map(|(id, name, number)| (id, format!("{name} · {number}")))
        .collect();
    let report = call_report(state, &policy).await?;
    let engine = sms::has_engine(&state.db, id).await?;
    let (zone, now) = (Zone::load(&state.db, lang).await, Utc::now());
    let (warnings, mut notes): (Vec<Note>, Vec<Note>) = report
        .findings
        .iter()
        .map(|f| f.note(&zone, now))
        .partition(|note| note.warning);
    if report.screening_hint {
        notes.push(screening_note(lang));
    }
    // Design 77 §14: "Legg til…"'s request (the link's, or the refused form's), only while it is this phone's and open.
    let request_id = shown.add_request.or_else(|| {
        shown
            .add_sent
            .and_then(|sent| sent.get("request"))
            .map(String::as_str)
    });
    let add_request = match request_id {
        Some(request) => {
            crate::handlers::contact_requests::prefill(&state.db, id, request.trim()).await?
        }
        None => None,
    };
    let (add_open, add_name, add_number, add_error) =
        match (shown.add, shown.add_held, add_request.as_ref()) {
            (Some((name, number, field, why)), _, _) => (
                true,
                name.to_string(),
                number.to_string(),
                Some((field, why)),
            ),
            (None, Some(number), _) => (true, String::new(), number.to_string(), None),
            (None, None, Some(request)) => {
                (true, request.name.clone(), request.number.clone(), None)
            }
            (None, None, None) => (false, String::new(), String::new(), None),
        };
    // R2-M1: a new contact gets every switch on, an allow from a held message "Kan sende melding til" alone, the kid's
    // request §14's; a refusal shows what was sent.
    let add_switches = match (shown.add_sent, shown.add_held, add_request.is_some()) {
        (Some(sent), _, _) => ADD_SWITCHES.map(|name| sent.contains_key(name)),
        (None, Some(_), _) => HELD_ADD_SWITCHES,
        (None, None, true) => REQUEST_ADD_SWITCHES,
        (None, None, false) => [true; 6],
    };
    // "Bruk Matrix-ID-en" starts unticked (R2-M1); a refusal shows what was sent.
    let use_matrix = shown
        .add_sent
        .is_some_and(|sent| sent.contains_key("use_matrix"));
    let requests =
        crate::handlers::contact_requests::load(&state.db, id, &zone, now, &default_country_code)
            .await?;
    let sms_page =
        crate::handlers::calls_sms::load(&state.db, id, &zone, now, &default_country_code).await?;
    let mut sms_page = sms_page;
    if let Some(refusal) = &shown.sms {
        // What the refused form sent, so nothing typed is lost.
        sms_page.lock_text = refusal.sent.contains_key("sms_lock_text");
        for (slot, field) in ["own_number_1", "own_number_2"].iter().enumerate() {
            sms_page.own[slot] = refusal
                .sent
                .get(*field)
                .map(|s| s.trim().to_string())
                .unwrap_or_default();
        }
    }
    let names_full = sms_page.names.len() as i64 >= crate::handlers::calls_sms::NAMES_MAX;
    let (name_add_open, name_add_value, name_add_error) = match shown.name_add {
        Some((typed, why)) => (true, typed.to_string(), Some(why)),
        None => (false, String::new(), None),
    };
    let sms_error = shown
        .sms
        .as_ref()
        .map(|refusal| (refusal.field, refusal.why.clone()));
    // Never on a refusal's page (the answer to a POST): a reload would send it again, or lose
    // what was typed (qa-09-code #9).
    let auto_refresh = report.auto_refresh
        && add_error.is_none()
        && refused.is_none()
        && emergency_add_error.is_none()
        && shown.settings_error.is_none()
        && sms_error.is_none()
        && name_add_error.is_none()
        && shown.add_held.is_none()
        && add_request.is_none();

    let phone_book: Vec<String> = contacts
        .iter()
        .filter(|c| c.outbound)
        .map(|c| c.number.clone())
        .collect();
    let emergency_view = emergency_rows(
        lang,
        &menu,
        &default_country_code,
        policy.calls_managed().then_some(phone_book.as_slice()),
    );
    Ok(Some(DeviceCallsTemplate {
        lang,
        title: format!("{} - {}", device.name, t(lang, "device.page.calls")),
        channels: vec![
            channel_row(lang, false, &policy.calls_mode, &policy.sms_mode, engine),
            channel_row(lang, true, &policy.sms_mode, &policy.calls_mode, engine),
        ],
        settings_error: shown.settings_error,
        rcs_form_url: RCS_FORM_URL,
        rcs_form_label: RCS_FORM_LABEL,
        default_message_options: message_app_options(lang, Some(&policy.default_message_app), None),
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
        add_switches,
        add_request: add_request.map(|r| (r.id, r.matrix_id)),
        use_matrix,
        requests,
        requests_notice: shown.requests_notice,
        sms: sms_page,
        held_notice: shown.held_notice,
        names_full,
        name_add_open,
        name_add_value,
        name_add_error,
        sms_error,
        family,
        emergency: emergency_view,
        emergency_can_add: menu.len() < emergency::MAX_ENTRIES,
        emergency_suggestions,
        emergency_notice: shown.emergency_notice,
        emergency_add_open,
        emergency_add_number,
        emergency_add_name,
        emergency_add_error,
        device,
    }))
}

/// The call-screening role is missing while Vibb is the phone app: a plain row, not a warning
/// (the user's emulator, 2026-10-12), with the adb fix behind its ⓘ. No "accept the prompt":
/// Android refuses exclusive-role prompts under the launcher's DISALLOW_CONFIG_DEFAULT_APPS.
fn screening_note(lang: Lang) -> Note {
    Note {
        warning: false,
        title: t(lang, "device.calls.screening").to_string(),
        value: Some(t(lang, "device.calls.not_granted").to_string()),
        detail: Some(t_params(
            lang,
            "device.calls.screening_sub",
            &[("launcher", LAUNCHER_FOR_ADB)],
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
    /// The emergency call has left Attention: a parent has read its inbox event, or 24 h have
    /// passed ([EMERGENCY_ATTENTION_HOURS]). The calls page keeps the finding for 7 days.
    pub emergency_left_attention: bool,
}

impl CallReport {
    /// What each finding is about (the Attention rows). The calls page reads `findings` itself.
    pub fn problems(&self) -> Vec<CallProblem> {
        self.findings
            .iter()
            .map(Finding::problem)
            .filter(|p| !(self.emergency_left_attention && *p == CallProblem::EmergencyCall))
            .collect()
    }
}

/// How long an emergency call stays in Attention at most (the inbox event's push lives that
/// long, `Kind::EmergencyCall.ttl()`); the calls page keeps it for 7 days.
pub(crate) const EMERGENCY_ATTENTION_HOURS: i64 = 24;

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
    /// Design 77 (code QA M2): the stored channels are a pair the phone's launcher can't do before its update - it
    /// loses SMS and/or keeps calls to the contacts until then.
    NeedsUpdate {
        sms_lost: bool,
        calls_contacts_only: bool,
    },
    /// Design 77 §7: SMS is "Styrt av Vibb" but Vibb One isn't the SMS app - messages are lost.
    SmsNotOurs,
    /// A hold pool is full (R2-M4): new unknown senders aren't held until `until` (`None`: the server has no held row
    /// of the pool, so it can't say).
    SmsHoldFull {
        until: Option<DateTime<Utc>>,
    },
    /// Messages the phone couldn't show (a parse failure), counted since its install.
    SmsParseFailures {
        count: i64,
    },
    /// This SIM's MMS server isn't on the list (D10): pictures aren't fetched.
    MmsUnchecked,
    /// Pictures in messages need mobile data (D14).
    MmsNoData,
    /// The SIM in `slot` knows another own number (`sim`) than the parent typed (D12).
    OwnNumberMismatch {
        slot: i64,
        sim: String,
    },
    /// SMS "Ikke styrt" and Vibb One still holds the role (code QA L3): it shows every message unfiltered until it
    /// hands it back.
    SmsRoleKept,
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
            Finding::NeedsUpdate { .. } => CallProblem::NeedsUpdate,
            Finding::SmsNotOurs => CallProblem::SmsNotOurs,
            Finding::SmsHoldFull { .. } => CallProblem::SmsHoldFull,
            Finding::SmsParseFailures { .. } => CallProblem::SmsParseFailures,
            Finding::MmsUnchecked => CallProblem::MmsUnchecked,
            Finding::MmsNoData => CallProblem::MmsNoData,
            Finding::OwnNumberMismatch { .. } => CallProblem::OwnNumberMismatch,
            Finding::SmsRoleKept => CallProblem::SmsRoleKept,
        }
    }

    /// The calls page's words (§4.2): the problem's short title and one or two sentences, local
    /// times, an expert detail (an adb command, a package name, the phone's own error) behind the
    /// ⓘ only. Everything but [Finding::Waiting] is a warning.
    pub fn note(&self, zone: &Zone, now: DateTime<Utc>) -> Note {
        let lang = zone.lang();
        let say = |key: &str| t(lang, key).to_string();
        let package = |package: &str| {
            Some(t_params(
                lang,
                "device.calls.finding.package",
                &[("package", package)],
            ))
        };
        let (detail, fix): (String, Option<String>) = match self {
            Finding::EmergencyCall { at } => (
                t_params(
                    lang,
                    "device.calls.finding.emergency_call",
                    &[("at", &zone.when(*at, now))],
                ),
                None,
            ),
            Finding::CallbackWindow { until } => (
                t_params(
                    lang,
                    "device.calls.finding.callback",
                    &[("until", &zone.until(*until, now))],
                ),
                None,
            ),
            Finding::CallScreenFailed { at } => (
                t_params(
                    lang,
                    "device.calls.finding.screen_failed",
                    &[("at", &zone.when(*at, now))],
                ),
                Some(say("device.calls.finding.screen_failed_fix")),
            ),
            Finding::Waiting { report } => (
                match report {
                    Some(at) => t_params(
                        lang,
                        "device.calls.finding.waiting_at",
                        &[("at", &stamp(zone, *at, now))],
                    ),
                    None => say("device.calls.finding.waiting"),
                },
                None,
            ),
            Finding::Unconfirmed { changed, report } => (
                {
                    let changed = changed.map_or(String::new(), |at| {
                        t_params(
                            lang,
                            "device.calls.finding.changed_at",
                            &[("at", &zone.when(at, now))],
                        )
                    });
                    let report =
                        report.map_or(String::new(), |at| format!(" ({})", stamp(zone, at, now)));
                    t_params(
                        lang,
                        "device.calls.finding.unconfirmed",
                        &[("changed", &changed), ("report", &report)],
                    )
                },
                None,
            ),
            Finding::DialerNotAllowed { dialer } => (
                say("device.calls.finding.dialer_not_allowed"),
                package(dialer),
            ),
            Finding::MessagesHidden { sms_app } => (
                say("device.calls.finding.messages_hidden"),
                package(sms_app),
            ),
            Finding::NotEnforced { stopped: true } => {
                (say("device.calls.finding.stopped_screening"), None)
            }
            Finding::NotEnforced { stopped: false } => (say("device.calls.finding.too_old"), None),
            Finding::FailClosed => (say("device.calls.finding.fail_closed"), None),
            Finding::NotPhoneApp => (say("device.calls.finding.not_phone_app"), None),
            Finding::AnyoneCanCall => (say("device.calls.finding.anyone_can_call"), None),
            Finding::NoRedirection => (
                say("device.calls.finding.no_redirection"),
                Some(t_params(
                    lang,
                    "device.calls.finding.no_redirection_fix",
                    &[("launcher", LAUNCHER_FOR_ADB)],
                )),
            ),
            Finding::CallLogUnreadable => (say("device.calls.finding.call_log"), None),
            Finding::RulesAfterRestart { unreadable } => (
                say(if *unreadable {
                    "device.calls.finding.restart_unreadable"
                } else {
                    "device.calls.finding.restart_unsaved"
                }),
                None,
            ),
            Finding::Reported { error } => (
                say("device.calls.finding.reported"),
                Some(t_params(
                    lang,
                    "device.calls.finding.reported_fix",
                    &[("error", error)],
                )),
            ),
            Finding::SmsAppAllowed { sms_app } => (
                say("device.calls.finding.sms_app_allowed"),
                package(sms_app),
            ),
            Finding::NeedsUpdate {
                sms_lost,
                calls_contacts_only,
            } => (
                say(match (sms_lost, calls_contacts_only) {
                    (true, true) => "device.calls.finding.needs_update",
                    (true, false) => "device.calls.finding.needs_update_sms",
                    _ => "device.calls.finding.needs_update_calls",
                }),
                None,
            ),
            Finding::SmsNotOurs => (say("device.calls.finding.sms_not_ours"), None),
            Finding::SmsHoldFull { until: Some(until) } => (
                t_params(
                    lang,
                    "device.calls.finding.hold_full_until",
                    &[("until", &zone.until(*until, now))],
                ),
                None,
            ),
            Finding::SmsHoldFull { until: None } => {
                (say("device.calls.finding.hold_full_day"), None)
            }
            Finding::SmsParseFailures { count } => (
                crate::i18n::t_count(lang, "device.calls.finding.parse_failures", *count, &[]),
                None,
            ),
            Finding::MmsUnchecked => (say("device.calls.finding.mms_unchecked"), None),
            Finding::MmsNoData => (say("device.calls.finding.mms_no_data"), None),
            Finding::OwnNumberMismatch { slot, sim } => (
                t_params(
                    lang,
                    "device.calls.finding.own_number",
                    &[("slot", &slot.to_string()), ("number", sim)],
                ),
                None,
            ),
            Finding::SmsRoleKept => (say("device.calls.finding.sms_role_kept"), None),
        };
        Note {
            warning: self.problem().needs_attention(),
            title: self.problem().title(lang).to_string(),
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
        emergency_left_attention: false,
    };
    // The capabilities, `call_state`, the report's time and `sms_state` (design 77).
    type Latest = (Option<String>, Option<String>, String, Option<String>);
    let latest: Option<Latest> = sqlx::query_as(
        "SELECT capabilities_json, call_state_json, reported_at, sms_state_json FROM device_status \
         WHERE device_id = ? ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(policy.device_id)
    .fetch_optional(&state.db)
    .await?;
    let Some((capabilities_json, call_state_json, reported_at, sms_state_json)) = latest else {
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
        // Out of Attention after a day, or once any parent has read this call's inbox event
        // (its dedup key is the call's instant, `parent_events::Builder::emergency_call`). No
        // event (the switch is off, a clamped clock) = the day alone.
        report.emergency_left_attention = now - at
            >= chrono::Duration::hours(EMERGENCY_ATTENTION_HOURS)
            || crate::parent_events::emergency_call_read(&state.db, policy.device_id, at).await?;
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
        if !policy.calls_managed()
            && let Some(dialer) = text("system_dialer")
            && !allowed.contains(&dialer)
        {
            findings.push(Finding::DialerNotAllowed { dialer });
        }
        // Design 77: only while SMS isn't restricted - restricted, the launcher suspends every SMS app but its own.
        if policy.sms_mode == "unmanaged"
            && let Some(sms_app) = text("default_sms_package")
            && !allowed.contains(&sms_app)
        {
            findings.push(Finding::MessagesHidden { sms_app });
        }
    }
    // Design 77 D2 (code QA M2): a stored pair the phone's launcher can't do yet (a phone set up again with an older
    // build) - the policy sends the fail-closed derived keys; say what that costs until the update.
    if !capabilities.iter().any(|c| c == sms::SMS_ENGINE_CAPABILITY)
        && let Some((sms_lost, calls_contacts_only)) =
            sms::older_launcher_loses(&policy.calls_mode, &policy.sms_mode)
    {
        findings.push(Finding::NeedsUpdate {
            sms_lost,
            calls_contacts_only,
        });
    }
    // Design 77 §7: what the engine's `sms_state` says, from a launcher with the engine.
    if capabilities.iter().any(|c| c == sms::SMS_ENGINE_CAPABILITY)
        && let Some(sms_state) = sms_state_json
            .as_deref()
            .and_then(|json| serde_json::from_str::<serde_json::Value>(json).ok())
    {
        sms_findings(
            state,
            policy,
            &sms_state,
            roles == RoleReport::Pending,
            findings,
        )
        .await?;
    }
    // Design 77 D2: the role, screening and call-log findings are the calls channel's (a launcher with the engine
    // reports `call_state.state` for calls alone, and hands the dialer role back while only SMS is managed).
    if !policy.calls_managed() {
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
    if !policy.sms_enabled()
        && let Some(sms_app) = text("default_sms_package")
        && allowlist
            .as_ref()
            .is_some_and(|list| list.contains(&sms_app))
    {
        findings.push(Finding::SmsAppAllowed { sms_app });
    }
    Ok(report)
}

/// Design 77 §7's findings from the phone's `sms_state` (`sms::sanitize_state`'s known keys): while SMS is "Styrt av
/// Vibb" - the SMS role isn't ours (messages are lost; not while a change is pending, design 09 B1), a hold pool is
/// full (until the earliest held row of it ends, R2-M4), messages that couldn't be shown, MMS that can't be checked
/// on this SIM or fetched without mobile data, a SIM's own number that differs from the parent's (D12); while SMS is
/// "Ikke styrt" - Vibb One still holds the role (code QA L3: it hands it back at its next apply).
async fn sms_findings(
    state: &AppState,
    policy: &DevicePolicy,
    sms_state: &serde_json::Value,
    pending: bool,
    findings: &mut Vec<Finding>,
) -> Result<(), sqlx::Error> {
    let flag = |key: &str| sms_state.get(key).and_then(serde_json::Value::as_bool);
    let role_held = flag("role_held");
    match policy.sms_mode.as_str() {
        "managed" => {}
        "unmanaged" => {
            if !pending && role_held == Some(true) {
                findings.push(Finding::SmsRoleKept);
            }
            return Ok(());
        }
        _ => return Ok(()),
    }
    if !pending && role_held == Some(false) {
        findings.push(Finding::SmsNotOurs);
    }
    let full = |pool: &str| {
        sms_state
            .get("hold_full")
            .and_then(|full| full.get(pool))
            .and_then(serde_json::Value::as_bool)
            == Some(true)
    };
    let (numbers, names) = (full("numbers"), full("names"));
    if numbers || names {
        let until = crate::handlers::calls_sms::hold_full_until(
            &state.db,
            policy.device_id,
            numbers,
            names,
            Utc::now(),
        )
        .await?;
        findings.push(Finding::SmsHoldFull { until });
    }
    if let Some(count) = sms_state
        .get("parse_failures")
        .and_then(serde_json::Value::as_i64)
        .filter(|n| *n > 0)
    {
        findings.push(Finding::SmsParseFailures { count });
    }
    if flag("mmsc_known") == Some(false) {
        findings.push(Finding::MmsUnchecked);
    }
    if flag("mms_data_allowed") == Some(false) {
        findings.push(Finding::MmsNoData);
    }
    let stored: Vec<sms::OwnNumber> = sqlx::query_as(
        "SELECT slot, number FROM device_own_numbers WHERE device_id = ? ORDER BY slot",
    )
    .bind(policy.device_id)
    .fetch_all(&state.db)
    .await?;
    for reported in sms_state
        .get("own_numbers")
        .and_then(serde_json::Value::as_array)
        .into_iter()
        .flatten()
    {
        let (Some(slot), Some(number)) = (
            reported.get("slot").and_then(serde_json::Value::as_i64),
            reported.get("number").and_then(serde_json::Value::as_str),
        ) else {
            continue;
        };
        if stored
            .iter()
            .any(|own| own.slot == slot && own.number != number)
        {
            findings.push(Finding::OwnNumberMismatch {
                slot,
                sim: number.to_string(),
            });
        }
    }
    Ok(())
}

/// What a [Finding] is about (design 31 M5): the Attention row's short title comes from
/// this, never from the warning's first sentence (package names, 40 words).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CallProblem {
    /// An emergency call in the last 7 days (its Attention row: until read, 24 h at most).
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
    /// Design 77: the channels as set need a launcher update (SMS lost, calls contacts-only until then).
    NeedsUpdate,
    /// Design 77 §7: SMS managed but Vibb One isn't the SMS app - messages are lost.
    SmsNotOurs,
    /// The waiting list is full (R2-M4).
    SmsHoldFull,
    /// Messages couldn't be shown (not an Attention row: a count since the install).
    SmsParseFailures,
    /// MMS can't be checked on this SIM (not an Attention row: a server release fixes it, D10).
    MmsUnchecked,
    /// Pictures need mobile data (not an Attention row: the phone's own data setting, D14).
    MmsNoData,
    /// A SIM's number differs from the one typed (D12).
    OwnNumberMismatch,
    /// SMS not managed but Vibb One still the SMS app (not an Attention row: it hands the role back itself).
    SmsRoleKept,
}

impl CallProblem {
    /// The Attention row's title and the Calls page's warning: at most 8 words, no package names.
    pub fn title(self, lang: Lang) -> &'static str {
        t(
            lang,
            match self {
                CallProblem::EmergencyCall => "device.calls.problem.emergency_call",
                CallProblem::CallbackWindow => "device.calls.problem.callback",
                CallProblem::CallScreenFailed => "device.calls.problem.screen_failed",
                CallProblem::Waiting => "device.calls.problem.waiting",
                CallProblem::Unconfirmed => "device.calls.problem.unconfirmed",
                CallProblem::DialerNotAllowed => "device.calls.problem.dialer_not_allowed",
                CallProblem::MessagesHidden => "device.calls.problem.messages_hidden",
                CallProblem::NotEnforced => "device.calls.problem.not_enforced",
                CallProblem::FailClosed => "device.calls.problem.fail_closed",
                CallProblem::NotPhoneApp => "device.calls.problem.not_phone_app",
                CallProblem::AnyoneCanCall => "device.calls.problem.anyone_can_call",
                CallProblem::NoRedirection => "device.calls.problem.no_redirection",
                CallProblem::CallLogUnreadable => "device.calls.problem.call_log",
                CallProblem::RulesAfterRestart => "device.calls.problem.restart",
                CallProblem::Reported => "device.calls.problem.reported",
                CallProblem::SmsAppAllowed => "device.calls.problem.sms_app_allowed",
                CallProblem::NeedsUpdate => "device.calls.problem.needs_update",
                CallProblem::SmsNotOurs => "device.calls.problem.sms_not_ours",
                CallProblem::SmsHoldFull => "device.calls.problem.hold_full",
                CallProblem::SmsParseFailures => "device.calls.problem.parse_failures",
                CallProblem::MmsUnchecked => "device.calls.problem.mms_unchecked",
                CallProblem::MmsNoData => "device.calls.problem.mms_no_data",
                CallProblem::OwnNumberMismatch => "device.calls.problem.own_number",
                CallProblem::SmsRoleKept => "device.calls.problem.sms_role_kept",
            },
        )
    }

    /// Something to look at (everything but `Waiting`, and design 77's states a row on the page tells enough of:
    /// the parse failures' count, the MMS server list, mobile data, the role a phone hands back itself).
    pub fn needs_attention(self) -> bool {
        !matches!(
            self,
            CallProblem::Waiting
                | CallProblem::SmsParseFailures
                | CallProblem::MmsUnchecked
                | CallProblem::MmsNoData
                | CallProblem::SmsRoleKept
        )
    }

    /// Every kind, for the tests.
    #[cfg(test)]
    pub(crate) const ALL: [CallProblem; 24] = [
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
        CallProblem::NeedsUpdate,
        CallProblem::SmsNotOurs,
        CallProblem::SmsHoldFull,
        CallProblem::SmsParseFailures,
        CallProblem::MmsUnchecked,
        CallProblem::MmsNoData,
        CallProblem::OwnNumberMismatch,
        CallProblem::SmsRoleKept,
    ];
}

/// Capability the launcher reports when it enforces `call_policy`.
pub(crate) const CALL_POLICY_CAPABILITY: &str = "call_policy_v1";

pub(crate) fn parse_time(value: &str) -> Option<DateTime<Utc>> {
    DateTime::parse_from_rfc3339(value)
        .ok()
        .map(|t| t.with_timezone(&Utc))
}

pub(super) fn db_error(lang: Lang, device_id: i64, err: sqlx::Error) -> Response {
    tracing::error!(device_id, %err, "calls page database error");
    crate::i18n::error(
        lang,
        StatusCode::INTERNAL_SERVER_ERROR,
        "error.save_failed_log",
    )
}

pub(super) fn not_found(lang: Lang) -> Response {
    crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.device_not_found")
}

/// Nudges the phone and returns to `to` (a path on its calls page).
pub(super) fn saved(state: &AppState, id: i64, to: String) -> Response {
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

fn bad_request(lang: Lang, key: &str) -> Response {
    crate::i18n::error(lang, StatusCode::BAD_REQUEST, key)
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
    lang: Lang,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let default_message_app = form
        .get("default_message_app")
        .map(String::as_str)
        .unwrap_or("sms");
    if !MESSAGE_APPS.contains(&default_message_app) {
        return bad_request(lang, "error.unknown_message_app");
    }
    // Design 77 D2: each channel's state; a missing or unknown one is refused, never guessed.
    let mode = |key: &str| {
        form.get(key)
            .map(String::as_str)
            .filter(|mode| CHANNEL_MODES.contains(mode))
    };
    let (Some(calls_mode), Some(sms_mode)) = (mode("calls_mode"), mode("sms_mode")) else {
        return bad_request(lang, "error.unknown_channel_mode");
    };
    // Code QA M2 (step 4's gate brought forward): a pair an older launcher can't do is taken only from a phone that
    // reports the engine - else the page again, with why, under the rows. The stored pair is always taken back (an
    // unchanged choice beside another field's save loosens nothing).
    if !sms::older_launcher_expresses(calls_mode, sms_mode) {
        let gate = async {
            let stored: Option<(String, String)> = sqlx::query_as(
                "SELECT calls_mode, sms_mode FROM device_policy WHERE device_id = ?",
            )
            .bind(id)
            .fetch_optional(&state.db)
            .await?;
            let unchanged = stored.is_some_and(|(c, s)| c == calls_mode && s == sms_mode);
            Ok::<_, sqlx::Error>(unchanged || sms::has_engine(&state.db, id).await?)
        }
        .await;
        match gate {
            Ok(true) => {}
            Ok(false) => {
                let shown = Shown {
                    settings_error: Some(t(lang, "device.calls.update_first")),
                    ..Default::default()
                };
                return render(&state, id, StatusCode::BAD_REQUEST, shown, lang).await;
            }
            Err(err) => return db_error(lang, id, err),
        }
    }
    // The old columns get D2's derived keys, so a rolled-back server sends the fail-closed view.
    let (managed, calls_enabled, sms_enabled) = derived_old_keys(calls_mode, sms_mode);
    let result = async {
        let mut tx = state.db.begin().await?;
        // A change to either mode stamps roles_changed_at: reports from before it may still
        // show the old role state (B1).
        let updated = sqlx::query(
            "UPDATE device_policy SET \
             roles_changed_at = CASE WHEN calls_mode != ?1 OR sms_mode != ?2 THEN datetime('now') \
                                     ELSE roles_changed_at END, \
             calls_mode = ?1, sms_mode = ?2, calls_managed = ?3, calls_enabled = ?4, sms_enabled = ?5, \
             default_message_app = ?6, updated_at = datetime('now') WHERE device_id = ?7",
        )
        .bind(calls_mode)
        .bind(sms_mode)
        .bind(managed)
        .bind(calls_enabled)
        .bind(sms_enabled)
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
        Ok(0) => not_found(lang),
        Ok(_) => saved(&state, id, format!("/devices/{id}/calls#settings")),
        Err(err) => db_error(lang, id, err),
    }
}

/// Add Contact's switches, in the form's order (design 77 R2-M1).
const ADD_SWITCHES: [&str; 6] = [
    "inbound",
    "outbound",
    "show_on_home",
    "sms_in",
    "sms_out",
    "mms",
];
/// "Opprett kontakt…" from a held message (D16, R2-M1): an allow grants exactly one thing, this sender's texts.
const HELD_ADD_SWITCHES: [bool; 6] = [false, false, false, true, false, false];
/// "Legg til…" from the kid's request (§14, R2-M1's named defaults): the kid asked to call and text this person - "Kan
/// ringe", "I telefonboken" and both ways of texting on; "På Hjem-skjermen" and pictures off.
const REQUEST_ADD_SWITCHES: [bool; 6] = [true, true, false, true, true, false];

/// Adds a contact to this device: normalises the number with the default country code, reuses
/// the global contact with that number (renaming it - the name is shared by every device that
/// has it) and attaches it at the end of the list. Its switches are the form's (design 77 R2-M1: the form says
/// `switches`, and a missing one is off - "Opprett kontakt…" sends "Kan sende melding til" alone); a post without
/// them gets every flag on, as before. "Kan sende melding til" is never stored for an emergency number (R2-H1). The
/// kid's open requests for the number - and the one "Legg til…" came from (`request`) - are answered `added` in the
/// same transaction, and that request's Matrix ID becomes the contact's only with "Bruk Matrix-ID-en" ticked
/// (`use_matrix`; design 77 §14, R2-M1). Back to its row; a refusal renders the page (400) with Add Contact open, what
/// was typed and the reason under its field.
pub async fn add_contact(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    if let Some(contact_id) = form.get("contact_id") {
        return add_family_contact(&state, lang, id, contact_id).await;
    }
    let name = form.get("name").map(|s| s.trim()).unwrap_or("");
    let raw_number = form.get("number").map(String::as_str).unwrap_or("");
    let with_switches = form.contains_key("switches");
    let switches = if with_switches {
        ADD_SWITCHES.map(|switch| form.contains_key(switch))
    } else {
        [true; 6]
    };
    let state = &state;
    let form = &form;
    let refuse = |field: &'static str, why: String| async move {
        let shown = Shown {
            add: Some((name, raw_number, field, why)),
            add_sent: with_switches.then_some(form),
            ..Default::default()
        };
        render(state, id, StatusCode::BAD_REQUEST, shown, lang).await
    };

    let name_len = name.chars().count();
    if name_len == 0 || name_len > 60 {
        return refuse("name", t(lang, "device.calls.name_refused").into()).await;
    }
    let cc: String =
        match sqlx::query_scalar("SELECT default_country_code FROM call_settings WHERE id = 1")
            .fetch_one(&state.db)
            .await
        {
            Ok(cc) => cc,
            Err(err) => return db_error(lang, id, err),
        };
    let number = match phone::normalize(raw_number, &cc) {
        Ok(number) => number,
        Err(err) => {
            return refuse(
                "number",
                t_params(
                    lang,
                    "device.calls.number_refused",
                    &[("why", t(lang, err.key()))],
                ),
            )
            .await;
        }
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
        let [inbound, outbound, home, sms_in, sms_out, mms] = switches;
        // Design 77 §14: the number's open requests (and "Legg til…"'s own) are added; the kid's Matrix ID is
        // saved only when the parent ticked "Bruk Matrix-ID-en" (R2-M1), and only as the stored request has it.
        let suggested = crate::handlers::contact_requests::mark_added(
            &mut tx,
            id,
            &number,
            form.get("request").map(|r| r.trim()),
        )
        .await?;
        let message_address = suggested.filter(|_| form.contains_key("use_matrix"));
        sqlx::query(
            "INSERT OR IGNORE INTO device_contacts (device_id, contact_id, sort_order, allow_inbound, \
             allow_outbound, show_on_home, sms_in, sms_out, mms, message_address) \
             VALUES (?, ?, (SELECT COALESCE(MAX(sort_order) + 1, 0) FROM device_contacts \
                            WHERE device_id = ?), ?, ?, ?, ?, ?, ?, ?)",
        )
        .bind(id)
        .bind(contact_id)
        .bind(id)
        .bind(inbound)
        .bind(outbound)
        .bind(home)
        .bind(sms_in && !emergency::is_emergency(&number, &cc))
        .bind(sms_out)
        .bind(mms)
        .bind(message_address)
        .execute(&mut *tx)
        .await?;
        tx.commit().await?;
        Ok::<_, sqlx::Error>(Some(contact_id))
    }
    .await;
    match result {
        Ok(None) => not_found(lang),
        Ok(Some(contact_id)) => saved(
            state,
            id,
            format!("/devices/{id}/calls#contact-{contact_id}"),
        ),
        Err(err) => db_error(lang, id, err),
    }
}

/// "Add from the family" (the user, 2026-11-03): puts a contact of the family's address book that another phone has
/// on this one, with every flag on and at the end of the list, as Add Contact does - its name, number and photo are
/// the contact's. Back to its new row; a contact that no longer exists → `?notice=contact_gone#contacts`, one already
/// on this phone → its row, nothing written (both refusals, [crate::settings_log::refused]).
async fn add_family_contact(state: &AppState, lang: Lang, id: i64, contact_id: &str) -> Response {
    let contact_id: i64 = contact_id.trim().parse().unwrap_or(0);
    let result = async {
        let mut tx = state.db.begin().await?;
        let (device, contact): (bool, bool) = sqlx::query_as(
            "SELECT EXISTS(SELECT 1 FROM device_policy WHERE device_id = ?1), \
             EXISTS(SELECT 1 FROM contacts WHERE id = ?2)",
        )
        .bind(id)
        .bind(contact_id)
        .fetch_one(&mut *tx)
        .await?;
        if !device || !contact {
            return Ok::<_, sqlx::Error>((device, contact, false));
        }
        let added = sqlx::query(
            "INSERT OR IGNORE INTO device_contacts (device_id, contact_id, sort_order) \
             VALUES (?1, ?2, (SELECT COALESCE(MAX(sort_order) + 1, 0) FROM device_contacts \
                              WHERE device_id = ?1))",
        )
        .bind(id)
        .bind(contact_id)
        .execute(&mut *tx)
        .await?
        .rows_affected()
            > 0;
        if added {
            // Design 77 §14: the kid's open requests for this number are answered too.
            let number: String =
                sqlx::query_scalar("SELECT phone_number FROM contacts WHERE id = ?")
                    .bind(contact_id)
                    .fetch_one(&mut *tx)
                    .await?;
            crate::handlers::contact_requests::mark_added(&mut tx, id, &number, None).await?;
        }
        tx.commit().await?;
        Ok((true, true, added))
    }
    .await;
    match result {
        Ok((false, _, _)) => not_found(lang),
        Ok((true, false, _)) => contacts_group(id, true),
        Ok((true, true, false)) => {
            crate::settings_log::refused(Redirect::to(&contact_row(id, contact_id)))
        }
        Ok((true, true, true)) => saved(
            state,
            id,
            format!("/devices/{id}/calls#contact-{contact_id}"),
        ),
        Err(err) => db_error(lang, id, err),
    }
}

/// A Matrix user ID as Element expects it: `@localpart:server`, no whitespace.
/// The Matrix user-ID grammar (spec "User Identifiers"), the same check as the launcher's
/// `isMatrixId`: localpart `a-z 0-9 . _ = - / +`, server a hostname, IPv4 or `[IPv6]` with an
/// optional `:port` - so `?`, `#`, `&` and spaces never end up in the phone's `matrix:` URI
/// (qa-09-code #8).
pub(crate) fn valid_matrix_id(value: &str) -> bool {
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
        Some(_) => return bad_request(lang, "error.unknown_message_app"),
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
                Ok(None) => return not_found(lang),
                Err(err) => return db_error(lang, id, err),
            }
            let shown = Shown {
                contact: Some(ContactRefusal {
                    id: contact_id,
                    field: "message_address",
                    why: t(lang, "device.calls.matrix_refused").to_string(),
                    sent: Some(&form),
                }),
                ..Default::default()
            };
            return render(&state, id, StatusCode::BAD_REQUEST, shown, lang).await;
        }
    };

    let result = async {
        let mut tx = state.db.begin().await?;
        // Design 77 D3: the "Meldinger" group's three switches with the call ones; "Kan sende melding til" is never
        // stored for an emergency number (R2-H1: its switch is disabled, and the policy never sends it).
        let number: Option<(String, String)> = sqlx::query_as(
            "SELECT c.phone_number, s.default_country_code FROM contacts c, call_settings s \
             WHERE c.id = ? AND s.id = 1",
        )
        .bind(contact_id)
        .fetch_optional(&mut *tx)
        .await?;
        let emergency_number =
            number.is_some_and(|(number, cc)| emergency::is_emergency(&number, &cc));
        let updated = sqlx::query(
            "UPDATE device_contacts SET allow_inbound = ?, allow_outbound = ?, show_on_home = ?, \
             sms_in = ?, sms_out = ?, mms = ?, \
             message_app = ?, message_address = ? WHERE device_id = ? AND contact_id = ?",
        )
        .bind(form.contains_key("inbound"))
        .bind(form.contains_key("outbound"))
        .bind(form.contains_key("show_on_home"))
        .bind(form.contains_key("sms_in") && !emergency_number)
        .bind(form.contains_key("sms_out"))
        .bind(form.contains_key("mms"))
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
        Ok(0) => gone(&state, id, contact_id, true, lang).await,
        Ok(_) => saved(&state, id, contact_row(id, contact_id)),
        Err(err) => db_error(lang, id, err),
    }
}

/// A form for a contact that isn't on this phone: an unknown phone is a 404, else back to the
/// Contacts group (`said`: with "nothing was saved"). Nothing was written either way.
async fn gone(state: &AppState, id: i64, contact_id: i64, said: bool, lang: Lang) -> Response {
    match contact_on_device(state, id, contact_id).await {
        Ok(None) => not_found(lang),
        Ok(Some(_)) => crate::settings_log::refused(contacts_group(id, said)),
        Err(err) => db_error(lang, id, err),
    }
}

/// Detaches a contact from this device, and deletes it from the address book once no device has
/// it any more. Back to the Contacts group - also when it was already gone (a second tap).
pub async fn remove_contact(
    State(state): State<AppState>,
    lang: Lang,
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
        Ok(0) => gone(&state, id, contact_id, false, lang).await,
        Ok(_) => {
            // The address-book entry may be gone, and its photo with it.
            photos::prune(&state).await;
            let _ = state.command_notify.send(id);
            contacts_group(id, false)
        }
        Err(err) => db_error(lang, id, err),
    }
}

/// Every device that has this contact - all of them see its photo.
async fn devices_with_contact(state: &AppState, contact_id: i64) -> Result<Vec<i64>, sqlx::Error> {
    sqlx::query_scalar("SELECT device_id FROM device_contacts WHERE contact_id = ?")
        .bind(contact_id)
        .fetch_all(&state.db)
        .await
}

/// A contact's photo as committed: the square the phones get, and the kept original with the view it was cut at.
struct PhotoCommit<'a> {
    hash: &'a str,
    original: &'a str,
    view: &'a str,
}

/// Sets (or, with `None`, clears) a contact's photo with its original and view, prunes files nobody uses any more
/// and nudges every device that has the contact. The caller holds `photos::lock_files()` across its store and this
/// commit ([`photos::prune`] takes it again afterwards).
async fn commit_photo(
    state: &AppState,
    contact_id: i64,
    photo: Option<PhotoCommit<'_>>,
) -> Result<Vec<i64>, sqlx::Error> {
    let mut tx = state.db.begin().await?;
    sqlx::query(
        "UPDATE contacts SET photo_hash = ?, photo_original_hash = ?, photo_view = ? WHERE id = ?",
    )
    .bind(photo.as_ref().map(|p| p.hash))
    .bind(photo.as_ref().map(|p| p.original))
    .bind(photo.as_ref().map(|p| p.view))
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
    lang: Lang,
) -> Response {
    match result {
        Ok(devices) => {
            photos::prune(state).await;
            for device in devices {
                let _ = state.command_notify.send(device);
            }
            Redirect::to(&contact_row(id, contact_id)).into_response()
        }
        Err(err) => db_error(lang, id, err),
    }
}

/// A contact's kept original and the view its square was cut at, if it has one.
async fn photo_original(
    state: &AppState,
    contact_id: i64,
) -> Result<Option<(String, Option<String>)>, sqlx::Error> {
    let row = sqlx::query_as::<_, (Option<String>, Option<String>)>(
        "SELECT photo_original_hash, photo_view FROM contacts WHERE id = ? AND photo_hash IS NOT NULL",
    )
    .bind(contact_id)
    .fetch_optional(&state.db)
    .await?;
    Ok(row.and_then(|(hash, view)| hash.map(|hash| (hash, view))))
}

/// Uploads a contact's photo (multipart: the file `photo`, the crop `view` - `x,y,w,h`, `photos::View` - and, for
/// "Adjust crop", `original`). A new file is decoded and kept as a downscaled original without metadata
/// (`photos::process_contact`), and the square is cut from it at `view` (none: cover, centred - an upload without
/// the cropper). No file but `original` (the hash the view was chosen on) re-cuts the square from the kept original
/// (`photos::recut`): an adjust sends only the view. A file that isn't a JPEG/PNG/WebP photo, is too big or can't
/// be decoded, a view outside the photo, not square or too small, or an `original` that is no longer the contact's
/// renders the page (400) with the contact open and the reason under its photo, and nothing changes. The photo
/// belongs to the address-book contact, so every device that has it gets it - only ever the square. Back to the
/// contact's row.
pub async fn upload_photo(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, contact_id)): Path<(i64, i64)>,
    mut multipart: Multipart,
) -> Response {
    match contact_on_device(&state, id, contact_id).await {
        Ok(Some(true)) => {}
        Ok(None) => return not_found(lang),
        Ok(Some(false)) => return contacts_group(id, true),
        Err(err) => return db_error(lang, id, err),
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
    let refusal = |err: photos::ContactPhotoError| match err {
        photos::ContactPhotoError::Photo(err) => err.text(lang),
        photos::ContactPhotoError::View(err) => t(lang, err.key()).to_string(),
    };

    let mut upload = None;
    let mut view_text = String::new();
    let mut original_text = String::new();
    loop {
        match multipart.next_field().await {
            Ok(Some(field)) => {
                let name = field.name().unwrap_or_default().to_string();
                match name.as_str() {
                    "photo" => match field.bytes().await {
                        Ok(bytes) => upload = Some(bytes),
                        Err(_) => {
                            return photo_error(&photos::PhotoError::TooLarge.text(lang)).await;
                        }
                    },
                    "view" | "original" => match field.text().await {
                        // A hash or four numbers: anything longer is neither.
                        Ok(text) if text.len() <= 128 => {
                            let text = text.trim().to_string();
                            if name == "view" {
                                view_text = text;
                            } else {
                                original_text = text;
                            }
                        }
                        _ => return photo_error(t(lang, photos::ViewError::Malformed.key())).await,
                    },
                    _ => {}
                }
            }
            Ok(None) => break,
            Err(_) => return photo_error(&photos::PhotoError::TooLarge.text(lang)).await,
        }
    }
    let view = match view_text.as_str() {
        "" => None,
        text => match photos::View::parse(text) {
            Ok(view) => Some(view),
            Err(err) => return photo_error(t(lang, err.key())).await,
        },
    };
    let upload = upload.filter(|bytes| !bytes.is_empty());

    // "Adjust crop" on a kept original: only the view comes, the square is cut from the original again.
    let Some(bytes) = upload else {
        if original_text.is_empty() {
            return photo_error(&photos::PhotoError::Empty.text(lang)).await;
        }
        let Some(view) = view else {
            return photo_error(t(lang, photos::ViewError::Malformed.key())).await;
        };
        let changed = || photo_error(t(lang, "device.calls.crop.photo_changed"));
        let (original, saved_view) = match photo_original(&state, contact_id).await {
            Ok(Some((original, saved))) if original == original_text => (original, saved),
            Ok(_) => return changed().await,
            Err(err) => return db_error(lang, id, err),
        };
        let Some(path) = photos::path_for(&state.photo_original_dir, &original) else {
            return changed().await;
        };
        let jpeg = match tokio::fs::read(&path).await {
            Ok(jpeg) => jpeg,
            Err(err) => {
                // Lost while the server runs (startup's recovery would have caught it): forget it, as recovery
                // does - the square stays and its adjust refines it again - and say so (QA photo-original L2).
                tracing::warn!(%err, hash = original, "a contact photo's kept original is missing");
                let forgot = {
                    let _files = photos::lock_files().await;
                    photos::CONTACT_ORIGINALS.forget(&state.db, &original).await
                };
                if let Err(err) = forgot {
                    return db_error(lang, id, err);
                }
                return photo_error(t(lang, "device.calls.crop.original_lost")).await;
            }
        };
        let cut = match photos::recut_limited(jpeg, view).await {
            Ok(cut) => cut,
            Err(err) => return photo_error(&refusal(err)).await,
        };
        // The square that is already stored (Save without a move): nothing to commit, no phone to send it to
        // again (QA photo-original L1). Compared as the pixels cut, so the cropper's rounding doesn't count.
        if saved_view.as_deref() == Some(cut.view.as_str()) {
            return Redirect::to(&contact_row(id, contact_id)).into_response();
        }
        let result = {
            let _files = photos::lock_files().await;
            // Replaced or removed meanwhile (another tab): this view was chosen on another picture.
            match photo_original(&state, contact_id).await {
                Ok(Some((now, _))) if now == original => {
                    if let Err(err) = photos::store(&state.photo_dir, &cut.square).await {
                        tracing::error!(%err, "couldn't store a contact photo");
                        return crate::i18n::error(
                            lang,
                            StatusCode::INTERNAL_SERVER_ERROR,
                            "error.save_photo",
                        );
                    }
                    let commit = PhotoCommit {
                        hash: &cut.square.hash,
                        original: &original,
                        view: &cut.view,
                    };
                    Some(commit_photo(&state, contact_id, Some(commit)).await)
                }
                Ok(_) => None,
                Err(err) => Some(Err(err)),
            }
        };
        let Some(result) = result else {
            return changed().await;
        };
        return photo_saved(&state, id, contact_id, result, lang).await;
    };

    let processed = match photos::process_contact_limited(bytes, view).await {
        Ok(processed) => processed,
        Err(err) => return photo_error(&refusal(err)).await,
    };
    let result = {
        let _files = photos::lock_files().await;
        for (dir, photo) in [
            (&state.photo_original_dir, &processed.original),
            (&state.photo_dir, &processed.cut.square),
        ] {
            if let Err(err) = photos::store(dir, photo).await {
                tracing::error!(%err, "couldn't store a contact photo");
                return crate::i18n::error(
                    lang,
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "error.save_photo",
                );
            }
        }
        let commit = PhotoCommit {
            hash: &processed.cut.square.hash,
            original: &processed.original.hash,
            view: &processed.cut.view,
        };
        commit_photo(&state, contact_id, Some(commit)).await
    };
    photo_saved(&state, id, contact_id, result, lang).await
}

/// Removes a contact's photo, its original and view (for every device that has the contact). Back to the
/// contact's row.
pub async fn remove_photo(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, contact_id)): Path<(i64, i64)>,
) -> Response {
    match contact_on_device(&state, id, contact_id).await {
        Ok(Some(true)) => {
            let result = {
                let _files = photos::lock_files().await;
                commit_photo(&state, contact_id, None).await
            };
            photo_saved(&state, id, contact_id, result, lang).await
        }
        // Nothing was removed: say so, as for a save (qa-31-s8-code N3).
        Ok(Some(false)) => contacts_group(id, true),
        Ok(None) => not_found(lang),
        Err(err) => db_error(lang, id, err),
    }
}

/// A stored photo for the admin pages (behind the admin session like every page).
pub async fn view_photo(State(state): State<AppState>, Path(hash): Path<String>) -> Response {
    let Some(path) = photos::path_for(&state.photo_dir, &hash) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    jpeg_file(&path, "private, max-age=31536000, immutable").await
}

/// A contact photo's kept original for "Adjust crop" (migration 0123): behind the admin session like every page,
/// and only while a contact with a photo names it (a hash nobody uses any more, waiting for its prune, is a 404).
/// Never on the phones' routes: the device API serves only the square. Not kept in the browser's cache beyond the
/// session: `private, no-store`.
pub async fn view_photo_original(
    State(state): State<AppState>,
    Path(hash): Path<String>,
) -> Response {
    let Some(path) = photos::path_for(&state.photo_original_dir, &hash) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let named: Result<bool, sqlx::Error> = sqlx::query_scalar(
        "SELECT EXISTS (SELECT 1 FROM contacts WHERE photo_original_hash = ? AND photo_hash IS NOT NULL)",
    )
    .bind(&hash)
    .fetch_one(&state.db)
    .await;
    match named {
        Ok(true) => jpeg_file(&path, "private, no-store").await,
        Ok(false) => StatusCode::NOT_FOUND.into_response(),
        Err(err) => {
            tracing::warn!(%err, "couldn't check a contact photo's original");
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        }
    }
}

async fn jpeg_file(path: &std::path::Path, cache: &'static str) -> Response {
    match tokio::fs::read(path).await {
        Ok(bytes) => (
            [
                (header::CONTENT_TYPE, "image/jpeg"),
                (header::CACHE_CONTROL, cache),
            ],
            bytes,
        )
            .into_response(),
        Err(_) => StatusCode::NOT_FOUND.into_response(),
    }
}

/// The emergency menu's group, with a line (`?notice=`): a refusal for [crate::settings_log].
fn emergency_group(id: i64, notice: &str) -> Response {
    crate::settings_log::refused(Redirect::to(&format!(
        "/devices/{id}/calls?notice={notice}#emergency"
    )))
}

/// Why an Add Number was refused (design 73 §3), checked in its transaction.
enum AddRefusal {
    Full,
    Duplicate,
}

/// POST `/devices/{id}/calls/emergency`: adds a number at the end of the emergency menu (design 73). The number is
/// normalised as a contact's; the name is optional, at most 40 characters. A phone without rows gets its 112 stored
/// first. Back to the new row; a refusal renders the page (400) with Add Number open, what was typed and why.
pub async fn add_emergency(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let raw_number = form.get("number").map(String::as_str).unwrap_or("");
    let name = form.get("name").map(|s| s.trim()).unwrap_or("");
    let state = &state;
    let refuse = |field: &'static str, why: String| async move {
        let shown = Shown {
            emergency_add: Some((raw_number, name, field, why)),
            ..Default::default()
        };
        crate::settings_log::refused(render(state, id, StatusCode::BAD_REQUEST, shown, lang).await)
    };
    if name.chars().count() > emergency::MAX_NAME_CHARS {
        return refuse(
            "emergency_name",
            t(lang, "device.emergency.name_refused").into(),
        )
        .await;
    }
    let cc: String =
        match sqlx::query_scalar("SELECT default_country_code FROM call_settings WHERE id = 1")
            .fetch_one(&state.db)
            .await
        {
            Ok(cc) => cc,
            Err(err) => return db_error(lang, id, err),
        };
    let number = match phone::normalize(raw_number, &cc) {
        Ok(number) => number,
        Err(err) => {
            return refuse(
                "emergency_number",
                t_params(
                    lang,
                    "device.calls.number_refused",
                    &[("why", t(lang, err.key()))],
                ),
            )
            .await;
        }
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
        emergency::store_default(&mut tx, id).await?;
        let numbers: Vec<String> = sqlx::query_scalar(
            "SELECT number FROM device_emergency_numbers WHERE device_id = ?",
        )
        .bind(id)
        .fetch_all(&mut *tx)
        .await?;
        if numbers.contains(&number) {
            return Ok(Some(Err(AddRefusal::Duplicate)));
        }
        if numbers.len() >= emergency::MAX_ENTRIES {
            return Ok(Some(Err(AddRefusal::Full)));
        }
        let entry: i64 = sqlx::query_scalar(
            "INSERT INTO device_emergency_numbers (device_id, number, name, sort_order) \
             VALUES (?, ?, ?, (SELECT COALESCE(MAX(sort_order) + 1, 1) FROM device_emergency_numbers \
                               WHERE device_id = ?)) RETURNING id",
        )
        .bind(id)
        .bind(&number)
        .bind(name)
        .bind(id)
        .fetch_one(&mut *tx)
        .await?;
        tx.commit().await?;
        Ok::<_, sqlx::Error>(Some(Ok(entry)))
    }
    .await;
    match result {
        Ok(None) => not_found(lang),
        Ok(Some(Ok(entry))) => saved(state, id, format!("/devices/{id}/calls#emergency-{entry}")),
        Ok(Some(Err(AddRefusal::Duplicate))) => {
            refuse(
                "emergency_number",
                t(lang, "device.emergency.duplicate").into(),
            )
            .await
        }
        Ok(Some(Err(AddRefusal::Full))) => {
            refuse("emergency_number", t(lang, "device.emergency.full").into()).await
        }
        Err(err) => db_error(lang, id, err),
    }
}

/// The phone's stored rows in order (numbers only), or `None` when `entry` isn't one of them (already gone, or
/// another phone's).
async fn stored_menu(
    tx: &mut sqlx::SqliteConnection,
    id: i64,
    entry: i64,
) -> Result<Option<Vec<emergency::MenuEntry>>, sqlx::Error> {
    let rows: Vec<(i64, String)> = sqlx::query_as(
        "SELECT id, number FROM device_emergency_numbers WHERE device_id = ? ORDER BY sort_order, id",
    )
    .bind(id)
    .fetch_all(&mut *tx)
    .await?;
    if !rows.iter().any(|(row, _)| *row == entry) {
        return Ok(None);
    }
    Ok(Some(
        rows.into_iter()
            .map(|(row, number)| emergency::MenuEntry {
                id: Some(row),
                number,
                name: String::new(),
            })
            .collect(),
    ))
}

/// POST `/devices/{id}/calls/emergency/{entry}/move`: `dir` `up` or `down` swaps it with its neighbour (as Dagen's
/// title rules); back to `#emergency-<entry>`. A number already gone returns to the group, saying so.
pub async fn move_emergency(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, entry)): Path<(i64, i64)>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let up = match form.get("dir").map(String::as_str) {
        Some("up") => true,
        Some("down") => false,
        _ => return bad_request(lang, "device.emergency.bad_move"),
    };
    let result = async {
        let mut tx = state.db.begin().await?;
        let Some(order) = stored_menu(&mut tx, id, entry).await? else {
            return Ok(false);
        };
        let at = order.iter().position(|e| e.id == Some(entry)).unwrap_or(0);
        let other = if up {
            at.checked_sub(1)
        } else {
            Some(at + 1).filter(|o| *o < order.len())
        };
        if let Some(other) = other {
            let mut ids: Vec<Option<i64>> = order.iter().map(|e| e.id).collect();
            // Renumber in the new order, so equal sort orders can't stall a move.
            ids.swap(at, other);
            for (i, row) in ids.iter().enumerate() {
                sqlx::query("UPDATE device_emergency_numbers SET sort_order = ? WHERE id = ?")
                    .bind(i as i64 + 1)
                    .bind(row)
                    .execute(&mut *tx)
                    .await?;
            }
        }
        tx.commit().await?;
        Ok::<_, sqlx::Error>(true)
    }
    .await;
    match result {
        Ok(true) => saved(&state, id, format!("/devices/{id}/calls#emergency-{entry}")),
        Ok(false) => emergency_group(id, "emergency_gone"),
        Err(err) => db_error(lang, id, err),
    }
}

/// POST `/devices/{id}/calls/emergency/{entry}/remove`: back to `#emergency`. Refused when it would leave the menu
/// without a number of the fixed list (design 73 §3: never without an emergency number, never empty).
pub async fn remove_emergency(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, entry)): Path<(i64, i64)>,
) -> Response {
    let cc: String =
        match sqlx::query_scalar("SELECT default_country_code FROM call_settings WHERE id = 1")
            .fetch_one(&state.db)
            .await
        {
            Ok(cc) => cc,
            Err(err) => return db_error(lang, id, err),
        };
    let result = async {
        let mut tx = state.db.begin().await?;
        let Some(order) = stored_menu(&mut tx, id, entry).await? else {
            return Ok(Some("emergency_gone"));
        };
        let at = order.iter().position(|e| e.id == Some(entry)).unwrap_or(0);
        if !emergency::can_remove(&order, at, &cc) {
            return Ok(Some("emergency_kept"));
        }
        sqlx::query("DELETE FROM device_emergency_numbers WHERE id = ? AND device_id = ?")
            .bind(entry)
            .bind(id)
            .execute(&mut *tx)
            .await?;
        tx.commit().await?;
        Ok::<_, sqlx::Error>(None)
    }
    .await;
    match result {
        Ok(None) => saved(&state, id, format!("/devices/{id}/calls#emergency")),
        Ok(Some(notice)) => emergency_group(id, notice),
        Err(err) => db_error(lang, id, err),
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
        assert_eq!(
            contact_summary(crate::i18n::Lang::En, true, true, true, (false, false)),
            "Both ways · Home"
        );
        assert_eq!(
            contact_summary(crate::i18n::Lang::En, true, false, false, (false, false)),
            "Calls in only"
        );
        assert_eq!(
            contact_summary(crate::i18n::Lang::En, false, true, true, (false, false)),
            "Calls out only · Home"
        );
        assert_eq!(
            contact_summary(crate::i18n::Lang::En, false, false, false, (false, false)),
            "No calls"
        );
        // Home only for a contact in the phone book, as the launcher shows it (qa-31-s8-code L2).
        assert_eq!(
            contact_summary(crate::i18n::Lang::En, true, false, true, (false, false)),
            "Calls in only"
        );
        assert_eq!(
            contact_summary(crate::i18n::Lang::En, false, false, true, (false, false)),
            "No calls"
        );
        // Design 77 D3: then which way texts go, when either does.
        assert_eq!(
            contact_summary(crate::i18n::Lang::En, true, true, true, (true, true)),
            "Both ways · Home · Texts both ways"
        );
        assert_eq!(
            contact_summary(crate::i18n::Lang::En, false, false, false, (true, false)),
            "No calls · Texts in"
        );
        assert_eq!(
            contact_summary(crate::i18n::Lang::Nb, false, false, false, (false, true)),
            "Ingen anrop · Melding ut"
        );
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
            Finding::NeedsUpdate {
                sms_lost: true,
                calls_contacts_only: true,
            },
            Finding::NeedsUpdate {
                sms_lost: true,
                calls_contacts_only: false,
            },
            Finding::NeedsUpdate {
                sms_lost: false,
                calls_contacts_only: true,
            },
            Finding::SmsNotOurs,
            Finding::SmsHoldFull { until: Some(later) },
            Finding::SmsHoldFull { until: None },
            Finding::SmsParseFailures { count: 2 },
            Finding::MmsUnchecked,
            Finding::MmsNoData,
            Finding::OwnNumberMismatch {
                slot: 1,
                sim: "+4790000002".to_string(),
            },
            Finding::SmsRoleKept,
        ];
        let mut kinds: Vec<_> = all.iter().map(Finding::problem).collect();
        kinds.dedup();
        assert_eq!(kinds, super::CallProblem::ALL, "one finding per problem");
        for finding in &all {
            let note = finding.note(&zone, now);
            assert_eq!(note.title, finding.problem().title(crate::i18n::Lang::En));
            // Waiting, and design 77's rows a parent can't act on, aren't warnings.
            assert_eq!(
                note.warning,
                !matches!(
                    finding,
                    Finding::Waiting { .. }
                        | Finding::SmsParseFailures { .. }
                        | Finding::MmsUnchecked
                        | Finding::MmsNoData
                        | Finding::SmsRoleKept
                )
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
