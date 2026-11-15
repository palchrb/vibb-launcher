# Design 104: the status bar's grey fade after a return, without changing the look

Status: built, 2026-11-15 (branch `design-104`, from main 09804930b). Server only: one static script, its test and its
cache number. No template other than the head's script line, no style, no route, no migration, no contract file.

The user (PLAN, "iOS: the grey fade over the status bar after a return - a fix that keeps the look"): on the installed
iPhone app the area behind the status bar now and then turns a grey-to-night fade, *"etter den har vært i bakgrunnen
av og til"* - after a return from the background, not every time. Server-v0.76.1's fix (the night canvas and a
one-frame nudge of the strip on a return, `docs/notes/server/pwa.md`, "The status bar's grey after a resume") does
not always win. The user's condition: fix it without changing how the app looks.

## 1. What the user's iPhone gets today

The first plan was to drop `black-translucent` (the research: qui #2520, taOS #3427, yuvomi #2399, volleybro #427 -
since iOS 26.1 a `black-translucent` home-screen app gets a system scrim over the status bar that iOS loses after a
resume; the projects that dropped the style lost the scrim), tested first as a second Home Screen copy. Reading what
the server sends showed it is already done for this iPhone:

- `server/src/status_bar.rs` (since `pwa-ios27`, server-v0.72.1, 2026-11-10) gives `black-translucent` only to a Safari
  whose `Version/NN` is 26 or lower; every other answer carries `head.html`'s `default`.
- The user's iPhone is on iOS 27.2 (PLAN, "PWA on iOS 27", and the lab readout in `pwa.md`, "On the device": top inset
  0, the page below the bar, a night status area with a white clock), and the app was removed and added again after
  0.72.1. Its Safari says `Version/27.2`, so the page it was added from carried `default`; the installed app's own
  requests carry no `Version/` and get `default` too.

So the installed app already has `default`, and the fade the user sees is not the `black-translucent` scrim: that
research applies only to Safari 26 or older. A test copy with the opaque style would have been the same app as today.
The rule in `status_bar.rs` stays as it is.

## 2. What the fade is under `default`

As read in 2026-11-12's round: iOS 26+ draws UIKit's scroll-edge pocket over the status area; WebKit hides it and paints
the area in a colour of its own when the page has a fixed box at its top edge (`.top-strip`, the night strip). WebKit
sends that edge and its colour to the app only when they change, and after a resume the state can be lost; then the
pocket shows. Server-v0.76.1 nudges the strip one frame off the night (`#0C0C15`) on `pageshow` and on a return to
visible, so the colour changes and is sent again. The nudge comes at the first moment the page is visible - which may
be before the web view is back on screen, so the update can be lost as well.

## 3. The change (`static/top-strip.js`, `?v=2`)

- **Retries after a return**: on `pageshow` and on `visibilitychange` to visible, the nudge as before, and again
  300 ms, 1 s and 3 s later (`RETRY_MS`), each the same one painted frame off the night.
- **Never stacking**: a new return clears the pending retries and starts its own series; going hidden clears them;
  a nudge that is still on its frame is not started again (as before).
- **The keyboard as cheap cover**: a field's `focusout` and the visual viewport's `resize` (the keyboard coming and
  going) re-arm one timer; a single nudge runs 300 ms after the last of them (`KEYBOARD_MS`), once the keyboard has
  settled. `visualViewport` is Safari 13+; without it `focusout` alone.
- Nothing else changes: the strip, its colour, the canvas, the bar style. Each nudge is one frame one step off the
  night - not visible. Elsewhere (Android, a desktop, a Safari tab) there is no pocket and nothing to see.

ES5 as before (Safari 14 floor). No reinstall: the next load of any page takes `top-strip.js?v=2`.

## 4. What to look for

The user uses the installed app as usual for a few days. Look for the grey fade after the app has been in the
background (switch to another app, lock the phone, come back), and after typing in a field. If it still comes back:
whether it stays, or goes by itself within about 3 s (the last retry) - that tells whether the retries reach WebKit
late, or not at all.

## 5. Not verified here

No iPhone in this round: whether WebKit takes a nudge 0.3-3 s after a return when it missed the first, and whether the
fade appears after the keyboard at all. Checked: `node --test jstest/top-strip.test.js` (the retries' times, one frame
each, a restarted series, hidden cancelling, the keyboard's single debounced nudge, no `requestAnimationFrame` or
`visualViewport`), `cargo fmt --check`, `cargo clippy`.

## 6. Rollback

Revert the commit: `top-strip.js` goes back to 0.76.1's (nudge on a return only) and the head to `?v=1`. Tests:
`jstest/top-strip.test.js`, and `shell::the_app_shell_scrolls_main_not_the_document`, which pins the script's line
in the head.
