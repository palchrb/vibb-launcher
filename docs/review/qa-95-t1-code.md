# Code QA: design 95 step 95-T1 (commit 7332a822, branch `traffic-1`)

Scope: the policy's weak ETag and 304 (`server/src/handlers/device_api.rs`, `server/src/tests/policy_etag.rs`), the
launcher's kept raw body and revalidation (`launcher/.../server/PolicyEtags.kt`, `MdmSyncWorker.kt`), and the shared
base client (`ServerClients` in `ServerTransport.kt`, with `MdmApi.kt`, `CommandListenerService.kt` and
`MusicServer.kt` as its children). This was a read-only review with no build run. Each finding says whether it was
**verified** in the code or **inferred** (OkHttp or network behaviour that was not run).

## Verdict

No High findings. The 304 can't make a phone apply a stale or wrong policy. The server hashes exactly the bytes it
serves (`serde_json::to_vec` after `build_policy`, so after `pop_command` and every `now`-dependent part). The phone
keeps the tag and the raw body in one sealed record. A 304 therefore means the server would send byte-identical bytes
now. Binding the record to the versionCode, the URL and the token hash is defence in depth: correctness doesn't depend
on it. One Medium finding is about liveness, from the shared pool. The Low findings are mostly test gaps.

## What was checked and holds (verified)

- **Tag over the served bytes.** `policy()` hashes `body` and serves that same `body` (device_api.rs:234-256). No
  layer after the handler changes it. The device routes have no `CompressionLayer`; the test sends
  `Accept-Encoding: gzip` and asserts there is no `Content-Encoding`. `device_cache_control` keeps the route's own
  `no-store` (limits.rs:413), on the 304 too. Without the handler's header the layer would write
  `private, no-cache`, and the test's `no-store` assertion would fail, so it guards this.
- **Commands.** A popped command (a redelivery included) is in the bytes, so it is never a 304. The phone never keeps
  a body with `pending_command` (MdmSyncWorker.kt:154), and it drops the kept one in that case. The next GET then goes
  without the header.
- **`*`.** It never matches (`policy_etag_matches`, device_api.rs:279). A 304 to a request without the header is
  `null` on the phone (PolicyEtags.kt:118). A 304 whose kept body doesn't decode asks once more without the header.
- **Retrofit/OkHttp.** No `Cache` is configured, so the 304 reaches Retrofit as an error response. `@Header` with
  `null` leaves `If-None-Match` out. The 304's `raw().handshake` feeds design 76's flag exactly as a 200's does. A
  MITM gains nothing from replaying 304s: with the flag off it could already forge a 200, and with it on it can't
  connect.
- **Secrets.** The raw body (the parent PIN hash and salt, the kid lock hash) is sealed with AAD `policy_etag/kept`.
  The test checks that neither `kiosk_desired` nor the token reaches the prefs. Only the token's SHA-256 is stored.
  `KeptPolicy.toString` is redacted, and no log prints the body. Backup and device transfer exclude every domain. The
  Child PIN travels only inside `pending_command` (device_api.rs:499-516), which is never kept.
- **Owner changes.** A versionCode, URL or token change drops the record on the next `usable()`. Re-enrolment gives a
  new token. A server answer shaped by capabilities after an upgrade is still compared on bytes, so it is safe.
- **Concurrency.** `getPolicy` has one caller, `performMdmSync`, under the process-wide `syncMutex`. No other process
  syncs (only `:bootcover` and `:mmsparse` exist).
- **Shared client: TLS, fence and tailnet.**
  - `newBuilder()` copies the base's interceptor lists. Each child's bearer, timeouts and `LastByteInterceptor` come
    after the closed-path refusal, the origin fence, the refusal catch, the pinner and the observer.
  - `Key(path, enforce, pins)` is made of data classes. A tsnet restart (a new `Proxy`), Leave Tailnet
    (Tailnet/Direct to Closed/Direct without a proxy), a new URL or a flag flip each build a new base with a new pool,
    and evict the old pool's idle connections. So a newly built child never reuses a connection made under another
    pin flag or path.
  - The stream's client from an old base keeps its connection until it reconnects. Before this commit it did the same:
    it was built per connect.
  - The DoH client is still its own builder.
- **Compatibility.** An older launcher sends no header and gets the 200 (`no-store` and an extra `ETag`). An older
  server sends no ETag, so `keepable` is false: nothing is kept and the phone never sends the header.

## Medium

### M1. A dead shared connection now costs a sync, and evicting on a stream drop doesn't cover the HTTP/2 case (inferred)

ServerTransport.kt:214-237, CommandListenerService.kt:289, MdmApi.kt:177.

**Before:** every `createMdmApi` had its own pool, so every sync opened a fresh connection.

**Now:** the sync reuses a pooled connection.
- On HTTP/1.1 that is an idle keep-alive connection, kept up to 5 min.
- Behind Caddy's HTTP/2 it is the SSE stream's own connection. That connection is in use, not idle, so `evictAll()`
  never touches it.

`streamDropped()` runs only once the stream notices it is dead, which can take up to `SSE_READ_TIMEOUT_MS` (5 min).

**Failure scenario:** a carrier NAT or a Wi-Fi with no way out silently drops the TCP connection while the stream is
"up".
1. The next periodic sync, "Sync now", the weather pull or the shutdown's POST (3 s `callTimeout`) is multiplexed onto
   that dead h2 connection.
2. It writes the request and waits the 10 s read timeout.
3. OkHttp does not retry a request whose send started after a `SocketTimeoutException`, so the policy fetch is
   `UNREACHABLE`. "Sync now" says the server wasn't reached, and the shutdown report is lost.

OkHttp 4.12's degraded ping then marks the connection unhealthy, so later calls open a new one. In practice it costs
one failed call per dead connection, not a lasting outage. When Wi-Fi is lost, netd usually destroys the sockets, so
that path fails fast and OkHttp retries; the silent drop is the bad case. Design §3.5 named this risk and offered "a
`pingInterval` on the base client, or evicting the pool when the SSE stream reconnects". Only eviction was built, and
it doesn't cover this case.

**Suggested fix:**
- Either retry a timed-out policy GET once on a fresh connection: a GET is idempotent, and `evictAll()` before the
  retry is enough.
- Or set a modest `pingInterval` only on the stream child. The pings would then hit the shared h2 connection and fail
  it within one interval.

## Low

### L1. The guard that keeps the Child PIN out of storage has no test at its call site (verified)

MdmSyncWorker.kt:154. `PolicyEtagsTest` checks only the pure `keepable()`.
- `ServerPinsTest`'s source check covers the `fetchPolicy(...)` call, not the keep line.
- If `freshPolicy.pendingCommand != null` became `false` (or the check moved after `cacheablePolicy`), every test
  would still pass.
- The phone would then seal a `set_kid_pin` body, Child PIN included, against design 59 §1.

**Suggested fix:** add a source assertion in the style of `ServerPinsTest` for
`keepable(body.etag, freshPolicy.pendingCommand != null)` placed before `cacheablePolicy(`, or extract the
keep/drop decision into a pure function that takes the served body and the accepted DTO, and test that.

### L2. Nothing tests the pool's lifecycle (verified)

`the server clients share one base client per path and flag` checks instance identity only. No test checks that a key
change evicts the replaced pool, that `streamDropped()` evicts, or that a child's interceptors run after the base's.
The last two are the properties that keep the origin fence and the pinner first. Interceptor order is easy to pin: a
child with a recording interceptor against a `Closed` path must never see the request.

### L3. The kept body stays on disk after enrolment ends (verified, minor)

`Enrollment.enroll` (Enrollment.kt:39-50) clears the old enrolment's state, but not `PolicyEtagStore`. The token hash
covers correctness. However, `performMdmSync` returns early when the URL or token is blank (MdmSyncWorker.kt:91), so
`usable()` never runs and the sealed parent PIN and kid lock hashes stay. Add `PolicyEtagStore.of(context).drop()`
beside `CommandAnswers.clear`, which also matches design §3.5's "drops it on ... a re-enrollment".

### L4. A 304 with nothing kept reads as "unreachable" (verified, cosmetic)

PolicyEtags.kt:118. Our server can't send that, so only a misbehaving middlebox could. The phone then reports the
server as not reached. It is harmless: the policy and the cache are untouched. A log line saying so is enough.

### L5. Server test coverage (verified, nits)

- `policy_etag_matches` takes a comma list and `W/`-stripped tags, but no test sends a list (`"x", W/"<tag>"`).
- Nothing guards the determinism of a populated policy. `again.body == first.body` runs on a bare enrolled device,
  where maps and lists are empty. A `HashMap` that reached `PolicyResponse` later would quietly turn every answer into
  a 200. That wastes bandwidth but is never unsafe. Running the same check on `seeded_family` would catch it.

## Not findings (looked at and set aside)

- **Bytes after decoding to a string and back:** UTF-8 JSON round-trips through `ResponseBody.string()` and the
  sealed JSON string, and OkHttp's transparent gunzip (behind a gzipping Caddy) gives back the original bytes. The kept
  body is the decoded string and is never hashed again on the phone.
- **HTTP/2 flow-control stalls from a body that isn't read:** music and APK downloads read to disk continuously and
  close in `use {}` (MusicFileDownloads.kt:88), so no connection window is held.
- **`base()` thrash:** every caller derives the path from the same stored URL through `serverPath`, which adds the
  trailing `/`. Only an enrolment to a different URL alternates the slot, which is harmless.
