# Dagen on the server (design 69)

The kid's day view lives in Vibb One (`launcher/CLAUDE.md`, "Dagen"); the calendar lives in the parent's tools (a school's
ICS feed through ICSx5, a family Nextcloud through DAVx5). **The server never sees an event**: it keeps the parent's
choices and the title rules, sends them as `PolicyResponse.calendar`, and stores which calendars the phone has.

## The policy's `calendar` (`calendar::policy_calendar`, always sent)

`{on_home, kid_adds, glance_next, lock_reminders, calendars: [{key, shown, remind, remind_minutes, writable_for_kid}],
rules: [{id, calendar, field, match, pattern, action, text}]}`. A fresh phone: on Home, the kid may add events (to the
phone's own "Min kalender"), nothing on the lock (`glance_next` false, `lock_reminders` `"off"`), no calendars saved, no
rules (`policy_json_keys_snapshot`). A read error fails the whole policy (500; the phone keeps its cache): a missing hide
rule would show what the parent hid. The key is the phone's (16 hex of SHA-256 over the provider's account type, account
name and `Calendars.NAME` - the feed or collection URL, so a display name's rename keeps it); the account name never
leaves the phone. Tables (migration `0103_calendar.sql`): `device_calendars` (no row = shown, reminders off, not the
kid's; `idx_device_calendars_kid`: at most one `writable_for_kid`), `device_calendar_rules` (`sort_order`; a description
rule only hides - a CHECK), the four `device_policy.calendar_*` switches, `devices.calendar_report`.

## The report's `calendar` (`calendar::sanitize_report`, `store_report`)

`{readable, calendars: [{key, account_type, name, source, access_level}], rules_skipped}` - never an event, a title, a
count or a time. Kept only within limits: `readable` a bool (else nothing is stored); each row through
`device_api::known_fields` with `calendar::CALENDAR_ROW_FIELDS`, a valid key (16 lowercase hex, once), an account type
of at most 200, the name cleaned and cut to 100, the source a hostname (`[a-z0-9.-]`, at most 253, lowercased) or null,
the access level an int (else 0); at most 50 rows; `rules_skipped` at most 30 ids, only ids the phone has. A report
without the key (an older launcher) leaves the last one. Contract: `testdata/calendar_status_report.json`
(byte-identical in the launcher's test resources; `tests::calendar::a_launcher_report_lists_the_calendars`,
`CalendarReportTest`); `intake_limits::the_field_tables_keep_every_contract_key` checks it too.

## The title rules (`calendar.rs`, one grammar with the launcher's `day/TitleRules.kt`)

Cleaning (control and bidi characters out, every whitespace run one space - Unicode's White_Space spelled out on both
sides -, trimmed, NFC, a title cut to 200 code points), matching (Starts with / Contains, case-insensitive per code
point with simple lowercase - `İ` → `i` by hand - and ignoring whitespace: "Romerriket" finds "R omerriket"), the
actions (replace; prefix = the text + the title without the match, the separators ` - – — : · | , ; /` trimmed from the
rest's ends; hide). The first rule whose calendar is any or the event's and whose field matches wins. Shared vectors:
`testdata/calendar_title_rules.json` (`calendar::tests::the_title_rules_vectors`, `TitleRulesTest`; both compare the
copies). Regex rules are design 69 §3.5's later step (RE2/J on the phone).

## Device › Calendar (`handlers/calendar.rs`)

`GET /devices/{id}/calendar` (`device_calendar.html`, `Section::Calendar`, the summary's "Calendar · N shown" row): Dagen
(`#dagen`: on Home; the kid's calendar - off, the phone's own, or a reported calendar it may write to: contributor or
more, not ICSx5, not phone-local; a saved pick the last report doesn't list - a re-login, a sync turned off, an
unreadable provider - stays the selected choice, "The chosen calendar - not on the phone now", and a save keeps it,
`tests::calendar::the_kids_calendar_survives_a_report_without_it`), On the lock screen (`#lock`: the "Next" line; the reminders' master switch, off or
the calm row), Calendars shown (`#calendars`: the phone's last report, one auto-saving form writing exactly the rows it
was sent - a missing `remind_<key>` keeps it -, a saved calendar gone from the phone greyed with Remove, the setup
steps without a report), Title rules (`#rules`: the list in order, up/down back to `#rule-<id>`, "Add a rule…").
"This phone's Vibb One doesn't know Dagen yet" until the last report claims `calendar_v1`.

Every route is behind the full session (`require_full_auth`; `tests::calendar::every_calendar_route_needs_a_session`:
signed out, each GET and POST is the login redirect and writes nothing).

The rule editor (`/rules/new`, `/rules/{rule}`, `device_calendar_rule.html`): look in, when it, the text, then (show as
with "Keep the rest", or hide), in; Save to `/devices/{id}/calendar#rule-<id>`; a refusal re-renders the editor as typed
(400, the reason in the bar); 30 rules, a pattern of 1-120 and a text of 0-40 characters after cleaning; a rule gone is
a 303 to `#rules` with `?notice=rule_gone`, marked `settings_log::refused`. The preview (`POST /rules/preview`) runs the
rule as typed on a pasted title and description, in its position among the phone's rules (the event taken to be in the
rule's calendar), and names a rule that matches first; with `fragment=1` (`static/calendar-preview.js`, 400 ms after
typing stops, the newest answer wins; `jstest/calendar-preview.test.js`) it answers only `partials/calendar_preview.html`,
else the editor with the preview filled (the noscript Preview button). It writes nothing and logs nothing.

## The kid's own events (design 69 step 3; the phone alone)

The server never sees them; the format is here because it is the one mark a CalDAV round trip keeps. An event the kid
adds in Vibb One gets `Events.UID_2445` = `vibb-<phone tag>-<random UUID>`, the tag being the first 8 hex (4 bytes) of
SHA-256 over `vibb-calendar-uid:` + the phone's stable id (`Settings.Secure.ANDROID_ID`, the same for the app across a
reinstall; an id kept in the app's `calendar_uid` prefs when the phone has none) - the id itself never leaves the
phone. The kid may change or delete only an event in the kid's calendar whose UID carries this phone's tag, or a
`vibb-` UID together with this phone's app as `CUSTOM_APP_PACKAGE` (a column a sync doesn't carry): two siblings whose
parent picked the same family calendar see each other's events, but neither may change the other's (QA L1;
`launcher/.../day/KidEvent.kt`, `KidEventTest`). On Dagen's list the kid's own event says its place when it has one,
else the calendar's name (mockup §1b shows the name always; the place is the more useful line).

## "On Home" (design 69 §4.4)

`device_app_display.on_home` (in 0103): Device › Apps › Name and icon's "On Home" switch (only a page that shows it -
`on_home_shown` - can turn it off; Reset puts it back), sent as `launcher_ui.app_display[].on_home: false` (only when
false). The launcher keeps such an app (a sync app) off Home and the drawer except during a pause or the override; it
stays allowed, and a time rule never suspends ICSx5 or DAVx5 while allowed.

## Words

Every word of these pages goes through `src/i18n.rs` (`docs/notes/server/pwa.md`, "Words"): the page's language (the
account's, else `Accept-Language`; design 70), `self.t("calendar.…")` in the templates, `t`/`t_params` in the handler -
the 400 and 500 bodies too (`calendar.error.*`). The Apps page's "On Home" switch, its sub-line and the summary's "· not on Home" are
`apps.on_home*`/`apps.not_on_home`, handed to the pre-i18n form (`AppDisplayForm.on_home_words`).
