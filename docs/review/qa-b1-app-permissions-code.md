# Code QA: Round B1, design 68 "per-app permissions" (branch `app-permissions`, c5a09810..09be9f79)

**Verdict: merge.** No High, no Medium. The camera denial follows L2's grant pattern (DENIED/DEFAULT, never GRANTED,
written only when it differs, our own package and system apps out), fails closed on the server (a failing read is a
500, never `[]`) and does nothing on the phone when the key is missing. The PWA form lands on its own open details with
JS on and off. The test gaps (L3, L4) are a few lines each: add them in a fix commit before the merge. L2 is a question
for the user. L1 (the app-op probe) is the E4 gate before the launcher RC.

Counts: **High 0 · Medium 0 · Low 5 · Nit 7.** Mutations: 15 run, 10 killed, 5 survived (L3, L4, L5).

## Critical paths verified

1. **Contract.** `app_permissions` is always a list (the snapshot checks `[]` and a row). A dropped table gives a 500
   (`db_error_is_500_not_default`; e is killed). `appPermissionsOf`: missing, `null`, non-list or `LastEnforcedPlan` =
   `null` = touch nothing (a is killed). Both `installed_apps_report.json` copies are byte-identical, `SyncScheduleTest`
   encodes the file and the `app_updates` test posts it. A newer key or value is dropped, never a refused report.
2. **`cameraGrants`.** DENIED outside a window, DEFAULT inside one, never GRANTED (b is killed), never our own package
   (c is killed). A target off the list goes to DEFAULT only if it reads otherwise, so an app taken off after DENIED is
   lifted on the next apply (the note's rule). Only an admin (us) sets DENIED, so this lifts nothing Android holds.
   `null` writes nothing except a window's lift. The pause lifts it (Q1).
3. **Call deferral.** A denial for a target holding the camera waits while `liveCall`; `callMayHaveEnded` then applies
   on IO (d is killed). The flag is memory-only, which loses nothing: the next apply recomputes from the policy.
4. **Install and update.** `enforceCameraOnPackage` runs before `enforceOnNewPackage`'s hide and in
   `onPackageChanged` outside the relock branch. A hidden package is skipped and denied by the apply that unhides it. On
   the android-14/15/16 tags `getPermissionGrantStateForUser` reads an unfiltered snapshot, so the `current` map
   (`AppEnforcer.kt:782`, unguarded per target) never throws for a hidden app.
5. **Readback.** DEFAULT reads through `checkPermission`. `exact_alarm` is granted when the app is battery-exempt (i is
   killed) or below target 33, and declaring `USE_EXACT_ALARM` drops it. A throw is `unknown`. `report` catches its DPM
   read, so `collectInstalledApps` can't fail because of it.
6. **Settings rows.** The `package:` page first, then the plain action, each through `systemPageIntent`. `openFirstPage`
   catches `ActivityNotFoundException`. The rows are read on IO, and a change on return requests a sync. `AppAccess.kt`
   sits behind the Settings gate, outside `KidScreensEscapeTest`'s scope.
7. **PWA.** The route is under `require_full_auth`, `log_saves` and CSRF. The grammar gives a 400. Play core and Vibb
   are refused at their row (n is killed). The cap is 200. The 303 goes to `?perms=<pkg>#perms-<pkg>` with the details
   open (m is killed). The camera switch shows only with `access.camera`. The update note shows without the capability
   (j is killed). No inline script, and `static/` is unchanged (no `jstest`). Every word is in `app_permissions::words`.
   Launcher en/nb parity holds (`TranslationsTest`).
8. **Migration 0102.** It applies fresh and on a copy of the bedtime-QA DB at 0101 (`pre-migrate-0101-0102.db`
   written, version 102). `server/CLAUDE.md` says 0103 is next. `access.camera` is sent only for non-system apps.

Browser pass (Chromium, 390 px, light and dark, JS on and off; WebKit isn't installed): the details show for Element X,
Etar and Immich only, with one camera switch. Off and on land on `?perms=…#perms-io.element.android.x` with the details
open and in view, and the sub-line follows. No horizontal scroll and no console or CSP errors.

## Low

- **L1. The §2.3 app-op probe wasn't run "before step 1"** (note §6.1). launcher/CLAUDE.md says "Not device-tested",
  and emulator.md §6v step 0 has the probe. Until it runs, every op ships as read and none as `unknown`. If a reading is
  wrong, the PWA says "approved" or "not approved" wrongly. Make it the E4 gate before the launcher RC.
- **L2. The PWA's sub-line says "approved" under nearly every allowed row.** `USE_FULL_SCREEN_INTENT` is
  `normal|appop`, so every messaging, dialer and clock app reads `full_screen: granted`.
  `AppAccessReader.report` (`AppAccess.kt:164-181`) applies the third-party rule to `camera` only, so allowlisted
  system apps (Phone, Clock, Messages) report their privileged grants as well.
  `PermsView::of` (`app_permissions.rs:244-249`) puts every state into the line. Device › Apps then reads
  "Full-screen notifications: approved" under most rows, against §4.1's "an allowed app with something to say".
  Suggestion (the user's call): leave `approved` parts out of the row's sub-line and keep them in the details. Report
  special accesses only for third-party apps as well, as for the camera; the phone rows' `accessEligible` already drops
  system apps when unmanaged.
- **L3. The nudge and the auth guard are untested.** Mutation l drops `command_notify.send` (`devices.rs:1147`) and
  survives. Without the nudge, camera off reaches the phone only at its next sync (up to 30 minutes). Mutation h moves
  the route into `public_routes`, a signed-out POST then writes, and all 1394 tests still pass. The route is placed
  correctly today (`main.rs:1149`, inside `admin_routes`), but no test pins that. Fix: add
  `app.state.command_notify.subscribe()` to `the_permissions_form_returns_to_its_open_details` (as
  `tests/app_display.rs:73` does), plus one signed-out POST that is redirected and writes nothing. A route-wide sweep
  would close the same gap for every admin form (a later round).
- **L4. The 200-row cap is untested.** Mutation g lifts it ×1000 (`app_permissions.rs:193`) and survives. Add one test:
  200 rows, then a new package gives `?notice=perms_full` at its row and nothing is written. A re-deny of a stored
  package still saves (`others` excludes it).
- **L5. The glue has no unit test.** No unit test covers the glue code (no Robolectric in the project). Two mutations
  survive: f (`AppAccess.kt:157`, a throw read as `denied` instead of `unknown`) and k (the call-end wake disabled,
  `BootHome.kt:104`). The pure halves are pinned. Accepted; E4 probes 1 and 3 cover them.

## Nit

- **N1.** A camera-off app that isn't allowed stays hidden. Every apply then writes DENIED, the PermissionController
  can't see the hidden package, and the call logs "Platform refused … CAMERA = 2" (`AppEnforcer.kt:754`) every time.
  Harmless; skip hidden targets in `cameraPass` if E4 confirms the refusal.
- **N2.** `enforceCameraOnPackage` (`AppEnforcer.kt:818`, also from `Application.kt:105`) runs outside `apply`'s lock.
  An install callback at a window edge can write DENIED just after the window's DEFAULT. The next apply heals it.
- **N3.** The server's grammar (`device_api.rs:732`) is looser than `isValidPackageName` (one segment, a leading digit):
  such a row is stored, takes a cap slot and is dropped on the phone. The UI can't produce one.
- **N4.** Less code: `render_apps` parses `installed_apps_json` a second time for `access` (`device_pages.rs:1802-1809`),
  even though `devices::app_rows` already parses it. The access could ride on the row.
- **N5.** Android 16 Settings calls usage access "App usage data" / "Data om appbruk" (`usage_access`,
  android-16.0.0_r1); Android 14 says "Usage access" / "Brukstilgang". No family app declares it.
- **N6.** In a pause every camera-off app reads `requested`, so the PWA says "Camera off · waiting for the phone" while
  the pause lasts. Acceptable, or say "paused".
- **N7.** PLAN.md is untouched by the branch (the note's step 6 lists it). Mark B1 in the merge commit.

**"Fullskjermvarsler" is verified**: Settings' nb `full_screen_intent_title` (android-14.0.0_r1 and -16.0.0_r1).
"Medieadministrering" ("Apper for medieadministrering") and "Alarmer og påminnelser" match.

## The implementer's deviations (all accepted)

1. `CameraPlan(writes, deferred)` and `AppEnforcer.cameraDeferred`: naming only; pure and tested.
2. `unsafeCheckOpRawNoThrow` under `@Suppress("DEPRECATION")`: as the note asks (§2.3), with try/catch to `unknown`.
3. The category is empty in XML, filled in code and placed last: a category header in a flat screen sits over every
   row after it, and the PWA's how-to path ("Pause all restrictions › App access") matches.
4. A package outside the grammar is a 400: no row carries such an anchor, like `toggle_app`.
5. Its own test: it covers the fragment, the 303 with `assert_lands`, the open details, the policy row and the refusals.
6. Own "Not approved …" strings for WAIT/NO_PAGE, and PAUSE_FIRST reuses the write-settings string: the right words, no
   duplicate.

## Mutations (each alone, its tests run, the file restored; the worktree is clean)

| # | Mutation | Result |
|---|---|---|
| a | `appPermissionsOf` returns `emptySet()` for a missing key | killed: `PolicyResponseCompatTest` (app_permissions …) |
| b | `cameraGrants` writes GRANTED in a window | killed: `EnforcementPlanTest` (cameraGrants …) |
| c | own package not skipped | killed: `EnforcementPlanTest` |
| d | call deferral dropped | killed: `EnforcementPlanTest` |
| e | the policy's DB error becomes `[]` | killed: `device_api::db_error_is_500_not_default` |
| f | `states`' throw becomes `denied` | **survived** (L5, glue) |
| g | the 200-row cap ×1000 | **survived** (L4) |
| h | the route moved out of `require_full_auth` into `public_routes` | **survived** the full suite (L3; no route-wide auth sweep exists) |
| i | `exact_alarm` ignores the battery exemption | killed: `AppAccessTest` (exact alarms …) |
| j | the capability-note condition inverted | killed: `device_pages::the_permissions_form_returns_to_its_open_details` |
| k | the call-end wake disabled | **survived** (L5, glue) |
| l | the save's nudge dropped | **survived** (L3) |
| m | `?perms=` never opens the details | killed: `device_pages::the_permissions_form_…` |
| n | Vibb not refused | killed: `device_pages::the_permissions_form_…` |
| o | `AppAccess::of` takes any value | killed: `app_permissions::tests` ×2, `app_updates::the_installed_apps_contract_…` |

## Full runs

Server: `fmt --check` clean, `clippy --all-targets --locked` 0 warnings, `cargo test` **1394 passed**. Launcher:
`assembleDebug testDebugUnitTest -PwarningsAsErrors=true` green, **1829 tests**, 0 failed.

## E4 probes (`jelly36`, then the Jelly Star)

1. L1's probe: `cmd appops get <pkg> <OP>` for the five ops against `installed_apps[].access` (Immich, Etar before and
   after battery exemption, Signal, Element X, and test apps for `WRITE_SETTINGS`/`GET_USAGE_STATS`).
2. Element X camera off: `dumpsys package` shows `CAMERA: granted=false` with `POLICY_FIXED`; note the words on the
   locked switch. Back on: the flag is gone and the app asks again.
3. During an Element X video call the denial waits ("Camera off waits for the call's end"), then lands at the call's
   end or the next screen-on; also try a carrier call with calls unmanaged.
4. A hidden camera-off app: does every apply log "Platform refused" (N1)? Allowed again, is it denied in the same apply?
5. Immich and Etar approved in a pause ("Approved - tap to change", report `granted`); afterwards Etar's first start
   works. Fossify Calendar and `me.vibb.music.debug`: no row, no switch. Count the "…: approved" lines on a real
   allowlist (L2).
