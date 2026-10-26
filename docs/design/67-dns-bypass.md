# Design 67: the browsers' secure-DNS bypass, and "DNS can't be reached" on the Web Filter

Revised 2026-10-26 after `docs/review/qa-67-design.md` (every finding applied).

Round L3 (PLAN.md, "Round L3 - DNS: the secure-DNS bypass and the upstream line"), branch `dns-bypass`, launcher and
server. Two approved items: launcher security review S-M3 (PLAN fix backlog, approved 2026-10-21, "the user's yes to
all three") and the incident's diagnostic (3) (PLAN, "Diagnostics from the same incident").

## 1. Why, and what this round leaves out

- S-M3: "One switch in an allowed browser ('Use secure DNS') bypasses the whole filter." The user said yes to all three
  mitigations: Chrome's managed config, a built-in block of the DoH hosts, Firefox's canary answered NXDOMAIN.
- Diagnostic (3): "Web Filter's status line should say 'upstream DNS can't be reached' while every upstream attempt
  fails ... so the parent sees the cause before the music app does." The incident was launcher 0.29.0, where every
  upstream socket was refused by `protect()` and the VPN answered no query (PLAN.md:767-771). §3 is built so that
  incident would have shown.

Honest reach: only the managed config (§2.1) really closes the bypass, and only in browsers that read it (Chrome; the
others per E3). The host list (§2.2) is a **best-effort backstop** for the best-known public resolvers, not the hundreds
that exist or anyone's own NextDNS/AdGuard host. In a browser that ignores the managed config (Firefox, Cromite,
PLAN.md:713-719) an unlisted secure-DNS host, or a template by IP literal (`https://1.1.1.1/dns-query`), still bypasses
the filter. The page says so (§2.4).

Non-goals:
- **Routing the public resolvers' IPs into the tunnel and dropping 443/853/QUIC to them** (S-M3 fix 3, PLAN's
  "Later, if wanted"): what would close the hole above. Nor Cromite's or Firefox's own settings (named on the page, §2.4).
- S-M2 (the filter's own up/down state, the heal paths), except its "send `dns_upstream` only while a tunnel is up",
  because §3 rewrites that function.

## 2. The bypass

### 2.1 Chrome's managed config

Chromium's policy `DnsOverHttpsMode` (`components/policy/resources/templates/policy_definitions/Miscellaneous/
DnsOverHttpsMode.yaml`, read 2026-10-26): `supported_on: android:85-`, values `off` / `automatic` / `secure`; "If this
policy is unset, for managed devices DNS-over-HTTPS queries will not be sent." `ShouldDisableDohForManaged()`
(`stub_resolver_config_reader.cc`) is true on Android once an owner app is found, checked asynchronously. So stock
Chrome on our device-owner phone probably has DoH off already; the explicit policy closes the startup window, shows the
switch as managed, and covers forks that patch the ownership check. E3 check 9 confirms it.

**The bundle**: exactly `{"DnsOverHttpsMode": "off"}` (a String), written with `setApplicationRestrictions`, which
replaces the package's whole bundle. Only the device owner can write one, and the launcher writes no other key for
these packages.

**Which packages - decided: a fixed list of package names, not the restrictions schema.**
`RestrictionsManager.getManifestRestrictions` only finds apps that declare the key in their manifest schema; a fork can
read Chrome's keys through Chromium's `AppRestrictionsProvider` without one. A bundle sent to a browser that ignores it
does no harm. The list is `BROWSER_DOH_PACKAGES` in the new `server/BrowserDoh.kt`:

| Package | Browser | Reads `DnsOverHttpsMode`? |
|---|---|---|
| `com.android.chrome`, `com.chrome.beta`, `com.chrome.dev`, `com.chrome.canary` | Chrome | yes (documented, Android 85+) |
| `com.microsoft.emmx` | Edge | to check in E3 (`edge://policy`) |
| `com.brave.browser`, `com.brave.browser_beta`, `com.brave.browser_nightly` | Brave | to check in E3 (`brave://policy`) |
| `com.vivaldi.browser` | Vivaldi | to check in E3 (`vivaldi://policy`) |
| `com.sec.android.app.sbrowser` | Samsung Internet | to check on a device (no emulator image) |
| `com.kiwibrowser.browser` | Kiwi (discontinued 2025) | to check in E3 if it still installs |

Not listed: Cromite `org.cromite.cromite` (its `Disable-Android-AppRestrictions.patch` drops the provider by default),
Firefox (§2.3), Vanadium (not on these phones). A browser that turns out not to read the key stays on the list; the
table in `launcher/CLAUDE.md` records the E3 result.

**When**: in `AppEnforcer.apply`, right after `applyVpnRestrictions` (AppEnforcer.kt:550), with the same
`vpnFilterEnabled` (`false` under the override or a pause). The pure `browserDohPlan(filterOn, installed)` returns
`pkg -> Bundle` for every installed listed package: the bundle above while the filter is on, an empty one while off. The
glue writes only when it differs from `getApplicationRestrictions`, with MusicSync's `sameBundle` (private at
MusicSync.kt:146, used at :121), made a shared `internal` top-level function. "Installed only" is not required (the DPM
stores a bundle for an uninstalled package and drops it at uninstall); it keeps the reads to real packages. The list
does not follow the allowlist: an unallowed browser is hidden or suspended, and the day the parent allows it, it starts
with DoH already off. A browser installed later gets its bundle at the next `apply()`; until then Chromium's
owned-device default holds it. No new policy key (the per-phone Web Filter switch drives it), so
`policy_json_keys_snapshot` / `PolicyResponseCompatTest` do not change.

### 2.2 The DoH-host category - decided: built into the phone

Built in, not a server category. It keeps the blocklist contract (`GET /api/devices/dns-blocklist`) unchanged, it is in
force before the first sync, and a server feed can't empty it. The parent can't lift it (open question 1); the parent
sees it as a fixed row (§2.4).

- **The list**: `launcher/app/src/main/assets/dns/doh_hosts.txt`. One lowercase name per line, `#` comments, each entry
  matched as a suffix (as `classifyDomain` walks today). The file's comments say where each group came from (the
  seed): Chromium's `net/dns/public/doh_provider_entry.cc` (`dns.google`, `cloudflare-dns.com`, the Quad9 `dns*`
  names, `dns.nextdns.io`, `dns.sb`, ...), the common public resolvers' DoH/DoT names (AdGuard, Mullvad, ControlD,
  CleanBrowsing, `one.one.one.one`, `8888.google`, ...), and the `doh.*` resolver names, **listed one by one**:
  `doh.opendns.com` (Cromite's default template), `doh.familyshield.opendns.com`, `doh.cleanbrowsing.org`, ...
  **No first-label rule** (`doh.`, `doh-<n>`): it would block real sites (`doh.wa.gov`, `doh.gov.ph` and `doh.gov.ae`
  are health departments) that the parent can't unblock, and the list needs no code for it.
- **Where**: `classifyDomain` gains the built-in set as a third input. Order: the FCM and Spotify exemptions, then the
  built-in set (category `"Secure DNS bypass"`), then the server's lists. The answer is the phone's NODATA
  (`blockedResponse`), as for any block. `DnsFilterEngine.loadFromDisk` loads the asset as its first statement,
  **before** the `if (!listFile.exists()) return` (DnsFilterEngine.kt:107), so the set is in force on a phone that has
  never synced a list.
- **Never our own upstreams - the predicate.** The set is consulted only from `KidVpnService.handlePacket`
  (`classify`'s single caller, KidVpnService.kt:312), that is, for a UDP/53 query that arrived through the tunnel at
  `192.0.2.2`; one sentence in `classifyDomain`'s doc says so. The launcher's own upstream traffic never arrives
  there:
  the providers are IP literals (DoT with SNI; DoH through `IP_LITERAL_DNS`, which never looks a name up), the
  parent's servers are looked up by `BootstrapDns` on protected sockets to a fixed resolver (design 36 §5.3), and every
  upstream socket is `protect()`ed. So a parent's backup `https://family.cloudflare-dns.com/dns-query` still works, while the kid's apps can't resolve
  that name. The predicate is `suffixIn(d, DOH_HOSTS)`, `d` the query name lowercased with its trailing dot trimmed.
  **No exemption for the launcher's own server host**: its API calls go through tsnet's SOCKS proxy, which resolves the
  name remotely (MdmApi.kt:135), and the admin server speaks no DoH, so nothing of ours needs one. The parent's DoH
  hosts get none either: a browser template pointing at an exempt name would reopen the bypass.
- **Side effect, accepted**: apps that use public DoH only as a censorship fallback (Telegram's `dns.google/resolve`)
  fall back to the system resolver, which is filtered. E3 checks that Element X still connects (WhatsApp needs a
  registered number: a device check).

### 2.3 Firefox's canary

Mozilla's "Canary domain - use-application-dns.net" (support.mozilla.org; read through search on 2026-10-26). Firefox resolves the name through the system resolver. Any RCODE other
than NOERROR, or a NOERROR without A/AAAA, disables DoH, but **only for users whose DoH was enabled by default**. A
user who turned DoH on (Increased or Max protection) is not affected: Increased falls back to our filter, Max uses
its own host (an unlisted one bypasses; a listed one resolves nothing). Firefox for Android's DoH settings and
defaults: to check in E3.

- **The answer**: `NXDOMAIN` (RCODE 3), question echoed, QR/RA set, RD copied, with `blockedResponse`'s negative-cache
  SOA (RFC 2308), built by a new `nxdomainResponse(request)` sharing `answerTo`.
- **Where**: `handlePacket`, before `classify`, for the exact name `use-application-dns.net` (any QTYPE; a subdomain
  is not the canary). Never forwarded, never in `BlockedEventLog` (KidVpnService.kt:314-316; Firefox's routine probe,
  not the kid's doing), and not counted by §3 (it is never sent upstream). Answered whether or not a server list blocks it.

### 2.4 What the parent sees (Web Filter page)

- **Blocklists** group: a fixed row after the lists, without a switch: "Secure DNS servers", sub "Built into Vibb One,
  on with the filter". Footer addition, worded for what it does: "Chrome's own secure DNS is turned off, and the
  best-known public secure-DNS servers are blocked. Firefox and Cromite can still be set to another secure-DNS server by
  hand."
- **Each Phone** group: one line per phone only where something is owed, and only while that phone's filter
  (`vpn_filter_enabled`, which `phone_lines` already reads) is on:
  - "Anna · Update Vibb One to close the secure-DNS bypass": a phone whose last report lacks the capability
    `dns_bypass_v1`, which the launcher claims once §2.1-§2.3 are built.
  - "Anna · Cromite: set its Secure DNS to Off on the phone, or it won't load pages" and "Anna · Firefox: its own
    secure DNS can get around the filter" (final words after E3 check 11): a phone whose allowlist (server side) holds a
    package from `dns_filter::DOH_IGNORING_BROWSERS` (`org.cromite.cromite`, `org.mozilla.firefox`; server only, no
    contract).
  No per-browser status from the phone: the bundle is fixed, and Chrome itself shows "managed".

## 3. "DNS can't be reached"

### 3.1 The phone's window - decided: at least 3 failed queries spread over at least 30 s, with no answer between

The unit is **a query the VPN handled**, judged by what the glue hands back, in one place. `CustomDns.resolve` and
`DnsFilterEngine.resolveProvider` return `Resolved(bytes, answered)`, and the pure `reachKind(resolved)` reads:
- **FAILED**: `bytes == null`, whatever the cause - every upstream attempt a transport failure (custom: the primary,
  then the backup, or no address for the backup; provider: DoT, then DoH), **silence after the request was written**
  (`Odd(TIMEOUT, null)`, UpstreamTransport.kt:90-91/152/314, which the backup and the provider return as `null`
  bytes, CustomDns.kt:132-136, PublicResolver.kt:136-138, DnsFilterEngine.kt:171-174), **`Attempt.Unprotected`**
  (CustomDns.kt:106, DnsFilterEngine.kt:174) and an exception in our code (`CustomDns.resolve`'s catch). To the
  family the cause does not matter: nothing resolves. With this the 0.29.0 `protect()` incident shows, and the
  3-query/30 s window filters out the short VPN hand-overs that `Unprotected` was kept out of the chooser for.
- **ANSWERED**: server bytes relayed (NOERROR, NXDOMAIN, a relayed REFUSED/SERVFAIL), and a synthesised
  NODATA/SERVFAIL after a healthy canary (design 36 §5.4: the server proved itself alive).
- **NEITHER**: our own SERVFAIL after a canary this query gave up waiting for (CustomDns.kt:116-119), the canary
  itself, a blocked name and the Firefox canary (never sent).

The chooser and `DotBlockedRule` keep ignoring `Unprotected` as today (CustomDns.kt:106, PublicResolver.kt:132-134);
only the reach count reads it.

**Offline is not an outage.** A failure counts only while the network is validated: `KidVpnService.lastNetwork`
(KidVpnService.kt:84, set by the callback at :197-229) becomes `@Volatile` and the glue passes
`lastNetwork?.validated == true`. In airplane mode, or on a Wi-Fi-only phone away from home, the VPN stays up and every
connect fails at once; with no validated network a failure leaves the state alone, and the phone is "offline", not
"DNS unreachable". `onNetwork` also tells `UpstreamReach` when the validated network is lost: an ongoing outage ends
then (an outage with no network is nobody's server) and the count clears. This mirrors `networkChanged`, which already
treats losing the network as "nothing to try" (UpstreamChooser.kt:128-135).

Pure `UpstreamReach` (new `server/UpstreamReach.kt`), **one `AtomicReference` per VPN service instance**, changed by
CAS: `{key, failures, firstFailMs, lastFailMs, downSinceMs?, lastOutage: (sinceMs, untilMs)?}`, in elapsed realtime.
`key` is the servers the query was sent to: the custom `rev`, or the provider's name (`KidVpnService` reads the provider
from prefs for every query, KidVpnService.kt:320, so the key rides on each call).
- `afterFailure(s, key, now)` (validated network only): a key that differs first runs the rekey below. A failure more
  than **60 s** after `lastFailMs`, while not down, restarts the count (`failures = 1`, `firstFailMs = now`): a stray
  failure can't date an outage hours back. Otherwise `failures + 1`; `firstFailMs = now` at the first. If not down,
  `failures >= 3` and `now - firstFailMs >= 30 s`, then `downSinceMs = firstFailMs` (the outage dates from its first
  failure). Log `Upstream unreachable` once.
- `afterAnswer(s, key, now)`: if down, `lastOutage = (downSinceMs, now)` and `downSinceMs = null`. Then `failures = 0`.
- **Rekey** (a new `rev`, or another provider): an ongoing outage ends at that moment into `lastOutage`, the count
  clears, and the new key is set. **`lastOutage` survives**: the parent who fixes the servers is the one who wants the
  confirmation (§3.3). Changing either server bumps `custom_rev` (dns_filter.rs:621, 662), which makes a new
  `CustomDns` (KidVpnService.kt:181-190), so the reach state lives in the service, not in `CustomDns`. A VPN restart
  starts a fresh state, `lastOutage` included. A network reset (`networkChanged`) changes nothing.

Why both: a count alone fires on one burst (20 names in 2 s while the network switches), time alone claims an outage
on an idle phone; the stub retries about every 5 s, so 30 s and 3 failures are at least two failed look-ups. In custom
mode nothing counts while the backup answers (design 36 §5.4).

### 3.2 The status field

The `dns_upstream` object gets two fields, in both modes, while the tunnel is up:

```json
"dns_upstream": {"rev": 4, "in_use": "backup", "since_ms": 1792312345000, "reason": "tls",
                 "unreachable_since_ms": 1792312400000, "unreachable_until_ms": null}
```

- `unreachable_since_ms` / `unreachable_until_ms`: wall-clock ms or `null` (as `upstreamReport` converts). Ongoing:
  `since` set, `until` `null`. The last ended outage: both. None: both `null`. Always sent (`DnsUpstreamReport` has no
  defaults).
- **Provider mode now reports too**: `{"rev": 0, "in_use": "provider", "since_ms": null, "reason": null, ...}`. An
  outage on Cloudflare or Quad9 (a network that blocks 853 and their 443) is just as real. An older server's
  `sanitize_dns_upstream` refuses `provider` and stores `null`, as today.
- `KidVpnService.dnsUpstreamReport()` (KidVpnService.kt:415) returns `null` unless the tunnel is up. S-M2's second bullet.
- The ended outage is carried because the status report itself often can't get out while DNS fails; the first report
  after recovery still tells the parent. Capability: none; missing fields mean nothing is known.

**The contract** `server/testdata/dns_upstream_status.json` (byte-identical in `launcher/app/src/test/resources/`) **stays
one body**, as the root CLAUDE.md describes it: today's body with `dns_upstream` growing the two fields (an ongoing
outage, as above) and `"capabilities": ["dns_custom_v1","dns_bypass_v1"]`. It pins field names, not modes. Its three
readers change in step 3: `DnsUpstreamStatusTest`, `UpstreamChooserTest.kt:157` (the `the status field is the
contract's` test builds the expected report from it) and the server's `custom_dns::dns_upstream_status_contract`. Provider mode
and an ended outage (`until` a number) are pinned by `UpstreamReachTest` (the report built from the state) and the
server's sanitizer tests.

### 3.3 The server

- **Storage**: inside `device_status.dns_upstream_json` (no migration). `DnsUpstreamState` (dns_server.rs:202) gains
  `unreachable_since_ms`/`unreachable_until_ms` (`Option<i64>`, serialized as `null`). `sanitize_dns_upstream`
  (dns_server.rs:216) accepts `in_use` `provider` (its `reason` dropped), keeps `since` when plausible (2000..2100), and
  keeps `until` only with a kept `since` and `until >= since`.
- **The lines** (`dns_filter::phone_line`, dns_filter.rs:246, the DNS Server group's footer). `phone_line` today treats
  any `in_use` other than `custom` at the matching rev as the backup, and lines are built only in custom mode
  (dns_filter.rs:440), so **`provider` gets its own match arm and, for provider mode, its own path**:
  - Custom, ongoing (matching `rev`, `in_use` custom or backup): "Anna · DNS can't be reached since 10:42 - neither
    your server nor the backup answers". It replaces the custom/backup words while it lasts.
  - Provider mode, ongoing: "Anna · DNS can't be reached since 10:42 - Cloudflare doesn't answer from where the phone
    is" ("Quad9" by the stored provider). In provider mode the footer lists only phones with such a line (filter on).
  - A report with `in_use` `provider` while custom is in force (even at rev 0, equal to `custom_rev`): "Waiting for
    the phone", never the backup.
  - Ended within the last 24 h (`until` from now), **whatever the report's `rev` or mode** (the fixed server is the
    one that bumped it): appended to the phone's line as " · couldn't be reached 10:42-11:05"; in provider mode it stands
    alone ("Anna · DNS couldn't be reached 10:42-11:05"). Not shown while the phone's filter is off.
  - No `dns_upstream` (the tunnel is down, or an older launcher): "Waiting for the phone" in custom mode, as today
    (dns_filter.rs:282-284); nothing in provider mode.
  - Times through `zone.when`; `nb` wording comes with the PWA's translation. The ongoing line goes with the first
    report after an answer; the ended line 24 h after its `until`, or at the next outage.

## 4. Security and privacy

- No new browsing data leaves the phone: a category hit reaches the server only through `BlockedEventLog` (only while
  the parent has that phone's log on); the canary is never logged; the outage fields are times only.
- The managed config can only tighten: one fixed key and value from the launcher's code, nothing from the policy, no
  package outside the list. The category only adds blocks (§2.2's predicate). The sanitizer keeps known words and
  plausible integers only (the contract test's "sneaky" row grows the two fields with strings).

## 5. Steps (branch `dns-bypass`, one round)

1. **Launcher: the category and the canary** (S). `assets/dns/doh_hosts.txt`, `DnsFilterEngine.kt` (load before the
   early return, the third input to `classifyDomain`), `KidVpnService.handlePacket` (canary first), `nxdomainResponse`.
   Tests in `DnsFilterEngineTest`:
   every file line is a valid lowercase LDH name, no duplicates; hits `chrome.cloudflare-dns.com`, `dns.google.`,
   `doh.opendns.com`; misses `example.com`, `dohmain.com`, `doh.com`, `notdns.google.com`, `doh.wa.gov`, `doh.gov.ph`;
   FCM still comes first; the set loads when no list file exists.

   `NxdomainTest` (in that file): RCODE 3, the SOA, the ID/RD echo. No `CustomDns` guard test (it never calls
   `classify`, so it could not fail); E3 check 12 covers it.
2. **Launcher: the managed config** (S). `BrowserDoh.kt` (list, `browserDohPlan`, the glue; `sameBundle` made shared),
   `AppEnforcer.apply`, `DNS_BYPASS_CAPABILITY` in `STATUS_CAPABILITIES`. `BrowserDohTest`: on gives the one key for
   each installed listed package only; off gives empty bundles; an unlisted or uninstalled package is never in the
   plan; the value is a String. `SyncScheduleTest`'s capability list.
3. **Both sides, one commit with the contract** (M). Launcher: `UpstreamReach.kt` + `UpstreamReachTest`:
   2 failures → none; 3 within 29 s → none; 3 over 30 s → since = the first; an answer between → reset, an answer ends
   it (`lastOutage`); 1 failure, 2 h, 2 failures → none (the 60 s rule), failures 20 s apart still count; a failure
   without a validated network leaves the state alone and losing the network ends an ongoing outage; a new rev or
   provider ends an ongoing outage into `lastOutage` and keeps it; concurrent CAS; `reachKind`: `Unprotected`, an
   exception, `Odd(TIMEOUT, null)` → FAILED, relayed bytes → ANSWERED, a given-up canary's SERVFAIL → NEITHER.

   Also: `Resolved` from `CustomDns.resolve` and `DnsFilterEngine.resolveProvider` (the `CustomDnsTest` rows for
   both-fail and probe+backup-fail), `KidVpnService` (glue, `@Volatile lastNetwork`, provider-mode report, tunnel-up
   gate), `DnsUpstreamReport`, `DnsUpstreamStatusTest`, `UpstreamChooserTest.kt:157`.

   Server: `dns_server.rs` (state, sanitizer and its tests), `custom_dns::dns_upstream_status_contract` (the body,
   sneaky), the contract file in both directories.
4. **Server: the page** (S). `dns_filter.rs` (`phone_line`: ongoing/ended/provider arms; provider mode's lines; the
   Each Phone lines; `DOH_IGNORING_BROWSERS`), `dns_filter.html` (the fixed row, footers). `tests::custom_dns`:
   each line's words; provider mode lists only outages; a provider report at rev 0 while custom mode is in force:
   "Waiting for the phone"; 24 h expiry; an ended outage shows at an old rev; no report: the tunnel-down words; the
   capability line (not for a phone with the filter off), the Cromite and Firefox lines.

   Docs: `docs/notes/server/dns.md`; `launcher/CLAUDE.md` (the "one known hole" paragraph rewritten with the browser
   table); this round's emulator.md §6m steps.

Builds: server `cargo test`/`fmt`/`clippy`; launcher `assembleDebug assembleRelease testDebugUnitTest`. Code QA after
step 4, then the fix round and E3.

**E3 - emulator.md §6m, new steps 9-17**, on `jelly36` (Chrome is preinstalled; Firefox, Edge, Brave and Vivaldi come
from Play). Two custom servers that can't be reached: `tls://192.0.2.10` and `tls://192.0.2.11` (TEST-NET-1, never
routed, not the tunnel's own `.1`/`.2`; two are needed because the server refuses the same server twice, and one alone
isn't enough: the backup answers).

9. Install main's APK first and, before step 2's build, on the enrolled phone: Chrome › Settings › Privacy and security
   › Use secure DNS. Record whether it shows "managed by your organization" (Chromium's owned-device default). After
   step 2: `chrome://policy` lists `DnsOverHttpsMode` = `off`, source Platform; the switch is managed and off.
10. Set Chrome's secure DNS to Google before step 2, or in a fork that shows the switch: a name on the phone's lists
    still doesn't load. Then `adb shell ping -c1 dns.google` and `ping -c1 chrome.cloudflare-dns.com`: unknown host.
    With the log on, the phone's log has "Secure DNS bypass".
11. Firefox (Play): `adb shell ping -c1 use-application-dns.net` → unknown host. Firefox Settings › Privacy › DNS over
    HTTPS (if present): Default → filtered; Increased with Cloudflare → filtered (falls back); Max → pages fail (the
    host is blocked). Record which modes exist on Android and fix the Firefox line's words.
12. Custom server `https://dns.quad9.net/dns-query`, backup `https://family.cloudflare-dns.com/dns-query` (a name on
    the list): look-ups work, and with a failing primary (`https://wrong.host.badssl.com/dns-query`) the backup by
    name answers (logcat `Upstream: backup`).
13. Custom `tls://192.0.2.10`, backup `tls://192.0.2.11`: ping three names over 40 s. logcat shows `Upstream
    unreachable`; after Sync now, if it gets through, the footer reads "DNS can't be reached since ...". Set the
    backup to `https://1.1.1.3/dns-query` (this bumps `custom_rev`): names resolve, and the next report gives "Backup
    server since ... · couldn't be reached HH:MM-HH:MM" - the ended line survives the rev change.
14. Offline is no outage: with the servers of step 13, `adb shell svc wifi disable` (and `svc data disable`) for 90 s
    with apps asking, then enable: no `Upstream unreachable` in logcat, no outage line.
15. Provider mode with 853 and `1.1.1.1:443` blocked (step 7's host iptables, `-d 1.1.1.1 --dport 443` and `--dport
    853`; no guest root): "DNS can't be reached ... Cloudflare doesn't answer".
16. Edge, Brave, Vivaldi from Play, allowlisted: `edge://policy` / `brave://policy` / `vivaldi://policy` show
    `DnsOverHttpsMode`; fill §2.1's table. Element X still connects with the category in force (WhatsApp: a device check).
17. Cromite allowlisted for the phone: the Each Phone line names it; with its Secure DNS on, pages don't load; Off →
    filtered.

## 6. Open questions for the user

**Answered 2026-10-26 (the user, "Enig i dine forslag på dns"): all three on their defaults** - the built-in list can't be lifted, an
ended outage stays on the page 24 h or until the next, no push for an outage (S-M2's "filter down" event is the place).

1. May the parent lift the built-in category (an Allowed Domains entry such as `dns.google` wins)? **Default: no.** It
   is the bypass itself; a parent who wants a browser on its own DoH turns the Web Filter off for that phone. (A yes
   would need a contract change: the phone has no allow input for it.)
2. How long does an ended outage stay on the page? **Default: 24 h**, or until the next one.
3. Should an outage longer than N minutes also notify the parent (a Web Push event, like "Battery low")? **Default: no,
   the line only.** S-M2's "filter down" event, when it is built, is the place for it.
