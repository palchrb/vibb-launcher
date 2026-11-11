# Code review: design 78 step 1 (the kid's light mode, Kalender behind a debug row)

Reviewed 2026-11-10: commit 89f062b6 on `kid-look-1` (one commit on bc547c84), by reading, one full unit-test run and
15 mutations. Nothing was run on an emulator or a phone. The design is `docs/design/78-kid-light-mode.md`, its QA
`qa-78-design.md`.

## Verdict

Merge after a small fix round. No High: no crash, no wrong behaviour a kid or a parent meets, and nothing on a release
build can pick Lys. The round is one release difference on Kalender that no note lists (M1), two test gaps where five
mutations of the Android half survive the whole suite (M2, M3), and Low tidying.

The full run on the commit as it was: `BUILD SUCCESSFUL`, 244 suites, 2461 tests, 0 failures, 0 errors, 0 skipped.

## Findings

**M1 - Medium. Kalender's theme changes two things in Mørk that the notes do not list.** `KidPageTheme`
(`res/values/styles.xml`) sets `android:enforceNavigationBarContrast=false`: at bc547c84 Kalender had the default
(true), so with 3-button navigation the system's scrim behind the buttons leaves Kalender in Mørk too (nothing changes
with gesture navigation). And `android:windowBackground` / `windowSplashScreenBackground` are `kid_ground` where they
were AppCompat's default (#303030 dark, light on a system-light phone) - seen only behind the layout: the starting
window and transitions. Read, not seen running. Every other night value was compared and is today's: each
`kid_page_*` night token resolves to exactly the colour or literal it replaced, alpha included, and the header's
`android:tint="@color/kid_page_ink"` is identity at night.

**M2 - Medium. The Android half of `PageLook` has no test.** `atUnlockedStart`, `choose`, `onHomeResumed` and the
mode-to-night-value mapping (three places). Dropping `applied = choice` in `onHomeResumed` (Home then reruns the change
and closes Kalender's task at every resume) and reading `choice = applied` at the unlocked start (a waiting change is
lost at the next process start) both survive the suite; no test names a night constant for `PageLook`.

**M3 - Medium. Nothing holds that a page wires itself up.** `KidPageActivity`'s `applyStyle(R.style.PageColors, true)`
and its four registry calls: making the overlay ineffective and removing `pageTaskStopped()` from `onStop` both survive
the suite. `KidPaletteTest` checks what `PageColors` holds, `PageManifestTest` only `: KidPageActivity()`.

**L1 - Low. A failed system call is handled in one place of three.** `atUnlockedStart` is in a try/catch but assigns
`applied` and `choice` only after the system call, so a throw leaves both `DARK` in memory while `kid_look` may say
light; `choose` and `onHomeResumed` are unguarded (a throw crashes kid Settings, or Home's `onResume` at every resume
while a change waits). No known reason for the call to throw on a phone.

**L2 - Low. `PageTokensScanTest`'s layout list is hand-kept and misses `item_day_page`.** A literal colour added there
survives.

**L3 - Low. The look row's click listeners are set in release too.** Only the visibility is behind
`BuildConfig.DEBUG`. No way to reach them in release was found (gone views take no touch, focus or accessibility).

**L4 - Low. A crash inside a change leaves Kalender's task open, and the note says Home's resume finishes it.** After
`runChange`'s commit `choice == applied`, so `onHomeResumed` returns at once; a death between the commit and
`closePageTasks` leaves the task in Recents with an old-look snapshot (the page itself is recreated in the new look).
`docs/notes/launcher/ui-polish.md`'s sentence is wrong. Unverified on a device.

**L5 - Low, unverified. Home's resume can close a Kalender task that was just opened.** `onHomeResumed` skips
`mayChange` by design; a waiting change with Home resuming between an open and the page's creation removes the new
task with the old. Debug only in step 1; for step 2.

**L6 - Low. The caption is untrue in step 1, and the design's §7 string table is stale.** The caption names Meldinger,
the phone book and the Settings lists; only Kalender follows.

**L7 - Low. The unlocked-start binder call sits before `val setupStart`,** so one synchronous `UiModeManager` call on
every unlock-to-Home path is outside the logged setup time.

**L8 - Low. `docs/testing/emulator.md` §6ah is runnable on `jelly36` but has gaps:** (a) no assertion that lock task
is on when `finishAndRemoveTask` runs on Kalender's task; (b) a change while Kalender's task lives only in Recents
after its process died; (c) "can't be reached by hand" is too strong - "not reliably"; (d) B.7's "reboot within a few
seconds" cannot hit the window it is meant for; (e) 3-button navigation on Kalender in Mørk (M1); (f) the unlocked
start's timing (L7).

**L9 - Low. Three overlapping tests.** `KidPaletteTest`'s exact day map repeats `values-notnight/colors_kid_page.xml`
(the contrast pairs are what matter); `PageTokensScanTest`'s "both looks define the same page tokens" is implied by the
two exact maps; `PickerThemeTest`'s twin equality overlaps `SplashThemeTest`'s drops-no-key rule.

No finding on: the unlocked start (runs on every unlocked start, before any non-direct-boot component; nothing reads
`kid_look` before unlock, `:bootcover` never touches `PageLook`); the night pin (all 25 activities are a page, pinned
or framework-themed; no application-context inflation, overlays, RemoteViews or WebView); `uiMode` (only
`PinLockActivity` overrides `onConfigurationChanged`, for font scale); the change itself beyond L4 and L5; licensing
(nothing under `ui/list/` or `apps/`, no Gradle or verification-metadata change); the docs beyond L4's sentence. The
largest release change is D2, designed and stated: on a phone with the system dark theme off, every non-page screen now
draws AppCompat's own bits in night form.

## The design's dated departures

All judged right as built. Two want the user's eye before step 2 shows the row: the "+" disc at 2.3:1 on paper, and
today's unselected number at 2.6:1 in the Lys date picker (to PLAN).

## Mutations

| # | Broken | Result |
|---|---|---|
| M1 | `unlockedStart` skips the system call on the first run | caught, `PageLookTest` |
| M2 | `uiMode` dropped from `HomeActivity` | caught, `PageManifestTest` |
| M3 | `@color/kid_ink` back in `item_day_event.xml` | caught, `PageTokensScanTest` |
| M4 | night pin removed from `InCallActivity` | caught, `PageLookScanTest` |
| M5 | day `kid_page_dim` below contrast | caught, `KidPaletteTest` and `PickerThemeTest` |
| M6 | `runChange` calls the system before the store | caught, `PageLookTest` |
| M7 | the row shown without the `DEBUG` check | caught, `PageLookScanTest` |
| M8 | `closesTask` matches any task | caught, `PageLookTest` |
| M11 | night `kid_page_dim` pointed at `kid_ink` | caught, `KidPaletteTest` |
| M12 | `mayChange` allows one started page | caught, `PageLookTest` |
| M9 | `onHomeResumed` drops `applied = choice` | **survives** (finding M2) |
| M13 | `atUnlockedStart` does not read the stored choice | **survives** (finding M2) |
| M14 | the page's `PageColors` overlay made ineffective | **survives** (finding M3) |
| M15 | `pageTaskStopped()` removed from `onStop` | **survives** (finding M3) |
| M10 | a literal colour in `item_day_page.xml` | **survives** (L2) |

## Only the emulator pass can settle

The starting window per look and way in, with one `onCreate` and no relaunch; that the pinned screens stay night with
no relaunch when the app's or the system's night mode flips (an unlock with the PIN lock showing on a system-light
phone included); `finishAndRemoveTask` on Kalender's task under lock task; D2's bits on a system-light phone and the
link-blocked notice; Kalender in Mørk with 3-button navigation; the bars with the keyboard up in Lys; the pickers,
dialogs, the "+" disc and the late status-bar tint in Lys; TalkBack on the row; font scale 1.3; the unlocked start's
cost; Android 16's "make more apps dark", Android 14's bar colours and the Jelly Star's own build.

## Fixes

Fix round, 2026-11-10, branch `kid-look-1`: everything is in 1c6e1564, this section in the commit after it. Every
finding was checked against the code first; one half of M1 was refuted (below), the rest stood. One full gated run on
the fixed tree: `BUILD SUCCESSFUL`, 244 suites, 2464 tests, 0 failures, 0 errors, 0 skipped. Nothing was run on an
emulator or a phone.

Emulator pass, 2026-11-11 (`docs/testing/emulator.md` §6ah, "The run of 2026-11-11"; `jelly36`, 163aceb5, stopped
before B.9 when the host killed the emulator for memory) - what it says about M1, L4 and L5: **nothing yet**. **M1**
was not seen: no screen of this build was looked at with 3-button navigation, and the window ground behind Kalender in
Mørk was not compared; only main's before pictures exist (no scrim band to see on main's 3-button bar on `jelly36`, the
buttons dark grey with the system light). **L4** (the old look's card in Recents after a cut-short change) and **L5**
(Home's resume closing a just-opened task while a change waits) need B.9-B.11 and the waiting change, which were not
run. Of the rest under "Only the emulator pass can settle" the pass confirmed: one `onCreate` and no relaunch per
open, the starting window per look and way in, the change with Kalender behind and its task removed under lock task,
no relaunch of Kalender or Home on a flip of the system's night mode, and the unlocked start's cost (L7's two lines:
14-405 ms beside 372-14231 ms). It also found what no review read: the app's night mode against the launcher's own
language (the design's §5.3, the line of 2026-11-11).

Source reading and fix, 2026-11-11 (AOSP `frameworks/base`, tag `android-16.0.0_r1`; no emulator; the design's §5.3 has
the cause with its files and methods) - one finding fixed, one new and open:

- **The launcher's language after a reboot, and its refused repair** (the pass's finding; Medium: a phone whose own
  language is not the launcher's shows English until Home resumes with no task of the launcher's own behind it) -
  **fixed in the mechanism, with a rest**: the language is asked for again at every unlocked start, right after the
  night mode, and a switch at Home's resume that the system refused closes Kalender's and Meldinger's tasks and asks
  once more (`LauncherLanguage` over a host in `ui/LauncherLocale.kt`, `LauncherLocales`, `Application.initUnlocked`;
  six tests more in `LauncherLocaleTest`, nine in all). The last gated run: `BUILD SUCCESSFUL`, 244 suites, 2470 tests,
  0 failures, 0 errors, 0 skipped. The rest (Low; inferred, not seen): a parent's change of language while the phone is
  up can stay refused over plain Home resumes in a process whose last call got through under the PIN lock, a page or a
  call, until a Home resume right after one of them or the next process start; the screens stay whole in the old
  language meanwhile. The reboot's loss does not depend on it: the unlocked start's ask comes before any activity or
  right after the night mode's call under the same one. **Not seen running**: §6ah's B.13-B.15 are its steps.
- **N1 (Medium for step 2, nothing in step 1's release builds; inferred from the source, not seen): a change of look
  can be refused like the language was.** `setApplicationNightMode` goes through the same
  `PackageConfigurationUpdaterImpl.updateConfig`, so it is refused when the activity the process follows is of another
  type (home or standard) than at the last call that got through. A process whose last call got through under a
  standard-type activity - started again under a page, the PIN lock or the call screen, or a language switch at Home's
  resume while the PIN lock was not yet destroyed - then refuses a change from kid Settings (Home's task); so can a
  waiting change at Home's resume right after a page was left. `LookState` has stored the look and closed the tasks by
  then, and the host logs `The app's night mode wasn't set`: the pages draw in the new look, their starting windows in
  the old one until the next process start - a frame of the other look, which step 0's ground rules out. Step 1's
  release builds have no change of look (the row is debug-only), so nothing ships with it; it has to be settled before
  step 2 shows the row. Not fixed here: a gate on the app's side alone does not settle it, and each way that would
  (never asking under a standard-type activity, the unlocked start included; the language in each activity's
  `attachBaseContext`; another mechanism than the per-app night mode) has a cost the design must weigh - its §5.3,
  "Open, for step 2". §6ah's B.16 is the step that shows it or clears it.

The language fix's code QA, 2026-11-11 (a fresh reviewer, 7b15ee70 read against the fetched AOSP files; no emulator):
the three source claims confirmed (the write record without the languages, the refusal by the followed activity's
type, a refused call storing no language), no High, two Medium, four Low. All handled in the commit after 7b15ee70;
the gated run on it: `BUILD SUCCESSFUL`, 244 suites, 2473 tests, 0 failures, 0 errors, 0 skipped
(`LauncherLocaleTest` 12, `PageLookScanTest` 3). **Nothing of it seen running** when this was written.

- **M1 (inferred from AOSP): the second ask inside the same Home resume is probably refused when the kid comes Home
  straight from the page.** The process chooses the activity it follows again only when one is added or removed
  (`WindowProcessController.java:844, 870, 877`); a paused, still-visible page is only marked finishing by the task's
  removal (`Task.java:1664-1668`, `ActivityRecord.java:3744-3812, 3837-3862`) and destroyed once the next activity
  reports idle. So: two warnings, the card gone, no language. **Fixed**: a resume that ends refused asks one last time
  `LANGUAGE_RETRY_DELAY_MS` (two seconds) later while Home is still in front and no call is live - one ask, dropped at
  Home's `onPause` (`LauncherLanguage.onRetry`, the host's `retryLater`/`cancelRetry` on the main handler). Two
  seconds: past the transition and Home's idle report, short of Android's ten-second fallback; the pass measures it.
  Wider than the finding's wording: it follows every resume that ends refused, with or without a close (the PIN lock
  just left is the same wait) - the design's §5.3 has the dated line.
- **M2 (read in the code): a change that stays refused closed Kalender's and Meldinger's tasks at every Home
  resume.** Not bounded on M1's path, nor with a standard-type activity of the launcher's outside the two page tasks
  alive (the phone book opened by a `tel:` link in another app's task). **Fixed**: `LauncherLanguage` remembers the
  wanted language for which a close was followed by a refused late ask and does not close again for it; it keeps
  asking at each resume, and an ask that gets through or another wanted language clears it. The process's one
  `LauncherLanguage` is kept in `LauncherLocales`. A rest, stated in the design: a late ask that never ran (Home left
  within the two seconds) decides nothing, so with such a lingering activity and a kid who leaves Home that fast every
  time the close can repeat; held the other way a language could stay stuck behind a page opened again.
- **L1: no test held a refused unlocked start** (the mutation "a refused unlocked-start ask closes the page tasks and
  asks again" survived). **Fixed**: `a refused unlocked start asks once, closes nothing and leaves the rest to Home's
  resume`.
- **L2: the language call's time was in neither log line.** **Fixed**: `I Application: The launcher's language took N
  ms`, between the look's line and `Unlocked setup took` (§6ah B.12).
- **L3 (inferred): the application's own texts on the boot whose record has no languages** - the new configuration
  reaches the process after `Application.onCreate` has begun, so the notification channels' names and other texts from
  the application's context may be in the phone's language until the next process start. **Not fixed before it is
  seen**; the pass looks for it (§6ah), and the small fix would be the channels once more on `ACTION_LOCALE_CHANGED`.
- **L4, wording** - **fixed**: `Application.initUnlocked`'s comment (the second call is refused only if the kind of
  activity the process follows changed between the two binder calls; caught, repaired at Home's resume) and
  `LanguageHost.ask`'s (a refused call keeps the language nowhere; `LocaleManagerService` does write its backup
  helper's modification info before the commit, which is not the language).
- **The order "night mode, then language"** was load-bearing and under no test: `PageLookScanTest` now holds it (the
  language's ask once in `Application.kt`, after the look's and before the rest of the unlocked setup).

- **M1** (1c6e1564): `KidPageTheme`'s `android:enforceNavigationBarContrast` is `@bool/kid_page_nav_contrast` - true in
  `values/`, false in `values-notnight/` - so Kalender in Mørk with 3-button navigation keeps the system's scrim;
  `KidPaletteTest`'s theme test pins the item and both bools files. The window's two grounds are **no difference** in
  Mørk, and the finding's "#303030" was not what bc547c84 had: `launcherBaseTheme` already set
  `android:windowSplashScreenBackground` to `kid_ground` and `android:colorBackground` to `kid_ground`, the framework's
  `Theme.Material` has `windowBackground = ?attr/colorBackground` (android-36 `themes_material.xml`), and AppCompat
  1.7.1 adds no window background on API 21+. So nothing was written down as a release difference; the design's §5.2
  and ui-polish.md's "What this changes on a release build" say what is true, with "read from the resources, not seen
  running".
- **M2** (1c6e1564): the look's rules are a pure class, `LookState`, over `LookHost` (`atUnlockedStart`, `choose`,
  `onHomeResumed`); the object `PageLook` keeps the host and the registry. One mapping, `nightValues`, gives
  AppCompat's, `UiModeManager`'s and a configuration's night constant to all three places. Three tests in
  `PageLookTest`: `a change that waits for Home survives a process start`, `Home's resume runs a waiting change once,
  and nothing when none waits`, `a look is one night value per API, dark the night everywhere`.
- **M3** (1c6e1564): `PageLookScanTest`'s `a page takes its look, its colours and its place in the registry from its
  base class` holds `KidPageActivity`'s `applyStyle(R.style.PageColors, true)` and each of its four registry calls in
  its own callback.
- **L1** (1c6e1564): the unlocked start reads `applied` and `choice` before the system call, and the host's
  `applyNightMode` catches and logs, so neither a tap nor Home's resume can crash on it. `Application.kt`'s comment says
  that.
- **L2** (1c6e1564): `PageTokensScanTest` reads its layout list from the `R.layout.*` and `*Binding` names in
  `ui/day/*.kt` (with the look row's preview and what the layouts include); `item_day_page` is in, and a floor keeps the
  list from going empty.
- **L3** (1c6e1564): the look row's listeners are set inside the `if (BuildConfig.DEBUG)` block; `PageLookScanTest`'s
  pin covers the row and the listeners.
- **L4** (1c6e1564, docs only): ui-polish.md says what the code does - Home's resume does not finish a change a process
  death cut short; the card with the old look's snapshot stays in Recents until the next change. Read, not seen.
- **L5** (1c6e1564, docs only): a line under the design's step 2 - Home's resume can close a just-opened Kalender task
  while a change waits; to settle before the row is shown in release builds.
- **L6** (1c6e1564, docs only): the design's §7 table has the shipped strings, and its note says the caption describes
  step 2 and is debug-only until then.
- **L7** (1c6e1564): the unlocked start logs its own line, `The kid's look took N ms`; `Unlocked setup took N ms` stays
  design 59's head (the preferences and the rest), so its numbers compare with the earlier ones.
- **L8** (1c6e1564): `docs/testing/emulator.md` §6ah has (a) the lock-task assertion around the task's removal (B.3),
  (b) a change while Kalender's task lives only in Recents (B.9), (c) "not reliably" with the tap-before-`onStop` way,
  (d) B.7 says a reboot cannot be counted on to hit the window, with two hand-written-file steps in its place (B.10,
  B.11), (e) Kalender in Mørk and Lys with 3-button navigation (C), (f) the unlocked start's two log lines (B.12).
- **L9** (1c6e1564): `PageTokensScanTest`'s "both looks define the same page tokens" and `KidPaletteTest`'s exact day
  map are gone; one line keeps that every page token has a day value, and the contrast pairs and the night map stay.
  `PickerThemeTest` stays: its twin equality is not fully implied by `SplashThemeTest`'s drops-no-key rule.

The five surviving mutations again, on 1c6e1564, one at a time with the `ui.look` tests:

| # | Broken | Result |
|---|---|---|
| M9 | the change does not move `applied` to the choice | caught, `PageLookTest` (`Home's resume runs a waiting change once, and nothing when none waits`; `a change stores the choice and the applied look before it closes anything, the system call last`) |
| M13 | `atUnlockedStart` does not read the stored choice | caught, `PageLookTest` (`a change that waits for Home survives a process start`) |
| M14 | the page's `PageColors` overlay made ineffective | caught, `PageLookScanTest` (`a page takes its look, its colours and its place in the registry from its base class`) |
| M15 | `pageTaskStopped()` removed from `onStop` | caught, `PageLookScanTest` (the same test) |
| M10 | a literal colour in `item_day_page.xml` | caught, `PageTokensScanTest` (`the page layouts, their drawables and their styles name page tokens only`) |

Still only the emulator pass can settle everything under "Only the emulator pass can settle", and with it the fix
round's own: the scrim in Mørk with 3-button navigation, the window grounds, L4's card in Recents, L5, and the look's
cost at the unlocked start.
