# 24 - Parent PIN hardening

User decisions (2026-10-09):
- the parent PIN on the kid's phone (the offline override PIN) gets an escalating lockout;
- every use and every wrong try is reported to the PWA;
- the PWA warns about easy PINs and makes changing the PIN easy.

**Threat model:** the kid guessing on the phone itself. The hash never leaves CE storage, there is no backup, and
`DISALLOW_DEBUGGING_FEATURES` blocks adb, so offline cracking is out of scope.

**Rules:** no bypass, never lock the parent out for good, and no machinery beyond what the decisions need.

**Revised after QA's design review** (§ "QA review (design)", then "Decisions after QA review" at the end) and the
user's answers of 2026-10-09. Design 19b phase 1 ships with step 1 (§5a).

## 0. Today (verified 2026-10-09)

- **`server/OfflineOverride.kt`:**
  - `verifyPin()` runs PBKDF2 against the cached `override_pin_hash`/`_salt`.
  - `MAX_FAILED_ATTEMPTS` = 5, then a 15-minute lockout by wall clock. The counter resets when a lockout starts, which
    allows 480 tries a day.
  - The state lives in the default (CE) prefs `offline_override_failed_attempts`/`offline_override_locked_until`,
    written with `apply()`. It isn't synchronized, and it is counted *after* the hash runs.
- **The bypass (QA #1).** `LockActivity`'s unlock-code dialog checked the lockout only when it opened, and
  `verifyPin()` didn't check it at all. So during any time lock the tries were unlimited, and a match lifted every
  restriction for 2 h.
  - **Hotfix ec58d6d9 (on main):** `verifyPin` returns false without hashing while `isLockedOut()`, and the dialog
    closes (with a toast) when a wrong try starts a lockout.
  - Step 1 replaces both.
  - The other four entries had no bypass.
- **PBKDF2 on the main thread.** `SettingsActivity.showPinGate` and `SettingsFragmentLauncher.showPausePinDialog` run
  it there (~0.5 s of jank). Every PIN result is a Toast, and toasts don't show while a screen-time budget sets
  `DISALLOW_CREATE_WINDOWS` (design 10).
- **What gets reported.** `offlineOverrideUsedPendingReport` is one boolean, cleared after any successful status
  report, so a use during an in-flight report is lost. Nothing reports Settings, the pause, install mode, the PIN
  lock's "Parent code" or wrong tries. Install mode only shows up as the `play_install_mode` security-log line, from
  the status diff.
- **Server, `devices.rs::update_policy` (~1703):**
  - A typed PIN of 6+ digits is hashed. Anything else is **silently ignored**, with no upper bound although the field
    says 6-10.
  - A PIN that matches the kid's is refused (`override_is_kid_pin`).
  - The PIN fields sit inside the "Play and kiosk" form, and the refusal notice renders at the page top (QA #8).
- **A successful policy sync ends the offline override** (`MdmSyncWorker`: `OfflineOverride.clear()`).
- **Reusable today.** `lock/PinBackoff.kt` (the kid PIN) has the three clocks (`LockClocks`, `BackoffWindow`,
  `backoffRemainingMs`). It counts the try before PBKDF2, resets on a new PIN (`backoffForHash`), and its store
  uses `commit()`. This design reuses those types.

## 1. Entry points (all of them: `PinHash.verify(` is only in `OfflineOverride` and `PinLockRuntime.checkPin`)

| # | Entry (`entry` wire value) | Where | On a correct PIN | Window |
|---|---|---|---|---|
| 1 | `override` | `LockActivity` "Enter unlock code" (time lock / budget) | `OfflineOverride.activate` | 2 h, ends early at the next successful sync |
| 2 | `pin_lock` | `PinLockActivity` "Parent code" -> `PinLockRuntime.checkParentCode` | opens the PIN lock only, resets the kid backoff | - |
| 3 | `settings` | `SettingsActivity` gate (every `onCreate`/`onStart`) | Settings visible until `onStop` | session |
| 4 | `pause` | Settings "Pause all restrictions" | `RestrictionsPause.start` | 2 h (`RESTRICTIONS_PAUSE_DURATION_MS`) |
| 5 | `install_mode` | Settings "Install from Play" | `PlayRuntime.startInstallMode` | 15 min (`INSTALL_MODE_DURATION_MS`) |

The pre-checks that read the lockout all switch to `ParentPin.lockoutRemainingMs`:
- `SettingsGate.settingsAccess(lockedOut)` and `PlayPolicy.canStartInstallMode(pinLockedOut)`;
- the `isLockedOut()` calls in `LockActivity` (hotfix included), `PinLockActivity` and
  `SettingsFragmentLauncher.showPausePinDialog`.

19b's "Background connection" row (§5a) is not an entry point. It lives inside the gated Settings and works only
during the pause.

## 2. Launcher: one gate, `ParentPin`

There are three new files: `server/ParentPin.kt` (object), `server/ParentPinLockout.kt` (pure, JVM-tested) and
`server/ParentPinStore.kt`. `OfflineOverride.verifyPin`/`isLockedOut` and the hotfix go.

```kotlin
enum class ParentPinEntry(val wire: String) {
    OVERRIDE("override"), PIN_LOCK("pin_lock"), SETTINGS("settings"), PAUSE("pause"), INSTALL_MODE("install_mode"),
}
sealed interface ParentPinResult {
    data object Ok : ParentPinResult                       // OVERRIDE/PAUSE/INSTALL_MODE: the window has started
    data class Wrong(val triesLeft: Int, val nextLockoutMs: Long) : ParentPinResult
    data class LockedOut(val remainingMs: Long) : ParentPinResult
    data object TooShort : ParentPinResult                 // < 6 characters: not counted (QA #14)
    data object NotConfigured : ParentPinResult
    data class Unavailable(val storageFull: Boolean) : ParentPinResult // a failed commit only (QA #3)
}
@Synchronized fun check(context: Context, entry: ParentPinEntry, pin: String): ParentPinResult // background only
@Synchronized fun lockoutRemainingMs(context: Context): Long                                   // 0 = none
```

**The windows start inside `check` (QA S4).**
- `check` computes `val start = BootClock.windowStart(duration)` and commits the `unlocked` event with
  `until_ms = start.untilWallMs`.
- Then, in the same call, it runs `OfflineOverride.activate(context, start)`, `RestrictionsPause.start(context,
  start)` or `PlayRuntime.startInstallMode(context, start)`.
  - `RestrictionsPause.start` now runs today's `reapplyAfterPauseChange` itself.
  - The UI keeps only its follow-ups: dismiss, finish, update the switch or summary.
- A source scan pins that the three starters are called only from `ParentPin.kt`. No caller can start a window
  without the gate, and a cancelled dialog coroutine can't split an event from its window.
- The order (event first) means a use is never hidden. A process death between the two steps shows a use whose window
  didn't start.

**Lock order.** `check` never takes another lock while holding its own. `checkParentCode` resets the kid backoff
under `PinLockRuntime`'s lock *after* `check` returns. If they are ever nested, `ParentPin` comes first.

### 2.1 State machine (pure, `ParentPinLockout.kt`)

```kotlin
const val PARENT_FREE_TRIES = 5
const val PARENT_MIN_PIN_LENGTH = 6
const val PARENT_LOCKOUT_BASE_MS = 15 * 60_000L
const val PARENT_LOCKOUT_CAP_MS = 24 * 60 * 60_000L
const val PARENT_LEVEL_DECAY_MS = 24 * 60 * 60_000L
const val PARENT_LEVEL_MAX = 8                          // parentLockoutMs(8) is already the cap
data class ParentLockoutState(
    val failures: Int = 0,              // wrong tries since the last lockout began (0..4); a correct PIN clears them
    val level: Int = 0,                 // the escalation level as of the last wrong try (0..8)
    val lockout: BackoffWindow? = null, // the running/last lockout (lock/PinBackoff.kt type)
    val decay: BackoffWindow? = null,   // from the last wrong try, duration level x 24 h
    val pinFingerprint: String? = null, // which parent PIN this is about (KidLockConfig.fingerprint's recipe)
)
fun parentLockoutMs(level: Int) = min(PARENT_LOCKOUT_BASE_MS shl (level - 1).coerceIn(0, 7), PARENT_LOCKOUT_CAP_MS)
// 15m, 30m, 1h, 2h, 4h, 8h, 16h, 24h
fun levelNow(s: ParentLockoutState, now: LockClocks): Int =   // on a refreshed state
    s.decay?.let { ceilDiv(backoffRemainingMs(it, now), PARENT_LEVEL_DECAY_MS).toInt() } ?: 0
```

- **Level (the user's answer to #7):**
  - A correct PIN keeps the level. Only a new PIN from the PWA resets it, together with any running lockout,
    through the fingerprint.
  - The level drops one step for every 24 h without a wrong try. `decay` restarts at every wrong try with the current
    level as its length in days. `levelNow` is the number of whole or partial days left in it.
  - `decay` uses the same clock rules as the lockout, so a clock moved back slows the decay and a forward jump within
    a boot doesn't speed it up.
  - From level 8, at most 8 quiet days return the level to 0.
- **`check`, in order, under one lock:**
  1. **Load and refresh.** Load the state (corrupt state: §2.3), then apply `forParentPin(state, fp)`: a new
     fingerprint gives a fresh state (failures, level, lockout, decay all reset). Then apply `refresh(state, now)`,
     which re-anchors both windows after a reboot. If that changed anything, commit it.
  2. **Locked out?** If `backoffRemainingMs(lockout, now) > 0`, return `LockedOut`. No hash runs, nothing is counted
     and no event is written, so hammering during a lockout costs nothing and logs nothing.
  3. **Too short?** If `pin.length < 6`, return `TooShort`, uncounted. The server has never accepted a parent PIN
     under 6 digits, so no such PIN can match. Longer inputs are not refused, because PINs saved before the 10-digit
     bound may be longer.
  4. **Count first.** Apply `beginParentAttempt(state, now, entry)`:
     - `L = levelNow`, `failures + 1`.
     - On the 5th: `L' = min(L + 1, 8)`, `failures = 0`, `lockout = backoffWindow(now, parentLockoutMs(L'))`,
       `level = L'`, `decay = backoffWindow(now, L' x 24 h)`, plus a pending `lockout` event (`level = L'`,
       `until_ms`).
     - Otherwise: `level = L`, `decay = L > 0 ? backoffWindow(now, L x 24 h) : null`, plus a pending `wrong` event
       (`failures`).

     The state **and the pending event** are committed in one `commit()` before PBKDF2 (QA #6). If the commit fails,
     see §2.3.
  5. **Hash.** Run PBKDF2.
  6. **Match.** The state becomes the *pre-attempt* state with `failures = 0`, so the level and decay are untouched: a
     correct PIN is not a wrong try. The pending event is replaced by `unlocked` in one commit. Then the window
     starts (above).
  7. **No match.** The committed state and event stand. Return `Wrong(5 - failures, parentLockoutMs(levelNow + 1))`,
     or `LockedOut(duration)` on the 5th.
- **Within one boot**, the existing `backoffRemainingMs` decides for both windows. It holds while elapsed realtime or
  the wall clock says time remains. A wall clock that went back is ignored, and nothing exceeds the duration.
- **Across a reboot** (another `BOOT_COUNT`, or elapsed realtime went back), `refresh` re-anchors each window to this
  boot:
  - `remaining = wallStart + duration - nowWall`, clamped to `0..duration`;
  - if `nowWall < wallStart`, then `remaining = duration`;
  - the new window is `backoffWindow(now, remaining)`, or `null` at 0.

  This deliberately differs from the kid backoff (a full restart on every boot). At 24 h, a full restart would let
  daily reboots keep the parent out indefinitely. Carrying the wall clock over can only shorten a window if the wall
  clock jumps forward across a reboot. `DISALLOW_CONFIG_DATE_TIME` and auto time prevent that while managed. During
  an override or pause they are lifted, which gives one extra round at most (QA's Q5). The level persists either way.
- **Bounds.**
  - Every lockout path is capped at 24 h. A new boot gives at most `duration`, and migration gives at most 15 min.
  - After a lockout the parent always gets 5 fresh tries.
  - Possible tries, whether or not the parent uses the PIN: 35 on the first day of continuous guessing, then about 5
    a day. A pause long enough to decay to 0 (up to 8 days) buys one more burst of 35. That makes at most about
    2,000 tries a year, about 0.2 % of the 6-digit space. Before: 480 a day, and unlimited on the time-lock screen.
- **The cost (accepted by the user).** The parent's own 5 wrong tries soon after a kid's guessing spree lock longer,
  and a kid can hold the lockout at 24 h. The way out is a new PIN from the PWA, which the phone and the PWA both say
  (§2.4, §5). Offline, the parent waits, at most 24 h.

### 2.2 Store and migration

- **Storage:** CE prefs `parent_pin_state`, every write with `commit()`, the same rules as `PinLockStore` (never DE;
  excluded from backup by the existing rules).
- **Keys:**
  - `version`, `failures`, `level`;
  - `lo_wall`, `lo_elapsed`, `lo_boot`, `lo_duration`;
  - `dc_wall`, `dc_elapsed`, `dc_boot`, `dc_duration`;
  - `pin_fp`;
  - `events_v1` (JSON, decoded separately and leniently, §2.3).
- **Migration of existing phones.** It runs once, on the first load with no `version` key, inside the lock:
  - `failures = offline_override_failed_attempts.coerceIn(0, 4)`.
  - If `offline_override_locked_until > nowWall`, a lockout is running:
    - `level = 1`;
    - `lockout = backoffWindow(now, min(lockedUntil - nowWall, 15 min))`, so a far-future value from a bad clock is
      capped;
    - `decay = backoffWindow(now, 24 h)`.
  - Otherwise: `level = 0`, no windows.
  - `pin_fp` is the current PIN's fingerprint, so the migration doesn't reset itself.
  - Commit `version = 1`, then zero the two legacy keys.
  - The two `LauncherPreferences` declarations stay, marked legacy, for this read. A running 15-minute lockout keeps
    running (never longer), and an old counter carries over. A crash between the two commits leaves only legacy keys
    that nothing reads (QA).
- **Legacy reporting stays.** `offline_override_used_pending_report` and the status field `offline_override_used`
  stay as they are, for servers older than this design.

### 2.3 Failure modes

- **Unreadable state (QA #2).** A load that throws (a type clash, a bad value, a missing key that should be there)
  never ends in a dead end. Under the lock, the state is replaced by
  `ParentLockoutState(level = 1, lockout = backoffWindow(now, 15 min), decay = backoffWindow(now, 24 h),
  pinFingerprint = fp)`. That is committed (which also rewrites every key with the right type), logged with
  `Log.e`, and the result is `LockedOut(15 min)`. It fails closed, then gives fresh tries. No event is made up for
  it.
- **Unreadable queue.** A corrupt `events_v1` decodes to an empty queue and is logged. It never decides a PIN result.
- **Failed commit (QA #3).**
  - `commit()` returning false (or throwing) at step 4 means: write the pre-attempt state and queue back with a second
    `commit()`, ignoring its result, so memory is restored even if the disk write fails again. Record nothing and
    return `Unavailable(storageFull = filesDir.usableSpace < 1 MiB)`.
  - Pressing OK again can't escalate anything.
  - The kid PIN differs here: `checkPin` ignores `saveBackoff`'s `false`, so with storage full the kid PIN keeps
    working and the parent PIN doesn't. That's accepted.
- **No hash, or an unusable one** (`PinHash.usable`): `NotConfigured`.
- **Main thread.** `check` never runs on it. The Settings gate and the pause/install dialogs move to `lifecycleScope`
  + `Dispatchers.Default`, with OK disabled while a check runs. `lockoutRemainingMs` does no hashing, but it may wait
  for a running `check` (≤ 0.5 s) on the shared lock. That's accepted.
- **A late Ok (QA #4).**
  - **Settings:** `SettingsActivity` takes a session number when a check starts, and `onStop` bumps it. An `Ok` from
    an ended session sets nothing: Settings stays gated. Its `unlocked` event stays.
  - **The PIN lock:** `PinLockRuntime` counts screen-offs. `PinLockActivity` captures the count when a kid-PIN or
    parent-code check starts, and drops an `Ok` that comes back after a screen-off. `PinLockState.step` ignores an
    `Unlocked` carrying an old count, so the lock doesn't open with the screen dark.

### 2.4 Phone UI and texts (en + nb, `TranslationsTest`)

- **No toasts for PIN results (QA #5).**
  - All four dialogs (`dialog_offline_override_pin`, used by the time lock, the Settings gate, the pause and install
    mode) get one error line, `dialog_offline_override_pin_error`, like `parent_code_error` in the PIN lock's dialog.
  - **`LockedOut`:** the dialog stays open with the text, and OK is disabled. It doesn't re-enable when the lockout
    ends; Cancel and reopen.
  - **Pre-checks:** where today a pre-check refuses with a toast (the Settings gate's `REFUSE_LOCKED_OUT` and
    `REFUSE_NO_PIN`, the pause, install mode's `LOCKED_OUT`, and the time lock's not-configured and locked-out
    cases), the dialog opens in that state instead. Cancel finishes Settings.
  - The non-PIN install-mode refusals keep their toasts (out of scope).
- **Texts:**
  - **Locked out** (`lock_unlock_code_locked_out`, `pin_lock_parent_locked_out`): "Too many wrong codes. Try again in
    %1$s, or save a new parent PIN on the parent page." / "For mange feil koder. Prøv igjen om %1$s, eller lagre en
    ny foreldrekode på foreldresiden." The value comes from `lockoutText(ms)`, rounded up to the minute: "15 min",
    "1 h 45 min", "24 h".
  - **Last try** (`parent_pin_last_try`, when `triesLeft == 1`): "Wrong code. One more wrong try locks it for %1$s."
  - **Too short** (`parent_pin_too_short`): "Enter at least 6 digits."
  - **Unavailable** (`parent_pin_unavailable`): "Couldn't check the code. Try again." With `storageFull`, it is
    `parent_pin_storage_full`: "The phone's storage is full. Free some space, then try again."

## 3. Events: queued on the phone, own endpoint

### 3.1 Why not the status report

An older server swallows unknown status keys and answers 204, so the phone would drop events that were never stored.
A separate endpoint gets a 404 from an old server (QA confirmed it on both listeners), and the events wait, as with
`CrashReports`.

### 3.2 Phone side (`server/ParentPinEvents.kt`, pure queue + upload)

- **Kinds:**
  - `unlocked`: every correct PIN, with `until_ms` for override, pause and install mode.
  - `wrong`: tries 1-4 of a round, with `failures`.
  - `lockout`: the 5th, with `level` and `until_ms`.

  They are written only by `ParentPin.check`, in the same commit as the state (§2.1), and carry no digits, length or
  other PIN material.
- **Queue:**
  - At most `PIN_EVENTS_MAX = 100` (the same constant on the server, pinned in both tests).
  - When it is full, the oldest `wrong` goes first, then the oldest overall. No `dropped` counter (QA S3): a lockout
    already means 5 wrong tries.
  - `id`: 128 random bits as 32 lowercase hex characters (`SecureRandom`).
- **Upload: `upload(context, api)`, only after the status report in `performMdmSync`, next to `CrashReports.upload`.**
  - No prompt send (QA S1): with no push, the parent sees events only when they open the PWA. An online phone syncs
    at least every 30 min. An override's event arrives in the sync that ends it.
  - It sends a snapshot taken under `ParentPin`'s lock. After a 2xx it removes **only the sent ids**, again under the
    lock (QA #11), so events added meanwhile survive. The sync mutex already makes uploads one at a time.
  - **Drop the batch** on any 4xx except 401, 404, 408 and 429 (QA #10).
  - **Keep it** on those four, on any 5xx and on a network failure.
- **Enrollment** (a new device token) clears the queue. The lockout state stays, since it is about the PIN.
- **Capability:** `parent_pin_events_v1` in `STATUS_CAPABILITIES`.

### 3.3 Contract: `POST /api/devices/pin-events` (bearer) -> 204

```json
{ "events": [ { "id": "0123456789abcdef0123456789abcdef", "kind": "unlocked", "entry": "override",
                "at_ms": 1760000000000, "until_ms": 1760007200000, "failures": null, "level": null } ] }
```

- `kind`: `unlocked | wrong | lockout`.
- `entry`: `override | pin_lock | settings | pause | install_mode`.
- `until_ms`, `failures` and `level` may be null or left out.
- This literal is pinned on both sides (QA #10). The server integration test posts it as is, and
  `PolicyResponseCompatTest` encodes an equal event to exactly this object.
- Launcher DTO: `ParentPinEventBatch`/`ParentPinEvent` (snake case via `ServerJson`).

### 3.4 Server side

- **Migration** (the next free number at the time; 0052 goes to 19b, §5a):

  ```sql
  CREATE TABLE device_pin_events (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
      event_id TEXT NOT NULL, kind TEXT NOT NULL, entry TEXT NOT NULL,
      occurred_at_ms INTEGER NOT NULL, until_ms INTEGER, failures INTEGER, level INTEGER,
      clock_off INTEGER NOT NULL DEFAULT 0,
      received_at TEXT NOT NULL DEFAULT (datetime('now')),
      UNIQUE (device_id, event_id));
  CREATE INDEX device_pin_events_recent ON device_pin_events (device_id, occurred_at_ms DESC);
  ```

- **Handler `device_api::pin_events`, routed in `device_routes.rs` (the device listener, design 22).**
  - **Lenient parsing (QA #10).** The body is `{"events": Vec<serde_json::Value>}`, and each event is read field by
    field. Only a body that isn't JSON, or has no `events` array, gets 400. More than `PIN_EVENTS_MAX` events gets
    413.
  - **Validation, `parent_pin::clean_event`:**
    - Skipped and logged: an `id` that isn't 32 hex characters, or an unknown `kind`/`entry`.
    - An `at_ms` outside 2020-01-01..now + 1 day is **kept** (QA #12): `occurred_at_ms` becomes the received time,
      `until_ms` becomes null and `clock_off = 1`.
    - An `until_ms` outside `at_ms..at_ms` + 24 h + 1 min becomes null.
    - `failures` outside 1..=4 and `level` outside 1..=8 become null.
  - **Storage.** Each event goes in with `INSERT OR IGNORE`. For `unlocked` and `lockout`, and **only when the row was
    inserted**, a security-log row is written in the same transaction (QA #13): `parent_pin_used` or
    `parent_pin_lockout`, detail `device {id}: {line}`, built only from the validated enums and numbers. Wrong tries
    stay on the device card and its notice.
  - The response is 204. It sends **no `command_notify`**: a nudge would sync the phone and end the override.
- **Retention:** `retention::prune` deletes rows older than 90 days (by `received_at`).

## 4. Server: easy PINs get a warning, not a refusal (`src/parent_pin.rs`, pure + unit tests)

User's answer (#9): the code is the parents' choice. No PIN is refused for being easy, and there is no date check.

- **Still refused, with a notice and nothing written:**
  - `parent_pin_invalid`: not 6-10 ASCII digits. This was silently ignored before.
  - `override_is_kid_pin`: the kid's PIN (`override_conflicts`, unchanged).
- **`easy(pin) -> Option<EasyKind>`**, warn only:
  - `Repeated`: all one digit.
  - `Sequence`: every step +1, or every step -1, mod 10. That covers 123456, 987654, 890123, 1234567890 and
    0987654321.
  - `Common`: an exact match in `COMMON_PINS`:
    - 121212, 123123, 112233, 123321, 131313, 101010, 202020, 232323;
    - 696969, 789789, 520520, 102030, 100000, 200000;
    - 159753, 147258, 258369, 147369, 159357, 789456, 147852, 123654, 111222, 147147, 741852, 963852, 852456,
      456123, 321654, 951753, 357159;
    - 12121212, 12341234, 11223344, 123123123, 1122334455, 1212121212.

  QA #16's broader rules ("at most 2 distinct digits", "a repeated block") are not taken. Existing PINs get the same
  warning only through the sweep below, which needs an enumerable set.
- **Its own card and handler.** The PIN leaves `update_policy`, whose SQL stops touching the PIN columns.
  `POST /devices/{id}/parent-pin` (`devices::update_parent_pin`, in `admin_routes` behind `require_full_auth`) takes
  `new_pin` or `clear_pin`:
  - It keeps the kid-PIN race guard (`CASE WHEN ? IS NULL AND kid_pin_hash IS NOT NULL ...`) and `spawn_blocking`.
  - It writes `override_pin_easy = easy(pin).is_some()` with a new hash, and `NULL` when the PIN is cleared.
  - It keeps the `override_pin_changed`/`_cleared` security events and nudges the phone.
  - It redirects to `?notice=<code>#parent-pin`.
- **Existing PINs get the same warning.** The user wants that, so the sweep stays and QA S2 is not taken.
  - A migration adds `ALTER TABLE device_policy ADD COLUMN override_pin_easy INTEGER;` (NULL = not checked).
  - At startup, a background task (`tokio::spawn` + `spawn_blocking`, one device at a time) checks every device with
    a hash and `override_pin_easy IS NULL`. It runs `security::verify_pin` against `parent_pin::easy_candidates()`:
    the Repeated and Sequence PINs of lengths 6-10 plus `COMMON_PINS`, 187 in all. That's a one-time cost of seconds
    to a minute of one core per device.
  - It then runs `UPDATE ... SET override_pin_easy = ? WHERE device_id = ? AND override_pin_hash = ?`, so a PIN
    changed meanwhile is not overwritten.
  - `DevicePolicy.override_pin_easy: Option<bool>`. The policy JSON is unchanged and so is `policy_json_keys_snapshot`.

## 5. PWA (English)

- **"Parent PIN"** replaces "unlock code (offline override PIN)" everywhere in `device_detail.html`:
  - the `managed_without_pin` banner, whose "Set a PIN below" links to `#parent-pin`;
  - the kid-lock card;
  - `kid_lock::flash_text`. The codes stay.

  The phone's own texts are unchanged.
- **Notices in their cards (QA #8).** The `?notice=` text renders at the top of the card it belongs to:
  - the parent-PIN codes inside `#parent-pin`, "saved" included;
  - the kid-lock codes inside `#screen-lock`;
  - nothing at the top of the page for these codes.
- **New card `id="parent-pin"`, right after "Screen lock"** (out of the "Play and kiosk" form):
  - **h2:** "Parent PIN"
  - **Intro:** "The parent PIN works on the phone even when it can't reach this server. It opens the launcher's
    Settings (and there, pausing all restrictions and Play install mode). It is the "Parent code" on the kid's lock
    screen, where it opens only the lock. On a time lock, "Enter unlock code" lifts the app restrictions and time
    rules for up to two hours (until the phone next reaches this server). Calls stay within the call rules, and phone
    hardening stays. It must differ from the kid's PIN."
  - **Lockout rule:** "After 5 wrong tries the phone stops accepting the parent PIN for 15 minutes. Each further
    lockout doubles (30 minutes, 1 hour, 2 hours, ...) up to 24 hours. The right PIN doesn't reset this. It steps
    back down by one for every 24 hours without a wrong try. Saving a new parent PIN here resets it and ends a running
    lockout once the phone checks in. If the phone can't reach this server, a lockout has to be waited out (at most
    24 hours)."
  - **Hint:** "Typed the PIN where your child could see it? Change it here. The phone uses the new PIN as soon as it
    checks in, usually within seconds."
  - **Easy warning** (`override_pin_easy = 1`): "This parent PIN is easy to guess (all one digit, a run like 123456,
    or a common PIN). That's your choice, but a child who guesses it can lift every restriction on the phone."
  - **Form:**
    - The label is "New parent PIN (6-10 digits; birthdays and years are easy to guess)".
    - The input is `type="password" inputmode="numeric" pattern="[0-9]{6,10}" maxlength="10"
      autocomplete="new-password"` (QA #15).
    - The button is "Save parent PIN".
    - Today's "Remove the PIN" checkbox stays, retitled.
  - **h3 "Reported by the phone":** the last 10 events, newest first, as "{when}: {line}".
    - `{when}` is "2026-10-09 14:02 UTC (3 h ago)", following house style.
    - Empty: "The phone hasn't reported any use of the parent PIN yet."
    - Without `parent_pin_events_v1`: "This phone's launcher doesn't report parent-PIN use yet; it will after its
      next update."
- **Lines** (`parent_pin::event_line`). The `unlocked`/`lockout` ones go to the security log too.
  - **unlocked:**
    - override: "Restrictions lifted on the time-lock screen, until {until} at the latest."
    - pin_lock: "Lock screen opened with the parent code."
    - settings: "The launcher's Settings opened."
    - pause: "All restrictions paused from Settings, until {until}."
    - install_mode: "Play install mode started, until {until}."
  - **wrong:** "Wrong parent PIN {where} ({failures} in a row)." Here `{where}` is one of "on the time-lock screen",
    "on the lock screen", "for Settings", "for pausing restrictions", "for Play install mode".
  - **lockout:** "5 wrong parent PINs {where}: the parent PIN is locked on the phone for {duration}, until {until}.
    Saving a new parent PIN ends it once the phone checks in."
  - **`clock_off`** adds " (the phone's clock was off; time of arrival shown)".
- **Status-card notices** (`parent_pin::notices(events, now)`, at most two, from the last 24 h):
  - **The latest use:** "The parent PIN was used on the phone {ago}: {line}". With more uses it adds " ({n} more in
    the last 24 hours - see Parent PIN)". For `override` and `pin_lock` only (screens the kid uses) it ends with
    " Typed where your child could see? Change the parent PIN." (a link to `#parent-pin`).
  - **A lockout (`error` style):** "The parent PIN was locked on the phone after repeated wrong tries (until
    {until}). If that wasn't you, your child may be guessing it - save a new parent PIN to stop it."
  - **Otherwise, wrong tries:** "{n} wrong parent PINs were typed on the phone in the last 24 hours. If that wasn't
    you, consider changing the PIN."
  - **The easy warning** also appears here, as one line linking to the card.
- **The old `offline_override_used` banner** shows only when the latest status lacks `parent_pin_events_v1`.
- **Flash texts** (`?notice=`, shown in `#parent-pin`):
  - `parent_pin_saved`: "Parent PIN saved. The phone uses it as soon as it checks in, and a lockout running there ends
    then."
  - `parent_pin_saved_easy`: "Parent PIN saved, but it is easy to guess (all one digit, a run like 123456, or a common
    PIN). The phone uses it as soon as it checks in."
  - `parent_pin_cleared`: "Parent PIN removed."
  - `parent_pin_invalid`: "Parent PIN not changed: it must be 6 to 10 digits."
  - `override_is_kid_pin`: "Parent PIN not changed: it must not be the kid's PIN."
  - `override_needed_by_lock`: reworded with "parent PIN".
- **Security log subtitle:** "... and successful sign-ins, plus parent-PIN use and lockouts reported by the phones."
- **Push:** a PWA notice now. Web Push or ntfy comes later (§9 Q2).

## 5a. Ships with step 1: design 19b phase 1 (same implementer)

This is per `docs/design/19b-doze-exemption.md`, "User decision". It is its own commit inside step 1 (1b), because
its status field is an API change: launcher and server go in one commit.

- **Launcher:**
  - `StatusReportRequest.batteryOptimizationExempt: Boolean? = null` -> `battery_optimization_exempt`, the value of
    `PowerManager.isIgnoringBatteryOptimizations` (`runCatching`, left out when unknown);
  - `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in the manifest;
  - the "Background connection" / "Bakgrunnstilkobling" row in the gated Settings (19b P3). It opens the system
    dialog only when that dialog resolves to an unsuspended system package (in practice: during the pause). Otherwise
    it shows the pause-first text.
- **Server:**
  - `StatusReportRequest.battery_optimization_exempt: Option<bool>` (`#[serde(default)]`);
  - migration `0052_battery_exemption.sql` (`device_status.battery_optimization_exempt INTEGER`);
  - `DeviceStatus.battery_optimization_exempt`;
  - the plain line in `play_card`.
- **Docs:** the `adb shell dumpsys deviceidle whitelist +me.vibb.launcher` line in the setup runbook.
- **Tests:**
  - `PolicyResponseCompatTest`: "battery_optimization_exempt is reported under the server's key and left out when
    unknown";
  - server `src/tests/play.rs`.
- **Next to this design's fields:** `parent_pin_events_v1` (a capability string, no status key),
  `POST /api/devices/pin-events` (§3.3), `device_pin_events` and `device_policy.override_pin_easy` (later migration
  numbers). There is no new policy key on either side. `policy_json_keys_snapshot` and the policy half of
  `PolicyResponseCompatTest` must not change.
- **Overlap:** the 19b row and this design's dialog changes are both in `SettingsFragmentLauncher`. The row reads
  `RestrictionsPause.isActive()` and never calls `ParentPin`.

## 6. Steps (each green on its own; API changes in both directories in one commit)

1. **Launcher: the gate, with 19b phase 1.**
   - **1a (launcher only):**
     - `ParentPinLockout`, `ParentPinStore` (with the migration) and `ParentPin`, with windows started inside
       `check`;
     - all five entries rewired, and every `isLockedOut` in §1 replaced (the hotfix included);
     - the in-dialog errors, the session and screen-off guards, and the strings;
     - events recorded, not uploaded yet.
   - **1b (launcher + server):** 19b phase 1 (§5a).
2. **Server: the card and the warning.**
   - `parent_pin.rs` (`easy`, `easy_candidates`), `update_parent_pin` (the PIN leaves `update_policy`);
   - the card, the in-card notices (kid lock too), the flash texts, the "parent PIN" wording;
   - the `override_pin_easy` migration and the startup sweep.
3. **Both directories, one commit: events.**
   - Server: the endpoint, the `device_pin_events` migration, the security log, the card's list, the notices,
     retention, the capability gating of the old banner.
   - Launcher: `ParentPinEvents.upload`, the `MdmApi` route, the DTOs, `parent_pin_events_v1`.
4. **Docs.** Both `CLAUDE.md`s (the PIN bullets, the device API list, the tables), `PLAN.md`, and an
   implementation-status section here.

## 7. Tests

- **Launcher `ParentPinLockoutTest` (pure):**
  - **Durations:** 15m/30m/1h/2h/4h/8h/16h/24h, `parentLockoutMs(0)` and level 8+ in range.
  - **Escalation:** 5 wrong give level 1, the next 5 give level 2.
  - **A correct PIN** clears failures but keeps the level and decay. A correct 5th try undoes its own lockout and
    event.
  - **A new fingerprint** resets everything.
  - **Decay:** level 3 drops one step per 24 h with no wrong try. A wrong try restarts it. A clock moved back doesn't
    speed it up.
  - **Locked out:** nothing is counted and no event is written.
  - **Too short:** not counted.
  - **Count first:** `beginParentAttempt`'s state already has the failure **and** its pending event.
  - **Same boot:** a wall clock that jumps forward doesn't shorten a window, and one that goes back doesn't stretch it
    past its duration.
  - **New boot:** carry-over by wall clock, a clock that went back gives the full duration, and the window is
    re-anchored.
  - **Migration:** a future `locked_until` gives level 1, ≤ 15 min and 24 h of decay. A far-future one is capped, a
    past one gives no window, and attempts carry over.
  - **`lockoutText`.**
- **Launcher `ParentPinStoreTest` (Robolectric-free, against a fake prefs map):**
  - a type clash and a corrupt `events_v1` each give a result other than `Unavailable`: `LockedOut(15 min)` for the
    first, a normal check for the second (QA #2);
  - a failed commit leaves the in-memory state and queue as they were before the attempt (QA #3).
- **Launcher `ParentPinEventsTest`:**
  - the bound of 100, with `wrong` dropped first;
  - after a 2xx only the sent ids go, and events added meanwhile stay;
  - the batch is kept on 401/404/408/429/5xx and dropped on other 4xx;
  - no field can carry PIN material.
- **Launcher `ParentPinCallersTest`** (source scan, like `KidScreensEscapeTest`):
  - `PinHash.verify(` appears only in `ParentPin.kt` and `PinLockRuntime.kt`;
  - `OfflineOverride.activate(`, `RestrictionsPause.start(` and `startInstallMode(` are called only from
    `ParentPin.kt`;
  - the legacy counter keys appear only in `ParentPinStore.kt`;
  - `verifyPin`/`isLockedOut` are gone;
  - no `Toast` in the four PIN dialogs' result paths;
  - `ParentPinEvents` never references `SyncRunner`.
- **Launcher `PinLockStateTest`:** an `Unlocked` with an old screen-off count is ignored (QA #4).
- **Launcher `PolicyResponseCompatTest`:**
  - "parent PIN events use the server's keys": the encoded batch equals §3.3's literal;
  - 19b's `battery_optimization_exempt` (§5a).
- **Unchanged and still green:** `SettingsGateTest`, `PlayPolicyTest`, `PinBackoffTest`. `TranslationsTest` covers the
  new nb strings.
- **Server `parent_pin::tests`:**
  - `easy` for each kind, wrap-around and 10-digit runs, the list, accepted examples (583920, 4719305);
  - every candidate is `easy`, and every 6-digit PIN that `easy` accepts is a candidate (a full 10^6 loop);
  - `clean_event` (clock-off kept, bad id skipped, out-of-range numbers nulled);
  - every `event_line`/`notices` text.
- **Server `src/tests/parent_pin.rs` (integration):**
  - **Posting:**
    - §3.3's literal is stored, with one security-log row per used/lockout event and none for `wrong`;
    - a re-send stores and logs nothing;
    - an unknown kind is skipped while the rest is stored;
    - non-JSON gets 400, and 101 events get 413;
    - no `command_notify`.
  - **Device page:** the list and the notices; the old banner only without the capability; the notices render inside
    their cards.
  - **`update_parent_pin`:** invalid and kid-PIN writes nothing; an easy PIN is saved with `saved_easy` and
    `override_pin_easy = 1`; clear works; the kid-PIN race guard holds.
  - `update_policy` leaves the PIN alone.
  - **The sweep** flags a stored 123456 and leaves a random PIN at 0.
  - **Retention.**
- **Server unchanged and still green:** `policy_json_keys_snapshot`, `status_report_*`, `tests/step10.rs` (adjusted
  for the moved PIN form).

## 8. Device checks (emulator, then the Jelly Star)

- **Lockout from every entry.**
  - 5 wrong PINs in each of the five entries lock all of them, with the time shown **in the dialog**. Also check this
    on a phone with a screen-time budget (no toasts).
  - On the time-lock screen the 6th try is refused (the bypass regression).
  - The last-try line names the next duration.
  - An empty OK is not counted.
- **Escalation and decay (emulator, root).**
  - After lockout 1 ends (wait, or move the date forward and reboot), 5 more wrong tries give "30 min".
  - A correct PIN, then 5 wrong, gives "1 h", because the level is kept.
  - Move the date 24 h forward and reboot: the next lockout is one step shorter.
- **Reboot during a lockout:** it still runs, for the wall-clock remainder. A date set back across a reboot gives at
  most one full duration.
- **Upgrade with a running 15-minute lockout:** it continues and never gets longer.
- **Settings and the PIN lock:**
  - OK then power within 0.5 s: after unlocking, Settings asks for the PIN again.
  - The same with the kid PIN: the lock stays locked.
- **Offline, then online.**
  - An override, Settings, a pause, install mode and some wrong tries all appear on the device page once.
  - The security log gets the uses and lockouts once.
  - Killing the launcher mid-upload causes no duplicates.
  - An override's event arrives with the sync that ends the override.
- **A new PIN during a lockout:** after the phone syncs, the lockout and the level are gone and the new PIN works.
- **An old server (pre-24) with the new launcher:** the events wait (404) and arrive after the server update.
- **Server:** an easy PIN is saved with the warning shown inside the card. An existing 123456 shows the warning
  after a restart.
- **19b phase 1:** per 19b §5.

## 9. Open questions - answered 2026-10-09

1. A correct PIN keeps the level. It decays one step per 24 h without a wrong try, and a new PIN from the PWA resets
   it and any running lockout (§2.1).
2. A PWA notice now. Web Push (VAPID, a subscriptions table, a `push` handler in `sw.js`, `web-push`, and for iOS
   only the installed PWA) or ntfy (PLAN's plan B) comes later. `device_pin_events` + `notices()` are their hook,
   along with a prompt send (dropped for now, S1).
3. Easy PINs, new and existing: a warning only (§4).
4. Fail closed, as QA #2/#3 narrowed it (§2.3).
5. Wall-clock carry-over across a reboot (§2.1).
6. UTC, following house style.
7. Install mode is logged twice (`play_install_mode` and `parent_pin_used`). Both stay.

Not in scope: Web Push or ntfy, a remote "reset lockout" command, refusing easy PINs or dates, the kid-PIN rules, and
the phone's "unlock code"/"Parent code" wording.

## QA review (design)

QA, 2026-10-09, against 26c7a154 (code at 5c93c3c0). Read:
- the design and `launcher/CLAUDE.md`;
- launcher: `OfflineOverride`, `RestrictionsPause` (`BootClock`, `timedWindowActive`), `PinBackoff`, `PinLockStore`,
  `PinLockRuntime.checkPin`/`checkParentCode`, `PinLockState.step`, `PinLockActivity`, `LockActivity`,
  `SettingsActivity`, `SettingsGate`, `SettingsFragmentLauncher`, `PlayRuntime`/`PlayPolicy`, `MdmSyncWorker`,
  `CrashReports`, `CommandListenerService`;
- server: `devices.rs::update_policy`, `security.rs`, `device_api.rs` (`crash_reports`, `log_play_events`),
  `device_routes.rs`, the routers in `main.rs`, `admin.rs`, `device_detail.html`, `scroll-restore.js`.

**Verdict:** the shape is right: one gate with the lockout inside it, count-first, wall-clock carry-over across a
reboot, and an endpoint of its own. Two findings break the hard rules: #1 is a bypass today, and #2 can lock the parent
out for good. #3-#6 are cheap fixes to the state machine and the dialogs. #7 and #9 need a user decision. S1-S3 remove
about a third of the events and server machinery.

**§0 against the code: all of it holds.**
- `PinHash.verify(` appears only in `OfflineOverride.verifyPin` and `PinLockRuntime.checkPin`, so §1's five entries
  are all of them.
- An old server answers 404 on both listeners: `build_admin_router` has no fallback, and `build_device_router` has
  `not_found`. §3.1's premise holds.
- `BootClock` and `backoffRemainingMs` fit this use. A `BOOT_COUNT` of -1 at both ends is harmless, because the
  wall-clock half of the `max` still holds the lockout.
- The migration (§2.2) is right. A running lockout never gets longer, `pin_fp` stops a self-reset, and a crash between
  its two commits leaves only legacy keys that nothing reads.
- Server card and endpoint:
  - the route belongs in `admin_routes` (`require_full_auth`);
  - CSRF is covered by the session cookie's `SameSite=Strict` (the tower-sessions default, not overridden);
  - no echo: the redirect carries only the code, and the field is never rendered back.

### High

1. **High - the live bypass is real. Close it before step 1.**
   - `LockActivity.showUnlockCodeDialog` checks `isLockedOut()` once, when the dialog opens. Every later OK runs
     `verifyPin`, which has no lockout check, and on a match `activate()`.
   - `verifyPin` resets the counter at each 5th wrong try, so the tries go on at about one per 3 s (≈1,000 an hour)
     during any time lock. With 3-4 digits seen over the parent's shoulder, that takes minutes.
   - The other four entries have no bypass today. The Settings gate and the pause/install dialogs close on the 5th
     wrong try, and the PIN lock re-checks on every OK.
   - Fix now, in a commit of its own: `verifyPin` returns false without hashing while `isLockedOut()`, and the dialog's
     wrong branch closes it on a lockout. Step 1 then replaces both.
2. **High - `Unavailable` has no way out, so an unreadable state locks the parent out for good.**
   - §2.3 refuses whenever "the store throws".
   - Scenario: a later build changes `events_v1`'s shape, or reads a key as the wrong type (`getInt` on a stored Long
     throws `ClassCastException`). From then on, every check fails on load:
     - all five entries say "Couldn't check the code";
     - a new PIN from the PWA doesn't help, because the fingerprint is compared after the failed read;
     - Settings never opens, so the phone can't be re-enrolled.
   - The kid lock's `Unusable` has a way out (the parent code). This one has none.
   - Fix:
     - decode the event queue separately and leniently: a corrupt queue becomes empty and is logged, and it never
       decides the PIN result;
     - under the lock, an unreadable counter state is replaced by a committed level-1 lockout starting now (15 min).
       That fails closed, then gives fresh tries;
     - keep `Unavailable` for a failed commit only, which is transient (#3);
     - test: a corrupt `events_v1` and a type clash each give a result other than `Unavailable` within 15 min.

### Medium

3. **Medium - a failed `commit()` still changes memory, so pressing OK on "Couldn't check" escalates the lockout.**
   - `SharedPreferences.commit()` updates the in-memory map before the disk write and doesn't roll it back.
   - Scenario, with storage full (in the threat model): each OK is counted in memory and returns `Unavailable`. The
     5th press leaves a level-1 window in memory, and about 40 presses reach level 8. Once space is freed, in the same
     process:
     - the next check finds a 24 h lockout, although no PIN was ever hashed;
     - the in-memory `wrong` events are uploaded as real wrong tries.
   - Fix:
     - on a failed commit, write the pre-attempt state back (memory is restored even if the disk write fails again),
       and record nothing;
     - when `filesDir.usableSpace` is low, the text says "The phone's storage is full. Free some space, then try
       again."
   - Also correct §2.3's "like the kid lock's `PinResult.Unusable`". `checkPin` ignores `saveBackoff`'s `false`, and
     only exceptions give `Unusable`. So with storage full the kid PIN keeps working and the parent PIN doesn't. That's
     acceptable, but the design should say so.
4. **Medium - with the Settings gate's check off the main thread, a late OK opens Settings for the next person.**
   - Today `verifyPin` blocks the main thread, so `onStop` can't run in between. In §2.3 the result comes back through
     `lifecycleScope`, which lives until `onDestroy`.
   - Scenario:
     1. The parent types the PIN, taps OK, and presses power within ~0.5 s.
     2. `onStop` resets `gatePassed`, and then the late Ok sets it again.
     3. The kid unlocks the PIN lock, Settings comes back to the front, and `enforceGate` shows it - pause and install
        mode included - without asking for the PIN.
   - Fix: take a session number when the check starts, bump it in `onStop`, and drop an Ok from an ended session. Its
     `unlocked` event stays.
   - The PIN lock has the same pattern today. An Ok (kid PIN or parent code) that lands after a screen-off sends
     `Unlocked`, and `step` goes from LOCKED to UNLOCKED with the screen dark. Drop an Ok that arrives after a ScreenOff
     in the same way.
5. **Medium - PIN messages shown as Toasts are invisible on phones with a budget, including the new last-try warning.**
   - `DISALLOW_CREATE_WINDOWS` is on whenever a screen-time budget is set, and toasts don't show then
     (`launcher/CLAUDE.md`, design 10).
   - Toasts are used for:
     - `LockActivity`'s wrong and locked-out texts;
     - the Settings gate's refusal;
     - the pause and install-mode dialogs.
   - So on the time-lock screen of such a phone, §2.4's `parent_pin_last_try` never shows, and neither does
     `lockoutText`. The warning exists to stop a parent walking into a 4 h lockout. And "every dialog closes" on
     `LockedOut` then leaves no message at all.
   - Fix:
     - one error line inside the dialog for all four dialog entries, like `parent_code_error` in the PIN lock's
       `dialog_pin_parent_code`;
     - on `LockedOut` the dialog stays open, with the text and OK disabled;
     - no toasts for PIN results.
6. **Medium - the wrong-try and lockout events are written after PBKDF2, so tries can be hidden.**
   - Step 4 commits the count, and only step 6 writes the event.
   - A hard reset (power held ~10 s) timed into the 0.5 s hash keeps the count but loses the event. On a 5th try it
     loses the lockout event too.
   - That allows guessing at the full budget with nothing showing in the PWA. The threat model includes power-cycling
     and patience.
   - Fix:
     - step 4's commit already holds the `wrong` or `lockout` event;
     - on a match, step 6 removes that id and adds `unlocked`, in one commit;
     - test: the state from `beginParentAttempt` carries the event.
7. **Medium (user decision) - the guessing bound holds only if the parent never types the PIN.**
   - A correct PIN resets `level`. With one parent use a day, the kid stops after 6 rounds: 30 tries, with the last
     8 h lockout ending at 15h45, so the PIN is free for the parent's next use and its reset.
   - That is about 11,000 tries a year (1.1 % of 6 digits), not ~1,900. With some digits seen, it takes days.
   - Options:
     - (a) keep it, fix §2.1's numbers, and rely on the reports plus a PIN change;
     - (b) a correct PIN clears `failures` and the window but keeps `level`, and only a new PIN resets it. That gives
       35 tries per PIN, then about 5 a day. The cost: the parent's own rare 5 wrong in a row then locks longer, and a
       lockout the kid triggers stays at 24 h until a new PIN.
   - Recommend (b), with the PWA's lockout notice saying "save a new parent PIN to reset it".
8. **Medium - a refused PIN's reason is shown where the parent can't see it.**
   - `device_detail.html:13` renders `notice` above the Status card, and the redirect's `#parent-pin` scrolls past
     it.
   - Scenario: the parent saves 123456. The card looks unchanged, so they believe it was saved. On the phone they type
     it: 5 wrong tries, then a 15 min lockout, then 30 min.
   - Today's `#screen-lock` refusals have the same flaw.
   - Fix: render the parent-PIN notices, the "saved" one included, at the top of the `#parent-pin` card, and the
     kid-lock notices inside `#screen-lock`, not at the top of the page.
9. **Medium (user decision) - birthdays are the kid's first guesses.**
   - This kid knows every family birthday. 5 people in 4 formats is 20 tries, which fits in the first day's budget.
   - What refusing dates costs:
     - 8 digits (DDMMYYYY, MMDDYYYY, YYYYMMDD, years 1900-2099): under 0.1 % of 8-digit PINs;
     - 6 digits (DDMMYY, MMDDYY, YYMMDD): about 10 %, a mild nuisance.
   - Recommend refusing both, with `parent_pin_date`: "Parent PIN not changed: it looks like a date. Pick one that is
     hard to guess." The label should then say "8 digits or more".
   - It's a pure function, with no sweep needed (S2).

### Low

10. **Low - upload status codes, the cap and the keys.**
   - Axum's `Json` answers 422 for a type mismatch, 413 for size and 415 for the content type. §3.2 handles only 400.
     A batch kept on a 422 is re-sent forever, and nothing queued behind it arrives. Fix:
     - the server reads `events` as `Vec<serde_json::Value>` and each one leniently (`kind`/`entry` as strings), so
       only a body that isn't JSON is refused;
     - the launcher drops the batch on any 4xx except 401, 404, 408 and 429.
   - More than 100 events: refuse with 413 rather than storing 100 and answering 204, which makes the phone delete the
     rest. Use one constant, pinned in both tests.
   - Keys: the server's integration test posts §3.3's JSON literal, and `PolicyResponseCompatTest` encodes to the same
     literal.
11. **Low - upload races.**
   - `upload` (in the sync) and `uploadSoon` can overlap. The ids dedupe, but `dropped` is subtracted twice and logged
     twice.
   - Removing the sent ids is a load-modify-commit. It must run under `ParentPin`'s lock, or a `check` committed in
     between is lost.
   - Fix: one mutex for both paths, and the removal under the lock. S1 and S3 remove most of this.
12. **Low - server validation silently drops events.**
   - An event outside 2020..now+1 d is skipped, the phone gets 204, and it deletes the event.
   - Scenario: during an override or pause (auto time is lifted then), the kid moves the clock forward and keeps
     airplane mode on. Every later wrong try is then dropped as implausible.
   - Fix: store such an event with `occurred_at_ms` set to the received time and a "phone clock was off" mark. The
     notices fall back to `received_at`.
13. **Low - the security log floods.**
   - The security page shows the last 200 rows (`admin.rs`). A guessing kid adds up to 35 `parent_pin_wrong` rows a
     day, and every parent visit to Settings adds one more. Sign-ins and bans scroll off the page.
   - Fix:
     - the security log gets `parent_pin_used` and `parent_pin_lockout` only; wrong tries stay on the device card;
     - write the event row and its log row in one transaction, as `crash_reports` does.
14. **Low - an empty or short input costs a try (today too).**
   - Tapping OK on an empty field uses up 1 of the 5 tries.
   - Fix: refuse it locally, uncounted: "Enter 6-10 digits." No PIN that short exists (the server has always required
     at least 6 digits), so the kid gains nothing.
15. **Low - PWA texts and placement.**
   - Put the card right after "Screen lock". That card says "unlock code (offline override PIN, below)"; reword it to
     "parent PIN". Link `managed_without_pin`'s "Set a PIN below" to `#parent-pin`.
   - The PIN input is `type="text"`, but the card's own hint is about a kid watching. Use `type="password"`,
     `inputmode="numeric"` and `autocomplete="new-password"`.
   - The use notice ends every use with "Typed where your child could see?", so the parent gets it after each of their
     own visits to Settings. Keep that tail for `override` only, so the lockout and wrong-try notices don't get lost.
   - Add the way out to the phone's locked-out text and to the PWA's lockout line: "A new parent PIN from the parent
     page ends this." A kid can hold the lockout at 24 h by typing 5 wrong codes each time it ends. If the phone is
     offline (server down, which is exactly when the parent needs Settings), the parent then waits up to 24 h with the
     phone in hand. That's not for good, but the card should say so.
   - Every outcome redirects with `#parent-pin`, so the no-jump rule holds.
16. **Low - the common list misses frequent shapes.**
   - Missing: 100000, 200000, 232323, 147147, 741852, 963852, 852456, 456123, 321654, 951753, 357159.
   - Two rules cover most of the list and these:
     - "at most 2 distinct digits", which refuses 0.28 % of 6-digit PINs;
     - "a block repeated" (123123, 520520, 12341234).
   - Both work only without the sweep (S2): the 10-digit PINs with at most 2 distinct digits alone are 46k candidates.
17. **Low - nits.**
   - `parentLockoutMs(0)` shifts by -1, and Kotlin then uses only the low 6 bits of the shift count. Use
     `coerceIn(0, 7)`.
   - §6 step 1's list misses the `isLockedOut` call in `SettingsFragmentLauncher.showPausePinDialog`. The scan test
     would catch it.
   - Lock order: `ParentPin` first, then `PinLockRuntime` (in `checkParentCode`), never the reverse.

### Simplification

- S1. **Drop `uploadSoon`.**
  - There is no push, so the parent sees an event only when they open the PWA.
  - On an online phone the next sync is at most 30 min away (the backstop), and the override's event arrives in the
    very sync that ends it.
  - This removes the second client path, the single-flight, the timeout, the `SyncRunner` scan rule and most of #11.
    Bring it back with Web Push or ntfy (Q2).
  - The endpoint stays: §3.1's first reason (an old server answers 204 and loses the events) still holds.
- S2. **Drop the startup sweep.**
  - Replace the three-state `override_pin_weak` with one column, `override_pin_checked`: 0 by default, 1 when saved
    through `update_parent_pin`.
  - A set PIN at 0 shows "Set before easy PINs were refused - save a new one to have it checked."
  - Today there is one install and one test phone.
  - This also frees the rules from the 176-candidate limit (#9, #16).
- S3. **Drop `dropped`.**
  - The eviction keeps the lockouts and drops `wrong` events first, and a lockout event already means "5 wrong".
  - This removes the counter, its subtraction, the double log and `parent_pin_events_dropped`.
- S4. **(optional) Start the window inside `check`** (for OVERRIDE, PAUSE and INSTALL_MODE) instead of
  `ParentPinGrant` + `require`.
  - A source scan ("the starters are called only from `ParentPin.kt`") gives the same guarantee.
  - An `unlocked` event can then no longer exist without its window. Today that happens when the dialog's coroutine is
    cancelled mid-check (the time lock ended and `LockActivity` finished).

### Open questions

1. Agree: a new PIN resets. Only the parent's session can do it. It fails when:
   - the phone is offline (expected);
   - the save was refused (the parent must see why, #8);
   - storage is full (#3).

   See #7 for the `level` choice.
2. Agree. With S1, there is no prompt send until push exists.
3. Use S2 instead of the sweep.
4. Agree, but narrower:
   - `Unavailable` only for a failed commit, with memory restored (#3);
   - an unreadable state becomes a 15-minute lockout (#2).
5. Agree. What remains:
   - a clock moved forward during an override or pause (auto time lifted) gives one extra round per override, then
     nothing;
   - a drained battery's clock reset gives at most one extra duration.
6. Agree.
7. Agree.

**Needs a user decision:**
- #7: does a correct PIN reset `level`?
- #9: refuse dates?
- #1: does the hotfix ship before step 1?

## Decisions after QA review

The user's answers (2026-10-09):
- **#7 / Q1:** a correct PIN keeps the level. The level decays one step per 24 h without a wrong try, on the lockout's
  clock rules. A new PIN from the PWA resets the level and a running lockout.
- **#9 and the easy-PIN rule:** the code is the parents' choice. Easy PINs (repeats, runs, the common list) are saved
  with an "easy to guess" warning. There is no date check, and existing easy PINs get the same warning.
- **#1:** the hotfix landed as ec58d6d9, and step 1 replaces it.
- **#2/#3:** take QA's fixes.
- **Q2-Q6:** the recommendations stand.
- **Design 19b phase 1** ships with step 1, by the same implementer (§5a).

One line per finding:
1. **Accepted.** The hotfix ec58d6d9 is on main (§0). Step 1's gate replaces it (§1, §6).
2. **Accepted.** An unreadable state becomes a committed level-1, 15-minute lockout. The queue is decoded separately
   and leniently. `Unavailable` is only for a failed commit (§2.3), and there is a test (§7).
3. **Accepted.** A failed commit writes the pre-attempt state back, records nothing, and gives the storage-full
   text. The kid-PIN difference is stated (§2.3).
4. **Accepted.** The Settings session number and the PIN lock's screen-off count drop a late `Ok`, and its event
   stays (§2.3). Tests and a device check are added.
5. **Accepted.** One in-dialog error line for all four dialogs. On `LockedOut` the dialog stays open with OK
   disabled. No toasts for PIN results (§2.4).
6. **Accepted.** The `wrong`/`lockout` event is committed with the count before PBKDF2, and replaced by `unlocked` on a
   match (§2.1 step 4/6).
7. **Changed per the user.** QA's option (b), plus a decay: one step per 24 h without a wrong try. A new PIN resets it
   (§2.1). The bounds are restated: about 2,000 tries a year whether or not the parent uses the PIN.
8. **Accepted.** Notices render inside their own cards, the kid-lock notices included (§5).
9. **Rejected per the user.** No date refusal. Easy PINs are not refused at all, only warned about (§4). The label
   mentions birthdays and years.
10. **Accepted.** The server parses leniently: 400 only for non-JSON, 413 above `PIN_EVENTS_MAX`. The launcher
    drops the batch on a 4xx other than 401/404/408/429. The JSON literal is pinned on both sides (§3.2-§3.4).
11. **Accepted.** With S1 there is one upload path, behind the sync mutex. The snapshot and the removal by id run
    under `ParentPin`'s lock (§3.2).
12. **Accepted.** Implausible times are kept, with the received time and `clock_off = 1`. Bad `until_ms`/`failures`/
    `level` are nulled, not dropped (§3.4).
13. **Accepted.** The security log gets used and lockout events only, in one transaction with the event row. Wrong
    tries stay on the device card and its notice (§3.4). The lockout row already means 5 wrong tries.
14. **Accepted, narrowed.** Under 6 characters is refused locally and not counted. Longer input is not refused,
    because PINs saved before the 10-digit bound may be longer (§2.1 step 3).
15. **Accepted, with one change.**
    - Accepted: the card goes after "Screen lock", the wording becomes "parent PIN", the banner links to the card, the
      input is a password field, and the way out ("save a new parent PIN") is on the phone's locked-out text and the
      PWA's lockout line. The offline wait of at most 24 h is stated on the card.
    - The change: the "Typed where your child could see?" tail goes on `override` **and `pin_lock`**. Both are screens
      the kid uses (§5).
16. **Partly accepted.** QA's 11 missing PINs join `COMMON_PINS`. The two broader rules are not taken: the user wants
    existing PINs to get the same warning, and the sweep needs an enumerable set (187 candidates, §4).
17. **Accepted.** `coerceIn(0, 7)`. `showPausePinDialog`'s `isLockedOut` is in §1's list. The lock order is stated
    (§2): `check` nests nothing, and `ParentPin` comes first if anything is ever nested.
- **S1, accepted.** No `uploadSoon`. Events go with the sync. A prompt send comes back with push (§3.2, §9).
- **S2, rejected per the user.** Existing easy PINs get the same warning, so the one-time sweep stays (§4). Its cost
  is seconds to a minute of one core per device, once.
- **S3, accepted.** No `dropped`. The eviction drops `wrong` first (§3.2).
- **S4, accepted.** The windows start inside `check`, from one `WindowStart` that the event also carries, with the
  event committed first. The grant class goes, and a source scan pins the three starters (§2, §7).
- **QA's open-questions notes:** all taken. Q5's residual risk (one extra round per override or pause, at most one
  extra duration after a clock reset) is stated in §2.1.
