# Security code QA: `push-never-silent` (fbc79364 on 5a520fd5)

Reviewed 2026-11-04 by reading `git diff 5a520fd5..fbc79364` and the tree around it (server only: Rust, templates,
static JS, migration 0128); cargo and node not run (implementer: cargo 1718/0, node 319/0). Measured against
security review M1's intent: a lost or stolen device, or a signed-out browser, stops getting notification titles, and
nothing turns pushes on again without a valid, fully authenticated, unlocked session.

## Result

**0 Critical, 0 High, 1 Medium, 6 Low, 8 Info.**

M1's core holds. The new sync route is behind `require_full_auth`, the app lock and the global `csrf_guard`. A
signed-out, half-signed-in (password without its code), must-change or old-epoch session gets a redirect (and its
session, the stored endpoint hash with it, is flushed). A locked one gets a 423. The `keep` hash is written only by
the server, only into the session that stored or found its own row, and only a session that goes on through the
change uses it. The app-lock refusals that no longer count are the server's own: the challenge is taken before any
credential or signature check, so there is no guessing gain. One real gap remains: a parent's deliberate Off is tied
to one endpoint (server) or to localStorage (browser), so a new endpoint brings the device back without a tap
(Medium). **Verdict: fix M-1 and L-1 (both small) before merge.** The Lows are cheap hardening and the merge chores
(L-5).

## Medium

### M-1 A deliberate Off is bypassed by a new endpoint: Remove, Turn Off and the 180-day expiry

`handlers/notifications.rs:1171` (`endpoint.is_empty()` -> `none`), `:1215` (`removed` is looked up by the posted
endpoint only), `static/register-sw.js:226` (`none` -> `renew`, a silent subscribe), `retention.rs:437`.

`push_removed` remembers the *endpoint's* hash, and `vibb.push-off` lives in localStorage. The sync answers `none`
for a request without a subscription, whatever this session or browser did before, and `pushSync` then subscribes
again without a tap whenever the permission is still `granted` (Turn Off and Remove never revoke it).
- **Remove from another device.** Mamma removes the old iPad (the kid uses it, still signed in) under Getting
  Notifications so titles stop showing there. The iPad's browser later drops or rotates its subscription: iOS losing it
  is exactly the bug this branch is about, and so are a `pushsubscriptionchange` or a re-added Home Screen app. The next
  page load posts nothing, gets `none`, subscribes with a new endpoint and posts it, and the answer is `stored`. Titles
  show on the iPad again with no tap. The only trace is a `push_subscribed` "Sent again by the app" line.
- **Turn Off on the device itself.** Turn Off unsubscribes the browser, so only `vibb.push-off` guards it. If
  localStorage is cleared while the session cookie lives (Safari's 7-day cap on script-writable storage for a site used
  in a browser tab, a partial "clear site data", a storage-partitioning change), the next page subscribes silently.
- **The 180-day expiry.** `push_removed` is pruned after `PUSH_GONE_DAYS`. Any sync after that (a session older than
  that, with `SESSION_MAX_DAYS` up to 3650, or a new sign-in on the removed device) brings the same endpoint back
  silently.

This does not break M1 (each needs a valid, unlocked session), but it breaks "explicit Off remembered", and it does so
for the unattended case (a shared or handed-down device), where nobody is there to tap.

**Fix:** bind the Off to the session, which the server already identifies (`SESSION_ENDPOINT_KEY`):
- In `sync`, answer `removed` when `push::this_browser(&session)` is in `push_removed` and the request either has no
  endpoint or has a different one, unless `asked=1`. The banner's Turn On and This Device's form still lift it,
  because `subscribed()` writes the new hash.
- Turn Off already notes the hash. Remove notes the removed row's hash, which is the target session's
  `SESSION_ENDPOINT_KEY` when that session stored or found it, so the check above covers Remove too.
- Either don't prune a `push_removed` row while a session could still name it, or note in the docs that a Remove
  expires after 180 days.
- Add a test: Remove, then a sync with no endpoint (or a new one) from the removed session answers `removed`, and
  `asked=1` stores.

## Low

### L-1 A failed Turn Off leaves `vibb.push-off` set, so a later loss is silent again

`static/notifications.js:427-433`. `markOff(win, true)` runs before `current.unsubscribe()`. When the unsubscribe
fails (the `off-failed` branch), the switch stays On and the server keeps the row, but the flag stays set. This
browser's `pushSync` and `reconcile` then never send it again by themselves, and no banner shows. The next time the
server loses the row (an account change elsewhere, a re-auth lockout), the loss is as silent as before this branch.
**Fix:** `markOff(win, false)` in the failure branch (or set it only after the unsubscribe resolved, before the
submit).

### L-2 A sign-out that carries no endpoint still leaves the signed-out browser getting titles

`handlers/auth.rs:545-572`. Pre-existing, but the branch now has what fixes it. `/logout` deletes this browser's row
only when the page posted `endpoint` (`register-sw.js`'s `signOut`, within `SIGN_OUT_WAIT`). Without it (JS not run,
the wait ran out, a slow `getSubscription`), the row stays active in the account's current epoch, and the signed-out
browser keeps getting titles. That is M1's case. **Fix (hardening):** before `session.flush()`, fall back to
`push::this_browser(&session)`: delete this admin's active row whose `endpoint_hash` matches, and log it the same
way.

### L-3 `keep_this_browser` trusts the hash without checking the row's epoch, and races `forget_stale`

`push.rs:526-551`. It moves *any* active row of the admin whose hash matches to the new epoch, including one from an
epoch older than the session's. This is unreachable today: the hash is written only for a row stored or found
current by this session (`subscribed`, the `known` arm). Still, the invariant lives two files away.
**Fix (defence in depth):** require the row's `session_epoch` to equal the session's epoch before the change (or
`CURRENT_EPOCH` evaluated before the bump).

There is also a race. A `send_due` pass between the change's epoch `UPDATE` and `keep_this_browser` deletes the row
as `Stale`, so nothing is kept. Account then says "every other device", and the row comes back at the next page's
sync. It heals itself; noted so nobody chases it.

### L-4 Account's "notifications are off on every other device" is wrong when no hash was recorded

`handlers/auth.rs:1366-1377` and its siblings. `keep` is `None` for a session that never stored or synced a row
(`known` is recorded only by the sync, and Notification Settings never posts a sync for a subscription it already
knows, while `pushSync` skips that page). In that case this browser's row is deleted too, `push_off` lists it, and
the flash says it was kept. The next page load stores it again, so the effect is a false line and a spurious event.
**Fix:** record the hash on Notification Settings too (a cheap `known` sync, or record it when the page renders a
matching `data-subscribed`), or word the flash from what `forget_in` actually kept.

### L-5 Merge chores: the migration-order test and server/CLAUDE.md

- `git merge-tree main push-never-silent` conflicts in `server/src/tests/music_spotify.rs` only: the doc comment and
  `migrations_from_73_in_order`'s list. Resolve to `(126, "weather no place")`, `(127, "lock media apps")`,
  `(128, "push removed")`, and merge both comment lines. With 0127 on main, 0128 runs after it, so there is no
  ordering hazard.
- In `server/CLAUDE.md:278` the migration paragraph still ends "The next free number is 0127". It should name 0127
  (main's lock media apps) and 0128 (this branch), and give 0129 as the next free number.

### L-6 Turn Off notes any posted endpoint, and logs another parent's row under this parent

`handlers/notifications.rs:1268`. `note_removed` runs for any non-empty `endpoint`, even when nothing was deleted.
Any signed-in session can therefore add unbounded `push_removed` rows (180 days each) with random endpoints. This is
authenticated-only DB growth. Also, when the deleted row was another parent's (shared device, L1 of design 35), the
note and the `push_unsubscribed` line carry the current parent. **Fix:** note and log only for the rows the `DELETE`
returned, with their owner's `admin_id` (`RETURNING admin_id, label`).

## Info

- **I-1 `keep` when the attacker is the current session: no limit needed.** A stolen full session that changes the
  password (or 2FA, a passkey, "Kun passkey") survives the change either way. Before this branch it lost its own row,
  but could Turn On again with one tap in a session the change left valid. Keeping it gives the attacker nothing new.
  Every other device, which is what M1 deletes for, still goes. It is also more visible now: `push_subscriptions_cleared`
  says "Kept on the device that made the change: {label}", and `push_off` is pushed to the other parents' devices.
  The one thing that defeats an attacker session, Sign Out Everywhere from the parent's device, keeps nothing
  (`sign_out_everywhere` passes `None`). The re-auth lockout and the CLI reset keep nothing either. Correct.
- **I-2 The sync's gate, checked.** It is mounted in `admin_routes` under `require_full_auth`
  (`main.rs:1614`, `:1695`). `load_active_admin` refuses a pending-2FA session (no `admin_id`), an epoch mismatch,
  a removed or expired account and an over-age session, and flushes the session, so the hash goes with it.
  `must_change_password` and missing 2FA get a redirect. The lock check returns 423. `csrf_guard` is global, and
  `store_subscription` also requires `Origin`. The `known` arm leaves another parent's current row alone. The stale
  arm (`CURRENT_EPOCH` false) re-assigns, which is right because the owner's session is gone. The answers carry the
  public key, the banner's words and a state, never labels or other devices. Tests cover signed out and locked, but
  not pending 2FA, old epoch or cross-site. Worth one test each, since the route is the new re-enable path.
- **I-3 Point 6 is genuinely the server's own.** `signed_in_assertion` takes the challenge (`passkeys.rs:996-1000`)
  before it looks at the credential, user handle or signature, so a `Challenge` refusal evaluates nothing.
  `Database` comes from a read error before verification (`:1003`, `:1012`) or from the use's store after a verified
  assertion (`:842-845`), neither of which an attacker steers. A garbage assertion fails `Assertion::parse` and still
  counts. A wrong, cloned or foreign passkey with a live challenge still counts. One side effect: the uncounted
  refusals are now unbounded per locked session, and each one writes an `app_unlock_failed` line (`handlers/lock.rs:211-218`), with no
  limiter on `POST /lock`. A stolen locked cookie can flood the security log (kept 180 days). Before, three lines
  ended the session. Consider coalescing the server's-own lines (one per session and minute with "×N", as
  `app_unlocked` does). That is Low-grade abuse; it needs the cookie.
- **I-4 `is_use` exclusion.** `Facts::is_use` is false only for the matched `PUSH_SYNC_ROUTE`. It can't hold the lock
  off, and a locked session is still checked (423). The banner's Turn On tap (also this route, `asked=1`) is
  therefore not a use either, which only means the lock can fall due a little earlier.
- **I-5 Request rate.** At most one sync per tab every 10 minutes (sessionStorage). It runs on every page only when
  sessionStorage throws. The lock and sign-in pages reset the spacing, so the first page after an unlock checks.
  `stored` logs once per real re-send. No path stores on every sync.
- **I-6 `push_off` loops and targets.** The device that went is never a recipient: `send_one` marks it `gone` before
  the raise, and `forget_in` deletes before tracing. A browser whose push service 410s every new subscription raises
  one event per cycle (a page load, a re-subscribe, a push, a 410), and the event has no switch, so it can't be
  muted. That is bounded by use, so acceptable. `ParentRemoved` and `Stale` are logged only, which is right.
  `forget_other_keys` raises events with no remaining device to push to (inbox only), which is fine. Labels come from
  `label_of`'s fixed set, so a User-Agent can't inject text into another parent's push.
- **I-7 Retention.** Gone rows now keep `p256dh`/`auth` for 180 days (and in backups). The keys are dead, but
  nulling them on `gone` would cost nothing. `push_removed`'s `removed_at` is refreshed on upsert. The `prune` test
  covers both tables.
- **I-8 Wording and docs.** en and nb are complete and identical in keys, bokmål throughout. One nb line reads
  badly: `security.detail.push_replaced` "Erstatter den push-tjenesten avsluttet" reads as "replaces that push
  service ended". Use "Erstatter den som push-tjenesten avsluttet". `handlers/lock.rs:11`'s module doc still says
  every bad assertion counts. `push::forget_in`'s doc says the CLI uses it, but `admin_auth::reset_2fa` inlines its
  own delete and calls `trace_forgotten`. The re-auth forms still count a `Database` refusal toward the sign-out.
  That matches the notes, but it is the same server-caused case point 6 fixes for the unlock.

## Checked, nothing found

- **CSRF / foreign endpoints.** No route lets a session name an endpoint it doesn't hold and gain from it. Endpoints
  are never rendered: `data-subscribed` carries 16-hex prefixes, and `subscriptions.json` carries ids and badges. A
  posted foreign endpoint with the attacker's own `p256dh`/`auth` would only send undecryptable pushes to the victim's
  push channel. The sync refuses even that for another parent's current row (`known`). `subscribe` still re-assigns
  it, as before (pre-existing, and it needs the secret endpoint).
- **`keep` after its session ends.** `/logout`, Sign Out Everywhere, the lockout and every refused `load_active_admin`
  flush the session, so the hash dies with it. A new sign-in calls `cycle_id` on a fresh or flushed session. Nothing
  carries the hash into another epoch or onto another device.
- **The lock screen's Sign Out confirm.** It is a `<details class="confirm">` with `confirm_cancel.html` and
  `confirm.js` from the head, with no `window.confirm`. The `data-sign-out` form is unchanged inside it, so the
  endpoint still goes with the sign-out. passkey.js's unlock listens on its own button and control, not on the
  summary.
- **No jump to the top.** The banner is inserted under `.page-title` with `main`'s `scrollTop` corrected (`main` is the
  `.container` scroller). Turn On and Turn Off keep their 303s to `#this-device` and `#push-offer-done`, and the form
  gained only a hidden `replaces`.
- **Every deletion traced.** Account changes, the lockout, Sign Out Everywhere, the CLI reset, a parent's Reset and
  Remove (after the commit), `forget_stale`, `forget_other_keys`, the 404/410 path, Turn Off, Remove and Sign Out
  each write a line. Gone rows are filtered out of the labels, so nothing is told twice.
- **Migration 0128.** `endpoint_hash` is the primary key, `admin_id` cascades, and only the hash is stored, never the
  endpoint. It runs after main's 0127. See L-5 for the test list.

## Fix round verified (0835133f, after the main merge b0a2cfe2)

Read `git diff 7e27749c..0835133f`. In the merge, only the migration list (`(127, "lock media apps")`,
`(128, "push removed")`) and server/CLAUDE.md touch this branch. cargo and node were not run.

**Every finding is fixed.**
- **M-1 fixed.**
  - `sync` now answers `removed` (unless `asked=1`) when the posted endpoint *or the session's noted browser* is in
    `push_removed`, or when the browser carries `__Host-handy-push-off`. That cookie is HttpOnly, SameSite=Strict,
    Secure, `Path=/`, 400 days (`lock::COOKIE_SECS`), and renewed by each `removed` answer.
  - It is set by Turn Off, by the banner's Hide (`off=1`) and by any `removed` answer. It is deleted by This Device's
    Turn On form and by a stored `asked=1`.
  - `push_removed` is no longer pruned. A tap's `subscribed()` also lifts the note of the session's previous browser.
  - `register-sw.js` turns `removed` into a local Off (no banner).
  - Tests: `a_remove_holds_past_a_new_endpoint_and_a_new_sign_in` and `turn_off_and_hide_mark_the_browser`.
- **L-1 fixed:** the failure branch clears the flag (jstest).
- **L-2 fixed:** `/logout` falls back to the session's noted row, still `admin_id`-bound, and doesn't mark it
  removed, so it comes back at the next sign-in as designed. Test:
  `sign_out_without_the_endpoint_takes_this_sessions_browser`.
- **L-3 fixed:**
  - `kept_row` takes only a row of the session's own epoch.
  - `keep_row` runs in the change's own transaction, right after the epoch `UPDATE`, for all four changes:
    password, 2FA reset, passkey remove under `BEGIN IMMEDIATE`, and `switch_on`.
  - `keep_row` re-checks `admin_id`/`active`, and `forget_in` re-checks `CURRENT_EPOCH`.
  - Test: `only_a_row_of_this_sessions_epoch_is_kept`.
- **L-4 fixed:**
  - Notification Settings posts a `known` sync for a subscription it already knows, so the hash is noted.
  - The flashes are worded from what was actually kept (`kept=1` -> `*_kept`), for the new password and for
    "Kun passkey". A crafted `kept=1` only changes the flash's words.
- **L-5 fixed:** the merge resolved the list. server/CLAUDE.md was updated in the merge.
- **L-6 fixed:** Turn Off notes and logs only the rows the `DELETE` returned, under their own owner.
- **I-2:** `the_sync_needs_a_whole_current_session` now covers the half-signed-in, older-epoch and cross-site cases.
- **I-3:** the server's own unlock refusals are coalesced: one `app_unlock_failed` line per parent, client and code
  every 10 minutes, "challenge ×N". I checked the SQL's character arithmetic: `length(code)+3` skips " ×", and the
  first merge reads "" as 1, giving ×2. The test expects `["challenge ×4"]`.
- **I-7:** gone rows are now written with `p256dh`/`auth` = `X''` (`NOT NULL` holds). A gone row is never encrypted
  to: `send_due` drops its queued deliveries before sending, and a re-activation upserts fresh keys. Test:
  `a_gone_row_keeps_no_keys`.
- **I-8:** the nb wording and both stale docs are fixed.

**The cookie can't be abused:**
- **CSRF:** it is set only by same-origin, CSRF-guarded, full-auth, unlocked POSTs (`sync`, `unsubscribe`). The
  `__Host-` prefix stops a sibling host from planting it.
- **Its only effect is to block the app's own re-send.** Deleting or forging it can't turn anything on: any store
  still needs the session, and `push_removed` still answers for the endpoint and the noted browser.
- **Cross-parent:** it is per browser, not per parent. On a shared browser, one parent's Off also stops the other
  parent's silent re-send. That is acceptable: their Turn On tap lifts it.
- **A way back always exists:** Notification Settings shows Off with Turn On whenever the answer is `removed`, and
  that form clears the cookie and both notes.

**M1 is not weakened:** nothing new re-enables a subscription, and the logout fallback closes the M1 gap left by a
sign-out without the endpoint.

**Remaining (Low, one):**
- **R-1 Removing a *gone* row now turns that device off for good.** `handlers/notifications.rs:1383-1395` notes
  `push_removed` for any removed row, `gone` included. The branch's own Devices warning invites this: "Notifications
  stopped on iPhone · Safari - turn them on again on that device, or remove it under Notifications".
  - Scenario: the iPhone's subscription 410s (the original iOS loss). The parent taps Remove on the laptop to clear
    the warning. At the iPhone's next opening, its noted hash is in `push_removed`, so the answer is `removed` and the
    cookie is set: no re-subscribe and no banner, until someone opens Notification Settings there and taps Turn On.
    Before the Remove, the same sync would have answered `gone` and re-subscribed on its own.
  - A gone row's endpoint is dead, so there is nothing to keep off. Removing it is cleanup, not an Off.
  - **Fix:** note only when the deleted row was `active`; add `state` to the `RETURNING`.

**Info:**
- An account change's own epoch commit can still race a send pass's `forget_stale` before `forget_subscriptions`
  runs. The other rows would then be deleted as `Stale`, logged but not told in the inbox. The window is milliseconds
  and unchanged from before the branch.
- `reset_totp` still leads straight to 2FA setup without kept-aware words. Harmless.

**Verdict:** with R-1 fixed (a one-line condition), the branch is ready to merge from a security standpoint.
