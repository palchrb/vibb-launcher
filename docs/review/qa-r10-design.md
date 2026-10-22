# Design QA: launcher round 10 - the phone's secrets under a Keystore key (`docs/design/r10-launcher-keystore-wrap.md`)

Checked against `main` (6dbec1b6): every token site (`grep deviceToken`, `settings_mdm_device_token_key`, tests), `lock/PinLockStore.kt`,
`lock/PinLockRuntime.kt` (init, decide, `pinSetStored`, configure, guardTripped, checkPin, `pinLength`/`pinUsable`),
`lock/PinLockState.kt` (`lockActivation`), `lock/PinLockActivity.kt:740-800`, `ui/ParentPinDialog.kt`,
`server/PinVault.kt`, `server/ParentPin.kt`, `server/MdmSyncWorker.kt:70-170,990-1060`, `server/AppEnforcer.kt:273`,
`server/WindowRelock*.kt`, `server/Provisioning.kt`, `server/PowerEvents.kt`, `Application.kt`,
`calls/DirectBootComponents.kt`, the manifest (`:bootcover`), the backup rules, `src/debug/.../PinVaultKeyReceiver.kt`,
the server's `handlers/device_api.rs:60-170`, `enrollment.rs`, `security.rs`, design 24b §2/QA #7, design 59 §4.5/§7/§9
and the security review's P-L5.

**Verdict: the right shape, and inside the approved scope.** One key, one helper, the same pref keys, and the prefix
instead of a version flag are the right amount of code. Every cited line holds. Nothing reads a secret before the
first unlock (`decide` reads only `active`/the guard; `:bootcover` reads no CE; `onShutdown` uses the client prepared
after the unlock; tsnet never reads the token), and one process touches CE. There is no settings export and no
listener on the token key. All four plaintexts are hex (`security.rs:62-66`, `:173-184`), so `ks1:` can't be legacy.
The PWA's kid-PIN change still lands: the sync applies `freshPolicy` (`MdmSyncWorker.kt:160,170`), not the cache. The
server's re-enrolment is as described (same row, old token off, commands dropped, `device_api.rs:146-160`; typed codes
rate-limited, `:83-91`).

The bugs are in the kid PIN's fail paths and the lock's scope. Counts: **1 High, 5 Medium, 5 Low, 4 Nit.**

## Findings

**R10-1 High - "redacted with none" can switch the kid lock off.**
- §3.2 says a redacted policy with no readable stored copy gives "the in-memory config, or none". It also says "the
  lock still comes up, `BAD_HASH`". With `null` the two can't both be true.
- `configure` feeds `newConfig != null` into `lockActivation(hasKidPin = …)` (`PinLockRuntime.kt:1191-1199`), and
  `!hasKidPin -> false to NO_PIN` (`PinLockState.kt:268`). So a null result turns the lock off.
- It also passes `null` to `KeyguardCover.configure` (`:1191`). `saveConfig(null)` then removes the fields
  (`PinLockStore.kt:52-53`).
- So a re-apply from the cache after a Keystore error opens the phone.
- Fix in the note:
  - `kidConfigFor(kidLock != null, …)` never returns `null`. Redacted with nothing readable returns
    `KidLockConfig("", "", policy length)` (unusable, so `true to BAD_HASH`).
  - `saveConfig` writes the PIN fields only for a policy's real hash (sealed) or `kidLock == null` (removed). A
    redacted or unresolved config writes `active`/`inactive` only.
  - Test it through `lockActivation`, so it returns `true to BAD_HASH`.
  - Mutation target: "redacted-none returns null".

**R10-2 Medium - `LOCK` doesn't cover the commit, so the migration can still write an old value over a new one.**
- `put(editor, aad, key, plain)` seals into the caller's editor. The caller commits later, outside `LOCK`
  (`saveConfig` commits several fields in one editor).
- Here is the race. The migration re-reads plaintext P1 under `LOCK`. An enrolment's or `saveToken`'s commit of a new
  value lands. Then the migration commits `ks1(P1)` over it. That is the very case §2 says `LOCK` prevents.
- Fix in the note:
  - Writes go through `SealedPrefs.edit(prefs) { e -> … }`, which seals, puts and commits inside `LOCK`.
  - `saveConfig`, `saveToken` and `DeviceToken.set` use it, and the migration commits under the same hold.
  - `LOCK` is a leaf lock: nothing but the prefs and the Keystore is called under it. Callers hold AppEnforcer's
    monitor, `PinLockRuntime`'s and `WindowRelockStore.LOCK`.
  - The listed test "a write between the two reads" must commit from a second thread between them.

**R10-3 Medium - a failed seal in `saveConfig` loses `active` and keeps the old PIN.**
- §3.2 says "a failed seal skips that commit". But the same commit carries `ACTIVE`/`INACTIVE` (`PinLockStore.kt:49-57`).
- The first PIN set on a phone whose key is broken is then never persisted as active. `decide` starts the next
  process DISABLED (`:231-243`).
- A changed PIN leaves the old hash on disk. After a restart, `kidConfigFor(redacted, stored)` opens the lock with the
  old PIN until the next sync. That PIN may be the one the parent changed because it leaked.
- Fix in the note:
  - On a failed seal, commit `active`/`inactive` and remove the PIN fields (never keep the old ones).
  - The next start then comes up `BAD_HASH` (fail closed) until a sync.
  - Same in the case of the crash between `storeAcceptedPolicy` and the apply after a PIN *change* (§4 names only the
    first PIN): the old PIN is valid until the start's sync. Say so.

**R10-4 Medium - one transient `Unreadable` sticks until the next fresh policy.**
- `configure` sets `config = newConfig` (`:1209`). `checkPin` uses `config ?: store` (`:1551`), so it never reads the
  store again once `config` holds the unusable stand-in from R10-1.
- §3.2 says "the in-memory config is used" first. So every later re-apply feeds that stand-in back as "stored".
- One Keystore hiccup at a re-apply then means parent code only until a sync. Offline, that lasts indefinitely.
- Fix in the note:
  - The rule uses the in-memory config only when it is usable. Otherwise it reads the store again.
  - `checkPin` reads the store when `cfg` is unusable.
  - Test: an `Unreadable` read, then a good one, makes the kid PIN work without a sync.

**R10-5 Medium - "Ny oppsettkode" puts a keyboard on the lock.**
- The typed code is 8 characters from `23456789A-Z` (`enrollment.rs:25-26`), so it needs an IME.
- Design 24b §1.8 / `ParentPinDialog.kt:22-24`: "never through a keyboard". The kid can enable a keyboard during a
  window, and an IME's own UI is an escape surface over the lock.
- Only the total loss needs the button. With the vault intact (check 3) the parent code opens the lock. Settings'
  existing enrolment then works behind the parent PIN (`SettingsFragmentLauncher.kt:323-370`).
- Fix in the note:
  - Show the button only when the token is `Unreadable` **and** `ParentPin.status` is `untilSync`/not configured. The
    `SettingsGate` blocks Settings after the first policy, so that is exactly the deadlock.
  - Say why the IME is acceptable there: the state isn't kid-triggerable, and the code is one-time and bound to this
    device's row.
  - Use `textVisiblePassword|textNoSuggestions` and `importantForAutofill="no"`, and keep `ParentPinKeypad.secure`.
  - The button never unlocks. Only the sync after it does, through the kid or parent PIN.
  - Or: our own 31-key pad (more code). The user's call, see Q2.

**R10-6 Medium - the note silently reverses design 24b's QA #7.**
- 24b §2 and QA #7 decided "the kid's PIN lock never depends on a Keystore key". A key failure would otherwise leave
  the lock with no way to open it ("with the Pi down … calls-only"; `24b-override-relock.md:289-291,626-632`).
- `PinVault.kt:66` says the same, and so do the debug receiver's doc and 24b's check ("the kid's PIN still opens the
  lock").
- Fix in the note: name the reversal in §5. That a total loss now needs the server and the parent (Pi down = calls
  only) is QA #7's cost. Update `PinVault.kt:66`, `PinVaultKeyReceiver`'s doc and 24b §7's check. Put the trade-off in
  Q2.

**R10-7 Low - the Keystore on the main thread.**
- `PinLockRuntime.init` runs on the main thread (`Application.kt:386`) and reads `PinLockStore.config`.
  `pinSetStored` (Home, main thread) does too (`:249`).
- After the upgrade the first such read also seals and commits. With the background pass holding `LOCK` for a keygen,
  main waits.
- The vault moved its keygen off the main thread for this reason (`Application.kt:362-365`, 24b QA Low 11). §2's
  "the memo keeps the Keystore off" the lock's start is wrong for the first read per process.
- Fix in the note:
  - A read on the main thread never migrates: it opens or returns the legacy value. The migration runs from the
    background pass or a background reader.
  - `pinSetStored` uses a presence check (`contains(PIN_HASH)`), not `config()`.
  - State the remaining cost: one decrypt per process on the main thread.

**R10-8 Low - design 59 is misread in §3.2 and §6.**
- §6 says "the PIN itself is never stored", and §3.2 says the sealing "goes with" phase C.
- Design 59 §4.5 (a) and §9.2 keep the kid PIN in plaintext "wrapped by a Keystore key in CE".
- Fix in the note: the hash fields go in phase D, but `SealedPrefs` stays. 59 (a) adds one AAD/field to it. Keep §6's
  warning against `UnlockedDeviceRequired`, which is right.

**R10-9 Low - `DeviceToken.set` deletes the key on the first failure.**
- A transient error at a routine Settings re-enrolment then orphans the kid PIN and the reset token. An open window's
  credential is then left in place.
- Fix in the note: retry once first, as the vault's `repeat(2)` (`PinVault.kt:98-106`). Recreate under `LOCK` and
  clear the memo.
- `SecretBox` needs a `recreate()` for the listed `DeviceToken.set` test, as `VaultKey` has.

**R10-10 Low - the token has 12 raw read sites, not 11, and nothing stops a 13th.**
- Count `MusicBridgeService` ×3 + `MusicSync` ×2 + 7 others.
- A later raw `mdm().deviceToken()` would send `Bearer ks1:…`, get a silent 401 everywhere, and look like a dead
  server.
- Fix in the note: a source-scan test (as `checkReleaseHasNoDebugHook`/`FirebaseLeftoversTest` do) that only
  `SealedPrefs.kt` names the generated getter/setter.

**R10-11 Low - `guardTripped` and the backoff on the fail path.**
- `guardTripped` writes `saveConfig(app, config, …)` (`:1222`). With `config == null` after an `Unreadable` at init, it
  deletes sealed fields that the next read could open. It should write `active`/`inactive` only, which falls out of
  R10-1's `saveConfig` rule.
- `backoffForHash(…, newConfig?.fingerprint)` (`:1201-1205`) resets the kid's wrong-PIN count to the stand-in's
  fingerprint, and again when the real hash comes back.
- Fix: skip the backoff step for an unusable stand-in.

**R10-12 Nit - the memo's spec.**
- Its readers are the bridge's binder threads, IO and main. Make it one `@Volatile` (stored, plain) pair per AAD, as
  `cachedPolicyMemo`, with no `LOCK` on a hit.
- Cache only `Value`s, never `Unreadable`, so a retry can heal.
- A `ks1:` whose Base64 doesn't decode is `Unreadable`.

**R10-13 Nit - the format is one-way.** An older build reads `ks1:` as a token (401), a hash (`BAD_HASH`) or a reset
token (null). No field path downgrades (`pendingApkCheck` needs a newer versionCode). Say so in one line, and that
`adb install -d` on the emulator breaks the token.

**R10-14 Nit - the emulator checks.**
- Name the default prefs file (`me.vibb.launcher.debug_preferences.xml`).
- Add a check between 1 and 2: change the kid PIN in the PWA, sync, force-stop, reopen. That is the redacted-cache path
  of §3.2. The new PIN opens and the old one is refused.
- Check 3: per R10-5, the button doesn't show there. The parent code then Settings' enrolment is the recovery.
- Check 4: also verify the kid can't open the button's keyboard without the dialog (`key all`, kid at the lock).

**R10-15 Nit - wording.** §1's AAD is `prefs-file/key`, so a later fifth field (R10-8) follows it. §3.1 "11 sites"
-> 12. §4's background task reads through the same accessors, so it inherits R10-7's main-thread rule.

## Open questions - recommendations (the user decides)

1. **TEE vs StrongBox:** TEE, as proposed.
   - The threat (a copied data directory) is fully met.
   - The kid PIN is read on the main thread at every process start (R10-7), where a secure element's round trip hurts.
   - The Jelly Star (MediaTek) may have no StrongBox at all.
   - Attestation value belongs to design 60.
2. **The recovery button vs "factory reset and the QR":** keep the button, narrowed per R10-5.
   - After R10-6 the deadlock is real. The lock is `BAD_HASH`, and the parent PIN is `Unavailable(untilSync)`
     (`ParentPin.kt:70,107`). `SettingsGate` blocks the existing enrolment, and the token can't reach the server.
     Without the button, a Keystore wipe makes the phone calls-only until a recovery wipe.
   - The alternative that needs no new UI: keep the kid PIN out of the key (24b QA #7). Total loss then leaves a usable
     but unmanaged phone (the last rules, offline), and the factory reset is the recovery. But P-L5's kid-hash point
     stays open.

## Not in scope (proposals)

- Redact the cached kid hash at the upgrade without waiting for a sync, only once the sealed copy matches it. Today it
  is seconds away. Only worth it if field phones sit offline after an update.
- Report the kind of `Unreadable` (key missing / tag failure / transient) in the next status after a re-enrolment, for
  the OEM-OTA cases §5 mentions.
