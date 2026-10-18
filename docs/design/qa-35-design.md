# QA 35 design review - notifications to the parent, phase 1: the inbox and Web Push (2026-10-18)

Scope: `docs/design/35-parent-notifications.md` at `bb020378` (branch `design-35`). I read it against the root
`CLAUDE.md`, PLAN.md's backlog item "Notifications to the parent, phase 1" and `server/CLAUDE.md`, and checked it
against this code: `server/src/main.rs` (`build_admin_router`'s `public_routes` / `require_full_auth`, the rustls
provider, `AppState`), `Cargo.toml`/`Cargo.lock`, the `ring` 0.17.14 sources (`lib.rs`, `rand.rs`,
`deprecated_test.rs`, `testutil.rs`, `hkdf.rs`, `ec/suite_b/ecdsa/signing.rs`, `ec/suite_b/ecdh.rs`),
`admin_auth.rs` (`csrf_guard`/`csrf_check`, `ADMIN_CSP`, `admin_headers`), `gate.rs`, `limits.rs`,
`handlers/parent_pin.rs` (`pin_events`), `handlers/window_relock.rs` (`previous_foreign_access`,
`log_foreign_access`), `handlers/device_api.rs` (the status intake, `boot_witness::log_line`, `last_seen_at`),
`handlers/calls.rs` (`last_emergency_call_at`), `handlers/devices.rs` (`connection_of`), `handlers/auth.rs`
(`sign_out_everywhere`), `handlers/backups.rs` (`strip_secrets_in`), `music_secret.rs` (`load`, `create_key_file`),
`music_net.rs` (`Source`), `attention.rs`, `templates/sw.js`, `static/register-sw.js`, the five tab templates, and
the launcher's `calls/CallSystem.kt` / `KidInCallService.kt` (where `last_emergency_call_at` comes from). I also
read design 36 at `ddbb2396` (branch `design-36`) for the migration numbers. QA wrote no product code.

**Verdict: buildable after a fix round to the note.**
- The architecture is right. The events are server rows, there is one `raise` with `ON CONFLICT DO NOTHING`, the
  endpoints are an allow-list, the receipt is a per-delivery token, and the health is computed when read.
- In-house crypto on `ring` is the right call. `ring` has every primitive the note needs (Confirmed).
- What has to change before code: two High findings and a handful of Medium ones, and the note can drop about a
  third of the push machinery.

## High

**H1 - Any 403 `BadJwtToken` x3 marks the subscription `gone`. A wrong server clock then turns off every parent
device for good.**
- **Where:** §4.4.
- **Why it happens.** `BadJwtToken` is our own failure, not the subscription's:
  - The commonest cause on this hardware is the clock. A Raspberry Pi has no RTC. After a power cut it boots on
    fake-hwclock's last-shutdown time, or 1970, until NTP syncs. A status report or the offline check can raise an
    event in that window, so the JWT's `exp` is already in the past.
  - The JWT is then cached "for an hour" (§4.3), so the bad token goes on being sent after NTP has synced.
  - Three refusals in a row turn every subscription `gone`. Each parent device then needs a manual Turn On again,
    which is exactly the silent-failure mode the delivery-health work is meant to prevent.
  - Mozilla answers a bad JWT with 401, and FCM answers a key mismatch with 403 and no Apple `reason`. The table
    doesn't say what happens to either.
- **Fix to the note.** Only 404 and 410 make a subscription `gone`.
  - A real key mismatch is already caught by `vapid_fp` at startup (§4.1). Drop the `VapidPkHashMismatch` →
    `gone` rule too, or keep it only for Apple's exact reason after the startup sweep.
  - **401 or 403:** drop the cached JWT for that `aud`, retry once with a fresh one, then mark the delivery
    `failed`. Log the reason, the `apns-id` and the host.
  - Show it as a server-level problem, not a device one. One warning row on `/notifications`: "The push service
    refused the server's signature - check the server's clock".
  - Cache the JWT by wall-clock `iat`, and re-mint it when `now` is outside `[iat, iat + 1 h]`. A clock jump then
    re-mints it instead of keeping a stale one.
  - Add a test: a 403 `BadJwtToken` leaves the subscription `active`, and the retry carries a new JWT.

**H2 - On the iPhone the page says "Not available in this browser" instead of "Add Vibb Phone to the Home Screen
first".**
- **Where:** §5.2. This is the user's main target device.
- **Why.** iOS Safari exposes the Push API only to Home Screen web apps. In a plain Safari tab, `PushManager`
  (and `Notification`) are missing, so the first state in the list catches the iPhone. The user's requirement is
  that the page says how to add the app to the Home Screen.
- **The second half: the user gesture.** "`requestPermission()` first, then `pushManager.subscribe` after its
  promise" can lose the gesture on WebKit. So can the implied `await navigator.serviceWorker.ready`. Apple's rule
  (PLAN) is "subscribe from a tap's handler at once".
- **Fix to the note.**
  1. **Test order.** First, `navigator.standalone === false` (the property exists only on iOS/iPadOS WebKit) →
     "Add to Home Screen first". Then no `PushManager` or not a secure context → "Not available". Then
     `denied` → "Blocked". Then known or unknown.
  2. **Registration.** Fetch it once at page load (`navigator.serviceWorker.ready`, kept in a variable). Turn On
     is disabled until it has resolved.
  3. **The click handler** calls `reg.pushManager.subscribe({userVisibleOnly: true, applicationServerKey})`
     synchronously, as its first call. `subscribe` asks for the permission itself on Safari, Chrome and Firefox,
     so there is no separate `requestPermission()`.
  4. **`applicationServerKey`** is passed as a `Uint8Array` decoded from `data-key`. Older Safari doesn't take a
     base64url string.
  5. **Tests.** `jstest/notifications.test.js` asserts the order (an iOS-like fake without `PushManager` and with
     `standalone: false` shows the Home Screen steps) and that `subscribe` is the click's first call.
- **Verify on iOS 18.4 and 16.4:** the prompt appears from `subscribe` alone.

## Medium

**M1 - Migration numbers clash with design 36.**
- Design 35 takes `0078` (step 1) and `0079` (step 2).
- Design 36 §3 says it takes "0079 if design 35 has landed 0078, else 0078". After 35 step 1, 36 would take `0079`,
  and 35 step 2's `0079_web_push.sql` collides with it (two version-79 migrations, so sqlx refuses).
- **Fix: one migration for all of 35, `0078_parent_events.sql`, in step 1.** It holds `parent_events`,
  `notification_settings`, `push_subscriptions` and `push_deliveries`.
  - Empty push tables in step 1 cost nothing, and step 2 then adds no migration.
  - Design 36 takes `0079` (or the next free one at its merge), and its note is changed to say so.
  - Alternative, if the tables must wait for step 2: reserve `0078` and `0079` for 35 and give 36 `0080`, stated
    in both notes. Either way, add every new file to the migrations-in-order test.

**M2 - The `ring` details the note gets wrong or leaves out.**
- **`ring` is not a direct dependency.** It comes in through `rustls`'s `"ring"` feature (`Cargo.toml:61`;
  `Cargo.lock` has `ring 0.17.14`).
  - `use ring::...` needs `ring = { version = "0.17", default-features = false, features = ["alloc"] }` in
    `[dependencies]`.
  - The lock file is unchanged: no new crate and no new verification entry. The note's "no new crate" is true, but
    it must name the Cargo.toml line.
- **`ring::test::rand::FixedSliceSequenceRandom` is usable, but it is not a supported API.**
  - It is `pub` with no feature gate (`lib.rs:173-177`: `pub mod deprecated_test; pub use deprecated_test as test`).
  - It is also `#[doc(hidden)]` and `#[deprecated(note = "internal API that will be removed")]`. It needs
    `#[allow(deprecated)]`, and its `Drop` asserts that `fill` was called exactly once per slice. That pins the
    order of draws: the ephemeral key's 32 bytes first, then the 16-byte salt. Say so in `encrypt`'s doc comment.
- **The suggested alternative, a test-only `SecureRandom`, is impossible.**
  - `SecureRandom` is sealed: `pub trait SecureRandom: sealed::SecureRandom`, where `sealed` is `pub(crate)`
    (`rand.rs:23,62`).
  - `agreement::EphemeralPrivateKey::generate` takes `&dyn SecureRandom`, but no crate outside `ring` can
    implement it.
- **The fake browser in the TestApp depends on the same deprecated type.**
  - `ring` has no ECDH with a static, imported private key. The only key type is single-use
    `EphemeralPrivateKey`.
  - "Decrypts each body with the fake browser's fixed key" (§8) therefore means building a fresh
    `EphemeralPrivateKey` from `FixedSliceSequenceRandom` over the fixed `ua_private`, once per decryption.
- **Fix to the note.**
  - One `#[cfg(test)]` helper in `webpush.rs` (`fixed_rng(&[&[u8]])` and `fake_browser_decrypt`), the only place
    with `#[allow(deprecated)]`.
  - Write the fallback down: if a `ring` bump removes the type, only tests break, and they move to a `p256`
    dev-dependency (test-only supply chain).
- **PRK_key and PRK can't be asserted.** `ring::hkdf::Prk` is opaque. Assert ecdh_secret (from the fixed
  agreement), IKM, CEK, NONCE, the header and the ciphertext, which Appendix A lists too. Recomputing the PRK with
  `hmac` would only test a second implementation.
- **The rest checks out.** `ECDSA_P256_SHA256_FIXED_SIGNING` gives the 64-byte r‖s that JWS ES256 needs.
  `EcdsaKeyPair::generate_pkcs8` / `from_pkcs8(alg, bytes, rng)` / `sign(rng, msg)` are as the note assumes, and
  `hkdf::KeyType` is not sealed, so a `Len(usize)` works (see Confirmed).

**M3 - The VAPID key is made "at first need" by a GET render. Make it at startup, as the music key is.**
- **Why lazy creation is wrong:**
  - A file is created as a side effect of rendering a page.
  - Two first renders race, and the `OnceCell` covers only one process.
  - §4.1's own startup sweep, "every active subscription with another `vapid_fp` becomes gone", needs the key
    before any page is rendered. With a lazy key, a server whose key file was lost has no key at startup: the sweep
    either does nothing, or it marks everything gone against a key that doesn't exist yet.
- **Fix:** `push::load_key(file)` in `main`, next to `music_secret::load`, with the same semantics.
  - A missing or empty file is created, through `.tmp` and a rename, 0600, in a 0700 directory.
  - A non-empty file that doesn't parse is refused, never replaced, and push stays off with the log line naming the
    file.
  - `AppState.push_key: Option<Arc<VapidKey>>` replaces the `OnceCell`. The sweep runs right after the load.
- **Shared helpers:** move `create_key_file`'s directory and tmp handling into the shared helper the note mentions
  (`keys.rs`). The VAPID key is binary DER, the music key base64 text, so the helper takes bytes.

**M4 - Phase 1 is over-built. What can go without losing a requirement** (the inbox, push, receipts, delivery
health, the switches):
1. **The IndexedDB retry queue in the worker** (at most 20, retried at the next push and click), plus the server's
   clamp of the receipt's `at` → **drop both**.
   - Health already resets on *any* receipt (§6), so the next push that gets through clears the warning anyway.
   - The queue only back-fills `receipt_at` on older deliveries, and those can honestly read "not confirmed"
     (M6).
   - `receipt_at` becomes the arrival time. The worker loses its IndexedDB code and its tests, and the receipt
     loses `at`.
2. **Per-subscription counters** (`unacked`, `first_unacked_at`, `last_accepted_at`, kept in step by every send and
   receipt) → **derive them from `push_deliveries`**.
   - The query: `count(*)` and `min(accepted_at)` of `status = 'accepted' AND receipt_at IS NULL AND accepted_at >
     COALESCE(sub.last_receipt_at, '')`, plus `max(accepted_at)`, on an index `(subscription_id, accepted_at)`.
   - The deliveries' 30-day retention is longer than the 24 h rule. There are no counters that can drift and no
     multi-row updates in the receipt. Keep only `last_receipt_at` on the subscription (it survives pruning).
3. **The token bucket** (6 per 10 min, state per subscription, waiting on a token) → **a cap per pass**.
   - When `send_due` finds more than 3 due event deliveries for one subscription, it sends the newest 2 and one
     summary ("5 new notifications").
   - The rest become `dropped`/`folded`. An `emergency_call` is always sent on its own.
   - The bursts that matter come from one intake: a phone that was offline for a week posts 20 PIN events in one
     `pin-events` call. The dedupe keys stop repeats, so a time-based bucket buys little.
   - The summary is a visible push with a *unique* `tag` (§5.3 gives it a fixed one). A second summary with the same
     tag would silently replace the first, because `renotify` is off.
4. **The receipt's own limit bucket** (60 per minute per client key; nothing like it exists on the admin listener)
   → **reuse `limits::ip_failed`/`ip_blocked`** for receipts answered 404.
   - A 128-bit token can't be guessed, and the gate already admits only tailnet and loopback clients.
5. **`AppState.started_at`** (it doesn't exist) → a `std::time::Instant` local to `run_offline_check`. Nothing else
   reads it.
6. **Optional: no `Topic` in phase 1.**
   - Coalescing at the push service drops the earlier pushes of a phone and kind while the parent's device is
     offline. Those never get a receipt, so they show as "not confirmed" and push the health toward "may not be
     showing".
   - The inbox is complete anyway. Dropping Topic removes the HMAC topic key and its tests.
   - QA leans to dropping it. Keeping it is also defensible, but if it stays, §6 must say that coalesced
     deliveries have no receipt by design.

**M5 - The emergency call reaches the parent only at the phone's next status report. The note doesn't say so.**
- **What the code does.** The launcher records the call (`KidInCallService.kt:117` →
  `CallPrefs.recordEmergencyConnected`) but doesn't trigger a report. `last_emergency_call_at` travels with the next
  status report: the 30-minute backstop, which inexact alarms stretch to about 52 min, or the next other trigger.
- **One call per report.** The phone reports only the *last* call, so two emergency calls between reports make one
  event.
- **PIN events** ride the phone's next `pin-events` POST, with the same caveat.
- **Why it matters.** "Server only" is a stated non-goal, and the most urgent, always-on kind is the one this hurts.
- **Fix to the note.** State the latency in §2.1 and in the Notify Me About footer ("as soon as the phone next
  reports - usually within half an hour").
  - Add user question 1 below: either a small launcher follow-up (an immediate status report when an emergency
    call ends, and after a stored PIN lockout) or accept the latency for phase 1.
  - QA recommends the follow-up as its own small step after step 1 (launcher only, no API change: the report
    already carries the field).

**M6 - The inbox doesn't show which pushes were shown, which PLAN asks for.**
- PLAN: the worker "posts a receipt when it shows a notification, so the server knows which pushes arrived (shown
  in the inbox)". The note stores `receipt_at`/`clicked_at` but never renders them.
- **Fix.** Step 2 adds one line per inbox row from that event's deliveries (one grouped query for the page's 100
  rows, by `event_id`):
  - "Shown on iPhone · Safari" when any delivery has `receipt_at`;
  - "Sent, not confirmed" when one is `accepted` with no receipt;
  - "Not sent" when it `failed`;
  - nothing when there was no subscription.
- The test grid gains the three states.

## Low

**L1 - §5.3 payload: fields to verify on iOS 18.4, and the split.**
- **What I recall of the WHATWG declarative push message:**
  - top level: `web_push: 8030` (required), `notification` (required), `mutable` (bool) and `app_badge`;
  - in `notification`: `title` and `navigate` (an absolute URL, both required), plus `body`, `lang`, `dir`, `tag`,
    `icon`, `silent`, `data` and the rest of `NotificationOptions`.
- WebKit's announcement example put `app_badge` *inside* `notification`, as a **string**. The spec may say
  top-level and a number. Every field placement and type above is "verify on iOS 18.4".
- **Fix to the note.**
  - Keep the one payload. Chrome and Firefox read it as plain data through `event.data.json()`. A Safari that
    supports it dispatches `push` to the worker first because of `mutable: true`, and shows the declarative
    notification only when the worker shows nothing.
  - So the only risk is a missing receipt, which is an honest "not confirmed". It is never a double notification,
    *if* the worker always calls `showNotification` inside `waitUntil`.
  - Move the badge count into `notification.data` (`"b": 3`) for the worker's `setAppBadge`, and drop the top-level
    `app_badge` until the device check confirms where Safari wants it. A badge missing in the fallback is harmless;
    a wrong field could make Safari reject the declarative parse and fall back to the "Something new" path.
  - Say that a declarative-fallback display leaves no receipt.

**L2 - §2.1 emergency call: the dedupe key and the 24-hour rule.**
- **The instant is stable today.** `lastEmergencyCallMs` is the max of the call log's start and the recorded
  connect time, and the record survives reboots. So the exact instant works as `dedup_key`.
- **The 24-hour rule uses the phone's timestamp against the server's clock.** A phone whose clock is days behind
  would never raise its most urgent event.
- **Fix.** Raise when the instant is newer than the previous report's `last_emergency_call_at` for this device,
  read with the same "previous row" query style as foreign access.
  - Keep the 24-hour rule only when there is *no* previous report.
  - An upgrade then announces nothing old, because the previous row carries the same instant, and no clock
    comparison remains.
  - The UNIQUE key still covers parallel reports.

**L3 - §2.3 offline check.**
- **The key.** Use the last *contact* (`connection_inputs`' newest, to the second) rather than
  `devices.last_seen_at`. The latter moves only on status reports (`device_api.rs:1225`), so a phone that came back
  on its stream alone and fell silent again would reuse the old key and never be announced.
- **The words.** "Anna offline for 6 h" is wrong for a phone that has been silent for weeks. The first deploy
  announces every long-dead phone once. Make the words "Anna has been offline since 12 Oct 08:12" (from the
  contact time), and the summary "No contact for 3 days".
- **Clearing.** Say it explicitly: nothing clears when the phone comes back. The event stays as history, and
  there is no "back online" event.

**L4 - §4.3 JWT cache key.**
- `sub` falls back to the *subscription's* `origin`, so two subscriptions on the same push service from two origins
  (the ts.net name, and the LAN name through Caddy) need two JWTs.
- Key the cache by `(aud, sub)`, or make `sub` one value per server (`VAPID_SUBJECT` else the first subscription's
  origin).
- `aud`'s ":port when not 443" can only happen in tests, since the allow-list admits port 443 only. Say so or
  drop it.

**L5 - §5.2 and §2.2 input edges.**
- **A missing `Origin` on subscribe.** `csrf_check` passes `Sec-Fetch-Site: same-origin` without looking at
  `Origin`, so "the CSRF guard already matched it" is not true. Refuse it with a flash ("This browser didn't say
  where the page came from"); every browser that has Push sends it.
- **`target` must reject `//`.** "A path starting `/`" also admits `//evil.example`, a protocol-relative URL, and
  `open` 303s to it. Check `starts_with('/') && !starts_with("//")` at raise, and again in the worker.

**L6 - §7 Backups and "Sign Out Everywhere".**
- **Backups.** `push_subscriptions` holds each browser's `auth` secret and goes into every backup zip. A restore
  makes them `gone` anyway, because the key isn't backed up. Add `DELETE FROM push_deliveries; DELETE FROM
  push_subscriptions` to `strip_secrets_in` (deliveries first; that connection doesn't enforce foreign keys). One
  line, and "no secrets in backups" stays literally true.
- **`reset-2fa`.** The CLI's `reset-2fa` (`admin_auth.rs:338`, which also bumps `session_epoch`) means "my
  authenticator or device is lost" too. Delete the subscriptions there as well, or say why not.

**L7 - §8 The test seam.**
- The fake push service as an axum server on 127.0.0.1 needs a `#[cfg(test)]` bypass of *both* the host list and
  `https`.
- The house pattern is a trait seam: `music_net::Source` is canned in `AppState` for the tests.
- **Fix.** Use `push::Transport` (`send(PushRequest) -> PushAnswer`, headers and body in, status and headers
  out). The real one is reqwest with `redirect::Policy::none()`. The test one records requests, decrypts with
  `fake_browser_decrypt` (M2) and answers from a script.
- `allowed()` then stays strict in every build, and there are no sockets and no ports.

**L8 - §2.4 "A switched-off kind is neither stored nor pushed".**
- PLAN says "listing every event", and also "each switchable".
- QA's position: keep the note's choice. It is the least code, and the switch means "I don't care about this", so
  an unread count growing with things the parent switched off would be noise. The Security log still keeps the PIN,
  foreign-access and boot rows.
- The switch group's footer must say it ("Off: not in Notifications and no notification; still in the Security
  log"). User question 2 below.

**L9 - §6 False "may not be showing".**
- A parent's iPhone with Tailscale off (common on iOS) shows every push but can't post receipts. With "or the oldest
  unacked > 24 h", one push on such a day raises the warning.
- The rule is the user's (PLAN: "3 in a row, or none for a day"), so keep it. Put "Is Tailscale on on that device?"
  *first* in the ⓘ, and add it to the DEPLOY.md note.

## Nits

- **N1** In §2.1, `parent_pin::clean_event` doesn't decide "newly stored". `handlers::parent_pin::pin_events` does,
  from the `INSERT OR IGNORE`'s `rows_affected() == 1`.
- **N2** The Locate tab is `/devices/locate` (`device_locate.html`). Name the template that gains the `.title-bar`,
  along with `settings_hub.html`.
- **N3** §4.2 says "pure (no I/O)" for `webpush.rs` but lists `allowed` and `topic` there. That's fine, but `topic`
  needs the key bytes passed in. Say "pure; keys passed in".
- **N4** §5.1's `bell: Bell` is passed by seven handlers (five tabs, the Settings hub row, `/notifications`). A
  `Bell::load(&db)` with one partial-index `COUNT` is cheap, so no cache is needed. Say that so nobody adds one.
- **N5** §4.3: reqwest's `gzip` feature adds `Accept-Encoding: gzip` to the push POSTs. It's harmless, but the
  header test shouldn't pin the exact header set.

## Confirmed

- **RFC 8291 and 8292 are fully covered by `ring` 0.17's public API.**
  - `agreement::{EphemeralPrivateKey::generate(&ECDH_P256, rng), compute_public_key, agree_ephemeral}`: the
    65-byte uncompressed point and the 32-byte x-coordinate secret.
  - `hkdf::{Salt::new(HKDF_SHA256, ..).extract, Prk::expand(&[info..], Len).fill}`, with `KeyType` not sealed.
  - `aead::{LessSafeKey, UnboundKey::new(&AES_128_GCM, cek), Nonce::assume_unique_for_key}` with
    `seal_in_place_append_tag`.
  - `signature::EcdsaKeyPair::{generate_pkcs8, from_pkcs8(&ECDSA_P256_SHA256_FIXED_SIGNING, der, rng),
    sign(rng, msg)}`, which gives the fixed 64-byte r‖s (ES256 for JWS), verified in tests with
    `UnparsedPublicKey::new(&ECDSA_P256_SHA256_FIXED, ..)`.
  - The `p256dh` check by one throwaway agreement works, since `agree_ephemeral` errs on a bad point.
  - §4.2's key info, `aes128gcm` header (salt 16, rs = 4096 u32 BE, idlen 65, keyid = as_public) and `0x02`
    delimiter are RFC 8291/8188-correct, and the 3072-byte cap fits one record.
  - RFC 8291 Appendix A is reproducible end to end through `FixedSliceSequenceRandom` (M2 for the caveats).
- **In-house beats `web-push`.**
  - That crate brings `ece` (OpenSSL by default, absent from the distroless image), a JWT crate and HTTP features.
  - `p256` from RustCrypto would be a new subtree too, while `ring` is already compiled and audited in this tree.
  - About 300 lines against a published test vector is the smaller risk. Using `ring` for all of `webpush.rs` (not
    a mix with the existing `aes-gcm`/`hkdf`) keeps it one library.
- One `raise` with `INSERT … ON CONFLICT DO NOTHING RETURNING id` on `(device_id, kind, dedup_key)`, with
  deliveries queued and `wake()` called after the commit. A crash mid-send re-sends at restart, and the per-event
  `tag` makes the browser replace rather than double it.
- The PIN events' key is the phone's `event_id`, in the same transaction as the security row. The foreign-access
  key (previous row id + hash of the new set) is the existing "appears/changes" edge and is race-safe. The unseen
  boot's `<count>:<last_at_ms>` is fine too.
- **The allow-list is the right four.**
  - `fcm.googleapis.com` covers Chrome, Edge-on-Android, Samsung Internet, Brave and Opera.
  - `updates.push.services.mozilla.com` covers Firefox on desktop and Android.
  - `*.push.apple.com` covers Safari, including `web.push.apple.com`. `*.notify.windows.com` covers Edge on
    Windows.
  - Label-boundary suffixes, no IP literals, port 443, no userinfo, no redirects, checked at subscribe and before
    every send. No escape hatch in phase 1 is right.
- TTL and Urgency per kind. "Never an invisible push": the worker's fallback notification on bad data, and test
  pushes that are always visible.
- **The receipt.**
  - A 128-bit token stored as SHA-256, 404 on a mismatch, one delivery's columns changed, `credentials: "omit"`.
  - It sits outside the session because the session can expire while the subscription lives. It must sit in
    `public_routes` (not under `require_full_auth`) with its own `DefaultBodyLimit` of 1 KiB; the note's "except
    the receipt" should say `public_routes` by name.
  - `csrf_guard` passes it (`Sec-Fetch-Site: same-origin` from the worker), and the admin gate still applies.
- **Security, as the note has it.**
  - The admin gate and CSRF on every route. Pages carry only the public key and endpoint hash prefixes.
  - Nothing secret is logged (host only). Titles come from typed values and the parent's phone name, and `detail`
    is never pushed.
  - "Sign Out Everywhere" deletes the subscriptions (and a password change doesn't).
  - A stolen subscription can't decrypt anything or push without the VAPID private key.
  - No CSP change: the worker is `'self'` and `connect-src 'self'` covers the receipt.
  - No `fetch` handler and no offline cache.
- The inbox's form-POST per row (no GET changes state), returning to its group with `leaves_the_page`, matches the
  rest of the PWA. The 303 to the target keeps history clean: Back returns to `/notifications`.
- The health warning on the Devices list as a top orange row, like `live_warning`/`login_warning`, is the right
  reading of PLAN's "an Attention row". Attention rows are per phone; a subscription isn't one.
- Retention of 90/30/30 days and the cascade on deleting a phone. Step 1 is shippable alone. The DEPLOY.md note
  covers outbound 443 from the Pi and the Docker network, `VAPID_SUBJECT`, the key not backed up, and the receipt
  needing the tailnet.

## Questions for the user (the note must add or change)

1. **Emergency call latency (new, M5):** in phase 1, the parent hears about an emergency call only at the phone's
   next report (usually within 30 min, at worst about 50). Accept that, or add a small launcher step (report at once
   when an emergency call ends or a PIN lockout is stored)? QA recommends the launcher step, right after step 1.
2. **Switched-off kinds (L8):** not stored at all (the note's choice, QA agrees), or stored as already-read so the
   inbox is complete while the bell stays quiet?
3. **"Parent PIN used" on by default** (the note's open question 1): QA agrees, on.
4. **Offline default** (the note's open question 2): 6 h, with the words changed to "offline since …" (L3).
5. **Migration order with design 36 (M1):** all of 35 in `0078` and 36 in `0079`? QA recommends yes.
6. **No `Topic` coalescing in phase 1 (M4.6)?** QA leans to no Topic, which keeps the code smaller and the health
   more honest.
