# Launcher notes: Dagen and calendar links

Moved from launcher/CLAUDE.md on 2026-11-01.

Naming (the user, 2026-11-02): the kid sees the app as **Kalender** (en **Calendar**) - the Home tile, the Recents card, the lock
glance's "Lås opp og åpne Kalender" (`day_title`, `glance_reminder_action`). "Dagen" stays the design and code name here (the
`day/` package, `DayActivity`, `#dagen`, `calendar.dagen.*` keys).

## Dagen: the kid's day, the parent's title rules (design 69, `docs/design/69-dagen.md`, at the monorepo root)

- **What**: a day view in Vibb One over Android's calendar provider, which ICSx5 (subscribed feeds) and DAVx5 (CalDAV)
  feed; one day as a list, swiped endlessly from day to day, an event's own page with the whole description; the
  parent's title rules applied wherever a title shows; on the lock the "Neste" line and the calm reminder row; the kid's
  own events in their calendar. **No event, title, description, count or time leaves the phone** - but the kid's
  coming events, only to the CalDAV calendar the parent picked (design 72b, below): the status report's `calendar`
  names calendars only. Mockup `docs/design/69-mockups/dagen.html` (§1, §1b, §3 A and C, §5).
- **Pure** (`day/`, no Android imports): `TitleRules.kt` (cleaning - control/bidi out, whitespace runs one space, NFC,
  200/2000 code points - and matching that ignores case per code point and whitespace; replace / prefix with the
  separators trimmed / hide; the first rule that applies wins; the server's `calendar.rs` is the same code, both run
  the shared `calendar_title_rules.json`, `TitleRulesTest`), `CalendarPolicy.kt` (`calendarPolicy`: the policy's
  `calendar` field by field - missing = an older server, everything shown and nothing on the lock; anything but an
  object = `UNKNOWN`, Dagen off; a rule it can't use is dropped and its id reported; `CalendarPolicyTest`),
  `CalendarKey.kt` (the key = 16 hex of SHA-256 over account type NUL account name NUL `Calendars.NAME`; the source =
  only the host of an http(s)/webcal NAME), `CalendarColor.kt` (a colour per calendar, 2026-10-27, mockup §6 B: the
  policy's `calendars[].color` - one of eight names, the ARGB table here, Dagen draws on the night ground only -, an
  unknown name none; `OWN` = the kid's own calendar's peach, never a policy value), `DayModel.kt` (`dayView`: all-day
  on its UTC dates, timed on the local ones, multi-day first/middle/last, cancelled/declined/hidden/rule-hidden out,
  two copies one row, the order, "now", each row's `color` by `rowColor` - the phone's own or the parent's pick for
  the kid peach, else the policy's or none -, `DayView.legend`/`legendShown` - the day's calendars in order of
  appearance, the key whenever the day has an event (2026-11-01; it was more than one); `DayModelTest` with the DST days), `RowText.kt` (the second line: the location, else the description's first
  sentence - a "." after a digit or `ca kl nr osv bl.a f.eks` ends nothing - at most 120 code points; `cut` = the row's
  "›"; `FirstSentenceTest`), `DayPager.kt` (`DAY_CENTER`, `dayAt`/`positionOf`, `todayJump`: "← I dag" on a later day,
  "I dag →" on an earlier one; `DayPagerTest`), `Reminders.kt` (§5: `reminderStart` off / before N / own - the earliest
  alarm at most 120 min before, `MINUTES_DEFAULT` none -, `reminderNow` only under the master switch and Dagen on Home,
  `glanceNext`, `nextReminderBoundary`; `RemindersTest`), `KidEvent.kt` (step 3: the form's input, its default start -
  the next whole hour, tomorrow's midnight past 23:00 today -, `kidCalendarOf`, `kidMayEdit` - this phone's by the
  `vibb-<phone tag>-<uuid>` UID, or a `vibb-` UID with our `CUSTOM_APP_PACKAGE`, in the kid's calendar: a sibling's
  event in a shared calendar is not the kid's; `phoneTag` = 8 hex of SHA-256 over `vibb-calendar-uid:` + ANDROID_ID,
  `docs/notes/server/calendar.md`; `KidEventTest`).
- **`calendar/CalendarStore`** (one worker thread; every provider call): the calendars that sync events, a day's
  instances (`Instances.CONTENT_BY_DAY_URI` for the day and its neighbours, filtered by the model), an LRU of 9 days, the
  events' own reminders, an event by id; the provider's change notification debounced to one reload a second; the
  report; `tileShown`; `dagenOn` (a usable policy with Dagen on Home: else `dayBlocking` is an empty day and an open
  Dagen, event page or form closes on resume or the next change); the kid's calendar (`kidCalendar`: the parent's
  writable pick, else the local "Min kalender" it
  makes at Dagen's open while the kid may add events - `ACCOUNT_TYPE_LOCAL` through the sync-adapter URI, NAME
  `vibb-kid-calendar`; only while that calendar is shown - a hidden one takes no new event); `requestSyncIfDue` (Dagen's open, at most every 30 min); the kid's three guarded writes (insert,
  and update/delete only of an event still theirs - the provider's only writes, `KidEventTest`). `READ_CALENDAR` and
  `WRITE_CALENDAR` are self-granted by `AppEnforcer.apply` while managed and at the store's start as device owner.
- **UI** (`ui/day/`): `DayActivity` (KidHeader's second line: the weekday over the date, always; the jump back off today;
  `ViewPager2` over `Int.MAX_VALUE` days, a 10 dp peek of the neighbours' cards - the pager's list padded
  `PAGER_PAD_DP` 13 dp, each page 3 dp, the root `clipToPadding=false` (E5's L1); a page shows nothing until its day is read; a date/time/zone
  change keeps the page's date; a minute tick; a reminder that is up under the header; the "+" while the kid may add
  events; since 2026-10-27 a task of its own - `taskAffinity` `${applicationId}.day` on the three Dagen activities, in
  Recents with "Kalender" (`day_title`) and the launcher's icon as its card (`setTaskDescription` on the root), so Home and Back get
  Android's task-close animation; a minute away (`DAY_BACK_TO_TODAY_MS`, pure `backToToday`: stopped that long, or its
  saved state that old) and it opens on today again, unless a day is asked for (the reminder row); the one `DayActivity.intent(context, day)` (`FLAG_ACTIVITY_NEW_TASK`; with a day
  also `CLEAR_TOP`, so the reminder row lands on the day view - from the lock's task it died with the lock, E5's H1)
  serves Home's tile and the glance, and the three screens redirect to the time-rule screen on resume while a lock
  reason is set (Kid Settings' rule), since Recents can bring the task over it; `DayTaskTest`), `DayListAdapter` (the
  calendar's 8 dp dot in its own 14 dp column before the time - `day_event_dot`, `bg_day_dot` tinted `CalendarColor`,
  invisible, never gone, without one, so every row's start and end time share one left edge (the user's one change to
  mockup §6 B); two lines of title and second line - the kid's own event's second
  line is its place, else the calendar's name -; the time column 56 dp, widened per page to its widest one-line time
  by `timeColumnPx`, an all-day row "Hele" over "dagen"; times by `ClockFormat.formatClock` - AM/PM in 12 h, E5's
  L4 -, the "›" only when cut - `cutByRule` or an ellipsis after
  layout -, the ongoing ring and "Pågår nå", one TalkBack node "Pågår nå, 14:45 til 15:30, SFO: Leksehjelp" with
  "Åpne hendelsen" - the calendar's name last on a day with a key; the key under the list, `item_day_legend`
  as the last item while `legendShown`: one `DotSpan` and name per calendar in one wrapping text, TalkBack
  "Kalendere: Skolen, Fotball"), `EventActivity` (the header's day: an all-day event's UTC date, `eventDay`; read again by id, rules applied, the whole description in a plain TextView, no
  links; Change and Delete only for the kid's own), `EventEditActivity` ("Ny avtale": what, day - `DayActivity.dayLine`, the locale's best pattern for "EEEEdMMMM", en-US
  "Tuesday, October 27" -, from - the framework's date and time pickers with our `VibbPickerDialog` (the dark Material dialog with every colour
  named: before it the system's light dialog took our white text, and the day numbers were white on white; `PickerThemeTest`)
  -, length, place). `KidHeader`'s row is at least 56 dp and grows with the second line
  (E5's M1). Pure `day/DayText.kt` (`dayLineSkeleton`/`dayLineText`, `timeColumnPx`; `DayTextTest`).
  Home: `GridTile.Day` right after the phone book (`tileShown`: Dagen on Home, a readable provider, a calendar shown or
  the kid's own to come). `KidScreensEscapeTest` scans `ui/day` and `calendar`.
- **The glance** (step 2, `lock/GlanceCalendar`, `GlancePanel`): today read off the main thread at the page's start and on
  every provider or policy change, one main-thread callback at the next boundary - no alarm, no notification, no wake.
  C: `GlanceCalendarRow` first in the list (the calendar glyph in the peach disc - since 2026-10-27 the calendar's 16 dp
  dot in the disc instead when it has a colour, `bg_glance_dot`, the row's `color` from the reminder's row -, "Fotball
  om 15 min" / "… nå", the start; never dismissable, and a sideways swipe on it does nothing - `SwipeMode.HELD`: the row gets a cancel, so no
  tap, E5's L2; the tap is `GlanceTarget.Day` through the keypad, `GlanceOpen.DAY`, none under a time rule).
  A: `lock_glance_next` under the alarm line in its style, yielding while C is up and when `glanceNextFits` (the third
  row keeps 16 dp in sight) says no - read from the laid-out page with the line taken out.
- **Enforcement and Home**: `CALENDAR_SYNC_APPS` (ICSx5, DAVx5) are never suspended by a time rule while allowed
  (`EnforcementPlan`), and an app with `app_display.on_home: false` is off Home and the drawer except while the pause or
  the override is open (`onHomeListable` in `AppFilter`).
- **The quiet row plus a ping** (2026-11-01, branch `reminder-ping`): `lock_reminders: "ping"` = the row
  (`lockReminders`) plus `lockPing`; any other value but `row` stays off (the server sends `ping` only to a launcher
  claiming `calendar_ping_v1`, `row` to an older one). Pure `day/ReminderPing.kt` (`reminderPing`: PLAY only with ping,
  ringer NORMAL, no Do Not Disturb, no call - `AudioManager.getMode()` -, no time rule in force, bedtime included, the
  row visible - the screen off or the lock's glance up, `GlanceCalendar.showing` (QA M2) - and on time: within
  `PING_LATE_MS` (90 s) of the row coming up, `reminderUpSince` (its reminder's start, or the start of the sooner event
  that hid it; QA M1: a boot, a sync, the switch turned on mid-window); else QUIET, the moment passes; once per
  reminder, key `eventId@begin`, kept a day - `keptPings`; `nextPingCheck`: a reminder's start, or the up row's start
  when the next may surface; `ReminderPingTest`). No usable policy (before the first unlock) leaves the alarm as it is
  (QA L1). `calendar/ReminderPinger`:
  unlike the row it needs an alarm (the sound matters with the screen off) - an exact alarm (`USE_EXACT_ALARM`) to the
  non-exported `ReminderPingReceiver` at the next check, else the next local midnight while ping is chosen, re-armed on
  every `CalendarStore` change (provider, policy, process start); the sound is `RingtoneManager`'s default
  notification sound with `USAGE_NOTIFICATION`/`CONTENT_TYPE_SONIFICATION`, once (`goAsync` held 4 s): no
  notification, nothing in the shade, no vibration. Logcat tag `ReminderPing` ("Reminder up: PLAY" / "QUIET").
- **Contracts**: the policy's `calendar` (`PolicyResponseCompatTest`), the report's `calendar` (`CalendarReport`,
  `calendar_status_report.json`, `CalendarReportTest`), capability `calendar_v1`.
- **Not device-tested**: design 69 §7 (`docs/testing/emulator.md` §6w): ICSx5's and DAVx5's NAME/account type/access
  level, the local calendar's insert as a plain app, the two-line caps and "›" at font 1.0 and 1.3, the glance
  geometry, DAVx5's round trip of the `vibb-` UID, `requestSync`, a far-future day.

## Calendar links: the server's calendars in a local calendar (design 72 step 1, `docs/design/72-calendar-feeds.md`, at the monorepo root)

- **What**: the parent pastes a calendar's link in the PWA; the server fetches and parses it
  (`docs/notes/server/calendar.md`, "Calendar links") and Vibb One writes the set into **one LOCAL read-only calendar
  per link** - account `Vibb One` (`FEED_ACCOUNT`), NAME `vibb-feed-<uid>` (`feedCalendarName`; the server computes the
  same key, `calendarKey("LOCAL", "Vibb One", NAME)`), the policy's name as its display name, `CAL_ACCESS_READ` 200
  (`FEED_ACCESS_LEVEL`: the kid-write rules - >= 500, not LOCAL - never pick it, `FeedPlanTest`), `SYNC_EVENTS` and
  `VISIBLE` 1, a calm colour by its key (`feedColour`). Dagen reads it like any calendar; the report lists it among
  `calendar.calendars[]` (no `source`), and `vibbLocal` checks NAME, so "Min kalender" is still made.
- **Pure** (`day/`): `CalendarPolicy.feeds` (`FeedRef(id, name, rev)`, a row that doesn't read dropped) and
  `feedsComplete` (an array every element of which read - QA L9: only then may a calendar the policy doesn't name be
  deleted; missing, a bad element, `UNKNOWN`/`OLDER_SERVER` delete nothing); `FeedEvents.kt` (`feedDocument`, the wire
  DTOs; `seriesRows(event, phoneZone)`: the master - `_SYNC_ID` = `UID_2445` = uid, `SYNC_DATA1` the series' rev,
  `SYNC_DATA2` the zone a floating (or unknown-zone) series is written in; single DTSTART + DTEND; recurring DTSTART +
  DURATION (`PT<n>S`, all-day `P<n>D`) + RRULE with a local `UNTIL` made UTC in the zone written (`ruleWithUtcUntil`,
  QA L6) + RDATE + EXDATE (UTC `yyyyMMdd'T'HHmmss'Z'`, all-day `yyyyMMdd`, comma-joined); EVENT_TIMEZONE the series'
  zone, the phone's when floating or unknown, UTC all-day at UTC midnights - then each override: `_SYNC_ID` = uid `#`
  recurrence id, ORIGINAL_SYNC_ID, ORIGINAL_INSTANCE_TIME, ORIGINAL_ALL_DAY, a cancelled one `STATUS_CANCELED`; the
  alarms as `Reminders` rows, METHOD_ALERT, read by Dagen's "own" mode; `EXDATE_COLUMN` - EXDATE's fallback, each
  exclusion a cancelled override row, the switch §6y may flip, QA L7; `FeedColumns` spells CalendarContract's column
  names, pinned by `FeedEventsTest`), `FeedPlan.kt` (`feedPlan`: a uid gone -> its master and, by ORIGINAL_SYNC_ID,
  its overrides deleted; a new rev, a floating series written in another zone, two masters of a uid or a mapping bump
  (`FEED_MAPPING`, `CAL_SYNC2`) -> deleted and inserted; a row of no series deleted; `feedBatches`: the deletes first
  in chunks, the inserts whole-series in `applyBatch` calls of at most `FEED_BATCH_OPS` 200 operations, never split;
  `runPass`: a refused batch retried series by series, one still refused counted - logged by its index -, the provider
  gone ends the pass with the rev left (`PassResult.writeRev`, QA M2); `feedFetch`: a set is fetched only when the
  calendar is missing, its `CAL_SYNC1` isn't the policy's rev - `If-None-Match` the old rev -, `CAL_SYNC2` isn't
  `FEED_MAPPING`, or `CAL_SYNC5`'s floating zone isn't the phone's; `feedCalendarsGone`).
- **Glue**: `calendar/CalendarFeeds.refreshIfNeeded(context, api, freshPolicy.calendar)` in `MdmSyncWorker` right
  after `dispatchPendingCommand` (so a `lock` or `locate` never waits behind a first apply, QA L6) and before the
  status report (a fresh policy only; never throws): the calendars made or renamed (from the
  policy alone), the links gone deleted (their events with them), each link that needs it fetched
  (`MdmApi.calendarFeed(id, ifNoneMatch)`; a failed GET, a 304 or a document that doesn't read changes nothing), planned
  and applied, then `CAL_SYNC1` the body's rev (N2), `CAL_SYNC2`, `CAL_SYNC3` the refused series, `CAL_SYNC4` the
  unknown zones, `CAL_SYNC5` the floating zone - written only after a pass the provider stayed for (idempotent and
  resumable: a killed pass is planned again). `CalendarStore` stays the provider's only writer: its section "calendar
  links (design 72)" holds every link write, **each through the sync-adapter URI with the account parameters**
  (`asFeedAdapter`, QA L10; a plain delete only marks DELETED); `applyFeedBatch` reads IllegalArgumentException,
  SQLiteException and OperationApplicationException as refused, anything else (no client, a dead provider) as gone. The
  report's `calendar.feeds` (`CalendarFeeds.report`, `CalendarFeedReportRow(id, applied_rev, refused, unknown_zone)`;
  left out when empty). Capability `calendar_feeds_v1` (`CALENDAR_FEEDS_CAPABILITY`).
- Tests: `CalendarFeedsTest` (the shared `calendar_feeds.json`: `feed` maps to exactly `provider` in its zone, the key,
  the policy's and the report's rows, both copies), `FeedPlanTest`, `FeedEventsTest` (the columns, all-day, floating,
  an unknown zone, EXDATE's fallback, the sync-adapter URI by source), `KidEventTest` (the kid's four writes above the
  section), `PolicyResponseCompatTest`.
- **Not device-tested** (`docs/testing/emulator.md` §6y): the provider taking the LOCAL calendar and the sync columns
  from a plain app, the EXDATE forms (else flip `EXDATE_COLUMN`), UNTIL, the override linking by ORIGINAL_SYNC_ID and
  the back reference, the first apply of 2000 events (timed), a kill mid-apply. Also still to do: the server's
  itslearning fixture folds a synthesised body - the real feed's bytes are still to be captured (QA N7).

## The kid's events written to a CalDAV calendar (design 72b, `docs/design/72b-caldav-write-back.md`, branch `caldav-write`)

- **What**: the parent marks a CalDAV calendar the server reads as the kid's (`calendar.kid_caldav` `{id, accepted,
  back, pending}`, `null` otherwise); the kid keeps writing in "Min kalender" (no `writable_for_kid` is sent while
  marked, so `kidCalendarOf` picks it), and the server writes the events into the calendar with the password it keeps -
  the phone never sees it, the account or the host. The parent's edits and deletes there come back and are applied in
  "Min kalender" (the calendar wins). The kid sees nothing new but that; no permission, no adb. Capability
  `calendar_kid_caldav_v1` (`CALENDAR_KID_CALDAV_CAPABILITY`).
- **Pure** (`day/KidCaldav.kt`): `kidCaldavPolicy` (missing or malformed = off), D2.7's `canonicalText` (`\r\n`/`\r` as
  `\n`, C0 but LF/TAB out, cut by code point 200/200/2000) and `kidVersion`/`kidHash` (16 hex of SHA-256 over the
  serde-compatible canonical JSON), `kidSnapshot` (this phone's rows - `ownedByVibb`, the UID pattern, live, no series -
  that end after now, no forward horizon; at most 300 rows and 512 KB, the farthest left out, `cut`/`cut_from` the
  earliest left out/`cut_uids` or `null` past 2000; `ended` the uids that ended within 60 days; `rev` over the rows by
  uid; the body in the server's key order), `kidAnswer`, `backOps` (by the live row with the UID: update, insert with
  the UID when the kid deleted it, delete, or ignore - a row `ownedByVibb` refuses is never touched; all-day at UTC
  midnights in `UTC`), the posted union and `skipSet` (D3.2), `skipChange` for `CAL_SYNC6`. `KidEvent.kt`:
  `kidEventInput(.., titleShown, placeShown, timesShown)` and `kidEventUpdateColumns` - a change writes the title, the
  place or the times only when the kid changed that field (R-L5; the times since code QA M1, so a parent's all-day day,
  odd length or later move survives a kid's title fix); changed times go in whole with `ALL_DAY` 0 and the zone (QA
  L6). `shouldPost` (D1.2, plus a set that differs from the last posted one or whose cut moved), `nextApplied`,
  `afterBackDeletes` (an applied hand-back delete leaves the skip set at once).
- **Glue** (`calendar/KidCaldavSync.syncIfNeeded`, in `MdmSyncWorker` right before `CalendarFeeds.refreshIfNeeded`, never
  throws): a full read of "Min kalender" only (`CalendarStore.kidEventsBlocking`); posts
  (`MdmApi.kidEvents`, `POST /api/devices/calendar/kid-events`) when the set differs from `accepted`, `back` > 0 or
  hand-backs wait to be acknowledged; a 429 asks for one more sync after `Retry-After`; the answer's hand-backs through
  `CalendarStore.applyKidBackBlocking` (each re-checked: live, in "Min kalender", this phone's), their ids kept in
  prefs (`kid_caldav`, at most 300) and sent as `applied` (cleared on a 200; a refused write isn't acknowledged; one more
  sync 11 s later carries them). The form's save and the event page's delete ask for a sync while marked
  (`KidCaldavSync.requestSoon`).
- **The skip set** (`CalendarFeeds`, always, marked or not): the live UIDs of "Min kalender" and every UID posted since
  the server last showed that set accepted with `pending` 0 are dropped from every feed document before `feedPlan`; a
  feed calendar keeps the UIDs it actually skipped in `CAL_SYNC6` (`feedSkipped`: those in its set, a JSON array, at
  most 300) - one that joins loses its rows locally with no GET (remembered once the delete went), one that leaves
  makes `feedFetch` fetch the set again without `If-None-Match` (QA N3). An unreadable "Min kalender" keeps each
  calendar's stored uids as its skip set (`kidLiveUidsBlocking`: `null` vs no calendar). Events
  this phone once wrote through DAVx5 (its tag, not in "Min kalender") show from the feed, read-only.
- Tests: `KidCaldavTest` (the shared `calendar_kid_events.json`: `provider` to exactly `snapshot` and `cut_variant`,
  `canonical`, the policy and the answers, `back_provider`, the ids, both copies), `FeedPlanTest` (the skip set,
  `skipLeft`), `KidEventTest` (R-L5, the hand-back writes' guards), `PolicyResponseCompatTest`.
- **Not device-tested**: `docs/testing/emulator.md` §6z (Radicale) and one real account.
