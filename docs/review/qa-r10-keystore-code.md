# Code QA: design r10 (the launcher's secrets sealed under a Keystore key)

Scope: `git diff 0fb3c6db..e62b6123` (branch `r10-keystore-wrap`, two commits, 39 files, launcher only) against
`docs/design/r10-launcher-keystore-wrap.md` (§1-§8, "QA fixes" R10-1..15, "Implementation status" and its deviations),
`docs/design/qa-r10-design.md`, `docs/review/qa-r10-revision-check.md` (N1-N8), security review P-L5
(`docs/review/security-review-2026-10-21-launcher.md:181`), `launcher/CLAUDE.md` and PLAN. Reviewed on branch
`qa-r10-keystore`.

**Verdict: merge after fixes.** The crypto and the core are right. `KeystoreSecretBox` is the note's spec exactly:
AES-256-GCM in AndroidKeyStore (`secrets_v1`), the Keystore's own random 12-byte IV per seal (randomized encryption
is on by default, no IV is passed in), a 128-bit tag, the AAD `prefs-file/key` on both sides, no user auth, no
`UnlockedDeviceRequired`, no StrongBox, the key never exportable. The keygen runs only in `seal`/`recreate`, always
under the leaf `LOCK`. A blob that doesn't open is `Unreadable` and never touched; the memo keeps values only; `edit`
never falls back to plaintext; the migration re-reads and commits under `LOCK`; a main-thread read never migrates;
`recreate()` comes only after a retry. Every R10-n fix is in, and so are N1, N2, N3, N6, N7 and N4's field hardening.
`kidConfigFor` never returns `null` for a `kid_lock`, `firstLaunchResetDue` uses presence (an unreadable token never
de-enrols), `showReEnrol` is exactly the note's condition and the re-enrol dialog never unlocks. The debug hook stays in
`src/debug`, exported only behind DUMP. No secret or hash reaches a log line or an exception message.

One Medium is in the code: the deadlock's way out ("Ny oppsettkode") doesn't bring the tailnet up, and in the deadlock
nothing else does (Q-1). The other two Mediums are regression guards for the two properties the brief singled out.
The source scan lets a raw `getString("mdm.device_token", …)` through (Q-2), and "an unreadable token never de-enrols"
has no test that fails when it breaks (Q-3). The mutation pass caught 10 of 21 breaks. Every break it missed is in
Keystore-only code, in glue the JVM can't run, or in a source pin that is too loose.

## Findings

### Medium

**Q-1. "Ny oppsettkode" doesn't connect the tailnet first, and in the deadlock nothing else does.**
`server/Enrollment.kt:18` calls `createMdmApi(serverUrl).enroll(…)` directly. `applyProvisioningExtras` runs
`TsnetClient.connectFromPreferences` first (`Provisioning.kt:34`) because a `*.ts.net` server URL resolves only through
tsnet. In the deadlock the token is `Unreadable`, so `performMdmSync` returns before its tsnet retry
(`MdmSyncWorker.kt:86-96`: the token check comes first). The SSE connect has the same early return. The one remaining
trigger is `HomeActivity.onResume` (`HomeActivity.kt:489`), and Home doesn't resume interactively while the lock is up
(a screen-off lock-up resumes it dark, which returns before that line). If tsnet failed at boot (no network yet, the
crash-loop guard, a slow join), every try in the dialog fails until a reboot. The dialog then says "That code didn't
work. Check it and try again, or make a new one." (`ReEnrolDialog.kt:102`), which sends the parent to burn codes on a
network fault. Settings' "Enroll now" isn't affected: the parent code's unlock left through Home, whose resume connected
tsnet.
*Fix:* in `Enrollment.enroll`, before the POST, call `TsnetClient.connectFromPreferences(context)` (already on IO; it
no-ops when connected or when no key is set). Give `Result.failure` a kind (HTTP 4xx vs transport). The dialog then
shows "Couldn't reach the server - check the connection and try again" for a transport failure and keeps
`re_enrol_failed` for a refused code. Test: a source pin that `connectFromPreferences(` precedes `createMdmApi(` in
`Enrollment.enroll`, as the branch's other source pins do.

**Q-2. The source scan doesn't ban the token's pref key as a literal - a raw read passes.**
`SealedPrefsTest.kt:206-222` bans `\bdeviceToken\(` and the resource name `settings_mdm_device_token_key`, and pins
`DeviceToken.get()` per file. Mutation M8b added
`DeviceToken.get() ?: LauncherPreferences.getSharedPreferences().getString("mdm.device_token", null)` to
`PowerEvents.prepare`, and the whole suite stayed green. That is the `Bearer ks1:…` bug R10-10 exists to stop. The
implementation status says the scan pins "the PIN/token keys never read or written raw". That holds for `pin_hash`,
`pin_salt` and `token` (by constant name), not for `mdm.device_token`. The scan also covers only `src/main`.
*Fix:* add `"mdm.device_token"` (outside `SealedPrefs.kt`'s `AAD` constant, e.g. ban the literal `"mdm.device_token"`
everywhere and the AAD's `"default/mdm.device_token"` only outside `SealedPrefs.kt`), `"window_relock/token"` and the
`"pin_lock_state/…"` AADs outside their owners. Scan `src/debug` and `src/release` too. For the reset token, ban
`"token"` with `getString`/`putString` in any file that opens `window_relock`.

**Q-3. "An unreadable token never de-enrols" isn't guarded by any test.**
M7 turned `DeviceToken.present()` (`SealedPrefs.kt:207`) into `get() != null`. That is exactly "treat `Unreadable` as
absent", and it would make `initRest` run `resetPreferences` on an enrolled phone before Home's first start (token,
server URL and cached policy gone). All 1746 tests passed. `FirstLaunchResetTest` pins only the call text in
`initRest`. The rule's input lost its blank-token cases in this branch (the old `""`/`"  "` rows moved into
`present()`, which nothing tests).
*Fix:* split `present()`'s body into a pure `tokenStored(raw: String?) = !raw.isNullOrBlank()` and test it with
`null`, `""`, `"  "`, a hex token and a `ks1:` blob that no box can open. Or pin `present()`'s body in
`FirstLaunchResetTest`: one `getString(key(), null)`, no `state()`/`get()`/`read(`.

### Low

**Q-4. `recreate()` deletes a working key after two transient failures (N8, and the kid PIN too).**
`SealedPrefs.kt:140-146`: two seal failures in a row, of any kind, delete `secrets_v1`. A keystore2 that answers
`BACKEND_BUSY`/`ProviderException` twice during an enrolment (seen at boot on some phones) costs a key that still opens
everything. The kid PIN's blob and the reset token are orphaned: the next process start comes up `BAD_HASH` until a
sync, and a window with a kid-set credential can't be relocked (N8).
*Fix:* `SecretBox.recreate()` only when the key itself is the fault: the alias is missing, `getKey` throws
`UnrecoverableKeyException`/`KeyStoreException`, or `Cipher.init` throws `InvalidKeyException`. Expose it as
`box.keyBroken(): Boolean` (one `getKey` + `init`) and call `recreate()` only when it is true. Otherwise return false
with nothing written. Keep the 1/2/3 test and add "two `ProviderException`s don't recreate".

**Q-5. A failed seal of a new kid PIN is never retried in that process.**
`PinLockRuntime.kt:1544`: `saveConfig` runs only when `newConfig != config` (or `active`/`inactive`/the request
changed). After a `Write` whose seal failed, `saveConfigTo` removes the fields (R10-3) but `config = newConfig` is set.
Every later sync's identical real hash compares equal, and a redacted apply passes `Keep`, so the PIN stays off the disk
for the rest of the process. Any restart, even after an hour online, is `BAD_HASH` until the next sync.
*Fix:* also save when `pinWriteFor(kidLock, newConfig) is PinWrite.Write && !PinLockStore.pinStored(app)`.

**Q-6. The main thread still makes Keystore calls repeatedly, not once per process.**
The note budgets "one decrypt per process (two fields)" on main (§2, R10-7). On main today:
- `PinLockRuntime.init`: the hash and the salt.
- `CommandListenerService.connect` (`CommandListenerService.kt:212`, `handler` on the main looper): the token.
- Settings' Sync now (`SettingsFragmentLauncher.kt:605`).

While the token is `Unreadable` the memo keeps nothing (right, so it can heal). The SSE connect then retries every
30 s (`NOT_ENROLLED_RETRY_DELAY_MS`) with `KeyStore.load` + `getKey` + `Cipher.init` + `doFinal` on main, and
`KeystoreSecretBox` re-opens the KeyStore and re-fetches the key on every call (`SealedPrefs.kt:221,235`). This is the
RC3-4 family: binder calls to keystore2 on the main thread at the unlock, inside `initRest`'s ANR-counted head.
*Fix:* read the token in `connect()` on IO and post the result. Cache the `SecretKey` handle in `KeystoreSecretBox` (a
`@Volatile` field, dropped on any open/seal exception and in `recreate`). Time `PinLockRuntime.init`'s read on the
Jelly Star (emulator check 7 below).

**Q-7. The crash guard's save takes `LOCK` on the main thread.**
`PinLockRuntime.guardTripped` runs from `PinLockActivity.onCreate` (`PinLockActivity.kt:508`) and calls
`saveConfig(PinWrite.Keep, …)` (`PinLockRuntime.kt:1564`), which goes through `sealed.edit` → `synchronized(LOCK)`. A
crash-guard trip comes at a process start, exactly when `Secrets.migrateAll` may hold `LOCK` across a first-time
keygen and three seals. N6's rule (main never waits on `LOCK`) is broken for this one path.
*Fix:* in `saveConfigTo`, a `Keep` with nothing sealed or removed commits a plain editor without `LOCK`. It touches no
sealed key, so it can't race a migration's commit, because the migration commits only its own key.

**Q-8. The re-enrol call runs in the activity's scope; a cancellation after the server consumed the code loses the
token.**
`ReEnrolDialog.kt:94` launches `Enrollment.enroll` in `lifecycleScope`. If the lock or the parent dialog's activity is
destroyed mid-call (a recreation, the crash guard, a finish), Retrofit's suspend call is cancelled. When the server
already consumed the one-shot code and rotated the token, that is a dead old token, no new one, and a new code needed.
Settings' path uses its own `CoroutineScope(Dispatchers.IO)`.
*Fix:* run the enrolment in a process scope (or `withContext(NonCancellable)` around the POST + `DeviceToken.set`), and
report back to the dialog only if it is still showing.

**Q-9. N4/N5 are still open, as the implementation status says.**
The button is in `ParentPinDialog`, which also serves the time-rule screen (`LockActivity`, not lock-tasked with the
kiosk off). There a system or allowlisted IME's own activities (settings, clipboard, voice) can open and stay. It is
reachable only in the deadlock, and only on a phone where the time-rule screen is in front without the PIN lock (no
`kid_lock`, or the lock inactive), so not kid-controllable. Recorded so the user decides, not as a blocker.
*Fix (if wanted):* in `ParentPinDialog` bind the button only while the window's activity is in lock task
(`ActivityManager.lockTaskModeState != LOCK_TASK_MODE_NONE`), or only for the PIN lock's dialog.

**Q-10. The Keystore box's AAD, IV and spec aren't pinned beyond the spec scan.**
JVM tests use `FakeBox`, so the real box's AAD and IV are untested:
- Dropping `updateAAD` in `KeystoreSecretBox.seal` (M1): on a phone, every value written after the upgrade becomes
  `Unreadable` (the token gone silent at the first enrolment).
- A fixed IV plus `.setRandomizedEncryptionRequired(false)` (M3): GCM nonce reuse.

Both passed. `SealedPrefsTest`'s spec test checks four parts present and three absent.
*Fix:* extend `the key is AES-256-GCM…` to count `cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))` exactly twice
(once in `seal`, once in `open`), ban `GCMParameterSpec` inside `seal`'s body and ban
`setRandomizedEncryptionRequired`/`setUserAuthenticationRequired`/`setUnlockedDeviceRequired` anywhere in the file.

**Q-11. Four glue rules from R10-4/R10-7/R10-11 are unpinned.**
All of these passed the suite:
- M6: `migrateHere()` always true, i.e. main migrates.
- M15: `checkPin` back to `config ?: …` (R10-4's re-read gone).
- M16: the crash guard passing `Remove` after an unreadable init (R10-11).
- M17: `configure`'s store lambda `{ null }` (every restart with a redacted cache becomes the stand-in).

`AndroidLockPlanTest` pins only `kidConfigFor(kidLock, config)` as a prefix.
*Fix:* source pins, in the style the branch already uses:
- `Secrets.migrateHere` is exactly `Looper.myLooper() != Looper.getMainLooper()`.
- `checkPin` contains `config?.takeIf { it.usable } ?:`.
- `guardTripped` contains `PinWrite.Keep` and no `Remove`.
- `configure` contains the full `kidConfigFor(kidLock, config) { runCatching { PinLockStore.config(app) }.getOrNull() }`.

M19 (the button forced visible in `bind`) also passed. Pin `if (due) View.VISIBLE else View.GONE` and the initial
`View.GONE`. M20 (no `Secrets.migrateAll` at start) passed too. Pin the launch line in `initRest`.

**Q-12. After a kid-PIN change, a crash between the cache write and the apply keeps the old PIN valid (accepted in §4
R10-3).**
`kidConfigFor` (`PinLockState.kt:316`) takes the stored copy whatever its `pin_rev`. When the redacted policy's
`pin_rev` differs from the stored one, the stored PIN is the old one, and the safer answer is the stand-in (the parent
code opens; the next sync brings the new hash).
*Fix (optional):* `stored()?.takeIf { it.usable && (kidLock.pinRev == 0L || it.pinRev == kidLock.pinRev) }`. Memory
needs no check: it is always the last applied. A test row for it.

### Nit

- **N-a.** `re_enrol_hint` (`values/strings.xml:436`, `values-nb/strings.xml:375`) says "Advanced › New Setup Code".
  The PWA's path is Advanced › Connection › New Setup Code (`server/templates/device_advanced.html:213`).
- **N-b.** The implementation status's N3 line says "`resetPreferences` runs only on a phone with no token stored". It
  also runs from `migratePreferencesToNewVersion`'s catch (`preferences/Preferences.kt:85`), whatever the token. It is
  harmless for the migration race, because it runs at `initRest`'s line 318 before `migrateAll` is launched (388), but
  the sentence is wrong. Reword: "before the background pass is launched".
- **N-c.** `KidLockConfig` and `KidLock` are data classes whose default `toString` prints the hash and the salt
  (pre-existing; nothing logs them today). Give both a redacted `toString`, as `Sealed.Value` has.
- **N-d.** `launcher/CLAUDE.md`'s r10 section says "`SealedPrefsTest` scans every site". True for the generated
  accessor, not for the literal key (Q-2). Update it with Q-2's fix.
- **N-e.** `dialog_re_enrol.xml`'s error colour is a hard-coded `#C92A2A` (about 5.5:1 on white, fine). A colour
  resource would let `KidPaletteTest` keep it.

## The deviations

- **`policyToCache` vs `cacheablePolicy`:** right. `cacheablePolicy` is also applied to `freshPolicy`
  (`MdmSyncWorker`, pinned by `PolicyResponseCompatTest`), so redacting there would have stripped the hash from the
  apply. `policyToCache` is used only by `storeAcceptedPolicy` (one caller). The cached `kid_lock` decodes back with
  `pinHash = ""` because `KidLock`'s fields default to `""` (`dto/PolicyResponse.kt:140-141`). The only other cached
  `kid_lock` readers (`pinSetStored`, `configure`) are fine with `""`. `commandPinVerifies` reads the fresh policy.
- **13 read sites:** verified. The 12 of §3.1 read `DeviceToken.get()` (the scan's map matches the grep). The 13th,
  `initRest`'s first-launch reset, uses `present()`, and no Keystore call happens there.
- **`DeviceToken.set` without context:** fine. `LauncherPreferences.getSharedPreferences()` is the default prefs file,
  the same file `resetPreferences` clears, and it is initialised before every caller (`Enrollment` runs from Settings,
  the QR and the lock, all after `LauncherPreferences.init`).
- **The stand-in keeps `pin_rev`:** acceptable. `lock_state.inactive = bad_hash` goes out with it, so the server can
  tell the rev isn't checkable. It matches a bad hash from the server.
- **The cached `kid_lock` without a `pin_hash` key:** correct with `encodeDefaults = false`. Emulator check 1 should
  read "no `pin_hash` key", as the implementation status says.
- **N1-N7:**
  - N1: `PinWrite.Keep/Remove/Write` is in. The crash guard and every redacted or stand-in apply pass `Keep`.
  - N2: the button is re-checked after every result in both dialogs (`ReEnrolTest` pins it).
  - N3: removals go through `Editor.remove` under `LOCK`.
  - N4: hardened in part (Q-9).
  - N5: open (Q-9).
  - N6: a non-migrating read never takes `LOCK`, except Q-7's save.
  - N7: the token is written on IO.
  - N8: named, not fixed (Q-4).

## Threading, the lock, ANR

`LOCK` is a leaf. Inside it there are only `prefs.getString`/`commit`, `box.*` and the logging callback. Callers hold
AppEnforcer's monitor (configure), `PinLockRuntime`'s (checkPin) or `WindowRelockStore.LOCK` (token reads in the
relock). Nothing is taken inside it, so there is no inversion. The memo is safe without the lock: it is keyed by the
stored string, so a stale entry written by a slow reader after a newer commit only costs one more open. The main
thread waits on `LOCK` only in Q-7. It makes Keystore calls in `init`, the SSE connect and Sync now (Q-6). The
migration pass is one `Dispatchers.IO` launch per process, through the same accessors.

## The source-pinning tests the branch changed

- `AndroidLockPlanTest` (the `saveConfig` call, `kidConfigFor` prefix, the `KidLockConfig(...)` line moved to
  `PinLockState.kt`): it follows the move but pins less of `configure` than before (M17, Q-11).
- `FirstLaunchResetTest`: the rule's blank-token rows went with the parameter change, and nothing replaced them
  (Q-3). The enrolment-mark check moved to `Enrollment.kt` and is equivalent.
- `CommandAnswerPlanTest` and `ZonePlanTest`: the clears are pinned in `Enrollment.kt`, and every enrolment path is
  pinned to call it. Equivalent, plus the third path.
- `PolicyResponseCompatTest`: the cache write is pinned to `policyToCache`, and `freshPolicy = cacheablePolicy(...)` is
  still pinned. Equivalent, plus the new redaction case.

## Mutation pass

Each mutation was applied alone and reverted with `git checkout --`. A "targeted" run is 12 classes, 190 tests
(`SealedPrefsTest`, `KidPinSealTest`, `ReEnrolTest`, `FirstLaunchResetTest`, `PolicyResponseCompatTest`,
`AndroidLockPlanTest`, `CommandAnswerPlanTest`, `ZonePlanTest`, `PinLockStateTest`, `WindowRelockCallersTest`,
`WindowRelockPlanTest`, `PinVaultTest`). A "full" run is the whole unit suite, 1746 tests.

| # | Mutation | Run | Result |
|---|---|---|---|
| M1 | `KeystoreSecretBox.seal` without `updateAAD` | targeted | NOT CAUGHT (Q-10) |
| M2 | `SealedPrefs` seals and opens with AAD `""` (field binding gone) | targeted | caught - `SealedPrefsTest` "a blob under another field's AAD…" |
| M3 | fixed all-zero IV in `seal` + `setRandomizedEncryptionRequired(false)` | targeted | NOT CAUGHT (Q-10) |
| M4 | `kidConfigFor` returns `null` when nothing is readable | targeted | caught - `KidPinSealTest` (2 tests) |
| M5 | `showReEnrol` one condition early (a parent lockout counts) | targeted | caught - `ReEnrolTest` |
| M6 | `Secrets.migrateHere()` always true (main migrates) | **full** | NOT CAUGHT (Q-11) |
| M7 | `DeviceToken.present()` = `get() != null` (Unreadable as absent) | **full** | NOT CAUGHT (Q-3) |
| M8 | a site's `DeviceToken.get()` replaced by a raw `getString("mdm.device_token")` | targeted | caught - `SealedPrefsTest` scan (by the site count) |
| M8b | an extra raw `getString("mdm.device_token", null)` fallback next to the site | targeted | NOT CAUGHT (Q-2) |
| M9 | the plaintext logged (`read`'s success, the migration's failure line) | targeted | NOT CAUGHT (no log scan; the code logs none today) |
| M10 | the migration's re-read and commit outside `LOCK` | targeted | caught - `SealedPrefsTest` "a write waiting for the lock…" |
| M11 | `read` deletes an unreadable value | targeted | caught - `SealedPrefsTest` (2), `KidPinSealTest` (1) |
| M12 | plaintext fallback in `Editor.putSealed` | targeted | caught - `SealedPrefsTest` (2), `KidPinSealTest` (1) |
| M13 | a redacted policy writes the PIN fields (`pinWriteFor`) | targeted | caught - `KidPinSealTest` |
| M14 | `putRecovering` recreates after one failure | targeted | caught - `SealedPrefsTest` |
| M15 | `checkPin` back to `config ?: store` (no re-read of an unusable memory) | targeted | NOT CAUGHT (Q-11) |
| M16 | the crash guard passes `Remove` after an unreadable init | **full** | NOT CAUGHT (Q-11) |
| M17 | `configure`'s store lambda `{ null }` | targeted | NOT CAUGHT (Q-11) |
| M18 | `policyToCache` keeps the salt | targeted | caught - `PolicyResponseCompatTest` |
| M19 | the re-enrol button always visible after the check | targeted | NOT CAUGHT (Q-11) |
| M20 | `Secrets.migrateAll` not launched from `initRest` | targeted | NOT CAUGHT (Q-11) |

**Score: 10 of 21 caught.** Every break of the pure core that the note's §7 named as a mutation target was caught
(redacted-none `null`, the migration's commit outside `LOCK`, delete on `Unreadable`, plaintext fallback in `edit`, a
redacted policy writing the PIN fields, `showReEnrol` with one half). Every miss is in Keystore-only code, in
Android-only glue, or in a source pin that is too loose. Q-2, Q-3, Q-10 and Q-11 give each one a test. After the last
revert, `git status` was clean except this file.

## Suite

Run from the worktree's `launcher/`, `-PwarningsAsErrors=true`, no emulator running:
- `assembleDebug testDebugUnitTest` on `e62b6123`: BUILD SUCCESSFUL, **1746 tests, 0 failures** (173 classes).
- Three more full-suite runs under mutations M6, M7 and M16: 1746 passed each time, which is how those three are
  NOT CAUGHT.
- 17 targeted runs for the other mutations.

No `assembleRelease`.

## Emulator checks to add to §8

7. **Boot cost on main.** Upgrade from the previous build, then five cold boots and five unlocks. Log the
   wall time of `PinLockStore.config` in `PinLockRuntime.init` (temporary) and read `Unlocked setup took N ms` against
   the previous build. No ANR dialog, no "Skipped frames" above the previous build's. Repeat on the Jelly Star (Q-6).
8. **The deadlock with the tailnet down** (Q-1). Use a `*.ts.net` server URL. Run `key all`, turn Wi-Fi off, reboot,
   and wait for the lock. Then turn Wi-Fi on, type a parent code once, and use "Ny oppsettkode" with a fresh code.
   Expected after Q-1's fix: enrolled and synced. Today: "That code didn't work" until a reboot.
9. **The secrets key lost while running.** Run `key secrets` without a force-stop. The memo keeps the token and
   the PIN for this process (a sync works, the kid PIN opens). Then force-stop: the lock is `BAD_HASH`, the device page
   goes silent, and §8 check 4 follows.
10. **The reset token after a key loss.** After check 4's re-enrolment, open a window on a phone with no Android
    credential: `window_relock.xml`'s `token` is a new `ks1:` value, and at the window's end the relock reports no
    "credential left".
11. **The migration is crash-safe.** Install over the old build and `am force-stop` within a second of the first
    unlock (before the background pass). The three files hold each field either plain or `ks1:`, never both and never
    gone, and no `*.bak` file is left in `shared_prefs/`. The next start seals the rest.
12. **The re-enrol dialog survives the screen.** Press power during the enrol call (and once rotate, if the lock
    allows it). The code is either used and the phone enrolled, or unused (Q-8).
13. **A wrong typed code, a right one in lower case** (the server normalises the case), and a code typed with the
    keyboard's caps off. All in check 5's flow.

## Fix round

All in a35c436a (branch `r10-keystore-wrap`); the docs in the commit after it. The suite: 1755 tests, 0 failures
(`-PwarningsAsErrors=true assembleDebug testDebugUnitTest`, no emulator running).

- **Q-1** Fixed in a35c436a: `Enrollment.enroll` calls `TsnetClient.connectFromPreferences(context)` before
  `createMdmApi`; a failure is an `EnrollFailure(refused)` (`enrollFailureOf`: only a 4xx is refused); the dialog shows
  `re_enrol_failed` for a refused code and the new `re_enrol_unreachable` ("Couldn't reach the server ...") otherwise.
  `SealedGlueTest` pins the order and the mapping.
- **Q-2** Fixed in a35c436a: `SealedPrefsTest` scans `src/main`, `src/debug` and `src/release`: `mdm.device_token`
  only in `SealedPrefs.kt` (one comment elsewhere reworded), `"window_relock/token"` only in `WindowRelockStore.kt`,
  `"pin_lock_state/` only in `PinLockStore.kt`, the generated accessor only in `SealedPrefs.kt`, no raw `"token"`
  get/put in a file naming `"window_relock"`. Re-check: M8b (`DeviceToken.get() ?:
  LauncherPreferences.getSharedPreferences().getString("mdm.device_token", null)` in `PowerEvents.prepare`) fails the
  full suite (1 failed: "the sealed fields' keys and AADs are named only by their owners"); reverted.
- **Q-3** Fixed in a35c436a: both - the pure `tokenStored(raw)` (null, `""`, `"  "`, hex, a `ks1:` blob no box
  opens) and a pin of `present()`'s body (`= tokenStored(prefs().getString(key(), null))`). Re-check: M7 (`present()` =
  `get() != null`) fails the full suite (1 failed: "a stored token is present whatever it holds"); reverted.
- **Q-4** Fixed in a35c436a: `SecretBox.keyBroken()` (the Keystore box: missing alias, `UnrecoverableKeyException`,
  `KeyStoreException`, `InvalidKeyException` from `Cipher.init`; anything else transient); `putRecovering` recreates only
  when it is true, else returns false with nothing written. Test: two `ProviderException`s with a usable key keep the key
  and the other field's blob; the 1/2/3 test runs with a broken key.
- **Q-5** Fixed in a35c436a: `configure` also saves when `write is PinWrite.Write && !PinLockStore.pinStored(app)`
  (pinned in `SealedGlueTest`).
- **Q-6** Fixed in a35c436a: `KeystoreSecretBox` keeps a `@Volatile` key handle, dropped on any seal/open failure,
  by `keyBroken` and by `recreate`; `CommandListenerService.connect` reads the token on a single background thread and
  posts `openStream` (a generation drops a stale read; `stopSse` bumps it); Settings' Sync now checks
  `DeviceToken.present()`. `PinLockRuntime.init`'s read stays (one per process, memoised) - emulator check 7 times it.
- **Q-7** Fixed in a35c436a: a `Keep` save commits a plain editor without `SealedPrefs.LOCK` (`KidPinSealTest`
  holds the lock on another thread and the save still returns at once).
- **Q-8** Fixed in a35c436a: the re-enrolment runs in `ReEnrolDialog`'s process scope (`SupervisorJob` + IO), the
  sync is requested there, and the dialog hears the result only while it still shows.
- **Q-9** Open, as documented (the note's implementation status and N4/N5); the user decides.
- **Q-10** Fixed in a35c436a: the spec test counts `cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))` twice, pins
  `seal`'s `init` without an IV and `val iv = cipher.iv`, bans `GCMParameterSpec` in `seal` and
  `setRandomizedEncryptionRequired` (with the user-auth, unlocked-device and StrongBox calls) anywhere in the file.
- **Q-11** Fixed in a35c436a: `SealedGlueTest` pins `migrateHere` exactly, `checkPin`'s re-read, the crash guard's
  `PinWrite.Keep` (no `Remove`), `configure`'s full store lambda, the button's initial `GONE` and its `if (due)`, and
  `initRest`'s `Secrets.migrateAll` launch.
- **Q-12** Fixed in a35c436a: `kidConfigFor` takes the stored copy only when its `pin_rev` is the policy's (or the
  policy's is 0); memory unchecked. `KidPinSealTest` rows for the other rev (stand-in, `BAD_HASH`), rev 0 and memory.
- **N-a** Fixed in a35c436a: the hint says Advanced › Connection › New Setup Code (en, nb).
- **N-b** Fixed in the docs commit: "before the background pass is launched".
- **N-c** Fixed in a35c436a: `KidLockConfig` and `KidLock` have a `toString` without the hash and salt.
- **N-d** Fixed in the docs commit: `launcher/CLAUDE.md`'s r10 section names what the scan bans now.
- **N-e** Fixed in a35c436a: `@color/dialog_error_text` (`ReEnrolTest`: >= 4.5:1 on white).
- **Emulator checks**: the seven are §8's checks 7-13 in the design note.
