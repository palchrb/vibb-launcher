# Code QA - server round 5 part B (designs 45, r5 small items, 47)

- Commit reviewed: `dee71b54` (branch `r5-b`; four commits on `d34a3485`: 18b0958a, 7ed241f9, a4c1d897, dee71b54).
- Ran: full `cargo test` once: **1081 passed, 0 failed** (279 s). `node --test server/jstest/`: **65 passed**.
  `cargo fmt --check`: clean. `cargo clippy --all-targets`: the 13 known warnings (models.rs, dns_engine.rs,
  devices.rs:1305, storytel.rs:97). None is in a touched file.
- Then 13 one-line mutations, each run against the tests that should catch it and reverted afterwards (table below).
  11 were caught. The 2 not caught are both nudges (finding 3).
- Read: server/CLAUDE.md core; designs 45, r5, 47 with their implementation status; qa-r5-design.md (45, r5, 47,
  Interactions); the full diff of the handlers, push.rs, streams.rs, device_api.rs, main.rs, admin_auth.rs, 0082, the
  templates and partial, notifications.js, the tests, jstest and the docs.

## Design QA findings: did each one land?

All of them landed. 45-1: the gear carries `data-unread`. 45-2: the count is taken after marking (mutation c is
caught). 45-3: the module doc is rewritten, and it gives SameSite=Strict as the reason the GETs may write. 45-4: one
route `get(settings_page).post(save_settings)`, the links go to the settings page, the `device` hidden inputs are kept.
45-5: documented. r5-1: the layer is added before `.layer(require_full_auth)` (main.rs:1316-1323) and answers a 303
without `push`. r5-2: the basis is `max(last_receipt_at, last_opened_at)`, and 0082 adds `last_opened_at`. r5-3:
dropped. r5-4: `art_candidate`'s condition (mutation i is caught). r5-5: `&push=` after an existing `?`; a summary or
test gets no event write. r5-6: `no_store_paths` lists both paths. r5-7: `opened`/`age()` on the guard, unit-tested.
47-1: `render_settings` sets `page_csp` (mutation f is caught). 47-2: the new signature; refusals re-render with
`Entered`. 47-3: every id is prefixed, error ids included, and a test checks for no leftover unprefixed id. 47-4: the
Reauthorize and no-key attention rows. 47-6: `done` leads to `/music/settings?login={code}#spotify-accounts`.

## The standing rules

1. **No jump.** Every subscription, test, remove and Notify Me About POST goes through `back_to`/`back_to_sub`, or
   to `save_settings` → `/notifications/settings{query}…#this-device|#devices|#events`. `open` and `read-all` go back
   to the inbox. Mutation e (back to `/notifications#events`) is caught by `the_switches_are_saved_and_events_still_stored`.
   The poll only sets `textContent`/`className` on `[data-sub]`/`[data-badge]` (jstest). Music: `save_app`,
   `clear_app`, `paste` and `remove_account` → `/music/settings?…#spotify-…`; plays-on → `#spotify-account-{id}`;
   storytel/phones → `?storytel=phones#storytel-phones`. All are pinned by the redirect table
   (`every_form_on_the_music_pages_returns_to_its_page`) and by their own tests. **Exception: the login refusal, finding 1.**
2. **The `?push=` marker.** It is GET only (mutation k is caught: "A POST is never redirected"). It runs inside
   `require_full_auth`: signed out goes to `/login` and nothing is marked (tested). Only this admin's delivery is
   marked (`s.admin_id = ?` in `push::opened`; mutation a is caught). A foreign id or garbage gets the same 303 with
   the other keys kept in order (tested). The writes are `clicked_at` with COALESCE, `last_opened_at`, and the event's
   `read_at` with COALESCE (mutation b is caught). The layer cannot become an open redirect: the Location is the
   request's own path, and no admin route matches a `//host` path.
3. **Read-on-open.** Only the shown ids are marked (`json_each` of `shown`; mutation d is caught). The count comes
   after (mutation c is caught). A `?device` filter leaves the other phone unread, and older pages mark their own rows
   (`an_empty_inbox_and_older_pages`). The gear's `data-unread` = every unread event left. **Right call:** §1.3 says
   the badge drops "to what is not shown yet (older pages, another phone under a filter)". That is the global count.
   A filtered `unread_after` would hide the other phone's unread events from the Home Screen badge. Unfiltered, the
   two numbers are the same, so the badge reaches 0 when the inbox is opened. The design's literal
   `data-unread="{{ unread_after }}"` contradicts its own sentence; the implementer followed the sentence.
4. **No secret on a page.** `SpotifySetup` carries the client id's last four characters / the entered value, the
   redirect URI, and each account's name, line, warning and plays-on. It has no token, ciphertext, nonce or
   fingerprint field. `subscriptions.json` is `id`, `badge`, `badge_class`, `sub` (exact-shape test; mutation j is caught).
5. **CSP.** `render_settings` applies `music_spotify::page_csp`, which only rewrites `form-action 'self'`. The add page
   at `/music/spotify` no longer widens anything (`render_add` sets no CSP), so the net change is narrower.
6. **The narrow handlers.** plays-on: binds the phone, moves it from another account, unbinds the account's other
   phones; "none" unbinds all; an unknown phone, unknown account, garbage or a missing field is a 400 with nothing
   written (mutation h is caught); it runs in `BEGIN IMMEDIATE`, through the shared `bind_spotify`, with the same
   `music_spotify_binding_changed` event. storytel/phones: `music_storytel` alone; the mobile-data switches, volume
   cap and storage limit are asserted untouched (mutation g is caught); unknown phone → 400; 0072's trigger bumps the
   revision. Both nudge, but no test checks it (finding 3).
7. **0082.** The condition is `state = 'ok' AND art_url IS NOT NULL AND (art_from IS NULL OR art_from <> art_url)`,
   joined on `entry_id`. A listing whose only untried item has no `art_url`, or is gone, stays unflagged (mutation i is
   caught). It adds `last_opened_at`. CLAUDE.md says the next free number is 0083, and `migrations_73_to_82_in_order`
   is renamed everywhere.
8. **Stream lines.** "command stream opened" logs `device`. `StreamGuard::drop` logs "closed" with `device` and
   `secs`, once per guard.
9. **Delivery words.** The order is Opened > Shown > Delivered > Pushed…not confirmed. With several deliveries:
   "· opened on N", else "· shown on N" (`the_delivery_line`). The three cases of the count words are tested
   (`the_device_row_counts_pushes_since_one_was_opened`).

## Findings

**1. Medium - a refused "Log in with Spotify" lands at the top of Music › Settings.**
`server/templates/partials/spotify_setup.html:140` (`action="/music/spotify/login"`, no fragment) and
`server/src/handlers/music_spotify.rs:789-850` (`login`'s refusals → `render_setup`).

Why it matters: when `login` refuses, it renders Music › Settings as the POST's answer. The refusals are: no key, the
app not ready, and "Spotify sends this login back to https://{target}". The last one is the usual case for a parent on
the LAN address. The answer has no fragment, no `autofocus`, and a different path, so `scroll-restore.js` restores
nothing ("another path restores nothing"). The page opens at the top, and the error sits below New episodes and
Storytel, out of sight. On the old page, Children's Accounts was the second group. Now it is the fourth, on a long page.
`every_form_on_the_music_pages_returns_to_its_page` has no `refusal_lands_at` row for `/music/spotify/login`, so the
test doesn't see it.

Fix: put `autofocus` on the Log in button (or the paste field) when `login_error` is set, and pin it in
`the_refusals_render_music_settings_with_prefixed_ids`. Focusing scrolls the group into view. Adding
`#spotify-login` to the action instead also works, but the browser then carries the fragment onto the 303 to
Spotify's authorize URL.

**2. Low - the Settings hub's "N unread" now leads to the settings page.** `server/templates/settings_hub.html:16-19`.
The row's value counts inbox events, but the row opens Notification Settings, where there are no events. The
Settings tab page already has the bell with the same count, top right. Fix: drop the `row-value` (design 45 §1.6 sends
the row to the settings page; the bell is the way to the inbox), and assert in `the_settings_page_holds_the_three_groups`
that the hub row has no "unread".

**3. Low - the two new handlers' nudges are untested.** `server/src/handlers/music.rs:4755` and
`server/src/handlers/music_spotify.rs:1173`. Removing either `nudge` passes every test (mutations m, n). The revision
trigger still moves, so a phone would sync on its next poll instead of at once. Fix: in
`plays_on_binds_one_phone_per_account` and `storytel_on_a_phone_from_music_settings`, subscribe to
`app.state.command_notify` before the POST and assert the changed phones' ids (as `tests/calls.rs:551` does).

**4. Nit - 0082 ignores `art_candidate`'s `playable`.**
`server/migrations/0082_art_backfill_and_last_opened.sql:8-12`. An NRK/RSS item with an `art_url` but no `url` is
not an art candidate, yet it flags its listing. The cost is one extra sweep, after which `art_more` is recomputed. The
SQL is the decided one (r5-4), so this only needs a comment, and only if the file is touched again. Don't edit an
applied migration.

**5. Nit - the `?storytel=phones` query.** This is the right call. It follows the existing `?open=<id>` pattern of
`/music/categories`. Without it, the `details` row the parent just toggled would fold away. The fragment is still
there, and `scroll-restore.js` keys on the path, so there is no jump. The query stays in the address after the save,
so a reload keeps the row open. That is harmless.

## Deviations judged

- 45: the gear's `data-unread` is the total (right call, rule 3). The gear reuses `title-action title-bell`, so no new
  CSS (fine). The full clock warning is on the settings page and a one-line row on the inbox (as §1.6). The Send a
  Test `navigate` goes to `/notifications/settings#devices` (needed). The shell.rs exemption: **still meaningful**.
  It names exactly the two tab-less pages, every other template with the bar must still set one of the five tabs, and
  keeping it in its own commit eases the merge with design 43.
- r5: the count words only on the Ok and May-not-be-showing rows (fine; the other states' sub-line is the actionable
  one). The poll finds its row from `?notice=test_sent&sub=N` and `data-sub`/`data-badge` (fine, jstested).
- 47: `disabled` on each field instead of `fieldset disabled` (right: there is no fieldset reset in style.css). The
  `?storytel=phones` query (finding 5). The intro moved into the ⓘ (fine). Device › Music's "Spotify Accounts ›" now
  leads to `#spotify-accounts` (needed: the old `#accounts` is gone). "Plays on" lists every phone with a policy row
  (as the add form does; fine).
- Scope: nothing beyond the three notes. The only changes outside them are the docs and the one href that the move
  made necessary.

## Mutation table

| # | Mutation (one line, reverted) | Result |
|---|---|---|
| a | `push::opened` without `s.admin_id = ?` (any admin's delivery) | caught by `web_push::a_tap_on_a_notification_marks_it_opened` |
| b | `mark_opened` skips the event's `read_at` | caught by `a_tap_on_a_notification_marks_it_opened`, `web_push::the_receipt` |
| c | inbox counts unread before marking | caught by `notifications::opening_the_inbox_reads_the_rows_it_shows`, `an_empty_inbox_and_older_pages` |
| d | the `?device` filter marks every phone's rows (UPDATE without the id list) | caught by the same two |
| e | `save_settings` → `/notifications{}#events` | caught by `notifications::the_switches_are_saved_and_events_still_stored` |
| f | `render_settings` carries plain `ADMIN_CSP` | caught by `settings_group::the_add_page_first_and_the_setup_in_music_settings`, `not_configured_shows_the_steps`, `the_app_setting` |
| g | storytel/phones also writes `music_volume_cap_pct = NULL` | caught by `settings_group::storytel_on_a_phone_from_music_settings` |
| h | plays-on with an unknown phone continues (unbinds, commits) | caught by `settings_group::plays_on_binds_one_phone_per_account` |
| i | 0082 without `art_url IS NOT NULL` | caught by `music_art::migration_0082_flags_entries_with_untried_pictures` |
| j | `subscriptions.json` gains an `endpoint` field | caught by `web_push::the_device_rows_as_json` |
| k | the marker layer also on POST | caught by `a_tap_on_a_notification_marks_it_opened` |
| m | storytel/phones without `nudge` | **NOT caught** (finding 3) |
| n | plays-on without `nudge` | **NOT caught** (finding 3) |

## Verdict

**Merge after the listed fixes:** finding 1 (Medium: autofocus on the login refusal, with its assertion). Findings 2
and 3 are small and can ride along. The nits need nothing now. The guards (marker ownership, read-on-open scope, the
narrow handlers' "nothing written", the CSP, no secret in the JSON) are all pinned by tests that fail when the guard is
removed.
