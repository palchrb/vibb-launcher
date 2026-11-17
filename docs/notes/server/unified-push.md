# App push: Vibb as the UnifiedPush distributor (design 108)

`docs/design/108-unified-push.md` (revision 2, variant A; the user's Q4 change of 2026-11-16: Element X keeps its Play
build). The phone's Vibb One is the distributor; this server is the push server behind the endpoints and a Matrix push
gateway. Code: `src/unified_push/` (its `README.md` lists what is ported from ntfy under Apache-2.0),
`src/handlers/app_push.rs`, migrations 0152 and 0153 (design 108b: `gateway_at`, `vapid_refused_at`).

## The routes

- **Public, on the phone listener only** (`unified_push::routes`, merged in `build_device_router`; the admin listener
  never serves them - `tests::listeners::up_routes_only_on_the_phone_listener`): `POST /up/{secret}` (RFC 8030
  publish), `GET`/`POST /_matrix/push/v1/notify` (gateway discovery and notify). Behind `unified_push::edge`, not
  `limits::device_edge`, in this order (R1-SR M1): the client first - 4 requests at once per client (`net::limit_key`
  of the trusted-proxy client, as the rest of the phone listener counts) and its request bucket, 40 then 20 a second
  (L3) -, then the body (at most 256 read at once, within 3 s, 4097 bytes at most: 413 above 4096), and only then one
  of the 32 handler slots and a 10 s answer; the device API's headers. A slow body never holds a handler slot, and
  Caddy's `request_buffers 8KiB` (DEPLOY.md) keeps slow clients on the proxy. No outbound request is ever made for a
  push (no open relay, no SSRF).
- **The device API** (bearer, `device_routes.rs`): `POST /api/devices/up/registrations` `{reg, secret, package,
  vapid?}` -> `{endpoint}` or 409 `{refused}`; `DELETE /api/devices/up/registrations/{reg}` -> 204;
  `POST /api/devices/up/messages` `{ack}` -> `{messages, more}`. Shapes: `testdata/unified_push.json`, shared with the
  launcher.

## The answers (§3.4)

`/up/`: 201 + `Location: <origin>/up/m/<key>` + `TTL`; 400 empty; 413; 410 for a tombstoned secret (404 when the body
is a Matrix notify, L1) and for a never-seen one; 429 + `Retry-After`; 507 when the store is full. A transient refusal
is a 5xx, never a 4xx (Mastodon deletes the subscription on a 4xx but 408/429); gone is 410 (Misskey). The gateway:
200 `{"rejected":[]}`, or the pushkey in `rejected` when it isn't under `<origin>/up/` (the current origin, or one
it replaced within that one's 30 days - every replaced origin keeps its own, `up_previous_origins`, R1-SR L7) or its
secret is gone or never seen; 400, 413, 429, 507 pass through.
Discovery: 200 `{"unifiedpush":{"gateway":"matrix"}}` while an origin is set, else 404. The server-only rows:
`testdata/unified_push_gateway.json` (`tests::unified_push::gateway_rows`).

## Who pays, and the ban

Every push is charged to the phone (120 burst, then one per 5 s - ntfy's sustained rate, R1-SR L2: each push wakes
the phone, so a leaked endpoint's steady flood must not drain the battery) and its registration (30 burst, one per
2 s) - ntfy's "rate visitor", never the sending IP (`rate.rs`). Device › Apps calls an app noisy from
`app_push::NOISY_PER_DAY` (300) pushes a day, averaged over at least a day since its oldest registration. Only never-seen secrets count toward a client's ban (30 in 10 min, then
429 for 10 min, UP's own map); the secret is looked up first, so a ban never blocks a resolving secret, and a
tombstoned one is answered without counting.

## The store (§3.5, §3.6)

`up_registrations` holds the SHA-256 of each secret, never the secret. Every deletion leaves a 30-day tombstone in
`up_gone`, written by the trigger `up_registrations_tombstone` (so a phone deleted through `ON DELETE CASCADE` is
covered too). `up_messages`: one row per message until acknowledged or expired (TTL at most 24 h); 100 per
registration and 500 rows / 1 MiB per phone (the oldest dropped - for the phone's cap, of its registration with the
most rows -, counted in `dropped`), 64 MiB in all (507); `tests::unified_push::the_queue_caps_and_ttl_zero`. A TTL 0
push is dropped while the phone's stream is closed and kept 30 s while it is open. A Matrix body is stored minimised
by an allow-list (Q8, R1-SR L6, `matrix::KEPT`): `notification.event_id`, `room_id`, `counts`, `prio`, `type` and
each device's `app_id`, `data`, `pushkey_ts`, every kept member with its bytes as sent - Element X's
`PushDataUnifiedPush` reads only the first four; the pushkey (the endpoint) is dropped. Also when it came to `/up/`
through an outside gateway; `outside_gateway_at` is noted only for a queued push, at most once a minute (L1). Nothing per message is logged
(`tests::unified_push::nothing_logged`), and the security log keeps `/up/…` for any `/up/` path
(`audit::clip_path`, L5). Retention (`unified_push::store::prune`, hourly): expired rows, the registrations of a phone
unseen 30 days (`devices.last_seen_at`), tombstones past 30 days.

## The nudge

`AppState::unified_push.up_notify`, a broadcast of its own; `commands_stream` merges it as `event: up` / `data: up`
(a lag sends only `up`). Coalesced per phone at the sender on the trailing edge (`notify.rs`, 250 ms): the last push of
a burst is always followed by an `up`. No capability gate: only a distributor makes registrations.

## Registration

`POST /api/devices/up/registrations` runs its checks and its insert in one `BEGIN IMMEDIATE` transaction (R1-SR L4),
so two at once can't both pass the caps. The churn limit (`busy`) is checked and counted under one lock: 20 new
registrations of one app on a phone an hour, and 60 of the phone across its apps (L5); a counted one whose insert
fails is taken back.

## The policy and the allowlist

`unified_push` `{on, origin, regs, stopped}` only to a launcher claiming `unified_push_v1` (`on` = the phone's switch
`devices.unified_push`, on by default, and an origin set). Every allowlist writer calls
`unified_push::prune_disallowed` (a source scan, `every_allowlist_writer_prunes`, keeps it so): an app that leaves the
list loses its registrations. **How a writer calls it (R1-SR M2):** on the connection it wrote with - inside its own
transaction, before the commit: `let pruned = unified_push::prune_disallowed(&mut tx, device_id).await?;`, then the
commit, then `command_notify` when `pruned > 0`. It reads the list on that connection, so it sees the uncommitted
write and never waits on a second pool connection (which a `BEGIN IMMEDIATE` writer, such as design 109's
`devices::edit_allowlist_in` inside `calls::save_settings`, would block). A writer with no transaction of its own calls
`unified_push::prune_disallowed_now(state, device_id)` after its write.

## The PWA

Settings › App push (`/settings/push`): the address (`https://` and an origin, never a Tailscale IP), the prefill from
Phone Setup that never saves by itself, "Saving turns on app push for every phone", a warning without
`DEVICE_BIND_ADDR`, each replaced address's end (every one within its 30 days), "Last seen from outside" (the newest discovery, written at most once a
minute to `unified_push_settings.last_discovery_at`, and the newest accepted push). Emptying the field clears it:
every registration deleted. Device › Apps' "App push" group (`#app-push`): the switch (on: a row and "Turn Off App
Push…" with its sheet; off: an auto-saving switch), a row per app with registrations ("2 accounts · last delivered
14:02", "No delivery in 7 days", "Stop…"), the stopped apps with Allow Again, and the "isn't using Vibb" warning for an
allowed, installed Element X without a registration (`KNOWN_UP_APPS`), and "Many pushes: about N a day" for a noisy
app: Element X keeps its Play build, and its push
provider is switched to Vibb One once per account in Element X (Settings › Notifications › Advanced › Push provider);
Google is the same setting back. Words `app_push.*` (the parent web push owns `push.*`).

## VAPID, the outside-gateway mark and `refresh` (design 108b, R23-S)

- **`unified_push::vapid`** (pure; the clock, headers and accepted origins are passed in; `the_rows` runs every row of
  `testdata/unified_push_vapid.json`): both header forms (`Authorization: vapid t=,k=` in any order, with or without
  spaces and quotes; `WebPush <jwt>` + `Crypto-Key: p256ecdsa=`), in this order: form (at most 1024 bytes, else bad),
  key (compared as decoded bytes), the JWT's shape (`alg` ES256, no `crit`, a 64-byte r‖s), **the signature with the
  registered key**, then `exp` (`[now - 5 min, now + 25 h]`), then `aud` (the current origin or a replaced one within
  its 30 days). The signature is judged first, so a bad signature is always 403 and 503 goes only to a correctly signed
  push with a wrong time or address. The headers are never logged. A stored key that isn't a P-256 point (an R1 server
  stored any 87 characters) reads as no key, with one warning per registration (`stored_key`).
- **At `/up/`** the check sits in `publish` after the secret's lookup and before the buckets (`accept`): no
  signature 401 + `WWW-Authenticate: vapid`, wrong key or bad signature 403, time or address 503 + `Retry-After: 300`
  (Q3 (c): a 5xx keeps a Mastodon subscription alive when our clock or origin is the fault). A refusal never counts
  toward the ban; it stamps `up_registrations.vapid_refused_at`, written only when the stamp is null or a minute old
  (`store::note_refusal`; `lookup` returns the stamp with the row, so a refused flood takes no write lock).
- **The gateway** answers a keyed registration's pushkey as `rejected` (a homeserver never signs) and stamps it
  the same way. A push queued through our own gateway sets `gateway_at` (at most once a minute).
- **Registration:** a `vapid` must be 87 base64url characters that are a P-256 point, else 400. A retry of a known
  token with another valid key stores it and clears `vapid_refused_at` (the phone's update, answered with the same
  endpoint); a retry without a key never drops one. The one-an-hour limit of key calls is the phone's (`keyTryMs`).
- **Policy `unified_push.refresh`** `[{reg, why}]`, by `reg`: `outside` while `outside_gateway_at` is within 7 days and
  `gateway_at` isn't after it; `refused` (wins when both) while `vapid_refused_at` is within a day and no push was
  accepted after it (`last_push_at`). Pinned by `policy_json_keys_snapshot` and `the_contract`.
- **PWA** (Device › Apps › App push), per app: "Only pushes signed by the app's server" (a key held), the outside
  warning (marked), and "Pushes not signed … were refused, last …" (within 7 days). No save: nothing can jump.
  `KNOWN_UP_APPS` is `(package, name, missing_key)` and holds FluffyChat, whose text says to open it once.
- **The server's clock** matters for `exp`: DEPLOY.md's "App push" says so. **Release the server before the launcher
  RC** (an R1 server answers a key update without storing it).
- Tests: `vapid::tests::{the_rows, points}`, `tests::unified_push::{the_contract (refresh and the register rows),
  vapid_at_the_route}`.
