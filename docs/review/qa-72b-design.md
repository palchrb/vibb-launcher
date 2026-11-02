# QA 72b (design): CalDAV write-back of the kid's own Dagen events

Note c3a20af8 (`docs/design/72b-caldav-write-back.md`, branch `design-72b`); against `main` at dac8a52a, design 72
§6-§7, `docs/notes/server/calendar.md`, `docs/notes/launcher/dagen-and-calendar.md`, RFC 4791/5545/7232. Read-only; no
build run.

**Verdict: revise first. Every claim about today's code holds, but two protocol holes need fixing before the brief,
and then a short revision check.** The shape is right: a whole-set snapshot instead of an outbox, the server as the
only writer with the password, every write conditional, the skip set instead of a per-phone feed, `write_refused`
kept apart from `login_refused`, and the mark on `device_calendar_feeds` as 0110 reserved. Two things break it.
First, a uid the server has forgotten or never saw is "adopted" by writing the phone's version over the calendar's,
which undoes the parent's edits and series (H1). Second, the hand-back has no acknowledgement and no rule for which
side wins, so a kid's later edit is either clobbered again and again or written over the parent's (H2). Most
Mediums share one theme: an event can be missing from the set, or its ETag can change, for reasons that aren't a
delete or a parent's edit.
**2 High, 8 Medium, 13 Low, 8 Nits.**

## Claims checked against the code (all hold)

`Dav::ask` (`caldav.rs:413-520`) has these behaviours:
- It takes one hop at a time with `follow: false`, at most 5 hops (`MAX_HOPS`, `:60`).
- Each hop goes through `calendar_feeds::route`. A LAN account is pinned to its origin host and port (`:425-430`).
- With `Hosts::Fixed`, an unknown host is `host_changed` before any request (`:434-438`).
- Any 401/403 with the password is `login_refused` (`:481-483`), for every method. That is why D4.3 must read the
  answers per method.
- It always sends `Content-Type: application/xml` and a `Depth` header (`:461-470`), so D4.3's "request shape" is
  needed.
- It re-sends the same method and body on any 3xx.

Other server code:
- `fetch_with` (`:734-806`) asks for `getetag` in the REPORT (`report_body`, `:327-340`; `PAST_DAYS` 60 and
  `AHEAD_DAYS` 400 + 1, `ics.rs:45,47`). It concatenates only the `calendar-data` bodies and drops each response's
  href and ETag (`:776-792`), so D4.6 really is new work.
- `PROPFIND_LIST` (`:320-321`) has no `current-user-privilege-set`.
- `refresh_source` returns `key_lost` (`:877`) and `not_listed` (`:874`). A `login_refused` or `host_changed` result
  stops the account, guarded by `password_nonce` (`:895-912`).
- `SourceResponse` carries `etag` (`music_net.rs:140-153`). `Gated` keeps one request in flight with 200 ms spacing and
  fair FIFO (`:609-633`), so one write at a time never starves a Storytel or Spotify token ask by more than one
  request.
- `music_sweep::backoff` is at `:492`. `device_api::known_fields` is at `device_api.rs:1153`: it drops a mis-shaped
  key silently and clips text by chars (`audit::clip`, `audit.rs:263`). `intake_limits` is a test module.
- `CannedFetch::answer_method` exists (`tests/music.rs:134`) and is stateless.
- `saved()` is a 303 to `#dagen` (`handlers/calendar.rs:100-103`).
- `save_dagen` accepts `off | phone | key` (`:654-726`) and clears `writable_for_kid` in one transaction. The 400 is
  `calendar.error.kid_calendar` (`:679,683`). `kid_choices` and `writable_for_kid` are at `:339-387`.
- The 0110 comment reserves the flag for `device_calendar_feeds` (`0110:18-20`).
- `device_calendar_feeds` has PK `(device_id, source_id)` with cascades and the orphan trigger (`0105:46-61`).
  Unticking is a plain `DELETE` (`caldav.rs:1364`), so the planned FK cascade works. `foreign_keys(true)` is set in
  `main.rs:1677`.
- `idx_device_calendars_kid` and `calendar_kid_adds` are in 0103 (`:18,40`).
- 0116 is design 74's (`74-passkeys.md:264`). No branch or worktree has a 0116 or 0117 yet.

Launcher:
- `insertKidEventBlocking` (`CalendarStore.kt:508-529`) writes `UID_2445` = `vibb-<tag>-<lowercase UUID>`,
  `CUSTOM_APP_PACKAGE`, and the phone's `EVENT_TIMEZONE`.
- `updateKidEventBlocking` and `deleteKidEventBlocking` (`:540-570`) use the plain URI, so a delete leaves a
  `DELETED=1` row (`eventBlocking` checks it, `:350-354`).
- `KID_EVENT_TITLE_MAX`/`PLACE_MAX` = 100 (`KidEvent.kt:16-17`). `TitleRules.clean` cuts by code point, which matches
  Rust's chars.
- `kidCalendarOf` (`KidEvent.kt:37-43`) falls back to `vibbLocal` when `kidCalendarKey` is null, so D6.3 holds and
  `kidCalendarOf` stays untouched.
- `ownedByVibb` (`DayModel.kt:124-128`) accepts the own tag *or* a `vibb-` UID with this app's package.
- `CAL_SYNC1`..`5` are in use (`CalendarStore.kt:715-719`, `CalendarFeeds.kt:27-29`), so `CAL_SYNC6` is free.
- `feedFetch` is at `FeedPlan.kt:143-147`. `CalendarFeeds.refreshIfNeeded` runs inside the fresh-policy block
  (`MdmSyncWorker.kt:169`), so D1.5a's hook site exists.
- `CommandListenerService.requestSync` is at `:361`. Repeated requests collapse into one more run
  (`SyncRunner`/`SyncCoalescer`).
- The clock is locked (`DISALLOW_CONFIG_DATE_TIME` + auto time, `AppEnforcer.kt:1177-1188`).

## High

**H1. A uid the server forgot or never saw overwrites the calendar ("adopt" = PUT the phone's version).**

Where: D4.4 ("412 on a create → PROPFIND its `getetag`, treat it as written by us with an unknown version, the phone's
version then `PUT` with that `If-Match`"), D2.4 ("the server forgets the uid"), D2.6 (the horizon forgets), D4.8/§12.3
(unmark deletes the rows), D1.4 (re-mark).

What: every path where the server has no row for a uid that the phone still sends ends in a blind overwrite:
- A parent makes the event recurring. D2.4 hands back a delete and forgets the uid. If the phone hasn't applied that
  yet (offline, a 429, a killed sync), its next snapshot still has the uid. The server reads it as new: create, 412,
  adopt. The parent's series is replaced by the kid's single event.
- Unmark, the parent edits, re-mark (D1.4's case). Every parent edit made in between is overwritten.
- A PUT that timed out after the server stored it. The retry gets 412 and the PUT runs again, which is harmless. But an
  *update* that timed out and then gets 412 on its retry is read as a parent's edit (D4.4), and the kid's newer change
  is dropped.

Why: this contradicts D2.1 ("the calendar wins") and is data loss in the parent's calendar, through ordinary
sequences. §9's "a tampered phone could claim a sibling's UID at a re-mark's adopt" is the same hole.

Fix:
- **Never PUT over a resource whose content the server hasn't read.** On a 412 for a create (and on
  `no-uid-conflict`), `GET` the href and parse it, then:
  - the canonical hash equals the phone's version → store its ETag, done (a lost answer, a re-mark of an unchanged
    event);
  - recurring, or with ATTENDEE/ORGANIZER (M8) → released;
  - otherwise → hand it back as an upsert (the calendar wins), `remote_hash` = what was read.
- The same on a 412 for an update: compare the GET to the version *last attempted*. Equal means our own lost answer.
- **Tombstones, not forgetting.** A released uid stays as `state = 'released'` and is never written again, until it
  has been absent from the phone's sets for 60 days. A horizon-aged uid keeps a row too (or the server horizon of M3
  covers it).
- A sibling's UID claimed by a tampered phone then becomes a hand-back to that phone, never a write.
- Add tests: release then the stale snapshot gives no PUT; unmark, edit, re-mark gives a hand-back, not a PUT; 412
  after a timed-out update with equal content gives no hand-back.

**H2. The hand-back has no acknowledgement and no rule for which side wins.**

Where: D2.3 ("the phone needs no acknowledgement field: its next snapshot shows the event as the calendar has it, and
the server clears the hand-back"; "unapplied after 7 days is dropped"), D2.5 rule 1.

What, in three cases:
- (a) The phone applies hand-back B, then the kid changes that event before the next POST (the form's `requestSync`
  makes this the normal case). The next snapshot differs from the calendar, so the server can't tell "unapplied" from
  "applied, then edited". It re-sends B and the kid's edit is overwritten. This repeats at every edit for up to 7 days.
- (b) For the same snapshot, D2.5 rule 1 says *write*: the version differs from the last one sent and from the
  calendar. So the parent's edit is overwritten instead. An implementer can read the note either way. Both readings
  are wrong somewhere.
- (c) A phone that stays offline 7+ days (a holiday) gets its hand-back dropped. The phone keeps the old version
  forever, because "Min kalender" is skipped from the feed. The parent and the kid see different events with no
  signal.

Why: this is the core of the recommended two-way answer to question 1. As written it either loops or breaks D2.1.

Fix:
- The POST carries `applied: ["<back id>", ...]`: the ids applied since the last 200 (kept in prefs, ≤ 300, cleared on
  a 200).
- Server: a uid with a waiting hand-back is never written. On the ack, `sent_hash := remote_hash`, so the kid's next
  change is an ordinary write.
- Re-send an un-acked hand-back. Drop the 7-day rule: a back goes only when the uid leaves the set or is released.
- Contract: `snapshot` gains `applied`, and `answers` gains a row where an ack clears the hand-back.
- Tests: a kid's edit after an applied hand-back is written once and not re-handed; an edit with an un-acked back is
  not written.

## Medium

**M1. A changed ETag is not a parent's edit (D2.2b, D4.6).**

What: "an ETag that differs from the stored one is a parent's edit". iCloud and Fastmail rewrite bodies, sometimes
asynchronously after the PUT (`X-APPLE-*`, re-serialised `DTSTAMP`). Some servers return an ETag on a PUT whose stored
body differs (contrary to RFC 4791 §5.3.4). D2.5.2's PROPFIND is also racy: a parent's edit between the PUT and the
PROPFIND is taken as our own.

Why: a false "parent's edit" hands back our own content. By H2's rule that then drops any kid change made meanwhile,
which is exactly the [verify] at §10's end ("iCloud's body rewrite not seen as an edit").

Fix:
- A differing ETag counts as a parent's edit only when the resource's canonical hash differs from `remote_hash`.
  Otherwise just store the new ETag.
- After a PUT without an ETag, read the resource with its body (a `GET` with its `ETag` header, or a PROPFIND with
  `calendar-data`) and compare its hash to what was written.

**M2. The canonical form isn't pinned, and a mismatch truncates the parent's text.**

Where: D2.5 ("canonical fields"), D2.3 (cuts 100/100/2000), D1.3 (the snapshot's cuts), D4.1 (hashes "over D2.5's
canonical fields").

What:
- If `remote_hash` is taken over the parent's uncut text, the next snapshot after the hand-back (cut on the server,
  and possibly through `TitleRules.clean` on the phone, which folds whitespace and drops code points) differs from
  both hashes. D2.5.1 then PUTs the cut version over the parent's long description. That is a one-time, silent loss of
  the parent's text.
- The same happens with `\r\n` against `\n` in a description, an all-day DTEND left out against an exclusive next
  day, `DURATION` against `DTEND`, and a floating time read in another zone.

Fix:
- Define one canonical function. Raw provider values on the phone (no `TitleRules.clean` in the snapshot). Cut by code
  point (100/100/2000). `\r\n` → `\n`. All-day as `YYYYMMDD` start + exclusive end. A missing DTEND or a DURATION
  resolved to an end. Floating resolved in the snapshot's `tz`.
- Hash *after* the cuts, on both sides.
- Pin it in the contract: a 140-character title, a 3000-character description with CRLF, double spaces, emoji/ZWJ,
  DURATION, all-day with no DTEND. Both `the_contract` and `KidCaldavTest` hash every row to the same 16 hex.

**M3. An event can be missing from the set without the kid deleting it.**

What: D1.1 reads any missing uid as a delete. D1.3 and the code give four other reasons a row can be missing:
- the cap (301 events: "the farthest from today dropped first" become DELETEs; a kid tapping "+" is a real kid);
- the phone's horizon against the server's (D2.6's "59 days" uses the server's clock, the phone filters by its own: a
  clock wrong at a boot without network time drops recent past events, which become DELETEs);
- a row that doesn't read (`known_fields` drops a mis-shaped key silently, `device_api.rs:1153-1182`; a row without
  `start` that is skipped becomes a DELETE);
- the tag (D1.3 takes only `vibb-<tag>-`, but `phoneTag` falls back to a prefs id when `ANDROID_ID` is blank,
  `CalendarStore.kt:575-592`, and a launcher data clear then changes the tag: every existing event leaves the set,
  while `ownedByVibb` still calls them the kid's).

Fix:
- The snapshot carries `cut` (rows dropped) and `from` (its horizon). The server never deletes while `cut > 0`, and
  deletes only uids whose stored end is ≥ max(`from`, server now − 59 d), refusing a `from` more than a day from its
  own.
- Any row that doesn't read is a 400 for the whole set (never a partial set). Validate required keys explicitly, not
  through `known_fields`' drop.
- D1.3 selects by `ownedByVibb` (own tag *or* own package), as `kidMayEdit` does.

**M4. The mass-delete hold ends in about 30 minutes and then deletes for good.**

Where: D2.6 ("accepted at the third such set in a row or 24 h after the first").

What: the phone re-POSTs at every sync while `accepted ≠ rev` (D1.2). "The third set in a row" is about two sync
intervals, not design 72's two hours. A "Min kalender" wiped by a Calendar Storage clear is re-made empty by
`ensureKidCalendar`, which counts as a valid full read. Half an hour later the kid's events are deleted from the
family calendar, the one copy that could have restored them. It is also unclear whether a held set counts as
`accepted`. If it does, the phone stops sending and only the 24 h path is left.

Fix:
- Hold by time only: 24 h, and `accepted` stays the old rev while held.
- The status line offers "Legg dem tilbake på telefonen" (hand back the held uids as upserts from `remote_hash`'s
  resource) next to the plain wait. Default after 24 h: accept, as today.
- Fix N1's wording with it.

**M5. The skip set as written doesn't cover the case it exists for.**

Where: D3.2 ("the UIDs of the last set posted while the policy's `pending > 0`").

What: an event the kid just deleted is by definition *not* in the last set posted, so it isn't skipped. And `pending`
comes from the policy fetched *before* this sync's POST (it is 0 then), while the server's DELETE runs after the
answer (D4.2). In the same sync, `CalendarFeeds` sees the uid leave the skip set, GETs the old document again (D3.2's
"extra reason") and shows the deleted event back as a read-only parent's event. It stays until the nudge-driven sync
after the DELETE, or for good if the DELETE is stuck (`write_refused`, `login_refused`).

Fix: keep "posted UIDs" in prefs as the **union** of every uid posted since the server last showed `accepted == the
last posted rev` and `pending == 0` (or have the 200 carry `pending` and the uids still to delete). Test: a delete,
then a feed that still holds it, shows nothing.

**M6. A CalDAV precondition 403 stops the whole calendar.**

Where: D4.4 ("403 otherwise → `write_refused` for this calendar alone ... every write of the phone stops").

What: RFC 4791 §5.3.2.1 signals `valid-calendar-data`, `valid-calendar-object-resource`, `supported-calendar-component`,
`max-resource-size`, `min-date-time`/`max-date-time` and `max-attendees-per-instance` as **403 with a precondition
element**. One event dated past a server's `max-date-time`, or a body iCloud dislikes, would stop every write for that
phone until the parent picks again.

Fix: parse the 403 body. `C:no-uid-conflict` → D4.4's adopt (H1's GET-first rule). Any other `C:` precondition →
that event `refused`. No body, or `D:need-privileges` → `write_refused`. Test each.

**M7. The UID isn't validated on the server, and the href is built from it.**

Where: D1.4 ("the UID is URL-safe"), §9 ("a phone can't name an href").

What: the regex `vibb-[0-9a-f]{8}-<uuid>` is applied only at adopt. A tampered phone (the security reviews' model:
"anything holding its token") sends `uid = "../work/x"`. `Url::join` resolves it into another collection of the same
account, the parent's work calendar, and writes there. A collection href without a trailing `/` makes `join` replace
its last segment.

Fix:
- 400 for any row whose uid isn't exactly `^vibb-[0-9a-f]{8}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`.
- Build the href by string append onto the collection path normalised to end in one `/`, never by `join` of
  phone-supplied text.
- An adopted `no-uid-conflict` href must be a direct child of the collection after normalisation.
- Test `../` and `%2e%2e`.

**M8. An event the parent invited people to sends cancellations at the kid's next change.**

Where: D2.4 ("attendees ... are lost at the kid's next change - the server writes the whole resource").

What: on iCloud, Fastmail and Nextcloud, a PUT that drops ATTENDEEs from an event with an ORGANIZER triggers implicit
scheduling (RFC 6638): the invitees get a CANCEL by e-mail. "Grandma invited to the kid's Kino" turns into
"Grandma's invitation cancelled" because the kid moved it 30 minutes.

Fix: treat ATTENDEE/ORGANIZER like RRULE. Release the event (a hand-back delete plus H1's tombstone), so it lives on
as the parent's. Or take D2.4's merge alternative for these. Losing an added VALARM is acceptable; say so in the ⓘ.

## Low

- **L1. Backoff and rate limits per account, not per event.** D4.4 backs off on 5xx per event, alone. A 429/503 from
  iCloud or Fastmail is a throttle for the account, so the job moves on to the next event at 60 a minute. Back off the
  account on 429/503, honouring `Retry-After`. Never follow a 3xx on a write: today's `ask` re-sends PUT/DELETE on
  301/302/303 (`caldav.rs:487-519`), and a write redirected anywhere should be a `status` error.
- **L2. Servers with no ETag or a weak one.** D4.4 doesn't say what happens when `getetag` is missing from the REPORT
  and the PROPFIND, or is `W/"…"`. `If-Match` uses strong comparison (RFC 7232 §3.1), so a weak tag gives 412 every
  time. Define this: no strong ETag means the calendar is stopped as `no_etag` (orange, "Kalenderen støtter ikke trygg
  skriving"), never an unconditional write. Store the ETag verbatim, at most `MAX_VALIDATOR` (500), and refuse one
  with CR/LF.
- **L3. `writable` needs `bind`.** D4.7 counts `write-content` as writable, but a new resource needs `DAV:bind` on the
  collection (RFC 3744). Require (`bind` and `write-content`), `write`, or `all`. A `not_listed` calendar (the Test no
  longer lists it) also stops its writes, the same as its fetch.
- **L4. One job at a time per account.** The POST's task, the scheduler pass and a sibling's POST on the same account
  can race. That gives a double create (412) and a 60-a-minute budget counted twice. Use a single-flight claim per
  account (a `claimed_at` column as Test's `claim`, `caldav.rs:952`). Each write reads the uid's *latest*
  `events_json` row just before it goes.
- **L5. Nudge and fetch storms.** D4.2 nudges "when `back` or `pending` changed", and `pending` changes at every write.
  A first pick of 300 events means about 5 minutes of back-to-back full syncs (the coalescer keeps one more run
  queued). Nudge only on `pending` reaching 0, `back` going above 0, or a new stop. The "fetch at once" after each job
  should be at most once a minute per source: a kid adding 5 events gives 5 REPORTs of the whole family calendar.
- **L6. Provider details of the hand-back.** An upsert by UID must ignore `DELETED=1` rows, which plain deletes leave
  behind (`CalendarStore.kt:350-354`, `:559-570`), or it "updates" an invisible row. The snapshot read and the skip set
  filter them the same way. A handed-back all-day event can't be changed by the kid's form: `updateKidEventBlocking`
  (`:540-556`) writes local times without `ALL_DAY=0`/`EVENT_TIMEZONE` handling, and the provider refuses an all-day
  row with non-midnight times. Either set `ALL_DAY=0` and the zone in the update, or open such an event read-only.
- **L7. "`null` after a restore" (D1.6) has no hook.** `handlers/backups.rs` swaps the database file wholesale, and no
  post-restore step exists. With H1 and M1 a restore heals itself: stale ETags give 412, then a GET, then equal
  content, so the ETag is learned again. Drop the claim, or name the step. M4's hold may fire once after a restore
  (events deleted after the backup). That is fine.
- **L8. Unticking the marked calendar silently ends the mark.** It happens through the cascade (`caldav.rs:1364`).
  The account page's switch row already gets "Barnets avtaler skrives hit". The untick's save notice should say "the
  kid's events stop being written here", and Dagen's select falls back to "phone".
- **L9. Calendar-side mass deletes have no hold.** A parent's "delete all" or a server-side wipe empties the kid's
  day through hand-back deletes, each one confirmed by PROPFIND, so they are real. Apply D2.6's 5-and-half hold to
  hand-back deletes too.
- **L10. Pin the tag per device.** The server can't check the tag, but it can pin the first tag a device posts and
  refuse (400) a set whose tag another device row has pinned. That costs nothing and closes §9's sibling-UID caveat
  together with H1. Allow M3's own-package rows with an old tag of the *same* device.
- **L11. The writer and the answers.**
  - Drop C0 controls other than LF/TAB before `write_event` (RFC 5545 TEXT forbids them), or a pasted title becomes a
    400 and `refused`.
  - `SEQUENCE` = max(stored, the parent's) + 1.
  - A 412 followed by a GET 404 is a delete: with `If-Match` a missing resource answers 412, not 404, so D4.4's "404
    on update" is the rare case.
  - Floating times in a parent's edit: resolve them in the snapshot's `tz` (M2).
- **L12. Tests need a stateful fake CalDAV.** `CannedFetch` (`tests/music.rs:70`) answers by method and URL. The
  write, refetch and hand-back cycle needs a fake that holds a resource map, honours `If-Match`/`If-None-Match` and
  answers the REPORT from the map, with a switch that rewrites bodies (iCloud). Add the cases from H1, H2 and M1-M8,
  and the stale snapshot after a release.
- **L13. Sizes.** Server is **L**, not M. The diff with three hashes, the acks, the job with claim and backoff, 412,
  adopt and the precondition reading, the per-resource REPORT read and PROPFIND confirm, the hold, `write_event`, the
  privilege set, the select, the status lines, the account line and the copy together are more than design 72 step 1
  (M). Launcher is **M** (the snapshot, the canonical form, the hand-back with all-day, the skip set with tombstones,
  `CAL_SYNC6`, the fetch reason). Consider two merges on the branch: one-way writes with tombstones, then the
  hand-back.

## Nits

N1. D2.6's text "venter til i morgen" doesn't fit "the third set in a row". With M4 it becomes true.
N2. The counted lines ("3 venter…", "2 avtaler ble ikke tatt imot", "Skrevet 15:12 · 12 avtaler") are `t_count`
pairs (`.one`/`.other`, `i18n.rs:207-214`).
N3. `feedFetch`'s new reason (the skip set changed) must return `FeedFetch(null)`, with no `If-None-Match`, or the
endpoint answers 304 and nothing comes back.
N4. Nextcloud (sabre) answers a UID at another href with a 400 "already exists", not 403 `no-uid-conflict`. It lands
as `refused`, which is fine; say so.
N5. REPORT hrefs are paths, often percent-encoded differently from the href the server built. Match by resolved,
decoded path, or by the VEVENT's UID, before D2.6's confirm, or every kid event costs a PROPFIND at each REPORT.
N6. A second save within 10 s gets a 429 and waits a whole sync. One `requestSync` after `Retry-After` is enough.
N7. §9's privacy text: a shared iCloud calendar can also be published by a public link. The ⓘ should say "alle
kalenderen er delt med". The 8-hex phone tag is now visible to everyone who sees the calendar: a stable pseudonym,
harmless, worth one line.
N8. D1.1 says ~90 KB (300 × 300 B) and D1.2 says 60 KB. With parent-added descriptions (2000 characters) a set can
pass 256 KB and get 413 for good. Give the 413 a status line ("for mange/lange avtaler") or cut descriptions in the
snapshot first.

## The open questions (recommendation first)

1. **Two-way, the calendar wins: agree, with H2's ack.** Without it the recommended answer loops. If the user wants
   the smaller first step, one-way with H1's tombstones is a clean subset (L13's two merges).
2. **Write the events already in "Min kalender" (60 days back): agree.** At 60 a minute and one in flight it is a
   few minutes. Nudge only at the end (L5).
3. **What was written stays: agree.** It is the only safe default. A wrong click must never wipe the family calendar.
   The PWA line in D5.4 says so.
4. **No name prefix: agree.** A prefix breaks M2's canonical compare and needs stripping on the way back. A calendar
   per kid is tidier.
5. **The choice in Dagen's select: agree.** One control, one per phone by construction. Add L8's notice on the
   account page's untick.

## What the implementer's brief must carry

- H1 (GET before any adopt, the hash compare on 412, tombstones for released and aged-out uids) and H2 (`applied` in
  the POST, no write while a hand-back waits, no 7-day drop). Both go in the contract commit, first.
- M1 (ETag *and* hash), M2 (one canonical form in the contract), M3 (`cut`, `from`, whole-set 400, `ownedByVibb`
  selection), M4 (24 h hold, restore offer), M5 (posted-uid union), M6 (the 403 body read), M7 (the uid regex,
  append-only href), M8 (attendees released).
- Today's must-haves: every write conditional; 403 is never the account's `login_refused`; D4.5's hosts and pinning
  unchanged; no write follows a redirect (L1); per-account backoff; a single-flight job (L4).
- The contract `calendar_kid_events.json` in both directories in one commit, with both workflows' explicit path lists
  (`launcher.yml:79`, `server-ci.yml:57`) and root `CLAUDE.md`. Also `policy_json_keys_snapshot` and
  `PolicyResponseCompatTest`, capability `calendar_kid_caldav_v1`, and migration 0117 (renumber after 0116 at merge).
- No event text in any log, page, error or settings-log row; no `Debug` on the set or its rows.
- i18n nb + en with `t_count` pairs (N2). The select's save is a 303 to `#dagen`. Notes (`calendar.md`,
  `dagen-and-calendar.md`) and PLAN's queue updated in the merge commit.
- §6z on the emulator against a stateful Radicale, plus one real account (iCloud preferred: the body rewrite of M1
  and the ETag on PUT).

## Revision check (head 89aca7a6)

**Verdict: mergeable as a design once two one-paragraph Mediums are added (R-M1, R-M2). No further design round is
needed; the code QA can check both.** Every High and Medium above is resolved in substance, not just listed. The
revision opened one new delete path, which comes from the user's "no past events" answer (R-M1), and left the
hand-back id undefined (R-M2). **New findings: 0 High, 2 Medium, 5 Low, 4 Nits.**

### The original findings, checked

| Finding | Resolved? | How |
|---|---|---|
| H1 | Yes | D4.4's "never PUT over unread content" with GET-first on every 412, `attempted_hash` for lost answers, `released` tombstones (D2.4), `past` rows (D2.6), D4.9's tag pins. A stale set after a release writes nothing; a re-pick hands back. R-L1 has two corner cases. |
| H2 | Yes, with R-M2 | `applied` in the POST, no write while a hand-back waits (D2.5.1 now says so first), re-sent until acknowledged, no expiry. |
| M1 | Yes | Content hash, not ETag (D2.2, D4.6). After a PUT without an ETag, a GET with its body (D2.5.2). |
| M2 | Yes | D2.7: raw values, CRLF and C0 handling, cut by code point after which the hash is taken, the times rules. Pinned row by row in §8's `canonical`. |
| M3 | Yes, narrowed (judged below) | `from` with a clock freeze, `cut`/`cut_from`, a whole-set 400 with explicit key checks, `ownedByVibb` selection. |
| M4 | Yes | 24 h by time, `accepted` stays old, "Legg dem tilbake". |
| M5 | Yes | The posted union, reset only when `accepted` = the last rev and `pending` = 0; the 200 carries `pending`. R-L2 has a timing caveat. |
| M6 | Yes | The 403 body decides; `need-privileges` or no body → `write_refused`, never `login_refused`. |
| M7 | Yes | Exact pattern, a 400 for the whole set, append-only href, adopted href a direct child. |
| M8 | Yes | ATTENDEE/ORGANIZER released, as recurring is. |

The Lows and Nits are all taken (§14). The sizes now match the review.

### The M3 narrowing and the 400-day window

**The M3 narrowing ("nothing starting at or after `cut_from` is deleted") is sound,** with two conditions:
- `cut_from` must be the **earliest start among all rows left out**. D1.3 says "the start of the first left out". If
  rows are dropped by end, or for bytes, "first" isn't the earliest, and a dropped row could start before `cut_from`.
- The test uses the **stored** start.

What remains is a kid moving a near event into the cut region (R-L3), which needs 300+ coming events. A kid's delete
of a near event still works while far ones are cut, which is the point of the narrowing. Agree.

**The 400-day fetch window: acceptable, agree.**
- A kid's event past +401 d is written and kept.
- A parent's edit to it is seen at the kid's next change (a 412, and the calendar wins) or when it comes within the
  window.
- A parent's *delete* of it reaches the phone only when it comes within the window. The REPORT then misses it, the
  confirming GET answers 404, and a hand-back delete goes. This is slow but not lossy.
- D4.6's "a REPORT that covers its stored dates" keeps far events from reading as missing, which is the important
  part.
- Put one line in `calendar.md` saying a parent's change to an event more than about 13 months ahead reaches the phone
  late. A per-resource PROPFIND for far events isn't worth it.

### Medium

**R-M1. A kid's edit that moves an event into the past deletes it from the calendar.**

Where: D1.3 (the set holds only `end > now`), D2.6 (a missing uid is deleted when its *stored* row ends after
max(`from`, now)).

What: the kid moves "Kino" from tomorrow to yesterday, a correction or a log of what happened. The phone leaves it
out because it has ended. The server still has tomorrow's end, so every delete test passes, and the event is DELETEd
from the family calendar while it stays on the phone. Before the user's answer 2 the 60 days back covered this. Now
nothing does.

Fix: the set carries `ended: [uid, …]`: the uids only, no text, of this phone's rows in "Min kalender" that ended
within the last 60 days, at most 300. The server never deletes a uid in `ended`; it marks the row `past` and stores
no new version. Add a contract row and a test ("moved into the past → not deleted, `past`"). The privacy line doesn't
change, because no text leaves.

**R-M2. The hand-back's `id` and what an acknowledgement clears are undefined.**

Where: D2.3 ("`id`: 16 hex"; "on the acknowledgement, `sent_hash := remote_hash`").

What: the parent edits twice. B1 is handed back, then D4.6 sees the second edit and `remote_hash` moves to B2's
version. If the implementation keeps B1's id (or re-stamps `back_json` under it), the phone's acknowledgement of B1
clears the wait and sets `sent_hash := remote_hash` (B2). The phone's next set carries B1's content, which differs
from both hashes, so it is read as the kid's change and written over the parent's second edit. That is exactly the
overwrite H2 closed.

Fix, three sentences:
- `id` = 16 hex of SHA-256 over uid + the handed-back version's hash, so a new parent edit is a new id.
- An `applied` id clears the wait only when it equals the current `back_id`. Unknown or stale ids are ignored.
- On a match, `sent_hash := that hash` (the acknowledged version, not whatever `remote_hash` is by then). An
  acknowledged `delete` drops the row.

Test: two parent edits, then the first acknowledged, gives no write and the second re-sent.

### Low

- **R-L1. GET-first loses the three-way base in two places.**
  - (a) On the *create* path a lost-answer create followed by a kid edit (v1 stored, the retry carries v2) gets a GET
    of v1 that differs from v2, so v1 is handed back and the kid's v2 is reverted. Compare `attempted_hash` on the
    create path too: equal means our own write, so store the ETag and do an ordinary update with `If-Match`.
  - (b) A `past` row the kid moves back into the future goes through "GET-first" as a create. The calendar's
    (unchanged) version differs from the phone's, so the kid's move is reverted, although the parent changed nothing.
    Keep `sent_hash`/`remote_hash` on `past` rows (hashes aren't text, which D4.8 allows). When a stored
    `remote_hash` exists and equals the GET's hash, the parent didn't change it, so PUT the phone's version with the
    GET's ETag. The calendar wins only when the GET differs from the base, or when there is no base (a re-pick after
    an unmark). Say in D2.1 that a re-pick reverts edits the kid made while unmarked: an accepted consequence of
    deleting the rows.
- **R-L2. The posted union can reset before the feed has caught up.** `pending` is computed from rows and reaches 0
  when the DELETE answers. The post-write fetch can wait up to a minute (D4.2), and any sync in between resets the
  union, GETs the old document and shows the ghost again until the next sync. Count a write in `pending` until the
  fetch after it has stored the new set. That is what the nudge waits for already.
- **R-L3. Cut mode: a near event moved into the cut region is deleted.** Its stored start is before `cut_from` and it
  is missing from the set. Since only uids are needed, the set can carry `cut_uids` (the left-out uids, ≤ 2000;
  beyond that, no deletes at all for that set). Or accept it and say so. It takes 300+ coming events.
- **R-L4. A rejected set is silent.** A tag pinned by another device row gets a 400, for example a launcher
  re-enrolled as a new device while its old row still exists: same `ANDROID_ID`, same tag. D5.2 has no line for it or
  for any `bad_set`. Add "Telefonens avtaler ble ikke tatt imot - {why}". For the tag case, name the other phone and
  say "slett den gamle telefonen" (its cascade frees the tag).
- **R-L5. The kid's form truncates a parent's long title.** D2.7 allows 200; the form cuts to 100 and runs
  `TitleRules.clean`. A kid who only changes the time PUTs the parent's title cut and whitespace-folded. Clean and cut
  only a title the kid actually changed; an untouched title is saved as it is.

### Nits

RN1. D2.6 "becomes `past`": state the test, "missing and stored end ≤ max(`from`, now), or in `ended` (R-M1)".
RN2. `start_at`/`end_at` are updated from every remote read (a hand-back, a GET), or a parent's move confuses
`past` and the delete test.
RN3. A create answered 404 whose GET also answers 404 (the collection is gone or a server quirk): call it that
event's `status` error, not an adopt.
RN4. `kid_caldav_tags` survives an unmark by design. Say it is deleted with the device (it is, by cascade) and that
it holds no text.
