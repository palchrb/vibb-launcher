# Design 132: Phase D - Android's PIN lock is the only lock

Status: design, 2026-11-25, revision 2 (design QA: 1 H, 5 M, 6 L, all taken - §14). No code. Base: `main` at `72840de22`. PLAN.md: "Phase D" (Fix backlog) and the
locked list's item 3 (C3, audit R2). Builds on design 59 (`59-android-keyguard-design.md`), 59b (the face), 59d (the
android-mode inventory) and the 2026-11-16 default (`android-lock-default`).

The owner, 2026-11-16: "jeg bruker ikke vibbs egen PIN lås, den har større problemer med å holde alt låst ... vi bruker
ikke noe annet enn androids pin lås, det er den vi bygger for!"

## 0. Summary

- Every phone runs Android's PIN lock (design 59's "android mode"). Vibb One's own keypad lock ("own mode", handy step
  10) is removed from the launcher, the server, the PWA and the docs.
- **What stays** is everything android mode uses: the glance as Android's face (`PinLockActivity` and its runtime keep
  their names), the boot cover and `BootHome`, `HomePin`, lock task while LOCKED, the status-bar heal, the camera
  lock's fallback, the re-front's face rows, the VoIP paths, the notice wake, `set_kid_pin`, the reset token, the
  Parent PIN (it never opened Android's lock).
- **What goes** is what only our keypad needed: the keypad stage and its back-off, the keyguard switched off
  (`setKeyguardDisabled(true)`, COVER/DISABLED), HOME_THEN_LOCK, the Home night gate and the boot mark, the keypad
  migration, the rollback to own mode, 24b's credential removal, the lock switch in the PWA, the `kid_keyguard` column.
- **Wire**: `kid_lock` keeps its keys; `keyguard` is always `"android"` (an older launcher then never falls back to
  own mode). The status report's `lock_state` gains one `inactive` word, `no_android_pin`. No policy key is added or
  removed, so `policy_json_keys_snapshot` and `PolicyResponseCompatTest` stay as they are.
- **No phone without a lock** (QA H1): a phone with a Child PIN but no Android credential is an orange line and
  Attention's `lock_off` unless the PIN is on its way, and once Android has the Child PIN the Device Owner sets the
  password quality NUMERIC, so Settings can't offer "None" (§4.5).
- **Order**: the server first (it forces Android's lock on every phone and drops the column), then the launcher.
- **Design 91 is not built** (Lost/beacon, HELD until the Jelly Star measurements). It no longer needs to come
  first: every phone in use already runs Android's lock, so phase D doesn't widen the restart gap (§9, Q2).

## 1. What is true today (checked against the code)

- The server stores the choice in `device_policy.kid_keyguard` (`'own'` | `'android'`, migration 0085, column default
  `'own'`). Since 2026-11-16 a phone added on the Devices tab is made with `'android'`
  (`devices::insert_device_with_policy`, `server/src/handlers/devices.rs:643-649`); older rows keep what they had.
- The policy's `kid_lock` is `{pin_hash, pin_salt, pin_length, keyguard, pin_rev}` or null (no Child PIN);
  `keyguard` is `"own"` for anything stored but `"android"` (`kid_lock::policy_kid_lock`).
- The launcher reads it with `androidRequested(kidLock) = kidLock?.keyguard == "android"`
  (`lock/AndroidLockPlan.kt:33`) and turns the face on with `androidFace(requested, managed, hasKidPin, deviceSecure)`
  (`:36`). Until Android has a credential our keypad is the gate, and its next correct Child PIN becomes Android's
  (`migrationDue`, `AndroidLock.migrateIfDue`) unless `set_kid_pin` sets it first.
- The PWA's Lock Screen & PINs has the select "Lås: Androids lås / Vibb Ones lås (fases ut)"
  (`templates/device_lock.html:45-50`, `POST /devices/{id}/kid-lock/keyguard`, `devices::update_kid_keyguard`).
- `pin_lock_v1` appears only in server tests; the launcher claims `android_keyguard_v1`
  (`lock/AndroidLockPlan.kt:312`), which gates `set_kid_pin` on the server.
- The audit's R2 line (`docs/review/2026-11-17-maintenance-audit.md:209`) says to delete `PinLock*` and `HomePin`.
  That is too wide: `PinLockActivity`/`PinLockRuntime`/`PinLockState`/`PinLockStore` host the glance (Android's face)
  and `HomePin` is android mode's own (59d step M). §3 is the exact list.

## 2. Inventory (grep-verified on `72840de22`)

The commands used are in §2.6, so the builder can run them again on the build's base.

### 2.1 Launcher (`launcher/app/src/main/java/com/kidslauncher/mdm/`)

"Own only" = used only while the face is off with a Child PIN set (our keypad as the gate). "No Android PIN" = the
paths of a managed phone without a Child PIN (no lock at all), which stay.

| File | Own-mode parts | Verdict |
|---|---|---|
| `lock/AndroidLockPlan.kt` | `androidRequested`; `migrationDue`; `FaceStep.OWN_KEYPAD` (`:82`, `:93`); `androidRollback`/`AndroidRollback`; `PinCommandAnswer.OWN_LOCK` (`:322`, "own lock"); the switch pieces of §2.1a | `androidFace(managed, hasKidPin, deviceSecure)` without `requested`; `faceStep` with `deviceSecure == false` -> LEAVE (the glance closes; the apply turns the lock DISABLED with `NO_ANDROID_PIN`, §4.5 - never a dead "Lås opp"); the rest per §2.1a |
| `lock/AndroidLock.kt` | `migrateIfDue` (`:204`); the rollback's clear (`:262`); the "next correct Child PIN becomes Android's" log (`:167`) | **Goes**; `applyPinCommand`, the token step, `onPasswordChanged`, `lockNow` on the crash guard, the restart line **stay** |
| `lock/KeyguardCoverPlan.kt` | `KeyguardMode.COVER`, `.DISABLED`, `coverWanted`, the cover's write (`:157-181`), `off(..)` with `disable = true`; `androidHeld` | **Goes**. `keyguardMode` = ANDROID when `managed && deviceSecure`, else UNTOUCHED (a row each in `KeyguardCoverPlanTest`); nothing ever writes `setKeyguardDisabled(true)` again |
| `lock/KeyguardCover.kt` | the COVER dismissal and checks, `Inputs.androidRequested`, "a cover left by an earlier process" (`:108`) | **Goes**; the one keyguard writer and `whenUnlocked`/`runAfterUnlock` stay |
| `lock/PinLockState.kt` | `wantKeyguardDisabled`; `lockActivation`'s `ANDROID_CREDENTIAL`, `KEYGUARD_NOT_DISABLED`, `BAD_HASH` rows and its `androidGate` parameter; `kidConfigFor`, `pinWriteFor`, `backoffStepDue`, `appendDigit`, `deleteDigit`, `enteredAfterScreenOffs` | **Goes**. `lockActivation(managed, hasKidPin, deviceSecure, guardTripped)`: no Child PIN `NO_PIN`, unmanaged `UNMANAGED`, `!deviceSecure` -> off with `NO_ANDROID_PIN` (DISABLED: no glance), the crash guard, else on. `step` (DISABLED/LOCKED/UNLOCKED), the process start LOCKED, the face's first CE start UNLOCKED stay |
| `lock/PinLockStore.kt` | the sealed `pin_hash`/`pin_salt` (and their AADs), failures, back-off fields, `keyguard_requested` | **Goes**. A one-shot at process start (`PinLockRuntime.init`, before the first configure) removes the old keys with `commit()` and logs once (QA L5: no PIN material left on disk, no migration). `android_pin_rev`, the own-change record, `android_boot_count`, `active`/`inactive`, `keyguard_android` ("we own the credential") stay |
| `lock/PinBackoff.kt` | the whole file (our keypad's wait) | **Goes** (Android's Gatekeeper: 5 tries, then 30 s, in the TEE) |
| `lock/PinKeypadLayout.kt`, `lock/PinKeyHighlight.kt` | the whole files | **Go** |
| `lock/PinLockActivity.kt` | the KEYPAD stage (`:259-266`, `:332`, `:353`, `:940-943`), the keypad's digits and dots, `migrateIfDue` after a correct PIN (`:1066`), the "Parent code" on the lock and the ring card, `UNUSABLE` | **Go**. The glance page, the ask (`requestDismissKeyguard`), `go()`/LEAVE, the emergency pill and the prompt line (`binding.pinPrompt`, `:1106`) stay |
| `lock/LockGlance.kt` | `GlanceStageEvent.OwnKeypad`, the KEYPAD stage, `GlancePrompt.UNUSABLE` (`:768-780`) | **Go**; "Lås opp" and a row always ask Android |
| `lock/GlancePanel.kt` | "our keypad in own mode" branches (`:537`, `:1084`) | **Go** |
| `lock/PinLockRuntime.kt` | the keypad's PIN check and back-off, HOME_THEN_LOCK (`:309`, `:812-822`), the ScreenOn backstop start, the never-give-up re-front, "unreadable: own mode's rule" (`:342`), the parent-code unlock | **Go**; the face's steps, `lockUp` (LOCK only), sleep check, RELOCK, the crash guard's `lockNow`, the notice wake, VoIP, `verifyCommandPin` stay |
| `lock/LockUpPlan.kt` | `LockUpStart.HOME_THEN_LOCK` (`:25`, `:56`), `darkLockUp` (`:139`) | **Goes**: LOCK or NONE |
| `lock/RefrontPolicy.kt` | the rows reached only with `androidLoss == null` (0.5 s, 2 s, then every 5 s, never giving up; `DarkLockUp`; the stop-only SOS mark) | **Go**. The face's `androidLossStep`, the exemptions, the one wait for `USER_PRESENT` and the RELOCK backstop (today "own mode's rows" after B-5, `:150`) **stay** - the backstop is renamed, not removed |
| `lock/HomeGate.kt`, `lock/BootMarkPlan.kt` | 16c's night Home and 16e's 3 s mark (59d: inert in face mode) | **Go**, with Home's and `PinLockRuntime`'s callers (`:353-450`) |
| `lock/CameraLock.kt`, `lock/CameraLockPlan.kt` | the suspension while LOCKED was own mode's main path | **Stay as the fallback** (59d: used only when the ANDROID read-back lacks `KEYGUARD_DISABLE_SECURE_CAMERA`) |
| `lock/BootCover.kt`, `BootCoverPlan.kt`, `BootCoverGuard.kt`, `BootCoverActivity.kt` | "own mode" branches (compat-fix-1's `ownModeCover`, `OWN_MARKER`, the 16b enable-from-shutdown) | **Stay, renamed "no Android PIN"**: they are also the path of a managed phone before its Child PIN (Android 14 hung at every boot without compat-fix-1's cover, emulator §6at). Only comments and names change |
| `lock/HomePin.kt`, `lock/BootHome.kt`, `lock/HomeFront.kt`, `lock/LockTaskRoot.kt`, `lock/LockTaskChrome.kt`, `lock/StatusBarHealPlan.kt` | comments naming own mode; `leaveInFront`'s `!androidFace` (`LockTaskRoot.kt:151`) | **Stay**; `leaveInFront`/`lockLeave` lose the own branch (the face is the only face) |
| `lock/NoticeWakePlan.kt` | own mode's 15 s `lockNow` (`:104`) | **Goes** (59d step E: the face uses the screen timeout) |
| `lock/VoipCallPlan.kt`, `lock/VoipCalls.kt` | own mode's 17b send and try detection (`:291`, `:349`) | **Go**; 59b's one heads-up send and the card stay |
| `server/WindowRelockPlan.kt`, `server/WindowRelock.kt` | `credentialAction` (24b step 1: remove a credential set in a window), `resetTokenNeeded` | **Go**: Android's PIN is the Child PIN; a PIN the kid changes in a window is the PWA's "isn't the Child PIN" line. `androidTokenStep` runs at every apply (§6) |
| `server/FingerprintEnrol.kt` | "Own mode never shows it", the pause after an `OWN_KEYPAD` reset | Comments and the guard simplify |
| `server/ParentPinLockout.kt` | `PIN_LOCK("pin_lock")`, the lock's "Parent code" entry | **Goes** (no caller); the server keeps the word for stored events |
| `server/dto/PolicyResponse.kt` | `KidLock`'s doc ("anything else is Vibb One's own lock") | The DTO keeps every field (the compat test decodes them); `keyguard` and `pinLength` are no longer read |
| `setup/SetupSteps.kt` | `childPinDone`'s `keyguard != "android" -> true` (`:255`) | That row goes: a Child PIN stored is done when Android has a credential |
| `ui/wallpaper/Wallpapers.kt` | own mode's lock fill and the design 08 opt-in branch (`:109-125`) | **Goes**: the face's ground alone |
| `ui/HomeActivity.kt`, `ui/LockActivity.kt`, `Application.kt`, `shortcuts/BrightnessSheetActivity.kt`, `server/MdmSyncWorker.kt:802`, `server/LockTaskHelpers.kt:326`, `calls/EmergencyCall.kt` | HomeGate/BootMark callers and comments naming the own lock | Callers go with their files; comments reworded |
| `res/layout/activity_pin_lock.xml`, `res/values*/strings.xml` | the keypad, the dots, the back arrow, "Foreldrekode" (`pin_lock_wrong`, `_wait`, `_checking`, `_unusable`, `_parent_code`, `_parent_wrong`, `_parent_locked_out`, `_parent_not_set`, `_delete`, `_dots`) | **Go**; `pin_prompt`, `pin_emergency*` and their strings stay (the glance uses them) |

### 2.1a The switch's own pieces in android mode (QA M1)

Design 59 built android mode as one side of a switch; these pieces exist only because of the switch.

| Piece (`lock/AndroidLockPlan.kt` unless named) | What it does today | Verdict |
|---|---|---|
| `requested` (`androidRequested`) and CE `keyguard_requested` (`PinLockStore.kt:46`, written through `requestToStore` from `PinLockRuntime.kt:1860`) | the policy's choice, kept for a process start and for 24b | **Go**: no choice is left |
| `requestToStore` (`:225`) | writes `keyguard_requested` only from an apply with the parent's choice | **Goes** with the flag |
| `WindowRelockStore.androidKeyguardRequested` (`server/WindowRelockStore.kt:229`) | skips 24b's credential step | **Goes** with `credentialAction` |
| `rollbackDue` (`:206`), `rollbackSyncDue` (`:213`), `rollbackAllowed` (`server/PolicyGate.kt:306`), their caller `AndroidLock.kt:103-132` | the edge out of android mode: clear Android's PIN when the server's own policy no longer asks for it | Q1 (a): **all go** - nothing clears Android's PIN. Q1 (b): kept, narrowed to "we own the credential and the server's own policy has `kid_lock: null`", the quality cleared first (§4.5) |
| `androidKeyguardHeld` (`:221`, `PinLockRuntime.kt:1839`) | keeps the ANDROID keyguard write on an apply without the parent's choice while we own a live credential | **Goes**: `keyguardMode` is ANDROID for any managed phone with a credential, fallback apply or not |
| `androidGate` (`:358`) = `face` or (`owned && deviceSecure`) | the remote lock's answer, the boot cover's marker (`BootCover.kt:77-91`, `AppEnforcer.kt:713` through `AndroidLock.gateNow`), `MdmSyncWorker.kt:801` | **Stays** (a credential we own on a fallback apply is still Android's gate) |
| `keyguardReport` (`:368`) | `"android"` while the gate, else `"own"` with our hash's rev | **Changes**: always `"android"`; the rev Android's PIN was set from (owned), else `null`; no credential is `inactive: no_android_pin` |
| `pinCommandStep(requested, ..)` (`:336`) and `PinCommandAnswer.OWN_LOCK` | answers "own lock" when the fresh policy isn't android | `requested` and `OWN_LOCK` **go**; `no_pin`, `mismatch`, `no_token`, `failed`, `pin set` stay |
| `BootCoverPlan.bootCoverAndroidMarker`, `BootCoverGuard`'s markers | the cover's mode before the first unlock | **Stay** (`androidGate` feeds them) |

### 2.2 Launcher tests (`launcher/app/src/test/`)

- Go or shrink: `PinBackoffTest`, `HomeGateTest`, `BootMarkPlanTest`, `PinLockLayoutTest`, `KeyguardCoverPlanTest`
  (COVER/DISABLED rows), `LockUpPlanTest`/`LockUpTest` (HOME_THEN_LOCK), `PinLockStateTest` (the keypad and
  `lockActivation`'s own rows), `RefrontPolicyTest` (own rows), `AndroidLockPlanTest`/`AndroidLockFaceTest`
  (`androidRequested`, `migrationDue`, `OWN_KEYPAD`, the rollback; P59-5's "own/missing/junk" rows), `LockGlanceTest`
  (the keypad stage), `WindowRelockPlanTest` (`credentialAction`, `resetTokenNeeded`), `KidPinSealTest`,
  `SealedGlueTest` (the hash's AAD), `CameraLockTest` (own rows only), `FingerprintEnrolTest`.
- New rows: `KeyguardCoverPlanTest` (ANDROID = `managed && deviceSecure`, a fallback apply included; UNTOUCHED
  otherwise); `PinLockStateTest`/`AndroidLockPlanTest` (`!deviceSecure` with a Child PIN -> DISABLED with
  `NO_ANDROID_PIN`, `faceStep` LEAVE; no keypad stage reachable); the quality step (§4.5) as pure rows.
- Stay: `PolicyResponseCompatTest` (the keys are unchanged), `ParentPinCallersTest` (`commandPinVerifies` stays the
  one kid-PIN `PinHash.verify` caller), `PinHashTest`, `SetupStepsTest` (with the contract row below),
  `StatusBarHealTest`, `TimeLockModelTest`.

### 2.3 Server (`server/`)

| Where | Own-mode parts | Verdict |
|---|---|---|
| `migrations/0085_kid_keyguard.sql` | `kid_keyguard` | Frozen. A new migration drops the column (§5) |
| `src/models.rs:205` | `DevicePolicy.kid_keyguard` | **Goes** |
| `src/kid_lock.rs` | `KEYGUARD_OWN`; `policy_kid_lock`'s branch; `queue_pin_command`'s `kid_keyguard = ?3` and its intake trigger "reports `keyguard: own`"; `LockNote::Off(AndroidCredential / KeyguardNotDisabled / BadHash)`, `WrongPins`, `Backoff`, `SwitchingBack`, `AndroidWaiting`; `LockState.failures`/`backoff_until_ms`; `PinAnswer::OwnLock` | `keyguard` always `"android"`; the queue rule without the column, its intake trigger "the phone has no Android PIN" (`keyguard: own` **or** `inactive: no_android_pin`); the notes in §4.4; `PinAnswer::OwnLock` kept for stored rows |
| `src/handlers/devices.rs` | `insert_device_with_policy`'s `kid_keyguard` (`:643-649`); `clear_kid_pin`'s `kid_keyguard != ?` (`:3722`); `update_kid_keyguard` (`:3867-3940`) | The insert drops the column; the switch handler **goes**; Remove Child PIN per Q1 |
| `src/main.rs:1295` | `POST /devices/{id}/kid-lock/keyguard` | **Goes** (404) |
| `src/handlers/locate.rs:1665-1693` | `android_lock` = `kid_keyguard = 'android' AND kid_pin_hash IS NOT NULL` | `kid_pin_hash IS NOT NULL` |
| `src/setup_status.rs:766` | `android_lock` input = keyguard android | `policy_kid_lock(policy).is_some()`; `When::AndroidLock` keeps its name (the contract's `when` word) |
| `src/attention.rs` | `lock_off` via the card's notes | Follows §4.4 |
| `templates/device_lock.html` | the Lock select (`:45-50`), `keyguard_android` branches (`:98`, `:118-119`), the own info panel | The select goes; one (i) (§4.4) |
| `src/i18n/en.json`, `nb.json` | `device.lock.lock`, `.lock_after_pin`, `.own_lock`, `.android_lock`, `.save_lock`, `.footer_own`, `.child_info_own`, `.remove_needs_pin`, `.note.android_credential*`, `.note.keyguard_not_disabled*`, `.note.bad_hash*`, the switching-back and back-off notes, `.flash.keyguard_needs_pin`, `.flash.saved_not_kept`, `security.detail.keyguard.*` | Go, except what stored rows still read: `security.event.kid_keyguard_changed` and `security.detail.keyguard.*` (the security log keeps 180 days), `device.lock.pin_answer.own_lock` and `.pin_row.own_lock_sub` (an old `set_kid_pin` answer), the `pin_lock` entry word of `device_pin_events` (90 days). `i18n::tests` keeps both catalogs equal |
| `src/tests/` | `kid_keyguard.rs` (and `mod kid_keyguard;` in `tests/mod.rs:50`), `android_lock_page.rs`, `step10.rs`, `attention.rs`, `device_pages.rs`, `i18n_pages.rs`, `setup_status.rs` (`:562-566` sets `kid_keyguard = 'android'`), `seeded_family.rs` (`:489`, the same `UPDATE`), `command_receipts.rs`, `calls.rs`, `music_spotify.rs`, `parent_pin.rs`, `lock_glance.rs` | Changed in D1 with the column (QA L4): the two `UPDATE`s lose `kid_keyguard`; own-mode cases go (`kid_keyguard.rs` folds into `android_lock_page.rs`, its `mod` line with it); the helper's plain `INSERT` no longer makes an own-lock phone |

### 2.4 Contract files (both directories, one commit)

- `server/testdata/setup_steps.json` and `launcher/app/src/test/resources/setup_steps.json`: the
  `child_pin_readings` row "Vibb One's own lock with a Child PIN" (`keyguard: "own"`) goes. No other contract file
  carries the mode (grep for `keyguard` in `server/testdata/` and the launcher's test resources: only that file; two
  launcher-only rule files use the word "keyguard" in a row's name, unrelated).
- No policy key changes: `kid_lock`'s inner keys stay (`step10.rs:130`'s key list, `PolicyResponseCompatTest`).
- `lock_state` is not a contract file; its new word is typed on the server (`kid_lock::Inactive::parse`).

### 2.5 Docs and emulator sections

- `launcher/CLAUDE.md` (one mention), `server/CLAUDE.md` (the keyguard gotcha's lead-in, the data model's
  `kid_keyguard`), `docs/notes/launcher/lock-and-glance.md` (20 mentions: the step 10 section is rewritten as "the
  glance's host" or folded into the design 59 section), `docs/notes/server/hardening-and-pins.md` (12: "Handy's own
  PIN lock", the switch, "Android's lock by default"), `docs/notes/server/pwa.md` (`:253`, `:310`, `:324`), and one
  line each in `docs/notes/launcher/{calls,dns-and-vpn,gotchas-and-history,hardening,logins,push-and-play,security,
  setup,time-rules,ui-polish}.md`, `home-and-photos.md` (4: 16b/16c and compat-fix-1's "own mode's boot HOME",
  reworded "no Android PIN"), `docs/setup/android-settings.md` (checked: its lines are about Android's own face, kept).
- `docs/testing/emulator.md`: §6c (step 10's own lock and call screens: the lock part goes, the call screens stay),
  §6k (design 32's lock-up with HOME_THEN_LOCK: goes), §6at (retitled "before the first unlock without an Android PIN";
  its own-mode steps become the no-Child-PIN case), §2711's own-lock note, and the design 59 rows that switch the mode
  through the PWA (`keyguard=android`, `:4785`).
- Design notes 10, 16c, 16e, 32, 59, 59b, 59d stay as history; each gets a one-line "Superseded in part by design
  132" under its status (no other edit).

### 2.6 The greps

```
grep -rn -i "own mode\|own lock\|OWN_KEYPAD\|migrationDue\|migrateIfDue\|HOME_THEN_LOCK" launcher/app/src/main
grep -rn "androidRequested\|androidFace\|KeyguardMode\.\(COVER\|DISABLED\)\|PinBackoff\|HomeGate\|BootMark" launcher/app/src
grep -rn "kid_keyguard\|KEYGUARD_OWN\|KEYGUARD_ANDROID\|update_kid_keyguard" server/src server/templates server/migrations
grep -rn "keyguard" server/testdata launcher/app/src/test/resources
grep -rln -i "own mode\|Vibb One's lock\|own PIN lock" docs launcher/CLAUDE.md server/CLAUDE.md
```

## 3. Removed and kept - the precise line

| Piece | Own mode only? | Phase D |
|---|---|---|
| The glance (lock page, rows, clock, weather, Meldinger, frosted cards) | No - it is Android's face | Kept |
| "Lås opp" / a row -> Android's PIN pad (`requestDismissKeyguard`) | No | Kept, the only unlock |
| Our keypad, its dots, wrong-PIN wait (`PinBackoff`) | Yes | Removed |
| "Foreldrekode" on the lock and the ring card | Yes (already off as the face) | Removed |
| The Parent PIN itself (Kid Settings, pause, rule pause, Play, offline override) | No | Kept |
| Boot cover (16b/F3) and `BootHome` | No: the face's restart and the no-PIN boot | Kept |
| Home's night ground (16c), the 3 s boot mark (16e) | Yes (inert as the face) | Removed |
| `HomePin` (the cover's PPA order) | No (59d step M) | Kept |
| Lock task while LOCKED, `LockTaskChrome`, status-bar heal | No | Kept |
| Re-front: never-give-up loop, dark lock-up | Yes | Removed; the face's capped steps and the RELOCK backstop kept |
| HOME_THEN_LOCK, the ScreenOn backstop start | Yes | Removed |
| `setKeyguardDisabled(true)`, COVER, DISABLED | Yes | Removed |
| Camera suspension while LOCKED | Own mode's main path, the face's fallback | Kept as the fallback |
| Notice wake's 15 s `lockNow` | Yes | Removed |
| VoIP 17b send and try detection | Yes | Removed (59b's one send kept) |
| Keypad migration (`migrateIfDue`) | Yes | Removed (§4.2) |
| Rollback android -> own (`androidRollback`) | Yes | Removed (Q1 decides the no-PIN edge) |
| 24b relock step 1 (remove a credential set in a window) | Yes | Removed |
| `set_kid_pin`, the reset token, `onPasswordChanged`, `pin_rev` | No | Kept; the token runs at every apply |
| `ANDROID_KEYGUARD_FEATURES`, the restart line at shutdown | No | Kept |
| Crash guard | Both | Kept: `lockNow` and SystemUI's face |
| `disallow_safe_boot` set by the first Child PIN | No (safe mode drops Vibb's policies' enforcement) | Kept |

## 4. The policy and the API (step 1: server, with the contract row, in one commit)

### 4.1 Policy

`kid_lock` stays `{pin_hash, pin_salt, pin_length, keyguard, pin_rev}` | null. `keyguard` is the constant `"android"`.

- An older launcher (any build with design 59) reads `"android"` and runs android mode: it never falls back to own
  mode while the server is new and the launcher old. This is why the server goes first.
- `pin_hash`/`pin_salt` stay: the phone checks a `set_kid_pin` against the fresh policy's hash
  (`commandPinVerifies`, S59-1's guard). The phone no longer stores them (§2.1). Dropping them from the wire would need a
  rev on the command; not worth it here.
- `pin_length` stays on the wire and is no longer read (Android's pad needs none).

### 4.2 No keypad migration; a PIN the server can't send

Without our keypad the only way the Child PIN reaches Android is `set_kid_pin`, which needs a sealed copy
(`kid_pin_sealed`). Two server cases have none:

- **A legacy PIN** (saved before 0085, `kid_pin_sealed` NULL): §4.5's rule makes it the orange line as soon as the
  phone reports `no_android_pin` (no row can be queued). At every server start the server logs how many phones have
  such a PIN (QA L1: one `info` line, `legacy Child PINs without a copy: N` with the device ids, only when N > 0;
  never PIN material), so the owner can save them again before the launcher update. Nothing is written to those rows.
- **The key file unreadable at a save**: today the PIN is saved without a copy (`saved_not_kept`). After phase D the
  save is **refused** with nothing written - flash `save_not_kept`: "Ikke lagret: serveren kan ikke lese nøkkelfilen."
  / "Not saved: this server can't read its key file." (QA L2: one sentence; the server log says why).

### 4.3 The status report

`lock_state` keeps its fields. Changes:

- `keyguard`: the launcher always reports `"android"`. The server still reads `"own"` from an older build (below).
- `inactive`: the words are `no_pin`, `unmanaged`, `crash_guard` and the new `no_android_pin` (a Child PIN is stored,
  Android has no credential yet: the moments before `set_kid_pin` lands, or a PIN the server can't send).
  `android_credential`, `keyguard_not_disabled` and `bad_hash` are no longer sent.
- `failures` and `backoff_until_ms` are no longer sent (0/null); the server drops the fields from `LockState` (serde
  ignores the unknown keys of an older build).

### 4.4 The PWA (Lock Screen & PINs › Child PIN)

Checked against CLAUDE.md "Words in the UI": no choice left to explain, one (i), orange only when something is wrong,
the fix behind the (i) (QA L2).

| Where | Today | After phase D |
|---|---|---|
| The "Lås" row | select Androids lås / Vibb Ones lås (fases ut) | **gone** |
| Footer under the group | "Androids lås: PIN-tastaturet, og fingeravtrykk hvis du legger til et." | gone; the (i) keeps `child_info_android` ("Etter en omstart ...") and gains that sentence |
| No Android PIN, the PIN on its way (§4.5) | row "Androids lås · Venter / Sender PIN-en" | the existing quiet row "Barne-PIN til telefonen · Venter / Sendt 14:02" (`pin_send`) alone |
| No Android PIN, nothing on its way (§4.5) | (keypad migration) | orange "Telefonen har ingen PIN" / "The phone has no PIN" (the existing `device.lock.no_pin`); behind its (i): "Send barne-PIN-en igjen under." / "Send the Child PIN again below." (the existing `no_pin_send`) when a copy is kept, else "Lagre barne-PIN på nytt." / "Save the Child PIN again." (new key `device.lock.no_pin_save`) |
| Phone reports `keyguard: own` (an older build) | "Androids lås · Venter" | the same two rows: §4.5's rule decides |
| Own-mode notes (credential still on, keyguard not off, can't read the hash, wrong PINs, wait, switching back) | rows and orange lines | gone |
| `parent_first` | "Velg en foreldre-PIN først: den åpner låsen hvis barne-PIN-en blir glemt." | "Velg en foreldre-PIN først." / "Set a Parent PIN first." (the Parent PIN doesn't open Android's lock; Show Child PIN does) |
| Remove Child PIN | refused while Android's lock is chosen | Q1 |

Kept unchanged: Show Child PIN, Send the Child PIN again, "Telefonens PIN er ikke barne-PIN-en", "Vibb One kan ikke
endre Androids PIN", "Låst igjen av Vibb One · N ganger", Locate's "Locked with the Child PIN (fingerprint off until
it's typed)", the security log's old lines.

### 4.5 No phone without a lock (QA H1)

A managed phone with a Child PIN stored and no Android credential has no lock at all (the glance is off, §2.1). The
causes: the moments before `set_kid_pin` lands; a legacy PIN; the token not active; Android refusing the PIN; the
PIN removed on the phone (once NUMERIC holds only some way outside Vibb One, e.g. a factory-level path - neither
Settings nor `locksettings clear` can, and the server can't unmanage a phone; §11 G12a/G12b). One rule covers all of
them, whatever the cause:

- **The server**: a report with `inactive: no_android_pin` (or an older build's `keyguard: own`) is the quiet
  `pin_send` row while a `set_kid_pin` of this enrolment is waiting or in flight (`kid_lock::pin_send`'s Waiting, or
  Sent without an answer); with none, or the newest answered `refused`/`failed`, it is §4.4's orange line and
  Attention's `lock_off` (`kid_lock::lock_card` and `attention.rs` read the same note). The queue rule's intake trigger
  still sends one row when it can (a sealed copy, the capability, nothing in flight, the newest not answered); after an
  answer only the parent's Send again tries again (no loop, as today).
- **The phone, so Settings can't remove the PIN**: once Android has the Child PIN (`set_kid_pin` answered `pin set`,
  `keyguard_android` owned), every apply on a managed phone with a Child PIN writes
  `setPasswordQuality(admin, PASSWORD_QUALITY_NUMERIC)` and reads it back (a mismatch logged). Settings › Security ›
  Screen lock then offers PIN and password only - no "None", "Swipe" or pattern. NUMERIC, not
  `setRequiredPasswordComplexity(MEDIUM)`: MEDIUM refuses 4-digit and simple PINs, and the Child PIN is 4-6 digits of
  the parent's choosing.
  - Not before Android has the PIN: a quality the current credential doesn't meet makes Android ask the user to set a
    new screen lock, which would race `set_kid_pin`.
  - `resetPasswordWithToken` with a 4-6 digit PIN passes NUMERIC (no minimum length is set, so Android's own floor of
    4 applies); a unit row and G1 check it, since a refused reset answers `failed`.
  - Under Q1 (b) the phone writes `PASSWORD_QUALITY_UNSPECIFIED` and reads it back **before**
    `resetPasswordWithToken(null)`: a reset to none under NUMERIC fails.
  - Unmanaged or no Child PIN: UNSPECIFIED.
- `setPasswordQuality` is deprecated for plain device admins but supported for a Device Owner on API 34-37; it needs
  no SDK gate (API 8). Lint's deprecation warning is suppressed at that one call with a comment, never NewApi.

## 5. The database (step 1)

The next free migration number at the build (0178 on `72840de22`; whatever is next free on `main` then, QA L3):

```sql
-- Design 132 (phase D): Android's PIN lock is the only lock. kid_keyguard (0085) chose between it and
-- Vibb One's own lock; nothing reads it now. No index, trigger, view, CHECK or foreign key names it
-- (only 0085 does), so SQLite's DROP COLUMN applies. Legacy Child PINs without a sealed copy
-- (kid_pin_sealed IS NULL AND kid_pin_hash IS NOT NULL) are left as they are: the page asks to save them again.
ALTER TABLE device_policy DROP COLUMN kid_keyguard;
```

- sqlx runs each migration inside its own transaction, and SQLite's `DROP COLUMN` is transactional: a failure leaves
  the column and the version row as they were.
- Tested (QA L3) from the previous head's schema: a database migrated to the migration before it, with own and
  android rows, migrates and serves `"android"` for both; and a backup zip of that database passes
  `backups::check_file` and restores into the new schema.
- Safe on real data: the column carries nothing other columns need; the startup's pre-migration backup
  (`startup::pre_migration_backup`) runs first, as for every migration; `backups::schema_matches` compares the
  migrated schema, so an old backup restored later is migrated the same way.
- `kid_pin_hash`/`_salt`/`_length`, `kid_pin_sealed`, `kid_pin_prev_*`, `kid_pin_rev` stay (the cross-check with the
  Parent PIN, Show Child PIN, `set_kid_pin`).
- A server rollback after this migration needs the pre-migration backup (sqlx refuses an unknown applied migration),
  as for any migration; D2 checks that DEPLOY.md's rollback steps say so.

## 6. A phone still reporting own mode

The server never breaks on it:

- **An older launcher (design 59 or later)** gets `keyguard: "android"`. It reports `keyguard: own` until Android has
  the Child PIN; the queue rule's intake trigger sends `set_kid_pin` (a sealed copy, `android_keyguard_v1`); the
  phone sets Android's PIN and reports `android`. A legacy PIN without a copy migrates through that build's own keypad
  at the kid's next unlock (the older build still has it), so even that case heals on an older launcher.
- **The new launcher** never reports `own`. A report of `keyguard: own` from any build is read as "no Android PIN"
  and follows §4.5's rule (quiet while a PIN is on its way, else orange and `lock_off`).
- **A launcher before design 59** (no `android_keyguard_v1`): none exist (only emulators and the Jelly Star run Vibb,
  CLAUDE.md "No backwards compatibility on the phones yet"). The server sends it no PIN (the queue rule's capability
  test, unchanged) and the page shows the "no Android PIN" line; no new code.
- **Keyguard left disabled on the phone** by an own-mode build (`setKeyguardDisabled(true)` persists in DPM across an
  update): the new launcher's every apply writes `setKeyguardDisabled(false)` in both remaining modes (UNTOUCHED and
  ANDROID), so the first apply after the update undoes it. A gate row checks it (§11, G3).
- Phone-side data: no migration. `PinLockStore`'s own keys are removed at the first write (§2.1); a stored
  `keyguard_android` is ignored.

## 7. Setup: QR and Koble til

New phones are already made with Android's lock (2026-11-16). What phase D adds:

1. **The reset token at enrolment (QA M5).** Today the token is set in android mode at an apply
   (`androidTokenStep`), so a screen lock set in Android's setup wizard before Vibb's first apply leaves it escrowed.
   The hook is `applyProvisioningExtras` (`server/Provisioning.kt:28`), the one apply that both the QR (from
   `PolicyComplianceActivity` and `onProfileProvisioningComplete`, through `QrExtras`) and Koble til
   (`QrExtras.enrolTyped`) run: after the server address is accepted, `androidTokenStep` runs once. The token is stored
   in `WindowRelockStore` (CE, sealed under r10); CE is unlocked at both points (the setup wizard, a running Home). The
   token step then also runs at every apply in every state (no `requested` gate).
   - **QR**: the compliance activity runs before the setup wizard's screen-lock page, with no credential, so the token
     is active at once and `set_kid_pin` can replace any PIN set later in the wizard.
   - **Koble til**: it runs after the setup wizard. With a screen lock already set there the token is escrowed and
     activates at the next unlock; until then `set_kid_pin` answers `no_token` and the page shows "Vibb One kan ikke
     endre Androids PIN" with "Lås opp telefonen én gang først." / "Unlock the phone once first." behind its (i). After
     that unlock, Send again (or the intake trigger at the next report) sets the Child PIN.
2. **The setup wizard's own "screen lock" page** (GMS phones): Vibb can't hide it. Skipping it is the runbook's advice
   (`docs/setup/google-account.md`, one line); after point 1 not skipping costs nothing on the QR path and one unlock
   on Koble til.
3. **The setup steps** (design 114): `child_pin` stays required and read by the phone (`childPinDone`: a stored Child
   PIN and Android's credential); `fingerprint`, `lock_clock`, `media_on_lock` keep `when: android_lock`, which now
   means "a Child PIN is stored".
4. **Order on a new phone**: Parent PIN, then Child PIN in the PWA (before or after the QR), then the phone sets
   Android's PIN within one sync and the quality NUMERIC (§4.5). Before the Child PIN the phone has no lock, as today;
   the setup page's `child_pin` step says so.

## 8. Android 14-17

The code stays 14-17 compatible (CLAUDE.md): no new platform API beyond `setPasswordQuality` (API 8, supported for a
Device Owner through 37), and every call that stays was already in android mode: `setKeyguardDisabled(false)`,
`setKeyguardDisabledFeatures`, `setMaximumTimeToLock`, `setResetPasswordToken`/`isResetPasswordTokenActive`/
`resetPasswordWithToken` (API 26), `requestDismissKeyguard` (API 26), `lockNow`, `setDeviceOwnerLockScreenInfo`,
`isDeviceLocked`/`isKeyguardLocked`. Removed paths remove version risk (`setKeyguardDisabled(true)`, the camera
suspension as the main path). The gate runs on 14 and 16 only (QA M3: the owner says 17's emulator image doesn't
work).

- **14** (`compat34`): the boot without an Android PIN still needs compat-fix-1's cover (kept); the token activates at
  once with no credential (design 59's RC run).
- **15**: no change to the keyguard, token, password-quality or lock-task paths used here (the device-owner behaviour
  changes checked); not gated, between 14 and 16.
- **16** (`jelly36`, the Jelly Star): Android 16's emulator-only Pixel SystemUI trigger is the face's, unchanged.
  **Identity Check** (Android 16's biometric step for some Settings changes away from trusted places) gets its own
  Jelly Star row (J1, §11): whether it touches a Device Owner's `resetPasswordWithToken` or the quality write. Nothing
  in AOSP's DPMS path suggests it; the emulator can't test it.
- **17**: code-level only (the APIs above are unchanged on 37). Settings can't be hidden there (compat-fix-1); with
  NUMERIC the kid still can't choose "None", and a changed PIN is the PWA's "isn't the Child PIN" line.

## 9. Design 91 and the order

PLAN's Phase D entry says design 91's beacon (Lost mode, find a phone before the first unlock after a restart) "is
meant to land before it". Checked: design 91 is **not built** (no beacon, no Lost mode in `server/src` or the
launcher; PLAN: HELD until the Jelly Star measurements U1-U4). The reason for the order was that phase D puts every
phone in android mode and so widens the before-the-first-unlock gap. That has already happened: since 2026-11-16 new
phones get Android's lock, and the owner's phones run it. Phase D removes code, not reach. Recommendation: build phase
D now, design 91 when its measurements are in (Q2).

## 10. Step plan (small commits; the server first)

| Step | Side | What | Size |
|---|---|---|---|
| D1 | server + contract | Migration (drop `kid_keyguard`); `keyguard` constant `"android"`; the switch route, handler, select and words gone; the queue rule without the column, its intake trigger also on `no_android_pin`; §4.5's rule (quiet while on its way, else orange + `lock_off`); the legacy count at start (L1); `LockState` without `failures`/`backoff_until_ms`; §4.4's notes; `save_not_kept` refusal; Locate and setup inputs; Remove Child PIN per Q1; the `setup_steps.json` row in both directories; the test files of §2.3 (L4); tests (§12) | M |
| D2 | docs | server notes (hardening-and-pins, pwa), `server/CLAUDE.md` | S |
| - | release | server tag (before any launcher with D3) | - |
| D3 | launcher | Pure plans and their tests: `androidFace` without `requested`, `faceStep` without OWN_KEYPAD (`!deviceSecure` LEAVE), `keyguardMode` ANDROID = `managed && deviceSecure`, `lockUpStarts` LOCK/NONE, RefrontPolicy's own rows, `lockActivation` with `NO_ANDROID_PIN`, §2.1a's verdicts, the quality step (§4.5), `childPinDone`, `credentialAction`/`resetTokenNeeded` gone, `PinBackoff`/`HomeGate`/`BootMarkPlan`/`PinKeypadLayout` gone | M |
| D4 | launcher | Glue: `PinLockActivity`'s keypad stage and layout, `PinLockRuntime`, `PinLockStore` (the one-shot key removal, L5), `KeyguardCover`, `AndroidLock` (migration, rollback per Q1, the quality write), `NoticeWake`, VoIP's own paths, `Wallpapers`, `WindowRelock`/`WindowRelockStore`, `PolicyGate`, `ParentPinLockout`, strings; boot cover names "no Android PIN" | M |
| D5 | launcher | The token in `applyProvisioningExtras` and at every apply (§7.1) | S |
| D6 | docs | launcher notes, `launcher/CLAUDE.md`, emulator.md (§6c, §6k, §6at), the superseded lines in designs 10/16c/16e/32/59/59b/59d | S |
| D7 | gate | §11 on 14 and 16, then a launcher RC, then the Jelly Star (J1) | - |

About 1 round server, 1.5 launcher, as the audit estimated (6.6k production and 0.9k test lines out, less the glance
host it counted). Each launcher step builds and passes `testDebugUnitTest` on its own; D3's tests change with D3.

## 11. Emulator gate (Android 14 `compat34`, Android 16 `jelly36`)

**Run 2026-11-26 on 14 and 16** (`docs/testing/emulator.md` §6bt, branch `gate-phase-d`): G1-G7, G9, G11, G12a pass;
G12b passes by its stand-in (unmanaging is not reachable, so the row is corrected to it below); G8 and G10 partial (no `am crash` for a Device Owner, no
VoIP app); J1 open.

G1, G4 and G8 run under both gesture and 3-button navigation on each version (QA M4;
`adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural` / `.threebutton`). The screenshots for
the owner come from `jelly36`.

| # | Row | 14 | 16 |
|---|---|---|---|
| G1 | A new phone: QR/adb provisioning, Parent PIN and Child PIN in the PWA. Within one sync `adb shell locksettings verify --old <Child PIN>` passes, `dumpsys device_policy` shows password quality NUMERIC, the report has `keyguard: android`, `pin_rev` the current, no `inactive`; screen off/on shows the glance, "Lås opp" Android's pad, the PIN opens Home. Both navigation modes | x | x |
| G2 | A PIN set in the setup wizard (QR: the wizard's screen-lock page, or `locksettings set-pin 1111` right after the compliance step): the token is active (the report's `reset_token`), `set_kid_pin` replaces 1111 with the Child PIN, no "can't change" line. Koble til with a PIN already set: `no_token` and "Lås opp telefonen én gang først." behind the (i); one unlock, then Send again (or the next report) sets the Child PIN | x | x |
| G3 | An updated phone: the previous launcher RC on own mode (old server, `keyguard=own`), then the new server (the old RC migrates by `set_kid_pin`), then the new launcher: `dumpsys device_policy` shows the keyguard not disabled, `pin_lock_state` holds no `pin_hash`/`pin_salt`/`keyguard_requested` after the first start, the glance and the pad work | x | x |
| G4 | Restart: Android's pad over the boot cover with the restart line, then Home, no stock launcher, no second PIN. Both navigation modes | x | x |
| G5 | Managed, no Child PIN: `adb reboot` and `adb emu kill` + cold boot boot to Home (compat-fix-1's cover on 14), no lock, no glance, quality UNSPECIFIED, the setup page's `child_pin` step open | x | x |
| G6 | Remove Child PIN as Q1 decides (a: no control on the page, a stale POST refused, Android's PIN stays; b: quality UNSPECIFIED first, then Android's PIN cleared, the report `no_pin`) | x | x |
| G7 | Five wrong PINs on Android's pad: Android's own 30 s wait; no Vibb back-off anywhere; `logcat -s AndroidLock PinLock` has no keypad stage | x | x |
| G8 | `am crash` of the launcher while LOCKED: the glance back, Android still locked (or RELOCK counted); three crashes: `lockNow`, SystemUI's face with the PIN. Both navigation modes | x | x |
| G9 | The Child PIN changed in Settings during a "pause all restrictions" window: the PWA's "isn't the Child PIN" line; Send again sets it back; no credential removed at the window's end | x | x |
| G10 | A VoIP ring (`scripts/fake-homeserver.py`), a call and an alarm over the locked glance; the power double-press opens no camera app but Android's secure camera or nothing | x | x |
| G11 | A legacy row (`UPDATE device_policy SET kid_pin_sealed = NULL`, a fresh launcher with no Android PIN): the start's log line counts it; the PWA's orange "The phone has no PIN" with "Save the Child PIN again." behind the (i), Attention's `lock_off`; saving the PIN again sets Android's | x | - |
| G12a | The kid tries to remove the PIN (QA H1): Settings › Security › Screen lock offers no "None"/"Swipe"/pattern, and `adb shell locksettings clear --old <Child PIN>` is refused ("New credential doesn't satisfy admin policies", AOSP `LockSettingsShellCommand.runClear`) while `dumpsys device_policy` shows the quality NUMERIC | x | x |
| G12b | No Android PIN reached another way (code QA M1). **Corrected after the gate (§6bt finding 3)**: a managed phone can't be made unmanaged from the server - the PWA has no switch for it, a `NULL` allowlist is filled again by the first-heartbeat bootstrap (`device_api`), and the launcher's `PolicyGate` refuses a fresh policy without an app list on a managed phone - so the quality never drops to UNSPECIFIED and Android's PIN can't be cleared while NUMERIC (G12a). The row is its stand-in: the Child PIN saved while the phone is offline, its `set_kid_pin` answered `refused: failed`, the phone with no Android PIN reporting `no_android_pin`: Android's Swipe screen at a wake (no glance, no dead "Lås opp"); the PWA's orange "Telefonen har ingen PIN" with "Send barne-PIN-en igjen under." behind the (i) and Attention's `lock_off` (nothing in flight, the newest row answered); Send again: `pin set`, NUMERIC, the line and the Attention row go. The launcher's `ownedGone` path (owned, no credential left) is not reached by it: it is reachable only if Android's credential is removed some other way, outside Vibb One - e.g. a factory-level path | x | x |

Jelly Star row (not the emulator):

| # | Row |
|---|---|
| J1 | Identity Check on (Settings › Security, away from a trusted place): `set_kid_pin` from the PWA still sets the PIN, the quality write holds, Send again works; the kid's own PIN change in Settings asks for Android's biometric step |

## 12. Tests

- Server: §4.5's rule as rows (`no_android_pin` with a waiting row, an in-flight row, none, a `refused`, a `failed`,
  an older build's `keyguard: own`: quiet, or orange and `lock_off`); the start's legacy count;
  `tests::kid_keyguard` and `android_lock_page` rewritten for one mode (the queue rule's intake on
  `no_android_pin` and on an older build's `keyguard: own`; the policy's `keyguard` always `"android"`; the switch
  route 404; `save_not_kept`); `kid_lock::tests::the_card_words_android_lock_case_by_case` without the own cases and
  with the legacy-PIN line; `setup_status::tests::the_child_pin_readings` (the contract row); `step10.rs`'s key list
  unchanged; the migration and restored-backup tests of §5; `i18n::tests` (catalogs equal, every used key present, the kept history keys still used by the log readers).
- Launcher: the pure tests of D3 (rows removed, `NO_ANDROID_PIN` added, `androidFace` without `requested`, the token
  step without a gate, the quality step: NUMERIC only once Android has the Child PIN, UNSPECIFIED before a Q1 (b)
  clear); `SetupStepsTest` on the shortened contract; a scan test that `setKeyguardDisabled(true` never
  appears in `src/main` (as `TrustedClockScanTest` scans for wall-clock reads).

## 13. Questions for the owner (visible cost only)

**Decided 2026-11-25 (the owner): Q1 (a) - "barne-pin er et must og ikke et valg": Remove goes, the PIN can be changed, never removed. Q2: phase D before design 91.**

**Q1 - "Fjern barne-PIN" after phase D.** Today Remove is refused while Android's lock is chosen, and the way round is
to choose Vibb One's lock first - which goes. Two ways:
- **(a) Remove goes** (recommended): a Child PIN can be changed, not removed; the phone always has Android's lock once
  it has had one. Retiring a phone is Erase or a reset. The launcher then never clears Android's PIN by itself (a
  `kid_lock` that reads null fails closed: Android's PIN stays).
- **(b) Remove stays**, behind its confirm: the server sends `kid_lock: null`, the phone clears Android's PIN with
  its token and has no lock; the setup page then shows the `child_pin` step as not done.

Either way an older launcher (any design 59 build) behaves as today: on a fresh `kid_lock: null` it clears Android's
PIN through its own rollback (QA L6). Under (a) the server never sends that null once a PIN is set, so the old path
never runs; under (b) it does, and an older build set no quality, so its clear works. Revision 2 adds to (b): the new
launcher clears the NUMERIC quality before it clears the PIN (§4.5).

**Q2 - Phase D before design 91.** PLAN says design 91's beacon lands first. Since every phone in use already runs
Android's lock, phase D doesn't widen the restart gap (§9). Recommended: build phase D now (it is item 3 of the locked
list), and design 91 when the Jelly Star measurements are in.

## 14. Revision 2 (design QA, 2026-11-25)

| Finding | Where taken |
|---|---|
| H1 - a phone with a Child PIN and no Android credential has no lock, mostly quietly | §4.5 (one rule: quiet only while a PIN is on its way, else orange + `lock_off`; NUMERIC quality once Android has the PIN, cleared before a Q1 (b) reset), §4.4, §6, G12a/G12b |
| M1 - a verdict for each switch piece | §2.1a; `keyguardMode` ANDROID = `managed && deviceSecure` (§2.1, `KeyguardCoverPlanTest`) |
| M2 - no dead glance without a credential | §2.1 (`faceStep` LEAVE, DISABLED with `NO_ANDROID_PIN`), §2.2's rows, G5, G12 |
| M3 - gate on 14 and 16 only | §8, §11; Identity Check as J1 |
| M4 - both navigation modes | §11 (G1, G4, G8) |
| M5 - the token's hook, CE, Koble til | §7.1 (`applyProvisioningExtras`), G2 |
| L1 - count legacy PINs at start | §4.2, D1, G11 |
| L2 - words | §4.2 (`save_not_kept` one sentence), §4.4 (the fix behind the (i); `keyguard: own` follows §4.5) |
| L3 - migration number, tests, transaction | §5 |
| L4 - the test files changed in D1 | §2.3, D1 |
| L5 - the old keys removed by a one-shot | §2.1 (`PinLockStore`), G3 |
| L6 - Q1's older-launcher note | §13 |
