# Code QA - launcher round 6, part B (r6 §2, design 41's lock button, design 42)

- Commits: `aa5a4940..9f7b5c0c` on branch `r6-b` - phase 1 `3a8cdc1f` (code) + `cac2df84` (docs): r6 §2, the music
  tile rendered alone, the time line, design 41's lock button behind `TorchPort`; phase 2 `dc4f62bb` (code) +
  `9f7b5c0c` (docs): design 42, Fossify Clock as the clock app, plus the addition of Fossify as a PIN-lock helper.
- Read: root and launcher `CLAUDE.md` (PIN lock, glance page, lock-task helpers, camera lock, listener, look);
  `r6-launcher-small-items.md` §2 and its status, `41-flashlight.md` (B's status), `42-lock-clock.md` (whole);
  `qa-r6-launcher-design.md` r6-4…r6-7, 41-4, 42-2…42-6; the full diff of every file in the brief.
- Ran: `testDebugUnitTest -PwarningsAsErrors=true`: **BUILD SUCCESSFUL, 1416 tests, 0 failures, 0 errors, 0
  skipped**. Then 11 one-line mutations against the targeted tests (`*Glance*`, `*Clock*`, `*MusicLock*`,
  `*LockMedia*`, `*RefrontPolicy*`, `*LockTaskHelpers*`, `*PinLockTask*`: 121 tests), each reverted. The tree is clean.
- Signer: downloaded `clock-10-foss-release.apk` from GitHub `FossifyOrg/Clock` release 1.6.0 (the latest,
  2026-01-30; file SHA-256 `43cf9f0e...dca079`, the same as the release's digest) into its own empty scratch directory.
  `apksigner verify --print-certs` (build-tools 36.0.0): `C=IN, O=Fossify, CN=Naveen Singh`, SHA-256
  **`affdb124d3f4720c2f98dbca9eacba0514fba4306e20a2786c861c3c0d6ff292`**. It **matches** `ClockApp.SIGNER_SHA256`.
  I also read the manifest (`aapt2 dump xmltree`) and the dex strings. Nothing from that directory was run.

## Findings

**1 (Medium) - `ClockReader` takes every ongoing Fossify notification, not just the timer's.**
`lock/ClockTile.kt:67` (`clockCandidate` = package && verified && ongoing), `badges/ClockReader.kt:58`. Fossify's
manifest declares three foreground services: `AlarmService`, `TimerService` and `StopwatchService` (all
`specialUse`). Each one must post an ongoing notification. The dex names their channels: `simple_alarm_timer` (the
probe's timer), `simple_alarm_stopwatch`, `Alarm_Channel` and `simple_timer_channel_<id>`. The probe's "a running
stopwatch posts nothing" is most likely true only while Fossify is in front. Once the app goes to the background,
`StopwatchService` posts its elapsed time, and the lock then shows a **"Timer" tile counting *down*** from a time that
counts up: it jumps back at every update. The ringing alarm's foreground notification would show as "Timer" with the
alarm's label. A label such as "7:30" parses as a time and counts down. This is wrong information on the lock, not a
security hole: it is still only text, cleaned, from the pinned app.
Fix: pass `sbn.notification.channelId` into `clockCandidate` and also require `== "simple_alarm_timer"` (one pinned
constant next to the package). Add the stopwatch and alarm channels as negatives in `ClockTileTest`. Add a device
check to §4: the stopwatch with Fossify in the background, and an alarm ringing.

**2 (Medium) - the timer's end over the lock is claimed but implemented nowhere.** `docs/design/42-lock-clock.md` §4
and the status say "the timer's end removes the tile (Fossify's alarm screen shows over the lock through the
exemption)". The exemption (`rememberAlarm`, `RefrontPolicy.kt:131`) only covers `getNextAlarmClock`, which means
`setAlarmClock` alarms. Two cases:
- Fossify schedules its timers with `setExact*`. Its "time's up" screen can now start over the LOCKED page (the new
  helper pin, kiosk off). `refrontAction` doesn't yield for it, so the lock refronts at once. The timer then rings
  behind the lock, and the kid can't silence it without the PIN (no shade while LOCKED).
- Fossify uses `setAlarmClock` for timers. Then the alarm line shows the timer's end as "Alarm 14:32".

Neither case is tested or recorded. Fix: check it first on the emulator (`dumpsys alarm` with a timer running, then
the timer ending while LOCKED). Then either record the behaviour as accepted, or widen the exemption by the timer's
end. Don't hide it behind "Alarm".

**3 (Medium) - the signer checks in the glue have no guard (mutations d, e, i survive).** Three places decide
whether the signer counts, and no test pins any of them:
- `lock/ClockApp.kt:34`: `installed()` must go through `clockAppVerified`.
- `lock/PinLockRuntime.kt:1320`: `clockPackages()` passes `ClockApp.verified`.
- `server/AppEnforcer.kt:905`: the helper pin's `takeIf { ClockApp.installed(context) }`.

Each can be removed in one line with the whole suite green. The result would be an `org.fossify.clock` with any
signer, pinned in lock task over the LOCKED page and opening the alarm exemption. The pure halves (`clockAppVerified`,
`lockClockPackages`, `pinLockHelpers(clockApp)`) are tested. Rules (3) and (4) of the brief rest on these three call
sites. Fix: three source asserts in `LockGlanceGuardTest`, in the style of the existing
`clockCandidate(sbn.packageName, ClockApp.verified, ` check: the `installed` body, `clockPackages`' argument and the
`clockApp =` line.

**4 (Low) - the timer's per-second update re-renders the music tile and measures both tiles.**
`lock/GlancePanel.kt:310-326`. Fossify posts every second, so `ClockReader` notifies every second. `tileChanged` then
calls `music.show()`, which rebinds the item, entry, art check and play icon and calls `tick()`. It also measures
`lock_media_tile` and `lock_timer_tile` again. No layout is requested: the texts are `match_parent`, and the play
icon keeps its size. Still, it is more than "the clock tile alone" (§1.5). Fix: have the coalescer carry which source
changed (two flags), and render and measure only that tile.

**5 (Low) - nothing guards the session callback's route (mutation k survives).** `GlancePanel.kt:88`: wiring
`MusicLockPanel`'s callback back to `render()` leaves every test green, which is exactly the flicker r6 §2 removed.
Fix: a guard that both panels' callbacks are `tileChanges.request()`.

**6 (Low) - the compact tile's time line costs a row (the brief's rule 7).** The numbers are correct (finding list
below). The compact tile is the form `glanceFit` uses exactly when rows compete for the height, so its 17 dp line
spends the room the compact form exists to make. On the Jelly Star with 3-button navigation (473 dp), dropping the
line from the **compact** tile only (`tileCompact` 107 -> 90) gives back both losses:
- music + 3 rows: two rows again (435.6 of 449);
- music + 2 rows: mockup A's big clock (434).

The full tile keeps its line, and the bar's content description still says the time. This is the user's call (they
asked for the time on the lock's card). My recommendation: no line on the compact tile, or ask. As shipped it is
acceptable, not a defect.

**7 (Low) - the alarm line is not in `LockGlanceTest`'s model.** `GlancePanel.kt` adds `lock_glance_alarm` (2 dp + 13
sp x 1.4 = 20.2 dp) to the fit's `date` term whenever an alarm is set, which is the normal case for a kid with a
morning alarm. Every expected fit in `LockGlanceLayoutTest` assumes it is absent. Example: "no music, 4 rows" at 473
dp is 440 + 20.2 > 449, so the page drops to the small clock. Fix: one case per bar height with the alarm line in the
model (or a `date` term from the XML with it), so the cost is visible.

**8 (Low) - `systemClockPackage` is null for the process's life when Fossify holds the next alarm at start.**
`PinLockRuntime.kt:474`: `alarmAppPackage` prefers `nextAlarmClock`'s creator. When that creator is Fossify (not
`FLAG_SYSTEM`), the result is null, and the system clock's own alarms lose the exemption and the alarm line until the
next process start. The mechanism is older, but it becomes likely now that Fossify is the catalog's clock. Fix:
resolve the system clock from `ACTION_SHOW_ALARMS` with `FLAG_SYSTEM`, independent of the next alarm.

**9 (Nit) - the hourglass glyph.** `view_lock_glance.xml:327` uses `lock_glyph_hourglass`, the time-rule screen's own
glyph (`activity_lock.xml:46`, `LockGlyph.HOURGLASS`). A kid may read the timer tile as "time's up / time limit".
Material Symbols `timer`, added through `scripts/material-symbols.sh`, would say what it is.

**10 (Nit)** `badges/ClockReader.kt:20` names `ClockReaderGuardTest`, which doesn't exist (the checks are in
`LockGlanceGuardTest`). Also for the record: Fossify's manifest asks for `SYSTEM_ALERT_WINDOW` (not granted by
default; a grant would give it a background-start exemption) and has **no `INTERNET`** permission (confirmed).

## The rules, with the evidence

1. **Nothing opens over the lock without the keypad: holds.** The torch's click is exactly `torch?.toggle()`
   (`LockGlanceGuardTest`: the regex on the wiring, one `toggle(` in the panel, no camera APIs, `TorchPort`'s members
   pinned). `tapClock` captures nothing and steps `GlanceStageEvent.Row(target.takeIf { taken })`. After the code the
   opener's existing `LAUNCH` (`GlanceOpener.kt:129`) runs. `startActivity` still appears twice in `GlanceOpener`, and
   there is no new send. Mutations f and g are caught.
2. **Nothing foreign is rendered: holds.** `ClockReader` reads only `EXTRA_TITLE`/`EXTRA_TEXT` (guarded: two
   `getCharSequence`, no actions, intents, views or prefs). `ClockTile.parse` runs `cleanGlanceText` on both. Our
   layout draws them, with our own glyph and label.
3. **Only `org.fossify.clock`, only while verified: holds in the code**, `GlanceReader` untouched (not in the diff).
   But see finding 1 (which of Fossify's notifications) and finding 3 (the glue isn't guarded).
4. **The exemption and the helper pin: acceptable.** The pin (`pinLockHelpers(..., clockApp)`, `LockTaskHelpers.kt`)
   puts the **whole package** into the kiosk-off lock task while LOCKED. Any Fossify activity may start over the
   page, but only Fossify itself can start one:
   - LOCKED has no Home, Overview or shade (`PIN_LOCK_FEATURES_KIOSK_OFF`), and no other pinned helper starts Fossify.
   - The alarm screen (`AlarmActivity`) runs in its own task (`taskAffinity=".ReminderActivity"`, `singleTask`,
     `excludeFromRecents`). Dismissing or snoozing it can't reveal a `MainActivity` left in Fossify's main task.
   - The refront yields only within 3 min of a remembered `setAlarmClock` alarm (`ALARM_RING_MS`) and ends when the
     lock is back in front.

   Today's system clock (`alarmApp`, its alarm activity likewise in its own task) is treated the same way. Fossify has
   no internet. The helper is needed: without it, lock task refuses Fossify's full-screen alarm with the kiosk off. The
   one open path is the timer's end (finding 2). The pin and the exemption fail closed on the signer (pure tests), but
   the glue is unguarded (finding 3).
5. **The refit rule: holds.** `glanceRefitDue` covers came/went/height (mutation a is caught). `GlanceCoalescer`
   gives one render per frame (mutation b is caught). `MusicLockPanel.tick` sets only the bars, their descriptions and
   the two lines; the play icon, art and texts move to `render`. `ClockLockPanel.tick` sets only the time and the
   description. Gaps: findings 4 and 5.
6. **The time line never relayouts: the deviation is right.** `TextView.setText` -> `checkForRelayout()`: when the
   width isn't `WRAP_CONTENT` and the view has been laid out (`mRight - mLeft > 0`), it builds the new layout. If the
   layout's height is unchanged (`maxLines=1`, the same font), it calls `invalidate()` and returns without
   `requestLayout()`. A `wrap_content` width would request a layout for every new width, which is why the note wanted
   a fixed width. `match_parent` gives that with no code and follows font scale. The only `requestLayout` is the first
   set after GONE -> VISIBLE, which coincides with the refit anyway. `ImageView.setImageResource` with the same
   intrinsic size invalidates only. `tnum` matters only for the look now (no jitter), not for the layout.
7. **The numbers match the XML.**
   - `clockFull` = 18 + 2x12 + max(18, 13 x 1.4) + 2 + 24 x 1.4 + 13 x 1.4 = **114.0**.
   - `tileFull` = 211 + 6 + 12 x 1.4 = **233.8**.
   - `tileCompact` **107** (the column of 69 dp over the 52 dp art).
   - `fixed` **106** (`lock_glance_bottom` with its 2 dp margin, 48 dp).
   - `LockGlanceLayoutTest` derives them from the XML.

   The lost row is real (music + 3 rows -> 5 with 1 row, "+ 2"). See finding 6 for the judgment.
8. **The signer matches** (above). Not High.
9. **The look: holds.** The torch uses `bg_music_control` with a `kid_core` glyph (off) and the peach `bg_music_play`
   with a `kid_ground` glyph (lit). The timer tile uses `bg_music_tile`, a `kid_accent` glyph, `kid_ink` time and
   `kid_core_dim` text. No animator or transition anywhere in the diff. The glyph choice: finding 9.
10. **Logs: holds.** `ClockReader` logs the exception class only (guarded). `ClockApp` logs the package and a
    boolean. The opener logs the package as it does for rows. `ClockTile.toString` = `ClockTile(time=…)`.
    `GlanceTarget.ClockApp` prints its name. No key, title or text is logged.

The design QA findings landed in the form the rewritten note chose:
- **r6-4:** `glanceRefitDue`.
- **r6-5:** the numbers re-pinned, the compact line under its bar, the bar >= 120 dp at 320 dp.
- **r6-6:** live views only.
- **r6-7:** the music app's recording is owed as a device check.
- **41-4:** `lock_glance_bottom`, `fixed` from the row, the order test.
- **42-2:** no action is fired at all.
- **42-3:** its own `ClockReader`, `clockPackages()`.
- **42-4:** `clockFull` and the candidate order.
- **42-5:** `GlanceTarget.ClockApp` in `glanceUsableUnderLock` through `appPackage`.
- **42-6:** `cleanGlanceText`, the weekday, tabular figures.

On the other deviations:
- `formatUntil` for the alarm (AM/PM kept in 12 h) is right for a time of day that isn't a clock face.
- The weekday uses the same locale path as the page's date and row times.
- `glanceUsableUnderLock`'s rewrite keeps the old behaviour for both old targets.
- Nothing widens the scope beyond the notes except the helper pin, and that addition is needed and acceptable (rule 4).

## Mutations

| # | Mutation (one line, reverted) | Result |
|---|---|---|
| a | `glanceRefitDue` ignores a height change (`fittedPx == null` only) | caught - `LockGlanceTest` refit rule |
| b | `GlanceCoalescer` without `if (pending) return` (two renders a frame) | caught - `LockGlanceTest` coalescer |
| c | `ClockReader.isTimer` passes `ClockApp.PACKAGE` instead of `sbn.packageName` (any package) | caught - `LockGlanceGuardTest` clock reader |
| d | `ClockApp.installed` = `true \|\| clockAppVerified(...)` (signer ignored) | **NOT caught** (finding 3) |
| e | `clockPackages()` passes `true` for verified | **NOT caught** (finding 3) |
| f | the torch's click also starts the camera activity | caught - `LockGlanceGuardTest` (2 tests) |
| g | `tapClock` starts Fossify's launch intent before the keypad | caught - `LockGlanceGuardTest` "opens nothing" |
| h | `glanceFit`'s clock candidates skip the compact music tile (timer dropped first) | caught - `LockGlanceTest` timer order |
| i | `resolvePinLockHelpers` pins `org.fossify.clock` without `installed` | **NOT caught** (finding 3) |
| j | `clockSeconds` uses an unanchored `find` (accepts "11:12 abc") | caught - `ClockTileTest` garbage |
| k | (extra) the music callback goes back to `render()` (the whole page) | **NOT caught** (finding 5) |

## Verdict

**Approved with fixes.** No High finding, and the pinned signer is correct. Before the merge: findings 1 (the timer
channel filter) and 3 (three guard asserts) are small. Finding 2 needs the emulator check first, then a recorded
decision. Lows 4-8 can go to the backlog; finding 6 is a question for the user.
