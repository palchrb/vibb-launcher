# 76 - Pin the server's certificate issuer in the launcher

Status: design note, 2026-11-04, on `main` at 05ba9aaf; to be built on branch `design-76-pin` (PLAN queue: "Pin the
server's certificate issuer in the launcher - IN DESIGN 2026-11-04"). The user, 2026-11-04: "Ja gjør det!", after the
eMudhra follow-up (PLAN, "Assess the eMudhra CA key compromise", item 2). Both directories (step 2). Security QA by
Opus before the build.

## 0. The shape

Over https Vibb One talks to the parent's server only when the chain Android has validated (system CAs, as today)
contains a certificate whose public key (SPKI SHA-256) is in the phone's **pin set**:

- **B, built in** (in the APK, never removable): the roots Caddy's default issuers chain to - Let's Encrypt (ISRG Root
  X1, X2) and ZeroSSL (Sectigo's USERTrust RSA/ECC, and Sectigo's newer R46/E46).
- **E, the parent's extra roots** (at most 4): what the parent allowed on the server for an issuer outside B. Carried
  by the setup QR (`server_roots`) and by the policy (`server_tls.roots`), stored with the server address.

Pin by root, not leaf: Caddy makes a new key at each renewal and can fall back from Let's Encrypt to ZeroSSL on its
own, so a leaf or intermediate pin would brick the phones every 60-90 days. A mismatch fails closed before a byte of
the request (or the bearer) leaves the phone; the phone keeps enforcing its cached policy, emergency calls and
everything offline keep working, and the parent recovers on the phone with the parent code (scan the setup QR again).
Nothing is learned from a first connection (no trust on first use). The tailnet path (`http://<Tailscale IP>`) has no
TLS and is not pinned (WireGuard authenticates the peer).

Step 1 (launcher only, B only) is enough for the user's Caddy setup and ships alone. Step 2 adds E (server + launcher,
one contract file) for any other issuer and for moving CA later without a launcher release.

## 1. What the phone talks to today

- **One transport.** Every client of the parent's server builds its OkHttp client through `toServer(path)`
  (`launcher/.../server/ServerTransport.kt:129-148`): the API (`createMdmApi`, `MdmApi.kt:147`), the SSE command
  stream (`CommandListenerService.buildClient`, `CommandListenerService.kt:92-101`, rebuilt per connect) and the music
  courier (`createMusicServer`, `music/MusicServer.kt:76`). `serverPath` (`ServerTransport.kt:85-106`) gives `Direct`
  (https, through tsnet's SOCKS proxy while it runs, else the phone's network), `Tailnet` (http to a tailnet IP, only
  through the proxy) or `Closed`. Since S-M1 nothing else exists (`docs/notes/launcher/security.md` §"The server
  address: https only").
- **Trust.** `res/xml/network_security_config.xml`: cleartext off, system CAs only (no user CAs), one cleartext name
  (`tailnet-server.vibb.invalid`); the debug copy (`src/debug/res/xml/`) adds `10.0.2.2`. No pin anywhere.
- **APKs.** The launcher's own update and every catalog app come from the server's cache
  (`GET /api/devices/apps/{id}/download`, `MdmApi.kt:95-101`, `server/src/handlers/tracked_apps.rs`): the phone never
  asks GitHub. Only Android's provisioning downloads `releases/latest/download/kids-launcher-mdm.apk` (the system's
  download, checked against the QR's signature checksum).
- **The music app** has no server client of its own: the launcher's bridge and courier are its only way to the server
  (`docs/notes/launcher/music-bridge.md`, first section); its direct
  internet (Spotify, Storytel, podcast hosts, Sonos on the LAN) is third-party.
- **Other TLS in the launcher**, not the parent's server: the DoH upstream client (`UpstreamTransport.kt:485`,
  `buildDohClient`) and tsnet's own control-plane traffic (Go, Tailscale's Noise protocol).
- **Setting the address.** The setup QR's admin extras carry `server_url`, `tailscale_auth_key`, `enrollment_code`
  (server `handlers/provisioning.rs:23-27`, `setup_payload` :378; launcher `dto/ProvisioningExtras.kt:18-59`), applied by
  `applyProvisioningExtras` (`server/Provisioning.kt:26-49`: refuse, save the address, tsnet, enroll). Settings (behind
  the parent code) edits Server URL (`SettingsFragmentLauncher.kt:354-370`) and scans a setup QR (`:645-690`, the same
  `applyProvisioningExtras`).

## 2. Decisions

### D1. What is pinned: root SPKI SHA-256, built-in set plus the parent's set, no TOFU

Pins are `sha256/<base64>` of the DER `SubjectPublicKeyInfo` (OkHttp's and HPKP's form). A chain passes when **any**
certificate in Android's *validated* chain matches - in practice the anchor, or a cross-signed intermediate carrying a
pinned root's key (ZeroSSL's USERTrust RSA cross-signed by AAA Certificate Services; Let's Encrypt's X2 cross-signed by
X1). Matching by key, not certificate, keeps cross-signs and root re-issues working.

**B** (derived 2026-11-04 on the build VM from Debian's `ca-certificates 20250419+deb13u1`, `/etc/ssl/certs/*.pem`, by
`openssl x509 -in F -noout -pubkey | openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | base64`; each
certificate's DER SHA-256 is listed so the implementer re-derives and compares - step 0 does):

| Name | Pin | Cert SHA-256 (first 8) | Expires |
|---|---|---|---|
| ISRG Root X1 | `C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=` | `96BCEC06` | 2035-06-04 |
| ISRG Root X2 | `diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=` | `69729B8E` | 2040-09-17 |
| USERTrust RSA Certification Authority | `x4QzPSC810K5/cMjb05Qm4k3Bw5zBn4lTdO/nEW/Td4=` | `E793C9B0` | 2038-01-18 |
| USERTrust ECC Certification Authority | `ICGRfpgmOUXIWcQ/HXPLQTkFPEFPoDyjvH7ohhQpjzs=` | `4FF460D5` | 2038-01-18 |
| Sectigo Public Server Authentication Root R46 | `Douxi77vs4G+Ib/BogbTFymEYq0QSFXwSgVCaZcI09Q=` | - | 2046-03-21 |
| Sectigo Public Server Authentication Root E46 | `sLVjNUaFYfW7n6EtgBeEpjOlcnBdNPMrZDRF36iwBdE=` | - | 2046-03-21 |

ISRG Root X1's value matches the one Let's Encrypt and OkHttp's docs publish. R46/E46 are Sectigo's (USERTrust's
operator) dedicated TLS roots: they add no new company, and keep a ZeroSSL fallback working once ZeroSSL's chains move
to them (Android stops the validated chain at the first anchor it has, so a phone with R46 in its store would not see
USERTrust). **TBD**: Let's Encrypt's announced next root generation (ISRG Root YR/YE) - add their pins in a launcher
release (and E meanwhile) once they are in Mozilla's store; until a phone's store has them, the cross-sign keeps X1/X2
in its validated chain. The step-1 commit fills the two missing cert hashes with the same commands.

**E** comes only from places the parent authenticates: the setup QR (shown on the parent's own screen, scanned by
the parent) and the policy (which arrives only over an already pinned https connection or the WireGuard-authenticated
tailnet). The server proposes E's entries from a self-check (D5) and the parent approves each.

**Rejected: trust on first use.** Learning the anchor of the enroll call right after the QR would pin whatever an
on-path attacker with an emSign (or any other mis-issued) certificate presented at that moment, and keep it. The QR
can carry the root directly, so nothing needs to be learned. **Rejected: leaf/SPKI of the server's own key** (Caddy's
key rotation; the PLAN's earlier `--reuse-key` idea would bind the phones to one key file forever). **Rejected:
`network_security_config` `<pin-set>`**: it names domains at build time; ours is the parent's, known only at runtime.

### D2. How the set changes

- **Fresh phone / re-scan**: the QR's `server_roots` (a comma-separated string of pins - Android's admin extras bundle
  is flat strings; at most 4; empty or absent = none) replaces the stored E together with the address. A present but
  malformed value refuses the whole QR: nothing saved, nothing enrolled (as S-M1 does for a refused address).
- **Policy**: `server_tls: {"roots": [{"pin": "sha256/...", "name": "GTS Root R1"}]}` replaces the stored E at each
  accepted policy. Key absent (an older server) = keep the stored E; an invalid object (not a list, more than 4, a
  malformed pin, a name over 64 characters) = keep the stored E and log - never a refusal of the policy. `[]` clears E.
  B is never in the policy's power: the server can widen to E, not remove Let's Encrypt.
- **Settings › Server URL** (parent code): changes the address only; the pin set stays (it belongs to the phone's
  setup, not the URL, and a new https address is pinned the same way). The tailnet http address needs no pins.
- **Moving the server to another CA**: allow the new root on the server first (Settings › Phone Setup, D5); every phone
  takes it at its next sync (over the old, still pinned certificate); then switch the issuer in Caddy. Phone Setup
  says so.
- **Before expiry**: B's roots run to 2035-2046; Android drops a root only through Conscrypt's mainline updates. New
  roots reach B by a launcher release and, sooner, E by the policy. A phone whose store gains a new anchor not in B∪E
  (the YR case above) is the failure mode §D3 handles; the server's self-check flags it on the server first when the
  server's own store is ahead (D5).
- **Migration**: phones enrolled before step 2 have no E. Their first policy from a step-2 server delivers E over the
  current connection; and step 2's launcher, at its first start, reads `server_tls` from the cached raw policy
  (`cachedPolicy()`, `MdmSyncWorker.kt:1230`, which keeps the server's JSON) when it has no stored E - as trustworthy
  as that policy was. Release order: **server first**, then the launcher (as S-M1). Step 1 alone needs no migration
  for a server whose chain ends in B; step 0 checks the user's.

### D3. Failure: closed, never silent, recoverable without adb

- **Mechanism**: the check runs inside the TLS connect, after Android's validation and before any request byte: the
  bearer, the status report and the SSE subscription never reach a forged server. No fallback, no retry without pins,
  no "accept anyway" (project rule: no insecure fallbacks).
- **The phone keeps working offline**, exactly as with a dead server: the cached policy and the last-enforced plan,
  time rules and schedules, the PIN lock and the parent code (checked offline against the vault), the allowlist,
  calls and **emergency calls** (Telecom, no network), alarms, downloaded music. What stops: new policy, commands
  (lock, locate, wipe), reports, location, the time request (its existing "couldn't reach" answer), catalog and own
  updates, the music courier (Spotify stops when its token runs out).
- **The kid** sees nothing new: no alarm, no notification text (Vibb One stays calm; the kid can't fix it). The time
  request says it couldn't reach the parent, as offline.
- **On the phone, for the parent** (Settings, behind the parent code): a new row "Server certificate" under Server URL
  (summary: what is accepted, or the last refusal with the issuer's name and time); "Sync now" says why in a dialog
  (as S-M1's closed path: E9 L1, a dialog, not a toast). The refusal is stored (time, host, the validated chain's
  anchor name and pin) and cleared by the next pinned success.
- **On the server**: nothing can arrive, so the PWA shows the phone as not seen since (as any dead phone). Step 2
  adds the cause where the server can know it: when its self-check finds its current root outside B∪E, Phone Setup and
  the device page say "The phones refuse this server's certificate (from X)" with the Allow button.
- **Recovery without adb**: (a) the parent puts the server back on an accepted issuer (Caddy's default), or (b) allows
  the new root in Phone Setup and scans the phone's setup QR in its Settings (parent code) - the QR carries E and
  enrolls again; (c) a phone with no parent code yet has Settings locked (`settings_gate_no_pin`): factory reset and a
  new setup QR (as S-M1). Whether (d), "accept the issuer the server shows now" on the phone itself, exists is
  question Q2.

### D4. Scope

| Path | Pinned | Why |
|---|---|---|
| `Direct` https (API, SSE, music courier, APK and file downloads) | yes | every byte to the parent's server over TLS |
| `Direct` https through tsnet's SOCKS proxy (e.g. a `*.ts.net` name) | yes | TLS is end to end through the proxy; the proxy dials non-tailnet addresses on the OS network (`dial.go`, `UserDial`); `tailscale cert` is Let's Encrypt, in B |
| `Tailnet` http to a Tailscale IP | no | no TLS; WireGuard authenticates the peer, and our proxy dials it only through netstack while it is a current peer (S-M1 H1) |
| debug `http://10.0.2.2` (emulator loop) | no | cleartext, debug builds only |
| GitHub (`releases/latest` at provisioning) | out | Android's own download, verified by the QR's signature checksum; the launcher never asks GitHub |
| DoH upstream, tsnet control plane, the music app's third-party hosts | out | not the parent's server; own trust (DoH's system CAs; Tailscale's Noise keys) |

### D5. Server side (step 2): the self-check and the allow list

- **Self-check** (`server/src/server_tls.rs`, new): for the phones' address (`provisioning::server_url`), when it is
  https: one TLS handshake to it (10 s timeout), the presented chain verified with rustls-webpki 0.103 against the same
  root store the server's outbound TLS uses (the image's filtered bundle, `server/deploy/distrusted-roots.txt` - so an
  emSign chain never verifies), and the verified path's anchor (`VerifiedPath::anchor()`; its SPKI contents re-wrapped
  in their SEQUENCE before hashing) as `{pin, name}`. Run at Phone Setup's page load (cached 10 minutes) and once a
  day; a failure (hairpin NAT, DNS) shows "couldn't check" and changes nothing.
- **The allow list** (Settings › Phone Setup, a new card "Certificate issuers the phones accept"): B by name ("built
  in"), then E's rows with Remove; when the self-check's root is outside B∪E, "This server's certificate is from X -
  the phones refuse it until you allow it" with "Allow X on the phones". Never added without the click. Stored in the
  settings table (a migration), at most 4; a save never jumps to the top (pwa-save-no-jump).
  The server never re-checks a parent-approved pin's origin; it is the parent's word.
- **The QR**: `AdminExtras` gains `server_roots` (E's pins, comma-separated; `fits()` at `provisioning.rs:331` still
  decides). Phone Setup refuses to render a QR while the self-check's current root is outside B∪E (the phone would
  refuse the enroll), with the Allow button beside the message; a failed self-check doesn't block the QR.
- **The policy**: `server_tls.roots` = E with names, for every phone (pinned by `policy_json_keys_snapshot` and the
  launcher's `PolicyResponseCompatTest`).
- **The phone's capability** `server_pin_v1` (`StatusCapabilities.kt:18`): the device page says "Accepts only the
  issuers in Phone Setup" for such a phone.

## 3. Launcher implementation sketch

- **`server/ServerPins.kt`** (new): `BUILTIN_PINS` (D1's table: name + pin), the store (two keys in the same prefs as
  `mdm.serverUrl`: `server_pins_extra` - JSON `[{pin, name?}]`; `server_pins_refusal` - `{at_ms, host, name, pin}`),
  and pure rules: `parsePin` (`sha256/` + base64 of exactly 32 bytes), `qrRoots(text)` (null = refused), `policyRoots
  (json)` (null = keep), `pinSet(stored) = BUILTIN ∪ stored` (an unreadable store = BUILTIN only, never "no pins").
- **`toServer`** (`ServerTransport.kt:129`): for `ServerPath.Direct` with an https base URL,
  `.certificatePinner(CertificatePinner.Builder().add(host, *pinSet(...)).build())` with the URL's exact host, plus
  `followRedirects(false)`/`followSslRedirects(false)` like the tailnet path (a pin covers one host; the device API
  never redirects, `ServerTransport.kt:134`). OkHttp 4.12 checks pins in `RealConnection.connectTls` against the chain
  cleaned by Android's `X509TrustManagerExtensions` (the validated path, anchor included), before the connection is
  pooled or any request is written; with a SOCKS proxy the TLS is still to the origin. The pins are read where the path
  is read: each `createMdmApi`, each SSE connect, each `createMusicServer`, so a new E applies at the next connection.
  `toServer`'s first interceptor (it already runs first, before the bearer) catches an `SSLPeerUnverifiedException`
  that `isPinFailure` recognises, stores the refusal (`pinRefusal(message)`: OkHttp's "Certificate pinning failure!"
  message lists the peer chain's pins and subjects; the last is the anchor) and rethrows `ServerPinRefused` (an
  `IOException`, like `ServerPathClosed`), which "Sync now" words.
  The tests pin the IPv4/IPv6-literal https host case (OkHttp's pin pattern with an IP literal); if OkHttp refuses an
  IP pattern, an IP-literal https host gets the same check in a `HostnameVerifier` wrapper instead (no IP pattern).
- **Provisioning** (`ProvisioningExtras`, `applyProvisioningExtras`): read `server_roots`, refuse a malformed one
  before saving anything, save E with the address.
- **Policy** (`PolicyResponse.serverTls`, raw JSON like `music`): read leniently at sync into the store; step 2's first
  start fills an empty store from the cached policy (D2, migration).
- **Settings**: the "Server certificate" row and dialog (accepted names; the last refusal; "Scan setup QR" as the way
  to fix it); "Sync now"'s refusal dialog.
- **Debug only**: the debug network security config gets `<debug-overrides>` trusting user CAs, so mitmproxy's CA on
  the emulator passes Android's validation and the pin is what refuses (QA's negative test). The release config is
  unchanged; `ServerTransportTest`'s pinning of both files is updated.
- **Texts (en / nb)**, `values/` and `values-nb/`:
  - `settings_mdm_server_cert`: "Server certificate" / "Serversertifikat"
  - `settings_mdm_server_cert_ok`: "Accepts Let's Encrypt and ZeroSSL" / "Godtar Let's Encrypt og ZeroSSL"
  - `settings_mdm_server_cert_ok_more` (plural): "Accepts Let's Encrypt, ZeroSSL and %d more from your server" /
    "Godtar Let's Encrypt, ZeroSSL og %d til fra serveren din"
  - `settings_mdm_server_cert_refused`: "Refused a certificate from %1$s at %2$s" / "Avviste et sertifikat fra %1$s
    kl. %2$s"
  - `server_cert_explain`: "Vibb One only talks to your server when its certificate comes from an issuer it
    accepts: %1$s. If you moved the server to another issuer, allow it in Phone Setup on the server and scan this
    phone's setup code again (Settings › Scan setup QR)." / "Vibb One snakker bare med serveren din når sertifikatet
    er fra en utsteder den godtar: %1$s. Har du flyttet serveren til en annen utsteder, godta den under
    Telefonoppsett på serveren og skann telefonens oppsettkode på nytt (Innstillinger › Skann oppsett-QR)."
  - `server_cert_refused_sync`: "The server's certificate is from %1$s, which this phone doesn't accept. Nothing was
    sent." / "Serverens sertifikat er fra %1$s, som denne telefonen ikke godtar. Ingenting ble sendt."
  - Server (step 2, the PWA's catalogue, en + nb): the card's title, "built in" / "innebygd", "This server's
    certificate is from %s - the phones refuse it until you allow it" / "Serverens sertifikat er fra %s - telefonene
    avviser det til du godtar det", "Allow %s on the phones" / "Godta %s på telefonene", "Couldn't check the server's
    certificate" / "Fikk ikke sjekket serverens sertifikat", and the device page's line.

## 4. The contract (step 2)

`server/testdata/server_pins.json`, byte-identical in `launcher/app/src/test/resources/`:
`builtin` (`[{name, pin}]` - both sides check their constants against it), `spki` (`[{pem, pin}]`: B's six root
certificates as PEM - public data - and one ECDSA and one RSA test root; the server's `server_tls::tests` and the
launcher's `ServerPinsTest` (via `CertificatePinner.pin`) compute every pin), `qr` (`[{server_roots, pins | null}]`),
`policy` (`[{server_tls, pins | "keep"}]`). Run by `server_tls::tests::the_contract` and `ServerPinsTest`; both compare
the copies; one commit for both directories. The policy key goes into `policy_json_keys_snapshot` and
`PolicyResponseCompatTest`. CI: add both copies to `launcher.yml`'s and `server-ci.yml`'s path lists, and the file to
the root `CLAUDE.md`'s contract paragraph and its CI list. Step 1 keeps B's table in `ServerPinsTest` alone.

## 5. Tests (lean)

- `ServerPinsTest` (JVM): the parsers, `pinSet` (unreadable store = B), the policy's keep/clear/replace rows, B
  against the contract, `pinRefusal` against a real OkHttp failure message.
- `ServerTransportTest` (JVM, extended): a local TLS server (`SSLServerSocket`, a throwaway test root, intermediate
  and leaf made once by a small script, files named `test-only-*`, public test data only) through the real `toServer`
  with the test root as the client's trust: a pinned root passes, an unpinned one fails before the request line
  reaches the server (the fake server records it never saw `GET`), the bearer is never seen, an IP-literal host, a
  redirect not followed; through the existing fake SOCKS5 proxy too. A source scan: every https server client goes
  through `toServer` (already pinned) and nothing calls `certificatePinner` elsewhere.
- Server: `server_tls::tests` (the contract, the anchor of a fixture chain, the allow-list form, the QR's
  `server_roots`, Phone Setup refusing a QR outside B∪E), `policy_json_keys_snapshot`.
- Emulator (QA): the user's real server (passes; Settings shows "Accepts ..."); mitmproxy with its CA as a user CA on a
  debug build (refused, nothing sent - mitmproxy's log shows no request; the Settings row and "Sync now" say why;
  the lock, calls and 112 keep working; a policy with E then a re-scan); a tailnet http address (unpinned, works).

## 6. Security analysis

- **Stops**: a certificate for the parent's host from any CA outside B∪E - emSign (Conscrypt on Android 16 still
  carries emSign ECC Root CA - G3 until Google's mainline update), any of the ~140 other roots in Android's store, a
  compelled or careless CA, a "valid" captive-portal or DNS-hijack certificate - presented by an on-path attacker on
  the kid's Wi-Fi, a mobile network or a hostile resolver. The device token, the reports (location) and the commands
  no longer depend on every CA in the store, only on two operators (ISRG, Sectigo) and what the parent allowed.
- **Doesn't stop**: mis-issuance by Let's Encrypt or Sectigo themselves, or an attacker who passes their domain
  validation (hijacks the parent's DNS, or sits on the path to the server for HTTP-01) - CAA records with
  `accounturi` narrow that (one optional line for `server/DEPLOY.md`); a compromised server (it owns the policy and can
  set E anyway); a compromised phone; the tailnet path's trust in Tailscale's coordination server; Android's own
  validation bugs (we pin after it, never instead of it).
- **TOFU**: none. The only learned roots are the server's, approved by the parent and carried by the QR the parent
  scans or the pinned policy. The self-check's root came over the server's own network but is validated by the
  server's filtered store and shown to the parent, who decides. Migration (D2) reads the cached policy once - no
  weaker than the connection that delivered it, and only to fill an empty E.
- **Downgrade paths**: no switch turns pinning off; B can't be removed by anyone but a launcher release; E widens
  only by the QR (needs the parent code on an enrolled phone, or a factory reset), the policy (needs the pinned
  server or the tailnet) or not at all; an unreadable store falls back to B, not to "no pins"; an older server's policy
  keeps E; a malformed QR value refuses the QR. Old launchers can't be installed over a new one (versionCode, the
  update fence). User CAs stay untrusted in release builds.
- **With S-M1**: S-M1 decides the scheme (https or the tailnet), this design narrows https's trust. `Closed` stays a
  refusal before any socket; a pin refusal happens one step later (after the handshake, before the request) and is
  worded the same way. The tailnet http path remains the only unpinned way and only through our proxy to a current
  peer.

## 7. Steps and sizes

0. **Check (S, no code)**: `openssl s_client -connect <the user's host>:443 -showcerts` (today's chain, its anchor in B);
   on the emulator, the anchor Android validates for that host and for a ZeroSSL-issued host (does it end at
   USERTrust or R46?); the two missing cert hashes; whether every phone's address is served by Caddy (Q1).
1. **Launcher, B only (M)**: `ServerPins.kt`, the pinner and redirects in `toServer`, the refusal state, Settings'
   row and "Sync now", the debug override, texts en + nb, the tests, the notes (`docs/notes/launcher/security.md`, a
   section after S-M1's). Launcher release only; ships alone.
2. **E (M-L, both directories)**: the self-check, Phone Setup's card and QR refusal, the settings migration, the
   QR's `server_roots`, the policy's `server_tls`, the launcher's readers and migration, `server_pin_v1`, the contract
   file and CI lists, PWA texts en + nb, the server notes. Server release first, then the launcher.

## 8. Questions for the user

- **Q1.** Does every phone reach the server at an https address served by your Caddy (Let's Encrypt or ZeroSSL)? A
  Cloudflare tunnel (Google Trust Services / SSL.com) or another proxy in front would need step 2 before step 1 ships.
  (`tailscale serve` / `*.ts.net` is Let's Encrypt and fine.)
- **Q2.** Recovery on the phone: only by scanning the setup QR in Settings (recommended - nothing is accepted that the
  server and you didn't approve), or also a button "Accept the issuer the server shows now" behind the parent code?
  The button helps when you're away from the PWA, but on a hostile network it would accept the attacker's issuer if
  you press it (the name is shown, e.g. "emSign ECC Root CA - G3").
- **Q3.** Step 2 now, right after step 1, or only when a phone needs an issuer outside Let's Encrypt/ZeroSSL? Without
  it, moving to another CA later needs a launcher release first.
