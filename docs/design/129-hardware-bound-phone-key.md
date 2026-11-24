# Design 129: A hardware-bound phone key

Status: design, revision 1, 2026-11-24 (branch `hw-key`, from main 2d08708eb). Server and launcher; the music app is
untouched (it never holds the device token: its server calls go through the launcher's bridge). Owner-approved
direction: step 1, a non-exportable Keystore key with key attestation replaces the bearer; step 2, the server reads
the verified-boot state from that attestation.

Marks: **[code]** read in the code at 2d08708eb; **[doc]** read in Android's or GrapheneOS's documentation on
2026-11-24 (developer.android.com "Verify hardware-backed key pairs with key attestation", source.android.com
"Key and ID attestation", grapheneos.org "Attestation compatibility guide"); **[assumed]** not verified, to be
checked by step 0 (§10) or the emulator gate.

## 0. Short answer

- At enrolment the launcher makes an **EC P-256 signing key in AndroidKeyStore** (StrongBox when the phone has it,
  the TEE otherwise), attested with a challenge the server fixes. The server checks the chain up to **Google's two
  attestation root keys** (the RSA-4096 key and the ECDSA P-384 "Key Attestation CA1" that signs chains from
  2026-02-01) and against **Google's revocation status list**, checks the key's properties and that it belongs to the
  release-signed `me.vibb.launcher`, and binds the public key to the phone.
- From then on **every request to `/api/devices/*` carries a signature** over the method, the path, a hash of the
  body, a time on the server's clock and a random nonce (`Vibb-Signature`). The server keeps a five-minute replay
  cache. No challenge round trip; the SSE stream signs its connect like any request; the tailnet path is signed the
  same way. **A bound phone's bearer no longer authenticates**, and a bearer sent for a bound phone is a security
  event.
- **Transition**: phones enrolled today register a key with their bearer; the bearer works until the registration is
  committed, then never again for that phone (only a new setup code - the parent's act, logged - starts over). The
  first server release only **observes** (verifies, stores, shows the verdict, keeps the bearer), so the Jelly Star's
  real attestation is seen before anything can lock it out (§8).
- **Fail closed**: an attestation that does not verify is refused - never a software key, never "accept anyway".
  During the transition a refused phone stays on its bearer with one orange line; once new enrolments require a key,
  a phone that can't attest can't be enrolled.
- **Step 2**: the attestation's RootOfTrust. `Verified` with a locked bootloader: nothing shown. `SelfSigned` with a
  verified-boot key on the pinned GrapheneOS list: nothing shown. Anything else: one orange line, **"Ukjent eller ulåst
  system" / "Unknown or unlocked system"**, the why behind (i).

## 1. Today [code]

- `POST /api/devices/enroll {enrollment_code}` answers `{device_id, device_token}`; the server keeps
  `SHA-256(token)` in `devices.token_hash` and an in-memory `TokenIndex`. `security::require_device_token` guards
  every other device route (`device_routes.rs`), with the per-client failure bucket and `device_auth_failed`.
- The launcher seals the token at rest (`secrets_v1`, design r10) and sends it only to the server's own origin
  (`ServerAuth.bearerInterceptor`, L8; `CommandListenerService` adds it to the SSE request by hand). Every server
  client goes through `ServerTransport.toServer` (origin fence, design 76's pin, the tailnet path).
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
  `FEATURE_STRONGBOX_KEYSTORE`; a `StrongBoxUnavailableException` makes it once more in the TEE. Never a key without
  attestation, never a software fallback.
- Alias `device_key_<gen>`; at most two exist (the bound one and one being registered); a refused or replaced key is
  deleted. No ID attestation (`setDevicePropertiesAttestationIncluded` stays off: the server needs no IMEI, serial or
  model, and privacy agrees).
- `getCertificateChain(alias)` gives the chain, leaf first; it goes to the server as Base64 DER.
- The key id (`<device_id>.<gen>`) and the server's verdict are plain prefs (not secrets). The sealed device token is
  deleted when the policy says the key is bound (§5.4).
- Sign: `Signature.getInstance("SHA256withECDSA")` (DER output), on a background thread, as every server call is.

## 3. What the server checks (registration)

A new module `server/src/attestation/` (no new crate: `der` 0.7 and `ring` are already in `Cargo.lock` [code]; not
`rustls-webpki`, whose TLS validity and usage rules are wrong for factory attestation chains). It follows Google's
verification steps [doc] and ports the rules of Google's Kotlin verifier (`github.com/android/keyattestation`,
Apache-2.0), whose test chains become our vectors (§7).

1. **Shape**: 2 to 6 certificates, each at most 4 KiB DER; the enrol body limit rises from 4 KiB to 32 KiB.
2. **Chain**: each certificate signed by the next (RSA PKCS#1 SHA-256, ECDSA P-256/P-384); the root's
   SubjectPublicKeyInfo is one of the **pinned root keys**, compiled in (`attestation/roots.rs`, each pin a SHA-256 of
   the SPKI, computed by a test from the certificates in `server/testdata/attestation/roots/`):
   - the RSA-4096 key with subject `SERIALNUMBER=f92009e853b6b045` (one key behind the 2016, 2019, 2021 and 2022
     certificates - the 2016 one expired 2026-05-24, which is why we pin the key, not a certificate) [doc];
   - the ECDSA P-384 "Key Attestation CA1" (Google LLC, valid 2025-07-17 to 2035-07-15), signing chains from
     2026-02-01 [doc].
   Google's JSON root list (`android.googleapis.com/attestation/root`) is never fetched at run time: a new root comes
   in a server release, by design 76's rule for pins (in as soon as Google publishes it). PLAN chore at every server
   release: compare Google's page with the pins.
3. **Revocation**: no certificate's serial (lowercase hex, leading zeros stripped) is `REVOKED` or `SUSPENDED` in
   `https://android.googleapis.com/attestation/status` [doc]. The server fetches it in the background, honouring
   `Cache-Control` (at least hourly, at most daily) - a plain GET with no device data - and keeps the last good copy
   on disk. **A list older than 7 days stops registrations** (503 with `retry_after_s`; the phone retries) and puts one
   orange line on Settings (§9.1); bound keys keep working (§6.3).
4. **Validity**: by the Kotlin library's rule [doc]: remotely provisioned (RKP) certificates are checked against the
   server's time; factory chains under the RSA key are not refused for an expired certificate.
5. **The extension**: the KeyDescription (OID `1.3.6.1.4.1.11129.2.1.17`) is read from the **first certificate from
   the root side** that has it; when the provisioning-info extension (`1.3.6.1.4.1.11129.2.1.30`) is there, the
   KeyDescription must be in the very next certificate [doc]. A later copy is ignored. It must be the leaf's.
6. `attestationVersion >= 3` (Keymaster 4.0 and every KeyMint: 100 to 500; an unknown higher version parses - new
   tags are skipped, never guessed).
7. `attestationSecurityLevel` and `keyMintSecurityLevel` equal, and `TrustedEnvironment` (1) or `StrongBox` (2).
   `Software` (0) is refused (§7.3 for the emulator).
8. `attestationChallenge` equals the one the server expects (§5).
9. In the **hardware-enforced** list: purpose SIGN only, algorithm EC, curve P-256, digest SHA-256 among the digests,
   origin GENERATED, `noAuthRequired`. A property only in the software list does not count.
10. `attestationApplicationId` (tag 709): exactly one package, `me.vibb.launcher`, and one signature digest equal to
    `LAUNCHER_SIGNATURE_CHECKSUM` (the QR's checksum is that SHA-256 [code]). **A server without it refuses every
    registration** (a startup warning and the orange line on Settings).
11. The leaf's public key verifies the request's `proof` (§5).
12. Kept per key: the SPKI, the security level, `attestationVersion`, `osPatchLevel`, `verifiedBootState`,
    `verifiedBootKey`, `deviceLocked`, the chain's serials and which root (for §6.3 and step 2).

Any failure is a refusal with one reason word (`chain`, `root`, `revoked`, `expired`, `software`, `properties`,
`app`, `challenge`, `proof`, `list_stale`) - never a weaker acceptance.

## 4. Every request signed

### 4.1 The choice

| Option | Why not |
|---|---|
| A challenge per request | A round trip per call on mobile; parallel calls need a pool of challenges. |
| A signed challenge buys a short-lived session token | A bearer again, for its life: the gap shrinks but stays. |
| Mutual TLS with the Keystore key | Breaks behind a TLS-terminating reverse proxy (most installs) and on the tailnet path (plain http over WireGuard). |
| **A per-request signature with time + nonce** (chosen) | No extra round trip; a lifted signature replays nothing (nonce cache) and covers only that exact request (method, path, body). Works on every path and through proxies. |

Cost: one ECDSA sign per request. In the TEE that is a binder call and a world switch, milliseconds [assumed];
StrongBox is a separate secure element and much slower, tens to hundreds of ms [assumed]. A sync is a few dozen
requests, the backstop half-hourly: next to the radio the CPU cost is noise, and only StrongBox's latency could
matter (step 0 measures it; Q2). On the server one P-256 verify (ring), cheap next to the request itself.

### 4.2 The canonical form

`Vibb-Signature: v1;k=<key id>;t=<ms>;n=<nonce>;s=<sig>`, the signature (Base64url, DER ECDSA) over the UTF-8 bytes:

```
vibb-req-v1
<key id>
<t: decimal ms on the server's clock>
<n: 16 random bytes, Base64url without padding>
<METHOD>
<path from "/api/devices" on, and "?" + the raw query when there is one>
<Content-Encoding as sent, or empty>
<hex SHA-256 of the body bytes as sent - of nothing for no body>
```

- The path is cut at `/api/devices`, so a server URL with a path prefix that a reverse proxy strips signs the same.
  No host: the key is known to one server only. But a signature sent elsewhere could be replayed here, so the signer
  runs **only for the server's own origin** (`sameOrigin`, L8), as the bearer does today.
- The body is hashed as it travels (a gzip'd status report compressed), so the server verifies **before** it inflates
  anything; the auth layer buffers at most `DEVICE_BODY_LIMIT` (512 KiB) to hash it, which every device route
  already caps.
- The signer is an OkHttp **network** interceptor: each attempt (OkHttp's own retry included) is signed afresh, so a
  retry is never a replay. Redirects are never followed (design 76).

### 4.3 Time, replay and the clock

- **The time is the server's.** The phone keeps `serverOffset` = the server's `Vibb-Time` (ms; a new header on every
  device answer, 401s included, set where `clock_skew::mark_answers` runs) minus `elapsedRealtime` at the round trip's
  middle; `t` = elapsedRealtime + offset. Not `TrustedClock`: that clock refuses a server marked `Vibb-Clock: off` and
  also takes GNSS or a carried time, while the signature needs exactly the server's own clock, right or wrong. Before
  any answer in this boot: `TrustedClock`'s time, else the wall clock - and a `stale` refusal corrects it.
  `TrustedClockScanTest` gets the signer's one wall-clock read.
- **Window**: accepted when `now - 300 s <= t <= now + 30 s` (a 512 KiB upload on a poor link may take minutes; the
  future side covers the offset's rounding).
- **Replay**: an in-memory set of (key, nonce) per device for the window; a nonce seen again = `replay`. Bounded per
  device (4096; beyond it a 429). A restart forgets the set, so **a `t` before the server process started is `stale`**:
  the phone re-signs, and nothing signed before a restart can be replayed after it.
- The SSE stream signs its connect; the stream then lives as today and is closed by the server when the key goes
  (`command_streams.close_device`, already called at enrolment [code]).

### 4.4 Answers

A refused request is a 401 with `Vibb-Auth: <reason>` and `Vibb-Time`:

| Reason | Phone |
|---|---|
| `stale` | take the offset from `Vibb-Time`, re-sign, one retry |
| `replay` | one retry with a new nonce (only a bug or an attacker gets here twice) |
| `unknown_key` | the key is not this server's (re-enrolled, removed): silent, as an unenrolled phone is today; "Ny oppsettkode" |
| `bad_sig`, `missing` | silent |
| `key_required` | a bearer for a bound phone, or (from S2) a bearer for a new enrolment: silent |

Every refusal but `stale` counts against the client's failure bucket and writes `device_auth_failed` with the reason,
as today. **A valid bearer for a bound phone** also writes `device_old_token` and puts the phone on Attention: the old
token is in someone's hands.

## 5. The flows

### 5.1 A new enrolment

`POST /api/devices/enroll {enrollment_code, chain, proof}`.

- The attestation challenge is `SHA-256("vibb-enroll-v1" || 0x00 || normalised code)`: the phone knows it before it
  calls, so enrolment stays one round trip; it ties the attestation to this code (freshness, not secrecy - the code is
  the credential, as today).
- `proof` = the new key's signature over `vibb-key-v1\n<challenge Base64url>\nenroll`.
- The server checks §3 inside the code's transaction; the answer is `{device_id, key_id}` - **no token**.
- The old body (code only): until S2 the server still answers with a bearer (phones run today's launcher until they
  update); from S2 it is a 400 `key_required`.

### 5.2 A phone enrolled today

1. `GET /api/devices/key/challenge` (bearer) -> `{challenge, expires_in_s: 600}`: 32 random bytes, one per device,
   used once.
2. The phone makes `device_key_<gen>` with it, then `POST /api/devices/key {challenge, chain, proof}` with its bearer
   (`proof` as above, `register` in place of `enroll`).
3. The server checks §3. **First key wins**: a phone that already has a key, observed or bound, gets 409 `exists`
   (only a new setup code clears it). The registering address goes into `device_key_bound` in the security log, so a
   thief who registers first is visible, and the real phone's 409 puts the phone on Attention.
4. In observe mode (S1) the key is stored `observed` and the bearer stays. From S2 it is committed `bound` in the
   same transaction that sets `token_hash = NULL`; S2 also turns every `observed` key into `bound` the same way.

A lost answer: the phone, holding a key the server may or may not have, signs its next request; a 2xx means
registered, `unknown_key` means not - it deletes the key and goes back to step 1. That is the only time the phone uses
the bearer after making a key, and only while the server has no key for it.

### 5.3 Rotation

The policy's `device_key.rotate: true` (set by the server; §6.3, Q5): the same two calls, authenticated by the current
key instead of the bearer. The new key is `pending` until the first request it signs verifies; then the old one is
`retired` and its alias deleted. No time-based rotation: the key never leaves the hardware.

### 5.4 The policy

`device_key: {state, rotate}` - `state` `none` (register, §5.2), `observed` (sign and keep the bearer) or `bound`
(delete the sealed token; sign only). Pinned by `policy_json_keys_snapshot` and `PolicyResponseCompatTest`.

## 6. Loss, failure and the rules

### 6.1 Fail closed (the owner's rule)

- The phone never sends a request without a signature once it holds a registered key, never falls back to the bearer
  after `bound`, never makes a key without attestation, never makes a software key.
- The server never accepts a chain that fails any check of §3, never reads a key's properties from the software list,
  never lets a bound phone use a bearer, never moves a phone from `bound` back to bearer except by a new setup code.
- During the transition a refused registration leaves the phone **as it is today** (its bearer), with the orange
  line: not a weakening, and never silent.

### 6.2 Key loss

- A factory reset (or a bootloader unlock, which wipes the phone) destroys the key: the phone is new, the parent
  makes a setup code. KeyMint binds keys to the root of trust, so a key can't outlive a change of boot state - which is
  also why step 2's reading at registration holds for the key's life.
- A key that stops working (Keystore broken, as `secrets_v1` can break): the phone goes silent (calls work, the cached
  rules hold), and the existing **"Ny oppsettkode"** on the parent-code dialog (`ReEnrolDialog`) also shows while the
  key is unusable: the parent types a new setup code, the phone enrols with a new key. It never unlocks.
- An OS update upgrades the key transparently (KeyMint key upgrade); nothing to do.

### 6.3 A revocation after binding

The list is re-read daily against every stored chain's serials. A bound key whose chain is later revoked keeps
working (it was attested while the list was clean; refusing it would lock the phone out with no way back if its keybox
stays revoked), but the phone gets the orange line "Nøkkel trukket tilbake" and the security log a line. The server
sets `rotate` once: an RKP phone gets a new attestation key and comes back clean; a factory keybox does not. Q4.

## 7. Contracts and tests

### 7.1 Shared: `server/testdata/device_key.json`

Byte-identical in `launcher/app/src/test/resources/`; both sides compare the copies; listed in the root `CLAUDE.md`
and in the workflows' path filters like the others.

- `canonical`: rows `{key_id, t, nonce, method, path, query, content_encoding, body_b64}` -> the exact signed bytes;
  `signed`: rows with a public key and a signature made once by `scripts/device-key-vectors.py` from a throwaway key
  derived from a fake phrase (public data only, as `up-vapid-vectors.py`).
- `window`: `{t, now, started}` -> `ok`/`stale`.
- `answers`: every `Vibb-Auth` reason to the phone's action (§4.4).
- `enroll`, `register`: the exact bodies, the challenge derivation (`code` -> challenge) and every answer (`ok`,
  `exists`, each refusal word, `list_stale` with `retry_after_s`).
- `policy`: the `device_key` rows (`none`, `observed`, `bound`, `rotate`).
- The launcher's `DeviceKeyContractTest` builds `canonical` exactly, maps every answer and decodes the policy; the
  server's `device_key::tests::the_contract` verifies every `signed` row, runs `window` and produces `answers`,
  `enroll`, `register` and `policy` through the real routes.

### 7.2 Server-only: `server/testdata/attestation/`

- `roots/`: Google's root certificates (the pins' source) and AOSP's software attestation roots (for §7.3 only).
- Chains from the Kotlin library's test data (Apache-2.0, its notice kept): factory RSA, RKP, the new P-384 root,
  StrongBox, a revoked serial, software, an unlocked device, a GrapheneOS device. **Never a capture of the family's
  phones** (an attestation certificate's serial is per device).
- `status.json` (a revoked and a suspended serial) and `rows.json` (each chain's verdict and RootOfTrust reading);
  `attestation::tests` runs every row.
- Server tests that are not about attestation insert a key directly (`TestApp` gains a signing helper with a key made
  at each run), so no test needs a real chain.

### 7.3 The emulator and debug builds

- An emulator's KeyMint is software: its chain ends in AOSP's published software root, at security level `Software`
  [doc]. A release server refuses it (`software`).
- The emulator gate runs a server built with the Cargo feature **`emulator_keys`** (not in `default`): it adds the
  software roots, accepts `Software`, and accepts `me.vibb.launcher.debug` with the digest in
  `LAUNCHER_DEBUG_SIGNATURE_CHECKSUM`. Its phones always show the step-2 orange line. `server-release.yml` and the
  Dockerfile never enable it, and `server-ci.yml`'s changes job fails if either names it.
- The launcher has **no debug path**: debug and release run the same key code; only the server decides.

## 8. Migration and the build

The server ships first. No phone-side compatibility (only emulators run Vibb yet); the server's real rows migrate.

| Step | What | Done when |
|---|---|---|
| S1 (server) | `attestation/`, the status-list fetch, a migration (next free number): `device_keys` (device, gen, SPKI, level, version, patch, boot state, boot key, locked, serials, root, state `observed`/`bound`/`pending`/`retired`, times); the challenge, key and enrol routes; the signature layer beside `require_device_token` (a signature, else the bearer); `Vibb-Time`; **observe mode**: keys stored `observed`, signatures verified and their failures logged, bearer kept; the PWA shows the verdict (§9). The contract file. Step 2's reading and the GrapheneOS list (data only). | code QA |
| L1 (launcher) | `DeviceKey`, the signer on every server client and the SSE (`ServerTransportTest`'s source scan pins it as it pins `bearerInterceptor(` today), `serverOffset`, enrolment with a key, §5.2 after a sync, the policy's `device_key`, "Ny oppsettkode" for a broken key. | code QA |
| Gate | Emulator, Android 14 and 17 (lowest and highest) against an `emulator_keys` server: enrolment, migration, every refusal, a restart's `stale`, a lost answer, the SSE, the key before the first unlock. | the owner's OK to run it |
| Step 0 | The **Jelly Star** on S1 + L1: About This Phone shows its verdict in observe mode - root, level, version, patch, boot state, the revocation check, StrongBox or not, the sign time (§10). Nothing can lock it out yet. | the owner, from the PWA |
| S2 (server) | Commit: `observed` keys become `bound` and `token_hash` goes; a bound phone's bearer refused (`device_old_token`); new enrolments key-only; step 2's orange line. | after step 0 is clean |
| Later | Drop the bearer code when no phone in the database is bearer-only. | the PWA shows none |

Bearer-only phones after S2 (a refused registration, a phone that never updated) keep working with their orange line:
never locked out, never silent. Q3.

## 9. Step 2: the system it booted

Read from the key's stored RootOfTrust (§3 item 12):

| Attestation | Shown |
|---|---|
| `Verified` and `deviceLocked` (the maker's signed system, bootloader locked) | nothing |
| `SelfSigned`, `deviceLocked`, `verifiedBootKey` on the pinned GrapheneOS list | nothing (About This Phone's quiet value "GrapheneOS") |
| anything else - `Unverified`, `Failed`, `SelfSigned` with another key, unlocked - and every `emulator_keys` phone | the orange line |

- **The GrapheneOS list**: `server/src/attestation/grapheneos_boot_keys.json`, compiled in. Source: GrapheneOS's
  signed `https://grapheneos.org/attestation.json` (21 SHA-256 fingerprints on 2026-11-24, one per supported model,
  with a `timestamp`) and its OpenSSH signature `attestation.json.sig`, checked against
  `https://grapheneos.org/allowed_signers` [doc]; these are the fingerprints the Auditor app pins, cross-checked
  against Auditor's source whenever the list changes. `scripts/grapheneos-boot-keys.py` fetches both, verifies with
  `ssh-keygen -Y verify` against the signer key pinned in the script, refuses a `timestamp` older than the committed
  one and writes the file; `--selftest` (no network) verifies a vendored sample signed by a throwaway key, run by
  `server-ci.yml`'s changes job. PLAN chore at every server release, as design 76's pins. A model GrapheneOS drops
  leaves the list, and its phones get the line - rightly: it no longer gets updates.
- `osPatchLevel` is shown as About This Phone's quiet value; no warning on its age (Q6).

### 9.1 Copy (PWA, nb / en)

One orange line each, on About This Phone, on Attention and as the Devices list's badge, as the other orange lines;
the explanation behind (i) alone.

| Key | nb | en | (i) nb | (i) en |
|---|---|---|---|---|
| `system_unknown` | Ukjent eller ulåst system | Unknown or unlocked system | Telefonen startet ikke produsentens system eller GrapheneOS med låst oppstart. Den kan være endret. Vet du ikke hvorfor: tilbakestill den. | The phone didn't start its maker's system or GrapheneOS with a locked bootloader. It may have been changed. If you don't know why, reset it. |
| `key_none` | Eldre innlogging | Older sign-in | Telefonen har ikke laget sin maskinvarenøkkel ennå. Den gjør det selv når Vibb One er oppdatert. | The phone hasn't made its hardware key yet. It does once Vibb One is updated. |
| `key_refused` | Nøkkel avvist | Key refused | Telefonen kunne ikke bevise at nøkkelen ligger i maskinvaren ({reason}). Den bruker eldre innlogging. | The phone couldn't prove its key is in hardware ({reason}). It uses the older sign-in. |
| `key_revoked` | Nøkkel trukket tilbake | Key revoked | Google har trukket tilbake sertifikatet bak telefonens nøkkel. | Google has revoked the certificate behind the phone's key. |
| `old_token` | Gammel innlogging brukt | Old sign-in used | Noen brukte telefonens gamle innlogging. Lag en ny oppsettkode. | Someone used the phone's old sign-in. Make a new setup code. |
| `list_stale` (Settings) | Googles nøkkelliste er utdatert | Google's key list is out of date | Serveren har ikke nådd Googles liste på over en uke. Nye telefoner kan ikke kobles til før den gjør det. | The server hasn't reached Google's list for over a week. New phones can't be set up until it does. |

`{reason}`: `chain`/`root` "ukjent utsteder" / "unknown issuer", `revoked` "tilbakekalt" / "revoked", `software`
"bare programvare" / "software only", `app` "feil app-signatur" / "wrong app signature", the rest "ugyldig" /
"invalid". The kid sees nothing; the phone's own enrolment screen gets one line on a refusal: "Telefonen kan ikke lage
en sikker nøkkel" / "This phone can't make a secure key".

Security log: `device_key_bound` ("Maskinvarenøkkel registrert" / "Hardware key registered", with the address),
`device_key_refused` (with the reason), `device_old_token`, and `device_auth_failed`'s new reasons.

## 10. Android 14 to 17, and the Jelly Star

- **Versions** [doc]: the KeyDescription's `attestationVersion` is the KeyMint version, not Android's: 300 is KeyMint
  3 (Android 14 brought `attestationIdSecondImei` with it), 400 adds `moduleHash`, 500 adds ML-DSA; which of 15, 16
  and 17 ship 400 and 500 is read off the emulators in the gate [assumed]. A phone that **upgraded** keeps its vendor's
  frozen KeyMint, so an Android 16 phone launched on 13 may report 200 or even Keymaster 4.1 (4) - hence `>= 3` in §3.
  ID attestation is used on no version.
- **Provisioning** [doc]: phones launched with Android 16 use only remote key provisioning (short-lived certificates;
  chains under the new P-384 root from 2026-02-01; the pool needs the network now and then - a generation that fails
  for want of keys is retried, never downgraded); older phones may carry a factory keybox under the RSA key.
- **StrongBox** is optional on every version; the TEE is required on all four.
- **Locked use**: a key without user auth or `UnlockedDeviceRequired` works while the screen is locked [doc]; before
  the first unlock after a boot the sealed token is unreadable today anyway (CE prefs), so nothing changes there;
  whether the key itself signs before the first unlock is checked in the gate [assumed].
- **The Jelly Star** (MediaTek, Android 16 by Unihertz's update, launched on 13) **must be checked on the real phone
  before S2** [assumed on every point]: that its chain ends at a Google root at all (a GMS-certified phone should; an
  uncertified one has none), its security level (TEE; StrongBox likely absent), its `attestationVersion`, that no
  certificate of its chain is revoked (leaked MediaTek keyboxes are among the commonest revocations), its boot state
  (`Verified` and locked as shipped) and the sign time. Step 0 does it with the release launcher in observe mode - no
  debug APK on the kid's phone.

## 11. Open questions

- **Q1. Commit at registration, or after a probation?** S2 commits at registration (the owner's direction: bearer
  until the key is registered). A probation (24 h of clean signatures before the bearer goes) would guard against a
  key that registers and then breaks; "Ny oppsettkode" already covers that. **Recommendation: commit at once; S1's
  observe mode is the probation, once, for the whole fleet.**
- **Q2. StrongBox or the TEE when both exist?** The brief says StrongBox. If the gate or step 0 shows sign times over
  ~150 ms at p95, a sync of dozens of serial requests gets seconds slower. **Recommendation: StrongBox as briefed,
  with that measured ceiling - above it the TEE, chosen once per phone at key creation and shown as a quiet value.**
- **Q3. Bearer-only phones after S2**: keep them working with the orange line until the parent acts, or refuse them
  from some date? **Recommendation: keep them (never lock out); the line is the nudge; the bearer code goes when the
  database has none.**
- **Q4. A revocation after binding**: keep the phone working with the line (§6.3), or refuse it? **Recommendation:
  keep it working, with the line and one `rotate`; refusing locks a phone out with no way back when its keybox stays
  revoked.**
- **Q5. Re-attest periodically** (a rotation every N months to refresh `osPatchLevel`)? **Recommendation: no in this
  round; rotation on demand only.**
- **Q6. A warning on an old security patch?** **Recommendation: no** (a parent can't patch an unsupported phone; per
  "Words in the UI" it is not something wrong to fix) - a quiet value only.
- **Q7. A phone whose chain ends at no Google root (an uncertified phone)**: a parent switch to accept it?
  **Recommendation: no** (no insecure fallback); only if the Jelly Star needs it, and then as its own design.
- **Q8. `LAUNCHER_SIGNATURE_CHECKSUM` as the app check** ties registration to the QR's setting: a server without it
  refuses all keys. **Recommendation: keep it** - one source for "our signing certificate"; a fork sets its own.
