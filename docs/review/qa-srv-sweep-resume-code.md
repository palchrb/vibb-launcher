# Code QA - server round (sweep, resume, lock select): branch `srv-sweep-resume`

- Commits: `533405a3..a0c3d44f` on branch `srv-sweep-resume`, five commits: `5d54da9e` (the Spotify checks touch
  Spotify less: the playlist snapshot gate without the 24 h re-read, the daily context retry, the show gate),
  `d0417e2f` (a new phone starts with the notification filter on), `4c6574c9` (`static/reconnect.js` and `GET /ping`),
  `c779739d` (one select per app on the lock, the `/text` route removed), `a0c3d44f` (a quiet NRK/RSS entry checked
  daily).
- Read: root and `server/` `CLAUDE.md`; `docs/notes/server/music.md` (the sweep's cadence, Spotify on the server),
  `pwa.md` (Lock Screen & PINs, the new "Connecting" section), `play-and-updates.md` (Kiosk escapes),
  `hardening-and-pins.md` (the lock's glance page); the whole diff; around it `spotify_check` and `finish`'s
  `music_listings` write, `schedule`/`schedule_one`, every reader of `spotify_items_at`/`spotify_snapshot`,
  `security::require_full_auth`, `build_device_router`, `admin_auth::admin_headers`/`ADMIN_CSP`,
  `static/scroll-restore.js` (the form and place records, `MAX_AGE_MS`), `static/auto-reload.js`, the POST handlers
  that render a page (`devices::regenerate_code`, `provisioning::provision_qr`, `auth::render_account`,
  `backups::restore_backup`, `auth::login`/`verify_2fa`), `kiosk_escapes::escapes_card`, the old lock template at
  `533405a3`, `jstest/notifications.test.js`.
- Ran: `cargo test`: **1105 passed, 0 failed**; `cargo fmt --check` clean; `cargo clippy --all-targets`: the 13 known
  warnings, none in a line this branch touches (`models.rs`, `dns_engine.rs:54-55`, `storytel.rs:97`,
  `devices.rs:1300`). `node --test jstest/`: **124 passed** (six runs in a row, and 15 more under a cargo build's
  load). The flaky `jstest/notifications.test.js` reproduced: 5 of 24 parallel runs failed (`not ok 5`/`6`), see Low 8.
  A headless Chromium check (Playwright, a throwaway Node server) for Medium 1: `location.reload()` on a POST's answer
  sent `POST /answer` again; with `history.replaceState(history.state, "", location.href)` first it sent `GET`.
- Mutations: the table at the end (8 JS, 12 Rust).

## What was verified

- **Spotify, the playlist gate** (`music_sweep.rs:2850-2856`): list mode + a `snapshot_id` + the same as stored =
  done after the metadata, for any age (the user's decision); `meta.snapshot` `None` (Spotify left it out) always
  reads the items, `old.spotify_snapshot` `None` (never read) too; Check now (`mode.requested`) always reads (R1
  caught). An early return keeps the plan's `SpotifyPlan::from_listing(old)` (mode, snapshot, `items_at`, reader,
  skipped) and the old items, so nothing in the listing or its version moves. Knowingly given up (in `music.md`): a
  list-mode playlist whose reader lost access stays a list until the snapshot moves or Check now - the tracks play
  one by one anyway.
- **Spotify, the context retry** (`music_sweep.rs:2861-2867`, `context_tried_lately` `:3057`): only for a playlist
  whose stored mode is `context`; `spotify_items_at` is now "the last try" in context mode, stamped by
  `spotify_context(.., Some(stamp(now)))` on the refused read, kept by the early return, `None` for an artist. Every
  reader of the column is in `music_sweep.rs` (`Listing`, `SpotifyPlan::from_listing`, `finish`'s write with
  `keep_fields`, the gate) - no page, route or restamp reads it, so the change of meaning breaks nothing. A listing
  from before (context, `items_at` NULL) is tried at its next check and stamped. `authorized_at` is written only by a
  login (`spotify.rs:1483-1488`, not by a token refresh), so "a newer login" really means a login; both stamps are
  `stamp()`'s format. R2, R3 caught.
- **Spotify, the show gate** (`music_sweep.rs:2930-2946`, `spotify::show_mark` `spotify.rs:2569`): only after a full
  read has stored a mark (a show listed before this branch has `spotify_snapshot` NULL and reads in full once, which
  stamps it); the gate's read fails the check like the full read would (the last list kept); an empty show has no mark and always
  reads in full (cheap); a `null` newest entry is `-`, a non-null unplayable one keeps its id - the same from the
  `limit=1` read and the full read's first page (same reader, same `market=from_token`); another reader in another
  market gives another mark = one full read (the safe side). Total changes and a new newest are both caught (R4, R12
  caught). Unchanged: 2 requests instead of up to 3 (metadata + up to two pages of 50).
- **Quiet entries** (`music_sweep.rs:478`, the `newest_at` SQL `:683-688`, `schedule_one` `:526-531`): the timestamps
  compared as text are all `stamp()`'s `%Y-%m-%d %H:%M:%S` (`published_at` is `stamp(..)` of the parsed date,
  `first_seen_at` `Item::new(.., now)`), so `MAX` is chronological; the two-argument `MAX` is NULL-safe through the
  `COALESCE(.., '')` and `NULLIF`. `gone` items keep their rows, so `MIN(first_seen_at)` (the first fill) is stable.
  Undated: by the first fill's sighting, quiet 30 days later; a new item, also an old-dated one, makes `newest_at` the
  check's time (R6 caught). Future dates (a scheduled episode, a wrong timezone, a skewed host): never quiet - the
  safe direction, checked at the interval as before. Server clock skew moves every cadence alike, nothing new. The
  quiet interval also caps the backoff (daily), as documented. Storytel/Spotify excluded (`quiet` matches only
  NRK/RSS; unit-tested). Cost: three correlated subqueries per entry per `schedule()` (once per check in a pass), each
  an index range on `music_items_seq (entry_id, seq)` - fine at this library's size.
- **`GET /ping`** (`main.rs:696-702`, `:786`): inside `admin_routes`, so behind `require_full_auth`, the admin gate
  and `admin_headers`; an empty 204 with `no-store` (tested). No body, no version, no timing beyond what any admin URL
  gives; a GET, so the CSRF guard is irrelevant. Signed out: a 303 to `/login` (tested), which `fetch` follows - the
  ping then downloads the login page, harmless. The phone listener (`build_device_router`, `main.rs:712-717`) has only
  `device_routes()` and `/healthz`: no `/ping` there, but nothing tests it (R10 survives, Low 6). No rate limit is
  needed beyond the other admin routes' (a session or a redirect, no work).
- **reconnect.js, what it never touches**: only `<a href>` clicks (`linkOf`), never a form, a `<button>` or a
  `formaction`; skipped are `download`, `data-no-wait`, a `target` other than `_self`, other origins and schemes,
  same-page fragments, modified and `defaultPrevented` clicks (J1, J6 caught). Every same-origin GET a link reaches
  was checked: pages, `?notice=`/`?from=` variants, the inbox's mark-as-seen and the push's `opened` stamp
  (idempotent); the only attachment is the backup download, now `download`; `/music/spotify/callback` is reached by
  Spotify's redirect, never a tap. So a restart of a **link** navigation re-fires nothing with a side effect - the
  **reload in place** can (Medium 1).
- **The place after the reload**: `keepPlace` writes scroll-restore's form record (`path`, `y` of `<main>`, `t`,
  `tops` over a fragment) in sessionStorage and `window.name` (J8 caught); scroll-restore also reads its place record
  for a `reload` navigation, so a reload that takes longer than the form record's 20 s (`MAX_AGE_MS`) still lands on
  the place, not the top. No reload after a `submit`, a `data-autosave` change or with a `<details open>` (J3, J7
  caught).
- **Browser floor**: ES5 (tested by a regex, and read): `new URL`, `AbortController` (guarded), `fetch` with
  `cache: "no-store"`, `classList`, `hidden`, `pageshow.persisted` - all in Chrome/WebView 80 and iOS Safari 14.
  `connect-src 'self'` allows the ping on every page, the Spotify page's own CSP is `ADMIN_CSP` with `form-action`
  widened. The bar and the chip are `position: absolute`, never fixed; the body is the non-scrolling shell
  (`overflow: hidden`, `100dvh`) on every page, so the document's top is the screen's. `--tab-on`, `--row-active`,
  `--surface`, `--label-2`, `--tint` exist in both themes. `style.css?v=39` bumped; the stylesheet shape test passes.
- **The lock select** (`lock_glance.rs`, `device_lock.html:203-218`): one `form.cell#lock-screen-app-<pkg>` per app,
  its label the app's name (and note), a native `select.pick` with exactly one `selected` (tested), `data-autosave`
  plus a `<noscript>` "Save <app>" (no-JS path); a row listed only because it is on (`unlisted`) lands on
  `#notifications` when set to Off, else its own row (R8 caught); unknown or missing `show` is a 400 before any
  read, a bad package a 400, an unknown phone a 404, a DB error a 500 - nothing written (tested). The security log has
  a line per change only (R7 caught): Off<->on as `lock_screen_app_changed`, the text as `lock_screen_text_changed`,
  Off->Full text both. The stored rows are design 25/51's, so the policy JSON is byte-identical (`policy_json_keys_
  snapshot` untouched and green; `each_move_of_the_select_is_stored_as_the_lock_shows_it` compares the policy at
  every move; R11 caught). The `/text` route is gone from `main.rs`, the template and the tests; no link or doc
  still points at it except the history in `hardening-and-pins.md`, which now says it is gone.
- **The filter for new phones** (`devices.rs:433-436`): the only `INSERT INTO device_policy`, so every new phone (the
  Add Phone flow) gets `notification_auto_cancel = 1`; the column default stays 0 and no row is updated, so existing
  phones keep their value (tested, R9 caught); the Advanced switch reads the row, so the PWA shows it on.

## Findings

**1 (Medium) - the reload in place after being away re-POSTs a page that is a POST's answer.**
`static/reconnect.js:247-253` reloads with `win.location.reload()`, and the script is on every page, including the
pages a POST renders directly instead of redirecting: the setup code (`devices::regenerate_code`, `POST
/devices/{id}/regenerate-code`), the setup QR (`provisioning::provision_qr`, its Wi-Fi password in the form), the
Account page's answers (`auth::render_account`: password change, reset two-factor), a refused restore
(`backups::restore_backup`), the music import's results, and the sign-in pages' errors (`login`, `verify_2fa`,
`change_password`, `setup_2fa_verify`). A phone's screen going off counts as hidden, so the likely case is real: the
parent shows the setup code, sets the kid's phone up for more than 10 minutes, wakes their own phone - the ping
answers and the page reloads. Chrome and Safari then ask to resend the form; a parent who taps Continue/Send makes a
new setup code (the one being typed is dead), resends the Wi-Fi password, re-runs a password change with a stale
"current password" or a restore. Headless Chromium resends without asking (checked: `POST /answer` twice). This is the
"never re-fires a navigation with side effects" rule, broken by the reload, not by the link restart.
Fix: (a) mark every template rendered as a POST's answer - one attribute, e.g. `<body data-post-answer>`, set by those
handlers' templates (or a `post_answer: bool` the head partial turns into `<meta name="handy-post-answer">`) - and skip
the return reload there (`cameBack`: `away && !saving && !postAnswer`); (b) as a belt, in `go()` call
`win.history.replaceState(win.history.state, "", win.location.href)` before `reload()` (checked in Chromium: the
reload is then a GET), so no reload can ever resend a form - with (a) in place it never lands on a POST-only URL's
405. Tests: a jstest that a page with the marker gets the ping but no reload and that `go()` calls `replaceState`
before `reload`; a Rust test that `regenerate_code`'s, `provision_qr`'s and `render_account`'s answers carry the
marker and a plain GET page doesn't.

**2 (Low) - a navigation that is only slow, or never leaves the page, is restarted every ~15 s for good.**
`reconnect.js:279`: with the server answering, `now() - w.restarted >= STUCK_AFTER` restarts the navigation 12 s
after the tap and again every 15 s (the doc's own Chromium check saw "a restart about every 15 s"), forever. A page
that takes longer than 12 s on the Pi (`/updates` waits up to 8 s for GitHub before it renders; a cold Music page on a
Pi Zero) is cancelled and asked again each time and may never arrive, and each restart redoes the server's work (the
GitHub check is unauthenticated: 60 an hour). The same loop hits any future same-origin link whose answer is a file
without `download` or a 204. Fix: keep the restart after a failed ping (the network came back) as is, but without a
failed ping restart at most once (or double the wait each time: 12, 24, 48 s) and then only offer "Try again"; a
jstest: answered pings at 3, 8, 13 ... 60 s restart once.

**3 (Low) - a save started during a wait is cancelled by the restart.**
`reconnect.js:331-335`: a `submit` or `data-autosave` change only sets `saving`, which only the return reload reads.
With a link's wait running (the tap didn't arrive, the parent then flips a switch on the same page), the next
answered ping calls `location.assign(link)`, which cancels the switch's POST navigation - it may or may not have
reached the server, and the page that comes is the link's, so the parent doesn't see whether it saved. Fix: on
`submit` and on an autosave `change`, `end()` the current wait (the newest action wins; the bar of the abandoned link
goes). A jstest: tap, change an autosave control, answered pings - no `assign`. (Programmatic `form.submit()` without
an autosave control, e.g. `notifications.js`'s Turn On, fires neither event; `pagehide` covers it once the page goes.)

**4 (Low) - the reload in place drops what was typed outside a `<details>`.**
`reconnect.js:271` keeps the page only for a save under way or an open `<details>`. Fields outside one are lost by the
reload: the sign-in form, the 2FA code, Add Phone's name, the music import's text, a time rule's fields, the
provisioning settings. Browsers restore some form state on reload, not reliably, and never in an installed iPhone app.
Fix: also skip the reload when a form control differs from its default (`value !== defaultValue` for inputs and
textareas, `checked !== defaultChecked`, an `option.selected !== defaultSelected`), checked when the ping answers; a
jstest with a typed input.

**5 (Low) - after a `pagehide` that doesn't unload, a wait has no timers left.**
`reconnect.js:358-361`: `pagehide` runs `stop()`, which clears the wait's timers but keeps `wait`. If a browser fires
`pagehide` without unloading the page and it then comes back without a `pageshow` that is `persisted` (not seen
here; possible in an installed iPhone app going to the background), `cameBack` pings once (`check(wait)`); if that
ping fails, nothing pings again and "Try again" never comes if it wasn't already offered - the chip says
"Connecting…" until `online` or the next visibility change. Fix: in `cameBack`, when a wait exists and its timers were stopped (`wait.timers.length === 0`), re-arm the 5 s ping and the 20 s offer (a small
`arm(w)` shared with `start`); a jstest: tap, `pagehide`, show, failed ping, then pings every 5 s.

**6 (Low) - test gaps: the phone listener's `/ping`, a stale page's posts.**
(a) Adding `.route("/ping", get(ping))` to `build_device_router` keeps every test green (R10 survives); the phone
listener must stay `device_routes()` + `/healthz`. Fix: one line in `shell::every_page_carries_the_connecting_script_
and_its_ping` - `send_via(app.device_router(), PEER, GET /ping)` is 404. (b) A Lock page loaded before the update
still posts `on`/`show_text`: its tick gets the 400 text "Pick Off, Name only or Full text." and its text switch an
empty 404 (the route is gone). Nothing is written, so it is safe; the answer just doesn't say what to do. Fix: word
the 400 "This page is out of date - reload it." for a body without `show`, or map the old fields (`on` present -> on
with the stored text, absent -> Off) for one release. (c) The landing of an `unlisted` row set to Name only or Full
text (its own row, not the group) is pinned only by `device_pages::lock_forms_return_to_the_page` (R8); a line in
`each_move_of_the_select_…` with `unlisted=1` and `show=text` would pin it where the rule lives.

**7 (Low) - `write_show` reads then writes in a deferred transaction.**
`handlers/lock_glance.rs:145`: `state.db.begin()` then a `SELECT` then an `INSERT`/`UPDATE`/`DELETE`. In WAL mode a
second save that commits between the read and the write (two parents, two tabs) makes this one's write fail with
`SQLITE_BUSY` at once (the busy timeout doesn't apply to a read-to-write upgrade) - a 500 "nothing was changed"; two
"Name only" saves racing can also hit the primary key on the `INSERT`. Fix: `begin_with("BEGIN IMMEDIATE")`, as the
sweep and the music handlers do for their read-then-write transactions.

**8 (Low) - `jstest/notifications.test.js` is flaky under load; this branch makes it slightly worse, not the cause.**
Its `settle()` (`notifications.test.js:86`) waits a real 20 ms, and the code under test awaits
`crypto.subtle.digest` (node's WebCrypto runs it on the thread pool), so on a loaded machine the digest isn't back
before the asserts: tests 5 ("a subscription the server knows is On…") and 6 failed in 5 of 24 parallel runs here.
`node --test jstest/` runs each file in its own process, so the new `reconnect.test.js` adds one more process to the
contention - it doesn't touch the timing. Fix (tried here on a copy: 0 of 24 parallel runs failed): give the fake page
a digest that resolves as a microtask -
`const syncSubtle = { digest: (_a, bytes) => { const d = crypto.createHash("sha256").update(bytes).digest(); return
Promise.resolve(d.buffer.slice(d.byteOffset, d.byteOffset + d.length)); } };` and `crypto: { subtle: syncSubtle }` in
`fakePage` (line 80); the "the key decodes and the hash matches the server's" test keeps the real WebCrypto, so the
real digest is still checked.

**9 (Low) - the chip's `role="status"` may not be read out.**
`reconnect.js:115-143` (`domView`) makes the chip with its text already in it and `hidden`, then only un-hides it.
Screen readers announce a change of a live region's content; a region that appears with its text already there is often not
announced (VoiceOver especially), so a blind parent hears nothing. Fix: keep the chip's `<span>` empty while hidden
and set `textContent = TEXT.connecting` when it is shown (clear it when hidden).

**10 (Low) - `show_mark` without a `total` can freeze the gate.**
`spotify.rs:2569-2577`: with no `total` the mark is the first entry's id alone, and `-` for a `null` entry. A show
whose newest entry is unavailable in the market and whose answer has no `total` keeps the mark `-` as episodes come
and go, so the gate holds until Check now. Spotify's paging object always has `total` today, so this is defensive.
Fix: `None` without a `total` (no gate, the next check reads in full); a unit test.

**11 (Info) - things to know, no change asked.**
(a) A new phone without notification access now shows the orange "Filter has no notification access … Grant it with
adb" row on Advanced (`kiosk_escapes.rs:542`, `escapes_card`) once it reports `notification_listener_enabled:
false`; it is not an Attention item, and phones set up for the lock's glance page have the access anyway. (b) A reload in
place keeps `?notice=` (only `auto-reload.js` and `install-now.js` strip theirs), so a page left on a refusal or
"sent" notice shows it again after being away; dropping it in the `replaceState` of Medium 1's fix costs one line.
(c) The `every` timer pushes one id per 5 s into `w.timers` while a wait lasts (720 an hour); harmless, `stop()`
clears them. (d) The show gate assumes Spotify's episode order is newest first, as the full read already does; a
serial show listed oldest first still moves its `total` on a new episode.

## Mutations

Each one change, the relevant tests run, then reverted (the tree was clean afterwards).

| # | Mutation | Result |
|---|---|---|
| J1 | `navigationTarget` without the `defaultPrevented` check | `which clicks are navigations` |
| J2 | `STUCK_AFTER = 0` | `a server that answers at once doesn't restart a navigation that is only slow` |
| J3 | the return reload ignores `saving` | `no reload in place while a <details> is open or after a save started` |
| J4 | any `pageshow` (not only `persisted`) clears the wait | `a first pageshow (not from the cache) doesn't clear a tap…` |
| J5 | a 5xx counts as an answer (`< 600`) | `a 5xx from a proxy is no answer` |
| J6 | no same-origin check | `which clicks are navigations` |
| J7 | the return reload ignores an open `<details>` | `no reload in place while a <details> is open…` |
| J8 | no `keepPlace()` before the reload | `back after more than 10 minutes: … keeps the place` |
| R1 | the playlist snapshot gate without `!mode.requested` (Check now gated) | `a_check_lists_the_fixture_playlist`, `a_403_playlist_goes_to_context_mode_and_back`, `entries::the_cards_and_check_now` |
| R2 | the context retry after 1 h instead of 24 h | `a_403_playlist_goes_to_context_mode_and_back` |
| R3 | the context retry ignores a newer login | `a_403_playlist_goes_to_context_mode_and_back` |
| R4 | the show gate stops on any mark (no comparison) | `a_show_reads_its_episodes_only_when_the_newest_or_the_count_moves` |
| R5 | `quiet`'s `<= now` -> `< now` (the 30-day boundary) | `a_quiet_entry_is_checked_daily_until_it_has_a_new_item` |
| R6 | `newest_at` without the "first seen by a later check" subquery | `a_quiet_entry_is_checked_daily_until_it_has_a_new_item` |
| R7 | the lock logs every save (`if true`) | `each_move_of_the_select_is_stored_as_the_lock_shows_it`, `ticking_and_unticking_an_app_is_idempotent_logged_and_nudges` |
| R8 | an `unlisted` row lands on `#notifications` for any value | `device_pages::lock_forms_return_to_the_page` only (no `tests::lock_glance` test covers an unlisted row set to Name only/Full text) |
| R9 | a new phone's `notification_auto_cancel` = 0 | `device_api::a_new_device_starts_with_all_kid_settings_on` |
| R10 | `/ping` added to `build_device_router` | **survives** the whole suite (1105 green; Low 6a) |
| R11 | Name only -> Full text writes `show_text = 0` | `each_move_of_the_select_…`, `the_page_lists_the_allowed_apps_with_their_ticks`, `an_apps_text_switch_saves_…` |
| R12 | `show_mark` without the `total` | `a_show_reads_its_episodes_only_when_the_newest_or_the_count_moves` |

## Verdict

**Mergeable after Medium 1.** The Spotify gates, the quiet cadence, the new-phone default and the lock select are
correct, documented and well pinned (every Rust mutation but the listener's `/ping` caught). reconnect.js does what
the user asked and keeps the place, but its reload in place can resend a form after the parent's screen was off -
fix that (the marker plus the `replaceState` belt) before the round merges; Lows 2-5 are small changes in the same
file and worth the same round, Low 8 is a test-only fix.
