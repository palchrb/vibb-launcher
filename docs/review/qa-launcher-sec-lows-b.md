# Code QA: `launcher-sec-lows-b` (security review 2026-10-21, launcher round L4b)

Branch commits d3ca4925 (S-L9), 6789adea (S-L10 + Leave Tailnet), 95e3ee94 (P-L2/P-L3), 4537a0f9 (S-L5). Reviewed by
reading the code only (no Gradle/cargo; the builder reports server 1711/0, fmt, clippy clean, launcher 2047/0).

**Verdict: 0 High / 2 Medium / 4 Low (+ 2 nits).** Mergeable after M1 (a small fix) and a decision on M2.

## Medium

**M1. The crash guard counts our own Kotlin crashes as tsnet crashes** - `server/TailnetKey.kt:48-58`,
`server/TsnetClient.kt:266-278`, `Application.kt:217-230`.
The app's uncaught-exception handler ends the process with `exitProcess(1)` and never hands the crash to the
platform handler. Android therefore records every Kotlin crash in the main process as `REASON_EXIT_SELF`, and
`TSNET_CRASH_EXITS` accepts that reason with any exit status. The note in `security.md` says this outright.
Scenario: tsnet connects. Within 10 min an ordinary Kotlin bug crashes Home, which restarts and connects again. The
same bug crashes it a second time. The next process skips tsnet for 15 min, and the loop repeats for as long as the
crash keeps happening. A phone whose server is tailnet-only then loses its server exactly while it is crash-looping.
`CrashReports` can't be delivered, and no fixed release can arrive over the tailnet.
Fix (one line): for `REASON_EXIT_SELF`, also require `exit.status == 2`. Go's runtime exits with 2 on a panic or a
fatal error; our handler exits with 1. Pass `exit?.status` into `deathAfterConnectIsCrash` and pin it with a test row.

**M2. Leave Tailnet on a phone whose server URL is a Tailscale address leaves it permanently in S-M1's open case** -
`server/TsnetClient.kt:286-306`, `MdmApi.kt:155`, `device.advanced.leave_tailnet_confirm`.
`http://100.x` and `http://[fd7a:115c:a1e0::…]` are supported server URLs (DEPLOY.md, `PHONE_SERVER_URL`).
`usesCleartextTraffic="true"` is still set, and `createMdmApi` silently goes direct when `TsnetClient.proxy()` is
null. S-M1 is still open: no fail-closed check exists in the code. After `leave`, the `tsnet_node_joined` mark is gone
too, and that mark is exactly what S-M1's planned guard ("a phone with a key or `tsnet_node_joined` never builds a
client without the proxy") would key on.
Scenario: on mobile data, `100.64.0.0/10` is the carrier's CGNAT space; on any Wi-Fi it goes to the gateway. Every
sync and SSE reconnect then sends `Authorization: Bearer <device token>` in cleartext. The phone also takes whatever
plain-http answer comes back: a policy that opens everything, or a `pending_command` such as `start_pause`.
The confirm text only says "it loses contact", which understates this: the phone doesn't lose contact, it starts
talking to whoever answers that address. Before this branch the same happened only while tsnet was down; Leave
makes it permanent.
Fix, in either place:
- Launcher: answer `failed to leave the tailnet` (or a fourth contract row, "server only on the tailnet") when the
  server URL's host is a Tailscale address.
- Server: hide or refuse the row for a phone set up with such a URL, and say why.

`https://…ts.net` is unaffected: it fails closed.

## Low

**L1. A kept DoT connection that stays silent is usually reported as a connect failure, not as silence** -
`UpstreamTransport.kt:145-156`.
On the kept path, `readFully` gets `soTimeout = left` against the same absolute deadline the `DotDeadline` watchdog
uses, and it throws `SocketTimeoutException` itself once `left <= 0`. Whichever fires first decides the outcome:
- The read's timeout wins (likely; the watchdog's close needs another thread to run): `watch.end()` returns true, so
  the catch logs "Kept DoT connection gone", drops the key, hits `remainingMs() <= 0 -> break` and returns
  `Transport(CONNECT)`.
- The watchdog wins: the result is `Odd(TIMEOUT)`, which is what the KDoc and the test expect.

Consequences of `Transport(CONNECT)`:
- The parent's server: `UpstreamChooser.afterTransport` goes to the backup with no canary.
- A public provider: `DotBlockedRule.afterDotFailure` counts towards switching to DoH.

The test's fake link throws only when it is cut, so it never sees the read-timeout order.
Fix: in the kept catch, `if (!watch.end() || e is InterruptedIOException) return afterWriteOutcome(...)`.

**L2. Location stays on if the process dies during the up-to-15 s fix** - `LocateCommands.kt:129-138`.
The `finally` covers exceptions and cancellation. A kill in the window (low memory, or the kid's Force stop if it is
reachable) leaves Location on. Under `on_request` and `interval`, `systemLocationTarget` is `null`, so nothing
switches it back off. Fix: persist "the sync switched Location on" before the switch and act on it at the next sync.
Rare; the class of problem is P-L2's own.

**L3. An A2DP-sink UUID outranks a PHONE/COMPUTER major class** - `BluetoothAudio.kt:65-66`.
A spoofed phone is not realistic: stock Android and iOS can't change their class of device or advertise an A2DP sink
without root. A friend's Linux laptop is: PipeWire and PulseAudio advertise the A2DP sink UUID in EIR by default, so
`btKind` returns `A2DP` and the laptop is offered pairing. With `DISALLOW_BLUETOOTH_SHARING` set, the gain is small
(PBAP/MAP still ask). Cheap tightening: `major in {PHONE, COMPUTER}` -> not audio, whatever the UUIDs say.

**L4. Pooled connections for a key nobody asks any more stay open until the next network change or VPN stop** -
`UpstreamTransport.kt:308-330`.
`take` sweeps only its own key. Scenarios: the parent switches the custom server, or Cloudflare to Quad9, or a DoT
host's address rotates. Up to 4 fds per stale key are left in CLOSE_WAIT. This is bounded and has no security
effect. Sweep every key's expired entries in `give`.

## Nits

- After `leave` (`MdmSyncWorker.kt:530`), the rest of that sync still uses `api` built on the closed loopback SOCKS
  port, and the global `Authenticator` still answers for it. Another app that binds that port within the same sync
  would get the remaining calls. That needs a hostile allowlisted app racing the port, so it is very unlikely. Ending
  the sync's server calls after `leave_tailnet`, or rebuilding `api`, closes it.
- P-L3's age rule also applies to `locate`. A lost phone whose fresh fix fails indoors now reports no location,
  where it used to report the last-known one with its age. The server's last stored row still shows, so only
  last-known fixes taken by other apps are lost. This is accepted per the finding; mention it in the Locate words if
  parents ask.

## Checked, nothing found

**DoT pool**
- Exclusive take, so never two queries on one connection.
- A connection is given back only after a whole answer that matched the ID before the deadline. A stale answer can't
  be waiting on a kept connection: a timed-out query's connection is cut.
- The key includes the SNI host, so NextDNS-style per-profile hostnames and the parent's server versus the public
  resolvers never share a connection or an answer.
- `synchronized` everywhere; the sockets are closed outside the lock.
- The `end()`/watchdog race can close a pooled link, which only costs a retry.
- Closed at stop, so a late `give` is cut.
- Concurrency is bounded by `dnsSlots`, and idle connections by 4 per key.
- The ID check on fresh connections only turns a wrong-ID answer into a transport failure (fine).

**Crash guard (apart from M1)**
- Android's kills don't count.
- A Go panic, abort or native crash within 10 min counts.
- A failed `getHistoricalProcessExitReasons` reads as "lived" (fail-open, as before).

**Leave Tailnet (apart from M2)**
- It is on the admin router next to `/revoke`, so the same session and CSRF apply.
- It needs `confirm == "yes"`, and the box is `required` in the template.
- It goes through `queue_command` and is logged as `device_command_queued`.
- It returns to `#connection` both ways, pinned by `advanced_forms_return_to_their_groups`.
- `leave` is `@Synchronized` with `connectFromPreferences`, so no connect can run mid-leave.
- The marks are removed before the state directory, so a death halfway through never rejoins.
- The key is removed from the same default prefs `forgetKeyOnceJoined` uses.
- The receipt goes out through the tailnet before the command runs, so the command isn't re-offered.
- Running it again answers "not on the tailnet".
- The contract file is byte-identical in both directories (`cmp`), and both workflows' path filters include it.

**Location**
- Only `locate` forces a fix.
- The restore is in a `finally`.
- `staysOn` covers the CONFIG_LOCATION hardening switch and zones; Off wins.
- Every path out of `currentLocation` that returns a stored fix goes through `reportable`.
- Off clears both the cached fix and the kept one.

**Bluetooth**
- The switch is saved only with `section=quick` (COALESCE) and is off by default.
- An older server reads as off, which fails closed.
- The pause and the offline override lift it, per the standing rule.
- The list is filtered when the scan finds a device, again at the tap, and on every apply (posted to main).
- Bonded devices keep their row.

**API contract**
- `bluetooth_any_device` is in both `policy_json_keys_snapshot` lists and in `PolicyResponseCompatTest`.
- en and nb are complete for every new key.

**Migration**
- `0124` is the next number after `origin/main`'s `0123` (main at c9ff464b, 2026-11-04).
- No other branch in the repo has a `0124`.
