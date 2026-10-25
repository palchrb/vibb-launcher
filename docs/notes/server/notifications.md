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
  `boot_witness::log_line` returns a line), `low_battery` (below). Every kind is stored; each parent's
  `notification_prefs` switches govern the push only (step 2; per parent since design 62 step 5, below). The reserved kinds get a variant when something raises them.
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
  delivery with a receipt is made `accepted` at the next pass, never sent again (L3). Sign Out Everywhere, a new password and "Reset Two-Factor Login" on Account (security
  review M1, `pwa-fixes-a`), the re-auth sign-out after three wrong passwords (`admin_auth::record_reauth`,
  qa-pwa-fixes-a-code Q-1) and the CLI's `reset-2fa` (`admin_auth::reset_2fa`) delete the admin's subscriptions
  (`push::forget_subscriptions`), this browser's too; Account says "notifications are off everywhere". A parent's
  Reset and Remove delete them in their own transaction (`parents.rs`). Each subscription also keeps the
  `session_epoch` of the session that turned it on (0095, written at subscribe and on its upsert): every reader -
  Notification Settings' device list, This Device's `data-subscribed`, the 10-device cap, Send a Test, `queue` -
  treats an older one as gone (`push::CURRENT_EPOCH`), and every pass of `send_due` first deletes them
  (`push::forget_stale`, one log line) - the backstop for a subscribe in flight while the sessions ended, or a
  sign-out whose delete failed; their deliveries go with them (`tests::web_push::a_new_password_and_a_2fa_reset_hold_no_subscription`,
  `a_re_auth_lockout_turns_notifications_off_at_once`, `a_subscription_from_a_signed_out_session_is_not_sent_to`);
  Settings' Sign Out deletes this browser's own row and unsubscribes it (`auth::logout`, `register-sw.js`; pwa.md,
  "Sign Out takes this browser's notifications");
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
- **Opening the inbox reads it** (§1.3): `page` reads the page, marks exactly the shown ids read for this parent
  (`parent_events::mark_read`, an `INSERT OR IGNORE` into `parent_event_reads`), then counts; the rows keep their pre-mark `unread` for the existing dot and
  "Unread:" text. The gear carries `data-unread` = every unread event left (not only the filter's: the bell's count), so
  `register-sw.js` (unchanged, `[data-unread]`) clears this device's app badge; other devices follow their next push's
  `app_badge` or page. An older page (`?before=`) reads its own rows when opened; a `?device=` filter leaves the other
  phones' rows. Events are shared by every parent; their read state is each parent's (design 62 §5, below). This GET writes on
  purpose: the admin cookie is SameSite=Strict, so a cross-site GET carries no session (the module doc says so). Back
  to the inbox is a reload (no-store) and shows no dots - by design.
- **The tap on a notification** (r5 §1): `push::send_one`'s `navigate` is `push::with_marker(target, delivery)` -
  `?push=<id>` before any `#fragment`, `&push=` after an existing query (none today); the classic worker's
  `data.navigate` is the same string. `handlers::notifications::push_marker` is a `from_fn_with_state` layer on
  `admin_routes`, added before `.layer(require_full_auth)` so it runs inside it and sees `CurrentAdmin`; on a GET with a
  `push` key it calls `push::opened` (this admin's delivery only: `clicked_at` kept first, the subscription's
  `last_opened_at`, the event read for the subscription's parent; a summary or test has no event) and answers a 303 to the same path and query
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
  the route's own test), `music_spotify::migrations_73_to_95_in_order`.
- **The shutdown's last position** (2026-10-25, design 38 "Last position", migration `0097_shutdown_fix.sql`): the
  `off` may carry `location {lat, lng, accuracy_m, at_ms, provider}` (`models::PowerLocation`, read leniently - a bad
  one is no position, never a refused body); `device_api::power` checks it as a report's fix
  (`models::checked_location`, a failure logged and the position dropped), and when the plan keeps the off stores it
  with `zones::intake` and marks the row `device_locations.shutdown_at` (`zones::mark_shutdown`). Locate's fix row
  says "Last position before the phone went off" (`data-fix-before-off`) while that row is the newest fix, no
  status report came after the shutdown (+60 s) and the off's event (by its dedup key, the shutdown's instant) may be
  said - `parent_events::off_said`, the device page's "Off since" exclusions: not for "… is restarting", not while its
  push is held (`locate::before_off`, qa-last-position-code Q-5). The `params`' provider is `models::location_provider`'s
  word (N-3). `Phone::phone_off` puts `{"location": ..}` in
  the event's `params` and "Last position from 21:10: shown on Locate" in its inbox-only `detail`; title, summary and
  push unchanged (`told` keeps a `phone_off`'s stored words). `retention::prune` clears a `phone_off`'s params and
  detail after the phone's location retention. Location Settings' footer: a sudden power loss or airplane mode
  leaves nothing. Tests: `power::the_shutdowns_last_position_is_on_locate` (the hold, a newer fix, a report),
  `power::a_bad_shutdown_position_costs_the_position_only` (a dropped off stores no fix, the provider word),
  `power::a_known_restart_says_no_last_position`, `power::the_shutdowns_position_goes_with_the_location_retention`,
  the contract test, `locate::tests::the_last_position_before_the_phone_went_off`.

## Per parent (design 62 step 5, `docs/design/62-parent-accounts.md` §5)

- **Migration 0088** (`0088_parent_reads_and_prefs.sql`): `notification_prefs` (one row per account that saved its
  switches, `admin_id` primary key, the seven `push_*` columns; no row = every switch on - since 0099 but "App updates", below) and `parent_event_reads`
  (`(admin_id, event_id)`, both `ON DELETE CASCADE`, so retention's delete of an event takes its reads). Both seeded for
  every account that isn't removed, from the family's switches and the old `read_at`; `notification_settings`,
  `parent_events.read_at` and `idx_parent_events_unread` are dropped (a missed reader fails loudly).
- **Unread for me** = no `parent_event_reads (me, event)` row (`parent_events::UNREAD`, `unread_count`). The bell
  (`Bell::load(db, admin_id)`: Devices, Apps, Locate, Music, the Settings hub), the inbox's rows and count after marking
  (`notifications::row_from(n)`'s `LEFT JOIN`), a row's `open`, Mark All as Read, the phone summary's Activity and the
  push marker/`clicked` receipt (`push::mark_opened`, for the subscription's parent) all read and write the signed-in
  parent's own rows; an inbox row's delivery line ("Delivered to …", "Opened on …", "Not sent") names only the
  signed-in parent's devices (`delivery_lines(db, admin_id, ..)`, qa-62-step345-code P62b-1). A push's badge (`data.b`, `app_badge`) is the subscription's parent's count. Adding a parent and
  `reset-2fa`'s restore mark every event so far read for them (`read_everything`); Remove deletes their prefs and reads.
- **The push filter** (`push::queue`): active subscriptions of accounts that aren't removed, with
  `COALESCE(p.<switch>, <its default>) = 1` (1, "App updates" 0 since 0099) from `notification_prefs`; the emergency call goes to every such parent. Notify Me About
  reads and writes the signed-in parent's row (`INSERT … ON CONFLICT (admin_id) DO UPDATE`, only for an account not
  removed); its footer says "Just for you - each parent chooses their own."
- Tests: `tests::parents::inbox_read_is_per_parent`, `notify_me_about_is_each_parents`,
  `migration_0088_copies_to_active_accounts`; `tests::web_push::push_follows_each_parents_switches`,
  `push_tap_reads_for_that_parent_and_badges_their_count`; the older notification, web push, summary and power tests
  read `parent_event_reads`/`notification_prefs`.

## Zone events (design 64 step 1, migration `0091_safe_zones.sql`)

- `Kind::ZoneArrived`/`ZoneLeft` (`zone_arrived`/`zone_left`, after `PhoneOn`; TTL 3600, normal, foldable) share one
  switch, `push_zones` "Zones: arrived and left" - `Kind::switches()` lists each switch once for Notify Me About. They
  are the first kinds **stored as a key plus parameters** (`parent_events.params`, `ZoneParams`; `title`/`summary`
  empty) and worded where shown or pushed by `parent_events::told` (the inbox's and Activity's `inbox_row`, the
  sender's `send_due`); every older kind keeps its stored words. Pushed only while news (the confirming fix under 30
  min old), kept with the phone's location history, not 90 days. All of it in `zones.md`.
- **A summary that is only the row's own clock is dropped** where the row shows its time anyway (the summary's
  Activity, `activity::without_own_clock`: "15:42" or "Yesterday 15:42" against the row's `when`), and both row
  templates leave out an empty summary's " · ". A zone event's summary is just its time; an emergency call or a
  phone-off on an earlier day now shows only `when` there too. So a later kind must not put anything but its clock
  in a summary it needs shown in Activity (the code QA's open question 3, kept as a rule).

## Requests for more time (design r13 step 2, `docs/design/r13-launcher-rule-card-and-time-left.md` §2.5-§2.7, migration `0096_extra_time_request.sql`)

- **The route**: `POST /api/devices/time-request` (`handlers::device_api::time_request`, bearer, body at most
  `TIME_REQUEST_BODY_LIMIT` 1 KiB): `{id, asked_at_ms}` from the launcher's "Be om mer tid" in the anchor's
  notification. 200 `{"accepted":true}`, `{"accepted":false,"reason":"capped"}` or `{"accepted":false,"reason":"stale"}`
  (`TimeRequestAnswer`); 400 for a body that doesn't parse, an id that isn't 16-64 lowercase hex
  (`time_request_id_ok`) or an instant out of range; 500 on a DB error (the phone keeps its request and retries). The
  shapes are the shared contract `testdata/time_request.json` (byte-identical in the launcher's test resources).
- **The kind** `Kind::ExtraTimeRequest` (`extra_time_request`, last in `Kind::ALL`; normal urgency, TTL 3600 s,
  foldable) with its own switch `push_extra_time` "Asks for more time" (Notify Me About lists it from
  `Kind::switches()`, per parent, on by default for every parent, existing ones included - migration 0096's `DEFAULT 1`,
  no row = on). Stored as a key plus parameters like the zone events: key = the phone's request id (a retried POST is
  one row), `params` = `ExtraTimeParams {phone, at, state, left_minutes?, rule?}` with `state` `left`/`used_up`/`rule`/
  `unknown` (`TimeAsk::of` the phone's own newest report, when it is at most 15 minutes old: a rule that locks first - `rule` holds its `lock_reason`,
  never its name -, then today's screen time from `time_state`; never text from the request). `title`/`summary` empty;
  `told` words it: "Anna asks for more screen time" (`fit_name` to 60) / "17:21 · Screen time used up",
  "Yesterday 21:40 · Bedtime", "14:02 · 1 h 20 min left", "School", "A time rule", or the clock alone for `unknown`.
  Target `/devices/{id}/time#extra`. Kept `EVENT_DAYS` (90) like every kind.
- **The cap and the news rule** (in one `BEGIN IMMEDIATE` transaction): "today" is the phone's own day
  (`device_api::extra_time_day_zone`, qa-r13-rule-card-code Q-2: the zone its latest report names in `time_state.zone`,
  the zone its ledger and its own count of three go by; else Phone Setup's). A request asked before it isn't stored
  (`stale`; a future `asked_at` counts as now); the phone's `extra_time_request` rows of today with another
  key are counted through `datetime(happened_at)` against that day's start - at `EXTRA_TIME_REQUESTS_A_DAY` (3) the
  request isn't stored (`capped`, one `tracing::info!`), so a retried third request is taken, never a fourth; else
  `raise` with `Push::Now` while it is news (asked under `EXTRA_TIME_NEWS_MINUTES` 30 min ago at intake), `Push::No`
  after (inbox and Activity only). The phone keeps its own count of three too (design r13 D5).
- **Activity**: `activity::without_own_clock` strips a leading or trailing own clock case-insensitively, so the day
  after "Yesterday 21:40 · Bedtime" against `when` "yesterday 21:40" shows "Bedtime".
- **Device › Time** shows "Asked for more time at 17:21" (`data-asked`, a `.group` with one `.row.row-note` at the top
  of Extra Time Today, above the buttons) while `parent_events::open_extra_time_request` finds one open: the phone's
  newest request raised in the server zone's today, and no `time_lifts` row for the phone made at or after its
  `created_at` (both the server's `datetime('now')`, compared through `datetime()`; any lift counts, one in the same
  second as the request counts as its answer). A lift form already returns to `#extra`, so after +15 min the page lands
  there with its flash and without the row.
- Tests: `tests::time_request` (the contract's answers and copies, one row and the push by each parent's switch, the
  same id twice, a retried third, the fourth capped, 31 min old unpushed, yesterday's stale, bad ids, the Time page's
  row until a lift and a lift before the request leaving it), `parent_events::tests` (the words per state, a long name,
  the day after, the state from a report), `activity::tests` (the case-insensitive strip),
  `migrations_73_to_96_in_order`.

## Push wording (2026-10-25)

The user (2026-10-25): "Generelt kunne vi også forenklet web push meldingene? Mindre tekst". Branch `pwa-fixes-b`,
migration `0098_push_words.sql`. (0097 is branch `last-position`'s, built in parallel.) **To be read by the user before it ships.**

- **The rule**: a push's title is "<name>: <fact>", its body one short clause - the time, then at most one number
  ("07:02 · battery 64 %"). No second sentence, no "no shutdown seen", "last report …", "unlocked …", "off since …",
  "probably flat", "Too many wrong tries", where the PIN was used, or what kind of app it was. **The inbox and the
  phone summary's Activity keep the long words, unchanged** - the push is the short form of the same event.
  `TITLE_MAX` (60) and `SUMMARY_MAX` (100) stay; a long phone name is cut ("Anna-Sofie Anna…: emergency call").
- **How**: the stored-words kinds make both forms at raise (`parent_events::Phone::pushed`; `NewEvent.push_title`/
  `push_body`, stored in `parent_events.push_title`/`push_body`, NULL when empty); the kinds told from parameters
  (zones, a request for more time, the two app update kinds) are worded short where they are pushed
  (`parent_events::pushed` -> `told_as(Form::Push, ..)`; `told` is `Form::Inbox`). The sender (`push::send_due`) reads
  `pushed`; a row from before 0098 has no push words and pushes its inbox words as before. The summary and test
  pushes' words are `push::SUMMARY_TITLE`/`SUMMARY_BODY`/`TEST_TITLE`.
- Times are the phone's local time as before: "14:02" today, "Yesterday 19:14", "12 Oct 14:02".

| Kind | Before: title / body (also the inbox, which keeps it) | After: the push's title / body |
|---|---|---|
| `emergency_call` | Anna called an emergency number / At 14:02 (another day: Yesterday 19:14) | Anna: emergency call / 14:02 (Yesterday 19:14) |
| `pin_used` | Parent PIN used on Anna / Pause · 14:02 | Anna: parent PIN used / 14:02 |
| `pin_lockout` | Parent PIN locked on Anna / Too many wrong tries · until 14:45 | Anna: parent PIN locked / Until 14:45 |
| `foreign_access` | An app may read the PIN on Anna / 2 apps with device admin or accessibility | Anna: an app may read the PIN / 14:30 · 2 apps |
| `unseen_boot` | Anna started without Vibb One / Safe mode or a start cut short | Anna: started without Vibb One / 14:02 |
| `low_battery` | Battery low on Anna (14 %) / Not charging · 14:02 | Anna: battery low / 14:02 · battery 14 % |
| `phone_off` | Anna was turned off / 14:02 · battery 3 %, probably flat | Anna: turned off / 14:02 · battery 3 % |
| `phone_off` (Android: battery) | Anna shut down: battery flat / 14:02 · battery 1 % | Anna: battery flat, shut down / 14:02 · battery 1 % |
| `phone_off` (Android: too hot) | Anna shut down: too hot / 14:02 · battery 64 % | Anna: too hot, shut down / 14:02 |
| `phone_off` (a restart; never pushed) | Anna is restarting / 14:02 · battery 64 %, charging | Anna: restarting / 14:02 (stored only) |
| `phone_on` (a restart) | Anna restarted / 14:20 · battery 64 % (· unlocked 14:23) | Anna: restarted / 14:20 · battery 64 % |
| `phone_on` (after a shutdown) | Anna was turned on / 14:20 · off since yesterday 19:14, battery was 3 % | Anna: turned on / 14:20 · battery 64 % (the battery now; none reported: 14:20) |
| `phone_on` (no shutdown seen) | Anna was turned on / 14:20 · no shutdown seen · last report 14:00, battery 5 %, probably flat | Anna: turned on / 14:20 (· battery 64 % when the start reported it) |
| `zone_arrived` | Anna arrived at Home / 14:02 | Anna: arrived at Home / 14:02 |
| `zone_left` | Anna left School / 14:02 | Anna: left School / 14:02 |
| `extra_time_request` (used up) | Anna asks for more screen time / 14:02 · Screen time used up | Anna: asks for more time / 14:02 |
| `extra_time_request` (time left) | Anna asks for more screen time / 14:02 · 1 h 20 min left | Anna: asks for more time / 14:02 · 1 h 20 min left |
| `extra_time_request` (a rule) | Anna asks for more screen time / 14:02 · Bedtime | Anna: asks for more time / 14:02 |
| `app_update_available` (new, below) | - (inbox: Signal 7.2.0 is available / 14:02 · 7.1.4 → 7.2.0) | Signal: update available / 14:02 · version 7.2.0 |
| `app_updated` (new, below) | - (inbox: Vibb One updated on Anna / 14:02 · 0.36.0-rc.7 → 0.36.0) | Anna: Vibb One updated / 14:02 · version 0.36.0 |
| summary (more than 3 at once) | New notifications / More than 3 at once - open Notifications to see them | New notifications / More in the inbox |
| test (Send a Test) | Test from Vibb Phone / If you see this, notifications work on iPhone · Safari | Vibb Phone: test / Notifications work on iPhone · Safari |

- Open for the user: the PIN's place (Pause, Lock screen, Time lock) and an extra-time request's rule ("Bedtime")
  left the push by the "at most one number" rule; either could come back as a word after the time if it is missed.
- Tests: `parent_events::tests::the_push_words_are_short` (every row of the table, the long name, a pre-0098 row),
  `tests::power` (the inbox keeps "Anna was turned off", the push says "Anna: turned off"), `tests::web_push`,
  `tests::zones`, `tests::time_request` (the pushed titles), `migrations_73_to_99_in_order`.

## App update notices (2026-10-25, migration `0099_app_update_notices.sql`)

The user (2026-10-25): "sende notifications når apper lagt til via pwa blir oppdatert? Trenger ikke nødvendigvis være
push varsel ... kanskje man kan få valget". Branch `pwa-fixes-b`.

- **Two kinds** (`parent_events::Kind::AppUpdateAvailable`/`AppUpdated`, `app_update_available`/`app_updated`, last in
  `Kind::ALL`; normal urgency, TTL 43200, foldable), stored as a key plus parameters like design 64's zones
  (`AppUpdateParams {app_id, app, from?, to, at, phone?}`: the catalog name and versions as they were, cut to 40;
  `title`/`summary` empty), worded by `told`/`pushed` (`app_update_told`). Kept 90 days like every kind.
- **`app_update_available`** - the catalog's event, **no phone** (`device_id` NULL): raised by
  `tracked_apps::sync_release` right after a new release is stored (the sweep and Check Now share it; GitHub and
  F-Droid apps, never a manual upload) over a previous one - an app's first release raises nothing
  (`update_available_event`) - and only when it is **ahead** of it (`release_ahead`, qa-pwa-fixes-b-code Q-1: a source
  that goes back - prereleases turned off after an RC, the newest release deleted, an F-Droid repo dropping its newest
  build - stores an older release, which is no update): F-Droid by the versionCode (the asset id); GitHub by the
  versions when both read as X.Y.Z with an optional `-rc.N` (`install_now::version_order`, an RC before its release),
  the same version under a newer asset id being a rolling tag's new build, else by the asset id (GitHub's upload
  order). Key `<app id>:<tag>@<asset id>`, the release exactly as the row stores it, so a sweep or a
  Check Now that finds it again raises nothing: one event per release, never per sweep. `from`/`to` are
  `tracked_apps::version_of` of the old and new tag ("1.0.56" -> "1.0.57"); a rolling tag's new build has no `from`.
  Target `/apps/tracked/{id}`. Inbox "Signal 7.2.0 is available" / "14:02 · 7.1.4 → 7.2.0"; push "Signal: update
  available" / "14:02 · version 7.2.0". In the inbox under every phone only (a `?device=` filter leaves it out), with a
  grey avatar and the app's letter (`notifications::inbox_row`).
- **`app_updated`** - per phone and version: `device_api::status` compares the report with the previous one
  (`PreviousReport` now reads `app_version`, `app_version_code`, `music_state_json`) through
  `tracked_apps::note_app_updates`: a versionCode higher than the previous report's that is the catalog row's stored
  release (`release_installed`: the launcher's by its tag's code, `install_now::launcher_tag_version_code`; the music
  app's by the version its code reads as, `device_pages::version_of_code`, against the tag's). Key `<app id>:<code>`;
  `from` the previous report's versionName (the launcher) or its code's version (the music app). No previous report (a
  new phone) raises nothing. Target `/devices/{id}/apps`. Inbox "Vibb One updated on Anna" / "14:02 · 0.36.0-rc.7 →
  0.36.0" (Activity: "0.36.0-rc.7 → 0.36.0"); push "Anna: Vibb One updated" / "14:02 · version 0.36.0". **Only the
  launcher and the music app**: the phone's report names no other app's installed version (`installed_apps` carries
  package, label, installer) - an `app_updated` for every catalog app needs the launcher to report each one's
  versionCode (a launcher change, both dirs, not built).
- **The switch** "App updates" (`push_app_updates`, `parent_events::APP_UPDATES_SWITCH`, one for both kinds) is the one
  switch **off by default**: 0099's `DEFAULT 0` for every existing row, and `Kind::switch_default` for a parent with no
  row - `push::queue`'s `COALESCE(p.<switch>, <default>)` and Notify Me About's `read_switches`. Always in the inbox;
  pushed only for a parent who turned it on. Per parent (design 62 step 5).
- **Migration 0099** also makes `parent_events.device_id` nullable: SQLite can't loosen a NOT NULL, and sqlx runs a
  migration in a transaction with foreign keys on (no `PRAGMA foreign_keys = OFF`), so `parent_events`,
  `push_deliveries` and `parent_event_reads` are rebuilt together - each renamed (a child's REFERENCES follow), made
  anew with today's columns, copied with their ids, the old children dropped before the old events (no ON DELETE runs),
  every index again under its name, and `sqlite_sequence` kept at the highest id ever given (an event or delivery id is
  never reused: a push's tag `e<id>`, a receipt's `d`). A catalog event's key is the partial unique index
  `idx_parent_events_catalog (kind, dedup_key) WHERE device_id IS NULL`; `raise` is `ON CONFLICT DO NOTHING` without a
  target, so both keys hold. The inbox's and Activity's rows read the phone with a LEFT JOIN (`notifications::Row`'s
  phone and name are `Option`).
- Tests: `tests::app_updates` (one event per release over three raises, the inbox and its filter, the target; a
  phone's report raising `app_updated` once for the launcher and the music app, never for a first report or a code
  that isn't the release; the switch off by default with no row, the push only once on; 0099 keeping events,
  deliveries, reads, ids, the counter and the cascades), `handlers::tracked_apps::tests::a_new_release_is_one_notice_never_one_per_sweep`
  (a real F-Droid sync: none for the first release, one for the next, none for the sweep and the Check Now after),
  `a_release_is_installed_by_its_versioncode`, `an_older_release_stored_over_a_newer_one_is_no_notice` and `only_a_release_ahead_is_an_update` (Q-1), `parent_events::tests` (`an_app_update_is_told_from_its_parameters`,
  the push words, the switches).

## The push icon (server round C, 2026-10-25, branch `pwa-fixes-c`)

- A push about a phone carries the phone's own picture as its icon (`pwa.md`, "A phone's own picture"), else the
  app's: `push::send_due` reads the event's phone's `devices.avatar_hash` (`LEFT JOIN devices` on
  `parent_events.device_id`) and `payload_json` puts `notification.data.icon` = `/phone-avatars/<hash>` or
  `push::APP_ICON` (`/static/icons/icon-192.png`); a summary, a test and a catalog event (no phone) take the app's.
- **In `data`, not as the notification's own `icon`**: only the worker's legacy path shows an icon we choose (Chrome,
  Firefox: `templates/sw.js` `iconOf(data)` - a path on this server, else the app's icon; another site's picture is
  never shown, `jstest/sw.test.js`). Safari's declarative notification is shown by webpushd itself with the app's own
  icon whatever the payload says, so the declarative payload keeps to the keys WebKit reads - a new top-level
  notification key was not worth the risk on the one path iOS 26 displays (0.29.6).
- **Fetched without the session**: the browser loads a notification's icon for the worker, outside any page, where
  the SameSite=Strict cookie isn't reliably sent; so `/phone-avatars/<hash>` needs no session (an unguessable
  content hash, only while a phone names it, the admin gate still in front). A same-origin path, resolved by the
  worker against its own origin.
- Tests: `tests::avatars::a_push_about_the_phone_carries_its_picture_as_the_icon`, `push::tests::the_payload_switch`,
  `jstest/sw.test.js` ("a push shows its phone's picture as the icon, never another site's").

