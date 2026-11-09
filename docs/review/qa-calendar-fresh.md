# Code QA: calendar-fresh (2026-11-09)

Branch `calendar-fresh`, commits `ed34f649` (server: calendar links fetched every 30 minutes) and `a75abbf4` (launcher:
the glance reminder row's tap reads today afresh; `day/DayRowsCache` with a generation). Reviewed from a detached
checkout.

**Verdict: no C, no H, no M. Ship.** Three L findings (a missing back-off test row, a flatter failure schedule, source-grep wiring tests), six notes.

Counts: C 0, H 0, M 0, L 3, N 6.

Run: `DayRowsCacheTest` (4) and `LockGlanceTest` (37) pass; server `cargo test calendar_feeds` (18, including
`the_fetch_cycle`) passes. Nothing else run, no emulator.

## Intent 3: the generation, and who calls what on which thread

Callers of the cache (`CalendarStore`, `GlanceCalendar`):

| Call | Where | Thread |
|---|---|---|
| `generation()`, `get()`, `put()` | `rowsBlocking`, reached from `dayBlocking`/`todayBlocking` | the `calendar` worker only (`DayActivity`, `GlanceCalendar`, `ReminderPinger` all go through `CalendarStore.run`/`executeOnWorker`, the one single-thread executor) |
| `clear()` | the `changed` Runnable (provider observer, debounced 1 s, and `invalidate()`) | main |
| `forget()` | `GlanceCalendar.load(fresh)` lambda | the worker |

All five methods are `@Synchronized` on the one monitor, the `LinkedHashMap` in access order is only touched inside
them (a `get` mutates it, so it needed the lock, and has it), and `generation`/`days` are never read outside it. The
guard is correct: `readAt` is taken before the provider is asked, `put` compares under the same lock that `clear`
bumps under, so a read that spans a `clear` can't store. The only real race is main's `clear` against the worker's
read, and that is exactly what is closed. A read that began and ended entirely inside the debounce window stores rows
that `clear` then drops: fine. A cache hit returns before any put: fine. The tap's own read takes `readAt` after its
`forget`, so it stores. No deadlock: no call is made while the monitor is held.

`private var generation` next to `fun generation()` compiles (no property getter on a private var); the build ran.

## Intent 2: the refresh at the tap

- Off the main thread: `GlancePanel.tap` calls `calendar.refresh()` after `step(...)`, which is
  `CalendarStore.run { ... }`: `worker.execute`, the provider reads and the `forget` on the worker, only the answer
  posted to main. `tap` has already stepped the stage and set the target, so the keypad (or Android's pad) and the
  unlock never wait for it. `takeTarget` reads `state`/`captured`, not the calendar, so a read still in flight at
  the unlock changes nothing: the target stays `GlanceTarget.Day(row.epochDay)`. `if (!started) return@run` guards the
  answer once the page has stopped.
- The only queueing is behind the one worker: the Day screen's first read after the unlock waits for at most one
  today+tomorrow read (two indexed provider queries, milliseconds). N1.
- The row stays on screen with its old data until the answer comes (`today` is replaced only in the answer), so
  nothing blinks; if the event is gone, the row leaves, and the day still opens.

## Intent 4: the first frame at screen-on

Unchanged. `start()` still calls `load()` (now `load(fresh = false)`, which skips the `forgetDays` line), `boundary` and
the listener call `load()` the same way, the cached `today` is read by `reminderRow()` exactly as before, and
`CalendarStore` differs on that path only by the cache's synchronized `generation()` call (one uncontended monitor
enter per `rowsBlocking`, on the worker). The only new `calendar.refresh()` caller is `tap`, on a row's tap. Nothing
was added to the power-on, start or render paths. `LockGlanceTest` (37) stays green.

## Intent 1: server load and back-off at 30 minutes

- `INTERVAL` 60 to 30 min is used for a new source's first `next_at`, a success, a 304, an unstorable fetch and a
  panicked fetch. CalDAV goes through the same `refresh`, so it follows; its fetch is a Depth-0 `PROPFIND`
  (ctag/sync-token) and one `REPORT` only on a change, so the doubled rate costs a ctag check per calendar, and
  `calendar_kid_events::after_fetch` still runs only on a changed set. A refused password still stops the account
  (`login_refused`), so a 30-minute retry can't lock an Apple ID by repeated 401s.
- `MAX_SOURCES` is 50 server-wide, `SPACING` 4 s, so a pass is at most about 200 s of spacing plus the fetches
  themselves: well inside 30 minutes for a family. The 6 h UID-churn interval is untouched and `backoff(.., interval)`
  takes whichever interval applies, so churn sources still back off up to 6 h.
- Back-off now: 15, 30, 30, ... (was 15, 60, 60, ...), daily after 7 days of failing. See L1 and L2.
- Rows already scheduled keep their `next_at` (up to 60 min ahead) and converge within an hour of the deploy. N2.

## i18n

`calendar.sources.footer` and `calendar.link.footer` changed in both `en.json` and `nb.json` (same key set, 3964 each,
JSON parses). "every half hour" / "hver halvtime" are both idiomatic and agree in meaning; the nb string reads as the
old "hver time" did. The templates (`device_calendar.html`, `device_calendar_link.html`) take them by key, no
hard-coded text. No other string in the server, the templates or the launcher resources says "hour" about calendar
fetching. (The sources footer also covers a CalDAV account and says "every half hour" while a churning link is
fetched every 6 h: pre-existing, N3.)

## Findings

### L1: the back-off cap at 30 minutes is not asserted

`the_fetch_cycle` pins the 304 interval (30) and the first failure (15 min), and the churn interval (6 h). Nothing
pins the new behaviour the commit message names: the second failure now waits 30, not 60. The cap is a one-liner in
`music_sweep::backoff` (shared with the music sweep, so a unit test of `backoff(2, calendar_feeds::INTERVAL)` is the
cheapest), or a second failure in `the_fetch_cycle` with `next - t0 == 30 min`. A later change of `INTERVAL` or of
the shared function would otherwise pass silently.

### L2: the failure schedule is flatter, and the pass can now outrun the interval in theory

A link that keeps failing for a reason other than a refused password (a host that 5xx's or rate-limits) is retried at
30 min after the second failure instead of at 60: about twice as many attempts over the 7 days before the daily rate
(about 330 instead of 170 per source), all through `music_fetch`'s one-request-in-flight gate that the music sweep
shares. That is what the design's "capped at the interval" says, and the user chose the 30; no action beyond knowing
it. Separately, a pass is sequential and each fetch has a 60 s timeout, so 50 sources all timing out would take
about 3200 s, which fitted in 60 min and no longer fits in 30. Not realistic at a family's few links (the cap of 50 is
server-wide), and the next pass simply starts late (`next_at <= now`), nothing breaks.

### L3: the wiring tests are source greps, not behaviour

`DayRowsCacheTest` tests 1 to 3 are real behaviour tests of the pure class and would fail without the generation
guard (`put` returning `false`, the stale value not stored, the LRU order). Test 4 and the new `LockGlanceTest` lines
read `CalendarStore.kt`, `GlancePanel.kt` and `GlanceCalendar.kt` as text: they confirm that `rows.generation()` comes
before `contentResolver.query(`, that `rows.put(day, readAt, out)` exists, that `tap` contains
`if (row is GlanceCalendarRow) calendar.refresh()` and that `load` contains the `forgetDays` line. They would stay
green if the call moved into the wrong branch or `refresh()` stopped being reached. This matches how the rest of
`LockGlanceTest` guards the glance (the calm-glance bans are greps too), and `CalendarStore`/`GlancePanel` need the
Android framework, so nothing better is cheap; just don't read those four lines as proof the tap works. An emulator
check (remove an event in the provider with the keypad up, watch the row go) is the real test.

### N1: the refresh shares the calendar worker

See intent 2. Two provider queries ahead of whatever the Day screen asks next. Negligible.

### N2: existing sources converge within an hour of the deploy

Their `next_at` was set with the 60-minute interval; no migration needed.

### N3: footer vs churn (pre-existing)

A link with UID churn is fetched every 6 h and the footers say "every half hour". A "Next" row on the link's page shows
the truth. Unchanged by this round.

### N4: the glance's rows are re-rendered in full behind the keypad, and under Android's pad

`GlanceCalendar.changed` is `render()`, i.e. `renderNow()`: rows, tiles, layout, not just the reminder row. Under the
keypad the page is GONE, so nothing shows. Under Android's pad (stage ASK) the page is deliberately kept drawn
(`glancePageShown`), so a vanishing reminder row re-lays the page out behind the pad's overlay when the answer lands.
That is the intended effect of the tap ("while the keypad or Android's pad is up"), so only a cosmetic note.

### N5: whether the tap re-read is needed at all

The provider's `ContentObserver` (registered on `CalendarContract.CONTENT_URI` with descendants, once the calendar is
readable) already clears the days and reloads the glance on every sync batch, so the refresh at the tap only helps
where a notification was missed (the observer not yet registered, or a write that didn't notify). The change does no
harm and the user asked for it; the effective fix for "a removed event lingers" is the 30-minute fetch and the
nudge, and the tap is belt and braces. If the lingering still shows after the deploy, look at the observer
(registration time, the 1 s debounce) before the cache.

### N6: documents still saying 60

`docs/design/72-calendar-feeds.md` line 51 ("each source every **60 min** (decision 2)") and its decision 2
("Fetch every 60 minutes, plus Fetch now - or 30") are the design as decided and are left as history; the live notes
(`docs/notes/server/calendar.md`) say 30 and both launcher notes (`dagen-and-calendar.md`, `lock-and-glance.md`)
carry the tap's refresh. `forget()` also bumps the generation although nothing else reads on the worker while it runs;
harmless, and a defence if a second reader thread is ever added.
