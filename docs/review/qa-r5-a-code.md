# Code QA - server round 5 part A (design 43 app shell, design 44 Locate history)

Branch `r5-a` at `cb3da5f3` (37d51ab0 design 43, adce7ab8 design 44, cb3da5f3 docs) on top of `d34a3485`. I read
the code and did not change the implementation. Severity: High = ships a bug or breaks a standing rule; Medium = a
wrong behaviour or a missing guard worth fixing before merge; Low = a gap, a test, a device check; Nit = wording or
polish.

What ran (x86 VM, `CARGO_TARGET_DIR=~/.cache/handy-target/qa-r5-a`):

- `cargo test`: **1073 passed, 0 failed** (281 s). The first run was OOM-killed at link time while r5-b's build
  ran alongside it. The rerun was clean.
- `node --test jstest/`: **100 passed, 0 failed**.
- `cargo fmt --check`: clean.
- `cargo clippy --all-targets --locked`: the same **13 warnings** as before (models.rs dead fields, dns_engine doc
  lists, storytel `chunks_exact`, devices.rs:1307 `sort_by_key`). None are in changed code, and none are new.
- 13 targeted mutations (table below). Each was reverted, and `git status` was clean after each.

## The standing rules, checked

1. **A save never jumps to the top.**
   - Every new form redirects to its own fragment. Recent's Cancel posts to `…/cancel#recent` and redirects to
     `/devices/{id}?cancel=…#recent` or `/devices/locate?device={id}&cancel=…#recent`. `assert_lands` passes in
     `each_recent_lists_its_own_commands_and_cancels_back_to_it`.
   - `scroll-restore.js` maths in the scroller:
     - The form record stores `scrollerOf(win).top()` (line 162).
     - `find()` returns `rect.top + scroller.top()` (line 256). The saved tops are screen tops, so
       `found.top - tops[id]` is the scrollTop that puts the group back at its saved screen top, whatever main's
       origin is. The deviation "no `originTop()`" is the right call: the algebra cancels the origin. The jstest
       "a scroller that doesn't start at the screen's top" proves it with main 44 px down.
   - The place record beats the fragment on back_forward/reload (lines 233-239, 268-269).
   - A `navigate` load restores nothing and doesn't even hook `load` (line 239). Mutations (a), (b), (c) and the
     navigate one are all caught.
   - The stylesheet `<link>` comes before the un-deferred script in `head.html`, so `getComputedStyle(main)` sees
     the shell's `overflow-y` at DOMContentLoaded.
2. **Nothing a phone sent reaches a page as HTML or script.**
   - No template has an inline `<script>` any more (`grep -rn "<script>" templates` is empty), and no `|safe` is
     left in the Locate templates.
   - `js_string` is gone everywhere.
   - Every value reaches `locate.js` through an Askama-escaped `data-` attribute. The test checks
     `data-shown="&#34;&#60;/script&#62;…"` on both pages.
   - Both popups use `textContent` (locate.js:127, :180). Mutation (i) on either line is caught by the jstests, and
     `locations::the_locate_page_builds_no_html_from_stored_values` also greps the script for `innerHTML`.
   - The list's `data-text` and the time/accuracy rows are server-formatted strings.
3. **No-store.** `admin_auth::no_store_path` has `["devices", _, "locations"]` next to `locations.json`.
   `no_store_paths` lists both. `the_locations_page_pages_every_fix_once` also asserts the `Cache-Control:
   no-store` header. Mutation (f) is caught twice.
4. **CSS floor.**
   - No `:has(` (only in comments), no nesting.
   - `body` has `height: 100vh; height: 100dvh`, and `.auth-page` has the vh fallback before dvh
     (`every_dvh_follows_a_vh_fallback`).
   - No `position: fixed` is left (`the_app_shell_scrolls_main_not_the_document`). Mutation (h) is caught both ways.
5. **Cancel's `back`.**
   - Only `Some("summary")` goes to the summary. Anything else, a missing field included, goes to Locate
     (locate.rs:850-853).
   - The test posts `back=summary`, `back=https://evil.example` and no field. Mutation (e) is caught.
   - The summary lands on `/devices/{id}?cancel=done#recent` with the flash in `#recent`. Locate lands on
     `/devices/locate?device={id}&cancel=…#recent`.
6. **span/since.**
   - `span_of` takes a `parse::<i64>` from the `SPANS` whitelist, else 12.
   - `since` takes a `parse::<i64>`; a value that isn't positive, or that overflows `try_hours`/`checked_sub`, means
     everything kept (tested with `0`, `-3`, `x`, `99999999999999`).
   - `newest: true` is set only when the span is empty and a `since` was given (locate.rs:938-955).
   - The server's count is a separate `COUNT(*)` with the span filter, so the newest point outside the span never
     enters it. The JS `countText` subtracts the flagged point. Mutation (g) on either side is caught.

## Design QA findings, landed?

- **43-1 to 43-11: all landed.**
  - 43-1: main is full width, and its padding is `max(16px, inset, calc((100% - 720px) / 2 + 16px))`.
  - 43-2: `touch-action` sits on `.container` and `.tab-bar`, not on `body` or `*`, and the shell test checks it.
  - 43-3, 43-4: `find()` uses the scroller, and `scrollerOf` is resolved at each use.
  - 43-5: `body.auth-page` opts out, and `music_spotify_done` takes the shell.
  - 43-6: the body has an exact height with no `min-height`, plus the `scrollY` guard.
  - 43-7, 43-8, 43-9: the place record beats the fragment, the `performance.navigation` fallback is there, and the
    map is capped at 50.
  - 43-10: the `shell.rs` assertion flipped, the `?v=` bumps are in, and there is no `-webkit-overflow-scrolling`.
  - 43-11: `order: 1`.
- **44-1 to 44-9: all landed.**
  - 44-1: no-store, as in rule 3.
  - 44-2: the summary's `#recent` sits under `#quick`, and the test checks the order.
  - 44-3: `locate.js` with `data-` attributes.
  - 44-4: `scrollIntoView` plus `scroll-margin-top`, and no sticky map.
  - 44-5: a `Form` with the whitelist.
  - 44-6: paging on `(captured_at, id)`, tested with 205 scrambled rows, three per timestamp.
  - 44-7: `preferCanvas`.
  - 44-8: `role=radio` with `aria-checked`. The newest point is never counted.
  - 44-9: the test names are right.

## The implementer's deviations, judged

- **Design 43, the column padding `+ 16px`**: right. It keeps the 688 px of content the old 720 px box with 16 px
  padding had.
- **Design 43, `.editor-bar` side margins kept at `-max(16px, inset)`**: right. The full expression would paint the
  bar across the window's empty margins.
- **Design 43, `.editor-bar` `top: -8px`**: the reasoning is sound for WebKit as well as Chromium.
  - WebKit's `RenderBoxModelObject::constrainingRectForStickyPosition` shrinks the scroller's overflow clip rect by
    its padding, like Blink, which shares that code's ancestry.
  - So in both engines the sticky offset counts from inside main's `8px + inset` padding, and `-8px` puts the bar
    exactly at the inset.
  - It is not proven on the device, and it hard-codes main's 8 px (see L1).
- **Design 43, no `originTop()`**: right, as shown in rule 1.
- **Design 43, the guard keyed on `scrollerOf(win).inner` rather than `body.auth-page`**: right and simpler. Any page
  whose window scrolls is left alone. The "not on an auth page" jstest catches the mutation that drops `.inner`.
- **Design 43, `music-cards.js` scrolls main**: right, since §2.3 missed it. It is untested (L2).
- **Design 44, the Locations page's pick re-opens the page**: acceptable.
  - The rows are worded in `Zone::at` and paged by the server, so a client redraw would have to duplicate both.
  - It is a GET with nothing to save. It opens at the top, where the pick is.
  - The remembered-span `location.replace` can't loop: the target URL carries `?span=`, which then wins and is stored.
  - One wrinkle: each pick adds a history entry (N1).
- **Design 44, the summary's Recent only with a row or a flash**: acceptable and better than "Nothing asked yet."
  on every phone. A Cancel always has a row to come back to, and `each_recent_…` asserts that it is absent on a
  fresh phone. The generic form sweep (`every_form_on_every_page_lands_on_its_own_group`) seeds no waiting command,
  so it never sees either Recent's Cancel form. The dedicated test covers both redirects.
- **Design 44, the small additions** ("Newest" on an older page, "3 kept", `#location` on Locate's no-JS links,
  the wait polling `since=1`): all fine.
  - `since=1` still has the newest fix last: either it is inside the hour, or it is appended flagged.
  - None of them widens scope beyond the two notes.

## Findings

No High and no Medium.

**L1 Low - `.editor-bar { top: -8px }` is coupled to `.container`'s `padding-top: calc(8px + inset)` by a bare
number** (`server/static/style.css:1955` against `:225-226`).
- Why it matters: if main's top padding ever changes, the bar moves with nothing to flag it. On the iPhone the
  engine argument above is sound but not yet seen on the device.
- Fix:
  - Pin both numbers in `tests::shell::the_app_shell_scrolls_main_not_the_document`: assert `.editor-bar`'s
    `top: -8px` together with `.container`'s `padding-top: calc(8px + env(safe-area-inset-top, 0px))`.
  - Keep the device check: on the iPhone app, open Device › Time with a long rule list and scroll. The bar's top
    edge must sit right under the status bar, with no gap above it and Save not covered. Repeat in landscape.

**L2 Low - `music-cards.js`'s scroll anchoring in the shell has no test** (`server/static/music-cards.js:41-49`).
- Why it matters: putting `window.scrollBy(0, dy)` back is NOT caught, because no jstest or Rust test loads the
  file. Only `music_sweep.rs:2579` checks the tag. A regression there would be a visible jump on Music while a
  card's status changes.
- Fix: add `jstest/music-cards.test.js`, or at least one case: a fake document with a scrolling `main`, a swap that
  grows a card above the first visible one, then assert `main.scrollTop += dy` and that the window is untouched.
  This needs the IIFE to export a small `swap`/`scrollBy` API under `module.exports`, like the other scripts.

**L3 Low - device check 4 needs a history Back, not the page's back link** (`docs/design/43-pwa-app-shell.md` §5.4
and the "Device checks owed" line).
- Why it matters: in the installed iPhone app the usual way back is the page's own "‹ Devices" link. That is a
  `navigate` load, so by design (and as before the shell) it opens at the top. Only a history Back restores the
  place record: the browser's Back, the Android system back, or a swipe where the platform offers one.
- Fix: word the check so the user tests it with a history Back, and knows that the "‹" link opening at the top is
  intended. Otherwise the check will be reported as a failure.

**N1 Nit - each tap on the Locations page's span pick adds a history entry** (`server/static/locate.js:161-170`).
- Why it matters: Back walks through the spans before it reaches Locate.
- Fix: on the list page, call `preventDefault()` too and then `win.location.replace(link.href)`. The no-JS link
  stays as it is.

**N2 Nit - a Lock again / Unlock / Allow from today shows twice on the summary** (`server/templates/device_detail.html:174`).
- Why it matters: it appears in Quick Actions' answers (`relock.answers`, today's and waiting ones) and again as a
  Recent row. The design QA (44-2) foresaw this when the summary was chosen.
- Fix: none needed now. If it reads as noise on the device, the Quick Actions answers could drop the rows that
  Recent shows.

**N3 Nit - the "no fixed box" guard matches only the exact text `position: fixed`** (`server/src/tests/shell.rs:794`).
- Why it matters: `position:fixed` written without the space would pass.
- Fix: check the comment-stripped CSS with whitespace removed (`!compact.contains("position:fixed")`).

**N4 Nit - `music_spotify_done.html` (no tab bar) lost the bottom safe-area inset** (`style.css:225`).
- Why it matters: main's bottom padding is now a flat 16 px. On a page without the bar, the last line can sit on
  the home indicator when it is scrolled to the end. The page is short, so this is cosmetic.
- Fix, optional: `padding-bottom: calc(16px + env(safe-area-inset-bottom, 0px))` on a bar-less page's main.

**N5 Nit - `.map-box { scroll-margin-top }` needs Safari 14.1** (`style.css:1711`).
- Why it matters: on 14.0, the floor, a Locations row tap leaves the map's top under the status bar. It still
  works otherwise.
- Fix: none needed.

**N6 Nit - `PLAN.md` isn't touched by the branch.**
- Why it matters: the Done entries and the device checks owed for designs 43 and 44 need to be listed when the
  branch merges.
- Fix: the coordinator adds them at the merge, per the backlog rule.

## Mutations

Each was one change, run against the tests that should catch it, then reverted. (d), (f) and (g)-server ran in one
build, because their catching tests are disjoint and each failure points at its own assertion.

| # | Mutation | Result |
|---|---|---|
| a | scroll-restore.js:269: the place record yields to a `#fragment` (`y = place` only without a hash) | caught by "Back restores the place record, also over a #fragment" (window, main) and "the form record wins over the place record" (window, main) |
| b | scroll-restore.js:256: `find()` drops `+ scrolled` | caught by 5 tests: the own-group fragment (window, main), "a scroller that doesn't start at the screen's top", "a row that moved…" (main), "window.name record" (main) |
| c | `scrollerOf(win)` cached once at install | caught by "installed before <main> exists, the restore still scrolls <main>" |
| d | locate.rs:33: `LOCATE_KINDS` gets `end_windows` | caught by `locate_page::each_recent_lists_its_own_commands_and_cancels_back_to_it` (:565) |
| e | locate.rs:851: `Some(other) if other.starts_with("https://") => other` | caught by `each_recent_lists_its_own_commands_and_cancels_back_to_it` (:617) |
| f | admin_auth.rs:478: drop `["devices", _, "locations"]` | caught by `admin_auth::tests::no_store_paths` and `locate_page::the_locations_page_pages_every_fix_once` (Cache-Control) |
| g | locations_json flags the newest `false` (server) / `countText` counts every point (JS) | caught by `locate_page::locations_json_takes_a_span_and_keeps_the_newest` / jstest locate 1 and 4 |
| h1 | style.css: `position: fixed` back on `.tab-bar` | caught by `shell::the_app_shell_scrolls_main_not_the_document` |
| h2 | style.css: drop `height: 100vh` before the body's `100dvh` | caught by `shell::every_dvh_follows_a_vh_fallback` and `the_app_shell_scrolls_main_not_the_document` |
| i | locate.js:127 marker popup `innerHTML` / :180 row popup `innerHTML` | caught by jstest locate 3 and 4 / locate 8 (and by construction the Rust grep for `innerHTML` in `locations.rs`) |
| + | scroll-restore.js:211: the guard without `.inner` | caught by "in the shell a window left offset is put back at 0; not on an auth page" |
| + | scroll-restore.js:235: the place record restored on any navigation type | caught by 7 tests (the cap, reload/navigate ×2, the legacy fallback ×2, bfcache ×2) |
| + | music-cards.js:47: `window.scrollBy` always (the pre-shell code) | **NOT caught**: no test loads the file (L2) |

## Verdict

**Merge after the listed fixes:** L1's test pin and L2's music-cards jstest are small. L3 is a wording fix in the
device checks. The nits are optional. Nothing breaks a standing rule. Every guard I mutated is caught except the
untested `music-cards.js` path.
