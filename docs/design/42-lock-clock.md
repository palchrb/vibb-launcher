# 42 - The clock on the lock: Fossify Clock's timer and the next alarm (proposal, rewritten after the probes)

Asked 2026-10-19/20. The user: a tile like iPhone's with the countdown, a tap opens the app; "bruker systemklokken for
å ikke skape noe mulige angrepsflater" - then, when Google's clock proved unreadable: "Jeg er helt ok med å ta inn
fossify clocken ... om fossify oppsettet vil fungere likt over alt og for alle telefoner". Launcher only (one pinned
constant; no API change). Builds on design 25 (the glance page, `GlanceReader`/`GlanceStore`, `glanceFit`,
`LockGlanceTest`), design 28 (the music tile), `PinLockRuntime`'s alarm exemption, and the catalog (security review
M10: a catalog app is pinned to its package and signer). Revised twice on 2026-10-20: after
`qa-r6-launcher-design.md` (42-1…42-6) and after the two probes below.

## 0. The probes (2026-10-20, the emulator)

- **Google's clock** (`com.google.android.deskclock`): the stopwatch/timer notification is a custom
  `contentView`, `android.showChronometer=false`, title and text `null`, flags `AUTO_CANCEL|LOCAL_ONLY`. Nothing
  readable without rendering a foreign view. Out.
- **Fossify Clock** (`org.fossify.clock`, installed through the catalog from GitHub `FossifyOrg/Clock`): a running
  timer posts an ongoing notification (`channel=simple_alarm_timer`, `ONGOING_EVENT|FOREGROUND_SERVICE`, `vis=PUBLIC`)
  whose **title is the remaining time ("11:12", updated every second)** and whose text is "1 timer er i gang"; no
  actions; `showChronometer=false`. A running stopwatch posts the same shape ("Klokke · nå", title "28:25", text "Stoppeklokke" - seen in the shade
  2026-10-20 after two dumps taken while it wasn't running); so the tile covers both. Its alarms go through
  **`setAlarmClock`** (`Alarm clock: triggerTime=… showIntent=… startActivity`), so `getNextAlarmClock` sees them.

So: Fossify Clock becomes **the catalog's clock app**. The same APK, package and signer on every phone (the catalog
pins both; Fossify asks for no internet permission).

## 1. Decisions

1. **The clock app is known by package and signer, in the launcher**: `ClockApp.PACKAGE = "org.fossify.clock"` and
   `ClockApp.SIGNER_SHA256` (the release APK's signing certificate, computed by the implementer with `apksigner
   --print-certs` on a GitHub release - the same value the catalog pinned, visible on the app's page in the PWA),
   checked against the installed package's signers (`CatalogApkCheck`'s way). `ClockApp.installed(context)` is true
   only when both match. A later server flag (`clock_app` on a tracked app, in the policy) can replace the constant;
   not now (no API change this round).
2. **The PIN lock's alarm exemption widens by that one app**: today the alarm's full-screen intent may show over the
   lock only for a `FLAG_SYSTEM` clock (`PinLockRuntime.systemClockPackage`); now also when the `nextAlarmClock`'s
   `showIntent.creatorPackage` is `ClockApp.PACKAGE` and `ClockApp.installed` holds. The same mechanism, one more
   package, no other change to what the lock allows. `clockPackages()` (read-only) replaces the private
   `systemClockPackage` for the readers below.
3. **The next-alarm line** under the date: `AlarmManager.nextAlarmClock` when its creator is one of
   `clockPackages()`; "Alarm 07:00", with the weekday when more than 24 h away ("Alarm tor 07:00"), in the lock's
   `ClockFormat`; refreshed on `ACTION_NEXT_ALARM_CLOCK_CHANGED` and at every page show. No tap.
4. **The timer tile**: its own `ClockReader` in `badges/` (wired in `BadgeListenerService` like `VoipCallReader`:
   posted/removed/connected/disconnected, its own guard test), taking only notifications of `ClockApp.PACKAGE`
   that are ongoing - `GlanceReader` never sees them (it drops unticked packages and every ongoing notification).
   `ClockTile.parse(title, text)` (pure): the remaining time from the title (`H:MM:SS`/`MM:SS`/`M:SS` to seconds;
   anything else = no time, the cleaned title shown as is), the text cleaned with `cleanGlanceText(raw, max)`. The
   tile in the music tile's style (`lock_timer_tile`: the glyph, "Timer" as our own label, the time large in a
   fixed-width tabular text measured for "88:88:88"); the time **counts down locally between the app's updates** from
   the last parsed value (a drift corrected by every update; the app updates each second, so the local tick is a
   smoothing, never the truth) and stops at 0:00. No buttons (the notification has none). The tile goes when the
   notification goes. A stopwatch shows the same tile counting **up** (the parse is the same; "Stoppeklokke" is told from the timer
   by the notification's text/channel; our label says "Stoppeklokke"; no end). The tile's tap: `GlanceTarget.ClockApp` (the
   app's launch intent) through the opener's one-send-two-starts contract - the keypad first, then Fossify
   (`LockGlanceGuardTest` case). Several timers: the app posts one notification ("2 timere er i gang"); the title is
   the nearest - shown as is.
5. **Rendering**: round 6's rule (`r6-launcher-small-items.md` §2) - a clock change renders the tile alone and
   refits the page only when the tile came or went or its measured height changed.
6. **`glanceFit`** gets the term `clockFull` (measured from the XML like `tileFull`) and tries the candidates in
   this order, first fit wins: (1) both tiles full and every row; (2) the music tile compact; (3) rows folded into
   "+ N varsler"; (4) the clock tile gone (then today's order for the music tile alone). A running timer outlives the
   music tile's full size and the rows (the kid came to see it). The numbers in `LockGlanceTest` (`fixed`, `tileFull`,
   `tileCompact`) are the ones round 6 re-pinned; `clockFull` is pinned new.
7. **Not read**: Google's clock (nothing to read) - a phone without Fossify shows the alarm line only when the system
   clock set the alarm (today's rule) and never a timer tile.

## 2. Security

Nothing from another app is rendered as a view: the time is parsed from a string and drawn by us, the text is
cleaned like a glance row's; only `ClockApp.PACKAGE` with the pinned signer is read; no action is fired (none exists);
the tile's tap goes through the keypad. The alarm exemption grows by exactly one pinned app. Redacted `toString`s as
the rows have. `LockGlanceGuardTest`: the tile opens nothing without the keypad; a notification of another package,
or of the right package with a wrong signer, shows nothing.

## 3. Tests (required)

`ClockTileTest` (JVM, pure): the parse (`11:12`, `1:05:00`, `0:09`, garbage), the local countdown between updates and
its correction, the alarm line's condition (creator in `clockPackages()`, none, > 24 h = weekday). `ClockAppTest`:
installed = package + signer. `LockGlanceTest`: `clockFull` pinned, the candidate order, 320/360/411 dp and the
largest font scale. `LockGlanceGuardTest`: as in §2. `PinLockRuntime`'s exemption test: Fossify's alarm allowed only
with the signer.

## 4. Device checks

A check that comes first, on the emulator: **an alarm two minutes ahead with the screen locked** - Fossify's alarm
screen must come up (over our lock, through the exemption). On Android 14+ a non-system clock gets
`USE_FULL_SCREEN_INTENT` only through the user's grant; the device owner can't give it. If the alarm shows as a plain
notification instead, Fossify's own one-time prompt (shown when the app is first opened) is the setup step, written
into the setup runbook; the launcher cannot do it for the parent.

Emulator (Fossify from the catalog): a 10-minute timer, the lock: the tile counts down and follows the app's updates,
the timer's end removes the tile (Fossify's alarm screen shows over the lock through the exemption); an alarm set
for tomorrow: "Alarm tor 07:00"; the tap: the keypad, then Fossify; Google's clock timer: nothing. The Jelly Star: the
same, plus Fossify's alarm ringing over the lock and dismissable.

## Revision (2026-10-20)

- After qa-r6-launcher-design.md: 42-1 the probe (§0); 42-2 no actions are fired at all now (none exist); 42-3 the
  own `ClockReader`, `clockPackages()` exposed; 42-4 the fit order and `clockFull` (§1.6); 42-5 `GlanceTarget.ClockApp`;
  42-6 `cleanGlanceText`, the weekday, tabular width.
- After the probes: the system clock is out, Fossify Clock in as the catalog's clock app with a pinned signer; the
  exemption widened by that one app (§1.1-1.2); the time read from the notification's title and smoothed locally
  (§1.4); the stopwatch tile too, counting up (seen in the shade after the dumps missed it).

## Implementation status (2026-10-20, implementer B, branch `r6-b`)

- **The pin** (`lock/ClockApp.kt`): `ClockApp.PACKAGE = "org.fossify.clock"`, `ClockApp.SIGNER_SHA256 =
  affdb124d3f4720c2f98dbca9eacba0514fba4306e20a2786c861c3c0d6ff292` - `apksigner verify --print-certs` of
  `clock-10-foss-release.apk` from GitHub `FossifyOrg/Clock` release 1.6.0 (APK SHA-256 `43cf9f0e...dca079`, signer
  "C=IN, O=Fossify, CN=Naveen Singh"). Compare with the catalog's pin on the app's page in the PWA. `installed(context)`
  (PackageManager, off the main thread) = the pin among the installed package's signers or their lineage
  (`SelfUpdate.signers`, pure `clockAppVerified`); `verified` is its cached answer, refreshed by `ClockApp.refresh` from
  `PinLockRuntime.init`'s IO block and from `Application`'s package callbacks (added, changed, removed) for that package
  only; a lost verification drops the timer tile at once.
- **The exemption** (§1.2): pure `lockClockPackages(systemClock, clockAppVerified)`; `PinLockRuntime.clockPackages()`
  (read-only) feeds `rememberAlarm`, which now takes a set (`RefrontPolicyTest`: Fossify's alarm only with the
  signer). **Addition, not in the note**: with the kiosk off the lock's lock task allows only `pinLockHelpers` while
  LOCKED, so Fossify's alarm screen could not come up over it - `pinLockHelpers(..., clockApp)` adds the verified clock
  app (`AppEnforcer.resolvePinLockHelpers` passes `ClockApp.installed`; `LockTaskHelpersTest`). With the kiosk on it is
  pinned like any allowlisted app (the catalog allowlists what it installs).
- **The alarm line** (§1.3): `lock_glance_alarm` under the date, pure `glanceAlarm` (a creator in `clockPackages()`,
  ahead of now, `withWeekday` past 24 h), "Alarm 07:00" / "Alarm tor 07:00" (`ClockFormat.formatUntil` - AM/PM kept in
  12 h, the alarm is a time, not a clock face - and the locale's "EEE" without its dot); refreshed at every page
  render and on `ACTION_NEXT_ALARM_CLOCK_CHANGED` (a receiver between onStart and onStop). It is part of the fit's
  `date` term and goes with the date.
- **The timer** (§1.4): `badges/ClockReader` (posted, removed, the connect sweep, disconnect from
  `BadgeListenerService`): pure `clockCandidate(package, verified, ongoing)` first, then only `EXTRA_TITLE` and
  `EXTRA_TEXT` into the pure `ClockTile.parse` (`clockSeconds`: `H:MM:SS`/`MM:SS`/`M:SS`; else the cleaned title as
  is; the text through `cleanGlanceText`), memory only, redacted `toString`. `lock/ClockLockPanel` binds
  `lock_timer_tile` (the hourglass glyph, "Timer", the time 24 sp tabular, the text) and counts down locally
  (`clockRemainingNow`/`clockTileTime`, stops at 0:00, ticks only while shown on the page - `clockTicks`); each update
  of the app corrects it. The tap: `GlanceTarget.ClockApp` -> the keypad with "Skriv koden for å åpne <Klokke>", under a
  time rule only when the rule leaves the app usable; after the code the opener's existing launch start (no send, no
  new start - one send, two starts unchanged).
- **Rendering** (§1.5): the timer's callbacks go through round 6's `GlanceCoalescer`; `tileChanged` refits when either
  tile came, went or changed height (`glanceRefitDue` for each; `fitClockPx`).
- **The fit** (§1.6): `GlanceHeights.clockFull` (114 dp from the XML) and `glanceFit(..., clock)`: with a timer, 1-2
  both tiles full, 3-4 the music tile compact, 5 the rows folded one by one down to none ("+ N varsler"), then today's
  order without the timer. At 473 dp with music, a timer and 2 rows: 5 with no rows and "+ 2" (446.6 of 449).
- **Deviation**: the time's width is `match_parent` (one line, tabular figures), not measured for "88:88:88" - the same
  reasoning as round 6's time line (no layout per tick, no overflow at 320 dp and font scale 2.0, where
  `LockGlanceLayoutTest` checks "8:88:88" at 24 sp x 2.0 fits the tile).
- Tests: `ClockTileTest` (the parse, the countdown and its correction, the candidate filter, the alarm line),
  `ClockAppTest`, `LockGlanceTest` (the candidate order with a timer, the tap's target/prompt/time rule/open step,
  `clockFull` 114 pinned, the order and touchables, the timer's width at 320/360/411 dp), `LockGlanceGuardTest` (the
  reader's two extras after the candidate check, no action/intent/view/prefs, its log line, the four listener calls;
  the tile opens nothing without the keypad), `RefrontPolicyTest`, `LockTaskHelpersTest`.
- **Device checks owed**: §4 - first the full-screen-intent check above; then the timer counting down and following
  the app, the tile leaving at the timer's end, "Alarm tor 07:00", the tap (keypad, then Fossify), Google's clock
  showing nothing; on the emulator also with the kiosk off (Fossify's alarm over the lock through the new helper pin).

### Fix round after qa-r6-b-code.md (2026-10-20, branch `r6-b`)

- **Fossify's notifications, from its source** (FossifyOrg/Clock main, read by the coordinator; the decision that
  replaces the QA's M2 step-aside): a running timer is channel `simple_alarm_timer` (ongoing, title = the remaining
  time, text "N timer er i gang"); the stopwatch `simple_alarm_stopwatch` (title = the elapsed time, text
  "Stoppeklokke"); the timer's end - fired from the app's own CountDownTimer, no `AlarmManager` entry (the user's
  `dumpsys alarm` probe: only the alarm clock's two entries) - is a **notification only**, no activity and no
  full-screen intent: channel `simple_timer_channel_<sound>_<millis>`, IMPORTANCE_HIGH, an insistent USAGE_ALARM sound,
  category EVENT, title the timer's label or "Timer", text "Tiden er ute", **one action "Dismiss", a broadcast to its
  HideTimerReceiver** (hides it and resets the timer), auto-hidden after `timerMaxReminderSecs`. Alarms are
  `Alarm_Channel`, a full-screen intent to AlarmActivity and `setAlarmClock` - today's exemption path. So the timer never
  reaches the alarm line and the lock needs no refront change for it: nothing over the lock moves.
- **M1, the channels** (pure `clockKind(package, verified, channelId)`): `simple_alarm_timer` = TIMER (counts down, our
  label "Timer"), `simple_alarm_stopwatch` = STOPWATCH (counts **up**, our label "Stoppeklokke"/"Stopwatch"), a channel
  starting with `simple_timer_channel_` = ENDED; `Alarm_Channel`, any other channel, no channel, a wrong signer or
  another package: nothing. `ClockReader` keeps one entry per notification key and shows one (`clockShown`: ended, then
  running, then the stopwatch).
- **The ended timer**: the tile shows "Timer", the cleaned text ("Tiden er ute") as its big line, and one round button
  **"Stopp"** (`lock_timer_stop`, the peach disc with Material Symbols `stop`). It is offered only when the
  notification has exactly one action and that action's intent is a broadcast and not an activity (pure
  `clockStopAction`; read by `ClockReader` only for ENDED). A tap sends it from `lock/ClockActions.stop` - the glance
  code's only `.send(`, re-checking `isActivity`/`isBroadcast`, with `ActivityOptions`
  `setPendingIntentBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_DENIED)`; it never throws. An activity
  action is never offered. The tile goes when the notification goes (the Dismiss, or Fossify's auto-hide).
- **M3**: `LockGlanceGuardTest` pins the three signer checks in the glue - `ClockApp.installed` through
  `clockAppVerified`, `clockPackages()` passing `ClockApp.verified`, the helper pin's `takeIf { ClockApp.installed }`.
- **L4/L5**: the coalescer carries which source changed (`musicDirty`, `clockDirty`); a clock update renders and
  measures the timer tile alone, never the music tile. The guard pins both panels' callbacks to `tileChanges.request()`
  and that `tileChanged` renders no page.
- **L7**: `LockGlanceLayoutTest` adds the alarm line (20.2 dp) to the date term: at 473 dp no music with 4 rows and the
  full music tile alone take the small clock (2); at 497 dp both keep the big one.
- **L8**: the system clock is a `MATCH_SYSTEM_ONLY` handler of `ACTION_SHOW_ALARMS` (`PinLockRuntime.systemClockApp`),
  never the next alarm's creator - with Fossify holding the next alarm at process start the system clock's own alarms
  kept the exemption.
- **Nits**: the tile's glyph is Material Symbols `timer` (`clock_glyph_timer`, and `clock_glyph_stop` for Stopp) through
  `scripts/material-symbols.sh`'s new `CLOCK_GLYPHS`; `ClockReader`'s doc names `LockGlanceGuardTest`.
- **Left as is** (the user's call, the coordinator's word): L6, the compact music tile's time line.
- **Device checks added**: a 2-minute timer ending with the screen LOCKED rings; the tile shows "Tiden er ute" with
  Stopp; Stopp silences it and the tile goes; the lock never moved. The stopwatch running with Fossify in the background
  shows "Stoppeklokke" counting up; an alarm ringing shows no tile (its own screen, the exemption).

