# 76 - Pin the server's certificate issuer in the launcher

Status: design note, 2026-11-04, on `main` at 05ba9aaf; to be built on branch `design-76-pin` (PLAN queue: "Pin the
server's certificate issuer in the launcher - IN DESIGN 2026-11-04"). The user, 2026-11-04: "Ja gjør det!", after the
eMudhra follow-up (PLAN, "Assess the eMudhra CA key compromise", item 2). Both directories.
**Revised 2026-11-04** after the security review (`docs/review/qa-76-design.md`) and the user's answers: Q1 everything
goes through the user's Caddy (Let's Encrypt, ZeroSSL as fallback); Q2 recovery without the QR on the home network -
by the tailnet, since home Wi-Fi isn't authenticated; Q3 "glem steg 2 per nå" (no parent extra roots, no allow list, no
Phone Setup changes). §9 maps every finding to the change; the re-review's R-1..R-5 are written in (§9's last item).

## 0. The shape

Over https Vibb One talks to the parent's server only when the chain Android has validated (system CAs, as today)
contains a certificate whose public key (SPKI SHA-256) is in a **built-in set B**: Let's Encrypt (ISRG Root X1, X2 and
the Generation Y roots YR, YE) and ZeroSSL's operator Sectigo (USERTrust RSA/ECC, R46/E46). Pin by root, not leaf:
Caddy makes a new key at each renewal and can fall back to ZeroSSL on its own.

**Enforcement is switched on per phone by the server**, after it has watched that phone: every phone always checks
and reports what it sees (in the set or not, the anchor's name, the network); the server sends
`server_tls.enforce: true` once that phone has shown only in-set chains for 72 hours (server time, on Wi-Fi and mobile
data); the phone enforces from the
first such policy that arrived over a connection that passed its pins, stores that, and from then on only a pinned
connection or the tailnet can switch it off. A phone whose server is outside the set (another install's Cloudflare,
GTS, a commercial CA) is never switched on: it works exactly as today, and the PWA says why. A refusal fails closed
before any request byte leaves the phone; the phone keeps enforcing its cached policy offline, emergency calls
included; refusals are reported at the next contact that gets through.

Recovery without the QR: with the parent code, point the phone at the server's tailnet address (http to a Tailscale IP,
WireGuard-authenticated, never pinned); the policy over it carries the server's current `enforce`. Or re-scan the setup
QR. Nothing is ever learned from a connection: there is no extra-roots set and no trust on first use.

## 1. What the phone talks to today

- **One transport.** Every client of the parent's server builds its OkHttp client through `toServer(path)`
  (`launcher/.../server/ServerTransport.kt:129-148`), called first in all three builders: the API (`createMdmApi`,
  `MdmApi.kt:147`, `toServer` at :151), the SSE stream (`CommandListenerService.buildClient`,
  `CommandListenerService.kt:92-101`, rebuilt per connect) and the music courier (`createMusicServer`,
  `music/MusicServer.kt:76-85`). `serverPath` (`ServerTransport.kt:85-106`) gives `Direct` (https, through tsnet's SOCKS
  proxy while it runs, else the phone's network), `Tailnet` (http to a tailnet IP, only through the proxy) or `Closed`.
  Only `Tailnet` and `Closed` have an interceptor today (`ServerTransport.kt:137,147`); `Direct` has none.
- **Trust.** `res/xml/network_security_config.xml`: cleartext off, system CAs only (no user CAs), one cleartext name
  (`tailnet-server.vibb.invalid`); the debug copy adds `10.0.2.2`. No pin anywhere.
- **APKs.** The launcher's own update and every catalog app come from the server's cache (`downloadTrackedApp(@Url)`,
  `MdmApi.kt:94-100`; `server/src/handlers/tracked_apps.rs`). The phone never asks GitHub; only Android's provisioning
  downloads `releases/latest` (the system's download, checked against the QR's signature checksum). `@Url` can name
  any absolute URL; the bearer goes only to the server's origin (`sameOrigin`, `ServerAuth.kt:12`).
- **The music app** has no server client of its own: the launcher's bridge and courier are its only way to the server
  (`docs/notes/launcher/music-bridge.md`, first section). Its direct internet (Spotify, Storytel, podcasts, Sonos) is
  third-party.
- **Other TLS** in the launcher, not the parent's server: the DoH upstream (`UpstreamTransport.kt:485`) and tsnet's
  control plane (Tailscale's Noise protocol).
- **The policy cache** re-encodes the DTO (`storeAcceptedPolicy`, `MdmSyncWorker.kt:1196-1199`): a key the DTO lacks
  is not kept, so nothing here relies on the cache.

## 2. Decisions

### D1. The built-in set B

Pins are `sha256/<base64>` of the DER `SubjectPublicKeyInfo` (OkHttp's form). A chain passes when **any** certificate
in Android's validated chain matches: the anchor, or a cross-signed certificate carrying a pinned root's key
(USERTrust RSA cross-signed by AAA Certificate Services; YR cross-signed by X1, YE by X2). A self-signed root and its
cross-sign share the key, so it doesn't matter which one Android's store ends the path at. That was C-1: today Android
validates `EE <- YR1 <- Root YR (by X1) <- X1` to X1; the day a Conscrypt update adds Root YR, the path ends at YR, and
a set without YR would refuse every Let's Encrypt server at once.

Derived 2026-11-04 on the build VM with `openssl x509 -in F -noout -pubkey | openssl pkey -pubin -outform DER | openssl
dgst -sha256 -binary | base64`. X1, X2, USERTrust and Sectigo from Debian's `ca-certificates 20250419+deb13u1`
(`/etc/ssl/certs`); YR and YE from `letsencrypt.org/certs/gen-y/root-yr.pem` / `root-ye.pem` (fetched 2026-11-04;
their cross-signs `root-yr-by-x1.pem` / `root-ye-by-x2.pem` give the same pins; both certificate hashes found on crt.sh,
IDs 20878464596 and 20878464462).

| Name | Pin | Cert SHA-256 (first 8) | Key made | Trusted until (root programme) |
|---|---|---|---|---|
| ISRG Root X1 | `C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=` | `96BCEC06` | 2015 | 2030-06-04 (LE's page) |
| ISRG Root X2 | `diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=` | `69729B8E` | 2020 | 2035-09-04 (LE's page) |
| Root YR (ISRG) | `fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88=` | `E57B7E6F` | 2025 | not yet in stores (submitted) |
| Root YE (ISRG) | `sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=` | `E14FFCAD` | 2025 | not yet in stores (submitted) |
| USERTrust RSA Certification Authority | `x4QzPSC810K5/cMjb05Qm4k3Bw5zBn4lTdO/nEW/Td4=` | `E793C9B0` | 2010 | past Chrome's 15-year term: expect removal |
| USERTrust ECC Certification Authority | `ICGRfpgmOUXIWcQ/HXPLQTkFPEFPoDyjvH7ohhQpjzs=` | `4FF460D5` | 2010 | past Chrome's 15-year term: expect removal |
| Sectigo Public Server Authentication Root R46 | `Douxi77vs4G+Ib/BogbTFymEYq0QSFXwSgVCaZcI09Q=` | `7BB647A6` | 2021 | about 2036 (15-year term) |
| Sectigo Public Server Authentication Root E46 | `sLVjNUaFYfW7n6EtgBeEpjOlcnBdNPMrZDRF36iwBdE=` | `C90F26F0` | 2021 | about 2036 (15-year term) |

A pin on a key that isn't in Android's store yet (YR, YE) is harmless: Android's validation still gates. R46/E46 are
Sectigo's dedicated TLS roots and add no new company; they keep ZeroSSL working when its chains move off USERTrust.

**The rule for B** (in this note and in `docs/notes/launcher/security.md`): a pinned operator's new root goes into B
as soon as its certificate is published, never when a store adds it; B never gains another operator without the
user's decision. A PLAN chore: at every launcher release, re-check `letsencrypt.org/certificates` and Sectigo's root
page, and re-run the derivation (`ServerPinsTest` holds every root's PEM and computes the pins, so a typo fails).

**Rejected**: trust on first use (an on-path attacker at enrolment or at the update would be learned and kept); leaf
or intermediate pins (Caddy rotates keys; LE rotates intermediates); `network_security_config` `<pin-set>` (domains at
build time; ours is the parent's).

### D2. Who switches enforcement on, and off

**The phone** (`server/ServerPins.kt`, pure rules, one stored Boolean `server_pin_enforce` next to `mdm.serverUrl`,
written with `commit()`):

| Event | The flag |
|---|---|
| a policy with `server_tls.enforce: true` over https whose connection passed B | on |
| `enforce: true` over a connection that didn't pass, or over the tailnet | unchanged (nothing proves B works yet) |
| `enforce: false` over a connection that passed B, or over the tailnet | off |
| `enforce: false` over an https connection that didn't pass | unchanged (while on, no such policy can arrive) |
| `server_tls` without an `enforce` key (the server's `watching`, R-1) | unchanged |
| `server_tls` absent (an older server) or not an object with a Boolean `enforce` | unchanged |
| a setup QR applied (`applyProvisioningExtras`: Android's provisioning or Settings' scanner) | off (a fresh setup) |
| Settings › Server URL changed | unchanged (a new https host must pass B too) |

"Passed" is judged on the policy response's own handshake (`response.raw().handshake()`: OkHttp keeps the chain
cleaned by Android's `X509TrustManagerExtensions`, anchor included - qa I-1); the policy is a plain `GET` without a
304, so every applied `server_tls` has its own handshake. "Over the tailnet" means the request's path was
`ServerPath.Tailnet`, never "no handshake" (a debug `http://10.0.2.2` answer has none and is "unchanged", R-5). The
flag is computed only from a fresh policy response in the sync - never from `cachedPolicy()`, the start-up or
`currentPolicyDecision` re-applies (R-5). While the flag is off the phone pins
nothing and behaves as today. Switching it off needs a policy through B or through the tailnet: once on, there is no
strip-by-MITM downgrade.

**The server** (`server/src/server_tls.rs`, new; pure `next_state`), per device, from the phone's reports:

- `watching` (the start - also the migration's default, a restored backup, a re-created device row): the policy
  carries `server_tls: {}`, **no `enforce` key**, so a phone keeps its flag (R-1). Only the parent's `off` ever sends
  `false`. A phone reporting `enforcing: true` (it stored that only from a pinned `true`) is shown as on and the server
  adopts `on`.
  **Automatic switch-on** (R-3): in-set sightings spanning **72 hours of server receive time** (never the phone's
  `at_ms`), on **at least three distinct days**, and on **both Wi-Fi and mobile** when the phone has ever reported
  mobile. Both: the days catch a weekend at home, the networks catch split horizon (a CDN chain on mobile data); a
  Wi-Fi-only phone has no second path to check.
  **An outside sighting stops the automatic switch-on** (R-2) - it doesn't restart the window, so forged rows can't
  quietly hold a phone off: the phone stays `watching`, the device page shows the sighting (name, network, time) and
  offers the one-shot "Turn the check on now" (parent override, at once), and the first outside sighting raises a
  parent event (push), at most one a day per phone - a split horizon or an attack, the parent hears of both.
- `on`: the policy carries `enforce: true`. **Sticky**: an outside observation never switches it off (an attacker
  could cause one); it is shown as an alarm (the same push, a `refused` row always).
- `off`: only by the parent, on the device page ("Turn off the certificate check", one-shot); the policy carries
  `enforce: false`. Loud (R-4): its own security event `server_tls_off` (and `server_tls_on` for the way back) with the
  parent's name, a push to every parent ("The certificate check for <phone> was turned off by <parent>"), "Off since
  %s by %s" on the device page, and an SSE nudge so the phone syncs at once. Sticky until "Turn it on again" (back to
  `watching`) or "Turn the check on now" (`on`).
- A phone without the capability `server_pin_v1` (an older launcher) gets no `server_tls`.

So a fresh phone is unpinned for at least its first three days, as every phone is today; the user's phones (Caddy, LE or ZeroSSL)
switch on by themselves, and a phone of another install whose server is outside B never does.

### D3. Failure: closed, never silent

- **Mechanism**: with the flag on, OkHttp's `CertificatePinner` runs inside `connectTls` after Android's validation and
  before the connection is pooled or a request byte is written (qa I-1); `SSLPeerUnverifiedException` is not retried.
  The bearer, the report and the SSE subscription never reach a forged server. No fallback, no "accept anyway".
- **The phone keeps working offline**, as with a dead server: the cached policy and last-enforced plan, time rules,
  the PIN lock and the parent code (offline, the vault), calls and **emergency calls** (Telecom), alarms, downloaded
  music. What stops: new policy, commands, reports, location, the time request (its "couldn't reach" answer), updates,
  the music courier.
- **The kid** sees nothing new (Vibb One stays calm; the kid can't fix it).
- **On the phone, for the parent** (Settings, parent code): a row "Server certificate" under Server URL - on, not on
  yet, or the last refusal's issuer and time; tapping it explains and names the recovery; "Sync now" says why in a
  dialog (as S-M1's closed path, E9 L1).
- **Reported later** (qa M-3): the phone keeps up to 8 outside observations - `refused` (flag on) and `seen` (flag off)
  with time, host, the anchor's `{name, pin}` (name truncated to 64 characters) and the network type (Wi-Fi, mobile,
  other); one is added only when it differs from the last, so the SSE's 30 s retries don't rewrite prefs. They go in
  every status report and are dropped only after a 2xx **over a connection that passed B, or over the tailnet** (R-2):
  a MITM answering 2xx with an emSign certificate can't erase them, so they reach the real server at the first honest
  contact. The device page: "Refused a certificate for your server from emSign ECC Root CA - G3 on Wi-Fi at 14:02" - the
  brick diagnostic and the attack alarm in one.
- **Recovery without adb or the QR** (the user's Q2): Settings (parent code) › Server URL › `http://<the server's
  Tailscale IP>[:port]`. The tailnet path is WireGuard-authenticated and never pinned: the phone syncs at once, sends
  its refusals, gets the fixed launcher if one is waiting, and takes the server's `enforce` (D2). Then:
  - the server is back in B (the usual case: Caddy fell back, or a store change that a B update fixed): switch the
    address back to https;
  - the server stays outside B: the parent turns the check off on the device page, the phone takes `enforce: false`
    over the tailnet, then the https address.
  A phone not on the tailnet (`settings_mdm_tailscale_joined` not shown, or the parent made it leave) takes a new
  Tailscale auth key in the same Settings (parent code). Plain home Wi-Fi is not a way: it isn't authenticated, so
  there is no on-phone "accept the issuer shown now" button (qa I-5).
- **Recovery with the QR**: Settings › Scan setup QR - the flag goes off (D2), the phone enrolls again and stays off
  until the server's `on` reaches it over a passing connection. A phone with no parent code yet has Settings locked
  (`settings_gate_no_pin`): factory reset and a new setup QR (as S-M1).

### D4. Scope

| Path | Pinned | Why |
|---|---|---|
| `Direct` https (API, SSE, music courier, APK and file downloads) | yes, once on | every byte to the parent's server |
| `Direct` https through tsnet's SOCKS proxy (a `*.ts.net` name) | yes, once on | TLS is end to end with SNI; the proxy dials non-tailnet addresses on the OS network (`dial.go`); `tailscale cert` is Let's Encrypt, in B |
| `Tailnet` http to a Tailscale IP | no | no TLS; WireGuard authenticates the peer, and our proxy dials it only through netstack while it is a current peer (S-M1 H1) |
| debug `http://10.0.2.2` | no | cleartext, debug builds only |
| GitHub (`releases/latest` at provisioning) | out | Android's own download, verified by the QR's checksum; the launcher never asks GitHub |
| DoH upstream, tsnet control plane, the music app's third-party hosts | out | not the parent's server |

## 3. Launcher implementation sketch

- **`server/ServerPins.kt`** (new): `BUILTIN_PINS` (D1's table, name + pin), the flag, the outside ring (prefs key
  `server_pins_outside`, JSON, at most 8), and pure rules: `inSet(spkiPins)`, `nextFlag(flag, enforce, passed,
  tailnet)` (D2's table; `enforce` is true, false or absent; `tailnet` = `path is ServerPath.Tailnet`, `passed` = the
  response's handshake in B), `addOutside(ring, observation)`, `dropOutside(ring, passed, tailnet)`,
  `pinFailureAnchor(e)` (below). `nextFlag` has one caller, the sync's fresh policy fetch (a source scan pins it).
- **`toServer`, `Direct` branch** (`ServerTransport.kt:130`), in this order:
  1. **The origin fence** (qa H-2, L-3), the first interceptor: a request whose scheme, host or port isn't the base's
     (`sameOrigin(path.baseUrl.toHttpUrl(), request.url)`) throws before a socket - an `@Url` the server named can't
     reach another host unpinned. `followRedirects(false)`/`followSslRedirects(false)` (no device route redirects,
     qa I-2).
  2. **The refusal catch**, the next interceptor: an `SSLPeerUnverifiedException` whose message starts with
     "Certificate pinning failure!" (only that prefix; a hostname failure is not a pin failure, qa L-5) is recorded
     as `refused` and rethrown as `ServerPinRefused` (an `IOException`, like `ServerPathClosed`). The anchor is the
     last `sha256/...: <subject>` line of OkHttp's message.
  3. **With the flag on**: `.certificatePinner(CertificatePinner.Builder().add(host, *B).build())` with `host =
     path.baseUrl.toHttpUrl().host` - exactly the string OkHttp compares (`HttpUrl` and `Pin` share
     `toCanonicalHost`; IP literals pin, qa I-1). A test asserts `findMatchingPins(host)` is non-empty: a pinner for
     the wrong string pins nothing. A pinner that can't be built (it can't with B's constants; tested) fails closed with
     `ServerPinRefused`, never a crash loop (qa L-4).
  4. **Always**: a network interceptor that hashes the validated chain's keys once per connection (a weak map) and
     records `seen` (in set or not, the anchor, the network) - the report-only half, the same with the flag off.
  The flag is read where the path is read (each `createMdmApi`, each SSE connect, each `createMusicServer`); an open
  stream keeps its connection, harmless since a change came over the pinned server.
- **Policy**: `PolicyResponse.serverTls: JsonElement?` (raw, like `music`, `PolicyResponse.kt:125`), read in the sync
  with the policy response's handshake into `nextFlag`.
- **Status**: capability `server_pin_v1` (`StatusCapabilities.kt:11-18`) and `server_tls: {enforcing, seen: {in_set,
  name, pin, network, at_ms}, outside: [{kind, name, pin, network, at_ms}]}`; `outside` dropped after a 2xx.
- **Provisioning**: `applyProvisioningExtras` (`Provisioning.kt:26-49`) clears the flag with the address. No new QR
  field.
- **Settings** (`SettingsFragmentLauncher.kt`, after the Server URL row, :173-176): the "Server certificate" row and
  its dialog; "Sync now"'s refusal dialog (`syncNowWithServer`, :700).
- **Debug only**: the debug network security config gets `<debug-overrides>` trusting user CAs, so mitmproxy's CA on
  the emulator passes Android's validation and the pin is what refuses. `ServerTransportTest` pins both files and
  asserts the release one has no `debug-overrides` and no `src="user"` (qa I-3).
- **Texts (en / nb)**, `values/` and `values-nb/` (labels as the app has them: "Server URL"/"Serveradresse", "Scan
  setup QR"/"Skann oppsettskode", qa L-6):
  - `settings_mdm_server_cert`: "Server certificate" / "Serversertifikat"
  - `settings_mdm_server_cert_on`: "Checked: only Let's Encrypt and ZeroSSL" / "Sjekkes: bare Let's Encrypt og
    ZeroSSL"
  - `settings_mdm_server_cert_off`: "Not checked - the server turns the check on" / "Sjekkes ikke - serveren slår på
    sjekken"
  - `settings_mdm_server_cert_refused`: "Refused a certificate from %1$s at %2$s" / "Avviste et sertifikat fra %1$s
    kl. %2$s"
  - `server_cert_explain`: "Vibb One talks to your server over https only when its certificate comes from Let's
    Encrypt or ZeroSSL. If it now comes from another issuer: put the server back on one of those, or set Server URL
    to http:// and the server's Tailscale IP - the tailnet connection works at once - and turn the check off on the
    phone's page on the admin site if the server stays with the new issuer. Or scan the phone's setup code on the
    admin site again (Scan setup QR)." / "Vibb One snakker med serveren din over https bare når sertifikatet er fra
    Let's Encrypt eller ZeroSSL. Kommer det nå fra en annen utsteder: flytt serveren tilbake til en av dem, eller sett
    Serveradresse til http:// og serverens Tailscale-IP - tailnet-tilkoblingen virker med en gang - og slå av sjekken
    på telefonens side på adminsiden hvis serveren blir hos den nye utstederen. Eller skann telefonens oppsettskode på
    adminsiden på nytt (Skann oppsettskode)."
  - `server_cert_refused_sync`: "The server's certificate is from %1$s, which this phone doesn't accept. Nothing was
    sent." / "Serverens sertifikat er fra %1$s, som denne telefonen ikke godtar. Ingenting ble sendt."

## 4. Server implementation sketch

- `server_tls.rs`: the per-device state (a migration: `tls_state` `watching|on|off`, `tls_window_from`,
  `tls_changed_at`, `tls_changed_by`, the receive times and networks of in-set sightings, the last 8 `outside` rows,
  `tls_outside_pushed_on`), `next_state` at each status report (server receive time), the policy's `server_tls`
  (`{}` watching, `{"enforce": true}` on, `{"enforce": false}` off) only for a phone with `server_pin_v1` (pinned by
  `policy_json_keys_snapshot`). The buttons go through their own handler (not the settings-log middleware's generic
  `settings_saved`), write the security event, push, and nudge the phone over the SSE.
- Intake: `server_tls` in the status report, bounded (8 rows, names 64 characters, pins 44 characters of base64).
- The device page, one row (no Phone Setup change, no allow list): "Certificate check: on - only Let's Encrypt or
  ZeroSSL" / "Sertifikatsjekk: på - bare Let's Encrypt eller ZeroSSL"; "Certificate check: watching - on from %s if
  every certificate stays with Let's Encrypt or ZeroSSL" / "Sertifikatsjekk: følger med - på fra %s hvis alle
  sertifikater er fra Let's Encrypt eller ZeroSSL"; "Certificate check: not on - this phone saw a certificate from %1$s
  on %2$s at %3$s - not switched on by itself" / "Sertifikatsjekk: ikke på - telefonen så et sertifikat fra %1$s på
  %2$s kl. %3$s - slås ikke på av seg selv"; "Off since %1$s, by %2$s" / "Av siden %1$s, av %2$s"; the alarm "Refused a certificate for your server from %1$s on %2$s at %3$s" / "Avviste et sertifikat
  for serveren din fra %1$s på %2$s kl. %3$s"; buttons "Turn off the certificate check" / "Slå av sertifikatsjekken",
  "Turn it on again" / "Slå den på igjen" and "Turn the check on now" / "Slå på sjekken nå"; the pushes "The
  certificate check for %1$s was turned off by %2$s" / "Sertifikatsjekken for %1$s ble slått av av %2$s" and "%1$s saw
  a certificate for your server from %2$s" / "%1$s så et sertifikat for serveren din fra %2$s". `en.json` and `nb.json`
  in the same commit; the save never jumps to the top.
- `server/DEPLOY.md`: one paragraph - the phones accept Let's Encrypt and ZeroSSL once on; behind a CDN that picks its
  own CA (Cloudflare's Universal SSL rotates LE, GTS, SSL.com) the check may switch on during an LE period and a later
  rotation then needs the tailnet recovery - turn such phones off, or use a fixed CA; optional CAA with `accounturi`.

## 5. The contract

`server/testdata/server_tls_report.json`, byte-identical in `launcher/app/src/test/resources/`: the status body's
`server_tls` (with `outside` rows of both kinds) and the policy's `server_tls` rows (`{"enforce": true}`,
`{"enforce": false}`, `{}`, absent, malformed) with the flag each gives from each start state over a passing, a
failing, a tailnet and a debug-http connection - among them `watching`'s `{}` keeps an enforcing phone on over a
passing connection - and the ring's drop rows (kept after a 2xx over a failing connection, dropped over a passing or a
tailnet one). The launcher's `ServerPinsTest` encodes the report exactly and runs every flag row; the server's
`server_tls::a_launcher_report_moves_the_state` posts the report (its instants moved to the last hour) and produces
the policy rows; both compare the copies. One commit for both directories; CI: both copies in `launcher.yml`'s and
`server-ci.yml`'s path lists; the root `CLAUDE.md`'s contract paragraph and CI list. B stays the launcher's alone (the
server never judges a chain).

## 6. Tests (lean)

- `ServerPinsTest` (JVM): B computed from the roots' PEMs (public data, in test resources); `nextFlag` and the ring;
  `pinFailureAnchor` against a real OkHttp 4.12 failure message (an OkHttp bump that changes it fails the test).
- `ServerTransportTest` (JVM, extended): a local TLS server (`SSLServerSocket`; a throwaway test root, intermediate and
  leaf made once by a small script, files `test-only-*`, public test data only) through the real `toServer`: flag on
  and the root in the test's B passes; not in it fails before the server reads a request line, the bearer never seen;
  flag off passes and records `seen` outside; an IP-literal host; an off-origin `@Url` fails before a socket; a
  redirect not followed; `findMatchingPins(host)` non-empty; through the existing fake SOCKS5 proxy too. Source scans:
  every server client through `toServer`, `certificatePinner` nowhere else, the release NSC as above.
- Server: `server_tls::tests` (`next_state`: the window in receive time, three days, Wi-Fi only vs. both networks, an
  outside row stopping the switch-on and the push once a day, sticky `on`, adopting a phone's `enforcing`, `watching`
  sending `{}`, the parent's off/on/on-now with the event, push and nudge, no `server_tls` without the capability), the
  contract, `policy_json_keys_snapshot`, the device page's row.
- Emulator (QA): the user's server (watching, then `on` after a shortened window in a debug server config); mitmproxy
  with its CA as a user CA on a debug build (flag off: works, `seen` outside reported, the window restarts; flag on:
  refused, nothing in mitmproxy's log, Settings and "Sync now" say why, lock, calls and 112 keep working); the tailnet
  recovery end to end (refusals arrive, the parent's off reaches the phone, back to https); a QR re-scan clears the
  flag.

## 7. Security analysis

- **Stops, once on**: a certificate for the parent's host from any CA outside B - emSign (Android 16's Conscrypt still
  carries emSign ECC Root CA - G3), any of the other ~140 roots, a careless CA, a captive-portal or DNS-hijack
  certificate - from an on-path attacker on the kid's Wi-Fi, mobile network or resolver. The token, location and
  commands then depend on two operators (ISRG, Sectigo), not on every root in the store; and an attempt is reported.
- **Doesn't stop**: mis-issuance by ISRG or Sectigo, or an attacker who passes their domain validation (the parent's
  DNS, or on the path to the server for HTTP-01; CAA with `accounturi` narrows it); a compromised server; a compromised
  phone; the tailnet's trust in Tailscale's coordination; Android's own validation bugs (we pin after it, never instead
  of it). **Before on** - the first 72 hours, a phone on a server outside B, a phone the parent turned off - the phone is
  exactly as today: an attacker on the path holds the bearer as today and can stop the automatic switch-on with a
  sighting, but can't erase it (the ring drops only over a passing connection), the parent gets a push and can turn
  the check on at once. A mis-issued LE certificate can send `enforce: false`; it lasts until the real server's next
  `true` over a passing connection.
- **No TOFU**: the phone learns no root; B is in the APK. The server learns only what phones report, and uses it only
  to decide when to switch on - a wrong report can delay enforcement, never widen trust.
- **Downgrade paths**: no attacker can switch a phone off - `enforce: false` is taken only through B or the tailnet,
  the server's `on` is sticky against outside rows, an absent or malformed `server_tls` keeps the flag. Switching off
  needs the parent: the device page (the PWA's passkey session), a QR scan (the parent code, or a factory reset), or a
  launcher release removing a root from B (never without the user). The flag is one Boolean in one `commit()`, lost
  only with the app's data (a factory reset, which also ends enrolment). Old launchers can't install over a new one
  (versionCode, the update fence). User CAs stay untrusted in release builds.
- **With S-M1**: S-M1 decides the scheme, this narrows https's trust. `Closed` still refuses before a socket; a pin
  refusal comes one step later (after the handshake, before the request), worded the same way. The tailnet http path
  stays the only unpinned way, only through our proxy to a current peer - and is now the authenticated recovery path.

## 8. Steps and sizes

0. **Check (S, no code) - OWED before the release that ships this** (code QA H-1, `docs/review/qa-76-code.md`):
   `openssl s_client -connect <the user's host>:443 -showcerts` (the chain's pins against B); on the emulator, the
   anchor Android validates for it and for a ZeroSSL-issued host; and **the recovery path on the user's server**: from
   another tailnet device `curl http://<the Docker VM's Tailscale IP>:3101/healthz` says `ok` (the install binds
   `0.0.0.0:3101` with `network_mode: host`, Caddy is on another host), the tailnet policy lets the phones' tag reach
   that port, the device page's Certificate check shows that address, and a phone with the tailnet joined syncs with
   it as its Server URL. Until it holds, the only recovery is the setup QR (DEPLOY.md, "Tailnet recovery address").
1. **Launcher (M)**: `ServerPins.kt`, `toServer`'s fence, catch, pinner and observer, the flag in the sync, the report
   and capability, the QR clearing the flag, Settings' row and "Sync now", the debug override, texts en + nb, the
   tests, `docs/notes/launcher/security.md` (a section after S-M1's, with D1's rule).
2. **Server (M)**: `server_tls.rs`, the migration, the intake, the policy key, the device page's row, three buttons,
   the security events, the pushes and the SSE nudge, texts en + nb, DEPLOY.md's paragraph, the server notes; the contract file lands with step 1 in one commit.
   Order: either may ship first - a new launcher on an old server is never switched on; a new server sends `enforce`
   only to `server_pin_v1` phones. One branch; the phones switch on 72 hours after both are out.

**Deferred** (the user, Q3): the parent's extra roots (E) in the QR and the policy, the allow list and Phone Setup's
warning, the server's own self-check of its chain (the phones' reports replace it and see each phone's network and
store), sibling-root offers for CDNs. Needed only for an install that wants pinning on a CA outside B; such an install
stays as today and the PWA says why.

No open questions for the user.

## 9. What changed after the review

- C-1: YR/YE in B now; the publish-not-store rule and the per-release chore; the root programmes' terms in D1.
- H-1: enforcement per phone, switched on by the server after 72 hours of in-set reports; a server outside B never
  switches on and stays as today; report-only is built in (the flag off).
- H-2, L-3: the pin host from `HttpUrl.host`, the origin fence first in `Direct`, `findMatchingPins` tested.
- M-1: no cache migration (no E; the flag is its own pref; `serverTls` raw in the DTO).
- M-2, M-4, M-5, L-1, L-2, L-7: deferred with E, the QR field and the self-check (none of them is built).
- M-3: the outside ring, reported at the next contact, the device page's alarm and a parent event.
- L-4: no runtime pins to parse (B is constant, tested); a pinner build failure fails closed. L-5: the prefix only,
  the anchor parse pinned, names truncated. L-6: nb labels and "the admin site". I-3: the release NSC check.
- I-5, I-6 and the user's Q2: no on-phone accept button; the tailnet address is the no-QR recovery.
- Re-review (0b91ddfd): R-1 `watching` sends `{}`, only the parent's off sends `false`, a phone's `enforcing` is
  believed; R-2 the ring drops only over a passing or tailnet connection, an outside sighting stops the switch-on,
  "Turn the check on now", one push a day; R-3 receive time, three days, both networks; R-4 the security event, the
  push to every parent, the name, the SSE nudge; R-5 `tailnet` from `ServerPath.Tailnet`, the flag only from a fresh
  policy. R-6's future option (the QR carrying `enforce`) stays out with step 2.
