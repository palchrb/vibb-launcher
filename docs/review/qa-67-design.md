# QA 67 (design): the browsers' secure-DNS bypass and "DNS can't be reached"
Reviewed `docs/design/67-dns-bypass.md` at main d157da75 against the launcher and server code it cites. Read-only, no builds.

**Verdict: 2 High, 4 Medium, 7 Low, 5 Nits. Fix the Highs and Mediums in the note before the build.**
§2 (the bypass) holds. The never-our-upstream claim is true in the code, the managed config is safe, and the canary
sits in the right place. §3 (the outage window) is the weak part. It leaves out the very incident that asked for it
(every socket refused by `protect()`). It would also call a phone without any network "DNS can't be reached", and it
misses the common "connection open, nothing answers" failure. The parent-facing words claim more than §2.2 delivers.

## High

**H1 - The incident that asked for diagnostic (3) is not counted.** §3.1 (note:155-157) counts neither
`Attempt.Unprotected` nor "an exception in our code", and leaves them to S-M2. The incident behind (3) was launcher
0.29.0, where every upstream socket was refused by `protect()` and the VPN answered no query (PLAN.md:766-770,
UpstreamTransport.kt:196). Every query was then `Unprotected` and dropped (CustomDns.kt:105-106, DnsFilterEngine.kt:174).
Under this note the parent would still see "Your server in use". To the family the cause doesn't matter: nothing
resolves. *Fix:* count a dropped query as failed whatever the reason (`Unprotected` and our own exceptions too). The
3-query/30 s window already filters out the short VPN hand-overs that `Unprotected` was kept out for. The chooser and
`DotBlockedRule` keep ignoring it, as they do today.

**H2 - No network reads as "DNS can't be reached".** The note never tells an offline phone from an unreachable
upstream. In airplane mode, or a Wi-Fi-only phone away from home, the VPN stays up. Apps' look-ups still reach
`handlePacket`, and every protected connect fails at once (`Transport(CONNECT)`). After 30 s that becomes an outage,
and when the network returns the parent sees "couldn't be reached 15:10-16:40" for an ordinary walk. The service already
tracks the best non-VPN network and whether it is validated (KidVpnService.kt:84, 197-229, `NetworkSnapshot`), and it
treats losing the network as "nothing to try" (UpstreamChooser.kt:477-483). *Fix:* call `afterFailure` only while
`lastNetwork?.validated == true` (make it `@Volatile`). A failure without a validated network leaves the state alone.
Say this in §3.1 and add an `UpstreamReachTest` row and an E3 step (`svc wifi disable`: no outage).

## Medium

**M1 - Silence after the request was written is neither a failure nor an answer.** §3.1 (note:150-153) defines a
failed query by `Transport` outcomes only. A silent server after the write is `Odd(TIMEOUT, null)` (UpstreamTransport.kt:90,
152, 314). The backup relays `outcome.bytes`, which is `null` here (CustomDns.kt:132-135). The provider returns it as it
is (PublicResolver.kt:136-138), and `resolveProvider` then drops it (DnsFilterEngine.kt:173). That is the usual shape of
"Wi-Fi up, internet gone" on a pooled DoH connection: OkHttp keeps an HTTP/2 connection after a stream timeout, so every
query writes and then times out. *Fix:* define it by what we hand back. Failed = the query is dropped with no bytes from
any server (H1 included). Answer = server bytes relayed. Our own synthesised SERVFAIL after a canary we gave up on
(CustomDns.kt:116-119) is neither, and so is the canary (§2.3). One rule, read from one place in the glue.

**M2 - A stale first failure can date an outage hours back.** `afterFailure` keeps `firstFailMs` until an answer comes
(note:161-164). Take one stray failure at 23:00, a dozing phone that asks nothing, then two failures at 07:00: that
makes "since 23:00", and later "couldn't be reached 23:00-07:01". The 30 s rule is met by the gap, not by an outage.
*Fix:* a failure more than 60 s after the previous one starts the count again (`failures = 1`, `firstFailMs = now`)
while not down. Add a test row: 1 failure, 2 h, 2 failures gives no outage.

**M3 - Fixing the servers erases the record, and E3 check 13 can't pass.** "A new `rev` ... starts a fresh state"
(note:165), and the server hides outages at an old rev (note:218-219). Changing either server bumps `custom_rev`
(dns_filter.rs:621, 662), and that makes a new `CustomDns` (KidVpnService.kt:183-190). So check 13 ("set the backup to
`https://1.1.1.3/dns-query` ... couldn't be reached HH:MM-HH:MM", note:300-301) never shows the ended line. The parent
who fixes the servers is exactly the one who wants that confirmation. *Fix:* a new rev or provider resets the counter
and any ongoing outage, but keeps `lastOutage`. The server shows an ended outage whatever its rev.

**M4 - The parent-facing words promise more than the host list delivers.** The footer says "the public secure-DNS
servers are blocked, so look-ups go through the filter" (note:137-138), and §1 says user-chosen modes "resolve nothing"
(note:18-20). In a browser that ignores the managed config (Firefox, Cromite; maybe Brave or Edge, E3), a custom provider
on an unlisted host bypasses the filter completely: there are hundreds of public DoH servers, plus anyone's own NextDNS
or AdGuard host. So does an IP-literal template (PLAN.md:713-719 for Cromite). Only the managed config really closes it,
and only in Chrome. *Fix:* footer: "Chrome's own secure DNS is turned off; the best-known secure-DNS servers are
blocked". Add Firefox (`org.mozilla.firefox`) to `DOH_IGNORING_BROWSERS`, with a line like "Firefox: its own secure DNS
can get around the filter" (wording after E3 check 11). Say in §1 that the list is a best-effort backstop.

## Low

- **L1 - The `doh` first-label rule blocks real sites.** `doh.wa.gov` (Washington State Department of Health),
  `doh.gov.ph` and `doh.gov.ae` are health departments, and the parent can't lift the category (Q1). *Fix:* list the
  known `doh.*` resolver names in the asset instead and drop the rule (less code). If it stays, add `doh.wa.gov` to the
  misses test (note:245).
- **L2 - "In force before the first sync" won't hold where the note says to load the asset.** `loadFromDisk` returns
  early when no blocklist file exists yet (DnsFilterEngine.kt:107). *Fix:* load the asset before that return, or as a
  lazy `val` on the object.
- **L3 - `phone_line` would show a provider report as the backup.** Its backup arm takes any `in_use` other than
  `custom` at the matching rev (dns_filter.rs:266-279). Lines are built only in custom mode (dns_filter.rs:438-445), so
  provider mode needs its own path. *Fix:* give `provider` its own match arm. The tests should include a provider
  report at rev 0 while custom mode is in force.
- **L4 - "Update Vibb One to close the bypass" would show for a phone whose filter is off** (note:140-141). There is
  nothing to close there. *Fix:* show it only while `vpn_filter_enabled` is on. `phone_lines` already reads it.
- **L5 - `CustomDnsGuardsTest` can't fail.** `CustomDns` never calls `classify`; the only caller is
  KidVpnService.kt:312. The test (note:248-249) proves nothing. *Fix:* drop it; E3 check 12 covers the behaviour. The
  structural guarantee is "the set is consulted only from `handlePacket`", which is worth one sentence in the code.
- **L6 - Nothing tells the service that the provider changed.** It is read from prefs for every query
  (KidVpnService.kt:320). *Fix:* keep the provider (or rev) inside the `UpstreamReach` state, and start a fresh state when
  a query's key differs. Also say what the line shows with `dns_upstream` absent (tunnel down): today "Waiting for the
  phone" in custom mode (dns_filter.rs:282-284), and nothing in provider mode.
- **L7 - The contract change misses a reader and is bigger than it needs to be.** UpstreamChooserTest.kt:157 also
  encodes the contract and is not in step 3's list. The root CLAUDE.md describes the file as one body. *Fix:* keep one
  body (row 1 with the two new fields): the file pins field names, not modes. Test provider mode in `UpstreamReachTest`
  and the sanitizer tests. Otherwise list both readers and the CLAUDE.md paragraph.

## Nit

- N1: the server-host exemption is close to dead code. The API goes through tsnet's SOCKS proxy, which resolves names
  remotely (MdmApi.kt:135), and a self-hosted host matches the list only through L1's rule. The exemption is no hole
  either: the server speaks no DoH (dns_engine.rs:6-14; no listener in main.rs). If L1 drops the rule, drop the
  exemption with it. If it stays, say that the tsnet control URL isn't exempt.
- N2: `sameBundle` is private in MusicSync.kt:146, so make it shared rather than copy it. On the DPM:
  `setApplicationRestrictions` stores a bundle for a package that isn't installed (no install check, removed at
  uninstall). The "installed only" filter is safe, but not required.
- N3: Q1 "yes" would need a contract change, because the phone has no allow input. Say so where the question is asked.
- N4: E3. Check 9's "before" part needs main's APK installed first, so say so. Check 12 doesn't say how the primary fails
  (use `wrong.host.badssl.com`). Check 14 runs on jelly36 with the host's iptables (`-d 1.1.1.1 --dport 443` and
  `--dport 853`), with no guest root. WhatsApp in check 15 needs a registered number, so it is a device check. Add H2's
  Wi-Fi-off step. The others run on jelly36 as written (Chrome is preinstalled; Firefox, Edge, Brave and Vivaldi come
  from Play).
- N5: length. About 60 of the 315 lines can go. §1's last three non-goals repeat §2.3 and §2.4. §2.1's last paragraph
  can be 2 lines. §2.2's seed list belongs in the asset's comments. §3.1's "Why both" and "This fits design 36" can be
  2 lines each. §4 can be 4 lines. Keep the per-browser table.

## Checked, holds
Upstream resolution never touches the filter. The providers are IP literals, through `IP_LITERAL_DNS`
(UpstreamTransport.kt:287-290, PublicResolver.kt:175-190). The parent's names are resolved by `BootstrapDns` over the
backup or primary given as an IP, or Cloudflare by IP (BootstrapDns.kt:89-94), and the DoH client's `Dns` is the
bootstrap (CustomDns.kt:52). Every socket is protected (UpstreamTransport.kt:200-211). `classify` has a single caller.
So a parent's `dns.quad9.net` or `family.cloudflare-dns.com` upstream keeps working while the kid's browser can't
resolve it. `vpnFilterEnabled` is false under the override and the pause (AppEnforcer.kt:322, 549). The canary branch
never reaches `BlockedEventLog` (KidVpnService.kt:314-316), and `answerTo` gives the QR/RA/RD shape. Older reports
still parse: the new `Option` fields are missing and become `None`, and an older server stores `provider` as null
(dns_server.rs:216-240). CI already triggers on the contract file in both workflows. The note matches the Cromite
findings (PLAN.md:713-719).
