# 72 - Calendars without sync apps: paste a link in the PWA

Status: design note, architect 2026-10-27, on `main` at b6a8b858. Not built. Both directories (server + launcher); step
2 is server only. PLAN: "Calendars without ICSx5/DAVx5". The user, 2026-10-27: "Er det DAVx5 og ICSx5 gjør vanskelig
nok til at det rettferdiggjør to ekstra installerte apper?? Hadde vært mye lettere om det bare var å lime inn lenker i
PWA for ical/caldav and that's it." Then: "Og kjør! Ics er pri 1, dav pri 2." Accepted shape: **the server fetches
and parses; Vibb One writes the events into a local calendar through the provider; a CalDAV password lives on the
server, never on the kid's phone; ICSx5/DAVx5 stay possible.** [AOSP] = read in AOSP's source; [verify] = the
emulator answers it (§10).

## 1. What we build on

- Design 69 (Dagen) reads `CalendarContract.Instances` of every calendar with `SYNC_EVENTS = 1`; a calendar's **key** =
  16 hex of SHA-256 over account type NUL account name NUL `Calendars.NAME` (`day/CalendarKey.kt`) carries the
  parent's settings (`device_calendars`) and title rules. `CalendarStore.ensureKidCalendar` (`:423-455`) already makes
  a LOCAL calendar (account `Vibb One`, NAME `vibb-kid-calendar`) through the sync-adapter URI; `WRITE_CALENDAR` is
  self-granted (design 69 step 3); `CalendarStore` is the provider's only writer and stays so.
- The server fetches pasted links through `music_net.rs`: per-`Kind` caps and timeouts; `Reach` (a public name never
  reaches a private address, no rebinding gap; a LAN/tailnet link judged by `music_sweep::lan_addresses` and pinned,
  same-host redirects); one request in flight server-wide (`AppState.music_fetch`, `Gated`); `net::USER_AGENT`;
  `SourceRequest` has no `Debug`. Delivery: a version in the policy and a per-phone `ETag` endpoint fetched only when
  it moves (`music_api::versioned_json`; `dns_filter_version` → `DnsFilterEngine.refreshIfNeeded`,
  `MdmSyncWorker.kt:161`); a change nudges through `state.command_notify`. Secrets: `music_secret.rs` (`seal_with`,
  AES-256-GCM, the fingerprint in the associated data, the key in no backup). `settings_log` never logs form values.

## 2. The model (server)

**A link is per phone; the fetch is shared.** The parent adds a link on one phone's Calendar page; the same URL added
on a sibling's page attaches to the same source (one fetch, one stored set). Tables (migration: the next free number at
build time; `pwa-i18n-1` holds 0104):

- `calendar_sources` - `id`, `uid` (TEXT UNIQUE, 32 hex = SHA-256 of `vibb-feed:` + the normalized URL; for step 2
  `vibb-caldav:` + account id + collection href), `kind` (`ics`|`caldav`, CHECK), the URL **sealed** (`url_sealed`,
  `url_nonce`, `url_key_fp`; associated data `calendar-v1:<fingerprint>:<uid>`) and its `host` in clear (the PWA's
  label, the only part logged), `lan` (NULL/0/1, judged as the sweep does: public stays public for good), `etag`,
  `last_modified`, `rev` (16 hex of SHA-256 over the served JSON), `feed_json` (the served document, §3), `ok_at`,
  `tried_at`, `failures`, `next_at`, `error` (a code, worded at render), the counts `events`, `cut`, `unknown_tz`,
  `bad`.
- `device_calendar_feeds` - `device_id`, `source_id` (FKs, cascade), `name` (1-60 after `calendar::clean`),
  `created_at`; PK (device, source). A source's last phone gone → the source deleted in the same transaction. Caps: 10
  links per phone, 50 sources per server (a family's; more is a multi-tenant server, out of scope).
- The uid is the URL's hash so that a link removed and added again gets the same key on the phone: its
  `device_calendars` row and title rules come back (design 69 greys a key the phone lacks, never deletes it). The hash
  reveals nothing of a token. **The set is one JSON blob per source**: nothing queries an event; the PWA needs counts.
- **Retention**: the set is replaced at every successful fetch, nothing historical kept; a failing fetch keeps the last
  good set (a school server's hiccup must not empty the kid's day); a removed source is deleted at once. A backup zip
  holds the set as of then and the URL sealed (unusable without `data/keys/`).

### 2.1 The fetch (`server/src/calendar_feeds.rs`)

- **Schedule**: one task; each source every **60 min** (decision 2), the most overdue first, 4 s apart, through
  `music_fetch` (one outbound load rule). Conditional GET (`etag`/`last_modified`); 304 keeps the set; every 200 is
  parsed (milliseconds; §2.2's horizon depends on today). A failure backs off as the sweep's: 15 min · 4^(n-1), capped
  at the interval, daily after 7 days - per source, which at ≤ 50 sources is the per-host hold.
- **Request**: `music_net::Kind::Calendar` - a cap of **5 MB** decoded (`truncated` = refused as `too_big`, the last
  set kept), 60 s overall; `Reach` and pinning exactly as an RSS feed's (`lan_addresses`, `pinned_to`). The body is
  decoded by the `Content-Type` charset through `encoding_rs` (already in the tree), else UTF-8 with U+FFFD; it must
  begin (after a BOM and whitespace) with `BEGIN:VCALENDAR`, else `not_calendar`. Error codes: `not_found` (404/410),
  `status` (other non-200, the number kept), `network`, `timeout`, `too_big`, `not_calendar`, `private_address`,
  `key_lost` (the URL can't be unsealed: "add the link again"). No body text ever reaches a log, a page or an error.
- **Change**: a new `rev` → `feed_json` and the counts in one transaction (that first checks the source still exists),
  then a nudge to its phones; the same `rev` writes only the state. **Fetch now**: per link, once per 5 minutes.

### 2.2 Parsing and normalizing (`server/src/ics.rs`, our own, no new crate)

RFC 5545 at the content-line level is small, and our feeds are hostile-shaped: unfold (CRLF or LF followed by a space or
tab; itslearning folds mid-word - "R omerriket" keeps the feed's own space, design 69 §5), split `NAME;params:value` with
quoted parameter values, unescape TEXT (`\n \, \; \\`), a BEGIN/END stack at most 4 deep. Limits while parsing:
an unfolded line cut at 64 KB, at most 20 000 VEVENTs read (then stop, `cut`). Read: VEVENT, its VALARMs, VTIMEZONE
(only its `TZID` and `X-LIC-LOCATION`); everything else ignored. `ical` (Apache-2.0) and `icalendar` (MIT/Apache)
parse, but neither caps or normalizes zones, so that code stays ours anyway; no `rrule` - **the provider expands**.

Per VEVENT: `UID` (missing → 32 hex of SHA-256 over DTSTART + SUMMARY, stable while unchanged), `RECURRENCE-ID`,
`SUMMARY` (cut to 200 code points), `DESCRIPTION` (2000, `calendar::DESCRIPTION_MAX`), `LOCATION` (200), control
characters but `\n` out, `DTSTART` (required, else `bad`), `DTEND` or `DURATION` (neither: zero length, or one day for
a date), `STATUS`, `SEQUENCE`, `RRULE`, `RDATE`, `EXDATE`, and the alarms. Then:

- **Series**: a master plus its overrides (same UID with a RECURRENCE-ID) = one wire series. Two masters with one UID
  (a bad feed): the higher SEQUENCE, then the later LAST-MODIFIED, then the later in the file. An override without a
  master becomes a standalone event (Google shares single instances so).
- **Cancelled**: a cancelled master (single or recurring) is dropped; a cancelled override is kept with `status:
  cancelled` (the provider then makes no instance for it, `CalendarInstancesHelper.java:510` [AOSP]).
- **RRULE**: passed through if it matches `[A-Z0-9=;,+-]{1,300}` after upper-casing; an `UNTIL` in local time is
  rewritten to UTC (`...Z`) for a timed event, to a DATE for an all-day one (what the provider's parser expects
  [verify]). Anything else: the master kept as one occurrence, counted `bad`.
- **Alarms**: a VALARM whose TRIGGER is a duration relative to the start (not `RELATED=END`, not absolute), 0-10 080
  minutes before, as a list of minutes, at most 5, de-duplicated. Dagen's "own" mode reads at most 120 (design 69 §5).
- **Horizon** (decision 3): a single event that ended more than **60 days** ago or starts more than **400 days** ahead
  is dropped; a recurring master is kept unless its UNTIL is more than 60 days ago (COUNT is not expanded).
- **Caps**: at most **2000 events** per source (a master and each override count one) and a served document of at most
  **2 MB**; past either, the events farthest from today go first (recurring masters count as today), counted `cut`.

### 2.3 Time zones

What real feeds send: itslearning (the user's feed, design 69 §5/§6.6) UTC with a `Z` - including its RRULEs; Google's
public/secret ICS `TZID=Europe/Oslo` with a VTIMEZONE and `X-LIC-LOCATION`; Outlook/Exchange published calendars
Windows names (`TZID=W. Europe Standard Time`, sometimes "Romance Standard Time") with a VTIMEZONE; hand-made feeds
now and then floating times. The wire carries every time as the feed wrote it, in its zone, and the **phone's** tz
database does the arithmetic (as ICSx5 on the phone does), so the server never converts a recurring time:

1. `...Z` → `tz: "UTC"`. A TZID that chrono-tz knows (Olson) → passed through.
2. Else: the VTIMEZONE's `X-LIC-LOCATION` if Olson; a known prefix stripped (`/mozilla.org/<n>/`,
   `/freeassociation.sourceforge.net/Tzfile/`, `/softwarestudio.org/Tzfile/`); a Windows name through the vendored
   **CLDR `windowsZones.xml` territory-001 table** (about 140 rows, ~6 KB, `server/src/windows_zones.txt` with the CLDR
   version in its header; Unicode License v3, permissive and GPL-compatible, a NOTICE.md line; a test that every target
   parses in chrono-tz).
3. Else **unknown** → treated as floating, counted `unknown_tz` ("3 events with an unknown time zone - shown in the
   phone's time").
4. **Floating** (no TZID, no Z) → `tz: null`: the phone writes it in its own zone (§4.2).
5. **All-day** (`VALUE=DATE`) → `start`/`end` as `YYYYMMDD`, `all_day: true`; the phone writes UTC midnights with
   `eventTimezone` UTC (design 69 §2.4: all-day instances are on their UTC dates).
- EXDATE/RDATE/RECURRENCE-ID in another zone than DTSTART are converted on the server into DTSTART's zone (chrono-tz;
  one instant, no recurrence arithmetic). **DST**: a TZID rule stays 08:15 local across 25 October; the itslearning
  `Z` rule moves an hour locally - faithful to the feed, as with ICSx5 (design 69 §6.6). Both are test rows.

## 3. Delivery to the phone

A school feed is ~200 events × ~400 bytes = 80 KB; the caps allow 10 × 2 MB: too big for the policy, downloaded whole
at every sync. So:

- **The policy** (`calendar.feeds`, inside design 69's `calendar` object, always sent, `[]` when none):
  `[{"id": "<uid>", "name": "Skolen", "rev": "3f9c0a12e4b7d856"}]`; `rev` is never null (the add check made a set,
  §5). Pinned by `policy_json_keys_snapshot` and `PolicyResponseCompatTest`.
- **The endpoint** `GET /api/devices/calendar/feeds/{uid}` (bearer, `device_routes.rs`): 404 unless this phone has the
  link; `ETag` = rev, `If-None-Match` → 304, gzip - `music_api::versioned_json`, made `pub(crate)` and reused. Body:
  `{"v": 1, "id", "rev", "events": [series]}`, a series being `{"uid", "rev" (16 hex over the series' JSON), "summary",
  "description", "location", "start", "end", "tz", "all_day", "rrule", "rdates": [], "exdates": [], "status"
  (confirmed|tentative|cancelled), "alarms": [15], "overrides": [{"recurrence_id", "summary", "description",
  "location", "start", "end", "status", "alarms"}]}`; times in RFC 5545 basic form in `tz` (`20261027T081500`,
  `20261027`).
- **The contract** (API in both dirs, one commit): `server/testdata/calendar_feeds.json`, byte-identical in
  `launcher/app/src/test/resources/`: `policy` (a `calendar.feeds` example), `feed` (the endpoint's document for
  `server/testdata/calendar_feeds/contract.ics`), `keys` (uid → the calendar key, so both sides hash alike), and
  `provider` (the rows the launcher must write for `feed`). Server `calendar_feeds::tests::the_contract` must produce
  `feed` from the .ics and `keys`; launcher `CalendarFeedsTest` maps `feed` to exactly `provider` and checks `keys`;
  both compare the copies. It joins both workflows' path lists and the root `CLAUDE.md`. Capability `calendar_feeds_v1`.
- **When**: the server nudges on a new rev and on every add/rename/remove; at a sync with a fresh policy the launcher
  fetches only the feeds whose `rev` differs from their calendar's `CAL_SYNC1` (§4) - nothing changed, no request; a
  rename is applied from the policy alone.

## 4. The phone writes (`CalendarStore`, pure `day/FeedPlan.kt` + `day/FeedEvents.kt`)

### 4.1 One calendar per link

`ACCOUNT_TYPE_LOCAL`, account `Vibb One` (as the kid's), `Calendars.NAME` = `vibb-feed-<uid>` - so the design-69 key
is the server's to compute too, and the PWA can list the calendar at once (§5). `CALENDAR_DISPLAY_NAME` = the policy's
`name` (updated at the next apply when the parent renames), `CALENDAR_ACCESS_LEVEL` = `CAL_ACCESS_READ` (200: the
PWA's and the phone's kid-write rules - contributor or more, not LOCAL - never pick it, and calendar apps show it
read-only), `SYNC_EVENTS` 1, `VISIBLE` 1, a calm colour picked from a fixed palette by the key (not configurable),
`CAL_SYNC1` = the applied feed rev, `CAL_SYNC2` = `FEED_MAPPING` (a constant a launcher that maps differently bumps:
everything rewritten). LOCAL calendars survive account cleanups (only vanished accounts' calendars go [AOSP]).

### 4.2 Events, through the sync-adapter URI

- A **master row**: `_SYNC_ID` = uid, `UID_2445` = uid, `SYNC_DATA1` = the series rev, `SYNC_DATA2` = the zone a
  floating series was written in (else empty), TITLE/DESCRIPTION/EVENT_LOCATION, `STATUS`; a single event `DTSTART` +
  `DTEND`; a recurring one `DTSTART` + `DURATION` (`PT<n>S`, all-day `P<n>D`; the provider needs DURATION for a
  recurrence) + `RRULE`, `RDATE`, `EXDATE` (comma-joined UTC `yyyyMMdd'T'HHmmss'Z'`, all-day `yyyyMMdd` [verify the
  provider's `RecurrenceSet` reads both]); `EVENT_TIMEZONE` = `tz`, the phone's `ZoneId.systemDefault()` when null,
  `UTC` for all-day. Times converted with `java.time` (the phone's tzdata).
- An **override row**: `_SYNC_ID` = uid + `#` + recurrence id, `ORIGINAL_SYNC_ID` = uid, `ORIGINAL_ID` = the master's
  new `_ID` (a back reference in the same batch), `ORIGINAL_INSTANCE_TIME` = the recurrence id as UTC ms,
  `ORIGINAL_ALL_DAY`; a cancelled one `STATUS_CANCELED` (the provider drops that instance) [verify the moved and the
  cancelled instance on Dagen].
- **Reminders**: one `Reminders` row per alarm minute, `METHOD_ALERT`. Dagen's "own" mode reads them; nothing rings
  (Etar goes, design 69 question 2).
- **The plan** (`FeedPlan.kt`, pure, `FeedPlanTest`): given the wire series (uid, rev) and the calendar's rows (id,
  `_SYNC_ID`, `ORIGINAL_SYNC_ID`, `SYNC_DATA1`, `SYNC_DATA2`), the floating zone now and `FEED_MAPPING`: a series whose
  uid is gone → delete its rows (master and overrides by `ORIGINAL_SYNC_ID`, explicitly); whose rev differs, or that is
  floating and was written in another zone, or after a mapping bump → delete and insert the whole series; else nothing.
  A row of the calendar that belongs to no series (a half-made one) → deleted.
- **Applying**: one `applyBatch` per series (master, overrides, reminders: atomic), on the store's worker thread, in the
  plan's order (deletes first); `CAL_SYNC1` is written **last**, after every series. Interrupted (a process death, the
  provider gone before the first unlock, a series the provider refuses - caught, logged, skipped): the rev stays old, the
  next sync plans again and every series already written matches - **idempotent and resumable**. The provider's change
  notification is already debounced to one reload a second.
- **Feeds gone**: a calendar named `vibb-feed-*` under `Vibb One` whose id is not in a fresh policy's `calendar.feeds`
  is deleted (its events go with it, the provider's `calendar_cleanup` trigger [AOSP]). Only an accepted fresh policy
  whose `calendar` has a `feeds` array drives this: missing (an older server), a cached policy or `UNKNOWN` deletes
  nothing. The parent's settings and rules for that key stay on the server (greyed with Remove, design 69 §3.2) and
  return if the same link is added again.
- **Floating times**: the next sync after the phone's zone moved rewrites them (`SYNC_DATA2`; no new receiver).
- Hook: `MdmSyncWorker` after `CalendarStore.policyChanged()`: `CalendarFeeds.refreshIfNeeded(context, api,
  freshPolicy.calendar)` (as `DnsFilterEngine.refreshIfNeeded`), `MdmApi.calendarFeed(id, ifNoneMatch)`. A failed GET
  changes nothing; the next sync tries again.

## 5. The PWA: Device › Calendar › "Kalenderlenker" / "Calendar links"

A new group `#links`, above "Calendars shown" (it feeds that list). Every word through `i18n` (`calendar.links.*`, nb +
en, the standing rule).

- **Add**: the link and a name ("Skolen"). The URL rules: `webcal://`/`webcals://` → `https://`; at most 2000
  characters; no userinfo (step 2 has proper logins); **https**, or plain http to a Tailscale address; plain http to
  another private address saved with the server-address rule's warning (`config::check_server_url`'s four classes,
  security review L8); **plain http to a public host refused** (decision 5) - the link's token would cross the internet
  in clear. Then the **add check**: one fetch through §2.1 (the gate, at most 60 s) that must parse with at least one
  VEVENT or an empty VCALENDAR; a refusal re-renders the form as typed with the reason (400), never the body. The same
  URL already a source → attached, no fetch. A save is a 303 to `#link-<uid>` (no jump to the top).
- **Each link**: the name (auto-saving), the host only (the link is sealed, never shown again), a status line ("Hentet
  14:05 · 182 hendelser"; "Venter på telefonen" until the report lists the key; "oppdater Vibb One" without
  `calendar_feeds_v1` - the server fetches anyway; the error in words with the last good set's age; `unknown_tz`;
  `cut`), Fetch now, and Remove (confirmed: "The calendar goes off this phone; ICSx5's calendars are not touched").
- **"Calendars shown"**: a link's calendar is listed **at once** with its key computed on the server ("Skolen ·
  Kalenderlenke"), its shown/reminder controls working before the phone has it (they are `device_calendars` rows by
  key), marked "Venter på telefonen" until the report has the key; then a row like any other, labelled
  "Kalenderlenke" (its key matches a link), not "Phone". Never offered as the kid's calendar.
- A reported ICSx5 calendar with a link's host gets a hint: "Kan være den samme som kalenderlenken Skolen". The empty
  page's setup steps (design 69 §6.1) lead with the link; ICSx5/DAVx5 become "Or, with a sync app".
- Routes behind `require_full_auth` (`tests::calendar::every_calendar_route_needs_a_session` grows); Fetch now and
  step 2's Test go into `settings_log::NOT_SAVES`; add/rename/remove are saves (route and ids only - never the URL).

## 6. Step 2: CalDAV, read-only

- **The account** (`caldav_accounts`: `id`, the server URL, `username`, the app password sealed with associated data
  `caldav-v1:<fingerprint>:<account id>`, `hosts` (the hosts the password may go to), `principal`, `home_set`, state).
  One login can feed several phones; each ticked calendar is a `calendar_sources` row of kind `caldav` (its collection
  href, `getctag`/`sync-token`), subscribed per phone as in §2.
- **Discovery**, one hop at a time (`follow: false`, every hop judged like a link - the §5 address rules, `Reach`,
  LAN pinning): `/.well-known/caldav` (a redirect), `PROPFIND` Depth 0 `current-user-principal`, then
  `calendar-home-set`, then `PROPFIND` Depth 1 on the home for `resourcetype`, `displayname`,
  `supported-calendar-component-set` (VEVENT only). XML through `quick-xml` (already in the tree). **The password goes
  only to the hosts the parent confirmed at Test**: the form shows "Passordet sendes til: caldav.icloud.com,
  p45-caldav.icloud.com" (iCloud's principal lives on another host) and saves that list; a hop to another host later
  fails `host_changed` ("Test again") before any request (reqwest's own cross-host header drop is not relied on).
- **Fetch**, each interval: `PROPFIND` Depth 0 `getctag` + `sync-token` (one small request); unchanged → done; changed
  → one `REPORT calendar-query` with a `time-range` (−60 d .. +400 d, §2.2's horizon) for `calendar-data`; each
  resource's VCALENDAR through `ics.rs`; the same set, caps, rev and delivery. No `sync-collection` (RFC 6578): per-href
  bookkeeping for bytes we don't need to save (≤ 2000 events, a full query only after a change).
- **In scope**: iCloud, Nextcloud, Fastmail (app passwords), Radicale and other basic-auth servers. **Out**: Google
  (CalDAV only with OAuth; its secret ICS address works in step 1), digest auth, client certificates.
- **The form**: the server (or an e-mail, its domain tried), the username, the app password; Test lists the calendars
  to tick; Save stores the account and this phone's ticks. The password is never rendered back.
- **Threats**: SSRF through the CalDAV URL - the §5 rules on every hop, a body never echoed; credentials never in a log
  (`SourceRequest` has no `Debug`; errors carry codes; the settings log has no form values), never in the policy, a
  report, an event or a page; a lost key = "log in again". Server M; the launcher unchanged.

## 7. Step 3: the kid's own events to CalDAV (not planned)

If wanted: the parent picks a ticked CalDAV calendar as the kid's; the phone posts its own events (`vibb-<tag>-` UIDs)
to `POST /api/devices/calendar/kid-events`; the server `PUT`s each `<uid>.ics` (`If-Match`/`If-None-Match: *`) or
`DELETE`s it, and the next fetch reads them back. The first kid's event to leave the phone - the user's call.

## 8. What ICSx5/DAVx5 keep

Everything (`CALENDAR_SYNC_APPS`, `on_home`, never suspended, `requestSyncIfDue`); §6w step 5 stays, the other path.

## 9. Privacy

The server now holds, for as long as a link exists, the linked calendars' event texts (title, description, place,
times, alarms) and the link sealed; each fetch replaces the set; removing the link deletes it all at once. It never
holds the kid's own events (they stay on the phone unless step 3), and never sends an event anywhere but the phones
with the link. A CalDAV password is sealed with the server's key and never leaves the server. Design 69's "the server
never sees an event" becomes "no event ever leaves the phone"; `docs/notes/server/calendar.md` says so. The user weighed
it (PLAN, 2026-10-27): parsing on the server shares more with it, but the server already holds the rest of the
family's setup, and the password stays off the kid's phone.

## 10. Testing (lean)

- **Server** (`ics::tests`, `calendar_feeds::tests`; fixtures `server/testdata/calendar_feeds/`): `itslearning.ics`
  (folded lines, "R omerriket", "5. trinn", 1500-character descriptions, a UTC weekly RRULE across 2026-10-25),
  `outlook.ics` (`W. Europe Standard Time` + VTIMEZONE, an unknown TZID counted), `google.ics` (VTIMEZONE +
  `X-LIC-LOCATION`, a `/mozilla.org/` TZID), `series.ics` (all-day + RRULE + EXDATE, a moved and a cancelled instance,
  an override in another zone, a local UNTIL, two masters with one UID, an orphan override), `cancelled.ics`,
  `contract.ics`; generated: 5 MB + 1 byte (`too_big`, the last set kept), 2001 events (`cut`, the far ones), garbage,
  a 70 KB line. Fetch: 304 keeps the set; a new rev nudges exactly the subscribed phones; a failure keeps the set and
  backs off; LAN pinned, a public name answering a private address refused; a source removed mid-fetch writes nothing;
  the last unsubscribe deletes the source; the zones table parses. Pages: the URL classes, a refused add re-rendered
  as typed, the 303 anchors, the computed key with "Venter på telefonen", Fetch now's gap, no URL in the settings log,
  every route behind the session; `policy_json_keys_snapshot`; the endpoint's 404/304/gzip.
- **Launcher**: `CalendarFeedsTest` (the contract), `FeedPlanTest` (no change → no op; a new, changed, gone, half-made
  series; a zone change rewrites only floating ones; a mapping bump rewrites all; `feeds` missing deletes nothing, `[]`
  every feed calendar), `FeedEventsTest` (DURATION, EXDATE strings, all-day UTC midnights, floating, an override's
  `ORIGINAL_INSTANCE_TIME`), `PolicyResponseCompatTest`.
- **Emulator §6y** (`jelly36`; the feed from the host as in §6w step 5, `http://10.0.2.2:8088/school.ics`, saved with
  the LAN warning): "Venter på telefonen" → the calendar appears, Dagen shows the series with its EXDATE, the moved and
  cancelled instance, the all-day camp, the VALARM in "own" mode; the UTC RRULE on 2026-10-25, a TZID one on
  2027-03-28; a title rule on it; the feed edited → Fetch now → the next sync has it, a second sync makes no request;
  the process killed mid-apply → the next sync completes it; removed → gone, settings greyed; added again → back.
  The [verify] items of §2.2 and §4.2 (UNTIL, EXDATE forms, the override linking) are answered here.

## 11. Steps and sizes

1. **ICS end to end** (one branch, both dirs, the contract commit first): server **S-M** (migration, `ics.rs`, the
   zones table, `calendar_feeds.rs`, `Kind::Calendar`, the endpoint, the policy key, `#links`, the "Calendars shown"
   merge, i18n) + launcher **S-M** (`calendar.feeds`, `MdmApi.calendarFeed`, `FeedPlan`, `FeedEvents`, the feed writes,
   the sync hook, `calendar_feeds_v1`). Docs: `calendar.md`, `launcher/CLAUDE.md`, root `CLAUDE.md`, NOTICE.md.
2. **CalDAV read-only**: server **M** (`caldav.rs`, `caldav_accounts`, the form and Test, discovery and fetch); fixtures
   of Nextcloud's and iCloud's PROPFIND/REPORT answers in `server/testdata/caldav/`, a fake CalDAV in the test source
   (redirect chain, another host, a refused password, a changed ctag); emulator: §6y against Radicale on the host.
3. Not planned (§7).

## 12. Decisions for the owner (recommendation first)

1. **A link per phone, the fetch shared** (the same URL on two phones = one source) - or one family list every phone
   gets. Per phone: the kids' calendars differ, and Dagen's settings are per phone already.
2. **Fetch every 60 minutes, plus Fetch now** - or 30. A school calendar changes a few times a week.
3. **Horizon: 60 days back, 400 days ahead** (recurring series whole) - or keep the whole past (bytes on both sides).
4. **The link sealed with the server's key** (a lost key = add the links again) - or in clear like an RSS link. A
   school's private feed link is a read password.
5. **Plain http to a public host refused** - or allowed with a warning. Every big provider serves https.
