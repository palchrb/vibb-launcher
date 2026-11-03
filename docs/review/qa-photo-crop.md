# Code QA: photo-crop (the parent picks a contact photo's crop in the PWA)

Branch `photo-crop` (8a39441d, e74716c5, 9551722c) against main, `git diff main...photo-crop`: `static/photo-crop.js` (new),
`templates/device_calls.html`, `static/style.css` (v77), `launcher_ui.rs` asserts, `jstest/photo-crop.test.js`,
`words.test.js`, the two i18n catalogs, `docs/notes/server/launcher-ui.md`. Read-only on code; no browser, no cargo, no
Gradle (as briefed).

## Setup

- Worktree `/workspace/wt-photo-crop`. I read the whole diff, and around it `scroll-restore.js`, `autosave.js`,
  `auto-reload.js`, the shell CSS (body, `.container`, `.tab-bar`), `shell.rs`'s `the_app_shell_scrolls_main_not_the_document`,
  `photos::process`, `calls::view_photo` and `admin_auth.rs`'s CSP middleware.
- `node --test jstest/`: **294 passed, 0 failed** (photo-crop's 9 and `words.test.js` among them).
- A throwaway fuzz of the maths (200 000 random photos from 1x1 to 5000x5000, five random pans / zooms each, frame 280):
  the square stayed inside the photo and the zoom in [1, 4] every time. Panoramas, portraits and 1 px sides included.
- Not run: a real browser. Everything about WebKit below is from reading the code, and the H1 below is why that is not
  enough.

## Counts

**1 H, 2 M, 8 L.**

## H

### H1. The picked photo never opens the cropper: the CSP has no `blob:` in `img-src`

`server/src/admin_auth.rs:857` (`ADMIN_CSP`: `img-src 'self' data: https://tile.openstreetmap.org`) against
`static/photo-crop.js:397-398` (`URL.createObjectURL(file)` handed to `open()`) and `:117-128` (`img.src = src; img.decode()`).

The middleware sets `ADMIN_CSP` on every non-API response (`admin_auth.rs:968-971`), the calls page included. A `blob:`
URL is not `'self'`, so the browser blocks the `<img>` load. `decode()` rejects, `open()`'s reject branch (`:236-246`)
runs `close()` and `plainUpload(input)`: the file goes up as picked, the old autosave. The parent sees the file input
upload at once and the dialog never appears (it may flash for a frame). Only "Adjust crop" works, because
`/contact-photos/{hash}` is `'self'`. The feature's main path is dead on every browser, and it fails *quietly*: the
fallback is exactly today's behaviour, so nothing looks broken.

The jstests cannot see this (a fake `Image`), and the Rust assert checks only attributes. The emulator check the PLAN
names next would catch it (console: "Refused to load the image 'blob:...' because it violates ... img-src").

Fix, cheapest first:
1. Add `blob:` to `img-src` (`'self' data: blob: https://tile.openstreetmap.org`). A blob URL can only be made by
   same-origin script, and `script-src 'self'` holds, so nothing new is reachable. It touches two pinned places:
   `tests/admin_auth.rs:544-548` (exact `img-src` list) and `tests/locations.rs:404` (substring
   `img-src 'self' data: https://tile.openstreetmap.org;`), and `docs/design/33-osm-tiles-referrer.md:53,68` says "one
   image host, nothing broader" - update the three and say why. `music_spotify::page_csp` derives from the const, so it follows.
2. Or no CSP change: read the file with `FileReader.readAsDataURL` and use that as `src` (`data:` is allowed). Costs a
   ~13 MB string for a 10 MB file and a second copy in memory; worse on an iPhone.
3. `createImageBitmap(file)` is not CSP-bound, but Safari has it only from 15 and the file's own header says WebKit
   orientation there is undocumented; not recommended.

Add a Rust test so it cannot regress: assert the calls page's CSP `img-src` contains `blob:` (the photo-crop jstests will
never see it).

## M

### M1. The client downscale is the only downscale now, and it is the browser's default (low) smoothing

`photo-crop.js:261` (`out.getContext("2d")` ... `drawImage(s.work, r.x, r.y, r.side, r.side, 0, 0, side, side)`), `:225`
(the 2048 px working copy) and `:166` (the preview).

Before, the server took the whole photo to 512 with `imageops::thumbnail` (`photos.rs:~270`, an area-averaging
filter). Now a 4000x3000 photo at cover is 2731x2048 -> square 2048 -> 512: a 4:1 reduction in one `drawImage` with the
default `imageSmoothingQuality = "low"` (bilinear, no mip-chain in Chrome and WebKit). That aliases: hair, fabric and
text moire, jagged edges on faces, in a 76 dp circle on a phone. The first reduction (original -> 2048, `:225`) is 2:1
and less visible, but the same call. The server's 512 -> 512 pass is the identity, so nothing repairs it. Then JPEG at
0.92 and again at q85 on the server.

Fix: before the draws set `ctx.imageSmoothingEnabled = true; ctx.imageSmoothingQuality = "high"` (Chrome: Lanczos-ish;
Safari and Firefox mostly ignore the hint, Firefox already averages), and in `save()` step down by halves while
`r.side > 2 * side` (draw to a canvas of half size, repeat) so WebKit gets an area-average too. Raise `QUALITY` to 0.95
(the server re-encodes at q85 anyway; the first pass should add little). A jstest can pin "no single `drawImage` shrinks
by more than 2x" by reading the fake canvas's recorded args.

### M2. Deviation 4: "Adjust crop" is one-way, and each pass loses pixels

`photo-crop.js:95-97` (`outputSide`), `:28` (`MAX_ZOOM = 4`), `:407-410` (the adjust opens the stored square).

The softness is real but mild. The phone's circle is 76 dp: 228 px at 3x, 266 px at 3.5x. A 2x adjust uploads 256 px
(about 1:1 on a 3x phone; fine). 3x gives 171 px (upscaled 1.3x by the phone), 4x gives 128 px (1.8x, visibly soft).
Server `Shape::Square` never enlarges (`photos.rs:~270`, `side.min(MAX_SIDE)`), so the stored file is that small.
What worries me more than the softness:

- The adjust can only zoom *in*. The stored square is the whole picture the dialog knows, so a parent who cropped too
  tight cannot widen it, only re-pick the photo from the gallery. "Adjust" will read as broken then.
- It compounds: adjust at 2x stores 256 px; the next adjust's "cover" is 256 px, so 2x again is 128 px. Two JPEG passes
  (q0.92, q85) each time.

Cheapest fix (a few lines, no server change): in the adjust session (`picked == false`) cap the zoom so the chosen
square stays at least about 230 px: `maxZoom = Math.max(1, Math.min(MAX_ZOOM, w / 230))` for the adjust, passed to
`clampView`/`zoomAt`/`pan` and `slider.max` (the slider's max is set once at `:143`, make it per session). For a 512 px
photo that is 2.2x. Then the stored photo never drops below the phone's native size, and the next adjust still has
room. Upscaling to 512 before upload (the other cheap idea) does not recover detail, only moves the interpolation from
the phone's GPU to a canvas; it is not worth it, and it hides the loss from the next adjust. Say in the note that an
adjust is a refinement of the stored square, not a re-crop.

The right fix is a follow-up, not this round: keep a re-encoded original on the server (<= 2048 px, EXIF/GPS dropped
by the same `process`, so the metadata rule holds; a `photo_src_hash` beside `photo_hash`, pruned and backed up like
it; about 150-300 KB per contact) and the crop view (zoom, centre) with it, and let "Adjust crop" open *that*. Then
widening works, there is no generational loss, and the stored square is always cut once from the original. It changes the
photo-privacy statement (originals are stored, though scrubbed) and `prune`/backup, so it needs the user's yes first.
Put it on the queue as "asked" and ship the zoom cap now.

## L

- **L1. A click on the veil leaks the focus and kills Escape.** `photo-crop.js:370-383` listens for Escape and Tab on the
  dialog only, and the page behind is neither `inert` nor hidden. A mouse click on the dark backdrop (outside
  `.crop-dialog`) puts focus on `body`; then Escape does nothing and Tab walks into the contacts' rows under the veil
  (`aria-modal` is only a hint to screen readers). Fix: listen for `keydown` on `document` while a session is open, or
  `backdrop.addEventListener("mousedown", ...)` -> `frame.focus()`, and set `inert` on `main` and the tab bar for the
  session where supported.
- **L2. The wheel is ~30 times slower in Firefox.** `:333-339`: `deltaY * 0.002` assumes pixels; Firefox's mouse wheel
  reports `deltaMode = 1` (lines, +-3). Multiply by 16 when `e.deltaMode === 1`, by 400 when 2.
- **L3. A stuck pointer.** `:291-300`: any mouse button starts a drag. A right click's `pointerup` can be eaten by the
  context menu, and the photo then follows the bare mouse until the next click. Ignore `e.button > 0` in `pointerdown`,
  and in `pointermove` drop a mouse pointer with `e.buttons === 0`.
- **L4. A failed Save in the adjust session closes the dialog with no word.** `:264-270`: `!blob` and not `picked` ->
  `close()` and nothing (a picked file falls back to the plain upload, which is right). Keep the dialog open and re-enable
  Save instead of `close()` first, or at least move the focus back. Rare (toBlob returns null on memory pressure).
- **L5. The Save test does not test the square.** `jstest/photo-crop.test.js:239-260` is titled "with the square the frame
  shows" and asserts the blob's type, quality and side 512, never the recorded `drawImage` source rectangle of the output
  canvas, which is the one line (`:261`) where the maths meets the pixels. `page.noBlob` (`:129`) is set and never
  used: the `toBlob`-returns-null fallback (`:266-269`) is untested. Pan, pinch, wheel and key handlers are untested
  too (the maths under them is). Add: after the 2x zoom, `canvas out.drawn[0]` equals `[work, x, y, 1024, 1024, 0, 0,
  512, 512]`; one test with `noBlob = true` -> picked file goes up as it is. Do not add more than that; the suite is lean
  and worth keeping lean.
- **L6. The working copy shrinks a banner photo's short side.** `:30-31,88-92`: the long side is capped at 4096, so an
  8000x600 photo becomes 4096x307 and its square is 307 px where the server alone would have cut 512 from 600. An
  extreme panorama (20000x100 -> 4096x20) would be nearly empty. Rare for a contact photo; if wanted, cap by pixel
  count (about 8 MP) instead of by the two sides.
- **L7. Landscape phone.** `style.css:~2410-2440`: the dialog is about 500 px tall (title, hint, 280 px frame, slider,
  buttons), a landscape phone is 360-390 px: the backdrop scrolls (`overflow-y: auto`, `margin: auto`) and Save is
  below the fold on first sight. Fine, but `max-width: min(280px, 50dvh)` on the frame would fit it.
- **L8. The frame is `role="img"` yet takes keys.** `device_calls.html:~252`: a focusable `img` with arrow/+/- handlers
  is announced as a picture. `role="application"` with the same `aria-label`, or `role="slider"`-like semantics on the
  frame, would read better; the zoom slider has no `aria-valuetext` (the browser says "1" to "4"). The aria-busy state
  is not announced. Not blocking.

## Checked and holds

- **Crop maths.** `clampView` clamps the zoom to [1, 4] (NaN and strings read as 1) and the centre so the square lies in
  the photo; at cover the short axis cannot move (`half = min/2`), so minimum zoom = cover at any aspect ratio, tall or
  wide; `pan` and `zoomAt` keep the grabbed point, with the clamp winning at the edges; the fuzz found no square outside the photo.
  `sourceRect` passes `Infinity` as max, which is correct for already-clamped views. The slider and the keys' `zoomAt` at
  (0, 0) do not depend on the frame width, so the stale `s.frame` after a turn only changes the arrows' step and the
  wheel's anchor, not the picture (e74716c5 re-reads it at each press).
- **The square is the phone's circle.** The 76 dp circle is the inscribed circle of the centre-cropped square
  (`centerCrop`); `.crop-mask` is a 50% radius over the same square, so what the ring shows is what the phone shows.
- **EXIF.** The `<img>` decode plus `drawImage` applies the orientation in Chrome 81+, Firefox 77+ and Safari
  13.1 / iOS 13.4+, and `naturalWidth/Height` are the upright size there; the stored photo has no EXIF, so adjust is
  safe. The canvas JPEG has none, so the server's orientation step is a no-op. Not using `createImageBitmap` is the right call.
- **A large file.** `decode()` is off the main thread; the one synchronous cost is the 2048 px `drawImage` (tens of ms);
  after that every move draws from a 2731x2048 canvas to a 280-840 px preview in a rAF. The work canvas is zeroed on
  close (the iOS canvas-memory habit). Over 10 MB or undecodable goes up as it is and the server says why (its 400 page
  with the reason under the photo). A side effect worth a line in the notes: a 48 MP iPhone JPEG that the server refuses
  (8000 px side) is now accepted when it is cropped here.
- **iOS Safari.** Pointer events (13+) with `setPointerCapture` and `touch-action: none` on the frame are the right
  pair for a pinch there (no touch-event path needed); `-webkit-touch-callout`/`user-select` are set; `DataTransfer` and
  a settable `input.files` need 14.1, and `supported()` probes exactly that and leaves the old autosave otherwise;
  `toBlob` with a JPEG quality is in Safari 11+; `decode()` has a `load` fallback; the CSS square uses the padding
  trick instead of `aspect-ratio` (iOS 14). The only WebKit-specific risk I found is the CSP in H1, not the code.
- **No jump.** Save sets `input.files` and calls `form.submit()`, which `scroll-restore.js` hooks (`remember(form)`:
  position of `main`, the tops of `#contact-N`), the redirect carries `#contact-N`, so the row stays where it was; the
  dialog is outside `<main>` and nothing scrolls `main` while it is open (the frame has `touch-action: none` and
  `preventDefault` on wheel; the backdrop has `overscroll-behavior: contain`). The 400 page is the same POST answer as
  before, `contact.error` for field `photo` renders under the photo; the plain fallback sends the untouched file to it.
  `auto-reload.js` is not a danger: the contact's form lives inside a `<details open>` (`device_calls.html:~147`), which
  stops the 3 s reload, though note that removing `data-autosave` (`:388`) also stops auto-reload's own
  "a change has started a save" flag for this input; harmless while the details stays open.
- **Cancel.** `cancel()` clears `input.value` for a picked file, never submits (a test pins `blobs` and `submitted` to
  empty), focus returns to the opener; the adjust session leaves the input as it was. A decode that finishes after Cancel
  is dropped (`session !== mine`) and its blob URL is revoked.
- **The dialog.** `role="dialog" aria-modal aria-labelledby aria-describedby`, the slider has a visible `<label>`, Tab
  and Shift-Tab wrap over the enabled stops, Escape cancels, Escape is ignored while saving. `.crop-backdrop` is
  `position: absolute` in the body (which is `position: relative`, the screen), never `fixed`: `shell.rs`'s
  `the_app_shell_scrolls_main_not_the_document` (which looks for `position:fixed` in the whole sheet) passes by reading;
  `z-index: 3000` is over the tab bar (10), the fade (9) and the status strip (20).
- **Security.** The canvas is never tainted: `/contact-photos/{hash}` and the blob are same-origin and the `<img>` has no
  `crossorigin`. Nothing leaves the origin (no fetch; `connect-src 'self'` unchanged). No file name reaches the DOM or the
  JS (the upload is named `photo.jpg`; the picked name is not read). The only attribute the script trusts is
  `data-photo-adjust`, rendered by Askama (escaped) from a SHA-256 hex hash into an `<img src>` path; `data-side` and
  `data-max-bytes` are the server's constants parsed with `Number()`. The server still re-decodes and re-encodes whatever
  arrives, so a hand-made POST gains nothing.
- **i18n.** All five new keys exist in en and nb, and the nb reads well ("knip", "glidebryteren"); the buttons reuse
  `common.cancel`/`common.save`; the words are rendered by the template, so the script carries no user-visible string
  (`words.test.js` lists only the key names). Nothing to pass as `data-w-*`.
- **Tests.** Lean and mostly meaningful: six on the maths, five on the dialog with a fake page (open without upload,
  Cancel/Escape upload nothing, Save uploads one JPEG, the trap, adjust, the fallbacks, no DataTransfer). The gaps are L5,
  and H1 which no fake can catch.
- **Misc.** `style.css?v=77` and `photo-crop.js?v=1` are bumped/new and served by `ServeDir`; the `[hidden]` override for
  `.row` is handled (`style.css:2392`); the script and the dialog render only when the page has contacts; pageshow resets
  `submitting` for the back-forward cache.

## Suggested order

1. H1 (CSP `blob:` + its two pinned tests + design 33's line + a Rust assert), then run it on the emulator before anything else.
2. M1 (smoothing and the step-down; one test on the recorded `drawImage` args).
3. M2 (the adjust zoom cap now; "keep the original" asked as its own item).
4. L1, L2, L3, L5 are small; the rest can wait.
