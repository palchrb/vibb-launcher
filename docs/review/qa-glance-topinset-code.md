# Code QA: glance-topinset (the clock twin under the keyguard status bar)

Branch `glance-topinset` (cba8031c code + tests + docs, 003419b8 report) against main `1ef36d09`. Reviewer's branch
`qa-glance-topinset` = `glance-topinset` + this file. Design: 59d §8.8, §8.11, §8.15. Report:
`docs/testing/2026-10-23-glance-topinset.md`.

## Setup

- Worktree checkout of `glance-topinset`, `git diff 1ef36d09..glance-topinset` read in full (`LockGlance.kt`,
  `GlancePanel.kt`, `LockGlanceTest.kt`, `LockGlanceGuardTest.kt`, 59d, `emulator.md`, `launcher/CLAUDE.md`, the report).
- Full launcher suite under the shared lock (`flock ~/build.lock ./gradlew --no-daemon --max-workers=2
  -PwarningsAsErrors=true assembleDebug testDebugUnitTest`): **BUILD SUCCESSFUL, 1631 tests, 0 failures, 0 errors,
  0 skipped**. No new warning.
- Mutations: one at a time on the source, the `LockGlance*` and `LockGlanceGuardTest` classes run each time, the file
  put back after each. The worktree ends clean (`git status` empty before this file).
- No emulator (as briefed). The AOSP sources were read through googlesource for the rule (below).

## 1. The inset read

- **Which insets.** `GlancePanel.keyguardBarPx` (GlancePanel.kt:334-339) reads `glance.root.rootWindowInsets`:
  `getInsetsIgnoringVisibility(statusBars()).top` and `getInsets(displayCutout()).top`. That is the right pair.
  `rootWindowInsets` is the ViewRootImpl's window insets, not the ones `KidInsets`' listener consumed for the views, so the
  read sees the real bar whatever the root's own padding is. "Ignoring visibility" keeps the height if the bar's content is
  ever hidden. The cutout term is redundant on every phone I know of (the status bar's height already covers the cutout,
  `SystemBarUtils.getStatusBarHeight` = max(safe inset, default + waterfall)), but it is harmless and is in the test.
- **When.** At every `placeProfile`, which runs only inside `fitNow`, which returns early unless `pageWidth()` is non-null
  (root laid out, visible, height > 0). A laid-out view is attached, and the insets are dispatched before the layout in the
  same traversal, so there is **no stale-zero read before attach**: the "no insets yet, 40 dp floor" branch can't be hit
  from this caller. The Jelly Star's 136 px case would not land at the 40 dp floor.
- **Re-placing.** A change of the status bar or the cutout changes `KidInsets`' padding (systemBars | displayCutout), which
  changes `available()`, which the layout listener (GlancePanel.kt:229) turns into a new `fitNow` -> a new read. The lock is
  portrait-only (`screenOrientation="portrait"`), so rotation is out. A cutout overlay switch is an assets change, which
  relaunches the activity (a new `GlancePanel`, placed at its first layout). The one case nothing re-places is a change of
  the ignoring-visibility height while the bar is hidden and the padding stays the same; that doesn't happen on these
  windows (lock task doesn't hide the bar's height). Fine.
- **Edge-to-edge.** targetSdk 36: Android 15+ forces edge-to-edge, so the window covers the bar and the insets are real.
  On Android 14 (minSdk 34, possibly the Jelly Star) the lock's window is not set edge-to-edge (`PinLockTheme`, no
  `setDecorFitsSystemWindows`), but an Activity window still spans the screen (DecorView draws the bar's background), so
  `rootWindowInsets` still carries the bar; the content's top moves down instead, and `placeProfile` takes that from
  `getLocationOnScreen` (`contentTop`). Target and content are both screen-y, so the arithmetic holds in both cases. Not
  measured on an Android 14 window - a device item, not a finding.
- **Own mode, android mode, boot cover, theme relaunch.** The profile is not gated on the face: both modes run the same
  `PinLockActivity` / `GlancePanel` path. The boot cover has no clock twin (no profile code in `BootCoverActivity`), so
  its hand-over is untouched. A relaunch builds a new panel and reads again at its first fit.

## 2. The `max(bar, 40 dp)` rule, sourced

- AOSP `packages/SystemUI/src/com/android/systemui/util/Utils.java` (main), `getStatusBarHeaderHeightKeyguard(Context)`:
  the larger of `SystemBarUtils.getStatusBarHeight(context)` and `status_bar_header_height_keyguard` + the cutout's
  waterfall top inset. `KeyguardClockViewModel.getSmallClockTopMargin()` (main, the blueprint keyguard) passes
  `systemBarUtils.getStatusBarHeaderHeightKeyguard()` into the small clock's top padding. Before the blueprint keyguard
  (Android 12-14, 15 without the flag) `KeyguardClockPositionAlgorithm` gets the same value from the notification panel
  as `keyguardStatusBarHeaderHeight` (from memory of those trees, not re-read for this review).
- `status_bar_header_height_keyguard` is 40 dp in SystemUI's `values/dimens.xml` - an **AOSP** default, not a Pixel one.
  It is overlayable: Google's own device overlays (redfin) set it to `status_bar_height_portrait`, which turns the floor
  into "the bar". The emulator images keep the AOSP 40 dp, which is what the 36 px measurements show (60 px).
- So the rule's shape holds for Android 16 Pixel and AOSP SystemUI alike (both measured) and, by source, for 15 and 14.
  What may differ on the Jelly Star is (a) Unihertz overlaying the 40 dp (moot while its bar is 48 dp, which is above
  it - the floor is inactive there) and (b) the offset under the bar (91.3 dp is Android 16's `keyguard_clock_top_margin`
  plus the clock view's own padding; Android 14's may differ). The profile already says "confirm on the phone".
- **Device check** for the Jelly Star (one frame each): `adb shell dumpsys window | grep -E 'type=statusBars frame'` for
  the bar; `uiautomator dump` on SystemUI's face for `com.android.systemui:id/keyguard_header` (its bottom is the keyguard
  bar itself, if the build still has that view); a recording frame of the single-line clock. Pass: clock baseline - bar
  = 137 px and date baseline - bar = 193 px. If the bar matches but the offsets don't, re-measure the Jelly Star profile's
  two numbers only; the rule stays.

## 3. The fit

- The layout test now models the Pixel profile on a 48 dp status bar (`keyguardStatusBarPx(72, 0, 1.5f)`, LockGlanceTest.kt:910):
  clock block 113.7 dp (was 156.4), date 12.7 (was 12.3). Checked by hand: 48 + 91.3 - (48 + 8) + 0.353 x 86 = 113.66;
  128.7 + 5.65 - (91.3 + 30.36) = 12.69. Right.
- `glanceFit`'s candidates and their order are unchanged (no diff in the fit code); only the measured clock block is
  smaller, so the implementer's claim "the fit's order is unchanged" is true. The outcome changed: music + 1 row now keeps
  the full tile at 473 dp as well as 497, music + 2-3 rows take candidate 3 (full tile, one row visible) at both. That is
  pinned at both heights.
- **A short bar (24 dp, the user's AVD).** Room grows 24 dp (the padding), the clock block grows 16 dp (the 40 dp floor
  minus 24): net +8 dp against the modelled 48 dp case. No overlap, no new gap: the rows and the tile follow the date in
  the column, so extra room ends up at the bottom as it always did.
- **A long bar (136 px, `handy36`).** The block is the same as the 48 dp case (bar = status bar, so `bar - contentTop`
  cancels) and identical to the old absolute 182 dp there; the room is smaller, exactly as before this branch. The date's
  floor (`-descent`) and the column flow mean the rows can't overlap the date at any bar. Nothing found.
- Consequence for the user, see Q-2: on the Jelly Star's modelled 48 dp bar the compact tile JL-3 described (and the user
  said he liked) no longer shows for music + 1 row; the full tile does.

## 4. Tests and mutations

The new test (`the profile's baselines sit under the keyguard status bar, ...`) is lean and pins the rule: 36 px -> 60 (the
floor), 136/136, a cutout taller than the bar (150), a 48 dp bar (72), no insets (60), and for all three profiles the clock
and date at 36 and 136 px bars against Android's measured 197/273 and 253/329. The glue pins are source pins in
`LockGlanceLayoutTest`.

| # | Mutation | Caught by |
|---|---|---|
| M1 | drop the 40 dp floor (`maxOf(status, cutout)`) | `the profile's baselines sit under ...`; `the clock profile - ...` (AOSP 197 px) |
| M2 | the cutout instead of the max (`maxOf(cutout, 40 dp)`) | `the profile's baselines ...`; `the expected fits ... at 473 and 497 dp` |
| M3 | absolute dp in the glue (`clockTarget = p.clockBaselineBelowBarDp * density`) | `the profile is the phone's own, ...` (source pin) |
| M3b | absolute dp in the pure fn (`profileBaselinePx = belowBarDp * density`) | `the profile's baselines ...`; `the clock profile - ...` |
| M4 | the inset read once (cached in `keyguardBarPx` after the first call) | **not caught** (Q-4) |
| M5 | clock target takes the date's offset (one line) | `the profile is the phone's own, ...` (the clock's pin string gone) |
| M5b | clock and date offsets swapped in the glue (both lines) | **not caught** (Q-1) |
| M6 | clock and date offsets swapped in `AOSP_EMULATOR_CLOCK` | `the profile's baselines ...`; `the clock profile - ...` |
| M7 | `getInsets(statusBars())` instead of ignoring visibility | `the profile is the phone's own, ...` (source pin) |
| M8 | drop the cutout term from the max | `the profile's baselines ...` (cutout 150) |
| M9 | the floor at 24 dp | `the profile's baselines ...`; `the clock profile - ...` |

All reverted; the branch is `glance-topinset` plus this file.

## 5. Docs

- §8.8: one clause added (the vertical numbers hang under the keyguard bar, §8.15). Right.
- §8.11 (59d:733): the old absolute numbers kept with "(from the window's top on `handy36`; since §8.15 91.3 and 128.7 dp
  under ... 136 px there)". Right: 273 - 136 = 137 px = 91.3 dp, 329 - 136 = 193 px = 128.7 dp (the old 219 dp was the rounded 329 px).
  But 59d:745 and :786 (step L and JL-3) and §8.4 item 7 (59d:431-434) still say the compact tile at 473 dp - Q-2.
- §8.15: accurate against the code and the report; the table, the floor, the fit consequence and the left edge match.
- `launcher/CLAUDE.md` clock entry: accurate (the function, the insets, the 40 dp, the offsets, the log line, the fit).
- `emulator.md` §3 `jelly36`: the `avdmanager` line, the config keys (480x854, 240 dpi, `_no_skin`, 2 GB, keyboard,
  `hw.mainKeys = no`, swiftshader) and the start line match the report's setup; the `dumpsys` check and the two cutout
  overlays are right. The provisioning note ("open Home before enrolling from Settings") is accurate against the code
  (`Application.initRest`: `resetPreferences` when `!internal().started()`), but sits in §3 while the enrolment step is
  §4's last paragraph - Q-3.

## 6. The left edge (recommendation, no code)

Leave it; don't build a per-digit table now. Reasons: (1) the kid's phone is the Jelly Star with AOSP's clock font, not
Google Sans Flex, so a Pixel-font table would be measured on the wrong face; (2) with a Norwegian 24 h clock (`HH`) the
first digit is only ever 0, 1 or 2 - "7" (the 6 px case) never leads, and 1 is within 2 px already; (3) 6 px at the left
edge is visible only in a side-by-side frame, not in the hand-over the kid sees. When the Jelly Star is measured on the
device (§2's check), also take the first digit's ink x for "0", "1" and "2" on SystemUI's face; only if two of them differ
by more than ~3 px is a three-entry table worth it.

## Findings

**Q-1 (Low) - the glue's clock/date pairing isn't pinned.** `LockGlanceTest.kt:963-964` checks that
`profileBaselinePx(bar, p.clockBaselineBelowBarDp, density)` and `... p.dateBaselineBelowBarDp ...` occur somewhere in
`placeProfile`, so swapping the two (`clockTarget` takes the date's offset and `dateTarget` the clock's, GlancePanel.kt:308
and :316) passes the whole suite (M5b) and would put the clock 56 px low. Expected fix: pin the two whole lines,
`val clockTarget = profileBaselinePx(bar, p.clockBaselineBelowBarDp, density)` and
`val dateTarget = profileBaselinePx(bar, p.dateBaselineBelowBarDp, density)`.

**Q-2 (Low, docs + tell the user) - the compact-tile statements are now stale.** 59d §8.4 item 7 (59d:431-434) asks the
user's rc QA to expect "music + 1 row shows the compact tile at 473 dp"; 59d:745 and :786 say the same for step L / JL-3.
Since this branch the clock block is 42.7 dp shorter on a 48 dp bar and music + 1 row keeps the full tile at 473 and 497
dp (pinned). Expected fix: §8.4 item 7 says the full tile (pointing to §8.15), and :745/:786 get "(until §8.15: the full
tile at both)". The caller should mention to the user that the compact tile he liked (JL-3) no longer shows for music + 1
row on the Jelly Star's modelled bar - a consequence of matching Android's place, not a choice.

**Q-3 (Low, docs) - the provisioning note isn't where the enrolment happens.** `emulator.md:56-60` (§3) uses `$P`, which
§4's code block defines later, and the tester enrols at §4's last paragraph (`emulator.md:184`, "Press Home, choose the
launcher. In its Settings ..."), which already opens Home but doesn't say it must come first. Expected fix: one clause at
:184 ("open Home first - enrolling before Home's first start resets the preferences at the next process start, §3") and
the package spelled out (or `$P` defined) in the §3 line. Separately, worth a PLAN backlog line: whether any real
provisioning path (QR, `ADMIN_POLICY_COMPLIANCE` -> Settings) can reach enrolment before Home's first `onStart`; the
reset would leave a managed phone without its cached policy (kiosk off) until the next sync.

**Q-4 (Info) - a one-shot inset read is not testable here.** M4 (caching the bar after the first read) passes. The read is
in a function called from every `placeProfile`, and a source pin against a cache would be brittle; accept, as the brief
allowed ("if testable").

**Q-5 (Info) - the waterfall term.** AOSP adds the cutout's waterfall top inset to the 40 dp floor
(`header + waterfallTop`); `keyguardStatusBarPx` doesn't. No phone in scope has a waterfall display, and the status bar's
inset already includes it. A KDoc clause ("waterfall displays: not modelled") would record it; no code needed.

## Verdict

**Merge after a small fix round** (Q-1's two pinned lines, Q-2's three doc lines, Q-3's clause). The code is right: the
rule is AOSP's, the read is at the right time from the right insets, nothing reads before attach, and the fit's order and
its new outcomes are correct and pinned. Q-4 and Q-5 need nothing.
