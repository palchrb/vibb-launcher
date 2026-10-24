# QA (code): the PWA's start-up hold

Branch `pwa-splash-hold`, one commit efd8d61b on `main` 6024e396. It touches the server's templates, one new
script (`static/splash-hold.js`), `style.css`, one generator script, the tests and the docs. I reviewed it on
2026-10-24 on branch `qa-pwa-splash-hold` (`git diff 6024e396..efd8d61b`). No Gradle, no emulator, no device. I
pushed nothing and fixed nothing.

What was asked (the user on the iPhone, 2026-10-24): the start-up splash "kunne kanskje blitt vist et sekund
lenger". The branch adds an overlay to every tab page. It shows the same night and Vibb mark as the start-up PNGs, on
the installed app's cold start only, until 1000 ms after `load` and never past 1600 ms from the navigation's start.
It then fades over 250 ms and is removed.

## Summary

- **The decision and the timing are right.**
  - All four conditions are strict `=== true` checks: standalone, a coarse pointer, the session's first document,
    and a start before the cap.
  - Missing or broken storage reads as "never". The check reads the flag back after writing it.
  - The cap's timer and the `load` timer both go through one guarded `end()`. Removing the overlay twice is safe.
  - The class is set before the body is parsed. The overlay is the first thing in the body, so it is in the first
    frame. If the script fails, the overlay stays `hidden`.
  - The overlay is out of flow, so it changes neither `main`'s scroll position nor `scroll-restore.js`'s records.
- **The weak point is the one condition built on sessionStorage.** On 2026-10-19 this app was seen to lose
  sessionStorage across a form's navigation on the iPhone (`scroll-restore.js` added `window.name` as a second
  carrier because of it). If that happens again, the hold covers the page a save lands on for about 1.3 s (Q-1).
- **On Android's installed app the hold is a second splash.** Chrome's own splash comes first (the icon and the name),
  and then a larger mark with no name. That is the user's call (Q-2).
- **The mark is the start-up images'.** Two independent checks compare the overlay's paths, scales, alpha, colours,
  viewBox and width with the script: the selftest and `the_start_up_holds_mark_is_the_start_up_images`. A Rust test
  reads the script at run time, but CI's change detection doesn't list the script (Q-4).
- **Every suite is green.** I ran 28 mutations on the script and 25 were caught. The 3 that survived are untested
  details: the `touchstart` listener, the `ended` guard, and a swap to `localStorage` (Q-3).

Counts: Blocker 0, High 0, Medium 1, Low 4, Note 8.

## Runs

| Side | Command | Result |
|---|---|---|
| server | `cargo test` (`CARGO_TARGET_DIR=~/.cache/handy-target/qa-pwa-splash-hold`, jobs 2, no debug info, `flock ~/build.lock`) | `test result: ok. 1216 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 377.43s` |
| server | `cargo fmt --check` | clean (exit 0) |
| server | `cargo clippy --all-targets` | ``Finished `dev` profile [unoptimized] target(s) in 1m 09s``; the warnings are all on lines this branch doesn't touch (`models.rs`, `storytel.rs:144`, `dns_engine.rs:54-55`, `devices.rs:1322`), the same as on `main` |
| js | `node --test jstest/*.test.js` (node v20.19.2) | `# tests 149`, `# pass 149`, `# fail 0` |
| script | `python3 -I ../scripts/pwa-startup-images.py --selftest`, from `server/` and from a scratch directory | `ok: 13 screens`, exit 0, both |

## 1. `static/splash-hold.js`, line by line

- **`firstInSession` (`:28-37`)**:
  - No storage gives `null`.
  - A flag already set gives `false`.
  - Otherwise it writes `"1"` and reads it back: `true` only when the value stuck, else `null`.
  - A throw gives `null`, and so does a `sessionStorage` getter that throws (`storageOf`, `:61-67`).
  - So a private mode or blocked site data never shows the hold. A storage that forgets its values is caught by
    the read-back within the same document. A storage that forgets between documents is not caught (Q-1).
- **`splashHoldDue` (`:41-44`)**: every condition is an `=== true`, plus `typeof now === "number" && now < 1600`.
  Without `performance.now` the clock reads 0 (`:69-71`), which counts as "early". That is harmless, since iOS 14+
  and Chrome 80+ all have it.
- **`install` (`:73-113`)**:
  - The `env` literal computes `firstInSession` for every document, so the flag is also set in a browser tab. That is
    harmless and intended: the first document decides.
  - The class is added with a separator, and `remove()` strips exactly that class token (`:91`). No other script
    touches `<html>`'s classes; only `locate.js` reads a custom property from it.
- **The timers**:
  - `endAt(CAP_MS)` is set at install, with a delay of `1600 - now`.
  - The `load` listener sets `endAt(min(load + 1000, 1600))`.
  - The script is synchronous in the head, so `load` cannot have fired before the listener is added. There is no
    `document.readyState` case to handle, and none is missing.
  - `end()` is guarded by `ended`. It looks the overlay up by id when it runs (the body exists by then).
  - The page with the script but no overlay (sign-in) goes straight to `remove()`, which only strips the class.
  - `remove()` checks for `parentNode`, so a second call (the cap timer after the load timer has removed the
    overlay) does nothing. The test "the cap's timer finds it gone" covers this.
- **The fade**:
  - `is-fading` is appended once (the guard), and the overlay is removed `FADE_MS` later.
  - The CSS transitions opacity over 250 ms (`style.css:2373-2374`).
  - With reduced motion it is removed at once, and the CSS sets `transition: none` too.
- **A tap**:
  - A `pointerdown` in the capture phase (and `touchstart`) calls `end()`.
  - The tap itself never reaches the page. The pointer went down on the overlay, so the click's target is the
    overlay. The overlay keeps its pointer events during the 250 ms fade, so a second tap there is swallowed too.
    That is the right choice (N-1).
  - Blocking taps for up to 1.6 s is acceptable: nothing under the overlay can be seen, and a tap ends it.
- **`aria-hidden="true"`**: the overlay is hidden from screen readers, and the page under it stays readable. It holds
  nothing focusable (`focusable="false"` on the SVG, and the test checks for no `<a>`, `<button>`, `<input>` or
  `tabindex`). Nothing on the cold-start pages autofocuses: the `autofocus` attributes are on edit pages after a
  refused save.
- **Leftovers**: the two document listeners stay for the page's whole life, and each later tap returns at `ended`.
  That costs nothing worth fixing.

### The first frame

- `partials/head.html` loads `style.css` first and then `splash-hold.js` without `defer`. A parser-blocking script
  after a stylesheet waits for the stylesheet, so the `.splash-holding` class is on `<html>` before the parser reaches
  `<body>`.
- The overlay is the first child of the body (the test pins this on nine rendered pages and on every template with
  the bar). So the first paint that shows any of the body shows the overlay too.
- `.splash-holding .splash-hold { display: flex }` is an author rule, so it beats the UA's `[hidden] { display: none }`.
  `style.css` has no global `[hidden]` rule with `!important` (the ones at `:2337` and `:2362` are scoped to other
  elements).
- **If the script fails** to load, throws or isn't due, no class is set, and the `hidden` attribute keeps the overlay
  off. **If `style.css` doesn't load**, `hidden` keeps it off as well.
- The `?v=49` bump guarantees no cached `?v=48` sheet without the rule, and the service worker caches nothing.
- **The cost of the request**: `splash-hold.js` is one more parser-blocking request on a cold cache. `scroll-restore.js`
  is already one, and the preload scanner fetches both in parallel, so the first paint barely moves (N-2).

### Scroll and focus

- The overlay is `position: absolute` in the body, which is `position: relative; overflow: hidden` (design 43). It is
  out of flow, so `main`'s size, its `scrollTop` and the fragment maths don't change.
- `scroll-restore.js`'s form record and place record read and write `main.scrollTop` only. They never see the
  overlay.
- A page that a save lands on is never the session's first document, so it never holds and the "no jump to the top"
  rule holds. The exception is Q-1.
- The first document of a session has no place record (`navigate`, and nothing was stored yet), so the hold never
  sits over a restore.
- Back to the first page from the back-forward cache: you can only leave the page during the hold by a scripted
  navigation, because the first tap is swallowed. On the cold-start pages, `reconnect.js` and `auto-reload.js` reload
  only on an error or an event, and a reload is a `reload` with the flag already set. So a restore from the
  back-forward cache that brings the overlay back isn't a practical case.

### A new session on an in-app navigation

- The start URL is `/`, which is a 303 to `/devices` (`main.rs:813`). A redirect is not a document, so `/devices`
  decides. A cold start without a session goes `/devices` -> `/login`, and the sign-in page uses up the session
  (section 4).
- **What is known about iOS**:
  - sessionStorage belongs to the top-level browsing context's session (HTML spec). A standalone app that iOS
    killed and the user relaunches starts a new one: an empty storage, so the hold shows. iOS shows its start-up image
    then too, which is the case the user asked about.
  - When only the web content process is jettisoned while the app's shell lives, WebKit reloads the page in the same
    session and keeps the session storage outside that process, so no hold. Whether iOS shows its start-up image in
    that case is not known; I couldn't check it.
  - Neither case can be verified without a device, so both are the user's check.
  - What is known from this app is the opposite failure: the installed iPhone app has lost sessionStorage *across a
    form's navigation* (pwa.md "Fix round 2026-10-06"; `scroll-restore.js:20-22`). That makes a later document look
    first again (Q-1).

### The headless Chromium check

pwa.md records that the implementer took a headless Chromium screenshot at 390 x 844 @3 and matched the mark's pixel
box to `startup-1170x2532.png`. I can't repeat it here (no browser run in this review), so it stays the implementer's
claim. The numeric checks (section 2) cover the geometry, but not where the overlay is placed on screen. The
placement follows from the CSS: absolute with insets of 0 in a body that is `100vh` in standalone (the screen),
centred with flex, and `43vmin` = `mark_share` of the shorter side. That is how the PNGs centre the mark. The real
iPhone cold start stays the user's check, as the docs say.

## 2. The inline SVG

- `partials/splash_hold.html` is 4943 bytes, about 4.75 KB rendered (the Askama comment is dropped). It is on every
  tab page, and gzip -9 makes it 2062 bytes.
- The admin router has no compression layer, so every tab page pays about 4.8 KB uncompressed. That is small next to
  the pages themselves, and an `<img>` would miss the first frame. A `CompressionLayer` for HTML would be a separate,
  server-wide decision (N-3).
- **The inline `style="width: 43vmin; …"`** is allowed: `ADMIN_CSP` has `style-src 'self' 'unsafe-inline'`, and 22
  other inline styles exist in the templates already.
- **The generator** (`scripts/pwa-startup-images.py`):
  - `hold_partial` writes the paths as relative deltas of the copied 2-decimal points, and `svg_points` reads them
    back.
  - The viewBox is `RING_REST x reach()`, the PNGs' mark box. The width is `mark_share x 100 vmin`.
  - The selftest asserts that the file equals `hold_partial(share)`, and that every path sums back to its ring.
  - `num` handles signs and the leading zero, and the selftest pins five cases of it.
  - In a scratch copy of the tree, four generator mutations failed the selftest: the viewBox without `RING_REST`, the
    width at 90 instead of 100, a path that drops its last delta, and a stroke-opacity of 1. A hand-edited ring point
    in the partial failed it too.
  - These five only prove that the partial was regenerated. The independent check is the Rust test below.

## 3. The Rust tests

- **`shell::the_start_up_hold_comes_first_on_every_tab_page`** pins:
  - The head's `splash-hold.js?v=<integer>`, with neither `defer` nor `async`.
  - The overlay's exact opening tag: id, class, `aria-hidden`, `hidden`.
  - Nothing focusable in the overlay, and `focusable="false"` on the SVG.
  - In every template that includes the bar, only `{% let %}`, `{% import %}` and comments between `<body>` and the
    bar, and the bar before `<main>`. This held in at least 36 templates.
  - The rendered body of nine pages, including Device › Summary and Calls: the overlay first, exactly once, and
    before `main`.
  - `/login` has the script and no overlay.
  - `the_app_shell_scrolls_main_not_the_document` now pins that the bar partial starts with the include.

  Together these pin what matters for the first frame.
- **`shell::the_start_up_holds_mark_is_the_start_up_images`** re-derives everything from the script's own source:
  - The constants, by `\nNAME = ` at the start of a line.
  - The rings, by `RINGS = [` up to `\n]\n`, with an `M`/`L` regex.
  - It then checks the overlay's paths (summed back point by point, to 1e-6), the scale, alpha, stroke and colours,
    a core with no `cx`/`cy`, the viewBox (`RING_REST x (max |x|,|y| + stroke/2)`), the width from the JSON's
    `mark_share`, and the CSS rules verbatim.
  - **The reading is robust in the way that counts**: any change in shape (a comment after a constant, another path
    syntax) makes it panic or fail. It can't pass silently.
  - It reads `../scripts/…` at run time, as `deploy_scripts` reads the workflows, so it needs the whole repo. CI and
    the dev loop have it, and the Docker image runs no tests.
  - **It runs only when CI runs the server's build**, and `server-changes` doesn't list `scripts/` (Q-4).
- **The extension to `every_startup_image_has_its_screens_size`**: it checks the one theme-color `#0C0C14` and the
  overlay on Devices, Apps and Settings. That is enough.

## 4. Sign-in uses up the session

- The flag is written by the first document that runs the script. On a cold start without a session that is
  `/login`, which has the script but no overlay. So iOS's splash goes straight to the light sign-in page, and Devices
  after the 2FA step doesn't hold.
- That is fine. The user signs in rarely, the hold over a sign-in form would only be in the way, and holding Devices
  after a 2FA round trip would show the splash in the middle of the app.
- pwa.md says this.

## 5. Docs and PLAN

- **pwa.md "The hold"** is accurate about the conditions, the timing, the cap's reasoning (no separate
  DOMContentLoaded rule), reduced motion, a tap, focus, theme-color, regeneration and the tests. There are two nits
  (Q-5):
  - "pages without the bar (sign-in, 2FA)" leaves out `change_password.html` and `music_spotify_done.html`.
  - "it only sets `.splash-holding` … and timers" leaves out the two tap listeners.
- **The "Regenerate" bullet** now names `partials/splash_hold.html` among the files to commit.
- **PLAN.md:1640-1642**: a sub-entry under the start-up splash in "Done (not released yet)". It is short and accurate.
- **Root `CLAUDE.md:36-37`** still describes `pwa-startup-images.py` as writing "the PWA's iPhone start-up PNGs and
  head.html's links". It doesn't say that it writes `splash_hold.html` too (Q-5).
- **`NOTICE.md:39-40`** names `server/static/startup/` as the Vibb mark © palchrb. The same mark is now in
  `server/templates/partials/splash_hold.html`, which NOTICE doesn't name (Q-5).
- **The commit message** is accurate and carries `Co-Authored-By: Claude Fable 5.1` and the session line.

## 6. The `?v=` bumps and the first-in-body rules

- `style.css` went from `?v=48` to `?v=49` (`head.html:5`), its only reference. `splash-hold.js` is new, at `?v=1`.
  No other asset changed.
- All 45 page templates include `partials/head.html`. The five without the bar are `login`, `setup_2fa`,
  `verify_2fa`, `change_password` and `music_spotify_done`. They get the script and no overlay, and none of them had
  a first-in-body rule.
- **The bar's own rules hold**:
  - `.tab-fade` is no longer the bar partial's first element, but no CSS depends on that. `.tab-bar ~ .container` is
    a general-sibling rule, and the overlay comes before both.
  - The fade test now pins the include first, then the fade, then the `<nav>`.
  - No `body > :first-child` rule exists in `style.css`.
- **The body's flex column** is unchanged: the overlay is `display: none` (hidden) or absolute, never a flex item in
  flow.
- **z-index**: 3000 is above every other value in `style.css` (9, 10, 20, 2000, 2000). No script sets a z-index.
- **A note for the merger**: `?v=49` may collide with another branch that bumps `style.css` at the same time (N-8).

## 7. Mutations

Each mutation was a string replacement in `static/splash-hold.js`, and I ran `node --test jstest/splash-hold.test.js`
after each one. A Python driver restored the original file in a `finally`, and `git status` was clean afterwards.

| # | Mutation | Result |
|---|---|---|
| J1 | due: the standalone condition dropped | caught (2 fail) |
| J2 | due: the coarse condition dropped | caught (2) |
| J3 | due: `firstInSession !== false` (null accepted) | caught (2) |
| J4 | due: firstInSession dropped | caught (2) |
| J5 | due: `now <= CAP_MS` | caught (1) |
| J6 | due: the time check dropped | caught (2) |
| J7 | the cap 1600 -> 2000 | caught (1) |
| J8 | end: `Math.max` for `Math.min` | caught (3) |
| J9 | end: no cap (`load + HOLD_MS`) | caught (1) |
| J10 | end: no load -> 0 | caught (1) |
| J11 | HOLD 1000 -> 800 | caught (3) |
| J12 | firstInSession: no read-back (`return true`) | caught (1) |
| J13 | firstInSession: the flag never read | caught (2) |
| J14 | firstInSession: the flag never written | caught (7) |
| J15 | firstInSession: a throw reads as `true` | caught (1) |
| J16 | firstInSession: no storage reads as `true` | caught (2) |
| J17 | `navigator.standalone` ignored | caught (1) |
| J18 | reduced motion ignored | caught (1) |
| J19 | no `pointerdown` listener | caught (1) |
| J20 | no `touchstart` listener | **missed** (Q-3) |
| J21 | no `load` listener | caught (2) |
| J22 | no cap timer | caught (1) |
| J23 | the `ended` guard dropped | **missed** (Q-3) |
| J24 | `remove()` keeps the class | caught (1) |
| J25 | `endAt`: an absolute delay (`at`, not `at - now`) | caught (3) |
| J26 | the fade without its delay (removed at once) | caught (1) |
| J27 | the class never set | caught (1) |
| J28 | `storageOf` prefers `localStorage` | **missed** (Q-3) |
| R1 | the script's `RING_ALPHA` 0.55 -> 0.6, with the partial left alone (`cargo test the_start_up_holds_mark`) | caught (`shell.rs:1609`, `left: 0.55`, `right: 0.6`); script restored from a copy |

## Findings

### Q-1 (Medium): a lost sessionStorage makes the hold cover a saved page

`static/splash-hold.js:28-37`, `:80`.

- **What is wrong**: whether a document is the session's first rests on one carrier, sessionStorage. This app's own
  notes record that the installed iPhone app lost sessionStorage across a form's navigation (2026-10-19,
  `scroll-restore.js:20-22`, pwa.md "Fix round 2026-10-06"). `window.name` was added as a second carrier because of
  it.
- **What it does if it happens again**: the page that the POST's 303 lands on finds no flag, reads its fresh write
  back, and is "first". It is standalone, coarse, and well inside 1.6 s, so the night overlay covers the saved page
  for about 1.3 s, and then the page fades in. The same happens on a tab change.
- That breaks the spirit of "a save never jumps", and it would happen on every save while the storage misbehaves.
  `scroll-restore.js` survived the 2026-10-19 loss only because of its second carrier. This check has none.
- **What to do**: add a condition that doesn't depend on storage. The hold is due only when this navigation is a
  plain `navigate` (the `PerformanceNavigationTiming` type, with the `performance.navigation` fallback, as
  `scroll-restore.js`'s `navigationType` does) **and** `document.referrer === ""`.
  - The server sends `Referrer-Policy: same-origin` (`admin_auth.rs:736`). So every link, tab, reload and form 303
    inside the app carries a same-origin referrer, while the home-screen launch of `/` -> `/devices` has none.
  - Don't reuse `window.name`: `scroll-restore.js` and `reconnect.js` overwrite the whole string.
  - Add a jstest: a document with a referrer, or a `reload`/`back_forward`, never holds, even with a fresh storage.
  - Say it in pwa.md.

### Q-2 (Low, the user's call): Android's installed app gets a second splash

`static/splash-hold.js:77-79`.

- **What happens**: on Android the WebAPK's own splash (the icon on the manifest's night, with the app's name under
  it, pwa.md "Android") stays until the first paint. Then the hold shows a larger mark with no name, for up to 1.6 s
  from the navigation. That is a visible two-step splash, not "the same picture a moment longer".
- **The same on an iPad and in some iPhone launches**: the iPad, an iPhone launched in landscape, and a size no PNG
  matches (Display Zoom, a future model) get iOS's white launch, then the night overlay.
- The user asked about the iPhone.
- **What to do**: ask the user. Either accept it, or make the hold iOS-only by keeping only `navigator.standalone ===
  true`, which only iOS sets. Matching the PNGs' media queries would be more exact, but more code than it is worth.

### Q-3 (Low): three details aren't tested

`jstest/splash-hold.test.js:20-48`.

- **J20**: `touchstart` is never fired, so its listener could go and nothing would notice. Pointer Events exist from
  iOS 13 (the floor is 14), so either drop the listener or fire it in the test.
- **J23**: nothing triggers `end()` twice while the overlay is still there. Add this sequence: a tap, then the `load`
  timer, then the cap, all before `FADE_MS` has passed. Assert that `className` is exactly
  `"splash-hold is-fading"` and that `removed() === 1`.
- **J28**: the fake window has no `localStorage`, so a swap to `localStorage` passes. That would be a real bug: the
  hold would show once per install and never again.
  - Give `fakeWindow` a `localStorage` whose use fails the test.
  - Or add this test: two windows that share a `localStorage` but each have a fresh `sessionStorage` both hold.

### Q-4 (Low): CI doesn't run the mark's test on a script-only change

`.github/workflows/server-ci.yml:52`.

- **What is wrong**: `the_start_up_holds_mark_is_the_start_up_images` reads `scripts/pwa-startup-images.py` at run
  time, but `server-changes` decides only on `server/` and a few contract files.
- **The effect**: a commit that changes only the script's constants (without regenerating) never runs the test that
  would catch the stale partial. It surfaces on the next server change, on someone else's branch.
- The selftest itself runs nowhere in CI (it needs Pillow). That gap was there before this branch.
- **What to do**: add `scripts/pwa-startup-images\.py` to that pattern, and add it to the root CLAUDE.md's list of
  what `server-ci.yml`'s build job runs on.

### Q-5 (Low): three doc gaps

- **Root `CLAUDE.md:36-37`**: `pwa-startup-images.py` writes `server/templates/partials/splash_hold.html` too. Name it.
- **`NOTICE.md:39-40`**: the Vibb mark is now also in `server/templates/partials/splash_hold.html` (the inline SVG).
  Name it with `server/static/startup/`.
- **pwa.md:912**: "pages without the bar (sign-in, 2FA)" should list the Change Password page and Spotify's "done"
  page as well.
- **pwa.md:914** (nit): "it only sets `.splash-holding` … and timers" leaves out the two tap listeners.

### Notes

- **N-1, the tap**: a tap ends the hold, and that tap is swallowed by the overlay. So is a tap during the 250 ms fade.
  That is right.
  - Don't "fix" it with `pointer-events: none` on `.is-fading`.
  - iOS synthesises the ending tap's `click` at touch end, by hit-testing at that moment. With the overlay no longer
    taking events, the click would land on a control the user never saw.
- **N-2, the cost of the request**: `splash-hold.js` is one more parser-blocking request before the first paint. It
  is fetched in parallel with `scroll-restore.js`, so the cost is small.
  - `/static` has no `Cache-Control` except `startup/`, so a cold start may revalidate both scripts.
  - Inlining the script (the CSP allows it) would avoid that request, but the codebase prefers files.
  - Leave it.
- **N-3, compression**: the overlay is 4.75 KB raw and 2 KB gzipped on every tab page. Compressing HTML server-wide
  (`tower-http`'s `CompressionLayer`) would help every page. That is a separate decision. Don't make one for this.
- **N-4, a relaunch after iOS killed the app**: a new session and an empty storage, so the hold shows. A reload after
  only the web content process died keeps the session, so no hold. I couldn't verify either here. The real iPhone
  cold start is the user's check, as the docs say.
- **N-5, the sign-in page**: it uses up the session without holding. That is fine (section 4).
- **N-6, the headless Chromium check**: it is the implementer's and can't be repeated here. The geometry is checked
  numerically on both sides, and the placement follows from the CSS.
- **N-7, mutations on the generator**: all five failed the selftest. They prove the partial was regenerated, and the
  Rust test is the independent check (R1).
- **N-8, the version bump**: `style.css?v=49` must stay unique when merging with other branches that touch
  `style.css`.

## Verdict

**Mergeable after Q-1.**

- Q-1 is a guard against a failure this app has already seen on the user's iPhone. It costs two conditions and a
  test, and without it a save could hide behind the splash.
- Q-2 is the user's call.
- Q-3, Q-4 and Q-5 are small and can go into the same fix round.
- The rest is right: the conditions, the timing, the first frame, scroll and focus, the shell tests and the mark's
  geometry.
