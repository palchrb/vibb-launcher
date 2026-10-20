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
