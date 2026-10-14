# Code QA: the PWA browser pass's fix round (2026-10-14)

Branch `pwa-browser-fixes` at `f4358a50` (on `main` `bd57cafb`), reviewed on `qa-browser-fixes`. Scope:
`server/static/scroll-restore.js` (`?v=2`) and its jstests, the redirect and form-action fragments across the
handlers and templates, `tests::shell::every_form_on_every_page_lands_on_its_own_group`,
`tests::shell::style_css_has_the_shape_browsers_read`, and the CSS for checkboxes, radios, file inputs and the
wallpaper switch.

## Summary and verdict

**Ready to merge after Medium 1, which is a one-line fix.** The fragment work holds up. Every action carries its
group, every redirect table pins its fragment, and the shell test checks that each fragment encloses its form on
every page. The scroll-restore logic is right in the cases the design lists: no fragment, the own group, a new or
moved row, a vanished group, another group, another path, a refusal answered at the action's path, and a
percent-encoded id. It uses only ES5 and `history.scrollRestoration` (Safari 11, Chrome 46), so it runs on the
browser floor. The CSS guard catches a lost `}` inside a rule, inside `@media` and at the end of an `@media`. The
restyled controls keep their names (the label, the `alt`, the `title`), keep a visible focus ring and show the real
controls in forced colours.

In a real Chromium, one problem remains with JS on: after a save, open any link and press Back, and the page comes
back at the top (Medium 1). Without JS the same steps keep the place. Two saves still land at the top without JS,
because their group is gone from the answer (Low 1). There are also two small gaps in the jstests (Low 2).

Full run: `cargo fmt --check` clean, `cargo clippy --all-targets --locked` gives the same 13 warnings as on `main`
(none in the changed code), `cargo test` 858 passed, `node --test server/jstest/*.test.js` 28 passed.

## Findings

### High

None.

### Medium

**M1. With JS, Back after a save lands at the top.** `server/static/scroll-restore.js:118` sets
`history.scrollRestoration = "manual"` on the entry the save landed on and never sets it back. The record was
consumed on load, so when the page is loaded again from history (no bfcache), neither the browser nor the script
restores anything. Checked in headless Chromium against a minimal page with the real script (scratch
`qa-browser/sr.js`): the user saves a form 400 px down the screen and lands correctly (scrollY 1159 -> 1295, form
still at 400), then taps a link and goes Back, and the page is at **scrollY 0**. Without JS the same steps return
to 1448.

Since `v=2` this happens after every JS save, because every save now restores (over its fragment too), whereas
`v=1` skipped saves that had a fragment. The common path is the summary: +15 min, then a Settings row, then Back.
bfcache (mobile Chrome, Safari) often hides the problem, but not on pages that bfcache refuses.

Fix: once the `load` restore has run, give the entry back to the browser:

```js
win.addEventListener("load", function () {
    restore();
    if (win.history && "scrollRestoration" in win.history) win.history.scrollRestoration = "auto";
});
```

Verified in the same harness: Back then returns to 1295, the place the save left. Make the jstests that assert
`"manual"` assert it before `load` and `"auto"` after. `auto-reload.js`'s `keepPlace` still works unchanged; only
its comment needs to drop "scroll-restore set this history entry's scrollRestoration to manual".

### Low

**L1. Two saves still land at the top without JS, because their group is gone from the answer.** The fragment
names an element that the redirected page no longer renders. With JS this is handled (`found === undefined` with
the id in `tops` falls back to the saved y), but without JS the browser finds no anchor and stays at the top:

- `POST /music/orphans/delete#orphans` (`server/src/handlers/music.rs:774`): `music_settings.html:104` renders
  `#orphans` only while there are orphan files, so a delete that worked removes the group. This is the last group
  of a long page.
- Unticking a lock-screen app that is listed only because it was ticked ("Not allowed" / "Not on the phone",
  `lock_glance::card_apps`). The redirect goes to `#lock-screen-app-<pkg>`
  (`server/src/handlers/lock_glance.rs:136`), and that row disappears with the tick.

Fixes: redirect the orphans delete to `#app` (the group above it), or always render an empty `#orphans` anchor.
For the lock-screen untick, redirect to `#notifications` when the package will no longer be listed. The class is
not tested: the redirect tables check the `Location`, not that the landing page has the id. A cheap guard is a
helper in the area tests that follows each redirect and asserts the fragment's `id="..."` is on that page.

**L2. Two scroll-restore rules aren't pinned by a test** (mutations J3 and J4 survived):

- The 20 s window is tested only with no fragment. Applying the age check only to the no-fragment branch
  (`scroll-restore.js:35`) passes every test, which would mean a stale record restores over a fragment. Fix: add
  an old record with a `#fragment` in `tops` to "another path or an old save don't restore"
  (`jstest/scroll-restore.test.js:162`), expecting no scroll.
- The window's length is read from the module's own `MAX_AGE_MS`, so changing it to 200 s passes. Fix:
  `assert.strictEqual(MAX_AGE_MS, 20000)`. The value is documented in server/CLAUDE.md and §5.1.

### Nits

- **N1.** The CSS guard (`server/src/tests/shell.rs:595`) takes the at-rule's name as the first whitespace-split
  word, so `@media(min-width: 600px) {` (valid CSS) and a block-bearing at-rule outside `RULE_LISTS` (`@container`,
  `@layer`, `@page` margin boxes) would be reported as a lost brace. It fails loudly, never silently, so it is fine
  to leave. If it ever fires on valid CSS, read the name as the characters up to `(` or whitespace.
- **N2.** `auto-reload.js:52`: "same key and shape" is no longer quite true. Its record has no `tops`, which is
  intended: over a `#fragment` the browser restores the place on reload. Say so in the comment.
- **N3.** `.glyph-choice input:focus:not(:focus-visible) + *` is dropped as a whole on Chrome 80 and Safari 14 (no
  `:focus-visible`). The ring then shows after a tap too, which is the same graceful fallback as the global
  `:focus` rule at `style.css:166`. Not a bug; noted because the floor was a review point.
- **N4.** iOS Safari was not available in the harness (WebKit isn't installed). The script's `DOMContentLoaded` and
  `load` double restore should cover Safari's late fragment scroll, but that is not verified on a device.

## Checked and fine

- **Edge cases of the restore:** another path, including a refusal rendered at the action's own path such as
  `/devices/{id}/apps/display#app-<pkg>`, goes to the browser's anchor. A refusal at the page's own path
  (`/apps/tracked/{id}#details`, `/schedules/rules/{id}#rule`) keeps the group in place. Two tabs are fine
  (sessionStorage is per tab). A row that moves (the app switch, a category or music entry move) stays under the
  finger. A deleted row's neighbour or a new row in the group keeps the group. A flash that appears above the group
  doesn't move it (the group stays at 400 px in the harness with a 120 px flash above). `?open=` comes back open
  (`tracked_apps::every_form_on_the_app_pages_returns_to_its_page` GETs the landing page). A record is always
  consumed on load. A cancelled submit (an invalid `required` box) writes nothing, because no submit event fires.
- **The fragment change:** a refusal's landing and flashes in their groups are unchanged. The music
  `#import-preview` exception is explicit in `check_forms`. `back_to`'s phones to `#lists` mapping and the
  `?open=` routes are pinned. External links and Attention targets keep their old fragments.
- **The CSS guard:** strings (with escapes), comments, `@media`, `@supports`, `@keyframes` and `@font-face` are
  handled. A lost `}` inside a rule inside `@media`, or the lost `}` of the `@media` itself, fails with a line
  number.
- **Controls:** the radios are hidden at 1 px with opacity 0, never `display: none`, so they are still focusable and
  announced. The swatch keys, the icon `alt`s and "Its own" / "The app's own" name them. The ring is a sibling's
  `box-shadow`. Forced colours (which drop `box-shadow`) restores `appearance: auto` for these and for the round
  checkboxes. The checkboxes use `-webkit-appearance` (Safari 14) and keep the global `:focus` outline. The file
  button has one rule per pseudo-element. The wallpaper switch is §3.0's markup (`role="switch" class="sw"` and an
  `aria-hidden` knob) and fits in its 76 px column. Every auto-saving form has its `<noscript>` Save.

## Mutations

| # | Mutation | Tests | Result |
|---|---|---|---|
| J1 | `find`: any ancestor id counts, not only one in `tops` | jstest | killed (2) |
| J2 | `find`: no ancestor walk | jstest | killed (1) |
| J3 | 20 s window applied only without a fragment | jstest | **survived** (L2) |
| J4 | window 20 s -> 200 s | jstest | **survived** (L2) |
| J5 | moved row: saved y instead of the element's place | jstest | killed (1) |
| J6 | vanished group: browser instead of saved y | jstest | killed (1) |
| J7 | `screenTops`: the form only, no ancestors | jstest | killed (2) |
| J8 | path check dropped | jstest | killed (1) |
| R1 | kiosk-escapes redirect loses `#updates` | `device_pages` | killed |
| R2 | CSS: lost `}` of a rule inside `@media (forced-colors)` | `style_css_has_the_shape` | killed |
| R3 | CSS: lost `}` closing an `@media` block | `style_css_has_the_shape` | killed |
| R4 | Web Filter log form's action `#log` -> `#filter` | `every_form_on_every_page` | killed |
| R5 | `?open=display` not honoured | `tracked_apps::every_form` | killed |
| R6 | `?open=source` not honoured | `tracked_apps::every_form` | killed |
| R7 | lock-screen app redirect to the group, not its row | `lock` | killed (2 tests) |
| R8 | `back_to`: a phone's list setting to `#phones` | `device_pages` | killed (2 tests) |

16 mutations: 14 killed, 2 survived (both L2). Every mutation was reverted, and the tree is clean.

## Counts

0 High, 1 Medium, 2 Low, 4 Nits.
