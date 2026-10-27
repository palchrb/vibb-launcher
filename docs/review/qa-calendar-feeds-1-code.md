# QA calendar-feeds-1 (code): calendar links - a pasted ICS link, fetched by the server, written by the phone

Branch `calendar-feeds-1` at 2a1cd898 (f6b2fbf3 contract + server, 3537f945 launcher, b1dea016 docs, 5ec48921 the merge
of main 03c50cd2 = S-i18n slice 1, 2a1cd898 the counted words), read as `git diff 03c50cd2...calendar-feeds-1` in full,
against `docs/design/72-calendar-feeds.md` (revised after `qa-72-design.md`, whose §13 is the brief) and RFC 5545, and
the provider's own validation rules (AOSP `CalendarProvider2.validateEventData`: a recurring event DTSTART + DURATION
and no DTEND, an exception DTSTART + DTEND + ORIGINAL_SYNC_ID + ORIGINAL_INSTANCE_TIME, a null column counts as absent).
Own worktree; no emulator (§6y's checks are the emulator's: the provider taking a LOCAL calendar and the sync columns
from a plain app, the EXDATE forms, UNTIL, the override linking, 2000 events timed, a kill mid-apply).

**Verdict: mergeable after a short fix round.** The shape is the note's and holds up: the link is sealed and only its
host ever reaches a page, a log, the settings log or the report (tests and a grep both say so); the fetch goes through
the shared client's rules with the redirects followed one hop at a time, never https → http, a LAN link pinned to its
judged addresses and only on its own host and port; the bearer endpoint answers only the phone that has the link; the
5 MB cap holds before any decoding; the folds come out of the bytes before the charset; the zone maths is right on
every case I traced (Oslo's 25 October and 28 March, EXDATE in `Z` on a TZID series, an override moved with a `Z`, a
date `UNTIL` on a timed series, Windows names, all-day end exclusive); the phone writes exactly what the provider's
rules ask (DURATION and no DTEND on a series, `P<n>D` all-day, UTC midnights, the exception's four columns, ORIGINAL_ID
by back reference, every write and delete through the sync-adapter URI), the rev is written last, and the orphan trigger
takes a source only when no phone names it - a deleted phone included. One High: the parser panics on an absurd
`DURATION`/`TRIGGER`, and the scheduler is one task, so one such line in any pasted feed ends every link's hourly fetch
until the server restarts, silently. **1 High, 1 Medium, 8 Low, 8 nits.**

## Setup and what holds

- **Runs.** Server: `cargo test --locked` **1450 passed**, `cargo fmt --check` clean, `cargo clippy --all-targets
  --locked` no warnings. Launcher: `assembleDebug testDebugUnitTest -PwarningsAsErrors=true` **1897 passed** (the one
  kapt `RELEASE_11` javac line as always). Both copies of `calendar_feeds.json` byte-identical (`cmp`); the `.ics`
  fixtures are CRLF on disk (`-text` in `server/.gitattributes` holds, `file` shows them so, the itslearning fold is
  `\xC3\r\n \xA5`). Migration order test is `migrations_73_to_105_in_order` with 103/104/105.
- **The contract is produced, not copied.** `calendar_feeds::tests::the_contract` parses `contract.ics` with `ics::parse`
  and `document()` and compares the result with the file's `feed` (and `keys`, the uids and revs); the launcher's
  `CalendarFeedsTest` decodes `feed`, runs `seriesRows` in the contract's zone and compares the rows with `provider`
  exactly (keys, values and reminders), checks the key against `calendarKey("LOCAL", "Vibb One", "vibb-feed-<uid>")`
  and the policy's and report's rows; `intake_limits::the_field_tables_keep_every_contract_key` runs `report.feeds`
  through `sanitize_report`. `policy_json_keys_snapshot` pins `feeds: []`; `PolicyResponseCompatTest` reads it (and a
  bad element leaves `feedsComplete` false). The i18n tests keep both catalogs' key sets equal (48 new `calendar.links.*`
  keys each) and every key the template names present; `t_count` carries the four counted lines.
- **Security, checked.** No URL in any log line (`host = %host` at the add, `source_id` and `code` on a failure), no
  `Debug` on `SourceRequest`, the `FetchError::Refused` text dropped for the code `private_address`; the page shows `host`
  only (`the_page_shows_a_link_and_waits_for_the_phone` greps for the token and the path); the settings log names the
  route and ids (`fetch_now_waits_five_minutes_and_is_not_a_save` greps it). `normalize` refuses userinfo, any scheme
  but http(s), a missing host and > 2000 characters (before and after `Url`'s own normalization), maps `webcal(s)` to
  https. Plain http only where the name resolves to the LAN or the tailnet, judged at the add and at **every** fetch
  (`route`: a LAN link that now resolves publicly goes public for good, an http one `insecure`); public requests use
  `Reach::Public`, whose resolver drops private answers at connect time, so a name can't rebind between the judgement
  and the fetch; LAN requests are `pinned_to` the judged addresses. `get` follows at most 5 hops with `follow: false`,
  refuses http after https or from a public route, refuses any other scheme, and for a LAN route any other host or
  port. `too_big` is the shared client's `truncated` at 5 MB *decoded* (gzip bombs bounded there), checked before
  `ics::parse`. `device_feed` joins `device_calendar_feeds` on the bearer's device and 404s a uid that isn't one
  (`valid_uid`), another phone's, or an unknown one (tested, and mutation f). The sealed URL and the set stay in a
  backup, as the note says.
- **Garbage, by hand.** A 70 KB line, a BOM mid-line, a fold before the first line (both `" BEGIN"` and `"\r\n BEGIN"`),
  `TZID=""`, `TZID="` unterminated, `TZID="x"y`, a parameter without `=`, a trailing `,`, `COUNT` and `UNTIL` together
  (passed through; Android's `EventRecurrence` tolerates both), a garbage `UNTIL` (the rule dropped, `bad`), a
  lowercase `until=...z` (upper-cased, kept), a `RECURRENCE-ID` without a master (standalone, uid `a#garbage`), a year
  9999 series, a DTSTART in the 28 March gap, `END` without `BEGIN`, nesting past 4, `SEQUENCE:abc`, empty EXDATE
  values, a UTF-16 body: none panics. `content_line`'s every slice is at an ASCII byte or at `len`, so no char-boundary
  panic either. Only durations panic - H1.
- **Time maths, traced.** `in_zone` converts one instant per value into the series' zone (`instant` takes the earliest
  of an ambiguous hour, the hour after for a gap); the itslearning `Z` rule goes out as `tz: "UTC"` with a `Z` UNTIL
  and the phone writes `EVENT_TIMEZONE=UTC`, so its instances move an hour locally across 25 October - the note's
  "faithful"; a `TZID=Europe/Oslo` rule stays 08:15. `rrule`: a known zone's local `UNTIL` → UTC, a date `UNTIL` → the
  zone's 23:59:59 as UTC, an all-day series' → a date, a floating one left local for the phone (which makes it UTC in
  the zone it writes, `ruleWithUtcUntil`, end of day for a date). EXDATE/RDATE sorted, deduplicated, in the series'
  zone; the phone turns them into `yyyyMMdd'T'HHmmss'Z'` (all-day `yyyyMMdd`) - the forms `RecurrenceSet` parses. The
  horizon keeps a series whole unless its UNTIL is over 60 days back (mutation c); the caps drop the farthest singles
  first, series count as today (mutation e). `W. Europe Standard Time` → `Europe/Berlin`, every table row parses.
- **The phone's writes.** `seriesRows`: a series gets DTSTART, `DURATION` (`PT<n>S` / `P<n>D`), `DTEND` null, RRULE,
  RDATE, EXDATE; a single DTSTART + DTEND; an override DTSTART + DTEND + `ORIGINAL_SYNC_ID` + `ORIGINAL_INSTANCE_TIME`
  (UTC ms; all-day the UTC midnight) + `ORIGINAL_ALL_DAY` and the series' `EVENT_TIMEZONE`, cancelled → `STATUS_CANCELED`;
  `insertFeedSeriesBlocking` adds `ORIGINAL_ID` by `withValueBackReference` to the master's op and a Reminders row per
  alarm (`METHOD_ALERT`, `EVENT_ID` by back reference). Every insert, update and delete in the section carries
  `CALLER_IS_SYNCADAPTER` + `ACCOUNT_NAME` + `ACCOUNT_TYPE=LOCAL` (`asFeedAdapter`, pinned by `FeedEventsTest`'s source
  scan), so a delete is a real delete and the sync columns are writable; `feedRowsBlocking` reads only the link
  calendar's rows (`CALENDAR_ID = ?`), so a delete by id never reaches another calendar; nothing else in `CalendarStore`
  deletes a calendar (`KidEventTest` counts the kid section's four writes; the kid's calendar is `vibb-kid-calendar`,
  `vibbLocal` by NAME, so "Min kalender" is still made). `CAL_ACCESS_READ` (200) keeps every link calendar out of
  `kidCalendarOf` (≥ 500, `FeedPlanTest`) and the PWA's kid picker (LOCAL refused; the page test greps the option).
  **Rev last:** `markFeedAppliedBlocking` runs only after `runPass` returned with the provider still there
  (`writeRev`, mutation l); a kill between a delete chunk and its insert leaves the master missing and the rev old, and
  the next sync's plan inserts it - idempotent. Batches: deletes first in chunks of 200, series packed whole into
  batches of ≤ 200 operations, one bigger series alone (mutation k).
- **Retention and the trigger.** `calendar_sources_orphaned` fires on the cascade of `DELETE FROM devices` too
  (`remove_and_a_deleted_phone_delete_the_source`; mutation g); a Remove while a fetch is in flight writes nothing
  (`UPDATE ... WHERE id = ? AND uid = ?`, the test). `device_calendars` rows stay by key and come back with the same
  link (tested). Dagen's kid pick (qa-l7 M1) is unchanged by this branch.
- **The departures, judged.** Three commits, not four - fine (the contract is in f6b2fbf3 with the server, first).
  `insecure` as its own code - right (the parent's fix differs from `private_address`: use https). Start + end on the
  wire, no DURATION, the phone computing `PT<n>S`/`P<n>D` - right, and the simpler contract; the override's end falls
  back to the master's length on the server already. The report row `{id, applied_rev, refused, unknown_zone}` -
  fine, pinned on both sides. `CAL_SYNC4`/`CAL_SYNC5` (unknown-zone count, the zone floating series were written in) -
  fine, documented in `launcher/CLAUDE.md`. The synthesised itslearning fold (QA N7 asked for the real bytes): the
  bytes-first rule is pinned by the `\xC3\r\n \xA5` row and a Latin-1 row anyway; capturing a real line stays a §6y
  item, not a blocker.

## High

**H1. `ics::parse` panics on a long `DURATION` or alarm `TRIGGER`, and that ends the hourly fetch of every link.**
`ics.rs:468-506` `parse_duration` builds `TimeDelta::seconds(seconds)` after checked arithmetic on the number, but
chrono's `seconds` itself panics past `i64::MAX / 1000` (`DURATION:PT9223372036854775807S`, `TRIGGER:-PT9223372036854775807S`
- chrono 0.4.45 `lib.rs:717`); `Moment::plus` (`:421-426`) adds the delta with `NaiveDateTime + TimeDelta` /
`NaiveDate + TimeDelta`, which panic on overflow (`DURATION:PT9000000000000000S` is within chrono's bound and overflows
the year; the all-day branch of `end_of` (`:717-719`) does the same through `TimeDelta::days`). Reproduced with four
bodies (a throwaway test, not committed). Scenario: a pasted feed (any link is untrusted bytes; a generator bug or a
hostile host) carries one such line; `calendar_feeds::run` (`calendar_feeds.rs:562-585`) is **one** spawned task
that calls `refresh` inline, so the panic ends the loop - no more fetches for any link until the server restarts, and
nothing says so: the page keeps "Fetched 14:05 · 182 events" with an ageing time and no error, the phones keep the old
set. The add check of such a link drops the parent's connection (axum's handler panic) instead of a 400. **Fix:**
`TimeDelta::try_seconds`, `checked_add_signed` / `checked_add_days` in `Moment::plus` and `end_of`, and a cap - a
duration over, say, 400 days (or any `None`) is `bad` and the event takes the fallback (zero / one day); the four
bodies as `ics::tests` rows. And make the scheduler survive a panic anyway (`tokio::spawn` each `refresh` and log the
join error, or `catch_unwind` around the parse): a parser over untrusted bytes is where the next one lands.

## Medium

**M1. A sibling added to a `key_lost` source gets a dead link with no way out but removing it on every phone.**
`calendar_feeds::add` (`calendar_feeds.rs:634-648`) attaches a phone to an existing source "no fetch" without looking
at its `error` or `url_key_fp`. After a key change the parent follows the page's own advice ("The server's key has
changed - add the link again") on phone A: the link is a source already (same uid), so it is attached as is, still
sealed with the old key, still `key_lost`, and keeps failing hourly; the source goes only when no phone names it, so the
parent must remove it from every sibling first and then add it again - nothing on the page says so. **Fix:** when the
existing source's `url_key_fp` isn't the current key's fingerprint (or `error = 'key_lost'`), re-seal the pasted
link onto that row (one UPDATE of `url_sealed`/`url_nonce`/`url_key_fp`, clearing the error and `next_at = now`) before
attaching - the parent pasted the same normalized link, so the uid proves it; a test: rotate `music_key`, add again on
a sibling, the next `refresh` fetches.

## Low

- **L1. The report's counts are uncapped and the uid filter untested.** `calendar.rs:493` keeps `refused` /
  `unknown_zone` as any non-negative `i64` (the page then says "The phone couldn't save 9223372036854775807 events" for
  whoever holds the token - security review M7's "stored within limits"); cap them (2000 is the set's cap). Mutation h
  (the `valid_uid` filter removed) survived: add an intake row with a bad `id`, a negative count and 11 rows.
- **L2. An unknown TZID never takes `X-WR-TIMEZONE`.** `ics.rs:765-771`: `When::Local(_, None, true)` goes floating
  (`tz: null`, the phone's zone) while only a TZID-less time takes the feed's zone. The note's §2.3 reads 3 then 4
  (unknown → floating → `X-WR-TIMEZONE` when Olson); a hand-made feed with `X-WR-TIMEZONE:Europe/Oslo` and a display-name
  TZID would show in the phone's zone instead of the feed's. Either order is defensible - say which in the note, or
  take the feed's zone for both.
- **L3. In a floating series a `Z` value is read as local.** `in_zone` (`ics.rs:463`) maps `(Floating, When::Utc(t))`
  to `Moment::At(t)`: an EXDATE, RECURRENCE-ID or DTEND written in UTC on a TZID-less (or unknown-TZID) master lands an
  offset off, so the exclusion misses and the override detaches. Rare (a floating master with UTC exclusions), and the
  server has no zone to convert into; the honest answer is `bad` + dropping the value, or converting into
  `X-WR-TIMEZONE` when there is one (L2).
- **L4. A refused delete chunk is "gone".** `FeedPlan.kt:110`: `delete(chunk) != OK` ends the pass with the rev old,
  so a chunk the provider *refuses* (not one it can't take) makes every sync GET the set again and retry - the loop
  QA M2 closed for inserts. Practically unreachable (a delete by `_ID` through the adapter URI has nothing to refuse),
  so: treat a refused delete like a refused insert (count it, go on), or say in the KDoc why it can't happen.
- **L5. Two address rules have no test.** `route`'s "a LAN link that now resolves publicly goes public for good"
  (`calendar_feeds.rs:171-182`, `lan` rewritten by `refresh`) and `get`'s LAN rule "a redirect only to the same host
  and port" (`:226-231`) are untested; `the_fetch_cycle` covers https → http and a private answer to a public name. Two
  rows in `the_fetch_cycle` (the DNS of a LAN source moved to a public address; a LAN 302 to another host). Also
  `lan_http` is judged at the add only (`:665-668`): a tailnet http link whose name later resolves to the LAN never
  gets the warning (and the reverse keeps it) - a nit unless `refresh` re-judges it with `lan`.
- **L6. The feed apply runs before the command dispatch in the sync.** `MdmSyncWorker.kt:145` calls
  `CalendarFeeds.refreshIfNeeded` (a GET of up to 2 MB and up to 2000 provider inserts on the store's worker) before
  `dispatchPendingCommand` (`:168`) and the status report (`:262`): a `lock`, `locate` or `wipe` queued by the parent
  waits behind a first apply. The note put the hook after `policyChanged()` (fine for the report's `applied_rev`); the
  cheap move is after the dispatch, before the report.
- **L7. Docs.** `docs/design/72-calendar-feeds.md:3` still says "Not built" (step 1 is); `docs/notes/server/
  calendar.md:107` says "0104 is the unmerged `pwa-i18n-1`'s" - it is merged (main 03c50cd2 = S-i18n slice 1, in this
  branch); and the launcher's `CLAUDE.md` section could name §6y's EXDATE switch (`EXDATE_COLUMN`) as the one flip the
  emulator may ask for (it does, in the bullet - fine) but not that the itslearning fold's real bytes are still to be
  captured (QA N7).
- **L8. `text()` keeps format characters.** `ics.rs:287-296` drops `is_control()` only, so a BOM mid-line (U+FEFF, a
  real generator habit when files are concatenated), zero-width joiners and bidi marks reach the title; the phone's
  `TitleRules.clean` drops bidi/control too but the rule matching runs on what the server sent. Drop the same marks
  `calendar::is_dropped` names (U+FEFF, U+200B, the bidi marks, U+2060-2064) - `text()` keeps `\n` for descriptions,
  so reuse `is_dropped` (made `pub(crate)`), not `clean`.

## Nits

1. `when_text` (`handlers/calendar.rs:531`) prints "27.10. 14:05" in both languages; English would read "Oct 27, 14:05"
   - the date form belongs in the catalog, as the clock does.
2. The name inputs say `maxlength="200"` while `NAME_MAX` is 60 (`device_calendar.html`); harmless, the server cuts.
3. `ensureFeedCalendarBlocking` sets `OWNER_ACCOUNT = "Vibb One"` (`CalendarStore.kt:697`) - the same value the kid's
   calendar uses (`LOCAL_ACCOUNT`), which the note calls "the kid's `OWNER_ACCOUNT`"; one constant would say so.
4. `seriesRows` (`FeedEvents.kt:199`) picks `ZoneOffset.UTC` for all-day and `wireMillis` ignores `zone` for all-day
   anyway (mutation o is equivalent); keep one of the two.
5. `sanitize_feeds` takes the first 10 rows after validation; a phone with 11 valid rows (impossible today) loses the
   last silently - fine, say so in the doc comment.
6. `refresh` on a DB error leaves `next_at` in the past, so `run` retries that source every minute and logs each time;
   acceptable (a DB that fails is louder elsewhere), but a `next_at` bump on the error path would quiet it.
7. `add` re-renders a *refused* link as typed (`:674`) with its token in the HTML - the note's choice; the input has
   `autocomplete="off"`, so it stays out of the browser's form history. Fine; noted for the record.
8. `calendar_feeds::run` sleeps 30 s, then 60 s between passes with 4 s between sources: at 50 due sources a pass is
   ~3.5 min, fine; the constant `SPACING` could be in the doc comment of `run`.

## Mutations (each alone, restored; `git status` clean before writing)

| | Mutation | Result |
|-|-|-|
| a | `in_zone`: a TZID value not converted into the series' zone | caught: `ics::the_series_cases` |
| b | `rrule`: a known zone's local `UNTIL` left local | caught: `the_series_cases`, `calendar_feeds::the_contract` |
| c | the horizon: a series with an old UNTIL kept | caught: `cancelled_and_the_horizon` |
| d | `unfold`: no tab fold | caught: `folds_are_taken_out_of_the_bytes_before_decoding` |
| e | the 2000 cap off by one | caught: `the_caps` |
| f | `device_feed` answers any phone | caught: `tests::calendar_feeds::a_link_reaches_the_policy_and_the_phone` |
| g | the trigger without its `WHEN` (a source deleted while a sibling has it) | caught: `remove_and_a_deleted_phone_delete_the_source` |
| h | `sanitize_feeds` without the uid check | **survived** (L1) |
| i | an https → http redirect allowed | caught: `the_fetch_cycle` |
| j | an override's own start not converted into the series' zone | caught: `the_google_feed`, `the_series_cases`, `the_contract` |
| k | `feedBatches` never flushes a batch | caught: `FeedPlanTest` (2) |
| l | `writeRev` always true (rev written after the provider went) | caught: `FeedPlanTest` |
| m | all-day duration `P1D` | caught: `FeedEventsTest` |
| n | a series written with DTEND | caught: `FeedEventsTest`, `CalendarFeedsTest` (the contract) |
| o | all-day in the phone's zone | survived - equivalent (`wireMillis` ignores `zone` for all-day; nit 4) |
| p | `feedsComplete` with a bad element | caught: `FeedPlanTest` |

Fuzz (a throwaway `ics::tests` row, not committed): 17 garbage bodies, 4 panic (H1), the rest parse as described above.

## For the fix round

H1 (the durations, the scheduler surviving a panic), M1 (re-seal on attach), L1's cap and test, L5's two rows, L6's
move, L7's two doc lines; L2-L4 and L8 as the implementer judges (a note line each if not changed). Then §6y on the
emulator: the provider's forms ([verify]: EXDATE, UNTIL, the override linking), the 2000-event apply timed, a kill
mid-apply, and the real itslearning fold captured for the fixture.
