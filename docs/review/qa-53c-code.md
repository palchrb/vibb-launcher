# Code QA - design 53, round 53-c (the socket's certificate: the pinned Sonos root, the leaf's udn)

- Commit on branch `d53-c` over `main`'s `562f42e4`: `b1057a86` (`SonosSocket.kt`'s `SONOS_ROOT_PEM`/`rootOnlyTrust`/
  `speakerCertRefusal`/`SpeakerTrust`, `SonosHome.socketClient(ip, uid)`, the renderer's `clientFor(ip, uid)`, the
  REFUSED mapping in the listener; `SonosTrustTest`, `SonosSocketTest`'s `d53c`, `SourceScanTest`'s `d53`; the test
  certificates with `make-certs.sh` and README; docs: the note's "53-c status", §2.2, §5 check 10, music/CLAUDE.md, PLAN).
- Read: root and `music/CLAUDE.md` (the trust exception); `docs/design/53-sonos-grouping-precision.md` (§0.1, §2.2
  "Trust", §5 check 10, "53-c status"); PLAN's "The certificate, checked the same day"; the full diff and the code
  around it (`SonosSocket` whole - `connect`, `retry`, `SocketBackoff`, `SocketParks`, the listener; `SonosHome.client()`;
  `SpeakerCache.resolve`/`applyTopology`; `Soap`'s uid; `network_security_config.xml`; the build's minSdk 34 /
  targetSdk 36 / OkHttp 4.12.0).
- Ran: `testDebugUnitTest -PwarningsAsErrors=true` (full music suite): **BUILD SUCCESSFUL, 1103 tests, 0 failures, 0
  skipped** (`SonosTrustTest` 6). `openssl x509 -fingerprint -sha256` over the PEM cut out of `SonosSocket.kt`:
  `42:DC:D4:A7:D4:B2:3B:D2:86:83:B4:03:37:7B:A8:8A:16:9B:35:78:EC:94:F4:15:BB:E6:FD:48:3D:D5:A0:8F` - equal to PLAN's
  and `SONOS_ROOT_SHA256`; subject = issuer = `C=US, ST=California, L=Santa Barbara, O=Sonos, Inc, OU=Sonos Devices,
  CN=Sonos Device Authentication Root CA`, 2015-10-05..2055-09-25, `CA:TRUE` (no pathlen), keyCertSign/cRLSign,
  `openssl verify` of its self-signature OK. The test certificates: no `PRIVATE` block anywhere in the directory, the
  subjects/SANs/dates as the README's table; `make-certs.sh` run into the scratchpad (keys thrown away after) gives
  certificates of the same shape (subject, issuer, dates, SAN, basic constraints, key usage, EKU - `diff` empty). Then
  11 one-line mutations against the targeted test classes, each reverted; the tree is clean but for this file.

## Findings

**1 (Medium) - a stale cached ip that now answers as another of the house's speakers parks our uid for 6 h.**
`SonosSocket.kt:627-630` counts every `SpeakerCertificateException` as `REFUSED`, so `udn mismatch` joins the refused
streak (`SocketBackoff.failed`, 300-307) and the fifth in a row parks the uid (`retry`, 521) - 1 + 2 + 5 + 15 s, about
23 s after the first try - in `SocketParks.PROCESS`, which even a new renderer for the room honours (`connect`, 480).
But `udn mismatch` is a statement about the *ip*, not about our speaker's certificate: a DHCP shuffle among the house's
Sonos players leaves the cache's ip on another player until the next topology ("DHCP moves and renames heal themselves",
`SpeakerCache.applyTopology`), and a valid Sonos leaf for the other player is exactly what the socket then meets. Each
retry re-resolves the ip (`connect`, 484), so a cache healed after a minute would succeed - but the park stops every
try for 6 h. The note's reason for parking a refused certificate ("the same certificate would come back") holds for
`chain`/`no udn`, not for `udn mismatch`. (A stale ip on a non-Sonos host that refuses TCP parks the same way since 53-a;
the fix below covers both.) Polling stays, so it is speed lost, not a feature - but for 6 h on a common home event.
Fix: give the exception its reason (`class SpeakerCertificateException(val reason: String)`) and remember the ip a
refusal was counted for - in `connect()`, a resolved ip other than the last one dialled resets the refused streak
(`backoff.opened()` already does exactly that), and `SocketParks` keeps `(until, ip)` so a park is void once the cache
names another ip (`parkedUntil(uid, ip, now)`). Simpler alternative: map `udn mismatch` to `OTHER` (the backoff's
300 s cadence keeps re-resolving). Test (`SonosSocketTest`): four mismatches at ip A, the cache moves to ip B, a fifth
refusal at B does not park; a park at A is void at B.

**2 (Low) - a phone clock far off refuses every speaker as `chain` and parks them.** `speakerCertRefusal`
(`SonosSocket.kt:184-188`) folds a validity failure into `chain`. The pinned root is valid to 2055, but the leaves'
dates are unrecorded (PLAN has the root's only); a kid's phone booting after a flat battery, before network time, sees
`CertificateNotYetValidException`/`CertificateExpiredException` (Conscrypt: a `CertPathValidatorException` with
`BasicReason.NOT_YET_VALID`/`EXPIRED` in the causes) on every speaker, and five tries in ~23 s park each one for 6 h
even though the clock is right a minute later. Everything else https on the phone fails too then, and polling stays -
hence Low. Fix: look for those causes in the caught exception and return a class of its own (`dates`), counted `OTHER`
by the listener (with finding 1's `reason`); `SonosTrustTest`'s `leaf_expired` then expects `dates`. And let check 10's
debug line add the leaf's `notAfter` year (not an identifier), so the leaves' lifetime gets recorded.

**3 (Low) - the udn's exact shape against our uid is assumed, not measured byte for byte.** Our uid is the device
description's UDN without `uuid:` (`Soap.kt:147-151`, e.g. `RINCON_<mac>01400`); PLAN's scrubbed note says the SAN holds
`urn:sonos:udn:RINCON_<uid>`, and the fixtures assume the SAN's value is the uid exactly (`RINCON_00000000000001400`).
If the real SAN differs (no `01400` suffix, another case or a `uuid:`), every speaker is refused `udn mismatch` - with
finding 1, parked - and the socket never works; the debug line then says only `refused (udn mismatch)`, which reads like
a stale ip. Fix: in §5 check 10 (and the "53-c status" Owed paragraph) say what to look for - "`refused (udn mismatch)`
on the speaker we dial (not at a moved ip) means the SAN's udn is spelt otherwise than the UPnP uid: report its shape
(the prefix, the length, the last five characters) without the value" - and, if the user still has the `openssl
s_client` output, compare the udn with the UDN in that speaker's `/xml/device_description.xml` before the device round.

**4 (Low) - the rule for a certificate with several udns is untested (M3 survives).** `udns.none { ... }`
(`SonosSocket.kt:196`) accepts a leaf that lists our uid among others; the note doesn't say so, and a mutation demanding
that *every* udn equal ours passes the suite. Fix: a fixture `leaf_two_udns` (another udn first, ours second) in
`make-certs.sh`, accepted in `SonosTrustTest`, and one sentence in the "53-c status".

**5 (Low) - no fixture for a leaf used as an issuer.** The realistic attacker on the LAN owns a Sonos: a valid leaf
under the real root with its private key. The udn check stops them presenting their own leaf; what stops them minting a
leaf naming our udn is the platform refusing a `CA:FALSE` issuer. That is the one platform rule the design leans on most
and it is not pinned. Fix: `make-certs.sh` signs a `leaf_under_leaf` (our udn) with `leaf_good`'s key; `SonosTrustTest`
expects `chain` for `[leaf_under_leaf, leaf_good]` and `[leaf_under_leaf, leaf_good, test_root]`.

**6 (Low) - keep `SpeakerTrust` a plain `X509TrustManager` and the inner call two-argument; pin it.** Checked how
Android runs this (no change needed today): Conscrypt's `Platform.checkServerTrusted` calls a non-extended trust
manager's 2-argument `checkServerTrusted` (after looking reflectively for a 3-argument host/socket overload, which
`SpeakerTrust` doesn't have); `TrustManagerFactory.getInstance("PKIX")` is the network security config's
`RootTrustManagerFactorySpi`, whose `engineInit(KeyStore)` builds a `KeyStoreConfigSource` config whose only
certificates entry is that store (no system or user CAs, no per-domain configs - so its 2-argument call doesn't throw);
`NetworkSecurityTrustManager` passes a null host to Conscrypt's `TrustManagerImpl`, which then skips Certificate
Transparency. Nothing is lost by the plain wrapper - and that is the point: an "upgrade" to `X509ExtendedTrustManager`
handing the socket or the ip down would bring the per-host policy in, and on targetSdk 36 (CT on by default) a
private-CA leaf without SCTs would fail on every speaker. Fix: in `SourceScanTest`'s `d53`, assert `class SpeakerTrust`
implements `X509TrustManager` (not `X509ExtendedTrustManager`) and `roots.checkServerTrusted(chain, authType` is the
only call on `roots`, with this reason in the test's comment and one line in the note.

**7 (Nit) - the once-per-uid debug line keeps the first verdict for the process.** `CERT_LOGGED.add(uid)`
(`SonosSocket.kt:230`): a first try at a stale ip logs `refused (udn mismatch)` and the later `verified` that check 10
looks for never appears until the app restarts. Fix: key the set on `uid + verdict`.

**8 (Nit) - the debug line logs a hostile certificate's CN raw.** `commonName` (204-207) picks the first `CN=` after any
`,` or `+`, also an escaped `\,CN=` inside another attribute's value, and the value reaches logcat with any control
characters. Debug builds only. Fix: drop control characters and cap the length (64) before logging.

**9 (Nit) - every connect builds a `KeyStore`, a `TrustManagerFactory` and an `SSLContext` on main**
(`connect` -> `clientFor` -> `speakerSocketClient`). Cheap at the backoff's cadence; `rootOnlyTrust(sonosRoot())` is
immutable and thread-safe and could be built once (`by lazy`) for the production root. A fresh `SSLContext` per client
should stay (see "pool" below).

**10 (Nit) - `scripts/sonos-ws-probe.py:273` still says "self-signed" and verifies nothing.** A `--root <pem>` option
verifying the chain (and printing whether the SAN has a `urn:sonos:udn:` equal to the uid asked for, never its value)
would give check 10 a Pi-side twin for the models the phone hasn't met yet.

**11 (Nit) - two wires only a text pin or nothing holds (M8, M10).** The verifier's behaviour is pinned only by
`d53`'s string match (`SourceScanTest.kt:690`); `SonosHome.socketClient`'s argument order (`SonosHome.kt:90-91`, two
`String`s) by nothing - swapped, every speaker fails closed (`udn mismatch`) and parks. Fix: in `SonosTrustTest`,
`client.hostnameVerifier.verify("10.0.0.5", null)` true and `"10.0.0.6"`/`"RINCON_..."` false; in `d53`, assert
`speakerSocketClient(client(), ip, uid,` in `SonosHome.kt` (or name the arguments there).

## The checks, with the evidence

- **The trust is confined**: one `KeyStore` with one `setCertificateEntry` (the pinned root), the factory `init(store)`
  (never `null`), one `SSLContext` over `SpeakerTrust`; no trust-all anywhere in main or debug sources (`SourceScanTest`
  `d53`, which M1 trips along with four `SonosTrustTest` cases - the JVM's `init(null)` loads `cacerts`, the test root
  isn't in it). `getAcceptedIssuers` is the root alone (asserted). `checkClientTrusted` refuses.
- **OkHttp can't go around it**: the handshake runs the trust manager; OkHttp's host check is the verifier that accepts
  exactly the gated ip (the leaf names no ip, so the default verifier would refuse every speaker - the custom one is
  required and narrow); the base client has no `CertificatePinner`, and the chain cleaner (Android:
  `X509TrustManagerExtensions` rejects `SpeakerTrust`, OkHttp falls back to `BasicCertificateChainCleaner` over
  `acceptedIssuers`) only runs with pins. `RetryAndFollowUpInterceptor` doesn't retry an `SSLHandshakeException` whose
  cause is a `CertificateException`, and the connection-spec fallback doesn't either.
- **The udn**: exact equality of the SAN URI after `urn:sonos:udn:`, case aside (a CA-signed udn for another player
  differs in more than case, so `ignoreCase` widens nothing); only `uniformResourceIdentifier` entries (type 6) are
  read; checked on `chain[0]`, the leaf both the platform and the SAN read take. The root sent as the whole chain passes
  PKIX and is refused `no udn` (tested). An empty uid can't match (`socketWanted` also never asks for one).
- **Intermediates**: path-built from what the speaker sends, in order and with the root along (tested); none fetched.
  The real root has no pathlen limit.
- **Time**: validity is the platform's (`leaf_expired` refused); the park consequence is finding 2.
- **Refusal -> REFUSED -> park**: the listener finds the exception within 8 causes (Conscrypt wraps it once:
  `SSLHandshakeException` caused by it; JSSE likewise; the test nests it twice); other TLS failures stay `OTHER` and
  reset the streak, so a transient handshake hiccup never parks a good speaker (tested: six in a row, no park). The
  certificate-based parks that do happen without a bad certificate are findings 1 and 2.
- **Logging**: release logs `certificate refused (<class>)` and `ended (REFUSED: certificate refused)` - never the
  platform's message (M7 caught), never a SAN value; the debug line has the leaf's subject CN (the MAC, as the note
  allows) and the issuer CN only (asserted against `urn:sonos`, `RINCON`, `Sonos_TEST`, `sonos-0000`).
- **Threads and the pool**: `SpeakerTrust` is immutable but for a concurrent set; Conscrypt's `TrustManagerImpl` is
  thread-safe. Each `socketClient(ip, uid)` call builds its own `SSLContext`, hence its own socket factory, hence its own
  OkHttp `Address` (the factory is compared by identity) - a pooled connection verified for uid A is never handed to
  uid B at the same ip; a WebSocket's connection is `noNewExchanges` after the upgrade anyway; the client session cache
  is per `SSLContext`, so no resumption crosses uids either. Keep it per (ip, uid) if the client is ever cached (a cache
  keyed on the ip alone would carry uid A's trust manager to uid B).
- **Test certificates**: public certificates only, fake-looking values (`Vibb Test`, `TEST00000000`,
  `RINCON_00000000000001400`, `Sonos_TEST`), the README's table matches, `make-certs.sh` reproduces them. Tests are lean
  and call the trust manager directly (no TLS server - which would need a committed key or a new test dependency; the
  wiring through Conscrypt is check 10's), no sleeping (the fake clock and `runDue`).

## Mutations

| # | Mutation | Tests run | Result |
|---|---|---|---|
| M1 | `TrustManagerFactory` `init(null)` (the system CAs) | `SonosTrustTest`, `SonosSocketTest`, `SourceScanTest` | caught - 4 `SonosTrustTest` cases and `d53` |
| M2 | the udn compared case-sensitively | `SonosTrustTest` | caught - "the udn's case" |
| M3 | every udn in the SAN must equal the uid (`none` -> `any { !equals }`) | `SonosTrustTest` | **NOT caught** (finding 4) |
| M4 | the chain check gets the leaf alone (the intermediates dropped) | `SonosTrustTest` | caught - the intermediate chain |
| M5 | a refused certificate counted `OTHER` | `SonosSocketTest` | caught - `d53c` (no park) |
| M6 | only the outer exception looked at (`take(1)`) | `SonosSocketTest` | caught - `d53c` |
| M7 | the platform's message in the `ended` line | `SonosSocketTest` | caught - `d53c` ("SECRET") |
| M8 | the host name verifier accepts any name | `SonosTrustTest`, `SonosSocketTest`; then `SourceScanTest` | **NOT caught** behaviourally; caught by `d53`'s text match only (finding 11) |
| M9 | the debug line on every check (no once per uid) | `SonosTrustTest` | caught - "once per uid" |
| M10 | `SonosHome.socketClient` passes `uid, ip` swapped (both `String`) | `SonosTrustTest`, `SonosSocketTest`, `SourceScanTest` | **NOT caught** (finding 11; fails closed: every speaker refused, then parked) |
| M11 | the udn read from the chain's last certificate | `SonosTrustTest` | caught - "the root sent along" |

8 of 11 caught; M8 is caught by the source scan's text pin when that class runs.

## Verdict

**0 High, 1 Medium, 5 Low, 5 Nit.** The trust is what the note says: the pinned Sonos root alone (fingerprint
verified here), the platform's own path validation over what the speaker sends, the leaf's `urn:sonos:udn:` equal to the
uid dialled, in the handshake, before any message; no system CAs, no trust-all, nothing in OkHttp that goes around it,
no pooled or resumed connection that crosses uids, and no identifier in any log. Approve after finding 1 (a stale ip
parking a good speaker for 6 h - the fix also covers 53-a's TCP-refused case); findings 2-6 are small and worth taking
in the same fix round (2 and 3 matter most for the device round: a wrong clock or a differently spelt udn would look
like "the socket never works"). Owed unchanged: §5 check 10 on the Jelly Star, every speaker model, with finding 3's
reading of a `udn mismatch`.
