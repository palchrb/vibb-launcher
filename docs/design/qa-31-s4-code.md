# QA 31 step 4 (code) - Lock Screen & PINs

Reviewer: code QA, 2026-10-13. Branch `design-31-s4`, `571f86fa..40b2b329` (one commit, 32 files, server and docs).
I read it against `docs/design/31-pwa-redesign.md`:
- §3.0, §3.2 (Lock Screen & PINs), §4, §5 (§5.1, §5.2, §5.5, §5.6), §8 step 4 and §9;
- "Step 3 implementation status" and "Step 4 implementation status" with its 10 choices;
- `qa-31-s3-code.md` for the format and the bar.

I also read the code the step touches or leans on:
- `handlers/device_pages.rs` (`view_lock`, the loaders), the `devices.rs` diff (`app_rows`, `lock_screen_card`,
  `update_kid_lock`, `update_screen_timeout`, the device page), `kid_lock.rs`, `parent_pin.rs`,
  `handlers/parent_pin.rs`, `lock_glance.rs`, `handlers/lock_glance.rs`, `handlers/wallpapers.rs`, `attention.rs`,
  `display_time.rs` (`when`, `until`, `relative`);
- `device_lock.html`, the `device_detail.html`, `note_warning.html`, `note_row.html` and CSS diffs;
- every changed test, `tests/device_pages.rs`'s step 4 part in full;
- `scroll-restore.js`, `autosave.js`, the admin router's layers in `main.rs`;
- the docs diff (`server/CLAUDE.md`, `docs/testing/emulator.md`, `PLAN.md`).

Line numbers are `40b2b329`'s. No product code was changed: the mutations below were temporary and are reverted, and
`git status` was clean before this file was written.

**Verdict: merge; the fix round is small and can go with step 5.** The step does what §8 step 4 asks:
- one GET route, `/devices/{id}/lock`, inside `require_full_auth`, CSRF and the session layer; unknown phone 404;
- all six forms keep their POST routes and field names (`kid-lock` set and clear, `parent-pin`, `screen-timeout`,
  `lock-wallpaper`, `lock-screen`, `lock-screen/apps`), and no handler logic changed apart from where it redirects;
- Remove Child PIN… is a `<details>` confirm (M6); the Parent PIN's "Remove it instead" stays a checkbox;
- the two PIN forms land on their group (`#child-pin`, `#parent-pin`) where the flash is; the four auto-saves return
  to the page with no fragment, so `scroll-restore.js` keeps the exact place (qa-31-s3-code M3's rule);
- no PIN is ever rendered or put in a URL: the fields have no `value`, the flashes are fixed texts from two code
  tables, an unknown `?notice=` shows nothing;
- the redirect table is built from the rendered forms, in page order, and catches an extra or moved form (#11 below);
- the PWA's PIN times are local (`event_row`, `notices`), the security log keeps its UTC lines (step 14);
- no API or contract change: the policy JSON, `phone_vectors.json` and the launcher are untouched.

What is left are gaps in what the tests pin, a stale state row, and copy:
- **L1:** a refused Child PIN re-opens its form, but no test says so (a mutation survived).
- **L2:** nothing pins that the Parent PIN field is a password field (a mutation survived).
- **L3:** "On the phone · Locked" carries no time, so a week-old report reads as the state now.
- **L4:** a failed policy read renders "Child PIN · Off", "Parent PIN · Not set".
- **L5:** two warning sentences use words §4.2 keeps out of the visible text.
- **L6:** the page for a phone not set up contradicts §3.2's "After setup" rows; the spec should say so.

Counts: 0 High, 0 Medium, 6 Low, 4 Nits.

## What I ran

All cargo runs used my own target dir (`~/.cache/handy-target/qa-31-4`), under `~/build.lock`.
- **`cargo test`, unchanged:** 757 passed, 0 failed (the implementer's count).
- **`cargo fmt --check`:** clean.
- **`cargo clippy --all-targets --locked`:** the same 13 warnings as on main: `models.rs` (9), `dns_engine.rs` (2),
  `storytel.rs` (1), `devices.rs:1414` (the old `sort_by`; it moved from `:1445` with the `app_rows` cut). None is in
  the step's new code.
- **`node --test jstest/*.test.js`:** 16 passed.
- **The mutations.** Each was run with targeted filters, then reverted:

  | # | Mutation | Caught by |
  |---|---|---|
  | 1 | `kid-lock` → `/lock?notice=…` without `#child-pin` | `device_pages::lock_forms_return_to_the_page`, `step10::kid_pin_needs_an_override_pin_first`, `step10::clearing_the_kid_pin_switches_the_lock_off` |
  | 2 | `lock-screen` / `lock-screen/apps` → `/lock#notifications` | `lock_forms_return_to_the_page`, `lock_glance::the_text_switch_saves_logs_and_nudges`, `lock_glance::ticking_and_unticking_…` |
  | 3 | Remove Child PIN outside its `<details>` | `device_pages::removing_the_child_pin_confirms_with_details` |
  | 4 | a refused Child PIN keeps its form closed (`child_open: false`) | **nothing** (L1) |
  | 5 | the Parent PIN field `type="text"` | **nothing** (L2) |
  | 6 | "Recent use" times as `%H:%M UTC` instead of `zone.when` | `parent_pin::tests::every_event_row_in_local_time`, `tests::parent_pin::the_group_lists_events_…` |
  | 7 | a refused Parent PIN keeps its form closed (`parent_open: false`) | `parent_pin::flashes_render_inside_their_groups` |
  | 8 | `note_warning.html` prints the adb fix in the sentence, no ⓘ | `step10::lock_page_warns_about_the_lock_state` |
  | 9 | Attention "lock off" → `/lock#screen-lock` | `attention::tests::rows_link_to_the_card_that_fixes_them` |
  | 10 | a phone not set up gets 404 on `/lock` | `device_pages::a_phone_not_set_up_can_get_its_pins_first` |
  | 11 | an extra form on the page, not in the table | `lock_forms_return_to_the_page` (the page-order check) |

  Two of eleven survived (L1, L2). The device-id-in-path, CSRF and auth guards are unchanged code with their own
  tests (`lock_glance::refused_saves_write_nothing`, the admin-auth matrix), so I did not mutate them again.

## Low

### L1. A refused Child PIN re-opens its form, untested
`src/handlers/device_pages.rs:810` (`child_open`), choice 2.

Mutation 4 (`child_open: false`) passed all of `device_pages`, `step10` and `parent_pin`. The Parent PIN's twin
(mutation 7) is pinned by `flashes_render_inside_their_groups`. Without the re-open, a parent who typed 5 digits
lands on a closed row and must find and tap it again; that is the whole reason for choice 2.

**Fix:** in `parent_pin::flashes_render_inside_their_groups` (it already loads `?notice=needs_override`), assert
`group(&html, "child-pin").contains("<details class=\"edit cell\" open>")`, and that `?notice=saved` has no `open`.

### L2. Nothing pins the Parent PIN field as a password field
`templates/device_lock.html:91`.

The field moved from the old card with `type="password"` and `autocomplete="new-password"`, which keep the PIN off
the screen while a child may be watching (the ⓘ's own "Typed it where your child could see?") and out of form
autofill history. Mutation 5 (`type="text"`) passed every test. The Child PIN's field is `type="text"` on purpose
(the parent reads it back to the child), so a later "make them consistent" edit is a real risk.

**Fix:** in `lock_screen_and_pins_holds_the_moved_forms`, assert the `new_pin` input has `type="password"` and
`autocomplete="new-password"` (find the `<input` around `name="new_pin"`, as the test already does for the
`<details>`).

### L3. "On the phone · Locked" has no time, so an old report reads as now
`src/kid_lock.rs:282-302` (`LockNote::Waiting`, `On`, `WrongPins`, `SteppedAside`), `device_lock.html:37-39`.

The rows come from the last `lock_state`, whatever its age. An Offline phone last heard from on Sunday shows "On the
phone · Unlocked", "Wrong PINs in a row · 3" on Friday, with nothing saying when. Elsewhere the redesign marks such
facts: Attention rows say "At last report" for an Offline phone (§3.2), and About shows Last full report. The old
card's "right now" had the same flaw, but the rows now look like live values.

**Fix:** pass the report's `reported_at` (and `zone`, `now`) to the template, and add the time to the "On the phone"
row: the value "Locked · 10:59" (`Zone::when`) with the exact time in `title`, or a `row-sub` "At last report 10:59"
only when the report is older than the Online window. Test it with a report from yesterday.

### L4. A failed policy read shows the PINs as Off / Not set
`src/handlers/device_pages.rs:757` (`load_policy`, `:359-371`).

`load_policy` turns a DB error (and a missing row) into `DevicePolicy::default()`. On Lock Screen & PINs, whose job is
to say whether the PINs are set, a transient read error renders "Child PIN · Off", "Parent PIN · Not set" and no
Remove Child PIN… - a false "this phone has no lock". About and Advanced share the loader, but there it costs at worst
a wrong switch state; here it misleads about security.

**Fix:** make `load_policy` return `Result<DevicePolicy, Response>`: a DB error is the 500 "Couldn't read this phone -
check the server log." that `load_device` already gives; a missing row can keep the default. Use it in `view_lock` (and
the other two pages, which then fail the same way).

### L5. Two warning sentences use words §4.2 keeps behind the ⓘ
`src/kid_lock.rs:257` (CrashGuard: "The phone tries it again at its next sync"), `:251` (KeyguardNotDisabled: "Check
that Vibb still manages the phone (device owner).").

§4.2 lists "sync" among the words never shown outside an ⓘ panel or Advanced, and the status section says the flashes
were reworded to drop "sync"; these two warnings sit on Lock Screen & PINs, visible. "Device owner" is the Android
term §4.1 asks to avoid. The test `warnings_are_short_and_keep_commands_behind_the_info` checks titles and `adb`, not
these words.

**Fix:** "The phone tries it again within 10 minutes of its next check-in; update Vibb if this repeats." and "Android
refused, so Vibb's lock stays off. Vibb may no longer manage this phone." with "device owner" in a `fix` behind the ⓘ.
Add "sync" and "device owner" to that test's hidden-words list.

### L6. Lock Screen & PINs for a phone not set up: right, but §3.2 says otherwise
`src/handlers/device_pages.rs:742-747` (the doc comment), `tests/device_pages.rs:711-724`; spec §3.2 ("A phone that
hasn't enrolled … The sub-page rows show greyed out with 'After setup'").

Serving the page before setup is the better behaviour: the first policy the phone fetches then already has a Parent
PIN, so it is never managed without one, and the old device page offered the PIN cards before setup too. But it is not
among the 10 recorded choices, and step 10, building the not-enrolled summary from §3.2, would grey this row out.

**Fix:** add it as choice 11 in "Step 4 implementation status", and amend §3.2's not-enrolled paragraph: "The sub-page
rows show greyed out with 'After setup', except Lock Screen & PINs: the PINs can be set first." Step 10 keeps the
row live.

## Nits

- **N1.** `server/CLAUDE.md:201` still says `lock-wallpaper` goes "back to `/devices/{id}`"; `:811` "Wrong tries stay
  on the device page" (they are on Lock Screen & PINs › Recent use now, and as a count in the Status card). Both
  should name `/devices/{id}/lock`.
- **N2.** `devices.rs:1557`: the device page calls `parent_pin::card` only for `easy` and `notices`, but the card also
  loads and formats the last 10 events (`shown`, `event_row`) that page never shows. Build `notices` there from
  `recent` alone, or let `card` skip `shown` when asked; one query less per device page view.
- **N3.** `device_pages.rs:762` copies the `capability` closure from `:845` (Advanced) in the same file, the fifth copy
  of `caps.contains(&format!("\"{name}\""))` in the crate. A `fn has_capability(latest: Option<&DeviceStatus>, name:
  &str) -> bool` in `device_pages` (used by both pages) is enough here.
- **N4.** `LockNote::Off(Inactive::Other(code))` (`kid_lock.rs:271`) shows the phone's raw code in the visible
  sentence, "The phone reported \"x\"." It is escaped and capped at 64 chars, and About's `PolicyProblem::Other` does
  the same, so it is consistent; but a code is debug text (§4.2) and belongs in `fix` with a sentence like "Vibb
  reported a reason this server doesn't know."

## The implementer's choices

- **#1 (the PIN rows open a form):** agree. `required` only on the Child PIN, whose remove has its own form; the
  Parent PIN's empty field + "Remove it instead" is a valid post.
- **#2 (a refusal opens the form again, also an easy Parent PIN):** agree; L1 pins the Child half.
- **#3 ("Recent use" as its own group):** agree. Ten rows inside the PIN group would push the footer and its ⓘ away.
- **#4 ("On the phone now" only while it differs):** agree; `device_api::screen_timeout_policy_form_and_report` pins
  both sides.
- **#5 (the device page keeps the Status card's Parent PIN notices and the no-PIN card):** agree until step 10. The
  links go to `/lock#parent-pin` and the notices are local time.
- **#6 (`Note.fix` behind an ⓘ):** agree. Advanced's Unsuspendable note may keep its packages in the sentence:
  §4.2 allows package names on Advanced.
- **#7 ("No Parent PIN set" as a warning only when managed):** agree; it mirrors the Attention alarm, so the row
  lands on its explanation.
- **#8 (labels from the mockup, no "Show what's playing"):** agree; there is no such setting.
- **#9 (the Child PIN ⓘ drops fingerprint, safe mode, Locate):** agree. Safe mode has its own warning while allowed.
- **#10 (an unlisted tick keeps its package name as label):** agree; the server knows no other name.

## Checked and fine

- **Access:** `/devices/{id}/lock` is in `admin_routes` (`main.rs:746`), behind `require_full_auth`, the session layer,
  `csrf_guard` and the gate. GET changes nothing. Every form carries the device only in its path; no form has a
  device id field to disagree with it.
- **PINs:** no `value=` on either PIN input; `?notice=` maps through `kid_lock::flash_text` and
  `parent_pin::flash_text`, whose code sets don't overlap (`saved`/`cleared`/`invalid`/… vs `parent_pin_*`/
  `override_*`); a bogus code renders nothing (tested). The validation, the cross-checks, the `CASE` that keeps the
  Parent PIN while a Child PIN is set, the events and the nudges are unchanged code.
- **Save never jumps to the top:** the four auto-saves return to `/devices/{id}/lock` with no fragment, so
  `scroll-restore.js` restores the exact position with JS (same path, no hash). The PIN forms use the group anchor,
  where the flash renders under the rows; their `<details>` re-opens on a refusal. Without JS: every auto-save has a
  `<noscript>` Save in its own form (tested); the PIN forms and the confirm are plain forms. A no-JS auto-save lands at
  the page top, which is §5.1.4's trade for same-page auto-saves (as Advanced in step 3).
- **Moved links:** the foreign-app steps, the Parent PIN notices, the easy-PIN line and the no-PIN card point to
  `/devices/{id}/lock#parent-pin`; Attention's no/easy Parent PIN → `#parent-pin`, lock off → `#child-pin`; both
  anchors exist (`devices_list::the_badge_counts_…`). No `#screen-lock`, `#screen-timeout`, `#lock-wallpaper` link is
  left in `src`, `templates` or `static`.
- **Typed texts:** `kid_lock::LockNote`/`Inactive` and `lock_glance::Note` replace the sentences; `inactive_warning`
  and `event_line` are gone with their tests; `kid_lock::lock_off` (the Attention fact) uses `Inactive::parse`, so the
  alarm and the page's warning agree. The adb commands and the VoIP package are only in `fix`, behind "How to fix it".
- **Times:** "Recent use" uses `Zone::when` with `Zone::exact` in `title`; a window's end gets its date when it ends on
  another local day (`end_text`, the past-midnight case tested); the lockout notice's end uses `Zone::until`. The
  Oslo unit tests and the page test check that no "UTC" shows. The security log keeps `log_line`'s UTC on purpose.
- **Device page:** `lock_value` and the page's "On · N digits" both come from `policy_kid_lock`, so a half-written
  row reads as off on both. `app_rows` is a straight cut of `render_device`; the device page destructures what it
  used before.
- **CSS:** `style.css?v=13`; no `:has()`, nesting or `color-mix()`; the ⓘ in a warning keeps a 44 px target;
  `prefers-reduced-motion` is not needed beyond the existing global rule for the chevron's transition.
- **Docs:** `docs/testing/emulator.md`, `PLAN.md` and the Locate page's hint follow the move (N1 aside).
