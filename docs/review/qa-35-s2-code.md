# Code QA: design 35 step 2 (Web Push to the parents' PWA)

Scope: `git diff b0d59eb1 983c6860` (branch `notif-s2`, one commit, 33 files) against `docs/design/35-parent-notifications.md`
(§4, §5.2, §5.3, §6, §7, §8, §9 step 2, §11 H1/H2 and the "Step 2 implementation status" with its nine deviations),
`docs/design/qa-35-design.md`, `docs/review/qa-35-s1-code.md` and `server/CLAUDE.md` / `server/DEPLOY.md`.

**Verdict: good to merge after the one Medium (or with it taken into the fix backlog).** The cryptography is right and
well tested: RFC 8291 Appendix A is asserted end to end (ECDH secret, IKM, CEK, nonce, the 86-byte header and the whole
ciphertext), the HKDF info strings are RFC 8291/8188's exactly (`"WebPush: info\0" || ua_public || as_public`,
`"Content-Encoding: aes128gcm\0"`, `"Content-Encoding: nonce\0"`), the record is one record with rs 4096 and the 0x02
delimiter, a fresh ephemeral key and salt per message, the 3072-byte cap holds the body under 4 KB. VAPID is ES256
with `ECDSA_P256_SHA256_FIXED_SIGNING`, the header and claims are right, `aud` is `https://host` (no port, no path),
`exp` = `iat` + 12 h, `k=` is the raw 65-byte point base64url unpadded, the JWT is reused only inside `[iat, iat + 1 h]`
and per `(aud, sub)`. H1 holds: 401/403 forgets the JWT, retries once at once with a fresh one, then `failed` - only
404/410 are `gone`. The receipt is a 128-bit token stored as SHA-256, compared in constant time, writes only the receipt
columns, and is bounded (1 KiB, 30 misses block the client). No secret reaches a page, a log or a backup. The one real
gap is health: a device whose pushes never reach its push service is never flagged, and Send a Test's outcome is
invisible.

## Findings

### Medium

**M1. Deliveries that never reach the push service don't feed health; Send a Test's result is invisible.**
`src/push.rs:816` (`health`) and `:846` (`subscriptions`) look only at accepted-but-unconfirmed pushes;
`src/handlers/notifications.rs:428` (`device_row`) shows "On · Nothing sent yet" (`NeverSent`) or "On · Last shown 3 days
ago" (`Ok`) whatever happened since. So when the Pi's firewall or the Docker network blocks the push services (the
first thing DEPLOY.md's "Notifications" asks the operator to open), or a push service answers 400/413, every delivery
ends `failed` and nothing warns: no orange row on `/notifications` or the Devices list, the device badge stays "On";
only each inbox row says "Not sent". The clock row covers 401/403 only. Measured in the browser pass with the push
service unreachable: after "Send a Test" the page says "A test is on its way." and, after the sender's tries (and later
the 600 s TTL), the device row still reads "Linux · Chrome - Nothing sent yet - On" (`light-test-later.png`,
`light-getting-notifications.png`); a test delivery has no `event_id`, so no delivery line shows it either. It doesn't
hang - it says nothing.
Fix: derive a fourth state from the newest deliveries, e.g. `Health::NotSent` when the subscription's latest finished
delivery (event, summary or test) is `failed` and none was accepted after it - badge "Not getting through", sub line from
the reason ("the push service couldn't be reached" for `network`/5xx, "refused (400)" otherwise, "Sending…" while one
waits for a retry), and the same orange warning row as `MayNotShow`. That also gives Send a Test its answer on the
next load (a test that went shows "Last shown …" once its receipt is in; one that failed shows why). One query change in
`subscriptions` (the latest finished delivery's status/reason per subscription) and a test with `ScriptedPush::fail()`.

### Low

**L1. This Device drops a subscription that belongs to the other parent's account on a shared device.**
`static/notifications.js:78` unsubscribes any local subscription whose hash isn't in `data-subscribed`, and
`src/handlers/notifications.rs:239` renders only the *signed-in admin's* endpoints. On a family iPad where parent A
turned notifications on and parent B later signs in to the PWA, B's first visit to `/notifications` silently
unsubscribes A's subscription in the browser; the server keeps A's row until a push gets a 410 ("Turned off"). Deviation
7's reason (a subscription made for an older key, or removed on the server) is sound, and every active subscription
gets every event anyway (`push::queue` doesn't look at `admin_id`). Fix: render the hashes of every active subscription
(all admins) in `data-subscribed`, or drop the local one only when no admin's active row has it. Rare (two admin
accounts on one device), hence Low.

**L2. The fold puts the urgent kinds into a normal-urgency summary.** `src/push.rs:462` spares only the emergency call.
A phone back after days posting a burst that holds a `pin_lockout` or a `foreign_access` (both `Urgency: high`, §4.3)
gets them folded into "New notifications" (`Urgency: normal`) when they aren't among the newest two. The design text
says only "an emergency call always goes on its own", so this follows §4.6, but the urgent kinds are exactly the ones
the parent should see on their own. Fix: `foldable` = `!kind.urgent()`; or give the summary `high` when it folds an
urgent kind.

**L3. A receipt for a delivery still waiting to retry doesn't stop the retry.** The deviation-3 case: a network error
after the body went (the push service accepted it), the worker shows it and posts its receipt before the retry -
`push::receipt` (`src/push.rs:918`) sets `receipt_at` (the token still matches: it is replaced only at the next send),
but the delivery stays `queued`, so `send_due` (`:381`) sends it again with a new token: a second notification (the same
`tag` replaces it, though iOS may alert again) and, if the receipt instead arrives after the retry minted its token,
a 404 that counts against the parent's device in `limits::ip_failed`. Fix: `send_due` (or `receipt`) turns a `queued`
delivery that has `receipt_at` into `accepted` (with `accepted_at` = the receipt's time) and doesn't send it again.
Rare; the "honest not confirmed" of deviation 3 is otherwise right - a lost receipt happens only when no retry follows.

### Nit

- **N1.** `webpush::tests::fresh_keys_per_message_the_cap_and_bad_keys` (`src/webpush.rs:352`) compares the first 86
  bytes, which hold the ephemeral key too - a fixed salt would pass it. The fixed-salt mutation is caught only by
  Appendix A (its header assertion, then `FixedSliceSequenceRandom`'s `Drop` panics while unwinding, which aborts the
  whole test binary and hides the other results). Assert `one[..16] != two[..16]` and `one[21..86] != two[21..86]`
  separately.
- **N2.** The "never retry past the TTL" guard in `send_one`'s `Retry` arm (`src/push.rs:741`) is untested: removing it
  passes every test, because the send-time check (`:584`) later ends the delivery `expired` anyway. What it changes is
  the delivery line: without it a test push after a network error says "Sending" for 30 min instead of "Not sent". A
  test push with 3 network errors (60 s, 300 s, then the 1800 s retry past the 600 s TTL → `failed` at once) pins it.
- **N3.** `subscribe` accepts an `http://` `Origin` (`src/handlers/notifications.rs:565`) and it becomes the JWT's
  fallback `sub`; RFC 8292 wants `mailto:` or `https:`, so such a subscription (only possible on `http://127.0.0.1`)
  would get 403s and show the clock row. Take only `https://` (and say why in the flash), or skip `sub` fallback for it.
- **N4.** "In a summary" (`delivery_line`) shows even when the summary delivery itself failed. Fine for phase 1.

### Deviations (the implementer's nine)

1. Summary without a count: acceptable - the inbox lists every event, the summary's target is `/notifications`, and
   fixed words survive a retry without a new column.
2. One push at a time: fine for one family. A dead endpoint costs ≤ 15 s per push in a pass; the emergency call is
   sorted first within a pass, and one raised mid-pass waits for the pass (at most a handful × 15 s).
3. Token per attempt, hash stored before the request: right (a quick receipt finds it; an old token can't confirm a
   push that wasn't the one shown). The lost-receipt case needs no retry to follow; see L3 for the adjacent case.
4. A tap posts `clicked` and opens the target directly: right (a worker can't follow a form's 303).
5. Endings (`expired`, `dropped`/`gone`, `dropped`/`event_gone`, "Sending"): clear; see N2.
6. The clock warning by `created_at`: fine (the 401/403 retry is in the same pass).
7. PWA's own 2FA reset keeps the subscriptions: acceptable - it signs out the other sessions, but the lost-device
   action is Sign Out Everywhere, which deletes them (tested), as does the CLI `reset-2fa` (tested).
8. Dropping an unknown local subscription at load: safe for one admin; see L1.
9. The form POST with `data-key`: fine, no JSON endpoint to guard.

### Checked and fine

- `keys.rs`: temp file `create_new` 0600, synced, rename, directory sync; a 0700 directory when made here; a non-empty
  file never replaced; a damaged key turns push off with the file untouched (tested); `music_secret` moved onto it
  without behaviour change. The live server made `data/keys/vapid-p256.pk8` `-rw-------` in a `drwx------` directory.
  `VapidKey`'s `Debug` prints only the fingerprint; `forget_other_keys` at startup (tested).
- `p256dh` is validated on the curve (a throwaway `ring` agreement) and `auth` must be 16 bytes at subscribe; `encrypt`
  fails `BadKey` on an off-curve point.
- Request: `Content-Encoding: aes128gcm`, `Content-Type`, `TTL`/`Urgency` per kind and test 600/high, no `Topic`
  (asserted), the endpoint re-checked with `allowed` before each send, redirects off, errors logged `without_url()`,
  every refusal logs host/status/reason/`apns-id` only.
- Answers: 201 accepted; 404/410 dropped + gone + `push_subscription_gone`; 401/403 per H1; 429 `Retry-After` seconds
  (an HTTP-date falls back to the schedule - fine, the push services send seconds) clamped to 2 h; 5xx and network on
  1/5/30/120 min then failed; other 4xx failed; the TTL checked before each send.
- Routes: the four POSTs sit in `require_full_auth` behind the global CSRF guard (tested: no session → `/login`, cross-site
  → 403); each redirects to `#this-device` or `#devices` with `from`/`device` kept (`assert_lands`); `check_forms` passes.
  The receipt is in `public_routes` with `DefaultBodyLimit::max(1024)` (413 tested), still behind the admin gate and the
  CSRF guard (cross-site 403 tested); 204/404 only; 128-bit token, so the 30-miss block is belt and braces.
- Security events `push_subscribed` / `push_subscription_gone` have labels (`every_written_event_has_a_label` passes);
  the notice codes are in `NOT_EVENTS`.
- `strip_secrets_in` deletes `push_deliveries` then `push_subscriptions` (tested on a real snapshot, the endpoint bytes
  absent); `/notifications/*` is `no_store_path`; retention prunes deliveries and gone subscriptions at 30 days.
- `raise_logged` now runs in its own transaction and wakes after the commit; `pin_events` and `battery_report` wake
  after their commits (tested with `woken()`).
- `Cargo.toml` gains `ring` only; `Cargo.lock` gains one dependency line; `--locked` builds.
- The worker: `push` always shows inside `waitUntil` (fallback "Vibb Phone / Something new - open Notifications"),
  badge, then the receipt with `credentials: "omit"`; `notificationclick` posts `clicked`, opens only a same-origin path
  (`//` and other origins → `/notifications`), steers an open window else `openWindow`; no `fetch` listener.
  `register-sw.js?v=2` sets/clears the badge from the bell. `notifications.js` follows H2's order and calls
  `subscribe` first in the tap with a `Uint8Array`; no inline script.

## Browser pass (Chromium 1140 via Playwright, 390 x 844 at 2x)

Throwaway server from `cargo build` (`BIND_ADDR=127.0.0.1:3196`, `INSECURE_COOKIES`), with `HTTPS_PROXY` set to a closed
port so every push service is unreachable. Setup as in the step-1 QA: first sign-in, forced password change, 2FA from the
shown key, two phones enrolled, events raised through the device API. Screenshots in
`/tmp/claude-1000/-workspace-handy/430a895f-506c-4fe9-bfff-b4b9a36a016b/scratchpad/qa-notif-s2/shots/`.

**Chromium headless and push:** the worker registers and activates over `http://127.0.0.1` (a secure context), but
Playwright's contexts are incognito, where Chromium has no Push API ("Chrome currently does not support the Push API in
incognito mode") and reports the permission as denied whatever `grantPermissions` says; a persistent profile's
`subscribe` hung (no GCM). So the real page ran with `PushManager.prototype.subscribe/getSubscription` stubbed (a fake
FCM endpoint and RFC 8291's test keys) - the page's own JS, form and server path, not the browser's push service.

| check | result | screenshots |
|---|---|---|
| Real PushManager, headless | Worker registered and active; state "Blocked" (incognito reports denied), Turn On hidden | `real-granted-this-device.png` |
| Permission denied | "Blocked" + the Settings note, no Turn On | `denied-this-device.png` |
| iOS Safari tab (`navigator.standalone === false`) | "Add to the Home Screen first" + the steps, checked before anything else | `ios-tab-this-device.png` |
| Off → Turn On (light) | "Off", Turn On enabled once the worker was ready; the click posted the form → `/notifications?from=devices&notice=push_on#this-device`, "On", flash "Notifications are on for this device.", scrollY 0 → 0 (the group is at the top) | `light-this-device-off.png`, `light-this-device-on.png` |
| Reload | "On" (the endpoint's hash is in `data-subscribed`) | `light-this-device-on-reload.png` |
| A local subscription the server doesn't know | Dropped at load (`unsubscribe` called once), "Off" | - |
| Turn Off | Local unsubscribe, then the POST → `...notice=push_off#this-device`, "Off", flash shown | `light-turned-off.png` |
| Send a Test, push service unreachable | → `...notice=test_sent#devices`, scrollY 1600 → 1600, "A test is on its way."; the server logged "a push didn't reach its push service host=fcm.googleapis.com"; 4 s later and after the retry the device row still says "Nothing sent yet · On" (**M1**) | `light-test-sent.png`, `light-test-later.png` |
| Delivery lines | "Sending" (retry waiting), "In a summary" (a 5-event `pin-events` burst: 3 folded, 2 + summary sent), "Not sent", "Pushed to iPhone · Safari, not confirmed", "Shown on iPhone · Safari" (seeded rows for a second subscription) - italic under the row's summary | `light-notifications-full.png` |
| Warnings | The red clock row (a seeded 403) and the orange "may not be showing on iPhone · Safari" row (3 unconfirmed) at the top; the device badge "May not be showing", "Last shown 3 h ago" | `light-notifications-top.png`, `light-getting-notifications.png` |
| Devices list warning row | "Notifications may not be showing on iPhone · Safari / Check it under Notifications", to `/notifications?from=devices#devices` | `light-devices-warning.png` |
| Dark | Badges, rows and footer readable (taken after the seeded iPhone row was removed, so no warning rows) | `dark-notifications-top.png`, `dark-getting-notifications.png`, `dark-devices-warning.png`, `dark-this-device-off.png` |
| Remove… | First of two device rows: scrollY 1990 → 1990, the next group at the same 294 px. The last row: 2500 → 2209 = the shorter page's new bottom (clamped, not a jump) | `light-after-remove.png` |
| JS off | "Needs JavaScript", no Turn On, footer "… Notifications need JavaScript." (`<noscript>`), light and dark; Send a Test lands with `#devices` at top 0 | `{light,dark}-nojs-this-device.png`, `light-nojs-after-test.png` |
| Width / console | scrollWidth 390 on every page; no console errors besides Chromium's incognito Push notice | - |

## Mutations

Each applied alone, the targeted tests run (`webpush push:: web_push notifications`, or `node --test jstest/`), then
reverted with `git checkout`; the tree is clean.

| # | mutation | result |
|---|---|---|
| M1 | 403 marks the subscription gone | caught: `push::tests::the_answer_table`, `web_push::the_push_services_answers` |
| M2 | no curve check on `p256dh` | caught: `webpush::tests::fresh_keys_…`, `web_push::subscribe_refusals_…` |
| M3 | a fixed salt (never drawn) | caught: `webpush::tests::rfc_8291_appendix_a` (header, then the fixed random's `Drop` aborts the binary; N1) |
| M4 | no 3072-byte cap | caught: `webpush::tests::fresh_keys_…` |
| M5 | the receipt accepts a wrong token | caught: `web_push::the_receipt` |
| M6 | CSRF skipped on `/notifications/subscriptions` | caught: `web_push::subscribe_refusals_…` |
| M7 | a switched-off kind is queued | caught: `web_push::switches_the_emergency_call_and_a_burst` |
| M8 | the push tables kept in backups | caught: `web_push::sign_out_everywhere_reset_2fa_and_backups_…` |
| M9 | JWT `aud` = the full endpoint URL | caught: `web_push::a_push_carries_…`, `webpush::tests::only_the_four_push_services` |
| M10 | the 401/403 retry keeps the cached JWT | caught: `web_push::the_push_services_answers` |
| M11 | the emergency call folded | caught: `web_push::switches_the_emergency_call_and_a_burst` |
| M12 | a retry scheduled past the TTL | **not caught** (N2) |
| M13 | `sw.js`: an unreadable push shows nothing | caught: jstest "a push that can't be read still shows a notification" |
| M14 | `sw.js`: the receipt before `showNotification` | caught: jstest "a push shows its notification, then the badge, then posts the receipt" |
| M15 | `sw.js`: a tap may open another origin | caught: jstest "a tap never leaves this server" |
| M16 | `notifications.js`: `requestPermission()` before `subscribe` | caught: jstest "Turn On waits for the worker, subscribes first …", "a refused prompt …" |

## Runs

- `cargo test --locked`: **1025 passed**, 0 failed (new: `webpush::tests` 4, `push::tests` 4,
  `handlers::notifications::tests` 1, `tests::web_push` 10).
- `cargo fmt --check`: clean. `cargo clippy --all-targets --locked`: the 13 known warnings, none new.
- `node --test jstest/`: **49 passed** (new: `sw.test.js` 6, `notifications.test.js` 7, `register-sw.test.js` 2).
- `cargo build --locked`: clean (the 9 known dead-code warnings).

Device checks still owed before step 2 is done (the note's list): iPhone 18.4+ (and 16.4-18.3 if at hand), macOS
Safari, Android Chrome, desktop Firefox, the 410 by removing the Home Screen app, the clock row on a Pi with a wrong
clock.
