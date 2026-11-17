# `unified_push/`: Vibb's push server and Matrix gateway (design 108)

Vibb One is the phone's UnifiedPush distributor; this server is the push server behind the endpoints it hands out,
and a Matrix push gateway. See `docs/design/108-unified-push.md` (revision 2, variant A).

## What is ported from ntfy, and under which licence

ntfy (https://github.com/binwiederhier/ntfy) is dual-licensed under the Apache License 2.0 and the GPLv2. The port
is taken under **Apache-2.0** (`Apache-2.0.txt` here is ntfy's own `LICENSE` file); the server as a whole is
distributed under the GNU GPL v3.0 or later. Each ported file says so in its header. The source is ntfy at commit
**a098f52** (2026-11-16), by Philipp C. Heckel and the ntfy contributors. ntfy's tree carries no `NOTICE` file and
its Go files no per-file headers. Every file was rewritten in Rust; nothing is copied verbatim.

| ntfy (a098f52) | Lines there | Here | What is kept | What changed |
|---|---|---|---|---|
| `server/server_matrix.go`: `matrixRequest`, `newRequestFromMatrixJSON`, `writeMatrixDiscoveryResponse`, `writeMatrixResponse`, `errMatrixPushkeyRejected` | 1-165 | `matrix.rs` | the body to the size limit else 413; invalid JSON or no `devices[0].pushkey` is 400; only `devices[0]` (ntfy #316); a pushkey not under our base is rejected (200 `{"rejected":[pushkey]}`, no open relay); the discovery and response bodies byte for byte | the base is `<origin>/up/` for the current origin or one it replaced, each for 30 days after its change (M4, R1-SR L7), scheme and host compared case-insensitively (M4); the registration is found by the secret alone (no topic regex: anything that isn't a 27-character secret is never looked up); a gone or never-seen secret is rejected too (ntfy's abandoned topic); **the stored body is minimised (Q8)** by an allow-list (R1-SR L6): only `notification.event_id`, `room_id`, `counts`, `prio`, `type` and each device's `app_id`, `data`, `pushkey_ts`, every kept member with its bytes |
| `server/server.go`: `transformMatrixJSON`, `handleMatrixDiscovery`, `handlePublishMatrix` | 1059-1064, 1249-1268, 2420-2436 | `matrix.rs` (`discovery`, `notify`) | discovery only while a base (our public origin) is set; every other refusal passes through as its status (429, 507 transient to the homeserver) | discovery answers 404 instead of ntfy's 500 when no origin is set (Element X then falls back to the public gateway); `prio: high` becomes the message's urgency |
| `server/server.go`: `handlePublishInternal` (the UnifiedPush branch), `parsePublishParams` (the UnifiedPush detection) | 1110-1145 | `publish.rs` | a body of 1-4096 bytes; a transient refusal is a 5xx, never a 4xx (Mastodon's `push_notification_worker.rb` deletes on any 4xx but 408/429); gone is 410 (Misskey and its forks delete only on 410) | every `/up/` push is a UnifiedPush push, so the detection (`Content-Encoding: aes128gcm` or `up=1`) isn't needed; raw bytes are stored (no base64 envelope); RFC 8030's `TTL` (clamped to a day, a day when absent), `Urgency` and `Topic` are honoured; a tombstoned secret with a Matrix-shaped body is 404, not 410 (L1: common-proxies' gateway rejects only on 401-404); a Matrix body through an outside gateway is minimised too; `201` with `Location: <origin>/up/m/<key>` |
| `server/server_rate_visitor.go`: `rateVisitor`, `unifiedPushTopicAbandoned`, `topicAbandoned` | 1-99 | `rate.rs`, `store.rs` | charge the subscriber, not the publisher: every push is charged to its phone, never to the sending IP; abandoned means 410 | ntfy can't know a topic is still wanted, so it says gone after 4 days without a subscriber; we know: gone means the registration was deleted (a 30-day tombstone, `store::lookup`, M5) |
| `server/visitor.go` `RequestAllowed`/`MessageAllowed`, `server/config.go` `DefaultVisitorRequestLimitBurst` (60), `DefaultVisitorRequestLimitReplenish` (5 s), `DefaultMessageSizeLimit` (4096) | config.go 79, 106-107 | `rate.rs`, `store.rs` | the token-bucket model, ntfy's sustained one per 5 s and the 4096-byte message | the numbers: a phone's bucket is 120 burst at one per 5 s, with a sub-bucket per registration of 30 at one per 2 s (M3), every client's own request bucket of 40 at 20 a second before any lookup (R1-SR L3), and a 429 carries `Retry-After` |
| `server/errors.go`: `errHTTPBadRequestMatrixMessageInvalid` (40019), `errHTTPGoneUnifiedPushTopicAbandoned` (41002), `errHTTPEntityTooLargeMatrixRequest` (41302), `errHTTPInsufficientStorageUnifiedPush` (50701) | 137, 202, 204, 222 | `matrix.rs`, `publish.rs`, `mod.rs` (`Refusal`) | the four statuses | empty bodies: app servers don't read ntfy's JSON error bodies |
| `server/server_matrix_test.go` (9 tests), `server/server_rate_visitor_test.go` | - | `matrix.rs` tests, `rate.rs` tests, `testdata/unified_push_gateway.json` | the Matrix tests one for one (success, too large, invalid JSON, not Matrix, mismatching pushkey, invalid topic as an invalid secret, discovery, error and success writers) and the charge rules that apply | the abandoned-after-4-days tests don't apply (no such timer here) |

## Left out

ntfy's topic model (`topicRegex`, the `up` prefix, the 14-character length: every endpoint here is a registration with
an owner), the cluster topic service, Firebase, Web Push to browsers, attachments, e-mail and calls, the message cache
and `since=` (our queue and its ACK replace them), auth and ACLs (the secret is the capability), `?up=1` and the topic
GET's `{"unifiedpush":{"version":1}}`, the rate-limit headers, and `X-Forwarded-For` handling (`net::Client` does it,
with `TRUSTED_PROXIES`, as everywhere on the phone listener).

## Our own (GPL-3.0-or-later)

`mod.rs` (the in-memory state, the origin, the policy, the allowlist prune), `device.rs` (the device API), `store.rs`
(the registrations, the queue, the tombstones), `edge.rs` (the routes' own limits), `notify.rs` (the trailing-edge
`up`), and the parts of `rate.rs`/`publish.rs`/`matrix.rs` listed as changed above.
