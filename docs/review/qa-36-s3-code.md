# QA 36 step 3 (code): DoH on 443 when 853 is blocked, and the bootstrap's disk cache

Code QA, 2026-10-18. Commit under review: `5d58e78a` (branch `dns-s3`, base `main` `9e785e47`), launcher only. I read it
against `docs/design/36-custom-dns.md` (§5.2, §5.3, §6, §9 step 3, §10, the step 2 status with its fix round, the step 3
status with its five deviations and four open points), `docs/review/qa-36-s2-code.md`, and the root and launcher
`CLAUDE.md`. Line numbers are `5d58e78a`'s, under `launcher/app/src/main/java/com/kidslauncher/mdm/server/` unless a
path is given. No product code was changed. Every mutation was reverted, and the tree was clean before this file was
written.

**Verdict: sound, merge after a small fix round.** The rule the brief sets is kept:
- The fallback is the same provider's DoH by IP-literal URL (`https://1.1.1.1/dns-query`, `https://9.9.9.9/dns-query`).
  It is never the other provider's, and a parent's DoT server never gets a guessed URL. A structural test pins this:
  only `CustomUpstream.kt` and the two public URLs ever build an `UpstreamTarget.Doh`.
- `DotBlockedRule` is pure and correct. Its state is one `AtomicReference` changed by CAS, as the chooser does it, and
  the threshold, window, probe, back-off, cap and reset are all tested.
- The providers' DoH client is `buildDohClient(IP_LITERAL_DNS, ::protect)`: a fresh builder, `NO_PROXY`, protected
  sockets, no redirects and no cache. It never resolves a name, never uses tsnet, and is evicted on a reset and at
  stop.
- The bootstrap's Cloudflare step shares the providers' Cloudflare state and reaches `https://1.1.1.1/dns-query`
  through the custom server's own protected client.
- The disk cache is CE-only, its format is strict, its expiry is capped, and a malformed file counts for nothing. It
  is written through a rename, never by a closed instance, and nothing logs a host.
- The status field and every contract file are unchanged, and there is no per-query background work.

What has to change:
- **M1**: a Forget (or a pick of Cloudflare/Quad9) while the VPN is off leaves the parent's host names on disk
  indefinitely. `syncCustom` returns before its delete. No test covers the delete, and mutation M8 survives.
- **M2**: on a network that *drops* 853, the providers' 5 s DoT connect uses up the query's deadline. The first queries
  on every such network, and again after every network reset, are dropped. Make the providers' DoT connect 2.5 s
  (deviation 1 then holds for both kinds of block).
- **M3** (open point 1, decided): the prefilled backup becomes `https://1.1.1.3/dns-query`. This is a server-side
  change, and the exact places are listed below.
- Small items: L1 (on a network that blocks DoH to the provider but not 853, three transient DoT failures cost a
  5-minute outage), L2 (three guards no test pins: M13, M14, M16), and a few nits.

## Runs

| Check | Result |
|---|---|
| launcher `./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest -PwarningsAsErrors=true` (under `flock ~/build.lock`) | BUILD SUCCESSFUL; **1343 tests**, 0 failed, 0 skipped (step 2's QA ran 1325, its fix round added some) |
| new and changed tests | `PublicResolverTest` 6 (new), `BootstrapResolverTest` +2, `CustomDnsTest` +2 (one expectation extended), `CustomUpstreamPrefTest` log scan extended to `PublicResolver.kt` |
| compiler warnings | only the two known kapt `RELEASE_11` lines, none in the diff |
| contract copies | `dns_upstream_status.json`, `time_status_report.json`, the policy snapshot: untouched (the diff touches no `server/` file and no test resource) |

## Findings

### High

None.

### Medium

#### M1. A Forget while the VPN is off leaves the parent's host names on disk indefinitely

`KidVpnService.kt:136-145` (`syncCustom`).

```kotlin
val config = DnsFilterEngine.customUpstream().takeUnless { stopped }
if (config == custom?.config) return                 // 138
custom?.close()
val cacheFile = File(noBackupFilesDir, BootstrapDiskCache.FILE_NAME)
if (DnsFilterEngine.customUpstream() == null) cacheFile.delete()   // 142
```

**What breaks.** The delete at `:142` runs only when the config *changes while the VPN runs*. I followed both paths:
- **The VPN is running** when the policy drops `dns_upstream` (Forget, or a pick of Cloudflare/Quad9 in the PWA). Then
  `setCustomUpstream(null)` → `onCustomChanged` → `syncCustom`, with `config == null != custom.config`. The old
  instance is closed (`closed = true` under its lock, so an in-flight look-up can't rewrite the file) and the file is
  deleted. This path is correct.
- **The VPN is off** at the time of the Forget (the web filter off for this phone, the pause or kill switch, a reboot
  before the VPN is up again, or the always-on VPN revoked). Then `onCustomChanged` is `null` (`:290`) and nothing
  happens. At the next `startVpn`, `syncCustom` finds `config == null` and `custom == null`, so it returns at `:138`
  before the delete. The file keeps the parent's host names, a DoT client id in the first label included, until a
  later custom config with names happens to overwrite it.

The step 3 status and `launcher/CLAUDE.md` both say the file is deleted "as does the VPN starting or syncing with no
custom server". That isn't true for the start. The harm is small, because it is CE storage, 0600 and no-backup, but
"Forget" in the PWA has to mean forgotten on the phone too. That is exactly why the delete was added.

The same applies, more narrowly, to **a change from custom A to custom B** when B's names never resolve (B by name on
a network where no look-up gets through). `loadDiskCache` reads only B's names (`CustomDns.kt:240-242`), but the file
keeps A's until B's first successful look-up rewrites it.

**Fix.**
- In `syncCustom`, delete before the early return, and key it on the policy rather than on `stopped`:
  ```kotlin
  val cacheFile = File(noBackupFilesDir, BootstrapDiskCache.FILE_NAME)
  // No custom server: its names' cache goes too (it holds a name the parent typed) - also at a
  // start with none, after a Forget while the VPN was off.
  if (DnsFilterEngine.customUpstream() == null) cacheFile.delete()
  val config = DnsFilterEngine.customUpstream().takeUnless { stopped }
  if (config == custom?.config) return
  ```
- In `CustomDns.loadDiskCache`, when the file held lines for names other than this config's, write back only the
  decoded entries, or delete the file if there are none. `decode` can return whether it skipped any.
- Put the delete decision in a pure helper (for example `bootstrapCacheKept(customUpstream): Boolean`, or a small
  `syncPlan`) and test it. At minimum, add a source pin in `BootstrapResolverTest` like the existing
  `File(noBackupFilesDir, …)` pin: the delete has to come before `if (config == custom?.config) return`. Mutation M8
  (the delete removed) survives today.
- Correct the wording in the design note's step 3 status and in `launcher/CLAUDE.md` if it changes.

#### M2. Deviation 1 on a network that drops 853: shorten the providers' DoT connect to 2.5 s

`UpstreamTransport.kt:53` (`PROVIDER_BUDGET = Budget(5000, 10_000)`), `PublicResolver.kt:101-114`,
`DnsFilterEngine.kt:171`.

**The judgement.** Deviation 1 is right. Letting a query go on to the same provider's DoH as soon as *its own* DoT has
failed, inside the ~4.8 s deadline, costs nothing on a healthy network and answers at once on a network that
*refuses* 853. Waiting for the threshold would only add dropped queries. Keep it.

**What breaks.** On a network that *drops* 853 (a silent firewall, which is the usual kind for a school or guest
Wi-Fi), each provider query spends the full 5 s connect. `left = 4.8 s - 5 s < BACKUP_FLOOR_MS`, so the query is
dropped. The `PublicResolverTest` case "a slow DoT failure leaves no time for DoH" pins that behaviour. Android's stub
resolver retries after about 5 s, so every app's first look-ups fail for 5-10 s until three failures start the period.
`PublicResolvers.reset()` on every `networkChanged` brings that back:
- on each join of such a network,
- on each mobile↔Wi-Fi switch back to it,
- on each VALIDATED gain, for example after a captive portal login.

On a school day that adds up to several stalls of "the internet doesn't work" for a few seconds each. Before step 2,
the 5 s connect was the only leg, so waiting longer could only help. Now there is a second leg, and a long first leg
just uses up the time the second one needs.

**Fix (decided).** `PROVIDER_BUDGET = Budget(2500, 10_000)`, so the connect is 2.5 s and the 10 s total for
handshake + answer after a connect stays as today.
- **DROP**: connect fails at 2.5 s, `left = 2.3 s ≥ 1.3 s`, and DoH runs within `Budget(1500, 2300)`. The answer lands
  by 4.8 s, inside the stub's first ~5 s try. The very first query on the network is answered, and the period still
  starts after three.
- **A healthy but slow network**: TCP's initial SYN retransmit is at 1 s, so one lost SYN still connects within 2.5 s.
  A connect slower than that goes to the same provider's DoH in the same query instead of waiting. At worst, three of
  those in a minute start a 5-minute DoH period with the same provider, which is harmless. Today a connect that takes
  more than 5 s answers a query the stub has already given up on.
- **A TLS blackhole** (TCP connects, the handshake is silently dropped) still uses up the 10 s total and is dropped
  until the period starts. That case is rare, since an intercepting middlebox shows a bad certificate and fails fast,
  and it isn't worth cutting the after-write read short for. Leave it as a known residual.
- **Tests**: in `PublicResolverTest`, keep the floor case (a DoT failure after 5 s, no DoH). Add "a dropped SYN at the
  providers' connect (2.5 s) still gets DoH in the same query" (DoT budget connect = 2500, DoT then DoH, an answer).
  Pin `PROVIDER_BUDGET.connectMs + BACKUP_FLOOR_MS <= QUERY_DEADLINE_MS` as a plain assertion, so a later bump can't
  silently bring the drop back.
- **Docs**: the `PROVIDER_BUDGET` doc comment ("as before design 36 (5 s connect, 5 s reads)"), the step 2 status's
  "Cloudflare/Quad9 keep today's timeouts" (add "until step 3: 2.5 s connect"), the step 3 deviation 1 text,
  `launcher/CLAUDE.md:802-803` ("a dropped SYN under the providers' 5 s connect doesn't - that query is dropped"), and
  `docs/testing/emulator.md:1459-1460` ("with DROP the first few are dropped (5 s connect)" becomes "with DROP each
  query takes ~2.5 s more until the period starts").

#### M3. The prefilled backup becomes `https://1.1.1.3/dns-query` (open point 1, decided)

The decision: under Custom, the prefilled backup is Cloudflare for Families over **DoH on 443** instead of `1.1.1.3`
(DoT on 853). The default backup then also answers on a network that blocks 853, where a DoT primary *and* a DoT
backup would both fail and every query would be dropped. A parent's own servers stay as they are (§10.4, "the
parent's servers are what they are"); only our default changes. This is server-side, so the implementer touches
`server/` for this alone. The wire, the launcher and the contract files don't change: the server already parses an
`https://` URL with an IP host, and the launcher already treats an IP-literal DoH backup as the bootstrap resolver,
so its look-ups go over 443 too.

Exact places:
- `server/src/handlers/dns_filter.rs:193`: `const BACKUP_PREFILL: &str = "https://1.1.1.3/dns-query";`. Update the doc
  comments at `:166` ("`1.1.1.3` in the backup's while none is stored") and `:191`, and at `:711`
  (`backup_prefill`'s "`1.1.1.3` only while…").
- `server/src/handlers/dns_filter.rs:195`: `BACKUP_NEEDED` becomes "Custom needs a backup server:
  https://1.1.1.3/dns-query is Cloudflare for Families."
- `server/templates/dns_filter.html:148`: `placeholder="1.1.1.3"` becomes `placeholder="https://1.1.1.3/dns-query"`.
- `server/templates/dns_filter.html:150`, the footer under the field: "Answers while your server can't be reached.
  https://1.1.1.3/dns-query is Cloudflare for Families, over DNS-over-HTTPS, so it also answers on networks that block
  DNS-over-TLS."
- `server/templates/dns_filter.html:172-173`, the ⓘ: after "both over DNS-over-TLS", add "(on a network that blocks
  it, over DNS-over-HTTPS to the same provider)". After the custom-server sentence, add "On a network that blocks port
  853 a DNS-over-TLS server can't be reached; a DNS-over-HTTPS backup still answers."
- Tests that pin the prefill: `server/src/tests/custom_dns.rs:77, 203, 364` (`value="1.1.1.3"` becomes
  `value="https://1.1.1.3/dns-query"`), and any assertion on `BACKUP_NEEDED`'s text. Rendered in an attribute, the URL
  is escaped by askama (`/` stays `/`, which is fine, but check the expected string against the rendered one). The
  saves that *post* `1.1.1.3` or `tls://1.1.1.3` (`custom_dns.rs:86, 233, 263, 375`, `settings_pages.rs:295`,
  `device_api.rs:389`, `backup_secrets.rs:117`, `dns_server.rs:554, 732, 746-756`) test the parser and storage and
  can stay. Add one save that posts the new prefill untouched and checks the stored `normalized()` value and the
  policy's `{"kind": "doh", "url": "https://1.1.1.3/dns-query"}`.
- Design note: §0 (line 23), §1.7 (line 50), §6 (lines 282-291: the prefill, the footer, `BACKUP_NEEDED`), §8 (line
  351). Leave §10.1, the owner's decision text, as history, with a one-line addendum: "2026-10-18, step 3 QA: the
  prefill is `https://1.1.1.3/dns-query`". Close open point 1 in the step 3 status.
- No migration: a backup already stored as `tls://1.1.1.3` stays as the parent saved it. Add one sentence to the step
  3 status: a parent can type the new one.
- Device check, owed in `docs/testing/emulator.md` §6m step 5/7: `https://1.1.1.3/dns-query` filters as Families does.
  Cloudflare's test domain for the adult category should come back `0.0.0.0` / `::`, and the certificate names
  `1.1.1.3`. Step 5 already uses this URL as a backup that answers.

### Low

#### L1. A network that blocks DoH to the provider but not 853: three transient DoT failures cost a 5-minute outage

`PublicResolver.kt:105` (`if (route == DotRoute.DOH) return transport(doh, …)`).

Some school filters block the well-known DoH endpoints on 443 (by IP or SNI) and leave 853 alone, or the other way
round. On such a network, a burst of three DoT failures within 60 s (weak signal, a hiccup) starts a DoH period. Every
query in the period then goes only to DoH, which fails, so DNS is gone until the probe after 5 minutes succeeds. The
period never doubles there, because the probe works. Today, the same hiccup costs only the failed queries
themselves. This case is narrow, but it is a regression.

**Fix.** Make the fall-through symmetric. In a DoH period, a DoH `Transport` failure tries DoT with what is left of
the deadline, as long as at least `BACKUP_FLOOR_MS` remains, within `dotBudget.atMost(UPSTREAM_BUDGET)`. It is not
the period's probe. A DoT that reaches the server then ends the period through `afterDotWorked`, as any working DoT
does. Add a test: in a period, DoH blocked and DoT working, the query is answered and the state is back to
`DotBlocked()`. This costs nothing on the networks step 3 is for, where DoH works.

#### L2. Three guards no test pins: a closed instance's write, the DoH floor, the reset wiring

- `CustomDns.kt:251-256`: mutation M14 (`if (closed) return` disabled) survives. Without the guard, a look-up still
  running for the old servers after a Forget could rewrite the file `syncCustom` just deleted. Test: hold a look-up in
  a fake transport (a latch), `close()`, release it with addresses, and assert that the file doesn't exist.
- `PublicResolver.kt:113`: M13 (`left < 0` for `left < BACKUP_FLOOR_MS`) survives. The only slow case
  (`dotTakes = 5_000`) leaves a negative remainder, so it doesn't tell the floor from zero. Test: DoT fails after 4 s
  (800 ms left), and the query is dropped with no DoH. Add this next to M2's new 2.5 s case.
- `KidVpnService.kt:178`: M16 (`PublicResolvers.reset()` removed from `onNetwork`) survives. A Wi-Fi toggle would then
  never try DoT again until the period ran out. Add a source pin in the `if (changed)` block, as for `networkReset`, or
  move the reset fan-out into a small testable function.

### Nits

- **N1. `probing` can stick if a transport throws.** `PublicResolver.kt:103-114`: if `transport` throws between the
  route CAS and `afterDotFailure`, `probing` stays true and that provider never probes again until a network reset.
  The production transports catch everything, but `CustomDns`'s bootstrap path catches the exception *around*
  `cloudflare.resolve` (`CustomDns.kt:196-200`), so it expects a throw. DoH still works, so this is harmless, but a
  `try/catch` in `resolve` that turns an exception into a counted `Transport(CONNECT)` makes it airtight.
- **N2. The providers' client is built on the first provider query.** `KidVpnService.kt:236` evaluates
  `providerDoh.value` on every provider query, so the client is built at the first one, not at the first DoH use. A
  builder plus the trust manager once per service start is cheap. "Lazy" is true in the sense that it isn't built
  while Custom is in use. Passing `providerDoh` (the `Lazy`) or `{ providerDoh.value }` into `resolveProvider` would
  build it only on a 853-blocked network. Optional.
- **N3. `afterDotWorked` allocates a `DotBlocked()` per successful DoT query** only to compare it. `DotBlocked`'s
  `copy`/equality is trivial, but a `private val CLEAR = DotBlocked()` would remove it. Optional.
- **N4. The design note's open point 3 (the read on the main thread at VPN start)**: fine. It is one file of a few
  hundred bytes, read once per config, next to `loadFromDisk`'s much larger blocklist read in the same `startVpn`
  (and M1's delete is one more `unlink`). No change.

## The brief's points, judged

- **The same provider's DoH, never the other's, never a guessed URL**: confirmed (`PublicResolver.kt:139-165`,
  `PublicResolvers.of`). Under Custom the parent's servers never pass through `PublicResolver`. The only route there is
  the bootstrap's `CLOUDFLARE_BOOTSTRAP`, which is Cloudflare's own resolver. The structural test in
  `PublicResolverTest` and the behaviour test (two DoT servers both blocked: only those two are ever asked) kill M1/M2.
- **`DotBlocked`**: 3 in a fixed 60 s window from the first failure, a working DoT clearing the count, 5 min doubling
  per failed probe to the 1 h cap (`shl` bounded by `coerceIn(0, 10)`, then `minOf`). There is one probe per period:
  the CAS sets `probing`, and the others see it and use DoH. A late non-probe DoT failure in a period changes nothing.
  A probe whose state was reset under it counts as an ordinary failure. Every change goes through one CAS loop, which
  logs only on a `doh`/`periods` change. Thread-safe. The probe's DoT is capped at `UPSTREAM_BUDGET`, so its query
  still gets DoH in time.
- **Deviation 3, a TLS failure on 853 counts**: fine. A MITM Wi-Fi with a bad certificate on 853 moves the phone to
  the same provider's DoH on 443, whose certificate OkHttp verifies against the IP just as strictly. Nothing is
  weakened, and the query is answered. Silence after the request was written is `Odd` and doesn't count, because 853
  worked.
- **Deviation 4, a stale disk entry served while one refresh runs**: consistent with the memory cache (§5.3). The
  24 h cap on read is right.
- **Deviation 5, the shared Cloudflare state**: right. It is the same `1.1.1.1:853` on the same network, and the
  providers and Custom never run at once.
- **Open point 4 (3 / 60 s / 5 min / 1 h)**: keep them.
  - Battery: there are no timers. A probe is one DoT attempt of at most 1.5 s connect when a query arrives after the
    period ends. On a school Wi-Fi that blocks 853 from 8 to 15, that is periods of 5, 10, 20, 40, then 60 minutes:
    about 8 probes in a 7-hour day.
  - On a REJECT network the threshold of 3 is reached in the first second, because Android sends A and AAAA in
    parallel. With M2 the ramp costs a DROP network ~2.5 s per query for the first three, instead of dropped queries.
  - A transient blip on a good network costs at most one 5-minute period with the same provider. A network reset
    clears the state, so a long period never outlives the network it was learned on.
  - A shorter cap (say 30 min) would buy little: one more probe an hour, and DoH works meanwhile.
- **The providers' DoH client**: `buildDohClient(IP_LITERAL_DNS, ::protect)`, which is `NO_PROXY`, protected sockets,
  no redirects, no cache, and the per-call `Budget` connect timeout. `IP_LITERAL_DNS` throws for any name. The client
  is evicted on `networkChanged` and at `stopSelfCleanly`, and it is never the tsnet clients. Under Custom, Cloudflare's
  DoH goes through `CustomDns`'s own client (`BootstrapDns` returns the literal), so it is protected and has no proxy
  either.
- **The disk cache**:
  - `File(noBackupFilesDir, …)`: CE storage, no backup. The VPN service isn't `directBootAware`, so it can't touch CE
    before the unlock. The app umask gives 0600.
  - The format is `v1`, then one line per name: host, wall-clock expiry, at most two addresses. Addresses are parsed by
    `ipLiteral`, never looked up.
  - It is read in the constructor before `start()`, holds only the current config's names, and caps expiry at 24 h
    ahead. Stale entries are kept. A malformed file is ignored whole.
  - It is written through temp + rename after a look-up that found addresses, under the instance's lock (so two
    look-ups don't interleave), and never after `close()`.
  - It is deleted for an IP-literal pair (tested) and on a Forget while the VPN runs (correct, untested). It is
    **not** deleted on a Forget while the VPN is off (M1).
  - It is never logged: `BootstrapDns.kt` and `CustomDns.kt` are both in the log scan, the cache code logs only
    `entries.size` and exception classes, and M9 is killed.
- **Contract files and the status field**: unchanged. `dns_upstream_status.json` has no new word.

## Mutations

Each mutation was applied alone, then `testDebugUnitTest --tests 'com.kidslauncher.mdm.server.*'
-PwarningsAsErrors=true` was run and the source restored (`scratchpad/qa-dns-s3/mut.py`).

| # | Mutation | Result |
|---|---|---|
| M1 | Quad9's DoH URL → `https://1.1.1.1/dns-query` (Quad9 falls to Cloudflare's DoH) | killed: `PublicResolverTest` own DoH, never the other's; the structural two-URLs test |
| M2 | `CustomDns`: a parent's DoT primary that fails is retried at a guessed `https://<host>/dns-query` | killed: `PublicResolverTest` no guessed DoH URL; `CustomDnsTest` home-IP fall-through |
| M3 | `failures >= 1` (the threshold ignored) | killed: `PublicResolverTest` threshold and per-provider tests; `CustomDnsTest` bootstrap over DoH |
| M4 | `route` ignores `probing` (a probe on every query after the period) | killed: `PublicResolverTest` one probe per period |
| M5 | the 1 h cap removed from `backoff` | killed: `PublicResolverTest` schedule 10/20/40/60/60/60 |
| M6 | `namesOf` keeps IP literals (the cache written for an IP pair) | killed: `BootstrapResolverTest` names; `CustomDnsTest` disk cache, none for IP literals |
| M7 | `decode` skips a bad address instead of rejecting the file | killed: `BootstrapResolverTest` corrupt files |
| M8 | `syncCustom`'s `cacheFile.delete()` removed (the cache kept after Forget) | **survived** (M1) |
| M9 | `log("Bootstrap cache: ${entries.keys} names")` (a host logged) | killed: `CustomUpstreamPrefTest` log scan |
| M10 | `buildDohClient(IP_LITERAL_DNS) { true }` (unprotected sockets for the providers' DoH) | killed: `PublicResolverTest` client pin |
| M11 | the bootstrap's Cloudflare step bypasses `PublicResolver` (DoT only) | killed: `CustomDnsTest` bootstrap over DoH, bootstrap period |
| M12 | the probe's DoT budget not capped at `UPSTREAM_BUDGET` | killed: `PublicResolverTest` probe budget |
| M13 | `left < 0` instead of `left < BACKUP_FLOOR_MS` (DoH with less than the floor left) | **survived** (L2) |
| M14 | `if (closed) return` disabled (a closed instance writes the cache) | **survived** (L2) |
| M15 | the 24 h cap removed on read | killed: `BootstrapResolverTest` expiry cap |
| M16 | `PublicResolvers.reset()` removed from `onNetwork` (a network reset keeps the DoH period) | **survived** (L2) |

Of 16, 12 were killed and 4 survived (M8 → finding M1; M13, M14, M16 → L2).

## Test quality

The new tests are lean and test behaviour through the pure rule and the glue with a fake transport. Two string pins
(`PublicResolverTest`'s `buildDohClient(IP_LITERAL_DNS, ::protect)`, `BootstrapResolverTest`'s `noBackupFilesDir`)
stand in for Android-only wiring, as in step 2. They are acceptable, and M10 shows they bite. The gaps are M1's delete
(M8) and L2's three (M13, M14, M16).
