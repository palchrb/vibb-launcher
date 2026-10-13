# QA 28 step 1 (code) - the Play Store in the drawer during the override or the pause

Reviewer: code QA, 2026-10-13. Branch `design-28-s1` (`84e29547..244b88e4`, 1 commit). Reviewed against
`docs/design/28-music-card-session.md` (§5, §6, §7 step 1, §8 Part B) and its design QA `qa-28-design.md` (M1, L6,
L8), and against the code the step builds on: `server/WindowRelock.kt` (listeners, `opened`, `relock`, `lockAgain`),
`server/WindowRelockStore.kt` (`runRelock`), `server/OfflineOverride.kt`, `server/RestrictionsPause.kt`,
`server/EnforcementPlan.kt`, `timerules/TimeRulesRuntime.kt` (`recheck`), `Application.kt` (`loadApps`, the
`LauncherApps` callback), `apps/AppFilter.kt`, `ui/list/apps/` (`ListFragmentApps`, `AppsRecyclerAdapter`,
`ContextMenuActions`), `ui/HomeActivity.kt`, `play/` and the generated `LauncherPreferences` setters. QA left no
product change behind: the mutation edits below were reverted before this commit.

**Result: mergeable after a small fix round. No High and no Medium findings; 2 Low.** The rule is right. The store is
listed in the drawer exactly when install mode or a window is open. It is on Home's grid only in install mode. Play
services and GSF are never listed. The refresh rests on the window's own edges, so it works on a GMS phone. Neither Low
finding gives the kid anything:
- L1: a rare UI glitch. The drawer's filter can now be the call that self-clears an expired window, and that re-enters
  the drawer's own refresh, so the drawer shows every app twice.
- L2: three of the step's wirings can be broken with every test still green (mutation-checked).

## What ran (under the shared build lock, the stub `tsnet.aar` copied in and removed afterwards)

- `./gradlew assembleDebug assembleRelease testDebugUnitTest -PwarningsAsErrors=true` at `244b88e4`: BUILD SUCCESSFUL.
  1041 tests, 0 failures. `PlayPolicyTest` has 22 tests, `PlayInvariantsTest` 7, `AppFilterTest` 3.
- **Mutation run 1** (all three at once, `--tests` on `play.*` and `apps.*`, `-PwarningsAsErrors=true`): **green**
  (fresh results, not UP-TO-DATE):
  - `Application.kt:319`: the listener line commented out (`// com.kidslauncher...WindowRelock.addListener { loadApps() }`);
  - `PlayRuntime.kt:69`: `playStoreListable(installModeActive(context), false)`, so the windows are dropped and the
    whole feature is off;
  - `HomeActivity.kt:651`: `playPackageOnHome(pkg, installMode || PlayRuntime.storeListable(context))`, so the store
    is on Home's grid in a window.
- **Mutation run 2** (without `-PwarningsAsErrors`, so the unused local doesn't stop the compile): **3 red**, each the
  intended test:
  - the listener line deleted: red `a window's edges reload the app list`;
  - Home's `playPackageOnHome` filter deleted: red `Home drops the store outside install mode`;
  - `playPackageOnHome`'s body set to `true`: red `the Play Store is on Home only in install mode`.

## Checked and sound

- **The rule.**
  - `AppFilter.kt:39` feeds `kidListable` with `PlayRuntime.storeListable` (`PlayRuntime.kt:69`), which is install
    mode or `overrideActive()`. `overrideActive()` is the file's existing `OfflineOverride.isActive() ||
    RestrictionsPause.isActive()`, the same predicate `buildEnforcementPlan` gets, so the list and the enforcement agree
    on what a window is.
  - `playPackageLaunchable` (`PlayPolicy.kt:100`) still lets only `PLAY_STORE` through, so Play services and GSF stay
    off every list in every state.
  - `AppFilter` has exactly two callers: the drawer (`AppsRecyclerAdapter`) and Home (`refreshApps`). Home adds
    `playPackageOnHome(pkg, installMode)` after our own package is dropped (`HomeActivity.kt:643-651`), with install
    mode read in the same background block, and an exception there counts as off. `GlancePanel.kt:421` only looks up
    an icon in `apps.value`; it lists nothing.
  - Install mode and a window are read a few microseconds apart in `refreshApps`. A flip between the two reads errs
    towards hiding the store, and the next render fixes it.
- **The refresh.**
  - `loadApps()` only posts the raw list (`Application.kt:411-420`). Both consumers filter when the value arrives, so
    the window state is read at observe time. Two concurrent `loadApps` posting out of order can't show a stale Play
    decision.
  - `WindowRelock` runs its listeners on main (`handler.post`, `WindowRelock.kt:94-96`). `loadApps` starts its own
    `Dispatchers.Default` coroutine, so nothing heavy runs on main.
  - The listener is a `CopyOnWriteArraySet` entry added once per process in `initRest`. It is never removed, which is
    right for an `Application` listener.
- **Both edges, both kinds of phone.**
  - **Open:** `opened` notifies unconditionally after `lift()` (`WindowRelock.kt:167`). That also covers a failed
    marker commit and a phone that isn't device owner. Both starters (`OfflineOverride.activate`,
    `RestrictionsPause.start`, which the PWA's `start_pause` also uses) go through it. No other code writes the window
    flags to true. A second window opened inside the first notifies again.
  - **"Lock again":** the flags are cleared under the lock, then it notifies (`:313-325`), so Play leaves the list at
    once.
  - **Natural end:** the boundary alarm runs `TimeRulesRuntime.recheck`, then `WindowRelock.check`, then the worker's
    `relock`, which notifies after `runRelock` returns a report (`:300-304`). The sync's `OfflineOverride.clear()` is
    followed by `relockInSync`, which also notifies. So Play leaves the drawer when the relock *finishes*, a few seconds
    after the window's end, as §8 B4 says ("at the relock").
    - On a suspendable phone, the relock's apply suspends Play earlier, and `onPackagesSuspended` plus `isMdmSuspended`
      hide it first.
    - On a GMS phone, the kiosk is back by the apply step. A stale drawer entry tapped in those seconds is stopped by
      lock task. With the kiosk off it opens, but that is the B4 status quo (`market://` opens Play there too).
    - With one window still open, `relockDue` is false and nothing notifies. That is correct, because the store stays
      listable.
  - **Open before the apply:** in the second between `opened`'s notify and the starter's apply, a GMS phone lists Play
    while the kiosk is still on. A tap is stopped by lock task, and the list is already right once the apply lands. On a
    suspendable phone, `isMdmSuspended` hides it until `onPackagesUnsuspended`.
  - **The drawer left open across an edge:** `apps.observe(activity)` re-delivers the newest value at the next start
    (§8 B2), and while the drawer is in front its adapter re-filters at once.
- **What the kid can reach.**
  - Outside a window, nothing new. The store is listed only when `storeListable` is true, and a stale list after the end
    is bounded by the relock as above.
  - The kiosk is off in a window (`appsManaged = false` gives `kioskPackages = null`, `EnforcementPlan.kt:166`).
  - `PlayLinkBlockedActivity` and its persistent preferred activity are untouched, and install mode's NOT_NEEDED is
    unchanged.
  - The drawer's long-press menu (hide, rename) now reaches the store in a window. That is the same as for every other
    listed app, and it gives no access.
- **MIT rule.** `apps/AppFilter.kt` changes one call and a comment. The local `installMode` stays, so
  `AppFilterTest`'s text pin holds. The logic is GPL code in `play/` and `ui/HomeActivity.kt`.
- **Docs.** The launcher `CLAUDE.md` Play bullet, design 07's status line, design 28's step 1 status, the KDoc in
  `PlayPolicy.kt`/`PlayRuntime.kt` and the `Application.kt` comment are all accurate. The parameter name and its KDoc
  follow QA L6. The style (fully qualified `WindowRelock` in `Application.kt`, the try/false read) matches the
  surrounding code.

## Low

### L1. The drawer's filter can now be the call that self-clears an expired window, which re-enters the drawer's refresh and lists every app twice
- **Where:**
  - `apps/AppFilter.kt:39` (`PlayRuntime.storeListable`), then `PlayRuntime.kt:149`, then `OfflineOverride.isActive`
    (`OfflineOverride.kt:28-42`) / `RestrictionsPause.isActive` (`RestrictionsPause.kt:87-98`). Both call `clear()` once
    the time is up.
  - `clear()` makes two writes to the default `SharedPreferences` through the generated setters, which use `apply()`.
  - `ui/list/apps/ListFragmentApps.kt:27-30` listens to the same file (`appsRecyclerAdapter.updateAppsList()`).
  - `AppsRecyclerAdapter.updateAppsList` (`AppsRecyclerAdapter.kt:95-99`) does `appsListDisplayed.clear()`, then
    `apps.value?.let { appsListDisplayed.addAll(appFilter(it)) }`.
- **Before this step:** `AppFilter` read only `PlayRuntime.installModeActive`. That reads the separate `play_state`
  file and never writes, so the filter never wrote preferences.
- **Scenario (an AOSP reading; no Robolectric here to run it):**
  1. The drawer is open (or reopened after a screen-off with no keyguard). A pause has just run out, and nothing has
     called `isActive()` since. For example, the boundary alarm is late in Doze, or an NTP step back made the elapsed
     leg end first.
  2. A package event (a background Play update of any app), a suspension edge or any preference write starts
     `updateAppsList` on main. It clears the list and calls `appFilter(it)`.
  3. Inside it, `RestrictionsPause.isActive()` calls `clear()`. Each `apply()` on the main thread calls the listeners
     **synchronously** (`SharedPreferencesImpl.notifyListeners`), so `updateAppsList` runs twice nested. Each nested run
     clears the list and adds the N filtered apps.
  4. The outer `addAll` then appends its own N.
  5. The drawer shows every app twice until the next refresh. A tap still opens the right app.

  Home's `refreshApps` runs the filter on `Dispatchers.Default`, so its listener calls are posted and Home isn't
  affected.
- **Fix (either one; both are small):**
  - In `AppsRecyclerAdapter.updateAppsList`, compute the list first and then replace:
    `val next = apps.value?.let { appFilter(it) }.orEmpty(); appsListDisplayed.clear(); appsListDisplayed.addAll(next)`.
    This is a re-entrancy fix to MIT code with no new logic, and it also protects against any future writer inside the
    filter.
  - Or read the windows without the self-clear in `storeListable`: a non-clearing `isOpen()` next to each `isActive()`,
    used only for listing. The clear is left to the enforcement, recheck and relock paths, which already call
    `isActive()` at the edge.
- **Test:** none possible on the JVM without Android. If the second fix is taken, a source scan that `storeListable`
  doesn't call `isActive()`.

### L2. Three of the step's wirings can break with every test green
- **Where:**
  - `PlayInvariantsTest.kt:66-90` (the three scans);
  - `PlayRuntime.kt:69` (not pinned at all);
  - `PlayPolicyTest.kt:155-165`.
- **Scenario** (mutation run 1 above, all green under `-PwarningsAsErrors=true`):
  - `storeListable` = `playStoreListable(installModeActive(context), false)`. The step's whole feature is off: Play is
    unreachable in a window, which is the bug design 28 B fixes. The pure `playStoreListable` truth table and the
    `AppFilter` scan both still pass, because nothing pins what `storeListable` feeds in.
  - Home passing `installMode || PlayRuntime.storeListable(context)` puts the store on Home's grid in a window (against
    QA L8). The Home scan checks only that `playPackageOnHome(` and `PlayRuntime.installModeActive(context)` occur
    somewhere in `refreshApps`, not that the read is the argument.
  - The listener line commented out still contains `WindowRelock.addListener { loadApps() }`, so the scan passes while
    no window edge reloads anything.
  - A smaller point: `Play services and GSF are never listable` asserts `playPackageOnHome(PLAY_SERVICES, installMode)`
    is **true** (`:161`). That is correct, because the function only narrows the store, but under that test name it
    reads as "Play services may be on Home".
- **Fix:**
  - Pin the runtime line: a scan that `PlayRuntime.kt` contains
    `playStoreListable(installModeActive(context), overrideActive())`.
  - Tighten Home's scan to the exact call `playPackageOnHome((it.getRawInfo() as? AppInfo)?.packageName, installMode)`,
    and assert that `storeListable` doesn't occur in `refreshApps`.
  - Make the scans skip comment lines: drop the lines whose trimmed text starts with `//` before matching. The same
    applies to the listener scan.
  - Give the GMS assertion a one-line comment ("`playPackageOnHome` narrows only the store; `playPackageLaunchable`
    keeps Play services off"), or move it to the Home test.

## Fixes (fix round, branch `design-28-s1`, one commit on top of `b3d268d0`)

| Finding | Commit | What changed |
| --- | --- | --- |
| L1 | this commit | `AppsRecyclerAdapter.updateAppsList` filters first, then clears and adds, so a nested call from the preference listener can't double the list. This was chosen over a non-clearing `isOpen()` for `storeListable` because: it is a three-line re-entrancy fix in the MIT file with no new logic; it covers any writer the filter can reach; and the lists, the enforcement and the relock keep one predicate for "a window is open" (`isActive()`), where a second, non-clearing predicate would have to stay in step with it. The relock is untouched: `openWindows()` still self-clears at the edge. Test: `PlayInvariantsTest` `the drawer filters before it replaces its list` (a source scan, because the adapter needs Android) |
| L2 | this commit | `PlayInvariantsTest`'s design 28 scans read the code without its comment lines (`//`, `/*`, `*`). The filter scan pins `PlayRuntime.storeListable`'s line and the two windows in `overrideActive()`. The Home scan pins the exact `.filter { playPackageOnHome((it.getRawInfo() as? AppInfo)?.packageName, installMode) }` and checks that `refreshApps` has no `storeListable`. In `PlayPolicyTest`, the `playPackageOnHome(PLAY_SERVICES, ...)` assertion moved to the Home test, with a comment saying that Home's filter narrows only the store |

**Mutation check.** QA's run 1 plus the L1 revert were applied all at once, then reverted, and run with `--tests` on
`play.*` and `apps.*` and `-PwarningsAsErrors=true`. Result: 4 red, each the intended test:
- the listener line commented out: red `a window's edges reload the app list`;
- `storeListable` with `false` for the windows: red `the filter asks the Play rule with the windows`;
- Home with `installMode || PlayRuntime.storeListable(context)`: red `Home drops the store outside install mode`;
- `updateAppsList` back to clear-then-add: red `the drawer filters before it replaces its list`.
