# Code QA of server round C (`pwa-fixes-c`: a phone's own picture, the push icon, the PWA accent)

Branch `pwa-fixes-c` at 4b300744 (2 commits on f074d842: 78640a5e the round, 4b300744 the power test), reviewed on
`qa-pwa-fixes-c`. Read against `docs/notes/server/pwa.md` ("A phone's own picture", "Field focus and the filled
button"), `notifications.md` ("The push icon") and PLAN.md's two queue lines (the avatar; the accent picks A/3). Paths
are under `server/`.

**Verdict**: the round does what the notes and the user's picks say. The no-session route is sound: it sits on the
admin listener only (`build_admin_router`'s `public_routes`, `src/main.rs:791`; `build_device_router` has the device
API, `/healthz` and an empty 404, nothing else), behind `gate::admin_gate`, takes 64 lowercase hex only
(`photos::path_for`) and serves only a hash a phone names now, so the old hash 404s the moment Remove or a replace
commits, before the prune. The upload reuses the contact photos' pipeline (`photos::process_limited`: sniffed format,
JPEG/PNG/WebP only, pixel and decode-allocation limits, re-encoded without EXIF) behind a 10 MB + 64 KiB body limit
that axum's `Multipart` enforces while it streams; every form and every refusal lands on `#avatar`. The backup carries
`device_avatars/`, the startup recovery restores or forgets, a phone's delete prunes its file. The accent matches A and
3 in Chromium and WebKit (measured below), contrast as the note says. **No High.** One Medium: the no-session route's
guards and the phone listener's exclusion are true in the code but unpinned (three of its mutations survive). The
rest is Low: a tapped borderless field has no focus sign at all, `focus-mode.js` and the accent CSS have no test, the
avatar route's own body limit and the inbox's picture are unpinned, and a doc line on `ADMIN_PUBLIC`.

**The WebKit "goes to /login" observation is a Playwright artefact, not this branch and not main's code** (below).

**Merge with main**: not clean, one trivial conflict. Main's fbe119e8 and this branch's 4b300744 make the same change
to `src/tests/power.rs:270` (`"Anna: turned off"`); the branch also adds a two-line comment above it, and git stops on
that hunk. Resolve by taking the branch's side (the assertion is identical); nothing else conflicts.

## Suites

- `cargo test` (CARGO_TARGET_DIR `qa-pwa-fixes-c`): **1333 passed**, 0 failed.
- `cargo fmt --check`: clean. `cargo clippy --all-targets`: the 13 known warnings, nothing new.
- `node --test jstest/*.test.js`: **164 passed**, 0 failed (with the new sw.js icon case).

## Findings

### Q-1 (Medium) The no-session picture route's guards are unpinned
Evidence: `src/handlers/device_avatar.rs:161` (the hex check through `photos::path_for`), `:169` (only a hash a phone
names), `src/tests/listeners.rs:76-110` (the phone listener's 404 list has no `/phone-avatars/...`),
`src/tests/avatars.rs:124-132` (the 404 cases: `""`, `x`, `b`×64; none has a file on disk).
Mutations R1 (serve any hash whose file is on disk), R2 (no hex check: `dir.join("{hash}.jpg")`) and R8 (the route also
on `build_device_router`) all pass the whole suite. R1 and R2 each hide behind the other today (a traversal needs a
hash the database names; an unnamed file is normally pruned), so nothing is exposed now, but this is the one image
route without a session, and the note promises "else 404, also for a file still on disk" and "not on the phones'
port". A refactor that drops one guard (say, a shared `view` for several stores) would go unnoticed.
Fix: in `tests::avatars`, write a valid-looking `<64 hex>.jpg` no phone names into `avatar_dir` and assert 404; fetch
`%2E%2E%2F...`, an uppercase-hex copy of the real hash and a 65-char hash and assert 404; add
`"/phone-avatars/" + "a"×64` (and `/contact-photos/...`) to `the_device_router_serves_nothing_but_the_device_api`'s
paths.

### Q-2 (Low) A tapped borderless field shows no focus at all; arrow keys after a tap show no ring
Evidence: `static/style.css:326-330` (`.pointer-focus input:focus, select:focus, textarea:focus { outline: none }`),
`:304-317` (the tinted border skips `.row-input` and `select.pick`), `:2049` (`input.row-input` has `border: 0`,
`background: none`). Measured (Playwright, mobile, Chromium and WebKit): `/devices/1/filter`'s domain field tapped ->
`outline: none`, border 0, no shadow, no fill - only the caret; Add Phone's name field tapped -> the tinted border,
white fill, no ring (as designed); Shift+Tab, Tab -> the 2 px ring, `pointer-focus` gone (as designed).
Before this round the tapped `.row-input` had the ring. The same rule also covers radios, checkboxes and the range/file
inputs: a parent who tapped once and then moves through a radio group with the arrow keys gets no ring until a Tab.
Fix: scope the drop to the fields that get the tinted border, e.g. `.pointer-focus input:not(.row-input):not([type=checkbox]):not([type=radio]):focus`
(or give `.row-input:focus` the 1.5 px inset tint like `.row-time`), and clear the mark on any navigation key
(`Tab`, arrows, `Home`/`End`) rather than Tab alone - or note the choice in pwa.md if it is deliberate.

### Q-3 (Low) `focus-mode.js` and the accent CSS have no test
Evidence: `static/focus-mode.js` (no `jstest/focus-mode.test.js`; the file is not exported for node the way
`autosave.js`/`register-sw.js` are), `static/style.css:1143-1160` (`button`), `:294-330` (the focus rules).
Mutations J2 (no mark on touchstart/pointerdown: the ring stays on a tap), J3 (Tab never clears the mark), C1 (the
`.pointer-focus` rule removed) and C2 (white text back on the light peach, 2.6:1) all pass.
Fix: export `install` under `module.exports` and add a node test with a fake document (press -> class set, a typed
letter keeps it, Tab/Shift+Tab clear it); in `tests::shell` (which already reads `style.css`), pin that `button`,
`.button-small` and `.act-strong` take `--on-tint-fill`, that no `--strong` token remains, and that the
`.pointer-focus` rule exists.

### Q-4 (Low) The avatar route's own 10 MB limit is unpinned
Evidence: `src/main.rs:988-991`. Mutation R4 (the `DefaultBodyLimit` layer removed, so axum's 2 MB default applies and
every phone photo of 2-10 MB is refused as `too_large`) passes. `photos::tests` pin `process`'s 10 MB check, not the
route's limit.
Fix: upload a 3 MB valid PNG (or a PNG padded with a large ancillary chunk) and expect the redirect without `?avatar=`;
a body over 10 MB + 64 KiB expects `?avatar=too_large#avatar` and nothing changed.

### Q-5 (Low) The inbox's picture (and two of the row pages) are unpinned
Evidence: `src/handlers/notifications.rs:264`; `src/tests/avatars.rs:142-148` checks `/devices`, `/devices/locate`,
`/schedules`, `/apps`, `/music` only. Mutation R11 (`photo: None` in `inbox_row`) passes the whole suite. An app's
page (`tracked_app_detail.html`) and a music entry's page (`music_entry.html`) are untested too.
Fix: raise an event for the phone with a picture and add `/notifications` (and one `/apps/{id}`, one music entry) to
the loop.

### Q-6 (Low, doc) "The admin gate still applies" needs its `ADMIN_PUBLIC` caveat
Evidence: `docs/notes/server/pwa.md` ("Served without a session"), `src/handlers/device_avatar.rs:15-20`,
`src/gate.rs:62-82` (`ADMIN_PUBLIC=on` skips the network check). With exposure (b) the picture is reachable from the
internet by anyone who has its URL (no session, no gate) - still 256 bits, but a child's photo. The hash only travels
in the signed-in pages, the browser's cache and the end-to-end-encrypted push payload, so this is acceptable; say so.
Fix: one sentence in the note and the module comment: "with `ADMIN_PUBLIC=on` the hash is the only guard".

### Q-7 (Info) The push icon when the fetch fails
`templates/sw.js:108-120` resolves `data.icon` against the worker's origin and keeps only a same-origin path
(`/\evil.example`, absolute URLs and non-strings fall back to the app's icon; jstest pinned, J1/J1b caught). A 404 or
403 icon (the picture removed after the push was queued, or the parent's phone off the tailnet) should not reject
`showNotification`: Chrome drops a resource that fails to load and shows the notification without it - so without
that icon, not with the app's (not tested here: no push service in this environment). The declarative
(Safari) payload keeps its keys; `icon` only rides in `data`, which WebKit passes through. Nothing to change; a note
line in `notifications.md` would help a later reader.

### Q-8 (Nit) `focus-mode.js`'s touchstart listener
`static/focus-mode.js:19` registers `touchstart` on `document` without `passive`. Chrome and Safari treat a
document-level touch listener as passive by default, so scrolling is not delayed, but `{ capture: true, passive: true }`
says it outright (and the `pointerdown` listener alone covers every engine this PWA supports beyond iOS 12).

## What I checked and found right

- **Route placement**: `/phone-avatars/{hash}` only in `build_admin_router`'s `public_routes` (`src/main.rs:791`);
  `build_device_router` (`:750-755`) does not merge it. The gate is the outermost layer (`:1507`);
  `the_picture_route_is_behind_the_admin_gate` pins 403 from a public peer. With `ADMIN_TAILSCALE_USERS` the icon
  fetch through `tailscale serve` carries the login header like any page request.
- **Headers**: `Content-Type: image/jpeg`, `Cache-Control: private, max-age=31536000, immutable` (pinned; R10 caught);
  `admin_headers` adds `nosniff`, CSP and keeps the route's own Cache-Control (not a `no_store_path`).
- **After Remove / replace**: `commit` sets the column under the store's lock, then `prune`; `view` reads the database
  before the file, so the old hash 404s as soon as the commit lands (pinned for replace and Remove; R3 caught). The
  browser's own cached copy of the old URL is private and never shown again (the pages point at the new URL or none).
- **Upload**: `missing_device` first (404 for an unknown phone, pinned); `field.bytes()` is bounded by the route's body
  limit (an oversize body errors mid-stream -> `too_large`); `process` sniffs the bytes (`with_guessed_format`, the
  multipart `Content-Type` is ignored), JPEG/PNG/WebP only (R6 caught), `MAX_INPUT_SIDE`, `MAX_INPUT_PIXELS`,
  `MAX_DECODE_ALLOC` against bombs, one upload at a time (`PROCESSING`). A refused file changes nothing and comes back
  as a fixed code; an unknown `?avatar=` shows nothing (pinned with `<script>`). CSRF guard and `require_full_auth`
  apply (the upload/remove routes are in `admin_routes`).
- **Storage**: own directory and lock (`photos::DEVICE_AVATARS`), backup (R5 caught), `recover_missing` (pinned with a
  hash no zip has), delete prunes (R7 caught). No startup orphan sweep, the same as the contact photos.
- **Push**: `send_due` LEFT JOINs `devices` on the event's phone; the summary and catalog events take `APP_ICON`
  (R9 caught). Payload growth ~90 bytes, well inside the 4096-byte record.
- **Accent**: `--strong`/`--strong-hover` are gone (grep: no reference in `static/`, `templates/`, `src/`);
  `.button-destructive` keeps its own `color: #FFFFFF` on `--destructive`; no rule paints a dark background on a
  `button` while inheriting its text colour. Contrast (computed): night `#0C0C14` on `#E8895E` 7.58:1, on hover
  `#E07A4C` 6.55:1; dark `#F0A884` 9.87:1, hover `#F6C4AA` 12.42:1. Measured in Chromium and WebKit: Add Phone's
  primary button `rgb(232,137,94)` / `rgb(12,12,20)`. `focus-mode.js` is 27 lines, ES5, loaded from `/static`
  (CSP `script-src 'self'`; no inline code, so no nonce question), un-deferred like `autosave.js`. Without JS the class
  never appears and the old `:focus`/`:focus-visible` pair shows the ring.
- **Templates**: the picture over the letter with the dot after it on the Devices list, the header, Locate, Time Rules,
  Apps and an app's page, Music and an entry's page, the inbox (`grep 'class="avatar '`: only the contacts' tiles
  lack it, as they should). Accessibility: the file input is named by its label ("Add a picture for Anna" / "Change
  picture for Anna", visually hidden), the second "Add picture" label is `aria-hidden` (no double announcement), the
  `<img>` is `alt=""` inside the `aria-hidden` tile, "Remove picture" is a real button, the error is `role="alert"`
  and the input's `aria-describedby`. The hidden input stays focusable with its ring drawn round the avatar.

## Mutation pass (17; every one reverted, `git status` clean after)

| # | Mutation | Result |
|---|---|---|
| R1 | `view` serves any hash whose file is on disk (no "a phone names it") | NOT CAUGHT (Q-1) |
| R2 | `view` skips the 64-hex check | NOT CAUGHT (Q-1) |
| R3 | Remove keeps the old file (no prune) | caught (`remove_and_replace_go_back_to_the_letter_and_prune_the_file`) |
| R4 | the avatar route's 10 MB body limit dropped (axum's 2 MB) | NOT CAUGHT (Q-4) |
| R5 | the backup without `device_avatars/` | caught (`the_backup_carries_the_picture_and_a_restore_gets_it_back`) |
| R6 | a non-image accepted (format check off) | caught (`a_file_that_isnt_a_picture_is_refused_under_the_avatar`) |
| R7 | a deleted phone keeps its picture file | caught (`remove_and_replace_...`) |
| R8 | the route also on the phone listener | NOT CAUGHT (Q-1) |
| R9 | the push icon ignores the phone's picture | caught (`a_push_about_the_phone_carries_its_picture_as_the_icon`) |
| R10 | `Cache-Control: public` | caught (`an_uploaded_picture_is_served_and_shown_on_every_phone_row`) |
| R11 | the inbox rows without the picture | NOT CAUGHT (Q-5) |
| J1 | sw.js shows any string as the icon | caught (`sw.test.js` icon case) |
| J1b | sw.js keeps the URL but drops the origin check | caught (`sw.test.js` icon case) |
| J2 | `focus-mode.js` marks nothing on touch/pointer (ring kept on a tap) | NOT CAUGHT (Q-3) |
| J3 | Tab never clears the mark (the keyboard loses its ring) | NOT CAUGHT (Q-3) |
| C1 | the `.pointer-focus` no-ring rule removed | NOT CAUGHT (Q-3) |
| C2 | filled buttons back to white text on the light peach (2.6:1) | NOT CAUGHT (Q-3) |

Score: **8 caught of 17**. Every survivor is a missing pin, not a wrong line; the fixes in Q-1, Q-3, Q-4, Q-5 catch them.

## The WebKit note ("desktop WebKit leaves Notification Settings for /login")

Reproduced on this branch's build, port 3171, fresh data dir, Playwright WebKit with `is_mobile=False`: after sign-in
(2FA), `/devices` stays, `/notifications/settings` and `/settings` go to `GET /login` about 0.3 s after loading;
`/notifications`, `/account`, `/backups`, `/schedules` stay. WebKit with `is_mobile=True` and Chromium desktop stay on
every page.

- **Not a session loss**: the `handy` cookie is unchanged, and the next `goto('/devices')` loads signed in.
- **Not the page**: no `beforeunload`, no referer, no `form.submit()`/`reload()` in the trace - the browser itself
  navigates. Blocking `focus-mode.js` changes nothing; answering `/sw.js` with a 404 (no worker) or blocking
  `register-sw.js` keeps the page; serving main's `sw.js` instead of the branch's still navigates.
- **The trigger is `PushManager.getSubscription()`**: Playwright's Linux desktop WebKit exposes `PushManager` (its mobile
  emulation does not), and calling `getSubscription()` on any page with a registered worker destroys the page's context
  by that navigation (shown on `/devices` with one `evaluate`). Stubbing `PushManager.prototype.getSubscription` with an
  init script keeps both pages. Those are exactly the two pages that call it: `notifications.js` (Notification
  Settings) and `register-sw.js`'s Sign Out lookup (Settings).

So it is a **test artefact of Playwright's WebKit build**, independent of this branch and of main's code (it would
show on main the same way; the earlier main build's hang matches). Desktop Safari has a real push implementation
(macOS 13+), so this says nothing about it; a check on a real Mac is still the way to be sure. For the Playwright
passes: use `is_mobile=True` for WebKit, or stub `getSubscription` in an init script. Suggest replacing the "Worth a
look on a desktop Safari" sentence in pwa.md's browser pass with this explanation.

## Fix round (2026-10-25, branch `pwa-fixes-c`)

All eight findings fixed in 86e522d8. Suites after it: `cargo test` 1336 passed, 0 failed; `cargo fmt --check` clean;
`cargo clippy --all-targets` the 13 known warnings, nothing new; `node --test jstest/` 169 passed.

- **Q-1** - Fixed in 86e522d8: `tests::avatars::the_picture_route_serves_only_a_named_hash` (a `<64 hex>.jpg` on disk
  no phone names, the real hash in uppercase, 63 and 65 characters, `<hash>.jpg`, `..%2Fsecret`, `%2E%2E%2Fsecret`,
  and a row naming `../secret` with `../secret.jpg` on disk - all 404, never the file's bytes; named, the stray is
  served); `/phone-avatars/<64 a>` and `/contact-photos/<64 a>` added to
  `listeners::the_device_router_serves_nothing_but_the_device_api`.
- **Q-2** - Fixed in 86e522d8: `input.row-input:focus, select.pick:focus` get a quiet tinted underline (`box-shadow:
  inset 0 -1.5px 0 var(--tint)`), the keyboard's ring on top. `focus-mode.js` (`?v=2`) clears the mark on Tab and
  Shift+Tab always, and on the navigation keys (arrows, Home, End, Page Up/Down, Space, Enter) when the focus is not
  in a field one types into - so arrow keys after a tapped radio, checkbox or select bring the ring back. Not every
  key (the coordinator's "any keyboard key"): typing and caret moves in a tapped text field keep the mark, since an
  on-screen keyboard sends keydowns too and "any key" would put the ring back the moment a parent types on a phone,
  undoing pick A. `style.css?v=55`. Noted in pwa.md.
- **Q-3** - Fixed in 86e522d8: `focus-mode.js` exports `install` for node; `jstest/focus-mode.test.js` (each press
  marks; pointer -> Tab -> touch -> Shift+Tab -> mouse -> keyCode 9; typing and caret keys kept in a text field, a
  textarea, a number field, a contenteditable; arrows/Space/Enter after a radio, select, checkbox or button clear it;
  no `classList`, no listeners). `shell::the_accent_rules_hold` pins the light tokens, `button`/`.button-small`/
  `.act-strong` and their hovers on `--tint-fill(-hover)` with `--on-tint-fill`, no `--strong`, the no-ring rule
  exactly `outline: none;`, the field's tinted border and fill, the borderless underline, the `:focus` ring, and the
  head's `focus-mode.js`.
- **Q-4** - Fixed in 86e522d8: `tests::avatars::the_upload_takes_a_photo_over_two_megabytes` - a ~3 MB noise PNG
  saves (redirect without `?avatar=`), an 11 MB body comes back `?avatar=too_large#avatar` with the picture kept.
- **Q-5** - Fixed in 86e522d8: the rows' loop in `an_uploaded_picture_is_served_and_shown_on_every_phone_row` adds
  `/notifications` (a low-battery event for the phone) and a catalog app's page (`/apps/tracked/{id}`, the phone
  ticked).
- **Q-6** - Fixed in 86e522d8: "with `ADMIN_PUBLIC=on` the hash is the only guard" (and why it is acceptable) in
  `pwa.md`, `device_avatar.rs`'s module comment and `server/CLAUDE.md`'s `devices` bullet.
- **Q-7** - Fixed in 86e522d8: a line in `notifications.md`, "The push icon" (a 404/403 icon doesn't fail
  `showNotification`; Chrome shows it without an icon; not tried against a real push service).
- **Q-8** - Fixed in 86e522d8: `touchstart` registered `{ capture: true, passive: true }`.
- **The WebKit note** - pwa.md's "Worth a look on a desktop Safari" replaced with this review's explanation (a
  Playwright desktop-WebKit artefact of `PushManager.getSubscription()`; WebKit passes use `is_mobile=True`).
- **Merge note** - kept the comment above `tests/power.rs`'s `"Anna: turned off"` (main's fbe119e8 has the same
  assertion; the coordinator resolves the hunk).

### Mutation re-check (each applied alone on 86e522d8's tree, its tests run, reverted; the tree clean after)

| # | Mutation | Result |
|---|---|---|
| R1 | `view` serves any hash whose file is on disk | caught (`the_picture_route_serves_only_a_named_hash`) |
| R2 | `view` skips the hex check (`dir.join("{hash}.jpg")`) | caught (`the_picture_route_serves_only_a_named_hash`) |
| R8 | the route also on `build_device_router` | caught (`the_device_router_serves_nothing_but_the_device_api`) |
| R4 | the avatar route's body limit layer dropped | caught (`the_upload_takes_a_photo_over_two_megabytes`) |
| R11 | `photo: None` in `inbox_row` | caught (`an_uploaded_picture_is_served_and_shown_on_every_phone_row`) |
| J2 | no mark on touchstart/pointerdown | caught (`focus-mode.test.js`, three cases) |
| J3 | Tab never clears the mark | caught (`focus-mode.test.js`, "pointer, keyboard, pointer") |
| C1 | the `.pointer-focus` no-ring rule removed | caught (`shell::the_accent_rules_hold`) |
| C2 | white text back on the light peach | caught (`shell::the_accent_rules_hold`) |

Every survivor of the review's pass is now caught: 17 of 17.
