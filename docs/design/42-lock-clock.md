# 42 - The clock on the lock: a timer tile from the system clock, the next alarm (proposal)

Asked 2026-10-19/20. The user: "ideelt sett en tile slik som iphone har i dag, som viser nedtelling etc og en knapp
for å slå av og på eller noe sånne greier. når man trykker på den så vil den i såfall åpne appen"; and "bruker
systemklokken for å ikke skape noe mulige angrepsflater" - no Fossify exemption, no hosted widgets. Launcher only; no
API change. Builds on design 25 (the glance page, `GlanceRows`/`GlanceStore`, `glanceFit`), design 28 (the music tile,
`MusicLockPanel`) and `PinLockRuntime`'s system-clock rule (`systemClockPackage`: the alarm app's package when it is a
`FLAG_SYSTEM` app).

## 0. What the kid sees

On the glance page, between the date and the music tile:

- a line **"Alarm 07:00"** with the alarm glyph while the system clock has a next alarm (the clock's own alarm; one
  set by another app shows nothing, as the PIN lock's exemption already decides); more than 24 h away it carries the
  weekday: "Alarm tor 07:00";
- a **tile in the music tile's style** while the system clock has a running timer or stopwatch: the glyph, the
  app's own title ("Timer", "Stoppeklokke" - whatever the notification says), the time large (counting down or up
  once a second), and at most two round buttons that are the notification's own non-activity actions with the app's
  own labels (Pause/Fortsett, Stopp/Nullstill). A tap on the tile itself goes the glance rows' way: the keypad first,
  then the clock app (`GlanceOpener`, design 25 §1.2's rule that a row never opens anything over the lock).

## 0b. Step 0 - the probe (before anything else)

On the emulator (Google's clock) and the Jelly Star, with a timer and a stopwatch running (and a paused timer), run
`adb shell dumpsys notification --noredact` and record in this note: whether the ongoing notification carries
`android.showChronometer` / `android.chronometerCountDown` and `when`, whether the content view is custom, its actions
(titles, and whether each PendingIntent is an activity or a service/broadcast), and the exact package. The design is
built only if the chronometer extras are there. If the clock draws its own view (as AOSP DeskClock does), 42 falls
back to the alarm line alone and the timer tile is dropped - the user decides then. The implementer does not start 42
before the probe's result is in this note.

## 1. Decisions

1. **The source is the system clock's notification, read by a `ClockReader`** of its own, wired from
   `BadgeListenerService`'s posted/removed/connected/disconnected like `VoipCallReader` (own allowlist guard test).
   `GlanceReader` cannot be used: it drops unticked packages and `glanceCandidate` drops every ongoing notification.
   `ClockReader` filters on `systemClockPackage` first, so `PinLockRuntime.systemClockPackage` is exposed read-only.
   A notification qualifies when `extras[EXTRA_SHOW_CHRONOMETER]` is true (as the probe confirms): `EXTRA_CHRONOMETER_COUNT_DOWN`
   true = a timer whose end is `when`; false = a stopwatch whose start is `when`. Several timers: the one ending first.
   A paused timer (posted without the chronometer, the remaining time in its text) shows the tile with the app's text
   line and the Resume action, no ticking. The tile goes when the notification goes (`removed`), like a row.
2. **The buttons are the notification's actions, in the clock's order** (what the shade shows): the first two whose
   `PendingIntent` is not an activity (`isActivity == false`), titles cleaned with `cleanGlanceText(raw, max)`. They are Taking the first two in the clock's order means "Pause, +1 min" with no "Stop" can result; that is accepted - the tile mirrors the shade.
   sent from their own file, `lock/ClockActions.kt`, with `ActivityOptions.makeBasic().
   setPendingIntentBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_DENIED)` (API 34+), so nothing can start
   over the LOCKED page. A guard test pins that single send, the `isActivity` filter and the system-clock package, and
   forbids `.send(` in `GlancePanel`. Whether a service action sent by us reaches a clock app in the background is a
   device check (§4); "no button" is the fallback. No icons from the app: our round buttons, the labels only.
3. **The time is ours**: `ClockTile.remaining(end, now)`/`elapsed(start, now)` formatted as the lock's music tile
   formats positions (`clockText`), ticked once a second only while the page shows the tile (`glanceTicks`); never the
   app's text while the chronometer runs. The ticking text has a fixed width with tabular figures
   (`fontFeatureSettings "tnum"`, a width measured for "88:88:88"), so a tick never asks the page for a layout.
4. **The next-alarm line**: `AlarmManager.nextAlarmClock`, shown only when `showIntent.creatorPackage ==
   systemClockPackage` (`PinLockRuntime`'s rule), the time in the lock's clock format (`ClockFormat`), the weekday when
   more than 24 h away, refreshed on `ACTION_NEXT_ALARM_CLOCK_CHANGED` and at every page show. No tap.
5. **Layout and fit**: `view_lock_glance.xml` gets `lock_glance_alarm` (a line under the date, in `date`'s term) and
   `lock_timer_tile` (the music tile's card: glyph, title, the big time, a row of up to two buttons). `GlanceHeights`
   gets two clock-tile terms (`clockFull`, `clockNoButtons`, measured from the XML like `tileFull`), and `glanceFit`
   tries the candidates in this order, first that fits wins: (1) both tiles full and every row; (2) the music tile
   compact; (3) rows folded into "+ N varsler"; (4) the clock tile without its buttons; (5) the clock tile gone (then
   today's order for the music tile alone). A running timer is the thing the kid came to see, so the clock tile
   outlives the music tile's full size and the rows.
6. **The tile's tap target**: a new `GlanceTarget.ClockApp` (the system clock's launch intent), handled by the opener's
   existing one-send-two-starts contract (unchanged counts); its case goes in `LockGlanceGuardTest` (the opener test)
   and in `glanceUsableUnderLock` (the clock app under a time rule).
7. **Rendering follows round 6's rule** (`r6-launcher-small-items.md` §2): a clock change renders the clock tile
   alone; the page refits when the tile comes or goes or its measured height changes (the buttons appear, the paused
   text wraps), never per tick.
8. **System clock only**: the emulator's is `com.google.android.deskclock`; the Jelly Star's package is read in the
   probe. Fossify Clock gets no exemption (the user's call).

## 2. Security

Nothing from another app is rendered as a view: titles and labels are strings cleaned like the glance rows', the
time is computed by us, only non-activity actions are sent, with background activity start denied. Only the system
clock package is read. `LockGlanceGuardTest`: the tile's tap leads to the keypad; no button starts an activity; a
notification without a chronometer and without actions shows nothing. Redacted `toString`s as the rows have.

## 3. Tests (required)

`ClockTileTest` (JVM, pure): which notification qualifies (package, ongoing, chronometer, count-down), the earliest
timer wins, remaining/elapsed maths and the format, the paused-timer shape, at most two non-activity actions in the
clock's order with cleaned labels, the alarm line's condition (creator package, no alarm) and its weekday rule.
`LockGlanceTest` (there is no `GlanceFitTest`): the clock-tile term and candidate order in the pure fit, the layout
test pinning the XML model (with the existing `fixed` 106, `tileFull` 211, `tileCompact` 90 re-derived once, in one
fit change together with design 41's bottom row and r6 §2's time line - the implementer of 41/r6 re-pins them first,
42 adds its terms on top), 320/360/411 dp, 320 dp at the largest font scale. `LockGlanceGuardTest`: as in §2, the
`ClockReader` allowlist, the `ClockActions` send.

## 4. Device checks

Emulator (Google's clock): a 2-minute timer from the clock app, then the lock: the tile counts down, Pause/Resume
work (a service action sent by us reaches the clock app in the background, or no button), the timer's end removes the
tile (the clock's alarm screen shows as today); a stopwatch; an alarm set for later: the alarm line, and with the
weekday past 24 h; a tap on the tile: the keypad, then the clock app. The Jelly Star: the same checks, the font scale
at the largest setting (the time still fits, `LockGlanceTest`'s widths).

## Revision (2026-10-20, after qa-r6-launcher-design.md)

- 42-1: §0b, the probe first; the design is built only if the chronometer extras exist, else the alarm line alone.
- 42-2: only non-activity actions, sent from `lock/ClockActions.kt` with background activity start denied, guard test (§1.2).
- 42-3: its own `ClockReader` wired like `VoipCallReader`; `systemClockPackage` exposed read-only (§1.1).
- 42-4: the clock-tile term and the candidate order, the clock tile stays and the music tile goes compact first (§1.5).
- 42-5: `GlanceTarget.ClockApp` through the opener's one-send-two-starts contract, guard case named (§1.6).
- 42-6: `cleanGlanceText`, the clock's first two actions in its order, the weekday on the alarm line, fixed-width tabular time (§0, §1.2-1.4).
- Tests: `LockGlanceTest`, not `GlanceFitTest` (§3).
