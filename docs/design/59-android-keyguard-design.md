# 59 - Android's own lock screen, the hybrid, for real (design)

Status: revised 2026-10-22 from its design QA (`docs/review/qa-59-design.md`, Q59-1..14 and nits) and the prototype's
code QA (`fb6df128:docs/review/qa-59-prototype-code.md`, P59-1..8); the last section lists what changed. Base:
`59-android-keyguard-assessment.md` ("A §n"), the user's answers in PLAN's design 59 entry, the prototype on
`proto-59-hybrid` (`46cf0fa5`, `59-prototype.md`, "P"), `docs/review/recents-over-lock-2026-10-22.md` and design r10.

**Goal** (the user, 2026-10-22): a full RC - a server release and `launcher-vX.Y.0-rc.1` - with Android's lock screen
behind a per-phone switch, tested live on the user's emulator. Missing switch = today's lock, so the RC is safe to
promote for every phone; the deletion of today's lock is phase D, one release after the last phone migrated.

**The user's answers this design builds on**: (1) no Locate/ring/wipe before the first unlock; make "type the PIN to
start the phone" clear. (2) The server keeps the kid's PIN reversibly - sealed, the key outside the database
(`data/keys/`) - and the PWA shows it after the parent re-enters the password; no "Foreldrekode" on the lock: the parent
types the kid's PIN, then the Parent PIN in Kid Settings as today. (3) A new PIN reaches the phone as a one-shot
command; no wait for design 60. (4) Android's PIN pad; fingerprint allowed (enrolled by the parent in a window).
(5) Keep Fossify for now.

## 0. Summary

| | Own (`kid_lock.keyguard` missing or `"own"`) | Android (`"android"`) |
|---|---|---|
| Gate | Our keypad, PBKDF2 hash, `setKeyguardDisabled(true)` - today, unchanged | Android's PIN = the Child PIN (`resetPasswordWithToken`), CE bound to it |
| Face | Our glance | Our glance, `showWhenLocked` over the occluded secure keyguard; SystemUI's lock face in the gaps and before the first unlock |
| "Lås opp" / a row | Our keypad | `requestDismissKeyguard` -> Android's PIN pad (fingerprint there) |
| Over the lock | Our re-front race | Platform: only `showWhenLocked` windows while the keyguard is locked; Recents, S-M4 closed by the keyguard |
| PIN change | The policy's hash | The policy's hash **and** a one-shot `set_kid_pin` command carrying the PIN |
| Parent override on the lock | "Foreldrekode" | None: the parent types the Child PIN (shown in the PWA) |

## 1. The contract (one commit, both directories - step 1)

- **Policy** `kid_lock` (still `null` = no lock) gains `keyguard: "own" | "android"` and `pin_rev: i64` - always sent
  with `kid_lock`. Pinned by `server/src/tests/step10.rs`'s `kid_lock` key list (the inner keys;
  `policy_json_keys_snapshot` pins only the top level) and `PolicyResponseCompatTest`. Launcher
  `KidLock.keyguard: String? = null` (anything but
  `"android"` = own), `pinRev: Long = 0`. `pin_rev` is a counter the server bumps on every Child PIN save or clear: the
  phone names "which PIN" by it, so no PIN material ever goes back (lock_state's rule).
- **Command** `set_kid_pin`: `pending_command.pin` (the PIN's digits) **only** on that command, left out otherwise
  (`skip_serializing_if`, as `allow`/`release_tag`); a server test pins `pending_command`'s keys with and without
  `pin`. Launcher `PendingCommand.pin: String? = null` with a redacted `toString()`; `storeAcceptedPolicy` caches the
  policy with `pendingCommand.pin = null` (r10's `cacheablePolicy` takes this over). Answers (`result.message`):
  `pin set`, `own lock`, `refused: no_token`, `refused: mismatch`, `refused: no_pin`, `refused: failed`.
- **Status** `lock_state` gains `keyguard` (`"android"` while Android's PIN is the gate and our glance its face, else
  `"own"`), `pin_rev` (the rev the gate enforces: own - the rev committed with the hash in use; android - the rev
  Android's PIN was set from; `null` = unknown, e.g. changed on the phone), `reset_token` (bool, our token active) and
  `keyguard_relocks` (count since the last report, §3.3). `sanitize_lock_state` keeps them typed: the two words,
  `pin_rev >= 0`, relocks capped at 1000. Under `#[serde(default)]` a missing `pin_rev` and `null` read the same, so the
  server tells an older launcher by the capability, never by the field. In android mode the phone reports `active:
  true, inactive: null` - **not** `android_credential`, which stays own mode's "remove Android's lock" warning (A §9.5
  asked for it as the normal state; re-using the word would make an older page give the wrong advice). While a
  rollback waits for the token (§3.8) the phone reports `keyguard: "android"` **and** `inactive: "android_credential"`:
  `keyguard` names the gate (Android's PIN still is), `inactive` why Vibb One's lock is off; step 2 words the pair as
  "Switching back…" with the no-token warning instead of own mode's advice, and an older page's "remove Android's lock"
  is the right runbook for it anyway (qa-59-revision N-C).
- **Power** `on.unlocked_at_ms` (optional): when CE first unlocked in this boot. `server/testdata/power_report.json`
  and its launcher copy change together (root `CLAUDE.md`).
- **Capability** `android_keyguard_v1` (launcher `STATUS_CAPABILITIES`, added in **step 4**, with the command handler -
  a build that claims it without the handler would answer `set_kid_pin` "unknown command").

**Two releases, both orders safe**:

| | Result |
|---|---|
| Old launcher + new server | `keyguard`/`pin_rev` are unknown keys (`ignoreUnknownKeys`); no `set_kid_pin` is queued (the queue rule needs `android_keyguard_v1` in the last report), and a row's `pin` is filled only for such a phone, so no PIN ever reaches it. The PWA's switch says "needs a Vibb One update". Today's lock. |
| New launcher + old server | No `keyguard` = own: today's lock, byte for byte (§3 keeps every own-mode path). |
| Server rolled back after android mode | A fresh policy without `keyguard` = own: the phone rolls back (§3.8) - Android's PIN cleared with the token, today's lock. |

## 2. Server (steps 1 and 2)

### 2.1 Storage and the command

- Migration (next free number; 0085 today): `device_policy.kid_keyguard TEXT NOT NULL DEFAULT 'own'`,
  `kid_pin_sealed TEXT`, `kid_pin_prev_sealed TEXT`, `kid_pin_prev_rev INTEGER`, `kid_pin_rev INTEGER NOT NULL DEFAULT 0`.
- **The key**: `data/keys/kid-pin.key`, its own file (a lost music key mustn't take the PINs, and vice versa), made by
  `keys::load_or_create` (0600 in a 0700 dir) and wrapped with `MusicKey::from_bytes` (AES-256-GCM,
  `seal_with`/`open_with`, fingerprint-checked) - not `music_secret::load`, whose errors and log lines speak of
  Storytel. No new crypto code. `AppState.kid_pin_key: Option<Arc<MusicKey>>`; a failed load is logged in kid-PIN
  words and leaves reveal and the command off, never the lock. Not in any backup (`backups.rs` copies the database and
  image stores; Docker keeps `data/keys/` in its own volume); a restore elsewhere means "not readable here - set the
  Child PIN again to see it". Never a fixture: tests make a key in their temp dir.
- **Sealed form**: one text `v1:<key fingerprint>:<nonce b64>:<ciphertext b64>`, AAD `kid-pin-v1:<fingerprint>:<device
  id>`, so a value can't be moved to another phone's row. A newtype `KidPin(String)` with a redacted `Debug` and a
  transparent `Serialize` carries the digits through the code; no `tracing` field ever takes it.
- **Save** (`update_kid_lock`, unchanged refusals; one transaction with the queue rule): the same `UPDATE` sets
  `kid_pin_prev_sealed = CASE WHEN kid_pin_prev_rev IS NULL THEN kid_pin_sealed ELSE kid_pin_prev_sealed END` and
  `kid_pin_prev_rev = CASE WHEN kid_pin_prev_rev IS NULL THEN kid_pin_rev ELSE kid_pin_prev_rev END` - both keyed on
  the rev, so the PIN the phone last confirmed survives any number of saves and a legacy PIN (no sealed copy) stays "an
  earlier PIN" (prev_rev 0, prev_sealed NULL) instead of the first new one (qa-59-revision M-2) -, the new
  `kid_pin_sealed` (NULL when the key is off: flash `saved_not_kept`), `kid_pin_rev = kid_pin_rev + 1`. **Clear** NULLs
  the sealed and prev columns, bumps the rev, sets `kid_keyguard = 'own'` (there is no PIN for Android to hold; setting
  a new PIN starts in own mode) and cancels a waiting `set_kid_pin` (`cancel_reason` `kid_pin_cleared`), one
  transaction. Status intake NULLs `prev_sealed`/`prev_rev` once the phone (with the capability) reports `pin_rev` = the
  current rev.
- **"Waiting"** is one SQL fragment shared with the pop: a `set_kid_pin` row with `delivered_at IS NULL AND cancelled_at
  IS NULL`. A row that went out is never "waiting": a save after a delivery queues a new row (a new id), and the pop
  retires the older unconfirmed one when the new one goes out (`device_api.rs:385-397`). So no save is lost to a row
  dying in L12's redelivery window, and no new PIN rides an id the phone already ran (`CommandAnswers.begin`).
- **Queue rule** (one helper, `kid_lock::queue_pin_command`): insert a `set_kid_pin` row (no payload) when the keyguard
  is `android`, a sealed PIN exists, the last report has `android_keyguard_v1` and none is waiting. Run after a Child
  PIN save, after the switch goes to `android` (a stored PIN migrates the phone at once), from "Send the Child PIN
  again", and **at status intake** when such a phone (reporting the capability) needs the PIN: it reports `keyguard:
  own` (a phone updated after the switch was set, qa-59-revision L-2) or a `pin_rev` that is a number other than the
  current one - unless the newest `set_kid_pin` is in flight (delivered within `REDELIVER_WINDOW_MINUTES`, no answer:
  a report sent before the phone ran it queues no duplicate, L-2) or **answered at all** (a refusal: no loop on
  `no_token`; a success the report didn't follow, e.g. an unmanaged phone that keeps reporting its own lock: no loop
  either; the parent's button retries). `pin_rev: null` (a PIN changed on the phone) is not auto-healed by default
  (open question 4). The switch going to `own` cancels a waiting row (`cancel_reason` `keyguard_own`). It never
  expires (like `lock`).
- **Delivery**: `build_policy` reads `device_policy` first and pops last; for `set_kid_pin` it opens `kid_pin_sealed`
  from that same `SELECT *` (consistent with the `kid_lock` hash and `pin_rev` in the same answer) and fills `pin` only
  when the phone's last report has the capability (a re-enrolled row with an old launcher gets none) and the keyguard
  is still android (a delivered row offered again after the switch to own carries none; the phone answers `own lock`).
  Unreadable or no capability -> `pin` left out (the phone answers `refused: no_pin`). The answer is `no-store`, as
  every device answer without its own `Cache-Control` already is (`limits`).
- **"Deleted on the receipt"**: the row never holds the PIN, so the receipt has nothing to delete; L12 stops a received
  command from being served again, and at most 3 deliveries in 15 minutes go out without one. The PIN exists in
  plaintext only inside that one response. (A literal delete would also lose the PWA's "taken" row.)

### 2.2 Reveal behind the password

- The Child PIN group has "Show Child PIN…" (a `<details>`): one `type="password"` field (`autocomplete=
  "current-password"`), POST to the Lock page's own path `/devices/{id}/lock` with `action=reveal_kid_pin` (CSRF as
  every admin POST), so `scroll-restore.js`'s per-path record keeps the place - no anchor, no jump to the group. The
  handler checks the password and answers by **rendering** Lock Screen & PINs with the PIN in the group (not a
  redirect: the PIN never sits in a URL, session or log). `admin_auth::no_store_path` gains `["devices", _, "lock"]`
  (its test too), instead of a per-handler header.
- What it shows: the current PIN. While the phone (reporting the capability) has a `pin_rev` other than the current and
  equal to `kid_pin_prev_rev`: "New: 4821 - until the phone gets it, its PIN is still 1357"; another non-current rev:
  "New: 4821 - the phone still has an earlier PIN". A phone without the capability: the newest alone. A legacy PIN
  (saved before this release): "Not stored on this server - set the Child PIN again to see it here".
- **One re-auth helper** `admin_auth::reauth(session, admin, password) -> Ok | Wrong | Busy`: the gated Argon2
  (`passwords.verify`, `Busy` -> 429), a wrong-password counter in the session reset by a right one, and the session
  ended at the third wrong in a row (sign in again with 2FA). Used by the reveal, `change_password` and `reset_totp`
  (`auth.rs:391`, `:772`), which are the same password oracle today. Security events `kid_pin_shown` /
  `kid_pin_reveal_refused` (device id only).

### 2.3 Lock Screen & PINs, the summary, Locate, power (step 2)

- **The switch**: a row "Lock screen" in the Child PIN group, a `select` with `data-autosave` ("Vibb One's" /
  "Android's - PIN pad, fingerprint"), POST `/devices/{id}/kid-lock/keyguard`, redirect `/devices/{id}/lock#child-pin`
  (`<noscript>` Save). Disabled without a Child PIN. Security event `kid_keyguard_changed`; nudges; runs the queue rule.
  **No save on this page jumps to the top**: every form posts to an anchor in its group or to the page's own path.
- **Rows and warnings** (typed `LockNote`s from a pure `lock_card` extended with the request, the capability and the
  new fields; unit-tested per case):
  - android requested, no capability: warning "Vibb One on this phone needs an update for Android's lock".
  - android requested, phone `keyguard: own`: row "Android's lock: waiting - the next time the Child PIN is typed on
    the phone" (or "sending the PIN" while a `set_kid_pin` waits).
  - `keyguard: android`, `pin_rev: null`: warning "The phone's PIN isn't the Child PIN (changed on the phone?)" with
    "Send the Child PIN again" (POST `/devices/{id}/kid-pin/send`, redirect `#child-pin`), or "Set a new Child PIN"
    when none is stored. Also an Attention item (action level) on the summary.
  - `pin_rev` behind the current: row "The phone still uses an earlier Child PIN until it gets the new one" (+ send
    again when none waits and the last answer was a refusal).
  - `keyguard: android`, `reset_token: false`: warning "Vibb One can't change the PIN or switch back until the child
    unlocks the phone once".
  - own requested, phone still `android`: row "Switching back…"; with no token, the warning "Clear Android's PIN in
    Settings › Security while the phone is open (Parent PIN › pause)".
  - `keyguard_relocks > 0`: row "Android's lock was locked by Vibb One N times" (§3.3; a sign T isn't holding).
  - The newest `set_kid_pin`'s state as a row ("New PIN sent 14:02 · taken 14:03" / its refusal in words).
  - "On the phone: Locked · Android's lock"; the Devices list/summary lock value "On · Android's lock".
  - `Inactive::AndroidCredential`'s warning only while own is requested.
- **Privacy ⓘ in android mode** (replaces design 10's while android is chosen): "Android's own lock: the PIN also
  encrypts the phone's data, so a thief can't read it without the PIN. After a restart the phone can't be found, rung
  or wiped until the PIN is typed, and Fossify Clock's alarms may not ring until then. Android waits 30 seconds after 5
  wrong PINs and longer after more, up to a day. The Parent PIN doesn't open Android's lock: type the Child PIN (Show
  Child PIN above), then the Parent PIN in Settings. A fingerprint works if you add one in Settings while the phone is
  open (Parent PIN › pause)."
- **Locate**: `reword` gets "locked (android pin)" -> "Locked with the Child PIN (fingerprint off until it's typed)";
  the ⓘ under the actions: "After a restart, Locate, Ring and Wipe wait until the Child PIN is typed on the phone".
  `set_kid_pin`'s answers are worded on the Lock page, not in Locate's list (`LOCATE_KINDS` unchanged).
- **Power**: "Anna was turned on · 07:02 · unlocked 07:15" when `unlocked_at_ms` is more than 2 minutes after `at_ms`
  (the minutes she sat at "type the PIN to start"); else today's words.

## 3. Launcher (steps 3 and 4)

**From the prototype** (P §1): `Hybrid59` -> `lock/AndroidLock.kt`, `HybridPlan.kt` -> `lock/AndroidLockPlan.kt`
(`AndroidLockPlanTest` from `HybridPlanTest`), `KeyguardMode.HYBRID` -> `ANDROID`, log tag `AndroidLock`. **Moves** as
is: the keyguard write (`setKeyguardDisabled(false)`, features recorded-then-written, T = the screen timeout, no
fallback), `lockActivation(androidGate)`, the lock-up's LOCK alone under a locked keyguard, "Lås opp" ->
`requestDismissKeyguard` with the screen-off guard on its answer, the hidden keypad/"Foreldrekode", the rollback's
clear. **Moves with the prototype QA's fixes** (step 3's brief names them): the re-front hold also needs `deviceSecure`
(P59-3); the camera lock stays engaged unless ANDROID's read-back holds B2's 0x2 while managed, and it re-syncs when
the face turns off as well as on (P59-4, P59-8); `unlockStep`'s live `deviceSecure` is pinned (P59-8); what
`configure` derives from `kid_lock.keyguard` is pinned by a JVM test (`"android"`, `"own"`, missing, junk -> face;
P59-5); the test task gets `inputs.dir("src/release")`/`("src/debug")` for the cover's remaining text pins (P59-7);
`KeyguardCover`'s "the keyguard stays disabled" comment on an ANDROID `coverFailed` is corrected (P59 nit). **Changes**:
the switch comes from the policy (`HybridDebug`, its receiver and their `DebugHookAbsentTest` entries go);
`noteRecentsGuard`'s `guarded=` verdict goes - one log line of the lock-task flags with `isKeyguardLocked`/
`isDeviceLocked`, no verdict (P59-1: lock task doesn't hold Recents; the keyguard does); the resume's "unlocked ->
leave" becomes §3.3's check (P59-6); the process start (§3.1); the PIN command; the token rule; FACE and IRIS off; the
lock-screen line.

### 3.1 The mode

- `configure` (every apply) reads `kid_lock.keyguard` and `pin_rev`; `PinLockStore.saveConfig` commits
  `keyguard_android` and `pin_rev` with the hash and `active` (CE `pin_lock_state`), so the process start knows the mode
  and every rev names the hash it came with. `face = android && managed && kidLock != null && deviceSecure` (P's
  `hybridFace`). Unmanaged with Android's PIN set: the PIN stays (a normal phone; the parent knows it).
- **Process start in android mode with the face** (`PinLockRuntime.decide` and the `ProcessStart` step, so 16c's Home
  gate agrees): the boot's **first** CE start (BOOT_COUNT against CE `pin_lock_state` `android_boot_count`, the same
  test `unlocked_at_ms` uses) is UNLOCKED - CE unlocked means the PIN was typed - so no glance and no second PIN after
  a restart; the glance comes at the next screen-off. Every later start (a crash, an update) is LOCKED, as in own mode,
  and §3.3 decides. `isKeyguardLocked` is never read for this (it can still read true while the boot's bouncer goes).
- **Crash guard in android mode**: a trip calls `dpm.lockNow()` (Android's PIN is then the gate with SystemUI's face)
  instead of leaving the phone open as own mode's S-L2 does.

### 3.2 The keyguard

`ANDROID_KEYGUARD_FEATURES` = WIDGETS_ALL | SECURE_NOTIFICATIONS | TRUST_AGENTS | FACE | IRIS | SHORTCUTS_ALL (plus B2's
SECURE_CAMERA): only fingerprint among the biometrics (default, open question 1). `LOCK_TASK_FEATURE_KEYGUARD` stays
forced (A §1; pinned). `KeyguardCover` stays the only writer; `ANDROID` never writes `setKeyguardDisabled(true)` and
never dismisses (the cover's dismissal runs only in COVER).

**The lock-screen line** (answer 1; default, open question 2): `setDeviceOwnerLockScreenInfo` with nb "Skriv koden for
å starte telefonen", en "Type your PIN to start the phone", written in android mode only at `ACTION_SHUTDOWN` (BootCover's
one shutdown receiver, after the cover's writes, its own try), and cleared (`null`) at the boot's first CE start. So the
restart words show exactly on the face before the first unlock; AFU the gaps show SystemUI's face without a line. Why
"none" and not a neutral second line: one string, one write each way, nothing on the face that isn't true. A hard crash
or a pulled battery skips the shutdown write; Android's own "PIN required after restart" still shows then. Cleared in
own mode. Whether SystemUI shows the info before the first unlock is a device check (step 10).

### 3.3 The face, and the keyguard really locked under it

The Recents review and P59-1: only a **locked** keyguard refuses Recents (`startExistingRecents`, Quickstep's
`DeviceLockedInputConsumer`); lock task's missing OVERVIEW doesn't. So whenever our glance is up in android mode,
Android must be locked, and our glance leaves only on Android's own verdict. One pure rule,
`faceStep(trigger, deviceSecure, androidLocked, presentSinceLocked)` in `AndroidLockPlan.kt`. `androidLocked` =
`isDeviceLocked || isKeyguardLocked` (unreadable = locked); `presentSinceLocked` (memory) = a `USER_PRESENT` (a
protected broadcast Android sends when its keyguard is done) since our last LOCKED edge, reset at that edge:

| Trigger | Android locked | Not locked, `USER_PRESENT` seen | Not locked, none |
|---|---|---|---|
| `SLEEP_CHECK` (1 s after each screen-off while LOCKED, dark) | stay | - | **RELOCK** |
| `RESUME` (the glance's resume, screen on) | stay | LEAVE (fingerprint from dark) | wait `ANDROID_ANSWER_WAIT_MS` (1 s) for the broadcast, then **RELOCK** |
| `UNLOCK_TAP` ("Lås opp", a row) | ASK_ANDROID | LEAVE | the same wait, then **RELOCK** |
| `USER_PRESENT` | (keep the mark) | LEAVE | - |
| PIN pad `onDismissSucceeded` | LEAVE | LEAVE | LEAVE |

- `deviceSecure == false` at any trigger -> OWN_KEYPAD (our keypad and hash - never past a PIN).
- **RELOCK** = `dpm.lockNow()`, logged ("Android's keyguard didn't lock under the glance - locked it"), counted in
  `keyguard_relocks`. It happens when T didn't take (Android's 5 s delay), SystemUI misbehaved, a wake raced the sleep's
  lock, or a crash restart found Android open; `lockNow` turns the screen off and costs the fingerprint until the next
  PIN (`STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW`) - accepted on these paths. A not-locked read alone never opens the
  phone (the prototype left on it: P59-6).
- LEAVE is the prototype's `unlock(offs)` (a row's target opens; Home below). Our mode goes UNLOCKED in android mode
  only through LEAVE or the boot's first CE start.
- The log line at each resume: lock-task flags, `isKeyguardLocked`, `isDeviceLocked`, `deviceSecure` - no verdict.

### 3.4 Migration (the first Android PIN)

Two ways, the same end state: `keyguard_android = true` committed **first** (write-ahead, so a death after the reset
can't hide the edge §3.8 needs), the token active, `resetPasswordWithToken(pin, token, 0)`, then `android_pin_rev`
committed - the rev **of the hash the PIN was checked against** - then an apply -> ANDROID:
- **The command** (§3.5), when the server holds the PIN - immediate; the rev from the same answer as its hash.
- **Our keypad** (P's `migrateIfDue`), for a PIN saved before this release: the next correct Child PIN typed on our
  keypad (our lock is the gate until then) becomes Android's, with the rev `saveConfig` committed beside the applied
  hash. A newer policy cached but not yet applied can't lend its rev (QA Q59-5). The kid sees one ordinary unlock.

### 3.5 The PIN command

`dispatchPendingCommand` `"set_kid_pin"` (after the receipt, as every command; it runs before this sync's apply) ->
`AndroidLock.applyPinCommand(freshPolicy.kidLock, pin)`, pure step `pinCommandStep`:
- the fresh policy's keyguard isn't android -> `own lock` (success; the hash in the policy is the gate);
- no `pin` -> `refused: no_pin`; the PIN doesn't verify against the **fresh** `kid_lock`'s hash and salt ->
  `refused: mismatch`. The check is a new `PinLockRuntime.verifyCommandPin(kidLock, pin)`: the fresh hash (not the
  applied `config`, which still holds the old PIN), no backoff, no failure counted - and still the only `PinHash.verify(`
  caller for the kid's PIN (`ParentPinCallersTest`);
- no active token -> `refused: no_token` (the token is then set for escrow, §3.6);
- else `keyguard_android = true` committed, `WindowRelock.resetCredential` (the one `resetPasswordWithToken` caller,
  with the one-shot "ours" flag of §3.7) -> `pin set`, `android_pin_rev` = the fresh rev committed, an apply. Works
  with the keyguard up (AFU); before the first unlock no command runs at all.
- The PIN is a local `String`, never stored, logged, cached or put in an answer; the token bytes are zeroed (P).

### 3.6 The reset token, kept active

In android mode `ensureResetToken` replaces `resetTokenNeeded` (`WindowRelockPlan.kt:127-128`; r10 §6's revisit), run at
every apply: none stored (r10's `Unreadable` counts as none) -> set one; stored, not active and **no credential** ->
set it again (it activates at once); stored, not active, credential set -> wait: it was escrowed and activates at the
kid's next unlock (AOSP activates escrow tokens on a verified credential - device check). `reset_token` in the report.
The migration sets it before the credential exists, so the normal path never needs escrow. Read only through
`WindowRelockStore.token()` (r10 seals it there later; nothing here changes then). Own mode keeps 24b's rule.

### 3.7 `onPasswordChanged`

Every own `resetPasswordWithToken` (migration, command, rollback) sets a one-shot "expect our change" flag first;
`onPasswordChanged` consumes it, or - with no flag - clears `android_pin_rev` (the report says `pin_rev: null`, the PWA
warns and offers the command). A credential removed on the phone turns the face off: our keypad is the gate again and
the next correct PIN migrates again. Design 24b's relock step 1 is skipped while **android is requested** (not "face
on"), so a window opened before the migration can't remove the migrated PIN at its relock (P59 nit); a kid's own PIN
set in a window is the warning above.

### 3.8 Rollback

Only on the edge out of android mode, and only on a policy that carries the parent's choice - a fresh one or
`CachedPolicy.Ok`, never a `Fallback` (`LastEnforcedPlan` has no `kid_lock`) or `Apply(null)`: the stored
`keyguard_android` is true and the policy says own, or its `kid_lock` is null. An own-mode phone's own credential
(today's `android_credential` case) is never touched. With Android's PIN set and the token active:
`resetPasswordWithToken(null)` (with the "ours" flag), then the apply writes DISABLED; `keyguard_android` is committed
false only once no credential is left (a failed clear retries at the next apply). No active token: Android's PIN stays
the gate (`inactive: android_credential`, own mode's warning and runbook) and `ensureResetToken` keeps running, so the
escrowed token finishes the rollback after the kid's next unlock. While a Child PIN is set the phone always has one of
the two locks; `kid_lock` null on a fresh policy opens it without a code, as today.

### 3.9 The rest under android mode

- **Notice wake** (r7 §3): unchanged by default - `lockNow()` after its 15 s, which makes the next unlock need the PIN,
  not the fingerprint (open question 3). Own mode unchanged.
- **Remote lock**: our LOCKED + `dpm.lockNow()`, answer `locked (android pin)`.
- **Re-front, camera lock, SOS guess**: off while Android is locked and secure (P, with P59-3/P59-4). Lock task while
  LOCKED is unchanged; **the keyguard holds Recents**, lock task's flags hold at most the shade over SystemUI's own face
  - a device check (step 6), not a guarantee.
- **VoIP**: design 17/17b stays as built. Security no longer depends on it: a call screen that isn't `showWhenLocked`
  lands behind the keyguard; the ring card has no "Foreldrekode".
- **`unlocked_at_ms`**: `PowerEvents` stamps the boot's start record at the boot's first CE start (§3.1's test) when it
  has none (DE record, `commit()`).
- **Reports**: `lock_state` per §1.

## 4. Why there is no path past the PIN

- Our glance leaves only on Android's own verdict: the PIN pad's `onDismissSucceeded`, or a `USER_PRESENT` since our
  LOCKED edge (the boot's first CE start is the third: CE can't unlock without the PIN). A not-locked read without one
  locks Android (§3.3). A missing credential falls back to our keypad and hash, never to an open phone.
- Anything that isn't `showWhenLocked` is invisible while the keyguard is locked (A §4.1): Home, chats, Recents,
  notification taps, a call app's chat arrow, our own crash (whose restart starts LOCKED, §3.1).
- No fallback policy rolls the PIN back (§3.8); the crash guard locks rather than opens (§3.1).
- The PIN: sealed at rest on the server with a key outside the database and the repo, in plaintext only in the one
  `set_kid_pin` response over tsnet + bearer to a phone with the capability, never cached or logged on the phone
  (redacted `toString`, the cache strip, a source scan), checked against the fresh policy's hash before use. A
  device-token holder gains nothing new: the policy already carries the 4-6 digit PIN's PBKDF2 hash and salt, which an
  offline search breaks in seconds.
- Reveal needs the parent's password inside a 2FA session; three wrong end the session (one helper for every re-auth
  form).

## 5. Design 57, today's lock code, r10

- **Design 57's branch** (`worktree-agent-a689b601f638554b8`) is not merged; it goes at phase D with its Lows. Of
  PLAN's "VoIP over the lock, small launcher fixes": (1) "Tilbake til samtalen" when the reopen fails still applies in
  both modes (in android mode its send hits the PIN pad when the call screen isn't `showWhenLocked` - safe); (2) the
  SOS guess on a VoIP wake and (3) the BlockedAppActivity flash are own-mode only - moot after phase D; (4)
  READ_PHONE_STATE for allowed apps is independent and stays. The Recents review's own-mode options (re-front at 0 ms,
  our thumbnails) are own-mode only too: worth doing only if phase D slips.
- **Today's lock code stays** in the RC, every own-mode path unchanged; phase D deletes A §6's list (cover, lock-up,
  re-front, SOS guess, keypad/backoff, camera lock, most VoIP, 16c/16e, the switch itself) once the last phone runs
  android mode for a release.
- **r10** (after this): reads the token through the same `WindowRelockStore.token()`; `cacheablePolicy` absorbs the
  pin strip; no plaintext PIN is ever kept on the phone (answer 2 is (b)), so r10 §6's extra `SealedPrefs` field isn't
  needed. r10's QA checks §3.6 with a sealed token that reads `Unreadable` (none -> a new token).

## 6. Implementation steps

1. **The contract + server storage and command** (one commit, both dirs): migration, the key, sealing on save and
   `prev`/`rev`, `kid_lock.keyguard`/`pin_rev`, the waiting fragment, the queue rule (intake included) and delivery,
   `lock_state` fields and their intake, `power on.unlocked_at_ms` (+ both `power_report.json`), the key pins; launcher
   DTOs, `PendingCommand.toString`, the cache strip, `PolicyResponseCompatTest`, `PowerEventsTest`'s copy. No launcher
   behaviour; no capability.
2. **Server PWA** (after 1; parallel with 3): the switch, reveal and the re-auth helper, `no_store_path`, send again,
   the lock card's notes, Attention, Locate words, the power words, the privacy text.
3. **Launcher, the hybrid for real** (after 1): §3.1-3.4, 3.7, 3.8, §3.9's re-front/camera/VoIP and the lock-screen
   line; the debug switch removed; P59-1, -3, -4, -5, -6, -7, -8 and the nits as listed in §3's intro.
4. **Launcher, the command and the reports** (after 3): §3.5, 3.6, remote lock, `lock_state`, `unlocked_at_ms`, and
   the capability last. Then the RC tags: the server first, then `launcher-vX.Y.0-rc.1` (a prerelease: the catalog
   row's `include_prereleases` is off, so no phone gets it by itself).

## 7. Tests (behaviour; the matrix only where a table is the rule)

- **Server**: seal/open round trip and the AAD binding (another device id, another key -> unreadable); save keeps the
  confirmed prev across two saves (A, B, C -> prev A), bumps rev, clear empties prev and sets own; reveal - wrong
  password renders no PIN, right one renders it, `no_store_path` covers the page, three wrong end the session (and the
  same in `change_password`/`reset_totp`), no PIN in a URL or the security log; the waiting fragment (a delivered row
  doesn't block a new one); the queue rule (each condition off -> no row; intake heals a stale number, not `null`, not
  after a refusal; the switch to own cancels); delivery fills `pin` only for `set_kid_pin`, from the same row as the
  hash, never after the receipt, never without the capability in the last report; the `kid_lock` and
  `pending_command` key pins; `sanitize_lock_state` with the new fields; prev cleared when the report is current;
  `lock_card` per case of §2.3; the power words with and without `unlocked_at_ms`; the save redirects keep their anchors.
- **Launcher** (`AndroidLockPlanTest` and friends): `faceStep`'s table - no LEAVE without the pad's success or a
  `USER_PRESENT` since the edge, RELOCK after the wait; the process start (first CE start of a boot UNLOCKED, later
  starts LOCKED) and the crash guard's `lockNow`; `pinCommandStep` per answer, and a fresh hash that differs from the
  applied one gives `pin set` through `verifyCommandPin`, which counts no failure; the token rule (none -> set; not
  active without a credential -> set; not active with one -> wait); the keypad migration's rev is the applied
  config's; `onPasswordChanged` with and without the flag; rollback (clear / no token / never on a Fallback or `null`,
  `keyguard_android` written before the reset); `configure`'s derivation of the face from `kid_lock.keyguard` (P59-5);
  the keyguard features (FACE and IRIS in, fingerprint not, KEYGUARD lock-task bit forced, 0x2 checked in the
  read-back) and `ANDROID` never writing `setKeyguardDisabled(true)`; own mode unchanged (the existing lock tests pass
  untouched); `PendingCommand.toString` and the cached policy hold no PIN; by source: `resetPasswordWithToken` only in
  `WindowRelock`, `requestDismissKeyguard` only in the glance's ask and the cover, `unlockStep`'s live `deviceSecure`,
  the camera re-sync on both edges (P59-8), no log call names the PIN, the debug switch gone; `PowerEventsTest` with
  `unlocked_at_ms`.
- Code-QA mutations: LEAVE without `USER_PRESENT`/success; OWN_KEYPAD skipped when not secure; the command checked
  against the applied hash; a rollback on a Fallback; the PIN cached; `pin` delivered without the capability; reveal
  without the password check; prev overwritten on a second save; `setKeyguardDisabled(true)` in ANDROID.

## 8. The user's emulator test list for the RC

Setup: the server RC on your host, the emulator enrolled on the **previous** launcher release, a Child PIN (4 digits,
below `1357`) and a Parent PIN set **before** the server update, kiosk on, Element X and WhatsApp allowed and signed in,
notification access, Advanced › Restrictions "Block USB debugging" off, no Android screen lock, gesture navigation.

```sh
P=me.vibb.launcher; PIN=1357
adb logcat -c; adb logcat -v time -s AndroidLock PinLock PinLockActivity KeyguardCover LockTaskChrome CameraLock \
  MdmDeviceAdminReceiver WindowRelock MdmSyncWorker > rc59.log &
kg() { adb shell dumpsys trust | grep -io "devicelocked=[01]"; adb shell dumpsys activity activities | grep -A6 KeyguardController | head -7; }
shot() { adb emu screenrecord screenshot "$PWD/$1.png"; }   # the glance is FLAG_SECURE; the console captures it
```

1. **Baseline on today's lock, then old launcher + new server.** Before anything else, on the previous launcher: open a
   WhatsApp chat, lock, wake, and try step 5's Recents gestures and keys on today's lock. **Send** whether Recents and
   a chat thumbnail showed (with `shot`, `kg`) - step 5 counts only if the leak shows here. Then update the server only,
   set Lock screen = Android's in the PWA: expect "needs an update" and the lock unchanged. **Send** the group's
   screenshot. Set it back to Vibb One's, then install the RC launcher (`adb install -r kids-launcher-mdm.apk`, after
   `apksigner verify --print-certs`).
2. **Reveal, a legacy PIN.** Show Child PIN with a wrong password, then the right one. Expect "refused", then "Not
   stored on this server", and the page where it was. **Send** what showed and Settings › Security's two events.
3. **The switch and the keypad migration.** Lock screen = Android's (the page must stay at the group). Lock, wake,
   "Lås opp": **our** keypad; type `$PIN`. Expect `migration ... true` in the log, `adb shell locksettings
   get-disabled` -> `false`, the PWA "On the phone · Android's lock". **Send** that, and `adb shell dumpsys
   device_policy | grep -iE "disabledKeyguardFeatures|maximumTimeToLock|lockScreenInfo"`.
4. **Every wake is the glance over a locked keyguard.** 20 wakes (`adb shell input keyevent 26` twice) from Home, from
   WhatsApp, after a timeout. Expect the glance every time, never a frame of an app or Home. After three of them run
   `kg`: `deviceLocked=1`, keyguard showing and occluded. Then a quick off/on (`adb shell "input keyevent 26 && input
   keyevent 26"`, under 300 ms) five times: the glance over a locked keyguard, or the screen going off once with
   `didn't lock` in the log (then the PIN). **Send** the `kg` outputs, `grep -c "didn't lock" rc59.log`, and any wake
   that showed something else.
5. **Recents and thumbnails.** As in step 1's baseline: open a WhatsApp chat, lock, wake. On the glance: a swipe up
   (`adb shell input swipe 540 2390 540 1200 150` - your screen's numbers), a held swipe (`... 1500`), by hand too; in
   3-button navigation (`adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton`) the square
   and `adb shell input keyevent KEYCODE_APP_SWITCH`; back to gestural; repeat with the kiosk off. Expect no Recents
   and no thumbnail. A square tap raising Android's PIN pad, and Recents only after the PIN, is expected. **Send**
   `shot` and `kg` after each, and `adb shell dumpsys activity activities | grep topResumedActivity`.
6. **Shade and Quick Settings.** On the glance: a swipe down from the top, `adb shell cmd statusbar
   expand-notifications`, `... expand-settings`. Then on SystemUI's own lock face (`adb shell am start -n
   $P/com.kidslauncher.mdm.ui.HomeActivity` while locked) the same. Expect nothing opens; on SystemUI's face no
   notification, no media player, no Home, and no line. **Send** screenshots, `kg`, and any tile that acted without the
   PIN (Airplane above all).
7. **"Lås opp", a row, wrong PINs.** "Lås opp" -> Android's pad -> `adb shell input text $PIN; adb shell input keyevent
   66` -> Home. A message row -> PIN -> the chat. Back on the pad -> the glance. Then `0000` + Enter five times: Android's
   wait; "Nødsamtale" still works. **Send** the pad's screenshot, each wait's length, whether the look is acceptable.
8. **A new PIN from the PWA (the command).** Save Child PIN `2468`. Show Child PIN: "New: 2468 - until the phone gets it,
   its PIN is still 1357" until the phone reports, then only 2468. The group's row: sent, then taken. Lock, wake:
   `2468` opens, `1357` doesn't. Repeat once with the screen off, once during step 7's wait, and once with two saves
   in a row while the emulator is offline (`adb shell svc wifi disable; svc data disable`): Show must name the PIN the
   phone really has. **Send** the rows, the log's `PIN command:` lines, and whether the new PIN worked at once during
   the wait.
9. **VoIP over the lock.** Lock, screen off; call the kid in Element X from another account; answer; try Back, the
   minimise arrow, any chat button; hang up. Then WhatsApp the same. Expect the call on, never a chat list or chat - at
   most the PIN pad. **Send** for each app what showed at the ring, after Answer and after hang-up (`shot voip-*`, `kg`).
10. **After a reboot.** `adb shell svc power reboot` (it sends `ACTION_SHUTDOWN`; plain `adb reboot` doesn't); don't type the PIN. Expect Android's
    lock face with "Skriv koden for å starte telefonen" and Android's "PIN required after restart". Call from a number
    not in the phone book (`adb emu gsm call +4799999999`): rejected, nothing rings. Call from an allowed contact: our
    call screen over the lock; `adb emu gsm cancel <number>`. Wait 3 minutes, then the PIN: Home at once, no glance, no
    second PIN. **Send** a screenshot of the first face, `adb logcat -b all -d | grep -iE
    "KidCallScreening|InCallController"`, and the PWA's "turned on" line (expect "· unlocked HH:MM").
11. **Fingerprint.** Pause from the PWA; Settings › Security › Fingerprint (the PIN), `adb emu finger touch 1` until
    enrolled; "Lås igjen" on Home. `locksettings get-disabled` stays `false`. Then: screen off + touch -> Home; screen
    off and a touch within 1 s of it -> Home, no screen-off, no `didn't lock`; glance up + touch -> nothing or an unlock
    (say which); on the pad + touch -> unlock; after Locate › Lock + touch on the pad -> refused. **The delay** (S3-3):
    for five touches from dark, the time from `the glance resumed` to `USER_PRESENT` in the `AndroidLock` lines (expect
    well under 3 s, `ANDROID_UNLOCK_WAIT_MS`; a `didn't lock` after a fingerprint means it came later). **Send** each
    result, the five delays and the Lock answer on the Locate page.
12. **The kid changes the PIN.** In a pause, Settings › Screen lock -> PIN `9999`, then "Lås igjen". Expect the PWA's
    warning "isn't the Child PIN" after the next report; "Send the Child PIN again" -> the phone's PIN is the Child PIN.
    **Send** the warning, the log's `MdmDeviceAdminReceiver` lines and whether `9999` still opens it afterwards.
13. **Camera, SOS, notice wake, a crash.** With the double-press gesture on (`adb shell settings put secure
    camera_double_tap_power_gesture_disabled 0`), `adb shell input keyevent 26 26` on the glance and dark: no camera.
    Five presses: the SOS countdown over the glance; cancel. With "Wake for a notification" on, a WhatsApp message while
    dark: the glance lights and goes off after about 15 s. Then `adb shell am crash $P` with the glance up, and once on
    Home: expect the glance back and the PIN asked. **Send** what differed, and the `CameraLock` lines.
14. **Rollback and back.** Lock screen = Vibb One's. Expect `rollback ... cleared`, `get-disabled` -> `true`, our keypad
    after "Lås opp". Then Android's again: this time the stored PIN migrates at once (no keypad step). **Send** the log
    lines and the group's rows.
15. **Remove the Child PIN in android mode.** Expect the phone to open without a code, `get-disabled` -> `true`, and the
    switch back at Vibb One's. Set the PIN again and switch to Android's for any later run.

At the end `kill %1`; **send** `rc59.log`, the screenshots and one line per step: as expected / what differed.

## 9. Still device checks (the RC run answers them)

SystemUI's keyguard honouring lock task's flags for the shade (6); the media player with SECURE_NOTIFICATIONS (6);
the line before the first unlock (10); escrow activation of a token at the kid's unlock (only seen if a token is lost;
not on the list); a token reset clearing Android's wait (8); fingerprint while the glance occludes (11); which call
screens are `showWhenLocked` (9); fingerprints kept across `resetPasswordWithToken` to a new PIN (8 after 11 - say if
the fingerprint stopped working); whether a quick off/on wakes before the keyguard locks (4). Before phase C reaches
the kid's phone, on the Jelly Star: its BFU call screening (A §2.3), and an overnight reboot with a 07:00 Fossify alarm
- does it ring before the PIN is typed (open question 5)?

## 10. Open questions for the user (each written in as the default)

1. **Face unlock off**, fingerprint only (answer 4 named fingerprint; a cheap 2D face unlock is easy to fool). A
   per-phone fingerprint switch is a later proposal.
2. **The lock-screen line** only after a restart (§3.2: written at shutdown, cleared at the first CE start; none
   otherwise). Alternative: one neutral line always ("Skriv koden for å låse opp").
3. **The notice wake** keeps today's 15 s and `lockNow`: the screen goes dark quickly, but the next unlock then needs
   the PIN, not the fingerprint. Alternative: no `lockNow` in android mode - the fingerprint keeps working, but the
   screen stays on for the phone's timeout (up to 10 min) - or `lockNow` only when that timeout is over 30 s.
4. **A PIN the kid set on the phone** is healed by the parent's "Send the Child PIN again", not automatically.
   Alternative: the server re-sends the Child PIN on its own whenever the phone reports `pin_rev: null`.
5. **Fossify Clock** stays for the RC; the Jelly Star's overnight-reboot alarm check (§9) decides before phase C.

## QA fixes (`qa-59-design.md`, and the prototype's `qa-59-prototype-code.md` where it points)

- Q59-1: the command checks the fresh policy's hash through `PinLockRuntime.verifyCommandPin`, no backoff (§3.5, §7).
- Q59-2: the boot's first CE start is UNLOCKED by the boot-count rule, later starts LOCKED; the crash guard calls
  `lockNow` in android mode (§3.1, §7, test 13).
- Q59-3: `seenLocked` dropped; LEAVE only on the pad's success or `USER_PRESENT` since the edge, RELOCK after a 1 s wait
  (§3.3, §4, §7); covers P59-6.
- Q59-4: rollback only on a fresh or `Ok` cached policy; `keyguard_android` written before the reset; the token kept
  running while a rollback waits (§3.4, §3.8, §7).
- Q59-5: `saveConfig` commits `pin_rev` with the hash; each migration commits the rev of the hash it checked (§3.1,
  §3.4).
- Q59-6: prev kept with `COALESCE` plus `kid_pin_prev_rev`; "earlier PIN" otherwise (§2.1, §2.2, test 8).
- Q59-7: "waiting" = never delivered, one fragment shared with the pop; intake heals a stale rev; `pin` only with the
  capability in the last report (§2.1, §7; `null` is open question 4).
- Q59-8 (P59-1, P59-2): the `guarded=` verdict dropped, the flags logged with the keyguard state; §3.9 corrected; test
  1's baseline on today's lock, `kg` in each Send, the expected square tap (§3, §3.9, §8).
- Q59-9: P59-3, -4, -5, -7, -8 and the nits named in §3's intro and step 3; the 24b skip is "android requested" (§3.7).
- Q59-10: kept today's `lockNow` as the default; the trade is open question 3 (§3.9, §10).
- Q59-11: the reveal posts to `/devices/{id}/lock` (scroll-restore keeps the place), `no_store_path` lists it, one
  re-auth helper for the reveal, `change_password` and `reset_totp` (§2.2, §7).
- Q59-12: no credential and an inactive token -> set it again; escrow only with a credential; `Unreadable` = none (§3.6).
- Q59-13: the capability lands in step 4 with the handler (§1, §6).
- Q59-14: quick off/on, a fingerprint within 1 s, `am crash` with the glance and on Home, the extra logcat tags (§8).
- N1: the key via `keys::load_or_create` + `MusicKey::from_bytes`, logged in kid-PIN words (§2.1). N2: `tests/step10.rs`
  and `pending_command`'s key pins named (§1, §7). N3: an older launcher told by the capability (§1, §2.2). N4: the
  one-shot "ours" flag (§3.7); clearing the Child PIN sets the switch back to own (§2.1, test 15).
- Open questions: QA's recommendations taken as defaults (§10); the line per its recommendation 2, "none" in the gaps.

## Prototype run on the emulator (2026-10-22, `docs/testing/2026-10-22-hybrid-prototype-run.md`)

The prototype (46cf0fa5) on `handy36`: nothing got past the PIN and no app content showed while locked; Recents was
refused in every way tried (kiosk on and off, Android's lock, BFU), while today's lock reproduced the leak. Two bugs
the launcher steps must fix:
- **`USER_PRESENT` never reaches us.** The screen receiver is registered not-exported (`PinLockRuntime.kt:455-464`),
  and `USER_PRESENT` is sent by SystemUI, so it is dropped (the broadcast log shows it). §3's LEAVE on `USER_PRESENT`
  depends on it: register that action exported (it is a protected broadcast, so no app can forge it - verify in AOSP's
  `protected-broadcast` list), or use a `KeyguardManager` callback; pin it with a test, and the emulator list checks
  it (an unlock through Android's own lock screen must leave the glance at once).
- **"Lås opp" once opened an empty notification shade instead of the PIN pad** (in a parent pause, tapped ~5 s before
  the pause reached the phone; no `requestDismissKeyguard` answer; then 30 s of dead taps, `PinLockActivity.kt:276`,
  `HybridPlan.kt:85`). Nothing leaked. The step must make a missing answer recover quickly (a short timeout, then a
  retry or our state re-read) and never leave dead taps for 30 s.
Also seen: after the first unlock of a boot, the stock Pixel launcher shows ~3 s before our Home, then a 1.4 s glance
flash; and `handy36`'s data partition is not wiped at boot (it kept an old device owner) - start it with `-wipe-data`.

## Step 1 as built (2026-10-22, `design-59-step1`)

Server 0.37.0 (migration `0085_kid_keyguard.sql`) and the launcher's DTOs, in one commit; notes in
`docs/notes/server/hardening-and-pins.md`, "Android's lock screen". From the revision check (`qa-59-revision-check.md`):

- **M-2**: both prev columns keyed on `kid_pin_prev_rev IS NULL` (§2.1);
  `kid_keyguard::saves_seal_the_pin_and_keep_the_one_the_phone_confirmed` runs legacy, B, C (prev stays rev 0 with no
  sealed copy) and then two saves from a confirmed sealed PIN (prev kept).
- **L-2**: the intake queues for a phone reporting `keyguard: own` while the server says android, and never while the
  newest row is in flight. **Deviation**: it also never queues once the newest row has *any* answer, not only a
  refusal - with L-2's own-reported case, a success the report doesn't follow (an unmanaged phone in android mode
  reports its own lock after `pin set`: §3.1's face needs `managed`) would otherwise queue a row at every report.
- **N-B**: §1's table reworded (no row without the capability); clearing the Child PIN cancels a waiting row.
- **N-C**: the pair `keyguard: android` + `inactive: android_credential` during a token-less rollback (§1); step 1
  keeps both fields typed, step 2's `lock_card` words the pair.
- **M-1**: the contract names no launcher flag; step 3 takes `keyguard_requested`/`keyguard_android` from the revision
  check. The reveal (with its new POST route on `/devices/{id}/lock`) is step 2.
- **Also**: the policy handler sets no `Cache-Control` of its own - every device answer without one is `no-store`
  already (`limits`); the test pins it on the answer with the PIN. A popped `set_kid_pin` carries the PIN only while
  the keyguard is still android. The launcher strips the PIN for the cache (`cacheablePolicy`, in the pure
  `PolicyGate.kt`) and from the policy applied after the command's dispatch, so no policy held in memory keeps it past
  the dispatch. The launcher's `LockStateReport` fields and the stamping of `PowerOn.unlockedAtMs` stay step 4 (the
  DTO field exists; nothing sets it yet).
- **Code QA fixes** (`qa-59-step1-code.md`): the pop hands a `set_kid_pin` out only while the stored `kid_pin_rev` is
  the one its answer read (S59-1: a save racing a poll left the older PIN going out, answered, and the intake unable
  to heal); the queue rule and `last_capabilities` count only rows and reports since `devices.enrolled_at` (S59-2,
  S59-3); a row used up by a later pop is not in flight (N-2); a first PIN without the key flashes
  `saved_safe_boot_not_kept` (N-1).

## Step 2 as built (2026-10-22, `design-59-step2`)

Server only (§2.2, §2.3); notes in `docs/notes/server/hardening-and-pins.md` ("Android's lock screen", step 2) and
`pwa.md` ("Lock Screen & PINs", "The rest of Settings"). No contract change: the policy, the command, the status and
`power_report.json` are step 1's. Deviations and choices:

- **The switch** is the Child PIN group's "Lock" row (not "Lock screen": the page's next group has that name), options
  "Vibb One's lock" / "Android's lock", the sub-line "Test phase: Android's lock will replace Vibb One's" (the user,
  2026-10-22: Android's lock is the destination). It returns to its row, `#lock-keyguard`, not `#child-pin`: the
  warnings that come in above the group with Android's lock (e.g. "Phone's PIN isn't the Child PIN") moved the row 87 px
  with the group anchor; with the row's own anchor `scroll-restore.js` keeps it at 0 px (browser check).
- **The reveal's password field** is `autocomplete="off"`, not §2.2's `current-password` (the step's brief: no
  autofill). Browsers may still offer a saved password there; the field never holds the PIN, which is shown as text.
- **The re-auth helper** (`admin_auth::reauth`, and `record_reauth` for the two forms whose check is
  `PasswordGate::change`) covers Account's password form (`update_account_password`) besides `change_password` and
  `reset_totp`: four forms, one count. The third wrong in a row flushes the session, logs
  `reauth_signed_out` and answers `/login`. Since the code QA's fix round the count is the account's
  (`admin_users.reauth_wrong`, migration 0086, one `UPDATE` per answer) and the third moves the session epoch, which
  ends every session of the account (S2P-1: a count in the session could be raced and revived).
- **"Send the Child PIN again"** is offered whenever Android's lock is chosen, a copy is kept, the phone has the
  capability and doesn't run the current PIN (`keyguard: own`, `pin_rev` null or another), and nothing is waiting or in
  flight - not only after a refusal (§2.3): a success the phone's report didn't follow and an unconfirmed delivery
  retry the same way, and the intake's "any answer" rule (step 1, D1) leaves exactly those to this button.
- **The command row** shows only while Android's lock is chosen (a cancelled or expired row says nothing). The
  summary's Activity, which lists every command, names it "Child PIN to the phone" and words its answers with the same
  `kid_lock::PinAnswer` (§2.3 kept `LOCATE_KINDS` unchanged; Locate's list never shows it).
- **The lock-off alarm** (`kid_lock::lock_off`) now reads `lock_card`'s notes, so the N-C pair (Android's PIN still the
  gate while a rollback waits) raises no "Lock is off on the phone". §2.3's "`Inactive::AndroidCredential`'s warning
  only while own is requested" is narrowed by the fix round (S2P-2): no warning only while the phone runs Android's
  lock; a phone on Vibb One's lock with an Android PIN keeps the warning and the alarm whichever lock is chosen; "Vibb One can't change Android's PIN" shows only
  while Android's lock is chosen (on the way back it is "Android's PIN is still on").
- **The summary's lock value** follows the parent's choice ("PIN on · Android's lock"), like its other values; the
  Devices list has no lock value. "On the phone" carries what the phone reports ("Locked · Android's lock · 10:59").
- **Power**: "· unlocked 07:15" is added to a restart's start as well as a turn-on (both sit at "type the PIN to start").
- **Locate**'s ⓘ says "Erase" (the page's word for wipe).

Browser check (Playwright, 390x844, light and dark, JS on and off; `docs/testing/2026-10-22-pwa-identity-pass.md`'s
setup): the switch, Show Child PIN wrong and right, Send again and the switch back each kept the control (or, for Send
again, whose row goes, the group's header) at the same screen position, except the switch back to Vibb One's lock at the
top of the page, where the removed warning leaves nothing to scroll (the page can't scroll above 0). Every text node
at or above WCAG AA in both schemes; no horizontal scroll; the third wrong password landed on `/login`.

**Code QA fix round** (`docs/review/qa-59-step2-code.md`): S2P-1 above. S2P-3: the reveal posts to
`/devices/{id}/lock#show-pin` (without JS the answer lands on its row; the fragment never reaches the server), and
`check_forms` has no exception for it any more. S2P-4: Send again and the reveal's "the phone still has" read only a
report since the enrolment. S2P-5: the shown PIN is in a plain cell (not a `<details>` whose reopening would restart
it) and hides after a minute by CSS alone; `static/reveal.js` empties it on `pagehide` and when the page is hidden.
S2P-6: post/redirect/get is out (the PIN in no address), so `reveal.js` turns the answer's history entry into a GET
(`history.replaceState`): a reload or Back loads the page without the PIN and never posts the password again; without
JS the browser asks before resubmitting, as for any form. Checked in Chromium: a reload after the reveal is one GET
with no PIN, the digits' computed visibility is `hidden` at the animation's end, the reveal row kept its place.

## Step 3 as built (2026-10-22, `design-59-step3`)

Launcher only. `Hybrid59`/`HybridPlan.kt` became `lock/AndroidLock.kt`/`lock/AndroidLockPlan.kt` (`AndroidLockPlanTest`),
`KeyguardMode.HYBRID` `ANDROID`, the log tag `AndroidLock`; the debug switch (`HybridDebug` in both source sets, its
receiver, manifest entry and `CheckDebugHook` names) is gone and a test bans the names. Notes in `launcher/CLAUDE.md`,
"Android's own lock screen as the gate".

- **The switch** is `androidRequested(kid_lock)` - exactly `"android"`; P59-5's test pins what it returns (`"own"`,
  missing, junk, no `kid_lock`) and `configure`'s derivation of the face and the keyguard from it.
- **M-1**: `keyguard_requested` (with `pin_rev`, committed by `saveConfig` beside the hash) and `keyguard_android`
  (owned: written ahead of the migration's reset, cleared only when `isDeviceSecure` reads false) in CE
  `pin_lock_state`, plus `android_pin_rev` and `android_boot_count`. The rollback edge = owned && !requested.
- **L-1**: `PolicyToApply.carriesParentChoice` (`Apply` with a policy); `AppEnforcer.apply(context, decision)` passes it
  to `PinLockRuntime.configure(..., parentChoice)`, and every `apply(.., currentPolicyDecision().policy)` caller now
  passes the decision (a test bans the old form). A bare policy never carries it (the debug cover receiver's apply).
- **L-3**: `WindowRelock.ensureAndroidResetToken` (`androidTokenStep`) at every apply while requested or owned.
  **L-4**: `PinLockStore.swapAndroidBoot` in `decide`, every CE start, every mode.
- **Bug 1**: `USER_PRESENT` has its own receiver, `RECEIVER_EXPORTED`, filter = that action alone (it is a
  `<protected-broadcast>` in frameworks/base `core/res/AndroidManifest.xml`, checked); it acts only as the face, so
  own mode is unchanged (the not-exported filter keeps the action, and returns for it as the face).
- **Bug 2**: an ask is retried by a tap once it is `ANDROID_ASK_RETRY_MS` (1 s) old, and recovered - the state read
  with RESUME's rule - when no answer came within `ANDROID_ASK_TIMEOUT_MS` (2 s) while our window has the focus, or
  when the focus comes back later (the empty shade collapsed). Per-ask callbacks: an older ask's cancel or error is
  moot; any ask's success leaves, but never after a screen-off since that ask.
- **P59-1/2**: `noteLockTask` logs mode, features and the keyguard state, no verdict. **P59-3**: `androidRefrontHeld`
  needs a credential. **P59-4/8**: the ANDROID write reads back `ANDROID_KEYGUARD_FEATURES | 0x2` (managed);
  `KeyguardCover.androidCameraGuard` = that read; the camera lock follows `AndroidLock.cameraGuarded()` and re-syncs on
  the face's edges and the guard's (`afterKeyguard`, after the keyguard write - `refreshTargets` runs before it).
  `step()` reads `deviceSecure` live for every trigger. **P59-6**: the resume is `faceStep(RESUME)`. **P59-7**: every
  `Test` task has `src/release` and `src/debug` as inputs. The nit: an ANDROID step failure logs "Android's PIN stays
  the gate", not the cover's "stays disabled".
- **Deviations, each the safer reading**:
  - The `USER_PRESENT` mark is reset at every LOCKED edge **and** every screen-off and remote lock while LOCKED (a
    screen-off while already LOCKED is no edge; a mark kept across it could open a later wake whose keyguard didn't
    lock).
  - `SLEEP_CHECK` never leaves, mark or not (dark: RELOCK); a missing credential beats the pad's success
    (`OWN_KEYPAD` first - a success with no credential is no PIN); `AFTER_WAIT` reads again, so a keyguard that locked
    during the wait stays rather than relocks.
  - The restart line is cleared at the boot's first CE start (every mode) and when the face goes (a rollback) - not
    at every own-mode apply, so a release phone reads nothing new per apply.
  - The "ours" flag holds 60 s; a lost `onPasswordChanged` can't consume a later change of the kid's.
  - `keyguard_relocks` is counted in memory (`AndroidLock.relocks`, capped at 1000); step 4 reports and resets it.
  - A `Fallback` apply (no `kid_lock`) turns the face off but, while we own the live credential, keeps the ANDROID
    keyguard write and the stored request (S3-1 below): Android's PIN stays the gate with SystemUI's face, no rollback.
- **Not here** (step 4): `set_kid_pin`, `verifyCommandPin`, remote lock's answer, `lock_state`'s fields,
  `unlocked_at_ms` (`AndroidLock.firstCeStartOfBoot` is there for it), `android_keyguard_v1`. Without the capability
  the server sends no PIN, so a phone switched to Android's lock migrates at its next Child PIN on our keypad.
- **Device checks this step leaves** (§8, §9): the ask's recovery on a real empty shade (focus semantics of
  SystemUI's bouncer and shade over a showWhenLocked activity), the sleep check's reads 1 s after a power press and a
  timeout, `USER_PRESENT` now arriving (an unlock through SystemUI's own face leaves the glance at once), the first
  boot unlock without a glance flash.
- **Code QA fixes** (`qa-59-step3-code.md`):
  - S3-1: `androidKeyguardHeld(owned, deviceSecure, parentChoice)` keeps `keyguardMode` ANDROID (features, T, 0x2)
    on an apply without the parent's choice while we own the live credential; `keyguard_requested` is stored only
    from such an apply (`requestToStore`), `AndroidLock.requested` likewise; 24b's step 1 is skipped while requested
    **or** owned.
  - S3-2: `USER_PRESENT` with the screen off is ignored (the background queue may deliver it after a screen-off).
  - S3-3: the safer of the two options - not a new leave signal, a longer wait: when Android read locked since this
    screen-off (the lock-up's read, the sleep check's STAY), a not-locked read waits `ANDROID_UNLOCK_WAIT_MS` (3 s)
    for `USER_PRESENT` instead of 1 s, then relocks as before. The leave rule is unchanged (no LEAVE without the
    broadcast or the pad); a quick wake that never saw Android locked keeps the 1 s. §8 step 11 measures the delay.
  - S3-4: pins for the mark reset's condition, `presentLeave` and `faceWait`'s steps and triggers, the resume's
    screen check; the pure `bootSwap` (tested) behind `swapAndroidBoot`'s commit.
  - N1: `migrateIfDue`'s `managed = true` explained (our keypad runs only while the lock is active, which needs
    managed). N2: an unreadable `keyguard_requested`/`keyguard_android` skips 24b's removal in every mode - the
    safe side, documented on `RelockPlatform.androidKeyguardRequested`.

## Step 4 as built (2026-10-22, `design-59-step4`)

Launcher only; no server change (step 1's contract holds as written). Notes in `launcher/CLAUDE.md`, "Android's own
lock screen as the gate".

- **`set_kid_pin`** (§3.5): `dispatchPendingCommand` now takes the fresh answer's `kidLock` with its command, and the
  branch calls `AndroidLock.applyPinCommand(context, kidLock, pending.pin)` (after the receipt, before this sync's
  apply). The pure `pinCommandStep(requested, pin, verifies, tokenActive)` gives the answer in §1's order (`own lock`,
  `refused: no_pin`, `refused: mismatch`, `refused: no_token`) or `null` = set it; `PinCommandAnswer` holds the six
  words and their `success`. `verifies` is `PinLockRuntime.verifyCommandPin(kidLock, pin)` (Q59-1): the fresh hash,
  no backoff, no failure, still in `PinLockRuntime.kt` (`ParentPinCallersTest`). `tokenActive` is
  `WindowRelock.ensureAndroidResetToken` (it sets a token for the escrow when none is stored, §3.6) and runs only for
  a PIN that verified. The set (`setFromCommand`, under `AndroidLock`'s monitor, which the rollback, the migration and
  `onPasswordChanged` share): `keyguard_android` committed first, `reset()` (the "ours" flag, the one
  `WindowRelock.resetCredential` call), then `android_pin_rev` = the fresh rev. Log: `PIN command: <answer> (rev N,
  secure now=..)`, never the PIN.
- **Replays and staleness**: a stale or replayed command's PIN is checked against the hash of the answer it came in -
  the server hands the row out only at the rev it read (S59-1), so an older PIN can't verify against a newer hash; a
  repeated id never runs (`CommandAnswers.begin`); the cache never holds the PIN, and dispatch runs only off a fresh
  answer.
- **Process death mid-dispatch**: the id is marked run before the receipt, so a delivery again is confirmed and not
  run; the row has no answer, and the server's intake queues a new one after the re-delivery window for a phone that
  still reports `own` or a stale rev (step 1). The write-ahead keeps that heal possible (below).
- **`lock_state`** (`LockStateReport`): `keyguard`, `pin_rev`, `reset_token`, `keyguard_relocks`, **always sent** (no
  defaults: `ServerJson` leaves defaults out, and the server's queue rule needs `keyguard: "own"` to see a phone that
  needs the PIN). From `AndroidLock.report(context, ownRev)`: the pure `keyguardReport` over `androidGate(face, owned,
  deviceSecure)` - `"android"` with `android_pin_rev` (only for a credential we own), else `"own"` with the rev
  committed with the hash in use (`config`'s, else the stored one; `null` = no Child PIN); `reset_token` =
  `WindowRelock.resetTokenActive` in every mode; the relocks an `AtomicInteger`, taken and reset by the report (as
  `exempt_yields`). A rollback waiting for the token reports `android` with `inactive: android_credential` (N-C).
- **Remote lock** (§3.9): `lockAnswer(ok, ours, androidGate)` - `locked (android pin)` whenever Android's PIN is the
  gate (`AndroidLock.gateNow`), else today's words.
- **`unlocked_at_ms`** (§3.9): `PowerEvents.recordUnlock(context, AndroidLock.firstCeStartOfBoot)`, called in
  `Application.initUnlocked` right after `PinLockRuntime.decide` (whose boot-count test sets the flag) and before
  `initRest` (so before the anchor's first sync can send the start); the pure `withUnlock` stamps this boot's start
  once (DE record, `commit()`, new key `on_unlocked_at_ms`). `power_report.json` already carried it (step 1).
- **Capability** `android_keyguard_v1` (`ANDROID_KEYGUARD_CAPABILITY`), last in `STATUS_CAPABILITIES`, beside the
  handler (Q59-13).
- **Deviations, each the safer or the more exact reading**:
  - The write-ahead before the command's reset keeps the rev Android holds **now** (`commandWriteAheadRev`: ours ->
    the stored rev, else none) instead of clearing it, so it never claims a rev whose PIN isn't set (the server would
    then drop the `prev` PIN the phone really has). A death between the reset and the rev's commit: since the fix
    round (S4-1) our change is marked in CE with the rev it sets, so the `onPasswordChanged` that starts a new process
    takes that rev (the server sees the current one); if that broadcast is lost too, the older rev stays and the
    server sends the PIN again. A failed reset gives back exactly what was written ahead (the command, unlike the
    keypad migration, can meet a foreign credential: that stays not ours).
  - `verifyCommandPin` also requires exactly the policy's `pin_length` in digits (`commandPinShape`): Android's pad
    and our keypad then take the same PIN. Its body is the top-level `commandPinVerifies` in the same file, so the JVM
    test runs the real PBKDF2 (the object's init needs a Looper).
  - No extra apply after `pin set`: the sync's own apply follows the dispatch (`AppEnforcer.apply` right after), with
    the fresh policy already cached.
  - `keyguard: "android"` also for an unmanaged phone holding our PIN (Android's PIN is its gate); step 1's loop guard
    holds either way.
  - The dispatch returns the command without its PIN (`ranCommand` lives for the rest of the sync).
- **Device checks this step leaves** (§8 step 8, 10, 12, 14): the command with the screen off, during Android's wait
  and with two saves offline; `unlocked HH:MM` after a reboot; whether a reset to the same PIN keeps the fingerprint.
- **Code QA fixes** (`qa-59-step4-code.md`):
  - S4-1: §3.7's one-shot in-memory "ours" flag is now a CE mark (`OwnChange` in `pin_lock_state`: a count, the boot,
    a 60 s elapsed window, the rev being set; pure `ownChangeArmed`/`ownChangeConsumed`/`ownChangeLive`). Every own
    reset arms it first and takes it back when it fails; `onPasswordChanged` consumes one and, for a change of ours,
    keeps the stored rev or takes the rev being set (`androidPinRevAfterChange(expected, rev, settingRev)`). A new
    process after a death and two of our resets close together are both known as ours.
  - S4-2: after `pin set`, the apply's `afterKeyguard` (after the ANDROID keyguard write) calls `lockNow` once while
    the face is on, our lock LOCKED and the screen dark (`lockAfterPinSet`), not counted as a relock: the next wake
    finds Android locked instead of RELOCKing under the kid's thumb. [device: §8 step 8, the screen off]
  - S4-3: the no-PIN-in-logs scan reads every `Log.*(`/`log(` call to its closing parenthesis, across lines, in
    `MdmSyncWorker`, `AndroidLock`, `PinLockRuntime`, `WindowRelock` and `PinHash`, and bans templates, `.pin`,
    concatenation and `pin` as an argument; the scanner itself is tested on M11's and M16's shapes (both killed).
  - S4-4: `requested = androidRequested(kidLock)` pinned in `applyPinCommand` (M13 killed).
  - N1: the final rev's commit is tried twice (`commitRev`, also the keypad migration's), then logged.
  - N2: `report` reads the relocks; only a status report the server took subtracts them (`relocksReported`), so a
    failed POST keeps them.

## F3: the first unlock after a reboot (2026-10-22, branch `fix-f3-boot-cover`)

The RC run (`docs/testing/2026-10-22-d59-rc-run.md` §9, F3): after the PIN on SystemUI's pad the top was the stock
`NexusLauncherActivity` from 1 to 10 s, our Home at 11 s; kiosk off in that window, hidden apps still hidden.

**Why.**
- Before design 59 the phone had no credential: CE unlocked at boot, and design 16's Home start (and 16b's cover, when
  its switch was on) ran behind the boot animation. With Android's PIN the boot stays BFU until the kid types it. Only
  direct-boot-aware HOMEs resolve then. On the emulator image that is the stock launcher; AOSP Launcher3's
  `QuickstepLauncher` isn't DBA (`quickstep/AndroidManifest-launcher.xml`), so there it is Settings' `FallbackHome`. The
  PIN's dismissal reveals whatever HOME is resumed behind the keyguard.
- FallbackHome finishes by itself at the unlock once HOME resolves elsewhere (`FallbackHome.maybeFinish`,
  `isUserUnlocked` + `resolveActivity`). The stock launcher never does: it stays until our process brings Home
  (design 16's `BootHome`).
- That start is the last step of our unlocked setup: `ACTION_USER_UNLOCKED` -> `ensureUnlockedInit` -> `decide`
  ("Process start: UNLOCKED", 16:42:17.0) -> `initRest` on the main thread -> `PinLockRuntime.init`'s posted pass ->
  `BootCover.init`, `BootHome` ("Home brought to the front: boot 4", 16:42:29.6). The 12.6 s between them is
  `initRest` on the debug build's first CE start (the run's "Verification of ... took N ms" lines: ART verifying
  classes at runtime). The prototype run's 3 s is the same path, warmer. `ensureAndroidResetToken` and the first apply
  run on background threads; `swapAndroidBoot` is one small CE commit inside `decide`; lock task starts only in
  Home's resume. None of them is in front of the Home start.
- The boot cover (16b) is the piece for exactly this window, but it was off: the server switch `boot_cover` is off
  by default, and it is armed only at `ACTION_SHUTDOWN`. `adb reboot`, a crash or a hard reset send none. Even when
  armed, it handed over 3 s after its first frame. In BFU that time passes behind the keyguard, so at the PIN it left
  at once, into a busy main thread.

**The fix (launcher only, no server switch or contract change).**
- **Android mode implies the cover**: `bootCoverWanted(switchOn || androidFace, managed, deviceOwner)`, the face as the
  apply's `configure` decided it (`AndroidLock.faceOn`). The server switch keeps its meaning for own mode. Why not the
  switch: in android mode the cover is what keeps the stock launcher off the screen, and it never shows before the PIN
  (below), so the BFU-lockout risk the switch guarded is not there. The status report's `boot_cover.wanted` is then
  true; the server's notes still show only with its switch on.
- **A device-protected marker** `boot_cover_android` (an empty file next to the cover's record; written only by the
  main process at each apply, `bootCoverAndroidMarker`). The cover and the locked boot read it, because CE can't be
  read before the PIN.
- **Armed at every boot**: besides the shutdown arm, `BootCallReceiver` (already DBA, `LOCKED_BOOT_COMPLETED`, the
  call policy first) calls `BootCover.atLockedBoot`. In android mode (`coverAtLockedBoot`: marker, not yet unlocked,
  guard not tripped) it enables the cover and starts the typed HOME restricted to our package. Before the unlock that
  resolves to the cover alone, HOME-typed, in front of a stock launcher the boot already started. A device owner may
  start it from the background (`BackgroundActivityStartController`: "callingUid is device owner",
  android15-release :859-862). A graceful shutdown's arm makes the start a re-delivery.
- **Never over the keyguard**: no `showWhenLocked`, no dismissal (`BootCoverManifestTest`). Before the PIN, Android's
  lock screen stays the face: the wallpaper, "Skriv koden for å starte telefonen" and the pad, as design 59 §4.4 wants.
  A showWhenLocked cover would hide the pad and the restart line, and it would need a dismiss path of its own. The
  cover only becomes visible when the PIN's dismissal reveals it.
- **It waits for our Home** (`coverWaitStep`, android mode only; own mode keeps 16b/16e's 3 s):
  - It leaves once our main process has taken over and Home is over it. Our main process disables it in
    `BootCover.init`, right before `BootHome`'s typed start in the same main-thread pass; then the cover's stop
    finishes it.
  - It never leaves earlier: below it may be the stock launcher a boot without `ACTION_SHUTDOWN` started, and a finish
    would resume it.
  - A stop while still enabled (the keyguard back, a call over it) never ends it.
  - AMS also finishes a disabled component's activities on the delayed `PACKAGE_CHANGED` (1 s, 10 s just after the
    system server starts: `PackageManagerService` :942-943, :4050; `cleanupDisabledPackageComponentsLocked`). By then
    Home's start has already put Home's task on top.
- **Never stranded**:
  - Taken over but still in front 5 s later (`COVER_TAKEOVER_GRACE_MS`; the boot's Home start didn't come - a call,
    or it was refused): it leaves anyway.
  - No take-over 30 s after the unlock (`COVER_LAUNCHER_WAIT_MS`; the debug build needed 12.6 s): it disables itself,
    starts the typed HOME (now HomeActivity, which starts our main process), and leaves as above.
  - The crash guard (2 crashes in a boot) and the tripped guard's sticky "off" are unchanged. A tripped guard holds in
    android mode until android mode or the switch goes off.
- **Measured, not shaved**: `Application.initUnlocked` logs `Unlocked setup took N ms` (the main thread's share of
  the gap). A cheap reorder doesn't help. Home's activity is created on the same main thread, so starting it earlier
  only queues it behind `initRest`. A slimmer `initRest` is worth it only if the release build's number is large
  (below).

**Timing on a release build (the Jelly Star).** With the release build as device owner, android mode on, Child PIN
set:
1. `adb logcat -c`, then reboot: `adb shell svc power reboot` (graceful) and, as a second run, `adb reboot` (the
   locked-boot arm).
2. Once `adb wait-for-device` returns and `adb shell getprop sys.boot_completed` reads 1, start both:
   - `adb logcat -v epoch -s BootCover BootCallReceiver HomeFront Application PinLock ActivityTaskManager:I > boot.log`
   - `adb shell 'while true; do echo "$(date +%s.%N | cut -c1-14) $(dumpsys activity activities | grep -m1 topResumedActivity)"; sleep 0.2; done' > top.log`
3. Before the PIN `boot.log` has `Boot cover started at the locked boot (android mode)`, or after a graceful
   shutdown `Boot cover enabled (shutdown)` from the last boot. Type the PIN.
4. Read:
   - `top.log`: from the PIN to `HomeActivity` the top is `BootCoverActivity` only, never the stock launcher's
     package. The PIN's moment is the first line whose top isn't the keyguard's.
   - `boot.log`: `Unlocked setup took N ms`, `Home brought to the front: boot N`, then
     `Boot cover leaves (taken over, Home over it): N ms after the unlock`. The last N is the gap the kid sees as the
     breathing mark.
   - `ActivityTaskManager: Displayed ...HomeActivity: +N ms` gives Home's own draw.
   - Any `Boot cover leaves (taken over, grace over)` or `Boot cover disabled: no take-over` line is a finding.

**The emulator run must check** (debug build, android mode):
1. `svc power reboot`, then the PIN: the breathing mark from the dismissal to Home, no `NexusLauncherActivity` in
   `topResumedActivity` at any sample. The mark (not the stock launcher) for the ~12 s of the debug start.
2. The same with `adb reboot` (no `ACTION_SHUTDOWN`): `Boot cover started at the locked boot`, then the same result.
3. BFU face unchanged: SystemUI's lock screen with "Type your PIN to start the phone" and the pad. The cover is not
   seen before the PIN, and the emergency call works. `dumpsys activity activities` shows `BootCoverActivity` behind
   the keyguard.
4. A call (`gsm call` from an allowed number) right after the PIN, before Home: the call screen comes over the
   cover; after the call our Home, no stock launcher.
5. The fallback: `run-as` kill the main process right after the PIN, again whenever it comes back, for 30 s -
   `Boot cover disabled: no take-over ...` after 30 s, then our Home. Killed only once, it comes back within the 30 s
   and takes over as in 1.
6. Own mode with the switch off: no cover, no marker (`run-as <pkg> ls /data/user_de/0/<pkg>/files`: no
   `boot_cover_android`), as before. Own mode with the switch on: 16b/16e unchanged (the 3 s mark).
7. Rollback to own mode: the marker goes at the next apply, and the next reboot has no locked-boot start.
