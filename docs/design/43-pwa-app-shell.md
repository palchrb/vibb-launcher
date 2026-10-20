# 43 - The PWA's app shell: no fixed tab bar, no double-tap zoom (proposal, revised)

Asked 2026-10-20. Server only (`server/static/style.css`, the scroll scripts, every page's layout); no API or
launcher change. Replaces round 1's `tab-bar.js` hypothesis (PLAN "Done (not released yet)", round 1). Revised the same
day after `docs/design/qa-r5-design.md` (43-1 … 43-11); the Revision section lists each change.

## 0. Goal

The user, 2026-10-20, the installed app on the iPhone (iOS 26): after a while in the background - not every time -
the tab bar "flyter oppover på skjermen" as the page scrolls. And: "av og til virker det som om den zoomer inn når
jeg trykker på tabs på bunnen".

Both are Safari behaviours a fixed, document-scrolling layout invites:

- A standalone web app keeps a stale layout viewport after a long background; a `position: fixed; bottom: 0` box is
  placed against it and then moves with the scroll. Round 1's `tab-bar.js` (a hidden/shown relayout at resume) does
  not hold on the device.
- Two quick taps on the same spot are Safari's double-tap zoom; a tab or a row that didn't take the first tap gets it.

Design 31's look is unchanged: the same bar, the same pages, the same safe-area insets, the same 720 px column.

## 1. Decisions

1. **The document stops scrolling on the tab pages.** `html { height: 100% }`, `body { height: 100vh; height: 100dvh;
   overflow: hidden; display: flex; flex-direction: column }` - exactly the viewport's height, never `min-height`
   (a body taller than a stale viewport can still be panned by a fragment, `scrollIntoView` or the iOS keyboard, and
   the bar would sit offset again). `main` is the scroller: `flex: 1 1 auto; min-height: 0; overflow-y: auto;
   overscroll-behavior-y: contain`. The tab bar is a plain flex child (`flex: none; order: 1`, so it lays out after
   `main` without a template edit; its reading order was first already, unchanged), no `position: fixed`, with the
   safe-area padding it has today. Nothing on any page is positioned against the viewport any more.
2. **`main` takes the full width; the column comes from its padding.** `main.container` is the scroller, so it must
   span the viewport (the scrollbar at the window's edge, the wheel working over the margins, the width the same on
   every page - auto side margins would switch off the flex stretch): `width: 100%; max-width: none; margin: 0;
   padding-left/right: max(16px, env(safe-area-inset-*), calc((100% - 720px) / 2))` (`480px` for `.container-narrow`).
   The content keeps its 720 px column. `.editor-bar`'s negative side margins (`-16px`) are re-checked against the
   new padding: they must use the same `max(...)` expression or the bar edge creeps on wide windows; the implementer
   measures it on the Pi's Chromium window and the iPhone.
3. **No `position: fixed` remains.** `.editor-bar` keeps `position: sticky; top: env(safe-area-inset-top)` - it sticks
   to `main`'s scrollport, which starts at the viewport top, so it still clears the status bar.
4. **The auth pages keep the document scroll.** `login`, `verify_2fa`, `setup_2fa`, `change_password` have
   `<body class="auth-page">` (the body is the centring box, not a wrapper), so `body.auth-page { height: auto;
   overflow: visible; min-height: 100vh; min-height: 100dvh }` opts them out of the shell; a card taller than the
   viewport (the keyboard up) stays reachable as today. `music_spotify_done` is a plain `main.container` page without a
   bar and takes the shell like the rest.
5. **The desktop scrollbar gutter** (`html { overflow-y: scroll }`, round 1) moves to the scroller: `main
   { overflow-y: scroll }` under `@media (hover: hover) and (pointer: fine)`, `auto` elsewhere. The bar is outside the
   scroller, so its width never depends on a scrollbar; the gutter keeps the content column steady.
6. **The bottom padding of `.container`** (96 px + the home indicator's inset, room under the fixed bar) becomes
   `16px`; the top padding keeps `env(safe-area-inset-top)`. `-webkit-overflow-scrolling: touch` is not added (a
   no-op since iOS 13).
7. **`tab-bar.js` is deleted**, with `jstest/tab-bar.test.js`, the script tag in `partials/tab_bar.html`, and the
   assertion in `src/tests/shell.rs:169` (which turns into "the tag is absent").
8. **Double-tap zoom off, pinch zoom kept**: `touch-action: manipulation` on `main` (the scroller - a restriction set
   above a scroll container does not reach into it), on `.tab-bar` and on the music cards' horizontal scrollers; never
   `*` (Leaflet's own `touch-action` rules must win on the map) and never `user-scalable=no` or `maximum-scale`. This
   also removes the 300 ms tap delay where it still exists.
9. **A guard against the one offset iOS can still produce.** With `overflow: hidden` on the body, the iOS keyboard's
   pan can leave the window scrolled by a few pixels after the keyboard closes. `scroll-restore.js` resets it: on
   `visualViewport` `resize`, on `focusout` and on `pageshow`, `if (win.scrollY) win.scrollTo(0, 0)` (a no-op
   everywhere else; skipped on `body.auth-page`).
10. **The place on the page is the scripts' job now.** A browser restores an inner scroller's position on Back only
    from the bfcache (the DOM kept), never on a fresh load; forms' redirects already go through `scroll-restore.js`.
    So `scroll-restore.js` grows one responsibility and the two other scripts follow it (§2).

## 2. The scripts

### 2.1 `static/scroll-restore.js`

- A pure `scrollerOf(win)` resolved **at each use**, never at install (the script runs from the head, before `<main>`
  exists): the `main` element when its computed `overflow-y` is `auto` or `scroll`, else the window (the auth pages,
  a page without `main`). It returns an adapter `{ top(), scrollTo(y), originTop() }`: for `main`, `top()` is
  `scrollTop`, `scrollTo(y)` sets it, `originTop()` is `main.getBoundingClientRect().top`; for the window, `scrollY`,
  `scrollTo(0, y)` and `0`.
- **`find()` uses the scroller's coordinates.** Today it builds the fragment element's document top as
  `rect.top + win.scrollY`; in the shell `win.scrollY` is always 0 and the result would be a y relative to wherever
  `main` happens to be - a save that jumps. New: `rect.top - scroller.originTop() + scroller.top()`. `screenTops`
  stays (viewport coordinates, compared with the same later). The jstests run every fragment case against a `main`
  scrolled to a non-zero `scrollTop`.
- **The form record** (`handy-scroll`, 20 s, `window.name` as the second carrier) is unchanged in shape and rule;
  `y` is `scroller.top()`.
- **New: the place record for Back and reload.** On `pagehide` (and `visibilitychange` to hidden) the script stores
  `{ y, t }` under `path + search` in ONE sessionStorage map, `handy-place`, capped at 50 entries (the oldest dropped).
  On load, when the form record restored nothing and the navigation is a Back/Forward or a reload
  (`performance.getEntriesByType("navigation")[0].type` is `back_forward` or `reload`; on a browser without it,
  `performance.navigation.type` 2 or 1 - both tested), the scroller goes to the stored `y` at DOMContentLoaded, at
  `load` and on the two frames after, as the form record does - **and the place record wins over a `#fragment`**:
  after any save the address carries its group's fragment, and Back must land where the parent was, not at the
  group. On a `navigate` load the fragment wins and nothing is restored (a link opens its page at the top, as today).
  A `pageshow` with `persisted` (bfcache) restores nothing: the DOM came back as it was; the no-store pages never
  come from the bfcache and so always take this path, which is right.
- `history.scrollRestoration = "manual"` around the restore stays (it still stops the browser's own window scroll).
- The §1.9 guard lives here too. Loaded from the head partial, not deferred, as today; `?v=` bumped.

### 2.2 `static/auto-reload.js` and `static/install-now.js`

Both write a `handy-scroll` record with `win.scrollY` before `location.reload()`/replace so the page comes back at
its place. They read the scroller the same way - each gets the same few-line `scrollerOf` (the files are standalone by
design) and writes `scroller.top()` - and get `?v=` bumps where they are loaded (`device_apps.html`,
`device_calls.html`, `tracked_app_detail.html`, `head.html`). Their jstests get a fake document with a scrolling `main`.

### 2.3 What is not touched

`register-sw.js`, `notifications.js`, `autosave.js`, `app-display.js`, `music-cards.js`: no window scroll in them.
Leaflet (the Locate map) lives inside `main`, reads `getBoundingClientRect` per event and keeps a drag on the map
through its own `touch-action`; the map box has a fixed height. The music cards' horizontal scrollers are children of
the scroller (`overscroll-behavior-y: contain` on `main` stops the rubber band from reaching the body).

## 3. Risks, named

- **The keyboard on iOS.** Safari scrolls the focused field into view by scrolling the nearest scrollable ancestor -
  `main`. Known weak spot: a field at the very bottom of a long form (Time rules, the DNS filter's lists). Device check
  §5; if a field hides behind the keyboard, the follow-up is `scroll-padding-bottom` on `main` from the
  `visualViewport` height delta - not in this round unless the check fails. The §1.9 guard covers the offset the
  keyboard can leave behind.
- **`#fragment` on first load.** Browsers scroll every scrollable ancestor to reveal the anchor (Safari 14+, Chrome
  80+), so the forms' redirects to `#group` keep working without JS - design 31 §5.1's no-JS fallback.
  `tests::shell::style_css_has_the_shape_browsers_read` is where a regression in the stylesheet's structure shows.
- **Pull-to-refresh** in the installed app on iOS (a document gesture) stops working on tab pages. Nobody asked for
  it; the inbox and the device pages refresh on their own (auto-reload, polling).

## 4. Tests

- `jstest/scroll-restore.test.js`: every existing test runs against a fake document whose `main` scrolls and is
  scrolled (`scrollTop` 300) before the restore - the window's `scrollY`/`scrollTo` must stay untouched (asserted) and
  the fragment cases must land on the group with the scroller's maths; `install` runs before `main` is in the fake
  document (the lazy lookup); new tests: `scrollerOf` picks `main`, then the window (an `auth-page` body, no `main`);
  a `back_forward` load restores the place record and beats a `#fragment`; a `reload` does; a `navigate` load doesn't;
  `performance.navigation.type` 1/2 as the fallback; a bfcache `pageshow` doesn't; the form record wins over the place
  record; the map is capped at 50; the §1.9 guard resets a non-zero `scrollY` on `focusout` and not on an `auth-page`.
- `jstest/auto-reload.test.js`, `install-now.test.js`: the record carries the scroller's top.
- `src/tests/shell.rs`: the `tab-bar.js` tag absent; `style.css` has no `position: fixed`; `touch-action:
  manipulation` on `main` and `.tab-bar`; `every_dvh_follows_a_vh_fallback` still passes (`100vh` before `100dvh`).
- `tab-bar.test.js` deleted.

## 5. Device checks (the user, iPhone, installed app; and a desktop browser)

1. The bar still after > 10 min in the background, scrolling a long page (Device › Time).
2. A quick double tap on a tab, and on a row deep in a long page: no zoom; a pinch still zooms.
3. A saved form (Device › Home's switches, Lock › Parent PIN, a group far down Device › Time) never jumps - the
   standing rule.
4. Back from a phone's page to the long Devices list lands at the place, also when the list's address carries a
   `#group`; a reload of a long page lands at its place.
5. Login with the keyboard up on the iPhone: the card reachable; Time rules' last field with the keyboard up, then the
   keyboard down and a scroll: no offset bar.
6. Desktop (the Pi's Chromium, a wide and a narrow window): the scrollbar at the window's edge, the wheel working over
   the margins, the content column the same width on a short and a long page, `.editor-bar` flush with the column.
7. The Locate map drags without the page scrolling; the music cards scroll sideways.

## Revision (2026-10-20, after qa-r5-design.md)

- 43-1: `main` full width, the 720/480 px column from padding; `.editor-bar`'s margins re-checked (§1.2).
- 43-2: `touch-action: manipulation` on `main`, `.tab-bar` and the card scrollers, not on `body`; the shell test
  checks those (§1.8, §4).
- 43-3: `find()` uses the scroller's origin and `scrollTop`; the fragment jstests run against a scrolled `main` (§2.1).
- 43-4: the scroller is resolved at each use, not at install (§2.1).
- 43-5: the auth pages opt out of the shell and keep the document scroll; `music_spotify_done` takes the shell (§1.4).
- 43-6: `body { height: 100vh; height: 100dvh }`, no `min-height`; the `scrollY` reset guard (§1.1, §1.9).
- 43-7: on Back/reload the place record beats the fragment; on `navigate` the fragment wins (§2.1).
- 43-8: `performance.navigation.type` as the fallback, both tested (§2.1, §4).
- 43-9: one `handy-place` map capped at 50 (§2.1).
- 43-10: the `shell.rs` assertion flips, the `?v=` bumps listed, no `-webkit-overflow-scrolling` (§1.6, §1.7, §2.2).
- 43-11: `order: 1`; the reading order "unchanged" (§1.1).
