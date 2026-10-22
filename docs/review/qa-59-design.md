# QA 59 - design (`docs/design/59-android-keyguard-design.md`)

Design QA, 2026-10-22, on `proto-59-qa`, checked against the assessment, PLAN's design 59 entry (the user's answers),
the prototype (`fa33001d..46cf0fa5`), its code QA (`fb6df128:docs/review/qa-59-prototype-code.md`, "P59-n"), the
Recents review and r10. Every reference below was read in the code. **0 High, 8 Medium, 6 Low, 4 Nit.**

Verified as the note says: `music_secret::load`/`MusicKey::seal_with`/`open_with` (fingerprint-checked), `keys::
load_or_create` (0600/0700); no backup copies `data/keys/` (`backups.rs:188-205`: the DB and image stores only; Docker
has its own `handy-keys` volume); `PendingCommand`'s `skip_serializing_if` pattern (`models.rs:1025-1032`); L12
(`MAX_COMMAND_DELIVERIES` 3, `REDELIVER_WINDOW_MINUTES` 15, `models.rs:619-630`); `build_policy` reads `device_policy`
first and pops the command last (`device_api.rs:229`, `:347-375`); `passwords.verify` -> `Busy` (`admin_auth.rs:99`);
`mark_post_answer` marks every POST answer (`admin_auth.rs:490-500`), so the reveal needs nothing for reconnect.js;
the SW has no fetch handler; no HTTP body logging on the phone; `CommandAnswers` stores only id+requested_at; the
next migration is 0085; `ParentPinCallersTest:32` pins `PinHash.verify(` to `PinLockRuntime.kt`.

## Findings

**Q59-1 (Medium): §3.5's PIN check, if done "through PinLockRuntime", checks the wrong hash.**
- Evidence: the sync stores the fresh policy (`MdmSyncWorker.kt:120`), dispatches the command (`:151`), and applies
  only later (`:170`). `checkPin` checks against `config`, the **last apply's** hash (`PinLockRuntime.kt:1551`, set in
  `configure` `:1209`). It also counts a failure first and returns `Waiting` during the kid's backoff (`:1555-1565`).
- Failure: after a PIN change, the new PIN is checked against the old hash: `refused: mismatch`, which is §3.5's main
  path. During a wait (test step 8) it gives `Waiting`, and a command would count as the kid's wrong try.
- Fix (§3.5): name a new `PinLockRuntime.verifyCommandPin(kidLock: KidLock, pin)`. It checks the **fresh** policy's
  hash and salt, with no backoff, and stays the only `PinHash.verify(` caller. Add a test: a fresh hash that differs
  from the applied one gives `pin set`.

**Q59-2 (Medium): the process-start rule (§3.1) can lock right after the boot PIN, and can open on a crash restart.**
- Evidence: `decide` runs in `initUnlocked`, which `KidAppComponentFactory` triggers "even while the user is still
  unlocking" (`Application.kt:271-280`). Own mode fails closed (`PinLockState.kt:146-154`). The crash guard turns our
  lock off (`PinLockRuntime.kt:1216-1224`).
- Failure:
  - (a) At boot `isKeyguardLocked` can still read true while the bouncer goes away. The result is LOCKED, then the
    glance, then RESUME "not locked, never seen" -> RELOCK -> `lockNow`. The kid types the PIN twice after a restart,
    with the screen turned off between.
  - (b) A crash restart later in the boot, when Android is unlocked because T didn't take, starts UNLOCKED. That skips
    §3.3's safety net. Own mode would start LOCKED.
- Fix (§3.1):
  - The boot's **first** CE start in android mode is UNLOCKED by rule: CE unlocked means the PIN was typed. Use the
    boot-count test `unlocked_at_ms` already needs.
  - Every later start is LOCKED, as in own mode, and §3.3 decides (RELOCK if Android isn't locked).
  - The crash guard in android mode calls `lockNow()` instead of leaving the phone open.
  - Test it in `AndroidLockPlanTest`.

**Q59-3 (Medium): `seenLocked` lets the glance leave without Android's verdict. The table can be simpler and safer.**
- Evidence: §3.3 table, lines 180-185, and §4's claim "leaves only on Android's own verdict".
- Failure:
  - "Not locked + seen" leaves on any transient not-locked read: a SystemUI restart, or `mShowing` racing the read.
    That is a read, not a verdict.
  - A fingerprint within the first second, before the 1 s check marked it seen, gets RELOCK on RESUME. That costs
    the fingerprint and turns the screen off (the USER_PRESENT row only covers the order where the broadcast comes
    first).
- Fix (§3.3): drop `seenLocked`.
  - Leave only on the pad's `onDismissSucceeded`, or a `USER_PRESENT` (a protected broadcast) since the LOCKED edge.
  - "Not locked, no USER_PRESENT" means RELOCK: at once when dark (the 1 s check), and after a grace of at most 1 s
    (`ANDROID_ANSWER_WAIT_MS`) on RESUME or a tap, so the broadcast can come first.
  - This also covers P59-6. Adjust §4's first bullet and the mutation "LEAVE on RESUME without seenLocked" to
    "LEAVE without USER_PRESENT/success".

**Q59-4 (Medium): the rollback edge fires on a fallback policy that carries no `kid_lock`.**
- Evidence: §3.8 triggers on "`kid_lock` is null". `LastEnforcedPlan.toPolicy()` has no `kidLock` (`PolicyGate.kt:134-149`).
  `choosePolicy` applies it on a corrupt cache (`:216`), and `configure` reads `policy?.kidLock` (`PinLockRuntime.kt:1176`).
  `Apply(null)` does the same.
- Failure: offline with a corrupt cache, the phone clears Android's PIN with the token. That is a parent decision taken
  from no parent input. With the fix, Android's PIN stays the gate instead.
- Fix (§3.8):
  - Roll back only on a policy that carries the parent's choice (fresh, or `CachedPolicy.Ok`), never on a
    `Fallback` or `null`.
  - Commit `keyguard_android = true` **before** the first `resetPasswordWithToken`, write-ahead as M6 does, so a
    death between the reset and the apply can't hide the edge.
  - While a rollback is pending with no token, keep `ensureResetToken` running, so escrow completes it at the kid's
    next unlock.
  - Add a test: a Fallback never rolls back.

**Q59-5 (Medium): the keypad migration's `pin_rev` can name a different PIN from the one it set.**
- Evidence: §3.4 says "`android_pin_rev = kid_lock.pin_rev`", but not from which policy. `checkPin` verifies against
  the applied config, while the cache may already hold a newer policy (stored at `:120`, applied at `:170`).
  `PinLockStore.saveConfig` (`PinLockStore.kt:48-58`) stores no rev.
- Failure: the kid types the old PIN between the store and the apply. The phone reports the new rev, the server
  drops `prev`, and the PWA shows only the new PIN while the phone's PIN is the old one. No command is queued.
- Fix (§3.4/§3.1): `saveConfig` commits `pin_rev` with the hash. The keypad migration commits the rev of the config
  it verified against. The command commits the fresh policy's rev, from the same answer as its hash.

**Q59-6 (Medium): `prev = sealed` on every save loses the phone's real PIN after two saves.**
- Evidence: §2.1 Save, lines 75-79.
- Failure: the phone holds A. The parent saves B, then C, so `prev` = B. The PWA says "its PIN is still B", but it
  is A. The parent types B into Android's pad and runs into Android's 30 s waits.
- Fix (§2.1/§2.2):
  - Save with `kid_pin_prev_sealed = COALESCE(kid_pin_prev_sealed, kid_pin_sealed)`, plus `kid_pin_prev_rev`.
  - Show the previous PIN only while the phone's reported `pin_rev` equals `prev_rev`. Otherwise show "an earlier
    PIN". Clear both when the report is current.

**Q59-7 (Medium): the queue rule isn't self-healing, and "waiting" isn't defined against L12.**
- Evidence: §2.1 Queue rule ("no undelivered `set_kid_pin` is waiting"). Redelivery stops after 3 deliveries or
  15 minutes (`device_api.rs:347-356`). A later pop retires older unconfirmed rows (`:385-397`). The phone runs an id
  once (`CommandAnswers.begin`).
- Failure:
  - A row that was delivered but never confirmed is "waiting" until it dies, so a save meanwhile queues nothing.
  - A redelivery of the same id after a new save is confirmed but not run.
  - Either way the phone stays on an old PIN until the parent presses "send again".
  - Separately, §7 tests "never to a phone without the capability", but delivery doesn't check it. A re-enrolled
    phone with an old launcher gets `pin` before its first report (`capabilities_json` is still the old phone's).
- Fix:
  - Define "waiting" by the pop's own deliverable predicate (one SQL fragment).
  - At status intake, run the queue rule when an `android_keyguard_v1` phone in android mode reports `pin_rev` ≠
    current, unless the newest `set_kid_pin` answer was a refusal. Optionally include `null` (a kid's own PIN); that
    is the user's call.
  - Fill `pin` only when the last report has the capability.

**Q59-8 (Medium): the prototype QA's Recents findings (P59-1, P59-2) aren't in the note. It repeats the wrong
claim.**
- Evidence:
  - §3 "Moves as is: ... `noteRecentsGuard`" (line 148), whose `guarded=true` verdict P59-1 shows is false.
  - §3.9 (line 247-248): "lock task ... (its status-bar flags hold the shade and Recents too)". That contradicts the
    Recents review §1 (OVERVIEW off doesn't stop Quickstep) and §3.3's own premise.
  - Test step 5 has no baseline on today's lock.
- Fix:
  - Drop the verdict. Log the lock-task flags together with `isKeyguardLocked`/`isDeviceLocked`.
  - Correct §3.9: the keyguard holds Recents; lock task holds only the shade, and that is a device check.
  - Run step 5 first on today's lock (during step 1, before the RC). A pass counts only if the leak reproduces there.
  - Add `KeyguardController` (showing/occluded) to each "Send".
  - Note that a square tap raising the pad (and Recents after the PIN) is expected.

**Q59-9 (Low): the other prototype QA findings, named into step 3's brief.**
- §3's "moves as is" list carries over defects the prototype QA found:
  - **P59-3**: the re-front hold must also need `deviceSecure`.
  - **P59-4**: the camera lock must stay engaged unless HYBRID's read-back holds B2's 0x2.
  - **P59-8 (M5)**: when the face turns off, the camera lock must re-sync.
- **P59-5** applies again: the real switch is `kid_lock.keyguard`. Pin what `configure` derives from it in a test
  (`"android"`, `"own"`, missing, junk -> face). A source scan for "the debug switch gone" isn't enough.
- **P59-7**: `inputs.dir("src/release")` on the test task, for the cover's pins that remain.
- P59-6 is covered by §3.3 (better by Q59-3). The relock nit is covered by §3.7. Define "skips android mode" there
  as "android requested", not "face on".
- Fix: list P59-1..8 by name in step 3 rather than "its findings go into step 3's brief".

**Q59-10 (Low): the notice wake without `lockNow` keeps the screen on for the parent's timeout.**
- Evidence: §3.9 line 244. The timeout can be 10 minutes (`models.rs:133-140`); today it is 15 s
  (`NoticeWakePlan.kt:14`). A showWhenLocked activity gets no keyguard short timeout.
- Fix: state the trade (battery, pocket presses vs the fingerprint), and let the user choose. Option: keep the 15 s
  `lockNow` only when the timeout is over 30 s.

**Q59-11 (Low): the reveal page.**
- `no_store_path` (`admin_auth.rs:473-488`, with its test) should list `["devices", _, "kid-pin", ..]` instead of a
  per-handler header.
- The answer's path differs from `/devices/{id}/lock`, so `scroll-restore.js`'s per-path record doesn't apply and the
  page lands at `#child-pin`. That is the group-top "jump" the user reported on 2026-10-19. Post to the Lock page's
  own path (an `action` field), or accept it in writing.
- The three-wrong session end: `change_password`/`reset_totp` (`auth.rs:391`, `:772`) are the same password oracle
  without it. Make one helper for every re-auth form, or note the gap.

**Q59-12 (Low): the token rule (§3.6): "stored but not active -> wait for the unlock" never ends without a
credential.**
- With no credential nothing verifies, so the stored token never activates.
- Fix:
  - Not active and `!deviceSecure`: set it again; it activates at once.
  - Not active and secure: wait for escrow.
  - r10's `Unreadable` counts as none.
- This replaces `resetTokenNeeded` (`WindowRelockPlan.kt:127-128`) in android mode, as r10 §6 asked.

**Q59-13 (Low): when the capability lands.** §1 (line 50) says step 3, and §6 step 4 (line 296) says step 4. Step 4
is right: a step-3 build that claims it would get `set_kid_pin` and answer "unknown command".

**Q59-14 (Low): the emulator list misses the two new safety paths.**
- Add to step 4:
  - a quick off/on (`input keyevent 26 && input keyevent 26`, under 300 ms);
  - a fingerprint touch within 1 s of power-off (after step 11).
- Add `adb shell am crash me.vibb.launcher` with the glance up, and once on Home (Q59-2: expect the glance and a PIN).
- Add `MdmDeviceAdminReceiver`, `CameraLock` and `PinLockActivity` to the logcat tags (steps 12 and 13).

**Nits.**
- N1: `music_secret::load` logs and errors in Storytel's words (`music_secret.rs:134-149`). Pass a label, or call
  `keys::load_or_create` directly, so a kid-pin key isn't announced as "Storytel logins are sealed with it".
- N2: `kid_lock`'s inner keys are pinned in `tests/step10.rs:156`, not in `policy_json_keys_snapshot` (top level
  only, `device_api.rs:293`). Name both, plus `pending_command`'s keys with and without `pin`.
- N3: under `#[serde(default)]` (`kid_lock.rs:95`) a missing `pin_rev` and `null` are the same. Tell an older
  launcher by the capability, as §2.2 half-says.
- N4: §3.7's "within 10 s" window: a one-shot "expect our change" flag, set before each own reset (rollback too), is
  exact. Also say what clearing the Child PIN does to `kid_keyguard` (step 15 implies it resets).

## Open questions - recommendations (the user decides)

1. **Face unlock off**: yes. Only fingerprint was approved, and a 2D face unlock is Class 2 at best on cheap phones.
   A per-phone fingerprint switch (`KEYGUARD_DISABLE_FINGERPRINT`, A §4.9) is a proposal for later, not this RC.
2. **The line**: set "Skriv koden for å starte telefonen" at `ACTION_SHUTDOWN` (BootCover's receiver already runs
   there), and a neutral line, or none, at the first CE start. The restart words then show only after a restart.
   A hard crash misses it; Android's own "PIN required after restart" still shows. Otherwise use "Skriv koden for å
   låse opp".
3. **Fossify**: keep it for the RC. Gate phase C on a Jelly Star overnight reboot with a 07:00 alarm: does it ring
   before the PIN is typed? `ClockReader`'s coupling is the cost of switching.

## Steps, tests, scope

- Step 1 is one commit with both directories. Steps 2 and 3 touch disjoint trees (`server/` vs `launcher/`). The
  order is right once Q59-13 is fixed.
- The tests are behaviour-first and have no redundant matrix. Add the tests from Q59-1, -2, -4, -5 and -9.
- Nothing is out of scope beyond the proposal in open question 1.
