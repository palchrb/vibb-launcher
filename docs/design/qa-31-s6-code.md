# QA 31 step 6 (code) - Device › Apps

Reviewer: code QA, 2026-10-13. Branch `design-31-s6`, `c8d0ad44..818ef495` (one commit, 26 files, server and docs).
I read it against `docs/design/31-pwa-redesign.md`:
- §1.2, §3.0, §3.2 (Device › Apps), §3.3, §4, §5 (§5.1 incl. §5.1.4, §5.2, §5.5, §5.6), §8 step 6 and §9;
- step 5's status and fix-round table, and "Step 6 implementation status" with its 8 choices;
- `qa-31-s5-code.md` and `qa-31-s13-code.md` for the format and the bar.

I also read the code the step touches or leans on:
- `handlers/devices.rs` (`toggle_app` in full, `app_anchor`, `latest_installed`, `app_rows`, `save_app_display`,
  `update_app_updates`, the device page's Apps row), `handlers/device_pages.rs` (`view_apps`, `render_apps`,
  `load_latest`), `app_downloads.rs`, `tracked_apps::phone_apps_href`/`allowed_count`,
  `device_api::is_package_name`/`cap_installed_apps`, `app_display::valid_package_name`, `attention::target`;
- `device_apps.html`, `partials/device_app_row.html`, the `app_display_form.html`, `device_detail.html`, CSS and
  head diffs; `static/scroll-restore.js`, `autosave.js`, `app-display.js`;
- the launcher's `collectInstalledApps` (`MdmSyncWorker.kt`: it never reports its own package);
- every changed test, `tests/device_pages.rs`'s step 6 part in full; the docs diff.

Line numbers are `818ef495`'s. No product code was changed: the mutations below were temporary and are reverted, and
`git status` was clean before this file was written.

**Verdict: merge; the fix round is small.** The step does what §8 step 6 asks:
- one GET route, `/devices/{id}/apps`, inside the admin router's full-auth, CSRF and session layers; unknown phone
  404; every read the page needs (the phone, its settings, its last report, `app_rows`, the names and icons) is a 500,
  never a shorter list; the device page degrades instead ("List unreadable" on the row, the blocks' "ticked" lines
  left out), which is right for a summary;
- the forms: the switches (`/apps/toggle`, `back=apps`) and the Name and icon saves return to the app's row
  (`#app-<package>`, `#catalog-<id>` without one), the Wi-Fi switch to `#downloads`; `back=music` and the old
  `anchor=music` to the device page's `#music`. The redirect table is built from the rendered forms and every
  fragment is checked against the page's ids (§5.1.6). Every auto-saving form has its own `<noscript>` Save;
- the phone for every write comes from the path; `back` only picks between two fixed targets; the Play-copy refusal's
  `app=` is a server-built anchor (`[A-Za-z0-9_.-]`), never echoed unless it matches a row;
- app names, statuses and release names are askama-escaped (pinned with `<b>Game</b>`); the tile's colour comes from
  the fixed colour table and the icon from `icon_known`;
- no API change: `policy_json_keys_snapshot` passes untouched, `launcher/` only has a doc line; the allowlist
  semantics (on = catalog row + allowlist + cancel uninstall; off = the reverse + uninstall unless preinstalled) are
  unchanged; the writes that were `.ok()` are 500s now;
- the device page's count and the Apps tab's agree by construction (both: reported apps on the list but Play's
  core, plus chosen catalog apps not reported; never Vibb - the launcher never reports itself), and a test pins it;
- the download labels are in Phone Setup's zone and §4.2's short grammar; the raw states never show.

On the choices the review was asked to look at:
- **(1) The switch keeps `#app-{pkg}` with JS on.** Right. Without JS the fragment is the only way back to a row in a
  long list; with JS, restoring the old offset would put another app under the finger, since the row just moved to
  the other group. The row lands at the top of the viewport (the tab bar is at the bottom, so nothing covers it), and
  the refused Play copy's reason sits inside the same cell, so it is in view too. It is a jump, but to the thing just
  changed, not to the top of the page.
- **(3) Vibb's row without a switch.** Right, and the server refuses its catalog id (400). A crafted post that names
  only Vibb's *package* still gets through (L3), but it can't do anything that matters.
- **(6)/(8) `is_package_name` for the toggle.** Right for every app a phone reports: the status intake drops anything
  else (`cap_installed_apps`), so a reported app can always be switched. The one row it can strand is a catalog app
  whose stored package predates that rule (L4).

What is left:
- **M1:** the installing reload can fire while a save is on its way and cancel it.
- **L1:** the reload's pause is untested, and the 400 refusal page reloads too (a re-POST once the form is closed).
- **L2:** nothing pins the Network row's time as local (a mutation survived).
- **L3:** Vibb's refusal keys on the catalog id only; a post with just its package passes.
- **L4:** a catalog app with a legacy, non-package `package_name` can no longer be switched either way.

Counts: 0 High, 1 Medium, 4 Low, 5 Nits.

## What I ran

All cargo runs used my own target dir (`~/.cache/handy-target/qa-31-6`), under `~/build.lock`.
- **`cargo test`, unchanged:** 788 passed, 0 failed.
- **`cargo fmt --check`:** clean.
- **`cargo clippy --all-targets --locked`:** the same 13 warnings as on main (`models.rs` dead fields, `dns_engine.rs`,
  `storytel.rs`, the old `sort_by` in `devices.rs:1369`). None is in the step's new code.
- **`node --test server/jstest/*.test.js`:** 16 passed.
- **The mutations.** Each was run with targeted filters (13 with the whole `tests::` module), then reverted:

  | # | Mutation | Caught by |
  |---|---|---|
  | 1 | no package check in `toggle_app` (any string into the allowlist) | `the_apps_switch_refuses_bad_input_and_the_page_fails_loudly` |
  | 2 | Vibb's catalog id toggles like any app | `the_apps_switch_refuses_bad_input_…` |
  | 3 | an unknown phone in the path goes on (no 404) | `the_apps_switch_refuses_bad_input_…` |
  | 4 | a switch returns to `/devices/{id}/apps` without its fragment | `apps_forms_return_to_their_rows` |
  | 5 | the switch's `<noscript>` Save removed | `apps_holds_the_moved_forms` |
  | 6 | the Play-copy refusal returns without `app=` and its fragment | `play::catalog_app_installed_from_play_is_refused` |
  | 7 | the app name `|safe` | `apps_holds_the_moved_forms` |
  | 8 | the downloads' "since" in UTC (`Zone::utc_fallback()`) | `app_downloads::tests` (the page test's dates are days old, so both read "6 Oct") |
  | 9 | the reload never pauses (`if (false)` for the open-form check) | **nothing** (L1) |
  | 10 | the device page's count includes Vibb | `the_apps_tab_lists_the_phones_and_the_catalog`, `apps_holds_the_moved_forms` |
  | 11 | `anchor=music` no longer read | `music::the_device_card_ticks_entries_and_switches_the_app` |
  | 12 | an unreadable `device_app_display` renders "its own" for every app | `the_apps_switch_refuses_bad_input_…` |
  | 13 | the Network row's time as the UTC clock (`at.format("%H:%M")`) | **nothing** (L2) |

  Two of thirteen survived (L1, L2).

## Medium

### M1. The installing reload can cancel a save that is on its way
`templates/device_apps.html:81-97`.

While an app installs, `tick()` runs every 3 s and calls `location.reload()` unless a Name and icon `<details>` is
open. Nothing stops it once a save has started: a switch or the Wi-Fi switch calls `form.submit()` (autosave.js), a
Name and icon Save is a plain submit, and the POST and its 303 take a while from a phone over the tailnet to a Pi. If
the timer fires in that window, the reload is a newer navigation and the browser drops the pending one. The parent
then sees the same page with the switch back where it was. Depending on timing the POST never left, or the server
dropped the handler when the connection closed, which can leave the multi-write toggle half done (the
`device_tracked_apps` row without the allowlist entry). This is exactly when a parent uses the page: an install was
just started by a switch, and they go on to the next one. With 3 s ticks and a half-second round trip, about one
switch in six is at risk. The old device page had a one-shot version of the same race; the step made it a loop on
the page where every switch lives.

**Fix:** stop the timer once the page is leaving. For example, keep `var leaving = false;`, set it from a capturing
`submit` listener and from a capturing `change` listener on `[data-autosave]` (autosave.js submits from `change`, and
`form.submit()` fires no `submit` event), and have `tick` return without rescheduling when `leaving` is set. Adding
`pagehide` does no harm. Pin it with a node test like `jstest/autosave.test.js`: a fake document with an installing
page, a `change` on an autosave control, advance the timers, and expect no `reload`. That also covers L1's first half.

## Low

### L1. The reload's pause is untested, and the refusal page reloads too
`templates/device_apps.html:81-97`, `src/handlers/devices.rs` (`save_app_display`'s `refuse` → `render_apps`).

- Mutation 9 (never pause) passed every test: nothing checks the open-form condition. It is the one thing that keeps
  a half-typed name from being lost.
- A refused Name and icon save is rendered as the answer to the POST (`/devices/{id}/apps/display`), with the reload
  script when something installs. While the form is open the script waits. Once the parent closes the form (gives up,
  or reads the row), the next tick reloads a POST response: the browser asks to resend the form, or resends it.

**Fix:** don't render the reload on the refusal (`installing: installing && entered.is_none()`), and pin it: the
400 page has no `location.reload`, and the GET page's script has the `details.app-display[open]` check. M1's node
test can also check the pause.

### L2. Nothing pins the Network row's time as local
`src/handlers/device_pages.rs:1247`, `tests/tracked_apps.rs` (`app_downloads_are_stored_and_shown`).

Mutation 13 (the UTC clock instead of `zone.when`) passed. The test asserts only `"Mobile data · "` and the ⓘ text.
qa-31-s5-code L3 asked for the same pin on Web Filter's log; local time is a §4.2 rule on every page.

**Fix:** in that test, set Phone Setup's zone to Europe/Oslo and the report's `reported_at` to a fixed instant earlier
today (or yesterday, to avoid midnight), and assert the row's local clock and its `title`'s exact local time.

### L3. Vibb's refusal keys on the catalog id only
`src/handlers/devices.rs:1696-1706`.

"Vibb's own row is a 400" holds only when the post carries Vibb's `tracked_app_id`. A crafted post with
`package_name=me.vibb.launcher` and no id passes. Turning it off removes Vibb's package from the allowlist and looks
for an uninstall to queue. Turning it on adds it. Neither has an effect: the launcher never reports itself, so no
uninstall is queued, and it is the Home app, which the allowlist does not hide. But the docs (`server/CLAUDE.md` "the Apps page
shows it 'Always on' … and `toggle_app` refuses its id") and choice 3 read as a guarantee, and the allowlist gains or
loses an entry nobody sees.

**Fix:** one query covers both cases: `SELECT 1 FROM tracked_apps WHERE is_launcher = 1 AND (id = ? OR (package_name
!= '' AND package_name = ?))` → 400. Add the package-only post to the test's refusal table.

### L4. A catalog app with a legacy package name can no longer be switched
`src/handlers/devices.rs:1667-1671`.

`tracked_apps.package_name` used to be free text (see `tracked_apps.rs:2139`). It has been filled from the APK or the
phone's report since then, but an older install can still have a typed value that isn't `is_package_name` (a space,
a hyphen). `app_rows` lists that row (`catalog-<id>`), and its form posts that package. `toggle_app` now answers
"Not an app - nothing was changed." as a bare 400 text page, so the app can be turned neither on nor off from the
page, and an allowlist entry it once added can't be removed. Before this step that worked. Choice 8 protects the apps
a phone reports, not this one.

**Fix:** with a `tracked_app_id`, validate the package against the catalog row instead: refuse a form package that
is neither a valid package name nor equal to the catalog's own value. Or render such a row without its package
(the id is enough to select it) and keep the 400 for the rest. Or treat it as a data fix: a startup check that
blanks a catalog `package_name` that isn't `is_package_name`, so the phone's report fills it again. Test it with one
catalog row whose package is `"Element X"`.

## Nits

- **N1.** A catalog app deleted since the page loaded returns to `/devices/{id}/apps` without a fragment
  (`devices.rs:1706`). Without JS that is the top of the page. With JS the restore works, but `scroll-restore.js`
  then sets `history.scrollRestoration = "manual"` on that history entry, so each later installing reload of it
  lands at the top. Return to `#allowed` or `#not-allowed` (the group the tap came from), or to the page's first
  group. Not likely, but the test already pins the redirect.
- **N2.** The device page's Apps row says "List unreadable" when `app_rows` fails on a DB error too
  (`devices.rs:1455-1462`). That text names the corrupt-allowlist case. "Couldn't read" says what happened.
- **N3.** `app_anchor`'s fallback `None => "allowed"` (`devices.rs:1617`) is the Allowed section's id. It can't be
  reached today (the intake drops unnamed apps, and `toggle_app` refuses such a post), but if it ever were, a
  duplicate id would follow. `"apps"`, or `unreachable`-style logging, avoids the collision.
- **N4.** Each installing reload keeps `?notice=from_play&app=…`, so the refused row's red reason shows until the
  install ends or the parent navigates. The reload also closes an open ⓘ. A
  `history.replaceState(null, "", location.pathname + location.hash)` after the flash is shown fixes the first.
  The second is cosmetic.
- **N5.** The Status card's blocked-apps lines still say "Ticked under Apps" / "Not ticked … until you tick it too"
  (`device_detail.html:73`, `window_relock::blocks_note`'s neighbours), but the page they link to has switches now:
  "Allowed under Apps" / "turn it on".
