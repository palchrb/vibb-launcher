# Code QA - launcher round 7, part B (design 51: "Show the text" per app on the lock's glance page)

- Commits: `096d86c0..b1184d73` on branch `r7-b`, one commit `b1184d73` (server + launcher + docs): migration 0083,
  the policy's per-app shape, the route `POST /devices/{id}/lock-screen/apps/text` in place of `save_text`, the Lock
  page's per-app cell, the launcher's `GlancePolicy(apps: Map<String, Boolean>)` and `showsText`, both contract pins.
- Read: root, `server/` and `launcher/` `CLAUDE.md` (the glance page, PIN lock, the listener, the policy contract, the
  no-jump rule); `docs/design/51-glance-text-per-app.md` (whole, incl. its Revision and Implementation status);
  `docs/design/qa-r6-server-design.md` 51-1…51-7; the full diff; the old parser `lockGlancePolicy` at `096d86c0`;
  `static/style.css` (`.cell`, the hairline rules) and `static/scroll-restore.js`'s header; `startup::pre_migration_backup`.
- Ran: server `cargo test`: **1086 passed, 0 failed**; `cargo fmt --check` clean; `cargo clippy --all-targets`: 13
  warnings, none in a line this commit touches (`models.rs:288…561`, `dns_engine.rs:54-55`, `storytel.rs:97`,
  `devices.rs:1305` - the local toolchain is newer than CI's pinned 1.99.0). Launcher `testDebugUnitTest
  -PwarningsAsErrors=true --tests '*GlanceRows*' --tests '*PolicyResponseCompat*' --tests '*LockGlanceGuard*'`:
  **BUILD SUCCESSFUL, 84 tests, 0 failures**.
- Mutations (one line each, each reverted with `git checkout --`; the tree is clean afterwards):

  | # | Mutation | Caught by |
  |---|---|---|
  | S1 | 0083's backfill `lock_glance_text = 1` -> `= 0` | `the_text_migration_takes_each_phones_old_switch` |
  | S2 | `save_app_text`'s `rows_affected() == 0` -> `== 99` (no 404 for an unticked app) | `refused_saves_write_nothing` |
  | S3 | `save_app_text` redirects to `#notifications` | `an_apps_text_switch_saves_logs_nudges_and_a_retick_resets_it`, `device_pages::lock_forms_return_to_the_page` |
  | S4 | `policy_apps` sends `show_text: false` always | `the_policy_apps_are_sorted_valid_and_unique`, `the_policy_carries_…_each_with_its_text`, the switch test |
  | S5 | `card_apps`' listed `show_text` = `contains_key` | `the_page_lists_the_allowed_apps_with_their_ticks` only (see Low 3) |
  | S6 | template `{% if app.on %}` -> `{% if true %}` (text switch on unticked rows) | `the_page_lists_the_allowed_apps_with_their_ticks` |
  | S7 | `save_app_text`'s `on = true` | `an_apps_text_switch_saves_…` |
  | S8 | the log line always "on" | `an_apps_text_switch_saves_…` |
  | S9 | `valid_package` check -> `is_empty()` | `refused_saves_write_nothing` (400) |
  | L1 | reader `showText = policy.apps.values.any { it }` | `LockGlanceGuardTest` identifier allowlist (incidental) |
  | L2 | `glanceApp`: `"show_text": "true"` (a string) accepted | `GlanceRowsTest` the policy |
  | L3 | old-shape string -> text on | `GlanceRowsTest` the policy, `PolicyResponseCompatTest` |
  | L4 | `showsText` = `apps[pkg] != false` (unknown = text) | `GlanceRowsTest` levels + the policy |
  | L5 | `glanceApp` drops `isValidPackageName` | `GlanceRowsTest` the policy, `PolicyResponseCompatTest` |
  | L6 | reader's tick gate `!in policy.apps` -> `in` | **survives** (Medium 1; pre-existing, the rows filter still drops them) |
  | L7 | reader `showText = true` (every ticked app's text) | **survives** (Medium 1) |
  | L8 | reader `showsText(policy.apps.keys.first())` | `LockGlanceGuardTest` identifier allowlist (incidental) |

## What was verified

- **Security**: text reaches the lock only at `GlanceLevel.TEXT`, which `glanceLevel` gives only for `showText` true,
  and the reader passes `policy.showsText(sbn.packageName)` (`GlanceReader.kt:114`) = `apps[pkg] == true`: a
  sender-only app, an unticked app and an unknown policy are all false (L4 caught). `readTexts` is still called only
  at TEXT (guard test). A show_text change makes a different `GlancePolicy` (map equality), so `refreshPolicy` clears
  the notices and re-sweeps (`GlanceStore.kt:143-151`): turning text off drops the text already read. No log line
  carries text: the server logs the package and on/off; the launcher's new `$withText` is a count, allowlisted.
- **PWA**: each app is one `div.cell#lock-screen-app-<pkg>` holding the tick form and (ticked only) the text form; both
  land on that id (S3 caught), `scroll-restore.js` keys on the form's id'd ancestors, so the cell is found for both;
  `every_form_on_every_page_lands_on_its_own_group` passes. The hairline rules are `.group > .cell ~ .cell::before`
  (direct children only), so the two forms in one cell have no hairline between them; the app above/below keeps its
  own. The footer now reads "Missed calls always show. Anyone holding the phone can read the message text." under the
  Notifications group; the `#lock-screen-text` row is gone and tested absent.
- **Migration**: `ALTER … ADD COLUMN show_text … DEFAULT 0` then a per-phone backfill from `device_policy` (S1
  caught; the test has one phone with and one without the old switch, each with two ticks). A phone without a
  `device_policy` row gets 0 (the safe side). `migrations_73_to_83_in_order` extended; `pre_migration_backup` takes the
  max migrator version by itself (nothing to set per migration); `server/CLAUDE.md` says the next free number is 0084.
  The trigger's default row and a re-tick get 0 (tested).
- **Contract**: `tests/device_api.rs` `policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest` move in
  the same commit to `{"apps":[{"package":"io.element.android.x","show_text":false}]}`; `models::PolicyResponse`'s and
  the launcher DTO's docs, `hardening-and-pins.md` and `launcher/CLAUDE.md` describe the new shape.
- **Compatibility**: the old parser at `096d86c0` maps `apps` through `(it as? JsonPrimitive)?.takeIf { isString }`,
  so objects are dropped -> empty apps -> missed calls only, and the missing top-level `show_text` reads false. The new
  parser reads an old server's strings as sender only and ignores its top-level `show_text` (tested in both classes).

## Findings

**1 (Medium) - the reader's two glue lines that enforce the rules are not guarded (L6, L7 survive).**
`launcher/app/src/main/java/com/kidslauncher/mdm/badges/GlanceReader.kt:114` (`showText =
policy.showsText(sbn.packageName)`) is the one place where design 51's rule ("text only for an app whose own switch
is on") becomes behaviour. Changing it to `showText = true` keeps all 84 targeted tests green, and every ticked app's
message text would show on the lock. The pure halves (`showsText`, `glanceLevel`) are tested; the call site is not.
This is the same gap as qa-r6-b-code M3. Line 90's tick gate (`sbn.packageName !in policy.apps`) also survives
inversion. That one is from design 25, not this commit. `glanceRows`' own `in policy.apps` filter still keeps
unticked rows off the lock, but the reader would read names from every app.
Fix: two source asserts in `LockGlanceGuardTest`, in the style of `the reader's suspended flag…`:
`reader.contains("showText = policy.showsText(sbn.packageName), messaging = messages.isNotEmpty())")` and
`reader.contains("if (sbn.packageName !in policy.apps || sbn.user != Process.myUserHandle()) return null")`.

**2 (Low) - PLAN's design 51 entry still describes the rejected string semantics and has no status.** `PLAN.md:796-805`
says "a plain string = the old global `show_text`" and "back to its group". The design and the code decided otherwise:
a string means sender only and the old switch is ignored (51-1), and each save goes back to the app's row (51-2). The
entry also doesn't carry what is still owed.
Fix: update the entry to the implemented shape and add a status line: implemented on `r7-b`; the release-note line for
both releases (an older launcher shows missed calls only until it updates; an older server's ticks read as sender
only); the device check (§3).

**3 (Low) - `card_apps`' listed-row `show_text` is pinned only by the page test (S5).** `server/src/lock_glance.rs:194`.
The unit test `card_apps…` (`lock_glance.rs:355-400`) has no listed app that is ticked with its text off: Element X is
ticked with text on, and Signal is not ticked. So `ticked.contains_key(..)` in place of `== Some(&true)` passes the
unit test. Only the HTML test catches it, through Element X's "Sender's name only".
Fix: in the unit test, tick `org.signal` with `false` and expect `on: true, show_text: false`.

**4 (Low, note) - the device check §3 is still owed.** The code can't show this, so it is not a code finding: two ticked
apps, one with its text on, a message from each, and neither switch jumping the page. It belongs in Finding 2's status
line, so it isn't lost before the `launcher-v*` and `server-v*` tags.

## Verdict

**Approved with fixes.** No High finding. The policy shape, the migration, the route, the no-jump landing and both
contract pins are correct, and every mutation in the new code is caught except the reader's call site. Before the
merge: fix Medium 1 (two guard asserts) and Low 2 (PLAN), both small. Low 3 can go with them or to the backlog. Low 4
is the owed emulator check.
