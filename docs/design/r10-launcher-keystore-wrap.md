# Launcher round 10 - the phone's secrets wrapped with a Keystore key (proposal)

Approved 2026-10-21 (PLAN "Keystore for the phone's secrets", item (1); item (2), the hardware-bound token, is design
60). Closes P-L5 (`docs/review/security-review-2026-10-21-launcher.md:181`) and design 59 §7's reset-token point
(AOSP `DPM:6363-6369`: "NEVER store this token on device in plaintext"). Launcher only: no API, policy or contract
change, nothing on the server.

**Goal.** A copy of the launcher's data directory (root, an adb/OEM backup, a lab image taken AFU) yields no usable
secret: the device token, the kid PIN's hash and salt and the reset-password token are on disk only as AES-GCM
ciphertext under a key that never leaves the TEE. **Not the goal**: anything an attacker running code as our uid on
the phone can do (they can call the Keystore too) - that is design 60's and design 59's ground.

## 1. The key

One key, alias `secrets_v1`, made on first use (like `PIN_VAULT_KEY_ALIAS`, `server/PinVault.kt:18`):

```kotlin
KeyGenParameterSpec.Builder("secrets_v1", PURPOSE_ENCRYPT or PURPOSE_DECRYPT)
    .setBlockModes(BLOCK_MODE_GCM).setEncryptionPaddings(ENCRYPTION_PADDING_NONE).setKeySize(256)
    .build() // randomized IV (the default), no user auth, no UnlockedDeviceRequired, no StrongBox
```

- **AES-256-GCM**: confidentiality and integrity in one; the IV is the Keystore's (12 bytes), the tag 128 bits.
- **AAD = the field's name** (`"default/mdm.device_token"`, `"pin_lock_state/pin_hash"`, `"pin_lock_state/pin_salt"`,
  `"window_relock/token"`): a blob can't be moved into another field, and a mix-up in our code fails loudly.
- **No user-auth binding, no `setUnlockedDeviceRequired`**: the sync, the SSE stream and the music bridge use the
  token while the screen is locked (design 59 §7), the relock and design 59's PIN command use the reset token while
  locked, and today the "unlock" is our own lock, which Android doesn't see. Such a key also survives adding or
  removing an Android credential (the window relock's `resetPasswordWithToken`, design 59's hybrid): only auth-bound
  keys are invalidated by that.
- **TEE, not StrongBox** (a deliberate narrowing of the brief, same call as the vault's QA S4): the threat here - a
  copied data directory - is fully met by any non-exportable key; StrongBox adds a secure-element round trip
  (tens to hundreds of ms) on the lock's start path (§3), its own failure mode (`StrongBoxUnavailableException`, key
  slots) and a fallback branch. Its real value - an attested key that survives a TEE exploit - belongs to design 60's
  signing key. Open question 1.
- **One key for all four fields**: a Keystore loss is in practice keystore-wide, so separate keys buy no resilience,
  only code.

## 2. The stored form and the one helper

A sealed value is a string `ks1:` + Base64(IV || ciphertext+tag) in the **same pref key** as today's plaintext. All
four plaintexts are hex (the server's `generate_device_token`/`hash_pin`, our 32-byte reset token), so `:` never
starts or appears in one: the prefix alone says sealed or legacy - no version flag.

New file `server/SealedPrefs.kt` (GPL, ~80 lines):

- `interface SecretBox { fun seal(aad: String, plain: ByteArray): ByteArray; fun open(aad: String, blob: ByteArray):
  ByteArray }` - throws on any failure. `KeystoreSecretBox` (Android, ~30 lines, the vault's `KeystoreVaultKey`
  shape) makes the key when the alias is missing - `seal` only; `open` with no key throws. Never deletes the key
  except in §5's one case.
- `class SealedPrefs(box)`, the pure core:
  - `read(prefs, aad, key): Sealed` = `Absent` | `Value(plain)` | `Unreadable`.
    - `ks1:` value: opened (memo below); an exception = `Unreadable`, logged once per stored value, **the stored
      value is never touched** (a transient Keystore error must not cost the phone its enrolment).
    - Legacy plaintext: the migration (§4), then `Value(plain)`.
  - `put(editor, aad, key, plain): Boolean` - seals and puts; false = sealing failed and **nothing was put** (never a
    plaintext fallback).
  - `LOCK`: every read-migrate and every write of a sealed field holds it (short: one Keystore call), so a migration
    can never write an old value over a newer one (an enrolment, a new reset token).
  - A memo `(aad, stored string) -> plain`, as `cachedPolicyMemo` (`server/MdmSyncWorker.kt:1033-1054`): one Keystore
    call per stored value per process. The token is read per request (the music bridge, downloads) and the kid PIN on
    the lock's start path; the memo keeps the Keystore off both. Plaintext in memory is unchanged from today (AFU is
    out of scope, design 59 §7).

## 3. The three secrets: where they are, who reads them

All three are in **credential-encrypted** storage today and stay there; none moves to DE. Nothing reads them before
the first unlock: the direct-boot components (`calls/DirectBootComponents.kt:10-19`) read only DE prefs
(`boot_call_policy`, `call_boot_state`, the boot cover's file), and the review's DE list holds no token or PIN
material (review :293-295). So the call screening's boot path is untouched, and the key's own BFU availability never
matters (the ciphertext is unreadable BFU anyway). Design 59 keeps that rule (§6).

**3.1 The device token.** Default prefs `mdm.device_token` (`preferences/LauncherPreferences$Config.java:44`).
- Written by `server/Provisioning.kt:44` and `ui/settings/launcher/SettingsFragmentLauncher.kt:353`, both through the
  generated setter (an async `apply()`).
- Read raw (`mdm.deviceToken()`) at 11 sites: `MdmSyncWorker.kt:74`, `CommandListenerService.kt:206`,
  `AppInstallReceiver.kt:158`, `AppDownloads.kt:546`, `PowerEvents.kt:306`, `MusicSync.kt:217,359`,
  `MusicFileDownloads.kt:221`, `MusicBridgeService.kt:176,208,273`, `SettingsFragmentLauncher.kt:464`.
- Change: `object DeviceToken { fun get(): String?; fun state(): Sealed; fun set(context, token): Boolean }` in
  `SealedPrefs.kt`. Every site calls `DeviceToken.get()` (`null` for `Absent` and `Unreadable`, so each site's existing
  "not enrolled, skip" branch covers a broken key). Both writers call `set`, which commits synchronously - also fixing
  the `apply()` race with a process death right after enrolment. `resetPreferences` still clears it with the rest.

**3.2 The kid PIN's hash and salt.** Two copies today:
- CE `pin_lock_state` `pin_hash`/`pin_salt` (`lock/PinLockStore.kt:21-22`, read `:37-41`, written `:48-57`). Read at
  process start (`PinLockRuntime.kt:249`, `:440`), at a PIN check (`:1551`) and at every `configure` (`:1176-1177`).
- The cached policy's `kid_lock` (`dto/PolicyResponse.kt:136-137`, cached by `storeAcceptedPolicy`,
  `MdmSyncWorker.kt:999-1019`), which `configure` reads at every re-apply from the cache.

Change - one wrapped copy, as the parent PIN has one vault:
- `PinLockStore` seals both fields through `SealedPrefs` (`config()` reads, `saveConfig()` puts; a failed seal skips
  that commit, returns false, and the in-memory config holds - as a failed commit today). The backoff's
  `hash_fingerprint` (8 bytes of SHA-256(salt:hash)) may stay plain: without the salt it can't test a PIN guess.
- `storeAcceptedPolicy` caches `kidLock.copy(pinHash = "", pinSalt = "")` (a new `cacheablePolicy`, next to
  `redactedPolicy`; **only** the cache - the policy passed on to `AppEnforcer.apply` keeps the hash, so the sync's
  `configure` still sees it). `""` means redacted, as for the parent PIN (`PinVault.kt:131-136`); the server's
  `hash_pin` never sends an empty hash.
- `configure` takes the PIN from a pure rule, `kidConfigFor(kidLock, stored)` in `PinLockState.kt`:
  `kidLock == null` -> no PIN (as today); a real hash -> it; redacted -> the stored config with the policy's
  `pin_length`. **A redacted policy never writes the PIN fields**: with the stored copy `Unreadable` the in-memory
  config is used, or none (the lock still comes up, `BAD_HASH`, the parent code opens it - today's "unreadable" path,
  `PinLockRuntime.kt:436-443`), and the sealed fields stay for the next try.
- `pinSetStored` (`:249`) is unchanged: it asks for `kidLock != null`, which the redacted cache still answers.

Design 59 phase C removes the kid hash from the policy and the PIN check from the phone; this sealing then goes with
it (one release later, phase D).

**3.3 The reset-password token.** CE `window_relock` `token`, hex (`server/WindowRelockStore.kt:36,176-178`). Written
by `ensureResetToken` (`WindowRelock.kt:250-264`: stored first, then `setResetPasswordToken`); read by its
`resetTokenNeeded` check (`:260`), `tokenUsable` (`:550`) and `removeCredential` (`:553`). Change: `token()` and
`saveToken()` go through `SealedPrefs`; `token()` returns `null` for `Unreadable`. Then `tokenUsable` is false (the
relock reports "credential left", today's honest outcome) and the next window's `ensureResetToken` sets a new token
when the phone has no credential (`resetTokenNeeded`, `WindowRelockPlan.kt:127-128`) - a new `setResetPasswordToken`
replaces the old one in DPM. A failed `saveToken` already returns before `setResetPasswordToken` (`:262`).

## 4. Migration of existing plaintext

Inside `SealedPrefs.read`, under `LOCK`: re-read the field; still the same plaintext -> seal it and `commit()` the
sealed string **into the same key** (one write replaces the other). Then:
- **Idempotent**: a sealed value is never sealed again (prefix).
- **Crash midway**: before the commit the plaintext is still there and migrates at the next read; after it, it is
  sealed. Never both, never neither. Each field is its own commit; a hash sealed and its salt not yet is fine.
- **Seal fails** (Keystore error): the plaintext stays and is returned - no worse than today, never deleted (that
  would unenroll the phone); the next read retries.
- **When**: lazily on any read, plus once at process start: `Application.initRest` posts one background task reading
  the three (`DeviceToken.get()`, `PinLockStore.config`, `WindowRelock.store(app).token()`), so a reset token isn't
  left plain until the next window. CE is unlocked there (`initRest` runs only after it).
- **The cached policy's kid hash** goes at the next accepted policy (every sync rewrites the cache through
  `cacheablePolicy`; the upgrade itself comes over the network, so that is seconds away). `configure` stores the
  sealed copy from the first apply after the upgrade - from the old cache, which still has the hash - so the
  redaction never runs ahead of the stored copy. A crash between the sync's cache write and its apply on a phone's
  very first PIN means `BAD_HASH` until the next sync (at start): the lock up, the parent code opens it.

No plaintext copy survives: SharedPreferences rewrites the whole XML file on a commit (`.bak` is deleted on success).
The old blocks on flash are the filesystem's business (FBE; out of reach of a data-directory copy).

## 5. A lost or broken key - never a bricked phone

When: an OEM Keystore bug (seen after OTAs on some phones), a Keystore wipe; **not** a backup restore (none is
possible, below), not app-data clearing or a factory reset (the prefs go too), not a credential change (§1).

What happens, field by field:
- **Kid PIN** -> `Unreadable`: `BAD_HASH`, the lock up, the parent code opens it; the next accepted policy brings the
  hash and `configure` seals it under a new key.
- **Reset token** -> §3.3.
- **Device token** -> `Unreadable`: every sync site skips (logged); the phone keeps its last rules offline
  (`PolicyGate`), calls work. The parent sees the phone go silent on the device page.

**The deadlock to break.** The parent PIN's vault key is lost in the same event. Its dialog then says "until the phone
reaches the server" (`ParentPin.kt:70,107`, OK disabled) - and with the token unreadable the phone never reaches it.
Today a sync heals the vault in seconds; after this change nothing would, and with the kid PIN unreadable too the lock
can't be opened. So:

- **Re-enrolment from the parent-code dialog.** While `DeviceToken.state()` is `Unreadable`, both parent-code dialogs
  (the lock's, `PinLockActivity.kt:760-800`, and `ui/ParentPinDialog.kt`) show one more button, "Ny oppsettkode": a text
  field for a typed enrolment code from the device page's Connection section (`/regenerate-code`, 8 characters, 15
  minutes; the server rate-limits wrong typed codes). It runs the enrolment Settings runs (factored into one
  `Enrollment.enroll(context, code)` used by Settings, this dialog and - for its HTTP part - `Provisioning`), with the
  stored server URL. On success: the new token is sealed and committed, the usual clears (`ParentPin.clearEvents`,
  `CommandAnswers`, `InstallNow`), a sync - which brings the policy, re-wraps the parent PIN and seals the kid PIN.
- No new trust: the code is a server-issued one-time secret only the parent's server login can make; the server
  already treats it as re-enrolment of the same device row (the old token stops, pending commands are dropped,
  `server/src/handlers/device_api.rs:146-160`). Without a server URL (never enrolled) the button isn't shown.
- **Sealing fails at enrolment** (the key exists but is broken): `DeviceToken.set` deletes `secrets_v1`, makes a new
  one and seals once more. Only here: the token is being replaced anyway, and the kid PIN and the reset token it
  orphans come back by §3.2/§3.3. If that fails too, the enrolment reports failure and writes nothing (no plaintext);
  the phone can't be managed until the Keystore works - as on a phone whose vault can't make a key today.

**Backup.** Already closed and unchanged: `allowBackup="false"` (`AndroidManifest.xml:103`) and both rule files
exclude every domain (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`). Keystore keys never travel in a backup
or a device transfer, so even a copied or restored prefs file is ciphertext only.

## 6. Design 59: how the hybrid reads the reset token

Through `WindowRelockStore.token()` as today - the plaintext never leaves that accessor's caller.
- The PIN command (design 59 §4.5: the new PIN as a one-shot command) runs after unlock (commands are CE-only):
  `resetPasswordWithToken(admin, pin, token, 0)` works while Android's keyguard shows, because the key has no
  `UnlockedDeviceRequired`. BFU the command waits, as every command does. The PIN itself is never stored.
- Design 59 must revisit `resetTokenNeeded`'s `!secureNow` (`WindowRelockPlan.kt:127-128`): with the kid's real
  Android PIN always set, a lost token can only be replaced while secure - `setResetPasswordToken` then activates it
  at the kid's next unlock (escrow). That rule change is design 59's, not this round's.
- Never add `setUnlockedDeviceRequired` or auth binding to `secrets_v1` later "because there is a real PIN now": the
  sync uses the token while locked (§1).

## 7. Tests (JVM; the Keystore behind `SecretBox`)

The fake box is plain `javax.crypto` AES/GCM/NoPadding with a JVM key (as `PinVaultTest`'s JVM HMAC key); prefs are
the fake SharedPreferences `PinVaultTest`/`ParentPinStoreTest` already use.

- `SealedPrefsTest`: round trip; a blob under another AAD fails (`Unreadable`); a legacy plaintext is sealed in one
  commit into the same key and returned, and a second read doesn't seal again; an `open` failure returns `Unreadable`
  and leaves the stored string byte-identical; a `seal` failure in the migration leaves the plaintext and returns it;
  `put` with a failing box puts nothing; the memo opens a stored value once; a write between a migration's two reads
  isn't overwritten (the re-read under `LOCK`).
- `PinLockStateTest`: `kidConfigFor` - null, real hash, redacted with a stored config (the policy's length wins),
  redacted with none.
- `cacheablePolicy`: the cached JSON has `"pin_hash":""`/`"pin_salt":""` under `kid_lock`, the rest unchanged; the
  policy passed on keeps them.
- `DeviceToken.set`: a broken box once -> key recreated and sealed; broken twice -> false, nothing written.

Mutation targets for code QA: skip the `LOCK` re-read; delete the stored value on `Unreadable`; a plaintext fallback
in `put`; a redacted policy writing the PIN fields.

## 8. Emulator checks for the user (debug build)

The debug hook `PinVaultKeyReceiver` (src/debug, DUMP-guarded) gets an extra `--es key secrets|all` to delete
`secrets_v1` (or both keys).

1. **Upgrade**: an enrolled emulator on the current build, kid PIN set, one override window opened (so a reset token
   exists); install this build. `adb shell run-as me.vibb.launcher.debug cat shared_prefs/<file>.xml` for the default
   prefs, `pin_lock_state.xml` and `window_relock.xml`: `mdm.device_token`, `pin_hash`, `pin_salt`, `token` start with
   `ks1:`; after the first sync `kid_mode_policy`'s `kid_lock` has `"pin_hash":""`. The device page's last seen moves,
   the kid PIN opens the lock, the parent PIN opens Settings.
2. **Relock**: set an Android screen lock inside a window; at the window's end it is removed (the sealed reset token
   works), as design 24b's check.
3. **Secrets key lost, vault intact**: `key secrets`, force-stop, open: the lock comes up, the kid PIN is refused
   (`BAD_HASH`), the parent code opens it; no sync runs (the token was under the same key) and the parent-code dialog
   shows "Ny oppsettkode". Type a new code from the device page: synced, the kid PIN works again.
4. **Total loss**: `key all`, force-stop, open: the parent-code dialog shows "Ny oppsettkode"; make a typed code on the
   device page, type it: enrolled, synced, kid and parent PIN both work; the device page shows one re-enrolment.
5. **BFU unchanged**: reboot, before the first unlock an allowed number rings and a blocked one is blocked, as before.

## Not in scope

- Design 60 (the hardware-bound, attested signing token; StrongBox belongs there).
- The tsnet node key (`filesDir/tailscale`, written by the Go library) and the rest of the cached policy (contacts,
  numbers) - wrapping the whole policy cache would touch the call and time-rule readers (`CallPolicyStore.kt:128`,
  `TimeRulesStore.kt:63`) and the lock's start path.
- Reporting "the key was lost" to the server (it can't be authenticated then; the silence and the re-enrolment show it).

## Open questions for the user

1. TEE only, or StrongBox when the phone has it (§1)? Proposal: TEE - same protection against a copied data
   directory, no latency on the lock's start, less code.
2. The re-enrolment button in the parent-code dialog (§5) is the one piece of new UI. Is a typed code from the device
   page the right recovery, or would you rather accept "factory reset and the QR" for a total Keystore loss?
