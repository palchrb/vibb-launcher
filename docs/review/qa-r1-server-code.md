# Code QA: fix round 1, spor A (server/PWA), `9e085254..10f70c3d`

Branch `fix-r1-server`, 5 commits: the Leaflet marker, the tab bar's width, iPhone safe areas + `tab-bar.js`,
the older-phone warning naming one app, and the nudge after a new catalog release. Reviewed against the diff,
server/CLAUDE.md, git history (thresholds), a headless Chromium (Playwright 1.48, scrollbars shown, 500x700) and
11 Rust + 3 JS mutations.

**Verdict: no High or Medium. Ship after the Lows (docs and one guard test).**

## High

None.

## Medium

None.

## Low

**L1 - server/CLAUDE.md doesn't cover items 1-3 and now says something wrong.** `server/CLAUDE.md:46` still says the
tab bar has "no script". `tab-bar.js` now ships, and `html { overflow-y: scroll }`, `viewport-fit=cover` with the
inset rules, and "dvh only after a vh fallback" aren't mentioned anywhere. Only items 4 and 5 got doc lines. The next
person to touch the bar or the head partial won't know why `overflow-y: scroll` is there (and that
`scrollbar-gutter` was tried and measured worse), or that the insets depend on `viewport-fit=cover`.
*Fix:* in the UI bullet (line 46), replace "no script" with "one deferred script, `static/tab-bar.js`, which lays
the bar out again after an iOS resume (jstest)". Add one sentence on `overflow-y: scroll` (why it's there and why not
`scrollbar-gutter`) and one on `viewport-fit=cover` + `env(safe-area-inset-*)` (container, bar, `.auth-page`,
`.editor-bar`).

**L2 - `static/tab-bar.js:1-7`: the iOS fix is a hypothesis, but the comment states it as fact.** Nobody has shown
that toggling `display` on the fixed bar heals WebKit's stale viewport in a standalone PWA after a resume. The comment
says "CSS can't reach that ... nothing flickers". I measured in Chromium: no focus loss (a focused tab link stays
`document.activeElement` across a resize), no scroll change (`scrollY` 145 -> 145 across resize + `pageshow`), and
no layout shift (the bar is fixed). WebKit itself is unverified. In Safari (not standalone), `resize` also fires
while the toolbars collapse during a scroll, so the toggle runs a few times per scroll gesture. That costs a forced
layout and a backdrop-filter layer rebuild each time. It's harmless, but it's work done for nothing if the fix
doesn't help.
*Fix:* mark it `[needs device check: resume a standalone install after >1 min in the background, bar visible]` in
the comment and in CLAUDE.md. If the iPhone check shows no effect, delete the file (less code). Optional: listen to
`resize` only when `matchMedia('(display-mode: standalone)').matches`.

**L3 - the CSS guards aren't tested (mutations survived).** Removing `min-height: 100vh` before `100dvh`
(`style.css:888`) passes every test. iOS 14, the stated floor, would then drop the declaration and the sign-in card
would lose its vertical centring. The style.css header now promises "dvh only after a vh fallback".
*Fix:* about 6 lines in `shell::style_css_has_the_shape_browsers_read`. For every declaration whose value has `dvh`,
assert that the previous declaration in the same block is the same property with `vh`. (Removing `overflow-y: scroll`
is also uncaught. That one is visual-only and needs a real browser, so leave it.)

## Nit

**N1 - `style.css:1919` `.editor-bar` keeps `margin: -8px -16px 0; padding: 8px 16px 0`.** The container's sides are
now `max(16px, env(safe-area-inset-*))`. In landscape on a notched iPhone the sticky bar's background no longer
reaches the container's padding edge, so the form scrolls visibly in the strips beside Save. It's cosmetic and only in
landscape.
*Fix:* `margin-left/right: calc(-1 * max(16px, env(safe-area-inset-left/right, 0px)))`, with the padding to match.

**N2 - `style.css:151`: `overflow-y: scroll` also shows an empty track on the sign-in/2FA pages on a desktop with
classic scrollbars.** Those pages have no tab bar, so the track buys nothing there. The design is accepted, and
`:has()` is off the floor. Scoping the rule to tab-bar pages would need a class on `<html>` in about 60 templates,
which isn't worth it.

## Checks without findings

- **Item 2: is the implementer's reasoning right? Yes.** Measured at 500x700 with real scrollbars. As shipped, the
  bar is 485 px and the tabs start at x = 4/99/195/290/386 on both `/devices` (short) and `/settings` (long).
  Without the rule, the short pages get a 500 px bar and the tabs move to 4/102/201/299/398, which is the bug. With
  `scrollbar-gutter: stable` instead, `getBoundingClientRect` says 485, but the screenshot of the short page shows the
  tabs painted over the full 500 px (Settings centred at ~447 vs ~433 on the long page). That's the inconsistency the
  implementer reported, so the gutter is out. The other options don't help:
  - `width: 100%` without `right: 0` resolves against the same viewport-minus-scrollbar, so nothing changes.
  - `width: 100vw` puts the bar's right 15 px under the scrollbar on long pages.
  - With `overflow-y` on `html` the viewport stays the scroller, so `scroll-restore.js`, `history.scrollRestoration`,
    `position: sticky` (`.editor-bar`) and `<details>` confirms are unaffected. `body` has no overflow rule anywhere
    (CSS and JS), so html's overflow can't stop body's from propagating.
  - iOS/macOS overlay scrollbars take no room either way.
- **Item 3, insets.** Every page is either `.container` or `.auth-page` (I checked every template that includes
  `head.html`), so the large titles, sub-page headers, the sign-in pages and the Locate map (inside the container)
  all get the insets. Nothing counts an inset twice: the container's bottom padding is 96 px + inset, and the bar is
  about 58 px + inset. The `100vh` fallback comes first, so iOS 14 drops `100dvh` and keeps `100vh`. `tab-bar.js` is
  an external deferred file, so a CSP needs no inline script.
- **Item 4, thresholds.** `git log -S extra_bytes` finds one commit for both files, 1c95b241. `git tag --contains`
  lists `launcher-v0.26.0`, `music-v0.4.0-rc.1` and `server-v0.26.0`. No `launcher-v0.26.0-rc.*` tag exists, and every
  older launcher/music tag is below the cut. So 25_999 (rc of 0.26.0) and 3_999 (rc of 0.4.0) are right.
  - The unknown-side logic holds. Every launcher fills `music_state.version_code`. An unknown `app_version_code`
    means a very old launcher, so naming Vibb One is right.
  - "Both new, still no `extra_bytes`" saying both only happens with a dev build. Accepted.
  - The titles have at most 6 words, the target stays `/music#music-settings`, and the compiler checks the
    `Kind::MusicOlderPhone(_)` matches. All five sites are updated.
- **Item 5, the nudge.**
  - It runs only after the row's UPDATE (autocommit) reported a row, after `store_pins`.
  - A no-op check (same tag + asset id), a refused release and a failed download return before it.
  - A server restart re-stores nothing, so nothing is nudged.
  - The hourly sync sends at most one nudge per phone per new release. The phone coalesces them (`SyncCoalescer`),
    and a lagging SSE receiver turns into one nudge (`commands_stream`), so there's no storm.
  - Re-uploading under the same label nudges again. That's acceptable: one extra cheap sync per parent action.
  - `scoped_apps` is reused, not copied. That means N+1 queries per release, which is fine for a family.
- **Item 1.** `imagePath` matches what Leaflet's `Icon.Default` expects, the three PNGs exist, and the test pins both.
- **Tests.** The jstest drives the real `install()` through fake listeners and rAF, and asserts the
  `none -> read -> ""` sequence, coalescing, ignoring the hidden state, and never scrolling (3 mutations caught,
  below). `a_new_release_nudges_the_phones_that_get_the_app` covers scoping, enrolment, a no-op check, refusal and the
  launcher row in one test without redundancy. The `catalog_pins` change pins the upload's single nudge.

## Mutations (each reverted)

| Mutation | Result |
|---|---|
| Drop the nudge in `sync_release` | caught: `tracked_apps::tests::a_new_release_nudges_the_phones_that_get_the_app` |
| Drop the nudge after an upload | caught: `catalog_pins::the_first_release_pins_the_app_and_a_different_one_is_refused` |
| `nudge_phones_with_app` ignores scoping (nudges every enrolled phone) | caught: `a_new_release_nudges...` (`[1, 2]` vs `[1]`) |
| Nudge every device, enrolled or not (`token_hash` filter dropped) | caught: `a_new_release_nudges...` (`[1, 3]`) |
| Nudge before the row's UPDATE | caught: `a_new_release_nudges...` (the first release isn't listed yet) |
| `VIBB_ONE_EXTRA_BYTES_FROM` 25_999 -> 26_000 | caught: `music::tests::an_older_phone_names_the_app_to_update` |
| `MUSIC_APP_EXTRA_BYTES_FROM` 3_999 -> 4_000 | caught: `an_older_phone_names_the_app_to_update` |
| An unknown versionCode counts as new (`is_none_or`) | caught: `an_older_phone_names...`, `tests::music::the_page_says_an_older_phone_uses_mobile_data_only_with_both` |
| `imagePath` -> `/static/leaflet/` | caught: `locate_page::the_map_marker_uses_leaflets_image_path` |
| Drop `viewport-fit=cover` | caught: `shell::the_app_is_called_vibb_phone_with_a_stable_identity` |
| Drop the `100vh` fallback before `100dvh` | **NOT caught** (L3) |
| Drop `html { overflow-y: scroll }` | **NOT caught** (visual, accepted) |
| tab-bar.js: drop the `offsetHeight` read | caught: 2 jstests |
| tab-bar.js: queue on `visibilitychange` to hidden | caught: jstest "going to the background does nothing" |
| tab-bar.js: drop the once-per-frame guard | caught: jstest "several events in one frame lay it out once" |

## Full run (10f70c3d, x86 VM, under `~/build.lock`)

- `cargo test`: **967 passed, 0 failed** (272.6 s).
- `node --test jstest/*.test.js`: 33 passed, 0 failed.
- `cargo fmt --check`: clean.
- `cargo clippy --all-targets --locked`: 13 warnings, unchanged.
