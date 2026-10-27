# Dagen on the server (design 69)

The kid's day view lives in Vibb One (`launcher/CLAUDE.md`, "Dagen"); the calendar lives in the parent's tools (a school's
ICS feed through ICSx5, a family Nextcloud through DAVx5) - or, since design 72, a link pasted on the phone's Calendar
page, which the server fetches itself ("Calendar links" below). **No event ever leaves the phone**: the server keeps the
parent's choices and the title rules, sends them as `PolicyResponse.calendar`, and stores which calendars the phone has;
a calendar link's events are the only ones it holds (while the link exists, sent only to the phones with it).

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
`tests::calendar::the_kids_calendar_survives_a_report_without_it`), On the lock screen (`#lock`: the "Next" line; the reminders' master select
"Reminders on the lock screen", Off or Quiet row), Calendars shown (`#calendars`: the phone's last report, one auto-saving form writing exactly the rows it
was sent - a missing `remind_<key>` keeps it -, a saved calendar gone from the phone greyed with Remove, the setup
steps without a report), Title rules (`#rules`: the list in order, up/down back to `#rule-<id>`, "Add a rule…").
"This phone's Vibb One doesn't know Dagen yet" until the last report claims `calendar_v1`.

2026-10-27 polish (the user's iPhone look at the nb page): each of Dagen, On the lock screen and Calendars shown has one
short footer line and the rest behind its ⓘ (`<details class="info">`, as About and Calls); the kid's calendar is a
`.row-stacked` row (the label "The kid's events" / "Barnets avtaler" on its own line, the picker under it at full
width - no wrap at 390 px); the lock master is the select "Reminders on the lock screen" / "Påminnelser på låseskjermen"
with Off / Quiet row ("Av" / "Stille rad"; a select, not a switch, since a third form - a quiet row with a ping - is to
come), the sub-line "A quiet row before the event - no sound, no vibration"; the policy's `lock_reminders` and its
`off`/`row` unchanged. The lock group's ⓘ draws the reminder row ("Skole: Matte om 15 min", 08:30) in one inline SVG
on the night ground. `tests::calendar::the_help_is_behind_the_i_and_the_reminders_say_what_they_are`.

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

Every word of these pages goes through `src/i18n.rs` (`docs/notes/server/pwa.md`, "Words"): the page's language from
`Accept-Language`, `self.t("calendar.…")` in the templates, `t`/`t_params` in the handler - the 400 and 500 bodies too
(`calendar.error.*`). The Apps page's "On Home" switch, its sub-line and the summary's "· not on Home" are
`apps.on_home*`/`apps.not_on_home`, handed to the pre-i18n form (`AppDisplayForm.on_home_words`).

## Calendar links (design 72 step 1, `docs/design/72-calendar-feeds.md`)

The parent pastes a calendar's ICS link (https or webcal) on Device › Calendar; the server fetches and parses it, and
the phone writes the events into a local read-only calendar of its own (`launcher/CLAUDE.md`, "Calendar links").
ICSx5/DAVx5 stay possible. Step 2 (CalDAV, read-only, the password on the server) is not built.

- **Model** (migration `0105_calendar_feeds.sql`; 0104 is the unmerged `pwa-i18n-1`'s): `calendar_sources` - one row per
  normalized link (`calendar_feeds::normalize`: scheme and host lowercased, webcal(s) as https, the default port and
  the fragment dropped, path and query byte for byte), `uid` = 32 hex of SHA-256 over `vibb-feed:` + it (a link removed
  and added again is the same calendar: its key's settings and rules come back), the link **sealed** with the music
  key (`url_sealed`/`url_nonce`/`url_key_fp`, associated data `calendar-v1:<fingerprint>:<uid>`), `host` (the only part
  ever shown or logged), `lan` (judged as the music sweep judges a pasted feed; a LAN link judged again each fetch and
  public for good once it resolves publicly), `lan_http` (plain http on the home LAN: the row's warning), the
  validators, `rev` and `feed_json` (the served document), `feed_name` (`X-WR-CALNAME`), the fetch state (`ok_at`,
  `tried_at`, `failures`, `next_at`, `error` - a code -, `error_status`, `empty_count`/`empty_since`, `churn`,
  `fetch_now_at`) and the counts (`events`, `cut`, `unknown_tz`, `bad`). `device_calendar_feeds` (device, source, the
  link's `name`, 1-60). **A link per phone, the fetch shared**: the same link on a sibling's page attaches to its source
  (no second fetch). The trigger `calendar_sources_orphaned` deletes a source no row names: Remove and a deleted phone
  (whose cascade reaches only its rows) both end there (QA H2). Caps: 10 links a phone, 50 sources a server. Nothing
  historical is kept: every accepted fetch replaces the set; a backup holds the set as of then and the link sealed.
- **The parser** (`src/ics.rs`, our own): the folds taken out of the **bytes** first (a generator folds inside a UTF-8
  sequence), then the whole decoded - BOM, else the `Content-Type` charset, else UTF-8 lossy; it must begin with
  `BEGIN:VCALENDAR` (else `not_calendar`: an HTML login page). Content lines with quoted parameters, TEXT unescaped,
  a stack 4 deep, a line cut at 64 KB, at most 20 000 VEVENTs. Zones: `Z` = `UTC`, an IANA TZID as is, else its
  VTIMEZONE's `X-LIC-LOCATION`, a known prefix stripped (`/mozilla.org/<n>/`, `/freeassociation.sourceforge.net/`,
  `/softwarestudio.org/`), a Windows name through CLDR's table (`src/windows_zones.txt`, CLDR 49, NOTICE.md); else
  unknown - floating, counted `unknown_tz`, shown in the phone's time. Floating takes `X-WR-TIMEZONE` when it is an
  IANA name, else `tz: null` (the phone's zone). EXDATE, RDATE, RECURRENCE-ID and an override's own times are converted
  into the series' zone (QA M4). UIDs never lose an event (missing → a hash; > 255 bytes → its hash; two singles with
  one UID and different starts both kept, `uid~<8 hex>`; else the higher SEQUENCE, the later LAST-MODIFIED, the later
  in the body). An override without its master, or whose all-day-ness differs, stands alone (`uid#<recurrence id>`);
  THISANDFUTURE is a single-instance override. Cancelled: a single or a series dropped (with its overrides), an override
  kept as `cancelled`. RRULE passed through (`[A-Z0-9=;,+-]{1,300}` with a FREQ) with a known zone's `UNTIL` made UTC
  (a date all-day); a floating `UNTIL` stays local for the phone. `RDATE;VALUE=PERIOD` and an EMAIL, `RELATED=END` or
  absolute alarm are dropped. The horizon: a single event that ended over 60 days ago or starts over 400 ahead goes, a
  series whole unless its UNTIL is over 60 days ago. Caps 2000 events and 2 MB served, the farthest from today first.
  A series' rev = 16 hex of SHA-256 over its JSON without the rev; the feed's over the events array.
- **The fetch** (`src/calendar_feeds.rs`): `run` (spawned in `main`, its first pass 30 s on) fetches each due source,
  most overdue first, 4 s apart, through `music_fetch` (one request in flight server-wide) with `Kind::Calendar` (5 MB,
  60 s; `SourceResponse.content_type` since design 72). Redirects are followed in `get`, one hop at a time (at most 5):
  never https → http (`insecure`), a LAN link only on its own host and port. Conditional GET; a 304 keeps the set; a
  failure keeps it and backs off by `music_sweep::backoff` (15 min · 4^(n-1), capped at the hour; daily after 7 days of
  failing). A 200 with no events while the set had some is held (`empty`) until the third such answer in a row or 24 h
  after the first (its validators aren't kept, so the next fetch asks again; QA M1). UID churn - every series new at
  three fetches in a row - fetches every 6 h. A new rev is stored (`WHERE id AND uid`: a source removed mid-fetch
  writes nothing) and nudges exactly the phones with the link. Error codes, worded at render
  (`calendar.links.why.*`): `not_found`, `status`, `network`, `timeout`, `too_big`, `not_calendar`,
  `private_address`, `key_lost` (the key changed: add the link again), `insecure`, `empty`.
- **Delivery**: the policy's `calendar.feeds` (`[{id, name, rev}]`, always sent, `[]` without one;
  `policy_json_keys_snapshot`) and `GET /api/devices/calendar/feeds/{uid}` (bearer; `handlers::calendar::device_feed`
  through `music_api::versioned_json`: `ETag` = rev, 304, gzip; 404 unless this phone has the link). The report's
  `calendar.feeds` (`[{id, applied_rev, refused, unknown_zone}]`, `calendar::sanitize_report`, at most 10 rows) says
  what the phone applied. Contract `testdata/calendar_feeds.json` (root `CLAUDE.md`). Capability `calendar_feeds_v1`.
- **The page** (`#links`, above "Calendars shown"): each link's name (auto-saving, back to `#link-<uid>`), its host, its
  status lines ("Hentet 14:05 · 182 hendelser", the error in words with the set's age, "Venter på telefonen" until the
  report lists its key, "Oppdater Vibb One …" without `calendar_feeds_v1`, the phone's refused series, `unknown_tz`,
  `cut`, churn, the LAN warning), "Hent nå" (`/fetch`: at most once per source per 5 minutes - else
  `?notice=fetch_wait` -, the fetch before the answer, in `settings_log::NOT_SAVES`), "Fjern…" (confirmed, back to
  `#links`). The add form (`POST /devices/{id}/calendar/links`): the link and an optional name (empty → the feed's
  `X-WR-CALNAME`, else the host); plain http judged by what the name resolves to (`lan_addresses`: LAN or tailnet only -
  the tailnet without the warning -, else refused); no userinfo; at most 2000 characters; without the music key the
  form says why and has no submit (QA L11); the add check is one fetch that must give a VCALENDAR (an empty one is
  fine); a refusal renders the page again as typed (400), never the body; a link already a source is attached with no
  fetch; a save is a 303 to `#link-<uid>`. "Calendars shown" lists a link's calendar at once by its computed key
  (`calendar_feeds::calendar_key`: LOCAL, `Vibb One`, `vibb-feed-<uid>`), "Kalenderlenke · Venter på telefonen" until
  reported, then "Kalenderlenke"; an ICSx5 calendar with a link's host says "Kan være den samme som kalenderlenken …".
  The kid's calendar is never a link's (LOCAL, access 200). Every route behind the full session
  (`every_calendar_route_needs_a_session`); the settings log names routes and ids, never the link.
- Tests: `ics::tests` (the fixtures in `testdata/calendar_feeds/`: itslearning - its fold through "å" and the producer's
  broken "R omerriket" -, Outlook, Google, series, cancelled, empty, html, contract; the caps, a 70 KB line, Latin-1),
  `calendar_feeds::tests` (the contract, normalization, names), `tests::calendar_feeds` (the add and its refusals, the
  address rules, the page, the endpoint, the fetch cycle, a removal mid-fetch, Remove and a deleted phone, the caps).
