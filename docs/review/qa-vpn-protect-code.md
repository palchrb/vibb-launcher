# QA (code): the VPN protect fix

Branch `vpn-restart-fix`, commit e561f4fe (on 539b0173). Launcher only. Reviewed 2026-10-19 for the hotfix
`launcher-v0.29.1`.

## Summary

The fix is correct and ready to ship. Every socket the DNS engine protects now comes from `protectedSocket`, which
binds the socket and then protects it, before any connect. That covers DoT in `resolveDoT` and the DoH client's
`ProtectedSocketFactory`. OkHttp 4.12 calls only the factory's no-argument `createSocket()`, then connects the socket
itself (`RealConnection.connectSocket`). The engine has no other sockets:

- No `DatagramSocket` or channel exists anywhere in `server/`.
- The bootstrap resolver (`BootstrapDns`, `CustomDns.lookUp`) and `PublicResolver` add no sockets of their own. They
  go through the same transport (`targetTransport` -> `resolveDoT` / `buildDohClient`).

The emulator proof (0.29.0 fails at the first VPN start; the fix resolves at enrolment, after a pause and after
"Lock again") matches the code.

The tests catch the regressions that matter: no bind, a refused socket left open, and the local-address overloads
bypassing the helper. One overload is not exercised (Low 1). No High or Medium findings.

Counts: High 0, Medium 0, Low 3, Nit 1.

## Checks

- **Order.** `Socket()` -> `bind(local)` -> `protect` -> (caller) `connect`, on every path. `bind(null)` binds the
  wildcard address and an ephemeral port. Android's libcore creates the descriptor at that point
  (`IoBridge.socket(AF_INET6, ...)`, dual-stack). `VpnService.protect(Socket)` then reads a real fd.
- **Address family.** On Android a `Socket` is always a dual-stack AF_INET6 socket, and the wildcard is `::`
  (`Inet6Address.ANY`). An IPv4 target such as 1.1.1.1 is connected as a v4-mapped address, exactly as the unbound
  0.28 path did. The bind does not change the family; it only creates the socket earlier. The JVM test connects the
  local-address overloads to 127.0.0.1 and passes.
- **Leaks.** `protectedSocket` closes the socket on any exception in `bind` or `protect`. `resolveDoT` wraps the
  socket in `.use`, so a failed connect closes it. OkHttp closes `rawSocket` when its own connect fails. The factory's
  connecting overloads do not close the socket on a connect failure; see Low 2.
- **Exception type.** `NotProtectedException` is an `IOException`, as the old `IOException("not protected")` was.
  `transportReason` gives `CONNECT` (connected = false), so the chooser and `DotBlockedRule` see exactly what 0.28/0.29
  saw. See Low 3 for what that means.
- **Threads and timeouts.** No change. The bind is a local, non-blocking syscall, and `resolveDoT`'s budget and
  OkHttp's connect/call timeouts are untouched.
- **The launcher's own clients.** `createMdmApi` (`MdmApi.kt:122`) and the SSE client
  (`CommandListenerService.kt:93`) set no `.dns(` and no `socketFactory`, so they use OkHttp's default `Dns.SYSTEM`,
  through tsnet's SOCKS proxy when it is up. The VPN routes only `FAKE_DNS_SERVER/32` (`KidVpnService.kt:103-105`),
  so their TCP never enters the tun; only their name look-ups reach it, through the system resolver. They needed no
  protection. They failed in 0.29.0 only because the VPN answered no query. Confirmed.
- **Docs.** The `launcher/CLAUDE.md` bullet "protect needs a descriptor", `docs/testing/emulator.md` §6m step 8 and
  the `PLAN.md` entry are accurate. On `PLAN.md` at merge, see Nit 1.

## Findings

### Low 1 - `createSocket(String, Int)` is not tested (`ProtectedSocketTest.kt:54`, `UpstreamTransport.kt:208`)

The overloads test exercises `(InetAddress, Int, InetAddress, Int)`, `(String, Int, InetAddress, Int)` and
`(InetAddress, Int)`, but not `(String, Int)`. The mutation that replaces it with `Socket(host, port)` (connected and
never protected) **survives**: the source pin only counts `Socket()` with no arguments. OkHttp never calls this
overload, so the shipped behaviour is not affected.

**Fix:** add `factory.createSocket("127.0.0.1", server.localPort)` to the test and expect 4 protects. Or make the
source pin reject any `Socket(` constructor in `ProtectedSocketFactory`.

### Low 2 - the factory's connecting overloads leak the socket when connect throws (`UpstreamTransport.kt:208-215`)

`protectedSocket(...).also { it.connect(...) }` does not close the socket if `connect` throws. This existed before
the fix and has no effect today, because OkHttp only calls the no-argument overload and closes that socket itself.

**Fix (later, not for 0.29.1):** close the socket on failure, for example
`protectedSocket(..).apply { try { connect(..) } catch (e: Exception) { close(); throw e } }`.

### Low 3 - a refused protect still counts as a DoT failure (`PublicResolver.kt:108-111`, `:132`; `UpstreamTransport.kt:73-78`)

A `NotProtectedException` becomes `Transport(CONNECT)`. `PublicResolver` then feeds it to
`DotBlockedRule.afterDotFailure`, so 3 refusals within 60 s start a "DoT blocked" DoH period, and in `CustomDns` they
start a backup period. This is unchanged from 0.29.0; there it produced the bogus "DoT blocked: cloudflare over DoH"
seen on the emulator.

With the fix, protect should only be refused while the VPN is being torn down or replaced (another VPN took over, or
consent was revoked). There the period is wrong but harmless: a network reset or the next VPN start resets it. It
does mean the brief's requirement ("a refused protect must not be counted as DoT blocked on 853") is **not** met.

**Fix (later):** map `NotProtectedException` to an outcome that neither chooser counts, for example a dropped query
with no state change, and log it once. Not needed for 0.29.1.

### Nit 1 - `PLAN.md` conflicts with main (3cd461ca)

`main` already has the "HIGH - no network after a pause ends" entry, inserted at the same spot. Its suspected cause
(stale protected sockets) and "a reboot or Wi-Fi off/on clears it" are both contradicted by this branch's findings.
The merge will conflict.

**Fix:** keep the branch's entry, drop main's HIGH entry, and keep main's "Diagnostics from the same incident"
entry. Its item (3), a Web Filter status line for "upstream DNS can't be reached", would also have shown this bug
under the providers; today `dns_upstream` exists only with a custom server.

## Mutations

Each was run against `ProtectedSocketTest` and reverted with `git checkout`:

| Mutation | Result |
|---|---|
| `socket.bind(local)` removed | caught: 3 of 4 tests fail (`protect sees a bound socket`, the overloads, the source pin) |
| `socket.close()` removed from the refusal path | caught: `a refused socket is closed and named` |
| `(String, Int, InetAddress, Int)` -> `Socket(host, port, localHost, localPort)` (helper skipped) | caught: the overloads test (3 protects expected) |
| `(String, Int, InetAddress, Int)` -> the pre-fix `createSocket().also { bind(..); connect }` | caught: `SocketException` (already bound) |
| `(String, Int)` -> `Socket(host, port)` | **survives** (Low 1) |

## Suite

`testDebugUnitTest -PwarningsAsErrors=true` on e561f4fe: **1392 tests, 0 failures, 0 errors, 0 skipped**
(`ProtectedSocketTest` 4). BUILD SUCCESSFUL in 1 m 33 s.
