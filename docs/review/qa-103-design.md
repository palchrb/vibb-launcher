# Design QA: design 103 - saves in place, Avansert's Open for setup, hairlines, field focus (`docs/design/103-saves-in-place.md`)

Checked against branch `design-103` (341bd817b, docs only on top of main c03db8997): `static/scroll-restore.js` (whole),
`auto-reload.js` (whole), `reconnect.js:590-894`, `pull-refresh.js:100-217`, `confirm.js`, `register-sw.js:1-80,160-240`,
`autosave.js`, `app-lock.js:1-135`, `rule-kind.js`, `calendar-preview.js`, the `.value =` writers in every page script,
`style.css` (tokens, `:330-415`, `:890-990`, `:2547`, `:2765-2810`, `:3086-3135`, `:3550-3640`, `:3730`),
`templates/partials/head.html`, `tab_bar.html`, `bell.html`, `js_words.html`, `group_flash.html`, `flash_note.html`,
`device_advanced.html`, `device_mobile.html`, `device_add.html`, the `<script>`, `autofocus` and `<details … open>` uses in
every template, `src/lock.rs:236-405` (`Facts`, `locked_response`), `admin_auth.rs:952-1031`,
`handlers/quick_actions.rs:595-686`, `window_relock.rs:950-985`, and the tests named or pinned (`tests/shell.rs`,
`lock_now.rs`, `window_relock.rs`, `step9.rs`, `calls.rs`, `device_pages.rs`, `provisioning.rs`, `tracked_apps.rs`,
`i18n.rs:901`). `node --test jstest/` passes on the branch (389/389). No `cargo check`: the commit is the design alone.

**Verdict: the right approach (C: the page itself, compared node by node), and the right amount of server change (none).**
The cited lines hold (§1.1's, §1.2's, §1.3's, §1.4's). The fallbacks (lang, `data-lock-ms`, CSS and script lists,
unmarked page script) fail safe, the security story is sound (same-origin URL from `stayLanding` or `location`,
`redirect: "manual"`, nothing executed: DOMParser is inert and scripts are never imported; a swap's GET carries no
`Sec-Fetch-User`, so it is not "use" for the app lock and a locked one gets the 423, `lock.rs:259-292,391`). The
Avansert rework, the hairline rule and the softer focus are right.

The problems are in the morph's matching and the place it keeps, and in the bookkeeping between a save's landing and
its GET. Counts: **2 High, 5 Medium, 10 Low.**

## Findings

**103-1 High - Matching id-less children by position breaks the page's two promises in the common case.**
- §2.3 matches children by id, then "by position among the children without an id". Forms, Attention lines and
  flashes have no id. Most forms don't either: 36 of 234 have one.
- The common change is a line that comes in **before** a form, as a sibling: Mobilnett's warning lines
  (`device_mobile.html:22-25`, `:62-64`) and Avansert's backup warning before the restrictions form
  (`device_advanced.html:57-63`).
- Old `[h2, form A, form B, p]`, new `[h2, div.attention, form A', form B', p']`:
  - form A is compared with the `div`. The tags differ, so A is replaced.
  - form B is morphed into A'. `p` is replaced by B', and `p'` is imported.
- So the saved form (the anchor) is gone, and §2.4 then scrolls nothing. The page jumps by the warning's height, which is
  exactly the case §2.4 promises to handle ("stays under the finger even when a warning comes in above it").
- Where the shift lands on the same tag, the *node* the parent touched now shows its neighbour's row. The anchor's delta
  then puts the wrong row under the finger, and §2.3's dirty rule keeps an unsaved value on the wrong control (§8 calls
  this a risk; it is the normal case).
- Fix (about 40 more lines):
  - Give each id-less element a soft key: tag plus `action`, or the first `data-flash` / `data-warning` /
    `data-mobile-line` / `data-choice` / `name` value.
  - Match with a lookahead, as idiomorph does: if the next new child's key matches a later current child, remove the
    ones between; if the current child's key matches a later new child, insert the new ones before it.
  - jstest: an Attention line inserted before a form keeps that form's node, and the anchor's delta is the line's
    height.

**103-2 High - The anchor inside a `<details>` that closes measures as 0 and scrolls main by its whole old top.**
- §2.4 anchors on "the saved form". D4 closes the `<details>` that holds the submitting button.
- The form is then not rendered, so `getBoundingClientRect()` is all zeros, and the delta is minus its old top. Main
  scrolls by hundreds of px.
- It hits the redesign's own flow: "Åpne nå" and "Lås igjen" in `#setup-sheet` (the form keeps its node while the
  phone hasn't answered). It also hits every in-place confirm sheet and Add Contact (`details#add-contact`,
  `device_calls.html:385`), whose landing `#contact-N` today is placed by scroll-restore's chain rule.
- Fix: reuse scroll-restore's rule, not a new one.
  - Record the tops of the form and its id'd ancestors (`screenTops`, `scroll-restore.js:284`).
  - After the morph, take the nearest one that is still connected **and** rendered (`getClientRects().length`).
    A closed `<details>` counts through its `<summary>`.
  - Moved out of every recorded ancestor: no scroll (the 2026-10-19 rule, `restoreTarget` `:277-279`).
- jstest: an anchor inside a closing sheet.

**103-3 Medium - The window between a save's landing and its GET's morph is not covered.**
`settle()` runs at `inflight == 0` (`scroll-restore.js:411-427`). A refresh then adds a GET during which the parent
can tap again:
- **(a) A tap is lost.** A flip of the still-busy form sets `again` (`:495-499`), but no send is left to consume it
  (`done()` already ran). The morph's saved-form rule then shows the server's state, so the tap is lost without a word.
- **(b) The in-flight marks are stripped.** A save B started in the window: the morph's attribute sync removes B's
  `aria-busy` and its submitter's `disabled` (the server's page has neither). "The busy marks go" (`releaseAll`,
  `:363`) would also drop B from `busy`, so a second tap posts twice.
- **(c) A fallback reload cancels a POST.** A fallback real reload (not swappable) in the window cancels B's POST.
  qa-31-s6-code M1 guarded against exactly that.
- **(d) A superseded refresh loses its saved forms.** "A refresh started later wins" drops an older refresh that
  carried saved forms. For example, the 3 s tick wins after `handy:saved` cleared its hold. Those forms then count as
  dirty elsewhere and keep the parent's state. Their `handy:saved` never fires, so `saving` and `stop()` stay set.
- Fix (spell it out in §2.2):
  - Release only the landed forms' busy entries, and re-send any `again` after the morph.
  - The morph never touches `aria-busy`/`disabled` on a form still in `busy`.
  - An answer that arrives while `inflight > 0` is dropped (the later landing refreshes).
  - A newer refresh inherits an older one's forms, submitters and "saved".
  - `handy:saved` carries `detail.pending`, and reconnect, pull-refresh and auto-reload clear `saving` only at 0.

**103-4 Medium - auto-reload.js in the head: when the tick starts is not designed.**
- "Refreshes only while `main` holds `[data-waits]`." Avansert loads without the marker. "Åpne nå" adds it through a
  swap, which is §3's whole flow ("the 3 s refresh turns the row into til 17:10").
- So the tick must arm on `handy:swapped` when the marker appears. "`stop()` is undone" must also re-arm the timer:
  `tick()` returns without rescheduling while `leaving` (`auto-reload.js:125`).
- `pagehide`'s `stop()` is permanent, so a page back from the bfcache never ticks again. That bug exists today, but the
  script now runs on every page.
- In the head, the `notice=`/`app=` strip (`:112-122`) runs on every page at load, not only on waiting pages. Make that
  a stated choice (it matches §2.2 step 6), or keep it behind the marker.
- jstest: no marker, then a swap adds it and the tick starts; a swap removes it and the tick stops.

**103-5 Medium - reconnect.js's "load" wait assumes the page unloads.**
- Its timers show the chip at 3 s, and `go()` does `location.reload()` once the network is back, after 12 s, and on
  "Try again" (`reconnect.js:640-660`, `:696-701`, `:729-734`).
- D1 (a) and D3 hand the refresh to this wait, but nothing ends it when a swap lands, and nothing routes `go()` to
  `handySwap.refresh`. So a swap that landed still shows "Connecting…" and later reloads the page anyway.
- Pull to refresh's spinner (`view.refreshing()`, `pull-refresh.js:188`) has the same gap, failure included.
- Fix: a wait kind "swap" whose `go()` calls the refresh, and an end (`handyReconnect.done()`, or a listener on
  `handy:swapped` / a failure event) that stops the wait and hides the spinner.
- Note: `saveStarted()` calls `end()` (`:784-787`). With the 400 ms bar as a wait, a second tap removes the first
  save's bar.

**103-6 Medium - A save that lands while the page is still parsing would morph a half-built `main`.**
- autosave.js and scroll-restore.js are un-deferred so that a switch saves while a long page loads (qa-31-s1-code L1).
- If the POST and the GET land before `DOMContentLoaded` (Calls on a slow link), the morph appends the missing rest to
  `main`, then the parser appends it again: the content is duplicated.
- Fix: `document.readyState === "loading"` means wait for `DOMContentLoaded` before the morph (or fall back).
- jstest case.

**103-7 Medium - The morph's semantics can't be proved in a hand-made fake DOM.**
- The jstests use hand-written fakes (`confirm.test.js:9-33`), and there is no jsdom (`server/` has no `package.json`).
- The parts most likely to be wrong are browser semantics a fake reproduces only by assumption: the dirty value and
  checkedness flags (`value` vs the attribute), `<option>` selectedness, radio groups, DOMParser's `<noscript>`
  (scripting off), `importNode`, and `getBoundingClientRect` of a hidden node (103-2).
- Keep the jstest for the logic, and add a repeatable real-engine check: a static fixture page that loads `swap.js` and
  morphs fixture pairs in Chromium and WebKit (the local Playwright), run as a script, not only as the one-off §6
  browser pass.

**103-8 Low - Hidden inputs that scripts write are invisible to the dirty rule.**
- For `type=hidden`, `.value` *is* the attribute, so the morph resets it to the server's.
- `register-sw.js:57` fills the sign-out forms' `endpoint` (`settings_hub.html:85`, `lock.html:47`). With D3's return
  in place, the Settings page swaps, the endpoint is empty, and Sign Out leaves the push row.
- §6 has register-sw re-read only the badge. Re-fill the endpoint on `handy:swapped`, but don't re-run `signOut()`
  whole: a second `submit` listener posts twice. Or mark such fields `data-swap-keep-value`.
- Same class, already in step 3: `contact-form.js:29` (`more`), `quick-sheets.js:266`, `notifications.js:285-311`.

**103-9 Low - The focused field: never set, so possibly dirty for good, or a caret jump.**
- A focused input in the saved form keeps the parent's text while its `value` attribute (`defaultValue`) takes the
  server's normalised one. Then `value !== defaultValue` for good. auto-reload's `typing` and reconnect's `edited` hold
  the 3 s refresh and pull to refresh until the parent edits it back.
- A focused input that isn't dirty follows its `value` attribute, so the attribute sync itself jumps the caret.
- Fix:
  - In the saved form, set the focused field's value when it still equals what was sent (caret at the end).
  - Elsewhere, skip the `value` attribute on the focused element.

**103-10 Low - `<noscript>` must stay opaque on import too.**
- A subtree imported after a tag mismatch carries DOMParser's `<noscript>` with real `<button type="submit">` children
  (e.g. Mobilnett's form ends in one, `device_mobile.html:47`).
- It doesn't render, but it is in `form.elements`, which changes implicit submission.
- Fix: empty or drop `<noscript>` in imported subtrees, and add a jstest case.

**103-11 Low - D10 misses the open-without-a-time case.**
- `setup_card` gives `value: None` and `open_text = setup_open` when paused with no known end
  (`quick_actions.rs:648-655`), not only for Play's install mode.
- Under §3 that row shows no value, and the sheet shows only Lås igjen, so the parent can't tell that it's open.
- Rule: the line shows whenever the row has no value.

**103-12 Low - The flash: the same text gives no sign, and a new node isn't announced.**
- A second save in the same group gives the same `<p class="flash">`, so the morph changes nothing (no animation
  exists either). The parent sees no answer to the second tap.
- A newly inserted `role="status"` is often not announced (§8 says so).
- Both have one cheap fix:
  - Re-create the flash node of a group that was just saved.
  - Copy its text into one persistent visually hidden live region outside `main`, next to `#js-words`.
- The next round could take this up instead of leaving it out of scope.

**103-13 Low - Hairlines: the CSS order, and the weight claim.**
- If the 2dppx block stays at `style.css:981` while the per-list rules keep their `border-top` shorthand later in the
  file (`:2547`, `:2770`, `:2804`, `:3557`, `:3633`), those lists get the border **and** the scaled background: a
  doubled line.
- Fold every base declaration into the one rule and put the 2dppx block after all of them.
- D12 (a) says "the same weight as now". At 3x it is 1.5 device px against today's 1 (when shown). It is still the right
  pick, but the wording should say so.
- No conflict with other pseudo-elements: no row uses `::before`/`::after` for anything else (`.crop-frame`,
  `.or-divider` and `.bat` are not rows). Rounded group corners and dark mode (`--sep` #3A3A50) are unaffected.

**103-14 Low - Focus colour: the contrast numbers are for `--surface` only.**
- #D9784C is 3.12:1 on white, but 2.79:1 on `--bg` #F2F2F7. Add Phone's field sits in a `.group` on `--surface`, so it
  is fine; §5 should say "on --surface".
- In dark mode `--field-bg` and `--field-focus` are both #0C0C14, so a tap changes only the 1 px colour (grey #7A7A90
  to #C08266).
- In light mode the focused edge (3.1:1) is now lower contrast than the resting grey (3.6:1).
- Show both in D13's mockup, light and dark. D13 (a) stands.

**103-15 Low - The no-save refresh's anchor via `elementFromPoint`.**
- Main's top edge sits under the fixed `.top-strip` and, on a tab page, the band's area.
- Sample a point below the strip, or skip `aria-hidden`/fixed hits, or the anchor is never found.

**103-16 Low - Scroll anchoring: unverified, so make it deterministic.**
- §2.4's "Chromium's own scroll anchoring may already have moved main… the two agree" depends on when Chromium applies
  its adjustment relative to a forced layout and a programmatic scroll. I couldn't verify it.
- `overflow-anchor: none` on `main` makes Chromium behave like WebKit (no anchoring) and leaves swap.js the only mover.

**103-17 Low - Tests that will break and aren't in §6.**
- The `?v=` pins: `shell.rs:1855` (`pull-refresh.js?v=4`), `:1890`/`:1904`/`:1907` (`reconnect.js?v=13`), `:2208`
  (`confirm.js?v=3`), `window_relock.rs:1070` and `device_pages.rs:2702` (the exact auto-reload tag).
- The negative asserts `!contains("auto-reload.js")` **fail** on every page once the script is in the head. They must flip to `data-waits`, not just be re-pointed: `step9.rs:120,262,286`, `calls.rs:452`,
  `device_pages.rs:1989,2717`, `provisioning.rs:993`.
- `i18n::every_key_is_used` (`i18n.rs:901`) for `device.advanced.setup.note` (its only user is `quick_actions.rs:659`).
  `device.summary.qa.close` stays: the summary uses it, `device_detail.html:252`.
- The jstests that pin the reload: auto-reload's 3 s reload, scroll-restore's `land`, reconnect's and pull-refresh's
  permanent `saving`.
- Also: §2.5 calls `calendar-preview.js` "document listeners only". It captures `#rule-form` and
  `[data-preview-result]` at install and writes `innerHTML` (`calendar-preview.js:25-28,46`). It works with the morph
  (id'd form, the server's preview after a save), but the marking criterion as written isn't met. Word it as "nodes a
  save keeps".

## The decisions

- **D1** (a). It is right, once 103-5 lets reconnect's wait end in a swap. Also say what a flip of a held form does
  while offline (today it would set `again`, and nothing sends it).
- **D2** (a). Agreed.
- **D3** all four. Agreed after 103-4 and 103-5. The `?v=` check makes a return after a server update a real load,
  which is what that reload is for.
- **D4** Agreed. Define "holding the button" as every `<details>` ancestor of the submitter (what a reload does today).
  The server re-opens on success only what the parent had open (`category.open`, `contact.open`, `source_open`), so
  "the parent's wins" loses nothing.
- **D5** Agreed (see 103-5's note on `end()`).
- **D6** Agreed. Unmarked Leaflet scripts fall back by themselves.
- **D7 / D8 / D9 / D11** Agreed.
- **D10** Agreed with 103-11.
- **D12** (a). Agreed (103-13's wording and order).
- **D13** (a). Agreed (103-14).

## What I couldn't verify

- §1.3's WebKit measurements: not re-run.
- Chromium's scroll anchoring order (103-16) and VoiceOver's handling of an inserted `role="status"` (103-12).
- The step 3 scripts' re-sync needs: not read in depth (quick-sheets, app-display, notifications, music-cards,
  photo-crop, passkey, reveal).
- The 178/56 split: checked only in total (234 POST forms; 33 whose literal `action` has no `#`, some of them
  templated or posting to their own anchor).
- Safari 14's handling of `fetch` `cache: "no-store"` with `redirect: "manual"`: scroll-restore already relies on
  `manual`, and `cache` is Safari 10.1+ by the docs, not tested here.
