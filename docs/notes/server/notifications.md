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
  `fcm.googleapis.com`, `jmt17.google.com` (a Chromium without Google's API keys, 2026-10-19),
  `updates.push.services.mozilla.com`, `*.push.apple.com`, `*.notify.windows.com`),
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
  receipt token (its SHA-256 stored before the request); the payload of design 35 §5.3 - `push::payload_json`: **declarative by default** (`web_push` 8030, `app_badge`, never `mutable` since 0.29.6 - see below), plain
  with `PUSH_PAYLOAD=classic` (`Push::with_payload`; 2026-10-19: the user's iPhone showed every push and confirmed
  none, and the two forms can now be compared on one device against the `push receipt` log lines, which carry the
  worker event `e` and the worker version `v` the receipt names) - (`navigate` = the
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
  `POST /notifications/receipt` is in `public_routes` with a 1 KiB limit and **outside the CSRF guard** (2026-10-19: a
  WebKit service worker's fetch carries no `Sec-Fetch-Site` and a proxy may rewrite `Host`, so the user's iPhone
  showed every push and confirmed none; the token is the receipt's proof, a cookie lends it nothing): `{d, t, a}`, the token's hash compared in
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
  `/notifications?from=devices#devices`. `templates/sw.js`: `push` inside `waitUntil` - **legacy** (the payload in `event.data`,
  every browser but Safari): show (with `navigate` as an option too, absolute and same-origin), then the badge, then
  the receipt (`credentials: "omit"`); a fallback "Vibb Phone" / "Something new - open Notifications" for an unreadable
  payload. **Declarative** (`event.data` null, Safari's proposed Notification in `event.notification` with our `data`:
  Safari 18.4+ reads a mutable payload itself and fires `push` with it - WWDC25 session 235, WebKit's PushEvent.idl):
  the receipt (`e=push-declarative`) and the badge, and **nothing shown** - Safari shows its proposed notification,
  the one iOS displays. The road there (2026-10-19, the user's iPhone on iOS 26, a two-step probe in the worker,
  0.29.2-0.29.4): a receipt after `showNotification` never arrived; `showNotification` threw a TypeError until the
  replacement carried `navigate` as an option (ServiceWorkerRegistration::showNotification: "did not include
  NotificationOptions that specify a valid defaultAction url"); and a replacement accepted (`shown: ok`) was never
  displayed. So the worker leaves the showing to the platform (0.29.5); the payload's top-level `app_badge` sets the
  badge without the worker too. **0.29.6: no `mutable` at all.** On 0.29.5 the worker ran, confirmed and badged, and
  iOS 26 still displayed nothing: with `mutable` the result of the worker's run (WebKit: the replacement, else the
  proposed notification) goes back through the host app to be shown, and on this iOS that step shows nothing in
  either case, while the worker's own failure (0.29.1) made it show the proposed one. WebKit's `WebPushDaemon` shows
  an immutable declarative push itself at once (`handleIncomingPushImpl`), no worker - the path Apple's own demos
  use, and the one that works. So the declarative payload is immutable; the worker's declarative branch stays for a
  WebKit that fires it; and **no receipt comes from a Safari device**: the sender marks a push Apple's service
  accepted (`webpush::is_apple`, declarative on) with `reason = 'declarative'`, which counts as delivered -
  `delivery_line` "Delivered to iPhone · Safari" (a receipt still says "Shown on"), `Subscription::last_delivered_at`
  "Last delivered …", and `subscriptions()` leaves such pushes out of the unconfirmed count, so an iPhone is never
  "May not be showing" (the user: 201 = delivered, no repeat-until-opened). `pushnotification` (WebKit's explainer's name, fired by no shipping Safari) does the
  same receipt and badge; `notificationclick` posts `clicked` and opens a same-origin path only (else `/notifications`,
  the link from `data.navigate`, else the notification's own `navigate`), steering an open window first - on Safari
  the tap on its own notification opens the link without the worker; still no `fetch` handler, no CSP change. `static/register-sw.js`
  (`?v=2`) also sets the app badge from the bell's `data-unread`.
- **Device checks owed** (design 35 "Step 2 implementation status"): iPhone 18.4+ and 16.4-18.3, macOS Safari,
  Android Chrome, desktop Firefox, the 410 after removing the app from the Home Screen, Focus on, Safari's declarative
  fallback, where `app_badge` goes.
- Tests: `webpush::tests` (Appendix A end to end, the cap, bad keys, the JWT verified with the public key, the
  allow-list), `push::tests` (the answer table, health, the JWT cache, labels, test spacing), `keys::tests`,
  `handlers::notifications::tests` (the delivery line), `src/tests/web_push.rs`, `jstest/sw.test.js`,
  `jstest/notifications.test.js`, `jstest/register-sw.test.js`.

## Notifications: the inbox first, the tap marked (design 45 and server round 5, migration `0082_art_backfill_and_last_opened.sql`)

Supersedes the page layout above (step 1's single `/notifications` page; step 2's warnings, This Device and Getting
Notifications on it). `docs/design/45-notifications-inbox-first.md`, `docs/design/r5-server-small-items.md`.

- **Two pages** (`handlers::notifications`, both full auth, both `no-store` by `["notifications", ..]`):
  `GET /notifications` (`notifications.html`, `InboxTemplate`) is the inbox alone - the back link to `?from=`'s tab, a
  gear in the bell's place (`title-action title-bell`, `aria-label` "Notification settings", `id="notification-settings"`)
  to `/notifications/settings{query}`, one-line warning rows (the clock, "may not be showing"/"not reaching") to the
  settings page's `#clock-warning`/`#devices`, the phone filter, the rows, Newer/Older, "Mark all N as read" (N = what
  is left under the filter), the empty state "Nothing yet - what the phones report shows up here"; no script of its
  own. `GET /notifications/settings` (`notifications_settings.html`, `SettingsTemplate`, one route with Notify Me About's
  POST) has the back link to the inbox, the clock warning, This Device (`#this-device`), Getting Notifications
  (`#devices`) and Notify Me About (`#events`) as they were, and `notifications.js?v=2` (with a push key). Both include
  the tab bar with no tab current. Every subscription POST and Notify Me About come back to
  `/notifications/settings{query}…#group`; `open` and `read-all` to the inbox. Links: the bell to the inbox; the Settings
  hub's row and the Devices list's warning to the settings page; a Send a Test push's `navigate` to
  `/notifications/settings#devices`.
- **Opening the inbox reads it** (§1.3): `page` reads the page, marks exactly the shown ids read (`UPDATE … WHERE id IN
  (json_each) AND read_at IS NULL`), then counts; the rows keep their pre-mark `unread` for the existing dot and
  "Unread:" text. The gear carries `data-unread` = every unread event left (not only the filter's: the bell's count), so
  `register-sw.js` (unchanged, `[data-unread]`) clears this device's app badge; other devices follow their next push's
  `app_badge` or page. An older page (`?before=`) reads its own rows when opened; a `?device=` filter leaves the other
  phones' rows. Events are shared by every parent, so is their read state (as Mark All as Read). This GET writes on
  purpose: the admin cookie is SameSite=Strict, so a cross-site GET carries no session (the module doc says so). Back
  to the inbox is a reload (no-store) and shows no dots - by design.
- **The tap on a notification** (r5 §1): `push::send_one`'s `navigate` is `push::with_marker(target, delivery)` -
  `?push=<id>` before any `#fragment`, `&push=` after an existing query (none today); the classic worker's
  `data.navigate` is the same string. `handlers::notifications::push_marker` is a `from_fn_with_state` layer on
  `admin_routes`, added before `.layer(require_full_auth)` so it runs inside it and sees `CurrentAdmin`; on a GET with a
  `push` key it calls `push::opened` (this admin's delivery only: `clicked_at` kept first, the subscription's
  `last_opened_at`, the event's `read_at`; a summary or test has no event) and answers a 303 to the same path and query
  without `push` (garbage or a foreign id: the same 303, nothing marked; the fragment survives, as the browser keeps
  it). Signed out, `require_full_auth` sends `/login` without the URL: the marker is lost, which changes nothing. A
  `clicked` receipt runs the same writes (`push::mark_opened`). `delivery_line`'s 5th field is "opened" (`clicked_at
  IS NOT NULL`): one delivery "Opened on …" before "Shown on" before "Delivered to" before "Pushed to …, not
  confirmed"; several "Pushed to 2 devices · opened on 1" when any was opened, else "· shown on N". A delivery never
  opened stays delivered.
- **The quiet count** (r5 §2): `push_subscriptions.last_opened_at` (0082; set by the marker and a click receipt,
  outliving the 30-day deliveries). `Subscription::since_opened` = accepted deliveries newer than
  `max(last_receipt_at, last_opened_at)` (every accepted one when neither came), `ever_opened` = either came. The device
  row's sub-line gains "· 7 since one was opened" or "· 7 not opened yet" (Ok and May not be showing rows; nothing at
  0). Plain `row-sub`; health unchanged.
- **Send a Test's rows poll** (r5 §5): `GET /notifications/subscriptions.json` (this admin's rows: `id`, `badge`,
  `badge_class`, `sub` - never an endpoint or key; no-store). Back on the settings page with `?notice=test_sent&sub=N`,
  `notifications.js` (`testedRow`, `pollRows`) fetches it every 3 s, at most 10 times, re-rendering each row's
  `[data-badge]` (text, class) and `[data-sub]` in place (no reload, no scroll), and stops once the tested row's
  sub-line changed; a lost answer just waits for the next tick.
- **The command stream's log lines** (r5 §3): `commands_stream` logs `command stream opened` (`device`) and
  `StreamGuard::drop` `command stream closed` (`device`, `secs` = `StreamGuard::age()`, from `opened: Instant`), both
  at `info` - the server's half of the SSE diagnosis (`adb logcat -s CommandListenerService` the phone's); `close_all`
  at shutdown gives one line per stream.
- **0082's other half** (r5 §4): `music_listings.art_more = 1` for every listing with an `ok` item whose `art_url` was
  never tried at that URL (`art_from IS NULL OR art_from <> art_url`, the art pass's own condition), so entries
  checked under the old caps get their pictures at the next sweep. `tests::music_art::migration_0082_flags_entries_with_untried_pictures`.
- Tests: `tests::notifications` (the inbox reads its rows, the filter, older pages, the settings page, the switches),
  `tests::web_push` (`pages` = inbox + settings; the marker, the count, `subscriptions.json`, every redirect),
  `push::tests::the_marker_in_a_target`, `handlers::notifications::tests::the_delivery_line`,
  `streams::tests::a_guard_knows_its_age`, `admin_auth::tests::no_store_paths`, `jstest/notifications.test.js` (the
  poll), `jstest/register-sw.test.js` (the badge source, unchanged).

## The phone turned off and on (design 38, `docs/design/38-phone-off-on.md`, migration `0080_phone_power.sql`)

- **The route** (`device_api::power`, `device_routes.rs`; bearer, its own 2 KiB body limit, `POWER_BODY_LIMIT`):
  `POST /api/devices/power` `{off?, on?}` (`models::PowerReport`: `PowerOff {at_ms, boot_count, battery, reboot,
  reason}`, `PowerOn {at_ms, boot_count, battery}`; `battery` through `lenient_battery`, `reason` any non-string =
  none) -> 204; neither part or not one -> 400; > 2 KiB -> 413; a DB error 500 (the phone keeps its record). No
  `device_status` row, no security log line. Capability `power_v1` (an old launcher never calls it). Contract
  `testdata/power_report.json`, byte-identical in the launcher's test resources (root `CLAUDE.md`).
- **The state machine** (pure `parent_events::power_events(&report, last_report, &phone) -> PowerPlan {events,
  drop_held_off, release_held_off, dropped}`, applied in one transaction, then `push.wake()` if a row or a delivery
  changed): parts dropped first (an instant > 5 min in the server's future - that part only -, an `off` or an `on`
  older than 30 days - `POWER_MAX_AGE`, the `on` since the code QA's nit 2 -, an `off` later than its `on`; logged). Paired when `off.boot_count < on.boot_count`: one boot apart and
  (`reboot: true`, or `reboot` unknown and the phone's two instants <= `RESTART_GAP` = 3 min apart) is a **restart** -
  "Anna restarted" pushed, the off row (new: `Push::No`, " · told at the next start") and its still-`queued`
  deliveries `dropped` with reason `restart` (`UPDATE ... WHERE event_id = <off> AND status = 'queued'`); otherwise
  "Anna was turned on · 07:02 · off since 21:14, battery was 3 %" pushed, a new off row late and unpushed, and an
  already-known off's held push - never tried yet - **released at once** (`next_try_at = NULL`), so the parent gets
  "turned off" before "turned on", never after (a deviation from the note, which left it untouched; design 38
  "Implementation status"). Unpaired: an `off` alone is a new row with its push held `OFF_HOLD` = 5 min from its
  arrival (`Push::Held`; `reboot: true` -> `Push::No`); an `on` alone says "no shutdown seen · last report 21:10,
  battery 5 %, probably flat" from the newest `device_status` row before the boot instant (one query). A resent body
  raises nothing (the dedup keys). A **new** `phone_off` row supersedes every older off of the phone whose push still
  waits (`dropped`, reason `superseded`; qa-38-code nit 3: two shutdowns before a start got through - the newest off is
  kept and told, never a stray "turned off" for a boot long over). "Turned off" comes only from a `phone_off`
  (ShutdownThread's broadcast).
- **Kinds and words** (`parent_events`): `Kind::PhoneOff` (`phone_off`, switch `push_phone_off` "Phone turned
  off"), `Kind::PhoneOn` (`phone_on`, `push_phone_on` "Phone turned on"), after `UnseenBoot` in `Kind::ALL`, not
  urgent, TTL 43200, foldable. `Phone::phone_off(off, at, late)`: "Anna was turned off" / "21:14 · battery 2 %,
  probably flat" (`battery_words`: probably flat at <= `FLAT_LEVEL` 3 not charging, else charging / not charging),
  reason `battery` "Anna shut down: battery flat" / `thermal-shutdown`, `thermal` "Anna shut down: too hot" (the level
  only), `reboot: true` "Anna is restarting" (`off_reason_words`: `userrequested` and anything else say nothing more,
  never shown); `Phone::phone_on(on, at, &Start)`: `Restart` / `AfterOff` / `NoShutdownSeen(LastReport)` (probably
  flat at <= `LAST_REPORT_FLAT_LEVEL` 10). Times by `Phone::at`, capitalised at the summary's start ("Yesterday
  21:14"); a missing battery leaves its clause out. Keys: `phone_off` = the instant (RFC 3339 ms UTC), `phone_on` =
  `boot:<devices.enrolled_at>:<boot_count>` (`Phone.enrolled_at`; a re-enrollment or a factory reset is another
  count). Target `/devices/{id}`.
- **The hold**: `NewEvent.push: Push` (`Now` from `Phone::event`, `Held(until)`, `No`); `raise` passes it to
  `push::queue(conn, event, kind, not_before)`, which writes `next_try_at` (`send_due` waits for it, `run_sender`
  wakes at the earliest; the TTL counts from `created_at`). **The sender claims a row** before its request
  (`push::claim`: `UPDATE ... SET token_hash, attempts + 1 WHERE id = ? AND status = 'queued'`) and skips it when that
  changed nothing - a restart's drop that commits after a pass read the row as due (qa-38-design N4).
  `notifications::delivery_line` counts a `restart` or `superseded` drop as no delivery (no line, never "Not sent");
  during the hold the row reads "Sending".
- **PWA**: the two switches on Notify Me About come from `Kind::switch()` (no template change; the form and its
  `#events` redirect are design 35's). The device page's header (`devices::render_device`, `data-power-off`): "Off
  since 21:14 · battery was 3 %" while the newest `phone_off` has a later `happened_at` than the newest `phone_on` and
  no `device_status` report is newer than it (qa-38-code M1: a report proves the phone is on, whatever start never
  came - a re-enrollment, a boot count of -1, a dropped start; compared as `datetime(happened_at, '+60 seconds') >=
  MAX(reported_at)` - RFC 3339 against SQLite's `datetime` text never as raw strings -, the minute for the phone's
  clock); one query; the battery read back from the off's stored words (`parent_events::off_since_line`, which also
  says nothing while the off's push is held - `created_at` within `OFF_HOLD`, a restart may still fold it - or for a
  restart the phone announced, "is restarting"; qa-38-code nit 1); gone with the next start or report; a read error is
  a 500 like the page's other reads.
- **Migration 0080**: `notification_settings.push_phone_off`/`push_phone_on`, default 1. Nothing else.
- Tests: `parent_events::tests` (`the_power_words`, `the_power_state_machine`, `the_off_since_line`, the kinds in
  `the_words_and_targets`), `handlers::notifications::tests::the_delivery_line` (a `restart` drop), `src/tests/power.rs`
  (the contract and its copy, H1's sequence - held, dropped, restarted -, the hold running out, the release before the
  "turned on", a known restart, the last report, the switch, re-enrollment, the 4xx answers, the device page's line,
  the claim; since the code QA's fix round a report ending "Off since", a superseded held push, the level range in
  the route's own test), `music_spotify::migrations_73_to_83_in_order`.
