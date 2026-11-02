# Security code QA - passkeys-1 (5cccaeaa), part A: the cryptographic and protocol core

Scope: `server/src/webauthn.rs`, `server/src/passkeys.rs`, `handlers/passkeys.rs` (only where it drives the core),
`PasskeyConfig`, migration 0116, `testdata/webauthn/`, `scripts/webauthn-rs256-vector.py`, `src/tests/passkeys.rs`,
`webauthn::tests`. Checked against WebAuthn L2/L3 §7.1/§7.2 and design 74 (D1.x, D4.x, D5.x, §7). Read only; no
cargo run (none of the findings needed one: each is plain from the code).

**Verdict: the verifier is sound - no High.** The CBOR reader, COSE parsing, signature checks, clientData and authData
handling, challenge single use and counter atomicity are correct. Two Mediums to fix before step 2: under a shared
`PASSKEY_RP_ID`, a forged Host lets a sibling subdomain relay an assertion (D4.2's guarantee isn't met), and the global
challenge cap can be filled by a signed-out attacker. Counts: **High 0, Medium 2, Low 3, Nit 7.**

---

## Medium

### M1 - A shared `PASSKEY_RP_ID` + a forged Host: a sibling subdomain can relay a sign-in (D4.2 not met)
`server/src/webauthn.rs:977-1013` (`rp_id_for`, `origins_for`), `server/src/passkeys.rs:198-236` (`Ceremony::of`).

`origins_for` adds `https://<request host>` for any host under the RP ID, and that host comes from `Host` (or a
trusted peer's `X-Forwarded-Host`). With `PASSKEY_RP_ID=example.com`, a client that reaches the backend directly
picks the "request's origin" itself:

1. The attacker runs (or has taken over) `wiki.example.com`, and can reach the admin listener directly: loopback (a
   service on the same box), the tailnet, or `ADMIN_PUBLIC=on` with the port reachable. `csrf_guard` lets a
   non-browser POST without `Sec-Fetch-Site`/`Origin` through.
2. `GET /auth/passkey/options` with `Host: wiki.example.com`: `rp_id_for` gives `example.com`, a `login` challenge
   bound to the attacker's own pre-session.
3. The wiki page runs `navigator.credentials.get({rpId: "example.com", challenge, allowCredentials: []})`. The browser
   allows it (the RP ID is a suffix of the page's host), and the parent picks their passkey with Face ID.
4. `POST /auth/passkey` with `Host: wiki.example.com` and the pre-session cookie: `origins_for` returns
   `["https://wiki.example.com"]`, the clientData origin matches, the stored `rp_id` is `example.com`, and the
   signature is valid. The attacker gets a full session (both factors, D1.5).

D4.2 promises exactly this can't happen ("a sibling subdomain under a shared PASSKEY_RP_ID ... can't relay an assertion
it got there"). The test that claims to cover it (`src/tests/passkeys.rs:948`, "A sibling under a shared RP ID
relays nothing") runs with the default config. There the RP ID is `handy.example.com`, so `wiki.example.com` was
never under it: the shared-RP-ID path isn't tested. With the default config (no `PASSKEY_RP_ID`) a forged Host is
harmless, because `finish` requires `row.rp_id == ceremony.rp_id`. The user's stated setup (Q5/Q8:
`PASSKEY_RP_ID` = the PWA's own host) isn't exposed either. The risk is the parent-domain setup the design documents
and DEPLOY.md offers.

**Fix:** when `PASSKEY_RP_ID` is set, don't build an origin from the Host. Accept `https://<PASSKEY_RP_ID>[:port]`
when the host *is* the RP ID, plus `PASSKEY_ORIGINS`. Every other host under it must be listed in `PASSKEY_ORIGINS`
(say so in the start-up line and DEPLOY.md). Without `PASSKEY_RP_ID`, keep today's rule (safe: the RP ID is the host,
and a forged one can't match a stored `rp_id`). Add an integration test with `PASSKEY_RP_ID=example.com`: a passkey
added at `handy.example.com`, then options + POST with `Host: wiki.example.com` and origin `https://wiki.example.com`
are refused.

### M2 - The 1000-entry challenge store can be filled by signed-out clients: every ceremony stops
`server/src/passkeys.rs:90-118` (`issue_at`), `server/src/handlers/passkeys.rs:72-140` (`login_options`).

The global cap is shared by every kind. When it is full, `issue` returns `None`: 503 for sign-in, re-auth and add
alike. The per-session cap of 8 doesn't help against a signed-out client, which sends no cookie and gets a new
pre-session for every call. The only brake is the per-IP ban: 15 counted events per 15 min per `limit_key`, and IPv6
is keyed per /64. About 67 keys per 5-minute TTL fill the store. A /56 has 256 /64s and a /48 has 65 536, so a single
VPS range or a small proxy pool keeps every parent's passkey sign-in, "Confirm with passkey" and Add Passkey down
indefinitely. Each call also writes a session row and a `security_events` row (no argon2 cost, unlike `/login`). In
step 1 the password is still there. In step 2 a passkey-only parent can't sign in at all, and in step 3 the unlock
fails. The precondition is reaching the admin listener: the tailnet by default, or anyone under `ADMIN_PUBLIC=on`
(exposure b).

**Fix (any of these; the first two together are enough):**
- Give `login` entries their own pool (e.g. 500). When it is full, evict the oldest `login` entry instead of
  refusing. Signed-in kinds (`register`/`reauth`, later `unlock`/`switch`) get the rest, which only valid sessions can
  use (at most 8 per session).
- Cap the live `login` challenges per `client_key` (e.g. 3; a 4th evicts that key's oldest). With that, the
  `passkey_started` ban rows aren't needed (see L1), and IPv6 can be counted per /56 for this call.

---

## Low

### L1 - Every sign-in page view counts toward the ban: a family can ban itself
`server/src/handlers/passkeys.rs:94-101`, `server/src/security.rs:590` (`BAN_COUNTED`), `server/static/passkey.js:216`.

Where `isConditionalMediationAvailable()` is true (Chrome, Android Chrome, Safari 16+), `passkey.js` fetches
`/auth/passkey/options` on every load of `/login`. Each fetch is a `passkey_started` row in the ban count, taken back
only by a passkey sign-in. A wrong password re-renders `/login`, which is another fetch: each wrong try now costs 2
of the 15 points, so about 8 wrong passwords in 15 minutes ban the address for an hour (it was 15). Behind Caddy
every parent at home shares one public address. A password sign-in after 15 page views in 15 minutes also ends in a
ban. Rare, but it is a new way for the family to lock itself out, and a passkey-only parent (step 2) can't get round
it.

**Fix:** stop counting options calls in the ban and bound them by the per-key live-challenge cap from M2.
Alternatively, delete the address's `passkey_started` rows on any complete sign-in, and don't fetch on page load
when conditional mediation isn't offered.

### L2 - The counter rule accepts a 0 after a non-zero counter (WebAuthn L3 §7.2 step 22 deviation)
`server/src/webauthn.rs:757-762`, `server/src/passkeys.rs:485` (`store_use`).

L3 treats "either counter non-zero and the new one not greater" as a clone signal. Here an assertion with counter 0
passes against a stored 5 (the store keeps 5). Someone who copied a counter-keeping authenticator's key (a security
key) can always sign with counter 0, and the copy is never flagged. This is what D5.3 wrote ("When both ... are
non-zero"), so it follows the design, but it drops the spec's signal for exactly the authenticators that have
counters. Synced passkeys always send 0 and stay 0, so they are unaffected.

**Fix:** refuse (`Counter`, `passkey_cloned`) when the stored counter is non-zero and the new one is not greater,
0 included. Tighten the `store_use` condition to `(sign_count = 0 AND ?1 = 0) OR sign_count < ?1`. Update D5.3's
wording.

### L3 - An RS256 key that ring will never verify is accepted at registration
`server/src/webauthn.rs:357-368`.

With `attestation: "none"` no signature is checked at registration. The RS256 checks are the modulus bit length
(leading zeros skipped) and `1 <= e.len() <= 8`. ring's `RsaPublicKeyComponents` refuses a modulus with a leading
zero byte, and an exponent that is even, below 3 or above 2^33-1. Such a key is stored as "added" (with a
`passkey_added` push to every parent) and then fails every assertion with `Signature`. Not exploitable (the key is
the parent's own), and D2.1's switch asks for an assertion, so it can't become the only key of a passkey-only
account. It still shows a passkey that never worked.

**Fix:** at parse, refuse `n[0] == 0` and an exponent that is even, below 3 or above 2^33-1 (`Refused::Key`). Add a
case to `a_bad_credential_id_or_key_is_refused`.

---

## Nit

- **N1** `webauthn.rs:261` - the CBOR reader refuses the simple values `true`/`false`. Authenticator extension
  outputs such as `hmac-secret: true`, `credBlob: true` or `largeBlobKey` would make a registration fail with
  `Extensions`. We request none of them, so this is only brittleness against an authenticator that adds one on its
  own. Consider accepting `0xf4`/`0xf5` (inside the dropped extensions map only).
- **N2** `0116_passkeys.sql:20` - `webauthn_user_id` has no UNIQUE index. A collision of 16 random bytes is
  negligible, but a restored or merged database could hold two equal handles. Then `sign_in`'s `fetch_optional` takes
  the first row and the other parent's passkey is refused as `UserHandle`. Migration 0116 is frozen from its first
  push and this branch isn't on `main` yet, so the index can still go into it now.
- **N3** `handlers/passkeys.rs:222` - `session.cycle_id().await.ok()` ignores a failure and goes on to sign in on
  the pre-sign-in session id. Session fixation only on a store error. Refuse (500) instead. `/login` does the same
  today, so fix both.
- **N4** `webauthn.rs:977-985` - with `PASSKEY_ORIGINS` set, `rp_id_for` gives the RP ID at *any* host, an IP
  literal and a missing Host included. That is safe: only the allowlist passes, because the host-derived origin is
  added only for a host under the RP ID. But the sign-in button then shows at an IP address, which D4.1 says gets no
  passkey UI, and the browser's `SecurityError` lands as the red line. Consider `None` for an IP or missing host even
  with `PASSKEY_ORIGINS`.
- **N5** `webauthn.rs:890-903` - `PASSKEY_ORIGINS` accepts `https://host:443` (and `http://localhost:80`). Browsers
  serialize those origins without the default port, so such an entry never matches. Refuse it at start with the
  reason, or normalize it.
- **N6** `passkeys.rs:98-106` - a session at its cap of 8 has its oldest challenge evicted even when the call is then
  refused because the store is full. Check the global cap first.
- **N7** `passkeys.rs:596-610` - a sign-in refused before the signature check (a known credential id whose account
  isn't ready, or a user handle naming another account) is logged under that account's username. Anyone holding a
  credential id could put `passkey_refused` lines on a named parent. Credential ids are shown only to their owner
  (`allowCredentials`/`excludeCredentials`), so the practical risk is nil. Leave the username out of refusals that
  happen before the signature is checked if this matters. (Similarly by design: a passkey sign-in during a TOTP lock
  goes through and `reset_after_login` clears `locked_until`/`lock_count`. That is fine, since the lock guards TOTP
  guessing, but it isn't written in D1.5.)

---

## Checked and fine (no finding)

- **CBOR** (`webauthn.rs:180-262`): definite lengths only; reserved values (28-30) and indefinite (31) refused; the
  argument read is bounds-checked (`checked_add` + `get`); every length is checked against the bytes left
  (`bounded`, `checked_mul`, 2 per map entry) before `to_vec`/`with_capacity`, so a huge length allocates nothing;
  depth at most 4; tags, floats and simple values refused; text must be UTF-8; duplicate keys refused (compared on
  decoded values, so a non-canonical repeat is caught too; quadratic, but bounded by the 4/8 KiB bodies); trailing
  bytes refused (`cbor_exact` for attestationObject and the stored COSE key, `pos != raw.len()` for authData).
  Non-minimal integer encodings are accepted, which is harmless: signatures cover the raw authData, and the stored
  COSE bytes are the registered bytes.
- **COSE**: `alg` is one of the three requested and agrees with `kty`/`crv` (-7 = 2/1, -8 = 1/6, -257 = 3). ES256
  needs x and y of 32 bytes, and the point is checked on P-256 through ring's ECDH peer-key check (no compressed
  points, no identity). Ed25519 is 32 bytes. RS256 is 2048-8192 bits (L3 aside). Verification uses
  `ECDSA_P256_SHA256_ASN1` (DER, which WebAuthn's ES256 uses), `ED25519` and `RSA_PKCS1_2048_8192_SHA256`, over
  `authData || SHA-256(clientDataJSON raw bytes)`.
- **clientDataJSON**: serde refuses duplicate fields and a non-bool `crossOrigin`; `type` is exact; the challenge is
  strict base64url (no padding, canonical trailing bits) and compared as bytes through the store's exact-key take;
  the origin is an exact string in `origins` and its host fits the stored RP ID; `crossOrigin: true` is refused;
  `tokenBinding`/`topOrigin` are ignored (fine with `crossOrigin` false).
- **authData**: the rpIdHash is checked against the **stored** `rp_id` (and `finish` requires the row's `rp_id` to
  equal the request's, so a forged Host can only propose an RP ID, as D4.1 says); UP and UV required at both
  ceremonies; BS without BE refused; BE fixed at registration and BS updated; AT required at registration and refused
  in an assertion; the attested data is read in order, the credential id is 16-1023 bytes and equals `rawId`, and
  `id` equals `rawId`'s base64url; the ED/extensions handling is exact both ways; the AAGUID is ignored.
- **Attestation**: `fmt` and `attStmt` are only type-checked, and nothing in them is trusted (with `attestation:
  "none"` there is nothing to trust; packed self-attestation is ignored, not verified). Registering a key you don't
  hold gains nothing: an assertion needs its signature, and `UNIQUE(credential_id)` blocks taking over an id.
- **Challenges**: 32 bytes from `SystemRandom`; `take` removes under the mutex before comparing (one use, also on a
  mismatch); bound to kind, session id, `(admin, epoch)`, RP ID and route (the re-auth route is checked by
  `reauth_route`, with the target id in it). Tested in parallel (`one_challenge_is_taken_once`).
- **Counter store**: one conditional `UPDATE` (`sign_count < ?1`), so two parallel uses of one value can't both pass
  (`one_counter_value_is_used_once`).
- **userHandle**: required at sign-in; `admin` must be ready and `row.admin_id == admin.id`, so another parent's
  credential can't sign in as you. At re-auth the credential must be the signed-in parent's, and a non-null handle
  must equal their `webauthn_user_id`. `webauthn_user_id` is 16 random bytes, made under the epoch + ready condition.
- **Enumeration**: sign-in options are always `allowCredentials: []`; every refusal shows the same red line; the
  button depends on the host only (D4.3). The early-exit timing reveals only whether a random credential id exists.
- **Pre-session**: cookie `__Host-handy` (Secure, HttpOnly, SameSite=Strict, Path=/); `cycle_id` at sign-in
  (fixation-safe, except N3). The 10-minute expiry applies only until the next request (tower-sessions reapplies the
  layer default), and expired rows are swept hourly. A stale cookie id binds the challenge to that id string, which
  is harmless.
- **`PasskeyConfig`**: upper case, IP, a name without a dot, a trailing dot and the listed public suffixes are refused
  at start; `PASSKEY_ORIGINS` needs `PASSKEY_RP_ID`, and each entry is lowercase https (http only for `localhost` with
  `INSECURE_COOKIES`) with no path, under the RP ID. Decision 6 (an RP ID at any host when `PASSKEY_ORIGINS` is set)
  is safe as written: outside the RP ID only the allowlist passes (N4 is UI only). The unsafe part is the
  Host-derived origin *under* a shared RP ID (M1).
- **Test data**: `testdata/webauthn/rs256.json` has public data only (the COSE key, authData, clientDataJSON, the
  signature, a credential id = SHA-256(n)). `scripts/webauthn-rs256-vector.py` makes the RSA key in-process and never
  writes it. The diff has no PEM/PKCS#8/JWK `d`; the ES256/Ed25519 keys are generated by ring in-process inside
  `#[cfg(test)]`.
- **Body limits**: 4 KiB (assertion) and 8 KiB (credential) are enough for real authenticators, an RSA-4096 key
  included.

Test gaps worth adding with the fixes: the shared-RP-ID relay (M1); the store full at the HTTP level and a signed-in
re-auth still issued while signed-out calls flood it (M2); an RS256 key ring rejects (L3); a counter of 0 after a
non-zero one (L2).

---

## Revision check - 8ce305da (fix round on 5cccaeaa)

Read only: `git diff 5cccaeaa..8ce305da` (webauthn.rs, passkeys.rs, handlers/passkeys.rs, handlers/auth.rs,
security.rs, 0116, the new tests). No cargo run.

**Verdict: mergeable. No High and no Medium is left.** M1 and M2 are fixed, and so are L1-L3 and N1-N6. Nothing
new reaches Medium; the remaining notes are R1 (Low) and R2-R4 (Nits).

| Finding | Status | How it was checked |
|---|---|---|
| **M1** relay through a forged Host | **Fixed** | `origins_for` builds an origin from the Host only when the host *is* the RP ID (`host == rp_id`). `rp_id_for` gives a host under the RP ID the RP ID only when `PASSKEY_ORIGINS` lists it, else `None` (no ceremony). Forged `Host: wiki.example.com`: no RP ID (not listed), or only the list's origins. Forged `Host: example.com` or a listed host: the origins are `https://example.com` and the list, so a wiki origin is refused. Without `PASSKEY_RP_ID` nothing changed (safe). Tested by `a_shared_rp_id_takes_only_its_own_and_the_listed_origins` (integration, with `PASSKEY_RP_ID=example.com`) and in `hosts_and_rp_ids`. |
| **M2** global store exhaustion | **Fixed** | Two pools. Signed-in kinds: 500, refused only when full, 8 per session, and only valid sessions can issue them (a parent is trusted). Sign-in: 500 with the oldest evicted (never refused), 3 per client key (`net::limit_key`: the IPv4 address or the IPv6 /64). Signed-out traffic can no longer refuse re-auth or add (`sign_ins_never_crowd_out_signed_in_ceremonies`, `a_flood_of_sign_ins_leaves_room_for_the_rest`). `make_room` leaves `cap-1` and then inserts: correct. |
| **L1** self-ban | **Fixed** | One counted row per session; a reload, the conditional get and the button get the live challenge back (`live_sign_in`, same session and RP ID only). The row is taken back by the next `/login` POST or `/auth/passkey` POST, each of which counts its own refusal. A client can't use this to dodge the ban: every path that takes the row back without a counted refusal needs a correct password or a valid passkey. The extractor-rejected `/login` (a missing field) doesn't run the handler, so its row stays. |
| **L2** counter 0 after non-zero | **Fixed** | `verify` refuses when the stored count is non-zero and the new one is not higher, 0 included. `store_use`'s condition is `(sign_count = 0 AND ?1 = 0) OR sign_count < ?1`, so the race with a stale read is also refused. Tested. |
| **L3** RS256 keys ring can't use | **Fixed** | A leading zero byte in n refused; 2048-4096 bits (now 4096 at most, for B-L7); e without a leading zero, at most 5 bytes, odd, 3 to 2^33-1. The `acc << 8` fold can't panic (only bits shift out, and the length is checked in the same `&&` chain). Tested. |
| N1 bools | Fixed | Only `0xf4`/`0xf5` (one-byte head) and only inside the ED extensions map. `cbor_exact` still refuses them; tested. |
| N2 unique handle | Fixed | `CREATE UNIQUE INDEX admin_users_webauthn_user_id` (SQLite allows any number of NULLs). See R3. |
| N3 `cycle_id` | Fixed | A failure is a 500 before anything is stored. The passkey sign-in cycles before writing the session; `/login` and `verify_2fa` do the same now. |
| N4 IP host with `PASSKEY_ORIGINS` | Fixed | A missing Host or an IP (`host_of` gives `None`) gets `None`. |
| N5 default port in `PASSKEY_ORIGINS` | Fixed | `:443` (https) and `:80` (http) refused at start. Tested. |
| N6 eviction then refusal | Fixed in effect | When a session at its cap evicts one, the pool's count drops by one, so the issue then succeeds. A refusal only happens with no eviction. |
| N7 | Unchanged (acceptable) | B-N4 refined the refusal code; the username is still logged before the signature is checked, which was fine as noted. |

### What the fix opened or left (all acceptable)

- **R1 (Low) - a distributed flood can still evict a parent's live sign-in challenge.** The sign-in pool evicts its
  oldest entry, so 500 issues inside one parent's ceremony window (the page load to the POST, seconds to minutes)
  push that parent's challenge out. The parent sees "the passkey didn't work" and a retry gets a fresh challenge.
  The per-client cap (3) doesn't help against many keys: each key gives at most 3 live challenges and about 15
  issues per 15 minutes before its ban. Keeping it up therefore takes about 34+ fresh /64s per eviction cycle,
  i.e. sustained traffic from a large IPv6 range or a proxy pool. And it needs a client that reaches the admin
  listener (the tailnet, or `ADMIN_PUBLIC=on`). What it costs:
  - Step 1: passkey sign-in degrades and the password works, and no signed-in ceremony is affected. Acceptable.
  - Step 2: for a passkey-only parent this is a way to keep them out while the flood lasts. Before step 2, consider
    counting the sign-in pool's per-client cap (and the `passkey_started` count) per /56 or /48 for IPv6, or
    letting a sign-in challenge that has already been handed to its session survive eviction for a short grace
    period (e.g. evict the oldest *never re-fetched* first).

  A co-located client (same NAT IPv4, e.g. the kid on the home Wi-Fi behind Caddy with `ADMIN_PUBLIC`) shares the
  parent's 3-per-client slots and can evict the parent's challenge with 3 calls. It could already get that address
  banned with 15 wrong passwords (the ban is per address, as before), so this adds nothing new. Acceptable.
- **R2 (Nit) - L2's stricter rule can lock out an odd provider.** A provider that registers with a non-zero counter
  and then always asserts 0 now gets `passkey_cloned` on every sign-in. L3 leaves the policy to the RP, and none of
  the mainstream providers (iCloud Keychain, Google Password Manager, Windows Hello, security keys) is known to do
  this. In step 1 the password stays, and D2.1's switch assertion would catch it before passkey-only. Worth a line
  in the manual-QA list (try 1Password and Bitwarden).
- **R3 (Nit) - 0116 changed after its first push.** `origin/passkeys-1` had 5cccaeaa, so the "frozen from its first
  push" rule is broken. That only matters for a database that already ran 5cccaeaa's 0116, e.g. a dev or emulator
  server built from the branch (`scripts/dev-rebuild.sh`). sqlx's checksum would refuse to start it. CI's databases
  are throwaway. Confirm no persistent DB ran it, or reset that DB's row in `_sqlx_migrations`. `main` never had it,
  so the change is fine.
- **R4 (Nit)** - `login_options` now saves the session on every call (`session.save()` outside the `id().is_none()`
  branch), including a signed-in session's. Harmless; it only refreshes the row.

**Remaining High/Medium: none.**
