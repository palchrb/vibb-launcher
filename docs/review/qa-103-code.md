# Code QA: design 103 - saves in place, Avansert's Open for setup, hairlines, field focus

Checked: branch `design-103` at 5315631e4 against main merge f40ef9536 (steps 1-5: 1a8d62a2b, 972edc67c, 328d9eb70,
53d07609b, 06de9c400, 5315631e4), against `docs/design/103-saves-in-place.md` (revision 2) and
`docs/review/qa-103-design.md`.

Read whole: `static/swap.js`, the diffs of `scroll-restore.js`, `auto-reload.js`, `reconnect.js` (and its install,
`go`/`check`/`arm`/`start`), `pull-refresh.js` (install), `confirm.js`, `register-sw.js`, `notifications.js`,
`contact-form.js`, `quick-sheets.js`, `install-now.js`, `music-cards.js`, `app-display.js`, `passkey.js`,
`photo-crop.js`, `reveal.js`, `rule-kind.js`, `calendar-preview.js`, `app-lock.js` (title, veil, `lock`), `style.css`'s
diff, `partials/head.html`, `js_words.html`, `tab_bar.html`, `device_advanced.html`, the five `data-waits` places,
`quick_actions.rs::setup_card`, the i18n diff, and every changed or at-risk server test.

Run:
- `node --test jstest/`: 430/430 pass.
- `node server/jstest/real/swap-real.js`: all pass in Chromium and WebKit.
- `cargo check --tests` and `cargo fmt --check`: clean.
- By hand (no `cargo test`):
  - `shell::the_app_shell_scrolls_main_not_the_document`'s hairline part, ported to Python: passes.
  - `i18n::every_key_is_used`: emulated, nothing unused.
  - en/nb key and `{param}` parity: equal.
  - Every negative `!…contains("…")` in `src/`, scanned against the text that is new on every page (the head's new
    HTML comment, `js.save_unknown` en/nb, `#swap-status`, `data-swap-ready`, `data-waits`): no hit but the intended
    `data-waits` flips.
- One Chromium check of my own for M1 (below).

**Verdict:** the mechanism is sound. The keyed morph with its lookahead always ends at the new page's order, and it
never loops: every step advances `nc`, or removes up to a found `later` that matches next. Never verified:
- the anchor chain with the closed-`<details>` rule;
- the gap bookkeeping (no swap and no fallback reload while `inflight > 0`, inherited records, `again` re-sent after
  the morph, only the landed entries released);
- `<noscript>` and `<script>` handling;
- `redirect: "manual"` with the opaque redirect and the 423 to the lock;
- the swappable checks;
- the event order (`handy:swapped`, then `landed`, then `handy:saved` with `pending`);
- auto-reload's arming;
- reconnect's `"swap"` wait and the bar counter;
- the ES5 / Safari 14 floor. No `?.`, no arrow functions, no `let`/`const`, no `closest`/`matches` without a guard,
  no `NodeList.forEach`, no `AbortController`. `isConnected` is feature-tested.

The CSS is right:
- one base rule;
- the per-list rules set only their `left`;
- the 2dppx block comes last;
- the tokens are in both themes;
- `overflow-anchor: none` is on `.container`.

Counts: **1 High, 1 Medium, 8 Low.**

## Findings

**H1 - A server test still pins the removed note: CI goes red.**
`server/src/tests/window_relock.rs:806-809` (`open_for_setup_is_the_advanced_pages_first_row`):

```rust
assert!(
    setup.contains("Apps, Settings and Play Store open on phone&#39;s phone for 2 hours"),
    "{setup}"
);
```

- That is `device.advanced.setup.note`, which step 1 removed. The page now says "Apps, Settings and Play open for 2
  hours."
- 103-17's list didn't name this test, and the build left it unchanged (only `window_relock.rs`'s `SCRIPT` const moved).
- The rest of the test still holds: the button's action, `back=advanced`, the flash, and Lås igjen while open.
- Fix: assert `t(En, "device.advanced.setup.line")` (and "Open now") instead.

No other test I found will fail:
- the `?v=` pins (17 scripts, every changed one bumped, every pin moved);
- the `data-waits` flips (step9, calls, device_pages, provisioning, tracked_apps, window_relock);
- `lock_now`'s Advanced test (its 103-11 case reads `restrictions_paused`, which the intake stores);
- `every_page_script_is_swap_ready_or_listed` (22 ready, at least 20 needed; every head script is in `HEAD_SCRIPTS`);
- the accent rules;
- `every_confirm_has_a_cancel` (Avansert keeps its other confirms);
- the nb pages' English check (every new nb word is in an nb value);
- `pages_restore_scroll_after_auto_save` (it runs the jstests).

**M1 - A focused switch or select outside the saved forms never follows the server again.**
`server/static/swap.js:293-296`, `:309`, and `:277` for the attribute.
- For `cur === ctx.focused`, `keepParent` is true whenever its form wasn't saved in this landing, and `:309` returns
  before any property is set. That holds for every control kind, not only text.
- Chrome on Android, and the keyboard, leave the focus on a switch after a tap. That tap also set the switch's dirty
  flag, so its `checked` no longer follows the attribute that `syncAttrs` updates.
- What happens:
  - On every later refresh with no save of that form (the 3 s tick, pull to refresh, the return, another form's save),
    the switch keeps the old state while `defaultChecked` takes the server's.
  - Checked in Chromium: the server says off, `checked` stays `true`, `defaultChecked` is `false`.
  - The switch is now "changed", so auto-reload's `typing()` (`auto-reload.js:85-89`) and reconnect's `edited` hold
    every later tick and pull.
  - Example: the summary waits for the phone after a tap on a switch. The phone reports another state, and the page
    shows a state the server doesn't hold and stops refreshing. That breaks §2.6's "the switch never claims a state
    it doesn't have".
- 103-9's two rules are about a text field's caret and its `value` attribute.
- Fix:
  - Apply the focused-element exceptions only to `kind === "text"`: `skip.value` at `:277`, the `keepParent` branch at
    `:293`, the early return at `:309`.
  - A focused check or select follows the ordinary dirty rule.
  - Add a jstest case and a `swap-real.js` pair: a clicked, focused checkbox in an unsaved form follows the server.

**L1 - The flash's other one-shot words stay in the address and are spoken again on every refresh.**
`swap.js:37`, `:645-648`.
- `ONE_SHOT` is `notice`, `app` and `saved`. Flashes also come from:
  - `done=`: the summary's rules-now, lifts and time answer; Account;
  - `cancel=`: the summary and Activity;
  - `changed=` and `install=`.
- After a swap these stay in the address. Every later in-place refresh of `location.href` draws the flash again,
  imports it fresh and speaks it through `#swap-status`: every 3 s while the summary waits, and on a pull or a return.
- Before, a reload showed the flash again too, but no live region repeated it every tick.
- `install-now.js` drops `?install=` only at load, and a swap's landing no longer reloads.
- Fix, either of:
  - speak (and always import fresh) only for a record that carries saved forms;
  - add those words to `ONE_SHOT`.

**L2 - The focused field in the saved form: a field not recorded in `sent` takes the server's value.**
`swap.js:616-622`, `scroll-restore.js:sentOf`.
- `unchangedSince` returns true when `sent` is null or doesn't name the field. That happens when the save was a
  switch's: the focus was on the switch, or on `body` on iOS.
- So text typed into another field of that form during the round trip is overwritten, and the caret is put at the end.
- It is narrow, and no worse than today's reload, but 103-9's "keeps the parent's text" isn't met.
- Fix: unknown means keep, when the field is dirty.

**L3 - A focused `<textarea>` outside the saved form follows its new `textContent`.**
`swap.js:302-304`.
- `skip.value` has no effect on a textarea. The text sync changes its default value, and a field that isn't dirty
  jumps its caret.
- This is the case 103-9 covers for inputs.
- Fix: skip the text sync for the focused textarea.

**L4 - A changed `<select>` whose options change is put back by index.**
`swap.js:299` and `:312-313`.
- `selectedOf` records selectedness by position. An option the server inserts or removes shifts the parent's pick to
  a neighbour.
- Fix: rare. Restore by `value`.

**L5 - The lost line can land above the anchor after the place was kept.**
`swap.js:644` and `:677-680`.
- `lostLine` runs after the anchor scroll. For a form around several groups (Avansert's restrictions) it appends the
  line to the form's first `.group`, which may be above the flipped switch. The switch then moves down by the line's
  height.
- Fix: append to the submitter's or the focused control's group, or insert before the scroll.

**L6 - A swap while the app lock's veil is up shows the page's title.**
`swap.js:628`.
- `app-lock.js` puts "Vibb" in the title while veiled (`hideTitle`). A swap then (a timer tick or a landing while
  hidden) writes the real title over it.
- `showTitle()` later restores the title from before the swap.
- Fix: skip the title while `html.veiled` / `html.locked`, or let app-lock re-read the title after `handy:swapped`.

**L7 - Two page scripts are conditional, so their refresh in place mostly falls back to a reload.**
- `install-now.js` is loaded only `{% if install_polling %}` (`tracked_app_detail.html:144`). When a row stops waiting,
  the new page's script list differs, so the refresh is a real reload: the old behaviour, not a fault.
- The same holds for `photo-crop.js` on Calls when the first contact is added or the last removed.
- `install-now.js`'s `pagehide` stop is still permanent. A return from the back-forward cache never polls again: the
  same class as 103-4, not fixed for this script.

**L8 - Minor robustness.**
- `swap.js:701-702`: `offline()` without reconnect.js reloads even while a POST is out. reconnect.js is on every page,
  so this can't happen today.
- `swap.js` doesn't check that `url` is same-origin itself. Every caller passes `location.href`, scroll-restore's
  checked landing or reconnect's own address.
- `handyStay.landed` re-sends a queued form even when the morph has removed it.

Not a defect: a refresh without a save, at the top of the page, keeps the first id'd element in place.
- New content above it scrolls the band up by its height: the new line shows, the band's top goes.
- It is consistent with 103-15, but after a pull to refresh the page is no longer at the top.

## The design QA's findings in the build

| Finding | In the code |
|---|---|
| 103-1 keyed lookahead | Fixed (`keyOf`, `morphChildren`, `wanted`, parked id'd moves); jstest and the real check |
| 103-2 the anchor through a closing sheet | Fixed (`anchorChain`/`anchorDelta`, `drawn` also checks a closed `<details>` ancestor, which both engines still give boxes) |
| 103-3 the gap | Fixed: an `again` in the gap is kept as the parent's and re-sent in `landed`; busy marks skipped; nothing swaps or reloads at `inflight > 0`; one inherited record; `handy:saved.pending` |
| 103-4 auto-reload in the head | Fixed (arm and disarm on `handy:swapped`, re-arm at `pending` 0 and from the bfcache, the strip only with the marker at load) |
| 103-5 reconnect's swap wait | Fixed (`"swap"` kind, `go` to `handySwap.refresh`, `handy:swapped` ends it, `busy(±1)` counter; 328d9eb70's queued flip) |
| 103-6 parsing | Fixed (waits for `DOMContentLoaded`) |
| 103-7 real engines | Fixed (`swap-real.js`, passes) |
| 103-8 hidden inputs | Fixed (register-sw fill split from its one listener, contact-form `more`/`*_shown`, notifications' `own`) |
| 103-9 the focused field | Partly: L2, L3, and M1 for the non-text kinds |
| 103-10 imported `<noscript>` | Fixed |
| 103-11 the open line | Fixed, with a test |
| 103-12 the flash and live region | Fixed (see L1 on repeats) |
| 103-13 hairline order | Fixed, pinned |
| 103-14 focus contrast | Fixed: 3.12 on white, 2.79 on `--bg`, 6.14 dark |
| 103-15 the no-save anchor | Fixed (rects, below the strip) |
| 103-16 `overflow-anchor` | Fixed, pinned |
| 103-17 tests | Missed one: H1 |

## What I couldn't verify

- `cargo test` and `cargo clippy --all-targets` (not run, by the brief). The H1 verdict and the other tests' verdicts
  come from reading the code and porting tests by hand.
- M1 in WebKit: my check loaded in Chromium only. iOS Safari doesn't focus a checkbox on a tap, so M1 is mainly
  Chrome/Android and keyboard use.
- The iPhone, VoiceOver on `#swap-status`, and Safari 14 itself (the floor checked by reading only).
- The step 5 browser pass (its commit message reports it).
