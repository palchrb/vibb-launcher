# QA (code): the screen capture switch

Branch `screen-capture`, commits feb35369 (code + docs) and 47535621 (the emulator report), on main 1ef36d09.
Reviewed 2026-10-23 on `qa-screen-capture` (this file only). Server and launcher.

## Summary

The switch is correct and small. Both sides agree on the key (`hardening.disallow_screen_capture`), the default (off)
and the fallback when the key is missing (off). Every path that sets or clears the other hardening switches also
handles this one, because they all run through `AppEnforcer.apply()`. The write happens only on a change, so a pass
that keeps it on writes nothing. Nothing of ours reads the screen. One fix round for the label (Q-1, the user's
decision), one weak source-scan test (Q-2) and stale doc counts (Q-3, Q-4).

Counts: High 0, Medium 0, Low 2, Nit 2.

## Setup

- Worktree on `qa-screen-capture` (from `screen-capture` 47535621), `git diff 1ef36d09..screen-capture`.
- Launcher: `./gradlew --no-daemon --max-workers=2 -PwarningsAsErrors=true assembleDebug testDebugUnitTest`
  (stub tsnet): BUILD SUCCESSFUL, **1633 tests, 0 failed** (161 classes).
- Server: `cargo fmt --check` clean; `cargo test`: **1206 passed, 0 failed** (9 dead-code warnings in the test build, none in code this branch touches).
- No emulator (the implementer's run: `docs/testing/2026-10-23-screen-capture.md`).

## Checks

1. **Key, default, fallback.** Server: `models::Hardening.disallow_screen_capture`, `Hardening::default()` false,
   column `INTEGER NOT NULL DEFAULT 0` (`0089_screen_capture.sql`), so an existing database upgrades to "off"
   (`rows_from_before_the_migration_get_the_defaults` compares a pre-0023 row with `Hardening::default()`). The
   policy always carries it (`Hardening` is serialized whole); `hardening_keys_snapshot` and
   `new_device_gets_explicit_defaults` pin the key and its `false`. Launcher: `HardeningPolicy.disallowScreenCapture:
   Boolean? = null`, `hardeningPlan` maps a missing key and a missing `hardening` to off
   (`policy?.disallowScreenCapture ?: false`); `ServerJson` has `ignoreUnknownKeys`, so an older launcher simply
   ignores the key. The decode test in `HardeningTest` reads the snake_case key.
2. **Apply logic** (`AppEnforcer.kt:304-305`, `:520`, `:580-620`).
   - Every enforcement pass is `AppEnforcer.apply()`: the sync, the pause and override switches, the process start
     with the cached policy (`Application.kt:108`), the re-checks, the admin receiver. No other code sets
     `setScreenCaptureDisabled` (the test pins it), so there is no heal/backstop path to miss.
   - The fallback plan (`LastEnforcedPlan`) stores the whole `HardeningPolicy`, so a corrupt cache keeps the
     parent's choice; `NOTHING_ALLOWED_FALLBACK` has no hardening, so it is off there - the same as the other
     default-off switch, airplane mode.
   - No double write: `clearHardening` writes "off" only when the plan has it off, `applyHardening` writes "on" only
     when the plan has it on (and its nested `clearHardening` skips it), and `screenCaptureWrite` compares with
     `getScreenCaptureDisabled` first. A pass that keeps it on writes and logs nothing; off->on writes once at the
     end, on->off once at the start. No flicker.
   - Unmanaged (no allowlist, calls unmanaged) -> `managed = false` -> plan off -> cleared at the start of the next
     pass. A remote wipe (`wipeDevice`) or a recovery reset removes the device owner and all its policies with it.
     The launcher never calls `clearDeviceOwnerApp`, so there is no "leave the phone" path that could skip it.
   - Design 59's rollback is the rollback of Android's PIN (`AndroidLock.configure`, `rollbackDue`), not a return
     to the stock launcher; it doesn't touch hardening and doesn't need to. The stock launcher showing during a boot
     (F3) changes nothing either: the DPM policy persists across reboots on its own.
   - Same failure model as the restrictions: "on" is set at the end of `apply()`, so an exception in between delays
     it to the next pass; `syncScreenCapture` catches and logs, the next pass retries.
3. **The PWA save.** The row is inside the existing `hardening` form with `data-autosave`, like its neighbours; the
   handler is the same `update_hardening` (one more `bind`), the same 303 back to
   `/devices/{id}/advanced#restrictions`, so `scroll-restore.js` keeps the position. `advanced_shows_the_switches_as_one_form`
   checks the box's state, `data-autosave` and its form; `form_saves_every_switch_and_missing_means_off` saves it.
4. **Our own screens.** Nothing of ours reads the screen: no `MediaProjection`, `PixelCopy`, `VirtualDisplay` or
   screenshot API in `launcher/` or `music/`. Ours already guard themselves: `PinLockActivity` (the lock and its
   glance) and the Parent PIN keypad set `FLAG_SECURE`, `HomeActivity` and `SettingsActivity` call
   `setRecentsScreenshotEnabled(false)`. The boot cover and keyguard cover set neither, which is fine: they show
   nothing private. `uiautomator dump` (the smoke test's checks) is accessibility, not capture, and keeps working;
   only `smoke-test.sh`'s evidence screenshots (`screencap`) would be black, which the emulator.md warning covers.
   Media casting (Sonos, Cast of a stream) is not screen capture and is unaffected; "casting" in the docs means
   screen mirroring (MediaProjection).
5. **Tests.** Three launcher tests (the plan rule, the write rule, a source scan for the one setter) and the server's
   switch lists extended. Lean. The mutation table is below; Q-2 is the one gap.
6. **Docs.** `04-hardening.md`'s subsection, `launcher/CLAUDE.md`, `server/CLAUDE.md` (0089, next free 0090),
   `hardening-and-pins.md`, PLAN's entry and the emulator.md warning are accurate. `adb emu screenrecord screenshot`
   needs a host path (emulator.md:381 has the full form), but the report shows it works with the switch on. Stale
   counts: Q-3, Q-4.

## Mutations

Launcher runs: `testDebugUnitTest` filtered to `HardeningTest`, `PolicyGateTest`, `PolicyResponseCompatTest`. Server
runs: `cargo test -- tests::hardening migrations_73`.

| # | Break | Caught by |
|---|---|---|
| L1 | Default true: `policy?.disallowScreenCapture ?: true` (`Hardening.kt:88`) | `screen capture is off by default, on only from the server and only while managed` |
| L2 | Write unconditionally (pure): `screenCaptureWrite` returns `wanted` | `screen capture is written only when it differs` |
| L2b | Write unconditionally (Android side): `syncScreenCapture` skips the getter and writes `disabled` (`AppEnforcer.kt:603`) | **survives** (Android-only wrapper; see Q-2) |
| L3 | Lift under a pause: `if (plan.screenCaptureDisabled && !RestrictionsPause.isActive())` / `if (!plan.screenCaptureDisabled \|\| RestrictionsPause.isActive())` | **survives** (Q-2) |
| L5 | Apply while unmanaged: `managed &&` dropped from the plan | `screen capture is off by default, ... only while managed` |
| L6 | No "off" in `clearHardening` (unmanaging/switch-off never clears it) | `screen capture follows the hardening plan alone, ...` (count 2) |
| L7 | The DTO reads another key (`@SerialName("disallow_screenshots")`) | `the server's hardening object decodes` |
| S1 | `Hardening::default()` true | `rows_from_before_the_migration_get_the_defaults` |
| S2 | Migration `DEFAULT 1` | `new_device_gets_explicit_defaults`, `advanced_shows_the_switches_as_one_form`, `rows_from_before_the_migration_get_the_defaults` |
| S3 | Drop the key from the policy JSON (`#[serde(skip)]`) | `hardening_keys_snapshot`, `new_device_gets_explicit_defaults`, `form_saves_every_switch_and_missing_means_off` |
| S4 | The handler doesn't save it (`.bind(false)`) | `form_saves_every_switch_and_missing_means_off` |
| S5 | The row without `data-autosave` | `advanced_shows_the_switches_as_one_form` |

12 breaks, 10 caught, 2 survive (L2b, L3).

All reverted; `git status` on the branch shows only this file.

## Findings

### Low 1 (Q-1) - the row label wraps at 390 px; it becomes "Block screenshots" (`server/templates/device_advanced.html:59`)

The implementer's own screenshot (`docs/testing/screens/screen-capture/pwa-restrictions-on.png`, 390 px) shows
"Screenshots and screen recording off" on two lines, the only wrapped row of the group, and "... off" reads opposite
to its neighbours ("Block airplane mode"). The user's decision: the label is **"Block screenshots"**.

**Fix:** the label "Block screenshots" in the template. The group's ⓘ gets one line: "Block screenshots also stops
screen recording and screen casting, and the phone's recent-apps previews go blank." Update the name where the docs
quote it: `docs/design/04-hardening.md` (the subsection's first paragraph - add that the switch also covers screen
recording and casting), `docs/notes/server/hardening-and-pins.md:7`, `docs/testing/emulator.md:56`, `PLAN.md`'s entry
(~line 863), `server/migrations/0089_screen_capture.sql` stays as it is (a comment only; never edit an applied
migration). Optionally pin the label in `advanced_shows_the_switches_as_one_form` next to "Block safe mode".

### Low 2 (Q-2) - the "never the override or the pause" test survives a pause guard (`HardeningTest.kt:269-282`)

The source scan checks that both `syncScreenCapture(dpm, admin, …)` lines *contain* `plan.screenCaptureDisabled`.
Mutation L3 (`if (plan.screenCaptureDisabled && !RestrictionsPause.isActive())` and the matching `|| ...` on the off
side) keeps that text and **survives**: the test's name promises exactly the rule it doesn't catch. The real
protection is structural (`hardeningPlan` has no override input, and the call sites read only the plan).

**Fix:** match the guards exactly: the two lines must be `if (plan.screenCaptureDisabled) syncScreenCapture(dpm,
admin, true)` and `if (!plan.screenCaptureDisabled) syncScreenCapture(dpm, admin, false)` (trimmed equality), so any
added term fails. Or drop the scan and keep the one-setter assertion only, with the test renamed to what it checks.
While there, the same scan can close L2b (the Android wrapper writing without the getter, also uncaught): assert that
`syncScreenCapture`'s body holds `screenCaptureWrite(dpm.getScreenCaptureDisabled(admin), disabled) ?: return`
before its one `setScreenCaptureDisabled(`. Optional - a repeated equal write is harmless apart from a log line.

### Nit 1 (Q-3) - `docs/notes/server/pwa.md:175` still says "the 10 hardening switches"

**Fix:** "the 11 hardening switches".

### Nit 2 (Q-4) - `docs/notes/server/hardening-and-pins.md:7` opens with "has nine switches" and ends "eleven in all"

**Fix:** "**`device_policy` has eleven switches**" with the two later ones in the list, or leave the opening as history
("started with nine"). Wording only.

## Verdict

**Merge after a fix round** (Q-1 is the user's decision; Q-2 to Q-4 are small). No behaviour change is needed in
the launcher.

## Backlog note (not this branch): a stuck `BlockedAppActivity` over the stock launcher after a PWA pause

Seen by the implementer on `handy36` (Android 16), kiosk on, no Child PIN (today's lock not set either), after
Overview had been opened several times under the kiosk; reproduced with the switch off, where
`setScreenCaptureDisabled` is never called, so not this branch. From `docs/testing/2026-10-23-screen-capture.md`:

- After the PWA's pause ("Unlock for 2 h", `start_pause`, `mdm.restrictions_paused=true`) the phone showed "App is not
  available - Vibb One (debug) is not available right now" (`BlockedAppActivity`) over the **stock launcher**.
- `LockTaskController` was `LOCKED` with `mLockTaskPackages u0:[]`.
- The events log: `wm_finish_activity ... HomeActivity, clear-task-all` 14 s after the command, then
  `wm_create_activity ... BlockedAppActivity`.
- The task was `type=home`, not `type=recents` as in the d59 RC run's F1 (`docs/testing/2026-10-22-d59-rc-run.md`).
- One Back ended it (`mLockTaskModeState=NONE`); Home brought Vibb One back.

Suggested PLAN backlog entry: "Pause after Overview under kiosk: lock task stays LOCKED with no packages, our
HomeActivity is finished (`clear-task-all`) ~14 s after the pause and Lock task blocks Vibb One itself
(`BlockedAppActivity`, task `type=home`) over the stock launcher until Back. d59 F1 in another shape - F1 was said
to go away with Android's lock, but this run had no Child PIN, so it is today's path; check whether the pause's
kiosk-off pass calls `stopLockTask`/`setLockTaskPackages([])` while a stale lock-task task remains, and why Home is
finished. Repro: kiosk on, open Overview a few times, PWA pause, wait 15 s."
