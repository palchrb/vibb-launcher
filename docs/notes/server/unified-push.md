# App push: Vibb as the UnifiedPush distributor (design 108)

`docs/design/108-unified-push.md` (revision 2, variant A; the user's Q4 change of 2026-11-16: Element X keeps its Play
build). The phone's Vibb One is the distributor; this server is the push server behind the endpoints and a Matrix push
gateway. Code: `src/unified_push/` (its `README.md` lists what is ported from ntfy under Apache-2.0),
`src/handlers/app_push.rs`, migration 0152.

## The routes

- **Public, on the phone listener only** (`unified_push::routes`, merged in `build_device_router`; the admin listener
  never serves them - `tests::listeners::up_routes_only_on_the_phone_listener`): `POST /up/{secret}` (RFC 8030
  publish), `GET`/`POST /_matrix/push/v1/notify` (gateway discovery and notify). Behind `unified_push::edge`, not
  `limits::device_edge`: 32 in flight, 4 per client (`net::limit_key` of the trusted-proxy client, as the rest of the
  phone listener counts), a 3 s body read, 4097 bytes read at most (413 above 4096), a 10 s answer, the device API's
  headers. No outbound request is ever made for a push (no open relay, no SSRF).
- **The device API** (bearer, `device_routes.rs`): `POST /api/devices/up/registrations` `{reg, secret, package,
  vapid?}` -> `{endpoint}` or 409 `{refused}`; `DELETE /api/devices/up/registrations/{reg}` -> 204;
  `POST /api/devices/up/messages` `{ack}` -> `{messages, more}`. Shapes: `testdata/unified_push.json`, shared with the
  launcher.

## The answers (§3.4)

`/up/`: 201 + `Location: <origin>/up/m/<key>` + `TTL`; 400 empty; 413; 410 for a tombstoned secret (404 when the body
is a Matrix notify, L1) and for a never-seen one; 429 + `Retry-After`; 507 when the store is full. A transient refusal
is a 5xx, never a 4xx (Mastodon deletes the subscription on a 4xx but 408/429); gone is 410 (Misskey). The gateway:
200 `{"rejected":[]}`, or the pushkey in `rejected` when it isn't under `<origin>/up/` (the current origin, or the
previous one for 30 days after a change) or its secret is gone or never seen; 400, 413, 429, 507 pass through.
Discovery: 200 `{"unifiedpush":{"gateway":"matrix"}}` while an origin is set, else 404. The server-only rows:
`testdata/unified_push_gateway.json` (`tests::unified_push::gateway_rows`).

## Who pays, and the ban

Every push is charged to the phone (120 burst, 1/s) and its registration (30 burst, one per 2 s) - ntfy's "rate
visitor", never the sending IP (`rate.rs`). Only never-seen secrets count toward a client's ban (30 in 10 min, then
429 for 10 min, UP's own map); the secret is looked up first, so a ban never blocks a resolving secret, and a
tombstoned one is answered without counting.

## The store (§3.5, §3.6)

`up_registrations` holds the SHA-256 of each secret, never the secret. Every deletion leaves a 30-day tombstone in
`up_gone`, written by the trigger `up_registrations_tombstone` (so a phone deleted through `ON DELETE CASCADE` is
covered too). `up_messages`: one row per message until acknowledged or expired (TTL at most 24 h); 100 per
registration and 500 rows / 1 MiB per phone (the oldest dropped, counted in `dropped`), 64 MiB in all (507). A Matrix
body is stored minimised (Q8: `notification.content`, `sender_display_name`, `room_name`, `room_alias` dropped, the
rest kept byte for byte), also when it came to `/up/` through an outside gateway. Nothing per message is logged
(`tests::unified_push::nothing_logged`), and the security log keeps `/up/…` for any `/up/` path
(`audit::clip_path`, L5). Retention (`unified_push::store::prune`, hourly): expired rows, the registrations of a phone
unseen 30 days (`devices.last_seen_at`), tombstones past 30 days.

## The nudge

`AppState::unified_push.up_notify`, a broadcast of its own; `commands_stream` merges it as `event: up` / `data: up`
(a lag sends only `up`). Coalesced per phone at the sender on the trailing edge (`notify.rs`, 250 ms): the last push of
a burst is always followed by an `up`. No capability gate: only a distributor makes registrations.

## The policy and the allowlist

`unified_push` `{on, origin, regs, stopped}` only to a launcher claiming `unified_push_v1` (`on` = the phone's switch
`devices.unified_push`, on by default, and an origin set). Every allowlist writer calls
`unified_push::prune_disallowed` (a source scan, `every_allowlist_writer_prunes`, keeps it so): an app that leaves the
list loses its registrations.

## The PWA

Settings › App push (`/settings/push`): the address (`https://` and an origin, never a Tailscale IP), the prefill from
Phone Setup that never saves by itself, "Saving turns on app push for every phone", a warning without
`DEVICE_BIND_ADDR`, the previous address's end, "Last seen from outside" (the newest discovery, written at most once a
minute to `unified_push_settings.last_discovery_at`, and the newest accepted push). Emptying the field clears it:
every registration deleted. Device › Apps' "App push" group (`#app-push`): the switch (on: a row and "Turn Off App
Push…" with its sheet; off: an auto-saving switch), a row per app with registrations ("2 accounts · last delivered
14:02", "No delivery in 7 days", "Stop…"), the stopped apps with Allow Again, and the "isn't using Vibb" warning for an
allowed, installed Element X without a registration (`KNOWN_UP_APPS`): Element X keeps its Play build, and its push
provider is switched to Vibb One once per account in Element X (Settings › Notifications › Advanced › Push provider);
Google is the same setting back. Words `app_push.*` (the parent web push owns `push.*`).
