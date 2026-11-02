# 72b - CalDAV write-back: the kid's own events go to the calendar the parent marks

Status: design note, architect 2026-11-02, on `main` at dac8a52a. Revised the same day after the design QA
(`qa-72b-design.md`: 2 High, 8 Medium, 13 Low, 8 Nits). Every High and Medium is taken, and so are the Lows and Nits
(§14 maps each one). The revision check of 89aca7a6 (2 Medium, 5 Low, 4 Nits) is folded in too. The user answered the five questions the same day (§12), and the decisions follow those answers.
This is design 72 step 3 (§7 sketched it as "not planned"). PLAN entry: "CalDAV write-back - the kid's own Dagen
events to the parent-marked CalDAV calendar". It touches both directories and adds one shared contract file.

The user, 2026-11-01: "man skal kunne legge inn en caldav kalender og velge at det er kalenderen barnet selv skal
kunne legge inn dagen i dag hendelser i". 2026-11-02, on the hand-back: "det må jo kunne synkroniseres!".

The accepted shape:
- The parent marks one CalDAV calendar as the kid's own. It must belong to an account already added for reading.
- The events the kid makes on the phone (today in the phone's LOCAL "Min kalender") are written to that calendar by
  the server, as a `PUT` of a VEVENT with the app password the server keeps.
- What the parent changes in that calendar comes back to the phone.
- The password never goes to the phone.

**Naming.** The kid's app "Dagen" is being renamed "Kalender" (the user, 2026-11-02). User-facing words here say
Kalender. Code names (`#dagen`, `save_dagen`, `day/`, design 69's terms) stay until that rename's own branch changes
them.

[verify] = a real server or the emulator answers it (§10).

Each decision gives **the recommendation**, then the alternatives in one line.

## 0. What we build on

**The kid's events today** (design 69 step 3, `docs/notes/server/calendar.md` "The kid's own events",
`day/KidEvent.kt`):
- The form "Ny avtale" has what, the day, from, a length and a place. No recurrence, no alarm.
- `CalendarStore.insertKidEventBlocking` writes the event as a plain app into `kidCalendar()`. That is the parent's
  writable pick (a DAVx5 calendar, `device_calendars.writable_for_kid`), else "Min kalender" (LOCAL, account `Vibb One`,
  NAME `vibb-kid-calendar`, key `calendar::KID_LOCAL_KEY`).
- Each event gets `UID_2445` = `vibb-<phone tag>-<lowercase UUID>`, `CUSTOM_APP_PACKAGE` = ours, and the phone's
  `EVENT_TIMEZONE`.
- `ownedByVibb`/`kidMayEdit` let the kid change or delete only the phone's own events in the kid's calendar.
- Change and delete go through the plain URI, so a delete leaves a `DELETED=1` row.
- The PWA's one choice is the select "Barnets avtaler" (`kid` = `off` | `phone` | a reported key, a 303 back to
  `#dagen`). That gives one kid's calendar per phone by construction.

**CalDAV read** (design 72 §6, `src/caldav.rs`):
- `caldav_accounts` is the family's. The password is sealed (`caldav-v1:`). `hosts` lists the only hosts it may go to.
  `state` is `ok`, `login_refused` or `host_changed`.
- Each ticked calendar is a `calendar_sources` row of kind `caldav`, subscribed per phone in `device_calendar_feeds`
  (PK device + source). Migration 0110's comment reserves "the kid's own" flag for this row.
- `Dav::ask` goes one hop at a time, each hop judged by `calendar_feeds::route`. A LAN account is pinned to its host and
  port. A request to another host is `host_changed` before it goes. Any 401/403 is `login_refused`. Every request goes
  through `state.music_fetch` (one in flight server-wide, fair FIFO). A 3xx is re-sent with the same method.
- The fetch: a PROPFIND for the ctag, then one `REPORT calendar-query` (−60 d .. +401 d). The REPORT asks for
  `getetag` but keeps only the bodies.

**The read-back on the phone**: a ticked calendar is a `vibb-feed-<uid>` LOCAL read-only calendar (`FeedPlan`, design
72 §4). A sibling who ticks the same calendar gets the same set.

## 1. The shape

```
the kid saves in Kalender ──► Min kalender (unchanged; offline fine)
        │ sync (soon after the save)
        ▼
POST /api/devices/calendar/kid-events  {calendar, rev, from, cut, cut_from, cut_uids[], ended[], applied[], events[]}
        │ answer: {accepted, pending, back[]}     ◄── the parent's edits and deletes, until acknowledged
        ▼
server: diff against kid_caldav_events ─► PUT/DELETE <collection>/<uid>.ics (If-None-Match / If-Match)
        │      every 412 → GET, compare by content: our own lost answer, or the parent's edit (the calendar wins)
        ▼
refetch the calendar ─► feed rev ─► every phone with it ticked; this phone skips its own (§4)
```

## 2. The phone hands the events over

### D1.1 - A snapshot, not a queue of operations

The phone sends **the whole set** of the kid's own coming events, and the server diffs it against what it wrote.

- A delete is an event missing from the set, under the rules of D1.3 and D2.6. A missing event is never a delete for
  any other reason.
- A lost request, a killed process or a week offline needs no outbox: the next snapshot says everything. The scheme is
  self-healing and idempotent.

*Alt: an operations log (create, edit, delete) with acks - a queue to persist, replay and order, and a lost delete is
lost for good. Or the provider's `DIRTY` flag as the outbox - it says nothing about deletes, because a row that never
synced is deleted outright.*

### D1.2 - Its own endpoint, not the status report

The phone posts to `POST /api/devices/calendar/kid-events` (bearer) only when one of these holds:
- the set differs from what the server last accepted (`kid_caldav.accepted`, D1.6);
- the server has something to hand back (`back > 0`);
- the phone has hand-backs to acknowledge (`applied` not empty).

The status report goes at every sync, stays small, and holds "never an event" (design 69).

*Alt: a block in the report - every sync pays, and the report's privacy rule breaks.*

### D1.3 - Which events: the coming ones, however far ahead (the user's answer 2)

**What is in the set:**
- Rows in "Min kalender" (`vibbLocal`) that `ownedByVibb` accepts: this phone's tag, or a `vibb-` UID with our
  `CUSTOM_APP_PACKAGE`. This is the same test `kidMayEdit` uses. A launcher data clear on a phone with no
  `ANDROID_ID` changes the tag, and the kid's events still count (QA M3).
- The UID must match D4.9's pattern.
- Not `DELETED`, no `RRULE`/`RDATE`.
- **Not ended**: `end > now`. There is no forward horizon.

**What is never in the set:**
- **Past events**, at the pick or later. An event that ends while it is written stays on the calendar as it is (D2.6).

**The limits:** at most **300 rows and 512 KB**. Past either, the farthest from now are left out. The set then says:
- `cut` - how many rows were left out;
- `cut_from` - the **earliest start among all rows left out**, whatever dropped them (the row count or the bytes);
- `cut_uids` - the uids left out, at most 2000. Past 2000 the list is dropped, `cut_uids` is `null`, and the server
  deletes nothing for that set (R-L3).

These limits are a safety net only, never a reason to delete (D2.6). The PWA shows them (D5.2).

**`ended`** - the uids only, no text: this phone's rows in "Min kalender" (picked by the same test) that ended within
the last 60 days, at most 300. The server never deletes a uid in `ended` (D2.6). This covers the kid who moves "Kino"
from tomorrow to yesterday: the event leaves the set because it has ended, and it must not be deleted from the family
calendar (R-M1). No text leaves the phone, so the privacy line doesn't change.

**Fields** - each value raw from the provider, through D2.7's canonical function only:
- `uid`;
- `summary`, `location`, `description`;
- `start` and `end` (UTC, RFC 5545 basic form `20261103T140000Z`; an all-day event `20261103` with an exclusive end and
  `all_day: true`);
- `tz` (the zone the event was entered in; it resolves a parent's floating time, D2.7).

**The set's metadata:**
- `from` = the phone's now, the set's lower bound.
- `rev` = 16 hex of SHA-256 over the canonical JSON of `events` (sorted by uid). The server stores it verbatim.

**When a set is sent:** only from a **full read** - the provider readable and "Min kalender" found. An unreadable
provider, a missing calendar or an `UNKNOWN` policy sends nothing. A "Min kalender" re-made empty after a data clear
does count as a full read, so D2.6's hold handles it.

*Alt: also the past 60 days (the user said no); also a DAVx5 pick's events (DAVx5 writes those already).*

### D1.4 - The UID is the identity end to end

- The resource is `<collection>/<uid>.ics`, built by appending to the path (D4.9). The VEVENT's `UID` is the same.
- A second PUT of the same event is an update, never a copy.
- A re-marked calendar finds its old resources by D4.4's GET-first rule.
- DAVx5 round-trips the same UID (design 69 QA L1).

### D1.5 - When

The phone posts at three moments:
1. **At every sync** where D1.2 says so, before `CalendarFeeds.refreshIfNeeded`.
2. **Right after the kid saves, changes or deletes an event**: `CommandListenerService.requestSync(context,
   "kid_event")` while `kid_caldav` is set. Repeated requests collapse into one run.
3. **After a 429**: one more `requestSync` once `Retry-After` has passed, so a quick second edit doesn't wait a whole
   sync (QA N6).

Offline, nothing is queued: the set goes at the next sync with a network. A failed POST changes nothing on the phone.

*Alt: a POST from the form itself - a second network path beside the sync, and the form must not wait on the
server.*

### D1.6 - The policy's `calendar.kid_caldav`

The policy always sends this key. It is `null` when nothing is marked or the launcher lacks the capability. Otherwise:

`{"id": "<source uid>", "accepted": "<rev>" | null, "back": 0, "pending": 0}`

- `id` - the marked calendar's feed uid; the phone knows its `vibb-feed-<uid>` calendar by it.
- `accepted` - the last set the server took. It is `null` after a pick or a re-pick, which means "send it all".
- `back` - how many hand-backs wait (D2.3).
- `pending` - how many writes wait (the phone's skip set, D3.2).

`policy_json_keys_snapshot` pins `kid_caldav: null`, and the contract pins the object's keys (§8);
`PolicyResponseCompatTest` covers the phone side. The capability is `calendar_kid_caldav_v1`. Without it the server
sends `null` and the PWA doesn't offer the choice (D5.3).

A database restore needs no hook. Stale ETags answer 412, D4.4 reads the resource and the content matches, so the
ETag is learned again (QA L7). D2.6's hold may fire once after a restore, which is fine.

### D1.7 - The answers (pinned in the contract)

**200** `{"accepted": "<rev>", "pending": n, "back": [...]}`. D4.2's writes run after the answer. While D2.6 holds a
set, `accepted` stays the old rev.

**409** `{"error": "not_marked"}`: no calendar is marked, or `calendar` isn't the marked one (a stale policy). The
phone waits for the next policy.

**429** with `Retry-After`: at most one POST per 10 s per phone.

**413**: over 512 KB. A phone that follows D1.3 never sends one. The PWA still says so (D5.2).

**400** - the whole set, never a partial one (QA M3):
- a row that doesn't read: every required key is checked explicitly, not through `known_fields`' silent drop;
- a UID outside D4.9's pattern;
- a tag pinned by another phone (D4.9);
- a `from` that doesn't parse.

The phone reads anything but 200 as "try at the next sync".

## 3. Conflicts and the way back (the user's answer 1: two-way, the calendar wins)

### D2.1 - The calendar wins

An event the parent changed or deleted in the CalDAV calendar is changed or deleted on the kid's phone too. If the
kid changed the same event meanwhile, the kid's change is dropped.

The parent manages the family's calendar, and the server and the parent own the rules. A kid's event the parent moved
must not move back silently.

"The calendar wins" applies only when the calendar actually changed against the last version the server knows: the
**base** (`remote_hash`, D4.4). With no base, the calendar's version wins outright. That happens on a re-pick after an
unmark, which deletes the rows. So a re-pick reverts any edits the kid made while unmarked to the calendar's version.
This is an accepted consequence (R-L1).

*Alt (not chosen): the phone wins; or one-way.*

### D2.2 - Detection on the server, by content

Per kid event the server keeps:
- the resource's `href` and `etag`;
- `remote_hash` - the canonical hash (D2.7) of what the calendar holds as last written or read.

A parent's edit is **a changed content hash**, never a changed ETag alone. iCloud and Fastmail rewrite bodies, and
some servers do it after the PUT (QA M1). The edit is seen two ways:
- **(a)** A conditional PUT or DELETE answers **412**. The server then `GET`s the href and compares (D4.4).
- **(b)** The marked calendar's fetch shows the uid's resource with another `getetag` **and** another hash (D4.6).
  Another ETag with the same hash only stores the new ETag.

The phone never compares versions.

### D2.3 - The hand-back, acknowledged (QA H2)

**The rows.** The 200's `back` is a list of:
- `{"id": "<16 hex>", "uid", "op": "upsert", "summary", "location", "description", "start", "end", "all_day"}`, or
- `{"id", "uid", "op": "delete"}`.

An upsert carries the parent's version in canonical form (D2.7).

**On the phone,** each row is applied through `CalendarStore` with the kid-write guards. `DELETED=1` rows are ignored
everywhere (QA L6).
- An upsert updates the live row with that UID in "Min kalender". If there is none, it inserts one with that same UID
  and our `CUSTOM_APP_PACKAGE`: the kid deleted it while the parent edited, and the calendar wins.
- A delete removes the row.
- A UID that `ownedByVibb` refuses is never touched.
- An all-day event lands as design 69 §2.4 says (UTC midnights, `EVENT_TIMEZONE` UTC).

**The acknowledgement.** The phone keeps the ids it applied, and the ids it deliberately ignored, in prefs (at most
300). The next POST sends them as `applied`, and the phone clears them on a 200. A row the provider refused is not
acknowledged and is applied again from the next answer.

**On the server** (R-M2):
- **The id** = 16 hex of SHA-256 over the uid + the handed-back version's hash. A delete's version hash is the fixed
  string `delete`. A second parent edit is therefore a new id, which replaces `back_id`/`back_json`.
- A uid with a waiting hand-back is **never written**.
- An `applied` id clears the wait **only when it equals the uid's current `back_id`**. An unknown or stale id is
  ignored, and the current hand-back is sent again.
- On a match, `sent_hash :=` the hash of **the version that was acknowledged** - never whatever `remote_hash` has
  moved to since. An acknowledged `delete` drops the row.
- A kid's change after that is an ordinary write.
- A hand-back that isn't acknowledged is re-sent in every 200 until it is. There is **no expiry**. The policy's `back`
  makes the phone post at each sync meanwhile, and a phone offline for a fortnight gets it when it is back.

### D2.4 - What can't come back is released

A parent's edit the phone can't keep turns the event into the parent's:
- it becomes recurring (`RRULE`, `RDATE`, an override);
- or it gains `ATTENDEE`/`ORGANIZER` (QA M8). Rewriting it would make iCloud, Fastmail and Nextcloud send the invitees
  a cancellation (RFC 6638).

**What happens:**
- The phone gets a hand-back `delete`.
- The uid's row becomes a **tombstone** (`state = 'released'`). It is never written again until it has been absent
  from the phone's sets for 60 days (QA H1). A stale set that still carries it writes nothing.
- The event then shows on the phone from the feed as one of the parent's: §4 skips only what is in "Min kalender".

**Properties the phone doesn't keep are lost at the kid's next change.** An alarm or a colour the parent added goes,
because the server writes the whole resource. Kalender's ⓘ says so (D5.4).

*Alt: merge into the stored remote VEVENT, replacing only the known properties - it keeps them, costs +S, and the
server must store each resource's text.*

### D2.5 - No echo loops

Three rules, all on D2.7's hashes:
1. **When the server writes a uid.** Only when no hand-back waits for it, **and** the phone's version differs from the
   last one the phone sent (`sent_hash`: the kid changed it), **and** it differs from what the calendar holds
   (`remote_hash`: not already there). An acknowledged hand-back comes back equal to the calendar and writes nothing.
2. **Its own write is recognised.** After a PUT, `remote_hash` = the hash written. The ETag comes from the answer. If
   the answer has none (RFC 4791 §5.3.4), the server reads the resource **with its body** (`GET` with its `ETag`), then:
   - an equal hash → that ETag is stored;
   - a different hash → a parent's edit that came between, handled as (b).
   The server never reads a bare ETag and takes it as its own (QA M1).
3. **The phone's feed apply skips its own events** (§4). A fetched kid event never lands in the provider twice and
   never changes "Min kalender".

### D2.6 - What is not a delete, and the holds

**The past is left alone** (the user's answer 2):
- An event whose end has passed leaves the set and is **never deleted on the calendar**.
- A row becomes `past` when its uid is missing from the set and either its stored end is at or before
  max(`from`, the server's now), or the uid is in `ended` (RN1, R-M1). From then on: no write, no hand-back, and no
  new version stored. The row keeps its hashes (D4.1).
- The row is dropped 60 days after the event's end.
- If the kid later moves it into the future, it is in the set again and D4.4's base rule writes it.

**A delete has to pass every test.** A uid missing from a set is deleted on the calendar only when its stored row:
- is `ok` (not `released`, not `past`, no hand-back waiting);
- is **not in `ended`**;
- ends after max(`from`, the server's now) (QA M3);
- and, when the set is `cut`, has a stored start before `cut_from` and is not in `cut_uids`. If `cut_uids` is
  `null`, nothing is deleted.

The stored `start_at` and `end_at` are updated from every remote read - a hand-back, a GET, the fetch (RN2) - so a
parent's move is judged by where the event is now.

A `from` more than a day from the server's clock freezes every delete for that set ("Telefonens klokke går feil"),
while its writes go through.

**The kid's mass delete is held** (QA M4). A set that would delete 5 or more events, and more than half of those
written, is held **for 24 h by time** (not by a count of sets).
- Typical causes: "Min kalender" wiped by Calendar Storage's data clear, or a phone set up again.
- Meanwhile its writes go through, its deletes wait, and `accepted` stays the old rev.
- The status line offers "Legg dem tilbake på telefonen" (D5.2). It hands the held uids back as upserts, read from
  the calendar.
- After 24 h with no answer, the deletes go.

**The calendar's mass delete is held** (QA L9). The same 5-and-half rule applies to hand-back deletes: held 24 h, the
status line saying so, then sent. A parent's "delete all" or a server-side wipe doesn't empty the kid's day in one
fetch.

**A missing resource is confirmed** by its own href before it counts as the parent's delete:
- a `GET` that answers 404 is a delete;
- a 200 means the parent moved it past the REPORT's window. It is parsed and handed back as an upsert.

### D2.7 - One canonical form (QA M2)

One function, the same on both sides, pinned row by row in the contract (§8).

**The text fields:**
- Input: raw values (the phone doesn't run `TitleRules.clean`).
- `\r\n` and `\r` become `\n`.
- C0 controls other than LF and TAB are dropped.
- Then each field is cut **by code point**: `summary` 200, `location` 200, `description` 2000 (design 72's `ics`
  caps). Nothing else is changed: no whitespace folding, no NFC.

**The times:**
- **Timed**: start and end as UTC instants. A missing `DTEND` is a zero length. A `DURATION` is resolved to an end.
- A floating time (a parent's edit) is resolved in the row's `tz` from the last set.
- **All-day**: `YYYYMMDD` start and **exclusive** end. A missing `DTEND` is one day.

**The hash** = 16 hex of SHA-256 over the canonical JSON `{summary, location, description, start, end, all_day}`,
taken **after** the cuts. The zone and the bytes never count: iCloud rewrites both.

The form still cuts what the kid types to 100 (`KID_EVENT_TITLE_MAX`) and runs `TitleRules.clean`, but only on a
field the kid actually changed. A title or place the kid left alone is saved exactly as it was (R-L5). So a kid who
only moves the time never cuts or folds a parent's longer title.

## 4. Read back without duplicates

### D3.1 - The marked calendar is a ticked calendar

The select offers only CalDAV calendars ticked for this phone (D5.1). So:
- the parent's own events in it reach the kid as today;
- the kid's events reach a sibling who ticks the same calendar, read-only and in that calendar's colour, as with a
  DAVx5 family calendar (design 69 QA L1).

The feed set stays one per source (design 72 §2: the fetch is shared), and the server filters nothing per phone.

### D3.2 - The phone skips its own (QA M5)

Before `feedPlan`, `CalendarFeeds` drops from every feed document each series whose UID is in the **skip set**:
- the UIDs of the live rows in "Min kalender"; plus
- **every UID posted since the server last showed `accepted` = the last posted rev with `pending` = 0**. The phone
  keeps this union in prefs and resets it to the current set's UIDs when a 200 or a policy shows both.
- `pending` counts a write **until the fetch after it has stored the new set** (R-L2). So the union is never reset
  while the feed may still hold a deleted event.

This way an event the kid just deleted is not shown back from a feed that still holds it - in the same sync (the
policy's `pending` was read before this POST) or while a DELETE is stuck (`write_refused`, `login_refused`).

**`CAL_SYNC6`.** Each feed calendar keeps its skipped UIDs in `CAL_SYNC6` (a JSON array, at most 300).
- A UID that leaves the skip set (released, deleted by a hand-back, its delete done) makes `feedFetch` GET that
  document again. It sends **no `If-None-Match`** (QA N3), or the answer is a 304.
- A UID that joins the set has its rows deleted from that calendar locally, with no GET.

**The filter always runs, marked or not.** An event in "Min kalender" is never shown twice, whatever was written where.
Dagen's "two copies one row" stays as the backstop. Events this phone once wrote through DAVx5 (its tag, not in "Min
kalender") show from the feed, read-only.

*Alt: skip by the phone's tag alone - those DAVx5-era events would vanish. Or the server leaving the phone's own
events out of its copy - a per-phone document, which loses the shared fetch and its ETag.*

## 5. Server

### D4.1 - Model

Migration **`0117_calendar_kid_writes.sql`**: the next free after 0116 (passkeys step 1, in build). Passkeys step 2
also wants a number, so renumber at merge if needed.

**`device_calendar_feeds.kid_writes`** `INTEGER NOT NULL DEFAULT 0`, with `CREATE UNIQUE INDEX
idx_device_calendar_feeds_kid ON device_calendar_feeds(device_id) WHERE kid_writes = 1` (one per phone).
- Only a `caldav` source, checked in code and by a test.
- Setting it clears `device_calendars.writable_for_kid` and sets `calendar_kid_adds = 1`, in one transaction.
- Picking `phone`, `off` or a reported key clears it.

**`device_kid_events`** - `device_id` is the PK.
- `source_id`, with FK `(device_id, source_id)` → `device_calendar_feeds` ON DELETE CASCADE. An unticked calendar, a
  removed account or a deleted phone takes it all.
- `rev`; `events_json` (the last set, replaced at each POST); `from_at`, `cut`, `cut_from`, `cut_uids`, `ended`
  (uids only).
- `rejected` - the code of the last set refused with a 400, and when (R-L4).
- `received_at`, `written_at`, `posted_at` (the 10 s gap).
- `error` (a code) and `error_at`.
- `held_since` and `held_kind` (`kid`/`calendar`, D2.6).

**`kid_caldav_events`** - PK `(device_id, uid)`.
- `source_id`, with the same FK.
- `href` and `etag` (verbatim, at most 500 characters, no CR/LF).
- `sent_hash`, `remote_hash`, `attempted_hash` (the version of a write in flight).
- `sequence`, `start_at`, `end_at`.
- `state`: `pending`, `ok`, `refused`, `released`, `past`.
- `failures`, `next_at`.
- `back_id` and `back_json` (the hand-back while it waits).
- `released_absent_since`.

`past` and `released` rows keep their hashes (`sent_hash`, `remote_hash`) and no text. A hash is not text, so this is
within D4.8. These hashes are the base that D4.4 needs when such an event comes back (R-L1).

**`kid_caldav_tags`** - `tag` is the PK; plus `device_id` (cascade). It holds each phone tag and the device that
first posted it (D4.9). It holds no text, and it is deleted with the device by cascade (RN4).

Unmarking deletes the phone's rows in the two event tables in the same transaction that clears the flag. The tags
stay.

### D4.2 - The write job (`src/calendar_kid_events.rs`; the requests in `caldav.rs`)

**When it runs.** The POST stores the set and answers at once. A job then works the diff, and every scheduler pass
picks up what still waits.

**One job per account at a time** (QA L4). A `write_claimed_at` claim, as Test's `claim` (`caldav.rs:952`). A POST, a
pass and a sibling's POST on the same account never race. Each write reads the uid's latest `events_json` row just
before it goes.

**Pace:**
- One request at a time through `music_fetch`.
- At most **60 writes a minute per account**; the rest go at the next pass.
- **Backoff is per account** (QA L1). A 429 or 503 stops the account's writes until its `Retry-After`, else by
  `music_sweep::backoff`.
- Other per-event failures back off alone.

**No request** while the account isn't `ok` or its password can't be opened. The writes wait as `pending`; the
account's stop is design 72 QA M5's, unchanged.

**After a job that wrote anything,** the calendar is fetched - at most once a minute per source (QA L5). Its scheduled
fetch runs its pending writes first.

**The nudge** (`command_notify`) goes only when one of these happens (QA L5):
- `pending` reaches 0. A write counts in `pending` until the fetch after it has stored the new set (R-L2);
- `back` goes above 0;
- a new stop.

### D4.3 - The requests

`Dav::ask` grows a request shape: the method, an optional Depth, the content type, and an `If-Match`/`If-None-Match`
header. Each method reads 401 and 403 its own way (D4.4).

Its rules stay exactly as they are: the hop limit, the host, the LAN pin. One change: **a write never follows a 3xx**
(QA L1). A redirect answered to a PUT or DELETE is a `status` error for that event.

**The three writes:**
- **Create**: `PUT <collection>/<uid>.ics`, `If-None-Match: *`, `Content-Type: text/calendar; charset=utf-8`.
- **Update**: `PUT <href>`, `If-Match: <etag>`.
- **Delete**: `DELETE <href>`, `If-Match: <etag>`.

There is never a write without a condition. **No strong ETag means no writes** (QA L2): a calendar whose resources
come with no `getetag`, or only a weak `W/"…"` one (`If-Match` compares strongly, RFC 7232 §3.1), is stopped as
`no_etag`.

**The body**, from a small writer `ics::write_event`:
- CRLF line ends, TEXT escaping of `\ ; , \n`, folding at 75 octets never inside a UTF-8 sequence.
- Input already canonical (D2.7), so no C0 controls.
- Round-tripped through `ics::parse` in its tests.

It writes a `VCALENDAR` (`VERSION:2.0`, `PRODID:-//Vibb//Vibb One//EN`) with one `VEVENT`:
- `UID`, `DTSTAMP`, `CREATED` (first PUT), `LAST-MODIFIED`;
- `SEQUENCE` = max(stored, the parent's last read) + 1 (QA L11);
- `SUMMARY`, and `LOCATION` and `DESCRIPTION` when not empty;
- `DTSTART`/`DTEND` in **UTC** (`...Z`; all-day `VALUE=DATE`). A single event needs no `VTIMEZONE`, and every client
  shows the instant in its own zone [verify iCloud, Fastmail, Nextcloud, Radicale].
- No `VALARM`, `ORGANIZER` or `ATTENDEE`.

### D4.4 - Reading the answers (QA H1, M6)

**The rule: never PUT over a resource whose content the server hasn't read.**

**201/204** → `ok`. `remote_hash` = the hash written, and the ETag is taken per D2.5.2.

**412, or a 404, on a create (`If-None-Match: *`).** The resource exists: a re-pick, a lost answer, a `past` event
moved back into the future, or a sibling's UID claimed by a tampered phone. The server `GET`s it, parses it, and
compares the GET's hash in this order:
- equal to the phone's version → store its ETag, `ok`;
- equal to `attempted_hash` → our own create whose answer was lost (R-L1a). Store the ETag, then do an ordinary update
  with `If-Match` if the phone's version has moved on since;
- equal to the stored base (`remote_hash` of a `past` or earlier row) → the parent changed nothing (R-L1b). PUT the
  phone's version with the GET's ETag;
- recurring, or with `ATTENDEE`/`ORGANIZER` → released (D2.4);
- otherwise - it differs from the base, or there is no base → a hand-back upsert of the calendar's version (the
  calendar wins), `remote_hash` = what was read.

A create answered 404 whose GET also answers 404 (the collection gone, a server quirk) is that event's `status`
error, not an adopt (RN3).

**412 on an update or delete.** The server `GET`s the href:
- a 404 → the parent deleted it. An update becomes a hand-back delete; a delete is done. (With `If-Match`, a missing
  resource answers 412, not 404: QA L11.)
- the hash equals `attempted_hash` → our own answer was lost; store the ETag.
- anything else → the parent's edit: a hand-back upsert (or released), and the kid's change is dropped.

**404 on an update or delete** (rare, with the condition): the same as a 412 followed by a GET 404.

**403: the body decides** (RFC 4791 §5.3.2.1):
- `C:no-uid-conflict` → its `<D:href>`. It is adopted only if it is a direct child of the collection after
  normalisation (D4.9) and on a confirmed host, and then only through the GET-first rule above. Otherwise `refused`.
- Any other `C:` precondition (`valid-calendar-data`, `max-date-time`, `max-resource-size`, …) → that event `refused`.
- `D:need-privileges`, or no body → **`write_refused`** for this calendar. Every write of the phone stops until the
  parent picks again or Test passes. **It is never the account's `login_refused`**: today's `ask` reads 403 that way,
  and writes must not.

**401** → the account's `login_refused`, as today.

**409, or Nextcloud's 400 "already exists"** for a UID at another href (QA N4) → that event `refused`. It is shown and
counted, and that is fine.

**507** → `full`, the calendar stopped.

**Other 400/415** → that event `refused`, logged by index and code, never its text, and counted.

**5xx other than 503, network, timeout** → that event's backoff.

### D4.5 - Where the password goes: unchanged

- Only to the account's confirmed `hosts`.
- A LAN account only to its own host and port, pinned.
- Never https → http.
- An adopted href on another host is `host_changed`.

A write never learns a new host.

### D4.6 - The marked calendar's fetch keeps more

For a source some phone writes to, the REPORT's resources are also read one by one for the uids in
`kid_caldav_events` that are `ok` or wait:
- Each resource is matched to its uid by the VEVENT's `UID`, not by comparing hrefs: hrefs come back percent-encoded
  in other ways (QA N5). The server reads its `getetag` and parses that resource alone.
- **A differing ETag with a differing hash** is a parent's edit → a hand-back (or released). A differing ETag with an
  equal hash just stores the ETag.
- **A uid missing** from a REPORT that covers its stored dates is confirmed (D2.6), then handed back.

An unchanged ctag skips all this, as the fetch does. The feed set is built exactly as today.

The REPORT spans −60 d .. +401 d. A parent's edit to a kid event further ahead (the set has no forward horizon, D1.3)
is seen at the kid's next change of it (a 412) or when it comes within 400 days. A parent's delete of such an event
reaches the phone once it comes within the window: the REPORT misses it, the confirming GET answers 404, and a
hand-back delete goes. That is slow but loses nothing.

This is acceptable: such events are rare. A per-resource PROPFIND for each far event at every fetch would cost more
than it gives. `calendar.md` gets one line on it: a parent's change to an event more than about 13 months ahead
reaches the phone late.

### D4.7 - Only writable calendars are offered (QA L3)

Test's Depth 1 PROPFIND (`PROPFIND_LIST`) also asks for `current-user-privilege-set`, and `calendars_json` rows gain
`writable`:
- `true` with (`bind` and `write-content`), or `write`, or `all` - a new resource needs `bind` on the collection
  (RFC 3744);
- `false` without;
- `null` when the server didn't say. Such a calendar is offered, and the first PUT's 403 answers the question.

The account's next Test fills it. A calendar the last Test no longer lists (`not_listed`) also stops its writes.

### D4.8 - Retention

**What the server holds** while the mark exists: the kid's latest set (titles, places, descriptions, times; replaced
at every POST), and per event its href, ETag, hashes, and a waiting hand-back. A `released` or `past` row holds no
text.

**When it goes:** unmarking, unticking, removing the account or deleting the phone deletes it all at once, by FK
cascade or in the same transaction.

**What was written stays in the calendar** (the user's answer 3). It is the family's calendar.

A backup zip holds the tables as of then, as it holds a feed's set. No event text appears in any log, page, error or
settings-log row. The set and row types have no `Debug`.

### D4.9 - The UID and the href (QA M7, L10)

**The pattern.** Every row's uid must be exactly
`^vibb-[0-9a-f]{8}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`. Anything else is a 400 for the
whole set. The phone applies the same pattern when it picks rows.

**The href is built by string append** onto the collection's path, normalised to end in exactly one `/`. It is never
a `Url::join` of text the phone supplied. An adopted href must be a direct child of the collection after
percent-decoding and normalisation. Tests: `../x`, `%2e%2e/x`, a collection href without the trailing slash.

**Tags are pinned per device.** A set's tags (the 8 hex in each UID) are recorded in `kid_caldav_tags` the first time
a device posts them. A tag another device holds is a 400. The same device may carry several tags: an old tag after a
data clear, the own-package rows of D1.3. With D4.4's GET-first rule, a tampered phone that claims a sibling's UID
gets a hand-back, never a write.

## 6. The PWA

### D5.1 - The choice lives in Kalender's select "Barnets avtaler" (the user's answer 5)

It sits in the Kalender group (`#dagen`), where the kid's calendar is chosen today: one per phone by construction,
and no second control to keep in step.

**The new options** come after the reported calendars: one per CalDAV calendar ticked for this phone whose `writable`
isn't `false`.
- The value is `caldav:<source uid>`.
- The label is "{name} · {server} (skrives av serveren)" / "{name} · {server} (written by the server)"
  (`calendar.kid.caldav_option`). The server name is shown as the account row shows it: "iCloud", else the host.

**The account's page** (`#calendars`) shows on the marked calendar's row "Barnets avtaler skrives hit" / "The kid's
events are written here", with a link to `#dagen` (`calendar.kid_caldav.here`).

**Unticking the marked calendar** (QA L8) ends the mark by the cascade:
- The save's notice says "Barnets avtaler skrives ikke lenger til {name} - det som er skrevet, blir liggende i
  kalenderen" / "The kid's events are no longer written to {name} - what was written stays in the calendar"
  (`?notice=kid_caldav_ended`, back to `#calendars`).
- The select then shows "Min kalender (på telefonen)".
- Removing the account says the same on the Calendar page's `#sources`.

*Alt (not chosen): a switch per calendar on the account's page.*

### D5.2 - The status line

It sits under the select while a CalDAV calendar is the pick (`calendar.kid_caldav.status.*`). It is one line of
counts only - never a title. The counted lines are `t_count` pairs, `.one`/`.other` (QA N2).

| State | nb | en |
|---|---|---|
| No set yet | "Venter på telefonen" | "Waiting for the phone" |
| Written | "Skrevet 15:12 · 12 avtaler" | "Written 15:12 · 12 events" |
| Waiting | "3 venter på å bli skrevet" | "3 waiting to be written" |
| Hand-backs waiting | "1 endring fra kalenderen venter på telefonen" | "1 change from the calendar waiting for the phone" |
| `cut` | "40 avtaler langt frem ble ikke tatt med" | "40 events far ahead weren't included" (D1.3, QA N8) |
| 413 | "For mange eller for lange avtaler" | "Too many or too long events" |

In orange:

| State | nb | en |
|---|---|---|
| `write_refused` | "Kalenderen tar ikke imot avtaler - velg en annen" | "The calendar doesn't take new events - pick another" |
| `full` | "Kalenderen er full" | "The calendar is full" |
| `no_etag` | "Kalenderen støtter ikke trygg skriving" | "The calendar doesn't support safe writing" |
| The account's stop | in its own words, with a link to the account's page ("Passordet ble avvist - test på nytt") | likewise |
| The clock (D2.6) | "Telefonens klokke går feil - ingen sletting" | "The phone's clock is wrong - nothing deleted" |
| `refused` | "2 avtaler ble ikke tatt imot" | "2 events weren't accepted" |
| A rejected set (R-L4) | "Telefonens avtaler ble ikke tatt imot - {why}" | "The phone's events weren't accepted - {why}" |

**{why} for a rejected set.** It names the code of the last 400.
- For a tag pinned by another device - typically the same phone enrolled again as a new device while its old row
  still exists - the line names the other phone and adds: "Slett den gamle telefonen {name} under Enheter" / "Delete
  the old phone {name} under Devices". Deleting it frees the tag by cascade.

**The holds** (D2.6), in orange:
- "Telefonen sendte 14 færre avtaler - de slettes i kalenderen i morgen kl. 15:12", with the button **"Legg dem
  tilbake på telefonen"** (a POST, back to `#dagen`).
- "14 av barnets avtaler er slettet i kalenderen - fjernes fra telefonen i morgen kl. 15:12".

None of this is an alarm on the Devices list: a write that waits doesn't hurt the kid's day.

### D5.3 - An older launcher

Without `calendar_kid_caldav_v1`, the CalDAV options are shown disabled with "Oppdater Vibb One for å skrive barnets
avtaler hit" / "Update Vibb One to write the kid's events here" (`calendar.kid_caldav.older`). A pick saved while the
capability was there stays, and the policy sends `null` until the capability returns.

### D5.4 - The words that change

**Kalender's ⓘ** gains (`calendar.kid_caldav.info`, nb, with en likewise):

> "Velger du en CalDAV-kalender, sender telefonen barnets kommende avtaler til serveren, og serveren skriver dem inn
> i kalenderen hos {server}. Alle kalenderen er delt med, ser dem - også søsken som har den på sin telefon, og alle
> med en offentlig lenke til den. Endrer eller sletter du en av dem i kalenderen, gjør telefonen det samme; en
> påminnelse du legger til, blir borte når barnet endrer avtalen. Avtaler som er over, blir liggende. Velger du noe
> annet, blir avtalene liggende både på telefonen og i kalenderen."

It adds a line on the UID's 8-hex phone tag: a stable pseudonym, visible in the calendar (QA N7).

**Other copy:** `calendar.preview.footer`, `calendar.rules.footer`, and the line in `calendar.sources.*` that says
"the phone's own events stay on the phone" each gain "- unless the kid's events are written to a CalDAV calendar".

Every word goes through `i18n`, in nb and en.

### D5.5 - Saves

- The select autosaves as today: a 303 to `#dagen`, so the page never jumps to the top.
- "Legg dem tilbake på telefonen" is a 303 to `#dagen`.
- A refused pick is the existing 400 `calendar.error.kid_calendar`: not ticked, `writable: false`, not a `caldav`
  source, or gone.
- Both saves go in the settings log, as the route and ids only.
- Routes are behind `require_full_auth`. The hold's button is a new route, so it joins
  `every_calendar_route_needs_a_session`.
- The device endpoint is bearer-only.

## 7. The launcher

### D6.1 - The kid's experience doesn't change

The "+", the form, "Min kalender" and its peach, `kidMayEdit` and offline saving all stay as they are. There is no
"sending…" mark, and no server or brand word anywhere the kid looks (the feed calendar's name is the parent's, as
today).

The only new thing the kid can see: an event the parent changed in the calendar changes on the phone.

No new permission is needed (`READ_CALENDAR`/`WRITE_CALENDAR` are self-granted already), and nothing needs adb.

### D6.2 - Pieces

**Pure `day/KidCaldav.kt`:**
- `kidCaldavPolicy`: the policy's object; missing or malformed = off, nothing sent.
- `canonical` and `kidHash` (D2.7).
- `kidSnapshot(rows, ownPackage, phoneTag, nowMs)`: D1.3's rows, the pattern, the limits with `cut`/`cut_from`, the
  rev.
- `backOps(back, rows)` (D2.3): update, insert with the UID, delete, or ignore - and the ids to acknowledge.
- `skipSet(rows, postedUnion)` (D3.2).

**Glue: `calendar/KidCaldavSync.syncIfNeeded(context, api, policy.calendar)`** runs in `MdmSyncWorker`, right before
`CalendarFeeds.refreshIfNeeded`, and never throws. It reads, posts when D1.2 says so, applies the hand-back, and
keeps `applied` and the posted union in prefs.

**`CalendarStore` stays the provider's only writer.** It gains `kidEventsBlocking` (the read) and
`applyKidBackBlocking` (the guarded writes, each re-checking `ownedByVibb` and the calendar, live rows only).
`updateKidEventBlocking` now writes `ALL_DAY = 0` and `EVENT_TIMEZONE` explicitly, so a handed-back all-day event can
be changed by the kid's form (QA L6). The form puts a title or place into the update only when the kid changed that
field (R-L5).

**Other changes:**
- `MdmApi.kidEvents(body)`.
- `CalendarFeeds`: the skip set before `feedPlan`, `CAL_SYNC6`, and `feedFetch`'s new reason without `If-None-Match`.
- The form's save, change and delete call `requestSync` while marked.
- The capability `calendar_kid_caldav_v1`.

### D6.3 - The policy keeps the kid writing locally

While a CalDAV calendar is marked, no `writable_for_kid` row is sent (D4.1). `kidCalendarOf` therefore picks "Min
kalender" and `ensureKidCalendar` makes it; `kidCalendarOf` itself is untouched.

The feed calendar is `CAL_ACCESS_READ`. Pointing `writable_for_kid` at it would leave the kid with no "+".

## 8. The contract

**The file:** `server/testdata/calendar_kid_events.json`, byte-identical in `launcher/app/src/test/resources/`, added
in one commit, first.

**What it holds:**
- `policy` - a `kid_caldav` object.
- `now`, `own_package`, `phone_tag`.
- `provider` - the "Min kalender" rows the phone reads, including rows that must be left out: a foreign UID, a row
  outside the pattern, a recurring row, an ended row, a `DELETED=1` row. It also has an own-package row with an older
  tag, a non-ASCII title, and an empty place.
- `snapshot` - the exact POST body for them, with `from`, `cut`, `ended` and `applied`. A second variant passes the
  limit: `cut`, `cut_from` as the earliest start left out, and `cut_uids`. An ended row of `provider` (ended within
  60 days) appears in `ended`.
- `canonical` - input rows with their exact canonical form and hash: a 250-code-point title, a 3000-character
  description with CRLF and a C0 control, double spaces, emoji with ZWJ, a `DURATION`, an all-day row without
  `DTEND`, a floating time with a `tz`.
- `answers`:
  - `accepted` with an upsert and a delete hand-back, and an all-day upsert;
  - an acknowledgement clearing a hand-back; a stale id that clears nothing and re-sends the current one;
  - a held set (the old `accepted`);
  - `not_marked`, `limited`, `too_big`;
  - `bad_set`, and `tag_pinned`.
- The `id`s in `answers` follow R-M2's rule: SHA-256 over uid + version hash. Both sides recompute them.
- `back_provider` - the provider values each hand-back writes.

**The server's test**, `calendar_kid_events::tests::the_contract`:
- posts `snapshot` against the stateful fake CalDAV (§10);
- checks each PUT's body against `server/testdata/calendar_kid_events/<uid>.ics` (server-only);
- produces every `answers` row;
- hashes every `canonical` row to its hex.

**The launcher's test**, `KidCaldavTest`:
- encodes `provider` to exactly `snapshot`;
- hashes `canonical`;
- maps each hand-back to exactly `back_provider` and its acknowledgement;
- decodes `policy` and the answers.

Both tests compare the two copies.

**Also in that commit:**
- the explicit path lists of both workflows (`launcher.yml`, `server-ci.yml`);
- root `CLAUDE.md`'s contract paragraph;
- `intake_limits` keeping `snapshot`'s keys.

## 9. Privacy and security

### The kid's coming events now leave the phone

Only while the parent has picked a CalDAV calendar for them, and only the ones still to come.

**What leaves:** the title, the place, a parent-added description, the times, and the UID. The UID carries the
8-hex phone tag: a hash, never `ANDROID_ID`.

**Where it goes:**
- To the server, which keeps the latest set (D4.8).
- On to the CalDAV host (Apple, for iCloud), under the parent's account.
- From there, to everyone the calendar is shared with, and to anyone with its public link if it has one.

**The copy changes:** design 69's "no event ever leaves the phone" becomes "the kid's coming events leave the phone
only to the calendar the parent picked". This goes in the PWA's copy (D5.4), in `docs/notes/server/calendar.md`'s
first paragraph, and in `docs/notes/launcher/dagen-and-calendar.md`.

### The password stays on the server

It stays sealed there. The phone never sees it, the account or the host. The policy carries a feed uid and counts,
nothing more.

### No insecure fallback

- No write without `If-Match` or `If-None-Match`, and no write without a strong ETag.
- No write over content not read first.
- No write to an unconfirmed host, to a LAN host outside its pin, or over plain http to a public host.
- No write that follows a redirect.
- A 403 stops the calendar or the event. It is never retried elsewhere, and never read as the account's stop.
- A lost key is `key_lost` ("log in again").
- TLS as every server fetch: the system roots, plus `server/deploy/distrusted-roots.txt` in the image.

### Abuse by a tampered phone

**The endpoint's limits:** a bearer endpoint, one POST per 10 s, 300 rows and 512 KB, the UID pattern, whole-set
validation, and the tag pinned per device (D4.9).

**What the server does with a set:**
- It deletes and updates only resources it wrote or read for this phone (rows keyed by device).
- It creates only at `<collection>/<uid>.ics`, conditionally.
- It adopts an existing resource only after reading it, and then the calendar's version wins.

**What a phone can't do:** name an href, overwrite or delete a parent's or a sibling's event, or write to a calendar
not marked for it.

## 10. Tests (lean)

### Server

**The fake.** `calendar_kid_events::tests` and `tests::caldav` use a **stateful fake CalDAV** (QA L12). It is a map
of resources with ETags that:
- honours `If-Match`/`If-None-Match`;
- answers PROPFIND and the REPORT from the map;
- answers `no-uid-conflict` and other preconditions on demand;
- has a switch that rewrites bodies after a PUT, as iCloud does, with or without returning an ETag;
- can be told to throttle (429/503).

`CannedFetch` stays for the rest.

**Cases:**
- the contract;
- create, update and delete each carry their condition, with no request without one;
- H1's three paths: release then a stale snapshot gives no PUT; unmark, the parent edits, re-pick gives a hand-back,
  not a PUT; a 412 after a timed-out update with equal content gives no hand-back;
- H2's: a kid's edit after an acknowledged hand-back is written once and not handed back again; an edit while a
  hand-back waits is not written; a hand-back is re-sent until acknowledged;
- M1's rewrite switch gives no false edit;
- R-M2's: two parent edits, then the first acknowledged, gives no write and the second re-sent; an acknowledged
  delete drops the row;
- the canonical rows;
- `cut` deletes nothing at or after `cut_from` and nothing in `cut_uids`; a near event moved into the cut region is
  not deleted; with `cut_uids` `null` nothing is deleted;
- an ended event is never deleted and becomes `past`; **an event the kid moved into the past (in `ended`) is not
  deleted and becomes `past`** (R-M1);
- R-L1's: a lost-answer create followed by a kid's edit is written, not handed back; a `past` event moved back into
  the future with an unchanged calendar is written, not reverted; a re-pick after an unmark hands back;
- `pending` stays above 0 until the fetch after the write has stored the set (R-L2);
- a rejected set and a pinned tag show their status line (R-L4);
- a 404 create whose GET answers 404 is a `status` error (RN3);
- a `from` a day off freezes deletes;
- a malformed row is a 400 for the whole set;
- the kid's mass delete is held 24 h by time, with "Legg dem tilbake"; the calendar's mass delete is held too;
- a missing resource is confirmed, and a moved one is handed back;
- 403 is read by its body (`no-uid-conflict` adopted via GET, a precondition refuses the event, `need-privileges`
  gives `write_refused` **with the account still `ok`**); 401 gives `login_refused`; 507; Nextcloud's 400;
- a redirect on a write is not followed;
- `no_etag` and a weak ETag;
- `../`, `%2e%2e` and a collection without a slash; a tag pinned by another device;
- the per-account claim and backoff with `Retry-After`; 60 a minute;
- the nudge only at `pending` 0;
- `writable` from the privilege set; `not_listed`;
- the select, the untick's notice, the older launcher;
- the cascades; the status lines; the 429/413/409/400 answers;
- `policy_json_keys_snapshot`;
- the writer's folding and escaping round trip;
- no text in the settings log.

### Launcher

- `KidCaldavTest`: the contract, a full read only, the limits, the rev stable across order.
- `backOps`: update, insert with the UID, delete, ignore, `DELETED=1` ignored, the acknowledged ids.
- `FeedPlanTest` + `skipSet`:
  - own events skipped;
  - the posted union hides a just-deleted event from a feed that still holds it;
  - a released event is shown, and its document fetched again without `If-None-Match`;
  - a newly skipped event is deleted locally with no GET;
  - DAVx5-era own events are shown.
- `KidEventTest`: the form's change of an all-day event; a time-only change keeps a parent's 150-character title
  untouched (R-L5).
- `KidCaldavTest`: `ended` and `cut_uids`; `cut_from` = the earliest start left out when bytes drop a nearer row.
- `PolicyResponseCompatTest`.

### Emulator §6z

In `docs/testing/emulator.md`. Radicale runs on the host, as in design 72 §10, with a read-only collection set up by
its rights file. Each step and what it should show:

| Step | Expected |
|---|---|
| Pick the calendar | Only the kid's coming events appear in Radicale (`curl`); none of the past ones. |
| The kid adds "Kino" in Kalender | It is in Radicale and shows once on the phone. |
| Edit it by `curl PUT` | The phone shows the new time. |
| Make it recurring by `curl` | It leaves "Min kalender" and shows read-only from the feed. |
| Delete it by `curl` | It is gone on the phone. |
| The kid deletes an event | It is gone in Radicale, and no ghost appears on the phone. |
| Airplane mode: two added, one deleted, back online | Radicale matches. |
| An event far ahead (2028) | It is written. |
| Pick the read-only collection | "tar ikke imot". |
| Untick the marked calendar | The notice shows, and the select falls back. |

**A real account** - the user's iCloud is preferred, else Fastmail or Nextcloud. It checks [verify]: the UTC body is
accepted, the ETag on a PUT, the privilege set at Test, and that iCloud's body rewrite is not read as an edit.

## 11. Steps and sizes

One branch. The code QA may review it in two halves, writes then the hand-back, but they merge together: one-way
alone is not what the user chose.

| # | Step | Size |
|---|---|---|
| 1 | **The contract** (both directories, the workflows' paths, root `CLAUDE.md`) | **S** |
| 2 | **Server, the writes** - 0117; `calendar_kid_events.rs` (the endpoint, whole-set checks, the diff with tombstones, `past`, `cut`, `from`, the kid's hold, the claimed job with per-account backoff); `caldav.rs`'s request shape, no redirects, the 403 body, GET-first; `ics::write_event`; the UID pattern and the tag pins; `writable` at Test; the select, the untick notice, the status lines, the account page's line, the words, the policy key | **M+** |
| 3 | **Server, the hand-back** - D4.6's per-resource read, the ETag+hash compare, the confirm, the calendar's hold, the acknowledgements, the "Legg dem tilbake" route | **M** |
| 4 | **Launcher** - `KidCaldav.kt` (canonical, snapshot, `backOps`, the skip set), the glue and the store's methods, the all-day update, `CAL_SYNC6` and the fetch reason, `requestSync`, the capability | **M** |
| 5 | **§6z** on the emulator, plus one real account | **S** |

Total: server **L**, launcher **M** (the review's sizes, agreed).

## 12. The user's answers (2026-11-02)

1. **Two-way: the parent's edits come back to the phone, and the calendar wins a clash** ("det må jo kunne
   synkroniseres!"). The hand-back is acknowledged (`applied`): no write while one waits, and no expiry (D2.3).
2. **No past events. Every coming event of the kid's, however far ahead** ("uansett hvor langt frem"). D1.3 has no
   forward horizon; the row and byte limits are a safety limit reported as `cut`, never a reason to delete. An event
   that passes into the past stays on the calendar as it is, never deleted (D2.6).
3. **What was written stays on the calendar when writing stops** (D4.8, D5.1).
4. **Titles exactly as the kid wrote them**, with no name prefix.
5. **The choice sits in Kalender's "Barnets avtaler" select**, with a line on the account's page and a notice when
   the marked calendar is unticked (D5.1).

## 13. What the implementer's brief must carry

**QA H1:** GET before any adopt; the hash compare on every 412 (`attempted_hash`); tombstones for released events;
`past` for ended ones.

**QA H2:** `applied` in the POST; no write while a hand-back waits; no expiry. In the contract commit, first.

**The revision check:**
- R-M1: `ended` - a uid in it is never deleted.
- R-M2: the hand-back id = SHA-256 over uid + version hash. Only an ack of the current id clears the wait, and it sets
  `sent_hash` to the acknowledged version.
- R-L1: the base compare on the create path.
- R-L2: `pending` counts a write until the fetch after it.
- R-L3: `cut_uids`.

**QA M1–M8:**
- M1: ETag **and** hash; a GET with the body after a PUT without an ETag.
- M2: D2.7's canonical form, pinned in the contract.
- M3: `cut`, `cut_from` and `from`; the whole-set 400; `ownedByVibb` selection.
- M4: the 24 h hold by time, with "Legg dem tilbake".
- M5: the posted union.
- M6: the 403 body read.
- M7: the UID pattern and the append-only href.
- M8: events with attendees released.

**Must-haves:**
- Every write conditional, with a strong ETag.
- 403 never the account's `login_refused`.
- D4.5's hosts and pinning unchanged.
- No write follows a redirect.
- Per-account backoff; the single-flight job; the nudge only at `pending` 0.

**The contract and its wiring:**
- `calendar_kid_events.json` in both directories in one commit, with both workflows' explicit path lists and root
  `CLAUDE.md`.
- `policy_json_keys_snapshot` + `PolicyResponseCompatTest`.
- The capability `calendar_kid_caldav_v1`.
- Migration 0117, renumbered at merge if needed.

**Privacy in the code:** no event text in any log, page, error or settings-log row; no `Debug` on the set or its rows.

**Words and docs:**
- i18n nb + en with `t_count` pairs; user-facing "Kalender", not "Dagen".
- The select's save and the hold's button are a 303 to `#dagen`.
- The notes (`calendar.md`, `dagen-and-calendar.md`) and PLAN's queue are updated in the merge commit.

## 14. The design QA, finding by finding

| Finding | Where it went |
|---|---|
| H1 | D4.4 (GET-first, `attempted_hash`), D2.4 (tombstones), D2.6 (`past`), D4.9 |
| H2 | D2.3 (`applied`, no write while waiting, no expiry), D2.5.1 |
| M1 | D2.2, D2.5.2, D4.6 |
| M2 | D2.7, §8's `canonical` |
| M3 | D1.3, D1.7, D2.6 |
| M4 | D2.6, D5.2 |
| M5 | D3.2 |
| M6 | D4.4 |
| M7 | D4.9 |
| M8 | D2.4, D4.4 |
| L1 | D4.2, D4.3 |
| L2 | D4.3, D4.1 |
| L3 | D4.7 |
| L4 | D4.2 |
| L5 | D4.2 |
| L6 | D2.3, D6.2 |
| L7 | D1.6 |
| L8 | D5.1 |
| L9 | D2.6 |
| L10 | D4.9 |
| L11 | D2.7, D4.3, D4.4 |
| L12 | §10 |
| L13 | §11 |
| N1 | D5.2 |
| N2 | D5.2 |
| N3 | D3.2 |
| N4 | D4.4 |
| N5 | D4.6 |
| N6 | D1.5 |
| N7 | D5.4, §9 |
| N8 | D1.3, D5.2 |

### The revision check (head 89aca7a6)

| Finding | Where it went |
|---|---|
| R-M1 | D1.3 (`ended`), D2.6, §8, §10 |
| R-M2 | D2.3 (the id, the matching ack, `sent_hash` = the acknowledged version, an acked delete drops the row), §8, §10 |
| R-L1 | D2.1 (a re-pick reverts), D4.1 (hashes kept on `past`/`released`), D4.4 (`attempted_hash` and the base on create) |
| R-L2 | D3.2, D4.2 |
| R-L3 | D1.3 (`cut_uids`), D2.6 |
| R-L4 | D4.1 (`rejected`), D5.2 |
| R-L5 | D2.7, D6.2, §10 |
| `cut_from` earliest | D1.3, D2.6 (the stored start) |
| RN1 | D2.6 |
| RN2 | D2.6 |
| RN3 | D4.4 |
| RN4 | D4.1 |
| The 400-day window | D4.6 (and a line for `calendar.md`) |

Rejected: none. One finding is narrowed. M3 says the server never deletes while `cut > 0`; instead it deletes
nothing that starts at or after `cut_from`. A kid's delete of a near event still goes through while far events are
cut.
