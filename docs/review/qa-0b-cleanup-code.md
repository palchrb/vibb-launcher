# QA 0b (code): dead code in the apps - branch `cleanup-apps` (25c4b49f, 35b98e46 on 380fc2c8)

**Verdict: merge.** 0 High, 0 Medium, 3 Low, 3 Nits. Behaviour is unchanged in both lock modes. With `switchOn = false`,
every call site gives the result main's release build gave. Both apps' full suites and `assembleRelease` pass with
dependency verification on, and nothing in either app, the scripts or the live docs names a deleted identifier. The
one gap that matters is in a test, not the round: `KeyguardCoverPlanTest` doesn't guard the android-mode write the
round's QA line relies on (L1). The Lows can go in the merge commit.

## What was verified

- **The keyguard cover, android mode** (`KeyguardCover.kt`). On main's release build `KeyguardCoverDebug.enabled` was
  `false`, so `switchOn` was false there too. `dismissTest` was always `NONE`, because only the debug receiver set it.
  The call sites, each identical to main's release build:
  - `wantedNow`'s `coverWanted = !off && switchOn && armed` is false, so `keyguardMode` never returns COVER
    (`KeyguardCoverPlan.kt:58`) and `coverOn` stays false.
  - `write`'s log gate `switchOn || wasOn || coverOn` and its "held off" suffix are unchanged.
  - `onLockResumed`:307, `onVoipPhase`:317, `PinLockRuntime`:945/1098 (`onScreenOff`/`onVoipPhase`) and
    `PinLockActivity.watchFirstFrame`:176 all skip.
  - `coverWritten`'s catch is unchanged.
  - The ANDROID branch (`keyguardMode`:56) doesn't read the switch, and `configure`'s body differs only by the deleted
    switch read.
- **`dismissRequested()` is reachable only behind the switch.** Its one caller is `PinLockActivity.requestDismiss`
  (:207). That is reached from `firstFrame` (posted only by the draw listener `watchFirstFrame` installs after
  `if (!KeyguardCover.switchOn) return`, and gated again by `dismissDueNow`'s `coverOn &&`) and from `checkKeyguard`
  (`!KeyguardCover.coverOn` returns, and `check` returns false when `!coverOn`). So `return true` is dead now, as
  main's release `NONE` was.
- **Own mode.** `lockUpStarts(...)` has one caller, `PinLockRuntime.kt:780`, with the same six arguments as main's :781.
  Main's release `LockUpDebug.adjust` returned its plan unchanged, and the debug default (`skipRestart = true`) did too.
- **Back buttons.** `include_kid_header.xml` sets no `visibility` on `kid_header_back`, so it is VISIBLE. Exactly 4
  layouts include it (phone book, kid Settings, Wi-Fi, Bluetooth) and 4 `KidHeader.bind` calls bind it. No page lost
  or gained a button.
- **D5** (checked on main with `git show 380fc2c8`). `openAppsList` has two callers, `HomeActivity:364` and
  `SettingsFragmentLauncher:137`; neither passes `excludePinned`. `pinnedVisibility` was always `VISIBLE`, whose
  predicate is `{ _, _ -> true }`, and the sort ran before the filter. So a stored `minimalist.apps` never changed the
  drawer's order or contents. The diff adds 0 lines in `ui/list/` or `apps/`.
- **Resources.** I grepped the repo (both apps, `scripts/`, every doc) for each deleted string, dimen, style, anim,
  layout, menu id, class, function, `palette`, `skipRestart`/`skipDismiss`/`failDismiss`, `keyguard_cover_debug` and
  `minimalist`. Hits remain only in `docs/review`, `docs/design`, `docs/plan` and one historical test run. The same 16
  string keys went from `values/` and `values-nb/`, and `TranslationsTest` (`assertEquals(en.keys, nb.keys)`) is the
  parity pin. `kid_icon_fallback` stays, and is live (`KidAvatars.kt:258`, `KidPaletteTest:239`).
- **Verification metadata** lost exactly the two `androidx.palette` components (16 lines). `checkReleaseHasNoGoogleServices`
  is kept and ran ("none ... in 101 release modules"). `checkReleaseHasNoDebugHook` ran on the release APK.
- **Docs.** The `launcher/CLAUDE.md` edits and the new §6k paragraph are right and short. `scripts/` names none of the
  deleted hooks.

## Findings

**Low**
- **L1** `launcher/app/src/test/java/com/kidslauncher/mdm/lock/KeyguardCoverPlanTest.kt:345-385`: nothing pins
  `KeyguardCover.configure`'s write. Mutation 3 added `if (wanted == KeyguardMode.ANDROID) return true` before
  `return write(...)` (`KeyguardCover.kt:135`), and the **whole** launcher suite stayed green. The pure writer is
  guarded (M3b), but the round's QA line expected `KeyguardCoverPlanTest` to cover this. The gap predates 0b; this
  round didn't touch that line. Fix: assert that
  `body(cover, "configure")` contains `return write(app, dpm, admin, "apply", wanted, forManaged).lockMayRun` and no
  other `return`. Until then, E1's boot/lock smoke is the only check that the android-mode cover still arms.
- **L2** `PLAN.md:377-378` still lists "Pending user decision: ... (`KidHeader.SHOW_BACK_BUTTON`)", and
  `PLAN.md:1599` still lists "Remove the unused androidx `palette` dependency". Both are done by this round. Fix: mark
  them done in the merge commit, with round 0b's own queue line.
- **L3** `docs/testing/emulator.md` §6k: the "Stay awake goes off" note went with the cover's text. Android mode
  (design 59) still writes `setMaximumTimeToLock` (`timeToLockFollows = coverOn || androidOn`), so DPMS still writes
  `STAY_ON_WHILE_PLUGGED_IN = 0` there. `launcher/CLAUDE.md:199` keeps the sentence but lost its §6k pointer. Fix: one
  line in §6k's new paragraph (android mode sets T: run `svc power stayon true` again after it).

**Nit**
- **N1** `launcher/CLAUDE.md:505` (the 16b bullet, "the main thread at the unlock") still lists "Firebase cleanup"
  among `initRest`'s tail. Delete the two words.
- **N2** `docs/design/32-lock-flash.md:832-833` (the H2 item) and §11's "Step 2" checklist still tell a tester to use
  `LockUpDebug` and `--ez failDismiss`. Add one line there saying the switch was deleted in round 0b and the checks
  can't run.
- **N3** `DebugHookAbsentTest.kt:44` counts only `<receiver`, so a debug-only `<service>`/`<activity>`/`<provider>`
  would pass. The deleted names also left `CheckDebugHook`'s list and the src/main ban. Optional: count all four
  component tags.

## Runs (`--no-daemon --max-workers=2 -PwarningsAsErrors=true`, under `~/build.lock`, dependency verification on)

| Run | Result |
|---|---|
| launcher `assembleDebug assembleRelease testDebugUnitTest` (stub `tsnet.aar`, deleted afterwards) | exit 0; 176 classes, **1804 tests**, 0 failed, 0 skipped |
| music `assembleDebug assembleRelease testDebugUnitTest` | exit 0; 81 classes, **1232 tests**, 0 failed, 0 skipped |

## Mutations (each reverted with `git checkout -- .`; the tree was clean at the end)

| # | Mutation | Targeted run | Outcome |
|---|---|---|---|
| 1 | `LockUpDebugReceiver` back in `src/debug/AndroidManifest.xml` | `DebugHookAbsentTest` | **caught**, exit 1 (`:44`, the receiver count) |
| 2 | a new `SomeNewHookReceiver` in the debug manifest | `DebugHookAbsentTest` | **caught**, exit 1 (`:44`) |
| 3 | `configure` skips the write when `wanted == ANDROID` | all `lock.*`, then the whole suite | **survived**, exit 0 both times (L1) |
| 3b | `androidWrite` skips `setKeyguardDisabled(false)` (`KeyguardCoverPlan.kt:199`) | all `lock.*` | **caught**, exit 1 (`AndroidLockPlanTest:388`) |
| 4 | `torch_title` removed from `values-nb/strings.xml` only | `TranslationsTest` (same run as 2) | **caught**, exit 1 (`:47`, the key sets) |
