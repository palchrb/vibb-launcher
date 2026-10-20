# Design QA - server round 5 (designs 43, 44, 45, 47 and the small items)

Reviewed against main at c0cc007c (design 47 at 7ccedb7c, the revision that moves the setup into Music › Settings):
`docs/design/43-pwa-app-shell.md`, `44-locate-history.md`, `45-notifications-inbox-first.md`,
`r5-server-small-items.md`, `47-spotify-add-first.md`, with the PLAN entries they come from and the code they name.
Reading only, no suites run. Severity: High = would ship a bug or break a standing rule; Medium = a wrong or missing
decision the implementer can't settle alone; Low = wording, a test, a small gap.

## Design 43 - the app shell

**43-1 High - `main` is `.container`, so the scroller is the 720 px column.** Every tab page has
`<main class="container">` (e.g. `templates/devices_list.html:9`). `.container` has `max-width: 720px; margin: 0 auto`
(`static/style.css:207-218`). With `main` as the scroller: (a) on the Pi's Chromium and any window wider than 720 px the
scrollbar sits at the column's edge, and the wheel does nothing over the grey margins; (b) in a column flexbox, auto side
margins switch off `stretch`, so `main` shrinks to its content's width and the column width changes from page to page.
This is the "content column steady" check (§5.6) failing by design. Fix: `main` takes the full width (`width: 100%;
max-width: none; margin: 0`) and the column comes from its padding (`max(16px, env(...), calc((100% - 720px) / 2))`,
480 px for `.container-narrow`), or an inner wrapper. Choose one in the brief, and check `.editor-bar`'s negative
margins (`style.css:1923-1925`) against the new padding.

**43-2 High - `touch-action: manipulation` on `body` doesn't reach the rows.** Under Pointer Events, the touch-action
intersection stops at the nearest ancestor with a default touch behaviour, which is a scroll container. WebKit and
Chromium both reset the inherited restriction at a scroller, so once `main` scrolls, the rows inside it get `auto`
again and keep double-tap zoom. Only the tab bar (outside `main`) would get the fix. PLAN's entry also put it on the
elements (`.tab`, `.row`, buttons, links), not on body. Fix: set it on `main` itself (it is the scroller), on `.tab-bar`,
and on the music cards' horizontal scrollers. Keep Leaflet's own `touch-action` rules winning: no `*` selector. The
`tests::shell` assertion should check `main` and `.tab-bar`, not `body`. Device check: a quick double tap on a row deep
in a long page, not only on a tab.

**43-3 High - `find()` adds `win.scrollY`; the proposal says it is "unchanged".** `static/scroll-restore.js:139` builds
the fragment target's document top as `getBoundingClientRect().top + win.scrollY`. In the shell `win.scrollY` is 0, so
the form record's `#fragment` path (`restoreTarget` → `found.top - tops[id]`, line 63) works out a y relative to wherever
the browser left `main`. The result is a save that jumps, which breaks the standing rule. Fix: `find` uses
`rect.top - scroller.element.getBoundingClientRect().top + scroller.top()` (0 for the window). The jstests run the
fragment cases against a scrolled `main`.

**43-4 Medium - the scroller can't be found when the script runs.** `scroll-restore.js` is loaded un-deferred from the
head (`partials/head.html`), before `<main>` exists. A `scrollerOf(win)` resolved in `install` always falls back to the
window. Fix: resolve it lazily in `remember`, `find` and `restore` (each call), and say so in §2.1. The jstest should
install before `main` is in the fake document.

**43-5 Medium - `.auth-page` is the `body`, not a wrapper.** `login.html:6` etc.: `<body class="auth-page">`. Body's
`overflow` propagates to the viewport (html stays `visible`), so `.auth-page { overflow-y: auto }` just keeps the
document scrolling, and `scrollerOf` returning the `.auth-page` element would read and set a `scrollTop` that never
moves. Also, with the new `body { height: 100% }` and `.auth-page`'s centring, a card taller than the viewport overflows
above the top, out of reach (the keyboard case in §1.4). Fix: the auth pages keep today's document scroll
(`body.auth-page { height: auto; overflow: visible; min-height: 100vh; min-height: 100dvh }`) and `scrollerOf` returns
the window for them. `music_spotify_done.html` is not an auth page (plain `body`, `main.container`, no bar); drop it
from §1.4.

**43-6 Medium - the body must be exactly the viewport's height, and iOS can still offset the window.** §1.1 gives body
`height: 100%` plus `min-height: 100dvh`. If `100dvh` is larger than the stale `100%` after a resume, the body grows past
the viewport, and a viewport with `overflow: hidden` can still be scrolled by a fragment, by `scrollIntoView` and by the
iOS keyboard's pan. The bar then sits off screen or offset, which is the same symptom in a new shape. Fix: `body {
height: 100vh; height: 100dvh }` (no `min-height`; the vh fallback also satisfies `every_dvh_follows_a_vh_fallback`,
which a `100%` fallback before `dvh` fails), plus a small guard in `scroll-restore.js`: on `visualViewport` `resize`,
`focusout` and `pageshow`, `if (win.scrollY) win.scrollTo(0, 0)`. That is the known fix for iOS leaving an
`overflow: hidden` page offset after the keyboard closes. Device check: the keyboard up and down on Time rules, then
scroll; and after 10+ min in the background.

**43-7 Medium - on Back, the fragment shouldn't beat the place record.** After any save the address carries the group's
fragment (`/devices/3/home#switches`). §2.1 lets "a `#fragment` on a Back/reload load win", so Back to any page that was
saved once lands at the group, not where the parent was: a jump on Back, and device check 4 fails. Fix: on
`back_forward`/`reload` the place record wins over the fragment, and the fragment wins only on a `navigate` load. The
form record keeps its own fragment rules.

**43-8 Low - the navigation type on the floor browser.** iOS 14 has no `PerformanceNavigationTiming`. Fall back to
`performance.navigation.type` (1 = reload, 2 = back_forward), and test both.

**43-9 Low - the place records grow without a bound.** One sessionStorage key per path+search, never removed. Keep one
`handy-place` map capped at about 50 entries, oldest dropped.

**43-10 Low - test fallout not listed.** `src/tests/shell.rs:169` asserts the `tab-bar.js` script tag. That test changes
(the tag must be absent). `style.css`, `scroll-restore.js`, `auto-reload.js` and `install-now.js` need `?v=` bumps
(`head.html`, `device_apps.html:82`, `device_calls.html:161`, `tracked_app_detail.html:90`).
`-webkit-overflow-scrolling: touch` does nothing since iOS 13: drop it.

**43-11 Low - `order: 1` keeps the bar first in reading order.** That is true today too, so no regression, but §1.2's
"the bar was visually last already" is the reason it stays odd for a screen reader. Not this round's problem; the
wording is fine as "unchanged".

Browser facts checked: an inner scroller follows a `#fragment` on first load (yes, every scrollable ancestor). Sticky
inside the scroller is fine: `.editor-bar` sticks to `main`'s scrollport, which starts at the viewport top, so
`top: env(safe-area-inset-top)` still clears the status bar. Leaflet inside a scroller is fine: it reads
`getBoundingClientRect` per event, and the map's own `touch-action` keeps a drag on the map. bfcache with `persisted`
restoring nothing is right. Note that the no-store pages (`/notifications`, `admin_auth.rs:472-486`) never come from
the bfcache, so they always take the place-record path.

## Design 44 - Locate history

**44-1 High - the new list page isn't no-store.** `admin_auth::no_store_path` (`src/admin_auth.rs:472-486`; the
proposal calls it `never_stored`) lists `locations.json` because live locations are never stored. A
`/devices/{id}/locations` HTML page holding every kept fix would get `private, no-cache` and stay in the browser's
cache. Fix: add `["devices", _, "locations"]` and put it in `no_store_paths`.

**44-2 Medium - the override commands aren't on the Lock page.** "Lock again", "Unlock for 2 h…" and "Allow these
apps…" are pressed on the phone's summary (`templates/device_detail.html:153, 160, 238`; `#quick`, `#blocked-apps`).
That page already shows each command's newest answer (`RelockCard.answers`, `handlers/window_relock.rs`).
`device_lock.html` is PINs and the lock screen, with nothing that queues those commands. PLAN says "on the Lock page",
but a Recent there would sit away from its buttons and repeat the summary's answers. The user or the coordinator
decides: the summary (a `#recent` under `#quick`, `back=summary` → `/devices/{id}#recent`) or the Lock page as written.

**44-3 Medium - the map script is inline, and the design needs it in a file.** `device_locate.html:181-264` is an inline
`<script>` with Askama values. `jstest/locate.test.js` can't load it, and the list page needs the same map. Fix: move it
to `static/locate.js` (ES5, `?v=`), with the device id, `shown` and the popup text in `data-` attributes (textContent
rules unchanged; `js_string` no longer needed). Both pages load it. The waiting poll moves with it.

**44-4 Medium - a tap on a list row pans a map that is off screen.** On the list page the map is at the top. A row 50
fixes down pans a map the parent can't see. Decide one: the map sticky at the top of the list page (inside the new
scroller, 43), or the tap also scrolls the map into view. The no-jump rule covers saves, not this, but write the choice
down.

**44-5 Low - the cancel handler takes no form.** `cancel_command` (`handlers/locate.rs:681`) has only `Path`. `back=`
needs a `Form<HashMap<String, String>>`, a hidden input in the shared partial, and a whitelist (`locate`, plus the page
44-2 settles on).

**44-6 Low - the paging key doesn't match the order.** Rows sort by `captured_at DESC, id DESC`, and fixes can arrive
out of order (a backlog after offline). `?before=<id>` can skip or repeat rows. Page on `(captured_at, id)`
(`before=<captured_at>,<id>`) or order by `id`.

**44-7 Low - many dots with "All kept".** 30 days at a 10-minute interval is about 4,300 `circleMarker`s in SVG, which is
slow on an iPhone. Use `L.map(..., { preferCanvas: true })` (or a canvas renderer for the dots).

**44-8 Low - the span pick's markup.** Links with `?span=` plus `role="radio"` is one control with two semantics. Use
`<a role="radio" aria-checked>` with JS calling `preventDefault`, or buttons inside a GET form; the test asserts
whichever you pick. The JSON's `newest: true` point must not count in the row's span count.

**44-9 Low - test names.** It's `admin_auth::tests::no_store_paths`; Locate's tests live in `src/tests/` (check the
existing file name). Add: the list page is no-store, and Cancel from Locate still lands on
`/devices/locate?device={id}&cancel=…#recent`.

Scope: matches the PLAN entry. Install Now: `install_now::install_row` (`handlers/install_now.rs:110-131`) already shows
the command's row (`command_row`) on the launcher's app page, so §1.2's fallback isn't needed.

## Design 45 - inbox first

**45-1 High - the inbox page has no `[data-unread]`, so the app badge doesn't clear.** `register-sw.js:25-29` sets the
badge only where the page has `[data-unread]`. Only the bell carries it (`partials/bell.html:3`), and the bell is on the
five tab pages only. `notifications.html` has none (and §1.3's "the page's own bell" doesn't exist). The user asked for
the counter to reset when the inbox opens, so after this design the iPhone badge stays until the next tab page. Fix:
the inbox renders `data-unread="{{ unread_after }}"` (on the gear link or on `main`). The test asserts the attribute
carries the post-mark count, and §1.4's text gets corrected.

**45-2 Medium - the unread count after marking.** `read_page` counts `unread` before any marking
(`handlers/notifications.rs:314-319`), and "Mark All as Read" shows on `unread > 0`. The brief must say: read the page,
mark the shown ids, then count. The rows keep their pre-mark `e.unread` for the dot. Reuse the existing
`unread-dot` + "Unread:" text (`notifications.html:93`) and don't add a `row-new` dot.

**45-3 Low - wording and docs.** Events aren't per admin (`parent_events` has no `admin_id`, migration 0078). "Only
writes the admin's own events' `read_at`" is wrong: every parent's inbox shares the read state, as Mark All as Read does
today. Say so. The module doc "No GET changes state" (`notifications.rs:8`) changes. The admin cookie is
SameSite=Strict (`music_spotify.rs` §done's comment), so a cross-site GET can't mark rows read; record that as the
reason a GET may write here.

**45-4 Low - links.** The Devices warning is a template href (`devices_list.html:36`), not `warning_row`'s output. The
inbox's attention rows today link to `#devices` on the same page (`notifications.html:23`) and must become
`/notifications/settings{query}#devices`. The Settings hub row (`settings_hub.html:16`) exists. Register the route as
one `.route("/notifications/settings", get(settings_page).post(save_settings))`. On the settings page the
`device` hidden inputs (the inbox filter) mean nothing: keep them for the query round-trip or drop them, but decide.

**45-5 Low - Back to the inbox.** `/notifications` is no-store, so Back reloads it and the dots are gone (the rows are
read now). That's fine, but write it down so nobody "fixes" it.

## Server round 5 - the small items

**r5-1 Medium - where the `?push=` layer goes.** `require_full_auth` is a `.layer` on `admin_routes`
(`src/main.rs:1298-1301`). It inserts `CurrentAdmin`, and an axum layer added later runs earlier. The marker layer must be
added *before* that `.layer(require_full_auth)` call (so it runs inside it and sees `CurrentAdmin`), not after it in
code order. Also: if signed out, `require_full_auth` redirects to `/login` without the URL (`security.rs:414-417`), so
the marker is lost. That is acceptable ("a missing tap changes nothing"); say so. Consider a 303 to the same URL without
`push`: Back and reload stop re-marking, and the fragment survives a redirect without a fragment.

**r5-2 Medium - "since opened" should be "since the last tap or receipt".** PLAN (Hotfixes entry, "Next server round")
says "delivered pushes after the device's last tap **or receipt**". §2 uses `clicked_at` only, so a Chromium device that
confirms every push would show a growing "N since one was opened". Also, deliveries are pruned after 30 days
(migration 0078), so `MAX(clicked_at)` vanishes and the line says "not opened yet" for a device that was opened. Fix: the
basis is `max(last_receipt_at, newest clicked_at)`. For the pruning, either accept it in the words ("· 7 in the last 30
days") or add `push_subscriptions.last_opened_at` in 0082 (the marker and the click receipt both set it).

**r5-3 Medium - the `Why::Art` rule as written changes nothing.** With `failures > 0`, `regular` already is the backoff
time (`music_sweep.rs:509-520`), so "due at the backoff, never later than regular" equals today's `Why::Interval` at
`regular`. The PLAN item (PLAN.md:918-920) is "one bad episode URL shouldn't hold every picture for 6 h". Find out
first whether a single picture's failure raises the listing's `failures` at all. If it doesn't, the rule is a no-op and
the real hold is elsewhere (`art_due`'s one-day retry, `ART_MAX_FAILS`). Re-decide the rule with that answer; the three
tests in §6 are vacuous as written.

**r5-4 Low - 0082's condition flags too much.** `art_state IS NULL` is also true of every item without an `art_url` and
of `gone` items, so nearly every listing gets flagged. Match `art_candidate`/`art_due` (`music_sweep.rs:3210-3263`):
`i.state = 'ok' AND i.art_url IS NOT NULL AND (i.art_from IS NULL OR i.art_from <> i.art_url)`. The join is
`music_items.entry_id = music_listings.entry_id` (0051). `art_more` is a count (0081); setting 1 is fine as "some
left". The migration test also needs a listing whose only untried item has no `art_url`, which must stay unflagged.

**r5-5 Low - inserting the marker.** No stored target has a `?` today (`parent_events.rs:179-315`), but insert `&push=`
when one does. The marker for a summary or test (no `event_id`) sets `clicked_at` only.

**r5-6 Low - `subscriptions.json` is already no-store.** `["notifications", ..]` (`admin_auth.rs:484`) covers it, so
nothing needs adding. Test it in `no_store_paths`. The poll needs `notifications.js` on the settings page (45 §1.7);
today it loads only with a push key (`notifications.html:193`), which is fine since there's nothing to test without
one.

**r5-7 Low - the stream log test.** Put the age on the guard (`opened: Instant`, `fn age()`) and unit-test that; no
tracing-capture crate is needed. `close_all` at shutdown logs one "closed" line per stream, which is expected.

## Design 47 - Spotify: add first, setup in Music › Settings

**47-1 High - the login form needs the widened CSP on its new page.** Only the Spotify page's `render` sets
`page_csp` (`handlers/music_spotify.rs:170-205`): ADMIN_CSP with `form-action` widened to Spotify's accounts origin,
because the browser checks `form-action` on the 303 that `login` answers with. On Music › Settings
(`handlers/music.rs:542`, `render_settings`) the page carries the plain ADMIN_CSP (`admin_auth.rs:462`), so "Log in with
Spotify" is blocked. Fix: `render_settings` sets `page_csp` (it's harmless for the other groups). Add a test that
`/music/settings`'s CSP names the accounts origin.

**47-2 Medium - refusals re-render; they don't redirect.** `save_app`, `clear_app`, `login` and `paste` answer a refusal
by rendering the page in place with `Entered` (values, the error field, `autofocus`, `login_elsewhere`; e.g.
`music_spotify.rs:706-746`). §1.2 only covers the redirects. `render_settings` today takes the Storytel error and notice
only (`music.rs:542-548`), and it needs the request's `HeaderMap` and the peer's trust for the redirect-URI and host
checks (`trusted`, `request_host`). The brief names the new signature, e.g. `render_settings(state, headers, trusted,
status, storytel, spotify: Entered)`, and `login_elsewhere` becomes `https://{target}/music/settings#spotify-accounts`.

**47-3 Medium - the id list is incomplete, and one id clashes.** Today's setup markup also uses `#key`, `#redirect-uri`,
`#app-form`, `#setup`, `#app-clear` (`clear_app`'s form lands there, not `#app`), `#account-{id}` (`remove`'s form
action), `#login`, and the field ids `client_id`, `redirect_uri`, `paste_url` (`music_spotify.html:20-147`).
`music_settings.html:89` already has `id="app"` (the music app group; `music.rs:102, 126` redirect to `#app`). Prefix
every one (`#spotify-key`, `#spotify-app-clear`, `#spotify-account-{id}`, …), each form's action fragment with it, and
each handler's redirect. `every_form_on_every_page_lands_on_its_own_group` then checks them.

**47-4 Low - the not-set-up states.** The add POST also refuses on `SpotifyError::Reauthorize` (an account to log in
again) and when the server has no music key (`#key`). Give the attention row those two wordings too, or say they stay as
the add form's refusal.

**47-5 Low - §1.4 is moot.** The Music tab's Settings row (`music.html:127`) shows no value today. Drop the Storytel or
Spotify mention.

**47-6 Low - the query on Music › Settings.** `show_settings` reads a session flash for Storytel (`music.rs:527-533`).
The Spotify notices come by `?login=|saved=|cleared=|removed=`, so make sure nothing else on that page reads those keys,
and `done`'s `target` (`music_spotify.rs:860`) moves to `/music/settings?login={code}#spotify-accounts`.

## Interactions

- 45's settings page includes the tab bar like the inbox (`tab = ""`), and 43's `order: 1` covers it. The gear
  replaces nothing in the bar; no clash.
- 45's read-on-open and r5's marker: a summary's `navigate` is `/notifications?push=N`. The layer sets `clicked_at`
  (no event), then the page marks the shown rows. Consistent. The marker isn't in `page_query`, so Newer/Older drop it.
- 43's scroller and 44: Leaflet is fine inside `main` (above). 44's span pick is a view change, not a save, so it needs
  no scroll record. 44-4's list pan should use `main`'s scroll if it scrolls at all.
- The Notify Me About POST and the new GET share `/notifications/settings`: register them as one route (45-4). The
  POST's redirect goes to `/notifications/settings{query}#events`.
- 47 and 43: Music › Settings gets longer; its forms' fragments are covered by 43-3's fix.

## Verdicts

- **43: needs a revision.** 43-1, 43-2 and 43-3 change the shell's CSS and the restore math; 43-4 to 43-7 follow from
  them. The revision can be short.
- **44: approved with the Medium+ fixes folded into the brief** (44-1, 44-3, 44-4). 44-2 (summary or Lock page) needs
  the user's or the coordinator's answer before the brief.
- **45: approved with the Medium+ fixes folded into the brief** (45-1, 45-2).
- **r5 small items: approved with the Medium+ fixes folded into the brief** (r5-1, r5-2). §4's art rule (r5-3) is
  re-decided first; migration 0082 can go ahead with r5-4's condition.
- **47: approved with the Medium+ fixes folded into the brief** (47-1, 47-2, 47-3).
