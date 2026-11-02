# 72b - CalDAV write-back: the kid's own events go to the calendar the parent marks

Status: design note, architect 2026-11-02, on `main` at dac8a52a; design QA next. Design 72 step 3 (§7 sketched it,
"not planned"); PLAN: "CalDAV write-back - the kid's own Dagen events to the parent-marked CalDAV calendar". Both
directories, one shared contract file. The user, 2026-11-01: "man skal kunne legge inn en caldav kalender og velge at
det er kalenderen barnet selv skal kunne legge inn dagen i dag hendelser i". Accepted shape: **the parent marks one
CalDAV calendar (of an account already added for reading) as the kid's own; the events the kid makes in Dagen - today
in the phone's LOCAL "Min kalender" - are written there by the server (a `PUT` of a VEVENT with the app password the
server keeps); the password never goes to the phone.** [verify] = a real server or the emulator answers it (§10).

Each decision: **the recommendation**, then the alternatives in one line.

## 0. What we build on

- **The kid's events today** (design 69 step 3, `docs/notes/server/calendar.md` "The kid's own events",
  `day/KidEvent.kt`): "Ny avtale" - what, the day, from, a length, a place; no recurrence, no alarm. Written by
  `CalendarStore.insertKidEventBlocking` as a plain app into `kidCalendar()` - the parent's writable pick (a DAVx5
  calendar, `device_calendars.writable_for_kid`), else "Min kalender" (LOCAL, account `Vibb One`, NAME
  `vibb-kid-calendar`, key `calendar::KID_LOCAL_KEY`). `UID_2445` = `vibb-<phone tag>-<uuid>`, `CUSTOM_APP_PACKAGE` =
  ours, `EVENT_TIMEZONE` the phone's; `kidMayEdit` lets the kid change or delete only this phone's own in the kid's
  calendar. The PWA's one choice is Dagen's select "Barnets avtaler" (`kid` = `off` | `phone` | a reported key, back to
  `#dagen`): one kid's calendar per phone by construction.
- **CalDAV read** (design 72 §6, `src/caldav.rs`): `caldav_accounts` (the family's; the password sealed
  `caldav-v1:`; `hosts` = the only hosts it may go to; `state` ok / `login_refused` / `host_changed`), each ticked
  calendar a `calendar_sources` row of kind `caldav` subscribed per phone in `device_calendar_feeds` (PK device, source;
  0110's comment reserves "the kid's own" flag for this row). `Dav::ask` - one hop at a time, each judged by
  `calendar_feeds::route`, a LAN account pinned to its host and port, `host_changed` before a request to another host,
  401/403 = `login_refused`, all through `state.music_fetch` (one request in flight server-wide). The fetch: PROPFIND
  ctag, then one `REPORT calendar-query` (−60 d .. +401 d) that already asks `getetag` but keeps only the bodies.
- **The phone reads it back**: a ticked calendar is a `vibb-feed-<uid>` LOCAL read-only calendar (`FeedPlan`, design
  72 §4); a sibling who ticks the same calendar gets the same set.

## 1. The shape

```
kid saves in Dagen ──► Min kalender (unchanged; offline fine)
        │ sync (soon after the save)
        ▼
POST /api/devices/calendar/kid-events  {calendar, rev, events[]}   ◄── the whole set, not operations
        │ answer: {accepted, back[]}  ◄── the parent's edits/deletes to apply
        ▼
server: diff against kid_caldav_events ─► PUT/DELETE <collection>/<uid>.ics (If-None-Match / If-Match)
        │                                   └─ 412 = the parent changed it: the calendar wins (§3)
        ▼
refetch the calendar ─► feed rev ─► every phone with it ticked; this phone skips its own (§4)
```

## 2. The phone hands the events over

**D1.1 - A snapshot, not a queue of operations.** The phone sends the **whole set** of the kid's own events, and the
server diffs it against what it wrote. A delete is an event missing from the set; a lost request, a killed process, a
crash or a week offline need no outbox - the next snapshot says everything (self-healing, idempotent). The set is
small: the kid's events within the horizon, ≤ 300 rows of ~300 bytes. *Alt: an operations log (create/edit/delete)
with acks - a queue to persist, replay and order, and a lost delete is lost for good; the provider's `DIRTY` flag as
the outbox - it says nothing of deletes (an unsynced row is deleted outright).*

**D1.2 - Its own endpoint, not the status report**: `POST /api/devices/calendar/kid-events` (bearer), sent only when
the set differs from what the server last accepted (the policy's `kid_caldav.accepted`, D1.6) or the server has
something to hand back (`back > 0`). The status report goes every sync and stays small; 60 KB of the kid's texts in
every report is waste, and the report is "never an event" (design 69). *Alt: a `calendar.kid_events` block in the
report - every sync pays, and the report's privacy rule breaks.*

**D1.3 - Which events**: in "Min kalender" (`vibbLocal`) only, with this phone's UID (`vibb-<tag>-`), not deleted, no
`RRULE`/`RDATE` (the form makes none; a row that has one is left out), ending at or after now − 60 days (design 72's
horizon); at most **300**, the farthest from today dropped first. Fields: `uid`, `summary` (≤ 100 - the form's
`KID_EVENT_TITLE_MAX`), `location` (≤ 100), `description` (≤ 2000; the kid can't type one, a parent's edit may add
it, D2.3), `start`/`end` (UTC, RFC 5545 basic form `20261103T140000Z`; all-day `20261103` with `all_day: true`),
`tz` (the zone the kid entered it in; informational). The set's `rev` = 16 hex of SHA-256 over its canonical JSON
(sorted by uid); the server stores it verbatim. A snapshot is sent only from a **full read** (the provider readable,
"Min kalender" found): an unreadable provider, a missing calendar, an `UNKNOWN` policy send nothing - never an empty
set by accident (an honest empty set, the kid having deleted all, is sent; D2.6 holds a mass delete). *Alt: also the
events the kid made in a DAVx5 pick - DAVx5 writes those already.*

**D1.4 - The UID is the identity** end to end: the resource is `<collection>/<uid>.ics` (the UID is URL-safe:
`vibb-` + 8 hex + `-` + a UUID), the VEVENT's `UID` the same. A second PUT of the same event is an update, never a
copy; a re-marked calendar finds its old resources (D4.4); DAVx5 would round-trip the same UID (design 69 QA L1).

**D1.5 - When**: (a) at every sync while `kid_caldav` is set and `accepted ≠` the set's rev or `back > 0`, before
`CalendarFeeds.refreshIfNeeded` (so a delete reaches the server before the feed is applied); (b) right after the kid's
save, change or delete, `CommandListenerService.requestSync(context, "kid_event")` while `kid_caldav` is set - the
parent sees it within the sync's time, not the backstop's. Offline: nothing queued; the set goes at the next sync with
a network (D1.1). A failed POST (network, 5xx, 429) changes nothing on the phone. *Alt: a POST from the form itself -
a second network path beside the sync, and the form must not wait on the server.*

**D1.6 - The policy's `calendar.kid_caldav`**: `null` (always sent; nothing marked, or a launcher without the
capability) or `{"id": "<source uid>", "accepted": "<rev>" | null, "back": 0, "pending": 0}` - the marked calendar's
feed uid (the phone knows its `vibb-feed-<uid>` calendar by it), the last set the server took (`null` after a mark, a
restore or a re-mark: send it all), how many hand-backs wait (D2.3), how many writes wait (the phone's skip set, D3.2).
`policy_json_keys_snapshot` pins `kid_caldav: null`, the row's keys in the contract (§8); `PolicyResponseCompatTest`.
Capability `calendar_kid_caldav_v1`: without it the server sends `null` and the PWA doesn't offer the choice (D5.3).

**D1.7 - The answers** (pinned in the contract): **200** `{"accepted": "<rev>", "back": [...]}` (D2.3; the writes run
after the answer, D4.2); **409** `{"error": "not_marked"}` (no calendar marked, or `calendar` isn't the marked one - a
stale policy: the phone does nothing until the next policy); **429** with `Retry-After` (at most one POST per 10 s per
phone); **413** (over 300 rows or 256 KB); **400** (a body that doesn't read: `device_api::known_fields` per row,
`intake_limits` keeps the contract's keys). The phone reads anything but 200 as "try at the next sync".

## 3. Conflicts and the way back

**D2.1 - The calendar wins.** An event the parent changed or deleted in the CalDAV calendar is changed or deleted on
the kid's phone too; if the kid changed the same event meanwhile, the kid's change is dropped. The parent is the one
who manages the family's calendar (the house rule: the server and the parent own the rules); a kid's event the parent
moved must not silently move back. *Alt: the phone wins (the parent's edit overwritten at the kid's next change of
that event, a parent's delete re-created) - simpler, but the calendar "fights back"; one-way with no hand-back at all
(the open question 1).*

**D2.2 - Detection by ETag, on the server only.** Per kid event the server keeps the resource's `href` and the `etag`
it last wrote or read. A parent's edit is seen two ways: (a) a PUT/DELETE with `If-Match: <etag>` answers **412**; (b)
the marked calendar's fetch (D4.6) shows the uid's resource with another `getetag`, or without it. The phone never
compares versions (it can't tell a parent's edit from its own write not yet fetched).

**D2.3 - The hand-back**: the 200's `back` = `[{"id": "<16 hex>", "uid", "op": "upsert", "summary", "location",
"description", "start", "end", "all_day"} | {"id", "uid", "op": "delete"}]`, the parent's version reduced to the
fields the phone keeps (summary and location cut to 100, description to 2000). The phone applies each through
`CalendarStore` with the kid-write guards: an upsert updates the row with that UID in "Min kalender", or inserts it
there with that same UID and our `CUSTOM_APP_PACKAGE` (the kid deleted it while the parent edited: the calendar wins);
a delete removes it; a UID without this phone's tag is never touched. All-day lands as design 69 §2.4 (UTC midnights,
`EVENT_TIMEZONE` UTC). The phone needs no acknowledgement field: its next snapshot shows the event as the calendar has
it, and the server clears the hand-back (D2.5). A hand-back still unapplied after 7 days is dropped, the phone's
version then taken as sent (no write) so nothing loops.

**D2.4 - What can't come back is released**: a parent's edit that makes the event recurring (`RRULE`/`RDATE`, an
override) is handed back as a `delete` and the server forgets the uid: the event then shows on the phone from the
feed as one of the parent's (§4 skips only what is in "Min kalender"). Properties the phone doesn't keep (an alarm,
attendees, a colour the parent added) are lost at the kid's next change of that event - the server writes the whole
resource. *Alt: merge into the stored remote VEVENT, replacing only the known properties - keeps them, +S, and the
server must store each resource's text.*

**D2.5 - No echo loops**, by three comparisons on canonical fields (summary, location, description, start and end as
instants, all-day; never the zone or the bytes - iCloud rewrites both):
1. The server writes a uid only when the phone's version differs from the last one the phone sent (**the kid changed
   it**) **and** from what the calendar holds (**not already there**): a hand-back applied on the phone comes back in
   the next snapshot equal to the calendar's version and writes nothing.
2. Its own write's ETag is stored (from the PUT's answer, else a PROPFIND Depth 0 `getetag` on the href - RFC 4791
   §5.3.4: a server that altered the body returns none), so its own write is never read as a parent's edit.
3. The phone's feed apply skips its own events (§4), so a fetched kid event never lands in the phone's provider twice
   and never changes "Min kalender" - the next snapshot is unchanged and nothing is posted.

**D2.6 - Safety rails.** **The horizon**: a uid missing from the set whose event ended more than 59 days ago is
forgotten (its row dropped), never deleted on the calendar - aging out isn't deleting. **A mass delete is held**: a
set that would delete 5 or more events and more than half of those written (a "Min kalender" wiped by Calendar
Storage's data clear, a phone restored) is held (`held_delete`, "Telefonen sendte 14 færre avtaler - venter"),
accepted at the third such set in a row or 24 h after the first (design 72's empty hold, QA M1); the writes and edits
in it go through at once. **A missing resource is confirmed** by its own href (`PROPFIND` Depth 0) before it counts
as a parent's delete: a parent who moved it past the REPORT's horizon has not deleted it (then it is parsed from a
`GET` and handed back as an upsert).

## 4. Read back without duplicates

**D3.1 - The marked calendar is a ticked calendar**: the select offers only CalDAV calendars ticked for this phone
(D5.1), so the parent's own events in it reach the kid as today, and the kid's events reach a sibling who ticks the
same calendar - read-only, in that calendar's colour, as with a DAVx5 family calendar (design 69 QA L1). The feed set
stays one per source (design 72 §2: the fetch shared); the server filters nothing per phone.

**D3.2 - The phone skips its own**: before `feedPlan`, `CalendarFeeds` drops from every feed document the series whose
UID is in the **skip set** = the UIDs of the kid's events in "Min kalender" ∪ the UIDs of the last set posted while
the policy's `pending > 0` (an event the kid just deleted isn't shown back from a feed fetched before the server's
DELETE). Rows of a skipped series already in a feed calendar are deleted by the existing plan. The skipped UIDs of
each feed calendar are kept in its `CAL_SYNC6` (a JSON array, ≤ 300): a UID that leaves the skip set (released, D2.4;
deleted by a hand-back) makes `feedFetch` GET that document again; a UID that joins it is deleted from the calendar's
rows locally, no GET. The filter always runs, marked or not: an event in "Min kalender" is never shown twice, whatever
was written where (Dagen's "two copies one row" stays the backstop). Events this phone once wrote through DAVx5 (own
tag, not in "Min kalender") show from the feed, read-only. *Alt: skip by phone tag alone - those DAVx5-era events
would vanish; the server leaving the phone's own events out of its copy - a per-phone document, the shared fetch
and its ETag lost.*

## 5. Server

**D4.1 - Model** (migration **`0117_calendar_kid_writes.sql`**: the next free after 0116, passkeys step 1 in build;
passkeys step 2 also wants one - renumber at merge if needed):
- `device_calendar_feeds.kid_writes INTEGER NOT NULL DEFAULT 0` + `CREATE UNIQUE INDEX idx_device_calendar_feeds_kid ON
  device_calendar_feeds(device_id) WHERE kid_writes = 1` (one per phone); only a `caldav` source, checked in code
  and by a test. Setting it clears `device_calendars.writable_for_kid` and sets `calendar_kid_adds = 1` in the same
  transaction; picking `phone`, `off` or a reported key clears it.
- `device_kid_events` - `device_id` PK, `source_id`, FK `(device_id, source_id)` → `device_calendar_feeds` ON DELETE
  CASCADE (an unticked calendar, a removed account, a deleted phone take it all), `rev`, `events_json` (the last set,
  replaced at each POST), `received_at`, `written_at`, `error` (a code), `error_at`, `held_since`/`held_count` (D2.6),
  `posted_at` (the 10 s gap).
- `kid_caldav_events` - `device_id`, `uid` (PK both), `source_id`, the same FK, `href`, `etag`, `sent_hash` (the phone's
  version last sent), `remote_hash` (the calendar's as last written or read), `sequence`, `end_at`, `state`
  (`pending`/`ok`/`refused`), `failures`, `next_at`, `back_id`, `back_json` (the hand-back while it waits). Hashes =
  16 hex over D2.5's canonical fields. Unmarking deletes both tables' rows for the phone in the transaction that
  clears the flag.

**D4.2 - The write job** (`src/calendar_kid_events.rs`; the requests in `caldav.rs`). The POST stores the set and
answers at once; a task then works that phone's diff, also each scheduler pass for what waits: **one write at a time**
through `music_fetch` (as every fetch), at most **60 a minute per account**, the rest the next pass; a failing event
backs off by `music_sweep::backoff`, alone. No request while the account isn't `ok` or its password can't be opened
(the writes wait as `pending`; the account's stop is design 72 QA M5's, unchanged). After a job that wrote anything,
**that calendar is fetched at once** (the ctag changed), and its scheduled fetch first runs its pending writes - the
feed served never lags the server's own writes. Then a nudge (`command_notify`) when `back` or `pending` changed.

**D4.3 - The requests.** `Dav::ask` grows a request shape (method, an optional Depth, the content type, an
`If-Match`/`If-None-Match` header) and a per-method reading of 401/403 (D4.4); its hop, host, LAN and pinning rules
stay exactly as they are. **Create**: `PUT <collection>/<uid>.ics`, `If-None-Match: *`, `Content-Type: text/calendar;
charset=utf-8`. **Update**: `PUT <href>`, `If-Match: <etag>`. **Delete**: `DELETE <href>`, `If-Match: <etag>`. Never a
write without a condition. The body, by a small writer `ics::write_event` (CRLF, TEXT escaping of `\ ; , \n`, folding
at 75 octets never inside a UTF-8 sequence; round-tripped through `ics::parse` in its tests):
`VCALENDAR` (`VERSION:2.0`, `PRODID:-//Vibb//Vibb One//EN`) › `VEVENT` with `UID`, `DTSTAMP`, `CREATED` (first PUT),
`LAST-MODIFIED`, `SEQUENCE` (+1 per kid change), `SUMMARY`, `LOCATION`/`DESCRIPTION` when not empty, `DTSTART`/`DTEND`
in **UTC** (`...Z`; all-day `VALUE=DATE`) - single events need no `VTIMEZONE`, and every client shows the instant in
its own zone [verify iCloud, Fastmail, Nextcloud, Radicale]. No `VALARM`, no `ORGANIZER`.

**D4.4 - Reading the answers**: 201/204 → `ok`, the ETag stored (D2.5.2). **412** → the parent's edit: `GET` the href,
parse, hand back (D2.3) - or, on a create, the resource exists (a re-mark): `PROPFIND` its `getetag` and treat it as
written by us with an unknown version, the phone's version then `PUT` with that `If-Match` (the UID carries this
phone's tag: it is ours). **403/409 with `no-uid-conflict`** (RFC 4791 §5.3.2.1: the UID lives at another href) → the
`<D:href>` in the body adopted if it is on a confirmed host and under the collection's path, else `refused`. **404**
on update or delete → the parent deleted it (update: handed back as a delete; delete: done). **401** → the account's
`login_refused`, as today. **403** otherwise → **`write_refused`** for this calendar alone (a read-only share, a
birthday calendar, a read-only app password): every write of the phone stops until the parent picks again or Test
passes - never the account's `login_refused` (today's `ask` reads 403 so; writes must not). **507** → `full`, the
calendar stopped. **400/415** → that event `refused` (logged by index and code, never its text; counted). **5xx,
network, timeout** → backoff, that event.

**D4.5 - Where the password goes**: unchanged. Only to the account's confirmed `hosts`; a LAN account only to its own
host and port, pinned; never https → http; an adopted href or a redirect to another host is `host_changed` before
the request. No new host is ever learned by a write.

**D4.6 - The marked calendar's fetch keeps more**: for a source some phone writes to, the REPORT's resources are also
read one by one for the uids in `kid_caldav_events` (`href`, `getetag`, that resource alone through `ics::parse`):
an ETag that differs from the stored one is a parent's edit (D2.2b; hand back, `remote_hash` updated); a uid missing
is confirmed (D2.6) then handed back as a delete. An unchanged ctag (`NotModified`) skips this, as the fetch does.
The feed set is built exactly as today.

**D4.7 - Only writable calendars are offered**: Test's Depth 1 PROPFIND (`PROPFIND_LIST`) also asks
`current-user-privilege-set`; `calendars_json` rows gain `writable` - `true` with `write-content` or `write` (or
`all`), `false` without, `null` when the server didn't say (offered; the first PUT's 403 answers it). The account's
next Test fills it; until then `null`.

**D4.8 - Retention**: the server holds, while the mark exists, the kid's latest set (titles, places, descriptions,
times; replaced at every POST) and per event its href, ETag, hashes and a waiting hand-back. Unmarking, unticking,
removing the account or deleting the phone deletes it all at once (FK cascade or the same transaction). **What was
written stays in the calendar** (the open question 3): it is the parent's calendar now. A backup zip holds the tables
as of then, as it holds a feed's set. No event text in any log, page, error or settings-log row; the snapshot and row
types have no `Debug`.

## 6. The PWA

**D5.1 - The choice lives in Dagen's "Barnets avtaler" select** (`#dagen`), where the kid's calendar is chosen today
- one per phone by construction, no second control to keep in step. New options after the reported calendars, one per
CalDAV calendar ticked for this phone whose `writable` isn't `false`: value `caldav:<source uid>`, labelled "{name} ·
{server} (skrives av serveren)" / "{name} · {server} (written by the server)" (`calendar.kid.caldav_option`; the
server as the account row says it, "iCloud", else the host). The account's page (`#calendars`) shows on the marked
calendar's row "Barnets avtaler skrives hit" / "The kid's events are written here" with a link to `#dagen`
(`calendar.kid_caldav.here`). *Alt: a "Bruk for barnets avtaler" switch per calendar on the account's page - two places
choose the kid's calendar, and the exclusivity must be explained (the open question 5).*

**D5.2 - The status line** under the select while a CalDAV calendar is the pick (`calendar.kid_caldav.status.*`, one
line, counts only - never a title): "Venter på telefonen" / "Waiting for the phone" (no set yet); "Skrevet 15:12 · 12
avtaler" / "Written 15:12 · 12 events"; "3 venter på å bli skrevet" / "3 waiting to be written"; orange: "Kalenderen tar
ikke imot avtaler - velg en annen" / "The calendar doesn't take new events - pick another" (`write_refused`), "Kalenderen
er full" / "The calendar is full", the account's stop in its own words with a link to the account's page ("Passordet
ble avvist - test på nytt"), "Telefonen sendte 14 færre avtaler - venter til i morgen" / "The phone sent 14 fewer
events - waiting until tomorrow" (D2.6), "2 avtaler ble ikke tatt imot" / "2 events weren't accepted" (`refused`).
Not an alarm on the Devices list: the kid's day isn't hurt by a write that waits.

**D5.3 - Older launcher**: without `calendar_kid_caldav_v1` the CalDAV options are shown disabled with "Oppdater Vibb
One for å skrive barnets avtaler hit" / "Update Vibb One to write the kid's events here" (`calendar.kid_caldav.older`);
a pick saved while the capability was there stays, and the policy sends `null` until it returns.

**D5.4 - The words that change** (QA L12 of design 72 again): the ⓘ of Dagen gains "Velger du en CalDAV-kalender,
sender telefonen barnets avtaler til serveren, og serveren skriver dem inn i kalenderen hos {server}. Alle som ser den
kalenderen, ser dem - også søsken som har den på sin telefon. Endrer eller sletter du en av dem i kalenderen, gjør
telefonen det samme. Velger du noe annet, blir avtalene liggende både på telefonen og i kalenderen." / en likewise
(`calendar.kid_caldav.info`); `calendar.preview.footer`, `calendar.rules.footer` and `calendar.sources.*`'s "the
phone's own events stay on the phone" gain "- unless the kid's events are written to a CalDAV calendar". Every word nb
+ en through `i18n`.

**D5.5 - Saves**: the select autosaves as today, a 303 to `#dagen` (the page never jumps to the top); a refused pick
(not ticked, `writable: false`, not a `caldav` source, gone) is the existing 400 `calendar.error.kid_calendar`. The
save is in the settings log (route and ids only). Routes behind `require_full_auth`
(`every_calendar_route_needs_a_session` unchanged: no new route). The device endpoint is bearer-only.

## 7. The launcher

**D6.1 - The kid's experience doesn't change**: the "+", the form, "Min kalender" and its peach, `kidMayEdit`, offline
saving - all as today; no "sending…" mark, no server or brand word anywhere the kid looks (the feed calendar's name is
the parent's, as today). The only new thing the kid can see: an event the parent changed in the calendar changes on
the phone. No new permission (`READ_CALENDAR`/`WRITE_CALENDAR` are self-granted already), nothing needing adb.

**D6.2 - Pieces**: pure `day/KidCaldav.kt` - `kidCaldavPolicy` (the policy's object; missing or malformed = off,
nothing sent), `kidSnapshot(rows, phoneTag, nowMs)` (D1.3: the rows, the cuts, the order, the rev), `backOps(back,
rows, phoneTag)` (D2.3: update / insert with the UID / delete / ignore a foreign UID), `skipSet(rows, lastPosted,
pending)` (D3.2). Glue `calendar/KidCaldavSync.syncIfNeeded(context, api, policy.calendar)` in `MdmSyncWorker`
right before `CalendarFeeds.refreshIfNeeded` (never throws): read, post when D1.5 says, apply the hand-back, keep the
last posted UIDs in prefs. `CalendarStore` stays the provider's only writer: `kidEventsBlocking` (the read) and
`applyKidBackBlocking` (the guarded writes, each re-checking tag and calendar). `MdmApi.kidEvents(body)`.
`CalendarFeeds`: the skip set before `feedPlan`, `CAL_SYNC6`, `feedFetch`'s extra reason. The form's save, change and
delete call `requestSync` while marked (D1.5b). Capability `calendar_kid_caldav_v1`.

**D6.3 - The policy keeps the kid writing locally**: while a CalDAV calendar is marked, no `writable_for_kid` row is
sent (D4.1), so `kidCalendarOf` picks "Min kalender" and `ensureKidCalendar` makes it - `kidCalendarOf` is untouched.
(The feed calendar is `CAL_ACCESS_READ`; pointing `writable_for_kid` at it would leave the kid with no "+".)

## 8. The contract

`server/testdata/calendar_kid_events.json`, byte-identical in `launcher/app/src/test/resources/` (one commit, first):
`policy` (a `kid_caldav` object), `provider` (the "Min kalender" rows the phone reads, with a foreign-UID row, a
recurring row and one past the horizon left out, a non-ASCII title, an empty place), `now` and `phone_tag`, `snapshot`
(the exact POST body for them), `answers` (`accepted` with an upsert and a delete hand-back, an all-day upsert,
`not_marked`, `limited`, `too_big`), `back_provider` (the provider values each hand-back writes). Server
`calendar_kid_events::tests::the_contract` posts `snapshot` against a fake CalDAV, checks each PUT's body against
`server/testdata/calendar_kid_events/<uid>.ics` (server-only) and produces every `answers` row; launcher
`KidCaldavTest` encodes `provider` to exactly `snapshot`, maps each hand-back to exactly `back_provider`, decodes
`policy` and the answers; both compare the copies. Both workflows' path lists and the root `CLAUDE.md`'s contract
paragraph; `intake_limits` keeps `snapshot`'s keys.

## 9. Privacy and security

- **The kid's own events now leave the phone** - but only while the parent has picked a CalDAV calendar for them:
  title, place, a parent-added description, times and the UID (with the 8-hex phone tag, a hash; never ANDROID_ID)
  go to the server, which keeps the latest set (D4.8), and on to the CalDAV host (Apple for iCloud) under the parent's
  account, where everyone who shares that calendar sees them. Design 69's "no event ever leaves the phone" becomes
  "the kid's events leave the phone only to the calendar the parent picked" in the PWA's copy (D5.4) and in
  `docs/notes/server/calendar.md`'s first paragraph and `docs/notes/launcher/dagen-and-calendar.md`.
- The password stays sealed on the server; the phone never sees it, the account or the host. The policy carries a
  feed uid and counts, nothing more.
- **No insecure fallback**: no write without `If-Match`/`If-None-Match`; no write to an unconfirmed host, a LAN host
  outside its pin, or over plain http to a public host; a 403 stops the calendar, never retries elsewhere or as the
  account; a lost key is `key_lost` ("log in again"), never a write in the clear; TLS as every server fetch (the
  system roots, `server/deploy/distrusted-roots.txt` in the image).
- Abuse by the phone: a bearer endpoint, one POST per 10 s, 300 rows, 256 KB, fields cut, every row through
  `known_fields`. The phone tag is the phone's own claim and the server can't check it, so it isn't the guard: the
  server deletes and updates only **resources it created from this phone's sets** (rows keyed by device), creates
  only at `<collection>/<uid>.ics` with `If-None-Match: *`, and adopts an existing resource (D4.4) only for a UID
  matching `vibb-[0-9a-f]{8}-<uuid>`. A phone can't name an href, delete a parent's or a sibling's event, or write to a
  calendar not marked for it. (A tampered phone could claim a sibling's UID at a re-mark's adopt - the cost of D1.4's
  re-mark; the open question 3's default keeps that rare.)

## 10. Tests (lean)

- **Server** (`calendar_kid_events::tests`, `tests::caldav` with `CannedFetch::answer_method` for PUT/DELETE): the
  contract; create with `If-None-Match: *`, update and delete with `If-Match`, no request without a condition; 412 on
  update → the parent's version handed back and the kid's change dropped; 412 on create → adopt; `no-uid-conflict` →
  the href adopted, another host refused; 404 on delete → done; 401 → `login_refused`, **403 → `write_refused` and the
  account still `ok`**; 507; 400 counted; no ETag in the PUT answer → PROPFIND; the fetch after a write sees no parent
  edit (no loop); a changed ETag in the REPORT → upsert hand-back; a missing resource confirmed by PROPFIND, a moved one
  handed back as an upsert; a recurring edit released; the hand-back applied → nothing written; the 7-day drop;
  the horizon forgets, never deletes; the mass-delete hold (held twice, accepted the third time and after 24 h); 60 a
  minute; writes wait while the account is stopped and resume after Test; the select (offered only ticked and not
  `writable: false`, one per phone, clears `writable_for_kid`, the older launcher disabled); untick/remove account/
  delete phone cascade; the status lines; 429/413/409/400; the snapshot of `policy_json_keys_snapshot`; the writer's
  folding and escaping round trip; no text in the settings log.
- **Launcher**: `KidCaldavTest` (the contract; a full read only; ≤ 300 nearest; the rev stable across order);
  `backOps` (update, insert with the UID, delete, a foreign UID ignored); `FeedPlanTest` + `skipSet` (own events
  skipped, a released one shown and its document fetched again, a newly skipped one deleted locally with no GET,
  DAVx5-era own events shown); `PolicyResponseCompatTest`.
- **Emulator §6z** (`docs/testing/emulator.md`; Radicale on the host as design 72 §10, a read-only collection by its
  rights file): mark → the kid adds "Kino" → it is in Radicale (`curl`) and shows once in Dagen; edited by `curl PUT`
  → the phone shows the new time; deleted by `curl` → gone on the phone; the kid deletes → gone in Radicale; airplane
  mode, two events added and one deleted → back online → Radicale matches; the read-only collection → "tar ikke imot".
  **A real account** (the user's iCloud, or Fastmail/Nextcloud): the UTC body accepted, the ETag on PUT, the
  privilege set at Test, iCloud's body rewrite not seen as an edit [verify].

## 11. Steps and sizes

One branch, as design 72 step 1:
1. **The contract** (both dirs, the workflows' paths, root `CLAUDE.md`) - **S**.
2. **Server** - **M**: 0117, `calendar_kid_events.rs` (the endpoint, the diff, the job, the hold), `caldav.rs`'s
   request shape and write answers, `ics::write_event`, D4.6's per-resource read, D4.7's `writable`, the select, the
   status line and the account page's line, the words, the policy key.
3. **Launcher** - **S-M**: `KidCaldav.kt`, the glue and the store's two methods, the skip set and `CAL_SYNC6`,
   `requestSync` after the form, the capability.
4. **§6z on the emulator + one real account** - **S**.

If the user picks one-way (question 1): D2.3-D2.4 and D4.6 go - server **S-M**, launcher **S**.

## 12. Open for the user (recommendation first)

1. **The parent's edits come back to the kid's phone, and the calendar wins a clash** - or one-way: the phone is the
   truth, an edit in the calendar is overwritten the next time the kid changes that event, and a deleted one stays on
   the kid's phone.
2. **At the pick, the kid's events already in "Min kalender" (from 60 days back) are written too** - or only events
   made after the pick.
3. **When writing stops (another pick, the calendar unticked, the account removed), what was written stays in the
   calendar** - or the server deletes the kid's events from it (a wrong click would wipe them from the family's
   calendar).
4. **The kid's events go in as the kid wrote them** ("Kino") - or with the kid's name first ("Ola: Kino"), useful
   when two kids write to one family calendar, at the price of a prefix the server must strip on the way back (a
   calendar per kid is the tidier way).
5. **The choice sits in Dagen's "Barnets avtaler"**, with a line on the account's page - or a switch on the account's
   page beside the calendar.

## 13. What the implementer's brief must carry

- D1.1's snapshot (deletes by absence, a full read only), D1.6's policy object, D2.5's three echo rules, D2.6's horizon,
  mass-delete hold and confirmed-missing, D3.2's skip set (Min kalender ∪ last posted while pending; always on).
- D4.4's **403 = `write_refused` for writes, never the account's `login_refused`**; every write conditional; the
  ETag after a PUT that returned none; D4.5 unchanged hosts and pinning; the fetch right after a write.
- The contract `calendar_kid_events.json` in both dirs in one commit (first), the workflows' path lists, root
  `CLAUDE.md`; `policy_json_keys_snapshot` + `PolicyResponseCompatTest`; capability `calendar_kid_caldav_v1`; migration
  0117 (renumber at merge if passkeys took it).
- No event text in any log, page, error or settings-log row (codes, counts, indexes); no `Debug` on the set or rows.
- i18n nb + en for every word (`calendar.kid.caldav_option`, `calendar.kid_caldav.*`, D5.4's copy); the save a 303
  to `#dagen`; the notes (`calendar.md`, `dagen-and-calendar.md`) and PLAN's queue updated at the merge.
