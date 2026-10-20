# 42 - The clock on the lock: a timer tile from the system clock, the next alarm (proposal)

Asked 2026-10-19/20. The user: "ideelt sett en tile slik som iphone har i dag, som viser nedtelling etc og en knapp
for å slå av og på eller noe sånne greier. når man trykker på den så vil den i såfall åpne appen"; and "bruker
systemklokken for å ikke skape noe mulige angrepsflater" - no Fossify exemption, no hosted widgets. Launcher only; no
API change. Builds on design 25 (the glance page, `GlanceReader`/`GlanceRows`/`GlanceStore`, `glanceFit`), design 28
(the music tile, `MusicLockPanel`) and `PinLockRuntime`'s system-clock rule (`systemClockPackage`: the alarm app's
package when it is a `FLAG_SYSTEM` app).

## 0. What the kid sees

On the glance page, between the date and the music tile:

- a line **"Alarm 07:00"** with the alarm glyph while the system clock has a next alarm (the clock's own alarm; one
  set by another app shows nothing, as the PIN lock's exemption already decides);
- a **tile in the music tile's style** while the system clock has a running timer or stopwatch: the glyph, the
  app's own title ("Timer", "Stoppeklokke" - whatever the notification says), the time large (counting down or up
  once a second), and at most two round buttons that are the notification's own actions with the app's own labels
  (Pause/Fortsett, Stopp/Nullstill). A tap on the tile itself goes the glance rows' way: the keypad first, then the
  clock app (`GlanceOpener`, design 25 §1.2's rule that a row never opens anything over the lock).

## 1. Decisions

1. **The source is the system clock's notification, read by the lock's listener** (`BadgeListenerService` ->
   `GlanceReader`): a `ClockReader` takes the ongoing notifications of `systemClockPackage` only. A notification
   qualifies when `extras[EXTRA_SHOW_CHRONOMETER]` is true: `EXTRA_CHRONOMETER_COUNT_DOWN` true = a timer whose end is
   `when`; false = a stopwatch whose start is `when`. Several timers: the one ending first. A paused timer (the clock
   app posts it without the chronometer, with the remaining time in its text) shows the tile with the app's text
   line and the Resume action, no ticking. The tile goes when the notification goes (`removed`), like a row.
2. **The buttons are the notification's actions** (`notification.actions`, the first two with a `PendingIntent`):
   their `title` cleaned like a row's text (`GlanceRows.cleanText`, length-capped), fired with
   `actionIntent.send()` - what a notification listener may do, the same as tapping the action in the shade; nothing
   starts over the lock (an action that would start an activity is the clock app's own decision, as in the shade; the
   check in §4 records what the stock clock does). No icons from the app: our round buttons, the labels only.
3. **The time is ours**: `ClockTile.remaining(end, now)`/`elapsed(start, now)` formatted as the lock's music tile
   formats positions (`clockText`), ticked once a second only while the page shows the tile (the music tile's ticker
   rule, `glanceTicks`); never the app's text while the chronometer runs.
4. **The next-alarm line**: `AlarmManager.nextAlarmClock`, shown only when `showIntent.creatorPackage ==
   systemClockPackage` (`PinLockRuntime`'s rule), the time in the lock's clock format (`ClockFormat`), refreshed on
   `ACTION_NEXT_ALARM_CLOCK_CHANGED` and at every page show. No tap.
5. **Layout**: `view_lock_glance.xml` gets `lock_glance_alarm` (a line under the date) and `lock_timer_tile` (the
   music tile's card: glyph, title, the big time, a row of up to two buttons); `glanceFit` gets the tile's height
   like the planned time-rule card (it may push rows into "+ N varsler"), with the alarm line in `date`'s term. The
   music tile and the timer tile can both show; the compact music tile is preferred when both are up and the page is
   short (`glanceFit` decides, as for rows).
6. **Rendering follows round 6's rule for the music tile** (`r6-launcher-small-items.md` §2): a clock change renders
   the clock tile alone; the page's fit runs only when the tile came or went.
7. **System clock only**: the emulator's is `com.google.android.deskclock`; the Jelly Star's package is read on the
   device (§4). Fossify Clock gets no exemption (the user's call).

## 2. Security

Nothing from another app is rendered as a view: titles and labels are strings cleaned like the glance rows', the
time is computed by us, the actions are the notification's own `PendingIntent`s fired as the shade would. Only the
system clock package is read. `LockGlanceGuardTest`: the tile's tap leads to the keypad; no button starts an
activity of ours; a notification without a chronometer and without actions shows nothing. Redacted `toString`s as
the rows have.

## 3. Tests (required)

`ClockTileTest` (JVM, pure): which notification qualifies (package, ongoing, chronometer, count-down), the earliest
timer wins, remaining/elapsed maths and the format, the paused-timer shape, at most two actions with cleaned labels,
the alarm line's condition (creator package, no alarm). `GlanceFitTest`: the tile's height term and the compact
music tile under pressure. `LockGlanceGuardTest` as in §2.

## 4. Device checks

Emulator (Google's clock): a 2-minute timer from the clock app, then the lock: the tile counts down, Pause/Resume
work, the timer's end removes the tile (the clock's alarm screen shows as today); a stopwatch; an alarm set for
later: the alarm line; a tap on the tile: the keypad, then the clock app. The Jelly Star: its clock package named,
the same checks, the font scale at the largest setting (the time still fits, `FitTest`'s widths).
