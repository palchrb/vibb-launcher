# Design 67: the browsers' secure-DNS bypass, and "DNS can't be reached" on the Web Filter

Round L3 (PLAN.md, "Round L3 - DNS: the secure-DNS bypass and the upstream line"), branch `dns-bypass`, launcher and
server. Two approved items: launcher security review S-M3 (PLAN fix backlog, approved 2026-10-21, "the user's yes to
all three") and the incident's diagnostic (3) (PLAN, "Diagnostics from the same incident").

## 1. Why, and what this round leaves out

- S-M3: "One switch in an allowed browser ('Use secure DNS') bypasses the whole filter." The user said yes to all three
  mitigations: Chrome's managed config, a built-in block of the DoH hosts, Firefox's canary answered NXDOMAIN.
- Diagnostic (3): "Web Filter's status line should say 'upstream DNS can't be reached' while every upstream attempt
  fails ... so the parent sees the cause before the music app does."

Non-goals:
- **Routing the public resolvers' IPs into the tunnel and dropping 443/853/QUIC to them** (S-M3 fix 3, PLAN's
  "Later, if wanted"). Not in this round. Until then a DoH template given by IP literal (`https://1.1.1.1/dns-query`)
  still bypasses the names in a browser that ignores the managed config (Cromite).
- **A user-chosen strict mode** in a browser that ignores the managed config: Firefox's "Max protection" or a
  hand-set Cromite. The host block makes these resolve nothing (no page loads); it does not make them use our filter.
  The canary does not touch a user's own choice (§2.3).
- **Cromite's own setting.** Cromite ignores Chrome's keys (PLAN's 2026-09-19 findings); only §2.2 holds it, and its
  Secure DNS must be set Off by hand. The PWA says so for a phone that allows it (§2.4).
- S-M2 (the filter's own up/down state, the heal paths). Only its "send `dns_upstream` only while a tunnel is up" is
  taken here, because §3 rewrites that function.

## 2. The bypass

### 2.1 Chrome's managed config

Chromium's policy `DnsOverHttpsMode` (`components/policy/resources/templates/policy_definitions/Miscellaneous/
DnsOverHttpsMode.yaml`, read 2026-10-26): `supported_on: android:85-`, values `off` / `automatic` / `secure`,
`dynamic_refresh: true`; "If this policy is unset, for managed devices DNS-over-HTTPS queries will not be sent."
`chrome/browser/net/stub_resolver_config_reader.cc` `ShouldDisableDohForManaged()`: on Android, true once an owner app
is found. That check runs asynchronously and counts as "not owned" until it completes. So stock Chrome on our
device-owner phone probably has DoH off already. The explicit policy closes the startup window, shows the switch as
managed, and covers forks that keep Chromium's policy reader but patch the ownership check. E3 confirms this (§5,
check 9) and records what Chrome shows before step 2.

**The bundle**: exactly `{"DnsOverHttpsMode": "off"}` (a String), written with `setApplicationRestrictions`, which
replaces the package's whole bundle. Only the device owner can write one, and the launcher writes no other key for
these packages.

**Which packages - decided: a fixed list of package names, not the restrictions schema.**
`RestrictionsManager.getManifestRestrictions` only finds apps that declare the key in their manifest schema. A fork
can read Chrome's keys through Chromium's `AppRestrictionsProvider` without declaring a schema. A bundle sent to a
browser that ignores it does no harm. The list is `BROWSER_DOH_PACKAGES` in the new `server/BrowserDoh.kt`:

| Package | Browser | Reads `DnsOverHttpsMode`? |
|---|---|---|
| `com.android.chrome`, `com.chrome.beta`, `com.chrome.dev`, `com.chrome.canary` | Chrome | yes (documented, Android 85+) |
| `com.microsoft.emmx` | Edge | to check in E3 (`edge://policy`) |
| `com.brave.browser`, `com.brave.browser_beta`, `com.brave.browser_nightly` | Brave | to check in E3 (`brave://policy`) |
| `com.vivaldi.browser` | Vivaldi | to check in E3 (`vivaldi://policy`) |
| `com.sec.android.app.sbrowser` | Samsung Internet | to check on a device (no emulator image) |
| `com.kiwibrowser.browser` | Kiwi (discontinued 2025) | to check in E3 if it still installs |

Not listed: Cromite `org.cromite.cromite` (its `Disable-Android-AppRestrictions.patch` drops the provider by default),
Firefox (§2.3), Vanadium (not on these phones). A browser that turns out not to read the key stays on the list;
the table in `launcher/CLAUDE.md` records the E3 result.

**When**: in `AppEnforcer.apply`, right after `applyVpnRestrictions`, with the same `vpnFilterEnabled` (the policy's
switch, `false` under the override or a pause). The pure function `browserDohPlan(filterOn, installed)` returns
`pkg -> Bundle` for every listed package that is installed:
- filter on: the bundle above;
- filter off: an empty bundle.

The glue writes a package's bundle only when it differs from `getApplicationRestrictions` (MusicSync's `sameBundle`
pattern). The list does not follow the allowlist: an installed browser that isn't allowed is hidden or suspended, so
the bundle is a no-op there. The day the parent allows it, it starts with DoH already off, and there is no window
between the unhide and the next write. "Cleared when the app leaves the allowlist" is therefore not needed, and is not
done. A browser installed while the filter runs gets its bundle at the next `apply()` (install receivers sync). Until
then Chromium's own owned-device default holds it. Clearing at filter-off also leaves Chrome off (the same default), and
that is acceptable.

**No new policy key**: the Web Filter's per-phone switch (`vpn_filter_enabled`) already drives it.
`policy_json_keys_snapshot` / `PolicyResponseCompatTest` do not change.

### 2.2 The DoH-host category - decided: built into the phone

Built in, not a server category. It keeps the blocklist contract (`GET /api/devices/dns-blocklist`) unchanged, it is in
force before the first sync, and a server feed can't empty it. The parent can't lift it (open question 1); the parent
sees it as a fixed row (§2.4).

- **The list**: `launcher/app/src/main/assets/dns/doh_hosts.txt`. One lowercase name per line, `#` comments, each entry
  matched as a suffix (as `classifyDomain` walks today). The file says where each group came from. Seed:
  - Chromium's `net/dns/public/doh_provider_entry.cc`: `dns.google`, `cloudflare-dns.com` (covers `chrome.`,
    `mozilla.`, `family.`, `security.`, `1dot1dot1dot1.`), `dns.quad9.net`, `dns9.quad9.net` .. `dns12.quad9.net`,
    `dns.nextdns.io` (covers `chromium.` and `firefox.`), `dns.sb`, `odvr.nic.cz`, `public.dns.iij.jp`,
    `policy.public.dns.iij.jp`, `dns.levonet.sk`, `joindns4.eu`.
  - The common public resolvers' DoH/DoT names: `one.one.one.one`, `dns.adguard-dns.com`, `family.adguard-dns.com`,
    `unfiltered.adguard-dns.com`, `dns.adguard.com`, `dns.mullvad.net`, `dns.controld.com`, `freedns.controld.com`,
    `dns0.eu`, `family-filter-dns.cleanbrowsing.org`, `adult-filter-dns.cleanbrowsing.org`,
    `security-filter-dns.cleanbrowsing.org`, `dns.alidns.com`, `doh.pub`, `dns.switch.ch`, `dns.twnic.tw`, `8888.google`.
  - Built-in rule, in code: a name of three or more labels whose first label is `doh`, `doh-<digits>` or `doh<digits>`
    (`doh.opendns.com`, `doh.familyshield.opendns.com`, `doh.cleanbrowsing.org`, `doh.dns.sb`, `doh.mullvad.net`,
    `doh-01.spectrum.com`, `doh.xfinity.com`, ...).
- **Where**: `classifyDomain` gains the built-in set as a third input. Order: the FCM and Spotify exemptions, then the
  built-in set (category `"Secure DNS bypass"`), then the server's lists. The answer is the phone's NODATA
  (`blockedResponse`), as for any block. `DnsFilterEngine` loads the asset once in `loadFromDisk`.
- **Never our own upstreams - the predicate.** The category is judged only in `KidVpnService.handlePacket`, that is,
  for a UDP/53 query that arrived through the tunnel at `192.0.2.2`. The launcher's own upstream traffic never arrives
  there:
  - the providers are IP literals (`1.1.1.1`/`9.9.9.9` DoT with SNI; `https://1.1.1.1/dns-query` through
    `IP_LITERAL_DNS`, which never looks a name up);
  - the parent's servers are looked up by `BootstrapDns` on protected sockets to a fixed resolver, never through
    `classifyDomain` (design 36 §5.3);
  - every upstream socket is `protect()`ed.

  So a parent's backup `https://family.cloudflare-dns.com/dns-query` still works, while the kid's apps can't resolve
  that name. One name the launcher does resolve through the tunnel is exempt: **the host of the launcher's own server
  URL** (`LauncherPreferences.mdm().serverUrl()`), compared exactly (lowercase, trailing dot trimmed). The exact
  predicate: `bypassHit(d) = d != ownServerHost && (suffixIn(d, DOH_HOSTS) || dohLabel(d))`, with `d` = the query
  name, lowercased and with its trailing dot trimmed. The parent's DoH hosts get no exemption: a browser template
  pointing at an exempt name would reopen the bypass.
- **Side effect, accepted**: apps that use public DoH only as a censorship fallback (Telegram's `dns.google/resolve`)
  fall back to the system resolver, which is filtered. E3 checks that WhatsApp and Element X still connect.

### 2.3 Firefox's canary

Mozilla's "Canary domain - use-application-dns.net" (support.mozilla.org; the page could not be fetched directly on
2026-10-26, its text came through search). Firefox resolves the name through the system resolver. Any RCODE other
than NOERROR, or a NOERROR without A/AAAA, disables DoH, but **only for users whose DoH was enabled by default**. A
user who turned DoH on (Increased or Max protection) is not affected, and only §2.2 holds them. Increased falls back to
our filter, Max resolves nothing. Firefox for Android's DoH settings and defaults: to check in E3.

- **The answer**: `NXDOMAIN` (RCODE 3), the question echoed, QR/RA set and RD copied, with the same negative-cache SOA
  in the authority section that `blockedResponse` puts there (RFC 2308: the stub caches the negative answer for 300 s
  instead of asking again at every start). Built by a new `nxdomainResponse(request)` next to `blockedResponse`,
  sharing `answerTo`.
- **Where**: `handlePacket`, before `classify`, for the exact name `use-application-dns.net` (any QTYPE; a subdomain
  is not the canary). Never forwarded, never in `BlockedEventLog` (Firefox's routine probe, not the kid's doing).
  Answered whether or not a server list blocks it.

### 2.4 What the parent sees (Web Filter page)

- **Blocklists** group: a fixed row after the lists, without a switch: "Secure DNS servers", sub "Built into Vibb One,
  on with the filter". Footer addition: "Browsers' own secure DNS is turned off where the browser allows it (Chrome),
  and the public secure-DNS servers are blocked, so look-ups go through the filter."
- **Each Phone** group: one line per phone only where something is owed:
  - "Anna · Update Vibb One to close the secure-DNS bypass": a phone whose last report lacks the new capability
    `dns_bypass_v1`, which the launcher claims once §2.1-§2.3 are built.
  - "Anna · Cromite: set its Secure DNS to Off on the phone, or it won't load pages": a phone whose allowlist (server
    side) holds a package from `dns_filter::DOH_IGNORING_BROWSERS` (`org.cromite.cromite`; server only, no contract).
  No per-browser status from the phone: the bundle is fixed, and Chrome itself shows "managed".

## 3. "DNS can't be reached"

### 3.1 The phone's window - decided: at least 3 failed queries spread over at least 30 s, with no answer between

A **failed query** is one allowed query for which every upstream attempt made ended in a transport failure:
- custom server: the primary's `Transport` (or a backup period that skipped it) and then the backup's `Transport`, or
  no address for the backup;
- provider: DoT and then DoH `Transport`, or no time left for the second.

An **answer** is any DNS message relayed from a server: NOERROR, NXDOMAIN, a relayed REFUSED/SERVFAIL, and a healthy
canary's NODATA/SERVFAIL (design 36 §5.4). Neither counts: `Attempt.Unprotected` (our own tunnel's problem, S-M2), an
exception in our code, a blocked name (never sent).

Pure `UpstreamReach` (new `server/UpstreamReach.kt`), one `AtomicReference` per VPN instance, changed by CAS:
`{failures, firstFailMs, downSinceMs?, lastOutage: (sinceMs, untilMs)?}`, all in elapsed realtime.
- `afterFailure(s, now)`: `failures + 1`; `firstFailMs = now` at the first. If `downSinceMs == null && failures >= 3 &&
  now - firstFailMs >= 30 s`, then `downSinceMs = firstFailMs` (the outage dates from its first failure). Log
  `Upstream unreachable` once.
- `afterAnswer(s, now)`: if down, `lastOutage = (downSinceMs, now)` and `downSinceMs = null`. Then `failures = 0`.
- A new `rev`, a provider change, or a VPN restart starts a fresh state (the old outage was about other servers). A
  network reset changes nothing: an outage that goes on across a network change is still one outage.

Why both a count and a time. The count alone fires on one burst: a page load asks 20 names in 2 s while the network
switches. Time alone claims an outage on an idle phone, which asked nothing. The stub retries each look-up about every
5 s, so 30 s and 3 failures mean at least two look-ups failed with nothing answered between them.

This fits design 36 §5.4. The chooser moves to the backup at the first transport failure, so in custom mode nothing is
counted while the backup answers. A failure counts only once the backup fails as well: "every upstream, the backup
included". The probe's failure followed by the backup's is one failed query.

### 3.2 The status field

The `dns_upstream` object gets two fields, in both modes, while the tunnel is up:

```json
"dns_upstream": {"rev": 4, "in_use": "backup", "since_ms": 1792312345000, "reason": "tls",
                 "unreachable_since_ms": 1792312400000, "unreachable_until_ms": null}
```

- `unreachable_since_ms` / `unreachable_until_ms`: wall-clock ms or `null`, turned from elapsed realtime as
  `upstreamReport` does. An ongoing outage has `since` set and `until` `null`. The last ended one has both. With no
  outage both are `null`. They are always sent (`DnsUpstreamReport` has no defaults).
- **Provider mode now reports too**: `{"rev": 0, "in_use": "provider", "since_ms": null, "reason": null, ...}`. An
  outage on Cloudflare or Quad9 (a network that blocks 853 and their 443) is just as real. An older server's
  `sanitize_dns_upstream` refuses `provider` and stores `null`, as today.
- `KidVpnService.dnsUpstreamReport()` returns `null` unless the tunnel is up (`state` has a tunnel). This is S-M2's
  second bullet.
- **Why the ended outage is carried**: while DNS fails, the status report itself often can't reach the server (the
  launcher looks up its server's name through the tunnel; only a pooled or SSE connection, or tsnet's own resolution,
  gets through). The first report after recovery then still tells the parent what happened.
- Capability: none. Missing fields mean nothing is known.

**The contract** `server/testdata/dns_upstream_status.json` (byte-identical in `launcher/app/src/test/resources/`)
becomes a JSON array of two status bodies. Row 1 is today's body with an ongoing outage (as above). Row 2 is provider
mode with an ended outage (`"unreachable_until_ms": 1792313000000`). Both carry `"capabilities":
["dns_custom_v1","dns_bypass_v1"]`. `DnsUpstreamStatusTest` encodes each row exactly. `custom_dns::
dns_upstream_status_contract` posts each row and finds its `dns_upstream` stored as is. Both still compare the copies.

### 3.3 The server

- **Storage**: inside `device_status.dns_upstream_json` (no migration). `DnsUpstreamState` gains
  `unreachable_since_ms`/`unreachable_until_ms` (`Option<i64>`, serialized as `null`). `sanitize_dns_upstream`:
  - accepts `in_use` `provider` (its `reason` dropped);
  - keeps `since` when plausible (2000..2100);
  - keeps `until` only with a kept `since` and `until >= since`.
- **The lines** (`dns_filter::phone_line`, the DNS Server group's footer):
  - Custom, ongoing: "Anna · DNS can't be reached since 10:42 - neither your server nor the backup answers". This
    replaces the custom/backup words while it lasts.
  - Provider, ongoing: "Anna · DNS can't be reached since 10:42 - Cloudflare doesn't answer from where the phone is"
    ("Quad9" by the stored provider). In provider mode the footer lists only such phones; today it lists none.
  - Ended within the last 24 h (`until` from now): appended to the phone's normal line as " · couldn't be reached
    10:42-11:05". In provider mode it stands alone ("Anna · DNS couldn't be reached 10:42-11:05").
  - Times through `zone.when`. A report whose `in_use` doesn't fit the page's mode (custom vs provider) shows no
    outage, and so does a custom report at an old `rev`.
  - `nb` wording comes later with the PWA's translation.
- **When it clears**: the ongoing line goes with the first report after an answer. The ended line goes 24 h after its
  `until`, or at the next outage.

## 4. Security and privacy

- **No new browsing data leaves the phone.** A category hit is a block like any other. It reaches the server only
  through `BlockedEventLog`, which records only while the parent has switched that phone's log on (off by default; the
  server keeps entries 7 days). The canary is never logged. The outage fields are times only: no name, no address, no
  count of the kid's queries.
- **The managed config can only tighten.** One fixed key and value, chosen by the launcher's code. Nothing from the
  policy goes into the bundle, so a hostile server can't push `DnsOverHttpsTemplates` or any other Chrome key. "Off"
  writes an empty bundle, and only to the listed packages; no other package's restrictions are touched.
- **The category can't hurt our own resolving** (§2.2's predicate). It can only add blocks; its one exemption is the
  launcher's own server host.
- The sanitizer keeps known words and plausible integers only, as today (the contract test's "sneaky" row grows the
  two fields with strings and checks that nothing but numbers or `null` is stored).

## 5. Steps (branch `dns-bypass`, one round)

1. **Launcher: the category and the canary** (S). `assets/dns/doh_hosts.txt`, `DnsFilterEngine.kt` (load, the third
   input to `classifyDomain`, `ownServerHost`), `KidVpnService.handlePacket` (canary first), `nxdomainResponse`.
   Tests in `DnsFilterEngineTest`:
   - every file line is a valid lowercase LDH name, with no duplicates;
   - hits: `chrome.cloudflare-dns.com`, `dns.google.`, `doh.opendns.com`, `doh-01.spectrum.com`;
   - misses: `example.com`, `dohmain.com`, `doh.com`, `notdns.google.com`;
   - the server host is exempt; FCM still comes first.

   `NxdomainTest` (in that file): RCODE 3, the SOA, the ID/RD echo. `CustomDnsGuardsTest`: with the set loaded, a
   backup `family.cloudflare-dns.com` is looked up and used.
2. **Launcher: the managed config** (S). `BrowserDoh.kt` (list, `browserDohPlan`, the glue with `sameBundle`),
   `AppEnforcer.apply`, `DNS_BYPASS_CAPABILITY` in `STATUS_CAPABILITIES`. `BrowserDohTest`: on gives the one key for
   each installed listed package only; off gives empty bundles; an unlisted or uninstalled package is never in the
   plan; the value is a String. `SyncScheduleTest`'s capability list.
3. **Both sides, one commit with the contract** (M). Launcher: `UpstreamReach.kt` + `UpstreamReachTest`:
   - 2 failures → none;
   - 3 within 29 s → none;
   - 3 over 30 s → since = the first;
   - an answer between → reset;
   - an answer ends it (`lastOutage`);
   - a new rev resets;
   - concurrent CAS.

   Also: an outcome kind from `CustomDns.resolve` and `DnsFilterEngine.resolveProvider` (answered/failed/neither; the
   `CustomDnsTest` rows for both-fail and probe+backup-fail), `KidVpnService` (glue, provider-mode report, tunnel-up
   gate), `DnsUpstreamReport`, `DnsUpstreamStatusTest`.

   Server: `dns_server.rs` (state, sanitizer and its tests), `custom_dns::dns_upstream_status_contract` (rows,
   sneaky), the contract file in both directories.
4. **Server: the page** (S). `dns_filter.rs` (`phone_line`: ongoing/ended/provider; the Each Phone lines;
   `DOH_IGNORING_BROWSERS`), `dns_filter.html` (the fixed row, footers). `tests::custom_dns`:
   - each line's words;
   - provider mode lists only outages;
   - 24 h expiry;
   - a mode-mismatched report shows nothing;
   - the capability and Cromite lines.

   Docs: `docs/notes/server/dns.md`; `launcher/CLAUDE.md` (the "one known hole" paragraph rewritten with the browser
   table); this round's emulator.md §6m steps.

Builds: server `cargo test`/`fmt`/`clippy`; launcher `assembleDebug assembleRelease testDebugUnitTest`. Code QA after
step 4, then the fix round and E3.

**E3 - emulator.md §6m, new steps 9-16** (two custom servers that can't be reached: `tls://192.0.2.10` and
`tls://192.0.2.11`. They are TEST-NET-1, never routed, and not the tunnel's own `.1`/`.2`. Two are needed because the
server refuses the same server twice, and one alone isn't enough: the backup answers.)

9. Before step 2's build, on the enrolled phone: Chrome › Settings › Privacy and security › Use secure DNS. Record
   whether it shows "managed by your organization" (Chromium's owned-device default). After it: `chrome://policy`
   lists `DnsOverHttpsMode` = `off`, source Platform; the switch is managed and off.
10. Set Chrome's secure DNS to Google before step 2, or in a fork that shows the switch: a name on the phone's lists
    still doesn't load. Then `adb shell ping -c1 dns.google` and `ping -c1 chrome.cloudflare-dns.com`: unknown host.
    With the log on, the phone's log has "Secure DNS bypass".
11. Firefox (Play): `adb shell ping -c1 use-application-dns.net` → unknown host. Firefox Settings › Privacy › DNS over
    HTTPS (if present): Default → filtered; Increased with Cloudflare → filtered (falls back); Max → pages fail (the
    host is blocked). Record which modes exist on Android.
12. Custom server `https://dns.quad9.net/dns-query`, backup `https://family.cloudflare-dns.com/dns-query` (a name on
    the list): look-ups work, and with a failing primary the backup by name answers (logcat `Upstream: backup`).
13. Custom `tls://192.0.2.10`, backup `tls://192.0.2.11`: ping three names over 40 s. logcat shows `Upstream
    unreachable`; after Sync now, if it gets through, the footer reads "DNS can't be reached since ...". Set the
    backup to `https://1.1.1.3/dns-query`: names resolve, and the next report gives "Backup server since ... ·
    couldn't be reached HH:MM-HH:MM".
14. Provider mode with 853 and `1.1.1.1:443` blocked (step 7's iptables, root): "DNS can't be reached ... Cloudflare
    doesn't answer". Without root this stays a device check.
15. Edge, Brave, Vivaldi from Play, allowlisted: `edge://policy` / `brave://policy` / `vivaldi://policy` show
    `DnsOverHttpsMode`; fill §2.1's table. WhatsApp and Element X still connect with the category in force.
16. Cromite allowlisted for the phone: the Each Phone line names it; with its Secure DNS on, pages don't load; Off →
    filtered.

## 6. Open questions for the user

1. May the parent lift the built-in category (an Allowed Domains entry such as `dns.google` wins)? **Default: no.** It
   is the bypass itself; a parent who wants a browser on its own DoH turns the Web Filter off for that phone.
2. How long does an ended outage stay on the page? **Default: 24 h**, or until the next one.
3. Should an outage longer than N minutes also notify the parent (a Web Push event, like "Battery low")? **Default: no,
   the line only.** S-M2's "filter down" event, when it is built, is the place for it.
