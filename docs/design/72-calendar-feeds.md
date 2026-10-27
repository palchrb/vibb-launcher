# 72 - Calendars without sync apps: paste a link in the PWA

Status: design note, architect 2026-10-27, on `main` at b6a8b858; revised 2026-10-27 after `docs/review/qa-72-design.md`
(every finding taken: H1-H2, M1-M5, L1-L12, N1-N8; the QA agrees with the five decisions, defaults until the user says
otherwise). Not built. Both directories; step 2 is server only. PLAN: "Calendars without ICSx5/DAVx5". The user,
2026-10-27: "Er det DAVx5 og ICSx5 gjør vanskelig nok til at det rettferdiggjør to ekstra installerte apper?? Hadde
vært mye lettere om det bare var å lime inn lenker i PWA for ical/caldav and that's it." Then: "Og kjør! Ics er pri 1,
dav pri 2." Accepted shape: **the server fetches and parses; Vibb One writes the events into a local calendar through
the provider; a CalDAV password lives on the server, never on the kid's phone; ICSx5/DAVx5 stay possible.** [AOSP] =
read in AOSP's source; [verify] = the emulator answers it (§10).

## 1. What we build on

- Design 69 (Dagen) reads `CalendarContract.Instances` of every calendar with `SYNC_EVENTS = 1`; a calendar's **key** =
  16 hex of SHA-256 over account type NUL account name NUL `Calendars.NAME` (`day/CalendarKey.kt`) carries the
  parent's settings (`device_calendars`) and title rules. `CalendarStore.ensureKidCalendar` (`:423-455`) already makes
  a LOCAL calendar (account `Vibb One`, NAME `vibb-kid-calendar`) through the sync-adapter URI; `WRITE_CALENDAR` is
  self-granted; `CalendarStore` is the provider's only writer and stays so.
- The server fetches pasted links through `music_net.rs`: per-`Kind` caps; `Reach` (a public name never reaches a
  private address; a LAN/tailnet link judged by `music_sweep::lan_addresses` and pinned, same-host redirects); one
  request in flight server-wide (`music_fetch`); `SourceRequest` has no `Debug`. Delivery: a version in the policy and
  a per-phone `ETag` endpoint (`music_api::versioned_json`, made `pub(crate)`, its log line no longer "music"; as
  `dns_filter_version` → `DnsFilterEngine.refreshIfNeeded`, `MdmSyncWorker.kt:161`); nudges via `command_notify`.
  Secrets: `music_secret.rs` (`seal_with`, AES-256-GCM; `AppState.music_key` is an `Option`).

## 2. The model (server)

**A link is per phone; the fetch is shared**: the same URL on a sibling's page attaches to the same source (one fetch,
one stored set). Migration: the next free number at build time (`pwa-i18n-1` holds 0104).

- `calendar_sources` - `id`, `uid` (TEXT UNIQUE, 32 hex = SHA-256 of `vibb-feed:` + the normalized URL; step 2:
  `vibb-caldav:` + account id + collection href), `kind` (`ics`|`caldav`), the URL **sealed** (`url_sealed`,
  `url_nonce`, `url_key_fp`; associated data `calendar-v1:<fingerprint>:<uid>`), `host` in clear (the label, the only
  part logged), `lan` (judged as the sweep does: public stays public for good), `etag`, `last_modified`, `rev`,
  `feed_json` (the served document, §3), `feed_name` (`X-WR-CALNAME`, cleaned), `ok_at`, `tried_at`, `failures`,
  `next_at`, `error` (a code), `empty_count`/`empty_since` (M1), `churn` (L8), counts `events`, `cut`, `unknown_tz`,
  `bad`. **The set is one JSON blob per source**: nothing queries an event.
- `device_calendar_feeds` - `device_id`, `source_id` (FKs, cascade), `name` (1-60 after `calendar::clean`), PK (device,
  source). **An `AFTER DELETE ON device_calendar_feeds` trigger deletes a source no row names any more** (QA H2): the
  Remove route and a deleted phone (`DELETE FROM devices` cascades only these rows) both end there. Caps: 10 links per
  phone, 50 sources per server (a family's; more is a multi-tenant server, out of scope).
- **The normalized URL** (QA L3): scheme and host lowercased, `webcal(s)` → `https`, the default port and the fragment
  dropped, path and query byte for byte (a token is case-sensitive). Its hash as uid means a link removed and added
  again gets the same key, so its `device_calendars` row and rules come back (design 69 greys a key the phone lacks).
- **Retention**: the set is replaced at every accepted fetch, nothing historical kept; a failing or held-empty fetch
  keeps the last good set; a removed source goes at once. A backup zip holds the set as of then and the URL sealed.

### 2.1 The fetch (`server/src/calendar_feeds.rs`)

- **Schedule**: one task; each source every **60 min** (decision 2), most overdue first, 4 s apart, through
  `music_fetch`. Conditional GET; 304 keeps the set; every 200 is parsed (the horizon depends on today). A failure backs
  off by `music_sweep::backoff` (15 min · 4^(n-1), capped at the interval, daily after 7 days) - per source, which at
  ≤ 50 sources is the per-host hold. **Fetch now**: once per source per 5 minutes.
- **Request**: a new `Kind::Calendar`, **5 MB** decoded (new; RSS's `Feed` is 20 MB; past it `too_big`, the set
  kept), 60 s; `Reach` and pinning as an RSS feed's; **an https → http redirect refused** for this kind (QA L2).
  `SourceResponse` gains `content_type` (cut to 200; in its `Debug` and the canned test source, QA L1).
- **Bytes first** (QA H1): **unfold on bytes** (CRLF or LF + SP/HTAB - the same bytes in every ASCII-compatible
  charset; generators fold inside a UTF-8 sequence, RFC 5545 §3.1), **then** decode the whole: BOM, else the
  `Content-Type` charset (`encoding_rs`), else UTF-8 with U+FFFD (as `music_sources.rs:347-361`). It must begin with
  `BEGIN:VCALENDAR`, else `not_calendar`.
- **Errors** (codes, worded at render): `not_found`, `status` (the number kept), `network`, `timeout`, `too_big`,
  `not_calendar`, `private_address`, `key_lost` ("add the link again"), `empty`. No body text or URL path in any log,
  page or error.
- **An empty but valid feed** (QA M1): zero events while the last good set had some is held (`empty`, "Lenken ga ingen
  hendelser - viser forrige"), accepted at the third such fetch in a row or 24 h after the first. The add check takes
  an empty calendar.
- **Change**: a new `rev` → `feed_json` and counts in one transaction (that first checks the source exists), then a
  nudge to its phones. **UID churn** (QA L8): every series new at three accepted fetches in a row → a status line and
  a 6 h interval until it stops.

### 2.2 Parsing and normalizing (`server/src/ics.rs`, our own, no new crate)

After the byte unfold: `NAME;params:value` with quoted parameters, TEXT unescaped (`\n`/`\N`, `\,`, `\;`, `\\`), a
BEGIN/END stack ≤ 4 deep, a line cut at 64 KB, at most 20 000 VEVENTs (then `cut`). Read: VEVENT, VALARM, VTIMEZONE
(`TZID`, `X-LIC-LOCATION`), the calendar's `X-WR-CALNAME`/`X-WR-TIMEZONE`; the rest ignored (`METHOD`, `DTSTAMP`
unused). `ical`/`icalendar` (Apache/MIT) parse but neither caps nor normalizes zones; no `rrule` - **the provider
expands**. Per VEVENT: `UID`, `RECURRENCE-ID`, `SUMMARY` (200 code points), `DESCRIPTION` (2000), `LOCATION` (200),
control characters but `\n` out, `DTSTART` (required, else `bad`), `DTEND` (exclusive; `≤ DTSTART` → one day for a
date, zero length for a time) or `DURATION`, `STATUS`, `SEQUENCE`, `LAST-MODIFIED`, `RRULE`, `RDATE`, `EXDATE`, alarms.

- **UIDs never lose an event** (QA L4): missing → 32 hex over DTSTART + DTEND + SUMMARY + LOCATION; over 255 bytes →
  its hash. Two masters with one UID that both don't recur and differ in DTSTART are both kept (the second uid + `~` +
  8 hex of its DTSTART); else the higher SEQUENCE, the later LAST-MODIFIED, the later in the file wins.
- **Series** = a master plus its overrides. An override without its master, or whose all-day-ness differs from it (QA
  M4), becomes a standalone event; `RANGE=THISANDFUTURE` is read as a single-instance override (as ICSx5).
- **Cancelled**: a single one dropped; a recurring master dropped with its overrides; an override kept as `cancelled`
  (the provider makes no instance, `CalendarInstancesHelper.java:510` [AOSP]).
- **RRULE** passed through if `[A-Z0-9=;,+-]{1,300}`; an `UNTIL` in a known zone → UTC `Z` (timed) or a DATE (all-day)
  [verify]; a floating or unknown-zone `UNTIL` stays local and the phone makes it UTC in the zone it writes (QA L6);
  else one occurrence, `bad`. `RDATE;VALUE=PERIOD` dropped (`bad`).
- **Alarms**: ACTION DISPLAY/AUDIO (EMAIL skipped), a TRIGGER duration before the start (not `RELATED=END`, not
  absolute), 0-10 080 minutes, at most 5, de-duplicated (Dagen's "own" mode reads ≤ 120).
- **Horizon** (decision 3): a single event that ended over **60 days** ago or starts over **400 days** ahead dropped; a
  recurring master kept unless its UNTIL is over 60 days ago (COUNT not expanded). **Caps**: **2000 events** (a master
  and each override count one) and **2 MB** served; past either, the farthest from today go (masters count as today).
- **Revs** (QA N1): a series' `rev` = 16 hex of SHA-256 over its canonical JSON without the `rev`; the feed's over the
  events array.

### 2.3 Time zones

Real feeds: itslearning (design 69 §5/§6.6) UTC `Z`, RRULEs included; Google `TZID=Europe/Oslo` + VTIMEZONE +
`X-LIC-LOCATION`; Outlook/Exchange Windows names (`W. Europe Standard Time`, "Romance Standard Time") + VTIMEZONE;
hand-made feeds now and then floating. The wire carries every time in **its series' zone**; the **phone's** tz database
does the arithmetic (as ICSx5 on the phone does):

1. `...Z` → `tz: "UTC"`; an Olson TZID (chrono-tz knows it) passes through.
2. Else `X-LIC-LOCATION` if Olson; a known prefix stripped (`/mozilla.org/<n>/`, `/freeassociation.sourceforge.net/
   Tzfile/`, `/softwarestudio.org/Tzfile/`); a Windows name through the vendored **CLDR `windowsZones.xml` territory-001
   table** (~140 rows, ~6 KB, `server/src/windows_zones.txt`, the CLDR version in its header; Unicode License v3,
   GPL-compatible, a NOTICE.md line; a test that every target parses).
3. Else **unknown** → floating, counted `unknown_tz` ("3 hendelser med ukjent tidssone - vist i telefonens tid"):
   Outlook's "Customized Time Zone" and display-name TZIDs ("(UTC+01:00) Amsterdam, …") land here; fine.
4. **Floating** → the feed's `X-WR-TIMEZONE` when Olson (QA L6), else `tz: null`: the phone's own zone (§4.2).
5. **All-day** → `YYYYMMDD`, `all_day: true`; the phone writes UTC midnights, `eventTimezone` UTC (design 69 §2.4).
- **Into the series' zone** (QA M4, settled in the contract commit, first): EXDATE, RDATE, RECURRENCE-ID **and an
  override's own DTSTART/DTEND** (Google moves an instance with another TZID or a `Z`) are converted into the master's
  zone (one instant each, no recurrence arithmetic); an override takes its series' `EVENT_TIMEZONE`. **DST**: a TZID
  rule stays 08:15 local across 25 October; the itslearning `Z` rule moves an hour locally - faithful, as ICSx5.

## 3. Delivery to the phone

A school feed is ~200 events × ~400 bytes = 80 KB; the caps allow 10 × 2 MB: too big for the policy, downloaded whole
at every sync. So:

- **The policy**: `calendar.feeds` (in design 69's `calendar`, always sent, `[]` when none) = `[{"id": "<uid>",
  "name": "Skolen", "rev": "3f9c0a12e4b7d856"}]`, `rev` never null (the add check made a set).
  `policy_json_keys_snapshot` pins `calendar` with `feeds: []`, a row's keys in a calendar test;
  `PolicyResponseCompatTest`.
- **The endpoint** `GET /api/devices/calendar/feeds/{uid}` (bearer): 404 unless this phone has the link; `ETag` = rev,
  304, gzip (`versioned_json`). `{"v": 1, "id", "rev", "events": [{"uid", "rev", "summary", "description",
  "location", "start", "end", "tz", "all_day", "rrule", "rdates", "exdates", "status" (confirmed|tentative|cancelled),
  "alarms": [15], "overrides": [{"recurrence_id", "summary", "description", "location", "start", "end", "status",
  "alarms"}]}]}`; every time in RFC 5545 basic form in the series' `tz` (`20261027T081500`, `20261027`).
- **The contract** (one commit, both dirs): `server/testdata/calendar_feeds.json`, byte-identical in
  `launcher/app/src/test/resources/`: `policy`, `feed` (the document for `testdata/calendar_feeds/contract.ics`, an
  override in another zone included), `keys` (uid → calendar key), `provider` (the rows to write for `feed`). Server
  `calendar_feeds::tests::the_contract` produces `feed` and `keys`; launcher `CalendarFeedsTest` maps `feed` to exactly
  `provider` and checks `keys`; both compare the copies. Both workflows' path lists, root `CLAUDE.md`. Capability
  `calendar_feeds_v1`.
- **When**: the server nudges on a new rev and every add/rename/remove; at a sync with a fresh policy the launcher
  fetches only feeds whose `rev` differs from their calendar's `CAL_SYNC1` (the body's `rev` is written, N2) - nothing
  changed, no request; a rename is applied from the policy alone.

## 4. The phone writes (`CalendarStore`, pure `day/FeedPlan.kt` + `day/FeedEvents.kt`)

**Every** Calendars, Events and Reminders insert, update **and delete** goes through the sync-adapter URI with
`ACCOUNT_NAME` + `ACCOUNT_TYPE` (QA L10): a plain delete only marks `DELETED=1`, and `CAL_SYNC*`, `_SYNC_ID`,
`SYNC_DATA*` are sync columns. A plain app may be the sync adapter of its own LOCAL account (the kid's calendar is).

- **One calendar per link**: LOCAL, account `Vibb One` with the kid's `OWNER_ACCOUNT`, NAME `vibb-feed-<uid>` (the
  server computes the key too; `vibbLocal` checks NAME, so "Min kalender" is still made), `CALENDAR_DISPLAY_NAME` = the
  policy's `name`, `CAL_ACCESS_READ` (200: the kid-write rules - ≥ 500, not LOCAL - never pick it), `SYNC_EVENTS` and
  `VISIBLE` 1, a calm colour by the key, `CAL_SYNC1` = the applied rev, `CAL_SYNC2` = `FEED_MAPPING` (bumped by a
  launcher that maps differently: all rewritten), `CAL_SYNC3` = series refused at the last pass. LOCAL survives account
  cleanups [AOSP].
- **A master row**: `_SYNC_ID` = `UID_2445` = uid, `SYNC_DATA1` = the series rev, `SYNC_DATA2` = the zone a floating
  series was written in; title, description, location, `STATUS`; single: `DTSTART` + `DTEND`; recurring: `DTSTART` +
  `DURATION` (`PT<n>S`, all-day `P<n>D`) + `RRULE` (a local `UNTIL` made UTC here), `RDATE`, `EXDATE` (UTC
  `yyyyMMdd'T'HHmmss'Z'`, all-day `yyyyMMdd`, comma-joined [verify]); `EVENT_TIMEZONE` = `tz`, the phone's zone when
  null, `UTC` for all-day; `java.time` converts. **EXDATE's fallback** (QA L7): if §6y shows a form ignored, each
  exclusion becomes a cancelled override row (`ORIGINAL_INSTANCE_TIME` + `STATUS_CANCELED`).
- **An override row**: `_SYNC_ID` = uid `#` recurrence id, `ORIGINAL_SYNC_ID` = uid, `ORIGINAL_ID` = the master's new
  `_ID` (a back reference in the batch), `ORIGINAL_INSTANCE_TIME` (UTC ms), `ORIGINAL_ALL_DAY`, the series'
  `EVENT_TIMEZONE`; a cancelled one `STATUS_CANCELED` [verify]. **Reminders**: one row per alarm minute,
  `METHOD_ALERT`, read by Dagen's "own" mode; nothing rings (Etar goes, design 69 question 2).
- **The plan** (`FeedPlanTest`): from the wire (uid, rev) and the rows (`_SYNC_ID`, `ORIGINAL_SYNC_ID`, `SYNC_DATA1/2`),
  the zone now and `FEED_MAPPING`: a gone uid → its rows deleted (overrides by `ORIGINAL_SYNC_ID`, explicitly); a
  different rev, a floating series written in another zone, or a mapping bump → the series deleted and inserted; a row
  of no series (half-made) → deleted; else nothing.
- **Applying** (QA L8): whole series packed into `applyBatch` calls of ≤ ~200 operations (never split, so atomic per
  series), on the store's worker, deletes first; change notifications are debounced already. §6y times 2000 events.
- **Gone vs refused** (QA M2): the provider **gone** (`IllegalStateException`, a null client, before the first unlock, a
  process death) ends the pass with `CAL_SYNC1` old - the next sync plans again and what was written matches:
  **idempotent and resumable**. A batch **refused** (`IllegalArgumentException`, `SQLiteException`) is retried series by
  series; one still refused is logged by its index (never its text) and counted in `CAL_SYNC3`. A pass whose only
  misses were refusals writes `CAL_SYNC1`, so a bad series doesn't make every sync GET the document again.
- **Feeds gone**: a `vibb-feed-*` calendar under `Vibb One` not in a fresh policy's `calendar.feeds` is deleted (its
  events with it [AOSP]) - only when `feeds` is an array **every element of which reads** (QA L9; `calendarPolicy`
  drops bad rows silently): missing (an older server), a bad element, a cached policy or `UNKNOWN` delete nothing. The
  key's settings and rules stay on the server and return with the same link.
- **Floating times**: the next sync after the phone's zone moved rewrites them (`SYNC_DATA2`; no new receiver).
- Hook: `MdmSyncWorker` after `CalendarStore.policyChanged()`: `CalendarFeeds.refreshIfNeeded(context, api,
  freshPolicy.calendar)`, `MdmApi.calendarFeed(id, ifNoneMatch)`; a failed GET changes nothing.

## 5. The PWA: Device › Calendar › "Kalenderlenker" / "Calendar links"

A group `#links` above "Calendars shown". Every word through `i18n` (`calendar.links.*`, nb + en).

- **Add**: the link and an **optional** name (QA M3: empty → `X-WR-CALNAME`, cleaned, 60; else the host; renamed on
  the row). The link normalized (§2), ≤ 2000 characters, no userinfo (step 2 has logins); **https**; **plain http
  judged by what the name resolves to** (`lan_addresses`, QA L2 - `http://nas.home.arpa/` and split-DNS names are LAN):
  Tailscale only → fine, LAN only → saved with the server-address rule's warning, else **refused** (decision 5). No
  music key → nothing can be sealed: the form says why and has no submit (QA L11, as Storytel's). Then the **add
  check**: one fetch (§2.1, ≤ 60 s) that must give a VCALENDAR (empty is fine); a refusal re-renders the form as typed
  (400), never the body. A URL already a source → attached, no fetch. A save is a 303 to `#link-<uid>` (no jump).
- **Each link**: the name (auto-saving), the host only (sealed, never shown again), a status line ("Hentet 14:05 · 182
  hendelser"; "Venter på telefonen" until the report lists the key; "oppdater Vibb One" without `calendar_feeds_v1`;
  the error in words with the set's age; `empty`, churn, `unknown_tz`, `cut`; step 2's `login_refused`), Fetch now,
  Remove (confirmed: "The calendar goes off this phone; ICSx5's calendars are not touched").
- **"Calendars shown"** lists a link's calendar **at once** by its computed key ("Skolen · Kalenderlenke"), its
  controls working before the phone has it (`device_calendars` rows by key), "Venter på telefonen" until reported;
  then a row like any other, labelled "Kalenderlenke", never offered as the kid's calendar. A reported ICSx5 calendar
  with a link's host: "Kan være den samme som kalenderlenken Skolen" (Dagen shows an identical copy once,
  `DayModel.kt:144`). The empty page's setup steps lead with the link; ICSx5/DAVx5 become "Or, with a sync app".
- **The parent's copy** (QA L12): the "i" texts, `calendar.preview.footer` and `calendar.rules.footer` (both say the
  phone's events never reach the server) now say a link's events are fetched by the server and the phone's own stay
  on the phone; the links' "i" says a day over 60 days back shows nothing from a link. `calendar.md:4,75` likewise.
- Routes behind `require_full_auth` (`every_calendar_route_needs_a_session` grows); Fetch now and Test in
  `settings_log::NOT_SAVES`; add/rename/remove are saves (route and ids only - never the URL).

## 6. Step 2: CalDAV, read-only

- **The account** (`caldav_accounts`: server URL, `username`, the app password sealed with `caldav-v1:<fingerprint>:
  <account id>`, `hosts` the password may go to, `principal`, `home_set`, state). Each ticked calendar is a
  `calendar_sources` row of kind `caldav` (collection href, `getctag`/`sync-token`), subscribed per phone as in §2; an
  account with no source left goes the same way.
- **Discovery** one hop at a time (`follow: false`, each hop judged by §5's rules, LAN pinned): `/.well-known/caldav`,
  `PROPFIND` `current-user-principal`, `calendar-home-set`, then Depth 1 for `resourcetype`, `displayname`,
  `supported-calendar-component-set` (VEVENT); XML by `quick-xml`. **The password goes only to the hosts confirmed at
  Test** ("Passordet sendes til: caldav.icloud.com, p45-caldav.icloud.com" - iCloud's principal is on another host);
  another host later → `host_changed` ("Test again") before any request.
- **Fetch**: `PROPFIND` Depth 0 `getctag` + `sync-token`; unchanged → done; changed → one `REPORT calendar-query` with
  a `time-range` (−60 d .. +400 d) for `calendar-data`, capped at `Kind::Calendar`'s 5 MB (`too_big` keeps the set);
  each resource through `ics.rs`; the same set, caps, rev and delivery. No `sync-collection`: bookkeeping for no gain.
- **A refused password stops** (QA M5): a 401/403 in a routine fetch marks the account `login_refused` - no request
  until Test passes or a new password is saved; every phone's link status says so (hourly retries can lock an Apple
  ID). Test at most once per 10 s per account, no retry after a 401.
- **Scope**: iCloud, Nextcloud, Fastmail (app passwords), Radicale and other basic-auth servers. **Out**: Google (OAuth
  only; its secret ICS address works in step 1), digest auth, client certificates. **The form**: the server (or an
  e-mail, its domain tried), username, app password; Test lists the calendars to tick; the password never rendered back.
- **Threats**: SSRF - §5's rules on every hop, no body echoed; credentials never in a log (no `Debug`, codes only, no
  form values in the settings log), the policy, a report or a page; a lost key = "log in again". The launcher unchanged.

## 7. Step 3: the kid's own events to CalDAV (not planned)

If wanted: the parent picks a ticked CalDAV calendar as the kid's; the phone posts its own events (`vibb-<tag>-` UIDs)
to `POST /api/devices/calendar/kid-events`; the server `PUT`s each `<uid>.ics` (`If-Match`/`If-None-Match: *`) or
`DELETE`s it, and the next fetch reads them back. The first kid's event to leave the phone - the user's call.

## 8. What ICSx5/DAVx5 keep

Everything (`CALENDAR_SYNC_APPS`, `on_home`, never suspended, `requestSyncIfDue`); §6w step 5 stays, the other path.

## 9. Privacy

The server now holds, while a link exists, the linked calendars' event texts (title, description, place, times,
alarms) and the link sealed; each fetch replaces the set; removing the link or the phone deletes it all at once. It
never holds the kid's own events (unless step 3) and sends an event only to the phones with the link. A CalDAV password
is sealed and never leaves the server. Design 69's "the server never sees an event" becomes "no event ever leaves the
phone" (§5's copy). The user weighed it (PLAN, 2026-10-27): parsing on the server shares more with it, but the server
already holds the rest of the family's setup, and the password stays off the kid's phone.

## 10. Testing (lean)

- **Server** (`ics::tests`, `calendar_feeds::tests`; `server/testdata/calendar_feeds/`): `itslearning.ics` (folds, "R
  omerriket", "5. trinn", long descriptions, a UTC weekly RRULE across 2026-10-25, **"skoleåret" folded between the
  bytes of "å"**), `outlook.ics` (Windows TZID + VTIMEZONE, an unknown TZID), `google.ics` (`X-LIC-LOCATION`,
  `/mozilla.org/`, an override moved with a `Z`), `series.ics` (all-day + RRULE + EXDATE, moved and cancelled
  instances, an override in another zone, a local UNTIL, floating with `X-WR-TIMEZONE`, duplicate UIDs single and
  recurring, a missing and a 300-byte UID, an orphan override, THISANDFUTURE, PERIOD, an EMAIL alarm), `cancelled.ics`,
  `contract.ics`; a Latin-1 body by `Content-Type`; generated: 5 MB + 1 (`too_big`, set kept), 2001 events (`cut`),
  garbage, a 70 KB line. Fetch: 304; empty held twice, accepted the third time and after 24 h; a new rev nudges exactly
  the subscribers; failure keeps the set and backs off; LAN pinned, a private answer to a public name refused, https →
  http refused; removed mid-fetch writes nothing; Remove **and a deleted phone** delete the source; churn → 6 h; the
  zones table. Pages: URL classes by resolution, the default name, no music key, a refused add as typed, 303 anchors,
  the computed key "Venter på telefonen", Fetch now's gap, no URL in the settings log, the session; the snapshot; the
  endpoint's 404/304/gzip. Step 2: a fake CalDAV in the test source (a redirect chain, another host, 401 →
  `login_refused` with no further request, Test's gap, a changed ctag) and Nextcloud/iCloud answers in `testdata/caldav/`.
- **Launcher**: `CalendarFeedsTest`; `FeedPlanTest` (no change → no op; new, changed, gone, half-made; a zone change
  rewrites only floating; a mapping bump all; `feeds` missing or with a bad element deletes nothing, `[]` all; refused
  → rev written, `CAL_SYNC3`; gone → rev left; batches ≤ ~200 never split a series); `FeedEventsTest` (DURATION,
  EXDATE and its fallback, all-day UTC midnights, floating with its local UNTIL, `ORIGINAL_INSTANCE_TIME`, every URI
  with the sync-adapter parameters); `PolicyResponseCompatTest`.
- **Emulator §6y** (`jelly36`; the feed from the host as §6w step 5, `http://10.0.2.2:8088/school.ics`, the LAN
  warning): "Venter på telefonen" → the calendar appears; Dagen shows the series with its EXDATE, the moved and
  cancelled instance, the all-day camp, the VALARM in "own" mode, "skoleåret" whole; the UTC RRULE on 2026-10-25 and a
  TZID one on 2027-03-28; a title rule; the feed edited → Fetch now → the next sync has it, a second sync no request;
  the feed emptied → the day kept; 2000 events' first apply timed; killed mid-apply → completed next sync; removed →
  gone, settings greyed; added again → back; the [verify] items (UNTIL, EXDATE with the fallback, override linking).
  Step 2: against Radicale on the host.

## 11. Steps and sizes

1. **ICS end to end** (one branch, both dirs, the contract commit first, with M4): server **M** (migration + trigger,
   `ics.rs`, the zones table, `calendar_feeds.rs`, `Kind::Calendar` + `content_type`, the endpoint, the policy key,
   `#links`, the "Calendars shown" merge, the copy, i18n) + launcher **S-M** (`calendar.feeds`, `MdmApi.calendarFeed`,
   `FeedPlan`, `FeedEvents`, the writes, the hook, `calendar_feeds_v1`). Docs: `calendar.md`, `launcher/CLAUDE.md`,
   root `CLAUDE.md`, NOTICE.md.
2. **CalDAV read-only**: server **M** (`caldav.rs`, `caldav_accounts`, the form and Test, discovery and fetch).
3. Not planned (§7).

## 12. Decisions for the owner (recommendation first; the QA agrees with each)

1. **A link per phone, the fetch shared** - or one family list every phone gets. The kids' calendars differ, and
   Dagen's settings are per phone already.
2. **Fetch every 60 minutes, plus Fetch now** - or 30. A school calendar changes a few times a week.
3. **Horizon: 60 days back, 400 days ahead** (recurring series whole) - or the whole past (bytes on both sides).
4. **The link sealed with the server's key** (a lost key = add the links again) - or in clear like an RSS link. A
   school's private feed link is a read password.
5. **Plain http to a public host refused** (judged by resolution, no https → http redirect) - or allowed with a warning.

## 13. What the implementer's brief must carry

- H1 (bytes unfolded, then decoded; the "å"-split row), H2 (the trigger, the deleted-phone test), M1's empty hold, M2's
  refused-vs-gone split, M3's optional name, M4's override zone (in the contract commit, first); step 2: M5.
- The contract `calendar_feeds.json` in both dirs in one commit with both workflows' path lists and root `CLAUDE.md`;
  `policy_json_keys_snapshot` + `PolicyResponseCompatTest`; capability `calendar_feeds_v1`; migration = next free.
- No URL or body text in any log, page, error or settings-log row (host and codes); `SourceRequest` stays without
  `Debug`; L10's sync-adapter rule for every write and delete.
- i18n nb + en for every word (`calendar.links.*`, the codes, the statuses); a save is a 303 to `#link-<uid>`; routes
  behind `require_full_auth`.
- The Lows as written; capture the real itslearning fold's bytes before writing `itslearning.ics` (QA N7: the e4-e5
  run saw ICSx5's output); §6y answers every [verify] and times L8's first apply. PLAN's queue updated at the merge.
