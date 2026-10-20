# Code QA - launcher round 7, part A (r7 §1 any allowed app rings over the lock, r7 §4 the Fossify items)

- Commits: `153ed405..da6a3d9e` on branch `l7-a`. `255eaf57` is §1: `voipCandidates(effective, voipAllowed,
  policyKnown)`, `voipAllowedApps`/`voipExcluded`, `lock_state.voip_rang` in both directories, and the Lock page's
  "Calls from ..." rows. `da6a3d9e` is §4: icon eviction, the Home press to the top, DiffUtil in the grid, the
  conflated `loadApps()` and the inherited "can't open" dialog.
- Read: root and launcher `CLAUDE.md` (VoIP calls over the PIN lock, glance page, Home, design 32, design 16);
  `r7-launcher-small-items.md` §1 with §1.4 and its deviations; `qa-r7-launcher-design.md` r7-1..r7-5;
  `fossify-launcher-comparison.md`; PLAN's round 7 entries; the full diff. Also read for context: `HomeFront`,
  `BootHome`, the lock-up path in `PinLockRuntime`, `SelfUpdate.bringHomeAfterUpdate`, `HomeActivity`'s
  resume/pause/stop/night code, `AppEnforcer.apply`/`essentialNotificationPackages`, `computeEnforcementPlan`'s
  hide/suspend, `AppIconLoader`, `KidAvatars`, `partials/note_row.html`.
- Ran:
  - Launcher: `testDebugUnitTest -PwarningsAsErrors=true`: **BUILD SUCCESSFUL, 1446 tests, 0 failures, 0 errors, 0
    skipped**.
  - Server: `cargo test`: **1095 passed, 0 failed**; `cargo fmt --check` clean; `cargo clippy --all-targets`: 13
    warnings, the same count as before (none from this diff).
  - Then 21 one-line mutations: 14 in the launcher (targeted `*HomeRound7*`, `*VoipCallPlan*`, `*VoipCallGuard*`,
    `*KidScreensEscape*`, `*PolicyResponseCompat*`, `*PinLockTask*`) and 7 in the server (`kid_lock`, `step10`). Each
    was reverted. The tree is clean apart from this file.

## Findings

**1 (Low) - icon eviction can lose a race with a render from the old list.** `Application.kt` `evictIcons` runs
before `loadApps()`, but the reload runs on `Dispatchers.Default` and posts later. Until the new list lands,
`apps.value` still holds the old `DetailedAppInfo`, whose `icon` is the **old APK's** `Drawable`, loaded eagerly in
its constructor. Two things can draw from it in that window:
- Home's `refreshApps` (a job already in flight, or one triggered by a resume or the relock listener), through
  `KidAvatars.renderAppIcon(..., { info.getIcon(context) })`.
- `AppIconLoader.key()` on the glance page or the time-rule screen.

Either one puts the old bitmap back under the **same** cache key (the key has no version in it). The new list then
gets a cache hit, and the old icon stays until the next update of that app or a process restart. The window is short
(one `getApps` run). An update with Home in front and the screen on is the realistic case. Fix: keep the packages
from the callbacks in a set and evict them again in the `appLoads` consumer, after `getApps` and right before
`apps.postValue`. Alternatively, put the package's `lastUpdateTime` into `appIconKey`. Add the device check "an
update while Home is in front" to PLAN's owed list.

**2 (Low) - four mutations survive: test gaps, not bugs.**
- (e) `voipRangEntries`' 7-day rule as `<=`: not caught. `org.example.old` sits exactly at the 7-day boundary, but
  `take(8)` cuts it anyway (11 newer entries), so the prune is never what drops it. Fix: a case with fewer than 8
  entries where only the age rule can drop one.
- (s5) the server's dedupe by package (`rang.retain(|r| seen.insert(...))`) removed: not caught. The duplicate
  `org.example.app3` is the oldest entry and falls past `truncate(8)`. Fix: a duplicate that is newer than the cap's
  cut, for example two entries in a short list.
- (s6) `lenient_voip_rang` with `map_while` (stops at the first bad entry): not caught. Every bad entry in
  `voip_rang_is_checked_entry_by_entry` comes after the good ones, and in `step10` the only entry after the bad
  `"yesterday"` is dropped anyway (future time). Fix: put a valid entry after a malformed one and expect it kept.
  This is r7-4's own point, so it deserves a pin.
- (k) `HomeGridAdapter.submit`'s `everything` without `ink != this.ink`: not caught. A wallpaper ink change would
  then leave the old labels' ink on every SAME tile. Fix: one source assert next to the existing
  `notifyDataSetChanged` count (`"metrics != this.metrics || ink != this.ink"`).

**3 (Info) - what the exclusion set doesn't cover.** `voipExcluded` = `smsPackages` + `ESSENTIAL_PACKAGES` + the
resolved essentials + our family. That is r7-2 as written, and it includes the system and default dialer, Telecom,
the emergency dialer, the clock and the keyboards. Two edge cases remain:
- `computeEnforcementPlan`'s `neverRestrict` packages (the SOS package, the WebView provider, the Play Store) are not
  excluded. They can ring only if the parent allowlists one and it posts a CALL notification with an FSI, which none
  of them does.
- If `systemDialerPackage()` resolves to null, an allowlisted system dialer is not excluded. `com.android.phone` and
  Telecom are always excluded, and a cellular ring already makes the lock step aside, so the cost is one wrong
  `voip_rang` row at most.

No change is needed. Worth one line in §1.3's device checks ("with the dialer allowlisted and calls unmanaged" is
already there).

**4 (Info) - an app that is the default SMS app is excluded even when it is also a VoIP app.** Signal no longer
takes the SMS role (2023), and Molly never did, so this is theoretical today. The old rule (`messagingAppPackages(rules,
null)`) did not exclude it. It fails narrow, so it is acceptable as is.

## The rules, with the evidence

1. **The candidate set: holds.**
   - `voipCandidates` returns `emptySet()` before any fallback for calls off and for `UnknownFailClosed`. It falls back
     to exactly the pre-r7 sets when `!policyKnown || voipAllowed == null` (mutations a and l are caught).
   - `voipAllowed` is the effective allowlist (the relock-blocked packages are filtered out, mutation c is caught),
     plus the messaging and usable apps, minus the exclusions (a set difference, so a duplicate is no bypass;
     mutation b is caught).
   - A relock-blocked or non-allowlisted messaging or usable app that comes back through `+ messaging + lockUsable` is
     still out: `computeEnforcementPlan` hides and suspends every controllable package outside the effective
     allowlist, and `eligible` drops suspended packages.
   - `voipAllowed` is never wider than the allowlist. During the override, `voipAllowed` still follows the
     allowlist, so it is narrower than what the override unsuspends.
   - `eligible` reads two volatiles plus `isPackageSuspended`. It makes no RoleManager call and never reads the raw
     allowlist (the glue test).
   - The exclusions on a real policy: `essentialNotificationPackages` resolves the system and default dialer, the
     emergency dialer, Telecom, cell broadcast, the clock, the SMS app and the IMEs. `smsPackages` adds
     `KNOWN_SMS_PACKAGES` whatever the role is.
   - A throw in `essentialNotificationPackages` skips `refresh`, which keeps the older, narrower value.
2. **No text is read: holds.** `VoipCallReader` is not in the diff, and `VoipCallGuardTest` is green. The new code
   (`noteRingWhileLocked`, `rangReport`, the `rang_v1` codec) handles only `ringingPackage`, `ringId` and wall ms.
   The log line names only the package.
3. **`voip_rang` holds only package names and times: holds.** On the launcher, `VoipRang(package, lastAtMs)` uses
   `isValidPackageName` in `voipRangEntries`. On the server, `VoipRang { package, last_at_ms }` goes through
   `valid_package_name`, `0 < t <= now + 5 min`, newest first and the cap of 8 (mutations s1, s2, s3 and s7 are
   caught).
   - The lenient parse can't break the page: an entry that doesn't deserialize is dropped, a non-list is empty, and
     the rest of `lock_state` stays.
   - The label comes from `app_rows` (the phone's reported label) and the time from `Zone::when`. Both render through
     `partials/note_row.html` as `{{ note.title }}`/`{{ value }}`, autoescaped with no `|safe`.
   - The rows show only with a Child PIN (mutation s4 is caught).
4. **A ring is recorded only over the lock: holds.** `voipRangDue` needs `locked` (mutation d is caught).
   `syncVoipRinger` runs on every VoIP pass (including the 2 s poll) and every lock-mode change, so a ring during
   which the phone locks is recorded too. The in-memory `rangRingId` means a new process may stamp the same ring
   again, which only moves that package's time.
5. **Contract: holds.** The launcher and the server change in one commit (`255eaf57`).
   - `LockStateReport.voipRang` is required, so `[]` is always encoded (`ServerJson` drops defaults).
   - `PolicyResponseCompatTest` pins the key set, `[]` and the entry shape `{"package","last_at_ms"}`.
   - `step10::lock_state_is_stored_sanitized_and_capped` covers a malformed entry and the rest of the state being
     kept, and a missing field giving `[]`.
   - `step10::lock_page_says_which_apps_rang_over_the_lock` covers the page.
   - The notes in `docs/notes/server/hardening-and-pins.md` are updated.
6. **The Home press to the top never fires for our own starts or under the lock: holds.**
   - Every Home start of ours goes through `HomeFront.bring` → `HomeFront.intent`, which carries `EXTRA_BROUGHT`:
     the lock leaving (`PinLockActivity`), the boot (`BootHome`), the dark lock-up (`animate = false`, same intent),
     `SelfUpdate.bringHomeAfterUpdate`, relock, role change, install mode end. `HomeFrontTest` keeps every start on
     that path. A grep finds no other HOME start (the `BootCover` and `AppEnforcer` hits are filters and queries).
   - The system's own HOME delivery while the night ground shows (design 32) has `contentShown == false` (and LOCKED,
     or `darkLockUp`): no scroll.
   - The extra is read from the delivered intent, not `getIntent()`. The system's HOME key always delivers a fresh
     intent, so no stale extra can carry over. `setIntent` is only bookkeeping; nothing else in `HomeActivity` reads
     `intent`.
   - `contentUp` is set in `resumeContent` and cleared in `onStop` and `showNight` (mutations f, g and m are caught).
     A press from another app finds Home stopped, so it doesn't scroll. That is narrower than Fossify, as documented.
7. **The conflated `loadApps()`: holds.**
   - `Channel(CONFLATED)` with one `consumeEach` consumer: a request during a run leaves one buffered element, so one
     more run follows, and the last request always runs.
   - Loads are serial, so an older list can't be posted last.
   - It runs on `SupervisorJob() + Dispatchers.Default`, with `postValue` as the main-thread hand-off as before.
     `AppDisplay.refresh`'s first-run rule is unchanged.
   - An `Exception` is caught per run, so the consumer survives. An `Error` would crash the process, as the old
     `launch` did.
   - Mutation n (`RENDEZVOUS`, which would drop a request during a run) is caught by the source test.
8. **DiffUtil: holds.**
   - `gridTileId` gives `app:<key>` for apps (the serialized `AppInfo`: package, activity and user), and
     `phone_book`/`settings`/`flashlight` for the rest. The "app:" prefix keeps them from clashing. `homeGrid` puts
     each `AppInfo` key in the grid once (the `infos` map is keyed by it). The music card is not a grid tile.
   - Ids are handed out per adapter from a map, never a hash (mutation h is caught).
   - The `BADGE` payload goes only to `GridTile.App` with the same `GridApp` (the label is included) and the
     identical bitmap. A new icon, a new name or the torch is FULL (mutation j is caught).
   - The payload path falls back to the full bind for any other payload or tile kind.
   - With `itemAnimator = null`, RecyclerView reuses the holder, so payloads are delivered.
   - New metrics or ink, and the first list, call `notifyDataSetChanged` (ink is unguarded, finding 2).
   - Clicks read `infos` at tap time, so a SAME tile with new `infos` still launches the current action.
9. **Icon eviction: one cache.** The glance page and the time-rule screen go through `AppIconLoader` to the same
   `KidAvatars.iconCache`. `evictPackage` scans every key and maps it back with `appIconKeyApp`, which takes off
   `|size|dpi` and an optional `|g=..|c=..` (glyph and colour values never contain `|`). So every size, density and
   parent choice goes. The drawer draws the reloaded list's own drawables. See finding 1 for the race.
10. **The dialog stays out of the MIT code: holds.** The only code change is in `actions/AppAction.kt` plus the
    strings; `ui/list/` and `apps/` are untouched (`git diff --stat`). `AppInfo.openSettings` is still defined in
    `ui/list/apps/ContextMenuActions.kt`, but now has no caller from a kid screen. `KidScreensEscapeTest` scans
    `actions/` and bans `openSettings(` and `startAppDetailsActivity` (mutation i is caught).

The design QA items, as built: r7-2 (`voipExcluded`), r7-3 (`voipAllowed` in `refresh`, the three-argument
`voipCandidates`), r7-4 (the lenient per-entry parse, `now_ms`, labels and zone named), r7-5 (`voipRangDue`). The
deviations in §1.4 are acceptable:
- The typed `Vec<VoipRang>` gives a cleaner stored shape.
- The 5-minute skew is reasonable.
- Using the page's zone is consistent with every other time on that page.
- The launcher's ring tests are pure rules plus a source test.

## Mutations

| # | Mutation (one line, reverted) | Result |
|---|---|---|
| a | `voipCandidates` ignores `policyKnown` (only `voipAllowed == null` falls back) | caught - `VoipCallPlanTest` fallbacks |
| b | `voipExcluded` without `ESSENTIAL_PACKAGES` | caught - `VoipCallPlanTest` exclusion set |
| c | `voipAllowedApps` keeps relock-blocked packages | caught - `VoipCallPlanTest` allowed set |
| d | `voipRangDue` without `locked` | caught - `VoipCallPlanTest` recorded only while locked |
| e | `voipRangEntries` keeps `>= 7 days` (`<=`) | **NOT caught** (finding 2) |
| f | `homePressToTop` without `!broughtByUs` | caught - `HomeRound7Test` |
| g | `homePressToTop` without `contentWasUp` | caught - `HomeRound7Test` |
| h | `gridTileId(Flashlight)` = `"settings"` (id clash) | caught - `HomeRound7Test` tile ids |
| i | the dialog's OK opens `openSettings(context)` again | caught - `KidScreensEscapeTest` |
| j | `tileChange` BADGE ignores `sameIcon` | caught - `HomeRound7Test` |
| k | `submit`'s `everything` without the ink check | **NOT caught** (finding 2) |
| l | `eligible` passes `policyKnown = true` | caught - `VoipCallPlanTest` glue |
| m | `onStop` no longer clears `contentUp` | caught - `HomeRound7Test` source |
| n | the app-load channel `RENDEZVOUS` (drops a request during a run) | caught - `HomeRound7Test` source |
| s1 | server: no `valid_package_name` on `voip_rang` | caught - `kid_lock` + `step10` |
| s2 | server: no future-time bound | caught - `kid_lock` + `step10` |
| s3 | server: skew 0 | caught - `kid_lock` |
| s4 | server: `rang_notes` without the Child PIN check | caught - `kid_lock::rang_notes_name_the_app_and_its_time` |
| s5 | server: no dedupe by package | **NOT caught** (finding 2) |
| s6 | server: `map_while` (a bad entry drops the rest) | **NOT caught** (finding 2) |
| s7 | server: cap 64 instead of 8 | caught - `kid_lock` |

## Verdict

**Approved.** There is no High or Medium finding. The security rules all hold:
- the candidate set and its fallbacks;
- the exclusions;
- no text read;
- `voip_rang` limited to package names and times;
- escaping on the page.

The four risky Home paths (lock leave, boot, dark lock-up, update) can't scroll. Before the merge, or straight to
the backlog:
- finding 1: evict again when the reloaded list lands (about five lines);
- finding 2: four small test additions.

Findings 3 and 4 need no change.
