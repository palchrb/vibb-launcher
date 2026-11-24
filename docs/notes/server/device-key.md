# The hardware-bound phone key, S1: observe (design 129)

The design is `docs/design/129-hardware-bound-phone-key.md` (revision 2). S1 is the server alone; the launcher's part
(L1) and the commit (S2) come later. **In S1 the bearer keeps working for every phone**: a key is stored `observed`,
signatures are verified and their failures logged, and nothing can lock a phone out.

## The attestation check (`src/attestation/`)

- `der.rs` - a small DER reader of our own (no new crate): high tag numbers (`[709]`), definite lengths, BOOLEAN as
  0x00/0xFF only, nothing else. `x509.rs` - the certificate's parts (the signed bytes, serial, names, validity, the key, the extensions) and
  `signed_by` for every algorithm pair the chains use (RSA PKCS#1 SHA-256; ECDSA P-256 and P-384 with SHA-256 or
  SHA-384, ring's). `key_description.rs` - the KeyDescription's fields the check reads; unknown tags are skipped, tags
  must be ascending (the Kotlin library refuses `tags_not_in_ascending_order`).
- `mod.rs::verify` - design 129 §3 items 1-10 in order, each failure one word (`Refusal`). No names are compared:
  the signatures bind the chain (code QA L2). The app (tag 709) is read from the software list, or the hardware list
  when only it has one (L3). Times are ASCII digits before anything is sliced (M3; `x509::tests` changes every byte of
  a real chain). It follows Google's Kotlin
  verifier (`github.com/android/keyattestation`): the KeyDescription is the leaf's alone (any certificate above it
  that carries one is refused); the leaf's own validity is never read; a factory chain (its first intermediate names a
  `serialNumber`, under the RSA root) is never refused for an expired certificate; every other certificate is read
  against the server's time. Purposes: SIGN, and VERIFY beside it is taken (Keystore adds it to some keys; the Kotlin
  library's test chains carry `[2, 3]`) - any other purpose is refused.
- `roots.rs` - the two pinned root keys (SHA-256 of the SPKI). `testdata/attestation/roots/` holds the certificates
  from Google's page; `tests::the_pins_are_the_roots` computes the pins from them. A new root comes in a release.
- `status_list.rs` - Google's revocation list: fetched by `main` (never the tests) at start and then by its
  `Cache-Control`, clamped to 1-24 h, failures retried hourly; the last good copy in `data/attestation/status.json`.
  Older than 7 days (or never fetched): registrations get 503 `list_stale` (`retry_after_s` 3600), and Settings shows
  "Googles nøkkelliste er utdatert" once the list aged or a fetch failed (not in a fresh start's first seconds). Each
  good fetch is read against every stored key's serials: `device_keys.revoked_at` set or cleared (§6.3; the key keeps
  working).
- `boot.rs` - step 2's reading from the RootOfTrust (`verified`, `grapheneos`, `unknown`) and the GrapheneOS list
  `grapheneos_boot_keys.json`, written by `scripts/grapheneos-boot-keys.py` (it verifies GrapheneOS's OpenSSH signature
  with `ssh-keygen -Y verify` against the signer it pins, and refuses an older `timestamp`; `--selftest` in
  `server-ci.yml`'s changes job). S1 records and shows the reading, never enforces it.
- The provisioning info (OID ...2.1.30) is checked for its place (the certificate above the leaf) and form (a CBOR
  map). The documented schema has no lost-device flag (read 2026-11-24), so `lost` is a reserved word for now.
- `LAUNCHER_SIGNATURE_CHECKSUM` is the app check (§3 item 10, Q8), decoded to 32 bytes. Unset: a startup warning and
  every registration refused `app` (the phones keep their bearer). A malformed value is ignored with an error, as
  every `ForkConfig` setting is (`config.rs`): never a refused start.

## Keys, routes and the edge (`src/device_key/`)

- Migration 0174: `device_keys` (a random 16-byte key id, the SPKI and point, what the attestation said, the
  registering address, `unusual`, `state`, `via`, `revoked_at`, `last_signed_at`), `device_key_challenges` (one per
  phone, 10 min), `device_addresses` (each phone's bearer addresses, `net::Client::key`, 30 days, pruned by
  `retention`), `sig_clock` (the signature clock's floor), and on `devices` the enrolment code used with a key (for a
  lost answer), the last refusal's reason and a key conflict.
- `KeyIndex` - `key id -> device, point` in memory for every key not `retired`, rebuilt at start and after every key
  write (enrol, register, revoke, delete).
- The edge (`limits::device_edge`): without a valid bearer, a blocked client is refused and the request takes its
  unauthenticated slot before any verify (code QA L6); a `Vibb-Signature` is read from the headers alone - the key id in the index, the
  window (`sig_now - 60 s ..= sig_now + 30 s`), `Vibb-Body-SHA256`, one P-256 verify, then the nonce (recorded only
  after a verify; 4096 a key, then 429). A valid one takes no unauthenticated slot. The body is wrapped in
  `HashingBody` (no buffering, outside the status route's inflation); a mismatch fails the route's extractor and the
  edge answers 400 `Vibb-Auth: bad_body`. An empty body is compared before the route runs. **When the phone's own
  valid bearer came with the request (S1, code QA M2)** nothing acts against it: the body's hash is only logged, a
  full nonce set is no 429, and its refused signature is never counted against the client. The status route inflates
  its own gzip after the whole compressed body was read and hashed (`device_api::report_body`: one member, nothing
  after it, the device limit; L4).
  `tests::device_key::every_device_post_reads_its_body` pins that every POST device route reads its body through an
  extractor.
- The auth layer (`security::require_device_token`): a valid signature authenticates as its phone; a refused one is a
  401 with `Vibb-Auth: <reason>` (every reason but `stale` counted and logged as `device_auth_failed`) - unless the same
  phone's valid bearer came with it (observe: logged, the bearer authenticates). A request with no credential at all
  gets `Vibb-Auth: missing`. Bearer requests note their address (at most hourly per address); signed ones note
  `last_signed_at` (at most every 10 min).
- `Vibb-Time` (`sig_now`, ms) is on every device answer, the edge's own 429/503 included. The signature clock is
  `max(wall clock, stored + 100 s)` (the 10 s write, both windows) at start plus monotonic time, written every 10 s and at shutdown.
- Routes: `GET /api/devices/key`, `GET /api/devices/key/challenge`, `POST /api/devices/key` (32 KiB), `POST
  /api/devices/key/conflict`; the enrolment's `chain`/`proof` (the enrol body limit is 32 KiB). The chain is checked
  before any transaction. A registration is `unusual` when the phone's bearer never came from that address in the 30
  days, at least an hour before the registration's challenge (`ADDRESS_KNOWN_BEFORE`: the registration's own bearer
  calls never make an address known). A conflict report needs a stored key (409 `none` otherwise). Logged, with the
  phone's name: `device_key_bound` (the address, unusual, at setup), `device_key_refused` (throttled),
  `device_key_conflict`.
- A lost enrolment answer (§5.1, code QA H1): at most two repeats, each with a proof whose ECDSA `r` was never seen
  for the code (`devices.enroll_used_proof_rs`, `enroll_repeats`), logged as `device_enrolled` "asked again"; Revoke
  Access and any later enrolment clear the kept code. One live key per phone is a unique index.
- The signature clock's floor after a restart is the stored value + 100 s (the 10 s write and both windows, M1).
- L1's code QA: a key index whose reload after a key write failed three times keeps the other phones' entries, drops
  that phone's (a removed key must never sign) and is **stale**: an unknown key id is then `SigCheck::Unavailable` - a
  503 with `Retry-After` (the phone's own bearer still authenticates in S1), never `unknown_key`, which the phone
  counts towards "lost" - and a request retries the reload at most every 10 s (`retry_if_stale`); a reload that works
  ends it. A valid signature that comes with **another** phone's bearer is refused (`bad_sig`).
- The policy's `device_key` (`{state, key_id?, spki_sha256?, rotate}`, `rotate` always false in S1) is pinned by
  `policy_json_keys_snapshot`.
- A re-enrolment replaces the row's keys, challenge and addresses (another phone); Revoke Access removes the keys;
  deleting a phone cascades and refreshes the index.

## The PWA

About This Phone › Connection's "Innlogging" row is a quiet value: "Eldre innlogging", "Maskinvarenøkkel" (behind
the (i): TEE or StrongBox, the attestation version, the security patch, the system read, the root, revoked or not,
and only what is wrong: revoked, not signed yet, an unusual address), "Nøkkel avvist (grunn)" or "Annen nøkkel
registrert". Nothing is orange in S1 but Settings' two lines: the stale list, and no `LAUNCHER_SIGNATURE_CHECKSUM`
(then no phone's row says "refused" - it is the server's setting, code QA L10).

## The contract

`server/testdata/device_key.json`, written by `scripts/device-key-vectors.py` (a throwaway key from a fake phrase;
`--check` in `server-ci.yml`'s changes job). `tests::device_key::the_contract_rows` runs the crypto, clock, window and
answer rows; `the_contract_routes` and `the_edge_rows` produce the route rows through the real routes. Since L1 it is
byte-identical in `launcher/app/src/test/resources/` (`the_contract_matches_the_launchers_copy`), with the `phone` and
`phone_nudge` rows (the registration flow's table, the launcher's alone to run: `DeviceKeyContractTest`). The
launcher's side is `docs/notes/launcher/security.md`, "The hardware-bound phone key".

## Not in S1

`emulator_keys` (§7.3) waits for the emulator's chain to be read on the gate images; the bound state, `held`,
`device_old_token`, `key_required`, rotation and the step-2 orange line are S2's.
