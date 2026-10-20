# 43 - The PWA's app shell: no fixed tab bar, no double-tap zoom (proposal)

Asked 2026-10-20. Server only (`server/static/style.css`, the scroll scripts, every page's layout); no API or
launcher change. Replaces round 1's `tab-bar.js` hypothesis (PLAN "Done (not released yet)", round 1).

## 0. Goal

The user, 2026-10-20, the installed app on the iPhone (iOS 26): after a while in the background - not every time -
the tab bar "flyter oppover på skjermen" as the page scrolls. And: "av og til virker det som om den zoomer inn når
jeg trykker på tabs på bunnen".

Both are Safari behaviours a fixed, document-scrolling layout invites:

- A standalone web app keeps a stale layout viewport after a long background; a `position: fixed; bottom: 0` box is
  placed against it and then moves with the scroll. Round 1's `tab-bar.js` (a hidden/shown relayout at resume) does
  not hold on the device.
- Two quick taps on the same spot are Safari's double-tap zoom; a tab or a row that didn't take the first tap gets it.

Design 31's look is unchanged: the same bar, the same pages, the same safe-area insets.

## 1. Decisions

1. **The document stops scrolling.** `html, body { height: 100% }`, `body { overflow: hidden; display: flex;
   flex-direction: column; min-height: 100dvh }` (`100%` where `dvh` is unknown). `main` is the scroller:
   `flex: 1 1 auto; min-height: 0; overflow-y: auto; -webkit-overflow-scrolling: touch; overscroll-behavior-y:
   contain`. The tab bar is a plain flex child after `main` (`flex: none`), no `position: fixed`, with the safe-area
   padding it has today. Nothing on any page is positioned against the viewport any more.
2. **The tab bar's place in the DOM moves below `main`.** Today every tab page includes `partials/tab_bar.html`
   before `<main>` (the mockup's order for the fixed box). In the shell the bar must come after `main` in the flex
   column; the simplest change is `order: 1` on `.tab-bar` in CSS and no template edit. The implementer chooses
   between `order` and moving the include in the 38 templates; `order` is preferred (one line, no template churn,
   focus order unchanged - the bar was visually last already).
3. **No `position: fixed` or `position: sticky` against the viewport remains.** `.editor-bar` keeps `position:
   sticky; top: …` - it sticks inside the scroller now, which is what it did in effect.
4. **The pages without a tab bar** (`login`, `verify_2fa`, `setup_2fa`, `change_password`, `music_spotify_done`:
   `main.auth-card` inside `.auth-page`) get the same shell: `.auth-page` becomes the scroller (`flex: 1 1 auto;
   overflow-y: auto`) and keeps its centering (`min-height: 100%` of the body column). The implementer checks the
   login page with the keyboard up on a short viewport: the card must stay reachable by scrolling.
5. **The desktop scrollbar gutter** (`html { overflow-y: scroll }`, round 1: the bar and every tab the same width on a
   short and a long page) moves to the scroller: `main { overflow-y: scroll }` on pointer devices (`@media (hover:
   hover) and (pointer: fine)`), `auto` elsewhere. The bar is outside the scroller, so its width never depends on a
   scrollbar; the gutter keeps the content column from shifting between pages.
6. **The bottom padding of `.container`** (96 px + the home indicator's inset, room under the fixed bar) becomes
   `16px` (the bar is in flow and carries the inset itself). The top padding keeps `env(safe-area-inset-top)`.
7. **`tab-bar.js` is deleted**, with `jstest/tab-bar.test.js` and the script tag in `partials/tab_bar.html`.
8. **Double-tap zoom off, pinch zoom kept**: `body { touch-action: manipulation }`. Never `user-scalable=no` or
   `maximum-scale` in the viewport meta (accessibility). This also removes the 300 ms tap delay where it still exists.
9. **The place on the page is the scripts' job now.** A browser restores an inner scroller's position on Back only
   from the bfcache (the DOM kept), never on a fresh load; forms' redirects already go through `scroll-restore.js`.
   So `scroll-restore.js` grows one responsibility and the two other scripts follow it (§2).

## 2. The scripts

### 2.1 `static/scroll-restore.js`

- A pure `scrollerOf(win)`: the element that scrolls the page - `win.document.querySelector("main")` when its
  computed `overflow-y` is `auto` or `scroll`, else `.auth-page`, else the window. Returns an adapter `{ top(),
  scrollTo(y), element }` so `install` reads `scroller.top()` where it read `win.scrollY` and calls
  `scroller.scrollTo(y)` where it called `win.scrollTo(0, y)`. `screenTops`/`find` use `getBoundingClientRect` and
  are unchanged (viewport coordinates work the same inside a scroller).
- **The form record** (`handy-scroll`, 20 s, `window.name` as the second carrier) is unchanged in shape and rule;
  `y` is the scroller's top.
- **New: the place record for Back and reload.** On `pagehide` (and, cheaply, `visibilitychange` to hidden) the
  script writes `handy-place:<path+search>` = `{ y, t }` to sessionStorage. On load, after the form record has had
  its chance (`restoreTarget` returned null), when `performance.getEntriesByType("navigation")[0].type` is
  `back_forward` or `reload` and a place record for this path exists, the scroller goes to its `y` - at
  DOMContentLoaded, at load and on the two frames after, as the form record does. A `pageshow` with `persisted`
  (bfcache) restores nothing: the DOM came back as it was. A fresh navigation (`navigate`) restores nothing: a link
  opens its page at the top, as today. A `#fragment` on a Back/reload load wins over the place record (the browser's
  anchor), as it does for a form record naming another group.
- `history.scrollRestoration = "manual"` around the restore stays (it still stops the browser's own window scroll).
- Loaded from the head partial, not deferred, as today.

### 2.2 `static/auto-reload.js` and `static/install-now.js`

Both write a `handy-scroll` record with `win.scrollY` before `location.reload()`/replace so the page comes back at
its place. They read the scroller the same way: a shared pure function is not worth a new file - each gets the same
six-line `scrollerOf` (the files are standalone by design, see their heads) and writes `scroller.top()`. Their jstests
get a fake document with a scrolling `main`.

### 2.3 What is not touched

`register-sw.js`, `notifications.js`, `autosave.js`, `app-display.js`, `music-cards.js`: no window scroll in them.
Leaflet (the Locate map) lives inside `main` and handles its own touch; the map box has a fixed height, so the page
scroller and the map's drag don't fight more than today. The music cards' horizontal scrollers are children of the
scroller (`overflow-x: auto` on them already; `overscroll-behavior-y: contain` on `main` stops the rubber band from
reaching the body).

## 3. Risks, named

- **The keyboard on iOS.** With an inner scroller the visual viewport shrinks and Safari scrolls the focused field
  into view by scrolling the nearest scrollable ancestor - `main` here. Known weak spot: a field at the very bottom of a
  long form (Time rules, the DNS filter's lists). Device check in §5; if a field hides behind the keyboard, the fix
  is `scroll-padding-bottom` on `main` equal to the keyboard's height (`visualViewport.height` delta), set on
  `visualViewport` `resize` - a small follow-up, not in this round unless the check fails.
- **`#fragment` on first load.** Browsers scroll every scrollable ancestor to reveal the anchor; this is
  standard and works for inner scrollers in Safari 14+ and Chrome 80+. The forms' redirects to `#group` keep
  working without JS, which is the no-JS fallback design 31 §5.1 requires. `tests::shell` (the browser-shape test of
  `style.css`) is where a regression in the stylesheet's structure would show.
- **Pull-to-refresh** in the installed app on iOS (a document gesture) stops working on tab pages. Nobody asked for
  it; the inbox and the device pages refresh on their own (auto-reload, polling).

## 4. Tests

- `jstest/scroll-restore.test.js`: every existing test runs against a fake document whose `main` scrolls (the
  window's `scrollY`/`scrollTo` must stay untouched - asserted); new tests: `scrollerOf` picks `main`, `.auth-page`,
  then the window; a Back load (`navigation.type = "back_forward"`) restores the place record; a `navigate` load
  doesn't; a `reload` does; a bfcache `pageshow` doesn't; a `#fragment` wins; the form record wins over the place
  record; an old place record is harmless (no age limit - Back can come an hour later; the record is per path).
- `jstest/auto-reload.test.js`, `install-now.test.js`: the record carries the scroller's top.
- `tests::shell::style_css_has_the_shape_browsers_read` still passes; a new assertion in `tests::shell`: no
  `position: fixed` in `style.css`, and `touch-action: manipulation` on `body`.
- `tab-bar.test.js` deleted.

## 5. Device checks (the user, iPhone, installed app; and a desktop browser)

1. The bar still after > 10 min in the background, scrolling a long page (Device › Time).
2. A quick double tap on a tab: no zoom; a pinch still zooms.
3. A saved form (Device › Home's switches, Lock › Parent PIN) never jumps - the standing rule.
4. Back from a phone's page to the long Devices list lands at the place; a reload of a long page lands at its place.
5. Login with the keyboard up on the iPhone: the card reachable; Time rules' last field with the keyboard up.
6. Desktop (Chrome, narrow window): the bar's width equal on a short and a long page, the content column steady.
7. The Locate map drags without the page scrolling; the music cards scroll sideways.
