# Dagen on the server (design 69)

The kid's day view lives in Vibb One (`launcher/CLAUDE.md`, "Dagen"); the calendar lives in the parent's tools (a school's
ICS feed through ICSx5, a family Nextcloud through DAVx5) - or, since design 72, a link pasted on the phone's Calendar
page, which the server fetches itself ("Calendar links" below). **No event ever leaves the phone**: the server keeps the
parent's choices and the title rules, sends them as `PolicyResponse.calendar`, and stores which calendars the phone has;
a calendar link's events are the only ones it holds (while the link exists, sent only to the phones with it).

## The policy's `calendar` (`calendar::policy_calendar`, always sent)

`{on_home, kid_adds, glance_next, lock_reminders, calendars: [{key, shown, remind, remind_minutes, writable_for_kid,
color}], rules: [{id, calendar, field, match, pattern, action, text}]}` (`color` since 2026-10-27, below). A fresh phone: on Home, the kid may add events (to the
phone's own "Min kalender"), nothing on the lock (`glance_next` false, `lock_reminders` `"off"`), no calendars saved, no
rules (`policy_json_keys_snapshot`). A read error fails the whole policy (500; the phone keeps its cache): a missing hide
rule would show what the parent hid. The key is the phone's (16 hex of SHA-256 over the provider's account type, account
name and `Calendars.NAME` - the feed or collection URL, so a display name's rename keeps it); the account name never
leaves the phone. Tables (migration `0103_calendar.sql`): `device_calendars` (no row = shown, reminders off, not the
kid's; `idx_device_calendars_kid`: at most one `writable_for_kid`), `device_calendar_rules` (`sort_order`; a description
rule only hides - a CHECK), the four `device_policy.calendar_*` switches, `devices.calendar_report`.

## A colour per calendar (2026-10-27, mockup §6 B, branch `calendar-colours`; code QA `docs/review/qa-calendar-colours-code.md`)

`calendars[].color` is one of eight names (`calendar::COLORS`: `sky`, `forest`, `sun`, `berry`, `sea`, `lavender`,
`sand`, `slate` - in the auto-pick's order; never hex: the phone's `CalendarColor` keeps the ARGB values, the PWA's
`static/style.css` the swatches `.cal-<name>`) or `null` = no dot (`device_calendars.color`, migration 0106, numbered
past the feeds branch's 0105 on purpose). Peach is the kid's own calendar's and is never stored or offered: the parent's
pick (`writable_for_kid`; choosing a calendar as the kid's clears its colour) and the phone's own "Min kalender", which
the server knows by its key - `calendar::KID_LOCAL_KEY` = `c211fc627cbde3c1`, the phone's key rule over `LOCAL`, the
launcher's `LOCAL_ACCOUNT` "Vibb One" and `LOCAL_NAME` "vibb-kid-calendar" (`local_calendar_key`, pinned against the
launcher's constants by `calendar::tests::kid_local_key_is_the_launchers`; QA L3) - have no Colour row and take no
colour. Device › Calendar's "Colour" row under every other calendar's Reminder (`color_<key>`, a plain autosaving
select, back to `#calendars`) offers the eight; a calendar without one shows "Pick for me" first (value `""`, read as
unchanged - a select of the eight alone would post the first for every calendar at the first autosave), or, with all
eight held by this phone's rows, the disabled "No free colour" (QA L6). Once picked there is no way back to "no dot"
(by design: the key needs every calendar's dot; QA nit 4).

**The auto-pick** (`calendar::assign_free_colors(tx, device, first, min_takers)`): the colour-taking calendars are
every shown saved row, every reported calendar with no row (shown by default) and every calendar link's own calendar
(its key is the server's, `calendar_feeds::calendar_key(uid)`, before the phone has it) - never the kid's pick or
`KID_LOCAL_KEY`. Each without a colour gets the first name **no row of the phone holds - hidden rows' colours count
too** (QA M1: with the pool rule a calendar hidden, its colour given away and shown again made two dots alike; now a
hidden calendar keeps its name reserved), in the order of `first` (a form's rows as posted, a report's rows as listed),
then by key; a calendar without a row gets a row holding only the colour, by an upsert that never overwrites a colour
(`ON CONFLICT … COALESCE`, QA L5: a report and an autosave may both decide to insert the same key). First, under a
readable report, a row holding only a colour whose calendar is neither reported nor a link's goes (QA L2: a gone
calendar - a school feed re-subscribed each term under a new NAME - frees its colour; a row the parent set anything
else on stays, as a hidden one; an unreadable report prunes nothing - the calendars aren't gone, only unseen). Eight in
use: the rest get none. It runs after **every save on the Calendar page** - the calendars form and Dagen's switches
(`min_takers` 0: one calendar alone gets a colour at a save), the links forms add, rename and remove
(`colors_after_link_save`, best effort after the link's own write; not "Fetch now", where no calendar can appear) - and
at **every report intake** (`store_report`, after the report's own statement, in its own transaction - a failure here
is logged and never costs the report, QA L5) with `min_takers` 2 (QA L7, the user's decision: the parent needs to do
nothing - the moment a key would first show, the dots are there; a phone with one calendar gets none from a report
alone, a key never shows for one). An unknown name is a 400 (`calendar.error.color`) that writes nothing. A row with
a colour is never pruned as "the defaults" (`prune_default_rows`); L2's prune is the auto-pick's own. **Not built**
(QA L1, the coordinator's call): the mockup's calendar name after the time on the lock's reminder row ("17:30 ·
Fotball") - the dot alone; the name is the key's job on Dagen. Tests: `tests::calendar::a_colour_is_saved_picked_and_refused`,
`a_link_save_and_a_new_report_extend_the_colours`, `hidden_keeps_gone_frees_the_kids_own_takes_none_and_the_ninth_has_none`;
the row's shape in `the_policy_carries_the_rules_and_the_calendars` and the launcher's `PolicyResponseCompatTest`.

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
"Reminders on the lock screen", Off or Quiet row), Fetched by the server (`#sources`: the calendar links and CalDAV
accounts, below "Device › Calendar's sources"), Calendars shown (`#calendars`: the phone's last report, one auto-saving form writing exactly the rows it
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

Every word of these pages goes through `src/i18n.rs` (`docs/notes/server/pwa.md`, "Words"): the page's language (the
account's, else `Accept-Language`; design 70), `self.t("calendar.…")` in the templates, `t`/`t_params` in the handler -
the 400 and 500 bodies too (`calendar.error.*`). The Apps page's "On Home" switch, its sub-line and the summary's "· not on Home" are
`apps.on_home*`/`apps.not_on_home`, handed to the pre-i18n form (`AppDisplayForm.on_home_words`).

## Calendar links (design 72 step 1, `docs/design/72-calendar-feeds.md`)

The parent pastes a calendar's ICS link (https or webcal) on Device › Calendar; the server fetches and parses it, and
the phone writes the events into a local read-only calendar of its own (`launcher/CLAUDE.md`, "Calendar links").
ICSx5/DAVx5 stay possible. Step 2 (CalDAV, read-only, the password on the server) is below, "CalDAV accounts".

- **Model** (migration `0105_calendar_feeds.sql`, after S-i18n slice 1's `0104_admin_lang.sql`): `calendar_sources` - one row per
  normalized link (`calendar_feeds::normalize`: scheme and host lowercased, webcal(s) as https, the default port and
  the fragment dropped, path and query byte for byte), `uid` = 32 hex of SHA-256 over `vibb-feed:` + it (a link removed
  and added again is the same calendar: its key's settings and rules come back), the link **sealed** with the music
  key (`url_sealed`/`url_nonce`/`url_key_fp`, associated data `calendar-v1:<fingerprint>:<uid>`), `host` (the only part
  ever shown or logged), `lan` (judged as the music sweep judges a pasted feed; a LAN link judged again each fetch and
  public for good once it resolves publicly), `lan_http` (plain http on the home LAN: the row's warning; judged at the add and again at every fetch from the
  route it took - a tailnet answer clears it, a LAN one sets it, QA L5), the
  validators, `rev` and `feed_json` (the served document), `feed_name` (`X-WR-CALNAME`), the fetch state (`ok_at`,
  `tried_at`, `failures`, `next_at`, `error` - a code -, `error_status`, `empty_count`/`empty_since`, `churn`,
  `fetch_now_at`) and the counts (`events`, `cut`, `unknown_tz`, `bad`). `device_calendar_feeds` (device, source, the
  link's `name`, 1-60). **A link per phone, the fetch shared**: the same link on a sibling's page attaches to its source
  (no second fetch). The trigger `calendar_sources_orphaned` deletes a source no row names: Remove and a deleted phone
  (whose cascade reaches only its rows) both end there (QA H2). Caps: 10 links a phone, 50 sources a server. Nothing
  historical is kept: every accepted fetch replaces the set; a backup holds the set as of then and the link sealed.
- **The parser** (`src/ics.rs`, our own): the folds taken out of the **bytes** first (a generator folds inside a UTF-8
  sequence), then the whole decoded - BOM, else the `Content-Type` charset, else UTF-8 lossy; it must begin with
  `BEGIN:VCALENDAR` (else `not_calendar`: an HTML login page). Content lines with quoted parameters, TEXT unescaped (control and invisible marks - `calendar::is_dropped`: BOM, zero-width, bidi - dropped, `\n`
  kept; QA L8),
  a stack 4 deep, a line cut at 64 KB, at most 20 000 VEVENTs. Zones: `Z` = `UTC`, an IANA TZID as is, else its
  VTIMEZONE's `X-LIC-LOCATION`, a known prefix stripped (`/mozilla.org/<n>/`, `/freeassociation.sourceforge.net/`,
  `/softwarestudio.org/`), a Windows name through CLDR's table (`src/windows_zones.txt`, CLDR 49, NOTICE.md); else
  unknown - counted `unknown_tz`. A TZID-less time and an unknown TZID alike take `X-WR-TIMEZONE` when it is an IANA
  name (QA L2), else float: `tz: null`, shown in the phone's time. In a floating series a `Z` EXDATE, RECURRENCE-ID or
  DTEND has no zone to land in: dropped and counted `bad` (QA L3). EXDATE, RDATE, RECURRENCE-ID and an override's own times are converted
  into the series' zone (QA M4). UIDs never lose an event (missing → a hash; > 255 bytes → its hash; two singles with
  one UID and different starts both kept, `uid~<8 hex>`; else the higher SEQUENCE, the later LAST-MODIFIED, the later
  in the body). An override without its master, or whose all-day-ness differs, stands alone (`uid#<recurrence id>`);
  THISANDFUTURE is a single-instance override. Cancelled: a single or a series dropped (with its overrides), an override
  kept as `cancelled`. RRULE passed through (`[A-Z0-9=;,+-]{1,300}` with a FREQ) with a known zone's `UNTIL` made UTC
  (a date all-day); a floating `UNTIL` stays local for the phone. A DURATION past `ics::MAX_DURATION_DAYS` (400 days) or one that doesn't read is `bad` (the default length then), a
  huge TRIGGER or COUNT can't overflow (every sum checked, QA H1). `RDATE;VALUE=PERIOD` and an EMAIL, `RELATED=END` or
  absolute alarm are dropped. The horizon: a single event that ended over 60 days ago or starts over 400 ahead goes, a
  series whole unless its UNTIL is over 60 days ago. Caps 2000 events and 2 MB served, the farthest from today first.
  A series' rev = 16 hex of SHA-256 over its JSON without the rev; the feed's over the events array.
- **The fetch** (`src/calendar_feeds.rs`): `run` (spawned in `main`, its first pass 30 s on, then `run_due` every
  minute) fetches each due source, most overdue first, `SPACING` 4 s apart, each in its own task - a parse that panics
  is caught (`catch_unwind`: the source's `unreadable`), a task that panics anyway is stored as that source's
  `unreadable` failure, and the pass goes on (QA H1); a fetch that couldn't be stored waits the interval (nit 6) - through `music_fetch` (one request in flight server-wide) with `Kind::Calendar` (5 MB,
  60 s; `SourceResponse.content_type` since design 72). Redirects are followed in `get`, one hop at a time (at most 5):
  never https → http (`insecure`), a LAN link only on its own host and port. Conditional GET; a 304 keeps the set; a
  failure keeps it and backs off by `music_sweep::backoff` (15 min · 4^(n-1), capped at the hour; daily after 7 days of
  failing). A 200 with no events while the set had some is held (`empty`) until the third such answer in a row or 24 h
  after the first (its validators aren't kept, so the next fetch asks again; QA M1). UID churn - every series new at
  three fetches in a row - fetches every 6 h. A new rev is stored (`WHERE id AND uid`: a source removed mid-fetch
  writes nothing) and nudges exactly the phones with the link. Error codes, worded at render
  (`calendar.links.why.*`): `not_found`, `status`, `network`, `timeout`, `too_big`, `not_calendar`,
  `private_address`, `key_lost` (the key changed: add the link again - the add re-seals the source under today's key
  and clears the error, QA M1), `insecure`, `empty`, `unreadable` (the parser failed on the body). **A new key is
  said at once** (E6's L3, 2026-11-01): the server makes a new music key at its start when the key file is gone, and a
  link sealed with the old one would turn `key_lost` only at its next fetch - so the page compares each link's
  `url_key_fp` (`LinkRow.key_fp`) with today's key's fingerprint: one that differs shows the row's `key_lost` line
  before any fetch stored it, and the group gets an alarm row (`data-warning="links-key-new"`, `calendar.links.key_new`
  and `_sub`: paste each marked link again with Add Link). No key at all is the form's own line (`links_off`), not this.
  Test `calendar_feeds::a_new_music_key_says_to_paste_the_links_again`.
- **Delivery**: the policy's `calendar.feeds` (`[{id, name, rev}]`, always sent, `[]` without one;
  `policy_json_keys_snapshot`) and `GET /api/devices/calendar/feeds/{uid}` (bearer; `handlers::calendar::device_feed`
  through `music_api::versioned_json`: `ETag` = rev, 304, gzip; 404 unless this phone has the link). The report's
  `calendar.feeds` (`[{id, applied_rev, refused, unknown_zone}]`, `calendar::sanitize_report`, at most 10 rows with a valid id, the counts capped at 2000 - QA L1) says
  what the phone applied. Contract `testdata/calendar_feeds.json` (root `CLAUDE.md`). Capability `calendar_feeds_v1`.
- **The page** (since 2026-11-01 the look of variant B, below - "Device › Calendar's sources"): the link's own page
  (`GET .../calendar/links/{uid}`): its name (auto-saving, back to `#name`), its host, Fetching (`#fetch`: the failure
  in words with the set's age - orange -, Last fetched and Events once a fetch was stored ("27.10. 14:05" - the
  date's form is the catalog's `calendar.links.when_date`, "10/27 14:05" in English), Next (not while the key is
  lost), the notes - "Venter på telefonen" until the report lists its key, "Oppdater Vibb One …" without
  `calendar_feeds_v1`, the phone's refused series, `unknown_tz`, `cut`, churn, the LAN warning -, "Hent nå" (`/fetch`:
  at most once per source per 5 minutes - else `?notice=fetch_wait` -, the fetch before the answer, in
  `settings_log::NOT_SAVES`, back to `#fetch`)), "Fjern…" (confirmed, back to the Calendar page's `#sources`). The add
  form (Add Calendar's link, `POST /devices/{id}/calendar/links`): the link and an optional name (empty → the feed's
  `X-WR-CALNAME`, else the host); plain http judged by what the name resolves to (`lan_addresses`: LAN or tailnet only -
  the tailnet without the warning -, else refused); no userinfo; at most 2000 characters; without the music key the
  page says why and has no form (QA L11); the add check is one fetch that must give a VCALENDAR (an empty one is
  fine); a refusal renders Add Calendar again as typed (400), the reason under the link, never the body; a link
  already a source is attached with no fetch; a save is a 303 to the link's page. "Calendars shown" lists a link's
  calendar at once by its computed key
  (`calendar_feeds::calendar_key`: LOCAL, `Vibb One`, `vibb-feed-<uid>`), "Kalenderlenke · Venter på telefonen" until
  reported, then "Kalenderlenke"; an ICSx5 calendar with a link's host says "Kan være den samme som kalenderlenken …".
  The kid's calendar is never a link's (LOCAL, access 200). Every route behind the full session
  (`every_calendar_route_needs_a_session`); the settings log names routes and ids, never the link.
- Tests: `ics::tests` (the fixtures in `testdata/calendar_feeds/`: itslearning - its fold through "å" and the producer's
  broken "R omerriket" -, Outlook, Google, series, cancelled, empty, html, contract; the caps, a 70 KB line, Latin-1),
  `calendar_feeds::tests` (the contract, normalization, names), `tests::calendar_feeds` (the add and its refusals, the
  address rules, the page, the endpoint, the fetch cycle, a removal mid-fetch, Remove and a deleted phone, the caps).

## CalDAV accounts (design 72 step 2, `src/caldav.rs`, branch `caldav-read`)

Read-only. The parent signs in to a CalDAV account with an **app password** on Device › Calendar (Add Calendar, below
"Device › Calendar's sources"); each calendar ticked on a phone's page becomes a `calendar_sources` row of kind `caldav` and reaches the
phone exactly as a link (`calendar.feeds`, `GET /api/devices/calendar/feeds/{uid}`, the phone's `vibb-feed-<uid>`
calendar): the launcher and the contract are unchanged. Scope: basic auth (iCloud, Fastmail, Nextcloud, Radicale);
out: Google (OAuth only - its secret ICS address works as a link), digest, client certificates.

- **Model** (`0110_caldav_accounts.sql`): `caldav_accounts` - **the family's**, shown on every phone's page, each phone
  ticking its own calendars (one row per normalized server address and username; Test with the same pair again
  replaces the password). The app password sealed with the music key (`caldav-v1:<fingerprint>:<id>`), never in a
  log, page, policy, report or the settings log (`Login` and the account row have no `Debug`). `hosts` (JSON) - the
  only hosts the password may go to; `calendars_json` - what the last passing Test listed (`[{id, href, name}]`, `id`
  = 16 hex of the href, what the tick form posts - never an address in a form); `state` `ok` / `login_refused` /
  `host_changed` / `untested` (a first Test that never passed: hidden, its password blanked); `test_error` (a code,
  worded by `handlers::calendar::caldav_why`), `tested_at` (the 10 s gap), `lan`/`lan_http`. `calendar_sources` gains
  `account_id` (cascade): a CalDAV source's `url_sealed` is the collection's address (sealed as a link is) and `etag`
  its validator (`<getctag>|<sync-token>`), `last_modified` the last REPORT's time. No account goes at once (code QA
  L3): the trigger `caldav_accounts_unused` stamps `unused_since` when its last calendar leaves the last phone
  (`caldav_accounts_used` clears it), and `caldav::sweep` (each scheduler pass) deletes an account no phone has a
  calendar of a day after both that and its last Test - one switch flipped by mistake never loses the login. A source's uid = 32 hex of SHA-256 over `vibb-caldav:` + server address NUL username NUL
  collection address - the account's identity, not its row id, so an account removed and signed in again gives each
  calendar its key (settings, rules) back, as a link does. Step 3's "the kid's own CalDAV calendar" flag would go on
  the phone's `device_calendar_feeds` row (nothing built).
- **Test** (`caldav::test_new` / `test_again`, `discover`): the server field takes an address (https unless written
  http, no userinfo, no query) or an e-mail - its domain tried, `icloud.com`/`me.com`/`mac.com` →
  `caldav.icloud.com`, `fastmail.com`/`.fm` → `caldav.fastmail.com` (`KNOWN_DOMAINS`); an empty username is the
  e-mail. Plain http only to a name that resolves to the LAN or the tailnet (as a link). Discovery one hop at a time
  (`follow: false`, at most 5 per request, each hop through `calendar_feeds::route`; never https → http, a LAN address
  only on its own host and port): PROPFIND Depth 0 for `current-user-principal`, `calendar-home-set`, `resourcetype`
  at `/.well-known/caldav` then `/` (or the path typed), `calendar-home-set` on the principal, Depth 1 on the home set
  (`resourcetype` calendar, `supported-calendar-component-set` with VEVENT or none, `displayname` - else the decoded
  last path segment). At Test **a host gets the password only once it asked for it** (a 401 without it; a mail
  domain's web host that never asks never gets it); a 401/403 with it is `login_refused`, never retried. The hosts
  that got it are the account's `hosts`, shown: "Passordet sendes bare til: …" - a calendar on a host that never
  asked isn't offered. Every address the server names (principal, home set, calendars) is held as a redirect is
  (code QA M1): a public account never reaches the LAN (an http href resolving there is `private_address`), a LAN
  account only its server's host and port. A typed password is stored only when the Test passes (QA L5); a
  refused stored password stops the account, a refused new one doesn't. At most once
  per 10 s per account (`?notice=caldav_wait`; a new account's form re-renders with it). A pass re-seals the
  calendars under today's key and makes every failing calendar of the account due at once. XML by `quick-xml`'s
  `NsReader` (`caldav::multistatus`: any prefix, CDATA, the five entities and numeric ones; a DOCTYPE with an internal
  subset is refused).
- **The fetch** (`caldav::refresh_source`, from `calendar_feeds::refresh`; the same schedule, backoff, empty hold,
  churn, caps, rev and nudge): no request while the account isn't `ok` or its password can't be opened (`key_lost`);
  a calendar the last Test no longer listed is `not_listed` (that calendar alone, no request; QA M2); PROPFIND Depth 0
  `getctag` + `sync-token` - the stored validator and a REPORT within 24 h → unchanged (QA L6: the window moves);
  else one `REPORT calendar-query`
  (`time-range` today −60 d .. +401 d, `calendar-data`), capped at `Kind::Calendar`'s 5 MB (`too_big` keeps the set),
  every resource's VCALENDAR concatenated into one body for `ics::parse` (a resource that isn't one whole VCALENDAR
  counted `bad`). Only to `hosts`: another host - a redirect - is `host_changed` before any request to it. **A
  401/403 marks the account `login_refused`** (QA M5) and **another host `host_changed`**: no request for any of its
  calendars until Test passes; every phone's row says so at once (`LinkRow.account_state`). A fetch that read an older
  password never stops the account over a Test that saved a new one (`AND password_nonce = ?`, QA L4).
- **The page** (since 2026-11-01 the account's own page, `GET .../calendar/caldav/{account}`, below): the stop as the
  page's alarm ("Passordet ble avvist - …", "Serveren svarte fra en ny vert - …", "Serverens nøkkel er byttet - …"),
  Server (iCloud's and Fastmail's names, else the host) and Logget inn, the hosts line ("Passordet sendes bare til:
  …") and the LAN warning; "På denne telefonen" (`#calendars`): a switch per calendar for this phone (one form, `cal`
  per listed calendar + `on_<id>`, auto-saving; a ticked one says "Hentet 15:10 · 12 hendelser" or its failure; a
  newly ticked calendar is fetched before the answer; at most 10 links and calendars a phone, `?notice=caldav_full`),
  and a calendar of this phone the account no longer lists (`not_listed`) without a switch, with Remove
  (`.../links/{uid}/remove`, back to `#calendars`); "Test på nytt…" (`#test`, a disclosure: an optional new app
  password, the stored one otherwise; back with `?notice=caldav_tested` - "Logget inn 15:12." or "Siste test: …" -,
  `caldav_wait`, `caldav_retype` (none to use) or `caldav_too_long`, the form open for the last three); "Fjern konto…"
  (confirmed: off every phone, the password deleted; back to the Calendar page's `#sources`). The new-account form is
  Add Calendar's CalDAV account: server or e-mail, username, app password, "Test og logg inn" (a pass goes to the
  account's page; a refusal re-renders Add Calendar as typed with CalDAV chosen, 400, the reason under its field -
  the username's or the password's own, else the server's -, never the password; no music key → no form). A ticked
  calendar is "CalDAV" in "Calendars shown"; it has no link page of its own (its uid's page goes to the account's).
  Test routes are in `settings_log::NOT_SAVES`; the ticks and Remove are saves (route and ids only).
- Tests: `caldav::tests` (the server field, the multistatus reader, uids), `tests::caldav` (a fake CalDAV in the test
  source - `CannedFetch::answer_method`/`redirect_method`/`basic`: iCloud's shape with a redirect chain and the
  calendars on another host, Nextcloud's by path, the challenge order, the ticks and the phone's policy, an unchanged
  and a changed ctag, a revoked password with no further request, Test's gap, another host, the refusals, removal).
  `testdata/caldav/` holds answers written in iCloud's and Nextcloud's shape (fake ids), **not captures**: a real
  server confirms them (design 72 §10's Radicale run, and an iCloud/Fastmail/Nextcloud account).

## Device › Calendar's sources (the look of 2026-11-01, variant B, branch `calendar-sources-ui`)

The user's pick of the mockups (variant B, version 2, drawn with the PWA's own parts): the calendar links and the CalDAV
accounts are one group on Device › Calendar and each has a page of its own (`handlers::calendar_sources`).

- **The group** "Hentes av serveren" / "Fetched by the server" (`#sources`, `calendar_sources::source_rows`, above
  "Calendars shown"): a `.group-icons` group, one `<a class="row">` per link (`.tile-teal`, its name, "host · Hentet
  15:10" - or, orange, the failure and "Prøver igjen 16:10", the held empty answer, the lost key) and one per CalDAV
  account (`.tile-indigo`, the username, "iCloud · 1 av 3 kalendere" - or, orange, the short stop "Passordet ble
  avvist - test på nytt" or "… · 1 kalender kan ikke hentes"), each with its chevron, then "Legg til kalender…"
  (`a.row.row-button`, to Add Calendar) or the no-key line (`data-sources="off"`) or the 10-a-phone line
  (`data-sources="full"`). The key-new alarm (`links-key-new`) stays above it. One footer and one ⓘ for both kinds
  (`calendar.sources.*`). No status dots: a failure is `tone-orange` text, as the Devices status line.
- **Add Calendar** (`GET .../calendar/add`, `device_calendar_add.html`): "Kilde" with two choice rows - "Lenke (https
  eller webcal)", "CalDAV-konto" - and the check mark, Add App's CSS-only swap (two radios outside the forms,
  `#kind-link`/`#kind-caldav` `:checked ~`, `style.css?v=66`); `?kind=caldav` chooses the account. Each kind is its
  own form to the route it always had (`POST .../calendar/links`, `POST .../calendar/caldav`), its fields as
  `.form-group .field` with the label over the field, a footer, the filled `.button-wide` ("Legg til", "Test og logg
  inn"). A refusal renders this page again (400) as typed with its kind chosen and the reason under its field
  (`aria-invalid`, autofocus); a pass is a 303 to the new source's page. The forms leave the page, so they have no
  fragment (`settings_pages::leaves_the_page`).
- **A link's page** and **an account's page**: above ("Calendar links", "CalDAV accounts"). Every POST goes back to
  the page its form is on, at its group (`#name`, `#fetch`, `#calendars`, `#test`; a removal to the Calendar page's
  `#sources`), so a save never jumps to the top; a source or account gone since the page loaded is a 303 to
  `#sources`. Without JS: the name and the switches have their `<noscript>` Save, the choice and the disclosures work
  as they are. Room is left on the account's page for design 72 step 3's "the kid's own calendar" (nothing built).
- Tests: `tests::calendar_feeds::add_calendar_picks_its_kind_and_a_gone_source_goes_back`, the rows and pages in
  `the_page_shows_a_link_and_waits_for_the_phone` and `tests::caldav::test_lists_the_calendars_and_where_the_password_goes`,
  the refusals' kind and field in `a_refused_add_renders_the_form_as_typed` and
  `a_refused_test_renders_the_form_as_typed_and_never_the_password`, the three GETs in
  `every_calendar_route_needs_a_session` and `shell::every_form_on_every_page_lands_on_its_own_group`.
