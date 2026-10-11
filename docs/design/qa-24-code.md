# QA 24 code review: parent PIN hardening + design 19b phase 1

QA, 2026-10-11. Branch `pin-24` at `9a91600d`: 45cf2d48 (1a), f8f3ea11 (1b = 19b phase 1), 10c360f4 (2),
190dddfe (3), 9a91600d (4), on main `112b68b3`. Read against `24-parent-pin-hardening.md` (body, QA review,
decisions, implementation status), `19b-doze-exemption.md` and its user decision, the hotfix ec58d6d9,
`security-review-2026-10-10.md` H3 and its status, and the three `CLAUDE.md`s.
Threat model: a motivated kid with the phone in hand, no adb, auto time.

**Builds (own worktree):**
- Launcher: `testDebugUnitTest assembleDebug assembleRelease -PwarningsAsErrors=true` is green, with 773 tests and
  0 failures.
- Server: `cargo test` passes 454 tests and `fmt --check` is clean. `clippy --all-targets` gives 15 warnings, the
  same set as the base and none in the branch's code.
- `phone_vectors.json` is identical on both sides. `policy_json_keys_snapshot` and the policy half of
  `PolicyResponseCompatTest` are unchanged.

**Verdict:** the gate is sound. I found no bypass through any of the five entries or their combinations.
- The count and its event are committed before PBKDF2 runs.
- A lockout refuses without hashing.
- A failed commit restores memory.
- An unreadable state fails closed for 15 minutes.
- A new PIN resets everything.

One merge blocker: main took migration 0054 after the branch base (High 1). The Lows are about what the
parent is told. Two simplifications remove a write-only field and a second "is a PIN configured?" rule.

Counts: **1 High, 0 Medium, 6 Low, 2 Simplification.**

## High

1. **High (merge blocker): two migrations numbered 0054.**
   - **Where:** `server/migrations/0054_battery_exemption.sql`. Main added `0054_location_cleanup.sql` in
     7e9fe55e (security H2) after `112b68b3`.
   - **Why it slips through:** the file names differ, so `git merge-tree main pin-24` merges with no conflict.
     The merged tree has both `0054_*.sql`.
   - **What breaks:** sqlx keys `_sqlx_migrations` by `version BIGINT PRIMARY KEY`.
     - A fresh database fails on the second v54 insert, so every server test fails at `TestApp::new`.
     - A Pi that already ran main's 0054 gets `MigrateError::VersionMismatch(54)` at startup, so the server
       doesn't start.
   - **Fix:**
     - Rebase onto current main.
     - Renumber to `0055_battery_exemption.sql`, `0056_parent_pin_easy.sql` and `0057_device_pin_events.sql`.
     - Update every mention: `server/CLAUDE.md:146` and `:525`, `server/src/models.rs:107` and `:313`,
       `docs/design/19b-doze-exemption.md:260`, and design 24's deviation 1 (`:918-919`).
     - Re-run `cargo test`. Main also changed `device_api.rs`, `models.rs`, `security.rs` and `tests/mod.rs`.
       They merge cleanly, but the rebase needs one green run.

## Medium

None.

## Low

1. **Low: a dialog that opens after 4 wrong tries gives no last-try warning.**
   - **Where:**
     - `ui/ParentPinDialog.kt:58-61` and `:83-87` (`refusal`), and `lock/PinLockActivity.kt:533`.
     - `ParentPin.lockoutRemainingMs` (`server/ParentPin.kt:47`) exposes only the lockout.
   - **Scenario:**
     - The kid types 4 wrong codes on the time-lock screen and stops, because the 4th try showed "One more
       wrong try locks it for 15 min". The `failures` count never decays.
     - Days later the parent opens Settings and makes one typo. That locks every entry at the next level, with no
       warning first.
     - QA #5 exists to prevent this, but the warning only shows after a try made in the same dialog.
   - **Fix:**
     - Replace `lockoutRemainingMs` with one pre-check under the lock, for example
       `ParentPin.status(context) = (lockoutMs, triesLeft, nextLockoutMs)`.
     - When `triesLeft == 1` the dialog opens with "One more wrong try locks it for {next}" and OK enabled.
       This uses the existing `parent_pin_last_try` text, or a variant without "Wrong code.".
     - Add a store test: after 4 wrong tries, the status says 1 try left and names the next lockout.

2. **Low: the security log hides when a phone event happened.**
   - **Where:** `server/src/handlers/parent_pin.rs:291-297`, with the detail from `parent_pin::event_line`
     (`:395`) and `until_text` (`:363`).
   - **Scenario:**
     - The log's "When" is `created_at`, the time the event arrived.
     - A Settings use or override from a phone that was offline for two days appears as happening now.
     - "until 10:53 UTC" carries no date. The integration test shows this: an event from 2025-10-09 is logged
       today as "...until 10:53 UTC at the latest."
     - The device card shows the right time, but the security log is where a parent reconstructs what happened.
   - **Fix:**
     - Put the phone's time into the log detail: `device {id}, 2026-10-09 14:02 UTC: {line}`.
     - Give `until` a date in the log line (`%-d %b %H:%M UTC`).
     - With `clock_off`, add the existing arrival note.

3. **Low: a lockout line names only where the 5th try was typed.**
   - **Where:** `server/src/parent_pin.rs:420-424`.
   - **Scenario:**
     - The kid makes 4 wrong tries on the lock screen. Then the parent makes one typo for Settings.
     - The card and the security log say "5 wrong parent PINs for Settings: the parent PIN is locked...".
     - The parent concludes it was their own typing and misses the kid's guessing.
   - **Fix:** "5 wrong parent PINs in a row (the last {where}): ...".

4. **Low: the Status card keeps telling the parent to change the PIN after they did.**
   - **Where:** `server/src/parent_pin.rs:460` (`notices`) and `handlers/parent_pin.rs:46`.
   - **Scenario:**
     - The parent sees "...your child may be guessing it - save a new parent PIN to stop it" and saves a new PIN.
     - For up to 24 h, the same notice (and "N wrong parent PINs... consider changing the PIN") stays at the top
       of the page. It reads as if the save didn't work.
   - **Fix:**
     - Record when the PIN was last saved, for example `device_policy.override_pin_set_at`. Put it in the
       renumbered `override_pin_easy` migration, so no extra migration is needed.
     - Build the notices only from events after that time. The card's list keeps everything.
     - A cheaper alternative: use the latest `override_pin_changed`/`_cleared` security-log row for `device {id}`.

5. **Low: docs and comments.**
   - `docs/design/24-parent-pin-hardening.md:168`: "The level persists either way" is wrong.
     - The decay window carries over by the wall clock, like the lockout.
     - So on a phone whose clock is unlocked during an override or pause (no managed calls and no time rules:
       `EnforcementPlan.lockDateTime`), a clock moved forward plus a reboot also clears the level. That gives up
       to 35 fresh tries, not "one extra round".
     - Say so. It needs a parent-granted window, so it stays an accepted residual.
   - `docs/review/security-review-2026-10-10.md:275`: the H3 status row still says "in progress". Point it at
     design 24.
     - Its checklist item `:240` ("USB keyboard via OTG against the PIN dialogs") is not in design 24 §8. Add it:
       fast typing hits the same count, and Enter must not act as OK.
   - `docs/testing/emulator.md:42`, `:75`, `:259` still say "override PIN"/"unlock code". On the device page
     that is now the "Parent PIN" card, which moved out of the "Play and kiosk" form.
   - `launcher/.../lock/PinBackoff.kt:9`: "its own counter (OfflineOverride)" should be `ParentPin`.
   - `server/src/parent_pin.rs:202-203`: the comment says a 413 means "the phone never deletes what wasn't
     stored". `pinEventsLeaveQueue(413)` is `true`, so the phone drops that batch.
     - This can't happen today: the queue holds at most 100, and design 22's 512 KiB cap is far away.
     - Make the comment true (the 413 guards against a launcher that sends more than it should).
   - `server/src/parent_pin.rs:435-437`: the `clock_off` note follows the final period: "...(1 in a row). (the
     phone's clock was off; ...)". Put it before the period.

6. **Low (test gap): the launcher doesn't pin the capability string.**
   - The server test posts `"parent_pin_events_v1"`. On the launcher side only the constant
     (`ParentPinEvents.kt`, `PARENT_PIN_EVENTS_CAPABILITY`) holds it.
   - If its value changes, the device page silently falls back to "doesn't report parent-PIN use yet" and the
     old banner.
   - Fix: assert the literal in `PolicyResponseCompatTest`, next to the event literal.

## Simplification

- **S1: `ParentLockoutState.level` is write-only.**
  - `levelNow(decay)` makes every decision (`beginParentAttempt`, `wrongResult`). `level` is only computed,
    stored (`ParentPinStore.kt:68`) and validated (`:97-98`).
  - The validation adds a failure mode: an out-of-range diagnostic value turns the state into "unreadable", which
    gives a 15-minute lockout.
  - Drop the field and the `level` key. Tests assert `levelNow` instead. An already-stored `level` key is
    harmless, because `read()` stops requiring it.
- **S2: two rules decide whether a PIN is configured.**
  - The pre-checks use `OfflineOverride.isConfigured()`, which only requires non-empty values:
    `ParentPinDialog.kt:84`, `SettingsActivity.kt:79` and `PlayRuntime.kt:153`.
  - `ParentPin.check` uses `cachedPin()`, which also requires `PinHash.usable` (`ParentPin.kt:69-74`).
  - With an unusable cached hash, the gate opens its dialog with OK enabled and then answers "No unlock code
    has been set up".
  - Use one `ParentPin.configured()` everywhere. (Unreachable today: the server only writes valid hex.)

## Deviations: verdicts

1. **Migration numbers: must change again.** They were right at `112b68b3`, but main now has 0054 (High 1).
2. **Handlers in `handlers/parent_pin.rs`, the pure code in `src/parent_pin.rs`: fine.** The device route is in
   `device_routes.rs`, behind `require_device_token`, `device_edge`'s limits and the 512 KiB cap. The admin
   route is in `admin_routes` behind `require_full_auth`, and the global `csrf_guard` also covers it.
3. **One shared dialog for four entries: fine, and better.** The PIN lock keeps its layout and shares
   `resultText`/`okAfter`/`refusal`.
4. **`lockoutText` takes the unit strings: fine** ("1 t 45 min").
5. **The Settings session number is taken at dialog creation: fine.** It is equivalent to taking it on OK:
   - `onStop` both dismisses the dialog and bumps the number;
   - a configuration change destroys the activity, which cancels `lifecycleScope`;
   - whichever comes first, the late Ok or `onStop`, the end state is gated.
6. **`LockEvent.Unlocked(checkedAt, screenOffs)`: fine.** `step` stays pure. If the late Ok lands before the
   `SCREEN_OFF` broadcast, the ScreenOff that follows locks again, so the screen is never dark and open.
7. **Install mode's `NO_PIN` in the dialog: fine.** It is consistent with §2.4. The non-PIN refusals keep their
   toasts.
8. **A correct PIN whose `unlocked` commit fails still works: accepted.**
   - Residual: the disk still holds the counted attempt (on a 5th try, a lockout and its `lockout` event) until
     the next successful commit.
   - A process death in that gap later shows a use as wrong or locked out.
   - It needs two commits within ~0.5 s where the first succeeds and the second fails. It is rare and fails
     closed.
9. **The clear path relies on the SQL `CASE` alone: fine.** That is the real race guard. (A clear without a PIN
   logs `override_pin_cleared`. The checkbox only renders with a PIN, so that can't happen from the page.)
10. **The sweep takes its candidates: fine.**
11. **Notice styles: fine.** "Saved but easy" in error style is deliberate.
12. **The 19b row decides by `dialogTarget`, not `RestrictionsPause.isActive()`: fine.** This is 19b's own rule
    (P3), and it also covers setup.
- **"Not done here" (11+ digit PINs): moot.** The old form already had `maxlength="10"`, so only a hand-made POST
  could have stored one.

## Checked and fine

**Bypasses (all five entries):**
- `PinHash.verify(` exists only in `ParentPin.kt` and `PinLockRuntime.kt`.
- The three window starters are called only from `ParentPin.startWindow`, after the `unlocked` commit. Nothing
  else writes `offlineOverrideActive`/`restrictionsPaused`/install mode's `UNTIL`. All of this is pinned by
  `ParentPinCallersTest`.
- The hotfix's `verifyPin`/`isLockedOut` are gone. Every try re-checks the lockout inside the lock, before
  any hash.

**Concurrency and dialogs:**
- Two dialogs at once (a double tap, the PIN lock over the time lock, a pause dialog left open under a re-shown
  gate) are serialised by `@Synchronized check`. Each one loads fresh state, so no try escapes the count.
- A pause or install dialog left over from an ended Settings session still needs the PIN itself. The modal gate
  dialog sits above it.
- OK is disabled on tap, so a dialog can't start a second check.

**Process death, reboot and clocks:**
- Rotation or process death mid-check: the count and event are committed before PBKDF2, and the window starts
  inside `check`, so a cancelled `lifecycleScope` can't split them.
- Reboot: a re-anchor by the wall clock, never longer than the duration, and a clock set back gives the full
  duration.
- Time zones don't matter (UTC ms).
- Airplane mode: events wait in the queue.

**Failures and storage:**
- Storage full: `Unavailable`, with memory restored and nothing hashed, matching real `SharedPreferencesImpl`
  behaviour (a failed write deletes the new file and keeps `.bak`).
- `TooShort` and `NotConfigured` are uncounted and unhashed.
- The 10-digit limit is the same on both dialogs (`maxLength="10"`, `numberPassword`), the PWA input and
  `parent_pin::valid`.

**Never locked out for good:**
- An unreadable state becomes a committed 15-minute lockout, keeping the queue.
- A corrupt queue is dropped, and the state is rewritten with the right types.
- The decay is one step per quiet 24 h and the cap is 24 h.
- A new PIN from the PWA (another salt, so another fingerprint, even for the same digits) resets everything at
  the next sync.
- The lock order holds: `check` nests nothing, and `checkParentCode` takes `PinLockRuntime`'s lock only after it.

**Migration of the old counter:** it runs once inside the lock and is capped at 15 minutes. The count carries
over, at most 4, and the legacy keys are zeroed. Tested.

**Events:**
- The queue holds at most 100 and drops `wrong` first. Ids are 128-bit `SecureRandom` hex.
- The upload sends a snapshot. Only the sent ids are removed, under the lock. Batches are kept on
  401/404/408/429/5xx/no answer and dropped on other 4xx. Enrollment (both paths) clears the queue.
- The server parses leniently (400 only for non-JSON or a missing array, 413 above 100) and skips bad
  ids/kinds/entries.
- Bad times are kept with `clock_off`, and bad numbers become null.
- `INSERT OR IGNORE` on `(device_id, event_id)`, with the security row in the same transaction and only for new
  `unlocked`/`lockout` rows.
- No `command_notify` is sent. 90-day pruning works, and device deletion cascades (foreign keys are on).
- An old server answers 404 on both listeners and the events wait.

**Server card:**
- Admin-only, CSRF covered (`csrf_guard` plus SameSite). No echo: the redirect carries only the code, the
  field is a password input, and logs hold neither the PIN nor its hash.
- 6-10 digits are required. The kid-PIN cross-check runs in `spawn_blocking`.
- `update_policy` no longer touches the PIN columns, and the policy form no longer has PIN fields.
- `easy`/`easy_candidates`: 187 candidates, with the full 6-digit loop tested. The one-time sweep uses
  `UPDATE ... WHERE override_pin_hash = ?`, so a newer save wins.

**Migrations:** 0054-0056 applied to a seeded database (a device, a policy with a PIN, a status row). The new
columns are NULL, and the pin-events cascade works.

**19b:**
- `battery_optimization_exempt` is sent as `Boolean?`, left out when unknown, `#[serde(default)]` on the
  server, stored, and shown as the `play_card` line. Pinned on both sides.
- The manifest permission is pinned (`PushManifestTest`).
- The row opens only an unsuspended, non-own system dialog, so during the pause or setup. Otherwise it shows
  the pause-first text. It is in the gated Settings only.

**PWA:**
- The text is English, and the notices sit inside their cards. `#parent-pin` and `#screen-lock` redirects land
  on the notice. `update_policy` relies on `scroll-restore.js`.
- The implementer's 360 px light and dark screenshots show no overflow and readable error and success boxes.

**Contract:** §3.3's literal is pinned on both sides. `PIN_EVENTS_MAX` is 100 on both sides.
