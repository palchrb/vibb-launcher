# Code QA: design 80 - a simpler Calls & Contacts page (branch `calls-simpler`)

Reviewed: `calls-simpler` at db4eae05 against `main` e54fc73d (`git diff e54fc73d...db4eae05`, 71 files): 813c7f88
(server steps 1-2), 7e93b86b (step 3, the wire, both directories), 76649c70 (the "Tilpasset" row's height) and the
merge of main (design 79). Read against `docs/design/80-calls-contacts-simpler.md` (revision 2 with its "As built"
line), `server/CLAUDE.md`, `docs/notes/server/calls-and-contacts.md`, `launcher/CLAUDE.md` and
`docs/notes/launcher/calls.md`, and the code the diff leans on (`MmsFlow.onRetrieved`, `MmsGate`, `SmsEngine.send` and
its outbox, `SqliteMessageStore`'s open helper, `sms::sanitize_state`, `save_sms`, `add_family_contact`).

Run here: the server suite, the jstests and the launcher's unit tests once each, `cargo fmt --check`, and 13 targeted
mutations. No browser pass, no emulator, no clippy.

## Verdict

**Mergeable after M1.** It is a one-line template fix with a test. L1-L4 should go in the same fix round: each is
small and touches a rule a parent relies on. The other Lows and the Nits can follow.

What holds, checked by reading and by the mutations:
- **Auth and CSRF.** The one new route (`POST /devices/{id}/calls/own-number/use`) sits in `build_admin_router` beside
  `/calls/sms`, under the same session middleware and `csrf_guard` layer. The split `/calls/settings`, the per-slot
  `/calls/sms`, Add Contact and the contact form are the old routes with new fields. Every write is scoped
  `WHERE device_id = ?`, an unknown phone is a 404, and the server has one family.
- **D3 can't widen anything by a missing field.** `calls_form::both_ways` turns a pair on only when the two-way switch
  is present and `*_shown` says it was drawn off. A missing switch under `on` turns both off. The stale-page guard only
  keeps what is stored. Every outcome is a pair the page can draw (on, off or "Tilpasset"), and every pair can be
  changed back from Avansert, so no post leaves a state the PWA can't undo. `sms_in` is never stored for an emergency
  number. Mutations S1 and S2 are killed.
- **Groups fail closed.** With `groups_shown`, a missing `sms_groups` means off. Without the marker (an older page) the
  stored value is kept. The column defaults to on (S7 killed).
- **The emergency menu's rules are untouched.** Only its routes' returns gained `?advanced=1`.
- **The wire.**
  - `call_policy.sms_groups` is always sent. It is pinned by the policy snapshot, `PolicyResponseCompatTest` and both
    `sms_contract.json` copies, which are byte-identical.
  - An older launcher ignores the key (ignoreUnknownKeys).
  - An older server drops `own_learned`: `sanitize_state` keeps only known keys.
  - The new launcher reads an absent key as on, both from the DTO and from stored rules.
  - The root `CLAUDE.md` contract paragraph matches what `SmsContractTest` and `sms::tests` do.
  - Migration 0134 is one `ADD COLUMN ... NOT NULL DEFAULT 1`, the next number after 0133.
- **The launcher's group switch.** It is enforced on every path a group message takes:
  - an incoming group MMS (`MmsGate.group`, judged at `onRetrieved`, so a deferred fetch is judged by today's rules);
  - a new send (`SmsEngine.send`, `targets.size > 1`);
  - a queued group message at the outbox, failed as `refused:groups_off`;
  - the compose bar (`ComposeState.GROUPS_OFF`).

  Groups are never held, so there is no release path to guard. Existing group threads stay readable and can't be
  answered. LM1 and LM4 are killed.
- **The store.** `messages.db` v4 creates `own_learned` both in `onUpgrade` (`oldVersion < 4`, after v2 and v3, so v1,
  v2 and v3 all reach it) and in `onCreate` (through `onUpgrade(1, 4)`). The fake store keeps the same
  null-means-delete contract.
- **The learning rule** is as §4.4 describes. Its sender must be a contact with `sms_in`, the message must have exactly
  one `To` and no `Cc`, and the `To` must be a full `+` number that isn't a short code, an emergency number, the sender
  or any contact. A conflict locks learning (LM5 killed). What it learns is "us" for the group rule only: no SMS, call
  or allow rule reads it.

**Counts: Critical 0, High 0, Medium 1, Low 7, Nit 5.**

## Medium

### M1. A page drawn before the phone claimed `sms_groups_v1` turns group texts off on its next SMS save - confirmed by reading

`templates/device_calls.html:546` posts `groups_shown=1` always, also while the switch below it is `disabled`.
`handlers/calls_sms.rs:961-969` decides at post time: with the marker, and the phone's newest report claiming the
capability, it stores `form.contains_key("sms_groups")`.

**What happens:**
1. Anna's phone runs a launcher before step 3. The parent opens Samtaler og kontakter, so the switch is drawn checked
   and disabled ("Oppdater Vibb One først"). A disabled checkbox is never posted.
2. The phone updates (Install Now, or the catalog's self-update) and its next report claims `sms_groups_v1`.
3. On the same page, the parent turns on "Vis meldingstekst på låseskjermen", or saves an own number. The form posts
   `groups_shown=1` and no `sms_groups`.
4. The handler sees the capability and stores `sms_groups = 0`. The phone then drops every group MMS. The parent chose
   nothing about groups, and only the closed Avansert row says "Gruppemeldinger av".

The window is exactly when a parent is likely to be on this page, right after an update. Without JS, the noscript
Save does the same.

**Fix:** draw the marker only with an enabled switch: `{% if sms.groups_capable %}<input type="hidden"
name="groups_shown" value="1">{% endif %}`. Add a test: draw the page without the capability, post the report with
it, then post the drawn form; `sms_groups` stays on.

## Low

### L1. A learned number is refused as a contact with "Det er telefonens eget nummer" - confirmed by reading

`handlers/calls.rs:2163-2170` with `calls_sms::phone_numbers`, which includes `own_learned.number`; `load_page`'s family
filter does the same.

**What happens:** §4.4's attack teaches the stranger S as "us". S might also be a real person the parent wants to add,
for example a classmate who was Bcc'd.
- Add Contact refuses S with "That's the phone's own number". The server states as fact what is only the phone's
  guess, and the guess came from a message a contact controls.
- A parent who believes it taps "Bruk dette nummeret" (one row away) and stores S for good. The parent's number wins
  over everything, so the attack's gain becomes permanent and is no longer flagged as learned.
- The refusal also makes the phone's own clearing rule unreachable from the PWA: "a contact with the learned number
  clears it" (`OwnLearning.current`, decision 4).

The design asks for this refusal (L6), so the gap is in the design's wording, not the build.

**Fix (smallest):** refuse only the stored and SIM-reported numbers. For a learned one, add the contact and let the
phone clear the learned number, as decision 4 already does. **Or** keep the refusal and word it by source: "Telefonen
lærte dette nummeret fra en melding fra {contact}. Er det feil, endre det under Avansert."

### L2. A momentary empty SIM list deletes the learned number for good - confirmed by reading

`sms/SmsRuntime.kt:191-196` (`simSet` is `""` for an empty `activeSubscriptionInfoList` or a `SecurityException`),
`sms/engine/OwnLearning.kt:50` (`state.sims != sims` gives null), `sms/engine/MmsFlow.kt:527-532`
(`ownLearnedFor` deletes the row when `current` is null).

**What happens:** `ownLearnedFor` runs for every status report (`SmsRuntime` around line 421). If a report runs while
no subscription is active (the SIM still loading after a boot, a SIM refresh, a modem restart), `""` differs from the
stored `"1"`. The state is then deleted, not just ignored, so groups are dropped again until a contact sends the next
one-to-one MMS. An open conflict is erased the same way, which reopens learning. Design §4.4 says nothing more is
learned "until the parent stores a number or the SIMs change".

**Fix:** treat `""` as unknown: keep the stored state, and neither use it nor learn while the set can't be read.
Clear only on a non-empty set that differs. Add one `OwnLearningTest` row.

### L3. A stale SMS form deletes a stored own number - confirmed by reading

`templates/device_calls.html:556-575` (a "known" row still posts its hidden field with the stored value, empty when
nothing is stored) and `calls_sms.rs` `save_sms` (an empty field removes the slot).

**What happens:**
1. The page shows "Telefonens nummer · +47 900 00 009 · Fra SIM-kortet" with an empty `own_number_1` behind
   "Endre…".
2. The parent taps "Bruk dette nummeret", which stores the number outside this form.
3. The parent comes back to the older document (Back into the bfcache, or a second tab) and turns on the lock text or
   "Gruppemeldinger". The form posts `own_number_1=` and slot 1 is deleted. The phone then falls back to the live SIM
   reading, the momentary IMS number M3 meant to pin.

D11 makes the stale page likelier here. Every save in the page's Avansert returns with `?advanced=1`, so a
`details.section[open]` is shown, and `reconnect.js` refuses its reload-in-place on that page while Avansert stays
open (also `auto-reload.js` when the page has it).

**Fix:** the same idea as D3's L1. Post `own_number_<n>_shown` with the drawn value, and write a slot only when the
posted value differs from it. Alternatively, let `shownOpen` ignore `details.section` (a page-level disclosure holding
nothing typed; `edited(doc)` already guards typed text in reconnect). The first fix is enough on its own.

### L4. "Bruk dette nummeret"'s checks are untested and skip the phone code; a SIM row can offer a number the route refuses - confirmed (mutation S6 survives)

`calls_sms.rs:1100` (`own_number_ok`), `calls_sms.rs:148` (`reported_own` keeps a SIM number by `sender_ok("number")`
alone, no `+`).

**What happens:**
- Replacing the filter with a no-op passes every test. Nothing pins that a reported short code, an emergency number
  or a number without `+` can't be stored by the tap.
- A SIM that reports `"90000009"` (no `+`) gets the "Fra SIM-kortet" row with "Bruk dette nummeret". The tap is
  refused and lands on "Telefonen meldte et annet nummer i mellomtiden", which is not what happened.
- The stored number never goes through `phone::normalize`, unlike `save_sms`. The brief asks that number
  normalisation go through the existing phone code.

**Fix:** filter `reported_own`'s SIM numbers (and the learned ones) with `own_number_ok` in one place, so a row is
offered only for a number the tap will store. In the route, require `phone::normalize(n, &cc) == Ok(n)`. Add a test
with a reported `"113"` and a `"90000009"`.

### L5. Add Contact of an emergency number ignores the two-way texts switch without JS - confirmed by reading

`handlers/calls_form.rs:98-99` (for an emergency number, texts = `(false, on("sms_out"))`), Add Contact's form
(`templates/device_calls.html:367-414`: the two-way `texts` plus Avansert's `sms_in`/`sms_out`).

**What happens:** the parent types 113 and, without JS, turns off "Kan sende og få meldinger". The post has
`texts_shown=on`, no `texts`, and Avansert's `sms_out=on`. `resolve` reads only `sms_out` for an emergency number, so
the kid can text 113, against the switch the parent turned off. With JS, the mirror clears the pair first.

Separately, the refusal redraw uses `resolve(sent, None, false)` (`calls.rs:889`), while the save uses
`is_emergency(&number)`, so the redraw and the save disagree for such a number.

**Fix:** in `resolve`'s emergency branch, run `both_ways` on `(sms_out, sms_out)` and take `.1`. The edit form's
emergency row posts no `texts_shown`, so it keeps working. In the redraw, pass `is_emergency` of the sent number.

### L6. The short-number request's Home and Pictures defaults are unpinned - confirmed (mutation S4b survives)

`calls.rs:903` `[false; 6]`. Turning on `show_on_home` and `mms` there passes every test.
`contact_requests::the_prefill_and_retry_rules` checks only the four call and text switches. The user's decision, as
built and documented, is "every switch off".

**Fix:** add `("mms", false), ("show_on_home", false)` to that test's loop.

### L7. The learning's sender guards beyond "a contact with `sms_in`" are untested - confirmed (mutation LM3 survives)

`MmsFlow.kt:460-461`. Dropping both `smsMode == MANAGED` and `!judge.isEmergency(m.from)` passes `OwnLearningTest`,
`MmsEngineTest` and `MessagesModelTest`. `OwnLearningTest` passes `senderAllowed` in as a flag, so only the flow
computes it.

These are defence in depth: the server never sends `sms_in` for an emergency number, and the flow runs only with the
role held. They are the guards §4.4 names, though.

**Fix:** add an `MmsEngineTest` row: a contact with `sms_in` that is an emergency number (by the platform's list)
teaches nothing.

## Nit

- **N1.** `shownOpen` is the same 20 lines in `auto-reload.js`, `reconnect.js` and `pull-refresh.js` (D11 asked for it
  that way, and each has its jstest). A comment in each naming the other two would keep them in step.
- **N2.** `save_settings` (`calls.rs:1998`) treats an unknown `section` as the old write-all, which resets the default
  Message button to SMS. Refuse any value other than `channels` and `button` (400).
- **N3.** The flag on "Tilpasset" at 390 px: a larger font or a narrower phone only wraps a line, it doesn't break.
  - `.row-label` has `min-width: 0` and `overflow-wrap: anywhere`, and the value is `flex: none; nowrap` at 0.875rem,
    so nothing overflows sideways.
  - At 320 px, "Kan sende og få meldinger" wraps to two lines.
  - The value is about 27 px wider than the 51 px switch, so the "Tilpasset" label can wrap one width step before the
    switch row's label does. A save that splits a channel can then shift what is under it by one line. L8 holds at
    the default size only.
- **N4.** `?notice=own_number_gone` also covers "no free slot" (`use_own_number`, conflict or learned with every
  active slot stored). It then says "the phone reported another number meanwhile". Either a second code or neutral
  words ("Nummeret ble ikke lagret").
- **N5.** `device.requests.short_choose` says "barnet" / "your child", where the page's other lines name the kid
  ("{name}"). Pass the name.

## The implementer's decisions

| Decision | Assessment |
|---|---|
| (1) `groups_capability` as its own contract key | Right: `capability` stays a string an older test reads, and both sides check the new key. |
| (2) the Matrix ID field only while used or stored; a post without it keeps the stored ID | Right. An emptied field still removes it. Without JS, choosing Element X and typing the ID takes two saves (the field appears after the first), which is acceptable. |
| (3) a forged family add of the own number lands on "contact_gone" | Fine for a forged post. Nothing is written. |
| (4) the learned number cleared by a stored number, a SIM change or a contact with it | Implemented as described. The SIM rule is too eager (L2), and the contact rule is unreachable from the PWA (L1). |
| (5) "Mangler Matrix-ID" under the field | Fine. |
| (6) the RCS ⓘ string while managed | Fine. The words are natural Norwegian, and the en string matches. |
| The short number's defaults | As the user decided: calls and texts off, with the line. Home and Pictures are off too, but unpinned (L6). |

## Mutations

Each mutation was rerun only against its targeted tests, then reverted (`git checkout --`).

| # | Mutation | Tests rerun | Result |
|---|---|---|---|
| S1 | `both_ways`: the stale-page guard returns the posted pair instead of the stored one | `calls_form`, `calls_simpler::a_contacts_two_way…` | killed (2) |
| S2 | `both_ways`: a changed two-way switch always sets both **on** (a missing switch defaulting on) | `calls_form`, `calls_simpler` | killed (3) |
| S3 | `save_sms`: the group switch written without the capability | `calls_simpler`, `sms` | killed (1) |
| S4a | the short-number request gets §14's normal defaults | `contact_requests`, `calls_simpler` | killed (1) |
| S4b | the short-number request with Home and Pictures on | same | **survived** (L6) |
| S5 | "Legg til fra familien" no longer excludes the phone's own number | `calls_simpler` | killed (1) |
| S6 | "Bruk dette nummeret" without `own_number_ok` | `calls_simpler`, `sms` | **survived** (L4) |
| S7 | migration 0134 `DEFAULT 0` | `tests::sms::`, `calls_simpler`, `policy_json` | killed (5) |
| LM1 | `MmsGate.group` ignores `groups = false` | `OwnLearningTest`, `MmsEngineTest` | killed (2) |
| LM2 | `OwnLearning.current` ignores a SIM change | `OwnLearningTest`, `MmsEngineTest`, `MessagesModelTest` | killed (2) |
| LM3 | `MmsFlow`'s `senderAllowed` without the SMS-mode and emergency checks | same | **survived** (L7) |
| LM4 | the outbox sends a queued group message with groups off | same | killed (1) |
| LM5 | the conflict no longer locks learning | same | killed (1) |

## Suites

- Server `cargo test`: **1839 passed, 0 failed** (729 s). The two clock-sensitive tests the implementer named passed in
  this run. `cargo fmt --check` is clean. Clippy was not run.
- jstests (`node --test jstest/*.test.js`): **332 passed, 0 failed**.
- Launcher `testDebugUnitTest -PwarningsAsErrors=true`: **2392 tests, 0 failed, 0 skipped** (counted from the result
  XMLs).

## Not checked

- No browser pass: the 390 px light and dark views, the no-shift claim (L8), iOS Safari and Chrome on Android, and
  `?advanced=1`/`&more=` across a real reconnect. N3 is from the CSS only.
- No emulator or device: the store's v3 to v4 upgrade on a real install (read only, with no instrumented test), the
  SIM-loading timing behind L2, and whether a PIN-locked SIM lists as an active subscription.
- `cargo clippy`.
- The mockups (`80-mockups.html`), and the design QA's table point by point (only where the code touched it).
- Older launcher plus new server, and the reverse, were checked by reading only: the DTO defaults and
  `sanitize_state`'s known keys.

## Fix round (2026-11-10, branch `calls-simpler`)

Code in d804b07d (server) and 276e33b4 (launcher); this section, the notes and the design's "As built" in the docs
commit after them. Every finding was checked against the code first; none was refuted. Each test below fails without
its fix (L4's, L2's and L7's were run against the mutation: killed).

| Finding | Commit | What changed | The test |
|---|---|---|---|
| M1 | d804b07d | `groups_shown` is drawn only with `sms.groups_capable`, so a page drawn before the capability (the switch disabled) never turns groups off; the noscript Save posts the same form. | `tests::calls_simpler::a_page_drawn_before_the_capability_never_turns_groups_off` |
| L1 | d804b07d | `calls_sms::phone_numbers` (Add Contact's refusal, the family list) holds only the stored and the SIM-reported numbers; a learned one never blocks a contact. Added, the contact reaches the phone, whose `OwnLearning.current` drops the learned number, and the page stops offering a learned number that is a contact. | `tests::calls_simpler::a_learned_number_can_be_added_as_a_contact` |
| L2 | 276e33b4 | `simSet` "" is "can't be read": `MmsFlow.ownLearnedFor` keeps the stored state and uses none of it, `OwnLearning.learn` learns nothing. Only a readable set that differs clears it. | `MmsEngineTest` "the own number is learned …" (the conflict kept across a "" moment), `OwnLearningTest` "it doesn't learn from anything else" |
| L3 | d804b07d | Each own-number field posts `own_number_<n>_shown` (what the page drew); `save_sms` writes a slot only when its field differs (an older page without it: as before). The reviewer's reconnect point is real: the Avansert drawn open from `?advanced=1` is `data-reopens`, and the three scripts' `shownOpen` skip it (it comes back open after the reload); a `<details>` opened inside it still blocks. | `tests::calls_simpler::a_stale_sms_form_keeps_a_number_stored_meanwhile`; jstest `reconnect.test.js` "a <details> the server reopens after a reload doesn't block it…" |
| L4 | d804b07d | One rule, `calls_sms::usable_own` (`phone::normalize` leaves the number as it is, and `own_number_ok`), applied in `reported_own` to every SIM and learned number - the row is offered by it and the route stores only what it kept. The route's separate filter is gone (one place), so S6's mutation is now "`reported_own` without `usable_own`": killed. | `tests::calls_simpler::bruk_dette_nummeret_only_for_a_number_the_tap_stores` (`113`, `90000009`, `+4712` refused and not offered; `+4790000009` stored) |
| L5 | d804b07d | `calls_form::resolve`'s emergency branch runs `both_ways` on `(sms_out, sms_out)` and takes the kid's way, so Add Contact without JS follows the two-way switch; the refusal redraw passes `is_emergency` of the sent number. A contact's own emergency row (no `texts_shown`) is unchanged. | `tests::calls_simpler::an_emergency_number_added_without_js_follows_the_texts_switch`, `calls_form::tests::an_emergency_number_texts_by_sms_out_alone`; the browser pass with JS off |
| L6 | d804b07d | The short-number request's Home and Pictures pinned off (S4b killed). | `tests::contact_requests::the_prefill_and_retry_rules` |
| L7 | 276e33b4 | A contact with `sms_in` that is an emergency number (`113`, a rule set the server never sends), let in by the callback window, teaches nothing (LM3's emergency half killed). | `MmsEngineTest` "nothing is learned from a contact with sms_in that is an emergency number (code QA L7)" |
| N1 | d804b07d | A comment in each copy of `shownOpen` naming the other two. Kept as three copies: each script stands alone and has its jstest. | - |
| N2 | d804b07d | An unknown `section` on `/calls/settings` is a 400 (`error.unknown_section`), nothing written. | `tests::calls_simpler::an_unknown_settings_section_is_refused` |
| N3 | d804b07d | "Tilpasset" takes exactly the switch's box (`flex: 0 0 51px`, the 12 px gap, 13 px type), so its label wraps at the same width as beside the switch at any font size or width; at a larger font the word runs into the row's right padding. | the browser pass: a split save at 390 and at 320 px, 0 px shift |
| N4 | d804b07d | No free slot is its own notice, `own_number_no_slot`: "Telefonen har allerede et lagret nummer, så ingenting ble lagret. Bruk «Endre» for å bytte det." | `tests::calls_simpler::the_learned_number_and_a_conflict` |
| N5 | d804b07d | The short-number line names the kid: "Velg selv om {name} skal kunne ringe og sende melding til det." | `tests::contact_requests::the_prefill_and_retry_rules` |
| Cosmetic | d804b07d | The hairlines between Avansert › Meldinger's rows (`.group-lines`). The RCS row keeps the note rows' ⓘ style (`partials/note_row.html`'s shape), as every other row with an ⓘ on the page. | the browser pass's screenshot |

Suites after the round: see the implementer's report (server `cargo test`, fmt, clippy; jstests; the launcher's unit
tests). Browser pass (Playwright, a throwaway server): 390 px light and dark, 320 px, JS off for L5.

## Verification (2026-11-10, `calls-simpler` at 7bcfefd3)

Read `git diff 5ea0850b..7bcfefd3` (d804b07d server, 276e33b4 launcher, 7bcfefd3 docs) and the fix-round table above.

Run: only the tests that touch the fixes.
- `cargo test calls_simpler contact_requests calls_form`: 35 passed, 0 failed.
- The changed jstests (`reconnect`, `auto-reload`, `pull-refresh`, `contact-form`): 78 passed, 0 failed.
- The launcher's `OwnLearningTest` and `MmsEngineTest`: 47 tests, 0 failed.
- One measurement of the "Tilpasset" row in Chromium (Playwright): a bare `.group` with today's `style.css`, at
  320 and 390 px, root font 100-200 %.

**Verdict: mergeable after V1**, a one-line revert in `auto-reload.js`. V2 and V3 can go in the same round or follow.

### Finding by finding

| Finding | Verdict |
|---|---|
| M1 | **Fixed.** `groups_shown` is drawn only with `sms.groups_capable` (`device_calls.html:546`). A page drawn without the capability never posts it, by autosave or by the noscript Save (the same form). The reverse: a page drawn with the capability posts the marker, and the switch turns groups off and on (`the_group_switch_needs_the_capability_and_its_marker`, unchanged). If the phone loses the capability between draw and post, the handler's own check keeps the stored value. The new test dies without the guard. |
| L1 | **Fixed, with a remark (V2).** `phone_numbers` holds only the stored and SIM-reported numbers, so a learned number never blocks Add Contact or the family list. A learned number that is a contact is no longer offered. A learned number is only ever offered: it is stored only by a POST to `/calls/own-number/use` with `source=learned`, which only a signed-in parent can send (CSRF-guarded). Nothing stores it by itself. |
| L2 | **Fixed.** `simSet == ""` returns early from `ownLearnedFor`: nothing is deleted and nothing is used, because `own` is computed with `learned == null`. `OwnLearning.learn` returns null for `""`. `ownLearned()` for the report goes through the same function, so the report shows none for that moment. No path uses the stored number while the SIM list is unknown: `ownLearnedFor` is its only reader. The new guard dies to its mutation. |
| L3 | **Fixed in `save_sms`, but the `data-reopens` half opens V1.** A slot is skipped only when `own_number_<n>_shown` equals the posted value, so `_shown` can only suppress a write, never cause one. A forged or inconsistent `_shown` writes nothing. An older page without it writes as before. Two slots swapped in one post are each compared to their own `_shown`, so both are written. A refused field is skipped only if unchanged, and is otherwise validated as before. The guard dies to its mutation. |
| L4 | **Fixed.** `usable_own` (`phone::normalize` leaves the number unchanged, and `own_number_ok`) filters every SIM and learned number inside `reported_own`. The route reads only `reported_own(db, id, cc)` for the URL's device, picks by `source` and never reads a number from the form. A number the page didn't keep can't be stored. S6 (`usable_own` made a no-op) dies. |
| L5 | **Fixed.** The emergency branch returns `(false, both_ways(…, (out, out), stored (out, out)).1)`, so `sms_in` is `false` on every path. Without JS, Add Contact's switch turned off now turns `sms_out` off. A contact's own emergency row (no `texts_shown`) still takes `sms_out` as posted. The refusal redraw judges the sent number. |
| L6 | **Fixed.** S4b dies. |
| L7 | **Fixed for the emergency half.** LM3 (both guards removed) dies. The SMS-mode half alone still survives (V4). The implementer's claim holds: `Emergency.isEmergencyOutgoing` returns `false` for any `+` number (`dialableDigits` gives null), so only a short number such as `113` reaches the check. That is not a gap of this branch. The server's `emergency::is_emergency` is the same exact-match rule, `phone::normalize` keeps `113` short (`phone_vectors.json`), and the policy never carries `+47113` as an emergency number on either side. Whether `+47113` should count as an emergency number is a design 73/77 question, and it is consistent today. |
| N1 | **Fixed.** A comment in each of the three copies. |
| N2 | **Fixed.** An unknown `section` returns 400 before any query (`calls.rs:2006-2009`). Nothing is written. |
| N3 | **Fixed, with a remark (V3).** |
| N4 | **Fixed.** `own_number_no_slot` has its own words. |
| N5 | **Fixed.** `{name}` in both languages. |

### V1. The fix's `data-reopens` lets `auto-reload.js` reload away a phone number being typed - Medium, confirmed by reading

`static/auto-reload.js:41` (and its two copies) skip a `details[data-reopens]`. `templates/device_calls.html:446` sets
it whenever the page's Avansert is drawn open.

**What happens:**
1. The parent sets SMS to "Styrt av Vibb". For two minutes the page carries `auto-reload.js`
   (`ROLES_REFRESH_SECS`, a reload every 3 s).
2. The page's own quiet line "Telefonens nummer mangler - gruppemeldinger blokkeres" links to
   `?advanced=1#sms-settings` (`device_calls.html:172`), so Avansert is drawn open with `data-reopens`.
3. The parent starts typing in the "Telefonens nummer" field. While the phone knows nothing it is a plain
   `div.field`, not inside a nested `<details>`.
4. Before the fix, the open Avansert held the reload. Now nothing visible-open counts, so the tick reloads in place
   every 3 s and the typed digits are lost.

`auto-reload.js` has no typing guard of its own: it stops only on a submit or an autosave `change`, and a text field's
`change` fires on blur.

`reconnect.js` is safe, because `edited(doc)` still guards typed text. `pull-refresh.js` isn't loaded on the Calls
page (only on the Devices list and the summary). No other template sets `data-reopens`, so every other page that
loads these scripts (`device_apps`, `device_detail`, `devices_list`, every page's `reconnect.js`) behaves as before.

**Smallest fix:** drop the `data-reopens` skip from `auto-reload.js` only (v8). Reconnect's reload-in-place, which L3
was about, keeps it. Or give `auto-reload.js` the same `edited(doc)` check reconnect has. Add a jstest: an open
`data-reopens` Avansert with a changed text field holds the tick.

### V2. "Bruk dette nummeret" for a learned number doesn't re-check what the page checked before offering it - Low, confirmed by reading

`handlers/calls_sms.rs:1117-1137`, the `learned`/`conflict` branch. The page offers the learned row only while nothing
is stored, no SIM knows a number, and the learned number isn't a contact (`own_rows`). The route checks none of these:
it stores into the first active slot with no stored number.

**What happens:** a stale page (Back, or a second tab, drawn while the learned row showed) and the parent's tap store
the learned number over:
- a SIM number reported since, as the parent's number, which then wins over the SIM;
- or a number the parent has since added as a contact.

It needs the parent's own tap, so it is never automatic. But the learned number is the attacker-influenced one, and
the reason for offering it has gone.

**Fix:** in that branch, refuse (`own_number_gone`) unless the stored slots are empty, no reported SIM number is
`Some`, and the number isn't a contact on this phone. That is the same `unknown` and contact test `own_rows` uses;
lift it into one helper.

### V3. "Tilpasset" is clipped at 150 % text and wider than the switch it replaces - Low, measured

`static/style.css:3089-3097`: `flex: 0 0 51px`, `margin-left: 12px`, 13 px type, `overflow: visible`, inside
`.group { overflow: hidden }`.

Measured in a bare group (Chromium, nb):

| Width | Root font | "Tilpasset" | Edge of the group | Row heights (Tilpasset/switch) |
|---|---|---|---|---|
| 390 px | 100 % | 52 px wide | 15 px clear | 53/53 |
| 390 px | 125 % | 64 px wide | 3 px clear | 53/53 |
| 390 px | 150 % | 77 px wide | **clipped by 10 px** | 86/86 |
| 390 px | 200 % | 103 px wide | **clipped by 36 px** | 107/107 |
| 320 px | 100 % | 52 px wide | 15 px clear | **65/53** |
| 320 px | 150 % | 77 px wide | **clipped by 10 px** | 86/86 |

So at 150 % (Chrome's text scaling on Android) the word runs past the row's padding and is cut by the group's rounded
edge. That is a clipped word, not just a tight fit.

The 12 px margin also gives the label 12 px less than beside the switch (193 vs 205 px at 320 px; the switch has
`margin: 0`). At some widths the label wraps beside "Tilpasset" and not beside the switch, which is L8's shift again:
in this bare group, at 320 px and 100 %, the row is 65 px against 53. The real contact form may sit at another width,
and the implementer's pass saw 0 px there, but the 12 px difference remains at some width.

**Fix:** `margin-left: 0` (the switch's own box), and `flex: 0 0 auto; min-width: 51px` instead of a fixed 51 px.
The label then has exactly the switch row's room at the default size, and the word pushes the label rather than
being clipped at large text.

### V4. Nit: the learning's SMS-mode guard is still untested (mutation LM3b survives)

`MmsFlow.kt:460`. Dropping only `rules?.smsMode == MANAGED` passes `OwnLearningTest` and `MmsEngineTest`. It is hardly
reachable: a fetched MMS is judged with the role held and the sender let in by `senderNow`. But a one-line
`MmsEngineTest` row (SMS "Av", a contact's one-to-one MMS, nothing learned) would pin it.

### Mutations (verification)

Each mutation was rerun only against its targeted tests, then reverted (`git checkout --`).

| # | Mutation | Tests rerun | Result |
|---|---|---|---|
| S4b | the short-number request with Home and Pictures on | `contact_requests` | killed (`the_prefill_and_retry_rules`) |
| S6 | `usable_own` made a no-op (`reported_own` without it) | `calls_simpler`, `sms` | killed (`bruk_dette_nummeret_only_for_a_number_the_tap_stores`) |
| LM3 | `senderAllowed` without the SMS-mode and emergency checks | `OwnLearningTest`, `MmsEngineTest` | killed (the L7 test) |
| M1-new | `groups_shown` drawn always again | `calls_simpler` | killed (`a_page_drawn_before_the_capability_never_turns_groups_off`) |
| L3-new | `save_sms` writes a slot even when its field equals `_shown` | `calls_simpler`, `sms` | killed (`a_stale_sms_form_keeps_a_number_stored_meanwhile`) |
| L2-new | `ownLearnedFor` judges `""` as a SIM set (no early return) | `OwnLearningTest`, `MmsEngineTest` | killed (the learning test's `""` moment) |
| LM3b | `senderAllowed` without the SMS-mode check alone | same | **survived** (V4, Nit) |

Not checked in this verification: the full suites (the implementer reports server 1845/0, jstests 333, launcher
2393/0), clippy, the real contact form in a browser (V3 is measured on a bare group), and a device.
