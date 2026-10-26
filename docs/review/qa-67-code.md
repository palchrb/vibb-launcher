# QA 67 (code): the browsers' secure-DNS bypass and "DNS can't be reached"
Branch `dns-bypass` at b473aefa (acb2d999, a19b58dc, 1614edb8, b473aefa on main 09354cbf), 29 files, +1324/-194.
Read, built, tested, mutated (each reverted), one headless browser pass. No code changed.

**Verdict: fix first, one short round - 0 High, 1 Medium, 6 Low, 6 Nits.**
The bypass part holds. The host set is judged for every QTYPE, before the server lists, and only for tunnel queries. The
canary is exact and never forwarded. The managed config only tightens. M1 matters for the parent: the "offline is no
outage" gate does not cover a lingering Wi-Fi or patchy cellular, so a kid walking out of the door can leave a 24 h
"couldn't be reached" line. That is a one-constant fix on the server. The Lows are wording and sanitizer edges.

## Medium

**M1 - An ordinary walk can still read as "DNS couldn't be reached".** `validated` (KidVpnService.kt:338/342,
UpstreamReach.kt:120) is sticky. Android re-validates lazily. A Wi-Fi at -85 dBm by the door, or a cellular dead spot
on the bus, stays VALIDATED for tens of seconds to minutes. NetworkMonitor's DNS-stall detection never sees our traffic
either: app DNS goes into the tunnel, and our upstreams are IP literals. So three failed look-ups over 30 s
(MIN_SPAN_MS, :57) declare an outage. `validatedLost` then ends it when the Wi-Fi finally drops (:230), and the page
shows "Your server in use · couldn't be reached 08:01-08:02" for 24 h (dns_filter.rs:291-298). This is exactly the line
the user doesn't want to see. *Fix:* the server shows an ended outage only when it lasted at least 5 min (a
`MIN_ENDED_OUTAGE` beside `ENDED_OUTAGE_SHOWN`, :276, plus a test row with a 2 min outage). Keep the ongoing line as
it is: it can only arrive once a report gets through, which means a real outage. Optionally raise the phone's
`MIN_SPAN_MS` to 60 s. Say it in dns.md.

## Low

- **L1 - The Allowed Domains footer is now untrue for the built-in hosts.** "Let through even when a list blocks it"
  (dns_filter.html:104) does not hold: a parent who adds `dns.google` sees it saved and still blocked, with nothing
  saying why (design §6 answer 1 is "can't be lifted"). *Fix:* "...when a list blocks it - not the secure-DNS servers
  built into Vibb One", or refuse such an entry with that sentence.
- **L2 - Clock skew is kept up to 2100.** The sanitizer (dns_server.rs:229-247) keeps any time in 2000..2100. In the
  browser pass, a phone 3 h ahead showed "Kari · DNS couldn't be reached 20:38-21:08" at 18:08, and the line lasts
  24 h + 3 h. A phone a year ahead showed "26 Oct 2027-26 Oct 2027", which stays for a year. *Fix:* at intake, drop
  outage times later than the arrival + 5 min (the intake knows `now`).
- **L3 - A malformed ended outage turns into an ongoing one.** `until < since` drops `until` but keeps `since`
  (dns_server.rs:246-247), so the page reads "DNS can't be reached since ..." (seen in the pass, Kari before the fix of
  my script). A correct launcher can't send this (`outageTimes` uses one offset for both times), so it is Low.
  *Fix:* drop both times when `until < since`, and turn the `[t, t-1]` sanitizer row into `(None, None)`.
- **L4 - The ongoing line never ages.** If a phone dies or goes dark mid-outage, its last report keeps "DNS can't be
  reached since 10:42" on the page for days (`outage_since` ignores the report's age). *Fix:* add "· last heard
  HH:MM" when the report is more than 1 h old, or drop the ongoing words after 24 h, as with the ended line.
- **L5 - The log's category word blames the kid.** The block category is "Secure DNS bypass" (DnsFilterEngine.kt:21),
  but most hits will be apps' fallbacks (Telegram's `dns.google`, design §2.2's side effect), not the kid's doing. The
  page row calls the same thing "Secure DNS servers". *Fix:* "Secure DNS server", the row's word, calm.
- **L6 - The package lists miss common variants.** `DOH_IGNORING_BROWSERS` (dns_filter.rs:264) has only
  `org.mozilla.firefox`. Firefox Beta/Nightly (`org.mozilla.firefox_beta`, `org.mozilla.fenix`), Fennec F-Droid
  (`org.mozilla.fennec_fdroid`) and IronFox (`org.ironfoxoss.ironfox`) get no Each Phone line. Chromium forks outside
  `BROWSER_DOH_PACKAGES` (BrowserDoh.kt:26), such as Opera `com.opera.browser` (it has its own DoH setting), get no
  bundle and no line. *Fix:* add the Firefox family to the server list, and record Opera/others in the E3 table
  (bundle and line).

## Nit

- N1: the asset holds **55** names, not 57 (no doc states a count; only the brief did). `doh.dns.sb` is covered by `dns.sb`.
- N2: "2 failures over 30 s is an outage" is caught only incidentally (MIN_FAILURES=2 fails the 60 s test, not the
  window test, whose 2-failure row spans 10 s). Add `assertNull(fail(ReachState(), 0, 30_000).downSinceMs)`.
- N3: NxdomainTest checks only the answer's shape. A forwarded canary is caught by `CustomDnsGuardsTest`'s source pin
  (verified by mutation), so the credit in the note's step 1 belongs there.
- N4: `_dns.resolver.arpa` (DDR, RFC 9462) is relayed upstream. A DDR client must verify the certificate against
  192.0.2.2, so no upgrade can happen. A NODATA next to the canary would be a cheap extra guard (optional).
- N5: on a phone that isn't device owner (dev builds), BrowserDoh.kt:70 logs a warning on every apply for every
  installed browser. Harmless, but noisy.
- N6: emulator.md §6m check 10 ("On main's build ... Then `ping dns.google`: unknown host") needs this branch's build
  for the ping. Say so.

## Adversarial checks that hold (file:line)
- **Name forms:** `classifyDomain` lowercases and trims the dot (DnsFilterEngine.kt `domainRaw.trimEnd('.').lowercase()`),
  and `toString(true)` omits the final dot. IDN arrives as punycode. A homograph is another name with another
  certificate. The test has hits `dns.google.` and `DoH.OpenDNS.com`.
- **CNAME:** only the question name is judged, and an answer whose chain names a listed host is relayed. Acceptable:
  the browser connects to its template's host and checks the certificate against it, so an alias of `dns.google`
  fails TLS. IP-literal templates stay open, and design §1 and the footer say so.
- **QTYPE:** `classify` runs before any QTYPE logic, so HTTPS/SVCB/A/AAAA for a listed host all get NODATA.
- **DoT 853 by IP:** a non-goal in design §1 ("Routing the public resolvers' IPs ... dropping 443/853/QUIC").
  Android's Private DNS stays locked.
- **The parent can't lift it:** the phone has no allow input. The set is checked before `blocked` (and the mutation
  that swaps the order fails the test). An Allowed entry only keeps a name out of `blocked` (see L1).
- **Never our upstream:** `classify` has one caller (KidVpnService.kt, after the canary at :328). CustomDns, BootstrapDns
  and PublicResolver never call it. A parent's `https://dns.quad9.net/dns-query` is looked up by BootstrapDns on
  protected sockets and keeps working. The canary is `isFirefoxCanary` with an exact match (`x.use-application-dns.net`
  is a test miss).
- **Managed config:** only installed packages are in the plan (`MATCH_UNINSTALLED_PACKAGES` sees hidden ones), so
  `setApplicationRestrictions` never hits a missing package. `getApplicationRestrictions` returns an empty Bundle,
  never null, so filter-off on an untouched browser compares equal and writes nothing. The bundle is cleared when the
  filter goes off. An uninstalled browser loses it at uninstall. The list ignores the allowlist, as the note says.
- **ReachTracker:** the CAS loop re-reads the state and re-evaluates `elapsed()` on every retry, and returns early on
  an equal state (data class). 8 threads x 500 failures give exactly 4000. Validated -> unvalidated or none clears the
  count and ends an outage. Unvalidated -> validated starts fresh, because unvalidated failures never counted.
  Validated -> validated (a hand-over) keeps the count, and the 30 s span with answers between covers it.
- **Tunnel down:** `upstreamReport` returns null unless `tunnel != null && !stopped` (both volatile). The page then
  shows "Waiting for the phone" (custom) or nothing (provider), never stale words. `live` is set in onCreate and
  cleared only by its own instance.
- **Provider mode:** `rev 0, provider`, reported; a provider report under Custom at rev 0 reads "Waiting for the
  phone" (test and browser pass).

## Design QA (qa-67-design.md) - applied in code
H1 `relayed(Unprotected)` and the catch -> FAILED (UpstreamReach.kt:30-34, CustomDns.kt resolve) ✓. H2 the validated
gate + `validatedLost` (:120, KidVpnService.kt:230) ✓ (see M1 for what it misses). M1 `Odd(TIMEOUT, null)` -> FAILED
(test) ✓. M2 `GAP_MS` (:59-66) ✓. M3 the rekey keeps `lastOutage`, and the server shows an ended outage at any rev/mode
(test Kari) ✓. M4 footer words, Firefox line, §1 backstop ✓. L1 no first-label rule, `doh.wa.gov`/`.gov.ph`/`.gov.ae`
misses ✓. L2 loaded before the early return (source test) ✓. L3 `Lookups::Provider` and the `in_use != "provider"`
guard ✓. L4 bypass lines only with the filter on ✓. L5 no CustomDns classify test (a canary-order pin instead) ✓. L6 the
key rides on each call, absent `dns_upstream` words ✓. L7 one body, three readers ✓. N1 no server-host exemption ✓.
N2 `sameBundle` shared ✓. N3 Q1 note ✓. N4 E3 steps 9-17 ✓. N5 partly (the note is 325 lines).

## The contract and S2 (`server-sec-lows-a`)
- Both `dns_upstream_status.json` copies are byte-identical. The three readers changed. A one-byte edit of either copy
  fails DnsUpstreamStatusTest (2), UpstreamChooserTest (1) and the server's `dns_upstream_status_contract` +
  `the_dns_status_contract_matches_the_launchers_copy`. An old launcher's body (no new fields) still parses (the existing
  footer test posts it; `parse_dns_upstream` reads a pre-67 row). An old server refuses `provider` and keeps only the
  known keys of a new report. `policy_json_keys_snapshot` is untouched. `dns_bypass_v1` sits after `dns_custom_v1` in
  `STATUS_CAPABILITIES` (SyncScheduleTest).
- **S2:** no file in common (`git diff --name-only` of both against the merge base), and
  `git merge-tree origin/server-sec-lows-a origin/dns-bypass` is clean. S2's `known_fields` filters only
  `call_state`/`time_state` (its device_api.rs:1087-1192). `dns_upstream` still goes through `sanitize_dns_upstream`
  on both branches, so the new fields survive. S2's `DnsEventReport::checked` keeps a category of at most 64
  characters with no control characters, so "Secure DNS bypass" (17) passes. After the merge, update its doc comment
  ("a blocklist's category ... which this server sent"), because the phone now names one itself. S2 bumps the server to
  0.55.0, and L3's server change ships in the next version.

## Builds and tests
- Server (local, flock): `cargo fmt --check` ok; `cargo clippy --all-targets -- -D warnings` clean; `cargo test`
  **1375 passed, 0 failed**. CI 40362271258: green (1375 passed, image smoke on both architectures).
- Launcher (local): `assembleDebug testDebugUnitTest -PwarningsAsErrors=true`: **1821 tests, 0 failed**.
  `assembleRelease` can't run here (no `libs/tsnet.aar`, `checkReleaseHasTsnet`). CI 40362276005 `launcher-build`
  (assembleRelease + tests) is green.

## Mutations (two launcher batches and one server batch, targeted tests, all reverted, `git status` clean)
| Mutation | Caught by |
|---|---|
| built-in set after the server lists | DnsFilterEngineTest "block by suffix, before the server's lists" |
| `doh.` first-label rule | DnsFilterEngineTest misses + "under no other" |
| canary line removed (forwarded) | CustomDnsGuardsTest "the lists decide first" (not NxdomainTest, N3) |
| off bundle while the filter is off | BrowserDohTest "empty bundle" |
| FAILED counted unvalidated | UpstreamReachTest "offline is no outage" |
| MIN_FAILURES = 2 | UpstreamReachTest "60 s" only (N2) |
| 60 s gap ignored | UpstreamReachTest "60 s" |
| launcher contract copy, one byte | DnsUpstreamStatusTest x2, UpstreamChooserTest |
| server: `until` before `since` kept | `sanitize_dns_upstream_keeps_known_words_only` |
| server: ended outage shown 26 h | `the_lines_say_when_dns_cant_be_reached` |
| server: contract copy, one byte | `dns_upstream_status_contract`, `..._matches_the_launchers_copy` |

## Browser pass (Chromium, 390 px, light + dark, port 3196, real enrolment and device-API reports)
States: no phones; custom mode with six phones (ongoing; ended 2 h ago; ended 25 h ago -> gone; no report; filter
off with an ongoing outage -> "Web filter off", no bypass line; an old launcher with Firefox allowed -> the update
and Firefox lines; Cromite allowed -> the Cromite line); provider mode (Quad9: "Anna · DNS can't be reached since
18:00 - Quad9 doesn't answer from where the phone is", "Ola · DNS couldn't be reached 15:37-16:07", nothing else); skew
(L2, L3). Every state matched the note. No horizontal scroll, **0 console errors** in all runs (the CSP is still the
old one on this branch). The fixed row reads cleanly. The footer is honest and calm. Long lines wrap under the
group's footer. Screenshots: scratchpad `l3qa2/shots/` (`nophones-*`, `custom-*-{phones,server}`, `provider-*`).
