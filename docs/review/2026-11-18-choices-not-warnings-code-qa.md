# A parent's choice is never a warning - code QA (2026-11-18)

Branch `copy-round-113`. Reviewed: 084388982 (the calls-off rule with unmanaged calls), 57c6889f3 (safe mode, no
Child PIN, location, Chrome, the SMS app; `partials/note_warning.html` / `note_row.html`), 356695c7a (the Attention
test), and the conflict resolutions of the merge c3c6c6b67 (`server/src/kiosk_escapes.rs`,
`server/src/tests/unified_push.rs`). Read against the user's rule (2026-11-18), CLAUDE.md "Words in the UI" and the
author's audit `docs/review/2026-11-18-choices-not-warnings.md`. No `cargo test` run (CI run 44268664709 green).

No High or Medium finding. Seven Low.

## Checked and fine

- **No real failure hidden.** Every removed or quietened line is a consequence of a setting the PWA offers:
  - Safe mode (`kid_lock.rs:926`): the first Child PIN turns Block safe mode on by itself (`devices.rs:3332`, since
    2026-10-05), so "allowed with a Child PIN" means the parent turned it off later. Advanced's footer still says safe
    mode skips the lock (`device.advanced.safe_boot_footer`). A start in safe mode still raises the red "Phone started
    without Vibb One" (`attention.rs:693`, `boot_witness::unseen`), so the hole stays visible when it is used.
  - No Child PIN (`lock_glance.rs:177`): there is no Attention row for it anywhere else either. Only "Child PIN set
    but lock off" (`ChildLockOff`) is wrong, and it is kept.
  - Location Off, location not locked, Chrome ticked, and the Messages app with SMS off: the app is hidden by the
    phone anyway (`device.calls.finding.sms_app_allowed`).
  - Calls not managed with a calls-off rule: the user approved this row by name ("Gjør det").
- **`SmsAppAllowed`.** It is left out of Attention through `needs_attention` (`attention.rs:271`). The Calls page
  splits its findings on `note.warning` (`calls.rs:858`), so the row goes to `notes` → `note_row.html`. The Devices
  list count and the summary read the same `items`. `calls_enforced` doesn't look at it.
- **`note_warning.html`.** Among its callers, only the lock glance's `NoChildPin` is a note with `warning: false`.
  Calls, Lock › Child PIN, Advanced › App screens and Updates split their notes on `warning` before the include.
  Music, Home, Apps and Time build every note with `warning: true`. So no other page loses its orange.
- **`note_row.html`'s new fix line.** It only changes `SmsAppAllowed`, the one row-level note that has a fix (its
  package name). `LockNote` rows, `EscapeNote`, `PlayNote` and `screening_note` all have `fix: None`.
- **Calls note sources.** The rule editor (`scope_of`, both branches) and Bedtime (`phone_or_answer`, show and
  refuse) both read `calls_unmanaged`. `p.calls_mode = 'unmanaged'` matches `DevicePolicy::calls_managed`
  (`models.rs:462`).
- **i18n.** nb.json and en.json have the same keys. None of the removed keys (`device.time.incoming_not_blocked*`,
  `device.attention.incoming_calls`, `device.lock.note.safe_mode*`) is referenced any more. The two new keys are short
  and exist in both files.
- **Saves.** No form or `#anchor` redirect changed.
- **Merge c3c6c6b67.**
  - `kiosk_escapes.rs::boot_cover_notes` keeps the copy round's "fine is no note" (shown after the arm, not armed, or
    armed with no start since → empty). It also keeps main's start-since rule: "not shown" only when `last_on_ms` is
    after the arm. Main's tests are kept with their expectations moved to "no note".
  - No `device.advanced.escape.cover_*` key is orphaned.
  - `tests/unified_push.rs` keeps both sides' tests in full: `seen_late_on_settings`, and design 115's
    `the_contract_of_115` and `a_hold_quiets_the_waiting_row`.

## Low

**L1 - `data-note` is not a swap key, so a quiet note can be morphed into the group after it.**
`server/templates/partials/note_warning.html:6` and `server/templates/device_locate.html:55`.
- Before, these blocks carried `data-warning`, one of `static/swap.js`'s `SOFT_KEYS` (`swap.js:34`), so they were keyed
  `DIV[data-warning=…]`. Now they key as a bare `DIV`.
- Scenario, Lock Screen & PINs: the parent saves or clears the Child PIN on the same page. The swap refresh adds or
  removes "No lock without a Child PIN" right before `#notifications`' `<div class="group">`, which is also a bare
  `DIV`. `morphChildren` (`swap.js:425`) then pairs the note with the group and morphs one into the other, instead of
  inserting or removing the note.
- The group's forms survive, because their ids move them, but the group's unkeyed children are rebuilt. That loses
  the "a line that comes in never replaces what follows" guarantee of design 103. The same can happen with Locate's
  location-off line when a refresh sees it change.
- Fix: add `"data-note"` to `SOFT_KEYS` in `swap.js`, and add a `jstest/swap.test.js` row with a note coming in before
  a group. The other `data-note` users (fdroid, zones, android-lock) only gain a stable key.

**L2 - The calls note shows whether the switch is on or off, and no longer says where to change it.**
`server/templates/schedule_rule.html:57-62`, `server/templates/device_bedtime.html:68`.
- The note shows while calls aren't managed, even when the rule allows calls. With the switch on, "only outgoing calls
  stop" reads as if something stops.
- The old sentence's pointer ("Styr anrop under Anrop og kontakter") is gone. The choice itself, Calls & Contacts'
  "Ikke styrt", says nothing about time rules (`device.calls.channel.calls_unmanaged`).
- Fix:
  - Word the note conditionally: "Uten anrop stoppes bare utgående anrop: anrop styres ikke på telefonen."
  - Add one short sentence to the "Ikke styrt" ⓘ: "Tidsregler stopper bare utgående anrop." That is the note "at that
    choice", as the rule asks.

**L3 - Everyone's rules almost always show the note, because "Ikke styrt" is the default rather than a choice.**
`server/src/handlers/schedules.rs:967-969`.
- `calls_mode` defaults to `'unmanaged'` (`migrations/0130_sms.sql:11`). `EXISTS (… calls_mode = 'unmanaged')` is
  therefore true on nearly every install. It is also true for a phone that has this everyone's rule switched off, and
  for a phone not yet set up.
- The default also means a parent who never opened Calls & Contacts didn't choose "Ikke styrt". A new rule has calls
  off, so Bedtime lets incoming calls ring, and nothing outside the rule's ⓘ says so. The user approved removing the
  row, so this is for awareness, not a revert.
- Fix (optional): limit the query to enrolled phones the rule is in force on. And/or raise the "the default isn't a
  choice" point with the user alongside the audit's borderline list.

**L4 - The "quiet" lines still use the Attention block's look.**
`server/templates/device_locate.html:55`, `partials/note_warning.html:6`.
- `class="attention"` without `attention-action` is grey, but it is still the padded, bold (600), tinted block above
  the group. That is the summary's grey Attention-row shape, and "Words in the UI" says a state that is as it should
  be shows nothing or a quiet value.
- Fix (design): render "Posisjon er av" and "Ingen lås uten barne-PIN" as a row in their group (`note_row.html`:
  title, consequence behind the ⓘ). Or give the non-warning branch a plain class (no box, normal weight).

**L5 - The quiet branch of `note_warning.html` has no page-level test.**
`server/src/tests/lock_glance.rs:956-958`.
- `the_page_shows_each_note` checks only that the text "No lock without a Child PIN" is there. Its comment "only the
  PIN warning" is stale. Nothing checks that the block has `data-note`, or that it has no `attention-action` /
  `data-warning`.
- `lock_glance.rs`' unit test checks the `warning` flag, but not the template branch.
- Fix: in that first case, assert `class="attention" data-note` in the subsection and `!contains("attention-action")`.

**L6 - Two tests now pass only because their string no longer exists.**
`server/src/tests/step10.rs:161`, `server/src/tests/time_rules.rs:1177-1192`.
- `!html.contains("Safe mode is allowed")`, and the `OLD_WARNING` / `OLD_ROW` checks, test removed keys and markup. They
  can't fail again.
- Fix (optional): have step10 assert that the Lock page's `#child-pin` section has no `data-warning` with safe mode
  off and a Child PIN set. The time-rules test's real checks (the `calls-unmanaged` note shows only while calls aren't
  managed) are meaningful and can stay as they are.

**L7 - The area notes still describe the removed warnings.**
- `docs/notes/server/time-rules.md:71`: Device › Time's approved warnings still list "incoming calls not blocked". Line
  110 was updated, line 71 was not.
- `docs/notes/server/hardening-and-pins.md:17`: "warnings as typed `kid_lock::LockNote`s: … safe boot allowed".
- Fix: drop both mentions. Line 17 could instead say that the first Child PIN turns Block safe mode on, and that a
  later Off is the parent's choice with no note.
