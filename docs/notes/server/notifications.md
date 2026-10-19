# Server notes: notifications

Moved verbatim from server/CLAUDE.md on 2026-10-19; the core file's 'Where the rest is' lists every note.

## Notifications to the parent, step 1: events and the inbox (design 35 §9 step 1, `docs/design/35-parent-notifications.md`, migration `0078_parent_events.sql`)

- **Events** (`src/parent_events.rs`): one row per event in `parent_events`, stored by `parent_events::raise(conn,
  &NewEvent)` (`INSERT ... ON CONFLICT (device_id, kind, dedup_key) DO NOTHING RETURNING id`, on the caller's
  connection, so `pin_events`' transaction holds it; a target that isn't a local path - `local_target`: one leading `/`,
  never `//` or `/\` - is refused). The words are made at raise by `Phone` (name, battery latch, Phone Setup zone, now;
  `Phone::load`) from typed values, cut to 60/100 characters (a long phone name gets "…", the rest stays), local times:
  `emergency_call` (`device_api::status`, `parent_events::new_emergency_call`: newer than the previous report's
  `call_state.last_emergency_call_at`; when that report named none - or there is none - at most 24 h old), `pin_lockout`/`pin_used`
  (`handlers::parent_pin::pin_events`, a newly stored `lockout`/`unlocked`, in its transaction), `foreign_access`
  (`handlers::window_relock::log_foreign_access`, now on `&AppState`: the set changed and isn't empty; key = previous
  report id + 16 hex of the stored set's SHA-256, both from the intake's one read of the previous report,
  `PreviousReport`, so parallel reports agree; `detail` = the apps by the phone's names), `unseen_boot` (where
  `boot_witness::log_line` returns a line), `low_battery` (below). Every kind is stored; `notification_settings`'
  switches govern the push only (step 2). The reserved kinds get a variant when something raises them.
- **The battery** (§2.5): status `battery` `{level, charging, low}` (`models::BatteryReport`, read by
  `lenient_battery`: not an object with a level in 0..=100 drops the reading, never the report) ->
  `device_status.battery_level`/`battery_charging`; the pure `battery_edge(latched, &battery)` (armed + `low` → Raise,
  latched + neither low nor charging → Rearm), `battery_report` latches `devices.battery_low_since` with a conditional
  UPDATE and raises only when that changed the row, in one transaction. "Battery 14 %, charging · 3 min ago"
  (`battery_line`, the report's age) on the Devices row (`time_glance` reads it with the badge) and in the summary's header.
- **PWA**: the bell (`partials/bell.html`, `parent_events::Bell::load`, one partial-index COUNT, no cache) in the
  `.title-bar` of the five tab pages (Locate and Settings gained the bar), before the page's own action; the count
  renders server-side ("99+", `data-unread`). `/notifications` (`handlers::notifications`, full auth): back link to the
  tab of `?from=`, no tab current (`shell::every_template_with_the_bar_sets_its_tab` allows `""` for it alone), Inbox
  (`#inbox`: the phone picker `?device=` as an auto-saving GET form with a `<noscript>` Show, 100 newest, "Show
  older" `?before=`, each row a form `POST /notifications/{id}/open` - marks read, 303 to the target, a gone event or
  a non-local target back to `#inbox` -, Mark All as Read `POST /notifications/read-all` back to `#inbox` with `from`
  and `device`), Notify Me About (`#events`, one auto-saving form, `POST /notifications/settings` writes every switch,
  the columns named by `Kind::switch()`). The inbox is `no-store` (`no_store_path`), so Back never shows its stale
  dots; the tab pages keep their cache, so the bell's count may lag one navigation after Back. The Settings hub's first row "Notifications · 3 unread". CSS under
  "/* Design 35 */" at the end of `style.css` (`?v=33` since step 2).
- **Retention**: `parent_events` 90 days by `created_at`, `push_deliveries` 30 days, `gone` subscriptions 30 days
  after `gone_at` (`retention::Pruned::parent_events`/`push_rows`); a deleted phone's events cascade.
- Tests: `parent_events::tests` (the words/targets table, the battery edge, the emergency rule), `src/tests/notifications.rs`.

## Notifications to the parent, step 2: Web Push (design 35 §9 step 2, no migration)

- **Modules**: `src/keys.rs` (the key files in `data/keys/`: `load_or_create` - made once through `<file>.tmp`, 0600,
  a rename and a directory sync in a 0700 directory; a non-empty file is never replaced; `music_secret` uses it too),
  `src/webpush.rs` (pure, on `ring` - now a direct dependency, `default-features = false, features = ["alloc"]`,
  already compiled in through rustls, so `Cargo.lock` gained only the dependency line: `VapidKey` (PKCS#8, `public`
  base64url, `fingerprint` 16 hex), `vapid_jwt` (ES256, `aud` = "https://host", `exp` = `iat` + 12 h, `sub` - `VAPID_SUBJECT`, else an `https://` origin, else none),
  `authorization` ("vapid t=…, k=…"), `encrypt` (RFC 8291 + RFC 8188 `aes128gcm`, one record, a fresh ephemeral key
  then a fresh salt per message, plaintext cap 3072), `allowed` (https, port 443, no userinfo, no IP literal;
  `fcm.googleapis.com`, `updates.push.services.mozilla.com`, `*.push.apple.com`, `*.notify.windows.com`),
  `valid_browser_key`; `test_browser` (`#[cfg(test)]`, the only `#[allow(deprecated)]`: `ring::test::rand::
  FixedSliceSequenceRandom` over RFC 8291 Appendix A's user agent, `decrypt`), `src/push.rs` (the rest).
- **The key**: `push::load_key(data/keys/vapid-p256.pk8)` in `main`; a damaged file -> `AppState.push.key` `None`
  (push off, the page says so, the file untouched); right after the load `forget_other_keys` turns every active
  subscription with another `vapid_fp` `gone`. `VAPID_SUBJECT` (`push::subject_of`: `mailto:`/`https:` else
  ignored with a warning). Never in a log (`VapidKey`'s `Debug` is its fingerprint), page or backup.
- **Queue and sender**: `parent_events::raise` calls `push::queue` on its own connection for a new row (one `queued`
  delivery per active subscription when the kind's switch is on; the emergency call always); `raise_logged` (now a
  transaction of its own, `&AppState`), `battery_report` (`&AppState`) and `pin_events` wake the sender
  (`state.push.wake()`, a `Notify`) after the commit. `push::run_sender` (spawned by `main`) runs `send_due(state,
  now)` at start, on every wake and at the earliest `next_try_at`; tests call `send_due` directly. A pass: due rows
  (`queued`, `next_try_at` NULL or past) of a gone subscription or a pruned event are `dropped`; more than 3 due event
  pushes of the normal kinds for one subscription (an urgent kind is never folded, L2) -> the newest 2 + one `summary` delivery ("New
  notifications", tag `s<id>`), the rest `dropped`/`folded`; sent one at a time, the emergency call first, then
  `Urgency: high`. Each send: TTL expired -> `failed`/`expired`; endpoint re-checked with `allowed`; a fresh 16-byte
  receipt token (its SHA-256 stored before the request); the payload of design 35 §5.3 (`navigate` = the
  subscription's origin + target, `tag` `e<event>`/`s<id>`/`t<id>`, `data {d, t, b}`); `TTL`/`Urgency` from
  `Kind::ttl()/urgency()` (`emergency_call` 86400/high; `pin_lockout`, `foreign_access` 43200/high; `pin_used`,
  `unseen_boot`, summary 43200/normal; `low_battery` 3600/normal; test 600/high), no `Topic`. The JWT is cached per
  `(aud, sub)` while `now` is in `[iat, iat + 1 h]`. `push::Transport` is the seam (`HttpTransport`: reqwest, HTTP/1.1,
  no redirects, connect 5 s, total 15 s, errors without the URL; `tests::web_push::ScriptedPush` records and
  answers from a script).
- **The answer table** (`push::outcome`): 2xx `accepted`; 404/410 `dropped`, the subscription `gone` +
  `push_subscription_gone` security line; 401/403 (H1: the server's signature, usually the Pi's clock) -> the cached
  JWT forgotten, one retry at once with a fresh one, then `failed` with the reason kept - never `gone`;
  429/500/502/503/504/network error -> retried after 1 min, 5 min, 30 min, 2 h (`Retry-After` seconds honoured up to
  2 h), then `failed`, never past the TTL (`expired`); other statuses `failed`. Every refusal logs the status, the
  JSON `reason`, `apns-id` and the host - never the path, a key, the JWT or a token.
- **Health** (`push::subscriptions`, derived when read): `Gone`; `NotGettingThrough` when the newest finished
  delivery (accepted or failed, by id) is `failed` - nothing accepted after it -, with `push::failure_text`'s reason
  (qa-35-s2-code M1: a blocked outbound 443 shows); unconfirmed = accepted, no receipt, after the subscription's
  `last_receipt_at`, `MayNotShow` at 3 or the oldest 24 h old; `NeverSent` ("Sending…" while one is queued); `Ok`. A
  receipt for any delivery clears the unconfirmed. Both warning states get the orange rows (`notifications::warning_of`). The clock warning (`push::signature_refused`): a 401/403 failure in the last day and none
  accepted since.
- **Routes** (`handlers::notifications`, full auth + CSRF, each back to its group with `?notice=`):
  `POST /notifications/subscriptions` (This Device's Turn On: `endpoint` allowed and <= 1024, `p256dh` a P-256 point,
  `auth` 16 bytes, `Origin` required, at most 10 active per admin, upsert on `endpoint`, label from the User-Agent
  "iPhone · Safari", `push_subscribed`; refusals flash in `#this-device`), `.../subscriptions/unsubscribe`,
  `.../subscriptions/{id}/test` (one per 30 s, in memory), `.../subscriptions/{id}/delete` (both `#devices`).
  `POST /notifications/receipt` is in `public_routes` with a 1 KiB limit: `{d, t, a}`, the token's hash compared in
  constant time, 204 (repeats too) / 404 counted in `limits::ip_failed` (429 while blocked) - except an older token of
  a delivery sent more than once (`push::Receipt::Stale`, not counted); `clicked` marks the event read. A `queued`
  delivery with a receipt is made `accepted` at the next pass, never sent again (L3). Sign Out Everywhere and `reset-2fa` (`admin_auth::reset_2fa`) delete the admin's subscriptions;
  `backups::strip_secrets_in` deletes `push_deliveries` and `push_subscriptions` from every copy.
- **PWA**: `/notifications` gained the warnings (the clock row, a "may not be showing" row per device to `#devices`),
  This Device (`#this-device`: the form carries `data-key` = the public key and `data-subscribed` = 16-hex SHA-256
  prefixes of every admin's active endpoints (`push::active_endpoint_hashes`, L1) - never an endpoint, browser key, auth secret or token;
  `static/notifications.js` - the state order of H2, `subscribe` as the tap's first call with a `Uint8Array` key, a
  local subscription the server doesn't know is dropped first), each inbox row's delivery line (`delivery_line`:
  "Shown on …", "Pushed to …, not confirmed", "Pushed to 2 devices · shown on 1", "Sending", "In a summary", "Not
  sent"), Getting Notifications (`#devices`: label, badge On / May not be showing / Turned off, "Last shown …", Send a
  Test, Remove…, the ⓘ checks). The Devices list's top orange row (`notifications::warning_row`) to
  `/notifications?from=devices#devices`. `templates/sw.js`: `push` always shows a notification inside `waitUntil`
  (a fallback "Vibb Phone" / "Something new - open Notifications" for an unreadable payload), then the badge, then the
  receipt (`credentials: "omit"`); `notificationclick` posts `clicked` and opens a same-origin path only (else
  `/notifications`), steering an open window first; still no `fetch` handler, no CSP change. `static/register-sw.js`
  (`?v=2`) also sets the app badge from the bell's `data-unread`.
- **Device checks owed** (design 35 "Step 2 implementation status"): iPhone 18.4+ and 16.4-18.3, macOS Safari,
  Android Chrome, desktop Firefox, the 410 after removing the app from the Home Screen, Focus on, Safari's declarative
  fallback, where `app_badge` goes.
- Tests: `webpush::tests` (Appendix A end to end, the cap, bad keys, the JWT verified with the public key, the
  allow-list), `push::tests` (the answer table, health, the JWT cache, labels, test spacing), `keys::tests`,
  `handlers::notifications::tests` (the delivery line), `src/tests/web_push.rs`, `jstest/sw.test.js`,
  `jstest/notifications.test.js`, `jstest/register-sw.test.js`.
