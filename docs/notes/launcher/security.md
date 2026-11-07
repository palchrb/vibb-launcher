# Launcher notes: parent PIN hardening, Keystore wrap, security review

Moved from launcher/CLAUDE.md on 2026-11-01.

## Parent PIN hardening (design 24, `docs/design/24-parent-pin-hardening.md`, at the monorepo root)

- **One gate** (`server/ParentPin.kt`): the PIN lock's "Parent code" (`pin_lock`), the Settings gate (`settings`), "Pause all restrictions" (`pause`) and "Install from Play" (`install_mode`) all call `ParentPin.check(context, entry, pin)` - background thread only (PBKDF2); the time lock's "Enter unlock code" (`override`) went on 2026-11-01 (PWA only; the server still words older `override` events). Since design 24b the parent PIN is checked through its vault (`PinVault`, below) and `PinHash.verify(` exists only in `PinLockRuntime` (the kid's PIN); `RestrictionsPause.start` and `PlayRuntime.startInstallMode` are called only from `ParentPin.kt` (the pause also from the PWA's `start_pause`), and nothing calls `OfflineOverride.activate` any more (gone) - the window starts inside `check`, after its `unlocked` event is committed (`ParentPinCallersTest`). Every pre-check reads `ParentPin.status` (configured - one rule, `PinHash.usable` included -, lockout left, tries left, next lockout): a dialog opened after 4 wrong tries anywhere warns "One more wrong try locks it for ...". One lock; `check` nests only design 24b's short locks (the relock marker's in `WindowRelock.opened`, the policy cache's in the vault's migration), neither of which ever takes `ParentPin`'s.
- **Lockout** (`ParentPinLockout.kt`, pure, `ParentPinLockoutTest`): 5 wrong tries lock every entry for 15 min, then 30 min, 1/2/4/8/16 h, cap 24 h (level 1-8). A correct PIN clears the tries but keeps the level; the level drops one step per 24 h without a wrong try (`decay` window); a new PIN from the server (another fingerprint) resets everything, a running lockout included. Within a boot both windows follow `backoffRemainingMs`; across a reboot `refreshParentLockout` re-anchors them by the wall clock (a clock set back gives the full duration, never more). The try **and** its `wrong`/`lockout` event are committed before PBKDF2; a match goes back to the pre-attempt state with no failures and swaps the event for `unlocked`. Under 6 characters: `TooShort`, not counted.
- **Store** (`ParentPinStore.kt`, `ParentPinStoreTest` against a fake prefs map): CE prefs `parent_pin_state`, every write one `commit()` of everything (`version`, `failures`, `lo_*`, `dc_*`, `pin_fp`, `events_v1`; the level is never stored - `levelNow` reads it from the decay window). The first load migrates the old `offline_override_failed_attempts`/`_locked_until` (still declared in `LauncherPreferences$Config` only for that; a running lockout keeps running, at most 15 min). An unreadable state (type clash, out of range, missing key) becomes a committed 15-minute level-1 lockout - never a dead end; a corrupt queue is dropped; a failed commit writes the previous state back and answers `Unavailable` ("Couldn't check the code" / "storage is full").
- **Dialogs** (`ui/ParentPinDialog.kt`; the PIN lock's own `dialog_pin_parent_code`): every result in the dialog's error line, never a toast (they don't show while a budget sets `DISALLOW_CREATE_WINDOWS`). No PIN or a running lockout opens the dialog in that state with OK disabled; after a lockout OK stays disabled (Cancel and reopen). Texts: the time left (`lockoutText`, "1 h 45 min", nb "1 t 45 min") plus the way out (a new parent PIN), and on the last try the next lockout. The Settings gate drops an Ok from an ended session (`session`, bumped in `onStop`).
- **Events** (`ParentPinEvents.kt`, `dto/ParentPinEvent`): `unlocked` (with `until_ms` for override/pause/install mode), `wrong` (`failures`) and `lockout` (`level`, `until_ms`), never PIN material; at most 100 queued, `wrong` dropped first. Uploaded only after the status report in `performMdmSync` (`POST /api/devices/pin-events`): a snapshot, and after a 2xx only the sent ids leave; dropped on any other 4xx except 401/404/408/429, kept on those, 5xx and no answer (an old server's 404: they wait). Enrollment clears the queue (not the lockout). Capability `parent_pin_events_v1`. The legacy `offline_override_used` status flag stays for older servers.
- **Sync at once** (launcher round 6 §1, `docs/design/r6-launcher-small-items.md`): `ParentPin.check` asks `SyncRunner.request(app, "pin_event")` when the pure `push/SyncTriggers.pin(result, lockoutAlreadyRunning)` says so - `Ok`, `Wrong`, and a `LockedOut` this try started; never a lockout already running (or an unreadable state, which became one: `parentPinStatus(...).lockoutMs > 0` read under the lock right before `checkParentPin`), `TooShort`, `NotConfigured`, `Unavailable` or `ForeignAccessOn`. The request comes after the verdict and outside `ParentPin`'s lock (`check` is a `synchronized(this)` block, not `@Synchronized`), never in `checkParentPin`, whose count-first save is a provisional `wrong` (`SyncTriggersTest` pins the order). The event body and the upload are unchanged.

## Windows that lock again, the parent-PIN vault, unseen boots (design 24b, `docs/design/24b-override-relock.md`, at the monorepo root)

- **Windows** (security review M9): an override (time lock, 2 h; none starts since 2026-11-01, one opened before runs out) or a pause (Settings, 2 h; or the PWA's `start_pause`) opens the phone completely, nothing blocked but the accessibility list (below); it locks down again by itself. `RestrictionsPause.start` (and `OfflineOverride.activate` until 2026-11-01) calls `WindowRelock.opened(context, kind, start) { lift }` first: under `WindowRelockStore.LOCK` it commits the marker (CE prefs `window_relock`: `pending`, `gen + 1`, `windows`) and - while none was pending - the start snapshot (when, Android credential, every non-system package's signers and update time, the disabled system apps, the user CA certificates' SHA-256, each saved Wi-Fi network's proxy by SSID), then runs the lift (the window's own flags), so no relock sees a marker without its window; then the window's end alarm (not waiting for the starter's `apply()`), the reset-password token (32 random bytes, stored first, set only while not active and no credential: it activates at once) and - as device owner only - the "Lock again" notification. Install mode stays out (its own end path).
- **Triggers**: one alarm - `TimeRulesRuntime.nextBoundary` has the window ends also without a time policy (the earlier of the wall end and the elapsed time left, `windowEndMs`) -, `TimeRulesRuntime.recheck(context, trigger)` (boundary alarm, boot - `boot` only in a boot `BootWitness.newBoot` hadn't seen, a crash/update restart is `timer` and brings Home -, screen on/user present) ends with `WindowRelock.check`, which never blocks (a conflated channel to one IO worker under `relockMutex`); `performMdmSync` runs `relockInSync` right after its `apply()` and before the status report; "Lock again" (notification `launcher:window` ids 1007/1008, ongoing - a device owner's can't be swiped away -, and Home's banner "Open until 18:40 · Lock again"), the Settings pause switch turned off and the PWA's `end_windows` call `lockAgain` (ends both windows; a window an older build opened without a marker is adopted first, `adoptedMarker`).
- **The relock** (`runRelock` in `WindowRelockStore.kt`, pure rules in `WindowRelockPlan.kt`, JVM-tested with a fake phone): due when the marker is set and no window is open; (1) an Android screen lock set in the window goes (`resetPasswordWithToken(null)`, only when the phone had none at the first window's start), (2) the signer check (`signerCheck`/`relockBlockDecision`): a package re-signed (no shared certificate with the expected signer: the catalog's pin - M10's `signer_sha256`, kept from the sync's app list by `rememberCatalogPins` - or the snapshot's) or new from an installer other than Play or us goes into `relock_blocked` (package -> expected signers) whatever the allowlist says; without a snapshot every package installed since `start_ms` from elsewhere is; `apply()` treats them as not allowlisted (hidden, never pinned, not in the lists; `effectiveAllowlist`, `shouldSuspendNewPackage`); a block clears when uninstalled, back to the expected signer (or, with none known, from Play/us), or on `allow_blocked` (not one with another key than its pin); an install outside a window re-checks that package (`onPackageInstalled`; a static shared library's versioned name is skipped quietly); each block keeps a `BlockNote` (why: `outside_play`/`other_key`/`catalog_key`, where: `relock`/`install`, when) and every status report sends all current blocks as `relock_blocks` (`currentBlocks`, with the app's key now, at most 16, and `relock_blocks_total` - `currentBlocksTotal` - so the PWA can say "and N more", QA of cmd-expiry Low 2) - an install-time block reaches the PWA only that way; the relock report has `blocked_signers`; a throwing check keeps the marker; (3) `apply()` (always, also in the sync), (4) Wi-Fi proxies set or changed since the start (`setHttpProxy(null)` + `updateNetwork`, location switched on and fine location self-granted around it - a grant stays, revoking would kill us; a refused one is a failure), user CA certificates added since the start (`uninstallCaCert` each, then read again; one left is a failure), our notification permission, (5) detections (a proxy left on any network but a Wi-Fi one kept or just cleared, enabled non-system accessibility services, other device admins, system apps newly disabled, non-system notification listeners, our notifications off), (6) the lock re-check and the time-rule screen, the alarm, Home to the front (not during a call - the self-update gate's check -, a newer window or a process start), the notifications. Findings are committed per step and add up across re-runs; a step that throws is a failure and the rest runs; a throwing `apply()` or a window opened meanwhile (`gen`) keeps the marker. The last finished relock is the status report's `relock` (`RelockReport`, every key always sent; lists valid package names, at most 8); capability `window_relock_v1`.
- **`apply()` outside windows while apps are managed** (`allowlist != null`, never on calls-only phones): `DISALLOW_CONFIG_TETHERING` (setting it untethers), `setPermittedAccessibilityServices` (also in windows: the one block a window keeps, the user's decision on QA 24b High 1)/`setPermittedInputMethods` = the allowlist (system ones always permitted; set after hiding, only when they differ; AOSP refuses a list that leaves out an enabled non-system service - `AppEnforcer.permittedListsOk`, the relock reports `permitted_lists`), and the keyboards never hidden are `keptInputMethods` = enabled ∩ (system ∪ allowlist).
- **Commands**: `end_windows` (`lockAgain(COMMAND)` + `PlayRuntime.endInstallMode`, answers "ended: pause, install mode" / "nothing was open"), `allow_blocked` ("allowed: n apps"; only the blocks in `PendingCommand.allow` whose key is still the one the PWA showed - `allowBlocked(..., listed)`, QA of cmd-expiry Low 1; every block when an older server sends none) and `start_pause` (`RestrictionsPause.start` with a fresh 2-hour start - the only caller besides `ParentPin`; "paused for 2 hours"), each followed by one more sync (a command queued behind follows).
- **Foreign access** (QA 24b High 1 (b), `server/ForeignAccess.kt`, `ForeignAccessTest`): an active device admin other than ours, or an enabled accessibility service, of a non-system app outside the effective allowlist (a device owner can't hide, suspend or uninstall an admin app) makes `ParentPin.status` (`foreignAccess`, before the keypad) and `ParentPin.check` (`ForeignAccessOn`, before anything is counted) refuse the parent PIN in all five entries; the dialogs point to the parent app. Checked live (a failed check keeps the last finding, CE prefs `window_relock`), after a relock and at a process start (a new finding requests a sync), and at every sync: status `foreign_access` `{admins, accessibility}`, left out when nothing is found. Calls-only phones: never.
- **Our own keypad** (`ui/ParentPinKeypad`, `include_parent_pin_keypad.xml`): every parent-PIN dialog (`ParentPinDialog`, the PIN lock's "Parent code") types on twelve keys with a dot row, up to 32 digits - no `EditText`, no keyboard -, `FLAG_SECURE` and data-sensitive views (`WindowRelockCallersTest`). An accessibility service outside the allowlist can't be turned on in a window any more, and one that is on refuses the PIN (foreign access).
- **The parent-PIN vault** (review L6, `server/PinVault.kt`, pure `PinVaultCore`, `PinVaultTest`): CE prefs `pin_vault` hold `tag = HMAC(K, PBKDF2 hash)` and the salt, K the AndroidKeyStore HMAC key `pin_vault_v1` (TEE, no StrongBox, no user auth). `ParentPin.acceptPolicy` (the sync, before caching) is the only reader of a policy's `override_pin_hash` (scan in `PinVaultTest`); the cached policy has `""` there. A PIN is checked as `HMAC(K, PBKDF2(pin, salt)) == tag`; the lockout's fingerprint is the tag's first 16 hex characters. Migration (first start, idempotent): wrap design 24's plain hash, move a lockout stored for its old fingerprint over (`ParentPinStore.rewriteFingerprint`), re-encode the cached policy (`redactCachedPolicy`, under `POLICY_CACHE_LOCK` shared with `storeAcceptedPolicy`), delete the plain fields. A key that fails twice sets `rewrap_needed` (the old tag and salt stay, also when it fails in `accept`): "can't be checked until the phone reaches the server" (`Unavailable(untilSync)`, nothing counted), the next sync makes a new key - the same PIN (same salt) keeps its lockout. The migration runs from `initRest` on IO (`ParentPin.prepareVault`). The kid's lock never uses the vault (since design r10 its hash is sealed under `secrets_v1` instead, below). Debug-only hook `PinVaultKeyReceiver` (src/debug, DUMP) deletes the key for the device check (`--es key secrets|all` since r10).
- **Boot witness** (H4, `server/BootWitness.kt`, `BootWitnessTest`): early in `Application.onCreate` (after the call policy, also in direct boot) `BOOT_COUNT` against device-protected prefs `boot_witness`: a jump > 1 adds `jump - 1` to a count that only goes up; the first start of the build counts nothing. Status `unseen_boots` (`{count, last_at_ms}`). `DISALLOW_SAFE_BOOT` by default is design 24b S4, held back until device check 6 (design 04) passes.
- **Not device-tested**: design 24b §7 - the alarm with the screen off and airplane mode on, the unswipeable notification, each surface reset (proxy with location off, APN proxy reported, CA certificate, an Android PIN removed and handy's lock back, a sideloaded keyboard gone, a sideloaded accessibility service refused in a window, hotspot off), the foreign-admin backstop, certificates and proxies from before a window kept, the same-name sideload case, a reboot in a window, the vault upgrade and key deletion, check 6.

## The phone's secrets sealed under a Keystore key (design r10, `docs/design/r10-launcher-keystore-wrap.md`, at the monorepo root)

- **What** (security review P-L5): the device token (default prefs `mdm.device_token`), the kid PIN's hash and salt
  (CE `pin_lock_state` `pin_hash`/`pin_salt`) and the reset-password token (CE `window_relock` `token`) are on disk only
  as `ks1:` + Base64(IV || AES-256-GCM ciphertext+tag) in the same pref key, under the non-exportable AndroidKeyStore key
  `secrets_v1` (TEE, not StrongBox; no user auth, no `UnlockedDeviceRequired` - the sync, the stream and the relock use
  them while locked; never add either). AAD = `prefs-file/key` (`default/mdm.device_token`, `pin_lock_state/pin_hash`,
  `pin_lock_state/pin_salt`, `window_relock/token`), so a blob can't move fields. The format is one-way (an older build
  reads `ks1:` as a token: 401; `adb install -d` on the emulator means re-enrol).
- **`server/SealedPrefs.kt`**: `SealedPrefs(box)` - `read(prefs, aad, key, migrate)` = `Absent`/`Value`/`Unreadable`
  (a blob that doesn't open or isn't Base64 is `Unreadable`, logged once per stored value, **never touched**); `edit`
  seals, puts and commits inside one leaf `LOCK` (a failed seal commits nothing - never a plaintext fallback); a legacy
  plaintext is sealed into the same key by a migrating read (re-read under `LOCK`, so it never overwrites a newer
  write); **a read on the main thread never migrates** (`Secrets.migrateHere()`), and `Secrets.migrateAll` runs once
  per process from `initRest` on IO; the memo is one (stored, plain) pair per AAD, values only. `DeviceToken` is the
  **only** code that names the generated `deviceToken` getter/setter (`get()` - `null` for none or unreadable, so every
  site's "not enrolled" covers a broken key -, `state()`, `present()` - stored at all, `tokenStored`, no Keystore call -,
  `set()`: one retry, then a new key only when `SecretBox.keyBroken()`); never read the token raw - `Bearer ks1:...`
  looks like a dead server (`SealedPrefsTest` pins every `DeviceToken.get()` site and bans the key `mdm.device_token`,
  the AADs and the generated accessor outside their owners in `src/main`, `src/debug` and `src/release`). The
  Keystore box caches its key handle; no token read on the main thread but `PinLockRuntime.init`'s PIN (the SSE
  connect reads on a background thread, Settings' Sync now checks presence).
- **One enrolment** (`server/Enrollment.kt`): the QR's `applyProvisioningExtras`, Settings' "Enroll now" and "Ny
  oppsettkode" call `Enrollment.enroll`, which brings the tailnet up first (`TsnetClient.connectFromPreferences`),
  seals the token on IO, sets `enrolled` and clears the old enrolment's state (`ParentPin.clearEvents`,
  `CommandAnswers`, `ZoneWatch.forget`, `InstallNow`); its failure is an `EnrollFailure` that says whether the server
  refused the code (4xx). A QR enrolment that worked (`applyProvisioningExtras`: Android's provisioning and the in-app
  scanner) asks `SyncRunner.request(.., "enrolled")` as Settings' "Enroll now" does - the first policy now, not at the
  next backstop (2026-10-25, `ProvisioningModeTest`).
- **The kid PIN, one sealed copy**: `storeAcceptedPolicy` caches `policyToCache` (the kid hash and salt `""`); the sync
  applies the policy with the hash. `configure` takes the pure `kidConfigFor(kidLock, memory, stored)` (`lock/
  PinLockState.kt`): a real hash; redacted -> a usable memory, else the usable sealed store, else the unusable stand-in
  (`true to BAD_HASH`: the lock up, the parent code opens it; the prompt "Only the parent code works right now", `GlancePrompt.UNUSABLE`, only in own mode - as Android's face the kid PIN still opens Android's pad, so the stand-in shows the normal hint: `glancePrompt(.., androidFace)`, r10 run F1, `LockGlanceTest`) - **never `null` while `kid_lock` is there**; the policy's
  length wins; a stored copy counts only with the policy's `pin_rev` (or rev 0). `saveConfig(PinWrite)`: `Write` only
  for a real hash (sealed; written again while `pinStored` is false), `Remove` for no `kid_lock`, `Keep` for a
  redacted/stand-in apply and the crash guard (a plain commit, no `LOCK`); a failed seal commits `active` and removes
  the PIN fields. No backoff
  step for the stand-in (`backoffStepDue`); `checkPin` reads the store again when memory is unusable; Home's
  `pinSetStored` checks `PinLockStore.pinStored` (presence, no Keystore).
- **A lost key** reverses 24b's QA #7: the lock comes up `BAD_HASH`, the next policy re-seals the PIN; an unreadable
  reset token is "none" (the next window sets a new one); an unreadable device token silences the phone (calls work,
  last rules offline) until Settings' enrolment, or - with the vault's key gone too, the deadlock - **"Ny oppsettkode"**
  (`ui/ReEnrolDialog.kt`, `dialog_re_enrol.xml`): the neutral button of both parent-code dialogs, shown only while the
  pure `showReEnrol` holds (token `Unreadable` and the parent PIN `untilSync` or not set, a server URL stored), read off
  main when the dialog opens and after every result; the one text field on the lock (no suggestions or autofill, no
  selection/paste menu, `FLAG_SECURE`), the typed setup code from the device page; it enrols (in a process scope, so a
  consumed code isn't lost to the activity) and syncs, **never unlocks**; a refused code and an unreachable server have
  their own lines (`ReEnrolTest`, `SealedGlueTest`). The parent-PIN keypads keep no text field (`WindowRelockCallersTest`).
- Tests: `SealedPrefsTest`, `KidPinSealTest`, `ReEnrolTest`, `SealedGlueTest` (the Android glue by source),
  `PolicyResponseCompatTest` (the redacted cache). Not device-tested: the note's §8 (checks 1-13).

## Security review, launcher wave (2026-10-11, `docs/review/security-review-2026-10-10.md`, at the monorepo root)

- **The bearer stays home** (L8, `server/ServerAuth.kt`, `ServerAuthTest`): `bearerInterceptor` adds `Authorization` only to requests for the server's own origin (scheme, host, port - `sameOrigin`); `createMdmApi` and `createMusicServer` both use it, so an absolute `@Url` elsewhere never gets the device token. The SSE request builds its URL from the base itself.
- **The Tailscale key is deleted after the first connect** (M12, `server/TailnetKey.kt`, `TailnetKeyTest`): tsnet reads `AuthKey` only to create the node (its state dir then holds the identity), so once `connect` returns an address `TsnetClient.forgetKeyOnceJoined` removes `tailscale_auth_key` and sets `tsnet_node_joined` in one `commit()`; later starts come up with an empty key from the stored state (`tailnetConnect`: key -> up with it, joined -> up with "", neither -> nothing). A key scanned or typed while connected is dropped the same way (tsnet would ignore it). `connectFromPreferences` is `@Synchronized` (QA Low 5): two first joins at once would race two tsnet servers on one state dir with the one key. Settings shows "On the tailnet - the key was deleted". A node removed from the tailnet may come back with a newly scanned key (tsnet logs in again when its stored node is refused; not verified) - else it is set up again (wipe and provision).
- **Remote wipe works on API 34+** (M8, `server/WipePlan.kt`, `WipePlanTest`): `LocateCommands.wipe` calls `wipeDevice(0)` (`wipeCall`): AOSP 14+'s `wipeData` from a device owner on the system user means "remove this user" for apps targeting 34+ and throws, so the old `wipeData(0)` only logged and the phone kept its data. Flags 0: factory reset protection stays (a found phone still wants the Google account). `device_admin.xml` declares `<wipe-data />` (AOSP 14 without its policy-engine flag accepts a wipe only from an admin that declares it). A wipe that throws or returns answers the command (`wipeOutcome`: a dying system server is "wiping"; anything else - also a call that *returns*, which a reset that starts never does: AOSP's `forceWipeDeviceNoLock` swallows a failure to write the recovery command, QA Low 3 - is a failure the Locate page shows). `device_admin.xml` declares exactly the uses-policies the code needs where AOSP checks the admin's declared policy rather than the owner's permissions (`limit-password` for `onPasswordChanged` - AOSP sends ACTION_PASSWORD_CHANGED only to it, not to `watch-login` as the file used to say -, `force-lock`, `wipe-data`, `disable-keyguard-features` - an Android 16 emulator refused `setKeyguardDisabledFeatures` without it); `AdminPoliciesTest` pins the XML, `DECLARED_ADMIN_POLICIES` and the calls in the sources against each other. A policy an update adds may count only after a restart: the calls retry by themselves (apply() sets the keyguard features each time), and every report lists what isn't granted yet (`admin_policies_pending`), which the device page turns into "Restart the phone once". Every status report sends `remote_wipe_ready` (`remoteWipeReady`, QA Low 4): on API 34 a device owner's `DeviceAdminInfo` is parsed at boot, so the new `<wipe-data />` counts only after a restart (`hasGrantedPolicy`); 35+ needs no policy (the owner holds `MANAGE_DEVICE_POLICY_WIPE_DATA`). The Locate page warns while it's false. Device check: `docs/testing/emulator.md` §6g.
- **Catalog APKs are checked before they install** (M10, phone side; `server/CatalogApkCheck.kt` pure, `CatalogApkCheckTest`): the server pins each catalog app's package and signing-certificate SHA-256 but only *reads* its signing block, so `AppDownloads.install` asks PackageManager (`getPackageArchiveInfo(GET_SIGNING_CERTIFICATES)`) first: the package must be the pin, and the pinned certificate must be in the APK's signers or their lineage (`SelfUpdate.signers`: `signingCertificateHistory`, or every `apkContentsSigners` with several signers - the server pins the first; a v3.1 rotation for SDK 33+, which the server ignores, keeps the older pinned key in the lineage). An unreadable manifest or signer fails closed. The session gets `setAppPackageName` (catalog: the pin; our own update: our package). A refused APK is deleted and its release refused until the list names another (`recordRefused`, no hourly re-download); a catalog app without both pins in the list isn't queued at all, and a record an older build wrote without pins waits for the next list (`finish` relists and defers). A release recorded as installed counts only while Android still has the package (`phonePackage`: `MATCH_UNINSTALLED_PACKAGES` so a hidden app counts, then `FLAG_INSTALLED`) at least at the versionCode the launcher installed (`lastInstalledVersionCode`, recorded via the install intent): one uninstalled by hand is forgotten (`forgetInstalled`) and queued again through every check (the user's emulator, 2026-10-12); PackageManager failing to tell counts as installed, so it never loops. Refusals go to the server as the install-failed report and, until the list changes, as `app_downloads` entries with state `refused_package`/`refused_signer`/`refused_unreadable`/`refused_unpinned` (`CatalogRefusal`, CE prefs `app_downloads` key `refusals`, `reconcileRefusals`).
- **Command receipts** (L12, `server/CommandAnswerPlan.kt` pure + `CommandAnswers.kt`, `CommandAnswerPlanTest`): `dispatchPendingCommand` marks the command as run (`CommandAnswers.begin`, CE prefs `command_answers`, the newest 32, one `commit()`) - keyed on its id *and* the server's `requested_at` (`commandKey`; QA of the launcher wave, Medium 1: a restored server backup or another server reuses ids), which the receipt and result send back; a new enrollment empties the state (`CommandAnswers.clear`, next to `ParentPin.clearEvents`) -, then sends `POST /api/devices/command-receipt` - before anything runs, so a wipe confirms too - and only then runs it; a command the server delivers again (our receipt was lost, or the phone refused that policy) is confirmed again and never run twice, and the sync doesn't force a fix or answer `locate` for it. Receipts and results that don't get through wait in the outbox (at most 32, receipts dropped first) and go after the status report (`flush`): a 2xx sends, 401/408/429/5xx/no answer keep, any other 4xx (a 404 from an older server's missing receipt route) drops. An unknown command answers "unknown command". Capability `command_receipt_v1` tells the server it may offer an unconfirmed command again (2 min, 3 deliveries).

## Security review 2026-10-21, the launcher's Lows, the rest (round L4b, `docs/review/security-review-2026-10-21-launcher.md`)

Round L4 (merged 2026-11-01) did the first fifteen; L4b the rest, one commit each. Tests: `SecurityLowsBTest`. S-L4
waits for a device capture of the Play Protect / account-security channel ids.

- **S-L9, kept DoT connections**: `DotPool` in `server/UpstreamTransport.kt` - see `dns-and-vpn.md`, "Transport".
- **S-L10, a death after a connect** (`server/TsnetClient.kt`, pure rules in `server/TailnetKey.kt`): a Go panic or
  abort after `connect` returned kills the process outside any try. `connect`'s `finally` now clears the pending flag
  and keeps the time it returned (`tsnet_connect_returned_at`) instead of zeroing the streak; the next process's first
  `connectFromPreferences` reads Android's record of how the last one ended
  (`getHistoricalProcessExitReasons`, the main process): `REASON_EXIT_SELF` with status 2 only (Go's panic/fatal
  exit, `GO_FATAL_EXIT_STATUS`; QA M1: our own crash handler's `exitProcess(1)` never counts), `REASON_SIGNALED` or
  `REASON_CRASH_NATIVE` within `TSNET_DEATH_WINDOW_MS` (10 min)
  counts as a crash (`deathAfterConnectIsCrash`); a process that lived on ends the streak (`crashStreakAfter`); low
  memory, an update, a force stop or a reboot never count. Two within the 15-min streak skip a connect, as before.
- **S-L10, Leave Tailnet** (command `leave_tailnet`, Advanced › Connection on the server, behind a ticked box;
  contract `server/testdata/leave_tailnet.json`): `TsnetClient.leave` closes tsnet, removes `tsnet_node_joined`, any
  stored auth key and the crash guard's marks in one `commit()`, then deletes `files/tailscale` and drops the global
  `Authenticator`. Answers "left the tailnet" / "not on the tailnet" (nothing was there) / "failed to leave the
  tailnet" / "server is reached only over the tailnet" (QA M2: refused, nothing changed, while the server URL's host
  is a Tailscale address - 100.64.0.0/10 or fd7a:115c:a1e0::/48, `tailnetOnlyServer`; off the tailnet that address is
  the carrier's CGNAT or the Wi-Fi's gateway). The answer goes into the outbox (`CommandAnswers.keep`) and the sync
  builds its API client again before its next call (QA nit 1), so nothing goes through the closed proxy; the outbox
  is flushed after the status report. Every status report sends `tailnet_joined` (`TsnetClient.joined`, the
  contract's `report`): the server shows the row only while it is true. Joining again takes a new key (Settings or a
  new setup QR). Not device-tested (needs the real aar): a joined
  phone leaves, Home resume and sync no longer start tsnet, the server still reachable directly.
- **P-L2 + P-L3, Location after a fix and the cached fix** (`server/LocateCommands.kt`, pure rules in
  `server/LocationPolicies.kt`): see `time-rules.md`, "Location policy". The server half of P-L3 needed no change:
  `zones::store_fix` already skips a fix with the same `captured_at` and coordinates (pinned by
  `zone_watch::here_now_counts_the_served_interval_and_a_still_re_send`'s "no new row").
- **S-L5, audio devices only** (Bluetooth page, server switch `bluetooth_any_device`): see `kid-settings.md`,
  "Bluetooth headphones in Kid Settings". L4 did the sharing restriction and the page's finish.

## The server address: https only (security review S-M1, the user's decision of 2026-11-04)

- **The rule** (`server/ServerUrlRule.kt`, pure; the server's `config::check_server_url` is the same rule): `https://`
  to any host, or `http://` to a Tailscale IP literal (100.64.0.0/10, `fd7a:115c:a1e0::/48`) - nothing else. No name is
  looked up, so a `*.ts.net` name must be https. The grammar is narrower than a URL (after trimming ASCII whitespace:
  `scheme://host[:port][/path]`, path `A-Z a-z 0-9 . _ ~ / -`; a strict dotted quad, a bracketed IPv6 of hex groups,
  or a DNS name whose last label isn't a number), so no userinfo, query, fragment, `%` or `\` can make OkHttp see
  another host than the rule did. `server/testdata/server_url_vectors.json`, byte-identical in
  `launcher/app/src/test/resources/`, is every row both sides run (`ServerTransportTest`, `config::tests::the_server_url_vectors`).
- **The paths** (`server/ServerTransport.kt`, `serverPath` pure, `serverPathNow` with `TsnetClient.proxy()` and
  `BuildConfig.DEBUG`): `createMdmApi`, the SSE client (`CommandListenerService.buildClient`) and `createMusicServer`
  call `OkHttpClient.Builder().toServer(path)` before anything else. `Direct` = https, through tsnet's proxy while it
  runs, else the phone's own network (as before). `Tailnet` = http to a tailnet IP, only through tsnet's proxy.
  `Closed` (`NOT_HTTPS`, or `TAILNET_DOWN` while tsnet isn't up) = the client's first interceptor throws
  `ServerPathClosed` (an `IOException`) before the bearer or a socket; the stream doesn't open and retries every 30 s
  like an unenrolled phone. No other file calls `TsnetClient.proxy()` (pinned).
- **Cleartext** (`res/xml/network_security_config.xml`, the manifest's `networkSecurityConfig`; `usesCleartextTraffic`
  is gone): off for the whole app, system CAs only, one exception - the name `tailnet-server.vibb.invalid`
  (`TAILNET_SERVER_HOST`). A network security config names hosts, never ranges, and OkHttp checks cleartext against the
  URL's host, so the tailnet path's base URL names that host: its `Dns` answers it with the tailnet address and refuses
  every other name, its `SocketFactory` makes SOCKS sockets to tsnet's proxy on loopback (a plain socket, not an HTTP
  request: no loopback exception), `NO_PROXY`, no redirects, and an interceptor sends the real `Host`. The name is under
  `.invalid` (RFC 6761) and no client but the tailnet one asks for it (another would ask the network's DNS, which a
  hostile resolver could answer - nothing of ours does). A tailnet socket refuses when tsnet's proxy is no longer the
  one its client was made with, and `TsnetClient.close()` drops the process `Authenticator` (QA L1: an app that binds
  the freed loopback port gets neither the credential nor the request). The SOCKS request carries the IP, never a name
  (`ServerTransportTest` runs it against a fake SOCKS5 proxy). `src/debug/res/xml/` adds `10.0.2.2`, and a debug build
  alone takes `http://10.0.2.2[:port]` as the address (`EMULATOR_HOST`, the emulator loop).
- **The proxy is ours** (`launcher/mobile/tsembed/dial.go`, the security QA of S-M1, H1): tsnet's `Loopback()` SOCKS
  server dials with tsdial's `UserDial`, which takes netstack only for a current peer and dials any other address - a
  Tailscale one included - on an OS socket (tailscale.com v1.104.0 `net/tsdial/tsdial.go` `dialOneUser`; `Server.Dial`
  is the same). So `StartProxy` runs its own `socks5.Server` (user `tsnet`, a random password, 127.0.0.1:0) whose
  dialer resolves once (`UserDialPlan`) and then: an address in 100.64.0.0/10 or `fd7a:115c:a1e0::/48` goes through
  `NetstackDialTCP` (never the OS network) and only while it is a peer (`UseNetstackForIP`), else it is refused;
  any other address goes through `UserDial` as before - only https goes there, and TLS with the system CAs covers it;
  TCP only. `go test ./tsembed/` (`TestRouteFor`) runs in the build-tsnet action.
- **Where it is refused**: the setup QR (`applyProvisioningExtras`, Android's provisioning and the in-app scanner) saves
  nothing and enrolls nothing; Settings' Server URL edit doesn't save it (the dialog opens again with the typed text and
  `server_url_refused` above it); "Sync now" and the in-app scanner say why instead of "couldn't reach", in a dialog (E9
  L1: a toast showed two lines and cut the advice). The Server URL row's summary says "Not used: ..." under a refused stored address and
  "Plain http, only through the tailnet" under a tailnet one.
- **A phone set up earlier with a plain-http address other than a tailnet IP** - a LAN address (the server took one
  with a warning from 0.55.0 until this rule) or `http://<name>.ts.net` (the old rule took it silently as Tailscale): it stops talking to the server at this update - no fallback, since a last report over it would carry the token
  in clear. It keeps enforcing its cached policy. The parent fixes it on the phone without adb: Settings (the parent
  code, checked offline against the vault) › Server URL › the https address; the next sync and stream use it at once.
  The PWA can't hear from such a phone (it shows as not seen since); Phone Setup says under a saved refused address that
  phones set up with it stop reaching the server and how to fix it.
  A phone with no parent code on it yet has its Settings locked (`settings_gate_no_pin`): it needs a factory reset and
  a new setup QR.
- **Release order**: the server first, then the launcher. Android's QR provisioning installs `releases/latest`, so an
  older server could still print a QR with a plain-http address that the new launcher refuses (the phone ends up
  Device Owner but not enrolled; fixable in its Settings, open until the first policy). Before the launcher ships,
  check which address the phones store (Settings › Server URL).
- **Not device-tested**: the tailnet path on a phone (our SOCKS proxy with an IPv4/IPv6 destination instead of a name,
  the NSC exception for the `.invalid` name), the proxy refusing a tailnet IP that isn't a peer (no packet on Wi-Fi or
  mobile), a MagicDNS https name through it, the refused-address summary and toast, a QR with an http LAN address.

## The server's certificate issuer, pinned (design 76, `docs/design/76-server-issuer-pin.md`)

- **The shape.** Over https the phone talks to the server only through a chain Android validated (system CAs, as
  before) that holds a key of the **built-in set B** (`server/ServerPins.kt`, `BUILTIN_PINS`): ISRG Root X1, X2, YR,
  YE (Let's Encrypt) and USERTrust RSA/ECC, Sectigo R46/E46 (ZeroSSL's operator). Any certificate of the chain counts,
  so a cross-sign carrying a pinned root's key passes wherever Android's store ends the path. Pins are `base64(SHA-256(
  DER SubjectPublicKeyInfo))`; `ServerPinsTest` computes each from the root's certificate in
  `src/test/resources/server_pins/`, so a typo fails.
- **The rule for B**: a pinned operator's new root goes in as soon as its certificate is published, never when a store
  adds it; B never gains another operator without the user's decision. PLAN chore at every launcher release:
  re-check `letsencrypt.org/certificates` and Sectigo's root page and re-run the derivation (`openssl x509 -in F -noout
  -pubkey | openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | base64`).
- **Switched on per phone by the server** (`server/src/server_tls.rs`): one Boolean `server_pin_enforce` (default
  prefs, `commit()`, `ServerPinStore`). `nextFlag` (one caller: the sync's fresh policy fetch, right after
  `storeAcceptedPolicy`, with that response's own handshake and the client's `ServerPath`; never the cache, a source
  scan pins it): `enforce: true` over a connection that passed B -> on; `enforce: false` over one that passed B or
  over `ServerPath.Tailnet` -> off; anything else (`{}` - the server's `watching` -, absent, malformed, a failing
  connection, a debug build's plain http) keeps it. A setup QR (`applyProvisioningExtras`) clears it; the Settings
  address edit doesn't.
- **`toServer`'s direct path** (`ServerTransport.kt`), in this order: the origin fence (a request to another scheme,
  host or port throws `ServerOriginRefused` before a socket; redirects are never followed), the refusal catch
  (OkHttp's `Certificate pinning failure!` only -> `ServerPinRefused`, and a `refused` sighting when the anchor reads:
  the last strict `    sha256/<44>: ` line before the **last** "Pinned certificates for", so a leaf's subject can't
  name the anchor, code QA L-1), the pinner with the
  flag on (`CertificatePinner` for exactly `HttpUrl.host`, after Android's validation; one that can't be built fails
  closed), and always the observer (each new connection's chain once; one outside B is kept as `seen`). The flag is
  read where the path is read (each `createMdmApi`, SSE connect, `createMusicServer`). The tailnet path and a debug
  build's `http://10.0.2.2` are never pinned.
- **Reported**: capability `server_pin_v1`; every status report's `server_tls: {enforcing, seen?, outside}` - `seen`
  is this sync's policy connection, `outside` the ring (`server_pins_outside`, at most 8, a row added only when it
  differs from the last). The ring's sent rows leave only after a 2xx over a connection that passed B or over the
  tailnet (R-2: a MITM answering 2xx can't erase them). Contract: `server_tls_report.json` (root `CLAUDE.md`).
- **On the phone**: Settings › "Server certificate" under Server URL (on / not on yet / the last refusal), its dialog
  names the recovery and the server's tailnet address when the server sent one (`server_tls.recovery_url`,
  `server_pin_recovery`, stored by `nextRecovery` only from a fresh policy over a passing or tailnet connection, only
  as tailnet http - code QA H-1; a setup QR clears it with the flag, verification n1); "Sync now" says why in a dialog after a refusal, with the same address. The kid sees nothing. Offline the phone keeps
  enforcing its cached policy, calls and 112 included.
- **Recovery without adb or the QR**: Settings (parent code) › Server URL › `http://<the server's Tailscale IP>` -
  WireGuard-authenticated, never pinned; the phone syncs, sends its refusals and takes the server's `enforce`. Or
  Scan setup QR (the flag goes off).
- **Debug only**: the debug network security config's `debug-overrides` trust user CAs (mitmproxy on the emulator),
  so the pin is what refuses there; `ServerTransportTest` asserts the release config has neither.
- **Tests**: `ServerPinsTest` (B, the contract's flag and drop rows, the report's encoding, OkHttp 4.12's failure
  message, the source scans), `ServerPinTransportTest` (a local TLS server with `TestOnlyChain`, a throwaway chain made
  in memory at each run by okhttp-tls's `HeldCertificate` - no key in the repo: pass, refuse before a request line,
  report-only, an IP literal, the origin fence and no redirect, a broken pinner, through a SOCKS5 relay). okhttp-tls
  4.12.0 is a test dependency only, its checksums in `gradle/verification-metadata.xml` (checked against Maven
  Central's `.sha256` and `.sha1`).
- The flag reads as on without a context (fail closed in form; never in practice), and PowerEvents' prepared shutdown
  client is rebuilt in the sync when the flag moved (code QA nits).
- **Not device-tested yet** (design 76 step 0 and §6's emulator rows): the anchor Android validates for the user's
  host and for a ZeroSSL host, mitmproxy with the check off and on, the tailnet recovery end to end, a QR re-scan.
