# 24 - Parent PIN hardening

User decisions (2026-10-09): the parent PIN on the kid's phone (the offline override PIN) gets an escalating
lockout, every use and every wrong try is reported to the PWA, the server refuses easy PINs, and the PWA makes
changing the PIN easy. Threat model: the kid guessing on the phone itself. The hash never leaves CE storage, there is
no backup, and `DISALLOW_DEBUGGING_FEATURES` blocks adb, so offline cracking is out of scope. The rules for this design:
no bypass, never lock the parent out for good, and no machinery beyond what these four decisions need. A QA design
review follows.

## 0. Today (verified 2026-10-09)

- `server/OfflineOverride.kt`: `verifyPin()` runs PBKDF2 against the cached `override_pin_hash`/`_salt`, with
  `MAX_FAILED_ATTEMPTS` = 5 and then a 15-minute lockout by wall clock. The counter resets when a lockout starts, so
  that allows 480 tries a day. The state lives in the default (CE) prefs `offline_override_failed_attempts` and
  `offline_override_locked_until`, which are written with `apply()`. The function is not synchronized, it is
  counted *after* the hash runs, and **`verifyPin()` never checks the lockout itself**. Each caller is expected to
  check it.
- **Bypass found:** `ui/LockActivity.showUnlockCodeDialog` checks `isLockedOut()` only when the dialog opens. The
  dialog stays open after a wrong code, and every later OK runs `verifyPin` + `activate()`. During a lockout, a
  match still lifts every restriction for 2 h. So on the time-lock screen the lockout does nothing, and tries are
  unlimited (one per typed code).
- `SettingsActivity.showPinGate` and `SettingsFragmentLauncher.showPausePinDialog` run PBKDF2 on the main thread
  (~0.5 s of jank). They do check the lockout correctly.
- `offlineOverrideUsedPendingReport` is a single boolean, cleared after any successful status report. A use that
  happens while a report is in flight is lost. Nothing reports Settings, the pause, install mode, the PIN lock's
  "Parent code" or wrong tries. Install mode shows up only indirectly, as the `play_install_mode` security-log line
  (from the status diff).
- Server `devices.rs::update_policy` (~1703): any typed PIN of 6 or more digits is hashed, and anything else is
  **silently ignored**. There is no upper bound (the field says 6-10). A PIN that matches the kid's is refused
  (`override_is_kid_pin`). The PIN fields sit inside the big "Play and kiosk" form.
- A successful policy sync ends the offline override (`MdmSyncWorker`: `OfflineOverride.clear()`). Any reporting
  this design adds must therefore **not** trigger a sync.
- `lock/PinBackoff.kt` (the kid PIN) already does almost everything needed. It has the three clocks (`LockClocks`,
  `BackoffWindow`, `backoffRemainingMs`), counts the failure before PBKDF2 and commits it, resets on a new PIN
  (`backoffForHash`), and its store uses `commit()`. This design reuses those types.

## 1. Entry points (all of them - grep `verifyPin`, `PinHash.verify`, `dialog_offline_override_pin`)

| # | Entry (`entry` wire value) | Where | On a correct PIN | Window |
|---|---|---|---|---|
| 1 | `override` | `LockActivity` "Enter unlock code" (time lock / budget) | `OfflineOverride.activate` | 2 h, ends early at the next successful sync |
| 2 | `pin_lock` | `PinLockActivity` "Parent code" -> `PinLockRuntime.checkParentCode` | opens the PIN lock only, resets the kid backoff | - |
| 3 | `settings` | `SettingsActivity` gate (every `onCreate`/`onStart`) | Settings visible until `onStop` | session |
| 4 | `pause` | Settings "Pause all restrictions" | `RestrictionsPause.start` | 2 h (`RESTRICTIONS_PAUSE_DURATION_MS`) |
| 5 | `install_mode` | Settings "Install from Play" | `PlayRuntime.startInstallMode` | 15 min (`INSTALL_MODE_DURATION_MS`) |

Pre-checks that read the lockout: `SettingsGate.settingsAccess(lockedOut)`, `PlayPolicy.canStartInstallMode(pinLockedOut)`
and the `isLockedOut()` checks in the dialogs. All five entries and all the pre-checks share one state. Nothing else verifies
the override hash.

## 2. Launcher: one gate, `ParentPin`

New `server/ParentPin.kt` (object), `server/ParentPinLockout.kt` (pure, JVM-tested) and `server/ParentPinStore.kt`.
`OfflineOverride.verifyPin` and `isLockedOut` are removed.

```kotlin
enum class ParentPinEntry(val wire: String, val windowMs: Long?) {
    OVERRIDE("override", OVERRIDE_DURATION_MS /* made internal */), PIN_LOCK("pin_lock", null), SETTINGS("settings", null),
    PAUSE("pause", RESTRICTIONS_PAUSE_DURATION_MS), INSTALL_MODE("install_mode", INSTALL_MODE_DURATION_MS),
}
sealed interface ParentPinResult {
    data class Ok(val grant: ParentPinGrant) : ParentPinResult
    data class Wrong(val triesLeft: Int, val nextLockoutMs: Long) : ParentPinResult
    data class LockedOut(val remainingMs: Long) : ParentPinResult
    data object NotConfigured : ParentPinResult
    data object Unavailable : ParentPinResult
}
class ParentPinGrant(val entry: ParentPinEntry)  // constructed only in ParentPin.kt (source-scan test)

@Synchronized fun check(context, entry, pin): ParentPinResult   // background thread only
@Synchronized fun lockoutRemainingMs(context): Long             // for the pre-checks; 0 = none
```

**The window starters require the grant.** These become `OfflineOverride.activate(context, grant)`,
`RestrictionsPause.start(grant)` and `PlayRuntime.startInstallMode(context, grant)`. Each one checks
`require(grant.entry == ...)`. A future entry point can't start a window without going through `check`. The lockout
test lives inside `check`, so a dialog that stays open can't skip it, which fixes the LockActivity bypass.

### 2.1 State machine (pure, `ParentPinLockout.kt`)

```kotlin
const val PARENT_FREE_TRIES = 5
const val PARENT_LOCKOUT_BASE_MS = 15 * 60_000L
const val PARENT_LOCKOUT_CAP_MS = 24 * 60 * 60_000L
data class ParentLockoutState(
    val failures: Int = 0,           // wrong tries since the last lockout began or the last correct PIN (0..4)
    val level: Int = 0,              // lockouts since the last correct PIN (stored capped at 99)
    val window: BackoffWindow? = null, // the running/last lockout (lock/PinBackoff.kt type)
    val pinFingerprint: String? = null, // which parent PIN this is about (KidLockConfig.fingerprint's recipe)
)
fun parentLockoutMs(level: Int) = min(BASE shl (level - 1).coerceAtMost(7), CAP)  // 15m,30m,1h,2h,4h,8h,16h,24h,24h...
```

- **Order inside `check`.** All of these steps run under one lock:
  1. Load the state, then apply `forParentPin(state, fp)`. A different fingerprint, meaning a new PIN from the
     server, gives a fresh state.
  2. Apply `refreshParentLockout(state, now)`.
  3. If `parentLockoutRemaining > 0`, return `LockedOut`. No hash runs and nothing is counted, so a kid hammering
     during a lockout creates no events.
  4. Apply `beginParentAttempt`: `failures + 1`. On the 5th, `level + 1`, `failures = 0` and
     `window = backoffWindow(now, parentLockoutMs(level))`. The state is **committed before PBKDF2**. If `commit()`
     returns false or the store throws, return `Unavailable` and refuse (§2.3).
  5. Run PBKDF2.
  6. On a match, apply `parentAttemptSucceeded` (failures 0, level 0, no window) and commit it in the same edit as the
     success event. Otherwise keep the counted state and add the wrong-try or lockout event in one commit.
- **Within one boot** the existing `backoffRemainingMs` decides. The lockout lasts while elapsed realtime or the wall
  clock says time remains. A wall clock that went back is ignored, and the result is capped at the duration.
- **Across a reboot** (another `BOOT_COUNT`, or elapsed realtime went back), `refreshParentLockout` re-anchors the
  window to this boot:
  - `remaining = wallStart + duration - nowWall`, clamped to `0..duration`;
  - if `nowWall < wallStart` (the clock went back past the start), `remaining = duration`;
  - the new window is `backoffWindow(now, remaining)`, committed.

  This deliberately differs from the kid backoff, which restarts the full wait on every boot. At 24 h, that rule
  would let daily reboots or a flat battery keep the parent out indefinitely. Carrying the wall clock over can only
  shorten a lockout if the wall clock jumps forward across a reboot. `DISALLOW_CONFIG_DATE_TIME` and auto time
  prevent that while the phone is managed. Even then, the level persists: each shortened lockout still allows only 5
  tries and the next one doubles.
- **Bounds.**
  - Every path is capped at 24 h. A new boot gives at most `duration`. Migration gives at most 15 min.
  - After a lockout the parent always gets 5 fresh tries.
  - Possible tries: 35 on the first day of continuous guessing, then about 5 a day, so about 1,900 a year (about
    0.2 % of the 6-digit space). Today it is 480 a day, and unlimited on the time-lock screen.
- **Reset.** The escalation resets on a correct PIN, and on a new PIN from the server through the fingerprint (§9 Q1).
  A new PIN is the only remote way out, and it matters because the kid can trigger the lockout on purpose. Nothing
  decays with time (the user's decision).

### 2.2 Store and migration

- CE prefs `parent_pin_state`, every write `commit()`, the same rules as `PinLockStore` (never in DE storage; excluded
  from backup by the existing rules).
- Keys: `version`, `failures`, `level`, `wall_start`, `elapsed_start`, `boot`, `duration`, `pin_fp`, `events_v1`
  (JSON), `dropped`.
- **Migration of existing phones.** It runs once, on the first load with no `version` key, inside the same lock:
  - `failures = offline_override_failed_attempts.coerceIn(0, 4)`.
  - If `offline_override_locked_until > nowWall`, a lockout is running: set
    `level = 1` and `window = backoffWindow(now, min(lockedUntil - nowWall, 15 min))`. A far-future value from a bad
    clock is capped at 15 min. Otherwise `level = 0` with no window.
  - `pin_fp` is the current PIN's fingerprint, so the migration doesn't reset itself.
  - Commit `version = 1`, then zero the two legacy keys.

  The two `LauncherPreferences` declarations stay, marked legacy, for this read. A running 15-minute lockout keeps
  running (never longer), and an old counter carries over.
- The legacy `offline_override_used_pending_report` flag and the status field `offline_override_used` stay as they
  are, for servers older than this design.

### 2.3 Failure modes

- **The store can't be read or committed:** `Unavailable`, shown as "Couldn't check the PIN. Try again." This fails
  closed, like the kid lock's `PinResult.Unusable`. A store that can't take a commit would otherwise give 5 tries per
  reboot. The parent still has the PWA. The kid's PIN still opens the PIN lock.
- **No hash, or an unusable one** (`PinHash.usable`): `NotConfigured`, the same texts as today.
- **Main thread:** `check` never runs on it. The Settings gate and the pause/install dialogs move to
  `lifecycleScope` + `Dispatchers.Default` (the OK button is disabled while a check runs, as in `LockActivity`).
  `lockoutRemainingMs` does no hashing. It can wait for a running `check` (≤ 0.5 s) because they share the lock.
  That's accepted, since a pre-check and a running check are rare together.

### 2.4 Phone texts (en + nb, `TranslationsTest`)

- `lock_unlock_code_locked_out` and `pin_lock_parent_locked_out` get a placeholder: "Too many wrong codes. Try again
  in %1$s." / "For mange feil koder. Prøv igjen om %1$s." The value comes from `lockoutText(ms)`, which rounds up to
  the minute: "15 min", "1 h 45 min", "24 h".
- New `parent_pin_last_try`, shown instead of the plain "wrong" text when `triesLeft == 1`: "Wrong code. One more
  wrong try locks it for %1$s." (`nextLockoutMs`). This stops a parent from walking into a 4-hour lockout.
- New `parent_pin_unavailable`: "Couldn't check the code. Try again."
- On `LockedOut`, every dialog closes, as Settings does today. The time-lock screen's dialog closes too.

## 3. Events: queued on the phone, own endpoint

### 3.1 Why not the status report

- **An older server swallows unknown status keys and answers 204.** The phone would then drop events that were never
  stored. A separate endpoint gets a 404 from an old server, and the events wait, as with `CrashReports`.
- **The status report is built only inside a sync, and a sync ends the override.** Sending an override event soon
  after the parent typed the PIN therefore needs a call that isn't a sync.

### 3.2 Phone side (`server/ParentPinEvents.kt`, pure queue + upload)

- **Events.** They are recorded only by `ParentPin.check`, in the same commit as the state change:
  - `unlocked`: every correct PIN, with `until_ms = at + entry.windowMs` for override, pause and install mode.
  - `wrong`: tries 1-4 of a round, with `failures`.
  - `lockout`: the 5th wrong try, with `level` and `until_ms`.

  Events carry no digits, length or other PIN material.
- **Queue.** At most `MAX_PARENT_PIN_EVENTS = 100`. When it is full, the oldest `wrong` event goes first, and only
  then the oldest overall. Each drop is counted in `dropped`. Lockouts are rate-limited by their own duration, so 100
  events cover about two weeks of continuous guessing offline.
- **Ids.** `id` is 128 random bits as 32 lowercase hex characters (`SecureRandom`).
- **Upload: `upload(context, api)`.**
  - It runs after the status report in `performMdmSync`, next to `CrashReports.upload`.
  - It sends a snapshot. After a 2xx it removes **only the sent ids** and subtracts the `dropped` it sent. Events
    added during the upload survive (the race that `offlineOverrideUsedPendingReport` has).
  - A 400 means the server can't ever accept this batch: remove it and log. A 404 (older server), any 5xx or a
    network failure keeps it.
- **Prompt send: `uploadSoon(context)`.**
  - It runs after an `unlocked` or `lockout` event: one coroutine on IO, single-flight (`AtomicBoolean`), using
    `createMdmApi(serverUrl, token)` as `AppInstallReceiver` does, with a 15 s timeout and no retry.
  - It never calls `SyncRunner` and never fetches the policy, so the override isn't ended by its own report.
  - `wrong` events ride along with the next upload.
- **Enrollment** (a new device token) clears the queue. The lockout state is kept, because it is about the PIN.
- **Capability:** `parent_pin_events_v1` is added to `STATUS_CAPABILITIES`.

### 3.3 Contract: `POST /api/devices/pin-events` (bearer) -> 204

```json
{ "events": [ { "id": "3f2a...32hex", "kind": "unlocked", "entry": "override",
                "at_ms": 1760000000000, "until_ms": 1760007200000, "failures": null, "level": null } ],
  "dropped": 0 }
```

- `kind` is one of `unlocked | wrong | lockout`. `entry` is one of
  `override | pin_lock | settings | pause | install_mode`.
- `until_ms`, `failures` and `level` may be null or left out.
- Launcher DTO: `ParentPinEventBatch` / `ParentPinEvent` (snake case through `ServerJson`).
- Server structs: `PinEventBatch` / `PinEventReport`, with `#[serde(default)]` on `dropped` and the optional fields.

### 3.4 Server side

- **Migration** (the next free number, 0052 today):

  ```sql
  CREATE TABLE device_pin_events (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
      event_id TEXT NOT NULL, kind TEXT NOT NULL, entry TEXT NOT NULL,
      occurred_at_ms INTEGER NOT NULL, until_ms INTEGER, failures INTEGER, level INTEGER,
      received_at TEXT NOT NULL DEFAULT (datetime('now')),
      UNIQUE (device_id, event_id));
  CREATE INDEX device_pin_events_recent ON device_pin_events (device_id, occurred_at_ms DESC);
  ALTER TABLE device_policy ADD COLUMN override_pin_weak INTEGER; -- NULL = not checked (§4)
  ```

- **Handler** `device_api::pin_events`:
  - At most 100 events are taken.
  - Each event is checked by `parent_pin::clean_event`, and invalid ones are skipped and logged:
    - `id` must be 32 hex characters, and `kind`/`entry` must be known values;
    - `at_ms` must lie between 2020-01-01 and now + 1 day;
    - `until_ms` must lie between `at_ms` and `at_ms` + 24 h + 1 min;
    - `failures` must be 1..=4 and `level` 1..=99.
  - Each clean event goes in with `INSERT OR IGNORE`. **Only when a row was inserted** does the handler also write a
    security-log row, so a re-sent batch never logs twice:
    - event type `parent_pin_used`, `parent_pin_wrong` or `parent_pin_lockout`;
    - detail `device {id}: {line}`, where the line is built only from the validated enums and numbers, never from
      phone text;
    - `dropped > 0` logs `parent_pin_events_dropped`.
  - The response is 204, and a body that won't parse gets 400.
  - It does **not** send `command_notify`, because a nudge would sync the phone and end the override.
- **Retention:** `retention::prune` deletes `device_pin_events` rows older than 90 days (by `received_at`).

## 4. Server: PIN validation (`src/parent_pin.rs`, pure + unit tests)

- **`refusal(pin) -> Option<ParentPinRefusal>`**, checked in this order:
  - `Invalid`: not 6-10 ASCII digits.
  - `Repeated`: all digits the same.
  - `Sequence`: every step is +1, or every step is -1, mod 10. This catches 123456, 987654, 890123, 1234567890 and
    0987654321.
  - `Common`: an exact match in `COMMON_PINS`. That's a short list of patterns the rules above miss: 121212, 123123,
    112233, 123321, 131313, 101010, 202020, 696969, 789789, 520520, 102030, 159753, 147258, 258369, 147369, 159357,
    789456, 147852, 123654, 111222, 12121212, 12341234, 11223344, 123123123, 1122334455, 1212121212.
  - The kid's PIN is still refused separately (`override_conflicts`).

  Birthdays are not refused: dates in any format are several percent of all 6-digit PINs, and the server can't know
  which dates matter.
  The input label tells the parent to avoid them.
- **Its own card and handler.** The PIN leaves `update_policy`, whose SQL stops touching the PIN columns. A new
  `POST /devices/{id}/parent-pin` (`devices::update_parent_pin`) takes `new_pin` or `clear_pin`:
  - It keeps the kid-PIN race guard (`CASE WHEN ? IS NULL AND kid_pin_hash IS NOT NULL ...`) and `spawn_blocking`.
  - It writes `override_pin_weak = 0` with a new hash, and `NULL` when the PIN is cleared.
  - It keeps the security events `override_pin_changed`/`_cleared` and nudges the phone.
  - It redirects to `?notice=<code>#parent-pin`. Today's silent ignore becomes an explicit refusal: nothing is written.
- **Existing PINs: a warning, not a refusal or a lockout.**
  - At startup, a background task (`tokio::spawn` + `spawn_blocking`, one device at a time) checks every device with
    a hash and `override_pin_weak IS NULL`.
  - It runs `security::verify_pin` against `parent_pin::weak_candidates()`. That's every Repeated and Sequence PIN of
    lengths 6-10 plus `COMMON_PINS`: about 176 candidates, one time per device, at 210k PBKDF2 rounds each, so tens of
    seconds of one core on an aarch64 server.
  - It then runs `UPDATE ... SET override_pin_weak = ? WHERE device_id = ? AND override_pin_hash = ?`, so a PIN
    changed meanwhile isn't overwritten.
  - `DevicePolicy` gets `override_pin_weak: Option<bool>`. The policy JSON is unchanged, so
    `policy_json_keys_snapshot` doesn't change.

## 5. PWA (English)

- **The name in the PWA is "parent PIN".** It replaces "unlock code (offline override PIN)" in
  `device_detail.html`, the `managed_without_pin` banner, the kid-lock card and the `kid_lock::flash_text` strings.
  The codes stay the same. The phone's own texts are unchanged.
- **New card `id="parent-pin"`**, moved out of the "Play and kiosk" form:
  - **h2:** "Parent PIN"
  - **Intro:** "The parent PIN works on the phone even when it can't reach this server. It opens the launcher's
    Settings (and there, pausing all restrictions and Play install mode). It is the "Parent code" on the kid's lock
    screen, where it opens only the lock. On a time lock, "Enter unlock code" lifts the app restrictions and time
    rules for up to two hours. They come back as soon as the phone reaches this server. Calls stay within the call
    rules and phone hardening stays. It must differ from the kid's PIN."
  - **Lockout rule:** "After 5 wrong tries the phone stops accepting the parent PIN for 15 minutes. Each further
    lockout doubles (30 minutes, 1 hour, 2 hours, ...) up to 24 hours. The right PIN resets this, and so does a new
    PIN saved here once the phone has checked in."
  - **Hint:** "Typed the PIN where your child could see it? Change it here. The phone uses the new PIN as soon as it
    checks in, usually within seconds."
  - **Weak warning** (`override_pin_weak = 1`): "This parent PIN is easy to guess (all one digit, a run like 123456,
    or a common PIN). Change it below."
  - **Form:** label "New parent PIN (6-10 digits; avoid birthdays and years)"; button "Save parent PIN"; today's
    "Remove the PIN" checkbox, retitled.
  - **h3 "Reported by the phone":** the last 10 events, newest first, as "{when}: {line}".
    - `{when}` is "2026-10-09 14:02 UTC (3 h ago)", following house style.
    - Empty: "The phone hasn't reported any use of the parent PIN yet."
    - A phone without `parent_pin_events_v1`: "This phone's launcher doesn't report parent-PIN use yet; it will after
      its next update."
- **Lines** (`parent_pin::event_line`). The same text goes to the security log.
  - unlocked/override: "Restrictions lifted on the time-lock screen, until {until} at the latest."
  - unlocked/pin_lock: "Lock screen opened with the parent code."
  - unlocked/settings: "The launcher's Settings opened."
  - unlocked/pause: "All restrictions paused from Settings, until {until}."
  - unlocked/install_mode: "Play install mode started, until {until}."
  - wrong: "Wrong parent PIN {where} ({failures} in a row)." Here `{where}` is one of "on the time-lock screen",
    "on the lock screen", "for Settings", "for pausing restrictions", "for Play install mode".
  - lockout: "5 wrong parent PINs {where}: the parent PIN is locked on the phone for {duration}, until {until}
    (lockout {level} in a row)."
- **Status-card notices** (`parent_pin::notices(events, now)`, at most two, from the last 24 h of the phone clock):
  - The latest use: "The parent PIN was used on the phone {ago}: {line}". With more uses it adds " ({n} more in the
    last 24 hours - see Parent PIN)", and it always ends with " Typed where your child could see? Change the parent
    PIN." (a link to `#parent-pin`).
  - A lockout (`error` style): "The parent PIN was locked on the phone after repeated wrong tries (until {until}). If
    that wasn't you, your child may be guessing it - change it under Parent PIN."
  - Otherwise, if there were wrong tries: "{n} wrong parent PINs were typed on the phone in the last 24 hours. If
    that wasn't you, consider changing the PIN."
- **The old `offline_override_used` banner** shows only when the latest status lacks `parent_pin_events_v1`.
- **Flash texts** (`?notice=`):
  - `parent_pin_saved`: "Parent PIN saved. The phone uses it as soon as it checks in, and a lockout running there ends then."
  - `parent_pin_cleared`: "Parent PIN removed."
  - `parent_pin_invalid`: "Parent PIN not changed: it must be 6 to 10 digits."
  - `parent_pin_repeated`: "Parent PIN not changed: all its digits are the same. Pick one that is hard to guess."
  - `parent_pin_sequence`: "Parent PIN not changed: it is a run of digits like 123456 or 987654. Pick one that is
    hard to guess."
  - `parent_pin_common`: "Parent PIN not changed: it is one of the most common PINs. Pick one that is hard to guess."
  - `override_is_kid_pin`: "Parent PIN not changed: it must not be the kid's PIN."
  - `override_needed_by_lock`: reworded with "parent PIN".
- **Security log subtitle:** "... and successful sign-ins, plus parent-PIN use reported by the phones."
- **Push:** a PWA notice now, Web Push as a later option (§9 Q2).

## 6. Steps (each one green on its own; API changes in both directories in one commit)

1. **Launcher: the gate.**
   - `ParentPinLockout`, `ParentPinStore` (with the migration), `ParentPin` and `ParentPinGrant`.
   - All five entries rewired. The grant-taking starters.
   - `lockoutRemainingMs` replaces `isLockedOut` in `SettingsActivity`, `PlayRuntime.canStart`, `LockActivity` and
     `PinLockActivity`.
   - The strings (§2.4). The events are recorded but not uploaded yet.
2. **Server: the validation and the card.**
   - `parent_pin.rs`, `update_parent_pin` (the PIN leaves `update_policy`), the card, the flash texts and the
     "parent PIN" wording.
   - The `override_pin_weak` column and the startup sweep.
3. **Both directories, one commit: events.**
   - Server: the endpoint, the table, the security log, the card's list, the notices, retention, the capability
     gating of the old banner.
   - Launcher: `ParentPinEvents.upload`/`uploadSoon`, the `MdmApi` route, the DTOs, `parent_pin_events_v1`.
4. **Docs.** Update both `CLAUDE.md`s (the PIN bullets, the device API list, the tables), `PLAN.md`, and an
   implementation-status section here.

## 7. Tests

- **Launcher `ParentPinLockoutTest` (pure):**
  - The durations 15m/30m/1h/2h/4h/8h/16h/24h/24h, and no overflow at level 99.
  - 5 wrong tries give level 1. The next 5 give level 2 (30 min).
  - A correct PIN resets failures, level and window. A correct 5th try undoes the lockout it just started.
  - Nothing is counted during a lockout.
  - Count-first: the state from `beginParentAttempt` already has the failure.
  - Same boot: a wall clock that jumps forward doesn't shorten the lockout, and one that goes back doesn't stretch it
    past the duration.
  - New boot: carry-over by wall clock, a clock that went back gives the full duration, nothing ever exceeds the
    duration, and the result is re-anchored.
  - A new fingerprint resets.
  - Migration: a future `locked_until` gives level 1 and ≤ 15 min; a far-future one is capped; a past one gives no
    window; attempts carry over.
  - `lockoutText`.
- **Launcher `ParentPinEventsTest`:**
  - The bound is 100 and `wrong` events are dropped first, with `dropped` counted.
  - After a 2xx only the sent ids go, and events added during the upload stay.
  - 404/5xx keep the batch and 400 drops it.
  - Events have no field that could carry PIN material.
- **Launcher `ParentPinCallersTest`** (source scan, like `KidScreensEscapeTest`):
  - `PinHash.verify(` appears only in `ParentPin.kt` and `PinLockRuntime.kt` (the kid PIN).
  - `ParentPinGrant(` appears only in `ParentPin.kt`.
  - The legacy counter keys appear only in `ParentPinStore.kt`.
  - `verifyPin`/`isLockedOut` are gone.
  - `ParentPinEvents` never references `SyncRunner` or `performMdmSync`.
- **Launcher `PolicyResponseCompatTest`:** "parent PIN events use the server's keys". The encoded batch has exactly
  `events`/`dropped`, and an event has `id`, `kind`, `entry`, `at_ms`, `until_ms`, `failures` and `level`.
- **Unchanged and still green:** `SettingsGateTest`, `PlayPolicyTest`, `PinBackoffTest`. `TranslationsTest` covers
  the new nb strings.
- **Server `parent_pin::tests`:**
  - Every refusal kind, including wrap-around runs and 10-digit runs.
  - Accepted examples (583920, 4719305).
  - `weak_candidates()` contains every rule match of lengths 6-10 and the whole list.
  - `clean_event` bounds.
  - Every `event_line`/`notices` text.
- **Server `src/tests/parent_pin.rs` (integration):**
  - A batch is stored with one security-log row per event. A re-send stores nothing and logs nothing. An unknown
    kind is skipped while the rest is stored. A body that won't parse gets 400.
  - The endpoint never nudges (the `command_notify` receiver stays empty).
  - The device page shows the list and the notices. The old banner shows only without the capability.
  - `update_parent_pin`: each refusal code writes nothing; save and clear work; the kid-PIN race guard holds.
  - `update_policy` (vpn/quick controls) leaves the PIN alone.
  - The sweep flags a stored 123456 and leaves a random PIN at 0.
  - Retention deletes old rows.
- **Server unchanged and still green:** `policy_json_keys_snapshot`, `status_report_*`, `tests/step10.rs` (adjusted
  for the moved PIN form).

## 8. Device checks (emulator, then the Jelly Star)

- **Lockout from every entry.**
  - 5 wrong PINs in each of the five entries lock all of them, with the remaining time shown.
  - On the time-lock screen the 6th try in the still-open dialog is refused (the bypass regression).
  - The last-try warning names the next duration.
- **Escalation, on the emulator with root.** After the first lockout ends (wait, or move the date forward and
  reboot), 5 more wrong tries give "30 min". A correct PIN, then 5 wrong, gives 15 min again.
- **Reboot during a lockout:** it still runs, for the wall-clock remainder. A date set back across a reboot gives at
  most one full duration.
- **Upgrade with a running 15-minute lockout:** it continues and never gets longer.
- **Offline, then online.**
  - An override, Settings, a pause, install mode and some wrong tries made offline all appear on the device page and
    in the security log once, after reconnecting.
  - Killing the launcher mid-upload causes no duplicates.
  - Online, an override's prompt event arrives within seconds **and the override stays on** (no sync was triggered).
- **A new PIN during a lockout:** after the phone syncs, the lockout is gone and the new PIN works.
- **An old server (pre-24) with the new launcher:** the events wait (404) and arrive after the server update.
- **Server checks:** the weak warning appears for an existing 123456 after a restart, and a trivial PIN is refused
  with its reason.

## 9. Open questions (with recommendations)

1. **Does a new PIN from the PWA reset the escalation and a running lockout?** The decision said "only a correct
   PIN". **Recommend yes.** Only the parent, signed in to the server, can do it. The old count was against the old
   PIN. And it is the one remote way out of a 24 h lockout that the kid can trigger on purpose. The PWA texts say so.
   No separate "reset lockout" button and no new policy key: the fingerprint already changes.
2. **Push to the parent.** **Recommend a PWA notice now** (the status-card notices plus the list and the security
   log). Web Push is a later, separate step. It needs VAPID keys, a subscriptions table, a `push` handler in today's
   install-only `sw.js`, the `web-push` crate, and outbound HTTPS to the browsers' push services. On iOS it works only
   for the installed PWA. If PLAN's ntfy plan B for nudges lands first, ntfy could carry this too. `device_pin_events`
   plus `notices()` are the hook either one would read.
3. **Existing trivial PINs.** **Recommend a warning only** (the card and a status notice), with no forced change and
   no lockout, detected by the one-time sweep (§4). The fallback, if the sweep's CPU cost is unwanted, is a generic
   "set before the server checked for easy PINs" hint for every PIN with `override_pin_weak IS NULL`.
4. **Fail closed when the lockout state can't be committed.** **Recommend yes** (§2.3, the same as the kid lock).
5. **Re-anchoring across a reboot by wall clock instead of a full restart.** **Recommend the carry-over** (§2.1).
   It is bounded and the escalation persists, while a full restart can keep the parent out indefinitely on a phone
   that reboots often.
6. **Times in UTC.** **Recommend keeping house style** ("UTC" plus "ago"). Local time across the PWA is its own change.
7. **Install mode is logged twice** (`play_install_mode` from the status diff, and `parent_pin_used`). **Recommend
   keeping both.** The status line confirms that the mode really ran.

Not in scope: Web Push, a remote "reset lockout" command, lockout decay over time, refusing birthdays, the kid-PIN
rules, and the phone's "unlock code"/"Parent code" wording.

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
