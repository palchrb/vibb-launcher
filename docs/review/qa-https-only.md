# Security code QA: branch `https-only` (d73c39a6), launcher review S-M1

Reviewed 2026-11-04 by reading the code only. No Gradle or cargo was run here. CI on the branch is green: server run
41816689328 (`cargo test` 1717 passed, 0 failed; the image jobs passed too) and launcher run 41816694093
(`launcher-build` BUILD SUCCESSFUL, `launcher-ci` success).

## High

### H1. tsnet's own SOCKS dialer falls back to the phone's network, so a "tailnet-only" http address can still go out in clear

`tsembed.go:155` uses `tsnet.Server.Loopback()`. Its SOCKS5 server dials with `s.dialer.UserDial` (tailscale.com
v1.104.0, `tsnet/tsnet.go:521-523`). `UserDial` → `dialOneUser` (`net/tsdial/tsdial.go:608-636`) uses netstack only
when `UseNetstackForIP` is true, and that is `s.lb.PeerForIP(ip)` (`tsnet.go:926-930`). Any other address goes to
`getPeerDialer()`, `SystemDial` or a plain `net.Dialer`. All three are OS sockets on the phone's Wi-Fi or mobile
network. The branch's guarantee, "http to a tailnet IP goes only through tsnet, never over the phone's own network"
(`ServerTransport.kt:17-22`, `security.md`), holds only while the stored IP is a peer in this phone's netmap.

Scenario: the server node is set up again and gets a new 100.x address (a lost Docker volume or state dir, a re-auth),
or an ACL change hides it from `tag:kid`, or the parent types a wrong tailnet IP. tsnet is up, so
`serverPath` → `ServerPath.Tailnet` (`ServerTransport.kt:85-98`). The SOCKS CONNECT to the old IP is then dialed on
the OS network. Every sync and every SSE retry (30 s) sends `Authorization: Bearer <device token>` in clear towards
100.x.y.z: on a carrier's CGNAT network, or on any Wi-Fi that routes 100.64/10. A hotspot the kid controls is enough.
Whatever answers is trusted. The policy has no signature (`PolicyGate.judgeFresh` judges only its shape), so the
answer can be an allow-all policy or commands. This is the "silent fallback past tsnet" that S-M1 names, one layer
down. It is also a loophole of the kind the user ruled out.

Fix (in `tsembed.go`): run our own `socks5.Server` instead of `Loopback()`'s. Its `Dialer` refuses a destination in
the Tailscale ranges (`tsaddr.IsTailscaleIP`) that isn't a current peer. `srv.LocalClient().Status` gives the peers'
`TailscaleIPs`, or use `WhoIs`. That way the check sits where the dial happens, not in Kotlin. The https path through
the same proxy has the same fallback, but TLS (system CAs) covers it there, so that part is not a finding. Add a note
to "Not device-tested": the SOCKS dial to an IP that isn't a peer.

## Medium

None.

## Low

- **L1. Squatting on the loopback port after tsnet stops.** A `ServerPath.Tailnet` client keeps the proxy it was
  built with (`ServerTransport.kt:126-127`): the prepared shutdown client (`PowerEvents.kt:430-433`, rebuilt only when
  the proxy changes) and a running download. `TsnetClient.close()` leaves the process `Authenticator` installed (only
  `leave()` clears it, `TsnetClient.kt:299`). An allowed app that binds the freed 127.0.0.1 port would get the SOCKS
  username and password and the cleartext request, bearer included. The window is small, and only apps the parent
  approved can try. A fix: clear the `Authenticator` in `close()`, and have `SocksSocketFactory` refuse a proxy that
  isn't `TsnetClient.proxy()` at connect time.
- **L2. Phones on `http://<name>.ts.net` go dark too, not only LAN ones.** The old server rule took
  `http://*.ts.net` silently as "Tailscale" (the removed `HostKind::Tailscale`). Such phones stop at this update,
  exactly like the LAN case (`ServerTransport.kt:20-22`). `security.md` and the review's status line speak only of "a
  plain-http LAN address". Before the launcher ships, check which address the user's own phones store. Also: Android
  provisioning installs `releases/latest`, so a server older than this branch can still print a QR with a LAN http
  address. Setup then finishes as Device Owner but not enrolled. Settings stays open until the first policy, so this
  can be fixed on the phone. Release the server first.
- **L3. The no-parent-code gap** (`security.md`, "A phone with no parent code"). An enrolled phone with a refused
  stored address and no parent code can be fixed only by a factory reset and a new QR. I find this acceptable. It is
  the price of "no fallback", the phone keeps enforcing its cached policy, and the server warns on Phone Setup. Say
  it plainly in the release note.

## Nits

- Comments name tests that don't exist: "ServerUrlRuleTest" (`ServerUrlRule.kt:7`) and "NetworkSecurityConfigTest"
  (both `network_security_config.xml` comments). Everything is in `ServerTransportTest`.
- "`.invalid` never resolves" (`ServerTransport.kt:29`, `network_security_config.xml:8`) overstates it. Android
  forwards such a query to the network's DNS, and a hostile resolver can answer it. Nothing is exposed today: only
  the Tailnet client asks for that name, with its own `Dns`. A server redirect from https would be needed, the server
  is trusted, and the bearer is same-origin only. Reword to "no client but the tailnet one asks for it".
- `settings_mdm_server_url_not_used` (en and nb) says "this address isn't encrypted" also for a refused https shape
  (`https://user@host`, `https://host/?x=1`). Better: "This address isn't one the phone uses: …".
- When the Settings edit refuses an address, the toast shows and the typed text is lost
  (`SettingsFragmentLauncher.kt:538-541`).

## Checked and found sound

- **Parser differentials.** No userinfo, query, fragment, `%`, `\` or internal whitespace gets through, and IDN, a
  trailing dot, numeric or `0x` last labels, octal-looking parts, ports `0`/`:`/`03100`/>65535, `[::ffff:…]`,
  dotted-tail IPv6 and zones are all refused. The Kotlin and Rust grammars match line for line, and the vectors are
  byte-identical. On the tailnet path OkHttp never parses the user's host: the IP bytes come from our own parse
  (`InetAddress.getByAddress`), and OkHttp sees only `tailnet-server.vibb.invalid` plus a checked port and path. On
  the https path, a parse that says https always means OkHttp also sees https (the same whitespace set, scheme
  case-insensitive), and `..` or `//x` in the path can't change the host.
- **Nothing reaches the network before the refusal.** Every server client (14 call sites) goes through
  `createMdmApi` or `createMusicServer`, or through the SSE client, which returns before building a client on a
  `Closed` path. `toServer` is first in each builder. The `Closed` interceptor is an application interceptor that
  throws an `IOException` before retry, bridge, DNS and connect. Each builder gets its own `ConnectionPool`, and
  okhttp-sse's `newBuilder()` keeps the DNS, socket factory and interceptors. Downloads use relative `@Url` paths.
  There are no `HttpURLConnection`, Glide, Coil or WebView server fetches, and crash reports ride the status report.
  The DoH client takes `https://` only (`CustomUpstream.kt:50`).
- **Cleartext.** OkHttp 4.12 checks `NetworkSecurityPolicy` against the URL's host before the socket connects. The
  SOCKS hop is a raw socket. The tailnet client has `NO_PROXY` (no `ProxySelector`), its DNS refuses every other
  name, redirects are off, and the SOCKS request carries the IP (the test checks ATYP 1). Http to a tailnet IP with
  tsnet down → `Closed`, never direct, and no race was found: the path is a snapshot, and a stale proxy fails, it
  never goes direct.
- **TLS.** Only system trust anchors, no user CAs, and no custom TrustManager, HostnameVerifier or pinner in the
  launcher. `UpstreamTransport.kt:227` uses the default verifier.
- **Debug only.** `10.0.2.2` is only in `src/debug/res/xml`. There is no `release` resource set and no flavors, and
  `emulatorUrl` needs `BuildConfig.DEBUG`. The release NSC would block it anyway.
- **Existing phones.** No grace request, so the bearer is never sent on a refused path. "Sync now" says why
  (`SettingsFragmentLauncher.kt:689`). The Settings edit takes the https address behind the parent code, offline,
  without adb, and asks for a sync at once.
- **Server.** `PHONE_SERVER_URL` is refused with a log line. A refused saved address never goes into a QR
  (`provisioning::server_url`), and the QR scanner and Android provisioning refuse before writing anything
  (`Provisioning.kt:29`). The save flow is unchanged (400 back to `#setup`, saved to `#saved`), so it doesn't jump to
  the top. `{host}` in the warning can contain only hex, digits, `.` and `:`.
- **i18n.** Three new launcher strings in en and nb, and four server keys in en and nb. `url_public_http` is gone,
  with no references left.
