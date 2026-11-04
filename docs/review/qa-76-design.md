# Design QA: 76 - pin the server's certificate issuer in the launcher (`docs/design/76-server-issuer-pin.md`)

Security review by Opus, 2026-11-04, of the design note at 36950813 on `design-76-pin`. Checked against the code at
that commit: `launcher/.../server/ServerTransport.kt` (whole), `ServerUrlRule.kt`, `ServerAuth.kt`, `MdmApi.kt:85-170`,
`CommandListenerService.kt:80-125`, `music/MusicServer.kt:60-100`, `Provisioning.kt`, `dto/ProvisioningExtras.kt`,
`SettingsFragmentLauncher.kt:350-372,640-692`, `MdmSyncWorker.kt:1185-1250`, `PolicyGate.kt:50`, `StatusCapabilities.kt`,
`UpstreamTransport.kt:485`, both `network_security_config.xml`, `app/build.gradle.kts` (build types), the tsnet proxy
(`launcher/mobile/tsembed/dial.go`), a grep of every OkHttp/HttpURLConnection/WebView/DownloadManager/`@Url` use in the
launcher and of `api/devices` in the music app; the server's `handlers/provisioning.rs:15-45,320-345,378`,
`config::check_server_url` (`config.rs:268`), `Cargo.toml`'s TLS stack, the device routes' redirects; OkHttp 4.12.0's
bytecode (`CertificatePinner$Pin.<init>`, `RealConnection.connectTls`, `RetryAndFollowUpInterceptor.isRecoverable`);
Let's Encrypt's certificates page (fetched 2026-11-04) and its Generation Y announcement. PLAN's "Pin the server's
certificate issuer" and "Assess the eMudhra CA key compromise" entries and the eMudhra memory note.

**Verdict: the right shape, but not ready to build.** Root-SPKI pins on Android's validated chain, a built-in set plus
parent-approved extras, fail closed, no TOFU, tailnet exempt: all correct, and the OkHttp mechanics hold (I-1). The
blockers are about time and about installs other than the user's. Let's Encrypt already issues from its Generation Y
hierarchy. The moment a Conscrypt update adds ISRG Root YR/YE, every phone pinned to B as written refuses the
user's own server, and the launcher update that would fix it can't arrive (C-1). Step 1 shipped alone has no safe
path for a server outside B, and no way for the parent to see one coming (H-1). Also, the pinner fails open when its
host pattern doesn't match the request (H-2). D2's migration rests on a cache that doesn't keep the raw JSON (M-1).

Counts: **1 Critical, 2 High, 5 Medium, 7 Low, 7 Info.** Every file:line claim in the design holds except those named in
M-1 and L-3 (and `StatusCapabilities.kt:18` is inside the list, which starts at :11 - fine).

## Critical

**C-1 - B lacks Let's Encrypt's Generation Y roots, which are already in use: a Play system update bricks every phone.**
- The design (D1, "TBD") would add ISRG Root YR/YE "once they are in Mozilla's store". That's the wrong trigger.
- Let's Encrypt's certificates page (2026-11-04) lists YE1/YE2/YR1/YR2 "in active rotation" under Root YE/YR, which are
  cross-signed by X2/X1 and "not yet in Root Program trust stores" but "submitted for inclusion soon". The default
  chain is `EE <- YR1 <- Root YR (cross-signed by X1) <- X1`.
- Today Android validates that chain to X1, so the pin passes. Conscrypt's path building tries trust anchors before
  the presented intermediates. The design itself says the validated chain stops at the first anchor it has.
- So the day a Conscrypt mainline update (Play system update, automatic, not ours) adds Root YR, YR1's issuer is found
  among the anchors and X1 is no longer in the validated chain. Every phone of every LE-served install then refuses at
  once. Caddy's default is LE, so that includes the user's.
- Recovery is the worst case:
  - The fixing launcher release comes from the server's cache, over the refused connection (`MdmApi.kt:94-100`).
  - Step 1 has no E. In step 2, E by policy also arrives over the refused connection.
  - What's left is a QR re-scan on each phone with the parent code (only once step 2 exists), or a switch to the
    tailnet address (I-6).
- Fix in the design:
  - B gets ISRG Root YR and YE now. Their certificates are published, and a pin on a key that isn't in Android's
    store yet is harmless, because Android's validation still gates.
  - Step 0 fetches them from letsencrypt.org/certificates and checks them against crt.sh, like the others.
  - Rule for B, written in the note and in `docs/notes/launcher/security.md`: a pinned operator's new root goes into B
    as soon as its certificate is published, never when a store adds it. Sectigo's newer roots follow the same rule
    (R46/E46 are already in).
  - Add a PLAN chore: re-check ISRG's and Sectigo's root pages at every launcher release.
  - Use the root programs' term limits, not certificate expiry, in D1's table and in "Before expiry". X1 is "trusted
    until 2030-06-04" and X2 until 2035-09-04 (LE's page). USERTrust's keys are from 2010, already past Chrome's
    15-year term. The note's "B's roots run to 2035-2046" overstates it.

## High

**H-1 - Step 1 alone silently breaks any install whose server chains outside B, with no on-phone recovery.**
- Who that hits:
  - A self-hoster behind a Cloudflare proxy or tunnel. Universal SSL can use GTS, LE or SSL.com, and Cloudflare picks
    and can change it.
  - Caddy set to another ACME CA (GTS via EAB, Buypass, SSL.com), or a commercial certificate (DigiCert, Sectigo OV is
    fine, GoDaddy, Amazon behind CloudFront).
  - Split horizon: Caddy and LE at home, a CDN on mobile.
- Two ways in, after a step-1 launcher arrives:
  - The server-cached self-update installs it.
  - A fresh provisioning downloads `releases/latest`.
- What happens: every https call is refused. The kid's location, commands and reports stop, and the PWA shows only
  "not seen since".
- In step 1 nothing on the phone can fix it:
  - There is no E.
  - Re-scanning the QR carries no roots.
  - A downgrade is blocked by the versionCode.
  - That leaves moving the server to LE or ZeroSSL (impossible behind Cloudflare's Universal SSL), the tailnet
    address, or a factory reset that still pins.
- "Step 0 checks the user's" and Q1 cover one install. The repo is public GPL, and the rule "no adb step, every grant
  has an on-phone path" applies to all.
- Learning the anchor at upgrade time would be TOFU. Staying unpinned while the chain is outside B is a fallback an
  on-path attacker can keep open (no-insecure-fallbacks). So the migration has to be gated by what the parent sees,
  not by what the phone learns.
- Required change. Make the rollout server-gated and observed first:
  1. **Server first** (step 2's server part, before any enforcing launcher):
     - the self-check;
     - the allow list E;
     - `server_tls` in the policy;
     - `server_roots` in the QR;
     - Phone Setup's warning;
     - a hold on the enforcing launcher's self-update for any phone whose reported anchor (below) is outside B∪E.
       The device page says why, with "Allow X" or "Install anyway".
  2. **A report-only launcher** (no enforcement). On each successful https connection it reads the validated chain
     (`chain.connection()?.handshake()?.peerCertificates` in a network interceptor: OkHttp keeps the cleaned chain
     there). It sends the chain's pinned or unpinned verdict and the anchor's `{name, pin}` in the status report
     (`server_tls: {anchor, in_pins}`). It already stores E from the policy and the QR. Each phone's own network and
     own Android store count (split horizon, store-ahead). The anchor is only shown and used to gate the update, never
     trusted.
  3. **The enforcing launcher.** A fresh provisioning (which gets `releases/latest`) is covered by Phone Setup's
     warning, which is in place since 1.
  - If the user wants step 1 for his own Caddy sooner, ship 1 and 2 first anyway. Both are small next to step 2, and
    the report-only release doubles as step 0's check on real phones.
  - With this, Q1 and Q3 dissolve: step 2's server part is no longer optional.

**H-2 - The pin fails open when its host pattern doesn't match the request, and `@Url` requests can name another host.**
- OkHttp's `CertificatePinner.check` returns at once when `findMatchingPins(host)` is empty. A pin set for the wrong
  string pins nothing, silently.
- The design says "the URL's exact host" but not from which parser. `ServerUrlParts.host` (`ServerUrlRule.kt:29`) is
  not `HttpUrl.host`: IPv6 bracketing, punycode, a trailing dot.
- `downloadTrackedApp(@Url url)` (`MdmApi.kt:94-100`) sends any URL the server's answer names. An absolute URL to
  another host goes out with no pin and no bearer (`ServerAuth.kt:25`).
- Fix:
  - The pattern is `path.baseUrl.toHttpUrl().host`, exactly what OkHttp compares (`HttpUrl` canonicalises with the
    same `toCanonicalHost` as `Pin`).
  - `toServer`'s Direct branch gets an origin fence as its first interceptor. A request whose scheme, host or port
    isn't the base's throws (`sameOrigin`, `ServerAuth.kt:12`), so every request a server client makes is to the
    pinned host. The tailnet branch already refuses other names through its `Dns`.
  - Test both: an off-origin `@Url` request fails before a socket is opened, and the pinner has non-empty pins for the
    base host. The latter needs a public check; `findMatchingPins` is public in 4.12.
  - The design's source scan ("nothing calls `certificatePinner` elsewhere") stays.

## Medium

**M-1 - D2's migration reads a cache that doesn't hold the server's JSON.**
- D2 says `cachedPolicy()` "keeps the server's JSON". It doesn't: `storeAcceptedPolicy` writes
  `ServerJson.encodeToString(PolicyResponse.serializer(), policyToCache(policy))` (`MdmSyncWorker.kt:1199`), the DTO
  re-encoded.
- A launcher whose `PolicyResponse` has no `serverTls` field drops the key. "Step 2's first start fills an empty E from
  the cached policy" therefore finds nothing on any phone that cached under step 1 or earlier.
- On a server outside B its first fresh fetch is then refused, which is the case the migration was for.
- Fix: the report-only launcher (H-1, 2) declares `serverTls: JsonElement?` (as `music`, `PolicyResponse.kt:125`) and
  stores E at every accepted policy. The enforcing launcher needs no cache migration.
- If the cache read stays anyway, make it one-shot (a done flag). "No stored E" has to differ from "stored `[]`", or a
  parent's cleared list comes back from a stale cache.
- Write the honest argument down: the cached policy came over an unpinned connection. That's acceptable only because
  an attacker on that connection already held the bearer.

**M-2 - E's cap of 4 and one-root-at-a-time approval don't fit CA-rotating front ends.**
- Cloudflare's Universal SSL rotates among LE, GTS (R1 RSA, R4 ECC) and SSL.com (RSA, ECC, and the 2022 TLS roots) at
  its discretion.
- The self-check offers only today's root, so the next rotation bricks the phones again (H-1's scenario, delayed).
- Fix:
  - Cap E at 8. That's still small, and the QR has room: about 52 bytes a pin, so 8 is under 420 bytes; `fits()` at
    `provisioning.rs:330` decides.
  - In the allow-list card, "Allow X" offers the issuer's sibling roots by name where the server knows them. A small
    server-side table (GTS R1-R4, SSL.com's four) is enough, each still a click.
  - Add a DEPLOY.md line: behind Cloudflare, either allow all three CAs or use an Advanced certificate with a fixed CA.

**M-3 - The parent learns of a refusal only by holding the phone.**
- D3 stores the refusal on the phone, and the server only sees silence. The step-2 server banner covers just the case
  the server's own handshake reproduces. It doesn't cover a store-ahead (C-1), a split horizon, or an on-path attacker
  on the kid's network, which is the attack this design exists for.
- A kid whose location silently stops while on a hostile Wi-Fi is exactly when the parent should be told.
- Fix:
  - Keep a small ring of refusals (time, host, anchor `{name, pin}`, the network type, at most 8). Write a refusal only
    when it differs from the last, so the SSE's 30 s retry doesn't rewrite prefs each time.
  - Send them in the next status report that gets through (another network, the tailnet, the fixed server). The
    device page says "Refused a certificate for your server from emSign ECC Root CA - G3 on mobile data at 14:02".
  - That's the brick diagnostic and the attack alarm in one. Add `server_tls.refusals` to the shared status contract
    file (§4).

**M-4 - A policy-delivered E lets an attacker who passes the pin keep a weaker CA after the strong one is gone.**
- Take an attacker with a mis-issued LE or Sectigo certificate for the parent's host, or the parent's DNS for HTTP-01.
  They already own the channel: the bearer, every policy field. The design accepts that (§6).
- What E adds is persistence. Their policy sets E to `[emSign G3]`. Once their LE certificate is revoked or the DNS is
  fixed, they keep intercepting with an emSign leaf on any network they sit on.
- The real server's next policy replaces E, so this lasts only while they keep the phone from the real server.
  Acceptable, but say so in §6 and make it visible.
- Fix:
  - The status report echoes the phone's effective E pins.
  - The server compares them with its allow list and warns on the device page ("This phone accepts an issuer you
    didn't allow").
  - The Settings row lists E's names.
- No signature scheme is needed; detection is the proportionate answer.

**M-5 - The self-check's QR block nudges the parent to "Allow" under attack, and its rule differs from the phone's.**
- D5 refuses to render a QR while the self-check's root is outside B∪E, with Allow beside the message.
- An attacker on the server's outbound path (or a captive DNS on the VM's network) with any CA in the server's
  filtered store can make the parent choose between no QR and widening every phone's trust.
- Fix:
  - Never block the QR. Show the warning, with "Allow X" and "Show the code anyway" (the phone then refuses the
    enroll and says why).
  - Word the Allow text so it says to allow X only after deliberately moving the certificate to X, for example
    "Only allow X if you moved your certificate to X".
- The rule mismatch:
  - The self-check judges only `VerifiedPath::anchor()`. The phone accepts any certificate in its validated chain.
  - When the server's store is ahead (YR present on the VM, not on the phone), the server flags a chain the phones
    accept.
  - Mirror the phone: pass when any certificate's SPKI in the verified path, anchor included, is in B∪E.
  - Verify the hostname too, as rustls does, not only the chain.

## Low

**L-1 - The QR's absent `server_roots` should keep E, not clear it.**
- D2: QR "empty or absent = none" replaces the stored E; the policy's absent key keeps it.
- Re-scanning a QR from an older server, a rolled-back image, or a screenshot clears E and bricks a non-B setup.
- Make absent = keep and `""` = clear, like the policy. Clearing never widens trust, so this is a brick fix, not a
  security one.

**L-2 - A malformed `server_roots` must surface as a refusal, not "not a setup code".**
- `fromQrJson` returns `null` for anything unreadable (`ProvisioningExtras.kt:44-58`). The scanner then toasts
  `toast_mdm_scan_qr_invalid` (`SettingsFragmentLauncher.kt:657-660`).
- Parse `server_roots` in `applyProvisioningExtras` (as the S-M1 address check, `Provisioning.kt:29-32`). Show the
  refusal in the same dialog style (E9 L1).
- In the native flow (`fromAdminExtrasBundle`) a refusal leaves an un-enrolled DO with Settings locked. The server's
  contract test must make a malformed value impossible, and the note should say so.

**L-3 - `toServer`'s Direct branch has no interceptor today.**
- §3 says the refusal is caught in "`toServer`'s first interceptor (it already runs first, before the bearer)". Only
  the tailnet and closed branches have one (`ServerTransport.kt:130,137,147`).
- The design must add it: the origin fence (H-2), then the pin-refusal catch.
- `toServer` is called first in all three builders (`MdmApi.kt:151`, `CommandListenerService.kt:96`,
  `MusicServer.kt:80`).

**L-4 - Validate pins with OkHttp's own decoder, and never crash a client build.**
- `CertificatePinner.Builder.add` throws `IllegalArgumentException` on a pin it can't decode
  (`CertificatePinner$Pin.<init>`).
- `parsePin` should accept exactly what OkHttp's `decodeBase64` does, at 32 bytes, so the QR, the policy and the store
  agree.
- `toServer` catches a throw and fails closed with `ServerPinRefused`, instead of crashing the sync or the stream in a
  loop.
- Dedupe E and drop entries already in B.

**L-5 - `isPinFailure` must not swallow hostname failures, and the message parse needs a contract test.**
- Hostname mismatch is also `SSLPeerUnverifiedException` ("Hostname x not verified"). Match the "Certificate pinning
  failure!" prefix only.
- The anchor is the last `sha256/...: <subject>` line. Pin it against a real 4.12 failure (as §5 says), so an OkHttp 5
  bump fails the test, not the dialog.
- Truncate the stored subject to 64 characters. The leaf and intermediate subjects come from whoever got them issued;
  only the anchor's is shown.

**L-6 - i18n.**
- nb `server_cert_explain` says "(Innstillinger › Skann oppsett-QR)". The existing label is "Skann oppsettskode"
  (`values-nb/strings.xml:209`).
- The same text says "oppsettkode" where the app says "oppsettskode".
- The server's QR lives on the device's page ("devices.qr"), not under Phone Setup. Say "the phone's setup code on
  the admin site" / "telefonens oppsettskode på adminsiden" as the existing summary does (`:210`).
- `settings_mdm_server_cert_ok` must change once B holds YR/YE. It names operators, not roots, so it doesn't today.
- The server's new strings need `en.json` and `nb.json` keys in the same commit.

**L-7 - QR density.**
- 8 pins add about 420 bytes to a QR that already carries the Wi-Fi and the checksum.
- Step 2's QA should scan it on the Jelly Star's camera at the PWA's rendered size, not only trust `fits()`.

## Info

**I-1 - The OkHttp mechanics hold (verified in 4.12.0's bytecode).**
- `connectTls` runs, in order: `startHandshake`, the hostname verifier, then `CertificatePinner.check` on the cleaned
  chain, before the socket is kept or a byte of the request is written.
- On Android the cleaner is `X509TrustManagerExtensions.checkServerTrusted(chain, authType, host)` behind the NSC
  trust manager. Its result is the validated path with the store's anchor, even when the server doesn't send the root.
- The JVM tests' `BasicCertificateChainCleaner` appends the trusted root too, so `ServerTransportTest`'s fixture is
  representative.
- `isRecoverable` returns false for `SSLPeerUnverifiedException`: no route retry, no second attempt.
- `Pin.<init>` canonicalises the pattern with `toCanonicalHost`, so IPv4 and IPv6 literals pin. The `HostnameVerifier`
  fallback in §3 isn't needed (keep the test).
- Each `OkHttpClient.Builder()` gets its own `ConnectionPool`, and `Address` equality includes the pinner. A pool can't
  hand a connection made under other pins to a new client, and HTTP/2 coalescing re-runs the pinner.
- An open SSE stream keeps its connection after E changes. That's harmless, since the change arrived over the pinned
  server.
- With tsnet's SOCKS proxy, OkHttp passes the unresolved name and TLS is end to end with SNI. The pin applies
  unchanged (`dial.go:60-90`).

**I-2 - Every client reaches the server through `toServer`.**
- The only OkHttp clients are the three in §1 and `buildDohClient` (`UpstreamTransport.kt:485`, third-party).
- There is no `HttpURLConnection`, `DownloadManager` or WebView to the server in the launcher. The music app only
  names `api/devices` in comments; its WebView loads local data (`SpotifyWebHost.kt:146`).
- No device route redirects (the server's `Redirect::` uses are PWA pages and test fakes), so `followRedirects(false)`
  on Direct changes nothing real.

**I-3 - The debug override can't reach release.**
- `<debug-overrides>` is honoured only when the manifest is debuggable. The debug file lives in `src/debug/res/xml/`,
  release has no `isDebuggable` (`build.gradle.kts:123`), and there is no other build type.
- Optional hardening: a release-build check (like the Firebase classpath check) that the merged release
  `network_security_config.xml` has no `debug-overrides` and no `src="user"`.

**I-4 - S-M1 interplay is clean.**
- `Closed` still refuses before a socket. The tailnet path is http, so no pinner applies.
- The debug `http://10.0.2.2` is http.
- `serverUrlVerdict` and the shared `server_url_vectors.json` are unchanged.
- An https tailnet IP literal can't get a public certificate, so it was already unusable.
- `*.ts.net` (`tailscale cert`) is LE: in B, and with C-1 in B through Gen Y too.

**I-5 - Q2: recommend no "accept what the server shows" button.**
- It is TOFU behind the parent code. On the hostile network that caused the refusal, it would install the attacker's
  anchor.
- Recovery stays: the QR re-scan; the Allow on the server, then a sync; or I-6.

**I-6 - A recovery path the note should name: the tailnet address.**
- A phone with a Tailscale key can be switched in Settings › Server URL (parent code) to `http://<server's tailnet
  IP>`. That's WireGuard-authenticated and unpinned, so it gets the policy with E and the fixed launcher.
- Then switch back. Put it in D3's recovery list and in the Settings explanation, when a key is set.

**I-7 - Server-side self-check dependencies.**
- reqwest 0.13 verifies through `rustls-platform-verifier`, which exposes no path.
- The self-check needs `rustls-webpki` and `rustls-native-certs` as direct dependencies. Both are already in
  `Cargo.lock`, so it's the same filtered bundle.
- It needs no new crypto backend (the ring provider is installed).

## Changes the design must make before the build

1. Put ISRG Root YR and YE in B now. Write down the rule "a pinned operator's new root goes into B when published" and
   the per-release re-check. Use the root programs' term limits in D1 and "Before expiry" (C-1).
2. Reorder the steps:
   - server part first: the self-check, the allow list, `server_tls`, `server_roots`, Phone Setup's warning, and the
     update hold;
   - then a report-only launcher that reports its validated anchor and stores E;
   - then enforcement, gated per phone on the server.
   - Drop "step 1 ships alone" (H-1).
3. Pin pattern from `HttpUrl.host`, plus the origin fence in Direct, with tests (H-2, L-3).
4. Replace the cached-policy migration with the report-only launcher storing E. If it stays, make it one-shot and
   tri-state (M-1).
5. E: up to 8 entries, deduped against B, sibling-root offers, the DEPLOY.md note on CDNs (M-2, L-4).
6. Refusals reported to the server on the next contact, and E echoed in the status report, both in the shared
   contract file (M-3, M-4).
7. The QR is never blocked by the self-check. The Allow wording. The self-check mirrors the phone's any-certificate
   rule and verifies the hostname (M-5).
8. QR `server_roots`: absent = keep, `""` = clear. A malformed value refused in `applyProvisioningExtras` with a
   dialog (L-1, L-2).
9. `isPinFailure` on the pinning prefix only, the anchor parse pinned by a test, subjects truncated (L-5). nb texts
   fixed (L-6). A QR scan test at 8 pins (L-7).
10. Q2 answered "no". The tailnet recovery path named (I-5, I-6).

## Re-review (63fbb9c7, 2026-11-04)

Re-read the whole revised note (`git diff 0187490e..63fbb9c7`). The user answered: everything goes through Caddy; the
no-QR recovery is by the tailnet; step 2 is deferred. I re-checked the new line references
(`SettingsFragmentLauncher.kt:172-176` the Server URL row, `:700` `syncNowWithServer`; `MdmSyncWorker.kt:1196-1199`;
`StatusCapabilities.kt:11`; `ServerTransport.kt:130,137,147`; `values-nb/strings.xml:202,208`). The policy is a plain
`GET api/devices/policy` with no ETag/304 (`MdmApi.kt:45`), so every applied `server_tls` comes with its own handshake.

**The pins are verified.**
- Downloaded `letsencrypt.org/certs/gen-y/root-yr.pem`, `root-ye.pem`, `root-yr-by-x1.pem` and `root-ye-by-x2.pem`, and
  recomputed them with D1's command:
  - YR `fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88=`, certificate `e57b7e6f…`;
  - YE `sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=`, certificate `e14ffcad…`.
- Each cross-sign carries the same key (by X1 / by X2, valid 2026-05-13 to 2032-09-02). The self-signed roots are valid
  2025-09-03 to 2045-09-02, and the subject is `C=US, O=ISRG, CN=Root YR|YE`.
- The other six pins and certificate prefixes recompute identically from this VM's `/etc/ssl/certs`. All eight of
  D1's rows are right.

**Verdict: ready to build once R-1 and R-2 are written into the note** (both are a few lines in D2, §3 and §5, no new
mechanism). The Lows can go in during the build.

### The earlier findings

| Finding | State |
|---|---|
| C-1 | **Resolved**: YR/YE in B (verified above), the publish-not-store rule, the per-release chore, the programmes' terms |
| H-1 | **Resolved** by per-phone, server-switched enforcement with report-only built in. A server outside B never switches on. The gaps are R-1, R-2 and R-3 below |
| H-2, L-3 | **Resolved**: the origin fence first in `Direct`, the pinner host from `HttpUrl.host`, the `findMatchingPins` test |
| M-1 | **Resolved**: no cache migration; the flag is its own pref; `serverTls` is raw in the DTO |
| M-2, M-5, L-1, L-2, L-7 | **Soundly deferred**: they belonged to E, the QR field and the self-check, none of which is built. The CDN rotation risk is in DEPLOY.md, and its recovery exists |
| M-3 | **Resolved**: the outside ring, reported at the next contact, the alarm and the push. One hole: R-2 |
| M-4 | **Moot** (no E). What remains: an attacker who passes B (a mis-issued LE certificate) can send `enforce: false`. It lasts only until the real server's next `true` over a passing connection (Info, already in §7's "Doesn't stop") |
| L-4, L-5, L-6, I-3 | **Resolved** (the nb labels now match `values-nb`: "Serveradresse", "Skann oppsettskode", "adminsiden") |
| I-5, I-6 | **Adopted**: no accept button; the tailnet address is the no-QR recovery |

### Remaining findings

**R-1 Medium - What the policy carries in `watching` is unspecified. The natural reading silently switches enforcing
phones off.**
- D2 says `on` carries `enforce: true` and `off` carries `enforce: false`. It says nothing about `watching`.
- An implementation of `enforce: state == on` sends `false` in `watching`. By D2's table the phone then takes "off"
  over any passing connection, which is every connection to the real server.
- `watching` is reached without anyone deciding to turn phones off:
  - "Turn it on again";
  - a server database restored from a backup made before `on`;
  - a re-created device row;
  - the migration's default for every existing device.
- Each of these would quietly unpin every enforcing phone it covers, for at least 72 hours, with nothing on the device
  page but "watching".
- Fix in the note and the contract (§5):
  - `watching` sends `server_tls` with no `enforce` key, which the phone reads as keep. Only the parent's `off` ever
    sends `false`.
  - A report whose `enforcing` is true while the server is `watching` is shown as "on (the phone's)", not "watching".
    Optionally the server adopts `on` from it, since the phone stored it only from a pinned policy.
  - Add contract rows: `watching` gives absent, and a phone that is on stays on over a passing connection.

**R-2 Medium - Before `on`, an attacker can erase their sightings and keep the phone unpinned with the stolen token,
quietly.**
- **Erasing.** The ring is "dropped after its 2xx" (D3). With the flag off, a MITM with any store-valid certificate
  (emSign) receives the status report itself and answers 2xx, so the phone drops the evidence. §7's "an attempt is
  reported" fails for exactly the attacker it describes.
- **Keeping it off.** The same MITM now holds the bearer (as today before `on`). From anywhere, once every 71 hours,
  it can post a status report with an `outside` row. Each one restarts the window (D2), and the phone is never
  switched on.
- **Quietly.** The only parent push is for a `refused` row (§4). An outside `seen` while watching is just a passive
  device-page line.
- Fix:
  - (a) The phone drops ring rows only after a 2xx over a connection that passed B, or over the tailnet. A MITM can't
    produce either, so the rows reach the real server at the first honest contact. That also covers a phone that is
    switched on in between.
  - (b) An outside sighting stops automatic switch-on instead of restarting the window. The phone stays `watching`
    and the device page shows the sighting (name, network, time) with a one-shot "Turn the check on now" (parent
    override, at once).
  - (c) The first outside sighting of a phone that had in-set ones raises a parent event (push), at most one a day per
    phone. It's either a split horizon or an attack, and the parent should hear of both.
- With (b), forged rows can no longer hold a phone off without the parent seeing it, and the parent can override
  them. Real split-horizon installs still never switch on by themselves.

**R-3 Low - The 72-hour window can finish on one network only.**
- A kid at home on Wi-Fi for three days switches on. The first day on mobile data then refuses a split-horizon
  install's CDN chain (recovery: the tailnet or the QR).
- The user's own setup (Caddy everywhere) is unaffected.
- Fix: count the window in server receive time, never the phone's `at_ms`, and require in-set sightings on both
  Wi-Fi and mobile when the phone has reported mobile at all, or at least three distinct days. Add a row in
  `next_state`'s tests.

**R-4 Low - Make the parent's "Turn off" loud.**
- With a stolen parent session, turning the check off is the quiet first step of a later MITM. It's not an
  escalation, since the session can already do everything, but it shouldn't pass silently.
- As written, the settings-log middleware would log it as a generic `settings_saved` row (`settings_log.rs`).
- Fix: its own security event (`server_tls_off` / `server_tls_on`, the parent's name). A parent event to every
  parent's push subscription ("The certificate check for <phone> was turned off by <parent>"). The device page's "Off
  since %s" names the parent too.
- Also nudge the phone over the SSE so "at once" holds (parent-override rule). Today it takes effect at the next
  sync.

**R-5 Low - Pin down `nextFlag`'s inputs.**
- `tailnet` must come from the request's `ServerPath.Tailnet`, never from `handshake() == null`, which a debug
  `http://10.0.2.2` also gives. A debug http answer must stay "unchanged".
- The flag must be computed only from a fresh policy response in the sync. The cached-policy re-apply paths
  (`cachedPolicy()`, start-up, `currentPolicyDecision`) never call it. Say so in §3, and add a source-scan or unit
  row.

**R-6 Info - Things that hold.**
- **Rollback.** Old launchers can't install over a new one (versionCode), and the flag survives everything but a
  factory reset.
- **Server rollback.** A server rolled back to a pre-76 version sends no `server_tls`, so the flag is kept.
- **Stripping.** A MITM that strips `server_tls` or injects `enforce: true` changes nothing: `true` counts only over a
  passing connection, and nothing it sends passes.
- **The tailnet "off".** It needs the parent to have set the tailnet address (parent code) and a current WireGuard
  peer at that IP. That's the right authority, and its trust in Tailscale's coordination server is already in §7.
- **The QR clearing the flag before the enroll.** This is needed: the enroll itself may have to go unpinned. It's
  behind the parent code or a factory reset.
- **Enrolment.** It stays unpinned for the first 72 hours, as today. A future option, outside Q3's deferral: Phone
  Setup's QR could carry `enforce` once the server has phones `on`, closing that window for new and re-enrolled
  phones.
