# Design 129: A hardware-bound phone key

Status: design, revision 2 (Q1-Q10 decided; gates on Android 14 and 16), 2026-11-24 (branch `hw-key`, from main 2d08708eb). Revision 2 answers the design QA of
revision 1 (H1-H4, M1-M8, L1-L8; the finding is named where its fix lands) and records the owner's decisions on Q1-Q8
(§11). Server and launcher; the music app is untouched (it never holds the device credential: its server calls go
through the launcher's bridge). Owner-approved direction: step 1, a non-exportable Keystore key with key attestation
replaces the bearer; step 2, the server reads the verified-boot state from that attestation.

**Addendum 129b** (2026-11-24, the owner's decision before S2): a new phone's enrolment requires a proven key from the
next step on, with a per-phone exception the parent confirms - `docs/design/129b-enrolment-requires-a-key.md`. It
replaces S1's "observe" enrolment (§5.1's S1 answers), builds §7.3's `emulator_keys`, and changes what S2 does for an
exempt phone (129b §8).

Marks: **[code]** read in the code at 2d08708eb; **[doc]** read in Android's or GrapheneOS's documentation on
2026-11-24 (developer.android.com "Verify hardware-backed key pairs with key attestation", source.android.com
"Key and ID attestation", grapheneos.org "Attestation compatibility guide"); **[assumed]** not verified, to be
checked by step 0 (§10) or the emulator gate.

## 0. Short answer

- At enrolment the launcher makes an **EC P-256 signing key in AndroidKeyStore** (StrongBox when the phone has it,
  the TEE otherwise), attested with a challenge the server fixes. The server checks the chain up to **Google's two
  attestation root keys** and against **Google's revocation status list**, checks the key's hardware-enforced
  properties, the app (a platform claim, §3 item 10) and the **boot state** (from S2: locked and `Verified`, or locked
  and GrapheneOS), and binds the public key to the phone under a **random key id**.
- From then on **every request to `/api/devices/*` carries a signature** over the method, the raw path and query, the
  Content-Encoding, a **body hash sent in its own header**, a time on the server's own monotonic signature clock and a
  random nonce. The signature verifies **from the headers alone, at the edge**, before any body is read and before
  the request takes a per-device slot; the body is hashed as it streams and a mismatch never reaches a handler. A
  bound phone's bearer no longer authenticates, and a bearer sent for a bound phone is a security event.
- **Registration is idempotent and owned by one flow** on the phone: no other call ever deletes a key, and the phone
  keeps every key it made until the server names the one it holds (`GET /api/devices/key`). A lost answer can't lock
  a phone out.
- **Transition**: the first server release only **observes** (verifies, stores, shows the verdict, keeps the bearer:
  the probation, once, for the whole fleet). S2 commits each phone - except a phone with a key conflict, a
  registration from an address its bearer never used, or a boot state that doesn't pass, which waits for the parent.
  S2 is a one-way door for the database (§8.1).
- **Fail closed**: an attestation that does not verify is refused - never a software key, never "accept anyway". A
  refused phone stays on its bearer with one orange line (from S2); since design 129b a new enrolment requires a key,
  and a phone that can't attest waits for the parent's per-phone exception.
- **Step 2**: `Verified` with a locked bootloader, or `SelfSigned` with a verified-boot key on the pinned GrapheneOS
  list: nothing shown. Anything else: one orange line, **"Ukjent eller ulåst system" / "Unknown or unlocked system"**.

## 1. Today [code]

- `POST /api/devices/enroll {enrollment_code}` answers `{device_id, device_token}`; the server keeps
  `SHA-256(token)` in `devices.token_hash` and an in-memory `TokenIndex`. `security::require_device_token` guards
  every other device route (`device_routes.rs`), with the per-client failure bucket and `device_auth_failed`.
- `limits::device_edge` (the outer layer of every device route) looks the bearer up in `TokenIndex`: a request
  without a valid one takes one of the `UNAUTHENTICATED_IN_FLIGHT` slots (503 past it); every route but the stream and
  downloads is cut at `REQUEST_TIMEOUT` (30 s).
- Body limits are per route: `DEVICE_BODY_LIMIT` (512 KiB) for most, `calendar_kid_events::BODY_LIMIT + 1`
  (1 MiB + 1) for `/api/devices/calendar/kid-events`, smaller ones elsewhere.
- The launcher seals the token at rest (`secrets_v1`, design r10) and sends it only to the server's own origin
  (`ServerAuth.bearerInterceptor`, L8; `CommandListenerService` adds it to the SSE request by hand). Every server
  client goes through `ServerTransport.toServer` (origin fence, design 76's pin, the tailnet path). The token is also
  an identity: `PolicyOwner.of(version, url, token)` (`PolicyEtags.kt`) and `HeartbeatAckStore.ownerOf(url, token)`
  (`StatusReports.kt`) key the ETag cache and the heartbeat base on it.
- The gap: the token is a bearer. Whoever lifts it - from a compromised phone, a memory dump, a log, a
  TLS-terminating proxy in front of the server - can be the phone from anywhere until the parent re-enrols it.

The goal is narrow: **a credential lifted from the phone is worthless off the phone**. Code running on the phone as
the launcher can still use the key (Keystore can't tell), which is out of scope; step 2 is what speaks about a phone
whose system is not what it should be.

## 2. The key on the phone

- `server/DeviceKey.kt`: `KeyPairGenerator.getInstance(EC, "AndroidKeyStore")` with
  `KeyGenParameterSpec.Builder(alias, PURPOSE_SIGN)`: `ECGenParameterSpec("secp256r1")`, `DIGEST_SHA256`,
  `setAttestationChallenge(challenge)`, **no user authentication and no `UnlockedDeviceRequired`** (the sync, the
  stream and the relock run while locked - the same rule as `secrets_v1`), `setIsStrongBoxBacked(true)` when
  `FEATURE_STRONGBOX_KEYSTORE`.
- **StrongBox falls back to the TEE on any failure** to generate there (`StrongBoxUnavailableException` or any other
  `ProviderException`/`KeyStoreException`), once, never further (L5). Never a key without attestation, never a
  software fallback.
- **Keys unavailable** (M7): on a remote-provisioning-only phone an empty attestation-key pool makes generation fail
  with `KeyStoreException.ERROR_ATTESTATION_KEYS_UNAVAILABLE` (transient) [doc]. That is a retry with its own reason
  `keys_unavailable` (WorkManager backoff, network required), never a downgrade, shown as a quiet value until a day
  has passed, then the `key_refused` line with that reason. The pool is filled by `com.android.rkpdapp`, which the
  kid's VPN blackholes in its SINK shape (only the launcher is excluded today, `KidVpnService`): rkpd is also
  excluded (`addDisallowedApplication`, when the package exists), since it talks only to Google's provisioning
  service and gives the kid no path out (Q9, decided). The gate checks generation in the SINK shape with an empty pool.
  **As built in L1 (code QA M4, 2026-11-24):** the package name differs by vendor - the owner's Jelly Star (Android 16)
  has **`com.google.android.rkpdapp`**, AOSP names it `com.android.rkpdapp`. So the launcher finds rkpd by what it does:
  a system service for Keystore's bind action `com.android.rkpdapp.IRemoteProvisioning` (AOSP
  `packages/modules/RemoteKeyProvisioning`, `app/AndroidManifest.xml`, `.service.RemoteProvisioningService`), resolved
  with `MATCH_SYSTEM_ONLY`, with both names as the fallback; each candidate is exempted only when it is a system app
  with an app uid of its own (`uid >= FIRST_APPLICATION_UID`, `getPackagesForUid(uid)` one package), so no shared uid
  rides along. It goes in the VPN's disallowed apps **and** lockdown's allow list (under lockdown an app outside the
  VPN has no network otherwise). None found: logged once, and the key path waits as `keys_unavailable` wherever the
  pool runs dry - never a lockout. The launcher has no WorkManager: the retry is the registration flow's own backoff.
  **Per-request latency (code QA M3):** the key is StrongBox when the phone has it (Q2); signs are serialised and a
  transient Keystore failure is retried twice; step 0 measures the sign time under load on the Jelly Star, and Q2's
  ceiling rule (above ~150 ms p95, the TEE) is applied then - a measurement, not a guess, decides.
- Aliases `device_key_<n>`, `n` counting up. The phone **keeps every key it made until the server names one** (§5.2);
  then it deletes the others. At most three exist; a fourth generation first deletes the oldest key whose challenge
  has expired and that the server has never named. No ID attestation (`setDevicePropertiesAttestationIncluded` stays
  off: the server needs no IMEI, serial or model).
- `getCertificateChain(alias)` gives the chain, leaf first; it goes to the server as Base64 DER.
- The server's key id for the adopted alias and the server's verdict are plain prefs (not secrets). The sealed device
  token is deleted when the policy says `bound` (§5.5).
- Sign: `Signature.getInstance("SHA256withECDSA")` (DER output), on a background thread, as every server call is.
  StrongBox serialises operations, so parallel workers queue on it (Q2's measurement is under that load).

### 2.1 One credential (H4)

L1 adds `server/DeviceCredential.kt`: `sealed interface DeviceCredential { Bearer(token) ; Key(keyId, signer) }` and
`DeviceCredentials.current(context)`, **the only reader of the sealed token** (it and `SealedPrefs` itself; the
token's writers stay `Enrollment` and `FirstLaunchReset`).

- Every site that reads the token today takes the credential instead: `MdmSyncWorker`, `CommandListenerService` (its
  hand-made SSE header becomes `credential.authorize(request)`), `AppInstallReceiver`, `AppDownloads`, `PowerEvents`,
  `WeatherPull`, `MusicSync`, `MusicFileDownloads`, `MusicBridgeService` (both sites), `WifiPlaces`, `UpRuntime`,
  `MapLinkActivity`, the Settings server edit, `ConnectActivity` and `ReEnrolDialog`; L1 greps for every
  `deviceToken`/`DEVICE_TOKEN` read first and the list in the commit is that grep's.
- `ServerAuth.bearerInterceptor` becomes `ServerAuth.credentialInterceptor(credential)`: the bearer header or the
  signer (§4.2), same-origin only, as today.
- **The owner ids are re-keyed**: `PolicyOwner.of(version, url, credential.ownerId)` and
  `HeartbeatAckStore.ownerOf(url, credential.ownerId)`, where `ownerId` is the token's hash for a bearer and
  `key:<key id>` for a key. Binding changes the owner, so the phone fetches the policy without an ETag once and sends
  one full report: intended, and pinned in `PolicyEtagsTest` and `StatusReportsTest`.
- `SealedPrefsTest`'s site pin (the files allowed to touch the sealed token) shrinks to `DeviceCredentials`,
  `SealedPrefs`, `Enrollment` and `FirstLaunchReset`; `ServerTransportTest`'s source scan pins
  `credentialInterceptor(` on every server client as it pins `bearerInterceptor(` today.

## 3. What the server checks (registration)

A new module `server/src/attestation/` (no new crate: `der` 0.7 and `ring` are already in `Cargo.lock` [code]; not
`rustls-webpki`, whose TLS validity and usage rules are wrong for factory attestation chains). It follows Google's
verification steps [doc] and ports the rules of Google's Kotlin verifier (`github.com/android/keyattestation`,
Apache-2.0), whose test chains become our vectors (§7). All of it runs **without the database**, before any
transaction (M4).

1. **Shape**: 2 to 6 certificates, each at most 4 KiB DER; the enrol body limit rises from 4 KiB to 32 KiB.
2. **Chain**: each certificate signed by the next, with **every algorithm pair the chains use** (L1): RSA PKCS#1
   with SHA-256; ECDSA P-256 and P-384, each with SHA-256 or SHA-384 (ring's `ECDSA_P256_SHA384_ASN1` and
   `ECDSA_P384_SHA256_ASN1` included); anything else is `chain`. The root's SubjectPublicKeyInfo is one of the
   **pinned root keys**, compiled in (`attestation/roots.rs`, each pin a SHA-256 of the SPKI, computed by a test from
   the certificates in `server/testdata/attestation/roots/`):
   - the RSA-4096 key with subject `SERIALNUMBER=f92009e853b6b045` (one key behind the 2016, 2019, 2021 and 2022
     certificates - the 2016 one expired 2026-05-24, which is why we pin the key, not a certificate) [doc];
   - the ECDSA P-384 "Key Attestation CA1" (Google LLC, valid 2025-07-17 to 2035-07-15), signing chains from
     2026-02-01 [doc].
   Google's JSON root list is never fetched at run time: a new root comes in a server release, by design 76's rule for
   pins. PLAN chore at every server release: compare Google's page with the pins.
3. **Revocation**: no certificate's serial (lowercase hex, leading zeros stripped) is `REVOKED` or `SUSPENDED` in
   `https://android.googleapis.com/attestation/status` [doc]. The server fetches it in the background, honouring
   `Cache-Control` (at least hourly, at most daily) - a plain GET with no device data - and keeps the last good copy
   on disk. **A list older than 7 days stops registrations** (503 with `retry_after_s`; the phone retries) and puts one
   orange line on Settings (§9.1); bound keys keep working (§6.3).
4. **Validity**: by the Kotlin library's rule [doc]: remotely provisioned (RKP) certificates are checked against the
   server's time; factory chains under the RSA key are not refused for an expired certificate.
5. **The extension**: the KeyDescription (OID `1.3.6.1.4.1.11129.2.1.17`) is read from the **first certificate from
   the root side** that has it; when the provisioning-info extension (`1.3.6.1.4.1.11129.2.1.30`) is there, the
   KeyDescription must be in the very next certificate [doc]. A later copy is ignored. It must be the leaf's. The
   provisioning info is parsed; a set `is_lost_device` is refused (`lost`) (L1).
6. `attestationVersion >= 3` (Keymaster 4.0 and every KeyMint: 100 to 500; an unknown higher version parses - new
   tags are skipped, never guessed).
7. `attestationSecurityLevel` and `keyMintSecurityLevel` equal, and `TrustedEnvironment` (1) or `StrongBox` (2).
   `Software` (0) is refused (§7.3 for the emulator).
8. `attestationChallenge` equals the one the server expects (§5).
9. In the **hardware-enforced** list: purpose SIGN only, algorithm EC, curve P-256, digest SHA-256 among the digests,
   origin GENERATED, `noAuthRequired`. A property only in the software list does not count.
10. **The app** (M6): `attestationApplicationId` (tag 709) **is read from the software-enforced list**, where Keystore
    puts it [doc]: it is the Android framework's statement, not the secure hardware's, and a rooted or unlocked
    system can write anything there. It must name exactly one package, `me.vibb.launcher`, and one signature digest
    equal to `LAUNCHER_SIGNATURE_CHECKSUM` **decoded** (the setting is the QR's Base64url form [code]; decoded it is
    32 raw bytes, compared byte for byte; a setting that doesn't decode to 32 bytes is a startup error). It is a
    check against the wrong app on an honest system; on a dishonest one, item 13 is what refuses (H3). **A server
    without the setting refuses every registration** (a startup warning and the orange line on Settings).
11. The leaf's public key verifies the request's `proof` (§5).
12. Kept per key: the SPKI, the security level, `attestationVersion`, `osPatchLevel`, `verifiedBootState`,
    `verifiedBootKey`, `deviceLocked`, the chain's serials and which root (for §6.3 and step 2).
13. **Boot state** (H3), from S2: `deviceLocked` and `Verified`, or `deviceLocked`, `SelfSigned` and a
    `verifiedBootKey` on the GrapheneOS list (§9). Anything else is refused (`boot`). In S1 it is recorded and shown,
    not enforced.

Any failure is a refusal with one reason word (`chain`, `root`, `revoked`, `expired`, `software`, `properties`,
`app`, `boot`, `lost`, `challenge`, `proof`, `list_stale`) - never a weaker acceptance.

## 4. Every request signed

### 4.1 The choice

| Option | Why not |
|---|---|
| A challenge per request | A round trip per call on mobile; parallel calls need a pool of challenges. |
| A signed challenge buys a short-lived session token | A bearer again, for its life: the gap shrinks but stays. |
| Mutual TLS with the Keystore key | Breaks behind a TLS-terminating reverse proxy (most installs) and on the tailnet path (plain http over WireGuard). |
| **A per-request signature with time + nonce** (chosen) | No extra round trip; a lifted signature replays nothing (nonce cache) and covers only that exact request. Works on every path and through proxies. |

Cost: one ECDSA sign per request. In the TEE that is a binder call and a world switch, milliseconds [assumed];
StrongBox is much slower, tens to hundreds of ms, and serialised [assumed]. Step 0 measures it (Q2). On the server one
P-256 verify (ring), cheap next to the request itself.

### 4.2 The canonical form

Two headers:

- `Vibb-Body-SHA256: <hex SHA-256 of the body bytes as sent - of nothing for no body>` (H1);
- `Vibb-Signature: v1;k=<key id>;t=<ms>;n=<nonce>;s=<sig>`, the signature (Base64url, DER ECDSA) over the UTF-8
  bytes:

```
vibb-req-v1
<key id>
<t: decimal ms on the server's signature clock>
<n: 16 random bytes, Base64url without padding>
<METHOD>
<path from "/api/devices" on, raw as sent, and "?" + the raw query when there is one>
<Content-Encoding as sent, or empty>
<the Vibb-Body-SHA256 value>
```

- **The key id is random** (H1): 16 bytes from the server's CSPRNG, Base64url, given at registration; nothing derived
  from the device id. It is not a secret, but it can't be guessed, so a stranger can't make the edge spend a verify
  on a real key.
- **The path is the raw one** (L3): the phone signs OkHttp's `encodedPath` and `encodedQuery`; the server compares the
  request's raw `uri().path()` and `uri().query()`, never a decoded form. Cut at `/api/devices`, so a server URL with
  a path prefix that a reverse proxy strips signs the same. Every device path the launcher builds uses only
  `[A-Za-z0-9._~/-]` and the query only those and `=&` (a launcher test pins it), so a proxy that decodes and
  re-encodes (nginx's `proxy_pass` with a URI part does) changes nothing; DEPLOY.md's proxy section says to pass the
  URI through unchanged anyway. The contract carries percent-encoded rows (§7.1).
- No host: the key is known to one server only. A signature sent elsewhere could be replayed here, so the signer runs
  **only for the server's own origin** (`sameOrigin`, L8), as the bearer does today.
- The body is hashed as it travels (a gzip'd status report compressed); the server checks the hash **before** it
  inflates anything (§4.5).
- The signer is an OkHttp **network** interceptor: each attempt (OkHttp's own retry included) is signed afresh, so a
  retry is never a replay. Redirects are never followed (design 76). OkHttp's request bodies here are in memory
  (JSON, gzip'd bytes) or a file the launcher wrote; the interceptor hashes them once before sending.

### 4.3 Time, replay and the clock

- **The signature clock is the server's, and it never steps back** (M5). `sig_now()` = `max(wall clock at start +
  e, base + e - e/10)`, `e` the monotonic elapsed time since start, where `base = max(wall clock at start, floor)` and
  `floor` is read from the database at start. The server
  writes `sig_now()` to the database every 10 s and at shutdown; `floor` = the stored value + 10 s + the future
  window (30 s) + the past window (60 s), 100 s in all (S1's code QA M1: a `t` up to 30 s ahead of the last stored
  value stays acceptable for 60 s past it, so the floor must clear both windows). So no `t` accepted before a restart can be accepted after it, an NTP step or a VM snapshot restore
  moves nothing back, and while the wall clock is behind the signature clock simply runs ahead of it (it is used for
  nothing else).
- **A base ahead of the wall clock runs at nine tenths of real time** until the wall clock as it was at start plus `e`
  catches it up (after ten times its lead: a restart's 100 s in under 17 minutes), then the clock follows that. The L1
  gate (2026-11-24, `docs/testing/emulator.md` §6bj, finding 3) saw why: the stored value is the signature clock
  itself, so a restart soon after the last write put the clock 100 s ahead of the wall clock and every later restart
  another 100 s (~17 min after ~10 restarts), for good - and each process start's first signed request was `stale`.
  Choices weighed: a floor of `max(wall, stored + a small margin)` would let a `t` accepted just before a stop be
  accepted again after it (the replay set is in memory; S1's code QA M1), and storing only the highest `t` accepted
  still ratchets while phones sign on the clock that is ahead. Slowing the clock keeps every guarantee: both terms only
  grow (never a step back), it never runs faster than real time (so the stored value + 100 s still clears every `t`
  accepted before a stop), and the lead always decays. The cost is a phone's offset drifting by a tenth of the time
  since its last answer while the clock catches up: a phone quiet for over five minutes in that span signs one request
  `stale` and takes the new offset (S1: answered on the bearer; S2: one retry).
- The phone keeps `serverOffset` = the server's `Vibb-Time` (`sig_now()` in ms; a new header on **every** device
  answer: the route's, the auth layer's 401s and the edge's own 429 and 503, L4) minus `elapsedRealtime` at the round
  trip's middle; `t` = elapsedRealtime + offset. Not `TrustedClock`: that clock refuses a server marked `Vibb-Clock:
  off` and takes GNSS or a carried time, while the signature needs exactly the server's clock. Before any answer in
  this boot: `TrustedClock`'s time, else the wall clock - and a `stale` refusal corrects it. `TrustedClockScanTest`
  gets the signer's one wall-clock read.
- **Window** (L4): accepted when `sig_now - 60 s <= t <= sig_now + 30 s`, checked when the headers arrive. The body's
  upload time doesn't count against it (the signature is checked before the body is read), so a minute covers the
  phone's queueing and the offset's error; the edge's 30 s timeout bounds the rest.
- **Replay**: an in-memory set of (key, nonce) per key for the window; **a nonce is recorded only after its signature
  verifies** (L2), so junk can't fill it. A nonce seen again = `replay`. Bounded per key (4096; beyond it a 429). A
  restart forgets the set; the floor above makes everything signed before it `stale`.
- **One server process** (L2): the replay set lives in one process. Two server processes on one database (an update
  that starts the new container before stopping the old) would each accept a nonce once. DEPLOY.md states it: the
  update stops the old container first (Watchtower's default, and `update-image.sh`'s order [assumed: checked in S1]).
- The SSE stream signs its connect; the stream then lives as today and is closed by the server when the phone's
  credential changes (§5.6).

### 4.4 Answers

A refused request is a 401 with `Vibb-Auth: <reason>` and `Vibb-Time`:

| Reason | Phone |
|---|---|
| `stale` | take the offset from `Vibb-Time`, re-sign, one retry |
| `replay` | one retry with a new nonce (only a bug or an attacker gets here twice) |
| `unknown_key` | **never deletes the key**; hands the question to the registration flow (§5.2), which asks `GET /api/devices/key` |
| `bad_sig`, `bad_body`, `missing` | silent |
| `key_required` | a bearer for a bound phone, or (from S2) a bearer for a new enrolment: silent |

Every refusal but `stale` counts against the client's failure bucket and writes `device_auth_failed` with the reason,
as today. **A valid bearer for a bound phone** also writes `device_old_token` and puts the phone on Attention: the old
token is in someone's hands.

### 4.5 The edge (H1, M2)

`limits::device_edge` learns the signature; the order for a request carrying `Vibb-Signature`:

1. Parse the header; look the key id up in an in-memory `KeyIndex` (key id -> device, SPKI, state), loaded at start
   and changed with every key write, as `TokenIndex` is. An unknown id, a malformed header, a window miss or a missing
   `Vibb-Body-SHA256` is an unauthenticated request: it takes an `UNAUTHENTICATED_IN_FLIGHT` slot as today and is
   answered 401 by the auth layer.
2. Verify the signature over the headers (one P-256 verify). Only a request that verifies is **authenticated**: it
   takes no unauthenticated slot, its nonce is recorded, and only then does it take the per-device bucket and
   in-flight slot (as a valid bearer does today).
3. The body is wrapped in a hashing body (no buffering) **outside** any decompression; at its end the hash is
   compared, and a mismatch makes the body yield an error, so the route's extractor fails (400 `bad_body`) and the
   handler never runs. Every device route reads its whole body through an extractor before it acts [assumed: S1 adds
   a source scan of `device_routes.rs` that pins it].
4. The body limit stays **each route's own** (`DefaultBodyLimit` as today): the auth layer buffers nothing, so
   `/api/devices/calendar/kid-events` keeps its 1 MiB + 1 and every other route its own. A test posts a signed
   kid-events body of exactly `BODY_LIMIT + 1` and one of 512 KiB + 1 to a 512 KiB route.

The edge's classification is a contract row (§7.1): bearer valid, bearer invalid, signature valid, signature with an
unknown key, a bad signature, a stale `t`, a missing body hash.

## 5. The flows

### 5.1 A new enrolment (M4)

`POST /api/devices/enroll {enrollment_code, chain, proof}`.

- The attestation challenge is `SHA-256("vibb-enroll-v1" || 0x00 || normalised code)`: the phone knows it before it
  calls, so enrolment stays one round trip; it ties the attestation to this code (freshness, not secrecy - the code is
  the credential, as today).
- `proof` = the new key's signature over `vibb-key-v1\n<challenge Base64url>\nenroll`.
- **Order**: §3's checks run first, with no database (the challenge comes from the submitted code). Only a chain that
  passes reaches the code's transaction (`UPDATE .. RETURNING`). A refusal leaves the code live (since 129b D5 it no
  longer counts against the enrol failure bucket; a wrong code still does).
- **Answers** (since 129b a refused or missing key no longer enrols: `409 waiting` and the parent's exception,
  129b §4.1, revision 2; the S1 answers below are history):
  - S1: `{device_id, device_token, key: {key_id, state: "observed"}}`, or, when the chain was refused,
    `{device_id, device_token, key: {state: "refused", reason}}` - the phone enrols on the bearer (S1 never refuses an
    enrolment for its key) and deletes the refused key; the registration flow then waits as after a refused registration
    (a day; the L1 gate's finding 2: the first sync made a second key the server refused again).
  - From S2: `{device_id, key_id}` - no token; a refused chain is a 400 `{reason}` and the code stays live; the old
    body (code only) is a 400 `key_required`.
- **Idempotent** (lost answer): the consumed code keeps the SPKI that consumed it until the code's own expiry. The
  same code with the same SPKI (and a fresh valid `proof`) gets the same answer again; in S1 the bearer is reissued
  (the server keeps only its hash, and the phone never got the first). The same code with another SPKI is the
  ordinary "code used". S1's code QA H1 bounds it: at most **two** repeats, each with a proof whose ECDSA `r` was never
  seen for this code (`r`, not the bytes: `(r, s)` and `(r, n - s)` both verify), so a captured enrol body never
  replays - **the phone signs the proof afresh on every attempt** (L1). Revoke Access and any later enrolment of the row
  clear the kept code; each repeat is logged (`device_enrolled`, "asked again").

### 5.2 A phone enrolled today: the registration flow (H2)

One `KeyRegistration` worker owns it (unique WorkManager work, never two at once); no other code makes, adopts or
deletes a key.

1. `GET /api/devices/key` (bearer, or the key once adopted) -> `{state: none|observed|bound|held, key_id?,
   spki_sha256?, rotate}`. This call decides; nothing else does.
2. **The server names a key the phone holds** (its `spki_sha256` matches a local alias): adopt it (store the key id),
   delete the other aliases. Done.
3. **The server names a key the phone doesn't hold**: a conflict - someone registered first. Nothing is deleted or
   made; the phone reports `key_conflict` (one `POST /api/devices/key/conflict`, bearer), which puts the phone on
   Attention (§9.1); only a new setup code clears it.
4. **The server has none**: `GET /api/devices/key/challenge` (bearer) -> `{challenge, expires_in_s: 600}` (32 random
   bytes, one live per device, used once); make a new alias with it; `POST /api/devices/key {challenge, chain,
   proof}` (bearer; `proof` as §5.1 with `register`).
5. **The POST is idempotent**: a POST whose SPKI equals the stored key's answers 200 with the same `{key_id, state}`
   again (whatever the challenge's state); a different SPKI while a key is stored is 409 `exists`. Whatever the POST's
   fate - an answer, an error, a timeout - the worker goes back to step 1; the key it made stays until step 2 or the
   cap in §2 removes it. So a POST that the server commits after the phone gave up is found by step 1 and adopted; a
   key is never deleted while the server might still name it.

The phone signs nothing until step 2 has adopted a key; until then every call uses the bearer. After adoption, an
`unknown_key` anywhere only wakes this worker (§4.4).

Server side: §3 runs before the transaction; the transaction stores the key (`observed` in S1; from S2, `bound` with
`token_hash = NULL`, §5.6). The registering address and the addresses this device's bearer requests came from in the
previous 30 days (a small set S1 starts keeping per device, `device_addresses`) are logged with `device_key_bound`; a
registration from an address not in that set is marked `unusual` (H3).

### 5.3 Rotation (M8)

The policy's `device_key.rotate: true` (set by the server, §6.3): the same worker and the same calls, authenticated
by the current key instead of the bearer, and the same rules - step 1 names the key the server holds (current, or the
new one once committed), the POST is idempotent on the new SPKI, nothing is deleted while the server might name it.
The new key is `pending` until the first request it signs verifies; then the old one is `retired` and the phone
deletes its alias at the next step 1. No time-based rotation (Q5): the key never leaves the hardware.

### 5.4 After S2: a key the server no longer knows (M3)

A bound phone that gets `unknown_key` and whose step 1 also answers `unknown_key` (the server's database was restored
from before the key, or the phone was removed): the phone keeps its keys, stops syncing, and shows **"Ny oppsettkode"**
on the parent-code dialog (`ReEnrolDialog`); a new setup code enrols it afresh. It never falls back to a bearer and
never unlocks. Never on one answer (L1's code QA H1): three step-1 `unknown_key` answers over an hour, each at least ten
minutes after the last counted one (the L1 gate's finding 1: the SSE's retries made 12 strikes in a minute), and any
answer that proves the key known - step 1 naming it, or a 2xx/304 to a request it signed with no bearer - ends the
suspicion (the gate's finding 1: once signed calls worked again nothing asked step 1, and the old strikes stayed).

### 5.5 The policy

`device_key: {state, key_id?, spki_sha256?, rotate}` - `state` `none` (register, §5.2), `observed` (sign and keep the
bearer), `bound` (delete the sealed token; sign only) or `held` (S2 waits for the parent, §8.1: keep the bearer).
Pinned by `policy_json_keys_snapshot` and `PolicyResponseCompatTest`. The policy is only a nudge to run step 1;
step 1's answer is what the phone acts on.

### 5.6 Closing streams (M1)

`command_streams.close_device` runs at every change of a phone's credential: a key bound (registration from S2, or a
parent's confirmation), S2's commit for **every** phone it commits, a rotation's commit, a re-enrolment (already
[code]). A stream opened with the bearer can't outlive the bearer.

## 6. Loss, failure and the rules

### 6.1 Fail closed (the owner's rule)

- The phone never sends a request without a signature once it has adopted a key, never falls back to the bearer
  after `bound`, never makes a key without attestation, never makes a software key, never deletes a key on another
  call's answer.
- The server never accepts a chain that fails any check of §3, never reads a key's properties from the software list
  (the app's identity, which lives there, is never taken as a hardware claim), never lets a bound phone use a bearer,
  never moves a phone from `bound` back to bearer except by a new setup code.
- During the transition a refused registration leaves the phone **as it is today** (its bearer), with the orange
  line from S2: not a weakening, and never silent.

### 6.2 Key loss

- A factory reset (or a bootloader unlock, which wipes the phone) destroys the key: the phone is new, the parent
  makes a setup code. KeyMint binds keys to the root of trust, so a key can't outlive a change of boot state - which is
  also why step 2's reading at registration holds for the key's life.
- A key that stops working (Keystore broken): the phone goes silent (calls work, the cached rules hold), and
  **"Ny oppsettkode"** shows on `ReEnrolDialog` while the key is unusable. It never unlocks.
- An OS update upgrades the key transparently (KeyMint key upgrade); nothing to do.

### 6.3 A revocation after binding (Q4, decided)

The list is re-read daily against every stored chain's serials. A bound key whose chain is later revoked keeps
working (refusing it would lock the phone out with no way back if its keybox stays revoked); the phone gets the
orange line "Nøkkel trukket tilbake" and the security log a line. The server sets `rotate` once: an RKP phone gets a
new attestation key and comes back clean (the line goes); a factory keybox does not, and the line stays.

## 7. Contracts and tests

### 7.1 Shared: `server/testdata/device_key.json`

Byte-identical in `launcher/app/src/test/resources/`; both sides compare the copies; listed in the root `CLAUDE.md`
and in the workflows' path filters like the others. Rows (L7):

- `canonical`: `{key_id, t, nonce, method, encoded_path, encoded_query, content_encoding, body_b64}` -> the body hash
  and the exact signed bytes; rows with percent-encoded path and query segments, which the server must match raw.
- `signed`: rows with a public key and a signature made once by `scripts/device-key-vectors.py` from a throwaway key
  derived from a fake phrase (public data only, as `up-vapid-vectors.py`); a row with a wrong body hash.
- `proof`: `enroll` and `register` proofs (the challenge, the signed bytes, a signature by the same throwaway key).
- `clock`: `{vibb_time, sent_elapsed, received_elapsed, now_elapsed}` -> the offset and `t`; and
  `{stored_floor, wall_at_start, elapsed}` -> `sig_now`.
- `window`: `{t, sig_now}` -> `ok`/`stale`.
- `edge`: each request kind of §4.5 -> `authenticated`/`unauthenticated` and the answer.
- `answers`: every `Vibb-Auth` reason to the phone's action (§4.4).
- `enroll`: the exact bodies, the challenge derivation (`code` -> challenge) and every answer, in S1 and S2
  (`observed`, `refused` with each reason, the S2 `key_required`, a lost answer's repeat, `list_stale` with
  `retry_after_s`).
- `register`: `GET /key`'s answers, the POST's (`ok`, a repeat of the same SPKI, `exists`, each refusal word), the
  conflict report.
- `phone`: the registration flow's table, `{policy state, has token, local keys, step-1 answer, last call's answer}`
  -> action (adopt, register, report conflict, keep waiting, show "Ny oppsettkode"): a lost answer committed late, a
  lost answer never committed, parallel `unknown_key`s while a POST is in flight, a restore after S2, a rotation's lost
  answer and its commit, `held`.
- `policy`: the `device_key` rows (`none`, `observed`, `bound`, `held`, `rotate`).
- The launcher's `DeviceKeyContractTest` builds `canonical` and `proof` exactly, computes `clock`, maps every answer,
  runs `phone` through the worker's pure decision function and decodes the policy; the server's
  `device_key::tests::the_contract` verifies every `signed` and `proof` row, runs `clock`, `window` and `edge`, and
  produces `answers`, `enroll`, `register` and `policy` through the real routes.

### 7.2 Server-only: `server/testdata/attestation/`

- `roots/`: Google's root certificates (the pins' source) and the emulator test roots (for §7.3 only).
- Chains from the Kotlin library's test data (Apache-2.0, its notice kept): factory RSA, RKP, the new P-384 root,
  a P-384-over-P-256 chain, StrongBox, a revoked serial, a lost device, software, an unlocked device, a GrapheneOS
  device. **Never a capture of the family's phones** (an attestation certificate's serial is per device).
- `status.json` (a revoked and a suspended serial) and `rows.json` (each chain's verdict and RootOfTrust reading);
  `attestation::tests` runs every row.
- Server tests that are not about attestation insert a key directly (`TestApp` gains a signing helper with a key made
  at each run), so no test needs a real chain.

### 7.3 The emulator and debug builds (L8)

- What the gate images attest to (step 0, read on 2026-11-24, `docs/testing/emulator.md` §6bj): on both Android 14
  (`compat34`, google_apis) and Android 16 (`aosp36`) the chain is **three P-256 certificates**, remotely provisioned
  through rkpd from Google's backend for unregistered devices: the leaf ("Android Keystore Key"), an intermediate
  `O=TEE, CN=<32 hex>` (valid ~2 weeks) and a **self-signed root `CN=Droid Unregistered Device CA, O=Google Test LLC`**
  (valid ~10 weeks). **The root is not stable**: every `-wipe-data` brought another root key (three seen), so it can't
  be pinned in `testdata/attestation/roots/`. The provisioning-info extension (`1.3.6.1.4.1.11129.2.1.30`) is on the
  intermediate **and on the root** (the release check refuses it on the root). The KeyDescription: `attestationVersion`
  (and KeyMint) **300 on 14, 400 on 16**; **both security levels `Software`**, every tag in the software list (the
  hardware list empty); RootOfTrust unverified, unlocked, a zero boot key; `osPatchLevel` 2023-09 (14) / 2025-07 (16);
  the app `me.vibb.launcher.debug` with the debug signer. **No StrongBox** on either. A release server refuses both
  (`root`).
- The emulator gate runs a server built with the Cargo feature **`emulator_keys`** (not in `default`; built in design
  129b's step E2, its §7 the exact rules). From step 0 it
  must accept exactly what those chains are, and nothing a family phone's chain needs loosened: a root that is
  self-signed and named `CN=Droid Unregistered Device CA, O=Google Test LLC` (by its name and self-signature, not a
  pinned key - it changes with every wiped image), the provisioning info on the root as well as the intermediate, the
  attestation and KeyMint versions 300 and 400, the `Software` security level with the authorisation tags read from the
  software list (the hardware list empty), no StrongBox, `me.vibb.launcher.debug` with the digest in
  `LAUNCHER_DEBUG_SIGNATURE_CHECKSUM`, and it skips item 13's enforcement (an emulator is never locked/Verified: an
  unverified RootOfTrust, unlocked, a zero boot key). Its
  phones always show the step-2 orange line. `server-release.yml` and the Dockerfile never enable it, and
  `server-ci.yml`'s changes job fails if either names it.
- `server-ci.yml`'s build job also runs `cargo clippy --all-targets --features emulator_keys` and
  `cargo test --features emulator_keys device_key attestation` (L7), so the feature can't rot.
- A release server refuses an emulator (`software` or `root`).
- The launcher has **no debug path**: debug and release run the same key code; only the server decides.

## 8. Migration and the build

The server ships first. No phone-side compatibility (only emulators run Vibb yet); the server's real rows migrate.

| Step | What | Done when |
|---|---|---|
| S1 (server) | `attestation/`, the status-list fetch, a migration (next free number): `device_keys` (key id, device, SPKI, level, version, patch, boot state, boot key, locked, serials, root, address, `unusual`, state `observed`/`bound`/`held`/`pending`/`retired`, times), `device_addresses`, the signature clock's floor, the enrol SPKI on consumed codes; `GET /key`, the challenge, `POST /key`, the conflict report and the new enrol; the edge and auth layer of §4.5 beside the bearer; `Vibb-Time`; **observe mode**: keys stored `observed`, signatures verified and their failures logged, bearer kept; the PWA shows the verdict quietly (§9.1). The contract file. Step 2's reading and the GrapheneOS list (data only). | code QA |
| L1 (launcher) | `DeviceKey`, `DeviceCredential` and its sites (§2.1), the signer on every server client and the SSE, `serverOffset`, enrolment with a key, the registration worker (§5.2), rotation, the policy's `device_key`, "Ny oppsettkode" for a broken or unknown key, rkpd out of the sink. | code QA |
| Gate | Emulator, Android 14 and 16 (never 17: its image is broken; the code still targets 14-17) against an `emulator_keys` server: enrolment, migration, every refusal, a restart's `stale`, a lost answer (the server delays its commit past the phone's timeout), parallel workers during registration, the SSE closed at binding, the key before the first unlock, generation in the SINK shape with an empty pool. | the owner's OK to run it |
| Step 0 | The **Jelly Star** on S1 + L1: About This Phone shows its verdict in observe mode - root, level, version, patch, boot state, the revocation check, StrongBox or not, the sign time under parallel load (§10). Nothing can lock it out yet. | the owner, from the PWA |
| E1/E2/L2 | Design 129b: a new enrolment requires a proven key, the per-phone exception, `emulator_keys`. | code QA, then the gate |
| S2 (server) | Commit (§8.1); a bound phone's bearer refused (`device_old_token`); new enrolments key-only; registration requires the boot state (§3 item 13); step 2's orange line. DEPLOY.md: S2 can't be rolled back. | after step 0 is clean |
| Later | Drop the bearer code when no phone in the database is bearer-only. | the PWA shows none |

Bearer-only phones after S2 (a refused registration, a phone that never updated) keep working with their orange line:
never locked out, never silent (Q3, decided).

### 8.1 S2's commit (H3, M1, M3)

**Keys registered before L1 ships** (S1's code QA L9): S1 takes registrations from any client with the bearer, before
any real launcher makes a key - such a key can only be someone else's. S2 treats every key registered before the first
L1 release reached the phone as suspect: `held`, never bound automatically.

S2's migration turns an `observed` key into `bound` (and sets `token_hash = NULL`, and closes the phone's streams) only
when all hold: the key passes §3 item 13 (the boot state); the device has no recorded key conflict (`exists` answer or
conflict report); the registration was not `unusual`. Any other observed key becomes **`held`**: the phone keeps its
bearer, and About This Phone and Attention show "Bekreft maskinvarenøkkel" with two buttons - **Bekreft** (binds it,
logged `device_key_confirmed`) and the existing new setup code. A held key whose boot state fails can't be confirmed;
only a new setup code (and a phone that passes) binds that phone.

**A one-way door** (M3): after S2 the database holds no bearer for a bound phone. Rolling the server back to a pre-S2
release leaves those phones without a credential the old server knows; restoring a backup from before S2 (or before a
phone's binding) leaves bound phones the server doesn't know. Both end at §5.4: every such phone needs a new setup code.
DEPLOY.md's backup and update sections say so, and S2's release notes repeat it.

## 9. Step 2: the system it booted

Read from the key's stored RootOfTrust (§3 item 12):

| Attestation | Shown |
|---|---|
| `Verified` and `deviceLocked` (the maker's signed system, bootloader locked) | nothing |
| `SelfSigned`, `deviceLocked`, `verifiedBootKey` on the pinned GrapheneOS list | nothing (About This Phone's quiet value "GrapheneOS") |
| anything else - `Unverified`, `Failed`, `SelfSigned` with another key, unlocked - and every `emulator_keys` phone | the orange line |

From S2 the same reading gates registration (§3 item 13): a phone that shows the line can't bind a key, and stays on
its bearer with the line.

- **The GrapheneOS list**: `server/src/attestation/grapheneos_boot_keys.json`, compiled in. Source: GrapheneOS's
  signed `https://grapheneos.org/attestation.json` (21 SHA-256 fingerprints on 2026-11-24, one per supported model,
  with a `timestamp`) and its OpenSSH signature `attestation.json.sig`, checked against
  `https://grapheneos.org/allowed_signers` [doc]; these are the fingerprints the Auditor app pins, cross-checked
  against Auditor's source whenever the list changes. `scripts/grapheneos-boot-keys.py` fetches both, verifies with
  `ssh-keygen -Y verify` against the signer key pinned in the script, refuses a `timestamp` older than the committed
  one and writes the file; `--selftest` (no network) verifies a vendored sample signed by a throwaway key, run by
  `server-ci.yml`'s changes job. PLAN chore at every server release, as design 76's pins. A model GrapheneOS drops
  leaves the list, and its phones get the line - rightly: it no longer gets updates.
- `osPatchLevel` is a quiet value on About This Phone; no warning on its age (Q6, decided).

### 9.1 Copy (PWA, nb / en)

One orange line each, on About This Phone, on Attention and as the Devices list's badge, as the other orange lines;
the explanation behind (i) alone. **In S1 nothing here is orange** but `list_stale` (L6): a phone's key state is a
quiet value on About This Phone ("Eldre innlogging", "Maskinvarenøkkel", "Nøkkel avvist (reason)"), since the parent
can't act on it yet. From S2 the lines below are orange.

| Key | nb | en | (i) nb | (i) en |
|---|---|---|---|---|
| `system_unknown` | Ukjent eller ulåst system | Unknown or unlocked system | Telefonen startet ikke produsentens system eller GrapheneOS med låst oppstart. Den kan være endret. Vet du ikke hvorfor: tilbakestill den. | The phone didn't start its maker's system or GrapheneOS with a locked bootloader. It may have been changed. If you don't know why, reset it. |
| `key_none` (from S2) | Eldre innlogging | Older sign-in | Telefonen har ikke laget sin maskinvarenøkkel. Oppdater Vibb One på telefonen. | The phone hasn't made its hardware key. Update Vibb One on the phone. |
| `key_refused` (from S2) | Nøkkel avvist | Key refused | Telefonen kunne ikke bevise at nøkkelen ligger i maskinvaren ({reason}). Den bruker eldre innlogging. | The phone couldn't prove its key is in hardware ({reason}). It uses the older sign-in. |
| `key_held` | Bekreft maskinvarenøkkel | Confirm hardware key | Nøkkelen ble registrert fra en uvanlig adresse eller etter en konflikt. Kjenner du ikke igjen dette: lag en ny oppsettkode. | The key was registered from an unusual address or after a conflict. If you don't recognise this, make a new setup code. |
| `key_conflict` | Annen nøkkel registrert | Another key registered | Noen registrerte en nøkkel for telefonen før den selv gjorde det. Lag en ny oppsettkode. | Someone registered a key for the phone before it did. Make a new setup code. |
| `key_revoked` | Nøkkel trukket tilbake | Key revoked | Google har trukket tilbake sertifikatet bak telefonens nøkkel. Telefonen virker som før, og Vibb prøver en ny nøkkel selv. Forsvinner ikke linjen, kan bare en oppdatering fra produsenten fikse det. | Google has revoked the certificate behind the phone's key. The phone works as before, and Vibb tries a new key by itself. If the line stays, only an update from the maker can fix it. |
| `old_token` | Gammel innlogging brukt | Old sign-in used | Noen brukte telefonens gamle innlogging. Lag en ny oppsettkode. | Someone used the phone's old sign-in. Make a new setup code. |
| `list_stale` (Settings) | Googles nøkkelliste er utdatert | Google's key list is out of date | Serveren har ikke nådd Googles liste på over en uke. Nye telefoner kan ikke kobles til før den gjør det. | The server hasn't reached Google's list for over a week. New phones can't be set up until it does. |

`{reason}`: `chain`/`root` "ukjent utsteder" / "unknown issuer", `revoked` "tilbakekalt" / "revoked", `software`
"bare programvare" / "software only", `app` "feil app-signatur" / "wrong app signature", `boot` "ulåst eller ukjent
system" / "unlocked or unknown system", `keys_unavailable` "venter på nøkler fra Google" / "waiting for keys from
Google", the rest "ugyldig" / "invalid". The kid sees nothing; the phone's own enrolment screen gets one line on a
refusal: "Telefonen kan ikke lage en sikker nøkkel" / "This phone can't make a secure key" (since 129b §5.4 with the
reason and "Spør en forelder").

Security log: `device_key_bound` ("Maskinvarenøkkel registrert" / "Hardware key registered", with the address and
`unusual`), `device_key_refused` (with the reason), `device_key_conflict`, `device_key_confirmed`, `device_old_token`,
and `device_auth_failed`'s new reasons.

## 10. Android 14 to 17, and the Jelly Star

- **Versions** [doc]: the KeyDescription's `attestationVersion` is the KeyMint version, not Android's: 300 is KeyMint
  3, 400 adds `moduleHash`, 500 adds ML-DSA; which of 15, 16 and 17 ship 400 and 500 is read off the Android 14 and 16
  gate images and, for 15 and 17, from their documentation [assumed]. A phone that **upgraded** keeps its vendor's frozen KeyMint, so an Android 16 phone launched on 13
  may report 200 or even Keymaster 4.1 (4) - hence `>= 3` in §3. ID attestation is used on no version.
- **Provisioning** [doc]: phones launched with Android 16 use only remote key provisioning (short-lived certificates;
  chains under the new P-384 root from 2026-02-01; the pool needs the network now and then - §2's `keys_unavailable`
  and rkpd outside the sink); older phones may carry a factory keybox under the RSA key.
- **StrongBox** is optional on every version; the TEE is required on all four.
- **Locked use**: a key without user auth or `UnlockedDeviceRequired` works while the screen is locked [doc]; before
  the first unlock after a boot the sealed token is unreadable today anyway (CE prefs); whether the key itself signs
  before the first unlock is checked in the gate [assumed].
- **Step 0 also reads, from S1's code QA**: whether the Jelly Star's chain encodes each issuer exactly as the next
  certificate's subject (S1 compares no names - the signatures bind the chain, L2), and in which list it puts the app
  (tag 709: S1 reads the software list and falls back to the hardware list, L3).
- **The Jelly Star** (MediaTek, Android 16 by Unihertz's update, launched on 13) **must be checked on the real phone
  before S2** [assumed on every point]: that its chain ends at a Google root at all, its security level (TEE;
  StrongBox likely absent), its `attestationVersion`, that no certificate of its chain is revoked (leaked MediaTek
  keyboxes are among the commonest revocations), its boot state (`Verified` and locked as shipped - S2 requires it)
  and the sign time under parallel load. Step 0 does it with the release launcher in observe mode - no debug APK on
  the kid's phone.

## 11. Decisions and open questions

Decided by the owner on 2026-11-24 (revision 1's recommendations, taken as they stood):

- **Q1. Commit at registration**, with S1's observe mode as the one probation for the whole fleet.
- **Q2. StrongBox when the phone has it**, with a measured ceiling (about 150 ms at p95 under parallel load); above
  it the TEE, chosen once per phone at key creation and shown as a quiet value.
- **Q3. Bearer-only phones after S2 keep working** with the orange line; the bearer code goes when the database has
  none.
- **Q4. A revocation after binding keeps the phone working**, with the orange line and one `rotate` (§6.3).
- **Q5. No periodic re-attestation**; rotation on demand only.
- **Q6. The security patch is a quiet value**, never a warning.
- **Q7. No switch for a phone without a Google root**; only as its own design if the Jelly Star needs it.
- **Q8. `LAUNCHER_SIGNATURE_CHECKSUM` is the app check** (decoded, §3 item 10); a server without it refuses all keys.

Decided by the owner on 2026-11-24, new in revision 2:

- **Q9. rkpd outside the kid's VPN sink** (§2, M7) - decided: excluded. Without it, a remote-provisioning-only phone in the SINK shape
  (no data, "Ingen data" abroad) can't make its key until it is on a network where the sink is off. With it, Google's
  provisioning app reaches the network in every shape: a few kilobytes now and then, and no path for the kid.
  Taken.
- **Q10. A held key at S2** (§8.1) - decided: Bekreft binds it. the parent's Bekreft binds it. The alternative is to require a new setup code for
  every held phone (stricter, more work for the parent). Taken: Bekreft, logged; a held key with a failing boot state can't be
  confirmed.
