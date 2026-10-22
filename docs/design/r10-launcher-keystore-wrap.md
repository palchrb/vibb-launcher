# Launcher round 10 - the phone's secrets wrapped with a Keystore key (proposal, revised after `qa-r10-design.md`)

Approved 2026-10-21 (PLAN "Keystore for the phone's secrets", item (1); item (2), the hardware-bound token, is design
60). Closes P-L5 (`docs/review/security-review-2026-10-21-launcher.md:181`) and design 59 §7's reset-token point
(AOSP `DPM:6363-6369`: "NEVER store this token on device in plaintext"). Launcher only: no API, policy or contract
change, nothing on the server. The user's answers (2026-10-22): TEE only; the recovery button only "så lenge dette
gir god beskyttelse og sikkerhet" - narrowed to the real deadlock (§5).

**Goal.** A copy of the launcher's data directory (root, an adb/OEM backup, a lab image taken AFU) yields no usable
secret: the device token, the kid PIN's hash and salt and the reset-password token are on disk only as AES-GCM
ciphertext under a key that never leaves the TEE. **Not the goal**: anything an attacker running code as our uid on
the phone can do (they can call the Keystore too) - design 60's and design 59's ground.

## 1. The key

One key, alias `secrets_v1`, made on first use (like `PIN_VAULT_KEY_ALIAS`, `server/PinVault.kt:18`):

```kotlin
KeyGenParameterSpec.Builder("secrets_v1", PURPOSE_ENCRYPT or PURPOSE_DECRYPT)
    .setBlockModes(BLOCK_MODE_GCM).setEncryptionPaddings(ENCRYPTION_PADDING_NONE).setKeySize(256)
    .build() // randomized IV (the default), no user auth, no UnlockedDeviceRequired, no StrongBox
```

- **AES-256-GCM**: confidentiality and integrity in one; the Keystore's 12-byte IV, a 128-bit tag.
- **AAD = `prefs-file/key`** (`default/mdm.device_token`, `pin_lock_state/pin_hash`, `pin_lock_state/pin_salt`,
  `window_relock/token`; a later field follows the same form, §6): a blob can't be moved into another field.
- **No user-auth binding, no `setUnlockedDeviceRequired`**: the sync, the SSE stream and the music bridge use the
  token while the screen is locked (design 59 §7), the relock and design 59's PIN command use the reset token while
  locked. Such a key also survives adding or removing an Android credential (the window relock's
  `resetPasswordWithToken`, design 59's hybrid): only auth-bound keys are invalidated by that.
- **TEE, not StrongBox** (the user's decision; the vault's QA S4): a copied data directory is fully met by any
  non-exportable key; the kid PIN is decrypted on the main thread once per process (§2), where a secure element's
  round trip hurts; the Jelly Star (MediaTek) may have none; attestation belongs to design 60.
- **One key for all fields**: a Keystore loss is in practice keystore-wide; separate keys buy only code.

## 2. The stored form and the one helper

A sealed value is `ks1:` + Base64(IV || ciphertext+tag) in the **same pref key** as today's plaintext. All four
plaintexts are hex (`security.rs:62-66`, `hash_pin`, our 32-byte reset token), so the prefix alone says sealed or
legacy - no version flag. The format is one-way: an older build reads `ks1:` as a token (401), a hash (`BAD_HASH`) or
no reset token; no field path downgrades (`pendingApkCheck` needs a newer versionCode), but `adb install -d` on the
emulator breaks the token - re-enrol.

New file `server/SealedPrefs.kt` (GPL, ~90 lines):

- `interface SecretBox { seal(aad, plain): ByteArray; open(aad, blob): ByteArray; recreate() }` - throws on failure.
  `KeystoreSecretBox` (Android, ~35 lines, the vault's `KeystoreVaultKey` shape) makes the key when the alias is
  missing - `seal` only; `open` with no key throws.
- `class SealedPrefs(box)`, the pure core:
  - `read(prefs, aad, key, migrate: Boolean): Sealed` = `Absent` | `Value(plain)` | `Unreadable`.
    - `ks1:` value: opened. An exception or Base64 that doesn't decode = `Unreadable`, logged once per stored value;
      **the stored value is never touched** (a transient Keystore error must not cost the phone its enrolment).
    - Legacy plaintext: `Value(plain)`; with `migrate` also §4's migration.
  - `edit(prefs) { e -> e.putSealed(aad, key, plain); e.putX(...) }: Boolean` - seals, puts and **commits inside
    `LOCK`**; a failed seal throws out of the block, nothing is committed, false. Never a plaintext fallback. Every
    write of a sealed field uses it (`saveConfig`, `saveToken`, `DeviceToken.set`), and the migration commits under
    the same hold - so no migration can write an old value over a newer one (QA R10-2).
  - `LOCK` is a leaf lock: nothing but the prefs and the Keystore runs under it. Callers may hold AppEnforcer's
    monitor, `PinLockRuntime`'s or `WindowRelockStore.LOCK`; nothing is taken inside it.
  - The memo: one `@Volatile` (stored string, plain) pair per AAD, as `cachedPolicyMemo`
    (`server/MdmSyncWorker.kt:1033-1054`); a hit takes no lock. Only `Value`s are cached, never `Unreadable`, so a
    retry can heal. Readers: the bridge's binder threads, IO and main.
- **Main thread** (QA R10-7): `PinLockRuntime.init` (`Application.kt:386`) reads `PinLockStore.config` on main. A read
  on the main thread passes `migrate = false` (it opens, or returns the legacy value); the migration runs from the
  background pass or a background reader, as the vault moved its keygen off main (`Application.kt:362-365`). The cost
  left on main: one decrypt per process (two fields). `pinSetStored` (`PinLockRuntime.kt:249`) uses a presence check
  (`contains(PIN_HASH)`), not `config()`.

## 3. The three secrets: where they are, who reads them

All three are in **credential-encrypted** storage and stay there; none moves to DE. Nothing reads them before the
first unlock: the direct-boot components (`calls/DirectBootComponents.kt:10-19`) and `:bootcover` read only DE prefs,
`decide` reads only `active` and the guard, and the review's DE list holds no token or PIN (review :293-295). The
call screening's boot path is untouched; design 59 keeps that rule (§6).

**3.1 The device token.** Default prefs `mdm.device_token` (`preferences/LauncherPreferences$Config.java:44`).
- Written by `server/Provisioning.kt:44` and `ui/settings/launcher/SettingsFragmentLauncher.kt:353` through the
  generated setter (an async `apply()`).
- Read raw (`mdm.deviceToken()`) at 12 sites: `MdmSyncWorker.kt:74`, `CommandListenerService.kt:206`,
  `AppInstallReceiver.kt:158`, `AppDownloads.kt:546`, `PowerEvents.kt:306`, `MusicSync.kt:217,359`,
  `MusicFileDownloads.kt:221`, `MusicBridgeService.kt:176,208,273`, `SettingsFragmentLauncher.kt:464`.
- Change: `object DeviceToken { get(): String?; state(): Sealed; set(context, token): Boolean }` in `SealedPrefs.kt`.
  Every site calls `get()` (`null` for `Absent` and `Unreadable`: each site's "not enrolled, skip" branch covers a
  broken key). Both writers call `set`, which commits synchronously (also fixing the `apply()` race with a process
  death right after enrolment). `resetPreferences` still clears it.
- `set`'s failure rule (QA R10-9): seal fails -> retry once (as the vault's `repeat(2)`, `PinVault.kt:98-106`); fails
  again -> `box.recreate()` under `LOCK`, clear the memo, seal once more; fails -> false, nothing written. Only here:
  the token is being replaced anyway, and the kid PIN and reset token the old key orphans come back by §3.2/§3.3.
- A source-scan test (as `FirebaseLeftoversTest`) checks that only `SealedPrefs.kt` names the generated getter or
  setter: a 13th raw read would send `Bearer ks1:…` and look like a dead server.

**3.2 The kid PIN's hash and salt.** Two copies today:
- CE `pin_lock_state` `pin_hash`/`pin_salt` (`lock/PinLockStore.kt:21-22`, read `:37-41`, written `:48-57`), read at
  process start (`PinLockRuntime.kt:440`), a PIN check (`:1551`) and every `configure` (`:1176-1177`).
- The cached policy's `kid_lock` (`dto/PolicyResponse.kt:136-137`, cached by `storeAcceptedPolicy`,
  `MdmSyncWorker.kt:999-1019`), which `configure` reads at every re-apply from the cache.

Change - one sealed copy, as the parent PIN has one vault:
- `storeAcceptedPolicy` caches `kidLock.copy(pinHash = "", pinSalt = "")` (`cacheablePolicy`, next to
  `redactedPolicy`; **only** the cache - the sync applies `freshPolicy` with the hash, `MdmSyncWorker.kt:160,170`).
  `""` means redacted, as for the parent PIN (`PinVault.kt:131-136`); the server never sends an empty hash.
- `configure` takes the PIN from a pure rule, `kidConfigFor(kidLock, memory, stored)` in `PinLockState.kt`, which
  **never returns `null` while `kidLock != null`** (QA R10-1):
  - `kidLock == null` -> `null` (no PIN, as today);
  - a real hash -> it (`fromPolicy = true`);
  - redacted -> the in-memory config **if usable**, else the stored one if readable and usable, else the stand-in
    `KidLockConfig("", "", length)` - unusable, so `lockActivation` gives `true to BAD_HASH`: the lock up, the parent
    code opens it (`PinLockRuntime.kt:436-443`'s path). The policy's `pin_length` wins in every case.
- `saveConfig(config, fromPolicy, active, inactive)` writes the PIN fields only for a policy's real hash (sealed) or
  removes them for `kidLock == null`; a redacted or stand-in config writes `active`/`inactive` only. So
  `guardTripped`'s `saveConfig(app, config, …)` (`:1222`) after an `Unreadable` at init no longer deletes fields the
  next read could open (QA R10-11).
- **A failed seal** in `saveConfig` (QA R10-3): commit `active`/`inactive` and **remove** the PIN fields, never keep
  the old ones (a changed PIN may be the leaked one). The next start comes up `BAD_HASH` until a sync.
- Healing (QA R10-4): `checkPin` reads the store again when `cfg` is unusable (today only when `null`, `:1551`), and the
  rule above reads the store whenever memory is unusable - one transient `Unreadable` heals at the next try, no sync.
- The backoff step (`backoffForHash`, `:1201-1205`) is skipped for the stand-in: the kid's wrong-PIN count isn't reset
  by a fingerprint that only means "unreadable" (QA R10-11).
- The backoff's `hash_fingerprint` (8 bytes of SHA-256(salt:hash)) stays plain: without the salt it can't test a guess.

**3.3 The reset-password token.** CE `window_relock` `token`, hex (`server/WindowRelockStore.kt:36,176-178`). Written
by `ensureResetToken` (`WindowRelock.kt:250-264`: stored first, then `setResetPasswordToken`); read by its
`resetTokenNeeded` check (`:260`), `tokenUsable` (`:550`) and `removeCredential` (`:553`). Change: `token()` and
`saveToken()` go through `SealedPrefs`; `token()` returns `null` for `Unreadable`. Then `tokenUsable` is false (the
relock reports "credential left") and the next window's `ensureResetToken` sets a new token while the phone has no
credential (`WindowRelockPlan.kt:127-128`); DPM replaces the old one. A failed `saveToken` already returns before
`setResetPasswordToken` (`:262`).

## 4. Migration of existing plaintext

Inside `SealedPrefs.read(…, migrate = true)`, under `LOCK`: re-read the field; still the same plaintext -> seal it and
`commit()` the sealed string **into the same key**. Then:
- **Idempotent**: a sealed value is never sealed again (prefix).
- **Crash midway**: before the commit the plaintext is there and migrates at the next read; after it, sealed. Never
  both, never neither. Each field is its own commit; a hash sealed and its salt not yet is fine.
- **Seal fails**: the plaintext stays and is returned (no worse than today, never deleted); the next read retries.
- **When**: on any background read, plus once at process start: `Application.initRest` posts one background task that
  reads the three through the same accessors (`DeviceToken.get()`, `PinLockStore.config`,
  `WindowRelock.store(app).token()`), so a reset token isn't left plain until the next window.
- **The cached policy's kid hash** goes at the next accepted policy (every sync rewrites the cache through
  `cacheablePolicy`; the upgrade comes over the network, so that is seconds away). `configure` seals the PIN from the
  first apply after the upgrade, from the old cache that still has the hash, so the redaction never runs ahead of the
  stored copy. A crash between the sync's cache write and its apply: on a first PIN, `BAD_HASH` until the next sync (at
  start); after a PIN **change**, the old PIN stays valid until that sync (seconds; QA R10-3).

SharedPreferences rewrites the whole XML file on a commit (`.bak` deleted on success), so no plaintext copy survives
in the data directory; old flash blocks are FBE's business.

## 5. A lost or broken key - never a bricked phone

When: an OEM Keystore bug (seen after OTAs on some phones), a Keystore wipe; **not** a backup restore (none is
possible, below), not app-data clearing or a factory reset (the prefs go too), not a credential change (§1).

- **Kid PIN** -> `Unreadable`: `BAD_HASH`, the lock up, the parent code opens it; the next accepted policy brings the
  hash and `configure` seals it under a new key.
- **Reset token** -> §3.3.
- **Device token** -> `Unreadable`: every sync site skips (logged); the phone keeps its last rules offline
  (`PolicyGate`), calls work. The parent sees the phone go silent on the device page. With the vault intact, the
  parent code opens the lock and Settings' existing enrolment (`SettingsFragmentLauncher.kt:323-370`, behind the
  parent PIN) takes a new code: no new UI needed.

**This reverses design 24b's QA #7** (QA R10-6): 24b decided "the kid's PIN lock never depends on a Keystore key"
(`24b-override-relock.md:289-291,626-632`, `PinVault.kt:66`). After this round a total Keystore loss (both keys)
leaves the lock `BAD_HASH` and the parent PIN `Unavailable(untilSync)` (`ParentPin.kt:70,107`); `SettingsGate` blocks
Settings and the token can't reach the server. That is the deadlock - and recovery now needs the server and the
parent (Pi down = calls only until it is back). The implementation updates `PinVault.kt:66`, `PinVaultKeyReceiver`'s
doc and 24b §7's check ("the kid's PIN still opens the lock" holds only with `secrets_v1` intact).

**The recovery button, only in the deadlock** (QA R10-5, the user's condition):
- Shown in both parent-code dialogs (the lock's, `PinLockActivity.kt:760-800`, and `ui/ParentPinDialog.kt`) only when
  `DeviceToken.state()` is `Unreadable` **and** `ParentPin.status` is `untilSync` or not configured, with a stored
  server URL. Neither half is kid-triggerable: a key goes only by an OEM fault, root or our uid.
- "Ny oppsettkode": one field for the typed enrolment code from the device page's Connection section
  (`/regenerate-code`). It runs Settings' enrolment (factored into `Enrollment.enroll(context, code)`, used by Settings,
  this dialog and `Provisioning`'s HTTP part); on success the usual clears (`ParentPin.clearEvents`, `CommandAnswers`,
  `InstallNow`) and a sync.
- **It never unlocks.** Its success seals a new token and syncs; the policy re-wraps the parent PIN and seals the kid
  PIN; the lock then opens only by the kid or parent PIN, as always.
- **Only the parent can make it work**: the code is made by the parent's server login, valid 15 minutes, one-shot,
  bound to this device's row (the old token stops, pending commands are dropped, `device_api.rs:146-160`); guessing
  is capped by the server's typed-code limits (the client bucket, 20 failures an hour server-wide pause typed codes,
  `device_api.rs:83-91`, `enrollment.rs:13-16`) - about 40 bits against at most a few dozen tries per live code.

**Letters, so a keyboard - confined to this dialog.** The typed code is 8 of `23456789A-Z` (`enrollment.rs:25-26`).
Options: (a) the system keyboard in this one field; (b) a digits-only code - the endpoint's `shape()` would accept 8
digits from `2-9` as typed, but the server would need a new generator and device-page option, at ~24 bits; (c) our
own 31-key pad. **Pick (a)**, the smaller safe one:
- 24b §1.8's "never through a keyboard" protects a **reusable** secret (the parent PIN) from a logging keyboard; this
  code is consumed by its use and dead in 15 minutes - a logged copy is worth nothing.
- The keyboards are the permitted ones only: outside windows `setPermittedInputMethods(system ∪ allowlist)` (24b
  §1.5, `EnforcementPlan.kt:250`), a sideloaded one is hidden.
- The IME's own surfaces (settings, voice, search) can't start anything over the lock: the lock is always in lock
  task while LOCKED (step 10, QA 10 #1), so another package's activity is refused.
- The field exists only in this dialog, only in the deadlock: `inputType="textVisiblePassword|textNoSuggestions|
  textCapCharacters"`, `importantForAutofill="no"`, the dialog kept `ParentPinKeypad.secure` (no screenshots); the IME
  is hidden on dismiss. The parent-PIN keypads keep no text field.

**Backup.** Already closed, unchanged: `allowBackup="false"` (`AndroidManifest.xml:103`) and both rule files exclude
every domain (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`); Keystore keys never travel.

## 6. Design 59: how the hybrid reads the reset token

- Through `WindowRelockStore.token()` as today. The PIN command (59 §4.5) runs after unlock (commands are CE-only):
  `resetPasswordWithToken(admin, pin, token, 0)` works while Android's keyguard shows, because the key has no
  `UnlockedDeviceRequired`. BFU the command waits, as every command does.
- Design 59 (a) and §9.2 keep the kid PIN on the phone in plaintext form "wrapped by a Keystore key in CE": that is
  one more `SealedPrefs` field (`pin_lock_state/kid_pin` or the like) under `secrets_v1`. Phase D removes only the
  hash and salt fields; `SealedPrefs` stays (QA R10-8).
- Design 59 must revisit `resetTokenNeeded`'s `!secureNow` (`WindowRelockPlan.kt:127-128`): with the kid's Android
  PIN always set, a lost token can only be replaced while secure (escrow: active after the kid's next unlock).
- Never add `setUnlockedDeviceRequired` or auth binding to `secrets_v1` "because there is a real PIN now": the sync
  uses the token while locked.

## 7. Tests (JVM; the Keystore behind `SecretBox`)

The fake box is `javax.crypto` AES/GCM/NoPadding with a JVM key (as `PinVaultTest`'s HMAC key), with a switch to fail;
prefs are the fake SharedPreferences `PinVaultTest`/`ParentPinStoreTest` use.

- `SealedPrefsTest`: round trip; another AAD or bad Base64 -> `Unreadable`; legacy plaintext sealed into the same key
  in one commit, and not sealed again; `migrate = false` returns it and writes nothing; `open` failing leaves the
  stored string byte-identical and isn't memoised (the next good read heals); a seal failure in the migration keeps
  the plaintext; `edit` with a failing box commits nothing; a commit from a second thread between the migration's two
  reads isn't overwritten.
- `kidConfigFor` through `lockActivation`: null; real hash; redacted with usable memory, with a stored copy (the
  policy's length wins), with nothing readable -> `true to BAD_HASH`; an unusable memory config falls through to the
  store.
- `saveConfig`: a redacted/stand-in config writes `active` only; a failed seal removes the PIN fields and keeps
  `active`.
- `cacheablePolicy`: the cached JSON has `"pin_hash":""`/`"pin_salt":""`; the policy passed on keeps them.
- `DeviceToken.set`: one failure -> retried, no recreate; two -> recreated; three -> false, nothing written.
- The source scan of §3.1. The recovery button's condition as a pure `showReEnrol(tokenState, parentStatus, url)`.

Mutation targets for code QA: redacted-none returns `null`; the migration's commit outside `LOCK`; delete on
`Unreadable`; a plaintext fallback in `edit`; a redacted policy writing the PIN fields; `showReEnrol` with only one
half of its condition.

## 8. Emulator checks for the user (debug build)

The debug hook `PinVaultKeyReceiver` (src/debug, DUMP-guarded) gets `--es key secrets|all` to delete `secrets_v1`
(or both keys).

1. **Upgrade**: an enrolled emulator on the current build, kid PIN set, one override window opened (a reset token
   exists); install this build. `adb shell run-as me.vibb.launcher.debug cat
   shared_prefs/me.vibb.launcher.debug_preferences.xml`, `pin_lock_state.xml`, `window_relock.xml`: `mdm.device_token`,
   `pin_hash`, `pin_salt`, `token` start with `ks1:`; after the first sync `kid_mode_policy`'s `kid_lock` has
   `"pin_hash":""`. The device page's last seen moves, the kid PIN opens the lock, the parent PIN opens Settings.
2. **Redacted cache**: change the kid PIN in the PWA, sync, force-stop, reopen: the new PIN opens, the old is refused.
3. **Relock**: set an Android screen lock inside a window; at its end it is removed (the sealed reset token works).
4. **Secrets key lost, vault intact**: `key secrets`, force-stop, open: the lock up, the kid PIN refused, **no**
   "Ny oppsettkode" button; the parent code opens the lock, Settings' enrolment takes a new code from the device page,
   synced, the kid PIN works again.
5. **Total loss**: `key all`, force-stop, open: the parent-code dialog shows "Ny oppsettkode". First as the kid: a
   wrong code is refused, the keyboard appears only in that field, none of its buttons (settings, voice) opens
   anything over the lock, Cancel leaves the lock as it was. Then a typed code from the device page: enrolled,
   synced, both PINs work; the device page shows one re-enrolment.
6. **BFU unchanged**: reboot; before the first unlock an allowed number rings and a blocked one is blocked.

## Not in scope

- Design 60 (the attested signing token; StrongBox belongs there).
- The tsnet node key (`filesDir/tailscale`) and the rest of the cached policy (it would touch `CallPolicyStore.kt:128`,
  `TimeRulesStore.kt:63` and the lock's start path).
- Redacting the cached kid hash at the upgrade without a sync; reporting the kind of `Unreadable` after a re-enrolment.

## QA fixes (`qa-r10-design.md`)

- R10-1: `kidConfigFor` never returns `null` for a `kid_lock`; the stand-in gives `true to BAD_HASH`; tested through
  `lockActivation` (§3.2, §7).
- R10-2: `SealedPrefs.edit` seals, puts and commits inside `LOCK`; a leaf lock; the race test from a second thread (§2).
- R10-3: a failed seal commits `active` and removes the PIN fields; the PIN-change crash case named (§3.2, §4).
- R10-4: unusable memory falls through to the store, in `configure` and `checkPin` (§3.2).
- R10-5: the button only in the deadlock (token `Unreadable` and parent PIN `untilSync`/not configured); the keyboard
  confined and argued, option (a) over digits or our own pad (§5).
- R10-6: the reversal of 24b QA #7 named, its cost stated, the three docs to update listed (§5).
- R10-7: main-thread reads never migrate; `pinSetStored` checks presence (§2).
- R10-8: design 59 (a) is one more `SealedPrefs` field; only the hash fields go in phase D (§6).
- R10-9: `DeviceToken.set` retries once before `recreate()` under `LOCK`; `SecretBox.recreate()` (§2, §3.1).
- R10-10: 12 sites; a source-scan test (§3.1).
- R10-11: `guardTripped`'s save writes `active`/`inactive` only; no backoff step for the stand-in (§3.2).
- R10-12: the memo spec - `@Volatile` pair per AAD, `Value`s only, bad Base64 = `Unreadable` (§2).
- R10-13: the one-way format and `adb install -d` (§2).
- R10-14: the prefs file named; the redacted-cache check; check 4 without the button; the kid's attempt in check 5 (§8).
- R10-15: AAD `prefs-file/key`; 12 sites; the background pass uses the same accessors (§1, §3.1, §4).
