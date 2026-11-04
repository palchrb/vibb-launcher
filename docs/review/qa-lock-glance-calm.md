# Light code QA - branch `lock-glance-calm` (7854af15, d2122590, 089004d2)

Reviewed 2026-11-04 by reading the diff against `main` and the touched code; no Gradle or cargo run (builder: launcher
2051/0, music 1249/0). Nothing High found.

## Medium

- **M1 - `VISIBILITY_SECRET` on the music notification may do nothing on this setup** (`music/.../playback/SecretMediaNotification.kt:35-38`,
  `PlaybackService.kt:352`). A notification's lock-screen visibility only acts under a *secure* keyguard. In own mode the
  launcher turns the system keyguard off (screen lock "None", `lock-and-glance.md` line 7), so it is a no-op there; it
  acts only in the `android` face (Android's PIN). Android's own media player on the lock screen is driven by the
  media session and its own setting (`Vis medier på låseskjermen`, `docs/review/qa-home-round-code.md:161`), not by the
  notification's visibility, as far as AOSP's SystemUI goes - so this may not remove the player the user saw. The diff
  says "not device-checked"; keep it labelled so in PLAN and check on the Jelly Star in both modes before calling the
  complaint fixed. The wrapper itself is sound (below).

## Low

- **L1 - the card's 6 dp gap goes stale** (`GlancePanel.kt` `spaceUnderCard`): it is recomputed only at the end of
  `fitNext`, but the alarm's own line can appear or go from the clock's minute tick (`LockClockFace.bindAlarm` ->
  `placeAlarm`) with the card open. Cosmetic, 6 dp, until the next `fitNext`. Call `spaceUnderCard()` from the tick path
  or from `weatherChanged` after `placeAlarm`.
- **L2 - the alarm's own-line text is not rebuilt when only the size changes** (`LockClockFace.placeAlarm`, the
  `alarm.text` guard compares strings only; the date-line copy has `lastAlarmPx`). The glyph keeps its old size/tint
  after a font-scale change if the activity is not recreated. Same `lastAlarmPx` guard fixes it.
- **L3 - tomorrow is read on every load, also before 18:00** (`GlanceCalendar.load`): one extra provider query that is
  not used until the evening. Harmless (worker thread); could be skipped before 16:00 if the boundary re-loads at 18:00
  (it does not, so leave as is unless it shows in a profile).
- **L4 - `besideFits` measures live children with `measure(UNSPECIFIED)`** (`LockClockFace.kt` ~231-240), from a layout
  callback and each minute. It works (it only needs widths), but `getDesiredWidth` as in `WeatherLockPanel.lineMeasure`
  would not touch the views' measure state.
- **L5 - tests**: no DST-day case for `glanceWakeAlarm` / `nextReminderBoundary` (the code uses `ZonedDateTime` so it is
  right, 25 Oct 2026 and 28 Mar 2027 would pin it); `SecretMediaNotificationTest` and `DayTextTest` are source scans
  (a Media3 notification needs a device/Robolectric, which music/ does not have) - they pin the text, not the
  behaviour; the browser test likewise scans for the probe constants (the pure `lockBrowsers` is tested properly).
- **L6 - a non-wake alarm shows in the evening**: `glanceWakeAlarm` takes any next alarm that rings before noon of
  the next day, so a 19:30 alarm at 18:05 shows as "19:30". That is the approved rule read literally; mention only.

## Checked and fine

- **Alarm edges** (`ClockTile.kt` `glanceWakeAlarm`): 17:59 -> evening = yesterday, noon = today 12:00, a 07:00-tomorrow
  alarm hidden; 18:00 -> evening = today, noon = tomorrow 12:00, shown. 11:59 alarm: shown until 12:00 then hidden
  (an alarm at 12:00 never). Past midnight the latest evening start is yesterday's, so a 07:00 alarm stays until it
  rings and a Monday alarm at Saturday 00:30 stays hidden. The cut is computed with `atTime(12,0).atZone(zone)`, so DST
  days are right; the weekday form (`> 24 h`) cannot occur (at most ~18 h). Zone change: `ZoneId.systemDefault()` is read
  live and `TextClock` re-fires on `ACTION_TIMEZONE_CHANGED`, so the alarm re-evaluates; the calendar's midnight
  boundary is re-armed at the next render only (screen-on), which is enough.
- **Alarm vs "I morgen" at midnight - not inconsistent**: the alarm is "the next wake-up" (kept until it rings), "I morgen"
  is a day's event (it becomes "Neste" when the day turns). At 00:30 the page reads alarm 07:00 and "Neste: Gym 09:00",
  which agree.
- **"I morgen" edges** (`Reminders.kt` `glanceNext`): an ongoing event has started, so tomorrow shows from 18:00 (tested
  18:00 with the 17:30-18:45 event); an event at 23:00 keeps "Neste" until 23:00; tomorrow all-day only or empty ->
  nothing; the calendar switch off -> nothing (`dayView` applies the rules, `glanceNext` policy gate); tomorrow not
  read -> nothing. `FIRST_DAY` rows are included, `CONTINUING` are not. `CalendarStore.dayBlocking` is called inside
  `CalendarStore.run`'s worker lambda (`GlanceCalendar.load`), never on the main thread; `next()` and `armBoundary()`
  only compute from the loaded data. The 18:00 and midnight boundaries are armed (`nextReminderBoundary`), and the
  midnight boundary reloads (`LocalDate.now() != loadedFor`). `ReminderPinger` uses `todayBlocking`/its own
  `nextPingCheck`, not the changed functions.
- **Per-minute `TextWatcher`** (`LockClockFace` init): `TextClock` with `HH:mm` ticks from `ACTION_TIME_TICK` (not each
  second - only a seconds format runs the 1 s ticker), so one `nextAlarmClock` binder call and a cheap `placeAlarm` per
  minute; every view write is guarded (text, size, content description, visibility), so no loop; `bindAlarm` touches
  `alarm`/`dateAlarm`, never `clock`; `alarmBound` keeps it quiet before the first bind; the watcher lives on a view of
  the same activity and is wrapped in try/catch - no leak.
- **Fit logic**: the weather's place is decided first and never counts the alarm; the alarm yields to its own line when
  `used + alarm > room` (also at width <= 0 -> shown, placed again at the first layout); the date row is never
  constrained, so the date is not cut; `weatherPlace` falls to `LINE` when date + " · " + core do not fit. Words that
  appear while the mark is beside wait for the next lock-up (documented, by design).
- **TalkBack**: `alarm` and `dateAlarm` carry `contentDescription` "Vekkerklokke 07:00" (en "Alarm 07:00"), the span glyph
  is not read, the weather separator is `importantForAccessibility=no`.
- **Music wrapper**: wraps both the returned notification and every one handed to the callback (artwork), `handleCustomCommand`
  forwarded; nothing else in `music/` sets a provider or posts the playback notification; Media3 1.7.1's
  start-in-foreground path goes through the same provider, so the FGS start notification is covered; mutating
  `Notification.visibility`/`publicVersion` on the built object before it is posted is safe and Android 14/15 FGS rules
  do not look at visibility. The media session and the launcher's lock tile (read from the session via the
  notification-listener access, not from this notification) are unaffected.
- **Browser probe**: `lockBrowsers = https ∩ http` of `queryIntentActivities(MATCH_ALL, BROWSABLE)` for the two
  `.invalid` links; real browsers declare both host-less; the QUERY_ALL_PACKAGES grant covers it; failure -> empty set.
