# QA (code): a save leaves the history as it was (`pwa-back`)

Branch `pwa-back`, one commit 8980243b4 on `main` (`git diff main...pwa-back`). Reviewed 2026-11-14 in the worktree
`/workspace/handy-wt/pwa-back`. I read `docs/notes/server/pwa.md` (the new section included), `server/CLAUDE.md`, the
diff, and the scripts and handlers it touches or relies on: `autosave.js`, `auto-reload.js`, `reconnect.js`,
`install-now.js`, `passkey.js`, `register-sw.js`, `notifications.js`, `photo-crop.js`, `reveal.js`, `templates/sw.js`,
`csrf_guard`, `mark_post_answer`, the router's layers, and the POST handlers behind the 189 `#fragment` actions where
their answers matter. `node --test jstest/` passes (380 of 380). I ran no cargo tests, Gradle, emulator or browser. I
fixed nothing and pushed nothing.

The bug: every save is a POST, and the 303 back to the same page adds a history entry, so Back after "Sjekk nå"
showed the same page again. The fix: `scroll-restore.js` sends a POST whose action has a `#fragment` with `fetch`
and `X-Handy-Stay: 1`. `admin_auth::stay_answer` turns that request's redirect into a 204 with `X-Handy-Location`.
The page then calls `replaceState` and `reload()` on the same path, or `location.assign` to another path. Every
other answer re-sends the form the browser's way.

## Summary

- **The fix itself is right.**
  - The 204 keeps `Set-Cookie` and the other headers. One-shot session notices, such as Storytel's, are used up
    exactly once, by the reload's GET.
  - The reload is `type === "reload"`, so the `handy-from` logic is skipped. That gives the same result as the old
    same-path `navigate`.
  - Back now goes to the page before.
- **Security holds.**
  - The fetch is same-origin with cookies. It carries `Sec-Fetch-Site: same-origin`, so `csrf_guard` passes it as
    before.
  - A cross-site page can't set `X-Handy-Stay`. With `no-cors` the header is dropped. With `cors` it needs a
    preflight, and there is no CORS layer.
  - `X-Handy-Location` only carries what `Location` would have carried, even for a signed-out caller.
  - No layer outside `stay_answer` makes a redirect (`gate`, `status_bar`, `admin_headers`, compression).
- **The weak point is the fallback.**
  - "Any other answer → send the form the browser's way" also covers a network error and a 5xx. In both cases the
    server may already have acted, and the page re-posts on its own, which the browser never did before (H1).
  - A request that waits gives no busy state, and the button stays live (M1).

Counts: **High 1, Medium 1, Low 5.**

## High

### H1. A lost answer or a 5xx re-posts the form automatically, so non-idempotent actions run twice

`server/static/scroll-restore.js:362-379`. The fetch's rejection handler is `post`, and every answer that isn't the
stay's 204 or an `opaqueredirect` also goes to `post()`. `post()` then calls `requestSubmit(submitter)` or the
native `form.submit()`, which is a second POST of the same body. `jstest/scroll-restore.test.js:885` pins this for
`page(500)`, `page(200)` and a thrown fetch.

The pwa.md section says this is safe because "a refusal is thus posted twice, which writes nothing". That holds for
a 400 or a 423. It does not hold for a rejection or a 5xx, where the server may have done the work and only the
answer was lost.

Concrete failures:

- **The reported flow, on the installed iPhone app.** The parent taps "Sjekk nå" (`CHECK_NOW_WAIT` is 10 s,
  `tracked_apps.rs:4003`) and switches app while waiting. WebKit fails the suspended page's fetch ("Load failed").
  When the parent comes back, the rejection handler re-posts the check. A Wi-Fi to mobile handover has the same
  effect on any save.
- **Non-idempotent targets reached this way.**
  - `/devices/{id}/lifts#quick`: each POST inserts a new `time_lifts` row (`lifts.rs:143`), so "+30 min" becomes
    two lifts.
  - `/devices/{id}/command/ring#actions`, once the first ring was delivered: `queue_command` only replaces
    undelivered ones, so the phone rings twice.
  - `/devices/{id}/kid-pin/send#child-pin` and `/devices/{id}/contacts#add-contact`.
- **A 5xx after work was done.**
  - `/backups/create#backups` answers 500 when `perform_backup` fails, for example on a full disk. The fallback runs
    the backup again on the same full disk.
  - `save_caldav_calendars` (`#calendars`) and a link's `fetch#fetch` fetch for up to 60 s per calendar before they
    answer. A reverse proxy's 504 (nginx's default `proxy_read_timeout` is 60 s) makes the page run the whole fetch
    again, and the parent waits twice as long before seeing the error.
- **Possible on Safari, not verified.** If the page goes into the back-forward cache with the fetch in flight (Back
  during the 10 s wait), the rejection may run when the page is restored by Forward, and re-post from there.

Re-posting is worse than showing an error here. Before this branch the browser showed its error page or the 5xx page
once and never re-sent.

**Fix:** re-send natively only for the answers where the server refused before acting:

- **Re-send** on 400, 403, 404, 409, 422 and 423, and on a 200 that is a refusal page.
- **Don't re-send** on a 5xx or a rejection. Load the page with a GET instead: `land({ reload: true, url:
  location.href })`, or `location.reload()` when offline, so the browser's own offline page shows. A one-shot word
  such as `?notice=unsure` can say "Couldn't confirm the save - check it". The GET shows what the server actually
  holds.

Change the test at `jstest/scroll-restore.test.js:885` to match, and correct the pwa.md bullet "Any other answer ...".

## Medium

### M1. No busy state while the fetch runs; a second tap runs a second full request

`server/static/scroll-restore.js:341-381`. While the fetch is out, the page gives no sign of it.

- **In a browser tab** the native POST used to show the browser's loading bar. That is gone, so the page looks idle
  for the length of the request: Check now's 10 s, `launcher-check`'s wait, `save_caldav_calendars`'s up to 60 s per
  calendar, a backup.
- **The button stays enabled.** A parent who taps again starts a second fetch, and both run to completion. Before, a
  second native submit cancelled the first navigation's connection.
- **The waits are harmless; the quick actions are not.** Check now is protected by `app_syncs.start`. The quick
  non-idempotent actions in H1 (a lift, a ring) are not.

pwa.md's choice not to add a wait mark rests on the installed app, which never showed one. It doesn't cover the tab,
or the double tap that the script could now stop cheaply.

**Fix:**

- Keep a `pending` set of forms. A submit or a hooked `submit()` of a form whose stay fetch is still out does
  nothing.
- While the fetch runs, set `aria-busy="true"` on the form, and `disabled` or a class on the submitter. Release them
  only on the fallback paths, because `land` reloads.

## Low

### L1. Several saves in flight: the first 204 reloads and cuts off the rest

`scroll-restore.js:327-339` and `:362-379`. Take two auto-saving switches flipped quickly, on two forms (Apps'
`/devices/{id}/apps/toggle#…`) or on one form. Fetch A's 204 starts the reload.

- **B is cut off.** If B hasn't answered when the reload commits, the document's unload aborts it, and axum drops
  B's handler on the disconnect.
- **B can show the old state.** If B lands after the reload's GET was rendered, B's switch shows its old position,
  and a parent who taps it again flips it back.
- **One form's two saves can land out of order.** Each body holds the whole form, so if B is processed before A, A
  undoes B.

The old path lost saves too: the second navigation cancelled the first. There the last answer was the page, and its
body held the whole form. Now the first answer is the page.

**Fix:** count the stay fetches in flight, and only `land` when the last one settles, at the last one's
`X-Handy-Location`. To keep one form's saves in order, send a form's next save only after its previous one answers.

### L2. The reload brings the place record over a fragment the form's groups don't hold

`scroll-restore.js:531` (`if (y === null) y = place`). The top comment says a `#fragment` the form's elements don't
hold "leaves the browser at that anchor". That worked because the 303's GET was a `navigate`, so there was no
`place`. A stay save on the same path is now a `reload`, which reads the `place` record. When `restoreTarget` returns
null, the page goes back to the old position, not to the anchor.

The case that hits this today: on the import preview, the music import's Confirm (`/music/import/confirm#import-preview`)
redirects to `/music/import#import`. The two forms are siblings with no recorded ancestor in common, so the result
lands at the old preview's scroll offset, clamped, not at `#import`.

**Fix:** have `land` mark the record (for example `saved.stay = true`), and have the restore skip `place` for a
record that is still fresh.

### L3. `X-Handy-Location` is navigated to with no scheme or origin check

`scroll-restore.js:123-127` and `:327-330`. `land` hands any value to `location.assign`. A browser ignores
`Location: javascript:…`, but `location.assign("javascript:…")` runs it in the PWA's origin. Every redirect the
`#fragment` forms reach is built by the server from closed sets. The redirects that use a `next`, `lock::local_next`
and `passkeys.rs:286`, belong to native forms. So nothing is exploitable today, but the script turns a harmless
`Location` into script execution.

**Fix:** in `stayLanding`, accept only `http:` and `https:` on this origin. For anything else, re-send natively.

### L4. `opaqueredirect` is treated as "saved"

`scroll-restore.js:372-375`. No layer outside `stay_answer` redirects, so in practice an `opaqueredirect` comes from
a proxy in front of the server. A forward-auth portal (Authelia, Cloudflare Access) redirects the POST to its login
before the server sees it. The page then reloads as if the save went through, the switch flips back, and nothing
says so. Before, the browser followed the portal's redirect.

**Fix:** reword the comment so it doesn't claim the save. A plain `location.reload()` is fine, because the portal
then takes the GET.

### L5. Test gaps; the native exceptions hold by accident

- **The native exceptions hold by accident.** Three forms are answered with a page on success: Add Parent and a
  parent's Reset (the one-time password, `parents.rs`, 200) and the setup QR (`provisioning::provision_qr`, 200, a
  new enrollment code each time). They post natively only because they hold a `type="password"` field. The new
  `check_forms` rule pins `data-native-post` to a hand-written list (`answered_with_a_page`, `settings_pages.rs:117`)
  that doesn't include them. If one lost its password field, it would go through the stay. The fallback would then
  re-post it, minting a second password or code, and the first would be shown nowhere. **Fix:** give these forms
  `data-native-post` too and extend `answered_with_a_page`.
- **Missing tests.**
  - The Rust test doesn't check that a GET (or another method) carrying the header passes untouched, that a 423 or
    a 403 passes through `stay_answer`, or that `Set-Cookie` survives the 204.
  - The JS test has no case for an `X-Handy-Location` on another origin or with another scheme (L3).
  - Nothing covers a `requestSubmit` that throws because the submitter has left the form (`:322`; the exception
    escapes from a promise callback, and nothing is shown).

## Resolution

Fixed on `pwa-back` in the commit after b5ef2fd2a (see `docs/notes/server/pwa.md`, "A save leaves the history as it
was").

- **H1**: only a refusal is sent again the browser's way - 400, 403, 404, 409, 422, 423 and a 200 (a refused form
  shown again; the forms answered with a page on success are `data-native-post`). A rejected fetch, a 5xx, any other
  status, an `opaqueredirect` and an unusable `X-Handy-Location` never post again: the page is loaded with a GET
  (`replaceState` to its own address, then `reload()`), which shows what the server holds; offline, the browser's own
  page. No "unsure" notice exists, so no new words were added. The JS test that pinned the old fallback now pins both
  sides (`a refusal is sent again ...; no answer or a 5xx never posts again`); the pwa.md bullet is rewritten.
- **M1**: while a form's save is out the form carries `aria-busy="true"` and its submit button is disabled with
  `aria-busy` (style.css v113: `button[aria-busy="true"]` dimmed to 0.55, `cursor: progress`, no animation). A second
  submit of that form is prevented and does nothing; the state is released on every path but the reload.
- **L1**: the stay saves in flight are counted; the page lands once, when the last has answered, at the last one's
  address (a refusal outweighs a lost answer, which outweighs a landing, so a refusal still shows). A form's
  `form.submit()` while its save is out is queued and sent, with the form as it is then, after the first answer.
- **L2**: `land` marks the form record `stay: true` before the reload; on load, a fresh stay record whose
  `restoreTarget` is null never takes the place record - the `#fragment`'s element is scrolled into view
  (`scrollIntoView(true)`, the anchor). Tested with the import's Confirm -> `#import`.
- **L3**: `stayLanding` returns null for anything but an http(s) address of this origin; the page is then loaded with a
  GET (the save was done, so it is not posted again).
- **L4**: `opaqueredirect` is "unknown": the plain GET reload, and the comment says so.
- **L5**: Add Parent, a parent's Reset and the setup QR carry `data-native-post` and are in `answered_with_a_page`.
  New tests: `admin_auth::tests::only_a_stay_posts_redirect_becomes_its_204` (the 204 keeps `Set-Cookie`; a GET with
  the header, a POST without it, a 423 and a 403 pass as they are), JS cases for another origin, `javascript:`,
  `data:` and another scheme, and for a `requestSubmit` that throws (the page with a GET, nothing escapes).

Checks: `node --test jstest/` 385/385; `cargo fmt --check`; `cargo clippy --all-targets -- -D warnings` clean; the
headless Chromium and WebKit run against the stub again (saves keep `history.length`, Back goes to the page before,
a 400 is shown by the browser's own POST, a password form posts natively). No cargo tests run locally.
