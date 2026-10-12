# QA: command expiry and cancel, the emulator fixes, and the 24b blocks follow-up (2026-10-12)

## Scope

1. Branch `cmd-expiry` on top of `main` at `acc8938d`:

| Commit | What |
|---|---|
| `fe620bab` | Command expiry and Cancel (server only, migration 0068) |
| `3a7964ff` | Catalog: an app uninstalled by hand is installed again |
| `d61591e3` | Calls: the missing call-screening role becomes a hint |
| `b5e67b25` | Home's role prompt lifts `DISALLOW_CONFIG_DEFAULT_APPS` (security change) |
| `705efde5` | Docs |

2. Already on `main`, not reviewed until now: `bd9878ae` (code) and `c3e7c0ac` (docs). This is the design 24b
   follow-up: `relock_blocks`, the Status card's list, and migration 0067.

The spec:
- the user's decision: wipe and lock wait without a limit; pause, ring, stop_ring and locate expire after an hour
  undelivered; Cancel for a command that hasn't been delivered;
- the L12 re-delivery, ordering and enrollment rules (`docs/review/qa-launcher-security-wave.md`,
  `security-review-2026-10-10.md`);
- `docs/design/24b-override-relock.md` §1.6 and its follow-up note. The user kept the two-step allow (tick the app
  under Apps, then press "Allow these apps") and asked for it to be made visible.

Paths: `L/` is `launcher/app/src/main/java/com/kidslauncher/mdm/`, `S/` is `server/`. Line numbers are at the tip
of `cmd-expiry` (`705efde5`).

## Build

- Launcher: `testDebugUnitTest assembleDebug assembleRelease -PwarningsAsErrors=true --max-workers=2`, built with
  the throwaway stub `app/libs/tsnet.aar`, which was removed afterwards. Both APKs built, and the release checks
  passed (no Google services, no debug hook). The test task was up to date, so it was run again with `--rerun`:
  990 tests, 0 failures.
- Server: `cargo test` passed 585 tests. `cargo fmt --check` is clean. `cargo clippy --all-targets` gives
  12 warnings, the same 12 as `main` (only line numbers moved). None is in the changed code.
- AOSP, fetched from `android16-release` (packages/modules/Permission):
  - `RequestRoleActivity` and `RequestRoleFragment`;
  - role-controller `Role` (`getRestrictionIntentAsUser`) and `RoleControllerServiceImpl`.
- Telecom `CallsManager.setUpCallFilterGraph`, from the copy in the scratchpad.
- Probe test: one temporary Rust test through the real routers, now deleted. It confirmed Medium 1.
- Not run: nothing on an emulator or a phone (no KVM here).

**Counts: 0 High, 3 Medium, 6 Low.**

## Medium

1. **Medium: pressing Ring, Stop ringing, Lock or "Update location now" silently deletes a wipe (or lock) still
   waiting for a lost phone. After this branch, the command that replaced it expires an hour later, so the phone
   gets nothing.**
   - Where: `S/src/handlers/locate.rs:340-372` (`queue_command`).
     - It runs `DELETE FROM device_commands WHERE device_id = ? AND delivered_at IS NULL AND command NOT IN
       (24b's three)`.
     - Then it runs `UPDATE ... SET deliveries = MAX` on every delivered, unconfirmed command.
     - Only the 24b commands are spared.
   - Scenario. The probe ran it through the real routes.
     1. The phone is lost and switched off. The parent wipes it (typing the name).
     2. Later the parent presses "Update location now" to see where it is.
     3. The `wipe` row is gone. Rows in the table before: `["wipe", "ring"(cancelled)]`. After: `["locate"]`.
     4. The security log has `device_wipe_queued` and then only `device_command_queued locate`. Nothing says the
        wipe was dropped.
     5. 61 minutes later the policy's `pending_command` is `null`. The phone comes back days later and is neither
        wiped nor located.
   - The same happens to a waiting `lock`.
   - The second statement (`deliveries = MAX`) also ends L12's re-delivery of a wipe that was fetched but whose
     receipt was lost.
   - Before this branch the replacing `locate` was at least delivered eventually. Expiry turns "your last action
     wins" into "nothing happens", which contradicts the decision that wipe and lock wait however long it takes.
   - The same DELETE also removes the history this branch promises to keep: rows cancelled by the parent or by an
     enrollment (`cancel_reason`), and expired rows.
   - The doc comment ("doubles as a way to cancel a queued-but-not-yet-delivered wipe") and `S/CLAUDE.md` still
     describe the implicit cancel. The explicit Cancel button now makes it redundant.
   - Fix:
     - In `queue_command`, supersede only the same kind: a new `ring` or `stop_ring` replaces a waiting
       `ring`/`stop_ring`, and a new `locate` replaces a waiting `locate`.
     - Never delete, or mark as used up, a `wipe` or `lock`. The parent cancels those with the new button.
     - If superseding stays, mark the old row `cancelled_at`/`cancel_reason = 'superseded'` instead of deleting it,
       and log it.
     - Add a test that a wipe survives a later Ring and Locate and still reaches the phone.

2. **Medium (security, `b5e67b25`): the kid controls how long `DISALLOW_CONFIG_DEFAULT_APPS` stays lifted for
   Home's role prompt. Restoring it depends on an in-memory flag and the prompt's result.**
   - Where:
     - `L/ui/HomeActivity.kt:483-528`: `promptForCallRoleIfNeeded` lifts the lock, then `rolePrompt.launch`.
       `onRolePromptDone` re-applies only if `defaultAppsLiftedForPrompt`, a plain field.
     - `L/server/AppEnforcer.kt:1178` (`setDefaultAppsLocked`).
   - What is open while lifted. AOSP 16 `Role.getRestrictionIntentAsUser` is the only place the restriction is
     checked. It runs in PermissionController's UI (`RequestRoleActivity.onCreate`, and
     `RequestRoleFragment.onItemClicked`), not in `RoleControllerServiceImpl`. While the lock is off, every
     default-app chooser PermissionController shows works. That means any other app's role request (the chooser
     lists every qualifying app, plus "None") and the Default apps screen (`MANAGE_DEFAULT_APPS_SETTINGS`).
     PermissionController is a kiosk helper, so all of these open in kiosk.
   - Scenario (kiosk features on the server are always SYSTEM_INFO, HOME, OVERVIEW, NOTIFICATIONS,
     GLOBAL_ACTIONS and KEYGUARD - `S/src/handlers/devices.rs:371`):
     1. Calls are managed, the launcher holds the dialer role, and the screening role is missing (the user's
        phone). Home shows the "caller ID & spam" prompt once a day, and the lock is now off.
     2. The kid doesn't answer it. They swipe to Recents and open any allowlisted app that offers "set as
        default": a browser, Signal/Molly for SMS, or anything that links to Default apps.
     3. Home is only stopped, so no result arrives and nothing re-locks.
     4. In that app's chooser the kid moves the phone-app role to the system dialer, or changes the SMS or home
        app.
     5. The launcher's in-call service and its screening are unbound: incoming calls aren't filtered by the
        launcher. This lasts until some other `apply()` runs: the sync backstop is 30 minutes
        (`L/push/SyncSchedule.kt:17`) or an SSE nudge.
     6. The kid can repeat this every day.
   - The flag also gets lost without the kid doing anything:
     - The manifest has `configChanges="orientation|screenSize"`. A uiMode change (dark mode from the shade), a
       font or density change, or the launcher's own `LauncherLocales.applyIfSafe` recreates HomeActivity. It runs
       in the same `resumeContent`, just before the prompt.
     - Process death also loses it.
     - The recreated instance gets the result with the flag `false`, so the lock stays off until an unrelated
       apply.
   - A crash between the lift and the launch can't happen: the exception path re-locks. A rotation doesn't
     recreate the activity.
   - Fix:
     1. Re-lock in `HomeActivity.onStop()` (when not `isChangingConfigurations`) if the flag is set. The prompt is a
        translucent dialog, so with it in front Home is only paused. When Home is stopped, the kid has gone
        somewhere else. Device check: Allow must still work while the dialog is up.
     2. Persist the lift (a timestamp in device-protected prefs, plus `savedInstanceState`). On HomeActivity
        create/resume with no prompt pending, and in `Application.onCreate`, a stale lift re-applies.
     3. Bound it: after about 2 minutes, re-lock even with the dialog still up. A late Allow then shows "blocked by
        admin", and tomorrow's prompt tries again.
     - Add a test that the lift is undone on `onStop` and after a recreate, not only the source-order scan in
       `DialerRoleTest`.

3. **Medium (`d61591e3` + `b5e67b25`): the calm hint says "Incoming calls are filtered" even when another app holds
   the call-screening role. The prompt that now shows lets the kid choose another app.**
   - Where: `S/src/handlers/calls.rs:431-441` (`screening == Some(false)` while the dialer role is held means the
     hint).
   - Telecom (`CallsManager.setUpCallFilterGraph`): when the screening-role holder is another app than the default
     dialer, that app's screening service runs *instead of* the dialer's. The launcher's screening is then never
     bound. Calls that aren't allowed ring until `KidInCallService` hangs up, and withheld numbers are never
     screened.
   - `RequestRoleFragment` lists every qualifying `CallScreeningService` app plus "None", and the kid picks.
     Google Phone qualifies on GMS phones, and it is often `config_defaultCallScreening` anyway.
   - The phone reports only `screening_role_held: false`, the same as "nobody holds it". The old error described
     exactly this case ("if another app holds it, that app screens incoming calls instead"). The hint removes it.
     The parent now reads that everything is fine.
   - Fix:
     - Keep the hint's calm tone, but say what it doesn't know: "... unless another app was chosen as the
       caller ID & spam app - then calls that aren't allowed may ring briefly before the launcher hangs up. To be
       sure, run `adb shell cmd role get-role-holders android.app.role.CALL_SCREENING`."
     - Or have the launcher tell the two apart. `KidCallScreeningService` gets bound for every incoming call while
       it is the one Telecom uses, so it can record "last incoming call screened by us" and the report can send
       `screening_bound_last_call`.

## Low

1. **Low: "Allow these apps" (`allow_blocked`) never expires, and it lets out every block present when the phone
   finally fetches it, including blocks the parent never saw.**
   - Where:
     - `S/src/models.rs:531`: `EXPIRING_COMMANDS` leaves out `allow_blocked`. The commit says this was the
       implementer's choice; the decision didn't name it.
     - `L/server/WindowRelock.kt:344`: `allowBlocked` drops all blocks except catalog-key mismatches.
   - Scenario:
     1. The parent presses Allow for app A while the phone is offline (or in airplane mode).
     2. Before the phone fetches it, a later window ends with a relock that blocks B, an imposter of a ticked app
        with another key, not a catalog app.
     3. The stale Allow lets B out.
   - `start_pause`, the other command that opens things up, expires for the same reason.
   - Fix: expire `allow_blocked` like `start_pause`. Better: send the package list the card showed
     (`allow_blocked:<pkgs>` or a payload) and let out only those.

2. **Low: the block list is cut at 16 with no "and N more", but Allow lets out every block.**
   - Where:
     - `L/server/WindowRelockPlan.kt:198-215` (`currentBlocks`: sorted, `take(16)`);
     - `S/src/window_relock.rs:296-358` (`blocks_note`).
   - Scenario: with 17 or more blocks (sideloaded decoys in a window), an imposter that sorts last isn't listed,
     and the parent's Allow lets it out unseen.
   - Fix: send `relock_blocks_total` (or one more entry as a sentinel) and show "and N more - check the phone". Or
     scope Allow to the listed packages (Low 1).

3. **Low: the two-step allow isn't visible per app.**
   - Where: `S/src/window_relock.rs:322-367`.
   - The card says "(a ticked app shows again)", but each item doesn't say whether that app is ticked under Apps.
   - In the emulator case that started the follow-up, the app was ticked. A parent who presses Allow for an
     unticked app sees nothing change on the phone, and nothing explains why. This is the visibility the user
     asked for.
   - Fix: pass the allowlist into `blocks_note`. Then end each item with "ticked - shows after Allow" or "not
     ticked - tick it under Apps too", and link to the app's row.

4. **Low: an enrollment leaves expired rows uncancelled, and expiry is computed against the server clock at every
   poll.**
   - Where: `S/src/handlers/device_api.rs:1353-1371` and the pop at `:344-356`.
   - Scenario: the Pi has no RTC. After a power cut, fake-hwclock restores the last saved time, which can be an hour
     or more behind real time, until NTP corrects it. In that window a ring or pause that has "expired" is
     deliverable again. That includes delivery to a newly enrolled phone, since `end_pending_commands` skips
     expired rows. This contradicts QA Low 2 of the launcher wave: nothing for the earlier phone reaches the new
     one.
   - Fix: cancel every undelivered row at enrollment. `status_line` can still say "Expired" by checking
     `past_expiry` before an `enrolled` cancel.

5. **Low: the new Locate page times are UTC but not labelled.**
   - Where: `S/src/models.rs:612-650`.
   - "Waiting ... - expires 2026-10-12 13:00:00 if it doesn't" and "Cancelled 2026-10-12 12:41:07 - never sent"
     are UTC. A parent in Norway reads them two hours off. The button's aria-label says "UTC"; the visible text
     doesn't. The "Requested" column already has the same issue.
   - Fix: add " UTC", as `parent_pin::when_text` does elsewhere.

6. **Low (cosmetic): `RelockReport` lost its KDoc.**
   - Where: `L/server/dto/RelockReport.kt:5-21`.
   - `bd9878ae` put `RelockBlockReport` and its KDoc between `RelockReport`'s KDoc and its class, so the first
     comment is orphaned.
   - Fix: move `RelockBlockReport` below `RelockReport`.

## Notes (not findings)

- `redelivery_nudge` (`S/src/handlers/device_api.rs:1409`) checks `cancelled_at` but not expiry. An expired
  command can get one more nudge: the phone syncs and gets nothing. Harmless.
- A start_pause that was delivered, never confirmed, and is past its hour still reads "no answer from the phone
  yet" on the Status card. The Locate page says "never confirmed". Acceptable.
- On the server, `blocks_note` treats a pinned app whose signer the phone couldn't read (`signer: null`, reason not
  `catalog_key`) as allowable. The phone keeps it blocked (empty signers never match the pin). The button then
  stays without effect. This is rare and safe in the right direction.
- The phone's catalog pins cover only the apps scoped to this device, while the server's `catalog_pins` covers
  every tracked app. The server can therefore say "stays blocked" where the phone would let the app out. The server
  is the stricter side, so this is fine.

## Missing tests

- A later Ring, Stop ringing, Locate or Lock never drops a waiting wipe (Medium 1). Today it does.
- The default-apps lock comes back after `onStop`, after a recreate, and on a stale persisted lift (Medium 2).
  Today only the source-order scan exists.
- The truncated block list (more than 16) and its Allow (Low 2).
- A stale `allow_blocked` delivered after a newer relock (Low 1).
- Enrollment with an expired, undelivered command (Low 4).

## Verified OK

- **Pop SQL** (`S/src/handlers/device_api.rs:344-375`):
  - the binds line up with the placeholders: device, 4 commands, expiry, receipts, max, gap, window;
  - `cancelled_at IS NULL` and the expiry clause hold for a first delivery and for a re-delivery alike;
  - the boundary matches `past_expiry` and `cancellable` (whole minutes: 59 min sent, 60 not), and the test covers
    it.
- **Cancel vs fetch:**
  - Each side is one SQLite statement (`UPDATE ... WHERE delivered_at IS NULL AND cancelled_at IS NULL` vs the
    pop's `cancelled_at IS NULL`). SQLite serializes writers, so exactly one wins.
  - Cancel is scoped by `device_id` (another device's path answers `too_late` and changes nothing).
  - It sits behind the same full-auth layer as wipe and lock, and is logged with the parent's name.
- **L12 interplay:**
  - `redeliverable()` and the pop both bound re-delivery by the hour for expiring commands. A lock goes on.
  - The ordering rule (older unconfirmed ones used up) is unaffected: skipped rows are undelivered.
  - Enrollment cancels what wasn't delivered (and wasn't expired) and uses up what was. It runs after the token
    refresh and before the new phone has its token.
- **Migrations:**
  - 0067 and 0068 only add nullable columns. `DeviceStatus` and `DeviceCommand` `FromRow` have the fields.
  - No other code lists `device_commands` columns.
  - No number clash with the other worktree (`wt-25b` ends at 0066).
- **`status_line` precedence:**
  - The order is result, then cancelled, then received, then undelivered (expired, waiting with its expiry time,
    waiting), then delivered.
  - A cancelled row can't be delivered, and a delivered one can't be cancelled.
- **24b answers:**
  - "expired" applies only to `start_pause` (`command_expires`); "Lock again" (`end_windows`) never expires.
  - "cancelled" shows for any of the three.
- **PWA:**
  - Cancel and "Allow these apps" redirect to the same path with no `#fragment`, so `scroll-restore.js` keeps the
    place.
  - Everything is askama-escaped.
  - Errors stay `<div class="error">` and the hint is a subtitle; the "neither role" case is still an error.
- **`relock_blocks` (`bd9878ae`):**
  - The server sanitizes it: valid package names, each once; 64-hex keys only; known reasons and places, else
    `unknown`/`relock`; times clamped; at most 16. Only these fields are stored.
  - `Some("[]")` vs `None` is handled: the relock's "Blocked:" line yields to the list only when the phone reports
    it.
  - `phone_app_blocked` is logged only for newly reported packages.
  - The literal is pinned on both sides (`PolicyResponseCompatTest`, server `window_relock::tests`). Nulls are sent
    explicitly (kotlinx `explicitNulls` default).
- **Phone side of Allow:**
  - The phone is the authority. A catalog-key mismatch or an unreadable signer stays blocked (`allowBlocked`:
    `!sameSigner(pin, now)`).
  - A let-out app isn't re-blocked when it is unhidden (`allowed` keeps its update time).
  - The block notes are written in the same `commit()` as the blocks and pruned with them.
- **The quiet `NameNotFoundException` skip** (static shared libraries) returns `false`, as the generic catch did
  before. No behaviour change, so no new bypass.
- **Catalog reinstall (`3a7964ff`):**
  - A hidden or suspended app counts as installed (`MATCH_UNINSTALLED_PACKAGES`, then `FLAG_INSTALLED`).
  - A gone release is forgotten and queued through pins, refusals and backoff. A refused tag stays refused.
  - PackageManager failing means "installed", so there is no loop. A blank package name means "unknown".
  - A Play-installed copy is still skipped first.
  - Unticking removes the app from `scoped_apps`, so there is no reinstall/uninstall fight with
    `device_pending_uninstalls`.
  - The versionCode travels through the install intent and survives `recordFailure`/`recordRefused`.
- **Lift guard (`b5e67b25`):**
  - `promptNeedsDefaultAppsLift` lifts only when the lock is on and the prompt isn't the dialer's.
  - `minSdk` is 34, so the Android 14+ check always applies.
  - `AppEnforcer.apply` is `@Synchronized` and re-derives `lockDefaultApps` itself.
  - A failed lift skips the prompt. A failed `launch` re-locks. A failed `apply` falls back to
    `setDefaultAppsLocked(true)`.
  - A rotation doesn't recreate Home (`configChanges`).
- **Tests and builds:** all green as listed under Build.
